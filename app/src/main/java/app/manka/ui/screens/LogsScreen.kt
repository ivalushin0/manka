package app.manka.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.manka.R
import app.manka.core.Module
import app.manka.core.NetDiag
import app.manka.ui.MainViewModel
import app.manka.ui.Mono
import app.manka.ui.ScreenScaffold
import kotlinx.coroutines.launch

@Composable
fun LogsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var reload by remember { mutableIntStateOf(0) }
    var diagRunning by remember { mutableStateOf(false) }
    var diagMenu by remember { mutableStateOf(false) }
    LaunchedEffect(reload) {
        val status = Module.status().values.entries.joinToString("\n") { "${it.key}=${it.value}" }
        text = "===== status\n$status\n\n" + Module.logs().ifBlank { context.getString(R.string.logs_empty) }
    }
    ScreenScaffold(
        title = stringResource(R.string.logs),
        onBack = onBack,
        actions = {
            Box {
                IconButton(enabled = !diagRunning, onClick = { diagMenu = true }) {
                    Icon(Icons.Filled.NetworkCheck, stringResource(R.string.diag))
                }
                DropdownMenu(expanded = diagMenu, onDismissRequest = { diagMenu = false }) {
                    val all = stringResource(R.string.diag_all)
                    val options = NetDiag.SERVICES.toList() +
                        (all to NetDiag.SERVICES.values.flatten().distinct())
                    options.forEach { (name, hosts) ->
                        DropdownMenuItem(text = { Text(name) }, onClick = {
                            diagMenu = false
                            diagRunning = true
                            vm.say(context.getString(R.string.diag_started, name))
                            scope.launch {
                                val sb = StringBuilder()
                                try {
                                    NetDiag.run(name, hosts, vm.prefs.autoTimeoutSec) { line ->
                                        sb.appendLine(line)
                                        text = sb.toString()
                                    }
                                } finally {
                                    diagRunning = false
                                }
                            }
                        })
                    }
                }
            }
            IconButton(onClick = { reload++ }) { Icon(Icons.Filled.Refresh, stringResource(R.string.refresh)) }
            IconButton(onClick = {
                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("manka log", text))
                vm.say(R.string.copied)
            }) { Icon(Icons.Filled.ContentCopy, stringResource(R.string.copy)) }
            IconButton(onClick = { scope.launch { Module.clearLogs(); reload++ } }) {
                Icon(Icons.Filled.Delete, stringResource(R.string.delete))
            }
        },
    ) { pad ->
        SelectionContainer {
            Mono(
                text,
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(pad),
            )
        }
    }
}
