package dev.pocketengine.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

/** Immutable project comparisons and separate native restore/fork actions on the original job page. */
@Composable
internal fun RemoteProjectHistoryPanel(snapshot:JSONObject,enabled:Boolean,controller:PocketWorldController,send:(String,JSONObject)->Unit) {
    val source=snapshot.optJSONObject("source")?:return
    val task=snapshot.optJSONObject("task")?:JSONObject()
    if(snapshot.optBoolean("teamRemoteWriter")||task.has("teamRunId")||source.has("assignmentWorkspace"))return
    val job=source.optString("jobId")
    if(job.isBlank())return
    val summary=source.optJSONObject("projectHistory")
    val hasBaseline=summary?.optJSONObject("baseline")!=null
    val histories=snapshot.optJSONArray("projectHistorySources").objects()
    val viewedJob=snapshot.optJSONObject("projectHistoryReadSource")?.optString("jobId").orEmpty().ifBlank{job}
    val canFork=source.optJSONObject("controls")?.optBoolean("threadFork")==true
    var expanded by remember(job){mutableStateOf(hasBaseline)}
    var prompt by remember(job){mutableStateOf("")}
    var timeout by remember(job){mutableStateOf("300")}
    val state=controller.state
    val busy=state.ownerPending.any{it.startsWith("remoteTask.")}||snapshot.optBoolean("projectBusy")
    HorizontalDivider(Modifier.padding(top=8.dp),color=Line)
    TextButton(onClick={expanded=!expanded}){Text(if(expanded)"收起项目版本与对话分支" else "项目版本与对话分支")}
    if(!expanded)return
    if(hasBaseline||histories.isNotEmpty()) {
        Text(if(hasBaseline)"本次工作有修改前项目基线" else "此任务保留了历史项目版本",fontSize=16.sp,fontWeight=FontWeight.SemiBold)
        Text("这里比较同一job前后的已冻结公开源码。协议报告涉及某文件，不等于独占作者；未报告变化按归属未知显示。",fontSize=14.sp,lineHeight=22.sp)
        OutlinedButton(onClick={send("project.list",JSONObject().put("offset",0))},enabled=enabled&&!busy){Text("读取这项原工作前后版本")}
    }else Text("这项旧工作没有启动前的项目基线，不能用结束后的当前文件补造。已有原成果快照入口仍可使用；新工作可显选先保存基线。",fontSize=14.sp,lineHeight=22.sp,color=SecondaryInk)
    histories.forEach { original ->
        TextButton(onClick={send("project.list",JSONObject().put("offset",0).put("operationId",original.optString("jobOperationId")))},enabled=enabled&&!busy){Text("查看已登记项目版本 · "+original.optString("jobId"))}
    }
    if(viewedJob!=job)Text("正在只读查看历史job $viewedJob。当前会话/执行没有切换；任何恢复仍须核对当前run、真实文件版本并单独确认。",fontSize=14.sp,lineHeight=22.sp,color=SecondaryInk)
    val page=snapshot.optJSONObject("projectHistoryPage").takeIf{it?.optString("jobId")==viewedJob}
    page?.let { current ->
        val history=current.optJSONObject("history")?:JSONObject()
        val baseline=history.optJSONObject("baseline")?:JSONObject()
        val after=history.optJSONObject("after")
        Text("基线 ${baseline.optInt("fileCount")}文件 / ${baseline.optLong("bytes")}字节 · 排除${baseline.optInt("excludedEntries")}个私密/缓存入口",fontSize=13.sp,lineHeight=20.sp,color=SecondaryInk)
        if(history.optString("comparisonStatus")!="available")Text("后置比较尚不可用：${history.optString("reason",history.optString("comparisonStatus"))}。这不是完整变化清单，可手动重新读取原job，不会启动worker。",fontSize=14.sp,lineHeight=22.sp)
        current.optJSONArray("files").objects().forEach { file ->
            val path=file.optString("path")
            val label=when(file.optString("change")){"added"->"新增";"deleted"->"删除";"modified"->"修改";"unchanged"->"未变";else->"仅基线"}
            Column(Modifier.fillMaxWidth().padding(vertical=8.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                Text("$label · $path",fontSize=14.sp,fontWeight=FontWeight.Medium)
                if(file.optString("change")in setOf("added","deleted","modified"))Text(if(file.optBoolean("workerReportedPath"))"上游报告涉及此路径，非独占创作证明" else "实际观察到变化，作者归属未知",fontSize=13.sp,color=SecondaryInk)
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    if(after!=null)TextButton(onClick={send("project.diff",JSONObject().put("path",path))},enabled=enabled&&!busy){Text("查看实际差异")}
                    if(file.optJSONObject("before")!=null)TextButton(onClick={send("project.reviewRestore",JSONObject().put("snapshotId",baseline.optString("snapshotId")).put("snapshotVersion",baseline.optString("snapshotVersion")).put("path",path))},enabled=enabled&&!busy){Text("审阅恢复修改前版本")}
                }
                if(after!=null&&file.optJSONObject("after")!=null)TextButton(onClick={send("project.reviewRestore",JSONObject().put("snapshotId",after.optString("snapshotId")).put("snapshotVersion",after.optString("snapshotVersion")).put("path",path))},enabled=enabled&&!busy){Text("审阅恢复该工作结束版本")}
            }
        }
        if(current.optBoolean("hasMore"))OutlinedButton(onClick={send("project.list",JSONObject().put("offset",current.optInt("nextOffset")))},enabled=enabled&&!busy){Text("下一页 · 同一冻结比较版本")}
        Text("已展示 ${current.optInt("offset")}–${current.optInt("nextOffset")} / ${current.optInt("totalFiles")}条；不是平台完整历史。",fontSize=13.sp,color=SecondaryInk)
    }
    snapshot.optJSONObject("projectHistoryDiff")?.takeIf{it.optString("jobId")==viewedJob}?.let { diff ->
        Text("差异：${diff.optString("path")}",fontSize=14.sp,fontWeight=FontWeight.Medium)
        if(diff.optBoolean("binary"))Text("二进制版本：${diff.optString("beforeSha256")} → ${diff.optString("afterSha256")}；不伪造文本diff。",fontSize=13.sp,lineHeight=20.sp)
        else SelectionContainer{Text(diff.optString("diff").ifBlank{"所读前后字节没有文本差异"},fontSize=13.sp,lineHeight=20.sp)}
        if(diff.optBoolean("diffTruncated"))Text("此文本diff超过展示范围，只显示有界前缀，不代表全部读完。",fontSize=14.sp,color=MaterialTheme.colorScheme.error)
    }
    Text("对话分支与文件恢复是两件事",fontSize=16.sp,fontWeight=FontWeight.SemiBold,modifier=Modifier.padding(top=10.dp))
    if(canFork) {
        Text("分支保留原对话的最新已终止轮次并得到真实新thread；文件仍保持当前内容。需要新的明确任务与预算审阅，不复用旧批准。",fontSize=14.sp,lineHeight=22.sp)
        OutlinedTextField(prompt,{prompt=it},label={Text("新分支这次要完成什么")},minLines=2,maxLines=5,modifier=Modifier.fillMaxWidth(),enabled=!busy)
        OutlinedTextField(timeout,{timeout=it.filter(Char::isDigit).take(4)},label={Text("新执行时限（10–3600秒）")},singleLine=true,enabled=!busy,modifier=Modifier.fillMaxWidth())
        OutlinedButton(onClick={send("project.reviewFork",JSONObject().put("prompt",prompt).put("timeoutSeconds",timeout.toIntOrNull()?:0))},enabled=enabled&&!busy&&prompt.isNotBlank()&&prompt.length<=12000&&(timeout.toIntOrNull()?:0)in 10..3600){Text("审阅这次独立会话分支")}
    }else Text(if(source.optString("worker")=="claude")"当前Claude接线没有取得首条prompt前可核的新session合同，此处明确不提供假分支；原会话明确续接仍可按原入口使用。" else "当前原job/安装协议没有可核分支条件，或原执行仍在活动/未知。不会把分支失败改成空会话创建。",fontSize=14.sp,lineHeight=22.sp,color=SecondaryInk)
    RemoteProjectActionReview(snapshot,busy,send)
    task.optJSONArray("operations").objects().filter{it.optString("tool")=="remote_file_restore"}.takeLast(8).forEach { original ->
        TextButton(onClick={send("project.queryRestore",JSONObject().put("operationId",original.optString("operationId")))},enabled=enabled&&!busy){Text("核对原文件恢复 · "+original.optString("state"))}
    }
    val operation=snapshot.optJSONObject("projectHistoryOperation")
    operation?.let { original ->
        val scope=original.optJSONObject("arguments")?.optJSONObject("scope")?:JSONObject()
        Text("原操作：${original.optString("state")}\n${original.optString("operationId")}",fontSize=13.sp,lineHeight=20.sp)
        if(scope.optString("action")=="RESTORE") {
            if(original.optString("state")in setOf("unknown","prepared"))TextButton(onClick={send("project.queryRestore",JSONObject().put("operationId",original.optString("operationId")))},enabled=enabled&&!busy){Text("只查询原文件恢复回执")}
            Text("恢复完成也不改写旧快照。要按恢复内容继续：在上方原文件成果入口重新读取、冻结当前所选文件，再明确选择新快照续接。",fontSize=14.sp,lineHeight=22.sp,color=SecondaryInk)
        }else TextButton(onClick={controller.focusTask(original.optString("taskId"))},enabled=original.optString("taskId").isNotBlank()){Text("打开新任务与真实分支回执")}
    }
}
