package dev.pocketengine.features.ssh;

import android.content.Context;
import android.util.AtomicFile;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.store.SecretVault;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.Key;
import java.security.KeyStoreSpi;
import java.security.Provider;
import java.security.Security;
import java.security.cert.Certificate;
import java.util.Base64;
import java.util.Date;
import java.util.Enumeration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import javax.crypto.spec.SecretKeySpec;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** A24's actual credential/TCP counterexample, asserting the intended fence instead of the bug. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE)
public final class SshCredentialFenceTest {
    private static final String PROVIDER="A26BCredentialFixture",BEFORE="synthetic-before",AFTER="synthetic-after";
    private Context android;private SecretVault vault;
    @Before public void setUp(){
        Provider provider=new Provider(PROVIDER,"1","Synthetic fixture only"){};provider.put("KeyStore.AndroidKeyStore",FakeStore.class.getName());Security.addProvider(provider);
        android=RuntimeEnvironment.getApplication();vault=new SecretVault(android);
    }
    @After public void cleanUp(){Security.removeProvider(PROVIDER);}
    private SshProfile initial(int port,String auth)throws Exception {
        return new SshProfile(new JSONObject().put("id","fixture").put("name","fixture").put("host","127.0.0.1").put("port",port).put("username","developer").put("workspace","/workspace").put("authType",auth)
                .put("pinnedKey",Base64.getEncoder().encodeToString("synthetic-host-key-1111111111111111".getBytes(StandardCharsets.UTF_8))));
    }
    private void seed(SshProfile profile)throws Exception {Files.writeString(android.getFilesDir().toPath().resolve("ssh-profiles.json"),new JSONArray().put(profile.document()).toString());}
    private FeatureContext context(){return new FeatureContext(android,null,vault,event->{});}
    private File file(){return new File(android.getFilesDir(),"ssh-profiles.json");}
    private SshProfile persisted()throws Exception {return new SshProfile(new JSONArray(Files.readString(file().toPath())).getJSONObject(0));}
    private JSONObject current(SshModule module)throws Exception {return module.snapshot().getJSONArray("profiles").getJSONObject(0);}
    private int slots(){return android.getSharedPreferences("feature_secrets",Context.MODE_PRIVATE).getAll().size();}
    private RunControl control(){RunControl control=new RunControl();control.bindHostRun("fixture-task","fixture-run",7);control.setAuthorityCheck(()->{});return control;}
    private JSONObject operation(String binding,String id)throws Exception {return new JSONObject().put("profileId","fixture").put("_profileBinding",binding).put("hostTaskId","fixture-task").put("_operationId",id).put("_hostRunId","fixture-run").put("_hostEpoch",7).put("principalId","owner").put("spaceId","private");}

    @Test public void staleOwnerEditorCannotChangeReplacementCredentialsOrRemoveProfile()throws Exception {
        SshProfile profile=initial(22,"password");seed(profile);SshModule module=new SshModule(context());
        try{
            JSONObject before=module.handle("saveProfile",profile.document().put("password",BEFORE));
            JSONObject after=module.handle("saveProfile",profile.document().put("expectedBindingHash",before.getString("bindingHash")).put("password",AFTER));
            assertThrows(SecurityException.class,()->module.handle("saveProfile",profile.document().put("expectedBindingHash",before.getString("bindingHash")).put("password",BEFORE)));
            assertThrows(SecurityException.class,()->module.handle("removeProfile",new JSONObject().put("profileId",profile.id).put("expectedBindingHash",before.getString("bindingHash"))));
            assertEquals(after.getString("bindingHash"),current(module).getString("bindingHash"));assertEquals(AFTER,new JSONObject(vault.get(persisted().credentialSlot())).getString("password"));
        }finally{module.close();}
    }

