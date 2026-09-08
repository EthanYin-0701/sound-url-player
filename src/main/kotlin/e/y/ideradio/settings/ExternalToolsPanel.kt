package e.y.ideradio.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.ui.JBColor
import e.y.ideradio.RadioStrings
import e.y.ideradio.resolve.external.ExternalProcessRunner
import e.y.ideradio.resolve.external.ExternalToolLocator
import e.y.ideradio.resolve.external.ToolLookup
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.SwingUtilities

/** Settings 外部工具区：只检测本机安装，不下载或升级任何二进制。 */
class ExternalToolsPanel(
    private val stateRef: () -> RadioSettingsState,
) : JPanel(GridBagLayout()) {

    private val disposed = AtomicBoolean(false)
    private val ytStatus = JLabel(RadioStrings.DETECTING_STATUS, SwingConstants.LEFT).apply {
        minimumSize = Dimension(50, preferredSize.height)
    }
    private val ffStatus = JLabel(RadioStrings.DETECTING_STATUS, SwingConstants.LEFT).apply {
        minimumSize = Dimension(50, preferredSize.height)
    }
    private val ytBrowseButton = JButton(RadioStrings.BROWSE_BUTTON)
    private val ffBrowseButton = JButton(RadioStrings.BROWSE_BUTTON)
    private val ytResetButton = JButton(RadioStrings.RE_AUTO_DETECT_BUTTON)
    private val ffResetButton = JButton(RadioStrings.RE_AUTO_DETECT_BUTTON)
    private val reDetectButton = JButton(RadioStrings.RE_DETECT_BUTTON)

    private var pendingYtDlpPath = stateRef().ytDlpPath
    private var pendingFfmpegPath = stateRef().ffmpegPath
    private val errorColor = JBColor.namedColor("Component.errorForeground", JBColor(0xC02828, 0xED5959))
    private val okColor = JBColor.namedColor("Label.successForeground", JBColor(0x1B7A3D, 0x5FAD65))

    init {
        alignmentX = Component.LEFT_ALIGNMENT
        border = BorderFactory.createTitledBorder(BorderFactory.createEtchedBorder(), RadioStrings.EXTERNAL_TOOLS_TITLE)
        addRow(0, RadioStrings.YT_DLP, ytStatus, ytBrowseButton, ytResetButton)
        addRow(1, RadioStrings.FFMPEG, ffStatus, ffBrowseButton, ffResetButton)
        val actions = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0))
        actions.add(JLabel(RadioStrings.EXTERNAL_TOOL_MISSING_HINT))
        actions.add(reDetectButton)
        add(actions, GridBagConstraints().apply {
            insets = Insets(2, 6, 2, 6)
            anchor = GridBagConstraints.WEST
            gridx = 1
            gridy = 2
            weightx = 1.0
        })

        ytBrowseButton.addActionListener { chooseTool(RadioStrings.YT_DLP) { pendingYtDlpPath = it } }
        ffBrowseButton.addActionListener { chooseTool(RadioStrings.FFMPEG) { pendingFfmpegPath = it } }
        ytResetButton.addActionListener { pendingYtDlpPath = ""; detect() }
        ffResetButton.addActionListener { pendingFfmpegPath = ""; detect() }
        reDetectButton.addActionListener { detect() }
    }

    private fun addRow(row: Int, name: String, status: JLabel, vararg buttons: JButton) {
        val gbc = GridBagConstraints().apply {
            insets = Insets(2, 6, 2, 6)
            anchor = GridBagConstraints.WEST
            gridx = 0
            gridy = row
        }
        add(JLabel(name), gbc)
        gbc.gridx = 1
        gbc.weightx = 1.0
        gbc.fill = GridBagConstraints.HORIZONTAL
        add(status, gbc)
        val controls = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0))
        buttons.forEach(controls::add)
        gbc.gridx = 2
        gbc.weightx = 0.0
        gbc.fill = GridBagConstraints.NONE
        add(controls, gbc)
    }

    /** 后台检测；自动发现时把绝对路径写入待 Apply 的设置。 */
    fun detect(onDone: (Boolean) -> Unit = {}) {
        onEdt {
            ytStatus.text = RadioStrings.DETECTING_STATUS
            ytStatus.toolTipText = null
            ffStatus.text = RadioStrings.DETECTING_STATUS
            ffStatus.toolTipText = null
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            if (disposed.get()) return@executeOnPooledThread
            val yt = ExternalToolLocator.lookupYtDlp(pendingYtDlpPath)
            val ff = ExternalToolLocator.lookupFfmpeg(pendingFfmpegPath)
            val ytVersion = (yt as? ToolLookup.Found)?.let { probeVersion(it.path, "--version") }
            val ffVersion = (ff as? ToolLookup.Found)?.let { probeVersion(it.path, "-version") }
            val failing = (yt !is ToolLookup.Found || ytVersion == null) ||
                          (ff !is ToolLookup.Found || ffVersion == null)
            onEdt {
                pendingYtDlpPath = render(RadioStrings.YT_DLP, ytStatus, yt, ytVersion, pendingYtDlpPath)
                pendingFfmpegPath = render(RadioStrings.FFMPEG, ffStatus, ff, ffVersion, pendingFfmpegPath)
                onDone(failing)
            }
        }
    }

    private fun render(name: String, label: JLabel, lookup: ToolLookup, version: String?, currentPath: String): String =
        when (lookup) {
            is ToolLookup.Found -> if (version == null) {
                label.foreground = errorColor
                val msg = RadioStrings.toolCannotRun(lookup.path)
                label.text = msg
                label.toolTipText = msg
                currentPath
            } else {
                label.foreground = okColor
                val msg = RadioStrings.toolFoundApply(lookup.path, version)
                label.text = msg
                label.toolTipText = msg
                File(lookup.path).absolutePath
            }
            is ToolLookup.InvalidExplicitPath -> {
                label.foreground = errorColor
                val msg = RadioStrings.toolInvalidPath(lookup.path)
                label.text = msg
                label.toolTipText = msg
                currentPath
            }
            ToolLookup.NotConfigured -> {
                label.foreground = errorColor
                val msg = RadioStrings.toolNotFound(name)
                label.text = msg
                label.toolTipText = msg
                currentPath
            }
        }

    private fun probeVersion(executable: String, versionArg: String): String? = runCatching {
        val result = ExternalProcessRunner().runCaptured(
            executable = executable,
            args = listOf(versionArg),
            timeoutMillis = 10_000,
            stdoutLimit = 64 * 1024,
            stderrLimit = 8 * 1024,
        )
        if (result.timedOut || result.exitCode != 0) return@runCatching null
        result.stdout.toString(Charsets.UTF_8).lineSequence()
            .firstOrNull { it.isNotBlank() }?.trim()?.take(80)
    }.getOrNull()

    private fun chooseTool(name: String, accept: (String) -> Unit) {
        val descriptor = FileChooserDescriptor(true, false, false, false, false, false)
            .withTitle(RadioStrings.chooseToolTitle(name))
            .withDescription(RadioStrings.CHOOSE_TOOL_DESC)
        val selected = FileChooser.chooseFile(descriptor, null, null as com.intellij.openapi.vfs.VirtualFile?) ?: return
        val file = File(selected.path)
        if (!file.isFile || !file.canExecute()) {
            val label = if (name == RadioStrings.YT_DLP) ytStatus else ffStatus
            label.foreground = errorColor
            val msg = RadioStrings.FILE_NOT_EXECUTABLE
            label.text = msg
            label.toolTipText = msg
            return
        }
        accept(file.absolutePath)
        detect()
    }

    fun isModified(): Boolean =
        pendingYtDlpPath != stateRef().ytDlpPath || pendingFfmpegPath != stateRef().ffmpegPath

    fun apply() {
        stateRef().ytDlpPath = pendingYtDlpPath
        stateRef().ffmpegPath = pendingFfmpegPath
    }

    fun reset() {
        pendingYtDlpPath = stateRef().ytDlpPath
        pendingFfmpegPath = stateRef().ffmpegPath
        detect()
    }

    fun disposePanel() {
        disposed.set(true)
    }

    private fun onEdt(action: () -> Unit) {
        if (disposed.get()) return
        if (SwingUtilities.isEventDispatchThread()) action() else SwingUtilities.invokeLater {
            if (!disposed.get()) action()
        }
    }
}
