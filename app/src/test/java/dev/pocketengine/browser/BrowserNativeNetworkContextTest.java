package dev.pocketengine.browser;

import android.app.Application;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.UUID;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import static org.junit.Assert.*;

/** Real candidate event ordering around nonce probing; no socket or renderer is mocked as verified. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,application=Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class BrowserNativeNetworkContextTest {
    private static JSONObject obj(Object... pairs)throws Exception {JSONObject o=new JSONObject();for(int i=0;i<pairs.length;i+=2)o.put((String)pairs[i],pairs[i+1]);return o;}
    private static final class Harness implements AutoCloseable {
        final BrowserNativeNetwork network;final Object candidate;final Method event,select;
        Harness()throws Exception {
            String document=UUID.randomUUID().toString();network=new BrowserNativeNetwork(obj("documentId",document),new BrowserNativeNetwork.DocumentProof("__pw_document_"+"a".repeat(32),"b".repeat(32),document),Runnable::run,()->{},()->"");
            Class<?> type=Class.forName("dev.pocketengine.browser.BrowserNativeNetwork$Candidate");Constructor<?> ctor=type.getDeclaredConstructor(BrowserNativeNetwork.class);ctor.setAccessible(true);candidate=ctor.newInstance(network);
            event=type.getDeclaredMethod("event",JSONObject.class);event.setAccessible(true);select=type.getDeclaredMethod("select",String.class,String.class,String.class,int.class);select.setAccessible(true);
        }
        void event(String name,JSONObject params)throws Exception {event.invoke(candidate,obj("method",name,"params",params));}
        void created(String unique)throws Exception {event("Runtime.executionContextCreated",obj("context",obj("id",7,"uniqueId",unique,"auxData",obj("frameId","frame-a","isDefault",false,"type","isolated"))));}
        boolean select(String unique)throws Exception {return (Boolean)select.invoke(candidate,"frame-a","loader-a",unique,7);}
        public void close(){network.close();}
    }
    @Test public void exactStillLiveContextCanBeSelected()throws Exception {try(Harness h=new Harness()){h.created("unique-a");assertTrue(h.select("unique-a"));}}
    @Test public void destroyedContextBetweenProbeAndSelectionCannotBeRebound()throws Exception {try(Harness h=new Harness()){h.created("unique-a");h.event("Runtime.executionContextDestroyed",obj("executionContextId",7,"executionContextUniqueId","unique-a"));assertFalse(h.select("unique-a"));}}
    @Test public void clearedOrDetachedCandidateCannotUseProbeSnapshot()throws Exception {for(String event:new String[]{"Runtime.executionContextsCleared","Inspector.detached"})try(Harness h=new Harness()){h.created("unique-a");h.event(event,obj());assertFalse(h.select("unique-a"));}}
    @Test public void reusedNumericIdRequiresTheNewUniqueContext()throws Exception {try(Harness h=new Harness()){h.created("unique-a");h.created("unique-b");assertFalse(h.select("unique-a"));h.event("Runtime.executionContextDestroyed",obj("executionContextId",7,"executionContextUniqueId","unique-a"));assertTrue(h.select("unique-b"));}}
    @Test public void selectedContextDestructionClosesCapture()throws Exception {try(Harness h=new Harness()){h.created("unique-a");assertTrue(h.select("unique-a"));h.event("Runtime.executionContextDestroyed",obj("executionContextId",7));assertFalse(h.network.active());}}
}
