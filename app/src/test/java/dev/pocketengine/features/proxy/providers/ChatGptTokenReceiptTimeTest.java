package dev.pocketengine.features.proxy.providers;

import dev.pocketengine.features.proxy.accounts.Credentials;
import dev.pocketengine.features.proxy.accounts.GatewayCall;
import dev.pocketengine.features.proxy.accounts.GatewayFailure;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Real loopback delay: a token may legitimately be issued after its request began. */
public final class ChatGptTokenReceiptTimeTest {
    private void delayedGrant(boolean refresh)throws Exception {
        try(FakeChatGpt fake=new FakeChatGpt();ChatGptProvider provider=fake.provider()){
            fake.nonce="receipt-time-nonce";CountDownLatch release=new CountDownLatch(1);
            if(refresh)fake.allowRefresh=release;else fake.allowExchange=release;
            var worker=Executors.newSingleThreadExecutor();
            try{
                var pending=worker.submit(()->{
                    long started=System.currentTimeMillis();GatewayCall call=new GatewayCall(20000);
                    if(!refresh)return provider.exchange(fake.clientId,"fixture-code","fixture-verifier","http://127.0.0.1:1234/auth/callback",fake.nonce,call,started);
                    Credentials old=new Credentials(FakeChatGpt.access(0),FakeChatGpt.refresh(0),"",fake.scope,started+3600000);
                    return provider.refresh(fake.clientId,old,fake.subject,call,started);
                });
                assertTrue((refresh?fake.refreshEntered:fake.exchangeEntered).await(2,TimeUnit.SECONDS));
                // Greater than the verifier's five-second clock-skew allowance. The fixture
                // signs the new ID token only after this response gate is released.
                Thread.sleep(8000);long receivedNotBefore=System.currentTimeMillis();release.countDown();
                ChatGptProvider.Grant grant=pending.get(4,TimeUnit.SECONDS);
                assertEquals(fake.subject,grant.subject);
                assertTrue(grant.credentials.expiresAt>=receivedNotBefore+fake.expiresIn*1000-1000);
                assertEquals(refresh?1:0,fake.refreshes.get());assertEquals(refresh?0:1,fake.exchanges.get());assertEquals(0,fake.responses.get());
            }finally{release.countDown();worker.shutdownNow();}
        }
    }
    @Test public void delayedAuthorizationCodeExchangeAcceptsNewlyIssuedIdentity()throws Exception {delayedGrant(false);}
    @Test public void delayedRefreshAcceptsNewlyIssuedIdentity()throws Exception {delayedGrant(true);}
    @Test public void actuallyFutureIssuedAndExpiredTokensRemainRejected()throws Exception {
        long now=System.currentTimeMillis(),seconds=now/1000;String issuer="https://issuer.example",client="oaiapp_fixture";
        JSONObject claims=new JSONObject().put("iss",issuer).put("sub","subject").put("aud",client).put("nonce","nonce");
        for(boolean future:new boolean[]{true,false}){
            claims.put("iat",future?seconds+60:seconds-120).put("exp",future?seconds+3600:seconds-60);
            String token=FakeChatGpt.signed(claims,new JSONObject().put("alg","RS256").put("kid","fixture-key"));
            assertEquals("INVALID_IDENTITY_TOKEN",assertThrows(GatewayFailure.class,()->OidcIdentityVerifier.verify(token,FakeChatGpt.jwks(),issuer,client,"nonce","access",now)).code);
        }
    }
}
