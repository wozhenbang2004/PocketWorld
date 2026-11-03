package dev.pocketengine.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

@Composable
internal fun NativeSsh(state: PocketUiState, controller: PocketWorldController) {
    val ssh = state.feature("ssh")
    var editing by remember { mutableStateOf<JSONObject?>(null) }
    var selected by remember { mutableStateOf<String?>(null) }
    var trust by remember { mutableStateOf<JSONObject?>(null) }
    var checked by remember { mutableStateOf(false) }
    var workspaceProfileId by remember { mutableStateOf<String?>(null) }
    SshCaption("SSH 直接连接你的主机。主机身份必须先核对；普通 SSH 会话不保证断线后任务继续，可恢复的电脑任务使用 Companion。")
    OutlinedButton(onClick = { editing = JSONObject() }) { Text("添加 SSH 主机") }
    ssh.optJSONArray("profiles").objects().forEach { profile ->
        val id = profile.optString("id")
        Column(Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
            Text(profile.optString("name"), fontSize = 15.sp)
            SshCaption(profile.optString("username") + "@" + profile.optString("host") + ":" + profile.optInt("port", 22))
            Text(profile.optString("workspace"), fontSize = 12.sp, color = SecondaryInk)
            SshCaption(if (profile.optBoolean("hostKeyTrusted")) "主机指纹已固定，连接仍需实际验证" else "尚未信任主机指纹")
            Row {
                TextButton(onClick = { selected = id; controller.requestFeature("ssh", "probeHostKey", JSONObject().put("profileId", id)) }, enabled = "ssh.probeHostKey" !in state.moduleBusy) { Text("查看主机指纹", fontSize = 12.sp) }
                TextButton(onClick = { editing = profile }) { Text("编辑连接", fontSize = 12.sp) }
                TextButton(onClick = { workspaceProfileId = id }, enabled = profile.optBoolean("hostKeyTrusted") && profile.optBoolean("credentialsConfigured")) { Text("使用此连接", fontSize = 12.sp) }
            }
        }
        HorizontalDivider(color = Line)
    }
    workspaceProfileId?.let { profileId -> NativeSshWorkbench(profileId, state, controller) }
    val probe = state.moduleResults["ssh.probeHostKey"].takeIf { selected != null && state.moduleResultInputs["ssh.probeHostKey"]?.optString("profileId") == selected }
    if (probe != null) {
        SshCaption("从当前网络连接取得的主机公钥指纹：")
        SelectionContainer { Text(probe.optString("fingerprint"), fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 21.sp) }
        SshCaption(probe.optString("keyType"))
        if (probe.optBoolean("changed")) Text("主机密钥已变化，当前执行已阻止。请先通过已有可信渠道核对主机。", color = Danger, fontSize = 12.sp, lineHeight = 21.sp)
        else if (probe.optString("fingerprint").isNotBlank()) OutlinedButton(onClick = { checked = false; trust = probe }, enabled = "ssh.approveHostKey" !in state.moduleBusy) { Text("核对并信任这个指纹") }
    }
    editing?.let { profile -> SshProfileDialog(profile, state, controller) { editing = null } }
    trust?.let { candidate -> AlertDialog(onDismissRequest = { trust = null }, containerColor = MaterialTheme.colorScheme.surface, title = { Text("确认主机身份") }, text = { Column {
        Text("请将下方指纹与这台主机已有可信渠道提供的指纹比较。", fontSize = 13.sp, lineHeight = 22.sp)
        SelectionContainer { Text(candidate.optString("fingerprint"), fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = 12.dp)) }
        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(checked, { checked = it }); Text("已通过可信渠道核对一致", fontSize = 12.sp) }
    } }, confirmButton = { Button(onClick = { controller.requestFeature("ssh", "approveHostKey", JSONObject().put("profileId", selected).put("probeId", candidate.optString("probeId")).put("fingerprint", candidate.optString("fingerprint"))); trust = null }, enabled = checked && selected != null) { Text("信任这个指纹") } }, dismissButton = { TextButton(onClick = { trust = null }) { Text("取消") } }) }
}

