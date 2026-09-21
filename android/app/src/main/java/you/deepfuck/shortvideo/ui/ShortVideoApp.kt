package you.deepfuck.shortvideo.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Login
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import you.deepfuck.shortvideo.AppUiState
import you.deepfuck.shortvideo.AuthenticationState
import you.deepfuck.shortvideo.MainViewModel
import you.deepfuck.shortvideo.data.FeedMode
import you.deepfuck.shortvideo.data.AppUpdatePhase
import you.deepfuck.shortvideo.data.MediaSurface

@Composable
fun ShortVideoApp(
    viewModel: MainViewModel,
    pipMode: Boolean,
    onFullscreenChanged: (Boolean) -> Unit,
    onPipRequested: () -> Unit,
    onBackgroundPlaybackRequested: () -> Unit,
    onDownloadAppUpdate: () -> Unit,
    onInstallAppUpdate: (String) -> Unit,
    onExportLogs: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val player by viewModel.playback.snapshot.collectAsStateWithLifecycle()
    var fullscreen by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(
        state.authentication,
        state.surface,
        state.showManagement,
        state.expandedMedia?.id,
        state.appUpdate.phase,
    ) {
        val fullscreenContentClosed = when (state.surface) {
            MediaSurface.ASMR -> state.expandedMedia == null
            MediaSurface.MOVIE -> state.selectedMovie == null || state.nowPlaying == null
            MediaSurface.DRAMA -> state.selectedDrama == null || state.nowPlaying == null
            else -> false
        }
        if (
            fullscreen &&
            (
                state.authentication != AuthenticationState.SIGNED_IN ||
                    state.showManagement ||
                    fullscreenContentClosed ||
                    state.appUpdate.phase != AppUpdatePhase.IDLE
            )
        ) {
            fullscreen = false
            onFullscreenChanged(false)
        }
    }

    BackHandler(enabled = fullscreen) {
        fullscreen = false
        onFullscreenChanged(false)
    }
    BackHandler(enabled = !fullscreen && state.expandedMedia != null) {
        viewModel.collapsePlayer()
    }
    BackHandler(enabled = !fullscreen && state.showManagement) {
        viewModel.hideManagement()
    }
    BackHandler(
        enabled = !fullscreen && !state.showManagement &&
            state.surface == MediaSurface.ASMR && state.selectedAuthor != null && state.expandedMedia == null,
    ) {
        viewModel.leaveAsmrAuthor()
    }
    BackHandler(
        enabled = !fullscreen && !state.showManagement &&
            state.surface == MediaSurface.MOVIE && state.selectedMovie != null,
    ) {
        viewModel.closeMovieDetail()
    }
    BackHandler(
        enabled = !fullscreen && !state.showManagement &&
            state.surface == MediaSurface.MOVIE && state.selectedMovie == null &&
            state.openMovieGroupId != null,
    ) {
        viewModel.closeMovieGroup()
    }
    BackHandler(
        enabled = !fullscreen && !state.showManagement &&
            state.surface == MediaSurface.DRAMA && state.selectedDrama != null && state.nowPlaying != null,
    ) {
        viewModel.stopDramaPlayback()
    }
    BackHandler(
        enabled = !fullscreen && !state.showManagement &&
            state.surface == MediaSurface.DRAMA && state.selectedDrama != null && state.nowPlaying == null,
    ) {
        viewModel.closeDramaDetail()
    }
    BackHandler(
        enabled = !fullscreen && !state.showManagement &&
            state.surface == MediaSurface.DRAMA && state.selectedDrama == null &&
            state.openDramaGroupId != null,
    ) {
        viewModel.closeDramaGroup()
    }

    when (state.authentication) {
        AuthenticationState.CHECKING -> LoadingScreen()
        AuthenticationState.SIGNED_OUT -> LoginScreen(
            busy = state.loginBusy,
            error = state.loginError,
            onLogin = viewModel::login,
        )
        AuthenticationState.SIGNED_IN -> if (state.showManagement) {
            ManagementScreen(
                state = state,
                onBack = viewModel::hideManagement,
                onRefresh = viewModel::loadAdminStatus,
                onScanSource = viewModel::startLibraryScan,
                onSaveSource = viewModel::saveMediaSource,
                onDeleteSource = viewModel::deleteMediaSource,
                onReorderSources = viewModel::reorderMediaSources,
                onFastStart = viewModel::startFastStartCheck,
                onShowLogs = viewModel::showRuntimeLogs,
                onDismissLogs = viewModel::dismissRuntimeLogs,
                onClearLogs = viewModel::clearRuntimeLogs,
                onExportLogs = onExportLogs,
            )
        } else {
            Box(Modifier.fillMaxSize()) {
                key(state.surface) {
                    when (state.surface) {
                        MediaSurface.ASMR -> {
                        AsmrScreen(
                            state = state,
                            player = player,
                            exoPlayer = viewModel.playback.player,
                            muted = state.muted,
                            fullscreen = fullscreen,
                            pipMode = pipMode,
                            authorListPosition = viewModel.asmrAuthorListPosition(),
                            mediaListPosition = state.selectedAuthor
                                ?.let(viewModel::asmrMediaListPosition)
                                ?: you.deepfuck.shortvideo.ListPosition(),
                            onAuthor = viewModel::selectAsmrAuthor,
                            onBackAuthor = viewModel::leaveAsmrAuthor,
                            onLoadMoreItems = viewModel::loadMoreAsmrItems,
                            onFilter = viewModel::setAsmrFilter,
                            onQuery = viewModel::setAsmrQuery,
                            onPlay = { entry ->
                                viewModel.playAsmr(entry)
                                if (entry.isAudio) onBackgroundPlaybackRequested()
                            },
                            onExpand = viewModel::expandNowPlaying,
                            onCollapse = viewModel::collapsePlayer,
                            onClose = viewModel::closeAsmrPlayer,
                            onToggle = viewModel::togglePlayback,
                            onMuted = viewModel::setMuted,
                            onSeek = viewModel.playback::seekTo,
                            onSeekBy = viewModel.playback::seekBy,
                            onVideoBackgroundPlayback = { enabled ->
                                if (enabled) onBackgroundPlaybackRequested()
                                viewModel.setAsmrVideoBackgroundPlayback(enabled)
                            },
                            onFullscreen = {
                                fullscreen = it
                                onFullscreenChanged(it)
                            },
                            onPip = onPipRequested,
                            onAuthorListPosition = viewModel::saveAsmrAuthorListPosition,
                            onMediaListPosition = viewModel::saveAsmrMediaListPosition,
                        )
                        }
                        MediaSurface.MOVIE -> {
                            MovieScreen(
                                state = state,
                                player = player,
                                exoPlayer = viewModel.playback.player,
                                imageLoader = viewModel.movieImageLoader,
                                fullscreen = fullscreen,
                                pipMode = pipMode,
                                listPosition = viewModel.movieListPosition(),
                                groupListPosition = state.openMovieGroupId
                                    ?.let(viewModel::movieGroupListPosition)
                                    ?: you.deepfuck.shortvideo.ListPosition(),
                                onSelect = viewModel::selectMovie,
                                onBack = viewModel::closeMovieDetail,
                                onPlay = viewModel::playMovie,
                                onOpenGroup = viewModel::openMovieGroup,
                                onCloseGroup = viewModel::closeMovieGroup,
                                onGroupSort = viewModel::setMovieGroupSort,
                                onLoadMoreGroup = viewModel::loadMoreMovieGroup,
                                onGroupListPosition = viewModel::saveMovieGroupListPosition,
                                onWallView = viewModel::setMovieWallView,
                                onRetry = viewModel::retryMovies,
                                onTogglePlayback = viewModel::togglePlayback,
                                onMuted = viewModel::setMuted,
                                onSeek = viewModel.playback::seekTo,
                                onSeekBy = viewModel.playback::seekBy,
                                onFullscreen = {
                                    fullscreen = it
                                    onFullscreenChanged(it)
                                },
                                onPip = onPipRequested,
                                onListPosition = viewModel::saveMovieListPosition,
                            )
                        }
                        MediaSurface.DRAMA -> {
                            DramaScreen(
                                state = state,
                                player = player,
                                exoPlayer = viewModel.playback.player,
                                imageLoader = viewModel.movieImageLoader,
                                fullscreen = fullscreen,
                                pipMode = pipMode,
                                listPosition = viewModel.dramaListPosition(),
                                onSelect = viewModel::selectDrama,
                                onResume = viewModel::resumeDrama,
                                onBack = viewModel::closeDramaDetail,
                                onResumeEpisode = viewModel::dramaResumeEpisode,
                                onPlayEpisode = viewModel::playDramaEpisode,
                                onStopPlayback = viewModel::stopDramaPlayback,
                                onOpenGroup = viewModel::openDramaGroup,
                                onCloseGroup = viewModel::closeDramaGroup,
                                onLoadMoreGroup = viewModel::loadMoreDramaGroup,
                                onRetry = viewModel::retryDramas,
                                onTogglePlayback = viewModel::togglePlayback,
                                onMuted = viewModel::setMuted,
                                onSeek = viewModel.playback::seekTo,
                                onSeekBy = viewModel.playback::seekBy,
                                onFullscreen = {
                                    fullscreen = it
                                    onFullscreenChanged(it)
                                },
                                onPip = onPipRequested,
                                onListPosition = viewModel::saveDramaListPosition,
                            )
                        }
                        MediaSurface.SHORT, MediaSurface.LONG -> {
                        FeedScreen(
                            state = state,
                            player = player,
                            exoPlayer = viewModel.playback.player,
                            fullscreen = fullscreen,
                            pipMode = pipMode,
                            onSurface = viewModel::changeSurface,
                            onMode = viewModel::changeMode,
                            onActive = viewModel::activateFeedItem,
                            onLoadMore = viewModel::loadMoreFeed,
                            onRetry = viewModel::retryFeed,
                            onTogglePlayback = viewModel::togglePlayback,
                            onMuted = viewModel::setMuted,
                            onSeek = viewModel.playback::seekTo,
                            onSeekBy = viewModel.playback::seekBy,
                            onFullscreen = {
                                fullscreen = it
                                onFullscreenChanged(it)
                            },
                            onPip = onPipRequested,
                            onManage = viewModel::showManagement,
                            onCheckUpdate = viewModel::checkForAppUpdate,
                            onLogout = viewModel::logout,
                        )
                        }
                    }
                }
                if (
                    !pipMode &&
                    !fullscreen &&
                    state.expandedMedia == null &&
                    state.selectedMovie == null &&
                    state.selectedDrama == null
                ) {
                    AppNavigation(
                        state = state,
                        compact = false,
                        onSurface = viewModel::changeSurface,
                        onMode = viewModel::changeMode,
                        onManage = viewModel::showManagement,
                        onCheckUpdate = viewModel::checkForAppUpdate,
                        onLogout = viewModel::logout,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .statusBarsPadding()
                            .padding(top = 2.dp),
                    )
                }
            }
        }
    }
    if (state.authentication == AuthenticationState.SIGNED_IN) {
        AppUpdateDialog(
            state = state.appUpdate,
            onDismiss = viewModel::dismissAppUpdate,
            onCheck = viewModel::checkForAppUpdate,
            onDownload = onDownloadAppUpdate,
            onInstall = onInstallAppUpdate,
        )
    }
}

