package dev.pocketengine.features.plugins;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Fixed published library through the production bounds/identity adapter, not a second expander. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE)
public final class McpResourceTemplateDataTest {
    private static JSONObject definition(String template)throws Exception{return new JSONObject().put("name","owner selected").put("uriTemplate",template);}
    private static String expand(String template,JSONObject values)throws Exception{return McpResourceTemplateData.preview(definition(template),values).getString("uri");}

    @Test public void supportedOperatorsPreserveEncodingUnicodeAndPrimitiveCollectionSemantics()throws Exception{
        assertEquals("memo://fixture/a%2Fb?q=~%2A%20%25&tag=one&tag=a%26b",expand("memo://fixture/{id}{?q,tag*}",new JSONObject()
                .put("id","a/b").put("q","~* %").put("tag",new JSONArray().put("one").put("a&b"))));
        assertEquals("memo://fixture/%E6%B1%89%E5%AD%97/%2F#%E9%97%AE?x=1&y=2",expand("memo://fixture{+path}{#fragment}",new JSONObject()
                .put("path","/汉字/%2F").put("fragment","问?x=1&y=2")));
        assertEquals("memo://fixture/%F0%9F%99%82?q=%F0%9F%99%82",expand("memo://fixture/🙂{?q}",new JSONObject().put("q","🙂")));
        assertEquals("memo://fixture?q=%252F",expand("memo://fixture{?q}",new JSONObject().put("q","%2F")));
        assertEquals("memo://fixture/red/green?q=one&x=two",expand("memo://fixture{/path*}{?values*}",new JSONObject()
                .put("path",new JSONArray().put("red").put("green")).put("values",new JSONObject().put("x","two").put("q","one"))));
        assertEquals("memo://fixture.red.green;empty",expand("memo://fixture{.labels*}{;empty}",new JSONObject()
                .put("labels",new JSONArray().put("red").put("green")).put("empty","")));
        assertEquals("memo://fixture/a/a",expand("memo://fixture/{id}/{id}",new JSONObject().put("id","a")));
    }

    @Test public void undefinedIsDistinctFromEmptyAndBusinessParameterNamesKeepTheirExactData()throws Exception{
        JSONObject definition=definition("memo://fixture{?token,authorization,private}");
        JSONObject values=new JSONObject().put("token","ordinary reference").put("authorization","").put("private",JSONObject.NULL);
        JSONObject preview=McpResourceTemplateData.preview(definition,values);
        assertEquals("memo://fixture?token=ordinary%20reference&authorization=",preview.getString("uri"));
        JSONObject source=preview.getJSONObject("resourceTemplate");assertEquals(values.toString(),source.getJSONObject("arguments").toString());
        assertEquals(McpData.digest(values),source.getString("argumentsDigest"));assertEquals(McpData.digest(definition),source.getString("descriptorDigest"));
        assertEquals("memo://fixture",expand("memo://fixture{?missing}",new JSONObject()));
        values.put("token","changed caller JSON");assertEquals("ordinary reference",source.getJSONObject("arguments").getString("token"));
        assertFalse(preview.getBoolean("networkRequested"));assertFalse(preview.getBoolean("executionAuthorized"));
    }

    @Test public void unsupportedTemplatesStayVisibleAndCannotBeRewrittenIntoARead()throws Exception{
        JSONObject prefix=definition("memo://fixture/{glyph:1}").put("private",new JSONObject().put("token","business metadata"));
        JSONArray rows=McpResourceTemplateData.catalogue(new JSONArray().put(prefix).put(definition("memo://fixture/{id}")));
        assertEquals(2,rows.length());assertFalse(rows.getJSONObject(0).getBoolean("supported"));assertTrue(rows.getJSONObject(0).getString("unsupportedReason").contains("prefix"));
        assertEquals(prefix.toString(),rows.getJSONObject(0).getJSONObject("definition").toString());assertTrue(rows.getJSONObject(1).getBoolean("supported"));
        assertThrows(IllegalArgumentException.class,()->McpResourceTemplateData.preview(prefix,new JSONObject().put("glyph","🙂")));
        for(String invalid:new String[]{"memo://fixture/{q:0}","memo://fixture/{q:99999}","memo://fixture/{q*,}","memo://fixture/{+}","memo://fixture/{bad-name}","memo://fixture/{q", "memo://fixture/%xy"})
            assertFalse(invalid,McpResourceTemplateData.catalogue(new JSONArray().put(definition(invalid))).getJSONObject(0).getBoolean("supported"));
        assertThrows(IllegalArgumentException.class,()->expand("memo://fixture/{+q}",new JSONObject().put("q","[x]")));
        assertThrows(IllegalArgumentException.class,()->expand("memo://fixture{;list*}",new JSONObject().put("list",new JSONArray().put(""))));
    }

