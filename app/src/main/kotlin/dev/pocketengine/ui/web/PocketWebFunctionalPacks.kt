package dev.pocketengine.ui.web

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import dev.pocketengine.features.plugins.distribution.FunctionalPackHost
import dev.pocketengine.ui.FunctionalPackDocumentPicker
import dev.pocketengine.ui.objects
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** The web workspace presents the same native Page, Review, selected actions and TaskStore receipts. */
internal class PocketWebFunctionalPacks(
    private val newPage: () -> FunctionalPackHost.Page,
    private val runWork: (Runnable) -> Unit,
    private val ownerCurrent: () -> Boolean,
    private val documents: FunctionalPackDocumentPicker
) {
    private data class Reading(val handle: String, val original: FunctionalPackHost.Review, val expires: Long,
        var selected: Set<String>, var selectionRevision: Long = 0)
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var generation = 0L
    @Volatile private var page: FunctionalPackHost.Page? = null
    private var pageId: String? = null
    private var reading: Reading? = null
    private var snapshot = JSONObject().put("packs", JSONArray())
    private var busy = false

    companion object {
        val COMMANDS = setOf("functionalPacks.open", "functionalPacks.close", "functionalPacks.snapshot",
            "functionalPacks.chooseFile", "functionalPacks.discardFile", "functionalPacks.inspect", "functionalPacks.select",
            "functionalPacks.reviewSelection", "functionalPacks.reviewRemove", "functionalPacks.reviewRestore",
            "functionalPacks.closeReview", "functionalPacks.confirm", "functionalPacks.cancel", "functionalPacks.reconcile", "functionalPacks.journal")
    }

    fun clear() {
        generation++; reading = null; busy = false; pageId = null
        val original = page; page = null; original?.setForeground(false); original?.close()
        snapshot = JSONObject().put("packs", JSONArray())
    }

    private fun fields(args: JSONObject, vararg allowed: String) = require(args.keys().asSequence().all { it in allowed }) { "功能包操作包含不适用的字段" }
    private fun text(args: JSONObject, key: String, max: Int = 160): String {
        val value = args.opt(key); require(value is String && value.isNotBlank() && value.length <= max && '\u0000' !in value) { "请核对$key" }; return value
    }
    private fun number(args: JSONObject, key: String): Long {
        val value = args.opt(key); require(value is Int || value is Long) { "请核对$key" }; return (value as Number).toLong().also { require(it >= 0) }
    }
    private fun public(value: JSONObject) = PocketWebState.publicValue(value) as JSONObject
    private fun view(): JSONObject = JSONObject().put("pageId", pageId).put("snapshot", public(snapshot)).put("document", documents.snapshot())
    private fun review(value: FunctionalPackHost.Review): JSONObject {
        val data = value.view()
        val selected = data.getJSONArray("components").objects().filter { it.optBoolean("selected") }.map { it.getString("key") }.toSet()
        reading = Reading(UUID.randomUUID().toString(), value, SystemClock.elapsedRealtime() + 120_000, selected)
        return reviewed(reading!!)
    }
    private fun reviewed(value: Reading): JSONObject {
        val data = value.original.view()
        // Complete source/declaration text is review material. JSON field names that happen to match
        // a credential name must not hide a tool parameter or the exact component declaration.
        val projected = public(data)
        val rows = projected.getJSONArray("components").objects()
        data.getJSONArray("components").objects().forEach { original ->
            rows.singleOrNull { it.optString("key") == original.optString("key") }?.put("inspectionText", original.getJSONObject("inspection").toString(2))
        }
        return JSONObject().put("pageId", pageId).put("reviewHandle", value.handle).put("selectionRevision", value.selectionRevision)
            .put("selectedKeys", JSONArray(value.selected.toList())).put("selectionMatches", value.original.matchesSelection(value.selected))
            .put("data", projected)
    }
    private fun currentReview(args: JSONObject): Reading {
        val exact = reading
        check(exact != null && exact.handle == text(args, "reviewHandle") && exact.expires > SystemClock.elapsedRealtime()) { "原功能包审阅已关闭或过期，请重新读取" }
        check(exact.selectionRevision == number(args, "selectionRevision")) { "当前组件选择已变化，请重新核对" }
        return exact
    }
    private fun selected(args: JSONObject, exact: Reading): Set<String> {
        val array = args.getJSONArray("selectedKeys"); require(array.length() <= 24)
        val keys = (0 until array.length()).map { index -> array.get(index).also { require(it is String && it.isNotBlank() && it.length <= 80) } as String }
        require(keys.toSet().size == keys.size) { "组件选择不能重复" }
        val available = exact.original.view().getJSONArray("components").objects().filter { it.optBoolean("selectable") }.map { it.getString("key") }.toSet()
        require(available.containsAll(keys)) { "所选组件不在本次准确原包中" }
        return keys.toSet()
    }
    private fun record(args: JSONObject): JSONObject {
        val id = text(args, "id")
        val original = snapshot.getJSONArray("packs").objects().singleOrNull { it.optString("id") == id } ?: error("请重新读取原功能包记录")
        check(original.getString("installationId") == text(args, "installationId") && original.getLong("revision") == number(args, "revision")) { "原功能包安装已变化，请重新读取" }
        return JSONObject(original.toString())
    }
    private fun checkRecord(owner: FunctionalPackHost.Page, expected: JSONObject) {
        val actual = owner.snapshot().getJSONArray("packs").objects().singleOrNull { it.optString("id") == expected.getString("id") }
        check(actual != null && actual.getString("installationId") == expected.getString("installationId") && actual.getLong("revision") == expected.getLong("revision")) { "原功能包安装已变化，请重新读取后审阅" }
    }

    fun dispatch(action: String, args: JSONObject, done: (JSONObject?, String?) -> Unit) {
        check(ownerCurrent()) { "请回到前台私人功能包页面" }
        if (action == "functionalPacks.open") {
            fields(args); clear(); page = newPage().also { it.setForeground(true) }; pageId = UUID.randomUUID().toString()
        } else {
            check(text(args, "pageId") == pageId && page != null) { "原功能包页面已变化，请重新打开" }
        }
        val original = page ?: error("请重新打开功能包页面")
        val ticket = generation
        fun current() = ticket == generation && page === original && ownerCurrent()
        if (action == "functionalPacks.close") { fields(args, "pageId"); clear(); done(JSONObject().put("closed", true), null); return }
        if (action == "functionalPacks.cancel") {
            fields(args, "pageId"); reading = null; original.cancel()
            done(JSONObject().put("cancelRequested", true).put("replayAllowed", false), null); return
        }
        check(!busy) { "请先等待原功能包操作回执" }
        fun <T> work(body: () -> T, accept: (T) -> JSONObject) {
            busy = true
            try { runWork(Runnable {
                val result = runCatching { check(current()) { "原功能包页面已离开，本次请求未开始" }; body() }
                main.post {
                    if (!current()) done(null, "原功能包页面已离开，请重新读取实际安装")
                    else {
                        busy = false
                        result.fold({ value -> runCatching { accept(value) }.fold({ done(it, null) }, { done(null, it.message ?: "原功能包结果待核对") }) },
                            { done(null, it.message ?: "原功能包结果待核对，请读取实际安装") })
                    }
                }
            }) } catch (failure: Exception) { busy = false; done(null, failure.message ?: "工作队列不可用，本次请求未开始") }
        }
        when (action) {
            "functionalPacks.open", "functionalPacks.snapshot" -> {
                if (action.endsWith("snapshot")) fields(args, "pageId")
                work({ original.snapshot() }) { snapshot = it; view() }
            }
            "functionalPacks.chooseFile" -> {
                fields(args, "pageId"); reading = null
                done(JSONObject().put("pageId", pageId).put("document", documents.choose()), null)
            }
            "functionalPacks.discardFile" -> {
                fields(args, "pageId", "requestId"); documents.discard(text(args, "requestId")); done(view(), null)
            }
            "functionalPacks.inspect" -> {
                fields(args, "pageId", "requestId"); val request = text(args, "requestId"); reading = null
                work({ documents.open(request).use(original::inspect) }) { review(it) }
            }
            "functionalPacks.select" -> {
                fields(args, "pageId", "reviewHandle", "selectionRevision", "selectedKeys")
                val exact = currentReview(args); check(!exact.original.view().getBoolean("removing")) { "卸载审阅不能改用安装组件选择" }
                exact.selected = selected(args, exact); exact.selectionRevision = Math.addExact(exact.selectionRevision, 1)
                done(reviewed(exact), null)
            }
            "functionalPacks.reviewSelection" -> {
                fields(args, "pageId", "reviewHandle", "selectionRevision")
                val exact = currentReview(args); val chosen = JSONArray(exact.selected.toList()); reading = null
                work({ original.selectComponents(exact.original, chosen) }) { review(it) }
            }
            "functionalPacks.reviewRemove", "functionalPacks.reviewRestore" -> {
                if (action.endsWith("reviewRestore")) fields(args, "pageId", "id", "installationId", "revision", "targetInstallationId")
                else fields(args, "pageId", "id", "installationId", "revision")
                val expected = record(args); val id = expected.getString("id")
                val target = if (action.endsWith("reviewRestore")) text(args, "targetInstallationId").also { target ->
                    check(expected.optJSONArray("history")?.objects()?.any { it.optString("installationId") == target } == true) { "请先读取准确历史版本" }
                } else null
                reading = null
                work({ checkRecord(original, expected); if (target == null) original.reviewRemove(id) else original.restore(id, target) }) { review(it) }
            }
            "functionalPacks.closeReview" -> {
                fields(args, "pageId", "reviewHandle"); if (reading?.handle == text(args, "reviewHandle")) reading = null
                done(JSONObject().put("closed", true), null)
            }
            "functionalPacks.confirm" -> {
                fields(args, "pageId", "reviewHandle", "selectionRevision", "consent")
                val exact = currentReview(args)
                require(args.opt("consent") == true) { "请先确认本次准确组件和实际安装范围" }
                check(exact.original.ready && exact.original.matchesSelection(exact.selected)) { "当前组件选择尚未审阅，请先重新核对" }
                reading = null
                work({
                    check(exact.expires > SystemClock.elapsedRealtime() && exact.original.matchesSelection(exact.selected)) { "原组件审阅已失效，本次安装未开始" }
                    original.setForeground(current())
                    val receipt = original.submit(exact.original, exact.original.digest)
                    receipt to original.snapshot()
                }) { (receipt, actual) -> snapshot = actual; view().put("receipt", public(receipt)).put("replayAllowed", false) }
            }
            "functionalPacks.reconcile", "functionalPacks.journal" -> {
                fields(args, "pageId", "id", "installationId", "revision")
                val expected = record(args)
                work({
                    checkRecord(original, expected)
                    val value = if (action.endsWith("journal")) original.journal(expected.getString("id")) else original.reconcile(expected.getString("id"))
                    value to original.snapshot()
                }) { (value, actual) -> snapshot = actual; view().put(if (action.endsWith("journal")) "journal" else "receipt", public(value)).put("replayAllowed", false) }
            }
            else -> error("未知功能包操作")
        }
    }
}
