package you.deepfuck.shortvideo.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalFocusManager
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
    onFullscreenChanged: (Boolean) -> Unit,
    onBackgroundPlaybackRequested: () -> Unit,
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
        val fullscreenContentClosed = state.surface == MediaSurface.ASMR && state.expandedMedia == null
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
                onScan = viewModel::startLibraryScan,
                onFastStart = viewModel::startFastStartCheck,
                onShowLogs = viewModel::showRuntimeLogs,
                onDismissLogs = viewModel::dismissRuntimeLogs,
                onClearLogs = viewModel::clearRuntimeLogs,
                onExportLogs = onExportLogs,
            )
        } else {
            Box(Modifier.fillMaxSize()) {
                key(state.surface) {
                    if (state.surface == MediaSurface.ASMR) {
                        AsmrScreen(
                            state = state,
                            player = player,
                            exoPlayer = viewModel.playback.player,
                            muted = state.muted,
                            fullscreen = fullscreen,
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
                            onAuthorListPosition = viewModel::saveAsmrAuthorListPosition,
                            onMediaListPosition = viewModel::saveAsmrMediaListPosition,
                        )
                    } else {
                        FeedScreen(
                            state = state,
                            player = player,
                            exoPlayer = viewModel.playback.player,
                            fullscreen = fullscreen,
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
                            onManage = viewModel::showManagement,
                            onCheckUpdate = viewModel::checkForAppUpdate,
                            onLogout = viewModel::logout,
                        )
                    }
                }
                if (!fullscreen && state.expandedMedia == null) {
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
            onDownload = viewModel::downloadAppUpdate,
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
            modifier = Modifier.fillMaxWidth().padding(bottom = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Box(Modifier.size(7.dp, 30.dp).clip(RoundedCornerShape(2.dp)).background(Accent))
                Text("短片", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(52.dp))
            Icon(Icons.Outlined.Lock, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(30.dp))
            Spacer(Modifier.height(16.dp))
            Text("继续上次播放", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(7.dp))
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
                shape = RoundedCornerShape(6.dp),
            )
            Spacer(Modifier.height(14.dp))
            Button(
                onClick = { focusManager.clearFocus(); onLogin(password) },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(6.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Accent),
            ) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(19.dp), color = TextPrimary, strokeWidth = 2.dp)
                } else {
                    Icon(Icons.AutoMirrored.Outlined.Login, contentDescription = null, modifier = Modifier.size(19.dp))
                }
                Spacer(Modifier.size(8.dp))
                Text(if (busy) "正在验证" else "进入")
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
    val tabWidth = 72.dp
    val indicatorOffset by animateDpAsState(
        targetValue = tabWidth * state.surface.ordinal,
        animationSpec = tween(200),
        label = "分类指示线",
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .padding(horizontal = if (compact) 8.dp else 12.dp),
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .width(tabWidth * MediaSurface.entries.size)
                .height(48.dp),
        ) {
            Row(Modifier.fillMaxSize()) {
                MediaSurface.entries.forEach { surface ->
                    val selected = state.surface == surface
                    TextButton(
                        onClick = { onSurface(surface) },
                        modifier = Modifier.width(tabWidth).height(44.dp),
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = Color.White.copy(alpha = if (selected) 1f else 0.66f),
                        ),
                    ) {
                        Text(
                            if (compact && surface != MediaSurface.ASMR) surface.label.take(1) else surface.label,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            style = MaterialTheme.typography.labelLarge.copy(
                                shadow = Shadow(Color.Black.copy(alpha = 0.58f), Offset(0f, 1f), 3f),
                            ),
                        )
                    }
                }
            }
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .offset(x = indicatorOffset + 24.dp)
                    .width(24.dp)
                    .height(2.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(Accent),
            )
        }
        Box(Modifier.align(Alignment.CenterEnd)) {
            IconButton(
                onClick = { menuOpen = true },
            ) {
                Icon(Icons.Outlined.MoreVert, contentDescription = "更多", tint = Color.White)
            }
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                containerColor = Raised,
            ) {
                if (state.surface != MediaSurface.ASMR) {
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
