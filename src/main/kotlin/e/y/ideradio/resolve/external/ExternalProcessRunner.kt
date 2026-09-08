package e.y.ideradio.resolve.external

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 统一外部子进程执行器（设计 §6.6，v0.4 双模式定稿）。
 *
 * 两种互斥模式，stdout 所有权在启动时一次确定：
 * - [runCaptured]（CAPTURE）：yt-dlp 等**有界任务**。Runner 独占 stdout+stderr：
 *   双线程并发读取（防管道写满死锁）、字节上限、整体硬超时。
 * - [runStreaming]（STREAM）：ffmpeg 等**长时流式任务**。Runner 只拥有进程生命周期与
 *   stderr（上限 256 KiB）；stdout 的唯一所有者是调用方提供的 consumer —— Runner 不读、
 *   不缓冲、不设总字节上限；仅看门狗（按 consumer 心跳判断空转）。
 *
 * 终止（两模式一致）：先 destroy 进程树（descendants），等待 [FORCE_KILL_DELAY_MS] 后
 * 未退出则 destroyForcibly；所有结束路径在 finally 中关闭流并结束线程。
 */
class ExternalProcessRunner {

    /** CAPTURE 结果。 */
    data class ExitResult(
        val exitCode: Int,
        val stdout: ByteArray,
        val stderr: String,
        val timedOut: Boolean = false,
        val cancelled: Boolean = false,
        val outputTruncated: Boolean = false,
    )

    /** STREAM 会话句柄：consumer 读取期间调用 [heartbeat]；可随时 [cancel]。 */
    class StreamSession internal constructor(
        private val process: Process,
        private val onCancel: () -> Unit,
    ) {
        private val cancelled = AtomicBoolean(false)
        private val lastHeartbeat = AtomicLong(System.nanoTime())
        internal val watchdogMillis: Long = WATCHDOG_IDLE_MS

        fun heartbeat() {
            lastHeartbeat.set(System.nanoTime())
        }

        internal fun idleMillis(): Long {
            return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - lastHeartbeat.get())
        }

        fun isCancelled(): Boolean = cancelled.get()

        fun cancel() {
            if (cancelled.compareAndSet(false, true)) {
                onCancel()
            }
        }

