package dev.pocketengine.phone;

import dev.pocketengine.core.PhonePort;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.phone.access.*;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class PhoneDynamicPolicyTest {
    private final AppCopyKey app=new AppCopyKey("example.notes",0);
    private static final class Physical implements PhonePort {
        final AtomicInteger reads=new AtomicInteger(),effects=new AtomicInteger(),clears=new AtomicInteger();Runnable readHook=()->{},effectHook=()->{},afterEffect=()->{};
        public boolean isConnected(){return true;}public void clearReferences(){clears.incrementAndGet();}
        public JSONObject observe(boolean image,RunControl control)throws Exception {control.check();reads.incrementAndGet();readHook.run();return new JSONObject().put("ok",true).put("observationId","fresh").put("imageBase64",image?"PRIVATE_PIXELS":"");}
        public JSONObject execute(String tool,JSONObject args,RunControl control)throws Exception {effectHook.run();control.dispatchAtPauseRevision(control.pauseRevision(),()->{effects.incrementAndGet();afterEffect.run();});return new JSONObject().put("ok",true).put("accepted",true);}
    }
    @Test public void denialAndUnknownOwnershipPreventAnyTreeOrPixelRead()throws Exception {Physical source=new Physical();PhoneAccessPolicy policy=()->new PhoneAccessPolicy.State(1,true,Map.of(app,new PhoneAccessPolicy.Rule(false,true)));GuardedPhonePort guarded=new GuardedPhonePort(source,policy,c->new PhoneOwnerPackages(Set.of(app),true),0);RunControl control=new RunControl();assertFalse(guarded.observe(true,control).getBoolean("ok"));guarded.requireOwnerShare(control);assertEquals(0,source.reads.get());GuardedPhonePort unknown=new GuardedPhonePort(source,()->new PhoneAccessPolicy.State(0,true,Map.of()),c->new PhoneOwnerPackages(Set.of(),false),0);assertFalse(unknown.observe(false,control).getBoolean("ok"));assertEquals(0,source.reads.get());}
    @Test public void revokedDuringCaptureDropsAllCapturedContent()throws Exception {Physical source=new Physical();AtomicReference<PhoneAccessPolicy.State> policy=new AtomicReference<>(new PhoneAccessPolicy.State(0,true,Map.of()));source.readHook=()->policy.set(new PhoneAccessPolicy.State(1,true,Map.of(app,new PhoneAccessPolicy.Rule(false,false))));GuardedPhonePort guarded=new GuardedPhonePort(source,policy::get,c->new PhoneOwnerPackages(Set.of(app),true),0);JSONObject returned=guarded.observe(true,new RunControl());assertFalse(returned.getBoolean("ok"));assertFalse(returned.toString().contains("PRIVATE_PIXELS"));assertTrue(source.clears.get()>0);}
    @Test public void policyRevisionInvalidatesOldRefsEvenWhenCurrentAppRemainsAllowed()throws Exception {Physical source=new Physical();AtomicReference<PhoneAccessPolicy.State> policy=new AtomicReference<>(new PhoneAccessPolicy.State(0,true,Map.of()));GuardedPhonePort guarded=new GuardedPhonePort(source,policy::get,c->new PhoneOwnerPackages(Set.of(app),true),0);RunControl control=new RunControl();guarded.observe(false,control);policy.set(new PhoneAccessPolicy.State(1,true,Map.of()));assertFalse(guarded.execute("phone_type",new JSONObject(),control).getBoolean("ok"));assertEquals(0,source.effects.get());guarded.observe(false,control);assertTrue(guarded.execute("phone_type",new JSONObject(),control).getBoolean("accepted"));}
    @Test public void liveDispatchCheckCatchesRevokeAfterInitialCheckAndDoesNotRecurse()throws Exception {Physical source=new Physical();AtomicReference<PhoneAccessPolicy.State> policy=new AtomicReference<>(new PhoneAccessPolicy.State(0,true,Map.of()));GuardedPhonePort guarded=new GuardedPhonePort(source,policy::get,c->{c.check();return new PhoneOwnerPackages(Set.of(app),true);},0);RunControl control=new RunControl();guarded.observe(false,control);source.effectHook=()->policy.set(new PhoneAccessPolicy.State(1,true,Map.of(app,new PhoneAccessPolicy.Rule(false,false))));assertThrows(InterruptedException.class,()->guarded.execute("phone_type",new JSONObject(),control));assertEquals(0,source.effects.get());}
    @Test public void targetChangedAfterAcceptedInputIsUnknownRatherThanDefiniteNonDispatch()throws Exception {Physical source=new Physical();AtomicReference<PhoneOwnerPackages> owners=new AtomicReference<>(new PhoneOwnerPackages(Set.of(app),true));AppCopyKey blocked=new AppCopyKey("example.bank",0);GuardedPhonePort guarded=new GuardedPhonePort(source,()->new PhoneAccessPolicy.State(0,true,Map.of(blocked,new PhoneAccessPolicy.Rule(false,false))),c->owners.get(),0);RunControl control=new RunControl();guarded.observe(false,control);source.afterEffect=()->owners.set(new PhoneOwnerPackages(Set.of(blocked),true));JSONObject receipt=guarded.execute("phone_type",new JSONObject(),control);assertTrue(receipt.isNull("accepted"));assertTrue(receipt.getBoolean("requiresReconciliation"));assertEquals(1,source.effects.get());}
}
