package dev.pocketengine.features.proxy.accounts;

import dev.pocketengine.features.proxy.providers.FakeChatGpt;
import java.io.File;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class AccountGatewayTest {
    static final class Vault implements CredentialVault {
        final Map<String,String> values=new ConcurrentHashMap<>();boolean failPut,failRemove;
        public String get(String slot){return values.getOrDefault(slot,"");}
        public void put(String slot,String value)throws Exception{if(failPut)throw new Exception("SECRET_MUST_NOT_ESCAPE");values.put(slot,value);}
        public void remove(String slot)throws Exception{if(failRemove)throw new Exception("SECRET_MUST_NOT_ESCAPE");values.remove(slot);}
    }
    static final class Rig implements AutoCloseable {
        final FakeChatGpt fake=new FakeChatGpt();final Vault vault=new Vault();final File file=Files.createTempDirectory("account-gateway-").resolve("accounts.json").toFile();
        final AccountBook book=new AccountBook(file,vault,"host-owner");final AccountGateway gateway=new AccountGateway(book,fake.provider());
        Rig()throws Exception{}
        JSONObject begin(String id)throws Exception{JSONObject started=gateway.beginLogin("My account",id);Map<String,String> params=parameters(started);fake.nonce=params.get("nonce");return started;}
        String connect()throws Exception{JSONObject started=begin("");assertTrue(callback(started,parameters(started).get("state"),"oaiapp_fixture").startsWith("HTTP/1.1 200"));return started.getString("accountId");}
        public void close(){gateway.close();fake.close();}
    }
    @Test public void officialDynamicRegistrationAndNativeTextRequestWorkEndToEndWithFixtures()throws Exception {
        try(Rig rig=new Rig()){
            JSONObject started=rig.begin("");Map<String,String> params=parameters(started);assertEquals("dynamic_agent_client",params.get("client_id"));assertEquals("Pocket World",params.get("agent_name_hint"));assertEquals("S256",params.get("code_challenge_method"));assertEquals("https://api.openai.com/v1",params.get("resource"));assertTrue(params.get("ext_agent_host_id").startsWith("urn:uuid:"));
            assertTrue(callback(started,params.get("state"),"oaiapp_fixture").startsWith("HTTP/1.1 200"));String id=started.getString("accountId");
            assertEquals("oaiapp_fixture",rig.fake.lastTokenForm.get("client_id"));assertEquals(params.get("redirect_uri"),rig.fake.lastTokenForm.get("redirect_uri"));assertNotNull(rig.fake.lastTokenForm.get("code_verifier"));
            JSONObject snapshot=rig.gateway.snapshot();assertEquals("connected",snapshot.getJSONArray("accounts").getJSONObject(0).getString("status"));assertEquals("complete",snapshot.getJSONObject("login").getString("status"));assertTrue(snapshot.getJSONArray("accounts").getJSONObject(0).getBoolean("planUsageEnabled"));
            String disk=new String(Files.readAllBytes(rig.file.toPath()),StandardCharsets.UTF_8);for(String hidden:new String[]{FakeChatGpt.access(0),FakeChatGpt.refresh(0),"id_token","code_verifier",params.get("state"),params.get("nonce")})assertFalse(disk.contains(hidden));assertFalse(snapshot.toString().contains("slot"));
            assertEquals(1,rig.gateway.models(id,new GatewayCall(5000)).length());JSONArray events=new JSONArray();rig.gateway.streamText(id,"fixture-model","Be concise",new JSONArray().put(new JSONObject().put("role","user").put("content","Hello")),new GatewayCall(5000),events::put);
            assertEquals("你好🙂",events.getJSONObject(0).getString("delta"));assertEquals("completed",events.getJSONObject(1).getString("type"));assertEquals("Bearer "+FakeChatGpt.access(0),rig.fake.lastAuthorization);assertFalse(rig.fake.lastRequest.getBoolean("store"));assertTrue(rig.fake.lastRequest.getBoolean("stream"));assertFalse(rig.fake.lastRequest.has("messages"));assertEquals(1,rig.fake.responses.get());
        }
    }
    @Test public void wrongStateDoesNotConsumeValidAttemptAndSingleExchangeOnly()throws Exception {
        try(Rig rig=new Rig()){JSONObject start=rig.begin("");assertTrue(callback(start,"wrong","oaiapp_fixture").startsWith("HTTP/1.1 400"));assertEquals(0,rig.fake.exchanges.get());assertTrue(callback(start,parameters(start).get("state"),"oaiapp_fixture").startsWith("HTTP/1.1 200"));try{callback(start,parameters(start).get("state"),"oaiapp_fixture");}catch(Exception closed){}assertEquals(1,rig.fake.exchanges.get());}
    }
    @Test public void cancelAndExpiryNeverExchange()throws Exception {
        try(Rig rig=new Rig()){JSONObject start=rig.begin("");rig.gateway.cancelLogin(start.getString("sessionId"));assertEquals("cancelled",rig.gateway.snapshot().getJSONObject("login").getString("status"));assertEquals(0,rig.fake.exchanges.get());
            JSONObject shortLogin=rig.gateway.beginLogin("Expire","",50);awaitStatus(rig.gateway,"expired");assertEquals(0,rig.fake.exchanges.get());assertEquals("expired",rig.gateway.snapshot().getJSONObject("login").getString("status"));}
    }
    @Test public void cancellationDuringExchangeFencesLateReply()throws Exception {
        try(Rig rig=new Rig()){rig.fake.allowExchange=new CountDownLatch(1);JSONObject start=rig.begin("");ExecutorService worker=Executors.newSingleThreadExecutor();
            try{Future<?> callback=worker.submit(()->{try{callback(start,parameters(start).get("state"),"oaiapp_fixture");}catch(Exception ignored){}});assertTrue(rig.fake.exchangeEntered.await(3,TimeUnit.SECONDS));rig.gateway.cancelLogin(start.getString("sessionId"));rig.fake.allowExchange.countDown();callback.get(3,TimeUnit.SECONDS);assertEquals("cancelled",rig.gateway.snapshot().getJSONObject("login").getString("status"));assertTrue(rig.vault.values.isEmpty());}finally{worker.shutdownNow();}}
    }
    @Test public void retainedRegistrationSurvivesFailedExchangeAndWrongReauthorizationClientIsRejected()throws Exception {
        try(Rig rig=new Rig()){rig.fake.tokenStatus=400;JSONObject start=rig.begin("");callback(start,parameters(start).get("state"),"oaiapp_fixture");assertEquals("oaiapp_fixture",rig.book.row(start.getString("accountId")).getString("clientId"));
            JSONObject retry=rig.begin(start.getString("accountId"));assertEquals("oaiapp_fixture",parameters(retry).get("client_id"));assertFalse(parameters(retry).containsKey("agent_name_hint"));callback(retry,parameters(retry).get("state"),"oaiapp_wrong");assertEquals(1,rig.fake.exchanges.get());assertTrue(rig.vault.values.isEmpty());}
    }
    @Test public void reauthorizationMustKeepTheVerifiedIdentity()throws Exception {
        try(Rig rig=new Rig()){String id=rig.connect();String slot=rig.book.row(id).getString("slot"),before=rig.vault.get(slot);rig.fake.subject="different-subject";JSONObject retry=rig.begin(id);callback(retry,parameters(retry).get("state"),"oaiapp_fixture");assertEquals("ACCOUNT_IDENTITY_MISMATCH",rig.gateway.snapshot().getJSONObject("login").getString("error"));assertEquals(before,rig.vault.get(slot));assertEquals("fixture-subject",rig.book.row(id).getString("subject"));}
    }
    @Test public void concurrentRefreshIsSingleflightAndAtomicallyRotatesOneCredentialBundle()throws Exception {
        try(Rig rig=new Rig()){String id=rig.connect();String old=rig.book.row(id).getString("slot");rig.fake.allowRefresh=new CountDownLatch(1);ExecutorService workers=Executors.newFixedThreadPool(2);
            try{Future<?> a=workers.submit(()->{try{rig.gateway.refresh(id,new GatewayCall(5000));}catch(Exception error){throw new RuntimeException(error);}});assertTrue(rig.fake.refreshEntered.await(2,TimeUnit.SECONDS));CountDownLatch second=new CountDownLatch(1);Future<?> b=workers.submit(()->{try{second.countDown();rig.gateway.refresh(id,new GatewayCall(5000));}catch(Exception error){throw new RuntimeException(error);}});second.await();Thread.sleep(80);rig.fake.allowRefresh.countDown();a.get(3,TimeUnit.SECONDS);b.get(3,TimeUnit.SECONDS);
                assertEquals(1,rig.fake.refreshes.get());JSONObject row=rig.book.row(id);assertNotEquals(old,row.getString("slot"));assertFalse(rig.vault.values.containsKey(old));assertEquals(FakeChatGpt.refresh(1),rig.book.credentials(id,row.getLong("version")).refreshToken());}
            finally{workers.shutdownNow();}}
    }
    @Test public void logoutDuringRefreshCannotResurrectAccount()throws Exception {
        try(Rig rig=new Rig()){String id=rig.connect();rig.fake.allowRefresh=new CountDownLatch(1);ExecutorService worker=Executors.newSingleThreadExecutor();
            try{Future<String> result=worker.submit(()->{try{rig.gateway.refresh(id,new GatewayCall(5000));return "unexpected";}catch(GatewayFailure failure){return failure.code;}});assertTrue(rig.fake.refreshEntered.await(2,TimeUnit.SECONDS));JSONObject logout=rig.gateway.logout(id,new GatewayCall(3000));rig.fake.allowRefresh.countDown();assertNotEquals("unexpected",result.get(3,TimeUnit.SECONDS));assertTrue(logout.getBoolean("signedOutLocally"));assertTrue(logout.getBoolean("remoteRevocationConfirmed"));assertEquals("signed_out",rig.book.row(id).getString("status"));assertTrue(rig.vault.values.isEmpty());assertEquals(1,rig.fake.refreshes.get());}
            finally{worker.shutdownNow();}}
    }
    @Test public void disableCancelsRefreshAndEnableDoesNotResurrectIt()throws Exception {
        try(Rig rig=new Rig()){String id=rig.connect();rig.fake.allowRefresh=new CountDownLatch(1);ExecutorService worker=Executors.newSingleThreadExecutor();try{Future<?> request=worker.submit(()->{try{rig.gateway.refresh(id,new GatewayCall(5000));fail();}catch(GatewayFailure expected){}});assertTrue(rig.fake.refreshEntered.await(2,TimeUnit.SECONDS));rig.gateway.setEnabled(id,false);rig.gateway.setEnabled(id,true);rig.fake.allowRefresh.countDown();request.get(3,TimeUnit.SECONDS);assertEquals("reauthorization_required",rig.book.row(id).getString("status"));}finally{worker.shutdownNow();}}
    }
    @Test public void refreshFailureHasCooldownAndDoesNotLeakOrRetry()throws Exception {
        try(Rig rig=new Rig()){String id=rig.connect();rig.fake.tokenStatus=503;try{rig.gateway.refresh(id,new GatewayCall(5000));fail();}catch(GatewayFailure failure){assertEquals("PROVIDER_UNAVAILABLE",failure.code);assertFalse(failure.toString().contains("secret"));}try{rig.gateway.models(id,new GatewayCall(5000));fail();}catch(GatewayFailure failure){assertEquals("ACCOUNT_COOLDOWN",failure.code);}assertEquals(1,rig.fake.refreshes.get());}
    }
    @Test public void absentPlanGrantNeverCallsInferenceAndAccountSelectionIsManual()throws Exception {
        try(Rig rig=new Rig()){rig.fake.scope="openid profile email offline_access";String id=rig.connect();try{rig.gateway.models(id,new GatewayCall(5000));fail();}catch(GatewayFailure failure){assertEquals("PLAN_USAGE_PERMISSION_REQUIRED",failure.code);}assertEquals(0,rig.fake.responses.get());String second=rig.connect();assertEquals(id,rig.book.selected());rig.gateway.select(second);assertEquals(second,rig.book.selected());rig.gateway.setEnabled(second,false);try{rig.gateway.models("",new GatewayCall(5000));fail();}catch(GatewayFailure failure){assertEquals("ACCOUNT_DISABLED",failure.code);}}
    }
    @Test public void restoringMetadataNeverResumesAnInterruptedAuthorizationOrRefresh()throws Exception {
        try(Rig rig=new Rig()){JSONObject started=rig.begin("");String host=rig.book.hostId();rig.gateway.close();AccountBook restored=new AccountBook(rig.file,rig.vault,"host-owner");assertEquals(host,restored.hostId());assertEquals("interrupted",restored.snapshot().getJSONObject("login").getString("status"));assertEquals(0,rig.fake.exchanges.get());try{new AccountBook(rig.file,rig.vault,"other-owner");fail();}catch(GatewayFailure failure){assertEquals("ACCOUNT_STORE_INVALID",failure.code);}}
    }
    @Test public void storageFailureRollsBackMetadataAndKeepsOldTokens()throws Exception {
        try(Rig rig=new Rig()){String id=rig.connect();JSONObject old=rig.book.row(id);String slot=old.getString("slot"),value=rig.vault.get(slot);AtomicBoolean fail=new AtomicBoolean(false);
            AccountBook faulted=new AccountBook(rig.file,rig.vault,"host-owner",(file,data)->{if(fail.get())throw new Exception("fixture-secret");AccountBook.atomicWrite(file,data);});fail.set(true);
            try{faulted.publish(id,old.getLong("version"),new Credentials("new-access","new-refresh","new-id","chatgpt.tokens.use.direct",System.currentTimeMillis()+10000),old.getString("issuer"),old.getString("subject"),"a@b.test");fail();}catch(GatewayFailure expected){assertEquals("ACCOUNT_STORE_UNAVAILABLE",expected.code);}
            assertEquals(old.toString(),faulted.row(id).toString());assertEquals(value,rig.vault.get(slot));assertEquals(1,rig.vault.values.size());}
    }
    @Test public void vaultFailureDoesNotPublishAndIncompleteLogoutCleanupIsVisible()throws Exception {
        try(Rig rig=new Rig()){String id=rig.connect();JSONObject old=rig.book.row(id);rig.vault.failPut=true;try{rig.book.publish(id,old.getLong("version"),new Credentials("new","refresh","id","openid",1),old.getString("issuer"),old.getString("subject"),"");fail();}catch(GatewayFailure failure){assertEquals("CREDENTIAL_COMMIT_FAILED",failure.code);}assertEquals(old.toString(),rig.book.row(id).toString());rig.vault.failPut=false;rig.vault.failRemove=true;rig.fake.revokeStatus=503;JSONObject logout=rig.gateway.logout(id,new GatewayCall(5000));assertTrue(logout.getBoolean("credentialCleanupRequired"));assertFalse(logout.getBoolean("remoteRevocationConfirmed"));assertEquals("",rig.book.row(id).getString("slot"));}
    }
    @Test public void cancellingARefreshWaiterDoesNotCancelItsLeader()throws Exception {
        try(Rig rig=new Rig()){String id=rig.connect();rig.fake.allowRefresh=new CountDownLatch(1);ExecutorService workers=Executors.newFixedThreadPool(2);GatewayCall waiter=new GatewayCall(5000);
            try{Future<?> leader=workers.submit(()->{try{rig.gateway.refresh(id,new GatewayCall(5000));}catch(Exception error){throw new RuntimeException(error);}});assertTrue(rig.fake.refreshEntered.await(2,TimeUnit.SECONDS));
                Future<String> second=workers.submit(()->{try{rig.gateway.models(id,waiter);return "unexpected";}catch(GatewayFailure error){return error.code;}});Thread.sleep(80);waiter.cancel();assertEquals("CANCELLED",second.get(2,TimeUnit.SECONDS));assertFalse(leader.isDone());rig.fake.allowRefresh.countDown();leader.get(2,TimeUnit.SECONDS);assertEquals(1,rig.fake.refreshes.get());assertEquals("connected",rig.book.row(id).getString("status"));
            }finally{workers.shutdownNow();}}
    }
    @Test public void connectedDataAndHostIdRestoreWithoutImplicitNetworkOrAuthorization()throws Exception {
        try(Rig rig=new Rig()){String id=rig.connect(),host=rig.book.hostId();rig.gateway.close();AccountBook restored=new AccountBook(rig.file,rig.vault,"host-owner");try(AccountGateway next=new AccountGateway(restored,rig.fake.provider())){assertEquals(host,restored.hostId());assertEquals("connected",restored.row(id).getString("status"));assertEquals(1,rig.fake.exchanges.get());assertEquals(0,rig.fake.refreshes.get());assertEquals(1,next.models(id,new GatewayCall(5000)).length());assertEquals(0,rig.fake.refreshes.get());}}
    }
    @Test public void crashedRefreshMarkerRequiresReauthorizationInsteadOfReusingTheOldRefreshToken()throws Exception {
        try(Rig rig=new Rig()){String id=rig.connect();JSONObject row=rig.book.row(id);rig.book.update(id,row.getLong("version"),AccountBook.json("status","refreshing"));
            try{rig.gateway.refresh(id,new GatewayCall(5000));fail();}catch(GatewayFailure error){assertEquals("REAUTHORIZATION_REQUIRED",error.code);}assertEquals(0,rig.fake.refreshes.get());rig.gateway.close();AccountBook restored=new AccountBook(rig.file,rig.vault,"host-owner");assertEquals("reauthorization_required",restored.row(id).getString("status"));}
    }
    @Test public void oneLoginAndTwoNetworkCallsAreHardAdmissionLimits()throws Exception {
        try(Rig rig=new Rig()){JSONObject pending=rig.begin("");try{rig.gateway.beginLogin("Another","");fail();}catch(GatewayFailure error){assertEquals("LOGIN_ALREADY_PENDING",error.code);}assertEquals(1,rig.gateway.snapshot().getJSONArray("accounts").length());rig.gateway.cancelLogin(pending.getString("sessionId"));String id=rig.connect();rig.fake.allowResponse=new CountDownLatch(1);ExecutorService workers=Executors.newFixedThreadPool(2);GatewayCall a=new GatewayCall(5000),b=new GatewayCall(5000);
            try{Future<?> first=workers.submit(()->{streamUntilCancelled(rig,id,a);return null;});Future<?> second=workers.submit(()->{streamUntilCancelled(rig,id,b);return null;});long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);while(rig.fake.responses.get()<2&&System.nanoTime()<until)Thread.sleep(10);assertEquals(2,rig.fake.responses.get());
                try{rig.gateway.models(id,new GatewayCall(5000));fail();}catch(GatewayFailure error){assertEquals("GATEWAY_BUSY",error.code);}a.cancel();b.cancel();first.get(2,TimeUnit.SECONDS);second.get(2,TimeUnit.SECONDS);assertEquals(2,rig.fake.responses.get());
            }finally{rig.fake.allowResponse.countDown();workers.shutdownNow();}}
    }
    @Test public void reviewedActionsCannotAffectAReauthorizedAccountFromAnOldSnapshot()throws Exception {
        try(Rig rig=new Rig()){
            String id=rig.connect();long old=rig.book.row(id).getLong("version");JSONObject login=rig.begin(id);
            assertTrue(callback(login,parameters(login).get("state"),"oaiapp_fixture").startsWith("HTTP/1.1 200"));
            String before=new String(Files.readAllBytes(rig.file.toPath()),StandardCharsets.UTF_8);Map<String,String> credentials=new HashMap<>(rig.vault.values);
            stale(()->rig.gateway.beginLoginReviewed("Old review",id,old));
            stale(()->rig.gateway.selectReviewed(id,old));stale(()->rig.gateway.setEnabledReviewed(id,old,false));
            stale(()->rig.gateway.logoutReviewed(id,old,new GatewayCall(5000)));
            stale(()->rig.gateway.refreshReviewed(id,old,new GatewayCall(5000)));
            stale(()->rig.gateway.modelsReviewed(id,old,new GatewayCall(5000)));
            assertEquals(before,new String(Files.readAllBytes(rig.file.toPath()),StandardCharsets.UTF_8));assertEquals(credentials,rig.vault.values);
            assertEquals(2,rig.fake.exchanges.get());assertEquals(0,rig.fake.refreshes.get());assertEquals(0,rig.fake.revocations.get());assertEquals(0,rig.fake.models.get());
        }
    }
    @Test public void matchingReviewedActionsKeepLegacyPayloadsAndCatalogCarriesItsOwnIdentity()throws Exception {
        try(Rig rig=new Rig()){
            JSONObject login=rig.gateway.beginLoginReviewed("Native review","",0);rig.fake.nonce=parameters(login).get("nonce");
            assertTrue(callback(login,parameters(login).get("state"),"oaiapp_fixture").startsWith("HTTP/1.1 200"));String id=login.getString("accountId");
            String other=rig.connect();rig.gateway.select(other);long version=rig.book.row(id).getLong("version");
            JSONObject catalog=rig.gateway.modelsReviewed(id,version,new GatewayCall(5000));
            assertEquals(id,catalog.getString("accountId"));assertEquals(version,catalog.getLong("version"));
            assertEquals("fixture-model",catalog.getJSONArray("models").getJSONObject(0).getString("id"));
            assertEquals("Fixture model",catalog.getJSONArray("models").getJSONObject(0).getString("displayName"));assertEquals(other,rig.book.selected());
            assertEquals(id,rig.gateway.selectReviewed(id,version).getString("selectedAccountId"));
            rig.gateway.setEnabledReviewed(id,version,false);assertFalse(rig.book.row(id).getBoolean("enabled"));
            rig.gateway.setEnabledReviewed(id,rig.book.row(id).getLong("version"),true);
            assertTrue(rig.gateway.refreshReviewed(id,rig.book.row(id).getLong("version"),new GatewayCall(5000)).has("accounts"));
            JSONObject logout=rig.gateway.logoutReviewed(id,rig.book.row(id).getLong("version"),new GatewayCall(5000));
            assertTrue(logout.getBoolean("signedOutLocally"));assertEquals("signed_out",rig.book.row(id).getString("status"));
            assertEquals(rig.book.row(id).getLong("version"),logout.getLong("accountVersion"));
        }
    }
    @Test public void reviewedCatalogAfterAutomaticRefreshUsesRefreshedRequestVersion()throws Exception {
        try(Rig rig=new Rig()){
            rig.fake.expiresIn=1;String id=rig.connect();long reviewed=rig.book.row(id).getLong("version");rig.fake.expiresIn=3600;
            JSONObject catalog=rig.gateway.modelsReviewed(id,reviewed,new GatewayCall(5000));
            assertEquals(1,rig.fake.refreshes.get());assertEquals(1,rig.fake.models.get());
            assertEquals(rig.book.row(id).getLong("version"),catalog.getLong("version"));assertEquals(reviewed+2,catalog.getLong("version"));
        }
    }
    @Test public void reviewedCatalogNeverPublishesOldModelsUnderANewAuthorizationVersion()throws Exception {
        try(Rig rig=new Rig()){
            String id=rig.connect();long reviewed=rig.book.row(id).getLong("version");rig.fake.allowModels=new CountDownLatch(1);ExecutorService worker=Executors.newSingleThreadExecutor();
            try{
                Future<String> result=worker.submit(()->{try{rig.gateway.modelsReviewed(id,reviewed,new GatewayCall(5000));return "unexpected_catalog";}catch(GatewayFailure error){return error.code;}});
                assertTrue(rig.fake.modelsEntered.await(2,TimeUnit.SECONDS));
                // Must acquire the gateway lock while model I/O is still blocked.
                JSONObject next=rig.gateway.beginLoginReviewed("Reauthorize",id,reviewed);rig.fake.nonce=parameters(next).get("nonce");
                assertTrue(callback(next,parameters(next).get("state"),"oaiapp_fixture").startsWith("HTTP/1.1 200"));
                long current=rig.book.row(id).getLong("version");rig.fake.allowModels.countDown();
                assertEquals("STALE_ACCOUNT_VERSION",result.get(3,TimeUnit.SECONDS));assertTrue(current>reviewed);
                assertEquals(current,rig.book.row(id).getLong("version"));assertEquals("connected",rig.book.row(id).getString("status"));
            }finally{rig.fake.allowModels.countDown();worker.shutdownNow();}
        }
    }
    @Test public void cancellingReviewedCatalogReturnsNoCatalogAndLeavesAccountUntouched()throws Exception {
        try(Rig rig=new Rig()){
            String id=rig.connect();long version=rig.book.row(id).getLong("version");rig.fake.allowModels=new CountDownLatch(1);GatewayCall call=new GatewayCall(5000);ExecutorService worker=Executors.newSingleThreadExecutor();
            try{
                Future<String> result=worker.submit(()->{try{rig.gateway.modelsReviewed(id,version,call);return "unexpected_catalog";}catch(GatewayFailure error){return error.code;}});
                assertTrue(rig.fake.modelsEntered.await(2,TimeUnit.SECONDS));call.cancel();rig.fake.allowModels.countDown();
                assertEquals("CANCELLED",result.get(3,TimeUnit.SECONDS));assertEquals(version,rig.book.row(id).getLong("version"));assertEquals("connected",rig.book.row(id).getString("status"));
            }finally{rig.fake.allowModels.countDown();worker.shutdownNow();}
        }
    }
    @Test public void reviewedRefreshWaiterCancellationPreservesSingleflightLeader()throws Exception {
        try(Rig rig=new Rig()){
            String id=rig.connect();long version=rig.book.row(id).getLong("version");rig.fake.allowRefresh=new CountDownLatch(1);GatewayCall waiter=new GatewayCall(5000);ExecutorService workers=Executors.newFixedThreadPool(2);
            try{
                Future<JSONObject> leader=workers.submit(()->rig.gateway.refreshReviewed(id,version,new GatewayCall(5000)));
                assertTrue(rig.fake.refreshEntered.await(2,TimeUnit.SECONDS));long refreshing=rig.book.row(id).getLong("version");
                Future<String> result=workers.submit(()->{try{rig.gateway.modelsReviewed(id,refreshing,waiter);return "unexpected_catalog";}catch(GatewayFailure error){return error.code;}});
                Thread.sleep(80);waiter.cancel();assertEquals("CANCELLED",result.get(2,TimeUnit.SECONDS));assertFalse(leader.isDone());
                rig.fake.allowRefresh.countDown();assertTrue(leader.get(3,TimeUnit.SECONDS).has("accounts"));assertEquals(1,rig.fake.refreshes.get());assertEquals(0,rig.fake.models.get());
            }finally{rig.fake.allowRefresh.countDown();workers.shutdownNow();}
        }
    }
    @Test public void reviewedRefreshAndCatalogWaiterShareTheSamePublishedVersion()throws Exception {
        try(Rig rig=new Rig()){
            String id=rig.connect();long version=rig.book.row(id).getLong("version");rig.fake.allowRefresh=new CountDownLatch(1);ExecutorService workers=Executors.newFixedThreadPool(2);
            try{
                Future<JSONObject> leader=workers.submit(()->rig.gateway.refreshReviewed(id,version,new GatewayCall(5000)));
                assertTrue(rig.fake.refreshEntered.await(2,TimeUnit.SECONDS));long refreshing=rig.book.row(id).getLong("version");
                Future<JSONObject> catalog=workers.submit(()->rig.gateway.modelsReviewed(id,refreshing,new GatewayCall(5000)));
                Thread.sleep(80);rig.fake.allowRefresh.countDown();leader.get(3,TimeUnit.SECONDS);
                assertEquals(version+2,catalog.get(3,TimeUnit.SECONDS).getLong("version"));assertEquals(1,rig.fake.refreshes.get());
            }finally{rig.fake.allowRefresh.countDown();workers.shutdownNow();}
        }
    }
    @Test public void queuedReviewedLogoutCannotSignOutANewlyStartedAuthorization()throws Exception {
        try(Rig rig=new Rig()){
            String id=rig.connect();long version=rig.book.row(id).getLong("version");ExecutorService worker=Executors.newSingleThreadExecutor();CountDownLatch queued=new CountDownLatch(1);
            try{
                Future<?> result;JSONObject login;
                synchronized(rig.gateway){
                    result=worker.submit(()->{queued.countDown();stale(()->rig.gateway.logoutReviewed(id,version,new GatewayCall(5000)));return null;});
                    assertTrue(queued.await(2,TimeUnit.SECONDS));login=rig.gateway.beginLoginReviewed("Reauthorize",id,version);
                }
                result.get(2,TimeUnit.SECONDS);assertEquals("pending",rig.gateway.snapshot().getJSONObject("login").getString("status"));assertEquals(0,rig.fake.revocations.get());
                rig.gateway.cancelLogin(login.getString("sessionId"));assertEquals("cancelled",rig.gateway.snapshot().getJSONObject("login").getString("status"));
            }finally{worker.shutdownNow();}
        }
    }
    @Test public void cancelledReviewedLogoutHasNoLocalOrNetworkEffectAndMissingIdentityNeverUsesSelected()throws Exception {
        try(Rig rig=new Rig()){
            String id=rig.connect();long version=rig.book.row(id).getLong("version");String before=new String(Files.readAllBytes(rig.file.toPath()),StandardCharsets.UTF_8);GatewayCall cancelled=new GatewayCall(5000);cancelled.cancel();
            try{rig.gateway.logoutReviewed(id,version,cancelled);fail();}catch(GatewayFailure error){assertEquals("CANCELLED",error.code);}
            try{rig.gateway.modelsReviewed("",version,new GatewayCall(5000));fail();}catch(GatewayFailure error){assertEquals("ACCOUNT_SELECTION_REQUIRED",error.code);}
            assertEquals(before,new String(Files.readAllBytes(rig.file.toPath()),StandardCharsets.UTF_8));assertEquals(0,rig.fake.revocations.get());assertEquals(0,rig.fake.models.get());
        }
    }
    interface ReviewedAction {void run()throws Exception;}
    static void stale(ReviewedAction action)throws Exception {try{action.run();fail("stale native action must fail closed");}catch(GatewayFailure error){assertEquals("STALE_ACCOUNT_VERSION",error.code);}}

    static void streamUntilCancelled(Rig rig,String id,GatewayCall call)throws Exception{try{rig.gateway.streamText(id,"fixture-model","",new JSONArray().put(new JSONObject().put("role","user").put("content","x")),call,event->{});fail();}catch(GatewayFailure expected){assertEquals("CANCELLED",expected.code);}}
    @Test public void abandonedUnregisteredAttemptsDoNotExhaustTheAccountLimit()throws Exception {
        try(Rig rig=new Rig()){for(int i=0;i<20;i++){JSONObject login=rig.begin("");rig.gateway.cancelLogin(login.getString("sessionId"));assertEquals(0,rig.gateway.snapshot().getJSONArray("accounts").length());}assertEquals(0,rig.fake.exchanges.get());String id=rig.connect();assertEquals("connected",rig.book.row(id).getString("status"));}
    }
    @Test public void streamedUsageFailurePausesPlanRequestsWithoutInventingResetOrSwitchingAccounts()throws Exception {
        try(Rig rig=new Rig()){String id=rig.connect();rig.fake.stream="data: {\"type\":\"response.output_text.delta\",\"delta\":\"partial\"}\n\ndata: {\"type\":\"response.failed\",\"response\":{\"error\":{\"code\":\"subscription_sharing_usage_limit_exceeded\",\"message\":\"fixture-secret\"}}}\n\n";
            JSONArray events=new JSONArray();try{rig.gateway.streamText(id,"fixture-model","",new JSONArray().put(new JSONObject().put("role","user").put("content","x")),new GatewayCall(5000),events::put);fail();}catch(GatewayFailure error){assertEquals("CHATGPT_USAGE_LIMIT_REACHED",error.code);assertEquals(0,error.retryAt);assertFalse(error.toString().contains("fixture-secret"));}
            assertFalse(events.toString().contains("completed"));assertEquals(1,rig.fake.responses.get());assertEquals("connected",rig.book.row(id).getString("status"));assertTrue(rig.book.row(id).getBoolean("usageLimited"));assertEquals(0,rig.book.row(id).getLong("cooldownUntil"));assertEquals(id,rig.book.selected());
        }
    }
    static Map<String,String> parameters(JSONObject start)throws Exception{return FakeChatGpt.parse(new java.net.URI(start.getString("authorizationUrl")).getRawQuery());}
    static String callback(JSONObject start,String state,String client)throws Exception {
        String uri=parameters(start).get("redirect_uri");java.net.URI address=new java.net.URI(uri);
        try(Socket socket=new Socket("127.0.0.1",address.getPort())){socket.setSoTimeout(6000);String query="/auth/callback?state="+state+"&code=fixture-authorization-code&client_id="+client;socket.getOutputStream().write(("GET "+query+" HTTP/1.1\r\nHost: 127.0.0.1:"+address.getPort()+"\r\n\r\n").getBytes(StandardCharsets.US_ASCII));socket.getOutputStream().flush();return new String(socket.getInputStream().readAllBytes(),StandardCharsets.UTF_8);}
    }
    static void awaitStatus(AccountGateway gateway,String status)throws Exception {long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(System.nanoTime()<deadline){if(status.equals(gateway.snapshot().getJSONObject("login").getString("status")))return;Thread.sleep(20);}fail("status not observed: "+status);}
}
