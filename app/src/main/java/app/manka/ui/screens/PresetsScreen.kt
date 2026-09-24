package app.manka.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.manka.R
import app.manka.core.Args
import app.manka.core.Engine
import app.manka.core.Preset
import app.manka.core.PresetRenderer
import app.manka.core.PresetSource
import app.manka.ui.Hint
import app.manka.ui.MainViewModel
import app.manka.ui.Mono
import app.manka.ui.ScreenScaffold
import app.manka.ui.SwitchRow

@Composable
fun PresetsScreen(vm: MainViewModel, engine: Engine, profile: String, onBack: () -> Unit) {
    val all by vm.presets.presets.collectAsState()
    val busy by vm.busy.collectAsState()
    val version by vm.prefs.version.collectAsState()
    val list = remember(all, engine) { all.filter { it.engine == engine } }
    val activeId = remember(all, version) { vm.presets.active(engine, profile).id }
    var editing by remember { mutableStateOf<Preset?>(null) }
    var expanded by remember { mutableStateOf<String?>(null) }

    ScreenScaffold(
        title = stringResource(R.string.presets_title, engine.title) + " · " + profileTitle(vm, profile),
        onBack = onBack,
        busy = busy,
        floating = {
            FloatingActionButton(onClick = {
                editing = Preset(
                    id = vm.presets.newId("user"), engine = engine, name = "", source = PresetSource.USER, template = "",
                )
            }) { Icon(Icons.Filled.Add, stringResource(R.string.preset_add)) }
        },
    ) { pad ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = pad,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PresetSource.entries.forEach { source ->
                val group = list.filter { it.source == source }
                if (group.isEmpty()) return@forEach
                item(key = "h-$source") {
                    Text(
                        stringResource(
                            when (source) {
                                PresetSource.BUILTIN -> R.string.source_builtin
                                PresetSource.STORE -> R.string.source_store
                                PresetSource.USER -> R.string.source_user
                                PresetSource.AUTO -> R.string.source_auto
                            },
                        ),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                items(group, key = { it.id }) { p ->
                    PresetCard(
                        vm = vm,
                        preset = p,
                        active = p.id == activeId,
                        expanded = expanded == p.id,
                        onExpand = { expanded = if (expanded == p.id) null else p.id },
                        onSelect = { vm.selectPreset(engine, profile, p.id) },
                        onEdit = { editing = it },
                    )
                }
            }
            item { Hint(stringResource(R.string.presets_hint)) }
        }
    }

    editing?.let { p ->
        PresetEditor(
            preset = p,
            onDismiss = { editing = null },
            onSave = { saved ->
                vm.presets.save(saved)
                editing = null
                if (saved.id == vm.prefs.activePreset(engine, profile) && vm.prefs.enabled) vm.apply()
            },
        )
    }
}

@Composable
private fun PresetCard(
    vm: MainViewModel,
    preset: Preset,
    active: Boolean,
    expanded: Boolean,
    onExpand: () -> Unit,
    onSelect: () -> Unit,
    onEdit: (Preset) -> Unit,
) {
    val editable = preset.source == PresetSource.USER || preset.source == PresetSource.AUTO
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (active) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth().clickable(onClick = onSelect), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = active, onClick = onSelect)
                Column(Modifier.weight(1f)) {
                    Text(preset.name, style = MaterialTheme.typography.bodyLarge)
                    val sub = listOfNotNull(preset.description, preset.score?.let { "$it%" }).joinToString(" · ")
                    if (sub.isNotEmpty()) Hint(sub)
                }
                IconButton(onClick = onExpand) {
                    Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null)
                }
            }
            if (expanded) {
                Column(Modifier.padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val rendered = remember(preset) { PresetRenderer.render(preset, vm.prefs) }
                    Mono(Args.join(rendered.args), Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()))
                    if (rendered.tcpPorts != null || rendered.udpPorts != null) {
                        Hint(stringResource(R.string.preset_ports, rendered.tcpPorts ?: "-", rendered.udpPorts ?: "-"))
                    }
                    preset.flags.forEach { f ->
                        var checked by remember { mutableStateOf(vm.prefs.presetFlag(preset.id, f.key, f.default)) }
                        SwitchRow(title = f.title, checked = checked, onChange = {
                            checked = it
                            vm.prefs.setPresetFlag(preset.id, f.key, it)
                            if (active && vm.prefs.enabled) vm.apply()
                        })
                    }
                    preset.options.forEach { o -> OptionPicker(vm, preset, o, active) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (editable) {
                            OutlinedButton(onClick = { onEdit(preset) }) {
                                Icon(Icons.Filled.Edit, null)
                                Text(stringResource(R.string.edit), Modifier.padding(start = 4.dp))
                            }
                            IconButton(onClick = { vm.presets.delete(preset.id) }, enabled = !active) {
                                Icon(Icons.Filled.Delete, stringResource(R.string.delete))
                            }
                        } else {
                            OutlinedButton(onClick = {
                                onEdit(
                                    Preset(
                                        id = vm.presets.newId("user"),
                                        engine = preset.engine,
                                        name = preset.name + " *",
                                        source = PresetSource.USER,
                                        template = Args.join(rendered.args),
                                        tcpPorts = rendered.tcpPorts,
                                        udpPorts = rendered.udpPorts,
                                        kitId = preset.kitId,
                                        description = preset.description,
                                    ),
                                )
                            }) {
                                Icon(Icons.Filled.ContentCopy, null)
                                Text(stringResource(R.string.preset_copy), Modifier.padding(start = 4.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OptionPicker(vm: MainViewModel, preset: Preset, option: app.manka.core.PresetOption, active: Boolean) {
    var index by remember { mutableStateOf(vm.prefs.presetOption(preset.id, option.variable)) }
    var open by remember { mutableStateOf(false) }
    val label = if (index < 0) stringResource(R.string.option_default) else stringResource(R.string.option_n, index + 1)
    Column {
        Text(option.title, style = MaterialTheme.typography.bodyMedium)
        Box {
            OutlinedButton(onClick = { open = true }) { Text(label) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.option_default)) },
                    onClick = {
                        index = -1; open = false
                        vm.prefs.setPresetOption(preset.id, option.variable, -1)
                        if (active && vm.prefs.enabled) vm.apply()
                    },
                )
                option.values.forEachIndexed { i, value ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(stringResource(R.string.option_n, i + 1))
                                Mono(value, maxLines = 2)
                            }
                        },
                        onClick = {
                            index = i; open = false
                            vm.prefs.setPresetOption(preset.id, option.variable, i)
                            if (active && vm.prefs.enabled) vm.apply()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun PresetEditor(preset: Preset, onDismiss: () -> Unit, onSave: (Preset) -> Unit) {
    var name by remember { mutableStateOf(preset.name) }
    var args by remember { mutableStateOf(preset.template) }
    var tcp by remember { mutableStateOf(preset.tcpPorts.orEmpty()) }
    var udp by remember { mutableStateOf(preset.udpPorts.orEmpty()) }
    val nfq = preset.engine != Engine.BYEDPI
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (preset.name.isEmpty()) R.string.preset_add else R.string.edit)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.preset_name)) }, singleLine = true)
                OutlinedTextField(
                    args, { args = it },
                    label = { Text(stringResource(R.string.preset_args)) },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    minLines = 4,
                )
                if (nfq) {
                    OutlinedTextField(tcp, { tcp = it }, label = { Text(stringResource(R.string.preset_tcp)) }, singleLine = true)
                    OutlinedTextField(udp, { udp = it }, label = { Text(stringResource(R.string.preset_udp)) }, singleLine = true)
                }
                Hint(stringResource(R.string.preset_editor_hint))
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && args.isNotBlank(),
                onClick = {
                    onSave(
                        preset.copy(
                            name = name.trim(),
                            template = args.trim(),
                            tcpPorts = Args.ports(tcp).ifEmpty { null },
                            udpPorts = Args.ports(udp).ifEmpty { null },
                            source = if (preset.source == PresetSource.AUTO) PresetSource.AUTO else PresetSource.USER,
                        ),
                    )
                },
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
