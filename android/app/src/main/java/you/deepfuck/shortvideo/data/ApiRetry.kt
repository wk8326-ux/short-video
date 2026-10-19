package you.deepfuck.shortvideo.data

import java.io.IOException

/**
 * Retries only idempotent catalogue reads. Mutating requests must not use this
 * helper because replaying a POST/PUT can apply the same action twice.
 */
internal fun isTransientReadFailure(error: IOException): Boolean =
    when (error) {
        is ApiException -> error.statusCode == 408 || error.statusCode == 429 || error.statusCode >= 500
        else -> true
    }

internal fun <T> withTransientReadRetry(
    attempts: Int = 2,
    delaysMs: List<Long> = listOf(450L),
    sleep: (Long) -> Unit = ::sleepBeforeReadRetry,
    block: () -> T,
): T {
    require(attempts > 0) { "attempts must be positive" }

    var lastError: IOException? = null
    repeat(attempts) { attempt ->
        try {
            return block()
        } catch (error: IOException) {
            lastError = error
            if (attempt + 1 >= attempts || !isTransientReadFailure(error)) throw error
            sleep(delaysMs.getOrElse(attempt) { delaysMs.lastOrNull() ?: 0L })
        }
    }
    throw checkNotNull(lastError)
}

private fun sleepBeforeReadRetry(delayMs: Long) {
    try {
        Thread.sleep(delayMs.coerceAtLeast(0L))
    } catch (interrupted: InterruptedException) {
        Thread.currentThread().interrupt()
        throw IOException("Read retry interrupted", interrupted)
    }
}
