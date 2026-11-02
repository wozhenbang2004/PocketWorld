package dev.pocketengine.ui

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.os.Looper
import android.os.Process
import android.service.notification.StatusBarNotification
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.*
import dev.pocketengine.EngineApplication
import dev.pocketengine.features.attachments.AttachmentRepository
import dev.pocketengine.features.notifications.OwnerNotificationListenerService
import org.junit.Test
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*
import org.robolectric.shadows.ShadowDialog
import java.time.Duration
import java.util.concurrent.TimeUnit

/** Real ComposeActivity/Settings/full-window owner leaf/confirmations/SQLite save.
 * Android grants/notifications are explicit fixtures, not device authorization evidence. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],manifest=Config.NONE,application=EngineApplication::class,qualifiers="w393dp-h852dp-mdpi",shadows=[OfficialAarResources::class])
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(androidx.compose.ui.InternalComposeUiApi::class,androidx.compose.ui.ExperimentalComposeUiApi::class)
class NotificationPreviewOwnerUiTest {
    companion object { const val PKG="example.owner.messages" }
    class Service : OwnerNotificationListenerService() { override fun getActiveNotifications()=emptyArray<StatusBarNotification>() }
    @Before fun setup(){org.robolectric.shadows.ShadowChoreographer.setPaused(true); java.security.Security.addProvider(object:java.security.Provider("NotificationUiKeys",1.0,"Local fixture keys"){init {put("KeyStore.AndroidKeyStore",dev.pocketengine.features.channels.ChannelTaskHostTest.FixtureKeyStore::class.java.name)}})}
    @After fun cleanup(){java.security.Security.removeProvider("NotificationUiKeys")}
    private fun root(view:View):ViewRootForTest? {if(view is ViewRootForTest)return view;if(view is ViewGroup)for(i in 0 until view.childCount)root(view.getChildAt(i))?.let{return it};return null}
    private fun windows(activity:ComposeActivity)=listOf(activity.window.decorView)+ShadowDialog.getShownDialogs().filter{it.isShowing}.mapNotNull{it.window?.decorView}
    private fun nodes(activity:ComposeActivity)=windows(activity).flatMap{root(it)?.semanticsOwner?.getAllSemanticsNodes(mergingEnabled=true).orEmpty()}
    private fun text(node:SemanticsNode)=if(node.config.contains(SemanticsProperties.Text))node.config[SemanticsProperties.Text].joinToString(""){it.text}else ""
    private fun frame(activity:ComposeActivity){fun layout(view:View){if(view is RootForTest)view.measureAndLayoutForTest();if(view is ViewGroup)for(i in 0 until view.childCount)layout(view.getChildAt(i))};repeat(3){androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications();Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32));windows(activity).forEach(::layout);Thread.sleep(5)}}
    private fun await(activity:ComposeActivity,condition:()->Boolean){val until=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);while(!condition()&&System.nanoTime()<until)frame(activity);assertTrue("Actual owner UI condition not reached; texts="+nodes(activity).map(::text),condition())}
    private fun click(activity:ComposeActivity,predicate:(SemanticsNode)->Boolean){val node=nodes(activity).firstOrNull(predicate)?:error("Missing native action: "+nodes(activity).map(::text));assertTrue(node.config[SemanticsActions.OnClick].action!!.invoke());frame(activity)}
    private fun tag(name:String):(SemanticsNode)->Boolean={it.config.contains(SemanticsProperties.TestTag)&&it.config[SemanticsProperties.TestTag]==name}
    @Test fun existingSettingsOpensSameWindowAndExactConfirmedNoticeSavesWithoutStartingTask(){
        val app=RuntimeEnvironment.getApplication() as EngineApplication
        val component=ComponentName(app,OwnerNotificationListenerService::class.java)
        val service=ServiceInfo().apply{name=component.className;packageName=app.packageName;applicationInfo=app.applicationInfo;exported=false;enabled=true;permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"}
        Shadows.shadowOf(app.packageManager).addOrUpdateService(service)
        val info=ApplicationInfo().apply{packageName=PKG;enabled=true;nonLocalizedLabel="消息应用"}
        Shadows.shadowOf(app.packageManager).installPackage(PackageInfo().apply{packageName=PKG;applicationInfo=info})
        Shadows.shadowOf(app.packageManager).addResolveInfoForIntent(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),ResolveInfo().apply{activityInfo=ActivityInfo().apply{packageName=PKG;name="$PKG.Main";applicationInfo=info}})
        Shadows.shadowOf(app.getSystemService(NotificationManager::class.java)).setNotificationListenerAccessGranted(component,true)
        val task=app.tasks.create("保留这份原任务").getString("id")
        val pending=Robolectric.buildActivity(ComposeActivity::class.java);pending.get().setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        val screen=pending.setup().visible();Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32));screen.windowFocusChanged(true);val activity=screen.get()
        val field=ComposeActivity::class.java.getDeclaredField("controller").apply{isAccessible=true};val controller=field.get(activity) as PocketWorldController
        var connected:org.robolectric.android.controller.ServiceController<Service>?=null
        try{
            frame(activity)
            click(activity){it.config.contains(SemanticsProperties.ContentDescription)&&it.config[SemanticsProperties.ContentDescription].contains("设置与连接")}
            click(activity){text(it)=="手机"&&it.config.contains(SemanticsActions.OnClick)}
            click(activity){text(it).contains("其他应用通知预览")&&it.config.contains(SemanticsActions.OnClick)}
            screen.windowFocusChanged(true);await(activity){controller.notificationPreviewsOpen&&nodes(activity).any(tag("notification_preview_state"))}
            assertTrue(activity.hasWindowFocus());assertFalse(ShadowDialog.getShownDialogs().any{it.isShowing})
            System.getProperty("notification.captureDir")?.let { directory ->
                val view=activity.window.decorView;val image=android.graphics.Bitmap.createBitmap(view.width.coerceAtLeast(1),view.height.coerceAtLeast(1),android.graphics.Bitmap.Config.ARGB_8888);view.draw(android.graphics.Canvas(image));java.io.File(directory).mkdirs();java.io.File(directory,"owner-preview-before-enable.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) };image.recycle()
            }
            click(activity,tag("notification_package_$PKG"));click(activity,tag("notification_preview_enable"))
            screen.windowFocusChanged(false);click(activity){text(it)=="明确开启所选范围"}
            assertFalse(app.notificationsModule().snapshot().getBoolean("enabled"))
            screen.pause();screen.resume();Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32));screen.windowFocusChanged(true);frame(activity)
            assertFalse("Leaving the Activity clears its pending owner confirmation",app.notificationsModule().snapshot().getBoolean("enabled"))
            click(activity,tag("notification_preview_enable"));screen.windowFocusChanged(false);click(activity){text(it)=="明确开启所选范围"};screen.windowFocusChanged(true)
            await(activity){app.notificationsModule().snapshot().getBoolean("enabled")}
            connected=Robolectric.buildService(Service::class.java).create();connected.get().onListenerConnected()
            val notification=Notification.Builder(app,"incoming").setSmallIcon(android.R.drawable.stat_notify_chat).setContentTitle("fixture title").setContentText("exact fixture notification body").build()
            connected.get().onNotificationPosted(StatusBarNotification(PKG,PKG,1,"private tag",Process.myUid(),0,0,notification,Process.myUserHandle(),System.currentTimeMillis()+1000))
            await(activity){nodes(activity).any{text(it).contains("保留这份原任务")}}
            click(activity,tag("notification_target_$task"))
            // The bounded list may put its notice below the viewport; scroll via native semantics.
            repeat(4){nodes(activity).firstOrNull{it.config.contains(SemanticsActions.ScrollBy)}?.config?.get(SemanticsActions.ScrollBy)?.action?.invoke(0f,500f);frame(activity)}
            click(activity){text(it)=="复核这一条并另存"}
            screen.windowFocusChanged(false);click(activity){text(it)=="明确保存这一条"};assertEquals(0,app.tasks.ownerAttachmentReferences(task).length());screen.windowFocusChanged(true)
            await(activity){app.tasks.ownerAttachmentReferences(task).length()==1}
            assertEquals(1,AttachmentRepository.get(app).book.list().length());assertEquals(1,app.tasks.list().length());assertTrue(app.tasks.get(task,false).optString("runId").isEmpty())
            click(activity,tag("notification_preview_disable"));assertFalse(app.notificationsModule().snapshot().getBoolean("enabled"));assertEquals(1,app.tasks.ownerAttachmentReferences(task).length())
            controller.closeNotificationPreviews();frame(activity);assertFalse(controller.notificationPreviewsOpen)
        }finally{connected?.destroy();screen.pause().stop().destroy();app.features.close()}
    }
}
