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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.manka.R
import app.manka.core.Module
import app.manka.ui.Banner
import app.manka.ui.BannerKind
import app.manka.ui.Hint
import app.manka.ui.KeyRow
import app.manka.ui.MainViewModel
import app.manka.ui.Mono
import app.manka.ui.StatusPill
import app.manka.ui.ScreenScaffold
import app.manka.ui.SectionCard
import app.manka.ui.StatusColors
import app.manka.ui.SwitchRow
import kotlinx.coroutines.launch

@Composable
fun TelegramScreen(vm: MainViewModel, onBack: () -> Unit) {
    val busy by vm.busy.collectAsState()
    val status by vm.status.collectAsState()
    @Suppress("UNUSED_VARIABLE") val v by vm.prefs.version.collectAsState()
    val prefs = vm.prefs
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var port by remember { mutableStateOf(prefs.tgwsPort.toString()) }
    var showSecret by rememberSaveable { mutableStateOf(false) }
    var check by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    var owner by remember { mutableStateOf<String?>(null) }

    // the port may be taken by the official TgWsProxy app: it and Manka both use 1443
    LaunchedEffect(status.tgwsRunning, prefs.tgws) {
        owner = if (prefs.tgws && !status.tgwsRunning) {
            runCatching { Module.run("tgws-port-owner").out.trim() }.getOrNull()?.takeIf { it.isNotBlank() }
        } else null
    }

    /** Saves the settings and applies them: the module restarts the proxy only when its arguments
     * really changed (a restart cuts every connection of Telegram). */
    fun save() {
        port.toIntOrNull()?.takeIf { it in 1024..65535 }?.let { prefs.tgwsPort = it }
        vm.apply()
    }

    fun copy(text: String) {
        val cm = context.getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("manka", text))
        vm.say(R.string.tgws_copied)
    }

    ScreenScaffold(
        title = stringResource(R.string.telegram),
        subtitle = stringResource(R.string.tgws_subtitle),
        onBack = onBack,
        busy = busy,
    ) { pad ->
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(pad),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.tgws_title),
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    StatusPill(
                        text = stringResource(if (status.tgwsRunning) R.string.tgws_status_on else R.string.tgws_status_off),
                        color = if (status.tgwsRunning) StatusColors.ok else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                SwitchRow(
                    title = stringResource(R.string.tgws_title),
                    subtitle = when {
                        !status.tgwsRunning -> stringResource(R.string.tgws_not_running)
                        !status.tgwsDirect -> stringResource(R.string.tgws_running_cf, prefs.tgwsPort)
                        else -> stringResource(R.string.tgws_running, prefs.tgwsPort)
                    },
                    checked = prefs.tgws,
                    enabled = status.usable && !busy,
                    onChange = { vm.setTgws(it) },
                )
                Hint(stringResource(R.string.tgws_background_hint))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { openTelegram(context, vm) }) { Text(stringResource(R.string.tgws_connect)) }
                    OutlinedButton(
                        enabled = !checking && status.tgwsRunning,
                        onClick = {
                            checking = true
                            check = null
                            scope.launch {
                                val out = runCatching { Module.run("tgws-probe").out.trim() }.getOrDefault("")
                                check = out.ifBlank { context.getString(R.string.tgws_check_empty) }
                                checking = false
                            }
                        },
                    ) { Text(stringResource(if (checking) R.string.tgws_checking else R.string.tgws_check)) }
                }
                check?.let {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Mono(it, Modifier.fillMaxWidth().padding(12.dp))
                    }
                }
            }

            val ownerName = owner?.substringBefore(' ')?.takeIf { it.isNotBlank() }
            if (ownerName != null) {
                val ownerPid = owner?.substringAfter(' ', "").orEmpty().ifBlank { "?" }
                val known = ownerName.contains("tgws", true) || ownerName.contains("tg-ws-proxy", true)
                Banner(
                    kind = BannerKind.Warn,
                    title = stringResource(R.string.tgws_port_busy, prefs.tgwsPort, ownerName, ownerPid),
                    text = stringResource(R.string.tgws_port_busy_hint),
                ) {
                    if (known) {
                        OutlinedButton(onClick = {
                            scope.launch {
                                Module.run("tgws-take-port")
                                vm.refresh()
                                owner = null
                            }
                        }) { Text(stringResource(R.string.tgws_take_port)) }
                    }
                }
            }

            SectionCard(title = stringResource(R.string.tgws_params)) {
                KeyRow(stringResource(R.string.tgws_server)) { Mono("127.0.0.1") }
                KeyRow(stringResource(R.string.tgws_port)) { Mono(prefs.tgwsPort.toString()) }
                KeyRow(stringResource(R.string.tgws_secret)) {
                    Mono(
                        if (showSecret) prefs.tgwsSecret else "•".repeat(12),
                        Modifier.fillMaxWidth(),
                        maxLines = 1,
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    TextButton(onClick = { showSecret = !showSecret }) {
                        Text(stringResource(if (showSecret) R.string.tgws_hide else R.string.tgws_show))
                    }
                    TextButton(onClick = { copy(prefs.tgwsSecret) }) { Text(stringResource(R.string.copy)) }
                }
                KeyRow(stringResource(R.string.tgws_link)) {
                    Mono(telegramLink(vm), Modifier.fillMaxWidth(), maxLines = 2)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    TextButton(onClick = { copy(telegramLink(vm)) }) { Text(stringResource(R.string.tgws_copy_link)) }
                }
            }

            SectionCard(title = stringResource(R.string.tgws_routing)) {
                OutlinedTextField(
                    value = port, onValueChange = { port = it.filter(Char::isDigit).take(5) },
                    label = { Text(stringResource(R.string.tgws_port)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            prefs.regenerateTgwsSecret()
                            save()
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.tgws_new_secret)) }
                    OutlinedButton(onClick = { save() }, enabled = !busy, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.save_restart))
                    }
                }
                Hint(stringResource(R.string.tgws_secret_hint))
            }
        }
    }
}


