package dev.pocketengine.features.proxy.accounts;

import dev.pocketengine.core.CapabilityPort;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.core.RunOutcome;
import dev.pocketengine.features.proxy.providers.ResponsesFixtures;
import dev.pocketengine.runtime.AgentRunner;
import dev.pocketengine.runtime.ModelGateway;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import static org.junit.Assert.*;

/** Real native gateway + loopback OAuth/Responses fixtures. No live provider or user settings. */
public final class AccountModelInferenceTest {
    @After public void unbind(){ModelGateway.installAccountInferencePort(null);}
    private ModelConfig config(AccountGatewayTest.Rig rig,String id)throws Exception {
        JSONObject ref=new JSONObject().put("provider","chatgpt_plan").put("accountId",id).put("accountVersion",rig.book.row(id).getLong("version")).put("modelId","fixture-model");
        return new ModelConfig("https://old.example/v1","api-key-must-not-cross","api-model",false,3,10).withAccountModel(ModelConfig.AccountModelRef.fromJson(ref));
    }
    private void bind(AccountGatewayTest.Rig rig){ModelGateway.installAccountInferencePort((ref,request,op,sink)->rig.gateway.streamResponsesReviewed(ref.accountId,ref.accountVersion,request,op,sink));}
    private JSONArray messages()throws Exception{return new JSONArray().put(new JSONObject().put("role","system").put("content","Host system instruction")).put(new JSONObject().put("role","user").put("content","Read actual state"));}
    private JSONArray tools()throws Exception{return new JSONArray().put(new JSONObject().put("type","function").put("function",new JSONObject().put("name","read_state").put("parameters",new JSONObject().put("type","object").put("properties",new JSONObject()))));}
    private String textStream()throws Exception {
        JSONObject m=ResponsesFixtures.message("msg_final","实际结果",false,true);
        return new ResponsesFixtures().item(0,ResponsesFixtures.message("msg_final","",false,false),false).text(0,"msg_final","实际结果",false).item(0,m,true).complete(new JSONArray().put(m)).toString();
    }
    private String callStream()throws Exception {
        JSONObject reasoning=new JSONObject().put("type","reasoning").put("id","rs_private").put("summary",new JSONArray()).put("encrypted_content","opaque-fixture-only");
        JSONObject done=ResponsesFixtures.function("item_not_call","call_1","read_state","{}",true).put("namespace","pocketworld");
        return new ResponsesFixtures().item(0,new JSONObject(reasoning.toString()).put("encrypted_content",JSONObject.NULL),false).item(0,reasoning,true)
            .item(1,ResponsesFixtures.function("item_not_call","call_1","read_state","",false).put("namespace","pocketworld"),false)
            .argument(1,"item_not_call","{}",false).argument(1,"item_not_call","{}",true).item(1,done,true).complete(new JSONArray().put(reasoning).put(done)).toString();
    }
    @Test public void mainGatewayUsesReviewedAccountNotSelectedAliasAndPreservesOpaqueContinuation()throws Exception {
        try(AccountGatewayTest.Rig rig=new AccountGatewayTest.Rig()){
            String id=rig.connect();bind(rig);ModelConfig config=config(rig,id);String second=rig.connect();rig.gateway.select(second);
            rig.fake.stream=callStream();JSONArray history=messages();JSONObject first=ModelGateway.complete(config,history,tools(),new RunControl());
            assertEquals("call_1",first.getJSONArray("tool_calls").getJSONObject(0).getString("id"));assertEquals("",first.getString("content"));
            JSONObject sent=rig.fake.lastRequest;assertEquals("fixture-model",sent.getString("model"));assertFalse(sent.getBoolean("store"));assertTrue(sent.getBoolean("stream"));
            assertEquals("developer",sent.getJSONArray("input").getJSONObject(0).getString("role"));assertEquals("pocketworld",sent.getJSONArray("tools").getJSONObject(0).getString("name"));
            assertFalse(sent.has("max_output_tokens"));assertFalse(sent.toString().contains("api-key-must-not-cross"));assertFalse(sent.toString().contains(id));
            assertEquals("Bearer fixture-access-secret-0",rig.fake.lastAuthorization);
            history.put(first).put(new JSONObject().put("role","tool").put("tool_call_id","call_1").put("content","actual-receipt"));rig.fake.stream=textStream();
            assertEquals("实际结果",ModelGateway.complete(config,history,tools(),new RunControl()).getString("content"));
            JSONArray next=rig.fake.lastRequest.getJSONArray("input");assertEquals("opaque-fixture-only",next.getJSONObject(2).getString("encrypted_content"));
            assertEquals("item_not_call",next.getJSONObject(3).getString("id"));assertEquals("call_1",next.getJSONObject(4).getString("call_id"));assertEquals(2,rig.fake.responses.get());
        }
    }
    @Test public void existingAgentRunnerDispatchesThroughCapabilityPortOnlyAfterCompletedReply()throws Exception {
        try(AccountGatewayTest.Rig rig=new AccountGatewayTest.Rig()){
            String id=rig.connect();bind(rig);rig.fake.stream=callStream();AtomicInteger executions=new AtomicInteger();
            CapabilityPort port=new CapabilityPort(){
                public JSONArray tools()throws Exception{return AccountModelInferenceTest.this.tools();}
                public boolean supports(String name){return name.equals("read_state");}
                public boolean sideEffecting(String name){return false;}
                public JSONObject execute(String name,JSONObject args,RunControl control)throws Exception{control.check();executions.incrementAndGet();rig.fake.stream=textStream();return new JSONObject().put("ok",true).put("actual","host-receipt");}
            };
            RunOutcome result=new AgentRunner(null,port).run("fixture-task","Read state",config(rig,id),new RunControl(),event->{});
            assertEquals("needs_confirmation",result.status);assertEquals(1,executions.get());assertEquals(2,rig.fake.responses.get());
            assertTrue(rig.fake.lastRequest.toString().contains("host-receipt"));assertTrue(result.evidence.getBoolean("noDeviceObservation"));
        }
    }
    @Test public void staleReferenceNeverSendsPromptAndDoesNotFallBackToAnotherAccount()throws Exception {
        try(AccountGatewayTest.Rig rig=new AccountGatewayTest.Rig()){
            String id=rig.connect();bind(rig);ModelConfig config=config(rig,id);String second=rig.connect();rig.gateway.select(second);rig.gateway.setEnabled(id,false);
            try{ModelGateway.complete(config,messages(),null,new RunControl());fail();}catch(ModelGateway.GatewayException expected){assertTrue(expected.getMessage().contains("版本"));}
            assertEquals(0,rig.fake.responses.get());assertEquals(second,rig.book.selected());
        }
    }
    @Test public void disableLogoutAndReauthorizationFenceDelayedResultWithoutReplay()throws Exception {
        for(String transition:new String[]{"disable","logout","reauthorize"})try(AccountGatewayTest.Rig rig=new AccountGatewayTest.Rig()){
            String id=rig.connect();bind(rig);ModelConfig config=config(rig,id);rig.fake.stream=textStream();rig.fake.allowResponse=new CountDownLatch(1);
            ExecutorService worker=Executors.newSingleThreadExecutor();
            try{
                Future<JSONObject> pending=worker.submit(()->ModelGateway.complete(config,messages(),null,new RunControl()));assertTrue(rig.fake.responseEntered.await(2,TimeUnit.SECONDS));
                if(transition.equals("disable"))rig.gateway.setEnabled(id,false);else if(transition.equals("logout"))rig.gateway.logout(id,new GatewayCall(5000));else rig.begin(id);
                rig.fake.allowResponse.countDown();try{pending.get(3,TimeUnit.SECONDS);fail("Stale decision returned");}catch(java.util.concurrent.ExecutionException expected){assertTrue(expected.getCause() instanceof ModelGateway.GatewayException);}
                assertEquals(1,rig.fake.responses.get());
            }finally{worker.shutdownNow();}
        }
    }
    @Test public void runCancellationCancelsGatewayCallAndReturnsNoDecision()throws Exception {
        try(AccountGatewayTest.Rig rig=new AccountGatewayTest.Rig()){
            String id=rig.connect();bind(rig);ModelConfig config=config(rig,id);rig.fake.stream=textStream();rig.fake.allowResponse=new CountDownLatch(1);RunControl control=new RunControl();ExecutorService worker=Executors.newSingleThreadExecutor();
            try{Future<JSONObject> pending=worker.submit(()->ModelGateway.complete(config,messages(),null,control));assertTrue(rig.fake.responseEntered.await(2,TimeUnit.SECONDS));control.cancel();try{pending.get(2,TimeUnit.SECONDS);fail();}catch(java.util.concurrent.ExecutionException expected){assertTrue(expected.getCause() instanceof InterruptedException);}assertEquals(1,rig.fake.responses.get());}finally{worker.shutdownNow();}
        }
    }
    @Test public void steeringCancelsOnlyTheCurrentAccountRequestAndKeepsItsOriginalReceipt()throws Exception {
        try(AccountGatewayTest.Rig rig=new AccountGatewayTest.Rig()){
            String id=rig.connect();bind(rig);ModelConfig config=config(rig,id);rig.fake.stream=textStream();rig.fake.allowResponse=new CountDownLatch(1);
            RunControl control=new RunControl();control.bindHostRun("steer-task","steer-run",1);
            java.util.List<dev.pocketengine.runtime.usage.InferenceReceipt> receipts=new java.util.concurrent.CopyOnWriteArrayList<>();
            var target=new dev.pocketengine.runtime.usage.InferenceCapture.Target(new dev.pocketengine.runtime.usage.InferenceReceipt.Context("steer-task","steer-run",1,dev.pocketengine.runtime.usage.InferenceReceipt.Purpose.MAIN,""),new dev.pocketengine.runtime.usage.InferenceCapture.Sink(){public void started(dev.pocketengine.runtime.usage.InferenceReceipt receipt){}public void finished(dev.pocketengine.runtime.usage.InferenceReceipt receipt){receipts.add(receipt);}});
            ExecutorService worker=Executors.newSingleThreadExecutor();
            try{
                long revision=control.modelReplanRevision();Future<JSONObject> pending=worker.submit(()->ModelGateway.completeStreaming(config,messages(),null,control,event->{},target,null,revision));
                assertTrue(rig.fake.responseEntered.await(2,TimeUnit.SECONDS));assertTrue(control.requestModelReplan("steer-task","steer-run",1));
                var stopped=assertThrows(java.util.concurrent.ExecutionException.class,()->pending.get(2,TimeUnit.SECONDS));assertTrue(stopped.getCause() instanceof RunControl.ModelReplanRequested);
                assertEquals(1,rig.fake.allowResponse.getCount());assertFalse(control.isCancelled());assertEquals(1,rig.fake.responses.get());assertEquals(dev.pocketengine.runtime.usage.InferenceReceipt.Outcome.CANCELLED,receipts.get(0).outcome);
                rig.fake.allowResponse.countDown();JSONArray updated=messages().put(new JSONObject().put("role","user").put("content","new original owner input"));
                assertEquals("实际结果",ModelGateway.completeStreaming(config,updated,null,control,event->{},target,null,control.modelReplanRevision()).getString("content"));
                assertEquals(2,rig.fake.responses.get());assertEquals(2,receipts.size());assertTrue(rig.fake.lastRequest.toString().contains("new original owner input"));
                assertTrue(control.matchesHostRun("steer-task","steer-run",1));assertEquals(dev.pocketengine.runtime.usage.InferenceReceipt.Outcome.COMPLETED,receipts.get(1).outcome);
            }finally{rig.fake.allowResponse.countDown();worker.shutdownNow();assertTrue(worker.awaitTermination(3,TimeUnit.SECONDS));}
        }
    }
    @Test public void imageUnknownFieldAndDanglingResultFailBeforeNetwork()throws Exception {
        try(AccountGatewayTest.Rig rig=new AccountGatewayTest.Rig()){
            String id=rig.connect();bind(rig);ModelConfig config=config(rig,id);
            JSONArray[] invalid={messages().put(new JSONObject().put("role","user").put("content",new JSONArray().put(new JSONObject().put("type","image_url").put("image_url",new JSONObject().put("url","data:image/png;base64,AAAA"))))),messages().put(new JSONObject().put("role","user").put("content","x").put("arbitrary","not supported")),messages().put(new JSONObject().put("role","tool").put("tool_call_id","absent").put("content","not proof"))};
            for(JSONArray history:invalid)try{ModelGateway.complete(config,history,tools(),new RunControl());fail();}catch(ModelGateway.GatewayException expected){}
            assertEquals(0,rig.fake.responses.get());
        }
    }
    @Test public void unsupportedAndPartialOutputNeverDispatchesAndHttpFailureIsNotRetried()throws Exception {
        try(AccountGatewayTest.Rig rig=new AccountGatewayTest.Rig()){
            String id=rig.connect();bind(rig);ModelConfig config=config(rig,id);String valid=callStream();rig.fake.stream=valid.substring(0,valid.indexOf("event: response.completed"));
            try{ModelGateway.complete(config,messages(),tools(),new RunControl());fail();}catch(ModelGateway.GatewayException expected){}assertEquals(1,rig.fake.responses.get());
            rig.fake.responseStatus=429;try{ModelGateway.complete(config,messages(),tools(),new RunControl());fail();}catch(ModelGateway.GatewayException expected){assertFalse(expected.getMessage().contains("fixture-access-secret"));}assertEquals(2,rig.fake.responses.get());
        }
    }
    @Test public void foreignAccountContinuationAndNearExpiryAreExplicitNoNetworkFailures()throws Exception {
        try(AccountGatewayTest.Rig rig=new AccountGatewayTest.Rig()){
            String id=rig.connect();bind(rig);ModelConfig config=config(rig,id);rig.fake.stream=callStream();JSONObject first=ModelGateway.complete(config,messages(),tools(),new RunControl());String second=rig.connect();
            try{ModelGateway.complete(config(rig,second),messages().put(first).put(new JSONObject().put("role","tool").put("tool_call_id","call_1").put("content","actual")),tools(),new RunControl());fail();}catch(ModelGateway.GatewayException expected){assertTrue(expected.getMessage().contains("历史"));}
            rig.fake.expiresIn=1;String expired=rig.connect();try{ModelGateway.complete(config(rig,expired),messages(),null,new RunControl());fail();}catch(ModelGateway.GatewayException expected){assertTrue(expected.getMessage().contains("续期"));}
            assertEquals(1,rig.fake.responses.get());assertEquals(0,rig.fake.refreshes.get());
        }
    }
    @Test public void frozenTaskFenceQueuesBeforeRealAccountTransportAndCancellationReturnsBothNativeSlots()throws Exception {
        try(AccountGatewayTest.Rig rig=new AccountGatewayTest.Rig()){
            String id=rig.connect();bind(rig);ModelConfig selected=config(rig,id);rig.fake.stream=textStream();rig.fake.allowResponse=new CountDownLatch(1);
            RunControl original=new RunControl();original.restrictModelConcurrency(1);ExecutorService workers=Executors.newFixedThreadPool(2);
            var field=ModelGateway.class.getDeclaredField("MODEL_CALLS");field.setAccessible(true);Object fence=field.get(null);
            var queued=fence.getClass().getDeclaredMethod("waitingCount");queued.setAccessible(true);
            var active=fence.getClass().getDeclaredMethod("activeCount");active.setAccessible(true);
            try{
                Future<JSONObject> first=workers.submit(()->ModelGateway.complete(selected,messages(),null,original));
                assertTrue("Real AccountGateway must enter the original loopback provider",rig.fake.responseEntered.await(2,TimeUnit.SECONDS));
                Future<JSONObject> second=workers.submit(()->ModelGateway.complete(selected,messages(),null,original));
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
                while((int)queued.invoke(fence)==0&&!second.isDone()&&System.nanoTime()<deadline)Thread.sleep(5);
                assertEquals("The second request must wait before reaching real AccountGateway",1,(int)queued.invoke(fence));
                assertEquals(1,rig.fake.responses.get());assertEquals(1,(int)active.invoke(fence));assertFalse(second.isDone());
                original.cancel();
                for(Future<JSONObject> stopped:java.util.List.of(first,second))try{stopped.get(3,TimeUnit.SECONDS);fail("Cancelled original must not return a model decision");}
                catch(java.util.concurrent.ExecutionException expected){assertTrue(expected.getCause() instanceof InterruptedException);}
                assertEquals("Queued cancellation must not send another account inference",1,rig.fake.responses.get());
                assertEquals(0,(int)queued.invoke(fence));assertEquals(0,(int)active.invoke(fence));
                var slots=AccountGateway.class.getDeclaredField("networkSlots");slots.setAccessible(true);
                assertEquals("The existing account-only two-slot policy is unchanged and fully released",2,((java.util.concurrent.Semaphore)slots.get(rig.gateway)).availablePermits());
                rig.fake.allowResponse.countDown();RunControl next=new RunControl();next.restrictModelConcurrency(1);
                assertEquals("实际结果",ModelGateway.complete(selected,messages(),null,next).getString("content"));
                assertEquals(2,rig.fake.responses.get());assertEquals(0,(int)active.invoke(fence));
            }finally{original.cancel();rig.fake.allowResponse.countDown();workers.shutdownNow();assertTrue(workers.awaitTermination(3,TimeUnit.SECONDS));}
        }
    }
    @Test public void accountConfigNeverContainsSavedKeyAndCannotBeForgedViaGenericSettings()throws Exception {
        try(AccountGatewayTest.Rig rig=new AccountGatewayTest.Rig()){
            ModelConfig config=config(rig,rig.connect());assertEquals("",config.apiKey);assertEquals("",config.baseUrl);assertFalse(config.publicJson().toString().contains("api-key-must-not-cross"));assertSame(config.accountModel,config.withStepCeiling(80).accountModel);
            try{config.withModel("another-model");fail();}catch(IllegalArgumentException expected){}
            try{ModelConfig.fromJson(config.publicJson());fail();}catch(IllegalArgumentException expected){}
            try{ModelConfig.AccountModelRef.fromJson(config.accountModel.toJson().put("accountVersion",1.5));fail();}catch(IllegalArgumentException expected){}
        }
    }
}
