package dev.pocketengine.runtime;

import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
import org.json.*;
import org.junit.Test;

public final class RepeatToolNoticeTest {
    private static JSONObject arguments()throws Exception{return new JSONObject().put("query","PRIVATE OWNER BODY").put("range",new JSONObject().put("a",1).put("b",2));}
    private static JSONObject failed()throws Exception{return new JSONObject().put("ok",false).put("code","permission_denied").put("message","PRIVATE ORIGINAL RECEIPT");}
    private static void round(RepeatToolNotice guard,String tool,Object args,JSONObject receipt,boolean readOnly,boolean notDispatched)throws Exception {
        guard.beginRound(1);guard.afterTool(tool,args,receipt,readOnly,notDispatched);guard.finishRound();
    }
    private static void failures(RepeatToolNotice guard,int count)throws Exception {for(int i=0;i<count;i++)round(guard,"project_search",arguments(),failed(),true,false);}

    @Test public void canonicalArgumentsIgnoreVolatileReceiptTextAndWarnOnceThenStop()throws Exception {
        RepeatToolNotice guard=new RepeatToolNotice();failures(guard,1);
        JSONObject reordered=new JSONObject().put("range",new JSONObject().put("b",2).put("a",1)).put("query","PRIVATE OWNER BODY");
        round(guard,"project_search",reordered,failed().put("message","different error wording").put("timestamp",123).put("operationId","different"),true,false);
        assertEquals("",guard.referenceData());failures(guard,1);
        String text=guard.referenceData();assertTrue(text.contains("pocketworld.progress-guard.v1"));assertTrue(text.getBytes(StandardCharsets.UTF_8).length<=RepeatToolNotice.MAX_REFERENCE_BYTES);
        assertFalse(text.contains("PRIVATE"));assertFalse(guard.snapshot().toString().contains("PRIVATE"));assertTrue(text.contains("\"automaticReplay\":false"));assertTrue(text.contains("\"executionAuthorized\":false"));assertTrue(text.contains("\"budgetsReset\":false"));
        assertEquals("",guard.referenceData());failures(guard,2);assertFalse(guard.stopRequired());failures(guard,1);assertTrue(guard.stopRequired());assertEquals("",guard.referenceData());
        assertEquals(6,guard.snapshot().getInt("consecutiveAllFailed"));assertEquals(1,guard.diagnostics().getInt("cyclePeriod"));
    }
    @Test public void alternatingFailureCycleWarnsAtABAAndStopsAfterThreeCycles()throws Exception {
        RepeatToolNotice guard=new RepeatToolNotice();for(int i=0;i<6;i++){
            round(guard,"project_search",new JSONObject().put("path",i%2==0?"A":"B"),failed(),true,false);
            if(i==2){assertFalse(guard.referenceData().isEmpty());assertEquals(2,guard.diagnostics().getInt("cyclePeriod"));}
            assertEquals(i==5,guard.stopRequired());
        }
        assertTrue(guard.stopSummary().contains("2 轮"));
    }
    @Test public void threeRoundCycleHasBoundedExplanatoryThreshold()throws Exception {
        RepeatToolNotice guard=new RepeatToolNotice();for(int i=0;i<9;i++){
            round(guard,"project_search",new JSONObject().put("path",i%3),failed(),true,false);assertEquals(i==8,guard.stopRequired());
        }
        assertEquals(3,guard.diagnostics().getInt("cyclePeriod"));
    }
    @Test public void oneBatchIsOneRoundAndAnyRealSuccessBreaksTheFailureChain()throws Exception {
        RepeatToolNotice guard=new RepeatToolNotice();guard.beginRound(8);for(int i=0;i<8;i++)guard.afterTool("project_search",arguments(),failed(),true,false);
        assertEquals(1,guard.snapshot().getInt("consecutiveAllFailed"));assertFalse(guard.stopRequired());
        guard.beginRound(2);guard.afterTool("project_search",arguments(),failed(),true,false);guard.afterTool("project_read",arguments(),new JSONObject().put("ok",true).put("sha256","a".repeat(64)),true,false);
        assertEquals(0,guard.snapshot().getInt("consecutiveAllFailed"));assertEquals(0,guard.snapshot().getJSONArray("cycleWindow").length());
    }
    @Test public void distinctPagingAndSourceRevisionChangesDoNotStopLegitimateWork()throws Exception {
        RepeatToolNotice guard=new RepeatToolNotice();for(int i=0;i<40;i++)round(guard,"project_search",new JSONObject().put("cursor","page-"+i),failed(),true,false);
        assertEquals("",guard.referenceData());assertFalse(guard.stopRequired());
        for(int i=0;i<40;i++)round(guard,"project_search",arguments(),failed().put("sourceSha256",String.format("%064x",i)),true,false);
        assertEquals(1,guard.snapshot().getInt("consecutiveAllFailed"));assertFalse(guard.stopRequired());assertEquals("",guard.referenceData());
    }
    @Test public void pollingWaitLogsAndUnknownNeverBecomeFailedRoundsOrEraseEarlierEvidence()throws Exception {
        for(String name:new String[]{"phone_wait","remote_getJobStatus","project_job_poll","project_log_read","plugin_watch","remote_getJob","remote_list_jobs","original_receipt_read"}){
            RepeatToolNotice guard=new RepeatToolNotice();failures(guard,2);for(int i=0;i<12;i++)round(guard,name,arguments(),failed(),true,false);
            assertEquals(name,2,guard.snapshot().getInt("consecutiveAllFailed"));assertEquals("",guard.referenceData());assertFalse(guard.stopRequired());
        }
        for(JSONObject receipt:new JSONObject[]{failed().put("code","effect_unknown"),failed().put("code","action_timeout"),failed().put("status","unknown"),failed().put("accepted",JSONObject.NULL),failed().put("requiresReconciliation",true),failed().put("requiresPause",true),failed().put("code","project_running")}){
            RepeatToolNotice guard=new RepeatToolNotice();failures(guard,2);round(guard,"project_search",arguments(),receipt,true,false);
            assertEquals(2,guard.snapshot().getInt("consecutiveAllFailed"));assertFalse(guard.stopRequired());
        }
    }
    @Test public void originalPreDispatchRefusalsCountButReceiptAssertionsDoNotProveNonDispatch()throws Exception {
        JSONObject refusal=failed().put("accepted",false).put("nativeDispatched",false);
        RepeatToolNotice actual=new RepeatToolNotice();for(int i=0;i<6;i++)round(actual,"project_write",arguments(),refusal,false,true);assertTrue(actual.stopRequired());
        RepeatToolNotice jsonOnly=new RepeatToolNotice();for(int i=0;i<30;i++)round(jsonOnly,"project_write",arguments(),refusal,false,false);
        assertFalse(jsonOnly.stopRequired());assertEquals(0,jsonOnly.snapshot().getInt("consecutiveAllFailed"));
    }
    @Test public void definitePreDispatchWaitAndPollingRefusalsStillWarnAndStop()throws Exception {
        for(String name:new String[]{"phone_wait","remote_list_jobs","remote_get_job"}){
            RepeatToolNotice guard=new RepeatToolNotice();
            for(int i=0;i<6;i++){
                round(guard,name,new JSONObject().put("ms",2501),failed().put("code","invalid_arguments"),false,true);
                if(i==2)assertFalse(name,guard.referenceData().isEmpty());
            }
            assertEquals(name,6,guard.snapshot().getInt("consecutiveAllFailed"));assertTrue(name,guard.stopRequired());
        }
    }
    @Test public void completedPollingResultBreaksTheOldFailureChain()throws Exception {
        RepeatToolNotice guard=new RepeatToolNotice();failures(guard,3);
        JSONObject result=new JSONObject().put("ok",true).put("status","completed").put("sourceSha256","a".repeat(64));
        round(guard,"remote_get_job",arguments(),result,true,false);
        assertEquals(0,guard.snapshot().getInt("consecutiveAllFailed"));assertEquals("",guard.referenceData());
        failures(guard,3);assertFalse(guard.stopRequired());assertEquals(3,guard.snapshot().getInt("consecutiveAllFailed"));
        round(guard,"remote_get_job",arguments(),result,true,false);
        assertEquals(3,guard.snapshot().getInt("consecutiveAllFailed"));failures(guard,3);assertTrue(guard.stopRequired());
    }
    @Test public void successfulWaitWithoutNewResultRetainsEarlierFailures()throws Exception {
        for(String status:new String[]{"","pending","queued","running","unknown"}){
            RepeatToolNotice guard=new RepeatToolNotice();failures(guard,2);
            JSONObject receipt=new JSONObject().put("ok",true).put("status",status);
            if(!status.isEmpty())receipt.put("sourceSha256","a".repeat(64));
            for(int i=0;i<8;i++)round(guard,"remote_get_job",arguments(),receipt,true,false);
            assertEquals(status,2,guard.snapshot().getInt("consecutiveAllFailed"));assertFalse(guard.stopRequired());
        }
    }
    @Test public void waitingForLogsResetsOnlyWhenItsActualSourceChanges()throws Exception {
        RepeatToolNotice guard=new RepeatToolNotice();JSONObject unchanged=new JSONObject().put("ok",true).put("sourceSha256","a".repeat(64));
        round(guard,"project_log_read",arguments(),unchanged,true,false);failures(guard,3);
        round(guard,"project_log_read",arguments(),unchanged,true,false);
        assertEquals(3,guard.snapshot().getInt("consecutiveAllFailed"));
        round(guard,"project_log_read",arguments(),new JSONObject(unchanged.toString()).put("sourceSha256","b".repeat(64)),true,false);
        assertEquals(0,guard.snapshot().getInt("consecutiveAllFailed"));assertFalse(guard.stopRequired());assertEquals("",guard.referenceData());
    }
    @Test public void invalidArgumentsHaveAStableDigestWithoutStoringTheirRawBody()throws Exception {
        RepeatToolNotice guard=new RepeatToolNotice();for(int i=0;i<6;i++)round(guard,"project_write","PRIVATE BROKEN JSON {",failed().put("code","invalid_arguments"),false,true);
        assertTrue(guard.stopRequired());assertFalse(guard.snapshot().toString().contains("PRIVATE"));
    }
    @Test public void coldRestoreRetainsCountWarningAndHardStopWithoutNewOwnerEvidence()throws Exception {
        RepeatToolNotice guard=new RepeatToolNotice();failures(guard,3);assertFalse(guard.referenceData().isEmpty());JSONObject saved=guard.snapshot();
        RepeatToolNotice cold=new RepeatToolNotice();cold.restore(new JSONObject(saved.toString()));assertEquals("",cold.referenceData());
        cold.ownerBoundary(new JSONArray().put(new JSONObject().put("role","user").put("content","正在恢复同一个任务")));
        failures(cold,3);assertTrue(cold.stopRequired());RepeatToolNotice stopped=new RepeatToolNotice();stopped.restore(cold.snapshot());assertTrue(stopped.stopRequired());
        assertThrows(IllegalStateException.class,()->stopped.beginRound(1));
        stopped.ownerBoundary(new JSONArray().put(new JSONObject().put("role","user").put("content","权限已修正，请重新核对").put("ownerMessageId","00000000-0000-4000-8000-000000000002")));
        assertFalse(stopped.stopRequired());assertEquals(0,stopped.snapshot().getInt("consecutiveAllFailed"));
    }
    @Test public void partialRoundColdRestoreDoesNotInventFailuresForUnknownTail()throws Exception {
        RepeatToolNotice guard=new RepeatToolNotice();failures(guard,2);guard.beginRound(2);guard.afterTool("project_search",arguments(),failed(),true,false);
        RepeatToolNotice cold=new RepeatToolNotice();cold.restore(guard.snapshot());assertEquals(2,cold.snapshot().getInt("consecutiveAllFailed"));assertTrue(cold.snapshot().isNull("pendingRound"));
        failures(cold,1);assertEquals(3,cold.snapshot().getInt("consecutiveAllFailed"));
    }
    @Test public void oldCheckpointWriterPreservesGuardAndCannotRegressItsRevision()throws Exception {
        RepeatToolNotice guard=new RepeatToolNotice();failures(guard,2);JSONObject old=guard.snapshot(),envelope=new JSONObject().put("progressGuard",old);
        assertEquals(old.toString(),RepeatToolNotice.checkpointValue(envelope,null).toString());
        failures(guard,1);JSONObject current=guard.snapshot();assertEquals(current.toString(),RepeatToolNotice.checkpointValue(envelope,current).toString());
        assertThrows(SecurityException.class,()->RepeatToolNotice.checkpointValue(new JSONObject().put("progressGuard",current),old));
        JSONObject changed=new JSONObject(old.toString()).put("noticePending",true);
        assertThrows(SecurityException.class,()->RepeatToolNotice.checkpointValue(envelope,changed));
    }
    @Test public void malformedPersistedStateFailsClosedInsteadOfStartingFresh()throws Exception {
        RepeatToolNotice guard=new RepeatToolNotice();failures(guard,2);JSONObject value=guard.snapshot();
        assertThrows(SecurityException.class,()->new RepeatToolNotice().restore(new JSONObject(value.toString()).put("format","future")));
        assertThrows(SecurityException.class,()->new RepeatToolNotice().restore(new JSONObject(value.toString()).put("progressGuardRevision",1.5)));
        assertThrows(SecurityException.class,()->new RepeatToolNotice().restore(new JSONObject(value.toString()).put("stopped",true)));
        assertThrows(SecurityException.class,()->RepeatToolNotice.checkpointValue(new JSONObject().put("progressGuard","invalid"),value));
    }
    @Test public void retainedStateStaysBoundedAfterLongDistinctFailureHistory()throws Exception {
        RepeatToolNotice guard=new RepeatToolNotice();for(int i=0;i<400;i++)round(guard,"project_search",new JSONObject().put("path",i),failed(),true,false);
        JSONObject state=guard.snapshot();assertEquals(12,state.getJSONArray("cycleWindow").length());assertEquals(24,state.getJSONArray("recentFingerprints").length());
        assertTrue(state.toString().getBytes(StandardCharsets.UTF_8).length<=RepeatToolNotice.MAX_STATE_BYTES);assertFalse(guard.stopRequired());
    }
}
