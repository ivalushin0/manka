package app.manka.store

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

object Http {
    private const val UA = "Manka (+https://github.com)"

    private fun open(url: String): HttpURLConnection {
        var current = url
        repeat(6) {
            val c = URL(current).openConnection() as HttpURLConnection
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", UA)
            // version.json / store index must not come from a stale cache
            c.useCaches = false
            c.setRequestProperty("Cache-Control", "no-cache")
            if (current.startsWith("https://api.github.com")) c.setRequestProperty("Accept", "application/vnd.github+json")
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location") ?: throw IOException("redirect without location")
                c.disconnect()
                current = URL(URL(current), loc).toString()
                return@repeat
            }
            if (code !in 200..299) {
                c.disconnect()
                throw IOException("HTTP $code for $current")
            }
            return c
        }
        throw IOException("too many redirects")
    }

    suspend fun text(url: String): String = withContext(Dispatchers.IO) {
        val c = open(url)
        try { c.inputStream.bufferedReader().readText() } finally { c.disconnect() }
    }

    suspend fun download(url: String, dest: File): File = withContext(Dispatchers.IO) {
        val c = open(url)
        try {
            dest.parentFile?.mkdirs()
            val tmp = File(dest.path + ".part")
            c.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
            }
            dest
        } finally {
            c.disconnect()
        }
    }
}

object Zip {
    /** Extracts [zip] into [dir] (wiped first). Guards against path traversal. */
    fun extract(zip: java.io.InputStream, dir: File, filter: (String) -> Boolean = { true }) {
        dir.deleteRecursively()
        dir.mkdirs()
        val root = dir.canonicalPath + File.separator
        ZipInputStream(zip).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                val name = e.name.replace('\\', '/')
                if (!filter(name)) continue
                val out = File(dir, name)
                if (!out.canonicalPath.startsWith(root)) continue
                if (e.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { z.copyTo(it) }
                }
            }
        }
    }
}
