package app.manka.core

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.IOException

/** Settings + own strategies in one JSON file, to move them to another phone or keep them safe. */
object Backup {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    fun export(context: Context, prefs: Prefs): String {
        val values = prefs.exportAll().mapNotNull { (k, v) ->
            val typed: Pair<String, JsonElement> = when (v) {
                is Boolean -> "b" to JsonPrimitive(v)
                is Int -> "i" to JsonPrimitive(v)
                is Long -> "l" to JsonPrimitive(v)
                is Float -> "f" to JsonPrimitive(v)
                is String -> "s" to JsonPrimitive(v)
                is Set<*> -> "set" to JsonArray(v.map { JsonPrimitive(it.toString()) })
                else -> return@mapNotNull null
            }
            k to JsonObject(mapOf("t" to JsonPrimitive(typed.first), "v" to typed.second))
        }.toMap()
        val presets = File(context.filesDir, PRESETS).takeIf { it.exists() }
            ?.let { runCatching { json.parseToJsonElement(it.readText()) }.getOrNull() }
            ?: JsonArray(emptyList())
        val root = JsonObject(
            mapOf(
                "app" to JsonPrimitive("manka"),
                "format" to JsonPrimitive(1),
                "prefs" to JsonObject(values),
                "presets" to presets,
            ),
        )
        return json.encodeToString(JsonElement.serializer(), root)
    }

    fun import(context: Context, prefs: Prefs, presets: PresetRepository, text: String) {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: throw IOException("not a JSON file")
        if (root["app"]?.jsonPrimitive?.contentOrNull != "manka") throw IOException("not a Manka backup")
        val values = root["prefs"]?.jsonObject?.mapNotNull { (k, e) ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val v = o["v"] ?: return@mapNotNull null
            val value: Any? = when (o["t"]?.jsonPrimitive?.contentOrNull) {
                "b" -> v.jsonPrimitive.booleanOrNull
                "i" -> v.jsonPrimitive.contentOrNull?.toIntOrNull()
                "l" -> v.jsonPrimitive.contentOrNull?.toLongOrNull()
                "f" -> v.jsonPrimitive.contentOrNull?.toFloatOrNull()
                "s" -> v.jsonPrimitive.contentOrNull
                "set" -> v.jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull }.toSet()
                else -> null
            }
            value?.let { k to it }
        }?.toMap() ?: throw IOException("no settings in the file")
        prefs.importAll(values)
        root["presets"]?.takeIf { p -> runCatching { json.decodeFromJsonElement(kotlinx.serialization.builtins.ListSerializer(Preset.serializer()), p) }.isSuccess }?.let { p ->
            File(context.filesDir, PRESETS).writeText(json.encodeToString(JsonElement.serializer(), p))
            presets.reloadUser()
        }
    }

    private const val PRESETS = "presets.json"
}
