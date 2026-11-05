package dev.pocketengine.ui.web

import android.os.Handler
import android.os.Looper
import dev.pocketengine.features.teams.TeamMemberEvidenceHost
import dev.pocketengine.features.teams.TeamResearchReadScope
import dev.pocketengine.store.TaskStore
import dev.pocketengine.ui.PocketWorldController
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject

/** Original native member-material Page owns sharing, scope revisions, provenance and approval. */
internal class PocketWebTeamEvidence(
    private val window: () -> Any,
    private val visible: () -> Boolean,
    private val openPage: (String, String) -> TeamMemberEvidenceHost.Page,
    private val runWork: (Runnable) -> Unit
) {
    constructor(controller: PocketWorldController) : this(controller::webWindowTicket, controller::webPrivateOwnerVisible,
        controller::openTeamMemberMaterialsPage, controller::runTeamPhoneWork)

    private class Page(val id: String, val native: TeamMemberEvidenceHost.Page, val owner: Any, val run: String, val member: String) {
        @Volatile var alive = true
        val choices = ConcurrentHashMap<String, Pair<String, String>>()
    }
    private class Review(val id: String, val page: Page, val single: TaskStore.TeamMemberMaterialReview?, val group: TaskStore.TeamMemberMaterialGroupReview?,
                         val shown: String, val revision: Long? = null) {
        val accepted = AtomicBoolean(false); val consent = AtomicLong(0); val attempted = AtomicBoolean(false)
        @Volatile var alive = true
    }
    private val pages = ConcurrentHashMap<String, Page>()
    private val reviews = ConcurrentHashMap<String, Review>()
    private val lifecycle = Any()
    private val busy = AtomicBoolean(false)
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var generation = 0L
    @Volatile private var opening: TeamMemberEvidenceHost.Page? = null
    companion object {
        val COMMANDS = setOf("teamEvidence.open", "teamEvidence.close", "teamEvidence.files", "teamEvidence.review", "teamEvidence.renew",
            "teamEvidence.revoke.review", "teamEvidence.consent", "teamEvidence.confirm", "teamEvidence.cancelReview")
        private fun fields(value: JSONObject, vararg names: String) { require(value.keys().asSequence().all { it in names }) { "资料选择包含不适用字段" } }
        private fun text(value: JSONObject, key: String, max: Int = 200): String { val raw = value.opt(key); require(raw is String && raw.isNotBlank() && raw.length <= max && '\u0000' !in raw) { "请重新选择原资料：$key" }; return raw }
        private fun integer(value: JSONObject, key: String, min: Int, max: Int): Int { val raw = value.opt(key); require((raw is Int || raw is Long) && (raw as Number).toLong() in min.toLong()..max.toLong()) { "范围必须是准确整数：$key" }; return (raw as Number).toInt() }
        private fun copy(value: JSONObject) = JSONObject(value.toString())
        private fun rows(value: JSONArray?) = if (value == null) emptyList() else (0 until value.length()).map { value.getJSONObject(it) }
    }
    fun clear() = synchronized(lifecycle) {
        generation++; reviews.values.forEach { it.alive = false; it.accepted.set(false); it.consent.incrementAndGet() }; reviews.clear()
        pages.values.forEach(::release); pages.clear(); opening?.close(); opening = null
    }
    private fun release(page: Page) { page.alive = false; page.native.setForeground(false); page.native.close(); page.choices.clear(); reviews.values.filter { it.page === page }.forEach { it.alive = false; it.accepted.set(false); it.consent.incrementAndGet() } }
    private fun current(page: Page) = page.alive && visible() && page.owner == window()
    private fun page(args: JSONObject): Page = (pages[text(args, "pageHandle")] ?: error("原成员资料页已关闭")).also { check(current(it)) { "原成员或前台窗口已变化" } }
    private fun review(args: JSONObject) = (reviews[text(args, "reviewHandle")] ?: error("原资料审阅已关闭")).also { check(it.alive && current(it.page)) { "原资料审阅窗口已变化" } }
    private fun choice(page: Page, kind: String, value: JSONObject): String {
        check(page.choices.size < 2048) { "目录过大，请先缩小原资料范围" }
        val id = UUID.randomUUID().toString(); page.choices[id] = kind to value.toString(); return id
    }
    private fun chosen(page: Page, kind: String, handle: String): JSONObject {
        check(current(page)); val original = page.choices[handle] ?: error("所选资料不在原目录中")
        require(original.first == kind) { "资料类型不符" }; return JSONObject(original.second)
    }
    private fun work(page: Page?, done: (JSONObject?, String?) -> Unit, action: () -> JSONObject) {
        check(busy.compareAndSet(false, true)) { "正在核对原资料，请等待回执" }
        val epoch = generation; val owner = window()
        fun current() = epoch == generation && visible() && owner == window() && (page == null || this@PocketWebTeamEvidence.current(page))
        try { runWork(Runnable {
            val result = runCatching { check(current()) { "原资料窗口已关闭" }; action() }
            main.post {
                busy.set(false)
                if (!current()) { opening?.close(); opening = null; done(null, "原资料窗口已变化，请读取原范围；没有重复批准") }
                else result.fold({ done(PocketWebState.publicValue(it) as JSONObject, null) }, { done(null, it.message ?: "原资料回执不可核对") })
            }
        }) } catch (failure: Exception) { busy.set(false); opening?.close(); opening = null; done(null, failure.message ?: "原工作队列不可用") }
    }
    private fun issued(page: Page, single: TaskStore.TeamMemberMaterialReview? = null, group: TaskStore.TeamMemberMaterialGroupReview? = null, revoke: JSONObject? = null): JSONObject = synchronized(lifecycle) {
        check(current(page)); val shown = single?.view() ?: group?.view() ?: revoke ?: error("缺少原审阅")
        reviews.values.filter { it.page === page }.forEach { it.alive = false; it.accepted.set(false); it.consent.incrementAndGet() }
        while (reviews.size >= 16) reviews.remove(reviews.keys.first())?.alive = false
        val id = UUID.randomUUID().toString(); reviews[id] = Review(id, page, single, group, shown.toString(), revoke?.getLong("revision"))
        JSONObject().put("reviewHandle", id).put("scope", if (group != null) "current_members" else "member").put("operation", if (revoke != null) "revoke" else "share").put("review", shown)
    }
    fun dispatch(action: String, args: JSONObject, done: (JSONObject?, String?) -> Unit) {
        if (action == "teamEvidence.close") {
            fields(args, "pageHandle"); pages.remove(text(args, "pageHandle"))?.let(::release); done(JSONObject().put("closed", true), null); return
        }
        if (action == "teamEvidence.cancelReview") {
            fields(args, "reviewHandle"); reviews.remove(text(args, "reviewHandle"))?.let { it.alive = false; it.accepted.set(false); it.consent.incrementAndGet() }
            done(JSONObject().put("closed", true), null); return
        }
        check(visible()) { "请回到前台私人团队页面" }
        when (action) {
            "teamEvidence.open" -> {
                fields(args, "runId", "memberId"); val run = text(args, "runId"); val member = text(args, "memberId"); val owner = window(); val epoch = generation
                work(null, done) {
                    val native = openPage(run, member); opening = native
                    try {
                        check(epoch == generation && visible() && owner == window()); native.setForeground(true)
                        val page = Page(UUID.randomUUID().toString(), native, owner, run, member); val catalog = native.catalogue(); val scope = native.current()
                        for ((key, kind) in listOf("records" to "record", "sources" to "source", "skills" to "skill", "resources" to "resource", "researchProjects" to "project")) rows(catalog.optJSONArray(key)).forEach { it.put("choiceHandle", choice(page, kind, it)) }
                        check(epoch == generation && current(page))
                        synchronized(lifecycle) { check(epoch == generation && current(page)); while (pages.size >= 4) pages.remove(pages.keys.first())?.let(::release); pages[page.id] = page; opening = null }
                        JSONObject().put("pageHandle", page.id).put("runId", run).put("memberId", member).put("catalogue", catalog).put("current", scope)
                    } catch (failure: Exception) { native.close(); opening = null; throw failure }
                }
            }
            "teamEvidence.files" -> {
                fields(args, "pageHandle", "projectHandle"); val page = page(args); val project = chosen(page, "project", text(args, "projectHandle"))
                work(page, done) { page.native.researchProjectFiles(project.getString("id")).also { result -> rows(result.getJSONArray("files")).forEach { it.put("choiceHandle", choice(page, "file", it)) } } }
            }
            "teamEvidence.review" -> {
                fields(args, "pageHandle", "scope", "records", "sources", "skills", "resources", "webOrigins", "projectFiles")
                val page = page(args); val scope = text(args, "scope"); require(scope in setOf("member", "current_members"))
                val input = copy(args); val selected = selection(page, input)
                work(page, done) {
                    if (scope == "current_members") { val review = page.native.reviewTogether(selected); verifyFileVersions(page, input, review.view(), true); issued(page, group = review) }
                    else { val review = page.native.review(selected); verifyFileVersions(page, input, review.view(), false); issued(page, single = review) }
                }
            }
            "teamEvidence.renew" -> { fields(args, "pageHandle"); val page = page(args); work(page, done) { issued(page, single = page.native.reviewRenewal()) } }
            "teamEvidence.revoke.review" -> { fields(args, "pageHandle"); val page = page(args); work(page, done) { val current = page.native.current(); check(current.optString("state") == "active"); issued(page, revoke = current) } }
            "teamEvidence.consent" -> {
                fields(args, "reviewHandle", "accepted"); require(args.opt("accepted") is Boolean); val review = review(args)
                review.consent.incrementAndGet(); review.accepted.set(args.getBoolean("accepted"))
                done(JSONObject().put("accepted", review.accepted.get()).put("scopeSaved", false), null)
            }
            "teamEvidence.confirm" -> {
                fields(args, "reviewHandle"); val review = review(args); check(review.accepted.get()) { "请核对所列成员与资料并明确确认" }
                check(review.attempted.compareAndSet(false, true)) { "这次确认已提交，请核对原范围" }
                val consent = review.consent.get(); val frozen = JSONObject(review.shown)
                work(review.page, done) {
                    check(review.alive && review.accepted.get() && review.consent.get() == consent) { "这次同意已撤回，原资料范围没有提交" }
                    val value = if (review.revision != null) review.page.native.revoke(review.revision)
                        else if (review.group != null) review.page.native.approveTogether(review.group, frozen.getString("digest"))
                        else review.page.native.approve(review.single, frozen.getString("digest"))
                    review.alive = false; review.accepted.set(false)
                    JSONObject().put("result", value).put("current", review.page.native.current()).put("scopeSaved", true).put("executionAuthorized", false)
                }
            }
            else -> error("未接入的原成员资料操作")
        }
    }
    private fun handles(args: JSONObject, key: String, max: Int): List<String> {
        val values = args.getJSONArray(key); require(values.length() <= max); val seen = mutableSetOf<String>()
        return (0 until values.length()).map { val value = values.getString(it); require(seen.add(value)) { "同一资料只选择一次" }; value }
    }
    private fun selection(page: Page, args: JSONObject): JSONObject {
        val records = JSONArray(); handles(args, "records", 64).forEach { val row = chosen(page, "record", it); records.put(JSONObject().put("id", row.getString("id")).put("revision", row.getLong("revision"))) }
        val sources = JSONArray(); val sourceRows = args.getJSONArray("sources"); require(sourceRows.length() <= 64); val selected = mutableSetOf<String>()
        rows(sourceRows).forEach { row -> fields(row, "choiceHandle", "offset", "chars"); val handle = text(row, "choiceHandle"); require(selected.add(handle)); val original = chosen(page, "source", handle); sources.put(JSONObject().put("sourceRef", original.getJSONObject("sourceRef")).put("offset", integer(row, "offset", 0, Int.MAX_VALUE)).put("chars", integer(row, "chars", 1, 24000)).put("unit", "UTF-16")) }
        val skills = JSONArray(); val skillRows = args.getJSONArray("skills"); require(skillRows.length() <= 16); selected.clear()
        rows(skillRows).forEach { row -> fields(row, "choiceHandle", "paths"); val handle = text(row, "choiceHandle"); require(selected.add(handle)); val original = chosen(page, "skill", handle); val paths = row.getJSONArray("paths"); require(paths.length() in 1..16); repeat(paths.length()) { require(paths.getString(it).length <= 500) }; skills.put(JSONObject().put("id", original.getString("id")).put("revision", original.getLong("revision")).put("digest", original.getString("digest")).put("paths", JSONArray(paths.toString()))) }
        val resources = JSONArray(); val resourceRows = args.getJSONArray("resources"); require(resourceRows.length() <= 32); selected.clear()
        rows(resourceRows).forEach { row -> fields(row, "choiceHandle", "offset", "chars"); val handle = text(row, "choiceHandle"); require(selected.add(handle)); val original = chosen(page, "resource", handle); resources.put(JSONObject().put("kind", original.getString("kind")).put("id", original.getString("id")).put("sha256", original.getString("sha256")).put("offset", integer(row, "offset", 0, Int.MAX_VALUE)).put("chars", integer(row, "chars", 1, 24000))) }
        val origins = args.getJSONArray("webOrigins"); require(origins.length() <= 4); val unique = mutableSetOf<String>()
        repeat(origins.length()) { val site = origins.getString(it); require(site.length <= 2048 && unique.add(site) && TeamResearchReadScope.origin(site) == site) { "请选择不重复的公共 HTTPS 站点，不含页面路径" } }
        val files = JSONArray(); handles(args, "projectFiles", 32).forEach { val original = chosen(page, "file", it); files.put(JSONObject().put("projectId", original.getString("projectId")).put("path", original.getString("path"))) }
        return JSONObject().put("allowedRecords", records).put("allowedSources", sources).put("skills", skills).put("resources", resources).put("researchReadAccess", JSONObject().put("webOrigins", JSONArray(origins.toString())).put("projectFiles", files))
    }
    private fun verifyFileVersions(page: Page, args: JSONObject, view: JSONObject, shared: Boolean) {
        val scopes = if (shared) rows(view.getJSONArray("members")).map { it.getJSONObject("review") } else listOf(view)
        handles(args, "projectFiles", 32).forEach { handle ->
            val expected = chosen(page, "file", handle)
            scopes.forEach { scope ->
                val actual = rows(scope.getJSONObject("researchReadAccess").getJSONArray("projectFiles")).single { it.getString("projectId") == expected.getString("projectId") && it.getString("path") == expected.getString("path") }
                for (key in listOf("version", "revisionId", "sha256")) if (expected.has(key)) check(expected.get(key) == actual.get(key)) { "所选文件已换版，请重新读取文件目录和范围" }
            }
        }
    }
}
