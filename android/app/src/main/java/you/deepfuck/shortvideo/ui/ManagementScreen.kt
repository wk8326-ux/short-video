package you.deepfuck.shortvideo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import you.deepfuck.shortvideo.AppUiState

@Composable
internal fun ManagementScreen(
    state: AppUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onScan: () -> Unit,
    onFastStart: () -> Unit,
    onShowLogs: () -> Unit,
    onDismissLogs: () -> Unit,
    onClearLogs: () -> Unit,
    onExportLogs: () -> Unit,
) {
    Column(Modifier.fillMaxSize().background(Canvas).safeDrawingPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().height(68.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回") }
            Column(Modifier.weight(1f)) {
                Text("媒体库管理", fontWeight = FontWeight.SemiBold)
                Text(
                    state.adminStatus?.scanLastSuccess?.let(::formatTimestamp) ?: "尚未完成扫描",
                    color = TextFaint,
                    style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                )
            }
            IconButton(onClick = onRefresh, enabled = !state.adminLoading) {
                Icon(Icons.Outlined.Refresh, contentDescription = "刷新状态")
            }
        }
        HorizontalDivider(color = Line)

        when {
            state.adminStatus == null && state.adminLoading -> {
                androidx.compose.foundation.layout.Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(Modifier.size(28.dp), color = TextSecondary, strokeWidth = 2.dp)
                }
            }
            state.adminStatus == null -> {
                androidx.compose.foundation.layout.Box(
                    Modifier.fillMaxSize().padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(state.adminError ?: "无法载入管理状态", color = TextSecondary)
                }
            }
            else -> {
                val status = state.adminStatus
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    item {
                        SectionTitle("媒体库", Icons.Outlined.Storage)
                        Spacer(Modifier.height(18.dp))
                        Facts(
                            listOf(
                                "总计" to status.totalItems.toString(),
                                "光鸭" to status.guangyaItems.toString(),
                                "ASMR" to status.asmrItems.toString(),
                            ),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(formatBytes(status.totalBytes), color = TextFaint)
                        Spacer(Modifier.height(22.dp))
                        Button(
                            onClick = onScan,
                            enabled = !status.scanRunning && !state.adminLoading,
                            colors = ButtonDefaults.buttonColors(containerColor = Accent),
                        ) {
                            if (status.scanRunning) {
                                CircularProgressIndicator(Modifier.size(18.dp), color = TextPrimary, strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Outlined.Refresh, contentDescription = null)
                            }
                            Spacer(Modifier.size(8.dp))
                            Text(if (status.scanRunning) "正在扫描" else "重新扫描媒体库")
                        }
                        status.scanLastError?.let {
                            Spacer(Modifier.height(12.dp))
                            Text(it, color = androidx.compose.material3.MaterialTheme.colorScheme.error)
                        }
                        SectionDivider()
                    }
                    item {
                        SectionTitle("MP4 Fast Start", Icons.Outlined.CheckCircle)
                        Spacer(Modifier.height(18.dp))
                        Facts(
                            listOf(
                                "已优化" to status.fastStartOptimized.toString(),
                                "需处理" to status.fastStartIssues.toString(),
                                "待检查" to status.fastStartPending.toString(),
                            ),
                        )
                        Spacer(Modifier.height(22.dp))
                        OutlinedButton(
                            onClick = onFastStart,
                            enabled = !status.fastStartRunning && !state.adminLoading,
                        ) {
                            if (status.fastStartRunning) {
                                CircularProgressIndicator(Modifier.size(18.dp), color = TextSecondary, strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Outlined.WarningAmber, contentDescription = null)
                            }
                            Spacer(Modifier.size(8.dp))
                            Text(if (status.fastStartRunning) "正在检查" else "检查 Fast Start")
                        }
                        SectionDivider()
                    }
                    item {
                        SectionTitle("运行日志", Icons.Outlined.BugReport)
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "记录最近的闪退、播放错误和关键页面切换，仅保存在本机。",
                            color = TextFaint,
                            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(16.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(onClick = onShowLogs) {
                                Icon(Icons.Outlined.BugReport, contentDescription = null)
                                Spacer(Modifier.size(8.dp))
                                Text("查看日志")
                            }
                            TextButton(onClick = onExportLogs) {
                                Icon(Icons.Outlined.IosShare, contentDescription = null)
                                Spacer(Modifier.size(6.dp))
                                Text("导出")
                            }
                        }
                        SectionDivider()
                    }
                    state.adminError?.let { error ->
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = androidx.compose.material3.MaterialTheme.colorScheme.error)
                                Text(error, color = androidx.compose.material3.MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
    }
    if (state.showRuntimeLogs) {
        RuntimeLogDialog(
            text = state.runtimeLogText,
            onDismiss = onDismissLogs,
            onClear = onClearLogs,
            onExport = onExportLogs,
        )
    }
}

@Composable
private fun RuntimeLogDialog(
    text: String,
    onDismiss: () -> Unit,
    onClear: () -> Unit,
    onExport: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("运行日志", fontWeight = FontWeight.SemiBold) },
        text = {
            Column {
                SelectionContainer {
                    Text(
                        text.ifBlank { "暂无运行日志" },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(360.dp)
                            .verticalScroll(rememberScrollState()),
                        color = TextSecondary,
                        fontFamily = FontFamily.Monospace,
                        style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                    )
                }
                TextButton(onClick = onClear) {
                    Icon(Icons.Outlined.DeleteOutline, contentDescription = null)
                    Spacer(Modifier.size(6.dp))
                    Text("清空日志")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onExport) {
                Icon(Icons.Outlined.IosShare, contentDescription = null)
                Spacer(Modifier.size(6.dp))
                Text("导出")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        containerColor = Raised,
    )
}

@Composable
private fun SectionTitle(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        Icon(icon, contentDescription = null, tint = TextFaint)
        Text(label, fontWeight = FontWeight.SemiBold, style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun Facts(values: List<Pair<String, String>>) {
    Row(Modifier.fillMaxWidth()) {
        values.forEach { (label, value) ->
            Column(Modifier.weight(1f)) {
                Text(label, color = TextFaint, style = androidx.compose.material3.MaterialTheme.typography.labelSmall)
                Spacer(Modifier.height(5.dp))
                Text(value, fontWeight = FontWeight.SemiBold, style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun SectionDivider() {
    HorizontalDivider(modifier = Modifier.padding(vertical = 28.dp), color = Line)
}

private fun formatTimestamp(seconds: Long): String = runCatching {
    FORMATTER.format(Instant.ofEpochSecond(seconds).atZone(ZoneId.systemDefault()))
}.getOrDefault("扫描时间未知")

private val FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
