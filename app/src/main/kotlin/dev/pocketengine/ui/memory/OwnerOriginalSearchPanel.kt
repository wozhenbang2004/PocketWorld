package dev.pocketengine.ui.memory

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.json.JSONObject

/** Native owner originals. Reads never register model references; explicit selection stages metadata only. */
@Composable
internal fun OwnerOriginalSearchPanel(responses: Map<String, JSONObject>, onRequest: (String, JSONObject) -> String, onOpenConversation: (String) -> Unit, onStageRange: ((JSONObject) -> String)? = null) {
    var expanded by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var submittedQuery by remember { mutableStateOf("") }
    var page by remember { mutableStateOf<JSONObject?>(null) }
    var selected by remember { mutableStateOf<JSONObject?>(null) }
    var exact by remember { mutableStateOf<JSONObject?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<Pair<String, String>?>(null) }
    fun search(cursor: String = "") {
        if (pending != null) return
        val term = if (cursor.isEmpty()) query else submittedQuery
        if (term.isBlank()) return
        submittedQuery = term; selected = null; exact = null; error = null
        pending = "searchOwnerOriginals" to onRequest("searchOwnerOriginals", JSONObject().put("query", term).put("cursor", cursor).put("limit", 20).put("maxChars", 16000))
    }
    fun read(hit: JSONObject, offset: Int = hit.optJSONObject("readRange")?.optInt("offset") ?: 0) {
        if (pending != null) return
        selected = JSONObject(hit.toString()); exact = null; error = null
        pending = "readOwnerOriginalSearchHit" to onRequest("readOwnerOriginalSearchHit", JSONObject().put("ownerLocator", JSONObject(hit.getJSONObject("ownerLocator").toString())).put("offset", offset).put("maxChars", 16000))
    }
    val waiting = pending
    LaunchedEffect(waiting, waiting?.let { responses[it.first]?.toString() }) {
        val action = waiting ?: return@LaunchedEffect
        val envelope = TaskMemoryEvidenceGuard.exactEnvelope(action.second, responses[action.first]) ?: return@LaunchedEffect
        pending = null
        if (!envelope.getBoolean("ok")) { if(action.first!="selectOwnerOriginalReference"){exact = null; selected = null; page = null}; error = envelope.optString("error", "原件未读到，请保留当前检索水位核对。"); return@LaunchedEffect }
        try {
            val result = envelope.getJSONObject("result")
            if(action.first=="selectOwnerOriginalReference"){
                require(result.opt("selectionValidated")==true&&result.opt("draftStaged")==true&&result.opt("bodyIncluded")==false&&result.opt("modelCalled")==false)
                notice="这段原话已加入原私聊草稿，尚未发送、未保存为记忆。关闭本页回原位置；发送时会再次核对来源。";return@LaunchedEffect
            }
            require(result.opt("ownerOnly") == true && result.opt("modelReferenceRegistered") == false && result.opt("executionAuthorized") == false && result.opt("modelCalled") == false)
            if (action.first == "searchOwnerOriginals") page = JSONObject(result.toString())
            else if (result.optString("status") != "current") { exact = null; selected = null; page = null; error = result.optString("reason", "来源已变或缺失；请重新搜索，未展示新正文。") }
            else {
                require(result.getJSONObject("ownerLocator").toString() == selected?.getJSONObject("ownerLocator")?.toString())
                exact = JSONObject(result.toString())
            }
        } catch (_: Exception) { exact = null; selected = null; page = null; error = "未取得这份原件版本的native回执；不展示替代正文。" }
    }
    TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起原文查找" else "不知道在哪个会话？查找保留原话") }
    if (expanded) Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("只在主人私人原文中逐页找文字，不调用模型、不扩张当前任务的模型检索范围。遗忘／停止派生不会隐藏你仍可查看的原话；删除的正文不返回。", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(query, { query = it.take(500) }, label = { Text("原话里的词、路径或标识") }, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = pending == null)
        Button(onClick = { search() }, enabled = pending == null && query.isNotBlank()) { Text("从头查找保留原文") }
        if (pending != null) Text("正在核对原件与本页读取预算…", style = MaterialTheme.typography.bodySmall)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        val current = page
        current?.optJSONObject("pageCoverage")?.let { coverage ->
            Text("本页扫描 ${coverage.optInt("scannedRows")} 条原件，实际取出 ${coverage.optLong("materializedOriginalRecordUtf8Bytes")} 字节；不是物理I/O或完整历史快照。原生ID续页不是时间顺序，正常新增／修改可能改变后续匹配。", style = MaterialTheme.typography.bodySmall)
        }
        val shown = exact
        if (shown != null) {
            Text(if (shown.optBoolean("importAssignedIdentity")) "旧原件 · 内部记录版本 ${shown.optLong("recordVersion")}，历史修订仍可能未知" else "${shown.optString("role")} · 实际原文修订 ${shown.optLong("recordVersion")}", style = MaterialTheme.typography.labelLarge)
            if (shown.optString("derivedUseStatus") == "retired_for_derived_context") Text("这份原话已停止用于记忆／上下文派生；主人仍可查看。不因此恢复派生资格。", style = MaterialTheme.typography.bodySmall)
            Text(if (shown.optBoolean("timeKnown")) "原记录时间字段：${shown.opt("at")}" else "原时间未知", style = MaterialTheme.typography.bodySmall)
            val range = shown.getJSONObject("readRange")
            Text("原件范围 ${range.getInt("offset")}＋${range.getInt("chars")} / ${range.getInt("totalChars")}（UTF-16）", style = MaterialTheme.typography.bodySmall)
            Text("原记录 ${shown.getString("recordId")} · 指纹 ${shown.getJSONObject("ownerLocator").getString("fingerprint")}", style = MaterialTheme.typography.bodySmall)
            shown.optJSONObject("originalByteReference")?.let { original -> Text("原文件SHA ${original.optString("sourceSha256")}\n原件字节 ${original.optLong("offset")}＋${original.optLong("length")}（UTF-8），不等于历史真实修订。", style = MaterialTheme.typography.bodySmall) }
            Text(shown.getString("body"), style = MaterialTheme.typography.bodyMedium)
            if(onStageRange!=null&&!shown.optBoolean("importAssignedIdentity")&&shown.optString("derivedUseStatus")!="retired_for_derived_context"){
                var rangeStart by remember(shown.toString()){mutableStateOf(range.getInt("offset").toString())}
                var rangeChars by remember(shown.toString()){mutableStateOf(minOf(4000,range.getInt("chars")).toString())}
                val start=rangeStart.toIntOrNull();val count=rangeChars.toIntOrNull()
                val inReadRange=start!=null&&count!=null&&count in 1..4000&&start>=range.getInt("offset")&&start.toLong()+count<=range.getInt("offset").toLong()+range.getInt("chars")
                Text("主动引用到当前私聊草稿：只带下面选定且本页实际读到的范围；不会把旧原话当新指令。",style=MaterialTheme.typography.bodySmall)
                OutlinedTextField(rangeStart,{rangeStart=it.take(10)},label={Text("UTF-16起点")},modifier=Modifier.fillMaxWidth(),singleLine=true,enabled=pending==null)
                OutlinedTextField(rangeChars,{rangeChars=it.take(4)},label={Text("字符数（最多4000）")},modifier=Modifier.fillMaxWidth(),singleLine=true,enabled=pending==null)
                OutlinedButton(onClick={
                    try{error=null;notice=null;pending="selectOwnerOriginalReference" to onStageRange(JSONObject().put("ownerLocator",JSONObject(shown.getJSONObject("ownerLocator").toString())).put("offset",start).put("chars",count))}
                    catch(failure:Exception){error=failure.message?:"未加入原草稿，请保留本页原文核对。"}
                },enabled=pending==null&&inReadRange){Text("把所选范围加入原私聊草稿（不发送）")}
            }
            TextButton(onClick = { read(shown, shown.getJSONObject("readRange").getInt("offset")) }, enabled = pending == null) { Text("重新核对这份原件与范围") }
            if (shown.optInt("nextOffset", -1) >= 0) TextButton(onClick = { read(shown, shown.getInt("nextOffset")) }, enabled = pending == null) { Text("继续这份同版本原件") }
            TextButton(onClick = { onOpenConversation(shown.getString("conversationId")) }, enabled = pending == null) { Text("回到这个原会话（最近窗口）") }
            TextButton(onClick = { selected = null; exact = null }) { Text("回到检索命中") }
        } else {
            val hits = current?.optJSONArray("entries")
            if (hits != null) for (i in 0 until hits.length()) {
                val hit = hits.getJSONObject(i)
                OutlinedButton(onClick = { read(hit) }, enabled = pending == null, modifier = Modifier.fillMaxWidth()) {
                    Text((if (hit.optBoolean("importAssignedIdentity")) "旧原件，来源未核实" else hit.optString("role")) + (if (hit.optString("derivedUseStatus") == "retired_for_derived_context") " · 已停止派生" else "") + "：" + hit.optString("body").take(160))
                }
            }
            if (current != null && hits?.length() == 0) Text(if (current.optBoolean("hasMore")) "这批未找到；还有未扫描原件，不能当全部历史无结果。" else "在这次有限原生ID水位内未找到；并不恢复旧裁剪历史。", style = MaterialTheme.typography.bodySmall)
            if (current?.optBoolean("hasMore") == true) TextButton(onClick = { search(current.getString("nextCursor")) }, enabled = pending == null) { Text("继续查下一批原件") }
            if ((current?.optJSONArray("gaps")?.length() ?: 0) > 0) Text("本页有已删除、来源不明或超预算原件，未用摘要／任务副本补齐。", style = MaterialTheme.typography.bodySmall)
        }
    }
}
