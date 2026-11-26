package app.manka.ui.screens

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import app.manka.core.NetLists
import app.manka.core.ProxyConfig
import app.manka.ui.Banner
import app.manka.ui.BannerKind
import app.manka.ui.StatusColors
import app.manka.ui.StatusPill
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import app.manka.R
import app.manka.core.Engine
import app.manka.core.Module
import app.manka.core.Profiles
import app.manka.core.Services
import app.manka.autoselect.ServiceCheck
import app.manka.autoselect.Targets
import app.manka.switchTab
import app.manka.ui.Hint
import app.manka.ui.MainViewModel
import app.manka.ui.ScreenScaffold
import app.manka.ui.SectionCard

@Composable
fun HomeScreen(vm: MainViewModel, nav: NavHostController) {
    val status by vm.status.collectAsState()
    val loaded by vm.loaded.collectAsState()
    val busy by vm.busy.collectAsState()
    val presetsList by vm.presets.presets.collectAsState()
    @Suppress("UNUSED_VARIABLE") val prefsVersion by vm.prefs.version.collectAsState()
    val context = LocalContext.current
    val prefs = vm.prefs
    val bundledVersion = remember { Module.bundledVersionCode(context) }
    val pickedProfile by vm.pickedProfile.collectAsState()

    ScreenScaffold(
        title = stringResource(R.string.app_name),
        subtitle = stringResource(R.string.home_subtitle, profileTitle(vm, status.ownProfile)),
        busy = busy,
        actions = {
            IconButton(onClick = { vm.refresh() }) { Icon(Icons.Filled.Refresh, stringResource(R.string.refresh)) }
        },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(pad),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!loaded) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() }
                return@Column
            }

            // ---- problems with root / module
            when {
                !status.rootOk -> Banner(
                    kind = BannerKind.Bad,
                    title = stringResource(R.string.no_root_title),
                    text = stringResource(R.string.no_root_text),
                ) { Button(onClick = { vm.refresh() }) { Text(stringResource(R.string.retry)) } }
                !status.installed -> Banner(
                    kind = BannerKind.Warn,
                    title = stringResource(R.string.module_missing_title),
                    text = stringResource(R.string.module_missing_text),
                ) {
                    Button(onClick = { vm.installModule() }, enabled = !busy && Module.hasBundledModule(context)) {
                        Text(stringResource(R.string.module_install))
                    }
                }
                status.pendingRemoval -> Banner(BannerKind.Bad, stringResource(R.string.module_removing))
                status.disabled -> Banner(BannerKind.Bad, stringResource(R.string.module_disabled))
                bundledVersion > status.versionCode -> Banner(
                    kind = BannerKind.Warn,
                    title = stringResource(R.string.module_update_available),
                ) {
                    Button(onClick = { vm.installModule() }, enabled = !busy) { Text(stringResource(R.string.module_update)) }
                }
            }

            val update by vm.update.collectAsState()
            if (update.available || update.progress != null || update.installing) UpdateCard(vm)

            // ---- power: state, profile and the two actions of the PC build's home screen
            val own = status.ownProfile
            val engine = prefs.engine(own)
            val running = prefs.enabled && status.engineRunning && status.rulesOk
            val shown = remember(prefsVersion) { shownServices(vm) }
            val servicesOk = shown.values.all { it >= 100 }
            val okCount = shown.values.count { it >= 100 }
            val active = remember(presetsList, engine, own, prefsVersion) { vm.presets.active(engine, own) }

            SectionCard(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(
                            stringResource(if (prefs.enabled) R.string.home_state_on else R.string.home_state_off),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Hint("${engine.title} · ${profileTitle(vm, own)} · ${active.name}")
                    }
                    Switch(
                        checked = prefs.enabled,
                        onCheckedChange = { vm.setEnabled(it) },
                        enabled = status.usable && !busy,
                    )
                }
                Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Stat(
                        if (shown.isEmpty()) "—" else "$okCount/${shown.size}",
                        stringResource(R.string.home_stat_services),
                        Modifier.weight(1f),
                    )
                    Stat(
                        if (!prefs.tgws) "—" else if (status.tgwsRunning) "ok" else "—",
                        stringResource(R.string.home_stat_telegram),
                        Modifier.weight(1f),
                    )
                    Stat(
                        lastCheckShort(prefs.serviceStatusTime),
                        stringResource(R.string.home_stat_checked),
                        Modifier.weight(1f),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { vm.pickedProfile.value = null; nav.navigate("profile") },
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.profile_open)) }
                    Button(onClick = { nav.switchTab("auto") }, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.strategy_autoselect))
                    }
                }
                if (running && !servicesOk) Hint(stringResource(R.string.state_partial))
                if (prefs.enabled && status.usable && !status.hasNfqueue && engine != Engine.BYEDPI) {
                    Hint(stringResource(R.string.no_nfqueue))
                }
                if (prefs.enabled && status.failed.isNotEmpty()) {
                    Hint(stringResource(R.string.state_failed, status.failed.joinToString()))
                }
            }

            if (prefs.enabled && status.usable) ServiceStatusCard(vm, nav, prefsVersion)

            // ---- telegram
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.telegram),
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    StatusPill(
                        text = stringResource(
                            when {
                                !prefs.tgws -> R.string.tgws_off
                                status.tgwsRunning -> R.string.tgws_status_on
                                else -> R.string.tgws_not_running
                            },
                        ),
                        color = when {
                            !prefs.tgws -> MaterialTheme.colorScheme.onSurfaceVariant
                            status.tgwsRunning -> StatusColors.ok
                            else -> StatusColors.bad
                        },
                    )
                }
                Hint(
                    when {
                        !prefs.tgws -> stringResource(R.string.tgws_off)
                        !status.tgwsRunning -> stringResource(R.string.tgws_not_running)
                        !status.tgwsDirect -> stringResource(R.string.tgws_running_cf, prefs.tgwsPort)
                        else -> stringResource(R.string.tgws_running, prefs.tgwsPort)
                    },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { openTelegram(context, vm) }, enabled = prefs.tgws) {
                        Text(stringResource(R.string.tgws_connect))
                    }
                    OutlinedButton(onClick = { nav.navigate("telegram") }) { Text(stringResource(R.string.settings)) }
                }
            }

            // ---- proxy for apps
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.proxy_title),
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    StatusPill(
                        text = stringResource(if (status.proxyRunning) R.string.tgws_status_on else R.string.tgws_status_off),
                        color = if (status.proxyRunning) StatusColors.ok else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Hint(proxyHomeTitle(vm))
                Hint(proxyState(vm))
                OutlinedButton(onClick = { nav.navigate("proxy") }) { Text(stringResource(R.string.settings)) }
            }
            Spacer(Modifier.size(8.dp))
        }
    }
}

