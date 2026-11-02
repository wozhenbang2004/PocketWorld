package dev.pocketengine.ui

import android.content.ContextWrapper
import android.widget.TextView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import dev.pocketengine.features.local.preview.ProjectLiveWebView
import kotlinx.coroutines.delay
import org.json.JSONObject

/** The live page is transient; project file drafts and fixed artifacts remain separate. */
@Composable
internal fun ProjectLivePreviewSection(task: UiTask, checkoutId: String, controller: PocketWorldController) {
    val operation = task.raw.optJSONArray("operations").objects().lastOrNull { row ->
        val envelope = row.optJSONObject("receipt")
        val actual = envelope?.optJSONObject("executionReceipt")
        val route = envelope?.optJSONObject("projectExecution")
        task.status == "running" && route != null && actual != null && row.optString("state") == "project_running" && row.optString("runId") == task.raw.optString("runId") &&
            route.optInt("coordinatorEpoch") == task.raw.optInt("coordinatorEpoch") && route.optString("checkoutId") == checkoutId &&
            actual.optJSONObject("devWatch")?.optBoolean("nativeSessionCreated") == true && actual.optString("status") == "running" &&
            !actual.optBoolean("cancelRequested") && !actual.optBoolean("resourcesReleased")
    }
    val executionId = operation?.optString("operationId")
    val context = LocalContext.current
    val lifecycle = remember(context) { generateSequence(context) { (it as? ContextWrapper)?.baseContext?.takeUnless { next -> next === it } }.filterIsInstance<LifecycleOwner>().firstOrNull()?.lifecycle }
    DisposableEffect(checkoutId, task.id) { onDispose { controller.closeProjectLivePreview() } }
    if (executionId != null) {
        val actual = operation?.optJSONObject("receipt")?.optJSONObject("executionReceipt")
        var details by remember(task.id, executionId) { mutableStateOf(false) }
        Text(if (task.raw.optJSONObject("developmentSession")?.optString("phase") == "owned_running") "开发服务保留中 · 可继续试用" else "开发服务运行中", style = MaterialTheme.typography.titleSmall)
        WorkbenchCaption("已批准的源码可在服务运行时修改；配置和依赖需要先停止服务。")
        TextButton(onClick = { details = !details }) { Text(if (details) "收起服务详情" else "服务详情") }
        if (details) {
            val session = task.raw.optJSONObject("developmentSession")
            val retained = session?.optJSONArray("services")?.objects()?.firstOrNull { it.optString("executionId") == executionId }
            WorkbenchCaption((if (retained != null) "本轮答复已结束；原服务保留至 ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(retained.optLong("expiresAt")))}，可继续发消息或编辑。" else "未保留的服务随模型轮次结束收尾。") + "任务停止、到期或撤销仍会停止服务。仅启动时列明的既有源码可热改，源码代际 ${actual?.optLong("sourceGeneration") ?: 0}。配置、锁、工具脚本、环境和新文件不在热改范围。")
            androidx.compose.foundation.text.selection.SelectionContainer { Text("execution $executionId\nrun ${task.raw.optString("runId")}\nepoch ${task.raw.optInt("coordinatorEpoch")}", style = MaterialTheme.typography.bodySmall, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace) }
        }
        OutlinedButton(onClick = { controller.openProjectLivePreview(task.id, checkoutId, executionId) }, enabled = !controller.projectLivePreviewChecking, modifier = Modifier.testTag("project_open_live_preview")) {
            Text(if (controller.projectLivePreviewChecking) "正在连接开发服务…" else "打开运行页面")
        }
    }
    val observed = controller.projectLivePreview?.takeIf { controller.projectLivePreviewTaskId == task.id && it.reference.checkoutId() == checkoutId }
    LaunchedEffect(executionId, observed?.identity) { if (observed != null && executionId != observed.reference.operationId()) controller.closeProjectLivePreview() }
    if (observed != null && executionId == observed.reference.operationId()) key(observed.identity) {
        var web by remember { mutableStateOf<ProjectLiveWebView?>(null) }
        var error by remember { mutableStateOf<String?>(null) }
        DisposableEffect(observed.identity, lifecycle) {
            val listener = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) { web?.release(); web = null; if (controller.projectLivePreview === observed) controller.closeProjectLivePreview() } }
            lifecycle?.addObserver(listener)
            onDispose { lifecycle?.removeObserver(listener); web?.release(); web = null }
        }
        LaunchedEffect(observed.identity, lifecycle) {
            while (controller.projectLivePreview === observed) {
                if (lifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) != false) controller.checkProjectLivePreview(observed)
                delay(1200)
            }
        }
        Text("运行中的页面", fontSize = 14.sp, modifier = Modifier.padding(top = 12.dp))
        WorkbenchCaption("页面随源码更新，必要时会整页刷新。要保留这个版本，请重新构建并采集成果。")
        var pageDetails by remember(observed.identity) { mutableStateOf(false) }
        TextButton(onClick = { pageDetails = !pageDetails }) { Text(if (pageDetails) "收起连接信息" else "连接信息") }
        if (pageDetails) WorkbenchCaption("已核对当前服务的 HTTP 与 WebSocket，不代表业务验收通过。此预览使用独立临时资料，无 Native 桥，不使用浏览器账号 Cookie。页面临时状态不是文件保存；切离预览会关闭页面，服务由原任务继续监督。")
        AndroidView(factory = { nativeContext ->
            try {
                ProjectLiveWebView.create(nativeContext, observed, object : ProjectLiveWebView.Events {
                    override fun error(message: String) { error = message; controller.recordProjectLivePreviewConsole(observed, task.id, "RENDER_ERROR", message, "", 0) }
                    override fun console(level: String, text: String, source: String, line: Int) { controller.recordProjectLivePreviewConsole(observed, task.id, level, text, source, line) }
                }).also { web = it }
            } catch (failure: Exception) { error = failure.message ?: "当前浏览器暂不能打开独立预览"; TextView(nativeContext).apply { text = error } }
        }, modifier = Modifier.fillMaxWidth().height(420.dp).testTag("project_live_webview"))
        error?.let { Text(it, color = Danger, fontSize = 13.sp) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { controller.closeProjectLivePreview() }) { Text("关闭页面") }
            TextButton(onClick = { controller.focusTask(task.id) }) { Text("到任务查看错误") }
        }
    }
}
