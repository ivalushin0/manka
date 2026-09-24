package app.manka.autoselect

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL

@Serializable
data class SiteResult(
    val url: String,
    val ok: Int,
    val total: Int,
    val avgMs: Long,
    val error: String? = null,
)

/**
 * Checks HTTPS availability the way a browser would see it: TLS handshake, response and the first
 * 64 KiB of the body (catches the "freeze after 16-20 KB" type of blocking).
 */
class SiteChecker(private val socksPort: Int? = null) {

    suspend fun check(
        urls: List<String>,
        requests: Int,
        timeoutSec: Int,
        concurrency: Int = 8,
        onSite: (SiteResult) -> Unit = {},
    ): List<SiteResult> = coroutineScope {
        val gate = Semaphore(concurrency)
        urls.map { url ->
            async(Dispatchers.IO) {
                gate.withPermit {
                    var ok = 0
                    var totalMs = 0L
                    var lastError: String? = null
                    repeat(requests) {
                        val t0 = System.nanoTime()
                        val err = once(url, timeoutSec)
                        if (err == null) {
                            ok++
                            totalMs += (System.nanoTime() - t0) / 1_000_000
                        } else {
                            lastError = err
                        }
                    }
                    SiteResult(url, ok, requests, if (ok > 0) totalMs / ok else 0, if (ok < requests) lastError else null)
                        .also(onSite)
                }
            }
        }.awaitAll()
    }

    /** Returns null on success or a short error description. */
    private suspend fun once(site: String, timeoutSec: Int): String? = withContext(Dispatchers.IO) {
        val url = if (site.startsWith("http://") || site.startsWith("https://")) site else "https://$site"
        var c: HttpURLConnection? = null
        try {
            val u = URL(url)
            c = (if (socksPort != null) {
                u.openConnection(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort)))
            } else {
                u.openConnection()
            }) as HttpURLConnection
            c.connectTimeout = timeoutSec * 1000
            c.readTimeout = timeoutSec * 1000
            c.instanceFollowRedirects = false
            c.useCaches = false
            c.setRequestProperty("Connection", "close")
            c.setRequestProperty("Accept-Encoding", "identity")
            c.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36",
            )
            val code = c.responseCode
            val declared = c.contentLengthLong
            val limit = if (declared in 1..LIMIT) declared else LIMIT
            val stream = (if (code >= 400) c.errorStream else c.inputStream) ?: return@withContext null
            stream.use { s ->
                val buf = ByteArray(8192)
                var read = 0L
                while (read < limit) {
                    val n = s.read(buf, 0, minOf(buf.size.toLong(), limit - read).toInt())
                    if (n < 0) break
                    read += n
                }
                if (declared > 0 && read < minOf(declared, LIMIT)) return@withContext "truncated ($read/$declared)"
            }
            null
        } catch (e: Exception) {
            e.javaClass.simpleName + (e.message?.let { ": " + it.take(80) } ?: "")
        } finally {
            c?.disconnect()
        }
    }

    companion object {
        private const val LIMIT = 65536L
    }
}
