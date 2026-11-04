package dev.pocketengine.store;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class OwnerInputQueueTest {
    private static JSONObject row() throws Exception {
        JSONObject value=new JSONObject().put("messageId","message-1").put("clientMessageId","client-1")
                .put("content","先完成当前任务").put("mode","queue").put("payloadSha256","accepted-digest")
                .put("ownerMessageRevision",3L).put("ownerRequestFingerprint","f".repeat(64)).put("attachments",new JSONArray());
        OwnerInputQueue.accepted(value,false);return value;
    }
    private static JSONObject message(JSONObject row)throws Exception {
        return new JSONObject().put("role","user").put("content",row.getString("content"))
                .put("ownerMessageId",row.getString("messageId")).put("ownerMessageRevision",row.getLong("ownerMessageRevision"))
                .put("ownerInputMode",row.getString("mode"));
    }
    @Test public void acceptedFollowupHasDistinctQueueAndNativeMessageRevisions()throws Exception{
        JSONObject row=row();assertEquals(1L,OwnerInputQueue.revision(row));assertEquals(3L,row.getLong("ownerMessageRevision"));
        assertEquals("pending",OwnerInputQueue.state(row));assertTrue(OwnerInputQueue.mutable(row));
    }
    @Test public void alreadyAdmittedGoalIsNotAnEditableFollowup()throws Exception{
        JSONObject row=row();OwnerInputQueue.accepted(row,true);assertEquals("admitted",OwnerInputQueue.state(row));
        assertFalse(OwnerInputQueue.mutable(row));assertThrows(IllegalStateException.class,()->OwnerInputQueue.requirePending(row,1,3));
    }
    @Test public void legacyAbsenceOfClaimIsNotProofThatEditingIsSafe()throws Exception{
        JSONObject row=row();row.remove("queueFormat");assertEquals("legacy_unconfirmed",OwnerInputQueue.state(row));
        assertFalse(OwnerInputQueue.mutable(row));assertFalse(OwnerInputQueue.claim(row,"run",1,1));
    }
    @Test public void actualIntegerMessageRevisionIsRequiredForMutation()throws Exception{
        JSONObject row=row();for(Object invalid:new Object[]{"3",3.0,JSONObject.NULL}){
            row.put("ownerMessageRevision",invalid);assertFalse(OwnerInputQueue.mutable(row));
        }
    }
    @Test public void mutationRequiresBothCurrentVersions()throws Exception{
        JSONObject row=row();OwnerInputQueue.requirePending(row,1,3);
        assertThrows(IllegalStateException.class,()->OwnerInputQueue.requirePending(row,2,3));
        assertThrows(IllegalStateException.class,()->OwnerInputQueue.requirePending(row,1,4));
    }
    @Test public void claimImmediatelyClosesEditWindowButDoesNotClaimProviderDelivery()throws Exception{
        JSONObject row=row();assertTrue(OwnerInputQueue.claim(row,"run-1",2,71));
        assertEquals("claimed",OwnerInputQueue.state(row));assertFalse(OwnerInputQueue.mutable(row));
        assertEquals(2L,OwnerInputQueue.revision(row));assertFalse(row.has("materializedRunId"));
        assertThrows(IllegalStateException.class,()->OwnerInputQueue.requirePending(row,2,3));
    }
    @Test public void repeatedPollRetainsSameClaimAndDoesNotRevertToEditable()throws Exception{
        JSONObject row=row();OwnerInputQueue.claim(row,"run-1",2,71);assertFalse(OwnerInputQueue.claim(row,"run-1",2,99));
        assertEquals(71L,row.getLong("claimedAt"));assertEquals(2L,OwnerInputQueue.revision(row));assertFalse(OwnerInputQueue.mutable(row));
    }
    @Test public void explicitLaterAttemptCanRecoverUncheckpointedClaimWithoutUnclaiming()throws Exception{
        JSONObject row=row();OwnerInputQueue.claim(row,"run-1",2,71);assertTrue(OwnerInputQueue.claim(row,"run-2",3,99));
        assertEquals("run-2",row.getString("claimRunId"));assertEquals("claimed",OwnerInputQueue.state(row));assertFalse(OwnerInputQueue.mutable(row));
    }
    @Test public void pendingCannotAppearInCheckpointWithoutNativeClaim()throws Exception{
        JSONObject row=row();assertThrows(SecurityException.class,()->OwnerInputQueue.checkpoint(new JSONArray().put(row),new JSONArray().put(message(row)),"run",1));
        assertEquals("pending",OwnerInputQueue.state(row));
    }
    @Test public void exactClaimIsMaterializedOnlyByActualCheckpoint()throws Exception{
        JSONObject row=row();OwnerInputQueue.claim(row,"run",1,71);
        assertTrue(OwnerInputQueue.checkpoint(new JSONArray().put(row),new JSONArray().put(message(row)),"run",1));
        assertEquals("materialized",OwnerInputQueue.state(row));assertEquals(3L,OwnerInputQueue.revision(row));
        assertFalse(OwnerInputQueue.checkpoint(new JSONArray().put(row),new JSONArray().put(message(row)),"run",1));
        assertFalse(OwnerInputQueue.claim(row,"next",2,99));assertFalse(OwnerInputQueue.mutable(row));
    }
    @Test public void staleMessageRevisionAndWrongModeCannotSettleClaim()throws Exception{
        JSONObject row=row();OwnerInputQueue.claim(row,"run",1,71);JSONArray rows=new JSONArray().put(row);
        assertThrows(SecurityException.class,()->OwnerInputQueue.checkpoint(rows,new JSONArray().put(message(row).put("ownerMessageRevision",2)),"run",1));
        assertThrows(SecurityException.class,()->OwnerInputQueue.checkpoint(rows,new JSONArray().put(message(row).put("ownerInputMode","steer")),"run",1));
        assertEquals("claimed",OwnerInputQueue.state(row));
    }
    @Test public void initialGoalKeepsExistingConversationRequestMetadataCompatibility()throws Exception{
        JSONObject row=row();OwnerInputQueue.accepted(row,true);JSONObject message=message(row);message.remove("ownerInputMode");
        assertTrue(OwnerInputQueue.checkpoint(new JSONArray().put(row),new JSONArray().put(message),"run",1));
        assertEquals("materialized",OwnerInputQueue.state(row));
    }
    @Test public void explicitNewRunCheckpointUpdatesMembershipButNotOriginalAcceptedIdentity()throws Exception{
        JSONObject row=row().put("acceptedRunId","original-run").put("acceptedEpoch",1);OwnerInputQueue.claim(row,"original-run",1,71);
        OwnerInputQueue.checkpoint(new JSONArray().put(row),new JSONArray().put(message(row)),"original-run",1);
        assertTrue(OwnerInputQueue.checkpoint(new JSONArray().put(row),new JSONArray().put(message(row)),"resumed-run",2));
        assertEquals("original-run",row.getString("acceptedRunId"));assertEquals(1,row.getInt("acceptedEpoch"));
        assertEquals("resumed-run",row.getString("materializedRunId"));assertEquals(2,row.getInt("materializedEpoch"));
        assertFalse(OwnerInputQueue.mutable(row));assertEquals(3L,row.getLong("ownerMessageRevision"));
    }
    @Test public void withdrawnBodyCannotBeReintroducedThroughCheckpoint()throws Exception{
        JSONObject row=row();OwnerInputQueue.changed(row,"withdrawn");assertFalse(OwnerInputQueue.claim(row,"run",1,99));
        assertThrows(SecurityException.class,()->OwnerInputQueue.checkpoint(new JSONArray().put(row),new JSONArray().put(message(row)),"run",1));
        assertEquals("先完成当前任务",row.getString("content"));
    }
    @Test public void duplicateNativeOwnerIdentityCannotSettleTwoMessages()throws Exception{
        JSONObject row=row();OwnerInputQueue.claim(row,"run",1,1);
        assertThrows(SecurityException.class,()->OwnerInputQueue.checkpoint(new JSONArray().put(row),new JSONArray().put(message(row)).put(message(row)),"run",1));
    }
    @Test public void summaryIsBoundedAndNeverCutsAnEmojiInHalf()throws Exception{
        JSONObject row=row().put("content","a".repeat(239)+"🙂more");JSONObject view=OwnerInputQueue.view(row,false,false);
        assertEquals(239,view.getString("content").length());assertTrue(view.getBoolean("contentTruncated"));assertFalse(view.getBoolean("executionAuthorized"));
        assertEquals(row.getString("content"),OwnerInputQueue.view(row,false,true).getString("content"));
    }
    @Test public void detailReferencesAreDefensiveCopiesAndCheckpointViewIsNeverEditable()throws Exception{
        JSONObject row=row();row.getJSONArray("attachments").put(new JSONObject().put("id","original"));
        row.put("ownerPreviewRefs",new JSONArray().put(new JSONObject().put("referenceId","native-preview-selection")));
        JSONObject view=OwnerInputQueue.view(row,true,true);view.getJSONArray("attachments").getJSONObject(0).put("id","changed");
        view.getJSONArray("ownerPreviewRefs").getJSONObject(0).put("referenceId","changed");
        assertEquals(1,view.getInt("previewReferenceCount"));assertEquals("native-preview-selection",row.getJSONArray("ownerPreviewRefs").getJSONObject(0).getString("referenceId"));
        assertFalse(OwnerInputQueue.view(row,false,false).has("ownerPreviewRefs"));
        assertEquals("original",row.getJSONArray("attachments").getJSONObject(0).getString("id"));assertFalse(view.getBoolean("editable"));
        assertEquals("materialized",view.getString("queueState"));
    }
}
