package dev.pocketengine.store;

import android.app.Application;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.runtime.models.ModelBinding;
import dev.pocketengine.runtime.models.ModelRequestPolicy;
import dev.pocketengine.runtime.models.ReasoningOptions;
import dev.pocketengine.runtime.usage.InferenceCapture;
import dev.pocketengine.runtime.usage.InferenceReceipt;
import dev.pocketengine.runtime.usage.InferenceTokenReservation;
import dev.pocketengine.runtime.usage.NativeInferenceWork;
import dev.pocketengine.runtime.usage.ProviderUsage;
import java.net.URI;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
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
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** Original TaskStore/SQL/RunJournal/Capture. No provider billing or Android-device claim. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35, manifest=Config.NONE, application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class NativeTokenBudgetJourneyTest {
    private static final String ROUTE = "connection:shared-original";
    private static String id() { return UUID.randomUUID().toString(); }
    private static SQLiteDatabase database(TaskStore tasks) throws Exception { var field=TaskStore.class.getDeclaredField("db"); field.setAccessible(true); return (SQLiteDatabase)field.get(tasks); }
    private static JSONArray messages() throws Exception { return new JSONArray().put(new JSONObject().put("role","user").put("content","原预算测试")); }
    private static JSONArray routes(String key) throws Exception { return new JSONArray().put(new JSONObject().put("routeKey",key).put("label","原连接")); }
    private static ModelConfig model(long credential, String name) {
        ModelConfig selected=new ModelConfig("https://fixture.invalid/v1","fixture-secret",name,false,12,10,"openai_chat",256);
        return selected.withNativeCatalog(new ModelBinding("shared-original","fixture",ModelBinding.Protocol.OPENAI_CHAT,ModelBinding.Format.OPENAI,
                URI.create("https://fixture.invalid/v1/models"),"saved-credential",credential,"original-installation",1,false),ReasoningOptions.DEFAULT,0);
    }
    private static long reserve(ModelConfig selected) throws Exception { return InferenceTokenReservation.prepare(selected,messages(),null).getLong("reservedTokens"); }
    private static void actual(InferenceCapture capture, long total) throws Exception {
        capture.usage.observe(new JSONObject().put("usage",new JSONObject().put("prompt_tokens",total).put("completion_tokens",0).put("total_tokens",total)),ProviderUsage.Stage.FINAL);
        capture.finish(InferenceReceipt.Outcome.COMPLETED);
    }
    private record Owner(String id, String run, int epoch, RunControl control) { }
    private static final class Fixture implements AutoCloseable {
        final Application android=RuntimeEnvironment.getApplication(); final TaskStore tasks;
        Fixture() throws Exception { android.deleteDatabase("pocket-world.db"); tasks=new TaskStore(android); }
        Owner owner() throws Exception { return start(tasks.createCapabilityTask("原主人任务",id()).getString("id")); }
        Owner start(String id) throws Exception {
            tasks.initializeExecutionScope(id,12); JSONObject task=tasks.startAttempt(id,"capabilities",12); RunControl control=new RunControl();
            control.bindHostRun(id,task.getString("runId"),task.getInt("coordinatorEpoch")); return new Owner(id,task.getString("runId"),task.getInt("coordinatorEpoch"),control);
        }
        InferenceCapture capture(Owner owner, InferenceReceipt.Purpose purpose, ModelConfig selected, boolean prepared) throws Exception {
            InferenceCapture call=new InferenceCapture(selected,owner.control,tasks.journal(owner.id,owner.run,owner.epoch,owner.control).inferenceTarget(purpose,purpose==InferenceReceipt.Purpose.MEMBER?"original-member":""));
            if(prepared)call.tokenReservation(messages(),null); return call;
        }
        JSONObject task(Owner owner) throws Exception { return tasks.ownerTokenBudgetState(owner.id,new JSONArray()).getJSONObject("task").getJSONObject("budget"); }
        JSONObject daily(String key) throws Exception { return tasks.ownerTokenBudgetState("",routes(key)).getJSONArray("routes").getJSONObject(0).getJSONObject("budget"); }
        public void close() throws Exception { SQLiteDatabase db=database(tasks); if(db.isOpen())db.close(); }
    }

    @Test public void mainSummaryAndMemberShareOriginalCumulativeBudgetAndUnknownIsNeverRefunded() throws Exception { try(Fixture f=new Fixture()) {
        ModelConfig selected=model(1,"first"); long reserve=reserve(selected); Owner owner=f.owner();
        f.tasks.setTaskTokenBudgetForOwner(owner.id,0,true,reserve+100);
        InferenceCapture main=f.capture(owner,InferenceReceipt.Purpose.MAIN,selected,true); main.dispatch(); actual(main,50);
        InferenceCapture summary=f.capture(owner,InferenceReceipt.Purpose.SUMMARY,selected,true); summary.dispatch(); summary.finish(InferenceReceipt.Outcome.FAILED);
        JSONObject before=f.task(owner); assertEquals(50,before.getLong("knownTokens")); assertEquals(reserve,before.getLong("heldTokens")); assertEquals(1,before.getLong("unknownCalls"));
        InferenceCapture member=f.capture(owner,InferenceReceipt.Purpose.MEMBER,selected,true);
        assertThrows(ModelRequestPolicy.Rejected.class,member::dispatch); assertFalse(member.wasDispatched());
        assertEquals(2,f.tasks.inferenceUsage(owner.id).getInt("attempts")); assertEquals(2,f.tasks.get(owner.id,false).getJSONObject("executionScope").getInt("modelCallsUsed"));
        f.tasks.setTaskTokenBudgetForOwner(owner.id,1,false,0); assertEquals(reserve,f.task(owner).getLong("heldTokens"));
    }}

    @Test public void concurrentTasksCannotOverspendTheSameSavedConnectionAndRejectedCallRollsBack() throws Exception { try(Fixture f=new Fixture()) {
        ModelConfig selected=model(1,"model-a"); long reserve=reserve(selected); f.tasks.setDailyTokenBudgetForOwner(ROUTE,0,true,reserve+50,"UTC");
        Owner a=f.owner(),b=f.owner(); InferenceCapture first=f.capture(a,InferenceReceipt.Purpose.MAIN,selected,true),second=f.capture(b,InferenceReceipt.Purpose.SUMMARY,selected,true);
        var workers=Executors.newFixedThreadPool(2); CountDownLatch ready=new CountDownLatch(2),go=new CountDownLatch(1);
        try {
            java.util.concurrent.Callable<Boolean> one=()->{ready.countDown();assertTrue(go.await(5,TimeUnit.SECONDS));try{first.dispatch();return true;}catch(ModelRequestPolicy.Rejected rejected){return false;}};
            java.util.concurrent.Callable<Boolean> two=()->{ready.countDown();assertTrue(go.await(5,TimeUnit.SECONDS));try{second.dispatch();return true;}catch(ModelRequestPolicy.Rejected rejected){return false;}};
            var left=workers.submit(one);var right=workers.submit(two);assertTrue(ready.await(5,TimeUnit.SECONDS));go.countDown();boolean admittedA=left.get(5,TimeUnit.SECONDS),admittedB=right.get(5,TimeUnit.SECONDS);
            assertTrue(admittedA!=admittedB);Owner rejected=admittedA?b:a;InferenceCapture winner=admittedA?first:second;
            assertEquals(0,f.tasks.inferenceUsage(rejected.id).getInt("attempts")); assertEquals(0,f.tasks.get(rejected.id,false).getJSONObject("executionScope").getInt("modelCallsUsed"));
            assertEquals(reserve,f.daily(ROUTE).getLong("heldTokens")); actual(winner,10);
            InferenceCapture renewed=f.capture(rejected,InferenceReceipt.Purpose.MAIN,model(2,"model-after-renewal"),true);renewed.dispatch();
            assertEquals(reserve,f.daily(ROUTE).getLong("heldTokens"));assertEquals(10,f.daily(ROUTE).getLong("knownTokens"));
            renewed.finish(InferenceReceipt.Outcome.CANCELLED); assertEquals(reserve,f.daily(ROUTE).getLong("heldTokens"));
        } finally {go.countDown();workers.shutdownNow();assertTrue(workers.awaitTermination(5,TimeUnit.SECONDS));}
    }}

    @Test public void actualOverrunIsRecordedInFullAndBlocksLaterCallsWithoutClaimingAHardProviderCap() throws Exception { try(Fixture f=new Fixture()) {
        Owner owner=f.owner();ModelConfig selected=model(1,"model");long reserve=reserve(selected);f.tasks.setTaskTokenBudgetForOwner(owner.id,0,true,reserve);
        InferenceCapture call=f.capture(owner,InferenceReceipt.Purpose.MAIN,selected,true);call.dispatch();actual(call,reserve+1);
        JSONObject pool=f.task(owner);assertEquals(reserve+1,pool.getLong("knownTokens"));assertEquals(0,pool.getLong("heldTokens"));assertTrue(pool.getBoolean("overLimit"));
        JSONObject receipt=f.tasks.inferenceUsage(owner.id).getJSONArray("receipts").getJSONObject(0);assertTrue(receipt.getJSONObject("tokenBudget").getBoolean("exceededReservation"));assertFalse(receipt.getJSONObject("tokenBudget").getBoolean("providerHardCap"));
        assertThrows(ModelRequestPolicy.Rejected.class,()->f.capture(owner,InferenceReceipt.Purpose.SUMMARY,selected,true).dispatch());
        call.finish(InferenceReceipt.Outcome.COMPLETED);assertEquals(reserve+1,f.task(owner).getLong("knownTokens"));
    }}

    @Test public void processRestartAndNextDayKeepUnknownAndPendingHoldsAndDeferTimezoneChange() throws Exception { try(Fixture f=new Fixture()) {
        Owner owner=f.owner();ModelConfig selected=model(1,"model");long reserve=reserve(selected);f.tasks.setDailyTokenBudgetForOwner(ROUTE,0,true,reserve*3,"UTC");
        InferenceCapture unknown=f.capture(owner,InferenceReceipt.Purpose.SUMMARY,selected,true);unknown.dispatch();unknown.finish(InferenceReceipt.Outcome.CANCELLED);
        InferenceCapture pending=f.capture(owner,InferenceReceipt.Purpose.MAIN,selected,true);pending.dispatch();
        JSONObject old=f.daily(ROUTE);f.tasks.setDailyTokenBudgetForOwner(ROUTE,1,true,reserve*3,"Asia/Tokyo");JSONObject changed=f.daily(ROUTE);
        assertEquals(old.getLong("windowStart"),changed.getLong("windowStart"));assertEquals("UTC",changed.getString("zoneId"));assertEquals("Asia/Tokyo",changed.getString("nextZoneId"));
        SQLiteDatabase db=database(f.tasks);db.beginTransaction();try{TaskTokenBudgets.setDaily(db,ROUTE,2,true,reserve*3,"Asia/Tokyo",old.getLong("windowEnd")+1);db.setTransactionSuccessful();}finally{db.endTransaction();}
        db.close();TaskStore reopened=new TaskStore(f.android);
        try {JSONObject day=reopened.ownerTokenBudgetState("",routes(ROUTE)).getJSONArray("routes").getJSONObject(0).getJSONObject("budget");
            assertEquals(reserve*2,day.getLong("heldTokens"));assertEquals(1,day.getLong("pendingCalls"));assertEquals(1,day.getLong("unknownCalls"));assertEquals(0,day.getLong("knownTokens"));
            assertEquals("Asia/Tokyo",day.getString("zoneId"));assertTrue(day.getLong("windowStart")>=old.getLong("windowEnd"));
            assertEquals(2,reopened.inferenceUsage(owner.id).getInt("attempts"));
        } finally {database(reopened).close();}
    }}

    @Test public void defaultsAreFrozenAtOriginalTaskCreationAndNeverRetroactivelyChangeQueuedTasks() throws Exception { try(Fixture f=new Fixture()) {
        f.tasks.setDefaultTokenBudgetForOwner(0,true,10000);String queued=f.tasks.createCapabilityTask("原排队任务",id()).getString("id");
        f.tasks.setDefaultTokenBudgetForOwner(1,true,20000);Owner old=f.start(queued),next=f.owner();
        assertEquals(10000,f.task(old).getLong("limit"));assertEquals(20000,f.task(next).getLong("limit"));
        f.tasks.setDefaultTokenBudgetForOwner(2,false,0);assertEquals(10000,f.task(old).getLong("limit"));assertTrue(f.task(old).getBoolean("enabled"));assertFalse(f.task(f.owner()).getBoolean("enabled"));
    }}

    @Test public void zeroConfigurationRetainsUnknownUseAndEnablingCannotTreatItAsFree() throws Exception { try(Fixture f=new Fixture()) {
        Owner owner=f.owner();InferenceCapture call=f.capture(owner,InferenceReceipt.Purpose.MAIN,model(1,"model"),false);call.dispatch();call.finish(InferenceReceipt.Outcome.FAILED);
        assertFalse(f.task(owner).getBoolean("enabled"));assertEquals(1,f.task(owner).getLong("unboundedCalls"));
        assertThrows(SecurityException.class,()->f.tasks.setTaskTokenBudgetForOwner(owner.id,0,true,100000));
        assertThrows(SecurityException.class,()->f.tasks.setDailyTokenBudgetForOwner(ROUTE,0,true,100000,"UTC"));
        assertEquals(1,f.tasks.inferenceUsage(owner.id).getInt("attempts"));assertEquals(1,f.task(owner).getLong("unknownCalls"));
        assertThrows(SecurityException.class,()->f.tasks.setTaskTokenBudgetForOwner(owner.id,99,false,0));
    }}

    @Test public void registeredAccountDailyBudgetIncludesNativeAuxiliaryCallsAndSurvivesVersionRenewal() throws Exception { try(Fixture f=new Fixture()) {
        ModelConfig.AccountModelRef account=ModelConfig.AccountModelRef.fromJson(new JSONObject().put("provider","chatgpt_plan").put("accountId","original-account").put("accountVersion",1).put("modelId","account-model"));
        ModelConfig selected=model(1,"model").withAccountModel(account);long reserve=reserve(selected);String key="account:original-account";
        f.tasks.setDailyTokenBudgetForOwner(key,0,true,reserve,"UTC");
        RunControl one=new RunControl(),two=new RunControl();try(NativeInferenceWork first=NativeInferenceWork.open(f.tasks,TaskStore.NativeInferenceKind.CONNECTION_TEST,id(),one);NativeInferenceWork channel=NativeInferenceWork.open(f.tasks,TaskStore.NativeInferenceKind.CHANNEL_CHAT,id(),two)) {
            InferenceCapture unknown=new InferenceCapture(selected,one,first.target());unknown.tokenReservation(messages(),null);unknown.dispatch();unknown.finish(InferenceReceipt.Outcome.FAILED);
            InferenceCapture renewed=new InferenceCapture(selected.withAccountModel(account.withVersion(2)),two,channel.target());renewed.tokenReservation(messages(),null);assertThrows(ModelRequestPolicy.Rejected.class,renewed::dispatch);
            assertEquals(0,f.tasks.inferenceUsage(channel.reference().getString("taskId")).getInt("attempts"));assertEquals(reserve,f.daily(key).getLong("heldTokens"));
        }
        assertThrows(ModelRequestPolicy.Rejected.class,()->f.tasks.requireTokenBudgetAttribution(InferenceReceipt.Context.unattributed(),InferenceTokenReservation.captureIdentity(selected)));
    }}

    @Test public void publicModelAndProviderRedactionNeverBypassTheOriginalConnectionDailyBudget() throws Exception { try(Fixture f=new Fixture()) {
        ModelConfig selected=model(1,"model-fixture-secret");long reserve=reserve(selected);f.tasks.setDailyTokenBudgetForOwner(ROUTE,0,true,reserve,"UTC");
        Owner owner=f.owner();InferenceCapture first=f.capture(owner,InferenceReceipt.Purpose.MAIN,selected,true);first.dispatch();first.finish(InferenceReceipt.Outcome.FAILED);
        JSONObject receipt=f.tasks.inferenceUsage(owner.id).getJSONArray("receipts").getJSONObject(0);
        assertTrue(receipt.getJSONObject("route").getBoolean("identityRedacted"));assertTrue(receipt.getJSONObject("route").isNull("requestedModel"));assertFalse(receipt.toString().contains("fixture-secret"));
        assertEquals(reserve,f.daily(ROUTE).getLong("heldTokens"));assertEquals(1,f.daily(ROUTE).getLong("unknownCalls"));
        ModelConfig differentLabel=model(2,"model").withApiConnection(new ModelBinding("shared-original","provider-fixture-secret",ModelBinding.Protocol.OPENAI_CHAT,ModelBinding.Format.OPENAI,
                URI.create("https://fixture.invalid/v1/models"),"saved-credential",2,"original-installation",1,false));
        InferenceCapture blocked=f.capture(owner,InferenceReceipt.Purpose.SUMMARY,differentLabel,true);
        assertThrows(ModelRequestPolicy.Rejected.class,blocked::dispatch);assertFalse(blocked.wasDispatched());assertEquals(1,f.tasks.inferenceUsage(owner.id).getInt("attempts"));
        assertEquals(1,f.tasks.get(owner.id,false).getJSONObject("executionScope").getInt("modelCallsUsed"));
    }}

    @Test public void redactedConnectionIdUsesPrivateIdentityAndOpaquePoolWithoutLeakingTheKey() throws Exception { try(Fixture f=new Fixture()) {
        ModelConfig selected=model(1,"model").withApiConnection(new ModelBinding("saved-fixture-secret","provider",ModelBinding.Protocol.OPENAI_CHAT,ModelBinding.Format.OPENAI,
                URI.create("https://fixture.invalid/v1/models"),"saved-credential",1,"original-installation",1,false));
        String key="connection:saved-fixture-secret";long reserve=reserve(selected);f.tasks.setDailyTokenBudgetForOwner(key,0,true,reserve,"UTC");
        Owner owner=f.owner();InferenceCapture first=f.capture(owner,InferenceReceipt.Purpose.MAIN,selected,true);first.dispatch();actual(first,50);
        JSONObject receipt=f.tasks.inferenceUsage(owner.id).getJSONArray("receipts").getJSONObject(0),annotation=receipt.getJSONObject("tokenBudget");
        assertTrue(receipt.getJSONObject("route").isNull("connectionId"));assertTrue(annotation.isNull("routeKey"));assertFalse(annotation.isNull("dailyPoolId"));assertFalse(receipt.toString().contains("fixture-secret"));
        assertEquals(50,f.daily(key).getLong("knownTokens"));assertEquals(0,f.daily(key).getLong("heldTokens"));
        assertThrows(ModelRequestPolicy.Rejected.class,()->f.capture(owner,InferenceReceipt.Purpose.SUMMARY,selected,true).dispatch());assertEquals(1,f.tasks.inferenceUsage(owner.id).getInt("attempts"));
        assertEquals(1,database(f.tasks).delete("meta","key=?",new String[]{"owner_token_budget_daily_v1:"+key}));
        assertThrows(SecurityException.class,()->f.tasks.setDailyTokenBudgetForOwner(key,0,true,reserve,"UTC"));
    }}

    @Test public void missingIdentityAndUnattributedRedactedCallsFailClosedWithAnEnabledDailyPolicy() throws Exception { try(Fixture f=new Fixture()) {
        ModelConfig redacted=model(1,"model-fixture-secret");f.tasks.setDailyTokenBudgetForOwner(ROUTE,0,true,reserve(redacted),"UTC");
        ModelConfig unidentified=new ModelConfig("https://fixture.invalid/v1","fixture-secret","model",false,12,10,"openai_chat",256);
        Owner owner=f.owner();InferenceCapture unknown=f.capture(owner,InferenceReceipt.Purpose.MAIN,unidentified,true);
        assertThrows(ModelRequestPolicy.Rejected.class,unknown::dispatch);assertFalse(unknown.wasDispatched());assertEquals(0,f.tasks.inferenceUsage(owner.id).getInt("attempts"));
        InferenceCapture.installBudgetAttribution(f.tasks::requireTokenBudgetAttribution);
        try {
            InferenceCapture noLedger=new InferenceCapture(redacted,new RunControl(),InferenceCapture.unattributed());noLedger.tokenReservation(messages(),null);
            assertThrows(ModelRequestPolicy.Rejected.class,noLedger::dispatch);assertFalse(noLedger.wasDispatched());
            InferenceCapture noIdentity=new InferenceCapture(unidentified,new RunControl(),InferenceCapture.unattributed());noIdentity.tokenReservation(messages(),null);
            assertThrows(ModelRequestPolicy.Rejected.class,noIdentity::dispatch);assertFalse(noIdentity.wasDispatched());
        } finally {InferenceCapture.installBudgetAttribution((context,identity)->{});}
        assertEquals(0,f.daily(ROUTE).getLong("calls"));assertEquals(0,f.tasks.get(owner.id,false).getJSONObject("executionScope").getInt("modelCallsUsed"));
    }}

    @Test public void missingOriginalDailyProjectionNeverRebuildsAnEmptyBalance() throws Exception { try(Fixture f=new Fixture()) {
        Owner owner=f.owner();InferenceCapture call=f.capture(owner,InferenceReceipt.Purpose.MAIN,model(1,"model"),true);call.dispatch();
        assertEquals(1,database(f.tasks).delete("meta","key=?",new String[]{"owner_token_budget_daily_v1:"+ROUTE}));
        assertThrows(SecurityException.class,()->f.daily(ROUTE));assertEquals(1,f.tasks.inferenceUsage(owner.id).getInt("attempts"));
    }}

    @Test public void windowClosedWhileWaitingForStoreLockCannotSaveAndPreCommitClosureRollsBack() throws Exception { try(Fixture f=new Fixture()) {
        var worker=Executors.newSingleThreadExecutor();AtomicBoolean current=new AtomicBoolean(true);CountDownLatch entered=new CountDownLatch(1);
        java.util.concurrent.Future<JSONObject> saved;
        try {
            synchronized(f.tasks) {saved=worker.submit(()->{entered.countDown();return f.tasks.setDefaultTokenBudgetForOwner(0,true,10000,current::get);});assertTrue(entered.await(5,TimeUnit.SECONDS));current.set(false);}
            var refused=assertThrows(java.util.concurrent.ExecutionException.class,()->saved.get(5,TimeUnit.SECONDS));assertTrue(refused.getCause() instanceof SecurityException);
            assertEquals(0,f.tasks.ownerTokenBudgetState("",new JSONArray()).getJSONObject("defaults").getLong("revision"));
            Owner owner=f.owner();AtomicInteger checks=new AtomicInteger();assertThrows(SecurityException.class,()->f.tasks.setTaskTokenBudgetForOwner(owner.id,0,true,20000,()->checks.incrementAndGet()==1));
            assertEquals(0,f.task(owner).getLong("revision"));assertFalse(f.task(owner).getBoolean("enabled"));assertEquals(0,f.tasks.inferenceUsage(owner.id).getInt("attempts"));
            checks.set(0);assertThrows(SecurityException.class,()->f.tasks.setDailyTokenBudgetForOwner(ROUTE,0,true,20000,"UTC",()->checks.incrementAndGet()==1));assertEquals(0,f.daily(ROUTE).getLong("revision"));
        } finally {worker.shutdownNow();assertTrue(worker.awaitTermination(5,TimeUnit.SECONDS));}
    }}
}
