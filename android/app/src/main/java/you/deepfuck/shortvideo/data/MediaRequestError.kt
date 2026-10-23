package you.deepfuck.shortvideo.data

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

internal fun mediaRequestErrorMessage(error: Throwable, fallback: String): String {
    val causes = generateSequence(error) { it.cause }.toList()
    val apiError = causes.filterIsInstance<ApiException>().firstOrNull()
    if (apiError != null) {
        return when (apiError.statusCode) {
            408 -> "服务器请求超时，请稍后重试"
            429 -> "服务器请求过于频繁，请稍后重试"
            in 500..599 -> "服务器暂时异常（HTTP ${apiError.statusCode}），请稍后重试"
            else -> fallback
        }
    }
    return when {
        causes.any { it is SocketTimeoutException } -> "连接服务器超时，请检查网络后重试"
        causes.any { it is UnknownHostException || it is ConnectException } -> "无法连接服务器，请检查网络后重试"
        causes.any { it is SSLException } -> "服务器安全连接失败，请检查网络后重试"
        error is IOException -> "网络请求失败，请检查连接后重试"
        else -> fallback
    }
}
