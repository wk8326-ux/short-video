package you.deepfuck.shortvideo.data

import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaRequestErrorTest {
    @Test
    fun reportsServerStatusSeparatelyFromNetworkTimeout() {
        assertEquals(
            "服务器暂时异常（HTTP 503），请稍后重试",
            mediaRequestErrorMessage(ApiException(503, "unavailable"), "无法载入目录"),
        )
        assertEquals(
            "连接服务器超时，请检查网络后重试",
            mediaRequestErrorMessage(SocketTimeoutException(), "无法载入目录"),
        )
    }

    @Test
    fun keepsPageSpecificFallbackForOtherResponses() {
        assertEquals(
            "无法载入目录",
            mediaRequestErrorMessage(ApiException(404, "not found"), "无法载入目录"),
        )
    }
}
