package dev.pocketengine.phone;

import android.app.Application;
import dev.pocketengine.phone.access.AppCopyKey;
import dev.pocketengine.phone.setup.AndroidPhoneAudioRecovery;
import dev.pocketengine.phone.setup.PhoneAudioRecovery;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Same private ledger, explicitly scoped native command fixtures; no actual AppOps or other device. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class PhoneAudioCommandScopeTest {
    private static final class Commands implements AndroidPhoneAudioRecovery.Commands {
        final AppCopyKey app;String mode="allow";int writes;Commands(AppCopyKey app){this.app=app;}
        public JSONObject call(String command,JSONObject args)throws Exception {assertEquals(app.packageName(),args.getString("packageName"));assertEquals(app.userId(),args.getInt("userId"));if(command.equals("audio.set")){mode=args.getString("mode");writes++;}return new JSONObject().put("ok",true).put("mode",mode);}
    }
    @Test public void eachOriginalSessionSuppliesItsOwnCommandScopeForSilenceAndUndo()throws Exception {Application app=RuntimeEnvironment.getApplication();PhoneAudioRecovery ledger=AndroidPhoneAudioRecovery.get(app);AppCopyKey one=new AppCopyKey("example.audio",0),two=new AppCopyKey("example.audio",10);Commands a=new Commands(one),b=new Commands(two);AndroidPhoneAudioRecovery.withCommands(a,()->ledger.silence("a",one,true));AndroidPhoneAudioRecovery.withCommands(b,()->ledger.silence("b",two,true));assertEquals("ignore",a.mode);assertEquals("ignore",b.mode);assertEquals(1,AndroidPhoneAudioRecovery.withCommands(a,()->ledger.restoreOwner("a")).getInt("restored"));assertEquals("allow",a.mode);assertEquals("ignore",b.mode);assertEquals(1,AndroidPhoneAudioRecovery.withCommands(b,()->ledger.restoreOwner("b")).getInt("restored"));assertEquals("allow",b.mode);}
    @Test public void noImplicitFirstDisplayBackendExistsOutsideANativeScope()throws Exception {PhoneAudioRecovery ledger=AndroidPhoneAudioRecovery.get(RuntimeEnvironment.getApplication());assertThrows(SecurityException.class,()->ledger.silence("new",new AppCopyKey("example.audio",0),true));assertEquals(0,ledger.snapshot().getInt("pendingCount"));}
}
