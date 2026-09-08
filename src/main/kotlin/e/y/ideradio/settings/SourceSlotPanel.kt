package e.y.ideradio.settings

import com.intellij.ui.JBColor
import com.intellij.ui.ContextHelpLabel
import e.y.ideradio.RadioStrings
import e.y.ideradio.player.PlaybackEvent
import e.y.ideradio.player.PlaybackService
import e.y.ideradio.player.PlaybackSource
import e.y.ideradio.player.PlaybackState
import e.y.ideradio.resolve.model.MediaPlatform
import e.y.ideradio.resolve.model.ResolvedTrack
import e.y.ideradio.resolve.model.SlotState
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.SwingUtilities
import javax.swing.border.TitledBorder

/**
 * 单平台栏（设计 §5.2，v0.6 控件规格）：
 *
 * ```
 * YouTube ───────────────────────────── [● 激活中] [设为当前源]
 * 链接: […………………………………]      [解析预览]
 * 标题: 《…》 时长: 03:45
 *   [▶ 播放] [⏸ 暂停] [⏹ 停止]
 * 错误行（红字）
 * ```
 *
 * 互斥（方案 A，v0.6 三态）：
 * - `selectedPlatform == null`：两栏均非激活、互不灰化；已解析槽位仅 [播放] 可用
 *   （点击隐含激活该栏）；
 * - 已选平台：该栏为激活栏（三键按播放状态启用），另一栏为非激活栏（三键整组灰掉）；
 * - 播放中：激活栏按 PLAYING/PAUSED 切换 暂停/恢复/停止。
 * URL 编辑 / 解析预览 / 标题时长 **不随激活灰化**，始终可用。
 *
 * 注意：不直接改 `selectedPlatform` —— 变更一律经 [PlaybackService.selectPlatform]/[play]
 * 原子 API；预览只更新本栏 UI，不激活（v0.5 规则）。
 */
