package dev.pocketengine.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.pocketengine.R
import dev.pocketengine.features.assistant.OwnerQuestionInputs
import org.json.JSONObject

internal fun hasOrdinaryOwnerQuestion(task: JSONObject): Boolean = try {
    task.optJSONObject("ownerQuestionView") != null || OwnerQuestionInputs.pageQuestion(task) != null
} catch (_: Exception) { false }

/** Original native ordinary question. No feature/model route, approval or takeover UI. */
@Composable
internal fun OwnerQuestionPanel(task: JSONObject, busy: Boolean, error: String?, result: JSONObject?, onAnswer: (JSONObject, String) -> Unit) {
    val view = remember(task.toString()) {
        try { task.optJSONObject("ownerQuestionView") ?: OwnerQuestionInputs.pageQuestion(task) } catch (_: Exception) { null }
    } ?: return
    val identity = view.optJSONObject("identity") ?: return
    val taskId = identity.optString("taskId")
    val requestId = identity.optString("requestId")
    var draft by rememberSaveable(taskId, requestId) { mutableStateOf("") }
    var accepted by rememberSaveable(taskId, requestId) { mutableStateOf(false) }
    LaunchedEffect(result?.toString(), taskId, requestId) {
        if (result?.optBoolean("accepted") == true && result.optString("taskId") == taskId && result.optString("requestId") == requestId) {
            accepted = true
            draft = ""
        }
    }
    Column(Modifier.fillMaxWidth().padding(top = 18.dp).testTag("owner-question"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.w8_cv_question_title), style = MaterialTheme.typography.titleSmall)
        SelectionContainer { Text(view.optString("question"), style = MaterialTheme.typography.bodyMedium) }
        if (accepted || view.optBoolean("answered")) {
            Text(stringResource(R.string.w8_cv_question_saved), style = MaterialTheme.typography.bodySmall)
        } else {
            Text(stringResource(R.string.w8_cv_question_note), style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(draft, { if (it.length <= 4000) draft = it }, label = { Text(stringResource(R.string.w8_cv_question_answer)) },
                minLines = 2, maxLines = 6, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("owner-question-answer"))
            Button(onClick = { onAnswer(JSONObject(identity.toString()), draft) }, enabled = draft.isNotBlank() && !busy,
                modifier = Modifier.testTag("owner-question-submit")) { Text(stringResource(R.string.w8_cv_question_submit)) }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
