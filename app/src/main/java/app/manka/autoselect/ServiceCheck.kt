package app.manka.autoselect

import app.manka.MankaApp
import app.manka.core.Applier
import app.manka.core.Module
import app.manka.core.Root

/**
 * Quick availability check for the home screen: a few hosts of every app through the running
 * bypass, plus the services Manka reaches another way (WhatsApp through its remapped address,
 * Telegram through TG WS Proxy, Gemini through the user's server).
 */
object ServiceCheck {
    /** Groups checked through the bypass (they have own strategies), in this order. */
    val GROUPS = listOf("youtube", "instagram", "facebook", "tiktok", "discord")

    /** The other services, each shown only while its feature is on. */
    const val WHATSAPP = "whatsapp"
    const val TELEGRAM = "telegram"
    const val GEMINI = "gemini"
    val EXTRA = listOf(WHATSAPP, TELEGRAM, GEMINI)

    private const val HOSTS_PER_GROUP = 3
    private val WHATSAPP_URLS = listOf("https://web.whatsapp.com/", "https://mmg.whatsapp.net/", "https://g.whatsapp.net/")

    /** Checks everything and stores the shares of hosts that opened and the failing hosts. */
    suspend fun run(app: MankaApp, timeoutSec: Int): Map<String, Int> {
        val prefs = app.prefs
        val status = Module.status()
        val rates = LinkedHashMap<String, Int>()
        val failed = LinkedHashMap<String, List<String>>()

        val groups = Targets.groups.filter { it.id in GROUPS }
        val urls = groups.associate { g -> g.id to g.urls.take(HOSTS_PER_GROUP) }
        val results = Checks.sites(urls.values.flatten().distinct(), 1, timeoutSec).associateBy { it.url }
        for (id in GROUPS) {
            val list = urls[id] ?: continue
            val bad = list.filter { (results[it]?.ok ?: 0) == 0 }
            rates[id] = (list.size - bad.size) * 100 / list.size.coerceAtLeast(1)
            failed[id] = bad.map(::host)
        }

        // WhatsApp: as its own connections go, to the remapped address when the remap is on
        if (app.applier.uidsOf(Applier.WHATSAPP).isNotEmpty() && RootChecker.available()) {
            val connectTo = status.metaIp?.let { "--connect-to ::$it:" }.orEmpty()
            val r = RootChecker.check(WHATSAPP_URLS, 1, timeoutSec, extraArgs = connectTo)
            val bad = r.filter { it.ok == 0 }
            rates[WHATSAPP] = (r.size - bad.size) * 100 / r.size.coerceAtLeast(1)
            failed[WHATSAPP] = bad.map { host(it.url) }
        }
        if (prefs.tgws) {
            val ok = status.tgwsRunning && Module.run("tgws-probe", 60).out.lines().any { it.trim() == "ok" }
            rates[TELEGRAM] = if (ok) 100 else 0
            failed[TELEGRAM] = emptyList()
        }
        if (prefs.proxy && status.proxyRunning) {
            val (ok, country) = gemini(app)
            rates[GEMINI] = if (ok) 100 else 0
            failed[GEMINI] = emptyList()
            if (country != null) prefs.proxyCountry = country
        }

        prefs.serviceStatus = rates
        prefs.serviceFailed = failed
        prefs.serviceStatusTime = System.currentTimeMillis()
        return rates
    }

    /**
     * As a proxied app (the Google app if it is one): Gemini answers and the exit is not Russian.
     * Returns the check and the exit country.
     */
    private suspend fun gemini(app: MankaApp): Pair<Boolean, String?> {
        val apps = app.prefs.proxyApps
        val google = "com.google.android.googlequicksearchbox"
        val uid = app.applier.uidsOf(if (google in apps) setOf(google) else apps).firstOrNull() ?: return false to null
        val out = Root.exec(
            "su $uid -G 3003 -c " + Root.q(
                "echo country=\$(curl -s -m 12 https://ipinfo.io/country); " +
                    "echo code=\$(curl -s -o /dev/null -m 12 -w '%{http_code}' https://gemini.google.com/app)",
            ),
            40,
        ).out
        val country = Regex("country=([A-Z]{2})").find(out)?.groupValues?.get(1)
        val code = Regex("code=(\\d+)").find(out)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        return (code in 200..399 && country != null && country != "RU") to country
    }

    private fun host(url: String) = url.substringAfter("://").substringBefore('/')
}
