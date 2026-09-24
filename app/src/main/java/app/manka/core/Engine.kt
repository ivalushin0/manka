package app.manka.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class Engine(val id: String, val title: String) {
    @SerialName("zapret2") ZAPRET2("zapret2", "zapret2"),
    @SerialName("zapret") ZAPRET("zapret", "zapret"),
    @SerialName("byedpi") BYEDPI("byedpi", "ByeDPI");

    companion object {
        fun of(id: String?): Engine? = entries.firstOrNull { it.id == id }
    }
}

object Paths {
    const val MODULE = "/data/adb/modules/manka"
    const val MODULE_UPDATE = "/data/adb/modules_update/manka"
    const val SCRIPT = "$MODULE/manka.sh"
    const val DATA = "/data/adb/manka"
    const val ARGS = "$DATA/args"
    const val FILES = "$DATA/files"
    const val FAKE = "$FILES/fake"
    const val LUA = "$FILES/lua"
    const val LISTS = "$DATA/lists"
    const val KITS = "$DATA/kits"
    const val LOGS = "$DATA/logs"
    const val EXCLUDE_LIST = "$LISTS/manka-exclude.txt"
}
