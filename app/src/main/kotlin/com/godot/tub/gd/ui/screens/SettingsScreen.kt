package com.godot.tub.gd.ui.screens

import android.widget.Toast
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.godot.tub.gd.AppSettings
import com.godot.tub.gd.utils.AnnouncementUtils
import com.godot.tub.gd.utils.SourceMergeUtils
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    onUpdateSettings: ((AppSettings) -> AppSettings) -> Unit
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val versionName = remember(context) {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "B1"
    }
    val scope = rememberCoroutineScope()

    var showPathDialog by remember { mutableStateOf(false) }
    var showAddRuleDialog by remember { mutableStateOf(false) }
    var showPromptDialog by remember { mutableStateOf(false) }
    var showDefaultRulesDialog by remember { mutableStateOf(false) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    var ruleToDelete by remember { mutableStateOf<Pair<String, Boolean>?>(null) }

    val scrollState = rememberScrollState()

    var tempPath by remember { mutableStateOf(settings.outputPath) }
    LaunchedEffect(settings.outputPath) { tempPath = settings.outputPath }

    val infiniteTransition = rememberInfiniteTransition(label = "arrow")
    val bounceOffset by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 10f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ), label = "bounce"
    )

    val promptModeColor = when (settings.promptMode) {
        "GENERAL" -> MaterialTheme.colorScheme.secondary
        "ADVANCED" -> MaterialTheme.colorScheme.tertiary
        "TUB_STYLE" -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    val promptModeText = when (settings.promptMode) {
        "NONE" -> "不注入（纯净模式）"
        "GENERAL" -> "通用开发"
        "ADVANCED" -> "进阶开发"
        "TUB_STYLE" -> "TUB 同款"
        else -> "未选择"
    }

    // === 弹窗区 ===

    if (showPathDialog) {
        AlertDialog(
            onDismissRequest = { showPathDialog = false },
            shape = RoundedCornerShape(32.dp),
            title = { Text("修改导出路径", style = MaterialTheme.typography.headlineSmall) },
            text = {
                OutlinedTextField(
                    value = tempPath,
                    onValueChange = { tempPath = it },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onUpdateSettings { it.copy(outputPath = tempPath) }
                    showPathDialog = false
                    Toast.makeText(context, "路径已保存", Toast.LENGTH_SHORT).show()
                }) { Text("保存", fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { tempPath = settings.outputPath; showPathDialog = false }) { Text("取消") }
            }
        )
    }

    if (showDeleteConfirmDialog && ruleToDelete != null) {
        val (rule, isHard) = ruleToDelete!!
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            shape = RoundedCornerShape(32.dp),
            title = { Text("确认删除", style = MaterialTheme.typography.headlineSmall) },
            text = { Text("是否删除${if (isHard) "重排除" else "软排除"}规则：\n$rule ？") },
            confirmButton = {
                TextButton(onClick = {
                    if (isHard) onUpdateSettings { it.copy(hardExcludeRules = it.hardExcludeRules - rule) }
                    else onUpdateSettings { it.copy(softExcludeRules = it.softExcludeRules - rule) }
                    showDeleteConfirmDialog = false
                    ruleToDelete = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmDialog = false; ruleToDelete = null }) { Text("取消") }
            }
        )
    }

    if (showDefaultRulesDialog) {
        AlertDialog(
            onDismissRequest = { showDefaultRulesDialog = false },
            shape = RoundedCornerShape(32.dp),
            title = { Text("系统默认规则", style = MaterialTheme.typography.headlineSmall) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        "为了确保扫描速度与 AI 纯净度，系统内置了以下不可修改的规则：",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        "底层重排除（连路径都不显示）",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        SourceMergeUtils.DEFAULT_HARD_DIRS.joinToString(", ") { "$it/" },
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Spacer(modifier = Modifier.height(18.dp))

                    Text(
                        "默认软排除（保留路径，省略内容）",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        SourceMergeUtils.DEFAULT_SOFT_EXTS.joinToString(", "),
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            },
            confirmButton = { TextButton(onClick = { showDefaultRulesDialog = false }) { Text("我知道了") } }
        )
    }

    if (showAddRuleDialog) {
        var extInput by remember { mutableStateOf("") }
        var isHardMode by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { showAddRuleDialog = false },
            shape = RoundedCornerShape(32.dp),
            title = { Text("添加过滤规则", style = MaterialTheme.typography.headlineSmall) },
            text = {
                Column {
                    Text(
                        "语法提示：\n• 文件夹请以 / 结尾，如 场景/ 或 .godot/\n• 支持中文及常规符号，不能包含空格或特殊字符",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    OutlinedTextField(
                        value = extInput,
                        onValueChange = { extInput = it.trim() },
                        placeholder = { Text("例如: build/ 或 .log") },
                        singleLine = true,
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(18.dp))

                    Text("选择过滤类型", style = MaterialTheme.typography.titleSmall)
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { isHardMode = false }
                            .padding(vertical = 6.dp)
                    ) {
                        RadioButton(selected = !isHardMode, onClick = { isHardMode = false })
                        Column(modifier = Modifier.padding(start = 8.dp)) {
                            Text("软排除（推荐）", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                            Text(
                                "保留路径，但省略文件内容",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { isHardMode = true }
                            .padding(vertical = 6.dp)
                    ) {
                        RadioButton(selected = isHardMode, onClick = { isHardMode = true })
                        Column(modifier = Modifier.padding(start = 8.dp)) {
                            Text("重排除", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                            Text(
                                "彻底隐藏，连路径也不显示",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val rule = extInput.trim()
                    if (rule.isNotEmpty()) {
                        val isDirectory = rule.endsWith("/")
                        val cleanRule = if (isDirectory) rule.dropLast(1) else rule

                        // 简化非法字符校验：直接用字符串包含，避开正则转义地狱
                        val illegalChars = "\\:*?\"<>|"

                        if (cleanRule.isEmpty()) {
                            Toast.makeText(context, "规则不能仅为斜杠", Toast.LENGTH_SHORT).show()
                        } else if (cleanRule.startsWith("/")) {
                            Toast.makeText(context, "规则不能以斜杠开头", Toast.LENGTH_SHORT).show()
                        } else if (cleanRule.any { it in illegalChars }) {
                            Toast.makeText(context, "包含系统不支持的特殊字符", Toast.LENGTH_SHORT).show()
                        } else if (cleanRule.contains(" ")) {
                            Toast.makeText(context, "规则不能包含空格", Toast.LENGTH_SHORT).show()
                        } else {
                            val allHard = SourceMergeUtils.DEFAULT_HARD_DIRS + settings.hardExcludeRules
                            val allSoft = SourceMergeUtils.DEFAULT_SOFT_EXTS + settings.softExcludeRules

                            val existsInHard = if (isDirectory) allHard.any { it.removeSuffix("/") == cleanRule } else allHard.contains(cleanRule)
                            val existsInSoft = if (isDirectory) allSoft.any { it.removeSuffix("/") == cleanRule } else allSoft.contains(cleanRule)

                            if (existsInHard || existsInSoft) {
                                Toast.makeText(context, "该规则已存在，无需重复添加", Toast.LENGTH_SHORT).show()
                            } else {
                                if (isHardMode) onUpdateSettings { it.copy(hardExcludeRules = it.hardExcludeRules + rule) }
                                else onUpdateSettings { it.copy(softExcludeRules = it.softExcludeRules + rule) }
                                showAddRuleDialog = false
                            }
                        }
                    } else {
                        showAddRuleDialog = false
                    }
                }) { Text("添加", fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { showAddRuleDialog = false }) { Text("取消") } }
        )
    }

    if (showPromptDialog) {
        val promptModes = listOf(
            PromptModeOption("NONE", "不注入", "仅保留项目结构，适合让 AI 自己分析或你完全自定义提示词。", MaterialTheme.colorScheme.onSurfaceVariant),
            PromptModeOption("GENERAL", "通用开发", "扩展性极高，仅注入基本语法标准，适合简单问答和自由发挥。", MaterialTheme.colorScheme.secondary),
            PromptModeOption("ADVANCED", "进阶开发", "如同高级工程师全方位重构，并针对 3D 场景与物理深度优化。", MaterialTheme.colorScheme.tertiary),
            PromptModeOption("TUB_STYLE", "TUB 同款", "在进阶基础上强制 AI 生成 Python 一键构建脚本，效率翻三倍。", MaterialTheme.colorScheme.error)
        )
        AlertDialog(
            onDismissRequest = { showPromptDialog = false },
            shape = RoundedCornerShape(32.dp),
            title = { Text("AI 提示词模式", style = MaterialTheme.typography.headlineSmall) },
            text = {
                Column {
                    promptModes.forEach { option ->
                        val isSelected = (settings.promptMode == option.id)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(selected = isSelected, onClick = {
                                    onUpdateSettings { it.copy(promptMode = option.id) }
                                    showPromptDialog = false
                                })
                                .padding(vertical = 10.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = null,
                                colors = RadioButtonDefaults.colors(selectedColor = option.color)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = option.title,
                                    color = if (isSelected) option.color else MaterialTheme.colorScheme.onSurface,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    fontSize = 15.sp
                                )
                                Text(
                                    text = option.desc,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 12.sp,
                                    lineHeight = 16.sp,
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showPromptDialog = false }) { Text("取消") } }
        )
    }

    // === 主 UI ===
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .pointerInput(Unit) {
                detectTapGestures(onTap = { focusManager.clearFocus() })
            }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(start = 20.dp, top = 14.dp, end = 20.dp, bottom = 96.dp)
        ) {
            ScreenHeader(title = "设置", subtitle = "导出、过滤与提示词")
            Spacer(modifier = Modifier.height(20.dp))

            SectionHeader("外观", Icons.Default.Palette)
            SegmentedControl(
                options = listOf("跟随系统", "浅色", "深色"),
                selectedIndex = settings.themeMode,
                onSelected = { onUpdateSettings { s -> s.copy(themeMode = it) } },
                icons = listOf(Icons.Default.Settings, Icons.Default.WbSunny, Icons.Default.NightsStay)
            )
            Spacer(modifier = Modifier.height(24.dp))

            SectionHeader("导出路径", Icons.Default.Folder)
            ExpressiveCard(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                onClick = { tempPath = settings.outputPath; showPathDialog = true }
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(15.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Folder,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("输出文件夹", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                        Text(
                            settings.outputPath,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(onClick = {
                        onUpdateSettings { it.copy(outputPath = "/storage/emulated/0/Documents/") }
                        tempPath = "/storage/emulated/0/Documents/"
                        Toast.makeText(context, "已恢复默认路径", Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "恢复默认",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(24.dp))

            SectionHeader("输出格式", Icons.Default.Description)
            SegmentedControl(
                options = listOf("TXT 纯文本", "MARKDOWN", "XML"),
                selectedIndex = when (settings.format) { "TXT" -> 0; "MARKDOWN" -> 1; "XML" -> 2; else -> 1 },
                onSelected = {
                    onUpdateSettings { s ->
                        s.copy(format = when (it) { 0 -> "TXT"; 1 -> "MARKDOWN"; else -> "XML" })
                    }
                }
            )
            Spacer(modifier = Modifier.height(24.dp))

            SectionHeader("输出模式", Icons.Default.ViewList)
            SegmentedControl(
                options = listOf("仅目录", "项目配置", "完整导出"),
                selectedIndex = when (settings.exportMode) { "DIR_ONLY" -> 0; "PROJECT_CONFIG" -> 1; "FULL_EXPORT" -> 2; else -> 0 },
                onSelected = {
                    val m = when (it) { 0 -> "DIR_ONLY"; 1 -> "PROJECT_CONFIG"; else -> "FULL_EXPORT" }
                    onUpdateSettings { s -> s.copy(exportMode = m) }
                }
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = when (settings.exportMode) {
                    "DIR_ONLY" -> "仅生成完整的文件目录树，不包含任何代码内容。"
                    "PROJECT_CONFIG" -> "仅提取 project.godot 和 .cfg 等核心配置文件。"
                    else -> "生成完整目录树及所有纯文本代码内容。"
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 6.dp)
            )

            Spacer(modifier = Modifier.height(18.dp))
            ExpressiveCard(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("全局占用显示", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                        Text(
                            "关闭时，仅软排除和超限的文件显示大小",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = settings.showGlobalFileSize,
                        onCheckedChange = { onUpdateSettings { s -> s.copy(showGlobalFileSize = it) } }
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
            ExpressiveCard(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("流式写入", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                        Text(
                            "边生成边写文件，大项目更省内存、速度更稳",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = settings.streamOutput,
                        onCheckedChange = { onUpdateSettings { s -> s.copy(streamOutput = it) } }
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
            ExpressiveCard(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("零拷贝写入", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                            Spacer(modifier = Modifier.width(8.dp))
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.tertiaryContainer
                            ) {
                                Text(
                                    "实验性",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Text(
                            "流式模式下直接写原始字节，跳过解码/编码；实验性功能，遇到异常可关闭",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = settings.zeroCopy,
                        enabled = settings.streamOutput,
                        onCheckedChange = { onUpdateSettings { s -> s.copy(zeroCopy = it) } }
                    )
                }
            }

            if (settings.exportMode == "FULL_EXPORT") {
                Spacer(modifier = Modifier.height(14.dp))
                ExpressiveCard(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    onClick = { showPromptDialog = true }
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("AI 创作提示词模式", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                            Text(
                                text = promptModeText,
                                color = promptModeColor,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))
                ExpressiveCard(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("文件大小上限", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                            Text(
                                "仅对文件夹项目扫描生效，多文件合并不受限",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        var sizeText by remember { mutableStateOf(settings.maxFileSizeKb.toString()) }
                        LaunchedEffect(settings.maxFileSizeKb) {
                            sizeText = settings.maxFileSizeKb.toString()
                        }
                        OutlinedTextField(
                            value = sizeText,
                            onValueChange = { sizeText = it },
                            modifier = Modifier
                                .width(104.dp)
                                .onFocusChanged { focusState ->
                                    if (!focusState.isFocused) {
                                        val newVal = sizeText.toIntOrNull()
                                        if (newVal != null && newVal > 0) {
                                            onUpdateSettings { s -> s.copy(maxFileSizeKb = newVal) }
                                        } else {
                                            sizeText = settings.maxFileSizeKb.toString()
                                        }
                                    }
                                },
                            shape = RoundedCornerShape(16.dp),
                            singleLine = true,
                            suffix = { Text("KB", fontSize = 12.sp) }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            SectionHeader("排除规则", Icons.Default.FilterAlt)
            ExpressiveCard(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showDefaultRulesDialog = true }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "系统默认规则",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                Spacer(modifier = Modifier.height(16.dp))

                Text("软排除 · 保留路径，省略内容", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                if (settings.softExcludeRules.isEmpty()) {
                    Text(
                        "暂无自定义软排除",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                } else {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        settings.softExcludeRules.forEach { ext ->
                            InputChip(
                                selected = false,
                                onClick = { ruleToDelete = Pair(ext, false); showDeleteConfirmDialog = true },
                                shape = CircleShape,
                                label = { Text(ext, fontSize = 12.sp) },
                                colors = InputChipDefaults.inputChipColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                    labelColor = MaterialTheme.colorScheme.onPrimaryContainer
                                ),
                                trailingIcon = {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "删除",
                                        modifier = Modifier.size(14.dp),
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                Text("重排除 · 彻底隐藏", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error)
                if (settings.hardExcludeRules.isEmpty()) {
                    Text(
                        "暂无自定义重排除",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                } else {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        settings.hardExcludeRules.forEach { ext ->
                            InputChip(
                                selected = false,
                                onClick = { ruleToDelete = Pair(ext, true); showDeleteConfirmDialog = true },
                                shape = CircleShape,
                                label = { Text(ext, fontSize = 12.sp) },
                                colors = InputChipDefaults.inputChipColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer,
                                    labelColor = MaterialTheme.colorScheme.onErrorContainer
                                ),
                                trailingIcon = {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "删除",
                                        modifier = Modifier.size(14.dp),
                                        tint = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                OutlinedButton(
                    onClick = { showAddRuleDialog = true },
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = CircleShape
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("添加新的排除规则", style = MaterialTheme.typography.labelLarge)
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                SectionHeader("关于", Icons.Default.Info)
                ExpressiveCard(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    onClick = {
                        val intent = android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse(AnnouncementUtils.REPO_URL)
                        )
                        context.startActivity(intent)
                    }
                ) {
                    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        BrandMark(size = 64, corner = 22, fontSize = 22)
                        Spacer(modifier = Modifier.height(14.dp))
                        Text("Godot 整合工具", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
                        Text("v$versionName", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.height(14.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            "by TUB-R · 点击访问 GitHub 仓库",
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(32.dp))
        }

        if (scrollState.canScrollForward) {
            SmallFloatingActionButton(
                onClick = { scope.launch { scrollState.animateScrollTo(scrollState.maxValue) } },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(bottom = 32.dp, end = 24.dp)
                    .offset(y = bounceOffset.dp),
                shape = CircleShape,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = "滑动到底部",
                    modifier = Modifier.size(26.dp)
                )
            }
        }
    }
}

private data class PromptModeOption(val id: String, val title: String, val desc: String, val color: Color)
