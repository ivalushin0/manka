package app.manka

import app.manka.core.Args
import app.manka.core.NetLists
import app.manka.core.Profiles
import app.manka.core.ProxyConfig
import app.manka.core.Services
import app.manka.store.KitCleaner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** The parts where a mistake does not crash but silently breaks the bypass. */
class CoreLogicTest {

    @Test
    fun ports() {
        assertEquals("80,443,50000:50100", Args.ports("80, 443,50000-50100,abc,443,"))
        assertEquals("", Args.ports("nonsense"))
    }

    @Test
    fun split() {
        assertEquals(listOf("--a=1", "two words", "", "x"), Args.split("--a=1 \"two words\" '' x"))
    }

    /** The module computes the same key with md5sum (lib/engine.sh current_key): the real home networks. */
    @Test
    fun profiles() {
        assertEquals("wifi_172becb8", Profiles.wifiKey("TrapHata"))
        assertEquals("wifi_ca23c420", Profiles.wifiKey("NordFox"))
        assertEquals(Profiles.WIFI, Profiles.parent("wifi_172becb8"))
        assertEquals(Profiles.WIFI, Profiles.parent(Profiles.MOBILE))
        assertNull(Profiles.parent(Profiles.WIFI))
        assertEquals(Profiles.MOBILE, Profiles.keyOf("mobile", "TrapHata"))
        assertEquals("wifi_172becb8", Profiles.keyOf("wifi", "TrapHata"))
        assertEquals(Profiles.WIFI, Profiles.keyOf("wifi", null))
    }

    @Test
    fun mergeServiceStrategies() {
        val main = Services.Part(
            listOf("--lua-init=@a.lua", "--filter-tcp=80,443", "--hostlist-exclude=/x/exclude.txt", "--lua-desync=fake", "--new", "--filter-udp=443", "--lua-desync=fake"),
            "80,443", "443",
        )
        val yt = Services.Scoped(
            "/lists/svc-youtube.txt",
            Services.Part(
                listOf("--lua-init=@a.lua", "--filter-tcp=443", "--hostlist=/kit/list.txt", "--lua-desync=multisplit", "--new", "--filter-udp=50000-50100", "--lua-desync=fake"),
                "443", "443,50000-50100",
            ),
            keepVoice = false,
        )
        val r = Services.merge(main, listOf(yt), excluded = listOf("/lists/svc-youtube.txt"))
        val blocks = r.args.joinToString(" ").split(" --new ")
        // the global option once, at the start
        assertEquals(1, r.args.count { it == "--lua-init=@a.lua" })
        assertEquals("--lua-init=@a.lua", r.args.first())
        // the service first, limited to its own list; its hostless UDP profile (voice/games) dropped
        assertTrue(blocks[0], blocks[0].contains("--hostlist=/lists/svc-youtube.txt") && !blocks[0].contains("/kit/list.txt"))
        assertFalse(r.args.contains("--filter-udp=50000-50100"))
        // the main strategy without the service
        assertTrue(blocks.drop(1).all { it.contains("--hostlist-exclude=/lists/svc-youtube.txt") })
        assertEquals(3, blocks.size)
        assertEquals("443,80", r.tcpPorts)
        assertEquals("443", r.udpPorts)
    }

    @Test
    fun netLists() {
        assertTrue(NetLists.DEFAULT.complete)
        assertEquals(ProxyConfig.GEMINI_DOMAINS, NetLists.DEFAULT.geminiDomains)
        // malformed entries are dropped, an emptied list rejects the whole download
        val parsed = NetLists.parse(
            """{"version":2,"meta_nets":["31.13.24.0/21","; rm -rf /"],"meta_edges":["57.144.249.32"],
               "tgws_dc_ips":["2:149.154.167.220"],"gemini_domains":["gemini.google.com","bad domain"]}""",
        )
        assertNotNull(parsed)
        assertEquals(listOf("31.13.24.0/21"), parsed!!.metaNets)
        assertEquals(listOf("gemini.google.com"), parsed.geminiDomains)
        assertNull(NetLists.parse("""{"version":2,"meta_nets":[],"meta_edges":["1.1.1.1"],"tgws_dc_ips":["2:1.1.1.1"],"gemini_domains":["a.b"]}"""))
        assertNull(NetLists.parse("not json"))
    }

    /** net/manka-net.json is what phones download: it must parse and not be older than the built-in lists. */
    @Test
    fun netListsFileInRepo() {
        val f = listOf(File("../net/manka-net.json"), File("net/manka-net.json")).first { it.exists() }
        val lists = NetLists.parse(f.readText())
        assertNotNull("net/manka-net.json does not parse or misses a list", lists)
        assertTrue(lists!!.version >= NetLists.DEFAULT.version)
    }

    @Test
    fun kitCleaner() {
        val kit = Files.createTempDirectory("kit").toFile()
        fun put(path: String, text: String = "x") = File(kit, path).apply { parentFile?.mkdirs(); writeText(text) }
        put("general.json", """{"startup_string":"--hostlist=%LISTS%list-general.txt --dpi-desync-fake-quic=%BIN%quic_1.bin"}""")
        put("lists/list-general.txt")
        put("bak/list-general.txt")
        put("bin/quic_1.bin")
        put("bin/quic_2.bin")
        put("bin/WinDivert64.sys")
        put("utils/test zapret.ps1")
        put("hosts")
        put("README.md")
        put("LICENSE.txt")
        KitCleaner.clean(kit)
        val left = kit.walk().filter { it.isFile }.map { it.relativeTo(kit).invariantSeparatorsPath }.toSet()
        assertEquals(setOf("general.json", "lists/list-general.txt", "bin/quic_1.bin", "README.md", "LICENSE.txt"), left)
        KitCleaner.keepMetaOnly(kit)
        val meta = kit.walk().filter { it.isFile }.map { it.relativeTo(kit).invariantSeparatorsPath }.toSet()
        assertEquals(setOf("general.json", "README.md", "LICENSE.txt"), meta)
        kit.deleteRecursively()
    }
}
