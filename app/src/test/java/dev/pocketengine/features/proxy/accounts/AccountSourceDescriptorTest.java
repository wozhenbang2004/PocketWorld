package dev.pocketengine.features.proxy.accounts;

import java.io.File;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public final class AccountSourceDescriptorTest {
    @Rule public final TemporaryFolder folder=new TemporaryFolder();

    private static final class Vault implements CredentialVault {
        final Map<String,String> values=new HashMap<>();int reads;
        public String get(String slot){reads++;return values.getOrDefault(slot,"");}
        public void put(String slot,String value){values.put(slot,value);}
        public void remove(String slot){values.remove(slot);}
    }

    @Test public void publicSourceUsesExistingStatusAndDoesNotReadOrExposeCredentials()throws Exception {
        File file=new File(folder.getRoot(),"accounts.json");Vault vault=new Vault();AccountBook book=new AccountBook(file,vault,"owner");
        JSONObject account=book.create("My ChatGPT");String id=account.getString("id");
        JSONObject registered=book.update(id,account.getLong("version"),AccountBook.json("clientId","fixture-client"));
        Credentials credentials=new Credentials("fixture-access-secret","fixture-refresh-secret","fixture-id-secret","chatgpt.tokens.use.direct",System.currentTimeMillis()+60_000);
        JSONObject connected=book.publish(id,registered.getLong("version"),credentials,"https://issuer.invalid","fixture-subject","user@fixture.invalid");
        String stored=Files.readString(file.toPath());JSONObject row=book.snapshot().getJSONArray("accounts").getJSONObject(0),source=row.getJSONObject("source");
        assertEquals("subscription_account",source.getString("kind"));assertEquals("chatgpt",source.getString("providerId"));
        assertEquals("oauth",source.getString("authentication"));assertFalse(source.getBoolean("externalRelayEligible"));
        assertEquals("connected",source.getString("credentialState"));assertEquals("unknown",source.getJSONObject("quota").getString("state"));
        assertEquals(connected.getLong("version"),row.getLong("version"));assertEquals(credentials.expiresAt,row.getLong("expiresAt"));
        for(String hidden:new String[]{credentials.accessToken(),credentials.refreshToken(),credentials.idToken(),connected.getString("slot"),"secretSlot"})assertFalse(row.toString().contains(hidden));
        assertEquals(0,vault.reads);assertEquals(stored,Files.readString(file.toPath()));assertFalse(book.row(id).has("source"));
        source.put("externalRelayEligible",true);assertFalse(book.snapshot().getJSONArray("accounts").getJSONObject(0).getJSONObject("source").getBoolean("externalRelayEligible"));
    }

    @Test public void cooldownDoesNotInventQuotaAndObservedLimitsNeverInventRemainingUnits()throws Exception {
        AccountBook book=new AccountBook(new File(folder.getRoot(),"accounts.json"),new Vault(),"owner");
        JSONObject account=book.create("My ChatGPT");String id=account.getString("id");long version=account.getLong("version");
        book.metadata(id,version,AccountBook.json("status","cooldown","cooldownUntil",System.currentTimeMillis()+60_000));
        JSONObject source=book.snapshot().getJSONArray("accounts").getJSONObject(0).getJSONObject("source");
        assertEquals("cooldown",source.getString("credentialState"));assertEquals("unknown",source.getJSONObject("quota").getString("state"));
        book.metadata(id,version,AccountBook.json("usageLimited",true,"status","reauthorization_required"));
        source=book.snapshot().getJSONArray("accounts").getJSONObject(0).getJSONObject("source");
        assertEquals("reauthorization_required",source.getString("credentialState"));assertEquals("limited",source.getJSONObject("quota").getString("state"));
        assertEquals(1,source.getJSONObject("quota").length());assertFalse(source.getBoolean("externalRelayEligible"));
        assertEquals(version,book.row(id).getLong("version"));
    }
}
