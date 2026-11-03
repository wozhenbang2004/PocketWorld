package dev.pocketengine.store;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class OwnerExecutionPolicyTest {
    @Test public void disableAndEnableNeverRestoreAnOldAdmission() throws Exception {
        JSONObject initial=OwnerExecutionPolicy.initial();
        assertEquals(1L,OwnerExecutionPolicy.requireEnabled(initial));
        JSONObject stopped=OwnerExecutionPolicy.change(initial,1L,false,100L);
        assertThrows(SecurityException.class,()->OwnerExecutionPolicy.requireEnabled(stopped));
        JSONObject enabled=OwnerExecutionPolicy.change(stopped,2L,true,101L);
        assertEquals(3L,OwnerExecutionPolicy.requireEnabled(enabled));
        assertThrows(SecurityException.class,()->OwnerExecutionPolicy.requireRevision(enabled,1L));
        assertFalse(enabled.getBoolean("resumeReplaysTasks"));
        assertFalse(enabled.getBoolean("cleanupConfirmed"));
        assertEquals(1L,initial.getLong("revision"));
    }
    @Test public void staleConfirmationCannotChangeOrAcknowledgeState() throws Exception {
        JSONObject stopped=OwnerExecutionPolicy.change(OwnerExecutionPolicy.initial(),1L,false,100L);
        assertThrows(SecurityException.class,()->OwnerExecutionPolicy.change(stopped,1L,true,101L));
        assertThrows(SecurityException.class,()->OwnerExecutionPolicy.change(stopped,1L,false,101L));
        assertEquals(2L,OwnerExecutionPolicy.change(stopped,2L,false,102L).getLong("revision"));
    }
    @Test public void clockRollbackCannotMoveThePolicyTimestampBack() throws Exception {
        JSONObject stopped=OwnerExecutionPolicy.change(OwnerExecutionPolicy.initial(),1L,false,100L);
        assertEquals(100L,OwnerExecutionPolicy.change(stopped,2L,true,20L).getLong("changedAt"));
        assertThrows(IllegalArgumentException.class,()->OwnerExecutionPolicy.change(stopped,2L,true,-1L));
    }
    @Test public void malformedOrReplayEnabledPolicyFailsClosed() throws Exception {
        for(Object bad:new Object[]{"1",1.0d,0L,-1L,JSONObject.NULL}) {
            JSONObject value=OwnerExecutionPolicy.initial().put("revision",bad);
            assertThrows(SecurityException.class,()->OwnerExecutionPolicy.validate(value));
        }
        for(Object bad:new Object[]{"true",1,JSONObject.NULL}) {
            JSONObject value=OwnerExecutionPolicy.initial().put("enabled",bad);
            assertThrows(SecurityException.class,()->OwnerExecutionPolicy.validate(value));
        }
        assertThrows(SecurityException.class,()->OwnerExecutionPolicy.validate(OwnerExecutionPolicy.initial().put("resumeReplaysTasks",true)));
        assertThrows(SecurityException.class,()->OwnerExecutionPolicy.validate(OwnerExecutionPolicy.initial().put("changedAt",0.5d)));
    }
    @Test public void exhaustedRevisionNeverWrapsToAnOlderAdmission() throws Exception {
        JSONObject last=OwnerExecutionPolicy.initial().put("revision",Long.MAX_VALUE);
        assertThrows(ArithmeticException.class,()->OwnerExecutionPolicy.change(last,Long.MAX_VALUE,false,1L));
        assertTrue(last.getBoolean("enabled"));
    }
    @Test public void validatedSnapshotCannotMutateStoredPolicy() throws Exception {
        JSONObject current=OwnerExecutionPolicy.initial();
        OwnerExecutionPolicy.validate(current).put("enabled",false);
        assertTrue(current.getBoolean("enabled"));
    }
}
