package dev.pocketengine.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject

internal data class AgentSlotDraft(val id: String, val name: String, val role: String, val model: String, val runner: String, val profileId: String = "", val environmentId: String = "", val writeFiles: String = "", val sourceTaskId: String = "", val snapshotId: String = "", val wallSeconds: String = "120", val outputBytes: String = "65536", val contributionBytes: String = "131072", val remoteBillingAcknowledged: Boolean = false, val fullProject: Boolean = false, val packageId: String = "", val packageSha: String = "", val packageManifest: String = "", val remoteCommandsAcknowledged: Boolean = false, val sourceTransferAcknowledged: Boolean = false, val externalExecutionRiskAcknowledged: Boolean = false, val modeId: String = "", val phoneDevice: String = "", val phoneAssignment: String = "", val phoneDependencies: List<String> = emptyList()) {
    fun json(): JSONObject = JSONObject().put("id", id).put("name", name).put("role", role).put("model", model).put("runner", runner).put("device", if (runner == "model") "phone" else if (runner == "phone_agent" && phoneDevice.isNotBlank()) JSONObject(phoneDevice) else JSONObject().put("profileId", profileId).put("environmentId", environmentId)).also {
        if(externalTeamHarness(runner) && runner != "codex_app_server")it.put("externalExecutionRiskAcknowledged",externalExecutionRiskAcknowledged)
        if(externalTeamHarness(runner) && modeId.isNotBlank())it.put("modeId",modeId)
        if(runner == "phone_agent") { it.put("assignment", phoneAssignment); it.put("dependsOn", JSONArray(phoneDependencies)) }
        if(writeFiles.isNotBlank())it.put("writeFiles",JSONArray(writeFiles.lines().map { it.trim() }.filter { it.isNotBlank() }))
        if(externalTeamHarness(runner)&&validRemoteLimits()&&remoteBillingAcknowledged) {
            val limits = JSONObject().put("maxWallTimeSeconds", wallSeconds.toInt()).put("maxOutputBytes", outputBytes.toInt()).put("maxContributionBytes", contributionBytes.toInt())
            if (fullProject && validRemoteSource()) it.put("remoteProduction", JSONObject().put("mode", dev.pocketengine.features.remote.RemoteProjectAssignments.MODE).put("packageReference", JSONObject().put("id", packageId).put("sha256", packageSha).put("manifestIdentity", packageManifest)).put("limits", limits).put("allowCommands", true).put("sameUserExecutionRiskAcknowledged", remoteCommandsAcknowledged).put("privateSourceTransferAcknowledged", sourceTransferAcknowledged).put("internalInferenceBillingUnboundedAcknowledged", true))
            else if (!fullProject && sourceTaskId.isNotBlank() && snapshotId.isNotBlank()) it.put("remoteProduction", JSONObject().put("sourceTaskId", sourceTaskId).put("snapshotId", snapshotId).put("limits", limits).put("internalInferenceBillingUnboundedAcknowledged", true))
        }
    }
    fun validRemoteSource(): Boolean = if (fullProject) packageId.isNotBlank() && packageSha.matches(Regex("[a-f0-9]{64}")) && packageManifest.matches(Regex("[a-f0-9]{64}")) && remoteCommandsAcknowledged && sourceTransferAcknowledged else sourceTaskId.isNotBlank() && snapshotId.isNotBlank()
    fun validRemoteLimits(): Boolean { val wall=wallSeconds.toIntOrNull()?:return false;val output=outputBytes.toIntOrNull()?:return false;val contribution=contributionBytes.toIntOrNull()?:return false;return wall in 10..900&&output in 1024..196608&&contribution in 1024..(if(fullProject)16777216 else 524288) }
}
internal fun teamLaunchable(team: JSONObject, state: PocketUiState): Boolean = team.optString("mode") != "produce" && team.optJSONArray("members").objects().let { members -> members.isNotEmpty() && members.all { member ->
    when (member.optString("runner", "model")) {
        "model" -> state.modelName.isNotBlank() && when (state.modelRoute) {
            "account" -> state.accountModelAvailable && member.optString("model").let { it.isBlank() || it == state.modelName }
            "api_key" -> state.baseUrl.isNotBlank()
            else -> false
        }
        "phone_agent" -> false // Phone starts use the per-member scope review in the phone workspace.
        else -> {
            val runner = member.optString("runner")
            val saved = state.teamMemberProbes[team.optString("id") + ":" + member.optString("id")]
            externalTeamHarness(runner) && saved?.optString("slot") == member.toString() && saved.optJSONObject("receipt")?.optBoolean("available") == true
                && (!configuredTeamHarness(runner) || saved.optJSONObject("receipt")?.optBoolean("protocolVerified") == true)
                && (runner == "codex_app_server" || member.optBoolean("externalExecutionRiskAcknowledged"))
        }
    }
} }

