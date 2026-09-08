package e.y.ideradio.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class HeadersSanitizerTest {

    @Test
    fun `合法头原样保留`() {
        val out = HeadersSanitizer.sanitize(mapOf("Referer" to "https://www.bilibili.com/", "User-Agent" to "ok"))
        assertEquals(2, out.size)
        assertEquals("https://www.bilibili.com/", out["Referer"])
    }

    @Test
    fun `普通头含换行被丢弃`() {
        val out = HeadersSanitizer.sanitize(mapOf("X-Foo" to "a\r\nInjected: 1", "User-Agent" to "ok"))
        assertEquals(1, out.size) // 只保留 UA
        assertEquals("ok", out["User-Agent"])
    }

    @Test
    fun `关键头非法抛异常`() {
        assertThrows(IllegalArgumentException::class.java) {
            HeadersSanitizer.sanitize(mapOf("Cookie" to "a\r\nX: y"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            HeadersSanitizer.sanitize(mapOf("Referer" to "bad\u0000value"))
        }
    }

    @Test
    fun `拼为 ffmpeg headers 参数含 CRLF`() {
        val arg = HeadersSanitizer.toFfmpegHeaderArgument(mapOf("Referer" to "https://x/"))
        assertEquals("Referer: https://x/\r\n", arg)
    }
}
