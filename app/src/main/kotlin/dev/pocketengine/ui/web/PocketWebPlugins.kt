package dev.pocketengine.ui.web

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import dev.pocketengine.features.FeatureHost
import dev.pocketengine.features.NativePluginHost
import dev.pocketengine.features.plugins.PluginRevisions
import dev.pocketengine.features.plugins.PluginsModule
import dev.pocketengine.features.plugins.catalog.PluginCatalogSession
import dev.pocketengine.ui.PocketWorldController
import dev.pocketengine.ui.objects
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Presentation handles around the existing native catalog page and reviewed plugin writes. */
internal class PocketWebPlugins(private val controller: PocketWorldController, private val featureHost: FeatureHost) {
    private data class Window(val generation: Long, val ticket: Pair<Triple<Long, Int, Long>, Long>)
    private data class Review(val kind: String, val input: String, val shown: String, val expires: Long, val window: Window)
    private data class CatalogReview(val handle: String, val view: PluginCatalogSession.View, val expires: Long, val window: Window)
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var generation = 0L
    @Volatile private var page: NativePluginHost.Page? = null
    private var catalogReview: CatalogReview? = null
    private var catalogReceipt: JSONObject? = null
    private var installed = JSONArray()
    private val histories = mutableMapOf<String, JSONObject>()
    private val reviews = mutableMapOf<String, Review>()

    companion object {
        val COMMANDS = setOf("plugins.open", "plugins.close", "plugins.snapshot", "plugins.history", "plugins.reviewChange",
            "plugins.reviewInstall", "plugins.reviewRestore", "plugins.confirm", "plugins.closeReview",
            "pluginCatalog.open", "pluginCatalog.read", "pluginCatalog.configure", "pluginCatalog.refresh",
            "pluginCatalog.prepare", "pluginCatalog.readInstalled", "pluginCatalog.history", "pluginCatalog.reviewRestore",
            "pluginCatalog.confirm", "pluginCatalog.cancel", "pluginCatalog.queryInstalled",
            "pluginCatalog.runtime", "pluginCatalog.prepareRuntime")
    }

