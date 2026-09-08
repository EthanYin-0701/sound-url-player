package e.y.ideradio.resolve.model

/**
 * 运行时解析结果（**仅内存**，设计 §4.2）：包含临时直链与请求头，
 * 不得交给任何持久化组件，也不得写入日志/错误提示。
 *
 * @param sourceUrl 来源链接（用于与槽位一致性校验 / 版本号防迟到覆盖）
 * @param durationSeconds 时长（秒）；-1 = 未知（yt-dlp 未给出 duration）
 * @param resolvedAtEpochMs 解析时刻（epoch millis），用于判断直链是否超过 TTL
 */
data class ResolvedTrack(
    val sourceUrl: String,
    val title: String,
    val durationSeconds: Long,
    val stream: AudioStreamRef,
    val resolvedAtEpochMs: Long,
)
