package e.y.ideradio.resolve

import com.intellij.openapi.components.service
import e.y.ideradio.resolve.model.MediaPlatform
import e.y.ideradio.resolve.model.ResolvedTrack
import e.y.ideradio.settings.RadioSettingsState
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 全局解析协调（设计 §6.4/§6.5/D18，v0.8）：
 * - **提交缓存**：播放取流/回写标题时用的"已提交（可播放）"结果；
 * - **平台递增版本号**：预览/提交解析每次发起递增；后台任务返回时校验
 *   "版本号仍 == 当前"且"来源 URL == 当前槽位 URL"，迟到结果不覆盖新状态；
 * - **不依赖设置页曾被打开**：本服务构造即持有 application 级 [RadioSettingsState]，
 *   播放取流可按持久化槽位 URL 直接重新解析（评审 #7）。
 *
 * 说明：实际解析执行由调用方放后台线程；[TrackResolver.resolve(url, cancelled)] 支持
 * 取消在途 yt-dlp（评审 #5）；版本号保证最终一致性（取消尽力而为）。
 */
class ResolveManager {

    private val settings: RadioSettingsState = service()

    private val committed = ConcurrentHashMap<MediaPlatform, ResolvedTrack>()
    private val versions = ConcurrentHashMap<MediaPlatform, AtomicLong>()

    /** 发起新一轮解析并登记版本；返回该轮版本号。 */
    fun begin(platform: MediaPlatform): Long =
        versions.computeIfAbsent(platform) { AtomicLong(0L) }.incrementAndGet()

    /** 该版本是否仍是最新（未被更新的解析取代）。 */
    fun isCurrent(platform: MediaPlatform, version: Long): Boolean =
        versions[platform]?.get() == version

    /** 当前版本对应的槽位 URL 是否仍等于 [url]（防止旧 URL 的迟到结果覆盖新 URL）。 */
    fun isUrlCurrent(platform: MediaPlatform, url: String): Boolean =
        sourceUrlOf(platform) == url

    /** 提交解析成功：写入全局缓存（供播放取流）。 */
    fun commit(platform: MediaPlatform, track: ResolvedTrack) {
        committed[platform] = track
    }

    /**
     * 播放取流（**在后台线程调用**，可能跑最长 30s yt-dlp）：
     * 有未过期缓存则复用；否则按持久化槽位 URL 用 [resolveFn] 重新解析并提交。
     * 返回 null = 无槽位 URL / 工具缺失 / 解析失败（调用方进入 ERROR 提示）。
     */
    fun obtainForPlayback(
        platform: MediaPlatform,
        resolveFn: (url: String, cancelled: () -> Boolean) -> ResolvedTrack,
        cancelled: () -> Boolean = { false },
    ): ResolvedTrack? {
        committed[platform]?.let { track ->
            if (isFresh(track)) return track
        }
        val url = sourceUrlOf(platform) ?: return null
        return runCatching { resolveFn(url, cancelled) }
            .onSuccess { committed[platform] = it }
            .getOrNull()
    }

    /** 缓存直链是否仍有效（TTL 30 min）。 */
    fun isFresh(track: ResolvedTrack): Boolean =
        System.currentTimeMillis() - track.resolvedAtEpochMs < TTL_MS

    fun cached(platform: MediaPlatform): ResolvedTrack? = committed[platform]

    fun sourceUrlOf(platform: MediaPlatform): String? =
        settings.slot(platform).sourceUrl.ifBlank { null }

    companion object {
        const val TTL_MS = 30 * 60 * 1000L
    }
}
