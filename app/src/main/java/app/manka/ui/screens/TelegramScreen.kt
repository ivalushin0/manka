package app.manka.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.manka.R
import app.manka.ui.Hint
import app.manka.ui.MainViewModel
import app.manka.ui.Mono
import app.manka.ui.ScreenScaffold
import app.manka.ui.SectionCard
import app.manka.ui.SwitchRow

@Composable
fun TelegramScreen(vm: MainViewModel, onBack: () -> Unit) {
    val busy by vm.busy.collectAsState()
    val status by vm.status.collectAsState()
    @Suppress("UNUSED_VARIABLE") val v by vm.prefs.version.collectAsState()
    val prefs = vm.prefs
    val context = LocalContext.current

    var port by remember { mutableStateOf(prefs.tgwsPort.toString()) }

    /** Saves the settings and applies them: the module restarts the proxy only when its arguments
     * really changed (a restart cuts every connection of Telegram). */
    fun save() {
        port.toIntOrNull()?.takeIf { it in 1024..65535 }?.let { prefs.tgwsPort = it }
        vm.apply()
    }

    ScreenScaffold(title = stringResource(R.string.telegram), onBack = onBack, busy = busy) { pad ->
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(pad),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionCard {
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
                    OutlinedButton(onClick = { save() }, enabled = !busy) { Text(stringResource(R.string.save_restart)) }
                }
                Mono(telegramLink(vm))
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
