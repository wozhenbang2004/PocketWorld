package dev.pocketengine.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject

/** Same original job/owner page. Normal answers are neither tool approvals nor a new chat turn. */
@Composable internal fun RemoteQuestionPanel(snapshot: JSONObject, busy: Boolean, answer: (String, String, JSONObject) -> Unit) {
    val source=snapshot.optJSONObject("source")?:JSONObject()
    source.optJSONArray("pendingQuestions").objects().forEach { request ->
        val requestId=request.optString("requestId")
        val requestHash=request.optString("requestHash")
        var choices by remember(snapshot.optString("taskId"),source.optString("jobOperationId"),requestId,requestHash) { mutableStateOf<Map<String,List<String>>>(emptyMap()) }
        var custom by remember(snapshot.optString("taskId"),source.optString("jobOperationId"),requestId,requestHash) { mutableStateOf<Map<String,String>>(emptyMap()) }
        val questions=request.optJSONArray("questions").objects()
        val old=snapshot.optJSONObject("task")?.optJSONArray("operations").objects().lastOrNull { op->
            val body=op.optJSONObject("arguments")?.optJSONObject("request")
            op.optString("tool")=="remote_question_answer"&&body!=null&&body.optString("requestId")==requestId&&body.optString("requestHash")==requestHash&&!(op.optString("state")=="remote_question_rejected"&&op.optJSONObject("receipt")?.optBoolean("notDispatchedConfirmed")==true)
        }
        val canAnswer=snapshot.optBoolean("canAnswerQuestions")&&request.optBoolean("canAnswer")&&!busy&&old==null
        HorizontalDivider()
        Text(if(snapshot.optString("questionAnswerScope")=="current_team_member")"当前团队成员需要你回答"else"Agent 需要你回答",fontWeight=FontWeight.SemiBold,fontSize=16.sp)
        Text("这是当前原问题的正常回答，不会批准命令、文件修改或开启新任务。",fontSize=12.sp,color=SecondaryInk)
        if(request.optBoolean("previewOmitted")) Text("选项预览未显示；只展示原问题和选项文字，不运行HTML。",fontSize=11.sp,color=SecondaryInk)
        questions.forEach { q ->
            val id=q.optString("id")
            Text(q.optString("header"),fontWeight=FontWeight.Medium,fontSize=12.sp)
            SelectionContainer { Text(q.optString("text"),fontSize=14.sp) }
            if(q.optBoolean("requiresPrivateInput")) Text("秘密输入不在此收集或保存；请停止后在可信电脑处理。",fontSize=12.sp,color=MaterialTheme.colorScheme.error)
            else {
                q.optJSONArray("options").objects().forEach { option ->
                    val label=option.optString("label")
                    val selected=choices[id].orEmpty().contains(label)&&custom[id].orEmpty().isBlank()
                    fun select() { val existing=choices[id].orEmpty();choices=choices+(id to if(q.optBoolean("multiSelect")) { if(label in existing)existing-label else existing+label } else listOf(label));custom=custom+(id to "") }
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        if(q.optBoolean("multiSelect")) Checkbox(selected,{select()},enabled=canAnswer)
                        else RadioButton(selected,{select()},enabled=canAnswer)
                        Column { Text(label,fontSize=13.sp);option.optString("description").takeIf{it.isNotBlank()}?.let{Text(it,fontSize=11.sp,color=SecondaryInk)} }
                    }
                }
                if(q.optBoolean("allowOther")) OutlinedTextField(custom[id].orEmpty(),{value->if(value.length<=2000){custom=custom+(id to value);if(value.isNotBlank())choices=choices+(id to emptyList())}},enabled=canAnswer,label={Text("其他回答（不输入密码、验证码等秘密）")},modifier=Modifier.fillMaxWidth(),maxLines=4)
            }
        }
        val actual=JSONObject()
        questions.forEach { q->val id=q.optString("id");val text=custom[id].orEmpty();actual.put(id,JSONArray(if(text.isNotBlank())listOf(text)else choices[id].orEmpty())) }
        val complete=questions.isNotEmpty()&&questions.all { q->val values=actual.optJSONArray(q.optString("id"));!q.optBoolean("requiresPrivateInput")&&values!=null&&values.length()>0&&(q.optBoolean("multiSelect")||values.length()==1) }
        if(old==null) Button(onClick={answer(requestId,requestHash,actual)},enabled=canAnswer&&complete){Text("发送这份正常回答一次")}
        else Text("答案已在原任务登记：${old.optString("state")}。只查询原回执，不重复发送。",fontSize=12.sp,color=SecondaryInk)
        if(!request.optBoolean("canAnswer")) Text(request.optString("unsupportedReason","此问题类型暂不能安全回答"),fontSize=12.sp,color=SecondaryInk)
        else if(!snapshot.optBoolean("canAnswerQuestions")) Text(snapshot.optString("questionAnswerReason","请先在前台重新查询原执行"),fontSize=12.sp,color=SecondaryInk)
    }
    source.optJSONArray("questionReceipts").objects().takeLast(4).forEach { receipt->Text("正常回答回执：${receipt.optString("status")}；sent仅指已写入原CLI，resolved仅指原问题结束，不自动证明答案采纳或业务成功。",fontSize=11.sp,color=SecondaryInk) }
}

