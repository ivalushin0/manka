package app.manka.store

import java.io.File

/**
 * Store kits are made for the Windows CDPI UI: besides lists and fake payloads they carry Windows
 * programs and drivers, test tools, backups and notes (one kit: a 10 MB backup copy of its host
 * list). Presets name every file they use literally (only the folders come from variables), so a
 * file no preset mentions is never used on the phone.
 */
object KitCleaner {
    private val junkDirs = setOf("bak", "backup", "backups", "utils", "test results")
    private val junkExt = setOf("sys", "exe", "dll", "ps1", "bat", "cmd", "vbs", "lnk", "reg", "msi")

    /** Preset descriptions: the app reads them, the module does not need them. */
    fun isMeta(f: File) = f.extension.lowercase() in setOf("json", "md") ||
        f.name.startsWith("LICENSE", ignoreCase = true) || f.name.startsWith("README", ignoreCase = true)

    /** Deletes what no preset uses. Returns the freed bytes. */
    fun clean(kitDir: File): Long {
        val presets = kitDir.walk().filter { it.isFile && it.extension.lowercase() == "json" }
            .joinToString("\n") { runCatching { it.readText() }.getOrDefault("") }
            .lowercase()
        var freed = 0L
        kitDir.walk().filter { it.isFile }.toList().forEach { f ->
            val dirs = f.relativeTo(kitDir).invariantSeparatorsPath.split('/').dropLast(1).map { it.lowercase() }
            val junk = dirs.any { it in junkDirs } || f.extension.lowercase() in junkExt ||
                (!isMeta(f) && !presets.contains(f.name.lowercase()))
            if (junk) {
                freed += f.length()
                f.delete()
            }
        }
        removeEmptyDirs(kitDir)
        return freed
    }

    /** After the copy for the module: the app keeps only the preset descriptions. */
    fun keepMetaOnly(kitDir: File) {
        kitDir.walk().filter { it.isFile && !isMeta(it) }.toList().forEach { it.delete() }
        removeEmptyDirs(kitDir)
    }

    fun hasData(kitDir: File) = kitDir.walk().any { it.isFile && !isMeta(it) }

    private fun removeEmptyDirs(root: File) {
        root.walk().filter { it.isDirectory && it != root }.toList().sortedByDescending { it.path.length }
            .forEach { if (it.listFiles().isNullOrEmpty()) it.delete() }
    }
}