fun telegramLink(vm: MainViewModel) =
    "tg://proxy?server=127.0.0.1&port=${vm.prefs.tgwsPort}&secret=dd${vm.prefs.tgwsSecret}"

fun openTelegram(context: Context, vm: MainViewModel) {
    val link = telegramLink(vm)
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        val cm = context.getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("tg proxy", link))
        vm.say(R.string.tgws_link_copied)
    }
}

/** Human name of a network profile: "Mobile", "Other Wi-Fi" or the SSID. */
@Composable
fun profileTitle(vm: MainViewModel, key: String): String = when {
    key == Profiles.MOBILE -> stringResource(R.string.net_mobile)
    key == Profiles.WIFI -> stringResource(R.string.net_wifi_other)
    else -> vm.prefs.knownWifi[key]
        ?: vm.status.value.ssid?.takeIf { Profiles.wifiKey(it) == key }
        ?: stringResource(R.string.net_wifi)
}

/** A big value with a small caption, as in the PC build's home stats. */
@Composable
private fun Stat(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/** The last service check as a short "11 мин" instead of a full relative phrase. */
@Composable
private fun lastCheckShort(time: Long): String {
    if (time <= 0) return "—"
    val min = (System.currentTimeMillis() - time) / 60000
    return when {
        min < 1 -> stringResource(R.string.home_ago_now)
        min < 60 -> stringResource(R.string.home_ago_min, min)
        min < 24 * 60 -> stringResource(R.string.home_ago_hour, min / 60)
        else -> stringResource(R.string.home_ago_day, min / (24 * 60))
    }
}


/** "Gemini through your server · KZ", or the number of listed addresses when the list was edited. */
@Composable
private fun proxyHomeTitle(vm: MainViewModel): String {
    val prefs = vm.prefs
    val country = prefs.proxyCountry.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
    val domains = ProxyConfig.domains(prefs.proxyDomains)
    val gemini = NetLists.current(prefs).geminiDomains
    return when {
        prefs.proxyWholeApps -> stringResource(R.string.proxy_home_whole) + country
        domains.toSet() == gemini.toSet() -> stringResource(R.string.proxy_home_gemini) + country
        domains.containsAll(gemini) -> (domains.size - gemini.size).let {
            pluralStringResource(R.plurals.proxy_home_gemini_plus, it, it)
        } + country
        else -> stringResource(R.string.proxy_home_list, domains.size) + country
    }
}

/** Results of the last check, only of the services whose feature is on. */
private fun shownServices(vm: MainViewModel): Map<String, Int> {
    val prefs = vm.prefs
    return prefs.serviceStatus.filterKeys { id ->
        when (id) {
            ServiceCheck.WHATSAPP -> prefs.metaFix
            ServiceCheck.TELEGRAM -> prefs.tgws
            ServiceCheck.GEMINI -> prefs.proxy
            else -> true
        }
    }
}

@Composable
private fun serviceTitle(id: String): String = when (id) {
    ServiceCheck.WHATSAPP -> "WhatsApp"
    ServiceCheck.TELEGRAM -> "Telegram"
    ServiceCheck.GEMINI -> "Gemini"
    else -> Targets.groups.firstOrNull { it.id == id }?.let { shortGroupTitle(stringResource(it.title)) } ?: id
}

/** Last check of every service as a grid of dots; a tap shows what failed and what to do. */
@Composable
private fun ServiceStatusCard(vm: MainViewModel, nav: NavHostController, version: Int) {
    val checking by vm.checkingServices.collectAsState()
    val shown = remember(version) { shownServices(vm) }
    val failed = remember(version) { vm.prefs.serviceFailed }
    val time = remember(version) { vm.prefs.serviceStatusTime }
    var open by remember { mutableStateOf<String?>(null) }
    val ids = (ServiceCheck.GROUPS + ServiceCheck.EXTRA).filter { shown.containsKey(it) }
    val pill = when {
        shown.isEmpty() -> stringResource(R.string.services_none) to MaterialTheme.colorScheme.onSurfaceVariant
        shown.values.all { it >= 100 } -> stringResource(R.string.services_all_ok) to StatusColors.ok
        shown.values.any { it <= 0 } -> stringResource(R.string.services_failed) to StatusColors.bad
        else -> stringResource(R.string.services_partial) to StatusColors.warn
    }
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.services_title),
                Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            StatusPill(pill.first, pill.second)
        }
        if (shown.isEmpty()) {
            Hint(stringResource(R.string.services_never))
        } else {
            ids.chunked(4).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { id ->
                        val rate = shown[id] ?: 0
                        val color = when {
                            rate >= 100 -> StatusColors.ok
                            rate <= 0 -> StatusColors.bad
                            else -> StatusColors.warn
                        }
                        Column(
                            Modifier.weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                                .clickable { open = id }
                                .padding(vertical = 9.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            Box(Modifier.size(7.dp).background(color, CircleShape))
                            Text(
                                serviceTitle(id),
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            if (time > 0) Hint(stringResource(R.string.services_time, DateUtils.getRelativeTimeSpanString(time).toString()))
            val bad = failed.filterValues { it.isNotEmpty() }
            if (bad.isNotEmpty()) {
                Hint(bad.entries.joinToString("; ") { "${serviceTitle(it.key)}: ${it.value.joinToString(", ")}" })
            }
            Hint(stringResource(R.string.services_tap_hint))
        }
        OutlinedButton(onClick = { vm.checkServices() }, enabled = !checking) {
            Text(stringResource(if (checking) R.string.services_checking else R.string.services_check))
        }
    }

    val id = open ?: return
    val rate = shown[id] ?: 0
    val bad = failed[id].orEmpty()
    val extra = id in ServiceCheck.EXTRA
    AlertDialog(
        onDismissRequest = { open = null },
        title = { Text(serviceTitle(id)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    when {
                        rate >= 100 -> stringResource(R.string.service_dialog_ok)
                        bad.isNotEmpty() -> stringResource(R.string.service_dialog_failed, bad.joinToString("\n"))
                        else -> stringResource(R.string.service_dialog_down)
                    },
                )
                when (id) {
                    ServiceCheck.WHATSAPP -> Hint(stringResource(R.string.service_whatsapp_hint))
                    ServiceCheck.TELEGRAM -> Hint(stringResource(R.string.service_telegram_hint))
                    ServiceCheck.GEMINI -> Hint(stringResource(R.string.service_gemini_hint, vm.prefs.proxyCountry.ifBlank { "?" }))
                    else -> if (rate < 100) Hint(stringResource(R.string.service_pick_hint))
                }
            }
        },
        confirmButton = {
            when {
                id == ServiceCheck.TELEGRAM -> TextButton(onClick = { open = null; nav.navigate("telegram") }) {
                    Text(stringResource(R.string.settings))
                }
                id == ServiceCheck.GEMINI -> TextButton(onClick = { open = null; nav.navigate("proxy") }) {
                    Text(stringResource(R.string.settings))
                }
                !extra && rate < 100 -> TextButton(onClick = {
                    open = null
                    vm.prefs.autoGroups = setOf(id)
                    nav.switchTab("auto")
                }) { Text(stringResource(R.string.service_dialog_pick)) }
                else -> TextButton(onClick = { open = null }) { Text(stringResource(R.string.close)) }
            }
        },
        dismissButton = if (id == ServiceCheck.TELEGRAM || id == ServiceCheck.GEMINI || (!extra && rate < 100)) {
            { TextButton(onClick = { open = null }) { Text(stringResource(R.string.close)) } }
        } else null,
    )
}

/** "Instagram (app hosts)" -> "Instagram" */
private fun shortGroupTitle(title: String) = title.substringBefore(" (").trim()
