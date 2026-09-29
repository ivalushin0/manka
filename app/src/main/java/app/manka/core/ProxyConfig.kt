package app.manka.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.ByteArrayOutputStream
import java.net.URLDecoder
import java.util.Base64
import java.util.zip.Inflater

/**
 * "Proxy for apps": turns the user's server key into an Xray config. Accepted keys:
 * vless://, trojan://, ss://, an AmneziaVPN key (vpn://, XRay protocol) or an Xray JSON config.
 * Pure JVM code (unit tested).
 */
object ProxyConfig {
    const val PORT = 10820

    enum class Reason { EMPTY, FORMAT, BROKEN, UNSUPPORTED, SUBSCRIPTION, NO_XRAY }

    /** [detail]: the unsupported option, or the protocols an AmneziaVPN key has instead of XRay. */
    class Invalid(val reason: Reason, val detail: String = "") : Exception("$reason $detail".trim())

    /** Server outbound (tag "proxy") and a short description without secrets. */
    data class Server(val outbound: JsonObject, val summary: String)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(key: String): Server {
        val k = key.trim()
        return when {
            k.isEmpty() -> throw Invalid(Reason.EMPTY)
            k.startsWith("vless://", ignoreCase = true) -> vless(k)
            k.startsWith("trojan://", ignoreCase = true) -> trojan(k)
            k.startsWith("ss://", ignoreCase = true) -> shadowsocks(k)
            k.startsWith("vpn://", ignoreCase = true) -> amnezia(k)
            k.startsWith("{") -> fromXrayConfig(runCatching { json.parseToJsonElement(k).jsonObject }.getOrElse { throw Invalid(Reason.BROKEN) })
            else -> throw Invalid(Reason.FORMAT)
        }
    }

    /** Socket mark of the direct connections, the module sends them through the bypass (DIRECT_MARK). */
    const val DIRECT_MARK = 0x20000000

    /** Gemini in the Google app (seen on a phone) and on the web. */
    val GEMINI_DOMAINS = listOf(
        "robinfrontend-pa.googleapis.com", "signaler-pa.googleapis.com", "subscriptionsfirstparty-pa.googleapis.com",
        "gemini.google.com", "bard.google.com", "generativelanguage.googleapis.com",
        "alkalimakersuite-pa.clients6.google.com", "aistudio.google.com",
    )

    /** One domain per line (or separated by spaces / commas); subdomains are included. */
    fun domains(text: String): List<String> = text.split('\n', ' ', ',', ';')
        .map { it.trim().trimEnd('.').lowercase().removePrefix("https://").removePrefix("http://").substringBefore('/') }
        .filter { it.contains('.') && it.all { c -> c.isLetterOrDigit() || c == '.' || c == '-' } }
        .distinct()

    /**
     * The whole Xray config: redirected TCP of the chosen apps -> the server. With [only] the server
     * gets just these domains (and their subdomains), the rest goes out directly, through the bypass.
     */
    fun build(server: Server, only: List<String>? = null, port: Int = PORT): String = buildJsonObject {
        // no access log: it would list every site the proxied apps open
        putJsonObject("log") { put("loglevel", "warning"); put("access", "none") }
        putJsonArray("inbounds") {
            add(buildJsonObject {
                put("tag", "redir")
                put("listen", "127.0.0.1")
                put("port", port)
                put("protocol", "dokodemo-door")
                putJsonObject("settings") { put("network", "tcp"); put("followRedirect", true) }
                // the server gets the domain, not the IP the phone resolved: it resolves it itself,
                // near its own location
                putJsonObject("sniffing") {
                    put("enabled", true)
                    putJsonArray("destOverride") { add(JsonPrimitive("http")); add(JsonPrimitive("tls")) }
                }
            })
        }
        putJsonArray("outbounds") {
            add(server.outbound)
            if (only != null) add(buildJsonObject {
                put("tag", "direct")
                put("protocol", "freedom")
                putJsonObject("streamSettings") { putJsonObject("sockopt") { put("mark", DIRECT_MARK) } }
            })
        }
        if (only != null) putJsonObject("routing") {
            putJsonArray("rules") {
                add(buildJsonObject {
                    put("type", "field")
                    // + the check in the app, which should show the server's address
                    putJsonArray("domain") { (only + "ipinfo.io").forEach { add(JsonPrimitive("domain:$it")) } }
                    put("outboundTag", "proxy")
                })
                add(buildJsonObject {
                    put("type", "field")
                    put("network", "tcp,udp")
                    put("outboundTag", "direct")
                })
            }
        }
    }.toString()

    // ---------------------------------------------------------------- links

    private data class Link(
        val user: String, val host: String, val port: Int,
        val params: Map<String, String>, val name: String,
    )

    private fun dec(s: String) = URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")

