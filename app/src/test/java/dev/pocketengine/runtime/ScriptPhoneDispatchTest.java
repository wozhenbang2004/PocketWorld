package dev.pocketengine.runtime;

import dev.pocketengine.core.RunControl;
import java.util.Set;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Native registration identity only; the original TaskStore/Runner tests cover dispatch. */
public final class ScriptPhoneDispatchTest {
    private static RunControl control(){RunControl c=new RunControl();c.bindHostRun("task","native-run",7);return c;}
    private static final ScriptPhoneDispatch.Actions ACTIONS=new ScriptPhoneDispatch.Actions(){
        public Set<String> allowedTools(){return Set.of("phone_observe");}
        public JSONObject invoke(String name,JSONObject args){throw new AssertionError("Registration must not dispatch");}
    };
    @Test public void registrationUsesTheOriginalControlAndItsNativeRunIdentity()throws Exception {
        RunControl original=control();boolean[] closed={false};
        try(AutoCloseable installation=ScriptPhoneDispatch.installFactory((task,run,epoch,parent,actual,actions)->{
            assertEquals("task",task);assertEquals("native-run",run);assertEquals(7,epoch);assertEquals("prepared-parent",parent);assertSame(original,actual);assertSame(ACTIONS,actions);return ()->closed[0]=true;
        })){try(AutoCloseable registration=ScriptPhoneDispatch.register("task","prepared-parent",original,ACTIONS)){assertFalse(closed[0]);}assertTrue(closed[0]);}
        assertFalse(ScriptPhoneDispatch.available());
    }
    @Test public void wrongTaskUnboundRunPauseAndCancelFailBeforeTheFactory()throws Exception {
        try(AutoCloseable installation=ScriptPhoneDispatch.installFactory((task,run,epoch,parent,actual,actions)->{throw new AssertionError("Rejected identities must never register");})){
            assertThrows(SecurityException.class,()->ScriptPhoneDispatch.register("other","parent",control(),ACTIONS));
            assertThrows(SecurityException.class,()->ScriptPhoneDispatch.register("task","parent",new RunControl(),ACTIONS));
            assertThrows(SecurityException.class,()->ScriptPhoneDispatch.register("task","",control(),ACTIONS));
            RunControl paused=control();paused.pause();assertThrows(SecurityException.class,()->ScriptPhoneDispatch.register("task","parent",paused,ACTIONS));
            RunControl cancelled=control();cancelled.cancel();assertThrows(InterruptedException.class,()->ScriptPhoneDispatch.register("task","parent",cancelled,ACTIONS));
        }
    }
    @Test public void oldInstallationCloseCannotRemoveANewerNativeFactory()throws Exception {
        AutoCloseable first=ScriptPhoneDispatch.installFactory((task,run,epoch,parent,actual,actions)->()->{});
        try(AutoCloseable second=ScriptPhoneDispatch.installFactory((task,run,epoch,parent,actual,actions)->()->{})){
            first.close();assertTrue(ScriptPhoneDispatch.available());try(AutoCloseable registration=ScriptPhoneDispatch.register("task","parent",control(),ACTIONS)){}
        }assertFalse(ScriptPhoneDispatch.available());
    }
}