    fun clear() {
        generation++; catalogReview = null; catalogReceipt = null; reviews.clear(); histories.clear(); installed = JSONArray()
        page?.let(controller::closePluginCatalogPage); page = null
    }
    private fun window() = Window(generation, controller.webWindowTicket())
    private fun current(captured: Window) = captured.generation == generation && captured.ticket == controller.webWindowTicket() && controller.webPrivateOwnerVisible()
    private fun originalModule(): PluginsModule = featureHost.module("plugins") as? PluginsModule ?: error("原插件宿主不可用")
    private fun fields(args: JSONObject, vararg names: String) { require(args.keys().asSequence().all { it in names }) { "插件操作包含不适用的字段" } }
    private fun text(args: JSONObject, name: String, max: Int = 160, empty: Boolean = false): String {
        val value = args.opt(name)
        require(value is String && value.length <= max && '\u0000' !in value && (empty || value.isNotBlank())) { "请核对$name" }
        return value
    }
    private fun bool(args: JSONObject, name: String): Boolean { require(args.opt(name) is Boolean) { "请核对$name" }; return args.getBoolean(name) }
    private fun nativePage(): NativePluginHost.Page = page ?: error("请重新打开原插件页面")
    private fun normalizedInstalled(value: JSONArray) = JSONArray().also { rows -> value.objects().forEach { row ->
        rows.put(JSONObject(row.toString()).put("installationId", PluginRevisions.installationId(row)).put("stateRevision", PluginRevisions.stateRevision(row)))
    } }
    /** Manifests are validated declarations, not credential containers. Keep parameter names such as
     * `token` intact in the full read-only definition while the regular public projection hides secrets. */
    private fun display(value: JSONObject): JSONObject {
        val safe = PocketWebState.publicValue(value) as JSONObject
        value.optJSONObject("manifest")?.let { safe.put("manifestText", it.toString(2)) }
        value.optJSONArray("changedTools")?.let { safe.put("changesText", it.toString(2)) }
        value.optJSONObject("selectedTool")?.let { safe.put("selectedToolText", it.toString(2)) }
        value.optJSONObject("preview")?.let { safe.put("preview", display(it)) }
        value.optJSONObject("packageReview")?.let { safe.put("packageReview", display(it)) }
        return safe
    }
    private fun plugin(args: JSONObject): JSONObject {
        val id = text(args, "id")
        val found = installed.objects().singleOrNull { it.optString("id") == id } ?: error("请重新读取这个原安装")
        check(PluginRevisions.installationId(found) == text(args, "installationId")) { "原安装已变化，请重新读取" }
        val expected = args.opt("stateRevision")
        require(expected is Number && java.math.BigDecimal(expected.toString()).longValueExact() == PluginRevisions.stateRevision(found)) { "原启停或授权状态已变化，请重新读取" }
        return found
    }
    private fun expected(plugin: JSONObject) = JSONObject().put("id", plugin.getString("id"))
        .put("expectedInstallationId", PluginRevisions.installationId(plugin)).put("expectedStateRevision", PluginRevisions.stateRevision(plugin))
        .put("expectedGrantsDigest", plugin.getString("grantsDigest"))
    private fun review(kind: String, input: JSONObject, shown: JSONObject): JSONObject {
        reviews.entries.removeAll { it.value.expires <= SystemClock.elapsedRealtime() }
        check(reviews.size < 8) { "请先关闭旧插件审阅" }
        val handle = UUID.randomUUID().toString()
        reviews[handle] = Review(kind, input.toString(), shown.toString(), SystemClock.elapsedRealtime() + 120_000, window())
        return JSONObject().put("reviewHandle", handle).put("kind", kind).put("data", display(shown))
    }
    private fun render(original: NativePluginHost.Page, value: PluginCatalogSession.View): JSONObject {
        check(original === page && original.session.accepts(original.nativeOwner(), value)) { "原目录读取已经失效" }
        val data = value.data()
        data.optJSONObject("installed")?.optJSONArray("plugins")?.let { rows ->
            installed = normalizedInstalled(rows)
            data.getJSONObject("installed").put("plugins", JSONArray().also { out -> installed.objects().forEach { out.put(display(it)) } })
        }
        data.optJSONObject("review")?.let { data.put("review", display(it)) }
        catalogReview = value.approval?.let { CatalogReview(UUID.randomUUID().toString(), value, SystemClock.elapsedRealtime() + 120_000, window()) }
        catalogReview?.let { data.put("reviewHandle", it.handle).put("reviewKind", value.approval!!.kind) }
        catalogReceipt?.let { data.put("operationReceipt", JSONObject(it.toString())) }
        data.put("runtime", controller.pluginRuntime)
        return PocketWebState.publicValue(data) as JSONObject
    }
    private fun execute(original: NativePluginHost.Page, request: PluginCatalogSession.Request, input: JSONObject, done: (JSONObject?, String?) -> Unit) {
        val pending = original.session.view(original.nativeOwner())
        val captured = window(); catalogReview = null
        val effectful = request.command in setOf("configure", "submit", "jsChange")
        fun present() = current(captured) && original === page
        fun receipt(actual: JSONObject?, recorded: Boolean) = JSONObject().put("requestId", request.requestId)
            .put("command", request.command).put("recorded", recorded).put("replayAllowed", false)
            .put("receipt", actual ?: JSONObject().put("status", "unknown").put("message", "原操作未取得确定回执，请查询当前安装；没有重复提交"))
        try {
            controller.runPluginCatalogWork(Runnable {
                var admitted = false; var settled = false
                try {
                    check(present()) { "原插件页面已离开，本次请求未开始" }
                    original.beforeDispatch(request, pending, input); admitted = true
                    val result = request.execute()
                    // Persist the real receipt even if navigation retired its presentation.
                    val actual = request.receipt()
                    settled = true; val recorded = runCatching { original.settled(request, result, actual) }.isSuccess
                    main.post {
                        if (!present() || !original.session.accepts(original.nativeOwner(), result)) done(null, "原插件窗口已变化，请重新读取实际安装")
                        else try {
                            if (effectful) catalogReceipt = receipt(actual, recorded)
                            done(render(original, result), null)
                        } catch (failure: Exception) { done(null, failure.message ?: "原目录回执待核对") }
                    }
                } catch (failure: Exception) {
                    if (!admitted) runCatching { request.rejected() }
                    val actual = runCatching { request.receipt() }.getOrNull()
                    val recorded = admitted && !settled && runCatching { original.settled(request, null, actual) }.isSuccess
                    main.post {
                        if (present() && admitted && effectful) {
                            catalogReceipt = receipt(actual, recorded)
                            runCatching { render(original, original.session.view(original.nativeOwner())) }
                                .fold({ done(it, null) }, { done(null, "原目录操作结果待核对，请读取当前安装") })
                        } else done(null, failure.message ?: "原插件结果待核对，请读取当前安装")
                    }
                }
            })
        } catch (failure: java.util.concurrent.RejectedExecutionException) {
            request.rejected(); done(null, "工作队列不可用，本次请求未开始")
        }
    }
    private fun catalog(command: String, input: JSONObject, done: (JSONObject?, String?) -> Unit) {
        check(command in setOf("read", "queryInstalled") || !catalogUnknown()) { "原目录操作仍待核对，请读取当前安装，勿重复提交" }
        val original = nativePage()
        execute(original, original.session.begin(original.nativeOwner(), UUID.randomUUID().toString(), command, input), input, done)
    }
    private fun catalogUnknown() = catalogReceipt?.let { !it.optBoolean("recorded") || it.optJSONObject("receipt")?.optString("status") == "unknown" } == true
    private fun feature(command: String, input: JSONObject, done: (JSONObject?, String?) -> Unit, accept: (JSONObject) -> JSONObject) {
        val captured = window(); val module = originalModule()
        try { controller.runPluginCatalogWork(Runnable {
            val result = runCatching {
                check(featureHost.module("plugins") === module) { "原插件宿主已变化" }
                synchronized(module) {
                check(current(captured)) { "原插件窗口已变化，本次读取未开始" }
                module.handle(command, input)
            } }
            main.post {
                if (!current(captured)) done(null, "原插件窗口已变化，请重新读取")
                else result.fold({ value ->
                    try { done(accept(value), null) } catch (failure: Exception) { done(null, failure.message ?: "原插件回执身份不匹配") }
                }, { done(null, it.message ?: "原插件读取未完成") })
            }
        }) } catch (_: java.util.concurrent.RejectedExecutionException) { done(null, "工作队列不可用，本次读取未开始") }
    }
    /** Keep the validation and the original module write under the very same module monitor.
     * No executor, installation store, grant authority or retry path is created here. */
    private fun mutate(captured: Review, input: JSONObject, done: (JSONObject?, String?) -> Unit) {
        val module = originalModule(); val shown = JSONObject(captured.shown); val id = shown.getString("id")
        try { controller.runPluginCatalogWork(Runnable {
            var entered = false
            val result = runCatching {
                check(featureHost.module("plugins") === module) { "原插件宿主已变化" }
                synchronized(module) {
                check(current(captured.window)) { "原审阅窗口已变化，本次变更未开始" }
                val currentRow = module.snapshot().getJSONArray("plugins").objects().singleOrNull { it.optString("id") == id }
                val expectedInstallation = input.getString("expectedInstallationId")
                val expectedNumber = input.get("expectedStateRevision")
                require(expectedNumber is Number) { "原审阅缺少准确状态版本" }
                val expectedRevision = java.math.BigDecimal(expectedNumber.toString()).longValueExact()
                check(if (currentRow == null) expectedInstallation.isEmpty() && expectedRevision == 0L
                    else PluginRevisions.installationId(currentRow) == expectedInstallation && PluginRevisions.stateRevision(currentRow) == expectedRevision) { "原安装或启停状态已变化，请重新读取后审阅" }
                if (input.has("expectedGrantsDigest")) check(currentRow?.optString("grantsDigest") == input.getString("expectedGrantsDigest")) { "原工具授权已变化，请重新审阅" }
                check(current(captured.window) && captured.expires > SystemClock.elapsedRealtime()) { "原审阅窗口已失效，本次变更未开始" }
                entered = true
                val actual = when (captured.kind) {
                    "install" -> module.handle("install", input)
                    "restore" -> module.handle("rollback", input)
                    "enabled" -> module.setEnabledReviewed(id, expectedInstallation, expectedRevision, input.getString("expectedGrantsDigest"), input.getBoolean("enabled"))
                    "grant" -> module.setToolGrantReviewed(id, expectedInstallation, input.getString("expectedGrantsDigest"), input.getString("tool"), input.getBoolean("granted"))
                    "remove" -> module.handle("remove", JSONObject().put("id", id))
                    else -> error("插件操作尚未接入")
                }
                if (captured.kind == "remove") check(actual.optBoolean("removed")) { "原卸载未取得确定回执" }
                else {
                    check(actual.optString("id") == id) { "原插件回执身份不匹配" }
                    if (captured.kind in setOf("install", "restore")) check(PluginRevisions.installationId(actual) != expectedInstallation && actual.getJSONObject("manifest").getString("version") == shown.getString("targetVersion") && !actual.getBoolean("enabled") && actual.getJSONArray("grants").length() == 0) { "新安装回执与原审阅不匹配" }
                    else {
                        check(PluginRevisions.installationId(actual) == expectedInstallation && PluginRevisions.stateRevision(actual) >= expectedRevision) { "原安装回执身份不匹配" }
                        if (captured.kind == "enabled") check(actual.getBoolean("enabled") == input.getBoolean("enabled")) { "原启停回执不匹配" }
                        if (captured.kind == "grant") check((0 until actual.getJSONArray("grants").length()).any { actual.getJSONArray("grants").optString(it) == "tool:${input.getString("tool")}" } == input.getBoolean("granted")) { "原工具授权回执不匹配" }
                    }
                }
                JSONObject().put("confirmed", true).put("status", "confirmed").put("kind", captured.kind).put("id", id)
                    .put("previousInstallationId", expectedInstallation).put("previousStateRevision", expectedRevision)
                    .put("replayAllowed", false).put("result", display(if (captured.kind == "remove") actual else JSONObject(actual.toString()).put("installationId", PluginRevisions.installationId(actual))))
            } }
            if (entered) runCatching { featureHost.sendState() }
            main.post {
                if (!current(captured.window)) done(null, "原插件窗口已变化，请核对当前安装；没有重复提交")
                else result.fold({ done(it, null) }, { failure ->
                    if (!entered) done(null, failure.message ?: "原插件变更未开始")
                    else done(JSONObject().put("confirmed", false).put("status", "unknown").put("kind", captured.kind).put("id", id)
                        .put("previousInstallationId", input.getString("expectedInstallationId")).put("previousStateRevision", input.get("expectedStateRevision"))
                        .put("replayAllowed", false).put("message", "原变更未取得确定回执，请读取当前安装核对；本次审阅不会再次提交"), null)
                })
            }
        }) } catch (_: java.util.concurrent.RejectedExecutionException) { done(null, "工作队列不可用，本次变更未开始，请重新审阅") }
    }
    private fun jsPlugin(args: JSONObject): JSONObject {
        val id = text(args, "id"); val original = nativePage()
        val row = original.session.view(original.nativeOwner()).data().getJSONObject("jsInstalled").getJSONArray("plugins")
            .objects().singleOrNull { it.optString("id") == id } ?: error("请先读取这个原扩展安装")
        check(row.getString("installationId") == text(args, "installationId")) { "原源码安装已变化，请重新读取" }
        val revision = args.opt("approvalRevision")
        check(revision is Number && java.math.BigDecimal(revision.toString()).longValueExact() == row.getLong("approvalRevision")) { "原源码批准状态已变化，请重新读取" }
        return row
    }
    private fun sameJs(original: JSONObject, actual: JSONObject?) = actual != null && original.getString("id") == actual.optString("id") &&
        original.getString("installationId") == actual.optString("installationId") && original.getLong("approvalRevision") == actual.optLong("approvalRevision", -1) && original.getString("sourceSha256") == actual.optString("sourceSha256")
    fun dispatch(action: String, args: JSONObject, done: (JSONObject?, String?) -> Unit) {
        check(controller.webPrivateOwnerVisible()) { "请回到前台私人插件页面" }
        if (action !in setOf("plugins.open", "pluginCatalog.open", "plugins.close")) nativePage()
        when (action) {
            "plugins.open", "pluginCatalog.open" -> {
                fields(args); clear(); page = controller.openPluginCatalogPage()
                catalog(if (action == "plugins.open") "queryInstalled" else "read", JSONObject(), done)
            }
            "plugins.close" -> {
                fields(args, "pageRequestId"); val original = page
                val matches = original != null && original.session.view(original.nativeOwner()).data().optString("pageRequestId") == text(args, "pageRequestId")
                if (matches) clear()
                done(JSONObject().put("closed", matches), null)
            }
            "plugins.snapshot", "pluginCatalog.queryInstalled" -> { fields(args); catalog("queryInstalled", JSONObject(), done) }
            "pluginCatalog.read" -> { fields(args); catalog("read", JSONObject(), done) }
            "pluginCatalog.cancel" -> {
                fields(args, "reviewHandle"); val original = nativePage()
                check(catalogReview?.handle == text(args, "reviewHandle")) { "原目录审阅已变化" }
                done(render(original, original.session.cancel(original.nativeOwner())), null)
            }
            "pluginCatalog.runtime" -> { fields(args); nativePage(); done(PocketWebState.publicValue(controller.pluginRuntime) as JSONObject, null) }
            "pluginCatalog.prepareRuntime" -> { fields(args); nativePage(); controller.preparePluginRuntime(); done(PocketWebState.publicValue(controller.pluginRuntime) as JSONObject, null) }
            "plugins.closeReview" -> { fields(args, "reviewHandle"); reviews.remove(text(args, "reviewHandle")); done(JSONObject().put("closed", true), null) }
            "plugins.history" -> {
                fields(args, "id", "installationId", "stateRevision"); val original = plugin(args)
                feature("history", JSONObject().put("id", original.getString("id")), done) { value ->
                    check(value.optString("id") == original.getString("id") && value.optString("currentInstallationId") == PluginRevisions.installationId(original)
                        && value.getLong("currentStateRevision") == PluginRevisions.stateRevision(original)) { "原安装已变化，请重新读取插件" }
                    histories[value.getString("id")] = JSONObject(value.toString())
                    val safe = display(value); safe.put("versions", JSONArray().also { out -> value.getJSONArray("versions").objects().forEach { out.put(display(it)) } }); safe
                }
            }
            "plugins.reviewChange" -> {
                fields(args, "id", "installationId", "stateRevision", "kind", "enabled", "tool", "granted")
                val original = plugin(args); val kind = text(args, "kind", 24); val input = expected(original)
                val shown = JSONObject(original.toString())
                when (kind) {
                    "enabled" -> { input.put("enabled", bool(args, "enabled")); shown.put("nextEnabled", input.getBoolean("enabled")) }
                    "grant" -> {
                        val tool = text(args, "tool", 160)
                        val definition = original.getJSONObject("manifest").getJSONArray("tools").objects().singleOrNull { it.optString("name") == tool } ?: error("原安装没有这个工具")
                        input.put("tool", tool).put("granted", bool(args, "granted")); shown.put("selectedTool", definition).put("nextGranted", input.getBoolean("granted"))
                    }
                    "remove" -> Unit
                    else -> error("插件操作尚未接入")
                }
                done(review(kind, input, shown), null)
            }
            "plugins.reviewInstall" -> {
                fields(args, "manifestText"); val manifest = JSONObject(text(args, "manifestText", 131072))
                feature("previewInstall", JSONObject().put("manifest", manifest), done) { value ->
                    check(value.optString("id") == manifest.getString("id") && value.getJSONObject("manifest").getString("id") == manifest.getString("id"))
                    val input = JSONObject().put("manifest", value.getJSONObject("manifest")).put("expectedInstallationId", value.getString("expectedInstallationId")).put("expectedStateRevision", value.getLong("expectedStateRevision"))
                    review("install", input, value)
                }
            }
            "plugins.reviewRestore" -> {
                fields(args, "id", "installationId", "stateRevision", "targetInstallationId")
                val original = plugin(args); val history = histories[original.getString("id")] ?: error("请先读取原版本历史")
                check(history.optString("currentInstallationId") == PluginRevisions.installationId(original) && history.getLong("currentStateRevision") == PluginRevisions.stateRevision(original))
                val target = text(args, "targetInstallationId")
                val old = history.getJSONArray("versions").objects().singleOrNull { it.optString("installationId") == target } ?: error("原历史版本不在已读取窗口")
                val input = expected(original).also { it.remove("expectedGrantsDigest") }.put("targetInstallationId", target)
                feature("previewRollback", input, done) { value ->
                    check(value.optBoolean("restoration") && value.optString("id") == original.getString("id") && value.optString("targetInstallationId") == target
                        && value.optString("targetVersion") == old.getJSONObject("manifest").getString("version")) { "原恢复预览身份已变化" }
                    review("restore", input, value)
                }
            }
            "plugins.confirm" -> {
                fields(args, "reviewHandle", "token")
                val captured = reviews.remove(text(args, "reviewHandle")) ?: error("这次原审阅已关闭或使用，请先核对当前安装")
                check(current(captured.window) && captured.expires > SystemClock.elapsedRealtime()) { "原审阅已过期或窗口已变化，请重新核对" }
                val input = JSONObject(captured.input)
                val token = if (args.has("token")) text(args, "token", 4096, true) else ""
                require('\r' !in token && '\n' !in token) { "工具凭据不能包含换行" }
                check(token.isEmpty() || captured.kind in setOf("install", "restore")) { "这项操作不接收凭据" }
                if (token.isNotEmpty()) input.put("token", token)
                mutate(captured, input, done)
            }
            "pluginCatalog.configure" -> {
                fields(args, "sources", "expectedRevision")
                val value = nativePage().session.view(nativePage().nativeOwner()).data()
                check(text(args, "expectedRevision") == value.getJSONObject("config").getString("revision")) { "原来源配置已变化，请重新读取" }
                require(args.getJSONArray("sources").length() <= 16)
                catalog("configure", JSONObject(args.toString()), done)
            }
            "pluginCatalog.refresh", "pluginCatalog.prepare" -> {
                if (action.endsWith("refresh")) fields(args, "kind", "sourceId") else fields(args, "kind", "sourceId", "id", "version")
                val kind = text(args, "kind", 24); require(kind in setOf("declaration", "js"))
                val sourceId = text(args, "sourceId"); val value = nativePage().session.view(nativePage().nativeOwner()).data()
                require(value.getJSONObject("config").getJSONArray("sources").objects().any { it.optString("id") == sourceId && it.optBoolean("enabled") }) { "请先读取已启用的原目录来源" }
                val input = JSONObject().put("sourceId", sourceId)
                if (action.endsWith("prepare")) {
                    val id = text(args, "id"); val version = text(args, "version", 80)
                    val catalog = value.getJSONObject("catalog")
                    require(catalog.getJSONObject("source").getString("id") == sourceId && (catalog.optString("kind") == "executable_js") == (kind == "js"))
                    require(catalog.getJSONArray("entries").objects().any { it.optString("id") == id && it.optString("version") == version }) { "请在本次原目录中选择版本" }
                    input.put("id", id).put("version", version)
                }
                val command = if (action.endsWith("prepare")) { if (kind == "js") "jsPrepare" else "prepare" } else { if (kind == "js") "jsRefresh" else "refresh" }
                catalog(command, input, done)
            }
            "pluginCatalog.readInstalled", "pluginCatalog.history", "pluginCatalog.reviewRestore" -> {
                if (action.endsWith("reviewRestore")) fields(args, "id", "installationId", "approvalRevision", "targetInstallationId") else fields(args, "id", "installationId", "approvalRevision")
                val selected = jsPlugin(args); val id = selected.getString("id")
                val original = nativePage(); val value = original.session.view(original.nativeOwner()).data()
                val input = JSONObject().put("id", id)
                val command = when {
                    action.endsWith("readInstalled") -> "jsReviewInstalled"
                    action.endsWith("history") -> "jsHistory"
                    else -> {
                        val target = text(args, "targetInstallationId"); val history = value.getJSONObject("jsHistory")
                        require(history.optString("id") == id && history.getJSONArray("versions").objects().any { it.optString("installationId") == target && it.optBoolean("restorable") })
                        input.put("installationId", target); "jsPrepareRestore"
                    }
                }
                catalog(command, input) { result, error ->
                    if (result != null && result.optString("status") == "ready") {
                        val actual = when (command) {
                            "jsReviewInstalled" -> result.optJSONObject("review")
                            "jsPrepareRestore" -> result.optJSONObject("review")?.optJSONObject("current")
                            else -> result.optJSONObject("jsInstalled")?.optJSONArray("plugins")?.objects()?.singleOrNull { it.optString("id") == id }
                        }
                        if (!sameJs(selected, actual)) {
                            catalogReview = null; runCatching { original.session.cancel(original.nativeOwner()) }
                            done(null, "原源码安装或批准状态已变化，请重新读取后审阅")
                        } else done(result, error)
                    } else done(result, error)
                }
            }
            "pluginCatalog.confirm" -> {
                fields(args, "reviewHandle", "operation")
                check(!catalogUnknown()) { "原目录操作仍待核对，请读取当前安装，勿重复提交" }
                val selected = catalogReview ?: error("请先读取本次原安装或源码审阅")
                check(selected.handle == text(args, "reviewHandle") && selected.expires > SystemClock.elapsedRealtime() && current(selected.window)) { "原审阅已关闭、过期或窗口已变化" }
                val original = nativePage(); check(original.session.accepts(original.nativeOwner(), selected.view))
                val data = selected.view.data(); check(data.optString("status") == "ready" && !data.optBoolean("busy") && !data.optBoolean("unresolved"))
                val operation = text(args, "operation", 24)
                val input = if (operation == "install") JSONObject() else JSONObject().put("action", operation)
                val request = if (operation == "install") original.session.submit(original.nativeOwner(), UUID.randomUUID().toString(), selected.view.approval)
                    else original.session.changeJs(original.nativeOwner(), UUID.randomUUID().toString(), selected.view.approval, operation)
                execute(original, request, input, done)
            }
            else -> error("插件页面操作尚未接入")
        }
    }
}
