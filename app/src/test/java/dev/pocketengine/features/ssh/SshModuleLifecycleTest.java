package dev.pocketengine.features.ssh;

import android.content.Context;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.store.SecretVault;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Native module admission/lifetime tests; no SSH server, device or credential is used. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=35)
public final class SshModuleLifecycleTest {
    private SshModule module;private SshProfile profile;private final AtomicInteger events=new AtomicInteger();
    @Before public void setUp()throws Exception {
        Context android=RuntimeEnvironment.getApplication();profile=new SshProfile(new JSONObject().put("id","fixture").put("name","fixture").put("host","unused.invalid").put("username","developer").put("workspace","/workspace").put("authType","key")
                .put("pinnedKey",Base64.getEncoder().encodeToString("fixture-host-key-1111111111111111".getBytes(StandardCharsets.UTF_8))));
        Files.writeString(new File(android.getFilesDir(),"ssh-profiles.json").toPath(),new JSONArray().put(profile.document()).toString());
        module=new SshModule(new FeatureContext(android,null,new SecretVault(android),event->events.incrementAndGet()));
    }
    @After public void close(){if(module!=null)module.close();}
    private RunControl control(){RunControl control=new RunControl();control.bindHostRun("task-a","run-a",7);return control;}
    private JSONObject input(String operation)throws Exception {return new JSONObject().put("profileId",profile.id).put("_profileBinding",profile.bindingHash()).put("hostTaskId","task-a").put("_operationId",operation).put("_hostRunId","run-a").put("_hostEpoch",7).put("principalId","owner").put("spaceId","private");}
    private void notRegistered(String id)throws Exception {assertEquals("not_active",module.handle("getOperation",new JSONObject().put("operationId",id)).getString("status"));assertEquals(0,module.snapshot().getJSONArray("operations").length());}
    @Test public void delayedOriginalCancellationNeverRegistersOrConnects()throws Exception {
        RunControl control=control();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);AtomicInteger checks=new AtomicInteger();
        control.setAuthorityCheck(()->{if(checks.incrementAndGet()==1){entered.countDown();assertTrue(release.await(2,TimeUnit.SECONDS));}});
        FutureTask<Exception> worker=new FutureTask<>(()->{try{module.invokeForHost("doctor",input("delayed"),control);return null;}catch(Exception expected){return expected;}});Thread thread=new Thread(worker,"ssh-delayed-admission-test");thread.start();
        try{assertTrue(entered.await(1,TimeUnit.SECONDS));Thread.sleep(400);control.cancel();release.countDown();assertTrue(worker.get(2,TimeUnit.SECONDS) instanceof InterruptedException);notRegistered("delayed");assertEquals(0,events.get());}
        finally{release.countDown();control.cancel();thread.join(2000);}
    }
    @Test public void wrongRunEpochPrivateScopeAndStaleProfileFailBeforeRegistration()throws Exception {
        for(JSONObject changed:new JSONObject[]{input("run").put("_hostRunId","other"),input("epoch").put("_hostEpoch",8),input("fraction").put("_hostEpoch",7.2),input("scope").put("spaceId","group"),input("binding").put("_profileBinding","stale")}){
            assertThrows(SecurityException.class,()->module.invokeForHost("doctor",changed,control()));notRegistered(changed.getString("_operationId"));
        }
    }
    @Test public void originalRunReceiptHasNoDuplicateTaskCompletionEventAndReleasesLocalLease()throws Exception {
        JSONObject result=module.invokeForHost("doctor",input("first"),control());assertEquals("failed",result.getString("status"));assertEquals("run-a",result.getString("runId"));assertEquals("first",result.getString("operationId"));assertEquals(7,result.getInt("epoch"));assertTrue(result.getBoolean("hostManaged"));assertEquals(0,events.get());
        JSONObject state=module.handle("getOperation",new JSONObject().put("operationId","first"));assertTrue(state.getBoolean("localResourcesClosed"));assertTrue(state.getLong("finishedAt")>0);assertFalse(state.has("doctor"));
        assertEquals("failed",module.invokeForHost("doctor",input("second"),control()).getString("status"));
        assertEquals("failed",module.handle("cancel",new JSONObject().put("operationId","first")).getString("status"));
    }
    @Test public void revokedLiveAuthorityIsRetainedInsteadOfCreatingAnotherControl()throws Exception {
        RunControl control=control();AtomicInteger checks=new AtomicInteger();control.setAuthorityCheck(()->{if(checks.incrementAndGet()>=4)throw new SecurityException("revoked");});
        JSONObject result=module.invokeForHost("doctor",input("revoked"),control);assertEquals("cancelled",result.getString("status"));assertTrue(control.isCancelled());assertEquals(0,events.get());
        assertEquals(0,module.snapshot().getJSONArray("operations").length());assertTrue(module.handle("getOperation",new JSONObject().put("operationId","revoked")).getBoolean("localResourcesClosed"));
    }
    @Test public void ptyConfigurationAndPausedControlsStayOutsideTheHostPort()throws Exception {
        for(String command:new String[]{"openPty","writePty","saveProfile","approveHostKey","cancel"})assertThrows(SecurityException.class,()->module.invokeForHost(command,input("denied"),control()));
        RunControl paused=control();paused.pause();assertThrows(SecurityException.class,()->module.invokeForHost("doctor",input("paused"),paused));notRegistered("paused");
    }
    @Test public void moduleCloseRejectsLaterHostRegistration()throws Exception {module.close();assertThrows(IllegalStateException.class,()->module.invokeForHost("doctor",input("closed"),control()));assertEquals(0,module.snapshot().getJSONArray("operations").length());}
}
