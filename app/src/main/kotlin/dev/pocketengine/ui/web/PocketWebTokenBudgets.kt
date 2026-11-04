package dev.pocketengine.ui.web

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import dev.pocketengine.EngineApplication
import dev.pocketengine.ui.PocketWorldController
import org.json.JSONObject
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Owner policy presentation over the sole TaskStore inference ledger. No feature/model tool. */
internal class PocketWebTokenBudgets(
    private val controller: PocketWorldController,
    private val host: EngineApplication,
    private val ownerCurrent: () -> Boolean = { true }
) : AutoCloseable {
    private data class Review(val handle: String, val input: String, val expires: Long)
    private class Window(val id: String, val taskId: String, val owner: Pair<Triple<Long, Int, Long>, Long>) {
        val busy = AtomicBoolean(false)
        @Volatile var snapshot: String? = null
        @Volatile var review: Review? = null
    }
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var window: Window? = null
    @Volatile private var closed = false

    companion object {
        val COMMANDS = setOf("tokenBudgets.open", "tokenBudgets.read", "tokenBudgets.review", "tokenBudgets.save", "tokenBudgets.discard", "tokenBudgets.close")
    }
    private fun current(original: Window?) = !closed && original != null && window === original && ownerCurrent() &&
        controller.webPrivateOwnerVisible() && original.owner == controller.webWindowTicket()
    private fun requireWindow(id: String) = window?.takeIf { it.id == id && current(it) }
        ?: error("原预算窗口已关闭，请重新核对；没有再次保存")
    private fun fields(input: JSONObject, vararg allowed: String) {
        require(input.keys().asSequence().all { it in allowed }) { "预算操作含不适用字段" }
    }
    private fun text(input: JSONObject, key: String, empty: Boolean = false): String {
        val value = input.opt(key)
        require(value is String && value.length <= 240 && '\u0000' !in value && (empty || value.isNotBlank())) { "请重新核对原预算范围" }
        return value
    }
    private fun integer(input: JSONObject, key: String, min: Long, max: Long): Long {
        val value = input.opt(key)
        require((value is Int || value is Long) && (value as Number).toLong() in min..max) { "请填写准确的整数 token 上限" }
        return (value as Number).toLong()
    }
    private fun nativeSnapshot(original: Window): JSONObject {
        requireWindow(original.id)
        val value = host.tokenBudgetsForOwner(original.taskId)
        requireWindow(original.id)
        return value
    }
    private fun policy(snapshot: JSONObject, scope: String, id: String): JSONObject = when (scope) {
        "default" -> { require(id.isEmpty()); snapshot.getJSONObject("defaults") }
        "task" -> {
            val task = snapshot.optJSONObject("task") ?: error("请从原任务详情读取累计预算")
            check(task.getBoolean("editable") && id == task.getString("taskId") && id == task.getString("ownerTaskId")) { "成员预算归原父任务，请回原父任务调整" }
            task.getJSONObject("budget")
        }
        "daily" -> {
            val routes = snapshot.getJSONArray("routes")
            (0 until routes.length()).map { routes.getJSONObject(it) }.singleOrNull { it.getString("routeKey") == id }
                ?.getJSONObject("budget") ?: error("原连接或账号登记已变化，请重新读取")
        }
        else -> error("不是原主人预算范围")
    }
    fun clear() { window?.review = null; window = null }
    override fun close() { closed = true; clear() }

    fun dispatch(action: String, args: JSONObject, done: (JSONObject?, String?, String) -> Unit) {
        require(action in COMMANDS)
        check(!closed && ownerCurrent() && controller.webPrivateOwnerVisible()) { "请回前台私人预算页" }
        val original = if (action == "tokenBudgets.open") {
            fields(args, "taskId")
            val taskId = if (args.has("taskId")) text(args, "taskId", true) else ""
            if (taskId.isNotEmpty()) check(controller.state.tasks.any { it.id == taskId }) { "原任务已不在当前读取窗口，请回任务详情重新读取" }
            clear(); Window(UUID.randomUUID().toString(), taskId, controller.webWindowTicket()).also { window = it }
        } else requireWindow(text(args, "windowId"))
        if (action == "tokenBudgets.close") {
            fields(args, "windowId"); clear(); done(JSONObject().put("closed", true), null, "not_accepted"); return
        }
        if (action == "tokenBudgets.discard") {
            fields(args, "windowId"); original.review = null; done(JSONObject().put("discarded", true), null, "not_accepted"); return
        }
        check(original.busy.compareAndSet(false, true)) { "正在核对原预算，请等待本次回执" }
        var writing = false
        try {
            val input: JSONObject? = when (action) {
                "tokenBudgets.open" -> null
                "tokenBudgets.read" -> { fields(args, "windowId"); original.review = null; null }
                "tokenBudgets.review" -> {
                    fields(args, "windowId", "scope", "id", "expectedRevision", "enabled", "limit", "zoneId")
                    val scope = text(args, "scope"); val id = text(args, "id", true)
                    val displayed = JSONObject(original.snapshot ?: error("请先读取原预算"))
                    val old = policy(displayed, scope, id)
                    val expected = integer(args, "expectedRevision", 0, Long.MAX_VALUE)
                    check(old.getLong("revision") == expected) { "预算版本已变化，请重新读取" }
                    require(args.opt("enabled") is Boolean)
                    val enabled = args.getBoolean("enabled")
                    val limit = integer(args, "limit", if (enabled) 1 else 0, 1_000_000_000_000L)
                    val zone = if (scope == "daily") ZoneId.of(text(args, "zoneId")).id else {
                        require(!args.has("zoneId") || text(args, "zoneId", true).isEmpty()); ""
                    }
                    original.review = null
                    JSONObject().put("scope", scope).put("id", id).put("expectedRevision", expected)
                        .put("enabled", enabled).put("limit", limit).put("zoneId", zone)
                }
                "tokenBudgets.save" -> {
                    fields(args, "windowId", "reviewHandle")
                    val review = original.review
                    original.review = null // Consume once before work; errors never replay the write.
                    check(review != null && review.handle == text(args, "reviewHandle") && review.expires > SystemClock.elapsedRealtime()) { "原预算审阅已关闭、过期或使用，请先重新读取" }
                    writing = true; JSONObject(review.input)
                }
                else -> error("未接入的预算操作")
            }
            controller.runModelConnectionWork(Runnable {
                val result = runCatching {
                    requireWindow(original.id)
                    when (action) {
                        "tokenBudgets.review" -> {
                            val proposal = requireNotNull(input)
                            val fresh = nativeSnapshot(original)
                            val old = policy(fresh, proposal.getString("scope"), proposal.getString("id"))
                            check(old.getLong("revision") == proposal.getLong("expectedRevision")) { "原预算已变化，请重新读取" }
                            if (proposal.getBoolean("enabled")) check(!old.optBoolean("blockedByUnknown")) { "原用量仍有无法界定的未知项，不能把遗漏当 0 启用上限" }
                            val handle = UUID.randomUUID().toString()
                            original.review = Review(handle, proposal.toString(), SystemClock.elapsedRealtime() + 120_000)
                            original.snapshot = fresh.toString()
                            JSONObject().put("reviewHandle", handle).put("input", proposal).put("previous", old)
                                .put("executionStarted", false).put("effectsApproved", false)
                        }
                        "tokenBudgets.save" -> {
                            val selected = requireNotNull(input)
                            val fresh = nativeSnapshot(original)
                            policy(fresh, selected.getString("scope"), selected.getString("id"))
                            requireWindow(original.id)
                            host.saveTokenBudgetForOwner(selected.getString("scope"), selected.getString("id"), selected.getLong("expectedRevision"),
                                selected.getBoolean("enabled"), selected.getLong("limit"), selected.getString("zoneId"), java.util.function.BooleanSupplier { current(original) })
                        }
                        else -> nativeSnapshot(original).also { original.snapshot = it.toString() }
                    }.put("windowId", original.id)
                }
                main.post {
                    original.busy.set(false)
                    if (!current(original)) done(null, if (writing) "原预算窗口已变化，请重新读取原设置核对保存结果；没有自动重试" else "原预算窗口已变化，请重新读取", if (writing) "outcome_unknown" else "not_accepted")
                    else result.fold({ value -> done(PocketWebState.publicValue(value) as JSONObject, null, "not_accepted") },
                        { error -> done(null, error.message ?: "原预算暂不能核对", if (writing) "outcome_unknown" else "not_accepted") })
                }
            })
        } catch (failure: Exception) { original.busy.set(false); throw failure }
    }
}
