package dev.pocketengine.browser;

import android.app.Application;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.PhonePort;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.core.RunOutcome;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.FeatureHost;
import dev.pocketengine.features.MemorySourceBridge;
import dev.pocketengine.features.NativeOwnerWorkflows;
import dev.pocketengine.features.memory.MemoryModule;
import dev.pocketengine.features.memory.SourceRef;
import dev.pocketengine.features.memory.ToolOutputCapture;
import dev.pocketengine.features.skills.SkillsModule;
import dev.pocketengine.runtime.AgentRunner;
import dev.pocketengine.runtime.context.CompactionPlan;
import dev.pocketengine.store.TaskStore;
import dev.pocketengine.testutil.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import static dev.pocketengine.browser.BrowserSemanticReadTest.*;
import static org.junit.Assert.*;

/** Real Runner, HTTP gateway, browser source port, original SQLite and owner reader. The DOM,
 * phone sensor and local model responses are controlled fixtures, not physical-device claims. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class FinalAnswerSourcesNativeTest {
    private static JSONObject call(String name,JSONObject arguments)throws Exception{
        return j("role","assistant","content","","tool_calls",new JSONArray().put(j("id","source-call-"+UUID.randomUUID(),"type","function","function",j("name",name,"arguments",arguments.toString()))));
    }
    private static JSONObject scope()throws Exception{return j("ownerId","owner","spaceId","private");}
    private static SQLiteDatabase database(TaskStore tasks)throws Exception{var field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);return (SQLiteDatabase)field.get(tasks);}
    private static JSONObject selection(JSONObject proof)throws Exception{return j("taskId",proof.getString("taskId"),"runId",proof.getString("runId"),"epoch",proof.getInt("coordinatorEpoch"),"answerFingerprint",proof.getString("answerFingerprint"));}
    private static JSONObject request(JSONObject answer,JSONObject source)throws Exception{JSONObject result=j("answer",new JSONObject(answer.toString()),"readId",UUID.randomUUID().toString());if(source!=null)result.put("source",new JSONObject(source.toString()));return result;}
    private static JSONArray kind(JSONObject listing,String kind)throws Exception{JSONArray rows=new JSONArray(),all=listing.getJSONArray("entries");for(int i=0;i<all.length();i++){JSONObject row=all.getJSONObject(i);if(kind.equals(row.getJSONObject("pin").getString("kind")))rows.put(row);}return rows;}

    private static final class Owner implements AutoCloseable {
        final FeatureHost features=new FeatureHost(event->{});final MemorySourceBridge sources;final MemoryModule memory;final NativeOwnerWorkflows nativeOwner;
        Owner(TaskStore tasks)throws Exception{
            FeatureContext context=new FeatureContext(RuntimeEnvironment.getApplication(),null,null,event->{});
            sources=new MemorySourceBridge(features,tasks);memory=new MemoryModule(context,sources::resolve);
            features.register(memory);features.register(new SkillsModule(context,memory::resolveExperience));
            nativeOwner=new NativeOwnerWorkflows(features,context,tasks,sources);
        }
        JSONObject read(JSONObject answer,JSONObject source)throws Exception{return nativeOwner.answerSources(request(answer,source),new RunControl());}
        public void close(){features.close();}
    }
    private static final class Journey implements AutoCloseable {
        final BrowserSemanticReadSqlTest.F browser=new BrowserSemanticReadSqlTest.F(true,null);
        final Owner owner=new Owner(browser.f.tasks);final AtomicInteger modelCalls=new AtomicInteger(),phoneReads=new AtomicInteger();
        final JSONObject answer;final RunOutcome outcome;
        Journey()throws Exception{
            browser.driver.contents.put(browser.tab,"first saved table value 42");AtomicReference<Throwable> failure=new AtomicReference<>();
            HttpServer model=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            model.createContext("/",exchange->{try{
                JSONObject body=new JSONObject(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8)),receipt=null;
                JSONArray messages=body.getJSONArray("messages");for(int i=messages.length()-1;i>=0;i--)if("tool".equals(messages.getJSONObject(i).optString("role"))){receipt=new JSONObject(messages.getJSONObject(i).getString("content"));break;}
                int step=modelCalls.getAndIncrement();JSONObject response;
                if(step==0||step==2){if(step==2){browser.driver.contents.put(browser.tab,"second saved table value 73");browser.ui.call(()->{browser.driver.original.pages.get(browser.tab).put("url","https://example.com/source-two?owner=private").put("title","Second fixture page").put("navigation",2L);return null;});}response=call("browser_task_observe",browser.args("table"));}
                else if(step==1||step==3){assertNotNull(receipt);assertTrue(receipt.toString(),receipt.getBoolean("ok"));response=call("browser_task_retain_source",j("tabId",browser.tab,"observationId",receipt.getString("observationId")));}
                else response=j("role","assistant","content","两次保留观察为 42 和 73。没有独立验证网页业务结果。");
                byte[] bytes=j("choices",new JSONArray().put(j("message",response,"finish_reason",response.has("tool_calls")?"tool_calls":"stop"))).toString().getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
            }catch(Throwable error){failure.compareAndSet(null,error);try{exchange.sendResponseHeaders(500,0);}catch(Exception ignored){}}finally{exchange.close();}});
            model.start();var worker=Executors.newSingleThreadExecutor();
            try{
                PhonePort phone=new PhonePort(){
                    public boolean isConnected(){return true;}
                    public void clearReferences(){}
                    public JSONObject observe(boolean pixels,RunControl control)throws Exception{assertSame(browser.f.control,control);control.check();return j("ok",true,"observationId",UUID.randomUUID().toString(),"packageName","dev.fixture.sources","text","original phone observation "+phoneReads.incrementAndGet(),"nodes",new JSONArray());}
                    public JSONObject execute(String tool,JSONObject arguments,RunControl original){throw new AssertionError("source navigation never writes to the phone");}
                };
                ModelConfig config=new ModelConfig("http://127.0.0.1:"+model.getAddress().getPort(),"fixture-model-key","answer-source-fixture",false,30,8);
                outcome=worker.submit(()->new AgentRunner(phone,browser.port).run(browser.f.binding.taskId,"读取原页面两次并保留依据",config,browser.f.control,event->{try{browser.f.tasks.event(browser.f.binding.taskId,browser.f.binding.runId,browser.f.binding.epoch,event);}catch(Exception error){throw new IllegalStateException(error);}},browser.f.journal)).get(30,TimeUnit.SECONDS);
                if(failure.get()!=null)throw new AssertionError("controlled model response",failure.get());
                assertTrue(outcome.summary,outcome.status.matches("completed|needs_confirmation"));assertEquals(5,modelCalls.get());
                answer=selection(outcome.evidence.getJSONObject("nativeFinalAnswer"));
            }finally{model.stop(0);worker.shutdownNow();worker.awaitTermination(2,TimeUnit.SECONDS);}
        }
        public void close()throws Exception{owner.close();browser.close();}
    }

    @Test public void originalRunnerSourcesReadAfterReopenAndNewRunWithoutModelCallsOrToolReplay()throws Exception{
        try(Journey journey=new Journey()){
            TaskStore tasks=journey.browser.f.tasks;JSONObject listed=journey.owner.read(journey.answer,null);
            assertEquals("current",listed.getString("status"));JSONArray pages=kind(listed,"browser_observation"),phones=kind(listed,"phone_observation");assertEquals(2,pages.length());assertTrue(phones.length()>0);
            int operations=tasks.get(journey.browser.f.binding.taskId,false).getJSONArray("operations").length();assertEquals(4,operations);
            journey.browser.driver.contents.put(journey.browser.tab,"different live page must never substitute");
            JSONObject first=journey.owner.read(listed.getJSONObject("answer"),pages.getJSONObject(0).getJSONObject("pin")),second=journey.owner.read(listed.getJSONObject("answer"),pages.getJSONObject(1).getJSONObject("pin"));
            assertTrue(first.getJSONObject("detail").getString("body").contains("42"));assertTrue(second.getJSONObject("detail").getString("body").contains("73"));assertFalse(first.toString().contains("different live page"));assertFalse(first.toString().contains("owner=private"));
            assertEquals("not_retained",first.getJSONObject("frame").getString("status"));assertFalse(first.getBoolean("modelCalled"));assertFalse(first.getBoolean("modelRelianceVerified"));
            JSONObject repeated=journey.owner.read(listed.getJSONObject("answer"),pages.getJSONObject(0).getJSONObject("pin"));assertEquals(first.getJSONObject("detail").toString(),repeated.getJSONObject("detail").toString());
            JSONObject phone=journey.owner.read(listed.getJSONObject("answer"),phones.getJSONObject(0).getJSONObject("pin"));assertTrue(phone.getJSONObject("detail").getString("body").contains("original phone observation"));assertEquals("not_retained",phone.getJSONObject("frame").getString("status"));
            // The unrelated current task-result screenshot is deliberately present.
            JSONObject evidence=new JSONObject(journey.outcome.evidence.toString()).put("imageBase64","iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a7VYAAAAASUVORK5CYII=");
            tasks.finish(journey.browser.f.binding.taskId,journey.outcome.status,journey.outcome.summary,evidence);
            assertFalse(tasks.get(journey.browser.f.binding.taskId,false).getJSONObject("evidence").optString("imageFile").isEmpty());
            TaskStore reopened=new TaskStore(RuntimeEnvironment.getApplication());try(Owner historical=new Owner(reopened)){
                JSONObject next=tasks.startAttempt(journey.browser.f.binding.taskId,"capabilities",5);assertNotEquals(journey.answer.getString("runId"),next.getString("runId"));
                JSONObject old=historical.read(listed.getJSONObject("answer"),phones.getJSONObject(0).getJSONObject("pin"));assertEquals("current",old.getString("status"));assertEquals(journey.answer.getString("runId"),old.getJSONObject("answer").getString("runId"));assertEquals("not_retained",old.getJSONObject("frame").getString("status"));assertFalse(old.toString().contains("imageBase64"));
                assertEquals(operations,reopened.get(journey.browser.f.binding.taskId,false).getJSONArray("operations").length());assertEquals(5,journey.modelCalls.get());
            }finally{BrowserTaskPersistenceTest.closeDatabase(reopened);}
        }
    }

    @Test public void nativeRangeForgetBlocksTheAnswerAndItsOtherOutputAliasesAfterReopen()throws Exception{
        try(Journey journey=new Journey()){
            JSONObject listed=journey.owner.read(journey.answer,null),page=kind(listed,"browser_observation").getJSONObject(0).getJSONObject("pin");TaskStore tasks=journey.browser.f.tasks;
            SourceRef selected=tasks.selectSavedMemorySource(journey.answer.getString("taskId"),journey.answer.getString("runId"),page.getLong("eventSeq"),3,17);
            JSONObject record=journey.owner.memory.handle("importFromSources",j("scope",scope(),"record",j("text","主人保留的范围依据","layer","fact","sourceRefs",new JSONArray().put(selected.toJson())))).getJSONObject("record");
            journey.owner.nativeOwner.memory("forgetReviewed",j("id",record.getString("id"),"expectedRevision",record.getLong("revision")));
            JSONObject checkpoint;try(Cursor rows=database(tasks).rawQuery("SELECT checkpoint FROM runs WHERE task_id=? AND run_id=?",new String[]{journey.answer.getString("taskId"),journey.answer.getString("runId")})){
                assertTrue(rows.moveToFirst());checkpoint=new JSONObject(rows.getString(0));
            }
            JSONObject retired=checkpoint.getJSONArray("messages").getJSONObject(listed.getJSONObject("answer").getInt("messageIndex"));
            assertTrue(retired.getBoolean("memoryContextRetired"));assertEquals(dev.pocketengine.features.memory.TaskMemoryHistory.RETIRED,retired.getString("content"));
            assertNotEquals(journey.answer.getString("answerFingerprint"),CompactionPlan.fingerprint(retired.getString("content")));
            // Canonical forget retired the answer itself, before its saved-source view is eligible.
            for(JSONObject denied:new JSONObject[]{journey.owner.read(listed.getJSONObject("answer"),page),journey.owner.read(journey.answer,null)}){
                assertEquals("answer_unavailable",denied.getString("status"));assertFalse(denied.has("detail"));assertFalse(denied.has("entries"));assertFalse(denied.has("frame"));
            }
            try(Owner reopened=new Owner(tasks)){JSONObject denied=reopened.read(listed.getJSONObject("answer"),page);assertEquals("answer_unavailable",denied.getString("status"));assertFalse(denied.has("detail"));assertFalse(denied.has("entries"));assertFalse(denied.has("frame"));}
        }
    }

    @Test public void changedOrMissingSavedEventIsPartialAndNeverSubstitutedWithAnotherPage()throws Exception{
        try(Journey journey=new Journey()){
            JSONObject listed=journey.owner.read(journey.answer,null);JSONArray pages=kind(listed,"browser_observation");JSONObject first=pages.getJSONObject(0).getJSONObject("pin"),second=pages.getJSONObject(1).getJSONObject("pin");
            SQLiteDatabase db=database(journey.browser.f.tasks);db.execSQL("DELETE FROM task_events WHERE task_id=? AND native_seq=?",new Object[]{journey.answer.getString("taskId"),first.getLong("eventSeq")});
            JSONObject stale=journey.owner.read(listed.getJSONObject("answer"),first);assertEquals("source_changed",stale.getString("status"));assertFalse(stale.has("detail"));
            JSONObject partial=journey.owner.read(journey.answer,null);assertEquals("current",partial.getString("status"));assertEquals("unavailable",kind(partial,"browser_observation").getJSONObject(0).getString("status"));
            assertTrue(journey.owner.read(listed.getJSONObject("answer"),second).getJSONObject("detail").getString("body").contains("73"));
            JSONObject event;try(Cursor row=db.rawQuery("SELECT event_json FROM task_events WHERE task_id=? AND native_seq=?",new String[]{journey.answer.getString("taskId"),Long.toString(second.getLong("eventSeq"))})){assertTrue(row.moveToFirst());event=new JSONObject(row.getString(0));}
            event.getJSONObject("source").put("excerpt","changed underlying saved bytes");ContentValues corrupt=new ContentValues();corrupt.put("event_json",event.toString());corrupt.put("body_bytes",event.toString().getBytes(StandardCharsets.UTF_8).length);db.update("task_events",corrupt,"task_id=? AND native_seq=?",new String[]{journey.answer.getString("taskId"),Long.toString(second.getLong("eventSeq"))});
            JSONObject changed=journey.owner.read(listed.getJSONObject("answer"),second);assertEquals("source_changed",changed.getString("status"));assertFalse(changed.toString().contains("changed underlying saved bytes"));
        }
    }

    @Test public void nativeOwnerCancellationCannotCancelTheOriginalRunOrDeliverALateFrame()throws Exception{
        try(Journey journey=new Journey()){
            JSONObject listed=journey.owner.read(journey.answer,null),page=kind(listed,"browser_observation").getJSONObject(0).getJSONObject("pin");RunControl stopped=new RunControl();stopped.cancel();
            assertThrows(InterruptedException.class,()->journey.owner.nativeOwner.answerSources(request(listed.getJSONObject("answer"),page),stopped));
            journey.owner.nativeOwner.installAnswerSourceFrameReader((identity,read)->{read.cancel();return j("status","not_retained");});
            assertThrows(InterruptedException.class,()->journey.owner.nativeOwner.answerSources(request(listed.getJSONObject("answer"),page),new RunControl()));
            assertFalse(journey.browser.f.control.isCancelled());assertEquals(5,journey.modelCalls.get());
        }
    }

    @Test public void alteredAnswerEpochMessageOrFramePinNeverReceivesAnotherBody()throws Exception{
        try(Journey journey=new Journey()){
            JSONObject listed=journey.owner.read(journey.answer,null),page=kind(listed,"browser_observation").getJSONObject(0).getJSONObject("pin");
            JSONObject wrongEpoch=new JSONObject(journey.answer.toString()).put("epoch",journey.answer.getInt("epoch")+1);assertThrows(SecurityException.class,()->journey.owner.read(wrongEpoch,null));
            JSONObject wrongAnswer=new JSONObject(listed.getJSONObject("answer").toString()).put("messageRevision","a".repeat(64));assertEquals("answer_unavailable",journey.owner.read(wrongAnswer,page).getString("status"));
            JSONObject wrongPage=new JSONObject(page.toString()).put("sourceRevision","b".repeat(64));assertEquals("source_changed",journey.owner.read(listed.getJSONObject("answer"),wrongPage).getString("status"));
            journey.owner.nativeOwner.installAnswerSourceFrameReader((identity,read)->j("status","retained","taskId",identity.taskId,"runId",UUID.randomUUID().toString(),"epoch",identity.epoch,"sourceId",identity.sourceId,"sourceRevision",identity.sourceRevision,"observationId",identity.observationId,"mimeType","image/png","sha256","c".repeat(64),"imageBase64","c3RhbGU="));
            JSONObject frame=journey.owner.read(listed.getJSONObject("answer"),page);assertEquals("current",frame.getString("status"));assertEquals("unavailable",frame.getJSONObject("frame").getString("status"));assertFalse(frame.toString().contains("c3RhbGU="));
        }
    }

    @Test public void unknownPreparedEffectRemainsUnknownWhenTheOwnerReadsSources()throws Exception{
        try(Journey journey=new Journey()){
            String pending=journey.browser.f.journal.beginOperation("browser_task_open",j("url","https://example.com/unconfirmed"));
            JSONObject before=journey.browser.f.tasks.get(journey.answer.getString("taskId"),false);JSONObject listed=journey.owner.read(journey.answer,null);
            JSONObject after=journey.browser.f.tasks.get(journey.answer.getString("taskId"),false);assertEquals("current",listed.getString("status"));assertEquals(before.getJSONArray("operations").toString(),after.getJSONArray("operations").toString());
            boolean found=false;JSONArray operations=after.getJSONArray("operations");for(int i=0;i<operations.length();i++)if(pending.equals(operations.getJSONObject(i).getString("operationId"))){found=true;assertEquals("prepared",operations.getJSONObject(i).getString("state"));}assertTrue(found);assertEquals(5,journey.modelCalls.get());assertEquals(0,journey.browser.driver.original.mutableDispatches);
        }
    }

    @Test public void exactNativeToolOutputUsesItsOriginalChunksAndRejectsAChangedRevision()throws Exception{
        try(BrowserTaskPersistenceTest.Fixture fixture=new BrowserTaskPersistenceTest.Fixture();Owner owner=new Owner(fixture.tasks)){
            String tool="ssh_read_file";fixture.tasks.authorizeTools(fixture.binding.taskId,fixture.binding.runId,new JSONArray().put(tool));
            String operation=fixture.journal.beginOperation(tool,j("path","fixture.txt"));ToolOutputCapture.Factory capture=fixture.tasks.toolOutputFactory(fixture.binding.taskId,fixture.binding.runId,fixture.binding.epoch,fixture.control,text->text);
            JSONObject nativeText=j("status","completed","content","native retained text: 42\nsecond line","outputComplete",true,"outputTruncated",false);
            JSONObject receipt=new JSONObject(nativeText.toString()).put("outputCapture",capture.begin(tool,operation,ToolOutputCapture.Kind.NATIVE_RESULT_DATA_JSON,fixture.control).record(nativeText,null).metadata());fixture.journal.finishOperation(operation,receipt);
            String finalText="已读取原文本，未执行写操作。";JSONArray messages=new JSONArray().put(call(tool,j("path","fixture.txt")));
            String call=messages.getJSONObject(0).getJSONArray("tool_calls").getJSONObject(0).getString("id");messages.put(j("role","tool","tool_call_id",call,"content",receipt.toString(),"toolOutputCapture",receipt.getJSONObject("outputCapture"))).put(j("role","assistant","content",finalText));
            JSONObject proof=j("format","pocketworld.runner-final-answer.v1","taskId",fixture.binding.taskId,"runId",fixture.binding.runId,"coordinatorEpoch",fixture.binding.epoch,"messageIndex",2,"historyLength",3,"answerFingerprint",CompactionPlan.fingerprint(finalText),"inputBoundaryEmpty",true);fixture.journal.checkpoint(messages,1,j("nativeFinalAnswer",proof));
            JSONObject listed=owner.read(selection(proof),null),pin=kind(listed,"native_tool_output").getJSONObject(0).getJSONObject("pin");
            JSONObject read=owner.read(listed.getJSONObject("answer"),pin);assertTrue(read.getJSONObject("detail").getString("body").contains("native retained text"));assertFalse(read.getJSONObject("detail").getBoolean("executionIndependentlyVerified"));
            database(fixture.tasks).execSQL("UPDATE task_output_sources SET content_fingerprint=? WHERE task_id=? AND run_id=?",new Object[]{"sha256:"+"f".repeat(64),fixture.binding.taskId,fixture.binding.runId});
            assertThrows(IllegalStateException.class,()->owner.read(listed.getJSONObject("answer"),pin));
        }
    }
}
