package you.deepfuck.shortvideo.logging

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.net.URI
import java.time.Instant
import kotlin.system.exitProcess

internal object RuntimeLogSanitizer {
    private val secretPattern = Regex(
        "(?i)(authorization|cookie|password|session|token)(\\s*[:=]\\s*)([^\\s,;]+)",
    )
    private val urlPattern = Regex("https?://[^\\s\\])}>]+")

    fun sanitize(value: String): String {
        val withoutSecrets = secretPattern.replace(value) { match ->
            "${match.groupValues[1]}${match.groupValues[2]}<redacted>"
        }
        return urlPattern.replace(withoutSecrets) { match ->
            val host = runCatching { URI(match.value).host }.getOrNull()
            if (host.isNullOrBlank()) "<url>" else "<url:$host>"
        }
    }
}

internal fun boundedLogText(value: String, maxChars: Int): String {
    if (maxChars <= 0) return ""
    return if (value.length <= maxChars) value else "... earlier entries omitted ...\n" + value.takeLast(maxChars)
}

class AppLogStore private constructor(context: Context) {
    private val directory = File(context.filesDir, LOG_DIRECTORY)
    private val activeFile = File(directory, ACTIVE_LOG)
    private val previousFile = File(directory, PREVIOUS_LOG)
    private val exportFile = File(directory, EXPORT_LOG)
    private val lock = Any()

    init {
        directory.mkdirs()
    }

    fun info(event: String, details: String = "") = write("INFO", event, details, null)

    fun warning(event: String, details: String = "", error: Throwable? = null) =
        write("WARN", event, details, error)

    fun error(event: String, details: String = "", error: Throwable? = null) =
        write("ERROR", event, details, error)

    fun fatal(event: String, details: String = "", error: Throwable? = null) =
        write("FATAL", event, details, error)

    fun readText(): String = synchronized(lock) {
        val combined = buildString {
            if (previousFile.isFile) append(previousFile.readText()).append('\n')
            if (activeFile.isFile) append(activeFile.readText())
        }
        boundedLogText(combined.trim(), MAX_VISIBLE_CHARS)
    }

    fun clear() = synchronized(lock) {
        activeFile.delete()
        previousFile.delete()
        exportFile.delete()
        activeFile.createNewFile()
    }

    fun exportFile(): File = synchronized(lock) {
        if (!activeFile.exists()) activeFile.createNewFile()
        exportFile.writeText(
            buildString {
                if (previousFile.isFile) append(previousFile.readText()).append('\n')
                append(activeFile.readText())
            },
        )
        exportFile
    }

    private fun write(level: String, event: String, details: String, error: Throwable?) {
        val stack = error?.let {
            StringWriter().also { output -> it.printStackTrace(PrintWriter(output)) }.toString()
        }.orEmpty()
        val payload = buildString {
            append(Instant.now()).append(' ')
            append(level).append(' ')
            append('[').append(Thread.currentThread().name).append("] ")
            append(event)
            if (details.isNotBlank()) append(" | ").append(details)
            if (stack.isNotBlank()) append('\n').append(stack)
        }
        val safePayload = RuntimeLogSanitizer.sanitize(payload).take(MAX_ENTRY_CHARS)
        synchronized(lock) {
            runCatching {
                directory.mkdirs()
                rotateIfNeeded(safePayload.length)
                activeFile.appendText(safePayload.trimEnd() + "\n")
            }
        }
    }

    private fun rotateIfNeeded(incomingChars: Int) {
        if (activeFile.length() + incomingChars <= MAX_FILE_BYTES) return
        previousFile.delete()
        if (activeFile.exists()) activeFile.renameTo(previousFile)
    }

    companion object {
        private const val LOG_DIRECTORY = "runtime-logs"
        private const val ACTIVE_LOG = "runtime.log"
        private const val PREVIOUS_LOG = "runtime.previous.log"
        private const val EXPORT_LOG = "short-video-runtime.log"
        private const val MAX_FILE_BYTES = 512L * 1024L
        private const val MAX_ENTRY_CHARS = 32_000
        private const val MAX_VISIBLE_CHARS = 120_000

        @Volatile
        private var instance: AppLogStore? = null

        fun get(context: Context): AppLogStore = instance ?: synchronized(this) {
            instance ?: AppLogStore(context.applicationContext).also { instance = it }
        }
    }
}

internal object AppCrashHandler {
    @Volatile
    private var installed = false

    fun install(context: Context) {
        if (installed) return
        synchronized(this) {
            if (installed) return
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            val logs = AppLogStore.get(context)
            Thread.setDefaultUncaughtExceptionHandler { thread, error ->
                logs.fatal(
                    event = "uncaught_exception",
                    details = "thread=${thread.name} type=${error.javaClass.name}",
                    error = error,
                )
                if (previous != null) previous.uncaughtException(thread, error) else exitProcess(10)
            }
            installed = true
        }
    }
}
