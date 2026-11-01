package dev.pocketengine.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

/** Install identity and current permission digest come from the same native plugin snapshot. */
@Composable
internal fun McpToolPermissions(plugin: JSONObject, snapshot: JSONObject, busy: Boolean, requiredTool: String?, request: (String, JSONObject) -> String) {
    var selected by remember(plugin.optString("installationId")) { mutableStateOf<JSONObject?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    val permissions = plugin.optJSONArray("grants")
    val canManage = snapshot.optJSONObject("nativeCapabilities")?.optBoolean("setToolGrantReviewed") == true
    plugin.optJSONObject("manifest")?.optJSONArray("tools").objects().filter { requiredTool == null || it.optString("name") == requiredTool }.forEach { tool ->
        val name = tool.optString("name")
        val granted = (0 until (permissions?.length() ?: 0)).any { permissions?.optString(it) == "tool:$name" }
        TextButton(onClick = { selected = JSONObject(plugin.toString()).put("selectedTool",tool).put("nextGranted",!granted); failure = null }, enabled = !busy && canManage) { Text("$name · ${if (granted) "已允许工具，可撤销" else "审阅并允许这个工具"}", fontSize = 12.sp) }
    }
    failure?.let { Text(it, color = Danger, fontSize = 12.sp) }
    selected?.let { frozen ->
        val tool = frozen.getJSONObject("selectedTool")
        val latest = snapshot.optJSONArray("plugins").objects().find { it.optString("id") == frozen.optString("id") }
        val current = latest?.optString("installationId") == frozen.optString("installationId") && latest?.optString("grantsDigest") == frozen.optString("grantsDigest") && frozen.optString("grantsDigest").matches(Regex("[a-f0-9]{64}"))
        AlertDialog(onDismissRequest = { selected = null }, containerColor = Color.White, title = { Text(if (frozen.getBoolean("nextGranted")) "允许这个工具？" else "撤销这个工具权限？") }, text = {
            Column(Modifier.heightIn(max = 430.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(frozen.optString("id") + " · " + tool.optString("name"), fontSize = 15.sp)
                Text("版本 ${frozen.optJSONObject("manifest")?.optString("version")}\n${tool.optString("endpoint")}", fontSize = 12.sp, lineHeight = 21.sp)
                Text("仅改变这个安装版本的工具权限。执行敏感动作时，原任务仍需按具体参数批准；远端名称与说明不会授予权限。撤销会停止继续等待中的调用，已发生的效果需核对。", fontSize = 13.sp, lineHeight = 21.sp)
                SelectionContainer { Text(tool.optString("description") + "\n" + (tool.optJSONObject("schema")?.toString(2) ?: ""), fontSize = 12.sp, lineHeight = 20.sp) }
                if (!current) Text("安装或权限已变化，请取消后重新读取", color = Danger)
            }
        }, confirmButton = { Button(onClick = {
            val payload = JSONObject().put("id",frozen.getString("id")).put("expectedInstallationId",frozen.getString("installationId")).put("expectedGrantsDigest",frozen.getString("grantsDigest")).put("tool",tool.getString("name")).put("granted",frozen.getBoolean("nextGranted"))
            if (request("setToolGrantReviewed",payload).isBlank()) failure = "宿主未接收工具权限请求，请重新读取后确认"
            selected = null
        }, enabled = current && !busy && canManage) { Text(if (frozen.getBoolean("nextGranted")) "允许这个工具" else "确认撤销") } }, dismissButton = { TextButton(onClick = { selected = null }) { Text("取消") } })
    }
}
