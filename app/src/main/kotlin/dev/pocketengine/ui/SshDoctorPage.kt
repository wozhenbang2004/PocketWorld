package dev.pocketengine.ui

import android.content.ContextWrapper
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Diagnostic body is held only by this visible private page, never the controller result cache. */
@Composable
internal fun PrivateSshDoctorPage(profile: JSONObject, controller: PocketWorldController, close: () -> Unit) {
    val page = remember { controller.newPrivateSshDoctorPage() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var report by remember { mutableStateOf<JSONObject?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var running by remember { mutableStateOf(false) }
    var reviewed by remember { mutableStateOf(false) }
    var alive by remember { mutableStateOf(true) }
    val lifecycle = remember(context) { generateSequence(context) { (it as? ContextWrapper)?.baseContext?.takeUnless { base -> base === it } }.filterIsInstance<LifecycleOwner>().firstOrNull()?.lifecycle }
    DisposableEffect(page, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) { alive = false; report = null; page.close(); close() } }
        lifecycle?.addObserver(observer)
        onDispose { alive = false; report = null; lifecycle?.removeObserver(observer); page.close() }
    }
    AlertDialog(onDismissRequest = close, containerColor = MaterialTheme.colorScheme.surface, title = { Text("核对此连接的实际环境") }, text = {
        Column(Modifier.heightIn(max = 530.dp).verticalScroll(rememberScrollState())) {
            Text(profile.optString("name"), fontSize = 15.sp)
            SshCaption(profile.optString("username") + "@" + profile.optString("host") + " · " + profile.optString("workspace"))
            SshCaption("只读收集 OS、架构、非交互 Shell/PATH、Git/Java/Python/Node 与目录权限。不会安装、修复或读取配置文件。当前支持 POSIX Shell；Windows PowerShell/cmd 尚不支持。")
            SshCaption("正文只在此私人页显示，离开前台即释放；原任务只保留诊断状态。")
            if (report == null && !running) Row { Checkbox(reviewed, { reviewed = it }); Text("已核对这台主机、账号、目录和本次只读范围", fontSize = 12.sp) }
            if (running) { LinearProgressIndicator(Modifier.fillMaxWidth()); TextButton(onClick = { page.cancel() }) { Text("取消本次诊断") } }
            report?.let { actual ->
                SshCaption(actual.optString("message"))
                actual.optJSONObject("doctor")?.let { body -> SelectionContainer { Text(body.toString(2), fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 18.sp) } }
                if (page.taskId().isNotBlank()) TextButton(onClick = { val task = page.taskId(); report = null; close(); controller.focusTask(task) }) { Text("查看原诊断任务状态") }
            }
            error?.let { Text(it, color = Danger, fontSize = 12.sp) }
        }
    }, confirmButton = {
        if (report == null) Button(onClick = {
            try { running = true; error = null; page.start(profile.getString("id"), profile.getString("bindingHash")) { result -> scope.launch { if (alive) { report = result; running = false } } } }
            catch (_: Exception) { running = false; error = "连接身份或配置已变化，请返回重新核对后开始。" }
        }, enabled = reviewed && !running) { Text("开始只读诊断") }
        else TextButton(onClick = close) { Text("关闭私人结果") }
    }, dismissButton = { TextButton(onClick = close) { Text("返回") } })
}
