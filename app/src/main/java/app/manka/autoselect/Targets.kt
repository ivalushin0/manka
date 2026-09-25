package app.manka.autoselect

import androidx.annotation.StringRes
import app.manka.R
import app.manka.store.Http
import app.manka.store.KitLoader
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class TargetGroup(val id: String, @StringRes val title: Int, val urls: List<String>)

object Targets {
    val groups = listOf(
        TargetGroup(
            "youtube", R.string.group_youtube,
            listOf(
                "https://www.youtube.com/",
                "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg",
                "https://yt3.ggpht.com/",
                "https://redirector.googlevideo.com/report_mapping",
                "https://youtubei.googleapis.com/",
                "https://m.youtube.com/",
                "https://youtube.googleapis.com/",
                "https://yt3.googleusercontent.com/",
            ),
        ),
        TargetGroup(
            "discord", R.string.group_discord,
            listOf(
                "https://discord.com/",
                "https://gateway.discord.gg/",
                "https://cdn.discordapp.com/",
                "https://discordapp.com/",
                "https://media.discordapp.net/",
            ),
        ),
        TargetGroup(
            "general", R.string.group_general,
            listOf(
                "https://rutracker.org/",
                "https://www.instagram.com/",
                "https://www.facebook.com/",
                "https://x.com/",
                "https://www.linkedin.com/",
                "https://proton.me/",
            ),
        ),
        TargetGroup(
            "instagram", R.string.group_instagram,
            listOf(
                "https://www.instagram.com/",
                "https://i.instagram.com/",
                "https://static.cdninstagram.com/",
                "https://scontent.cdninstagram.com/",
                "https://graph.facebook.com/",
            ),
        ),
        TargetGroup(
            "facebook", R.string.group_facebook,
            listOf(
                "https://www.facebook.com/",
                "https://m.facebook.com/",
                "https://graph.facebook.com/",
                "https://b-graph.facebook.com/",
                "https://static.xx.fbcdn.net/",
                "https://scontent.xx.fbcdn.net/",
            ),
        ),
        TargetGroup(
            "tiktok", R.string.group_tiktok,
            listOf(
                "https://www.tiktok.com/",
                "https://m.tiktok.com/",
                "https://www.tiktokv.com/",
                "https://api16-normal-c-useast1a.tiktokv.com/",
                "https://p16-sign-va.tiktokcdn.com/",
                "https://v16m.tiktokcdn.com/",
            ),
        ),
        TargetGroup(
            "cdn", R.string.group_cdn,
            listOf(
                "https://cdnjs.cloudflare.com/ajax/libs/jquery/3.7.1/jquery.min.js",
                "https://speed.cloudflare.com/__down?bytes=65536",
                "https://www.cloudflare.com/",
            ),
        ),
        TargetGroup(
            "telegram", R.string.group_telegram,
            listOf(
                "https://web.telegram.org/",
                "https://telegram.org/",
                "https://t.me/",
            ),
        ),
    )

    /** Hosts from hyperion-cs "TCP 16-20" DPI checker suite (the one CDPIUI uses), best effort. */
    suspend fun dpiSuite(): List<String> = runCatching {
        KitLoader.json.parseToJsonElement(Http.text("https://hyperion-cs.github.io/dpi-checkers/ru/tcp-16-20/suite.v2.json"))
            .jsonArray.mapNotNull { it.jsonObject["host"]?.jsonPrimitive?.contentOrNull }
            .map { "https://$it/" }
    }.getOrDefault(emptyList())

    fun parseCustom(text: String): List<String> = text.lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .map { if (it.startsWith("http://") || it.startsWith("https://")) it else "https://$it/" }
        .distinct()
}
