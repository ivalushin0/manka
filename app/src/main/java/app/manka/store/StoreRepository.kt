package app.manka.store

import android.content.Context
import app.manka.core.Module
import app.manka.core.Paths
import app.manka.core.PresetRepository
import app.manka.core.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.IOException

enum class StoreCategory(val key: String) { CONFIGS("configs"), SUBSCRIPTIONS("subscriptions"), COMPONENTS("components"), ADDONS("addons") }

data class StoreItem(
    val storeId: String,
    val category: StoreCategory,
    val type: String,
    val name: String,
    val shortName: String,
    val developer: String,
    val smallDescription: String,
    val description: String,
    val warning: String?,
    val versionControl: String,
    val link: String,
    val preferredFile: String?,
    val links: List<Pair<String, String>>,
) {
    /** What Manka can do with the item on Android. */
    val support: Support
        get() = when {
            type == "configlist" -> Support.INSTALL
            type == "lsubscription" -> Support.INSTALL
            type == "component" && name in BUNDLED_COMPONENTS -> Support.BUNDLED
            else -> Support.UNSUPPORTED
        }

    enum class Support { INSTALL, BUNDLED, UNSUPPORTED }

    companion object {
        val BUNDLED_COMPONENTS = setOf("zapret", "zapret2", "byedpi", "tg_ws_proxy")
    }
}

/**
 * CDPIUI store (github.com/Storik4pro/CDPIUI-Store) compatible catalogue.
 * The index snapshot bundled in the APK is used until an online refresh succeeds.
 */
