package dev.pocketengine.features.semantic;

import static org.junit.Assert.*;
import org.json.JSONObject;
import org.junit.Test;

public class SemanticRequestTest {
    private static SemanticRequest request(String value) throws Exception {return SemanticRequest.parse(new JSONObject(value));}
    @Test public void httpsEntryPreservesActualUrlAndCanonicalDigest() throws Exception {
        SemanticRequest a=request("{kind:'web',url:'https://example.org/a?q=1#piece'}"),b=request("{url:'https://example.org/a?q=1#piece',kind:'web'}");
        assertEquals("android.intent.action.VIEW",a.action);assertEquals("example.org",a.host);assertEquals(a.digest(),b.digest());assertEquals("https://example.org/a?q=1#piece",a.data);
    }
    @Test public void mapUsesEncodedGeoSearchRatherThanAnArbitraryUri() throws Exception {
        SemanticRequest r=request("{kind:'map_search',query:'上海 & q=altered'}");
        assertTrue(r.data.startsWith("geo:0,0?q=%E4%B8%8A%E6%B5%B7%20%26%20q%3Daltered"));assertEquals("android.intent.action.VIEW",r.action);
    }
    @Test public void dialOnlyOpensDialerAndNormalizesPresentationCharacters() throws Exception {
        SemanticRequest r=request("{kind:'dial',number:'+86 (021) 123-4567'}");assertEquals("android.intent.action.DIAL",r.action);assertEquals("tel:+860211234567",r.data);
    }
    @Test public void emailOnlyOpensDraftWithBoundedExtras() throws Exception {
        SemanticRequest r=request("{kind:'email_draft',to:'alice+work@example.org',body:'你好\\n第二行'}");
        assertEquals("android.intent.action.SENDTO",r.action);assertEquals("mailto:alice%2Bwork@example.org",r.data);assertEquals("",r.parameters().getString("subject"));
    }
    @Test public void requestDoesNotRetainMutableCallerJson() throws Exception {
        JSONObject input=new JSONObject("{kind:'web',url:'https://example.org/'}");SemanticRequest r=SemanticRequest.parse(input);input.put("url","https://changed.org");
        JSONObject out=r.parameters();out.put("url","https://changed.org");assertEquals("https://example.org/",r.parameters().getString("url"));
    }
    @Test public void refusesRawIntentIdentityAndUnusedParameters() throws Exception {
        for(String extra:new String[]{"component","extras","taskId","grant","_hostOperationId","number"}) {
            JSONObject bad=new JSONObject("{kind:'web',url:'https://example.org/'}").put(extra,"injected");assertThrows(IllegalArgumentException.class,()->SemanticRequest.parse(bad));
        }
    }
    @Test public void rejectsUnsafeOrAmbiguousUrlSchemesAndAuthorities() throws Exception {
        for(String value:new String[]{"http://example.org/","javascript:alert(1)","file:///private","content://files/x","intent://a/#Intent;end","https://u:p@example.org/","https://example.org/\\evil","https://example.org/a b","https://example.org:70000/"})
            assertThrows(Exception.class,()->SemanticRequest.parse(new JSONObject().put("kind","web").put("url",value)));
    }
    @Test public void blocksUssdMmiAutocallPauseAndWaitInputs() throws Exception {
        for(String value:new String[]{"*123#","#06#","123,456","123;456","tel:123","+","12\n34","123456789012345678901"})
            assertThrows(Exception.class,()->SemanticRequest.parse(new JSONObject().put("kind","dial").put("number",value)));
    }
    @Test public void rejectsMultipleMailRecipientsAndHeaderControls() throws Exception {
        assertThrows(Exception.class,()->request("{kind:'email_draft',to:'a@example.org,b@example.org'}"));
        assertThrows(Exception.class,()->request("{kind:'email_draft',to:'a@example.org',subject:'title\\nBcc: x'}"));
    }
    @Test public void rejectsMissingWrongTypeUnknownKindsAndOversizeBody() throws Exception {
        for(String json:new String[]{"{}","{kind:'web'}","{kind:'web',url:17}","{kind:'app_function'}","{kind:'web_mcp'}"})assertThrows(Exception.class,()->request(json));
        assertThrows(Exception.class,()->SemanticRequest.parse(new JSONObject().put("kind","email_draft").put("to","a@example.org").put("body","a".repeat(4001))));
    }
}