@Composable
private fun SshProfileDialog(profile: JSONObject, state: PocketUiState, controller: PocketWorldController, close: () -> Unit) {
    var name by remember { mutableStateOf(profile.optString("name")) }
    var host by remember { mutableStateOf(profile.optString("host")) }
    var port by remember { mutableStateOf(profile.optInt("port", 22).toString()) }
    var username by remember { mutableStateOf(profile.optString("username")) }
    var workspace by remember { mutableStateOf(profile.optString("workspace")) }
    var auth by remember { mutableStateOf(profile.optString("authType", "key")) }
    var secret by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf(false) }
    val busy = "ssh.saveProfile" in state.moduleBusy
    LaunchedEffect(busy) { if (submitted && !busy && state.moduleErrors["ssh.saveProfile"] == null) { secret = ""; passphrase = ""; close() } }
    AlertDialog(onDismissRequest = { if (!busy) { secret = ""; passphrase = ""; close() } }, containerColor = MaterialTheme.colorScheme.surface, title = { Text(if (profile.has("id")) "编辑 SSH 主机" else "添加 SSH 主机") }, text = {
        Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("名称") }, singleLine = true)
            OutlinedTextField(host, { host = it }, label = { Text("主机地址") }, singleLine = true)
            OutlinedTextField(port, { port = it.filter(Char::isDigit).take(5) }, label = { Text("端口") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            OutlinedTextField(username, { username = it }, label = { Text("用户名") }, singleLine = true)
            OutlinedTextField(workspace, { workspace = it }, label = { Text("远端工作目录") }, placeholder = { Text("/home/user/project") }, singleLine = true)
            Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(auth == "key", { auth = "key"; secret = "" }); Text("私钥", fontSize = 13.sp); RadioButton(auth == "password", { auth = "password"; secret = ""; passphrase = "" }); Text("密码", fontSize = 13.sp) }
            OutlinedTextField(secret, { secret = it }, label = { Text(if (auth == "key") "私钥内容" else "登录密码") }, singleLine = auth == "password", maxLines = if (auth == "key") 6 else 1, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            if (auth == "key") OutlinedTextField(passphrase, { passphrase = it }, label = { Text("私钥口令（可选）") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
            SshCaption(if (profile.optBoolean("credentialsConfigured")) "已保存凭据。更换主机或用户时请重新填写。" else "凭据只保存到手机的加密存储。")
            state.moduleErrors["ssh.saveProfile"]?.let { Text(it, fontSize = 12.sp, color = Danger) }
        }
    }, confirmButton = { Button(onClick = { submitted = true; val input = JSONObject().put("name", name.trim()).put("host", host.trim()).put("port", port.toIntOrNull() ?: 0).put("username", username.trim()).put("workspace", workspace.trim()).put("authType", auth); if (profile.has("id")) input.put("id", profile.getString("id")); if (secret.isNotEmpty()) input.put(if (auth == "key") "privateKey" else "password", secret); if (auth == "key" && passphrase.isNotEmpty()) input.put("passphrase", passphrase); controller.requestFeature("ssh", "saveProfile", input) }, enabled = !busy && name.isNotBlank() && host.isNotBlank() && username.isNotBlank() && workspace.startsWith("/") && (port.toIntOrNull() ?: 0) in 1..65535) { Text(if (busy) "保存中" else "保存主机") } }, dismissButton = { TextButton(onClick = { secret = ""; passphrase = ""; close() }, enabled = !busy) { Text("取消") } })
}
@Composable internal fun SshCaption(value: String) { Text(value, color = SecondaryInk, fontSize = 12.sp, lineHeight = 21.sp, modifier = Modifier.padding(top = 6.dp, bottom = 9.dp)) }
