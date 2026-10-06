package you.deepfuck.shortvideo.ui

import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.Business
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.LocalMovies
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.TheaterComedy
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.ui.platform.LocalContext
import you.deepfuck.shortvideo.AppUiState
import you.deepfuck.shortvideo.BuildConfig
import you.deepfuck.shortvideo.MainViewModel
import you.deepfuck.shortvideo.data.LibraryResult
import you.deepfuck.shortvideo.data.MediaSurface

internal enum class RootTab { HOME, SEARCH, FAVORITES, SETTINGS }

@Composable
internal fun RootChrome(
    state: AppUiState,
    tab: RootTab,
    onTab: (RootTab) -> Unit,
    onSurface: (MediaSurface) -> Unit,
    showNavigation: Boolean = true,
    content: @Composable () -> Unit,
) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        content()
        if (showNavigation) {
            AppNavigation(
                state = state,
                compact = false,
                onSurface = onSurface,
                onMode = {},
                onManage = {},
                onCheckUpdate = {},
                onLogout = {},
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
        if (showNavigation) Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .navigationBarsPadding(),
        ) {
            NavigationBar(
                modifier = Modifier.fillMaxWidth().height(80.dp),
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                tonalElevation = 0.dp,
                windowInsets = WindowInsets(0, 0, 0, 0),
            ) {
                val destinations = listOf(
                    RootTab.HOME to (Icons.Outlined.Home to Icons.Filled.Home),
                    RootTab.SEARCH to (Icons.Outlined.Search to Icons.Filled.Search),
                    RootTab.FAVORITES to (Icons.Outlined.FavoriteBorder to Icons.Filled.Favorite),
                    RootTab.SETTINGS to (Icons.Outlined.Settings to Icons.Filled.Settings),
                )
                destinations.forEach { (destination, icons) ->
                    val selected = destination == tab
                    NavigationBarItem(
                        selected = selected,
                        onClick = { onTab(destination) },
                        icon = {
                            Icon(
                                if (selected) icons.second else icons.first,
                                contentDescription = destination.label,
                            )
                        },
                        alwaysShowLabel = false,
                        modifier = Modifier.semantics { this.selected = selected },
                    )
                }
            }
        }
    }
}

private val RootTab.label: String
    get() = when (this) {
        RootTab.HOME -> "首页"
        RootTab.SEARCH -> "搜索"
        RootTab.FAVORITES -> "收藏"
        RootTab.SETTINGS -> "设置"
    }

@Composable
internal fun LibrarySearchScreen(
    state: AppUiState,
    viewModel: MainViewModel,
    onOpen: (LibraryResult) -> Unit,
    onFavorite: (LibraryResult) -> Unit,
) {
    LaunchedEffect(Unit) { viewModel.searchLibrary() }
    var voiceError by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val voiceIntent = remember {
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "说出要搜索的内容")
        }
    }
    val voiceLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val spoken = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
        if (!spoken.isNullOrBlank()) {
            voiceError = null
            viewModel.searchLibrary(spoken)
        }
    }
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(
                top = UiDimens.NavigationHeight + UiDimens.RootTopContentInset,
                bottom = UiDimens.RootBottomContentInset,
            )
            .navigationBarsPadding(),
    ) {
        OutlinedTextField(
            value = state.searchQuery,
            onValueChange = viewModel::searchLibrary,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            singleLine = true,
            shape = ControlShape,
            supportingText = { voiceError?.let { Text(it, color = MaterialTheme.colorScheme.error) } },
            placeholder = { Text("搜索标题、文件夹、作者、演员、制作商、标签") },
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            trailingIcon = {
                if (state.searchQuery.isNotEmpty()) {
                    IconButton(onClick = { viewModel.searchLibrary("") }) {
                        Icon(Icons.Outlined.Close, contentDescription = "清除")
                    }
                } else {
                    IconButton(onClick = {
                        voiceError = null
                        if (context.packageManager.resolveActivity(voiceIntent, 0) == null) {
                            voiceError = "此设备没有可用的语音识别服务"
                        } else {
                            runCatching { voiceLauncher.launch(voiceIntent) }
                                .onFailure { voiceError = "无法启动语音识别" }
                        }
                    }) {
                        Icon(Icons.Outlined.Mic, contentDescription = "语音搜索")
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { viewModel.searchLibrary() }),
        )
        SectionTabs(
            selected = state.searchSection,
            onSelected = viewModel::setSearchSection,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            "搜索结果",
            modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 8.dp),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        when {
            state.searchLoading && state.searchResults.isEmpty() -> LoadingState()
            state.searchError != null && state.searchResults.isEmpty() -> EmptyState(state.searchError)
            state.searchQuery.isBlank() -> EmptyState("搜索标题、目录和媒体元数据")
            state.searchResults.isEmpty() -> EmptyState("没有匹配结果")
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 16.dp),
            ) {
                items(state.searchResults, key = LibraryResult::id) { result ->
                    LibraryResultRow(
                        result = result,
                        imageLoader = viewModel.movieImageLoader,
                        onClick = { onOpen(result) },
                        onFavorite = { onFavorite(result) },
                    )
                }
                if (state.searchLoading) item { LoadingState(compact = true) }
            }
        }
    }
}

