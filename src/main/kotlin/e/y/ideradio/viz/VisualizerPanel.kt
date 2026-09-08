package e.y.ideradio.viz

import com.intellij.openapi.Disposable
import com.intellij.ui.JBColor
import e.y.ideradio.RadioStrings
import e.y.ideradio.player.PlaybackEvent
import e.y.ideradio.player.PlaybackService
import e.y.ideradio.player.PlaybackState
import e.y.ideradio.player.PcmBuffer
import e.y.ideradio.resolve.model.MediaPlatform
import e.y.ideradio.settings.RadioSettingsState
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.font.TextAttribute
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.Timer

/**
 * IDE Radio Tool Window 内容面板（设计 §8）：可视化画布（波形/柱状，单击切换）+
 * 顶部当前曲目信息 + 底部播放/暂停/停止（与设置页同一 PlaybackService，天然同步）。
 *
 * - 数据通路：解码侧写 [PcmBuffer]；本面板 Swing Timer（约 30 fps）**拉取最新快照**绘制
 *   （latest-only；仅窗口可见时工作；dispose 停止，设计 §4.4/§8.5）。
 * - 播放目标 = `selectedPlatform`；null（重启未选择）→ [播放] 禁用并提示去 Settings。
 * - 多窗口：每个 project 一份面板，都订阅同一 application 服务；[dispose] 注销监听。
 */
