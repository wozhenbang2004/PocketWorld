package dev.pocketengine.phone.access;

import dev.pocketengine.core.PhonePort;
import dev.pocketengine.core.RunControl;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class GuardedPhonePortTest {
    private static final AppCopyKey APP=new AppCopyKey("fixture.phone",10);
    private static PhoneAccessPolicy.State state(long revision,boolean automation,boolean share){return new PhoneAccessPolicy.State(revision,true,Map.of(APP,new PhoneAccessPolicy.Rule(automation,share)));}
    private static final class Physical implements PhonePort {
        int reads,writes,cleared;Runnable onRead=()->{},onWrite=()->{};
        public boolean isConnected(){return true;}public void clearReferences(){cleared++;}
        public JSONObject observe(boolean image,RunControl control)throws Exception{control.check();reads++;onRead.run();return new JSONObject().put("ok",true).put("imageBase64","actual-private-frame");}
        public JSONObject execute(String name,JSONObject args,RunControl control)throws Exception{control.check();writes++;onWrite.run();return new JSONObject().put("ok",true).put("accepted",true);}
    }
    @Test public void disabledAppIsDeniedBeforeAnyObservationAndOwnerSharingIsSeparate()throws Exception {
        Physical physical=new Physical();var state=new AtomicReference<>(state(1,false,true));var guarded=new GuardedPhonePort(physical,state::get,c->new PhoneOwnerPackages(Set.of(APP),true),10);RunControl control=new RunControl();
        JSONObject denied=guarded.observe(true,control);assertEquals("app_access_denied",denied.getString("error"));assertEquals(0,physical.reads);guarded.requireOwnerShare(control);
        state.set(state(2,true,false));assertThrows(PhoneAccessPolicy.Denied.class,()->guarded.requireOwnerShare(control));
    }
    @Test public void revocationDuringReadDropsTheFrameWithoutClaimingAnUnknownAction()throws Exception {
        Physical physical=new Physical();var state=new AtomicReference<>(state(1,true,true));physical.onRead=()->state.set(state(2,false,false));var guarded=new GuardedPhonePort(physical,state::get,c->new PhoneOwnerPackages(Set.of(APP),true),10);
        JSONObject result=guarded.observe(true,new RunControl());assertFalse(result.getBoolean("ok"));assertFalse(result.getBoolean("accepted"));assertFalse(result.getBoolean("requiresReconciliation"));assertFalse(result.has("imageBase64"));
    }
    @Test public void policyRevisionAndAnotherRunCannotReuseAnOldActionReference()throws Exception {
        Physical physical=new Physical();var state=new AtomicReference<>(state(1,true,true));var guarded=new GuardedPhonePort(physical,state::get,c->new PhoneOwnerPackages(Set.of(APP),true),10);RunControl original=new RunControl();guarded.observe(false,original);
        assertFalse(guarded.execute("phone_tap",new JSONObject(),new RunControl()).getBoolean("accepted"));assertEquals(0,physical.writes);
        guarded.observe(false,original);state.set(state(2,true,true));assertFalse(guarded.execute("phone_tap",new JSONObject(),original).getBoolean("accepted"));assertEquals(0,physical.writes);
    }
    @Test public void acceptedEffectThenRevocationIsUnknownAndNotAutomaticallyReplayed()throws Exception {
        Physical physical=new Physical();var state=new AtomicReference<>(state(1,true,true));var guarded=new GuardedPhonePort(physical,state::get,c->new PhoneOwnerPackages(Set.of(APP),true),10);RunControl control=new RunControl();guarded.observe(false,control);physical.onWrite=()->state.set(state(2,false,false));
        JSONObject result=guarded.execute("phone_tap",new JSONObject(),control);assertTrue(result.isNull("accepted"));assertTrue(result.getBoolean("requiresReconciliation"));assertFalse(guarded.execute("phone_tap",new JSONObject(),control).getBoolean("accepted"));assertEquals(1,physical.writes);
    }
    @Test public void unknownProfileOwnershipNeverDefaultsToMainUser()throws Exception {
        Physical physical=new Physical();var guarded=new GuardedPhonePort(physical,()->state(1,true,true),c->new PhoneOwnerPackages(Set.of(),false),10);assertEquals("app_owner_unknown",guarded.observe(true,new RunControl()).getString("error"));assertEquals(0,physical.reads);
        assertFalse(new PhoneOwnerPackages(Set.of(APP,new AppCopyKey("fixture.phone",0)),true).provesCopy(APP));
    }
}
