package app.manka.core

/** Command line helpers shared by presets, the store converter and auto selection. */
object Args {
    /** Splits a command line into arguments, honouring single and double quotes. */
    fun split(line: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var quote: Char? = null
        var hasToken = false
        for (c in line) {
            when {
                quote != null -> if (c == quote) quote = null else cur.append(c)
                c == '"' || c == '\'' -> { quote = c; hasToken = true }
                c.isWhitespace() -> {
                    if (hasToken || cur.isNotEmpty()) out += cur.toString()
                    cur.clear()
                    hasToken = false
                }
                else -> { cur.append(c); hasToken = true }
            }
        }
        if (hasToken || cur.isNotEmpty()) out += cur.toString()
        return out
    }

    /** Joins arguments back into a readable command line. */
    fun join(args: List<String>): String = args.joinToString(" ") { a ->
        if (a.isEmpty() || a.any { it.isWhitespace() || it == '"' || it == '\'' }) "\"" + a.replace("\"", "'") + "\"" else a
    }

    /** Replaces the placeholders a strategy may use with real on-device paths. */
    fun resolve(args: List<String>, kitDir: String? = null, fakeSni: String = "www.google.com"): List<String> =
        args.map { a ->
            var r = a
                .replace("\$FAKE", Paths.FAKE)
                .replace("\$LUA", Paths.LUA)
                .replace("\$LISTS", Paths.LISTS)
                .replace("\$EXCLUDE", Paths.EXCLUDE_LIST)
                .replace("{sni}", fakeSni)
            if (kitDir != null) r = r.replace("\$KIT", kitDir)
            r
        }

    private val portItem = Regex("""^\d{1,5}([:-]\d{1,5})?$""")

    /** Normalises a port list for iptables: "80, 443,50000-50100" -> "80,443,50000:50100". Invalid items are dropped. */
    fun ports(value: String): String = value.split(',', ' ')
        .map { it.trim() }
        .filter { it.isNotEmpty() && portItem.matches(it) }
        .map { it.replace('-', ':') }
        .distinct()
        .joinToString(",")
}
