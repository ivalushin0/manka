package app.manka.ui.screens

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import app.manka.R
import app.manka.core.Engine
import app.manka.core.Profiles
import app.manka.core.Services
import app.manka.switchTab
import app.manka.ui.Hint
import app.manka.ui.MainViewModel
import app.manka.ui.ScreenScaffold
import app.manka.ui.SectionCard

/** Engine, strategy and service strategies of every network profile (moved off the home screen). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfileScreen(vm: MainViewModel, nav: NavHostController) {
    val status by vm.status.collectAsState()
    val busy by vm.busy.collectAsState()
    val presetsList by vm.presets.presets.collectAsState()
    val prefsVersion by vm.prefs.version.collectAsState()
    val pickedProfile by vm.pickedProfile.collectAsState()
    val prefs = vm.prefs
    val own = status.ownProfile

    ScreenScaffold(title = stringResource(R.string.profile), onBack = { nav.popBackStack() }, busy = busy) { pad ->
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(pad),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val picked = pickedProfile ?: own
            val keys = remember(prefsVersion, own) {
                (listOf(own, Profiles.MOBILE, Profiles.WIFI) + prefs.knownWifi.keys.sorted()).distinct()
            }
            SectionCard {
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
            }

            val pEngine = prefs.engine(picked)
            SectionCard(title = stringResource(R.string.engine)) {
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
                if (pEngine != Engine.BYEDPI) ServiceStrategies(vm, nav, pEngine, picked, prefsVersion)
            }

            if (prefs.lastCheckTime > 0 && prefs.lastCheckRate >= 0) {
                SectionCard(title = stringResource(R.string.health_title)) {
                    Text(
                        stringResource(
                            R.string.last_check,
                            DateUtils.getRelativeTimeSpanString(prefs.lastCheckTime).toString(),
                            prefs.lastCheckRate,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    val failed = remember(prefsVersion) { prefs.lastCheckFailed }
                    if (failed.isNotEmpty()) Hint(stringResource(R.string.last_check_failed, failed.joinToString(", ")))
                    Hint(stringResource(R.string.health_hint))
                }
            }

            if (Profiles.isSsid(picked) && prefs.knownWifi.containsKey(picked)) {
                TextButton(onClick = { vm.forgetWifi(picked) }) { Text(stringResource(R.string.profile_forget)) }
            }
        }
    }
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
            Text(
                value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2,
                modifier = Modifier.weight(0.6f),
            )
        }
    }
}