class SourceSlotPanel(
    val platform: MediaPlatform,
    private val playback: PlaybackService,
    private val stateRef: () -> RadioSettingsState,
    /**
     * 本栏会话草稿读取（由 Configurable 注入 `{ drafts[platform] }`，方案 B1）：
     * 只读回调，避免在面板里复制草稿状态造成第二真相源。
     */
    private val draftRef: () -> ResolvedTrack?,
    /** 点「解析预览」回调（由 Configurable 在后台执行解析）。 */
    private val previewHandler: (String) -> Unit,
) {

    private val urlField = JTextField(16).apply {
        minimumSize = Dimension(220, preferredSize.height)
    }
    private val previewButton = JButton(RadioStrings.PREVIEW_BUTTON)
    private val retryButton = JButton(RadioStrings.RETRY_BUTTON)
    private val activateButton = JButton(RadioStrings.ACTIVATE_BUTTON)
    private val activeBadge = JLabel(RadioStrings.ACTIVE_BADGE_INACTIVE)
    private val metaLabel = JLabel(RadioStrings.META_NOT_RESOLVED).apply {
        minimumSize = Dimension(50, preferredSize.height)
    }
    private val playButton = JButton(RadioStrings.PLAY_BUTTON)
    private val pauseButton = JButton(RadioStrings.PAUSE_BUTTON)
    private val stopButton = JButton(RadioStrings.STOP_BUTTON)

    /** 错误提示：可换行、不撑开设置页（评审 #16）；仅在出错时可见。 */
    private val errorLabel = JTextArea().apply {
        isEditable = false
        isOpaque = false
        isFocusable = false
        lineWrap = true
        wrapStyleWord = true
        foreground = JBColorSafe.error()
        border = null
        alignmentX = 0f
        maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
        isVisible = false
    }

    private val root = JPanel(BorderLayout())
    private val listener: (PlaybackEvent) -> Unit = { event ->
        // 控制 executor 线程回调 → 切回 EDT 刷新
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater { refresh() }
        } else {
            refresh()
        }
    }

    init {
        urlField.document.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent?) { onUrlEdited() }
            override fun removeUpdate(e: javax.swing.event.DocumentEvent?) { onUrlEdited() }
            override fun changedUpdate(e: javax.swing.event.DocumentEvent?) { onUrlEdited() }
        })

        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.alignmentX = Component.LEFT_ALIGNMENT
        panel.border = BorderFactory.createTitledBorder(
            BorderFactory.createEtchedBorder(),
            platform.displayName(),
            TitledBorder.LEFT, TitledBorder.TOP,
        )

        // 头部：平台名 + 激活徽标 + 设为当前源 + 互斥说明小问号
        val header = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0))
        header.alignmentX = Component.LEFT_ALIGNMENT
        val titleLabel = JLabel(platform.displayName()).apply {
            // 样式+字号一次指定：deriveFont(int style, float size)；勿拆成 .toFloat()（会命中 float 字号重载，把加粗做成字号）
            font = font.deriveFont(java.awt.Font.BOLD, font.size2D + 1f)
        }
        activeBadge.foreground = JBColor.namedColor("Label.successForeground", JBColor(0x1B7A3D, 0x5FAD65)) // 激活绿（主题自适应）
        header.add(titleLabel)
        header.add(activeBadge)
        header.add(Box.createHorizontalStrut(12))
        header.add(activateButton)
        header.add(Box.createHorizontalStrut(6))
        header.add(ContextHelpLabel.create(RadioStrings.HELP_TOOLTIP))

        // 链接行
        val urlRow = JPanel(GridBagLayout()).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            add(JLabel(RadioStrings.URL_LABEL), GridBagConstraints().apply {
                gridx = 0
                gridy = 0
                insets = Insets(0, 0, 0, 4)
                anchor = GridBagConstraints.WEST
            })
            add(urlField, GridBagConstraints().apply {
                gridx = 1
                gridy = 0
                weightx = 1.0
                fill = GridBagConstraints.HORIZONTAL
                anchor = GridBagConstraints.WEST
            })
        }

        // 操作行（解析预览 + 重试，重试仅在出错时可见）
        val actionRow = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0))
        actionRow.alignmentX = Component.LEFT_ALIGNMENT
        actionRow.add(previewButton)
        actionRow.add(retryButton)
        retryButton.isVisible = false

        // 元信息行
        val metaRow = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0))
        metaRow.alignmentX = Component.LEFT_ALIGNMENT
        metaRow.add(metaLabel)

        // 控制按钮行（独立成行，L5）
        val controlRow = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0))
        controlRow.alignmentX = Component.LEFT_ALIGNMENT
        controlRow.add(playButton)
        controlRow.add(pauseButton)
        controlRow.add(stopButton)

        panel.add(header)
        panel.add(urlRow)
        panel.add(actionRow)
        panel.add(metaRow)
        panel.add(controlRow)
        panel.add(errorLabel)
        root.add(panel, BorderLayout.NORTH)
        root.alignmentX = Component.LEFT_ALIGNMENT
        root.border = BorderFactory.createEmptyBorder(4, 4, 4, 4)

        activateButton.addActionListener { playback.selectPlatform(platform) }
        playButton.addActionListener {
            // PAUSED 且当前激活即本栏 → 续播（会话内已存覆盖，无需重传）；否则从头播放该栏
            if (playback.state == PlaybackState.PAUSED && playback.selectedPlatform == platform) {
                playback.resume()
            } else {
                // 方案 B：预览草稿与当前输入框 URL 一致时，把草稿作为本次播放的显式来源
                val url = url()
                val override = draftRef()
                    ?.takeIf { it.sourceUrl == url }
                    ?.let { PlaybackSource(it.sourceUrl, it) }
                playback.play(platform, override = override)
            }
        }
        pauseButton.addActionListener { playback.togglePause() }
        stopButton.addActionListener { playback.stop() }
        previewButton.addActionListener { previewHandler(urlField.text.trim()) }
        // 重试 = 用当前输入框 URL 重新预览（评审 #16：解析失败提供 [重试]）
        retryButton.addActionListener { previewHandler(urlField.text.trim()) }

        playback.addListener(listener)
        refresh()
    }

    /** 当前编辑框 URL。 */
    fun url(): String = urlField.text.trim()

    /** 从持久化设置填充（reset 时调用）。 */
    fun resetFromState() {
        val slot = slotState()
        urlField.text = slot.sourceUrl
        urlField.caretPosition = 0
        urlField.toolTipText = slot.sourceUrl.ifBlank { null }
        val text = if (slot.title.isBlank()) RadioStrings.META_NOT_RESOLVED else RadioStrings.savedMetaText(slot.title, formatDuration(slot.durationSeconds))
        metaLabel.text = text
        metaLabel.toolTipText = if (slot.title.isBlank()) null else text
        setError(null)
        refresh()
    }

    /** 预览结果回填（Configurable 后台解析完成后，EDT 调用）。 */
    fun applyPreview(title: String?, durationSeconds: Long?, error: String?) {
        if (error != null) {
            setError(error)
        } else {
            setError(null)
            val t = title ?: RadioStrings.UNKNOWN_TITLE
            val text = RadioStrings.previewMetaText(t, formatDuration(durationSeconds ?: -1))
            metaLabel.text = text
            metaLabel.toolTipText = text
        }
        // 方案 B1 第 1 层根因：预览/重试成功/失败后都要重算三键使能
        // （草稿就绪即可播，不再等 Apply/PlaybackEvent 才点亮）
        refresh()
    }

    /** 预览发起中提示（避免"点了没反应"，高2）。 */
    fun showResolving() {
        setError(null)
        metaLabel.text = RadioStrings.META_RESOLVING
        metaLabel.toolTipText = null
        refresh() // 解析中不沿用旧使能态（如旧草稿已失效应即时反映）
    }

    /** 统一错误展示：控制换行区可见性 + [重试] 按钮（评审 #16）。 */
    private fun setError(message: String?) {
        val hasError = !message.isNullOrBlank()
        errorLabel.text = message ?: " "
        errorLabel.isVisible = hasError
        retryButton.isVisible = hasError
        // 从"有错"回到"无错"时 FlowLayout 也要立刻收起 [重试] 空位（方案 B 第四节）
        root.revalidate()
        root.repaint()
    }

    /** URL 编辑后：更新 tooltip 并重算使能（草稿因 sourceUrl 不匹配自动失效即变灰，B1）。 */
    private fun onUrlEdited() {
        urlField.toolTipText = urlField.text.ifBlank { null }
        refresh()
    }

    /** 播放状态 / 激活互斥刷新（设计 §5.4 三态 + 方案 B1 草稿感知使能）。 */
    fun refresh() {
        val selected = playback.selectedPlatform
        val active = selected == platform
        val state = playback.state
        val slot = slotState()

        // 激活徽标与按钮文案（可点击，切换激活源）
        if (active) {
            activeBadge.text = RadioStrings.ACTIVE_BADGE_ACTIVE
            activateButton.text = "${RadioStrings.ACTIVATE_BUTTON}${RadioStrings.ACTIVATED_SUFFIX}"
            activateButton.isEnabled = false
        } else {
            activeBadge.text = RadioStrings.ACTIVE_BADGE_INACTIVE
            activateButton.text = RadioStrings.ACTIVATE_BUTTON
            activateButton.isEnabled = true
        }

        // 方案 B1：可播 = 持久化槽位可播 **或** 本栏草稿与当前输入框 URL 一致
        // （预览/重试成功后未 Apply 也能点亮；URL 一改即因 sourceUrl 不匹配而失效）
        val url = url()
        val draft = draftRef()?.takeIf { it.sourceUrl == url }
        val canPlay = slot.isPlayable() || draft != null
        playButton.toolTipText = if (draft != null) {
            RadioStrings.DRAFT_PLAY_TOOLTIP
        } else {
            null
        }

        // 三键三态（v0.6 低优1 定稿）
        when {
            !active && selected != null -> {
                // 非激活栏：三键整组灰掉
                playButton.isEnabled = false
                pauseButton.isEnabled = false
                stopButton.isEnabled = false
            }
            !active && selected == null -> {
                // 两栏均未激活：已解析槽位（或草稿）仅 [播放] 可用（点击隐含激活）
                playButton.isEnabled = canPlay
                pauseButton.isEnabled = false
                stopButton.isEnabled = false
            }
            else -> {
                // 激活栏：按播放状态
                when (state) {
                    PlaybackState.PLAYING -> {
                        playButton.isEnabled = false
                        pauseButton.isEnabled = true
                        stopButton.isEnabled = true
                    }
                    PlaybackState.PAUSED -> {
                        playButton.isEnabled = true
                        pauseButton.isEnabled = true
                        stopButton.isEnabled = true
                    }
                    PlaybackState.LOADING -> {
                        playButton.isEnabled = false
                        pauseButton.isEnabled = false
                        stopButton.isEnabled = true
                    }
                    else -> { // IDLE / ERROR
                        playButton.isEnabled = canPlay
                        pauseButton.isEnabled = false
                        stopButton.isEnabled = false
                    }
                }
            }
        }
    }

    private fun slotState(): SlotState = stateRef().slot(platform)

    /** dispose：注销播放事件监听。 */
    fun dispose() {
        playback.removeListener(listener)
    }

    fun component(): JComponent = root

    private fun MediaPlatform.displayName(): String = when (this) {
        MediaPlatform.YOUTUBE -> RadioStrings.YOUTUBE
        MediaPlatform.BILIBILI -> RadioStrings.BILIBILI
    }

    companion object {
        private fun formatDuration(seconds: Long): String {
            if (seconds < 0) return RadioStrings.UNKNOWN_DURATION
            val m = seconds / 60
            val s = seconds % 60
            return "%02d:%02d".format(m, s)
        }
    }
}

/** 平台无关错误色兜底（主题自适应优化留 M3）。 */
private object JBColorSafe {
    fun error(): Color = Color(0xC0, 0x28, 0x28)
}
