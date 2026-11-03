package dev.pocketengine.phone;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class PhoneResourceDispositionTest {
    private static JSONObject j(Object... pairs)throws Exception {JSONObject out=new JSONObject();for(int i=0;i<pairs.length;i+=2)out.put((String)pairs[i],pairs[i+1]);return out;}
    private static JSONObject task(String run,int epoch)throws Exception {return j("id","task","runId",run,"coordinatorEpoch",epoch);}
    @Test public void onlyAnExplicitNativeBooleanConfirmsReleaseAndAudioStaysSeparate()throws Exception {
        for(Object confirmation:new Object[]{false,"true",1,JSONObject.NULL}) {
            JSONObject value=PhoneResourceDisposition.release("task","run",4,j("releaseConfirmed",confirmation,"leaseExpired",true));
            assertEquals("release_unknown",value.getString("state"));assertFalse(value.getBoolean("releaseConfirmed"));
            assertFalse(value.getBoolean("leaseExpiryIsReleaseProof"));assertFalse(value.getBoolean("thirdPartyProcessesForceStopped"));
        }
        assertEquals("release_unknown",PhoneResourceDisposition.release("task","run",4,null).getString("state"));
        JSONObject released=PhoneResourceDisposition.release("task","run",4,j("releaseConfirmed",true,"audioRestore",j("pending",2)));
        assertEquals("release_confirmed",released.getString("state"));assertEquals("pending",released.getString("audioRecovery"));
        assertEquals("restored_or_preserved",PhoneResourceDisposition.release("task","run",4,j("releaseConfirmed",true,"audioRestore",j("pending",0))).getString("audioRecovery"));
    }
    @Test public void originalDeadlineAndUnknownDeadlineNeverBecomeAReleaseReceipt()throws Exception {
        JSONObject expired=PhoneResourceDisposition.held("task","run",4,"awaiting_owner",1L,null);
        assertEquals("held_for_owner",expired.getString("state"));assertEquals(1L,expired.getLong("deadlineAt"));assertEquals("original_task_deadline",expired.getString("deadlinePolicy"));
        JSONObject unlimited=PhoneResourceDisposition.held("task","run",4,"manual_takeover",Long.MAX_VALUE,null);
        assertTrue(unlimited.isNull("deadlineAt"));assertEquals("until_original_task_stops",unlimited.getString("deadlinePolicy"));
        JSONObject unknown=PhoneResourceDisposition.held("task","run",4,"reconciliation",null,j("pending",true));
        assertEquals("unverified",unknown.getString("deadlinePolicy"));assertEquals("pending",unknown.getString("audioRecovery"));
        assertEquals("stop_original_run",unknown.getString("manualRelease"));
        assertThrows(IllegalArgumentException.class,()->PhoneResourceDisposition.held("task","run",4,"awaiting_owner",0L,null));
    }
    @Test public void lateOldRunReceiptsNeverRelabelANewRunAndCurrentReceiptsAreCopied()throws Exception {
        JSONObject pending=PhoneResourceDisposition.pending("task","new",5);
        JSONObject old=PhoneResourceDisposition.release("task","old",4,j("releaseConfirmed",true));
        JSONObject current=task("new",5).put("evidence",j("phoneResources",pending))
                .put("events",new JSONArray().put(j("type","phone_resource_release","phoneResources",old)));
        assertFalse(PhoneResourceDisposition.matchesTask(current,old));assertEquals("release_pending",PhoneResourceDisposition.forTask(current).getString("state"));
        JSONObject actual=PhoneResourceDisposition.release("task","new",5,j("releaseConfirmed",true));
        current.getJSONArray("events").put(j("type","phone_resource_release","phoneResources",actual));
        JSONObject projected=PhoneResourceDisposition.forTask(current);assertEquals("release_confirmed",projected.getString("state"));
        projected.put("state","changed-by-caller");assertEquals("release_confirmed",PhoneResourceDisposition.forTask(current).getString("state"));
        assertNull(PhoneResourceDisposition.forTask(task("other",6).put("evidence",j("phoneResources",actual))));
    }
    @Test public void incompleteCoercedAndOtherTaskIdentitiesAreNotCurrentResourceEvidence()throws Exception {
        JSONObject actual=PhoneResourceDisposition.pending("task","run",4);
        assertTrue(PhoneResourceDisposition.matchesTask(task("run",4),actual));
        assertFalse(PhoneResourceDisposition.matchesTask(task("run",4).put("coordinatorEpoch","4"),actual));
        assertFalse(PhoneResourceDisposition.matchesTask(task("run",4).put("id","other"),actual));
        assertFalse(PhoneResourceDisposition.matchesTask(task("run",4),new JSONObject(actual.toString()).put("coordinatorEpoch",4.0)));
        assertThrows(IllegalArgumentException.class,()->PhoneResourceDisposition.pending("task","",4));
        assertThrows(IllegalArgumentException.class,()->PhoneResourceDisposition.pending("task","run",0));
    }
}
