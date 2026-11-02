package dev.pocketengine.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import dev.pocketengine.EngineApplication
import dev.pocketengine.features.HostAutomationCoordinator
import dev.pocketengine.testutil.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowChoreographer

class ContinuousPlanFixtureActivity : ComponentActivity()

/** Current native editor and controls -> real Controller/Application/Book; no supplied plan DTO. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],manifest=Config.NONE,application=EngineApplication::class,qualifiers="w360dp-h820dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class ContinuousPlanMountedNativeTest {
    @Before fun manualFrames(){ShadowChoreographer.setPaused(true);ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))}
    internal class Screen(
        files: List<Pair<String, String>> = listOf("reports/source.md" to "# Source\nFixture source file prepared in the actual workspace.\n", "reports/delivery.md" to "# Delivery\nFixture delivery file prepared in the actual workspace.\n"),
        failPaths: Set<String> = emptySet(),
        private val holds: Map<String, CountDownLatch> = emptyMap()
    ) : AutoCloseable {
        val app=RuntimeEnvironment.getApplication() as EngineApplication
        val controller=PocketWorldController(ComposeActivity(),app)
        val mounted=Robolectric.buildActivity(ContinuousPlanFixtureActivity::class.java).setup().visible()
        val activity=mounted.get()
        val width=(activity.resources.configuration.screenWidthDp*activity.resources.displayMetrics.density).toInt()
        val height=(activity.resources.configuration.screenHeightDp*activity.resources.displayMetrics.density).toInt()
        val modelCalls=AtomicInteger()
        val requestedPaths=CopyOnWriteArrayList<String>()
        private val model=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        private val evidence=File(System.getProperty("b2.plan.renders")).apply{mkdirs()}
        private fun trace(value:String){File(evidence,"ui-trace.log").appendText(value+"\n")}
        private var observedError=""
        private val watchdog=Thread {try{Thread.sleep(45_000);File(evidence,"ui-stack.txt").writeText(Thread.getAllStackTraces().entries.filter{it.key.name.contains("SDK")||it.key.name=="main"}.joinToString("\n\n"){it.key.name+" "+it.key.state+"\n"+it.value.take(35).joinToString("\n")})}catch(_:InterruptedException){}}.apply{isDaemon=true;start()}
        init {
            val workspace=app.features.module("workspace");val project=workspace.handle("create",JSONObject().put("name","计划验收项目")).getString("id")
            files.forEach{(path,content)->workspace.handle("write",JSONObject().put("projectId",project).put("path",path).put("content",content).put("expectedVersion",0))}
            model.createContext("/v1/chat/completions"){exchange->try{
                modelCalls.incrementAndGet();val request=JSONObject(String(exchange.requestBody.readAllBytes(),StandardCharsets.UTF_8));val history=request.getJSONArray("messages");var frozen=false;var path=files.first().first
                for(i in 0 until history.length()){val row=history.getJSONObject(i);files.firstOrNull{row.optString("content").contains("写入与冻结仅限 "+it.first)}?.let{path=it.first};if(row.optString("role")=="tool"&&row.optString("content").contains("task_workspace"))frozen=true};requestedPaths+=path
                holds[path]?.let{assertTrue("controlled original model reply was never released",it.await(40,TimeUnit.SECONDS))}
                if(path in failPaths){val response="{\"error\":{\"message\":\"controlled native dependency task failure\"}}".toByteArray(StandardCharsets.UTF_8);exchange.sendResponseHeaders(500,response.size.toLong());exchange.responseBody.write(response);return@createContext}
                val message=JSONObject().put("role","assistant").put("content",if(frozen)"指定文件已冻结，等待原生核验。" else "")
                if(!frozen)message.put("tool_calls",JSONArray().put(JSONObject().put("id","native-freeze-${modelCalls.get()}").put("type","function").put("function",JSONObject().put("name","workspace_create_artifact").put("arguments",JSONObject().put("projectId",project).put("path",path).put("version",0).toString()))))
                val response=JSONObject().put("choices",JSONArray().put(JSONObject().put("message",message).put("finish_reason",if(frozen) "stop" else "tool_calls"))).toString().toByteArray(StandardCharsets.UTF_8);exchange.responseHeaders.add("Content-Type","application/json");exchange.sendResponseHeaders(200,response.size.toLong());exchange.responseBody.write(response)
            }finally{exchange.close()}};model.start()
            app.getSharedPreferences("private_settings",0).edit().putString("baseUrl","http://127.0.0.1:${model.address.port}/v1").putString("model","native-ui-controlled-model").putString("provider","openai_chat").putInt("maxSteps",6).putInt("maxOutputTokens",512).putBoolean("sendScreenshots",false).commit()
            controller.attach()
            val colors=Class.forName("dev.pocketengine.ui.PocketWorldAppKt").getDeclaredField("AppColors").let{it.isAccessible=true;it.get(null) as ColorScheme}
            activity.setContent { MaterialTheme(colorScheme=colors) { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp)) { NativePlans(controller.state,controller) } } }
            advance()
        }
        fun compose(view:View):View? {if(view.javaClass.name=="androidx.compose.ui.platform.AndroidComposeView")return view;if(view is ViewGroup)for(i in 0 until view.childCount)compose(view.getChildAt(i))?.let{return it};return null}
        fun nodes():List<SemanticsNode> {val out=mutableListOf<SemanticsNode>();fun collect(n:SemanticsNode){out+=n;n.children.forEach(::collect)};WindowInspector.getGlobalWindowViews().filter{it.isAttachedToWindow&&it.visibility==View.VISIBLE}.forEach{root->compose(root)?.let{collect((it.javaClass.getMethod("getSemanticsOwner").invoke(it) as SemanticsOwner).rootSemanticsNode)}};return out}
        fun text(n:SemanticsNode)=n.config.getOrNull(SemanticsProperties.Text)?.joinToString(" "){it.text}.orEmpty()
        fun advance(){repeat(6){Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32));val error=controller.state.moduleErrors.toString();if(error!=observedError){trace("errors "+error);observedError=error};activity.window.decorView.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));activity.window.decorView.layout(0,0,width,height);Thread.sleep(6)};val windows=mutableListOf(activity.window);ShadowDialog.getShownDialogs().filter{it.isShowing}.mapNotNull{it.window}.forEach{windows+=it};windows.forEach{window->compose(window.decorView)?.javaClass?.getMethod("invalidateDescendants")?.invoke(compose(window.decorView));val image=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);var result=-1;PixelCopy.request(window,image,{result=it},Handler(Looper.getMainLooper()));Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals(PixelCopy.SUCCESS,result);image.recycle()}}
        fun await(label:String){repeat(35){if(nodes().any{text(it).contains(label)})return;advance()};error("Missing $label: ${nodes().map(::text)} errors=${controller.state.moduleErrors}")}
        fun click(label:String,last:Boolean=false,at:Int?=null){trace("click "+label);val candidates=nodes().filter{text(it)==label&&it.config.getOrNull(SemanticsActions.OnClick)?.action!=null};val node=if(at!=null)candidates.getOrNull(at)else if(last)candidates.lastOrNull()else candidates.firstOrNull();checkNotNull(node){"Missing click $label: ${nodes().map(::text)}"};assertTrue(node.config[SemanticsActions.OnClick].action!!.invoke());advance();trace("clicked "+label)}
        fun edit(label:String,value:String,last:Boolean=false){trace("edit "+label);val candidates=nodes().filter{text(it).contains(label)&&it.config.getOrNull(SemanticsActions.SetText)?.action!=null};val n=if(last)candidates.lastOrNull()else candidates.firstOrNull();checkNotNull(n){"Missing field $label: ${nodes().map(::text)}"};assertTrue(n.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString(value)));advance();trace("edited "+label)}
        fun scroll(end:Boolean){nodes().filter{it.config.getOrNull(SemanticsActions.ScrollBy)?.action!=null}.forEach{it.config[SemanticsActions.ScrollBy].action!!.invoke(0f,if(end)100000f else -100000f)};advance()}
        fun plan():JSONObject=app.features.module("automation").handle("listPlans",JSONObject().put("scope",JSONObject().put("ownerId","owner").put("spaceId","private"))).getJSONArray("plans").getJSONObject(0)
        fun alarm(){val field=EngineApplication::class.java.getDeclaredField("automationHost");field.isAccessible=true;(field.get(app) as HostAutomationCoordinator).onAutomationDue(System.currentTimeMillis(),4)}
        fun save(name:String){advance();val dialog=ShadowDialog.getShownDialogs().lastOrNull{it.isShowing};val root=dialog?.window?.decorView?:activity.window.decorView;val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);bitmap.eraseColor(android.graphics.Color.WHITE);root.draw(Canvas(bitmap));var ink=0;for(y in 0 until height step 5)for(x in 0 until width step 5)if(android.graphics.Color.red(bitmap.getPixel(x,y))<160)ink++;assertTrue("Actual Compose pixels missing",ink>100);val dir=File(System.getProperty("b2.plan.renders"));dir.mkdirs();File(dir,"$name.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle();val rows=JSONArray();fun collect(n:SemanticsNode){rows.put(JSONObject().put("text",text(n)).put("bounds",n.boundsInRoot.toString()));n.children.forEach(::collect)};compose(root)?.let{collect((it.javaClass.getMethod("getSemanticsOwner").invoke(it) as SemanticsOwner).rootSemanticsNode)};File(dir,"$name.json").writeText(JSONObject().put("viewportDp","360x820").put("authority","actual Controller/Application/AutomationBook/TaskStore").put("selectedWindow",if(dialog==null)"activity"else"showing_dialog").put("modelCalls",modelCalls.get()).put("nodes",rows).toString(2))}
        override fun close(){holds.values.forEach{it.countDown()};watchdog.interrupt();controller.detach();mounted.pause().stop().destroy();Shadows.shadowOf(Looper.getMainLooper()).idle();model.stop(0);app.features.close()}
    }
    @Test fun createReviewPauseEditResumeThenCompleteTheSameGoalWithRealRunnerFileResults(){
        Screen().use { ui ->
            ui.await("建立一个持续目标");ui.save("01-empty-native-goals");ui.click("建立一个持续目标");ui.edit("最终要得到什么结果","每周项目整理：核实源文件，再归档交付文件");ui.edit("最早几分钟后开始","0");ui.edit("这一步的可交付目标","核实源文件并冻结为私人成果");ui.click("使用的能力 · 手机应用");ui.click("所选项目的文件");ui.click("项目 · 选择已有项目");ui.click("计划验收项目");ui.edit("目标文件","reports/source.md");ui.edit("用于机器核验的完整首行","# Source")
            ui.click("添加后继步骤");ui.edit("这一步的可交付目标","归档交付文件并保留真实来源",true);ui.click("使用的能力 · 手机应用",true);ui.click("所选项目的文件");ui.click("项目 · 选择已有项目",true);ui.click("计划验收项目");ui.edit("目标文件","reports/delivery.md",true);ui.edit("用于机器核验的完整首行","# Delivery",true);ui.scroll(false);ui.save("02-native-goal-definition")
            ui.click("保存并审阅范围");ui.await("审阅这个目标的执行范围");ui.save("03-native-whole-goal-review");ui.click("同意将本目标需要的项目文件，或手机界面与可见文字，发送给我配置的云端模型。");ui.click("运行条件、窗口与预算");ui.click("需要系统已验证的网络");ui.click("收起运行设置");ui.click("按时间、依赖与条件自动推进");ui.click("保存授权，等我开始");ui.await("等你开始");ui.save("04-reviewed-goal-awaits-owner-start")
            val originalDue=ui.plan().getJSONArray("steps").getJSONObject(0).getLong("dueAt")
            assertEquals(0,ui.app.tasks.list().length());assertEquals(0,ui.modelCalls.get());ui.click("暂停");ui.await("计划已暂停");ui.click("修改这一步");ui.await("修改未执行步骤");ui.edit("这一步的可交付目标","先核实来源，再冻结源文件的修订结果");ui.click("保存修改，重新审阅");ui.await("修改后待审阅");assertEquals(originalDue,ui.plan().getJSONArray("steps").getJSONObject(0).getLong("dueAt"));ui.save("05-paused-edit-awaits-review");ui.click("审阅修改后的目标");ui.await("审阅这个目标的执行范围");ui.click("保存授权，等我开始");ui.await("继续同一目标");ui.click("继续同一目标");ui.await("等你开始");assertEquals(0,ui.app.tasks.list().length());ui.click("开始目标");ui.await("已按文件标准核验");ui.alarm();ui.await("目标已按标准完成");ui.save("06-real-original-task-results-complete-goal")
            assertEquals(2,ui.app.tasks.list().length());assertTrue(ui.modelCalls.get() in 4..12);val first=ui.plan().getJSONArray("steps").getJSONObject(0).getString("taskId");for(index in 0 until 2){val id=ui.plan().getJSONArray("steps").getJSONObject(index).getString("taskId");assertTrue(ui.app.tasks.automationArtifactVerified(id));val task=ui.app.tasks.get(id,false);assertEquals(1,task.getJSONArray("operations").length());val events=task.getJSONArray("events");for(at in 0 until events.length())assertNotEquals("user_verification",events.getJSONObject(at).optString("type"))};ui.click("打开原任务与成果");assertEquals(first,ui.controller.state.requestedTaskId);assertTrue(ui.controller.state.moduleErrors.isEmpty())
        }
    }
}
