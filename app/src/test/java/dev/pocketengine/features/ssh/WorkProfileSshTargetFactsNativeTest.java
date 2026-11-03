package dev.pocketengine.features.ssh;

import android.app.Application;
import android.util.AtomicFile;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.store.SecretVault;
import dev.pocketengine.store.SettingsStore;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Original SSH profile/host-key/credential-reference state. Never opens an SSH socket or PTY. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class WorkProfileSshTargetFactsNativeTest {
    private static final class Fixture implements AutoCloseable {
        final SshProfile profile;final SshModule ssh;
        Fixture(boolean trusted,boolean credentials)throws Exception {
            var app=RuntimeEnvironment.getApplication();JSONObject row=new JSONObject().put("id","original-ssh").put("name","private source fixture").put("host","fixture.invalid").put("port",22).put("username","owner").put("workspace","/fixture/project").put("authType","key").put("pinnedKey",trusted?Base64.getEncoder().encodeToString(new byte[32]):"").put("configRevision","11111111-1111-1111-1111-111111111111").put("credentialRevision","22222222-2222-2222-2222-222222222222");profile=new SshProfile(row);
            File file=new File(app.getFilesDir(),"native-ssh-source-profile.json");Files.write(file.toPath(),new JSONArray().put(row).toString().getBytes(StandardCharsets.UTF_8));
            if(credentials)app.getSharedPreferences("feature_secrets",0).edit().putString(profile.credentialSlot(),"fixture-ciphertext-presence-only").commit();else app.getSharedPreferences("feature_secrets",0).edit().remove(profile.credentialSlot()).commit();
            ssh=new SshModule(new FeatureContext(app,new SettingsStore(app),new SecretVault(app),event->{}),new AtomicFile(file));
        }
        JSONObject ref()throws Exception{return new JSONObject().put("kind","ssh").put("profileId",profile.id).put("bindingHash",profile.bindingHash()).put("optional",false);}
        public void close(){ssh.close();}
    }
    @Test public void configuredTrustedOriginalBindingIsOnlySourceReadyAndCreatesNoConnectionOrOperation()throws Exception {
        try(Fixture f=new Fixture(true,true)){JSONObject ref=f.ref(),facts=f.ssh.workProfileTargetFacts(ref,new RunControl());assertTrue(facts.getBoolean("supported"));assertEquals(f.profile.bindingHash(),facts.getString("bindingHash"));JSONObject evidence=facts.getJSONObject("sourceEvidence");assertTrue(evidence.getBoolean("hostKeyTrusted"));assertTrue(evidence.getBoolean("credentialsConfigured"));assertFalse(evidence.getBoolean("connectionVerified"));assertFalse(evidence.getBoolean("configurationApplied"));assertFalse(evidence.getBoolean("runtimeVerified"));assertFalse(facts.toString().contains("fixture.invalid"));assertFalse(facts.toString().contains("ciphertext"));f.ssh.checkWorkProfileTargetCurrent(ref,facts,new RunControl());assertEquals(0,f.ssh.snapshot().getJSONArray("operations").length());assertEquals(0,f.ssh.snapshot().getJSONArray("terminals").length());}
    }
    @Test public void untrustedHostOrMissingCredentialIsUnavailableNotAConnectivitySuccess()throws Exception {
        for(boolean trusted:new boolean[]{false,true})try(Fixture f=new Fixture(trusted,!trusted)){JSONObject facts=f.ssh.workProfileTargetFacts(f.ref(),new RunControl());assertFalse(facts.getBoolean("supported"));assertEquals(trusted?"credentials_unconfigured":"host_key_untrusted",facts.getJSONObject("sourceEvidence").getString("reasonCode"));assertFalse(facts.getJSONObject("sourceEvidence").getBoolean("connectionVerified"));}
    }
    @Test public void credentialWithdrawalChangedTupleAndClosedOrCancelledOwnerFailTheFastGuard()throws Exception {
        try(Fixture f=new Fixture(true,true)){JSONObject ref=f.ref(),facts=f.ssh.workProfileTargetFacts(ref,new RunControl());
            assertThrows(SecurityException.class,()->f.ssh.checkWorkProfileTargetCurrent(ref,new JSONObject(facts.toString()).put("profileId","other"),new RunControl()));
            RuntimeEnvironment.getApplication().getSharedPreferences("feature_secrets",0).edit().remove(f.profile.credentialSlot()).commit();assertThrows(SecurityException.class,()->f.ssh.checkWorkProfileTargetCurrent(ref,facts,new RunControl()));
            assertThrows(SecurityException.class,()->f.ssh.workProfileTargetFacts(new JSONObject(ref.toString()).put("bindingHash","f".repeat(64)),new RunControl()));assertThrows(SecurityException.class,()->f.ssh.workProfileTargetFacts(new JSONObject(ref.toString()).put("argv",new JSONArray().put("forged")),new RunControl()));
            RuntimeEnvironment.getApplication().getSharedPreferences("feature_secrets",0).edit().putString(f.profile.credentialSlot(),"fixture-ciphertext-presence-only").commit();
            f.ssh.handle("forgetHostKey",new JSONObject().put("profileId",f.profile.id).put("fingerprint",SshProfile.fingerprint(Base64.getDecoder().decode(f.profile.pinnedKey))));assertThrows(SecurityException.class,()->f.ssh.checkWorkProfileTargetCurrent(ref,facts,new RunControl()));
            RunControl cancelled=new RunControl();cancelled.cancel();assertThrows(InterruptedException.class,()->f.ssh.workProfileTargetFacts(ref,cancelled));f.ssh.close();assertThrows(SecurityException.class,()->f.ssh.workProfileTargetFacts(ref,new RunControl()));}
    }
}
