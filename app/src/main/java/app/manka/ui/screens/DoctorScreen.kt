package app.manka.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.manka.BuildConfig
import app.manka.R
import app.manka.core.Engine
import app.manka.core.Module
import app.manka.ui.Hint
import app.manka.ui.HintStyle
import app.manka.ui.MainViewModel
import app.manka.ui.Mono
import app.manka.ui.ScreenScaffold
import app.manka.ui.SectionCard
import app.manka.ui.StatusColors
import app.manka.ui.StatusPill
import kotlinx.coroutines.launch

private enum class Health { Ok, Warn, Bad, Off }

private data class Check(val name: String, val detail: String, val health: Health, val badge: String)

/**
 * One screen that answers "what is wrong": root, module, engine, DNS, Telegram, the app proxy and
 * the network, with the proxy's own route state. The report button copies everything for a bug report.
 */
@Composable
fun DoctorScreen(vm: MainViewModel, onBack: () -> Unit) {
    val status by vm.status.collectAsState()
    val busy by vm.busy.collectAsState()
    val prefs = vm.prefs
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var probe by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }

    fun runChecks() {
        if (checking) return
        checking = true
        vm.refresh()
        scope.launch {
            probe = runCatching { Module.run("tgws-probe").out.trim() }.getOrNull()?.takeIf { it.isNotBlank() }
            checking = false
        }
    }
    LaunchedEffect(Unit) { runChecks() }

    val own = status.ownProfile
    val engine = Engine.of(status.values["engine"])
    val engineTitle = engine?.title ?: status.values["engine"].orEmpty()
    val dnsMode = when (status.values["dns_mode"]) {
        "doh" -> "DoH"
        "plain" -> "plain"
        "system" -> "system"
        else -> "—"
    }
    val dnsUp = status.values["dns_running"] == "1"
    val ok = stringResource(R.string.doctor_ok)
    val off = stringResource(R.string.doctor_off)
    val no = stringResource(R.string.doctor_no)

    val checks = listOf(
        Check(
            stringResource(R.string.doctor_root),
            if (status.rootOk) "uid 0" else stringResource(R.string.doctor_no_root),
            if (status.rootOk) Health.Ok else Health.Bad,
            if (status.rootOk) ok else no,
        ),
        Check(
            stringResource(R.string.doctor_module),
            when {
                !status.installed -> stringResource(R.string.doctor_not_installed)
                status.disabled -> stringResource(R.string.module_disabled)
                else -> status.version.ifBlank { "—" }
            },
            if (status.installed && !status.disabled) Health.Ok else Health.Bad,
            if (status.installed && !status.disabled) ok else no,
        ),
        Check(
            stringResource(R.string.doctor_engine),
            when {
                !prefs.enabled -> stringResource(R.string.tgws_off)
                !status.engineRunning -> stringResource(R.string.doctor_not_running)
                !status.rulesOk -> stringResource(R.string.doctor_no_rules)
                else -> "$engineTitle · ${profileTitle(vm, own)} · ${stringResource(R.string.doctor_rules)}"
            },
            when {
                !prefs.enabled -> Health.Off
                status.engineRunning && status.rulesOk -> Health.Ok
                else -> Health.Bad
            },
            when {
                !prefs.enabled -> off
                status.engineRunning && status.rulesOk -> ok
                else -> no
            },
        ),
        Check(
            stringResource(R.string.doctor_dns),
            "$dnsMode · 127.0.0.1:10853 " + if (dnsUp) "ok" else "—",
            when {
                status.values["dns_mode"] == "system" -> Health.Warn
                dnsUp -> Health.Ok
                else -> Health.Bad
            },
            when {
                status.values["dns_mode"] == "system" -> off
                dnsUp -> ok
                else -> no
            },
        ),
        Check(
            stringResource(R.string.doctor_telegram),
            when {
                !prefs.tgws -> stringResource(R.string.tgws_off)
                !status.tgwsRunning -> stringResource(R.string.tgws_not_running)
                else -> probe?.lineSequence()?.firstOrNull()?.takeIf { it.isNotBlank() }
                    ?: "127.0.0.1:${prefs.tgwsPort}"
            },
            when {
                !prefs.tgws -> Health.Off
                status.tgwsRunning -> Health.Ok
                else -> Health.Bad
            },
            when {
                !prefs.tgws -> off
                status.tgwsRunning -> ok
                else -> no
            },
        ),
        Check(
            stringResource(R.string.doctor_proxy),
            proxyState(vm),
            when {
                !prefs.proxy -> Health.Off
                status.proxyRunning -> Health.Ok
                !status.proxyAvailable -> Health.Bad
                else -> Health.Warn
            },
            when {
                !prefs.proxy -> off
                status.proxyRunning -> ok
                else -> no
            },
        ),
        Check(
            stringResource(R.string.doctor_network),
            profileTitle(vm, own) + (status.ssid?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
            Health.Ok,
            ok,
        ),
    )

    val bad = checks.count { it.health == Health.Bad }
    val warn = checks.count { it.health == Health.Warn }
    val okCount = checks.count { it.health == Health.Ok }
    val summary = when {
        bad > 0 -> stringResource(R.string.doctor_problems)
        warn > 0 -> stringResource(R.string.doctor_warnings)
        else -> stringResource(R.string.doctor_all_ok)
    }
    val summaryColor = when {
        bad > 0 -> StatusColors.bad
        warn > 0 -> StatusColors.warn
        else -> StatusColors.ok
    }

    fun report(): String = buildString {
        appendLine("Manka ${BuildConfig.VERSION_NAME} · module ${status.version}")
        appendLine("--- status")
        status.values.toSortedMap().forEach { (k, v) -> appendLine("$k=$v") }
        if (!probe.isNullOrBlank()) {
            appendLine("--- tgws-probe")
            appendLine(probe)
        }
    }

    ScreenScaffold(
        title = stringResource(R.string.doctor_title),
        subtitle = stringResource(R.string.doctor_subtitle),
        onBack = onBack,
        busy = busy || checking,
    ) { pad ->
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(pad),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionCard(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = 10.dp)) {
                        Text(summary, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Hint(stringResource(R.string.doctor_summary, okCount, checks.size))
                    }
                    StatusPill(
                        text = if (bad > 0) stringResource(R.string.doctor_problems) else ok,
                        color = summaryColor,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { runChecks() }, enabled = !checking, modifier = Modifier.weight(1f)) {
                        Text(stringResource(if (checking) R.string.tgws_checking else R.string.doctor_check_again))
                    }
                    Button(
                        onClick = {
                            val cm = context.getSystemService(ClipboardManager::class.java)
                            cm.setPrimaryClip(ClipData.newPlainText("manka report", report()))
                            vm.say(R.string.doctor_report_copied)
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.doctor_report)) }
                }
            }

            SectionCard {
                checks.forEachIndexed { index, check ->
                    DoctorRow(check)
                    if (index < checks.lastIndex) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }

            if (!probe.isNullOrBlank()) {
                SectionCard(title = "tgws-probe") { Mono(probe.orEmpty()) }
            }

            Hint(stringResource(R.string.doctor_hint))
        }
    }
}

@Composable
private fun DoctorRow(check: Check) {
    val color = when (check.health) {
        Health.Ok -> StatusColors.ok
        Health.Warn -> StatusColors.warn
        Health.Bad -> StatusColors.bad
        Health.Off -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f).padding(end = 10.dp)) {
            Text(check.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(check.detail, style = HintStyle.copy(color = MaterialTheme.colorScheme.onSurfaceVariant))
        }
        StatusPill(check.badge, color)
    }
}
