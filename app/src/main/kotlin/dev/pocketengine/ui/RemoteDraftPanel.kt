package dev.pocketengine.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.UUID
import org.json.JSONObject

/** Durable local intent, not an automatic future job or a claim of provider consumption. */
@Composable
internal fun RemoteDraftPanel(snapshot: JSONObject, enabled: Boolean, send: (String, JSONObject) -> Unit) {
    if (snapshot.optBoolean("teamRemoteWriter")) return
    val taskId=snapshot.optString("taskId"); val source=snapshot.optJSONObject("source")?:return
    if (source.optString("protocol") !in setOf("codex_app_server","claude_stream_json")) return
    val drafts=snapshot.optJSONArray("inputDrafts").objects()
    var id by remember(taskId) { mutableStateOf(UUID.randomUUID().toString()) }
    var revision by remember(taskId) { mutableLongStateOf(0) }
    var text by remember(taskId) { mutableStateOf("") }
    var mode by remember(taskId) { mutableStateOf("next_turn") }
    val selected=drafts.find { it.optString("id")==id }
    LaunchedEffect(selected?.optLong("revision")) { if(selected!=null)revision=selected.optLong("revision") }
    val editable=selected==null||selected.optString("state") in setOf("draft","queued")
    Column(Modifier.fillMaxWidth().padding(vertical=12.dp), verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text("草稿与待发队列",style=MaterialTheme.typography.titleSmall)
        Text("草稿保存在这个任务。排队不会自动发送：当前执行结束、重连或重开App后，仍需核对原目标并在前台明确发送。未知回执只查原编号。",fontSize=14.sp)
        for (row in drafts.takeLast(16).asReversed()) TextButton(onClick={id=row.optString("id");revision=row.optLong("revision");text=row.optString("text");mode=row.optString("mode")}) {
            Text((when(row.optString("state")){"queued"->"待明确发送";"draft"->"本机草稿";"withdrawn"->"已撤回未发草稿";else->"原操作 · "+row.optString("deliveryStatus",row.optString("journalState","待查询"))})+" · "+row.optString("text").take(50),fontSize=14.sp)
        }
        if (selected!=null) Text("保存目标：${selected.optJSONObject("scope")?.optString("threadId")} · 原job ${selected.optJSONObject("scope")?.optString("jobId")} · v$revision",fontSize=13.sp)
        Row { RadioButton(mode=="next_turn",{if(editable&&selected==null)mode="next_turn"},enabled=editable&&selected==null); Text("下一轮：Codex / Claude均需终态后明确续接",fontSize=14.sp,modifier=Modifier.padding(top=12.dp)) }
        if(source.optString("protocol")=="codex_app_server")Row { RadioButton(mode=="steer",{if(editable&&selected==null)mode="steer"},enabled=editable&&selected==null); Text("当前Codex turn补充：准确turn不变才可发送",fontSize=14.sp,modifier=Modifier.padding(top=12.dp)) }
        OutlinedTextField(text,{if(it.length<=12000)text=it},enabled=editable&&enabled,label={Text("给这个原会话的要求")},minLines=2,maxLines=6,modifier=Modifier.fillMaxWidth())
        if(editable) Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick={send("saveDraft",JSONObject().put("draftId",id).put("draftRevision",revision).put("mode",mode).put("text",text).put("queued",false))},enabled=enabled&&text.isNotBlank()){Text("保存草稿")}
            Button(onClick={send("saveDraft",JSONObject().put("draftId",id).put("draftRevision",revision).put("mode",mode).put("text",text).put("queued",true))},enabled=enabled&&text.isNotBlank()){Text("保存并排队")}
        }
        if(selected?.optString("state")=="queued") {
            val canSend=if(mode=="next_turn")snapshot.optBoolean("canContinue")else source.optJSONObject("controls")?.optBoolean("inputSteering")==true&&source.optString("executorStatus") in setOf("running","awaiting_approval")
            Button(onClick={send("sendDraft",JSONObject().put("draftId",id).put("draftRevision",revision))},enabled=enabled&&canSend){Text(if(mode=="steer")"明确发送这条到原turn" else "明确发送这条并继续原会话")}
            Text("不是自动排程；目标/电脑代次或会话变更将拒绝发送。新要求会消耗实际远端执行与计费，不代表结果成功。",fontSize=13.sp)
        }
        if(selected!=null&&editable)TextButton(onClick={send("withdrawDraft",JSONObject().put("draftId",id).put("draftRevision",revision))},enabled=enabled){Text("撤回这条尚未发送的草稿")}
        TextButton(onClick={id=UUID.randomUUID().toString();revision=0;text="";mode="next_turn"},enabled=enabled){Text("新建另一条（不自动转发旧草稿）")}
    }
}
