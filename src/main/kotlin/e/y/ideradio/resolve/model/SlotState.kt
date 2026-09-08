package e.y.ideradio.resolve.model

/**
 * 单平台"槽位"持久化 bean（设计 §4.1）：**唯一允许落盘的数据**。
 *
 * - 可变属性 + 默认值：可被 IntelliJ XML 序列化器写入；旧状态缺字段由默认值兜底。
 * - 只保存用户配置与解析出的元数据；**绝不包含临时流地址或请求头**（运行时流信息
 *   在 [ResolvedTrack]/[AudioStreamRef]，仅内存且脱敏，见设计 §4.2/§7.1）。
 * - 不含 platform 字段：两个固定槽位由 `RadioSettingsState.youtube/bilibili` 表达。
 */
class SlotState {
    /** 用户粘贴的原始链接（唯一必填输入）。 */
    var sourceUrl: String = ""

    /** 解析成功后的标题（空 = 尚未解析成功）。 */
    var title: String = ""

    /** 时长（秒）；-1 = 未知。 */
    var durationSeconds: Long = -1

    /** 上次解析成功时间（epoch millis）；-1 = 从未成功。 */
    var lastResolvedAtEpochMs: Long = -1

    /** 是否已具备可播放的元数据（已解析成功且非空 URL）。 */
    fun isPlayable(): Boolean = sourceUrl.isNotBlank() && title.isNotBlank()
}
