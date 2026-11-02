package dev.pocketengine.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pocketengine.EngineApplication
import dev.pocketengine.R
import dev.pocketengine.features.operations.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Foreground maintenance in the existing native settings flow, with explicit actions and fixed previews. */
@Composable
internal fun OperationsPage(onLanguageApply: (String, (String?) -> Unit) -> Unit,
    onPhonePractice: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as EngineApplication
    val owner = remember(app) { NativeOperationsOwner(app) }
    val updates = remember(app) { runCatching { PrivateAppUpdates.get(app) }.getOrNull() }
    val scope = rememberCoroutineScope()
    val main = remember { Handler(Looper.getMainLooper()) }
    val alive = remember { booleanArrayOf(true) }
    DisposableEffect(owner) { onDispose { alive[0] = false; owner.close() } }
    var running by remember { mutableStateOf(false) }
    var check by remember { mutableStateOf<JSONObject?>(null) }
    var expandedChecks by remember { mutableStateOf(emptySet<String>()) }
    var diagnostic by remember { mutableStateOf<MinimalDiagnostics.Snapshot?>(null) }
    var update by remember { mutableStateOf(runCatching { updates?.state() }.getOrNull()) }
    var language by remember { mutableStateOf(AppLanguage.selected(app)) }
    var error by remember { mutableStateOf<String?>(null) }
    var configuring by remember { mutableStateOf(false) }
    var source by remember { mutableStateOf("") }
    fun work(action: suspend () -> Unit) {
        if (running) return
        running = true; error = null
        scope.launch { try { action() } catch (failure: Exception) { error = failure.message?.take(240) ?: failure.javaClass.simpleName } finally { running = false } }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.ops_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = onBack) { Text(stringResource(R.string.ops_back)) }
        }
        Text(stringResource(R.string.ops_checks), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.ops_checks_note), fontSize = 13.sp, lineHeight = 20.sp)
        if (check?.optBoolean("ended") == false) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            TextButton(onClick = owner::cancelChecks) { Text(stringResource(R.string.ops_stop)) }
        } else Button(onClick = {
            try { owner.startChecks { value -> main.post { if (alive[0]) check = value } } }
            catch (failure: Exception) { error = failure.message }
        }, enabled = !running) { Text(stringResource(R.string.ops_start)) }
        check?.optJSONArray("checks")?.let { rows ->
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(when(row.optString("id")) { "device" -> R.string.ops_check_device; "permissions" -> R.string.ops_check_permissions; "phone" -> R.string.ops_check_phone; "browser" -> R.string.ops_check_browser; else -> R.string.ops_check_runtime }), fontSize = 13.sp)
                    Text(stringResource(when(row.optString("status")) { "passed" -> R.string.ops_status_passed; "partial" -> R.string.ops_status_partial; "failed" -> R.string.ops_status_failed; "skipped" -> R.string.ops_status_skipped; else -> R.string.ops_status_running }), fontSize = 13.sp)
                }
                val evidence = row.optJSONObject("evidence")
                val reason = row.optString("reason").ifBlank { evidence?.optString("reason").orEmpty() }
                val reasonText = when (reason) {
                    "step_timeout", "total_timeout" -> R.string.ops_check_timeout_note
                    "cancelled" -> R.string.ops_check_cancelled_note
                    "runtime_not_prepared", "native_supervisor_unavailable" -> R.string.ops_check_runtime_note
                    "historical_original_practice_read_only", "current_original_practice_read_only", "run_original_phone_practice_task_for_action_evidence" -> R.string.ops_check_phone_note
                    "probe_error", "fixed_runtime_probe_failed", "generation_release_unconfirmed" -> R.string.ops_check_failed_note
                    else -> when {
                        row.optString("id") == "permissions" && row.optString("status") == "partial" -> R.string.ops_check_permissions_note
                        row.optString("status") == "failed" -> R.string.ops_check_failed_note
                        else -> null
                    }
                }
                reasonText?.let { Text(stringResource(it), fontSize = 12.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (evidence != null || reason.isNotBlank()) {
                    val id = row.optString("id")
                    TextButton(onClick = { expandedChecks = if (id in expandedChecks) expandedChecks - id else expandedChecks + id }) {
                        Text(stringResource(if (id in expandedChecks) R.string.ops_check_hide_evidence else R.string.ops_check_show_evidence))
                    }
                    if (id in expandedChecks) SelectionContainer {
                        Text(row.toString(2), fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 16.sp,
                            modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp).verticalScroll(rememberScrollState()))
                    }
                }
            }
            if (check?.optBoolean("ended") == true && check?.optBoolean("resourcesReleased") != true) Text(stringResource(R.string.ops_cleanup_unknown), color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
        }
        TextButton(onClick = onPhonePractice) { Text(stringResource(R.string.ops_phone_practice)) }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text(stringResource(R.string.ops_diagnostics), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.ops_diagnostics_note), fontSize = 13.sp, lineHeight = 20.sp)
        TextButton(onClick = { work { diagnostic = withContext(Dispatchers.IO) { owner.diagnostics() } } }, enabled = !running) { Text(stringResource(R.string.ops_preview)) }
        diagnostic?.let { fixed ->
            SelectionContainer { Text(fixed.text, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp).verticalScroll(rememberScrollState())) }
            Text(fixed.sha256.take(16), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Pocket World diagnostics", fixed.text)) }) { Text(stringResource(R.string.ops_copy)) }
                TextButton(onClick = { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, fixed.text), context.getString(R.string.ops_diagnostics))) }) { Text(stringResource(R.string.ops_share)) }
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text(stringResource(R.string.ops_language), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.ops_language_note), fontSize = 13.sp, lineHeight = 20.sp)
        listOf("system" to R.string.ops_language_system, "zh-Hans" to R.string.ops_language_zh, "en" to R.string.ops_language_en).forEach { (tag, title) ->
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(language == tag, role = Role.RadioButton, onClick = { language = tag }), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = language == tag, onClick = null);Text(stringResource(title), Modifier.padding(start = 8.dp))
            }
        }
        Button(onClick = { running = true; owner.cancelChecks(); onLanguageApply(language) { failure -> if (alive[0]) { running = false; error = failure } } }, enabled = !running && language != AppLanguage.selected(app)) { Text(stringResource(R.string.ops_language_apply)) }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text(stringResource(R.string.ops_update), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.ops_update_note), fontSize = 13.sp, lineHeight = 20.sp)
        TextButton(onClick = { configuring = true }, enabled = !running && updates != null) { Text(stringResource(R.string.ops_update_configure)) }
        val phase = update?.optString("phase").orEmpty()
        Text(stringResource(when(phase) { "available" -> R.string.ops_update_available; "up_to_date" -> R.string.ops_update_current; "downloading" -> R.string.ops_update_downloading; "ready" -> R.string.ops_update_ready; "rejected" -> R.string.ops_update_rejected; "enqueue_unknown", "download_unknown", "cancellation_unconfirmed" -> R.string.ops_update_unknown; else -> R.string.ops_update_manual }), fontSize = 13.sp)
        update?.takeIf { it.has("versionCode") }?.let { Text("${it.optString("versionName")} · ${it.optLong("versionCode")} · ${it.optLong("bytes")} B", fontSize = 12.sp) }
        if (update?.optBoolean("sourceConfigured") == true && !update!!.has("updateId")) TextButton(onClick = { work { update = withContext(Dispatchers.IO) { updates!!.check() } } }, enabled = !running) { Text(stringResource(R.string.ops_update_check)) }
        if (phase == "available") Button(onClick = { work { update = withContext(Dispatchers.IO) { updates!!.download(update!!.getString("sha256")) } } }, enabled = !running) { Text(stringResource(R.string.ops_update_download)) }
        update?.optString("updateId")?.takeIf { it.isNotEmpty() }?.let { id ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { work { update = withContext(Dispatchers.IO) { updates!!.query(id) } } }, enabled = !running) { Text(stringResource(R.string.ops_update_query)) }
                TextButton(onClick = { work { update = withContext(Dispatchers.IO) { updates!!.cancel(id) } } }, enabled = !running && phase != "cancelled") { Text(stringResource(R.string.ops_update_cancel)) }
            }
            if (phase == "ready" || phase == "installer_requested") Button(onClick = { work { val install = withContext(Dispatchers.IO) { updates!!.installIntent(id) }; context.startActivity(install) } }, enabled = !running) { Text(stringResource(R.string.ops_update_install)) }
        }
        if (!context.packageManager.canRequestPackageInstalls()) TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + context.packageName))) }) { Text(stringResource(R.string.ops_update_permission)) }
        if (running) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
        Spacer(Modifier.height(28.dp))
    }
    if (configuring) AlertDialog(onDismissRequest = { configuring = false }, title = { Text(stringResource(R.string.ops_update_configure)) }, text = { OutlinedTextField(source, { if (it.length <= 2048) source = it }, label = { Text("HTTPS manifest URL") }, singleLine = true) }, confirmButton = {
        TextButton(onClick = { work { update = withContext(Dispatchers.IO) { updates!!.configure(source) }; configuring = false } }, enabled = !running && source.isNotBlank()) { Text(stringResource(R.string.ops_save)) }
    }, dismissButton = { TextButton(onClick = { configuring = false }) { Text(stringResource(R.string.ops_cancel)) } })
}
