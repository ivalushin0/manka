package app.manka.core

import java.security.MessageDigest

/**
 * Converts an expanded zapret (nfqws1 / winws) command line into zapret2 (nfqws2) arguments.
 *
 * Ported from CDPI UI's Zapret1ToZapret2Converter
 * (https://github.com/Storik4pro/cdpiui, Apache License 2.0, (c) Storik4pro and contributors).
 * zapret-lib.lua / zapret-antidpi.lua are loaded by the module, so only the compatibility
 * Lua shim and the blobs are emitted here.
 */
object Z1ToZ2 {
    data class Result(val args: List<String>, val errors: List<String>, val warnings: List<String>)

    private const val COMPAT_FUNC = "cdpi_z1_desync"

    private const val COMPAT_LUA =
        "cdpi_z1_zero64=string.rep(string.char(0),64); " +
            "cdpi_z1_zero256=string.rep(string.char(0),256); " +
            "cdpi_z1_funcs={fake=fake,multisplit=multisplit,multidisorder=multidisorder," +
            "multidisorder_legacy=multidisorder_legacy,fakedsplit=fakedsplit," +
            "fakeddisorder=fakeddisorder,hostfakesplit=hostfakesplit,syndata=syndata," +
            "synack=synack,rst=rst,send=send,drop=drop,udplen=udplen,dht_dn=dht_dn}; " +
            "function cdpi_z1_desync(ctx,d) " +
            "if d.arg.z1_skip_nosni and d.l7payload=='tls_client_hello' and " +
            "(not d.track or not d.track.hostname) then return end; " +
            "if d.arg.z1_func=='fake' then " +
            "local b=d.arg[d.l7payload]; " +
            "if not b then b=d.dis.udp and d.arg.unknown_udp or d.arg.unknown end; " +
            "if not b then return end; d.arg.blob=b end; " +
            "return cdpi_z1_funcs[d.arg.z1_func](ctx,d) " +
            "end"

    // winws globals: filtered out earlier by PresetRenderer, kept here for manual input
    private val GLOBAL = setOf(
        "debug", "dry-run", "version", "comment", "intercept", "ctrack-timeouts", "ctrack-disable",
        "ipcache-lifetime", "ipcache-hostname", "wf-iface", "wf-l3", "wf-tcp", "wf-udp", "wf-tcp-in",
        "wf-tcp-out", "wf-udp-in", "wf-udp-out", "wf-tcp-empty", "wf-icmp-in", "wf-icmp-out", "wf-ipp-in",
        "wf-ipp-out", "wf-raw-part", "wf-raw-filter", "wf-filter-lan", "wf-raw", "wf-dup-check", "wf-save",
        "ssid-filter", "nlm-filter", "qnum", "uid", "user", "daemon", "pidfile",
    )

    private val PROFILE = setOf(
        "filter-l3", "filter-tcp", "filter-udp", "filter-icmp", "filter-ipp", "filter-l7", "ipset", "ipset-ip",
        "ipset-exclude", "ipset-exclude-ip", "hostlist", "hostlist-domains", "hostlist-exclude",
        "hostlist-exclude-domains", "hostlist-auto", "hostlist-auto-fail-threshold", "hostlist-auto-fail-time",
        "hostlist-auto-retrans-threshold", "hostlist-auto-retrans-reset", "hostlist-auto-retrans-maxseq",
        "hostlist-auto-incoming-maxseq", "hostlist-auto-udp-out", "hostlist-auto-udp-in", "hostlist-auto-debug",
        "filter-ssid", "name", "skip",
    )

