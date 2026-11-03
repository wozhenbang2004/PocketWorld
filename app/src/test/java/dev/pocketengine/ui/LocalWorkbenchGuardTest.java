package dev.pocketengine.ui;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class LocalWorkbenchGuardTest {
    private JSONObject review()throws Exception{return new JSONObject().put("checkoutId","checkout-a").put("path","src/main.py").put("expectedHash","a".repeat(64)).put("expectedVersion",3).put("importable",true).put("truncated",false);}
    @Test public void lateCrossCheckoutPathAndRequestResponsesDoNotReplaceReviewedIdentity()throws Exception {
        JSONObject response=new JSONObject().put("checkoutId","checkout-a").put("path","src/main.py"),input=new JSONObject().put("checkoutId","checkout-a").put("path","src/main.py").put("_requestId","request-a");
        assertSame(response,LocalWorkbenchGuard.result(response,input,"request-a","checkout-a","src/main.py"));
        assertNull(LocalWorkbenchGuard.result(response,input,"old-request","checkout-a","src/main.py"));assertNull(LocalWorkbenchGuard.result(response,input,"request-a","checkout-b","src/main.py"));assertNull(LocalWorkbenchGuard.result(response,input,"request-a","checkout-a","other.txt"));assertNull(LocalWorkbenchGuard.result(response,input,null,"checkout-a","src/main.py"));
    }
    @Test public void importCopiesOnlyExactReviewedIdentityAndVersion()throws Exception {
        JSONObject reviewed=review(),payload=LocalWorkbenchGuard.importPayload(reviewed);reviewed.put("expectedHash","b".repeat(64)).put("expectedVersion",4);
        assertEquals("a".repeat(64),payload.getString("expectedHash"));assertEquals(3,payload.getInt("expectedVersion"));assertEquals(4,payload.length());
        for(JSONObject invalid:new JSONObject[]{review().put("expectedVersion","3"),review().put("truncated",true),review().put("importable",false),review().put("expectedHash","")}){
            try{LocalWorkbenchGuard.importPayload(invalid);fail("Incomplete review accepted");}catch(IllegalArgumentException expected){}
        }
    }
    @Test public void binaryReviewCannotFallBackToTheOldTextSubmissionShape()throws Exception {
        JSONObject binary=review().put("format",dev.pocketengine.features.local.project.ProjectBinaryImport.REVIEW).put("textDiff",false);
        assertThrows(IllegalArgumentException.class,()->LocalWorkbenchGuard.importPayload(binary));
        assertEquals(4,LocalWorkbenchGuard.importPayload(review()).length());
    }
    @Test public void matchingTextReviewSubmitsOnlyBaselineReconciliationAndPinsProjectAndTarget()throws Exception {
        JSONObject reviewed=review().put("format",dev.pocketengine.features.local.project.ProjectTextImport.REVIEW).put("projectId","project-a").put("same",true).put("baselineNeedsRefresh",true).put("expectedTargetHash","a".repeat(64)).put("targetDeleted",false);
        JSONObject input=LocalWorkbenchGuard.importPayload(reviewed);assertTrue(input.getBoolean("reconcileBaselineOnly"));assertEquals("project-a",input.getString("projectId"));assertEquals("a".repeat(64),input.getString("expectedTargetHash"));assertFalse(input.getBoolean("expectedTargetDeleted"));assertFalse(input.has("content"));
        assertThrows(Exception.class,()->LocalWorkbenchGuard.importPayload(reviewed.put("same",false)));
    }
    @Test public void argumentsAreLiteralLinesNotShellSyntax()throws Exception {
        JSONArray argv=LocalWorkbenchGuard.argv("python3","main file.py\n$(touch never)\n");assertEquals(3,argv.length());assertEquals("main file.py",argv.getString(1));assertEquals("$(touch never)",argv.getString(2));
    }
    @Test public void termuxUnknownAndStopRequestAreNotTerminalSuccess() {
        assertFalse(LocalWorkbenchGuard.terminal("unknown",true));assertFalse(LocalWorkbenchGuard.terminal("awaiting_result",true));assertFalse(LocalWorkbenchGuard.terminal("stopping",true));assertTrue(LocalWorkbenchGuard.terminal("failed",true));assertTrue(LocalWorkbenchGuard.terminal("cancelled",false));
    }
    @Test public void nativeColdUnknownOrInterruptedDoesNotPermitTreatingTheOriginalWorkspaceAsReleased() {
        assertFalse(LocalWorkbenchGuard.terminal("unknown",false));assertFalse(LocalWorkbenchGuard.terminal("interrupted",false));assertFalse(LocalWorkbenchGuard.terminal("stopping",false));assertTrue(LocalWorkbenchGuard.terminal("completed",false));assertTrue(LocalWorkbenchGuard.terminal("failed",false));
        assertFalse(LocalWorkbenchGuard.pollable("unknown",false));assertFalse(LocalWorkbenchGuard.pollable("interrupted",false));assertTrue(LocalWorkbenchGuard.pollable("running",false));assertTrue(LocalWorkbenchGuard.pollable("unknown",true));
    }
}
