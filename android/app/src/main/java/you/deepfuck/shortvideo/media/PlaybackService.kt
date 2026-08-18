package you.deepfuck.shortvideo.media

import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import you.deepfuck.shortvideo.logging.AppLogStore

class PlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val logs = AppLogStore.get(this)
        runCatching {
            val engine = PlaybackEngineProvider.get(this)
            mediaSession = MediaSession.Builder(this, engine.player).build()
        }.onSuccess {
            logs.info("media_session_create")
        }.onFailure { error ->
            logs.error("media_session_create_failed", error = error)
            stopSelf()
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onDestroy() {
        AppLogStore.get(this).info("media_session_destroy")
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }
}
