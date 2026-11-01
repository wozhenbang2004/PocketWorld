package dev.pocketengine.features.skills;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class SkillExecutionRecordsTest {
    private JSONObject record(String status)throws Exception {
        return new JSONObject().put("claimedAt",1).put("review",new JSONObject().put("request",new JSONObject().put("outputPaths",new JSONArray().put("output/report.txt"))))
                .put("receipt",new JSONObject().put("status",status));
    }
    @Test public void missingInventoryIsVisibleAndRetriableWithoutClaimingAnEmptyResult()throws Exception {
        JSONObject original=record("completed");String before=original.toString();JSONObject value=SkillExecutionRecords.artifactAvailability(original);
        assertEquals("not_retrieved",value.getString("state"));assertEquals(1,value.getInt("requestedCount"));assertEquals(0,value.getInt("savedCount"));assertTrue(value.getBoolean("canRetrieve"));
        assertFalse(value.getBoolean("replayAllowed"));assertFalse(value.getBoolean("businessVerified"));assertEquals(before,original.toString());
    }
    @Test public void retrievedEmptyMissingAndTruncatedInventoriesAreDistinctFromAvailableFiles()throws Exception {
        JSONObject original=record("completed"),receipt=original.getJSONObject("receipt");
        receipt.put("artifacts",new JSONArray()).put("missingArtifactPaths",new JSONArray().put("output/report.txt")).put("artifactsTruncated",false);
        JSONObject missing=SkillExecutionRecords.artifactAvailability(original);assertEquals("missing",missing.getString("state"));assertFalse(missing.getBoolean("canRetrieve"));
        missing.getJSONArray("missingPaths").put("cannot-mutate-original");assertEquals(1,receipt.getJSONArray("missingArtifactPaths").length());
        receipt.put("artifacts",new JSONArray().put(new JSONObject().put("path","output/report.txt"))).put("missingArtifactPaths",new JSONArray());
        assertEquals("available",SkillExecutionRecords.artifactAvailability(original).getString("state"));
        receipt.put("artifactsTruncated",true);assertEquals("partial",SkillExecutionRecords.artifactAvailability(original).getString("state"));
    }
    @Test public void unclaimedUnknownAndCancelledOperationsCannotRetrieveOrReplayArtifacts()throws Exception {
        for(String state:new String[]{"accepted","running","unknown","cancelled","rejected"}){
            JSONObject value=SkillExecutionRecords.artifactAvailability(record(state));assertFalse(state,value.getBoolean("canRetrieve"));assertFalse(state,value.getBoolean("canRecheck"));assertFalse(value.getBoolean("replayAllowed"));
        }
        JSONObject original=record("completed");original.remove("claimedAt");assertEquals("not_dispatched",SkillExecutionRecords.artifactAvailability(original).getString("state"));
    }
    @Test public void noRequestedFilesDoesNotCreateARecoveryTask()throws Exception {
        JSONObject original=record("completed");original.getJSONObject("review").getJSONObject("request").put("outputPaths",new JSONArray());
        JSONObject value=SkillExecutionRecords.artifactAvailability(original);assertEquals("not_requested",value.getString("state"));assertFalse(value.getBoolean("canRecheck"));
    }
}
