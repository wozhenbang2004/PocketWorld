package dev.pocketengine.features.ssh;

import static org.junit.Assert.*;
import dev.pocketengine.core.RunControl;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.Test;

/** Private projection and the additive original-control lifetime, no SSH/device success claim. */
public final class SshInvocationScopeTest {
    @Test public void diagnosticBodyCannotEnterGenericTaskProjection()throws Exception {
        JSONObject original=new JSONObject().put("status","completed").put("accepted",true).put("runId","original-run").put("doctor",new JSONObject().put("path","PRIVATE_PATH_FIXTURE")).put("stdout","PRIVATE_STDOUT_FIXTURE").put("message","PRIVATE_MESSAGE_FIXTURE").put("persistReport",true);
        JSONObject projected=SshPrivacy.doctorMetadata(original);assertEquals("completed",projected.getString("status"));assertEquals("original-run",projected.getString("runId"));assertTrue(projected.getBoolean("diagnosticBodyOmitted"));assertFalse(projected.getBoolean("persistReport"));assertTrue(projected.getBoolean("readOnly"));assertFalse(projected.toString().contains("PRIVATE_"));assertTrue(original.has("doctor"));
    }
    @Test public void scopedCheckerPreservesOriginalCheckerAndRemovesOnlyItsOwnRegistration()throws Exception {
        RunControl original=new RunControl();List<String> order=new ArrayList<>();original.setAuthorityCheck(()->order.add("original"));AutoCloseable first=original.retainAuthorityCheck(()->order.add("first"));AutoCloseable second=original.retainAuthorityCheck(()->order.add("second"));original.check();assertEquals(List.of("original","first","second"),order);order.clear();first.close();first.close();original.check();assertEquals(List.of("original","second"),order);second.close();order.clear();original.check();assertEquals(List.of("original"),order);
    }
    @Test public void revocationStopsTheSameOriginalControlAndItsResourceCallbackOnce()throws Exception {
        RunControl original=new RunControl();original.bindHostRun("task","run",3);AtomicBoolean allowed=new AtomicBoolean(true);AtomicInteger sourceChecks=new AtomicInteger(),released=new AtomicInteger();original.setAuthorityCheck(sourceChecks::incrementAndGet);original.onCancel(released::incrementAndGet);
        try(AutoCloseable retained=original.retainAuthorityCheck(()->{if(!allowed.get())throw new SecurityException("review withdrawn");})){original.check();allowed.set(false);assertThrows(InterruptedException.class,original::check);assertTrue(original.isCancelled());assertEquals(1,released.get());assertEquals(2,sourceChecks.get());assertThrows(InterruptedException.class,original::check);assertEquals(1,released.get());}
    }
}