    /** scheme://user@host:port?query#name (host may be [IPv6]). */
    private fun link(k: String): Link {
        val body = k.substringAfter("://")
        val name = dec(body.substringAfter('#', ""))
        val noName = body.substringBefore('#')
        val query = noName.substringAfter('?', "")
        val main = noName.substringBefore('?').trimEnd('/')
        val at = main.lastIndexOf('@')
        if (at <= 0) throw Invalid(Reason.BROKEN)
        val user = dec(main.substring(0, at))
        val hp = main.substring(at + 1)
        val host: String
        val portStr: String
        if (hp.startsWith("[")) {
            host = hp.substring(1, hp.indexOf(']').takeIf { it > 0 } ?: throw Invalid(Reason.BROKEN))
            portStr = hp.substringAfter("]:", "")
        } else {
            host = hp.substringBeforeLast(':')
            portStr = hp.substringAfterLast(':', "")
        }
        val port = portStr.toIntOrNull()?.takeIf { it in 1..65535 } ?: throw Invalid(Reason.BROKEN)
        if (host.isBlank()) throw Invalid(Reason.BROKEN)
        val params = query.split('&').filter { it.isNotEmpty() }.associate {
            dec(it.substringBefore('=')) to dec(it.substringAfter('=', ""))
        }
        return Link(user, host, port, params, name)
    }

    private fun vless(k: String): Server {
        val l = link(k)
        val out = buildJsonObject {
            put("tag", "proxy")
            put("protocol", "vless")
            putJsonObject("settings") {
                putJsonArray("vnext") {
                    add(buildJsonObject {
                        put("address", l.host)
                        put("port", l.port)
                        putJsonArray("users") {
                            add(buildJsonObject {
                                put("id", l.user)
                                put("encryption", l.params["encryption"]?.takeIf { it.isNotBlank() } ?: "none")
                                l.params["flow"]?.takeIf { it.isNotBlank() }?.let { put("flow", it) }
                            })
                        }
                    })
                }
            }
            put("streamSettings", stream(l))
        }
        return Server(out, summary("VLESS", l))
    }

    private fun trojan(k: String): Server {
        val l = link(k)
        val p = if ("security" in l.params) l.params else l.params + ("security" to "tls")
        val out = buildJsonObject {
            put("tag", "proxy")
            put("protocol", "trojan")
            putJsonObject("settings") {
                putJsonArray("servers") {
                    add(buildJsonObject { put("address", l.host); put("port", l.port); put("password", l.user) })
                }
            }
            put("streamSettings", stream(l.copy(params = p)))
        }
        return Server(out, summary("Trojan", l.copy(params = p)))
    }

    /** ss://base64(method:password)@host:port or ss://base64(method:password@host:port) (SIP002 / legacy). */
    private fun shadowsocks(k: String): Server {
        val body = k.substringAfter("://").substringBefore('#').substringBefore('?').trimEnd('/')
        val full = if ('@' in body) body else b64(body).toString(Charsets.UTF_8)
        val at = full.lastIndexOf('@')
        if (at <= 0) throw Invalid(Reason.BROKEN)
        val rawCred = dec(full.substring(0, at))
        val cred = if (':' in rawCred) rawCred else b64(rawCred).toString(Charsets.UTF_8)
        val l = link("ss://x@" + full.substring(at + 1))
        val method = cred.substringBefore(':')
        val password = cred.substringAfter(':', "")
        if (method.isBlank() || password.isEmpty()) throw Invalid(Reason.BROKEN)
        val out = buildJsonObject {
            put("tag", "proxy")
            put("protocol", "shadowsocks")
            putJsonObject("settings") {
                putJsonArray("servers") {
                    add(buildJsonObject {
                        put("address", l.host); put("port", l.port)
                        put("method", method); put("password", password)
                    })
                }
            }
        }
        return Server(out, "Shadowsocks · ${l.host}:${l.port}")
    }

    private fun stream(l: Link): JsonObject {
        val p = l.params
        val net = (p["type"]?.takeIf { it.isNotBlank() } ?: "tcp").let { if (it == "raw") "tcp" else it }
        val sec = p["security"]?.takeIf { it.isNotBlank() } ?: "none"
        val sni = p["sni"]?.takeIf { it.isNotBlank() } ?: p["peer"]?.takeIf { it.isNotBlank() }
        val fp = p["fp"]?.takeIf { it.isNotBlank() }
        val host = p["host"]?.takeIf { it.isNotBlank() }
        val path = p["path"]?.takeIf { it.isNotBlank() }
        return buildJsonObject {
            put("network", net)
            put("security", sec)
            when (sec) {
                "reality" -> putJsonObject("realitySettings") {
                    put("serverName", sni ?: "")
                    put("fingerprint", fp ?: "chrome")
                    put("publicKey", p["pbk"] ?: throw Invalid(Reason.BROKEN))
                    put("shortId", p["sid"] ?: "")
                    p["spx"]?.takeIf { it.isNotBlank() }?.let { put("spiderX", it) }
                }
                "tls" -> putJsonObject("tlsSettings") {
                    (sni ?: host)?.let { put("serverName", it) }
                    fp?.let { put("fingerprint", it) }
                    p["alpn"]?.takeIf { it.isNotBlank() }?.let { a ->
                        putJsonArray("alpn") { a.split(',').forEach { add(JsonPrimitive(it.trim())) } }
                    }
                    if (p["allowInsecure"] == "1" || p["insecure"] == "1") put("allowInsecure", true)
                }
                "none" -> {}
                else -> throw Invalid(Reason.UNSUPPORTED, sec)
            }
            when (net) {
                "tcp" -> if (p["headerType"] == "http") putJsonObject("tcpSettings") {
                    putJsonObject("header") {
                        put("type", "http")
                        putJsonObject("request") {
                            path?.let { pa -> putJsonArray("path") { add(JsonPrimitive(pa)) } }
                            host?.let { h -> putJsonObject("headers") { putJsonArray("Host") { add(JsonPrimitive(h)) } } }
                        }
                    }
                }
                "ws" -> putJsonObject("wsSettings") { put("path", path ?: "/"); host?.let { put("host", it) } }
                "httpupgrade" -> putJsonObject("httpupgradeSettings") { put("path", path ?: "/"); host?.let { put("host", it) } }
                "xhttp", "splithttp" -> putJsonObject("xhttpSettings") {
                    put("path", path ?: "/"); host?.let { put("host", it) }
                    put("mode", p["mode"]?.takeIf { it.isNotBlank() } ?: "auto")
                }
                "grpc" -> putJsonObject("grpcSettings") {
                    put("serviceName", p["serviceName"] ?: "")
                    if (p["mode"] == "multi") put("multiMode", true)
                }
                else -> throw Invalid(Reason.UNSUPPORTED, net)
            }
        }
    }

