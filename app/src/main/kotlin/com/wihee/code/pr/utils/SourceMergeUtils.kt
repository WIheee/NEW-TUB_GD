package com.wihee.code.pr.utils

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import com.wihee.code.pr.SelectedFile
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.Writer
import java.util.ArrayDeque
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.text.SimpleDateFormat
import java.util.*

object SourceMergeUtils {
    val DEFAULT_HARD_DIRS = setOf(
        // VCS / IDE / 构建产物
        ".git", ".svn", ".hg", ".idea", ".vscode", ".vs", ".gradle", ".kotlin", ".cxx",
        "build", "dist", "out", "target", "obj", "coverage", "tmp", "temp", "logs",
        // 包管理 / 依赖目录
        "node_modules", "bower_components", "jspm_packages", "vendor", "Pods", "DerivedData",
        ".venv", "venv", "__pycache__", ".pytest_cache", ".mypy_cache", ".ruff_cache", ".tox", ".eggs",
        // 前端 / 框架缓存
        ".next", ".nuxt", ".svelte-kit", ".turbo", ".parcel-cache", ".yarn", ".cache", ".sass-cache",
        // 移动端 / 原生 / 游戏引擎
        "CMakeFiles", "Library", "Temp", "Logs", ".dart_tool", ".pub-cache",
        // 云 / 基础设施
        ".terraform", ".serverless", ".aws-sam",
        // Godot
        ".godot", ".import"
    )
    val DEFAULT_SOFT_EXTS = setOf(
        // 图片
        ".png", ".jpg", ".jpeg", ".gif", ".webp", ".svg", ".ico", ".psd", ".tga", ".exr", ".hdr",
        ".dds", ".ktx", ".ktx2", ".basis", ".bmp", ".tiff", ".tif", ".heic", ".avif",
        // 字体
        ".ttf", ".otf", ".woff", ".woff2", ".eot",
        // 3D / 模型
        ".gltf", ".glb", ".obj", ".fbx", ".dae", ".blend", ".stl", ".ply", ".usd", ".usdz",
        // 音频 / 视频
        ".mp3", ".wav", ".ogg", ".flac", ".m4a", ".aac", ".opus", ".wem",
        ".mp4", ".webm", ".mov", ".avi", ".mkv", ".ogv",
        // 压缩包
        ".zip", ".tar", ".gz", ".tgz", ".xz", ".bz2", ".rar", ".7z",
        // 二进制 / 可执行 / 库
        ".apk", ".aab", ".ipa", ".exe", ".dll", ".so", ".dylib", ".a", ".lib", ".o", ".class",
        ".jar", ".war", ".dex", ".wasm", ".pdb", ".ilk", ".exp",
        // 文档 / 数据库
        ".pdf", ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx",
        ".sqlite", ".db", ".mdb", ".ldb",
        // 噪声文本
        ".map", ".log"
    )
    
    val TEXT_EXTS = setOf(
        "kt", "java", "py", "gd", "c", "cpp", "h", "cs", "js", "ts", "html", "css", 
        "xml", "json", "yaml", "yml", "toml", "gradle", "properties", "md", "txt", 
        "tscn", "tres", "godot", "cfg", "sh", "bat", "gdshader", "glsl", "frag", "vert",
        "svg", "import", "uid"
    )

    data class FileNode(
        val name: String, val isDir: Boolean, val uri: Uri, val size: Long, 
        val documentFile: DocumentFile? = null, val children: MutableList<FileNode> = mutableListOf(),
        val file: File? = null, val sortKey: String = name.lowercase()
    )

    data class ScanResult(
        val content: String,
        val godotVersion: String,
        val charCount: Int = content.length,
        val lineCount: Int = 0
    )

    fun formatSize(size: Long): String {
        return if (size < 1024) "$size B"
        else if (size < 1024 * 1024) String.format(Locale.US, "%.1f KB", size / 1024.0)
        else String.format(Locale.US, "%.1f MB", size / (1024.0 * 1024.0))
    }

    fun scanDirectory(
        context: Context, treeUri: Uri, outputFormat: String, exportMode: String, promptMode: String, 
        softExcludes: Set<String>, hardExcludes: Set<String>, maxFileSizeKb: Int, showGlobalFileSize: Boolean,
        customPrompts: Map<String, String> = emptyMap()
    ): ScanResult {
        val (rootNode, version) = buildRootNode(context, treeUri, hardExcludes)
            ?: return ScanResult("Unable to read this folder", "Unknown")
        val nodes = if (exportMode == "FULL_EXPORT") {
            collectTextNodes(context, rootNode, softExcludes, maxFileSizeKb)
        } else {
            emptyList()
        }
        val textSource = if (nodes.isEmpty()) {
            null
        } else {
            TextPipeline(nodes, loader = { node -> readText(context, node) })
        }
        val estimated = if (nodes.isEmpty()) {
            4096
        } else {
            nodes.sumOf { it.size }.coerceIn(0L, 4L * 1024 * 1024).toInt() + (1 shl 16)
        }
        val sink = StringBuilderSink(estimated) { node -> readText(context, node, textSource) }
        try {
            buildOutput(sink, context, rootNode.name, rootNode, outputFormat, exportMode, promptMode, softExcludes, maxFileSizeKb, showGlobalFileSize, version, textSource, customPrompts)
        } finally {
            textSource?.close()
        }
        return ScanResult(sink.text(), version)
    }

