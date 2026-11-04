package dev.pocketengine.bootstrap;

import android.content.pm.PackageInfo;
import dev.pocketengine.EngineApplication;
import dev.pocketengine.store.DatabaseFilePreservation;
import dev.pocketengine.store.TaskDatabaseMaintenance;
import dev.pocketengine.store.TaskStore;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.SQLiteMode;
import org.robolectric.shadows.ShadowApplication;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** Actual Engine admission and maintenance record, with only bootstrap worker pickup held.
 * Does not claim that the queued SQLite reopening or business initialization completes. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=EngineApplication.class,
        shadows=BootstrapOriginalRetryNativeTest.HeldStartup.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class BootstrapOriginalRetryNativeTest {
    @Implements(EngineApplication.class)
    public static final class HeldStartup extends ShadowApplication {
        @Implementation protected void onCreate() { }
    }

    /** Delays the original Runnable; it does not replace the production permit or admission checks. */
    private static final class Queue extends AbstractExecutorService {
        final List<Runnable> pending=new ArrayList<>();boolean closed;
        @Override public void execute(Runnable command){if(closed)throw new java.util.concurrent.RejectedExecutionException();pending.add(command);}
        @Override public void shutdown(){closed=true;}
        @Override public List<Runnable> shutdownNow(){closed=true;List<Runnable> old=new ArrayList<>(pending);pending.clear();return old;}
        @Override public boolean isShutdown(){return closed;}
        @Override public boolean isTerminated(){return closed&&pending.isEmpty();}
        @Override public boolean awaitTermination(long timeout,TimeUnit unit){return isTerminated();}
    }

    private EngineApplication app;
    private BootstrapStatus status;
    private Queue queue;
    private TaskDatabaseMaintenance maintenance;
    private final BooleanSupplier owner=()->true;
    private File recordFile;

    private Object get(String name)throws Exception {Field field=EngineApplication.class.getDeclaredField(name);field.setAccessible(true);return field.get(app);}
    private void set(String name,Object value)throws Exception {Field field=EngineApplication.class.getDeclaredField(name);field.setAccessible(true);field.set(app,value);}
    private Object invoke(String name,Class<?>[] parameters,Object... arguments)throws Exception {
        Method method=EngineApplication.class.getDeclaredMethod(name,parameters);method.setAccessible(true);
        try{return method.invoke(app,arguments);}catch(InvocationTargetException wrapper){Throwable failure=wrapper.getCause();if(failure instanceof Exception exception)throw exception;if(failure instanceof Error error)throw error;throw new AssertionError(failure);}
    }

    @Before public void preserveARealClosedOriginalThenReloadTheColdCoordinator()throws Exception {
        app=(EngineApplication)RuntimeEnvironment.getApplication();status=(BootstrapStatus)get("bootstrap");
        assertNull(app.tasks);assertNull(app.settings);assertFalse(app.businessReady());
        PackageInfo installed=new PackageInfo();installed.packageName=app.getPackageName();installed.applicationInfo=app.getApplicationInfo();
        installed.applicationInfo.uid=android.os.Process.myUid();installed.versionName="bootstrap-fixture";installed.setLongVersionCode(1);
        shadowOf(app.getPackageManager()).installPackage(installed);
        TaskStore original=new TaskStore(app,()->false,(stage,schema)->{});
        original.closeForMaintenance();assertTrue(original.closedForMaintenance());
        long initial=status.beginInitial();status.failed(initial,"database_locked","fixture original lock","preserve original",true);
        TaskDatabaseMaintenance prior=new TaskDatabaseMaintenance(app,new TaskDatabaseMaintenance.Host(){
            public long generation(){return status.generation();}
            public boolean quiesce()throws Exception{return (Boolean)invoke("quiesceOriginalDatabaseForMaintenance",new Class<?>[0]);}
        });
        set("databaseMaintenance",prior);prior.failure("database_locked","fixture original lock",TaskStore.DATABASE_SCHEMA);
        prior.command("repair.preserve",new JSONObject().put("expectedGeneration",initial).put("requestId","preserve-original-fixture").put("repairId",JSONObject.NULL),owner);
        assertEquals("preserved",prior.snapshot().getString("phase"));assertTrue(prior.snapshot().getBoolean("preservationVerified"));
        recordFile=new File(app.getNoBackupFilesDir(),"database-maintenance/current.json");assertTrue(recordFile.isFile());
        // Reconstruct process-local state only. The new coordinator must read the actual saved record.
        status=new BootstrapStatus(TaskStore.DATABASE_SCHEMA);set("bootstrap",status);set("databaseMaintenance",null);
        ((ExecutorService)get("bootstrapWorker")).shutdownNow();queue=new Queue();set("bootstrapWorker",queue);
        String bytes=DatabaseFilePreservation.inspect(app.getDatabasePath(TaskStore.DATABASE_NAME)).fingerprint();
        long cold=status.beginInitial();invoke("initializeOriginalBusiness",new Class<?>[]{long.class,TaskDatabaseMaintenance.OriginalReadPermit.class},cold,null);
        maintenance=(TaskDatabaseMaintenance)get("databaseMaintenance");assertNotNull(maintenance);assertNotSame(prior,maintenance);
        assertEquals("preserved",maintenance.snapshot().getString("phase"));assertTrue(maintenance.snapshot().getBoolean("originalRetryAvailable"));
        assertEquals(bytes,DatabaseFilePreservation.inspect(app.getDatabasePath(TaskStore.DATABASE_NAME)).fingerprint());
        assertNull(app.tasks);assertNull(app.settings);assertFalse(app.businessReady());assertTrue(queue.pending.isEmpty());
    }

    @After public void closeFixtureOnly(){if(app!=null)app.onTerminate();}

    private JSONObject retry(String request)throws Exception {
        JSONObject saved=maintenance.snapshot();return new JSONObject().put("expectedGeneration",status.generation()).put("requestId",request)
                .put("repairId",saved.getString("repairId")).put("sourceFingerprint",saved.getString("sourceFingerprint")).put("backupFingerprint",saved.getString("backupFingerprint"));
    }

    @Test public void coldPreservedOriginalAllowsOneExplicitRetryWithoutAutomaticOpening()throws Exception {
        assertTrue(app.bootstrapState().getBoolean("retryAvailable"));long cold=status.generation();JSONObject request=retry("cold-original-retry-1");
        app.bootstrapOwnerCommand("bootstrap.retry",request,owner);
        assertEquals(cold+1,status.generation());assertEquals(1,queue.pending.size());
        assertEquals("original_retry_prepared",maintenance.snapshot().getString("phase"));assertTrue(maintenance.observesOriginalRetry(request));
        assertNull(app.tasks);assertFalse(app.businessReady());
        app.bootstrapOwnerCommand("bootstrap.retry",new JSONObject(request.toString()),owner);
        assertEquals(1,queue.pending.size());assertEquals(cold+1,status.generation());
    }

    @Test public void unavailableBootstrapRetryCannotWriteAOneUsePermitBeforeRejection()throws Exception {
        status.maintenance("quiescence_unconfirmed","original boundary unavailable","read original",false);
        JSONObject request=retry("blocked-original-retry-1");byte[] before=Files.readAllBytes(recordFile.toPath());long generation=status.generation();
        assertFalse(app.bootstrapState().getBoolean("retryAvailable"));
        assertThrows(SecurityException.class,()->app.bootstrapOwnerCommand("bootstrap.retry",request,owner));
        assertArrayEquals(before,Files.readAllBytes(recordFile.toPath()));assertEquals("preserved",maintenance.snapshot().getString("phase"));
        assertFalse(maintenance.observesOriginalRetry(request));assertEquals(generation,status.generation());assertTrue(queue.pending.isEmpty());
    }
}
