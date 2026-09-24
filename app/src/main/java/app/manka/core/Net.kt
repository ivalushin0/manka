package app.manka.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.security.MessageDigest

/**
 * Network profiles. Every profile has its own engine and strategy:
 *  - "mobile"          mobile data
 *  - "wifi"            any Wi-Fi network without its own profile
 *  - "wifi_<md5[8]>"   one Wi-Fi network (by SSID)
 * The module computes the same key from the SSID (md5sum of the raw SSID).
 */
object Profiles {
    const val WIFI = "wifi"
    const val MOBILE = "mobile"

    fun wifiKey(ssid: String): String {
        val md5 = MessageDigest.getInstance("MD5").digest(ssid.toByteArray(Charsets.UTF_8))
        return "wifi_" + md5.joinToString("") { "%02x".format(it) }.take(8)
    }

    fun isSsid(key: String) = key.startsWith("wifi_")

    /** Profile used while [key] has no settings of its own. */
    fun parent(key: String): String? = when {
        isSsid(key) -> WIFI
        key == MOBILE -> WIFI
        else -> null
    }

    /** Network type without root (no SSID): profile to show before the module status is known. */
    fun currentType(context: Context): String {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return WIFI
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return WIFI
        return if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
            !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        ) MOBILE else WIFI
    }

    /** Own profile key of the network the phone is on right now. */
    fun keyOf(type: String?, ssid: String?): String = when {
        type == MOBILE -> MOBILE
        !ssid.isNullOrBlank() -> wifiKey(ssid)
        else -> WIFI
    }
}