class VisualizerPanel(
    private val playback: PlaybackService,
    private val pcm: PcmBuffer,
    settings: RadioSettingsState,
) : JPanel(BorderLayout()), Disposable {

    enum class Mode { WAVEFORM, SPECTRUM }

    private val settingsRef: () -> RadioSettingsState = { settings }

    // 顶部信息条
    private val trackLabel = JLabel(RadioStrings.NOT_PLAYING, SwingConstants.LEFT)
    private val progressLabel = JLabel("", SwingConstants.CENTER)
    private val modeLabel = JLabel("", SwingConstants.RIGHT)

    // 曲目/进度状态（由事件驱动，EDT 更新）
    private var currentDurationSeconds: Long = -1
    private var currentPositionSeconds: Long = 0L

    // 底部控制
    private val playButton = JButton(RadioStrings.PLAY_BUTTON)
    private val pauseButton = JButton(RadioStrings.PAUSE_BUTTON)
    private val stopButton = JButton(RadioStrings.STOP_BUTTON)

    private val canvas = CanvasView()

    private var mode: Mode = Mode.WAVEFORM

    private val spectrum = SpectrumRenderer()
    private var lastLevels: FloatArray = FloatArray(0)

    private val refreshTimer = Timer(REFRESH_MS) {
        if (!isShowing) return@Timer
        val hasAudio = pcm.totalFrames > 0
        val activePlayback = playback.state == PlaybackState.PLAYING || playback.state == PlaybackState.PAUSED
        if (hasAudio || activePlayback || playback.state == PlaybackState.LOADING) {
            canvas.repaint()
        }
    }

    private val listener: (PlaybackEvent) -> Unit = { event -> onEdt { onPlaybackEvent(event) } }

    init {
        // 顶部信息条
        val header = JPanel(BorderLayout())
        header.border = BorderFactory.createEmptyBorder(4, 8, 2, 8)
        trackLabel.font = trackLabel.font.deriveFont(Font.BOLD) // 注意：int 样式重载，勿 .toFloat() 误入 float 字号重载
        progressLabel.foreground = JBColor.namedColor("Component.infoForeground", JBColor.GRAY)
        modeLabel.foreground = JBColor.namedColor("Component.infoForeground", JBColor.GRAY)
        header.add(trackLabel, BorderLayout.WEST)
        header.add(progressLabel, BorderLayout.CENTER)
        header.add(modeLabel, BorderLayout.EAST)
        add(header, BorderLayout.NORTH)

        // 画布
        canvas.preferredSize = Dimension(600, 160)
        canvas.minimumSize = Dimension(200, 80)
        canvas.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                toggleMode()
            }
        })
        add(canvas, BorderLayout.CENTER)

        // 底部控制
        val controls = JPanel(FlowLayout(FlowLayout.CENTER, 10, 6))
        controls.add(playButton)
        controls.add(pauseButton)
        controls.add(stopButton)
        controls.border = BorderFactory.createEmptyBorder(2, 0, 4, 0)
        add(controls, BorderLayout.SOUTH)

        playButton.addActionListener {
            if (playback.state == PlaybackState.PAUSED) playback.resume()
            else playback.selectedPlatform?.let { playback.play(it) }
        }
        pauseButton.addActionListener { playback.togglePause() }
        stopButton.addActionListener { playback.stop() }

        playback.addListener(listener)
        refreshTimer.start()
        updateModeLabel()
        refresh()
    }

    private fun toggleMode() {
        mode = if (mode == Mode.WAVEFORM) Mode.SPECTRUM else Mode.WAVEFORM
        updateModeLabel()
        canvas.repaint()
    }

    private fun updateModeLabel() {
        val name = when (mode) {
            Mode.WAVEFORM -> RadioStrings.WAVEFORM_NAME
            Mode.SPECTRUM -> RadioStrings.SPECTRUM_NAME
        }
        modeLabel.text = RadioStrings.modeLabelText(name)
        modeLabel.toolTipText = RadioStrings.canvasTooltip(RadioStrings.WAVEFORM_NAME, RadioStrings.SPECTRUM_NAME)
    }

    private fun onPlaybackEvent(event: PlaybackEvent) {
        when (event) {
            is PlaybackEvent.TrackChanged -> {
                val title = event.track?.title
                val platformName = event.platform?.let { if (it == MediaPlatform.YOUTUBE) RadioStrings.YOUTUBE else RadioStrings.BILIBILI }
                trackLabel.text = when {
                    title != null && platformName != null -> RadioStrings.currentTrackText(title, platformName)
                    else -> RadioStrings.NOT_PLAYING
                }
                currentDurationSeconds = event.track?.durationSeconds ?: -1
                currentPositionSeconds = 0L
                updateProgressLabel()
            }
            is PlaybackEvent.StateChanged -> {
                updateProgressLabel()
            }
            is PlaybackEvent.Position -> {
                currentPositionSeconds = event.seconds
                updateProgressLabel()
            }
        }
        refresh()
    }

    /** 顶部进度/时长/循环文案（高5）。 */
    private fun updateProgressLabel() {
        val hasTrack = trackLabel.text != RadioStrings.NOT_PLAYING
        if (!hasTrack) {
            progressLabel.text = ""
            return
        }
        val pos = formatTime(currentPositionSeconds)
        val dur = if (currentDurationSeconds >= 0) formatTime(currentDurationSeconds) else "--:--"
        val loop = if (settingsRef().loopEnabled) RadioStrings.LOOP_ON else RadioStrings.LOOP_OFF
        progressLabel.text = RadioStrings.progressText(pos, dur, loop)
    }

    private fun formatTime(seconds: Long): String {
        if (seconds < 0) return "--:--"
        val m = seconds / 60
        val s = seconds % 60
        return "%02d:%02d".format(m, s)
    }

    /** 刷新按钮三态（与设置页互斥规则一致，设计 §5.4/§8.5）。 */
    fun refresh() {
        val selected = playback.selectedPlatform
        val active = selected != null // ToolWindow 无平台选择控件：仅当已有激活源可播
        val canPlay = active && settingsRef().slot(selected).isPlayable()
        val state = playback.state

        when {
            !active -> {
                playButton.isEnabled = false
                pauseButton.isEnabled = false
                stopButton.isEnabled = false
            }
            state == PlaybackState.PLAYING -> {
                playButton.isEnabled = false
                pauseButton.isEnabled = true
                stopButton.isEnabled = true
            }
            state == PlaybackState.PAUSED -> {
                playButton.isEnabled = true
                pauseButton.isEnabled = true
                stopButton.isEnabled = true
            }
            state == PlaybackState.LOADING -> {
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

    private fun onEdt(r: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) r() else SwingUtilities.invokeLater { r() }
    }

    override fun dispose() {
        refreshTimer.stop()
        playback.removeListener(listener)
    }

    /** 画布：自绘当前模式。 */
    private inner class CanvasView : JPanel() {
        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val g2 = g as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val w = width
            val h = height
            val base = JBColor.namedColor("Panel.background", JBColor.WHITE)
            val wave = JBColor.namedColor("FileColor.Green", Color(0x1B, 0x9E, 0x77))
            val spectrumColor = JBColor.namedColor("FileColor.Blue", Color(0x40, 0x79, 0xBF))

            val hasAudio = pcm.totalFrames > 0
            val isPlaying = playback.state == PlaybackState.PLAYING || playback.state == PlaybackState.PAUSED

            if (!hasAudio && !isPlaying) {
                // 空闲占位
                g2.color = base
                g2.fillRect(0, 0, w, h)
                g2.color = JBColor.namedColor("Component.disabledForeground", JBColor.GRAY)
                g2.font = g2.font.deriveFont(13f)
                val msg = when {
                    playback.selectedPlatform == null -> "未在播放 — 请先在 Settings → Tools → IDE Radio 配置并播放"
                    else -> "未在播放"
                }
                drawCentered(g2, msg, w, h)
                return
            }

            when (mode) {
                Mode.WAVEFORM -> {
                    val snap = pcm.latest(4096)
                    if (snap == null) {
                        g2.color = base; g2.fillRect(0, 0, w, h)
                        return
                    }
                    WaveformRenderer.draw(g2, w, h, snap.first, wave, base)
                }
                Mode.SPECTRUM -> {
                    val snap = pcm.latest(spectrum.fftSize)
                    if (snap == null) {
                        g2.color = base; g2.fillRect(0, 0, w, h)
                        return
                    }
                    lastLevels = spectrum.computeLevels(snap.first, snap.second)
                    spectrum.draw(g2, w, h, lastLevels, spectrumColor, base)
                }
            }
        }

        private fun drawCentered(g2: Graphics2D, text: String, w: Int, h: Int) {
            val fm = g2.fontMetrics
            val x = (w - fm.stringWidth(text)) / 2
            val y = h / 2 - fm.height / 2 + fm.ascent
            g2.drawString(text, x, y)
        }
    }

    companion object {
        private const val REFRESH_MS = 33 // ~30 fps
    }
}
