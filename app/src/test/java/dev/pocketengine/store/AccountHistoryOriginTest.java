package dev.pocketengine.store;

import android.app.Application;
import android.content.ContentValues;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.runtime.usage.InferenceCapture;
import dev.pocketengine.runtime.usage.InferenceReceipt;
import dev.pocketengine.runtime.usage.LogicalInferenceAuthority;
import dev.pocketengine.runtime.ModelGateway;
import dev.pocketengine.features.proxy.accounts.GatewayCall;
import dev.pocketengine.features.proxy.providers.ChatGptProvider;
import dev.pocketengine.features.proxy.providers.ResponsesFixtures;
import dev.pocketengine.features.proxy.providers.ResponsesRequest;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** Native SQLite provenance fixtures, not a live-account or device acceptance test. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class AccountHistoryOriginTest {
    @Test public void nativeContinuationSendsProvenHistoryWithoutAuthorizingOldCredentials()throws Exception {
        try(Fixture f=new Fixture(true)) {
            f.receipt("COMPLETED");java.util.concurrent.atomic.AtomicInteger posts=new java.util.concurrent.atomic.AtomicInteger();
            ModelGateway.installAccountInferencePort(new ModelGateway.AccountInferencePort(){
                public boolean accountIdentityCurrent(ModelConfig.AccountModelRef ref){return f.current.accountModel.matches(ref);}
                public void stream(ModelConfig.AccountModelRef ref,ResponsesRequest request,GatewayCall operation,ChatGptProvider.EventSink sink)throws dev.pocketengine.features.proxy.accounts.GatewayFailure {
                    assertEquals(9,ref.accountVersion);assertEquals(f.account,ref.accountId);posts.incrementAndGet();
                    try{
                        Field bytes=ResponsesRequest.class.getDeclaredField("bytes");bytes.setAccessible(true);JSONArray input=new JSONObject(new String((byte[])bytes.get(request),StandardCharsets.UTF_8)).getJSONArray("input");
                        assertEquals(2,input.length());assertEquals("opaque-native-fixture",input.getJSONObject(0).getString("id"));assertEquals("original completed reply",input.getJSONObject(0).getJSONArray("content").getJSONObject(0).getString("text"));assertEquals("Continue the reviewed task",input.getJSONObject(1).getString("content"));
                        sink.event(new JSONObject().put("type","completed").put("output",new JSONArray().put(ResponsesFixtures.message("new","verified",false,true))));
                    }catch(Exception failure){throw new dev.pocketengine.features.proxy.accounts.GatewayFailure("FIXTURE_FAILED");}
                }
            });
            JSONObject answer=ModelGateway.complete(f.current,new JSONArray().put(new JSONObject().put("role","assistant").put("content","original completed reply").put("provider_data",f.data)).put(new JSONObject().put("role","user").put("content","Continue the reviewed task")),null,f.original,f.target);
            assertEquals("verified",answer.getString("content"));assertEquals(1,posts.get());assertTrue(answer.getJSONObject("provider_data").getString("originCallId").matches("[a-f0-9-]{36}"));
            try(android.database.Cursor rows=f.sql.rawQuery("SELECT count(*) FROM operations WHERE task_id=?",new String[]{f.task})){assertTrue(rows.moveToFirst());assertEquals(0,rows.getInt(0));}
        }finally{ModelGateway.installAccountInferencePort(null);}
    }
    @Test public void completedOriginalReceiptProvesHistoryAfterAccountRenewal()throws Exception {
        try(Fixture f=new Fixture()) {f.receipt("COMPLETED");assertTrue(f.accept(f.call,f.current,f.data));}
    }
    @Test public void preOriginIdHistoryRequiresItsExactOriginalCompletedReceipt()throws Exception {
        try(Fixture f=new Fixture()) {assertFalse(f.accept("",f.current,f.data));f.receipt("COMPLETED");assertTrue(f.accept("",f.current,f.data));}
    }
    @Test public void absentFailedTamperedForeignAndCancelledOriginsDeny()throws Exception {
        try(Fixture f=new Fixture()) {
            assertFalse(f.accept(f.call,f.current,f.data));f.receipt("FAILED");assertFalse(f.accept(f.call,f.current,f.data));
            f.receipt("COMPLETED");assertTrue(f.accept(f.call,f.current,f.data));
            JSONObject changed=new JSONObject(f.data.toString());changed.getJSONArray("items").getJSONObject(0).put("id","tampered");assertFalse(f.accept(f.call,f.current,changed));
            assertFalse(f.accept(f.call,f.model(UUID.randomUUID().toString(),9,"fixture-model"),f.data));
            assertFalse(f.accept(f.call,f.model(f.account,9,"another-model"),f.data));
            changed=new JSONObject(f.data.toString());changed.getJSONObject("nativeRef").put("accountVersion",7);assertFalse(f.accept(f.call,f.current,changed));
            f.body.getJSONObject("route").put("accountVersion",7);f.save();assertFalse(f.accept(f.call,f.current,f.data));
            f.original.cancel();try{f.accept(f.call,f.current,f.data);fail("Stopped original run must not authorize history");}catch(InterruptedException expected){}
        }
    }
    @Test public void receiptContextAndAmbiguousLegacyIdentityCannotBeBorrowed()throws Exception {
        try(Fixture f=new Fixture()) {
            f.receipt("COMPLETED");f.body.getJSONObject("context").put("taskId",UUID.randomUUID().toString());f.save();assertFalse(f.accept(f.call,f.current,f.data));
            f.body.getJSONObject("context").put("taskId",f.task).put("purpose","SUMMARY");f.save();assertFalse(f.accept(f.call,f.current,f.data));
            f.body.getJSONObject("context").put("purpose","MAIN").put("memberId","foreign-member");f.save();assertFalse(f.accept(f.call,f.current,f.data));
            f.body.getJSONObject("context").put("memberId","");f.save();assertTrue(f.accept("",f.current,f.data));
            ContentValues duplicate=new ContentValues();String json=new JSONObject(f.body.toString()).put("callId",UUID.randomUUID().toString()).toString();duplicate.put("task_id",f.task);duplicate.put("event_order",900002);duplicate.put("event_type","inference_receipt");duplicate.put("call_id",UUID.randomUUID().toString());duplicate.put("event_json",json);duplicate.put("body_bytes",json.getBytes(StandardCharsets.UTF_8).length);f.sql.insertOrThrow("task_events",null,duplicate);assertFalse(f.accept("",f.current,f.data));
        }
    }
    private static final class Fixture implements AutoCloseable {
        final AtomicBoolean ready=new AtomicBoolean();final RunControl original=new RunControl();final TaskStore store;
        final String task,run,call=UUID.randomUUID().toString(),account=UUID.randomUUID().toString();final int epoch;
        final SQLiteDatabase sql;final LogicalInferenceAuthority authority;final InferenceCapture.Target target;final ModelConfig current;final JSONObject data;final String priorRun;final int priorEpoch;JSONObject body;
        Fixture()throws Exception {this(false);}
        Fixture(boolean continued)throws Exception {
            Application app=RuntimeEnvironment.getApplication();app.deleteDatabase(TaskStore.DATABASE_NAME);store=new TaskStore(app,ready::get,(stage,schema)->{});store.verifyBootstrapReadback();ready.set(true);store.conversationRepository().initializeEmpty();
            task=store.createCapabilityTask("Native account history fixture","").getString("id");JSONObject attempt=store.startAttempt(task,"capabilities",8);priorRun=attempt.getString("runId");priorEpoch=attempt.getInt("coordinatorEpoch");if(continued){store.endAttempt(task,priorRun,"needs_confirmation","Native review",new JSONObject());attempt=store.startAttempt(task,"capabilities",8);}run=attempt.getString("runId");epoch=attempt.getInt("coordinatorEpoch");original.bindHostRun(task,run,epoch);store.status(task,"running","Native fixture");
            Field field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);sql=(SQLiteDatabase)field.get(store);target=store.journal(task,run,epoch,original).inferenceTarget(InferenceReceipt.Purpose.MAIN,"");authority=target.logical;
            current=model(account,9,"fixture-model");data=new JSONObject().put("provider",ModelConfig.ACCOUNT_PROVIDER).put("nativeRef",model(account,8,"fixture-model").accountModel.toJson()).put("items",new JSONArray().put(ResponsesFixtures.message("opaque-native-fixture","original completed reply",false,true)));
        }
        ModelConfig model(String id,long version,String name)throws Exception {return new ModelConfig("","","",false,3,10).withAccountModel(ModelConfig.AccountModelRef.fromJson(new JSONObject().put("provider",ModelConfig.ACCOUNT_PROVIDER).put("accountId",id).put("accountVersion",version).put("modelId",name)));}
        void receipt(String outcome)throws Exception {body=new JSONObject().put("type","inference_receipt").put("callId",call).put("outcome",outcome).put("context",new InferenceReceipt.Context(task,priorRun,priorEpoch,InferenceReceipt.Purpose.MAIN,"").toJson()).put("route",new JSONObject().put("historyDigest",InferenceCapture.historyDigest(data)).put("protocol","openai_responses").put("requestedModel","fixture-model").put("accountId",account).put("accountVersion",8));save();}
        void save()throws Exception {String json=body.toString();ContentValues row=new ContentValues();row.put("task_id",task);row.put("event_order",900001);row.put("event_type","inference_receipt");row.put("call_id",call);row.put("event_json",json);row.put("body_bytes",json.getBytes(StandardCharsets.UTF_8).length);sql.insertWithOnConflict("task_events",null,row,SQLiteDatabase.CONFLICT_REPLACE);}
        boolean accept(String id,ModelConfig model,JSONObject history)throws Exception {try{return authority.matchesHistoryOrigin(id,model,history);}catch(SecurityException denied){return false;}catch(IllegalStateException ambiguous){return false;}}
        public void close()throws Exception {original.cancel();ready.set(false);if(!store.closedForMaintenance())store.closeForMaintenance();}
    }
}
