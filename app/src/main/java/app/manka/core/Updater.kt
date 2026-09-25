package app.manka.core

import android.content.Context
import app.manka.BuildConfig
import app.manka.store.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Self-update from GitHub releases: the rolling "latest" build carries a version.json next to the APK. */
object Updater {
    private const val BASE = "https://github.com/ivalushin0/manka/releases/download/latest"
    private const val RESULT = "/data/local/tmp/manka-update.result"
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class Info(val versionCode: Int, val versionName: String, val apk: String, val module: String? = null) {
        val newer get() = versionCode > BuildConfig.VERSION_CODE
        val apkUrl get() = "$BASE/$apk"
    }

    suspend fun check(): Info = json.decodeFromString(Info.serializer(), Http.text("$BASE/version.json"))

    /** Downloads the APK into the cache, reporting progress 0..1 (or -1 when the size is unknown). */
    suspend fun download(context: Context, info: Info, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val dest = File(context.cacheDir, "update/manka.apk")
        dest.parentFile?.mkdirs()
        var url = info.apkUrl
        var c: HttpURLConnection
        var hops = 0
        while (true) {
            c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            c.instanceFollowRedirects = false
            val code = c.responseCode
            if (code in 300..399) {
                url = URL(URL(url), c.getHeaderField("Location") ?: throw IOException("redirect without location")).toString()
                c.disconnect()
                if (++hops > 6) throw IOException("too many redirects")
                continue
            }
            if (code !in 200..299) {
                c.disconnect()
                throw IOException("HTTP $code")
            }
            break
        }
        try {
            val total = c.contentLengthLong
            c.inputStream.use { input ->
                dest.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var last = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (done - last > 128 * 1024) {
                            last = done
                            onProgress(if (total > 0) done.toFloat() / total else -1f)
                        }
                    }
                    if (total > 0 && done != total) throw IOException("incomplete download ($done/$total)")
                }
            }
            onProgress(1f)
            dest
        } finally {
            c.disconnect()
        }
    }

    /**
     * Installs the APK with root in a detached shell: installing over ourselves kills this process,
     * so the shell outlives it and starts the app again. Returns an error text, or null when the
     * install went through (normally we are killed before that).
     */
    suspend fun install(context: Context, apk: File): String? {
        apk.setReadable(true, false)
        val pkg = context.packageName
        val r = Root.exec(
            """
            T=/data/local/tmp/manka-update.apk
            R=$RESULT
            rm -f ${'$'}R
            cat ${Root.q(apk.absolutePath)} > ${'$'}T || { echo "copy failed"; exit 1; }
            chmod 0644 ${'$'}T
            setsid sh -c '
                out=${'$'}(pm install -r -d "${'$'}0" 2>&1); rc=${'$'}?
                rm -f "${'$'}0"
                if [ ${'$'}rc -eq 0 ]; then
                    am start -n ${pkg}/app.manka.MainActivity >/dev/null 2>&1
                else
                    echo "${'$'}out" > "${'$'}1"
                fi
            ' "${'$'}T" "${'$'}R" </dev/null >/dev/null 2>&1 &
            echo started
            """.trimIndent(), 30,
        )
        if (!r.out.contains("started")) return r.out.trim().ifBlank { "exit code ${r.code}" }
        // if the install fails we are still alive and the shell leaves the reason behind
        repeat(90) {
            kotlinx.coroutines.delay(2000)
            val res = Root.exec("[ -f $RESULT ] && { cat $RESULT; rm -f $RESULT; }", 15).out.trim()
            if (res.isNotEmpty()) return res.lines().lastOrNull { it.isNotBlank() } ?: "install failed"
        }
        return null
    }
}
