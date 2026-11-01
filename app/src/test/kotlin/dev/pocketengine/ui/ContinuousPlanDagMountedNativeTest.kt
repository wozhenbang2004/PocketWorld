package dev.pocketengine.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import dev.pocketengine.EngineApplication
import java.util.concurrent.CountDownLatch
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*
import org.robolectric.shadows.ShadowChoreographer
import java.time.Duration

private typealias DagScreen = ContinuousPlanMountedNativeTest.Screen

/** Real multi-parent native creation/editing and original Runner results, without supplied success DTOs. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],manifest=Config.NONE,application=EngineApplication::class,qualifiers="w360dp-h820dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class ContinuousPlanDagMountedNativeTest {
    private val goals=listOf("准备输入甲", "准备输入乙", "合并两份已核验输入", "整理最终归档", "前置失败后保留恢复说明")
    private val paths=listOf("dag/a.md", "dag/b.md", "dag/join.md", "dag/final.md", "dag/recovery.md")
    private val headings=listOf("# Input A", "# Input B", "# Join", "# Final", "# Recovery")
    private val files get()=paths.mapIndexed{index,path->path to headings[index]+"\nActual immutable fixture file for the native DAG journey.\n"}
    @Before fun manualFrames(){ShadowChoreographer.setPaused(true);ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))}
    private fun outcome(value:String)=when(value){"failed"->"前置失败";"cancelled"->"前置取消";"settled"->"前置已结束（含未采用）";else->"前置成功"}
    private fun dependencies(ui:DagScreen,index:Int,wanted:Map<Int,String>,capture:String?=null){
        val label=ui.nodes().filter{ui.text(it).startsWith("前置条件 · ")&&it.config.getOrNull(SemanticsActions.OnClick)?.action!=null}.last().let(ui::text)
        ui.click(label,last=true);ui.await("选择前置条件")
        for(parent in 0 until index){
            val title="步骤 ${parent+1} · ${goals[parent]}"
            val row=ui.nodes().first{ui.text(it)==title&&it.config.getOrNull(SemanticsProperties.ToggleableState)!=null}
            if((row.config.getOrNull(SemanticsProperties.ToggleableState).toString()=="On")!=wanted.containsKey(parent))ui.click(title)
            wanted[parent]?.let{expected->
                val button=ui.nodes().first{ui.text(it).startsWith("步骤 ${parent+1} 的结果 · ")&&it.config.getOrNull(SemanticsActions.OnClick)?.action!=null}
                if(!ui.text(button).endsWith(outcome(expected))){ui.click(ui.text(button));ui.click(outcome(expected))}
            }
        }
        capture?.let{ui.save(it)};ui.click("保留 ${wanted.size} 项前置")
    }
    private fun fillLast(ui:DagScreen,index:Int,wanted:Map<Int,String>){
        ui.edit("这一步的可交付目标",goals[index],true);ui.click("使用的能力 · 手机应用",true);ui.click("所选项目的文件");ui.click("项目 · 选择已有项目",true);ui.click("计划验收项目");ui.edit("目标文件",paths[index],true);ui.edit("用于机器核验的完整首行",headings[index],true)
        if(index>0)dependencies(ui,index,wanted,if(index==2)"dag-01-create-two-parent-picker"else null)
    }
    private fun review(ui:DagScreen,manual:Boolean,first:Boolean){
        ui.await("审阅这个目标的执行范围")
        if(first){ui.click("同意将本目标需要的项目文件，或手机界面与可见文字，发送给我配置的云端模型。");ui.click("运行条件、窗口与预算");ui.click("需要系统已验证的网络");ui.click("收起运行设置");if(manual)ui.click("按时间、依赖与条件自动推进")}
        ui.click(if(manual)"保存授权，等我开始"else"批准并按计划开始")
    }
    private fun step(ui:DagScreen,index:Int)=ui.plan().getJSONArray("steps").getJSONObject(index)
    private fun awaitStep(ui:DagScreen,index:Int,status:String){repeat(60){if(step(ui,index).optString("status")==status)return;ui.advance()};error("Step $index did not become $status: ${ui.plan()}")}
    private fun awaitPath(ui:DagScreen,path:String){repeat(50){if(path in ui.requestedPaths)return;ui.advance()};error("Original model request for $path did not enter")}
    private fun refs(step:JSONObject):Map<String,String>{val a=step.getJSONArray("dependencies");return (0 until a.length()).associate{a.getJSONObject(it).getString("stepId") to a.getJSONObject(it).getString("outcome")}}
    private fun assertMachineOriginal(ui:DagScreen,index:Int){val task=ui.app.tasks.get(step(ui,index).getString("taskId"),false);assertEquals("completed",task.getString("status"));assertTrue(ui.app.tasks.automationArtifactVerified(task.getString("id")));assertEquals(1,task.getJSONArray("operations").length());val events=task.getJSONArray("events");for(at in 0 until events.length())assertNotEquals("user_verification",events.getJSONObject(at).optString("type"))}
    @Test fun nativeDagWaitsForBothParentsAndPausedEditAddsAnEdgeWithoutLosingOriginalHistory(){
        val releaseB=CountDownLatch(1)
        DagScreen(files,holds=mapOf(paths[1] to releaseB)).use{ui->
            ui.await("建立一个持续目标");ui.click("建立一个持续目标");ui.edit("最终要得到什么结果","等待两份真实输入后汇合，并按修改后的依赖归档")
            fillLast(ui,0,emptyMap());ui.click("添加后继步骤");fillLast(ui,1,emptyMap());ui.click("添加后继步骤");fillLast(ui,2,mapOf(0 to "completed",1 to "completed"));ui.click("添加后继步骤");fillLast(ui,3,mapOf(2 to "completed"));ui.click("添加失败恢复");fillLast(ui,4,mapOf(0 to "completed",1 to "failed"));ui.click("保存并审阅范围");ui.save("dag-02-whole-goal-dependency-review");review(ui,true,true);ui.await("等你开始")
            assertEquals(0,ui.app.tasks.list().length());val ids=(0..4).map{step(ui,it).getString("id")};assertEquals(mapOf(ids[0] to "completed",ids[1] to "completed"),refs(step(ui,2)))
            ui.click("开始目标");awaitStep(ui,0,"completed");ui.alarm();awaitPath(ui,paths[1]);val first=ui.app.tasks.get(step(ui,0).getString("taskId"),false);val b=ui.app.tasks.get(step(ui,1).getString("taskId"),false)
            assertFalse(paths[2] in ui.requestedPaths);assertEquals("",step(ui,2).optString("taskId"));assertEquals(2,ui.app.tasks.list().length())
            // A concurrent native owner action advances only the plan revision. Invoke the
            // already-rendered button to check a rejected stale control, without any new task.
            val stalePause=ui.nodes().first{ui.text(it)=="暂停"&&it.config.getOrNull(SemanticsActions.OnClick)?.action!=null}
            val beforeStale=ui.plan();ui.app.dispatchOwnerAction("automationPlan",JSONObject().put("command","resume").put("payload",JSONObject().put("id",beforeStale.getString("id")).put("revision",beforeStale.getLong("revision"))))
            assertTrue(stalePause.config[SemanticsActions.OnClick].action!!.invoke());ui.advance();ui.await("计划或步骤已改变，请刷新后重试");assertEquals("active",ui.plan().getJSONObject("execution").getString("state"));ui.save("dag-03a-stale-control-error-retained")
            repeat(10){if("owner.automationPlan" in ui.controller.state.moduleBusy)ui.advance()};ui.click("刷新");ui.advance();assertTrue(ui.controller.state.moduleErrors["owner.automationPlan"].orEmpty().contains("计划或步骤已改变"));ui.click("暂停");ui.await("计划已暂停")
            val originalJoin=JSONObject(step(ui,2).toString());val originalFinal=JSONObject(step(ui,3).toString());ui.click("修改这一步",at=1);ui.await("尚无修改，返回即可保留当前授权与前置条件。")
            assertTrue(ui.nodes().any{ui.text(it)=="保存修改，重新审阅"&&it.config.contains(SemanticsProperties.Disabled)})
            dependencies(ui,3,mapOf(0 to "completed",2 to "completed"),"dag-03-edit-future-join-retains-parents");ui.edit("这一步的可交付目标","整理已核验版本并归档两份输入的汇合结果");ui.click("保存修改，重新审阅");ui.await("修改后待审阅")
            assertEquals(refs(originalJoin),refs(step(ui,2)));assertEquals(originalJoin.getLong("dueAt"),step(ui,2).getLong("dueAt"));assertEquals(mapOf(ids[0] to "completed",ids[2] to "completed"),refs(step(ui,3)));assertEquals(originalFinal.getLong("dueAt"),step(ui,3).getLong("dueAt"));assertEquals(first.getString("id"),step(ui,0).getString("taskId"));assertEquals(first.getString("runId"),ui.app.tasks.get(first.getString("id"),false).getString("runId"));assertEquals(b.getString("id"),step(ui,1).getString("taskId"))
            ui.click("审阅修改后的目标");review(ui,true,false);ui.await("继续同一目标");ui.click("继续同一目标");releaseB.countDown();awaitStep(ui,1,"completed");assertEquals(b.getString("runId"),ui.app.tasks.get(b.getString("id"),false).getString("runId"));assertEquals(b.getInt("coordinatorEpoch"),ui.app.tasks.get(b.getString("id"),false).getInt("coordinatorEpoch"))
            ui.alarm();awaitStep(ui,2,"completed");ui.alarm();awaitStep(ui,3,"completed");ui.await("目标已按标准完成");ui.save("dag-04-complete-original-multi-parent-goal");assertEquals("skipped",step(ui,4).getString("status"));assertEquals(4,ui.app.tasks.list().length());for(index in 0..3)assertMachineOriginal(ui,index);ui.click("打开原任务与成果",at=3);assertEquals(step(ui,3).getString("taskId"),ui.controller.state.requestedTaskId)
        }
    }
    @Test fun actualFailedParentSelectsTheReviewedMultiParentRecoveryAndNeverTheSuccessJoin(){
        DagScreen(files,failPaths=setOf(paths[1])).use{ui->
            val project=ui.controller.state.feature("workspace").getJSONArray("projects").getJSONObject(0).getString("id")
            fun draft(index:Int,deps:List<ExecutionDependencyDraft>)=ExecutionStepDraft(goals[index],"workspace",project,paths[index],headings[index],"16",deps)
            val definition=executionPlanDefinition("两份输入中一份真实失败时，保留原失败并形成恢复说明",listOf(draft(0,emptyList()),draft(1,emptyList()),draft(2,listOf(ExecutionDependencyDraft(0),ExecutionDependencyDraft(1))),draft(4,listOf(ExecutionDependencyDraft(0),ExecutionDependencyDraft(1,"failed")))),"0",System.currentTimeMillis())
            ui.app.dispatchOwnerAction("automationPlan",JSONObject().put("command","create").put("payload",JSONObject().put("plan",definition)));ui.click("刷新");ui.await(definition.getString("goal"));ui.click(ui.nodes().first{ui.text(it).startsWith(definition.getString("goal"))&&it.config.getOrNull(SemanticsActions.OnClick)?.action!=null}.let(ui::text));ui.click("审阅目标与执行范围");review(ui,false,true);awaitStep(ui,0,"completed");ui.alarm();awaitStep(ui,1,"failed");assertTrue(step(ui,1).getJSONObject("taskObservation").getBoolean("terminalVerified"));assertFalse(ui.app.tasks.hasUnresolved(step(ui,1).getString("taskId")));assertEquals("",step(ui,2).optString("taskId"));ui.alarm();awaitStep(ui,3,"completed");ui.await("本轮结束，有步骤失败");ui.save("dag-05-known-failure-multi-parent-recovery")
            assertEquals("skipped",step(ui,2).getString("status"));assertFalse(paths[2] in ui.requestedPaths);assertEquals(3,ui.app.tasks.list().length());assertEquals("failed",ui.app.tasks.get(step(ui,1).getString("taskId"),false).getString("status"));assertMachineOriginal(ui,0);assertMachineOriginal(ui,3);assertEquals(2,refs(step(ui,3)).size);ui.click("打开原任务与成果",at=2);assertEquals(step(ui,3).getString("taskId"),ui.controller.state.requestedTaskId)
        }
    }
}
