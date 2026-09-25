package app.manka.ui.screens

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
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
import app.manka.core.Profiles
import app.manka.core.Services
import app.manka.autoselect.AutoSelector
import androidx.navigation.NavHostController
import app.manka.ui.Hint
import app.manka.ui.MainViewModel
import app.manka.ui.Mono
import app.manka.ui.ScreenScaffold
import app.manka.ui.SectionCard
import app.manka.ui.SwitchRow
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun AutoSelectScreen(vm: MainViewModel, nav: NavHostController) {
    val state by vm.autoSelector.state.collectAsState()
    val status by vm.status.collectAsState()
    val busy by vm.busy.collectAsState()
    val prefs = vm.prefs
    val scope = rememberCoroutineScope()
    val profile = status.ownProfile
    val historyRuns by vm.autoSelector.history.runs.collectAsState()

    // null = all engines
    var engine by remember { mutableStateOf<Engine?>(prefs.engine(profile)) }
    var groups by remember { mutableStateOf(prefs.autoGroups) }
    var custom by remember { mutableStateOf(prefs.customSites) }
    var full by remember { mutableStateOf(prefs.autoFullMode) }
    var includeStore by remember { mutableStateOf(prefs.autoIncludeStore) }
    var byeByeDpi by remember { mutableStateOf(prefs.autoByeByeDpi) }
    var requests by remember { mutableStateOf(prefs.autoRequests) }
    var loadingSuite by remember { mutableStateOf(false) }
    // null = main strategy, a service id, or ALL_SERVICES
    var target by remember { mutableStateOf<String?>(null) }
    val queue by vm.autoSelector.queue.collectAsState()
    val perService = target != null
    fun pickTarget(t: String?) {
        target = t
        if (t != null && (engine == null || engine == Engine.BYEDPI)) engine = Engine.ZAPRET2
    }

    fun start() {
        prefs.autoGroups = groups
        prefs.customSites = custom
        prefs.autoFullMode = full
        prefs.autoIncludeStore = includeStore
        prefs.autoByeByeDpi = byeByeDpi
        prefs.autoRequests = requests
        scope.launch {
            if (target != null) {
                val base = AutoRequest(
                    engines = listOf(engine ?: Engine.ZAPRET2),
                    profile = vm.status.value.ownProfile,
                    profileLabel = vm.status.value.ssid,
                    targets = emptyList(),
                    full = full,
                    includeStore = includeStore,
                    requests = requests,
                    timeoutSec = prefs.autoTimeoutSec,
                    stopAfterPerfect = if (full) 0 else 3,
                    byeByeDpi = false,
                )
                if (target == ALL_SERVICES) {
                    vm.autoSelector.startServices(base, Services.all)
                } else {
                    val s = Services.byId(target) ?: return@launch
                    val urls = Targets.groups.filter { it.id in s.targetGroups }.flatMap { it.urls }
                    vm.autoSelector.start(base.copy(service = s.id, targets = urls))
                }
                return@launch
            }
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
                    engines = engine?.let { listOf(it) } ?: Engine.entries.toList(),
                    profile = vm.status.value.ownProfile,
                    profileLabel = vm.status.value.ssid,
                    targets = urls.distinct(),
                    full = full,
                    includeStore = includeStore,
                    requests = requests,
                    timeoutSec = prefs.autoTimeoutSec,
                    stopAfterPerfect = if (full) 0 else 3,
                    byeByeDpi = byeByeDpi,
                ),
            )
        }
    }

    ScreenScaffold(
        title = stringResource(R.string.auto_title),
        busy = busy,
        actions = {
            IconButton(onClick = { nav.navigate("history") }) {
                BadgedBox(badge = { if (historyRuns.isNotEmpty()) Badge { Text(historyRuns.size.toString()) } }) {
                    Icon(Icons.Filled.History, stringResource(R.string.history_title))
                }
            }
        },
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = pad, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (queue.services.isNotEmpty()) {
                item { QueueCard(queue, running = queue.active, onNew = { vm.autoSelector.resetQueue(); vm.autoSelector.reset() }) }
            }
            if (!state.running && state.phase != Phase.DONE && state.phase != Phase.CANCELLED) {
                item {
                    SectionCard(title = stringResource(R.string.auto_target)) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = target == null, onClick = { pickTarget(null) }, label = { Text(stringResource(R.string.auto_target_main)) })
                            Services.all.forEach { s ->
                                FilterChip(selected = target == s.id, onClick = { pickTarget(s.id) }, label = { Text(s.title) })
                            }
                            FilterChip(
                                selected = target == ALL_SERVICES,
                                onClick = { pickTarget(ALL_SERVICES) },
                                label = { Text(stringResource(R.string.auto_target_all_services)) },
                            )
                        }
                        Hint(stringResource(if (target == null) R.string.auto_target_main_hint else R.string.auto_target_service_hint))
                    }
                }
                item {
                    SectionCard(title = stringResource(R.string.engine)) {
                        val options = if (perService) listOf<Engine?>(Engine.ZAPRET2, Engine.ZAPRET) else Engine.entries + listOf<Engine?>(null)
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            options.forEachIndexed { i, e ->
                                SegmentedButton(
                                    selected = engine == e,
                                    onClick = { engine = e },
                                    shape = SegmentedButtonDefaults.itemShape(i, options.size),
                                    icon = {},
                                ) { Text(e?.title ?: stringResource(R.string.auto_all_engines)) }
                            }
                        }
                        Hint(stringResource(if (engine == null) R.string.auto_all_hint else R.string.auto_engine_hint))
                        Text(stringResource(R.string.auto_for_net, profileTitle(vm, profile)), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (!perService) item {
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
                        if (engine == null || engine == Engine.BYEDPI) {
                            SwitchRow(
                                title = stringResource(R.string.auto_byebyedpi),
                                subtitle = stringResource(R.string.auto_byebyedpi_hint),
                                checked = byeByeDpi,
                                onChange = { byeByeDpi = it },
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
                if (state.allEngines && !state.running && state.bestByEngine.isNotEmpty()) {
                    item { RecommendationCard(vm, state) }
                }
                if (state.baseline.isNotEmpty()) {
                    item { BaselineCard(state) }
                }
                val sorted = state.sorted
                itemsIndexed(sorted, key = { i, r -> "$i-${r.candidate.name.hashCode()}" }) { i, r ->
                    ResultCard(
                        rank = i + 1,
                        result = r,
                        canApply = !state.running && r.percent > 0,
                        showEngine = state.allEngines,
                        onApply = {
                            val p = state.profile ?: profile
                            vm.op { vm.autoSelector.applyResult(r, p, state.profileLabel) }
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
            Mono((state.currentEngine?.let { it.title + " · " } ?: "") + state.currentName, maxLines = 2)
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
private fun ResultCard(rank: Int, result: StrategyResult, canApply: Boolean, showEngine: Boolean, onApply: () -> Unit) {
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
                    if (showEngine) Text(result.engine.title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
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

/** "Without bypass X of Y sites open"; the list of blocked sites is folded until tapped. */
@Composable
private fun BaselineCard(state: AutoState) {
    var open by remember { mutableStateOf(false) }
    val blocked = state.baseline.filter { it.ok == 0 }
    SectionCard {
        Row(
            Modifier.fillMaxWidth().clickable(enabled = blocked.isNotEmpty()) { open = !open },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.auto_baseline, state.baseline.count { it.ok > 0 }, state.baseline.size),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            if (blocked.isNotEmpty()) {
                Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null)
            }
        }
        if (blocked.isEmpty()) {
            Hint(stringResource(R.string.auto_nothing_blocked))
        } else if (open) {
            Text(stringResource(R.string.auto_blocked_list))
            blocked.forEach { Hint("✗ " + it.url) }
        } else {
            Hint(stringResource(R.string.auto_blocked_count, blocked.size))
        }
    }
}

/** "All engines": which engine works best on this network, with one-tap apply. */
@Composable
private fun RecommendationCard(vm: MainViewModel, state: AutoState) {
    val best = state.bestByEngine
    val winner = best.first()
    SectionCard(
        title = stringResource(R.string.auto_recommend_title, profileTitle(vm, state.profile ?: Profiles.WIFI)),
        containerColor = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Text(
            stringResource(R.string.auto_recommend, winner.engine.title, winner.percent),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        best.forEach { r ->
            Hint(stringResource(R.string.auto_engine_best, r.engine.title, r.percent, r.avgMs))
        }
        state.engines.filter { e -> best.none { it.engine == e } }.forEach { e ->
            Hint(stringResource(R.string.auto_engine_none, e.title))
        }
        Button(onClick = {
            vm.op { vm.autoSelector.applyResult(winner, state.profile ?: Profiles.WIFI, state.profileLabel) }
            vm.say(R.string.auto_applied)
        }) {
            Icon(Icons.Filled.Check, null)
            Text(stringResource(R.string.auto_recommend_apply, winner.engine.title), Modifier.padding(start = 6.dp))
        }
    }
}

private const val ALL_SERVICES = "*"

/** "Every service in a row": which service got which result, applied automatically at the end. */
@Composable
private fun QueueCard(queue: AutoSelector.QueueState, running: Boolean, onNew: () -> Unit) {
    SectionCard(title = stringResource(R.string.auto_queue_title), containerColor = MaterialTheme.colorScheme.primaryContainer) {
        queue.services.forEach { id ->
            val title = Services.byId(id)?.title ?: id
            val line = when {
                id == queue.current -> stringResource(R.string.auto_queue_testing, title)
                id !in queue.done -> stringResource(R.string.auto_queue_waiting, title)
                queue.done[id] == null -> stringResource(R.string.auto_queue_none, title)
                else -> stringResource(R.string.auto_queue_result, title, queue.done[id]!!.percent)
            }
            Text(line)
        }
        if (!running) {
            Hint(stringResource(R.string.auto_queue_applied))
            TextButton(onClick = onNew) { Text(stringResource(R.string.auto_new)) }
        }
    }
}
