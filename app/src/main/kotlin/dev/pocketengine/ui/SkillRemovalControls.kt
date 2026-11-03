package dev.pocketengine.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.pocketengine.EngineApplication
import dev.pocketengine.core.RunControl
import dev.pocketengine.features.skills.SkillsModule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Uses the original selected package and one native review object; JSON cannot confirm deletion. */
@Composable internal fun SkillRemovalControls(current: JSONObject?, module: SkillsModule, app: EngineApplication, controller: PocketWorldController) {
    val worker = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var foreground by remember { mutableStateOf(lifecycle.currentState == Lifecycle.State.RESUMED) }
    var review by remember { mutableStateOf<SkillsModule.RemovalReview?>(null) }
    var metadata by remember { mutableStateOf<JSONObject?>(null) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var control by remember { mutableStateOf<RunControl?>(null) }
    val identity = current?.let { "${it.optString("id")}:${it.optInt("revision")}:${it.optString("digest")}:${it.optLong("executionGeneration")}" }
    val latestIdentity by rememberUpdatedState(identity)
    LaunchedEffect(identity) { review = null; metadata = null }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ ->
            foreground = lifecycle.currentState == Lifecycle.State.RESUMED
            if (!foreground) { review = null; metadata = null; control?.cancel() }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); control?.cancel() }
    }
    notice?.let { Text(it) }
    if (current == null) return
    if (current.has("origin")) { Text("来源技能请在原经验审核页处理；这里不解除来源关系。"); return }
    if (current.optBoolean("enabled")) { Text("卸载前请先在技能列表明确停用，再回来核对。"); return }
    TextButton(onClick = {
        val pinned = JSONObject(current.toString()); val expectedIdentity = identity
        busy = true; notice = null
        worker.launch {
            try {
                val checked = withContext(Dispatchers.IO) { module.reviewRemovalForOwner(app.tasks, pinned) }
                if (foreground && latestIdentity == expectedIdentity) { metadata = checked.view(); review = checked }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { notice = failure.message ?: "无法核对当前技能使用，请先查看原任务。" }
            finally { busy = false }
        }
    }, enabled = !busy && foreground) { Text("审阅卸载所选技能…") }
    val selected = review; val view = metadata
    if (selected != null && view != null) AlertDialog(onDismissRequest = { review = null; metadata = null }, title = { Text("卸载 ${view.optString("name")}？") }, text = {
        Column {
            Text("将移除这份停用技能的正文、资源和保留修订。若需要恢复内容，请先导出 ZIP。原任务、执行回执和成果保留。")
            Text("修订 ${view.optInt("revision")} · ${view.optJSONArray("resources")?.length() ?: 0} 个文件。确认时会重新核对原版本、任务与资源；结果不明时不会重试。")
        }
    }, confirmButton = { TextButton(onClick = {
        if (!foreground || busy) return@TextButton
        review = null; metadata = null; busy = true
        val original = RunControl(); control = original
        notice = "原卸载编号：${selected.actionId()}。提交后请按此编号核对，不自动重试。"
        worker.launch {
            try {
                val receipt = withContext(Dispatchers.IO) { selected.remove(original) }
                notice = if (receipt.optBoolean("deleted") && receipt.optString("status") == "completed") "已卸载这份停用技能；原任务、回执和成果保留。原编号：${selected.actionId()}" else "卸载未完整确认，请核对原任务 ${selected.actionId()}；不会自动重试。"
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { notice = "卸载结果请核对原编号 ${selected.actionId()}：${failure.message ?: "原操作未确认"}" }
            finally { control = null; busy = false; controller.refresh() }
        }
    }, enabled = foreground && !busy) { Text("确认卸载这一版本") } }, dismissButton = { TextButton(onClick = { review = null; metadata = null }) { Text("保留") } })
}
