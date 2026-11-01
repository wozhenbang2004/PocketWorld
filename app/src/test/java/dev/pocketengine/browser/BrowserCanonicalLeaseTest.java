package dev.pocketengine.browser;
import static org.junit.Assert.*;
import java.util.concurrent.atomic.*;
import org.json.*;
import org.junit.Test;
public final class BrowserCanonicalLeaseTest {
 JSONObject claim()throws Exception{return new JSONObject().put("taskId","task").put("runId","run").put("principalId","owner").put("spaceId","private");}
 @Test public void nativeUntilCompleteSurvivesTenMinutesWithoutChangingIdentity()throws Exception {AtomicLong now=new AtomicLong(1);BrowserLease l=new BrowserLease(now::get);JSONObject a=l.acquireTask(claim(),()->Long.MAX_VALUE);now.set(1_800_001);assertEquals(a.getLong("epoch"),l.require(a));assertEquals(a.getString("leaseId"),l.snapshot().getString("leaseId"));}
 @Test public void canonicalRenewalAndRevocationUseSameLeaseAndCannotResurrect()throws Exception {AtomicLong now=new AtomicLong(1),deadline=new AtomicLong(1000);AtomicBoolean revoked=new AtomicBoolean();BrowserLease l=new BrowserLease(now::get);JSONObject a=l.acquireTask(claim(),()->{if(revoked.get())throw new SecurityException();return deadline.get();});deadline.set(3000);now.set(1500);assertEquals(a.getLong("epoch"),l.require(a));revoked.set(true);assertThrows(SecurityException.class,()->l.require(a));revoked.set(false);assertFalse(l.snapshot().getBoolean("active"));}
 @Test public void processReservationRenewalCannotRecreateAnExpiredOrReleasedToken(){AtomicLong now=new AtomicLong(1);dev.pocketengine.core.ExecutionResources r=new dev.pocketengine.core.ExecutionResources(now::get);assertTrue(r.acquireBrowser("task:run:1",1000));assertTrue(r.renewBrowser("task:run:1",5000));assertFalse(r.renewBrowser("other:run:1",5000));r.releaseBrowser("task:run:1");assertFalse(r.renewBrowser("task:run:1",Long.MAX_VALUE));}
}