        fun awaitExit(timeoutMs: Long): Boolean = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
    }

    /**
     * CAPTURE：运行有界命令并完整捕获输出。
     *
     * @param executable 可执行文件（已经 ExternalToolLocator 定位）
     * @param args 参数数组（ProcessBuilder 直传，不经 shell）
     * @param cancelled 取消谓词：等待期间轮询，变为 true 时销毁进程树（v0.8）
     */
    fun runCaptured(
        executable: String,
        args: List<String>,
        timeoutMillis: Long = CAPTURE_TIMEOUT_MS,
        stdoutLimit: Int = DEFAULT_STDOUT_LIMIT,
        stderrLimit: Int = DEFAULT_STDERR_LIMIT,
        environment: Map<String, String> = emptyMap(),
        cancelled: () -> Boolean = { false },
    ): ExitResult {
        val pb = ProcessBuilder(listOf(executable) + args)
        pb.environment().putAll(environment)
        val process = pb.start()

        val out = ByteArrayOutputStream()
        val errBuf = ByteArrayOutputStream()
        val outTruncated = AtomicBoolean(false)
        val errTruncated = AtomicBoolean(false)

        val outThread = Thread { drain(process.inputStream, out, stdoutLimit, outTruncated) }
        val errThread = Thread { drain(process.errorStream, errBuf, stderrLimit, errTruncated) }
        outThread.isDaemon = true
        errThread.isDaemon = true
        outThread.start()
        errThread.start()

        var timedOut = false
        var cancelledFlag = false
        try {
            process.outputStream.close()
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
            while (true) {
                if (process.waitFor(250, TimeUnit.MILLISECONDS)) break
                if (cancelled()) {
                    cancelledFlag = true
                    destroyTree(process)
                    process.waitFor(FORCE_KILL_DELAY_MS, TimeUnit.MILLISECONDS)
                    break
                }
                if (System.nanoTime() >= deadline) {
                    timedOut = true
                    destroyTree(process)
                    process.waitFor(FORCE_KILL_DELAY_MS, TimeUnit.MILLISECONDS)
                    break
                }
            }
        } finally {
            outThread.join(JOIN_MS)
            errThread.join(JOIN_MS)
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
        }

        val stderr = errBuf.toString(Charsets.UTF_8)
        return ExitResult(
            exitCode = process.exitValue(),
            stdout = out.toByteArray(),
            stderr = stderr,
            timedOut = timedOut,
            cancelled = cancelledFlag,
            outputTruncated = outTruncated.get() || errTruncated.get(),
        )
    }

    /**
     * STREAM：启动长时进程并把 stdout 交给 [stdoutConsumer] 独占消费。
     *
     * Runner 负责 stderr 排空（错误归类）与进程生命周期；consumer 每次读到数据应调用
     * 返回会话的 [StreamSession.heartbeat]，看门狗据此判断空转并自动取消。
     */
    fun runStreaming(
        executable: String,
        args: List<String>,
        stdoutConsumer: (InputStream) -> Unit,
        stderrLimit: Int = DEFAULT_STDERR_LIMIT,
        environment: Map<String, String> = emptyMap(),
        onExit: (ExitResult) -> Unit = {},
    ): StreamSession {
        val pb = ProcessBuilder(listOf(executable) + args)
        pb.environment().putAll(environment)
        val process = pb.start()
        process.outputStream.close()

        val errBuf = ByteArrayOutputStream()
        val errTruncated = AtomicBoolean(false)
        val stderrThread = Thread { drain(process.errorStream, errBuf, stderrLimit, errTruncated) }
        stderrThread.isDaemon = true
        stderrThread.start()

        val exitRef = AtomicBoolean(false)
        val session = StreamSession(process) {
            // 取消回调：销毁进程树 + 强制终止兜底
            if (exitRef.compareAndSet(false, true)) {
                destroyTree(process)
                process.waitFor(FORCE_KILL_DELAY_MS, TimeUnit.MILLISECONDS)
            }
        }

        // consumer 线程：stdout 唯一消费者（Runner 不读 stdout）
        // 心跳包装：consumer 读到的每个有数据块自动刷新 session 心跳，
        // 外部 consumer 无需（也无法）访问 session（设计 §6.6 空转看门狗依据）。
        val heartbeatStream = object : java.io.InputStream() {
            override fun read(): Int =
                process.inputStream.read().also { if (it >= 0) session.heartbeat() }

            override fun read(b: ByteArray, off: Int, len: Int): Int =
                process.inputStream.read(b, off, len).also { if (it > 0) session.heartbeat() }

            override fun close() {
                runCatching { process.inputStream.close() }
            }
        }
        val consumerThread = Thread {
            try {
                stdoutConsumer(heartbeatStream)
            } catch (t: Throwable) {
                // consumer 异常：终止进程树（设计 §6.6 触发方 b）
                session.cancel()
            } finally {
                if (exitRef.compareAndSet(false, true)) {
                    process.destroy()
                }
                runCatching { heartbeatStream.close() }
            }
        }
        consumerThread.isDaemon = true
        consumerThread.start()

        // 看门狗：进程自然退出或取消后停止
        val watchdog: ScheduledFuture<*> = watchdogExecutor.scheduleAtFixedRate({
            val exited = !process.isAlive
            val idleTooLong = !session.isCancelled() && session.idleMillis() > session.watchdogMillis
            if (exited || idleTooLong) {
                if (exitRef.compareAndSet(false, true)) {
                    if (idleTooLong) destroyTree(process)
                    process.waitFor(FORCE_KILL_DELAY_MS, TimeUnit.MILLISECONDS)
                }
                stderrThread.join(JOIN_MS)
                onExit(
                    ExitResult(
                        exitCode = runCatching { process.exitValue() }.getOrDefault(-1),
                        stdout = ByteArray(0),
                        stderr = errBuf.toString(Charsets.UTF_8),
                        outputTruncated = errTruncated.get(),
                    )
                )
                throw WatchdogStopException()
            }
        }, WATCHDOG_PERIOD_MS, WATCHDOG_PERIOD_MS, TimeUnit.MILLISECONDS)

        return session
    }

    private class WatchdogStopException : RuntimeException()

    /** 销毁进程树：先 descendants 后父进程，超时后强制终止。 */
    private fun destroyTree(process: Process) {
        runCatching {
            process.descendants().forEach { it.destroy() }
            process.destroy()
        }
        if (!process.waitFor(FORCE_KILL_DELAY_MS, TimeUnit.MILLISECONDS)) {
            runCatching {
                process.descendants().forEach { it.destroyForcibly() }
                process.destroyForcibly()
            }
        }
    }

    /** 排空输入流到 [sink]；超过 [limit] 后继续读但丢弃（防进程写满死锁），并置截断标记。 */
    private fun drain(input: InputStream, sink: ByteArrayOutputStream, limit: Int, truncated: AtomicBoolean) {
        val buf = ByteArray(8192)
        var total = 0
        try {
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total <= limit) {
                    sink.write(buf, 0, n)
                } else {
                    if (!truncated.get()) truncated.set(true)
                    // 超出部分丢弃，仅继续消费以解除背压
                }
            }
        } catch (_: Throwable) {
            // 流被关闭（正常收尾路径）
        } finally {
            runCatching { input.close() }
        }
    }

    companion object {
        const val CAPTURE_TIMEOUT_MS = 30_000L
        const val FORCE_KILL_DELAY_MS = 2_000L
        const val JOIN_MS = 2_000L
        const val WATCHDOG_IDLE_MS = 10_000L
        const val WATCHDOG_PERIOD_MS = 1_000L
        const val DEFAULT_STDOUT_LIMIT = 8 * 1024 * 1024
        const val DEFAULT_STDERR_LIMIT = 256 * 1024

        private val watchdogExecutor = Executors.newScheduledThreadPool(1) { r ->
            Thread(r, "IDE-Radio-Watchdog").apply { isDaemon = true }
        }
    }
}
