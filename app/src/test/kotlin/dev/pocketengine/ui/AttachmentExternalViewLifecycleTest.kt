package dev.pocketengine.ui

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Handler
import android.os.Looper
import dev.pocketengine.EngineApplication
import dev.pocketengine.core.RunControl
import androidx.compose.runtime.MutableState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.savedstate.SavedStateRegistryController
import dev.pocketengine.features.FeatureContext
import dev.pocketengine.features.NativeGroupOwnerWorkflows
import dev.pocketengine.features.groups.GroupsModule
import dev.pocketengine.media.jobs.results.GroupMediaResults
import dev.pocketengine.media.jobs.results.GroupResultAccess
import dev.pocketengine.features.attachments.AttachmentOutcomeHost
import dev.pocketengine.features.attachments.AttachmentRepository
import dev.pocketengine.features.attachments.AttachmentsModule
import dev.pocketengine.features.attachments.AttachmentViewSnapshots
import dev.pocketengine.store.SecretVault
import dev.pocketengine.store.SettingsStore
import dev.pocketengine.store.TaskStore
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import java.io.ByteArrayInputStream
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.Executor

/** Actual coordinator, owner host and immutable snapshots, with explicit Android-launch and scoped group-access fixtures. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],manifest=Config.NONE,application=Application::class,shadows=[AttachmentExternalViewLifecycleTest.LaunchShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class AttachmentExternalViewLifecycleTest {
    @Implements(Activity::class)
    class LaunchShadow:org.robolectric.shadows.ShadowActivity() {
        @Implementation
        fun startActivity(intent:Intent) {
            launches.add(intent)
            onEntry?.invoke()
            if(noReader)throw android.content.ActivityNotFoundException("No matching Android reader")
            if(throwAfterEntry)throw IllegalStateException("platform launch result is unknown")
        }
    }
    companion object {
        val launches=ArrayList<Intent>()
        var throwAfterEntry=false
        var noReader=false
        var onEntry:(()->Unit)?=null
    }
    private lateinit var activity:ComposeActivity
    private lateinit var app:EngineApplication
    private lateinit var repository:AttachmentRepository
    private lateinit var flow:AttachmentOutcomeCoordinator
    private lateinit var io:QueuedIo
    private lateinit var original:JSONObject
    private var groupPreparedCopyObserved=false
    private val bytes="fixed original, never a disposable view".toByteArray()

    @Before fun prepare() {
        launches.clear();throwAfterEntry=false;noReader=false;onEntry=null
        // Attach the real Application without starting unrelated feature workers or external providers.
        app=EngineApplication()
        ReflectionHelpers.callInstanceMethod<Any>(app,"attach",ClassParameter.from(android.content.Context::class.java,RuntimeEnvironment.getApplication()))
        app.tasks=TaskStore(app);repository=AttachmentRepository.get(app)
        val documents=AttachmentsModule(FeatureContext(app,SettingsStore(app),SecretVault(app)){})
        app.attachmentOutcomes=AttachmentOutcomeHost(app.tasks,repository,documents){_,_,_->error("No media operation")}
        original=repository.book.importBytes(ByteArrayInputStream(bytes),"fixed.txt","text/plain",JSONObject())
        activity=Robolectric.buildActivity(ComposeActivity::class.java).get()
        io=QueuedIo();flow=AttachmentOutcomeCoordinator(activity,app,io,Handler(Looper.getMainLooper()),{error("No group authority")},{},{})
        flow.foreground(true);flow.openFile(original);io.next();idle()
        assertNotNull(flow.state.file);assertNull(flow.state.error)
    }
    @After fun finish() {
        if(::flow.isInitialized){flow.detach();idle()}
        if(::app.isInitialized)app.groupOwnerWorkflows?.close()
        if(::app.isInitialized&&app.tasks!=null)ReflectionHelpers.getField<android.database.sqlite.SQLiteDatabase>(app.tasks,"db").close()
    }
    @Test fun closedPreparationReleasesOnlyItsOwnCopyAcrossRepeatedOpens() {
        repeat(6) {
            flow.openExternal();io.next();assertEquals(1,copies().size)
            flow.close();assertEquals("Main only enqueues disk cleanup",1,copies().size);io.drain();assertEquals("An unlaunched close must release quota",0,copies().size)
            assertTrue(launches.isEmpty());assertOriginal()
            flow.openFile(original);io.next();idle()
        }
    }
    @Test fun backgroundedPreparationDoesNotWaitForTenMinuteExpiry() {
        flow.openExternal();io.next();assertEquals(1,copies().size)
        flow.foreground(false);io.drain()
        assertEquals(0,copies().size);assertTrue(launches.isEmpty());assertOriginal()
    }
    @Test fun cancelledPreparationCannotLaunchAfterAnotherViewOpens() {
        flow.openExternal();io.next();assertEquals(1,copies().size)
        flow.cancel();flow.openFile(original);io.drain()
        assertEquals(0,copies().size);assertTrue(launches.isEmpty());assertOriginal()
    }
    @Test fun readerCopySurvivesWorkCompletionAndLaterCloseWithOriginalTtl() {
        flow.openExternal();io.next();idle()
        assertEquals(1,launches.size);assertEquals(1,copies().size)
        val intent=launches.single()
        assertNotEquals(0,intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
        assertEquals(0,intent.flags and (Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION))
        assertTrue("Dispatch must fence later unissued cleanup",intent.getBooleanExtra(AttachmentOutcomeHost.NATIVE_VIEW_ISSUED,false))
        flow.cancel();flow.close();idle()
        assertArrayEquals(bytes,copies().single().readBytes());assertOriginal()
        val token=intent.data!!.lastPathSegment!!
        val record=JSONObject(File(app.cacheDir,"outcome-views/$token.json").readText())
        val remaining=record.getLong("expiresAt")-System.currentTimeMillis()
        assertTrue("Existing ten-minute reader TTL is preserved",remaining in 550_000L..600_000L)
        assertThrows(SecurityException::class.java){app.attachmentOutcomes.discardUnissuedView(intent)}
        assertEquals(1,copies().size)
    }
    @Test fun exceptionAfterStartActivityEntryDoesNotDeleteAnUnknownReaderCopy() {
        throwAfterEntry=true
        flow.openExternal();io.next();idle()
        assertEquals(1,launches.size);assertEquals(1,copies().size)
        flow.cancel();flow.close();idle()
        assertTrue(launches.single().getBooleanExtra(AttachmentOutcomeHost.NATIVE_VIEW_ISSUED,false))
        assertArrayEquals(bytes,copies().single().readBytes());assertOriginal()
    }
    @Test fun explicitNoReaderResultReleasesOnlyThisCopyEvenWithReentrantCancel() {
        repeat(6){
            noReader=true;onEntry={flow.cancel()}
            flow.openExternal();io.next();assertEquals(1,copies().size);idle();io.drain()
            assertEquals(0,copies().size);assertNotNull(flow.state.error)
            assertFalse(launches.last().getBooleanExtra(AttachmentOutcomeHost.NATIVE_VIEW_ISSUED,false));assertOriginal()
        }
    }
    @Test fun cancellingANewPreparationKeepsAnAlreadyIssuedReaderCopy() {
        flow.openExternal();io.next();idle();val delivered=launches.single().data!!.lastPathSegment!!
        flow.openExternal();io.next();assertEquals(2,copies().size)
        flow.cancel();io.drain()
        assertEquals(1,launches.size);assertEquals(listOf("$delivered.blob"),copies().map{it.name})
        assertArrayEquals(bytes,copies().single().readBytes());assertOriginal()
    }
    @Test fun rejectedMainQueueReleasesTheNeverLaunchedCopy() {
        val thread=android.os.HandlerThread("closed-view-main-fixture");thread.start();val handler=Handler(thread.looper)
        thread.quit();thread.join(10_000);assertFalse(thread.isAlive)
        flow.detach();flow=AttachmentOutcomeCoordinator(activity,app,io,handler,{error("No group authority")},{},{})
        ReflectionHelpers.getField<MutableState<AttachmentOutcomeState>>(flow,"state\$delegate").value=AttachmentOutcomeState(open=true,file=original,snapshot=JSONObject())
        flow.foreground(true);flow.openExternal();io.next();io.drain()
        assertEquals(0,copies().size);assertTrue(launches.isEmpty());assertOriginal()
    }
    @Test fun refusedCleanupExecutorKeepsTheExistingTtlWithoutTouchingTheOriginal() {
        flow.openExternal();io.next();assertEquals(1,copies().size)
        io.rejecting=true;flow.close();idle()
        assertTrue(launches.isEmpty());assertEquals(1,copies().size);assertArrayEquals(bytes,copies().single().readBytes());assertOriginal()
    }
    @Test fun groupFailureAfterPreparingTheCopyReleasesItWithoutAStillLiveOwnerWindow() {
        val originalGroup=selectGroup(revokeWhenPrepared=true)
        flow.openExternal();io.next();io.drain()
        assertEquals(0,copies().size);assertTrue(launches.isEmpty());assertNotNull(flow.state.error);assertTrue("The group post-read gate failed only after this copy was prepared",groupPreparedCopyObserved)
        assertArrayEquals(bytes,originalGroup.readBytes());assertOriginal()
    }
    @Test fun groupPreparationReleasesItsCopyAfterOriginalWindowRevocationWithoutOwnerCleanup() {
        val originalGroup=selectGroup()
        flow.openExternal();io.next();assertEquals(1,copies().size)
        app.groupOwnerWorkflows.close();flow.foreground(false);io.drain()
        assertEquals(0,copies().size);assertTrue(launches.isEmpty())
        assertArrayEquals(bytes,originalGroup.readBytes());assertOriginal()
    }
    @Test fun groupExplicitNoReaderResultDoesNotBorrowOwnerCleanup() {
        val originalGroup=selectGroup();noReader=true;onEntry={flow.cancel()}
        flow.openExternal();io.next();assertEquals(1,copies().size);idle();io.drain()
        assertEquals(0,copies().size);assertNotNull(flow.state.error)
        assertArrayEquals(bytes,originalGroup.readBytes());assertOriginal()
    }
    @Test fun groupReaderCopySurvivesCancellationAndRejectsUnissuedCleanup() {
        val originalGroup=selectGroup()
        flow.openExternal();io.next();idle()
        assertEquals(1,launches.size);flow.cancel();flow.close();idle()
        val intent=launches.single()
        assertThrows(SecurityException::class.java){AttachmentViewSnapshots(app).discardUnissuedView(intent)}
        assertArrayEquals(bytes,copies().single().readBytes());assertArrayEquals(bytes,originalGroup.readBytes());assertOriginal()
    }
    @Test fun copyCleanupRejectsCanonicalOrForeignUris() {
        val copies=AttachmentViewSnapshots(app)
        for(uri in listOf(repository.uri(original.getString("id")),android.net.Uri.parse("content://another.app.attachments/view/00000000-0000-0000-0000-000000000000"))){
            assertThrows(SecurityException::class.java){copies.discardUnissuedView(Intent(Intent.ACTION_VIEW).setData(uri))}
        }
        assertOriginal()
    }
    private fun selectGroup(revokeWhenPrepared:Boolean=false):File {
        val owner=NativeGroupOwnerWorkflows(app,ComposeActivity::class.java)
        app.groupOwnerWorkflows=owner
        owner.bind(GroupsModule(File(app.filesDir,"view-group-registry"),null,null,null,owner))
        Shadows.shadowOf(activity).setApplication(app)
        // Explicit ComponentActivity lifecycle fixture; no Compose screen or unrelated Controller is mounted.
        ReflectionHelpers.getField<SavedStateRegistryController>(activity,"savedStateRegistryController").performRestore(null)
        (activity.lifecycle as LifecycleRegistry).currentState=Lifecycle.State.RESUMED
        val window=owner.open(activity)
        val selected=GroupResultAccess.Selection("request","task","group-space","artifact","artifact","group","members","","")
        val context=GroupResultAccess.Context(selected,"",0,1,"completed","not_required","","",0,"",false,false)
        val canonical=File(app.filesDir,"scoped-group-original.txt");canonical.writeBytes(bytes)
        val sha=java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it.toInt() and 255)}
        val artifact=GroupResultAccess.Artifact("artifact","group-space","task","artifact","group","text/plain",bytes.size.toLong(),sha,"","","","")
        val access=object:GroupResultAccess {
            override fun inspect(selection:GroupResultAccess.Selection,control:RunControl):GroupResultAccess.Context {
                owner.check();control.check()
                if(revokeWhenPrepared)control.setAuthorityCheck{if(File(app.cacheDir,"outcome-views").listFiles{_,name->name.endsWith(".json")}?.isNotEmpty()==true){groupPreparedCopyObserved=true;owner.close(window)}}
                return context
            }
            override fun checkRead(expected:GroupResultAccess.Context,control:RunControl){owner.check();control.check()}
            override fun mediaReceipt(expected:GroupResultAccess.Context,control:RunControl):JSONObject?=null
            override fun artifact(expected:GroupResultAccess.Context,control:RunControl)=artifact
            override fun openArtifact(expected:GroupResultAccess.Context,selected:GroupResultAccess.Artifact,control:RunControl)=ByteArrayInputStream(canonical.readBytes())
            override fun delivery(expected:GroupResultAccess.Context,control:RunControl):GroupResultAccess.Delivery?=null
            override fun refreshOriginalMedia(expected:GroupResultAccess.Context,refresh:GroupResultAccess.Refresh,control:RunControl){error("No provider effect")}
        }
        ReflectionHelpers.setField(app,"groupMediaResults",GroupMediaResults(access))
        app.attachmentOutcomes=null // The group path cannot borrow the private owner cleanup host.
        flow.detach();flow=AttachmentOutcomeCoordinator(activity,app,io,Handler(Looper.getMainLooper()),{window},{},{})
        val state=ReflectionHelpers.getField<MutableState<AttachmentOutcomeState>>(flow,"state\$delegate")
        state.value=AttachmentOutcomeState(open=true,taskId="task",group=selected,snapshot=JSONObject(),file=JSONObject().put("id","artifact").put("sha256",sha).put("mimeType","text/plain"))
        flow.foreground(true)
        return canonical
    }
    private fun copies()=File(app.cacheDir,"outcome-views").listFiles{_,name->name.endsWith(".blob")}?.toList().orEmpty()
    private fun assertOriginal(){assertArrayEquals(bytes,repository.book.readVerifiedSnapshot(original.getString("id"),original.getString("sha256"),RunControl()));assertEquals(1,repository.book.list().length())}
    private fun idle(){Shadows.shadowOf(Looper.getMainLooper()).idle()}
    private class QueuedIo:Executor {
        private val jobs=ArrayDeque<Runnable>()
        var rejecting=false
        override fun execute(command:Runnable){if(rejecting)throw java.util.concurrent.RejectedExecutionException("Closed original IO fixture");jobs.addLast(command)}
        fun next(){val job=jobs.removeFirst();val worker=Thread(job,"view-preparation-fixture");worker.start();worker.join(10_000);check(!worker.isAlive){"Preparation did not finish"}}
        fun drain(){repeat(30){if(jobs.isNotEmpty())next();Shadows.shadowOf(Looper.getMainLooper()).idle();if(jobs.isEmpty())return};error("View cleanup IO did not settle")}
    }
}
