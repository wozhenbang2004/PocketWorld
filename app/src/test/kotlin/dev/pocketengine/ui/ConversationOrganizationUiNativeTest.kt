package dev.pocketengine.ui

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import androidx.compose.runtime.*
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import dev.pocketengine.EngineApplication
import dev.pocketengine.features.conversation.ConversationHistory
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.*

/** Existing history/menu/name dialog -> actual Controller/Workbench -> original SQLite; not visual/device QA. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35], manifest=Config.NONE, application=EngineApplication::class, qualifiers="w400dp-h1000dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE) @SQLiteMode(SQLiteMode.Mode.NATIVE) @LooperMode(LooperMode.Mode.PAUSED)
class ConversationOrganizationUiNativeTest {
    private class Rig : AutoCloseable {
        val app=RuntimeEnvironment.getApplication() as EngineApplication
        private val owner: org.robolectric.android.controller.ActivityController<ComposeActivity>
        val controller: PocketWorldController
        var renaming by mutableStateOf<ConversationHistory.Item?>(null)
        var busy by mutableStateOf(false)
        var error by mutableStateOf<String?>(null)
        init {
            org.robolectric.shadows.ShadowChoreographer.setPaused(true)
            org.robolectric.shadows.ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
            owner=Robolectric.buildActivity(ComposeActivity::class.java).setup()
            controller=ComposeActivity::class.java.getDeclaredField("controller").apply { isAccessible=true }.get(owner.get()) as PocketWorldController
            val view=ComposeView(owner.get())
            view.setContent {
                ConversationHistoryBrowser(controller.state.conversations.map(controller::conversationOrganizationItem),null,false,{}, {},
                    onRename={renaming=it;error=null},onPin={item->controller.organizeConversation(item.id,item.organizationRevision,null,item.pinOrder<=0){_,failure->error=failure}},
                    managementBusy=controller.state.moduleBusy.any { it.startsWith("assistant.organization:") })
                renaming?.let { item -> EditableNameDialog("修改对话名称","对话名称",item.title,{if(!busy)renaming=null},{title->busy=true;controller.organizeConversation(item.id,item.organizationRevision,title,null){_,failure->busy=false;error=failure;if(failure==null)renaming=null}},initial=item.title,confirmLabel="保存名称",busy=busy,error=error,maxChars=120) }
            }
            owner.get().setContentView(view)
        }
        fun seed(title:String,at:Long):String {
            val id=UUID.randomUUID().toString();val repo=app.tasks.conversationRepository();repo.transaction { repo.saveConversation(JSONObject().put("conversationId",id).put("title",title).put("createdAt",at).put("updatedAt",at));null };controller.refresh();return id
        }
        fun drain(){val io=PocketWorldController::class.java.getDeclaredField("io").apply { isAccessible=true }.get(controller) as ExecutorService;io.submit{}.get(5,TimeUnit.SECONDS);Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32));val root=owner.get().window.decorView;root.measure(View.MeasureSpec.makeMeasureSpec(1200,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(3000,View.MeasureSpec.EXACTLY));root.layout(0,0,1200,3000);val bitmap=android.graphics.Bitmap.createBitmap(1200,3000,android.graphics.Bitmap.Config.ARGB_8888);var copied=-1;android.view.PixelCopy.request(owner.get().window,bitmap,{copied=it},android.os.Handler(Looper.getMainLooper()));Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals(android.view.PixelCopy.SUCCESS,copied);bitmap.recycle()}
        private fun compose(view:View):View? {if(view.javaClass.name=="androidx.compose.ui.platform.AndroidComposeView")return view;if(view is ViewGroup)for(i in 0 until view.childCount){compose(view.getChildAt(i))?.let{return it}};return null}
        private fun collect(node:SemanticsNode,out:MutableList<SemanticsNode>){out+=node;node.children.forEach{collect(it,out)}}
        fun nodes():List<SemanticsNode> {val out=mutableListOf<SemanticsNode>();WindowInspector.getGlobalWindowViews().filter{it.isAttachedToWindow}.forEach{root->compose(root)?.let{view->val owner=view.javaClass.getMethod("getSemanticsOwner").invoke(view) as SemanticsOwner;collect(owner.rootSemanticsNode,out)}};return out}
        fun text(node:SemanticsNode)=if(node.config.contains(SemanticsProperties.Text))node.config[SemanticsProperties.Text].joinToString(""){it.text}else ""
        fun has(value:String)=nodes().any{text(it).contains(value)}
        fun await(value:String){repeat(60){drain();if(has(value))return};throw AssertionError("Missing existing conversation semantics $value; $error")}
        fun click(label:String){repeat(60){drain();nodes().firstOrNull{node->(text(node)==label||node.config.contains(SemanticsProperties.ContentDescription)&&label in node.config[SemanticsProperties.ContentDescription])&&node.config.contains(SemanticsActions.OnClick)}?.let{node->assertFalse(node.config.contains(SemanticsProperties.Disabled));assertTrue(node.config[SemanticsActions.OnClick].action!!.invoke());drain();return}};throw AssertionError("Missing actual action $label; conversations=${controller.state.conversations}; assistant=${controller.state.feature("assistant")}; nodes=${nodes().map { text(it) + if(it.config.contains(SemanticsProperties.ContentDescription)) it.config[SemanticsProperties.ContentDescription].toString() else "" }.take(30)}")}
        fun edit(before:String,value:String){for(node in nodes())if(node.config.contains(SemanticsActions.SetText)&&node.config.contains(SemanticsProperties.EditableText)&&node.config[SemanticsProperties.EditableText].text==before){assertTrue(node.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString(value)));drain();return};throw AssertionError("Missing original title field")}
        fun org(id:String)=app.features.ownerConversationWorkbench("getOrganization",JSONObject().put("conversationId",id))
        override fun close(){owner.pause().stop().destroy();Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32));app.onTerminate();app.features.close()}
    }
    @Test fun actualManageMenuRenamesAndCancellingADraftDoesNotWrite() {
        Rig().use { ui->val id=ui.seed("原会话名称",100);ui.click("管理对话：原会话名称");ui.click("改名…");ui.edit("原会话名称","未保存的名字");ui.click("取消");assertEquals("原会话名称",ui.org(id).getString("title"));assertEquals(0,ui.org(id).getLong("organizationRevision"));
            ui.click("管理对话：原会话名称");ui.click("改名…");ui.edit("原会话名称","主人手工标题");ui.click("保存名称");ui.await("主人手工标题");assertEquals("主人手工标题",ui.org(id).getString("title"));assertEquals(1,ui.org(id).getLong("organizationRevision"));assertEquals(0,ui.app.tasks.list().length()) }
    }
    @Test fun originalRefreshKeepsPinnedConversationOutsideTheLegacyTwentyRowWindow() {
        Rig().use { ui ->
            val old=ui.seed("被置顶的早期原会话",100)
            repeat(21){ui.seed("较新的原会话$it",1000L+it)}
            ui.app.features.ownerConversationWorkbench("updateOrganization",JSONObject().put("conversationId",old).put("expectedRevision",0).put("changes",JSONObject().put("pinOrder",1)))
            ui.controller.refresh();repeat(4){ui.drain()}
            assertEquals(20,ui.controller.state.conversations.size)
            assertEquals(old,ui.controller.state.conversations.first().id)
            assertEquals(1,ui.controller.conversationOrganizationItem(ui.controller.state.conversations.first()).pinOrder)
            assertEquals(0,ui.app.tasks.list().length())
        }
    }
    @Test fun actualPinMenuPersistsAndUnpinReturnsToDateGroupsWithoutStartingTask() {
        Rig().use { ui->val now=System.currentTimeMillis();val old=ui.seed("较早的对话",now-30*86400000L);ui.seed("今天的对话",now);ui.click("管理对话：较早的对话");ui.click("置顶");ui.await("置顶");assertEquals(1,ui.org(old).getLong("pinOrder"));
            ui.click("管理对话：较早的对话");ui.click("取消置顶");repeat(3){ui.drain()};assertEquals(0,ui.org(old).getLong("pinOrder"));assertEquals(2,ui.org(old).getLong("organizationRevision"));assertEquals(0,ui.app.tasks.list().length()) }
    }
}
