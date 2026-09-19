package you.deepfuck.shortvideo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.LiveTv
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import you.deepfuck.shortvideo.AppUiState
import you.deepfuck.shortvideo.data.AdminStatus
import you.deepfuck.shortvideo.data.LibrarySection
import you.deepfuck.shortvideo.data.MediaLibrarySource
import you.deepfuck.shortvideo.data.MediaSourceDraft

@Composable
internal fun ManagementScreen(
    state: AppUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onScanSource: (String) -> Unit,
    onSaveSource: (String?, MediaSourceDraft) -> Unit,
    onDeleteSource: (String) -> Unit,
    onFastStart: () -> Unit,
    onShowLogs: () -> Unit,
    onDismissLogs: () -> Unit,
    onClearLogs: () -> Unit,
    onExportLogs: () -> Unit,
) {
    var editingSource by remember { mutableStateOf<MediaLibrarySource?>(null) }
    var sourceDialogOpen by remember { mutableStateOf(false) }
    var deletingSource by remember { mutableStateOf<MediaLibrarySource?>(null) }
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
                                "短剧" to status.dramaItems.toString(),
                            ),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(formatBytes(status.totalBytes), color = TextFaint)
                        Spacer(Modifier.height(18.dp))
                        status.sources.forEachIndexed { index, source ->
                            if (index > 0) {
                                Spacer(Modifier.height(8.dp))
                            }
                            MediaSourceRow(
                                source = source,
                                starting = "scan-${source.id}" in state.adminActions,
                                deleting = "delete-source-${source.id}" in state.adminActions,
                                onScan = onScanSource,
                                onEdit = {
                                    editingSource = source
                                    sourceDialogOpen = true
                                },
                                onDelete = { deletingSource = source },
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
        // Addresses the user already typed are offered again so a new library
        // only needs its folder, not the whole URL.
        val history = state.adminStatus?.sources.orEmpty()
            .filter { it.id != editingSource?.id }
            .map { it.baseUrl.trim() to it.rootPath.trim() }
            .filter { it.first.isNotBlank() }
        MediaSourceDialog(
            source = editingSource,
            history = history,
            saving = "source-${editingSource?.id ?: "new"}" in state.adminActions,
            onDismiss = { sourceDialogOpen = false },
            onSave = { draft ->
                onSaveSource(editingSource?.id, draft)
                sourceDialogOpen = false
            },
        )
    }
    deletingSource?.let { source ->
        AlertDialog(
            onDismissRequest = { deletingSource = null },
            title = { Text("删除媒体源？") },
            text = {
                Text(
                    "将删除“${source.name}”及本地索引的 ${source.videos} 个媒体。网盘原始文件不会被删除。",
                    color = TextSecondary,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteSource(source.id)
                        deletingSource = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = androidx.compose.material3.MaterialTheme.colorScheme.error),
                ) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deletingSource = null }) { Text("取消") } },
            containerColor = Raised,
        )
    }
}

