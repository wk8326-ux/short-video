package you.deepfuck.shortvideo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.ImageSearch
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import you.deepfuck.shortvideo.AppUiState
import you.deepfuck.shortvideo.data.LibrarySection
import you.deepfuck.shortvideo.data.MediaLibrarySource
import you.deepfuck.shortvideo.data.MediaSourceDraft
import you.deepfuck.shortvideo.data.MovieMetadataStatus

@Composable
internal fun ManagementScreen(
    state: AppUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onScanSource: (String) -> Unit,
    onMovieMetadata: (String) -> Unit,
    onSaveSource: (String?, MediaSourceDraft) -> Unit,
    onFastStart: () -> Unit,
    onShowLogs: () -> Unit,
    onDismissLogs: () -> Unit,
    onClearLogs: () -> Unit,
    onExportLogs: () -> Unit,
) {
    var editingSource by remember { mutableStateOf<MediaLibrarySource?>(null) }
    var sourceDialogOpen by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(Canvas).safeDrawingPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().height(68.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlassIconButton(
                label = "返回",
                onClick = onBack,
                tint = TextPrimary,
            ) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null) }
            Column(Modifier.weight(1f)) {
                Text(
                    "媒体库管理",
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary,
                    style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                )
                Text(
                    "各媒体源独立维护，只在手动操作时扫描",
                    color = TextFaint,
                    style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                )
            }
            GlassIconButton(
                label = "添加媒体源",
                onClick = {
                    editingSource = null
                    sourceDialogOpen = true
                },
                tint = TextPrimary,
            ) {
                Icon(Icons.Outlined.Add, contentDescription = null)
            }
            GlassIconButton(
                label = "刷新状态",
                onClick = onRefresh,
                enabled = !state.adminLoading,
                tint = TextPrimary,
            ) {
                Icon(Icons.Outlined.Refresh, contentDescription = null)
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
                                "电影" to status.movieItems.toString(),
                            ),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(formatBytes(status.totalBytes), color = TextFaint)
                        Spacer(Modifier.height(18.dp))
                        status.sources.forEachIndexed { index, source ->
                            if (index > 0) {
                                Spacer(Modifier.height(12.dp))
                            }
                            MediaSourceRow(
                                source = source,
                                starting = "scan-${source.id}" in state.adminActions,
                                metadataStatus = status.movieMetadata,
                                metadataStarting = "metadata-${source.id}" in state.adminActions,
                                onScan = onScanSource,
                                onMovieMetadata = onMovieMetadata,
                                onEdit = {
                                    editingSource = source
                                    sourceDialogOpen = true
                                },
                            )
                        }
                        if (status.sources.isEmpty()) {
                            Text("尚未添加媒体源", color = TextFaint)
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
                            enabled = !status.fastStartRunning && "fast-start" !in state.adminActions,
                            modifier = Modifier.heightIn(min = 48.dp),
                            shape = ControlShape,
                        ) {
                            if (status.fastStartRunning) {
                                CircularProgressIndicator(Modifier.size(18.dp), color = TextSecondary, strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Outlined.WarningAmber, contentDescription = null)
                            }
                            Spacer(Modifier.size(8.dp))
                            Text(
                                if (status.fastStartRunning || "fast-start" in state.adminActions) {
                                    "正在检查"
                                } else {
                                    "检查 Fast Start"
                                },
                            )
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
    if (sourceDialogOpen) {
        MediaSourceDialog(
            source = editingSource,
            saving = "source-${editingSource?.id ?: "new"}" in state.adminActions,
            onDismiss = { sourceDialogOpen = false },
            onSave = { draft ->
                onSaveSource(editingSource?.id, draft)
                sourceDialogOpen = false
            },
        )
    }
}

