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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Real original SQL/host/Companion/bytes with explicit fake CLI peers; no actual account, model, phone or Compose clicks. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class TeamMemberQuestionNativeJourneyTest {
    static final class Fixture implements AutoCloseable {
        final Path root=Files.createTempDirectory("native-original-question-");final Object nativeOwner=new Object();final CopyOnWriteArrayList<JSONObject> events=new CopyOnWriteArrayList<>();
        Runnable dispatchHook=()->{};dev.pocketengine.features.teams.TeamsModule teams;String teamRun;com.sun.net.httpserver.HttpServer model;Process server;BufferedReader output;String base,task;Path workspace;TaskStore tasks;FeatureHost host;RemoteModule remote;RemoteOwnerWorkflows owner;
        Fixture(String worker)throws Exception {this(worker,false);}
        Fixture(String worker,boolean approval)throws Exception {try{
            Path repo=Path.of(System.getProperty("user.dir"));server=new ProcessBuilder("/usr/bin/python3","-B","-u",repo.resolve("companion/interaction_fixture_server.py").toString(),root.toString(),approval?"team-approval":"team").directory(repo.resolve("companion").toFile()).redirectError(root.resolve("server-stderr.log").toFile()).start();output=new BufferedReader(new InputStreamReader(server.getInputStream(),java.nio.charset.StandardCharsets.UTF_8));long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(!output.ready()&&server.isAlive()&&System.nanoTime()<until)Thread.sleep(5);assertTrue(Files.readString(root.resolve("server-stderr.log")),output.ready());JSONObject bootstrap=new JSONObject(output.readLine());base=bootstrap.getString("baseUrl");workspace=Path.of(bootstrap.getString("workspace"));boot(true);
            var app=RuntimeEnvironment.getApplication();model=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);model.createContext("/v1/chat/completions",exchange->{try{exchange.getRequestBody().readAllBytes();byte[] body="{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"fixture synthesis\"},\"finish_reason\":\"stop\"}]}".getBytes();exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);}finally{exchange.close();}});model.start();
            app.getSharedPreferences("private_settings",0).edit().remove("selectedAccountModel").putString("baseUrl","http://127.0.0.1:"+model.getAddress().getPort()+"/v1").putString("model","fixture").putString("provider","openai_chat").commit();
            EventSink sink=event->{events.add(event);host.recordEvent(event);};teams=new dev.pocketengine.features.teams.TeamsModule(new FeatureContext(app,new SettingsStore(app),new SecretVault(app),sink));host.register(teams);
            teams.setExternalMemberFactory(new CompanionExternalMemberFactory((command,input)->{if(command.equals("probeWorker"))return new JSONObject().put("available",true).put("cliFlagsVerified",true).put("accountConfigured",true).put("protocolVerified",true).put("configReadVerified",true).put("capabilities",new JSONObject().put("readOnlySandbox",true));return remote.handle(command,input);},new ExternalChildJournal(){public JSONObject prepare(JSONObject request,JSONObject binding)throws Exception{return tasks.prepareTeamChild(request,binding);}public void record(JSONObject link,JSONObject receipt)throws Exception{tasks.updateExternal(link.getString("taskId"),receipt);}}));
            JSONObject slot=new JSONObject().put("id","original-member").put("name","Original member").put("role","read and answer").put("runner",worker).put("device",new JSONObject().put("profileId","computer").put("environmentId","workspace"));if(worker.equals("claude"))slot.put("externalExecutionRiskAcknowledged",true);
            JSONObject definition=teams.handle("save",new JSONObject().put("name","Original team").put("mode","collaborate").put("members",new JSONArray().put(slot)));
            String request=UUID.randomUUID().toString();host.request(new JSONObject().put("feature","teams").put("command","start").put("requestId",request).put("payload",new JSONObject().put("teamId",definition.getString("id")).put("goal","Answer the exact original question").put("rounds",1)));JSONObject response=null;until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(response==null&&System.nanoTime()<until){for(JSONObject event:events)if(request.equals(event.optString("requestId")))response=event;Thread.sleep(5);}assertNotNull(events.toString(),response);assertTrue(response.toString(),response.optBoolean("ok"));teamRun=response.getJSONObject("result").getString("id");
            until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(task==null&&System.nanoTime()<until){JSONArray all=tasks.list();for(int i=0;i<all.length();i++){JSONObject row=all.getJSONObject(i);if(teamRun.equals(row.optString("teamRunId")))task=row.getString("id");}Thread.sleep(10);}assertNotNull(teams.handle("get",new JSONObject().put("runId",teamRun)).toString(),task);pending(approval);
        }catch(Exception|Error failure){try{close();}catch(Exception cleanup){failure.addSuppressed(cleanup);}throw failure;}}
        void boot(boolean first)throws Exception {var app=RuntimeEnvironment.getApplication();if(first){app.deleteDatabase("pocket-world.db");app.deleteFile("remote-profiles.json");app.deleteFile("remote-operations.json");}tasks=new TaskStore(app);host=new FeatureHost(events::add,tasks,()->false);EventSink sink=event->{events.add(event);host.recordEvent(event);};remote=new RemoteModule(new FeatureContext(app,new SettingsStore(app),new SecretVault(app),sink),(id,url)->"native-question-fixture-token-longer-than-32-characters",path->{if(path.endsWith("/question-answer"))dispatchHook.run();});host.register(remote);if(first)remote.handle("saveProfile",new JSONObject().put("id","computer").put("name","original controlled question host").put("baseUrl",base));owner=new RemoteOwnerWorkflows(host,tasks,sink);owner.setForeground(nativeOwner,true);}
        JSONObject target()throws Exception{return tasks.remoteOwnerTarget(task);}
        void pending()throws Exception {pending(false);}
        void pending(boolean approval)throws Exception {long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(System.nanoTime()<until){JSONObject source=target();if(!source.optString("jobId").isEmpty()){remote.handle("getJob",new JSONObject(source.toString()));String pending=approval?"pendingApprovals":"pendingQuestions";if(target().optJSONArray(pending)!=null&&target().getJSONArray(pending).length()>0){Thread.sleep(250);remote.handle("getJob",new JSONObject(target().toString()));return;}}Thread.sleep(10);}throw new AssertionError(tasks.get(task,false));}
        JSONObject open()throws Exception{return owner.handle(nativeOwner,"open",new JSONObject().put("windowId",UUID.randomUUID().toString()).put("taskId",task)).getJSONObject("snapshot");}
        JSONObject action(JSONObject review)throws Exception{return new JSONObject().put("taskId",task).put("windowId",review.getString("windowId")).put("reviewId",review.getString("reviewId"));}
        JSONObject refresh(JSONObject review)throws Exception{return owner.handle(nativeOwner,"refresh",action(review)).getJSONObject("snapshot");}
        JSONObject question(JSONObject review)throws Exception{return review.getJSONObject("source").getJSONArray("pendingQuestions").getJSONObject(0);}
        JSONObject answer(JSONObject review)throws Exception {JSONObject q=question(review),answers=q.getString("protocol").equals("codex_app_server")?new JSONObject().put("style",new JSONArray().put("Brief")):new JSONObject().put("q0",new JSONArray().put("A").put("B")).put("q1",new JSONArray().put("Custom name"));return owner.handle(nativeOwner,"answerQuestion",action(review).put("requestId",q.getString("requestId")).put("requestHash",q.getString("requestHash")).put("answers",answers));}
        JSONObject settled(JSONObject review)throws Exception {long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(System.nanoTime()<until){review=refresh(review);if(review.getJSONObject("source").optString("executorStatus").equals("completed")&&!tasks.hasUnresolved(task))return review;Thread.sleep(10);}throw new AssertionError(tasks.get(task,false));}
        int posts()throws Exception{return Files.exists(root.resolve("question-post-count"))?Integer.parseInt(Files.readString(root.resolve("question-post-count"))):0;}
        public void close()throws Exception {if(teams!=null)teams.close();if(model!=null)model.stop(0);if(remote!=null)remote.close();if(host!=null)host.close();if(server!=null){server.destroy();if(!server.waitFor(3,TimeUnit.SECONDS)){server.destroyForcibly();server.waitFor(2,TimeUnit.SECONDS);}}if(output!=null)output.close();}
    }
    @Test public void codexOriginalTeamMemberAnswersOnlyItsOriginalHarnessQuestion()throws Exception {journey("codex_app_server");}
    @Test public void claudeOriginalTeamMemberAnswersOnlyItsOriginalHarnessQuestion()throws Exception {journey("claude");}
    private void journey(String worker)throws Exception {try(Fixture f=new Fixture(worker)){
        JSONObject review=f.open(),source=review.getJSONObject("source"),question=f.question(review);assertTrue(review.toString(),review.getBoolean("canAnswerQuestions"));assertEquals("current_team_member",review.getString("questionAnswerScope"));
        JSONObject answers=worker.equals("claude")?new JSONObject().put("q0",new JSONArray().put("A").put("B")).put("q1",new JSONArray().put("Custom name")):new JSONObject().put("style",new JSONArray().put("Brief"));
        assertThrows(SecurityException.class,()->f.tasks.claimRemoteQuestionAnswer(source,question.getString("requestId"),question.getString("requestHash"),answers));assertEquals(0,f.posts());
        f.owner.setForeground(f.nativeOwner,false);assertThrows(SecurityException.class,()->f.answer(review));assertEquals(0,f.posts());f.owner.setForeground(f.nativeOwner,true);
        JSONObject invalid=f.action(review).put("requestId",question.getString("requestId")).put("requestHash","0".repeat(64)).put("answers",answers);assertThrows(SecurityException.class,()->f.owner.handle(f.nativeOwner,"answerQuestion",invalid));assertEquals(0,f.posts());
        JSONObject fresh=f.refresh(review);JSONObject done=f.settled(f.answer(fresh).getJSONObject("snapshot"));assertEquals(1,f.posts());assertEquals(source.getString("jobId"),done.getJSONObject("source").getString("jobId"));assertEquals(source.getString("runId"),done.getJSONObject("source").getString("runId"));assertEquals("resolved",done.getJSONObject("source").getJSONArray("questionReceipts").getJSONObject(0).getString("status"));assertEquals(2,f.tasks.list().length());
    }}
    @Test public void readonlyMemberCannotApproveEscalationButDeclineReturnsToSameHarnessThenQuestion()throws Exception {try(Fixture f=new Fixture("codex_app_server",true)){
        JSONObject review=f.open(),approval=review.getJSONObject("source").getJSONArray("pendingApprovals").getJSONObject(0);assertEquals(0,review.getJSONArray("teamMemberApprovalAcceptIds").length());
        assertThrows(SecurityException.class,()->f.owner.handle(f.nativeOwner,"approve",f.action(review).put("requestId",approval.getString("requestId")).put("decision","accept")));assertFalse(Files.exists(f.root.resolve("approval-post-count")));
        JSONObject fresh=f.refresh(review);JSONObject declined=f.owner.handle(f.nativeOwner,"approve",f.action(fresh).put("requestId",approval.getString("requestId")).put("decision","decline"));f.pending();JSONObject question=f.refresh(declined.getJSONObject("snapshot"));assertEquals("1",Files.readString(f.root.resolve("approval-post-count")));f.settled(f.answer(question).getJSONObject("snapshot"));assertEquals(1,f.posts());
    }}
    @Test public void finalWireFenceRejectsParentCancellationAndKeepsOriginalQuestionUnanswered()throws Exception {try(Fixture f=new Fixture("codex_app_server")){
        JSONObject review=f.open();f.dispatchHook=()->{try{f.tasks.status(f.teamRun,"cancelled","owner stop at original wire boundary");}catch(Exception failure){throw new RuntimeException(failure);}};
        JSONObject result=f.answer(review);assertEquals(0,f.posts());assertEquals("remote_question_rejected",result.getJSONObject("operation").getString("state"));assertTrue(result.getJSONObject("operation").getJSONObject("receipt").getBoolean("notDispatchedConfirmed"));
    }}
    @Test public void expiredNativeMemberClaimNeverWritesToOriginalHarness()throws Exception {try(Fixture f=new Fixture("codex_app_server")){
        JSONObject review=f.open();f.dispatchHook=()->{try{Thread.sleep(31_000L);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new RuntimeException(interrupted);}};
        JSONObject result=f.answer(review);assertEquals(0,f.posts());assertEquals("remote_question_rejected",result.getJSONObject("operation").getString("state"));assertTrue(result.getJSONObject("operation").getJSONObject("receipt").getBoolean("notDispatchedConfirmed"));
    }}
    @Test public void changedCoordinatorMemberAndForgedSessionCannotAnswerReviewedQuestion()throws Exception {try(Fixture f=new Fixture("codex_app_server")){
        JSONObject review=f.open(),source=review.getJSONObject("source");assertThrows(SecurityException.class,()->f.tasks.requireTeamMemberInteraction(new JSONObject(source.toString()).put("threadId","another-thread")));
        f.teams.handle("pause",new JSONObject().put("runId",f.teamRun));assertThrows(SecurityException.class,()->f.answer(review));assertEquals(0,f.posts());
    }}
    @Test public void stoppedParentRejectsAlreadyReviewedQuestionWithoutWireWrite()throws Exception {try(Fixture f=new Fixture("codex_app_server")){
        JSONObject review=f.open();f.tasks.status(f.teamRun,"cancelled","fixture owner stopped original parent");assertThrows(SecurityException.class,()->f.answer(review));assertEquals(0,f.posts());
    }}
}
