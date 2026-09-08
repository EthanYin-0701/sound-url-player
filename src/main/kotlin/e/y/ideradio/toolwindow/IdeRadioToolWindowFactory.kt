package e.y.ideradio.toolwindow

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import e.y.ideradio.player.PlaybackService
import e.y.ideradio.settings.RadioSettingsState
import e.y.ideradio.viz.VisualizerPanel

/**
 * IDE Radio Tool Window 工厂（设计 §8.1）：
 * - Tool Window 按 project 创建（多窗口多面板），但都订阅同一 application 级
 *   [PlaybackService] / `PcmBuffer` → 显示同一播放会话，状态天然同步；
 * - 面板注册到该 Content 的 Disposer：窗口/项目关闭时注销监听、停止 Timer。
 */
class IdeRadioToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val playback = service<PlaybackService>()
        val settings = service<RadioSettingsState>()
        val panel = VisualizerPanel(playback, playback.pcmBuffer, settings)

        val content = ContentFactory.getInstance().createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)
        // content 关闭/移除时 dispose 面板（注销监听/停 Timer，防跨窗口泄漏，设计 §8.1）
        Disposer.register(content, panel)
    }
}
