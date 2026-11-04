package dev.pocketengine.ui

import android.app.Activity
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import androidx.compose.runtime.MutableState
import dev.pocketengine.EngineApplication
import dev.pocketengine.core.RunControl
import dev.pocketengine.features.FeatureContext
import dev.pocketengine.features.attachments.AttachmentOutcomeHost
import dev.pocketengine.features.attachments.AttachmentRepository
import dev.pocketengine.features.attachments.AttachmentsModule
import dev.pocketengine.features.attachments.TeamWorkspaceOutcomeHost
import dev.pocketengine.media.jobs.results.GroupResultAccess
import dev.pocketengine.store.SecretVault
import dev.pocketengine.store.SettingsStore
import dev.pocketengine.store.TaskStore
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileNotFoundException
import java.util.ArrayDeque
import java.util.concurrent.Executor

/** Real Activity launcher, coordinator and SAF/repository bytes; Android launch outcomes are explicit fixtures. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],manifest=Config.NONE,application=Application::class,shadows=[AttachmentExportPickerLifecycleTest.PickerShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class AttachmentExportPickerLifecycleTest {
    @Implements(Activity::class)
    class PickerShadow:org.robolectric.shadows.ShadowActivity() {
        @Implementation
        fun startActivityForResult(intent:Intent,requestCode:Int,options:Bundle?) {
            launches.add(intent)
            when(launchFailure){
                "missing"->throw android.content.ActivityNotFoundException("No document picker")
                "unknown"->throw IllegalStateException("Platform may already have dispatched")
            }
        }
    }
    class Destination:ContentProvider() {
        override fun onCreate()=true
        override fun openFile(uri:Uri,mode:String):ParcelFileDescriptor {
            opens++
            if(failWrite)throw FileNotFoundException("Destination refused the write")
            return ParcelFileDescriptor.open(File(outputRoot,uri.lastPathSegment!!),ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_WRITE_ONLY)
        }
        override fun query(uri:Uri,projection:Array<out String>?,selection:String?,args:Array<out String>?,sort:String?):Cursor?=null
        override fun getType(uri:Uri)="text/plain"
        override fun insert(uri:Uri,values:ContentValues?):Uri?=null
        override fun delete(uri:Uri,selection:String?,args:Array<out String>?)=0
        override fun update(uri:Uri,values:ContentValues?,selection:String?,args:Array<out String>?)=0
    }
    companion object {
        val launches=ArrayList<Intent>()
        var launchFailure=""
        lateinit var outputRoot:File
        var opens=0
        var failWrite=false
    }
    private lateinit var activity:ComposeActivity
    private lateinit var app:EngineApplication
    private lateinit var repository:AttachmentRepository
    private lateinit var flow:AttachmentOutcomeCoordinator
    private lateinit var io:QueuedIo
    private lateinit var original:JSONObject
    private lateinit var other:JSONObject
    private val bytes="exact original picker source".toByteArray()
    private val otherBytes="a different source must not replace it".toByteArray()
    private var ownerWindows=0

    @Before fun prepare() {
        launches.clear();launchFailure="";opens=0;failWrite=false;ownerWindows=0
        app=EngineApplication()
        ReflectionHelpers.callInstanceMethod<Any>(app,"attach",ClassParameter.from(android.content.Context::class.java,RuntimeEnvironment.getApplication()))
        app.tasks=TaskStore(app);repository=AttachmentRepository.get(app)
        val documents=AttachmentsModule(FeatureContext(app,SettingsStore(app),SecretVault(app)){})
        app.attachmentOutcomes=AttachmentOutcomeHost(app.tasks,repository,documents){_,_,_->error("No provider effect")}
        original=repository.book.importBytes(ByteArrayInputStream(bytes),"original.txt","text/plain",JSONObject())
        other=repository.book.importBytes(ByteArrayInputStream(otherBytes),"other.txt","text/plain",JSONObject())
        outputRoot=File(app.cacheDir,"picker-destinations").also{it.mkdirs()}
        val info=android.content.pm.ProviderInfo().also{it.authority="export-picker-fixture";it.exported=false}
        ShadowContentResolver.registerProviderInternal(info.authority,Robolectric.buildContentProvider(Destination::class.java).create(info).get())
        io=QueuedIo();newCoordinator();flow.foreground(true);flow.openFile(original);drain()
        assertNotNull(flow.state.file);assertNull(flow.state.error)
    }
    @After fun finish() {
        if(::flow.isInitialized){flow.detach();idle()}
        if(::app.isInitialized&&app.tasks!=null)ReflectionHelpers.getField<android.database.sqlite.SQLiteDatabase>(app.tasks,"db").close()
    }
    @Test fun repeatedPrepareCannotReplaceTheOneOutstandingSource() {
        flow.prepareExport();val pin=pin()!!
        flow.selectFile(other);repeat(3){flow.prepareExport()}
        assertEquals(1,launches.size);assertSame(pin,pin())
        assertEquals(original.getString("id"),pin.getJSONObject("file").getString("id"))
        deliver(uri("original.txt"));drain()
        assertArrayEquals(bytes,File(outputRoot,"original.txt").readBytes());assertEquals(1,opens);assertNull(pin());assertOriginals()
    }
    @Test fun backgroundAndReturnKeepTheOriginalPendingRequest() {
        flow.prepareExport();val pin=pin()
        flow.foreground(false);flow.foreground(true);flow.prepareExport()
        assertEquals(1,launches.size);assertSame(pin,pin())
        deliver(uri("background.txt"));drain();assertArrayEquals(bytes,File(outputRoot,"background.txt").readBytes())
    }
    @Test fun busyClosedAndDetachedViewsCannotLaunchAnotherPicker() {
        state(flow.state.copy(busy=true));flow.prepareExport();assertTrue(launches.isEmpty())
        state(flow.state.copy(busy=false));flow.close();flow.prepareExport();assertTrue(launches.isEmpty())
        state(AttachmentOutcomeState(open=true,file=original,snapshot=JSONObject()));flow.detach();flow.prepareExport();assertTrue(launches.isEmpty())
    }
    @Test fun cancellationReleasesTheSlotForANewExplicitRequest() {
        flow.prepareExport();deliver(null);assertNull(pin())
        flow.selectFile(other);flow.prepareExport();assertEquals(2,launches.size)
        deliver(uri("next.txt"));drain();assertArrayEquals(otherBytes,File(outputRoot,"next.txt").readBytes());assertEquals(1,opens)
    }
    @Test fun strayAndDuplicateResultsNeverWriteWithoutAPendingPicker() {
        result("unrelated",uri("stray.txt"));assertEquals(0,io.size)
        flow.prepareExport();val request=requestId();result(request,uri("one.txt"));val writing=pin();result(request,uri("duplicate.txt"));launchStatus(request,"NOT_STARTED");launchStatus(request,"UNKNOWN");assertSame(writing,pin());drain()
        assertEquals(1,opens);assertFalse(File(outputRoot,"duplicate.txt").exists());assertNull(pin())
    }
    @Test fun explicitNoHandlerResultClearsOnlyTheUnstartedRequestAndAllowsRetry() {
        launchFailure="missing";flow.prepareExport()
        assertNull(pin());assertNotNull(flow.state.error);assertTrue(flow.state.error!!.contains("未启动"))
        launchFailure="";flow.prepareExport();assertNotNull(pin());assertEquals(2,launches.size)
        deliver(uri("retry.txt"));drain();assertArrayEquals(bytes,File(outputRoot,"retry.txt").readBytes())
    }
    @Test fun unknownPlatformExceptionRetainsThePinAndAcceptsItsLaterResult() {
        launchFailure="unknown";flow.prepareExport();val pin=pin()
        assertNotNull(pin);assertTrue(flow.state.error!!.contains("尚未确认"))
        launchFailure="";flow.selectFile(other);flow.prepareExport();assertEquals(1,launches.size);assertSame(pin,pin())
        deliver(uri("unknown.txt"));drain();assertArrayEquals(bytes,File(outputRoot,"unknown.txt").readBytes())
    }
    @Test fun unknownLaunchStaysReservedAcrossSaveAndRestore() {
        launchFailure="unknown";flow.prepareExport();val saved=Bundle();flow.save(saved)
        recreate(saved);flow.prepareExport()
        assertEquals(1,launches.size);assertEquals(original.getString("id"),pin()!!.getJSONObject("file").getString("id"))
        deliver(null);assertNull(pin());assertEquals(0,opens)
    }
    @Test fun savedPickerRestoresItsOriginalPinInsteadOfTheCurrentSelection() {
        flow.prepareExport();flow.selectFile(other);val saved=Bundle();flow.save(saved)
        recreate(saved);flow.prepareExport();assertEquals(1,launches.size)
        deliver(uri("restored.txt"));drain();assertArrayEquals(bytes,File(outputRoot,"restored.txt").readBytes())
    }
    @Test fun consumedPausedResultIsNotReplayedFromASavedUriOnRecreation() {
        flow.prepareExport();flow.foreground(false);deliver(uri("paused.txt"))
        val saved=Bundle();flow.save(saved);assertEquals(0,opens)
        recreate(saved);drain()
        assertEquals(0,opens);assertEquals(1,launches.size);assertNull(pin());assertNotNull(flow.state.error)
        assertTrue(flow.state.error!!.contains("尚未确认"));assertFalse(saved.containsKey("attachmentOutcomeExportResult"))
    }
    @Test fun sameInstanceResumesItsConsumedResultOnceWithoutRelaunching() {
        flow.prepareExport();val request=requestId();flow.foreground(false);result(request,uri("paused-live.txt"))
        result(request,uri("duplicate.txt"));assertEquals(0,opens);flow.foreground(true);drain()
        assertArrayEquals(bytes,File(outputRoot,"paused-live.txt").readBytes());assertEquals(1,opens);assertEquals(1,launches.size)
    }
    @Test fun closingTheOriginalViewAbandonsButDoesNotReuseItsOutstandingSlot() {
        flow.prepareExport();flow.close();flow.openFile(other);drain();flow.prepareExport()
        assertEquals(1,launches.size)
        deliver(uri("closed.txt"));drain();assertEquals(0,opens);assertNull(pin())
        flow.prepareExport();assertEquals(2,launches.size)
    }
    @Test fun closedPendingStateStillDiscardsItsLateResultAfterRestoration() {
        flow.prepareExport();flow.close();val saved=Bundle();flow.save(saved)
        recreate(saved);flow.openFile(other);drain();deliver(uri("abandoned.txt"));drain()
        assertEquals(0,opens);assertNull(pin())
    }
    @Test fun failedWriteConsumesThePickerWithoutReplayingItAfterRestore() {
        flow.prepareExport();failWrite=true;deliver(uri("refused.txt"));drain()
        assertNull(pin());assertNotNull(flow.state.error);assertEquals(1,opens)
        val saved=Bundle();flow.save(saved);assertFalse(saved.containsKey("attachmentOutcomeExport"))
        recreate(saved);assertNull(pin());assertEquals(1,opens)
        failWrite=false;flow.prepareExport();deliver(uri("retry-write.txt"));drain();assertArrayEquals(bytes,File(outputRoot,"retry-write.txt").readBytes())
    }
    @Test fun ioKeepsItsSlotUntilTerminalCleanupAndOldCallbacksCannotConsumeTheNextPin() {
        flow.prepareExport();val old=requestId();result(old,uri("old.txt"));val writing=pin()
        flow.cancel();flow.selectFile(other);flow.prepareExport()
        assertEquals(1,launches.size);assertSame(writing,pin());drain();assertNull(pin());assertEquals(0,opens)
        flow.prepareExport();val newer=pin();result(old,uri("late-old.txt"));assertSame(newer,pin());assertEquals(0,io.size)
        deliver(uri("new.txt"));drain();assertArrayEquals(otherBytes,File(outputRoot,"new.txt").readBytes());assertEquals(1,opens)
    }
    @Test fun cancelledOldPickerCallbackCannotCancelOrWriteThroughANewRequest() {
        flow.prepareExport();val old=requestId();result(old,null)
        flow.selectFile(other);flow.prepareExport();val newer=pin()
        result(old,null);result(old,uri("late.txt"));launchStatus(old,"NOT_STARTED");launchStatus(old,"UNKNOWN");assertSame(newer,pin());assertEquals(0,io.size)
        deliver(uri("fresh.txt"));drain();assertArrayEquals(otherBytes,File(outputRoot,"fresh.txt").readBytes());assertEquals(1,opens)
    }
    @Test fun restoredWritingPhaseIsUnknownAndDoesNotQueueTheSavedDestinationAgain() {
        flow.prepareExport();deliver(uri("interrupted.txt"));val saved=Bundle();flow.save(saved)
        assertEquals("writing",JSONObject(saved.getString("attachmentOutcomeExport")!!).getString("exportPhase"))
        recreate(saved);drain();assertEquals(0,opens);assertNull(pin());assertNotNull(flow.state.error);assertTrue(flow.state.error!!.contains("尚未确认"))
    }
    @Test fun rejectedWriterQueueReleasesTheUnstartedWriteWithAnExplicitError() {
        flow.prepareExport();io.rejecting=true;deliver(uri("not-queued.txt"))
        assertNull(pin());assertEquals(0,opens);assertNotNull(flow.state.error);assertTrue(flow.state.error!!.contains("未开始"))
    }
    @Test fun originalPrivateExportDoesNotBorrowTheNewCurrentGroupWindow() {
        flow.prepareExport();state(flow.state.copy(group=group()))
        deliver(uri("private.txt"));drain();assertEquals(0,ownerWindows);assertArrayEquals(bytes,File(outputRoot,"private.txt").readBytes())
    }
    @Test fun originalGroupExportStillNeedsItsOwnerWindowAfterSelectionChanges() {
        state(flow.state.copy(group=group()));flow.prepareExport();state(flow.state.copy(group=null))
        deliver(uri("group.txt"));drain()
        assertEquals(1,ownerWindows);assertNotNull(flow.state.error);assertEquals(0,opens);assertNull(pin())
    }
    @Test fun teamWaitingPinIsNotReplacedAndWritingRestoreDoesNotReplayEvenWhenRefreshFails() {
        val ref=JSONObject();for(key in listOf("teamRunId","assignmentId","taskId","runId","artifactId","draftProjectId","targetProjectId","draftRevisionId"))ref.put(key,java.util.UUID.randomUUID().toString())
        ref.put("assignmentId",ref.getString("taskId")).put("memberId","writer").put("path","frozen.txt").put("baseVersion",0).put("baseHash","absent").put("draftVersion",1).put("sha256",original.getString("sha256"))
        val team=TeamWorkspaceOutcomeHost.Selection.fromManifest(ref)
        state(AttachmentOutcomeState(open=true,taskId=ref.getString("taskId"),team=team,snapshot=JSONObject().put("artifact",JSONObject().put("kind","text_file").put("mimeType","text/plain").put("path","frozen.txt"))))
        flow.prepareExport();val originalPin=pin();flow.prepareExport();assertEquals(1,launches.size);assertSame(originalPin,pin());assertEquals("teams",pin()!!.getString("provider"))
        deliver(uri("team.txt"));val saved=Bundle();flow.save(saved);recreate(saved)
        // The scoped fixture deliberately has no team host, so even the new preview fails. Unknown export evidence must still remain visible.
        assertNull(pin());assertEquals(0,opens);assertNotNull(flow.state.error);assertTrue(flow.state.error!!.contains("尚未确认"));assertEquals(1,launches.size)
    }
    private fun group()=GroupResultAccess.Selection("request","task","space","output","artifact","group","members","","")
    private fun newCoordinator() {
        activity=Robolectric.buildActivity(ComposeActivity::class.java).get();Shadows.shadowOf(activity).setApplication(app)
        flow=AttachmentOutcomeCoordinator(activity,app,io,Handler(Looper.getMainLooper()),{ownerWindows++;error("No group authority")},{},{})
    }
    private fun recreate(saved:Bundle) {
        flow.detach();idle();newCoordinator();flow.restore(saved);flow.foreground(true);drain()
    }
    private fun state(value:AttachmentOutcomeState){ReflectionHelpers.getField<MutableState<AttachmentOutcomeState>>(flow,"state\$delegate").value=value}
    private fun pin():JSONObject?=ReflectionHelpers.getField(flow,"export")
    private fun requestId()=pin()!!.optString("exportRequestId","baseline")
    private fun deliver(uri:Uri?){result(requestId(),uri)}
    private fun launchStatus(requestId:String,name:String) {
        val method=flow.javaClass.methods.firstOrNull{it.name=="exportLaunchResult"}?:return
        val result=method.parameterTypes[1].enumConstants.first{(it as Enum<*>).name==name}
        method.invoke(flow,requestId,result)
    }
    private fun result(requestId:String,uri:Uri?) {
        if(flow.javaClass.methods.any{it.name=="exportResult"&&it.parameterCount==2})
            ReflectionHelpers.callInstanceMethod<Any?>(flow,"exportResult",ClassParameter.from(String::class.java,requestId),ClassParameter.from(Uri::class.java,uri))
        else ReflectionHelpers.callInstanceMethod<Any?>(flow,"exportResult",ClassParameter.from(Uri::class.java,uri))
    }
    private fun uri(name:String)=Uri.parse("content://export-picker-fixture/$name")
    private fun idle(){Shadows.shadowOf(Looper.getMainLooper()).idle()}
    private fun drain(){while(io.size>0){io.next();idle()};idle()}
    private fun assertOriginals(){
        assertArrayEquals(bytes,repository.book.readVerifiedSnapshot(original.getString("id"),original.getString("sha256"),RunControl()))
        assertArrayEquals(otherBytes,repository.book.readVerifiedSnapshot(other.getString("id"),other.getString("sha256"),RunControl()));assertEquals(2,repository.book.list().length())
    }
    private class QueuedIo:Executor {
        private val jobs=ArrayDeque<Runnable>()
        val size get()=jobs.size
        var rejecting=false
        override fun execute(task:Runnable){if(rejecting)throw java.util.concurrent.RejectedExecutionException("Fixture queue rejection");jobs.add(task)}
        fun next(){check(jobs.isNotEmpty());jobs.removeFirst().run()}
    }
}
