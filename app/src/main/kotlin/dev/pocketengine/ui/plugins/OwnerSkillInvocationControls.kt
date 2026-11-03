package dev.pocketengine.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject

/** Exact owner choice in the original task page; JSON here is input, never execution authority. */
@Composable
internal fun OwnerSkillInvocationControls(snapshot: JSONObject, busy: Boolean, submit: (JSONObject, Long) -> Unit) {
    val selection = snapshot.optJSONObject("ownerInvocation") ?: return
    val choices = selection.optJSONArray("skills").objects()
    if (choices.isEmpty()) return
    val pin = selection.optString("taskPin")
    var expanded by remember(pin) { mutableStateOf(false) }
    var selectedId by remember(pin) { mutableStateOf("") }
    var choicesOpen by remember(pin) { mutableStateOf(false) }
    var script by remember(pin, selectedId) { mutableStateOf("") }
    var scriptsOpen by remember(pin, selectedId) { mutableStateOf(false) }
    var arguments by remember(pin, selectedId) { mutableStateOf("") }
    var timeout by remember(pin, selectedId) { mutableStateOf("30") }
    var submitted by remember(snapshot) { mutableStateOf(false) }
    val skill = choices.find { it.optString("id") == selectedId }
    val scripts = skill?.optJSONArray("scripts")
    val seconds = timeout.toIntOrNull()
    val available = selection.optBoolean("available") && snapshot.optJSONObject("configuration")?.optBoolean("configured") == true
    HorizontalDivider(color = Line)
    TextButton(onClick = { expanded = !expanded }, enabled = !busy && !submitted) { Text(if (expanded) "收起主人选择" else "由我选择一次脚本") }
    if (!expanded) return
    Text("在这项原任务发起", fontSize = 14.sp, fontWeight = FontWeight.Medium)
    Text("可选择仅供主人使用的已启用技能。这里只提交准确脚本与参数；随后仍需审阅本次执行计划、依赖和输入输出范围。", color = SecondaryInk, fontSize = 12.sp, lineHeight = 21.sp)
    if (!available) Text("请先核对原任务状态、未决操作与执行位置，再明确选择。不会恢复旧请求或替你重新启用技能。", color = SecondaryInk, fontSize = 12.sp, lineHeight = 21.sp)
    Box {
        OutlinedButton(onClick = { choicesOpen = true }, enabled = !busy && !submitted) { Text(skill?.optString("name") ?: "选择已启用技能") }
        DropdownMenu(expanded = choicesOpen, onDismissRequest = { choicesOpen = false }) {
            choices.forEach { row -> DropdownMenuItem(text = { Text(row.optString("name") + " · 修订 " + row.optInt("revision")) }, onClick = { selectedId = row.getString("id"); choicesOpen = false }) }
        }
    }
    if (skill == null) return
    SelectionContainer { Text("来源版本 ${skill.optString("sourceVersion")}\n内容摘要 ${skill.optString("digest")}", color = SecondaryInk, fontSize = 12.sp, lineHeight = 20.sp) }
    Box {
        OutlinedButton(onClick = { scriptsOpen = true }, enabled = !busy && !submitted) { Text(script.ifEmpty { "选择 scripts/ 中的脚本" }) }
        DropdownMenu(expanded = scriptsOpen, onDismissRequest = { scriptsOpen = false }) {
            if (scripts != null) for (index in 0 until scripts.length()) {
                val path = scripts.getString(index)
                DropdownMenuItem(text = { Text(path) }, onClick = { script = path; scriptsOpen = false })
            }
        }
    }
    OutlinedTextField(arguments, { arguments = it }, label = { Text("参数：每行一个，留空表示无参数") }, supportingText = { Text("每行原样作为一个参数，不拆分空格，也不执行命令字符串。") }, enabled = !busy && !submitted, modifier = Modifier.fillMaxWidth(), maxLines = 6)
    OutlinedTextField(timeout, { timeout = it }, label = { Text("执行超时（1–120 秒）") }, enabled = !busy && !submitted, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedButton(onClick = {
        val argv = JSONArray(); if (arguments.isNotEmpty()) arguments.split('\n').forEach { argv.put(it) }
        val input = JSONObject().put("id", skill.getString("id")).put("revision", skill.getInt("revision")).put("digest", skill.getString("digest"))
            .put("scriptPath", script).put("arguments", argv).put("timeoutSeconds", seconds).put("maxOutputBytes", 16384)
        submitted = true; submit(input, skill.optLong("executionGeneration", 0))
    }, enabled = available && !busy && !submitted && script.isNotEmpty() && seconds != null && seconds in 1..120) { Text(if (submitted) "本次选择已提交，请核对原任务" else "在原任务准备本次审阅") }
}