@Composable
private fun MediaSourceRow(
    source: MediaLibrarySource,
    starting: Boolean,
    metadataStatus: MovieMetadataStatus,
    metadataStarting: Boolean,
    onScan: (String) -> Unit,
    onMovieMetadata: (String) -> Unit,
    onEdit: () -> Unit,
) {
    val busy = source.scan.running || starting
    val metadataBusy = metadataStarting || (
        metadataStatus.running && metadataStatus.sourceId == source.id
    )
    val otherMetadataBusy = metadataStatus.running && metadataStatus.sourceId != source.id
    val statusLabel = when {
        busy -> "扫描中"
        !source.enabled -> "已停用"
        source.scan.lastError != null -> "需检查"
        source.scan.lastSuccess != null -> "已索引"
        else -> "待扫描"
    }
    Column(
        Modifier
            .fillMaxWidth()
            .glassSurface(fill = Raised, line = Line)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(
                    source.name,
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    "${source.baseUrl}${source.rootPath}",
                    color = TextFaint,
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box(
                modifier = Modifier
                    .clip(ControlShape)
                    .background(Accent.copy(alpha = 0.12f))
                    .padding(horizontal = 8.dp, vertical = 5.dp),
            ) {
                Text(
                    source.section.label,
                    color = AccentSoft,
                    style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                )
            }
        }
        HorizontalDivider(Modifier.padding(top = 14.dp, bottom = 10.dp), color = Line)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SourceMetric("媒体数量", source.videos.toString(), Modifier.weight(1f))
            SourceMetric("目录", source.scan.directories.toString(), Modifier.weight(1f))
            SourceMetric("当前状态", statusLabel, Modifier.weight(1f))
        }
        source.scan.lastSuccess?.let {
            Spacer(Modifier.height(10.dp))
            Text(
                "上次扫描 ${formatTimestamp(it)}",
                color = TextFaint,
                style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
            )
        }
        source.scan.lastError?.let {
            Spacer(Modifier.height(8.dp))
            Text(
                it,
                color = androidx.compose.material3.MaterialTheme.colorScheme.error,
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
            )
        }
        if (source.section == LibrarySection.MOVIE) {
            val metadata = source.movieMetadata
            HorizontalDivider(Modifier.padding(top = 14.dp, bottom = 12.dp), color = Line)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SourceMetric("已匹配", (metadata?.matched ?: 0).toString(), Modifier.weight(1f))
                SourceMetric("待处理", (metadata?.pending ?: 0).toString(), Modifier.weight(1f))
                SourceMetric("需确认", (metadata?.needsReview ?: 0).toString(), Modifier.weight(1f))
            }
            if (metadataBusy) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "正在刮削 ${metadataStatus.checked} / ${metadataStatus.total}",
                    color = TextSecondary,
                    style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                )
            } else if ((metadata?.total ?: 0) == 0) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "先扫描媒体源，再刮削封面和资料。",
                    color = TextFaint,
                    style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                )
            } else {
                metadata?.lastSuccess?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "上次刮削 ${formatTimestamp(it)}",
                        color = TextFaint,
                        style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                    )
                }
                metadataStatus.lastError.takeIf { metadataStatus.sourceId == source.id }?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        it,
                        color = androidx.compose.material3.MaterialTheme.colorScheme.error,
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = onEdit,
                enabled = !busy && !metadataBusy,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Icon(Icons.Outlined.Edit, contentDescription = null)
                Spacer(Modifier.size(6.dp))
                Text("编辑")
            }
            Spacer(Modifier.weight(1f))
            OutlinedButton(
                onClick = { onScan(source.id) },
                enabled = source.enabled && !busy && !metadataBusy,
                modifier = Modifier.heightIn(min = 48.dp),
                shape = ControlShape,
            ) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(17.dp), color = TextSecondary, strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Outlined.Refresh, contentDescription = null)
                }
                Spacer(Modifier.size(7.dp))
                Text(if (busy) "扫描中" else "扫描")
            }
            if (source.section == LibrarySection.MOVIE) {
                Spacer(Modifier.size(8.dp))
                OutlinedButton(
                    onClick = { onMovieMetadata(source.id) },
                    enabled = source.enabled && !busy && !metadataBusy && !otherMetadataBusy && source.videos > 0,
                    modifier = Modifier.heightIn(min = 48.dp),
                    shape = ControlShape,
                ) {
                    if (metadataBusy) {
                        CircularProgressIndicator(Modifier.size(17.dp), color = TextSecondary, strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Outlined.ImageSearch, contentDescription = null)
                    }
                    Spacer(Modifier.size(7.dp))
                    Text(if (metadataBusy) "刮削中" else "刮削")
                }
            }
        }
    }
}

