package dev.pocketengine.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

internal typealias OwnerQuestionSubmit = (JSONObject, String, (JSONObject?, String?) -> Unit) -> Unit

/** Uses the task card's existing sheet and input controls; the native wait is rechecked on send. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OwnerQuestionPanel(question: JSONObject, answer: OwnerQuestionSubmit, choose: OwnerQuestionSubmit) {
    val identity = question.optJSONObject("identity") ?: return
    val waitKey = identity.toString()
    var open by rememberSaveable(waitKey) { mutableStateOf(false) }
    var text by rememberSaveable(waitKey) { mutableStateOf("") }
    var submitted by rememberSaveable(waitKey) { mutableStateOf(false) }
    var receiptMessage by rememberSaveable(waitKey) { mutableStateOf<String?>(null) }
    val receive: (JSONObject?, String?) -> Unit = { result, failure ->
        if (result?.optBoolean("answerSaved") == true) {
            open = false
            receiptMessage = "回答已保留在原任务；后续执行以原任务状态为准。"
        } else receiptMessage = if (result?.optBoolean("expired") == true)
            "这个原问题已过期。请返回原任务查看当前状态。"
        else failure ?: "回答是否保留尚未确认。请核对原任务记录；这里不会自动重发。"
    }
    if (question.optBoolean("answered")) {
        Text(if (question.optString("sourceKind") == "native_phone_owner_input") "回答已保留在原手机任务；是否继续以原任务状态为准。" else "回答已保留在原对话；是否继续以原任务状态为准。", fontSize = 12.sp, color = SecondaryInk)
    } else TextButton(onClick = { open = true }, enabled = !submitted, modifier = Modifier.testTag("owner_question_open")) { Text("回答这个普通问题") }
    if (submitted) Text(receiptMessage ?: "正在核对原回答回执；如页面曾被重建，请刷新原任务确认结果。不会自动重发。", fontSize = 12.sp, color = SecondaryInk)
    if (open) ModalBottomSheet(onDismissRequest = { open = false }) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("回答原任务", style = MaterialTheme.typography.titleLarge)
            Text(question.optString("question"), style = MaterialTheme.typography.bodyLarge)
            Text("回答只进入这一个原等待项，不是对敏感操作的批准。旧问题或到期通知只能回到原记录。", fontSize = 13.sp, color = SecondaryInk)
            val options = question.optJSONArray("options")
            for (index in 0 until (options?.length() ?: 0)) {
                val option = options?.optJSONObject(index) ?: continue
                TextButton(onClick = { submitted = true; choose(JSONObject(identity.toString()), option.getString("id"), receive) },
                    enabled = !submitted, modifier = Modifier.fillMaxWidth().testTag("owner_question_option_" + option.optString("id"))) {
                    Text(option.optString("label"), modifier = Modifier.fillMaxWidth())
                }
            }
            OutlinedTextField(text, { if (it.length <= 4000) text = it }, enabled = !submitted, label = { Text("你的回答") }, modifier = Modifier.fillMaxWidth().testTag("owner_question_answer"), minLines = 2, maxLines = 6)
            Button(onClick = { submitted = true; answer(JSONObject(identity.toString()), text, receive) }, enabled = text.isNotBlank() && !submitted, modifier = Modifier.fillMaxWidth().testTag("owner_question_send")) { Text("发给原任务") }
            if (submitted) Text(receiptMessage ?: "正在提交到原等待项…", fontSize = 13.sp, color = SecondaryInk)
            TextButton(onClick = { open = false }, modifier = Modifier.fillMaxWidth()) { Text("稍后回答") }
        }
    }
}
