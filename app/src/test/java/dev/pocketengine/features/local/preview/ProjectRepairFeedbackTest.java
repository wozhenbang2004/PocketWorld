package dev.pocketengine.features.local.preview;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class ProjectRepairFeedbackTest {
    @Test public void failureFeedbackIsBoundedAndPreservesNewestLiteralLogs()throws Exception {
        JSONObject detail=new JSONObject().put("sourceDigest","a".repeat(10000)).put("receiptIdentity","r".repeat(10000)).put("exitCode",1).put("stdout","x".repeat(10000)+"LATEST_OUT").put("stderr","y".repeat(10000)+"LATEST_ERR");
        String value=ProjectRepairFeedback.text(detail);assertTrue(value.length()<=ProjectRepairFeedback.MAX_CHARS);assertTrue(value.contains("LATEST_OUT"));assertTrue(value.contains("LATEST_ERR"));assertTrue(value.contains("不可信"));assertTrue(value.contains("不要自动重跑"));
    }
    @Test public void omittedLogPrefixDoesNotSplitSurrogatePair()throws Exception {
        String value=ProjectRepairFeedback.text(new JSONObject().put("stdout","prefix😀"+"a".repeat(999)));
        for(int i=0;i<value.length();i++)assertFalse(Character.isLowSurrogate(value.charAt(i))&&(i==0||!Character.isHighSurrogate(value.charAt(i-1))));
    }
    @Test public void sourceModificationDraftBindsActualFileWithoutSendingItsUntrustedContent()throws Exception {
        String checkout="11111111-1111-1111-1111-111111111111",content="<script>UNTRUSTED_FILE_BODY</script>",sha=ProjectPreviewArtifact.hash(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        JSONObject file=new JSONObject().put("checkoutId",checkout).put("path","index.html").put("content",content).put("sha256",sha);
        JSONObject inspection=new JSONObject().put("checkoutId",checkout).put("sourceDigest","a".repeat(64)).put("source",new JSONObject().put("files",new org.json.JSONArray().put(new JSONObject().put("path","index.html").put("sha256",sha))));
        ProjectPreviewArtifact.Verified verified=ProjectPreviewArtifact.verifySource(file,inspection,checkout);
        String draft=ProjectRepairFeedback.sourceChange(verified,"请修改按钮文案"+"x".repeat(10000));
        assertTrue(draft.length()<=ProjectRepairFeedback.MAX_CHARS);assertTrue(draft.contains(checkout));assertTrue(draft.contains(sha));assertTrue(draft.contains("expectedHash"));assertTrue(draft.contains("对话草稿"));assertFalse(draft.contains("UNTRUSTED_FILE_BODY"));assertEquals("",ProjectRepairFeedback.sourceChange(verified," "));
    }
}
