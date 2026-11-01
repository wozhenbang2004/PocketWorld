package dev.pocketengine.browser;

import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Host contract tests; synthetic DOM facts are not a WebView/provider/device claim. */
public final class BrowserSemanticReadTest {
    static JSONObject j(Object... values)throws Exception {JSONObject out=new JSONObject();for(int i=0;i<values.length;i+=2)out.put((String)values[i],values[i+1]);return out;}
    static JSONObject frame()throws Exception{return j("featureSupported",false,"execution","page_world_fallback","fallbackReason","provider_feature_unsupported","knownOriginMatchedFrames",new JSONArray(),"frameTreeComplete",false,"childFrameContentsRead",false,"automaticReload",false);}
    static JSONObject facts(String mode)throws Exception{return j("mode",mode,"regionRef","","projectionVersion","1234abcd","from",0,"to",1,"totalCaptured",1,"returnedItems",1,"omittedItems",0,"partial",false,"reasons",new JSONArray(),"wholePageComplete",false,"scope","main_document_rendered_light_and_open_shadow_dom","textCharsScanned",24,"nodesScanned",4,"childFramesOmitted",0,"openShadowRootsRead",0,"closedShadowCoverage","not_asserted","nameComputation","bounded_dom_labels_not_browser_accessibility_tree","textProjection","whitespace_normalized_dom_text");}
    static JSONObject raw(String mode)throws Exception{return j("semanticRead",true,"status","observed","url","https://read.example/report?owner=private","title","Report","text","actual revenue fixture 42","elements",new JSONArray(),"read",j("mode",mode,"data",new JSONArray().put("actual revenue fixture 42"),"cursor",JSONObject.NULL,"facts",facts(mode)),"documentId",UUID.randomUUID().toString(),"frameId","main","frameCoverage",frame());}
    @Test public void readSchemaRejectsAuthorityFieldsActionRefsAndConflictingSelectors()throws Exception {
        BrowserSemanticRead.validate(j("mode","outline","query","营收"));BrowserSemanticRead.validate(j("mode","text","regionRef",UUID.randomUUID()+":r1"));
        assertThrows(IllegalArgumentException.class,()->BrowserSemanticRead.validate(j("mode","text","cursor",UUID.randomUUID()+":c0","query","new")));
        assertThrows(IllegalArgumentException.class,()->BrowserSemanticRead.validate(j("mode","outline","regionRef",UUID.randomUUID()+":r1")));
        assertThrows(IllegalArgumentException.class,()->BrowserSemanticRead.validate(j("mode","text","regionRef","n1")));
        assertThrows(IllegalArgumentException.class,()->BrowserSemanticRead.validate(j("mode","text","taskId","model")));
    }
    @Test public void nativeReadProjectionHasNoActionRefsAndSourceFactsKeepRealDigestAndCoverage()throws Exception {
        JSONObject actual=BrowserSemanticRead.result(raw("text"));assertTrue(actual.getBoolean("readOnlyEvidence"));assertFalse(actual.getBoolean("verified"));assertEquals(0,actual.getJSONArray("elements").length());JSONObject source=BrowserSemanticRead.sourceFacts(actual.getJSONObject("read"));BrowserSemanticRead.validateSourceFacts(source);assertTrue(source.getString("projectionSha256").matches("[a-f0-9]{64}"));assertFalse(source.getBoolean("wholePageComplete"));assertFalse(source.has("cursor"));
        JSONObject different=raw("text").put("text","changed original projection");assertNotEquals(source.getString("projectionSha256"),BrowserSemanticRead.result(different).getJSONObject("read").getString("projectionSha256"));
    }
    @Test public void sourceCannotUpgradePartialOrInventFieldsAndCounters()throws Exception {
        JSONObject source=BrowserSemanticRead.sourceFacts(BrowserSemanticRead.result(raw("text")).getJSONObject("read"));
        assertThrows(SecurityException.class,()->BrowserSemanticRead.validateSourceFacts(new JSONObject(source.toString()).put("wholePageComplete",true)));
        assertThrows(SecurityException.class,()->BrowserSemanticRead.validateSourceFacts(new JSONObject(source.toString()).put("nativePermission",true)));
        assertThrows(SecurityException.class,()->BrowserSemanticRead.validateSourceFacts(new JSONObject(source.toString()).put("omittedItems",2)));
        assertThrows(SecurityException.class,()->BrowserSemanticRead.validateSourceFacts(new JSONObject(source.toString()).put("partial",false).put("reasons",new JSONArray().put("child_frames_not_read"))));
    }
    @Test public void malformedProducerCannotSmuggleActionRefsOrUnboundedStructuredOutput()throws Exception {
        JSONObject actions=raw("text");actions.getJSONArray("elements").put(j("ref","n0"));assertThrows(IllegalStateException.class,()->BrowserSemanticRead.result(actions));
        JSONObject oversized=raw("list");oversized.getJSONObject("read").getJSONArray("data").put(0,"x".repeat(40000));JSONObject rejected=BrowserSemanticRead.result(oversized);assertEquals("unavailable",rejected.getString("status"));assertEquals("semantic_output_budget",rejected.getString("code"));assertFalse(rejected.has("read"));
    }
    @Test public void missingRegionIsATypedReadonlyResultNotAnUnknownWriteOrAutomaticRetry()throws Exception {
        JSONObject out=BrowserSemanticRead.result(j("semanticRead",true,"status","unavailable","code","read_region_ambiguous"));assertEquals("unavailable",out.getString("status"));assertTrue(out.getBoolean("accepted"));assertFalse(out.getBoolean("verified"));assertTrue(out.getBoolean("untrustedPageContent"));
    }
    @Test public void scriptsHaveNoNativeActionsAndRequireAnActualHostNonce()throws Exception {
        String code=BrowserSemanticRead.script(j("mode","outline","query","late table"),"native-key","original-scope",UUID.randomUUID().toString());assertTrue(code.contains("child_frames_not_read"));assertTrue(code.contains("read_region_changed"));assertFalse(code.contains(".click("));assertFalse(code.contains("scrollBy("));assertThrows(IllegalArgumentException.class,()->BrowserSemanticRead.script(j("mode","text"),"key","epoch","model-nonce"));
    }
}
