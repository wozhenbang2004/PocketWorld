package dev.pocketengine.features.proxy.accounts;

import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.proxy.providers.ChatGptProvider;
import dev.pocketengine.features.proxy.providers.ResponsesFixtures;
import dev.pocketengine.features.proxy.providers.ResponsesRequest;
import dev.pocketengine.runtime.ModelGateway;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import static org.junit.Assert.*;

/** Native renewal identity and continuation only; synthetic localhost accounts, no real authorization. */
public final class AccountInferenceRenewalTest {
    @After public void unbind(){ModelGateway.installAccountInferencePort(null);}
    private ModelConfig config(AccountGatewayTest.Rig rig,String id)throws Exception {
        return new ModelConfig("","","",false,3,10).withAccountModel(ModelConfig.AccountModelRef.fromJson(new JSONObject().put("provider","chatgpt_plan").put("accountId",id).put("accountVersion",rig.book.row(id).getLong("version")).put("modelId","fixture-model")));
    }
    private void bind(AccountGatewayTest.Rig rig,AtomicLong persisted){
        ModelGateway.installAccountInferencePort(new ModelGateway.AccountInferencePort(){
            public boolean accountIdentityCurrent(ModelConfig.AccountModelRef ref){return rig.gateway.inferenceIdentityCurrent(ref.accountId,ref.accountVersion);}
            public void stream(ModelConfig.AccountModelRef ref,ResponsesRequest request,GatewayCall op,ChatGptProvider.EventSink sink)throws GatewayFailure{rig.gateway.streamResponsesReviewed(ref.accountId,ref.accountVersion,request,op,sink);}
            public void streamWithVersion(ModelConfig.AccountModelRef ref,ResponsesRequest request,GatewayCall op,ModelGateway.AccountVersionSink versions,ChatGptProvider.EventSink sink)throws GatewayFailure {
                rig.gateway.streamResponsesReviewed(ref.accountId,ref.accountVersion,request,op,actual->{persisted.compareAndSet(ref.accountVersion,actual);versions.authorized(actual);},sink);
            }
        });
    }
    private JSONArray messages()throws Exception{return new JSONArray().put(new JSONObject().put("role","user").put("content","Read state"));}
    private JSONArray tools()throws Exception{return new JSONArray().put(new JSONObject().put("type","function").put("function",new JSONObject().put("name","inspect").put("parameters",new JSONObject().put("type","object"))));}
    private String textStream()throws Exception {JSONObject item=ResponsesFixtures.message("msg","verified",false,true);return new ResponsesFixtures().item(0,ResponsesFixtures.message("msg","",false,false),false).text(0,"msg","verified",false).item(0,item,true).complete(new JSONArray().put(item)).toString();}
    private String callStream()throws Exception {
        JSONObject r=new JSONObject().put("type","reasoning").put("id","reasoning").put("summary",new JSONArray()).put("encrypted_content","opaque-renewal-fixture");
        JSONObject c=ResponsesFixtures.function("item","call","inspect","{}",true).put("namespace","pocketworld");
        return new ResponsesFixtures().item(0,new JSONObject(r.toString()).put("encrypted_content",JSONObject.NULL),false).item(0,r,true)
            .item(1,ResponsesFixtures.function("item","call","inspect","",false).put("namespace","pocketworld"),false).argument(1,"item","{}",false).argument(1,"item","{}",true).item(1,c,true).complete(new JSONArray().put(r).put(c)).toString();
    }
    @Test public void nearExpiryRenewsOnceBeforeInferenceAndImmutableConfigContinuesWithinSameRun()throws Exception {
        try(AccountGatewayTest.Rig rig=new AccountGatewayTest.Rig()){
            rig.fake.expiresIn=1;String id=rig.connect();ModelConfig config=config(rig,id);long initial=config.accountModel.accountVersion;AtomicLong persisted=new AtomicLong(initial);bind(rig,persisted);rig.fake.expiresIn=3600;rig.fake.stream=textStream();RunControl control=new RunControl();
            JSONObject first=ModelGateway.complete(config,messages(),null,control);assertEquals("verified",first.getString("content"));assertEquals(initial+2,persisted.get());assertEquals(initial,config.accountModel.accountVersion);
            assertEquals(initial+2,first.getJSONObject("provider_data").getJSONObject("nativeRef").getLong("accountVersion"));
            assertEquals("verified",ModelGateway.complete(config,messages().put(first),null,control).getString("content"));assertEquals(1,rig.fake.refreshes.get());assertEquals(2,rig.fake.responses.get());
            assertEquals("Bearer fixture-access-secret-1",rig.fake.lastAuthorization);
        }
    }
    @Test public void opaquePreRenewalItemsContinueInRunButCannotGrantAuthorityToANewRun()throws Exception {
        try(AccountGatewayTest.Rig rig=new AccountGatewayTest.Rig()){
            rig.fake.expiresIn=61;String id=rig.connect();ModelConfig config=config(rig,id);AtomicLong persisted=new AtomicLong(config.accountModel.accountVersion);bind(rig,persisted);RunControl control=new RunControl();rig.fake.stream=callStream();JSONArray history=messages();
            JSONObject first=ModelGateway.complete(config,history,tools(),control);history.put(first).put(new JSONObject().put("role","tool").put("tool_call_id","call").put("content","actual-host-receipt"));
            long until=rig.book.credentials(id,config.accountModel.accountVersion).expiresAt-59_950;while(System.currentTimeMillis()<until)Thread.sleep(Math.max(1,Math.min(30,until-System.currentTimeMillis())));
            rig.fake.expiresIn=3600;rig.fake.stream=textStream();JSONObject second=ModelGateway.complete(config,history,tools(),control);assertEquals(1,rig.fake.refreshes.get());assertTrue(rig.fake.lastRequest.toString().contains("opaque-renewal-fixture"));
            history.put(second);assertEquals("verified",ModelGateway.complete(config,history,tools(),control).getString("content"));assertEquals(3,rig.fake.responses.get());
            ModelConfig restored=config(rig,id);try{ModelGateway.complete(restored,history,tools(),new RunControl());fail("History granted its own renewal authority");}catch(ModelGateway.GatewayException expected){assertTrue(expected.getMessage().contains("历史"));}assertEquals(3,rig.fake.responses.get());
        }
    }
    @Test public void reviewedInferenceWaitersShareOnlyTheAdmittedRefreshFlight()throws Exception {
        try(AccountGatewayTest.Rig rig=new AccountGatewayTest.Rig()){
            rig.fake.expiresIn=1;String id=rig.connect();ModelConfig config=config(rig,id);AtomicLong persisted=new AtomicLong(config.accountModel.accountVersion);bind(rig,persisted);rig.fake.expiresIn=3600;rig.fake.stream=textStream();rig.fake.allowRefresh=new CountDownLatch(1);
            ExecutorService workers=Executors.newFixedThreadPool(2);CountDownLatch waiting=new CountDownLatch(1);
            try{
                Future<JSONObject> first=workers.submit(()->ModelGateway.complete(config,messages(),null,new RunControl()));assertTrue(rig.fake.refreshEntered.await(2,TimeUnit.SECONDS));
                Future<JSONObject> second=workers.submit(()->{waiting.countDown();return ModelGateway.complete(config,messages(),null,new RunControl());});assertTrue(waiting.await(1,TimeUnit.SECONDS));Thread.sleep(100);rig.fake.allowRefresh.countDown();
                assertEquals("verified",first.get(3,TimeUnit.SECONDS).getString("content"));assertEquals("verified",second.get(3,TimeUnit.SECONDS).getString("content"));assertEquals(1,rig.fake.refreshes.get());assertEquals(2,rig.fake.responses.get());
                try{ModelGateway.complete(config,messages(),null,new RunControl());fail("Old selection aliased to a completed refresh");}catch(ModelGateway.GatewayException expected){assertTrue(expected.getMessage().contains("版本"));}assertEquals(2,rig.fake.responses.get());
            }finally{rig.fake.allowRefresh.countDown();workers.shutdownNow();}
        }
    }
    @Test public void cancelledRenewalWaiterDoesNotCancelLeaderOrReachInference()throws Exception {
        try(AccountGatewayTest.Rig rig=new AccountGatewayTest.Rig()){
            rig.fake.expiresIn=1;String id=rig.connect();ModelConfig config=config(rig,id);bind(rig,new AtomicLong(config.accountModel.accountVersion));rig.fake.expiresIn=3600;rig.fake.stream=textStream();rig.fake.allowRefresh=new CountDownLatch(1);RunControl waiter=new RunControl();ExecutorService workers=Executors.newFixedThreadPool(2);
            try{Future<JSONObject> first=workers.submit(()->ModelGateway.complete(config,messages(),null,new RunControl()));assertTrue(rig.fake.refreshEntered.await(2,TimeUnit.SECONDS));Future<JSONObject> second=workers.submit(()->ModelGateway.complete(config,messages(),null,waiter));Thread.sleep(100);waiter.cancel();try{second.get(2,TimeUnit.SECONDS);fail();}catch(java.util.concurrent.ExecutionException expected){assertTrue(expected.getCause() instanceof InterruptedException);}assertFalse(first.isDone());rig.fake.allowRefresh.countDown();assertEquals("verified",first.get(3,TimeUnit.SECONDS).getString("content"));assertEquals(1,rig.fake.responses.get());assertEquals(1,rig.fake.refreshes.get());}finally{rig.fake.allowRefresh.countDown();workers.shutdownNow();}
        }
    }
    @Test public void logoutDuringRenewalPublishesNeitherSelectionNorModelPrompt()throws Exception {
        try(AccountGatewayTest.Rig rig=new AccountGatewayTest.Rig()){
            rig.fake.expiresIn=1;String id=rig.connect();ModelConfig config=config(rig,id);long initial=config.accountModel.accountVersion;AtomicLong persisted=new AtomicLong(initial);bind(rig,persisted);rig.fake.expiresIn=3600;rig.fake.allowRefresh=new CountDownLatch(1);ExecutorService worker=Executors.newSingleThreadExecutor();
            try{Future<JSONObject> pending=worker.submit(()->ModelGateway.complete(config,messages(),null,new RunControl()));assertTrue(rig.fake.refreshEntered.await(2,TimeUnit.SECONDS));rig.gateway.logout(id,new GatewayCall(5000));rig.fake.allowRefresh.countDown();try{pending.get(3,TimeUnit.SECONDS);fail();}catch(java.util.concurrent.ExecutionException expected){assertTrue(expected.getCause() instanceof ModelGateway.GatewayException);}assertEquals(initial,persisted.get());assertEquals(0,rig.fake.responses.get());assertEquals("signed_out",rig.book.row(id).getString("status"));}finally{rig.fake.allowRefresh.countDown();worker.shutdownNow();}
        }
    }
    @Test public void failedSelectionPersistenceStopsBeforeChargedRequestAndDoesNotRepeatRefresh()throws Exception {
        try(AccountGatewayTest.Rig rig=new AccountGatewayTest.Rig()){
            rig.fake.expiresIn=1;String id=rig.connect();ModelConfig config=config(rig,id);rig.fake.expiresIn=3600;
            ModelGateway.installAccountInferencePort(new ModelGateway.AccountInferencePort(){
                public void stream(ModelConfig.AccountModelRef ref,ResponsesRequest req,GatewayCall op,ChatGptProvider.EventSink sink)throws GatewayFailure{throw new GatewayFailure("UNEXPECTED_LEGACY_PATH");}
                public void streamWithVersion(ModelConfig.AccountModelRef ref,ResponsesRequest req,GatewayCall op,ModelGateway.AccountVersionSink version,ChatGptProvider.EventSink sink)throws GatewayFailure{rig.gateway.streamResponsesReviewed(ref.accountId,ref.accountVersion,req,op,actual->{throw new GatewayFailure("ACCOUNT_SELECTION_STORE_UNAVAILABLE");},sink);}
            });
            try{ModelGateway.complete(config,messages(),null,new RunControl());fail();}catch(ModelGateway.GatewayException expected){assertTrue(expected.getMessage().contains("保存"));}
            assertEquals(1,rig.fake.refreshes.get());assertEquals(0,rig.fake.responses.get());
        }
    }
    @Test public void ownerRouteChangeIsNotOverwrittenByRenewalAndSameRunCannotChangeIdentity()throws Exception {
        try(AccountGatewayTest.Rig rig=new AccountGatewayTest.Rig()){
            rig.fake.expiresIn=1;String id=rig.connect();ModelConfig config=config(rig,id);AtomicLong selectedOtherRoute=new AtomicLong(-100);bind(rig,selectedOtherRoute);rig.fake.expiresIn=3600;rig.fake.stream=textStream();RunControl control=new RunControl();
            assertEquals("verified",ModelGateway.complete(config,messages(),null,control).getString("content"));assertEquals(-100,selectedOtherRoute.get());String another=rig.connect();
            try{ModelGateway.complete(config(rig,another),messages(),null,control);fail();}catch(ModelGateway.GatewayException expected){assertTrue(expected.getMessage().contains("同一次"));}assertEquals(1,rig.fake.responses.get());
        }
    }
    @Test public void renewedResponseIsFencedByActualVersionWhenOwnerDisablesAccount()throws Exception {
        try(AccountGatewayTest.Rig rig=new AccountGatewayTest.Rig()){
            rig.fake.expiresIn=1;String id=rig.connect();ModelConfig config=config(rig,id);long initial=config.accountModel.accountVersion;AtomicLong persisted=new AtomicLong(initial);bind(rig,persisted);rig.fake.expiresIn=3600;rig.fake.stream=textStream();rig.fake.allowResponse=new CountDownLatch(1);ExecutorService worker=Executors.newSingleThreadExecutor();
            try{
                Future<JSONObject> pending=worker.submit(()->ModelGateway.complete(config,messages(),null,new RunControl()));assertTrue(rig.fake.responseEntered.await(2,TimeUnit.SECONDS));assertEquals(initial+2,persisted.get());
                rig.gateway.setEnabledReviewed(id,persisted.get(),false);rig.fake.allowResponse.countDown();
                try{pending.get(3,TimeUnit.SECONDS);fail("Renewed but revoked decision escaped");}catch(java.util.concurrent.ExecutionException expected){assertTrue(expected.getCause() instanceof ModelGateway.GatewayException);}
                assertEquals(1,rig.fake.refreshes.get());assertEquals(1,rig.fake.responses.get());assertFalse(rig.book.row(id).getBoolean("enabled"));
            }finally{rig.fake.allowResponse.countDown();worker.shutdownNow();}
        }
    }
    @Test public void semanticRouteAllowsOnlyTheOriginalRunsActualRenewalAndRejectsNativeRevocation()throws Exception {
        try(AccountGatewayTest.Rig rig=new AccountGatewayTest.Rig()){
            rig.fake.expiresIn=1;String id=rig.connect();ModelConfig captured=config(rig,id);long initial=captured.accountModel.accountVersion;AtomicLong persisted=new AtomicLong(initial);bind(rig,persisted);
            rig.fake.expiresIn=3600;rig.fake.stream=textStream();RunControl original=new RunControl();
            dev.pocketengine.runtime.models.InferenceRouteContinuity.requireCurrent(captured.withStepCeiling(10),captured,original);
            ModelGateway.complete(captured,messages(),null,original);ModelConfig renewed=config(rig,id);assertEquals(initial+2,renewed.accountModel.accountVersion);
            dev.pocketengine.runtime.models.InferenceRouteContinuity.requireCurrent(captured,renewed,original);
            assertFalse(ModelGateway.accountSelectionContinues(new RunControl(),captured.accountModel,renewed.accountModel));
            assertFalse(ModelGateway.accountSelectionContinues(original,captured.accountModel,captured.accountModel));
            assertFalse(ModelGateway.accountSelectionContinues(original,captured.accountModel,renewed.accountModel.withVersion(renewed.accountModel.accountVersion+1)));
            rig.gateway.setEnabledReviewed(id,renewed.accountModel.accountVersion,false);
            assertThrows(SecurityException.class,()->dev.pocketengine.runtime.models.InferenceRouteContinuity.requireCurrent(captured,renewed,original));
            assertFalse(rig.gateway.inferenceIdentityCurrent(id,renewed.accountModel.accountVersion));assertEquals(1,rig.fake.responses.get());assertEquals(1,rig.fake.refreshes.get());
            rig.gateway.logout(id,new GatewayCall(3000));assertFalse(ModelGateway.accountSelectionContinues(original,captured.accountModel,renewed.accountModel));assertEquals(1,rig.fake.responses.get());
        }
    }

}
