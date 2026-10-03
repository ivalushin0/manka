package app.manka.ui.screens

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.TextButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.material3.AlertDialog
import app.manka.core.NetLists
import app.manka.core.ProxyConfig
import app.manka.ui.StatusColors
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AssistChip
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
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
import app.manka.ui.SwitchRow

@OptIn(ExperimentalLayoutApi::class)
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
                !status.rootOk -> SectionCard(
                    title = stringResource(R.string.no_root_title),
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ) {
                    Text(stringResource(R.string.no_root_text))
                    Button(onClick = { vm.refresh() }) { Text(stringResource(R.string.retry)) }
                }
                !status.installed -> SectionCard(
                    title = stringResource(R.string.module_missing_title),
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                ) {
                    Text(stringResource(R.string.module_missing_text))
                    Button(onClick = { vm.installModule() }, enabled = !busy && Module.hasBundledModule(context)) {
                        Text(stringResource(R.string.module_install))
                    }
                }
                status.pendingRemoval -> SectionCard(containerColor = MaterialTheme.colorScheme.errorContainer) {
                    Text(stringResource(R.string.module_removing))
                }
                status.disabled -> SectionCard(containerColor = MaterialTheme.colorScheme.errorContainer) {
                    Text(stringResource(R.string.module_disabled))
                }
                bundledVersion > status.versionCode -> SectionCard(containerColor = MaterialTheme.colorScheme.tertiaryContainer) {
                    Text(stringResource(R.string.module_update_available))
                    Button(onClick = { vm.installModule() }, enabled = !busy) { Text(stringResource(R.string.module_update)) }
                }
            }

            val update by vm.update.collectAsState()
            if (update.available || update.progress != null || update.installing) UpdateCard(vm)

            // ---- power: the button shows the state in traffic-light colours
            val own = status.ownProfile
            val engine = prefs.engine(own)
            val running = prefs.enabled && status.engineRunning && status.rulesOk
            val shown = remember(prefsVersion) { shownServices(vm) }
            val servicesOk = shown.values.all { it >= 100 }
            val stateColor = when {
                !prefs.enabled -> null
                running && servicesOk -> StatusColors.ok
                running || status.failed.isEmpty() -> StatusColors.warn
                else -> StatusColors.bad
            }
            SectionCard {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    FilledIconButton(
                        onClick = { vm.setEnabled(!prefs.enabled) },
                        enabled = status.usable && !busy,
                        modifier = Modifier.size(128.dp),
                        colors = if (stateColor != null) IconButtonDefaults.filledIconButtonColors(
                            containerColor = stateColor,
                            contentColor = StatusColors.onColor,
                        ) else IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    ) {
                        Icon(Icons.Filled.PowerSettingsNew, stringResource(R.string.toggle_bypass), Modifier.size(64.dp))
                    }
                    Spacer(Modifier.size(12.dp))
                    val stateText = when {
                        !prefs.enabled -> stringResource(R.string.state_off)
                        running -> stringResource(
                            R.string.state_on_net,
                            (Engine.of(status.values["engine"]) ?: engine).title,
                            profileTitle(vm, own),
                        )
                        status.failed.isNotEmpty() -> stringResource(R.string.state_failed, status.failed.joinToString())
                        else -> stringResource(R.string.state_starting)
                    }
                    Text(stateText, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    if (running && !servicesOk) Hint(stringResource(R.string.state_partial))
                    if (prefs.enabled && status.usable && !status.hasNfqueue && engine != Engine.BYEDPI) {
                        Hint(stringResource(R.string.no_nfqueue))
                    }
                }
            }

            // ---- network profile: a summary, the settings are on their own screen
            SectionCard(title = stringResource(R.string.profile_of, profileTitle(vm, own))) {
                val active = remember(presetsList, engine, own, prefsVersion) { vm.presets.active(engine, own) }
                Text(stringResource(R.string.profile_summary, engine.title, active.name), style = MaterialTheme.typography.bodyMedium)
                if (prefs.lastCheckTime > 0 && prefs.lastCheckRate >= 0) {
                    Hint(
                        stringResource(
                            R.string.last_check,
                            DateUtils.getRelativeTimeSpanString(prefs.lastCheckTime).toString(),
                            prefs.lastCheckRate,
                        ),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.pickedProfile.value = null; nav.navigate("profile") }) {
                        Text(stringResource(R.string.profile_open))
                    }
                    Button(onClick = { nav.switchTab("auto") }) { Text(stringResource(R.string.strategy_autoselect)) }
                }
            }

            if (prefs.enabled && status.usable) ServiceStatusCard(vm, nav, prefsVersion)

            // ---- telegram
            SectionCard(title = stringResource(R.string.telegram)) {
                SwitchRow(
                    title = stringResource(R.string.tgws_title),
                    subtitle = when {
                        !prefs.tgws -> stringResource(R.string.tgws_off)
                        !status.tgwsRunning -> stringResource(R.string.tgws_not_running)
                        !status.tgwsDirect -> stringResource(R.string.tgws_running_cf, prefs.tgwsPort)
                        else -> stringResource(R.string.tgws_running, prefs.tgwsPort)
                    },
                    checked = prefs.tgws,
                    enabled = status.usable && !busy,
                    onChange = { vm.setTgws(it) },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { openTelegram(context, vm) }, enabled = prefs.tgws) {
                        Text(stringResource(R.string.tgws_connect))
                    }
                    OutlinedButton(onClick = { nav.navigate("telegram") }) { Text(stringResource(R.string.settings)) }
                }
            }

            // ---- proxy for apps
            SectionCard(title = stringResource(R.string.proxy_title)) {
                SwitchRow(
                    title = proxyHomeTitle(vm),
                    subtitle = proxyState(vm),
                    checked = prefs.proxy,
                    enabled = status.usable && !busy && status.proxyAvailable &&
                        (prefs.proxy || prefs.proxyKey.isNotBlank()),
                    onChange = { prefs.proxy = it; vm.apply() },
                )
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

/** Last check of every service as coloured chips; a tap shows what failed and what to do. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ServiceStatusCard(vm: MainViewModel, nav: NavHostController, version: Int) {
    val checking by vm.checkingServices.collectAsState()
    val shown = remember(version) { shownServices(vm) }
    val failed = remember(version) { vm.prefs.serviceFailed }
    val time = remember(version) { vm.prefs.serviceStatusTime }
    var open by remember { mutableStateOf<String?>(null) }
    SectionCard(title = stringResource(R.string.services_title)) {
        if (shown.isEmpty()) {
            Hint(stringResource(R.string.services_never))
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (ServiceCheck.GROUPS + ServiceCheck.EXTRA).forEach { id ->
                    val rate = shown[id] ?: return@forEach
                    val color = when {
                        rate >= 100 -> StatusColors.ok
                        rate <= 0 -> StatusColors.bad
                        else -> StatusColors.warn
                    }
                    AssistChip(
                        onClick = { open = id },
                        label = { Text(serviceTitle(id)) },
                        leadingIcon = { Box(Modifier.size(10.dp).background(color, CircleShape)) },
                    )
                }
            }
            if (time > 0) Hint(stringResource(R.string.services_time, DateUtils.getRelativeTimeSpanString(time).toString()))
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
