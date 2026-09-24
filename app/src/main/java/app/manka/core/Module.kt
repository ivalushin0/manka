package app.manka.core

import android.content.Context
import java.io.File
import java.util.zip.ZipInputStream

data class ModuleStatus(
    val rootOk: Boolean = false,
    val installed: Boolean = false,
    val disabled: Boolean = false,
    val pendingRemoval: Boolean = false,
    val versionCode: Int = 0,
    val values: Map<String, String> = emptyMap(),
) {
    val version get() = values["module_version"].orEmpty()
    val engineRunning get() = values["engine_running"] == "1"
    val rulesOk get() = values["rules_ok"] == "1"
    val tgwsRunning get() = values["tgws_running"] == "1"
    val failed get() = values["failed"].orEmpty().split(' ').filter { it.isNotBlank() }
    val hasConnbytes get() = values["HAS_CB"] == "1"
    val hasNfqueue get() = values["HAS_NFQ"] != "0"
    val usable get() = rootOk && installed && !disabled && !pendingRemoval
}

/** Everything that talks to the root module. */
object Module {

    private fun parse(out: String): Map<String, String> = out.lines()
        .mapNotNull { l -> l.indexOf('=').takeIf { it > 0 }?.let { l.substring(0, it).trim() to l.substring(it + 1).trim() } }
        .toMap()

    suspend fun status(): ModuleStatus {
        val r = Root.exec(
            """
            M=${Paths.MODULE}
            echo "root=$(id -u)"
            [ -f ${'$'}M/manka.sh ] && echo installed=1 || echo installed=0
            [ -f ${'$'}M/disable ] && echo disabled=1 || echo disabled=0
            [ -f ${'$'}M/remove ] && echo remove=1 || echo remove=0
            echo "version_code=$(sed -n 's/^versionCode=//p' ${'$'}M/module.prop 2>/dev/null)"
            [ -f ${'$'}M/manka.sh ] && sh ${'$'}M/manka.sh status
            """.trimIndent(), 30,
        )
        val v = parse(r.out)
        if (v["root"] != "0") return ModuleStatus(rootOk = false)
        return ModuleStatus(
            rootOk = true,
            installed = v["installed"] == "1",
            disabled = v["disabled"] == "1",
            pendingRemoval = v["remove"] == "1",
            versionCode = v["version_code"]?.toIntOrNull() ?: 0,
            values = v,
        )
    }

    /** versionCode of the module zip shipped inside the APK (0 if the APK has none). */
    fun bundledVersionCode(context: Context): Int = runCatching {
        context.assets.open("module.zip").use { input ->
            ZipInputStream(input).use { zip ->
                generateSequence { zip.nextEntry }.firstOrNull { it.name == "module.prop" } ?: return 0
                zip.bufferedReader().readText().lines()
                    .firstOrNull { it.startsWith("versionCode=") }?.substringAfter('=')?.trim()?.toIntOrNull() ?: 0
            }
        }
    }.getOrDefault(0)

    fun hasBundledModule(context: Context) = runCatching { context.assets.open("module.zip").close() }.isSuccess

    /** Installs (or updates) the bundled module through the root manager and activates it without reboot. */
    suspend fun install(context: Context): Root.Result {
        val zip = File(context.cacheDir, "manka-module.zip")
        context.assets.open("module.zip").use { input -> zip.outputStream().use { input.copyTo(it) } }
        zip.setReadable(true, false)
        return Root.exec(
            """
            Z=${Root.q(zip.absolutePath)}
            M=${Paths.MODULE}
            [ -f ${'$'}M/manka.sh ] && sh ${'$'}M/manka.sh stop >/dev/null 2>&1
            if [ -x /data/adb/ksud ]; then /data/adb/ksud module install "${'$'}Z"
            elif command -v ksud >/dev/null 2>&1; then ksud module install "${'$'}Z"
            elif [ -x /data/adb/apd ]; then /data/adb/apd module install "${'$'}Z"
            elif command -v magisk >/dev/null 2>&1; then magisk --install-module "${'$'}Z"
            else echo "No Magisk / KernelSU / APatch found"; exit 3
            fi
            rc=${'$'}?
            [ ${'$'}rc -eq 0 ] || exit ${'$'}rc
            # the manager activates the update on reboot; run the new copy right away
            if [ -d ${Paths.MODULE_UPDATE} ]; then
                mkdir -p ${'$'}M
                cp -af ${Paths.MODULE_UPDATE}/. ${'$'}M/
            fi
            [ -f ${'$'}M/manka.sh ] || { echo "module files not found after install"; exit 4; }
            rm -f "${'$'}Z"
            echo installed
            """.trimIndent(), 180,
        )
    }

    /** Copies local files into root-owned locations. Map: destination -> local file. */
    suspend fun copyIn(files: Map<String, File>): Root.Result {
        val script = buildString {
            files.forEach { (dest, src) ->
                src.setReadable(true, false)
                val dir = dest.substringBeforeLast('/')
                appendLine("mkdir -p ${Root.q(dir)} && cat ${Root.q(src.absolutePath)} > ${Root.q(dest)} && chmod 0644 ${Root.q(dest)}")
            }
        }
        return Root.exec(script)
    }

    suspend fun run(command: String, timeoutSec: Long = 60): Root.Result =
        Root.exec("sh ${Paths.SCRIPT} $command", timeoutSec)

    suspend fun start(): ModuleStatus {
        run("start", 90)
        return status()
    }

    suspend fun stop(): ModuleStatus {
        run("stop", 60)
        return status()
    }

    suspend fun restartTgws(): Root.Result = run("tgws-restart", 30)

    /** Starts a temporary engine instance limited to [uid]. Returns true when the engine came up. */
    suspend fun testStart(engine: Engine, argsFile: String, uid: Int): Boolean {
        val r = run("test-start ${engine.id} ${Root.q(argsFile)} $uid", 30)
        return r.lines.lastOrNull()?.trim() == "ok"
    }

    suspend fun testStop() = run("test-stop", 30)

    suspend fun logs(): String = Root.exec(
        """
        cd ${Paths.LOGS} 2>/dev/null || exit 0
        for f in manka.log *.log; do
            [ -f "${'$'}f" ] || continue
            [ "${'$'}f" = manka.log ] && [ -n "${'$'}done_main" ] && continue
            [ "${'$'}f" = manka.log ] && done_main=1
            echo "===== ${'$'}f"
            tail -n 300 "${'$'}f"
        done
        """.trimIndent(),
    ).out

    suspend fun clearLogs() = Root.exec("rm -f ${Paths.LOGS}/*.log ${Paths.LOGS}/*.old")

    suspend fun readFile(path: String): String? {
        val r = Root.exec("cat ${Root.q(path)}")
        return if (r.ok) r.out else null
    }

    /** Copies an extracted store kit to its runtime location. */
    suspend fun syncKit(kitId: String, localDir: File): Root.Result {
        localDir.walk().forEach { it.setReadable(true, false); if (it.isDirectory) it.setExecutable(true, false) }
        val dest = "${Paths.KITS}/$kitId"
        return Root.exec(
            "rm -rf ${Root.q(dest)} && mkdir -p ${Root.q(dest)} && cp -r ${Root.q(localDir.absolutePath)}/. ${Root.q(dest)}/ " +
                "&& chmod -R a+rX ${Root.q(dest)}",
        )
    }

    suspend fun removeKit(kitId: String) = Root.exec("rm -rf ${Root.q("${Paths.KITS}/$kitId")}")
}
