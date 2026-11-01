package dev.pocketengine.ui

import android.content.Context
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import dev.pocketengine.features.local.project.ProjectFileDraftStore
import java.io.File
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/** Project editor navigation only. Never serializes a grant, approval checkbox or live WebView. */
internal class ProjectWorkbenchSession(context: Context) : ViewModel() {
    private val preferences = context.applicationContext.getSharedPreferences("project-workbench-position-v1", Context.MODE_PRIVATE)
    private val change = mutableIntStateOf(0)
    val fileDrafts = sharedFileDrafts(context.applicationContext)
    private val agents = mutableMapOf<String, ProjectAgentDraftSession>()
    private val fileTrees = mutableMapOf<String, ProjectTreeUiState>()
    private val pages = mutableMapOf<Pair<String, Int>, LazyListState>()
    private val codeReturns = mutableMapOf<String, java.util.ArrayDeque<Triple<String,Int,Int>>>()
    private var navigationRevision = 0L
    fun navigationRevision(): Long = navigationRevision

    fun checkout(projectId: String): String? { change.intValue; return preferences.getString("project.$projectId.checkout", null) }
    fun selectCheckout(projectId: String, checkoutId: String, replace: Boolean = false) {
        if (!replace && checkout(projectId) != null) return
        if (checkout(projectId) != checkoutId) navigationRevision++
        preferences.edit().putString("project.$projectId.checkout", checkoutId).apply(); change.intValue++
    }
    fun view(projectId: String): Int { change.intValue; return preferences.getInt("project.$projectId.view", 0).coerceIn(0, 3) }
    fun selectView(projectId: String, view: Int) { navigationRevision++; preferences.edit().putInt("project.$projectId.view", view.coerceIn(0, 3)).apply(); change.intValue++ }
    fun page(projectId: String, view: Int): LazyListState = pages.getOrPut(projectId to view.coerceIn(0, 3)) { LazyListState() }
    fun fileTree(checkoutId: String): ProjectTreeUiState = fileTrees.getOrPut(checkoutId) { ProjectTreeUiState() }
    fun editorPosition(checkoutId: String, path: String): Pair<Int, Int> {
        val key = "editor.$checkoutId.$path"
        return preferences.getInt("$key.start", 0).coerceAtLeast(0) to preferences.getInt("$key.end", 0).coerceAtLeast(0)
    }
    fun rememberEditorPosition(checkoutId: String, path: String, start: Int, end: Int) {
        val key = "editor.$checkoutId.$path"
        preferences.edit().putInt("$key.start", start.coerceAtLeast(0)).putInt("$key.end", end.coerceAtLeast(0)).apply()
    }
    fun editorViewport(checkoutId: String, path: String): ProjectEditorViewport? {
        val key = "editor.$checkoutId.$path.viewport"
        val hash = preferences.getString("$key.hash", null)?.takeIf { it.matches(Regex("[a-f0-9]{64}")) } ?: return null
        return ProjectEditorViewport(hash, preferences.getInt("$key.anchor", 0).coerceIn(0, 262144),
            preferences.getInt("$key.inLine", 0).coerceIn(0, 4096), preferences.getInt("$key.horizontal", 0).coerceIn(0, 1000000))
    }
    fun rememberEditorViewport(checkoutId: String, path: String, position: ProjectEditorViewport) {
        val key = "editor.$checkoutId.$path.viewport"
        preferences.edit().putString("$key.hash", position.sourceSha256).putInt("$key.anchor", position.firstVisibleOffset.coerceIn(0, 262144))
            .putInt("$key.inLine", position.inLinePixels.coerceIn(0, 4096)).putInt("$key.horizontal", position.horizontalPixels.coerceIn(0, 1000000)).apply()
    }
    fun editorFontSp(): Int { change.intValue; return preferences.getInt("editor.fontSp", 14).coerceIn(12, 22) }
    fun editorFontSp(value: Int) { preferences.edit().putInt("editor.fontSp", value.coerceIn(12, 22)).apply(); change.intValue++ }
    fun opened(checkoutId: String): Pair<String, Boolean>? {
        change.intValue
        val path = preferences.getString("checkout.$checkoutId.path", null) ?: return null
        return path to preferences.getBoolean("checkout.$checkoutId.new", false)
    }
    fun navigateCode(checkout:String,from:String,fromStart:Int,fromEnd:Int,target:String,start:Int,end:Int) {
        val stack=codeReturns.getOrPut(checkout){java.util.ArrayDeque()};if(stack.size>=20)stack.removeFirst();stack.addLast(Triple(from,fromStart,fromEnd))
        rememberEditorPosition(checkout,from,fromStart,fromEnd);rememberEditorPosition(checkout,target,start,end);open(checkout,target,false)
    }
    fun hasCodeReturn(checkout:String):Boolean { change.intValue;return codeReturns[checkout]?.isNotEmpty()==true }
    fun returnFromCode(checkout:String) { val original=codeReturns[checkout]?.pollLast()?:return;rememberEditorPosition(checkout,original.first,original.second,original.third);open(checkout,original.first,false) }
    fun lastFile(checkoutId: String): String? { change.intValue; return preferences.getString("checkout.$checkoutId.lastPath", null) }
    fun open(checkoutId: String, path: String, fresh: Boolean) {
        preferences.edit().putString("checkout.$checkoutId.lastPath", path).putString("checkout.$checkoutId.path", path).putBoolean("checkout.$checkoutId.new", fresh).apply(); change.intValue++
    }
    fun closeFile(checkoutId: String) {
        preferences.edit().remove("checkout.$checkoutId.path").remove("checkout.$checkoutId.new").apply(); change.intValue++
    }
    fun selectedTask(checkoutId: String): String? { change.intValue; return preferences.getString("checkout.$checkoutId.task", null) }
    fun selectTask(checkoutId: String, taskId: String?) { if (selectedTask(checkoutId) == taskId) return; navigationRevision++; preferences.edit().putString("checkout.$checkoutId.task", taskId).apply(); change.intValue++ }
    /** Only the exact original query locator, never file text, plan, grant or automatic retry. */
    fun pendingLiveFileSave(checkoutId: String, path: String): JSONObject? {
        val body = preferences.getString("live-save.$checkoutId.$path", null) ?: return null
        return JSONObject(body).also { require(it.getString("checkoutId") == checkoutId && it.getString("path") == path && it.toString().length <= 2048) }
    }
    /** IO caller commits before entering any source effect. */
    fun persistLiveFileSave(checkoutId: String, path: String, locator: JSONObject?) {
        val edit = preferences.edit()
        if (locator == null) edit.remove("live-save.$checkoutId.$path") else {
            require(locator.getString("checkoutId") == checkoutId && locator.getString("path") == path)
            val safe = JSONObject()
            listOf("taskId", "runId", "operationId", "coordinatorEpoch", "checkoutId", "path").forEach { safe.put(it, locator.get(it)) }
            require(safe.toString().length <= 2048)
            edit.putString("live-save.$checkoutId.$path", safe.toString())
        }
        check(edit.commit()) { "原保存定位尚未可靠保存，未派发新保存" }
    }
    fun agent(checkoutId: String): ProjectAgentDraftSession = agents.getOrPut(checkoutId) {
        ProjectAgentDraftSession().also { session ->
            try { preferences.getString("checkout.$checkoutId.liveInput", null)?.let { session.liveInput.value = dev.pocketengine.features.local.project.ProjectLiveInput.verified(JSONObject(it)) } }
            catch (_: Exception) { session.liveInputError.value = "原项目补充登记无法核对，未自动发送；请回原任务检查实际消息" }
        }
    }
    /** Called on the controller's existing IO lane, before any send. Never placed in Bundle. */
    fun persistLiveInput(checkoutId: String, record: JSONObject?) {
        val edit = preferences.edit()
        if (record == null) edit.remove("checkout.$checkoutId.liveInput") else {
            val checked = dev.pocketengine.features.local.project.ProjectLiveInput.verified(record)
            require(checked.getString("checkoutId") == checkoutId)
            edit.putString("checkout.$checkoutId.liveInput", checked.toString())
        }
        check(edit.commit()) { "原项目补充尚未可靠保存，未发送" }
    }
    /** A new Activity may observe the original admission; it never automatically resubmits it. */
    fun attachOwnerUi() {
        agents.values.forEach { session ->
            if (session.originalSubmission.value != null) {
                session.unconfirmedSubmission.value = JSONObject().put("clientMessageId", session.client.value).put("status", "unconfirmed")
                session.request.value = null; session.submissionQuery.value = null
            }
            session.continuation.value = null
            session.liveInputRequest.value = null
        }
    }
    companion object {
        // SEND intents can create another Activity. Both must see one original draft revision,
        // not race two independent caches. Only file IO is shared; no task/grant/runtime owner is.
        private val fileCaches = mutableMapOf<String, ProjectFileDraftStore>()
        @Synchronized private fun sharedFileDrafts(context: Context): ProjectFileDraftStore {
            val root = File(context.filesDir, "project-file-drafts-v1").canonicalFile
            return fileCaches.getOrPut(root.path) {
                val writer = ThreadPoolExecutor(0, 1, 15, TimeUnit.SECONDS, ArrayBlockingQueue(64),
                    { work -> Thread(work, "project-draft-cache").also { it.isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())
                ProjectFileDraftStore(root, writer)
            }
        }
    }
}

/** Composition-independent project goal/feedback drafts, not the chat composer or an admission ledger. */
internal class ProjectAgentDraftSession {
    val goal = mutableStateOf("")
    val client = mutableStateOf(UUID.randomUUID().toString())
    val request = mutableStateOf<String?>(null)
    val originalSubmission = mutableStateOf<ProjectGoalSubmission?>(null)
    val unconfirmedSubmission = mutableStateOf<JSONObject?>(null)
    val submissionQuery = mutableStateOf<String?>(null)
    val runtimeId = mutableStateOf("managed_process_v1")
    val newTask = mutableStateOf(false)
    val feedback = mutableStateMapOf<String, String>()
    val continuation = mutableStateOf<ProjectContinuationRequest?>(null)
    val liveInput = mutableStateOf<JSONObject?>(null)
    val liveInputRequest = mutableStateOf<String?>(null)
    val liveInputSaving = mutableStateOf(false)
    val liveInputError = mutableStateOf<String?>(null)
}

/** Presentation only; no file body, approval, grant or execution identity is held here. */
internal class ProjectTreeUiState {
    val query = mutableStateOf("")
    val filter = mutableIntStateOf(0)
    val expanded = mutableStateOf<Set<String>>(emptySet())
    val list = LazyListState()
}

internal enum class ProjectWorkbenchFacet(val index: Int) { Agent(0), Files(1), Run(2), Preview(3) }

/** Reading position only. A digest protects the anchor from a different draft; this is not a file revision grant. */
internal data class ProjectEditorViewport(val sourceSha256: String, val firstVisibleOffset: Int, val inLinePixels: Int, val horizontalPixels: Int)
