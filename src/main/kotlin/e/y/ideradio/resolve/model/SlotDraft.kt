package e.y.ideradio.resolve.model

/**
 * 设置页会话草稿（设计 §4.2/§5.2/§6.5）：仅存在于当前 `Configurable` 生命周期内。
 *
 * 「解析预览」的结果只写到这里：不进入全局 `ResolvedTrack` 缓存、不更新
 * `selectedPlatform`、不启用正式播放；Apply 时才"转正"，Cancel/关闭设置页即丢弃。
 */
data class SlotDraft(
    val platform: MediaPlatform,
    /** 草稿中的 URL（可能尚未 Apply/持久化）。 */
    val draftUrl: String,
    /** 预览解析结果（成功时非空）；仅 UI 展示用。 */
    val preview: ResolvedTrack? = null,
    /** 预览失败的可读错误文案（成功时为空）。 */
    val errorMessage: String? = null,
)
