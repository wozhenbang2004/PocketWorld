package dev.pocketengine.features.assistant;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.RemoteInput;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.os.Looper;
import android.os.SystemClock;
import dev.pocketengine.EngineApplication;
import dev.pocketengine.PhoneRunService;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.PhonePort;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.assistant.notifications.OwnerQuestionReply;
import dev.pocketengine.features.assistant.notifications.OwnerQuestionReplyReceiver;
import dev.pocketengine.phone.PhoneAccessibilityService;
import dev.pocketengine.phone.observation.ObservationReport;
import dev.pocketengine.runtime.ModelGateway;
import dev.pocketengine.runtime.models.ModelBinding;
import dev.pocketengine.runtime.models.ModelFacts;
import dev.pocketengine.testutil.HttpServer;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.Security;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.annotation.SQLiteMode;

/** Actual App admission, original Service worker, Runner, HTTP, notification receiver and
 * SQLite audit. The physical tree and local model responses are explicit fixtures. This
 * does not certify a real phone, provider, OS background delivery or notification visuals. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=EngineApplication.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public final class OwnerPhoneQuestionContinuationJourneyTest {
    private static final String QUESTION="Which option on this controlled original phone page?";
    private static final String ANSWER="选择第二项；只读检查当前页面并说明结果。PHONE-OWNER-EXACT-ANSWER";
    private static final String RESULT="PHONE-OWNER-ORIGINAL-WORKER-FINISHED";
    private EngineApplication app;
    private SQLiteDatabase db;
    private ServiceController<PhoneAccessibilityService> accessibilityController;
    private ServiceController<PhoneRunService> serviceController;
    private PhoneRunService service;
    private ExecutorService worker;
    private OwnerQuestionReplyReceiver receiver;
    private HttpServer model;
    private String taskId,runId;
    private int epoch;
    private RunControl original;
    private Physical physical;
    private final AtomicInteger calls=new AtomicInteger();
    private final AtomicReference<Throwable> modelFailure=new AtomicReference<>();
    private final List<JSONObject> requests=new CopyOnWriteArrayList<>();
    private final CountDownLatch releaseWorker=new CountDownLatch(1),secondRequest=new CountDownLatch(1),releaseSecondResponse=new CountDownLatch(1);

    @Before public void nativeKeys(){
        Security.addProvider(new java.security.Provider("PhoneQuestionJourneyKeys",1.0d,"Test-only AndroidKeyStore"){{
            put("KeyStore.AndroidKeyStore",dev.pocketengine.features.channels.ChannelTaskHostTest.FixtureKeyStore.class.getName());
        }});
    }

    @Test public void actualRemoteInputResumesTheOriginalWorkerThroughHttpAndSettlesTheSameRun()throws Exception {
        app=(EngineApplication)RuntimeEnvironment.getApplication();db=(SQLiteDatabase)get(app.tasks,"db");
        startModel();configureOriginalModel();
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
        accessibilityController=Robolectric.buildService(PhoneAccessibilityService.class).create();
        var connect=PhoneAccessibilityService.class.getDeclaredMethod("onServiceConnected");connect.setAccessible(true);connect.invoke(accessibilityController.get());
        serviceController=Robolectric.buildService(PhoneRunService.class).create();service=serviceController.get();
        worker=(ExecutorService)get(service,"worker");Thread originalWorker=worker.submit(Thread::currentThread).get(5,TimeUnit.SECONDS);
        // Config.NONE has no manifest receivers. Register the exact production receiver
        // only for this in-process OS fixture; its dispatch and goAsync body stay real.
        receiver=new OwnerQuestionReplyReceiver();IntentFilter filter=new IntentFilter(OwnerQuestionReply.ACTION);filter.addDataScheme("pocketworld-task-action");
        app.registerReceiver(receiver,filter,Context.RECEIVER_NOT_EXPORTED);

        taskId=app.start("只读观察当前手机页面，先询问我选择哪一项，再结合我的回答说明结果。",true,"main_screen");
        JSONObject admitted=app.tasks.get(taskId,false);runId=admitted.getString("runId");epoch=admitted.getInt("coordinatorEpoch");original=app.control(taskId);
        assertNotNull(original);assertTrue(original.matchesHostRun(taskId,runId,epoch));ModelConfig captured=app.config(taskId);
        physical=new Physical(accessibilityController.get().observationSessionId(),originalWorker);
        // Substitute hardware only. App.start has already admitted the real scope/run/lease;
        // START still owns every Service flag and creates the actual Runner itself.
        set(service,"heldPort",physical);
        Intent start=shadowOf(app).getNextStartedService();assertNotNull(start);assertEquals(PhoneRunService.START,start.getAction());assertEquals(taskId,start.getStringExtra("taskId"));
        service.onStartCommand(start,0,1);shadowOf(Looper.getMainLooper()).idleFor(750,TimeUnit.MILLISECONDS);
        await(()->ordinaryHold()!=null);
        worker.submit(()->{}).get(5,TimeUnit.SECONDS);

        OwnerPhoneQuestions.Held held=ordinaryHold();assertNotNull(held);held.requireOriginal();
        JSONObject waiting=app.tasks.get(taskId,false);String grant=grant(),lease=lease();
        assertEquals("awaiting_user",waiting.getString("status"));assertEquals(QUESTION,waiting.getJSONObject("evidence").getJSONObject("ownerQuestion").getString("question"));
        assertEquals(1,calls.get());assertEquals(1,waiting.getJSONObject("executionScope").getInt("modelCallsUsed"));
        assertSame(original,held.originalControl());assertSame(physical,held.physicalPort());assertSame(captured,held.capturedModel());
        assertSame(physical,get(service,"heldPort"));assertSame(physical,get(app,"activePort"));assertTrue(original.isPaused());
        assertEquals(1,count("SELECT count(*) FROM runs WHERE task_id=?",taskId));assertFalse(waiting.getJSONObject("ownerInputMailbox").getBoolean("open"));
        assertEquals(held.waitRevision(),checkpoint().getJSONArray("messages").length());
        int observationsBeforeAnswer=physical.observations.get();assertTrue(observationsBeforeAnswer>0);

        Notification posted=shadowOf(app.getSystemService(NotificationManager.class)).getNotification(100);assertNotNull(posted);
        Notification.Action answer=null;for(Notification.Action action:posted.actions)if(action.getRemoteInputs()!=null)answer=action;
        assertNotNull("the actual Service notification must offer its ordinary question",answer);
        assertTrue(answer.isAuthenticationRequired());assertFalse(answer.getAllowGeneratedReplies());
        Intent bound=shadowOf(answer.actionIntent).getSavedIntent();assertEquals(OwnerQuestionReplyReceiver.class.getName(),bound.getComponent().getClassName());
        assertEquals("pocketworld-task-action",bound.getData().getScheme());assertTrue(bound.getBooleanExtra("phoneOwnerQuestion",false));
        CountDownLatch parked=new CountDownLatch(1);Future<?> queueBarrier=worker.submit(()->{
            parked.countDown();try{if(!releaseWorker.await(15,TimeUnit.SECONDS))throw new AssertionError("original worker fixture was not released");}
            catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new AssertionError(interrupted);}
        });assertTrue(parked.await(5,TimeUnit.SECONDS));

        deliver(answer);JSONObject saved=app.tasks.get(taskId,false);JSONArray inputs=saved.getJSONArray("ownerInputs");assertEquals(1,inputs.length());
        JSONObject input=inputs.getJSONObject(0);String message=input.getString("messageId"),clock=input.getJSONObject("inputOriginClock").toString();
        assertEquals(ANSWER,input.getString("content"));assertEquals("saved",input.getJSONObject("ownerQuestionReply").getString("phase"));
        assertEquals(taskId,input.getString("acceptedTaskId"));assertEquals(runId,input.getString("acceptedRunId"));assertEquals(epoch,input.getInt("acceptedEpoch"));
        assertTrue(saved.getJSONObject("evidence").getJSONObject("ownerQuestion").getBoolean("replyConsumed"));
        assertEquals("original_worker_requested",saved.getJSONObject("ownerQuestionReplyState").getString("state"));
        assertTrue(original.isPaused());assertFalse(saved.getJSONObject("ownerInputMailbox").getBoolean("open"));assertFalse(saved.has("ownerQuestionContinuation"));
        assertEquals(grant,grant());assertEquals(lease,lease());assertEquals(1,calls.get());
        deliver(answer);assertEquals(1,app.tasks.get(taskId,false).getJSONArray("ownerInputs").length());
        assertEquals(clock,app.tasks.get(taskId,false).getJSONArray("ownerInputs").getJSONObject(0).getJSONObject("inputOriginClock").toString());
        assertEquals(1,count("SELECT count(*) FROM task_events WHERE task_id=? AND event_type='owner_phone_question_answer_saved'",taskId));

        releaseWorker.countDown();queueBarrier.get(5,TimeUnit.SECONDS);await(()->secondRequest.getCount()==0);
        JSONObject continuing=app.tasks.get(taskId,false);assertEquals(2,calls.get());assertFalse(original.isPaused());
        assertSame(original,app.control(taskId));assertSame(physical,get(app,"activePort"));assertNull(get(service,"heldPort"));
        assertEquals(runId,continuing.getString("runId"));assertEquals(epoch,continuing.getInt("coordinatorEpoch"));
        assertEquals("claimed",continuing.getJSONObject("ownerQuestionContinuation").getString("phase"));
        assertEquals("continuing",continuing.getJSONArray("ownerInputs").getJSONObject(0).getJSONObject("ownerQuestionReply").getString("phase"));
        assertEquals(2,continuing.getJSONObject("executionScope").getInt("modelCallsUsed"));assertEquals(grant,grant());assertEquals(lease,lease());
        assertEquals(1,count("SELECT count(*) FROM runs WHERE task_id=?",taskId));assertTrue(physical.observations.get()>observationsBeforeAnswer);
        assertTrue(requests.get(1).getJSONArray("messages").toString().contains(ANSWER));assertFalse(requests.get(0).toString().contains(ANSWER));
        assertEquals(1,checkpointAnswerCount(message));assertNull(ordinaryHold());

        releaseSecondResponse.countDown();await(()->app.activeId()==null&&get(service,"runningId")==null);
        worker.submit(()->{}).get(5,TimeUnit.SECONDS);assertNull(String.valueOf(modelFailure.get()),modelFailure.get());
        JSONObject finished=app.tasks.get(taskId,false);assertEquals("needs_confirmation",finished.getString("status"));
        assertEquals(runId,finished.getString("runId"));assertEquals(epoch,finished.getInt("coordinatorEpoch"));assertTrue(finished.getString("summary").contains(RESULT));
        JSONObject finalAnswer=finished.getJSONObject("evidence").getJSONObject("nativeFinalAnswer");
        assertEquals(taskId,finalAnswer.getString("taskId"));assertEquals(runId,finalAnswer.getString("runId"));assertEquals(epoch,finalAnswer.getInt("coordinatorEpoch"));assertTrue(finalAnswer.getBoolean("inputBoundaryEmpty"));
        assertEquals("needs_confirmation",scalar("SELECT status FROM runs WHERE run_id=?",runId));
        assertEquals(grant,grant());assertEquals(0,count("SELECT count(*) FROM leases WHERE resource_id='phone.local'"));
        assertEquals(1,count("SELECT count(*) FROM runs WHERE task_id=?",taskId));assertEquals(0,count("SELECT count(*) FROM operations WHERE task_id=?",taskId));
        assertEquals(0,count("SELECT count(*) FROM assistant_sources"));assertEquals(1,finished.getJSONArray("ownerInputs").length());assertEquals(1,checkpointAnswerCount(message));
        assertFalse(finished.getJSONObject("ownerInputMailbox").getBoolean("open"));assertFalse(original.isCancelled());
        assertNull(get(app,"activePort"));assertNull(get(service,"heldPort"));assertEquals(0,physical.effects.get());assertTrue(physical.observations.get()>=3);
        assertOriginalInferenceAudit();
        // A duplicate of the actual notice after normal release is receipt lookup only.
        deliver(answer);worker.submit(()->{}).get(5,TimeUnit.SECONDS);
        assertEquals(2,calls.get());assertEquals(1,app.tasks.get(taskId,false).getJSONArray("ownerInputs").length());
        assertEquals(1,count("SELECT count(*) FROM task_events WHERE task_id=? AND event_type='owner_phone_question_answer_saved'",taskId));
        assertEquals(1,count("SELECT count(*) FROM task_events WHERE task_id=? AND event_type='owner_phone_question_continuation_claimed'",taskId));
        assertEquals(0,count("SELECT count(*) FROM leases WHERE resource_id='phone.local'"));assertEquals(grant,grant());
    }

    private void startModel()throws Exception {
        model=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        model.createContext("/",exchange->{try{
            JSONObject body=new JSONObject(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));requests.add(body);int call=calls.incrementAndGet();
            assertTrue("only the two original physical calls are expected",call==1||call==2);
            assertTrue(body.getJSONArray("tools").toString().contains("run_ask_user"));assertFalse(body.toString().contains("data:image/"));
            JSONObject message;
            if(call==1)message=new JSONObject().put("role","assistant").put("content",JSONObject.NULL).put("tool_calls",new JSONArray().put(new JSONObject().put("id","original-phone-question").put("type","function").put("function",new JSONObject().put("name","run_ask_user").put("arguments",new JSONObject().put("question",QUESTION).toString()))));
            else{secondRequest.countDown();if(!releaseSecondResponse.await(15,TimeUnit.SECONDS))throw new AssertionError("second native response fixture was not released");message=new JSONObject().put("role","assistant").put("content",RESULT);}
            byte[] bytes=new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("message",message).put("finish_reason",call==1?"tool_calls":"stop"))).toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
        }catch(Throwable failure){modelFailure.compareAndSet(null,failure);try{exchange.sendResponseHeaders(500,0);}catch(Exception closed){}}
        finally{exchange.close();}});model.start();
    }

    private void configureOriginalModel()throws Exception {
        app.getSharedPreferences("private_settings",0).edit().putString("baseUrl","http://127.0.0.1:"+model.getAddress().getPort())
            .putString("provider","openai_chat").putString("model","ordinary-phone-continuation-fixture")
            .putBoolean("supportsImageInput",false).putBoolean("sendScreenshots",false).putBoolean("localCaptureAllowed",false).putBoolean("localOcrAllowed",false)
            .putInt("maxSteps",8).putInt("requestTimeoutSeconds",20).commit();
        assertNotNull("the original App must mount its model catalog",app.modelConnections);
        app.modelConnections.connections();app.modelConnections.configure(null,ModelBinding.Format.OPENAI,URI.create(app.settings.apiConfigForCatalog().baseUrl+"/models"));
        ModelConfig selected=app.settings.apiConfigForCatalog();ModelBinding binding=selected.capabilityBinding;
        // Real native owner facts for this synthetic endpoint, not a provider probe result
        // or an exemption from original model admission/currentness checks.
        app.modelConnections.editField(binding,selected.model,0,ModelFacts.Field.CHAT_OUTPUT_PARAMETER,ModelFacts.OwnerValue.known(ModelFacts.ChatOutputParameter.MAX_TOKENS),false);
        app.modelConnections.editField(binding,selected.model,1,ModelFacts.Field.TOOL_CALLS,ModelFacts.OwnerValue.known(true),false);
        assertTrue(app.settings.get().catalogEnforced);assertTrue(app.settings.matchesApiCatalogRoute(app.settings.get()));
        ModelGateway.validate(app.settings.get());assertEquals(0,calls.get());
    }

    private final class Physical implements PhonePort {
        final String session;final Thread originalWorker;final AtomicInteger observations=new AtomicInteger(),effects=new AtomicInteger();
        Physical(String session,Thread originalWorker){this.session=session;this.originalWorker=originalWorker;}
        public boolean isConnected(){return true;}
        public void clearReferences(){}
        public JSONObject observe(boolean image,RunControl control)throws Exception {
            assertSame(original,control);assertSame("initial and resumed reads must use the same Service worker",originalWorker,Thread.currentThread());
            assertFalse(image);control.check();observations.incrementAndGet();
            JSONObject tree=new JSONObject().put("ok",true).put("observationId",UUID.randomUUID().toString()).put("sessionId",session).put("displayId",0)
                .put("observedAtUptimeMs",SystemClock.uptimeMillis()).put("pauseRevision",control.pauseRevision()).put("screenWidth",320).put("screenHeight",160)
                .put("packageName","fixture.phone.question").put("truncated",false)
                .put("nodes",new JSONArray().put(new JSONObject().put("id","fixture-option").put("text","Controlled original phone page, two choices").put("enabled",true).put("clickable",false)));
            return ObservationReport.attach(tree,true,"controlled_fixture_tree",false,"not_requested");
        }
        public JSONObject execute(String name,JSONObject args,RunControl control){effects.incrementAndGet();throw new AssertionError("this ordinary-answer journey must not perform a phone effect");}
    }

    private OwnerPhoneQuestions.Held ordinaryHold()throws Exception {
        JSONObject task=app.tasks.get(taskId,false),identity=OwnerPhoneQuestions.reference(task);if(identity==null)return null;
        OwnerPhoneQuestions owner=(OwnerPhoneQuestions)get(app,"ownerPhoneQuestions");
        return owner.find(taskId,runId,epoch,identity.getString("requestId"),identity.getLong("waitRevision"));
    }
    private void deliver(Notification.Action action)throws Exception {
        BroadcastReceiver.PendingResult previous=shadowOf(receiver).getOriginalPendingResult();
        Intent delivered=new Intent(shadowOf(action.actionIntent).getSavedIntent());Bundle answer=new Bundle();answer.putCharSequence(OwnerQuestionReply.KEY,ANSWER);
        RemoteInput.addResultsToIntent(action.getRemoteInputs(),delivered,answer);action.actionIntent.send(app,0,delivered);
        await(()->shadowOf(receiver).getOriginalPendingResult()!=null&&shadowOf(receiver).getOriginalPendingResult()!=previous);
        assertTrue(shadowOf(receiver).wentAsync());shadowOf(shadowOf(receiver).getOriginalPendingResult()).getFuture().get(5,TimeUnit.SECONDS);
    }
    private void assertOriginalInferenceAudit()throws Exception {
        JSONObject usage=app.tasks.inferenceUsage(taskId);JSONArray receipts=usage.getJSONArray("receipts"),logical=usage.getJSONArray("logicalRequests");
        assertEquals(2,receipts.length());assertEquals(2,logical.length());Set<String> physicalCalls=new HashSet<>();
        for(int i=0;i<receipts.length();i++){JSONObject receipt=receipts.getJSONObject(i),context=receipt.getJSONObject("context");assertTrue(physicalCalls.add(receipt.getString("callId")));
            assertEquals("COMPLETED",receipt.getString("outcome"));assertEquals(taskId,context.getString("taskId"));assertEquals(runId,context.getString("runId"));assertEquals(epoch,context.getInt("epoch"));}
        for(int i=0;i<logical.length();i++){JSONObject request=logical.getJSONObject(i);assertEquals(taskId,request.getString("taskId"));assertEquals(runId,request.getString("runId"));assertEquals(epoch,request.getInt("epoch"));
            assertEquals("SUCCEEDED",request.getString("state"));assertTrue(physicalCalls.contains(request.getString("chosenCallId")));assertEquals(1,request.getJSONObject("control").getJSONArray("attempts").length());}
    }
    private JSONObject checkpoint()throws Exception{return new JSONObject(scalar("SELECT checkpoint FROM runs WHERE run_id=?",runId));}
    private int checkpointAnswerCount(String message)throws Exception {
        int count=0;JSONArray history=checkpoint().getJSONArray("messages");for(int i=0;i<history.length();i++){JSONObject row=history.getJSONObject(i);if(message.equals(row.optString("ownerMessageId"))){assertEquals("user",row.getString("role"));assertEquals(ANSWER,row.getString("content"));count++;}}return count;
    }
    private String grant()throws Exception{return scalar("SELECT grant_json FROM runs WHERE run_id=?",runId);}
    private String lease()throws Exception {
        try(var rows=db.rawQuery("SELECT run_id,epoch,expires_at FROM leases WHERE resource_id='phone.local'",null)){assertTrue(rows.moveToFirst());assertEquals(runId,rows.getString(0));assertEquals(epoch,rows.getInt(1));return rows.getString(0)+":"+rows.getInt(1)+":"+rows.getLong(2);}
    }
    private String scalar(String sql,String...args)throws Exception{try(var rows=db.rawQuery(sql,args)){assertTrue(rows.moveToFirst());return rows.getString(0);}}
    private long count(String sql,String...args)throws Exception{return Long.parseLong(scalar(sql,args));}
    @FunctionalInterface private interface Condition{boolean ready()throws Exception;}
    private void await(Condition condition)throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(12);
        do{shadowOf(Looper.getMainLooper()).idle();assertNull(String.valueOf(modelFailure.get()),modelFailure.get());if(condition.ready())return;Thread.sleep(5);}while(System.nanoTime()<until);
        fail("original phone journey did not reach its bounded native phase; HTTP calls="+calls.get());
    }
    private static Object get(Object target,String name)throws Exception{Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(target);}
    private static void set(Object target,String name,Object value)throws Exception{Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);field.set(target,value);}

    @After public void close()throws Exception {
        releaseWorker.countDown();releaseSecondResponse.countDown();
        try{
            if(app!=null&&app.activeId()!=null)app.stop(app.activeId());shadowOf(Looper.getMainLooper()).idle();
            if(worker!=null&&!worker.isShutdown())worker.submit(()->{}).get(5,TimeUnit.SECONDS);
        }finally{
            try{if(serviceController!=null)serviceController.destroy();if(worker!=null)worker.awaitTermination(5,TimeUnit.SECONDS);shadowOf(Looper.getMainLooper()).idle();}
            finally{
                if(receiver!=null)app.unregisterReceiver(receiver);if(accessibilityController!=null)accessibilityController.destroy();if(model!=null)model.stop(0);
                if(app!=null){app.onTerminate();if(app.features!=null)app.features.close();}if(db!=null&&db.isOpen())db.close();Security.removeProvider("PhoneQuestionJourneyKeys");
            }
        }
    }
}