@Composable
private fun LoadingScreen() {
    Box(
        modifier = Modifier.fillMaxSize().background(Canvas),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(28.dp), color = TextSecondary, strokeWidth = 2.dp)
    }
}

@Composable
private fun LoginScreen(busy: Boolean, error: String?, onLogin: (String) -> Unit) {
    var password by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Canvas)
            .safeDrawingPadding()
            .imePadding()
            .padding(horizontal = 28.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().widthIn(max = 420.dp).padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Box(Modifier.size(7.dp, 30.dp).clip(RoundedCornerShape(2.dp)).background(Accent))
                Text("deepfuck", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(36.dp))
            GlassPanel(
                modifier = Modifier.fillMaxWidth(),
                fill = GlassFillSoft,
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(Icons.Outlined.Lock, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(28.dp))
                    Spacer(Modifier.height(16.dp))
                    Text("继续上次播放", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(6.dp))
                    Text("输入访问密码", color = TextFaint, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(24.dp))
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !busy,
                        singleLine = true,
                        label = { Text("访问密码") },
                        isError = error != null,
                        supportingText = { if (error != null) Text(error) },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = {
                            focusManager.clearFocus()
                            onLogin(password)
                        }),
                        shape = ControlShape,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = CanvasSoft.copy(alpha = 0.72f),
                            unfocusedContainerColor = CanvasSoft.copy(alpha = 0.54f),
                            focusedBorderColor = GlassLine,
                            unfocusedBorderColor = Line,
                            cursorColor = AccentSoft,
                        ),
                    )
                    Spacer(Modifier.height(14.dp))
                    Button(
                        onClick = { focusManager.clearFocus(); onLogin(password) },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = ControlShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Accent,
                            contentColor = Color.White,
                        ),
                    ) {
                        if (busy) {
                            CircularProgressIndicator(Modifier.size(19.dp), color = Color.White, strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.AutoMirrored.Outlined.Login, contentDescription = null, modifier = Modifier.size(19.dp))
                        }
                        Spacer(Modifier.size(8.dp))
                        Text(if (busy) "正在验证" else "进入")
                    }
                }
            }
        }
    }
}

