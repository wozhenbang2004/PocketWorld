package dev.pocketengine.runtime;

import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
import org.json.*;
import org.junit.Test;

public final class RepeatToolNoticeTest {
    private static JSONObject arguments()throws Exception{return new JSONObject().put("query","PRIVATE OWNER BODY").put("range",new JSONObject().put("a",1).put("b",2));}
    private static JSONObject failed()throws Exception{return new JSONObject().put("ok",false).put("code","not_found").put("message","PRIVATE ORIGINAL RECEIPT");}
    private static void repeat(RepeatToolNotice notice,String tool,JSONObject args,JSONObject receipt,boolean readOnly){for(int i=0;i<3;i++)notice.afterTool(tool,args,receipt,readOnly);}
    @Test public void canonicalSameArgumentsAndFailedReadYieldOnlyOneBoundedBodyFreeNotice()throws Exception {
        RepeatToolNotice notice=new RepeatToolNotice();notice.afterTool("project_search",arguments(),failed(),true);
        JSONObject reordered=new JSONObject().put("range",new JSONObject().put("b",2).put("a",1)).put("query","PRIVATE OWNER BODY");
        notice.afterTool("project_search",reordered,failed(),true);assertEquals("",notice.referenceData());notice.afterTool("project_search",arguments(),failed(),true);
        String text=notice.referenceData();assertTrue(text.contains("pocketworld.repeat-tool-notice.v1"));assertTrue(text.getBytes(StandardCharsets.UTF_8).length<=RepeatToolNotice.MAX_REFERENCE_BYTES);
        assertFalse(text.contains("PRIVATE"));assertFalse(text.contains("query"));assertTrue(text.contains("\"automaticReplay\":false"));assertTrue(text.contains("\"executionAuthorized\":false"));assertTrue(text.contains("\"budgetsReset\":false"));
        assertEquals("",notice.referenceData());repeat(notice,"project_search",arguments(),failed(),true);assertEquals("",notice.referenceData());
    }
    @Test public void changedCallOrReceiptAndSuccessfulEffectAckDoNotShowNoProgress()throws Exception {
        RepeatToolNotice notice=new RepeatToolNotice();
        for(int i=0;i<8;i++)notice.afterTool("project_search",new JSONObject().put("query",i),failed(),true);assertEquals("",notice.referenceData());
        for(int i=0;i<8;i++)notice.afterTool("project_search",arguments(),failed().put("resultRevision",i),true);assertEquals("",notice.referenceData());
        repeat(notice,"phone_click_node",arguments(),new JSONObject().put("ok",true).put("code","ok"),false);assertEquals("",notice.referenceData());
        repeat(notice,"plugin_write",arguments(),failed(),false);assertEquals("",notice.referenceData());
    }
    @Test public void legitimatePollWaitStatusLogsAndUnknownOrRunningReceiptsNeverAccumulate()throws Exception {
        for(String name:new String[]{"phone_wait","remote_getJobStatus","project_job_poll","project_log_read","plugin_watch","remote_getJob","remote_list_jobs","original_receipt_read"}){RepeatToolNotice notice=new RepeatToolNotice();repeat(notice,name,arguments(),failed(),true);assertEquals(name,"",notice.referenceData());}
        for(JSONObject receipt:new JSONObject[]{failed().put("code","effect_unknown"),failed().put("code","action_timeout"),failed().put("status","unknown"),failed().put("accepted",JSONObject.NULL),failed().put("requiresReconciliation",true),failed().put("requiresPause",true),failed().put("code","project_running")}){
            RepeatToolNotice notice=new RepeatToolNotice();repeat(notice,"project_search",arguments(),receipt,true);assertEquals(receipt.toString(),"",notice.referenceData());
        }
    }
    @Test public void definiteOriginalNonDispatchCanWarnButNeverAuthorizeRetry()throws Exception {
        RepeatToolNotice notice=new RepeatToolNotice();repeat(notice,"project_write",arguments(),failed().put("accepted",false).put("nativeDispatched",false),false);
        String reference=notice.referenceData();assertTrue(reference.contains("pocketworld.repeat-tool-notice.v1"));assertTrue(reference.contains("\"automaticReplay\":false"));
    }
    @Test public void admittedOwnerFeedbackResetsSoftChainButObservationsDoNot()throws Exception {
        RepeatToolNotice notice=new RepeatToolNotice();JSONArray history=new JSONArray();notice.ownerBoundary(history);
        notice.afterTool("project_search",arguments(),failed(),true);notice.afterTool("project_search",arguments(),failed(),true);
        history.put(new JSONObject().put("role","user").put("content","native observation"));notice.ownerBoundary(history);notice.afterTool("project_search",arguments(),failed(),true);assertFalse(notice.referenceData().isEmpty());
        history.put(new JSONObject().put("role","user").put("content","Repeat explicitly").put("ownerMessageId","00000000-0000-4000-8000-000000000002").put("ownerInputMode","steer"));notice.ownerBoundary(history);
        notice.afterTool("project_search",arguments(),failed(),true);notice.afterTool("project_search",arguments(),failed(),true);assertEquals("",notice.referenceData());notice.afterTool("project_search",arguments(),failed(),true);assertFalse(notice.referenceData().isEmpty());
        history.put(new JSONObject().put("role","user").put("content","机主的新补充要求：\nNew feedback").put("ownerRecallFeedback",true));notice.ownerBoundary(history);assertEquals("",notice.referenceData());
    }
}
