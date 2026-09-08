package e.y.ideradio.resolve.external

import java.io.File

/**
 * 外部二进制定位（设计 §6.1/D17，v0.8）：
 * - 返回**结构化三态**：Found / NotConfigured / InvalidExplicitPath；
 * - **显式路径已配置但无效 → 不回退**（否则"选错 ffmpeg 却静默用系统里另一个版本"难排错）；
 * - yt-dlp / ffmpeg：显式 → PATH → **可信常见位置**
 *   （缓解从 Dock/Finder 启动的 IDE 拿不到 shell PATH）。
 *
 * [lookupYtDlp]/[lookupFfmpeg] 供 UI 与调用方使用；旧 [locate] 保留为"非三态"便捷入口。
 */
sealed class ToolLookup {
    data class Found(val path: String, val source: Source) : ToolLookup()
    object NotConfigured : ToolLookup()
    data class InvalidExplicitPath(val path: String) : ToolLookup()

    enum class Source { CUSTOM, PATH, COMMON }
}

object ExternalToolLocator {

    /** yt-dlp：显式 → PATH →可信常见位置。 */
    fun lookupYtDlp(customPath: String?): ToolLookup {
        val p = customPath?.trim().orEmpty()
        if (p.isNotEmpty()) {
            explicitCustom(p)?.let { return it } // Found(CUSTOM) 或 InvalidExplicitPath（不回退）
            return ToolLookup.InvalidExplicitPath(p)
        }
        findOnPath("yt-dlp")?.let { return ToolLookup.Found(it, ToolLookup.Source.PATH) }
        for (candidate in commonLocations("yt-dlp")) {
            if (candidate.isFile && candidate.canExecute()) {
                return ToolLookup.Found(candidate.path, ToolLookup.Source.COMMON)
            }
        }
        return ToolLookup.NotConfigured
    }

    /** ffmpeg：显式 → PATH → 可信常见位置。 */
    fun lookupFfmpeg(customPath: String?): ToolLookup {
        val p = customPath?.trim().orEmpty()
        if (p.isNotEmpty()) {
            explicitCustom(p)?.let { return it }
            return ToolLookup.InvalidExplicitPath(p)
        }
        findOnPath("ffmpeg")?.let { return ToolLookup.Found(it, ToolLookup.Source.PATH) }
        for (candidate in commonLocations("ffmpeg")) {
            if (candidate.isFile && candidate.canExecute()) {
                return ToolLookup.Found(candidate.path, ToolLookup.Source.COMMON)
            }
        }
        return ToolLookup.NotConfigured
    }

    /** 显式路径有效 → Found(CUSTOM)；路径为空 → null（未配置）；非空但无效 → Invalid。 */
    private fun explicitCustom(customPath: String?): ToolLookup? {
        val p = customPath?.trim().orEmpty()
        if (p.isEmpty()) return null
        val f = File(p)
        if (f.isFile && f.canExecute()) return ToolLookup.Found(f.path, ToolLookup.Source.CUSTOM)
        // 允许纯命令名（按 PATH 解析一次）
        findOnPath(p)?.let { return ToolLookup.Found(it, ToolLookup.Source.CUSTOM) }
        return ToolLookup.InvalidExplicitPath(p)
    }

    /** 旧便捷入口：仅 自定义路径 → PATH（无缓存/常见位置），供历史调用。 */
    fun locate(toolName: String, customPath: String?): String? {
        explicitCustom(customPath)?.let {
            return when (it) {
                is ToolLookup.Found -> it.path
                else -> null
            }
        }
        return findOnPath(toolName)
    }

    private fun findOnPath(name: String): String? {
        val pathEnv = System.getenv("PATH") ?: return null
        val lower = name.lowercase()
        val candidates = sequence {
            for (dir in pathEnv.split(File.pathSeparatorChar)) {
                yield(File(dir, name))
                if (!lower.endsWith(".exe")) yield(File(dir, "$name.exe"))
            }
        }
        return candidates.firstOrNull { it.isFile && it.canExecute() }?.path
    }

    /** 常见可信安装位置（不含 PATH 时的兜底探测）。 */
    private fun commonLocations(toolName: String): List<File> {
        val isMac = System.getProperty("os.name", "").lowercase().contains("mac")
        val isWin = System.getProperty("os.name", "").lowercase().contains("win")
        val list = mutableListOf<File>()
        if (isMac) {
            list += File("/opt/homebrew/bin/$toolName")
            list += File("/usr/local/bin/$toolName")
            list += File("/usr/bin/$toolName")
        } else if (isWin) {
            // Windows：先尝试 where.exe 输出
            runCatching {
                val p = ProcessBuilder("where.exe", toolName).redirectErrorStream(true).start()
                val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
                p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
                for (line in out.lineSequence()) {
                    val f = File(line.trim())
                    if (f.isFile && f.canExecute()) list += f
                }
            }
            // 常见包管理 shim
            list += File(System.getenv("LOCALAPPDATA") ?: "", "Microsoft\\WinGet\\Links\\$toolName.exe")
        } else {
            list += File("/usr/bin/$toolName")
            list += File("/usr/local/bin/$toolName")
        }
        return list
    }
}
