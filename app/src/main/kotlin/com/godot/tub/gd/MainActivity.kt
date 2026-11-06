package com.godot.tub.gd

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import com.godot.tub.gd.ui.screens.ExpressiveLoadingOverlay
import com.godot.tub.gd.ui.screens.ExpressiveTabBar
import com.godot.tub.gd.ui.screens.HomeScreen
import com.godot.tub.gd.ui.screens.MultiFileScreen
import com.godot.tub.gd.ui.screens.ResultScreen
import com.godot.tub.gd.ui.screens.SettingsScreen
import com.godot.tub.gd.ui.screens.TabItem
import com.godot.tub.gd.ui.theme.AppTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
            }
        } else if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE), 100)
            }
        }

        val viewModel = ViewModelProvider(this)[MainViewModel::class.java]
        viewModel.init(applicationContext)

        setContent {
            val context = LocalContext.current
            LaunchedEffect(Unit) {
                viewModel.errorMessage.collect { msg ->
                    android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
                }
            }
            val settings by viewModel.settings.collectAsState()
            val isSystemDark = isSystemInDarkTheme()
            val darkTheme = when (settings.themeMode) {
                1 -> false
                2 -> true
                else -> isSystemDark
            }

            var showVersionWarning by remember { mutableStateOf(false) }
            val result by viewModel.result.collectAsState()

            LaunchedEffect(result) {
                if (result != null && result?.godotVersion == "Unknown") {
                    showVersionWarning = true
                }
            }

            AppTheme(darkTheme = darkTheme) {
                Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    AppNavigation(viewModel)

                    val isLoading by viewModel.isLoading.collectAsState()
                    if (isLoading) {
                        ExpressiveLoadingOverlay()
                    }

                    if (showVersionWarning) {
                        AlertDialog(
                            onDismissRequest = { showVersionWarning = false },
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
                            title = { Text("未识别到 Godot 版本", style = MaterialTheme.typography.headlineSmall) },
                            text = {
                                Text(
                                    "没有在 project.godot 中找到 config/features 信息。提示词将按通用规则注入。",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            confirmButton = {
                                TextButton(onClick = { showVersionWarning = false }) { Text("继续") }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AppNavigation(viewModel: MainViewModel) {
    val screen by viewModel.currentScreen.collectAsState()
    val result by viewModel.result.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val historyList by viewModel.historyList.collectAsState()
    val context = LocalContext.current

    BackHandler(enabled = screen != "home") {
        viewModel.currentScreen.value = "home"
    }

    val tabItems = remember {
        listOf(
            TabItem("扫描", Icons.Default.Folder),
            TabItem("合并", Icons.Default.Description),
            TabItem("设置", Icons.Default.Settings)
        )
    }
    val tabIndex = when (screen) {
        "multi_files" -> 1
        "settings" -> 2
        else -> 0
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            AnimatedContent(
                targetState = screen,
                modifier = Modifier.weight(1f),
                transitionSpec = {
                    (
                        fadeIn(animationSpec = tween(240)) +
                            scaleIn(animationSpec = tween(240), initialScale = 0.97f)
                        ) togetherWith (
                        fadeOut(animationSpec = tween(120)) +
                            scaleOut(animationSpec = tween(120), targetScale = 1.02f)
                        )
                },
                label = "screen"
            ) { target ->
                when (target) {
                    "home" -> HomeScreen(
                        outputPath = settings.outputPath,
                        format = settings.format,
                        historyList = historyList,
                        onFolderSelected = { uri -> viewModel.generateFromTreeUri(context, uri) },
                        onNavigateToMultiFile = { viewModel.currentScreen.value = "multi_files" },
                        onHistoryItemClick = { item -> viewModel.viewHistoryItem(item) },
                        onClearHistory = { viewModel.clearHistory() }
                    )
                    "multi_files" -> MultiFileScreen(
                        pendingFiles = viewModel.pendingMultiFiles.collectAsState().value,
                        settings = settings,
                        onAddFile = { viewModel.addPendingMultiFile(context, it) },
                        onRemoveFile = { viewModel.removePendingMultiFile(it) },
                        onClearAll = { viewModel.clearPendingMultiFiles() },
                        onStartMerge = { viewModel.startMultiFileMerge(context) }
                    )
                    "settings" -> SettingsScreen(
                        settings = settings,
                        onUpdateSettings = { viewModel.updateSettings(it) }
                    )
                    "result" -> Unit
                }
            }

            AnimatedVisibility(
                visible = screen != "result",
                enter = slideInVertically(animationSpec = tween(240)) { it } + fadeIn(tween(200)),
                exit = slideOutVertically(animationSpec = tween(180)) { it } + fadeOut(tween(120))
            ) {
                ExpressiveTabBar(
                    selectedIndex = tabIndex,
                    items = tabItems,
                    onSelect = { index ->
                        viewModel.currentScreen.value = when (index) {
                            1 -> "multi_files"
                            2 -> "settings"
                            else -> "home"
                        }
                    }
                )
            }
        }

        AnimatedVisibility(
            visible = screen == "result" && result != null,
            enter = slideInVertically(animationSpec = tween(300)) { it } + fadeIn(tween(260)),
            exit = slideOutVertically(animationSpec = tween(220)) { it } + fadeOut(tween(160))
        ) {
            if (result != null) {
                ResultScreen(
                    result = result!!,
                    onBack = { viewModel.currentScreen.value = "home" }
                )
            }
        }
    }
}
