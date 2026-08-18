package you.deepfuck.shortvideo.data

import org.json.JSONObject

data class AppUpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkFile: String,
    val sha256: String,
    val size: Long,
    val notes: String,
    val downloadUrl: String,
) {
    init {
        require(versionCode > 0)
        require(versionName.isNotBlank() && versionName.length <= 64)
        require(APK_FILE_PATTERN.matches(apkFile))
        require(SHA256_PATTERN.matches(sha256))
        require(size > 0L)
        require(notes.length <= 10_000)
        require(downloadUrl.startsWith("/api/app/update/apk"))
    }

    companion object {
        private val APK_FILE_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,199}\\.apk$", RegexOption.IGNORE_CASE)
        private val SHA256_PATTERN = Regex("^[0-9a-fA-F]{64}$")

        fun fromJson(value: JSONObject): AppUpdateInfo = AppUpdateInfo(
            versionCode = value.getInt("versionCode"),
            versionName = value.getString("versionName"),
            apkFile = value.getString("apkFile"),
            sha256 = value.getString("sha256").lowercase(),
            size = value.getLong("size"),
            notes = value.optString("notes"),
            downloadUrl = value.getString("downloadUrl"),
        )
    }
}

enum class AppUpdatePhase { IDLE, CHECKING, AVAILABLE, DOWNLOADING, READY, LATEST, ERROR }

data class AppUpdateUiState(
    val phase: AppUpdatePhase = AppUpdatePhase.IDLE,
    val currentVersionName: String = "",
    val info: AppUpdateInfo? = null,
    val progress: Float = 0f,
    val downloadedBytes: Long = 0L,
    val downloadedApkPath: String? = null,
    val message: String? = null,
)
