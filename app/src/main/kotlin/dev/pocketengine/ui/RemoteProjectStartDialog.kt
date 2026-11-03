package dev.pocketengine.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.json.JSONObject

/** Native owner review; ordinary text creation and attachment selection retain their old paths. */
@Composable
internal fun RemoteProjectStartDialog(request: JSONObject, controller: PocketWorldController, close: () -> Unit) {
    var window by remember(request.toString()) { mutableStateOf<String?>(null) }
    DisposableEffect(request.toString()) {
        val opened=controller.enterRemoteProject(request);window=opened
        onDispose { controller.leaveRemoteTask(opened) }
    }
    val state=controller.state
    val snapshot=state.ownerSnapshot("remoteTask").takeIf { it.optString("windowId")==window }?:JSONObject()
    val busy=state.ownerPending.any { it.startsWith("remoteTask.") }||snapshot.optBoolean("projectBusy")
    var root by remember(window) { mutableStateOf("") }
    var error by remember(window) { mutableStateOf<String?>(null) }
    val operation=snapshot.optJSONObject("projectHistoryOperation")
    fun send(command:String, payload:JSONObject=JSONObject()) { try { controller.requestRemoteTask(command,payload);error=null } catch(failure:Exception){error=failure.message} }
    Dialog(onDismissRequest=close,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(22.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    Text("先保留项目版本",fontSize=21.sp,fontWeight=FontWeight.SemiBold,maxLines=2,overflow=TextOverflow.Ellipsis,modifier=Modifier.weight(1f))
                    TextButton(onClick=close){Text("返回")}
                }
                Text("在这次Agent启动前冻结所选公开源码，结束后与同一份基线比较。失败不会把不完整采集冒成成功。",fontSize=16.sp,lineHeight=24.sp)
                val env=snapshot.optJSONObject("projectEnvironment")
                Text("工作区：${env?.optString("workspace").orEmpty()}\n电脑：${request.optString("profileId")} · ${remoteWorkerLabel(request.optString("worker"))}",fontSize=14.sp,lineHeight=22.sp)
                OutlinedTextField(root,{root=it},label={Text("基线项目子目录（留空为所配置工作区）")},enabled=!busy&&operation==null,singleLine=true,modifier=Modifier.fillMaxWidth())
                Text("仅此目录的公开源码：最多2000文件、每文件256KiB、合计8MiB。凭据、.git、依赖和构建缓存排除；遇到公共符号链接、版本漂移或超限会明确停止，不默默少读。",fontSize=14.sp,lineHeight=22.sp)
                Text("这只是版本范围。Agent仍按原工作区工具权限运行，不是目录或操作系统沙箱；范围外变化没有这份基线证明。",fontSize=14.sp,lineHeight=22.sp,color=SecondaryInk)
                if(operation==null)OutlinedButton(onClick={send("project.reviewStart",JSONObject().put("projectRoot",root))},enabled=!busy&&env!=null){Text("核对目录、目标与这次工作")}
                RemoteProjectActionReview(snapshot,busy,::send)
                operation?.let { actual ->
                    Text("本次操作已登记：${actual.optString("state")}。未知时只查询原编号，不再创建另一份工作。",fontSize=14.sp,lineHeight=22.sp)
                    TextButton(onClick={controller.focusTask(actual.optString("taskId"));close()},enabled=actual.optString("taskId").isNotBlank()){Text("打开这项原任务与回执")}
                }
                val latest=state.ownerResponses("remoteTask").values.filter { it.optString("windowId")==window }.maxByOrNull{it.optLong("sequence")}
                (error?:latest?.takeIf{!it.optBoolean("ok")}?.optString("error"))?.let { Text(it,color=MaterialTheme.colorScheme.error,fontSize=14.sp) }
            }
        }
    }
}

