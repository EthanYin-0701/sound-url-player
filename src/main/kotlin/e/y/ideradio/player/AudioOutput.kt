package e.y.ideradio.player

import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine
import e.y.ideradio.RadioStrings

/**
 * 音频输出（设计 §7.1/§7.3）：SourceDataLine 播放 s16le 立体声 PCM。
 *
 * - [open] 后由解码 consumer 逐块 [writeFrames]；
 * - [closeNow]（v0.6 低优8）：先 flush 再 close 当前 line，**唤醒阻塞在 write 的
 *   writer 线程**（close 后 write 抛 IllegalStateException → 写循环退出），供
 *   暂停/停止/切歌路径使用；恢复/重播时创建新 line（本类实例通常一次播放一个）。
 * - [framePosition]：累计写入帧数（volatile），供暂停时记录进度。
 */
class AudioOutput(
    private val sampleRate: Float = 44100f,
    private val channels: Int = 2,
) {
    private var line: SourceDataLine? = null
    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)

    @Volatile
    private var framesWritten: Long = 0L

    private val format: AudioFormat =
        AudioFormat(sampleRate, 16, channels, true, false) // signed, little-endian

    val framePosition: Long
        get() = framesWritten

    fun secondsPosition(): Double = framesWritten.toDouble() / sampleRate

    /**
     * 已**实际播放**的秒数（高4/高5）：基于 `SourceDataLine.getLongFramePosition()`，
     * 不含已写入但仍在声卡缓冲中未播出的部分，是记录暂停进度 / 广播 Position 的正确口径。
     * line 未 open 或已 close 时返回 0。
     */
    fun playedSeconds(): Double {
        val l = line ?: return 0.0
        return runCatching { l.getLongFramePosition().toDouble() / sampleRate }.getOrDefault(0.0)
    }

    fun open() {
        synchronized(this) {
            if (line != null) return
            val l = AudioSystem.getSourceDataLine(format)
            l.open(format, 8192)
            l.start()
            line = l
            closed.set(false)
        }
    }

    /** 写入 [count] 帧（left/right 归一化 float，-1..1）。可能阻塞；closeNow 唤醒。 */
    fun writeFrames(left: FloatArray, right: FloatArray, count: Int) {
        val l = line ?: throw IllegalStateException(RadioStrings.AUDIO_OUTPUT_NOT_OPEN)
        val bytes = ByteArray(count * channels * 2)
        var p = 0
        for (i in 0 until count) {
            writeSample(left[i], bytes, p); p += 2
            writeSample(right[i], bytes, p); p += 2
        }
        l.write(bytes, 0, bytes.size)
        framesWritten += count
    }

    private fun writeSample(sample: Float, buf: ByteArray, offset: Int) {
        val v = (sample.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
        buf[offset] = (v.toInt() and 0xFF).toByte()
        buf[offset + 1] = ((v.toInt() shr 8) and 0xFF).toByte()
    }

    /** 暂停/停止/切歌：flush + close 唤醒 writer（v0.6）。可重复调用（幂等）。 */
    fun closeNow() {
        if (closed.compareAndSet(false, true)) {
            val l = line ?: return
            runCatching { l.flush() }
            runCatching { l.stop() }
            runCatching { l.close() }
        }
    }

    /** 自然结束后收尾（与 closeNow 幂等）。 */
    fun close() = closeNow()

    fun isClosed(): Boolean = closed.get()
}
