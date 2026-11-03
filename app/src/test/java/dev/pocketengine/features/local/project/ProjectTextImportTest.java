package dev.pocketengine.features.local.project;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class ProjectTextImportTest {
    private JSONObject review()throws Exception{return new JSONObject().put("format",ProjectTextImport.REVIEW).put("checkoutId","checkout-a").put("projectId","project-a").put("path","main.txt").put("same",true).put("baselineNeedsRefresh",true).put("importable",true).put("truncated",false).put("expectedVersion",2).put("expectedHash","a".repeat(64)).put("expectedTargetHash","a".repeat(64)).put("targetDeleted",false);}
    @Test public void baselinePayloadCopiesOnlyFreshIdentitiesAndNeverCarriesAWriteBody()throws Exception {
        JSONObject reviewed=review().put("content","owner text"),input=ProjectTextImport.reconciliationPayload(reviewed);reviewed.put("expectedVersion",3).put("expectedHash","b".repeat(64));
        assertTrue(input.getBoolean("reconcileBaselineOnly"));assertEquals(2,input.getInt("expectedVersion"));assertEquals("a".repeat(64),input.getString("expectedHash"));assertEquals("project-a",input.getString("projectId"));
        assertFalse(input.has("content"));assertFalse(input.has("bytes"));assertEquals(9,input.length());
    }
    @Test public void incompleteOrNonmatchingReviewCannotBecomeBaselineOnly()throws Exception {
        for(JSONObject invalid:new JSONObject[]{review().put("same",false),review().put("same","true"),review().put("baselineNeedsRefresh",false),review().put("truncated",true),review().put("importable",false),review().put("expectedVersion","2"),review().put("expectedTargetHash","b".repeat(64)),review().put("targetDeleted",true),review().put("targetDeleted","false"),review().put("format",ProjectBinaryImport.REVIEW)})
            assertThrows(Exception.class,()->ProjectTextImport.reconciliationPayload(invalid));
    }
    @Test public void tombstoneReconciliationCarriesAbsentIdentity()throws Exception {
        JSONObject input=ProjectTextImport.reconciliationPayload(review().put("expectedHash","absent").put("expectedTargetHash","absent").put("targetDeleted",true));
        assertEquals("absent",input.getString("expectedHash"));assertTrue(input.getBoolean("expectedTargetDeleted"));
    }
    @Test public void baselineCurrentnessRequiresExactProjectVersionHashAndExistence()throws Exception {
        String hash=ProjectSources.sha256("after".getBytes(java.nio.charset.StandardCharsets.UTF_8));JSONObject reviewed=review().put("expectedHash",hash).put("expectedTargetHash",hash);
        JSONObject input=ProjectTextImport.reconciliationPayload(reviewed),file=new JSONObject().put("sha256",hash).put("deleted",false),target=new JSONObject().put("content","after").put("deleted",false).put("version",2);
        String untouched=target.toString();ProjectTextImport.requireReconciliation(input,"project-a",file,target);assertEquals(untouched,target.toString());
        for(JSONObject invalid:new JSONObject[]{new JSONObject(input.toString()).put("projectId","project-b"),new JSONObject(input.toString()).put("expectedVersion",3),new JSONObject(input.toString()).put("expectedTargetHash","0".repeat(64)),new JSONObject(input.toString()).put("expectedTargetDeleted",true),new JSONObject(input.toString()).put("reconcileBaselineOnly","true"),new JSONObject(input.toString()).put("content","hidden write")})
            assertThrows(Exception.class,()->ProjectTextImport.requireReconciliation(invalid,"project-a",file,target));
        assertThrows(Exception.class,()->ProjectTextImport.requireReconciliation(input,"project-a",file,target.put("deleted",true)));
    }
}