/** The exact native digest includes all target/scope/expiry fields; JSON alone is not approval. */
@Composable
internal fun RemoteProjectActionReview(snapshot:JSONObject,busy:Boolean,send:(String,JSONObject)->Unit) {
    val review=snapshot.optJSONObject("projectHistoryReview")?:return
    val scope=review.optJSONObject("scope")?:return
    val action=scope.optString("action")
    var accepted by remember(review.optString("digest")) { mutableStateOf(false) }
    var details by remember(review.optString("digest")) { mutableStateOf(false) }
    val request=scope.optJSONObject("request")?:JSONObject()
    HorizontalDivider(color=Line)
    Text(when(action){"RESTORE"->"确认只恢复这个文件";"FORK"->"确认建立独立对话分支";else->"确认项目范围与新工作"},fontWeight=FontWeight.SemiBold,fontSize=16.sp)
    SelectionContainer { Text("目标：${scope.optString("serviceBase")}\n环境：${request.optString("environmentId",scope.optJSONObject("source")?.optString("environmentId").orEmpty())}",fontSize=14.sp,lineHeight=22.sp) }
    if(action=="RESTORE") {
        val desired=scope.optJSONObject("desired")?:JSONObject();val current=scope.optJSONObject("current")?:JSONObject()
        Text("替换：${desired.optString("path")}\n旧版本：${desired.optLong("bytes")}字节 · SHA-256 ${desired.optString("sha256")}\n当前已读：${current.optLong("bytes")}字节 · ${current.optString("sha256")}",fontSize=14.sp,lineHeight=22.sp)
        Text("只写回这一个已冻结文件的实际原字节；再次核对当前版本，漂移即拒绝。不是全局OS事务，未知时不重发；不会恢复或改写对话。恢复会产生当前新文件版本，旧快照仍不变。",fontSize=14.sp,lineHeight=22.sp)
    } else {
        if(action=="FORK") {
            val source=scope.optJSONObject("source")?:JSONObject()
            Text("从 ${source.optString("threadId")} / ${source.optString("turnId")} 分支到新任务。真实thread/fork及新历史核对成功后才送本次要求；旧端不支持时明确不可用，不退回新建空会话。",fontSize=14.sp,lineHeight=22.sp)
            Text("只分支对话，不恢复文件。工作区仍是现在的内容；不继承旧批准、旧job或旧预算。",fontSize=14.sp,lineHeight=22.sp)
        } else Text("版本目录：${request.optJSONObject("projectHistory")?.optString("projectRoot").orEmpty().ifBlank{"所配置工作区"}}\n原工作区：${scope.optString("workspace")}",fontSize=14.sp,lineHeight=22.sp)
        Text(remoteWorkerLabel(request.optString("worker")) + " · " + request.optString("model").ifBlank { "执行端默认模型" },fontSize=14.sp,lineHeight=22.sp)
        if(request.has("effort"))Text("本次请求档位：${request.optString("effort")}；真实读回在原任务另列，未因此增加额度或工具权限。",fontSize=14.sp,lineHeight=22.sp)
        SelectionContainer { Text(request.optString("prompt"),fontSize=14.sp,lineHeight=22.sp) }
        Text("新执行时限：${request.optInt("timeoutSeconds")}秒 · ${if(request.optBoolean("readOnly"))"Codex只读" else "按原工作区写入权限执行"}。CLI内部模型次数、tokens与费用不可硬限，旧未知费用不清零。",fontSize=14.sp,lineHeight=22.sp)
        Text("文件变化只证明这段时间实际观察到的差异。上游声明涉及某路径，不等于独占作者；同UID/外部写入者仍可能修改文件。",fontSize=14.sp,lineHeight=22.sp)
    }
    Row(verticalAlignment=Alignment.CenterVertically) {
        Checkbox(accepted,{accepted=it},enabled=!busy)
        Text("我已核对确切目标、数据/文件范围和上述风险，仅同意这一次动作。",fontSize=14.sp,lineHeight=22.sp,modifier=Modifier.weight(1f))
    }
    Button(onClick={send("project.confirm",JSONObject().put("digest",review.optString("digest")).put("scopeAcknowledged",accepted))},enabled=!busy&&accepted){Text(if(action=="RESTORE")"恢复这一个文件" else "只提交这一次新工作")}
    TextButton(onClick={details=!details}){Text(if(details)"收起版本与身份细节" else "查看版本与身份细节")}
    if(details)SelectionContainer { Text(scope.toString(2),fontSize=13.sp,lineHeight=20.sp) }
}
