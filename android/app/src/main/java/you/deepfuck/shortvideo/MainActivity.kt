package you.deepfuck.shortvideo

import android.Manifest
import android.app.PictureInPictureParams
import android.content.ClipData
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.io.File
import you.deepfuck.shortvideo.ui.ShortVideoApp
import you.deepfuck.shortvideo.ui.ShortVideoTheme
import you.deepfuck.shortvideo.ui.Canvas
import you.deepfuck.shortvideo.ui.TextPrimary
import you.deepfuck.shortvideo.logging.AppLogStore

class MainActivity : ComponentActivity() {
    private val viewModel by viewModels<MainViewModel>()
    /**
     * Compose needs to know about the floating window so it can drop the
     * chrome: the same layout that fills a phone is only a thumbnail here.
     */
    private val pipMode = mutableStateOf(false)
    /**
     * `onPause` fires before the system reports the PiP transition, so the
     * request is latched here and released once the callback arrives.
     */
    private var pipRequested = false
    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }
    private var pendingUpdateApk: File? = null
    private val installPermission = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        val apk = pendingUpdateApk ?: return@registerForActivityResult
        if (packageManager.canRequestPackageInstalls()) {
            openPackageInstaller(apk)
        } else {
            viewModel.reportAppUpdateInstallError("需要允许此应用安装未知来源应用")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        setContent {
            ShortVideoTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Canvas,
                    contentColor = TextPrimary,
                ) {
                    Box(Modifier.fillMaxSize()) {
                        ShortVideoApp(
                            viewModel = viewModel,
                            pipMode = pipMode.value,
                            onFullscreenChanged = ::setPlayerFullscreen,
                            onPipRequested = ::enterPlayerPip,
                            onBackgroundPlaybackRequested = ::requestMediaNotificationPermission,
                            onDownloadAppUpdate = ::downloadAppUpdate,
                            onInstallAppUpdate = ::installAppUpdate,
                            onExportLogs = ::exportRuntimeLogs,
                        )
                        Spacer(
                            Modifier
                                .align(Alignment.TopCenter)
                                .fillMaxWidth()
                                .windowInsetsTopHeight(WindowInsets.statusBars)
                                .background(Canvas),
                        )
                    }
                }
            }
        }
    }

    override fun onPause() {
        // Leaving the app is the one moment the server copy of the playhead
        // really matters, so this report skips the usual throttle.
        viewModel.saveCurrentPosition(forceReport = true)
        super.onPause()
    }

    /**
     * Playback is only torn down once the activity is truly hidden. A floating
     * window keeps the activity visible, so shrinking the video no longer
     * silences it the way a plain trip to the home screen does.
     */
    override fun onStop() {
        if (!isInPictureInPictureMode && !pipRequested) viewModel.onAppBackgrounded()
        super.onStop()
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        pipRequested = false
        pipMode.value = isInPictureInPictureMode
    }

    private fun setPlayerFullscreen(fullscreen: Boolean) {
        requestedOrientation = if (fullscreen) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        WindowInsetsControllerCompat(window, window.decorView).apply {
            if (fullscreen) {
                systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            } else {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
                show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    /**
     * Shrinks the running video into a floating window. The window keeps the
     * same aspect ratio as the decoded frame so the picture is not letterboxed
     * into a fixed 16:9 box.
     */
    private fun enterPlayerPip() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        pipRequested = true
        val entered = runCatching {
            enterPictureInPictureMode(
                PictureInPictureParams.Builder()
                    .setAspectRatio(currentPlayerAspectRatio())
                    .build(),
            )
        }.getOrDefault(false)
        if (!entered) pipRequested = false
    }

    private fun currentPlayerAspectRatio(): Rational {
        val size = runCatching { viewModel.playback.player.videoSize }.getOrNull()
        val width = size?.width ?: 0
        val height = size?.height ?: 0
        if (width <= 0 || height <= 0) return Rational(16, 9)
        // The platform rejects ratios outside roughly 0.42:1 … 2.39:1.
        val ratio = (width.toDouble() / height.toDouble()).coerceIn(0.42, 2.39)
        return Rational((ratio * 1000).toInt(), 1000)
    }

    private fun requestMediaNotificationPermission() {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun downloadAppUpdate() {
        requestMediaNotificationPermission()
        viewModel.downloadAppUpdate()
    }

    private fun installAppUpdate(apkPath: String) {
        val updateDirectory = File(filesDir, "app-updates").canonicalFile
        val apk = runCatching { File(apkPath).canonicalFile }.getOrNull()
        if (
            apk == null ||
            apk.parentFile != updateDirectory ||
            !apk.isFile ||
            !apk.name.endsWith(".apk", ignoreCase = true)
        ) {
            viewModel.reportAppUpdateInstallError("安装包不存在，请重新下载")
            return
        }
        pendingUpdateApk = apk
        if (packageManager.canRequestPackageInstalls()) {
            openPackageInstaller(apk)
            return
        }
        val permissionIntent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:$packageName"),
        )
        runCatching { installPermission.launch(permissionIntent) }
            .onFailure {
                viewModel.reportAppUpdateInstallError("无法打开安装权限设置")
            }
    }

    private fun openPackageInstaller(apk: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", apk)
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            clipData = ClipData.newRawUri("应用更新", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(installIntent) }
            .onSuccess { pendingUpdateApk = null }
            .onFailure {
                viewModel.reportAppUpdateInstallError("无法打开系统安装器")
            }
    }

    private fun exportRuntimeLogs() {
        val file = AppLogStore.get(this).exportFile()
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("运行日志", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching {
            startActivity(Intent.createChooser(shareIntent, "导出运行日志"))
        }.onFailure { error ->
            AppLogStore.get(this).error("log_export_failed", error = error)
        }
    }
}