@Composable internal fun RemoteProgressPanel(snapshot: JSONObject) {
    val committed=snapshot.optJSONObject("eventRead")
    val readStatus=snapshot.optString("eventReadStatus")
    if(readStatus=="not_available") Text("原job本次没有可读取的进度。已保存记录和游标未清除；自动补读已暂停，可用“重新连接原任务”再核对。",fontSize=13.sp,color=SecondaryInk)
    if(readStatus=="unconfirmed") Text("进度补流未确认。已保存记录仍可看；没有重发任务、批准或答案。",fontSize=13.sp,color=MaterialTheme.colorScheme.error)
    if(readStatus=="unsupported") Text(if(committed==null)"这台电脑未声明持久事件补流；下面仅是原job最近快照，不代表完整历史。" else "当前电脑未声明持久补流；已保存的旧事件仍可看，当前状态仅按原job快照核对。",fontSize=13.sp,color=SecondaryInk)
    val progress=committed?:snapshot.optJSONObject("result")?.optJSONObject("progress")?:return
    if(committed==null&&!progress.optBoolean("available"))return
    val events=progress.optJSONArray("events").objects()
    val originalSource=committed?.optJSONObject("scope")?:snapshot.optJSONObject("source")?:JSONObject()
    if(originalSource.optString("worker")=="codex_app_server") {
        val runtime=originalSource.optJSONObject("modelRuntime")
        val actualVersion=originalSource.optString("nativeRuntimeVersion").ifBlank { if(runtime?.optString("protocol")=="native_model_runtime_v1"&&runtime.optString("worker")=="codex_app_server"&&runtime.optString("threadId")==originalSource.optString("threadId")&&runtime.optString("turnId")==originalSource.optString("turnId"))runtime.optString("runtimeVersion") else "" }
        if(actualVersion!=dev.pocketengine.features.remote.RemoteJobEvents.CODEX_CHILD_VERSION||originalSource.optString("threadId").isBlank()||originalSource.optString("turnId").isBlank())Text("此原版本或父thread/turn的子来源尚不可核对；仅保留原任务进度，不猜测或打开外部child会话。",fontSize=13.sp,color=SecondaryInk)
    }
    val sourced=events.mapNotNull { event -> runCatching { event to dev.pocketengine.features.remote.RemoteJobEvents.sourceView(event,originalSource) }.getOrNull() }
    val children=sourced.map{it.second}.filter{it.optString("kind")=="child"}.distinctBy{it.getString("key")}
    var sourceFilter by remember(snapshot.optString("taskId"),originalSource.optString("jobOperationId"),progress.optString("generation")){mutableStateOf("*")}
    var sourceMenu by remember(snapshot.optString("taskId"),progress.optString("generation")){mutableStateOf(false)}
    var expanded by remember(snapshot.optString("taskId"),progress.optString("generation"),sourceFilter){mutableStateOf(false)}
    fun sourceLabel(source:JSONObject)=if(source.optString("sourceProtocol")==dev.pocketengine.features.remote.RemoteJobEvents.CODEX_CHILD_SOURCE_PROTOCOL)"Codex子来源 ${source.optString("nativeChildThreadId").take(24)}" else if(source.optString("kind")=="child")"子来源 ${source.optString("nativeAgentId",source.optString("parentToolUseId")).take(24)}" else "主／未标记来源"
    val visible=sourced.filter{sourceFilter=="*"||it.second.getString("key")==sourceFilter}
    HorizontalDivider()
    Text(if(committed!=null&&readStatus!="confirmed")"已保存的执行进度"else if(snapshot.optBoolean("fresh"))"本次读取的执行进度"else"上次读取的执行进度",fontWeight=FontWeight.SemiBold,fontSize=15.sp)
    if(committed!=null){
        Text("已保存到事件 ${progress.optLong("nextSequence")}；${if(readStatus!="confirmed")"本次补流尚未确认"else if(progress.optBoolean("hasMore"))"正在补读已保留记录"else"当前保留记录已核对"}。关闭页面只停止观察，不停止电脑任务。",fontSize=14.sp)
        Text("有界增量读取，非SSE实时流或完整CLI历史；问题与审批仍以刚查询的当前请求为准。",fontSize=13.sp,color=SecondaryInk)
        if(progress.optBoolean("sourceScopeChanged"))Text("读取范围或电脑配置代次已变化，旧缓存未混入当前事件。",fontSize=13.sp,color=SecondaryInk)
        progress.optJSONArray("gaps").objects().forEach { gap -> Text(if(gap.optString("reason")=="retention_gap")"事件 ${gap.optLong("fromSequence")}–${gap.optLong("throughSequence")} 已不在电脑保留窗口，无法补回；原job当前状态另行读取，未虚构遗漏内容。" else "事件来源代次已变化，旧代遗漏范围无法确认；未继续旧执行或重发操作。",fontSize=13.sp,color=MaterialTheme.colorScheme.error) }
        if(progress.optLong("locallyDiscardedEvents")>0)Text("本机只保留最近64条／24KiB事件；更早已读记录未常驻缓存。",fontSize=13.sp,color=SecondaryInk)
    }else Text("原job最近事件快照，由查询更新，不是实时直播或完整历史。已省略${progress.optInt("droppedCount")}条，当前保留序号${progress.optLong("retainedFromSequence")}–${progress.optLong("lastSequence")}。",fontSize=13.sp,color=SecondaryInk)
    if(children.isNotEmpty()||sourceFilter!="*") {
        Text("仅按已核对的原生父子来源分看：Claude完整公开消息的parent tool-use；固定版本Codex的直接子thread/turn公开观察。不是可操作子会话，不新增权限、用量或恢复能力；有界读取不是子token直播，不读取隐藏thinking、不保证完整transcript。",fontSize=13.sp,color=SecondaryInk)
        Box {
            OutlinedButton(onClick={sourceMenu=true}) { Text(if(sourceFilter=="*")"全部原来源"else if(sourceFilter=="main_or_unspecified")"主／未标记来源"else children.firstOrNull{it.getString("key")==sourceFilter}?.let(::sourceLabel)?:"已移出保留窗口的子来源") }
            DropdownMenu(expanded=sourceMenu,onDismissRequest={sourceMenu=false}) {
                DropdownMenuItem(text={Text("全部原来源")},onClick={sourceFilter="*";sourceMenu=false})
                DropdownMenuItem(text={Text("主／未标记来源")},onClick={sourceFilter="main_or_unspecified";sourceMenu=false})
                children.forEach { child -> DropdownMenuItem(text={Text(sourceLabel(child))},onClick={sourceFilter=child.getString("key");sourceMenu=false}) }
            }
        }
    }
    if(sourced.size<events.size)Text("${events.size-sourced.size}条观察的来源无法核对，未混入父或子来源显示；原记录与游标保留。",fontSize=13.sp,color=MaterialTheme.colorScheme.error)
    if(events.isEmpty())Text("尚无可显示的已保存事件。",fontSize=14.sp)
    if(events.isNotEmpty()&&visible.isEmpty())Text("当前保留窗口没有此来源的可核对记录；可切换其他来源，不会补造或重发。",fontSize=13.sp,color=SecondaryInk)
    (if(expanded)visible else visible.takeLast(6)).forEach { (event,lineage)->
        if(lineage.optString("kind")=="child")Text(if(lineage.optString("sourceProtocol")==dev.pocketengine.features.remote.RemoteJobEvents.CODEX_CHILD_SOURCE_PROTOCOL)"${sourceLabel(lineage)} · 原parent thread ${lineage.optString("nativeParentThreadId").take(24)} / turn ${lineage.optString("nativeParentTurnId").take(24)} · 只读" else "${sourceLabel(lineage)} · parent tool-use ${lineage.getString("parentToolUseId")} · 只读",fontSize=12.sp,color=SecondaryInk)
        else if(children.isNotEmpty())Text("主／未标记来源（旧事件未声明子归属）",fontSize=12.sp,color=SecondaryInk)
        Text(listOf(event.optString("tool",event.optString("kind")),event.optString("status")).filter{it.isNotBlank()}.joinToString(" · "),fontSize=14.sp,fontWeight=FontWeight.Medium)
        if(event.optString("textForm")=="received_public_delta_not_complete_message")Text("已收到的公开增量观察，不代表完整消息；本地观察序号不是provider重放身份。",fontSize=12.sp,color=SecondaryInk)
        for(key in listOf("summary","command","text","explanation","counters"))event.optString(key).takeIf{it.isNotBlank()}?.let { text->SelectionContainer{Text(text,fontSize=14.sp)};if(event.optBoolean(key+"Truncated"))Text("本段已截断，不能视作完整输出。",fontSize=13.sp,color=SecondaryInk) }
        if(event.optString("kind")=="usage")Text("供应商原thread累计观察，不作本job请求计数、硬预算或账单，不逐快照累加。",fontSize=10.sp,color=SecondaryInk)
    }
    if(visible.size>6)TextButton(onClick={expanded=!expanded}){Text(if(expanded)"收起早前记录"else"查看该来源当前保留的${visible.size}条")}
}
