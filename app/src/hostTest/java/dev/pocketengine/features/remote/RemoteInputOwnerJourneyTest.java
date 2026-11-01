package dev.pocketengine.features.remote;

import android.app.Application;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
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
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
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

/** Real original SQL/Book/HTTP/binary/worker admission; the installed coding CLI peers are fixtures. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class RemoteInputOwnerJourneyTest {
    static final class Fixture implements AutoCloseable {
        final Path root=Files.createTempDirectory("c3-rebuilt-inputs-native-");
        final Object nativeOwner=new Object();
        final CopyOnWriteArrayList<JSONObject> events=new CopyOnWriteArrayList<>();
        final CountDownLatch createEntered=new CountDownLatch(1),releaseCreate=new CountDownLatch(1);
        volatile boolean holdCreate;
        Process server;BufferedReader output;String base,task,window;Path workspace;
        TaskStore tasks;FeatureHost host;RemoteModule remote;RemoteOwnerWorkflows owner;
        AttachmentRepository attachments;AttachmentsModule documents;AttachmentOutcomeHost viewer;
        Fixture()throws Exception {try{
            Path repo=Path.of(System.getProperty("user.dir"));
            server=new ProcessBuilder("/usr/bin/python3","-B","-u",repo.resolve("companion/input_files_fixture_server.py").toString(),root.toString())
                    .directory(repo.resolve("companion").toFile()).redirectError(root.resolve("server-stderr.log").toFile()).start();
            output=new BufferedReader(new InputStreamReader(server.getInputStream(),StandardCharsets.UTF_8));
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(6);
            while(!output.ready()&&server.isAlive()&&System.nanoTime()<until)Thread.sleep(5);
            assertTrue(Files.readString(root.resolve("server-stderr.log")),output.ready());
            JSONObject info=new JSONObject(output.readLine());base=info.getString("baseUrl");workspace=Path.of(info.getString("workspace"));boot(true);
        }catch(Exception|Error failure){try{close();}catch(Exception cleanup){failure.addSuppressed(cleanup);}throw failure;}}
        void boot(boolean first)throws Exception {
            var app=RuntimeEnvironment.getApplication();
            if(first){app.deleteDatabase("pocket-world.db");app.deleteFile("remote-profiles.json");app.deleteFile("remote-operations.json");app.getSharedPreferences("feature_secrets",Context.MODE_PRIVATE).edit().clear().commit();}
            tasks=new TaskStore(app);host=new FeatureHost(events::add,tasks,()->false);
            EventSink sink=event->{events.add(event);host.recordEvent(event);};
            FeatureContext context=new FeatureContext(app,new SettingsStore(app),new SecretVault(app),sink);
            remote=new RemoteModule(context,(id,address)->"native-input-files-fixture-token-longer-than-32-characters",path->{
                if(path.equals("/v1/jobs")&&task!=null){
                    assertNotNull("Original SQL task must precede the first real HTTP create",tasks.get(task,false));
                    assertEquals("native_remote_private_inputs",tasks.get(task,false).getJSONObject("origin").getString("kind"));
                    if(holdCreate){createEntered.countDown();if(!releaseCreate.await(6,TimeUnit.SECONDS))throw new AssertionError("Fixture create gate not released");}
                }
            });
            host.register(remote);documents=new AttachmentsModule(context);host.register(documents);
            if(first){
                remote.handle("saveProfile",new JSONObject().put("id","computer").put("name","Controlled input fixture").put("baseUrl",base));
                // Test-only presence record + injected CredentialReader. Not AndroidKeyStore or real credential evidence.
                var slot=RemoteModule.class.getDeclaredMethod("slot",String.class,String.class);slot.setAccessible(true);
                app.getSharedPreferences("feature_secrets",Context.MODE_PRIVATE).edit().putString((String)slot.invoke(null,"computer",base),"fixture-presence-only").commit();
            }
            owner=new RemoteOwnerWorkflows(host,tasks,sink);owner.setForeground(nativeOwner,true);attachments=AttachmentRepository.get(app);
            if(first)for(int i=attachments.book.list().length()-1;i>=0;i--)attachments.book.remove(attachments.book.list().getJSONObject(i).getString("id"));
            viewer=new AttachmentOutcomeHost(tasks,attachments,documents,(command,payload,control)->{throw new AssertionError("No media dispatch");});
        }
        JSONObject originalRequest(String worker)throws Exception {
            String binding=remote.profileBindingHash("computer");
            JSONObject env=RemoteTaskRequest.environment(remote.handle("environments",new JSONObject().put("profileId","computer")),"workspace",worker);
            return new JSONObject().put("profileId","computer").put("bindingHash",binding).put("environmentId","workspace").put("environmentBinding",env.getString("bindingHash"))
                    .put("worker",worker).put("readOnly",worker.equals("codex_app_server")).put("timeoutSeconds",30).put("prompt","Read only the explicitly selected input bytes and report their hashes");
        }
        JSONObject open(String worker)throws Exception {
            task=UUID.randomUUID().toString();window=UUID.randomUUID().toString();
            return owner.handle(nativeOwner,"openInputs",new JSONObject().put("windowId",window).put("taskId",task).put("request",originalRequest(worker))).getJSONObject("snapshot");
        }
        JSONObject action(String command,JSONObject fields)throws Exception {
            JSONObject args=new JSONObject().put("taskId",task).put("windowId",window);
            for(var keys=fields.keys();keys.hasNext();){String key=keys.next();args.put(key,fields.get(key));}
            return owner.handle(nativeOwner,command,args).getJSONObject("snapshot");
        }
        JSONObject file(String name,byte[] bytes,JSONObject source)throws Exception {return attachments.book.importBytes(new ByteArrayInputStream(bytes),name,"application/octet-stream",source);}
        JSONObject select(JSONObject... files)throws Exception {
            JSONArray refs=new JSONArray();for(JSONObject file:files)refs.put(new JSONObject().put("id",file.getString("id")).put("sha256",file.getString("sha256")));
            action("selectInputs",new JSONObject().put("files",refs));return action("reviewInputs",new JSONObject());
        }
        JSONObject confirm(JSONObject review)throws Exception {return action("confirmInputs",new JSONObject().put("digest",review.getJSONObject("review").getString("digest")).put("billingAcknowledged",true));}
        JSONObject query()throws Exception{return action("queryInputs",new JSONObject());}
        JSONObject job()throws Exception {JSONObject source=tasks.remoteOwnerTarget(task);return remote.handle("getJob",source);}
        void settled()throws Exception {
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(7);
            while(System.nanoTime()<until){JSONObject actual=job();if(actual.optString("status").equals("completed")&&actual.optBoolean("dispatchFenceFinal")){tasks.reconcileRemoteInputs(task,actual);return;}Thread.sleep(10);}
            throw new AssertionError(tasks.get(task,false));
        }
        int count(String name)throws Exception{return Files.exists(root.resolve(name))?Integer.parseInt(Files.readString(root.resolve(name))):0;}
        void waitMarker(String marker)throws Exception {long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(!Files.exists(root.resolve(marker))&&System.nanoTime()<until)Thread.sleep(5);assertTrue(marker,Files.exists(root.resolve(marker)));}
        void reopenOriginal()throws Exception {owner.setForeground(nativeOwner,false);owner.close(window);remote.close();host.close();boot(false);window=UUID.randomUUID().toString();owner.handle(nativeOwner,"openInputs",new JSONObject().put("windowId",window).put("taskId",task));}
        SQLiteDatabase db()throws Exception {var field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);return (SQLiteDatabase)field.get(tasks);}
        public void close()throws Exception {
            releaseCreate.countDown();Files.writeString(root.resolve("release-upload-response"),"cleanup original fixture barrier");
            if(owner!=null){owner.setForeground(nativeOwner,false);if(window!=null)owner.close(window);}if(remote!=null)remote.close();if(host!=null)host.close();
            if(server!=null){server.destroy();if(!server.waitFor(4,TimeUnit.SECONDS)){server.destroyForcibly();server.waitFor(2,TimeUnit.SECONDS);}assertFalse(server.isAlive());
                if(Files.exists(root.resolve("resource-release.json"))){JSONObject proof=new JSONObject(Files.readString(root.resolve("resource-release.json")));assertTrue(proof.getBoolean("listenerClosed"));assertEquals(0,proof.getInt("activeProcesses"));}}
            if(output!=null)output.close();
        }
    }
    @Test public void explicitBookBinaryAndEmptyInputsReachActualCodexAndClaudeOnlyAfterReadyThenOriginalViewer()throws Exception {
        try(Fixture f=new Fixture()){
            byte[] raw=new byte[513];for(int i=0;i<raw.length;i++)raw[i]=(byte)i;
            JSONObject binary=f.file("真实原件.bin",raw,new JSONObject().put("kind","native_fixture_private"));
            JSONObject empty=f.file("empty.txt",new byte[0],new JSONObject().put("kind","native_fixture_private"));
            JSONObject otherOriginalJob=null;
            for(String worker:new String[]{"codex_app_server","claude"}){
                f.open(worker);JSONObject review=f.select(binary,empty);assertNull(f.tasks.get(f.task,false));assertEquals(0,f.count("submit-count")-(worker.equals("claude")?1:0));
                JSONObject response;
                if(worker.equals("codex_app_server")){
                    Files.writeString(f.root.resolve("hold-upload-response"),"Original GET settles the upload before the actual PUT response");
                    ExecutorService io=Executors.newSingleThreadExecutor();try{
                        Future<JSONObject> submitted=io.submit(()->f.confirm(review));f.waitMarker("upload-response-entered");
                        f.tasks.reconcileRemoteInputs(f.task,f.job());assertFalse("Own accepted receipt must not become a false unknown barrier",f.tasks.hasUnresolved(f.task));
                        JSONObject current=f.tasks.get(f.task,false),held=current.getJSONObject("remoteInputState"),oldCreate=null;
                        JSONArray operations=current.getJSONArray("operations");for(int i=0;i<operations.length();i++){JSONObject op=operations.getJSONObject(i);if(op.getJSONObject("arguments").optString("action").equals("CREATE"))oldCreate=op.getJSONObject("receipt");}
                        assertNotNull(oldCreate);assertTrue(held.getLong("revision")>oldCreate.getJSONObject("inputState").getLong("revision"));
                        f.tasks.reconcileRemoteInputs(f.task,oldCreate);JSONObject preserved=f.tasks.get(f.task,false).getJSONObject("remoteInputState");
                        assertEquals(held.getLong("revision"),preserved.getLong("revision"));assertEquals("verified",preserved.getJSONArray("files").getJSONObject(0).getString("state"));
                        Files.writeString(f.root.resolve("send-old-input-with-new-job-message"),"Do not discard the new job event when its input component is old");
                        try{f.tasks.reconcileRemoteInputs(f.task,f.job());JSONObject merged=f.tasks.get(f.task,false);
                            assertTrue(merged.toString(),merged.getString("summary").contains("NEW ORIGINAL JOB EVENT WITH EARLIER INPUT COMPONENT"));
                            assertEquals(held.getLong("revision"),merged.getJSONObject("remoteInputState").getLong("revision"));
                            assertEquals(held.getLong("revision"),merged.getJSONObject("evidence").getJSONObject("inputState").getLong("revision"));
                        }finally{Files.deleteIfExists(f.root.resolve("send-old-input-with-new-job-message"));}
                        Files.writeString(f.root.resolve("release-upload-response"),"Allow the one original upload response to finish");response=submitted.get(6,TimeUnit.SECONDS);
                    }finally{Files.writeString(f.root.resolve("release-upload-response"),"cleanup");io.shutdownNow();assertTrue(io.awaitTermination(3,TimeUnit.SECONDS));}
                }else response=f.confirm(review);
                assertTrue(response.toString(),response.getBoolean("registered"));f.settled();
                JSONObject task=f.tasks.get(f.task,false),state=task.getJSONObject("remoteInputState");assertTrue(state.toString(),state.getBoolean("workerStarted"));assertEquals(513,state.getInt("verifiedBytes"));
                if(otherOriginalJob!=null){JSONObject wrong=otherOriginalJob;assertTrue(wrong.toString(),wrong.getJSONObject("inputState").getLong("revision")>state.getLong("revision"));
                    assertThrows(SecurityException.class,()->f.tasks.reconcileRemoteInputs(f.task,wrong));assertEquals(state.getLong("revision"),f.tasks.get(f.task,false).getJSONObject("remoteInputState").getLong("revision"));}
                if(worker.equals("codex_app_server")){
                    Files.writeString(f.root.resolve("same-input-revision-conflict"),"Authenticated server conflict, never JSON-created authority");
                    try{assertThrows(SecurityException.class,()->f.tasks.reconcileRemoteInputs(f.task,f.job()));}
                    finally{Files.deleteIfExists(f.root.resolve("same-input-revision-conflict"));}
                }
                JSONArray summary=new JSONArray(Files.readString(f.workspace.resolve("input-summary.json")));assertEquals(2,summary.length());
                assertEquals(binary.getString("sha256"),summary.getJSONObject(0).getString("sha256"));assertEquals(513,summary.getJSONObject(0).getInt("bytes"));assertEquals(0,summary.getJSONObject(1).getInt("bytes"));
                String originalTask=f.task,id=UUID.randomUUID().toString();JSONObject page=f.owner.handle(f.nativeOwner,"open",new JSONObject().put("taskId",originalTask).put("windowId",id)).getJSONObject("snapshot");
                JSONObject base=new JSONObject().put("taskId",originalTask).put("windowId",id).put("reviewId",page.getString("reviewId"));
                page=f.owner.handle(f.nativeOwner,"listResultFiles",new JSONObject(base.toString()).put("path","")).getJSONObject("snapshot");base.put("reviewId",page.getString("reviewId"));
                page=f.owner.handle(f.nativeOwner,"selectResultFile",new JSONObject(base.toString()).put("path","proof.txt")).getJSONObject("snapshot");base.put("reviewId",page.getString("reviewId"));
                page=f.owner.handle(f.nativeOwner,"captureSelectedSnapshot",base).getJSONObject("snapshot");base.put("reviewId",page.getString("reviewId"));
                page=f.owner.handle(f.nativeOwner,"refresh",base).getJSONObject("snapshot");base.put("reviewId",page.getString("reviewId"));
                JSONObject frozen=f.tasks.get(originalTask,false).getJSONArray("remoteResultSnapshots").getJSONObject(0);
                f.owner.handle(f.nativeOwner,"saveSnapshot",new JSONObject(base.toString()).put("snapshotId",frozen.getString("snapshotId")).put("path","proof.txt"));
                JSONObject saved=f.tasks.remoteSnapshotAttachments(originalTask).getJSONObject(0).getJSONObject("attachment");
                JSONObject document=f.viewer.readOwnerDocument(originalTask,saved.getString("id"),saved.getString("sha256"),DocumentContent.Request.defaults(),new RunControl());
                assertTrue(document.toString(),document.toString().contains("INPUTS ACTUALLY READ BY CONTROLLED CLI"));otherOriginalJob=f.job();f.owner.close(id);f.owner.close(f.window);
            }
            assertEquals(2,f.count("submit-count"));assertEquals(4,f.count("put-count"));assertEquals(2,f.count("start-count"));assertEquals(2,f.tasks.list().length());
        }
    }
    @Test public void lostUploadAckAndBackgroundReopenQueryOriginalBytesWithoutSecondBodyOrJob()throws Exception {
        try(Fixture f=new Fixture()){
            JSONObject file=f.file("binary.bin",new byte[]{0,1,2,-1},new JSONObject());f.open("codex_app_server");JSONObject review=f.select(file);
            Files.writeString(f.root.resolve("hold-upload-response"),"hold after actual hash accepted");Files.writeString(f.root.resolve("drop-upload-ack"),"drop original reply");
            ExecutorService worker=Executors.newSingleThreadExecutor();try{
                Future<JSONObject> confirm=worker.submit(()->f.confirm(review));f.waitMarker("upload-response-entered");f.owner.setForeground(f.nativeOwner,false);
                Files.writeString(f.root.resolve("release-upload-response"),"release original ACK");confirm.get(6,TimeUnit.SECONDS);
                assertEquals(0,f.count("start-count"));assertEquals(1,f.count("put-count"));assertFalse(Files.exists(f.workspace.resolve("codex-turn-count")));
                f.reopenOriginal();JSONObject queried=f.query();assertEquals("ready",queried.getJSONObject("inputState").getString("phase"));
                assertFalse(f.tasks.hasUnresolved(f.task));f.confirm(f.action("reviewInputs",new JSONObject()));f.settled();
                assertEquals(1,f.count("submit-count"));assertEquals(1,f.count("put-count"));assertEquals(1,f.count("start-count"));assertEquals("1",Files.readString(f.workspace.resolve("codex-turn-count")));
            }finally{Files.writeString(f.root.resolve("release-upload-response"),"cleanup");worker.shutdownNow();assertTrue(worker.awaitTermination(3,TimeUnit.SECONDS));}
        }
    }
    @Test public void backgroundBeforeNativeCreateFenceHasDurablePreparedProofAndZeroHttpBodyOrWorker()throws Exception {
        try(Fixture f=new Fixture()){
            JSONObject file=f.file("queued.bin",new byte[]{0,1},new JSONObject());f.open("codex_app_server");JSONObject review=f.select(file);f.holdCreate=true;
            ExecutorService worker=Executors.newSingleThreadExecutor();try{
                Future<JSONObject> confirm=worker.submit(()->f.confirm(review));assertTrue(f.createEntered.await(4,TimeUnit.SECONDS));assertNotNull(f.tasks.get(f.task,false));
                f.owner.setForeground(f.nativeOwner,false);f.releaseCreate.countDown();confirm.get(4,TimeUnit.SECONDS);assertEquals(0,f.count("submit-count"));assertEquals(0,f.count("put-count"));assertEquals(0,f.count("start-count"));
                try(Cursor rows=f.db().rawQuery("SELECT state,receipt FROM operations WHERE task_id=? AND tool='remote_private_input'",new String[]{f.task})){
                    assertTrue(rows.moveToFirst());assertEquals("receipt",rows.getString(0));assertTrue(new JSONObject(rows.getString(1)).getBoolean("notDispatchedConfirmed"));assertFalse(rows.moveToNext());}
                f.owner.setForeground(f.nativeOwner,true);f.action("cancelInputs",new JSONObject());assertEquals("cancelled",f.tasks.get(f.task,false).getString("status"));assertFalse(f.tasks.hasUnresolved(f.task));
            }finally{f.releaseCreate.countDown();worker.shutdownNow();assertTrue(worker.awaitTermination(3,TimeUnit.SECONDS));}
        }
    }
    @Test public void groupCopyChangedBookForgedJsonAndRotatedProfileNeverAcquireExternalFileDispatch()throws Exception {
        try(Fixture f=new Fixture()){
            JSONObject group=f.file("group.bin",new byte[]{1},new JSONObject().put("groupSpaceId","external-group"));f.open("codex_app_server");
            assertThrows(SecurityException.class,()->f.select(group));JSONObject changed=f.file("changed.bin",new byte[]{1,2},new JSONObject());
            Files.write(f.attachments.book.file(changed.getString("id")).toPath(),new byte[]{3,4});assertThrows(Exception.class,()->f.select(changed));
            JSONObject healthy=f.file("original.bin",new byte[]{5,6},new JSONObject()),review=f.select(healthy);
            assertThrows(SecurityException.class,()->f.action("confirmInputs",new JSONObject().put("digest",review.getJSONObject("review").getString("digest")).put("billingAcknowledged",true).put("files",new JSONArray())));
            f.remote.handle("saveProfile",new JSONObject().put("id","computer").put("name","Changed real native profile generation").put("baseUrl",f.base));
            f.confirm(review);assertNull(f.tasks.get(f.task,false));assertEquals(0,f.count("submit-count"));assertEquals(0,f.count("put-count"));assertEquals(0,f.count("start-count"));
        }
    }
    @Test public void oldServerCapabilityAndGenericJsonCannotSilentlyDropInputsWhileOrdinaryTextStillWorks()throws Exception {
        try(Fixture f=new Fixture()){
            Files.writeString(f.root.resolve("old-input-capability"),"simulate older advertised original server");assertThrows(SecurityException.class,()->f.open("codex_app_server"));
            assertThrows(SecurityException.class,()->f.remote.handle("submitJob",new JSONObject().put("profileId","computer").put("environmentId","workspace").put("worker","codex_app_server").put("prompt","Do not drop private input fields").put("inputProtocol",RemoteTaskRequest.INPUT_PROTOCOL).put("inputFiles",new JSONArray())));
            assertEquals(0,f.count("submit-count"));assertEquals(0,f.tasks.list().length());f.task=null;
            JSONObject created=f.remote.handle("submitJob",new JSONObject().put("profileId","computer").put("environmentId","workspace").put("worker","codex_app_server").put("readOnly",true).put("prompt","Ordinary no-attachment request"));
            String job=created.getString("id");long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);JSONObject actual=created;
            while(!actual.optString("status").equals("completed")&&System.nanoTime()<until){Thread.sleep(10);actual=f.remote.handle("getJob",new JSONObject().put("profileId","computer").put("jobId",job));}
            assertEquals(actual.toString(),"completed",actual.getString("status"));assertFalse(actual.has("inputState"));assertEquals(1,f.count("submit-count"));assertEquals(0,f.count("put-count"));
        }
    }
}