@Composable
internal fun FavoritesScreen(
    state: AppUiState,
    viewModel: MainViewModel,
    onOpen: (LibraryResult) -> Unit,
    onFavorite: (LibraryResult) -> Unit,
) {
    LaunchedEffect(Unit) { viewModel.loadFavorites(reset = true) }
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(
                top = UiDimens.NavigationHeight + UiDimens.RootTopContentInset,
                bottom = UiDimens.RootBottomContentInset,
            )
            .navigationBarsPadding(),
    ) {
        SectionTabs(
            selected = state.favoritesSection,
            onSelected = { viewModel.loadFavorites(section = it, reset = true) },
        )
        when {
            state.favoritesLoading && state.favoriteItems.isEmpty() -> LoadingState()
            state.favoritesError != null && state.favoriteItems.isEmpty() -> EmptyState(state.favoritesError)
            state.favoriteItems.isEmpty() -> EmptyState("还没有收藏的媒体")
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 12.dp),
            ) {
                items(state.favoriteItems, key = LibraryResult::id) { result ->
                    LibraryResultRow(
                        result = result,
                        imageLoader = viewModel.movieImageLoader,
                        onClick = { onOpen(result) },
                        onFavorite = { onFavorite(result) },
                    )
                }
                if (state.favoriteNextOffset != null) {
                    item {
                        TextButton(
                            onClick = viewModel::loadMoreFavorites,
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                        ) {
                            if (state.favoritesLoading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            else Text("加载更多 · ${state.favoriteItems.size} / ${state.favoriteTotal}")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTabs(
    selected: String,
    onSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sections = listOf(
        "all" to "全部",
        "short" to "短视频",
        "long" to "长视频",
        "asmr" to "ASMR",
        "movie" to "电影",
        "drama" to "短剧",
    )
    PrimaryScrollableTabRow(
        selectedTabIndex = sections.indexOfFirst { it.first == selected }.coerceAtLeast(0),
        modifier = modifier.fillMaxWidth(),
        edgePadding = 12.dp,
        containerColor = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.primary,
        divider = { androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant) },
    ) {
        sections.forEach { (key, label) ->
            Tab(
                selected = selected == key,
                onClick = { onSelected(key) },
                text = { Text(label, style = MaterialTheme.typography.titleSmall, maxLines = 1) },
            )
        }
    }
}

@Composable
private fun LibraryResultRow(
    result: LibraryResult,
    imageLoader: coil.ImageLoader,
    onClick: () -> Unit,
    onFavorite: () -> Unit,
) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(76.dp)
            .semantics { contentDescription = "${result.title}，${result.subtitle}" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            onClick = onClick,
            modifier = Modifier.weight(1f).fillMaxSize(),
            color = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            Row(
                Modifier.fillMaxSize().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh),
                    contentAlignment = Alignment.Center,
                ) {
                    if (result.posterUrl != null) {
                        AsyncImage(
                            model = ImageRequest.Builder(context).data(result.posterUrl).crossfade(true).build(),
                            imageLoader = imageLoader,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Icon(resultIcon(result), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(result.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        result.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (result.favoriteTargetId != null) {
            IconButton(onClick = onFavorite, modifier = Modifier.size(48.dp)) {
                Icon(
                    if (result.favorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                    contentDescription = if (result.favorite) "取消收藏" else "收藏",
                    tint = if (result.favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Spacer(Modifier.width(12.dp))
        }
    }
}

@Composable
private fun resultIcon(result: LibraryResult) = when (result.type) {
    "author" -> Icons.Outlined.Folder
    "entity" -> if (result.entity?.type == "studio") Icons.Outlined.Business else Icons.Outlined.Person
    "movie" -> Icons.Outlined.LocalMovies
    "drama" -> Icons.Outlined.TheaterComedy
    else -> if (result.section == "asmr") Icons.Outlined.Headphones else Icons.Outlined.PlayCircle
}

@Composable
internal fun SettingsScreen(
    state: AppUiState,
    onManagement: () -> Unit,
    onUpdate: () -> Unit,
    onThemes: () -> Unit,
    onLogout: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(
                top = UiDimens.NavigationHeight + UiDimens.RootTopContentInset,
                bottom = UiDimens.RootBottomContentInset,
            )
            .navigationBarsPadding(),
    ) {
        SettingsRow("管理", "媒体库、扫描、元数据和排除目录", Icons.Outlined.Tune, onManagement)
        SettingsRow("更新", "当前版本 ${BuildConfig.VERSION_NAME}", Icons.Outlined.SystemUpdate, onUpdate)
        SettingsRow(
            "主题配色",
            AppSkinOptions.firstOrNull { it.id == state.skin }?.label ?: AppSkinOptions.first().label,
            Icons.Outlined.Palette,
            onThemes,
        )
        SettingsRow("退出登录", "清除当前设备会话", Icons.AutoMirrored.Outlined.Logout, onLogout)
    }
}

@Composable
private fun SettingsRow(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(76.dp).padding(horizontal = 8.dp),
        color = Color.Transparent,
    ) {
        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer) }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun ThemeSettingsScreen(
    state: AppUiState,
    onBack: () -> Unit,
    onSelect: (String) -> Unit,
    onRetry: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedSkin = AppSkinOptions.firstOrNull { it.id == state.skin } ?: AppSkinOptions.first()
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(
                top = UiDimens.NavigationHeight + UiDimens.RootTopContentInset,
                bottom = UiDimens.RootBottomContentInset,
            )
            .navigationBarsPadding(),
    ) {
        Row(
            Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
            }
            Text("主题配色", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(48.dp))
        }
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "选择皮肤",
                modifier = Modifier.padding(top = 10.dp),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Surface(
                modifier = Modifier.fillMaxWidth().height(152.dp),
                color = colors.surfaceContainerLow,
                shape = RoundedCornerShape(20.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, colors.outlineVariant),
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(18.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(selectedSkin.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${colors.background.hexCode()} 背景 · ${colors.surfaceContainer.hexCode()} 内容层 · " +
                            "${colors.primary.hexCode()} 强调色 · ${colors.onSurface.hexCode()} 主文本",
                        color = colors.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(colors.primary, colors.secondary, colors.tertiary, colors.onSurface).forEach { swatch ->
                            Box(Modifier.weight(1f).height(10.dp).clip(CircleShape).background(swatch))
                        }
                    }
                }
            }
            Box(Modifier.fillMaxWidth()) {
                androidx.compose.material3.OutlinedButton(
                    onClick = { expanded = true },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
                    shape = ControlShape,
                ) {
                    Icon(Icons.Outlined.Palette, contentDescription = null)
                    Column(Modifier.weight(1f).padding(horizontal = 10.dp), horizontalAlignment = Alignment.Start) {
                        Text("切换皮肤", style = MaterialTheme.typography.labelMedium)
                        Text(selectedSkin.label, style = MaterialTheme.typography.bodyMedium)
                    }
                    Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = null)
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    AppSkinOptions.forEach { skin ->
                        DropdownMenuItem(
                            text = { Text(skin.label) },
                            leadingIcon = { Icon(Icons.Outlined.Palette, contentDescription = null) },
                            trailingIcon = if (skin.id == state.skin) {
                                { Icon(Icons.Outlined.Check, contentDescription = "当前皮肤") }
                            } else null,
                            onClick = {
                                expanded = false
                                onSelect(skin.id)
                            },
                        )
                    }
                }
            }
            Text(
                "选择后即时预览，并通过 GET/PUT /api/settings 持久化。",
                color = colors.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            Row(
                Modifier.fillMaxWidth().heightIn(min = 40.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when {
                    state.skinSaving -> {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("正在保存外观设置", color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                    state.skinSaveError != null -> {
                        Text(state.skinSaveError, Modifier.weight(1f), color = colors.error, style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = onRetry) { Text("重试") }
                    }
                    else -> Text("已同步 · /api/settings", color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

private fun Color.hexCode(): String = "#%06X".format(0xFFFFFF and toArgb())

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun LoadingState(compact: Boolean = false) {
    Box(
        Modifier.fillMaxWidth().then(if (compact) Modifier.height(56.dp) else Modifier.height(220.dp)),
        contentAlignment = Alignment.Center,
    ) { LoadingIndicator(Modifier.size(32.dp)) }
}

@Composable
private fun EmptyState(message: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
