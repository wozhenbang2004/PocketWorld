package dev.pocketengine.ui

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.pocketengine.runtime.models.ModelConnectionService
import dev.pocketengine.runtime.models.ModelSelectionCoordinator
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject

/** An optional disclosure within the existing model page, using its existing worker. */
@Composable
internal fun StepRoutingControls(service: ModelConnectionService, runOnWorker: (Runnable) -> Unit) {
    var expanded by remember(service) { mutableStateOf(false) }
    var snapshot by remember(service) { mutableStateOf(JSONObject()) }
    var busy by remember(service) { mutableStateOf(false) }
    var error by remember(service) { mutableStateOf<String?>(null) }
    val alive = remember(service) { AtomicBoolean(true) }
    val main = remember { Handler(Looper.getMainLooper()) }
    val selection = remember(service) { service.modelSelection() }
    val pendingWrite = remember(service) { AtomicReference<ModelSelectionCoordinator.Mutation?>(null) }
    var shared by remember(selection) { mutableStateOf(selection.status()) }
    var loadedRevision by remember(selection) { mutableLongStateOf(-1L) }
    var readAttemptRevision by remember(selection) { mutableLongStateOf(-1L) }
    var snapshotAvailable by remember(selection) { mutableStateOf(false) }
    fun load(mutation: Boolean = false, action: () -> JSONObject) {
        if (busy || !alive.get()) return
        if (mutation && !snapshotAvailable) { error = "请先读取当前模型组，再保存轻量步骤设置。"; return }
        val reservation = if (mutation) try { selection.beginMutation(loadedRevision) } catch (failed: IllegalStateException) { error = failed.message; shared = selection.status(); return } else null
        if (reservation != null) pendingWrite.set(reservation)
        busy = true; error = null
        try {
            runOnWorker(Runnable {
                val writeFailure = if (reservation != null) runCatching { reservation.run { action() } }.exceptionOrNull() else null
                if (reservation != null) pendingWrite.compareAndSet(reservation, null)
                val result = runCatching { selection.readStable { if (mutation) service.stepRoutingSnapshot() else action() } }
                val completed = selection.status()
                main.post { if (alive.get()) {
                    shared = selection.status()
                    result.fold({
                        readAttemptRevision = it.revision()
                        if (!shared.pending() && shared.revision() == it.revision()) { snapshot = it.value(); loadedRevision = it.revision(); snapshotAvailable = true }
                    }, { snapshotAvailable = false; readAttemptRevision = completed.revision(); error = "原模型组暂不可读取，请刷新后核对。" })
                    if (writeFailure != null) error = writeFailure.message ?: "原模型组、能力或设置修订已变化，请刷新后核对。"
                    busy = false
                } }
            })
        } catch (_: Exception) { reservation?.close(); if (reservation != null) pendingWrite.compareAndSet(reservation, null); busy = false; snapshotAvailable = false; error = "本机工作线程暂不可用，请重新打开模型页。"; readAttemptRevision = selection.status().revision() }
    }
    DisposableEffect(service) {
        alive.set(true)
        val subscription = selection.observe { main.post { if (alive.get()) shared = selection.status() } }
        shared = selection.status()
        onDispose { alive.set(false); pendingWrite.getAndSet(null)?.close(); subscription.close() }
    }
    LaunchedEffect(service, shared, busy) {
        if (!busy && !shared.pending() && readAttemptRevision < shared.revision()) load { service.stepRoutingSnapshot() }
    }
    val blocked = busy || shared.pending() || !snapshotAvailable || loadedRevision < shared.revision()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        TextButton(onClick = { expanded = !expanded }) {
            Text("轻量步骤 · " + if (snapshot.optBoolean("enabled")) "已启用" else "关闭")
        }
        if (expanded) {
            Text("连续两次同来源的只读步骤成功后，允许使用你指定的轻量候选。首步、风险、未知、来源变化和最终答复使用主候选；所有操作仍走原审批与预算。", style = MaterialTheme.typography.bodySmall, color = SecondaryInk)
            Text("此处只使用当前主模型组中的现有候选。轻量结果被拒绝时仍会产生实际用量，不保证更低费用。", style = MaterialTheme.typography.bodySmall, color = SecondaryInk)
            if (snapshot.optBoolean("enabled") && !snapshot.optBoolean("configurationCurrent")) {
                Text("已保存的组配置发生变化，轻量路由当前不生效。请核对后重新启用。", style = MaterialTheme.typography.bodySmall)
            }
            val candidates = snapshot.optJSONArray("candidates")
            if (candidates == null || candidates.length() == 0) Text("请先在原模型组中配置并选择主候选和一个具备明确能力的备选。", style = MaterialTheme.typography.bodySmall)
            for (i in 0 until (candidates?.length() ?: 0)) {
                val row = candidates!!.getJSONObject(i)
                val selected = snapshot.optBoolean("enabled") && snapshot.optBoolean("configurationCurrent") && row.optString("candidateId") == snapshot.optString("candidateId")
                TextButton(enabled = !blocked && row.optBoolean("capabilitiesKnown") && !selected, onClick = {
                    val revision = snapshot.optLong("revision")
                    val fingerprint = snapshot.optString("currentPlanFingerprint")
                    val id = row.optString("candidateId")
                    load(mutation = true) { service.saveStepRouting(true, revision, fingerprint, id) }
                }) { Text(row.optString("model") + " · " + when {
                    selected -> "当前轻量候选"
                    !row.optBoolean("capabilitiesKnown") -> "能力未知"
                    else -> "启用此候选"
                }) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(enabled = !busy && !shared.pending(), onClick = { load { service.stepRoutingSnapshot() } }) { Text("刷新") }
                if (snapshot.optBoolean("enabled")) TextButton(enabled = !blocked, onClick = {
                    val revision = snapshot.optLong("revision")
                    load(mutation = true) { service.saveStepRouting(false, revision, "", "") }
                }) { Text("关闭轻量步骤") }
            }
            if (busy || shared.pending()) LinearProgressIndicator(Modifier.fillMaxWidth().height(1.dp), color = Ink)
            error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        }
    }
}
