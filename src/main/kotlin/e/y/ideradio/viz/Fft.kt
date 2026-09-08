package e.y.ideradio.viz

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 自实现 radix-2 迭代 FFT（设计 §8.3/D4）：输入长度须为 2 的幂。
 * 就地计算 [real]/[imag]，输出为频谱（直流在 [0]，共轭对称）。
 */
object Fft {

    /** 计算幅度谱（长度 = n/2 + 1 的单边幅度），[windowed] 为已加窗的时域样本。 */
    fun magnitudeSpectrum(windowed: FloatArray): FloatArray {
        val n = windowed.size
        require(n >= 2 && (n and (n - 1)) == 0) { "FFT 长度必须为 2 的幂: $n" }
        val re = FloatArray(n)
        val im = FloatArray(n)
        System.arraycopy(windowed, 0, re, 0, n)
        fft(re, im)

        val out = FloatArray(n / 2 + 1)
        for (k in 0 until out.size) {
            val mag = kotlin.math.sqrt(re[k] * re[k] + im[k] * im[k].toDouble()).toFloat()
            // 单边谱：直流/奈奎斯特 bin 不乘 2，其余乘 2（幅度守恒）
            val norm = if (k == 0 || k == n / 2) 1f / n else 2f / n
            out[k] = mag * norm
        }
        return out
    }

    /** 就地 radix-2 Cooley-Tukey（位反转 + 蝶形）。 */
    fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size
        // 位反转排列
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        // 蝶形
        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wRe = cos(ang).toFloat()
            val wIm = sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var curRe = 1f
                var curIm = 0f
                for (k in 0 until len / 2) {
                    val uRe = re[i + k]
                    val uIm = im[i + k]
                    val vRe = re[i + k + len / 2] * curRe - im[i + k + len / 2] * curIm
                    val vIm = re[i + k + len / 2] * curIm + im[i + k + len / 2] * curRe
                    re[i + k] = uRe + vRe
                    im[i + k] = uIm + vIm
                    re[i + k + len / 2] = uRe - vRe
                    im[i + k + len / 2] = uIm - vIm
                    val nRe = curRe * wRe - curIm * wIm
                    curIm = curRe * wIm + curIm * wRe
                    curRe = nRe
                }
                i += len
            }
            len = len shl 1
        }
    }
}
