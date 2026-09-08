package e.y.ideradio.resolve

import e.y.ideradio.RadioStrings
import e.y.ideradio.resolve.external.ExternalProcessRunner
import e.y.ideradio.resolve.external.ExternalToolLocator
import e.y.ideradio.resolve.external.ToolLookup
import e.y.ideradio.resolve.model.AudioStreamRef
import e.y.ideradio.resolve.model.MediaPlatform
import e.y.ideradio.resolve.model.ResolvedTrack
import e.y.ideradio.resolve.ytdlp.YtDlpJsonMapper
import e.y.ideradio.resolve.ytdlp.YtDlpInfo

/**
 * 基于 yt-dlp 的解析实现（设计 §6.1/§6.7）：`yt-dlp -J` → kotlinx DTO → 纯音频筛选 →
 * 请求头归一（format http_headers 优先 + 平台兜底头）。
 *
 * 外部进程经 [ExternalProcessRunner]（CAPTURE：排空/上限/30s 硬超时/进程树清理）。
 * 调用方须放到后台线程（如 `Application.executeOnPooledThread`）。
 *
 * v0.10（B 站 412 修复）：
 * - 对**可重试错误**（412/429 风控、网络类/SSL/5xx）自动重试至多 [MAX_ATTEMPTS] 次，
 *   指数退避 [RETRY_BASE_DELAY_MS]，可被 cancelled 中断；
 * - 支持 Cookie：构造注入 [cookieProvider]（浏览器或 cookies.txt，缓解风控）；
 * - 移除已废弃的 `--no-call-home`（yt-dlp 2026+ 会打印 Deprecated 警告）。
 */
