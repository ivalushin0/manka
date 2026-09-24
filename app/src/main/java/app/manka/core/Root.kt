package app.manka.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Runs shell scripts as root. Every call gets its own `su` process, so a hung command never blocks the next one. */
object Root {
    data class Result(val code: Int, val out: String) {
        val ok get() = code == 0
        val lines get() = out.lines().filter { it.isNotBlank() }
    }

    suspend fun exec(script: String, timeoutSec: Long = 60): Result =
        withContext(Dispatchers.IO) { execBlocking(script, timeoutSec) }

    fun execBlocking(script: String, timeoutSec: Long = 60): Result {
        val process = try {
            ProcessBuilder("su").redirectErrorStream(true).start()
        } catch (e: Exception) {
            return Result(-1, e.message ?: "su not found")
        }
        val out = StringBuilder()
        val reader = thread(name = "su-reader") {
            try {
                process.inputStream.bufferedReader().forEachLine { line ->
                    synchronized(out) { out.appendLine(line) }
                }
            } catch (_: Exception) {
            }
        }
        try {
            process.outputStream.bufferedWriter().use { w ->
                w.write(script)
                w.write("\nexit \$?\n")
            }
        } catch (_: Exception) {
            // su refused the request and closed stdin
        }
        val finished = process.waitFor(timeoutSec, TimeUnit.SECONDS)
        if (!finished) {
            process.destroy()
            reader.join(1000)
            return Result(-2, synchronized(out) { out.toString() })
        }
        reader.join(2000)
        return Result(process.exitValue(), synchronized(out) { out.toString() })
    }

    suspend fun available(): Boolean = exec("id", 15).out.contains("uid=0")

    /** Single-quotes a value for sh. */
    fun q(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
