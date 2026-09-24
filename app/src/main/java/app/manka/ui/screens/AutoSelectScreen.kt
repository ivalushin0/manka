package app.manka.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.manka.R
import app.manka.autoselect.AutoRequest
import app.manka.autoselect.AutoState
import app.manka.autoselect.Phase
import app.manka.autoselect.SiteResult
import app.manka.autoselect.StrategyResult
import app.manka.autoselect.Targets
import app.manka.core.Engine
import app.manka.ui.Hint
import app.manka.ui.MainViewModel
import app.manka.ui.Mono
import app.manka.ui.ScreenScaffold
import app.manka.ui.SectionCard
import app.manka.ui.SwitchRow
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AutoSelectScreen(vm: MainViewModel) {
    val state by vm.autoSelector.state.collectAsState()
    val status by vm.status.collectAsState()
    val busy by vm.busy.collectAsState()
    val prefs = vm.prefs
    val scope = rememberCoroutineScope()

    var engine by remember { mutableStateOf(prefs.engine) }
    var groups by remember { mutableStateOf(prefs.autoGroups) }
    var custom by remember { mutableStateOf(prefs.customSites) }
    var full by remember { mutableStateOf(prefs.autoFullMode) }
    var includeStore by remember { mutableStateOf(prefs.autoIncludeStore) }
    var requests by remember { mutableStateOf(prefs.autoRequests) }
    var loadingSuite by remember { mutableStateOf(false) }

    fun start() {
        prefs.autoGroups = groups
        prefs.customSites = custom
        prefs.autoFullMode = full
        prefs.autoIncludeStore = includeStore
        prefs.autoRequests = requests
        scope.launch {
            val urls = Targets.groups.filter { it.id in groups }.flatMap { it.urls }.toMutableList()
            if ("dpi" in groups) {
                loadingSuite = true
                urls += Targets.dpiSuite()
                loadingSuite = false
            }
            urls += Targets.parseCustom(custom)
            if (urls.isEmpty()) {
                vm.say(R.string.auto_no_targets)
                return@launch
            }
            vm.autoSelector.start(
                AutoRequest(
                    engine = engine,
                    targets = urls.distinct(),
                    full = full,
                    includeStore = includeStore,
                    requests = requests,
                    timeoutSec = prefs.autoTimeoutSec,
                    stopAfterPerfect = if (full) 0 else 3,
                ),
            )
        }
    }

    ScreenScaffold(title = stringResource(R.string.auto_title), busy = busy) { pad ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = pad, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!state.running && state.phase != Phase.DONE && state.phase != Phase.CANCELLED) {
                item {
                    SectionCard(title = stringResource(R.string.engine)) {
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            Engine.entries.forEachIndexed { i, e ->
                                SegmentedButton(
                                    selected = engine == e,
                                    onClick = { engine = e },
                                    shape = SegmentedButtonDefaults.itemShape(i, Engine.entries.size),
                                ) { Text(e.title) }
                            }
                        }
                        Hint(stringResource(R.string.auto_engine_hint))
                    }
                }
                item {
                    SectionCard(title = stringResource(R.string.auto_sites)) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Targets.groups.forEach { g ->
                                FilterChip(
                                    selected = g.id in groups,
                                    onClick = { groups = if (g.id in groups) groups - g.id else groups + g.id },
                                    label = { Text(stringResource(g.title)) },
                                )
                            }
                            FilterChip(
                                selected = "dpi" in groups,
                                onClick = { groups = if ("dpi" in groups) groups - "dpi" else groups + "dpi" },
                                label = { Text(stringResource(R.string.group_dpi_suite)) },
                            )
                        }
                        OutlinedTextField(
                            value = custom,
                            onValueChange = { custom = it },
                            label = { Text(stringResource(R.string.auto_custom)) },
                            placeholder = { Text("example.com\nhttps://site.org/page") },
                            minLines = 2,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                item {
                    SectionCard(title = stringResource(R.string.auto_mode)) {
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            SegmentedButton(
                                selected = !full, onClick = { full = false },
                                shape = SegmentedButtonDefaults.itemShape(0, 2),
                            ) { Text(stringResource(R.string.auto_quick)) }
                            SegmentedButton(
                                selected = full, onClick = { full = true },
                                shape = SegmentedButtonDefaults.itemShape(1, 2),
                            ) { Text(stringResource(R.string.auto_full)) }
                        }
                        Hint(stringResource(if (full) R.string.auto_full_hint else R.string.auto_quick_hint))
                        if (engine != Engine.BYEDPI) {
                            SwitchRow(
                                title = stringResource(R.string.auto_include_store),
                                checked = includeStore,
                                onChange = { includeStore = it },
                            )
                        }
                        Text(stringResource(R.string.auto_requests, requests))
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            listOf(1, 2, 3).forEachIndexed { i, n ->
                                SegmentedButton(
                                    selected = requests == n, onClick = { requests = n },
                                    shape = SegmentedButtonDefaults.itemShape(i, 3),
                                ) { Text(n.toString()) }
                            }
                        }
                    }
                }
                item {
                    Button(
                        onClick = { start() },
                        enabled = status.usable && !busy && !loadingSuite,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.auto_start)) }
                    Hint(stringResource(R.string.auto_disclaimer))
                }
            } else {
                item { ProgressCard(state, onStop = { vm.autoSelector.cancel() }, onNew = { vm.autoSelector.reset() }) }
                if (state.baseline.isNotEmpty()) {
                    item {
                        SectionCard(
                            title = stringResource(
                                R.string.auto_baseline,
                                state.baseline.count { it.ok > 0 },
                                state.baseline.size,
                            ),
                        ) {
                            val blocked = state.baseline.filter { it.ok == 0 }
                            if (blocked.isNotEmpty()) Text(stringResource(R.string.auto_blocked_list))
                            blocked.forEach { Hint("✗ " + it.url) }
                            if (state.baseline.all { it.ok > 0 }) Hint(stringResource(R.string.auto_nothing_blocked))
                        }
                    }
                }
                val sorted = state.sorted
                itemsIndexed(sorted, key = { i, r -> "$i-${r.candidate.name.hashCode()}" }) { i, r ->
                    ResultCard(
                        rank = i + 1,
                        result = r,
                        canApply = !state.running && r.percent > 0,
                        onApply = {
                            val e = state.engine ?: return@ResultCard
                            vm.op { vm.autoSelector.applyResult(r, e) }
                            vm.say(R.string.auto_applied)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ProgressCard(state: AutoState, onStop: () -> Unit, onNew: () -> Unit) {
    SectionCard {
        val title = when (state.phase) {
            Phase.BASELINE -> stringResource(R.string.auto_phase_baseline)
            Phase.TESTING -> stringResource(R.string.auto_phase_testing, state.current, state.total)
            Phase.DONE -> stringResource(R.string.auto_phase_done)
            Phase.CANCELLED -> stringResource(R.string.auto_phase_cancelled)
            Phase.ERROR -> stringResource(R.string.auto_phase_error, state.error.orEmpty())
            Phase.IDLE -> ""
        }
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        if (state.running) {
            LinearProgressIndicator(
                progress = { if (state.total == 0) 0f else state.current.toFloat() / state.total },
                modifier = Modifier.fillMaxWidth(),
            )
            Mono(state.currentName, maxLines = 2)
            Button(
                onClick = onStop,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) { Text(stringResource(R.string.auto_stop)) }
            Hint(stringResource(R.string.auto_running_hint))
        } else {
            val best = state.best
            Text(
                if (best != null) stringResource(R.string.auto_best, best.percent) else stringResource(R.string.auto_none_found),
            )
            if (best == null) Hint(stringResource(R.string.auto_none_hint))
            TextButton(onClick = onNew) { Text(stringResource(R.string.auto_new)) }
        }
    }
}

@Composable
private fun ResultCard(rank: Int, result: StrategyResult, canApply: Boolean, onApply: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    val color = when {
        result.startFailed -> MaterialTheme.colorScheme.errorContainer
        result.percent >= 90 -> Color(0xFF2E7D32).copy(alpha = 0.18f)
        result.percent >= 50 -> Color(0xFFF9A825).copy(alpha = 0.18f)
        else -> MaterialTheme.colorScheme.surfaceContainer
    }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = color)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("#$rank", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(end = 8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (result.startFailed) stringResource(R.string.auto_start_failed)
                        else stringResource(R.string.auto_result, result.percent, result.ok, result.total, result.avgMs),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Mono(result.candidate.name, maxLines = if (open) Int.MAX_VALUE else 2)
                }
                IconButton(onClick = { open = !open }) {
                    Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null)
                }
            }
            result.startError?.let { err ->
                Mono(err, maxLines = if (open) Int.MAX_VALUE else 3)
            }
            if (open) {
                result.sites.forEach { s -> SiteLine(s) }
            }
            if (canApply) {
                Button(onClick = onApply) {
                    Icon(Icons.Filled.Check, null)
                    Text(stringResource(R.string.auto_apply), Modifier.padding(start = 6.dp))
                }
            }
        }
    }
}

@Composable
private fun SiteLine(s: SiteResult) {
    val mark = if (s.ok == s.total) "✓" else if (s.ok == 0) "✗" else "~"
    Hint("$mark ${s.ok}/${s.total}  ${s.url}" + (s.error?.let { "  — $it" } ?: ""))
}
