package dev.pocketengine.features.proxy.accounts;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class ChatGptLoginLifetimeTest {
    @Test public void originalLoginTtlAlsoEndsAnAlreadyStartedCodeExchange()throws Exception {
        try(AccountGatewayTest.Rig rig=new AccountGatewayTest.Rig()){
            rig.fake.allowExchange=new CountDownLatch(1);JSONObject login=rig.gateway.beginLogin("TTL fixture","",800);
            var parameters=AccountGatewayTest.parameters(login);rig.fake.nonce=parameters.get("nonce");var worker=Executors.newSingleThreadExecutor();
            try{
                var callback=worker.submit(()->{try{AccountGatewayTest.callback(login,parameters.get("state"),"oaiapp_fixture");}catch(Exception ignored){}});
                assertTrue(rig.fake.exchangeEntered.await(2,TimeUnit.SECONDS));Thread.sleep(950);
                assertEquals("expired",rig.gateway.snapshot().getJSONObject("login").getString("status"));
                assertEquals("LOGIN_EXPIRED",rig.gateway.snapshot().getJSONObject("login").getString("error"));
                rig.fake.allowExchange.countDown();callback.get(2,TimeUnit.SECONDS);assertTrue(rig.vault.values.isEmpty());
            }finally{rig.fake.allowExchange.countDown();worker.shutdownNow();}
        }
    }
    @Test public void cancellingARefreshKeepsTheSignedInIdentityAndDoesNotForceOAuth()throws Exception {
        try(AccountGatewayTest.Rig rig=new AccountGatewayTest.Rig()){
            String id=rig.connect();rig.fake.allowRefresh=new CountDownLatch(1);GatewayCall operation=new GatewayCall(5000);var worker=Executors.newSingleThreadExecutor();
            try{
                var pending=worker.submit(()->{try{rig.gateway.refresh(id,operation);return "unexpected";}catch(GatewayFailure failure){return failure.code;}});
                assertTrue(rig.fake.refreshEntered.await(2,TimeUnit.SECONDS));operation.cancel();assertEquals("CANCELLED",pending.get(2,TimeUnit.SECONDS));
                assertEquals("connected",rig.book.row(id).getString("status"));assertEquals("fixture-subject",rig.book.row(id).getString("subject"));assertEquals(1,rig.vault.values.size());
            }finally{rig.fake.allowRefresh.countDown();worker.shutdownNow();}
        }
    }
    @Test public void firstSuccessfulPlanSignInHasAPersistentWelcomeRequirement()throws Exception {
        try(AccountGatewayTest.Rig rig=new AccountGatewayTest.Rig()){
            rig.connect();assertTrue(rig.gateway.snapshot().optBoolean("planWelcomeRequired"));
        }
    }
    @Test public void welcomeAcknowledgementPersistsAcrossApplicationRecreationAndReauthorization()throws Exception {
        try(AccountGatewayTest.Rig rig=new AccountGatewayTest.Rig()){
            String id=rig.connect();long version=rig.book.row(id).getLong("version");rig.gateway.acknowledgePlanWelcome(id,version);
            assertFalse(rig.gateway.snapshot().optBoolean("planWelcomeRequired"));AccountBook restored=new AccountBook(rig.file,rig.vault,"host-owner");
            assertFalse(restored.snapshot().optBoolean("planWelcomeRequired"));assertEquals(version,restored.row(id).getLong("version"));
        }
    }
    @Test public void deniedLoginRetainsRegistrationWithoutSendingAnExchange()throws Exception {
        try(AccountGatewayTest.Rig rig=new AccountGatewayTest.Rig()){
            JSONObject login=rig.begin("");java.net.URI uri=new java.net.URI(AccountGatewayTest.parameters(login).get("redirect_uri"));
            try(java.net.Socket socket=new java.net.Socket("127.0.0.1",uri.getPort())){
                String path="/auth/callback?state="+AccountGatewayTest.parameters(login).get("state")+"&error=access_denied";
                socket.getOutputStream().write(("GET "+path+" HTTP/1.1\r\nHost: 127.0.0.1:"+uri.getPort()+"\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));socket.getInputStream().readAllBytes();
            }
            assertEquals("AUTHORIZATION_DECLINED",rig.gateway.snapshot().getJSONObject("login").getString("error"));assertEquals(0,rig.fake.exchanges.get());assertTrue(rig.vault.values.isEmpty());
        }
    }
    @Test public void confirmedTerminalRefreshClearsTokensButRetainsVerifiedIdentityAndClientRegistration()throws Exception {
        try(AccountGatewayTest.Rig rig=new AccountGatewayTest.Rig()){
            String id=rig.connect();rig.fake.tokenStatus=400;rig.fake.tokenErrorBody="{\"error\":\"refresh_token_reused\"}";
            try{rig.gateway.refresh(id,new GatewayCall(5000));fail();}catch(GatewayFailure expected){assertEquals("REAUTHORIZATION_REQUIRED",expected.code);}
            assertTrue(rig.vault.values.isEmpty());JSONObject row=rig.book.row(id);assertEquals("fixture-subject",row.getString("subject"));assertEquals("oaiapp_fixture",row.getString("clientId"));assertEquals("reauthorization_required",row.getString("status"));
        }
    }

}