@Composable
private fun MediaSourceRow(
    source: MediaLibrarySource,
    starting: Boolean,
    deleting: Boolean,
    onScan: (String) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val busy = source.scan.running || starting
    // Two rows sharing two edges: the name and the media count both start on the
    // card's content edge, the section tag and the action cluster both end on it.
    // The wall of metrics that used to live here pushed a dozen libraries past a
    // screen; a left-hugging button row under a right-aligned tag just looked
    // broken, so every element now sits on one of those two lines.
    Column(
        Modifier
            .fillMaxWidth()
            .glassSurface(fill = Raised, line = Line)
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 2.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                source.name,
                modifier = Modifier.weight(1f),
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold,
                style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                source.section.label,
                color = AccentSoft,
                style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                maxLines = 1,
            )
        }
        source.scan.lastError?.let { message ->
            Spacer(Modifier.height(2.dp))
            Text(
                message,
                color = androidx.compose.material3.MaterialTheme.colorScheme.error,
                style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The count is the number the user came for; the scan age rides
            // along in the space that used to be empty, so the card grows no
            // taller than it was.
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    source.videos.toString(),
                    color = TextSecondary,
                    style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                )
                val hint = sourceScanHint(source)
                if (hint != null) {
                    Text(
                        "  ·  $hint",
                        color = TextFaint,
                        style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            // Icon-only actions keep the row compact; the label doubles as the
            // accessibility text via GlassIconButton.
            Row(
                // A 48dp icon button caps its glyph with 12dp of padding, so the
                // cluster is nudged out by exactly that amount: the last glyph
                // then lands on the same right edge as the section tag above,
                // while the target itself stays inside the card.
                modifier = Modifier.offset(x = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                GlassIconButton(
                    label = "编辑媒体源",
                    onClick = onEdit,
                    enabled = !busy && !deleting,
                    tint = TextSecondary,
                ) {
                    Icon(Icons.Outlined.Edit, contentDescription = null)
                }
                GlassIconButton(
                    label = if (busy) "正在扫描" else "扫描媒体源",
                    onClick = { onScan(source.id) },
                    enabled = source.enabled && !busy,
                    tint = if (busy) TextSecondary else Color.White,
                ) {
                    if (busy) {
                        CircularProgressIndicator(Modifier.size(18.dp), color = TextSecondary, strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Outlined.Refresh, contentDescription = null)
                    }
                }
                GlassIconButton(
                    label = "删除媒体源",
                    onClick = onDelete,
                    enabled = !busy && !deleting,
                    tint = androidx.compose.material3.MaterialTheme.colorScheme.error,
                ) {
                    Icon(Icons.Outlined.DeleteOutline, contentDescription = null)
                }
            }
        }
    }
}

// The question this list has to answer at a glance is "which library is stale",
// so the scan age is shown relative instead of as an absolute timestamp.
private fun sourceScanHint(source: MediaLibrarySource): String? {
    if (!source.enabled) return "已停用"
    val lastSuccess = source.scan.lastSuccess
    if (lastSuccess == null || lastSuccess <= 0L) {
        return if (source.videos > 0) null else "未扫描"
    }
    val stamp = lastSuccess * 1000L
    val elapsed = System.currentTimeMillis() - stamp
    return when {
        elapsed < 60_000L -> "刚刚"
        elapsed < 3_600_000L -> "${elapsed / 60_000L} 分钟前"
        elapsed < 86_400_000L -> "${elapsed / 3_600_000L} 小时前"
        elapsed < 30L * 86_400_000L -> "${elapsed / 86_400_000L} 天前"
        else -> SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(stamp))
    }
}

