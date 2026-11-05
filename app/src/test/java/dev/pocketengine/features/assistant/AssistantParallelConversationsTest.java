package dev.pocketengine.features.assistant;

import android.app.Application;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.store.SecretVault;
import dev.pocketengine.store.SettingsStore;
import dev.pocketengine.store.TaskStore;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Real AssistantModule + original SQLite mailbox; explicitly controlled worker fixtures, not model/provider validation. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35, manifest=Config.NONE, application=Application.class)
public final class AssistantParallelConversationsTest {
    private static JSONObject json(Object... values) throws Exception {
        JSONObject result=new JSONObject();for(int i=0;i<values.length;i+=2)result.put((String)values[i],values[i+1]);return result;
    }
    private static JSONObject send(String goal) throws Exception { return json("message",goal,"clientMessageId",UUID.randomUUID().toString()); }
    private static JSONObject follow(JSONObject original,String goal)throws Exception{return send(goal).put("conversationId",original.getString("conversationId")).put("taskId",original.getString("taskId")).put("runId",original.getString("runId")).put("epoch",original.getInt("epoch"));}
    private static final class Gate {
        final CountDownLatch entered=new CountDownLatch(1), release=new CountDownLatch(1), finished=new CountDownLatch(1);
        final AtomicBoolean started=new AtomicBoolean();
        volatile boolean retainAfterReply;
    }
    private record Bound(String conversation, JSONObject identity, ModelConfig model, RunControl control, Gate gate) { }

    private static final class Fixture implements AutoCloseable, AssistantModule.NativeInputHost {
        final SettingsStore settings;
        final TaskStore tasks;
        final AssistantModule module;
        final Map<String,Gate> gates=new ConcurrentHashMap<>();
        final Map<RunControl,Bound> originals=new ConcurrentHashMap<>();
        final Map<String,ModelConfig> executedModels=new ConcurrentHashMap<>();
        final java.util.List<JSONObject> events=new java.util.concurrent.CopyOnWriteArrayList<>();
        final AtomicInteger starts=new AtomicInteger(),appends=new AtomicInteger(),executions=new AtomicInteger();