class StoreRepository(
    private val context: Context,
    private val prefs: Prefs,
    private val presets: PresetRepository,
) {
    private val onlineIndex = File(context.filesDir, "store-index")

    private fun readIndex(path: String): String? {
        val f = File(onlineIndex, path)
        if (f.exists()) return f.readText()
        return runCatching { context.assets.open("store/index/$path").bufferedReader().use { it.readText() } }.getOrNull()
    }

    private fun dynamic(value: String?): String {
        if (value == null) return ""
        val m = Regex("""^\${'$'}LOADDYNAMIC\((.*)\)$""").find(value.trim()) ?: return value
        val path = m.groupValues[1].trim()
        return readIndex(path.replace("{LOC_CODE}", KitLoader.langCode()))
            ?: readIndex(path.replace("{LOC_CODE}", "EN"))
            ?: ""
    }

    suspend fun items(): List<StoreItem> = withContext(Dispatchers.IO) {
        val root = readIndex("init.json")?.let { KitLoader.json.parseToJsonElement(it).jsonObject } ?: return@withContext emptyList()
        val dirs = root["categories_directory"]?.jsonObject ?: return@withContext emptyList()
        StoreCategory.entries.flatMap { cat ->
            val dir = dirs[cat.key]?.jsonPrimitive?.contentOrNull ?: return@flatMap emptyList()
            val catObj = readIndex("$dir/init.json")?.let { runCatching { KitLoader.json.parseToJsonElement(it).jsonObject }.getOrNull() }
                ?: return@flatMap emptyList()
            val itemDirs = catObj["items_directories"]?.jsonObject ?: return@flatMap emptyList()
            val order = (catObj["items"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: itemDirs.keys.toList()
            order.mapNotNull { key ->
                val sub = itemDirs[key]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val o = readIndex("$dir/$sub/init.json")
                    ?.let { runCatching { KitLoader.json.parseToJsonElement(it).jsonObject }.getOrNull() }
                    ?: return@mapNotNull null
                parseItem(cat, o)
            }
        }
    }

    private fun JsonObject.str(k: String) = this[k]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }

    private fun parseItem(cat: StoreCategory, o: JsonObject): StoreItem? {
        val id = o.str("store_id") ?: return null
        return StoreItem(
            storeId = id,
            category = cat,
            type = o.str("type").orEmpty(),
            name = o.str("name").orEmpty(),
            shortName = o.str("short_name") ?: o.str("name").orEmpty(),
            developer = o.str("developer").orEmpty(),
            smallDescription = dynamic(o.str("small_description")).trim(),
            description = dynamic(o.str("description")).trim(),
            warning = if (o.str("display_warning") == "true") dynamic(o.str("warning_text")).trim().ifBlank { null } else null,
            versionControl = o.str("version_control").orEmpty(),
            link = o.str("version_control_link").orEmpty(),
            preferredFile = o.str("preffered_to_download_file_name"),
            links = (o["links"] as? JsonArray)?.mapNotNull { l ->
                val lo = l as? JsonObject ?: return@mapNotNull null
                (lo.str("name") ?: return@mapNotNull null) to (lo.str("url") ?: return@mapNotNull null)
            } ?: emptyList(),
        )
    }

    /** Downloads the current store index from GitHub. */
    suspend fun refreshIndex() = withContext(Dispatchers.IO) {
        val zip = File(context.cacheDir, "store-index.zip")
        Http.download("https://codeload.github.com/Storik4pro/CDPIUI-Store/zip/refs/heads/main", zip)
        val tmp = File(context.filesDir, "store-index.new")
        zip.inputStream().use { Zip.extract(it, tmp) { n -> n.endsWith(".json") || n.endsWith(".md") || n.endsWith("/") } }
        zip.delete()
        // strip the "CDPIUI-Store-main/" top folder
        val top = tmp.listFiles()?.singleOrNull { it.isDirectory } ?: throw IOException("unexpected archive layout")
        onlineIndex.deleteRecursively()
        if (!top.renameTo(onlineIndex)) throw IOException("cannot move index")
        tmp.deleteRecursively()
    }

    fun installedVersion(item: StoreItem): String? = prefs.kitVersion(item.storeId)

    data class Release(val tag: String, val url: String, val name: String)

    /** Latest downloadable file of an item. */
    suspend fun latestRelease(item: StoreItem): Release {
        if (item.versionControl == "subscription") {
            return Release(tag = "latest", url = item.link, name = item.link.substringAfterLast('/'))
        }
        val repo = item.link.removePrefix("https://github.com/").trim('/')
        val o = KitLoader.json.parseToJsonElement(Http.text("https://api.github.com/repos/$repo/releases/latest")).jsonObject
        val tag = o.str("tag_name") ?: throw IOException("no release in $repo")
        val assets = o["assets"]?.jsonArray?.mapNotNull { a ->
            val ao = a as? JsonObject ?: return@mapNotNull null
            (ao.str("name") ?: return@mapNotNull null) to (ao.str("browser_download_url") ?: return@mapNotNull null)
        } ?: emptyList()
        val pick = item.preferredFile?.let { p -> assets.firstOrNull { it.first == p || it.first.startsWith(p) } }
            ?: assets.firstOrNull { it.first.endsWith(".zip") }
            ?: assets.firstOrNull()
            ?: throw IOException("no files in $repo $tag")
        return Release(tag, pick.second, pick.first)
    }

    suspend fun install(item: StoreItem) {
        val rel = latestRelease(item)
        val file = Http.download(rel.url, File(context.cacheDir, "store-${item.storeId}-${rel.name}"))
        try {
            when (item.type) {
                "configlist" -> installKit(item.storeId, item.shortName, rel.tag) { file.inputStream() }
                "lsubscription" -> installList(item.storeId, file, rel.tag)
                else -> throw IOException("unsupported item type ${item.type}")
            }
        } finally {
            file.delete()
        }
    }

    private suspend fun installKit(storeId: String, name: String, version: String, open: () -> java.io.InputStream) {
        val dir = File(KitLoader.kitsDir(context), storeId)
        withContext(Dispatchers.IO) {
            open().use { Zip.extract(it, dir) }
            val meta = JsonObject(mapOf("name" to JsonPrimitive(name), "version" to JsonPrimitive(version)))
            File(dir, "manka-kit.json").writeText(meta.toString())
        }
        val r = Module.syncKit(storeId, dir)
        if (!r.ok) throw IOException(r.out.ifBlank { "root copy failed" })
        prefs.setKitVersion(storeId, version)
        presets.reloadStore()
    }

    private suspend fun installList(storeId: String, file: File, version: String) {
        val r = Module.copyIn(mapOf("${Paths.LISTS}/$storeId.txt" to file))
        if (!r.ok) throw IOException(r.out.ifBlank { "root copy failed" })
        prefs.setKitVersion(storeId, version)
    }

    suspend fun remove(item: StoreItem) {
        when (item.type) {
            "configlist" -> {
                File(KitLoader.kitsDir(context), item.storeId).deleteRecursively()
                Module.removeKit(item.storeId)
                presets.reloadStore()
            }
            "lsubscription" -> app.manka.core.Root.exec("rm -f ${Paths.LISTS}/${item.storeId}.txt")
        }
        prefs.setKitVersion(item.storeId, null)
    }

    /** Installs kits bundled in the APK that are not installed yet (or older than the bundled copy). */
    suspend fun importBundledKits() {
        val names = runCatching { context.assets.list("store/kits")?.toList() }.getOrNull() ?: return
        val snapshot = runCatching { context.assets.open("store/snapshot.txt").bufferedReader().use { it.readText().trim() } }.getOrNull() ?: ""
        if (prefs.bundledKitsImported == snapshot) return
        val itemsById = runCatching { items().associateBy { it.storeId } }.getOrDefault(emptyMap())
        for (zipName in names.filter { it.endsWith(".zip") }) {
            val id = zipName.removeSuffix(".zip")
            val version = runCatching {
                context.assets.open("store/kits/$id.version").bufferedReader().use { it.readText().trim() }
            }.getOrDefault("bundled")
            val installed = prefs.kitVersion(id)
            if (installed != null && installed == version) continue
            if (installed != null && !File(KitLoader.kitsDir(context), id).exists()) prefs.setKitVersion(id, null)
            runCatching {
                installKit(id, itemsById[id]?.shortName ?: id, version) { context.assets.open("store/kits/$zipName") }
            }
        }
        prefs.bundledKitsImported = snapshot
    }

    /** Re-copies kits after the module (and /data/adb/manka) was reinstalled. */
    suspend fun resyncKits() {
        KitLoader.kitsDir(context).listFiles()?.filter { it.isDirectory }?.forEach { Module.syncKit(it.name, it) }
    }
}
