package e.y.ideradio.resolve.model

/**
 * 播放源平台。YouTube 与 Bilibili 为**互斥激活源**（设计 v0.5+，方案 A）：
 * 同一时间至多一栏可播放，非激活栏的播放控制灰掉。
 */
enum class MediaPlatform { YOUTUBE, BILIBILI }
