package dev.pocketengine.phone.access;

import android.app.Application;
import java.util.Set;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(manifest=Config.NONE,sdk=35,application=Application.class)
public final class AndroidPhoneAccessStoreNativeTest {
    @Test public void independentNativeStoreInstancesUseOneRevisionAndRejectLostUpdates()throws Exception {
        Application app=RuntimeEnvironment.getApplication();app.getSharedPreferences("phone_app_access",0).edit().clear().commit();AndroidPhoneAccessStore first=new AndroidPhoneAccessStore(app),second=new AndroidPhoneAccessStore(app);AppCopyKey key=new AppCopyKey("fixture.phone",10);
        first.setOwnerRule(key,false,true,0);assertEquals(1,second.current().revision());assertThrows(SecurityException.class,()->second.setOwnerRule(key,true,false,0));assertThrows(PhoneAccessPolicy.Denied.class,()->second.current().require(Set.of(key),true,PhoneAccessPolicy.Use.AUTOMATION));second.current().require(Set.of(key),true,PhoneAccessPolicy.Use.OWNER_SHARE);
    }
    @Test public void corruptPersistedPolicyDoesNotBecomeAnEmptyAllowList()throws Exception {
        Application app=RuntimeEnvironment.getApplication();app.getSharedPreferences("phone_app_access",0).edit().clear().putString("rules","not-json").putLong("revision",4).commit();AndroidPhoneAccessStore policy=new AndroidPhoneAccessStore(app);assertFalse(policy.current().available());assertThrows(SecurityException.class,()->policy.setOwnerRule(new AppCopyKey("fixture.phone",0),true,true,4));
    }
}
