package app.manka.autoselect

import app.manka.core.Root

/**
 * Site checks made by curl as the shell user (uid 2000) through root. Android cuts the network
 * of apps in the background (blocked=APP_BACKGROUND), so checks from Manka's own process fail there;
 * the shell user is not an app and is never restricted. Its traffic goes through the bypass like any
 * app (in "only selected apps" mode Manka adds it to the list).
 */
object RootChecker {
    const val UID = 2000

    @Volatile private var hasCurl: Boolean? = null

    suspend fun available(): Boolean =
        hasCurl ?: Root.exec("command -v curl >/dev/null 2>&1 && echo yes", 15).out.contains("yes").also { hasCurl = it }

    /** Same result shape as [SiteChecker.check]; all sites are checked in parallel in one root call. */
    suspend fun check(urls: List<String>, requests: Int, timeoutSec: Int, socksPort: Int? = null): List<SiteResult> {
        if (urls.isEmpty()) return emptyList()
        val proxy = socksPort?.let { "--socks5 127.0.0.1:$it" }.orEmpty()
        val jobs = urls.mapIndexed { i, url ->
            // one line per request: index code seconds exit
            "( for n in \$(seq $requests); do " +
                "r=\$(curl -s -o /dev/null $proxy -m $timeoutSec --connect-timeout $timeoutSec -A Mozilla/5.0 " +
                "-w '%{http_code} %{time_total}' ${Root.q(url)}); echo \"$i \$r \$?\"; done ) &"
        }
        val script = jobs.joinToString("\n") + "\nwait"
        // 3003 = inet: without it Android refuses the process any network access
        val out = Root.exec("su $UID -G 3003 -c ${Root.q(script)}", (timeoutSec + 5L) * requests + 20).out
        val lines = out.lines().mapNotNull { l ->
            val p = l.trim().split(' ')
            if (p.size < 4) null else p[0].toIntOrNull()?.let { it to p }
        }.groupBy({ it.first }, { it.second })
        return urls.mapIndexed { i, url ->
            val runs = lines[i].orEmpty()
            val good = runs.filter { it[3] == "0" && it[1] != "000" }
            val avg = good.mapNotNull { it[2].toDoubleOrNull() }.average().takeIf { !it.isNaN() }?.let { (it * 1000).toLong() } ?: 0
            val error = runs.firstOrNull { it !in good }?.let { r -> "curl exit ${r[3]} (HTTP ${r[1]})" }
            SiteResult(url, good.size, requests, avg, if (good.size < requests) error ?: "no answer" else null)
        }
    }
}

/** Site checks for code that may run in the background: through root when possible, else in-process. */
object Checks {
    suspend fun sites(urls: List<String>, requests: Int, timeoutSec: Int, socksPort: Int? = null): List<SiteResult> =
        if (RootChecker.available()) RootChecker.check(urls, requests, timeoutSec, socksPort)
        else SiteChecker(socksPort).check(urls, requests, timeoutSec)
}