    private val LEGACY = setOf(
        "ip-id", "dup", "dup-replace", "dup-ttl", "dup-ttl6", "dup-autottl", "dup-autottl6", "dup-tcp-flags-set",
        "dup-tcp-flags-unset", "dup-fooling", "dup-ts-increment", "dup-badseq-increment", "dup-badack-increment",
        "dup-ip-id", "dup-start", "dup-cutoff", "dpi-desync", "dpi-desync-ttl", "dpi-desync-ttl6",
        "dpi-desync-autottl", "dpi-desync-autottl6", "dpi-desync-tcp-flags-set", "dpi-desync-tcp-flags-unset",
        "dpi-desync-fooling", "dpi-desync-repeats", "dpi-desync-skip-nosni", "dpi-desync-split-pos",
        "dpi-desync-split-seqovl", "dpi-desync-split-seqovl-pattern", "dpi-desync-fakedsplit-pattern",
        "dpi-desync-fakedsplit-mod", "dpi-desync-hostfakesplit-midhost", "dpi-desync-hostfakesplit-mod",
        "dpi-desync-ipfrag-pos-tcp", "dpi-desync-ipfrag-pos-udp", "dpi-desync-ts-increment",
        "dpi-desync-badseq-increment", "dpi-desync-badack-increment", "dpi-desync-any-protocol",
        "dpi-desync-fake-http", "dpi-desync-fake-tls", "dpi-desync-fake-tls-mod", "dpi-desync-fake-unknown",
        "dpi-desync-fake-syndata", "dpi-desync-fake-quic", "dpi-desync-fake-wireguard", "dpi-desync-fake-dht",
        "dpi-desync-fake-discord", "dpi-desync-fake-stun", "dpi-desync-fake-unknown-udp",
        "dpi-desync-udplen-increment", "dpi-desync-udplen-pattern", "dpi-desync-start", "dpi-desync-cutoff",
    )

    private val ZERO_PHASE = setOf("synack", "syndata")

    private data class Opt(val name: String, val value: String?)

    private class Ctx {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val blobs = linkedMapOf<String, Pair<String, String>>() // key -> (name, expression)
    }

    fun convert(args: List<String>): Result {
        val ctx = Ctx()
        val parsed = parse(args, ctx)
        val profiles = mutableListOf(mutableListOf<Opt>())
        for (o in parsed) {
            when {
                o.name == "new" -> profiles += mutableListOf<Opt>()
                o.name in GLOBAL -> Unit // handled by the module / iptables
                else -> profiles.last() += o
            }
        }
        val converted = profiles.mapIndexed { i, p -> convertProfile(p, i + 1, ctx) }
        val out = mutableListOf<String>()
        ctx.blobs.values.sortedBy { it.first }.forEach { (name, expr) -> out += "--blob=$name:$expr" }
        out += "--lua-init=$COMPAT_LUA"
        converted.forEachIndexed { i, profile ->
            if (i > 0) out += "--new"
            out += profile
        }
        return Result(out, ctx.errors, ctx.warnings)
    }

    private fun parse(tokens: List<String>, ctx: Ctx): List<Opt> {
        val out = mutableListOf<Opt>()
        var i = 0
        while (i < tokens.size) {
            val t = tokens[i]
            if (!t.startsWith("--")) {
                ctx.errors += "unexpected argument '$t'"
                i++
                continue
            }
            val body = t.substring(2)
            val eq = body.indexOf('=')
            val name = (if (eq >= 0) body.substring(0, eq) else body).lowercase()
            var value: String? = if (eq >= 0) body.substring(eq + 1) else null
            if (eq < 0 && i + 1 < tokens.size && !tokens[i + 1].startsWith("--")) value = tokens[++i]
            out += Opt(name, value)
            i++
        }
        return out
    }

