package you.deepfuck.shortvideo

import android.Manifest
import android.content.ClipData
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.io.File
import you.deepfuck.shortvideo.ui.ShortVideoApp
import you.deepfuck.shortvideo.ui.ShortVideoTheme
import you.deepfuck.shortvideo.logging.AppLogStore

class MainActivity : ComponentActivity() {
    private val viewModel by viewModels<MainViewModel>()
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
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            ShortVideoTheme {
                ShortVideoApp(
                    viewModel = viewModel,
                    onFullscreenChanged = ::setPlayerFullscreen,
                    onBackgroundPlaybackRequested = ::requestMediaNotificationPermission,
                    onInstallAppUpdate = ::installAppUpdate,
                    onExportLogs = ::exportRuntimeLogs,
                )
            }
        }
    }

    override fun onPause() {
        viewModel.saveCurrentPosition()
        viewModel.onAppBackgrounded()
        super.onPause()
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
                show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    private fun requestMediaNotificationPermission() {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
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
