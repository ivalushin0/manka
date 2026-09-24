package app.manka.core

import kotlinx.serialization.Serializable

@Serializable
enum class PresetSource { BUILTIN, STORE, USER, AUTO }

/**
 * A strategy for one engine. [template] is a command line which may still contain CDPIUI style
 * variables (%NAME%, $LOCALCONDITION(...), $GETCURRENTDIR()) and Manka placeholders ($FAKE, $KIT, ...).
 */
@Serializable
data class Preset(
    val id: String,
    val engine: Engine,
    val name: String,
    val source: PresetSource,
    val template: String,
    val tcpPorts: String? = null,
    val udpPorts: String? = null,
    val kitId: String? = null,
    val description: String? = null,
    /** %NAME%=value definitions, values may contain $LOCALCONDITION(...). */
    val variables: Map<String, String> = emptyMap(),
    /** Switchable %NAME% variables of UC presets. */
    val options: List<PresetOption> = emptyList(),
    /** Boolean parameters used by $LOCALCONDITION (CDPIUI "jparams"). */
    val flags: List<PresetFlag> = emptyList(),
    /** Share of test requests that passed when this preset was produced by auto selection. */
    val score: Int? = null,
    /** Written for another engine and converted on the fly (zapret -> zapret2, like CDPI UI "LEGACY"). */
    val convertFrom: Engine? = null,
)

@Serializable
data class PresetOption(
    val variable: String,
    val title: String,
    val defaultValue: String,
    val values: List<String>,
)

@Serializable
data class PresetFlag(
    val key: String,
    val title: String,
    val default: Boolean,
)

data class RenderedArgs(
    val args: List<String>,
    val tcpPorts: String?,
    val udpPorts: String?,
)

object PresetRenderer {
    private val condition = Regex("""\${'$'}LOCALCONDITION\(\s*([A-Za-z0-9_]+)\s*==\s*(true|false)\s*\?\s*([^:)]*?)\s*:\s*([^)]*?)\s*\)""")
    private val varRef = Regex("""%([A-Za-z0-9_]+)%""")

    fun render(preset: Preset, prefs: Prefs?): RenderedArgs {
        val flags = preset.flags.associate { f ->
            f.key to (prefs?.presetFlag(preset.id, f.key, f.default) ?: f.default)
        }
        val values = HashMap<String, String>()
        preset.variables.forEach { (k, v) -> values[k.uppercase()] = evalConditions(v, flags) }
        preset.options.forEach { o ->
            val idx = prefs?.presetOption(preset.id, o.variable) ?: -1
            values[o.variable.uppercase()] = o.values.getOrNull(idx) ?: o.defaultValue
        }
        var line = evalConditions(preset.template, flags)
        repeat(3) {
            line = varRef.replace(line) { m -> values[m.groupValues[1].uppercase()] ?: m.value }
        }
        line = line
            .replace("\$GETCURRENTDIR()", "\$KIT")
            .replace("%~dp0", "\$KIT/")
            .replace("\$EMPTY", "")
        val rendered = fromWinws(Args.split(line), preset.tcpPorts, preset.udpPorts)
        if (preset.convertFrom == Engine.ZAPRET && preset.engine == Engine.ZAPRET2) {
            return rendered.copy(args = Z1ToZ2.convert(rendered.args).args)
        }
        return rendered
    }

    private fun evalConditions(text: String, flags: Map<String, Boolean>): String =
        condition.replace(text) { m ->
            val actual = flags[m.groupValues[1]] ?: false
            val expected = m.groupValues[2] == "true"
            if (actual == expected) m.groupValues[3] else m.groupValues[4]
        }

    /**
     * winws (Windows zapret) accepts the same options as nfqws plus WinDivert filters.
     * The --wf-* filters become iptables ports, windows-only options are dropped.
     */
    fun fromWinws(args: List<String>, tcp: String?, udp: String?): RenderedArgs {
        var tcpPorts = tcp
        var udpPorts = udp
        val out = mutableListOf<String>()
        for (raw in args) {
            val a = if (raw.contains("\$KIT")) raw.replace('\\', '/') else raw
            when {
                a.startsWith("--wf-tcp=") -> tcpPorts = Args.ports(a.substringAfter('='))
                a.startsWith("--wf-udp=") -> udpPorts = Args.ports(a.substringAfter('='))
                a.startsWith("--wf-") -> Unit
                a.startsWith("--ssid-filter") || a.startsWith("--nlm-filter") -> Unit
                a.startsWith("--debug") -> Unit
                a.isBlank() -> Unit
                else -> out += a
            }
        }
        // drop empty profiles left behind (e.g. "--new --new")
        val cleaned = mutableListOf<String>()
        for (a in out) {
            if (a == "--new" && (cleaned.isEmpty() || cleaned.last() == "--new")) continue
            cleaned += a
        }
        while (cleaned.lastOrNull() == "--new") cleaned.removeAt(cleaned.lastIndex)
        return RenderedArgs(cleaned, tcpPorts?.ifBlank { null }, udpPorts?.ifBlank { null })
    }
}
