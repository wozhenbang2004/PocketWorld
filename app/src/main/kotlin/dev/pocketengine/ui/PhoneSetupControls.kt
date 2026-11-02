package dev.pocketengine.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.pocketengine.R
import org.json.JSONObject

/** Low-frequency owner controls, embedded below the existing primary setup flow. */
@Composable
internal fun PhoneSetupControls(snapshot: JSONObject, diagnostic: JSONObject?, apps: JSONObject? = null, onCommand: (String, JSONObject) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    TextButton(onClick = { expanded = !expanded }) { Text(stringResource(if (expanded) R.string.phone_setup_collapse else R.string.phone_setup_expand)) }
    if (!expanded) return
    val context = LocalContext.current
    val recovery = snapshot.optJSONObject("recovery") ?: JSONObject()
    val access = snapshot.optJSONObject("access") ?: JSONObject()
    var packageName by remember { mutableStateOf("") }
    var userId by remember { mutableStateOf("0") }
    var automate by remember { mutableStateOf(false) }
    var share by remember { mutableStateOf(false) }
    var picker by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.phone_setup_recovery_title), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.phone_setup_recovery_detail), style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.phone_setup_recovery_allow), Modifier.weight(1f).padding(end = 12.dp))
            Switch(checked = recovery.optBoolean("consent"), onCheckedChange = { onCommand("recovery.consent", JSONObject().put("enabled", it).put("expectedRevision", recovery.optLong("revision"))) })
        }
        OutlinedButton(onClick = { onCommand("recovery.restore", JSONObject()) }, enabled = recovery.optBoolean("consent")) { Text(stringResource(R.string.phone_setup_recovery_restore)) }
        Text(stringResource(if (recovery.optBoolean("secureSettingsGranted")) R.string.phone_setup_recovery_granted else R.string.phone_setup_recovery_missing), style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        Text(stringResource(R.string.phone_setup_access_title), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.phone_setup_access_detail), style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { picker = !picker; if (picker) onCommand("apps.list", JSONObject()) }) { Text(stringResource(R.string.phone_setup_access_choose)) }
        if (picker && apps != null) {
            val rows = apps.optJSONArray("apps")
            if (rows != null) Column(Modifier.fillMaxWidth().heightIn(max = 240.dp)) {
                androidx.compose.foundation.lazy.LazyColumn { items(rows.length()) { i -> val app = rows.optJSONObject(i); if (app != null) TextButton(onClick = { packageName = app.optString("packageName"); userId = app.optInt("userId").toString(); automate = true; share = true; val saved = access.optJSONArray("rules"); if (saved != null) for (n in 0 until saved.length()) { val rule = saved.optJSONObject(n) ?: continue; if (rule.optString("packageName") == packageName && rule.optInt("userId") == app.optInt("userId")) { automate = rule.optBoolean("automation"); share = rule.optBoolean("ownerShare") } }; picker = false }) { Text(stringResource(R.string.phone_setup_app_identity, app.optString("label"), app.optInt("userId"))) } } }
            }
        }
        OutlinedTextField(packageName, { packageName = it }, label = { Text(stringResource(R.string.phone_setup_package_label)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(userId, { userId = it.filter(Char::isDigit).take(5) }, label = { Text(stringResource(R.string.phone_setup_user_label)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(stringResource(R.string.phone_setup_automation_allow), Modifier.weight(1f).padding(end = 12.dp)); Switch(automate, { automate = it }) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(stringResource(R.string.phone_setup_share_allow), Modifier.weight(1f).padding(end = 12.dp)); Switch(share, { share = it }) }
        OutlinedButton(onClick = { onCommand("access.set", JSONObject().put("packageName", packageName).put("userId", userId.toInt()).put("automation", automate).put("ownerShare", share).put("expectedRevision", access.optLong("revision"))) }, enabled = packageName.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")) && (userId.toIntOrNull() ?: -1) in 0..99999 && access.optBoolean("available")) { Text(stringResource(R.string.phone_setup_access_save)) }
        val rules = access.optJSONArray("rules")
        if (rules != null) for (i in 0 until rules.length()) { val rule = rules.optJSONObject(i) ?: continue; Text(stringResource(R.string.phone_setup_rule_summary, rule.optString("packageName"), rule.optInt("userId"), stringResource(if (rule.optBoolean("automation")) R.string.phone_setup_allowed else R.string.phone_setup_disabled), stringResource(if (rule.optBoolean("ownerShare")) R.string.phone_setup_allowed else R.string.phone_setup_disabled)), style = MaterialTheme.typography.bodySmall) }
        HorizontalDivider()
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(stringResource(R.string.phone_setup_audio_allow), Modifier.weight(1f).padding(end = 12.dp)); Switch(snapshot.optBoolean("audioSilenceEnabled"), { onCommand("audio.policy", JSONObject().put("enabled", it)) }) }
        Text(stringResource(R.string.phone_setup_audio_detail), style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { onCommand("audio.restore", JSONObject()) }) { Text(stringResource(R.string.phone_setup_audio_restore)) }
        if (snapshot.optBoolean("clipboardRecoveryPending")) OutlinedButton(onClick = { onCommand("clipboard.restore", JSONObject()) }) { Text(stringResource(R.string.phone_setup_clipboard_restore)) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(stringResource(R.string.phone_setup_clipboard_allow), Modifier.weight(1f).padding(end = 12.dp)); Switch(snapshot.optBoolean("clipboardCompatibilityEnabled"), { onCommand("clipboard.policy", JSONObject().put("enabled", it)) }) }
        Text(stringResource(R.string.phone_setup_clipboard_detail), style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        OutlinedButton(onClick = { onCommand("diagnostics.preview", JSONObject()) }) { Text(stringResource(R.string.phone_setup_diagnostics_preview)) }
        if (diagnostic != null) {
            val text = diagnostic.toString(2)
            Text(text, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.phone_setup_diagnostics_clip_label), text)) }) { Text(stringResource(R.string.phone_setup_diagnostics_copy)) }
                TextButton(onClick = { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), context.getString(R.string.phone_setup_diagnostics_share_title)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) { Text(stringResource(R.string.phone_setup_diagnostics_share)) }
            }
        }
    }
}
