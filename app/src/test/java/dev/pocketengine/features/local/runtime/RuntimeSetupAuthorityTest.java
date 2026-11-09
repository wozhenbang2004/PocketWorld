package dev.pocketengine.features.local.runtime;

import android.app.Application;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.store.TaskStore;
import java.nio.file.Files;
import java.nio.file.Path;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import static org.junit.Assert.*;

/** Real SQL, typed native reviews and existing runtime admission. The synthetic APK engines are
 * never executed or described as ARM64 probes; the host group must load the real JNI library. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public final class RuntimeSetupAuthorityTest {
    private TaskStore tasks;private ManagedProjectRuntime runtime;private RuntimeSetupHost host;private Path root,proot;
    @Before public void create()throws Exception {
        assertTrue("Run the registered native host group",new NativeProcessSupervisor().available());
        root=Files.createTempDirectory("runtime-owner-setup-");Path nativeDir=Files.createDirectory(root.resolve("native"));
        proot=Files.writeString(nativeDir.resolve("libproot.so"),"synthetic engine, never exec");Path loader=Files.writeString(nativeDir.resolve("libloader.so"),"synthetic loader, never exec");
        assertTrue(proot.toFile().setExecutable(true));assertTrue(loader.toFile().setExecutable(true));
        JSONObject descriptor=AndroidRuntimePreparationTest.catalog();descriptor.getJSONObject("engine").put("prootSha256",RuntimeFiles.sha256(proot.toFile())).put("loaderSha256",RuntimeFiles.sha256(loader.toFile()));
        runtime=ManagedProjectRuntime.createAndroid(root.resolve("runtime").toFile(),nativeDir.toFile(),new AndroidRuntimeCatalog(descriptor));
        tasks=new TaskStore(org.robolectric.RuntimeEnvironment.getApplication());host=new RuntimeSetupHost(tasks,runtime,root.resolve("owner").toFile(),ignored->{});
    }
    @After public void close()throws Exception {
        if(host!=null)host.close();if(runtime!=null)runtime.close();if(tasks!=null)tasks.close();
        if(root!=null)try(var paths=Files.walk(root)){for(Path path:paths.sorted(java.util.Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}
    }
    private RunControl original(String id)throws Exception {RunControl control=new RunControl();control.bindHostRun(id,id,1);control.setAuthorityCheck(()->tasks.requireRuntimeSetupCurrent(id,control));return control;}
    private SQLiteDatabase database()throws Exception {var field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);return (SQLiteDatabase)field.get(tasks);}
    private void noInstallerJobs()throws Exception {Path jobs=root.resolve("runtime/managed-process/jobs");if(Files.isDirectory(jobs))try(var rows=Files.list(jobs)){assertEquals(0,rows.filter(Files::isDirectory).count());}}

    @Test public void freshOwnerReadAndReviewNeedNoProjectAndCreateNoExecution()throws Exception {
        JSONObject view=host.read();assertEquals("not_prepared",view.getString("status"));assertTrue(view.getBoolean("canPrepare"));assertNull(tasks.runtimeSetupForOwner(""));
        try(var review=host.review("prepare")){
            JSONObject shown=review.view();assertFalse(shown.getBoolean("executionAuthorized"));assertEquals(2,shown.getLong("downloadBytes"));assertTrue(shown.getBoolean("additionalDownloads"));assertFalse(shown.has("projectId"));assertNull(tasks.runtimeSetupForOwner(""));
        }
        noInstallerJobs();
    }
    @Test public void originalReviewCreatesOneApprovedOperationWithNoModelAuthority()throws Exception {
        var first=host.review("prepare").confirm();var second=host.review("repair").confirm();String id=first.requestId();
        JSONObject admitted=tasks.beginRuntimeSetupForOwner(first);assertFalse(admitted.getBoolean("knownOperation"));assertEquals("tool",admitted.getJSONObject("task").getString("kind"));
        assertFalse(admitted.getJSONObject("task").has("developmentContext"));assertFalse(admitted.getJSONObject("task").has("conversationId"));
        assertTrue(tasks.beginRuntimeSetupForOwner(first).getBoolean("knownOperation"));JSONObject duplicate=tasks.beginRuntimeSetupForOwner(second);assertTrue(duplicate.getBoolean("knownOperation"));assertEquals(id,duplicate.getJSONObject("task").getString("id"));
        try(var row=database().rawQuery("SELECT grant_json FROM runs WHERE task_id=? AND run_id=?",new String[]{id,id})){
            assertTrue(row.moveToFirst());JSONObject grant=new JSONObject(row.getString(0));assertEquals(0,grant.getJSONArray("allowedInferencePurposes").length());assertEquals(1,grant.getJSONArray("tools").length());assertEquals(RuntimeSetupHost.TOOL,grant.getJSONArray("tools").getString(0));
        }
        RunControl control=original(id);tasks.claimRuntimeSetupForOwner(id,control);
        assertEquals("unknown",tasks.runtimeSetupForOwner(id).getJSONObject("operation").getString("state"));
        assertThrows(SecurityException.class,()->tasks.claimRuntimeSetupForOwner(id,control));assertThrows(SecurityException.class,()->tasks.startAttempt(id,"capabilities",10));
        assertThrows(SecurityException.class,()->tasks.resolveOperation(id,id,"not_occurred"));assertThrows(SecurityException.class,()->tasks.reconcileExternalTask(id,"not_occurred","fixture"));
        noInstallerJobs();
    }
    @Test public void closedReviewAndChangedEngineCannotLaunch()throws Exception {
        var review=host.review("prepare");var confirmed=review.confirm();review.close();assertThrows(SecurityException.class,()->host.start(confirmed));assertNull(tasks.runtimeSetupForOwner(""));
        var changed=host.review("prepare").confirm();Files.writeString(proot,"changed after owner review");JSONObject result=host.start(changed);
        assertEquals("failed",result.getString("status"));assertTrue(result.getJSONObject("operation").getJSONObject("receipt").getBoolean("notDispatched"));
        assertEquals("runtime_apk_engine_or_supervisor_unavailable",result.getJSONObject("operation").getJSONObject("error").getString("code"));noInstallerJobs();
    }
    @Test public void restartBeforeClaimIsRetryableButUnknownClaimIsNeverReplayed()throws Exception {
        var prepared=host.review("prepare").confirm();tasks.beginRuntimeSetupForOwner(prepared);
        JSONObject safe=host.read(prepared.requestId(),0,32768);assertEquals("interrupted",safe.getString("status"));assertTrue(safe.getBoolean("canRetry"));
        var dispatched=host.review("prepare").confirm();String id=dispatched.requestId();tasks.beginRuntimeSetupForOwner(dispatched);tasks.claimRuntimeSetupForOwner(id,original(id));
        JSONObject unknown=host.start(dispatched);assertTrue(unknown.getBoolean("knownOperation"));assertEquals("interrupted",unknown.getString("status"));assertFalse(unknown.getBoolean("canPrepare"));assertFalse(unknown.getBoolean("canRetry"));
        assertEquals("unknown",tasks.runtimeSetupForOwner(id).getJSONObject("operation").getString("state"));noInstallerJobs();
    }
    @Test public void ownerStopStillWorksWhenGlobalExecutionWasDisabledAndCannotChangeIdentity()throws Exception {
        var confirmed=host.review("prepare").confirm();String id=confirmed.requestId();tasks.beginRuntimeSetupForOwner(confirmed);RunControl control=original(id);tasks.claimRuntimeSetupForOwner(id,control);
        JSONObject policy=tasks.ownerExecutionState();tasks.setExecutionEnabledForOwner(policy.getLong("revision"),false);assertThrows(SecurityException.class,control::check);
        assertThrows(SecurityException.class,()->host.cancel(id,"other-run",1));JSONObject stopped=host.cancel(id,id,1);
        assertFalse(stopped.getBoolean("canRetry"));assertTrue(tasks.runtimeSetupForOwner(id).getJSONObject("task").getBoolean("cancelRequested"));noInstallerJobs();
    }
}
