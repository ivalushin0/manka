package app.manka.core

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.manka.MainActivity
import app.manka.MankaApp
import app.manka.R
import kotlinx.coroutines.launch

/** Optional ongoing notification: bypass state, engine, network, and an on/off button. */
object StatusNotifier {
    const val CHANNEL = "persistent"
    private const val ID = 20

    fun update(context: Context, status: ModuleStatus?) {
        val app = MankaApp.of(context)
        val prefs = app.prefs
        val nm = NotificationManagerCompat.from(context)
        if (!prefs.statusNotification) {
            nm.cancel(ID)
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        val on = prefs.enabled
        val title = context.getString(if (on) R.string.notify_on else R.string.notify_off)
        val text = buildList {
            if (on) {
                val profile = status?.ownProfile ?: Profiles.currentType(context)
                add((Engine.of(status?.values?.get("engine")) ?: prefs.engine(profile)).title)
                val ssid = status?.ssid
                if (ssid != null) add(ssid) else if (status?.netType == "mobile") add(context.getString(R.string.net_mobile))
                if (status != null && status.usable && !(status.engineRunning && status.rulesOk)) add(context.getString(R.string.notify_not_running))
            }
            if (prefs.tgws) add("Telegram " + if (status?.tgwsRunning != false) "✓" else "✗")
        }.joinToString(" · ")

        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val toggle = PendingIntent.getBroadcast(
            context, 1, Intent(context, ToggleReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, context.getString(if (on) R.string.notify_turn_off else R.string.notify_turn_on), toggle)
            .build()
        @Suppress("MissingPermission")
        nm.notify(ID, n)
    }

    /** The on/off button of the notification. */
    class ToggleReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val app = MankaApp.of(context)
            val pending = goAsync()
            app.prefs.enabled = !app.prefs.enabled
            app.appScope.launch {
                try {
                    update(context, app.applier.apply())
                } catch (_: Exception) {
                    update(context, null)
                } finally {
                    pending.finish()
                }
            }
        }
    }
}
