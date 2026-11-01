package dev.pocketengine.features.teams;

import android.app.Application;
import dev.pocketengine.core.CapabilityPort;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.FeatureHost;
import dev.pocketengine.features.HostToolSupervisor;
import dev.pocketengine.features.workspace.WorkspaceModule;
import dev.pocketengine.runtime.ModelGateway;
import dev.pocketengine.store.SecretVault;
import dev.pocketengine.store.SettingsStore;
import dev.pocketengine.store.TaskStore;
import dev.pocketengine.testutil.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Real original room/TaskStore/ModelGateway/HostToolSupervisor/WorkspaceModule.
 * HTTP model replies are controlled fixtures, not a live model-quality or Android UI acceptance. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class TeamEvidenceDiscussionJourneyTest {
    private static String id(){return UUID.randomUUID().toString();}
    private static JSONObject message(JSONObject content)throws Exception {return new JSONObject().put("role","assistant").put("content",content.toString());}
    private static JSONObject tool(String project)throws Exception {return new JSONObject().put("role","assistant").put("content",JSONObject.NULL).put("tool_calls",new JSONArray().put(new JSONObject().put("id",id()).put("type","function").put("function",new JSONObject().put("name","workspace_read").put("arguments",new JSONObject().put("projectId",project).put("path","evidence.txt").toString()))));}
    private static JSONObject source(JSONArray history)throws Exception {String system=history.getJSONObject(0).getString("content"),marker=system.contains("原阶段输入：\n")?"原阶段输入：\n":"原输入：\n";return new JSONObject(system.substring(system.indexOf(marker)+marker.length()).split("\n",2)[0]);}
    private static JSONObject firstClaim(JSONObject input,String other)throws Exception {JSONArray positions=input.getJSONArray("positions");for(int i=0;i<positions.length();i++)if(!positions.getJSONObject(i).getString("memberId").equals(other))return positions.getJSONObject(i).getJSONArray("claims").getJSONObject(0);throw new AssertionError("missing original other claim");}
    private static final class Fixture implements AutoCloseable {
        TaskStore tasks;FeatureHost features;WorkspaceModule workspace;TeamCoordinator coordinator;HttpServer server;ModelConfig config;final String run=id();String project;final AtomicReference<Throwable> failure=new AtomicReference<>();final AtomicInteger calls=new AtomicInteger(),aCalls=new AtomicInteger(),bCalls=new AtomicInteger();final CountDownLatch responseEntered=new CountDownLatch(1),responseRelease=new CountDownLatch(1);final int reads;final boolean blockResponse,forgeEvidence;
        TeamCoordinator.EvidenceFactory evidence;
        Fixture(int reads,boolean blockResponse,boolean forgeEvidence)throws Exception {
            this.reads=reads;this.blockResponse=blockResponse;this.forgeEvidence=forgeEvidence;
            try{var app=RuntimeEnvironment.getApplication();app.deleteDatabase("pocket-world.db");tasks=new TaskStore(app);features=new FeatureHost(event->{},tasks,()->false);workspace=new WorkspaceModule(new FeatureContext(app,new SettingsStore(app),new SecretVault(app),features::recordEvent));features.register(workspace);
            project=workspace.handle("create",new JSONObject().put("name","原讨论真实来源")).getString("id");workspace.handle("write",new JSONObject().put("projectId",project).put("path","evidence.txt").put("content","当前受控范围：离线所选文字文件；网络命令没有授权。约束与测量不等于最终业务可用性。\n").put("expectedVersion",0));
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/",exchange->{try {JSONObject request=new JSONObject(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));JSONObject reply=reply(request);byte[] bytes=new JSONObject().put("model",request.getString("model")).put("choices",new JSONArray().put(new JSONObject().put("index",0).put("message",reply).put("finish_reason",reply.has("tool_calls")?"tool_calls":"stop"))).put("usage",new JSONObject().put("prompt_tokens",12).put("completion_tokens",5).put("total_tokens",17)).toString().getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);}catch(Throwable error){if(!blockResponse)failure.compareAndSet(null,error);try{exchange.sendResponseHeaders(500,0);}catch(Exception ignored){}}finally{exchange.close();}});server.start();config=new ModelConfig("http://127.0.0.1:"+server.getAddress().getPort()+"/v1","fixture-only","coordinator",false,20,10,"openai_chat",512);
            tasks.beginExternal("teams","start",run,new JSONObject().put("goal","依据原文件讨论能否默认开放网络，不以多数票冒充授权"));
            evidence=(nativeCall)->{JSONObject who=nativeCall.view();String taskId=who.getString("teamRunId"),runId=taskId,member=who.getString("memberId");JSONObject current=tasks.get(taskId,false);CapabilityPort port=new HostToolSupervisor(features,tasks,taskId,runId,current.getInt("coordinatorEpoch"),false);JSONArray offered=port.tools(),names=new JSONArray();for(int i=0;i<offered.length();i++)names.put(offered.getJSONObject(i).getJSONObject("function").getString("name"));tasks.authorizeTools(taskId,runId,names);return port;};
            coordinator=new TeamCoordinator(Files.createTempDirectory("original-evidence-room-").resolve("runs.json").toFile(),new TeamCoordinator.ToolGateway(){
                public JSONObject complete(ModelConfig model,JSONArray messages,JSONArray tools,RunControl control)throws Exception {throw new AssertionError("Every actual request must carry its original native call");}
                public JSONObject complete(ModelConfig model,JSONArray messages,JSONArray tools,RunControl control,TeamCoordinator.InferenceCall call)throws Exception {return ModelGateway.complete(model,messages,tools,control,tasks.teamInferenceTarget(call.nativeRunId,call.purpose,call.memberId,call.site,control,call::checkCurrent));}
            },event->{try{if(event.optJSONObject("run")!=null)tasks.updateExternal(run,event.getJSONObject("run"));}catch(Throwable error){failure.compareAndSet(null,error);}});
            JSONObject definition=new JSONObject().put("id",id()).put("name","原共同讨论现场").put("mode","debate").put("discussionFormat",TeamEvidenceDiscussion.FORMAT).put("leaderId","a").put("members",new JSONArray().put(new JSONObject().put("id","a").put("name","同名").put("role","先独立考虑可行性").put("model","author-a")).put(new JSONObject().put("id","b").put("name","同名").put("role","先独立考虑授权与风险").put("model","author-b")));
            coordinator.start(definition,"依据原文件讨论能否默认开放网络，不以多数票冒充授权",2,config,run,evidence,null);
            }catch(Exception|Error error){try{close();}catch(Exception suppressed){error.addSuppressed(suppressed);}throw error;}
        }
        JSONObject reply(JSONObject request)throws Exception {
            calls.incrementAndGet();String model=request.getString("model");if(model.equals("author-a"))aCalls.incrementAndGet();else if(model.equals("author-b"))bCalls.incrementAndGet();else throw new AssertionError("unselected author model "+model);
            JSONArray history=request.getJSONArray("messages");JSONObject input=source(history);String stage=input.getString("stage"),version=input.getString("inputVersion");
            if(stage.equals("conditional_decision")){
                JSONArray decisions=new JSONArray(),records=input.getJSONArray("actualChallenges");for(int i=0;i<records.length();i++){JSONArray items=records.getJSONObject(i).getJSONArray("challenges");for(int j=0;j<items.length();j++){JSONObject challenge=items.getJSONObject(j);decisions.put(new JSONObject().put("challengeId",challenge.getString("challengeId")).put("assessment",challenge.getString("claimOwnerId").equals("b")?"unresolved":"addressed").put("reason",challenge.getString("claimOwnerId").equals("b")?"原作者实际回应仍未决，需要更多约束证据":"原作者已实际修订边界，但不等于事实验证"));}}
                JSONObject first=input.getJSONArray("independentPositions").getJSONObject(0).getJSONArray("claims").getJSONObject(0);return message(new JSONObject().put("inputVersion",version).put("recommendation","只在原已授权离线范围继续，不默认开放网络或执行命令。").put("conditions",new JSONArray().put("需要额外命令/网络时回原native owner授权入口")).put("disagreements",new JSONArray().put(new JSONObject().put("claimIds",new JSONArray().put(first.getString("claimId"))).put("reason","可行性和权限是两回事；保留未决约束"))).put("challengeDecisions",decisions));
            }
            String member=input.getString("memberId");assertEquals(member.equals("a")?"author-a":"author-b",model);
            JSONArray refs=new JSONArray();int delivered=0;for(int i=0;i<history.length();i++)if(history.getJSONObject(i).optString("role").equals("tool")){JSONObject receipt=new JSONObject(history.getJSONObject(i).getString("content"));JSONObject ref=receipt.optJSONObject("nativeEvidenceRef");if(ref!=null){refs.put(ref.getString("id"));delivered++;assertEquals(run,ref.getString("teamRunId"));assertEquals(member,ref.getString("memberId"));assertTrue(receipt.getString("content").contains("网络命令没有授权"));}}
            if(stage.equals("independent")){
                assertFalse("independent phase cannot see another position",input.has("positions"));assertFalse(history.getJSONObject(1).getString("content").contains("上轮各方观点"));if(delivered<reads)return tool(project);
                return message(new JSONObject().put("inputVersion",version).put("position",member.equals("a")?"当前文件制作可离线完成":"不能把可行性当作网络权限").put("claims",new JSONArray().put(new JSONObject().put("text",member.equals("a")?"当前选定文本贡献可在离线边界内推进":"若无native明确授权，不能默认开放网络命令").put("basis","evidence").put("evidenceIds",forgeEvidence&&member.equals("a")?new JSONArray().put("model-invented-source"):refs))).put("uncertainties",new JSONArray().put("尚未执行完整项目build/test")));
            }
            if(stage.equals("challenge")){
                if(reads>1&&delivered<reads)return tool(project);JSONObject target=firstClaim(input,member);JSONArray actualRefs=refs;if(actualRefs.length()==0)for(int i=0;i<input.getJSONArray("positions").length();i++){JSONObject own=input.getJSONArray("positions").getJSONObject(i);if(member.equals(own.getString("memberId")))actualRefs=own.getJSONArray("claims").getJSONObject(0).getJSONArray("evidenceIds");}
                return message(new JSONObject().put("inputVersion",version).put("challenges",new JSONArray().put(new JSONObject().put("claimId",target.getString("claimId")).put("claimVersion",target.getString("claimVersion")).put("kind","counterevidence").put("text",member.equals("a")?"离线文本范围是否足以推出所有后续约束？原文没有完整项目检查":"原文明确没有网络命令授权，不应从可行性扩成权限").put("evidenceIds",actualRefs))).put("noChallengeReason",""));
            }
            assertEquals("response",stage);if(blockResponse&&member.equals("a")){responseEntered.countDown();assertTrue(responseRelease.await(8,TimeUnit.SECONDS));}
            JSONArray responses=new JSONArray(),incoming=input.getJSONArray("challenges");assertEquals(1,incoming.length());for(int i=0;i<incoming.length();i++){JSONObject challenge=incoming.getJSONObject(i);assertEquals(member,challenge.getString("claimOwnerId"));responses.put(new JSONObject().put("challengeId",challenge.getString("challengeId")).put("challengeVersion",challenge.getString("challengeVersion")).put("resolution",member.equals("a")?"revise":"unresolved").put("text",member.equals("a")?"实际读到你的质疑；修订为只在原离线文本scope推进，网络另问主人":"实际读到你的质疑，但无法由此排除完整项目依赖风险，保留未决").put("evidenceIds",new JSONArray()));}
            return message(new JSONObject().put("inputVersion",version).put("responses",responses).put("reflection","这是原作者对实际质疑的回应，不是客观质量批准。"));
        }
        JSONObject settled()throws Exception {long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);JSONObject value;do{if(failure.get()!=null)throw new AssertionError(failure.get());value=coordinator.get(run);if(!value.optString("status").matches("queued|running"))return value;Thread.sleep(10);}while(System.nanoTime()<until);throw new AssertionError("original team did not settle "+value);}
        int originalUsage()throws Exception {JSONArray events=tasks.get(run,false).getJSONArray("events");int count=0;Set<String> ids=new java.util.HashSet<>();for(int i=0;i<events.length();i++){JSONObject event=events.getJSONObject(i);if("inference_receipt".equals(event.optString("type"))){assertTrue(ids.add(event.getString("callId")));assertEquals(run,event.getJSONObject("context").getString("runId"));assertEquals("EXTERNAL_TASK_RUN",event.getJSONObject("context").getString("bindingKind"));count++;}}return count;}
        public void close()throws Exception {responseRelease.countDown();if(coordinator!=null)coordinator.close();if(server!=null)server.stop(0);if(coordinator!=null)for(String name:new String[]{"coordinator","members"}){java.lang.reflect.Field field=TeamCoordinator.class.getDeclaredField(name);field.setAccessible(true);assertTrue("owned discussion executor still active",((java.util.concurrent.ExecutorService)field.get(coordinator)).awaitTermination(3,TimeUnit.SECONDS));}if(features!=null)features.close();if(tasks!=null){java.lang.reflect.Field db=TaskStore.class.getDeclaredField("db");db.setAccessible(true);((android.database.sqlite.SQLiteDatabase)db.get(tasks)).close();}}
    }
    @Test public void realIndependentMembersReadOriginalFilesChallengeAndActuallyRespondBeforeConditionalDecision()throws Exception {
        try(Fixture f=new Fixture(1,false,false)){JSONObject run=f.settled();assertEquals(run.toString(),"completed",run.getString("status"));assertEquals(9,f.calls.get());assertTrue(f.aCalls.get()>=4);assertEquals(4,f.bCalls.get());assertEquals(9,f.originalUsage());assertEquals(13,run.getInt("callLimit"));assertEquals(9,run.getInt("callsUsed"));assertEquals(2,run.getInt("toolCallsUsed"));assertEquals(6,run.getJSONObject("discussion").getJSONArray("records").length());JSONObject decision=run.getJSONObject("discussion").getJSONObject("decision");assertTrue(decision.getBoolean("allOriginalAuthorsResponded"));assertEquals(1,decision.getJSONArray("unresolved").length());assertEquals(1,decision.getJSONArray("disagreements").length());assertFalse(decision.getBoolean("factVerified"));assertFalse(decision.getBoolean("executionAuthorized"));assertTrue(run.getString("summary").contains("未决质疑"));assertEquals("当前受控范围：离线所选文字文件；网络命令没有授权。约束与测量不等于最终业务可用性。\n",f.workspace.handle("read",new JSONObject().put("projectId",f.project).put("path","evidence.txt")).getString("content"));}
    }
    @Test public void forgedEvidenceDoesNotBecomeAClaimOrACompletedDebate()throws Exception {
        try(Fixture f=new Fixture(1,false,true)){JSONObject run=f.settled();assertEquals("partial",run.getString("status"));assertEquals("inconclusive",run.getJSONObject("discussion").getString("status"));assertFalse(run.getJSONObject("discussion").has("decision"));assertEquals(4,f.calls.get());JSONObject a=TeamWorkPlan.find(run.getJSONArray("members"),"a");assertTrue(TeamWorkPlan.state(a,1).getString("unacceptedDiscussionOutput").contains("model-invented-source"));assertEquals("failed",TeamWorkPlan.state(a,1).getString("status"));}
    }
    @Test public void oneOriginalThirteenRequestLimitStopsWithUnresolvedWorkWithoutAutoRefill()throws Exception {
        try(Fixture f=new Fixture(2,false,false)){JSONObject run=f.settled();assertEquals("partial",run.getString("status"));assertEquals("inconclusive",run.getJSONObject("discussion").getString("status"));assertEquals(13,run.getInt("callsUsed"));assertEquals(13,run.getInt("callLimit"));assertEquals(13,f.calls.get());assertEquals(13,f.originalUsage());assertEquals(8,run.getInt("toolCallsUsed"));assertFalse(run.getJSONObject("discussion").has("decision"));assertThrows(IllegalStateException.class,()->f.coordinator.resume(f.run,null,f.config,f.evidence,null));assertEquals(13,f.coordinator.get(f.run).getInt("callLimit"));}
    }
    @Test public void cancelledOriginalAuthorLateReplyCannotCompleteTheRoomOrPublishAConclusion()throws Exception {
        try(Fixture f=new Fixture(1,true,false)){assertTrue(f.responseEntered.await(12,TimeUnit.SECONDS));int used=f.coordinator.cancel(f.run).getInt("callsUsed");f.responseRelease.countDown();long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(System.nanoTime()<until&&f.tasks.get(f.run,false).getJSONArray("events").toString().contains("\"outcome\":\"STARTED\""))Thread.sleep(10);JSONObject stopped=f.coordinator.get(f.run);assertEquals("cancelled",stopped.getString("status"));assertFalse(stopped.getJSONObject("discussion").has("decision"));assertEquals(used,stopped.getInt("callsUsed"));assertThrows(IllegalStateException.class,()->f.coordinator.resume(f.run,null,f.config,f.evidence,null));}
    }
}
