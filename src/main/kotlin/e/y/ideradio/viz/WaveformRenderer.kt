package e.y.ideradio.viz

import java.awt.Color
import java.awt.Graphics2D

/**
 * 波形渲染（设计 §8.3）：取最近 N 样本，按画布宽度分桶 min/max 包络绘制连线
 * （实时滚动显示时域波形）。
 */
object WaveformRenderer {

    /**
     * @param left 左声道样本（时间升序，最新在后）
     */
    fun draw(g: Graphics2D, width: Int, height: Int, left: FloatArray, color: Color, base: Color) {
        g.color = base
        g.fillRect(0, 0, width, height)
        if (left.isEmpty()) return

        val midY = height / 2f
        val amp = (height - 6f) / 2f
        g.color = color

        // 分桶 min/max 包络
        val buckets = width.coerceAtLeast(64)
        var prevMin = Float.NaN
        var prevMax = Float.NaN
        for (b in 0 until buckets) {
            val startIdx = b.toLong() * left.size / buckets
            val endIdx = ((b + 1).toLong() * left.size / buckets).toInt().coerceAtLeast(startIdx.toInt() + 1)
            var mn = Float.MAX_VALUE
            var mx = Float.MIN_VALUE
            for (i in startIdx.toInt() until endIdx) {
                val s = left[i]
                if (s < mn) mn = s
                if (s > mx) mx = s
            }
            if (mn == Float.MAX_VALUE) { mn = 0f; mx = 0f }
            val x = b * width / buckets.toFloat()
            val yMin = midY - mn.coerceIn(-1f, 1f) * amp
            val yMax = midY - mx.coerceIn(-1f, 1f) * amp
            if (!prevMin.isNaN()) {
                g.drawLine(x.toInt(), yMin.toInt(), (x - width / buckets.toFloat()).toInt(), prevMin.toInt())
                g.drawLine(x.toInt(), yMax.toInt(), (x - width / buckets.toFloat()).toInt(), prevMax.toInt())
            }
            prevMin = yMin
            prevMax = yMax
        }
        // 中线
        g.color = Color(color.red, color.green, color.blue, 40)
        g.drawLine(0, midY.toInt(), width, midY.toInt())
    }
}
