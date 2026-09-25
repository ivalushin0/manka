package app.manka.ui.screens

import android.app.LocaleManager
import android.os.Build
import android.os.LocaleList
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import app.manka.BuildConfig
import app.manka.R
import app.manka.core.Applier
import app.manka.core.Args
import app.manka.core.Module
import app.manka.core.Paths
import app.manka.ui.Hint
import app.manka.ui.MainViewModel
import app.manka.ui.ScreenScaffold
import app.manka.ui.SectionCard
import app.manka.ui.SwitchRow
import app.manka.work.HealthWorker
import app.manka.core.StatusNotifier
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun SettingsScreen(vm: MainViewModel, nav: NavHostController) {
    val busy by vm.busy.collectAsState()
    val status by vm.status.collectAsState()
    @Suppress("UNUSED_VARIABLE") val v by vm.prefs.version.collectAsState()
    val prefs = vm.prefs
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var tcp by remember { mutableStateOf(prefs.tcpPorts) }
    var udp by remember { mutableStateOf(prefs.udpPorts) }
    var byedpiPorts by remember { mutableStateOf(prefs.byedpiPorts) }
    var sni by remember { mutableStateOf(prefs.fakeSni) }
    var dns by remember { mutableStateOf(prefs.dnsServer.takeIf { Applier.IPV4.matches(it) }.orEmpty()) }
    var excludeText by remember { mutableStateOf<String?>(null) }

    fun applyIfOn() {
        if (prefs.enabled || prefs.tgws) vm.apply()
    }

    ScreenScaffold(title = stringResource(R.string.settings), busy = busy) { pad ->
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(pad),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionCard(title = stringResource(R.string.settings_bypass)) {
                SwitchRow(
                    title = stringResource(R.string.block_quic),
                    subtitle = stringResource(R.string.block_quic_hint),
                    checked = prefs.blockQuic,
                    onChange = { prefs.blockQuic = it; applyIfOn() },
                )
                SwitchRow(
                    title = stringResource(R.string.ipv6),
                    subtitle = stringResource(R.string.ipv6_hint),
                    checked = prefs.ipv6,
                    onChange = { prefs.ipv6 = it; applyIfOn() },
                )
                SwitchRow(
                    title = stringResource(R.string.discord_voice),
                    subtitle = stringResource(R.string.discord_voice_hint),
                    checked = prefs.discordVoice,
                    onChange = { prefs.discordVoice = it; applyIfOn() },
                )
                SwitchRow(
                    title = stringResource(R.string.hotspot),
                    subtitle = stringResource(R.string.hotspot_hint),
                    checked = prefs.hotspot,
                    onChange = { prefs.hotspot = it; applyIfOn() },
                )
                Text(stringResource(R.string.dns_title), style = MaterialTheme.typography.bodyLarge)
                Hint(stringResource(R.string.dns_hint))
                val dnsOptions = listOf("" to R.string.dns_system, "doh:google" to R.string.dns_google, "doh:cloudflare" to R.string.dns_cloudflare, "doh:quad9" to R.string.dns_quad9)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    dnsOptions.forEachIndexed { i, (ip, label) ->
                        SegmentedButton(
                            selected = prefs.dnsServer == ip,
                            onClick = { prefs.dnsServer = ip; dns = ""; applyIfOn() },
                            shape = SegmentedButtonDefaults.itemShape(i, dnsOptions.size),
                            icon = {},
                        ) { Text(stringResource(label), maxLines = 1) }
                    }
                }
                OutlinedTextField(
                    value = dns, onValueChange = { dns = it.trim() }, singleLine = true,
                    label = { Text(stringResource(R.string.dns_custom)) }, modifier = Modifier.fillMaxWidth(),
                    isError = dns.isNotEmpty() && !Applier.IPV4.matches(dns),
                    trailingIcon = {
                        TextButton(
                            enabled = dns != prefs.dnsServer && Applier.IPV4.matches(dns),
                            onClick = { prefs.dnsServer = dns; applyIfOn() },
                        ) { Text(stringResource(R.string.save)) }
                    },
                )
                OutlinedButton(onClick = { nav.navigate("apps") }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.apps_button, prefs.excludedPackages.size))
                }
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            excludeText = Module.readFile(Paths.EXCLUDE_LIST) ?: Applier.DEFAULT_EXCLUDE
                        }
                    },
                    enabled = status.usable,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.exclude_sites)) }
                OutlinedTextField(
                    value = tcp, onValueChange = { tcp = it }, singleLine = true,
                    label = { Text(stringResource(R.string.tcp_ports)) }, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = udp, onValueChange = { udp = it }, singleLine = true,
                    label = { Text(stringResource(R.string.udp_ports)) }, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = byedpiPorts, onValueChange = { byedpiPorts = it }, singleLine = true,
                    label = { Text(stringResource(R.string.byedpi_ports)) }, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = sni, onValueChange = { sni = it.trim() }, singleLine = true,
                    label = { Text(stringResource(R.string.fake_sni)) }, modifier = Modifier.fillMaxWidth(),
                )
                Hint(stringResource(R.string.ports_hint))
                Button(onClick = {
                    prefs.tcpPorts = Args.ports(tcp).ifEmpty { "80,443" }
                    prefs.udpPorts = Args.ports(udp)
                    prefs.byedpiPorts = Args.ports(byedpiPorts).ifEmpty { "80,443" }
                    prefs.fakeSni = sni.ifBlank { "www.google.com" }
                    tcp = prefs.tcpPorts; udp = prefs.udpPorts; byedpiPorts = prefs.byedpiPorts
                    applyIfOn()
                }, enabled = !busy) { Text(stringResource(R.string.save_apply)) }
            }

            SectionCard(title = stringResource(R.string.settings_check)) {
                Hint(stringResource(R.string.check_hint))
                val intervals = listOf(0, 1, 3, 6, 12)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    intervals.forEachIndexed { i, h ->
                        SegmentedButton(
                            selected = prefs.checkIntervalHours == h,
                            onClick = {
                                prefs.checkIntervalHours = h
                                HealthWorker.schedule(context, prefs)
                            },
                            shape = SegmentedButtonDefaults.itemShape(i, intervals.size),
                        ) { Text(if (h == 0) stringResource(R.string.off) else stringResource(R.string.hours_short, h)) }
                    }
                }
                SwitchRow(
                    title = stringResource(R.string.auto_reselect),
                    subtitle = stringResource(R.string.auto_reselect_hint),
                    checked = prefs.autoReselect,
                    onChange = { prefs.autoReselect = it },
                )
                SwitchRow(
                    title = stringResource(R.string.auto_store_update),
                    subtitle = stringResource(R.string.auto_store_update_hint),
                    checked = prefs.autoStoreUpdate,
                    onChange = { prefs.autoStoreUpdate = it },
                )
                SwitchRow(
                    title = stringResource(R.string.status_notification),
                    subtitle = stringResource(R.string.status_notification_hint),
                    checked = prefs.statusNotification,
                    onChange = {
                        prefs.statusNotification = it
                        StatusNotifier.update(context, status)
                    },
                )
                Text(stringResource(R.string.request_timeout, prefs.autoTimeoutSec))
                val timeouts = listOf(3, 5, 8, 12)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    timeouts.forEachIndexed { i, t ->
                        SegmentedButton(
                            selected = prefs.autoTimeoutSec == t,
                            onClick = { prefs.autoTimeoutSec = t },
                            shape = SegmentedButtonDefaults.itemShape(i, timeouts.size),
                        ) { Text(stringResource(R.string.seconds_short, t)) }
                    }
                }
            }

            SectionCard(title = stringResource(R.string.settings_module)) {
                Text(
                    stringResource(
                        R.string.module_info,
                        status.version.ifBlank { "—" },
                        if (status.hasConnbytes) "✓" else "✗",
                        if (status.values["HAS_MP"] == "1") "✓" else "✗",
                        if (status.hasNfqueue) "✓" else "✗",
                    ),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
                if (status.usable && !status.hasConnbytes) Hint(stringResource(R.string.no_connbytes_hint))
                val load = status.values.keys.filter { it.startsWith("cpu_") }.sorted()
                if (load.isNotEmpty()) {
                    Text(stringResource(R.string.load_title), style = MaterialTheme.typography.bodyMedium)
                    load.forEach { k ->
                        val name = k.removePrefix("cpu_")
                        val cpu = (status.values[k]?.toIntOrNull() ?: 0) / 100.0
                        val mem = (status.values["mem_"]?.toIntOrNull() ?: 0) / 1024
                        Text(
                            stringResource(R.string.load_line, name, String.format(java.util.Locale.ROOT, "%.2f", cpu), mem),
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Hint(stringResource(R.string.load_hint))
                }
                SwitchRow(
                    title = stringResource(R.string.debug_logs),
                    subtitle = stringResource(R.string.debug_logs_hint),
                    checked = prefs.debugLogs,
                    onChange = { prefs.debugLogs = it; applyIfOn() },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { nav.navigate("logs") }) { Text(stringResource(R.string.logs)) }
                    OutlinedButton(onClick = { vm.installModule() }, enabled = status.rootOk && !busy) {
                        Text(stringResource(R.string.module_reinstall))
                    }
                }
            }

            if (Build.VERSION.SDK_INT >= 33) {
                SectionCard(title = stringResource(R.string.language)) {
                    val lm = context.getSystemService(LocaleManager::class.java)
                    val current = lm.applicationLocales.toLanguageTags()
                    val options = listOf("" to R.string.language_system, "ru" to R.string.language_ru, "en" to R.string.language_en)
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        options.forEachIndexed { i, (tag, label) ->
                            SegmentedButton(
                                selected = current == tag,
                                onClick = { lm.applicationLocales = LocaleList.forLanguageTags(tag) },
                                shape = SegmentedButtonDefaults.itemShape(i, options.size),
                            ) { Text(stringResource(label)) }
                        }
                    }
                }
            }

            BackupCard(vm)

            UpdateCard(vm)

            SectionCard(title = stringResource(R.string.about)) {
                Hint(stringResource(R.string.about_credits))
            }
        }
    }

    excludeText?.let { text ->
        var value by remember(text) { mutableStateOf(text) }
        AlertDialog(
            onDismissRequest = { excludeText = null },
            title = { Text(stringResource(R.string.exclude_sites)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Hint(stringResource(R.string.exclude_sites_hint))
                    OutlinedTextField(
                        value = value, onValueChange = { value = it }, minLines = 6, maxLines = 14,
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val content = value.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n", postfix = "\n")
                    excludeText = null
                    vm.op {
                        val f = File(context.cacheDir, "exclude-edit.txt").apply { writeText(content) }
                        Module.copyIn(mapOf(Paths.EXCLUDE_LIST to f))
                        if (prefs.enabled) vm.app.applier.apply()
                    }
                }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = { TextButton(onClick = { excludeText = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
fun UpdateCard(vm: MainViewModel) {
    val u by vm.update.collectAsState()
    SectionCard(title = stringResource(R.string.update_title)) {
        Text(stringResource(R.string.about_version, BuildConfig.VERSION_NAME))
        val info = u.info
        when {
            u.checking -> {
                Text(stringResource(R.string.update_checking))
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            u.progress != null -> {
                val p = u.progress!!
                if (p >= 0f) {
                    Text(stringResource(R.string.update_downloading, (p * 100).toInt()))
                    LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                } else {
                    Text(stringResource(R.string.update_downloading, 0))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
            u.installing -> {
                Text(stringResource(R.string.update_installing))
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            info != null && u.available -> {
                Text(stringResource(R.string.update_available, info.versionName), style = MaterialTheme.typography.titleMedium)
                if (info.news.isNotEmpty()) {
                    Text(stringResource(R.string.update_changes), style = MaterialTheme.typography.bodyMedium)
                    info.news.forEach { Hint("• $it") }
                }
                Button(onClick = { vm.installUpdate() }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.update_install))
                }
                Hint(stringResource(R.string.update_hint))
            }
            info != null -> Text(stringResource(R.string.update_latest))
        }
        u.error?.let { Text(stringResource(R.string.update_error, it), color = MaterialTheme.colorScheme.error) }
        if (!u.checking && u.progress == null && !u.installing) {
            OutlinedButton(onClick = { vm.checkUpdate() }) { Text(stringResource(R.string.update_check)) }
        }
    }
}

/** Settings and own strategies to / from a JSON file. */
@Composable
private fun BackupCard(vm: MainViewModel) {
    val busy by vm.busy.collectAsState()
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) vm.exportBackup(uri)
    }
    val load = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importBackup(uri)
    }
    SectionCard(title = stringResource(R.string.backup_title)) {
        Hint(stringResource(R.string.backup_hint))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                val date = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.ROOT).format(java.util.Date())
                save.launch("manka-backup-$date.json")
            }, enabled = !busy) { Text(stringResource(R.string.backup_save)) }
            OutlinedButton(onClick = { load.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }, enabled = !busy) {
                Text(stringResource(R.string.backup_load))
            }
        }
    }
}
