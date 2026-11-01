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
import dev.pocketengine.features.teams.TeamWorkFiles
import dev.pocketengine.store.TaskStore
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** The native owner, not a model's feedback/resume call, selects a new remote revision. */
@Composable internal fun TeamRemoteRevisionControls(run:JSONObject,member:JSONObject,original:JSONObject,integration:JSONObject?,controller:PocketWorldController) {
    val runId=run.getString("id");val assignment=original.getString("assignmentId")
    var retry by remember(runId,assignment){mutableIntStateOf(0)}
    val opened=remember(runId,assignment,retry){runCatching{controller.openTeamRemoteRevisionPage(runId,assignment)}}
    val page=opened.getOrNull()
    if(page==null){Text("原成果修订暂不可用：${opened.exceptionOrNull()?.message}",color=MaterialTheme.colorScheme.error);TextButton(onClick={retry++}){Text("重新读取原成果")};return}
    val lifecycle=LocalLifecycleOwner.current.lifecycle;val main=remember{Handler(Looper.getMainLooper())}
    var alive by remember(page){mutableStateOf(true)};var busy by remember(page){mutableStateOf(false)}
    var text by remember(runId,assignment){mutableStateOf("")};var notice by remember(page){mutableStateOf<String?>(null)}
    var current by remember(page){mutableStateOf(JSONObject())};var exact by remember(page){mutableStateOf<TaskStore.TeamRemoteRevisionReview?>(null)}
    var shown by remember(page){mutableStateOf(JSONObject())};var accepted by remember(exact){mutableStateOf(false)}
    DisposableEffect(page,lifecycle){
        fun foreground()=page.setForeground(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        val observer=LifecycleEventObserver{_,_->foreground()};lifecycle.addObserver(observer);foreground()
        onDispose{alive=false;page.close();lifecycle.removeObserver(observer)}
    }
    fun <T> work(action:()->T,success:(T)->Unit){if(busy||!alive)return;busy=true;notice=null
        try{controller.runEcosystemControlWork(Runnable{val result=runCatching(action);val recorded=if(result.isFailure)runCatching{page.snapshot()}.getOrNull() else null;main.post{if(alive){busy=false;if(recorded!=null)current=recorded;result.fold(success){notice=it.message?:"原操作未取得确认，请核对原记录"}}}})}catch(_:Exception){busy=false;notice="原工作队列不可用，尚未开始"}}
    LaunchedEffect(page){work({page.snapshot()}){current=it}}
    val refs=original.optJSONArray("artifacts")?:JSONArray()
    val canPropose=member.optString("status")=="completed"&&refs.length()>0&&integration?.optBoolean("currentFilesComplete")==true
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(6.dp)){
        if(canPropose){
            OutlinedTextField(text,{text=it;exact=null},label={Text("沿已整合成果继续的修改要求")},minLines=2,maxLines=4,enabled=!busy,modifier=Modifier.fillMaxWidth())
            OutlinedButton(onClick={work({
                val request=JSONObject().put("runId",runId).put("assignmentId",assignment).put("memberId",member.getString("id")).put("revisionRequestId",UUID.randomUUID().toString()).put("text",text)
                    .put("expectedFrozenRunId",refs.getJSONObject(0).getString("runId")).put("expectedArtifactsHash",TeamWorkFiles.hash(TeamWorkFiles.canonical(JSONObject().put("artifacts",refs))))
                    .put("expectedIntegrationReviewId",integration!!.getString("reviewId")).put("expectedIntegrationPlanHash",integration.getString("planHash"))
                page.review(request)
            }){exact=it;shown=it.view();accepted=false}},enabled=!busy&&text.isNotBlank()&&text.length<=4000){Text("审阅这份远端成果的新修订")}
        }
        exact?.let{review->
            val source=shown.optJSONObject("source")?:JSONObject();val selection=source.optJSONObject("originalSelection")?:JSONObject()
            TeamCaption("新基线来自 V1 实际作者任务 ${source.optString("sourceTaskId").take(12)} 的冻结贡献 ${source.optString("snapshotId").take(12)}，并逐文件核对手机当前整合版本；不复用旧source job或审批。")
            source.optJSONArray("appliedHeads").objects().forEach{file->Text("${file.optString("path")} · v${file.optInt("baseVersion")} · ${file.optString("baseHash").take(12)}",fontSize=12.sp)}
            TeamCaption("仍使用 ${selection.optString("profileId")} / ${selection.optString("environmentId")}，仅所选UTF-8文件副本，不是Git全项目、build/test或OS隔离。")
            val old=shown.optJSONObject("parentBudget")?:JSONObject();val plan=shown.optJSONObject("budgetPlan")?:JSONObject()
            TeamCaption("原模型上限 ${old.optInt("modelRequestLimit")} → ${shown.optInt("proposedModelRequestLimit")}；工具 ${old.optInt("toolOperationLimit")} → ${shown.optInt("proposedToolOperationLimit")}。计划含全量独审/唯一整合及明确重试余量，估计不是复杂任务足额保证；已用、预留与unknown不会清零。")
            TeamCaption("保存本修订不提交新job、不恢复旧control。下一步还须明确批准本修订独立job范围与未知内部计费；新job使用新的原reservation。")
            Row(verticalAlignment=Alignment.CenterVertically){Checkbox(accepted,{accepted=it},enabled=!busy);Text("我确认以上准确新基线、修改要求及可见预算调整。",fontSize=13.sp)}
            Button(onClick={work({page.approve(review,shown.getString("digest"))}){exact=null;notice="新修订已保存，待批准新的独立job范围。";controller.requestFeature("teams","get",JSONObject().put("runId",runId))}},enabled=!busy&&accepted&&plan.optBoolean("includesFullIndependentReviewAndUniqueIntegration")){Text("保存本次修订与预算，不提交job")}
        }
        if(current.optBoolean("recorded")&&current.optBoolean("currentScopeMatches")&&current.optString("taskStatus")=="awaiting_user"&&!current.optBoolean("cancelRequested")){
            TextButton(onClick={work({page.recoverRecorded()}){notice="原已批准scope已同步；没有提交或恢复执行。";controller.requestFeature("teams","get",JSONObject().put("runId",runId))}},enabled=!busy){Text("同步已保存的新修订（不恢复执行）")}
        }
        TextButton(onClick={work({page.snapshot()}){current=it}},enabled=!busy){Text("核对已记录修订")}
        notice?.let{Text(it,fontSize=13.sp)}
    }
}
