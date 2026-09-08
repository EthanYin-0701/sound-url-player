package e.y.ideradio.resolve

/**
 * 解析失败分类（设计 §6/§10）：UI 依据 [kind] 显示可读文案并提供 [重试]。
 */
enum class ResolveErrorKind {
    /** 缺少 yt-dlp / 自定义路径不可执行。 */
    TOOL_MISSING,

    /** Runner CAPTURE 整体超时。 */
    TIMEOUT,

    /** yt-dlp 输出无法解析（非 JSON/空输出等）。 */
    OUTPUT_INVALID,

    /** yt-dlp 输出超过捕获上限（stdout/stderr 截断，不得把截断 JSON 交给解析器，v0.8）。 */
    OUTPUT_LIMIT,

    /** 无纯音频格式（bestaudio 选不到，不回退视频轨，设计 §6.1）。 */
    UNSUPPORTED_FORMAT,

    LOGIN_REQUIRED,
    UNAVAILABLE,
    AGE_RESTRICTED,
    GEO_RESTRICTED,

    /** 站点风控/限流（如 Bilibili HTTP 412 / 429），多为 IP 级间歇性限制（v0.10）。 */
    RATE_LIMITED,

    /** 其他解析失败（网络等）。 */
    GENERIC,
}

/** 解析失败异常：携带分类供 UI/日志使用（message 已脱敏，不含临时直链与请求头）。 */
class ResolveException(
    val kind: ResolveErrorKind,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
