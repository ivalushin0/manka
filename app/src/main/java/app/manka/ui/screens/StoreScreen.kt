package app.manka.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.manka.R
import app.manka.store.StoreCategory
import app.manka.store.StoreItem
import app.manka.ui.Hint
import app.manka.ui.MainViewModel
import app.manka.ui.ScreenScaffold
import app.manka.ui.SectionCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StoreScreen(vm: MainViewModel) {
    val busy by vm.busy.collectAsState()
    val status by vm.status.collectAsState()
    val version by vm.prefs.version.collectAsState()
    var items by remember { mutableStateOf<List<StoreItem>>(emptyList()) }
    var reload by remember { mutableIntStateOf(0) }
    var tab by remember { mutableIntStateOf(0) }
    val categories = listOf(StoreCategory.CONFIGS, StoreCategory.SUBSCRIPTIONS, StoreCategory.COMPONENTS)

    LaunchedEffect(reload) { items = runCatching { vm.store.items() }.getOrDefault(emptyList()) }

    ScreenScaffold(
        title = stringResource(R.string.store_title),
        busy = busy,
        actions = {
            IconButton(onClick = {
                vm.op(R.string.store_refresh_failed) {
                    vm.store.refreshIndex()
                    vm.say(R.string.store_refreshed)
                    reload++
                }
            }) { Icon(Icons.Filled.CloudDownload, stringResource(R.string.store_refresh)) }
        },
    ) { pad ->
        Column(Modifier.fillMaxSize()) {
            PrimaryTabRow(selectedTabIndex = tab) {
                categories.forEachIndexed { i, c ->
                    Tab(
                        selected = tab == i,
                        onClick = { tab = i },
                        text = {
                            Text(
                                stringResource(
                                    when (c) {
                                        StoreCategory.CONFIGS -> R.string.store_configs
                                        StoreCategory.SUBSCRIPTIONS -> R.string.store_lists
                                        else -> R.string.store_components
                                    },
                                ),
                            )
                        },
                    )
                }
            }
            val shown = items.filter { it.category == categories[tab] }
            LazyColumn(contentPadding = pad, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { Hint(stringResource(R.string.store_hint)) }
                items(shown, key = { it.storeId }) { item ->
                    StoreItemCard(vm, item, version, enabled = !busy && status.usable)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StoreItemCard(vm: MainViewModel, item: StoreItem, @Suppress("UNUSED_PARAMETER") version: Int, enabled: Boolean) {
    val context = LocalContext.current
    var more by remember { mutableStateOf(false) }
    val installed = vm.store.installedVersion(item)
    SectionCard(title = item.shortName) {
        Hint(stringResource(R.string.store_by, item.developer))
        if (item.smallDescription.isNotBlank()) Text(item.smallDescription, style = MaterialTheme.typography.bodyMedium)
        if (more && item.description.isNotBlank()) Text(item.description, style = MaterialTheme.typography.bodySmall)
        item.warning?.let { if (more) Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item.links.forEach { (name, url) ->
                AssistChip(
                    onClick = {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                    },
                    label = { Text(name) },
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            when (item.support) {
                StoreItem.Support.INSTALL -> {
                    if (installed == null) {
                        Button(enabled = enabled, onClick = {
                            vm.op(R.string.store_install_failed) {
                                vm.store.install(item)
                                vm.say(R.string.store_installed, item.shortName)
                            }
                        }) { Text(stringResource(R.string.install)) }
                    } else {
                        OutlinedButton(enabled = enabled, onClick = {
                            vm.op(R.string.store_install_failed) {
                                vm.store.install(item)
                                vm.say(R.string.store_updated, item.shortName)
                            }
                        }) { Text(stringResource(R.string.update)) }
                        TextButton(enabled = enabled, onClick = { vm.op { vm.store.remove(item) } }) {
                            Text(stringResource(R.string.delete))
                        }
                    }
                }
                StoreItem.Support.BUNDLED -> Hint(stringResource(R.string.store_bundled))
                StoreItem.Support.UNSUPPORTED -> Hint(stringResource(R.string.store_unsupported))
            }
            if (item.description.isNotBlank() || item.warning != null) {
                TextButton(onClick = { more = !more }) {
                    Text(stringResource(if (more) R.string.less else R.string.more))
                }
            }
        }
        if (installed != null) Hint(stringResource(R.string.store_installed_version, installed))
        if (item.type == "lsubscription" && installed != null) Hint(stringResource(R.string.store_list_path, item.storeId))
    }
}
