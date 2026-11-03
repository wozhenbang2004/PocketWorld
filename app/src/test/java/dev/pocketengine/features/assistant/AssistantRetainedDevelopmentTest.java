package dev.pocketengine.features.assistant;

import android.app.Application;
import dev.pocketengine.core.*;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.store.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Real conversation admission, with an explicit native continuation fixture (no runtime claim). */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class AssistantRetainedDevelopmentTest {
    private static JSONObject j(Object... pairs)throws Exception{JSONObject result=new JSONObject();for(int i=0;i<pairs.length;i+=2)result.put((String)pairs[i],pairs[i+1]);return result;}
    private static void idle(AssistantModule module)throws Exception {long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(module.snapshot().getBoolean("running")&&System.nanoTime()<until)Thread.sleep(5);assertFalse(module.snapshot().getBoolean("running"));}
    @Test public void twoHeldConversationsReleaseSlotAndOriginalInputReclaimsOriginalControl()throws Exception {
        var android=RuntimeEnvironment.getApplication();android.getSharedPreferences("private_settings",0).edit().putString("baseUrl","http://127.0.0.1:12345/v1").putString("model","fixture").putString("provider","openai_chat").commit();
        FeatureContext context=new FeatureContext(android,new SettingsStore(android),new SecretVault(android),event->{});Map<RunControl,JSONObject> bindings=new ConcurrentHashMap<>();Set<RunControl> held=ConcurrentHashMap.newKeySet();List<RunControl> calls=new CopyOnWriteArrayList<>();AtomicInteger starts=new AtomicInteger(),appends=new AtomicInteger();
        try(AssistantModule module=new AssistantModule(context,null,(cid,goal,history,config,control)->{calls.add(control);return j("content","native fixture answer","taskId",bindings.get(control).getString("taskId"),"developmentRetained",held.contains(control));})){
            module.setSourceSynchronizer(()->{});module.setNativeInputHost(new AssistantModule.NativeInputHost(){
                public JSONObject start(String cid,String goal,JSONObject message,ModelConfig config,RunControl control)throws Exception {int count=starts.incrementAndGet();String task=UUID.randomUUID().toString(),run=UUID.randomUUID().toString();control.bindHostRun(task,run,1);JSONObject binding=j("taskId",task,"runId",run,"epoch",1);bindings.put(control,binding);if(count<=2)held.add(control);return binding;}
                public JSONObject append(String cid,JSONObject message,String mode,JSONObject expected,RunControl control)throws Exception {assertTrue(held.contains(control));assertEquals(bindings.get(control).getString("runId"),expected.getString("runId"));appends.incrementAndGet();return bindings.get(control);}
                public boolean retainsDevelopment(RunControl control){return held.contains(control);}
            });
            JSONObject first=module.handle("send",j("message","retain one","clientMessageId",UUID.randomUUID().toString()));idle(module);RunControl original=calls.get(0);
            module.handle("send",j("message","retain two","clientMessageId",UUID.randomUUID().toString()));idle(module);
            JSONObject heldSnapshot=module.snapshot();assertFalse(heldSnapshot.getBoolean("running"));assertEquals(0,heldSnapshot.getJSONObject("turnSlots").getInt("active"));
            JSONArray retained=heldSnapshot.getJSONArray("retainedTurns");assertEquals(2,retained.length());
            JSONObject firstRetained=null;for(int i=0;i<retained.length();i++)if(first.getString("conversationId").equals(retained.getJSONObject(i).getString("conversationId")))firstRetained=retained.getJSONObject(i);
            assertNotNull(firstRetained);assertEquals(first.getString("taskId"),firstRetained.getString("taskId"));assertEquals(first.getString("runId"),firstRetained.getString("runId"));assertFalse(firstRetained.getBoolean("workerSlotHeld"));
            module.handle("send",j("message","ordinary third","clientMessageId",UUID.randomUUID().toString()));idle(module);assertEquals(3,starts.get());assertEquals(3,calls.size());
            JSONObject resumed=module.handle("send",j("message","edit original","clientMessageId",UUID.randomUUID().toString(),"conversationId",first.getString("conversationId")));idle(module);
            assertEquals(first.getString("taskId"),resumed.getString("taskId"));assertEquals(first.getString("runId"),resumed.getString("runId"));assertSame(original,calls.get(3));assertEquals(3,starts.get());assertEquals(1,appends.get());
            held.remove(original);module.releaseDevelopmentTurn(original);
            assertThrows(IllegalStateException.class,()->module.handle("send",j("message","stale original","clientMessageId",UUID.randomUUID().toString(),"conversationId",first.getString("conversationId"),"taskId",first.getString("taskId"),"runId",first.getString("runId"),"epoch",1)));
        }
    }
    @Test public void conversationCancelStopsOnlyItsYieldedOriginalWhileAnotherConversationRuns()throws Exception {
        var android=RuntimeEnvironment.getApplication();android.getSharedPreferences("private_settings",0).edit().putString("baseUrl","http://127.0.0.1:12345/v1").putString("model","fixture").putString("provider","openai_chat").commit();
        FeatureContext context=new FeatureContext(android,new SettingsStore(android),new SecretVault(android),event->{});Map<String,RunControl> controls=new ConcurrentHashMap<>();Map<RunControl,JSONObject> bindings=new ConcurrentHashMap<>();Set<RunControl> held=ConcurrentHashMap.newKeySet();AtomicInteger starts=new AtomicInteger(),cancelCalls=new AtomicInteger();CountDownLatch thirdEntered=new CountDownLatch(1),releaseThird=new CountDownLatch(1);
        try(AssistantModule module=new AssistantModule(context,null,(cid,goal,history,config,control)->{if(!held.contains(control)){thirdEntered.countDown();assertTrue(releaseThird.await(5,TimeUnit.SECONDS));}return j("content","native answer","developmentRetained",held.contains(control));})){
            module.setSourceSynchronizer(()->{});module.setNativeInputHost(new AssistantModule.NativeInputHost(){
                public JSONObject start(String cid,String goal,JSONObject message,ModelConfig config,RunControl control)throws Exception {control.bindHostRun(UUID.randomUUID().toString(),UUID.randomUUID().toString(),1);var identity=control.hostRunIdentity();JSONObject binding=j("taskId",identity.taskId(),"runId",identity.runId(),"epoch",1);bindings.put(control,binding);controls.put(cid,control);if(starts.incrementAndGet()<=2)held.add(control);return binding;}
                public JSONObject append(String cid,JSONObject message,String mode,JSONObject expected,RunControl control)throws Exception {throw new AssertionError("cancel must not resume or create a run");}
                public boolean retainsDevelopment(RunControl control){return held.contains(control);}
                public void cancel(RunControl control){cancelCalls.incrementAndGet();control.cancel();held.remove(control);}
            });
            try{
                JSONObject first=module.handle("send",j("message","first","clientMessageId",UUID.randomUUID().toString()));idle(module);JSONObject second=module.handle("send",j("message","second","clientMessageId",UUID.randomUUID().toString()));idle(module);JSONObject third=module.handle("send",j("message","third","clientMessageId",UUID.randomUUID().toString()));assertTrue(thirdEntered.await(5,TimeUnit.SECONDS));
                assertFalse(module.handle("cancel",j("conversationId","not-the-original")).getBoolean("cancellationRequested"));
                assertThrows(SecurityException.class,()->module.handle("cancel",j("conversationId",first.getString("conversationId"),"taskId",first.getString("taskId"),"runId","stale-run","epoch",1)));assertFalse(controls.get(first.getString("conversationId")).isCancelled());
                assertTrue(module.handle("cancel",j("conversationId",first.getString("conversationId"))).getBoolean("cancellationRequested"));assertTrue(controls.get(first.getString("conversationId")).isCancelled());assertFalse(controls.get(second.getString("conversationId")).isCancelled());assertFalse(controls.get(third.getString("conversationId")).isCancelled());assertEquals(1,cancelCalls.get());
                assertFalse(module.handle("cancel",j("conversationId",first.getString("conversationId"))).getBoolean("cancellationRequested"));releaseThird.countDown();idle(module);
                assertTrue(module.handle("cancel",j("conversationId",second.getString("conversationId"))).getBoolean("cancellationRequested"));assertEquals(2,cancelCalls.get());assertEquals(3,starts.get());
                assertThrows(IllegalStateException.class,()->module.handle("send",j("message","stale","clientMessageId",UUID.randomUUID().toString(),"conversationId",first.getString("conversationId"),"taskId",first.getString("taskId"),"runId",first.getString("runId"),"epoch",1)));
            }finally{releaseThird.countDown();}
        }
    }

}
