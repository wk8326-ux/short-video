package you.deepfuck.shortvideo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import you.deepfuck.shortvideo.data.AppUpdatePhase
import you.deepfuck.shortvideo.data.AppUpdateUiState

@Composable
internal fun AppUpdateDialog(
    state: AppUpdateUiState,
    onDismiss: () -> Unit,
    onCheck: () -> Unit,
    onDownload: () -> Unit,
    onInstall: (String) -> Unit,
) {
    if (state.phase == AppUpdatePhase.IDLE) return
    val info = state.info
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Raised,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(Icons.Outlined.SystemUpdate, contentDescription = null, tint = TextSecondary)
                Text(updateTitle(state.phase), fontWeight = FontWeight.SemiBold)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    VersionValue("当前版本", state.currentVersionName.ifBlank { "未知" })
                    info?.let { VersionValue("最新版本", it.versionName, Alignment.End) }
                }
                HorizontalDivider(color = Line)
                when (state.phase) {
                    AppUpdatePhase.CHECKING -> BusyMessage("正在连接更新服务")
                    AppUpdatePhase.AVAILABLE -> {
                        Text(
                            info?.notes?.ifBlank { "包含功能更新和稳定性改进" }.orEmpty(),
                            color = TextSecondary,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            "安装包 ${info?.let { formatBytes(it.size) }.orEmpty()}。首次更新需要允许安装未知来源应用。",
                            color = TextFaint,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    AppUpdatePhase.DOWNLOADING -> {
                        LinearProgressIndicator(
                            progress = { state.progress },
                            modifier = Modifier.fillMaxWidth().height(2.dp),
                            color = Accent,
                            trackColor = Color.White.copy(alpha = 0.12f),
                        )
                        Text(
                            "${formatBytes(state.downloadedBytes)} / ${info?.let { formatBytes(it.size) }.orEmpty()}  ·  ${(state.progress * 100).toInt()}%",
                            color = TextSecondary,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    AppUpdatePhase.READY -> Text(
                        "安装包已通过 SHA-256 校验。接下来由 Android 系统完成安装。",
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    AppUpdatePhase.LATEST -> Text(
                        "当前安装的版本已经是最新版本。",
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    AppUpdatePhase.ERROR -> Text(
                        state.message ?: "更新未完成，请重试。",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    AppUpdatePhase.IDLE -> Unit
                }
            }
        },
        confirmButton = {
            when (state.phase) {
                AppUpdatePhase.AVAILABLE -> PrimaryAction("下载并安装", onDownload)
                AppUpdatePhase.READY -> PrimaryAction("打开安装器") {
                    state.downloadedApkPath?.let(onInstall)
                }
                AppUpdatePhase.LATEST -> PrimaryAction("完成", onDismiss)
                AppUpdatePhase.ERROR -> PrimaryAction(
                    if (state.downloadedApkPath != null) "重试安装" else if (info != null) "重新下载" else "重新检查",
                ) {
                    when {
                        state.downloadedApkPath != null -> onInstall(state.downloadedApkPath)
                        info != null -> onDownload()
                        else -> onCheck()
                    }
                }
                AppUpdatePhase.CHECKING,
                AppUpdatePhase.DOWNLOADING,
                AppUpdatePhase.IDLE,
                -> Unit
            }
        },
        dismissButton = {
            if (state.phase != AppUpdatePhase.LATEST) {
                TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 44.dp)) {
                    Text(if (state.phase in listOf(AppUpdatePhase.CHECKING, AppUpdatePhase.DOWNLOADING)) "取消" else "稍后")
                }
            }
        },
    )
}

@Composable
private fun VersionValue(label: String, value: String, alignment: Alignment.Horizontal = Alignment.Start) {
    Column(horizontalAlignment = alignment) {
        Text(label, color = TextFaint, style = MaterialTheme.typography.labelSmall)
        Text(value, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun BusyMessage(label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        CircularProgressIndicator(Modifier.size(18.dp), color = TextSecondary, strokeWidth = 2.dp)
        Text(label, color = TextSecondary)
    }
}

@Composable
private fun PrimaryAction(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.heightIn(min = 44.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Accent),
    ) {
        Text(label)
    }
}

private fun updateTitle(phase: AppUpdatePhase): String = when (phase) {
    AppUpdatePhase.CHECKING -> "检查更新"
    AppUpdatePhase.AVAILABLE -> "发现新版本"
    AppUpdatePhase.DOWNLOADING -> "正在下载"
    AppUpdatePhase.READY -> "可以安装"
    AppUpdatePhase.LATEST -> "已是最新版本"
    AppUpdatePhase.ERROR -> "更新未完成"
    AppUpdatePhase.IDLE -> "检查更新"
}
