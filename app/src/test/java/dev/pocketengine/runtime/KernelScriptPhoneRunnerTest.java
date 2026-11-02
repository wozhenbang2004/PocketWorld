package dev.pocketengine.runtime;

import dev.pocketengine.core.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Original Runner and HTTP Gateway. Native fixtures isolate its callback contract;
 * TaskStoreScriptPhoneScopeTest separately covers the actual original SQL scope. */
public final class KernelScriptPhoneRunnerTest {
    static final class Phone implements PhonePort {
        int writes;boolean unknown,failReadAfterWrite;RunControl original;
        public boolean isConnected(){return true;}
        public JSONObject observe(boolean pixels,RunControl current)throws Exception {
            assertSame(original,current);assertFalse(pixels);current.check();if(failReadAfterWrite&&writes>0)throw new IllegalStateException("native read unavailable");
            return new JSONObject().put("ok",true).put("observationId","actual-"+writes).put("packageName","app.example").put("nodes",new JSONArray().put(new JSONObject().put("id","n").put("text","搜索").put("clickable",true)));
        }
        public JSONObject execute(String tool,JSONObject args,RunControl current)throws Exception {
            assertSame(original,current);current.check();writes++;
            return unknown?new JSONObject().put("ok",false).put("accepted",JSONObject.NULL).put("code","action_timeout"):new JSONObject().put("ok",true).put("accepted",true);
        }
        public void clearReferences(){}
    }
    static final class Journal implements RunJournal {
        JSONArray history=new JSONArray();final List<String> tools=new ArrayList<>();final List<JSONObject> receipts=new ArrayList<>();
        public JSONObject restore(){return new JSONObject();}
        public void checkpoint(JSONArray messages,int steps,JSONObject observation)throws Exception {history=new JSONArray(messages.toString());}
        public String beginOperation(String tool,JSONObject args){tools.add(tool);return "native-op-"+tools.size();}
        public void finishOperation(String operation,JSONObject receipt)throws Exception {receipts.add(new JSONObject(receipt.toString()));}
    }
    static final class Skill implements CapabilityPort {
        ScriptPhoneDispatch.Actions callbacks;JSONObject clickReceipt;boolean executing,steerOnly,parentUnknown;
        public JSONArray tools()throws Exception {return new JSONArray().put(new JSONObject().put("type","function").put("function",new JSONObject().put("name","skills_execute").put("parameters",new JSONObject().put("type","object").put("properties",new JSONObject()).put("additionalProperties",false))));}
        public boolean supports(String name){return name.equals("skills_execute");}
        public boolean sideEffecting(String name){return supports(name);}
        public JSONObject execute(String name,JSONObject args,RunControl original)throws Exception {
            assertNotNull(callbacks);assertTrue(args.getString("_hostOperationId").startsWith("native-op-"));executing=true;
            JSONObject observed=callbacks.invoke("phone_observe",new JSONObject());
            if(!steerOnly)clickReceipt=callbacks.invoke("phone_click_node",new JSONObject().put("observationId",observed.getString("observationId")).put("nodeId","n"));
            executing=false;return parentUnknown?new JSONObject().put("ok",false).put("accepted",JSONObject.NULL).put("code","action_timeout"):new JSONObject().put("ok",true).put("accepted",true);
        }
    }
    private static JSONObject reply(boolean tool)throws Exception {
        JSONObject message=new JSONObject().put("role","assistant");
        if(tool)message.put("tool_calls",new JSONArray().put(new JSONObject().put("id","parent-call").put("type","function").put("function",new JSONObject().put("name","skills_execute").put("arguments","{}"))));else message.put("content","已执行搜索导航，请主人核对");
        return new JSONObject().put("model","fixture-model").put("choices",new JSONArray().put(new JSONObject().put("index",0).put("finish_reason",tool?"tool_calls":"stop").put("message",message)));
    }
    private static RunControl control(){RunControl original=new RunControl();original.bindHostRun("task","run",1);return original;}
    private static AutoCloseable install(Skill skill,RunControl original)throws Exception {
        return ScriptPhoneDispatch.installFactory((task,run,epoch,parent,current,actions)->{assertSame(original,current);assertEquals("native-op-1",parent);assertEquals("run",run);assertTrue(actions.allowedTools().contains("phone_observe"));assertFalse(actions.allowedTools().contains("skills_execute"));assertFalse(actions.allowedTools().contains("phone_list_apps"));assertFalse(actions.allowedTools().contains("phone_wait"));skill.callbacks=actions;return ()->{};});
    }
    private static RunOutcome run(KernelRunnerContractTest.Wire wire,Phone phone,Skill skill,Journal journal,RunControl original,AgentRunner.OwnerInputs inputs){phone.original=original;return new AgentRunner(phone,skill,selected->null,null,inputs).run("task","帮我点击搜索",wire.config(),original,event->{},journal);}
    @Test public void callbacksUseTheOriginalJournalAndControlAndStopAtParentExit()throws Exception {
        Phone phone=new Phone();Skill skill=new Skill();Journal journal=new Journal();RunControl original=control();
        try(AutoCloseable installation=install(skill,original);KernelRunnerContractTest.Wire wire=new KernelRunnerContractTest.Wire(reply(true),reply(false))){
            RunOutcome outcome=run(wire,phone,skill,journal,original,null);assertEquals("needs_confirmation",outcome.status);assertEquals(1,phone.writes);assertEquals(List.of("skills_execute","phone_observe","phone_click_node"),journal.tools);assertEquals(3,journal.receipts.size());assertTrue(skill.clickReceipt.getBoolean("accepted"));assertTrue(skill.clickReceipt.has("observation"));
            assertThrows(SecurityException.class,()->skill.callbacks.invoke("phone_observe",new JSONObject()));
        }
    }
    @Test public void anUnknownChildStopsTheParentEvenWhenBackendReturnsSuccess()throws Exception {
        Phone phone=new Phone();phone.unknown=true;Skill skill=new Skill();Journal journal=new Journal();RunControl original=control();
        try(AutoCloseable installation=install(skill,original);KernelRunnerContractTest.Wire wire=new KernelRunnerContractTest.Wire(reply(true),reply(false))){RunOutcome outcome=run(wire,phone,skill,journal,original,null);assertEquals("awaiting_user",outcome.status);assertEquals(1,phone.writes);assertEquals(1,wire.sent.size());assertTrue(outcome.evidence.getBoolean("requiresReconciliation"));assertTrue(journal.receipts.get(1).isNull("accepted"));}
    }
    @Test public void knownActionReceiptSurvivesPostReadFailure()throws Exception {
        Phone phone=new Phone();phone.failReadAfterWrite=true;Skill skill=new Skill();Journal journal=new Journal();RunControl original=control();
        try(AutoCloseable installation=install(skill,original);KernelRunnerContractTest.Wire wire=new KernelRunnerContractTest.Wire(reply(true),reply(false))){RunOutcome outcome=run(wire,phone,skill,journal,original,null);assertEquals("awaiting_user",outcome.status);assertEquals(1,wire.sent.size());assertEquals(1,phone.writes);assertTrue(journal.receipts.get(1).getBoolean("accepted"));assertTrue(skill.clickReceipt.getBoolean("accepted"));assertFalse(skill.clickReceipt.getBoolean("postObservationAvailable"));}
    }
    @Test public void queuedOwnerInputCannotSkipAnUnknownParentBarrier()throws Exception {
        Phone phone=new Phone();Skill skill=new Skill();skill.steerOnly=true;skill.parentUnknown=true;Journal journal=new Journal();RunControl original=control();String messageId=UUID.randomUUID().toString();boolean[] sent={false};
        AgentRunner.OwnerInputs inputs=(included,boundary,close,current)->{if(skill.executing&&!sent[0]){sent[0]=true;return new JSONArray().put(new JSONObject().put("messageId",messageId).put("mode","steer").put("content","请先停下并核对现场"));}return new JSONArray();};
        try(AutoCloseable installation=install(skill,original);KernelRunnerContractTest.Wire wire=new KernelRunnerContractTest.Wire(reply(true),reply(false))){RunOutcome outcome=run(wire,phone,skill,journal,original,inputs);assertEquals(outcome.summary+" "+outcome.evidence,"awaiting_user",outcome.status);assertEquals(1,wire.sent.size());assertEquals(0,phone.writes);assertTrue(journal.history.toString().contains(messageId));assertTrue(outcome.evidence.getBoolean("requiresReconciliation"));}
    }
}
