package e.y.ideradio.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil
import com.intellij.util.xmlb.annotations.Tag
import e.y.ideradio.resolve.model.MediaPlatform
import e.y.ideradio.resolve.model.SlotState

/**
 * 设置根状态（application 级持久化；设计 §4.1，v0.6）。
 *
 * - 可序列化 bean：可变属性 + 默认值，经 XML 序列化到 `ideRadioSettings.xml`。
 * - 两个固定槽位用 [youtube]/[bilibili] 字段（不用 Map，规避 Map+枚举键序列化不确定性）。
 * - 属性默认值兜底：加载旧状态缺字段时安全。
 *
 * 注：具体序列化形式（bean + @Tag vs BaseState）按设计 §13-T6 以 2025.3 实测为准；
 * 本实现采用平台长期稳定的注解 bean 方案，runIde 阶段验证。
 */
@State(name = "IdeRadioSettings", storages = [Storage("ideRadioSettings.xml")])
class RadioSettingsState : PersistentStateComponent<RadioSettingsState> {

    /** 全局循环播放（默认勾选）。 */
    var loopEnabled: Boolean = true

    /** 自动检测或用户浏览选择的 yt-dlp / ffmpeg 路径（空 = 自动查找）。 */
    var ytDlpPath: String = ""
    var ffmpegPath: String = ""

    /**
     * 解析用 Cookie 来源（缓解 B 站风控/412，v0.10）：
     * - [cookiesFromBrowser]：浏览器名（yt-dlp `--cookies-from-browser`，如 chrome/firefox/edge），空 = 不用；
     * - [cookiesFilePath]：cookies.txt 路径（yt-dlp `--cookies <file>`），空 = 不用。
     * 二者仅填一个时生效；同时填时优先 [cookiesFromBrowser]。
     */
    var cookiesFromBrowser: String = ""
    var cookiesFilePath: String = ""

    /** 两个互斥播放源槽位（固定字段，非 Map）。 */
    @get:Tag("youtube")
    var youtube: SlotState = SlotState()

    @get:Tag("bilibili")
    var bilibili: SlotState = SlotState()

    /** 高级设置折叠状态（默认折叠）。 */
    var advancedExpanded: Boolean = false

    fun slot(platform: MediaPlatform): SlotState = when (platform) {
        MediaPlatform.YOUTUBE -> youtube
        MediaPlatform.BILIBILI -> bilibili
    }

    override fun getState(): RadioSettingsState = this

    override fun loadState(state: RadioSettingsState) {
        XmlSerializerUtil.copyBean(state, this)
    }
}
