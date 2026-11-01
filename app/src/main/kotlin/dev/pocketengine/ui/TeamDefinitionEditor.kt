package dev.pocketengine.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject

internal data class AgentSlotDraft(val id: String, val name: String, val role: String, val model: String, val runner: String, val profileId: String = "", val environmentId: String = "", val writeFiles: String = "", val sourceTaskId: String = "", val snapshotId: String = "", val wallSeconds: String = "120", val outputBytes: String = "65536", val contributionBytes: String = "131072", val remoteBillingAcknowledged: Boolean = false) {
    fun json(): JSONObject = JSONObject().put("id", id).put("name", name).put("role", role).put("model", model).put("runner", runner).put("device", if (runner == "model") "phone" else JSONObject().put("profileId", profileId).put("environmentId", environmentId)).also {
        if(writeFiles.isNotBlank())it.put("writeFiles",JSONArray(writeFiles.lines().map { it.trim() }.filter { it.isNotBlank() }))
        if(runner=="codex_app_server"&&sourceTaskId.isNotBlank()&&snapshotId.isNotBlank()&&validRemoteLimits()&&remoteBillingAcknowledged)it.put("remoteProduction",JSONObject().put("sourceTaskId",sourceTaskId).put("snapshotId",snapshotId).put("limits",JSONObject().put("maxWallTimeSeconds",wallSeconds.toInt()).put("maxOutputBytes",outputBytes.toInt()).put("maxContributionBytes",contributionBytes.toInt())).put("internalInferenceBillingUnboundedAcknowledged",remoteBillingAcknowledged))
    }
    fun validRemoteLimits(): Boolean { val wall=wallSeconds.toIntOrNull()?:return false;val output=outputBytes.toIntOrNull()?:return false;val contribution=contributionBytes.toIntOrNull()?:return false;return wall in 10..900&&output in 1024..196608&&contribution in 1024..524288 }
}
internal fun teamLaunchable(team: JSONObject, state: PocketUiState): Boolean = team.optString("mode") != "produce" && team.optJSONArray("members").objects().let { members -> members.isNotEmpty() && members.all { member ->
    when (member.optString("runner", "model")) {
        "model" -> state.baseUrl.isNotBlank() && state.modelName.isNotBlank()
        "codex_app_server" -> {
            val saved = state.teamMemberProbes[team.optString("id") + ":" + member.optString("id")]
            saved?.optString("slot") == member.toString() && saved?.optJSONObject("receipt")?.optBoolean("available") == true
        }
        else -> false
    }
} }

