package dev.pocketengine.features.proxy.accounts;

import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.features.proxy.LoopbackRelay;
import dev.pocketengine.features.proxy.AppAccountResponsesFixture;
import dev.pocketengine.runtime.models.NativeInferenceScope;
import dev.pocketengine.runtime.models.InferenceAudiencePolicy;
import dev.pocketengine.features.proxy.providers.ChatGptProvider;
import dev.pocketengine.features.proxy.providers.FakeChatGpt;
import dev.pocketengine.features.proxy.providers.RelayDelayedFixture;
import dev.pocketengine.features.proxy.providers.ResponsesFixtures;
import dev.pocketengine.features.proxy.providers.ResponsesRequest;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Original 18 account protocol/lifetime scenarios, migrated to native owner-only AccountGateway/Responses calls. */
public class AccountLoopbackRelayTest {
    private static final String TOKEN="local-client-test-token-123456789";
    private static ModelConfig config(AccountGatewayTest.Rig rig,String id)throws Exception{
        ModelConfig.AccountModelRef ref=ModelConfig.AccountModelRef.fromJson(new JSONObject().put("provider",ModelConfig.ACCOUNT_PROVIDER).put("accountId",id).put("accountVersion",rig.book.row(id).getLong("version")).put("modelId","fixture-model"));
        return new ModelConfig("","","",false,3,10).withAccountModel(ref);
    }
    private static final class Route implements LoopbackRelay.AccountRoute {
        final AccountGateway gateway;final AtomicReference<ModelConfig> selected;final CountDownLatch returned=new CountDownLatch(1);boolean failPersistence;
        Route(AccountGateway gateway,AtomicReference<ModelConfig> selected){this.gateway=gateway;this.selected=selected;}
        void advance(ModelConfig.AccountModelRef ref,long actual)throws GatewayFailure {
            if(failPersistence)throw new GatewayFailure("ACCOUNT_SELECTION_STORE_UNAVAILABLE");
            ModelConfig old=selected.get();if(old.usesAccount()&&old.accountModel.matches(ref))selected.compareAndSet(old,old.withAccountModel(ref.withVersion(actual)));
        }
        public JSONArray models(ModelConfig.AccountModelRef ref,GatewayCall call)throws GatewayFailure {
            JSONObject catalog=gateway.modelsReviewed(ref.accountId,ref.accountVersion,call);
            try{advance(ref,catalog.getLong("version"));return catalog.getJSONArray("models");}catch(GatewayFailure failure){throw failure;}catch(Exception failure){throw new GatewayFailure("ACCOUNT_STORE_UNAVAILABLE");}
        }
        public void stream(ModelConfig.AccountModelRef ref,ResponsesRequest request,GatewayCall call,ChatGptProvider.EventSink sink)throws GatewayFailure{
            try{gateway.streamResponsesReviewed(ref.accountId,ref.accountVersion,request,call,actual->advance(ref,actual),sink);}finally{returned.countDown();}
        }
    }
    private static JSONObject request(boolean stream)throws Exception{return new JSONObject().put("model","fixture-model").put("input",new JSONArray().put(new JSONObject().put("role","user").put("content","hello"))).put("stream",stream).put("store",false);}
    private static String text(String value)throws Exception{JSONObject item=ResponsesFixtures.message("m",value,false,true);return new ResponsesFixtures().item(0,ResponsesFixtures.message("m","",false,false),false).text(0,"m",value,false).item(0,item,true).complete(new JSONArray().put(item)).toString();}
    private static GatewayFailure failed(java.util.concurrent.Future<?> future)throws Exception {
        try{future.get(3,TimeUnit.SECONDS);throw new AssertionError("request unexpectedly completed");}
        catch(java.util.concurrent.ExecutionException failure){if(failure.getCause() instanceof GatewayFailure)return (GatewayFailure)failure.getCause();throw failure;}
    }
    private static JSONObject next(AppAccountResponsesFixture nativeCall)throws Exception {JSONObject value=nativeCall.events.poll(3,TimeUnit.SECONDS);assertNotNull("native stream ended before expected event",value);return value;}
    private static void noCompleted(AppAccountResponsesFixture nativeCall){assertTrue(nativeCall.history.stream().noneMatch(e->"response.completed".equals(e.optString("type"))));assertNull(nativeCall.output);}
    @Test public void authenticationPrecedesCatalogOrInferenceAndModelsUseRealSelectedGateway()throws Exception {
        try(AccountGatewayTest.Rig rig=new AccountGatewayTest.Rig()){
            String id=rig.connect();AtomicReference<ModelConfig> selected=new AtomicReference<>(config(rig,id));Route route=new Route(rig.gateway,selected);
            try(AppAccountResponsesFixture denied=new AppAccountResponsesFixture(selected::get,route)){
                denied.audience=NativeInferenceScope.externalRelay();assertThrows(InferenceAudiencePolicy.Rejected.class,denied::models);
                assertThrows(InferenceAudiencePolicy.Rejected.class,()->denied.response(request(false)));assertEquals(0,rig.fake.models.get());assertEquals(0,rig.fake.responses.get());
            }
            try(AppAccountResponsesFixture owner=new AppAccountResponsesFixture(selected::get,route)){
                JSONArray models=owner.models();assertEquals("fixture-model",models.getJSONObject(0).getString("id"));assertEquals(1,models.length());assertEquals(1,rig.fake.models.get());assertFalse(models.toString().contains(id));assertEquals("Bearer "+FakeChatGpt.access(0),rig.fake.lastAuthorization);
            }
        }
    }
    @Test public void nonstreamTextAndUsageComeFromAccountNotApiKeyRoute()throws Exception {
        try(AccountGatewayTest.Rig rig=new AccountGatewayTest.Rig()){
            String id=rig.connect();AtomicReference<ModelConfig> selected=new AtomicReference<>(config(rig,id));rig.fake.stream=text("hello 你好🙂 "+FakeChatGpt.access(0));
            try(AppAccountResponsesFixture owner=new AppAccountResponsesFixture(selected::get,new Route(rig.gateway,selected))){
                JSONObject response=owner.response(request(false));assertFalse(response.toString().contains(FakeChatGpt.access(0)));assertFalse(response.toString().contains(TOKEN));assertEquals("response",response.getString("object"));assertEquals("completed",response.getString("status"));assertEquals(10,response.getJSONObject("usage").getInt("total_tokens"));assertEquals(1,rig.fake.responses.get());assertTrue(rig.fake.lastRequest.getBoolean("stream"));assertFalse(rig.fake.lastRequest.getBoolean("store"));
            }
        }
    }
    @Test public void strictAccountInputCannotChargeOrSilentlyLoseData()throws Exception {
        try(AccountGatewayTest.Rig rig=new AccountGatewayTest.Rig()){
            rig.fake.expiresIn=1;String id=rig.connect();AtomicReference<ModelConfig> selected=new AtomicReference<>(config(rig,id));rig.fake.expiresIn=3600;
            JSONObject image=request(false).put("input",new JSONArray().put(new JSONObject().put("role","user").put("content",new JSONArray().put(new JSONObject().put("type","input_image").put("image_url","https://example.test/image")))));
            for(JSONObject body:new JSONObject[]{request(false).put("model","other"),request(false).put("temperature",0.7),request(false).put("previous_response_id","old"),image,request(false).put("tools",new JSONArray().put(ResponsesFixtures.declaration("flat"))),new JSONObject().put("messages",new JSONArray())}){
                try(AppAccountResponsesFixture owner=new AppAccountResponsesFixture(selected::get,new Route(rig.gateway,selected))){assertEquals(400,AppAccountResponsesFixture.status(assertThrows(GatewayFailure.class,()->owner.response(body))));}
            }
            assertEquals(0,rig.fake.refreshes.get());assertEquals(0,rig.fake.responses.get());
        }
    }
    @Test public void missingHostPortFailsInsteadOfApiFallback()throws Exception {
        try(AccountGatewayTest.Rig rig=new AccountGatewayTest.Rig()){String id=rig.connect();ModelConfig selected=config(rig,id);try(AppAccountResponsesFixture owner=new AppAccountResponsesFixture(()->selected,null)){
            GatewayFailure failure=assertThrows(GatewayFailure.class,()->owner.response(request(false)));assertEquals(503,AppAccountResponsesFixture.status(failure));assertEquals("ACCOUNT_ROUTE_UNAVAILABLE",failure.code);assertEquals(0,rig.fake.responses.get());
        }}
    }
    @Test public void functionsAndOpaqueReasoningRoundTripWithoutAnyHostExecution()throws Exception {
        try(AccountGatewayTest.Rig rig=new AccountGatewayTest.Rig()){
            String id=rig.connect();AtomicReference<ModelConfig> selected=new AtomicReference<>(config(rig,id));JSONObject reasoning=new JSONObject().put("type","reasoning").put("id","r").put("summary",new JSONArray()).put("encrypted_content","opaque-fixture"),function=ResponsesFixtures.function("f","call-observe","observe","{}",true);
            rig.fake.stream=new ResponsesFixtures().item(0,new JSONObject(reasoning.toString()).put("encrypted_content",JSONObject.NULL),false).item(0,reasoning,true).item(1,ResponsesFixtures.function("f","call-observe","observe","",false),false).argument(1,"f","{}",false).argument(1,"f","{}",true).item(1,function,true).complete(new JSONArray().put(reasoning).put(function)).toString();
            JSONObject body=request(false).put("tools",ResponsesFixtures.tools());JSONArray items;
            try(AppAccountResponsesFixture owner=new AppAccountResponsesFixture(selected::get,new Route(rig.gateway,selected))){items=owner.response(body).getJSONArray("output");assertEquals("opaque-fixture",items.getJSONObject(0).getString("encrypted_content"));assertEquals("call-observe",items.getJSONObject(1).getString("call_id"));assertEquals(1,rig.fake.responses.get());}
            body.getJSONArray("input").put(items.getJSONObject(0)).put(items.getJSONObject(1)).put(new JSONObject().put("type","function_call_output").put("call_id","call-observe").put("output","client-supplied-fixture-result"));rig.fake.stream=text("receipt seen");
            try(AppAccountResponsesFixture owner=new AppAccountResponsesFixture(selected::get,new Route(rig.gateway,selected))){assertEquals("completed",owner.response(body).getString("status"));assertEquals(2,rig.fake.responses.get());assertTrue(rig.fake.lastRequest.toString().contains("opaque-fixture"));assertTrue(rig.fake.lastRequest.toString().contains("client-supplied-fixture-result"));}
        }
    }
    @Test public void actualSseTextArrivesBeforeProviderTailAndHasOnlyResponsesTerminal()throws Exception {
        CountDownLatch sent=new CountDownLatch(1),release=new CountDownLatch(1);
        try(AccountGatewayTest.Rig rig=new AccountGatewayTest.Rig()){
            String id=rig.connect();AtomicReference<ModelConfig> selected=new AtomicReference<>(config(rig,id));String stream=text("first 你好🙂");int split=stream.indexOf("event: response.output_text.done");assertTrue(split>0);RelayDelayedFixture.install(rig.fake,stream.substring(0,split),stream.substring(split),sent,release);
            try(AppAccountResponsesFixture owner=new AppAccountResponsesFixture(selected::get,new Route(rig.gateway,selected))){
                java.util.concurrent.Future<JSONObject> result=owner.start(request(true));JSONObject event;long expected=0;do{event=next(owner);assertEquals(expected++,event.getLong("sequence_number"));}while(!event.getString("type").equals("response.output_text.delta"));assertEquals("first 你好🙂",event.getString("delta"));assertEquals(1,release.getCount());assertFalse(result.isDone());release.countDown();
                do{event=next(owner);assertEquals(expected++,event.getLong("sequence_number"));}while(!event.getString("type").equals("response.completed"));assertEquals("completed",event.getJSONObject("response").getString("status"));result.get(3,TimeUnit.SECONDS);assertTrue(owner.events.isEmpty());
            }
        }finally{release.countDown();}
    }
    @Test public void incompleteProviderStreamNeverReturnsCompletedOrReleasesPendingFunctions()throws Exception {
        try(AccountGatewayTest.Rig rig=new AccountGatewayTest.Rig()){
            String id=rig.connect();AtomicReference<ModelConfig> selected=new AtomicReference<>(config(rig,id));String stream=text("partial");rig.fake.stream=stream.substring(0,stream.indexOf("event: response.completed"));
            try(AppAccountResponsesFixture owner=new AppAccountResponsesFixture(selected::get,new Route(rig.gateway,selected))){assertThrows(GatewayFailure.class,()->owner.response(request(true)));noCompleted(owner);assertEquals(1,rig.fake.responses.get());}
        }
    }
    @Test public void providerQuotaErrorIsNotReplayedAndDoesNotSwitchAccount()throws Exception {
        try(AccountGatewayTest.Rig rig=new AccountGatewayTest.Rig()){
            String id=rig.connect(),other=rig.connect();AtomicReference<ModelConfig> selected=new AtomicReference<>(config(rig,id));rig.fake.responseStatus=429;
            try(AppAccountResponsesFixture owner=new AppAccountResponsesFixture(selected::get,new Route(rig.gateway,selected))){GatewayFailure failure=assertThrows(GatewayFailure.class,()->owner.response(request(false)));assertEquals(429,AppAccountResponsesFixture.status(failure));assertFalse(AppAccountResponsesFixture.error(failure).toString().contains("fixture-access-secret"));assertEquals(1,rig.fake.responses.get());assertEquals(id,rig.book.selected());assertEquals("connected",rig.book.row(other).getString("status"));}
        }
    }
    @Test public void renewalUsesExistingSingleflightAndPersistsVersionBeforeRequest()throws Exception {
        try(AccountGatewayTest.Rig rig=new AccountGatewayTest.Rig()){
            rig.fake.expiresIn=1;String id=rig.connect();AtomicReference<ModelConfig> selected=new AtomicReference<>(config(rig,id));long initial=selected.get().accountModel.accountVersion;rig.fake.expiresIn=3600;rig.fake.stream=text("renewed");
            for(int i=0;i<2;i++)try(AppAccountResponsesFixture owner=new AppAccountResponsesFixture(selected::get,new Route(rig.gateway,selected))){assertEquals("completed",owner.response(request(false)).getString("status"));assertEquals(initial+1,selected.get().accountModel.accountVersion);assertEquals(1,rig.fake.refreshes.get());assertEquals("Bearer "+FakeChatGpt.access(1),rig.fake.lastAuthorization);}
            assertEquals(2,rig.fake.responses.get());
        }
    }
    @Test public void failedNativeVersionPersistenceCannotSubmitBillableRequest()throws Exception {
        try(AccountGatewayTest.Rig rig=new AccountGatewayTest.Rig()){
            rig.fake.expiresIn=1;String id=rig.connect();AtomicReference<ModelConfig> selected=new AtomicReference<>(config(rig,id));rig.fake.expiresIn=3600;Route route=new Route(rig.gateway,selected);route.failPersistence=true;
            try(AppAccountResponsesFixture owner=new AppAccountResponsesFixture(selected::get,route)){GatewayFailure failure=assertThrows(GatewayFailure.class,()->owner.response(request(false)));assertEquals(503,AppAccountResponsesFixture.status(failure));assertEquals(1,rig.fake.refreshes.get());assertEquals(0,rig.fake.responses.get());}
        }
    }
    @Test public void disconnectBeforeHeadersCancelsSameActualGatewayCall()throws Exception {
        try(AccountGatewayTest.Rig rig=new AccountGatewayTest.Rig()){
            String id=rig.connect();AtomicReference<ModelConfig> selected=new AtomicReference<>(config(rig,id));rig.fake.allowResponse=new CountDownLatch(1);Route route=new Route(rig.gateway,selected);
            try(AppAccountResponsesFixture owner=new AppAccountResponsesFixture(selected::get,route)){java.util.concurrent.Future<?> pending=owner.start(request(true));assertTrue(rig.fake.responseEntered.await(2,TimeUnit.SECONDS));owner.call.cancel();assertNotNull(failed(pending));assertTrue(route.returned.await(2,TimeUnit.SECONDS));assertEquals(1,rig.fake.allowResponse.getCount());assertEquals(1,rig.fake.responses.get());noCompleted(owner);}
        }
    }
    @Test public void stopDuringStalledBodyIsPromptAndNoLateCompletionEscapes()throws Exception {
        CountDownLatch sent=new CountDownLatch(1),release=new CountDownLatch(1);
        try(AccountGatewayTest.Rig rig=new AccountGatewayTest.Rig()){
            String id=rig.connect();AtomicReference<ModelConfig> selected=new AtomicReference<>(config(rig,id));String stream=text("partial");int split=stream.indexOf("event: response.output_text.done");RelayDelayedFixture.install(rig.fake,stream.substring(0,split),stream.substring(split),sent,release);Route route=new Route(rig.gateway,selected);
            try(AppAccountResponsesFixture owner=new AppAccountResponsesFixture(selected::get,route)){java.util.concurrent.Future<?> pending=owner.start(request(true));assertTrue(sent.await(2,TimeUnit.SECONDS));long start=System.nanoTime();owner.close();assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<1000);assertTrue(route.returned.await(2,TimeUnit.SECONDS));assertNotNull(failed(pending));noCompleted(owner);}
        }finally{release.countDown();}
    }
    @Test public void accountDisableFencesLateReplyWithoutReplay()throws Exception {
        try(AccountGatewayTest.Rig rig=new AccountGatewayTest.Rig()){
            String id=rig.connect();AtomicReference<ModelConfig> selected=new AtomicReference<>(config(rig,id));rig.fake.allowResponse=new CountDownLatch(1);rig.fake.stream=text("too late");Route route=new Route(rig.gateway,selected);
            try(AppAccountResponsesFixture owner=new AppAccountResponsesFixture(selected::get,route)){java.util.concurrent.Future<?> pending=owner.start(request(true));assertTrue(rig.fake.responseEntered.await(2,TimeUnit.SECONDS));rig.gateway.setEnabled(id,false);rig.fake.allowResponse.countDown();assertNotNull(failed(pending));assertTrue(owner.history.stream().noneMatch(e->e.toString().contains("too late")));noCompleted(owner);assertEquals(1,rig.fake.responses.get());}
        }
    }
    @Test public void deadlineClosesAnActualStalledAccountBodyAndCancelsUpstream()throws Exception {
        CountDownLatch sent=new CountDownLatch(1),release=new CountDownLatch(1);
        try(AccountGatewayTest.Rig rig=new AccountGatewayTest.Rig()){
            String id=rig.connect();AtomicReference<ModelConfig> selected=new AtomicReference<>(config(rig,id));String stream=text("deadline partial");int split=stream.indexOf("event: response.output_text.done");RelayDelayedFixture.install(rig.fake,stream.substring(0,split),stream.substring(split),sent,release);Route route=new Route(rig.gateway,selected);
            long start=System.nanoTime();try(AppAccountResponsesFixture owner=new AppAccountResponsesFixture(selected::get,route,500)){assertThrows(GatewayFailure.class,()->owner.response(request(true)));noCompleted(owner);assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<2500);assertTrue(route.returned.await(1,TimeUnit.SECONDS));assertEquals(1,release.getCount());assertEquals(1,rig.fake.responses.get());}
        }finally{release.countDown();}
    }
    @Test public void accountNetworkSlotsRejectExcessInferenceWithoutRetry()throws Exception {
        try(AccountGatewayTest.Rig rig=new AccountGatewayTest.Rig()){
            String id=rig.connect();AtomicReference<ModelConfig> selected=new AtomicReference<>(config(rig,id));rig.fake.allowResponse=new CountDownLatch(1);Route route=new Route(rig.gateway,selected);
            try(AppAccountResponsesFixture a=new AppAccountResponsesFixture(selected::get,route);AppAccountResponsesFixture b=new AppAccountResponsesFixture(selected::get,route);AppAccountResponsesFixture excess=new AppAccountResponsesFixture(selected::get,route)){
                java.util.concurrent.Future<?> first=a.start(request(false)),second=b.start(request(false));long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);while(rig.fake.responses.get()<2&&System.nanoTime()<until)Thread.sleep(5);assertEquals(2,rig.fake.responses.get());GatewayFailure failure=assertThrows(GatewayFailure.class,()->excess.response(request(false)));assertEquals(503,AppAccountResponsesFixture.status(failure));assertEquals("GATEWAY_BUSY",failure.code);assertEquals(2,rig.fake.responses.get());a.call.cancel();b.call.cancel();assertNotNull(failed(first));assertNotNull(failed(second));
            }
        }
    }
    @Test public void catalogDisconnectCancelsTheSameGatewayOperation()throws Exception {
        try(AccountGatewayTest.Rig rig=new AccountGatewayTest.Rig()){
            String id=rig.connect();AtomicReference<ModelConfig> selected=new AtomicReference<>(config(rig,id));rig.fake.allowModels=new CountDownLatch(1);CountDownLatch returned=new CountDownLatch(1);Route route=new Route(rig.gateway,selected);
            LoopbackRelay.AccountRoute port=new LoopbackRelay.AccountRoute(){public JSONArray models(ModelConfig.AccountModelRef ref,GatewayCall call)throws GatewayFailure{try{return route.models(ref,call);}finally{returned.countDown();}}public void stream(ModelConfig.AccountModelRef ref,ResponsesRequest req,GatewayCall call,ChatGptProvider.EventSink sink)throws GatewayFailure{route.stream(ref,req,call,sink);}};
            try(AppAccountResponsesFixture owner=new AppAccountResponsesFixture(selected::get,port)){java.util.concurrent.Future<?> pending=owner.startModels();assertTrue(rig.fake.modelsEntered.await(2,TimeUnit.SECONDS));owner.call.cancel();assertNotNull(failed(pending));assertTrue(returned.await(2,TimeUnit.SECONDS));assertEquals(1,rig.fake.allowModels.getCount());assertEquals(1,rig.fake.models.get());}
        }
    }
    @Test public void providerAuthenticationAndPermissionStatusesRemainDistinctAndSafe()throws Exception {
        for(int status:new int[]{401,403})try(AccountGatewayTest.Rig rig=new AccountGatewayTest.Rig()){
            String id=rig.connect();AtomicReference<ModelConfig> selected=new AtomicReference<>(config(rig,id));rig.fake.responseStatus=status;
            try(AppAccountResponsesFixture owner=new AppAccountResponsesFixture(selected::get,new Route(rig.gateway,selected))){GatewayFailure failure=assertThrows(GatewayFailure.class,()->owner.response(request(false)));assertEquals(status,AppAccountResponsesFixture.status(failure));assertEquals(status==401?"CHATGPT_ACCOUNT_PERMISSION_REJECTED":"CHATGPT_POLICY_RESTRICTED",failure.code);assertFalse(AppAccountResponsesFixture.error(failure).toString().contains("fixture-access-secret"));assertEquals(1,rig.fake.responses.get());}
        }
    }
    @Test public void stalledDownstreamWriteCannotHoldAccountDisableUntilFullRequestDeadline()throws Exception {
        CountDownLatch sent=new CountDownLatch(1),release=new CountDownLatch(1);java.util.concurrent.ExecutorService actor=java.util.concurrent.Executors.newSingleThreadExecutor();
        try(AccountGatewayTest.Rig rig=new AccountGatewayTest.Rig()){
            String id=rig.connect();AtomicReference<ModelConfig> selected=new AtomicReference<>(config(rig,id));Route route=new Route(rig.gateway,selected);String stream=text("queued native delta");int split=stream.indexOf("event: response.output_text.done");RelayDelayedFixture.install(rig.fake,stream.substring(0,split),stream.substring(split),sent,release);
            try(AppAccountResponsesFixture owner=new AppAccountResponsesFixture(selected::get,route)){
                java.util.concurrent.Future<?> pending=owner.start(request(true));assertTrue(sent.await(2,TimeUnit.SECONDS));long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);while(owner.events.isEmpty()&&System.nanoTime()<until)Thread.sleep(5);assertFalse("consumer is deliberately not draining its queued native events",owner.events.isEmpty());
                long started=System.nanoTime();actor.submit(()->{rig.gateway.setEnabled(id,false);return null;}).get(2,TimeUnit.SECONDS);assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)<2000);assertNotNull(failed(pending));assertTrue(route.returned.await(1,TimeUnit.SECONDS));assertFalse(rig.book.row(id).getBoolean("enabled"));assertEquals(1,rig.fake.responses.get());assertEquals(1,release.getCount());noCompleted(owner);
            }
        }finally{release.countDown();actor.shutdownNow();}
    }
}
