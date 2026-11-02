package dev.pocketengine.features.skills;

import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class ManagedScriptBridgeSessionTest {
    private static final String TOKEN="T".repeat(43);
    private JSONObject request(String id,String tool)throws Exception{return new JSONObject().put("token",TOKEN).put("requestId",id).put("method","phone").put("tool",tool).put("args",new JSONObject());}
    private ManagedScriptBridgeSession.Outputs outputs(){return new ManagedScriptBridgeSession.Outputs(){public void progress(String text){}public JSONObject share(String path)throws Exception{throw new SecurityException("unapproved_output");}};}
    @Test public void allowedObserveActionObserveUsesTheOriginalCallbackOncePerIdentity()throws Exception{
        AtomicInteger calls=new AtomicInteger();var session=new ManagedScriptBridgeSession(TOKEN,new ManagedScriptBridgeSession.Phone(){public Set<String> allowedTools(){return Set.of("phone_observe","phone_tap");}public JSONObject invoke(String name,JSONObject args)throws Exception{return new JSONObject().put("ok",true).put("nativeSequence",calls.incrementAndGet());}},()->{},outputs());
        JSONObject first=session.handle(request("observe1","phone_observe").toString());assertEquals(1,first.getInt("nativeSequence"));assertEquals(1,session.handle(request("observe1","phone_observe").toString()).getInt("nativeSequence"));assertEquals(1,calls.get());
        session.handle(request("tap1","phone_tap").toString());session.handle(request("observe2","phone_observe").toString());assertEquals(3,calls.get());assertThrows(SecurityException.class,()->session.handle(request("tap1","phone_observe").toString()));
    }
    @Test public void cancellationCloseBadTokenAndForgedScopeDoNotDispatch()throws Exception{
        AtomicBoolean cancelled=new AtomicBoolean();AtomicInteger calls=new AtomicInteger();var session=new ManagedScriptBridgeSession(TOKEN,new ManagedScriptBridgeSession.Phone(){public Set<String> allowedTools(){return Set.of("phone_observe");}public JSONObject invoke(String name,JSONObject args)throws Exception{calls.incrementAndGet();return new JSONObject().put("ok",true);}},()->{if(cancelled.get())throw new SecurityException("original_cancelled");},outputs());
        assertThrows(SecurityException.class,()->session.handle(request("bad","phone_observe").put("token","invalid").toString()));assertThrows(SecurityException.class,()->session.handle(request("scope","phone_observe").put("taskId","fake").toString()));
        JSONObject forged=request("arg","phone_observe");forged.getJSONObject("args").put("ownerId","fake");assertEquals("rejected",session.handle(forged.toString()).getString("status"));cancelled.set(true);assertThrows(SecurityException.class,()->session.handle(request("late","phone_observe").toString()));session.close();cancelled.set(false);assertThrows(SecurityException.class,()->session.handle(request("closed","phone_observe").toString()));assertEquals(0,calls.get());
    }
    @Test public void unknownOriginalCallbackIsRetainedAndNeverAutomaticallyReplayed()throws Exception{
        AtomicInteger calls=new AtomicInteger();var session=new ManagedScriptBridgeSession(TOKEN,new ManagedScriptBridgeSession.Phone(){public Set<String> allowedTools(){return Set.of("phone_tap");}public JSONObject invoke(String name,JSONObject args)throws Exception{calls.incrementAndGet();throw new java.io.IOException("original_receipt_unknown");}},()->{},outputs());
        assertEquals("unknown",session.handle(request("first","phone_tap").toString()).getString("status"));assertEquals("unknown",session.handle(request("first","phone_tap").toString()).getString("status"));assertEquals("rejected",session.handle(request("second","phone_tap").toString()).getString("status"));assertEquals(1,calls.get());
        var actualUnknown=new ManagedScriptBridgeSession(TOKEN,new ManagedScriptBridgeSession.Phone(){public Set<String> allowedTools(){return Set.of("phone_tap");}public JSONObject invoke(String name,JSONObject args)throws Exception{return new JSONObject().put("status","unknown");}},()->{},outputs());
        JSONObject original=actualUnknown.handle(request("one","phone_tap").toString());assertFalse(original.getBoolean("ok"));assertFalse(original.getBoolean("replayAllowed"));assertEquals("unknown",original.getString("status"));assertEquals(original.toString(),actualUnknown.handle(request("one","phone_tap").toString()).toString());assertEquals("rejected",actualUnknown.handle(request("two","phone_tap").toString()).getString("status"));
        var afterEffectRevoked=new ManagedScriptBridgeSession(TOKEN,new ManagedScriptBridgeSession.Phone(){public Set<String> allowedTools(){return Set.of("phone_tap");}public JSONObject invoke(String name,JSONObject args)throws Exception{throw new SecurityException("lifecycle_revoked_after_native_entry");}},()->{},outputs());
        assertEquals("unknown",afterEffectRevoked.handle(request("one","phone_tap").toString()).getString("status"));assertEquals("rejected",afterEffectRevoked.handle(request("two","phone_tap").toString()).getString("status"));
    }
    @Test public void closeWaitsForTheOneCurrentNativeCallbackAndFencesLaterRequests()throws Exception{
        var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);var closeDone=new java.util.concurrent.CountDownLatch(1);
        String token="x".repeat(43);var session=new ManagedScriptBridgeSession(token,new ManagedScriptBridgeSession.Phone(){public Set<String> allowedTools(){return Set.of("phone_observe");}public JSONObject invoke(String tool,JSONObject args)throws Exception{entered.countDown();if(!release.await(2,java.util.concurrent.TimeUnit.SECONDS))throw new IllegalStateException("test_release_missing");return new JSONObject().put("ok",true);}},()->{},new ManagedScriptBridgeSession.Outputs(){public void progress(String text){}public JSONObject share(String path){return new JSONObject();}});
        var failure=new java.util.concurrent.atomic.AtomicReference<Throwable>();Thread call=new Thread(()->{try{session.handle(new JSONObject().put("token",token).put("requestId","one").put("method","phone").put("tool","phone_observe").put("args",new JSONObject()).toString());}catch(Throwable error){failure.set(error);}});call.start();assertTrue(entered.await(2,java.util.concurrent.TimeUnit.SECONDS));
        Thread closing=new Thread(()->{session.close();closeDone.countDown();});closing.start();try{assertFalse(closeDone.await(50,java.util.concurrent.TimeUnit.MILLISECONDS));}finally{release.countDown();}call.join(2000);closing.join(2000);assertNull(failure.get());assertEquals(0,closeDone.getCount());assertThrows(SecurityException.class,()->session.handle(new JSONObject().put("token",token).put("requestId","two").put("method","info").toString()));
    }

}
