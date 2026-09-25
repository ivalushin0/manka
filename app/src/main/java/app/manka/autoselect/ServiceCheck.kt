package app.manka.autoselect

import app.manka.core.Prefs

/** Quick per-service availability check for the home screen (a few hosts of every app). */
object ServiceCheck {
    /** Target groups shown on the home screen, in this order. */
    val GROUPS = listOf("youtube", "instagram", "facebook", "tiktok", "discord")

    private const val HOSTS_PER_GROUP = 3

    /** Checks every group through the running bypass and stores the result in [prefs]. */
    suspend fun run(prefs: Prefs, timeoutSec: Int): Map<String, Int> {
        val groups = Targets.groups.filter { it.id in GROUPS }
        val urls = groups.associate { g -> g.id to g.urls.take(HOSTS_PER_GROUP) }
        val results = Checks.sites(urls.values.flatten().distinct(), 1, timeoutSec).associateBy { it.url }
        val status = GROUPS.mapNotNull { id ->
            val list = urls[id] ?: return@mapNotNull null
            val ok = list.count { (results[it]?.ok ?: 0) > 0 }
            id to ok * 100 / list.size.coerceAtLeast(1)
        }.toMap()
        prefs.serviceStatus = status
        prefs.serviceStatusTime = System.currentTimeMillis()
        return status
    }
}
