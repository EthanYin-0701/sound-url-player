package e.y.ideradio.player

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import e.y.ideradio.resolve.ResolveManager
import e.y.ideradio.resolve.TrackResolver
import e.y.ideradio.resolve.YtDlpResolver
import e.y.ideradio.resolve.external.ExternalToolLocator
import e.y.ideradio.resolve.external.ToolLookup
import e.y.ideradio.resolve.model.AudioStreamRef
import e.y.ideradio.resolve.model.MediaPlatform
import e.y.ideradio.resolve.model.ResolvedTrack
import e.y.ideradio.settings.RadioSettingsState
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * 本次播放会话的显式来源覆盖（**仅内存**，设计 §5 会话草稿 + 修复方案 B）：
 * 由调用方（设置页草稿播放）显式传入，覆盖持久化槽位 URL；用完随会话消亡。
 *
 * @param url 草稿来源链接（重解析/TTL 过期时用，而非槽位 URL）
 * @param track 预览得到的已解析结果（可为 null；非空且未过期时直接复用）
 */
data class PlaybackSource(
    val url: String,
    val track: ResolvedTrack?,
)

/**
 * 播放会话服务（application 级单例；设计 §3/§5.4/§7，v0.6 定稿）。
 *
 * 关键约束：
 * - 所有命令（[selectPlatform]/[play]/[pause]/[resume]/[stop]）提交到**单线程控制
 *   executor** 串行执行，杜绝多窗口并发操作打乱状态机；
 * - 每次会话携带递增 [generation]，迟到的旧进程退出回调 generation 不匹配即被忽略；
 * - [selectedPlatform] 只读，变更只能经原子的 [selectPlatform] / [play]；
 * - YouTube / Bilibili 互斥（方案 A）：切换激活源时先停止旧会话并清理，新源进入 IDLE
 *   （不自动开播）。
 *
 * 播放管线（M1-H2）：取 [ResolveManager] 缓存（过期自动重解析）→ ffmpeg STREAM
 * （[FfmpegPcmSource] 独占 stdout）→ [AudioOutput] 声卡 → 同步 [PcmBuffer]（可视化）。
 * 暂停 = 终止 ffmpeg + 记录进度（[pausedPositionSeconds]）；恢复 = `-ss` 续播。
 *
 * 事件监听器回调运行在控制 executor 线程，UI 侧需自行切回 EDT（invokeLater）。
 */
class PlaybackService {

    private val resolveManager: ResolveManager = service()
    private val settings: RadioSettingsState = service()

    /** 当前播放状态。仅控制线程写；读取 volatile。 */
    @Volatile
    var state: PlaybackState = PlaybackState.IDLE
        private set

    /** 激活源（只读）。null = 重启后尚未选择（两栏均未激活、互不灰化）。 */
    @Volatile
    var selectedPlatform: MediaPlatform? = null
        private set

    /** 当前播放曲目（仅内存对象）。 */
    @Volatile
    var active: Pair<MediaPlatform, ResolvedTrack>? = null
        private set

    /** PCM 环形缓冲（可视化数据源，M2 使用；解码线程写入）。 */
    val pcmBuffer: PcmBuffer = PcmBuffer()

    /** 会话代数：每次启动/切换播放会话递增，用于丢弃旧回调。 */
    val generation: Long
        get() = generationCounter.get()

    private val generationCounter = AtomicLong(0L)
    private val listeners = CopyOnWriteArrayList<(PlaybackEvent) -> Unit>()

