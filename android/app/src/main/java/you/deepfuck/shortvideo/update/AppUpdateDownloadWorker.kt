package you.deepfuck.shortvideo.update

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import you.deepfuck.shortvideo.data.ApiException
import you.deepfuck.shortvideo.data.AppUpdateInfo
import you.deepfuck.shortvideo.data.MediaApi
import you.deepfuck.shortvideo.data.PlaybackPreferences
import you.deepfuck.shortvideo.logging.AppLogStore

internal object AppUpdateWork {
    const val KEY_DOWNLOADED_BYTES = "downloaded_bytes"
    const val KEY_TOTAL_BYTES = "total_bytes"
    const val KEY_APK_PATH = "apk_path"
    const val KEY_ERROR = "error"

    private const val KEY_VERSION_CODE = "version_code"
    private const val KEY_VERSION_NAME = "version_name"
    private const val KEY_APK_FILE = "apk_file"
    private const val KEY_SHA256 = "sha256"
    private const val KEY_SIZE = "size"
    private const val KEY_DOWNLOAD_URL = "download_url"

    fun uniqueName(versionCode: Int): String = "app-update-$versionCode"

    fun targetFile(context: Context, update: AppUpdateInfo): File =
        File(File(context.filesDir, "app-updates"), "short-video-${update.versionCode}.apk")

    fun partialFile(context: Context, update: AppUpdateInfo): File =
        File("${targetFile(context, update).absolutePath}.part")

    fun request(update: AppUpdateInfo): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<AppUpdateDownloadWorker>()
            .setInputData(toInputData(update))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .addTag(uniqueName(update.versionCode))
            .build()

    internal fun toInputData(update: AppUpdateInfo): Data = workDataOf(
        KEY_VERSION_CODE to update.versionCode,
        KEY_VERSION_NAME to update.versionName,
        KEY_APK_FILE to update.apkFile,
        KEY_SHA256 to update.sha256,
        KEY_SIZE to update.size,
        KEY_DOWNLOAD_URL to update.downloadUrl,
    )

    internal fun fromInputData(data: Data): AppUpdateInfo? = runCatching {
        AppUpdateInfo(
            versionCode = data.getInt(KEY_VERSION_CODE, 0),
            versionName = data.getString(KEY_VERSION_NAME).orEmpty(),
            apkFile = data.getString(KEY_APK_FILE).orEmpty(),
            sha256 = data.getString(KEY_SHA256).orEmpty(),
            size = data.getLong(KEY_SIZE, 0L),
            notes = "",
            downloadUrl = data.getString(KEY_DOWNLOAD_URL).orEmpty(),
        )
    }.getOrNull()
}

internal class AppUpdateDownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val update = AppUpdateWork.fromInputData(inputData)
            ?: return Result.failure(workDataOf(AppUpdateWork.KEY_ERROR to "更新信息无效，请重新检查"))
        val logs = AppLogStore.get(applicationContext)
        val target = AppUpdateWork.targetFile(applicationContext, update)
        logs.info(
            "app_update_download_start",
            "version=${update.versionCode} attempt=$runAttemptCount retained=${AppUpdateWork.partialFile(applicationContext, update).length()}",
        )

        return try {
            withContext(Dispatchers.IO) {
                var lastPercent = -1
                MediaApi(PlaybackPreferences(applicationContext)).downloadAppUpdate(update, target) { downloaded, total ->
                    val percent = ((downloaded * 100L) / total.coerceAtLeast(1L)).toInt()
                    if (percent != lastPercent) {
                        lastPercent = percent
                        setProgressAsync(
                            workDataOf(
                                AppUpdateWork.KEY_DOWNLOADED_BYTES to downloaded,
                                AppUpdateWork.KEY_TOTAL_BYTES to total,
                            ),
                        )
                    }
                }
            }
            logs.info("app_update_download_complete", "version=${update.versionCode} bytes=${target.length()}")
            Result.success(
                workDataOf(
                    AppUpdateWork.KEY_APK_PATH to target.absolutePath,
                    AppUpdateWork.KEY_DOWNLOADED_BYTES to update.size,
                    AppUpdateWork.KEY_TOTAL_BYTES to update.size,
                ),
            )
        } catch (error: CancellationException) {
            logs.info(
                "app_update_download_stopped",
                "version=${update.versionCode} retained=${AppUpdateWork.partialFile(applicationContext, update).length()}",
            )
            throw error
        } catch (error: ApiException) {
            val message = when (error.statusCode) {
                401, 403 -> "登录状态已失效，请重新登录后继续下载"
                409 -> "服务器版本已变化，请重新检查更新"
                else -> "更新服务返回 HTTP ${error.statusCode}"
            }
            logs.warning("app_update_download_http_error", "version=${update.versionCode} status=${error.statusCode}")
            if (error.statusCode >= 500 && runAttemptCount < MAX_RETRIES) Result.retry()
            else Result.failure(workDataOf(AppUpdateWork.KEY_ERROR to message))
        } catch (error: IOException) {
            logs.warning(
                "app_update_download_io_error",
                "version=${update.versionCode} attempt=$runAttemptCount retained=${AppUpdateWork.partialFile(applicationContext, update).length()}",
                error,
            )
            if (runAttemptCount < MAX_RETRIES) Result.retry()
            else Result.failure(workDataOf(AppUpdateWork.KEY_ERROR to "下载暂时中断，已保留进度，可稍后继续"))
        } catch (error: Throwable) {
            logs.error("app_update_download_failed", "version=${update.versionCode}", error)
            Result.failure(workDataOf(AppUpdateWork.KEY_ERROR to "更新下载失败，已保留当前进度"))
        }
    }

    private companion object {
        const val MAX_RETRIES = 5
    }
}
