package dev.pocketengine.ui.web

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import dev.pocketengine.core.RunControl
import dev.pocketengine.features.local.project.CheckoutTextMatches
import dev.pocketengine.features.local.project.CheckoutPathMatches
import dev.pocketengine.features.local.project.ProjectFileDraft
import dev.pocketengine.ui.ProjectAdoptedFormalFile
import dev.pocketengine.features.workspace.WorkspaceModule
import dev.pocketengine.ui.PocketWorldController
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Native owner adapter for the existing private-file draft/cache and SHA-CAS
 * writer. Formal versions use a separate, single-use native import review;
 * neither path authorizes live source edits or code execution. */
internal class PocketWebCheckoutFiles(private val controller: PocketWorldController) {
    private class Page(val handle: String, val project: String, val checkout: String, val parentCurrent: () -> Boolean, val sourcePath: String? = null, @Volatile var initialPhoneSha: String? = null) {
        @Volatile var matches: CheckoutPathMatches? = null
        @Volatile var matchesUntil: Long = 0
        @Volatile var textPage: CheckoutTextMatches? = null
        @Volatile var textUntil: Long = 0
        @Volatile var savedEdit: SavedEdit? = null
        var priorSavedFile: ListedSave? = null // Main-thread selection from this exact list response.
        val git = PocketWebCheckoutGit(project, checkout)
        var opened: Opened? = null
        var adoption: Adoption? = null
    }
    // Only a foreground, path-and-hash receipt. The private editor already has
    // the two bounded texts; no second history store or writer is created here.
    data class SavedEdit(val id: String, val path: String, val beforeHash: String, val savedHash: String) {
        fun view() = JSONObject().put("id",id).put("path",path).put("beforeHash",beforeHash).put("savedHash",savedHash)
    }
    private data class ListedSave(val id: String, val path: String, val sha: String)
    private class Adoption(val handle: String, val file: Opened, val revision: Long, val review: JSONObject, val expires: Long)
    private class Opened(val handle: String, var draft: ProjectFileDraft) {
        var sequence = 0L
        var saveUnknown = false
        @Volatile var formal: ProjectAdoptedFormalFile? = null
    }
    private val main = Handler(Looper.getMainLooper())
    private val store = controller.projectWorkbench.fileDrafts
    @Volatile private var page: Page? = null
    private var busy = false
    companion object {
        val COMMANDS = setOf("projects.development.files.open", "projects.development.files.openSource", "projects.development.files.openHostChange", "projects.development.files.list", "projects.development.files.find", "projects.development.files.findNext", "projects.development.files.readFound", "projects.development.files.read",
            "projects.development.files.findText", "projects.development.files.findTextNext", "projects.development.files.readTextHit",
            "projects.development.files.edit", "projects.development.files.save", "projects.development.files.resolve",
            "projects.development.files.cache", "projects.development.files.close", "projects.development.files.reviewImport",
            "projects.development.files.import", "projects.development.files.dismissImport", "projects.development.files.openFormal")
    }
    fun clear() { val old = page; page = null; busy = false;old?.matches=null; old?.textPage=null; old?.priorSavedFile=null; old?.opened?.let { store.release(it.draft) } }
    fun closedForOriginalDraftRead() = page == null
    private fun current(original: Page) = page === original && original.parentCurrent() && controller.webPrivateOwnerVisible() && controller.state.projectId == original.project
    private fun fields(args: JSONObject, vararg allowed: String) = require(args.keys().asSequence().all { it in allowed }) { "副本文件操作包含未声明字段" }
    private fun text(args: JSONObject, key: String, max: Int = 80, blank: Boolean = false): String {
        val value = args.opt(key); require(value is String && value.length <= max && '\u0000' !in value && (blank || value.isNotBlank())) { "请核对$key" }; return value
    }
    private fun number(args: JSONObject, key: String): Long {
        val value = args.opt(key); require(value is Int || value is Long) { "请核对$key" }; return (value as Number).toLong()
    }
    private fun selected(args: JSONObject): Page {
        val original = page; check(original != null && current(original) && original.handle == text(args, "filesHandle")) { "原私人副本文件窗口已关闭，请重新打开" }; return original
    }
    /** The existing foreground save receipt can only be used for this ordinary editor and open file. */
    fun savedEditForBuild(project: String, checkout: String, filesHandle: String, saveId: String): SavedEdit {
        val original = page
        check(original != null && original.project == project && original.checkout == checkout && original.handle == filesHandle &&
            original.sourcePath == null && !busy && current(original)) { "原文件窗口已变化，未核对其他副本。" }
        return synchronized(store) {
            val receipt = original.savedEdit; val file = original.opened
            check(receipt != null && receipt.id == saveId && file != null && !file.saveUnknown && !file.draft.dirty() && !file.draft.conflict() &&
                file.draft.path == receipt.path && file.draft.baseHash == receipt.savedHash) { "原文件保存回执已变化，请先核对原文件。" }
            receipt
        }
    }
    private fun textPage(original: Page,args: JSONObject): CheckoutTextMatches {
        check(original.sourcePath==null && SystemClock.elapsedRealtime()<original.textUntil){"原副本搜索已结束，请重新查找"}
        val value=original.textPage
        check(value!=null && value.handle==text(args,"textHandle")){"请重新打开这次搜索的原页"}
        return value
    }
    private fun opened(original: Page, args: JSONObject): Opened {
        val file = original.opened; check(file != null && file.handle == text(args, "fileHandle")) { "原文件已切换，请重新读取" }; return file
    }
    private fun requireDraft(file: Opened) {
        val value = file.draft
        check(store.open(value.projectId, value.checkoutId, value.path, value.creating) === value) { "另一编辑器已更新这份草稿，当前文字仍保留，请重新读取后比较" }
    }
    private fun info(original: Page, file: Opened, body: Boolean): JSONObject {
        val value = file.draft
        val result = JSONObject().put("filesHandle", original.handle).put("fileHandle", file.handle).put("projectId", original.project)
            .put("checkoutId", original.checkout).put("path", value.path).put("revision", value.revision).put("editSequence", file.sequence)
            .put("dirty", value.dirty()).put("conflict", value.conflict()).put("baseHash", value.baseHash).put("saveUnknown", file.saveUnknown)
            .put("draftDurable", store.durable(value)).put("draftError", store.persistenceError(value) ?: "")
            .put("projectVersionWritten", false).put("executionAuthorized", false)
        file.formal?.let { result.put("formalFile", it.position()) }
        if (body) result.put("text", value.text).put("baseContent", value.baseContent).put("latest", value.latestReview() ?: JSONObject.NULL)
        return result
    }
    private fun workspace() = controller.nativeButlerHost().features.module("workspace") as WorkspaceModule
    private fun work(original: Page, done: (JSONObject?, String?) -> Unit, block: () -> JSONObject) =
        observe(original, done, { it }, block)
    private fun <T> observe(original: Page, done: (JSONObject?, String?) -> Unit, present: (T) -> JSONObject, block: () -> T) {
        check(!busy) { "请等待原文件操作回执" }; busy = true
        try { controller.runPluginCatalogWork(Runnable {
            val result = runCatching { check(current(original)) { "原副本窗口已关闭，未开始排队操作" }; block() }
            main.post {
                if (page === original) busy = false
                if (!current(original)) done(null, "原副本窗口已变化；已有草稿保留，保存结果请重新读取原文件核对")
                else result.mapCatching(present).fold({ value ->
                    if (value.has("importStatus")) runCatching { controller.requestFeature("workspace", "list", JSONObject().put("projectId", original.project)) }
                        .onFailure { value.put("projectListRefreshMessage", "正式文件列表尚未刷新；请重新选择原项目读取，不重复采纳。") }
                    done(PocketWebState.publicValue(value) as JSONObject, null)
                }, { done(null, it.message ?: "原文件操作未完成，草稿仍保留") })
            }
        }) } catch (failure: Exception) { busy = false; throw failure }
    }
    private fun listing(original: Page, after: String, expected: String): JSONObject {
        check(original.sourcePath == null) { "原单文件入口不能读取副本文件列表" }
        val source = controller.projectDevelopmentForWeb().ordinaryEditorFilesForOwner(original.project, original.checkout)
        val digest = source.getString("sourceDigest")
        check(after.isBlank() || expected == digest) { "副本文件在翻页期间变化，请重新读取列表，不拼接不同版本" }
        val all = sortedMapOf<String, JSONObject>(); val files = source.getJSONArray("files")
        for (i in 0 until files.length()) { val row = files.getJSONObject(i); all[row.getString("path")] = JSONObject(row.toString()) }
        var cacheError = ""
        try { for (path in store.dirtyPaths(original.project, original.checkout)) all.getOrPut(path) { JSONObject().put("path", path).put("change", "draft_only") }.put("hasDraft", true) }
        catch (failure: Exception) { cacheError = failure.message ?: "部分保留草稿暂无法读取，未删除" }
        val selected = all.entries.filter { it.key > after }.take(41)
        return JSONObject().put("filesHandle", original.handle).put("projectId", original.project).put("checkoutId", original.checkout)
            .put("sourceDigest", digest).put("files", JSONArray(selected.take(40).map { it.value })).put("fileCount", source.getInt("fileCount"))
            .put("nextPath", if (selected.size > 40) selected[39].key else "").put("draftError", cacheError).put("readOnly", true)
            .putOpt("priorSavedFile", source.optJSONObject("priorSavedFile"))
            .put("gitAvailable", runCatching { PocketWebCheckoutGit.available(controller, original.checkout) }.getOrDefault(false))
    }
    private fun presentListing(original: Page, value: JSONObject): JSONObject {
        original.priorSavedFile = null
        val position = value.optJSONObject("priorSavedFile")
        val path = position?.opt("path") as? String
        val sha = position?.opt("sha256") as? String
        if (current(original) && original.sourcePath == null && original.opened == null && position != null &&
            value.opt("readOnly") == true && value.opt("filesHandle") == original.handle &&
            value.opt("projectId") == original.project && value.opt("checkoutId") == original.checkout &&
            position.opt("format") == "pocketworld.owner-checkout-file-save-position.v1" &&
            position.opt("projectId") == original.project && position.opt("checkoutId") == original.checkout &&
            path != null && path.isNotBlank() && path.length <= 240 && sha != null && sha.matches(Regex("[a-f0-9]{64}"))) {
            val listed = ListedSave(UUID.randomUUID().toString(), path, sha)
            position.put("readId", listed.id)
            original.priorSavedFile = listed
        } else value.remove("priorSavedFile")
        return value
    }
    fun dispatch(action: String, args: JSONObject, project: String, parentCurrent: () -> Boolean, done: (JSONObject?, String?) -> Unit) {
        require(action in COMMANDS)
        if (action == "projects.development.files.close") {
            fields(args, "viewHandle", "filesHandle")
            if (page?.handle == text(args, "filesHandle")) clear()
            done(JSONObject().put("closed", true).put("taskStopped", false), null); return
        }
        if (action in setOf("projects.development.files.open", "projects.development.files.openSource", "projects.development.files.openHostChange")) {
            val pin = if (action.endsWith(".openSource")) {
                fields(args, "viewHandle", "checkoutId", "sourceMember")
                JSONObject(args.getJSONObject("sourceMember").toString()).also { text(it, "path", 240) }
            } else null
            val host = if (action.endsWith(".openHostChange")) {
                fields(args, "viewHandle", "checkoutId", "hostFile")
                JSONObject(args.getJSONObject("hostFile").toString()).also {
                    fields(it, "path", "evidenceDigest", "originalSha256", "phoneSha256"); text(it, "path", 240)
                    for (key in listOf("evidenceDigest", "originalSha256", "phoneSha256")) require(text(it, key, 64).matches(Regex("[a-f0-9]{64}")))
                }
            } else null
            if (pin == null && host == null) fields(args, "viewHandle", "checkoutId")
            val checkout = text(args, "checkoutId")
            check(parentCurrent() && controller.webPrivateOwnerVisible()) { "原项目窗口已关闭" }
            clear(); val original = Page(UUID.randomUUID().toString(), project, checkout, parentCurrent, pin?.getString("path") ?: host?.getString("path"), host?.getString("phoneSha256")); page = original
            observe(original, done, { value: JSONObject -> if (original.sourcePath == null) presentListing(original, value) else value }) {
                if (host != null) {
                    // Origin and phone bytes are native evidence, not a path-only editor deep link.
                    val actual = controller.projectDevelopmentForWeb().readChangedHostTextForOwner(project, checkout, host.getString("evidenceDigest"),
                        host.getString("path"), host.getString("originalSha256"), host.getString("phoneSha256")) { current(original) }
                    check(actual.getString("projectId") == project && actual.getString("checkoutId") == checkout && host.keys().asSequence().all { actual.getString(it) == host.getString(it) }) {
                        "原主机文件或本机已保存版本不符，未打开替代文件"
                    }
                    JSONObject().put("filesHandle", original.handle).put("projectId", project).put("checkoutId", checkout).put("hostFile", host)
                        .put("readOnly", true).put("executionAuthorized", false)
                } else if (pin == null) listing(original, "", "") else {
                    // Re-read the private task's producing SQL operation and immutable ZIP member;
                    // a suggested checkout/path from the renderer is never a provenance credential.
                    val saved = TaskProjectPackageFiles.read(controller.nativeButlerHost(), pin, RunControl())
                    check(saved.getString("projectId") == project && saved.getString("checkoutId") == checkout && saved.getString("path") == original.sourcePath) {
                        "文件不属于原工作的这个项目副本，没有改用其他副本"
                    }
                    JSONObject().put("filesHandle", original.handle).put("projectId", project).put("checkoutId", checkout)
                        .put("savedMember", saved).put("readOnly", true).put("executionAuthorized", false)
                }
            }; return
        }
        val original = selected(args)
        val readPriorSave = action == "projects.development.files.read" && args.has("priorSavedFileId")
        if (!readPriorSave && action in setOf("projects.development.files.list", "projects.development.files.find", "projects.development.files.findNext", "projects.development.files.readFound", "projects.development.files.read", "projects.development.files.edit",
                "projects.development.files.resolve", "projects.development.files.save")) original.adoption = null
        if (action in setOf("projects.development.files.reviewImport", "projects.development.files.import",
                "projects.development.files.edit", "projects.development.files.save", "projects.development.files.resolve")) original.opened?.formal = null
        when (action) {
            "projects.development.files.openFormal" -> {
                fields(args, "viewHandle", "filesHandle", "fileHandle", "formalHandle")
                val file = opened(original, args); val pin = file.formal
                check(pin != null && pin.handle == text(args, "formalHandle") && pin.project == original.project &&
                    pin.checkout == original.checkout && pin.path == file.draft.path) { "原正式修订定位已结束，请在同一文件重新比较" }
                fun sameWindow() = current(original) && original.opened === file && file.formal === pin && controller.state.filePath == null &&
                    !file.saveUnknown && synchronized(store) { requireDraft(file); !file.draft.dirty() }
                check(sameWindow()) { "原副本仍有草稿或已切换，没有代开正式文件" }
                original.adoption = null // Opening the original version never applies a pending import.
                observe(original, done, { loaded ->
                    check(sameWindow()) { "原副本窗口已变化，没有覆盖其他编辑" }; controller.showAdoptedFormalFile(loaded)
                    pin.position().put("opened", true).put("projectVersionWritten", false).put("executionAuthorized", false)
                }) { pin.read(workspace()) }; return
            }
            "projects.development.files.dismissImport" -> {
                fields(args, "viewHandle", "filesHandle", "importHandle")
                if (original.adoption?.handle == text(args, "importHandle")) original.adoption = null
                done(JSONObject().put("dismissed", true).put("projectVersionWritten", false), null)
            }
            "projects.development.files.reviewImport" -> {
                fields(args, "viewHandle", "filesHandle", "fileHandle"); val file = opened(original, args)
                val revision = synchronized(store) {
                    requireDraft(file); check(!file.draft.dirty() && !file.saveUnknown) { "先保存或处理这份草稿，再比较实际副本与正式版本" }; file.draft.revision
                }
                original.adoption = null
                work(original, done) {
                    val reviewed = controller.projectDevelopmentForWeb().reviewFileImportForOwner(original.project, original.checkout, file.draft.path)
                    synchronized(store) { requireDraft(file); check(file.draft.revision == revision && file.draft.baseHash == reviewed.getString("expectedHash")) { "副本或草稿在比较期间变化，请读取同一文件重新核对" } }
                    check(current(original) && original.opened === file) { "原文件采纳窗口已变化，没有准备替代文件审阅" }
                    val entry = Adoption(UUID.randomUUID().toString(), file, revision, JSONObject(reviewed.toString()), SystemClock.elapsedRealtime() + 120_000)
                    original.adoption = entry
                    val shown = JSONObject(reviewed.toString())
                    if (reviewed.opt("same") == true) runCatching {
                        ProjectAdoptedFormalFile.matching(reviewed, original.project, original.checkout, file.draft.path, workspace())
                    }.onSuccess { pin -> file.formal = pin; pin?.let { shown.put("formalFile", it.position()) } }
                    shown.put("filesHandle", original.handle).put("fileHandle", file.handle).put("importHandle", entry.handle)
                        .put("expiresAt", System.currentTimeMillis() + 120_000).put("projectVersionWritten", false).put("executionAuthorized", false)
                }
            }
            "projects.development.files.import" -> {
                fields(args, "viewHandle", "filesHandle", "importHandle")
                val entry = original.adoption
                check(entry != null && entry.handle == text(args, "importHandle") && entry.expires > SystemClock.elapsedRealtime() && original.opened === entry.file) { "原采纳审阅已过期或换页，请重新比较" }
                check(entry.review.optBoolean("importable") && !entry.review.optBoolean("truncated")) { "这份差异当前不能采纳，原版本未改变" }
                original.adoption = null // Consume before dispatch, including an unknown acknowledgement.
                work(original, done) {
                    try {
                        val result = controller.projectDevelopmentForWeb().importFileForOwner(original.project, original.checkout, entry.file.draft.path, entry.review) {
                            current(original) && original.opened === entry.file && entry.expires > SystemClock.elapsedRealtime() && synchronized(store) { requireDraft(entry.file); entry.file.draft.revision == entry.revision && !entry.file.draft.dirty() }
                        }
                        entry.file.formal = ProjectAdoptedFormalFile.receipt(result, original.project, original.checkout, entry.file.draft.path, ordinaryEditor = original.sourcePath == null)
                        info(original, entry.file, true).also { reply ->
                            reply.remove("projectVersionWritten")
                            if (result.opt("projectVersionCommitted") is Boolean) reply.put("projectVersionWritten", result.getBoolean("projectVersionCommitted"))
                        }.put("importStatus", "observed").put("importReceipt", result)
                    } catch (failure: Exception) {
                        info(original, entry.file, true).also { it.remove("projectVersionWritten") }.put("importStatus", "unknown").put("importError", failure.message)
                            .put("importMessage", "没有取得完整采纳回执。原审阅已消费；请重新比较这个文件与正式版本，不自动重发或改写其他文件。")
                    }
                }
            }
            "projects.development.files.list" -> {
                check(original.sourcePath == null) { "这个入口只打开原任务选中的那一个文件" }
                if(args.has("gitRead")){
                    fields(args,"viewHandle","filesHandle","gitRead")
                    val request=JSONObject(args.getJSONObject("gitRead").toString())
                    work(original,done) {
                        original.git.read(controller,request){ current(original) }.put("filesHandle",original.handle)
                            .put("projectId",original.project).put("checkoutId",original.checkout)
                    }
                }else{
                    fields(args, "viewHandle", "filesHandle", "afterPath", "sourceDigest")
                    val after = text(args, "afterPath", 240, true); val expected = text(args, "sourceDigest", 64, true)
                    check(!busy) { "请等待原文件操作回执" }
                    original.priorSavedFile = null
                    observe(original, done, { value: JSONObject -> presentListing(original, value) }) { listing(original, after, expected) }
                }
            }
            "projects.development.files.find", "projects.development.files.findNext" -> {
                check(original.sourcePath == null) { "原任务单文件入口不能搜索整个副本" }
                if(action.endsWith(".find")){
                    fields(args,"viewHandle","filesHandle","query","filter")
                    val query=text(args,"query",128,true);val filter=text(args,"filter",16)
                    work(original,done){
                        val source=controller.projectDevelopmentForWeb().filesForOwner(original.project,original.checkout)
                        val found=CheckoutPathMatches.capture(original.project,original.checkout,source,query,filter)
                        check(current(original));original.matches=found;original.matchesUntil=SystemClock.elapsedRealtime()+600000
                        found.page(found.handle,0).put("filesHandle",original.handle)
                    }
                }else{
                    fields(args,"viewHandle","filesHandle","searchHandle","start")
                    val search=text(args,"searchHandle");val start=number(args,"start");require(start in 0L..2000L)
                    work(original,done){
                        controller.projectDevelopmentForWeb().requireOrdinaryFileOwner(original.project,original.checkout)
                        check(SystemClock.elapsedRealtime()<original.matchesUntil){"原查询已结束，请重新查找"}
                        val found=original.matches?:error("原查询已结束，请重新查找")
                        found.page(search,start.toInt()).put("filesHandle",original.handle)
                    }
                }
            }
            "projects.development.files.findText", "projects.development.files.findTextNext" -> {
                check(original.sourcePath==null){"原任务单文件入口不能搜索整个副本"}
                val previous=if(action.endsWith(".findTextNext")){
                    fields(args,"viewHandle","filesHandle","textHandle");textPage(original,args)
                }else{fields(args,"viewHandle","filesHandle","query");null}
                val literal=previous?.literal?:text(args,"query",128)
                val until=SystemClock.elapsedRealtime()+60000
                observe(original,done,{ found: CheckoutTextMatches ->
                    original.textPage=found;original.textUntil=SystemClock.elapsedRealtime()+600000
                    found.page().put("filesHandle",original.handle)
                }){
                    controller.projectDevelopmentForWeb().findTextForOwner(original.project,original.checkout,literal,previous) {
                        current(original)&&SystemClock.elapsedRealtime()<until&&(previous==null||original.textPage===previous&&SystemClock.elapsedRealtime()<original.textUntil)
                    }
                }
            }
            "projects.development.files.read", "projects.development.files.readFound", "projects.development.files.readTextHit" -> {
                val located=if(action.endsWith(".readTextHit")){
                    fields(args,"viewHandle","filesHandle","textHandle","hit")
                    val index=number(args,"hit");require(index in 0L until CheckoutTextMatches.PAGE.toLong())
                    val source=textPage(original,args);source to source.selected(index.toInt())
                }else null
                val hit=if(action.endsWith(".readFound")){
                    check(original.sourcePath==null){"原任务只读所选文件"};fields(args,"viewHandle","filesHandle","searchHandle","hit")
                    val index=number(args,"hit");require(index in 0L..1999L);check(SystemClock.elapsedRealtime()<original.matchesUntil){"原查询已过期，请重新查找"}
                    (original.matches?:error("原查询已结束，请重新查找")).selected(text(args,"searchHandle"),index.toInt())
                }else{if(located==null)fields(args, "viewHandle", "filesHandle", "path", "gitStatusOperation", "savedEditId", "priorSavedFileId");null}
                val gitStatus=if(args.has("gitStatusOperation")){
                    check(action.endsWith(".read")&&original.sourcePath==null){"只能在原副本目录选择 Git 文件"}
                    text(args,"gitStatusOperation")
                }else null
                val path=located?.second?.getString("path")?:hit?.path()?:text(args,"path",240)
                val saved=if(args.has("savedEditId")){
                    check(action.endsWith(".read")&&original.sourcePath==null&&gitStatus==null) { "只在原私人副本文件窗口核对本次保存" }
                    val receipt=original.savedEdit
                    check(receipt!=null&&receipt.id==text(args,"savedEditId")&&receipt.path==path) { "本次保存回执已变化，请在原列表重新选择" }
                    receipt
                }else null
                val listed=if(readPriorSave){
                    val position=original.priorSavedFile
                    check(!busy && original.sourcePath==null && original.opened==null && gitStatus==null && saved==null &&
                        position!=null && position.id==text(args,"priorSavedFileId") && position.path==path) {
                        "先前确认的保存位置已变化，请在原副本清单重新选择。"
                    }
                    original.priorSavedFile=null
                    position
                }else null
                if (original.sourcePath != null) {
                    check(path == original.sourcePath && !busy) { "只能重新核对已选择的原文件" }
                    original.opened?.let { store.release(it.draft) }; original.opened = null
                }
                work(original, done) {
                    val gitSha=gitStatus?.let { original.git.shaForEditor(it,path) }
                    val actual = controller.projectDevelopmentForWeb().fileForOwner(original.project, original.checkout, path)
                    if(saved!=null)check(original.savedEdit===saved&&actual.opt("deleted")==false&&actual.opt("expectedHash")==saved.savedHash&&
                        actual.opt("projectId")==original.project&&actual.opt("checkoutId")==original.checkout&&actual.opt("path")==saved.path) {
                        "这个文件已不再是本次保存的字节。未打开其他版本，可从副本清单核对当前文件。"
                    }
                    if(gitSha!=null)check(!actual.getBoolean("deleted")&&actual.getString("expectedHash")==gitSha){
                        "上次 Git 读取后文件内容已变化，请重读同一副本的 Git 状态。"
                    }
                    val anchor=located?.let{(source,selected)->
                        check(textPage(original,args)===source){"原查询已经改变"};source.anchor(selected,actual)
                    }
                    if(hit!=null){
                        check(SystemClock.elapsedRealtime()<original.matchesUntil&&actual.opt("projectId")==original.project&&actual.opt("checkoutId")==original.checkout){"原查询或副本已变化，请重新查找"}
                        CheckoutPathMatches.requireStillExact(hit,actual)
                    }
                    check(original.sourcePath == null || !actual.getBoolean("deleted")) { "原副本已没有这个文件。固定包保留原文，不会重建或替换文件" }
                    original.initialPhoneSha?.let { expected ->
                        check(!actual.getBoolean("deleted") && actual.getString("expectedHash") == expected) {
                            "刚核对的本机版本已经改变，未打开或保存任何替代文件。请返回重新比较。"
                        }
                    }
                    val value = synchronized(store) {
                        // Do not touch even the existing draft/cache for a deleted or replacement version.
                        if(listed!=null)check(current(original) && original.opened==null &&
                            actual.opt("deleted")==false && actual.opt("expectedHash")==listed.sha &&
                            actual.opt("projectId")==original.project && actual.opt("checkoutId")==original.checkout &&
                            actual.opt("path")==listed.path) {
                            "无法核对先前确认保存的版本。未打开替代文件，可从同一副本清单核对当前文件。"
                        }
                        val prior = store.open(original.project, original.checkout, path, actual.optBoolean("deleted"))
                        val observed = if (original.sourcePath == null && current(original))
                            prior.observeRecoveredOrdinaryFile(actual) else prior.observe(actual)
                        if(listed!=null)check(current(original) && original.opened==null) { "原副本清单已关闭，未替换草稿。" }
                        observed.also { store.retain(it) }
                    }
                    if (!current(original)) { store.release(value); error("原文件读取窗口已变化，草稿已保留") }
                    original.opened?.let { store.release(it.draft) }
                    val file = Opened(UUID.randomUUID().toString(), value); original.opened = file
                    val shown = info(original, file, true)
                    if(anchor!=null)shown.put("sourceHit",anchor)
                    if(hit!=null)shown.put("matchedFileSha256",hit.sha())
                    if(saved!=null)shown.put("savedEditId",saved.id)
                    if(listed!=null)shown.put("priorSavedFileId",listed.id)
                    original.initialPhoneSha = null // First real read succeeded. From here existing save CAS and adoption review govern this same path.
                    shown
                }
            }
            "projects.development.files.edit" -> {
                fields(args, "viewHandle", "filesHandle", "fileHandle", "editSequence", "text")
                check(!busy) { "原文件正在核对或保存，当前编辑未替换" }; val file = opened(original, args)
                val sequence = number(args, "editSequence")
                require(sequence > 0 && sequence <= 1_000_000) { "文件编辑序号无效" }
                val value = text(args, "text", 262144, true)
                synchronized(store) {
                    requireDraft(file)
                    if (sequence > file.sequence) { val next = file.draft.edit(value); store.retain(next); file.draft = next; file.sequence = sequence }
                }
                done(info(original, file, false), null)
            }
            "projects.development.files.cache" -> {
                fields(args, "viewHandle", "filesHandle", "fileHandle", "retry"); val file = opened(original, args)
                check(!busy) { "请等待原文件回执" }
                require(args.opt("retry") is Boolean)
                synchronized(store) { requireDraft(file); if (args.getBoolean("retry")) store.retry(file.draft) }
                done(info(original, file, false), null)
            }
            "projects.development.files.resolve" -> {
                fields(args, "viewHandle", "filesHandle", "fileHandle", "revision", "choice")
                check(!busy) { "请等待原文件回执" }; val file = opened(original, args)
                val choice = text(args, "choice"); require(choice in setOf("keep_mine", "use_latest"))
                synchronized(store) {
                    requireDraft(file); check(number(args, "revision") == file.draft.revision) { "草稿在比较后已变化，没有替换" }
                    val next = if (choice == "keep_mine") file.draft.keepMineAgainstLatest() else file.draft.useLatest()
                    store.retain(next); file.draft = next; file.saveUnknown = false
                }
                done(info(original, file, true), null)
            }
            "projects.development.files.save" -> {
                fields(args, "viewHandle", "filesHandle", "fileHandle", "revision", "editSequence", "createEmpty")
                val createEmpty=args.has("createEmpty")
                require(!createEmpty||args.opt("createEmpty")==true) { "需要明确选择创建空文件" }
                val file = opened(original, args)
                val submitted = synchronized(store) {
                    requireDraft(file); check(!file.saveUnknown && number(args, "revision") == file.draft.revision && number(args, "editSequence") == file.sequence) { "编辑或保存状态已变化，请读取原文件核对" }
                    if(createEmpty){
                        val draft=file.draft;val latest=draft.latestReview()
                        // A clean empty buffer is not evidence of absence.
                        // Only the original ordinary read may enable this intent.
                        check(original.sourcePath==null && draft.baseHash=="absent" && draft.baseContent.isEmpty() &&
                            draft.text.isEmpty() && !draft.dirty() && !draft.conflict() && latest!=null &&
                            latest.opt("projectId")==original.project && latest.opt("checkoutId")==original.checkout &&
                            latest.opt("path")==draft.path && latest.opt("deleted")==true && latest.opt("sha256")=="absent" &&
                            latest.opt("expectedHash")=="absent" && latest.optLong("bytes",-1)==0L && latest.opt("content")=="") {
                            "这份副本没有核对到原路径确实不存在，未创建空文件。"
                        }
                    }else check(file.draft.dirty()) { "没有待保存的修改" }
                    file.draft.saveIntent()
                }
                work(original, done) {
                    try {
                        val host = controller.projectDevelopmentForWeb()
                        fun stillFile() = current(original) && original.opened === file
                        val result = if (original.sourcePath == null) host.saveOrdinaryEditorFileForOwner(submitted,createEmpty) { stillFile() }
                            else host.saveFileForOwner(submitted) { stillFile() }
                        synchronized(store) {
                            val latest = store.open(original.project, original.checkout, submitted.path, false)
                            val observed = JSONObject(result.toString()).put("projectId", original.project).put("expectedHash", result.getString("sha256")).put("deleted",false)
                            val saved = latest.saved(submitted, result).observe(observed); store.retain(saved); file.draft = saved
                        }
                        info(original, file, true).put("saveStatus", "observed").put("message", if(createEmpty)
                            "已创建并回读这份私人副本的空文件；没有导回正式项目，也没有运行代码"
                            else "已按原SHA保存到这个私人副本并回读；没有导回正式项目，也没有运行代码")
                            .also { reply ->
                                if(original.sourcePath==null){
                                    val receipt=SavedEdit(UUID.randomUUID().toString(),submitted.path,submitted.expectedHash,result.getString("sha256"))
                                    original.savedEdit=receipt
                                    reply.put("savedEdit",receipt.view())
                                }
                            }
                    } catch (failure: Exception) {
                        file.saveUnknown = true
                        info(original, file, true).put("saveStatus", "unknown").put("message", "原保存未取得完整回执，草稿仍保留。请读取同一文件核对，不自动再次保存。").put("saveError", failure.message)
                    }
                }
            }
        }
    }
}