    @Test public void ambiguousUnknownNestedInvalidUnicodeAndOversizedValuesFailBeforeAnyRead()throws Exception{
        assertThrows(IllegalArgumentException.class,()->McpResourceTemplateData.parseArguments("{\"q\":\"one\",\"q\":\"two\"}"));
        assertThrows(IllegalArgumentException.class,()->expand("memo://fixture{?q}",new JSONObject().put("unlisted","value")));
        assertThrows(IllegalArgumentException.class,()->expand("memo://fixture{?q}",new JSONObject().put("q",new JSONArray().put(new JSONObject()))));
        assertThrows(IllegalArgumentException.class,()->expand("memo://fixture{?q}",new JSONObject().put("q",true)));
        assertThrows(IllegalArgumentException.class,()->expand("memo://fixture{?q}",new JSONObject().put("q","\uD800")));
        assertThrows(IllegalArgumentException.class,()->expand("memo://fixture{?q}",new JSONObject().put("q","\n")));
        assertThrows(IllegalArgumentException.class,()->expand("memo://fixture{?q}",new JSONObject().put("q","x".repeat(2049))));
        assertThrows(IllegalArgumentException.class,()->expand("memo://fixture{?q}",new JSONObject().put("q","汉".repeat(240))));
        JSONArray tooMany=new JSONArray();for(int i=0;i<33;i++)tooMany.put("x");
        assertThrows(IllegalArgumentException.class,()->expand("memo://fixture{?q}",new JSONObject().put("q",tooMany)));
    }

    @Test public void templateProvenanceMustReproduceTheReviewedUriAndIndexNeverContainsParameterBodies()throws Exception{
        JSONObject preview=McpResourceTemplateData.preview(definition("memo://fixture/{id}{?q}"),new JSONObject().put("id","original").put("q","private selection"));
        JSONObject source=preview.getJSONObject("resourceTemplate"),index=McpResourceTemplateData.index(source);
        McpResourceTemplateData.checkIndex(index,preview.getString("descriptorDigest"));assertFalse(index.has("arguments"));assertFalse(index.toString().contains("private selection"));
        assertEquals(source.toString(),McpResourceTemplateData.checkedProvenance(source,preview.getString("uri"),preview.getString("descriptorDigest")).toString());
        source.getJSONObject("arguments").put("id","replacement");
        assertThrows(SecurityException.class,()->McpResourceTemplateData.checkedProvenance(source,preview.getString("uri"),preview.getString("descriptorDigest")));
    }

    @Test public void knownLibraryEmptySeparatorLimitsAreExplicitAndDoNotRewriteValidUris()throws Exception{
        // RFC 6570 would retain ,a or //a here. Handy 2.1.8 joinParts drops the
        // first empty component; the adapter must refuse, not read the other URI.
        for(String expression:new String[]{"{empty,x}","{+empty,x}","{#empty,x}","{.empty,x}","{/empty,x}"})
            assertThrows(IllegalArgumentException.class,()->expand("memo://fixture/"+expression,new JSONObject().put("empty","").put("x","a")));
        for(String expression:new String[]{"{list}","{/list*}","{?list}","{&list}"})
            assertThrows(IllegalArgumentException.class,()->expand("memo://fixture/"+expression,new JSONObject().put("list",new JSONArray().put("").put("a"))));
        assertThrows(IllegalArgumentException.class,()->expand("memo://fixture/{list,x}",new JSONObject().put("list",new JSONArray().put("")).put("x","a")));
        assertEquals("memo://fixture/a,",expand("memo://fixture/{x,empty}",new JSONObject().put("x","a").put("empty","")));
        assertEquals("memo://fixture/",expand("memo://fixture{/empty,x}",new JSONObject().put("empty","").put("x",JSONObject.NULL)));
        assertEquals("memo://fixture?list=&list=a",expand("memo://fixture{?list*}",new JSONObject().put("list",new JSONArray().put("").put("a"))));
        assertEquals("memo://fixture?x=one&list=&list=a",expand("memo://fixture?x=one{&list*}",new JSONObject().put("list",new JSONArray().put("").put("a"))));
        assertEquals("memo://fixture?list=a,",expand("memo://fixture{?list}",new JSONObject().put("list",new JSONArray().put("a").put(""))));
        assertEquals("memo://fixture?q=",expand("memo://fixture{?q,list,map}",new JSONObject().put("q","").put("list",new JSONArray()).put("map",new JSONObject())));
        JSONObject map=new JSONObject().put("","a").put("x","");
        assertEquals("memo://fixture/,a,x,",expand("memo://fixture/{keys}",new JSONObject().put("keys",map)));
        assertEquals("memo://fixture?=a&x=",expand("memo://fixture{?keys*}",new JSONObject().put("keys",map)));
        assertEquals("memo://fixture/=a/x=two",expand("memo://fixture{/keys*}",new JSONObject().put("keys",new JSONObject().put("","a").put("x","two"))));
        // RFC non-query exploded maps omit '=' for empty values; the library does not.
        for(String expression:new String[]{"{keys*}","{+keys*}","{#keys*}","{.keys*}","{/keys*}"})
            assertThrows(IllegalArgumentException.class,()->expand("memo://fixture/"+expression,new JSONObject().put("keys",map)));
        assertThrows(IllegalArgumentException.class,()->expand("memo://fixture{;keys*}",new JSONObject().put("keys",map)));
        assertThrows(IllegalArgumentException.class,()->expand("memo://fixture{;empty*}",new JSONObject().put("empty","")));
    }
}
