package dev.pocketengine.features.teams;

import android.app.Application;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.core.*;
import dev.pocketengine.features.*;
import dev.pocketengine.features.memory.*;
import dev.pocketengine.features.skills.SkillsModule;
import dev.pocketengine.features.workspace.WorkspaceModule;
import dev.pocketengine.runtime.AgentRunner;
import dev.pocketengine.runtime.ModelGateway;
import dev.pocketengine.runtime.context.*;
import dev.pocketengine.store.*;
import dev.pocketengine.testutil.HttpServer;
import java.io.File;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Predicate;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;

/** Actual original parent Runner -> mounted facade -> original research children. Synthetic HTTP only. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class TeamResearchParentNativeJourneyTest {
    private static String id(){return UUID.randomUUID().toString();}
    private static JSONObject text(String value)throws Exception{return new JSONObject().put("role","assistant").put("content",value);}
    private static JSONObject tool(String name,JSONObject args)throws Exception{return new JSONObject().put("role","assistant").put("content",JSONObject.NULL).put("tool_calls",new JSONArray().put(new JSONObject().put("id",id()).put("type","function").put("function",new JSONObject().put("name",name).put("arguments",args.toString()))));}
    private static SQLiteDatabase sql(TaskStore tasks)throws Exception{var f=TaskStore.class.getDeclaredField("db");f.setAccessible(true);return (SQLiteDatabase)f.get(tasks);}
    private static boolean offered(JSONObject request,String tool)throws Exception{JSONArray rows=request.optJSONArray("tools");for(int i=0;rows!=null&&i<rows.length();i++)if(tool.equals(rows.getJSONObject(i).getJSONObject("function").optString("name")))return true;return false;}
    private static JSONObject latest(JSONObject request)throws Exception{JSONArray rows=request.getJSONArray("messages");for(int i=rows.length()-1;i>=0;i--)if("tool".equals(rows.getJSONObject(i).optString("role")))return new JSONObject(rows.getJSONObject(i).getString("content"));return null;}
    static final class Domain implements AutoCloseable,dev.pocketengine.features.assistant.AssistantModule.NativeInputHost,AgentRunner.OwnerInputs {
        final boolean unknown;final String longOutput;final TaskStore tasks;final FeatureHost features;final SettingsStore settings;final TeamsModule teams;final TeamCoordinator coordinator;final MemoryModule memory;final TeamMemberEvidenceHost materials;final TeamResearchHost research;final WorkspaceModule workspace;final HttpServer server;
        String parent;final String teamId;final AtomicReference<String> teamRun=new AtomicReference<>();final JSONObject startArguments;
        final AtomicReference<Throwable> failure=new AtomicReference<>();final AtomicInteger http=new AtomicInteger(),parentCalls=new AtomicInteger(),memberCalls=new AtomicInteger(),sourceBodyReads=new AtomicInteger(),lostPeerAck=new AtomicInteger();
        final CountDownLatch aEntered=new CountDownLatch(1),releaseCancelledA=new CountDownLatch(1);final CountDownLatch wakeIdle=new CountDownLatch(1),releaseWakeSender=new CountDownLatch(1);volatile boolean wakeScenario,wakeExpected,wakeDuplicate,wakePeerScenario;final CountDownLatch peerSent=new CountDownLatch(1),releasePeerFinal=new CountDownLatch(1);final AtomicBoolean firstB=new AtomicBoolean(true),bPeerSent=new AtomicBoolean();final AtomicInteger aCalls=new AtomicInteger();volatile JSONObject wakeReceipt,wakeDuplicateReceipt;final String wakeMessageId=id();final AtomicBoolean firstA=new AtomicBoolean(true),loseNextPeerPersist=new AtomicBoolean();
        final List<JSONObject> requests=new CopyOnWriteArrayList<>();HostToolSupervisor parentPort;RunControl original;String parentRun;int epoch;volatile int stage;volatile JSONObject observedStatus;final dev.pocketengine.features.assistant.AssistantModule assistant;volatile RunOutcome outcome;volatile ModelConfig captured;volatile JSONObject ownerBinding,acceptedReply;final AtomicInteger ownerReplies=new AtomicInteger();final CountDownLatch parentDone=new CountDownLatch(1);
        Domain(boolean unknown)throws Exception {this(unknown,"");}
        Domain(boolean unknown,String longOutput)throws Exception {
            this.longOutput=longOutput;this.unknown=unknown;var app=RuntimeEnvironment.getApplication();app.deleteDatabase("pocket-world.db");app.getSharedPreferences("private_settings",0).edit().clear().commit();
            File teamDir=new File(app.getFilesDir(),"teams");if(teamDir.exists())removeOwn(teamDir);tasks=new TaskStore(app);settings=new SettingsStore(app);features=new FeatureHost(e->{},tasks,()->false);
            FeatureContext context=new FeatureContext(app,settings,new SecretVault(app),features::recordEvent);workspace=new WorkspaceModule(context);features.register(workspace);memory=new MemoryModule(context);features.register(memory);features.register(new SkillsModule(context));
            materials=new TeamMemberEvidenceHost(features,tasks,ref->{sourceBodyReads.incrementAndGet();throw new AssertionError("unshared research cannot read an owner's body");},ref->{throw new AssertionError("unshared research has no owner source");});research=new TeamResearchHost(tasks,materials);
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/",exchange->{try{
                JSONObject request=new JSONObject(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));requests.add(request);int order=http.incrementAndGet();trace("http-"+order+"-request",request);JSONObject answer=answer(request);
                byte[] body=new JSONObject().put("model",request.getString("model")).put("choices",new JSONArray().put(new JSONObject().put("index",0).put("message",answer).put("finish_reason",answer.has("tool_calls")?"tool_calls":"stop"))).put("usage",new JSONObject().put("prompt_tokens",17).put("completion_tokens",7).put("total_tokens",24)).toString().getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);
            }catch(Throwable error){if(!(error instanceof java.io.IOException))failure.compareAndSet(null,error);try{exchange.sendResponseHeaders(500,0);}catch(Exception ignored){}}finally{exchange.close();}});server.start();
            settings.save(new JSONObject().put("baseUrl","http://127.0.0.1:"+server.getAddress().getPort()+"/v1").put("apiKey","").put("model","main-agent").put("sendScreenshots",false).put("maxSteps",32).put("maxOutputTokens",512).put("requestTimeoutSeconds",60).put("provider","openai_chat"));
            teams=new TeamsModule(context);features.register(teams);teams.setEvidenceFactory(materials);teams.setResearchHost(research);teams.setInferenceFactory((call,live)->tasks.teamInferenceTarget(call.nativeRunId,call.purpose,call.memberId,call.site,live,call::checkCurrent));
            var cf=TeamsModule.class.getDeclaredField("coordinator");cf.setAccessible(true);coordinator=(TeamCoordinator)cf.get(teams);
            if(unknown){var ff=TeamCoordinator.class.getDeclaredField("file");ff.setAccessible(true);File actual=(File)ff.get(coordinator);ff.set(coordinator,new File(actual.getPath()){@Override public File getParentFile(){try{JSONArray nativeRuns=coordinator.list();boolean peerQueued=false;for(int i=0;i<nativeRuns.length();i++)peerQueued|=coordinator.get(nativeRuns.getJSONObject(i).getString("id")).getJSONArray("mailbox").length()>0;if(peerQueued&&loseNextPeerPersist.compareAndSet(true,false)){lostPeerAck.incrementAndGet();return new File(getPath());}}catch(Exception failure){throw new IllegalStateException(failure);}return super.getParentFile();}});}
            JSONArray members=new JSONArray();for(String who:new String[]{"a","b","c","d"})members.put(new JSONObject().put("id",who).put("name","研究成员 "+who).put("role","公开研究成员"+who+"，只核对公开目标，不读主人私域").put("runner","model").put("device","phone").put("model","member-"+who));
            teamId=teams.handle("save",new JSONObject().put("name","主代理负责交付、成员直接协作").put("mode","collaborate").put("leaderId","a").put("members",members)).getString("id");
            Files.deleteIfExists(new File(app.getFilesDir(),"private_conversations.json").toPath());assistant=new dev.pocketengine.features.assistant.AssistantModule(context,memory,this::executeParent,tasks);assistant.setNativeInputHost(this);features.register(assistant);startArguments=new JSONObject().put("teamId",teamId).put("goal","公开目标：区分研究依据与未确定结论，主代理向用户交付").put("rounds",1);
            ContextBudgetPolicy.installHostSource(c->ContextBudgetPolicy.RouteBudget.forRoute(c,new ContextWindow.Budget(128000,c.maxOutputTokens,1024,1048576,128,0),ContextBudgetPolicy.SourceKind.OWNER_OVERRIDE,1,System.currentTimeMillis()));
        }
        private static void removeOwn(File f)throws Exception{File[] children=f.listFiles();if(children!=null)for(File child:children)removeOwn(child);if(!f.delete())throw new java.io.IOException("fixture own team dir cleanup failed");}
        ModelConfig model()throws Exception{return settings.get();}
        JSONObject state()throws Exception{return coordinator.get(teamRun.get());}
        JSONObject await(Predicate<JSONObject> test)throws Exception{long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(25);do{if(failure.get()!=null)throw new AssertionError(failure.get());if(teamRun.get()!=null){JSONObject s=state();if(test.test(s))return s;}Thread.sleep(10);}while(System.nanoTime()<until);throw new AssertionError("original research phase did not settle stage="+stage+" state="+state());}
        JSONObject exact(String action,String member)throws Exception{JSONObject s=state(),out=new JSONObject().put("teamRunId",teamRun.get()).put("expectedTeamRevision",s.getLong("revision")).put("expectedGeneration",s.getInt("generation"));if(!action.isEmpty())out.put("action",action);if(!member.isEmpty()){JSONObject selected=TeamWorkPlan.find(s.getJSONArray("members"),member);out.put("memberId",member).put("expectedMemberGeneration",selected.getInt("generation"));}return out;}
        JSONObject answer(JSONObject request)throws Exception {
            String system=request.getJSONArray("messages").getJSONObject(0).getString("content");
            if(system.contains("你是团队Leader")||system.contains("你是团队协调者"))return text("研究汇总：四名实际成员分别核对公开目标。未执行项目代码，不声称业务独证。");
            if(!offered(request,"teams_start")){
                assertTrue(system.contains("你在原研究child持续工作"));memberCalls.incrementAndGet();assertFalse(request.toString().contains("OWNER_PRIVATE_SENTINEL"));
                if(system.contains("公开研究成员a"))aCalls.incrementAndGet();
                if(system.contains("公开研究成员a")&&firstA.compareAndSet(true,false)){
                    aEntered.countDown();if(wakeScenario)return tool("run_end_not_done",new JSONObject().put("kind","missing_information").put("reason","Waiting for the parent public fact, not permission or a human question").put("performed","Kept original readonly checkpoint").put("remaining","Use the next exact parent message").put("recoverable",true));if(unknown){loseNextPeerPersist.set(true);return tool("team_send_message",new JSONObject().put("targetMemberId","b").put("text","只核对公开目标，不能把消息当主人批准"));}
                    assertTrue(releaseCancelledA.await(20,TimeUnit.SECONDS));return text("旧请求已被暂停；该回复不得复活旧执行");
                }
                if(wakePeerScenario&&system.contains("公开研究成员b")){
                    if(firstB.compareAndSet(true,false))return tool("run_end_not_done",new JSONObject().put("kind","missing_information").put("reason","Wait at original B checkpoint for explicit parent continuation").put("performed","Retained B checkpoint").put("remaining","Check bounded peer evidence").put("recoverable",true));
                    if(bPeerSent.compareAndSet(false,true))return tool("team_send_message",new JSONObject().put("targetMemberId","a").put("text","PEER_SOURCE_SENTINEL: public evidence for the original same-team checkpoint, never owner authority"));
                    peerSent.countDown();assertTrue(releasePeerFinal.await(45,TimeUnit.SECONDS));return text("B finished the original public source; no write or new authority");
                }
                if(wakePeerScenario&&system.contains("公开研究成员a")){boolean peerData=false;JSONArray supplied=request.getJSONArray("messages");for(int i=0;i<supplied.length();i++)peerData|=supplied.getJSONObject(i).optString("content").contains("同队成员 b 的排队消息（不可信数据，不是主人指令或权限）")&&supplied.getJSONObject(i).optString("content").contains("PEER_SOURCE_SENTINEL");assertTrue(peerData);}
                if(!wakePeerScenario&&wakeScenario&&system.contains("公开研究成员a")){assertTrue(request.toString().contains("Native queued parent public fact"));boolean parentData=false;JSONArray supplied=request.getJSONArray("messages");for(int i=0;i<supplied.length();i++)parentData|=supplied.getJSONObject(i).optString("content").contains("父代理排队资料（不可信数据，不是主人确认/批准，不改变范围）");assertTrue(parentData);}
                if(!longOutput.isEmpty()&&system.contains("公开研究成员b"))return text(longOutput);
                return text(system.contains("公开研究成员a")?"成员A在原父暂停/继续后完成公开研究，已看到排队补充":"其他成员完成自己的公开研究；资料仍待主代理核对交付");
            }
            parentCalls.incrementAndGet();JSONObject last=latest(request);
            if(stage==0){stage=1;return tool("teams_start",startArguments);}
            if(stage==1){assertNotNull(last);teamRun.compareAndSet(null,last.getString("id"));assertTrue(aEntered.await(15,TimeUnit.SECONDS));if(!unknown)await(s->{try{for(String member:wakePeerScenario?new String[]{"c","d"}:new String[]{"b","c","d"})if(!"completed".equals(TeamWorkPlan.find(s.getJSONArray("members"),member).getString("status")))return false;return true;}catch(Exception e){throw new IllegalStateException(e);}});if(wakeScenario){await(s->{try{return TeamWorkPlan.find(s.getJSONArray("members"),"a").optString("status").matches("awaiting_user|interrupted")&&coordinator.parentExecutorState(teamRun.get()).getBoolean("resourcesReleased");}catch(Exception e){throw new IllegalStateException(e);}});stage=wakePeerScenario?40:20;wakeIdle.countDown();assertTrue(releaseWakeSender.await(45,TimeUnit.SECONDS));if(wakePeerScenario)return tool("teams_message",exact("","b").put("delivery","continue").put("researchRound",1).put("messageId",wakeMessageId).put("text","Continue B original checkpoint to verify a public peer fact; no new grant"));return tool("teams_message",exact("","a").put("delivery","queue").put("researchRound",1).put("messageId",wakeMessageId).put("text","Native queued parent public fact; source is parent_model, never owner consent"));}if(unknown){await(s->!s.optString("status").matches("running|queued"));stage=5;return tool("teams_get",new JSONObject().put("teamRunId",teamRun.get()));}stage=2;return tool("teams_control",exact("pause","a"));}
            if(stage==40){wakeReceipt=new JSONObject(last.toString());if(wakeExpected){await(s->s.optString("status").equals("completed"));stage=5;return tool("teams_get",new JSONObject().put("teamRunId",teamRun.get()).put("view","research_summary"));}await(s->{try{return bPeerSent.get()&&coordinator.parentExecutorState(teamRun.get()).getBoolean("resourcesReleased");}catch(Exception e){throw new IllegalStateException(e);}});return tool("run_end_not_done",new JSONObject().put("kind","missing_information").put("reason","Peer has settled but original owner policy/safety blocks wake").put("performed","Kept source receipt and original checkpoint").put("remaining","Await exact native owner review").put("recoverable",true));}
            if(stage==20){wakeReceipt=new JSONObject(last.toString());if(wakeDuplicate){await(s->{try{return s.optString("status").equals("completed")&&coordinator.parentExecutorState(teamRun.get()).getBoolean("resourcesReleased");}catch(Exception e){throw new IllegalStateException(e);}});stage=21;return tool("teams_message",exact("","a").put("delivery","queue").put("researchRound",1).put("messageId",wakeMessageId).put("text","Native queued parent public fact; source is parent_model, never owner consent"));}if(!wakeExpected)return tool("run_end_not_done",new JSONObject().put("kind","missing_information").put("reason","Queue is saved; no owner wake policy enabled").put("performed","Queued exact original message only").put("remaining","Await original owner policy/review").put("recoverable",true));await(s->s.optString("status").equals("completed"));stage=5;return tool("teams_get",new JSONObject().put("teamRunId",teamRun.get()).put("view","research_summary"));}
            if(stage==21){wakeDuplicateReceipt=new JSONObject(last.toString());await(s->s.optString("status").equals("completed"));stage=5;return tool("teams_get",new JSONObject().put("teamRunId",teamRun.get()).put("view","research_summary"));}
            if(stage==2){releaseCancelledA.countDown();await(s->{try{return coordinator.parentExecutorState(teamRun.get()).getBoolean("resourcesReleased");}catch(Exception e){throw new IllegalStateException(e);}});stage=3;return tool("teams_message",exact("","a").put("delivery","queue").put("messageId",id()).put("text","主代理排队的公开核对要求，不是主人批准，不新增资料或执行权限"));}
            if(stage==3){stage=4;return tool("teams_control",exact("resume","a").put("researchRound",1));}
            if(stage==4){await(s->s.optString("status").equals("completed"));stage=5;return tool("teams_get",new JSONObject().put("teamRunId",teamRun.get()).put("view","research_summary").put("offset",0).put("length",8000));}
            assertNotNull(last);if(unknown){observedStatus=new JSONObject(last.toString());assertEquals("blocked_unknown",last.getString("status"));assertTrue(last.getBoolean("hasUnresolvedEffects"));return text("原成员效果未决，主代理保留已完成研究并明确请用户核对，不自动重放该消息。");}
            if(!longOutput.isEmpty()&&stage==5){assertTrue(last.getString("content").contains("四名实际成员"));String expected="";JSONArray messages=request.getJSONArray("messages");for(int m=0;m<messages.length();m++)if("tool".equals(messages.getJSONObject(m).optString("role"))){JSONObject receipt=new JSONObject(messages.getJSONObject(m).getString("content"));JSONArray index=receipt.optJSONArray("researchOutputs");for(int n=0;index!=null&&n<index.length();n++)if("b".equals(index.getJSONObject(n).optString("memberId"))&&index.getJSONObject(n).optInt("researchRound")==1)expected=index.getJSONObject(n).getString("sha256");}assertEquals("actual earlier status receipt must supply the complete member hash",TeamWorkFiles.hash(longOutput),expected);stage=6;return tool("teams_get",new JSONObject().put("teamRunId",teamRun.get()).put("view","member_output").put("memberId","b").put("researchRound",1).put("expectedOutputHash",expected).put("offset",12000).put("length",4000));}
            if(!longOutput.isEmpty()){assertEquals(longOutput.substring(12000,16000),last.getString("content"));assertEquals(TeamWorkFiles.hash(longOutput),last.getJSONObject("researchOutput").getString("sha256"));assertEquals(longOutput.length(),last.getInt("totalChars"));return text("主代理向用户交付：已从原SQL准确回读长成员成果，展示裁切不改变来源hash。");}
            assertTrue(last.getString("content").contains("四名实际成员"));assertEquals("pocketworld.team-research-output.v1",last.getJSONObject("researchOutput").getString("format"));assertFalse(last.getBoolean("businessVerified"));return text("主代理向用户交付：原四成员研究已汇总，未执行项目代码；仍区分依据与未确认结论。");
        }
        public JSONObject start(String conversation,String goal,JSONObject message,ModelConfig config,RunControl live)throws Exception {
            captured=config;JSONObject task=tasks.startAttempt(tasks.createCapabilityTask(goal,conversation).getString("id"),"capabilities",32);parent=task.getString("id");parentRun=task.getString("runId");epoch=task.getInt("coordinatorEpoch");original=live;live.bindHostRun(parent,parentRun,epoch);
            tasks.openOwnerInputMailbox(parent,parentRun,epoch,conversation,live);JSONObject input=tasks.appendOwnerGoalInput(parent,parentRun,epoch,conversation,message,"queue",live);ownerBinding=new JSONObject().put("taskId",parent).put("runId",parentRun).put("epoch",epoch).put("conversationId",conversation);
            JSONObject answer=new JSONObject(ownerBinding.toString());for(String key:new String[]{"queueRevision","queueState","queueRole","ownerMessageRevision"})if(input.has(key))answer.put(key,input.get(key));return answer;
        }
        public JSONObject append(String conversation,JSONObject message,String mode,JSONObject expected,RunControl live)throws Exception {throw new SecurityException("本夹具没有第二个主人输入");}
        public JSONArray poll(JSONArray included,boolean boundary,boolean close,RunControl live)throws Exception {assertSame(original,live);return tasks.pollOwnerInputs(parent,parentRun,epoch,included,boundary,close,live);}
        public void completed(AgentRunner.OwnerReply reply,RunControl live)throws Exception {assertSame(original,live);acceptedReply=assistant.acceptTaskOwnerReply(reply,captured,live);ownerReplies.incrementAndGet();}
        JSONObject executeParent(String conversation,String goal,JSONArray initial,ModelConfig config,RunControl live)throws Exception {
            try{
                assertSame(original,live);parentPort=new HostToolSupervisor(features,tasks,parent,parentRun,epoch,true);JSONArray names=new JSONArray(),declared=parentPort.tools();for(int i=0;i<declared.length();i++)names.put(declared.getJSONObject(i).getJSONObject("function").getString("name"));tasks.authorizeTools(parent,parentRun,names);
                JSONObject approval=tasks.requireCapabilityApproval(parent,parentRun,"teams_start",startArguments,new JSONObject().put("feature","teams").put("command","start").put("teamDefinition",features.teamDefinition(teamId)));assertFalse(approval.getBoolean("approved"));tasks.decideCapabilityApproval(parent,approval.getString("approvalId"),true);
                var journal=TeamParentResearchHistory.journal(tasks.journal(parent,parentRun,epoch,original),()->features.checkTeamResearchParentInputs(parent,parentRun,epoch,original));if(initial!=null)journal.checkpoint(initial,0,new JSONObject());
                AgentRunner.RequestProjection projection=(history,c,control)->{Set<String> delegated=new HashSet<>();JSONArray inputs=tasks.get(parent,false).getJSONArray("ownerInputs");for(int i=0;i<inputs.length();i++)delegated.add(inputs.getJSONObject(i).getString("messageId"));return assistant.projectMemoryHistory(history,delegated,conversation);};
                outcome=new AgentRunner(null,parentPort,ContextBudgetPolicy.currentHostSource(),projection,this).run(parent,goal,config,original,event->{try{tasks.event(parent,parentRun,epoch,event);}catch(Exception e){throw new IllegalStateException(e);}},journal);
                trace("parent-outcome",new JSONObject().put("status",outcome.status).put("summary",outcome.summary).put("evidence",outcome.evidence).put("stage",stage).put("actualOwnerReply",acceptedReply==null?JSONObject.NULL:acceptedReply));
                tasks.endAttempt(parent,parentRun,outcome.status,outcome.summary,outcome.evidence);return new JSONObject().put("taskId",parent).put("content",outcome.evidence.optString("modelSummary",outcome.summary)).put("nativeOwnerReplyRecorded",acceptedReply!=null);
            }catch(Exception|Error error){failure.compareAndSet(null,error);throw error;}finally{parentDone.countDown();}
        }
        RunOutcome runParent()throws Exception {
            assistant.handle("send",new JSONObject().put("message","开始已批准团队研究，主代理负责本对话统筹和交付；成员只使用原批准研究工具，不写项目或操作手机").put("clientMessageId",id()).put("mode","queue"));assertTrue(parentDone.await(60,TimeUnit.SECONDS));long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(assistant.snapshot().optBoolean("running")&&System.nanoTime()<end)Thread.sleep(10);assertFalse(assistant.snapshot().optBoolean("running"));if(failure.get()!=null)throw new AssertionError(failure.get());assertNotNull(outcome);return outcome;
        }
        void assertActualOwnerReply(String expected)throws Exception {
            assertEquals(1,ownerReplies.get());assertNotNull(acceptedReply);assertFalse(acceptedReply.optBoolean("duplicate"));JSONObject current=assistant.handle("get",new JSONObject().put("conversationId",ownerBinding.getString("conversationId")));JSONArray rows=current.getJSONArray("messages");int replies=0;for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);if("assistant".equals(row.optString("role"))&&parent.equals(row.optString("taskId"))){assertTrue(row.getString("content").contains(expected));assertEquals(parentRun,row.getString("nativeRunId"));replies++;}}assertEquals("original Assistant must contain exactly one actual native parent reply",1,replies);
        }
        void trace(String name,JSONObject data)throws Exception{String root=System.getProperty("a25.researchEvidenceDir","");if(root.isEmpty())return;File dir=new File(root,parent);if(!dir.isDirectory()&&!dir.mkdirs())throw new IllegalStateException("own fixture evidence unavailable");byte[] body=data.toString().getBytes(StandardCharsets.UTF_8);if(body.length<=262144)Files.write(new File(dir,name+".json").toPath(),body);}
        public void close()throws Exception {
            releaseCancelledA.countDown();releasePeerFinal.countDown();releaseWakeSender.countDown();if(original!=null)original.cancel();if(parentPort!=null)parentPort.close();teams.close();for(String field:new String[]{"coordinator","members"}){var f=TeamCoordinator.class.getDeclaredField(field);f.setAccessible(true);assertTrue(((ExecutorService)f.get(coordinator)).awaitTermination(5,TimeUnit.SECONDS));}
            assistant.close();var turns=dev.pocketengine.features.assistant.AssistantModule.class.getDeclaredField("turns");turns.setAccessible(true);assertTrue(((ExecutorService)turns.get(assistant)).awaitTermination(5,TimeUnit.SECONDS));server.stop(0);if(teamRun.get()!=null)trace("final-native-sql",new JSONObject().put("parent",tasks.get(parent,false)).put("team",tasks.get(teamRun.get(),false)).put("coordinator",state()).put("httpCalls",http.get()).put("parentCalls",parentCalls.get()).put("memberCalls",memberCalls.get()));features.close();sql(tasks).close();ContextBudgetPolicy.installHostSource(c->null);
        }
    }
    @Test public void mainAgentUsesActualApprovedReadOnlyParentScopeToPauseQueueResumeAndReadResearchSummary()throws Exception {
        try(Domain d=new Domain(false)){
            RunOutcome result=d.runParent();assertEquals("needs_confirmation",result.status);assertEquals(5,d.stage);d.assertActualOwnerReply("主代理向用户交付");assertTrue(result.evidence.getString("modelSummary").contains("主代理向用户交付"));assertEquals("completed",d.state().getString("status"));assertEquals(0,d.sourceBodyReads.get());
            JSONObject team=d.tasks.get(d.teamRun.get(),false),scope=team.getJSONObject("teamResearchParentScope"),budget=d.tasks.teamBudgetSnapshot(d.teamRun.get());assertEquals(d.parent,scope.getString("parentTaskId"));assertFalse(scope.getBoolean("projectWriteAuthorized"));assertFalse(team.has("teamWriteScope"));assertFalse(team.has("teamWorkPlan"));assertEquals(d.http.get(),budget.getInt("modelRequestsReservedOrUsed"));assertFalse(d.tasks.hasUnresolved(d.parent));
            JSONArray inbox=d.state().getJSONArray("inbox");assertEquals(1,inbox.length());assertEquals(d.parent,inbox.getJSONObject(0).getString("senderParentTaskId"));assertEquals("consumed",inbox.getJSONObject(0).getString("status"));
            assertTrue(d.tasks.hasTeamResearchParentInputs(d.parent));assertThrows(SecurityException.class,()->d.features.checkTeamResearchParentInputs(d.parent,d.parentRun,d.epoch,d.original));
            assertThrows(SecurityException.class,()->d.teams.parentCall(d.parent,d.parentRun,d.epoch,"teams_get","",new JSONObject().put("teamRunId",d.teamRun.get()),d.original));
            JSONArray children=team.getJSONArray("relatedTasks");assertEquals(4,children.length());for(int i=0;i<children.length();i++){JSONObject child=d.tasks.get(children.getJSONObject(i).getString("taskId"),false);assertEquals("team_research",child.getJSONObject("origin").getString("kind"));assertEquals("completed",child.getString("status"));assertFalse(d.tasks.hasUnresolved(child.getString("id")));}
            assertTrue(d.coordinator.parentResearchOutputIndex(d.teamRun.get()).length()>=5);
            String other=d.tasks.createCapabilityTask("foreign owner task","").getString("id");JSONObject attempt=d.tasks.startAttempt(other,"capabilities",8);RunControl foreign=new RunControl();foreign.bindHostRun(other,attempt.getString("runId"),attempt.getInt("coordinatorEpoch"));d.tasks.authorizeTools(other,attempt.getString("runId"),new JSONArray().put("teams_get"));assertThrows(SecurityException.class,()->d.teams.parentCall(other,attempt.getString("runId"),attempt.getInt("coordinatorEpoch"),"teams_get","",new JSONObject().put("teamRunId",d.teamRun.get()),foreign));foreign.cancel();
        }
    }
    @Test public void actualPeerPersistenceUnknownBlocksParentCompletionAndIsNeverAutomaticallyReplayed()throws Exception {
        try(Domain d=new Domain(true)){
            RunOutcome result=d.runParent();assertEquals("needs_confirmation",result.status);assertTrue(result.evidence.getString("modelSummary").contains("效果未决"));d.assertActualOwnerReply("效果未决");assertEquals(1,d.lostPeerAck.get());
            JSONObject state=d.observedStatus;assertNotNull(state);assertThrows(SecurityException.class,()->d.teams.parentCall(d.parent,d.parentRun,d.epoch,"teams_get","",new JSONObject().put("teamRunId",d.teamRun.get()),d.original));assertEquals("blocked_unknown",state.getString("status"));assertTrue(state.getBoolean("hasUnresolvedEffects"));assertEquals(1,state.getInt("unknownChildCount"));assertFalse(state.getBoolean("resourcesReleased"));assertEquals(0,d.sourceBodyReads.get());
            int unknown=0;try(Cursor rows=sql(d.tasks).rawQuery("SELECT state FROM operations WHERE tool='team_send_message'",null)){while(rows.moveToNext()){unknown++;assertEquals("unknown",rows.getString(0));}}assertEquals(1,unknown);assertEquals(1,d.state().getJSONArray("mailbox").length());assertFalse(d.state().optBoolean("businessVerified"));
        }
    }
}