    private fun convertProfile(options: List<Opt>, index: Int, ctx: Ctx): List<String> {
        val out = mutableListOf<String>()
        val legacy = LinkedHashMap<String, MutableList<String?>>()
        for (o in options) {
            if (o.name in PROFILE) {
                if (o.name == "hostlist-auto") {
                    ctx.warnings += "profile $index: zapret2 routes auto-hostlist profiles only after the hostname is known"
                }
                out += fmt(o.name, o.value)
                continue
            }
            if (o.name !in LEGACY) {
                ctx.errors += "profile $index: --${o.name} has no zapret2 equivalent"
                continue
            }
            legacy.getOrPut(o.name) { mutableListOf() } += o.value
        }

        appendDup(out, legacy, index, ctx)

        val strategy = last(legacy, "dpi-desync")
        if (strategy.isNullOrBlank()) return out
        val modes = strategy.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (modes.isEmpty()) {
            ctx.errors += "profile $index: empty --dpi-desync"
            return out
        }
        val range = range(last(legacy, "dpi-desync-start"), last(legacy, "dpi-desync-cutoff"), index, ctx)
        val anyProtocol = enabled(legacy, "dpi-desync-any-protocol")
        val skipNoSni = !legacy.containsKey("dpi-desync-skip-nosni") || enabled(legacy, "dpi-desync-skip-nosni")
        val common = commonArgs(legacy, "dpi-desync", index, ctx)

        for (mode in modes.filter { it.lowercase() in ZERO_PHASE }) {
            out += "--payload=all"
            out += fmt("out-range", range)
            appendMode(out, mode, legacy, common, anyProtocol, skipNoSni, index, ctx)
        }
        val dataModes = modes.filter { it.lowercase() !in ZERO_PHASE }
        if (dataModes.isNotEmpty()) {
            out += fmt("out-range", range)
            out += if (anyProtocol) "--payload=all" else "--payload=known"
            dataModes.forEach { appendMode(out, it, legacy, common, anyProtocol, skipNoSni, index, ctx) }
        }
        return out
    }

    private fun appendDup(out: MutableList<String>, legacy: Map<String, List<String?>>, index: Int, ctx: Ctx) {
        val repeats = last(legacy, "dup")
        if (repeats.isNullOrBlank()) return
        val range = range(last(legacy, "dup-start"), last(legacy, "dup-cutoff"), index, ctx)
        val args = commonArgs(legacy, "dup", index, ctx)
        args += "dir=out"
        args += "repeats=$repeats"
        out += "--payload=all"
        out += fmt("out-range", range)
        out += luaDesync("send", args)
        if (enabled(legacy, "dup-replace")) out += "--lua-desync=drop:dir=out"
    }

