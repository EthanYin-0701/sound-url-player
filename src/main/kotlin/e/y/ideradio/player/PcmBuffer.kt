package e.y.ideradio.player

/**
 * application 级 PCM 环形缓冲（设计 §4.4/§8.5）：解码 consumer **写入**，
 * 各可见面板 Swing Timer 以固定帧率**拉取最新快照**（latest-only，慢订阅者丢帧
 * 不阻塞播放线程）。PCM **不走事件总线**。
 *
 * 存储：最近 [MAX_FRAMES] 帧立体声（44.1 kHz 约 0.19s），环形覆盖。
 * 线程安全：写（解码线程）与读（EDT Timer）经同步保护。
 */
class PcmBuffer(private val maxFrames: Int = MAX_FRAMES) {

    private val left = FloatArray(maxFrames)
    private val right = FloatArray(maxFrames)

    /** 已写总帧数（单调递增，用于计算位置/判断是否为空）。 */
    @Volatile
    var totalFrames: Long = 0L
        private set

    /** 追加 [count] 帧（调用方保证长度足够）。环形覆盖最旧数据。 */
    @Synchronized
    fun append(leftFrames: FloatArray, rightFrames: FloatArray, count: Int) {
        if (count <= 0) return
        var written = 0
        var pos = (totalFrames % maxFrames).toInt()
        while (written < count) {
            val chunk = minOf(count - written, maxFrames - pos)
            System.arraycopy(leftFrames, written, left, pos, chunk)
            System.arraycopy(rightFrames, written, right, pos, chunk)
            written += chunk
            pos = (pos + chunk) % maxFrames
        }
        totalFrames += count
    }

    /**
     * 取最近 [n] 帧（按时间顺序），不足则取全部可用；无数据返回 null。
     * 返回 pair(left, right)。
     */
    @Synchronized
    fun latest(n: Int): Pair<FloatArray, FloatArray>? {
        val available = minOf(n, maxFrames, totalFrames.toInt())
        if (available <= 0) return null
        val start = ((totalFrames - available) % maxFrames).toInt()
        val outL = FloatArray(available)
        val outR = FloatArray(available)
        var copied = 0
        var idx = start
        while (copied < available) {
            val chunk = minOf(available - copied, maxFrames - idx)
            System.arraycopy(left, idx, outL, copied, chunk)
            System.arraycopy(right, idx, outR, copied, chunk)
            copied += chunk
            idx = (idx + chunk) % maxFrames
        }
        return outL to outR
    }

    /** 清空（停止/切源时）。 */
    @Synchronized
    fun clear() {
        left.fill(0f)
        right.fill(0f)
        totalFrames = 0L
    }

    companion object {
        /** 容量：覆盖波形（4096）与频谱（1024）快照需求，约 0.19s @44.1k。 */
        const val MAX_FRAMES = 8192
    }
}
