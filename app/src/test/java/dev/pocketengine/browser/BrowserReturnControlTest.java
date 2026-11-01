package dev.pocketengine.browser;

import dev.pocketengine.core.RunControl;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import static org.junit.Assert.*;

/** Pure host race regression; the original resume() is the baseline when bounded resume is absent. */
public final class BrowserReturnControlTest {
    private static void resume(RunControl control,long revision)throws Exception {
        try{RunControl.class.getMethod("resumeAtPauseRevision",long.class).invoke(control,revision);}
        catch(NoSuchMethodException baseline){control.resume();}
        catch(InvocationTargetException failure){Throwable cause=failure.getCause();if(cause instanceof Exception)throw (Exception)cause;throw new AssertionError(cause);}
    }
    private RunControl control(){RunControl control=new RunControl();control.bindHostRun("original-task","original-run",3);return control;}
    @Test public void secondOwnerPauseCannotBeResumedByAnOlderReturnConfirmation()throws Exception {
        RunControl control=control();control.pause();long confirmed=control.pauseRevision();control.pause();
        assertThrows(SecurityException.class,()->resume(control,confirmed));assertTrue(control.isPaused());assertTrue(control.matchesHostRun("original-task","original-run",3));
    }
    @Test public void cancellationDuringReturnCannotResumeItsCancelledRun()throws Exception {
        RunControl control=control();control.pause();long confirmed=control.pauseRevision();control.cancel();
        assertThrows(InterruptedException.class,()->resume(control,confirmed));assertTrue(control.isPaused());assertTrue(control.isCancelled());
    }
    @Test public void authorityIsCheckedBeforeMonitorAndAConcurrentPauseStillWins()throws Exception {
        RunControl control=control();control.pause();long confirmed=control.pauseRevision();CountDownLatch paused=new CountDownLatch(1);
        control.setAuthorityCheck(()->{Thread owner=new Thread(()->{control.pause();paused.countDown();});owner.start();if(!paused.await(1,TimeUnit.SECONDS))throw new IllegalStateException("Authority callback ran while holding the pause monitor");owner.join(1000);});
        assertThrows(SecurityException.class,()->resume(control,confirmed));assertTrue(control.isPaused());assertTrue(paused.await(1,TimeUnit.SECONDS));
    }
    @Test public void exactLiveConfirmationResumesOnlyTheSameBoundRun()throws Exception {
        RunControl control=control();control.pause();long confirmed=control.pauseRevision();resume(control,confirmed);assertFalse(control.isPaused());assertTrue(control.matchesHostRun("original-task","original-run",3));assertThrows(SecurityException.class,()->resume(control,confirmed));
    }
}
