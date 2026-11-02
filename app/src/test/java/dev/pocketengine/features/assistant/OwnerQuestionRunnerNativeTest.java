package dev.pocketengine.features.assistant;

import dev.pocketengine.testutil.HttpServer;
import dev.pocketengine.EngineApplication;
import dev.pocketengine.features.assistant.notifications.OwnerQuestionReply;
import dev.pocketengine.store.TaskStore;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
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

/** Actual Application -> TaskStore -> original Runner -> local HTTP -> ordinary wait ->
 * native owner answer -> same original Runner. No paid model, phone or account operation. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=EngineApplication.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class OwnerQuestionRunnerNativeTest {
    @org.junit.Before public void nativeKeyFixture(){java.security.Security.addProvider(new java.security.Provider("OwnerQuestionFixtureKeys",1.0d,"Test-only AndroidKeyStore provider"){{put("KeyStore.AndroidKeyStore",dev.pocketengine.features.channels.ChannelTaskHostTest.FixtureKeyStore.class.getName());}});}
    @org.junit.After public void removeNativeKeyFixture(){java.security.Security.removeProvider("OwnerQuestionFixtureKeys");}
    private static final class Fixture implements AutoCloseable {
        final EngineApplication app=(EngineApplication)RuntimeEnvironment.getApplication();final AssistantModule assistant=(AssistantModule)app.features.module("assistant");
        final HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);final AtomicInteger calls=new AtomicInteger();final List<String> requests=new CopyOnWriteArrayList<>();
        Fixture()throws Exception{
            server.createContext("/",exchange->{try{
                requests.add(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));int count=calls.incrementAndGet();
                JSONObject message=count==1?new JSONObject().put("role","assistant").put("content",JSONObject.NULL).put("tool_calls",new JSONArray().put(new JSONObject().put("id","call_owner_question").put("type","function").put("function",new JSONObject().put("name","run_ask_user").put("arguments",new JSONObject().put("question","Which original option?").toString()))))
                    :new JSONObject().put("role","assistant").put("content","FIXTURE-ORIGINAL-ANSWER-RECEIVED");
                byte[] bytes=new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("message",message).put("finish_reason",count==1?"tool_calls":"stop"))).toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
            }finally{exchange.close();}});server.start();
            app.getSharedPreferences("private_settings",0).edit().putString("baseUrl","http://127.0.0.1:"+server.getAddress().getPort()).putString("provider","openai_chat").putString("model","controlled-owner-question-fixture").putBoolean("supportsImageInput",false).putBoolean("sendScreenshots",false).putInt("maxSteps",8).commit();
        }
        JSONObject start(int modelBudget)throws Exception{
            return assistant.handle("send",new JSONObject().put("message","Ask the fixture's ordinary question, then use my answer").put("clientMessageId",UUID.randomUUID().toString())
                .put("executionScope",new JSONObject().put("deadlineAt",0L).put("toolLimit",8).put("modelCallLimit",modelBudget)));
        }
        JSONObject waiting(String id)throws Exception{
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(12);JSONObject task;
            var field=EngineApplication.class.getDeclaredField("ownerQuestionInputs");field.setAccessible(true);OwnerQuestionInputs nativeInputs=(OwnerQuestionInputs)field.get(app);
            do{task=app.tasks.get(id,false);JSONObject question=OwnerQuestionReply.reference(task);
                if(question!=null&&!assistant.snapshot().optBoolean("running")&&nativeInputs.find(id,question.getString("runId"),question.getInt("coordinatorEpoch"),question.getString("requestId"),question.getLong("waitRevision"))!=null)return task;
                Thread.sleep(10);
            }while(System.nanoTime()<until);fail("original Runner did not reach a retained native ordinary question: "+task.optString("status"));return null;
        }
        JSONObject answer(JSONObject task,String text)throws Exception{
            return app.dispatchOwnerAction("answerOwnerQuestion",new JSONObject().put("taskId",task.getString("id")).put("questionIdentity",OwnerQuestionReply.reference(task)).put("text",text));
        }
        JSONObject settled(String id)throws Exception{
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(12);JSONObject task;var field=EngineApplication.class.getDeclaredField("capabilityControls");field.setAccessible(true);
            do{task=app.tasks.get(id,false);if(!task.optString("status").matches("preparing|running")&&!((java.util.Map<?,?>)field.get(app)).containsKey(id))return task;Thread.sleep(10);}while(System.nanoTime()<until);
            fail("original question worker did not settle");return null;
        }
        String grant(String run)throws Exception{var field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);var db=(android.database.sqlite.SQLiteDatabase)field.get(app.tasks);try(var row=db.rawQuery("SELECT grant_json FROM runs WHERE run_id=?",new String[]{run})){assertTrue(row.moveToFirst());return row.getString(0);}}
        int runs(String id)throws Exception{var field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);var db=(android.database.sqlite.SQLiteDatabase)field.get(app.tasks);try(var row=db.rawQuery("SELECT count(*) FROM runs WHERE task_id=?",new String[]{id})){assertTrue(row.moveToFirst());return row.getInt(0);}}
        public void close()throws Exception{assistant.handle("cancel",new JSONObject());assistant.close();server.stop(0);var field=EngineApplication.class.getDeclaredField("capabilityWorkers");field.setAccessible(true);((java.util.concurrent.ExecutorService)field.get(app)).shutdownNow();}
    }
    @Test public void nativeTaskPageAnswerReturnsToSameRunWithOriginalGrantAndCumulativeUsage()throws Exception{try(Fixture f=new Fixture()){
        JSONObject ack=f.start(4),waiting=f.waiting(ack.getString("taskId"));String run=waiting.getString("runId"),grant=f.grant(run);int epoch=waiting.getInt("coordinatorEpoch"),used=waiting.getJSONObject("executionScope").getInt("modelCallsUsed");
        JSONObject identity=OwnerQuestionReply.reference(waiting);JSONObject accepted=f.answer(waiting,"FIXTURE-REAL-OWNER-SECOND-OPTION");assertTrue(accepted.getBoolean("accepted"));assertTrue(accepted.getBoolean("continuationRequested"));assertFalse(accepted.getBoolean("executionAuthorized"));
        JSONObject finished=f.settled(ack.getString("taskId"));assertTrue(finished.getString("status"),List.of("completed","needs_confirmation").contains(finished.getString("status")));
        assertEquals(run,finished.getString("runId"));assertEquals(epoch,finished.getInt("coordinatorEpoch"));assertEquals(1,f.runs(ack.getString("taskId")));assertEquals(grant,f.grant(run));assertEquals(used+1,finished.getJSONObject("executionScope").getInt("modelCallsUsed"));
        assertEquals(2,f.calls.get());assertTrue(f.requests.get(1).contains("FIXTURE-REAL-OWNER-SECOND-OPTION"));JSONObject source=f.app.tasks.conversationRepository().source(accepted.getString("messageId"));assertEquals("user",source.getString("role"));
        assertEquals("consumed",source.getJSONObject("submission").getString("status"));
        JSONObject repeated=f.app.dispatchOwnerAction("answerOwnerQuestion",new JSONObject().put("taskId",ack.getString("taskId")).put("questionIdentity",identity).put("text","FIXTURE-REAL-OWNER-SECOND-OPTION"));assertTrue(repeated.getBoolean("duplicate"));assertEquals(2,f.calls.get());assertEquals(1,f.runs(ack.getString("taskId")));
    }}
    @Test public void exhaustedOriginalModelBudgetKeepsRealAnswerWithoutAnotherHttpRequestOrAttempt()throws Exception{try(Fixture f=new Fixture()){
        JSONObject ack=f.start(1),waiting=f.waiting(ack.getString("taskId"));String grant=f.grant(waiting.getString("runId"));assertEquals(1,waiting.getJSONObject("executionScope").getInt("modelCallsUsed"));
        JSONObject accepted=f.answer(waiting,"FIXTURE-SAVED-NO-MORE-BUDGET");assertTrue(accepted.getBoolean("accepted"));JSONObject retained=f.settled(ack.getString("taskId"));
        assertEquals("awaiting_user",retained.getString("status"));assertEquals(waiting.getString("runId"),retained.getString("runId"));assertEquals(waiting.getInt("coordinatorEpoch"),retained.getInt("coordinatorEpoch"));assertEquals(grant,f.grant(waiting.getString("runId")));assertEquals(1,f.calls.get());assertEquals(1,f.runs(ack.getString("taskId")));
        JSONObject source=f.app.tasks.conversationRepository().source(accepted.getString("messageId"));assertEquals("user",source.getString("role"));assertEquals("accepted",source.getJSONObject("submission").getString("status"));assertFalse(retained.getJSONObject("ownerInputMailbox").getBoolean("open"));
    }}
}