    @Test public void credentialReplacementRejectsOriginalBindingBeforeRegistrationAndTcp()throws Exception {
        AtomicInteger accepted=new AtomicInteger();
        try(ServerSocket tcp=new ServerSocket(0,1,InetAddress.getLoopbackAddress())){
            tcp.setSoTimeout(300);SshProfile profile=initial(tcp.getLocalPort(),"password");seed(profile);
            try(SshModule module=new SshModule(context())){
                JSONObject before=module.handle("saveProfile",profile.document().put("password",BEFORE));String reviewed=before.getString("bindingHash");RunControl original=control();
                JSONObject after=module.handle("saveProfile",profile.document().put("password",AFTER));
                Thread peer=new Thread(()->{try(java.net.Socket connection=tcp.accept()){accepted.incrementAndGet();}catch(Exception timeout){}},"a26b-owned-peer");peer.start();
                Exception refusal=null;try{module.invokeForHost("doctor",operation(reviewed,"stale-op"),original);}catch(Exception expected){refusal=expected;}peer.join(1500);
                JSONObject status=module.handle("getOperation",new JSONObject().put("operationId","stale-op"));
                System.out.println("CREDENTIAL_FENCE bindingChanged="+!reviewed.equals(after.getString("bindingHash"))+" staleRejected="+(refusal instanceof SecurityException)+" registered="+!status.optString("status").equals("not_active")+" TCP="+accepted.get()+" SSH_protocol_verified=false");
                assertTrue("Old binding must fail before credential access/registration",refusal instanceof SecurityException);
                assertNotEquals(reviewed,after.getString("bindingHash"));assertEquals("not_active",status.getString("status"));assertEquals(0,accepted.get());assertFalse(original.isCancelled());
            }
        }
    }