@Composable
internal fun TeamDefinitionEditor(team: JSONObject, state: PocketUiState, controller: PocketWorldController, close: () -> Unit) {
    var name by remember { mutableStateOf(team.optString("name")) }
    var mode by remember { mutableStateOf(team.optString("mode", "debate")) }
    var evidenceDiscussion by remember { mutableStateOf(!team.has("id") || team.optString("discussionFormat") == dev.pocketengine.features.teams.TeamEvidenceDiscussion.FORMAT) }
    var slots by remember {
        mutableStateOf(team.optJSONArray("members").objects().mapIndexed { index, member -> val remote=member.optJSONObject("remoteProduction");val limits=remote?.optJSONObject("limits");AgentSlotDraft(member.optString("id", "member-" + index), member.optString("name"), member.optString("role"), member.optString("model"), member.optString("runner", "model"), member.optJSONObject("device")?.optString("profileId").orEmpty(), member.optJSONObject("device")?.optString("environmentId").orEmpty(), member.optJSONArray("writeFiles").let{files->(0 until (files?.length()?:0)).joinToString("\n"){files!!.getString(it)}},remote?.optString("sourceTaskId").orEmpty(),remote?.optString("snapshotId").orEmpty(),limits?.optInt("maxWallTimeSeconds",120)?.toString()?:"120",limits?.optInt("maxOutputBytes",65536)?.toString()?:"65536",limits?.optInt("maxContributionBytes",131072)?.toString()?:"131072",remote?.optBoolean("internalInferenceBillingUnboundedAcknowledged")==true) }.ifEmpty { listOf(AgentSlotDraft("member-0", "成员 1", "", "", "model"), AgentSlotDraft("member-1", "成员 2", "", "", "model")) })
    }
    var leader by remember { mutableStateOf(team.optString("leaderId", slots.first().id)) }
    var submitted by remember { mutableStateOf<String?>(null) }
    val busy = "teams.save" in state.moduleBusy
    val codexConfigured = state.feature("teams").optJSONArray("runnerCapabilities").objects().find { it.optString("id") == "codex_app_server" }?.optBoolean("hostAdapterConfigured") == true
    val remoteProductionConfigured = state.feature("teams").optBoolean("selectedFileRemoteProductionConfigured")
    fun update(index: Int, next: AgentSlotDraft) { slots = slots.mapIndexed { n, old -> if (n == index) next else old } }
    LaunchedEffect(state.moduleResults["teams.save"]) { if (submitted != null && state.moduleResultInputs["teams.save"]?.optString("_requestId") == submitted) close() }
    AlertDialog(onDismissRequest = { if (!busy) close() }, containerColor = Color.White, title = { Text(if (team.has("id")) "编辑团队" else "创建团队") }, text = {
        Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("团队名称") }, singleLine = true)
            Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) { FilterChip(mode == "collaborate", { mode = "collaborate" }, label = { Text("共同分析") }); FilterChip(mode == "debate", { mode = "debate" }, label = { Text("取证与讨论") }); FilterChip(mode=="produce",{mode="produce"},enabled=state.feature("teams").optBoolean("writeAssignmentsConfigured"),label={Text("文件协作")}) }
            if(mode=="debate") {
                Row(verticalAlignment=Alignment.CenterVertically) { Checkbox(evidenceDiscussion,{evidenceDiscussion=it},enabled=!busy); Text("独立观点 → 具体质疑 → 原作者回应 → 条件结论",style=MaterialTheme.typography.bodySmall) }
                TeamCaption(if(evidenceDiscussion)"由实际成员分别发言，引用原主张与证据；缺失回应或预算耗尽会明确留作未决，不让协调者代演。本片接模型成员，不包含外部成员协议或执行授权。" else "原简短分析：成员独立发言后汇总；不冒称已完成逐条质疑与原作者回应。")
            }
            if(mode=="produce")TeamCaption("每名成员只修改选定的互斥文件。本机成员在一次隔离草稿授权内自主编辑；远端Codex需另明确批准独立job和计费未知，只制作原快照的所选文字文件副本。原项目仍经独审与唯一整合；命令/build/test、全项目Git和OS读隔离尚不支持。请从已选项目的原任务交给此团队。")
            TeamCaption("角色、模型、执行器和执行位置分别设置。保存只影响新任务，已经运行的任务保留自身配置。")
            slots.forEachIndexed { index, slot ->
                Text("成员 " + (index + 1), fontSize = 14.sp)
                OutlinedTextField(slot.name, { update(index, slot.copy(name = it)) }, label = { Text("名称") }, singleLine = true)
                OutlinedTextField(slot.role, { update(index, slot.copy(role = it)) }, label = { Text("职责与工作要求") }, minLines = 2, maxLines = 5)
                if(mode=="produce")OutlinedTextField(slot.writeFiles,{update(index,slot.copy(writeFiles=it))},label={Text("负责的项目文件，每行一个")},placeholder={Text("src/example.txt\nREADME.md")},minLines=2,maxLines=5,supportingText={Text("1至8个项目相对路径；不同成员不能选择同一文件。")})
                OutlinedTextField(slot.model, { update(index, slot.copy(model = it)) }, label = { Text(if(mode=="produce"&&slot.runner=="codex_app_server")"远端Codex作者模型（留空用该执行器默认）" else "模型名称（留空使用默认）") }, singleLine = true)
                Text("执行器", fontSize = 12.sp)
                listOf("model", "codex_app_server", "claude", "kimi").forEach { runner -> Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(slot.runner == runner, { update(index, slot.copy(runner = runner)) }, enabled = !busy && (runner == "model" || runner == "codex_app_server" && (if(mode=="produce")remoteProductionConfigured else codexConfigured))); Text(teamRunnerLabel(runner), fontSize = 12.sp) } }
                if (slot.runner == "model") TeamCaption("由手机接入已配置的模型服务。")
                else if (slot.runner == "codex_app_server") {
                    TeamCaption("选择真实电脑与环境。保存后需实际探测该成员，未验证不能开始任务。")
                    state.feature("remote").optJSONArray("profiles").objects().forEach { profile -> Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(slot.profileId == profile.optString("id"), { update(index, slot.copy(profileId = profile.optString("id"), environmentId = "", sourceTaskId="",snapshotId="",remoteBillingAcknowledged=false)); controller.requestFeature("remote", "environments", JSONObject().put("profileId", profile.optString("id"))) }); Text(profile.optString("name"), fontSize = 12.sp) } }
                    if (slot.profileId.isNotBlank()) {
                        TextButton(onClick = { controller.requestFeature("remote", "environments", JSONObject().put("profileId", slot.profileId)) }, enabled = "remote.environments" !in state.moduleBusy) { Text("读取该电脑的环境", fontSize = 11.sp) }
                        val available = state.moduleResults["remote.environments"].takeIf { state.moduleResultInputs["remote.environments"]?.optString("profileId") == slot.profileId }?.optJSONArray("environments").objects()
                        available.forEach { environment -> Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(slot.environmentId == environment.optString("id"), { update(index, slot.copy(environmentId = environment.optString("id"),sourceTaskId="",snapshotId="",remoteBillingAcknowledged=false)) }); Text(environment.optString("label", environment.optString("id")), fontSize = 12.sp) } }
                        if (slot.environmentId.isNotBlank()) TeamCaption("选中环境：" + slot.environmentId)
                    }
                    if(mode=="produce"){
                        TeamCaption("手机模型只负责调度，远端Codex才是作者。请选择原任务已冻结的同电脑/同环境文字文件快照；开始前会逐文件核真实hash，不复制整项目。")
                        val snapshots=state.tasks.flatMap{task->task.raw.optJSONArray("remoteResultSnapshots").objects().map{snapshot->task to snapshot}}.filter{(_,snapshot)->val source=snapshot.optJSONObject("source");source!=null&&source.optString("profileId")==slot.profileId&&source.optString("environmentId")==slot.environmentId&&snapshot.optString("status")=="completed"}
                        if(snapshots.isEmpty())TeamCaption("当前已加载任务没有匹配快照。请先到原电脑任务选择所需文件并冻结原件，再返回本团队。")
                        snapshots.forEach{(task,snapshot)->Row(verticalAlignment=Alignment.CenterVertically){RadioButton(slot.sourceTaskId==task.id&&slot.snapshotId==snapshot.optString("snapshotId"),{update(index,slot.copy(sourceTaskId=task.id,snapshotId=snapshot.getString("snapshotId"),remoteBillingAcknowledged=false))});Text(task.raw.optString("goal").take(80)+" · "+snapshot.optJSONArray("files").objects().joinToString{it.optString("path")},fontSize=12.sp)}}
                        OutlinedTextField(slot.wallSeconds,{update(index,slot.copy(wallSeconds=it))},label={Text("该job最长运行秒数（10–900）")},singleLine=true)
                        OutlinedTextField(slot.outputBytes,{update(index,slot.copy(outputBytes=it))},label={Text("该job输出字节上限（1024–196608）")},singleLine=true)
                        OutlinedTextField(slot.contributionBytes,{update(index,slot.copy(contributionBytes=it))},label={Text("冻结贡献字节上限（1024–524288）")},singleLine=true)
                        Row(verticalAlignment=Alignment.CenterVertically){Checkbox(slot.remoteBillingAcknowledged,{update(index,slot.copy(remoteBillingAcknowledged=it))});Text("我理解内部模型次数、token和费用不能硬限；开始后还须批准本次独立远端job范围。",fontSize=12.sp)}
                    }
                } else TeamCaption("此执行器尚未接入，需切换为已实现的执行器后才能使用。")
                Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(leader == slot.id, { leader = slot.id }); Text("由此成员担任 Leader", fontSize = 12.sp); if (slots.size > (if(mode=="debate"&&evidenceDiscussion) 2 else 1)) TextButton(onClick = { slots = slots.filterNot { it.id == slot.id }; if (leader == slot.id) leader = slots.first().id }) { Text("移除", fontSize = 11.sp) } }
                HorizontalDivider(color = Line)
            }
            if (slots.size < 4) TextButton(onClick = { val next = (0..20).first { n -> slots.none { it.id == "member-" + n } }; slots = slots + AgentSlotDraft("member-" + next, "成员 " + (slots.size + 1), "", "", "model") }) { Text("增加成员") }
            state.moduleErrors["teams.save"]?.let { Text(it, fontSize = 12.sp, color = Danger) }
        }
    }, confirmButton = { Button(onClick = { val input = JSONObject().put("name", name.trim()).put("mode", mode).put("discussionFormat",if(mode=="debate"&&evidenceDiscussion)dev.pocketengine.features.teams.TeamEvidenceDiscussion.FORMAT else "classic").put("leaderId", leader).put("members", JSONArray().also { array -> slots.forEach { array.put(it.json()) } }); if (team.has("id")) input.put("id", team.getString("id")); submitted = controller.requestFeature("teams", "save", input) }, enabled = !busy && (mode!="debate" || !evidenceDiscussion || slots.size>=2&&slots.all{it.runner=="model"}) && (mode!="produce" || slots.all{it.writeFiles.lines().count{line->line.isNotBlank()} in 1..8&&(it.runner=="model"||it.runner=="codex_app_server"&&remoteProductionConfigured&&it.sourceTaskId.isNotBlank()&&it.snapshotId.isNotBlank()&&it.validRemoteLimits()&&it.remoteBillingAcknowledged)}) && name.isNotBlank() && name.length <= 80 && slots.all { it.name.isNotBlank() && it.name.length <= 48 && it.role.isNotBlank() && it.role.length <= 1200 && (it.runner == "model" || it.runner == "codex_app_server" && (if(mode=="produce")remoteProductionConfigured else codexConfigured) && it.profileId.isNotBlank() && it.environmentId.isNotBlank()) }) { Text(if (busy) "保存中" else "保存团队") } }, dismissButton = { TextButton(onClick = close, enabled = !busy) { Text("取消") } })
}
