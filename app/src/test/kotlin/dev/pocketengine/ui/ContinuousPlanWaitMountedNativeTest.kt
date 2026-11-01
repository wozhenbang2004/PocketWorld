package dev.pocketengine.ui

import android.content.Intent
import android.os.BatteryManager
import dev.pocketengine.EngineApplication
import dev.pocketengine.features.AutomationPlanHost
import dev.pocketengine.features.FeatureContext
import dev.pocketengine.features.automation.AutomationModule
import dev.pocketengine.store.TaskStore
import java.time.Duration
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*
import org.robolectric.shadows.ShadowChoreographer
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull

/** Actual Android condition adapter + existing native alarm/task/result path; no injected online DTO. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],manifest=Config.NONE,application=EngineApplication::class,qualifiers="w360dp-h820dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class ContinuousPlanWaitMountedNativeTest {
    @Before fun manualFrames(){ShadowChoreographer.setPaused(true);ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))}
    private fun j(vararg values:Any):JSONObject{val out=JSONObject();for(i in values.indices step 2)out.put(values[i] as String,values[i+1]);return out}
    private fun scope()=j("ownerId","owner","spaceId","private")
    private fun battery(ui:ContinuousPlanMountedNativeTest.Screen,status:Int,level:Int=80){ui.app.sendStickyBroadcast(Intent(Intent.ACTION_BATTERY_CHANGED).putExtra(BatteryManager.EXTRA_PRESENT,true).putExtra(BatteryManager.EXTRA_LEVEL,level).putExtra(BatteryManager.EXTRA_SCALE,100).putExtra(BatteryManager.EXTRA_STATUS,status))}
    private fun module(ui:ContinuousPlanMountedNativeTest.Screen)=ui.app.features.module("automation") as AutomationModule
    private fun plan(ui:ContinuousPlanMountedNativeTest.Screen,id:String)=module(ui).handle("getPlan",j("scope",scope(),"id",id)).getJSONObject("plan")
    private fun call(ui:ContinuousPlanMountedNativeTest.Screen,command:String,payload:JSONObject)=ui.app.dispatchOwnerAction("automationPlan",j("command",command,"payload",payload))
    private fun ref(value:JSONObject)=j("id",value.getString("id"),"revision",value.getLong("revision"))
    private fun settle(ui:ContinuousPlanMountedNativeTest.Screen){repeat(12){if("owner.automationPlan" in ui.controller.state.moduleBusy)ui.advance()}}
    private fun awaitState(ui:ContinuousPlanMountedNativeTest.Screen,id:String,state:String){repeat(60){if(plan(ui,id).optString("state")==state)return;ui.advance()};error("Missing $state: ${plan(ui,id)}")}
    private fun select(ui:ContinuousPlanMountedNativeTest.Screen,title:String){settle(ui);ui.click("刷新");ui.await(title);ui.click(ui.nodes().first{ui.text(it).startsWith(title)&&it.config.getOrNull(SemanticsActions.OnClick)?.action!=null}.let(ui::text))}
    private fun create(ui:ContinuousPlanMountedNativeTest.Screen,title:String,path:String="reports/source.md",heading:String="# Source",automatic:Boolean=true,windowMillis:Long=3_600_000L):String{
        val project=ui.controller.state.feature("workspace").getJSONArray("projects").getJSONObject(0).getString("id")
        val definition=executionPlanDefinition(title,listOf(ExecutionStepDraft(title,"workspace",project,path,heading,"16")),"0",System.currentTimeMillis())
        val made=call(ui,"create",j("plan",definition)).getJSONObject("plan");call(ui,"approve",ref(made).put("settings",j("mode",if(automatic)"automatic"else"manual","maxTaskAdmissions",1,"modelObservationConsent",true,"allowPrivateArtifactFreeze",true,"requiresNetwork",false,"requiresCharging",true,"expiresAt",System.currentTimeMillis()+windowMillis)));return made.getString("id")
    }
    private fun firstTask(ui:ContinuousPlanMountedNativeTest.Screen,id:String)=ui.app.tasks.get(plan(ui,id).getJSONArray("steps").getJSONObject(0).getString("taskId"),false)
    private fun pendingOriginal(ui:ContinuousPlanMountedNativeTest.Screen,id:String):Pair<JSONObject,JSONObject>{
        val current=plan(ui,id);module(ui).startPlanExecution(scope(),id,current.getLong("revision"));val ready=plan(ui,id);val step=ready.getJSONArray("steps").getJSONObject(0);val prepared=module(ui).preparePlanTask(scope(),id,ready.getLong("revision"),step.getString("id"));val request=module(ui).planTaskRequest(scope(),id,step.getString("id"),prepared.getString("occurrenceId"));return request to ui.app.tasks.createReminderTask(request,"capabilities")
    }
    @Test fun nativeReviewShowsExactProjectAndStandardThenConditionRecoveryStartsOnlyTheSelectedGoal(){
        ContinuousPlanMountedNativeTest.Screen().use{ui->
            battery(ui,BatteryManager.BATTERY_STATUS_UNKNOWN,120);ui.await("建立一个持续目标");ui.click("建立一个持续目标");ui.edit("最终要得到什么结果","充电可用后核验指定项目原文件");ui.edit("这一步的可交付目标","核验并冻结源文件");ui.click("使用的能力 · 手机应用");ui.click("所选项目的文件");ui.click("项目 · 选择已有项目");ui.click("计划验收项目");ui.edit("目标文件","reports/source.md");ui.edit("用于机器核验的完整首行","# Source");ui.click("保存并审阅范围");ui.await("项目 · 计划验收项目")
            val project=ui.controller.state.feature("workspace").getJSONArray("projects").getJSONObject(0).getString("id");ui.await("项目 ID · $project");ui.await("完整首行 · # Source");ui.await("最小大小 · 16 UTF-8 字节");ui.save("wait-01-native-exact-project-and-file-review")
            ui.click("同意将本目标需要的项目文件，或手机界面与可见文字，发送给我配置的云端模型。");ui.click("运行条件、窗口与预算");ui.click("需要系统已验证的网络");ui.click("仅在充电时运行");ui.click("收起运行设置");ui.click("批准并按计划开始");ui.await("运行条件暂不可读取");val selected=ui.plan().getString("id");assertEquals(0,ui.app.tasks.list().length());assertEquals(0,ui.modelCalls.get())
            val other=create(ui,"另一个已审阅的等待目标","reports/delivery.md","# Delivery");battery(ui,BatteryManager.BATTERY_STATUS_DISCHARGING);settle(ui);ui.click("刷新");ui.await("等待运行条件");ui.save("wait-02-real-battery-blocks-before-task")
            battery(ui,BatteryManager.BATTERY_STATUS_CHARGING);settle(ui);ui.click("刷新");ui.await("可以核对并继续此目标");assertEquals(0,ui.app.tasks.list().length());settle(ui);ui.click("核对并继续此目标");awaitState(ui,selected,"completed");assertEquals(1,ui.app.tasks.list().length());assertEquals("",plan(ui,other).getJSONArray("steps").getJSONObject(0).optString("taskId"));assertTrue(ui.app.tasks.automationArtifactVerified(firstTask(ui,selected).getString("id")));ui.save("wait-03-current-condition-original-result")
            ui.click("返回目标列表");select(ui,"另一个已审阅的等待目标");ui.click("取消目标");ui.click("取消目标",last=true);ui.await("后继执行已取消");ui.alarm();assertEquals(1,ui.app.tasks.list().length());assertEquals("cancelled",plan(ui,other).getString("state"))
        }
    }
    @Test fun reopenedNativeBooksAndSqliteQueryFirstRetainOriginalOccurrenceBeforeAnyResume(){
        ContinuousPlanMountedNativeTest.Screen().use{ui->
            ui.await("建立一个持续目标");battery(ui,BatteryManager.BATTERY_STATUS_CHARGING);val title="恢复同一次登记的原任务，充电恢复后继续";val id=create(ui,title,automatic=false);val(request,original)=pendingOriginal(ui,id);assertEquals("",original.optString("runId"));assertEquals(1,ui.app.tasks.list().length());assertEquals(0,ui.modelCalls.get());battery(ui,BatteryManager.BATTERY_STATUS_DISCHARGING)
            // Fresh native DB recovery and Book/Resolver instances, same persisted files. This
            // is a reopen check, not a claim that an Android process/device was killed here.
            val reopened=TaskStore(ui.app);val reader=AutomationPlanHost(reopened);val recovered=AutomationModule(FeatureContext(ui.app,null,null,{_ ->}),reader::resolveEvidence,reader);recovered.refreshPlanTasks(scope(),id);assertEquals(original.getString("id"),recovered.handle("getPlan",j("scope",scope(),"id",id)).getJSONObject("plan").getJSONArray("steps").getJSONObject(0).getString("taskId"));assertEquals(0,ui.modelCalls.get())
            select(ui,title);ui.await("等待运行条件");settle(ui);ui.click("只核对原任务状态");settle(ui);ui.click("核对并继续此目标");assertEquals(0,ui.modelCalls.get());assertEquals(1,ui.app.tasks.list().length());assertEquals("",firstTask(ui,id).optString("runId"));assertEquals(1,plan(ui,id).getJSONObject("execution").getInt("admissions"));ui.save("wait-04-reopened-original-held-by-current-condition")
            battery(ui,BatteryManager.BATTERY_STATUS_CHARGING);settle(ui);ui.click("刷新");ui.await("原任务已登记，尚未开始执行");settle(ui);ui.click("核对并继续此目标");awaitState(ui,id,"completed");val done=firstTask(ui,id);assertEquals(original.getString("id"),done.getString("id"));assertEquals(request.getString("occurrenceId"),done.getJSONObject("origin").getString("occurrenceId"));assertEquals(1,done.getInt("coordinatorEpoch"));assertEquals(1,done.getJSONArray("operations").length());assertEquals(1,plan(ui,id).getJSONObject("execution").getInt("admissions"));assertEquals(1,ui.app.tasks.list().length());assertTrue(ui.app.tasks.automationArtifactVerified(done.getString("id")));ui.save("wait-05-same-occurrence-native-result-after-reopen")
        }
    }
    @Test fun expiryAndFreshReviewNeverExtendTheOldOccurrenceAndCancelNeverRetriggersIt(){
        ContinuousPlanMountedNativeTest.Screen().use{ui->
            ui.await("建立一个持续目标");battery(ui,BatteryManager.BATTERY_STATUS_CHARGING);val title="过期原触发只核对，不恢复旧许可";val id=create(ui,title,automatic=false,windowMillis=2_000L);val(request,original)=pendingOriginal(ui,id);val deadline=request.getLong("validUntil")
            // The production bridge reads System.currentTimeMillis, not Android uptime. A short
            // real wall-clock window exercises that exact path without changing any saved intent.
            assertTrue("Original intent must exist before its deadline",System.currentTimeMillis()<deadline);Thread.sleep((deadline-System.currentTimeMillis()+25).coerceAtLeast(0));assertTrue("Original wall-clock deadline has elapsed",System.currentTimeMillis()>=deadline);select(ui,title);ui.await("本次执行窗口已结束");settle(ui);ui.click("只核对原任务状态");assertEquals(0,ui.modelCalls.get())
            call(ui,"pause",ref(plan(ui,id)));val paused=plan(ui,id);call(ui,"approve",ref(paused).put("settings",j("mode","automatic","maxTaskAdmissions",2,"modelObservationConsent",true,"allowPrivateArtifactFreeze",true,"requiresNetwork",false,"requiresCharging",true,"expiresAt",System.currentTimeMillis()+3_600_000L)));call(ui,"resume",ref(plan(ui,id)));call(ui,"refresh",ref(plan(ui,id)));assertEquals(0,ui.modelCalls.get());assertEquals(1,ui.app.tasks.list().length());assertEquals(original.getString("id"),firstTask(ui,id).getString("id"));assertEquals("",firstTask(ui,id).optString("runId"));assertEquals(deadline,plan(ui,id).getJSONArray("steps").getJSONObject(0).getJSONObject("taskAdmission").getLong("validUntil"));settle(ui);ui.click("刷新");ui.await("本次执行窗口已结束");ui.save("wait-06-expired-occurrence-stays-blocked-after-new-review")
            ui.click("取消目标");ui.click("取消目标",last=true);ui.await("后继执行已取消");ui.alarm();assertEquals("cancelled",plan(ui,id).getString("state"));assertEquals(1,ui.app.tasks.list().length());assertEquals(0,ui.modelCalls.get())
        }
    }
}
