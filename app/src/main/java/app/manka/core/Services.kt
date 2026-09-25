package app.manka.core

/**
 * Per-service strategies for zapret / zapret2: every service can get its own strategy, limited to
 * the service's domains, while the main strategy of the profile handles everything else.
 */
data class Service(
    val id: String,
    val title: String,
    /** Hostlist of the service (subdomains match too). */
    val domains: List<String>,
    /** Auto selection target groups (see Targets) that check the service. */
    val targetGroups: List<String>,
    /** Keep hostname-less UDP profiles (voice) of the service strategy. */
    val keepVoice: Boolean = false,
)

object Services {
    /** Stored instead of a preset id: the service is not processed at all. */
    const val OFF = "off"

    val all = listOf(
        Service(
            "youtube", "YouTube",
            listOf(
                "youtube.com", "youtu.be", "youtube-nocookie.com", "youtubekids.com", "googlevideo.com",
                "ytimg.com", "ggpht.com", "youtubei.googleapis.com", "youtube.googleapis.com",
                "youtubeembeddedplayer.googleapis.com", "yt3.googleusercontent.com", "jnn-pa.googleapis.com",
                "wide-youtube.l.google.com", "youtube-ui.l.google.com", "yt-video-upload.l.google.com", "ytimg.l.google.com",
            ),
            listOf("youtube"),
        ),
        Service(
            "meta", "Instagram / Facebook",
            listOf(
                "instagram.com", "cdninstagram.com", "instagr.am", "ig.me", "igsonar.com", "igcdn.com",
                "facebook.com", "facebook.net", "fbcdn.net", "fb.com", "fb.me", "fbsbx.com", "messenger.com",
                "threads.net", "threads.com",
            ),
            listOf("instagram", "facebook"),
        ),
        Service(
            "tiktok", "TikTok",
            listOf(
                "tiktok.com", "tiktokv.com", "tiktokcdn.com", "tiktokcdn-us.com", "tiktokcdn-eu.com", "ttwstatic.com",
                "tiktokw.us", "byteoversea.com", "byteoversea.net", "ibytedtos.com", "ibyteimg.com", "ipstatp.com",
                "muscdn.com", "musical.ly", "tiktokd.org", "tiktokd.net", "tik-tokapi.com",
            ),
            listOf("tiktok"),
        ),
        Service(
            "discord", "Discord",
            listOf(
                "discord.com", "discord.gg", "discord.app", "discord.co", "discord.dev", "discord.gift", "discord.new",
                "discord.media", "discordapp.com", "discordapp.net", "discordcdn.com", "discordstatus.com", "dis.gd",
                "discord-attachments-uploads-prd.storage.googleapis.com",
            ),
            listOf("discord"),
            keepVoice = true,
        ),
    )

    fun byId(id: String?): Service? = all.firstOrNull { it.id == id }

    fun listPath(id: String) = "${Paths.LISTS}/svc-$id.txt"

    fun listContent(s: Service) = s.domains.joinToString("\n", postfix = "\n")

    // ------------------------------------------------------------------ Discord voice (UDP)

    const val VOICE_PORTS = "19294:19344,50000:50100"

    /** Profile for Discord voice / STUN (the same idea as Flowseal's configs). */
    fun voiceArgs(engine: Engine): List<String> = when (engine) {
        Engine.ZAPRET -> listOf(
            "--filter-udp=19294-19344,50000-50100", "--filter-l7=discord,stun",
            "--dpi-desync=fake", "--dpi-desync-repeats=6",
        )
        Engine.ZAPRET2 -> listOf(
            "--filter-udp=19294-19344,50000-50100", "--filter-l7=discord,stun",
            "--payload=discord_ip_discovery,stun",
            "--lua-desync=fake:blob=0x00000000000000000000000000000000:repeats=6",
        )
        Engine.BYEDPI -> emptyList()
    }

    // ------------------------------------------------------------------ merging command lines

    data class Part(val args: List<String>, val tcpPorts: String, val udpPorts: String)

    /** A service strategy limited to the hostlist at [list]. */
    data class Scoped(val list: String, val part: Part, val keepVoice: Boolean)

    /** Options that select traffic by host / address: replaced by the service hostlist. */
    private val SCOPE = listOf(
        "--hostlist=", "--hostlist-domains=", "--hostlist-exclude=", "--hostlist-exclude-domains=",
        "--hostlist-auto=", "--hostlist-auto-", "--ipset=", "--ipset-ip=", "--ipset-exclude=", "--ipset-exclude-ip=",
    )

    /** Options that are global for the whole process and must appear once. */
    private val GLOBAL = listOf(
        "--blob=", "--lua-init=", "--ctrack-timeouts=", "--ctrack-disable", "--ipcache-lifetime=",
        "--ipcache-hostname", "--writeable",
    )

    private fun isScope(a: String) = SCOPE.any { a.startsWith(it) }
    private fun isGlobal(a: String) = GLOBAL.any { a == it || a.startsWith(it) }

    private fun blocks(args: List<String>): List<List<String>> {
        val out = mutableListOf<MutableList<String>>(mutableListOf())
        for (a in args) if (a == "--new") out += mutableListOf<String>() else out.last() += a
        return out.filter { b -> b.any { !isGlobal(it) } }
    }

    /** UDP-only profile for ports other than 443 without any host filter: voice, games. */
    private fun isHostless(block: List<String>): Boolean {
        if (block.any { isScope(it) }) return false
        if (block.any { it.startsWith("--filter-tcp=") }) return false
        val udp = block.firstOrNull { it.startsWith("--filter-udp=") } ?: return false
        return udp.substringAfter('=').split(',').none { it == "443" }
    }

    /**
     * Services first (limited to their hostlists), then [main] with every service in [excluded]
     * taken out. nfqws uses the first matching profile, so the order matters.
     */
    fun merge(main: Part?, services: List<Scoped>, excluded: List<String>, extra: List<List<String>> = emptyList()): Part {
        val globals = LinkedHashSet<String>()
        val out = mutableListOf<List<String>>()
        fun collectGlobals(args: List<String>) = args.filter { isGlobal(it) }.forEach { globals += it }

        for (s in services) {
            collectGlobals(s.part.args)
            for (b in blocks(s.part.args)) {
                val body = b.filter { !isGlobal(it) }
                if (isHostless(body)) {
                    if (s.keepVoice) out += body
                    continue
                }
                out += body.filter { !isScope(it) } + "--hostlist=${s.list}"
            }
        }
        if (main != null) {
            collectGlobals(main.args)
            for (b in blocks(main.args)) {
                out += b.filter { !isGlobal(it) } + excluded.map { "--hostlist-exclude=$it" }
            }
        }
        out += extra.filter { it.isNotEmpty() }

        val args = globals.toList() + out.flatMapIndexed { i, b -> if (i == 0) b else listOf("--new") + b }
        val tcp = Args.ports((services.map { it.part.tcpPorts } + listOfNotNull(main?.tcpPorts)).joinToString(","))
        val udp = Args.ports((services.map { it.part.udpPorts } + listOfNotNull(main?.udpPorts)).joinToString(","))
        return Part(args, tcp, udp)
    }
}
