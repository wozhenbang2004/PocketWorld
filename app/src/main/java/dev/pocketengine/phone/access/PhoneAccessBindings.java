package dev.pocketengine.phone.access;

import android.content.Context;
import android.view.Display;
import dev.pocketengine.core.PhonePort;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.phone.PhoneAccessibilityService;
import dev.pocketengine.phone.display.BackgroundPhonePort;
import dev.pocketengine.phone.display.DisplayModule;

/** Binds the original physical port and live owner policy without introducing another runner. */
public final class PhoneAccessBindings {
    private PhoneAccessBindings() { }
    public static PhonePort agent(Context context,PhonePort physical,DisplayModule display,RunControl original)throws Exception {
        AndroidPhoneAccessStore policy=new AndroidPhoneAccessStore(context);PhoneAccessPolicy.State captured=policy.current();
        if(!captured.available())throw new SecurityException("app_policy_unavailable");
        if(captured.rules().isEmpty()){
            // Existing unconfigured main-screen operation does not acquire a new shell permission.
            // Any later owner edit invalidates this exact binding, including old observations/history.
            original.retainAuthorityCheck(()->{var live=policy.current();if(!live.available()||live.revision()!=captured.revision())throw new SecurityException("app_policy_changed");});
            return physical;
        }
        PhoneOwnerPackages.Probe probe=physical instanceof BackgroundPhonePort?((BackgroundPhonePort)physical)::ownerPackages:
                live->primaryOwners(display,live);
        int user=android.os.Process.myUid()/100000;
        return new GuardedPhonePort(physical,policy,probe,user);
    }
    public static void requireOwnerShare(Context context,DisplayModule display,RunControl original)throws Exception {
        PhoneAccessPolicy.State policy=new AndroidPhoneAccessStore(context).current();
        if(!policy.available())throw new SecurityException("app_policy_unavailable");
        if(policy.rules().isEmpty())return;
        PhoneOwnerPackages actual=primaryOwners(display,original);
        policy.require(actual.owners(),actual.known(),PhoneAccessPolicy.Use.OWNER_SHARE);
    }
    public static PhoneOwnerPackages primaryOwners(DisplayModule display,RunControl original)throws Exception {
        if(display==null)throw new SecurityException("phone_owner_identity_unavailable");
        PhoneOwnerPackages identities=PhoneOwnerPackages.fromOwnership(display.primaryOwnership(original,original.isPaused()),"primary");
        return PhoneWindowPackages.read(PhoneAccessibilityService.get(),Display.DEFAULT_DISPLAY,identities,original::check);
    }
}
