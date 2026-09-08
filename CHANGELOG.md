<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# IDE Radio Changelog

## [Unreleased]

### Added

- Settings 页（Settings → Tools → IDE Radio）：YouTube / Bilibili 两个互斥播放源栏位，
  支持「解析预览」（会话草稿，Apply 生效）与标题/时长展示。
- 播放控制（播放/暂停/停止/循环，默认单曲循环）；YouTube / Bilibili 源互斥（方案 A，
  非激活栏播放控制灰掉，经「设为当前源」原子切换）。
- 基于 yt-dlp + ffmpeg 的播放管线：yt-dlp 解析（kotlinx.serialization）→ ffmpeg STREAM
  解码（请求头校验/脱敏）→ Java Sound 输出；暂停=终止+进度记录、恢复=`-ss` 续播。
- 「IDE Radio」Tool Window：波形/频谱可视化（单击画布切换），与设置页共用同一播放会话。
- 外部工具管理：打开设置即检测 yt-dlp/ffmpeg，并验证版本；支持文件选择器指定路径。
- 开发：Gradle 依赖 kotlinx-serialization（1.8.1），单元测试覆盖解析、FFT、环形缓冲和
  请求头校验。

### Changed

- 集中管理所有字符串与汉字：将 Kotlin 代码中的 UI 文本、按钮文案、提示信息、错误/状态消息及工具名称全部重构并集中到 `RadioStrings` 常量定义文件中管理。
- 外部工具管理改为仅检测：插件不再安装或升级 yt-dlp/ffmpeg；检测到后自动填写路径，
  未检测到则提示参考 README 自行安装和升级，并保留文件选择器兜底。

### Fixed

- **解析预览/重试成功后播放三键仍全灰**（`plans/retry_preview_buttons_not_enabled_review.md`，
  方案 B：草稿感知的使能 + 草稿感知的播放）：
  - UI 侧：`SourceSlotPanel` 注入只读草稿回调（`drafts[platform]`），三键可播判定 = 槽位
    可播 **或** 草稿 URL == 当前输入框 URL；预览成功/失败、解析发起、URL 编辑均补
    `refresh()`（此前预览成功根本不重算使能）；`[播放]` 在草稿态标注「使用预览结果播放
    （未保存，Apply 后持久化）」tooltip；[重试] 收起时空位立即 revalidate/repaint。
  - 播放侧：新增 `PlaybackSource(url, track)` 与 `PlaybackService.play(…, override)`；
    草稿播放走显式来源（track 新鲜直接用、过期按草稿 URL 重解析，**不写入全局缓存**），
    覆盖存为会话字段以支持循环重播/暂停恢复沿用；停止/切源/无覆盖播放清除；Apply 提交
    成功后 `clearSourceOverride` + 移除草稿，避免"槽位已新、播放仍走旧草稿"分叉。
- **Settings 页三个输入框宽度随 Settings 窗口自适应**（settings_UI-issue3）：根面板实现 `Scrollable` 以正确响应视口拉宽与收窄；长文本单行标签（`metaLabel`、`ytStatus`/`ffStatus`、`cookieHint`）改为自适应换行组件或支持截断与 Tooltip；错误提示区移除固定尺寸；输入框显式设置最小宽度，确保在窗口缩小时平滑缩放而不被压坏或出现多余横向滚动条。
- 播放解析不再阻塞控制线程：LOADING 期间停止/切源即时生效并取消在途 yt-dlp（generation 防护）。
- 预览/提交解析带平台版本号与 URL 校验，迟到的旧结果不再覆盖新状态。
- **点 OK 直接提交也能落盘**：提交解析的全局副作用（缓存写入 + 标题/时长持久化）不再
  依赖设置面板存活；仅 UI 回填受面板 dispose 保护（修复"标题永不保存、播放按钮永久灰掉"）。
- **解析预览可正常显示**：预览改用"草稿 URL 一致性"校验（而非与持久化槽位 URL 比较，
  后者导致预览永远被判 stale）；预览发起即显示"解析中…"。
- **暂停/恢复进度正确累积**：进度 = 会话起始位置 + 实际已播放（`SourceDataLine`
  `getLongFramePosition`），反复暂停不再回跳。
- **Tool Window 顶部显示曲目与进度**：开始播放即广播 `TrackChanged`（真实标题），并按
  500ms 周期广播 `Position`；顶部展示 `当前位置 / 总时长 · 循环开/关`。
- **B 站 412/风控限流**（评审 #16）：新增 `RATE_LIMITED` 分类与可读中文文案（不再裸抛
  原始 stderr）；对 412/429/SSL/网络/5xx 类失败**自动重试至多 3 次**（1s/2s 退避、可取消）；
  Settings 新增「解析选项」区（从浏览器读取 Cookie / 指定 cookies.txt，写入 yt-dlp
  `--cookies-from-browser`/`--cookies`）；错误行改为可换行组件并显示 **[重试]** 按钮；
  移除已废弃的 `--no-call-home` 参数。
- 纯音频筛选排除 `acodec="none"` 空格式；yt-dlp 输出超限时报明确错误而非解析失败。
- 临时音频直链与请求头仅内存、日志/UI 一律脱敏。
