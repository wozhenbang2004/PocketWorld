package dev.pocketengine.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import java.util.Base64
import org.json.JSONObject

/** This modal keeps the underlying answer, list anchor and composer draft mounted. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AnswerSourcesSheet(anchor: JSONObject, read: (JSONObject, Int, (JSONObject?, String?) -> Unit) -> Unit, frame: (JSONObject, Int, (JSONObject?, String?) -> Unit) -> Unit, controller: PocketWorldController, back: () -> Unit) {
    val context = LocalContext.current
    var selected by remember(anchor.toString()) { mutableIntStateOf(-1) }
    var result by remember(anchor.toString()) { mutableStateOf<JSONObject?>(null) }
    var rawCitation by remember { mutableStateOf<JSONObject?>(null) }
    var picture by remember { mutableStateOf<String?>(null) }
    var structuredOpen by remember(selected) { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }; var error by remember { mutableStateOf<String?>(null) }
    var generation by remember { mutableIntStateOf(0) }; var alive by remember(anchor.toString()) { mutableStateOf(true) }
    DisposableEffect(anchor.toString()) { controller.enterOwnerDomain("taskMemory"); onDispose { alive = false; generation++; controller.leaveOwnerDomain("taskMemory") } }
    fun returnToList() { selected = -1; result = null; rawCitation = null; picture = null; generation++; loading = false; error = null }
    BackHandler { if (rawCitation != null) rawCitation = null else if (selected >= 0) returnToList() else back() }
    ModalBottomSheet(onDismissRequest = back, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.9f).navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = { if (rawCitation != null) rawCitation = null else if (selected >= 0) returnToList() else back() }) { Text(stringResource(if (rawCitation != null) R.string.w2_sources_back_source else if (selected >= 0) R.string.w2_sources_back_list else R.string.w2_sources_back_answer)) }
            Text(stringResource(R.string.w2_sources_title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.w2_sources_original_identity, anchor.optString("taskId"), anchor.optString("runId"), anchor.optString("answerId")), style = MaterialTheme.typography.labelSmall)
            Text(stringResource(R.string.w2_sources_notice), style = MaterialTheme.typography.bodySmall)
            if (anchor.optBoolean("coverageLimited")) Text(stringResource(R.string.w2_sources_coverage_limited), style = MaterialTheme.typography.bodySmall)
            if (rawCitation != null) {
                dev.pocketengine.ui.memory.TaskMemoryRawCitationPanel(anchor.getString("taskId"), anchor.getString("runId"), rawCitation!!, controller.state.ownerResponses("taskMemory"), { command, payload -> controller.requestOwnerDomain("taskMemory", command, payload) }, { task -> back(); controller.focusTask(task) }, { rawCitation = null }, controller.state.memoryRefreshRevision)
            } else if (selected < 0) {
                val entries = anchor.optJSONArray("entries")
                if (entries == null || entries.length() == 0) Text(stringResource(R.string.w2_sources_empty))
                for (i in 0 until (entries?.length() ?: 0)) {
                    val entry = entries!!.getJSONObject(i)
                    TextButton(onClick = {
                        selected = i; loading = true; error = null; val request = ++generation
                        read(anchor, i) { value, failure -> if (alive && request == generation) { result = value; error = failure; loading = false } }
                    }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.w2_sources_entry, i + 1, stringResource(when (entry.optString("type")) { "browser_source_retained" -> R.string.w2_sources_web_observation; "observation" -> R.string.w2_sources_phone_observation; else -> R.string.w2_sources_exact_citation }), entry.optLong("eventOrder"))) }
                }
            } else result?.let { value ->
                if (!value.optBoolean("available")) Text(stringResource(R.string.w2_sources_unavailable))
                else {
                    Text(value.optString("title", stringResource(R.string.w2_sources_original_range)), style = MaterialTheme.typography.titleMedium)
                    if (value.optString("url").isNotBlank()) Text(value.getString("url"), style = MaterialTheme.typography.bodySmall)
                    value.optJSONObject("sourceIdentity")?.let { source -> val identities = listOf("sourceId" to stringResource(R.string.w2_sources_identity_source), "observationId" to stringResource(R.string.w2_sources_identity_observation), "documentId" to stringResource(R.string.w2_sources_identity_document)).mapNotNull { (key, label) -> source.optString(key).takeIf(String::isNotBlank)?.let { "$label $it" } }; if (identities.isNotEmpty()) Text(identities.joinToString(" · "), style = MaterialTheme.typography.labelSmall) }
                    if (value.has("body")) SelectionContainer { Text(value.getString("body"), style = MaterialTheme.typography.bodyMedium) }
                    value.optJSONObject("structuredProjection")?.let { fields -> TextButton(onClick = { structuredOpen = !structuredOpen }) { Text(if (structuredOpen) stringResource(R.string.w2_sources_structured_collapse) else stringResource(R.string.w2_sources_structured_expand)) }; if (structuredOpen) SelectionContainer { Text(fields.toString(2), style = MaterialTheme.typography.bodySmall) } }
                    value.optJSONObject("range")?.let { range -> Text(stringResource(R.string.w2_sources_utf16_range, range.optInt("offset"), range.optInt("chars"), range.optInt("totalChars")), style = MaterialTheme.typography.labelSmall) }
                    val jumps = value.optJSONArray("jumps")
                    for (i in 0 until (jumps?.length() ?: 0)) {
                        val jump = jumps!!.getJSONObject(i)
                        TextButton(onClick = { rawCitation = JSONObject(jump.toString()) }) { Text(stringResource(R.string.w2_sources_read_original, i + 1, jump.optString("sourceId"))) }
                    }
                    val savedFrame = value.optJSONObject("frame")
                    if (savedFrame != null && picture == null) TextButton(onClick = { loading = true; val request = ++generation; frame(anchor, selected) { returned, failure -> if (alive && request == generation) { picture = returned?.optString("dataUrl")?.takeIf { returned.optBoolean("available") && it.isNotEmpty() }; error = failure ?: if (picture == null) context.getString(R.string.w2_sources_frame_unavailable) else null; loading = false } } }) { Text(stringResource(R.string.w2_sources_open_frame)) }
                    else if (savedFrame == null) Text(stringResource(R.string.w2_sources_frame_not_retained), style = MaterialTheme.typography.bodySmall)
                    picture?.let { encoded -> val bitmap = remember(encoded) { runCatching { val bytes = Base64.getDecoder().decode(encoded.substringAfter(",")); BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull() }; bitmap?.let { Image(it.asImageBitmap(), stringResource(R.string.w2_sources_frame_description), Modifier.fillMaxWidth()) } }
                }
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Spacer(Modifier.height(18.dp))
        }
    }
}
