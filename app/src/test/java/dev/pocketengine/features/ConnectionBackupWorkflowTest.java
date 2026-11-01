package dev.pocketengine.features;

import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.channels.ChannelTaskHostTest;
import dev.pocketengine.features.remote.RemoteModule;
import dev.pocketengine.features.ssh.SshModule;
import dev.pocketengine.store.SecretVault;
import dev.pocketengine.store.SettingsStore;
import java.nio.file.Files;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import static org.junit.Assert.*;

/** Local configuration only. No endpoint, SSH host, account login or credential migration. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public final class ConnectionBackupWorkflowTest {
    @Before public void vault(){java.security.Security.addProvider(new java.security.Provider("ConnectionBackupFixtureVault","1","Synthetic owner-keystore"){{put("KeyStore.AndroidKeyStore",ChannelTaskHostTest.FixtureKeyStore.class.getName());}});}
    @After public void release(){java.security.Security.removeProvider("ConnectionBackupFixtureVault");}
    private static FeatureContext context()throws Exception {Context app=RuntimeEnvironment.getApplication();var files=Files.createTempDirectory(app.getFilesDir().toPath(),"connection-backup-").toFile();Context android=new ContextWrapper(app){public java.io.File getFilesDir(){return files;}public Context getApplicationContext(){return this;}};return new FeatureContext(android,new SettingsStore(android),new SecretVault(android),ignored->{});}
    private static JSONObject sshRow()throws Exception{return new JSONObject().put("sourceProfileId","old-ssh").put("name","Original SSH").put("host","127.0.0.1").put("port",22222).put("username","fixture").put("workspace","/native-project").put("authType","key").put("previousHostFingerprint","SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");}

    @Test public void nativeSectionsRestoreNewConfigurationIdsDurablyWithoutAnySecretHostTrustJobGrantOrConnection()throws Exception {
        FeatureContext context=context();String action=UUID.randomUUID().toString();RunControl owner=new RunControl();
        try(RemoteModule remote=new RemoteModule(context);SshModule ssh=new SshModule(context)){
            remote.handle("saveProfile",new JSONObject().put("id","old-computer").put("name","My computer").put("baseUrl","https://remote.example/companion").put("token","fixture-original-secret-must-never-enter-backup"));
            JSONObject exported=remote.exportConnectionBackupForOwner(owner);assertFalse(exported.toString().contains("fixture-original-secret"));assertFalse(exported.toString().contains("bindingRevision"));
            assertEquals("unknown",remote.queryConnectionBackupForOwner(exported,action,owner).getJSONArray("profiles").getJSONObject(0).getString("state"));assertEquals(1,remote.snapshot().getJSONArray("profiles").length());
            var review=remote.reviewConnectionBackupForOwner(exported,action,owner);var admission=review.confirm();JSONObject first=remote.restoreConnectionBackupForOwner(admission,owner);String id=first.getJSONArray("profiles").getJSONObject(0).getString("newProfileId");assertNotEquals("old-computer",id);assertEquals("restored_needs_credentials",first.getJSONArray("profiles").getJSONObject(0).getString("state"));
            JSONObject again=remote.restoreConnectionBackupForOwner(admission,owner);assertTrue(again.getBoolean("queryOnly"));assertEquals(id,again.getJSONArray("profiles").getJSONObject(0).getString("newProfileId"));assertEquals("already_restored",again.getJSONArray("profiles").getJSONObject(0).getString("state"));assertEquals("found",remote.queryConnectionBackupForOwner(exported,action,owner).getJSONArray("profiles").getJSONObject(0).getString("state"));
            JSONObject row=null;JSONArray profiles=remote.snapshot().getJSONArray("profiles");for(int n=0;n<profiles.length();n++)if(id.equals(profiles.getJSONObject(n).getString("id")))row=profiles.getJSONObject(n);assertNotNull(row);assertFalse(row.getBoolean("hasToken"));assertTrue(remote.snapshot().getJSONArray("operations").length()==0);
            JSONObject section=ConnectionBackupSections.section("ssh",new JSONArray().put(sshRow()));var sshAdmission=ssh.reviewConnectionBackupForOwner(section,action,owner).confirm();JSONObject restored=ssh.restoreConnectionBackupForOwner(sshAdmission,owner);assertEquals("restored_needs_credentials_and_host_verification",restored.getJSONArray("profiles").getJSONObject(0).getString("state"));JSONObject newSsh=ssh.snapshot().getJSONArray("profiles").getJSONObject(0);assertFalse(newSsh.getBoolean("credentialsConfigured"));assertFalse(newSsh.getBoolean("hostKeyTrusted"));assertEquals("",newSsh.getString("hostFingerprint"));assertEquals("found",ssh.queryConnectionBackupForOwner(section,action,owner).getJSONArray("profiles").getJSONObject(0).getString("state"));assertEquals(0,ssh.snapshot().getJSONArray("operations").length());
            JSONObject reexport=ssh.exportConnectionBackupForOwner(owner);assertEquals("",reexport.getJSONArray("profiles").getJSONObject(0).getString("previousHostFingerprint"));assertFalse(reexport.toString().contains("pinnedKey"));assertFalse(reexport.toString().contains("nativeBackupOrigin"));
        }
        try(RemoteModule remote=new RemoteModule(context);SshModule ssh=new SshModule(context)){
            assertEquals(2,remote.snapshot().getJSONArray("profiles").length());assertEquals(1,ssh.snapshot().getJSONArray("profiles").length());assertFalse(ssh.snapshot().getJSONArray("profiles").getJSONObject(0).getBoolean("hostKeyTrusted"));
        }
    }

    @Test public void nonSecretFormatRejectsCredentialAndGrantInjectionAndRevokedOriginalControlMakesNoImportedRow()throws Exception {
        FeatureContext context=context();try(RemoteModule remote=new RemoteModule(context);SshModule ssh=new SshModule(context)){
            RunControl original=new RunControl();String action=UUID.randomUUID().toString();JSONObject section=ConnectionBackupSections.section("remote",new JSONArray().put(new JSONObject().put("sourceProfileId","old").put("name","Original").put("baseUrl","https://remote.example")));
            JSONObject injected=new JSONObject(section.toString());injected.getJSONArray("profiles").getJSONObject(0).put("token","not-permitted");assertThrows(SecurityException.class,()->remote.reviewConnectionBackupForOwner(injected,action,original));
            JSONObject sshSection=ConnectionBackupSections.section("ssh",new JSONArray().put(sshRow()));sshSection.getJSONArray("profiles").getJSONObject(0).put("pinnedKey","not-an-importable-trust-grant");assertThrows(SecurityException.class,()->ssh.reviewConnectionBackupForOwner(sshSection,action,original));
            var review=remote.reviewConnectionBackupForOwner(section,action,original);JSONObject copy=review.metadata();copy.getJSONArray("profiles").getJSONObject(0).put("baseUrl","https://other.example");assertEquals("https://remote.example",review.metadata().getJSONArray("profiles").getJSONObject(0).getString("baseUrl"));var admission=review.confirm();assertThrows(SecurityException.class,()->remote.restoreConnectionBackupForOwner(admission,new RunControl()));original.cancel();assertThrows(InterruptedException.class,()->remote.restoreConnectionBackupForOwner(admission,original));assertEquals(0,remote.snapshot().getJSONArray("profiles").length());assertEquals(0,ssh.snapshot().getJSONArray("profiles").length());
        }
    }

    @Test public void repeatedOriginalActionNeverClobbersAnOwnerEditedImportedConfiguration()throws Exception {
        FeatureContext context=context();try(RemoteModule remote=new RemoteModule(context)){
            RunControl original=new RunControl();String action=UUID.randomUUID().toString();JSONObject section=ConnectionBackupSections.section("remote",new JSONArray().put(new JSONObject().put("sourceProfileId","old").put("name","Original").put("baseUrl","https://remote.example")));var admission=remote.reviewConnectionBackupForOwner(section,action,original).confirm();String id=remote.restoreConnectionBackupForOwner(admission,original).getJSONArray("profiles").getJSONObject(0).getString("newProfileId");remote.handle("saveProfile",new JSONObject().put("id",id).put("name","Owner changed").put("baseUrl","https://changed.example"));
            JSONObject result=remote.restoreConnectionBackupForOwner(admission,original);assertEquals("configuration_changed_not_overwritten",result.getJSONArray("profiles").getJSONObject(0).getString("state"));assertEquals(1,remote.snapshot().getJSONArray("profiles").length());assertEquals("https://changed.example",remote.snapshot().getJSONArray("profiles").getJSONObject(0).getString("baseUrl"));
        }
    }
}
