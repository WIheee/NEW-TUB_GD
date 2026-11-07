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
 * 约定：ID 单调递增，应用只会在「远程最大 ID > 本机已读 ID」时弹窗。
 * 任何网络或解析异常都返回空列表，绝不打扰用户。
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
