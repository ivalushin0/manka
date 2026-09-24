package app.manka.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
    LaunchedEffect(reload) {
        val status = Module.status().values.entries.joinToString("\n") { "${it.key}=${it.value}" }
        text = "===== status\n$status\n\n" + Module.logs().ifBlank { context.getString(R.string.logs_empty) }
    }
    ScreenScaffold(
        title = stringResource(R.string.logs),
        onBack = onBack,
        actions = {
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
