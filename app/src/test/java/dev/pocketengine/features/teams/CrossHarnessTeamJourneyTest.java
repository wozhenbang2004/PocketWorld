package dev.pocketengine.features.teams;

import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.features.remote.CompanionExternalMemberFactory;
import dev.pocketengine.features.remote.ExternalChildJournal;
import java.io.File;
import java.nio.file.Files;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Original coordinator and production factory, with paired-service receipts only mocked. */
public class CrossHarnessTeamJourneyTest {
    private JSONObject slot(String id,String runner)throws Exception{
        JSONObject value=new JSONObject().put("id",id).put("name",id).put("role","核对原资料并给出可追源结论").put("runner",runner);
        if(!runner.equals("model"))value.put("device",new JSONObject().put("profileId","paired").put("environmentId","workspace"));
        if(runner.equals("claude"))value.put("externalExecutionRiskAcknowledged",true);
        return value;
    }
    @Test public void phoneCodexClaudeShareOriginalTeamAndResumeEachOwnSessionAcrossRounds()throws Exception {
        File state=new File(Files.createTempDirectory("cross-harness-team").toFile(),"runs.json");
        Map<String,JSONObject> jobs=new ConcurrentHashMap<>();Map<String,String> sessions=new ConcurrentHashMap<>();AtomicInteger resumes=new AtomicInteger(),phoneCalls=new AtomicInteger();
        ExternalChildJournal journal=new ExternalChildJournal(){
            public JSONObject prepare(JSONObject request,JSONObject binding)throws Exception{return new JSONObject().put("taskId",request.getString("operationId")).put("operationId",request.getString("operationId")).put("knownOperation",jobs.containsKey(request.getString("operationId")));}
            public void record(JSONObject link,JSONObject receipt)throws Exception{assertEquals(link.getString("operationId"),receipt.getString("operationId"));}
        };
        CompanionExternalMemberFactory factory=new CompanionExternalMemberFactory((command,input)->{
            if(command.equals("environments"))return new JSONObject().put("environments",new JSONArray().put(new JSONObject().put("id","workspace").put("bindingHash","a".repeat(64)).put("workspace","/fixture/project").put("kind","ssh")));
            if(command.equals("probeWorker"))return new JSONObject().put("available",true).put("cliFlagsVerified",true).put("protocolVerified",!input.getString("worker").equals("claude")).put("accountConfigured",true).put("configReadVerified",true).put("capabilities",new JSONObject().put("readOnlySandbox",true));
            String operation=input.getString("operationId");if(command.equals("reconcileOperation"))return jobs.containsKey(operation)?new JSONObject(jobs.get(operation).toString()):new JSONObject().put("status","not_found");
            assertEquals("submitJob",command);assertFalse("Phone provider model must not be silently sent to a different harness",input.has("model"));String worker=input.getString("worker"),thread=sessions.computeIfAbsent(worker,k->UUID.randomUUID().toString());
            if(input.has("resumeJobId")){resumes.incrementAndGet();assertEquals(thread,input.getString("threadId"));assertTrue(jobs.values().stream().anyMatch(v->v.optString("id").equals(input.optString("resumeJobId"))));}
            JSONObject receipt=new JSONObject().put("id",UUID.randomUUID().toString()).put("operationId",operation).put("status","completed").put("worker",worker).put("environmentId","workspace").put("serviceBase","http://127.0.0.1:1234").put("threadId",thread).put("turnId",UUID.randomUUID().toString()).put("readOnly",true).put("output",worker+" real fixture result");
            if(worker.equals("claude"))receipt.put("protocol","claude_stream_json").put("protocolVerified",true).put("sessionIdentityConfirmed",true).put("sessionId",thread).put("teamReadOnlyTools",true);
            else receipt.put("protocol","codex_app_server").put("readOnlyConfirmed",true).put("externalToolsDisabled",true);
            jobs.put(operation,receipt);return new JSONObject(receipt.toString());
        },journal);
        ModelConfig model=new ModelConfig("https://example.invalid/v1","fixture","phone-model",false,20,10);String id;
        try(TeamCoordinator coordinator=new TeamCoordinator(state,(config,messages,control)->{assertEquals("phone-model",config.model);phoneCalls.incrementAndGet();return new JSONObject().put("content","phone native result");},e->{})){
            JSONObject team=new JSONObject().put("id","mixed").put("name","原混合团队").put("mode","collaborate").put("leaderId","phone").put("members",new JSONArray().put(slot("phone","model")).put(slot("codex","codex_app_server")).put(slot("claude","claude")));
            id=coordinator.start(team,"交叉核对证据",2,model,UUID.randomUUID().toString(),null,factory).getString("id");long deadline=System.nanoTime()+5_000_000_000L;JSONObject done;
            do{done=coordinator.get(id);if(done.optString("status").equals("completed"))break;Thread.sleep(10);}while(System.nanoTime()<deadline);
            assertEquals(done.toString(),"completed",done.getString("status"));assertEquals(4,done.getInt("externalTurnsUsed"));assertEquals(2,resumes.get());assertEquals(4,jobs.size());assertEquals(3,phoneCalls.get());
            for(int i=1;i<3;i++){JSONObject member=done.getJSONArray("members").getJSONObject(i);assertEquals(2,member.getJSONArray("roundStates").length());assertEquals(sessions.get(member.getString("runner")),member.getJSONObject("externalSession").getString("threadId"));}
        }
        try(TeamCoordinator restored=new TeamCoordinator(state,(c,m,k)->{fail("Reading history must not start inference");return null;},e->{})){assertEquals("completed",restored.get(id).getString("status"));assertEquals(4,jobs.size());}
    }
}
