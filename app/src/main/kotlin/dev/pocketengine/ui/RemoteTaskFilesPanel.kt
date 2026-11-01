package dev.pocketengine.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

/** One original job's bounded files; existing Book/viewer and snapshot continuation remain the owners. */
@Composable
internal fun RemoteTaskFilesPanel(
    snapshot: JSONObject, canAct: Boolean,
    send: (String, JSONObject) -> Unit
) {
    if (snapshot.optBoolean("teamRemoteWriter")) return
    val source = snapshot.optJSONObject("source") ?: JSONObject()
    val selected = snapshot.optJSONArray("selectedResultFiles").objects()
    val directory = snapshot.optJSONObject("resultDirectory")
    var path by remember(source.optString("jobOperationId")) { mutableStateOf("") }
    var confirm by remember(source.optString("jobOperationId")) { mutableStateOf<Pair<String, String>?>(null) }
    val canBrowse = canAct && snapshot.optBoolean("canCaptureSnapshot")
    HorizontalDivider(color = Line)
    Text("原任务成果文件", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    Text("只列当前目录；选择文件后才读取内容和版本。冻结后可存入手机原件，再按同一版本续改。", fontSize = 14.sp, lineHeight = 22.sp, color = SecondaryInk)
    if (source.optString("executorStatus") in setOf("completed", "failed", "cancelled")) {
        OutlinedTextField(path, { path = it }, label = { Text("工作区相对目录（留空为根目录）") }, maxLines = 3, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { send("listResultFiles", JSONObject().put("path", path).put("offset", 0)) }, enabled = canBrowse) { Text("读取这个目录") }
            if (directory?.optString("path").orEmpty().isNotEmpty()) TextButton(onClick = {
                path = directory!!.optString("path").substringBeforeLast("/", "")
                send("listResultFiles", JSONObject().put("path", path).put("offset", 0))
            }, enabled = canBrowse) { Text("上一级") }
        }
    } else Text("原 job 结算后再查看文件；失联或未知时只核对原执行，不另建任务。", fontSize = 14.sp, lineHeight = 22.sp, color = SecondaryInk)
    directory?.let { page ->
        Text("当前目录：" + page.optString("path").ifEmpty { "工作区根目录" }, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Column(Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
            page.optJSONArray("files").objects().forEach { file ->
                val selectedFile = selected.any { it.optString("path") == file.optString("path") }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                        Text(file.optString("name"), fontSize = 14.sp)
                        Text(if (file.optString("kind") == "directory") "目录" else "${file.optLong("bytes")} 字节 · ${file.optString("kind")}", fontSize = 13.sp, color = SecondaryInk)
                        if (!file.optBoolean("selectable") && file.optString("kind") != "directory") Text("不能作为本次有界成果文件", fontSize = 13.sp, color = SecondaryInk)
                    }
                    if (file.optString("kind") == "directory") TextButton(onClick = {
                        path = file.optString("path"); send("listResultFiles", JSONObject().put("path", path).put("offset", 0))
                    }, enabled = canBrowse) { Text("进入") }
                    else TextButton(onClick = { send("selectResultFile", JSONObject().put("path", file.optString("path"))) }, enabled = canBrowse && file.optBoolean("selectable")) {
                        Text(if (selectedFile) "移除选择" else "读取并选择")
                    }
                }
            }
        }
        if (!page.isNull("nextOffset")) TextButton(onClick = {
            send("listResultFiles", JSONObject().put("path", page.optString("path")).put("offset", page.getInt("nextOffset")))
        }, enabled = canBrowse) { Text("沿同目录版本读取下一页") }
        Text(when {
            page.optBoolean("inspectionLimitReached") -> "已到检视上限；没有读完所有目录项，请进入更具体的目录。"
            page.optBoolean("listingComplete") -> "当前目录本轮已列到末尾；未递归读取子目录。"
            else -> "这里只是当前一页，未读取其他文件内容。"
        }, fontSize = 13.sp, lineHeight = 20.sp, color = SecondaryInk)
    }
    if (selected.isNotEmpty()) {
        Text("已读取并选择 ${selected.size} 个文件 · ${selected.sumOf { it.optLong("bytes") }} 字节", fontSize = 14.sp, fontWeight = FontWeight.Medium)
        selected.forEach { Text("${it.optString("path")} · ${it.optLong("bytes")} 字节", fontSize = 14.sp) }
        Button(onClick = { confirm = snapshot.optString("reviewId") to (snapshot.optJSONArray("selectedResultFiles")?.toString() ?: "[]") }, enabled = canBrowse) { Text("核对并冻结所选版本") }
    }
    confirm?.let { reviewed ->
        val same = reviewed.first == snapshot.optString("reviewId") && reviewed.second == snapshot.optJSONArray("selectedResultFiles")?.toString()
        AlertDialog(onDismissRequest = { confirm = null }, title = { Text("冻结这些文件的已阅版本") }, text = {
            Column(Modifier.heightIn(max = 430.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("目标：${source.optString("profileId")} / ${source.optString("environmentId")}", fontSize = 14.sp)
                Text("原 job：${source.optString("jobId")}", fontSize = 13.sp)
                selected.forEach { file -> SelectionContainer { Text("${file.optString("path")}\n${file.optLong("bytes")} 字节\nSHA-256 ${file.optString("sha256")}", fontSize = 13.sp, lineHeight = 20.sp) } }
                Text("只保存上述范围；版本漂移会拒绝，不会悄悄改成新内容。快照不证明全部内容由该 job 创作，也不批准命令、自动导回或外发。", fontSize = 14.sp, lineHeight = 22.sp)
            }
        }, confirmButton = { Button(onClick = { send("captureSelectedSnapshot", JSONObject()); confirm = null }, enabled = canBrowse && same) { Text("确认冻结此版本") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("返回核对") } })
    }
}
