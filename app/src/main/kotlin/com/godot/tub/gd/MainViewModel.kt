package com.godot.tub.gd

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.godot.tub.gd.utils.AnnouncementUtils
import com.godot.tub.gd.utils.SourceMergeUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class SelectedFile(val uri: Uri, val name: String)

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
    val zeroCopy: Boolean = true
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

    val errorMessage = MutableSharedFlow<String>()

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.getSharedPreferences("TUB_GD_SETTINGS", Context.MODE_PRIVATE)
        historyFile = File(context.filesDir, "history.json")
        
        val softStr = prefs.getString("softExcludeRules", "") ?: ""
        val softRules = if (softStr.isBlank()) emptySet() else softStr.split(",").toSet()
        
        val hardStr = prefs.getString("hardExcludeRules", "") ?: ""
        val hardRules = if (hardStr.isBlank()) emptySet() else hardStr.split(",").toSet()
        
        val savedPath = prefs.getString("outputPath", null)
        val finalPath = if (savedPath.isNullOrBlank()) "/storage/emulated/0/Documents/" else savedPath

        var savedMode = prefs.getString("exportMode", "DIR_ONLY") ?: "DIR_ONLY"
        if (savedMode == "PROJECT_SUMMARY") {
            savedMode = "DIR_ONLY"
            prefs.edit().putString("exportMode", "DIR_ONLY").apply()
        }
        
        settings.value = AppSettings(
            themeMode = prefs.getInt("themeMode", 0),
            outputPath = finalPath,
            format = prefs.getString("format", "MARKDOWN") ?: "MARKDOWN",
            exportMode = savedMode,
            promptMode = prefs.getString("promptMode", "GENERAL") ?: "GENERAL",
            softExcludeRules = softRules,
            hardExcludeRules = hardRules,
            maxFileSizeKb = prefs.getInt("maxFileSizeKb", 100),
            showGlobalFileSize = prefs.getBoolean("showGlobalFileSize", true),
            streamOutput = prefs.getBoolean("streamOutput", true),
            zeroCopy = prefs.getBoolean("zeroCopy", true)
        )
        lastSeenAnnouncementId = prefs.getInt("lastAnnouncementId", 0)

        // 历史记录放到后台读取，避免启动时主线程解析大 JSON
        viewModelScope.launch(Dispatchers.IO) { loadHistory() }

        // 启动时静默检查公告：只有出现更新的 ID 才会弹窗
        refreshAnnouncements(silent = true)
    }

    /**
     * 拉取远程公告。
     * @param silent true = 启动时静默检查（无网络/无更新都不打扰用户）；false = 用户手动点开。
     */
    fun refreshAnnouncements(silent: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            val fetched = AnnouncementUtils.fetch()
            if (fetched.isEmpty()) {
                if (!silent) errorMessage.emit("公告获取失败，请检查网络后重试")
                return@launch
            }
            announcements.value = fetched

            val unread = fetched.filter { it.id > lastSeenAnnouncementId }
            if (unread.isEmpty() && silent) return@launch

            pendingAnnouncements.value = if (unread.isNotEmpty()) unread else fetched.take(1)
            announcementDialogVisible.value = true
        }
    }

    /** 关闭公告并把本机已读 ID 推到远程最大值，避免重复打扰。 */
    fun dismissAnnouncements() {
        announcementDialogVisible.value = false
        val maxId = announcements.value.maxOfOrNull { it.id } ?: return
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
                putBoolean("zeroCopy", newSettings.zeroCopy)
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
                        outFile, previewLimit = 50_000, zeroCopy = cfg.zeroCopy
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
                        context, uri, cfg.format, cfg.exportMode, cfg.promptMode, cfg.softExcludeRules, cfg.hardExcludeRules, cfg.maxFileSizeKb, cfg.showGlobalFileSize
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

    fun viewHistoryItem(item: ExportResult) {
        result.value = item
        currentScreen.value = "result"
    }
}
