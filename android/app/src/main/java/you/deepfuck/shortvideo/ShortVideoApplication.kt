package you.deepfuck.shortvideo

import android.app.Application
import you.deepfuck.shortvideo.logging.AppCrashHandler
import you.deepfuck.shortvideo.logging.AppLogStore

class ShortVideoApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLogStore.get(this).info("app_start", "version=${BuildConfig.VERSION_NAME}")
        AppCrashHandler.install(this)
    }
}
