package dev.pocketengine.ui

import android.app.AlarmManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import dev.pocketengine.EngineApplication
import dev.pocketengine.features.FeatureContext
import dev.pocketengine.features.automation.AutomationModule
import dev.pocketengine.features.remote.B2CommandWaitFixture
import dev.pocketengine.store.SecretVault
import java.io.File
import java.nio.file.Files
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*
import org.robolectric.shadows.ShadowChoreographer

/** Native existing-Job prerequisite -> actual Companion process -> original Runner/file proof. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],manifest=Config.NONE,application=EngineApplication::class,qualifiers="w360dp-h820dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class ContinuousPlanRemoteWaitMountedNativeTest {
    @Before fun frames(){ShadowChoreographer.setPaused(true);ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))}
    private fun j(vararg v:Any):JSONObject{val o=JSONObject();for(i in v.indices step 2)o.put(v[i] as String,v[i+1]);return o}
    private fun scope()=j("ownerId","owner","spaceId","private")
    private fun module(ui:ContinuousPlanMountedNativeTest.Screen)=ui.app.features.module("automation") as AutomationModule
    private fun plan(ui:ContinuousPlanMountedNativeTest.Screen,id:String)=module(ui).handle("getPlan",j("scope",scope(),"id",id)).getJSONObject("plan")
    private fun call(ui:ContinuousPlanMountedNativeTest.Screen,command:String,p:JSONObject)=ui.app.dispatchOwnerAction("automationPlan",j("command",command,"payload",p))
    private fun ref(p:JSONObject)=j("id",p.getString("id"),"revision",p.getLong("revision"))
    private fun until(ui:ContinuousPlanMountedNativeTest.Screen,condition:()->Boolean){val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);while(System.nanoTime()<deadline){if(condition())return;ui.advance()};error("Native Job/plan transition was not observed: ${ui.controller.state.moduleErrors}; tasks=${ui.app.tasks.list()}")}
    private fun chooseSource(ui:ContinuousPlanMountedNativeTest.Screen,title:String){ui.click("等待已有电脑任务（可选）");ui.await(title);val row=ui.nodes().first{ui.text(it).startsWith(title)&&it.config.getOrNull(SemanticsActions.OnClick)?.action!=null};ui.click(ui.text(row))}

    private inner class Computer(private val ui:ContinuousPlanMountedNativeTest.Screen):AutoCloseable {
        val root=Files.createTempDirectory("b2-native-command-wait-").toFile()
        private val script=File(root,"serve.py")
        private val token="b2-only-fixture-token-not-a-real-credential-123456789"
        private val process:Process
        val base:String
        val remote= B2CommandWaitFixture.open(FeatureContext(ui.app,ui.app.settings,SecretVault(ui.app),ui.app::publish),token)
        init {
            File(root,"workspace").mkdirs()
            script.writeText("""
import sys, threading
from pathlib import Path
sys.path.insert(0, sys.argv[1] + '/companion')
from pocket_companion import JobManager, CompanionServer, CompanionHandler
root=Path(sys.argv[2]); workspace=root/'workspace'
config={'stateDir':str(root/'state'),'environments':[{'id':'computer','kind':'local','root':str(workspace),'workers':['command'],'commands':{'python':sys.executable}}]}
manager=JobManager(config, '$token')
class ObservedHandler(CompanionHandler):
    def do_POST(self):
        with (root/'posts.log').open('a') as out: out.write(self.path+'\n')
        super().do_POST()
    def do_GET(self):
        if (root/'drop-reads').exists(): self.close_connection=True; self.connection.close(); return
        super().do_GET()
server=CompanionServer(('127.0.0.1',0),manager);server.RequestHandlerClass=ObservedHandler
thread=threading.Thread(target=server.serve_forever,kwargs={'poll_interval':.02},daemon=True);thread.start()
print(server.server_port,flush=True)
try: sys.stdin.readline()
finally: server.shutdown();server.server_close();manager.close();thread.join(2)
""".trimIndent())
            process=ProcessBuilder("python",script.absolutePath,System.getProperty("b2.source"),root.absolutePath).redirectError(File(root,"server.log")).start()
            val port=process.inputStream.bufferedReader().readLine().toInt();base="http://127.0.0.1:$port"
            remote.handle("saveProfile",j("id","computer","name","真实命令测试电脑","baseUrl",base))
        }
        fun submit(title:String):JSONObject{
            val operation=UUID.randomUUID().toString()
            val code="from pathlib import Path; import time; gate=Path('release');\nwhile not gate.exists(): time.sleep(.02)\nprint('Original command process finished',flush=True)"
            val input=j("profileId","computer","environmentId","computer","worker","command","goal",title,"operationId",operation,"timeoutSeconds",45,"argv",JSONArray().put("python").put("-c").put(code))
            val original=ui.app.tasks.beginExternal("remote","submitJob",operation,input)
            remote.handle("submitJob",input)
            until(ui){ui.app.tasks.get(original.getString("id"),false).optString("externalId").isNotEmpty()}
            return ui.app.tasks.automationRemoteWaitReference(original.getString("id"))
        }
        fun reconcile(source:JSONObject){remote.handle("reconcileOperation",j("profileId","computer","serviceBase",base,"operationId",source.getString("operationId")))}
        fun posts()=File(root,"posts.log").takeIf{it.exists()}?.readLines().orEmpty()
        fun release(){File(root,"workspace/release").writeText("owner fixture released the original process")}
        override fun close(){remote.close();process.outputStream.write('\n'.code);process.outputStream.flush();if(!process.waitFor(4,TimeUnit.SECONDS)){process.destroy();if(!process.waitFor(2,TimeUnit.SECONDS))process.destroyForcibly()};val records=JSONArray();File(root,"state").walkTopDown().filter{it.isFile&&it.extension=="json"}.forEach{file->runCatching{JSONObject(file.readText())}.getOrNull()?.let{job->val row=JSONObject();for(key in listOf("id","operationId","worker","environmentId","status","exitCode","message"))if(job.has(key))row.put(key,job.get(key));if(row.length()>0)records.put(row)}};File(System.getProperty("b2.plan.renders"),root.name+"-companion.json").writeText(j("actualProductionSource",System.getProperty("b2.source")+"/companion/pocket_companion.py","posts",JSONArray(posts()),"savedJobs",records,"stderr",File(root,"server.log").readText()).toString(2));root.deleteRecursively()}
    }

    @Test fun nativeSelectionUnknownRecoveryPauseEditThenOriginalCommandExitAdvancesTheSameGoal(){
        ContinuousPlanMountedNativeTest.Screen().use { ui -> Computer(ui).use { computer ->
            val source=computer.submit("原电脑命令：等待明确释放后退出")
            ui.await("建立一个持续目标");ui.click("建立一个持续目标");ui.edit("最终要得到什么结果","原电脑命令结束后核验来源并归档交付")
            ui.edit("这一步的可交付目标","电脑结束后核实源文件");ui.click("使用的能力 · 手机应用");ui.click("所选项目的文件");ui.click("项目 · 选择已有项目");ui.click("计划验收项目");ui.edit("目标文件","reports/source.md");ui.edit("用于机器核验的完整首行","# Source");chooseSource(ui,"原电脑命令：等待明确释放后退出")
            ui.click("添加后继步骤");ui.edit("这一步的可交付目标","归档交付文件",true);ui.click("使用的能力 · 手机应用",true);ui.click("所选项目的文件");ui.click("项目 · 选择已有项目",true);ui.click("计划验收项目");ui.edit("目标文件","reports/delivery.md",true);ui.edit("用于机器核验的完整首行","# Delivery",true)
            ui.click("保存并审阅范围");ui.await("先等待原电脑进程退出码 0");ui.click("查看原执行身份");ui.await("Run ${source.getString("runId")}");ui.save("remote-wait-01-exact-native-source-review")
            ui.click("同意将本目标需要的项目文件，或手机界面与可见文字，发送给我配置的云端模型。");ui.click("运行条件、窗口与预算");ui.click("需要系统已验证的网络");ui.click("收起运行设置");ui.click("批准并按计划开始");ui.await("等待电脑原任务");val id=ui.plan().getString("id");val due=plan(ui,id).getJSONArray("steps").getJSONObject(0).getLong("dueAt");assertEquals(1,ui.app.tasks.list().length());assertEquals(0,ui.modelCalls.get());assertEquals(0,plan(ui,id).getJSONObject("execution").getInt("admissions"));ui.save("remote-wait-02-original-process-blocks-private-successor")
            File(computer.root,"drop-reads").writeText("simulate read-side transport loss, keep original process alive")
            until(ui){ui.app.tasks.hasUnresolved(source.getString("taskId"))};ui.await("电脑原结果待核对");assertEquals(0,ui.modelCalls.get());assertEquals(listOf("/v1/jobs"),computer.posts())
            ui.click("暂停");ui.await("计划已暂停");ui.click("修改这一步");ui.edit("这一步的可交付目标","保留原电脑等待，核实源文件修订结果");ui.click("保存修改，重新审阅");ui.await("修改后待审阅");assertEquals(due,plan(ui,id).getJSONArray("steps").getJSONObject(0).getLong("dueAt"));assertEquals(source.toString(),plan(ui,id).getJSONArray("steps").getJSONObject(0).getJSONObject("remoteWait").toString())
            ui.click("审阅修改后的目标");ui.await("先等待原电脑进程退出码 0");ui.click("批准并按计划开始");ui.await("继续同一目标")
            File(computer.root,"drop-reads").delete();computer.reconcile(source);computer.release();until(ui){ui.app.tasks.automationRemoteWaitObservation(source).optBoolean("allowed")};ui.alarm();assertEquals(1,ui.app.tasks.list().length());assertEquals(0,ui.modelCalls.get());assertEquals(1,computer.posts().size);ui.save("remote-wait-03-original-exit-zero-retained-while-plan-paused")
            ui.click("继续同一目标");until(ui){plan(ui,id).getJSONArray("steps").getJSONObject(0).optString("status")=="completed"};ui.alarm();until(ui){plan(ui,id).optString("state")=="completed"};ui.click("刷新");ui.await("目标已按标准完成");ui.save("remote-wait-04-original-file-results-after-command-prerequisite")
            val final=plan(ui,id);assertEquals(2,final.getJSONObject("execution").getInt("admissions"));assertEquals(3,ui.app.tasks.list().length());assertEquals(1,computer.posts().size);assertTrue(ui.modelCalls.get()>=4)
            for(i in 0..1){val step=final.getJSONArray("steps").getJSONObject(i);assertTrue(ui.app.tasks.automationArtifactVerified(step.getString("taskId")))}
            assertEquals("needs_confirmation",ui.app.tasks.get(source.getString("taskId"),false).getString("status"));assertFalse(ui.app.tasks.get(source.getString("taskId"),false).getJSONObject("evidence").getBoolean("automaticallyVerified"))
            ui.click("打开电脑原任务");assertEquals(source.getString("taskId"),ui.controller.state.requestedTaskId)
        } }
    }

    @Test fun cancellingTheWaitNeverCancelsOrResubmitsTheIndependentOriginalCommand(){
        ContinuousPlanMountedNativeTest.Screen().use { ui -> Computer(ui).use { computer ->
            val source=computer.submit("独立原电脑命令，取消等待也继续运行");ui.await("建立一个持续目标")
            val project=ui.controller.state.feature("workspace").getJSONArray("projects").getJSONObject(0).getString("id")
            val definition=executionPlanDefinition("取消等待不等于停止原电脑任务",listOf(ExecutionStepDraft("等待后核实来源","workspace",project,"reports/source.md","# Source","16",remoteWait=source)),"0",System.currentTimeMillis())
            val created=call(ui,"create",j("plan",definition)).getJSONObject("plan");val id=created.getString("id");call(ui,"approve",ref(created).put("settings",j("mode","automatic","maxTaskAdmissions",1,"modelObservationConsent",true,"allowPrivateArtifactFreeze",true,"requiresNetwork",false,"expiresAt",System.currentTimeMillis()+3_600_000L)))
            call(ui,"cancel",ref(plan(ui,id)));assertEquals("cancelled",plan(ui,id).getString("state"));assertFalse(ui.app.tasks.get(source.getString("taskId"),false).optBoolean("cancelRequested"));assertEquals(listOf("/v1/jobs"),computer.posts());computer.release();until(ui){ui.app.tasks.automationRemoteWaitObservation(source).optBoolean("allowed")};ui.alarm();assertEquals(1,ui.app.tasks.list().length());assertEquals(0,ui.modelCalls.get());assertEquals(listOf("/v1/jobs"),computer.posts())
        } }
    }
}
