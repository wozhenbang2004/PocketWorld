package dev.pocketengine.features.memory;

import static dev.pocketengine.features.memory.MemoryRecallAgentJourneyNativeTest.*;
import static org.junit.Assert.*;

import android.app.Application;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.core.RunJournal;
import dev.pocketengine.core.RunOutcome;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.FeatureHost;
import dev.pocketengine.features.HostToolSupervisor;
import dev.pocketengine.runtime.AgentRunner;
import dev.pocketengine.runtime.context.ContextBudgetPolicy;
import dev.pocketengine.runtime.context.ContextWindow;
import dev.pocketengine.store.TaskStore;
import dev.pocketengine.testutil.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;

/** Real original Agent/Host/Book/SQLite/Gateway. Only provider replies are scripted;
 * historical memory prose is intentionally distinct from an original source/effect proof. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class HistoricalMemoryRecallAgentNativeTest {
    private static final long PAST=1_700_000_000_000L;
    private static final String OLD="residency FORMER_CEDAR_HARBOR_ONLY",NOW="residency CURRENT_MAPLE_HARBOR_ONLY";

    private static JSONObject remembered(String text,String value,long at)throws Exception{
        return new JSONObject().put("layer","fact").put("text",text).put("assertion",new JSONObject()
                .put("version",1).put("kind","told").put("subjectId","owner").put("subjectType","owner")
                .put("subjectLabel","Owner").put("predicate","residency").put("object",value)
                .put("eventTime",at).put("validFrom",at).put("evidenceSpans",new JSONArray()));
    }
    private static JSONObject save(Fixture fixture,String text,String value,long at)throws Exception{
        return fixture.memory.handle("save",new JSONObject().put("scope",scope()).put("record",remembered(text,value,at))).getJSONObject("record");
    }
    private static JSONObject arguments(String mode)throws Exception{return new JSONObject().put("query","residency").put("mode",mode).put("constraints",new JSONObject().put("maxHops",0)).put("limit",8);}
    private static JSONObject recall(String id,JSONObject args)throws Exception{
        JSONObject result=call(id,args);result.getJSONObject("function").put("name","memory_recall");return result;
    }
    private static JSONObject row(JSONArray rows,String id)throws Exception{
        for(int i=0;i<rows.length();i++)if(id.equals(rows.getJSONObject(i).optString("id")))return rows.getJSONObject(i);throw new AssertionError("retained version not returned: "+id);
    }
    private static void historical(JSONObject receipt)throws Exception{
        assertEquals("retained_history",receipt.getString("readMode"));assertEquals("historical_read",receipt.getString("phase"));
        assertTrue(receipt.getBoolean("readOnlyHistory"));assertFalse(receipt.getBoolean("activeRecall"));
        assertFalse(receipt.getBoolean("memoryTextIsOriginalEvidence"));assertFalse(receipt.getBoolean("currentFactVerified"));
        assertFalse(receipt.getBoolean("modelUseVerified"));assertFalse(receipt.getBoolean("executionAuthorized"));
        assertEquals(0,receipt.getJSONObject("queryPlan").getInt("additionalNetworkRequests"));
        assertEquals(0,receipt.getJSONObject("queryPlan").getInt("additionalModelRequests"));
        assertTrue(receipt.getString("context").length()<=12000);assertTrue(receipt.getJSONArray("records").length()<=16);
    }

    @Test public void directOwnerOldVersionReachesOriginalAgentWithoutEnteringCurrentRecallOrExpandingScope()throws Exception{
        try(Fixture f=new Fixture("Review the recorded residency timeline.")){
            JSONObject past=save(f,OLD,"Cedar harbor",PAST),current=save(f,NOW,"Maple harbor",PAST+1000);
            JSONObject foreign=f.memory.handle("save",new JSONObject().put("scope",new JSONObject().put("ownerId","another-owner").put("spaceId","private"))
                    .put("record",remembered("residency FOREIGN_BODY_MUST_NOT_ESCAPE","Elsewhere",PAST))).getJSONObject("record");
            assertEquals(0,past.getJSONArray("sourceRefs").length());
            JSONArray baseline=f.memory.contextSnapshot(scope(),"residency",8,12000).getJSONArray("records");
            assertEquals(1,baseline.length());assertEquals(current.getString("id"),baseline.getJSONObject(0).getString("id"));
            JSONObject historical=arguments("historical"),forged=arguments("historical").put("scope",new JSONObject().put("ownerId","another-owner").put("spaceId","private"));
            try(Gateway gateway=new Gateway(f,(request,body)->request==1?calls(recall("current-read",arguments("current")),recall("past-read",historical),recall("same-past-read",historical),recall("forged-scope",forged)):answer("These are retained versions, requiring a separate check before acting."))){
                RunOutcome outcome=f.run(gateway);gateway.assertHealthy();assertEquals("needs_confirmation",outcome.status);assertEquals(2,gateway.requests.size());
                assertFalse(gateway.requests.get(0).toString().contains(OLD));assertTrue(gateway.requests.get(0).toString().contains(NOW));
                List<JSONObject> results=toolResults(gateway.requests.get(1));assertEquals(4,results.size());
                assertEquals(1,results.get(0).getJSONArray("records").length());assertEquals(current.getString("id"),results.get(0).getJSONArray("records").getJSONObject(0).getString("id"));
                for(JSONObject receipt:results.subList(1,3)){
                    historical(receipt);assertEquals(2,receipt.getJSONArray("records").length());
                    JSONObject old=row(receipt.getJSONArray("records"),past.getString("id"));assertEquals("historical",old.getString("temporalStatus"));
                    assertEquals(PAST,old.getJSONObject("assertion").getLong("eventTime"));assertEquals(TaskMemoryUse.digest(OLD),old.getString("textSha256"));
                    assertEquals(past.getLong("revision"),old.getLong("revision"));assertEquals(0,old.getJSONArray("sourceRefs").length());
                    assertFalse(old.getBoolean("memoryTextIsOriginalEvidence"));assertEquals("current",row(receipt.getJSONArray("records"),current.getString("id")).getString("temporalStatus"));
                }
                assertEquals(results.get(1).getString("historySelectionId"),results.get(2).getString("historySelectionId"));
                assertEquals(results.get(1).getString("historyDigest"),results.get(2).getString("historyDigest"));
                assertEquals("invalid_arguments",results.get(3).getString("code"));assertFalse(gateway.requests.get(1).toString().contains(foreign.getString("text")));
                JSONObject task=f.tasks.get(f.id,false);assertEquals(2,events(task,"memory_history_reference_prepared").size());assertEquals(1,events(task,"memory_tool_reference_prepared").size());
                assertEquals(0,operations(task,"memory_recall").size());
                for(JSONObject provided:operations(task,TaskMemoryJournal.TOOL))for(int i=0;i<provided.getJSONObject("arguments").getJSONArray("records").length();i++)
                    assertNotEquals(past.getString("id"),provided.getJSONObject("arguments").getJSONArray("records").getJSONObject(i).getString("id"));
                assertEquals(2,f.tasks.inferenceUsage(f.id).getInt("attempts"));
            }
        }
    }

    @Test public void historicalSourceRevisionForgetAndCancellationDiscardOutstandingCallbacksWithoutReplay()throws Exception{
        for(String mutation:List.of("source_revision","forget","cancel"))try(Fixture f=new Fixture("Review the recorded residency timeline.")){
            Original past=f.remember(OLD,"person:traveller","Traveller","residency",PAST);
            f.remember(NOW,"person:traveller","Traveller","residency",PAST+1000);
            JSONObject args=arguments("historical");args.getJSONObject("constraints").put("eventBefore",PAST).put("sourceKinds",new JSONArray().put("message"));
            try(Gateway gateway=new Gateway(f,(request,body)->{
                if(request==1)return calls(recall("original-past",args));assertEquals("no model replay after source fence",2,request);
                JSONObject receipt=toolResults(body).get(0);historical(receipt);assertEquals(1,receipt.getJSONArray("records").length());
                JSONObject record=receipt.getJSONArray("records").getJSONObject(0);assertEquals(past.ref,SourceRef.fromJson(record.getJSONArray("sourceRefs").getJSONObject(0)));
                JSONObject span=record.getJSONObject("assertion").getJSONArray("evidenceSpans").getJSONObject(0);
                assertEquals(0,span.getInt("offset"));assertEquals(OLD.length(),span.getInt("chars"));assertEquals(TaskMemoryUse.digest(OLD),span.getString("sha256"));
                if(mutation.equals("source_revision"))f.reviseOriginal(past,"The former source has been corrected.");
                else if(mutation.equals("forget"))f.memory.forgetReviewed(scope(),past.record.getString("id"),past.record.getLong("revision"));
                else {f.tasks.requestCancel(f.id,f.run);f.control.cancel();}
                return calls(recall("late-historical-callback",args));
            })){
                RunOutcome outcome=f.run(gateway);gateway.assertHealthy();assertEquals(2,gateway.requests.size());
                assertTrue(outcome.status,outcome.status.equals("awaiting_user")||outcome.status.equals("cancelled"));
                JSONObject task=f.tasks.get(f.id,false);assertEquals(1,events(task,"memory_history_reference_prepared").size());
                assertEquals(1,events(task,"tool").stream().filter(event->event.has("receipt")).count());assertEquals(0,operations(task,"memory_recall").size());
                assertEquals(2,f.tasks.inferenceUsage(f.id).getInt("attempts"));
                if(mutation.equals("forget")){
                    assertTrue(f.memory.isSourceForgotten(scope(),past.ref));f.assertOriginal(past);
                    MemoryModule reopened=new MemoryModule(new FeatureContext(f.context,null,null,event->{}),(scope,kind,id)->f.originals.resolveMetadata(id).state);
                    assertEquals(0,reopened.searchHistoryForOwner(OLD,"",8).getJSONArray("matches").length());reopened.close();
                }
            }
        }
    }

    @Test public void coldOriginalTaskRecoveryRetiresHistoricalReceiptAndDerivedAnswerUntilAnExplicitFreshRead()throws Exception{
        try(Fixture f=new Fixture("Review the recorded residency timeline.")){
            JSONObject past=save(f,OLD,"Cedar harbor",PAST);save(f,NOW,"Maple harbor",PAST+1000);
            JSONObject args=arguments("historical");args.getJSONObject("constraints").put("eventBefore",PAST);
            try(Gateway gateway=new Gateway(f,(request,body)->{
                if(request==1)return calls(recall("before-cold",args));
                if(request==2)return calls(recall("derived-query-with-old-text",new JSONObject(args.toString()).put("query",OLD)));
                if(request==3)return answer("Historical retained prose: "+OLD);
                if(request==4){assertFalse(body.toString().contains(OLD));assertTrue(body.toString().contains("reference_expired"));assertTrue(body.toString().contains(TaskMemoryHistory.RETIRED));
                    assertEquals("{}",callArguments(body.getJSONArray("messages"),"derived-query-with-old-text"));return calls(recall("explicit-fresh-history",args));}
                assertEquals(5,request);historical(toolResults(body).get(toolResults(body).size()-1));assertTrue(body.toString().contains(OLD));return answer("Fresh historical selection read; it remains historical, not authority.");
            })){
                assertEquals("needs_confirmation",f.run(gateway).status);gateway.assertHealthy();
                String originalSelection=toolResults(gateway.requests.get(1)).get(0).getString("historySelectionId");
                String originalArguments=callArguments(checkpoint(f.database,f.run),"derived-query-with-old-text");assertTrue(originalArguments.contains(OLD));
                f.tasks.endAttempt(f.id,f.run,"needs_confirmation","Native fixture pause after read",new JSONObject());
                // Separate native store, Book, Host, control and run; persisted original data is
                // kept. No process-local historical token is copied into the restored task.
                try(ColdRun cold=new ColdRun(f)){
                    RunOutcome outcome=cold.run(f.goal,gateway);gateway.assertHealthy();assertEquals("needs_confirmation",outcome.status);assertEquals(5,gateway.requests.size());
                    List<JSONObject> finalResults=toolResults(gateway.requests.get(4));JSONObject fresh=finalResults.get(finalResults.size()-1);
                    assertNotEquals(originalSelection,fresh.getString("historySelectionId"));assertEquals(past.getString("id"),fresh.getJSONArray("records").getJSONObject(0).getString("id"));
                    assertEquals(3,events(cold.tasks.get(f.id,false),"memory_history_reference_prepared").size());
                    assertEquals(originalArguments,callArguments(checkpoint(f.database,f.run),"derived-query-with-old-text"));
                    assertEquals(originalArguments,callArguments(checkpoint(f.database,cold.run),"derived-query-with-old-text"));
                }
            }
        }
    }

    private static String callArguments(JSONArray messages,String callId)throws Exception{
        for(int i=0;i<messages.length();i++){JSONArray calls=messages.getJSONObject(i).optJSONArray("tool_calls");for(int j=0;calls!=null&&j<calls.length();j++){
            JSONObject call=calls.getJSONObject(j);if(callId.equals(call.optString("id")))return call.getJSONObject("function").getString("arguments");
        }}throw new AssertionError("original call pairing missing: "+callId);
    }

    @Test public void coldHistoricalArgumentsStayRetiredThroughOriginalAgentContextLimitCompaction()throws Exception{
        try(Fixture f=new Fixture("Review the recorded residency timeline.")){
            save(f,OLD,"Cedar harbor",PAST);save(f,NOW,"Maple harbor",PAST+1000);
            JSONObject args=arguments("historical");args.getJSONObject("constraints").put("eventBefore",PAST);
            try(Gateway first=new Gateway(f,(request,body)->request==1?calls(recall("before-limit-history",args)):
                    request==2?calls(recall("derived-query-with-old-text",new JSONObject(args.toString()).put("query",OLD))):answer("Historical retained prose: "+OLD))){
                assertEquals("needs_confirmation",f.run(first).status);first.assertHealthy();assertEquals(3,first.requests.size());
            }
            RunJournal original=f.tasks.journal(f.id,f.run,f.epoch,f.control);JSONObject stored=original.restore();JSONArray raw=stored.getJSONArray("messages");
            // Other native observation fixtures belong to this same original checkpoint. They
            // contain no memory-derived prose and can be summarized without replaying a tool.
            for(int i=0;i<5;i++)raw.put(new JSONObject().put("role","user").put("hostObservation",true).put("content",new JSONArray().put(new JSONObject().put("type","text")
                    .put("text",TaskMemoryHistory.OBSERVATION_PREFIX+new JSONObject().put("actualImageAttached",false).put("visionNotice","fixture native observation")
                            .put("nativeObservation","SAFE_NATIVE_OBSERVATION_"+i+" "+String.valueOf((char)('a'+i)).repeat(9000))))));
            original.checkpoint(raw,stored.getInt("steps"),new JSONObject().put("noDeviceObservation",true));
            String originalArguments=callArguments(raw,"derived-query-with-old-text"),originalCheckpoint=checkpoint(f.database,f.run).toString();assertTrue(originalArguments.contains(OLD));
            f.tasks.endAttempt(f.id,f.run,"needs_confirmation","Native fixture cold boundary",new JSONObject());
            try(ColdRun cold=new ColdRun(f);ContextLimitGateway provider=new ContextLimitGateway()){
                RunOutcome outcome=cold.runCompacting(f.goal,provider.config());provider.assertHealthy();assertEquals("needs_confirmation",outcome.status);assertEquals(3,provider.requests.size());
                assertEquals(6,cold.tasks.inferenceUsage(f.id).getInt("attempts"));assertEquals(2,events(cold.tasks.get(f.id,false),"memory_history_reference_prepared").size());
                assertEquals(originalCheckpoint,checkpoint(f.database,f.run).toString());assertEquals(originalArguments,callArguments(checkpoint(f.database,cold.run),"derived-query-with-old-text"));
                JSONObject publication;
                try(var row=f.database.rawQuery("SELECT checkpoint FROM runs WHERE run_id=?",new String[]{cold.run})){
                    assertTrue(row.moveToFirst());publication=new JSONObject(row.getString(0)).getJSONObject("contextCompaction");
                }
                assertTrue(publication.getJSONArray("sources").length()>0);assertTrue(publication.getJSONObject("coverage").getBoolean("rawRetained"));
                assertEquals("SUMMARY",publication.getJSONObject("inferenceReceipt").getJSONObject("context").getString("purpose"));
                assertEquals("COMPLETED",publication.getJSONObject("inferenceReceipt").getString("outcome"));
            }
        }
    }

    private static JSONArray checkpoint(SQLiteDatabase database,String run)throws Exception{
        try(var row=database.rawQuery("SELECT checkpoint FROM runs WHERE run_id=?",new String[]{run})){
            assertTrue(row.moveToFirst());return new JSONObject(row.getString(0)).getJSONArray("messages");
        }
    }

    @Test public void nativeHistoricalReadSharesOriginalBoundedSelectionsAndRejectsAlteredReceipts()throws Exception{
        try(Fixture f=new Fixture("Review residency history.")){
            save(f,OLD,"Cedar harbor",PAST);save(f,NOW,"Maple harbor",PAST+1000);
            try(HostToolSupervisor port=new HostToolSupervisor(f.host,f.tasks,f.id,f.run,f.epoch,false)){
                port.beginRun(f.goal,f.control);JSONObject first=port.execute("memory_recall",arguments("historical"),f.control);
                JSONObject validation=port.contextSnapshot(f.goal).getJSONObject("referenceValidation");
                assertTrue(TaskMemoryUse.historicalReceiptCurrent(first,validation.getJSONArray("historicalMemory")));
                JSONObject forged=new JSONObject(first.toString()).put("extraHistoricalBody","invented retained prose");
                assertFalse(TaskMemoryUse.historicalReceiptCurrent(forged,validation.getJSONArray("historicalMemory")));
                for(int i=1;i<16;i++){JSONObject bounded=arguments("historical");bounded.getJSONObject("constraints").put("eventBefore",PAST+1000+i);port.execute("memory_recall",bounded,f.control);}
                JSONObject overflow=arguments("historical");overflow.getJSONObject("constraints").put("eventBefore",PAST+2000);
                assertThrows(IllegalStateException.class,()->port.execute("memory_recall",overflow,f.control));
                assertEquals(first.getString("historySelectionId"),port.execute("memory_recall",arguments("historical"),f.control).getString("historySelectionId"));
                assertThrows(IllegalStateException.class,()->port.execute("memory_recall",arguments("current"),f.control));
                assertEquals(0,f.tasks.inferenceUsage(f.id).getInt("attempts"));port.endRun(f.control);
                assertThrows(SecurityException.class,()->port.responseCurrent(f.control));
            }
        }
    }

    private static final class ColdRun implements AutoCloseable {
        final TaskStore tasks;final FeatureHost host;final String task,run;final int epoch;final RunControl control=new RunControl();
        ColdRun(Fixture previous)throws Exception{
            tasks=new TaskStore(previous.context);task=previous.id;JSONObject attempt=tasks.startAttempt(task,"main_screen",8);run=attempt.getString("runId");epoch=attempt.getInt("coordinatorEpoch");control.bindHostRun(task,run,epoch);
            tasks.authorizeTools(task,run,new JSONArray().put("memory_recall").put("memory_search"));host=new FeatureHost(event->{},tasks,()->false);
            var originals=tasks.conversationRepository();host.register(new MemoryModule(new FeatureContext(previous.context,null,null,event->{}),(scope,kind,id)->"message".equals(kind)?originals.resolveMetadata(id).state:tasks.resolveMemorySource(scope,kind,id),SQLiteMemoryRetrievalBackend.create()));
        }
        RunOutcome run(String goal,Gateway gateway)throws Exception{
            return run(goal,gateway.config(),false);
        }
        RunOutcome runCompacting(String goal,ModelConfig selected)throws Exception{return run(goal,selected,true);}
        private RunOutcome run(String goal,ModelConfig selected,boolean compact)throws Exception{
            ContextBudgetPolicy.Source budgets=route->ContextBudgetPolicy.RouteBudget.forRoute(route,new ContextWindow.Budget(131072,2048,1024,262144,64,0),ContextBudgetPolicy.SourceKind.OWNER_OVERRIDE,1,1);
            RunJournal journal=tasks.journal(task,run,epoch,control);
            if(compact){
                MemoryHostFence fence=new MemoryHostFence(){
                    public void check()throws Exception{control.check();tasks.checkExtensionRun(task,run,epoch,control);}
                    public JSONObject admit(Admission action)throws Exception{synchronized(tasks){check();return action.run();}}
                };
                var raw=new TaskStoreMemoryReader.RawBinding(task,run,epoch,control,fence,host::memoryContextRevision);tasks.bindRawModelExposure(raw);
                journal=journal.withCompaction(tasks.compactionJournal(task,run,epoch,control,raw,()->selected));
            }
            return new AgentRunner(null,new HostToolSupervisor(host,tasks,task,run,epoch,false),budgets).run(task,goal,selected,control,event->{try{tasks.event(task,run,epoch,event);}catch(Exception failure){throw new AssertionError(failure);}},journal);
        }
        public void close()throws Exception{host.close();var field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);((SQLiteDatabase)field.get(tasks)).close();}
    }

    private static final class ContextLimitGateway implements AutoCloseable{
        final HttpServer server;final List<JSONObject> requests=new CopyOnWriteArrayList<>();final AtomicReference<Throwable> failure=new AtomicReference<>();
        ContextLimitGateway()throws Exception{
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/v1/chat/completions",exchange->{
                try{
                    JSONObject body=new JSONObject(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));requests.add(body);int count=requests.size(),status=200;JSONObject response;
                    try{
                        assertFalse(body.toString().contains(OLD));
                        if(count==1){assertTrue(body.toString().contains("reference_expired"));assertEquals("{}",callArguments(body.getJSONArray("messages"),"derived-query-with-old-text"));status=400;
                            response=new JSONObject().put("error",new JSONObject().put("code","context_length_exceeded").put("message","maximum context length exceeded"));
                        }else if(count==2){assertFalse(body.has("tools"));assertTrue(body.toString().contains("settled_history_data"));assertTrue(body.toString().contains("SAFE_NATIVE_OBSERVATION_"));
                            assertFalse(body.toString().contains("derived-query-with-old-text"));response=answer("Retained native observations only; original sources remain available.");
                        }else{assertEquals("one main rejection, one summary, one resumed main",3,count);assertTrue(body.toString().contains("derived_history_summary"));
                            assertEquals("{}",callArguments(body.getJSONArray("messages"),"derived-query-with-old-text"));response=answer("Continued after bounded native compaction; historical sources need a fresh explicit read.");}
                    }catch(Throwable invalid){failure.compareAndSet(null,invalid);status=200;response=answer("Fixture assertion failed.");}
                    byte[] bytes=response.toString().getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);
                }catch(Throwable invalid){failure.compareAndSet(null,invalid);}finally{exchange.close();}
            });server.start();
        }
        ModelConfig config(){return new ModelConfig("http://127.0.0.1:"+server.getAddress().getPort()+"/v1","fixture-key","fixture",false,8,10,"openai_chat",500);}
        void assertHealthy(){if(failure.get()!=null)throw new AssertionError("Native context-limit journey fixture failed",failure.get());}
        public void close(){server.stop(0);}
    }
}
