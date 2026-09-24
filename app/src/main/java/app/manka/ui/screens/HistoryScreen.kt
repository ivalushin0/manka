package app.manka.ui.screens

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import app.manka.autoselect.HistoryRun
import app.manka.autoselect.SavedResult
import app.manka.ui.Hint
import app.manka.ui.MainViewModel
import app.manka.ui.Mono
import app.manka.ui.ScreenScaffold
import app.manka.ui.SectionCard

@Composable
private fun runTitle(vm: MainViewModel, run: HistoryRun): String {
    val engine = run.engine?.title ?: stringResource(R.string.auto_all_engines)
    val network = run.profileLabel ?: profileTitle(vm, run.profile)
    return "$engine · $network"
}

/** Past auto selections: tap one to see its strategies and apply any of them. */
@Composable
fun HistoryScreen(vm: MainViewModel, nav: NavHostController, onBack: () -> Unit) {
    val runs by vm.autoSelector.history.runs.collectAsState()
    ScreenScaffold(title = stringResource(R.string.history_title), onBack = onBack) { pad ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = pad, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (runs.isEmpty()) item { Hint(stringResource(R.string.history_empty)) }
            items(runs, key = { it.id }) { run ->
                Card(
                    Modifier.fillMaxWidth().clickable { nav.navigate("history/${run.id}") },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(runTitle(vm, run), style = MaterialTheme.typography.titleMedium)
                            Hint(DateUtils.getRelativeTimeSpanString(run.time).toString())
                            val best = run.results.firstOrNull()
                            Text(
                                stringResource(
                                    R.string.history_summary,
                                    best?.percent ?: 0,
                                    run.results.size,
                                    run.tested,
                                ) + (best?.engine?.takeIf { run.engine == null }?.let { " · " + it.title } ?: ""),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        IconButton(onClick = { vm.autoSelector.history.delete(run.id) }) {
                            Icon(Icons.Filled.Delete, stringResource(R.string.delete))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun HistoryRunScreen(vm: MainViewModel, id: String, onBack: () -> Unit) {
    val runs by vm.autoSelector.history.runs.collectAsState()
    val busy by vm.busy.collectAsState()
    val run = runs.firstOrNull { it.id == id }
    ScreenScaffold(
        title = run?.let { runTitle(vm, it) } ?: stringResource(R.string.history_title),
        onBack = onBack,
        busy = busy,
    ) { pad ->
        if (run == null) return@ScreenScaffold
        val network = run.profileLabel ?: profileTitle(vm, run.profile)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = pad, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                SectionCard {
                    Text(DateUtils.getRelativeTimeSpanString(run.time).toString(), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.auto_baseline, run.baselineOk, run.baselineTotal))
                    Hint(stringResource(R.string.history_apply_hint, network))
                }
            }
            items(run.results.withIndex().toList(), key = { it.index }) { (i, r) ->
                SavedResultCard(i + 1, r, showEngine = run.engine == null, enabled = !busy) {
                    vm.op { vm.autoSelector.applyStrategy(r, run.profile, run.profileLabel, run.targets) }
                    vm.say(R.string.auto_applied)
                }
            }
        }
    }
}

@Composable
private fun SavedResultCard(rank: Int, r: SavedResult, showEngine: Boolean, enabled: Boolean, onApply: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("#$rank", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(end = 8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.auto_result, r.percent, r.ok, r.total, r.avgMs),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    if (showEngine && r.engine != null) {
                        Text(r.engine.title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    }
                    Mono(r.name, maxLines = if (open) Int.MAX_VALUE else 2)
                }
                IconButton(onClick = { open = !open }) {
                    Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null)
                }
            }
            if (open) {
                r.sites.forEach { s ->
                    val mark = if (s.ok == s.total) "✓" else if (s.ok == 0) "✗" else "~"
                    Hint("$mark ${s.ok}/${s.total}  ${s.url}")
                }
            }
            Button(onClick = onApply, enabled = enabled) {
                Icon(Icons.Filled.Check, null)
                Text(stringResource(R.string.auto_apply), Modifier.padding(start = 6.dp))
            }
        }
    }
}
