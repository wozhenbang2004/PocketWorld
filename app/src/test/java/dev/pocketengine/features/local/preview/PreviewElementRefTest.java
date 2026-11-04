package dev.pocketengine.features.local.preview;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class PreviewElementRefTest {
    static JSONObject observation()throws Exception{return new JSONObject().put("selectorHint","button#save").put("text","保存").put("htmlSnippet","<button>保存</button>").put("bounds",new JSONObject().put("x",2).put("y",3).put("width",40).put("height",20)).put("truncated",new JSONObject().put("selectorHint",false).put("text",false).put("htmlSnippet",false));}
    static JSONObject reference(String id)throws Exception {
        JSONObject value=observation().put("referenceId",id).put("taskId","original-task").put("runId","original-run").put("epoch",3).put("conversationId","original-conversation").put("projectId","original-project").put("checkoutId","original-checkout").put("previewId","a".repeat(64)).put("sourceDigest","b".repeat(64)).put("documentId","native-document").put("documentGeneration",5).put("path","dist/index.html").put("resourceSha256","c".repeat(64)).put("untrusted",true).put("selectorIsSourceLocation",false);return value;
    }
    interface Attempt {void run()throws Exception;}
    static void rejected(Attempt attempt)throws Exception {try{attempt.run();fail("invalid reference accepted");}catch(IllegalArgumentException|SecurityException expected){}}
    @Test public void canonicalCopyPreservesOriginalIdentityAndOwnsNestedValues()throws Exception {
        JSONObject input=reference("one"),copy=PreviewElementRef.checked(input);input.getJSONObject("bounds").put("width",999);input.getJSONObject("truncated").put("text",true);
        assertEquals(40d,copy.getJSONObject("bounds").getDouble("width"),0);assertFalse(copy.getJSONObject("truncated").getBoolean("text"));assertEquals("original-run",copy.getString("runId"));assertTrue(copy.getBoolean("untrusted"));assertFalse(copy.getBoolean("selectorIsSourceLocation"));
        assertEquals(copy.toString(),PreviewElementRef.checked(new JSONObject(copy.toString())).toString());
    }
    @Test public void fixedShapeRejectsExtraAuthorityMissingFieldsAndNonObjects()throws Exception {
        rejected(()->PreviewElementRef.checked(reference("one").put("grant",true)));JSONObject missing=reference("one");missing.remove("documentId");rejected(()->PreviewElementRef.checked(missing));
        rejected(()->PreviewElementRef.checked(new JSONArray().put("not-an-object")));rejected(()->PreviewElementRef.checked((JSONArray)null));
        rejected(()->PreviewElementRef.checked(reference("one").put("untrusted",false)));rejected(()->PreviewElementRef.checked(reference("one").put("selectorIsSourceLocation",true)));
    }
    @Test public void integersAndBooleansNeverCoerceStringsOrFractions()throws Exception {
        for(Object epoch:new Object[]{"3",3d,0,-1,((long)Integer.MAX_VALUE)+1})rejected(()->PreviewElementRef.checked(reference("one").put("epoch",epoch)));
        rejected(()->PreviewElementRef.checked(reference("one").put("documentGeneration",1.1d)));
        JSONObject row=reference("one");row.getJSONObject("truncated").put("text","false");rejected(()->PreviewElementRef.checked(row));
        rejected(()->PreviewElementRef.checked(reference("one").put("untrusted","true")));
    }
    @Test public void finiteBoundedRectangleHasExactNumericShape()throws Exception {
        for(Object width:new Object[]{"40",-1,10000001}){JSONObject row=reference("one");row.getJSONObject("bounds").put("width",width);rejected(()->PreviewElementRef.checked(row));}
        JSONObject extra=reference("one");extra.getJSONObject("bounds").put("screenX",0);rejected(()->PreviewElementRef.checked(extra));
        JSONObject edge=reference("one");edge.getJSONObject("bounds").put("x",-10000000).put("y",10000000).put("width",0);assertEquals(0d,PreviewElementRef.checked(edge).getJSONObject("bounds").getDouble("width"),0);
    }
    @Test public void unicodeAndPerFieldLimitsAreExact()throws Exception {
        for(String text:new String[]{"\ud800","\udc00","a\0b","x".repeat(1201)})rejected(()->PreviewElementRef.checked(reference("one").put("text",text)));
        assertEquals(1200,PreviewElementRef.checked(reference("one").put("text","😀".repeat(600))).getString("text").length());
        assertEquals("",PreviewElementRef.checked(reference("one").put("text","")).getString("text"));
        rejected(()->PreviewElementRef.checked(reference("one").put("selectorHint","x".repeat(241))));rejected(()->PreviewElementRef.checked(reference("one").put("htmlSnippet","x".repeat(4001))));
    }
    @Test public void sourcePathsAndHashesUseActualProjectRules()throws Exception {
        for(String path:new String[]{"../secret","/index.html","dir/../index.html","dir\\file.html","https://page","a\0b"})rejected(()->PreviewElementRef.checked(reference("one").put("path",path)));
        rejected(()->PreviewElementRef.checked(reference("one").put("resourceSha256","C".repeat(64))));rejected(()->PreviewElementRef.checked(reference("one").put("previewId","not-a-hash")));
    }
    @Test public void collectionCapsRejectDuplicatesAndAggregateOverflow()throws Exception {
        JSONArray exact=new JSONArray();for(int i=0;i<3;i++)exact.put(reference("ref-"+i).put("selectorHint","").put("text","").put("htmlSnippet","x".repeat(4000)));assertEquals(3,PreviewElementRef.checked(exact).length());
        exact.put(reference("four").put("text","").put("htmlSnippet",""));rejected(()->PreviewElementRef.checked(exact));
        JSONArray tooMany=new JSONArray();for(int i=0;i<5;i++)tooMany.put(reference("ref-"+i));rejected(()->PreviewElementRef.checked(tooMany));
        rejected(()->PreviewElementRef.checked(new JSONArray().put(reference("same")).put(reference("same"))));
    }
    @Test public void byteBudgetIncludesIdentityAndEscapedMetadata()throws Exception {
        JSONObject one=reference("one"),two=reference("two");for(String key:new String[]{"taskId","runId","conversationId","projectId","checkoutId","documentId"}){one.put(key,"中".repeat(2048));two.put(key,"中".repeat(2048));}
        PreviewElementRef.checked(one);rejected(()->PreviewElementRef.checked(new JSONArray().put(one).put(two)));
    }
}
