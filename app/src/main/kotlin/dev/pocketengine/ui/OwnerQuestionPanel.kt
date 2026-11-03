package dev.pocketengine.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

/** Uses the task card's existing sheet and input controls; the native wait is rechecked on send. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OwnerQuestionPanel(question: JSONObject, answer: (JSONObject, String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf(false) }
    val identity = question.optJSONObject("identity") ?: return
    if (question.optBoolean("answered")) {
        Text(if (question.optString("sourceKind") == "native_phone_owner_input") "回答已保留在原手机任务；是否继续以原任务状态为准。" else "回答已保留在原对话；是否继续以原任务状态为准。", fontSize = 12.sp, color = SecondaryInk)
    } else TextButton(onClick = { open = true }, enabled = !submitted, modifier = Modifier.testTag("owner_question_open")) { Text("回答这个普通问题") }
    if (open) ModalBottomSheet(onDismissRequest = { open = false }) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("回答原任务", style = MaterialTheme.typography.titleLarge)
            Text("回答只进入这一个原等待项，不是对敏感操作的批准。旧问题或到期通知只能回到原记录。", fontSize = 13.sp, color = SecondaryInk)
            OutlinedTextField(text, { if (it.length <= 4000) text = it }, label = { Text("你的回答") }, modifier = Modifier.fillMaxWidth().testTag("owner_question_answer"), minLines = 2, maxLines = 6)
            Button(onClick = { submitted = true; open = false; answer(JSONObject(identity.toString()), text) }, enabled = text.isNotBlank() && !submitted, modifier = Modifier.fillMaxWidth().testTag("owner_question_send")) { Text("发给原任务") }
            TextButton(onClick = { open = false }, modifier = Modifier.fillMaxWidth()) { Text("取消") }
        }
    }
}
