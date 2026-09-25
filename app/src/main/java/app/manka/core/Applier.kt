package app.manka.core

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.File

/** Turns app settings + presets into the files the module reads, then (re)starts it. */
class Applier(
    private val context: Context,
    private val prefs: Prefs,
    private val presets: PresetRepository,
) {
    data class EngineConfig(val args: List<String>, val tcpPorts: String, val udpPorts: String)

    fun engineConfig(engine: Engine, preset: Preset): EngineConfig {
        val rendered = PresetRenderer.render(preset, prefs)
        val args = Args.resolve(
            rendered.args,
            kitDir = preset.kitId?.let { "${Paths.KITS}/$it" },
            fakeSni = prefs.fakeSni,
        )
        val tcp = Args.ports(rendered.tcpPorts ?: prefs.tcpPorts).ifEmpty { "80,443" }
        var udp = Args.ports(rendered.udpPorts ?: prefs.udpPorts)
        if (prefs.blockQuic) udp = udp.split(',').filter { it != "443" && it.isNotBlank() }.joinToString(",")
        return EngineConfig(args, tcp, udp)
    }

    /** UIDs for APP_UIDS; in whitelist mode Manka itself is included so its health checks see the bypass. */
    fun appUids(): List<Int> =
        if (prefs.appsOnly) (excludedUids() + android.os.Process.myUid()).distinct() else excludedUids()

    fun excludedUids(): List<Int> {
        val pm = context.packageManager
        return prefs.excludedPackages.mapNotNull { pkg ->
            runCatching {
                if (Build.VERSION.SDK_INT >= 33) {
                    pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0)).uid
                } else {
                    @Suppress("DEPRECATION") pm.getApplicationInfo(pkg, 0).uid
                }
            }.getOrNull()
        }.distinct().sorted()
    }

    fun tgwsArgs(): List<String> {
        val out = mutableListOf("--secret", prefs.tgwsSecret, "--pool-size", prefs.tgwsPoolSize.toString())
        if (!prefs.debugLogs) out += "-q"
        if (prefs.tgwsCloudflare) out += "--default-domains"
        prefs.tgwsDcIps.split(',', ' ', '\n').map { it.trim() }.filter { it.matches(Regex("""\d+:[0-9a-fA-F.:]+""")) }
            .forEach { out += listOf("--dc-ip", it) }
        out += Args.split(prefs.tgwsExtraArgs)
        return out
    }

    private fun profileConf(key: String, cfg: EngineConfig) = buildString {
        appendLine("ENGINE=${prefs.engine(key).id}")
        appendLine("TCP_PORTS=${cfg.tcpPorts}")
        appendLine("UDP_PORTS=${cfg.udpPorts}")
    }

    private fun settingsConf(uids: List<Int>): String = buildString {
        fun kv(k: String, v: Any) = appendLine("$k=$v")
        kv("ENABLED", if (prefs.enabled) 1 else 0)
        kv("TGWS", if (prefs.tgws) 1 else 0)
        kv("TGWS_PORT", prefs.tgwsPort)
        kv("BYEDPI_PORTS", Args.ports(prefs.byedpiPorts).ifEmpty { "80,443" })
        kv("BLOCK_QUIC", if (prefs.blockQuic) 1 else 0)
        kv("IPV6", if (prefs.ipv6) 1 else 0)
        kv("APPS_MODE", if (prefs.appsOnly) "only" else "exclude")
        kv("APP_UIDS", "\"" + uids.joinToString(" ") + "\"")
        kv("DEBUG", if (prefs.debugLogs) 1 else 0)
        kv("HOTSPOT", if (prefs.hotspot) 1 else 0)
        val dns = prefs.dnsServer.trim()
        val doh = DOH[dns]
        kv("DNS_MODE", if (doh != null) "doh" else if (IPV4.matches(dns)) "plain" else "system")
        // for doh: the plain server of the same provider, used if dnsproxy cannot start
        kv("DNS_SERVER", doh?.plain ?: dns.takeIf { IPV4.matches(it) }.orEmpty())
        kv("DNS_DOH", "\"" + doh?.urls.orEmpty().joinToString(" ") + "\"")
    }

    private fun tmp(name: String, content: String): File =
        File(context.cacheDir, name).apply { writeText(content) }

    private fun argsFile(name: String, args: List<String>) = tmp(name, args.joinToString("\n", postfix = "\n"))

    /** Writes the configuration and restarts the module. */
    suspend fun apply(): ModuleStatus {
        writeConfig()
        return Module.start()
    }

    /**
     * Engine + active strategy of a network profile (inherited from the parent profile if not set),
     * with the per-service strategies and the Discord voice profile merged in for zapret / zapret2.
     */
    fun profileConfig(key: String): EngineConfig {
        val engine = prefs.engine(key)
        val main = engineConfig(engine, presets.active(engine, key))
        if (engine == Engine.BYEDPI) return main
        val overrides = Services.all.mapNotNull { s -> prefs.servicePreset(engine, key, s.id)?.let { s to it } }
        val voice = prefs.discordVoice
        if (overrides.isEmpty() && !voice) return main

        val scoped = overrides.mapNotNull { (s, id) ->
            if (id == Services.OFF) return@mapNotNull null
            val p = presets.byId(id)?.takeIf { it.engine == engine } ?: return@mapNotNull null
            val cfg = engineConfig(engine, p)
            Services.Scoped(Services.listPath(s.id), Services.Part(cfg.args, cfg.tcpPorts, cfg.udpPorts), s.keepVoice)
        }
        val merged = Services.merge(
            main = Services.Part(main.args, main.tcpPorts, main.udpPorts),
            services = scoped,
            // a service with its own strategy (or switched off) is taken out of the main strategy
            excluded = overrides.map { Services.listPath(it.first.id) },
            extra = if (voice) listOf(Services.voiceArgs(engine)) else emptyList(),
        )
        val udp = if (voice) Args.ports(merged.udpPorts + "," + Services.VOICE_PORTS) else merged.udpPorts
        return EngineConfig(merged.args, merged.tcpPorts.ifEmpty { "80,443" }, udp)
    }

    fun profileKeys(): List<String> = listOf(Profiles.WIFI, Profiles.MOBILE) + prefs.knownWifi.keys.sorted()

    suspend fun writeConfig() {
        presets.awaitLoaded()
        val files = linkedMapOf(
            "${Paths.DATA}/settings.conf" to tmp("settings.conf", settingsConf(appUids())),
            "${Paths.ARGS}/tgws.args" to argsFile("tgws.args", tgwsArgs()),
        )
        for (s in Services.all) {
            files[Services.listPath(s.id)] = tmp("svc-${s.id}.txt", Services.listContent(s))
        }
        for (key in profileKeys()) {
            val cfg = profileConfig(key)
            files["${Paths.PROFILES}/$key.conf"] = tmp("$key.conf", profileConf(key, cfg))
            files["${Paths.PROFILES}/$key.args"] = argsFile("$key.args", cfg.args)
        }
        // profiles of forgotten networks must disappear; one root call for everything
        val r = Module.copyIn(files, before = "rm -rf ${Paths.PROFILES}", after = excludeListScript())
        if (!r.ok) throw java.io.IOException(r.out.lines().lastOrNull { it.isNotBlank() } ?: "cannot write the configuration")
    }

    /** nfqws refuses to start when a referenced hostlist is missing. */
    private fun excludeListScript() =
        "[ -f ${Paths.EXCLUDE_LIST} ] || { mkdir -p ${Paths.LISTS}; cat ${Root.q(tmp("exclude.txt", DEFAULT_EXCLUDE).absolutePath)} > ${Paths.EXCLUDE_LIST}; }"

    suspend fun writeTestArgs(args: List<String>): String {
        val dest = "${Paths.ARGS}/test.args"
        Module.copyIn(mapOf(dest to argsFile("test.args", args)))
        return dest
    }

    companion object {
        data class Doh(val plain: String, val urls: List<String>)

        /** DNS-over-HTTPS by IP address: no bootstrap DNS needed, the certificates cover the IPs. */
        val DOH = mapOf(
            "doh:google" to Doh("8.8.8.8", listOf("https://8.8.8.8/dns-query", "https://8.8.4.4/dns-query")),
            "doh:cloudflare" to Doh("1.1.1.1", listOf("https://1.1.1.1/dns-query", "https://1.0.0.1/dns-query")),
            "doh:quad9" to Doh("9.9.9.9", listOf("https://9.9.9.9/dns-query", "https://149.112.112.112/dns-query")),
        )

        val IPV4 = Regex("""^(25[0-5]|2[0-4]\d|1?\d?\d)(\.(25[0-5]|2[0-4]\d|1?\d?\d)){3}$""")

        val DEFAULT_EXCLUDE = listOf(
            "gosuslugi.ru", "nalog.gov.ru", "mos.ru", "sberbank.ru", "sber.ru", "tbank.ru", "tinkoff.ru",
            "vtb.ru", "alfabank.ru", "gazprombank.ru", "raiffeisen.ru", "psbank.ru", "sovcombank.ru",
            "yandex.ru", "ya.ru", "yandex.net", "yastatic.net", "vk.com", "vk.ru", "userapi.com", "vkuser.net",
            "mail.ru", "ok.ru", "ozon.ru", "wildberries.ru", "wb.ru", "avito.ru", "rzd.ru", "kinopoisk.ru",
        ).joinToString("\n", postfix = "\n")
    }
}
