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
public final class RemoteEventCursorJourneyTest {
    static final class Fixture implements AutoCloseable {
        final Path root=Files.createTempDirectory("c3-original-event-replay-");
        final Object nativeOwner=new Object();
        final CopyOnWriteArrayList<JSONObject> events=new CopyOnWriteArrayList<>();
        final CountDownLatch nativeReadEntered=new CountDownLatch(1);
        Process server;BufferedReader output;String base,task,worker;
        TaskStore tasks;FeatureHost host;RemoteModule remote;RemoteOwnerWorkflows owner;
        Fixture(String worker)throws Exception {this.worker=worker;try{
            Path repo=Path.of(System.getProperty("pocket.gateway.source",System.getProperty("user.dir"))).toAbsolutePath().normalize();
            server=new ProcessBuilder(System.getProperty("pocket.gateway.python","python3"),"-B","-u",repo.resolve("companion/event_replay_fixture_server.py").toString(),root.toString()).directory(repo.resolve("companion").toFile()).redirectError(root.resolve("server-stderr.log").toFile()).start();
            output=new BufferedReader(new InputStreamReader(server.getInputStream(),StandardCharsets.UTF_8));
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(!output.ready()&&server.isAlive()&&System.nanoTime()<until)Thread.sleep(5);
            assertTrue(readUtf8(root.resolve("server-stderr.log")),output.ready());base=new JSONObject(output.readLine()).getString("baseUrl");boot(true);
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
        static String readUtf8(Path path)throws Exception{return new String(Files.readAllBytes(path),StandardCharsets.UTF_8);}
        int count(String name)throws Exception {return Files.exists(root.resolve(name))?Integer.parseInt(readUtf8(root.resolve(name))):0;}
        void marker(String name)throws Exception{Files.write(root.resolve(name),"controlled fixture fault only".getBytes(StandardCharsets.UTF_8));}
        void remove(String name)throws Exception{Files.deleteIfExists(root.resolve(name));}
        void waitFile(String name)throws Exception{long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);while(!Files.exists(root.resolve(name))&&System.nanoTime()<until)Thread.sleep(5);assertTrue(name,Files.exists(root.resolve(name)));}
        void reopen()throws Exception{owner.setForeground(nativeOwner,false);remote.close();host.close();boot(false);}
        public void close()throws Exception {
            if(owner!=null)owner.setForeground(nativeOwner,false);if(remote!=null)remote.close();if(host!=null)host.close();
            if(server!=null){server.destroy();if(!server.waitFor(6,TimeUnit.SECONDS)){server.destroyForcibly();server.waitFor(2,TimeUnit.SECONDS);}assertFalse(server.isAlive());
                JSONObject released=new JSONObject(readUtf8(root.resolve("resource-release.json")));assertTrue(released.getBoolean("listenerClosed"));assertEquals(0,released.getInt("activeProcesses"));}
            if(output!=null)output.close();
        }
    }


