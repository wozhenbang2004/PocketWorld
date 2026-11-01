package dev.pocketengine.ui.memory

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.pocketengine.ui.PocketWorldController
import org.json.JSONArray
import org.json.JSONObject

/** Mounted only below the actual native source preview, not a model-supplied source picker. */
@Composable
internal fun SourceLearningPlanPicker(sourceRef: JSONObject, controller: PocketWorldController, openTask: (String) -> Unit) {
    var expanded by remember(sourceRef.toString()) { mutableStateOf(false) }
    var pending by remember(sourceRef.toString()) { mutableStateOf<Pair<String, String>?>(null) }
    var page by remember(sourceRef.toString()) { mutableStateOf<JSONObject?>(null) }
    var saved by remember(sourceRef.toString()) { mutableStateOf<JSONObject?>(null) }
    var error by remember(sourceRef.toString()) { mutableStateOf<String?>(null) }
    fun request(command: String, payload: JSONObject) {
        if (pending != null) return
        error = null; pending = command to controller.requestOwnerDomain("learning", command, payload)
    }
    val responses = controller.state.ownerResponses("learning")
    val action = pending
    LaunchedEffect(action, responses[action?.first]?.toString()) {
        val expected = action ?: return@LaunchedEffect
        val envelope = TaskMemoryEvidenceGuard.exactEnvelope(expected.second, responses[expected.first]) ?: return@LaunchedEffect
        pending = null
        if (!envelope.getBoolean("ok")) { error = envelope.optString("error", "来源计划未保存，请核对原任务。"); return@LaunchedEffect }
        try {
            val result = envelope.getJSONObject("result")
            if (expected.first == "listLearningJobs") {
                require(result.opt("formalMemorySaved") == false && result.optString("source") == "original_task_metadata_index")
                page = result
            } else {
                require(result.opt("formalMemorySaved") == false && result.opt("executionAuthorized") == false && result.getString("taskId").isNotEmpty())
                val inputs = result.getJSONObject("job").getJSONArray("inputs")
                require((0 until inputs.length()).any { TaskMemoryEvidenceGuard.sameSource(sourceRef, inputs.getJSONObject(it).getJSONObject("sourceRef")) })
                saved = result
            }
        } catch (_: Exception) { error = "未取得含这份来源版本的原计划回执；这里不会发送模型请求。" }
    }
    TextButton(onClick = {
        expanded = !expanded
        if (expanded && page == null) request("listLearningJobs", JSONObject().put("cursor", "").put("limit", 20))
    }, enabled = pending == null) { Text(if (expanded) "收起逐批计划" else "把此来源加入可续接的逐批学习计划") }
    if (expanded) Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("只保存所选版本的来源清单，不发送正文。可以先从其他原消息/任务/已选文件范围继续添加来源；首次开始后清单锁定，最多 20 份。", style = MaterialTheme.typography.bodySmall)
        saved?.let { result ->
            Text("来源已保存在原任务中；尚未提炼或采用为正式记忆。", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { openTask(result.getString("taskId")) }) { Text("查看原逐批任务并核对后开始") }
        }
        if (saved == null) {
            OutlinedButton(onClick = { request("createLearningJob", JSONObject().put("sourceRefs", JSONArray().put(JSONObject(sourceRef.toString())))) }, enabled = pending == null) { Text("新建待开始的逐批任务") }
            val jobs = page?.optJSONArray("jobs")
            if (jobs != null) for (i in 0 until jobs.length()) {
                val current = jobs.getJSONObject(i)
                if (!current.optBoolean("canAddSources")) continue
                val job = current.getJSONObject("job")
                TextButton(onClick = { request("addLearningJobSource", JSONObject().put("taskId", current.getString("taskId")).put("revision", job.getLong("revision")).put("sourceRef", JSONObject(sourceRef.toString()))) }, enabled = pending == null && job.getJSONArray("inputs").length() < 20) {
                    Text("加入任务 ${current.getString("taskId").takeLast(6)} · 已选 ${job.getJSONArray("inputs").length()} 份来源")
                }
            }
            if (page?.optBoolean("hasMore") == true) TextButton(onClick = { request("listLearningJobs", JSONObject().put("cursor", page!!.getString("nextCursor")).put("limit", 20)) }, enabled = pending == null) { Text("更早的逐批任务") }
        }
        if (pending != null) Text("正在核对原计划…", style = MaterialTheme.typography.bodySmall)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}
