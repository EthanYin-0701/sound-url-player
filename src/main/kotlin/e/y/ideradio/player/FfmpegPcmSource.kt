package e.y.ideradio.player

import e.y.ideradio.resolve.external.ExternalProcessRunner
import e.y.ideradio.resolve.model.AudioStreamRef
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ffmpeg 解码源（设计 §7.1，Runner STREAM 模式）：一次 ffmpeg 子进程解码
 * s16le/44.1k/立体声 PCM，consumer（本类解码线程）独占 stdout，分帧写 [AudioOutput]
 * 并同步 [PcmBuffer]（供可视化）。
 *
 * - 结束/错误经 [onFinished] 上报（幂等；可能由 consumer EOF 或 Runner 看门狗 onExit
 *   触发，调用方需做 generation 校验，见 PlaybackService）。
 * - [cancel]：经 Runner 销毁进程树（暂停/停止/切歌路径在控制线程调用；
 *   配合先 [AudioOutput.closeNow] 唤醒阻塞的 writer）。
 */
class FfmpegPcmSource(
    private val ffmpegExecutable: String,
    private val args: List<String>,
    private val audio: AudioOutput,
    private val pcm: PcmBuffer,
) {
    enum class EndReason { EOF, FAILED, CANCELLED }

    private val runner = ExternalProcessRunner()
    private var session: ExternalProcessRunner.StreamSession? = null
    private val finished = AtomicBoolean(false)
    private var cancelled = false

    /** 播放结束回调（在 consumer / Runner 线程调用；实现须自行切线程并幂等）。 */
    var onFinished: ((EndReason) -> Unit)? = null

    fun start() {
        audio.open()
        session = runner.runStreaming(
            executable = ffmpegExecutable,
            args = args,
            stdoutConsumer = { input -> consume(input) },
            onExit = { result ->
                val reason = when {
                    cancelled -> EndReason.CANCELLED
                    result.exitCode == 0 -> EndReason.EOF
                    else -> EndReason.FAILED
                }
                notifyFinished(reason)
            },
        )
    }

    /** 取消本次解码（暂停/停止/切歌）。控制线程调用；先 audio.closeNow() 唤醒 writer。 */
    fun cancel() {
        cancelled = true
        session?.cancel()
    }

    private fun consume(input: InputStream) {
        val frameBytes = 4 // s16le 立体声
        val framesPerChunk = 2048
        val buf = ByteArray(framesPerChunk * frameBytes)
        val left = FloatArray(framesPerChunk)
        val right = FloatArray(framesPerChunk)
        var naturalEnd = false
        try {
            while (!audio.isClosed()) {
                var filled = 0
                // 填满一块；读到 EOF 则退出
                var eof = false
                while (filled < buf.size) {
                    val n = input.read(buf, filled, buf.size - filled)
                    if (n < 0) { eof = true; break }
                    filled += n
                }
                if (eof && filled == 0) { naturalEnd = true; break }
                if (filled == 0) break
                val frames = filled / frameBytes
                for (i in 0 until frames) {
                    val off = i * frameBytes
                    val l = ((buf[off].toInt() and 0xFF) or (buf[off + 1].toInt() shl 8)).toShort()
                    val r = ((buf[off + 2].toInt() and 0xFF) or (buf[off + 3].toInt() shl 8)).toShort()
                    left[i] = l / 32768f
                    right[i] = r / 32768f
                }
                if (audio.isClosed()) break
                audio.writeFrames(left, right, frames)
                if (pcm.totalFrames >= 0) pcm.append(left, right, frames)
            }
        } catch (t: Throwable) {
            // 声卡被 close 唤醒 writer 抛 IllegalStateException 等 → 视为被取消/中断
            notifyFinished(if (cancelled) EndReason.CANCELLED else EndReason.FAILED)
        } finally {
            audio.close()
            if (!finished.get()) {
                notifyFinished(
                    when {
                        cancelled -> EndReason.CANCELLED
                        naturalEnd -> EndReason.EOF
                        else -> EndReason.FAILED
                    }
                )
            }
        }
    }

    private fun notifyFinished(reason: EndReason) {
        if (finished.compareAndSet(false, true)) {
            onFinished?.invoke(reason)
        }
    }
}
