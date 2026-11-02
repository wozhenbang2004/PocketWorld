package dev.pocketengine.phone;

import android.app.Application;
import android.view.accessibility.AccessibilityWindowInfo;
import dev.pocketengine.phone.access.*;
import java.util.Map;
import java.util.Set;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Native-window metadata reconciliation only; no screen contents or real phone access. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class PhoneWindowPackagesTest {
    private final AppCopyKey allowed=new AppCopyKey("example.notes",0),work=new AppCopyKey("example.notes",10);
    @Test public void aDifferentVisibleWindowCannotBorrowTheResumedAppsPermission(){PhoneOwnerPackages actual=PhoneWindowPackages.match(Set.of("example.notes","example.private"),true,new PhoneOwnerPackages(Set.of(allowed),true));assertFalse(actual.known());assertThrows(PhoneAccessPolicy.Denied.class,()->new PhoneAccessPolicy.State(0,true,Map.of()).require(actual.owners(),actual.known(),PhoneAccessPolicy.Use.AUTOMATION));}
    @Test public void missingRootOrUserNeverBecomesAKnownOwner(){assertFalse(PhoneWindowPackages.match(Set.of("example.notes"),false,new PhoneOwnerPackages(Set.of(allowed),true)).known());assertFalse(PhoneWindowPackages.match(Set.of("example.notes"),true,new PhoneOwnerPackages(Set.of(),false)).known());assertFalse(PhoneWindowPackages.match(Set.of(),true,new PhoneOwnerPackages(Set.of(allowed),true)).known());}
    @Test public void samePackageMultipleProfilesChecksEveryPossibleActualIdentity(){PhoneOwnerPackages actual=PhoneWindowPackages.match(Set.of("example.notes"),true,new PhoneOwnerPackages(Set.of(allowed,work),true));assertTrue(actual.known());assertEquals(Set.of(allowed,work),actual.owners());PhoneAccessPolicy.State policy=new PhoneAccessPolicy.State(2,true,Map.of(work,new PhoneAccessPolicy.Rule(false,true)));assertThrows(PhoneAccessPolicy.Denied.class,()->policy.require(actual.owners(),actual.known(),PhoneAccessPolicy.Use.AUTOMATION));policy.require(actual.owners(),actual.known(),PhoneAccessPolicy.Use.OWNER_SHARE);}
    @Test public void anotherAccessibilityServicesOverlayCannotBypassAppPolicy(){int overlay=AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY;assertTrue(PhoneWindowPackages.ignoreControlOverlay(overlay,"dev.pocketengine","dev.pocketengine"));assertFalse(PhoneWindowPackages.ignoreControlOverlay(overlay,"example.private","dev.pocketengine"));assertFalse(PhoneWindowPackages.ignoreControlOverlay(AccessibilityWindowInfo.TYPE_APPLICATION,"dev.pocketengine","dev.pocketengine"));assertFalse(PhoneWindowPackages.ignoreControlOverlay(overlay,null,"dev.pocketengine"));PhoneOwnerPackages actual=PhoneWindowPackages.match(Set.of("example.notes","example.private"),true,new PhoneOwnerPackages(Set.of(allowed),true));assertFalse(actual.known());assertThrows(PhoneAccessPolicy.Denied.class,()->new PhoneAccessPolicy.State(1,true,Map.of()).require(actual.owners(),actual.known(),PhoneAccessPolicy.Use.OWNER_SHARE));}
}
