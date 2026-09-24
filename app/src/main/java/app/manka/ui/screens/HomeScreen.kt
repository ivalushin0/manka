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
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
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
import app.manka.switchTab
import app.manka.ui.Hint
import app.manka.ui.MainViewModel
import app.manka.ui.ScreenScaffold
import app.manka.ui.SectionCard
import app.manka.ui.SwitchRow

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

            // ---- power
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
                        running -> stringResource(R.string.state_on, prefs.engine.title)
                        status.failed.isNotEmpty() -> stringResource(R.string.state_failed, status.failed.joinToString())
                        else -> stringResource(R.string.state_starting)
                    }
                    Text(stateText, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    if (prefs.enabled && status.usable && !status.hasNfqueue && prefs.engine != Engine.BYEDPI) {
                        Hint(stringResource(R.string.no_nfqueue))
                    }
                }
            }

            // ---- engine
            SectionCard(title = stringResource(R.string.engine)) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    Engine.entries.forEachIndexed { i, e ->
                        SegmentedButton(
                            selected = prefs.engine == e,
                            onClick = { vm.setEngine(e) },
                            shape = SegmentedButtonDefaults.itemShape(i, Engine.entries.size),
                            enabled = !busy,
                        ) { Text(e.title) }
                    }
                }
                Hint(
                    stringResource(
                        when (prefs.engine) {
                            Engine.ZAPRET2 -> R.string.engine_zapret2_hint
                            Engine.ZAPRET -> R.string.engine_zapret_hint
                            Engine.BYEDPI -> R.string.engine_byedpi_hint
                        },
                    ),
                )
                val active = remember(presetsList, prefs.engine, prefsVersion) { vm.presets.active(prefs.engine) }
                Text(stringResource(R.string.strategy_current, active.name), style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { nav.navigate("presets/${prefs.engine.id}") }) {
                        Text(stringResource(R.string.strategy_choose))
                    }
                    Button(onClick = { nav.switchTab("auto") }) { Text(stringResource(R.string.strategy_autoselect)) }
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
