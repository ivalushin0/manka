package app.manka.core

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.security.SecureRandom

/** App settings. [version] ticks on every change so Compose can observe it. */
class Prefs(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("manka", Context.MODE_PRIVATE)
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version

    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> _version.value++ }

    init {
        sp.registerOnSharedPreferenceChangeListener(listener)
    }

    private fun str(key: String, def: String) = sp.getString(key, def) ?: def

    var enabled: Boolean
        get() = sp.getBoolean("enabled", false)
        set(v) = sp.edit { putBoolean("enabled", v) }

    // ---- network profiles (see Profiles). A profile without own settings inherits its parent's.
    private fun sfx(key: String) = if (key == Profiles.WIFI) "" else "_$key"

    fun hasOwnEngine(key: String) = sp.contains("engine${sfx(key)}")

    fun engine(key: String): Engine = Engine.of(sp.getString("engine${sfx(key)}", null))
        ?: Profiles.parent(key)?.let { engine(it) }
        ?: Engine.BYEDPI

    fun setEngine(key: String, engine: Engine) = sp.edit { putString("engine${sfx(key)}", engine.id) }

    fun activePreset(engine: Engine, key: String): String? =
        sp.getString("active_${engine.id}${sfx(key)}", null)
            ?: Profiles.parent(key)?.let { activePreset(engine, it) }

    fun setActivePreset(engine: Engine, key: String, id: String) =
        sp.edit { putString("active_${engine.id}${sfx(key)}", id) }

    fun baselineRate(key: String): Int = sp.getInt("baseline_rate${sfx(key)}", -1)
    fun setBaselineRate(key: String, rate: Int) = sp.edit { putInt("baseline_rate${sfx(key)}", rate) }

    /** Wi-Fi networks that have their own profile: key -> SSID. */
    var knownWifi: Map<String, String>
        get() = (sp.getStringSet("known_wifi", emptySet()) ?: emptySet())
            .mapNotNull { e -> e.indexOf('\t').takeIf { it > 0 }?.let { e.substring(0, it) to e.substring(it + 1) } }
            .toMap()
        set(v) = sp.edit { putStringSet("known_wifi", v.map { (k, s) -> "$k\t$s" }.toSet()) }

    fun rememberWifi(ssid: String) {
        knownWifi = knownWifi + (Profiles.wifiKey(ssid) to ssid)
    }

    /** Drops the own settings of a Wi-Fi network, it falls back to the common Wi-Fi profile. */
    fun forgetWifi(key: String) {
        if (!Profiles.isSsid(key)) return
        knownWifi = knownWifi - key
        sp.edit {
            sp.all.keys.filter { it.endsWith("_$key") }.forEach { remove(it) }
        }
    }

    /** Selected value index of a store preset option (-1 = value from the preset itself). */
    fun presetOption(presetId: String, variable: String): Int = sp.getInt("opt_${presetId}_$variable", -1)
    fun setPresetOption(presetId: String, variable: String, index: Int) =
        sp.edit { putInt("opt_${presetId}_$variable", index) }

    fun presetFlag(presetId: String, flag: String, def: Boolean): Boolean = sp.getBoolean("flag_${presetId}_$flag", def)
    fun setPresetFlag(presetId: String, flag: String, value: Boolean) =
        sp.edit { putBoolean("flag_${presetId}_$flag", value) }

    // ---- Telegram
    var tgws: Boolean
        get() = sp.getBoolean("tgws", false)
        set(v) = sp.edit { putBoolean("tgws", v) }
    var tgwsPort: Int
        get() = sp.getInt("tgws_port", 1443)
        set(v) = sp.edit { putInt("tgws_port", v) }
    val tgwsSecret: String
        get() = sp.getString("tgws_secret", null) ?: newSecret().also { s -> sp.edit { putString("tgws_secret", s) } }
    fun regenerateTgwsSecret() = sp.edit { putString("tgws_secret", newSecret()) }
    var tgwsPoolSize: Int
        get() = sp.getInt("tgws_pool", 0)
        set(v) = sp.edit { putInt("tgws_pool", v) }
    var tgwsCloudflare: Boolean
        get() = sp.getBoolean("tgws_cf", true)
        set(v) = sp.edit { putBoolean("tgws_cf", v) }
    var tgwsDcIps: String
        get() = str("tgws_dc_ips", "")
        set(v) = sp.edit { putString("tgws_dc_ips", v) }
    var tgwsExtraArgs: String
        get() = str("tgws_extra", "")
        set(v) = sp.edit { putString("tgws_extra", v) }

    // ---- bypass
    var blockQuic: Boolean
        get() = sp.getBoolean("block_quic", true)
        set(v) = sp.edit { putBoolean("block_quic", v) }
    var ipv6: Boolean
        get() = sp.getBoolean("ipv6", true)
        set(v) = sp.edit { putBoolean("ipv6", v) }
    var tcpPorts: String
        get() = str("tcp_ports", "80,443")
        set(v) = sp.edit { putString("tcp_ports", v) }
    var udpPorts: String
        get() = str("udp_ports", "443")
        set(v) = sp.edit { putString("udp_ports", v) }
    var byedpiPorts: String
        get() = str("byedpi_ports", "80,443")
        set(v) = sp.edit { putString("byedpi_ports", v) }
    var fakeSni: String
        get() = str("fake_sni", "www.google.com")
        set(v) = sp.edit { putString("fake_sni", v) }
    /** true: bypass only for [excludedPackages] (whitelist), false: for every app except them. */
    var appsOnly: Boolean
        get() = sp.getBoolean("apps_only", false)
        set(v) = sp.edit { putBoolean("apps_only", v) }
    var excludedPackages: Set<String>
        get() = sp.getStringSet("excluded", emptySet()) ?: emptySet()
        set(v) = sp.edit { putStringSet("excluded", v) }
    /** IPv4 DNS server for all plain DNS while bypass is on ("" = system DNS). */
    var dnsServer: String
        get() = str("dns_server", "8.8.8.8")
        set(v) = sp.edit { putString("dns_server", v) }
    var debugLogs: Boolean
        get() = sp.getBoolean("debug", false)
        set(v) = sp.edit { putBoolean("debug", v) }

    // ---- health check / auto re-selection
    var checkIntervalHours: Int
        get() = sp.getInt("check_interval", 3)
        set(v) = sp.edit { putInt("check_interval", v) }
    var autoReselect: Boolean
        get() = sp.getBoolean("auto_reselect", true)
        set(v) = sp.edit { putBoolean("auto_reselect", v) }
    var healthTargets: List<String>
        get() = str("health_targets", "").split('\n').filter { it.isNotBlank() }
        set(v) = sp.edit { putString("health_targets", v.joinToString("\n")) }
    var lastCheckTime: Long
        get() = sp.getLong("last_check_time", 0)
        set(v) = sp.edit { putLong("last_check_time", v) }
    var lastCheckRate: Int
        get() = sp.getInt("last_check_rate", -1)
        set(v) = sp.edit { putInt("last_check_rate", v) }

    // ---- auto selection form
    var autoGroups: Set<String>
        get() = sp.getStringSet("auto_groups", setOf("youtube", "discord", "general")) ?: emptySet()
        set(v) = sp.edit { putStringSet("auto_groups", v) }
    var customSites: String
        get() = str("custom_sites", "")
        set(v) = sp.edit { putString("custom_sites", v) }
    var autoFullMode: Boolean
        get() = sp.getBoolean("auto_full", false)
        set(v) = sp.edit { putBoolean("auto_full", v) }
    var autoIncludeStore: Boolean
        get() = sp.getBoolean("auto_store", true)
        set(v) = sp.edit { putBoolean("auto_store", v) }
    var autoByeByeDpi: Boolean
        get() = sp.getBoolean("auto_byebyedpi", true)
        set(v) = sp.edit { putBoolean("auto_byebyedpi", v) }
    var autoRequests: Int
        get() = sp.getInt("auto_requests", 1)
        set(v) = sp.edit { putInt("auto_requests", v) }
    var autoTimeoutSec: Int
        get() = sp.getInt("auto_timeout", 5)
        set(v) = sp.edit { putInt("auto_timeout", v) }

    // ---- store
    fun kitVersion(storeId: String): String? = sp.getString("kit_$storeId", null)
    fun setKitVersion(storeId: String, version: String?) = sp.edit {
        if (version == null) remove("kit_$storeId") else putString("kit_$storeId", version)
    }
    var bundledKitsImported: String
        get() = str("bundled_kits", "")
        set(v) = sp.edit { putString("bundled_kits", v) }

    private fun newSecret(): String {
        val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