    private val controlExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "IDE-Radio-Control").apply { isDaemon = true }
    }

    // 当前会话资源（仅控制线程访问）
    private var currentSource: FfmpegPcmSource? = null
    private var currentAudio: AudioOutput? = null
    private var pausedPositionSeconds: Double = 0.0

    /**
     * 本次播放会话的显式来源覆盖（修复方案 B3-1，仅控制线程访问）：
     * 设置页「预览成功即播」时由 [play] 写入；暂停/恢复/循环重播沿用，保证第二遍仍播
     * 草稿 URL；[stop]/[selectPlatform]/无覆盖的 [play]/[clearSourceOverride] 清除。
     * 注意：不在 [stopInternal] 里清 —— [startPlayback] 开头会调它做会话清理，循环与
     * 恢复路径需要覆盖继续存活。
     */
    private var sourceOverride: Pair<MediaPlatform, PlaybackSource>? = null

    /** 本次 ffmpeg 会话的起始播放位置（秒）——恢复续播时传入，供进度累积（高4）。 */
    private var sessionStartSeconds: Double = 0.0

    /** 播放进度周期性广播（高5）；懒启动，仅播放时运行。 */
    private val positionTicker: java.util.concurrent.ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "IDE-Radio-Position").apply { isDaemon = true }
        }
    private var positionTickerRunning = false

    // ---- 命令入口（全部经控制 executor 串行） ----

    /** 原子切换激活源（设计 §5.4/§7.2）：停旧 → 清理 → 切新（IDLE）→ 广播。不自动开播。 */
    fun selectPlatform(platform: MediaPlatform) = submit {
        if (selectedPlatform == platform && state == PlaybackState.IDLE && currentSource == null) {
            return@submit
        }
        stopInternal()
        sourceOverride = null // 切源丢弃草稿会话（方案 B3-1）
        selectedPlatform = platform
        active = null
        setState(PlaybackState.IDLE)
        fire(PlaybackEvent.TrackChanged(platform, null))
    }

    /**
     * 播放指定平台（隐含激活 + 从 [positionSeconds] 或开头开始）。
     *
     * @param override 显式来源覆盖（设置页"预览结果即播"用，方案 B2）；为 null 时走
     *   持久化槽位 URL 的正常路径。非 null 会把草稿存为会话覆盖（循环/恢复沿用）。
     */
    fun play(platform: MediaPlatform, positionSeconds: Double = 0.0, override: PlaybackSource? = null) = submit {
        // 显式覆盖进入本会话；无覆盖则清掉（可能残留的旧草稿会话被新的普通播放取代）
        sourceOverride = override?.let { platform to it }
        startPlayback(platform, positionSeconds)
    }

    /** 暂停：终止 ffmpeg 并记录进度（高4：进度 = 会话起始 + 实际已播放，累积正确）。 */
    fun pause() = submit {
        if (state != PlaybackState.PLAYING) return@submit
        val audio = currentAudio
        val pos = sessionStartSeconds + (audio?.playedSeconds() ?: 0.0)
        teardownSession() // 先 cancel 进程树，再 closeNow 唤醒 writer
        pausedPositionSeconds = pos
        fire(PlaybackEvent.Position(pos.toLong()))
        setState(PlaybackState.PAUSED)
    }

    /** 恢复：从记录位置续播。 */
    fun resume() = submit {
        if (state != PlaybackState.PAUSED) return@submit
        val platform = selectedPlatform ?: return@submit
        val pos = pausedPositionSeconds
        pausedPositionSeconds = 0.0
        startPlayback(platform, pos)
    }

    fun togglePause() {
        if (state == PlaybackState.PLAYING) pause() else if (state == PlaybackState.PAUSED) resume()
    }

    /** 停止：终止并清进度；保留 selectedPlatform（另一栏继续灰，直到显式切换）。 */
    fun stop() = submit {
        stopInternal()
        sourceOverride = null // 停止即丢弃草稿会话（方案 B3-1）
        pausedPositionSeconds = 0.0
        fire(PlaybackEvent.TrackChanged(selectedPlatform, null))
        fire(PlaybackEvent.Position(0L))
    }

    /**
     * 清除指定平台的会话来源覆盖（方案 B3-3）：Apply/提交解析成功后调用，
     * 避免"槽位已是新 URL、播放仍走旧草稿覆盖"的分叉。无匹配覆盖时为空操作。
     */
    fun clearSourceOverride(platform: MediaPlatform) = submit {
        if (sourceOverride?.first == platform) sourceOverride = null
    }

    // ---- 监听 ----

    fun addListener(l: (PlaybackEvent) -> Unit) {
        listeners.add(l)
    }

    fun removeListener(l: (PlaybackEvent) -> Unit) {
        listeners.remove(l)
    }

    // ---- 内部 ----

    private fun submit(action: () -> Unit) {
        controlExecutor.execute(action)
    }

    /** 内部停止：清理当前会话资源；不回退 selectedPlatform。调用方须在控制线程。 */
    private fun stopInternal() {
        generationCounter.incrementAndGet() // 使旧会话回调失效
        teardownSession()
        active = null
        pcmBuffer.clear()
        sessionStartSeconds = 0.0
        setState(PlaybackState.IDLE)
    }

    /** 拆除当前播放会话（进程/声卡/引用）。须在控制线程。 */
    private fun teardownSession() {
        val source = currentSource
        currentSource = null
        val audio = currentAudio
        currentAudio = null
        source?.cancel()       // 先销毁进程树（stdout EOF → consumer 退出）
        audio?.closeNow()      // 再唤醒阻塞在 write 的 writer（v0.6 低优8）
    }

    /**
     * 启动播放（内部；须在控制线程；v0.8 异步化，评审 #5）。
     *
     * 控制线程只负责：停旧会话、切激活源、置 LOADING、**发起**后台解析；最长 30s 的
     * yt-dlp 解析与 ffmpeg 定位放独立后台线程（cancelled 绑定当前 generation，LOADING
     * 期间 stop/切源会令其立即取消）。解析完成带 generation 提交回控制线程：不匹配则
     * 丢弃（已被 stop/切源取代），匹配才启动 ffmpeg。
     */
    private fun startPlayback(platform: MediaPlatform, positionSeconds: Double) {
        // 停旧会话并开启新代数
        stopInternal()
        if (selectedPlatform != platform) {
            selectedPlatform = platform
            active = null
            fire(PlaybackEvent.TrackChanged(platform, null))
        }
        val gen = generationCounter.get()
        // 方案 B：本会话显式来源覆盖（草稿播放）。在控制线程快照，供后台取流分支使用；
        // 快照后即使被后续 stop/切源清除也不影响本次已发起的会话。
        val override = sourceOverride?.takeIf { it.first == platform }?.second
        setState(PlaybackState.LOADING)

        val resolver = resolverFor(platform)
        ApplicationManager.getApplication().executeOnPooledThread {
            // 后台：取流（缓存命中即返回；否则跑 yt-dlp，可被取消）
            val cancelled = { gen != generationCounter.get() }
            val track = if (override != null) {
                // 草稿来源：新鲜直接用；过期按草稿 URL 重解析；绝不 commit 进全局缓存（B2）
                resolveDraft(override, resolver, cancelled)
            } else {
                resolveManager.obtainForPlayback(platform, { url, c ->
                    resolver.resolve(url, c)
                }, cancelled)
            }
            // 后台：ffmpeg 定位（三态；仅 Found 可用）
            val ffmpeg = if (track != null) {
                (ExternalToolLocator.lookupFfmpeg(settings.ffmpegPath) as? ToolLookup.Found)?.path
            } else null

            submit {
                if (gen != generationCounter.get()) return@submit // 期间被 stop/切换取代
                if (track == null || ffmpeg == null) {
                    active = null
                    setState(PlaybackState.ERROR)
                    fire(PlaybackEvent.TrackChanged(platform, null))
                    return@submit
                }
                startFfmpeg(platform, track, ffmpeg, positionSeconds, gen)
            }
        }
    }

    /** 在控制线程启动 ffmpeg（STREAM）会话（前置条件：已拿到 track 与 ffmpeg 路径）。 */
    private fun startFfmpeg(
        platform: MediaPlatform,
        track: ResolvedTrack,
        ffmpeg: String,
        positionSeconds: Double,
        gen: Long,
    ) {
        val args = buildFfmpegArgs(track.stream, positionSeconds)
            ?: run {
                // 关键头非法等 → ERROR（HeadersSanitizer 已抛）
                active = null
                setState(PlaybackState.ERROR)
                return
            }

        sessionStartSeconds = positionSeconds // 供 pause/Position 累积（高4）
        val audio = AudioOutput()
        val source = FfmpegPcmSource(ffmpeg, args, audio, pcmBuffer)
        source.onFinished = { reason -> onSourceFinished(gen, reason) }
        currentAudio = audio
        currentSource = source
        active = platform to track

        try {
            source.start()
            setState(PlaybackState.PLAYING)
            // 高5：广播真实曲目，供 Tool Window/设置页显示标题；并懒启动进度广播
            fire(PlaybackEvent.TrackChanged(platform, track))
            startPositionTickerIfNeeded()
        } catch (e: Exception) {
            audio.closeNow()
            currentSource = null
            currentAudio = null
            active = null
            setState(PlaybackState.ERROR)
        }
    }

    /** 懒启动 500ms 周期进度广播；仅在 PLAYING 时发送（高5）。 */
    private fun startPositionTickerIfNeeded() {
        if (positionTickerRunning) return
        positionTickerRunning = true
        positionTicker.scheduleWithFixedDelay(
            {
                submit {
                    if (state == PlaybackState.PLAYING) {
                        val audio = currentAudio
                        val pos = sessionStartSeconds + (audio?.playedSeconds() ?: 0.0)
                        fire(PlaybackEvent.Position(pos.toLong()))
                    }
                }
            },
            500, 500, java.util.concurrent.TimeUnit.MILLISECONDS,
        )
    }

    /** 解码结束回调（consumer / Runner 线程）；提交回控制线程并校验代数。 */
    private fun onSourceFinished(gen: Long, reason: FfmpegPcmSource.EndReason) {
        submit {
            if (gen != generationCounter.get()) return@submit
            currentSource = null
            currentAudio = null
            pcmBuffer.clear()
            when (reason) {
                FfmpegPcmSource.EndReason.EOF -> {
                    val platform = selectedPlatform
                    if (platform != null && settings.loopEnabled) {
                        // 循环：从头重播（直链过期会静默重解析）
                        startPlayback(platform, 0.0)
                    } else {
                        active = null
                        sessionStartSeconds = 0.0
                        setState(PlaybackState.IDLE)
                        fire(PlaybackEvent.TrackChanged(platform, null))
                        fire(PlaybackEvent.Position(0L))
                    }
                }
                FfmpegPcmSource.EndReason.CANCELLED -> {
                    // 已由 pause/stop/select 处理；保持当前状态（PAUSED/IDLE）
                }
                FfmpegPcmSource.EndReason.FAILED -> {
                    active = null
                    sessionStartSeconds = 0.0
                    setState(PlaybackState.ERROR)
                }
            }
        }
    }

    private fun resolverFor(platform: MediaPlatform) =
        YtDlpResolver(platform, { settings.ytDlpPath }) { settings.cookiesFromBrowser to settings.cookiesFilePath }

    /**
     * 草稿来源取流（方案 B2，后台线程调用）：
     * - 覆盖自带 track 且未过期（TTL 30min）→ 直接复用；
     * - 否则按 [PlaybackSource.url]（草稿 URL，非槽位 URL）重解析；
     * - 无论哪条路径都**不调 [ResolveManager.commit]**：草稿结果不得进全局缓存，
     *   否则 Cancel 设置页后 Tool Window 侧会莫名沿用未保存的链接。
     * 返回 null = 解析失败/被取消（调用方进入 ERROR）。
     */
    private fun resolveDraft(
        override: PlaybackSource,
        resolver: TrackResolver,
        cancelled: () -> Boolean,
    ): ResolvedTrack? {
        override.track?.let { if (resolveManager.isFresh(it)) return it }
        return runCatching { resolver.resolve(override.url, cancelled) }.getOrNull()
    }

    /**
     * 构造 ffmpeg STREAM 参数：`-ss`（续播，输入侧）→ `-headers`（已校验）→ `-i 直链`
     * → s16le 立体声 44.1k 输出 stdout。
     * 关键头非法时抛 [IllegalArgumentException]（由调用方转为 ERROR）。
     */
    private fun buildFfmpegArgs(stream: AudioStreamRef, positionSeconds: Double): List<String>? {
        val args = mutableListOf<String>()
        args += "-hide_banner"
        args += "-loglevel"
        args += "error"
        if (positionSeconds > 0) {
            args += "-ss"
            args += positionSeconds.toLong().toString()
        }
        val headers = try {
            HeadersSanitizer.sanitize(stream.headers)
        } catch (e: IllegalArgumentException) {
            return null
        }
        val headerArg = HeadersSanitizer.toFfmpegHeaderArgument(headers)
        if (headerArg.isNotEmpty()) {
            args += "-headers"
            args += headerArg
        }
        args += "-i"
        args += stream.url
        args += "-f"
        args += "s16le"
        args += "-ac"
        args += "2"
        args += "-ar"
        args += "44100"
        args += "-"
        return args
    }

    private fun setState(next: PlaybackState) {
        if (state == next) return
        state = next
        fire(PlaybackEvent.StateChanged(next))
    }

    private fun fire(event: PlaybackEvent) {
        for (l in listeners) {
            runCatching { l(event) }
        }
    }

    /** IDE 退出/插件卸载时调用：停止并释放控制线程与进度广播线程。 */
    fun dispose() {
        controlExecutor.execute { stopInternal() }
        controlExecutor.shutdown()
        positionTicker.shutdownNow()
    }
}