@Composable
private fun SuggestionRow(
    label: String,
    options: List<String>,
    onPick: (String) -> Unit,
) {
    if (options.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            label,
            color = TextFaint,
            style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
        )
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEach { option ->
                Surface(
                    onClick = { onPick(option) },
                    color = RaisedStrong,
                    contentColor = TextSecondary,
                    shape = ControlShape,
                    modifier = Modifier.heightIn(min = 40.dp),
                ) {
                    Box(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            option,
                            style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MediaSourceDialog(
    source: MediaLibrarySource?,
    history: List<Pair<String, String>>,
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
    val knownAddresses = history.map { it.first }.distinct().filter { it != baseUrl.trim() }
    val knownRoots = history
        .filter { it.first == baseUrl.trim() }
        .map { it.second }
        .distinct()
        .filter { it.isNotBlank() && it != rootPath.trim() }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Raised,
        dragHandle = { BottomSheetDefaults.DragHandle(color = Line) },
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (source == null) Icons.Outlined.Add else Icons.Outlined.Edit,
                    contentDescription = null,
                    tint = TextSecondary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.size(10.dp))
                Text(
                    if (source == null) "添加媒体源" else "编辑媒体源",
                    modifier = Modifier.weight(1f),
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary,
                    style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                )
                GlassIconButton(label = "关闭", onClick = onDismiss, tint = TextSecondary) {
                    Icon(Icons.Outlined.Close, contentDescription = null)
                }
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 500.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                FieldGroup("归属板块", Icons.Outlined.Category) {
                    SectionTiles(section) { picked ->
                        section = picked
                        scanMode = if (picked.usesTreeScan) "tree" else "authors_recursive"
                    }
                }
                FieldGroup("服务", Icons.Outlined.Cloud) {
                    ChoiceRow(
                        label = "服务类型",
                        choices = listOf("alist" to "AList", "openlist" to "OpenList"),
                        selected = provider,
                        onSelected = { provider = it },
                    )
                    if (section == LibrarySection.ASMR) {
                        ChoiceRow(
                            label = "作者目录结构",
                            choices = listOf("authors" to "一级作者目录", "authors_recursive" to "嵌套作者目录"),
                            selected = scanMode,
                            onSelected = { scanMode = it },
                        )
                    }
                }
                FieldGroup("基础信息", Icons.Outlined.Badge) {
                    OutlinedTextField(
                        name,
                        { name = it },
                        Modifier.fillMaxWidth(),
                        label = { Text("名称") },
                        leadingIcon = { Icon(Icons.Outlined.Badge, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        baseUrl,
                        { baseUrl = it },
                        Modifier.fillMaxWidth(),
                        label = { Text("服务地址") },
                        leadingIcon = { Icon(Icons.Outlined.Link, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        singleLine = true,
                    )
                    SuggestionRow(
                        label = "用过",
                        options = knownAddresses,
                        onPick = { baseUrl = it },
                    )
                    OutlinedTextField(
                        rootPath,
                        { rootPath = it },
                        Modifier.fillMaxWidth(),
                        label = { Text("根目录") },
                        leadingIcon = { Icon(Icons.Outlined.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        singleLine = true,
                    )
                    SuggestionRow(
                        label = "该地址用过",
                        options = knownRoots,
                        onPick = { rootPath = it },
                    )
                }
                FieldGroup("鉴权", Icons.Outlined.Key) {
                    ToggleRow("匿名访问", anonymous) { anonymous = it }
                    if (!anonymous) {
                        OutlinedTextField(
                            username,
                            { username = it },
                            Modifier.fillMaxWidth(),
                            label = { Text(if (source?.usernameConfigured == true) "用户名（已配置）" else "用户名") },
                            leadingIcon = { Icon(Icons.Outlined.Badge, contentDescription = null, modifier = Modifier.size(18.dp)) },
                            singleLine = true,
                        )
                        OutlinedTextField(
                            password,
                            { password = it },
                            Modifier.fillMaxWidth(),
                            label = { Text("密码（留空保持不变）") },
                            leadingIcon = { Icon(Icons.Outlined.Key, contentDescription = null, modifier = Modifier.size(18.dp)) },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                        )
                        OutlinedTextField(
                            token,
                            { token = it },
                            Modifier.fillMaxWidth(),
                            label = { Text(if (source?.tokenConfigured == true) "令牌（已配置，留空保持）" else "令牌（可选）") },
                            leadingIcon = { Icon(Icons.Outlined.Key, contentDescription = null, modifier = Modifier.size(18.dp)) },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                        )
                    }
                    ToggleRow("启用媒体源", enabled) { enabled = it }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    shape = ControlShape,
                ) { Text("取消") }
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
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    shape = ControlShape,
                ) { Text(if (saving) "保存中" else "保存") }
            }
        }
    }
}

@Composable
private fun FieldGroup(
    title: String,
    icon: ImageVector,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Icon(icon, contentDescription = null, tint = TextFaint, modifier = Modifier.size(15.dp))
            Text(
                title,
                color = TextFaint,
                style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

@Composable
private fun SectionTiles(selected: LibrarySection, onSelected: (LibrarySection) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LibrarySection.entries.forEach { entry ->
            val active = entry == selected
            Column(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 62.dp)
                    .clip(ControlShape)
                    .background(if (active) Accent.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.03f))
                    .border(1.dp, if (active) AccentSoft else Line, ControlShape)
                    .clickable { onSelected(entry) }
                    .padding(vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    entry.tileIcon,
                    contentDescription = null,
                    tint = if (active) AccentSoft else TextSecondary,
                    modifier = Modifier.size(19.dp),
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    entry.tileLabel,
                    color = if (active) TextPrimary else TextFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

private val LibrarySection.tileIcon: ImageVector
    get() = when (this) {
        LibrarySection.FEED -> Icons.Outlined.PlayCircle
        LibrarySection.ASMR -> Icons.Outlined.Headphones
        LibrarySection.MOVIE -> Icons.Outlined.Movie
        LibrarySection.DRAMA -> Icons.Outlined.LiveTv
    }

private val LibrarySection.tileLabel: String
    get() = when (this) {
        LibrarySection.FEED -> "视频"
        LibrarySection.ASMR -> "ASMR"
        LibrarySection.MOVIE -> "电影"
        LibrarySection.DRAMA -> "短剧"
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
                    ) {
                        Text(
                            text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                        )
                    }
                } else {
                    OutlinedButton(
                        onClick = { onSelected(value) },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        shape = ControlShape,
                    ) {
                        Text(
                            text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                        )
                    }
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
