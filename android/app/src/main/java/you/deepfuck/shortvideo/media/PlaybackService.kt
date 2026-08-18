package you.deepfuck.shortvideo.media

import android.content.Context
import android.content.Intent
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
            val session = MediaSession.Builder(this, engine.player).build()
            try {
                addSession(session)
                mediaSession = session
            } catch (error: Throwable) {
                session.release()
                throw error
            }
        }.onSuccess {
            logs.info("media_session_registered", "sessions=${getSessions().size}")
        }.onFailure { error ->
            logs.error("media_session_registration_failed", error = error)
            stopSelf()
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onDestroy() {
        val logs = AppLogStore.get(this)
        mediaSession?.let { session ->
            runCatching {
                if (isSessionAdded(session)) removeSession(session)
            }.onFailure { error ->
                logs.error("media_session_removal_failed", error = error)
            }
            runCatching(session::release).onFailure { error ->
                logs.error("media_session_release_failed", error = error)
            }
        }
        mediaSession = null
        logs.info("media_session_released")
        super.onDestroy()
    }

    companion object {
        fun start(context: Context) =
            context.startService(Intent(context, PlaybackService::class.java))

        fun stop(context: Context): Boolean =
            context.stopService(Intent(context, PlaybackService::class.java))
    }
}
