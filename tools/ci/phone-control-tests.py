TEST='app/src/test/java/dev/pocketengine/'
write(TEST+'phone/observation/PhoneObservationRecoveryTest.java', '''package dev.pocketengine.phone.observation;
import dev.pocketengine.core.PhonePort;
import dev.pocketengine.core.RunControl;
import java.util.ArrayDeque;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;
public final class PhoneObservationRecoveryTest {
    private static JSONObject failure(String code)throws Exception{return new JSONObject().put("ok",false).put("accepted",false).put("error",code);}
    private static JSONObject success()throws Exception{return new JSONObject().put("ok",true).put("observationId","fresh").put("displayId",9).put("sessionId","original");}
    private static final class Fixture implements PhonePort,PhoneObservationRecovery.Timing {
        long clock;int reads,effects,clears;Runnable duringWait=()->{};
        final ArrayDeque<JSONObject> results=new ArrayDeque<>();final RunControl control=new RunControl();
        Fixture(JSONObject...values){for(JSONObject value:values)results.add(value);control.bindHostRun("task","run",1);}
        public boolean isConnected(){return true;}
        public JSONObject observe(boolean image,RunControl run){assertSame(control,run);assertTrue(image);reads++;return results.remove();}
        public JSONObject execute(String tool,JSONObject args,RunControl run){effects++;throw new AssertionError("read recovery dispatched an action");}
        public void clearReferences(){clears++;}
        public long now(){return clock;}
        public void pause(long ms){clock+=ms;duringWait.run();}
        JSONObject read(boolean retry)throws Exception{return PhoneObservationRecovery.read(this,true,control,()->{},retry,(code,n,ms)->{},this);}
    }
    @Test public void transientFailuresRetryOnlyReadOnSamePort()throws Exception {
        for(String code:new String[]{"screen_changed","frame_pending","screenshot_throttled","screenshot_busy"}){
            Fixture f=new Fixture(failure(code),success());JSONObject out=f.read(true);
            assertTrue(out.getBoolean("ok"));assertEquals("fresh",out.getString("observationId"));assertEquals(9,out.getInt("displayId"));
            assertEquals(2,f.reads);assertEquals(0,f.effects);assertEquals(1,f.clears);assertEquals(1,out.getInt("readOnlyRetries"));
            assertFalse(out.getJSONObject("observationRecovery").getBoolean("actionReplay"));assertFalse(out.getJSONObject("observationRecovery").getBoolean("displayFallback"));
        }
    }
    @Test public void retryCountIsBounded()throws Exception {
        Fixture f=new Fixture(failure("frame_pending"),failure("frame_pending"),failure("frame_pending"));
        JSONObject out=f.read(true);assertFalse(out.getBoolean("ok"));assertEquals(3,f.reads);assertEquals(0,f.effects);
        assertTrue(out.getJSONObject("observationRecovery").getBoolean("retryBudgetExhausted"));
    }
    @Test public void planningReadDoesNotSpendAnotherRead()throws Exception {
        Fixture f=new Fixture(failure("screen_changed"));assertFalse(f.read(false).getBoolean("ok"));assertEquals(1,f.reads);assertEquals(0,f.clock);assertEquals(0,f.clears);
    }
    @Test public void permissionIdentityAndUnknownEffectsNeverRetry()throws Exception {
        for(String code:new String[]{"permission_denied","service_disconnected","wrong_display","focus_isolation_lost","screen_locked","screenshot_secure_window","action_unknown","tree_read_timeout"}){
            Fixture f=new Fixture(failure(code));assertFalse(f.read(true).getBoolean("ok"));assertEquals(1,f.reads);assertEquals(0,f.clock);
        }
        for(String flag:new String[]{"requiresPause","requiresUser","requiresOwnerReturn","requiresReconciliation"}){
            Fixture f=new Fixture(failure("screen_changed").put(flag,true));assertFalse(f.read(true).getBoolean("ok"));assertEquals(1,f.reads);
        }
        Fixture f=new Fixture(failure("screen_changed").put("accepted",JSONObject.NULL));assertTrue(f.read(true).getBoolean("requiresPause"));assertEquals(1,f.reads);
    }
    @Test public void pauseResumeInvalidatesReadAuthority()throws Exception {
        Fixture f=new Fixture(failure("screen_changed"));f.duringWait=()->{f.control.pause();f.control.resume();};
        assertEquals("paused_or_changed",f.read(true).getString("error"));assertEquals(1,f.reads);assertEquals(0,f.effects);
    }
    @Test public void cancellationStopsBeforeNextRead()throws Exception {
        Fixture f=new Fixture(failure("frame_pending"));f.duringWait=f.control::cancel;
        assertThrows(InterruptedException.class,()->f.read(true));assertEquals(1,f.reads);assertEquals(0,f.effects);
    }
    @Test public void elapsedBudgetCheckedBeforeStartingAnotherRead()throws Exception {
        Fixture f=new Fixture(failure("frame_pending"));f.duringWait=()->f.clock=5000;
        assertTrue(f.read(true).getJSONObject("observationRecovery").getBoolean("retryBudgetExhausted"));assertEquals(1,f.reads);
    }
    @Test public void revokedNativeClaimPropagates()throws Exception {
        Fixture f=new Fixture(failure("screen_changed"));int[] checks={0};
        assertThrows(SecurityException.class,()->PhoneObservationRecovery.read(f,true,f.control,()->{if(++checks[0]>=3)throw new SecurityException("revoked");},true,(code,n,ms)->{},f));
        assertEquals(1,f.reads);assertEquals(0,f.effects);
    }
}
''')
write(TEST+'phone/observation/PhoneCaptureGateTest.java', '''package dev.pocketengine.phone.observation;
import org.junit.Test;
import static org.junit.Assert.*;
public final class PhoneCaptureGateTest {
    @Test public void intervalStartsAtActualRequestAndIsShared()throws Exception {
        long[] clock={0};PhoneCaptureGate gate=new PhoneCaptureGate(()->clock[0],ms->clock[0]+=ms);
        var first=gate.acquire(()->{});clock[0]=120;first.requested();first.close();
        try(var second=gate.acquire(()->{})){assertEquals(1120,clock[0]);second.requested();}
        try(var third=gate.acquire(()->{})){assertEquals(2120,clock[0]);}
    }
    @Test public void busyCaptureCannotStealLease()throws Exception {
        long[] clock={0};PhoneCaptureGate gate=new PhoneCaptureGate(()->clock[0],ms->clock[0]+=ms);var original=gate.acquire(()->{});
        assertEquals("screenshot_busy",assertThrows(PhoneCaptureGate.Unavailable.class,()->gate.acquire(()->{})).code);
        assertEquals(PhoneCaptureGate.ACQUIRE_MS,clock[0]);original.requested();original.close();
        try(var next=gate.acquire(()->{})){assertEquals(3500,clock[0]);}
    }
    @Test public void oldLeaseCannotReleaseNewLease()throws Exception {
        long[] clock={0};PhoneCaptureGate gate=new PhoneCaptureGate(()->clock[0],ms->clock[0]+=ms);
        var old=gate.acquire(()->{});old.close();var current=gate.acquire(()->{});old.close();
        assertThrows(PhoneCaptureGate.Unavailable.class,()->gate.acquire(()->{}));current.requested();current.close();
    }
    @Test public void cancelDuringWait()throws Exception {
        long[] clock={0};PhoneCaptureGate gate=new PhoneCaptureGate(()->clock[0],ms->clock[0]+=ms);var active=gate.acquire(()->{});
        assertThrows(InterruptedException.class,()->gate.acquire(()->{if(clock[0]>=50)throw new InterruptedException();}));
        assertEquals(50,clock[0]);active.requested();active.close();
    }
    @Test public void disconnectRevokesPendingAndFutureCaptures()throws Exception {
        long[] clock={0};PhoneCaptureGate gate=new PhoneCaptureGate(()->clock[0],ms->clock[0]+=ms);var old=gate.acquire(()->{});gate.close();
        assertEquals("service_disconnected",assertThrows(PhoneCaptureGate.Unavailable.class,old::requested).code);
        assertEquals("service_disconnected",assertThrows(PhoneCaptureGate.Unavailable.class,()->gate.acquire(()->{})).code);old.close();
    }
}
''')
write(TEST+'phone/PhoneServiceConnectionTest.java', '''package dev.pocketengine.phone;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;
public final class PhoneServiceConnectionTest {
    @Test public void enabledButDisconnectedDoesNotRequestAnotherGrant()throws Exception {
        JSONObject x=PhoneServiceConnection.describe(true,false);assertEquals("enabled_disconnected",x.getString("state"));assertFalse(x.getBoolean("ready"));
        assertEquals("retry",x.getString("action"));assertEquals("accessibility_binding_pending",x.getString("code"));
    }
    @Test public void disabledAndUnknownAreDifferent()throws Exception {
        assertEquals("accessibility",PhoneServiceConnection.describe(false,false).getString("action"));
        JSONObject x=PhoneServiceConnection.describe(JSONObject.NULL,false);assertEquals("unknown",x.getString("state"));assertTrue(x.isNull("registered"));assertFalse(x.getBoolean("ready"));
    }
    @Test public void bindingNotRememberedConsentIsRequired()throws Exception {
        assertTrue(PhoneServiceConnection.describe(JSONObject.NULL,true).getBoolean("ready"));assertFalse(PhoneServiceConnection.describe(true,JSONObject.NULL).getBoolean("ready"));
    }
    @Test public void bothModesPublishAccurateConnectionCause()throws Exception {
        JSONObject readiness=PhoneReadiness.evaluate(new JSONObject().put("serviceRegistered",true).put("serviceConnected",false));
        for(String mode:new String[]{"mainObservation","backgroundPreparation"}){
            var checks=readiness.getJSONObject(mode).getJSONArray("checks");boolean found=false;
            for(int i=0;i<checks.length();i++){JSONObject row=checks.getJSONObject(i);if(row.getString("id").equals("serviceConnected")){found=true;assertEquals("accessibility_binding_pending",row.getString("code"));}}
            assertTrue(found);assertFalse(readiness.getJSONObject(mode).getBoolean("ready"));
        }
        assertFalse(readiness.getBoolean("mainScreenFallback"));
    }
}
''')
write(TEST+'runtime/PhoneModeCatalogTest.java', '''package dev.pocketengine.runtime;
import dev.pocketengine.core.PhonePort;
import dev.pocketengine.core.RunControl;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;
public final class PhoneModeCatalogTest {
    private static final class Port implements PhonePort {
        public boolean isConnected(){return true;}
        public boolean supportsTool(String name){return !name.equals("phone_home");}
        public JSONObject observe(boolean image,RunControl run){throw new AssertionError("catalog read device");}
        public JSONObject execute(String name,JSONObject args,RunControl run){throw new AssertionError("catalog acted");}
        public void clearReferences(){}
    }
    @Test public void removesOnlyUnsupportedBackgroundTool()throws Exception {
        JSONArray original=AgentRunner.tools(),filtered=AgentRunner.phoneTools(original,new Port());boolean back=false,launch=false;
        for(int i=0;i<filtered.length();i++){String name=filtered.getJSONObject(i).getJSONObject("function").getString("name");assertNotEquals("phone_home",name);back|=name.equals("phone_back");launch|=name.equals("phone_launch_app");}
        assertTrue(back);assertTrue(launch);assertEquals(original.length()-1,filtered.length());
    }
}
''')
replace('app/src/hostTest/java/dev/pocketengine/phone/display/PhoneObservationBoundariesNativeTest.java','    private PhonePort foreground(){','''    @Test public void rebindingSameServiceInvalidatesBothPhysicalDriverReferences()throws Exception {
        java.lang.reflect.Method connected=PhoneAccessibilityService.class.getDeclaredMethod("onServiceConnected");connected.setAccessible(true);
        for(boolean bg:new boolean[]{false,true}){
            PhonePort port=bg?background():foreground();RunControl run=control();JSONObject old=observe(port,run);
            String session=service.observationSessionId();var signals=service.uiSignals();connected.invoke(service);
            assertNotEquals(session,service.observationSessionId());assertTrue(signals.snapshot(java.util.Set.of()).closed);
            JSONObject result=complete(caller.submit(()->port.execute("phone_click_node",click(old),run)));
            assertFalse(result.toString(),result.optBoolean("accepted"));assertEquals(0,actions.get());assertEquals(0,helperMutations.get());
            assertEquals(!bg,port.supportsTool("phone_home"));
        }
    }
    private PhonePort foreground(){''')
