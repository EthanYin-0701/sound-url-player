package e.y.ideradio.settings

import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.ui.HideableTitledPanel
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import e.y.ideradio.RadioStrings
import e.y.ideradio.player.PlaybackService
import e.y.ideradio.resolve.model.MediaPlatform
import e.y.ideradio.resolve.model.ResolvedTrack
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.io.File
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextField

/**
 * Settings 主面板（设计 §5.2）：两个互斥平台栏 + 全局「循环播放」+ 会话提示行。
 *
 * 编辑语义（标准 Configurable）：所有编辑只作用于本 UI；Apply/OK 提交并持久化；
 * Cancel 丢弃（未 Apply 的预览草稿不产生全局副作用，见 §5.2/§5.3）。
 */
class RadioSettingsPanel(
    private val stateRef: () -> RadioSettingsState,
    private val playback: PlaybackService,
    /** 平台 → 会话草稿（由 Configurable 注入 `{ platform -> drafts[platform] }`，方案 B1）。 */
    private val draftOf: (MediaPlatform) -> ResolvedTrack?,
    private val previewHandler: (MediaPlatform, String) -> Unit,
    private val commitHandler: () -> Unit,
) {

    // ---- 解析选项（B 站风控/Cookie，v0.10；评审 #16）----
    private val cookieBrowserCombo = JComboBox<CookieBrowserOption>()
    private val cookieFileField = JTextField(24).apply {
        minimumSize = Dimension(220, preferredSize.height)
    }
    private val browseCookieButton = JButton(RadioStrings.BROWSE_BUTTON)
    private val clearCookieButton = JButton(RadioStrings.CLEAR_BUTTON)
    private val cookieHint = javax.swing.JTextArea().apply {
        isEditable = false
        isOpaque = false
        isFocusable = false
        lineWrap = true
        wrapStyleWord = true
        foreground = JBColor.namedColor("Component.infoForeground", JBColor(0x606060, 0x999999))
        border = null
        alignmentX = 0f
        text = RadioStrings.COOKIE_HINT
    }

    private val youtubePanel = SourceSlotPanel(
        platform = MediaPlatform.YOUTUBE,
        playback = playback,
        stateRef = stateRef,
        draftRef = { draftOf(MediaPlatform.YOUTUBE) },
        previewHandler = { url -> previewHandler(MediaPlatform.YOUTUBE, url) },
    )
    private val bilibiliPanel = SourceSlotPanel(
        platform = MediaPlatform.BILIBILI,
        playback = playback,
        stateRef = stateRef,
        draftRef = { draftOf(MediaPlatform.BILIBILI) },
        previewHandler = { url -> previewHandler(MediaPlatform.BILIBILI, url) },
    )
    private val loopCheckBox = JCheckBox(RadioStrings.LOOP_CHECKBOX)
    private val externalToolsPanel = ExternalToolsPanel(stateRef)
    private val advancedContent = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        alignmentX = Component.LEFT_ALIGNMENT
        add(buildCookiePanel())
        add(Box.createVerticalStrut(8))
        add(externalToolsPanel)
    }
    private val advancedPanel = HideableTitledPanel(
        RadioStrings.ADVANCED_SETTINGS_TITLE,
        false,
        advancedContent,
        stateRef().advancedExpanded,
    ).apply {
        alignmentX = Component.LEFT_ALIGNMENT
    }
    private val root = object : JPanel(), javax.swing.Scrollable {
        init {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
        }
        override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
        override fun getScrollableUnitIncrement(visibleRect: java.awt.Rectangle?, orientation: Int, direction: Int): Int = 16
        override fun getScrollableBlockIncrement(visibleRect: java.awt.Rectangle?, orientation: Int, direction: Int): Int = 64
        override fun getScrollableTracksViewportWidth(): Boolean {
            val p = parent
            return p is javax.swing.JViewport && p.width >= minimumSize.width
        }
        override fun getScrollableTracksViewportHeight(): Boolean = false
    }

    init {
        youtubePanel.component().alignmentX = Component.LEFT_ALIGNMENT
        bilibiliPanel.component().alignmentX = Component.LEFT_ALIGNMENT
        loopCheckBox.alignmentX = Component.LEFT_ALIGNMENT
        root.add(youtubePanel.component())
        root.add(bilibiliPanel.component())
        root.add(Box.createVerticalStrut(6))
        root.add(loopCheckBox)
        root.add(Box.createVerticalStrut(8))
        root.add(advancedPanel)
    }

    /** 解析选项子面板（Cookie 来源：浏览器 或 cookies.txt，缓解风控/登录）。 */
    private fun buildCookiePanel(): JPanel {
        cookieFileField.document.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent?) { cookieFileField.toolTipText = cookieFileField.text.ifBlank { null } }
            override fun removeUpdate(e: javax.swing.event.DocumentEvent?) { cookieFileField.toolTipText = cookieFileField.text.ifBlank { null } }
            override fun changedUpdate(e: javax.swing.event.DocumentEvent?) { cookieFileField.toolTipText = cookieFileField.text.ifBlank { null } }
        })

        val p = JPanel()
        p.layout = BoxLayout(p, BoxLayout.Y_AXIS)
        p.border = BorderFactory.createTitledBorder(BorderFactory.createEtchedBorder(), RadioStrings.COOKIE_PANEL_TITLE)
        p.alignmentX = 0f

        CookieBrowserOption.entries.forEach { cookieBrowserCombo.addItem(it) }
        cookieBrowserCombo.maximumSize = Dimension(JBUI.scale(220), cookieBrowserCombo.preferredSize.height)

        val row1 = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0))
        row1.alignmentX = Component.LEFT_ALIGNMENT
        row1.add(JLabel(RadioStrings.BROWSER_COOKIE_LABEL))
        row1.add(cookieBrowserCombo)

        val row2 = JPanel(GridBagLayout()).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            add(JLabel(RadioStrings.COOKIES_TXT_LABEL), GridBagConstraints().apply {
                gridx = 0
                gridy = 0
                insets = Insets(0, 0, 0, 4)
                anchor = GridBagConstraints.WEST
            })
            add(cookieFileField, GridBagConstraints().apply {
                gridx = 1
                gridy = 0
                weightx = 1.0
                fill = GridBagConstraints.HORIZONTAL
                anchor = GridBagConstraints.WEST
            })
            val btnPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
                add(browseCookieButton)
                add(clearCookieButton)
            }
            add(btnPanel, GridBagConstraints().apply {
                gridx = 2
                gridy = 0
                anchor = GridBagConstraints.WEST
            })
        }

        cookieHint.alignmentX = 0f

        p.add(row1)
        p.add(Box.createVerticalStrut(4))
        p.add(row2)
        p.add(Box.createVerticalStrut(4))
        p.add(cookieHint)

        browseCookieButton.addActionListener {
            val d = FileChooserDescriptor(true, false, false, false, false, false)
                .withTitle(RadioStrings.CHOOSE_COOKIES_TITLE)
                .withDescription(RadioStrings.CHOOSE_COOKIES_DESC)
            val vf = FileChooser.chooseFile(d, null, null as com.intellij.openapi.vfs.VirtualFile?) ?: return@addActionListener
            val f = File(vf.path)
            if (f.isFile) {
                cookieFileField.text = f.absolutePath
                cookieFileField.caretPosition = 0
                cookieFileField.toolTipText = f.absolutePath
                cookieBrowserCombo.selectedItem = CookieBrowserOption.NONE // 互斥
            }
        }
        clearCookieButton.addActionListener {
            cookieFileField.text = ""
            cookieFileField.caretPosition = 0
            cookieFileField.toolTipText = null
            cookieBrowserCombo.selectedItem = CookieBrowserOption.NONE
        }
        cookieBrowserCombo.addActionListener {
            if (cookieBrowserCombo.selectedItem != CookieBrowserOption.NONE) cookieFileField.text = ""
        }
        return p
    }

    fun component(): JComponent = root

    /** 指定平台栏当前输入框 URL（供预览的"草稿 URL 一致性"校验，高2）。 */
    fun currentUrlOf(platform: MediaPlatform): String = slotPanel(platform).url()

    /** 预览发起后置"解析中…"提示并禁用按钮（避免"点了没反应"）。 */
    fun setPreviewBusyOn(platform: MediaPlatform) {
        slotPanel(platform).showResolving()
    }

    /** createComponent 后调用：后台检测 yt-dlp/ffmpeg 可用性（v0.7）。 */
    fun detectExternalTools() {
        externalToolsPanel.detect { failing ->
            if (failing) {
                advancedPanel.setOn(true)
            }
        }
    }

    /** reset：从持久化状态填充（loop + 两栏 URL/标题 + 外部工具路径 + Cookie）。 */
    fun reset() {
        val state = stateRef()
        loopCheckBox.isSelected = state.loopEnabled
        youtubePanel.resetFromState()
        bilibiliPanel.resetFromState()
        externalToolsPanel.reset()
        cookieBrowserCombo.selectedItem = CookieBrowserOption.fromKey(state.cookiesFromBrowser)
        cookieFileField.text = state.cookiesFilePath
        cookieFileField.caretPosition = 0
        cookieFileField.toolTipText = state.cookiesFilePath.ifBlank { null }
        advancedPanel.setOn(state.advancedExpanded)
    }

    /** isModified：比较 UI 与持久化状态（两栏 URL、循环、外部工具、Cookie）。 */
    fun isModified(): Boolean {
        val state = stateRef()
        val urlChanged = youtubePanel.url() != state.youtube.sourceUrl ||
            bilibiliPanel.url() != state.bilibili.sourceUrl
        val cookieChanged =
            (cookieBrowserCombo.selectedItem as CookieBrowserOption).key != state.cookiesFromBrowser ||
                cookieFileField.text.trim() != state.cookiesFilePath
        return urlChanged || loopCheckBox.isSelected != state.loopEnabled ||
            externalToolsPanel.isModified() || cookieChanged
    }

    /** apply：收集 URL/循环/外部工具/Cookie 写回状态；URL 变更触发提交解析。 */
    fun apply() {
        val state = stateRef()
        var changed = false
        changed = writeSlot(state, MediaPlatform.YOUTUBE, youtubePanel.url()) || changed
        changed = writeSlot(state, MediaPlatform.BILIBILI, bilibiliPanel.url()) || changed
        if (state.loopEnabled != loopCheckBox.isSelected) {
            state.loopEnabled = loopCheckBox.isSelected
        }
        externalToolsPanel.apply()
        state.cookiesFromBrowser = (cookieBrowserCombo.selectedItem as CookieBrowserOption).key
        state.cookiesFilePath = cookieFileField.text.trim()
        state.advancedExpanded = advancedPanel.isExpanded()
        if (changed) commitHandler()
    }

    private fun writeSlot(state: RadioSettingsState, platform: MediaPlatform, newUrl: String): Boolean {
        val slot = state.slot(platform)
        if (slot.sourceUrl == newUrl) return false
        slot.sourceUrl = newUrl
        // URL 变更：旧的标题/时长失效，待提交解析后回填（§5.3）
        slot.title = ""
        slot.durationSeconds = -1
        slot.lastResolvedAtEpochMs = -1
        return true
    }

    fun refresh() {
        youtubePanel.refresh()
        bilibiliPanel.refresh()
    }

    /** 预览/提交结果回填到指定平台栏（EDT 调用）。 */
    fun applyPreviewOn(platform: MediaPlatform, title: String?, durationSeconds: Long?, error: String?) {
        slotPanel(platform).applyPreview(title, durationSeconds, error)
    }

    /** 从持久化状态重填指定平台栏（提交解析成功后调用）。 */
    fun resetFromStateOn(platform: MediaPlatform) {
        slotPanel(platform).resetFromState()
    }

    private fun slotPanel(platform: MediaPlatform) = when (platform) {
        MediaPlatform.YOUTUBE -> youtubePanel
        MediaPlatform.BILIBILI -> bilibiliPanel
    }

    fun dispose() {
        externalToolsPanel.disposePanel()
        youtubePanel.dispose()
        bilibiliPanel.dispose()
    }
}

/** 浏览器 Cookie 来源选项（映射 yt-dlp `--cookies-from-browser` 值，v0.10）。 */
enum class CookieBrowserOption(val key: String, val label: String) {
    NONE("", RadioStrings.COOKIE_NONE_LABEL),
    CHROME("chrome", RadioStrings.COOKIE_CHROME_LABEL),
    EDGE("edge", RadioStrings.COOKIE_EDGE_LABEL),
    FIREFOX("firefox", RadioStrings.COOKIE_FIREFOX_LABEL),
    SAFARI("safari", RadioStrings.COOKIE_SAFARI_LABEL);

    companion object {
        fun fromKey(key: String): CookieBrowserOption =
            entries.firstOrNull { it.key == key } ?: NONE
    }

    override fun toString(): String = label
}
