package dev.pocketengine.core;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;
import static org.junit.Assert.*;

public final class RunControlRecoveryTest {
    @Test public void recoveryRetainsExactIdentityAndCannotUpgrade() throws Exception {
        RunControl original=RunControl.forOriginalRecovery("task-a","run-a",7);
        assertTrue(original.recoveryOnly());
        assertTrue(original.matchesHostRun("task-a","run-a",7));
        assertFalse(original.matchesHostRun("task-b","run-a",7));
        assertFalse(original.matchesHostRun("task-a","run-b",7));
        assertFalse(original.matchesHostRun("task-a","run-a",8));
        assertThrows(SecurityException.class,original::requireExecution);
        assertThrows(SecurityException.class,()->original.bindTask("task-a"));
        assertThrows(SecurityException.class,()->original.bindHostRun("task-a","run-a",7));
        AtomicInteger effects=new AtomicInteger();
        assertThrows(SecurityException.class,()->original.dispatchAtPauseRevision(original.pauseRevision(),effects::incrementAndGet));
        original.pause();
        assertThrows(SecurityException.class,()->original.dispatchAtPauseState(original.pauseRevision(),true,effects::incrementAndGet));
        original.resume();original.check();
        assertEquals(0,effects.get());
        assertThrows(SecurityException.class,original::requireExecution);
    }
    @Test public void recoveryRequiresCompleteOriginalIdentity() {
        assertThrows(IllegalArgumentException.class,()->RunControl.forOriginalRecovery("","run",1));
        assertThrows(IllegalArgumentException.class,()->RunControl.forOriginalRecovery("task","",1));
        assertThrows(IllegalArgumentException.class,()->RunControl.forOriginalRecovery("task","run",0));
    }
    @Test public void recoveryDoesNotEnterTheNewExecutionAdmission() throws Exception {
        AtomicInteger calls=new AtomicInteger();
        try(AutoCloseable fixture=admission((c,t,r,e)->{calls.incrementAndGet();throw new SecurityException("disabled");})) {
            RunControl recovery=RunControl.forOriginalRecovery("task","historical-run",2);
            recovery.check();recovery.checkpoint();
            assertEquals(0,calls.get());
            assertThrows(SecurityException.class,()->new RunControl().bindTask("task"));
            assertEquals(1,calls.get());
        }
    }
    @Test public void recoveryCannotReplaceTheRetainedOriginalSourceCheck() throws Exception {
        RunControl recovery=RunControl.forOriginalRecovery("task","run",1);
        AtomicBoolean revoked=new AtomicBoolean();
        recovery.retainAuthorityCheck(()->{if(revoked.get())throw new SecurityException("source changed");});
        recovery.setAuthorityCheck(()->{});recovery.check();
        revoked.set(true);recovery.setAuthorityCheck(()->{});
        assertThrows(InterruptedException.class,recovery::check);
        assertTrue(recovery.isCancelled());
    }
    @Test public void parentCancellationStillStopsOriginalRecovery() throws Exception {
        RunControl parent=new RunControl(),recovery=RunControl.forOriginalRecovery("task","run",1);
        recovery.retainAuthorityCheck(parent::check);
        parent.onCancel(recovery::cancel);recovery.check();parent.cancel();
        assertThrows(InterruptedException.class,recovery::check);
        assertTrue(recovery.isCancelled());
    }
    @Test public void reEnableCannotReviveAnOldExecutionControl() throws Exception {
        AtomicBoolean enabled=new AtomicBoolean(true);AtomicLong revision=new AtomicLong(1L);
        try(AutoCloseable fixture=admission((c,t,r,e)->{
            assertFalse(Thread.holdsLock(c));
            if(!enabled.get())throw new SecurityException("disabled");
            long admitted=revision.get();
            return ()->{if(!enabled.get()||revision.get()!=admitted)throw new SecurityException("old admission");};
        })) {
            RunControl old=new RunControl();old.bindHostRun("task","run",1);old.check();
            enabled.set(false);revision.incrementAndGet();
            assertThrows(InterruptedException.class,old::check);
            enabled.set(true);revision.incrementAndGet();old.resume();
            assertThrows(InterruptedException.class,old::check);
            RunControl fresh=new RunControl();fresh.bindHostRun("new-task","new-run",1);fresh.check();
            assertFalse(fresh.recoveryOnly());
        }
    }
    @Test public void policyChangeFromAuthorityCallbackCannotSlipIntoNativeDispatch() throws Exception {
        AtomicBoolean enabled=new AtomicBoolean(true);AtomicInteger effects=new AtomicInteger();
        try(AutoCloseable fixture=admission((c,t,r,e)->()->{if(!enabled.get())throw new SecurityException("disabled");})) {
            RunControl original=new RunControl();original.bindHostRun("task","run",1);
            original.setAuthorityCheck(()->enabled.set(false));
            assertThrows(SecurityException.class,()->original.dispatchAtPauseRevision(0,effects::incrementAndGet));
            assertEquals(0,effects.get());
        }
    }
    private static AutoCloseable admission(RunControl.HostAdmission gate) throws Exception {
        Field field=RunControl.class.getDeclaredField("hostAdmission");field.setAccessible(true);
        Object previous=field.get(null);RunControl.installHostAdmission(gate);
        return ()->field.set(null,previous);
    }
}
