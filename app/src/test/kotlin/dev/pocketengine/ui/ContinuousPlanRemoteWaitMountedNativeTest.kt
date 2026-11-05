package dev.pocketengine.ui

import dev.pocketengine.EngineApplication
import dev.pocketengine.features.FeatureContext
import dev.pocketengine.features.remote.B2CommandWaitFixture
import dev.pocketengine.store.SecretVault
import dev.pocketengine.testutil.ReadyApplicationFixture
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.SQLiteMode

/** Web selection of an existing Job -> actual Companion process -> original Runner/file proof. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = EngineApplication::class,
    shadows = [ReadyApplicationFixture.NoUnrelatedStartup::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class ContinuousPlanRemoteWaitMountedNativeTest {
    private fun j(vararg values: Any): JSONObject { val out = JSONObject(); for (i in values.indices step 2) out.put(values[i] as String, values[i + 1]); return out }
    private fun until(ui: ContinuousPlanMountedNativeTest.Screen, condition: () -> Boolean) = ui.until("original Job/plan transition", condition)

    private inner class Computer(private val ui:ContinuousPlanMountedNativeTest.Screen):AutoCloseable {
        private val sourceRoot=File(checkNotNull(System.getProperty("pocket.gateway.source")?.takeIf { it.isNotBlank() }) {
            "Host runner must set -Dpocket.gateway.source to the original repository root"
        }).canonicalFile
        private val companionSource=File(sourceRoot,"companion/pocket_companion.py").also {
            check(it.isFile && it.canRead()) { "Original companion source is unavailable: ${it.absolutePath}" }
        }
        private val python=File(checkNotNull(System.getProperty("pocket.gateway.python")?.takeIf { it.isNotBlank() }
            ?: System.getProperty("a25.python")?.takeIf { it.isNotBlank() }) {
            "Host runner must set -Dpocket.gateway.python or -Da25.python to the actual Python executable"
        }).canonicalFile.also { check(it.isFile && it.canExecute()) { "Configured Python executable is unavailable: ${it.absolutePath}" } }
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
            process=ProcessBuilder(python.absolutePath,script.absolutePath,sourceRoot.absolutePath,root.absolutePath).redirectError(File(root,"server.log")).start()
            val port=checkNotNull(process.inputStream.bufferedReader().readLine()?.toIntOrNull()?.takeIf { it in 1..65535 }) {
                "Original companion did not announce a valid loopback port; inspect ${File(root,"server.log").absolutePath}"
            };base="http://127.0.0.1:$port"
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
        override fun close(){remote.close();process.outputStream.write('\n'.code);process.outputStream.flush();if(!process.waitFor(4,TimeUnit.SECONDS)){process.destroy();if(!process.waitFor(2,TimeUnit.SECONDS))process.destroyForcibly()};val records=JSONArray();File(root,"state").walkTopDown().filter{it.isFile&&it.extension=="json"}.forEach{file->runCatching{JSONObject(file.readText())}.getOrNull()?.let{job->val row=JSONObject();for(key in listOf("id","operationId","worker","environmentId","status","exitCode","message"))if(job.has(key))row.put(key,job.get(key));if(row.length()>0)records.put(row)}};File(ui.evidence,root.name+"-companion.json").writeText(j("actualProductionSource",companionSource.absolutePath,"posts",JSONArray(posts()),"savedJobs",records,"stderr",File(root,"server.log").readText()).toString(2));root.deleteRecursively()}
    }

    @Test fun nativeSelectionUnknownRecoveryPauseEditThenOriginalCommandExitAdvancesTheSameGoal() {
        ContinuousPlanMountedNativeTest.Screen().use { ui -> Computer(ui).use { computer ->
            val source = computer.submit("原电脑命令：等待明确释放后退出")
            val review = ui.reviewCreate("原电脑命令结束后核验来源并归档交付", listOf(
                ui.stepDraft("电脑结束后核实源文件", "reports/source.md", "# Source", remoteWait = source),
                ui.stepDraft("归档交付文件", "reports/delivery.md", "# Delivery", mapOf(0 to "completed"))
            ))
            val reviewedSource = review.getJSONObject("plan").getJSONArray("steps").getJSONObject(0).getJSONObject("remoteWait")
            assertEquals(source.getString("runId"), reviewedSource.getString("runId"))
            assertEquals(source.getString("taskId"), reviewedSource.getString("taskId"))
            assertEquals(source.getString("operationId"), reviewedSource.getString("operationId"))
            assertEquals(source.toString(), reviewedSource.toString())
            val id = ui.confirm(review).getJSONObject("reading").getJSONObject("plan").getString("id")
            ui.approve(id)
            val due = ui.plan(id).getJSONArray("steps").getJSONObject(0).getLong("dueAt")
            assertEquals(1, ui.app.tasks.list().length()); assertEquals(0, ui.modelCalls.get())
            assertEquals(0, ui.plan(id).getJSONObject("execution").getInt("admissions"))
            assertFalse(ui.read(id).getJSONObject("plan").getJSONObject("runtimeDecision").getJSONObject("remoteWaitStatus").getBoolean("allowed"))
            ui.save("remote-wait-01-original-process-blocks-private-successor")
            File(computer.root, "drop-reads").writeText("simulate read-side transport loss, keep original process alive")
            until(ui) { ui.app.tasks.hasUnresolved(source.getString("taskId")) }
            assertEquals(0, ui.modelCalls.get()); assertEquals(listOf("/v1/jobs"), computer.posts())
            ui.action(id, "pause")
            ui.editStep(id, 0, ui.stepDraft("保留原电脑等待，核实源文件修订结果", "reports/source.md", "# Source", remoteWait = "preserve"))
            assertEquals(due, ui.plan(id).getJSONArray("steps").getJSONObject(0).getLong("dueAt"))
            assertEquals(source.toString(), ui.plan(id).getJSONArray("steps").getJSONObject(0).getJSONObject("remoteWait").toString())
            assertFalse(ui.read(id).getJSONObject("plan").getBoolean("approvalValid"))
            ui.approve(id)
            assertEquals("paused", ui.plan(id).getJSONObject("execution").getString("state"))
            File(computer.root, "drop-reads").delete(); computer.reconcile(source); computer.release()
            until(ui) { ui.app.tasks.automationRemoteWaitObservation(source).optBoolean("allowed") }
            ui.alarm()
            assertEquals(1, ui.app.tasks.list().length()); assertEquals(0, ui.modelCalls.get()); assertEquals(1, computer.posts().size)
            ui.save("remote-wait-02-original-exit-zero-retained-while-plan-paused")
            ui.action(id, "resume"); ui.awaitStep(id, 0, "completed"); ui.alarm(); ui.awaitState(id, "completed")
            ui.save("remote-wait-03-original-file-results-after-command-prerequisite")
            val final = ui.plan(id)
            assertEquals(2, final.getJSONObject("execution").getInt("admissions")); assertEquals(3, ui.app.tasks.list().length())
            assertEquals(1, computer.posts().size); assertTrue(ui.modelCalls.get() >= 4)
            for (index in 0..1) assertTrue(ui.app.tasks.automationArtifactVerified(final.getJSONArray("steps").getJSONObject(index).getString("taskId")))
            val original = ui.app.tasks.get(source.getString("taskId"), false)
            assertEquals("needs_confirmation", original.getString("status")); assertFalse(original.getJSONObject("evidence").getBoolean("automaticallyVerified"))
            ui.openTask(source.getString("taskId")); assertEquals(source.getString("taskId"), ui.controller.state.requestedTaskId)
        } }
    }

    @Test fun cancellingTheWaitNeverCancelsOrResubmitsTheIndependentOriginalCommand() {
        ContinuousPlanMountedNativeTest.Screen().use { ui -> Computer(ui).use { computer ->
            val source = computer.submit("独立原电脑命令，取消等待也继续运行")
            val id = ui.create("取消等待不等于停止原电脑任务", listOf(ui.stepDraft("等待后核实来源", "reports/source.md", "# Source", remoteWait = source)))
            ui.approve(id, admissions = 1)
            ui.action(id, "cancel")
            assertEquals("cancelled", ui.plan(id).getString("state"))
            assertFalse(ui.app.tasks.get(source.getString("taskId"), false).optBoolean("cancelRequested"))
            assertEquals(listOf("/v1/jobs"), computer.posts())
            computer.release(); until(ui) { ui.app.tasks.automationRemoteWaitObservation(source).optBoolean("allowed") }; ui.alarm()
            assertEquals(1, ui.app.tasks.list().length()); assertEquals(0, ui.modelCalls.get()); assertEquals(listOf("/v1/jobs"), computer.posts())
        } }
    }
}
