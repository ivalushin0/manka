package app.manka.core

import app.manka.store.Http
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Addresses that change with the blocks: blocked Meta ranges and reachable WhatsApp edges, Telegram's
 * direct front, Gemini's domains. They come from net/manka-net.json in the repository (fetched once
 * a day), so a new block needs no app update; the values below are the fallback.
 */
@Serializable
data class NetLists(
    val version: Int = 0,
    @SerialName("meta_nets") val metaNets: List<String> = emptyList(),
    @SerialName("meta_edges") val metaEdges: List<String> = emptyList(),
    @SerialName("tgws_dc_ips") val tgwsDcIps: List<String> = emptyList(),
    @SerialName("gemini_domains") val geminiDomains: List<String> = emptyList(),
) {
    /** Only well-formed entries: they end up in shell settings and proxy configs. */
    fun sanitized() = copy(
        metaNets = metaNets.filter { CIDR.matches(it) },
        metaEdges = metaEdges.filter { IP.matches(it) },
        tgwsDcIps = tgwsDcIps.filter { DC_IP.matches(it) },
        geminiDomains = geminiDomains.filter { DOMAIN.matches(it) },
    )

    /** Every list usable: a broken download must not empty a list. */
    val complete get() = metaNets.isNotEmpty() && metaEdges.isNotEmpty() && tgwsDcIps.isNotEmpty() && geminiDomains.isNotEmpty()

    companion object {
        private val IP = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")
        private val CIDR = Regex("""^\d{1,3}(\.\d{1,3}){3}/\d{1,2}$""")
        private val DC_IP = Regex("""^\d{1,3}:\d{1,3}(\.\d{1,3}){3}$""")
        private val DOMAIN = Regex("""^[a-z0-9-]+(\.[a-z0-9-]+)+$""")

        private const val URL = "https://raw.githubusercontent.com/ivalushin0/manka/main/net/manka-net.json"
        private const val URL2 = "https://github.com/ivalushin0/manka/raw/main/net/manka-net.json"
        private const val DAY = 24 * 60 * 60 * 1000L
        private val json = Json { ignoreUnknownKeys = true }

        val DEFAULT = NetLists(
            version = 1,
            metaNets = listOf(
                "31.13.24.0/21", "31.13.64.0/18", "157.240.0.0/16", "179.60.192.0/22", "185.60.216.0/22",
                "129.134.0.0/16", "163.70.128.0/17", "69.171.224.0/19", "66.220.144.0/20", "69.63.176.0/20",
                "173.252.64.0/18", "102.132.96.0/20", "45.64.40.0/22", "204.15.20.0/22",
            ),
            metaEdges = listOf(
                "57.144.249.32", "57.144.248.34", "57.144.173.32", "57.144.245.33", "57.144.244.34",
                "57.144.172.34", "57.144.249.33",
            ),
            tgwsDcIps = listOf("2:149.154.167.220", "4:149.154.167.220"),
            geminiDomains = ProxyConfig.GEMINI_DOMAINS,
        )

        fun parse(text: String): NetLists? =
            runCatching { json.decodeFromString(serializer(), text).sanitized() }.getOrNull()?.takeIf { it.complete }

        /** The stored lists, or the built-in ones. */
        fun current(prefs: Prefs): NetLists =
            parse(prefs.netLists)?.takeIf { it.version >= DEFAULT.version } ?: DEFAULT

        /** Fetches the lists at most once a day. true: they changed (the configuration should be rewritten). */
        suspend fun refresh(prefs: Prefs, force: Boolean = false): Boolean {
            if (!force && System.currentTimeMillis() - prefs.netListsTime < DAY) return false
            val text = runCatching { Http.text(URL) }.recoverCatching { Http.text(URL2) }.getOrNull() ?: return false
            prefs.netListsTime = System.currentTimeMillis()
            val fetched = parse(text) ?: return false
            if (fetched.version <= current(prefs).version) return false
            prefs.netLists = text
            return true
        }
    }
}
