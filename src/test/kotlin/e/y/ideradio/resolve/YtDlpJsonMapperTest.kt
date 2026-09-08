package e.y.ideradio.resolve

import e.y.ideradio.resolve.ytdlp.YtDlpJsonMapper
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class YtDlpJsonMapperTest {

    private val sample = """
        {
          "id": "abc123",
          "title": "示例曲目",
          "duration": 225,
          "formats": [
            {"format_id": "f1", "url": "https://a/1.m4a", "vcodec": "none", "acodec": "mp4a.40.2",
             "abr": 128, "http_headers": {"Referer": "https://www.bilibili.com/", "X-Empty": null, "X-Obj": {}}},
            {"format_id": "f2", "url": "https://a/2.mp4", "vcodec": "avc1", "acodec": "mp4a.40.2", "abr": 96},
            {"format_id": "f3", "url": "", "vcodec": "none", "acodec": "mp4a.40.2", "abr": 200},
            {"format_id": "f4", "url": "https://a/4.m4a", "vcodec": "none", "acodec": "mp4a.40.2",
             "abr": "160"}
          ]
        }
    """.trimIndent()

    @Test
    fun `解析并选纯音频最高码率`() {
        val info = YtDlpJsonMapper.parse(sample)
        assertEquals("示例曲目", info.title)
        assertEquals(225L, YtDlpJsonMapper.durationSeconds(info))
        val best = YtDlpJsonMapper.selectBestAudioFormat(info)!!
        // f4 abr=160（字符串数字归一）> f1 abr=128；f3 url 空被跳过；f2 含视频被排除
        assertEquals("f4", best.formatId)
    }

    @Test
    fun `请求头归一丢弃 null 与非对象值`() {
        val info = YtDlpJsonMapper.parse(sample)
        val best = YtDlpJsonMapper.selectBestAudioFormat(info)!!
        val headers = YtDlpJsonMapper.headersOf(best)
        assertEquals(emptyMap<String, String>(), headers) // f4 无 http_headers
        val f1 = info.formats.first { it.formatId == "f1" }
        val h1 = YtDlpJsonMapper.headersOf(f1)
        assertEquals(mapOf("Referer" to "https://www.bilibili.com/"), h1)
    }

    @Test
    fun `duration 缺失或非数值取 -1`() {
        val noDuration = """{"id":"x","title":"t","formats":[]}"""
        assertEquals(-1L, YtDlpJsonMapper.durationSeconds(YtDlpJsonMapper.parse(noDuration)))

        val obj = buildJsonObject { put("duration", JsonPrimitive("abc")) }
        // 构造完整对象直接验证 toDouble 归一
        assertNull(YtDlpJsonMapper.toDouble(obj["duration"]))
    }

    @Test
    fun `无纯音频返回 null`() {
        val onlyVideo = """{"id":"x","title":"t","formats":[
            {"format_id":"v","url":"https://a/v.mp4","vcodec":"avc1","acodec":"none"}]}"""
        assertNull(YtDlpJsonMapper.selectBestAudioFormat(YtDlpJsonMapper.parse(onlyVideo)))
    }

    @Test
    fun `acodec 为字符串 none 的空格式必须被排除`() {
        // 评审 #4：只有 acodec="none" 的"空格式"即使 url/码率再高也不可选
        val json = """{"id":"x","title":"t","formats":[
            {"format_id":"empty","url":"https://a/empty.m4a","vcodec":"none","acodec":"none","abr":999},
            {"format_id":"good","url":"https://a/good.m4a","vcodec":"none","acodec":"mp4a.40.2","abr":128}]}"""
        val best = YtDlpJsonMapper.selectBestAudioFormat(YtDlpJsonMapper.parse(json))
        assertNotNull(best)
        assertEquals("good", best!!.formatId)
    }
}
