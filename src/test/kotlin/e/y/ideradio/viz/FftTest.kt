package e.y.ideradio.viz

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos

class FftTest {

    @Test
    fun `直流信号频谱集中在第 0 bin`() {
        val spec = Fft.magnitudeSpectrum(FloatArray(1024) { 1f })
        assertEquals(513, spec.size)
        assertEquals(1f, spec[0], 1e-2f)
        for (k in 1 until spec.size) {
            assertEquals(0f, spec[k], 1e-3f)
        }
    }

    @Test
    fun `正弦峰值落在对应 bin`() {
        val n = 1024
        val k0 = 100 // 恰好整数周期
        val sig = FloatArray(n) { i -> cos(2.0 * PI * k0 * i / n).toFloat() }
        val spec = Fft.magnitudeSpectrum(sig)
        // 峰值应在 bin 100（单边幅度约 1）
        var peakBin = 0
        var peakVal = 0f
        for (k in 0 until spec.size) {
            if (spec[k] > peakVal) {
                peakVal = spec[k]
                peakBin = k
            }
        }
        assertEquals(k0, peakBin)
        assertEquals(1f, peakVal, 0.05f)
    }

    @Test
    fun `非 2 的幂长度抛异常`() {
        try {
            Fft.magnitudeSpectrum(FloatArray(1000))
            throw AssertionError("应拒绝非 2 的幂")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }
}
