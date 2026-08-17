package you.deepfuck.shortvideo.media

import android.content.Context
import you.deepfuck.shortvideo.data.MediaApi
import you.deepfuck.shortvideo.data.PlaybackPreferences

object PlaybackEngineProvider {
    @Volatile
    private var instance: PlaybackEngine? = null

    fun get(context: Context): PlaybackEngine = instance ?: synchronized(this) {
        instance ?: PlaybackEngine(
            context.applicationContext,
            MediaApi(PlaybackPreferences(context.applicationContext)),
        ).also { instance = it }
    }
}