    /**
     * 流式导出：边生成边写入 outFile，内存里只保留 previewLimit 个字符的预览。
     */
    fun scanDirectoryToFile(
        context: Context, treeUri: Uri, outputFormat: String, exportMode: String, promptMode: String,
        softExcludes: Set<String>, hardExcludes: Set<String>, maxFileSizeKb: Int, showGlobalFileSize: Boolean,
        outFile: File, previewLimit: Int = 200_000, zeroCopy: Boolean = true,
        customPrompts: Map<String, String> = emptyMap()
    ): ScanResult {
        val (rootNode, version) = buildRootNode(context, treeUri, hardExcludes)
            ?: return ScanResult("Unable to read this folder", "Unknown")
        outFile.parentFile?.mkdirs()

        // 先写临时文件，成功后改名，避免中途失败留下半个文件
        val tempFile = File(outFile.parentFile ?: File("."), outFile.name + ".part")

        // 关闭零拷贝时，仍然可以把读取并行化
        val textSource = if (!zeroCopy && exportMode == "FULL_EXPORT") {
            val nodes = collectTextNodes(context, rootNode, softExcludes, maxFileSizeKb)
            if (nodes.isEmpty()) null else TextPipeline(nodes, loader = { node -> readText(context, node) })
        } else {
            null
        }

        val output = FileOutputStream(tempFile)
        val writer = output.writer(Charsets.UTF_8).buffered(128 * 1024)
        val sink = StreamingSink(writer, output, previewLimit, zeroCopy) { node -> readText(context, node, textSource) }
        try {
            buildOutput(sink, context, rootNode.name, rootNode, outputFormat, exportMode, promptMode, softExcludes, maxFileSizeKb, showGlobalFileSize, version, textSource, customPrompts)
        } finally {
            writer.close()
            textSource?.close()
        }
        if (!tempFile.renameTo(outFile)) {
            tempFile.copyTo(outFile, overwrite = true)
            tempFile.delete()
        }
        val lines = if (sink.chars == 0L) 0 else sink.newlines + 1
        return ScanResult(sink.previewText(), version, sink.chars.toInt(), lines)
    }

    private fun buildRootNode(context: Context, treeUri: Uri, hardExcludes: Set<String>): Pair<FileNode, String>? {
        // 优先 C++ 原生遍历
        resolveTreeUriToFile(treeUri)?.let { realRoot ->
            buildNativeRoot(realRoot, hardExcludes)?.let { return it }
        }
        return buildSafRoot(context, treeUri, hardExcludes)
    }

    private fun buildSafRoot(context: Context, treeUri: Uri, hardExcludes: Set<String>): Pair<FileNode, String>? {
        val rootDoc = DocumentFile.fromTreeUri(context, treeUri) ?: return null

        var detectedVersion = "Unknown"
        val projectFile = rootDoc.findFile("project.godot")
        if (projectFile != null && projectFile.isFile) {
            try {
                val text = context.contentResolver.openInputStream(projectFile.uri)?.bufferedReader()?.use { it.readText() } ?: ""
                detectedVersion = detectVersionFromText(text)
            } catch (_: Exception) {
            }
        }

        val rootNode = FileNode(rootDoc.name ?: "Project", true, rootDoc.uri, 0, rootDoc)

        fun parseDir(doc: DocumentFile, node: FileNode) {
            doc.listFiles().forEach { child ->
                if (child.isDirectory) {
                    val dirName = child.name ?: ""
                    val isHardDir = hardExcludes.contains(dirName) || hardExcludes.contains("$dirName/")
                    if (!isHardDir) {
                        val childNode = FileNode(dirName, true, child.uri, 0, child)
                        node.children.add(childNode)
                        parseDir(child, childNode)
                    }
                } else {
                    val fileName = child.name ?: ""
                    val ext = fileName.substringAfterLast(".").lowercase()
                    val isHardFile = hardExcludes.contains(".$ext") || hardExcludes.contains(ext)
                    if (!isHardFile) {
                        node.children.add(FileNode(fileName, false, child.uri, child.length(), child))
                    }
                }
            }
        }
        parseDir(rootDoc, rootNode)
        return rootNode to detectedVersion
    }

    fun processMultipleFiles(
        context: Context, files: List<SelectedFile>, outputFormat: String, 
        softExcludes: Set<String>, hardExcludes: Set<String>, maxFileSizeKb: Int, showGlobalFileSize: Boolean
    ): ScanResult {
        val sb = StringBuilder()
        val date = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
        val detectedVersion = ""

        if (outputFormat == "XML") {
            sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<project name=\"MultiFiles\" generated=\"$date\">\n")
            sb.append("  <files>\n")
            files.forEach { file ->
                val uri = file.uri
                val name = file.name
                val pathStr = getDisplayNameAndPath(context, uri).second
                var size = 0L
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (sizeIdx != -1) size = cursor.getLong(sizeIdx)
                    }
                }
                
                val ext = name.substringAfterLast(".").lowercase()
                val isHardFiltered = hardExcludes.contains(".$ext") || hardExcludes.contains(ext)
                val isSoftFiltered = softExcludes.contains(".$ext") || softExcludes.contains(ext)
                
