package you.deepfuck.shortvideo

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.work.WorkManager
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import you.deepfuck.shortvideo.data.CatalogSyncWorker
import you.deepfuck.shortvideo.ui.ShortVideoTheme
import you.deepfuck.shortvideo.ui.TvBoxApp

/** The standalone movie entry never creates the backend view model. */
class TvBoxActivity : ComponentActivity() {
    private val viewModel by viewModels<TvBoxMovieViewModel>()
    private val pipMode = mutableStateOf(false)
    private var pipRequested = false
    private val pipReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_PIP_TOGGLE) {
                viewModel.togglePlayback()
                updatePipActions()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // An upgrade must not leave a former server bootstrap running behind this entry.
        WorkManager.getInstance(this).cancelAllWorkByTag(CatalogSyncWorker.TAG)
        val filter = IntentFilter(ACTION_PIP_TOGGLE)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(pipReceiver, filter, RECEIVER_NOT_EXPORTED)
        else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(pipReceiver, filter)
        }
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        WindowCompat.setDecorFitsSystemWindows(window, false)
        lifecycleScope.launch {
            viewModel.playback.snapshot.map { it.playWhenReady }.distinctUntilChanged()
                .collect { updatePipActions() }
        }
        setContent {
            val state by viewModel.state.collectAsStateWithLifecycle()
            ShortVideoTheme(skin = state.catalog.skin) {
                Surface(color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) {
                    TvBoxApp(
                        viewModel = viewModel,
                        pipMode = pipMode.value,
                        onFullscreenChanged = ::setFullscreen,
                        onPipRequested = ::enterPip,
                        appReleaseUrl = "https://github.com/wk8326-ux/short-video/releases/latest",
                        onOpenLegacy = {
                            viewModel.onBackgrounded()
                            startActivity(Intent(this, MainActivity::class.java))
                        },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.activate()
    }

    override fun onPause() {
        viewModel.saveCurrentPosition()
        super.onPause()
    }

    override fun onStop() {
        if (!isInPictureInPictureMode && !pipRequested) viewModel.onBackgrounded()
        super.onStop()
    }

    override fun onDestroy() {
        unregisterReceiver(pipReceiver)
        super.onDestroy()
    }

    override fun onPictureInPictureModeChanged(inPip: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(inPip, newConfig)
        pipRequested = false
        pipMode.value = inPip
        updatePipActions()
    }

    private fun setFullscreen(fullscreen: Boolean) {
        requestedOrientation = if (fullscreen) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        WindowInsetsControllerCompat(window, window.decorView).apply {
            if (fullscreen) {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            } else show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun enterPip() {
        pipRequested = true
        if (!runCatching { enterPictureInPictureMode(pipParams()) }.getOrDefault(false)) pipRequested = false
    }

    private fun updatePipActions() {
        if (isInPictureInPictureMode || pipRequested) runCatching { setPictureInPictureParams(pipParams()) }
    }

    private fun pipParams(): PictureInPictureParams {
        val playing = viewModel.playback.snapshot.value.playWhenReady
        val toggle = PendingIntent.getBroadcast(
            this, 1902, Intent(ACTION_PIP_TOGGLE).setPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val action = RemoteAction(
            Icon.createWithResource(this, if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play),
            if (playing) "暂停" else "播放", if (playing) "暂停" else "播放", toggle,
        )
        val size = viewModel.playback.player.videoSize
        val ratio = if (size.width > 0 && size.height > 0) {
            (size.width.toDouble() / size.height).coerceIn(0.42, 2.39)
        } else 16.0 / 9.0
        return PictureInPictureParams.Builder().setAspectRatio(Rational((ratio * 1000).toInt(), 1000))
            .setActions(listOf(action)).build()
    }

    companion object {
        private const val ACTION_PIP_TOGGLE = "you.deepfuck.shortvideo.action.TVBOX_PIP_TOGGLE"
    }
}
