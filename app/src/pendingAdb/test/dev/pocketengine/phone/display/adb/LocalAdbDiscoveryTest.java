package dev.pocketengine.phone.display.adb;

import android.app.Application;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Looper;
import java.net.InetAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** Exact production discovery with controlled NSD callbacks; no multicast/device evidence. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class,shadows=LocalAdbDiscoveryTest.ControlledNsd.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class LocalAdbDiscoveryTest {
    private LocalAdbDiscovery discovery;
    @Before public void prepare(){ControlledNsd.clear();discovery=new LocalAdbDiscovery(RuntimeEnvironment.getApplication(),value->{});discovery.start();idle();assertEquals(2,ControlledNsd.discoveries.size());}
    @After public void close(){discovery.close();idle();}
    private static void idle(){shadowOf(Looper.getMainLooper()).idle();}
    private static NsdServiceInfo service(String type,String name,int port)throws Exception {NsdServiceInfo info=new NsdServiceInfo();info.setServiceType(type);info.setServiceName(name);info.setHost(InetAddress.getByName("127.0.0.1"));info.setPort(port);return info;}
    private void found(String type,String name,int port)throws Exception {ControlledNsd.discoveries.get(type).onServiceFound(service(type,name,port));idle();}
    @Test public void finalDiscoveryStartFailureConvergesAndExplicitRetryStartsNewScan(){
        for(Map.Entry<String,NsdManager.DiscoveryListener> entry:new ArrayList<>(ControlledNsd.discoveries.entrySet()))entry.getValue().onStartDiscoveryFailed(entry.getKey(),3);idle();
        assertFalse(discovery.snapshot().optBoolean("discovering"));assertEquals("discovery_start_failed",discovery.snapshot().optString("errorCode"));
        ControlledNsd.discoveries.clear();discovery.start();idle();assertEquals(2,ControlledNsd.discoveries.size());assertTrue(discovery.snapshot().optBoolean("discovering"));
    }
    @Test public void resolutionTimeoutAdvancesQueueAndLateResultCannotPublishStalePort()throws Exception {
        found(LocalAdbEndpoints.PAIRING,"pair",37111);found(LocalAdbEndpoints.CONNECT,"connect",37112);assertEquals(1,ControlledNsd.resolutions.size());
        NsdManager.ResolveListener old=ControlledNsd.resolutions.get(0);shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5));assertEquals(2,ControlledNsd.resolutions.size());assertEquals(1,ControlledNsd.resolveStops);
        old.onServiceResolved(service(LocalAdbEndpoints.PAIRING,"pair",37111));idle();assertEquals(0,discovery.snapshot().getJSONArray("pairing").length());
        ControlledNsd.resolutions.get(1).onServiceResolved(service(LocalAdbEndpoints.CONNECT,"connect",37112));idle();
        assertEquals(37112,discovery.snapshot().getJSONArray("connect").getJSONObject(0).getInt("port"));assertFalse(discovery.snapshot().optBoolean("timedOut"));
    }
    @Test public void scanDeadlineAndExplicitStopRejectOldCallbacksButPermitRefresh()throws Exception {
        found(LocalAdbEndpoints.CONNECT,"connect",37112);NsdManager.ResolveListener old=ControlledNsd.resolutions.get(0);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(20));assertFalse(discovery.snapshot().optBoolean("discovering"));assertTrue(discovery.snapshot().optBoolean("timedOut"));assertEquals("discovery_timeout",discovery.snapshot().optString("errorCode"));
        old.onServiceResolved(service(LocalAdbEndpoints.CONNECT,"connect",37112));idle();assertEquals(0,discovery.snapshot().getJSONArray("connect").length());
        discovery.refreshConnect();idle();assertTrue(discovery.snapshot().optBoolean("discovering"));discovery.stop();idle();assertFalse(discovery.snapshot().optBoolean("discovering"));assertTrue(ControlledNsd.discoveryStops>=4);
    }
    @Test public void connectIsResolvedAgainAfterPairAndForeignAddressesNeverEnterSnapshot()throws Exception {
        found(LocalAdbEndpoints.CONNECT,"connect",37112);ControlledNsd.resolutions.get(0).onServiceResolved(service(LocalAdbEndpoints.CONNECT,"connect",37112));idle();assertEquals(1,discovery.snapshot().getJSONArray("connect").length());
        discovery.refreshConnect();idle();assertEquals(2,ControlledNsd.resolutions.size());NsdServiceInfo foreign=service(LocalAdbEndpoints.CONNECT,"connect",37112);foreign.setHost(InetAddress.getByName("192.0.2.245"));ControlledNsd.resolutions.get(1).onServiceResolved(foreign);idle();
        assertEquals(0,discovery.snapshot().getJSONArray("connect").length());
    }
    @Test public void lostAndRediscoveredServiceRejectsThePreviousResolution()throws Exception {
        found(LocalAdbEndpoints.PAIRING,"pair",37111);
        NsdManager.ResolveListener old=ControlledNsd.resolutions.get(0);
        ControlledNsd.discoveries.get(LocalAdbEndpoints.PAIRING).onServiceLost(service(LocalAdbEndpoints.PAIRING,"pair",37111));idle();
        found(LocalAdbEndpoints.PAIRING,"pair",38111);
        old.onServiceResolved(service(LocalAdbEndpoints.PAIRING,"pair",37111));idle();
        assertEquals("the closed pairing window must not reappear",0,discovery.snapshot().getJSONArray("pairing").length());
        assertEquals(2,ControlledNsd.resolutions.size());
        ControlledNsd.resolutions.get(1).onServiceResolved(service(LocalAdbEndpoints.PAIRING,"pair",38111));idle();
        assertEquals(38111,discovery.snapshot().getJSONArray("pairing").getJSONObject(0).getInt("port"));
    }
    @Implements(NsdManager.class)
    public static final class ControlledNsd {
        static final Map<String,NsdManager.DiscoveryListener> discoveries=new LinkedHashMap<>();
        static final List<NsdManager.ResolveListener> resolutions=new ArrayList<>();static int resolveStops,discoveryStops;
        static void clear(){discoveries.clear();resolutions.clear();resolveStops=0;discoveryStops=0;}
        @Implementation protected void discoverServices(String type,int protocol,NsdManager.DiscoveryListener listener){discoveries.put(type,listener);}
        @Implementation protected void resolveService(NsdServiceInfo info,NsdManager.ResolveListener listener){resolutions.add(listener);}
        @Implementation protected void stopServiceDiscovery(NsdManager.DiscoveryListener listener){discoveryStops++;}
        @Implementation(minSdk=34) protected void stopServiceResolution(NsdManager.ResolveListener listener){resolveStops++;}
    }
}
