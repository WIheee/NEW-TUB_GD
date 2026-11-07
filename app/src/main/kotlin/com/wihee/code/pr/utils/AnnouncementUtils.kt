package com.wihee.code.pr.utils

import java.net.HttpURLConnection
import java.net.URL

/**
 * 远程公告：从 GitHub 仓库根目录的 Announcement.md 拉取并解析。
 *
 * 文件格式（由仓库维护者手工维护）：
 *
 *     ## [12] 标题
 *     - date: 2026-11-07
 *     - action: update      // update | info，缺省 info
 *     - version: B3         // 可选，展示给用户的目标版本
 *     - url: https://...    // 可选，缺省跳到 releases 页
 *
 *     正文（按纯文本渲染）
 *
 * 约定：信息类公告按 ID 只提示一次；版本更新公告（action: update）只要目标版本
 * 高于本机版本，每次启动都会提示，装机后自动消失。任何网络或解析异常都返回空列表。
 */
object AnnouncementUtils {

    const val REPO_URL = "https://github.com/WIheee/Code-pr"
    const val RELEASES_URL = "https://github.com/WIheee/Code-pr/releases"
    const val RAW_URL = "https://raw.githubusercontent.com/WIheee/Code-pr/main/Announcement.md"

    private const val CONNECT_TIMEOUT_MS = 6000
    private const val READ_TIMEOUT_MS = 6000

    data class Announcement(
        val id: Int,
        val title: String,
        val date: String = "",
        val version: String = "",
        val isUpdate: Boolean = false,
        val url: String = "",
        val body: String = ""
    )

    private val HEADER = Regex("""(?m)^##\s*\[\s*(\d+)\s*]\s*(.*)$""")
    private val META = Regex(
        """^\s*[-*]?\s*(date|action|url|version)\s*[:：]\s*(.*)$""",
        RegexOption.IGNORE_CASE
    )

    /** 拉取并解析公告；失败返回空列表。会在调用线程阻塞，务必在 IO 线程调用。 */
    fun fetch(): List<Announcement> {
        return try {
            val markdown = download(RAW_URL) ?: return emptyList()
            parse(markdown)
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun download(url: String): String? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("Accept", "text/plain")
            setRequestProperty("User-Agent", "CodePR-Android")
        }
        return try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                null
            } else {
                connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            }
        } finally {
            connection.disconnect()
        }
    }

    /** 解析 Announcement.md，按 ID 从大到小排序。 */
    fun parse(markdown: String): List<Announcement> {
        if (markdown.isBlank()) return emptyList()
        val text = markdown.replace("\r\n", "\n").replace('\r', '\n')
        val headers = HEADER.findAll(text).toList()
        if (headers.isEmpty()) return emptyList()

        return headers.mapIndexedNotNull { index, match ->
            val id = match.groupValues[1].toIntOrNull() ?: return@mapIndexedNotNull null
            val title = match.groupValues[2].trim()
            val blockStart = match.range.last + 1
            val blockEnd = headers.getOrNull(index + 1)?.range?.first ?: text.length
            val block = if (blockStart >= blockEnd) "" else text.substring(blockStart, blockEnd)
            val (meta, body) = splitMeta(block)

            val action = meta["action"].orEmpty().lowercase()
            Announcement(
                id = id,
                title = title.ifBlank { "Announcement #$id" },
                date = meta["date"].orEmpty(),
                version = meta["version"].orEmpty(),
                isUpdate = action == "update" || action == "upgrade",
                url = meta["url"].orEmpty(),
                body = body.trim()
            )
        }.sortedByDescending { it.id }
    }

    /**
     * 判断 [remote] 是否比 [current] 新。
     * 支持 `B3` / `B12` 这类 beta 版本与 `1.9.0` 这类正式版；beta 永远早于正式版。
     * 任一方无法解析时保守返回 false，避免误报更新。
     */
    fun isVersionNewer(remote: String, current: String): Boolean {
        if (remote.isBlank() || current.isBlank()) return false
        val left = parseVersion(remote)
        val right = parseVersion(current)
        if (left.isEmpty() || right.isEmpty()) return false
        for (i in 0 until maxOf(left.size, right.size)) {
            val a = left.getOrElse(i) { 0 }
            val b = right.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    private fun parseVersion(raw: String): List<Int> {
        val value = raw.trim().removePrefix("v").removePrefix("V")
        // beta：B3 / B12 / B3.0 → 前缀 0，保证 B999 < 1.0.0
        val beta = Regex("""^[Bb](\d+)(?:\.\d+)*$""").matchEntire(value)
        if (beta != null) {
            return listOf(0, beta.groupValues[1].toIntOrNull() ?: return emptyList())
        }
        // 正式版：取所有数字段，前缀 1
        val numbers = Regex("""\d+""").findAll(value).mapNotNull { it.value.toIntOrNull() }.toList()
        return if (numbers.isEmpty()) emptyList() else listOf(1) + numbers
    }

    /**
     * 把单条公告的块拆成「元数据 + 正文」。
     * 元数据只允许出现在正文之前，且必须是 date/action/url/version 四个键之一，
     * 因此正文里出现 "- 修复了 xxx" 不会被误吞。
     */
    private fun splitMeta(block: String): Pair<Map<String, String>, String> {
        val meta = LinkedHashMap<String, String>()
        val lines = block.split('\n')
        var bodyStart = lines.size
        for ((index, line) in lines.withIndex()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            val match = META.matchEntire(trimmed)
            if (match == null) {
                bodyStart = index
                break
            }
            meta[match.groupValues[1].lowercase()] = match.groupValues[2].trim()
        }
        val body = lines.drop(bodyStart).joinToString("\n").trim()
        return meta to body
    }
}
