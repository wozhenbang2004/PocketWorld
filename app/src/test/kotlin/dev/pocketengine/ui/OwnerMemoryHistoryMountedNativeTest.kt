package dev.pocketengine.ui

import android.app.Application
import dev.pocketengine.features.local.project.ProjectSources
import dev.pocketengine.features.memory.SourceRef
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*

/** Actual Web adapter -> Controller -> Book/VWS. React callbacks/labels: memory-history.test.ts. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class OwnerMemoryHistoryMountedNativeTest {
    private fun scope() = JSONObject().put("ownerId", "owner").put("spaceId", "private")
    private fun fact(text: String, city: String, at: Long) = JSONObject().put("text", text).put("layer", "fact").put("assertion", JSONObject().put("version", 1).put("kind", "told").put("subjectId", "owner").put("subjectType", "owner").put("subjectLabel", "主人").put("predicate", "居住城市").put("object", city).put("eventTime", at).put("validFrom", at).put("evidenceSpans", JSONArray()))
    private fun save(f: OwnerMemoryWebFixture, record: JSONObject) = f.native.memory.handle("save", JSONObject().put("scope", scope()).put("record", record))
    private fun read(f: OwnerMemoryWebFixture, session: String, query: String) = f.read(f.search(session, query).getJSONArray("matches").getJSONObject(0))
    private fun correct(read: JSONObject, text: String) = JSONObject().put("readHandle", read.getString("readHandle")).put("text", text)

    @Test fun userCanFindAnOldFactThroughWebWhileCurrentRecallStillKeepsTheNewFact() {
        OwnerMemoryWebFixture().use { f ->
            save(f, fact("旧A城经历", "A城", 100)); save(f, fact("当前B城记录", "B城", 200))
            val result = read(f, f.session(), "A城"); val record = result.getJSONObject("record")
            assertEquals("historical", record.getString("temporalStatus")); assertEquals("旧A城经历", record.getString("text"))
            assertFalse(result.getBoolean("originalEvidenceRead")); assertTrue(record.getBoolean("readOnlyHistory")); assertFalse(record.getBoolean("memoryTextIsOriginalEvidence"))
            assertEquals(0, f.native.memory.handle("search", JSONObject().put("scope", scope()).put("query", "A城")).getJSONArray("records").length())
        }
    }
    @Test fun changedQueryClosedPanelAndLateReadsCannotReviveOldSelections() {
        OwnerMemoryWebFixture().use { f ->
            save(f, JSONObject().put("text", "一段旧事")); val session = f.session()
            val old = f.search(session, "旧事").getJSONArray("matches").getJSONObject(0)
            f.search(session, "新查询"); f.rejected("memoryHistory.record", JSONObject().put("recordHandle", old.getString("recordHandle")))
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            f.native.io.execute { entered.countDown(); release.await(5, TimeUnit.SECONDS) }; assertTrue(entered.await(5, TimeUnit.SECONDS))
            val queued = f.start("memoryHistory.search", JSONObject().put("sessionHandle", session).put("query", "旧事").put("cursor", ""))
            f.request("memoryHistory.close", JSONObject().put("handle", session)); release.countDown()
            assertNull(f.finish(queued).value); assertNotNull(queued.error)
            repeat(2) { val reopened = f.session(); f.rejected("memoryHistory.search", JSONObject().put("sessionHandle", session).put("query", "旧事").put("cursor", "")); f.rejected("memoryHistory.record", JSONObject().put("recordHandle", old.getString("recordHandle"))); f.request("memoryHistory.close", JSONObject().put("handle", reopened)) }
            assertEquals(1, f.search(f.session(), "旧事").getJSONArray("matches").length())
        }
    }
    @Test fun correctionConsumesExactReviewOnceAndCannotCrossScopeOrReturnToAnOldWindow() {
        OwnerMemoryWebFixture().use { f ->
            save(f, fact("曾经记作旧A城经历", "A城", 100)); save(f, fact("后来的B城记载", "B城", 200))
            val original = read(f, f.session(), "A城"); val before = original.getJSONObject("record")
            val review = f.request("memoryHistory.review", correct(original, "主人纠正为C城的经历"))
            val confirm = JSONObject().put("reviewHandle", review.getString("reviewHandle"))
            val first = f.start("memoryHistory.confirm", confirm); f.rejected("memoryHistory.confirm", confirm)
            val result = f.finish(first).also { assertNull(it.error) }.value!!; val saved = result.getJSONObject("record")
            assertEquals(before.getString("id"), saved.getString("id")); assertEquals(before.getLong("revision") + 1, saved.getLong("revision"))
            assertEquals("主人纠正为C城的经历", saved.getString("text")); assertTrue(saved.has("assertionReview")); assertEquals("inferred", saved.getJSONObject("assertion").getString("kind")); assertFalse(result.getBoolean("executionAuthorized"))
            f.rejected("memoryHistory.review", correct(original, "旧按钮不得再提交")); f.rejected("memoryHistory.confirm", confirm)
            val reread = read(f, f.session(), "C城"); assertEquals(saved.getLong("revision"), reread.getJSONObject("record").getLong("revision"))
            val stale = f.request("memoryHistory.review", correct(reread, "范围改变后不应保存的D城文字"))
            val payload = JSONObject().put("reviewHandle", stale.getString("reviewHandle")); f.native.choose(f.native.create("纠正期间切换到项目")); f.rejected("memoryHistory.confirm", payload)
            f.native.choose(null); f.rejected("memoryHistory.confirm", payload)
            val persisted = f.native.memory.handle("get", JSONObject().put("scope", scope()).put("id", saved.getString("id"))).getJSONObject("record")
            assertEquals(saved.getString("id"), persisted.getString("id")); assertEquals(saved.getLong("revision"), persisted.getLong("revision")); assertEquals(saved.getString("text"), persisted.getString("text"))
        }
    }
    @Test fun exactOriginalSpanUsesNativeReaderAndChangingScopeRetiresItsOldHandle() {
        OwnerMemoryWebFixture().use { f ->
            val original = "原项目笔记：那年住在A城。"; val project = f.native.workspace.handle("create", JSONObject().put("name", "old-source-web")).getString("id")
            f.native.workspace.handle("write", JSONObject().put("projectId", project).put("path", "notes.txt").put("content", original).put("expectedVersion", 0)); val file = f.native.workspace.fileSnapshotForHost(project, "notes.txt", 0)
            f.native.io.submit { f.native.workspace.refreshFileHeadForHost(project, "notes.txt") }.get(10, TimeUnit.SECONDS)
            val reference = SourceRef.workspaceFile(project, "notes.txt", file.version, file.revisionId, file.sha256, 0, original.length, "owner", "private")
            val value = fact("A城旧事的存储概述", "A城", 100).put("sourceRefs", JSONArray().put(reference.toJson()))
            value.getJSONObject("assertion").getJSONArray("evidenceSpans").put(JSONObject().put("sourceRef", reference.toJson()).put("offset", 0).put("chars", original.length).put("sha256", ProjectSources.sha256(original.toByteArray(StandardCharsets.UTF_8))))
            save(f, value); val read = read(f, f.session(), "A城"); val args = JSONObject().put("readHandle", read.getString("readHandle")).put("index", 0).put("span", true).put("offset", 0)
            val source = f.request("memoryHistory.source", args); assertEquals(original, source.getString("text")); assertTrue(source.getBoolean("verifiedOriginalSpan")); assertFalse(source.getBoolean("assertionTruthVerified"))
            f.native.choose(f.native.create("切换后的项目")); f.rejected("memoryHistory.source", args); f.native.choose(null); f.rejected("memoryHistory.source", args)
            assertNotNull(read(f, f.session(), "A城").getString("readHandle"))
        }
    }
    @Test fun projectHistoryKeepsOriginalScopeAndARevokedProjectNeverFallsBackToPrivateMemory() {
        OwnerMemoryWebFixture().use { f ->
            save(f, JSONObject().put("text", "项目原文：主人全局私密记录不能混进项目")); val project = f.native.create("历史入口项目"); val other = f.native.create("其他项目")
            f.native.choose(project); val candidate = f.native.propose(f.native.file()).getJSONObject("candidate")
            val accepted = f.native.result("acceptCandidate", JSONObject().put("id", candidate.getString("id")).put("revision", candidate.getLong("revision"))).getJSONObject("record")
            val session = f.session(); val page = f.search(session, "项目原文"); assertFalse(page.toString().contains("主人全局私密记录")); val read = f.read(page.getJSONArray("matches").getJSONObject(0))
            assertEquals(project.getString("spaceId"), read.getJSONObject("record").getString("spaceId"))
            val args = JSONObject().put("readHandle", read.getString("readHandle")).put("index", 0).put("span", false).put("offset", 0)
            val source = f.request("memoryHistory.source", args); assertEquals(accepted.getString("text"), source.getString("text")); assertEquals(accepted.getJSONArray("sourceRefs").getJSONObject(0).toString(), source.getJSONObject("sourceRef").toString()); assertFalse(source.getBoolean("executionAuthorized"))
            f.native.choose(other); f.rejected("memoryHistory.source", args); val current = f.session()
            f.native.workspace.close(); assertFalse(f.native.request("snapshot", JSONObject()).getBoolean("ok"))
            f.rejected("memoryHistory.search", JSONObject().put("sessionHandle", current).put("query", "项目原文").put("cursor", "")); assertEquals("unavailable", f.native.snapshot().getString("namespaceStatus")); assertFalse(f.native.snapshot().toString().contains("主人全局私密记录"))
        }
    }
}
