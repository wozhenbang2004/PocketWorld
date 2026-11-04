package dev.pocketengine.phone.display;

import android.app.Application;
import android.content.Context;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.phone.display.adb.AdbBootstrap;
import dev.pocketengine.phone.display.adb.AdbBootstrapClient;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Negotiation plus the real create entry point; no shell connection or display is fabricated. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class PhoneParallelCapabilitiesTest {
    @Test public void softwareRoutingCapacityDoesNotClaimPhysicalDeviceVerification()throws Exception {
        JSONObject capability=PhoneParallelCapabilities.current();assertTrue(capability.getBoolean("supported"));assertFalse(capability.getBoolean("deviceIsolationVerified"));
        assertEquals(4,capability.getInt("maximumActivePhoneRuns"));assertEquals("per_run_background_sessions",capability.getString("code"));assertFalse(capability.getBoolean("automaticFallback"));
        assertEquals(2,capability.getInt("sessionRouting"));assertTrue(capability.getBoolean("requiresLiveIsolationChecks"));assertTrue(capability.getBoolean("sameAppUserExclusive"));
        JSONArray pending=capability.getJSONArray("unverifiedOnDevice");Set<String> checks=new HashSet<>();for(int i=0;i<pending.length();i++)checks.add(pending.getString(i));
        assertEquals(Set.of("simultaneous_focus_and_ime","cross_display_input_and_capture","per_run_physical_cleanup"),checks);
    }
    @Test public void countAndBooleanContractsCannotRoundOrCoerceARequestIntoSupportedSingleSession()throws Exception {
        PhoneParallelCapabilities.require(null);PhoneParallelCapabilities.require(new JSONObject().put("parallel",false).put("concurrentSessions",1));
        assertThrows(IllegalArgumentException.class,()->PhoneParallelCapabilities.require(new JSONObject().put("parallel","true")));
        for(Object invalid:new Object[]{0,5,1.5,"1",true,JSONObject.NULL})assertThrows(String.valueOf(invalid),RuntimeException.class,()->PhoneParallelCapabilities.require(new JSONObject().put("concurrentSessions",invalid)));
        for(int requested:new int[]{2,3,4})PhoneParallelCapabilities.require(new JSONObject().put("parallel",true).put("concurrentSessions",requested));
    }
    @Test public void parallelCreateWithoutNativeRunIsRejectedBeforeConfigurationProbeTransportOrSessionMutation()throws Exception {
        Context android=RuntimeEnvironment.getApplication();android.getSharedPreferences("display_backend",0).edit().putString("backend","adb").commit();
        AtomicInteger configured=new AtomicInteger(),probes=new AtomicInteger(),transport=new AtomicInteger();
        AdbBootstrapClient adb=new AdbBootstrapClient(){
            public JSONObject snapshot(){return new JSONObject();}
            public JSONObject discover(){transport.incrementAndGet();throw new AssertionError("no bootstrap discovery");}
            public JSONObject pair(JSONObject input){transport.incrementAndGet();throw new AssertionError("no pairing");}
            public JSONObject connect(JSONObject input,JSONObject bootstrap,AdbBootstrap.AuthenticatedStatus status){transport.incrementAndGet();throw new AssertionError("no connection");}
            public JSONObject cancel(){transport.incrementAndGet();throw new AssertionError("no cancellation of another connection");}
            public JSONObject forgetIdentity(){transport.incrementAndGet();throw new AssertionError("no identity mutation");}
            public void close() { }
        };
        DisplayModule module=new DisplayModule(new FeatureContext(android,null,null,event->{}),changed->adb,
                ()->{configured.incrementAndGet();throw new AssertionError("no configuration read or credential creation");},
                ()->{probes.incrementAndGet();throw new AssertionError("no authenticated helper probe");});
        RunControl original=new RunControl();try{
            String before=module.session().toString();
            for(JSONObject request:new JSONObject[]{new JSONObject().put("parallel",true),new JSONObject().put("concurrentSessions",2)}){
                assertThrows(SecurityException.class,()->module.create(request,original));
            }
            assertEquals(0,configured.get());assertEquals(0,probes.get());assertEquals(0,transport.get());assertFalse(module.hasSession());assertEquals(before,module.session().toString());
        }finally{original.cancel();module.close();}
    }
}
