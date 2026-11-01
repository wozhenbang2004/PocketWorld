package dev.pocketengine.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.util.UUID
import org.json.JSONObject

/** Editing this roster changes this actual run, not only the next-run definition template. */
@Composable internal fun TeamRunMemberControls(run: JSONObject, busy: Boolean, submit: (String, JSONObject) -> String) {
    var expanded by rememberSaveable(run.getString("id")) { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    val all = run.optJSONArray("members").objects()
    fun action(member: JSONObject, kind: String, extra: JSONObject = JSONObject()) {
        runCatching { extra.put("runId", run.getString("id")).put("memberId", member.getString("id")).put("expectedConfigRevision", member.optInt("configRevision", 1)).put("action", kind); submit("configureMember", extra) }.onFailure { notice = it.message }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起本轮成员配置" else "管理本轮成员 · 改名 / 模型 / 退出") }
        if (!expanded) return@Column
        TeamCaption("稳定ID标识成员，名字只是显示。隐藏不停止工作；暂停可保留原身份，退出不自动转投旧Reply。换模型会撤销原执行的新派发并暂停，未知效果先核对，资料范围需重审；不会追加额度或自动继续。参与者变化使后续质疑/结论失效，已有原件保留。")
        all.forEach { member -> key(member.getString("id")) {
            var name by rememberSaveable(member.getString("id"), member.optInt("configRevision", 1)) { mutableStateOf(member.optString("name")) }
            var model by rememberSaveable(member.getString("id"), member.optInt("configRevision", 1)) { mutableStateOf(member.optString("model")) }
            var confirmExit by remember { mutableStateOf(false) }
            var leader by rememberSaveable(member.getString("id")) { mutableStateOf(all.firstOrNull { it.optString("id") != member.optString("id") && !it.optBoolean("exited") }?.optString("id").orEmpty()) }
            Text("${member.optString("name")} · ${member.optString("id")}", style = MaterialTheme.typography.titleSmall)
            TeamCaption("${teamMemberStatus(member.optString("status"))} · 执行 g${member.optInt("generation")} · 配置 r${member.optInt("configRevision", 1)} · 资料身份 r${member.optInt("identityRevision", 1)}")
            if (member.optBoolean("exited")) TeamCaption("已退出；历史身份/消息/贡献保留。同名新成员使用全新ID，不继承其资料或Reply。")
            else {
                OutlinedTextField(name, { name = it }, label = { Text("本轮显示名") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = { action(member, "rename", JSONObject().put("name", name)) }, enabled = !busy && name.isNotBlank() && name.length <= 48 && name != member.optString("name")) { Text("保存本轮名字") }
                    TextButton(onClick = { action(member, "visibility", JSONObject().put("hidden", !member.optBoolean("hidden"))) }, enabled = !busy) { Text(if (member.optBoolean("hidden")) "显示此成员（不恢复执行）" else "仅隐藏现场入口（不暂停）") }
                }
                if (member.optString("runner", "model") == "model") {
                    OutlinedTextField(model, { model = it }, label = { Text("当前已配置提供方的模型名") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                    TextButton(onClick = { action(member, "model", JSONObject().put("model", model)) }, enabled = !busy && model.isNotBlank() && model.length <= 200 && model != member.optString("model")) { Text("切换下次执行模型并暂停原成员") }
                }
                if (run.optString("leaderId") == member.optString("id")) {
                    TeamCaption("当前协调者退出时明确选择接任者；这不授予该成员新的工具权限：")
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { all.filter { it.optString("id") != member.optString("id") && !it.optBoolean("exited") }.forEach { next -> FilterChip(leader == next.optString("id"), { leader = next.getString("id") }, label = { Text(next.optString("name")) }, enabled = !busy) } }
                }
                Row { Checkbox(confirmExit, { confirmExit = it }, enabled = !busy); Text("我确认此成员退出本轮；不删除成果，不把未知写入当已撤销") }
                TextButton(onClick = { action(member, "exit", JSONObject().put("newLeaderId", if (run.optString("leaderId") == member.optString("id")) leader else "")); confirmExit = false }, enabled = confirmExit && !busy) { Text("退出本轮并保留原身份历史") }
            }
            HorizontalDivider()
        } }
        if (all.count { !it.optBoolean("exited") } < 4 && all.size < 16) {
            var newId by rememberSaveable(run.getString("id")) { mutableStateOf("member_" + UUID.randomUUID().toString().replace('-', '_')) }
            var name by rememberSaveable(run.getString("id")) { mutableStateOf("") }
            var role by rememberSaveable(run.getString("id")) { mutableStateOf("") }
            var model by rememberSaveable(run.getString("id")) { mutableStateOf("") }
            Text("增加新稳定身份", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(name, { name = it }, label = { Text("名字，可与历史退出者同名") }, enabled = !busy, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(role, { role = it }, label = { Text("明确职责 / 分工") }, minLines = 2, enabled = !busy, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(model, { model = it }, label = { Text("模型名（空白用当前提供方默认）") }, enabled = !busy, modifier = Modifier.fillMaxWidth())
            TeamCaption("新ID $newId；新增后保留暂停，预算不补满。制作成员仍须本轮准确工作项/文件授权，不能以加入团队自动获得执行权。")
            OutlinedButton(onClick = { runCatching { val selected = JSONObject().put("id", newId).put("name", name).put("role", role).put("assignment", role).put("runner", "model").put("device", "phone"); if (model.isNotBlank()) selected.put("model", model); submit("configureMember", JSONObject().put("runId", run.getString("id")).put("action", "add").put("expectedRevision", run.getLong("revision")).put("member", selected)); newId = "member_" + UUID.randomUUID().toString().replace('-', '_') }.onFailure { notice = it.message } }, enabled = !busy && name.isNotBlank() && name.length <= 48 && role.isNotBlank() && role.length <= 1200 && model.length <= 200) { Text("加入新的暂停成员，不继承旧Reply/私域") }
        }
        TeamCaption("原本轮模型预算 ${run.optInt("callsUsed")} / ${run.optInt("callLimit")}；工具 ${run.optInt("toolCallsUsed")} / ${run.optInt("toolCallLimit")}。配置变化不补预算，达到上限会明确停下。")
        notice?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
