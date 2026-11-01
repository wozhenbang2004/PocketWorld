package dev.pocketengine.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pocketengine.features.teams.TeamWorkFiles
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Owner controls for the original assignment. Every write/revision still goes through the native TaskStore gates. */
@Composable
internal fun TeamWritePanel(run:JSONObject,selected:JSONObject?,state:PocketUiState,controller:PocketWorldController,onOpenTask:(String)->Unit){
    val runId=run.getString("id");val busy=state.moduleBusy.any{it.startsWith("teams.")};val stopped=run.optString("status")=="cancelled"
    fun value(command:String):JSONObject?=state.moduleResults["teams.$command"].takeIf{state.moduleResultInputs["teams.$command"]?.optString("runId")==runId}
    val review=value("reviewOutputs");val independent=value("independentReview")?:review?.optJSONObject("independentReview")?:run.optJSONObject("independentReview");val approved=value("approveOutputs")?:review?.optJSONObject("approvedReview");val integration=value("integrateOutputs")?:review?.optJSONObject("integration")
    val members=selected?.let{listOf(it)}?:run.optJSONArray("members").objects()
    Column(Modifier.fillMaxWidth().padding(vertical=12.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
        Text("文件协作",fontSize=16.sp,fontWeight=FontWeight.SemiBold)
        val projectId=run.optJSONObject("productionScope")?.optString("targetProjectId")
        val project=state.feature("workspace").optJSONArray("projects").objects().find{it.optString("id")==projectId}
        TeamCaption("原项目："+(project?.optString("name")?:"主人选定的项目")+"。成员先保存各自草稿；你核对实际差异后，再由唯一整合者逐文件保存。")
        if(selected==null&&!stopped&&run.optJSONObject("workPlan")==null)TeamDraftScopeControls(runId,controller::openTeamDraftScopePage,controller::runEcosystemControlWork)
        if(run.optJSONObject("workPlan")==null)members.forEach{member->
            val original=member.optJSONObject("assignmentTask");val taskId=original?.optString("taskId").orEmpty()
            Text(member.optString("name")+" · "+teamMemberStatus(member.optString("status")),fontSize=14.sp,fontWeight=FontWeight.Medium)
            if(taskId.isNotBlank())TextButton(onClick={onOpenTask(taskId)}){Text("查看原分工任务与审批")}
            if(taskId.isNotBlank()&&member.optString("runner")=="codex_app_server"&&member.optString("status")!="completed"&&!stopped)TeamRemoteScopeControls(runId,taskId,controller::openTeamRemoteScopePage,controller::runEcosystemControlWork)
            if(taskId.isNotBlank()&&member.optString("runner")=="codex_app_server"){
                state.tasks.filter { child->val origin=child.raw.optJSONObject("origin");val remoteAssignment=origin?.optJSONObject("context")?.optJSONObject("nativeAssignment");origin?.optString("kind")=="team_remote_assignment"&&remoteAssignment!=null&&remoteAssignment.optString("assignmentId")==taskId&&remoteAssignment.optString("teamRunId")==runId }.forEach { child->
                    TeamCaption("真实远端作者原任务 · "+child.raw.optString("status")+"。取消请求不等已退出，工作副本尚未声明回收。")
                    RemoteTaskApprovals(child,controller)
                    TextButton(onClick={onOpenTask(child.id)}){Text("打开该成员的原Codex job、待审请求与真实回执")}
                }
            }
            if(original!=null&&taskId.isNotBlank()&&member.optString("runner")=="codex_app_server"&&!stopped)TeamRemoteRevisionControls(run,member,original,integration,controller)
            original?.optJSONArray("questions").objects().forEach{question->
                key(question.optString("questionId")){
                    var answer by remember{mutableStateOf("")};val saved=value("answerAssignment")?.takeIf{it.optString("assignmentId")==original?.optString("assignmentId")&&it.optString("questionId")==question.optString("questionId")}
                    SelectionContainer{Text(question.optString("question"),fontSize=14.sp)}
                    val answered=question.optString("state")!="pending"||saved!=null
                    if(!answered){
                        OutlinedTextField(answer,{answer=it},label={Text("回答这条原问题")},minLines=2,maxLines=4,enabled=!busy,modifier=Modifier.fillMaxWidth())
                        OutlinedButton(onClick={controller.requestFeature("teams","answerAssignment",JSONObject().put("runId",runId).put("assignmentId",original!!.getString("assignmentId")).put("questionId",question.getString("questionId")).put("answerId",UUID.randomUUID().toString()).put("answer",answer))},enabled=!busy&&answer.isNotBlank()&&answer.length<=4000){Text("保存回答到原问题")}
                    }else TeamCaption(if(question.optString("state")=="consumed")"成员已在实际答复中使用这条回答。" else "回答已保留在原分工，继续后交给该成员；回答不替代文件审批。")
                }
            }
            if(original!=null&&member.optString("status") !in setOf("running","completed")&&!stopped)OutlinedButton(onClick={val input=reviewedTeamCollaborationAction(run,teamCollaborationStamp(run),"resume",member.getString("id"));controller.requestFeature("teams","resume",input)},enabled=!busy){Text("继续这名成员的原分工")}
            val refs=original?.optJSONArray("artifacts")?:member.optJSONArray("roundStates").objects().lastOrNull()?.optJSONArray("artifacts")
            if(original!=null&&refs!=null&&member.optString("status")=="completed"&&!stopped&&member.optString("runner")!="codex_app_server"){
                val remoteRevisionPending=member.optString("runner")=="codex_app_server"
                val integratedRefs=integration?.optJSONArray("receipts").objects().mapNotNull{it.optJSONObject("teamIntegrated")}
                val thisVersionIntegrated=integration?.optBoolean("currentFilesComplete")==true&&refs.length()>0&&refs.objects().all{ref->integratedRefs.any{it.optString("artifactId")==ref.optString("artifactId")&&it.optString("runId")==ref.optString("runId")}}
                var correction by remember(runId,member.optString("id")){mutableStateOf("")}
                OutlinedTextField(correction,{correction=it},label={Text("对这份成果的修改要求")},minLines=2,maxLines=4,enabled=!busy&&!remoteRevisionPending,modifier=Modifier.fillMaxWidth())
                TextButton(onClick={val input=reviewedTeamCollaborationAction(run,teamCollaborationStamp(run),"feedback",member.getString("id"),correction);input.put("assignmentId",original.getString("assignmentId")).put("revisionRequestId",UUID.randomUUID().toString()).put("expectedFrozenRunId",if(refs.length()>0)refs.getJSONObject(0).getString("runId") else original.getString("runId")).put("expectedArtifactsHash",TeamWorkFiles.hash(TeamWorkFiles.canonical(JSONObject().put("artifacts",refs))));controller.requestFeature("teams",if(thisVersionIntegrated)"continueOutcome" else "reviseAssignment",input.apply{if(thisVersionIntegrated){put("expectedIntegrationReviewId",integration.getString("reviewId"));put("expectedIntegrationPlanHash",integration.getString("planHash"))}})},enabled=!busy&&!remoteRevisionPending&&correction.isNotBlank()&&correction.length<=4000&&(approved==null||integration?.optBoolean("currentFilesComplete")==true)){Text(if(thisVersionIntegrated)"沿这份已整合成果继续修改" else "请求原分工的新修订")}
                TeamCaption(if(remoteRevisionPending)"远端新修订/沿原成果继续尚缺准确贡献基线重选；当前不会修改原分工或复用旧快照。现有job查询、贡献审查和唯一整合可继续。" else "新修订另建草稿并保留旧成果；保存要求后还需明确继续和批准。")
            }
        }
        if(run.optJSONObject("workPlan")!=null)TeamCaption("每个工作项的原assignment、交接与冻结文件在同一现场的「分工」中；此处审查的是全部当前工作项，不按成员最近一个任务替代。")
        if(selected==null){
            OutlinedButton(onClick={controller.requestFeature("teams","reviewOutputs",JSONObject().put("runId",runId))},enabled=!busy){Text("查看实际文件差异")}
            review?.let{actual->
                actual.optJSONArray("diffs").objects().forEach{file->TeamReviewedFileDiff(actual.optString("planHash"),file)}
                actual.optJSONArray("incompleteAssignments").objects().forEach{TeamCaption("仍需处理："+it.optString("memberId")+" · "+teamMemberStatus(it.optString("status")))}
                if(actual.optBoolean("recovery"))TeamCaption("这是原唯一整合任务的当前版本重审；旧操作与审批保留。只保留真实回读仍匹配的成功文件，其余文件需要本次新审批。")
                if(!actual.optBoolean("canIntegrate"))TeamCaption(actual.optString("blockedReason").ifBlank{"当前尚不能整合：请处理未完成的原分工，或重新核对已变化的项目文件。"})
                val reviewedIndependently=independent?.takeIf{it.optString("planHash")==actual.optString("planHash")&&it.optString("status")=="completed"&&it.optBoolean("completeOriginalFilesRead")&&it.optString("verdict")=="no_blocking_findings"&&!it.optBoolean("stale")}
                val parentBudget=run.optJSONObject("parentBudget")
                parentBudget?.let{budget->TeamCaption("原父总池：模型剩余 ${budget.optInt("remainingModelRequests")} / ${budget.optInt("modelRequestLimit")}，文件操作剩余 ${budget.optInt("remainingToolOperations")} / ${budget.optInt("toolOperationLimit")}；包含独审和唯一整合，token/费用上限未知。")}
                TextButton(onClick={controller.requestFeature("teams","resume",reviewedTeamCollaborationAction(run,teamCollaborationStamp(run),"resume",null))},enabled=!busy&&!stopped&&run.optJSONObject("workPlan")==null){Text("审阅后继续原计划与原有界预算")}
                OutlinedButton(onClick={controller.requestFeature("teams","independentReview",JSONObject().put("runId",runId).put("expectedPlanHash",actual.getString("planHash")))},enabled=!busy&&!stopped&&actual.optBoolean("canIntegrate")&&reviewedIndependently==null){Text("请独立审查者读取这些冻结文件")}
                independent?.let{proof->
                    TeamCaption(if(reviewedIndependently!=null)"独立原任务已读取这版全部文件。审查意见不是业务验收或文件批准。" else "独审尚未完成或成果已修订；旧审查不能批准当前版本。")
                    TeamCaption(when(proof.optString("verdict")){"changes_requested"->"独审要求修改：请把具体意见发给原作者，修订会使旧review失效。";"no_blocking_findings"->"独立模型未发现阻塞问题；仍需主人核对。";else->"独审结论未确定，不允许据此整合。"})
                    if(proof.optString("findings").isNotBlank()&&proof.optString("findings")!=proof.optString("summary"))SelectionContainer{Text(proof.optString("findings"),fontSize=14.sp)}
                    if(proof.optString("summary").isNotBlank())SelectionContainer{Text(proof.optString("summary"),fontSize=14.sp)}
                    proof.optString("taskId").takeIf{it.isNotBlank()}?.let{task->TextButton(onClick={onOpenTask(task)}){Text("查看独立审查的原任务与阅读证据")}}
                }
                Button(onClick={controller.requestFeature("teams","approveOutputs",JSONObject().put("runId",runId).put("expectedPlanHash",actual.getString("planHash")))},enabled=!busy&&!stopped&&actual.optBoolean("canIntegrate")&&reviewedIndependently!=null&&approved?.optString("planHash")!=actual.optString("planHash")){Text(if(actual.optBoolean("recovery"))"确认当前版本并批准本次重审" else "确认这份差异与整合计划")}
                approved?.takeIf{it.optString("planHash")==actual.optString("planHash")}?.let{decision->
                    TeamCaption("这份审查已保存。唯一整合者仍会逐文件请求本机批准，并核对当前版本。")
                    Button(onClick={controller.requestFeature("teams","integrateOutputs",JSONObject().put("runId",runId).put("reviewId",decision.getString("reviewId")))},enabled=!busy&&!stopped){Text("继续唯一整合者")}
                }
            }
            integration?.let{result->
                TeamCaption(result.optString("message","请查看唯一整合者的原任务记录。"))
                result.optJSONObject("projectDelivery")?.takeIf { it.optBoolean("complete") }?.let { delivery ->
                    val artifact = delivery.getJSONObject("packageArtifact")
                    Text("完整固定项目成果", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    TeamCaption("同版源码 ${delivery.optString("sourceDigest").take(16)} · 原包 ${artifact.optString("archiveSha256").take(16)} / ${artifact.optLong("archiveBytes")} 字节；${delivery.optJSONObject("checks")?.optJSONArray("checks")?.length() ?: 0} 项实际检查。")
                    TeamCaption("由主人完整基线与准确已审输出组合；不含成员额外文件，不覆盖父活跃checkout，不等于当前主项目已测试。")
                    OutlinedButton(onClick = { controller.openTeamProjectPackage(runId) }, enabled = !busy) { Text("打开原固定包、预览或明确接收继续") }
                }

                result.optString("taskId").takeIf{it.isNotBlank()}?.let{task->TextButton(onClick={onOpenTask(task)}){Text("查看唯一整合者与逐文件审批")}}
                result.optJSONObject("progress")?.let{progress->
                    TeamCaption("当前回读："+progress.optInt("applied")+" 个本次保存，"+progress.optInt("retained")+" 个保留，"+(progress.optJSONArray("remaining")?.length()?:0)+" 个待处理；不是整批事务或业务验收。")
                    if(result.optBoolean("hasUnresolved"))TeamCaption("原操作效果仍未知，请先在原任务核对；不会重新提交。")
                    if(result.optBoolean("requiresOwnerReview"))TeamCaption("当前文件已变化，请重新查看实际差异并明确批准。")
                }
                TeamCaption("各文件分别核对版本并保存；失败时可能已有部分文件完成，以原任务回执为准。")
            }
        }
        listOf("answerAssignment","reviseAssignment","continueOutcome","reviewOutputs","independentReview","approveOutputs","integrateOutputs").forEach{command->state.moduleErrors["teams.$command"]?.let{Text(it,color=Danger,fontSize=12.sp)}}
    }
}

@Composable
private fun TeamReviewedFileDiff(plan:String,file:JSONObject){
    val text=file.optString("diff");var offset by remember(plan,file.optString("path")){mutableIntStateOf(0)};val end=minOf(text.length,offset+12000)
    Text(file.optString("path"),fontSize=14.sp,fontWeight=FontWeight.Medium)
    TeamCaption(when(file.optString("disposition")){"retain_applied"->"保留原成功回执：当前文件仍是该精确版本，不会再次写入。";"apply_reviewed"->"按当前项目版本重新审查；旧审批不允许覆盖此版本。";else->if(file.optBoolean("matchesOwnerBase"))"原项目仍符合主人选定的版本。" else "原项目已变化，这份差异不能直接整合。"})
    SelectionContainer{Text(if(text.isEmpty())"文件内容相同。" else text.substring(offset,end),fontSize=12.sp)}
    if(offset>0||end<text.length)Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){TextButton(onClick={offset=maxOf(0,offset-12000)},enabled=offset>0){Text("上一段")};TextButton(onClick={offset=end},enabled=end<text.length){Text("下一段")}}
    if(file.optBoolean("truncated"))TeamCaption("差异超过完整展示上限；当前禁止整合，请先核对文件。")
    HorizontalDivider(color=Line)
}
