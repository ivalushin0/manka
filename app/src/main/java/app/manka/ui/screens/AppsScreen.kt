package app.manka.ui.screens

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import app.manka.R
import app.manka.ui.Hint
import app.manka.ui.MainViewModel
import app.manka.ui.ScreenScaffold
import app.manka.ui.SwitchRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class AppEntry(val pkg: String, val label: String, val system: Boolean, val info: ApplicationInfo)

@Composable
fun AppsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val busy by vm.busy.collectAsState()
    var apps by remember { mutableStateOf<List<AppEntry>?>(null) }
    var query by remember { mutableStateOf("") }
    var showSystem by remember { mutableStateOf(false) }
    var excluded by remember { mutableStateOf(vm.prefs.excludedPackages) }
    var only by remember { mutableStateOf(vm.prefs.appsOnly) }
    val initial = remember { vm.prefs.excludedPackages }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            @Suppress("DEPRECATION")
            pm.getInstalledApplications(0)
                .filter { it.packageName != context.packageName }
                .filter { pm.checkPermission(android.Manifest.permission.INTERNET, it.packageName) == PackageManager.PERMISSION_GRANTED }
                .map {
                    AppEntry(
                        pkg = it.packageName,
                        label = it.loadLabel(pm).toString(),
                        system = it.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                        info = it,
                    )
                }
                .sortedWith(compareByDescending<AppEntry> { it.pkg in initial }.thenBy { it.label.lowercase() })
        }
    }

    fun save() {
        vm.prefs.excludedPackages = excluded
        vm.prefs.appsOnly = only
        if (vm.prefs.enabled) vm.apply()
        onBack()
    }

    BackHandler { save() }

    ScreenScaffold(
        title = stringResource(R.string.apps_title),
        onBack = { save() },
        busy = busy,
        actions = { IconButton(onClick = { save() }) { Icon(Icons.Filled.Check, stringResource(R.string.save)) } },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = !only, onClick = { only = false },
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                ) { Text(stringResource(R.string.apps_mode_exclude)) }
                SegmentedButton(
                    selected = only, onClick = { only = true },
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                ) { Text(stringResource(R.string.apps_mode_only)) }
            }
            Hint(stringResource(if (only) R.string.apps_hint_only else R.string.apps_hint))
            if (only && excluded.isEmpty()) {
                Text(stringResource(R.string.apps_only_empty), color = MaterialTheme.colorScheme.error)
            }
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                placeholder = { Text(stringResource(R.string.search)) },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            SwitchRow(title = stringResource(R.string.apps_show_system), checked = showSystem, onChange = { showSystem = it })
            val list = apps
            if (list == null) {
                CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
            } else {
                val q = query.trim().lowercase()
                val shown = list.filter { (showSystem || !it.system || it.pkg in excluded) }
                    .filter { q.isEmpty() || it.label.lowercase().contains(q) || it.pkg.contains(q) }
                LazyColumn(Modifier.fillMaxSize()) {
                    items(shown, key = { it.pkg }) { app ->
                        val checked = app.pkg in excluded
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable { excluded = if (checked) excluded - app.pkg else excluded + app.pkg }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AppIcon(app.info)
                            Spacer(Modifier.size(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(app.label, style = MaterialTheme.typography.bodyLarge)
                                Hint(app.pkg)
                            }
                            Checkbox(checked = checked, onCheckedChange = {
                                excluded = if (it) excluded + app.pkg else excluded - app.pkg
                            })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppIcon(info: ApplicationInfo) {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(null, info.packageName) {
        value = withContext(Dispatchers.IO) {
            runCatching { info.loadIcon(context.packageManager).toBitmap(96, 96).asImageBitmap() }.getOrNull()
        }
    }
    val b = bitmap
    if (b != null) Image(b, null, Modifier.size(40.dp)) else Spacer(Modifier.size(40.dp))
}
