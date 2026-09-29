package app.manka

import app.manka.core.ProxyConfig
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.Base64
import java.util.zip.Deflater

/**
 * Key parsing for "proxy for apps". The built configs are also written to build/proxy/, where
 * scripts/validate-proxy.sh checks them with the real Xray (xray run -test).
 */
class ProxyConfigTest {
    private val uuid = "0b3a6a7e-1c2d-4e5f-8a9b-0c1d2e3f4a5b"

    private val reality = "vless://$uuid@203.0.113.10:443?type=tcp&security=reality&pbk=Q1w2E3r4T5y6U7i8O9p0A1s2D3f4G5h6J7k8L9z0X1c" +
        "&fp=chrome&sni=www.microsoft.com&sid=6ba85179e30d4fc2&flow=xtls-rprx-vision&spx=%2F#My%20server"

    private val samples = mutableMapOf<String, String>()

    private fun keep(name: String, s: ProxyConfig.Server) {
        samples[name] = ProxyConfig.build(s)
    }

    private fun JsonObject.obj(k: String) = this[k]!!.jsonObject
    private fun JsonObject.str(k: String) = this[k]!!.jsonPrimitive.content

    @Test
    fun vlessReality() {
        val s = ProxyConfig.parse(reality)
        val o = s.outbound
        assertEquals("vless", o.str("protocol"))
        assertEquals("proxy", o.str("tag"))
        val user = o.obj("settings")["vnext"]!!.jsonArray[0].jsonObject["users"]!!.jsonArray[0].jsonObject
        assertEquals(uuid, user.str("id"))
        assertEquals("xtls-rprx-vision", user.str("flow"))
        val r = o.obj("streamSettings").obj("realitySettings")
        assertEquals("www.microsoft.com", r.str("serverName"))
        assertEquals("6ba85179e30d4fc2", r.str("shortId"))
        assertEquals("/", r.str("spiderX"))
        assertTrue(s.summary, s.summary.contains("203.0.113.10:443"))
        assertTrue("no secrets in the summary", !s.summary.contains(uuid))
        keep("vless-reality", s)
        samples["gemini-only"] = ProxyConfig.build(s, ProxyConfig.GEMINI_DOMAINS)
    }

    @Test
    fun geminiOnly() {
        val cfg = kotlinx.serialization.json.Json.parseToJsonElement(
            ProxyConfig.build(ProxyConfig.parse(reality), listOf("gemini.google.com")),
        ).jsonObject
        val outs = cfg["outbounds"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("proxy", "direct"), outs.map { it.str("tag") })
        assertEquals(ProxyConfig.DIRECT_MARK.toString(), outs[1].obj("streamSettings").obj("sockopt").str("mark"))
        val rules = cfg.obj("routing")["rules"]!!.jsonArray.map { it.jsonObject }
        assertEquals("proxy", rules[0].str("outboundTag"))
        assertTrue(rules[0]["domain"]!!.jsonArray.any { it.jsonPrimitive.content == "domain:gemini.google.com" })
        assertEquals("direct", rules.last().str("outboundTag"))
        assertEquals(
            listOf("gemini.google.com", "robinfrontend-pa.googleapis.com"),
            ProxyConfig.domains(" https://Gemini.Google.com/app \nrobinfrontend-pa.googleapis.com, bad_host\n\n"),
        )
    }

    @Test
    fun vlessTransports() {
        keep("vless-ws-tls", ProxyConfig.parse("vless://$uuid@example.org:443?type=ws&security=tls&path=%2Fws&host=cdn.example.org&sni=cdn.example.org"))
        keep("vless-grpc", ProxyConfig.parse("vless://$uuid@example.org:443?type=grpc&security=tls&serviceName=grpc&sni=example.org"))
        keep("vless-xhttp", ProxyConfig.parse("vless://$uuid@[2001:db8::1]:8443?type=xhttp&security=reality&pbk=Q1w2E3r4T5y6U7i8O9p0A1s2D3f4G5h6J7k8L9z0X1c&sni=a.example&path=%2Fx"))
    }

