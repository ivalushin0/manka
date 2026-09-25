package app.manka.core

/** Built-in presets and the strategy catalogue used by auto selection. */
object Strategies {

    data class Candidate(
        val name: String,
        val template: String,
        val preset: Preset? = null,
        val engine: Engine = preset?.engine ?: Engine.BYEDPI,
    )

    // ------------------------------------------------------------------ full command lines

    /** Wraps a TLS strategy into a complete profile set for the engine. */
    fun compose(engine: Engine, tls: String): String = when (engine) {
        Engine.ZAPRET -> listOf(
            "--filter-tcp=80 --filter-l7=http --hostlist-exclude=\$EXCLUDE" +
                " --dpi-desync=fake,multisplit --dpi-desync-split-pos=method+2 --dpi-desync-fooling=md5sig",
            "--filter-tcp=443 --filter-l7=tls --hostlist-exclude=\$EXCLUDE $tls",
            "--filter-udp=443 --filter-l7=quic --hostlist-exclude=\$EXCLUDE --dpi-desync=fake --dpi-desync-repeats=6",
        ).joinToString(" --new ")
        Engine.ZAPRET2 -> listOf(
            "--filter-tcp=80 --filter-l7=http --hostlist-exclude=\$EXCLUDE --payload=http_req" +
                " --lua-desync=fake:blob=fake_default_http:tcp_md5 --lua-desync=multisplit:pos=method+2",
            "--filter-tcp=443 --filter-l7=tls --hostlist-exclude=\$EXCLUDE --payload=tls_client_hello $tls",
            "--filter-udp=443 --filter-l7=quic --hostlist-exclude=\$EXCLUDE --payload=quic_initial" +
                " --lua-desync=fake:blob=fake_default_quic:repeats=6",
        ).joinToString(" --new ")
        Engine.BYEDPI -> tls
    }

    fun builtinPresets(): List<Preset> = listOf(
        Preset(
            id = "builtin-byedpi-1", engine = Engine.BYEDPI, name = "ByeDPI: disorder + tlsrec",
            source = PresetSource.BUILTIN, template = "-d1 -s1+s -r1+s -S -a1",
        ),
        Preset(
            id = "builtin-byedpi-2", engine = Engine.BYEDPI, name = "ByeDPI: fake + md5sig",
            source = PresetSource.BUILTIN, template = "-d1 -f-1 -t8 -S -r1+s",
        ),
        Preset(
            id = "builtin-byedpi-3", engine = Engine.BYEDPI, name = "ByeDPI: auto",
            source = PresetSource.BUILTIN, template = "-o1 -d1 -r1+s -At,r,s -f-1 -t8 -S -As -s1+s -d3+s",
        ),
        Preset(
            id = "builtin-zapret-1", engine = Engine.ZAPRET, name = "zapret: fake + multisplit",
            source = PresetSource.BUILTIN,
            template = compose(Engine.ZAPRET, "--dpi-desync=fake,multisplit --dpi-desync-split-pos=1,midsld --dpi-desync-fooling=md5sig"),
        ),
        Preset(
            id = "builtin-zapret-2", engine = Engine.ZAPRET, name = "zapret: multidisorder",
            source = PresetSource.BUILTIN,
            template = compose(Engine.ZAPRET, "--dpi-desync=multidisorder --dpi-desync-split-pos=1,midsld"),
        ),
        Preset(
            id = "builtin-zapret2-1", engine = Engine.ZAPRET2, name = "zapret2: fake + multidisorder",
            source = PresetSource.BUILTIN,
            template = compose(
                Engine.ZAPRET2,
                "--lua-desync=fake:blob=fake_default_tls:tcp_md5:tcp_seq=-10000 --lua-desync=multidisorder:pos=1,midsld",
            ),
        ),
        Preset(
            id = "builtin-zapret2-2", engine = Engine.ZAPRET2, name = "zapret2: multisplit seqovl",
            source = PresetSource.BUILTIN,
            template = compose(Engine.ZAPRET2, "--lua-desync=multisplit:pos=10:seqovl=1"),
        ),
    )

    // ------------------------------------------------------------------ candidates

    fun candidates(engine: Engine, full: Boolean): List<Candidate> {
        val tls = when (engine) {
            Engine.ZAPRET -> if (full) zapretFull() else ZAPRET_QUICK
            Engine.ZAPRET2 -> if (full) zapret2Full() else ZAPRET2_QUICK
            Engine.BYEDPI -> if (full) byedpiFull() else BYEDPI_QUICK
        }
        return tls.distinct().map { Candidate(name = it, template = compose(engine, it), engine = engine) }
    }