    private fun appendMode(
        out: MutableList<String>,
        mode: String,
        legacy: Map<String, List<String?>>,
        common: List<String>,
        anyProtocol: Boolean,
        skipNoSni: Boolean,
        index: Int,
        ctx: Ctx,
    ) {
        val m = mode.lowercase()
        val args = if (m == "multisplit" || m == "multidisorder") {
            common.filter { it.equals("ip_id_conn", true) || it.startsWith("ip_id=", true) }.toMutableList()
        } else {
            common.toMutableList()
        }
        if (anyProtocol && m != "fakeknown" && m !in ZERO_PHASE) args += "payload=~empty"

        when (m) {
            "fake", "fakeknown" -> {
                args.addAll(0, fakePayloadArgs(legacy, index, ctx))
                val tlsMod = last(legacy, "dpi-desync-fake-tls-mod")
                if (!tlsMod.isNullOrBlank() && !tlsMod.equals("none", true)) args += "tls_mod=$tlsMod"
                out += legacyDesync("fake", args, skipNoSni)
            }
            "multisplit", "multidisorder" -> {
                splitArgs(args, legacy, m, index, ctx)
                args.removeAll { it.equals("repeats", true) || it.startsWith("repeats=", true) }
                out += legacyDesync(m, args, skipNoSni)
            }
            "fakedsplit", "fakeddisorder" -> {
                splitArgs(args, legacy, m, index, ctx)
                fakeSplitArgs(args, legacy, index, ctx)
                out += legacyDesync(m, args, skipNoSni)
            }
            "hostfakesplit" -> {
                addValue(args, "midhost", last(legacy, "dpi-desync-hostfakesplit-midhost"))
                val mods = last(legacy, "dpi-desync-hostfakesplit-mod")
                if (!mods.isNullOrBlank() && !mods.equals("none", true)) {
                    for (mod in csv(mods)) {
                        when {
                            mod.startsWith("host=", true) -> args += mod
                            mod.equals("altorder=1", true) ->
                                ctx.warnings += "profile $index: hostfakesplit altorder=1 converted to the standard order"
                            !mod.equals("altorder=0", true) ->
                                ctx.errors += "profile $index: unsupported hostfakesplit modifier '$mod'"
                        }
                    }
                }
                out += legacyDesync("hostfakesplit", args, skipNoSni)
            }
            "syndata" -> {
                val syndata = last(legacy, "dpi-desync-fake-syndata")
                if (!syndata.isNullOrBlank()) args.add(0, "blob=" + blob(syndata, index, ctx))
                out += legacyDesync("syndata", args, skipNoSni)
            }
            "synack" -> out += legacyDesync("synack", args, skipNoSni)
            "rst" -> out += legacyDesync("rst", args, skipNoSni)
            "rstack" -> {
                args += "rstack"
                out += legacyDesync("rst", args, skipNoSni)
            }
            "ipfrag2" -> {
                args += "ipfrag"
                addValue(args, "ipfrag_pos_tcp", last(legacy, "dpi-desync-ipfrag-pos-tcp"))
                addValue(args, "ipfrag_pos_udp", last(legacy, "dpi-desync-ipfrag-pos-udp"))
                out += legacyDesync("send", args, skipNoSni)
                out += legacyDesync("drop", emptyList(), skipNoSni)
            }
            "udplen" -> {
                addValue(args, "increment", last(legacy, "dpi-desync-udplen-increment"))
                val pattern = last(legacy, "dpi-desync-udplen-pattern")
                if (!pattern.isNullOrBlank()) args += "pattern=" + blob(pattern, index, ctx)
                out += legacyDesync("udplen", args, skipNoSni)
            }
            "tamper" -> out += legacyDesync("dht_dn", args, skipNoSni)
            else -> ctx.errors += "profile $index: strategy '$mode' has no zapret2 equivalent"
        }
    }

    private fun fakePayloadArgs(legacy: Map<String, List<String?>>, index: Int, ctx: Ctx): List<String> {
        val zero64 = "cdpi_z1_zero64"
        val zero256 = "cdpi_z1_zero256"
        fun b(option: String, def: String) = last(legacy, option)?.takeIf { it.isNotBlank() }?.let { blob(it, index, ctx) } ?: def
        val wireguard = b("dpi-desync-fake-wireguard", zero64)
        return listOf(
            "http_req=" + b("dpi-desync-fake-http", "fake_default_http"),
            "tls_client_hello=" + b("dpi-desync-fake-tls", "fake_default_tls"),
            "quic_initial=" + b("dpi-desync-fake-quic", "fake_default_quic"),
            "wireguard_initiation=$wireguard",
            "wireguard_response=$wireguard",
            "wireguard_cookie=$wireguard",
            "wireguard_keepalive=$wireguard",
            "dht=" + b("dpi-desync-fake-dht", zero64),
            "utp_bt_handshake=$zero64",
            "discord_ip_discovery=" + b("dpi-desync-fake-discord", zero64),
            "stun=" + b("dpi-desync-fake-stun", zero64),
            "unknown=" + b("dpi-desync-fake-unknown", zero256),
            "unknown_udp=" + b("dpi-desync-fake-unknown-udp", zero64),
        )
    }

    private fun splitArgs(args: MutableList<String>, legacy: Map<String, List<String?>>, mode: String, index: Int, ctx: Ctx) {
        addValue(args, "pos", last(legacy, "dpi-desync-split-pos"))
        val seqovl = last(legacy, "dpi-desync-split-seqovl")
        addValue(args, "seqovl", seqovl)
        val pattern = last(legacy, "dpi-desync-split-seqovl-pattern")
        if (!pattern.isNullOrBlank()) args += "seqovl_pattern=" + blob(pattern, index, ctx)
        if (mode == "multisplit" && !seqovl.isNullOrBlank() && seqovl.toIntOrNull() == null) {
            ctx.errors += "profile $index: zapret2 multisplit accepts only a numeric seqovl"
        }
    }