class YtDlpResolver(
    override val platform: MediaPlatform,
    /** 取 RadioSettingsState.ytDlpPath（空 = 走 PATH）。 */
    private val ytDlpPathProvider: () -> String = { "" },
    /** 取 RadioSettingsState 的 Cookie 配置（浏览器名或 cookies.txt 路径）。 */
    private val cookieProvider: () -> Pair<String, String> = { "" to "" },
) : TrackResolver {

    private val runner = ExternalProcessRunner()

    override fun extractId(url: String): String? {
        // 平台视频 ID 提取：后续按需扩展（youtube watch/youtu.be/shorts、bilibili BV）。
        return when (platform) {
            MediaPlatform.YOUTUBE -> youtubeId(url)
            MediaPlatform.BILIBILI -> bilibiliBv(url)
        }
    }

    override fun resolve(url: String): ResolvedTrack = resolve(url) { false }

    override fun resolve(url: String, cancelled: () -> Boolean): ResolvedTrack {
        val exe = when (val lookup = ExternalToolLocator.lookupYtDlp(ytDlpPathProvider())) {
            is ToolLookup.Found -> lookup.path
            is ToolLookup.InvalidExplicitPath ->
                throw ResolveException(
                    ResolveErrorKind.TOOL_MISSING,
                    RadioStrings.toolMissingCustom(RadioStrings.YT_DLP, lookup.path),
                )
            ToolLookup.NotConfigured ->
                throw ResolveException(
                    ResolveErrorKind.TOOL_MISSING,
                    RadioStrings.toolMissingDefault(RadioStrings.YT_DLP),
                )
        }

        val baseArgs = buildArgs(url)

        // 自动重试：可重试错误最多 MAX_ATTEMPTS 次（v0.10）
        var lastError: ResolveException? = null
        for (attempt in 1..MAX_ATTEMPTS) {
            if (cancelled()) throw ResolveException(ResolveErrorKind.GENERIC, RadioStrings.RESOLVE_CANCELLED)
            if (attempt > 1) sleepWithCancel(backoffMs(attempt - 1), cancelled)

            val result = runner.runCaptured(
                executable = exe,
                args = baseArgs,
                cancelled = cancelled,
            )
            if (result.cancelled) throw ResolveException(ResolveErrorKind.GENERIC, RadioStrings.RESOLVE_CANCELLED)
            if (result.timedOut) {
                lastError = ResolveException(ResolveErrorKind.TIMEOUT, RadioStrings.RESOLVE_TIMEOUT)
                if (attempt == MAX_ATTEMPTS) throw lastError
                continue
            }
            if (result.exitCode != 0) {
                val e = classifyFailure(result.stderr)
                if (isRetryable(e) && attempt < MAX_ATTEMPTS) {
                    lastError = e
                    continue
                }
                throw e
            }
            // v0.8（评审 #13）：输出超限时不得把截断 JSON 交给解析器
            if (result.outputTruncated) {
                throw ResolveException(
                    ResolveErrorKind.OUTPUT_LIMIT,
                    RadioStrings.RESOLVE_OUTPUT_LIMIT,
                )
            }

            val info: YtDlpInfo = try {
                YtDlpJsonMapper.parse(result.stdout.toString(Charsets.UTF_8))
            } catch (e: Exception) {
                throw ResolveException(ResolveErrorKind.OUTPUT_INVALID, RadioStrings.RESOLVE_OUTPUT_INVALID, e)
            }

            val format = YtDlpJsonMapper.selectBestAudioFormat(info)
                ?: throw ResolveException(
                    ResolveErrorKind.UNSUPPORTED_FORMAT,
                    RadioStrings.UNSUPPORTED_FORMAT_ERROR,
                )

            val headers = headersFor(format)
            return ResolvedTrack(
                sourceUrl = url,
                title = info.title.ifBlank { RadioStrings.UNKNOWN_TITLE },
                durationSeconds = YtDlpJsonMapper.durationSeconds(info),
                stream = AudioStreamRef(url = format.url, headers = headers),
                resolvedAtEpochMs = System.currentTimeMillis(),
            )
        }
        throw lastError ?: ResolveException(ResolveErrorKind.GENERIC, RadioStrings.resolveFailed("GENERIC"))
    }

    /** yt-dlp 命令参数（不含可执行文件；internal 供测试）：移除 `--no-call-home`（已废弃），按需拼 Cookie。 */
    internal fun buildArgs(url: String): List<String> {
        val args = mutableListOf("-J", "--no-playlist", "--no-warnings")
        val (browser, cookiesFile) = cookieProvider()
        if (browser.isNotBlank()) {
            args += "--cookies-from-browser"
            args += browser.trim()
        } else if (cookiesFile.isNotBlank()) {
            args += "--cookies"
            args += cookiesFile.trim()
        }
        args += url
        return args
    }

    private fun sleepWithCancel(ms: Long, cancelled: () -> Boolean) {
        val until = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < until) {
            if (cancelled()) return
            try {
                Thread.sleep(minOf(200L, until - System.currentTimeMillis()))
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }

    private fun backoffMs(attempt: Int): Long =
        RETRY_BASE_DELAY_MS * (1L shl (attempt - 1)) // 1s, 2s

    /** 是否值得自动重试：412/429 风控、超时、网络/SSL/5xx 类。 */
    private fun isRetryable(e: ResolveException): Boolean = when (e.kind) {
        ResolveErrorKind.RATE_LIMITED -> true
        ResolveErrorKind.GENERIC -> networkish(e.message ?: "")
        ResolveErrorKind.GEO_RESTRICTED -> false // 不是临时性
        else -> false
    }

    private fun networkish(text: String): Boolean {
        val l = text.lowercase()
        return listOf("ssl", "timed out", "connection", "unexpected eof", "http error 5", "network", "temporarily", "try again", "ec 2" /* curl code 2 */)
            .any { l.contains(it) }
    }

    /** 按 yt-dlp stderr 内容做粗分类（internal 供测试以真实样本固化，评审 #16）。 */
    internal fun classifyFailure(stderr: String): ResolveException {
        val raw = stderr.trim().take(500).ifBlank { "yt-dlp 退出码非 0（未返回错误详情）。" }
        val lower = stderr.lowercase()
        val kind = when {
            "http error 412" in lower || "error 412" in lower || "412: precondition failed" in lower ->
                ResolveErrorKind.RATE_LIMITED
            "http error 429" in lower || "too many requests" in lower -> ResolveErrorKind.RATE_LIMITED
            "sign in" in lower || "login" in lower || "log in" in lower -> ResolveErrorKind.LOGIN_REQUIRED
            "video unavailable" in lower ||
                "not available in your country" in lower -> ResolveErrorKind.UNAVAILABLE
            "geo restriction" in lower || "geo-restricted" in lower -> ResolveErrorKind.GEO_RESTRICTED
            "age-restricted" in lower || "confirm your age" in lower -> ResolveErrorKind.AGE_RESTRICTED
            "is not a valid url" in lower || "unsupported url" in lower -> ResolveErrorKind.GENERIC
            "ssl" in lower || "unexpected eof" in lower || "connection" in lower || "timed out" in lower ->
                ResolveErrorKind.GENERIC // 网络类（可重试由 isRetryable 判定）
            else -> ResolveErrorKind.GENERIC
        }
        val friendly = friendlyMessage(kind, raw)
        return ResolveException(kind, friendly)
    }

    /** 可读文案（评审：不要把原始英文 stderr 直接塞给 UI）。 */
    private fun friendlyMessage(kind: ResolveErrorKind, raw: String): String = when (kind) {
        ResolveErrorKind.RATE_LIMITED ->
            "站点风控/限流（HTTP 412/429），多为临时性。插件已自动重试仍失败，请稍后重试；" +
                "频繁失败可在设置页配置浏览器 Cookie 后重试。"
        ResolveErrorKind.LOGIN_REQUIRED -> "该内容需要登录，请配置浏览器 Cookie 后重试。"
        ResolveErrorKind.GEO_RESTRICTED -> "该内容在当前地区不可用。"
        ResolveErrorKind.AGE_RESTRICTED -> "该内容受年龄限制，无法匿名访问。"
        ResolveErrorKind.UNAVAILABLE -> "视频不可用或已被移除。"
        else -> "解析失败（${kind}）：${raw.take(200)}"
    }

    /** format 自带 http_headers 优先；Bilibili 缺 Referer 时补平台兜底头（设计 §6.1）。 */
    private fun headersFor(format: e.y.ideradio.resolve.ytdlp.YtDlpFormat): Map<String, String> {
        val headers = YtDlpJsonMapper.headersOf(format).toMutableMap()
        if (platform == MediaPlatform.BILIBILI && headers.keys.none { it.equals("Referer", true) }) {
            headers["Referer"] = "https://www.bilibili.com/"
        }
        if (headers.keys.none { it.equals("User-Agent", true) }) {
            headers["User-Agent"] = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36"
        }
        return headers
    }

    private fun youtubeId(url: String): String? {
        // youtube.com/watch?v=ID / youtu.be/ID / shorts/ID / music.youtube.com
        val patterns = listOf(
            Regex("(?:youtube\\.com|music\\.youtube\\.com)/watch\\?v=([A-Za-z0-9_-]{6,})"),
            Regex("youtu\\.be/([A-Za-z0-9_-]{6,})"),
            Regex("(?:youtube\\.com)/(?:shorts|embed|live)/([A-Za-z0-9_-]{6,})"),
        )
        for (p in patterns) p.find(url)?.groupValues?.get(1)?.let { return it }
        return null
    }

    private fun bilibiliBv(url: String): String? {
        val m = Regex("(BV[0-9A-Za-z]{10})").find(url) ?: return null
        return m.groupValues[1]
    }

    companion object {
        /** 含首次尝试的总尝试次数（1 次 + 至多 2 次重试）。 */
        const val MAX_ATTEMPTS = 3
        const val RETRY_BASE_DELAY_MS = 1_000L
    }
}