    private static JSONObject drain(Fixture f,JSONObject view)throws Exception {
        for(int n=0;n<5&&view.optJSONObject("eventRead")!=null&&view.getJSONObject("eventRead").optBoolean("hasMore");n++)view=f.refresh(view);
        assertEquals("confirmed",view.getString("eventReadStatus"));assertFalse(view.getJSONObject("eventRead").getBoolean("hasMore"));return view;
    }
    @Test public void originalDriversReplayExactRetainedEventsRestoreRequestsAndPersistCursorAcrossPhoneReopen()throws Exception {
        for(String worker:new String[]{"codex_app_server","claude"})try(Fixture f=new Fixture(worker)){
            JSONObject view=drain(f,f.open()),cache=view.getJSONObject("eventRead"),source=view.getJSONObject("source");String run=source.getString("runId"),job=source.getString("jobId"),generation=cache.getString("generation");
            assertTrue(cache.getJSONArray("gaps").length()>0);assertEquals("retention_gap",cache.getJSONArray("gaps").getJSONObject(0).getString("reason"));assertEquals(64,cache.getJSONArray("events").length());assertEquals(cache.getLong("headSequence"),cache.getLong("nextSequence"));
            long cursor=cache.getLong("nextSequence");String approval=worker.equals("claude")?"":source.getJSONArray("pendingApprovals").getJSONObject(0).getString("requestId");String question=worker.equals("claude")?source.getJSONArray("pendingQuestions").getJSONObject(0).getString("requestHash"):"";
            f.marker("drop-job-reads");assertEquals("unconfirmed",f.refresh(view).getString("queryStatus"));f.remove("drop-job-reads");
            f.reopen();view=drain(f,f.open());assertEquals(cursor,view.getJSONObject("eventRead").getLong("nextSequence"));assertEquals(generation,view.getJSONObject("eventRead").getString("generation"));assertEquals(run,view.getJSONObject("source").getString("runId"));assertEquals(job,view.getJSONObject("source").getString("jobId"));
            if(worker.equals("claude"))assertEquals(question,view.getJSONObject("source").getJSONArray("pendingQuestions").getJSONObject(0).getString("requestHash"));
            else {assertEquals(approval,view.getJSONObject("source").getJSONArray("pendingApprovals").getJSONObject(0).getString("requestId"));f.approve(view);f.pending(true);view=drain(f,f.refresh(view));assertTrue(view.getJSONObject("source").getJSONArray("pendingQuestions").length()>0);assertEquals(1,f.count("approval-post-count"));}
            assertFalse(view.getJSONObject("eventRead").getBoolean("liveStreaming"));assertFalse(view.getJSONObject("eventRead").getBoolean("executionAuthorized"));assertEquals(1,f.count("submit-count"));assertEquals(0,f.count("question-post-count"));
        }
    }
    @Test public void backgroundDuringDispatchedEventGetDoesNotCommitLateCursorAndExplicitReturnReadsSameJob()throws Exception {
        try(Fixture f=new Fixture("codex_app_server")){
            JSONObject view=f.open();long cursor=f.tasks.originalRemoteEventRead(f.task).getLong("nextSequence");String run=f.source().getString("runId"),job=f.source().getString("jobId");f.marker("hold-event-reads");
            ExecutorService reader=Executors.newSingleThreadExecutor(r->new Thread(r,"controlled-original-owner-read"));
            try{Future<JSONObject> pending=reader.submit(()->f.refresh(view));f.waitFile("event-read-held");f.owner.setForeground(f.nativeOwner,false);f.marker("release-event-reads");assertThrows(ExecutionException.class,()->pending.get(4,TimeUnit.SECONDS));assertEquals(cursor,f.tasks.originalRemoteEventRead(f.task).getLong("nextSequence"));}
            finally{f.marker("release-event-reads");f.remove("hold-event-reads");reader.shutdownNow();assertTrue(reader.awaitTermination(3,TimeUnit.SECONDS));}
            f.owner.close(view.getString("windowId"));f.owner.setForeground(f.nativeOwner,true);JSONObject restored=drain(f,f.open());assertTrue(restored.getJSONObject("eventRead").getLong("nextSequence")>cursor);assertEquals(run,f.source().getString("runId"));assertEquals(job,f.source().getString("jobId"));assertEquals(1,f.count("submit-count"));assertEquals(0,f.count("approval-post-count"));
        }
    }
    @Test public void oldServerSnapshotFallbackAndTypedCrossTaskOrBindingRefusalNeverCreateOrApprove()throws Exception {
        try(Fixture f=new Fixture("codex_app_server")){
            f.marker("legacy-event-capabilities");JSONObject old=f.open();assertEquals("unsupported",old.getString("eventReadStatus"));assertFalse(old.has("eventRead"));assertEquals(0,f.count("event-get-count"));f.remove("legacy-event-capabilities");
            JSONObject view=f.refresh(old),before=f.tasks.originalRemoteEventRead(f.task),source=f.source();String binding=f.remote.profileBindingHash("computer");
            JSONObject wrongTask=new JSONObject(source.toString()).put("taskId",UUID.randomUUID().toString());var page=f.remote.readOriginalJobEvents(wrongTask,binding,before,()->{});
            assertThrows(SecurityException.class,()->f.tasks.recordOriginalRemoteEventPage(f.task,page));assertTrue(RemoteJobEvents.same(before,f.tasks.originalRemoteEventRead(f.task)));
            int reads=f.count("event-get-count");assertThrows(SecurityException.class,()->f.remote.readOriginalJobEvents(source,"0".repeat(64),before,()->{}));assertEquals(reads,f.count("event-get-count"));assertEquals(1,f.count("submit-count"));assertEquals(0,f.count("approval-post-count"));assertEquals(0,f.count("question-post-count"));
        }
    }
    @Test public void actualOriginalOwnerReadPersistsTwoPublicChildSourcesAcrossReopenWithoutChangingQuestionsOrGrant()throws Exception{
        try(Fixture f=new Fixture("claude")){
            JSONObject view=drain(f,f.open()),cache=view.getJSONObject("eventRead"),source=view.getJSONObject("source");String job=source.getString("jobId"),run=source.getString("runId"),question=source.getJSONArray("pendingQuestions").getJSONObject(0).getString("requestHash");java.util.Set<String> children=new java.util.HashSet<>();int childRows=0;JSONArray rows=cache.getJSONArray("events");
            for(int i=0;i<rows.length();i++){JSONObject event=rows.getJSONObject(i),lineage=RemoteJobEvents.sourceView(event,cache.getJSONObject("scope"));if(!"child".equals(lineage.getString("kind")))continue;childRows++;children.add(lineage.getString("parentToolUseId"));assertEquals(source.getString("sessionId"),lineage.getString("parentSessionId"));assertFalse(lineage.getBoolean("executionAuthorized"));assertFalse(lineage.getBoolean("childSessionControl"));}
            assertEquals(6,childRows);assertEquals(java.util.Set.of("toolu_child_a","toolu_child_b"),children);assertFalse(cache.toString().contains("NEVER_EXPOSE_CHILD_THINKING"));long cursor=cache.getLong("nextSequence");f.reopen();JSONObject restored=drain(f,f.open());assertEquals(cursor,restored.getJSONObject("eventRead").getLong("nextSequence"));assertTrue(RemoteJobEvents.same(cache.getJSONObject("scope"),restored.getJSONObject("eventRead").getJSONObject("scope")));assertEquals(job,restored.getJSONObject("source").getString("jobId"));assertEquals(run,restored.getJSONObject("source").getString("runId"));assertEquals(question,restored.getJSONObject("source").getJSONArray("pendingQuestions").getJSONObject(0).getString("requestHash"));assertEquals(1,f.count("submit-count"));assertEquals(0,f.count("approval-post-count"));assertEquals(0,f.count("question-post-count"));
        }
    }
}