    private fun fakeSplitArgs(args: MutableList<String>, legacy: Map<String, List<String?>>, index: Int, ctx: Ctx) {
        val pattern = last(legacy, "dpi-desync-fakedsplit-pattern")
        if (!pattern.isNullOrBlank()) args += "pattern=" + blob(pattern, index, ctx)
        val mods = last(legacy, "dpi-desync-fakedsplit-mod")
        if (mods.isNullOrBlank() || mods.equals("none", true)) return
        for (mod in csv(mods)) {
            val alt = mod.takeIf { it.startsWith("altorder=", true) }?.substringAfter('=')?.toIntOrNull()
            if (alt == null) {
                ctx.errors += "profile $index: unsupported fakedsplit modifier '$mod'"
                continue
            }
            when (alt and 7) {
                0 -> Unit
                1 -> args += "nofake1"
                2 -> args += listOf("nofake1", "nofake2")
                3 -> args += listOf("nofake1", "nofake2", "nofake4")
                else -> ctx.errors += "profile $index: unsupported fakedsplit altorder '$alt'"
            }
            if (alt and 24 != 0) ctx.warnings += "profile $index: fakedsplit altorder '$alt' converted approximately"
        }
    }

    private fun commonArgs(legacy: Map<String, List<String?>>, prefix: String, index: Int, ctx: Ctx): MutableList<String> {
        val args = mutableListOf<String>()
        val p = if (prefix == "dup") "dup" else "dpi-desync"
        val ttl = last(legacy, "$p-ttl")
        addValue(args, "ip_ttl", ttl)
        addValue(args, "ip6_ttl", last(legacy, "$p-ttl6") ?: ttl)

        val defAuto = if (p == "dup") "+1:3-64" else "1:3-20"
        val defRange = if (p == "dup") "3-64" else "3-20"
        val auto = optional(legacy, "$p-autottl", defAuto)
        val auto6 = if (legacy.containsKey("$p-autottl6")) optional(legacy, "$p-autottl6", auto ?: defAuto) else auto
        addAutoTtl(args, "ip_autottl", auto, defRange)
        addAutoTtl(args, "ip6_autottl", auto6, defRange)

        addValue(args, "tcp_flags_set", last(legacy, "$p-tcp-flags-set")?.replace("PSH", "PUSH", true))
        addValue(args, "tcp_flags_unset", last(legacy, "$p-tcp-flags-unset")?.replace("PSH", "PUSH", true))

        val ipId = if (p == "dup") last(legacy, "dup-ip-id") ?: last(legacy, "ip-id") else last(legacy, "ip-id")
        if (!ipId.isNullOrBlank()) {
            when {
                ipId.equals("same", true) -> args += "ip_id=none"
                ipId.equals("seqgroup", true) -> args += listOf("ip_id=seq", "ip_id_conn")
                else -> args += "ip_id=$ipId"
            }
        }

        last(legacy, "$p-fooling")?.takeIf { it.isNotBlank() }?.let { fooling ->
            for (f in csv(fooling)) {
                when (f.lowercase()) {
                    "none" -> Unit
                    "md5sig" -> args += "tcp_md5"
                    "badsum" -> args += "badsum"
                    "datanoack" -> args += "tcp_flags_unset=ack"
                    "ts" -> args += "tcp_ts=" + (last(legacy, "$p-ts-increment") ?: "-600000")
                    "badseq" -> {
                        val seq = last(legacy, "$p-badseq-increment") ?: "-10000"
                        if (!isZero(seq)) {
                            args += "tcp_seq=$seq"
                        } else {
                            args += "tcp_ack=" + (last(legacy, "$p-badack-increment") ?: "-66000")
                            args += "tcp_ts_up"
                        }
                    }
                    "hopbyhop" -> args += "ip6_hopbyhop"
                    "hopbyhop2" -> args += listOf("ip6_hopbyhop", "ip6_hopbyhop2")
                    else -> ctx.errors += "profile $index: fooling '$f' has no zapret2 equivalent"
                }
            }
        }
        if (p != "dup") addValue(args, "repeats", last(legacy, "dpi-desync-repeats"))
        return args
    }

