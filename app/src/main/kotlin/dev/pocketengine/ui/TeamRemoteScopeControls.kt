package dev.pocketengine.ui

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.pocketengine.features.teams.TaskStoreTeamWriteHost
import dev.pocketengine.store.TaskStore
import org.json.JSONObject

/** Independent remote-job authority is an explicit owner choice, never inherited from chat or model arguments. */
@Composable internal fun TeamRemoteScopeControls(runId: String, assignmentId: String, openPage: (String, String) -> TaskStoreTeamWriteHost.RemoteScopePage, runWork: (Runnable) -> Unit) {
    var retry by remember(runId, assignmentId) { mutableIntStateOf(0) }
    val opened = remember(runId, assignmentId, retry) { runCatching { openPage(runId, assignmentId) } }
    val page = opened.getOrNull()
    if (page == null) {
        Text("原远端分工审阅暂不可用：${opened.exceptionOrNull()?.message ?: "请重新核对原任务"}", color=MaterialTheme.colorScheme.error, fontSize=13.sp)
        TextButton(onClick={retry++}) { Text("重新读取原分工") }; return
    }
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    val main=remember { Handler(Looper.getMainLooper()) }
    var alive by remember(page) { mutableStateOf(true) }
    var busy by remember(page) { mutableStateOf(false) }
    var current by remember(page) { mutableStateOf(JSONObject()) }
    var review by remember(page) { mutableStateOf<TaskStore.TeamRemoteScopeReview?>(null) }
    var shown by remember(page) { mutableStateOf(JSONObject()) }
    var accepted by remember(review) { mutableStateOf(false) }
    var notice by remember(page) { mutableStateOf<String?>(null) }
    DisposableEffect(page,lifecycle) {
        fun foreground()=page.setForeground(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        val observer=LifecycleEventObserver { _,_->foreground() };lifecycle.addObserver(observer);foreground()
        onDispose { alive=false;page.close();lifecycle.removeObserver(observer) }
    }
    fun <T> work(action:()->T,accept:(T)->Unit) {
        if(busy||!alive)return;busy=true;notice=null
        try { runWork(Runnable { val result=runCatching(action);main.post { if(alive){busy=false;result.fold(accept){notice=it.message?:"请核对原操作，未重新提交"}} } }) }
        catch(_:Exception){busy=false;notice="原工作队列不可用，尚未开始"}
    }
    LaunchedEffect(page) { work({page.snapshot()}){current=it} }
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(6.dp)) {
        TeamCaption(if(current.optJSONObject("selection")?.optBoolean("fullProject")==true) "真实Codex作者读取主人显选的完整源包，在独立Git新基线制作；仅原写路径可导回增改删/二进制。远端命令与手机唯一整合者另审，目录不是OS隔离。" else "真实Codex作者处理所选文字文件副本；此旧模式不含命令/build/test/Git或OS隔离。原项目不由远端直写。")
        TextButton(onClick={work({page.snapshot()}){current=it;review=null}},enabled=!busy){Text("核对原范围与剩余预算")}
        current.optJSONObject("approvedScope")?.takeIf{!current.optBoolean("needsOwnerScope",true)}?.let { scope->
            TeamCaption("当前分工已批准 ${scope.optInt("approvedJobs")} 次独立远端job；未知接收/取消不退款，后续修改或新job需重新明确批准。")
            TextButton(onClick={work({page.revoke(scope.getString("scopeId"),scope.getInt("revision"))}){current=it;review=null;notice="已撤销后续派发并请求停止原job；真实退出和工作副本回收仍以原记录为准。"}},enabled=!busy){Text("撤销范围并请求停止原job")}
        }
        if(current.optBoolean("needsOwnerScope",true)) {
            OutlinedButton(onClick={work({page.review()}){review=it;shown=it.view();accepted=false}},enabled=!busy){Text("审阅本次独立远端job与预算")}
        }
        review?.let { exact->
            val selected=shown.optJSONObject("remoteSelection")?:JSONObject()
            TeamCaption("执行位置：${selected.optString("profileId")} / ${selected.optString("environmentId")}；" + if(selected.optBoolean("fullProject")) "完整固定包 ${selected.optJSONObject("packageReference")?.optString("id")} · ${selected.optString("packageIdentity").take(12)}（新Git基线，不含原历史）" else "基线快照 ${selected.optString("snapshotId").take(12)} · ${selected.optString("snapshotVersion").take(12)}。")
            shown.optJSONArray("files").objects().forEach { file->Text("${file.optString("path")} · 原项目 v${file.optInt("baseVersion")} · ${file.optString("baseHash").take(12)}",fontSize=12.sp) }
            val limits=selected.optJSONObject("limits")?:JSONObject()
            TeamCaption("明确硬限：${limits.optInt("maxWallTimeSeconds")}秒；输出${limits.optLong("maxOutputBytes")}字节；冻结贡献${limits.optLong("maxContributionBytes")}字节。")
            TeamCaption("本次范围到期：${java.time.Instant.ofEpochMilli(shown.optLong("proposedScopeExpiresAt"))}；改版、撤销、到期或超出原路径都需重新审阅，Reply不续预算或授权。")
            TeamCaption("一次独立job≠一次模型请求。远端内部模型次数/token/费用未知且不能硬限，不会填0；不满足token或费用硬上限的任务不得提交。")
            TeamCaption("本地调度模型请求上限 ${shown.optJSONObject("parentBudget")?.optInt("modelRequestLimit")} → ${shown.optInt("proposedParentModelLimit")}；原父工具上限 → ${shown.optInt("proposedParentToolLimit")}，实际父操作剩余 ${shown.optInt("parentRemainingOperations")}；本次至少${shown.optInt("minimumAdditionalToolOperations")}步，独立job剩余额度${shown.optInt("remainingParentExternalJobs")}。")
            shown.optJSONObject("localModelBudgetPlan")?.let { plan->TeamCaption("本片调度计划估计 ${plan.optInt("plannedFlowRequests")} 次本地请求，另含 ${plan.optInt("explicitRetryMargin")} 次明确重试余量；不是通用最小值或复杂任务完成保证。耗尽会停下，不自动续预算。") }
            TeamCaption(if(selected.optBoolean("fullProject")) "完整源包已明确外传供读，命令同用户风险与未知内部计费已显选；原worker每条命令/fileChange仍单次核确切请求，不允许session/execpolicy/网络扩权。手机调度模型不因此拥有手机命令；手机组合测试/包须另有实际证据。" else "只可单次批准原worker针对精确所选路径的fileChange。命令/执行策略/网络/目录扩权不在旧scope；请在原child页处理真实待审请求。")
            Row(verticalAlignment=Alignment.CenterVertically){Checkbox(accepted,{accepted=it},enabled=!busy);Text("我明确批准以上范围、可见本地预算及1次独立远端job，并接受内部推理计费不可硬限。",fontSize=13.sp)}
            Button(onClick={work({page.approve(exact,shown.getString("digest"))}){current=it;review=null;notice="本次独立job范围已保存，原成员已回原分工继续；没有重建旧control。"}},enabled=!busy&&accepted&&shown.optInt("maximumAssignmentJobs")>=1&&shown.optInt("parentRemainingOperations")>=shown.optInt("minimumAdditionalToolOperations")){Text("批准这次原分工job并继续")}
        }
        notice?.let { Text(it,fontSize=13.sp) }
    }
}
