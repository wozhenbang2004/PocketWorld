package dev.pocketengine.ui.web

import android.os.Handler
import android.os.Looper
import android.util.Base64
import dev.pocketengine.core.RunControl
import dev.pocketengine.features.skills.NativeSkillExecutionHost
import dev.pocketengine.ui.McpInstallPreviewRules
import dev.pocketengine.ui.PocketWorldController
import dev.pocketengine.ui.SkillExecutionReviewGuard
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

/** A presentation of the existing task host. A JSON plan/receipt never reconstructs native approval. */
internal class PocketWebTaskCapabilities(
    private val owner: () -> Any,
    private val visible: () -> Boolean,
    private val host: () -> NativeSkillExecutionHost,
    private val snapshot: (String) -> JSONObject,
    private val artifact: (String, String, String, String) -> ByteArray,
    private val mcpSnapshot: () -> JSONObject,
    private val mcp: (String, JSONObject, RunControl) -> JSONObject,
    private val execute: (Runnable) -> Unit,
    private val exporter: PocketWebTaskArtifactExport? = null
) {
    constructor(controller: PocketWorldController, changed: () -> Unit = {}, originalTaskVisible: (String) -> Boolean = { true }) : this(controller::webWindowTicket, controller::webPrivateOwnerVisible,
        controller::skillExecutionHost, controller::taskCapabilitySnapshot, controller::readTaskSkillArtifact,
        { controller.nativeAccountWorkflows().mcpSnapshot() },
        { command, input, control -> controller.nativeAccountWorkflows().mcp(command, input, control) }, controller::runEcosystemControlWork,
        PocketWebTaskArtifactExport(controller, originalTaskVisible, changed))

    private class Page(val id: String, val task: String, val owner: Any, val native: NativeSkillExecutionHost?) {
        @Volatile var alive = true
        val choices = ConcurrentHashMap<String, Pair<String, String>>()
    }
    private class Review(val id: String, val page: Page, val script: NativeSkillExecutionHost.Review?, val command: String?, val payload: String?, val shown: String) {
        @Volatile var alive = true
        @Volatile var consent = 0L
        @Volatile var accepted = false
        var attempted = false
        var window: NativeSkillExecutionHost.OwnerWindow? = null
    }
    private val pages = ConcurrentHashMap<String, Page>()
    private val reviews = ConcurrentHashMap<String, Review>()
    private val main = Handler(Looper.getMainLooper())
    private val busy = AtomicBoolean(false)
    private val lifetime = Any()
    @Volatile private var generation = 0L

    companion object {
        val COMMANDS = setOf("taskCapabilities.open", "taskCapabilities.close", "taskCapabilities.refresh", "taskCapabilities.review",
            "taskCapabilities.script", "taskCapabilities.consent", "taskCapabilities.decide", "taskCapabilities.dismissReview",
            "taskCapabilities.receipt", "taskCapabilities.retrieve", "taskCapabilities.artifact", "taskCapabilities.export", "taskCapabilities.mcp.review", "taskCapabilities.mcp.confirm")
        private fun fields(input: JSONObject, vararg allowed: String) { require(input.keys().asSequence().all { it in allowed }) { "本次任务能力操作包含不适用字段" } }
        private fun text(input: JSONObject, key: String): String { val raw = input.opt(key); require(raw is String && raw.isNotBlank() && raw.length <= 240 && '\u0000' !in raw); return raw }
        private fun copy(input: JSONObject) = JSONObject(input.toString())
        private fun rows(input: JSONArray?) = if (input == null) emptyList() else (0 until input.length()).map { input.getJSONObject(it) }
    }
    private fun current(page: Page) = page.alive && page.owner == owner() && visible()
    private fun retire(review: Review) = synchronized(review) { review.alive = false; review.accepted = false; review.consent++; review.window?.close(); review.window = null }
    private fun release(page: Page) { page.alive = false; page.choices.clear(); reviews.values.filter { it.page === page }.forEach(::retire) }
    private fun closePages() = synchronized(lifetime) { generation++; pages.values.forEach(::release); pages.clear(); reviews.values.forEach(::retire); reviews.clear() }
    fun clear() { closePages(); exporter?.clear() }
    fun background() { closePages(); exporter?.foreground(false) }
    fun foreground(value: Boolean) { if (!value) closePages(); exporter?.foreground(value) }
    fun exportSnapshot(): JSONObject? = exporter?.snapshot()
    private fun page(input: JSONObject): Page = (pages[text(input, "pageHandle")] ?: error("原任务能力页已关闭")).also { check(current(it)) { "原任务或前台窗口已变化" } }
    private fun review(input: JSONObject): Review = (reviews[text(input, "reviewHandle")] ?: error("原脚本审阅已关闭")).also { check(it.alive && current(it.page)) { "原审阅窗口已变化" } }
    private fun choice(page: Page, kind: String, value: JSONObject): String { check(page.choices.size < 1024); val id = UUID.randomUUID().toString(); page.choices[id] = kind to value.toString(); return id }
    private fun selected(page: Page, id: String, kind: String): JSONObject { val pin = page.choices[id] ?: error("原记录已变化，请重新读取"); check(pin.first == kind && current(page)); return JSONObject(pin.second) }
    private fun work(page: Page?, done: (JSONObject?, String?) -> Unit, action: (RunControl) -> JSONObject) {
        check(busy.compareAndSet(false, true)) { "请等待原任务能力回执" }
        val epoch = generation; val captured = owner(); val control = RunControl()
        fun accepted() = epoch == generation && captured == owner() && visible() && (page == null || current(page))
        control.setAuthorityCheck { check(accepted()) { "原任务能力窗口已变化" } }
        try { execute(Runnable {
            val result = runCatching { control.check(); action(control).also { control.check() } }
            main.post {
                busy.set(false)
                if (!accepted()) done(null, "原窗口已变化；请核对原任务回执，没有自动重新提交")
                else result.fold({ done(PocketWebState.publicValue(it) as JSONObject, null) }, { done(null, it.message ?: "原任务能力回执尚未确认") })
            }
        }) } catch (failure: Exception) { busy.set(false); done(null, failure.message ?: "原控制队列不可用") }
    }
    private fun view(page: Page): JSONObject {
        val value = snapshot(page.task); check(value.getString("taskId") == page.task && current(page))
        page.choices.clear()
        rows(value.optJSONArray("executions")).forEach { row ->
            val operation = JSONObject().put("operationId", row.getString("operationId"))
            row.put("operationHandle", choice(page, "operation", operation))
            rows(row.optJSONObject("receipt")?.optJSONArray("artifacts")).forEach { file -> file.put("artifactHandle", choice(page, "artifact", copy(file).put("operationId", operation.getString("operationId")))) }
        }
        rows(value.optJSONArray("pluginNeeds")).forEach { need -> need.put("needHandle", choice(page, "mcp", copy(need))) }
        return value.put("pageHandle", page.id).put("executionAuthorized", false)
    }
    private fun issued(page: Page, script: NativeSkillExecutionHost.Review? = null, command: String? = null, payload: JSONObject? = null, shown: JSONObject): JSONObject = synchronized(lifetime) {
        check(current(page)); reviews.values.filter { it.page === page }.forEach(::retire)
        while (reviews.size >= 24) reviews.remove(reviews.keys.first())?.let(::retire)
        val id = UUID.randomUUID().toString(); reviews[id] = Review(id, page, script, command, payload?.toString(), shown.toString())
        copy(shown).put("reviewHandle", id).put("kind", if (script != null) "script" else command).put("saved", false)
    }
    private fun script(review: Review): NativeSkillExecutionHost.Review {
        val native = checkNotNull(review.script); val host = checkNotNull(review.page.native)
        check(review.alive && current(review.page) && host.pendingReview(review.page.task) === native) { "原任务脚本审阅已结束或已换代" }
        return native
    }
    fun dispatch(action: String, input: JSONObject, done: (JSONObject?, String?) -> Unit) {
        if (action == "taskCapabilities.close") { fields(input, "pageHandle"); pages.remove(text(input, "pageHandle"))?.let(::release); done(JSONObject().put("closed", true), null); return }
        if (action == "taskCapabilities.dismissReview") { fields(input, "reviewHandle"); reviews.remove(text(input, "reviewHandle"))?.let(::retire); done(JSONObject().put("closed", true).put("taskCancelled", false), null); return }
        check(visible()) { "请回到私人任务的前台页面" }
        when (action) {
            "taskCapabilities.open" -> {
                fields(input, "taskId"); val task = text(input, "taskId"); val owner = owner(); val epoch = generation
                work(null, done) {
                    val native = runCatching { host() }.getOrNull(); val page = Page(UUID.randomUUID().toString(), task, owner, native)
                    synchronized(lifetime) { check(epoch == generation && current(page)); check(pages.size < 16); pages[page.id] = page }
                    try { view(page) } catch (failure: Throwable) { pages.remove(page.id); release(page); throw failure }
                }
            }
            "taskCapabilities.refresh" -> { fields(input, "pageHandle"); val page = page(input); work(page, done) { view(page) } }
            "taskCapabilities.review" -> {
                fields(input, "pageHandle"); val page = page(input); val native = checkNotNull(page.native) { "原脚本执行宿主不可用" }
                work(page, done) { val review = native.pendingReview(page.task) ?: error("当前原任务没有待审阅脚本"); val digest = native.validateReview(review); check(digest == review.plan.digest); issued(page, script = review, shown = review.plan.review()) }
            }
            "taskCapabilities.script" -> {
                fields(input, "reviewHandle"); val review = review(input)
                work(review.page, done) { val native = script(review); val value = checkNotNull(review.page.native).readScript(native); script(review); value.put("planDigest", native.plan.digest) }
            }
            "taskCapabilities.consent" -> {
                fields(input, "reviewHandle", "accepted"); val review = review(input); val accepted = input.get("accepted"); require(accepted is Boolean)
                synchronized(review) {
                    check(review.alive && current(review.page)); review.consent++; review.accepted = accepted; review.window?.close(); review.window = null
                    if (accepted && review.script != null) review.window = checkNotNull(review.page.native).openOwnerWindow().also { it.setForeground(true) }
                }
                done(JSONObject().put("accepted", accepted).put("saved", false).put("executionApproved", false), null)
            }
            "taskCapabilities.decide" -> {
                fields(input, "reviewHandle", "approved"); val review = review(input); val approved = input.get("approved"); require(approved is Boolean)
                val native = script(review); val pair = synchronized(review) {
                    check(!review.attempted && (!approved || review.accepted)); review.attempted = true
                    val window = if (approved) checkNotNull(review.window) else checkNotNull(review.page.native).openOwnerWindow().also { it.setForeground(true); review.window = it }
                    review.consent to window
                }
                work(review.page, done) {
                    check(review.alive && review.consent == pair.first && (!approved || review.accepted)) { "本次确认已撤回，请重新审阅" }
                    val host = checkNotNull(review.page.native)
                    if (approved) check(SkillExecutionReviewGuard.canApprove(native.plan.digest, host.validateReview(script(review)), native.plan.expiresAt, System.currentTimeMillis(), false, review.accepted))
                    host.decide(pair.second, native, native.plan.digest, approved)
                    retire(review)
                    JSONObject().put("taskId", review.page.task).put("decisionRecorded", true).put("approved", approved).put("completed", false).put("replayAllowed", false)
                }
            }
            "taskCapabilities.receipt", "taskCapabilities.retrieve" -> {
                fields(input, "pageHandle", "operationHandle"); val page = page(input); val operation = selected(page, text(input, "operationHandle"), "operation").getString("operationId")
                work(page, done) { val host = checkNotNull(page.native); val value = if (action == "taskCapabilities.retrieve") host.retrieveArtifacts(page.task, operation) else host.reconcile(page.task, operation, false)
                    val view = view(page); JSONObject().put("original", value).put("view", view).put("replayAllowed", false).put("retrieved", action == "taskCapabilities.retrieve") }
            }
            "taskCapabilities.artifact" -> {
                fields(input, "pageHandle", "artifactHandle"); val page = page(input); val file = selected(page, text(input, "artifactHandle"), "artifact")
                work(page, done) { val bytes = artifact(page.task, file.getString("operationId"), file.getString("artifactId"), file.getString("sha256")); check(bytes.size.toLong() == file.getLong("bytes")); copy(file).put("base64", Base64.encodeToString(bytes, Base64.NO_WRAP)).put("untrusted", true).put("replayAllowed", false) }
            }
            "taskCapabilities.export" -> {
                fields(input, "pageHandle", "artifactHandle"); val page = page(input); val file = selected(page, text(input, "artifactHandle"), "artifact")
                done(checkNotNull(exporter) { "原系统文件保存入口尚未挂载" }.start(page.task, file), null)
            }
            "taskCapabilities.mcp.review" -> {
                fields(input, "pageHandle", "needHandle", "change", "value"); val page = page(input); val need = selected(page, text(input, "needHandle"), "mcp"); val change = text(input, "change"); val value = input.get("value"); require(value is Boolean && change in setOf("grant", "enabled"))
                work(page, done) {
                    val current = snapshot(page.task); val exact = rows(current.optJSONArray("pluginNeeds")).firstOrNull { it.optString("pluginId") == need.getString("pluginId") && it.optString("tool") == need.getString("tool") } ?: error("原任务已不需要这项工具")
                    val installation = checkNotNull(exact.optJSONObject("installation")) { "原任务所需服务尚未安装，请先在服务页安装" }
                    check(installation.getString("installationId") == need.getJSONObject("installation").getString("installationId")) { "原安装已替换，请刷新" }
                    val all = mcpSnapshot(); val selected = rows(all.optJSONArray("plugins")).first { it.optString("id") == need.getString("pluginId") && it.optString("installationId") == installation.getString("installationId") }
                    val tool = rows(selected.optJSONObject("manifest")?.optJSONArray("tools")).firstOrNull { it.optString("name") == need.getString("tool") } ?: error("原安装不包含任务所需工具")
                    val payload = if (change == "enabled") McpInstallPreviewRules.enablePayload(selected, all, value) else JSONObject().put("id", selected.getString("id")).put("expectedInstallationId", selected.getString("installationId")).put("expectedGrantsDigest", selected.getString("grantsDigest")).put("tool", need.getString("tool")).put("granted", value)
                    if (change == "grant") check(all.optJSONObject("nativeCapabilities")?.opt("setToolGrantReviewed") == true)
                    issued(page, command = if (change == "grant") "setToolGrantReviewed" else "setEnabledReviewed", payload = payload,
                        shown = JSONObject().put("pluginId", selected.getString("id")).put("installationId", selected.getString("installationId")).put("tool", tool).put("change", change).put("value", value))
                }
            }
            "taskCapabilities.mcp.confirm" -> {
                fields(input, "reviewHandle"); val review = review(input); check(review.script == null)
                val consent = synchronized(review) { check(review.alive && review.accepted && !review.attempted); review.attempted = true; review.consent }
                work(review.page, done) { control ->
                    val result = synchronized(review) {
                        check(review.alive && review.accepted && review.consent == consent && current(review.page)); control.check()
                        mcp(checkNotNull(review.command), JSONObject(checkNotNull(review.payload)), control)
                    }
                    retire(review); val expected = JSONObject(checkNotNull(review.payload)); check(result.optString("id") == expected.getString("id") && result.optString("installationId") == expected.getString("expectedInstallationId"))
                    if (review.command == "setEnabledReviewed") check(result.opt("enabled") == expected.get("enabled"))
                    else check((0 until (result.optJSONArray("grants")?.length() ?: 0)).any { result.getJSONArray("grants").optString(it) == "tool:" + expected.getString("tool") } == expected.getBoolean("granted"))
                    JSONObject().put("saved", true).put("installation", result).put("view", view(review.page)).put("toolExecuted", false)
                }
            }
            else -> error("未知任务能力操作")
        }
    }
}