replace(TEST+'phone/observation/AdaptivePhonePortTest.java','error(out,"screenshots_disabled");assertTrue(out.getBoolean("requiresPause"));','error(out,configured?"visual_engines_unavailable":"local_capture_disabled");assertTrue(out.getBoolean("requiresPause"));')
registry=ROOT/'tools/host-tests.json';groups=json.loads(registry.read_text())
groups['phone-control-reliability']={
 'class':'dev.pocketengine.phone.observation.PhoneObservationRecoveryTest',
 'additionalClasses':['dev.pocketengine.phone.observation.PhoneCaptureGateTest','dev.pocketengine.phone.PhoneServiceConnectionTest','dev.pocketengine.phone.PhoneReadinessTest','dev.pocketengine.phone.AndroidPhoneReadinessTest','dev.pocketengine.runtime.PhoneModeCatalogTest','dev.pocketengine.runtime.OwnerIntentRunnerNativeTest','dev.pocketengine.phone.observation.AdaptivePhonePortTest'],
 'helpers':['dev.pocketengine.testutil.HttpServer'],'robolectric':True,
 'description':'Bounded same-port read recovery, capture serialization, exact service connection status and physical backend catalog narrowing. Host verification, not device acceptance.'}
registry.write_text(json.dumps(groups,ensure_ascii=False,indent=2)+'\n')
write('docs/verification/phone-control-reliability-20261109.md','''# Phone control reliability

This revision preserves accessibility nodes first, separately authorized local OCR/image capture, and exact target/display validation. No upstream implementation is copied.

References examined: Mika `0c172724f3f0c96f7345262588072f6e95db1138` (Apache-2.0), StillFrameRead/ChangeWait/FrameFreshness; DSH-Go `1c5b220093295f8d069f6bfb06d8b4fc97b9e9c2` (AGPL-3.0), PhoneDisplaySelection/ScreenVerdict and phone-control guide.

Changes: distinguish enabled registration from a live accessibility binding in both modes; renew observation identity on same-object service rebind; serialize capture per service before first tree sampling; hold the owner's overlay hidden through tree/image/tree; bound read-only transient recovery on the original port; preserve planning-read ledgers; suppress unsupported background Home in the catalog; keep old-mode errors out of a new mode; classify exact greetings as discussion.

Existing paths retained: main and independent-display click/type/scroll/gestures, exact node identity, OCR targets, query/extract/collect/wait, app copies, semantic intents, helper ownership and IME isolation, live preview, pause/takeover/return/stop, unknown-action reconciliation.

Verification uses actual production Java/Kotlin compilation, selected nonempty JUnit reports, real driver host fixtures, and production renderer browser tests with an explicit synthetic host. No real device, WeChat action, installation, OEM screen-off behavior, or root/Sui parity is claimed. A greeting in the runner avoids device observation; normal phone-host admission still applies. A successful native input receipt is not business completion. Unknown effects must never be replayed, and a background failure must never silently use the main screen.
''')
print('Phone control production patch and regression tests staged successfully')
