package dev.pocketengine.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import dev.pocketengine.EngineApplication
import dev.pocketengine.core.RunControl
import dev.pocketengine.features.local.project.ProjectFileDraft
import dev.pocketengine.features.local.project.ProjectOwnerLiveFileSave
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Hand editing joins the already approved dev-watch; it never starts/resumes a model or service. */
@Composable
internal fun ProjectLiveFileSaveControls(
    projectId: String, checkoutId: String, path: String, editor: ProjectFileDraft,
    controller: PocketWorldController, canSave: Boolean, changed: (ProjectFileDraft) -> Boolean,
    saved: () -> Unit, blocksOtherWrites: (Boolean) -> Unit
) {
    val app = LocalContext.current.applicationContext as EngineApplication
    val session = controller.projectWorkbench
    val task = session.selectedTask(checkoutId)
    val scope = rememberCoroutineScope()
    val latestEditor by rememberUpdatedState(editor)
    val latestChanged by rememberUpdatedState(changed)
    val latestSaved by rememberUpdatedState(saved)
    var busy by remember(checkoutId, path) { mutableStateOf(false) }
    var pending by remember(checkoutId, path) { mutableStateOf<JSONObject?>(null) }
    var pendingLoaded by remember(checkoutId, path) { mutableStateOf(false) }
    var unreadablePending by remember(checkoutId, path) { mutableStateOf(false) }
    var review by remember(checkoutId, path) { mutableStateOf<ProjectOwnerLiveFileSave.Review?>(null) }
    var reviewedRevision by remember(checkoutId, path) { mutableLongStateOf(-1) }
    var notice by remember(checkoutId, path) { mutableStateOf<String?>(null) }
    var observation by remember(checkoutId, path) { mutableStateOf<RunControl?>(null) }
    var consent by remember(review) { mutableStateOf(false) }
    LaunchedEffect(checkoutId, path) {
        try { pending = withContext(Dispatchers.IO) { session.pendingLiveFileSave(checkoutId, path) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { unreadablePending = true; notice = "原保存定位无法读取。请在原任务核对；没有重新保存或丢弃草稿。" }
        finally { pendingLoaded = true }
    }
    LaunchedEffect(busy, pending, unreadablePending, pendingLoaded) { blocksOtherWrites(!pendingLoaded || busy || pending != null || unreadablePending) }
    DisposableEffect(checkoutId, path) {
        onDispose {
            // Stops this owner's observation/pre-dispatch wait only. The original service control
            // is deliberately not referenced here; an entered save remains queryable by its op.
            observation?.cancel()
        }
    }
    suspend fun receive(actual: ProjectOwnerLiveFileSave.Observation) {
        if (actual.completed()) {
            val next = latestEditor.saved(actual.originalIntent, actual.json())
            check(latestChanged(next)) { "已收到原保存，但草稿缓存未确认；原动作定位保留，请核对后再保存。" }
            withContext(Dispatchers.IO) { session.persistLiveFileSave(checkoutId, path, null) }
            pending = null; notice = "已保存这份源码。开发服务仍使用原进程；页面更新效果请到预览核对。"; latestSaved()
        } else if (actual.terminal()) {
            withContext(Dispatchers.IO) { session.persistLiveFileSave(checkoutId, path, null) }
            pending = null; notice = "原保存没有成功。草稿保留；先读当前文件，再决定后续保存。"; latestSaved()
        } else notice = "原保存结果待核。只查询这次保存，不重发；你的新输入继续保留。"
    }
    if (pending != null || unreadablePending) {
        Text("有一份原保存需要核对", style = MaterialTheme.typography.labelMedium)
        OutlinedButton(onClick = {
            val original = pending ?: return@OutlinedButton
            if (busy) return@OutlinedButton
            busy = true
            scope.launch {
                try { receive(withContext(Dispatchers.IO) { app.features.originalProjectLiveFileSaveForOwner(JSONObject(original.toString())) }) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failed: Exception) { notice = "原保存暂不可核对；未重发、未清除草稿。" + (failed.message?.take(160) ?: "") }
                finally { busy = false }
            }
        }, enabled = !busy && pending != null, modifier = Modifier.fillMaxWidth().testTag("project_editor_live_save_query")) { Text("核对原保存") }
    } else if (task != null && !editor.creating) {
        OutlinedButton(onClick = {
            if (busy) return@OutlinedButton
            val original = try { latestEditor.saveIntent() } catch (failed: Exception) { notice = failed.message; return@OutlinedButton }
            if (original.projectId != projectId || original.checkoutId != checkoutId || original.path != path) return@OutlinedButton
            val control = RunControl(); observation = control; busy = true; reviewedRevision = latestEditor.revision
            scope.launch {
                try { review = withContext(Dispatchers.IO) { app.features.reviewProjectLiveFileForOwner(task, original, control) }; notice = null }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failed: Exception) { notice = failed.message?.take(300) ?: "没有可用的受控开发服务；请先停止运行，再普通保存。" }
                finally { busy = false; observation = null }
            }
        }, enabled = canSave && pendingLoaded && !unreadablePending && !busy, modifier = Modifier.fillMaxWidth().testTag("project_editor_live_save_review")) { Text("保存到仍在运行的开发服务…") }
    }
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    notice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    review?.let { selected ->
        val pins = selected.metadata()
        val unchanged = reviewedRevision == latestEditor.revision
        AlertDialog(
            onDismissRequest = { if (!busy) review = null },
            title = { Text("保存并让开发服务继续使用？") },
            text = { Column {
                Text("$path\n${pins.getInt("bytes")} 字节\n\n只在原启动批准已覆盖这个文件及其后续执行影响时保存。配置、依赖和脚本不会因此获准修改。")
                if (!unchanged) Text("草稿已有新输入，请取消并重新核对。", color = MaterialTheme.colorScheme.error)
                Row { Checkbox(consent, { consent = it }, enabled = !busy); Text("已核对这份修改，会在当前开发服务中执行。", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall) }
            } },
            confirmButton = { Button(onClick = {
                if (!consent || busy || reviewedRevision != latestEditor.revision) return@Button
                val control = RunControl(); observation = control; busy = true
                scope.launch {
                    try {
                        val result = withContext(Dispatchers.IO) { app.features.saveProjectLiveFileForOwner(selected, control) { locator ->
                            // Durable body-free locator before native dispatch. UI cancellation must
                            // not invalidate the original already-approved service or replay a save.
                            session.persistLiveFileSave(checkoutId, path, locator)
                        } }
                        pending = withContext(Dispatchers.IO) { session.pendingLiveFileSave(checkoutId, path) }
                        receive(result); review = null
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failed: Exception) {
                        pending = withContext(Dispatchers.IO) { session.pendingLiveFileSave(checkoutId, path) }
                        review = null; notice = if (pending != null) "已登记的原保存待核，只查询它；草稿保留。" else failed.message?.take(300) ?: "原保存未完成，草稿保留。"
                    } finally { busy = false; observation = null }
                }
            }, enabled = consent && unchanged && !busy, modifier = Modifier.testTag("project_editor_live_save_confirm")) { Text("保存这份源码") } },
            dismissButton = { TextButton(onClick = { review = null }, enabled = !busy) { Text("继续编辑") } }
        )
    }
}
