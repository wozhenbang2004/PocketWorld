package dev.pocketengine.features.local.preview;

import java.nio.charset.StandardCharsets;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Protocol fixtures exercise consumer rejection, not a claim of a real Android/Termux build. */
public final class ProjectCollectedArtifactTest {
    private static final String CHECKOUT="11111111-1111-1111-1111-111111111111",SOURCE="a".repeat(64),PLAN="b".repeat(64),BUILD="build_1",COLLECT="collect_1",BUILD_RECEIPT="c".repeat(64),COLLECT_RECEIPT="d".repeat(64),PATH="dist/index.html";
    private JSONObject build()throws Exception {return new JSONObject().put("checkoutId",CHECKOUT).put("sourceDigest",SOURCE).put("jobId",BUILD).put("receiptIdentity",BUILD_RECEIPT).put("intent","build").put("status","completed").put("exitCode",0).put("receiptReceived",true).put("sourceVerifiedBeforeExec",true).put("cancelRequested",false);}
    private JSONObject selection()throws Exception {
        JSONObject value=new JSONObject().put("artifactPlanId",PLAN).put("artifactPlanDigest",PLAN).put("checkoutId",CHECKOUT).put("sourceDigest",SOURCE).put("buildJobId",BUILD).put("buildReceiptIdentity",BUILD_RECEIPT).put("buildReceipt",build()).put("path",PATH).put("maxBytes",32000);
        JSONObject payload=new JSONObject().put("checkoutId",CHECKOUT).put("expectedSourceDigest",SOURCE).put("artifactSelection",new JSONObject(value.toString()));
        return value.put("hostAction",new JSONObject().put("feature","local").put("command","termux.collect").put("payload",payload));
    }
    private JSONObject artifact()throws Exception {
        String html="<button onclick=\"this.textContent='已点击'\">交互</button>",sha=ProjectPreviewArtifact.hash(html.getBytes(StandardCharsets.UTF_8));int bytes=html.getBytes(StandardCharsets.UTF_8).length;
        JSONObject binding=new JSONObject().put("authority","owner_tracked_job").put("taskId","collect_task").put("runId",COLLECT).put("operationId",COLLECT);
        JSONObject collection=new JSONObject().put("jobId",COLLECT).put("receiptIdentity",COLLECT_RECEIPT).put("intent","artifact_collect").put("binding",binding).put("status","completed").put("exitCode",0).put("receiptReceived",true).put("cancelRequested",false).put("outputTruncated",false)
                .put("checkoutId",CHECKOUT).put("sourceDigest",SOURCE).put("collectionPlanDigest",PLAN).put("buildJobId",BUILD).put("buildReceiptIdentity",BUILD_RECEIPT).put("path",PATH).put("sha256",sha).put("bytes",bytes);
        return new JSONObject().put("checkoutId",CHECKOUT).put("sourceDigest",SOURCE).put("jobId",BUILD).put("buildJobId",BUILD).put("buildReceiptIdentity",BUILD_RECEIPT).put("collectionJobId",COLLECT).put("collectionReceiptIdentity",COLLECT_RECEIPT)
                .put("path",PATH).put("sha256",sha).put("bytes",bytes).put("content",html).put("mimeType","text/html").put("truncated",false).put("artifactOrigin","termux_selected_output").put("generatedArtifact",true)
                .put("atomicArtifactFence",false).put("buildOutputAttribution","selected_output_observed_after_build").put("buildReceipt",build()).put("collectionReceipt",collection);
    }
    @Test public void exactSelectedBytesKeepSeparateBuildAndCollectionProvenance()throws Exception {
        ProjectPreviewArtifact.Verified value=ProjectPreviewArtifact.verifyCollected(artifact(),build(),selection(),COLLECT);
        assertTrue(value.generatedArtifact);assertTrue(value.buildAssociated);assertTrue(value.html);assertEquals("termux_selected_output",value.artifactOrigin);assertEquals(BUILD,value.jobId);assertEquals(COLLECT,value.collectionJobId);assertEquals(BUILD_RECEIPT,value.receiptIdentity);assertEquals(COLLECT_RECEIPT,value.collectionReceiptIdentity);
        JSONObject next=artifact().put("collectionJobId","collect_2").put("collectionReceiptIdentity","e".repeat(64));JSONObject receipt=next.getJSONObject("collectionReceipt");receipt.put("jobId","collect_2").put("receiptIdentity","e".repeat(64));receipt.getJSONObject("binding").put("runId","collect_2").put("operationId","collect_2");
        assertNotEquals(value.identity,ProjectPreviewArtifact.verifyCollected(next,build(),selection(),"collect_2").identity);
    }
    @Test public void alteredBuildSourcePathContentOrCollectionCannotBeMixed()throws Exception {
        for(String key:new String[]{"checkoutId","sourceDigest","jobId","buildJobId","buildReceiptIdentity","collectionJobId","collectionReceiptIdentity","path","sha256","content"}) {
            JSONObject wrong=artifact().put(key,"changed");assertThrows(Exception.class,()->ProjectPreviewArtifact.verifyCollected(wrong,build(),selection(),COLLECT));
        }
        for(String key:new String[]{"jobId","receiptIdentity","checkoutId","sourceDigest","buildJobId","buildReceiptIdentity","collectionPlanDigest","path","sha256"}) {
            JSONObject wrong=artifact();wrong.getJSONObject("collectionReceipt").put(key,"changed");assertThrows(Exception.class,()->ProjectPreviewArtifact.verifyCollected(wrong,build(),selection(),COLLECT));
        }
    }
    @Test public void incompleteUnknownCancelledFailedTruncatedAndFractionalReceiptsReject()throws Exception {
        for(String status:new String[]{"unknown","cancel_requested","failed","awaiting_result"}){JSONObject wrong=artifact();wrong.getJSONObject("collectionReceipt").put("status",status);assertThrows(Exception.class,()->ProjectPreviewArtifact.verifyCollected(wrong,build(),selection(),COLLECT));}
        for(String key:new String[]{"receiptReceived","cancelRequested","outputTruncated","exitCode"}) {
            JSONObject wrong=artifact();wrong.getJSONObject("collectionReceipt").put(key,key.equals("exitCode")?0.5:key.equals("receiptReceived")?false:true);assertThrows(Exception.class,()->ProjectPreviewArtifact.verifyCollected(wrong,build(),selection(),COLLECT));
        }
        JSONObject stringZero=artifact();stringZero.getJSONObject("collectionReceipt").put("exitCode","0");assertThrows(Exception.class,()->ProjectPreviewArtifact.verifyCollected(stringZero,build(),selection(),COLLECT));
        assertThrows(Exception.class,()->ProjectPreviewArtifact.verifyCollected(artifact(),build().put("cancelRequested",true),selection(),COLLECT));
    }
    @Test public void exactSizesUtf8AndSafePathAreRequired()throws Exception {
        for(JSONObject wrong:new JSONObject[]{artifact().put("bytes",1),artifact().put("bytes",1.5),artifact().put("content","\uD800"),artifact().put("content","x".repeat(32001)),artifact().put("truncated",true),artifact().put("path","../secret")})assertThrows(Exception.class,()->ProjectPreviewArtifact.verifyCollected(wrong,build(),selection(),COLLECT));
        JSONObject wrong=artifact();wrong.getJSONObject("collectionReceipt").put("bytes",1);assertThrows(Exception.class,()->ProjectPreviewArtifact.verifyCollected(wrong,build(),selection(),COLLECT));
    }
    @Test public void sourceAndGeneratedProvenanceRemainSeparateAndBounded()throws Exception {
        for(JSONObject wrong:new JSONObject[]{artifact().put("generatedArtifact",false),artifact().put("artifactOrigin","checkout_source"),artifact().put("atomicArtifactFence",true),artifact().put("buildOutputAttribution","exclusive_build_output")})assertThrows(Exception.class,()->ProjectPreviewArtifact.verifyCollected(wrong,build(),selection(),COLLECT));
        assertThrows(Exception.class,()->ProjectPreviewArtifact.verify(artifact(),build(),CHECKOUT,SOURCE));
        assertThrows(Exception.class,()->ProjectPreviewArtifact.verifyCollected(artifact(),build(),selection().put("path","other.html"),COLLECT));
        assertThrows(Exception.class,()->ProjectPreviewArtifact.verifyCollected(artifact(),build(),selection().put("maxBytes",262144),COLLECT));
    }
    @Test public void ownerConfirmsOnlyExactExistingCollectRouteWithoutNativeIdentity()throws Exception {
        JSONObject selected=selection(),action=ProjectOwnerAction.reviewedCollection(selected,build(),PATH);assertEquals("termux.collect",action.getString("command"));action.getJSONObject("payload").put("checkoutId","changed");assertEquals(CHECKOUT,selected.getJSONObject("hostAction").getJSONObject("payload").getString("checkoutId"));
        for(String key:new String[]{"operationId","_taskId","runId","grant","approval"}) {JSONObject wrong=selection();wrong.getJSONObject("hostAction").getJSONObject("payload").put(key,"forged");assertThrows(Exception.class,()->ProjectOwnerAction.reviewedCollection(wrong,build(),PATH));}
        JSONObject wrong=selection();wrong.getJSONObject("hostAction").put("command","termux.run");assertThrows(Exception.class,()->ProjectOwnerAction.reviewedCollection(wrong,build(),PATH));
        assertThrows(Exception.class,()->ProjectOwnerAction.reviewedCollection(selection(),build(),"other.html"));
    }
    @Test public void nestedSelectionAndNativeCollectionBindingCannotBeReplaced()throws Exception {
        JSONObject wrong=selection();wrong.getJSONObject("hostAction").getJSONObject("payload").getJSONObject("artifactSelection").put("path","other.html");assertThrows(Exception.class,()->ProjectOwnerAction.reviewedCollection(wrong,build(),PATH));
        for(String key:new String[]{"authority","taskId","runId","operationId"}) {JSONObject changed=artifact();changed.getJSONObject("collectionReceipt").getJSONObject("binding").put(key,"forged");if(key.equals("taskId"))changed.getJSONObject("collectionReceipt").getJSONObject("binding").put(key,"invalid/path");assertThrows(Exception.class,()->ProjectPreviewArtifact.verifyCollected(changed,build(),selection(),COLLECT));}
    }
    @Test public void generatedFeedbackTargetsProducerSourceWithoutTreatingOutputAsLocalSource()throws Exception {
        String feedback=ProjectRepairFeedback.sourceChange(ProjectPreviewArtifact.verifyCollected(artifact(),build(),selection(),COLLECT),"把按钮改成开始");
        assertTrue(feedback.contains("修改生成此产物的源码"));assertTrue(feedback.contains(COLLECT_RECEIPT));assertTrue(feedback.contains("不是本机已有源文件"));assertTrue(feedback.contains("该源文件自己的expectedHash"));assertFalse(feedback.contains("onclick"));assertTrue(feedback.length()<=ProjectRepairFeedback.MAX_CHARS);
    }
}
