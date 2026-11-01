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
        Text("Agent 需要你回答",fontWeight=FontWeight.SemiBold,fontSize=16.sp)
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
    val progress=snapshot.optJSONObject("result")?.optJSONObject("progress")?:return
    if(!progress.optBoolean("available"))return
    val events=progress.optJSONArray("events").objects()
    if(events.isEmpty())return
    var expanded by remember(snapshot.optString("taskId"),progress.optString("generation")){mutableStateOf(false)}
    HorizontalDivider()
    Text(if(snapshot.optBoolean("fresh"))"本次读取的执行进度"else"上次读取的执行进度",fontWeight=FontWeight.SemiBold,fontSize=15.sp)
    Text("原job最近事件快照，由查询更新，不是实时直播或完整历史。已省略${progress.optInt("droppedCount")}条，当前保留序号${progress.optLong("retainedFromSequence")}–${progress.optLong("lastSequence")}。",fontSize=11.sp,color=SecondaryInk)
    (if(expanded)events else events.takeLast(6)).forEach { event->
        Text(listOf(event.optString("tool",event.optString("kind")),event.optString("status")).filter{it.isNotBlank()}.joinToString(" · "),fontSize=12.sp,fontWeight=FontWeight.Medium)
        for(key in listOf("summary","command","text","explanation","counters"))event.optString(key).takeIf{it.isNotBlank()}?.let { text->SelectionContainer{Text(text,fontSize=11.sp)};if(event.optBoolean(key+"Truncated"))Text("本段已截断，不能视作完整输出。",fontSize=10.sp,color=SecondaryInk) }
        if(event.optString("kind")=="usage")Text("供应商原thread累计观察，不作本job请求计数、硬预算或账单，不逐快照累加。",fontSize=10.sp,color=SecondaryInk)
    }
    if(events.size>6)TextButton(onClick={expanded=!expanded}){Text(if(expanded)"收起早前记录"else"查看当前保留的${events.size}条")}
}
