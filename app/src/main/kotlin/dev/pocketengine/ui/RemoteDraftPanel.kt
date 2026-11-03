package dev.pocketengine.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pocketengine.features.remote.RemoteInputDelivery
import dev.pocketengine.features.remote.RemoteDraftClipboard
import java.util.UUID
import org.json.JSONObject

/** Durable local intent, not an automatic future job or a claim of provider consumption. */
@Composable
internal fun RemoteDraftPanel(snapshot: JSONObject, enabled: Boolean, send: (String, JSONObject) -> Unit) {
    RemoteDraftPanel(snapshot, enabled, clipboard = null, send = send)
}

/** Existing callers keep their no-clipboard ABI; only the explicit native owner
 * caller supplies a one-use reader. No ambient clipboard access is introduced. */
@Composable
internal fun RemoteDraftPanel(snapshot: JSONObject, enabled: Boolean, clipboard: ((String, Long, String) -> String)?, send: (String, JSONObject) -> Unit) {
    if (snapshot.optBoolean("teamRemoteWriter")) return
    val taskId=snapshot.optString("taskId"); val source=snapshot.optJSONObject("source")?:return
    if (source.optString("protocol") !in setOf("codex_app_server","claude_stream_json","pi_rpc_v1")) return
    val delivery=RemoteInputDelivery.describe(source)
    val currentTurn=delivery.getJSONObject("currentTurn")
    val nextTurn=delivery.getJSONObject("nextTurn")
    val pi=source.optString("protocol")=="pi_rpc_v1"
    val targetReady=!pi||delivery.optBoolean("protocolEvidenceReported")
    val drafts=snapshot.optJSONArray("inputDrafts").objects()
    var id by remember(taskId) { mutableStateOf(UUID.randomUUID().toString()) }
    var revision by remember(taskId) { mutableLongStateOf(0) }
    var text by remember(taskId) { mutableStateOf("") }
    var mode by remember(taskId) { mutableStateOf("next_turn") }
    var notice by remember(taskId) { mutableStateOf<String?>(null) }
    var clipboardReviewUsed by remember(taskId) { mutableStateOf<String?>(null) }
    val selected=drafts.find { it.optString("id")==id }
    LaunchedEffect(selected?.optLong("revision")) { if(selected!=null)revision=selected.optLong("revision") }
    val editable=selected==null||selected.optString("state") in setOf("draft","queued")
    Column(Modifier.fillMaxWidth().padding(vertical=12.dp), verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text("草稿与待发队列",style=MaterialTheme.typography.titleSmall)
        Text("草稿保存在这个任务。排队不会自动发送：当前执行结束、重连或重开App后，仍需核对原目标并在前台明确发送。未知回执只查原编号。",fontSize=14.sp)
        Text("草稿发送沿用原会话配置，不会借用其他继续表单的模型/档位选择。",fontSize=13.sp)
        Text(when {
            pi&&currentTurn.optBoolean("observedAvailable") -> "当前输入：可追加到这个无工具任务的原Pi队列；队列接收不证明模型已处理，不另开prompt或增加额度。"
            pi&&currentTurn.optBoolean("adapterMounted") -> "当前输入：Pi原队列输入已确认，但当前执行不允许追加；先保留本机草稿。"
            currentTurn.optBoolean("observedAvailable") -> "当前轮：可向本次准确Codex turn补充；接收回执不等于模型已经采用或任务完成。"
            currentTurn.optBoolean("adapterMounted") -> "当前轮：Codex原turn补充已挂接，但本次未观察到可发送的准确turn；先保留草稿。"
            pi -> "当前输入：未观察到可追加的原Pi执行。保存和排队本身不发送；既有操作请按原消息回执核对。"
            source.optString("protocol")=="claude_stream_json" -> "当前轮：本适配器未挂接Claude运行中补充。保存和排队本身不发送；既有操作请按原消息回执核对。"
            else -> "当前轮输入能力尚未由原job确认；草稿不是发送许可。"
        },fontSize=13.sp)
        if(pi) Text("Pi仅限已确认同session/thread的无工具对话；不开放工具，不代表已具备Pi编程能力。",fontSize=13.sp)
        if(!nextTurn.optBoolean("observedAvailable")) Text("当前尚未满足原会话下一轮条件；保存或排队只保留本机意图，未发送。",fontSize=13.sp)
        Text(if(delivery.optBoolean("protocolEvidenceReported")&&delivery.getJSONObject("interrupt").optString("mode")=="native_cleanup_only") "停止入口仅核对/清理原执行资源，不会撤销已发生的操作。" else "停止只是发给原执行器的请求；须等待原生确认，不代表进程已经停止，也不会回滚已发生的操作。",fontSize=13.sp)
        for (row in drafts.takeLast(16).asReversed()) TextButton(onClick={id=row.optString("id");revision=row.optLong("revision");text=row.optString("text");mode=row.optString("mode");notice=null}) {
            Text((when(row.optString("state")){"queued"->"待明确发送";"draft"->"本机草稿";"withdrawn"->"已撤回未发草稿";else->"原操作 · "+row.optString("deliveryStatus",row.optString("journalState","待查询"))})+" · "+row.optString("text").take(50),fontSize=14.sp)
        }
        if (selected!=null) Text("保存目标：${selected.optJSONObject("scope")?.optString("threadId")} · 原job ${selected.optJSONObject("scope")?.optString("jobId")} · v$revision",fontSize=13.sp)
        Row { RadioButton(mode=="next_turn",{if(editable&&selected==null)mode="next_turn"},enabled=editable&&selected==null); Text("下一轮：原会话核对终态后明确续接；Pi仅无工具对话",fontSize=14.sp,modifier=Modifier.padding(top=12.dp)) }
        if(source.optString("protocol")=="codex_app_server"||pi&&currentTurn.optBoolean("adapterMounted"))Row { RadioButton(mode=="steer",{if(editable&&selected==null)mode="steer"},enabled=editable&&selected==null); Text(if(pi) "当前Pi队列：原job/session/prompt不变才可追加" else "当前Codex turn补充：准确turn不变才可发送",fontSize=14.sp,modifier=Modifier.padding(top=12.dp)) }
        OutlinedTextField(text,{if(it.length<=12000){text=it;notice=null}},enabled=editable&&enabled,label={Text("给这个原会话的要求")},minLines=2,maxLines=6,modifier=Modifier.fillMaxWidth())
        if(clipboard!=null&&editable) {
            val readId=id;val readRevision=revision;val readMode=mode;val readReview=snapshot.optString("reviewId")
            val cursorCurrent=if(selected==null)revision==0L else revision==selected.optLong("revision")&&mode==selected.optString("mode")
            key(readReview,readId,readRevision,readMode) {
                OutlinedButton(onClick={
                    if(id!=readId||revision!=readRevision||mode!=readMode||clipboardReviewUsed==readReview){notice=RemoteDraftClipboard.Reason.TARGET.message;return@OutlinedButton}
                    val before=text;clipboardReviewUsed=readReview
                    try {
                        val imported=clipboard(readId,readRevision,readMode)
                        if(id!=readId||revision!=readRevision||mode!=readMode||text!=before){notice=RemoteDraftClipboard.Reason.TARGET.message;return@OutlinedButton}
                        text=RemoteDraftClipboard.append(before,imported);notice="已追加到本机编辑器；尚未保存或发送，请先核对文字。"
                    } catch(safe: RemoteDraftClipboard.Failure){notice=safe.reason.message}
                    catch(_: Exception){notice=RemoteDraftClipboard.Reason.UNAVAILABLE.message}
                },enabled=enabled&&cursorCurrent&&delivery.optBoolean("protocolEvidenceReported")&&readReview.isNotEmpty()&&clipboardReviewUsed!=readReview){Text("追加本机剪贴板一次（仅填草稿）")}
            }
            Text(if(clipboardReviewUsed==readReview)"本次原审阅已使用一次读取；再次读取需先查询原任务。" else "仅明确点击时读取本机纯文本；不继承剪贴板同步权限，不覆盖原编辑，也不自动保存或发送。",fontSize=13.sp)
        }
        if(editable) Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick={send("saveDraft",JSONObject().put("draftId",id).put("draftRevision",revision).put("mode",mode).put("text",text).put("queued",false))},enabled=enabled&&targetReady&&text.isNotBlank()){Text("保存草稿")}
            Button(onClick={send("saveDraft",JSONObject().put("draftId",id).put("draftRevision",revision).put("mode",mode).put("text",text).put("queued",true))},enabled=enabled&&targetReady&&text.isNotBlank()){Text("保存并排队")}
        }
        if(selected?.optString("state")=="queued") {
            val exactId=selected.getString("id");val exactRevision=selected.getLong("revision");val exactMode=selected.getString("mode");val exactText=selected.getString("text")
            val matchesSaved=id==exactId&&revision==exactRevision&&mode==exactMode&&text==exactText
            val canSend=if(mode=="next_turn")snapshot.optBoolean("canContinue")&&(!pi||nextTurn.optBoolean("observedAvailable"))else currentTurn.optBoolean("observedAvailable")
            if(!matchesSaved) Text("当前编辑尚未保存；请先保存并排队这个版本，再明确发送。",fontSize=13.sp)
            key(exactId,exactRevision,exactMode) {
                Button(onClick={
                    if(id!=exactId||revision!=exactRevision||mode!=exactMode||text!=exactText){notice="草稿选择或内容已变化，未发送旧确认。";return@Button}
                    notice=null;send("sendDraft",JSONObject().put("draftId",exactId).put("draftRevision",exactRevision))
                },enabled=enabled&&canSend&&matchesSaved){Text(if(mode=="steer"&&pi)"明确追加这条到原Pi队列" else if(mode=="steer")"明确发送这条到原turn" else "明确发送这条并继续原会话")}
            }
            Text("不是自动排程；目标/电脑代次或会话变更将拒绝发送。新要求会消耗实际远端执行与计费，不代表结果成功。",fontSize=13.sp)
        }
        if(selected!=null&&editable)TextButton(onClick={send("withdrawDraft",JSONObject().put("draftId",id).put("draftRevision",revision))},enabled=enabled){Text("撤回这条尚未发送的草稿")}
        notice?.let { Text(it,fontSize=13.sp) }
        TextButton(onClick={id=UUID.randomUUID().toString();revision=0;text="";mode="next_turn";notice=null},enabled=enabled){Text("新建另一条（不自动转发旧草稿）")}
    }
}
