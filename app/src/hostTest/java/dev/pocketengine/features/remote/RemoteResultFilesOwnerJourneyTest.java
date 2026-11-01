package dev.pocketengine.features.remote;

import android.app.Application;
import dev.pocketengine.core.EventSink;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.FeatureHost;
import dev.pocketengine.features.RemoteOwnerWorkflows;
import dev.pocketengine.features.attachments.*;
import dev.pocketengine.features.attachments.documents.DocumentContent;
import dev.pocketengine.store.SecretVault;
import dev.pocketengine.store.SettingsStore;
import dev.pocketengine.store.TaskStore;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.*;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Native SQL/Book/viewer over the production loopback Companion. CLI protocol peer is fixture-only. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class RemoteResultFilesOwnerJourneyTest {
    static final class Fixture implements AutoCloseable {
        final Path root=Files.createTempDirectory("c3-rebuilt-result-files-");
        final Object nativeOwner=new Object();
        final CopyOnWriteArrayList<JSONObject> events=new CopyOnWriteArrayList<>();
        Process server;BufferedReader output;String base,task;Path workspace;
        TaskStore tasks;FeatureHost host;RemoteModule remote;RemoteOwnerWorkflows owner;AttachmentRepository attachments;AttachmentOutcomeHost viewer;
        Fixture()throws Exception {try{
            Path repo=Path.of(System.getProperty("user.dir"));
            server=new ProcessBuilder("/usr/bin/python3","-B","-u",repo.resolve("companion/result_files_fixture_server.py").toString(),root.toString())
                    .directory(repo.resolve("companion").toFile()).redirectError(root.resolve("server-stderr.log").toFile()).start();
            output=new BufferedReader(new InputStreamReader(server.getInputStream(),StandardCharsets.UTF_8));
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(!output.ready()&&server.isAlive()&&System.nanoTime()<until)Thread.sleep(5);
            assertTrue(Files.readString(root.resolve("server-stderr.log")),output.ready());
            JSONObject info=new JSONObject(output.readLine());base=info.getString("baseUrl");workspace=Path.of(info.getString("workspace"));boot(true);
            String request=UUID.randomUUID().toString();
            host.request(new JSONObject().put("feature","remote").put("command","submitJob").put("requestId",request).put("payload",
                    new JSONObject().put("profileId","computer").put("environmentId","workspace").put("worker","codex_app_server").put("readOnly",true).put("prompt","controlled original result fixture")));
            JSONObject response=null;until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(response==null&&System.nanoTime()<until){for(JSONObject event:events)if(request.equals(event.optString("requestId")))response=event;Thread.sleep(5);}
            assertNotNull(events.toString(),response);assertTrue(response.toString(),response.optBoolean("ok"));
            task=response.getJSONObject("result").getString("hostTaskId");settled();
        }catch(Exception|Error failure){try{close();}catch(Exception cleanup){failure.addSuppressed(cleanup);}throw failure;}}
        void boot(boolean first)throws Exception {
            var app=RuntimeEnvironment.getApplication();
            if(first){app.deleteDatabase("pocket-world.db");app.deleteFile("remote-profiles.json");app.deleteFile("remote-operations.json");app.getSharedPreferences("feature_secrets",android.content.Context.MODE_PRIVATE).edit().clear().commit();}
            tasks=new TaskStore(app);host=new FeatureHost(events::add,tasks,()->false);
            EventSink sink=event->{events.add(event);host.recordEvent(event);};
            FeatureContext context=new FeatureContext(app,new SettingsStore(app),new SecretVault(app),sink);
            remote=new RemoteModule(context,(id,address)->"native-result-files-fixture-token-longer-than-32-characters",path->{});
            host.register(remote);if(first){remote.handle("saveProfile",new JSONObject().put("id","computer").put("name","controlled original result host").put("baseUrl",base));
                // Test-only presence record with the injected CredentialReader; not AndroidKeyStore evidence.
                var slot=RemoteModule.class.getDeclaredMethod("slot",String.class,String.class);slot.setAccessible(true);
                app.getSharedPreferences("feature_secrets",android.content.Context.MODE_PRIVATE).edit().putString((String)slot.invoke(null,"computer",base),"fixture-presence-only").commit();}
            owner=new RemoteOwnerWorkflows(host,tasks,sink);owner.setForeground(nativeOwner,true);
            attachments=AttachmentRepository.get(app);
            if(first)for(int i=attachments.book.list().length()-1;i>=0;i--)attachments.book.remove(attachments.book.list().getJSONObject(i).getString("id"));
            viewer=new AttachmentOutcomeHost(tasks,attachments,new AttachmentsModule(context),(command,payload,control)->{throw new AssertionError("No media dispatch");});
        }
        JSONObject open()throws Exception{return owner.handle(nativeOwner,"open",new JSONObject().put("taskId",task).put("windowId",UUID.randomUUID().toString())).getJSONObject("snapshot");}
        JSONObject action(JSONObject review)throws Exception{return new JSONObject().put("taskId",task).put("windowId",review.getString("windowId")).put("reviewId",review.getString("reviewId"));}
        JSONObject call(String command,JSONObject review,JSONObject args)throws Exception {
            JSONObject request=action(review);for(java.util.Iterator<String> keys=args.keys();keys.hasNext();){String key=keys.next();request.put(key,args.get(key));}
            return owner.handle(nativeOwner,command,request);
        }
        JSONObject select(JSONObject review,String path)throws Exception {
            review=call("listResultFiles",review,new JSONObject().put("path","")).getJSONObject("snapshot");
            return call("selectResultFile",review,new JSONObject().put("path",path)).getJSONObject("snapshot");
        }
        JSONObject freeze(JSONObject review)throws Exception {
            JSONObject result=call("captureSelectedSnapshot",review,new JSONObject());
            assertEquals(result.toString(),"receipt",result.getJSONObject("operation").getString("state"));
            return call("refresh",result.getJSONObject("snapshot"),new JSONObject()).getJSONObject("snapshot");
        }
        JSONObject savedSnapshot()throws Exception{return tasks.get(task,false).getJSONArray("remoteResultSnapshots").getJSONObject(0);}
        JSONObject save(JSONObject review)throws Exception {
            return call("saveSnapshot",review,new JSONObject().put("snapshotId",savedSnapshot().getString("snapshotId")).put("path","proof.txt")).getJSONObject("snapshot");
        }
        JSONObject attachment()throws Exception{return tasks.remoteSnapshotAttachments(task).getJSONObject(0).getJSONObject("attachment");}
        int count(String file)throws Exception{return Files.exists(root.resolve(file))?Integer.parseInt(Files.readString(root.resolve(file))):0;}
        void settled()throws Exception {
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(System.nanoTime()<until){JSONObject source=tasks.remoteOwnerTarget(task);
                if(!source.optString("jobId").isEmpty()){remote.handle("getJob",source);if(tasks.get(task,false).optString("executionStatus").equals("completed")){Thread.sleep(25);return;}}Thread.sleep(10);}
            throw new AssertionError(tasks.get(task,false));
        }
        void reopen()throws Exception {owner.setForeground(nativeOwner,false);remote.close();host.close();boot(false);}
        public void close()throws Exception {
            if(owner!=null)owner.setForeground(nativeOwner,false);if(remote!=null)remote.close();if(host!=null)host.close();
            if(server!=null){server.destroy();if(!server.waitFor(3,TimeUnit.SECONDS)){server.destroyForcibly();server.waitFor(2,TimeUnit.SECONDS);}assertFalse(server.isAlive());
                JSONObject released=new JSONObject(Files.readString(root.resolve("resource-release.json")));assertTrue(released.getBoolean("listenerClosed"));assertEquals(0,released.getInt("activeProcesses"));}
            if(output!=null)output.close();
        }
    }
    @Test public void originalDirectoryExactFreezeBookViewerAndSameSnapshotContinuationKeepOneTask()throws Exception {
        try(Fixture f=new Fixture()){
            JSONObject review=f.select(f.open(),"proof.txt");String oldRun=f.tasks.get(f.task,false).getString("runId");
            assertEquals(1,review.getJSONArray("selectedResultFiles").length());
            review=f.freeze(review);JSONObject snapshot=f.savedSnapshot();String immutable=snapshot.toString();
            review=f.save(review);JSONObject file=f.attachment();
            assertEquals(immutable,f.savedSnapshot().toString());assertEquals(1,f.attachments.book.list().length());
            assertEquals("remote_result_snapshot",file.getJSONObject("source").getString("kind"));
            JSONObject outcome=f.viewer.snapshot(f.task,new RunControl());assertEquals(1,outcome.getJSONArray("artifacts").length());assertFalse(outcome.getBoolean("verifiedBusinessSuccess"));
            JSONObject read=f.viewer.readOwnerDocument(f.task,file.getString("id"),file.getString("sha256"),DocumentContent.Request.defaults(),new RunControl());
            assertTrue(read.toString(),read.toString().contains("ORIGINAL IMMUTABLE NATIVE RESULT"));assertFalse(read.has("verifiedBusinessSuccess")&&read.getBoolean("verifiedBusinessSuccess"));
            f.call("continue",review,new JSONObject().put("prompt","Continue only this reviewed result version").put("snapshotId",snapshot.getString("snapshotId")).put("snapshotVersion",snapshot.getString("snapshotVersion")));
            f.settled();assertEquals(1,f.tasks.list().length());assertNotEquals(oldRun,f.tasks.get(f.task,false).getString("runId"));
            assertEquals(snapshot.getString("snapshotVersion"),f.tasks.get(f.task,false).getJSONObject("evidence").getString("resultSnapshotVersion"));
            assertEquals(2,f.count("submit-count"));assertEquals(1,f.count("capture-count"));
        }
    }
    @Test public void reviewedVersionDriftAndUnlistedSecretRefuseWithoutSavingNewBytes()throws Exception {
        try(Fixture f=new Fixture()){
            JSONObject review=f.select(f.open(),"proof.txt"),selected=review.getJSONArray("selectedResultFiles").getJSONObject(0);
            assertThrows(SecurityException.class,()->f.call("selectResultFile",review,new JSONObject().put("path",".env")));
            assertThrows(SecurityException.class,()->f.call("captureSelectedSnapshot",review,new JSONObject().put("expectedFiles",new JSONArray().put(selected))));
            Files.writeString(f.workspace.resolve("proof.txt"),"ACTUAL NEW WORKSPACE VERSION");
            JSONObject result=f.call("captureSelectedSnapshot",review,new JSONObject());
            assertEquals("rejected",result.getJSONObject("operation").getJSONObject("receipt").getString("status"));
            assertEquals(0,f.tasks.get(f.task,false).optJSONArray("remoteResultSnapshots")==null?0:f.tasks.get(f.task,false).getJSONArray("remoteResultSnapshots").length());
            assertEquals(0,f.attachments.book.list().length());assertEquals(1,f.count("capture-count"));
        }
    }
    @Test public void backgroundDuringActualSnapshotGetCannotCommitLateBookBytes()throws Exception {
        try(Fixture f=new Fixture()){
            JSONObject review=f.freeze(f.select(f.open(),"proof.txt"));
            Files.writeString(f.root.resolve("hold-snapshot-file-read"),"hold real HTTP response after byte read");
            ExecutorService worker=Executors.newSingleThreadExecutor();
            try{
                Future<Throwable> result=worker.submit(()->{try{f.save(review);return null;}catch(Throwable failure){return failure;}});
                long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);while(!Files.exists(f.root.resolve("snapshot-read-entered"))&&System.nanoTime()<until)Thread.sleep(5);
                assertTrue(Files.exists(f.root.resolve("snapshot-read-entered")));f.owner.setForeground(f.nativeOwner,false);
                Files.writeString(f.root.resolve("release-snapshot-file-read"),"release original read");
                assertNotNull(result.get(4,TimeUnit.SECONDS));assertEquals(0,f.attachments.book.list().length());assertEquals(0,f.tasks.remoteSnapshotAttachments(f.task).length());
                assertEquals(1,f.count("snapshot-file-read-count"));
            }finally{Files.writeString(f.root.resolve("release-snapshot-file-read"),"cleanup");worker.shutdownNow();assertTrue(worker.awaitTermination(3,TimeUnit.SECONDS));}
        }
    }
    @Test public void committedBookButLostSqlAssociationRecoversSameIdWithoutSecondGet()throws Exception {
        try(Fixture f=new Fixture()){
            JSONObject review=f.freeze(f.select(f.open(),"proof.txt"));
            java.lang.reflect.Field field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);
            android.database.sqlite.SQLiteDatabase db=(android.database.sqlite.SQLiteDatabase)field.get(f.tasks);
            db.execSQL("CREATE TRIGGER c3_deny_association BEFORE INSERT ON tasks WHEN NEW.task_id='"+f.task+"' AND instr(NEW.document,'remoteSnapshotAttachments')>0 BEGIN SELECT RAISE(ABORT,'controlled association ACK failure'); END");
            assertThrows(Exception.class,()->f.save(review));assertEquals(1,f.attachments.book.list().length());assertEquals(0,f.tasks.remoteSnapshotAttachments(f.task).length());
            String id=f.attachments.book.list().getJSONObject(0).getString("id");int reads=f.count("snapshot-file-read-count");db.execSQL("DROP TRIGGER c3_deny_association");
            f.save(review);assertEquals(id,f.attachment().getString("id"));assertEquals(reads,f.count("snapshot-file-read-count"));assertEquals(1,f.attachments.book.list().length());
        }
    }
    @Test public void localByteTamperIsInputChangedAndNeverTriggersAutomaticRemoteReplacement()throws Exception {
        try(Fixture f=new Fixture()){
            JSONObject review=f.save(f.freeze(f.select(f.open(),"proof.txt"))),file=f.attachment();int reads=f.count("snapshot-file-read-count");
            byte[] changed=Files.readAllBytes(f.attachments.book.file(file.getString("id")).toPath());changed[0]^=1;Files.write(f.attachments.book.file(file.getString("id")).toPath(),changed);
            JSONObject result=f.viewer.readOwnerDocument(f.task,file.getString("id"),file.getString("sha256"),DocumentContent.Request.defaults(),new RunControl());
            assertEquals("input_changed",result.getString("status"));assertEquals(0,result.getJSONArray("chunks").length());assertFalse(result.has("readReceipt"));
            assertThrows(Exception.class,()->f.save(review));assertEquals(reads,f.count("snapshot-file-read-count"));assertEquals(1,f.attachments.book.list().length());
        }
    }
    @Test public void reopenedSqlAndOfflineBookStillOpenOriginalFrozenPrivateResult()throws Exception {
        try(Fixture f=new Fixture()){
            f.save(f.freeze(f.select(f.open(),"proof.txt")));JSONObject file=f.attachment();String id=file.getString("id");
            f.reopen();f.server.destroy();assertTrue(f.server.waitFor(3,TimeUnit.SECONDS));
            assertEquals(id,f.attachment().getString("id"));JSONObject read=f.viewer.readOwnerDocument(f.task,id,file.getString("sha256"),DocumentContent.Request.defaults(),new RunControl());
            assertTrue(read.toString().contains("ORIGINAL IMMUTABLE NATIVE RESULT"));assertEquals(1,f.tasks.list().length());
        }
    }
}
