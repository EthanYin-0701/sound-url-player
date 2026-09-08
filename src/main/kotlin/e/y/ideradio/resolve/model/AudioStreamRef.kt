package e.y.ideradio.resolve.model

/**
 * 音频流引用：短时有效（TTL 约 30–60 分钟），**永不落盘、不进日志**（脱敏原则见设计 §7.1）。
 *
 * @param url 临时直链（含签名参数，属敏感信息）
 * @param headers 优先取自所选 format 的 http_headers（经 CR/LF/NUL 校验），平台固定头仅兜底
 */
data class AudioStreamRef(
    val url: String,
    val headers: Map<String, String>,
)
