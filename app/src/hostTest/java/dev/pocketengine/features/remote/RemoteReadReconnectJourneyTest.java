package dev.pocketengine.features.remote;

import android.app.Application;
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
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Original SQL/host/HTTP/Companion/driver. Installed local CLI peers are explicitly synthetic. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class RemoteReadReconnectJourneyTest {
    static final class Fixture implements AutoCloseable {
        final Path root=Files.createTempDirectory("c3-original-read-reconnect-");
        final Object nativeOwner=new Object();
        final CopyOnWriteArrayList<JSONObject> events=new CopyOnWriteArrayList<>();
        final CountDownLatch nativeReadEntered=new CountDownLatch(1);
        Process server;BufferedReader output;String base,task,worker;
        TaskStore tasks;FeatureHost host;RemoteModule remote;RemoteOwnerWorkflows owner;
        Fixture(String worker)throws Exception {this.worker=worker;try{
            Path repo=Path.of(System.getProperty("user.dir"));
            server=new ProcessBuilder("/usr/bin/python3","-B","-u",repo.resolve("companion/read_reconnect_fixture_server.py").toString(),root.toString()).directory(repo.resolve("companion").toFile()).redirectError(root.resolve("server-stderr.log").toFile()).start();
            output=new BufferedReader(new InputStreamReader(server.getInputStream(),StandardCharsets.UTF_8));
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(!output.ready()&&server.isAlive()&&System.nanoTime()<until)Thread.sleep(5);
            assertTrue(Files.readString(root.resolve("server-stderr.log")),output.ready());base=new JSONObject(output.readLine()).getString("baseUrl");boot(true);
            String request=UUID.randomUUID().toString();
            host.request(new JSONObject().put("feature","remote").put("command","submitJob").put("requestId",request).put("payload",new JSONObject().put("profileId","computer").put("environmentId","workspace").put("worker",worker).put("prompt","Controlled original read reconnection, no repeated creation").put("timeoutSeconds",45)));
            JSONObject response=null;until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(response==null&&System.nanoTime()<until){for(JSONObject event:events)if(request.equals(event.optString("requestId")))response=event;Thread.sleep(5);}
            assertNotNull(events.toString(),response);assertTrue(response.toString(),response.optBoolean("ok"));task=response.getJSONObject("result").getString("hostTaskId");pending(worker.equals("claude"));
        }catch(Exception|Error failure){try{close();}catch(Exception cleanup){failure.addSuppressed(cleanup);}throw failure;}}
        void boot(boolean first)throws Exception {
            var app=RuntimeEnvironment.getApplication();if(first){app.deleteDatabase("pocket-world.db");app.deleteFile("remote-profiles.json");app.deleteFile("remote-operations.json");}
            tasks=new TaskStore(app);host=new FeatureHost(events::add,tasks,()->false);EventSink sink=event->{events.add(event);host.recordEvent(event);};
            remote=new RemoteModule(new FeatureContext(app,new SettingsStore(app),new SecretVault(app),sink),(id,url)->"native-read-reconnect-fixture-token-longer-than-32-characters",path->{if(Thread.currentThread().getName().equals("controlled-original-owner-read")&&path.startsWith("/v1/jobs/"))nativeReadEntered.countDown();});
            host.register(remote);if(first)remote.handle("saveProfile",new JSONObject().put("id","computer").put("name","controlled original host").put("baseUrl",base));
            owner=new RemoteOwnerWorkflows(host,tasks,sink);owner.setForeground(nativeOwner,true);
        }
        JSONObject source()throws Exception{return tasks.remoteOwnerTarget(task);}
        void pending(boolean question)throws Exception {
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(6);
            while(System.nanoTime()<until){JSONObject source=source();if(!source.optString("jobId").isEmpty())remote.handle("getJob",source);
                JSONArray values=source().optJSONArray(question?"pendingQuestions":"pendingApprovals");if(values!=null&&values.length()>0)return;Thread.sleep(10);}
            throw new AssertionError(tasks.get(task,false));
        }
        JSONObject open()throws Exception{return owner.handle(nativeOwner,"open",new JSONObject().put("windowId",UUID.randomUUID().toString()).put("taskId",task)).getJSONObject("snapshot");}
        JSONObject action(JSONObject review)throws Exception{return new JSONObject().put("windowId",review.getString("windowId")).put("taskId",task).put("reviewId",review.getString("reviewId"));}
        JSONObject refresh(JSONObject review)throws Exception{return owner.handle(nativeOwner,"refresh",action(review)).getJSONObject("snapshot");}
        void approve(JSONObject review)throws Exception {String id=review.getJSONObject("source").getJSONArray("pendingApprovals").getJSONObject(0).getString("requestId");owner.handle(nativeOwner,"approve",action(review).put("requestId",id).put("decision","accept"));}
        int count(String name)throws Exception {return Files.exists(root.resolve(name))?Integer.parseInt(Files.readString(root.resolve(name))):0;}
        void marker(String name)throws Exception{Files.writeString(root.resolve(name),"controlled fixture fault only");}
        void remove(String name)throws Exception{Files.deleteIfExists(root.resolve(name));}
        void waitFile(String name)throws Exception{long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);while(!Files.exists(root.resolve(name))&&System.nanoTime()<until)Thread.sleep(5);assertTrue(name,Files.exists(root.resolve(name)));}
        void reopen()throws Exception{owner.setForeground(nativeOwner,false);remote.close();host.close();boot(false);}
        public void close()throws Exception {
            if(owner!=null)owner.setForeground(nativeOwner,false);if(remote!=null)remote.close();if(host!=null)host.close();
            if(server!=null){server.destroy();if(!server.waitFor(6,TimeUnit.SECONDS)){server.destroyForcibly();server.waitFor(2,TimeUnit.SECONDS);}assertFalse(server.isAlive());
                JSONObject released=new JSONObject(Files.readString(root.resolve("resource-release.json")));assertTrue(released.getBoolean("listenerClosed"));assertEquals(0,released.getInt("activeProcesses"));}
            if(output!=null)output.close();
        }
    }

    @Test public void disconnectReconnectAndPhoneReopenRestoreOriginalProgressApprovalAndQuestionWithoutNewJobOrAnswer()throws Exception {
        try(Fixture f=new Fixture("codex_app_server")) {
            JSONObject first=f.open();String run=f.tasks.get(f.task,false).getString("runId"),job=first.getJSONObject("source").getString("jobId"),operation=first.getJSONObject("source").getString("jobOperationId");
            String approval=first.getJSONObject("source").getJSONArray("pendingApprovals").getJSONObject(0).getString("requestId");long observed=first.getLong("lastSuccessfulReadAt");
            assertTrue(first.getBoolean("fresh"));assertEquals("confirmed",first.getString("queryStatus"));assertTrue(observed>0);assertTrue(first.getBoolean("readObservationOnly"));
            f.marker("drop-job-reads");JSONObject lost=f.refresh(first);assertFalse(lost.getBoolean("fresh"));assertEquals("unconfirmed",lost.getString("queryStatus"));assertEquals(observed,lost.getLong("lastSuccessfulReadAt"));assertFalse(lost.getBoolean("canContinue"));assertFalse(lost.getBoolean("canAnswerQuestions"));
            assertEquals(1,f.count("submit-count"));assertEquals(0,f.count("approval-post-count"));assertEquals(0,f.count("question-post-count"));
            f.remove("drop-job-reads");f.remote.requestReadRefresh();JSONObject recovered=f.refresh(lost);assertTrue(recovered.getBoolean("fresh"));assertEquals(approval,recovered.getJSONObject("source").getJSONArray("pendingApprovals").getJSONObject(0).getString("requestId"));assertTrue(recovered.getJSONObject("result").getJSONObject("progress").getBoolean("available"));assertFalse(recovered.getJSONObject("result").getJSONObject("progress").getBoolean("liveStreaming"));
            f.approve(recovered);f.pending(true);JSONObject question=f.refresh(recovered);String hash=question.getJSONObject("source").getJSONArray("pendingQuestions").getJSONObject(0).getString("requestHash");assertTrue(question.getBoolean("canAnswerQuestions"));
            f.marker("drop-job-reads");assertEquals("unconfirmed",f.refresh(question).getString("queryStatus"));f.remove("drop-job-reads");JSONObject same=f.refresh(question);assertEquals(hash,same.getJSONObject("source").getJSONArray("pendingQuestions").getJSONObject(0).getString("requestHash"));
            f.owner.close(same.getString("windowId"));f.reopen();JSONObject reopened=f.open();assertEquals(job,reopened.getJSONObject("source").getString("jobId"));assertEquals(operation,reopened.getJSONObject("source").getString("jobOperationId"));assertEquals(run,f.tasks.get(f.task,false).getString("runId"));assertEquals(1,f.tasks.list().length());assertTrue(reopened.getBoolean("canAnswerQuestions"));
            assertEquals(hash,reopened.getJSONObject("source").getJSONArray("pendingQuestions").getJSONObject(0).getString("requestHash"));assertEquals(1,f.count("submit-count"));assertEquals(1,f.count("approval-post-count"));assertEquals(0,f.count("question-post-count"));
        }
    }

    @Test public void bothProtocolsDiscardGetAfterBackgroundAndNeverRestoreOldReview()throws Exception {
        for(String protocol:new String[]{"codex_app_server","claude"})try(Fixture f=new Fixture(protocol)) {
            JSONObject review=f.open();long observed=review.getLong("lastSuccessfulReadAt");String run=f.tasks.get(f.task,false).getString("runId");
            f.marker("hold-job-reads");ExecutorService reader=Executors.newSingleThreadExecutor(r->new Thread(r,"controlled-original-owner-read"));
            try {
                Future<Throwable> late=reader.submit(()->{try{f.refresh(review);return null;}catch(Throwable failure){return failure;}});
                assertTrue(f.nativeReadEntered.await(2,TimeUnit.SECONDS));f.waitFile("job-read-held");f.owner.setForeground(f.nativeOwner,false);f.marker("release-job-reads");
                assertTrue(String.valueOf(late.get(5,TimeUnit.SECONDS)),late.get() instanceof SecurityException);
                assertThrows(SecurityException.class,()->f.refresh(review));assertEquals(1,f.count("submit-count"));assertEquals(0,f.count("approval-post-count"));assertEquals(0,f.count("question-post-count"));
                f.remove("hold-job-reads");f.owner.setForeground(f.nativeOwner,true);JSONObject current=f.refresh(review);assertTrue(current.getBoolean("fresh"));assertTrue(current.getLong("lastSuccessfulReadAt")>=observed);assertEquals(run,f.tasks.get(f.task,false).getString("runId"));assertNotEquals(review.getString("reviewId"),current.getString("reviewId"));
                f.remove("release-job-reads");f.remove("job-read-held");f.marker("hold-job-reads");
                Future<Throwable> closed=reader.submit(()->{try{f.refresh(current);return null;}catch(Throwable failure){return failure;}});
                f.waitFile("job-read-held");f.owner.close(current.getString("windowId"));f.marker("release-job-reads");assertTrue(closed.get(5,TimeUnit.SECONDS) instanceof SecurityException);
                f.remove("hold-job-reads");JSONObject next=f.open();assertNotEquals(current.getString("windowId"),next.getString("windowId"));assertEquals(run,f.tasks.get(f.task,false).getString("runId"));assertEquals(1,f.count("submit-count"));
            } finally {f.marker("release-job-reads");reader.shutdownNow();assertTrue(reader.awaitTermination(3,TimeUnit.SECONDS));}
        }
    }

    @Test public void cancelledOriginalJobRemainsCancelledAndReadReconnectNeverCreatesAnotherRun()throws Exception {
        try(Fixture f=new Fixture("codex_app_server")) {
            JSONObject review=f.open(),source=review.getJSONObject("source");String run=f.tasks.get(f.task,false).getString("runId"),job=source.getString("jobId");
            f.marker("hold-job-reads");ExecutorService reader=Executors.newSingleThreadExecutor(r->new Thread(r,"controlled-original-owner-read"));
            try {
                Future<Throwable> stale=reader.submit(()->{try{f.refresh(review);return null;}catch(Throwable failure){return failure;}});
                assertTrue(f.nativeReadEntered.await(2,TimeUnit.SECONDS));f.waitFile("job-read-held");
                f.tasks.requestCancel(f.task,run);f.remote.handle("cancelJob",new JSONObject(source.toString()));
                long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(!f.tasks.get(f.task,false).optString("status").equals("cancelled")&&System.nanoTime()<until){f.remote.handle("getJob",new JSONObject(source.toString()));Thread.sleep(10);}
                assertEquals("cancelled",f.tasks.get(f.task,false).getString("status"));f.marker("release-job-reads");assertNull(stale.get(5,TimeUnit.SECONDS));
                assertEquals("cancelled",f.tasks.get(f.task,false).getString("status"));
            } finally {f.marker("release-job-reads");f.remove("hold-job-reads");reader.shutdownNow();assertTrue(reader.awaitTermination(3,TimeUnit.SECONDS));}
            int posts=f.count("all-post-count");
            f.marker("drop-job-reads");assertEquals("unconfirmed",f.refresh(review).getString("queryStatus"));f.remove("drop-job-reads");f.remote.requestReadRefresh();JSONObject read=f.refresh(review);
            assertEquals("cancelled",f.tasks.get(f.task,false).getString("status"));assertEquals(run,f.tasks.get(f.task,false).getString("runId"));assertEquals(job,read.getJSONObject("source").getString("jobId"));assertFalse(read.getBoolean("canContinue"));assertFalse(read.getBoolean("canAnswerQuestions"));assertEquals(posts,f.count("all-post-count"));assertEquals(1,f.count("submit-count"));assertEquals(1,f.tasks.list().length());
        }
    }
}
