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

            // ---- power
            val own = status.ownProfile
            val engine = prefs.engine(own)
            val running = prefs.enabled && status.engineRunning && status.rulesOk
            SectionCard {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    FilledIconButton(
                        onClick = { vm.setEnabled(!prefs.enabled) },
                        enabled = status.usable && !busy,
                        modifier = Modifier.size(128.dp),
                        colors = if (prefs.enabled) IconButtonDefaults.filledIconButtonColors()
                        else IconButtonDefaults.filledIconButtonColors(
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
                    if (prefs.enabled && status.usable && !status.hasNfqueue && engine != Engine.BYEDPI) {
                        Hint(stringResource(R.string.no_nfqueue))
                    }
                }
            }

            // ---- network profile: engine + strategy
            val picked = pickedProfile ?: own
            val keys = remember(prefsVersion, own) {
                (listOf(own, Profiles.MOBILE, Profiles.WIFI) + prefs.knownWifi.keys.sorted()).distinct()
            }
            SectionCard(title = stringResource(R.string.profile)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    keys.forEach { key ->
                        FilterChip(
                            selected = picked == key,
                            onClick = { vm.pickedProfile.value = key },
                            label = { Text(profileTitle(vm, key) + if (key == own) " •" else "") },
                        )
                    }
                }
                Hint(stringResource(R.string.profile_hint, profileTitle(vm, own)))
                if (Profiles.isSsid(picked) && !prefs.hasOwnEngine(picked)) {
                    Hint(stringResource(R.string.profile_inherits))
                }
                val pEngine = prefs.engine(picked)
                Text(stringResource(R.string.engine), style = MaterialTheme.typography.titleSmall)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    Engine.entries.forEachIndexed { i, e ->
                        SegmentedButton(
                            selected = pEngine == e,
                            onClick = { vm.setEngine(picked, e) },
                            shape = SegmentedButtonDefaults.itemShape(i, Engine.entries.size),
                            enabled = !busy,
                        ) { Text(e.title) }
                    }
                }
                Hint(
                    stringResource(
                        when (pEngine) {
                            Engine.ZAPRET2 -> R.string.engine_zapret2_hint
                            Engine.ZAPRET -> R.string.engine_zapret_hint
                            Engine.BYEDPI -> R.string.engine_byedpi_hint
                        },
                    ),
                )
                val active = remember(presetsList, pEngine, picked, prefsVersion) { vm.presets.active(pEngine, picked) }
                Text(stringResource(R.string.strategy_current, active.name), style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { nav.navigate("presets/${pEngine.id}/$picked") }) {
                        Text(stringResource(R.string.strategy_choose))
                    }
                    Button(onClick = { nav.switchTab("auto") }) { Text(stringResource(R.string.strategy_autoselect)) }
                }
                if (pEngine != Engine.BYEDPI) {
                    ServiceStrategies(vm, nav, pEngine, picked, prefsVersion)
                }
                if (Profiles.isSsid(picked) && prefs.knownWifi.containsKey(picked)) {
                    TextButton(onClick = { vm.forgetWifi(picked) }) { Text(stringResource(R.string.profile_forget)) }
                }
                if (prefs.lastCheckTime > 0 && prefs.lastCheckRate >= 0) {
                    Hint(
                        stringResource(
                            R.string.last_check,
                            DateUtils.getRelativeTimeSpanString(prefs.lastCheckTime).toString(),
                            prefs.lastCheckRate,
                        ),
                    )
                }
            }

            if (prefs.enabled && status.usable) ServiceStatusCard(vm, nav, prefsVersion)

            // ---- telegram
            SectionCard(title = stringResource(R.string.telegram)) {
                SwitchRow(
                    title = stringResource(R.string.tgws_title),
                    subtitle = when {
                        !prefs.tgws -> stringResource(R.string.tgws_off)
                        status.tgwsRunning -> stringResource(R.string.tgws_running, prefs.tgwsPort)
                        else -> stringResource(R.string.tgws_not_running)
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

/** zapret / zapret2: own strategies of YouTube, Instagram, ... in the profile (folded). */
@Composable
private fun ServiceStrategies(vm: MainViewModel, nav: NavHostController, engine: Engine, profile: String, version: Int) {
    var open by remember { mutableStateOf(false) }
    val presets by vm.presets.presets.collectAsState()
    val own = remember(version, presets, engine, profile) {
        Services.all.associate { it.id to vm.prefs.servicePreset(engine, profile, it.id) }
    }
    val count = own.values.count { it != null }
    Row(
        Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.service_strategies, count),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f),
        )
        Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null)
    }
    if (!open) return
    Hint(stringResource(R.string.service_strategies_hint))
    Services.all.forEach { s ->
        val id = own[s.id]
        val value = when (id) {
            null -> stringResource(R.string.service_use_main)
            Services.OFF -> stringResource(R.string.service_off)
            else -> vm.presets.byId(id)?.name ?: id
        }
        Row(
            Modifier.fillMaxWidth().clickable { nav.navigate("presets/${engine.id}/$profile?service=${s.id}") }.padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(s.title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.4f))
            Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2,
                modifier = Modifier.weight(0.6f))
        }
    }
}

/** Last check of every service ("YouTube ✓ · Instagram ✗"); a failing one leads to auto selection. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ServiceStatusCard(vm: MainViewModel, nav: NavHostController, version: Int) {
    val checking by vm.checkingServices.collectAsState()
    val status = remember(version) { vm.prefs.serviceStatus }
    val time = remember(version) { vm.prefs.serviceStatusTime }
    SectionCard(title = stringResource(R.string.services_title)) {
        if (status.isEmpty()) {
            Hint(stringResource(R.string.services_never))
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ServiceCheck.GROUPS.forEach { id ->
                    val rate = status[id] ?: return@forEach
                    val group = Targets.groups.firstOrNull { it.id == id } ?: return@forEach
                    val mark = when {
                        rate >= 100 -> "✓"
                        rate <= 0 -> "✗"
                        else -> "~"
                    }
                    AssistChip(
                        onClick = {
                            if (rate < 100) {
                                vm.prefs.autoGroups = setOf(id)
                                nav.switchTab("auto")
                            }
                        },
                        label = { Text("$mark ${shortGroupTitle(stringResource(group.title))}") },
                    )
                }
            }
            if (time > 0) Hint(stringResource(R.string.services_time, DateUtils.getRelativeTimeSpanString(time).toString()))
            if (status.values.any { it < 100 }) Hint(stringResource(R.string.services_tap_hint))
        }
        OutlinedButton(onClick = { vm.checkServices() }, enabled = !checking) {
            Text(stringResource(if (checking) R.string.services_checking else R.string.services_check))
        }
    }
}

/** "Instagram (app hosts)" -> "Instagram" */
private fun shortGroupTitle(title: String) = title.substringBefore(" (").trim()
