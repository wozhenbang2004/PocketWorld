package dev.pocketengine.ui

import dev.pocketengine.EngineApplication
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*
import java.util.concurrent.TimeUnit

/** The existing source action and dialog consume the real Controller/Application/source reader. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],manifest=Config.NONE,application=EngineApplication::class,qualifiers="w320dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class SourceLearningMountedNativeTest {
    @Before fun keys() { TaskMemoryNativeWorkflowTest().keys() }
    private fun draft()=JSONObject().put("layer","fact").put("text","仅这个项目保留的待审约定").put("reason","来自这次选中的真实任务结果").put("applicability","仅当前项目，仍须主人核对").put("confidence",0.5).toString()
    @Test fun previewLongOriginalThenExplicitProposalKeepsCandidateSeparateAndReturnsToExistingReview() {
        TaskMemoryNativeWorkflowTest.Fixture().use { f ->
            val source=f.app.tasks.createCapabilityTask("完整原结果阅读任务","").getString("id")
            val summary="原始确认说明。".repeat(310)+"SOURCE_TAIL_NATIVE_316"
            f.app.tasks.finish(source,"needs_confirmation",summary,JSONObject());f.app.dispatchOwnerAction("confirmResult",JSONObject().put("taskId",source).put("completed",true));f.answer=draft()
            TaskMemoryMountedNativeTest.Screen(f).use { ui ->
                ui.sourceTask=source;ui.mode=3;ui.advance();ui.click("提炼待审候选");ui.await("确认时的结果摘要");assertEquals(0,f.calls.get());ui.save("07-source-learning-preview")
                ui.click("查看完整内容（当前显示前2000字）");ui.await("SOURCE_TAIL_NATIVE_316");assertFalse(ui.nodes().any{ui.text(it).contains("This UI prose")})
                ui.click("发送来源并提炼候选");ui.await("候选待审，尚未采用");ui.save("08-source-learning-pending")
                assertEquals(1,f.calls.get());assertTrue(f.requests[0].toString().contains("SOURCE_TAIL_NATIVE_316"))
                val candidate=f.memory.handle("listCandidates",JSONObject().put("scope",TaskMemoryNativeWorkflowTest.scope())).getJSONArray("candidates").getJSONObject(0)
                assertEquals("pending",candidate.getString("status"));assertEquals("task:$source:verified_result",candidate.getJSONArray("sourceRefs").getJSONObject(0).getString("sourceId"))
                ui.click("去审核候选");assertEquals("memoryReview",f.controller.state.requestedConnectionSection);assertTrue(f.controller.state.ownerResponses("learning").isEmpty())
            }
        }
    }
    @Test fun nativeStopShowsRealCancellationAndNeverTreatsLateResponseAsPendingReview() {
        TaskMemoryNativeWorkflowTest.Fixture().use { f ->
            f.answer=draft();f.blockCall=1
            TaskMemoryMountedNativeTest.Screen(f).use { ui ->
                ui.mode=3;ui.advance();ui.click("提炼待审候选");ui.await("确认时的结果摘要");ui.click("发送来源并提炼候选");assertTrue(f.blocked.await(4,TimeUnit.SECONDS));ui.await("停止本次提炼")
                ui.click("停止本次提炼");ui.await("本次提炼已停止");ui.save("09-source-learning-stopped");f.release.countDown()
                TaskMemoryNativeWorkflowTest.await { f.app.tasks.list().let { rows -> (0 until rows.length()).any{rows.getJSONObject(it).optString("goal").contains("真实来源提炼")&&rows.getJSONObject(it).optString("status")=="cancelled"} } }
                assertEquals(0,f.memory.handle("listCandidates",JSONObject().put("scope",TaskMemoryNativeWorkflowTest.scope())).getJSONArray("candidates").length());assertEquals(1,f.calls.get());assertFalse(ui.nodes().any{ui.text(it)=="候选待审，尚未采用"})
            }
        }
    }
    @Test fun unconfirmedAndForgottenSourceRemainReadableRefusalsWithNoModelCall() {
        TaskMemoryNativeWorkflowTest.Fixture().use { f ->
            val unconfirmed=f.app.tasks.createCapabilityTask("尚未确认的空结果任务","").getString("id")
            TaskMemoryMountedNativeTest.Screen(f).use { ui ->
                ui.sourceTask=unconfirmed;ui.mode=3;ui.advance();ui.click("提炼待审候选");ui.await("真实来源不存在或尚未确认");ui.save("10-source-learning-unconfirmed");assertEquals(0,f.calls.get());ui.click("返回")
                f.app.nativeOwnerWorkflows.memory("forgetReviewed",JSONObject().put("id",f.old.getString("id")).put("expectedRevision",f.old.getLong("revision")))
                ui.sourceTask=f.sourceTask;ui.advance();ui.click("提炼待审候选");ui.await("此来源已遗忘");ui.save("11-source-learning-forgotten")
                assertEquals(0,f.calls.get());assertFalse(ui.nodes().any{ui.text(it).contains("B1_OLD_REFERENCE_741")});assertTrue(ui.nodes().any{ui.text(it)=="发送来源并提炼候选"&&it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)})
            }
        }
    }
}
