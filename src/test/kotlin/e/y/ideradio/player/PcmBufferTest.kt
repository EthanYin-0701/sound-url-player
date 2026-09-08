package e.y.ideradio.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PcmBufferTest {

    @Test
    fun `空缓冲返回 null`() {
        assertNull(PcmBuffer(8).latest(4))
    }

    @Test
    fun `不满容量返回全部且保序`() {
        val buf = PcmBuffer(8)
        val l = floatArrayOf(0.1f, 0.2f, 0.3f)
        val r = floatArrayOf(1.1f, 1.2f, 1.3f)
        buf.append(l, r, 3)
        val (ol, or) = buf.latest(8)!!
        assertEquals(3, ol.size)
        assertEquals(0.1f, ol[0], 1e-6f)
        assertEquals(0.3f, ol[2], 1e-6f)
        assertEquals(1.2f, or[1], 1e-6f)
    }

    @Test
    fun `超过容量保留最近数据`() {
        val buf = PcmBuffer(4)
        buf.append(floatArrayOf(1f, 2f, 3f, 4f), FloatArray(4), 4)
        buf.append(floatArrayOf(5f, 6f), FloatArray(2), 2) // 覆盖 1,2 → 剩 3,4,5,6
        val (ol, _) = buf.latest(4)!!
        assertEquals(4, ol.size)
        assertEquals(3f, ol[0], 1e-6f)
        assertEquals(6f, ol[3], 1e-6f)
    }

    @Test
    fun `跨环形边界回绕读取保序`() {
        val buf = PcmBuffer(4)
        buf.append(floatArrayOf(1f, 2f, 3f), FloatArray(3), 3) // pos=3
        buf.append(floatArrayOf(4f, 5f, 6f), FloatArray(3), 3) // 覆盖 1,2 → 3,4,5,6 环上 3,0,1,2
        val (ol, _) = buf.latest(4)!!
        assertEquals(floatArrayOf(3f, 4f, 5f, 6f)[2], ol[2], 1e-6f)
        assertEquals(3f, ol[0], 1e-6f)
        assertEquals(6f, ol[3], 1e-6f)
    }
}
