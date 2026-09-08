package e.y.ideradio

/**
 * IDE Radio 统一字符串与文案常量管理。
 * 集中管理所有 UI 文本、按钮文案、提示信息、错误/状态消息及工具名称。
 */
object RadioStrings {

    // ---- 通用 / 平台名称 / 工具名称 ----
    const val PLUGIN_NAME = "IDE Radio"
    const val YOUTUBE = "YouTube"
    const val BILIBILI = "Bilibili"
    const val YT_DLP = "yt-dlp"
    const val FFMPEG = "ffmpeg"

    // ---- 设置面板 (RadioSettingsPanel / SourceSlotPanel / ExternalToolsPanel) ----
    const val SETTINGS_DISPLAY_NAME = "IDE Radio"
    const val ADVANCED_SETTINGS_TITLE = "高级设置（Cookie 与外部工具）"
    const val COOKIE_HINT = "当遇到 412/限流或需登录内容时使用；浏览器 Cookie 与 cookies.txt 二选一。"
    const val BROWSE_BUTTON = "浏览…"
    const val CLEAR_BUTTON = "清除"
    const val LOOP_CHECKBOX = "循环播放（默认勾选；当前曲目播完自动重播）"
    const val EXTERNAL_TOOLS_TITLE = "外部工具"
    const val RE_DETECT_BUTTON = "重新检测"
    const val RE_AUTO_DETECT_BUTTON = "重新自动检测"
    const val EXTERNAL_TOOL_MISSING_HINT = "未检测到时，请参考插件 README 自行安装或升级。"
    const val DETECTING_STATUS = "检测中…"
    const val COOKIE_PANEL_TITLE = "解析选项（站点风控 / 需登录内容）"
    const val BROWSER_COOKIE_LABEL = "从浏览器读取 Cookie："
    const val COOKIES_TXT_LABEL = "或 cookies.txt："
    const val CHOOSE_COOKIES_TITLE = "选择 cookies.txt"
    const val CHOOSE_COOKIES_DESC = "Netscape 格式 cookies.txt，供 yt-dlp --cookies 使用。"
    const val COOKIE_NONE_LABEL = "不使用"
    const val COOKIE_CHROME_LABEL = "Chrome / Chromium"
    const val COOKIE_FIREFOX_LABEL = "Firefox"
    const val COOKIE_EDGE_LABEL = "Edge / Microsoft Edge"
    const val COOKIE_SAFARI_LABEL = "Safari"

    // ---- SourceSlotPanel ----
    const val PREVIEW_BUTTON = "解析预览"
    const val RETRY_BUTTON = "重试"
    const val ACTIVATE_BUTTON = "设为当前源"
    const val ACTIVE_BADGE_ACTIVE = "● 激活中"
    const val ACTIVE_BADGE_INACTIVE = "未激活"
    const val META_NOT_RESOLVED = "尚未解析"
    const val META_RESOLVING = "解析中…"
    const val PLAY_BUTTON = "▶ 播放"
    const val PAUSE_BUTTON = "⏸ 暂停"
    const val STOP_BUTTON = "⏹ 停止"
    const val URL_LABEL = "链接:"
    const val TITLE_LABEL_PREFIX = "标题: "
    const val DURATION_LABEL_PREFIX = " 时长: "
    const val UNKNOWN_TITLE = "未知标题"
    const val UNKNOWN_DURATION = "未知"
    const val TITLE_BOOK = "《"
    const val TITLE_BOOK_END = "》"
    const val ACTIVATED_SUFFIX = "（已激活）"
    const val HELP_TOOLTIP = "播放源互斥 —— 未激活栏的播放控制为灰；切换请点目标栏「设为当前源」"
    const val DRAFT_PLAY_TOOLTIP = "使用预览结果播放（未保存，Apply 后持久化）"

    fun previewMetaText(title: String, durationStr: String): String =
        "标题: $title · 时长: $durationStr（预览，Apply 后生效）"

    fun savedMetaText(title: String, durationStr: String): String =
        "标题: $title · 时长: $durationStr"

    // ---- ToolWindow / VisualizerPanel ----
    const val NOT_PLAYING = "未在播放"
    const val WAVEFORM_MODE_TIP = "波形 (点击切换到频谱)"
    const val SPECTRUM_MODE_TIP = "频谱 (点击切换到波形)"
    const val WAVEFORM_NAME = "波形图"
    const val SPECTRUM_NAME = "柱状图"
    const val CLICK_TO_SWITCH = "单击切换"
    const val LOOP_ON = "循环开"
    const val LOOP_OFF = "循环关"

    fun modeLabelText(name: String): String = "$name · $CLICK_TO_SWITCH"
    fun canvasTooltip(w1: String, w2: String): String = "单击画布在 $w1 / $w2 间切换"
    fun currentTrackText(title: String, platformName: String): String = "当前：$title（$platformName）"
    fun progressText(pos: String, dur: String, loop: String): String = "$pos / $dur · $loop"

    // ---- 状态文案 ----
    const val STATUS_IDLE = "IDLE"
    const val STATUS_PLAYING = "PLAYING"
    const val STATUS_PAUSED = "PAUSED"
    const val STATUS_STOPPED = "STOPPED"
    const val STATUS_LOADING = "LOADING"
    const val STATUS_ERROR = "ERROR"

    // ---- 解析与工具异常 / 提示文案 ----
    const val RESOLVE_CANCELLED = "解析已取消。"
    const val RESOLVE_TIMEOUT = "解析超时（yt-dlp 30 秒内未返回），请重试或检查网络。"
    const val RESOLVE_OUTPUT_LIMIT = "yt-dlp 输出超过上限（可能是异常视频或版本过旧），解析已中止。请先更新 yt-dlp 后重试。"
    const val RESOLVE_OUTPUT_INVALID = "yt-dlp 输出无法解析。"
    const val UNSUPPORTED_FORMAT_ERROR = "未找到纯音频格式（该视频可能仅含视频轨或不可用）。"
    const val AUDIO_OUTPUT_NOT_OPEN = "AudioOutput 未 open"

    fun toolMissingCustom(toolName: String, path: String): String =
        "自定义 $toolName 路径无效：$path。请在 Settings → Tools → IDE Radio 重新检测，或参考插件说明自行安装和升级。"

    fun toolMissingDefault(toolName: String): String =
        "未找到 $toolName：请参考插件说明自行安装或升级，然后在 Settings → Tools → IDE Radio 重新检测。"

    fun resolveFailed(kind: Any): String = "解析失败（$kind）"
    fun resolveFailedWithMessage(message: String): String = "解析失败：$message"
    fun foundVersion(version: String): String = "已找到 ($version)"
    fun invalidHttpHeader(headerName: String): String = "解析器返回非法请求头: $headerName"

    fun toolCannotRun(path: String): String = "✗ $path 无法运行；请参考插件说明重新安装或升级"
    fun toolFoundApply(path: String, version: String): String = "✓ $path（$version）— Apply 后保存"
    fun toolInvalidPath(path: String): String = "✗ 已保存路径无效：$path；请重新检测或参考插件说明"
    fun toolNotFound(name: String): String = "✗ 未检测到 $name；请参考插件说明自行安装和升级"
    fun chooseToolTitle(name: String): String = "选择 $name 可执行文件"
    const val CHOOSE_TOOL_DESC = "选择后将验证版本；点 Apply / OK 保存路径。"
    const val FILE_NOT_EXECUTABLE = "✗ 所选文件不可执行"
}