    private fun blob(value: String, index: Int, ctx: Ctx): String {
        val v = value.trim().trim('"')
        if (v.startsWith("0x", true)) return v
        if (v == "!") return "fake_default_tls"
        if (v.startsWith("!")) {
            ctx.warnings += "profile $index: TLS fake offset '$v' ignored"
            return "fake_default_tls"
        }
        var path = v
        var offset = 0L
        val at = v.indexOf('@')
        if (at >= 0) {
            val off = v.substring(0, at)
            path = v.substring(at + 1)
            if (off.startsWith("+")) off.substring(1).toLongOrNull()?.let { offset = it }
        }
        path = path.trim().trim('"')
        val key = "$offset:$path"
        ctx.blobs[key]?.let { return it.first }
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
        val name = "z1_" + digest.joinToString("") { "%02x".format(it) }.take(12)
        val expr = (if (offset > 0) "+$offset" else "") + "@" + path
        ctx.blobs[key] = name to expr
        return name
    }

    private fun range(start: String?, cutoff: String?, index: Int, ctx: Ctx): String {
        val s = counter(start)
        val c = counter(cutoff)
        if ((start != null && s == null) || (cutoff != null && c == null)) {
            ctx.errors += "profile $index: invalid range '${start ?: cutoff}'"
            return "a"
        }
        return when {
            s != null && c != null -> "$s<$c"
            s != null -> "$s-"
            c != null -> "<$c"
            else -> "a"
        }
    }

    private fun counter(value: String?): String? {
        if (value == null || value.length < 2) return null
        val mode = value[0].lowercaseChar()
        if (mode != 'n' && mode != 'd' && mode != 's') return null
        return if (value.substring(1).toLongOrNull() != null) mode + value.substring(1) else null
    }

    private fun optional(o: Map<String, List<String?>>, name: String, def: String?): String? {
        val v = o[name] ?: return null
        if (v.isEmpty()) return null
        return v.last() ?: def
    }

    private fun last(o: Map<String, List<String?>>, name: String): String? = o[name]?.lastOrNull()

    private fun enabled(o: Map<String, List<String?>>, name: String): Boolean {
        val v = o[name] ?: return false
        if (v.isEmpty()) return false
        val last = v.last()
        return last == null || last == "1" || last.equals("true", true)
    }

    private fun isZero(value: String): Boolean =
        if (value.startsWith("0x", true)) value.substring(2).toLongOrNull(16) == 0L else value.toLongOrNull() == 0L

    private fun addAutoTtl(args: MutableList<String>, name: String, value: String?, defRange: String) {
        if (value.isNullOrBlank() || value == "-" || value == "0:0-0" || value == "0,0-0") return
        var n = value
        if (n[0] != '+' && n[0] != '-') n = "-$n"
        n = n.replace(':', ',')
        if (!n.contains(',')) n += ",$defRange"
        args += "$name=$n"
    }

    private fun addValue(args: MutableList<String>, name: String, value: String?) {
        if (!value.isNullOrBlank()) args += "$name=$value"
    }

    private fun csv(value: String) = value.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    private fun legacyDesync(function: String, args: List<String>, skipNoSni: Boolean): String {
        val all = mutableListOf("z1_func=$function")
        if (skipNoSni) all += "z1_skip_nosni=1"
        all += args
        return luaDesync(COMPAT_FUNC, all)
    }

    private fun luaDesync(function: String, args: List<String>): String {
        val v = args.filter { it.isNotBlank() }
        return if (v.isEmpty()) "--lua-desync=$function" else "--lua-desync=$function:" + v.joinToString(":")
    }

    private fun fmt(name: String, value: String?) = if (value == null) "--$name" else "--$name=$value"
}
