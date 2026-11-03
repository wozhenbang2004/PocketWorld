package dev.pocketengine.runtime.policy;

import static org.junit.Assert.*;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public final class OwnerQuestionChoicesTest {
    private JSONObject option(String id,String label)throws Exception{return new JSONObject().put("id",id).put("label",label);}
    private JSONObject args()throws Exception{return new JSONObject().put("question","先做哪一项？").put("options",new JSONArray().put(option("read","先读取原资料")).put(option("wait","稍后再做")));}
    private JSONObject identity()throws Exception{return new JSONObject().put("taskId","task").put("runId","run").put("requestId","request").put("coordinatorEpoch",4).put("waitRevision",12L);}
    private JSONObject waiting()throws Exception{
        JSONObject result=args().put("format","pocketworld.owner-question.v1").put("replyKind","answer").put("approval",false).put("takeover",false);
        JSONObject id=identity();for(String key:id.keySet())result.put(key,id.get(key));return result;
    }
    @Test public void oldFreeTextQuestionRemainsValidAndChoicesAreOptional()throws Exception{
        JSONObject args=new JSONObject().put("question","请补充日期");RunOutcomeTools.validate(RunOutcomeTools.ASK,args);assertEquals(0,OwnerQuestionChoices.options(args).length());
    }
    @Test public void choicesAreCopiedAndOnlyTheirStoredLabelBecomesAnAnswer()throws Exception{
        JSONObject original=args();RunOutcomeTools.validate(RunOutcomeTools.ASK,original);JSONArray copy=OwnerQuestionChoices.options(original);copy.getJSONObject(0).put("label","changed");
        assertEquals("先读取原资料",original.getJSONArray("options").getJSONObject(0).getString("label"));assertEquals("先读取原资料",OwnerQuestionChoices.answer(waiting(),identity(),"read"));
    }
    @Test public void forgedAuthorityAndExtraFieldsAreRejected()throws Exception{
        for(String field:new String[]{"taskId","approval","grant","runId","execute"}){
            assertThrows(IllegalArgumentException.class,()->RunOutcomeTools.validate(RunOutcomeTools.ASK,args().put(field,true)));
            JSONObject input=args();input.getJSONArray("options").getJSONObject(0).put(field,true);
            assertThrows(IllegalArgumentException.class,()->RunOutcomeTools.validate(RunOutcomeTools.ASK,input));
        }
    }
    @Test public void duplicateIdsOrLabelsAndUnsafeIdsAreRejected()throws Exception{
        JSONObject ids=args();ids.getJSONArray("options").getJSONObject(1).put("id","read");assertThrows(IllegalArgumentException.class,()->OwnerQuestionChoices.options(ids));
        JSONObject labels=args();labels.getJSONArray("options").getJSONObject(1).put("label"," 先读取原资料 ");assertThrows(IllegalArgumentException.class,()->OwnerQuestionChoices.options(labels));
        for(String id:new String[]{"","a/b","a\ncommand","x".repeat(65)}){JSONObject invalid=args();invalid.getJSONArray("options").getJSONObject(0).put("id",id);assertThrows(IllegalArgumentException.class,()->OwnerQuestionChoices.options(invalid));}
    }
    @Test public void countTypeAndLengthLimitsAreEnforcedBeforeWaiting()throws Exception{
        for(Object value:new Object[]{JSONObject.NULL,"not an array",new JSONArray(),new JSONArray().put(option("only","仅一个")),new JSONArray().put(true).put(false)})
            assertThrows(IllegalArgumentException.class,()->RunOutcomeTools.validate(RunOutcomeTools.ASK,new JSONObject().put("question","选择").put("options",value)));
        JSONObject many=args();for(int i=2;i<6;i++)many.getJSONArray("options").put(option("o"+i,"label"+i));assertThrows(IllegalArgumentException.class,()->OwnerQuestionChoices.options(many));
        JSONObject longLabel=args();longLabel.getJSONArray("options").getJSONObject(0).put("label","x".repeat(301));assertThrows(IllegalArgumentException.class,()->OwnerQuestionChoices.options(longLabel));
    }
    @Test public void staleTaskRunEpochRequestAndRevisionCannotChooseAnotherWait()throws Exception{
        for(String key:new String[]{"taskId","runId","requestId"}){JSONObject stale=identity().put(key,"different");assertThrows(SecurityException.class,()->OwnerQuestionChoices.answer(waiting(),stale,"read"));}
        for(String key:new String[]{"coordinatorEpoch","waitRevision"}){JSONObject stale=identity().put(key,99);assertThrows(SecurityException.class,()->OwnerQuestionChoices.answer(waiting(),stale,"read"));}
        assertThrows(SecurityException.class,()->OwnerQuestionChoices.answer(waiting(),identity().put("waitRevision","12"),"read"));
        assertThrows(SecurityException.class,()->OwnerQuestionChoices.answer(waiting(),identity(),"unknown"));
    }
    @Test public void approvalAndTakeoverCannotBecomeOrdinaryChoiceAnswers()throws Exception{
        for(String key:new String[]{"approval","takeover"})assertThrows(SecurityException.class,()->OwnerQuestionChoices.answer(waiting().put(key,true),identity(),"read"));
        assertThrows(SecurityException.class,()->OwnerQuestionChoices.answer(waiting().put("replyKind","approve"),identity(),"read"));
    }
    @Test public void repeatedSelectionResolvesToTheSameExistingReplyPayload()throws Exception{
        JSONObject wait=waiting();String before=wait.toString();assertEquals(OwnerQuestionChoices.answer(wait,identity(),"read"),OwnerQuestionChoices.answer(wait,identity(),"read"));assertEquals(before,wait.toString());
    }
}
