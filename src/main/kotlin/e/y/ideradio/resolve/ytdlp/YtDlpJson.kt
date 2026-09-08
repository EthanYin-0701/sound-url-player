package e.y.ideradio.resolve.ytdlp

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * yt-dlp `-J` 输出的最小 DTO（设计 §6.7，v0.6 定稿）。
 *
 * 容错约定：
 * - `Json { ignoreUnknownKeys = true }` —— 未知字段一律忽略（yt-dlp 演进不破坏解析）；
 * - `duration` / `abr` / `tbr` 声明为 [JsonElement]，由 [YtDlpJsonMapper] 归一为数值
 *   （yt-dlp 可能输出整数、浮点或字符串数字，kotlinx 不会自动转 `Double`）；
 * - `http_headers` 声明为 [JsonElement]，由 Mapper 归一为合法头映射（缺省/null/非对象
 *   → 空 Map，见 §6.7 容错规则），并经 §7.1 的 CR/LF/NUL 校验后才用于 ffmpeg。
 */
@Serializable
data class YtDlpInfo(
    val id: String = "",
    val title: String = "",
    val duration: JsonElement? = null,
    val formats: List<YtDlpFormat> = emptyList(),
)

@Serializable
data class YtDlpFormat(
    @SerialName("format_id")
    val formatId: String = "",
    val url: String = "",
    val vcodec: String = "none",
    val acodec: String = "none",
    val abr: JsonElement? = null,
    val tbr: JsonElement? = null,
    @SerialName("http_headers")
    val httpHeaders: JsonElement? = null,
)
