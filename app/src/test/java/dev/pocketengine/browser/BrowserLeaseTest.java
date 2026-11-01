package dev.pocketengine.browser;

import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class BrowserLeaseTest {
    private JSONObject claim(String run)throws Exception{return new JSONObject().put("taskId","task-1").put("runId",run).put("principalId","owner").put("spaceId","private");}
    private JSONObject operation(JSONObject acquired)throws Exception{return new JSONObject().put("taskId",acquired.getString("taskId")).put("runId",acquired.getString("runId")).put("leaseId",acquired.getString("leaseId"));}
    @Test public void resourceIsExclusiveAndAcquisitionIsIdempotent()throws Exception{
        BrowserLease leases=new BrowserLease();JSONObject first=leases.acquire(claim("run-1"));
        assertEquals(first.getString("leaseId"),leases.acquire(claim("run-1")).getString("leaseId"));
        assertThrows(IllegalStateException.class,()->leases.acquire(claim("run-2")));
        assertEquals(first.getLong("epoch"),leases.require(operation(first)));
    }
    @Test public void ownerAndProfileMustMatchHostGrant()throws Exception{
        BrowserLease leases=new BrowserLease();
        assertThrows(SecurityException.class,()->leases.acquire(claim("run-1").put("principalId","group-member")));
        assertThrows(SecurityException.class,()->leases.acquire(claim("run-1").put("spaceId","group-1")));
        assertThrows(IllegalArgumentException.class,()->leases.acquire(claim("run-1").put("profileId","chrome")));
        assertFalse(leases.snapshot().getBoolean("active"));
    }
    @Test public void cancellationFencesOldEpochAndCredentials()throws Exception{
        BrowserLease leases=new BrowserLease();JSONObject first=leases.acquire(claim("run-1"));
        leases.release(operation(first),"cancel");JSONObject second=leases.acquire(claim("run-2"));
        assertFalse(leases.current(first.getLong("epoch")));assertTrue(leases.current(second.getLong("epoch")));
        assertThrows(SecurityException.class,()->leases.require(operation(first)));
        assertThrows(SecurityException.class,()->leases.release(operation(first),"cancel"));
        assertEquals(second.getString("leaseId"),leases.snapshot().getString("leaseId"));
    }
    @Test public void expiredLeaseCannotExecuteAndCanBeReclaimed()throws Exception{
        AtomicLong now=new AtomicLong(100);BrowserLease leases=new BrowserLease(now::get);JSONObject first=leases.acquire(claim("run-1"));
        now.set(600100);assertFalse(leases.current(first.getLong("epoch")));
        assertThrows(SecurityException.class,()->leases.require(operation(first)));
        assertTrue(leases.acquire(claim("run-2")).getBoolean("active"));
    }
    @Test public void sameLeaseCannotBeUsedByDifferentRun()throws Exception{
        BrowserLease leases=new BrowserLease();JSONObject acquired=leases.acquire(claim("run-1"));
        assertThrows(SecurityException.class,()->leases.require(operation(acquired).put("runId","run-2")));
    }
    @Test public void expiryEmitsOriginalTaskIdentityExactlyOnce()throws Exception{
        AtomicLong now=new AtomicLong(100);BrowserLease leases=new BrowserLease(now::get);java.util.List<JSONObject> events=new java.util.ArrayList<>();leases.setExpirationListener(events::add);
        JSONObject acquired=leases.acquire(claim("run-1"));now.set(600100);assertFalse(leases.snapshot().getBoolean("active"));
        leases.snapshot();assertFalse(leases.current(acquired.getLong("epoch")));assertEquals(1,events.size());JSONObject expired=events.get(0);
        assertEquals(acquired.getString("taskId"),expired.getString("taskId"));assertEquals(acquired.getString("runId"),expired.getString("runId"));assertEquals(acquired.getString("leaseId"),expired.getString("leaseId"));assertEquals("expired",expired.getString("status"));assertFalse(expired.getBoolean("active"));
    }
    @Test public void nativeProfileSwitchFencesOldLeaseEvenWhenReturningToTheSameProfile()throws Exception {
        BrowserProfileRegistry profiles=new BrowserProfileRegistry(new java.io.File(java.nio.file.Files.createTempDirectory("browser-lease-profiles-").toFile(),"profiles.json"));String managed=profiles.create("独立资料",profiles.revision(),true).getString("id");profiles.select(managed,profiles.revision(),true);BrowserLease lease=new BrowserLease();BrowserProfileRegistry.Session original=profiles.openSession(true);lease.bindProfile(original);assertThrows(SecurityException.class,()->lease.acquire(claim("missing-profile-binding")));JSONObject acquired=lease.acquire(claim("run-1").put("profileId",original.profileId).put("profileRevision",original.profileRevision).put("browserSessionId",original.sessionId));
        profiles.select(original.profileId,profiles.revision(),true);BrowserProfileRegistry.Session replacement=profiles.openSession(true);lease.bindProfile(replacement);assertFalse(lease.current(acquired.getLong("epoch")));assertThrows(SecurityException.class,()->lease.require(operation(acquired)));
        JSONObject current=lease.acquire(claim("run-2").put("profileId",replacement.profileId).put("profileRevision",replacement.profileRevision).put("browserSessionId",replacement.sessionId));assertEquals(replacement.sessionId,current.getString("browserSessionId"));assertThrows(SecurityException.class,()->lease.require(operation(current).put("browserSessionId",original.sessionId)));assertThrows(SecurityException.class,()->lease.require(operation(current).put("profileRevision",0)));
    }
}
