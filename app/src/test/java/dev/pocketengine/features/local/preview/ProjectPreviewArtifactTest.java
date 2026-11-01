package dev.pocketengine.features.local.preview;

import java.nio.charset.StandardCharsets;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class ProjectPreviewArtifactTest {
    private static final String CHECKOUT="11111111-1111-1111-1111-111111111111",JOB="22222222-2222-2222-2222-222222222222",SOURCE="a".repeat(64);
    private JSONObject build()throws Exception{return new JSONObject().put("checkoutId",CHECKOUT).put("sourceDigest",SOURCE).put("intent","build").put("status","completed").put("exitCode",0).put("jobId",JOB).put("receiptIdentity","receipt-123").put("receiptReceived",true).put("sourceVerifiedBeforeExec",true).put("cancelRequested",false);}
    private JSONObject artifact()throws Exception {
        String html="<h1 id='n'>0</h1><button onclick=\"document.getElementById('n').textContent++\">加一</button>";
        return new JSONObject().put("checkoutId",CHECKOUT).put("sourceDigest",SOURCE).put("buildReceiptIdentity","receipt-123").put("jobId",JOB).put("path","dist/index.html").put("sha256",ProjectPreviewArtifact.hash(html.getBytes(StandardCharsets.UTF_8))).put("content",html).put("mimeType","text/html").put("truncated",false).put("artifactOrigin","checkout_source").put("generatedArtifact",false);
    }
    @Test public void realBytesAndExactBuildProduceStableIsolatedIdentity()throws Exception {
        ProjectPreviewArtifact.Verified value=ProjectPreviewArtifact.verify(artifact(),build(),CHECKOUT,SOURCE);
        assertTrue(value.html);assertEquals("dist/index.html",value.path);assertEquals(64,value.identity.length());assertTrue(ProjectPreviewPolicy.origin(value).endsWith(".invalid/preview.html"));assertEquals("checkout_source",value.artifactOrigin);assertFalse(value.generatedArtifact);assertTrue(value.buildAssociated);
        assertEquals(value.identity,ProjectPreviewArtifact.verify(artifact(),build(),CHECKOUT,SOURCE).identity);
    }
    @Test public void fileReplacementOrCrossSourceAndReceiptReject()throws Exception {
        for(String key:new String[]{"checkoutId","sourceDigest","jobId","buildReceiptIdentity","content","sha256"}) {
            JSONObject wrong=artifact().put(key,key.equals("sha256")?"b".repeat(64):"changed");
            assertThrows(Exception.class,()->ProjectPreviewArtifact.verify(wrong,build(),CHECKOUT,SOURCE));
        }
    }
    @Test public void failedOrUnknownAndNonBuildReceiptsCannotPreview()throws Exception {
        for(JSONObject wrong:new JSONObject[]{build().put("exitCode",1),build().put("status","unknown"),build().put("intent","run"),build().put("exitCode",JSONObject.NULL)})assertThrows(Exception.class,()->ProjectPreviewArtifact.verify(artifact(),wrong,CHECKOUT,SOURCE));
    }
    @Test public void fractionalExitCodeNeverBecomesSuccessfulBuild()throws Exception {
        assertThrows(SecurityException.class,()->ProjectPreviewArtifact.verify(artifact(),build().put("exitCode",0.5),CHECKOUT,SOURCE));
    }
    @Test public void incompleteUncheckedOrCancelledExecutionNeverClaimsVerifiedBuild()throws Exception {
        for(JSONObject wrong:new JSONObject[]{build().put("receiptReceived",false),build().put("sourceVerifiedBeforeExec",false),build().put("cancelRequested",true),build().put("receiptReceived","true")})assertFalse(ProjectPreviewArtifact.successfulBuild(wrong));
    }
    @Test public void buildAssociationNeverPromotesSourceToGeneratedArtifact()throws Exception {
        assertThrows(SecurityException.class,()->ProjectPreviewArtifact.verify(artifact().put("generatedArtifact",true),build(),CHECKOUT,SOURCE));
        assertThrows(SecurityException.class,()->ProjectPreviewArtifact.verify(artifact().put("artifactOrigin","termux_output"),build(),CHECKOUT,SOURCE));
        JSONObject missing=artifact();missing.remove("artifactOrigin");assertThrows(SecurityException.class,()->ProjectPreviewArtifact.verify(missing,build(),CHECKOUT,SOURCE));
    }
    @Test public void realTermuxOperationIdentifiersNeedNotBeUuids()throws Exception {
        assertEquals("build-operation_17",ProjectPreviewArtifact.verify(artifact().put("jobId","build-operation_17"),build().put("jobId","build-operation_17"),CHECKOUT,SOURCE).jobId);
    }
    @Test public void binaryTruncatedAndEscapingPathsReject()throws Exception {
        for(String path:new String[]{"../private","file:///data/private","https://host/index.html","a//b","/absolute","a\\b"})assertThrows(Exception.class,()->ProjectPreviewArtifact.verify(artifact().put("path",path),build(),CHECKOUT,SOURCE));
        assertThrows(Exception.class,()->ProjectPreviewArtifact.verify(artifact().put("truncated",true),build(),CHECKOUT,SOURCE));
        assertThrows(Exception.class,()->ProjectPreviewArtifact.verify(artifact().put("mimeType","application/vnd.android.package-archive"),build(),CHECKOUT,SOURCE));
    }
    @Test public void malformedUtf16AndOversizedContentRejectBeforeRendering()throws Exception {
        assertThrows(Exception.class,()->ProjectPreviewArtifact.verify(artifact().put("content","\uD800"),build(),CHECKOUT,SOURCE));
        assertThrows(Exception.class,()->ProjectPreviewArtifact.verify(artifact().put("content","界".repeat(100000)),build(),CHECKOUT,SOURCE));
    }
    @Test public void policiesPrecedeUntrustedMarkupButKeepInteractiveScriptLiteral()throws Exception {
        ProjectPreviewArtifact.Verified value=ProjectPreviewArtifact.verify(artifact(),build(),CHECKOUT,SOURCE);String document=ProjectPreviewPolicy.document(value);
        assertTrue(document.indexOf("Content-Security-Policy")<document.indexOf("<h1"));assertTrue(document.contains("script-src 'unsafe-inline'"));assertTrue(document.contains("connect-src 'none'"));assertTrue(document.contains("frame-src 'none'"));assertTrue(document.contains("form-action 'none'"));assertTrue(document.contains(value.content));
    }
    @Test public void onlySameDocumentFragmentCanNavigate()throws Exception {
        String origin=ProjectPreviewPolicy.origin(ProjectPreviewArtifact.verify(artifact(),build(),CHECKOUT,SOURCE));
        assertTrue(ProjectPreviewPolicy.sameDocumentFragment(origin+"#section",origin));
        for(String url:new String[]{"https://example.com",origin+"?escape=1",origin+".evil#x","javascript:alert(1)","file:///private","content://private"})assertFalse(ProjectPreviewPolicy.sameDocumentFragment(url,origin));
    }
    @Test public void supersededRequestsAndChangedCheckoutNeverAdmitLateResults()throws Exception {
        JSONObject input=new JSONObject().put("_requestId","r1").put("checkoutId",CHECKOUT),result=new JSONObject().put("requestId","r1").put("checkoutId",CHECKOUT).put("sourceDigest",SOURCE);
        assertTrue(ProjectPreviewArtifact.current(result,input,"r1",CHECKOUT,SOURCE));assertFalse(ProjectPreviewArtifact.current(result,input,"r2",CHECKOUT,SOURCE));assertFalse(ProjectPreviewArtifact.current(result,input,"r1",JOB,SOURCE));assertFalse(ProjectPreviewArtifact.current(result,input,"r1",CHECKOUT,"b".repeat(64)));
    }
    @Test public void controllerEnvelopeCorrelatesResultsWithoutFabricatedInnerRequestIds()throws Exception {
        JSONObject input=new JSONObject().put("_requestId","r1").put("checkoutId",CHECKOUT),receipt=build();
        assertTrue(ProjectPreviewArtifact.current(receipt,input,"r1",CHECKOUT,SOURCE));
        assertTrue(ProjectPreviewArtifact.current(new JSONObject().put("receipt",receipt),input,"r1",CHECKOUT,SOURCE));
        assertFalse(ProjectPreviewArtifact.current(new JSONObject().put("receipt",receipt).put("requestId","stale"),input,"r1",CHECKOUT,SOURCE));
    }
    @Test public void actualManifestSourcePreviewDoesNotInventBuildReceipt()throws Exception {
        JSONObject actual=artifact().put("path","index.html");
        JSONObject inspection=new JSONObject().put("checkoutId",CHECKOUT).put("sourceDigest",SOURCE).put("source",new JSONObject().put("files",new org.json.JSONArray().put(new JSONObject().put("path","index.html").put("sha256",actual.getString("sha256")))));
        ProjectPreviewArtifact.Verified verified=ProjectPreviewArtifact.verifySource(actual,inspection,CHECKOUT);
        assertTrue(verified.html);assertFalse(verified.buildAssociated);assertEquals("",verified.jobId);assertEquals("",verified.receiptIdentity);
        assertThrows(SecurityException.class,()->ProjectPreviewArtifact.verifySource(new JSONObject(actual.toString()).put("content","changed"),inspection,CHECKOUT));
        assertThrows(SecurityException.class,()->ProjectPreviewArtifact.verifySource(new JSONObject(actual.toString()).put("path","other.html"),inspection,CHECKOUT));
        assertThrows(SecurityException.class,()->ProjectPreviewArtifact.verifySource(new JSONObject(actual.toString()).put("truncated",true),inspection,CHECKOUT));
    }
}
