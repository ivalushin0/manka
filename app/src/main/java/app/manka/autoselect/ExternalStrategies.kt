package app.manka.autoselect

import android.content.Context
import app.manka.store.Http
import java.io.File

/**
 * Strategy lists maintained by other projects, downloaded at run time (not bundled:
 * ByeByeDPI is GPL-3.0, Manka is MIT). Cached for a week, the cache is used offline.
 */
object ExternalStrategies {
    const val BYEBYEDPI_URL =
        "https://raw.githubusercontent.com/romanvht/ByeByeDPI/master/app/src/main/assets/proxytest_strategies.list"
    private const val MAX_AGE_MS = 7L * 24 * 3600 * 1000

    suspend fun byeByeDpi(context: Context): List<String> {
        val cache = File(context.filesDir, "external/byebyedpi.list")
        val fresh = cache.exists() && System.currentTimeMillis() - cache.lastModified() < MAX_AGE_MS
        if (!fresh) {
            runCatching {
                val text = Http.text(BYEBYEDPI_URL)
                if (text.lines().any { it.trim().startsWith("-") }) {
                    cache.parentFile?.mkdirs()
                    cache.writeText(text)
                }
            }
        }
        if (!cache.exists()) return emptyList()
        return cache.readLines().map { it.trim() }.filter { it.startsWith("-") }.distinct()
    }
}
