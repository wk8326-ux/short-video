package you.deepfuck.shortvideo.data

import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/** Source-scoped loopback endpoints for CatVod proxyLocal and JavaScript proxy callbacks. */
internal class TvBoxLocalProxy : Closeable {
    data class Response(val status: Int, val mime: String, val body: InputStream, val headers: Map<String, String> = emptyMap())
    private val handlers = ConcurrentHashMap<String, (Map<String, String>) -> Response>()
    private val token = UUID.randomUUID().toString()
    private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
    private val workers = ThreadPoolExecutor(2, 4, 30, TimeUnit.SECONDS, ArrayBlockingQueue(16),
        { task -> Thread(task, "TVBox-proxy").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val acceptor = Executors.newSingleThreadExecutor { task -> Thread(task, "TVBox-proxy-listener").apply { isDaemon = true } }
    init {
        acceptor.execute {
            while (!server.isClosed) {
                val socket = try { server.accept().apply { soTimeout = 15_000 } } catch (_: Exception) { break }
                sockets += socket
                try { workers.execute { serve(socket) } }
                catch (_: java.util.concurrent.RejectedExecutionException) { sockets -= socket; socket.close() }
            }
        }
    }

    fun register(id: String, handler: (Map<String, String>) -> Response): String {
        val path = "/$token/${tvBoxDigest(id)}/proxy"
        handlers[path] = handler
        return "http://127.0.0.1:${server.localPort}$path"
    }

    fun remove(id: String) { handlers.remove("/$token/${tvBoxDigest(id)}/proxy") }

    private fun serve(socket: Socket) {
        try {
            socket.use {
                val input = it.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                val line = input.readLine()?.takeIf { value -> value.length <= 8192 }?.split(' ') ?: return
                if (line.size != 3 || line[0] !in setOf("GET", "HEAD")) return
                var headerSize = 0
                while (true) {
                    val header = input.readLine() ?: return
                    headerSize += header.length
                    if (headerSize > 16_384) return
                    if (header.isEmpty()) break
                }
                val path = line[1].substringBefore('?')
                val params = line[1].substringAfter('?', "").split('&').filter(String::isNotBlank).associate { entry ->
                    URLDecoder.decode(entry.substringBefore('='), "UTF-8") to URLDecoder.decode(entry.substringAfter('=', ""), "UTF-8")
                }
                val response = try { handlers[path]?.invoke(params) ?: errorResponse(404, "Unknown spider") }
                catch (_: Exception) { errorResponse(502, "Spider proxy failed") }
                response.body.use { body ->
                    val output = it.getOutputStream().buffered()
                    val status = response.status.takeIf { code -> code in 100..599 } ?: 502
                    output.write("HTTP/1.0 $status TVBox\r\nContent-Type: ${clean(response.mime)}\r\nConnection: close\r\n".toByteArray())
                    response.headers.filterKeys { key -> key.lowercase() !in setOf("connection", "transfer-encoding", "content-length", "content-type") }
                        .forEach { (key, value) -> output.write("${clean(key)}: ${clean(value)}\r\n".toByteArray()) }
                    output.write("\r\n".toByteArray())
                    if (line[0] == "GET") body.copyTo(output)
                    output.flush()
                }
            }
        } catch (_: Exception) { socket.close() }
        finally { sockets -= socket }
    }

    private fun clean(value: String) = value.replace("\r", "").replace("\n", "")
    private fun errorResponse(status: Int, text: String) = Response(status, "text/plain", ByteArrayInputStream(text.toByteArray()))

    override fun close() {
        server.close()
        sockets.toList().forEach { runCatching { it.close() } }
        workers.shutdownNow()
        acceptor.shutdownNow()
        handlers.clear()
    }
}
