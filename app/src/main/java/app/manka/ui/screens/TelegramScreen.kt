package app.manka.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.Composable
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
import app.manka.ui.Hint
import app.manka.ui.MainViewModel
import app.manka.ui.Mono
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

    ScreenScaffold(title = stringResource(R.string.telegram), onBack = onBack, busy = busy) { pad ->
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
                    StatusPill(running = status.tgwsRunning)
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
                    OutlinedButton(onClick = { save() }, enabled = !busy) { Text(stringResource(R.string.save_restart)) }
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

            SectionCard(title = stringResource(R.string.tgws_params)) {
                KeyRow(stringResource(R.string.tgws_server)) { Mono("127.0.0.1") }
                KeyRow(stringResource(R.string.tgws_port)) { Mono(prefs.tgwsPort.toString()) }
                KeyRow(stringResource(R.string.tgws_secret)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Mono(
                            if (showSecret) prefs.tgwsSecret else "•".repeat(16),
                            Modifier.weight(1f),
                        )
                        OutlinedButton(onClick = { showSecret = !showSecret }) {
                            Text(stringResource(if (showSecret) R.string.tgws_hide else R.string.tgws_show))
                        }
                        OutlinedButton(onClick = { copy(prefs.tgwsSecret) }) { Text(stringResource(R.string.copy)) }
                    }
                }
                KeyRow(stringResource(R.string.tgws_link)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Mono(telegramLink(vm), Modifier.weight(1f), maxLines = 2)
                        OutlinedButton(onClick = { copy(telegramLink(vm)) }) { Text(stringResource(R.string.copy)) }
                    }
                }
            }

            SectionCard(title = stringResource(R.string.tgws_routing)) {
                OutlinedTextField(
                    value = port, onValueChange = { port = it.filter(Char::isDigit).take(5) },
                    label = { Text(stringResource(R.string.tgws_port)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(onClick = {
                    prefs.regenerateTgwsSecret()
                    save()
                }) { Text(stringResource(R.string.tgws_new_secret)) }
                Hint(stringResource(R.string.tgws_secret_hint))
            }
        }
    }
}

@Composable
private fun StatusPill(running: Boolean) {
    val color = if (running) StatusColors.ok else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(color = color.copy(alpha = 0.15f), shape = RoundedCornerShape(50)) {
        Text(
            stringResource(if (running) R.string.tgws_status_on else R.string.tgws_status_off),
            Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelMedium,
            color = color,
        )
    }
}

@Composable
private fun KeyRow(key: String, value: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            key,
            Modifier.width(84.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box(Modifier.weight(1f)) { value() }
    }
}