    @Test
    fun trojanAndShadowsocks() {
        val t = ProxyConfig.parse("trojan://secret@example.org:443?sni=example.org#t")
        assertEquals("tls", t.outbound.obj("streamSettings").str("security"))
        keep("trojan", t)
        val cred = Base64.getUrlEncoder().withoutPadding().encodeToString("chacha20-ietf-poly1305:p@ss".toByteArray())
        val ss = ProxyConfig.parse("ss://$cred@198.51.100.7:8388#ss")
        val server = ss.outbound.obj("settings")["servers"]!!.jsonArray[0].jsonObject
        assertEquals("chacha20-ietf-poly1305", server.str("method"))
        assertEquals("p@ss", server.str("password"))
        keep("shadowsocks", ss)
        val legacy = Base64.getEncoder().encodeToString("aes-256-gcm:pw@198.51.100.8:8388".toByteArray())
        assertEquals("198.51.100.8", ProxyConfig.parse("ss://$legacy").outbound.obj("settings")["servers"]!!
            .jsonArray[0].jsonObject.str("address"))
    }

    /** AmneziaVPN share format: vpn:// + base64url(4-byte size + zlib(json)). */
    private fun amneziaKey(json: String): String {
        val data = json.toByteArray()
        val d = Deflater().apply { setInput(data); finish() }
        val out = ByteArrayOutputStream().apply { write(ByteBuffer.allocate(4).putInt(data.size).array()) }
        val buf = ByteArray(4096)
        while (!d.finished()) out.write(buf, 0, d.deflate(buf))
        return "vpn://" + Base64.getUrlEncoder().withoutPadding().encodeToString(out.toByteArray())
    }

    @Test
    fun amnezia() {
        val xrayConfig = buildJsonObject {
            put("inbounds", buildJsonArray { add(buildJsonObject { put("protocol", "socks"); put("port", 10808) }) })
            put("outbounds", buildJsonArray { add(ProxyConfig.parse(reality).outbound) })
        }.toString()
        val key = amneziaKey(buildJsonObject {
            put("containers", buildJsonArray {
                add(buildJsonObject {
                    put("container", "amnezia-xray")
                    put("xray", buildJsonObject { put("last_config", xrayConfig); put("port", "443") })
                })
            })
            put("defaultContainer", "amnezia-xray")
            put("hostName", "203.0.113.10")
        }.toString())
        val s = ProxyConfig.parse(key)
        assertEquals("vless", s.outbound.str("protocol"))
        keep("amnezia", s)

        val awgOnly = amneziaKey("""{"containers":[{"container":"amnezia-awg","awg":{"last_config":"{}"}}]}""")
        try {
            ProxyConfig.parse(awgOnly)
            fail("AWG-only key accepted")
        } catch (e: ProxyConfig.Invalid) {
            assertEquals(ProxyConfig.Reason.NO_XRAY, e.reason)
            assertEquals("amnezia-awg", e.detail)
        }
    }

    @Test
    fun rejects() {
        for ((k, reason) in listOf(
            "" to ProxyConfig.Reason.EMPTY,
            "http://example.org" to ProxyConfig.Reason.FORMAT,
            "vless://example.org:443" to ProxyConfig.Reason.BROKEN,
            "vless://$uuid@example.org:443?security=reality" to ProxyConfig.Reason.BROKEN,
            "vless://$uuid@example.org:443?type=kcp" to ProxyConfig.Reason.UNSUPPORTED,
            "vpn://!!!" to ProxyConfig.Reason.BROKEN,
        )) {
            try {
                ProxyConfig.parse(k)
                fail("accepted: $k")
            } catch (e: ProxyConfig.Invalid) {
                assertEquals(k, reason, e.reason)
            }
        }
    }

    @org.junit.After
    fun write() {
        val dir = File("build/proxy").apply { mkdirs() }
        samples.forEach { (name, cfg) -> File(dir, "$name.json").writeText(cfg) }
    }
}
