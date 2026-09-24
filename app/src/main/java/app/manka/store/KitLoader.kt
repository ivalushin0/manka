package app.manka.store

import android.content.Context
import app.manka.R
import app.manka.core.Engine
import app.manka.core.Preset
import app.manka.core.PresetFlag
import app.manka.core.PresetOption
import app.manka.core.PresetRenderer
import app.manka.core.PresetSource
import app.manka.core.Z1ToZ2
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.Locale

/** Reads CDPIUI config kits (IC:/UC: preset json files) extracted into filesDir/kits/<storeId>. */
object KitLoader {
    @OptIn(ExperimentalSerializationApi::class)
    val json = Json { ignoreUnknownKeys = true; isLenient = true; allowTrailingComma = true }

    fun kitsDir(context: Context) = File(context.filesDir, "kits")

    fun langCode(): String = if (Locale.getDefault().language == "ru") "RU" else "EN"

    fun loadInstalled(context: Context): List<Preset> {
        val dirs = kitsDir(context).listFiles()?.filter { it.isDirectory }?.sortedBy { it.name } ?: return emptyList()
        return dirs.flatMap { runCatching { loadKit(context, it) }.getOrDefault(emptyList()) }
    }

    private fun strings(kitDir: File): Map<String, String> {
        val init = File(kitDir, "init.json").takeIf { it.exists() } ?: return emptyMap()
        val obj = runCatching { json.parseToJsonElement(init.readText()).jsonObject }.getOrNull() ?: return emptyMap()
        val rel = obj["localized_strings_directory"]?.jsonObject?.get(langCode())?.jsonPrimitive?.contentOrNull
            ?: obj["localized_strings_directory"]?.jsonObject?.get("EN")?.jsonPrimitive?.contentOrNull
            ?: return emptyMap()
        val f = File(kitDir, rel)
        if (!f.exists()) return emptyMap()
        val s = runCatching { json.parseToJsonElement(f.readText()).jsonObject }.getOrNull() ?: return emptyMap()
        return s.mapValues { (_, v) -> (v as? JsonPrimitive)?.contentOrNull.orEmpty() }
    }

    private val loadString = Regex("""\${'$'}LOADSTRING\(([^)]*)\)""")

    private fun localize(text: String, strings: Map<String, String>) =
        loadString.replace(text) { m -> strings[m.groupValues[1].trim()] ?: m.groupValues[1] }.replace(Regex("\\s+"), " ").trim()

    private fun engineOf(componentId: String?): Engine? = when (componentId) {
        "CSZTBN012" -> Engine.ZAPRET
        "CSZTBN062" -> Engine.ZAPRET2
        "CSBIHA024" -> Engine.BYEDPI
        else -> null
    }

    private fun flagTitle(context: Context, key: String): String = when (key) {
        "useGameFilterTCP" -> context.getString(R.string.flag_game_tcp)
        "useGameFilterUDP" -> context.getString(R.string.flag_game_udp)
        else -> key
    }

    fun kitName(kitDir: File): String? = runCatching {
        json.parseToJsonElement(File(kitDir, "manka-kit.json").readText()).jsonObject["name"]?.jsonPrimitive?.contentOrNull
    }.getOrNull()

    private fun loadKit(context: Context, kitDir: File): List<Preset> {
        val kitId = kitDir.name
        val strings = strings(kitDir)
        val kitName = kitName(kitDir) ?: kitId
        val files = kitDir.listFiles { f -> f.isFile && f.name.endsWith(".json") && f.name != "init.json" && f.name != "manka-kit.json" }
            ?.sortedBy { it.name.lowercase() } ?: return emptyList()
        val native = files.mapNotNull { f ->
            val o = runCatching { json.parseToJsonElement(f.readText()).jsonObject }.getOrNull() ?: return@mapNotNull null
            val meta = o["meta"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            if (!meta.startsWith("IC:") && !meta.startsWith("UC:")) return@mapNotNull null
            val target = (o["target"] as? JsonArray)?.firstOrNull()?.jsonPrimitive?.contentOrNull
            val engine = engineOf(target) ?: return@mapNotNull null
            val startup = o["startup_string"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val name = localize(o["name"]?.jsonPrimitive?.contentOrNull ?: f.nameWithoutExtension, strings)

            val variables = LinkedHashMap<String, String>()
            (o["variables"] as? JsonArray)?.forEach { v ->
                val s = v.jsonPrimitive.contentOrNull ?: return@forEach
                val eq = s.indexOf('=')
                if (eq > 0) variables[s.substring(0, eq).trim('%', ' ')] = s.substring(eq + 1)
            }
            (o["commaVars"] as? JsonObject)?.forEach { (k, v) ->
                variables.putIfAbsent(k, (v as? JsonPrimitive)?.contentOrNull.orEmpty())
            }
            val options = (o["availableCommaVarsValues"] as? JsonArray)?.mapNotNull { e ->
                val oo = e as? JsonObject ?: return@mapNotNull null
                val variable = oo["VarName"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val values = oo["Values"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
                val comment = localize(oo["Comment"]?.jsonPrimitive?.contentOrNull ?: variable, strings)
                val def = variables[variable]
                    ?: values.getOrNull(oo["CurrentValueIndex"]?.jsonPrimitive?.intOrNull ?: 0)
                    ?: ""
                PresetOption(variable = variable, title = comment, defaultValue = def, values = values)
            } ?: emptyList()
            val flags = (o["jparams"] as? JsonObject)?.mapNotNull { (k, v) ->
                val b = (v as? JsonPrimitive)?.booleanOrNull ?: return@mapNotNull null
                PresetFlag(key = k, title = flagTitle(context, k), default = b)
            } ?: emptyList()

            Preset(
                id = "store-$kitId-${f.nameWithoutExtension}",
                engine = engine,
                name = name,
                source = PresetSource.STORE,
                template = startup,
                kitId = kitId,
                description = kitName,
                variables = variables.filterKeys { k -> options.none { it.variable == k } },
                options = options,
                flags = flags,
            )
        }
        // zapret presets also run on zapret2 through the CDPI UI compatible converter
        val legacy = native.filter { it.engine == Engine.ZAPRET }.mapNotNull { p ->
            val z2 = p.copy(
                id = p.id + "-z2",
                engine = Engine.ZAPRET2,
                description = "$kitName · LEGACY",
                convertFrom = Engine.ZAPRET,
            )
            // render with default options: variables and conditions resolved
            val source = PresetRenderer.render(p, null).args
            z2.takeIf { runCatching { Z1ToZ2.convert(source).errors.isEmpty() }.getOrDefault(false) }
        }
        return native + legacy
    }
}
