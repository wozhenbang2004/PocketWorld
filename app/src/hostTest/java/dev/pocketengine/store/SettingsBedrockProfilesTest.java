package dev.pocketengine.store;

import android.app.Application;
import android.content.SharedPreferences;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.runtime.models.ApiConnectionProfile;
import dev.pocketengine.runtime.models.ModelBinding;
import java.time.Instant;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Original SharedPreferences and inactive-slot seam; not AndroidKeyStore or AWS acceptance. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class SettingsBedrockProfilesTest {
    private SharedPreferences prefs;private SettingsApiProfilesTest.Secrets secrets;private SettingsStore store;
    private static final String ACCESS="ASIAABCDEFGHIJKLMNOP",SECRET="FixtureSecretAccessKey0123456789+/=",TOKEN="FixtureSessionToken0123456789+/=";
    @Before public void open()throws Exception{prefs=RuntimeEnvironment.getApplication().getSharedPreferences("bedrock_settings",0);prefs.edit().clear().commit();secrets=new SettingsApiProfilesTest.Secrets();store=new SettingsStore(prefs,secrets);store.apiConnections();}
    private static JSONObject session(String suffix,long expiry)throws Exception{return new JSONObject().put("accessKeyId",ACCESS).put("secretAccessKey",SECRET+suffix).put("sessionToken",TOKEN+suffix).put("expiresAt",Instant.ofEpochMilli(expiry).toString());}
    private static JSONObject input()throws Exception{return new JSONObject().put("provider","bedrock_anthropic").put("bedrockRegion","us-east-1").put("model","anthropic.claude-sonnet-4-6").put("awsSession",session("",System.currentTimeMillis()+3600000));}
    private JSONObject add()throws Exception{return store.addAndSelectApiConnection("AWS owner",input());}
    @Test public void originalVaultAndRestartKeepNativeIdentityWithoutPublicSecretBytes()throws Exception{
        JSONObject row=add();ModelConfig original=store.get();assertEquals("",original.apiKey);assertNotNull(original.awsSession);assertEquals(ACCESS,original.awsSession.accessKeyId());assertEquals(row.getString("id"),original.capabilityBinding.connectionId());
        assertEquals(ModelBinding.Format.NONE,original.capabilityBinding.format());assertEquals("",original.capabilityBinding.modelsEndpoint().toString());assertFalse(original.catalogEnforced);
        SettingsStore reopened=new SettingsStore(prefs,secrets);assertTrue(original.sameCredentials(reopened.get()));assertEquals(original.capabilityBinding,reopened.currentForCaptured(original).capabilityBinding);
        assertFalse(original.containsSensitive(prefs.getAll().toString()));assertFalse(original.containsSensitive(store.apiConnectionsSnapshot().toString()));assertTrue(row.getBoolean("hasAwsSession"));assertFalse(row.getBoolean("hasApiKey"));
        assertEquals(1,secrets.values.size());assertTrue(secrets.values.values().iterator().next().contains("pocketworld.aws-sts-session.v1"));
    }
    @Test public void connectionMustBeSavedBeforeTestAndUnsavedChangesNeverDispatch()throws Exception{
        assertThrows(IllegalStateException.class,()->store.previewApiConnection("",0,input()));JSONObject row=add();String id=row.getString("id");
        ModelConfig saved=store.get();assertEquals(saved.capabilityBinding,store.previewApiConnection(id,1,new JSONObject()).capabilityBinding);
        assertThrows(IllegalStateException.class,()->store.previewApiConnection(id,1,new JSONObject().put("awsSession",session("",saved.awsSession.expiresAtEpochMillis()))));
        assertThrows(IllegalStateException.class,()->store.previewApiConnection(id,1,new JSONObject().put("maxOutputTokens",8192)));
        assertThrows(IllegalStateException.class,()->store.previewApiConnection(id,1,new JSONObject().put("model","anthropic.claude-opus-4-6-v1")));
        assertThrows(Exception.class,()->store.configureApiCatalog(null,ModelBinding.Format.NONE,java.net.URI.create("")));
        assertEquals(saved.capabilityBinding,store.get().capabilityBinding);
    }
    @Test public void rotationUsesOriginalConnectionAndTwoSlotsWhileOldRunStaysRevoked()throws Exception{
        JSONObject row=add();ModelConfig original=store.get();
        for(int i=1;i<=4;i++){row=store.saveApiConnection(row.getString("id"),row.getLong("revision"),"AWS owner",new JSONObject().put("awsSession",session("A".repeat(i),System.currentTimeMillis()+3600000)));assertEquals(original.capabilityBinding.connectionId(),store.get().capabilityBinding.connectionId());}
        assertEquals(original.capabilityBinding.credentialRevision()+4,store.get().capabilityBinding.credentialRevision());assertEquals(2,secrets.values.size());assertThrows(SecurityException.class,()->store.currentForCaptured(original));
        ModelConfig current=store.get();JSONObject frozen=row;assertThrows(IllegalStateException.class,()->store.saveApiConnection(frozen.getString("id"),1,"stale",new JSONObject().put("awsSession",session("Z",System.currentTimeMillis()+3600000))));assertEquals(current.capabilityBinding,store.get().capabilityBinding);
    }
    @Test public void expiryIsReadableButSelectionTestAndCapturedDispatchAreRejected()throws Exception{
        JSONObject row=add();ModelConfig captured=store.get();JSONObject document=new JSONObject(prefs.getString("apiConnections","")),stored=document.getJSONArray("connections").getJSONObject(0);long expired=System.currentTimeMillis()-1;
        JSONObject vault=new JSONObject(secrets.get(stored.getString("secretSlot"))).put("expiresAtEpochMillis",expired);secrets.put(stored.getString("secretSlot"),vault.toString());stored.put("credentialExpiresAtEpochMillis",expired);assertTrue(prefs.edit().putString("apiConnections",document.toString()).commit());
        SettingsStore reopened=new SettingsStore(prefs,secrets);assertEquals("expired",reopened.apiConnectionsSnapshot().getJSONArray("connections").getJSONObject(0).getString("credentialStatus"));assertNotNull(reopened.get().awsSession);
        assertThrows(IllegalArgumentException.class,()->reopened.selectApiConnection(row.getString("id"),1));assertThrows(IllegalArgumentException.class,()->reopened.useApiKeyModel());
        assertThrows(IllegalArgumentException.class,()->reopened.previewApiConnection(row.getString("id"),1,new JSONObject()));assertThrows(Exception.class,()->reopened.currentForCaptured(captured));
    }
    @Test public void regionCannotBorrowSessionAndFailedVaultWriteKeepsOriginalMetadata()throws Exception{
        JSONObject row=add();String id=row.getString("id"),before=prefs.getString("apiConnections","");ModelConfig original=store.get();
        assertThrows(IllegalArgumentException.class,()->store.saveApiConnection(id,1,"region changed",new JSONObject().put("bedrockRegion","eu-west-1")));assertEquals(before,prefs.getString("apiConnections",""));
        secrets.failPut=true;assertThrows(IllegalStateException.class,()->store.saveApiConnection(id,1,"AWS owner",new JSONObject().put("awsSession",session("new",System.currentTimeMillis()+3600000))));assertEquals(before,prefs.getString("apiConnections",""));assertTrue(original.sameCredentials(store.get()));
    }
    @Test public void disablingAndReenablingDoesNotReviveOldSessionAuthority()throws Exception{
        JSONObject row=add();ModelConfig original=store.get();JSONObject disabled=store.setApiConnectionEnabled(row.getString("id"),1,false);assertNull(store.get().awsSession);assertThrows(SecurityException.class,()->store.currentForCaptured(original));
        JSONObject enabled=store.setApiConnectionEnabled(row.getString("id"),disabled.getLong("revision"),true);assertThrows(SecurityException.class,()->store.currentForCaptured(original));assertEquals(original.awsSession.sessionToken(),store.selectApiConnection(row.getString("id"),enabled.getLong("revision")).awsSession.sessionToken());
    }
    @Test public void backupRetainsTargetOnlyAndRestoresNewDisabledCredentialFreeIdentity()throws Exception{
        JSONObject original=add();ModelConfig config=store.get();secrets.failGet=true;JSONObject backup=store.exportBackupForOwner(new RunControl());assertFalse(config.containsSensitive(backup.toString()));assertFalse(backup.toString().contains("credentialExpiresAt"));
        JSONObject plan=OwnerSettingsBackup.prepare(backup,"12345678-1234-1234-1234-123456789abc");ApiConnectionProfile restored=ApiConnectionProfile.fromJson(plan.getJSONArray("apiConnections").getJSONObject(0));
        assertNotEquals(original.getString("id"),restored.id());assertEquals("us-east-1",restored.configuration.bedrockRegion);assertEquals(config.model,restored.configuration.model);assertFalse(restored.enabled);assertEquals("",restored.secretSlot);assertNull(restored.configuration.awsSession);assertEquals(0,restored.credentialExpiresAtEpochMillis);
        assertEquals(original.getString("id"),store.apiConnectionsSnapshot().getString("selectedConnectionId"));
    }
    @Test public void originalAndroidJsonCannotCoerceNumbersIntoTypedSessionSecrets()throws Exception{
        JSONObject request=input();request.getJSONObject("awsSession").put("secretAccessKey",12345678901234567L).put("sessionToken",23456789012345678L);
        assertThrows(IllegalArgumentException.class,()->store.addAndSelectApiConnection("coerced",request));assertEquals(0,store.apiConnectionsSnapshot().getJSONArray("connections").length());assertTrue(secrets.values.isEmpty());
        JSONObject row=add(),document=new JSONObject(prefs.getString("apiConnections",""));String slot=document.getJSONArray("connections").getJSONObject(0).getString("secretSlot");JSONObject vault=new JSONObject(secrets.get(slot)).put("secretAccessKey",12345678901234567L).put("sessionToken",23456789012345678L);secrets.put(slot,vault.toString());
        assertThrows(IllegalStateException.class,()->new SettingsStore(prefs,secrets).selectApiConnection(row.getString("id"),1));
    }
}
