package e.y.ideradio.player

import e.y.ideradio.RadioStrings

/**
 * HTTP 头拼入 ffmpeg `-headers` 前的校验（设计 §7.1/D13，v0.4/v0.6）：
 * - header name 必须匹配合法 HTTP token（RFC 7230 token 子集判断）；
 * - value **拒绝 `\r`、`\n` 与 NUL**；
 * - 普通头非法 → 丢弃（返回降级映射）；**关键头（Referer/User-Agent/Cookie）非法 →
 *   抛 [IllegalArgumentException]**（终止播放，不静默降级为难定位的 403）。
 */
object HeadersSanitizer {

    private val TOKEN_REGEX = Regex("^[!#$%&'*+.^_`|~0-9A-Za-z-]+$")
    private val CRITICAL_HEADERS = setOf("referer", "user-agent", "cookie")

    /**
     * @return 通过校验的键值映射（键原样保留；非法普通头剔除）。
     * @throws IllegalArgumentException 关键头缺失值非法 / 值为空 时
     */
    fun sanitize(headers: Map<String, String>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for ((name, value) in headers) {
            val validName = TOKEN_REGEX.matches(name) && !name.equals("Host", true)
            val validValue = value.isNotBlank() && !value.any { it == '\r' || it == '\n' || it == '\u0000' }
            if (!validName || !validValue) {
                if (CRITICAL_HEADERS.any { name.equals(it, true) }) {
                    throw IllegalArgumentException(RadioStrings.invalidHttpHeader(name.take(40)))
                }
                continue // 普通头丢弃并告警
            }
            out[name] = value
        }
        return out
    }

    /** 拼成 ffmpeg `-headers` 单参数值（"K: v\r\nK2: v2\r\n"）。 */
    fun toFfmpegHeaderArgument(headers: Map<String, String>): String {
        if (headers.isEmpty()) return ""
        val sb = StringBuilder()
        for ((k, v) in headers) {
            sb.append(k).append(": ").append(v).append("\r\n")
        }
        return sb.toString()
    }
}