                if (!isHardFiltered) {
                    val sizeAttr = if (showGlobalFileSize) " size=\"${formatSize(size)}\"" else ""
                    val lang = getLang(ext)
                    val langAttr = if (lang.isNotEmpty()) " language=\"$lang\"" else ""
                    sb.append("    <file path=\"$pathStr\"$langAttr$sizeAttr>\n")
                    
                    if (isSoftFiltered) {
                        sb.append("      <![CDATA[\n[Content omitted: soft-exclusion rule]\n]]>\n")
                    } else if (isTextFile(context, name, size, uri)) {
                        val rawText = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
                        val text = rawText.replace("]]>", "]]]]><![CDATA[>")
                        sb.append("      <![CDATA[\n$text\n]]>\n")
                    } else {
                        sb.append("      <![CDATA[\n[Binary resource omitted]\n]]>\n")
                    }
                    sb.append("    </file>\n")
                }
            }
            sb.append("  </files>\n</project>")
            return ScanResult(sb.toString(), detectedVersion)
        }

        val isMd = (outputFormat == "MARKDOWN")
        if (isMd) sb.append("# Multi-file Merge\n\n> Files: ${files.size} | Generated: $date\n\n")
        else sb.append("Multi-file Merge\nFiles: ${files.size} | Generated: $date\n\n")
        
        files.forEachIndexed { index, file ->
            val uri = file.uri
            val name = file.name
            val pathStr = getDisplayNameAndPath(context, uri).second
            var size = 0L
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIdx != -1) size = cursor.getLong(sizeIdx)
                }
            }
            
            val ext = name.substringAfterLast(".").lowercase()
            val isHardFiltered = hardExcludes.contains(".$ext") || hardExcludes.contains(ext)
            val isSoftFiltered = softExcludes.contains(".$ext") || softExcludes.contains(ext)
            
            if (!isHardFiltered) {
                val sizeStr = if (showGlobalFileSize) " (${formatSize(size)})" else ""
                if (isMd) sb.append("\n---\n### [${index + 1}/${files.size}] $pathStr$sizeStr\n\n") 
                else sb.append("\n==================================================\n[${index + 1}/${files.size}] $pathStr$sizeStr\n==================================================\n\n")
                
                if (isSoftFiltered) {
                    sb.append("> **[Content omitted]** Excluded by a soft-exclusion rule.\n\n")
                } else if (isTextFile(context, name, size, uri)) {
                    val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
                    if (isMd) sb.append("```${getLang(ext)}\n").append(text).append("\n```\n\n")
                    else sb.append(text).append("\n\n")
                } else {
                    sb.append("> **[Content omitted]** Binary resource (size: ${formatSize(size)}).\n\n")
                }
            }
        }
        return ScanResult(sb.toString(), detectedVersion)
    }

    private fun getDisplayNameAndPath(context: Context, uri: Uri): Pair<String, String> {
        var name = "unknown_file"
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIdx != -1) name = cursor.getString(nameIdx)
            }
        }
        
        var path = uri.path ?: return Pair(name, name)
        
        if (path.startsWith("/document/primary:")) {
            path = path.replace("/document/primary:", "/storage/emulated/0/")
        } else if (path.startsWith("/document/")) {
            path = path.substringAfter("/document/")
        } else if (path.startsWith("/tree/")) {
            path = path.substringAfter("/tree/").substringBefore("/document/")
        } else if (path.startsWith("document:")) {
             return Pair(name, name)
        }
        
        val decoded = try { java.net.URLDecoder.decode(path, "UTF-8") } catch (e: Exception) { path }
        if (decoded.startsWith("document:") || decoded.contains("%2F") || decoded.contains("primary:")) {
             return Pair(name, name)
        }
        return Pair(name, decoded)
    }

    private fun buildOutput(
        sb: ContentSink,
        context: Context, projectName: String, rootNode: FileNode, 
        outputFormat: String, exportMode: String, promptMode: String, softExcludes: Set<String>, maxFileSizeKb: Int, showGlobalFileSize: Boolean,
        godotVersion: String, textSource: TextSource? = null, customPrompts: Map<String, String> = emptyMap()
    ) {
        val date = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
        
        if (exportMode == "PROJECT_CONFIG") {
            if (outputFormat == "XML") {
                sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<project name=\"$projectName\" godot_version=\"$godotVersion\" generated=\"$date\">\n")
                sb.append("  <files>\n")
                fun extractConfigs(node: FileNode, path: String) {
                    if (!node.isDir) {
                        val ext = node.name.substringAfterLast(".").lowercase()
                        if (ext == "godot" || ext == "cfg") {
                            val relPath = if (path.isEmpty()) node.name else "$path/${node.name}"
                            val sizeAttr = if (showGlobalFileSize) " size=\"${formatSize(node.size)}\"" else ""
                            val lang = getLang(ext)
                            val langAttr = if (lang.isNotEmpty()) " language=\"$lang\"" else ""
                            try {
                                val rawText = readText(context, node, textSource)
                                val text = rawText.replace("]]>", "]]]]><![CDATA[>")
                                sb.append("    <file path=\"$relPath\"$langAttr$sizeAttr>\n      <![CDATA[\n$text\n]]>\n    </file>\n")
                            } catch (e: Exception) {}
                        }
                    } else {
                        val newPath = if (path.isEmpty()) node.name else "$path/${node.name}"
                        node.children.sortedBy { it.sortKey }.forEach { extractConfigs(it, newPath) }
                    }
                }
                rootNode.children.sortedBy { it.sortKey }.forEach { extractConfigs(it, "") }
                sb.append("  </files>\n</project>")
            } else {
                sb.append("# Project Configuration (core settings)\n\n> Generated: $date\n\n")
                fun extractConfigs(node: FileNode, path: String) {
                    if (!node.isDir) {
                        val ext = node.name.substringAfterLast(".").lowercase()
                        if (ext == "godot" || ext == "cfg") {
                            val relPath = if (path.isEmpty()) node.name else "$path/${node.name}"
                            val sizeStr = if (showGlobalFileSize) " (${formatSize(node.size)})" else ""
                            try {
                                val text = readText(context, node, textSource)
                                sb.append("### $relPath$sizeStr\n\n```ini\n$text\n```\n\n")
                            } catch (e: Exception) {}
                        }
                    } else {
                        val newPath = if (path.isEmpty()) node.name else "$path/${node.name}"
                        node.children.sortedBy { it.sortKey }.forEach { extractConfigs(it, newPath) }
                    }
                }
                rootNode.children.sortedBy { it.sortKey }.forEach { extractConfigs(it, "") }
            }
            return
        }

        if (outputFormat == "XML") {
            sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<project name=\"$projectName\" godot_version=\"$godotVersion\" generated=\"$date\">\n")
            
            // 提示词放最前面：AI 先读到任务约束，再读代码
            if (exportMode == "FULL_EXPORT" && promptMode != "NONE") {
                val prompt = buildPrompt(promptMode, godotVersion, customPrompts)
                if (prompt.isNotBlank()) {
                    sb.append("  <prompt>\n    <![CDATA[\n")
                    sb.append(prompt.replace("]]>", "]]]]><![CDATA[>"))
                    sb.append("\n]]>\n  </prompt>\n")
                }
            }

            sb.append("  <structure>\n    <![CDATA[\n")
            buildTree(rootNode, showGlobalFileSize = showGlobalFileSize).lineSequence().forEach { line ->
                sb.append("    ").append(line).append('\n')
            }
            sb.append("\n    ]]>\n  </structure>\n")
            
            if (exportMode == "FULL_EXPORT") {
                sb.append("  <files>\n")
                fun extractXml(node: FileNode, path: String) {
                    if (!node.isDir) {
                        val relPath = if (path.isEmpty()) node.name else "$path/${node.name}"
                        val ext = node.name.substringAfterLast(".").lowercase()
                        val isSoftFiltered = softExcludes.contains(".$ext") || softExcludes.contains(ext)
                        
                        val sizeAttr = if (showGlobalFileSize) " size=\"${formatSize(node.size)}\"" else ""
                        val lang = getLang(ext)
                        val langAttr = if (lang.isNotEmpty()) " language=\"$lang\"" else ""
                        sb.append("    <file path=\"$relPath\"$langAttr$sizeAttr>\n")
                        if (isSoftFiltered) {
                            sb.append("      <![CDATA[\n[Content omitted: soft-exclusion rule]\n]]>\n")
                        } else if (node.size > maxFileSizeKb * 1024L) {
                            sb.append("      <![CDATA[\n[Content omitted: file exceeds size limit]\n]]>\n")
                        } else if (isTextFile(context, node)) {
                            try {
                                val rawText = readText(context, node, textSource)
                                val text = rawText.replace("]]>", "]]]]><![CDATA[>")
                                sb.append("      <![CDATA[\n$text\n]]>\n")
                            } catch (e: Exception) {
                                sb.append("      <![CDATA[\nUnable to read\n]]>\n")
                            }
                        } else {
                            sb.append("      <![CDATA[\n[Binary resource omitted]\n]]>\n")
                        }
                        sb.append("    </file>\n")
                    } else {
                        val newPath = if (path.isEmpty()) node.name else "$path/${node.name}"
                        node.children.sortedBy { it.sortKey }.forEach { extractXml(it, newPath) }
                    }
                }
                rootNode.children.sortedBy { it.sortKey }.forEach { extractXml(it, "") }
                sb.append("  </files>\n")
            }
            
            sb.append("</project>")
            return
        }

        val isMd = (outputFormat == "MARKDOWN")
        if (isMd) {
            sb.append("# Project: $projectName\n\n")
            sb.append("> Generated: $date\n\n")
            if (exportMode == "DIR_ONLY") sb.append("*This document contains the project directory structure only.*\n\n") else sb.append("*This document contains the directory structure and file contents.*\n\n")
        } else {
            sb.append("Project: $projectName\n")
            sb.append("Generated: $date\n\n")
        }

        // 提示词放最前面：AI 先读到任务约束，再读目录与源码
        if (exportMode == "FULL_EXPORT" && promptMode != "NONE") {
            val prompt = buildPrompt(promptMode, godotVersion, customPrompts)
            if (prompt.isNotBlank()) {
                if (isMd) {
                    sb.append(prompt)
                    sb.append("---\n\n")
                } else {
                    sb.append(prompt.replace("##", "").replace("**", ""))
                    sb.append("\n")
                }
            }
        }

        if (exportMode == "DIR_ONLY" || exportMode == "FULL_EXPORT") {
            if (isMd) sb.append("## Directory Structure\n\n```\n")
            else sb.append("--- Directory Structure ---\n\n")
            sb.append(buildTree(rootNode, showGlobalFileSize = showGlobalFileSize))
            if (isMd) sb.append("\n```\n\n") else sb.append("\n\n")
        }

        if (exportMode == "FULL_EXPORT") {
            if (isMd) sb.append("## File Contents\n\n")
            else sb.append("--- File Contents ---\n\n")
            
            var fileIndex = 0
            val totalFiles = countFiles(rootNode)

            fun extractContent(node: FileNode, path: String) {
                if (!node.isDir) {
                    fileIndex++
                    val relPath = if (path.isEmpty()) node.name else "$path/${node.name}"
                    val ext = node.name.substringAfterLast(".").lowercase()
                    val isSoftFiltered = softExcludes.contains(".$ext") || softExcludes.contains(ext)
                    
                    val sizeStr = if (showGlobalFileSize) " (${formatSize(node.size)})" else ""
                    if (isMd) sb.append("### [$fileIndex/$totalFiles] $projectName/$relPath$sizeStr\n\n")
                    else sb.append("[$fileIndex/$totalFiles] $projectName/$relPath$sizeStr\n\n")
                    
                    if (isSoftFiltered) {
                        sb.append("> **[Content omitted]** Excluded by a soft-exclusion rule.\n\n")
                    } else if (node.size > maxFileSizeKb * 1024L) {
                        sb.append("> **[Content omitted]** File size ${formatSize(node.size)} exceeds the ${maxFileSizeKb} KB limit.\n\n")
                    } else if (isTextFile(context, node)) {
                        try {
                            if (isMd) sb.append("```").append(getLang(ext)).append('\n')
                            sb.appendFileContent(node)
                            if (isMd) sb.append("\n```\n\n") else sb.append("\n\n")
                        } catch (e: Exception) {
                            sb.append("> Unable to read content.\n\n")
                        }
                    } else {
                        sb.append("> **[Content omitted]** Binary resource.\n\n")
                    }
                } else {
                    val newPath = if (path.isEmpty()) node.name else "$path/${node.name}"
                    node.children.sortedBy { it.sortKey }.forEach { extractContent(it, newPath) }
                }
            }
            rootNode.children.sortedBy { it.sortKey }.forEach { extractContent(it, "") }
        }
    }

    private fun isTextFile(context: Context, node: FileNode): Boolean {
        val ext = node.name.substringAfterLast(".").lowercase()
        val lowerName = node.name.lowercase()
        if (TEXT_EXTS.contains(ext)) return true
        if (lowerName == ".gitignore" || lowerName == ".editorconfig" || lowerName == ".gitattributes" || lowerName == ".nomedia") return true

        if (node.size in 1..102400) {
            try {
                val stream = node.file?.inputStream() ?: context.contentResolver.openInputStream(node.uri) ?: return false
                stream.use { input ->
                    val buffer = ByteArray(1024)
                    val read = input.read(buffer)
                    for (i in 0 until read) {
                        if (buffer[i] == 0.toByte()) return false
                    }
                    return true
                }
            } catch (_: Exception) {
            }
        }
        return false
    }

    private fun isTextFile(context: Context, fileName: String, size: Long, uri: Uri): Boolean {
        val ext = fileName.substringAfterLast(".").lowercase()
        val lowerName = fileName.lowercase()
        if (TEXT_EXTS.contains(ext)) return true
        if (lowerName == ".gitignore" || lowerName == ".editorconfig" || lowerName == ".gitattributes" || lowerName == ".nomedia") return true
        
        if (size in 1..102400) {
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    val buffer = ByteArray(1024)
                    val read = input.read(buffer)
                    for (i in 0 until read) {
                        if (buffer[i] == 0.toByte()) return false
                    }
                    return true
                }
            } catch (_: Exception) {}
        }
        return false
    }

    private fun buildTree(node: FileNode, prefix: String = "", showGlobalFileSize: Boolean = false): String {
        val sb = StringBuilder()
        appendTree(sb, node, prefix, showGlobalFileSize)
        return sb.toString()
    }

    // 递归写入同一个 StringBuilder，避免每个节点创建临时 String
    private fun appendTree(sb: StringBuilder, node: FileNode, prefix: String, showGlobalFileSize: Boolean) {
        if (prefix.isEmpty()) sb.append("└── ").append(node.name).append("/\n")
        val children = node.children.sortedWith(compareBy({ !it.isDir }, { it.sortKey }))
        children.forEachIndexed { i, child ->
            val isLast = i == children.size - 1
            val conn = if (isLast) "└── " else "├── "
            val actualPrefix = if (prefix.isEmpty()) "    " else prefix

            val sizeStr = if (showGlobalFileSize && !child.isDir) " (${formatSize(child.size)})" else ""
            val displayName = if (child.isDir) child.name + "/" else child.name
            sb.append(actualPrefix).append(conn).append(displayName).append(sizeStr).append('\n')

            if (child.isDir) {
                val nextPrefix = actualPrefix + if (isLast) "    " else "│   "
                appendTree(sb, child, nextPrefix, showGlobalFileSize)
            }
        }
    }

    private fun countFiles(node: FileNode): Int {
        var count = 0
        if (!node.isDir) count++
        node.children.forEach { count += countFiles(it) }
        return count
    }

        private fun getLang(ext: String): String = when (ext.lowercase()) {
        // 常见编程语言
        "kt", "kts" -> "kotlin"
        "java" -> "java"
        "py" -> "python"
        "gd" -> "gdscript"
        "c" -> "c"
        "cpp", "cc", "cxx", "h", "hpp" -> "cpp"
        "cs" -> "csharp"
        "js", "mjs" -> "javascript"
        "ts" -> "typescript"
        "jsx" -> "jsx"
        "tsx" -> "tsx"
        "go" -> "go"
        "rs" -> "rust"
        "swift" -> "swift"
        "php" -> "php"
        "rb" -> "ruby"
        "lua" -> "lua"
        "dart" -> "dart"
        "r" -> "r"
        
        // 脚本与命令行
        "sh", "bash" -> "bash"
        "zsh" -> "zsh"
        "bat", "cmd" -> "bat"
        "ps1" -> "powershell"
        
        // 标记语言与样式
        "html", "htm" -> "html"
        "css" -> "css"
        "scss" -> "scss"
        "less" -> "less"
        "xml", "svg" -> "xml"
        "md", "markdown" -> "markdown"
        "txt" -> "text"
        
        // 数据与配置
        "json" -> "json"
        "yaml", "yml" -> "yaml"
        "toml" -> "toml"
        "ini", "cfg", "properties", "godot", "tscn", "tres", "import", "uid" -> "ini"
        "sql" -> "sql"
        "gradle" -> "groovy"
        
        // 着色器与图形
        "glsl", "frag", "vert", "gdshader" -> "glsl"
        "shader", "hlsl" -> "hlsl"
        "obj", "mtl" -> "wavefront"
        
        else -> ""
    }

    /**
     * 生成注入到文档开头的提示词。
     * - 内置模式 GENERAL / ADVANCED / CODE_PR_STYLE：按 Godot 版本注入差异化规则
     * - 自定义模式 "CUSTOM:<id>"：正文取自 [customPrompts]，支持 {{version}} 占位符
     */
    fun buildPrompt(mode: String, version: String, customPrompts: Map<String, String> = emptyMap()): String {
        if (mode == "NONE") return ""

        if (mode.startsWith("CUSTOM:")) {
            val body = customPrompts[mode.removePrefix("CUSTOM:")].orEmpty()
            if (body.isBlank()) return ""
            return body.replace("{{version}}", version).trimEnd() + "\n\n"
        }

        val v = version.toFloatOrNull() ?: 4.0f
        val sb = StringBuilder()

        sb.append("## AI Task Instructions\n\n")
        sb.append("### Role\n")
        sb.append("You are a senior Godot $version engineer. The document that follows contains the source code of a Godot $version project.\n\n")

        sb.append("### Hard Rules\n")
        sb.append("- Reference resources across files with `res://` paths. Never use absolute paths.\n")
        sb.append("- Use GDScript and Godot 4.x APIs only.\n")
        sb.append("- Do not use Godot 3 APIs (for example `KinematicBody` or `instance()`).\n")
        if (v >= 4.4f) {
            sb.append("- `.uid` files are generated by the editor. Do not create or modify them.\n")
        }
        if (v >= 4.6f) {
            sb.append("- `unique_id` values in `.tscn` files are maintained by the editor. Do not write them.\n")
            sb.append("- The default physics engine is Jolt Physics. Do not change physics server settings without a reason.\n")
        }
        if (v >= 4.7f) {
            sb.append("- `is_class` parameters are `StringName`. Use the `&\"ClassName\"` syntax.\n")
            sb.append("- `RichTextLabel.add_image` takes `width_unit` and `height_unit`.\n")
            sb.append("- Assigning to a `PackedArray` directly does not trigger setters. Use `.set(i, value)`.\n")
            sb.append("- Overridden methods such as `_process` must contain an explicit `return` statement.\n")
        }
        sb.append("\n")

        if (mode == "ADVANCED" || mode == "CODE_PR_STYLE") {
            sb.append("### Engineering Guidelines\n")
            sb.append("1. Scene graph: read the `.tscn` files below and understand the node tree, including the hierarchy of Camera3D, CharacterBody3D, Marker3D, and UI nodes.\n")
            sb.append("2. Physics: when the code uses `PhysicsRayQueryParameters3D`, verify `collision_layer` and `collision_mask` so the ray hits the intended targets.\n")
            sb.append("3. Resource loading: use `preload()` for resources that are always needed and `load()` for dynamic resources.\n")
            sb.append("4. UI in 3D: nodes under CanvasLayer use screen coordinates. For a screen-center crosshair, use `get_viewport().get_visible_rect().size / 2`.\n")
            sb.append("5. Decoupling: prefer signals and groups over hard-coded node paths such as `get_node(\"../../Player\")`. Use `get_tree().get_nodes_in_group(\"player_team\")` where appropriate.\n\n")
        }

        if (mode == "CODE_PR_STYLE") {
            sb.append("### Automated Patch Workflow (highest priority)\n")
            sb.append("To modify the project, output a single standalone Python script (for example `patch.py`) that can be run from the project root.\n")
            sb.append("Requirements:\n")
            sb.append("1. Paths: use `pathlib.Path` and resolve everything from the script location (`root = Path(__file__).resolve().parent`). Do not mix in absolute paths.\n")
            sb.append("2. Encoding: always write files with `encoding=\"utf-8\"` and `newline=\"\\n\"`.\n")
            sb.append("3. Strategy:\n")
            sb.append("   - New files: write them directly.\n")
            sb.append("   - Full rewrites: write the complete file content.\n")
            sb.append("   - Targeted edits: wrap the old and new code in Python raw strings (`r\"\"\"...\"\"\"`). Do not rely on escape-prone string replacement.\n")
            sb.append("4. Style: keep the generated GDScript consistent with Godot 4.x formatting (tabs or four spaces, but not mixed).\n")
            sb.append("5. Do not modify `.uid` files or `unique_id` values.\n\n")
        }

        sb.append("### Request\n")
        sb.append("Read the project structure and code that follow, then wait for the user's development request.\n")

        return sb.toString()
    }
    // ================== 原生扫描与路径解析 ==================

    private fun readText(context: Context, node: FileNode, source: TextSource? = null): String {
        source?.let { return it.get(node) }
        // 大文件用 64KB 缓冲，减少 read syscall 次数
        node.file?.let { file ->
            return file.inputStream().use { input ->
                input.reader(Charsets.UTF_8).buffered(64 * 1024).readText()
            }
        }
        return context.contentResolver.openInputStream(node.uri)?.bufferedReader()?.use { it.readText() } ?: ""
    }

    /**
     * 把 SAF 树 URI（content://...）解析为真实文件路径。
     * 只处理本地存储卷（primary / SD 卡），其它 provider 返回 null 以回退 SAF。
     */
    private fun resolveTreeUriToFile(uri: Uri): File? {
        return try {
            val docId = DocumentsContract.getTreeDocumentId(uri)
            val parts = docId.split(":", limit = 2)
            if (parts.size != 2) return null
            val volume = parts[0]
            val relative = parts[1]
            val base = if (volume.equals("primary", true) || volume.equals("home", true)) {
                Environment.getExternalStorageDirectory()
            } else {
                File("/storage", volume)
            }
            val target = if (relative.isBlank()) base else File(base, relative)
            if (target.isDirectory && target.canRead()) target else null
        } catch (_: Exception) {
            null
        }
    }

    private fun buildNativeRoot(root: File, hardExcludes: Set<String>): Pair<FileNode, String>? {
        if (!root.isDirectory || !root.canRead()) return null
        val skipDirs = hardExcludes.map { it.removeSuffix("/") }.toSet()
        val entries = NativeScanner.listFiles(root, skipDirs) ?: return null
        if (entries.isEmpty()) return null

        val detectedVersion = detectVersionFromFile(File(root, "project.godot"))
        val rootNode = FileNode(root.name, true, Uri.EMPTY, 0, null, file = root)

        // 目录索引：按相对路径缓存，避免每个文件都做一次线性查找（大项目下是 O(n²)）
        val dirCache = HashMap<String, FileNode>()
        dirCache[""] = rootNode

        entries.forEach { entry ->
            val rel = entry.relativePath
            val name = rel.substringAfterLast('/')
            val ext = name.substringAfterLast(".").lowercase()
            if (hardExcludes.contains(".$ext") || hardExcludes.contains(ext)) {
                return@forEach
            }

            val parent = getOrCreateDir(dirCache, rel.substringBeforeLast('/', ""), root, rootNode)
            val file = File(root, rel)
            val size = if (entry.size >= 0) entry.size else file.length()
            parent.children.add(FileNode(name, false, Uri.EMPTY, size, null, file = file))
        }
        return rootNode to detectedVersion
    }

    private interface TextSource {
        fun get(node: FileNode): String
        fun close() {}
    }

    /**
     * 分批流水线：写入线程按输出顺序消费，后台线程并行预读后面几个文件。
     * 同时受「窗口文件数」和「待读字节数」双重限制，内存可控。
     */
    private class TextPipeline(
        private val ordered: List<FileNode>,
        private val loader: (FileNode) -> String,
        private val maxPendingFiles: Int = 6,
        private val maxPendingBytes: Long = 8L * 1024 * 1024
    ) : TextSource {

        private class Pending(
            val node: FileNode,
            val future: java.util.concurrent.Future<String>,
            val size: Long
        )

        private val executor = Executors.newFixedThreadPool(
            Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        ) { runnable -> Thread(runnable, "codepr-pipeline").apply { isDaemon = true } }

        private val pending = ArrayDeque<Pending>()
        private var nextToSchedule = 0
        private var pendingBytes = 0L

        private fun schedule() {
            while (nextToSchedule < ordered.size && pending.size < maxPendingFiles) {
                val node = ordered[nextToSchedule]
                if (pending.isNotEmpty() && pendingBytes + node.size > maxPendingBytes) {
                    break
                }
                nextToSchedule++
                pendingBytes += node.size
                pending.addLast(Pending(node, executor.submit(Callable { loader(node) }), node.size))
            }
        }

        override fun get(node: FileNode): String {
            if (pending.isEmpty()) schedule()
            val iterator = pending.iterator()
            while (iterator.hasNext()) {
                val item = iterator.next()
                if (item.node === node) {
                    iterator.remove()
                    pendingBytes -= item.size
                    schedule()
                    return try {
                        item.future.get()
                    } catch (_: Exception) {
                        loader(node)
                    }
                }
            }
            // 顺序不符（例如过滤结果在扫描期间变化），退回同步读取
            return loader(node)
        }

        override fun close() {
            executor.shutdownNow()
        }
    }

    /**
     * 按输出顺序收集「会被读取内容」的文件，供流水线预读。
     * 注意：children 的排序必须与 buildOutput 中的排序一致。
     */
    private fun collectTextNodes(
        context: Context,
        rootNode: FileNode,
        softExcludes: Set<String>,
        maxFileSizeKb: Int
    ): List<FileNode> {
        val result = ArrayList<FileNode>()

        fun walk(node: FileNode) {
            if (node.isDir) {
                node.children.sortedBy { it.sortKey }.forEach(::walk)
                return
            }
            val ext = node.name.substringAfterLast(".").lowercase()
            if (softExcludes.contains(".$ext") || softExcludes.contains(ext)) return
            if (node.size > maxFileSizeKb * 1024L) return
            if (!isTextFile(context, node)) return
            result.add(node)
        }
        walk(rootNode)
        return result
    }

    private fun getOrCreateDir(
        dirs: MutableMap<String, FileNode>, path: String, root: File, rootNode: FileNode
    ): FileNode {
        if (path.isEmpty()) return rootNode
        dirs[path]?.let { return it }
        val parent = getOrCreateDir(dirs, path.substringBeforeLast('/', ""), root, rootNode)
        val node = FileNode(path.substringAfterLast('/'), true, Uri.EMPTY, 0, null, file = File(root, path))
        parent.children.add(node)
        dirs[path] = node
        return node
    }

    private fun detectVersionFromFile(file: File): String {
        if (!file.isFile) return "Unknown"
        return try {
            detectVersionFromText(file.bufferedReader().use { it.readText() })
        } catch (_: Exception) {
            "Unknown"
        }
    }

    private fun detectVersionFromText(text: String): String {
        val match = Regex("""config/features\s*=\s*PackedStringArray\("([^"]+)""").find(text)
        if (match != null) {
            val raw = match.groupValues[1]
            val parts = raw.split(".")
            return if (parts.size >= 2) "${parts[0]}.${parts[1]}" else raw
        }
        return if (text.contains("config_version=5")) "4.x" else "Unknown"
    }

    /**
     * 边写边统计字符/行数，并截取前 N 个字符作为预览。
     */
    /**
     * 输出目标：既能按字符追加，也能把文件内容按原始字节写入。
     */
    private interface ContentSink : Appendable {
        fun appendFileContent(node: FileNode)
    }

    /** 内存模式：内容仍读成 String 后追加 */
    private class StringBuilderSink(
        capacity: Int,
        private val load: (FileNode) -> String
    ) : ContentSink {
        private val builder = StringBuilder(capacity)

        override fun append(csq: CharSequence?): Appendable {
            builder.append(csq)
            return this
        }

        override fun append(csq: CharSequence?, start: Int, end: Int): Appendable {
            builder.append(csq, start, end)
            return this
        }

        override fun append(c: Char): Appendable {
            builder.append(c)
            return this
        }

        override fun appendFileContent(node: FileNode) {
            builder.append(load(node))
        }

        fun text(): String = builder.toString()
    }

    /**
     * 流式模式：文件内容直接以 UTF-8 原始字节写入输出流，跳过 decode/encode。
     * 字符数按 UTF-8 首字节统计（非连续字节即为一个字符），行数按 0x0A 统计。
     */
    private class StreamingSink(
        private val writer: Writer,
        private val output: OutputStream,
        private val previewLimit: Int,
        private val zeroCopy: Boolean,
        private val load: (FileNode) -> String
    ) : ContentSink {
        private val previewBytes = ByteArrayOutputStream(minOf(previewLimit, 8192))
        var chars: Long = 0L
            private set
        var newlines: Int = 0
            private set

        override fun append(csq: CharSequence?): Appendable = append(csq, 0, csq?.length ?: 0)

        override fun append(csq: CharSequence?, start: Int, end: Int): Appendable {
            if (csq != null && end > start) {
                chars += (end - start)
                var searchFrom = start
                while (searchFrom < end) {
                    val index = csq.indexOf('\n', searchFrom)
                    if (index < 0 || index >= end) break
                    newlines++
                    searchFrom = index + 1
                }
                if (previewBytes.size() < previewLimit) {
                    capture(csq.subSequence(start, end).toString().toByteArray(Charsets.UTF_8))
                }
                writer.append(csq, start, end)
            }
            return this
        }

        override fun append(c: Char): Appendable {
            chars++
            if (c == '\n') newlines++
            if (previewBytes.size() < previewLimit) {
                capture(c.toString().toByteArray(Charsets.UTF_8))
            }
            writer.append(c)
            return this
        }

        private fun capture(bytes: ByteArray, length: Int = bytes.size) {
            if (length <= 0) return
            val room = previewLimit - previewBytes.size()
            if (room <= 0) return
            previewBytes.write(bytes, 0, minOf(length, room))
        }

        override fun appendFileContent(node: FileNode) {
            val file = node.file
            if (!zeroCopy || file == null) {
                // 关闭零拷贝，或 SAF 没有真实路径时，退回字符读取
                append(load(node))
                return
            }
            writer.flush()
            val buffer = ByteArray(64 * 1024)
            file.inputStream().use { input ->
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    for (i in 0 until read) {
                        val b = buffer[i].toInt() and 0xFF
                        if (b == 0x0A) newlines++
                        if ((b and 0xC0) != 0x80) chars++
                    }
                    capture(buffer, read)
                    output.write(buffer, 0, read)
                }
            }
            output.flush()
        }

        fun previewText(): String {
            if (previewBytes.size() == 0) return ""
            val text = String(previewBytes.toByteArray(), Charsets.UTF_8)
            // 截断可能落在多字节字符中间，去掉末尾的替换符
            return if (text.endsWith('\uFFFD')) text.dropLast(1) else text
        }
    }
}
