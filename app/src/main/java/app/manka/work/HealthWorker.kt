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
import app.manka.autoselect.ServiceCheck
import app.manka.autoselect.SiteChecker
import app.manka.autoselect.Targets
import app.manka.core.Engine
import app.manka.core.Module
import app.manka.core.Services
import app.manka.core.Profiles
import app.manka.core.Prefs
import app.manka.core.StatusNotifier
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
        // an auto selection has the bypass in test mode: nothing to check or repair now
        if (app.autoSelector.busy) return Result.success()

        var status = Module.status()
        if (!status.usable) return Result.success()
        if (status.values["testing"] == "1") return Result.success()
        // the network changed and the module did not notice (netwatch unavailable)
        val runningKey = status.values["key"].orEmpty()
        val netMismatch = (status.netType == "wifi" || status.netType == "mobile") &&
            runningKey.isNotEmpty() && runningKey != status.ownProfile
        val engineBroken = prefs.enabled && (!status.engineRunning || !status.rulesOk || netMismatch)
        val tgwsBroken = prefs.tgws && !status.tgwsRunning
        if (engineBroken || tgwsBroken) {
            app.applier.apply()
            status = Module.status()
        }
        // store kits and lists: at most every few days, only the installed ones
        if (prefs.autoStoreUpdate && System.currentTimeMillis() - prefs.storeCheckTime > STORE_INTERVAL) {
            val updated = runCatching { app.store.updateInstalled() }.getOrDefault(0)
            prefs.storeCheckTime = System.currentTimeMillis()
            if (updated > 0 && prefs.enabled) {
                app.applier.apply()
                status = Module.status()
            }
        }
        StatusNotifier.update(applicationContext, status)
        if (!prefs.enabled || app.autoSelector.busy) return Result.success()

        val profile = status.ownProfile
        val engine = prefs.engine(profile)
        val targets = prefs.healthTargets.ifEmpty { Targets.groups.first { it.id == "youtube" }.urls }
        val sites = SiteChecker().check(targets, 1, prefs.autoTimeoutSec)
        val ok = sites.sumOf { it.ok }
        val total = sites.sumOf { it.total }.coerceAtLeast(1)
        val rate = ok * 100 / total
        prefs.lastCheckTime = System.currentTimeMillis()
        prefs.lastCheckRate = rate
        val serviceRates = runCatching { ServiceCheck.run(prefs, prefs.autoTimeoutSec) }.getOrNull()

        val expected = prefs.baselineRate(profile).takeIf { it > 0 } ?: 100
        val degraded = rate < 50 && rate < expected - 25
        if (degraded) reselectMain(app, profile, status.ssid, engine, targets, rate)
        if (serviceRates != null && engine != Engine.BYEDPI) reselectServices(app, profile, status.ssid, engine, serviceRates)
        return Result.success()
    }

    private suspend fun reselectMain(app: MankaApp, profile: String, ssid: String?, engine: Engine, targets: List<String>, rate: Int) {
        val prefs = app.prefs
        if (!prefs.autoReselect) {
            notify(applicationContext, applicationContext.getString(R.string.notify_degraded, rate))
            return
        }
        val final = app.autoSelector.run(
            AutoRequest(
                engines = listOf(engine),
                profile = profile,
                profileLabel = ssid,
                targets = targets,
                full = false,
                includeStore = true,
                requests = 1,
                timeoutSec = prefs.autoTimeoutSec,
            ),
        )
        val best = final.best
        if (best != null && best.percent > rate) {
            app.autoSelector.applyResult(best, profile, ssid, null)
            notify(applicationContext, applicationContext.getString(R.string.notify_reselected, best.percent))
        } else {
            notify(applicationContext, applicationContext.getString(R.string.notify_reselect_failed, rate))
        }
    }

    /** Services with their own strategy that stopped opening get a new one (or a notification). */
    private suspend fun reselectServices(app: MankaApp, profile: String, ssid: String?, engine: Engine, rates: Map<String, Int>) {
        val prefs = app.prefs
        val ctx = applicationContext
        Services.all.forEachIndexed { i, s ->
            val own = prefs.servicePreset(engine, profile, s.id) ?: return@forEachIndexed
            if (own == Services.OFF) return@forEachIndexed
            val groupRates = s.targetGroups.mapNotNull { rates[it] }
            if (groupRates.isEmpty()) return@forEachIndexed
            val rate = groupRates.average().toInt()
            if (rate >= 50) return@forEachIndexed
            if (app.autoSelector.busy) return@forEachIndexed
            val id = NOTIFICATION_ID + 1 + i
            if (!prefs.autoReselect) {
                notify(ctx, ctx.getString(R.string.notify_service_down, s.title), id)
                return@forEachIndexed
            }
            val targets = Targets.groups.filter { it.id in s.targetGroups }.flatMap { it.urls }
            val final = app.autoSelector.run(
                AutoRequest(
                    engines = listOf(engine),
                    profile = profile,
                    profileLabel = ssid,
                    targets = targets,
                    full = false,
                    includeStore = true,
                    requests = 1,
                    timeoutSec = prefs.autoTimeoutSec,
                    service = s.id,
                ),
            )
            val best = final.best
            if (best != null && best.percent > rate) {
                app.autoSelector.applyResult(best, profile, ssid, s.id)
                notify(ctx, ctx.getString(R.string.notify_service_reselected, s.title, best.percent), id)
            } else {
                notify(ctx, ctx.getString(R.string.notify_service_failed, s.title), id)
            }
        }
    }

    companion object {
        private const val NAME = "health"
        private const val NOTIFICATION_ID = 10
        private const val STORE_INTERVAL = 3 * 24 * 60 * 60 * 1000L

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

        fun notify(context: Context, text: String, id: Int = NOTIFICATION_ID) {
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
            NotificationManagerCompat.from(context).notify(id, n)
        }
    }
}
