package e.y.ideradio.resolve.ytdlp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * yt-dlp JSON 解析与映射（设计 §6.7，v0.6 定稿）：DTO 校验 → 纯音频筛选 → 头归一。
 *
 * 解析失败（非法 JSON / 非对象）由 [parse] 抛出 [kotlinx.serialization.SerializationException]
 * 或其子类，调用方归类为"yt-dlp 输出异常"。
 */
object YtDlpJsonMapper {

    private val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        isLenient = false
    }

    fun parse(raw: String): YtDlpInfo = json.decodeFromString(YtDlpInfo.serializer(), raw)

    /** 时长（秒）；缺失/null/非数值 → -1（显示"未知"，不视为失败）。 */
    fun durationSeconds(info: YtDlpInfo): Long {
        val v = toDouble(info.duration) ?: return -1L
        return v.toLong().coerceAtLeast(0L)
    }

    /**
     * 纯音频筛选（设计 §6.1，v0.4 不回退）：`vcodec=="none" && acodec!="none"`，
     * 按 abr/tbr 取最优；无纯音频返回 null（调用方报"不支持的媒体格式"）。
     * 不使用 `bestaudio/best` 回退到含视频格式。
     */
    fun selectBestAudioFormat(info: YtDlpInfo): YtDlpFormat? {
        return info.formats
            .asSequence()
            .filter { f -> f.url.isNotBlank() }
            .filter { f ->
                // v0.8：`acodec == "none"`（字符串）视同无音频必须排除（评审 #4）
                f.vcodec.equals("none", ignoreCase = true) &&
                    f.acodec.isNotBlank() &&
                    !f.acodec.equals("none", ignoreCase = true)
            }
            .maxByOrNull { f -> toDouble(f.abr) ?: toDouble(f.tbr) ?: 0.0 }
    }

    /**
     * 请求头归一（设计 §6.7/v0.6）：显式 null、缺失、非对象、值为非字符串 → 忽略该项；
     * 只保留 name/value 均为合法字符串的条目。CR/LF/NUL 过滤在播放前（§7.1）执行。
     */
    fun headersOf(format: YtDlpFormat): Map<String, String> {
        val el = format.httpHeaders ?: return emptyMap()
        if (el !is JsonObject) return emptyMap()
        val result = LinkedHashMap<String, String>()
        for ((k, v) in el) {
            val value = stringValue(v) ?: continue
            result[k] = value
        }
        return result
    }

    /** JsonPrimitive 的字符串/数值内容；null 或非原始值 → null。 */
    private fun stringValue(el: JsonElement): String? {
        if (el is JsonPrimitive) {
            if (el.isString) return el.contentOrNull
            return el.contentOrNull // 数字/布尔也取其字面量
        }
        return null
    }

    /** 把 yt-dlp 可能输出的数字或字符串数字归一到 Double；无法解析 → null。 */
    fun toDouble(el: JsonElement?): Double? {
        if (el == null) return null
        if (el !is JsonPrimitive) return null
        return el.contentOrNull?.toDoubleOrNull()
    }
}
