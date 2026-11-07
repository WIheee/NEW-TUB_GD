package com.wihee.code.pr

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wihee.code.pr.utils.AnnouncementUtils
import com.wihee.code.pr.utils.SourceMergeUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class SelectedFile(val uri: Uri, val name: String)

/** 用户自定义提示词；内置模式（GENERAL / ADVANCED / CODE_PR_STYLE）不在这里。 */
data class PromptTemplate(
    val id: String,
    val name: String,
    val body: String
)

data class AppSettings(
    val themeMode: Int = 0,
    val outputPath: String = "/storage/emulated/0/Documents/",
    val format: String = "MARKDOWN",
    val exportMode: String = "DIR_ONLY",
    val promptMode: String = "GENERAL",
    val softExcludeRules: Set<String> = emptySet(),
    val hardExcludeRules: Set<String> = emptySet(),
    val maxFileSizeKb: Int = 100,
    val showGlobalFileSize: Boolean = true,
    val streamOutput: Boolean = true,
    val customPrompts: List<PromptTemplate> = emptyList()
)

data class ExportResult(
    val fileName: String, val filePath: String, val charCount: Int,
    val tokenCount: Int, val lineCount: Int, val fullText: String,
    val godotVersion: String = "4.x",
    val timestamp: Long = System.currentTimeMillis(),
    val streamed: Boolean = false
)

class MainViewModel : ViewModel() {
    private lateinit var prefs: SharedPreferences
    private lateinit var historyFile: File
    
    val settings = MutableStateFlow(AppSettings())
    val currentScreen = MutableStateFlow("home")
    var previousScreen = "home"
    val result = MutableStateFlow<ExportResult?>(null)
    val historyList = MutableStateFlow<List<ExportResult>>(emptyList())
    val pendingMultiFiles = MutableStateFlow<List<SelectedFile>>(emptyList())
    val isLoading = MutableStateFlow(false)

    val announcements = MutableStateFlow<List<AnnouncementUtils.Announcement>>(emptyList())
    val pendingAnnouncements = MutableStateFlow<List<AnnouncementUtils.Announcement>>(emptyList())
    val announcementDialogVisible = MutableStateFlow(false)

    private var lastSeenAnnouncementId = 0
    private var appVersionName: String = ""

    val errorMessage = MutableSharedFlow<String>()

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.getSharedPreferences("CODE_PR_SETTINGS", Context.MODE_PRIVATE)
        historyFile = File(context.filesDir, "history.json")
        appVersionName = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty()
        
        fun parseRuleSet(raw: String?): Set<String> =
            raw?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()

        // 首次启动把内置默认规则并入用户列表：之后它们和自定义规则一样可删除、可重新添加。
        val defaultsSeeded = prefs.getBoolean("excludeDefaultsSeeded", false)
        val softRules: Set<String> = parseRuleSet(prefs.getString("softExcludeRules", "")) +
            if (defaultsSeeded) emptySet<String>() else SourceMergeUtils.DEFAULT_SOFT_EXTS
        val hardRules: Set<String> = parseRuleSet(prefs.getString("hardExcludeRules", "")) +
            if (defaultsSeeded) emptySet<String>() else SourceMergeUtils.DEFAULT_HARD_DIRS.map { "$it/" }
        if (!defaultsSeeded) {
            prefs.edit()
                .putString("softExcludeRules", softRules.joinToString(","))
                .putString("hardExcludeRules", hardRules.joinToString(","))
                .putBoolean("excludeDefaultsSeeded", true)
                .apply()
        }
        
        val savedPath = prefs.getString("outputPath", null)
        val finalPath = if (savedPath.isNullOrBlank()) "/storage/emulated/0/Documents/" else savedPath

        var savedMode = prefs.getString("exportMode", "DIR_ONLY") ?: "DIR_ONLY"
        if (savedMode == "PROJECT_SUMMARY") {
            savedMode = "DIR_ONLY"
            prefs.edit().putString("exportMode", "DIR_ONLY").apply()
        }

        // 旧版本的提示词模式 id 随品牌更名迁移
        var savedPromptMode = prefs.getString("promptMode", "GENERAL") ?: "GENERAL"
        if (savedPromptMode == "TUB_STYLE") {
            savedPromptMode = "CODE_PR_STYLE"
            prefs.edit().putString("promptMode", "CODE_PR_STYLE").apply()
        }
        
