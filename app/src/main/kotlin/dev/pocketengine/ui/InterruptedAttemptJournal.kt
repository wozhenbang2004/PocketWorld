package dev.pocketengine.ui

import dev.pocketengine.R
import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pocketengine.features.conversation.InterruptedAttempts
import org.json.JSONArray
import org.json.JSONObject

/** Owner-only audit reader. Opening, paging and copying cannot call a model or resume a task. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun InterruptedAttemptJournal(taskId: String, initial: List<InterruptedAttempts.Attempt>,
    readPage: ((String, (JSONObject?, String?) -> Unit) -> Unit)? = null) {
    val context = LocalContext.current
    var open by remember(taskId) { mutableStateOf(false) }
    var attempts by remember(taskId) { mutableStateOf(initial) }
    var retained by remember(taskId) { mutableStateOf(JSONArray()) }
    var cursor by remember(taskId) { mutableStateOf("") }
    var loaded by remember(taskId) { mutableStateOf(false) }
    var more by remember(taskId) { mutableStateOf(false) }
    var partial by remember(taskId) { mutableStateOf(false) }
    var reading by remember(taskId) { mutableStateOf(false) }
    var failure by remember(taskId) { mutableStateOf<String?>(null) }
    val mounted = remember(taskId) { booleanArrayOf(true) }
    DisposableEffect(taskId) { onDispose { mounted[0] = false } }
    fun load(next: String) {
        if (readPage == null || reading) return
        reading = true; failure = null
        readPage(next) { page, error ->
            if (!mounted[0]) return@readPage
            reading = false
            if (error != null || page == null) { failure = error ?: context.getString(R.string.w8_cv_037); return@readPage }
            try {
                require(page.getString("taskId") == taskId)
                val incoming = InterruptedAttempts.eventsFromAuditPage(page)
                val combined = JSONArray()
                for (i in 0 until incoming.length()) if (incoming.getJSONObject(i).optString("type") == "model_attempt_interrupted") combined.put(incoming.getJSONObject(i))
                val resetting = next.isEmpty() || combined.length() + retained.length() > 200
                if (!resetting) for (i in 0 until retained.length()) combined.put(retained.getJSONObject(i))
                retained = combined; cursor = page.optString("nextCursor"); more = page.optBoolean("hasMore")
                partial = partial || page.optBoolean("readLimited") || resetting && next.isNotEmpty()
                attempts = InterruptedAttempts.fromEvents(combined, more || partial); loaded = true
            } catch (_: Exception) { failure = context.getString(R.string.w8_cv_038) }
        }
    }
    if (initial.isNotEmpty() || readPage != null) TextButton(onClick = { open = true; if (!loaded) load("") }, modifier = Modifier.testTag("conversation_interrupted_open_" + taskId)) {
        Text(if (initial.isEmpty()) stringResource(R.string.w8_cv_039) else stringResource(R.string.w8_cv_interrupted_count, initial.size), fontSize = 13.sp)
    }
    if (open) ModalBottomSheet(onDismissRequest = { open = false }) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 22.dp).verticalScroll(rememberScrollState())) {
            Text(stringResource(R.string.w8_cv_040), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.w8_cv_041), fontSize = 13.sp, modifier = Modifier.padding(vertical = 12.dp))
            attempts.forEachIndexed { index, attempt ->
                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                Text(stringResource(R.string.w8_cv_fragment_count, index + 1, attempt.retainedChars), style = MaterialTheme.typography.titleSmall)
                if (attempt.runId.isEmpty()) Text(stringResource(R.string.w8_cv_042), fontSize = 12.sp)
                if (!attempt.contiguous || attempt.conflicting) Text(stringResource(R.string.w8_cv_043), color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                SelectionContainer { Text(attempt.text, fontSize = 14.sp, lineHeight = 22.sp, modifier = Modifier.padding(vertical = 12.dp).testTag("conversation_interrupted_text")) }
            }
            if (attempts.isEmpty() && !reading) Text(stringResource(R.string.w8_cv_044), fontSize = 13.sp)
            if (partial) Text(stringResource(R.string.w8_cv_045), fontSize = 12.sp)
            failure?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
            if (reading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 12.dp))
            if (readPage != null && (more || failure != null)) TextButton(onClick = { load(if (loaded) cursor else "") }, enabled = !reading) { Text(if (failure != null) stringResource(R.string.w8_cv_046) else stringResource(R.string.w8_cv_047)) }
            TextButton(onClick = { open = false }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.w8_cv_048)) }
        }
    }
}
