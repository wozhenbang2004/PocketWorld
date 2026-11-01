package dev.pocketengine.features.ssh;

import static org.junit.Assert.*;
import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.FeatureHost;
import dev.pocketengine.features.attachments.AttachmentRepository;
import dev.pocketengine.features.attachments.AttachmentsModule;
import dev.pocketengine.store.SecretVault;
import dev.pocketengine.store.SettingsStore;
import dev.pocketengine.store.TaskStore;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import org.robolectric.annotation.SQLiteMode;

/** Original SQL/native feature/profile/vault/book with real isolated loopback SSH/SFTP protocol (Apache MINA fixture).
 * Not a real remote account, Android device, deployment, full project build or resume proof. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class SshBinaryOwnerJourneyTest {
    @Before public void keys(){assertNotNull("controlled server must be explicitly supplied",System.getProperty("pocket.ssh.port"));java.security.Security.addProvider(new java.security.Provider("SshBinaryKeyStore","1","Explicit fixture"){{put("KeyStore.AndroidKeyStore",FixtureKeyStore.class.getName());}});}
    @After public void release(){java.security.Security.removeProvider("SshBinaryKeyStore");}
    private static final class Fixture implements AutoCloseable {
        final Context android;final TaskStore tasks;final FeatureContext context;final FeatureHost host;final SshModule ssh;final AttachmentsModule attachments;final ConcurrentMap<String,JSONObject> results=new ConcurrentHashMap<>();String profile;final Path remote=Path.of(System.getProperty("pocket.ssh.workspace"));
        Fixture()throws Exception {
            Context base=RuntimeEnvironment.getApplication();java.io.File files=new java.io.File(base.getFilesDir(),"ssh-binary-"+UUID.randomUUID());assertTrue(files.mkdirs());android=new ContextWrapper(base){@Override public Context getApplicationContext(){return this;}@Override public java.io.File getFilesDir(){return files;}@Override public java.io.File getDatabasePath(String name){java.io.File directory=new java.io.File(files,"database");directory.mkdirs();return new java.io.File(directory,name);}};
            context=new FeatureContext(android,new SettingsStore(android),new SecretVault(android),ignored->{});tasks=new TaskStore(android);AtomicReference<FeatureHost> owner=new AtomicReference<>();host=new FeatureHost(event->{try{if(event.optString("type").equals("feature_event"))owner.get().recordEvent(event);if(event.optString("type").equals("feature_result"))results.put(event.getString("requestId"),new JSONObject(event.toString()));}catch(Exception error){throw new IllegalStateException(error);}},tasks,()->false);owner.set(host);
            FeatureContext actual=new FeatureContext(android,context.settings,context.secrets,event->{host.recordEvent(event);});ssh=new SshModule(actual);attachments=new AttachmentsModule(actual);attachments.setNativeRecordRead(record->{JSONObject source=record.optJSONObject("source");if(source!=null&&!source.optString("taskId").isEmpty()){JSONObject original=tasks.get(source.getString("taskId"),false),origin=original==null?null:original.optJSONObject("origin");if(origin!=null&&origin.optString("kind").equals("group"))throw new SecurityException("original group source is not a private owner attachment");}});ssh.setOwnerAttachments(attachments);ssh.setNativeExecutionAuthority(tasks::requireSshNativeRequest);host.register(ssh);host.register(attachments);
            JSONObject saved=call("saveProfile",new JSONObject().put("name","Controlled SSH binary").put("host","127.0.0.1").put("port",Integer.parseInt(System.getProperty("pocket.ssh.port"))).put("username",System.getProperty("pocket.ssh.user")).put("workspace",System.getProperty("pocket.ssh.remoteRoot",remote.toString())).put("authType","key").put("privateKey",Files.readString(Path.of(System.getProperty("pocket.ssh.key")))));profile=saved.getString("id");JSONObject probe=call("probeHostKey",new JSONObject().put("profileId",profile));assertFalse(probe.getBoolean("credentialsSent"));call("approveHostKey",new JSONObject().put("profileId",profile).put("probeId",probe.getString("probeId")).put("fingerprint",probe.getString("fingerprint")));
        }
        JSONObject profile()throws Exception {return ssh.snapshot().getJSONArray("profiles").getJSONObject(0);}
        JSONObject input(String path)throws Exception{return new JSONObject().put("profileId",profile).put("_profileBinding",profile().getString("bindingHash")).put("path",path).put("timeoutSeconds",20);}
        String submit(String command,JSONObject payload)throws Exception{String request=UUID.randomUUID().toString();host.request(new JSONObject().put("feature","ssh").put("command",command).put("payload",payload).put("requestId",request));return request;}
        JSONObject await(String request)throws Exception {long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(25);JSONObject value;while((value=results.get(request))==null){if(System.nanoTime()>until)throw new AssertionError("original feature request did not complete: "+request);Thread.sleep(5);}return value;}
        JSONObject call(String command,JSONObject payload)throws Exception{JSONObject value=await(submit(command,payload));if(!value.getBoolean("ok"))throw new AssertionError(command+" native host rejected: "+value);return value.getJSONObject("result");}
        JSONObject importBytes(byte[] bytes)throws Exception {return AttachmentRepository.get(android).book.importBytes(new ByteArrayInputStream(bytes),"fixture-byte-source.bin","application/octet-stream",new JSONObject().put("kind","fixture_owner_selected"));}
        @Override public void close()throws Exception {host.close();var database=TaskStore.class.getDeclaredField("db");database.setAccessible(true);synchronized(tasks){((SQLiteDatabase)database.get(tasks)).close();}}
    }
    private static long connectionLines(String log){return log.lines().filter(line->line.contains("Connection from 127.0.0.1")).count();}
    private static byte[] binary(){byte[] value=new byte[1024*1024+37];for(int i=0;i<value.length;i++)value[i]=(byte)((i*31+7)&255);return value;}
    @Test public void originalTrackedBinaryUploadDownloadAndChangedVersionUseRealStreamsHashesAndPrivateArtifacts()throws Exception {
        try(Fixture f=new Fixture()){
            String path="binary-"+UUID.randomUUID()+".bin";byte[] data=binary();JSONObject local=f.importBytes(data),before=f.call("inspectFile",f.input(path));assertEquals("absent",before.getString("version"));assertFalse(before.getBoolean("exists"));
            JSONObject sent=f.call("uploadFile",f.input(path).put("expectedVersion","absent").put("attachmentId",local.getString("id")).put("expectedSha256",local.getString("sha256")).put("acceptCooperativeWrite",true));assertEquals("completed",sent.getString("status"));assertTrue(sent.getBoolean("verifiedBytes"));assertTrue(sent.getBoolean("targetReplacementSent"));assertTrue(sent.getBoolean("stagingCleanupConfirmed"));assertTrue(sent.getBoolean("lockCleanupConfirmed"));assertFalse(sent.getBoolean("verifiedBusinessSuccess"));assertArrayEquals(data,Files.readAllBytes(f.remote.resolve(path)));assertTrue(sent.toString().length()<16000);
            JSONObject task=f.tasks.get(sent.getString("hostTaskId"),false);assertEquals("needs_confirmation",task.getString("status"));assertEquals("completed",task.getString("executionStatus"));assertTrue(task.getJSONObject("evidence").getBoolean("verifiedBytes"));assertEquals("uploadFile",task.getString("command"));assertEquals(local.getString("id"),task.getJSONObject("externalRefs").getJSONObject("sshNativeRequest").getString("attachmentId"));assertEquals(0,task.getInt("coordinatorEpoch"));assertFalse(task.toString().contains(java.util.Base64.getEncoder().encodeToString(data)));
            JSONObject current=f.call("inspectFile",f.input(path));assertEquals("sha256:"+local.getString("sha256"),current.getString("version"));JSONObject received=f.call("downloadFile",f.input(path).put("expectedVersion",current.getString("version")));assertTrue(received.getBoolean("artifactSaved"));JSONObject artifact=received.getJSONObject("attachment");assertEquals(local.getString("sha256"),artifact.getString("sha256"));assertArrayEquals(data,AttachmentRepository.get(f.android).book.readVerifiedSnapshot(artifact.getString("id"),artifact.getString("sha256"),new RunControl()));assertEquals(received.getString("hostTaskId"),artifact.getJSONObject("source").getString("taskId"));assertFalse(artifact.getJSONObject("source").getBoolean("authorshipVerified"));
            byte[] changed=data.clone();changed[100]^=1;Files.write(f.remote.resolve(path),changed);JSONObject conflict=f.call("uploadFile",f.input(path).put("expectedVersion",current.getString("version")).put("attachmentId",local.getString("id")).put("expectedSha256",local.getString("sha256")).put("acceptCooperativeWrite",true));assertEquals("version_conflict",conflict.getString("code"));assertFalse(conflict.getBoolean("targetReplacementSent"));assertArrayEquals(changed,Files.readAllBytes(f.remote.resolve(path)));int count=AttachmentRepository.get(f.android).book.list().length();JSONObject wrongDownload=f.call("downloadFile",f.input(path).put("expectedVersion",current.getString("version")));assertEquals("version_conflict",wrongDownload.getString("code"));assertFalse(wrongDownload.getBoolean("artifactSaved"));assertEquals(count,AttachmentRepository.get(f.android).book.list().length());
            Path huge=f.remote.resolve("oversize-"+UUID.randomUUID());try(java.io.RandomAccessFile sparse=new java.io.RandomAccessFile(huge.toFile(),"rw")){sparse.setLength(DirectSshClient.MAX_TRANSFER+1);}JSONObject over=f.call("inspectFile",f.input(huge.getFileName().toString()));assertEquals("failed",over.getString("status"));assertFalse(over.optBoolean("accepted",true));
        }
    }
    @Test public void cancelledOriginalNativeQueueCannotConnectOrWriteAfterValidSqlAdmissionBeforeActiveRegistration()throws Exception {
        try(Fixture f=new Fixture()){
            String marker="late-"+UUID.randomUUID();JSONObject alive=f.call("exec",f.input("").put("command","printf alive").put("timeoutSeconds",10));assertEquals("completed",alive.getString("status"));assertEquals("alive",alive.getString("stdout"));
            String logBefore=Files.readString(Path.of(System.getProperty("pocket.ssh.log")));CountDownLatch queued=new CountDownLatch(1),release=new CountDownLatch(1);AtomicBoolean first=new AtomicBoolean(true);AtomicReference<String> taskId=new AtomicReference<>(),runId=new AtomicReference<>();f.ssh.setNativeExecutionAuthority((task,run,request)->{f.tasks.requireSshNativeRequest(task,run,request);if(request.command().equals("exec")&&first.compareAndSet(true,false)){taskId.set(task);runId.set(run);queued.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));}});
            String request=f.submit("exec",f.input("").put("command","printf forbidden > "+marker).put("timeoutSeconds",10));try{assertTrue(queued.await(5,TimeUnit.SECONDS));assertEquals(0,f.ssh.snapshot().getJSONArray("operations").length());f.tasks.requestCancel(taskId.get(),runId.get());f.call("cancel",new JSONObject().put("operationId",runId.get()));release.countDown();JSONObject result=f.await(request);assertFalse(result.getBoolean("ok"));assertEquals(0,f.ssh.snapshot().getJSONArray("operations").length());assertFalse(Files.exists(f.remote.resolve(marker)));String after=Files.readString(Path.of(System.getProperty("pocket.ssh.log")));assertEquals("late cancelled native worker opened a new SSH connection",connectionLines(logBefore),connectionLines(after));assertTrue(f.tasks.get(taskId.get(),false).getBoolean("cancelRequested"));}
            finally{release.countDown();}
        }
    }
    @Test public void exactNativeRequestHashAndOriginalOwnerAttachmentScopeAreRequiredWhileModelPortStaysUnexpanded()throws Exception {
        try(Fixture f=new Fixture()){
            JSONObject profile=f.profile();String operation=UUID.randomUUID().toString();JSONObject original=f.input("").put("command","printf approved").put("timeoutSeconds",10).put("_resourceId",SshProfile.hash((profile.getString("hostFingerprint")+"\n"+profile.getString("username")).getBytes(StandardCharsets.UTF_8)));JSONObject row=f.tasks.beginExternal("ssh","exec",operation,original);JSONObject wrong=new JSONObject(original.toString()).put("hostTaskId",row.getString("id")).put("_operationId",row.getString("runId")).put("principalId","owner").put("spaceId","private").put("command","printf not-approved");assertThrows(SecurityException.class,()->f.ssh.handle("exec",wrong));assertEquals(0,f.ssh.snapshot().getJSONArray("operations").length());f.tasks.updateExternal(row.getString("id"),new JSONObject().put("status","failed").put("accepted",false).put("message","fixture confirms exact changed-request preflight denial"));
            RunControl model=new RunControl();model.bindHostRun("model-task","model-run",1);for(String command:new String[]{"inspectFile","downloadFile","uploadFile"})assertThrows(SecurityException.class,()->f.ssh.invokeForHost(command,new JSONObject(),model));
            JSONObject forbidden=AttachmentRepository.get(f.android).book.importBytes(new ByteArrayInputStream(binary()),"group-technical.bin","application/octet-stream",new JSONObject().put("groupSpaceId","native-group"));String path="must-not-upload-"+UUID.randomUUID();JSONObject refusal=f.await(f.submit("uploadFile",f.input(path).put("expectedVersion","absent").put("attachmentId",forbidden.getString("id")).put("expectedSha256",forbidden.getString("sha256")).put("acceptCooperativeWrite",true)));assertFalse(refusal.getBoolean("ok"));assertFalse(Files.exists(f.remote.resolve(path)));assertEquals(0,f.ssh.snapshot().getJSONArray("operations").length());
            // Independently exercise the real transport's dispatched cancellation, not a
            // fake native grant: one staging chunk is sent, then the original control closes it.
            SshProfile pinned=new SshProfile(new JSONArray(Files.readString(f.android.getFilesDir().toPath().resolve("ssh-profiles.json"))).getJSONObject(0));RunControl transport=new RunControl();byte[] body=binary();String cancelledPath="cancelled-"+UUID.randomUUID();
            try(DirectSshClient.Connection connection=DirectSshClient.connect(pinned,new JSONObject(f.context.secrets.get(pinned.credentialSlot())),transport)){
                InputStreamAfterCancel source=new InputStreamAfterCancel(body,transport);JSONObject partial=connection.uploadBinary(cancelledPath,source,body.length,SshProfile.hash(body),"absent",true,()->{},()->{});
                assertEquals("unknown",partial.getString("status"));assertFalse(partial.getBoolean("targetReplacementSent"));assertTrue(partial.getBoolean("partialRemoteFilePossible"));assertTrue(partial.getBoolean("cleanupReviewRequired"));assertFalse(partial.getBoolean("automaticRetry"));assertEquals(65536,partial.getLong("bytesReadFromLocal"));assertFalse(Files.exists(f.remote.resolve(cancelledPath)));assertTrue(transport.isCancelled());
            }
        }
    }
    private static final class InputStreamAfterCancel extends ByteArrayInputStream {private final RunControl original;private int reads;InputStreamAfterCancel(byte[] bytes,RunControl control){super(bytes);original=control;}@Override public synchronized int read(byte[] bytes,int offset,int length){int count=super.read(bytes,offset,length);if(++reads==2)original.cancel();return count;}}
    public static final class FixtureKeyStore extends java.security.KeyStoreSpi {
        private static final javax.crypto.SecretKey KEY=new javax.crypto.spec.SecretKeySpec(new byte[32],"AES");public java.security.Key engineGetKey(String alias,char[] pass){return KEY;}public java.security.cert.Certificate[] engineGetCertificateChain(String alias){return null;}public java.security.cert.Certificate engineGetCertificate(String alias){return null;}public java.util.Date engineGetCreationDate(String alias){return new java.util.Date(0);}public void engineSetKeyEntry(String a,java.security.Key k,char[] p,java.security.cert.Certificate[] c){throw new UnsupportedOperationException();}public void engineSetKeyEntry(String a,byte[] k,java.security.cert.Certificate[] c){throw new UnsupportedOperationException();}public void engineSetCertificateEntry(String a,java.security.cert.Certificate c){throw new UnsupportedOperationException();}public void engineDeleteEntry(String a){throw new UnsupportedOperationException();}public java.util.Enumeration<String> engineAliases(){return java.util.Collections.enumeration(java.util.Collections.singleton("pocketengine.feature-secrets.v1"));}public boolean engineContainsAlias(String a){return a.equals("pocketengine.feature-secrets.v1");}public int engineSize(){return 1;}public boolean engineIsKeyEntry(String a){return engineContainsAlias(a);}public boolean engineIsCertificateEntry(String a){return false;}public String engineGetCertificateAlias(java.security.cert.Certificate c){return null;}public void engineStore(java.io.OutputStream o,char[] p){}public void engineLoad(java.io.InputStream i,char[] p){}
    }
}
