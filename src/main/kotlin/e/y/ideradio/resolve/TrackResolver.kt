package e.y.ideradio.resolve

import e.y.ideradio.resolve.model.MediaPlatform
import e.y.ideradio.resolve.model.ResolvedTrack

/**
 * 链接解析器（设计 §6）：**阻塞式**，仅供后台线程调用（MVP 不引入协程）。
 *
 * 预览解析与提交解析共用同一实现；区别只在于结果落到会话草稿还是全局缓存
 * （由上层 [ResolveManager] / Configurable 决定，见设计 §6.5）。
 */
interface TrackResolver {
    val platform: MediaPlatform

    /** 校验/提取视频 ID，支持站内短链；无法识别返回 null。 */
    fun extractId(url: String): String?

    /**
     * 解析链接 → [ResolvedTrack]（含临时直链与请求头，仅内存）。
     * 失败抛 [ResolveException]（带 [ResolveErrorKind] 分类）。
     */
    fun resolve(url: String): ResolvedTrack = resolve(url) { false }

    /**
     * 可取消版解析：调用方可在 [cancelled] 变为 true 时让在途 yt-dlp 进程被终止
     * （v0.8，评审 #5 —— 供 LOADING 期间 stop/切源取消解析）。
     */
    fun resolve(url: String, cancelled: () -> Boolean): ResolvedTrack
}