@Composable
internal fun AppNavigation(
    state: AppUiState,
    compact: Boolean,
    onSurface: (MediaSurface) -> Unit,
    onMode: (FeedMode) -> Unit,
    onManage: () -> Unit,
    onCheckUpdate: () -> Unit,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .padding(horizontal = if (compact) 8.dp else 12.dp),
    ) {
        // 分栏数量会随板块增加，这里按两侧各留一个 48dp 圆钮的空间来分配宽度，
        // 下限压到 36dp，保证窄屏上 5 个分栏也不会和右侧“更多”按钮叠在一起。
        val tabWidth = ((maxWidth - 104.dp - 16.dp) / MediaSurface.entries.size)
            .coerceIn(36.dp, 72.dp)
        val indicatorOffset by animateDpAsState(
            targetValue = tabWidth * state.surface.ordinal,
            animationSpec = tween(200),
            label = "分类指示线",
        )
        GlassPanel(
            modifier = Modifier
                .align(Alignment.Center)
                .width(tabWidth * MediaSurface.entries.size + 16.dp)
                .height(52.dp),
            fill = GlassFillSoft,
            line = Line,
        ) {
            Box(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
                Row(Modifier.fillMaxSize()) {
                    MediaSurface.entries.forEach { surface ->
                        val active = state.surface == surface
                        TextButton(
                            onClick = { onSurface(surface) },
                            modifier = Modifier
                                .width(tabWidth)
                                .height(48.dp)
                                .semantics { selected = active },
                            contentPadding = PaddingValues(horizontal = 2.dp),
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = Color.White.copy(alpha = if (active) 1f else 0.66f),
                            ),
                        ) {
                            Text(
                                surface.label,
                                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                                style = (when {
                                    tabWidth < 46.dp -> MaterialTheme.typography.labelSmall
                                    tabWidth < 62.dp -> MaterialTheme.typography.labelMedium
                                    else -> MaterialTheme.typography.labelLarge
                                }).copy(
                                    shadow = Shadow(Color.Black.copy(alpha = 0.58f), Offset(0f, 1f), 3f),
                                ),
                            )
                        }
                    }
                }
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .offset { IntOffset(x = (indicatorOffset + (tabWidth - 24.dp) / 2).roundToPx(), y = 0) }
                        .width(24.dp)
                        .height(2.dp)
                        .clip(RoundedCornerShape(1.dp))
                        .background(Accent),
                )
            }
        }
        Box(Modifier.align(Alignment.CenterEnd)) {
            GlassIconButton(
                label = "更多",
                onClick = { menuOpen = true },
            ) {
                Icon(Icons.Outlined.MoreVert, contentDescription = null)
            }
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                containerColor = RaisedStrong,
                shape = GlassPanelShape,
                shadowElevation = 0.dp,
            ) {
                if (state.surface.isFeed) {
                    FeedMode.entries.forEach { mode ->
                        DropdownMenuItem(
                            text = { Text(mode.label) },
                            leadingIcon = {
                                Icon(
                                    if (mode == FeedMode.SHUFFLE) Icons.Outlined.Shuffle else Icons.Outlined.Schedule,
                                    contentDescription = null,
                                )
                            },
                            trailingIcon = {
                                if (state.mode == mode) Icon(Icons.Outlined.Check, contentDescription = null)
                            },
                            onClick = { menuOpen = false; onMode(mode) },
                        )
                    }
                }
                DropdownMenuItem(
                    text = { Text("管理") },
                    leadingIcon = { Icon(Icons.Outlined.AdminPanelSettings, contentDescription = null) },
                    onClick = { menuOpen = false; onManage() },
                )
                DropdownMenuItem(
                    text = { Text("检查更新") },
                    leadingIcon = { Icon(Icons.Outlined.SystemUpdate, contentDescription = null) },
                    onClick = { menuOpen = false; onCheckUpdate() },
                )
                DropdownMenuItem(
                    text = { Text("退出登录") },
                    leadingIcon = { Icon(Icons.AutoMirrored.Outlined.Logout, contentDescription = null) },
                    onClick = { menuOpen = false; onLogout() },
                )
            }
        }
    }
}
