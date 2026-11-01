package dev.pocketengine.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.os.Handler
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import dev.pocketengine.EngineApplication
import dev.pocketengine.ui.memory.TaskMemorySearchSheet
import dev.pocketengine.ui.memory.TaskMemoryWorkflowSheet
import java.io.File
import java.time.Duration
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*
import org.robolectric.shadows.ShadowDialog

class TaskMemoryMountedFixtureActivity : ComponentActivity()

/** Current native leaves -> real Controller -> Application -> actual request journal, no supplied proof DTO. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],manifest=Config.NONE,application=EngineApplication::class,qualifiers="w320dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class TaskMemoryMountedNativeTest {
    @Before fun keys() { TaskMemoryNativeWorkflowTest().keys() }
    internal class Screen(val host: TaskMemoryNativeWorkflowTest.Fixture) : AutoCloseable {
        val activityController=Robolectric.buildActivity(TaskMemoryMountedFixtureActivity::class.java).setup().visible()
        val activity=activityController.get()
        var mode by mutableStateOf(0)
        var reference by mutableStateOf(JSONObject())
        var sourceTask by mutableStateOf(host.sourceTask)
        val width=(activity.resources.configuration.screenWidthDp*activity.resources.displayMetrics.density).toInt()
        val height=(activity.resources.configuration.screenHeightDp*activity.resources.displayMetrics.density).toInt()
        private val colors=Class.forName("dev.pocketengine.ui.PocketWorldAppKt").getDeclaredField("AppColors").let{it.isAccessible=true;it.get(null) as ColorScheme}
        init { activity.setContent { MaterialTheme(colorScheme=colors) { when(mode) {
            0 -> TaskMemorySearchSheet(host.controller) { mode=2 }
            1 -> TaskMemoryWorkflowSheet(host.controller,reference) { mode=2 }
            3 -> SourceMemoryControl("task",sourceTask,"This UI prose must never become authoritative source",host.controller.state,host.controller)
            else -> Text("原任务与回答保持在同一应用")
        } } }; advance() }
        fun compose(view:View):View? {
            if(view.javaClass.name=="androidx.compose.ui.platform.AndroidComposeView")return view
            if(view is ViewGroup)for(i in 0 until view.childCount)compose(view.getChildAt(i))?.let{return it};return null
        }
        fun nodes():List<SemanticsNode> {
            val result=mutableListOf<SemanticsNode>();fun collect(node:SemanticsNode){result+=node;node.children.forEach(::collect)}
            WindowInspector.getGlobalWindowViews().filter{it.isAttachedToWindow&&it.visibility==View.VISIBLE}.forEach{root->compose(root)?.let{collect((it.javaClass.getMethod("getSemanticsOwner").invoke(it) as SemanticsOwner).rootSemanticsNode)}};return result
        }
        fun text(node:SemanticsNode)=node.config.getOrNull(SemanticsProperties.Text)?.joinToString(" "){it.text}.orEmpty()
        fun advance() { repeat(8) {
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))
            activity.window.decorView.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY))
            activity.window.decorView.layout(0,0,width,height);Thread.sleep(5)
        }
            repeat(2){
                val windows=mutableListOf(activity.window);ShadowDialog.getLatestDialog()?.takeIf{it.isShowing}?.window?.let{windows+=it}
                windows.forEach{window->
                    compose(window.decorView)?.let{it.javaClass.getMethod("invalidateDescendants").invoke(it)};window.decorView.invalidate()
                    val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);var result=-1
                    PixelCopy.request(window,bitmap,{result=it},Handler(Looper.getMainLooper()));Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals(PixelCopy.SUCCESS,result);bitmap.recycle()
                }
            }
        }
        fun await(label:String){repeat(35){if(nodes().any{text(it).contains(label)})return;advance()};error("Missing $label; ${nodes().map(::text)}")}
        fun click(label:String){val node=nodes().firstOrNull{text(it)==label&&it.config.getOrNull(SemanticsActions.OnClick)?.action!=null}?:error("Missing click $label; ${nodes().map(::text)}");assertTrue(node.config[SemanticsActions.OnClick].action!!.invoke());advance()}
        fun edit(value:String){val node=nodes().first{it.config.getOrNull(SemanticsActions.SetText)?.action!=null};assertTrue(node.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString(value)));advance()}
        fun save(name:String){
            advance();val root=WindowInspector.getGlobalWindowViews().last{it.isAttachedToWindow&&it.visibility==View.VISIBLE&&compose(it)!=null}
            compose(root)?.let{it.javaClass.getMethod("invalidateDescendants").invoke(it)};root.invalidate()
            val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);bitmap.eraseColor(android.graphics.Color.WHITE);root.draw(Canvas(bitmap))
            var ink=0;for(y in 0 until height step 4)for(x in 0 until width step 4)if(android.graphics.Color.red(bitmap.getPixel(x,y))<160)ink++
            assertTrue("Native mounted tree must render visible text/control pixels",ink>100)
            val out=File(System.getProperty("b1.memory.renders"));out.mkdirs();File(out,"$name.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
            val semantics=JSONArray();nodes().forEach{semantics.put(JSONObject().put("text",text(it)).put("bounds",it.boundsInRoot.toString()))}
            File(out,"$name.json").writeText(JSONObject().put("viewportDp","320x800").put("host","actual Controller/Application/TaskStore/Runner/Gateway").put("providedLookup","native operations journal").put("nodes",semantics).toString(2))
        }
        override fun close(){activityController.pause().stop().destroy();Shadows.shadowOf(Looper.getMainLooper()).idle()}
    }
    @Test fun searchOldTaskOpenOriginalThenReviewActualRequestSourceAndCorrectNextRequest() {
        TaskMemoryNativeWorkflowTest.Fixture().use { f ->
            repeat(205){f.app.tasks.beginExternal("memory_history_fixture","inspect",UUID.randomUUID().toString(),JSONObject().put("goal","较新的原任务 $it"))}
            Screen(f).use { ui ->
                ui.edit("早期项目验收");ui.click("搜索任务记录");ui.await("主人曾确认结果");ui.save("01-native-full-task-search")
                ui.click("打开这条原任务");assertEquals(f.sourceTask,f.controller.state.requestedTaskId)
                val first=f.completed(f.send("按项目约定回答"));ui.reference=f.binding(first);ui.mode=1;ui.advance();ui.await("本次提供给模型的记忆来源");ui.save("02-native-provided-reference")
                ui.click("查看本次选择与省略");ui.await("当时提供核心参考 1 条");ui.save("02a-native-selection-budget");ui.click("收起选择与省略说明")
                ui.click("查看这个版本的来源");ui.await("主人核对时的原结果");ui.save("03-native-exact-source")
                ui.click("返回本次来源");ui.click("纠正或遗忘");ui.click("改写这条记忆");ui.edit("项目约定 B1_CORRECTED_REFERENCE_952");ui.click("保存纠正并停用旧版本")
                ui.await("已保存你的纠正并清理本次旧参考");ui.save("04-native-corrected-reference-retired")
                assertFalse(ui.nodes().any{ui.text(it).contains("B1_OLD_REFERENCE_741")})
                f.answer="当前约定已纠正";val second=f.completed(f.send("再核对当前项目约定"));assertFalse(f.requests[1].toString().contains("B1_OLD_REFERENCE_741"));assertTrue(f.requests[1].toString().contains("B1_CORRECTED_REFERENCE_952"))
                ui.mode=2;ui.advance();ui.reference=f.binding(second);ui.mode=1;ui.advance();ui.await("本次提供给模型的记忆来源");ui.click("查看这个版本的来源");ui.await("确认时的结果摘要")
                f.app.dispatchOwnerAction("confirmResult",JSONObject().put("taskId",f.sourceTask).put("completed",false));ui.await("部分来源已失效");ui.save("05-native-source-revoked-live")
                assertFalse(ui.nodes().any{ui.text(it).contains("B1_OLD_REFERENCE_741")});assertFalse(ui.nodes().any{ui.text(it).contains("B1_CORRECTED_REFERENCE_952")})
            }
        }
    }
    @Test fun actualRequestBudgetInspectorShowsOmittedCoreIdentityWithoutOldBodies() {
        TaskMemoryNativeWorkflowTest.Fixture().use { f ->
            repeat(15){f.memory.handle("save",JSONObject().put("scope",TaskMemoryNativeWorkflowTest.scope()).put("record",JSONObject().put("text","稳定主人偏好 $it").put("core",true)))}
            f.memory.handle("save",JSONObject().put("scope",TaskMemoryNativeWorkflowTest.scope()).put("record",JSONObject().put("text","DELTA_TICKET 当前任务的明确约定")))
            val task=f.completed(f.send("DELTA_TICKET"))
            Screen(f).use { ui ->
                ui.reference=f.binding(task);ui.mode=1;ui.advance();ui.await("本次提供给模型的记忆来源")
                ui.click("查看本次选择与省略");ui.await("条数预算已满：1 条");ui.await("任务匹配 1 条");ui.save("06-native-budget-omission")
                assertTrue(ui.nodes().any{ui.text(it).contains("省略版本（仅标识，不保留旧正文）")})
            }
        }
    }
}
