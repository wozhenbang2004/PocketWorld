package dev.pocketengine.features.proxy.accounts;

import dev.pocketengine.features.proxy.providers.ResponsesFixtures;
import dev.pocketengine.features.proxy.providers.ResponsesRequest;
import dev.pocketengine.runtime.models.AccountCatalogFacts;
import dev.pocketengine.runtime.models.ModelConnectionService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Complete recovery journeys with synthetic loopback credentials, never a real account. */
public final class AccountInferenceRecoveryJourneyTest {
    private static ResponsesRequest request()throws Exception{return new ResponsesRequest("fixture-model","",new JSONArray().put(new JSONObject().put("role","user").put("content","hello")),new JSONArray());}
    private static void text(AccountGatewayTest.Rig rig)throws Exception {
        JSONObject done=ResponsesFixtures.message("msg","verified",false,true);
        rig.fake.stream=new ResponsesFixtures().item(0,ResponsesFixtures.message("msg","",false,false),false).text(0,"msg","verified",false).item(0,done,true).complete(new JSONArray().put(done)).toString();
    }
    private static void clearCooldown(AccountBook book,String id)throws Exception {long version=book.row(id).getLong("version");book.metadata(id,version,AccountBook.json("cooldownUntil",0));}
    private static String send(AccountGateway gateway,String id,long version,GatewayCall call,AtomicLong accepted)throws Exception {
        StringBuilder result=new StringBuilder();
        gateway.streamResponsesReviewed(id,version,request(),call,accepted::set,event->{if("text_delta".equals(event.optString("type")))result.append(event.getString("delta"));});
        return result.toString();
    }
    private static long expireFacts(AccountGatewayTest.Rig rig,String id)throws Exception {
        long version=rig.book.row(id).getLong("version");rig.gateway.modelsReviewed(id,version,new GatewayCall(5000));rig.gateway.selectModelReviewed(id,version,"fixture-model");
        JSONObject evidence=rig.book.row(id).getJSONObject("modelEvidence");long at=System.currentTimeMillis()-ModelConnectionService.MAX_CATALOG_AGE_MILLIS-1000;
        evidence.getJSONObject("models").getJSONObject("fixture-model").put("catalogFacts",AccountCatalogFacts.parse(new JSONObject(),"fixture-model",at).toJson());
        rig.book.metadata(id,version,new JSONObject().put("modelEvidence",evidence));rig.gateway.close();return version;
    }
    @Test public void transientRefreshFailureKeepsSelectionAndNextOwnerAttemptCanRecover()throws Exception {
        try(AccountGatewayTest.Rig rig=new AccountGatewayTest.Rig()){
            rig.fake.expiresIn=1;String id=rig.connect();long selected=rig.book.row(id).getLong("version");AtomicLong accepted=new AtomicLong(-1);text(rig);rig.fake.tokenStatus=503;
            assertEquals("PROVIDER_UNAVAILABLE",assertThrows(GatewayFailure.class,()->send(rig.gateway,id,selected,new GatewayCall(5000),accepted)).code);
            assertEquals(selected,rig.book.row(id).getLong("version"));assertEquals(-1,accepted.get());assertEquals(1,rig.fake.refreshes.get());assertEquals(0,rig.fake.responses.get());
            clearCooldown(rig.book,id);rig.fake.tokenStatus=200;rig.fake.expiresIn=3600;
            assertEquals("verified",send(rig.gateway,id,selected,new GatewayCall(5000),accepted));assertEquals(selected+1,accepted.get());assertEquals(2,rig.fake.refreshes.get());assertEquals(1,rig.fake.responses.get());
        }
    }
    @Test public void catalogFailureAfterVerifiedRenewalDoesNotStrandTheOriginalSelection()throws Exception {
        try(AccountGatewayTest.Rig rig=new AccountGatewayTest.Rig()){
            String id=rig.connect();long selected=rig.book.row(id).getLong("version");rig.gateway.modelsReviewed(id,selected,new GatewayCall(5000));rig.gateway.selectModelReviewed(id,selected,"fixture-model");
            Credentials old=rig.book.credentials(id,selected);rig.vault.put(rig.book.row(id).getString("slot"),new Credentials(old.accessToken(),old.refreshToken(),old.idToken(),old.scopes,System.currentTimeMillis()+1000).encode());
            String catalog=rig.fake.modelsBody;text(rig);rig.fake.expiresIn=3600;rig.fake.modelsBody="{}";AtomicLong accepted=new AtomicLong(-1);
            assertEquals("INVALID_MODEL_CATALOG",assertThrows(GatewayFailure.class,()->send(rig.gateway,id,selected,new GatewayCall(5000),accepted)).code);
            assertEquals(selected+1,rig.book.row(id).getLong("version"));assertEquals(-1,accepted.get());assertEquals(0,rig.fake.responses.get());
            rig.fake.modelsBody=catalog;assertEquals("verified",send(rig.gateway,id,selected,new GatewayCall(5000),accepted));assertEquals(1,rig.fake.refreshes.get());assertEquals(1,rig.fake.responses.get());
        }
    }
    @Test public void pendingRotationVerificationRecoversForTheOriginalSelectionWithoutReplayingRefresh()throws Exception {
        try(AccountGatewayTest.Rig rig=new AccountGatewayTest.Rig()){
            rig.fake.expiresIn=1;String id=rig.connect();long selected=rig.book.row(id).getLong("version");text(rig);rig.fake.expiresIn=3600;rig.fake.enforceRefreshRotation=true;rig.fake.jwksStatus=503;
            assertEquals("PROVIDER_UNAVAILABLE",assertThrows(GatewayFailure.class,()->send(rig.gateway,id,selected,new GatewayCall(5000),new AtomicLong())).code);
            assertNotNull(rig.book.pendingRefresh(id,selected));assertEquals(selected,rig.book.row(id).getLong("version"));assertFalse(rig.gateway.inferenceIdentityCurrent(id,selected));
            clearCooldown(rig.book,id);rig.fake.jwksStatus=200;
            assertEquals("verified",send(rig.gateway,id,selected,new GatewayCall(5000),new AtomicLong()));assertEquals(1,rig.fake.refreshes.get());assertEquals(1,rig.fake.responses.get());
        }
    }
    @Test public void expiredCatalogIsRefreshedBeforeInferenceAfterIdleRestart()throws Exception {
        try(AccountGatewayTest.Rig rig=new AccountGatewayTest.Rig()){
            String id=rig.connect();text(rig);long version=expireFacts(rig,id);AccountBook restored=new AccountBook(rig.file,rig.vault,"host-owner");
            try(AccountGateway gateway=new AccountGateway(restored,rig.fake.provider())){
                assertEquals("STALE_MODEL_CATALOG",assertThrows(GatewayFailure.class,()->gateway.modelFacts(id,version,"fixture-model")).code);assertEquals(1,rig.fake.models.get());
                assertEquals("verified",send(gateway,id,version,new GatewayCall(5000),new AtomicLong()));assertEquals(2,rig.fake.models.get());assertEquals(0,rig.fake.refreshes.get());assertEquals(1,rig.fake.responses.get());
            }
        }
    }
    @Test public void concurrentPreparationSharesOneCatalogAndCancellingFirstWaiterDoesNotCancelOther()throws Exception {
        try(AccountGatewayTest.Rig rig=new AccountGatewayTest.Rig()){
            String id=rig.connect();long version=expireFacts(rig,id);AccountBook restored=new AccountBook(rig.file,rig.vault,"host-owner");rig.fake.modelsEntered=new CountDownLatch(1);rig.fake.allowModels=new CountDownLatch(1);
            try(AccountGateway gateway=new AccountGateway(restored,rig.fake.provider())){
                var workers=Executors.newFixedThreadPool(2);GatewayCall firstCall=new GatewayCall(5000);CountDownLatch secondStarted=new CountDownLatch(1);
                try{
                    var first=workers.submit(()->{try{gateway.prepareForInference(id,version,"fixture-model",firstCall);return "unexpected";}catch(GatewayFailure failure){return failure.code;}});
                    assertTrue(rig.fake.modelsEntered.await(2,TimeUnit.SECONDS));
                    var second=workers.submit(()->{secondStarted.countDown();return gateway.prepareForInference(id,version,"fixture-model",new GatewayCall(5000));});assertTrue(secondStarted.await(2,TimeUnit.SECONDS));
                    // Wait only for the second native waiter to join the held read, not for provider timing.
                    long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);while(gateway.catalogWaiterCount(id)<2&&System.nanoTime()<until)Thread.sleep(5);assertEquals(2,gateway.catalogWaiterCount(id));
                    firstCall.cancel();assertEquals("CANCELLED",first.get(2,TimeUnit.SECONDS));assertFalse(second.isDone());rig.fake.allowModels.countDown();
                    assertEquals(version,second.get(2,TimeUnit.SECONDS).accountVersion);assertEquals(2,rig.fake.models.get());assertEquals(0,rig.fake.responses.get());
                }finally{rig.fake.allowModels.countDown();workers.shutdownNow();}
            }
        }
    }
    @Test public void successfulSameGrantRenewalDoesNotDiscardAlreadyPostedResponseOrRewriteItsVersion()throws Exception {
        try(AccountGatewayTest.Rig rig=new AccountGatewayTest.Rig()){
            String id=rig.connect();long version=rig.book.row(id).getLong("version");text(rig);rig.fake.allowResponse=new CountDownLatch(1);var worker=Executors.newSingleThreadExecutor();AtomicLong accepted=new AtomicLong(-1);
            try{var result=worker.submit(()->send(rig.gateway,id,version,new GatewayCall(5000),accepted));assertTrue(rig.fake.responseEntered.await(2,TimeUnit.SECONDS));
                rig.gateway.refreshReviewed(id,version,new GatewayCall(5000));assertEquals(version+1,rig.book.row(id).getLong("version"));rig.fake.allowResponse.countDown();
                assertEquals("verified",result.get(2,TimeUnit.SECONDS));assertEquals(version,accepted.get());assertEquals(1,rig.fake.responses.get());assertEquals(1,rig.fake.refreshes.get());
            }finally{rig.fake.allowResponse.countDown();worker.shutdownNow();}
        }
    }
    @Test public void logoutDisableAndInteractiveReauthorizationStillRevokeAdmittedResponse()throws Exception {
        for(String action:new String[]{"logout","disable","reauthorize"})try(AccountGatewayTest.Rig rig=new AccountGatewayTest.Rig()){
            String id=rig.connect();long version=rig.book.row(id).getLong("version");text(rig);rig.fake.allowResponse=new CountDownLatch(1);var worker=Executors.newSingleThreadExecutor();AtomicInteger completed=new AtomicInteger();
            try{var result=worker.submit(()->{try{rig.gateway.streamResponsesReviewed(id,version,request(),new GatewayCall(5000),actual->{},event->{if("completed".equals(event.optString("type")))completed.incrementAndGet();});return "unexpected";}catch(GatewayFailure failure){return failure.code;}});
                assertTrue(rig.fake.responseEntered.await(2,TimeUnit.SECONDS));
                if(action.equals("logout"))rig.gateway.logoutReviewed(id,version,new GatewayCall(5000));else if(action.equals("disable")){rig.gateway.setEnabledReviewed(id,version,false);rig.gateway.setEnabled(id,true);}else rig.begin(id);
                rig.fake.allowResponse.countDown();assertNotEquals(action,"unexpected",result.get(2,TimeUnit.SECONDS));assertEquals(0,completed.get());assertFalse(rig.gateway.authorizationCurrent(id,version));assertEquals(1,rig.fake.responses.get());
            }finally{rig.fake.allowResponse.countDown();worker.shutdownNow();}
        }
    }
    @Test public void oldStoreMigrationDoesNotInventContinuityBeforeItsCurrentVersion()throws Exception {
        try(AccountGatewayTest.Rig rig=new AccountGatewayTest.Rig()){
            String id=rig.connect();long before=rig.book.row(id).getLong("version");rig.gateway.refresh(id,new GatewayCall(5000));long current=rig.book.row(id).getLong("version");rig.gateway.close();
            JSONObject disk=new JSONObject(java.nio.file.Files.readString(rig.file.toPath()));disk.getJSONObject("accounts").getJSONObject(id).remove("authorizationEpoch");AccountBook.atomicWrite(rig.file,disk);
            AccountBook restored=new AccountBook(rig.file,rig.vault,"host-owner");assertFalse(restored.authorizationCurrent(id,before));assertTrue(restored.authorizationCurrent(id,current));
        }
    }
    @Test public void closingGatewayCompletesQueuedCatalogWithoutWaitingForCallerDeadline()throws Exception {
        try(AccountGatewayTest.Rig rig=new AccountGatewayTest.Rig()){
            String[] ids={rig.connect(),rig.connect(),rig.connect()};rig.fake.allowModels=new CountDownLatch(1);var workers=Executors.newFixedThreadPool(3);
            try{
                java.util.List<java.util.concurrent.Future<String>> results=new java.util.ArrayList<>();
                for(String id:ids){long version=rig.book.row(id).getLong("version");results.add(workers.submit(()->{try{rig.gateway.prepareForInference(id,version,"fixture-model",new GatewayCall(60_000));return "unexpected";}catch(GatewayFailure failure){return failure.code;}}));}
                long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);while((rig.fake.models.get()<2||rig.gateway.catalogWaiterCount(ids[2])<1)&&System.nanoTime()<until)Thread.sleep(5);
                assertEquals(2,rig.fake.models.get());for(String id:ids)assertEquals(1,rig.gateway.catalogWaiterCount(id));
                rig.gateway.close();for(var result:results)assertNotEquals("unexpected",result.get(1,TimeUnit.SECONDS));assertEquals(2,rig.fake.models.get());assertEquals(0,rig.fake.responses.get());
            }finally{rig.fake.allowModels.countDown();workers.shutdownNow();}
        }
    }
    @Test public void rejectedCatalogSubmissionSettlesItsWaiterAndLeavesNoPendingFlight()throws Exception {
        try(AccountGatewayTest.Rig rig=new AccountGatewayTest.Rig()){
            String id=rig.connect();long version=rig.book.row(id).getLong("version");
            java.lang.reflect.Field field=AccountGateway.class.getDeclaredField("catalogWorkers");field.setAccessible(true);var executor=(java.util.concurrent.ThreadPoolExecutor)field.get(rig.gateway);
            CountDownLatch release=new CountDownLatch(1);Runnable held=()->{try{release.await(3,TimeUnit.SECONDS);}catch(InterruptedException stopped){Thread.currentThread().interrupt();}};
            try{
                executor.execute(held);executor.execute(held);for(int n=0;n<16;n++)executor.execute(held);
                assertEquals("GATEWAY_BUSY",assertThrows(GatewayFailure.class,()->rig.gateway.prepareForInference(id,version,"fixture-model",new GatewayCall(5000))).code);
                assertEquals(0,rig.gateway.catalogWaiterCount(id));assertEquals(0,rig.fake.models.get());assertEquals(0,rig.fake.responses.get());
            }finally{release.countDown();}
        }
    }
}
