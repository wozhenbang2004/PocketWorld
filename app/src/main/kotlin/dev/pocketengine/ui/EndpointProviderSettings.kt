package dev.pocketengine.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

@Composable
internal fun EndpointProviderSettings(realtime: JSONObject, state: PocketUiState, controller: PocketWorldController) {
    val config = realtime.optJSONObject("providerConfiguration") ?: JSONObject()
    val supported = realtime.optJSONObject("capabilities")?.optBoolean("endpointProviderSupported") == true
    var endpoint by remember(config.optString("endpointUrl")) { mutableStateOf(config.optString("endpointUrl")) }
    var server by remember(config.optString("serverUrl")) { mutableStateOf(config.optString("serverUrl")) }
    var agent by remember(config.optString("agentName")) { mutableStateOf(config.optString("agentName")) }
    var auth by remember { mutableStateOf("") }
    var clearing by remember { mutableStateOf(false) }
    LaunchedEffect(config.optLong("revision")) { auth = "" }
    Text("可信 token / agent 服务", fontSize = 14.sp, fontWeight = FontWeight.Medium)
    Text("需要你自己已有的认证服务和已部署的 AI agent。此应用不会代建服务器、生成真实凭据或把模型 API key 转交给它。保存只存配置，接受通话后才联网。", fontSize = 12.sp, lineHeight = 20.sp, color = SecondaryInk)
    if (!supported) Text("宿主尚未安装正式端点适配器，不能发起服务通话。", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
    OutlinedTextField(endpoint, { endpoint = it }, label = { Text("认证端点 HTTPS 地址") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(server, { server = it }, label = { Text("允许连接的 LiveKit WSS 地址") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(agent, { agent = it }, label = { Text("服务中真实部署的 agent 名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(auth, { auth = it }, label = { Text("该认证服务的 Bearer 令牌") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
    Text(if (config.optBoolean("hasAuthentication")) "认证单独加密保存；同一认证服务 origin 可留空保留。不要填写模型 API key 或 LiveKit 服务端签名 secret。" else "这里只填写你自己的认证服务访问令牌，不是房间 JWT、模型 API key 或 LiveKit 服务端签名 secret。", fontSize = 12.sp, lineHeight = 19.sp, color = SecondaryInk)
    Button(onClick = { controller.requestRealtime("configureEndpointProvider", JSONObject().put("endpointUrl", endpoint.trim()).put("serverUrl", server.trim()).put("agentName", agent.trim()).put("authenticationToken", auth)) }, enabled = supported && endpoint.isNotBlank() && server.isNotBlank() && agent.isNotBlank() && "realtime.configureEndpointProvider" !in state.moduleBusy) { Text("保存可信服务配置") }
    if (config.length() > 0) TextButton(onClick = { clearing = true }, enabled = "realtime.clearEndpointProvider" !in state.moduleBusy) { Text("停用并清除这项服务认证") }
    if (clearing) AlertDialog(onDismissRequest = { clearing = false }, title = { Text("清除通话服务配置？") }, text = { Text("只删除此项端点配置和本机加密认证；不撤销服务端令牌，也不停止已有任务。之后需要重新填写。") }, confirmButton = { TextButton(onClick = { clearing = false; controller.requestRealtime("clearEndpointProvider") }) { Text("确认清除") } }, dismissButton = { TextButton(onClick = { clearing = false }) { Text("保留") } })
}