@Composable
internal fun TeamDefinitionEditor(team: JSONObject, state: PocketUiState, controller: PocketWorldController, close: () -> Unit) {
    var name by remember { mutableStateOf(team.optString("name")) }
    var mode by remember { mutableStateOf(team.optString("mode", "debate")) }
    var evidenceDiscussion by remember { mutableStateOf(!team.has("id") || team.optString("discussionFormat") == dev.pocketengine.features.teams.TeamEvidenceDiscussion.FORMAT) }
    var slots by remember {
        mutableStateOf(team.optJSONArray("members").objects().mapIndexed { index, member -> val remote=member.optJSONObject("remoteProduction");val limits=remote?.optJSONObject("limits");AgentSlotDraft(member.optString("id", "member-" + index), member.optString("name"), member.optString("role"), member.optString("model"), member.optString("runner", "model"), member.optJSONObject("device")?.optString("profileId").orEmpty(), member.optJSONObject("device")?.optString("environmentId").orEmpty(), member.optJSONArray("writeFiles").let{files->(0 until (files?.length()?:0)).joinToString("\n"){files!!.getString(it)}},remote?.optString("sourceTaskId").orEmpty(),remote?.optString("snapshotId").orEmpty(),limits?.optInt("maxWallTimeSeconds",120)?.toString()?:"120",limits?.optInt("maxOutputBytes",65536)?.toString()?:"65536",limits?.optInt("maxContributionBytes",131072)?.toString()?:"131072",remote?.optBoolean("internalInferenceBillingUnboundedAcknowledged")==true, remote?.optString("mode")==dev.pocketengine.features.remote.RemoteProjectAssignments.MODE, remote?.optJSONObject("packageReference")?.optString("id").orEmpty(), remote?.optJSONObject("packageReference")?.optString("sha256").orEmpty(), remote?.optJSONObject("packageReference")?.optString("manifestIdentity").orEmpty(), remote?.optBoolean("sameUserExecutionRiskAcknowledged")==true, remote?.optBoolean("privateSourceTransferAcknowledged")==true,member.optBoolean("externalExecutionRiskAcknowledged"), member.optString("modeId"), if(member.optString("runner")=="phone_agent")member.optJSONObject("device")?.toString().orEmpty() else "", member.optString("assignment"), member.optJSONArray("dependsOn").let { values -> (0 until (values?.length() ?: 0)).map { values!!.getString(it) } }) }.ifEmpty { listOf(AgentSlotDraft("member-0", "成员 1", "", "", "model"), AgentSlotDraft("member-1", "成员 2", "", "", "model")) })
    }
    var leader by remember { mutableStateOf(team.optString("leaderId", slots.first().id)) }
    var submitted by remember { mutableStateOf<String?>(null) }
    val busy = "teams.save" in state.moduleBusy
    var externalReady by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    val remoteProductionConfigured = state.feature("teams").optBoolean("selectedFileRemoteProductionConfigured")
    fun update(index: Int, next: AgentSlotDraft) {
        val old = slots[index]
        if (listOf(old.runner,old.profileId,old.environmentId,old.model,old.modeId,old.fullProject) != listOf(next.runner,next.profileId,next.environmentId,next.model,next.modeId,next.fullProject)) externalReady = externalReady + (next.id to false)
        slots = slots.mapIndexed { n, current -> if (n == index) next else current }
    }
    LaunchedEffect(state.moduleResults["teams.save"]) { if (submitted != null && state.moduleResultInputs["teams.save"]?.optString("_requestId") == submitted) close() }
    AlertDialog(onDismissRequest = { if (!busy) close() }, containerColor = MaterialTheme.colorScheme.surface, title = { Text(if (team.has("id")) "编辑团队" else "创建团队") }, text = {
        Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("团队名称") }, singleLine = true)
            Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) { FilterChip(mode == "collaborate", { mode = "collaborate" }, label = { Text("共同分析") }); FilterChip(mode == "debate", { mode = "debate" }, label = { Text("取证与讨论") }); FilterChip(mode=="produce",{mode="produce"},enabled=state.feature("teams").optBoolean("writeAssignmentsConfigured"),label={Text("文件协作")}) }
            if(mode=="debate") {
                Row(verticalAlignment=Alignment.CenterVertically) { Checkbox(evidenceDiscussion,{evidenceDiscussion=it},enabled=!busy); Text("独立观点 → 具体质疑 → 原作者回应 → 条件结论",style=MaterialTheme.typography.bodySmall) }
                TeamCaption(if(evidenceDiscussion)"由实际成员分别发言，引用原主张与证据；缺失回应或预算耗尽会明确留作未决，不让协调者代演。本片接模型成员，不包含外部成员协议或执行授权。" else "原简短分析：成员独立发言后汇总；不冒称已完成逐条质疑与原作者回应。")
            }
            if(mode=="produce")TeamCaption("每名成员只修改选定的互斥文件。本机成员在一次隔离草稿授权内自主编辑；电脑执行器需另明确批准独立job和计费未知，可显选既有文字快照或准确完整源项目包。完整包使用真实独立Git新基线，远端命令另审；手机唯一整合者仍需另审本机组合检查与打包。任何副本都不冒OS读隔离。请从已选项目的原任务交给此团队。")
            TeamCaption("角色、模型、执行器和执行位置分别设置。保存只影响新任务，已经运行的任务保留自身配置。")
            slots.forEachIndexed { index, slot ->
                Text("成员 " + (index + 1), fontSize = 14.sp)
                OutlinedTextField(slot.name, { update(index, slot.copy(name = it)) }, label = { Text("名称") }, singleLine = true)
                OutlinedTextField(slot.role, { update(index, slot.copy(role = it)) }, label = { Text("职责与工作要求") }, minLines = 2, maxLines = 5)
                if(mode=="produce")OutlinedTextField(slot.writeFiles,{update(index,slot.copy(writeFiles=it))},label={Text("负责的项目文件，每行一个")},placeholder={Text("src/example.txt\nREADME.md")},minLines=2,maxLines=5,supportingText={Text("1至8个项目相对路径；不同成员不能选择同一文件。")})
                if (slot.runner == "model") OutlinedTextField(slot.model, { update(index, slot.copy(model = it)) }, label = { Text("模型服务 ID（留空使用主模型）") }, singleLine = true)
                Text("成员怎样工作", fontSize = 12.sp)
                Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(slot.runner == "model", { update(index, slot.copy(runner = "model", externalExecutionRiskAcknowledged = false)) }, enabled = !busy); Text("由手机连接模型服务分析", fontSize = 12.sp) }
                Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(slot.runner != "model" && slot.runner != "phone_agent", { update(index, slot.copy(runner = "computer", model = "", modeId = "", externalExecutionRiskAcknowledged = false)) }, enabled = !busy); Text("在电脑上使用执行器工作", fontSize = 12.sp) }
                if (slot.runner == "model") TeamCaption("模型负责推理，手机负责调度；工具与会话由本项目管理。")
                else if (slot.runner == "phone_agent") {
                    TeamCaption("保留原手机目标与应用范围。具体手机和预算在手机团队工作区核对；这名成员不会被转换为电脑执行器。")
                    OutlinedTextField(slot.phoneAssignment, { update(index, slot.copy(phoneAssignment = it)) }, label = { Text("手机上的具体分工") }, minLines = 2, maxLines = 5)
                } else {
                    TeamHarnessFields(slot, state, controller, busy, mode == "produce", { update(index, it) }) { ready -> if (externalReady[slot.id] != ready) externalReady = externalReady + (slot.id to ready) }
                    if(mode=="produce"){
                        Row { FilterChip(!slot.fullProject, { update(index, slot.copy(fullProject=false, remoteBillingAcknowledged=false)) }, label={Text("所选文字副本")}); FilterChip(slot.fullProject, { update(index,slot.copy(fullProject=true,remoteBillingAcknowledged=false,remoteCommandsAcknowledged=false,sourceTransferAcknowledged=false)) }, label={Text("完整源项目 + Git")}) }
                        if(slot.fullProject) {
                            TeamCaption("手机模型只调度，所选电脑执行器才是作者。完整固定包（含未列作输出的代码）会外传供读；只导回明确批准的写路径。新Git基线不含原Git历史，cwd不是OS隔离。")
                            val packages=state.feature("workspace").optJSONArray("artifacts").objects().filter{it.optString("kind")=="project_bundle"&&it.optString("mode")=="source_project"&&it.optString("availability")!="retired"}
                            if(packages.isEmpty()) TeamCaption("没有已冻结完整源项目包；请先在原项目保存准确源包。不能用任意ZIP/旧快照冒基线。")
                            packages.forEach { artifact-> Row(verticalAlignment=Alignment.CenterVertically) { RadioButton(slot.packageId==artifact.getString("id")&&slot.packageSha==artifact.getString("sha256"), { update(index,slot.copy(packageId=artifact.getString("id"),packageSha=artifact.getString("sha256"),packageManifest=artifact.getString("manifestIdentity"),sourceTransferAcknowledged=false,remoteCommandsAcknowledged=false,remoteBillingAcknowledged=false)) }); Text("项目 ${artifact.optString("projectId").take(8)} · 版本 ${artifact.optInt("versionSequence")} · ${artifact.optInt("fileCount")}文件 · ${artifact.optString("sha256").take(12)}",fontSize=12.sp) } }
                            Row(verticalAlignment=Alignment.CenterVertically){Checkbox(slot.sourceTransferAcknowledged,{update(index,slot.copy(sourceTransferAcknowledged=it))});Text("我明确同意将这个准确完整源包外传到所选电脑；不是只传负责的文件。",fontSize=12.sp)}
                            Row(verticalAlignment=Alignment.CenterVertically){Checkbox(slot.remoteCommandsAcknowledged,{update(index,slot.copy(remoteCommandsAcknowledged=it))});Text("我允许远端作者提出命令；每次原请求另审。同用户命令可能访问该用户可访问文件/网络，不是checkout沙箱。",fontSize=12.sp)}
                        } else {
                        TeamCaption("手机模型只负责调度，所选电脑执行器才是作者。请选择原任务已冻结的同电脑/同环境文字文件快照；开始前会逐文件核真实hash，不复制整项目。")
                        val snapshots=state.tasks.flatMap{task->task.raw.optJSONArray("remoteResultSnapshots").objects().map{snapshot->task to snapshot}}.filter{(_,snapshot)->val source=snapshot.optJSONObject("source");source!=null&&source.optString("profileId")==slot.profileId&&source.optString("environmentId")==slot.environmentId&&snapshot.optString("status")=="completed"}
                        if(snapshots.isEmpty())TeamCaption("当前已加载任务没有匹配快照。请先到原电脑任务选择所需文件并冻结原件，再返回本团队。")
                        snapshots.forEach{(task,snapshot)->Row(verticalAlignment=Alignment.CenterVertically){RadioButton(slot.sourceTaskId==task.id&&slot.snapshotId==snapshot.optString("snapshotId"),{update(index,slot.copy(sourceTaskId=task.id,snapshotId=snapshot.getString("snapshotId"),remoteBillingAcknowledged=false))});Text(task.raw.optString("goal").take(80)+" · "+snapshot.optJSONArray("files").objects().joinToString{it.optString("path")},fontSize=12.sp)}}
                        }
                        OutlinedTextField(slot.wallSeconds,{update(index,slot.copy(wallSeconds=it))},label={Text("该job最长运行秒数（10–900）")},singleLine=true)
                        OutlinedTextField(slot.outputBytes,{update(index,slot.copy(outputBytes=it))},label={Text("该job输出字节上限（1024–196608）")},singleLine=true)
                        OutlinedTextField(slot.contributionBytes,{update(index,slot.copy(contributionBytes=it))},label={Text(if(slot.fullProject) "冻结贡献字节上限（1024–16777216）" else "冻结贡献字节上限（1024–524288）")},singleLine=true)
                        Row(verticalAlignment=Alignment.CenterVertically){Checkbox(slot.remoteBillingAcknowledged,{update(index,slot.copy(remoteBillingAcknowledged=it))});Text("我理解内部模型次数、token和费用不能硬限；开始后还须批准本次独立远端job范围。",fontSize=12.sp)}
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(leader == slot.id, { leader = slot.id }); Text("由此成员担任 Leader", fontSize = 12.sp); if (slots.size > (if(mode=="debate"&&evidenceDiscussion) 2 else 1)) TextButton(onClick = { slots = slots.filterNot { it.id == slot.id }; if (leader == slot.id) leader = slots.first().id }) { Text("移除", fontSize = 11.sp) } }
                HorizontalDivider(color = Line)
            }
            if (slots.size < 4) TextButton(onClick = { val next = (0..20).first { n -> slots.none { it.id == "member-" + n } }; slots = slots + AgentSlotDraft("member-" + next, "成员 " + (slots.size + 1), "", "", "model") }) { Text("增加成员") }
            state.moduleErrors["teams.save"]?.let { Text(it, fontSize = 12.sp, color = Danger) }
        }
    }, confirmButton = { Button(onClick = { val input = JSONObject().put("name", name.trim()).put("mode", mode).put("discussionFormat",if(mode=="debate"&&evidenceDiscussion)dev.pocketengine.features.teams.TeamEvidenceDiscussion.FORMAT else "classic").put("leaderId", leader).put("members", JSONArray().also { array -> slots.forEach { array.put(it.json()) } }); if (team.has("id")) input.put("id", team.getString("id")); submitted = controller.requestFeature("teams", "save", input) }, enabled = !busy && (mode!="debate" || !evidenceDiscussion || slots.size>=2&&slots.all{it.runner=="model"}) && (mode!="produce" || slots.all{it.writeFiles.lines().count{line->line.isNotBlank()} in 1..8&&(it.runner=="model"||externalTeamHarness(it.runner)&&remoteProductionConfigured&&externalReady[it.id]==true&&it.validRemoteSource()&&it.validRemoteLimits()&&it.remoteBillingAcknowledged)}) && name.isNotBlank() && name.length <= 80 && slots.all { it.name.isNotBlank() && it.name.length <= 48 && it.role.isNotBlank() && it.role.length <= 1200 && (it.runner == "model" || it.runner == "phone_agent" && it.phoneDevice.isNotBlank() && it.phoneAssignment.isNotBlank() || externalTeamHarness(it.runner) && externalReady[it.id] == true && (it.runner == "codex_app_server" || it.externalExecutionRiskAcknowledged) && it.profileId.isNotBlank() && it.environmentId.isNotBlank()) }) { Text(if (busy) "保存中" else "保存团队") } }, dismissButton = { TextButton(onClick = close, enabled = !busy) { Text("取消") } })
}
