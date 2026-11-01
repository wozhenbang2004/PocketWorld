package dev.pocketengine.ui

import android.app.Application
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.*
import dev.pocketengine.features.skills.NativeSkillExecutionHostTest
import dev.pocketengine.features.plugins.TaskMcpPreparationTest
import java.io.File
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.*
import org.robolectric.shadows.ShadowDialog

class TaskCapabilityFixtureActivity : ComponentActivity()

/** Real new Compose leaf + real Module/TaskStore/canonical HTTP backend, explicit protocol fixture.
 * No EngineApplication/model/device/bubblewrap-positive claim is made by this fixture. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w320dp-h800dp-xxhdpi",application=Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class TaskCapabilityNativeTest {
    private class Ui(val domain: NativeSkillExecutionHostTest.Fixture? = null, val customContent: (@Composable () -> Unit)? = null) : AutoCloseable {
        val controller=Robolectric.buildActivity(TaskCapabilityFixtureActivity::class.java).setup().visible()
        val activity=controller.get()
        val io=Executors.newSingleThreadExecutor()
        var returned=0
        init { activity.setContent { MaterialTheme(colorScheme=lightColorScheme(primary=Ink,onPrimary=Color.White,secondary=Ink,background=Color.White,surface=Color.White,surfaceVariant=Soft,onSurface=Ink,onSurfaceVariant=SecondaryInk,outline=Color(0xffcbd0d5),outlineVariant=Line,error=Danger)) {
            if(customContent!=null)customContent() else { val actual=requireNotNull(domain);TaskCapabilityPage(actual.task.getString("id"),actual.host,{io.execute(it)},
            {actual.host.taskSnapshot(actual.task.getString("id"),setOf("skills_execute"),JSONObject())},
            {operation,id,sha->actual.tasks.readSkillArtifact(actual.task.getString("id"),operation,id,sha)},
            {_,_->error("No unrelated MCP setup in this task")},{returned++}) }
        } };advance() }
        private fun compose(view:View):View? {if(view.javaClass.name=="androidx.compose.ui.platform.AndroidComposeView")return view;if(view is ViewGroup)for(i in 0 until view.childCount)compose(view.getChildAt(i))?.let{return it};return null}
        fun nodes():List<SemanticsNode>{val all=mutableListOf<SemanticsNode>();fun collect(n:SemanticsNode){all+=n;n.children.forEach(::collect)}
            WindowInspector.getGlobalWindowViews().filter{it.isAttachedToWindow&&it.visibility==View.VISIBLE}.forEach{root->compose(root)?.let{collect((it.javaClass.getMethod("getSemanticsOwner").invoke(it) as SemanticsOwner).rootSemanticsNode)}};return all}
        fun text(n:SemanticsNode)=n.config.getOrNull(SemanticsProperties.Text)?.joinToString(" "){it.text}.orEmpty()
        fun advance(){repeat(5){shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(24));for(root in WindowInspector.getGlobalWindowViews().filter{it.isAttachedToWindow}){
            val w=(activity.resources.configuration.screenWidthDp*activity.resources.displayMetrics.density).toInt();val h=(activity.resources.configuration.screenHeightDp*activity.resources.displayMetrics.density).toInt()
            root.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));root.layout(0,0,w,h)
        };Thread.sleep(5)}; repeat(2) { val windows=mutableListOf(activity.window); ShadowDialog.getLatestDialog()?.takeIf{it.isShowing}?.window?.let{windows+=it}; for(window in windows) { compose(window.decorView)?.let{it.javaClass.getMethod("invalidateDescendants").invoke(it)};window.decorView.invalidate();val frame=Bitmap.createBitmap(window.decorView.width,window.decorView.height,Bitmap.Config.ARGB_8888);var copied=-1;PixelCopy.request(window,frame,{copied=it},Handler(Looper.getMainLooper()));shadowOf(Looper.getMainLooper()).idle();assertEquals(PixelCopy.SUCCESS,copied);frame.recycle() } } }
        fun await(label:String){repeat(100){if(nodes().any{text(it).contains(label)})return;advance()};error("Missing $label; ${nodes().map(::text)}")}
        fun click(label:String){val node=nodes().firstOrNull{text(it)==label&&it.config.getOrNull(SemanticsActions.OnClick)?.action!=null}?:error("No click $label; ${nodes().map(::text)}");assertTrue(node.config[SemanticsActions.OnClick].action!!.invoke());advance()}
        fun scrollTo(label:String){repeat(18){if(nodes().any{text(it).contains(label)&&it.boundsInRoot.height>8})return;nodes().lastOrNull{it.config.getOrNull(SemanticsActions.ScrollBy)?.action!=null}?.config?.get(SemanticsActions.ScrollBy)?.action?.invoke(0f,550f);advance()};error("Cannot reveal $label; scroll="+nodes().filter{it.config.getOrNull(SemanticsActions.ScrollBy)?.action!=null}.map{listOf(it.boundsInRoot.toString(),it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.value?.invoke(),it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.maxValue?.invoke())}+"; labels="+nodes().filter{text(it).isNotEmpty()}.map{text(it)+" "+it.boundsInRoot})}
        fun acknowledge(){val n=nodes().first{it.config.getOrNull(SemanticsProperties.ToggleableState)!=null&&it.config.getOrNull(SemanticsActions.OnClick)?.action!=null};assertTrue(n.config[SemanticsActions.OnClick].action!!.invoke());advance()}
        fun save(name:String){advance();val root=WindowInspector.getGlobalWindowViews().last{it.isAttachedToWindow&&it.visibility==View.VISIBLE&&compose(it)!=null};compose(root)?.let{it.javaClass.getMethod("invalidateDescendants").invoke(it)}
            val bitmap=Bitmap.createBitmap(root.width,root.height,Bitmap.Config.ARGB_8888);bitmap.eraseColor(android.graphics.Color.WHITE);root.draw(Canvas(bitmap))
            var ink=0;for(y in 0 until bitmap.height step 3)for(x in 0 until bitmap.width step 3)if(android.graphics.Color.red(bitmap.getPixel(x,y))<180)ink++;assertTrue("Actual native Canvas has text/control pixels",ink>100)
            val out=File(requireNotNull(System.getProperty("b6.ecosystem.renders")));out.mkdirs();File(out,"$name.png").outputStream().use{assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))};bitmap.recycle()
            File(out,"$name-semantics.json").writeText(JSONObject().put("leaf","TaskCapabilityPage/SkillExecutionReviewPage").put("viewport","320x800dp").put("nodes",org.json.JSONArray(nodes().map{JSONObject().put("text",text(it)).put("bounds",it.boundsInRoot.toString()).put("disabled",it.config.contains(SemanticsProperties.Disabled))})).toString(2))
        }
        override fun close(){controller.pause().stop().destroy();shadowOf(Looper.getMainLooper()).idle();io.shutdownNow()}
    }
    @Test fun taskOpensExactNativeReviewApprovesOnceAndRetainsOriginalArtifact() {
        NativeSkillExecutionHostTest.Fixture("字面参数 中文 空格").use { domain ->
            val running=domain.execute();domain.review()
            Ui(domain).use { ui ->
                ui.await("当前任务正在等你审阅脚本");assertEquals(0,domain.posts.get());ui.save("01-task-preparation-320dp")
                val raw=domain.tasks.get(domain.task.getString("id"),false)
                assertTrue(unresolvedOperations(UiTask(raw.getString("id"),raw.getString("goal"),raw.getString("status"),raw.optString("summary"),raw.getString("executionMode"),raw.getLong("updatedAt"),raw)).isEmpty())
                ui.click("审阅本次执行");ui.await("审阅本次脚本执行");assertEquals(0,domain.posts.get())
                ui.scrollTo("读取当前版本的脚本内容");ui.click("读取当前版本的脚本内容");ui.await("NEVER EXECUTE USER SKILL")
                ui.scrollTo("批准本次执行");ui.acknowledge();ui.save("02-exact-script-review-320dp");ui.click("批准本次执行")
                val result=running.get(12,TimeUnit.SECONDS);assertEquals("completed",result.getString("status"));assertEquals(1,domain.posts.get())
                ui.await("原任务脚本回执");ui.scrollTo("核对原回执");ui.click("核对原回执");ui.await("已核对原操作，没有重新提交");assertEquals(1,domain.posts.get());ui.save("03-original-receipt-320dp")
                assertEquals(1,domain.tasks.readSkillExecution(domain.task.getString("id"),domain.input.getString("_hostOperationId")).getJSONObject("receipt").getJSONArray("artifacts").length())
                ui.click("返回原任务");assertEquals(1,ui.returned)
            }
        }
    }
    @Test fun nativeWindowBackgroundRejectsApprovalAndOldReviewNeverBecomesAReplayedRequest() {
        NativeSkillExecutionHostTest.Fixture().use { domain ->
            val running=domain.execute();val review=domain.review();val window=domain.host.openOwnerWindow();window.setForeground(true);window.setForeground(false)
            assertThrows(SecurityException::class.java){domain.host.decide(window,review,review.plan.digest,true)};assertEquals(0,domain.posts.get())
            domain.host.cancelReview(review);assertEquals("rejected",running.get(5,TimeUnit.SECONDS).getString("status"));window.close()
            Ui(domain).use { ui ->ui.await("原任务脚本回执");assertFalse(ui.nodes().any{ui.text(it)=="审阅本次执行"});ui.scrollTo("核对原回执");ui.click("核对原回执");ui.await("已核对原操作，没有重新提交");assertEquals(0,domain.posts.get());ui.save("04-rejected-original-operation-320dp") }
        }
    }
    @Test fun completedScriptWithUnavailableFilesOffersOriginalArtifactRetrievalWithoutReexecution() {
        NativeSkillExecutionHostTest.Fixture().use { domain ->
            domain.failArtifactReads=true
            val running=domain.execute();val review=domain.review();val window=domain.host.openOwnerWindow();window.setForeground(true);domain.host.decide(window,review,review.plan.digest,true);window.close()
            assertEquals("unavailable",running.get(12,TimeUnit.SECONDS).getString("artifactStatus"));assertEquals(1,domain.posts.get())
            Ui(domain).use { ui ->
                ui.await("原文件成果尚未取回");ui.scrollTo("取回原文件成果");assertEquals(1,domain.posts.get())
                domain.failArtifactReads=false;ui.click("取回原文件成果");ui.await("已按原回执取回文件成果，没有重新提交脚本")
                assertEquals(1,domain.posts.get());assertEquals(1,domain.tasks.readSkillExecution(domain.task.getString("id"),domain.input.getString("_hostOperationId")).getJSONObject("receipt").getJSONArray("artifacts").length())
                ui.scrollTo("导出原成果");ui.save("06-original-artifact-retrieval-320dp")
            }
        }
    }
    @Test fun requiredMcpReusesCurrentInstallAndOnlyGrantsTheDisplayedTool() {
        TaskMcpPreparationTest.UiDomain().use { domain ->
            val snapshot=mutableStateOf(domain.snapshot());val responses=mutableStateOf<Map<String,JSONObject>>(emptyMap());val pending=mutableStateOf<Set<String>>(emptySet());val io=Executors.newSingleThreadExecutor()
            Ui(customContent={McpInstallPreviewPage(snapshot.value,responses.value,pending.value,false,null,{command,payload->
                val request=java.util.UUID.randomUUID().toString();pending.value=pending.value+command
                io.execute {
                    val result=runCatching{domain.owner.mcp(command,payload)};val actual=domain.snapshot()
                    Handler(Looper.getMainLooper()).post {val envelope=JSONObject().put("requestId",request).put("ok",result.isSuccess);result.fold({envelope.put("result",it)},{envelope.put("error",it.message)});responses.value=responses.value+(command to envelope);snapshot.value=actual;pending.value=pending.value-command}
                };request
            },{error("No configuration import is needed for an existing service")},{},"remote","lookup")}).use { ui ->
                ui.await("复用已安装的服务");assertFalse(ui.nodes().any{ui.text(it)=="服务地址"});assertFalse(ui.nodes().any{ui.text(it)=="不相关服务"})
                ui.click("lookup · 审阅并允许这个工具");ui.await("允许这个工具？");ui.click("允许这个工具");ui.await("lookup · 已允许工具，可撤销");assertEquals(0,domain.callCount())
                ui.click("启用这个安装…");ui.click("确认启用");ui.await("停用这个安装…");assertEquals(1,domain.plugins.toolRegistry().length());assertEquals("remote",domain.plugins.toolRegistry().getJSONObject(0).getString("pluginId"));assertEquals(0,domain.callCount());ui.save("05-required-mcp-reuse-320dp")
            };io.shutdownNow()
        }
    }
}