        Fixture(boolean legacy) throws Exception {
            var android=RuntimeEnvironment.getApplication();
            android.getSharedPreferences("private_settings",0).edit().putString("baseUrl","http://127.0.0.1:12345/v1")
                    .putString("provider","openai_chat").putString("model","captured-model").putInt("maxSteps",8).putBoolean("sendScreenshots",false).commit();
            settings=new SettingsStore(android);tasks=new TaskStore(android);
            FeatureContext context=new FeatureContext(android,settings,new SecretVault(android),events::add);
            module=legacy?new AssistantModule(context,null,this::execute):new AssistantModule(context,null,this::execute,tasks);
            module.setSourceSynchronizer(()->{});module.setNativeInputHost(this);
        }
        Gate gate(String goal){return gates.computeIfAbsent(goal,key->new Gate());}
        public JSONObject start(String conversation,String goal,JSONObject message,ModelConfig model,RunControl control) throws Exception {
            String task=tasks.createCapabilityTask(goal,conversation).getString("id");tasks.initializeExecutionScope(task,model.maxSteps);
            JSONObject attempt=tasks.startAttempt(task,"capabilities",model.maxSteps);control.bindHostRun(task,attempt.getString("runId"),attempt.getInt("coordinatorEpoch"));
            JSONObject identity=json("taskId",task,"runId",attempt.getString("runId"),"epoch",attempt.getInt("coordinatorEpoch"));
            Bound bound=new Bound(conversation,identity,model,control,gate(goal));originals.put(control,bound);starts.incrementAndGet();
            control.onCancel(()->{try{if(!bound.gate.started.get())tasks.endAttempt(task,identity.getString("runId"),"cancelled","Original admission cancelled",new JSONObject());}
                catch(Exception failed){throw new IllegalStateException(failed);}finally{bound.gate.release.countDown();}});
            tasks.openOwnerInputMailbox(task,identity.getString("runId"),identity.getInt("epoch"),conversation,control);
            tasks.appendOwnerGoalInput(task,identity.getString("runId"),identity.getInt("epoch"),conversation,message,"queue",control);
            return new JSONObject(identity.toString());
        }
        public JSONObject append(String conversation,JSONObject message,String mode,JSONObject expected,RunControl control) throws Exception {
            Bound bound=originals.get(control);assertNotNull(bound);assertEquals(conversation,bound.conversation);
            assertTrue(control.matchesHostRun(expected.getString("taskId"),expected.getString("runId"),expected.getInt("epoch")));
            tasks.appendOwnerInput(expected.getString("taskId"),expected.getString("runId"),expected.getInt("epoch"),conversation,message,mode,control);
            appends.incrementAndGet();return new JSONObject(bound.identity.toString());
        }
        public void cancel(RunControl control) throws Exception {
            Bound bound=originals.get(control);if(bound!=null)tasks.requestCancel(bound.identity.getString("taskId"),bound.identity.getString("runId"));control.cancel();
            if(bound!=null&&bound.gate.retainAfterReply&&bound.gate.finished.getCount()==0)tasks.endAttempt(bound.identity.getString("taskId"),bound.identity.getString("runId"),"cancelled","Original retained admission cancelled",new JSONObject());
        }
        public boolean retainsDevelopment(RunControl control){Bound bound=originals.get(control);return bound!=null&&bound.gate.retainAfterReply&&!control.isCancelled();}
        JSONObject execute(String conversation,String goal,JSONArray history,ModelConfig model,RunControl control) throws Exception {
            Bound bound=originals.get(control);assertNotNull(bound);assertSame(bound.model,model);bound.gate.started.set(true);
            executions.incrementAndGet();executedModels.put(conversation,model);bound.gate.entered.countDown();
            try{
                assertTrue("Worker fixture timed out",bound.gate.release.await(20,TimeUnit.SECONDS));control.check();
                if(bound.gate.retainAfterReply)return json("content","Fixture retained answer","taskId",bound.identity.getString("taskId"),"developmentRetained",true);
                tasks.endAttempt(bound.identity.getString("taskId"),bound.identity.getString("runId"),"completed","Fixture completed",new JSONObject());
                return json("content","Fixture answer for "+goal,"taskId",bound.identity.getString("taskId"));
            }finally{if(control.isCancelled())tasks.endAttempt(bound.identity.getString("taskId"),bound.identity.getString("runId"),"cancelled","Original cancelled",new JSONObject());bound.gate.finished.countDown();}
        }
        Bound bound(JSONObject ack) throws Exception {
            String run=ack.getString("runId");return originals.values().stream().filter(value->run.equals(value.identity.optString("runId"))).findFirst().orElseThrow();
        }
        JSONObject live(String conversation) throws Exception {
            JSONArray live=module.snapshot().getJSONArray("activeTurns");for(int i=0;i<live.length();i++)if(conversation.equals(live.getJSONObject(i).optString("conversationId")))return live.getJSONObject(i);return null;
        }
        void idle(String conversation) throws Exception {
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);while(live(conversation)!=null&&System.nanoTime()<deadline)Thread.sleep(5);assertNull(live(conversation));
        }
        java.util.concurrent.ThreadPoolExecutor workers()throws Exception{
            var field=AssistantModule.class.getDeclaredField("turns");field.setAccessible(true);return (java.util.concurrent.ThreadPoolExecutor)field.get(module);
        }
        JSONObject submission(JSONObject input)throws Exception{return module.handle("getSubmission",json("clientMessageId",input.getString("clientMessageId")));}
        java.util.Set<String> unconfirmedMessages(){
            java.util.Set<String> ids=new java.util.HashSet<>();for(JSONObject event:events){JSONObject payload=event.optJSONObject("payload");if(payload!=null&&"unconfirmed".equals(payload.optString("phase"))&&payload.has("messageId"))ids.add(payload.optString("messageId"));}return ids;
        }
        void seedEarlierConsumedReply(JSONObject ack)throws Exception{
            // Native stored state after an earlier completed reply. This fixture exercises
            // later continuation rejection, not model completion or OwnerReply issuance.
            var repository=tasks.conversationRepository();String conversation=ack.getString("conversationId"),message=ack.getString("messageId");JSONObject row=repository.message(conversation,message);
            tasks.journal(ack.getString("taskId"),ack.getString("runId"),ack.getInt("epoch"),bound(ack).control).checkpoint(new JSONArray().put(json("role","user","content",row.getString("content"),"ownerMessageId",message,"ownerMessageRevision",row.getLong("revision"),"ownerInputMode","queue")),0,new JSONObject());
            repository.transaction(()->{JSONObject state=repository.source(message),consumed=new JSONObject(state.getJSONObject("submission").toString()).put("status","consumed").put("queueState","materialized").put("reconciliationRequired",false);
                state.put("submission",consumed);row.put("submission",new JSONObject(consumed.toString()));repository.saveSource(state);repository.saveMessage(conversation,row);return null;});
        }
        public void close() throws Exception {
            module.close();for(Gate gate:gates.values())gate.release.countDown();
            for(Gate gate:gates.values())if(gate.started.get())assertTrue(gate.finished.await(3,TimeUnit.SECONDS));
        }
    }

    private void assertRejectedAdmission(boolean legacy)throws Exception{
        try(Fixture f=new Fixture(legacy)){
            f.workers().shutdown();JSONObject input=send("original refused before pickup"),ack=f.module.handle("send",input);
            assertEquals("unconfirmed",ack.getString("status"));assertTrue(ack.getBoolean("reconciliationRequired"));assertEquals("released",ack.getString("resourceState"));
            assertEquals("cancelled",f.tasks.get(ack.getString("taskId"),false).getString("status"));assertFalse(f.gate("original refused before pickup").started.get());
            JSONObject stored=f.module.handle("getSubmission",json("clientMessageId",input.getString("clientMessageId")));
            for(String key:new String[]{"messageId","clientMessageId","conversationId","taskId","runId","epoch","status","resourceState"})assertEquals(ack.get(key),stored.get(key));
            assertEquals("unconfirmed",f.module.handle("get",json("conversationId",ack.getString("conversationId"))).getJSONArray("messages").getJSONObject(0).getJSONObject("submission").getString("status"));
            JSONObject duplicate=f.module.handle("send",input);assertTrue(duplicate.getBoolean("duplicate"));assertEquals("unconfirmed",duplicate.getString("status"));assertEquals(1,f.starts.get());assertFalse(f.module.snapshot().getBoolean("running"));
            var android=RuntimeEnvironment.getApplication();FeatureContext context=new FeatureContext(android,f.settings,new SecretVault(android),event->{});
            AssistantModule reopened=legacy?new AssistantModule(context,null):new AssistantModule(context,null,null,f.tasks);
            try{assertEquals("unconfirmed",reopened.handle("getSubmission",json("clientMessageId",input.getString("clientMessageId"))).getString("status"));}
            finally{reopened.close();}
            if(!legacy)assertEquals("admitted",f.tasks.queuedOwnerInput(ack.getString("taskId"),ack.getString("conversationId"),ack.getString("messageId")).getString("queueState"));
        }
    }
    @Test public void synchronousRejectionReturnsAndPersistsTheSameUnconfirmedNativeReceipt()throws Exception{assertRejectedAdmission(false);}
    @Test public void legacyReceiptAlsoRetainsRejectionAcrossReopenWithoutResending()throws Exception{assertRejectedAdmission(true);}

    @Test public void rejectionAfterWaitingUpdatesEveryPendingOriginalReceipt()throws Exception{
        try(Fixture f=new Fixture(false)){
            JSONObject a=f.module.handle("send",send("a")),b=f.module.handle("send",send("b"));
            assertTrue(f.gate("a").entered.await(3,TimeUnit.SECONDS));assertTrue(f.gate("b").entered.await(3,TimeUnit.SECONDS));
            JSONObject input=send("waiting original"),ack=f.module.handle("send",input);assertEquals("accepted",ack.getString("status"));assertEquals("queued",f.live(ack.getString("conversationId")).getString("resourceState"));
            JSONObject extra=follow(ack,"waiting original follow-up"),extraAck=f.module.handle("send",extra);assertEquals("queued",extraAck.getString("status"));f.events.clear();
            f.workers().shutdown();f.gate("a").release.countDown();f.idle(a.getString("conversationId"));f.idle(ack.getString("conversationId"));
            JSONObject stored=f.module.handle("getSubmission",json("clientMessageId",input.getString("clientMessageId")));
            assertEquals("unconfirmed",stored.getString("status"));assertEquals("released",stored.getString("resourceState"));assertEquals(ack.getString("runId"),stored.getString("runId"));assertEquals(ack.getString("messageId"),stored.getString("messageId"));
            JSONObject extraStored=f.submission(extra);assertEquals("unconfirmed",extraStored.getString("status"));assertEquals("released",extraStored.getString("resourceState"));assertEquals(extraAck.getString("messageId"),extraStored.getString("messageId"));assertEquals(ack.getString("runId"),extraStored.getString("runId"));
            assertEquals(java.util.Set.of(ack.getString("messageId"),extraAck.getString("messageId")),f.unconfirmedMessages());
            assertEquals("cancelled",f.tasks.get(ack.getString("taskId"),false).getString("status"));assertFalse(f.gate("waiting original").started.get());
            assertFalse(f.bound(b).control.isCancelled());assertEquals("accepted",f.submission(b).getString("status"));assertEquals(3,f.starts.get());assertTrue(f.module.handle("send",input).getBoolean("duplicate"));
        }
    }
    @Test public void rejectedRetainedContinuationUpdatesNewInputsWithoutRegressingConsumedOrWithdrawnReceipts()throws Exception{
        try(Fixture f=new Fixture(false)){
            f.gate("retained original").retainAfterReply=true;JSONObject input=send("retained original"),ack=f.module.handle("send",input);assertTrue(f.gate("retained original").entered.await(3,TimeUnit.SECONDS));
            f.seedEarlierConsumedReply(ack);JSONObject extra=follow(ack,"new retained input"),extraAck=f.module.handle("send",extra);
            JSONObject withdrawn=follow(ack,"withdraw before retained continuation"),withdrawnAck=f.module.handle("send",withdrawn);
            JSONObject queued=f.tasks.queuedOwnerInput(ack.getString("taskId"),ack.getString("conversationId"),withdrawnAck.getString("messageId"));
            f.module.mutateQueuedOwnerInput(ack.getString("taskId"),ack.getString("conversationId"),withdrawnAck.getString("messageId"),queued.getLong("queueRevision"),queued.getLong("ownerMessageRevision"),withdrawn.getString("message"),"queue",true);
            f.events.clear();f.workers().shutdown();f.gate("retained original").release.countDown();f.idle(ack.getString("conversationId"));
            assertEquals("consumed",f.submission(input).getString("status"));assertEquals("withdrawn",f.submission(withdrawn).getString("status"));
            JSONObject pending=f.submission(extra);assertEquals("unconfirmed",pending.getString("status"));assertEquals("released",pending.getString("resourceState"));assertEquals(ack.getString("runId"),pending.getString("runId"));
            assertEquals(java.util.Set.of(extraAck.getString("messageId")),f.unconfirmedMessages());assertEquals(1,f.executions.get());assertEquals(1,f.starts.get());
            assertEquals("cancelled",f.tasks.get(ack.getString("taskId"),false).getString("status"));
        }
    }

    @Test public void twoOriginalConversationsRunWhileThirdKeepsItsCapturedModelBudgetAndDurableAck() throws Exception {
        try(Fixture f=new Fixture(false)){
            JSONObject a=f.module.handle("send",send("a")),b=f.module.handle("send",send("b"));
            assertTrue(f.gate("a").entered.await(3,TimeUnit.SECONDS));assertTrue(f.gate("b").entered.await(3,TimeUnit.SECONDS));
            JSONObject thirdInput=send("c"),c=f.module.handle("send",thirdInput);
            assertEquals("queued",f.live(c.getString("conversationId")).getString("resourceState"));assertEquals(1,f.gate("c").entered.getCount());
            assertEquals(3,f.module.snapshot().getJSONArray("activeTurns").length());assertFalse(f.module.snapshot().has("activeTurn"));
            JSONObject original=f.tasks.get(c.getString("taskId"),false);assertEquals(8,original.getInt("stepCeiling"));assertEquals(1,original.getJSONArray("ownerInputs").length());
            assertEquals(c.getString("messageId"),f.module.handle("getSubmission",json("clientMessageId",thirdInput.getString("clientMessageId"))).getString("messageId"));
            JSONObject follow=send("a follow-up").put("conversationId",a.getString("conversationId")).put("taskId",a.getString("taskId")).put("runId",a.getString("runId")).put("epoch",a.getInt("epoch"));
            assertEquals("queued",f.module.handle("send",follow).getString("status"));assertTrue(f.module.handle("send",follow).getBoolean("duplicate"));assertEquals(1,f.appends.get());assertEquals(3,f.starts.get());
            f.settings.save(json("model","later-model","maxSteps",40));
            f.gate("a").release.countDown();f.idle(a.getString("conversationId"));assertTrue(f.gate("c").entered.await(3,TimeUnit.SECONDS));
            assertNotNull(f.live(b.getString("conversationId")));assertFalse(f.bound(b).control.isCancelled());
            assertEquals("captured-model",f.executedModels.get(c.getString("conversationId")).model);assertEquals(8,f.executedModels.get(c.getString("conversationId")).maxSteps);
            assertEquals(8,f.tasks.get(c.getString("taskId"),false).getInt("stepCeiling"));assertTrue(f.module.handle("send",thirdInput).getBoolean("duplicate"));assertEquals(3,f.starts.get());
            JSONObject d=f.module.handle("send",send("d"));assertEquals("later-model",f.bound(d).model.model);assertEquals(40,f.bound(d).model.maxSteps);
        }
    }

    @Test public void exactCancelAndExternalQueuedCancelLeaveOtherConversationsAndNewRunIntact() throws Exception {
        try(Fixture f=new Fixture(false)){
            JSONObject a=f.module.handle("send",send("a")),b=f.module.handle("send",send("b"));
            assertTrue(f.gate("a").entered.await(3,TimeUnit.SECONDS));assertTrue(f.gate("b").entered.await(3,TimeUnit.SECONDS));
            JSONObject c=f.module.handle("send",send("c"));f.bound(c).control.cancel();assertNull(f.live(c.getString("conversationId")));assertEquals(1,f.gate("c").entered.getCount());
            assertThrows(IllegalArgumentException.class,()->f.module.handle("cancel",new JSONObject()));
            JSONObject exact=json("conversationId",a.getString("conversationId"),"taskId",a.getString("taskId"),"runId",a.getString("runId"),"epoch",a.getInt("epoch"));
            assertTrue(f.module.handle("cancel",exact).getBoolean("cancellationRequested"));f.idle(a.getString("conversationId"));
            assertNotNull(f.live(b.getString("conversationId")));assertFalse(f.bound(b).control.isCancelled());
            JSONObject replacement=f.module.handle("send",send("replacement").put("conversationId",a.getString("conversationId")));
            assertNotEquals(a.getString("runId"),replacement.getString("runId"));
            assertThrows(SecurityException.class,()->f.module.handle("cancel",exact));assertFalse(f.bound(replacement).control.isCancelled());assertFalse(f.bound(b).control.isCancelled());
        }
    }

    @Test public void eleventhConversationRemainsUnsavedAndCloseCannotStartQueuedWorkers() throws Exception {
        try(Fixture f=new Fixture(false)){
            for(int i=0;i<10;i++)f.module.handle("send",send("task-"+i));
            assertTrue(f.gate("task-0").entered.await(3,TimeUnit.SECONDS));assertTrue(f.gate("task-1").entered.await(3,TimeUnit.SECONDS));
            JSONObject extra=send("keep this draft");assertThrows(IllegalStateException.class,()->f.module.handle("send",extra));
            assertEquals(10,f.tasks.list().length());assertEquals(10,f.module.handle("list",new JSONObject()).getJSONArray("conversations").length());
            assertThrows(IllegalArgumentException.class,()->f.module.handle("getSubmission",json("clientMessageId",extra.getString("clientMessageId"))));
            f.module.close();
            for(int i=2;i<10;i++){assertEquals(1,f.gate("task-"+i).entered.getCount());assertFalse(f.gate("task-"+i).started.get());}
            assertFalse(f.module.snapshot().getBoolean("running"));assertEquals(0,f.module.snapshot().getJSONObject("turnSlots").getInt("available"));
        }
    }

    @Test public void legacyWindowNeverEvictsAnOlderConversationWhoseOriginalWorkerStillRuns() throws Exception {
        try(Fixture f=new Fixture(true)){
            JSONObject old=f.module.handle("send",send("long original"));assertTrue(f.gate("long original").entered.await(3,TimeUnit.SECONDS));
            for(int i=0;i<22;i++){
                String goal="quick-"+i;f.gate(goal).release.countDown();JSONObject quick=f.module.handle("send",send(goal));f.idle(quick.getString("conversationId"));
            }
            assertNotNull(f.live(old.getString("conversationId")));assertEquals(1,f.module.handle("get",json("conversationId",old.getString("conversationId"))).getJSONArray("messages").length());
            f.gate("long original").release.countDown();f.idle(old.getString("conversationId"));
            assertEquals(2,f.module.handle("get",json("conversationId",old.getString("conversationId"))).getJSONArray("messages").length());
        }
    }
}
