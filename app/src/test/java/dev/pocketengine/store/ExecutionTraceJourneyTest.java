package dev.pocketengine.store;

import android.app.Application;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.core.CapabilityPort;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.core.RunJournal;
import dev.pocketengine.core.RunOutcome;
import dev.pocketengine.runtime.AgentRunner;
import dev.pocketengine.runtime.usage.InferenceCapture;
import dev.pocketengine.runtime.usage.InferenceReceipt;
import dev.pocketengine.runtime.usage.ProviderUsage;
import dev.pocketengine.runtime.usage.ToolTraceCapture;
import dev.pocketengine.runtime.usage.ExecutionTraceCapture;
import dev.pocketengine.features.memory.SourceRef;
import dev.pocketengine.testutil.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** Actual TaskStore/Runner/Gateway with a local HTTP fixture; no provider/device acceptance claim. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=35, manifest=Config.NONE, application=Application.class) @SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class ExecutionTraceJourneyTest {
    private static final class Fixture implements AutoCloseable {
        final Application app = RuntimeEnvironment.getApplication(); final TaskStore store; final String task, run; final int epoch;
        final RunControl original = new RunControl(); final RunJournal journal;
        Fixture() throws Exception {
            app.deleteDatabase("pocket-world.db");store = new TaskStore(app);
            store.conversationRepository().transaction(()->{store.conversationRepository().saveConversation(new JSONObject().put("conversationId","conversation").put("title","Trace fixture").put("messages",new JSONArray()));return null;});
            JSONObject row = store.startAttempt(store.createCapabilityTask("trace fixture", "conversation").getString("id"), "capabilities", 12);
            task = row.getString("id");run = row.getString("runId");epoch = row.getInt("coordinatorEpoch");
            original.bindHostRun(task, run, epoch);journal = store.journal(task, run, epoch, original);
        }
        JSONObject page(String run, String cursor, int limit) throws Exception { return store.ownerExecutionTrace(task, run, cursor, limit, new RunControl()); }
        JSONObject detail(String run,String node) throws Exception {return store.readOwnerExecutionTraceDetail(store.prepareOwnerExecutionTraceDetail(task,run,node,new RunControl()));}
        SQLiteDatabase db()throws Exception {var field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);return (SQLiteDatabase)field.get(store);}
        InferenceCapture capture() throws Exception {
            return new InferenceCapture(new ModelConfig("https://fixture.invalid/v1", "DO-NOT-LEAK", "fixture-model", false, 8, 10), original, journal.inferenceTarget(InferenceReceipt.Purpose.MAIN, ""));
        }
        public void close() throws Exception {db().close();}
    }
    private static JSONArray kind(JSONObject page, String kind) throws Exception {
        JSONArray result = new JSONArray(), nodes = page.getJSONArray("nodes");
        for (int i = 0; i < nodes.length(); i++) if (kind.equals(nodes.getJSONObject(i).getString("kind"))) result.put(nodes.get(i));
        return result;
    }
    @Test public void sqlPagesDeduplicateNativeStartsPreserveRawUsageAndDoNotMutateExecution() throws Exception {
        try (Fixture f = new Fixture()) {
            InferenceCapture model = f.capture(); model.dispatch();
            ToolTraceCapture tool = new ToolTraceCapture(f.task, f.original, "model:" + model.callId, model.callId, "provider-call", "read_file", "", "", new JSONObject().put("path", "fixture"), event -> f.store.event(f.task, event));
            JSONObject running = kind(f.page("", "", 100), "tool").getJSONObject(0); assertEquals("running", running.getString("status"));assertTrue(running.isNull("durationMs"));
            tool.dispatchEntered();tool.finish(new JSONObject().put("ok", true).put("text", "original result"), null, null, f.original);
            model.usage.observe(new JSONObject().put("usage", new JSONObject().put("prompt_tokens", 17).put("completion_tokens", 4)), ProviderUsage.Stage.FINAL);model.finish(InferenceReceipt.Outcome.COMPLETED);
            String before = f.store.get(f.task, false).toString();JSONObject all = f.page("", "", 100);
            JSONArray tools = kind(all, "tool"), models = kind(all, "model");assertEquals(1, tools.length());assertEquals(1, models.length());
            assertEquals(models.getJSONObject(0).getString("id"), tools.getJSONObject(0).getString("parentId"));assertTrue(tools.getJSONObject(0).getBoolean("parentVisible"));
            assertEquals("17", models.getJSONObject(0).getJSONObject("usage").getJSONObject("counters").getJSONObject("INPUT").get("value").toString());
            assertEquals("unknown", models.getJSONObject(0).getJSONObject("usage").getJSONObject("counters").getJSONObject("CACHE_READ").getString("source"));
            assertTrue(tools.getJSONObject(0).getBoolean("timingKnown"));assertTrue(tools.getJSONObject(0).get("elapsedNanos") instanceof String);
            Set<String> nodes = new HashSet<>();String cursor = "";int pages = 0;
            do { JSONObject page = f.page(f.run, cursor, 1);assertFalse(page.getString("cursor").isEmpty());
                JSONArray rows = page.getJSONArray("nodes");for (int i = 0; i < rows.length(); i++) assertTrue(nodes.add(rows.getJSONObject(i).getString("id")));
                cursor = page.getString("nextCursor");assertTrue(++pages < 20);
            } while (!cursor.isEmpty());
            assertEquals(all.getJSONArray("nodes").length(), nodes.size());assertEquals(before, f.store.get(f.task, false).toString());
            assertEquals(0, f.store.get(f.task, false).getJSONArray("operations").length());
            assertThrows(SecurityException.class, () -> f.store.ownerExecutionTrace(f.task, "", "", 100, f.original));
            RunControl stopped = new RunControl();stopped.cancel();assertThrows(InterruptedException.class, () -> f.store.ownerExecutionTrace(f.task, "", "", 100, stopped));
        }
    }
    @Test public void historicalRunIsExplicitAndMissingTerminalAfterRunEndsStaysUnknown() throws Exception {
        try (Fixture f = new Fixture()) {
            InferenceCapture old = f.capture();old.dispatch();
            new ToolTraceCapture(f.task, f.original, "model:" + old.callId, old.callId, "provider", "read_file", "", "", new JSONObject(), event -> f.store.event(f.task, event));
            f.store.event(f.task, new JSONObject().put("type", "progress").put("message", "unbound historical event"));
            f.store.endAttempt(f.task, f.run, "interrupted", "process stopped", new JSONObject());
            JSONObject next = f.store.startAttempt(f.task, "capabilities", 12), page = f.page(f.run, "", 100);
            assertEquals(f.run, page.getString("runId"));assertEquals(2, page.getJSONArray("runs").length());assertEquals("unknown", kind(page, "tool").getJSONObject(0).getString("status"));
            assertTrue(kind(page, "model").getJSONObject(0).isNull("durationMs"));assertTrue(page.getInt("unboundLegacyEvents") > 0);
            assertEquals(next.getString("runId"), f.page("", "", 100).getString("runId"));assertEquals(0, kind(f.page("", "", 100), "model").length());
            assertThrows(SecurityException.class, () -> f.page(next.getString("runId"), page.getString("cursor"), 100));
            assertThrows(SecurityException.class, () -> f.page(UUID.randomUUID().toString(), "", 100));
        }
    }
    @Test public void originalRunnerRecordsReadonlyFailureAndRejectionWithPhysicalModelParent() throws Exception {
        AtomicInteger requests = new AtomicInteger(), dispatches = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                exchange.getRequestBody().readAllBytes();JSONObject message = new JSONObject().put("role", "assistant");
                if (requests.getAndIncrement() == 0) {
                    JSONArray calls = new JSONArray();for (String name : new String[]{"fixture_read", "fixture_missing", "fixture_rejected"})
                        calls.put(new JSONObject().put("id", "provider-" + name).put("type", "function").put("function", new JSONObject().put("name", name).put("arguments", "{}")));
                    message.put("tool_calls", calls);
                } else message.put("content", "fixture complete");
                byte[] bytes = new JSONObject().put("model", "served-model").put("choices", new JSONArray().put(new JSONObject().put("finish_reason", message.has("tool_calls") ? "tool_calls" : "stop").put("message", message)))
                        .put("usage", new JSONObject().put("prompt_tokens", 23).put("completion_tokens", 5)).toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");exchange.sendResponseHeaders(200, bytes.length);exchange.getResponseBody().write(bytes);
            } catch (Exception failure) { throw new RuntimeException(failure); } finally { exchange.close(); }
        });server.start();
        try (Fixture f = new Fixture()) {
            CapabilityPort port = new CapabilityPort() {
                public JSONArray tools() throws Exception {
                    JSONArray values = new JSONArray();for (String name : new String[]{"fixture_read", "fixture_missing", "fixture_rejected"}) values.put(new JSONObject().put("type", "function").put("function", new JSONObject().put("name", name).put("description", "fixture read").put("parameters", new JSONObject().put("type", "object").put("properties", new JSONObject()).put("additionalProperties", false))));return values;
                }
                public boolean supports(String name) { return name.startsWith("fixture_"); }
                public boolean sideEffecting(String name) { return false; }
                public void validate(String name, JSONObject arguments) { if (name.equals("fixture_rejected")) throw new IllegalArgumentException("fixture rejected"); }
                public JSONObject execute(String name, JSONObject args, RunControl control) throws Exception {
                    control.check();dispatches.incrementAndGet();return new JSONObject().put("ok", !name.equals("fixture_missing")).put("code", name.equals("fixture_missing") ? "file_missing" : "read_complete")
                            .put("message", "DO-NOT-LEAK").put("authorization", "SECRET-AUTH");
                }
            };
            String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
            ModelConfig config = new ModelConfig(endpoint, "DO-NOT-LEAK", "fixture-model", false, 8, 10, "openai_chat", 512).withCleartextConsent(ModelConfig.cleartextConsentFor("openai_chat", endpoint));
            RunOutcome result = new AgentRunner(null, port, selected -> null).run(f.task, "inspect the fixture files", config, f.original, event -> {
                try { f.store.event(f.task, event); } catch (Exception failedOriginalEvent) { throw new IllegalStateException(failedOriginalEvent); }
            }, f.journal);
            assertNotEquals(result.summary, "failed", result.status);assertEquals(2, dispatches.get());
            JSONObject page = f.page("", "", 100);JSONArray tools = kind(page, "tool"), models = kind(page, "model");assertEquals(3, tools.length());assertEquals(2, models.length());
            Set<String> states = new HashSet<>();for (int i = 0; i < tools.length(); i++) {
                JSONObject tool = tools.getJSONObject(i);states.add(tool.getString("status"));assertTrue(tool.getBoolean("timingKnown"));assertEquals(models.getJSONObject(0).getString("id"), tool.getString("parentId"));assertFalse(tool.has("operationId"));
            }
            assertEquals(Set.of("completed", "failed", "rejected"), states);assertFalse(page.toString().contains("DO-NOT-LEAK"));assertFalse(page.toString().contains("SECRET-AUTH"));
            assertTrue(page.getBoolean("compact"));assertFalse(page.toString().contains("fixture complete"));
            assertEquals(1,models.getJSONObject(0).getInt("turnIndex"));assertEquals(2,models.getJSONObject(1).getInt("turnIndex"));assertNotEquals(models.getJSONObject(0).getString("turnId"),models.getJSONObject(1).getString("turnId"));
            int inputs=0,assistants=0;for(int i=0;i<page.getJSONArray("nodes").length();i++){JSONObject node=page.getJSONArray("nodes").getJSONObject(i);if("input".equals(node.optString("role")))inputs++;if("assistant".equals(node.optString("role")))assistants++;}
            assertEquals(1,inputs);assertEquals(2,assistants);
            JSONObject toolDetail=f.detail(f.run,tools.getJSONObject(0).getString("id"));assertEquals("current",toolDetail.getString("sourceState"));assertTrue(toolDetail.getJSONObject("node").has("schema"));assertFalse(toolDetail.toString().contains("SECRET-AUTH"));
            JSONObject modelDetail=f.detail(f.run,models.getJSONObject(1).getString("id")).getJSONObject("node");assertEquals("fixture complete",modelDetail.getJSONObject("output").getString("content"));assertTrue(modelDetail.getJSONObject("options").getBoolean("stream"));
            assertFalse(dev.pocketengine.features.memory.TaskMemoryHistory.wireMessages(TaskTraceDetails.messages(f.db(),f.task,f.run)).toString().contains("nativeTraceCallId"));
            assertEquals(0, f.store.get(f.task, false).getJSONArray("operations").length());
        } finally { server.stop(0); }
    }
    @Test public void selectedOldRunDetailsRecheckSourceRetirementAndKeepOnlyMetadataOnFailure() throws Exception {
        try(Fixture f=new Fixture()){
            String messageId=UUID.randomUUID().toString(),body="EXACT-OWNER-SOURCE-BODY";SourceRef source=new SourceRef("message",messageId,1,"owner","private");
            JSONObject user=new JSONObject().put("id",messageId).put("revision",1).put("role","user").put("content",body).put("clientMessageId",UUID.randomUUID().toString()).put("attachments",new JSONArray());
            var repo=f.store.conversationRepository();repo.transaction(()->{repo.saveSource(source.toJson().put("conversationId","conversation").put("role","user").put("deleted",false));repo.saveMessage("conversation",user);return null;});
            f.store.openOwnerInputMailbox(f.task,f.run,f.epoch,"conversation",f.original);f.store.appendOwnerInput(f.task,f.run,f.epoch,"conversation",user,"queue",f.original);f.store.pollOwnerInputs(f.task,f.run,f.epoch,new JSONArray(),true,false,f.original);
            JSONArray history=new JSONArray().put(new JSONObject().put("role","user").put("content",body).put("ownerMessageId",messageId).put("ownerMessageRevision",1).put("ownerInputMode","queue"));
            f.journal.checkpoint(history,0,new JSONObject());InferenceReceipt.CallSite site=new InferenceReceipt.CallSite("native-cycle",0,1,0);
            InferenceCapture call=new InferenceCapture(new ModelConfig("https://fixture.invalid/v1","fixture-secret","fixture-model",false,8,10),f.original,f.journal.inferenceTarget(InferenceReceipt.Purpose.MAIN,"",site));
            call.dispatch();ExecutionTraceCapture trace=new ExecutionTraceCapture(f.task,f.original,event->f.store.event(f.task,event));trace.request(call.callId,site,history,0);call.finish(InferenceReceipt.Outcome.COMPLETED);
            JSONObject answer=new JSONObject().put("role","assistant").put("content","ORIGINAL-ANSWER-BODY").put("nativeTraceCallId",call.callId);history.put(answer);f.journal.checkpoint(history,0,new JSONObject());trace.assistant(call.callId,site,answer,0);
            JSONObject oldPage=f.page(f.run,"",100);String input="";for(int i=0;i<oldPage.getJSONArray("nodes").length();i++){JSONObject node=oldPage.getJSONArray("nodes").getJSONObject(i);if("input".equals(node.optString("role")))input=node.getString("id");}
            assertFalse(input.isEmpty());assertFalse(oldPage.toString().contains(body));assertEquals(body,f.detail(f.run,input).getJSONObject("node").getString("message"));
            f.store.endAttempt(f.task,f.run,"interrupted","fixture stopped",new JSONObject());JSONObject next=f.store.startAttempt(f.task,"capabilities",12);RunControl nextControl=new RunControl();nextControl.bindHostRun(f.task,next.getString("runId"),next.getInt("coordinatorEpoch"));f.store.journal(f.task,next.getString("runId"),next.getInt("coordinatorEpoch"),nextControl).checkpoint(history,0,new JSONObject());
            assertEquals(body,f.detail(f.run,input).getJSONObject("node").getString("message"));
            TaskStore.TraceDetailRead selected=f.store.prepareOwnerExecutionTraceDetail(f.task,f.run,"model:"+call.callId,new RunControl());assertEquals(1,selected.sources().length());
            repo.transaction(()->{repo.retire(new JSONArray().put(new JSONObject().put("sourceRefs",new JSONArray().put(source.toJson()))),"user_forget");return null;});
            JSONObject withdrawn=f.store.readOwnerExecutionTraceDetail(selected),node=withdrawn.getJSONObject("node");assertEquals("unavailable",withdrawn.getString("sourceState"));assertEquals(f.run,withdrawn.getString("runId"));assertTrue(node.getBoolean("timingKnown"));
            for(String field:new String[]{"input","output","schema","message","raw","preview"})assertFalse(field,node.has(field));assertFalse(withdrawn.toString().contains("ORIGINAL-ANSWER-BODY"));assertFalse(withdrawn.toString().contains(body));
            assertEquals("unavailable",f.store.unavailableOwnerExecutionTraceDetail(selected,"source_forgotten_or_unavailable").getString("sourceState"));assertEquals(1,kind(f.page(f.run,"",100),"model").length());
            assertThrows(SecurityException.class,()->f.store.prepareOwnerExecutionTraceDetail(f.task,next.getString("runId"),"model:"+call.callId,new RunControl()));
        }
    }
    @Test public void scanBudgetResumesAndLargeToolSchemaDoesNotDropNativeSpan() throws Exception {
        try(Fixture f=new Fixture()){
            InferenceCapture model=f.capture();model.dispatch();model.finish(InferenceReceipt.Outcome.COMPLETED);
            String payload="x".repeat(11000);JSONArray values=new JSONArray();for(int i=0;i<225;i++)values.put("option_"+i);
            JSONObject schema=new JSONObject().put("type","function").put("function",new JSONObject().put("name","read_file").put("description",payload).put("parameters",new JSONObject().put("description",payload).put("enum",values).put("authorization","SCHEMA-SECRET")));
            ToolTraceCapture tool=new ToolTraceCapture(f.task,f.original,"model:"+model.callId,model.callId,"provider","read_file","","turn",new JSONObject().put("path",payload).put("detail",payload),schema,1,event->f.store.event(f.task,ToolTraceCapture.bounded(event)));
            tool.dispatchEntered();tool.finish(new JSONObject().put("ok",true).put("text",payload).put("detail",payload),null,null,f.original);
            try(android.database.Cursor row=f.db().rawQuery("SELECT max(body_bytes) FROM task_events WHERE task_id=? AND call_id=?",new String[]{f.task,tool.id()})){assertTrue(row.moveToFirst());assertTrue(row.getLong(0)>65536);}
            assertEquals(1,TaskTraceQuery.toolSource(f.db(),f.task,f.run,f.epoch,new RunControl()).getJSONArray("toolCalls").length());
            JSONObject detail=f.detail(f.run,"tool:"+tool.id()).getJSONObject("node");assertEquals(225,detail.getJSONObject("schema").getJSONObject("function").getJSONObject("parameters").getJSONArray("enum").length());assertFalse(detail.toString().contains("SCHEMA-SECRET"));assertTrue(detail.getBoolean("schemaSanitized"));
            f.db().beginTransaction();try{for(int i=0;i<8200;i++)TaskAuditStore.append(f.db(),f.task,new JSONObject().put("type","model_stream").put("runId",f.run));f.db().setTransactionSuccessful();}finally{f.db().endTransaction();}
            JSONObject first=f.page(f.run,"",100);assertTrue(first.getBoolean("hasMore"));assertEquals(0,first.getJSONArray("nodes").length());JSONObject second=f.page(f.run,first.getString("nextCursor"),100);assertFalse(second.getBoolean("hasMore"));assertEquals(1,kind(second,"model").length());assertEquals(1,kind(second,"tool").length());assertFalse(second.toString().contains(payload));
        }
    }
    @Test public void originalContextReferencesPastGenericArrayLimitStayBodyFreeAndComplete() throws Exception {
        try(Fixture f=new Fixture()){
            JSONArray history=new JSONArray();for(int i=0;i<350;i++)history.put(new JSONObject().put("role","assistant").put("content","retained context "+i));
            f.journal.checkpoint(history,0,new JSONObject());InferenceCapture model=f.capture();model.dispatch();
            new ExecutionTraceCapture(f.task,f.original,event->f.store.event(f.task,event)).request(model.callId,new InferenceReceipt.CallSite("original-cycle",0,1,0),history,0);model.finish(InferenceReceipt.Outcome.COMPLETED);
            JSONObject metadata=kind(f.page(f.run,"",100),"model").getJSONObject(0);assertEquals(350,metadata.getInt("contextRefCount"));assertEquals(350,metadata.getInt("contextRefsRetained"));assertFalse(metadata.getBoolean("contextRefsLimited"));assertFalse(metadata.has("sourceRefs"));assertFalse(metadata.toString().contains("retained context"));
            JSONObject input=f.detail(f.run,metadata.getString("id")).getJSONObject("node").getJSONObject("input");assertEquals(350,input.getJSONArray("messages").length());assertEquals(0,input.getInt("missingMessages"));assertFalse(input.getBoolean("contextRefsLimited"));
        }
    }
}
