package e.y.ideradio.viz

import com.intellij.ui.JBColor
import java.awt.Color
import java.awt.Graphics2D
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow

/**
 * 频谱柱状渲染（设计 §8.3）：取最近 [FFT_N] 样本 → Hann 窗 → FFT → 对数频带聚合
 * （[BANDS] 根柱，20 Hz–20 kHz）→ dB 归一化高度；带峰值 attack/decay 平滑。
 */
class SpectrumRenderer(
    val fftSize: Int = 1024,
    private val bands: Int = 32,
    private val sampleRate: Float = 44100f,
) {
    private val window = FloatArray(fftSize) { i ->
        (0.5 - 0.5 * cos(2.0 * PI * i / (fftSize - 1))).toFloat() // Hann
    }
    private val bandEdges = computeLogBands(bands, 20f, 20000f, sampleRate, fftSize)
    private val peak = FloatArray(bands)
    private val current = FloatArray(bands)

    /** 输入左右声道合并（取平均），返回 band 高度数组 0..1。 */
    fun computeLevels(left: FloatArray, right: FloatArray): FloatArray {
        val n = minOf(left.size, right.size, fftSize)
        val mono = FloatArray(fftSize)
        for (i in 0 until n) mono[i] = (left[i] + right[i]) * 0.5f
        for (i in 0 until fftSize) mono[i] *= window[i]

        val spec = Fft.magnitudeSpectrum(mono)

        // 每 band 平均幅度 → dB（相对满幅）
        for (b in 0 until bands) {
            val lo = bandEdges[b]
            val hi = bandEdges[b + 1]
            var sum = 0.0
            var cnt = 0
            for (k in lo until hi) {
                if (k < spec.size) {
                    sum += spec[k].toDouble().pow(2.0)
                    cnt++
                }
            }
            val rms = if (cnt > 0) kotlin.math.sqrt(sum / cnt) else 0.0
            // 0 dBFS ≈ 1.0 → dB 值；-60 dB 以下视为 0
            val db = 20.0 * kotlin.math.log10(rms.coerceAtLeast(1e-6))
            current[b] = ((db + 60.0) / 60.0).toFloat().coerceIn(0f, 1f)
        }

        // 峰值平滑：attack 立即跟上、decay 缓降（观感类似均衡器）
        for (b in 0 until bands) {
            peak[b] = if (current[b] >= peak[b]) current[b]
            else peak[b] * DECAY + current[b] * (1f - DECAY)
        }
        return peak.copyOf()
    }

    fun draw(g: Graphics2D, width: Int, height: Int, levels: FloatArray, color: Color, base: Color) {
        val barW = width.toFloat() / levels.size
        g.color = base
        g.fillRect(0, 0, width, height)
        for (i in levels.indices) {
            val h = (levels[i] * (height - 4f)).toInt().coerceAtLeast(1)
            val x = (i * barW + 1f).toInt()
            val w = (barW - 2f).coerceAtLeast(1f).toInt()
            g.color = color
            g.fillRect(x, height - h, w, h)
        }
    }

    companion object {
        const val DECAY = 0.82f
    }
}

/** 对数频带边界（bin 索引）：20 Hz–20 kHz 按人耳对数刻度均分。 */
private fun computeLogBands(bands: Int, fMin: Float, fMax: Float, sampleRate: Float, fftSize: Int): IntArray {
    val edges = IntArray(bands + 1)
    val logMin = ln(fMin.toDouble())
    val logMax = ln(fMax.toDouble())
    for (b in 0..bands) {
        val freq = kotlin.math.exp(logMin + (logMax - logMin) * b / bands)
        val bin = (freq / sampleRate * fftSize).toInt().coerceIn(0, fftSize / 2)
        edges[b] = bin
    }
    return edges
}
