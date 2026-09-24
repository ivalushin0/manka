package app.manka.work

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.manka.MainActivity
import app.manka.MankaApp
import app.manka.R
import app.manka.autoselect.AutoRequest
import app.manka.autoselect.SiteChecker
import app.manka.autoselect.Targets
import app.manka.core.Module
import app.manka.core.Prefs
import java.util.concurrent.TimeUnit

/**
 * Periodic check: keeps the module running and verifies that the active strategy still opens
 * the sites it was selected for. When it stops working a quick re-selection runs automatically.
 */
class HealthWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = MankaApp.of(applicationContext)
        val prefs = app.prefs
        if (!prefs.enabled && !prefs.tgws) return Result.success()

        var status = Module.status()
        if (!status.usable) return Result.success()
        val engineBroken = prefs.enabled && (!status.engineRunning || !status.rulesOk)
        val tgwsBroken = prefs.tgws && !status.tgwsRunning
        if (engineBroken || tgwsBroken) {
            app.applier.apply()
            status = Module.status()
        }
        if (!prefs.enabled || app.autoSelector.state.value.running) return Result.success()

        val targets = prefs.healthTargets.ifEmpty { Targets.groups.first { it.id == "youtube" }.urls }
        val sites = SiteChecker().check(targets, 1, prefs.autoTimeoutSec)
        val ok = sites.sumOf { it.ok }
        val total = sites.sumOf { it.total }.coerceAtLeast(1)
        val rate = ok * 100 / total
        prefs.lastCheckTime = System.currentTimeMillis()
        prefs.lastCheckRate = rate

        val expected = prefs.baselineRate.takeIf { it > 0 } ?: 100
        val degraded = rate < 50 && rate < expected - 25
        if (!degraded) return Result.success()

        if (!prefs.autoReselect) {
            notify(applicationContext, applicationContext.getString(R.string.notify_degraded, rate))
            return Result.success()
        }
        val final = app.autoSelector.run(
            AutoRequest(
                engine = prefs.engine,
                targets = targets,
                full = false,
                includeStore = true,
                requests = 1,
                timeoutSec = prefs.autoTimeoutSec,
            ),
        )
        val best = final.best
        if (best != null && best.percent > rate) {
            app.autoSelector.applyResult(best, prefs.engine)
            notify(applicationContext, applicationContext.getString(R.string.notify_reselected, best.percent))
        } else {
            notify(applicationContext, applicationContext.getString(R.string.notify_reselect_failed, rate))
        }
        return Result.success()
    }

    companion object {
        private const val NAME = "health"
        private const val NOTIFICATION_ID = 10

        fun schedule(context: Context, prefs: Prefs) {
            val wm = WorkManager.getInstance(context)
            val hours = prefs.checkIntervalHours
            if (hours <= 0) {
                wm.cancelUniqueWork(NAME)
                return
            }
            val request = PeriodicWorkRequestBuilder<HealthWorker>(hours.toLong(), TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInitialDelay(15, TimeUnit.MINUTES)
                .build()
            wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        fun notify(context: Context, text: String) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
                android.os.Build.VERSION.SDK_INT >= 33
            ) return
            val intent = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val n = NotificationCompat.Builder(context, MankaApp.CHANNEL_STATUS)
                .setSmallIcon(R.drawable.ic_tile)
                .setContentTitle(context.getString(R.string.app_name))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(intent)
                .setAutoCancel(true)
                .build()
            @Suppress("MissingPermission")
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, n)
        }
    }
}
