package dev.pocketengine.features.assistant;

import android.app.Application;
import android.database.sqlite.SQLiteDatabase;
import com.sun.net.httpserver.HttpServer;
import dev.pocketengine.core.*;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.runtime.AgentRunner;
import dev.pocketengine.runtime.context.ContextBudgetPolicy;
import dev.pocketengine.store.TaskStore;
import dev.pocketengine.store.SettingsStore;
import dev.pocketengine.store.SecretVault;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;

/** Original Assistant facade/SQLite/Runner and loopback HTTP. Not a provider, device or Compose test. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class QueuedOwnerInputRunnerJourneyTest {
    private static final String BEFORE="QUEUE-OLD-TEXT",AFTER="QUEUE-EDITED-TEXT",WITHDRAWN="QUEUE-WITHDRAWN-TEXT";
    private static String id(){return UUID.randomUUID().toString();}
    private static final class Server implements AutoCloseable {
        final HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        final ExecutorService workers=Executors.newCachedThreadPool();
        final List<JSONObject> bodies=new CopyOnWriteArrayList<>();
        final CountDownLatch first=new CountDownLatch(1),second=new CountDownLatch(1),release=new CountDownLatch(1);
        Server()throws Exception{
            server.setExecutor(workers);server.createContext("/",exchange->{try{
                int index=bodies.size();bodies.add(new JSONObject(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8)));if(index==1)second.countDown();
                if(index==0){first.countDown();if(!release.await(15,TimeUnit.SECONDS))throw new AssertionError("first physical response gate timed out");}
                byte[] bytes=new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("message",new JSONObject().put("role","assistant").put("content",index==0?"FIRST-ANSWER":"UPDATED-ANSWER")).put("finish_reason","stop"))).toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
            }catch(Exception failure){throw new RuntimeException(failure);}finally{exchange.close();}});server.start();
        }
        String base(){return "http://127.0.0.1:"+server.getAddress().getPort();}
        public void close(){release.countDown();server.stop(0);workers.shutdownNow();}
    }
    private static final class Fixture implements AutoCloseable,AssistantModule.NativeInputHost,AgentRunner.OwnerInputs {
        final Application android=RuntimeEnvironment.getApplication();final TaskStore tasks;final AssistantModule assistant;final SQLiteDatabase db;
        final AtomicInteger abandoned=new AtomicInteger();final CountDownLatch claimed=new CountDownLatch(1),releaseClaim=new CountDownLatch(1);
        final CountDownLatch appendedBeforeAck=new CountDownLatch(1),releaseAck=new CountDownLatch(1);final ExecutorService ownerCalls=Executors.newSingleThreadExecutor();
        volatile boolean holdAck;volatile JSONObject appendedRow;
        volatile JSONObject binding;volatile RunControl control;volatile RunOutcome outcome;volatile boolean holdClaim;volatile ModelConfig captured;
        final List<AgentRunner.OwnerReply> replies=new CopyOnWriteArrayList<>();
        Fixture(Server server)throws Exception{
            android.deleteDatabase("pocket-world.db");java.nio.file.Files.deleteIfExists(new java.io.File(android.getFilesDir(),"private_conversations.json").toPath());
            android.getSharedPreferences("private_settings",0).edit().putString("baseUrl",server.base()).putString("model","fixture-model").putString("provider","openai_chat").putBoolean("sendScreenshots",false).putInt("maxSteps",8).commit();
            tasks=new TaskStore(android);var field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);db=(SQLiteDatabase)field.get(tasks);
            FeatureContext context=new FeatureContext(android,new SettingsStore(android),new SecretVault(android),event->{});
            assistant=new AssistantModule(context,null,this::execute,tasks);assistant.setNativeInputHost(this);
        }
        public JSONObject start(String conversation,String goal,JSONObject message,ModelConfig config,RunControl original)throws Exception{
            JSONObject task=tasks.startAttempt(tasks.createCapabilityTask(goal,conversation).getString("id"),"capabilities",config.maxSteps);control=original;
            String taskId=task.getString("id"),run=task.getString("runId");int epoch=task.getInt("coordinatorEpoch");original.bindHostRun(taskId,run,epoch);
            tasks.openOwnerInputMailbox(taskId,run,epoch,conversation,original);JSONObject row=tasks.appendOwnerGoalInput(taskId,run,epoch,conversation,message,message.optString("inputMode","queue"),original);
            binding=new JSONObject().put("taskId",taskId).put("runId",run).put("epoch",epoch).put("conversationId",conversation);return acknowledgement(row);
        }
        private JSONObject acknowledgement(JSONObject row)throws Exception{JSONObject answer=new JSONObject(binding.toString());for(String key:new String[]{"queueRevision","queueState","queueRole","ownerMessageRevision"})if(row.has(key))answer.put(key,row.get(key));return answer;}
        public JSONObject append(String conversation,JSONObject message,String mode,JSONObject expected,RunControl original)throws Exception{
            assertSame(control,original);assertEquals(binding.getString("taskId"),expected.getString("taskId"));assertEquals(binding.getString("runId"),expected.getString("runId"));
            JSONObject row=tasks.appendOwnerInput(binding.getString("taskId"),binding.getString("runId"),binding.getInt("epoch"),conversation,message,mode,original);
            if(holdAck){appendedRow=new JSONObject(row.toString());appendedBeforeAck.countDown();if(!releaseAck.await(15,TimeUnit.SECONDS))throw new AssertionError("native append/ACK gate timed out");}
            return acknowledgement(row);
        }
        public JSONArray poll(JSONArray included,boolean boundary,boolean close,RunControl original)throws Exception{
            JSONArray rows=tasks.pollOwnerInputs(binding.getString("taskId"),binding.getString("runId"),binding.getInt("epoch"),included,boundary,close,original);
            if(holdClaim&&boundary&&rows.length()>0){claimed.countDown();if(!releaseClaim.await(15,TimeUnit.SECONDS))throw new AssertionError("claim gate timed out");}
            return rows;
        }
        public void completed(AgentRunner.OwnerReply reply,RunControl original)throws Exception{replies.add(reply);assistant.acceptTaskOwnerReply(reply,captured,original);}
        JSONObject execute(String conversation,String goal,JSONArray initial,ModelConfig config,RunControl original)throws Exception{
            captured=config;RunJournal journal=tasks.journal(binding.getString("taskId"),binding.getString("runId"),binding.getInt("epoch"),original);if(initial!=null)journal.checkpoint(initial,0,new JSONObject());
            CapabilityPort port=new CapabilityPort(){public JSONArray tools(){return new JSONArray();}public boolean supports(String name){return false;}public boolean sideEffecting(String name){return false;}public JSONObject execute(String name,JSONObject args,RunControl ignored){throw new AssertionError("no tool effect belongs to this fixture");}public void modelRequestAbandoned(String request){abandoned.incrementAndGet();}};
            AgentRunner.RequestProjection projection=(history,captured,live)->{Set<String> delegated=new HashSet<>();JSONArray rows=tasks.get(binding.getString("taskId"),false).getJSONArray("ownerInputs");for(int i=0;i<rows.length();i++)if(!"withdrawn".equals(rows.getJSONObject(i).optString("queueState")))delegated.add(rows.getJSONObject(i).getString("messageId"));return assistant.projectMemoryHistory(history,delegated,conversation);};
            ContextBudgetPolicy.Source budget=captured->ContextBudgetPolicy.RouteBudget.forRoute(captured,new dev.pocketengine.runtime.context.ContextWindow.Budget(16384,captured.maxOutputTokens,512,2L*1024*1024,64,0),ContextBudgetPolicy.SourceKind.OWNER_OVERRIDE,1,System.currentTimeMillis());
            outcome=new AgentRunner(null,port,budget,projection,this).run(binding.getString("taskId"),goal,config,original,event->{try{tasks.event(binding.getString("taskId"),binding.getString("runId"),binding.getInt("epoch"),event);}catch(Exception failed){throw new IllegalStateException(failed);}},journal);
            tasks.endAttempt(binding.getString("taskId"),binding.getString("runId"),outcome.status,outcome.summary,outcome.evidence);
            return new JSONObject().put("taskId",binding.getString("taskId")).put("content",outcome.evidence.optString("modelSummary",outcome.summary));
        }
        JSONObject first()throws Exception{return assistant.handle("send",new JSONObject().put("message","主人最初的目标").put("clientMessageId",id()).put("mode","queue"));}
        JSONObject append(String text)throws Exception{return append(text,"queue");}
        JSONObject append(String text,String mode)throws Exception{return assistant.handle("send",new JSONObject(binding.toString()).put("message",text).put("clientMessageId",id()).put("mode",mode));}
        JSONObject detail(JSONObject ack)throws Exception{return tasks.queuedOwnerInput(binding.getString("taskId"),binding.getString("conversationId"),ack.getString("messageId"));}
        JSONObject mutate(JSONObject ack,String text,String mode,boolean withdrawn)throws Exception{JSONObject current=detail(ack);return assistant.mutateQueuedOwnerInput(binding.getString("taskId"),binding.getString("conversationId"),ack.getString("messageId"),current.getLong("queueRevision"),current.getLong("ownerMessageRevision"),text,mode,withdrawn);}
        void idle()throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(12);while(assistant.snapshot().getBoolean("running")&&System.nanoTime()<end)Thread.sleep(10);assertFalse("original Runner did not settle",assistant.snapshot().getBoolean("running"));}
        public void close()throws Exception{releaseClaim.countDown();releaseAck.countDown();assistant.close();ownerCalls.shutdownNow();idle();assertTrue("owner send worker terminated",ownerCalls.awaitTermination(5,TimeUnit.SECONDS));db.close();}
    }
    /** Reopens the real DB and a facade with no activeChat, exactly as explicit same-task resume. */
    private static final class ColdResume implements AutoCloseable {
        final TaskStore tasks;final AssistantModule assistant;final SQLiteDatabase db;final RunControl control=new RunControl();final ModelConfig captured;
        final String task,conversation,run;final int epoch;volatile AgentRunner.OwnerReply reply;volatile RunOutcome outcome;
        ColdResume(Application android,JSONObject original,ModelConfig captured)throws Exception{
            this.captured=captured;task=original.getString("taskId");conversation=original.getString("conversationId");tasks=new TaskStore(android);
            var field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);db=(SQLiteDatabase)field.get(tasks);
            assistant=new AssistantModule(new FeatureContext(android,new SettingsStore(android),new SecretVault(android),event->{}),null,null,tasks);
            JSONObject attempt=tasks.startAttempt(task,"capabilities",captured.maxSteps);run=attempt.getString("runId");epoch=attempt.getInt("coordinatorEpoch");control.bindHostRun(task,run,epoch);tasks.openOwnerInputMailbox(task,run,epoch,conversation,control);
        }
        void execute()throws Exception{
            CapabilityPort port=new CapabilityPort(){public JSONArray tools(){return new JSONArray();}public boolean supports(String name){return false;}public boolean sideEffecting(String name){return false;}public JSONObject execute(String name,JSONObject args,RunControl original){throw new AssertionError("cold resume has no native effect tools");}};
            AgentRunner.OwnerInputs inputs=new AgentRunner.OwnerInputs(){
                public JSONArray poll(JSONArray included,boolean boundary,boolean close,RunControl original)throws Exception{assertSame(control,original);return tasks.pollOwnerInputs(task,run,epoch,included,boundary,close,original);}
                public void completed(AgentRunner.OwnerReply completed,RunControl original)throws Exception{reply=completed;assistant.acceptTaskOwnerReply(completed,captured,original);}
            };
            AgentRunner.RequestProjection projection=(history,config,original)->{Set<String> delegated=new HashSet<>();JSONArray rows=tasks.get(task,false).getJSONArray("ownerInputs");for(int i=0;i<rows.length();i++)if(!"withdrawn".equals(rows.getJSONObject(i).optString("queueState")))delegated.add(rows.getJSONObject(i).getString("messageId"));return assistant.projectMemoryHistory(history,delegated,conversation);};
            ContextBudgetPolicy.Source budget=config->ContextBudgetPolicy.RouteBudget.forRoute(config,new dev.pocketengine.runtime.context.ContextWindow.Budget(16384,config.maxOutputTokens,512,2L*1024*1024,64,0),ContextBudgetPolicy.SourceKind.OWNER_OVERRIDE,1,System.currentTimeMillis());
            outcome=new AgentRunner(null,port,budget,projection,inputs).run(task,tasks.get(task,false).getString("goal"),captured,control,event->{try{tasks.event(task,run,epoch,event);}catch(Exception failed){throw new IllegalStateException(failed);}},tasks.journal(task,run,epoch,control));tasks.endAttempt(task,run,outcome.status,outcome.summary,outcome.evidence);
        }
        public void close(){control.cancel();assistant.close();db.close();}
    }
    @Test public void acceptedEditAndWithdrawalReachOnlyOriginalRunnerAndSurviveDatabaseReopen()throws Exception{
        try(Server server=new Server();Fixture f=new Fixture(server)){
            JSONObject first=f.first();assertTrue(server.first.await(5,TimeUnit.SECONDS));JSONObject edit=f.append(BEFORE),retract=f.append(WITHDRAWN);
            String fingerprint=f.tasks.conversationRepository().source(edit.getString("messageId")).getString("requestFingerprint");
            JSONObject updated=f.mutate(edit,AFTER,"queue",false);f.mutate(retract,WITHDRAWN,"queue",true);
            assertEquals(2L,updated.getLong("queueRevision"));assertEquals(fingerprint,f.tasks.conversationRepository().source(edit.getString("messageId")).getString("requestFingerprint"));
            assertThrows(IllegalStateException.class,()->f.assistant.mutateQueuedOwnerInput(first.getString("taskId"),first.getString("conversationId"),edit.getString("messageId"),1,1,"LATE","queue",false));
            server.release.countDown();f.idle();assertEquals("needs_confirmation",f.outcome.status);assertEquals(2,server.bodies.size());
            assertFalse(server.bodies.get(0).toString().contains(BEFORE));String second=server.bodies.get(1).toString();assertTrue(second.contains(AFTER));assertFalse(second.contains(BEFORE));assertFalse(second.contains(WITHDRAWN));
            assertEquals("materialized",f.detail(edit).getString("queueState"));assertEquals("withdrawn",f.detail(retract).getString("queueState"));
            assertEquals("withdrawn",f.assistant.handle("getSubmission",new JSONObject().put("clientMessageId",retract.getString("clientMessageId"))).getString("status"));
            assertEquals(1,f.tasks.list().length());assertEquals(0,f.tasks.get(first.getString("taskId"),false).getJSONArray("operations").length());
            assertTrue(f.tasks.get(first.getString("taskId"),false).getJSONArray("events").toString().contains(BEFORE));
            TaskStore reopened=new TaskStore(f.android);try{JSONObject page=reopened.queuedOwnerInputs(first.getString("taskId"),first.getString("conversationId"));assertEquals(3,page.getJSONArray("items").length());assertEquals("withdrawn",reopened.queuedOwnerInput(first.getString("taskId"),first.getString("conversationId"),retract.getString("messageId")).getString("queueState"));}finally{var field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);((SQLiteDatabase)field.get(reopened)).close();}
        }
    }
    @Test public void promotingPendingQueueToSteerDiscardsOldAnswerWithoutReplayingEffects()throws Exception{
        try(Server server=new Server();Fixture f=new Fixture(server)){
            f.first();assertTrue(server.first.await(5,TimeUnit.SECONDS));JSONObject edit=f.append(BEFORE);f.mutate(edit,AFTER,"steer",false);
            server.release.countDown();f.idle();assertEquals(2,server.bodies.size());assertEquals(1,f.abandoned.get());
            assertTrue(server.bodies.get(1).toString().contains(AFTER));assertFalse(server.bodies.get(1).toString().contains(BEFORE));
            JSONObject conversation=f.assistant.handle("get",new JSONObject().put("conversationId",f.binding.getString("conversationId")));
            assertFalse("discarded response cannot be published",conversation.toString().contains("FIRST-ANSWER"));assertEquals("materialized",f.detail(edit).getString("queueState"));
            assertEquals(0,f.tasks.get(f.binding.getString("taskId"),false).getJSONArray("operations").length());assertFalse(f.control.isCancelled());
        }
    }
    @Test public void claimBeforeCheckpointCannotBeWithdrawnAndExplicitRestartRecoversExactInput()throws Exception{
        try(Server server=new Server();Fixture f=new Fixture(server)){
            JSONObject first=f.first();assertTrue(server.first.await(5,TimeUnit.SECONDS));JSONObject queued=f.append(BEFORE);f.holdClaim=true;server.release.countDown();assertTrue(f.claimed.await(5,TimeUnit.SECONDS));
            JSONObject claimed=f.detail(queued);assertEquals("claimed",claimed.getString("queueState"));assertFalse(claimed.getBoolean("editable"));
            assertThrows(IllegalStateException.class,()->f.mutate(queued,BEFORE,"queue",true));assertEquals(1,server.bodies.size());f.control.cancel();f.releaseClaim.countDown();f.idle();
            try(ColdResume cold=new ColdResume(f.android,first,f.captured)){
                JSONObject before=cold.tasks.queuedOwnerInput(first.getString("taskId"),first.getString("conversationId"),queued.getString("messageId"));assertEquals("claimed",before.getString("queueState"));assertFalse(before.getBoolean("editable"));
                cold.execute();assertEquals("needs_confirmation",cold.outcome.status);assertEquals(2,server.bodies.size());assertTrue(server.bodies.get(1).toString().contains(BEFORE));
                JSONObject source=cold.assistant.handle("getSubmission",new JSONObject().put("clientMessageId",queued.getString("clientMessageId")));
                assertEquals("consumed",source.getString("status"));assertEquals(first.getString("runId"),source.getString("runId"));assertEquals(first.getInt("epoch"),source.getInt("epoch"));
                assertEquals(cold.run,source.getString("consumedRunId"));assertEquals(cold.epoch,source.getInt("consumedEpoch"));assertEquals("materialized",cold.tasks.queuedOwnerInput(first.getString("taskId"),first.getString("conversationId"),queued.getString("messageId")).getString("queueState"));
                assertFalse("cold native completion does not create another active chat",cold.assistant.snapshot().getBoolean("running"));assertEquals(1,cold.tasks.list().length());
                JSONObject duplicate=cold.assistant.acceptTaskOwnerReply(cold.reply,cold.captured,cold.control);assertTrue(duplicate.getBoolean("duplicate"));assertEquals(0,duplicate.getJSONArray("consumed").length());
                assertThrows(SecurityException.class,()->cold.assistant.acceptTaskOwnerReply(f.replies.get(0),cold.captured,cold.control));
                assertEquals(0,cold.tasks.get(cold.task,false).getJSONArray("operations").length());
            }
        }
    }
    @Test public void realRepositoryVersionMismatchStopsOriginalRunnerBeforeAnotherPhysicalRequest()throws Exception{
        try(Server server=new Server();Fixture f=new Fixture(server)){
            f.first();assertTrue(server.first.await(5,TimeUnit.SECONDS));JSONObject queued=f.append(BEFORE);ConversationRepository repo=f.tasks.conversationRepository();
            // Fault injection into the actual native source rows, not a permissive currentness stub.
            // The production queue facade never exposes this independent storage operation.
            repo.transaction(()->{JSONObject source=repo.source(queued.getString("messageId")),message=repo.message(f.binding.getString("conversationId"),queued.getString("messageId"));source.put("revision",2L);message.put("revision",2L).put("content","INDEPENDENT-SOURCE-VERSION");repo.saveSource(source);repo.saveMessage(f.binding.getString("conversationId"),message);return null;});
            server.release.countDown();f.idle();assertEquals(1,server.bodies.size());assertEquals("awaiting_user",f.outcome.status);assertFalse(f.control.isCancelled());
            assertEquals("review_queued_inputs",f.outcome.evidence.getJSONObject("queuedOwnerInput").getString("nextAction"));assertEquals("pending",f.detail(queued).getString("queueState"));
            assertEquals(BEFORE,f.detail(queued).getString("content"));assertEquals(0,f.tasks.get(f.binding.getString("taskId"),false).getJSONArray("operations").length());
        }
    }
    @Test public void steerClaimDuringNativeAppendBeforeAckCommitUsesOriginalRowNotAcknowledgementAsGrant()throws Exception{
        try(Server server=new Server();Fixture f=new Fixture(server)){
            f.first();assertTrue(server.first.await(5,TimeUnit.SECONDS));f.holdAck=true;
            Future<JSONObject> sending=f.ownerCalls.submit(()->f.append("ACK-GAP-OWNER-STEER","steer"));assertTrue(f.appendedBeforeAck.await(5,TimeUnit.SECONDS));
            JSONObject initial=f.assistant.handle("getSubmission",new JSONObject().put("clientMessageId",f.appendedRow.getString("clientMessageId")));
            assertEquals("unconfirmed",initial.getString("status"));assertFalse(initial.has("taskId"));assertFalse(initial.has("runId"));assertFalse(initial.has("epoch"));
            server.release.countDown();assertTrue("real native accepted steer must reach the next physical request before its separate UI ACK commit",server.second.await(5,TimeUnit.SECONDS));
            JSONObject delivered=f.tasks.queuedOwnerInput(f.binding.getString("taskId"),f.binding.getString("conversationId"),f.appendedRow.getString("messageId"));
            assertEquals("materialized",delivered.getString("queueState"));assertFalse(delivered.getBoolean("editable"));assertFalse(f.control.isCancelled());assertTrue(server.bodies.get(1).toString().contains("ACK-GAP-OWNER-STEER"));
            f.releaseAck.countDown();JSONObject ack=sending.get(5,TimeUnit.SECONDS);assertEquals("queued",ack.getString("status"));f.idle();
            assertEquals("needs_confirmation",f.outcome.status);assertEquals(2,server.bodies.size());assertEquals(1,f.abandoned.get());
            assertEquals("materialized",f.detail(ack).getString("queueState"));assertFalse(f.detail(ack).getBoolean("editable"));assertThrows(IllegalStateException.class,()->f.mutate(ack,"ACK-GAP-OWNER-STEER","steer",true));
            assertEquals("consumed",f.assistant.handle("getSubmission",new JSONObject().put("clientMessageId",ack.getString("clientMessageId"))).getString("status"));
        }
    }

    @Test public void completedNativeProofIsOriginalObjectBoundAndCallbackSelectionExpires()throws Exception{
        try(Server server=new Server();Fixture f=new Fixture(server)){
            f.first();assertTrue(server.first.await(5,TimeUnit.SECONDS));server.release.countDown();f.idle();assertEquals(1,f.replies.size());AgentRunner.OwnerReply actual=f.replies.get(0);
            assertTrue(actual.matchesOriginal(f.control));RunControl replacement=new RunControl();replacement.bindHostRun(f.binding.getString("taskId"),f.binding.getString("runId"),f.binding.getInt("epoch"));assertFalse(actual.matchesOriginal(replacement));
            assertThrows(SecurityException.class,()->f.tasks.withQueuedOwnerReply(actual,replacement,selected->{throw new AssertionError("foreign control must not reach source mutation");}));
            TaskStore.QueuedOwnerReplySelection[] retained=new TaskStore.QueuedOwnerReplySelection[1];
            f.tasks.withQueuedOwnerReply(actual,f.control,selected->{selected.requireMutationScope(f.tasks);retained[0]=selected;JSONArray copy=selected.inputs();copy.put(new JSONObject().put("messageId","not-a-native-input"));assertEquals(0,selected.inputs().length());return new JSONObject().put("auditOnly",true);});
            assertThrows(SecurityException.class,()->retained[0].inputs());assertThrows(SecurityException.class,()->retained[0].requireMutationScope(f.tasks));
            int messages=f.assistant.handle("get",new JSONObject().put("conversationId",f.binding.getString("conversationId"))).getJSONArray("messages").length();
            assertTrue(f.assistant.acceptTaskOwnerReply(actual,f.captured,f.control).getBoolean("duplicate"));assertEquals(messages,f.assistant.handle("get",new JSONObject().put("conversationId",f.binding.getString("conversationId"))).getJSONArray("messages").length());assertEquals(1,server.bodies.size());
        }
    }

    @Test public void readingOtherOriginalConversationDuringLiveRunnerDoesNotSwitchDraftGrantOrModelInput()throws Exception{
        try(Server server=new Server();Fixture f=new Fixture(server)){
            String other=id(),message=id();var repository=f.tasks.conversationRepository();repository.transaction(()->{
                repository.saveConversation(new JSONObject().put("conversationId",other).put("title","只读B历史").put("createdAt",1L));
                JSONObject row=new JSONObject().put("id",message).put("revision",1L).put("role","user").put("content","B-ONLY-OWNER-ARCHIVE-NOT-A-INSTRUCTION").put("at",1L);
                repository.saveSource(new dev.pocketengine.features.memory.SourceRef("message",message,1L,"owner","private").toJson().put("conversationId",other).put("role","user").put("deleted",false));repository.saveMessage(other,row);return null;
            });
            f.first();assertTrue(server.first.await(5,TimeUnit.SECONDS));JSONObject original=new JSONObject(f.binding.toString());OwnerComposerState composer=OwnerComposerState.empty();var draft=composer.selectConversation(original.getString("conversationId"));draft=composer.edit(draft.key,draft.revision,"A-UNSENT-DRAFT-REMAINS","steer",new JSONArray());composer.reading(draft.key,5,8,"A-anchor","A-tail",false);var reading=composer.readingConversation(other);
            JSONObject actual=f.assistant.readOriginalConversation(other,"",40);assertEquals(other,actual.getString("conversationId"));assertEquals("B-ONLY-OWNER-ARCHIVE-NOT-A-INSTRUCTION",actual.getJSONArray("messages").getJSONObject(0).getString("content"));composer.reading(reading.key,0,0,message,message,false);
            assertTrue(f.assistant.snapshot().getBoolean("running"));assertEquals(draft.key,composer.activeKey());assertEquals("A-UNSENT-DRAFT-REMAINS",composer.active().text);assertEquals("A-anchor",composer.active().anchorMessage);JSONObject extra=f.append("A-EXACT-SAFE-BOUNDARY-INPUT","steer");server.release.countDown();f.idle();assertEquals(2,server.bodies.size());assertTrue(server.bodies.get(1).toString().contains("A-EXACT-SAFE-BOUNDARY-INPUT"));assertFalse(server.bodies.toString().contains("B-ONLY-OWNER-ARCHIVE-NOT-A-INSTRUCTION"));assertEquals(original.getString("taskId"),f.binding.getString("taskId"));assertEquals(original.getString("runId"),f.binding.getString("runId"));assertEquals("consumed",f.assistant.handle("getSubmission",new JSONObject().put("clientMessageId",extra.getString("clientMessageId"))).getString("status"));assertEquals("A-UNSENT-DRAFT-REMAINS",composer.active().text);assertEquals(message,composer.draft(reading.key).anchorMessage);
        }
    }
}