        settings.value = AppSettings(
            themeMode = prefs.getInt("themeMode", 0),
            outputPath = finalPath,
            format = prefs.getString("format", "MARKDOWN") ?: "MARKDOWN",
            exportMode = savedMode,
            promptMode = savedPromptMode,
            softExcludeRules = softRules,
            hardExcludeRules = hardRules,
            maxFileSizeKb = prefs.getInt("maxFileSizeKb", 100),
            showGlobalFileSize = prefs.getBoolean("showGlobalFileSize", true),
            streamOutput = prefs.getBoolean("streamOutput", true),
            customPrompts = parsePromptTemplates(prefs.getString("customPrompts", "") ?: "")
        )
        lastSeenAnnouncementId = prefs.getInt("lastAnnouncementId", 0)

        // 历史记录放到后台读取，避免启动时主线程解析大 JSON
        viewModelScope.launch(Dispatchers.IO) { loadHistory() }

        // 启动时静默检查公告：信息类只在有新 ID 时弹，版本更新只在确实有更高版本时弹
        refreshAnnouncements(silent = true)
    }

    /**
     * 拉取远程公告。
     * - 信息类公告：ID 大于本机已读 ID 时提示一次
     * - 版本更新公告：只要目标版本高于当前版本，每次启动都提示；已是最新版本则直接忽略
     *
     * @param silent true = 启动时静默检查（无网络/无新内容都不打扰用户）；false = 用户手动点开。
     */
    fun refreshAnnouncements(silent: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            val fetched = AnnouncementUtils.fetch()
            if (fetched.isEmpty()) {
                if (!silent) errorMessage.emit("公告获取失败，请检查网络后重试")
                return@launch
            }
            announcements.value = fetched

            // 当前已是最新版本时，旧版本的更新公告不再展示
            val visible = fetched.filter { announcement ->
                !announcement.isUpdate ||
                    announcement.version.isBlank() ||
                    isNewerThanCurrent(announcement.version)
            }
            val pending = buildPendingAnnouncements(visible)

            if (pending.isEmpty()) {
                if (silent) return@launch
                if (visible.isEmpty()) {
                    errorMessage.emit("当前已是最新版本，暂无新公告")
                } else {
                    // 手动点开时允许回看最新一条普通公告
                    pendingAnnouncements.value = visible.take(1)
                    announcementDialogVisible.value = true
                }
                return@launch
            }

            pendingAnnouncements.value = pending
            announcementDialogVisible.value = true
        }
    }

    private fun isNewerThanCurrent(version: String): Boolean =
        appVersionName.isBlank() || AnnouncementUtils.isVersionNewer(version, appVersionName)

    /**
     * 启动时只挑真正需要打扰用户的内容：
     * - 最新的一条「目标版本高于当前」的更新公告（每次启动都提示，直到更新）
     * - 未读的普通公告，以及没有写版本号的更新公告（只提示一次）
     */
    private fun buildPendingAnnouncements(
        visible: List<AnnouncementUtils.Announcement>
    ): List<AnnouncementUtils.Announcement> {
        val newestUpdate = visible
            .filter { it.isUpdate && it.version.isNotBlank() && isNewerThanCurrent(it.version) }
            .maxByOrNull { it.id }
        val unread = visible.filter { announcement ->
            announcement.id > lastSeenAnnouncementId &&
                (!announcement.isUpdate || announcement.version.isBlank())
        }
        return buildList {
            if (newestUpdate != null) add(newestUpdate)
            addAll(unread)
        }.distinctBy { it.id }.sortedByDescending { it.id }
    }

    /** 关闭公告：只推进普通公告的已读 ID；版本更新公告要保持每次启动都提醒。 */
    fun dismissAnnouncements() {
        announcementDialogVisible.value = false
        val maxId = announcements.value
            .filter { !it.isUpdate || it.version.isBlank() }
            .maxOfOrNull { it.id } ?: return
        if (maxId <= lastSeenAnnouncementId) return
        lastSeenAnnouncementId = maxId
        if (::prefs.isInitialized) {
            prefs.edit().putInt("lastAnnouncementId", maxId).apply()
        }
    }

    fun updateSettings(update: (AppSettings) -> AppSettings) {
        val newSettings = update(settings.value)
        settings.value = newSettings
        if (::prefs.isInitialized) {
            prefs.edit().apply {
                putInt("themeMode", newSettings.themeMode)
                putString("outputPath", newSettings.outputPath)
                putString("format", newSettings.format)
                putString("exportMode", newSettings.exportMode)
                putString("promptMode", newSettings.promptMode)
                putString("softExcludeRules", newSettings.softExcludeRules.joinToString(","))
                putString("hardExcludeRules", newSettings.hardExcludeRules.joinToString(","))
                putInt("maxFileSizeKb", newSettings.maxFileSizeKb)
                putBoolean("showGlobalFileSize", newSettings.showGlobalFileSize)
                putBoolean("streamOutput", newSettings.streamOutput)
                putString("customPrompts", serializePromptTemplates(newSettings.customPrompts))
                apply()
            }
        }
    }

    fun generateFromTreeUri(context: Context, uri: Uri) {
        isLoading.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val cfg = settings.value
                val rootDoc = DocumentFile.fromTreeUri(context, uri)
                val folderName = rootDoc?.name ?: "Project"
                if (cfg.streamOutput) {
                    val outDir = File(cfg.outputPath)
                    if (!outDir.exists() && !outDir.mkdirs()) {
                        throw Exception("无法创建输出目录，请检查存储权限")
                    }
                    val ext = if (cfg.format == "MARKDOWN") "md" else cfg.format.lowercase()
                    val outFile = File(outDir, "${folderName}_source.$ext")
                    val scanResult = SourceMergeUtils.scanDirectoryToFile(
                        context, uri, cfg.format, cfg.exportMode, cfg.promptMode,
                        cfg.softExcludeRules, cfg.hardExcludeRules, cfg.maxFileSizeKb, cfg.showGlobalFileSize,
                        outFile, previewLimit = 50_000, customPrompts = cfg.promptBodyMap()
                    )
                    val newResult = ExportResult(
                        fileName = outFile.name,
                        filePath = outDir.absolutePath,
                        charCount = scanResult.charCount,
                        tokenCount = scanResult.charCount / 4,
                        lineCount = scanResult.lineCount,
                        fullText = scanResult.content,
                        godotVersion = scanResult.godotVersion,
                        streamed = true
                    )
                    result.value = newResult
                    addHistory(newResult)
                    currentScreen.value = "result"
                } else {
                    val scanResult = SourceMergeUtils.scanDirectory(
                        context, uri, cfg.format, cfg.exportMode, cfg.promptMode, cfg.softExcludeRules, cfg.hardExcludeRules, cfg.maxFileSizeKb, cfg.showGlobalFileSize,
                        customPrompts = cfg.promptBodyMap()
                    )
                    saveResult(folderName, scanResult.content, scanResult.godotVersion)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                errorMessage.emit("扫描失败: ${e.message ?: "请检查是否授予了文件访问权限"}")
            } finally {
                isLoading.value = false
            }
        }
    }

    fun addPendingMultiFile(context: Context, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            var name = "unknown_file"
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIdx != -1) name = cursor.getString(nameIdx)
                }
            }
            val file = SelectedFile(uri, name)
            val list = pendingMultiFiles.value.toMutableList()
            if (list.none { it.uri == uri }) {
                list.add(file)
                pendingMultiFiles.value = list
            }
        }
    }

    fun removePendingMultiFile(file: SelectedFile) {
        val list = pendingMultiFiles.value.toMutableList()
        list.remove(file)
        pendingMultiFiles.value = list
    }

    fun clearPendingMultiFiles() {
        pendingMultiFiles.value = emptyList()
    }

    fun startMultiFileMerge(context: Context) {
        val files = pendingMultiFiles.value
        if (files.isEmpty()) return
        generateFromMultipleFiles(context, files)
    }

    fun generateFromMultipleFiles(context: Context, files: List<SelectedFile>) {
        isLoading.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val cfg = settings.value
                val scanResult = SourceMergeUtils.processMultipleFiles(
                    context, files, cfg.format, cfg.softExcludeRules, cfg.hardExcludeRules, cfg.maxFileSizeKb, cfg.showGlobalFileSize
                )
                saveResult("MultiFiles", scanResult.content, scanResult.godotVersion)
                clearPendingMultiFiles()
            } catch (e: Exception) {
                e.printStackTrace()
                errorMessage.emit("合并失败: ${e.message}")
            } finally {
                isLoading.value = false
            }
        }
    }

    private fun saveResult(baseName: String, text: String, godotVersion: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val cfg = settings.value
                val outDir = File(cfg.outputPath)
                if (!outDir.exists()) {
                    val created = outDir.mkdirs()
                    if (!created) throw Exception("无法创建输出目录，请检查存储权限")
                }
                val ext = if (cfg.format == "MARKDOWN") "md" else cfg.format.lowercase()
                val outFile = File(outDir, "${baseName}_source.$ext")
                outFile.writeText(text)
                
                val newResult = ExportResult(
                    fileName = outFile.name, filePath = outDir.absolutePath,
                    charCount = text.length, tokenCount = text.length / 4,
                    lineCount = text.count { it == '\n' } + 1, fullText = text, godotVersion = godotVersion
                )
                result.value = newResult
                addHistory(newResult)
                currentScreen.value = "result"
            } catch (e: Exception) {
                e.printStackTrace()
                errorMessage.emit("保存文件失败: ${e.message ?: "请检查写入权限或存储空间"}")
            }
        }
    }

    private fun addHistory(item: ExportResult) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val list = historyList.value.toMutableList()
                list.add(0, item)
                if (list.size > 20) list.removeAt(list.size - 1)
                historyList.value = list
                
                val jsonArray = JSONArray()
                list.forEach {
                    val obj = JSONObject()
                    obj.put("fileName", it.fileName)
                    obj.put("filePath", it.filePath)
                    obj.put("charCount", it.charCount)
                    obj.put("tokenCount", it.tokenCount)
                    obj.put("lineCount", it.lineCount)
                    obj.put("fullText", it.fullText)
                    obj.put("godotVersion", it.godotVersion)
                    obj.put("timestamp", it.timestamp)
                    obj.put("streamed", it.streamed)
                    jsonArray.put(obj)
                }
                historyFile.writeText(jsonArray.toString())
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun loadHistory() {
        if (!historyFile.exists()) return
        try {
            val jsonStr = historyFile.readText()
            if (jsonStr.isBlank()) return
            val array = JSONArray(jsonStr)
            val list = mutableListOf<ExportResult>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(ExportResult(
                    fileName = obj.getString("fileName"),
                    filePath = obj.getString("filePath"),
                    charCount = obj.getInt("charCount"),
                    tokenCount = obj.getInt("tokenCount"),
                    lineCount = obj.getInt("lineCount"),
                    fullText = obj.getString("fullText"),
                    godotVersion = obj.optString("godotVersion", "4.x"),
                    timestamp = obj.getLong("timestamp"),
                    streamed = obj.optBoolean("streamed", false)
                ))
            }
            historyList.value = list
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    fun clearHistory() {
        historyList.value = emptyList()
        if (historyFile.exists()) historyFile.delete()
    }

    /** 自定义提示词按 id -> 正文交给导出层；内置模式不需要查表。 */
    private fun AppSettings.promptBodyMap(): Map<String, String> =
        customPrompts.associate { it.id to it.body }

    private fun parsePromptTemplates(raw: String): List<PromptTemplate> {
        if (raw.isBlank()) return emptyList()
        return try {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i)
                    if (obj != null) {
                        val id = obj.optString("id")
                        if (id.isNotBlank()) {
                            add(
                                PromptTemplate(
                                    id = id,
                                    name = obj.optString("name").ifBlank { "自定义提示词" },
                                    body = obj.optString("body")
                                )
                            )
                        }
                    }
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun serializePromptTemplates(templates: List<PromptTemplate>): String {
        val array = JSONArray()
        templates.forEach { template ->
            array.put(JSONObject().apply {
                put("id", template.id)
                put("name", template.name)
                put("body", template.body)
            })
        }
        return array.toString()
    }

    fun viewHistoryItem(item: ExportResult) {
        result.value = item
        currentScreen.value = "result"
    }
}