    @Test public void passwordClearReenterAndBlankSaveAllInvalidateCapturedBindings()throws Exception {
        SshProfile profile=initial(22,"password");seed(profile);
        try(SshModule module=new SshModule(context())){
            JSONObject before=module.handle("saveProfile",profile.document().put("password",BEFORE));
            JSONObject[] updates={profile.document().put("password",AFTER),profile.document(),profile.document().put("clearCredentials",true),profile.document().put("password",BEFORE)};
            for(int i=0;i<updates.length;i++){
                String oldBinding=before.getString("bindingHash");JSONObject next=module.handle("saveProfile",updates[i]);
                assertNotEquals(before.getString("configRevision"),next.getString("configRevision"));assertNotEquals(before.getString("credentialRevision"),next.getString("credentialRevision"));
                assertThrows(SecurityException.class,()->module.invokeForHost("doctor",operation(oldBinding,"password-stale"),control()));before=next;
            }
            assertEquals(BEFORE,new JSONObject(vault.get(persisted().credentialSlot())).getString("password"));assertEquals(1,slots());
        }
    }
    @Test public void privateKeyPassphraseReplacementAndClearInvalidateCapturedBindings()throws Exception {
        SshProfile profile=initial(22,"key");seed(profile);
        try(SshModule module=new SshModule(context())){
            JSONObject before=module.handle("saveProfile",profile.document().put("privateKey","synthetic-key-a").put("passphrase",BEFORE));
            JSONObject[] updates={profile.document().put("privateKey","synthetic-key-b"),profile.document().put("passphrase",AFTER),profile.document().put("clearPassphrase",true),profile.document().put("clearCredentials",true)};
            for(JSONObject update:updates){String oldBinding=before.getString("bindingHash");JSONObject next=module.handle("saveProfile",update);assertNotEquals(oldBinding,next.getString("bindingHash"));assertThrows(SecurityException.class,()->module.invokeForHost("doctor",operation(oldBinding,"key-stale"),control()));before=next;}
            assertFalse(before.getBoolean("credentialsConfigured"));assertEquals(0,slots());
        }
    }
    @Test public void legacySlotMigratesOnlyAfterCommitAndRevisionsSurviveReload()throws Exception {
        SshProfile legacy=initial(22,"password");seed(legacy);vault.put(legacy.credentialSlot(),new JSONObject().put("password",BEFORE).toString());String binding;
        try(SshModule module=new SshModule(context())){
            assertEquals(legacy.bindingHash(),current(module).getString("bindingHash"));JSONObject saved=module.handle("saveProfile",legacy.document());binding=saved.getString("bindingHash");
            assertNotEquals(legacy.credentialSlot(),persisted().credentialSlot());assertFalse(vault.contains(legacy.credentialSlot()));assertEquals(BEFORE,new JSONObject(vault.get(persisted().credentialSlot())).getString("password"));assertEquals(1,slots());
            String publicDocument=current(module).toString(),storedDocument=Files.readString(file().toPath());assertFalse(publicDocument.contains(BEFORE));assertFalse(storedDocument.contains(BEFORE));
        }
        try(SshModule restored=new SshModule(context())){assertEquals(binding,current(restored).getString("bindingHash"));assertTrue(current(restored).getBoolean("credentialsConfigured"));assertThrows(SecurityException.class,()->restored.invokeForHost("doctor",operation(legacy.bindingHash(),"legacy-stale"),control()));}
    }
    @Test public void delayedOriginalControlCannotConsumeAConfigurationSavedWhileQueued()throws Exception {
        SshProfile profile=initial(22,"password");seed(profile);
        try(SshModule module=new SshModule(context())){
            JSONObject before=module.handle("saveProfile",profile.document().put("password",BEFORE));RunControl original=control();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);AtomicInteger checks=new AtomicInteger();
            original.setAuthorityCheck(()->{if(checks.incrementAndGet()==1){entered.countDown();assertTrue(release.await(2,TimeUnit.SECONDS));}});
            FutureTask<Exception> future=new FutureTask<>(()->{try{module.invokeForHost("doctor",operation(before.getString("bindingHash"),"queued-stale"),original);return null;}catch(Exception failure){return failure;}});Thread worker=new Thread(future,"a26b-queued-binding");worker.start();
            try{assertTrue(entered.await(1,TimeUnit.SECONDS));module.handle("saveProfile",profile.document().put("password",AFTER));release.countDown();assertTrue(future.get(2,TimeUnit.SECONDS) instanceof SecurityException);assertEquals("not_active",module.handle("getOperation",new JSONObject().put("operationId","queued-stale")).getString("status"));}
            finally{release.countDown();original.cancel();worker.join(2000);}
        }
    }
    @Test public void anAlreadyRegisteredOperationPreventsCredentialMutation()throws Exception {
        SshProfile profile=initial(22,"password");seed(profile);
        try(SshModule module=new SshModule(context())){
            JSONObject before=module.handle("saveProfile",profile.document().put("password",BEFORE));RunControl original=control();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);AtomicInteger checks=new AtomicInteger();
            original.setAuthorityCheck(()->{if(checks.incrementAndGet()==4){entered.countDown();assertTrue(release.await(2,TimeUnit.SECONDS));}});
            FutureTask<JSONObject> future=new FutureTask<>(()->module.invokeForHost("doctor",operation(before.getString("bindingHash"),"registered"),original));Thread worker=new Thread(future,"a26b-registered-binding");worker.start();
            try{assertTrue(entered.await(1,TimeUnit.SECONDS));assertThrows(IllegalStateException.class,()->module.handle("saveProfile",profile.document().put("password",AFTER)));assertEquals(before.getString("bindingHash"),current(module).getString("bindingHash"));assertEquals(BEFORE,new JSONObject(vault.get(persisted().credentialSlot())).getString("password"));original.cancel();release.countDown();assertEquals("cancelled",future.get(2,TimeUnit.SECONDS).getString("status"));}
            finally{release.countDown();original.cancel();worker.join(2000);}
        }
    }
    @Test public void stagedCredentialRollsBackOnStartWriteFailure()throws Exception {assertSaveFailure(0);}
    @Test public void stagedCredentialRollsBackOnFinishWriteFailure()throws Exception {assertSaveFailure(1);}
    @Test public void stagedCredentialRollsBackWhenFinishSilentlyDiscardsWrite()throws Exception {assertSaveFailure(2);}
    private void assertSaveFailure(int behavior)throws Exception {
        SshProfile profile=initial(22,"password");seed(profile);FailingFile store=new FailingFile(file());
        try(SshModule module=new SshModule(context(),store)){
            JSONObject before=module.handle("saveProfile",profile.document().put("password",BEFORE));SshProfile old=persisted();byte[] oldDocument=Files.readAllBytes(file().toPath());store.behavior=behavior;
            assertThrows(IllegalStateException.class,()->module.handle("saveProfile",profile.document().put("password",AFTER)));
            assertEquals(before.getString("bindingHash"),current(module).getString("bindingHash"));assertArrayEquals(oldDocument,Files.readAllBytes(file().toPath()));assertEquals(old.credentialSlot(),persisted().credentialSlot());
            assertEquals(BEFORE,new JSONObject(vault.get(old.credentialSlot())).getString("password"));assertEquals("Only the previously committed slot remains",1,slots());
        }
        try(SshModule restored=new SshModule(context())){assertEquals(BEFORE,new JSONObject(vault.get(persisted().credentialSlot())).getString("password"));assertTrue(current(restored).getBoolean("credentialsConfigured"));}
    }
    @Test public void confirmedCommitAfterFinishThrowsKeepsNewRevisionAndNewSlot()throws Exception {
        SshProfile profile=initial(22,"password");seed(profile);FailingFile store=new FailingFile(file());
        try(SshModule module=new SshModule(context(),store)){
            JSONObject before=module.handle("saveProfile",profile.document().put("password",BEFORE));store.behavior=3;JSONObject after=module.handle("saveProfile",profile.document().put("password",AFTER));
            assertNotEquals(before.getString("bindingHash"),after.getString("bindingHash"));assertEquals(persisted().bindingHash(),after.getString("bindingHash"));assertEquals(AFTER,new JSONObject(vault.get(persisted().credentialSlot())).getString("password"));assertEquals(1,slots());
        }
    }
    @Test public void failedRemovalLeavesCommittedProfileAndCredentialIntact()throws Exception {
        SshProfile profile=initial(22,"password");seed(profile);FailingFile store=new FailingFile(file());
        try(SshModule module=new SshModule(context(),store)){
            JSONObject before=module.handle("saveProfile",profile.document().put("password",BEFORE));SshProfile old=persisted();store.behavior=0;
            assertThrows(IllegalStateException.class,()->module.handle("removeProfile",new JSONObject().put("profileId",profile.id)));assertEquals(before.getString("bindingHash"),current(module).getString("bindingHash"));assertEquals(BEFORE,new JSONObject(vault.get(old.credentialSlot())).getString("password"));
        }
    }
    @Test public void forgettingHostTrustAdvancesOnlyPublicConfigRevision()throws Exception {
        SshProfile profile=initial(22,"password");seed(profile);
        try(SshModule module=new SshModule(context())){
            JSONObject before=module.handle("saveProfile",profile.document().put("password",BEFORE));SshProfile old=persisted();
            JSONObject after=module.handle("forgetHostKey",new JSONObject().put("profileId",profile.id).put("fingerprint",before.getString("hostFingerprint")));
            assertNotEquals(before.getString("configRevision"),after.getString("configRevision"));assertEquals(before.getString("credentialRevision"),after.getString("credentialRevision"));assertFalse(after.getBoolean("hostKeyTrusted"));assertEquals(old.credentialSlot(),persisted().credentialSlot());assertEquals(BEFORE,new JSONObject(vault.get(persisted().credentialSlot())).getString("password"));
        }
    }
    @Test public void nonSecretRevisionValidationAndAccountLeaseRemainIndependent()throws Exception {
        SshProfile original=initial(22,"password"),first=new SshProfile(original.document().put("configRevision","11111111-1111-4111-8111-111111111111").put("credentialRevision","22222222-2222-4222-8222-222222222222"));
        SshProfile later=new SshProfile(first.document().put("configRevision","33333333-3333-4333-8333-333333333333"));
        assertNotEquals(first.bindingHash(),later.bindingHash());assertEquals(first.accountKey(),later.accountKey());assertEquals(first.credentialSlot(),later.credentialSlot());assertNotEquals(original.credentialSlot(),first.credentialSlot());
        assertThrows(IllegalArgumentException.class,()->new SshProfile(first.document().put("configRevision",42)));assertThrows(IllegalArgumentException.class,()->new SshProfile(first.document().put("credentialRevision","not-a-revision")));
    }
    private static final class FailingFile extends AtomicFile {
        int behavior=-1;FailingFile(File file){super(file);}
        @Override public FileOutputStream startWrite()throws IOException {if(behavior==0)throw new IOException("synthetic storage start failure");return super.startWrite();}
        @Override public void finishWrite(FileOutputStream output){if(behavior==1)throw new IllegalStateException("synthetic precommit failure");if(behavior==2){super.failWrite(output);return;}super.finishWrite(output);if(behavior==3)throw new IllegalStateException("synthetic postcommit exception");}
    }

    public static final class FakeStore extends KeyStoreSpi {
        private static final Key KEY=new SecretKeySpec(new byte[16],"AES");
        @Override public Key engineGetKey(String a,char[] p){return KEY;}
        @Override public Certificate[] engineGetCertificateChain(String a){return null;}
        @Override public Certificate engineGetCertificate(String a){return null;}
        @Override public Date engineGetCreationDate(String a){return new Date(0);}
        @Override public void engineSetKeyEntry(String a,Key k,char[] p,Certificate[] c){}
        @Override public void engineSetKeyEntry(String a,byte[] k,Certificate[] c){}
        @Override public void engineSetCertificateEntry(String a,Certificate c){}
        @Override public void engineDeleteEntry(String a){}
        @Override public Enumeration<String> engineAliases(){return java.util.Collections.emptyEnumeration();}
        @Override public boolean engineContainsAlias(String a){return true;}
        @Override public int engineSize(){return 1;}
        @Override public boolean engineIsKeyEntry(String a){return true;}
        @Override public boolean engineIsCertificateEntry(String a){return false;}
        @Override public String engineGetCertificateAlias(Certificate c){return null;}
        @Override public void engineStore(java.io.OutputStream s,char[] p){}
        @Override public void engineLoad(java.io.InputStream s,char[] p){}
    }
}