    private val ZAPRET_QUICK = listOf(
        "--dpi-desync=multisplit --dpi-desync-split-pos=1",
        "--dpi-desync=multisplit --dpi-desync-split-pos=2",
        "--dpi-desync=multisplit --dpi-desync-split-pos=1,midsld",
        "--dpi-desync=multidisorder --dpi-desync-split-pos=1,midsld",
        "--dpi-desync=multisplit --dpi-desync-split-seqovl=1 --dpi-desync-split-pos=sld+1",
        "--dpi-desync=multisplit --dpi-desync-split-seqovl=681 --dpi-desync-split-pos=1" +
            " --dpi-desync-split-seqovl-pattern=\$FAKE/tls_clienthello_www_google_com.bin",
        "--dpi-desync=fake --dpi-desync-fooling=md5sig",
        "--dpi-desync=fake --dpi-desync-fooling=badseq",
        "--dpi-desync=fake --dpi-desync-autottl",
        "--dpi-desync=fake,multisplit --dpi-desync-split-pos=1,midsld --dpi-desync-fooling=md5sig",
        "--dpi-desync=fake,multidisorder --dpi-desync-split-pos=1,midsld --dpi-desync-fooling=badseq",
        "--dpi-desync=fake,fakedsplit --dpi-desync-split-pos=1 --dpi-desync-fooling=md5sig",
        "--dpi-desync=fake,fakeddisorder --dpi-desync-split-pos=midsld --dpi-desync-fooling=badseq",
        "--dpi-desync=fake,multisplit --dpi-desync-split-pos=1 --dpi-desync-fooling=md5sig" +
            " --dpi-desync-fake-tls=\$FAKE/tls_clienthello_www_google_com.bin",
        "--dpi-desync=fake --dpi-desync-fake-tls-mod=rnd,dupsid,sni=www.google.com --dpi-desync-fooling=md5sig --dpi-desync-repeats=6",
        "--dpi-desync=fake,multidisorder --dpi-desync-split-pos=1,midsld --dpi-desync-fooling=md5sig --dpi-desync-repeats=6",
        "--dpi-desync=syndata,multisplit --dpi-desync-split-pos=1",
        "--dpi-desync=hostfakesplit --dpi-desync-fooling=md5sig",
    )

    private fun zapretFull(): List<String> {
        val out = ZAPRET_QUICK.toMutableList()
        val positions = listOf("1", "2", "midsld", "1,midsld", "sniext+1", "host+1", "1,sniext+1,host+1,midsld")
        for (mode in listOf("multisplit", "multidisorder")) {
            for (pos in positions) out += "--dpi-desync=$mode --dpi-desync-split-pos=$pos"
        }
        for (pos in listOf("1", "sld+1", "midsld")) {
            out += "--dpi-desync=multisplit --dpi-desync-split-seqovl=1 --dpi-desync-split-pos=$pos"
        }
        val foolings = listOf(
            "--dpi-desync-fooling=md5sig", "--dpi-desync-fooling=badseq", "--dpi-desync-autottl",
            "--dpi-desync-ttl=4", "--dpi-desync-fooling=datanoack",
        )
        val combos = listOf(
            "--dpi-desync=fake",
            "--dpi-desync=fake,multisplit --dpi-desync-split-pos=1,midsld",
            "--dpi-desync=fake,multidisorder --dpi-desync-split-pos=1,midsld",
            "--dpi-desync=fake,fakedsplit --dpi-desync-split-pos=1",
            "--dpi-desync=fake,fakeddisorder --dpi-desync-split-pos=midsld",
        )
        for (c in combos) for (f in foolings) out += "$c $f"
        for (f in foolings) {
            out += "--dpi-desync=fake $f --dpi-desync-repeats=6"
            out += "--dpi-desync=fake,multisplit --dpi-desync-split-pos=1 $f" +
                " --dpi-desync-fake-tls=\$FAKE/tls_clienthello_www_google_com.bin"
        }
        return out
    }