@Composable
private fun SourceMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            value,
            color = TextPrimary,
            fontWeight = FontWeight.SemiBold,
            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(3.dp))
        Text(
            label,
            color = TextFaint,
            style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun MediaSourceDialog(
    source: MediaLibrarySource?,
    saving: Boolean,
    onDismiss: () -> Unit,
    onSave: (MediaSourceDraft) -> Unit,
) {
    var name by remember(source?.id) { mutableStateOf(source?.name.orEmpty()) }
    var provider by remember(source?.id) { mutableStateOf(source?.provider ?: "alist") }
    var baseUrl by remember(source?.id) { mutableStateOf(source?.baseUrl.orEmpty()) }
    var rootPath by remember(source?.id) { mutableStateOf(source?.rootPath ?: "/") }
    var section by remember(source?.id) { mutableStateOf(source?.section ?: LibrarySection.FEED) }
    var scanMode by remember(source?.id) {
        mutableStateOf(source?.scanMode ?: if (source?.section == LibrarySection.ASMR) "authors_recursive" else "tree")
    }
    var anonymous by remember(source?.id) { mutableStateOf(source?.anonymous ?: true) }
    var username by remember(source?.id) { mutableStateOf("") }
    var password by remember(source?.id) { mutableStateOf("") }
    var token by remember(source?.id) { mutableStateOf("") }
    var enabled by remember(source?.id) { mutableStateOf(source?.enabled ?: true) }
    val valid = name.isNotBlank() && baseUrl.startsWith("http") && rootPath.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (source == null) "添加媒体源" else "编辑媒体源") },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("名称") }, singleLine = true)
                ChoiceRow(
                    label = "服务类型",
                    choices = listOf("alist" to "AList", "openlist" to "OpenList"),
                    selected = provider,
                    onSelected = { provider = it },
                )
                OutlinedTextField(baseUrl, { baseUrl = it }, Modifier.fillMaxWidth(), label = { Text("服务地址") }, singleLine = true)
                OutlinedTextField(rootPath, { rootPath = it }, Modifier.fillMaxWidth(), label = { Text("根目录") }, singleLine = true)
                ChoiceRow(
                    label = "归属板块",
                    choices = listOf(
                        LibrarySection.FEED.apiValue to "Video",
                        LibrarySection.ASMR.apiValue to "ASMR",
                        LibrarySection.MOVIE.apiValue to "Movie",
                    ),
                    selected = section.apiValue,
                    onSelected = { value ->
                        section = LibrarySection.entries.first { it.apiValue == value }
                        scanMode = if (section.usesTreeScan) "tree" else "authors_recursive"
                    },
                )
                if (section == LibrarySection.ASMR) {
                    ChoiceRow(
                        label = "作者目录结构",
                        choices = listOf("authors" to "一级作者目录", "authors_recursive" to "嵌套作者目录"),
                        selected = scanMode,
                        onSelected = { scanMode = it },
                    )
                }
                ToggleRow("匿名访问", anonymous) { anonymous = it }
                if (!anonymous) {
                    OutlinedTextField(
                        username,
                        { username = it },
                        Modifier.fillMaxWidth(),
                        label = { Text(if (source?.usernameConfigured == true) "用户名（已配置）" else "用户名") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        password,
                        { password = it },
                        Modifier.fillMaxWidth(),
                        label = { Text("密码（留空保持不变）") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        token,
                        { token = it },
                        Modifier.fillMaxWidth(),
                        label = { Text(if (source?.tokenConfigured == true) "令牌（已配置，留空保持）" else "令牌（可选）") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                    )
                }
                ToggleRow("启用媒体源", enabled) { enabled = it }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        MediaSourceDraft(
                            name = name.trim(),
                            provider = provider,
                            baseUrl = baseUrl.trim(),
                            rootPath = rootPath.trim(),
                            section = section,
                            scanMode = if (section.usesTreeScan) "tree" else scanMode,
                            anonymous = anonymous,
                            token = token,
                            username = username,
                            password = password,
                            enabled = enabled,
                        ),
                    )
                },
                enabled = valid && !saving,
            ) { Text(if (saving) "保存中" else "保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        containerColor = Raised,
    )
}

@Composable
private fun ChoiceRow(
    label: String,
    choices: List<Pair<String, String>>,
    selected: String,
    onSelected: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = TextFaint, style = androidx.compose.material3.MaterialTheme.typography.labelSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            choices.forEach { (value, text) ->
                if (value == selected) {
                    Button(
                        onClick = { onSelected(value) },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        shape = ControlShape,
                    ) { Text(text) }
                } else {
                    OutlinedButton(
                        onClick = { onSelected(value) },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        shape = ControlShape,
                    ) { Text(text) }
                }
            }
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), color = TextSecondary)
        Switch(checked = checked, onCheckedChange = onChecked)
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
        Text(
            label,
            fontWeight = FontWeight.SemiBold,
            color = TextPrimary,
            style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
        )
    }
}

@Composable
private fun Facts(values: List<Pair<String, String>>) {
    Row(Modifier.fillMaxWidth()) {
        values.forEach { (label, value) ->
            Column(Modifier.weight(1f)) {
                Text(label, color = TextFaint, style = androidx.compose.material3.MaterialTheme.typography.labelSmall)
                Spacer(Modifier.height(5.dp))
                Text(
                    value,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary,
                    style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                )
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
