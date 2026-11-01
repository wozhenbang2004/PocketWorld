package dev.pocketengine.features.teams;

import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.core.RunJournal;
import dev.pocketengine.core.RunOutcome;
import dev.pocketengine.features.workspace.JsonFiles;
import dev.pocketengine.runtime.AgentRunner;
import dev.pocketengine.runtime.context.ContextBudgetPolicy;
import dev.pocketengine.runtime.context.ContextWindow;
import dev.pocketengine.testutil.HttpServer;
import java.io.File;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Real coordinator -> original AgentRunner -> actual loopback model -> real file/artifact bytes.
 * NativeTasks is an explicit original-journal/owner-input fixture; this is not Android TaskStore SQL validation. */
public final class TeamAssignmentRunnerJourneyTest {
    static final class Entry {
        JSONObject binding;String status="queued",summary="",question="",questionId="",answer="",answerId="";boolean consumed,lastOwnerContinuation;int epoch;RunControl control;
        final TeamWorkFilesTest.PreparedHost host=new TeamWorkFilesTest.PreparedHost();final File checkpoint;
        Entry(File checkpoint){this.checkpoint=checkpoint;}
    }
    static final class NativeTasks implements TeamAssignmentHost.Tasks {
        final Map<String,Entry> entries=new LinkedHashMap<>();final File root;NativeTasks(File root)throws Exception{this.root=root;java.nio.file.Files.createDirectories(root.toPath());}
        @Override public synchronized JSONObject begin(TeamCoordinator.AssignmentCall call,RunControl control,int allocation)throws Exception {
            call.checkCurrent();Entry e=entries.get(call.assignmentId);if(e==null){e=new Entry(new File(root,call.assignmentId+".json"));entries.put(call.assignmentId,e);}if(e.status.equals("completed")||!e.questionId.isEmpty()&&e.answer.isEmpty())return new JSONObject().put("inspectOnly",true);
            e.lastOwnerContinuation=call.ownerContinuation;e.epoch++;e.control=control;e.status="running";e.binding=new JSONObject().put("taskId",call.assignmentId).put("runId",TeamWorkFilesTest.id()).put("coordinatorEpoch",e.epoch).put("teamRunId",call.teamRunId).put("assignmentId",call.assignmentId).put("memberId",call.memberId).put("targetProjectId",call.scope().getString("targetProjectId")).put("files",call.files()).put("stepCeiling",40);
            control.bindHostRun(call.assignmentId,e.binding.getString("runId"),e.epoch);return new JSONObject(e.binding.toString());
        }
        @Override public AutoCloseable retainExecution(JSONObject binding,RunControl original)throws Exception {Entry e=entry(binding);return original.retainAuthorityCheck(()->{if(!e.status.equals("running"))throw new SecurityException("fixture original task already settled");});}
        Entry entry(JSONObject binding)throws Exception {Entry e=entries.get(binding.getString("assignmentId"));if(e==null||!e.binding.getString("runId").equals(binding.getString("runId")))throw new SecurityException("fixture original run changed");return e;}
        @Override public TeamWorkFiles.Host files(TeamCoordinator.AssignmentCall call,JSONObject binding,RunControl original)throws Exception {Entry e=entry(binding);return new TeamWorkFiles.Host(){
            public void check(String name,RunControl control)throws Exception {call.checkCurrent();if(control!=original||e.control!=original)throw new SecurityException("fixture original control changed");e.host.check(name,original);}
            public JSONObject claim(String op,String tool,JSONObject args,JSONObject capability,RunControl control)throws Exception {check(tool,control);return e.host.claim(op,tool,args,capability,control);}
            public JSONArray receipts(String tool)throws Exception{return e.host.receipts(tool);}
            public JSONObject question(String op,String text,RunControl control)throws Exception {check("team_request_input",control);e.questionId=op;e.question=text;return e.host.question(op,text,control);}
            public JSONObject message(String op,JSONObject args,RunControl control)throws Exception {check("team_send_message",control);return call.queuePeerMessage(op,args);}
        };}
        @Override public RunJournal journal(JSONObject binding,RunControl original)throws Exception {Entry e=entry(binding);return new RunJournal(){
            public JSONObject restore()throws Exception {original.check();return JsonFiles.read(e.checkpoint);}
            public void checkpoint(JSONArray messages,int steps,JSONObject observation)throws Exception {original.check();JsonFiles.write(e.checkpoint,new JSONObject().put("messages",messages).put("steps",steps).put("observation",observation));}
            public String beginOperation(String tool,JSONObject args)throws Exception {original.check();return e.host.prepare(tool,args,true);}
            public void finishOperation(String operation,JSONObject receipt)throws Exception {e.host.settle(operation,receipt);}
        };}
        @Override public AgentRunner.OwnerInputs inputs(JSONObject binding,RunControl original)throws Exception {Entry e=entry(binding);return new AgentRunner.OwnerInputs(){
            public JSONArray poll(JSONArray included,boolean boundary,boolean close,RunControl control)throws Exception {if(control!=original)throw new SecurityException("wrong original");JSONArray result=new JSONArray();if(e.answer.isEmpty()||e.consumed)return result;for(int i=0;i<included.length();i++)if(e.answerId.equals(included.getString(i)))return result;return result.put(new JSONObject().put("messageId",e.answerId).put("mode","queue").put("content","主人对原问题 "+e.questionId+" 的回答："+e.answer));}
            public void completed(AgentRunner.OwnerReply reply,RunControl control)throws Exception {assertSame(original,control);assertEquals(binding.getString("taskId"),reply.taskId);if(reply.messageIds().contains(e.answerId))e.consumed=true;}
        };}
        @Override public void event(JSONObject binding,JSONObject event)throws Exception {entry(binding);}
        @Override public void end(JSONObject binding,RunOutcome outcome)throws Exception {Entry e=entry(binding);e.summary=outcome.summary;e.status=outcome.status.equals("needs_confirmation")&&e.host.receipts("team_freeze_outputs").length()>0?"completed":outcome.status;if(!e.questionId.isEmpty()&&e.answer.isEmpty())e.status="awaiting_input";}
        @Override public JSONObject inspect(TeamCoordinator.AssignmentCall call)throws Exception {Entry e=entries.get(call.assignmentId);JSONArray receipts=e.host.receipts("team_freeze_outputs"),artifacts=new JSONArray();for(int i=0;i<receipts.length();i++)if(receipts.getJSONObject(i).optJSONArray("teamArtifacts")!=null)artifacts=receipts.getJSONObject(i).getJSONArray("teamArtifacts");return new JSONObject().put("assignmentId",call.assignmentId).put("taskId",call.assignmentId).put("status",e.status).put("artifacts",artifacts).put("message",e.summary);}
    }
    private JSONObject response(String model,int call)throws Exception {
        String tool;JSONObject args=new JSONObject();if(call==1)tool="team_prepare_draft";else if(model.equals("a")&&call==2){tool="team_request_input";args.put("question","原文件是否保留中文标题？");}
        else if(model.equals("a")&&call==3){tool="team_read_file";args.put("path","src/a.txt");}
        else if(model.equals("a")&&call==4||model.equals("b")&&call==2){tool="team_write_file";args.put("path",model.equals("a")?"src/a.txt":"src/b.txt").put("content",model.equals("a")?"原文件\n保留中文标题后的成员产出\n":"其他成员文件\n乙实际补充\n").put("expectedVersion",1);}
        else if(model.equals("a")&&call==5||model.equals("b")&&call==3)tool="team_freeze_outputs";
        else if(model.equals("a")&&call==6){tool="team_send_message";args.put("targetMemberId","b").put("text","我已保留中文标题，原冻结成果可核对；这条消息不授予执行权限。");}
        else return new JSONObject().put("role","assistant").put("content","已按真实冻结回执交付；等待主人审查原文件差异。");
        return new JSONObject().put("role","assistant").put("content",JSONObject.NULL).put("tool_calls",new JSONArray().put(new JSONObject().put("id",model+"-call-"+call).put("type","function").put("function",new JSONObject().put("name",tool).put("arguments",args.toString()))));
    }
    private JSONObject settled(TeamCoordinator coordinator,String id)throws Exception {long end=System.nanoTime()+12_000_000_000L;while(System.nanoTime()<end){JSONObject run=coordinator.get(id);if(!run.optString("status").matches("queued|running|stopping"))return run;Thread.sleep(10);}throw new AssertionError(coordinator.get(id));}
    @Test public void originalRunnerPausesForOriginalQuestionThenResumesSameAssignmentAndProducesRealFiles()throws Exception {runOriginalJourney(false);}
    @Test public void exactReplyUsesSameOriginalRunnerWithoutOwnerContinuationOrBudgetRefill()throws Exception {runOriginalJourney(true);}
    private void runOriginalJourney(boolean safeReply)throws Exception {
        TeamWorkFilesTest.Fixture f=new TeamWorkFilesTest.Fixture();NativeTasks nativeTasks=new NativeTasks(new File(f.root,"original-journals"));AtomicInteger a=new AtomicInteger(),b=new AtomicInteger();
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/",exchange->{JSONObject request=new JSONObject(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));String model=request.getString("model");assertTrue(request.getJSONArray("messages").getJSONObject(0).getString("content").contains("机主明确分配文件任务"));JSONArray tools=request.getJSONArray("tools");for(int i=0;i<tools.length();i++)assertTrue(tools.getJSONObject(i).getJSONObject("function").getString("name").startsWith("team_"));int call=model.equals("a")?a.incrementAndGet():b.incrementAndGet();byte[] bytes=new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("index",0).put("message",response(model,call)).put("finish_reason",response(model,call).has("tool_calls")?"tool_calls":"stop"))).toString().getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();});server.start();
        ContextBudgetPolicy.installHostSource(selected->ContextBudgetPolicy.RouteBudget.forRoute(selected,new ContextWindow.Budget(128000,1000,1000,2L*1024*1024,64,0),ContextBudgetPolicy.SourceKind.OWNER_OVERRIDE,1,System.currentTimeMillis()));
        try(TeamCoordinator coordinator=new TeamCoordinator(new File(f.root,"team-runs.json"),(model,messages,tools,control)->{throw new AssertionError("produce mode cannot run the read-only research/synthesis loop");},event->{})){
            coordinator.setAssignmentFactory(new TeamAssignmentHost(nativeTasks,f.workspace));JSONObject team=new JSONObject().put("id",TeamWorkFilesTest.id()).put("name","真实文件协作").put("mode","produce").put("members",new JSONArray().put(new JSONObject().put("id","a").put("name","甲").put("role","修改原文件").put("runner","model").put("device","phone").put("model","a").put("writeFiles",new JSONArray().put("src/a.txt"))).put(new JSONObject().put("id","b").put("name","乙").put("role","补充另一文件").put("runner","model").put("device","phone").put("model","b").put("writeFiles",new JSONArray().put("src/b.txt"))));
            ModelConfig model=new ModelConfig("http://127.0.0.1:"+server.getAddress().getPort()+"/v1","fixture-secret","a",false,24,10,"openai_chat",256);String runId=TeamWorkFilesTest.id();TeamWriteScope scope=TeamWriteScope.capture(f.workspace,TeamWorkFilesTest.id(),TeamWorkFilesTest.id(),f.project,team);
            assertThrows(SecurityException.class,()->coordinator.start(team,"共同完成两个文件",1,model));coordinator.startWithScope(team,"共同完成两个文件",1,model,runId,null,null,scope);JSONObject paused=settled(coordinator,runId);assertEquals("calls a="+a.get()+" b="+b.get()+paused.toString(2),"paused",paused.getString("status"));JSONObject member=TeamWorkPlan.find(paused.getJSONArray("members"),"a");String assignment=member.getJSONArray("roundStates").getJSONObject(0).getString("assignmentId");Entry original=nativeTasks.entries.get(assignment);assertEquals("awaiting_input",member.getString("status"));assertFalse(original.questionId.isEmpty());assertEquals(2,a.get());assertEquals("原文件\n",f.files.read(f.project,"src/a.txt",0).getString("content"));
            original.answerId=TeamWorkFilesTest.id();original.answer="保留原中文标题";assertFalse(original.consumed);assertEquals(2,a.get());
            if(safeReply){
                // An explicit existing owner-budget continuation is first admitted while the question remains pending.
                // Reply itself must then neither replenish nor manufacture an ownerContinuation generation.
                coordinator.resume(runId,"b",model,null,null);paused=settled(coordinator,runId);member=TeamWorkPlan.find(paused.getJSONArray("members"),"a");int beforeLimit=paused.getInt("callLimit"),memberGeneration=member.getInt("generation");
                JSONObject input=new JSONObject().put("runId",runId).put("memberId","a").put("assignmentId",assignment).put("questionId",original.questionId).put("answerId",original.answerId).put("answer",original.answer).put("quoteMessageId","question:"+original.questionId).put("expectedRevision",paused.getLong("revision")).put("expectedGeneration",paused.getInt("generation")).put("expectedMemberGeneration",memberGeneration).put("continueSafely",true);
                AtomicInteger claimed=new AtomicInteger();JSONObject receipt=coordinator.answerAssignment(input,new TeamCoordinator.AssignmentAnswers(){public JSONObject save(JSONObject supplied)throws Exception {assertEquals(original.questionId,supplied.getString("questionId"));return new JSONObject().put("questionId",original.questionId).put("answerId",original.answerId).put("state","answered");}public JSONObject claimContinuation(JSONObject supplied)throws Exception {assertEquals(1,claimed.incrementAndGet());return new JSONObject().put("claimed",true).put("reservationId",TeamWorkFilesTest.id());}},model,null,null);
                assertEquals("queued",receipt.getString("continuationState"));assertEquals(beforeLimit,coordinator.get(runId).getInt("callLimit"));assertEquals(memberGeneration,TeamWorkPlan.find(coordinator.get(runId).getJSONArray("members"),"a").getInt("generation"));
            }else coordinator.resume(runId,"a",model,null,null);JSONObject reviewed=settled(coordinator,runId);assertEquals("awaiting_review",reviewed.getString("status"));assertEquals(assignment,TeamWorkPlan.find(reviewed.getJSONArray("members"),"a").getJSONArray("roundStates").getJSONObject(0).getString("assignmentId"));assertTrue(original.consumed);if(safeReply)assertFalse(original.lastOwnerContinuation);assertEquals(7,a.get());assertEquals(a.get()+b.get(),reviewed.getInt("callsUsed"));assertEquals(3,f.files.projects().length());assertEquals(2,f.artifacts.list(null).length());assertEquals("原文件\n",f.files.read(f.project,"src/a.txt",0).getString("content"));assertEquals("其他成员文件\n",f.files.read(f.project,"src/b.txt",0).getString("content"));assertTrue(JsonFiles.read(original.checkpoint).getJSONArray("messages").toString().contains(original.answerId));
            int before=a.get();coordinator.feedback(runId,"a","请修订已冻结的文案");coordinator.resume(runId,"a",model,null,null);JSONObject blocked=settled(coordinator,runId);assertEquals("paused",blocked.getString("status"));assertEquals(before,a.get());assertEquals(3,f.files.projects().length());assertEquals("pending",blocked.getJSONArray("inbox").getJSONObject(0).getString("status"));
            assertEquals(1,blocked.getJSONArray("mailbox").length());assertEquals("unread",blocked.getJSONArray("mailbox").getJSONObject(0).getString("status"));JSONObject peer=TeamWorkPlan.find(blocked.getJSONArray("members"),"b");int bBefore=b.get();coordinator.continueMessages(runId,"b",blocked.getLong("revision"),peer.getInt("generation"),model,null,null);JSONObject noFalseConsumption=settled(coordinator,runId);assertEquals("paused",noFalseConsumption.getString("status"));assertEquals(bBefore,b.get());assertEquals("unread",noFalseConsumption.getJSONArray("mailbox").getJSONObject(0).getString("status"));
        }finally{server.stop(0);ContextBudgetPolicy.installHostSource(selected->null);}
    }
}
