package dev.pocketengine.features.remote;

import android.app.Application;
import android.content.ContentValues;
import dev.pocketengine.core.EventSink;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.FeatureHost;
import dev.pocketengine.features.RemoteOwnerWorkflows;
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
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Original SQL/owner/HTTP/Companion/real local process; coding CLI is explicitly synthetic. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class RemoteColdRecoveryOwnerJourneyTest {
    static final class Fixture implements AutoCloseable {
        final Path root=Files.createTempDirectory("c3-cold-original-owner-");final Object nativeOwner=new Object();
        final CopyOnWriteArrayList<JSONObject> events=new CopyOnWriteArrayList<>();Process server;BufferedReader output;String base,task;
        TaskStore tasks;FeatureHost host;RemoteModule remote;RemoteOwnerWorkflows owner;
        Fixture()throws Exception{try{
            Path repo=Path.of(System.getProperty("user.dir"));server=new ProcessBuilder("/usr/bin/python3","-B","-u",repo.resolve("companion/cold_recovery_fixture_server.py").toString(),root.toString()).directory(repo.resolve("companion").toFile()).redirectError(root.resolve("server-stderr.log").toFile()).start();output=new BufferedReader(new InputStreamReader(server.getInputStream(),StandardCharsets.UTF_8));
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(!output.ready()&&server.isAlive()&&System.nanoTime()<until)Thread.sleep(5);assertTrue(Files.readString(root.resolve("server-stderr.log")),output.ready());base=new JSONObject(output.readLine()).getString("baseUrl");boot(true);
            String request=UUID.randomUUID().toString();host.request(new JSONObject().put("feature","remote").put("command","submitJob").put("requestId",request).put("payload",new JSONObject().put("profileId","computer").put("environmentId","workspace").put("worker","codex_app_server").put("prompt","Controlled cold original task").put("timeoutSeconds",30)));
            JSONObject response=null;until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(response==null&&System.nanoTime()<until){for(JSONObject event:events)if(request.equals(event.optString("requestId")))response=event;Thread.sleep(5);}assertNotNull(events.toString(),response);assertTrue(response.toString(),response.optBoolean("ok"));task=response.getJSONObject("result").getString("hostTaskId");marker("simulate-cold-restart");waitFile("restart-done");
        }catch(Exception|Error failure){try{close();}catch(Exception cleanup){failure.addSuppressed(cleanup);}throw failure;}}
        void boot(boolean first)throws Exception{var app=RuntimeEnvironment.getApplication();if(first){app.deleteDatabase("pocket-world.db");app.deleteFile("remote-profiles.json");app.deleteFile("remote-operations.json");}tasks=new TaskStore(app);host=new FeatureHost(events::add,tasks,()->false);EventSink sink=event->{events.add(event);host.recordEvent(event);};remote=new RemoteModule(new FeatureContext(app,new SettingsStore(app),new SecretVault(app),sink),(id,url)->"native-cold-recovery-controlled-token-more-than-32-characters",path->{});host.register(remote);if(first)remote.handle("saveProfile",new JSONObject().put("id","computer").put("name","controlled original cold host").put("baseUrl",base));owner=new RemoteOwnerWorkflows(host,tasks,sink);owner.setForeground(nativeOwner,true);}
        JSONObject open()throws Exception{return owner.handle(nativeOwner,"open",new JSONObject().put("windowId",UUID.randomUUID().toString()).put("taskId",task)).getJSONObject("snapshot");}
        JSONObject action(JSONObject view)throws Exception{return new JSONObject().put("windowId",view.getString("windowId")).put("taskId",task).put("reviewId",view.getString("reviewId"));}
        JSONObject refresh(JSONObject view)throws Exception{return owner.handle(nativeOwner,"refresh",action(view)).getJSONObject("snapshot");}
        JSONObject inspect(JSONObject view)throws Exception{return owner.handle(nativeOwner,"inspectRecovery",action(view)).getJSONObject("snapshot");}
        JSONObject source()throws Exception{return tasks.remoteOwnerTarget(task);}
        int count(String name)throws Exception{return Files.exists(root.resolve(name))?Integer.parseInt(Files.readString(root.resolve(name))):0;}
        void marker(String name)throws Exception{Files.writeString(root.resolve(name),"controlled fault boundary");}
        void waitFile(String name)throws Exception{long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(!Files.exists(root.resolve(name))&&System.nanoTime()<until)Thread.sleep(5);assertTrue(name,Files.exists(root.resolve(name)));}
        void reopen()throws Exception{owner.setForeground(nativeOwner,false);remote.close();host.close();boot(false);}
        void completed()throws Exception{long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(System.nanoTime()<until){JSONObject source=source();if(!source.optString("jobId").isEmpty())remote.handle("getJob",source);if(source().optString("executorStatus").equals("completed"))return;Thread.sleep(5);}fail(tasks.get(task,false).toString());}
        public void close()throws Exception{if(owner!=null)owner.setForeground(nativeOwner,false);if(remote!=null)remote.close();if(host!=null)host.close();if(server!=null){server.destroy();if(!server.waitFor(6,TimeUnit.SECONDS)){server.destroyForcibly();server.waitFor(2,TimeUnit.SECONDS);}assertFalse(server.isAlive());JSONObject released=new JSONObject(Files.readString(root.resolve("resource-release.json")));assertTrue(released.getBoolean("listenerClosed"));for(String key:new String[]{"activeProcesses","activeDrivers","resources"})assertEquals(0,released.getInt(key));}if(output!=null)output.close();}
    }
    @Test public void actualColdProofNewOwnerBudgetAndSameTaskNewRunContinueExactSessionWithoutWashingHistoricalResults()throws Exception{
        try(Fixture f=new Fixture()){
            JSONObject first=f.open(),old=first.getJSONObject("source");assertEquals("interrupted",old.getString("executorStatus"));assertFalse(first.getBoolean("canContinue"));String run=old.getString("runId"),job=old.getString("jobId"),operation=old.getString("jobOperationId");int epoch=old.getInt("coordinatorEpoch");
            JSONObject proof=f.inspect(first);assertTrue(proof.toString(),proof.getBoolean("canContinue"));assertTrue(RemoteColdRecovery.source(proof.getJSONObject("source")));assertEquals("interrupted",f.tasks.get(f.task,false).getString("status"));assertEquals(1,f.count("submit-count"));assertEquals(1,f.count("recovery-read-count"));assertFalse(proof.getJSONObject("source").getJSONObject("recoveryProof").getBoolean("businessSuccessVerified"));
            assertThrows(SecurityException.class,()->f.owner.handle(f.nativeOwner,"continue",f.action(proof).put("prompt","New explicit requirement")));assertEquals(run,f.tasks.get(f.task,false).getString("runId"));assertEquals(1,f.count("submit-count"));
            JSONObject current=f.refresh(proof);f.owner.handle(f.nativeOwner,"continue",f.action(current).put("prompt","New explicit requirement").put("timeoutSeconds",10).put("recoveryOwnerAcknowledged",true));f.completed();JSONObject next=f.source();assertEquals(f.task,next.getString("taskId"));assertNotEquals(run,next.getString("runId"));assertEquals(epoch+1,next.getInt("coordinatorEpoch"));assertNotEquals(job,next.getString("jobId"));assertNotEquals(operation,next.getString("jobOperationId"));assertEquals(old.getString("threadId"),next.getString("threadId"));assertEquals(2,f.count("submit-count"));assertEquals(1,f.tasks.list().length());
            JSONObject prior=RemoteTransport.request(f.base,"native-cold-recovery-controlled-token-more-than-32-characters","/v1/jobs/"+job,null,5);assertEquals("interrupted",prior.getString("status"));assertFalse(prior.getJSONObject("recoveryProof").getBoolean("internalUsageKnown"));f.reopen();JSONObject reopened=f.open();assertEquals(next.getString("jobId"),reopened.getJSONObject("source").getString("jobId"));assertEquals(2,f.count("submit-count"));
        }
    }
    @Test public void exactProofCannotBypassDifferentTaskOriginalUnknownOrNativeCancellation()throws Exception{
        try(Fixture f=new Fixture()){
            JSONObject proof=f.inspect(f.open()),source=proof.getJSONObject("source"),wrong=new JSONObject(source.toString());wrong.getJSONObject("recoveryProof").put("jobId","another-job");assertFalse(RemoteColdRecovery.source(wrong));assertThrows(SecurityException.class,()->f.tasks.checkRemoteContinuationSource(wrong));
            var dbField=TaskStore.class.getDeclaredField("db");dbField.setAccessible(true);var db=(android.database.sqlite.SQLiteDatabase)dbField.get(f.tasks);String unknown=UUID.randomUUID().toString();synchronized(f.tasks){ContentValues row=new ContentValues();row.put("operation_id",unknown);row.put("task_id",f.task);row.put("run_id",source.getString("runId"));row.put("tool","fixture_unrelated_write");row.put("arguments","{}");row.put("state","unknown");row.put("created_at",System.currentTimeMillis());db.insertOrThrow("operations",null,row);try{assertThrows(SecurityException.class,()->f.tasks.checkRemoteContinuationSource(source));}finally{db.delete("operations","operation_id=?",new String[]{unknown});}}
            f.tasks.requestCancel(f.task,source.getString("runId"));assertThrows(SecurityException.class,()->f.owner.handle(f.nativeOwner,"continue",f.action(proof).put("prompt","Not authorized").put("timeoutSeconds",10).put("recoveryOwnerAcknowledged",true)));assertThrows(SecurityException.class,()->f.inspect(proof));assertEquals(1,f.count("submit-count"));assertEquals(1,f.count("recovery-read-count"));
        }
    }
    @Test public void dispatchedReadAfterBackgroundNeverCreatesAReviewRunOrTurn()throws Exception{
        try(Fixture f=new Fixture()){
            JSONObject first=f.open();String run=first.getJSONObject("source").getString("runId");f.marker("hold-recovery-read");ExecutorService reader=Executors.newSingleThreadExecutor();try{Future<JSONObject> late=reader.submit(()->f.inspect(first));f.waitFile("recovery-read-held");f.owner.setForeground(f.nativeOwner,false);f.marker("release-recovery-read");assertThrows(ExecutionException.class,()->late.get(5,TimeUnit.SECONDS));assertEquals(run,f.tasks.get(f.task,false).getString("runId"));assertEquals(1,f.count("submit-count"));assertThrows(SecurityException.class,()->f.owner.handle(f.nativeOwner,"continue",f.action(first).put("prompt","No stale owner window").put("timeoutSeconds",10).put("recoveryOwnerAcknowledged",true)));}finally{f.marker("release-recovery-read");reader.shutdownNow();assertTrue(reader.awaitTermination(3,TimeUnit.SECONDS));}
            f.owner.setForeground(f.nativeOwner,true);JSONObject fresh=f.refresh(first);assertTrue(fresh.getBoolean("fresh"));assertNotEquals(first.getString("reviewId"),fresh.getString("reviewId"));assertEquals(run,f.tasks.get(f.task,false).getString("runId"));assertEquals(1,f.count("submit-count"));
        }
    }
}
