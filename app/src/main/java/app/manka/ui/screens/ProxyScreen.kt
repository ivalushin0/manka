package app.manka.ui.screens

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import app.manka.R
import app.manka.core.ProxyConfig
import app.manka.core.Root
import app.manka.ui.Hint
import app.manka.ui.MainViewModel
import app.manka.ui.ScreenScaffold
import app.manka.ui.SectionCard
import app.manka.ui.SwitchRow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Composable
fun ProxyScreen(vm: MainViewModel, nav: NavController) {
    val busy by vm.busy.collectAsState()
    val status by vm.status.collectAsState()
    @Suppress("UNUSED_VARIABLE") val v by vm.prefs.version.collectAsState()
    val prefs = vm.prefs
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var key by remember { mutableStateOf(prefs.proxyKey) }
    var showKey by remember { mutableStateOf(false) }
    var domains by remember { mutableStateOf(prefs.proxyDomains) }
    var check by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    val parsed = remember(key) { runCatching { ProxyConfig.parse(key) } }
    val saved = remember(prefs.proxyKey) { runCatching { ProxyConfig.parse(prefs.proxyKey) }.getOrNull() }
    val appNames = remember(prefs.proxyApps) { labels(context, prefs.proxyApps) }

    fun saveKey() {
        prefs.proxyKey = key.trim()
        check = null
        if (prefs.proxy) vm.apply()
    }

    ScreenScaffold(title = stringResource(R.string.proxy_title), onBack = { nav.popBackStack() }, busy = busy) { pad ->
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(pad),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionCard {
                SwitchRow(
                    title = stringResource(R.string.proxy_title),
                    subtitle = proxyState(vm),
                    checked = prefs.proxy,
                    enabled = status.usable && !busy && status.proxyAvailable && (prefs.proxy || saved != null),
                    onChange = { prefs.proxy = it; check = null; vm.apply() },
                )
                Hint(stringResource(R.string.proxy_hint))
            }

            SectionCard(title = stringResource(R.string.proxy_server)) {
                OutlinedTextField(
                    value = key, onValueChange = { key = it },
                    label = { Text(stringResource(R.string.proxy_key)) },
                    placeholder = { Text("vless://…  vpn://…") },
                    singleLine = true,
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showKey = !showKey }) {
                            Icon(if (showKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, null)
                        }
                    },
                    supportingText = {
                        val e = parsed.exceptionOrNull()
                        when {
                            key.isBlank() -> Text(stringResource(R.string.proxy_key_empty))
                            e != null -> Text(invalidText(e), color = MaterialTheme.colorScheme.error)
                            else -> Text(parsed.getOrNull()!!.summary)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
                            ?.trim()?.let { key = it }
                    }) { Text(stringResource(R.string.proxy_paste)) }
                    Button(
                        onClick = { saveKey() },
                        enabled = !busy && key.trim() != prefs.proxyKey && (key.isBlank() || parsed.isSuccess),
                    ) { Text(stringResource(R.string.save)) }
                }
                Hint(stringResource(R.string.proxy_key_hint))
            }

            SectionCard(title = stringResource(R.string.proxy_route)) {
                SwitchRow(
                    title = stringResource(R.string.proxy_whole),
                    subtitle = stringResource(R.string.proxy_whole_hint),
                    checked = prefs.proxyWholeApps,
                    onChange = { prefs.proxyWholeApps = it; check = null; if (prefs.proxy) vm.apply() },
                )
                if (!prefs.proxyWholeApps) {
                    OutlinedTextField(
                        value = domains, onValueChange = { domains = it },
                        label = { Text(stringResource(R.string.proxy_domains)) },
                        minLines = 3, maxLines = 10,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Hint(stringResource(R.string.proxy_domains_hint))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { domains = ProxyConfig.GEMINI_DOMAINS.joinToString("\n") }) {
                            Text(stringResource(R.string.proxy_domains_reset))
                        }
                        Button(
                            onClick = {
                                prefs.proxyDomains = ProxyConfig.domains(domains).joinToString("\n")
                                domains = prefs.proxyDomains
                                if (prefs.proxy) vm.apply()
                            },
                            enabled = !busy && ProxyConfig.domains(domains) != ProxyConfig.domains(prefs.proxyDomains),
                        ) { Text(stringResource(R.string.save)) }
                    }
                }
            }

            SectionCard(title = stringResource(R.string.proxy_apps_title)) {
                Text(
                    appNames.ifEmpty { listOf(stringResource(R.string.proxy_apps_none)) }.joinToString(", "),
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedButton(onClick = { nav.navigate("proxy_apps") }) { Text(stringResource(R.string.proxy_apps_choose)) }
            }

            SectionCard(title = stringResource(R.string.proxy_check)) {
                Hint(stringResource(R.string.proxy_check_hint))
                OutlinedButton(
                    onClick = {
                        checking = true
                        scope.launch {
                            check = runCheck(vm)
                            checking = false
                        }
                    },
                    enabled = !checking && status.proxyRunning,
                ) { Text(stringResource(if (checking) R.string.proxy_checking else R.string.proxy_check_button)) }
                check?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
}

@Composable
fun proxyState(vm: MainViewModel): String {
    val status by vm.status.collectAsState()
    val prefs = vm.prefs
    return when {
        !status.proxyAvailable -> stringResource(R.string.proxy_unavailable)
        !prefs.proxy -> stringResource(R.string.proxy_off)
        vm.app.applier.proxyServer() == null -> stringResource(R.string.proxy_no_key)
        status.proxyRunning -> stringResource(R.string.proxy_running, prefs.proxyApps.size)
        else -> stringResource(R.string.proxy_not_running)
    }
}

@Composable
private fun invalidText(e: Throwable): String {
    val i = e as? ProxyConfig.Invalid ?: return stringResource(R.string.proxy_err_broken)
    return when (i.reason) {
        ProxyConfig.Reason.EMPTY -> stringResource(R.string.proxy_key_empty)
        ProxyConfig.Reason.FORMAT -> stringResource(R.string.proxy_err_format)
        ProxyConfig.Reason.BROKEN -> stringResource(R.string.proxy_err_broken)
        ProxyConfig.Reason.UNSUPPORTED -> stringResource(R.string.proxy_err_unsupported, i.detail)
        ProxyConfig.Reason.SUBSCRIPTION -> stringResource(R.string.proxy_err_subscription)
        ProxyConfig.Reason.NO_XRAY -> stringResource(R.string.proxy_err_no_xray, i.detail)
    }
}

private fun labels(context: Context, packages: Set<String>): List<String> {
    val pm = context.packageManager
    return packages.mapNotNull { pkg ->
        runCatching {
            @Suppress("DEPRECATION") pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString()
        }.getOrNull()
    }.sorted()
}

/**
 * Asks ipinfo.io for the exit address as one of the proxied apps (curl under its uid through root),
 * so the answer shows what those apps get.
 */
private suspend fun runCheck(vm: MainViewModel): String {
    val ctx = vm.app
    val uid = vm.app.applier.uidsOf(vm.prefs.proxyApps).firstOrNull()
        ?: return ctx.getString(R.string.proxy_apps_none)
    val r = Root.exec("su $uid -G 3003 -c ${Root.q("curl -s -m 15 https://ipinfo.io/json")}", 30)
    val o = runCatching { Json.parseToJsonElement(r.out.trim()).jsonObject }.getOrNull()
        ?: return ctx.getString(R.string.proxy_check_failed)
    fun f(k: String) = o[k]?.jsonPrimitive?.contentOrNull.orEmpty()
    val where = listOf(f("country"), f("city")).filter { it.isNotBlank() }.joinToString(", ")
    return ctx.getString(R.string.proxy_check_result, where, f("ip"), f("org"))
}

