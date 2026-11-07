package com.wihee.code.pr.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wihee.code.pr.utils.AnnouncementUtils

/**
 * 公告弹窗：一次展示所有未读公告（或手动打开时最新一条）。
 * 「前往更新」跳到公告自带的 url，缺省用仓库 releases 页。
 */
@Composable
fun AnnouncementDialog(
    announcements: List<AnnouncementUtils.Announcement>,
    currentVersion: String,
    onDismiss: () -> Unit
) {
    if (announcements.isEmpty()) return
    val context = LocalContext.current

    val updateTarget = announcements.firstOrNull { it.isUpdate && it.url.isNotBlank() }?.url
        ?: AnnouncementUtils.RELEASES_URL
    val hasUpdate = announcements.any { it.isUpdate }
    val updateButton: (@Composable () -> Unit)? = if (hasUpdate) {
        {
            TextButton(onClick = {
                openUrl(context, updateTarget)
                onDismiss()
            }) {
                Icon(
                    Icons.Default.SystemUpdate,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text("前往更新")
            }
        }
    } else {
        null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(32.dp),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .background(
                            MaterialTheme.colorScheme.primaryContainer,
                            RoundedCornerShape(14.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Campaign,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("公告", style = MaterialTheme.typography.headlineSmall)
                    if (currentVersion.isNotBlank()) {
                        Text(
                            "当前版本 v$currentVersion",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        text = {
            LazyColumn(
                modifier = Modifier.heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(announcements, key = { it.id }) { item ->
                    AnnouncementItem(item)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("我知道了", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = updateButton
    )
}

@Composable
private fun AnnouncementItem(item: AnnouncementUtils.Announcement) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                item.title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (item.isUpdate) {
                Spacer(Modifier.width(8.dp))
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        "更新",
                        style = MaterialTheme.typography.labelMedium,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }
        }

        val metaLine = listOfNotNull(
            item.date.takeIf { it.isNotBlank() },
            item.version.takeIf { it.isNotBlank() }?.let { "最新 v$it" }
        ).joinToString(" · ")
        if (metaLine.isNotEmpty()) {
            Spacer(Modifier.height(3.dp))
            Text(
                metaLine,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (item.body.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                item.body,
                style = MaterialTheme.typography.bodySmall,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun openUrl(context: Context, url: String) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
