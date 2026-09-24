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
        kv("EXCLUDE_UIDS", "\"" + uids.joinToString(" ") + "\"")
        kv("DEBUG", if (prefs.debugLogs) 1 else 0)
    }

    private fun tmp(name: String, content: String): File =
        File(context.cacheDir, name).apply { writeText(content) }

    private fun argsFile(name: String, args: List<String>) = tmp(name, args.joinToString("\n", postfix = "\n"))

    /** Writes the configuration and restarts the module. */
    suspend fun apply(): ModuleStatus {
        writeConfig()
        return Module.start()
    }

    /** Engine + active strategy of a network profile (inherited from the parent profile if not set). */
    fun profileConfig(key: String): EngineConfig {
        val engine = prefs.engine(key)
        return engineConfig(engine, presets.active(engine, key))
    }

    fun profileKeys(): List<String> = listOf(Profiles.WIFI, Profiles.MOBILE) + prefs.knownWifi.keys.sorted()

    suspend fun writeConfig() {
        val files = linkedMapOf(
            "${Paths.DATA}/settings.conf" to tmp("settings.conf", settingsConf(excludedUids())),
            "${Paths.ARGS}/tgws.args" to argsFile("tgws.args", tgwsArgs()),
        )
        for (key in profileKeys()) {
            val cfg = profileConfig(key)
            files["${Paths.PROFILES}/$key.conf"] = tmp("$key.conf", profileConf(key, cfg))
            files["${Paths.PROFILES}/$key.args"] = argsFile("$key.args", cfg.args)
        }
        // profiles of forgotten networks must disappear
        Root.exec("rm -rf ${Paths.PROFILES}")
        Module.copyIn(files)
        ensureExcludeList()
    }

    /** nfqws refuses to start when a referenced hostlist is missing. */
    suspend fun ensureExcludeList() {
        Root.exec(
            "[ -f ${Paths.EXCLUDE_LIST} ] || { mkdir -p ${Paths.LISTS}; cat ${Root.q(tmp("exclude.txt", DEFAULT_EXCLUDE).absolutePath)} > ${Paths.EXCLUDE_LIST}; }",
        )
    }

    suspend fun writeTestArgs(args: List<String>): String {
        val dest = "${Paths.ARGS}/test.args"
        Module.copyIn(mapOf(dest to argsFile("test.args", args)))
        return dest
    }

    companion object {
        val DEFAULT_EXCLUDE = listOf(
            "gosuslugi.ru", "nalog.gov.ru", "mos.ru", "sberbank.ru", "sber.ru", "tbank.ru", "tinkoff.ru",
            "vtb.ru", "alfabank.ru", "gazprombank.ru", "raiffeisen.ru", "psbank.ru", "sovcombank.ru",
            "yandex.ru", "ya.ru", "yandex.net", "yastatic.net", "vk.com", "vk.ru", "userapi.com", "vkuser.net",
            "mail.ru", "ok.ru", "ozon.ru", "wildberries.ru", "wb.ru", "avito.ru", "rzd.ru", "kinopoisk.ru",
        ).joinToString("\n", postfix = "\n")
    }
}
