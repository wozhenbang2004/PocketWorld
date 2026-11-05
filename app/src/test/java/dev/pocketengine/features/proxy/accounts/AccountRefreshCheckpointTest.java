package dev.pocketengine.features.proxy.accounts;

import dev.pocketengine.features.proxy.providers.FakeChatGpt;
import dev.pocketengine.features.proxy.providers.ResponsesRequest;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;
import org.json.JSONArray;
import org.junit.Test;
import static org.junit.Assert.*;

/** Every token is synthetic. The local issuer consumes each refresh grant at most once. */
public final class AccountRefreshCheckpointTest {
    private void failAfterRotation(AccountGatewayTest.Rig rig,String id)throws Exception {
        rig.fake.enforceRefreshRotation=true;rig.fake.jwksStatus=503;
        assertEquals("PROVIDER_UNAVAILABLE",assertThrows(GatewayFailure.class,()->rig.gateway.refresh(id,new GatewayCall(5000))).code);
        assertEquals(1,rig.fake.refreshes.get());long version=rig.book.row(id).getLong("version");
        assertNotNull(rig.book.pendingRefresh(id,version));assertFalse(rig.gateway.inferenceIdentityCurrent(id,version));
        assertEquals(FakeChatGpt.refresh(0),rig.book.credentials(id,version).refreshToken());
        String disk=new String(Files.readAllBytes(rig.file.toPath()),StandardCharsets.UTF_8);assertFalse(disk.contains(FakeChatGpt.refresh(1)));assertFalse(disk.contains(FakeChatGpt.access(1)));
        assertEquals(0,rig.fake.responses.get());
    }
    private void awaitRetry(AccountBook book,String id)throws Exception {
        long until=book.row(id).optLong("cooldownUntil")+10;
        while(System.currentTimeMillis()<until)Thread.sleep(Math.min(50,until-System.currentTimeMillis()));
    }
    private void assertRecovered(AccountGatewayTest.Rig rig,AccountBook book,AccountGateway gateway,String id)throws Exception {
        rig.fake.jwksStatus=200;awaitRetry(book,id);gateway.refresh(id,new GatewayCall(5000));
        long version=book.row(id).getLong("version");assertNull(book.pendingRefresh(id,version));
        assertEquals("connected",book.row(id).getString("status"));assertTrue(gateway.inferenceIdentityCurrent(id,version));
        assertEquals(FakeChatGpt.refresh(1),book.credentials(id,version).refreshToken());
        assertEquals(1,rig.fake.refreshes.get());assertEquals(1,rig.vault.values.size());
    }
    @Test public void keyServiceRecoveryNeverReplaysAConsumedRefreshGrant()throws Exception {
        try(AccountGatewayTest.Rig rig=new AccountGatewayTest.Rig()){
            String id=rig.connect();failAfterRotation(rig,id);awaitRetry(rig.book,id);long version=rig.book.row(id).getLong("version");
            ResponsesRequest request=new ResponsesRequest("fixture-model","",new JSONArray().put(new JSONObject().put("role","user").put("content","hello")),new JSONArray());
            assertEquals("ACCOUNT_REFRESH_REQUIRED",assertThrows(GatewayFailure.class,()->rig.gateway.streamResponsesReviewed(id,version,request,new GatewayCall(5000),event->{})).code);
            assertEquals(0,rig.fake.responses.get());assertRecovered(rig,rig.book,rig.gateway,id);
        }
    }
    @Test public void pendingVerificationSurvivesRestartAndInterruptedRefreshMarker()throws Exception {
        try(AccountGatewayTest.Rig rig=new AccountGatewayTest.Rig()){
            String id=rig.connect();failAfterRotation(rig,id);
            rig.book.update(id,rig.book.row(id).getLong("version"),AccountBook.json("status","refreshing"));rig.gateway.close();
            AccountBook restored=new AccountBook(rig.file,rig.vault,"host-owner");
            assertEquals("connected",restored.row(id).getString("status"));assertFalse(restored.inferenceIdentityCurrent(id,restored.row(id).getLong("version")));
            try(AccountGateway gateway=new AccountGateway(restored,rig.fake.provider())){assertRecovered(rig,restored,gateway,id);}
        }
    }
    @Test public void wrongVerifiedSubjectInvalidatesBothBundlesInsteadOfReusingOldGrant()throws Exception {
        try(AccountGatewayTest.Rig rig=new AccountGatewayTest.Rig()){
            String id=rig.connect();rig.fake.enforceRefreshRotation=true;rig.fake.subject="different-subject";
            assertEquals("ACCOUNT_IDENTITY_MISMATCH",assertThrows(GatewayFailure.class,()->rig.gateway.refresh(id,new GatewayCall(5000))).code);
            assertEquals("reauthorization_required",rig.book.row(id).getString("status"));assertTrue(rig.vault.values.isEmpty());
            assertNull(rig.book.pendingRefresh(id,rig.book.row(id).getLong("version")));
            assertEquals("REAUTHORIZATION_REQUIRED",assertThrows(GatewayFailure.class,()->rig.gateway.refresh(id,new GatewayCall(5000))).code);assertEquals(1,rig.fake.refreshes.get());
        }
    }
    @Test public void logoutRevokesTheLatestPendingBundleAndRemovesBothSlots()throws Exception {
        try(AccountGatewayTest.Rig rig=new AccountGatewayTest.Rig()){
            String id=rig.connect();failAfterRotation(rig,id);
            assertTrue(rig.gateway.logout(id,new GatewayCall(5000)).getBoolean("signedOutLocally"));
            assertEquals(FakeChatGpt.refresh(1),rig.fake.lastRevokedToken);assertEquals(1,rig.fake.revocations.get());assertTrue(rig.vault.values.isEmpty());
            assertNull(rig.book.pendingRefresh(id,rig.book.row(id).getLong("version")));
        }
    }
    @Test public void disabledAccountCannotResumePendingVerification()throws Exception {
        try(AccountGatewayTest.Rig rig=new AccountGatewayTest.Rig()){
            String id=rig.connect();failAfterRotation(rig,id);long version=rig.book.row(id).getLong("version");rig.gateway.setEnabled(id,false);
            assertEquals("ACCOUNT_DISABLED",assertThrows(GatewayFailure.class,()->rig.gateway.refresh(id,new GatewayCall(5000))).code);
            assertFalse(rig.gateway.inferenceIdentityCurrent(id,version));assertEquals(1,rig.fake.refreshes.get());
            rig.gateway.setEnabled(id,true);assertRecovered(rig,rig.book,rig.gateway,id);
        }
    }
    @Test public void cancellationAfterCheckpointLeavesOnlyVerificationToResume()throws Exception {
        try(AccountGatewayTest.Rig rig=new AccountGatewayTest.Rig()){
            String id=rig.connect();rig.fake.enforceRefreshRotation=true;rig.fake.jwksEntered=new CountDownLatch(1);rig.fake.allowJwks=new CountDownLatch(1);
            GatewayCall call=new GatewayCall(5000);var worker=Executors.newSingleThreadExecutor();
            try{var pending=worker.submit(()->{try{rig.gateway.refresh(id,call);return "unexpected";}catch(GatewayFailure failure){return failure.code;}});
                assertTrue(rig.fake.jwksEntered.await(2,TimeUnit.SECONDS));call.cancel();rig.fake.allowJwks.countDown();assertEquals("CANCELLED",pending.get(2,TimeUnit.SECONDS));
                assertRecovered(rig,rig.book,rig.gateway,id);
            }finally{rig.fake.allowJwks.countDown();worker.shutdownNow();}
        }
    }
    @Test public void failedCheckpointWriteNeverRetriesTheNowConsumedOldGrant()throws Exception {
        try(AccountGatewayTest.Rig rig=new AccountGatewayTest.Rig()){
            String id=rig.connect();rig.gateway.close();rig.fake.enforceRefreshRotation=true;
            AccountBook faulted=new AccountBook(rig.file,rig.vault,"host-owner",(file,value)->{
                if(value.getJSONObject("accounts").getJSONObject(id).optJSONObject("pendingRefresh")!=null)throw new java.io.IOException("fixture write failure");AccountBook.atomicWrite(file,value);});
            try(AccountGateway gateway=new AccountGateway(faulted,rig.fake.provider())){
                assertEquals("REFRESH_CHECKPOINT_UNAVAILABLE",assertThrows(GatewayFailure.class,()->gateway.refresh(id,new GatewayCall(5000))).code);
                assertEquals("reauthorization_required",faulted.row(id).getString("status"));assertTrue(rig.vault.values.isEmpty());
                assertEquals("REAUTHORIZATION_REQUIRED",assertThrows(GatewayFailure.class,()->gateway.refresh(id,new GatewayCall(5000))).code);assertEquals(1,rig.fake.refreshes.get());
            }
        }
    }
    @Test public void failedFinalPublicationPreservesThePreviouslyWrittenCheckpoint()throws Exception {
        try(AccountGatewayTest.Rig rig=new AccountGatewayTest.Rig()){
            String id=rig.connect(),oldSlot=rig.book.row(id).getString("slot");rig.gateway.close();rig.fake.enforceRefreshRotation=true;AtomicBoolean fail=new AtomicBoolean(true);
            AccountBook faulted=new AccountBook(rig.file,rig.vault,"host-owner",(file,value)->{
                JSONObject row=value.getJSONObject("accounts").getJSONObject(id);
                if(fail.get()&&!oldSlot.equals(row.optString("slot"))&&row.optJSONObject("pendingRefresh")==null)throw new java.io.IOException("fixture publish failure");AccountBook.atomicWrite(file,value);});
            try(AccountGateway gateway=new AccountGateway(faulted,rig.fake.provider())){
                assertEquals("ACCOUNT_STORE_UNAVAILABLE",assertThrows(GatewayFailure.class,()->gateway.refresh(id,new GatewayCall(5000))).code);
                assertNotNull(faulted.pendingRefresh(id,faulted.row(id).getLong("version")));assertEquals(2,rig.vault.values.size());fail.set(false);
                assertRecovered(rig,faulted,gateway,id);
            }
        }
    }
    @Test public void expiredPendingAccessIsNeverUsedForCatalogOrInference()throws Exception {
        try(AccountGatewayTest.Rig rig=new AccountGatewayTest.Rig()){
            String id=rig.connect();failAfterRotation(rig,id);long version=rig.book.row(id).getLong("version");
            Credentials pending=rig.book.pendingRefresh(id,version).credentials();String slot=rig.book.row(id).getJSONObject("pendingRefresh").getString("slot");
            rig.vault.put(slot,new Credentials(pending.accessToken(),pending.refreshToken(),pending.idToken(),pending.scopes,System.currentTimeMillis()-1).encode());
            rig.fake.jwksStatus=200;awaitRetry(rig.book,id);
            assertEquals("ACCOUNT_REFRESH_REQUIRED",assertThrows(GatewayFailure.class,()->rig.gateway.models(id,new GatewayCall(5000))).code);
            assertEquals(0,rig.fake.models.get());assertEquals(0,rig.fake.responses.get());assertEquals(1,rig.fake.refreshes.get());
            assertNull(rig.book.pendingRefresh(id,rig.book.row(id).getLong("version")));
            rig.gateway.models(id,new GatewayCall(5000));assertEquals(2,rig.fake.refreshes.get());assertEquals(1,rig.fake.models.get());
            assertEquals("Bearer "+FakeChatGpt.access(2),rig.fake.lastAuthorization);
        }
    }
}
