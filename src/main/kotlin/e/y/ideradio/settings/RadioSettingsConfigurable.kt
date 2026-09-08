package e.y.ideradio.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import e.y.ideradio.RadioStrings
import e.y.ideradio.player.PlaybackService
import e.y.ideradio.resolve.ResolveException
import e.y.ideradio.resolve.ResolveManager
import e.y.ideradio.resolve.TrackResolver
import e.y.ideradio.resolve.YtDlpResolver
import e.y.ideradio.resolve.model.MediaPlatform
import e.y.ideradio.resolve.model.ResolvedTrack
import javax.swing.JComponent
import javax.swing.SwingUtilities

/**
 * Settings → Tools → IDE Radio（application 级 Configurable；设计 §5）。
 *
 * 生命周期（标准 Configurable + 会话草稿语义，v0.3/v0.6）：
 * - 「解析预览」结果只存于本 Configurable 的 [drafts]，不写全局缓存、不更新
 *   `selectedPlatform`、不启用播放；Cancel / dispose 即丢弃（Reset 会从持久化回填）。
 * - Apply：URL/循环写回 [RadioSettingsState]；URL 变更触发"提交解析"，成功后标题/时长
 *   回写持久化槽位并刷新 UI。
 * - 播放/暂停/停止：转交 [PlaybackService] 原子 API（`selectPlatform`/`play`…）。
 *
 * 平台实测项（设计 §13-T1/T6）：`parentId="tools"` 挂载与 bean 序列化在 runIde 验证。
 */
class RadioSettingsConfigurable : Configurable {

    private val state: RadioSettingsState = service()
    private val playback: PlaybackService = service()
    private val resolveManager: ResolveManager = service()

    private var panel: RadioSettingsPanel? = null
    private var scroll: JBScrollPane? = null

    /** 会话草稿：平台 → 预览解析结果（仅当前设置会话内存在）。 */
    private val drafts = mutableMapOf<MediaPlatform, ResolvedTrack>()

    private val resolvers: Map<MediaPlatform, TrackResolver> = mapOf(
        MediaPlatform.YOUTUBE to YtDlpResolver(MediaPlatform.YOUTUBE, { state.ytDlpPath }) { state.cookiesFromBrowser to state.cookiesFilePath },
        MediaPlatform.BILIBILI to YtDlpResolver(MediaPlatform.BILIBILI, { state.ytDlpPath }) { state.cookiesFromBrowser to state.cookiesFilePath },
    )

    override fun getDisplayName(): String = RadioStrings.SETTINGS_DISPLAY_NAME

    override fun createComponent(): JComponent {
        scroll?.let { return it }
        val p = RadioSettingsPanel(
            stateRef = { state },
            playback = playback,
            draftOf = { platform -> drafts[platform] },
            previewHandler = { platform, url -> runPreview(platform, url) },
            commitHandler = { runCommit() },
        )
        panel = p
        p.reset()
        // 打开设置页即后台检测 yt-dlp/ffmpeg 可用性（设计 §5.2 v0.7）
        p.detectExternalTools()
        return JBScrollPane(p.component()).apply {
            border = JBUI.Borders.empty()
            horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
        }.also { scroll = it }
    }

    override fun isModified(): Boolean = panel?.isModified() ?: false

    override fun apply() {
        panel?.apply() // URL/循环写回 state；URL 变更时内部调用 commitHandler → runCommit()
    }

    override fun reset() {
        drafts.clear()
        panel?.reset()
    }

    override fun disposeUIResources() {
        drafts.clear()
        panel?.dispose()
        panel = null
        scroll = null
    }

    // ---- 解析执行（后台线程；经 ResolveManager 版本登记） ----