    private val ZAPRET2_QUICK = listOf(
        "--lua-desync=multisplit:pos=1",
        "--lua-desync=multisplit:pos=2",
        "--lua-desync=multisplit:pos=1,midsld",
        "--lua-desync=multidisorder:pos=1,midsld",
        "--lua-desync=multisplit:pos=sniext+1",
        "--lua-desync=multisplit:pos=10:seqovl=1",
        "--lua-desync=multisplit:pos=1:seqovl=#fake_default_tls:seqovl_pattern=fake_default_tls",
        "--lua-desync=tcpseg:pos=0,-1:seqovl=1 --lua-desync=drop",
        "--lua-desync=fake:blob=fake_default_tls:tcp_md5",
        "--lua-desync=fake:blob=fake_default_tls:badsum",
        "--lua-desync=fake:blob=fake_default_tls:tcp_seq=-3000",
        "--lua-desync=fake:blob=fake_default_tls:ip_autottl=-2,3-20",
        "--lua-desync=fake:blob=fake_default_tls:tcp_md5 --lua-desync=multisplit:pos=1,midsld",
        "--lua-desync=fake:blob=fake_default_tls:tcp_md5:tcp_seq=-10000 --lua-desync=multidisorder:pos=1,midsld",
        "--lua-desync=fake:blob=fake_default_tls:tcp_md5:tls_mod=rnd,dupsid --lua-desync=multisplit:pos=2",
        "--lua-desync=fake:blob=0x00000000:tcp_md5 --lua-desync=fake:blob=fake_default_tls:tcp_md5:tls_mod=rnd,dupsid",
        "--lua-desync=multisplit:blob=fake_default_tls:tcp_md5:pos=2:nodrop",
        "--lua-desync=fake:blob=fake_default_tls:tcp_ts=-1000 --lua-desync=multidisorder:pos=2",
        "--lua-desync=wssize:wsize=1:scale=6 --lua-desync=multisplit:pos=1",
        // Flowseal-style (fake SNI of a whitelisted host): the only ones that also opened
        // youtubei.googleapis.com and Cloudflare-hosted sites without the 16 KB freeze on a test phone
        "--lua-desync=hostfakesplit:host=www.google.com:tcp_ts=-600000:repeats=4",
        "--lua-desync=hostfakesplit:host=www.google.com:tcp_ts=-600000:repeats=4:ip_id=zero",
        "--lua-desync=hostfakesplit:host=www.google.com:tcp_md5:repeats=4",
        "--lua-desync=fake:blob=fake_default_tls:tls_mod=rnd,dupsid,sni=www.google.com:tcp_ts=-1000:repeats=6 --lua-desync=multisplit:pos=1",
    )

    private fun zapret2Full(): List<String> {
        val out = ZAPRET2_QUICK.toMutableList()
        val positions = listOf(
            "2", "1", "sniext+1", "sniext+4", "host+1", "midsld", "1,midsld", "1,midsld,1220",
            "1,sniext+1,host+1,midsld-2,midsld,midsld+2,endhost-1",
        )
        for (f in listOf("multisplit", "multidisorder")) for (p in positions) out += "--lua-desync=$f:pos=$p"
        for (p in listOf("10", "10,sniext+1", "10,sniext+4", "10,midsld")) out += "--lua-desync=multisplit:pos=$p:seqovl=1"
        val foolings = listOf(
            "tcp_md5", "badsum", "tcp_seq=-3000", "tcp_seq=1000000", "tcp_ack=-66000:tcp_ts_up", "tcp_ts=-1000",
            "ip_autottl=-1,3-20", "ip_autottl=-2,3-20", "ip_ttl=4",
        )
        for (f in foolings) {
            out += "--lua-desync=fake:blob=fake_default_tls:$f"
            out += "--lua-desync=fake:blob=fake_default_tls:$f --lua-desync=multisplit:pos=1,midsld"
            out += "--lua-desync=fake:blob=fake_default_tls:$f --lua-desync=multidisorder:pos=1,midsld"
            out += "--lua-desync=fake:blob=fake_default_tls:$f:tls_mod=rnd,dupsid,padencap"
        }
        for (p in listOf("1", "midsld", "1,midsld")) out += "--lua-desync=wssize:wsize=1:scale=6 --lua-desync=multisplit:pos=$p"
        return out
    }

    private val BYEDPI_QUICK = listOf(
        "-d1",
        "-s1+s",
        "-d1+s",
        "-o1",
        "-q1+s",
        "-s1 -d3+s",
        "-d1 -r1+s",
        "-s1+s -r1+s",
        "-f-1 -t8",
        "-f-1 -t6 -S",
        "-d1 -f-1 -t8 -S",
        "-o1 -d1 -r1+s",
        "-d1 -s1+s -r1+s -S -a1",
        "-s1 -q1 -r1+s",
        "-d1+sm -r1+s",
        "-n {sni} -f-1 -t8 -Qr",
        "-d1 -d3+s -s6+s -d9+s -s12+s -r1+s",
        "-d1 -At,r,s -f-1 -t8 -S",
        "-s1 -At,r -d1 -r1+s",
        "-o1 -At -f-1 -t8 -S -a1",
    )

    private fun byedpiFull(): List<String> {
        val out = BYEDPI_QUICK.toMutableList()
        val methods = listOf("-s", "-d", "-o", "-q")
        val positions = listOf("1", "2", "1+s", "3+s", "1+sm", "-1")
        for (m in methods) for (p in positions) {
            out += "$m$p"
            out += "$m$p -r1+s"
        }
        for (p in listOf("-1", "1+s", "3+s")) {
            out += "-f$p -t8"
            out += "-f$p -t6 -S"
            out += "-d1 -f$p -t8 -S -r1+s"
        }
        return out
    }
}
