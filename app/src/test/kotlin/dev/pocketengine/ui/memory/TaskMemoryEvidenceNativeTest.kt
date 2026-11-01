package dev.pocketengine.ui.memory

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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import java.io.File
import java.time.Duration
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.*
import org.robolectric.shadows.ShadowDialog

class TaskMemoryFixtureActivity : ComponentActivity()

/** Actual Compose leaf + actual B1 TaskMemoryUse/MemoryModule; controlled native journal, no root bridge/model/device claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w320dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class TaskMemoryEvidenceNativeTest {
    private class Fixture(populate:Boolean=true,text:String="原任务确认的地点是杭州。",providedDomain:TaskMemoryNativeFixture?=null) : AutoCloseable {
        val domain=providedDomain ?: TaskMemoryNativeFixture(RuntimeEnvironment.getApplication(),populate,text)
        val snapshot=mutableStateOf<JSONObject?>(domain.receipt())
        val responses=mutableStateOf<Map<String,JSONObject>>(emptyMap())
        val pending=mutableStateOf<Set<String>>(emptySet())
        val controller=Robolectric.buildActivity(TaskMemoryFixtureActivity::class.java).setup().visible()
        val activity=controller.get()
        val width=(activity.resources.configuration.screenWidthDp*activity.resources.displayMetrics.density).toInt()
        val height=(activity.resources.configuration.screenHeightDp*activity.resources.displayMetrics.density).toInt()
        var dismissed=0
        init {
            activity.setContent {
                MaterialTheme(colorScheme=lightColorScheme(primary=Color(0xff222428),onPrimary=Color.White,
                    surface=Color.White,background=Color.White,onSurface=Color(0xff222428),onSurfaceVariant=Color(0xff72777f),error=Color(0xffa3443d))) {
                    TaskMemoryEvidenceSheet(domain.task.taskId,domain.task.runId,domain.reference.requestId,domain.reference.answerId,snapshot.value,
                        pending.value,responses.value,onRequest={command,payload->
                            val id=UUID.randomUUID().toString();pending.value=pending.value+command
                            Handler(Looper.getMainLooper()).post {
                                val result=try{JSONObject().put("requestId",id).put("ok",true).put("result",domain.request(command,payload))}
                                catch(failure:Exception){JSONObject().put("requestId",id).put("ok",false).put("error",failure.message)}
                                responses.value=responses.value+(command to result);pending.value=pending.value-command
                            };id
                        },onOpenTask={domain.openedTasks+=it},onDismiss={dismissed++})
                }
            }
            advance()
        }
        fun compose(view:View):View? {
            if(view.javaClass.name=="androidx.compose.ui.platform.AndroidComposeView")return view
            if(view is ViewGroup)for(i in 0 until view.childCount)compose(view.getChildAt(i))?.let{return it}
            return null
        }
        fun nodes():List<SemanticsNode>{
            val all=mutableListOf<SemanticsNode>()
            fun collect(node:SemanticsNode){all+=node;node.children.forEach(::collect)}
            WindowInspector.getGlobalWindowViews().filter{it.isAttachedToWindow&&it.visibility==View.VISIBLE}.forEach{root->
                compose(root)?.let{collect((it.javaClass.getMethod("getSemanticsOwner").invoke(it) as SemanticsOwner).rootSemanticsNode)}
            };return all
        }
        fun text(node:SemanticsNode)=node.config.getOrNull(SemanticsProperties.Text)?.joinToString(" "){it.text}.orEmpty()
        fun advance(){repeat(14){
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))
            activity.window.decorView.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY))
            activity.window.decorView.layout(0,0,width,height);Thread.sleep(3)
        }
            repeat(2) {
                val windows=mutableListOf(activity.window)
                ShadowDialog.getLatestDialog()?.takeIf{it.isShowing}?.window?.let{windows+=it}
                for(window in windows){
                    compose(window.decorView)?.let{it.javaClass.getMethod("invalidateDescendants").invoke(it)}
                    window.decorView.invalidate();val frame=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
                    var copied=-1;PixelCopy.request(window,frame,{copied=it},Handler(Looper.getMainLooper()));shadowOf(Looper.getMainLooper()).idle()
                    assertEquals(PixelCopy.SUCCESS,copied);frame.recycle()
                }
            }
        }
        fun await(label:String){repeat(30){if(nodes().any{text(it).contains(label)})return;advance()};error("Missing $label; nodes=${nodes().map(::text)}")}
        fun click(label:String){val node=nodes().firstOrNull{text(it)==label&&it.config.getOrNull(SemanticsActions.OnClick)?.action!=null}
            ?:error("No click $label; nodes=${nodes().map(::text)}")
            assertTrue(node.config[SemanticsActions.OnClick].action!!.invoke());advance()}
        fun edit(value:String){val node=nodes().first{it.config.getOrNull(SemanticsActions.SetText)?.action!=null}
            assertTrue(node.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString(value)));advance()}
        fun scrollTo(label:String){repeat(16){
            if(nodes().any{text(it).contains(label)&&it.boundsInRoot.height>5})return
            nodes().lastOrNull{it.config.getOrNull(SemanticsActions.ScrollBy)?.action!=null}?.config?.get(SemanticsActions.ScrollBy)?.action?.invoke(0f,450f)
            advance()
        };error("Could not reveal $label")}
        fun save(name:String){
            advance();val window=ShadowDialog.getLatestDialog()?.takeIf{it.isShowing}?.window?:activity.window
            compose(window.decorView)?.let{it.javaClass.getMethod("invalidateDescendants").invoke(it)};window.decorView.invalidate()
            val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);var result=-1
            PixelCopy.request(window,bitmap,{result=it},Handler(Looper.getMainLooper()));shadowOf(Looper.getMainLooper()).idle()
            assertEquals(PixelCopy.SUCCESS,result)
            var dark=0;for(y in 0 until height step 3)for(x in 0 until width step 3){val p=bitmap.getPixel(x,y);if(android.graphics.Color.alpha(p)>200&&android.graphics.Color.red(p)<180)dark++}
            assertTrue("Native rendering contains opaque text/control pixels",dark>100)
            val out=File(System.getProperty("b6.taskMemory.renders"));out.mkdirs()
            File(out,"$name.png").outputStream().use{assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))};bitmap.recycle()
            val canvas=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);canvas.eraseColor(android.graphics.Color.WHITE)
            val attachedRoot=WindowInspector.getGlobalWindowViews().last{it.isAttachedToWindow&&it.visibility==View.VISIBLE&&compose(it)!=null}
            compose(attachedRoot)?.let{it.javaClass.getMethod("invalidateDescendants").invoke(it)}
            attachedRoot.invalidate();attachedRoot.draw(Canvas(canvas))
            val header=nodes().last{ text(it)=="返回回答" }.boundsInRoot
            var headerInk=0
            for(y in header.top.toInt().coerceAtLeast(0) until header.bottom.toInt().coerceAtMost(height))
                for(x in header.left.toInt().coerceAtLeast(0) until header.right.toInt().coerceAtMost(width))
                    if(android.graphics.Color.alpha(canvas.getPixel(x,y))>200&&android.graphics.Color.red(canvas.getPixel(x,y))<120)headerInk++
            assertTrue("Current attached native tree renders the fixed return control",headerInk>50)
            File(out,"$name-native-canvas.png").outputStream().use{assertTrue(canvas.compress(Bitmap.CompressFormat.PNG,100,it))};canvas.recycle()
            val rows=JSONArray();nodes().forEach{rows.put(JSONObject().put("text",text(it)).put("bounds",it.boundsInRoot.toString())
                .put("disabled",it.config.contains(SemanticsProperties.Disabled)).put("editable",it.config.getOrNull(SemanticsActions.SetText)?.action!=null))}
            File(out,"$name-semantics.json").writeText(JSONObject().put("productionLeaf","TaskMemoryEvidenceSheet").put("viewportDp","320x800")
                .put("realDomain","B1 TaskMemoryUse / MemoryModule").put("rootJournal","controlled fixture only").put("nodes",rows).toString(2))
        }
        override fun close(){controller.pause().stop().destroy();shadowOf(Looper.getMainLooper()).idle();domain.close()}
    }
    @Test fun actualReceiptOpensExactSourceAndOriginalTaskThenReturns(){
        Fixture().use{f->
            f.await("本次提供给模型的记忆来源");f.save("01-provided-sources-320dp")
            assertTrue(f.domain.commands.isEmpty())
            f.click("打开原任务");assertEquals(listOf("task-original"),f.domain.openedTasks)
            f.click("查看这个版本的来源");f.await("原任务确认的地点证据");f.save("02-exact-source-320dp")
            assertTrue(f.nodes().any{f.text(it).contains("由用户确认的地点资料")})
            f.click("返回本次来源");f.click("返回回答");assertEquals(1,f.dismissed)
        }
    }
    @Test fun correctionUsesRealRevisionAndShowsUnconfirmedContextCleanup(){
        Fixture().use{f->
            f.click("纠正或遗忘");f.click("改写这条记忆");f.edit("目前已确认的地点是苏州。")
            f.save("03-correction-review-320dp");f.click("保存纠正并停用旧版本")
            f.await("旧上下文清理仍待核对");f.save("04-corrected-revocation-pending-320dp")
            val saved=f.domain.module.handle("get",JSONObject().put("scope",f.domain.scope).put("id",f.domain.original.getString("id"))).getJSONObject("record")
            assertEquals("目前已确认的地点是苏州。",saved.getString("text"));assertEquals(2L,saved.getLong("revision"))
            assertFalse(f.nodes().any{f.text(it).contains("后续不会再召回")})
            assertFalse(f.nodes().any{f.text(it)=="原任务确认的地点是杭州。"})
        }
    }
    @Test fun exactReplacementForgetsOldVersionAndNativeReceiptReportsSuperseded(){
        Fixture().use{f->
            val kept=f.domain.keepCorrectFact("已另行保存的正确地点是苏州。")
            f.click("纠正或遗忘");f.click("用已保存的正确记忆替代");f.await("选择版本 1")
            val option=f.nodes().first{f.text(it).contains("已另行保存的正确地点是苏州。")&&it.config.getOrNull(SemanticsActions.OnClick)?.action!=null}
            assertTrue(option.config[SemanticsActions.OnClick].action!!.invoke());f.advance();f.click("确认用所选记录替代")
            f.await("已由明确保存的事实替代");f.save("05-explicit-replacement-320dp")
            val records=f.domain.module.handle("list",JSONObject().put("scope",f.domain.scope)).getJSONArray("records")
            assertEquals(1,records.length());assertEquals(kept.getString("id"),records.getJSONObject(0).getString("id"))
            assertTrue(f.domain.revocationRefs.length()>0)
        }
    }
    @Test fun failedReviewRetainsDraftAndRestartWithoutJournalNeverSearchesAgain(){
        val longText="原任务确认的地点是杭州，这里保留足够长的中文用于窄屏阅读。".repeat(20)
        var reopened:TaskMemoryNativeFixture?=null
        Fixture(text=longText).use{f->
            f.save("06-long-before-review-320dp")
            f.scrollTo("纠正或遗忘");f.click("纠正或遗忘");f.click("改写这条记忆");f.edit("用户仍未提交成功的纠正草稿")
            f.domain.failCorrection=true;f.click("保存纠正并停用旧版本");f.await("此版本已变化")
            assertTrue(f.nodes().any{it.config.getOrNull(SemanticsProperties.EditableText)?.text=="用户仍未提交成功的纠正草稿"})
            f.save("06-failed-review-retains-draft-320dp")
            f.click("重新核对这个版本")
            assertTrue(f.nodes().any{it.config.getOrNull(SemanticsProperties.EditableText)?.text=="用户仍未提交成功的纠正草稿"})
            reopened=f.domain
        }
        reopened!!.reopenWithoutOriginalJournal()
        Fixture(providedDomain=reopened!!).use{f->
            f.await("本次来源回执不可用");f.save("07-restart-no-original-receipt-320dp")
            assertFalse(f.nodes().any{f.text(it).contains("原任务确认的地点是杭州")})
            assertFalse(f.domain.commands.any{it.contains("search",true)||it.contains("recall",true)})
        }
    }
    @Test fun actualTaskSearchShowsFailedRecordWithoutPromotingItToFormalMemory(){
        Fixture().use{f->
            val before=f.domain.module.handle("list",JSONObject().put("scope",f.domain.scope)).getJSONArray("records").length()
            f.click("查找原任务记录");f.edit("地点");f.click("搜索任务记录")
            f.await("任务失败，仍可核对记录");f.save("08-real-task-record-search-320dp")
            assertTrue(f.nodes().any{f.text(it).contains("最近200条")})
            assertTrue(f.nodes().any{f.text(it).contains("不是正式记忆")})
            f.click("打开这条原任务");assertEquals(f.domain.searchedTaskId,f.domain.openedTasks.last())
            assertEquals(before,f.domain.module.handle("list",JSONObject().put("scope",f.domain.scope)).getJSONArray("records").length())
        }
    }
    @Test fun emptyAndRevokedReceiptsKeepTheirActualMeaning(){
        Fixture(populate=false).use{f->
            f.await("此回执没有提供记忆条目。");f.save("09-empty-provided-receipt-320dp")
            assertFalse(f.nodes().any{f.text(it)=="纠正或遗忘"})
        }
    }
    @Test fun actualForgetRemovesFormalMemoryAndLeavesContextCleanupUnconfirmed(){
        Fixture().use{f->
            f.click("纠正或遗忘");f.click("遗忘这条及衍生内容");f.save("10-forget-exact-version-review-320dp")
            f.click("确认遗忘并停用");f.await("记忆已遗忘或移除");f.save("11-forgotten-receipt-320dp")
            assertEquals(0,f.domain.module.handle("list",JSONObject().put("scope",f.domain.scope)).getJSONArray("records").length())
            assertTrue(f.domain.revocationRefs.length()>0)
            assertTrue(f.nodes().any{f.text(it).contains("旧上下文清理仍待核对")})
        }
    }
    @Test fun nativeRevokedProofHidesOldBodyAndDoesNotOfferOldVersionMutation(){
        Fixture().use{f->
            f.snapshot.value=f.domain.revokeProvided();f.advance();f.await("本次参考已撤销")
            assertFalse(f.nodes().any{f.text(it)=="原任务确认的地点是杭州。"})
            assertFalse(f.nodes().any{f.text(it)=="纠正或遗忘"});f.save("12-revoked-native-receipt-320dp")
        }
    }
}