    private fun summary(proto: String, l: Link): String {
        val sec = l.params["security"]?.takeIf { it.isNotBlank() && it != "none" }
        val net = l.params["type"]?.takeIf { it.isNotBlank() && it != "tcp" && it != "raw" }
        return listOfNotNull(proto, "${l.host}:${l.port}", listOfNotNull(sec, net).joinToString("/").ifEmpty { null })
            .joinToString(" · ")
    }

    // ---------------------------------------------------------------- AmneziaVPN / Xray JSON

    private fun b64(s: String): ByteArray {
        val clean = s.trim().replace("\n", "").replace("\r", "")
        return runCatching { Base64.getUrlDecoder().decode(clean) }
            .recoverCatching { Base64.getDecoder().decode(clean) }
            .getOrElse { throw Invalid(Reason.BROKEN) }
    }

    /** vpn://base64url(qCompress(json)): 4-byte big-endian size, then a zlib stream. Plain JSON is accepted too. */
    private fun amnezia(k: String): Server {
        val raw = b64(k.substringAfter("://"))
        val text = runCatching { inflate(raw.copyOfRange(4, raw.size)) }.getOrNull()
            ?: runCatching { inflate(raw) }.getOrNull()
            ?: raw.toString(Charsets.UTF_8)
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrElse { throw Invalid(Reason.BROKEN) }
        if ("api_config" in root || "auth_data" in root) {
            throw Invalid(Reason.SUBSCRIPTION)
        }
        val containers = (root["containers"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val xray = containers.firstNotNullOfOrNull { c -> (c["xray"] as? JsonObject)?.get("last_config")?.jsonPrimitive?.contentOrNull }
            ?: throw if (containers.isEmpty()) Invalid(Reason.BROKEN)
            else Invalid(Reason.NO_XRAY, containers.mapNotNull { it["container"]?.jsonPrimitive?.contentOrNull }.joinToString())
        return fromXrayConfig(runCatching { json.parseToJsonElement(xray).jsonObject }.getOrElse { throw Invalid(Reason.BROKEN) })
    }

    private fun inflate(data: ByteArray): String {
        val inf = Inflater()
        inf.setInput(data)
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (!inf.finished()) {
            val n = inf.inflate(buf)
            if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break
            out.write(buf, 0, n)
        }
        inf.end()
        if (out.size() == 0) throw Invalid(Reason.BROKEN)
        return out.toString("UTF-8")
    }

    private val SERVICE = setOf("freedom", "blackhole", "dns", "loopback")

    /** A client Xray config (e.g. AmneziaVPN's): its first proxy outbound. */
    private fun fromXrayConfig(cfg: JsonObject): Server {
        val outs = (cfg["outbounds"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val o = outs.firstOrNull { it["protocol"]?.jsonPrimitive?.contentOrNull !in SERVICE }
            ?: throw Invalid(Reason.BROKEN)
        val proto = o["protocol"]?.jsonPrimitive?.contentOrNull ?: "?"
        val tagged = JsonObject(o + ("tag" to JsonPrimitive("proxy")) - "proxySettings")
        val s = tagged["settings"] as? JsonObject
        val addr = (s?.get("vnext") as? JsonArray ?: s?.get("servers") as? JsonArray)
            ?.firstOrNull()?.let { it as? JsonObject }
            ?.let { "${it["address"]?.jsonPrimitive?.contentOrNull}:${it["port"]?.jsonPrimitive?.contentOrNull}" }
        val st = tagged["streamSettings"] as? JsonObject
        val sec = st?.get("security")?.jsonPrimitive?.contentOrNull?.takeIf { it != "none" }
        return Server(tagged, listOfNotNull(proto.uppercase(), addr, sec).joinToString(" · "))
    }

}
