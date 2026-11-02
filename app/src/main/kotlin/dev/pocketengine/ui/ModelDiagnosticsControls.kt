package dev.pocketengine.ui

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import dev.pocketengine.R
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import dev.pocketengine.core.RunControl
import dev.pocketengine.runtime.models.ModelBinding
import dev.pocketengine.runtime.models.ModelConnectionService
import dev.pocketengine.runtime.models.ModelTargetRef
import kotlinx.coroutines.delay
import org.json.JSONObject

/** Mounted within the original model settings; all calls remain explicit native owner actions. */
@Composable
internal fun ModelDiagnosticsControls(service: ModelConnectionService, snapshot: JSONObject, worker: (Runnable) -> Unit, changed: () -> Unit) {
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    val main = remember { Handler(Looper.getMainLooper()) }
    val identity = snapshot.optJSONObject("api")?.toString().orEmpty()
    var expanded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var control by remember { mutableStateOf<RunControl?>(null) }
    var image by remember(identity) { mutableStateOf(false) }
    var result by remember(identity) { mutableStateOf(snapshot.optJSONObject("capabilityProbe")) }
    var error by remember { mutableStateOf<String?>(null) }
    var routing by remember(snapshot.toString()) { mutableStateOf(snapshot.optJSONObject("stepRouting") ?: JSONObject()) }
    var model by remember { mutableStateOf("") }
    var auth by remember { mutableStateOf(JSONObject()) }
    var alive by remember(service) { mutableStateOf(true) }
    fun execute(action: () -> JSONObject, done: (JSONObject) -> Unit) {
        if (busy) return
        busy = true; error = null
        try { worker(Runnable {
            val response = runCatching(action)
            main.post { if (alive) { busy = false; control = null; response.fold(done) { error = if (it is InterruptedException) context.getString(R.string.w2_diag_stopped) else context.getString(R.string.w2_diag_failed) }; changed() } }
        }) } catch (_: Exception) { busy = false; error = context.getString(R.string.w2_diagnostics_worker_unavailable) }
    }
    DisposableEffect(service) { onDispose { alive = false; control?.cancel(); service.openRouter().cancel() } }
    LaunchedEffect(auth.optString("attemptId"), auth.optString("status")) {
        while (auth.optString("status") == "pending") { delay(1200); auth = service.openRouter().snapshot(); if (auth.optString("status") == "completed") changed() }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { expanded = !expanded }) { Text(stringResource(if (expanded) R.string.w2_diag_collapse else R.string.w2_diag_expand)) }
        if (expanded) {
            Text(stringResource(R.string.w2_diag_capability), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.w2_diag_probe_notice), style = MaterialTheme.typography.bodySmall)
            Row { Checkbox(image, { image = it }, enabled = !busy); Text(stringResource(R.string.w2_diag_probe_image), style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { val binding = snapshot.optJSONObject("api")?.optJSONObject("capabilityBinding")?.let(ModelBinding::fromJson) ?: return@Button; val run = RunControl(); control = run; execute({ service.probe(binding, image, run) }) { result = it } }, enabled = !busy && snapshot.optJSONObject("api")?.optJSONObject("capabilityBinding") != null) { Text(stringResource(R.string.w2_diag_probe_start)) }
                if (control != null) TextButton(onClick = { control?.cancel() }) { Text(stringResource(R.string.w2_diag_probe_stop)) }
            }
            result?.let { value ->
                Text(stringResource(R.string.w2_diag_probe_results, probeStatus(value.optString("text")), probeStatus(value.optString("tools")), probeStatus(value.optString("image"))), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.w2_diag_probe_revision, value.optString("model")), style = MaterialTheme.typography.labelSmall)
            }
            HorizontalDivider()
            Text(stringResource(R.string.w2_diag_routing_state, stringResource(if (routing.optBoolean("enabled")) R.string.w2_diag_enabled else R.string.w2_diag_disabled)), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.w2_diag_routing_notice), style = MaterialTheme.typography.bodySmall)
            val profiles = snapshot.optJSONObject("connections")?.optJSONArray("connections")
            if (routing.optBoolean("enabled")) TextButton(onClick = { execute({ service.saveStepRouting(false, null) }) { routing = it.optJSONObject("stepRouting") ?: JSONObject() } }, enabled = !busy) { Text(stringResource(R.string.w2_diag_routing_disable)) }
            for (i in 0 until (profiles?.length() ?: 0)) profiles?.optJSONObject(i)?.takeIf { it.optBoolean("enabled") && it.optString("model").isNotBlank() }?.let { profile ->
                TextButton(onClick = { execute({ service.saveStepRouting(true, ModelTargetRef.exact(profile.getString("id"), profile.getString("model"))) }) { routing = it.optJSONObject("stepRouting") ?: JSONObject() } }, enabled = !busy) { Text(stringResource(R.string.w2_diag_select_model, profile.optString("name"), profile.optString("model"))) }
            }
            HorizontalDivider()
            Text(stringResource(R.string.w2_diag_openrouter_title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.w2_diag_openrouter_notice), style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(model, { model = it }, label = { Text(stringResource(R.string.w2_diag_exact_model_id)) }, singleLine = true, enabled = !busy && auth.optString("status") != "pending", modifier = Modifier.fillMaxWidth())
            Button(onClick = { execute({ service.openRouter().begin(model.trim()) }) { value -> auth = value; runCatching { uri.openUri(value.getString("authorizationUrl")) }.onFailure { service.openRouter().cancel(); auth = service.openRouter().snapshot(); error = context.getString(R.string.w2_diag_browser_failed) } } }, enabled = !busy && model.isNotBlank() && auth.optString("status") != "pending") { Text(stringResource(R.string.w2_diag_openrouter_open)) }
            if (auth.optString("status") == "pending") TextButton(onClick = { service.openRouter().cancel(); auth = service.openRouter().snapshot() }) { Text(stringResource(R.string.w2_diag_openrouter_cancel)) }
            if (auth.optString("status").isNotBlank()) Text(when (auth.optString("status")) { "pending" -> stringResource(R.string.w2_diag_auth_pending); "completed" -> stringResource(R.string.w2_diag_auth_completed); "cancelled" -> stringResource(R.string.w2_diag_auth_cancelled); "storage_unconfirmed" -> stringResource(R.string.w2_diag_auth_storage_unknown); else -> stringResource(R.string.w2_diag_auth_failed) }, style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun probeStatus(value: String) = stringResource(when (value) { "passed" -> R.string.w2_diag_status_passed; "failed" -> R.string.w2_diag_status_failed; "not_requested" -> R.string.w2_diag_status_not_requested; else -> R.string.w2_diag_status_unfinished })
