package dev.pocketengine.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.json.JSONObject

/** A native owner setting for the actual selected conversation, never a model tool argument. */
@Composable
internal fun ConversationHistoryScopeControl(conversation: String, state: PocketUiState,
    controller: PocketWorldController) {
    var expanded by remember(conversation) { mutableStateOf(false) }
    TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起历史召回范围" else "本会话的历史召回范围") }
    if (!expanded) return
    var request by remember(conversation) { mutableStateOf<String?>(null) }
    var command by remember(conversation) { mutableStateOf("getConversationHistoryReadScope") }
    var current by remember(conversation) { mutableStateOf<JSONObject?>(null) }
    var error by remember(conversation) { mutableStateOf<String?>(null) }
    var confirm by remember(conversation) { mutableStateOf(false) }
    fun load() {
        if (request != null) return
        command = "getConversationHistoryReadScope"
        request = controller.requestOwnerDomain("taskMemory", command, JSONObject().put("conversationId", conversation))
    }
    fun set(mode: String, ownerConfirmed: Boolean) {
        val selected = current ?: return
        if (request != null) return
        command = "setConversationHistoryReadScope"
        error = null
        request = controller.requestOwnerDomain("taskMemory", command, JSONObject().put("conversationId", conversation)
            .put("revision", selected.getLong("revision")).put("mode", mode).put("ownerConfirmed", ownerConfirmed))
    }
    // NativeMemory owns the original taskMemory page lifetime, including sibling read sheets.
    LaunchedEffect(conversation) { load() }
    val response = state.ownerResponses("taskMemory")[command]
    LaunchedEffect(request, response?.toString()) {
        val pending = request ?: return@LaunchedEffect
        if (response?.optString("requestId") != pending) return@LaunchedEffect
        request = null
        if (response.opt("ok") != true) { error = response.optString("error", "原历史范围暂不可读"); return@LaunchedEffect }
        try {
            val value = response.getJSONObject("result")
            require(value.getString("conversationId") == conversation && value.getString("ownerId") == "owner"
                && value.getString("spaceId") == "private" && value.opt("executionAuthorized") == false
                && value.getString("mode") in setOf("CURRENT", "OWNER_PRIVATE") && value.getLong("revision") >= 0)
            current = JSONObject(value.toString())
        } catch (_: Exception) { current = null; error = "原会话身份或范围版本不可核对，未扩大读取范围" }
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val ownerPrivate = current?.optString("mode") == "OWNER_PRIVATE"
        Text(if (ownerPrivate) "已开启：主人私人旧会话只读召回" else "默认：只读取当前会话的保留原文与任务")
        Text("此设置只属于当前选中的会话。不会恢复旧任务控制或批准，不会自动发送旧正文；模型仍须在原工具预算内先发现位置，再按准确版本与范围读取。",
            style = MaterialTheme.typography.bodySmall)
        if (request != null) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (ownerPrivate) OutlinedButton(onClick = { set("CURRENT", false) }, enabled = request == null && current != null) { Text("关闭跨会话召回") }
            else OutlinedButton(onClick = { confirm = true }, enabled = request == null && current?.optBoolean("canEnableOwnerPrivate") == true) { Text("开启私人旧会话只读范围") }
            TextButton(onClick = { load() }, enabled = request == null) { Text("重新读取") }
        }
        Text("关闭或修改范围立即使旧在途授权失效；正在运行的任务可能要求重新开始一轮。原审计记录不会被改写。",
            style = MaterialTheme.typography.bodySmall)
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("允许这个会话召回私人旧原文？") },
        text = { Text("仅允许这个当前会话在你自己的私人原始记录内检索、选取并读取准确范围。账号/密钥或已撤回来源不因此变成可读；这不是执行旧指令的授权。你可随时关闭，在途旧授权随即失效。") },
        confirmButton = { TextButton(onClick = { confirm = false; set("OWNER_PRIVATE", true) }) { Text("明确开启只读范围") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("保持当前会话") } })
}
