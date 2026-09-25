package app.manka.core

import app.manka.autoselect.SiteChecker
import app.manka.store.Http
import app.manka.store.KitLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Network diagnostics for a site that does not open in its app: what the system DNS returns vs
 * DNS-over-HTTPS, whether IPv6 is in play and which of the app's real hosts answer through the bypass.
 */
object NetDiag {
    val INSTAGRAM = listOf(
        "www.instagram.com",
        "i.instagram.com",
        "graph.instagram.com",
        "static.cdninstagram.com",
        "scontent.cdninstagram.com",
        "edge-mqtt.facebook.com",
        "graph.facebook.com",
        "static.xx.fbcdn.net",
    )

    suspend fun run(hosts: List<String>, timeoutSec: Int, out: (String) -> Unit) {
        out("===== diagnostics")
        val sys = Root.exec(
            """
            echo "private_dns_mode=$(settings get global private_dns_mode)"
            echo "private_dns_specifier=$(settings get global private_dns_specifier)"
            echo "ipv6_default_route=$(ip -6 route show default 2>/dev/null | head -1)"
            echo "ipv6_global_addr=$(ip -6 addr show scope global 2>/dev/null | grep -c inet6)"
            for p in $(ls ${Paths.PROFILES}/*.args 2>/dev/null); do echo "--- $(basename ${'$'}p)"; tr '\n' ' ' < ${'$'}p; echo; done
            echo "--- nat MANKA_DNS"; iptables -t nat -S MANKA_DNS 2>&1 | head -8
            echo "--- filter MANKA_FLTQ"; iptables -t filter -S MANKA_FLTQ 2>&1 | head -8
            echo "--- ip6 filter MANKA_FLTQ"; ip6tables -t filter -S MANKA_FLTQ 2>&1 | head -8
            """.trimIndent(), 30,
        )
        out(sys.out.trim())

        for (host in hosts) {
            out("")
            out("### $host")
            val system = resolve(host)
            out("system DNS: " + system.fold({ l -> l.joinToString(" ").ifEmpty { "-" } }, { "error " + it.message }))
            val doh4 = doh(host, 1)
            val doh6 = doh(host, 28)
            out("DoH dns.google: " + (doh4 + doh6).joinToString(" ").ifEmpty { "-" })
            system.getOrNull()?.let { addrs ->
                val v4 = addrs.filter { it.contains('.') }
                if (v4.isNotEmpty() && doh4.isNotEmpty() && v4.none { it in doh4 } && v4.none { same24(it, doh4) }) {
                    out("!! system DNS answer differs from DoH (substituted DNS?)")
                }
                if (v4.any { isStub(it) }) out("!! system DNS returned a private/stub address")
            }
            val checks = SiteChecker().check(listOf("https://$host/"), 1, timeoutSec)
            val r = checks.first()
            out("HTTPS via bypass: " + if (r.ok > 0) "OK ${r.avgMs} ms" else "FAIL ${r.error}")
            val v6 = system.getOrNull()?.firstOrNull { it.contains(':') } ?: doh6.firstOrNull()
            if (v6 != null) out("IPv6 TCP $v6: " + tcp(v6, timeoutSec))
        }
        out("")
        out("===== end")
    }

    private suspend fun resolve(host: String): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching {
            InetAddress.getAllByName(host).map { a ->
                when (a) {
                    is Inet4Address, is Inet6Address -> a.hostAddress.orEmpty().substringBefore('%')
                    else -> a.hostAddress.orEmpty()
                }
            }.distinct()
        }
    }

    private suspend fun doh(host: String, type: Int): List<String> = runCatching {
        val text = Http.text("https://dns.google/resolve?name=$host&type=$type")
        KitLoader.json.parseToJsonElement(text).jsonObject["Answer"]?.jsonArray.orEmpty()
            .map { it.jsonObject }
            .filter { it["type"]?.jsonPrimitive?.intOrNull == type }
            .mapNotNull { it["data"]?.jsonPrimitive?.contentOrNull }
    }.getOrDefault(emptyList())

    private suspend fun tcp(ip: String, timeoutSec: Int): String = withContext(Dispatchers.IO) {
        val t0 = System.nanoTime()
        try {
            Socket().use { it.connect(InetSocketAddress(InetAddress.getByName(ip), 443), timeoutSec * 1000) }
            "connected ${(System.nanoTime() - t0) / 1_000_000} ms"
        } catch (e: Exception) {
            e.javaClass.simpleName + (e.message?.let { ": " + it.take(60) } ?: "")
        }
    }

    private fun same24(ip: String, others: List<String>) = others.any { it.substringBeforeLast('.') == ip.substringBeforeLast('.') }

    private fun isStub(ip: String): Boolean {
        val p = ip.split('.').mapNotNull { it.toIntOrNull() }
        if (p.size != 4) return false
        return p[0] == 10 || p[0] == 127 || p[0] == 0 || (p[0] == 192 && p[1] == 168) ||
            (p[0] == 172 && p[1] in 16..31) || (p[0] == 100 && p[1] in 64..127)
    }
}
