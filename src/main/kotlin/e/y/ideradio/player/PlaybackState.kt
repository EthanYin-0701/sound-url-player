package e.y.ideradio.player

import e.y.ideradio.resolve.model.MediaPlatform
import e.y.ideradio.resolve.model.ResolvedTrack

/**
 * 播放状态机（设计 §4.3）：
 * IDLE → LOADING → PLAYING ⇄ PAUSED；LOADING 失败 → ERROR；stop/自然结束 → IDLE。
 */
enum class PlaybackState { IDLE, LOADING, PLAYING, PAUSED, ERROR }

/**
 * 播放会话事件（设计 §4.4）。由 [PlaybackService] 广播；设置页与各 Tool Window 面板
 * 订阅后各自渲染。注意：PCM 可视化数据**不走事件总线**，改由 `PcmBuffer` 快照拉取。
 */
sealed class PlaybackEvent {
    data class StateChanged(val state: PlaybackState) : PlaybackEvent()

    data class TrackChanged(
        val platform: MediaPlatform?,
        val track: ResolvedTrack?,
    ) : PlaybackEvent()

    /** 周期性进度（秒），用于刷新进度显示。 */
    data class Position(val seconds: Long) : PlaybackEvent()
}
