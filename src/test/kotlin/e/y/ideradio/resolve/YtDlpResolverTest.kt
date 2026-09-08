package e.y.ideradio.resolve

import e.y.ideradio.resolve.model.MediaPlatform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YtDlpResolverTest {

    private fun resolver(
        cookies: Pair<String, String> = "" to "",
    ) = YtDlpResolver(MediaPlatform.BILIBILI, { "" }, { cookies })

    @Test
    fun `412 归类为风控限流并可重试`() {
        val e = resolver().classifyFailure(
            "ERROR: [BiliBili] 18MWLziEBs: Unable to download webpage: HTTP Error 412: Precondition Failed"
        )
        assertEquals(ResolveErrorKind.RATE_LIMITED, e.kind)
        assertTrue("412 应可自动重试", e.kind == ResolveErrorKind.RATE_LIMITED)
        // 文案应为可读中文而非原始 stderr
        assertTrue(e.message!!.contains("风控/限流"))
        assertFalse(e.message!!.contains("HTTP Error 412"))
    }

    @Test
    fun `SSL EOF 归类为网络错误且 message 不裸抛 stderr`() {
        val e = resolver().classifyFailure("ERROR: unable to download webpage: SSL: UNEXPECTED_EOF_WHILE_READING")
        assertEquals(ResolveErrorKind.GENERIC, e.kind)
        assertFalse(e.message!!.startsWith("ERROR:"))
    }

    @Test
    fun `登录类归为 LOGIN_REQUIRED`() {
        val e = resolver().classifyFailure("ERROR: please sign in and try again")
        assertEquals(ResolveErrorKind.LOGIN_REQUIRED, e.kind)
    }

    @Test
    fun `参数不再包含已废弃 no-call-home`() {
        val args = resolver().buildArgs("https://www.bilibili.com/video/BVxxxxxxxxxx")
        assertFalse(args.contains("--no-call-home"))
    }

    @Test
    fun `配置 cookies from browser 时拼入参数`() {
        val args = resolver("chrome" to "").buildArgs("https://x")
        assertTrue(args.contains("--cookies-from-browser"))
        assertTrue(args.contains("chrome"))
        assertFalse(args.contains("--cookies"))
    }

    @Test
    fun `配置 cookies 文件时拼入参数且浏览器为空优先用文件`() {
        val args = resolver("" to "/tmp/cookies.txt").buildArgs("https://x")
        assertTrue(args.contains("--cookies"))
        assertTrue(args.contains("/tmp/cookies.txt"))
    }

    @Test
    fun `extractId 匹配 bilibili bv`() {
        assertEquals("BV18MWLziEBs", resolver().extractId("https://www.bilibili.com/video/BV18MWLziEBs?p=2"))
        assertEquals(null, resolver().extractId("https://www.youtube.com/watch?v=abc123"))
    }
}