    /**
     * 预览（高2 修复）：结果只进 [drafts] 与面板展示。
     * 预览输入是"尚未 Apply 的草稿 URL"，不能拿持久化槽位 URL 做一致性校验（否则永远
     * stale）；正确口径 = 版本仍最新 且 面板仍存活 且 该栏输入框 URL 仍是发起预览的
     * URL。发起时先置"解析中…"。
     */
    private fun runPreview(platform: MediaPlatform, url: String) {
        if (url.isBlank()) return
        val resolver = resolvers[platform] ?: return
        panel?.setPreviewBusyOn(platform)
        val version = resolveManager.begin(platform)
        executeResolve(platform, resolver, url, version) { result, isStaleVersion ->
            if (isStaleVersion) return@executeResolve
            val staleUrlOrClosed = panel == null || panel?.currentUrlOf(platform) != url
            if (staleUrlOrClosed) return@executeResolve // 面板已关或 URL 已被改
            result.fold(
                onSuccess = { track ->
                    drafts[platform] = track
                    panel?.applyPreviewOn(platform, track.title, track.durationSeconds, null)
                },
                onFailure = { panel?.applyPreviewOn(platform, null, null, describe(it)) },
            )
        }
    }

    /**
     * 提交（Apply/OK 触发，高1 修复 + 方案 B3-3）：
     * - **全局副作用（resolveManager.commit + 回写 SlotState）与 UI 回填分离**——即使
     *   点 OK 后 `disposeUIResources()` 已把面板置 null，副作用仍无条件执行，标题/时长
     *   照常落盘、全局缓存照常提交（否则两个入口的 [播放] 永久灰掉）；
     * - 成功后该平台会话草稿即"消费"（移除 [drafts] 并清 [PlaybackService] 来源覆盖），
     *   此后播放走正常持久化路径，不再有"槽位已新、播放仍走旧草稿"的分叉；
     * - 一致性 = 版本仍最新 且 槽位 URL 未被再次改动；面板是否存活只影响 UI 回填。
     */
    private fun runCommit() {
        for ((platform, resolver) in resolvers) {
            val url = state.slot(platform).sourceUrl
            if (url.isBlank()) continue
            val version = resolveManager.begin(platform)
            executeResolve(platform, resolver, url, version) { result, isStaleVersion ->
                if (isStaleVersion) return@executeResolve
                if (!resolveManager.isUrlCurrent(platform, url)) return@executeResolve
                result.fold(
                    onSuccess = { track ->
                        // ---- 全局副作用（不依赖面板存活）----
                        resolveManager.commit(platform, track)
                        val slot = state.slot(platform)
                        slot.title = track.title
                        slot.durationSeconds = track.durationSeconds
                        slot.lastResolvedAtEpochMs = track.resolvedAtEpochMs
                        // ---- 草稿已被"消费"：提交后槽位/全局缓存即权威 ----
                        // 移除会话草稿并清播放覆盖，防"槽位已新、播放仍走旧草稿"分叉（B3-3）
                        drafts.remove(platform)
                        playback.clearSourceOverride(platform)
                        // ---- UI 回填（面板可能已 dispose）----
                        panel?.resetFromStateOn(platform)
                    },
                    onFailure = {
                        // 提交失败无持久化副作用；仅当面板存活时提示
                        panel?.applyPreviewOn(platform, null, null, describe(it))
                    },
                )
            }
        }
    }

    /**
     * 池化线程执行阻塞解析；完成后回 EDT 先判"版本是否仍最新"（迟到即丢弃），
     * 再交给 [onDone] 做各自的一致性/副作用/UI 处理。
     * 注意：**不再在此处判定 `panel == null`**（高1：提交副作用需在面板关闭后仍执行）。
     */
    private fun executeResolve(
        platform: MediaPlatform,
        resolver: TrackResolver,
        url: String,
        version: Long,
        onDone: (Result<ResolvedTrack>, isStaleVersion: Boolean) -> Unit,
    ) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching {
                resolver.resolve(url) { !resolveManager.isCurrent(platform, version) }
            }
            val runnable = Runnable {
                val staleVersion = !resolveManager.isCurrent(platform, version)
                onDone(result, staleVersion)
            }
            SwingUtilities.invokeLater(runnable)
        }
    }

    private fun describe(e: Throwable): String = when (e) {
        is ResolveException -> e.message ?: RadioStrings.resolveFailed(e.kind)
        else -> RadioStrings.resolveFailedWithMessage(e.message ?: e.javaClass.simpleName)
    }
}
