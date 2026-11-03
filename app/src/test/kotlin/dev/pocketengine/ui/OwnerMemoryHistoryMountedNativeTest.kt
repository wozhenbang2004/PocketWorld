package dev.pocketengine.ui

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Recomposer
import androidx.compose.ui.platform.ComposeView
import kotlinx.coroutines.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.text.AnnotatedString
import dev.pocketengine.features.memory.SourceRef
import dev.pocketengine.features.local.project.ProjectSources
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowChoreographer
import org.robolectric.shadows.ShadowDialog
import org.robolectric.annotation.*

/** Mounted existing cognition controls -> actual Controller/native owner/Book/SQLite.
 * Device input, provider inference, screenshots and aesthetic acceptance are not claimed. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = Application::class, qualifiers = "w400dp-h1600dp-xxhdpi")
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class OwnerMemoryHistoryMountedNativeTest {
    private var mountedRoot: View? = null
    private fun compose(view: View): View? {
        if (view.javaClass.name == "androidx.compose.ui.platform.AndroidComposeView") return view
        if (view is ViewGroup) repeat(view.childCount) { compose(view.getChildAt(it))?.let { found -> return found } }
        return null
    }
    private fun nodes(root: View? = mountedRoot): List<SemanticsNode> {
        val result = mutableListOf<SemanticsNode>()
        fun visit(node: SemanticsNode) { result += node; node.children.forEach(::visit) }
        root?.let { assertTrue("Read only an actually mounted window root", it.isAttachedToWindow); compose(it)?.let { view -> visit((view.javaClass.getMethod("getSemanticsOwner").invoke(view) as SemanticsOwner).rootSemanticsNode) } }
        return result
    }
    private fun dialogNodes(): List<SemanticsNode> = nodes(ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }?.window?.decorView)
    private fun text(node: SemanticsNode) = node.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }.orEmpty()
    private fun scope() = JSONObject().put("ownerId", "owner").put("spaceId", "private")
    private fun fact(text: String, city: String, at: Long) = JSONObject().put("text", text).put("layer", "fact").put("assertion", JSONObject().put("version", 1).put("kind", "told").put("subjectId", "owner").put("subjectType", "owner").put("subjectLabel", "主人").put("predicate", "居住城市").put("object", city).put("eventTime", at).put("validFrom", at).put("evidenceSpans", JSONArray()))
    private inner class Fixture : AutoCloseable {
        val native = ProjectMemoryReviewUiNativeTest.Fixture()
        val calls = mutableListOf<String>()
        init { ShadowChoreographer.setPaused(true); ShadowChoreographer.setFrameDelay(Duration.ofMillis(16)) }
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        // Own the real Compose scheduler per fixture: Robolectric replaces the main Looper
        // between methods, while Android's default dispatcher/Recomposer may retain the old one.
        private val frames = BroadcastFrameClock()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined + frames)
        private val recomposer = Recomposer(scope.coroutineContext)
        private val recomposition = scope.launch(start = CoroutineStart.UNDISPATCHED) { recomposer.runRecomposeAndApplyChanges() }
        private val view = ComposeView(activity.get()).also { it.setParentCompositionContext(recomposer) }
        private var frameNanos = 0L
        fun save(value: JSONObject) { native.memory.handle("save", JSONObject().put("scope", scope()).put("record", value)) }
        fun mount() {
            native.enter()
            mountedRoot = activity.get().window.decorView
            view.setContent { MaterialTheme { Column {
                val state = native.controller.state
                val snapshot = state.ownerSnapshot("memoryReview")
                if (snapshot.optString("namespaceStatus") == "private") MemoryCognitionPanel(state.ownerResponses("memoryReview"), state.ownerPending("memoryReview"), snapshot.optString("cognitionRevision")) { command, payload -> calls += command; native.controller.requestOwnerDomain("memoryReview", command, payload) }
                else if (snapshot.optString("namespaceStatus") == "project") MemoryLearningReviewPage(snapshot, state.ownerResponses("memoryReview"), state.ownerPending("memoryReview"), false, state.ownerErrors["memoryReview"],
                    { command, payload -> calls += command; native.controller.requestOwnerDomain("memoryReview", command, payload) },
                    { reference -> calls += "openSource"; native.controller.openOwnerSource(reference) }, {}, native.controller::dismissOwnerSource)
                else Text("范围尚未核对：不读取旧事")
            } } }
            activity.get().setContentView(view)
            frame(); assertEquals("snapshot=${native.snapshot()}; replies=${native.controller.state.ownerResponses("memoryReview")}; errors=${native.controller.state.ownerErrors}; pending=${native.controller.state.ownerPending}", "private", native.snapshot().optString("namespaceStatus")); click("查看主体认知、历史与冲突…")
        }
        fun frame() {
            native.settle()
            fun layout(value: View) {
                if (value is RootForTest) value.measureAndLayoutForTest()
                if (value is ViewGroup) repeat(value.childCount) { layout(value.getChildAt(it)) }
            }
            repeat(6) {
                Snapshot.sendApplyNotifications()
                frameNanos += 32_000_000L
                frames.sendFrame(frameNanos)
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))
                val roots = mutableListOf(activity.get().window.decorView)
                ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }?.window?.decorView?.let { roots += it }
                roots.forEach { root ->
                    root.measure(View.MeasureSpec.makeMeasureSpec(1200, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(4800, View.MeasureSpec.AT_MOST))
                    root.layout(0, 0, root.measuredWidth, root.measuredHeight)
                    layout(root); root.invalidate()
                }
                Thread.sleep(5)
            }
        }
        fun button(label: String) = nodes().firstOrNull { text(it) == label && it.config.getOrNull(SemanticsActions.OnClick)?.action != null } ?: error("Missing actual cognition action $label; ${nodes().map(::text)}")
        fun click(label: String) { val node = button(label); assertNull("Fresh native action must not be disabled", node.config.getOrNull(SemanticsProperties.Disabled)); assertTrue(node.config[SemanticsActions.OnClick].action!!.invoke()); frame() }
        fun clickContaining(value: String) { val node = nodes().first { text(it).contains(value) && it.config.getOrNull(SemanticsActions.OnClick)?.action != null }; assertNull(node.config.getOrNull(SemanticsProperties.Disabled)); assertTrue(node.config[SemanticsActions.OnClick].action!!.invoke()); frame() }
        fun edit(query: String) { val node = nodes().firstOrNull { it.config.getOrNull(SemanticsActions.SetText)?.action != null } ?: error("Missing actual editable history query; ${nodes().map(::text)}"); assertTrue(node.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString(query))); frame() }
        fun dialogButton(label: String) = dialogNodes().firstOrNull { text(it) == label && it.config.getOrNull(SemanticsActions.OnClick)?.action != null } ?: error("Missing actual dialog action $label; ${dialogNodes().map(::text)}")
        fun editDialog(value: String) { val node = dialogNodes().single { it.config.getOrNull(SemanticsActions.SetText)?.action != null }; assertNull(node.config.getOrNull(SemanticsProperties.Disabled)); assertTrue(node.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString(value))); frame() }
        override fun close() { mountedRoot = null; view.disposeComposition(); recomposer.close(); recomposition.cancel(); scope.cancel(); activity.pause().stop().destroy(); repeat(8) { shadowOf(Looper.getMainLooper()).runOneTask() }; native.close() }
    }
    @Test fun userCanFindAnOldFactThroughTheExistingPanelWhileCurrentRecallStillKeepsTheNewFact() {
        Fixture().use { f ->
            f.save(fact("旧A城经历", "A城", 100)); f.save(fact("当前B城记录", "B城", 200)); f.mount(); f.edit("A城"); f.click("查旧事（只读）")
            assertTrue(nodes().any { text(it).contains("历史版本（不是现在的事实）") && text(it).contains("旧A城经历") }); f.clickContaining("旧A城经历")
            assertTrue(nodes().any { text(it).contains("已存记忆文本（不是原件）") }); assertTrue(nodes().any { text(it).contains("不能把摘要当原件") })
            assertTrue(f.native.response("readHistoryRecord").getBoolean("ok")); assertFalse(f.native.response("readHistoryRecord").getJSONObject("result").getBoolean("originalEvidenceRead"))
            assertEquals(0, f.native.memory.handle("search", JSONObject().put("scope", scope()).put("query", "A城")).getJSONArray("records").length())
        }
    }
    @Test fun editedQueriesAndClosedCognitionPanelsCannotDispatchOldReadOrSearchCallbacks() {
        Fixture().use { f ->
            f.save(JSONObject().put("text", "一段旧事")); f.mount(); f.edit("旧事"); f.click("查旧事（只读）")
            val oldRead = nodes().first { text(it).contains("一段旧事") && it.config.getOrNull(SemanticsActions.OnClick)?.action != null }.config[SemanticsActions.OnClick].action!!
            f.edit("新查询"); val beforeRead = f.calls.count { it == "readHistoryRecord" }; oldRead.invoke(); f.frame(); assertEquals(beforeRead, f.calls.count { it == "readHistoryRecord" })
            val oldSearch = f.button("查旧事（只读）").config[SemanticsActions.OnClick].action!!; f.click("收起主体认知与冲突"); val beforeSearch = f.calls.count { it == "searchHistory" }; oldSearch.invoke(); f.frame(); assertEquals(beforeSearch, f.calls.count { it == "searchHistory" })
            repeat(2) {
                f.click("查看主体认知、历史与冲突…")
                oldSearch.invoke(); oldRead.invoke(); f.frame()
                assertEquals(beforeSearch, f.calls.count { it == "searchHistory" }); assertEquals(beforeRead, f.calls.count { it == "readHistoryRecord" })
                assertFalse(nodes().any { text(it).contains("一段旧事") })
                f.click("收起主体认知与冲突")
            }
            f.click("查看主体认知、历史与冲突…"); f.edit("旧事"); f.click("查旧事（只读）"); assertTrue(nodes().any { text(it).contains("一段旧事") })
        }
    }
    @Test fun ownerCanCorrectAnExactHistoryVersionAndOldMountedButtonsCannotRepeatOrCrossScope() {
        Fixture().use { f ->
            val oldText = "曾经记作旧A城经历"
            val correctedText = "主人纠正为C城的经历"
            f.save(fact(oldText, "A城", 100)); f.save(fact("后来的B城记载", "B城", 200))
            f.mount(); f.edit("A城"); f.click("查旧事（只读）"); f.clickContaining(oldText)
            val before = f.native.response("readHistoryRecord").getJSONObject("result").getJSONObject("record")
            assertEquals("historical", before.getString("temporalStatus"))
            val oldOpen = f.button("纠正这条已存记忆…").config[SemanticsActions.OnClick].action!!
            f.click("纠正这条已存记忆…"); assertTrue(dialogNodes().any { text(it) == "纠正这条记忆？" })
            f.editDialog(correctedText)
            val confirm = f.dialogButton("保存这次纠正")
            assertNull(confirm.config.getOrNull(SemanticsProperties.Disabled))
            val oldConfirm = confirm.config[SemanticsActions.OnClick].action!!
            assertTrue(oldConfirm.invoke()); oldConfirm.invoke() // Same mounted callback before recomposition.
            assertEquals(1, f.calls.count { it == "correctReviewed" })
            f.frame()
            val reply = f.native.response("correctReviewed")
            assertTrue(reply.toString(), reply.getBoolean("ok"))
            val saved = reply.getJSONObject("result").getJSONObject("record")
            assertEquals(before.getString("id"), saved.getString("id"))
            assertEquals(before.getLong("revision") + 1, saved.getLong("revision"))
            assertEquals(correctedText, saved.getString("text")); assertTrue(saved.has("assertionReview"))
            assertEquals("inferred", saved.getJSONObject("assertion").getString("kind"))
            assertFalse(reply.getJSONObject("result").getBoolean("executionAuthorized"))
            assertTrue(dialogNodes().isEmpty()); assertFalse(nodes().any { text(it).contains(oldText) })
            oldOpen.invoke(); oldConfirm.invoke(); f.frame()
            assertEquals(1, f.calls.count { it == "correctReviewed" }); assertTrue(dialogNodes().isEmpty())

            f.edit("C城"); f.click("查旧事（只读）"); f.clickContaining(correctedText)
            assertEquals(saved.getLong("revision"), f.native.response("readHistoryRecord").getJSONObject("result").getJSONObject("record").getLong("revision"))
            f.click("纠正这条已存记忆…"); f.editDialog("范围改变后不应保存的D城文字")
            val abandonedConfirm = f.dialogButton("保存这次纠正").config[SemanticsActions.OnClick].action!!
            val project = f.native.create("纠正期间切换到项目")
            f.native.choose(project); f.frame()
            assertTrue(dialogNodes().isEmpty()); abandonedConfirm.invoke(); oldOpen.invoke(); f.frame()
            assertEquals(1, f.calls.count { it == "correctReviewed" })
            assertFalse(nodes().any { text(it).contains(correctedText) })
            f.native.choose(null); f.frame(); abandonedConfirm.invoke(); oldConfirm.invoke(); f.frame()
            assertEquals(1, f.calls.count { it == "correctReviewed" }); assertTrue(dialogNodes().isEmpty())
            val actual = f.native.memory.handle("get", JSONObject().put("scope", scope()).put("id", saved.getString("id"))).getJSONObject("record")
            assertEquals(saved.getLong("revision"), actual.getLong("revision")); assertEquals(correctedText, actual.getString("text"))
        }
    }
    @Test fun exactOriginalSpanUsesTheRealNativeReaderAndChangingScopeDisposesItsOldCallback() {
        Fixture().use { f ->
            val original = "原项目笔记：那年住在A城。"; val project = f.native.workspace.handle("create", JSONObject().put("name", "old-source-ui")).getString("id")
            f.native.workspace.handle("write", JSONObject().put("projectId", project).put("path", "notes.txt").put("content", original).put("expectedVersion", 0)); val file = f.native.workspace.fileSnapshotForHost(project, "notes.txt", 0)
            f.native.io.submit { f.native.workspace.refreshFileHeadForHost(project, "notes.txt") }.get(10, TimeUnit.SECONDS)
            val ref = SourceRef.workspaceFile(project, "notes.txt", file.version, file.revisionId, file.sha256, 0, original.length, "owner", "private")
            val value = fact("A城旧事的存储概述", "A城", 100).put("sourceRefs", JSONArray().put(ref.toJson()))
            value.getJSONObject("assertion").getJSONArray("evidenceSpans").put(JSONObject().put("sourceRef", ref.toJson()).put("offset", 0).put("chars", original.length).put("sha256", ProjectSources.sha256(original.toByteArray(StandardCharsets.UTF_8))))
            f.save(value); f.mount(); f.edit("A城"); f.click("查旧事（只读）"); f.clickContaining("A城旧事的存储概述"); val oldSpan = f.button("回读这条旧事的确切原句与hash").config[SemanticsActions.OnClick].action!!
            f.click("回读这条旧事的确切原句与hash"); assertTrue(nodes().any { text(it) == original }); val read = f.native.response("readAssertionEvidence").getJSONObject("result"); assertTrue(read.getBoolean("verifiedOriginalSpan")); assertFalse(read.getBoolean("assertionTruthVerified"))
            val target = f.native.create("切换后的项目"); f.native.choose(target); f.frame(); val before = f.calls.count { it == "readAssertionEvidence" }; oldSpan.invoke(); f.frame(); assertEquals(before, f.calls.count { it == "readAssertionEvidence" }); assertFalse(nodes().any { text(it) == original })
            f.native.choose(null); f.frame(); f.click("查看主体认知、历史与冲突…")
            oldSpan.invoke(); f.frame(); assertEquals(before, f.calls.count { it == "readAssertionEvidence" }); assertFalse(nodes().any { text(it) == original })
            f.edit("A城"); f.click("查旧事（只读）"); f.clickContaining("A城旧事的存储概述"); assertNotNull(f.button("回读这条旧事的确切原句与hash"))
        }
    }
    @Test fun actualProjectReviewCanQueryItsOwnHistoryAndOpenOriginalScopeWithoutPrivateFallback() {
        Fixture().use { f ->
            f.save(JSONObject().put("text", "项目原文：主人全局私密记录不能混进项目")); val project = f.native.create("历史入口项目"); val other = f.native.create("其他项目")
            f.mount(); f.native.choose(project); val candidate = f.native.propose(f.native.file()).getJSONObject("candidate")
            val accepted = f.native.result("acceptCandidate", JSONObject().put("id", candidate.getString("id")).put("revision", candidate.getLong("revision"))).getJSONObject("record")
            f.frame(); f.edit("项目原文"); f.click("查旧事（只读）"); assertFalse(nodes().any { text(it).contains("主人全局私密记录") }); f.clickContaining("保留约束与选择理由")
            assertEquals(project.getString("spaceId"), f.native.response("readHistoryRecord").getJSONObject("result").getJSONObject("record").getString("spaceId"))
            val oldSource = f.button("回读原选来源范围（非原话认证）").config[SemanticsActions.OnClick].action!!
            f.click("回读原选来源范围（非原话认证）"); val original = f.native.controller.state.ownerSource!!
            assertEquals(accepted.getString("text"), original.getString("text")); assertEquals(accepted.getJSONArray("sourceRefs").getJSONObject(0).toString(), original.getJSONObject("sourceRef").toString()); assertFalse(original.getBoolean("executionAuthorized"))
            f.native.choose(other); f.frame(); val sourceCalls = f.calls.count { it == "openSource" }; oldSource.invoke(); f.frame(); assertEquals(sourceCalls, f.calls.count { it == "openSource" }); assertNull(f.native.controller.state.ownerSource)
            f.edit("项目原文"); val oldSearch = f.button("查旧事（只读）").config[SemanticsActions.OnClick].action!!
            val settledAt = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while ("snapshot" in f.native.controller.state.ownerPending("memoryReview")) { check(System.nanoTime() < settledAt) { "Original project page snapshot did not settle" }; f.frame() }
            f.native.workspace.close(); val requestId = f.native.controller.requestOwnerDomain("memoryReview", "snapshot", JSONObject())
            val replyAt = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (f.native.response("snapshot")?.optString("requestId") != requestId) { check(System.nanoTime() < replyAt) { "Missing actual closed-scope snapshot receipt" }; f.frame() }
            assertFalse(f.native.response("snapshot").getBoolean("ok")); f.frame(); val searchCalls = f.calls.count { it == "searchHistory" }; oldSearch.invoke(); f.frame(); assertEquals(searchCalls, f.calls.count { it == "searchHistory" }); assertTrue(nodes().any { text(it).contains("范围尚未核对") }); assertFalse(nodes().any { text(it).contains("主人全局私密记录") })
        }
    }

}
