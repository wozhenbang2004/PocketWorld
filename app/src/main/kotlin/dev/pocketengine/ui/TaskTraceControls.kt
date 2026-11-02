package dev.pocketengine.ui

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import dev.pocketengine.R
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.pocketengine.core.RunControl
import dev.pocketengine.runtime.usage.OtlpTraceExport
import dev.pocketengine.runtime.usage.TaskDetailedTrace
import org.json.JSONObject

/** Low-frequency debug controls and explicit preview/send, mounted in existing settings/task details. */
@Composable
internal fun TaskTraceControls(manager: TaskDetailedTrace, runId: String?, worker: (Runnable) -> Unit) {
    val context = LocalContext.current
    val main = remember { Handler(Looper.getMainLooper()) }
    var open by remember { mutableStateOf(false) }; var snapshot by remember { mutableStateOf(JSONObject()) }
    var archive by remember(runId) { mutableStateOf<JSONObject?>(null) }; var preview by remember(runId) { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }; var error by remember { mutableStateOf<String?>(null) }; var receipt by remember { mutableStateOf<JSONObject?>(null) }
    var dialog by remember { mutableStateOf(false) }; var endpoint by remember { mutableStateOf("") }; var header by remember { mutableStateOf("Authorization") }; var secret by remember { mutableStateOf("") }
    var control by remember { mutableStateOf<RunControl?>(null) }; var alive by remember(manager) { mutableStateOf(true) }
    fun execute(action: () -> JSONObject, done: (JSONObject) -> Unit) { if (busy) return; busy = true; error = null; try { worker(Runnable { val result = runCatching(action); main.post { if (alive) { busy = false; control = null; result.fold(done) { error = context.getString(R.string.w2_trace_action_failed) } } } }) } catch (_: Exception) { busy = false; error = context.getString(R.string.w2_diagnostics_worker_unavailable) } }
    DisposableEffect(manager) { onDispose { alive = false; control?.cancel(); secret = "" } }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { open = !open; if (open) execute({ manager.snapshot() }) { snapshot = it } }) { Text(stringResource(if (open) R.string.w2_trace_collapse else R.string.w2_trace_expand)) }
        if (open && snapshot.optBoolean("supported")) {
            Text(stringResource(R.string.w2_trace_collection_notice), style = MaterialTheme.typography.bodySmall)
            Row { Switch(snapshot.optBoolean("enabled"), { enabled -> execute({ manager.configure(enabled, (snapshot.optLong("limitBytes", 8388608) / 1048576).toInt()); manager.snapshot() }) { snapshot = it } }, enabled = !busy); Text(if (snapshot.optBoolean("enabled")) stringResource(R.string.w2_trace_collecting) else stringResource(R.string.w2_trace_default_off), style = MaterialTheme.typography.bodyMedium) }
            Text(stringResource(R.string.w2_trace_retained_bytes, snapshot.optLong("retainedBytes"), snapshot.optLong("limitBytes")), style = MaterialTheme.typography.bodySmall)
            Row { listOf(1, 8, 32).forEach { limit -> TextButton(onClick = { execute({ manager.configure(snapshot.optBoolean("enabled"), limit); manager.snapshot() }) { snapshot = it } }, enabled = !busy) { Text(stringResource(R.string.w2_trace_limit_mib, limit)) } } }
            if (!runId.isNullOrBlank()) TextButton(onClick = { execute({ manager.read(runId) }) { value -> archive = value; preview = if (value.optBoolean("available")) OtlpTraceExport.preview(value).toString(2) else null; if (!value.optBoolean("available")) error = context.getString(R.string.w2_trace_not_retained) } }, enabled = !busy) { Text(stringResource(R.string.w2_trace_preview)) }
            preview?.let { text -> Text(stringResource(R.string.w2_trace_preview_size, archive?.optJSONArray("spans")?.length() ?: 0, text.toByteArray().size), style = MaterialTheme.typography.labelSmall); SelectionContainer { Text(text.take(4000) + if (text.length > 4000) stringResource(R.string.w2_trace_preview_truncated) else "", style = MaterialTheme.typography.bodySmall) }; TextButton(onClick = { dialog = true }, enabled = !busy) { Text(stringResource(R.string.w2_trace_choose_endpoint)) } }
            receipt?.let { value -> Text(when (value.optString("status")) { "accepted" -> stringResource(R.string.w2_trace_accepted); "partially_rejected" -> stringResource(R.string.w2_trace_partially_rejected, value.optLong("rejectedSpans")); "accepted_with_warning" -> stringResource(R.string.w2_trace_warning); else -> stringResource(R.string.w2_trace_send_failed) }, style = MaterialTheme.typography.bodySmall) }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (control != null) TextButton(onClick = { control?.cancel() }) { Text(stringResource(R.string.w2_trace_stop_send)) }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
    if (dialog) AlertDialog(onDismissRequest = { dialog = false; secret = "" }, title = { Text(stringResource(R.string.w2_trace_send_title)) }, text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(stringResource(R.string.w2_trace_send_notice), style = MaterialTheme.typography.bodySmall); OutlinedTextField(endpoint, { endpoint = it }, label = { Text(stringResource(R.string.w2_trace_endpoint)) }); OutlinedTextField(header, { header = it }, label = { Text(stringResource(R.string.w2_trace_auth_header)) }); OutlinedTextField(secret, { secret = it }, label = { Text(stringResource(R.string.w2_trace_auth_value)) }, visualTransformation = PasswordVisualTransformation()) } }, confirmButton = { TextButton(onClick = { val selected = archive ?: return@TextButton; val value = secret; secret = ""; dialog = false; val run = RunControl(); control = run; execute({ OtlpTraceExport.send(selected, endpoint, header, value, run) }) { receipt = it } }, enabled = !busy) { Text(stringResource(R.string.w2_trace_send_once)) } }, dismissButton = { TextButton(onClick = { dialog = false; secret = "" }) { Text(stringResource(R.string.w2_trace_cancel)) } })
}
