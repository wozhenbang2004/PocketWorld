package dev.pocketengine.features.plugins.mcp;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class McpElicitationSchemaTest {
    private static JSONObject schema()throws Exception{return new JSONObject("{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":3},\"count\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":5},\"approved\":{\"type\":\"boolean\"},\"size\":{\"type\":\"string\",\"enum\":[\"small\",\"large\"]}},\"required\":[\"name\",\"count\",\"approved\",\"size\"]}");}
    @Test public void scalarValuesSurviveWithTypesAndNoDefaultAnswers()throws Exception {
        McpElicitationSchema.Form form=McpElicitationSchema.parse("form",schema());JSONObject answer=new JSONObject().put("name","你好🙂").put("count",2).put("approved",false).put("size","large");
        JSONObject checked=form.validate(answer);assertEquals("你好🙂",checked.getString("name"));assertEquals(2,checked.getInt("count"));assertFalse(checked.getBoolean("approved"));assertEquals("large",checked.getString("size"));
        answer.put("count",6);assertEquals(2,checked.getInt("count"));assertThrows(IllegalArgumentException.class,()->form.validate(answer));assertThrows(IllegalArgumentException.class,()->form.validate(new JSONObject()));
        assertFalse(form.view().getBoolean("authenticationAllowed"));assertEquals(form.schemaDigest,McpElicitationSchema.parse(null,schema()).schemaDigest);
    }
    @Test public void missingUnknownWrongTypedAndOutOfRangeValuesCannotBeAccepted()throws Exception {
        McpElicitationSchema.Form form=McpElicitationSchema.parse("form",schema());JSONObject valid=new JSONObject().put("name","one").put("count",1).put("approved",true).put("size","small");
        for(JSONObject invalid:new JSONObject[]{new JSONObject(valid.toString()).put("extra","secret"),new JSONObject(valid.toString()).put("count","1"),new JSONObject(valid.toString()).put("count",1.5),new JSONObject(valid.toString()).put("name","long"),new JSONObject(valid.toString()).put("approved","true"),new JSONObject(valid.toString()).put("size","other"),new JSONObject(valid.toString()).put("name",JSONObject.NULL)})assertThrows(IllegalArgumentException.class,()->form.validate(invalid));
    }
    @Test public void urlComplexSchemasUnknownKeywordsAndAuthenticationAreExplicitlyUnsupported()throws Exception {
        assertEquals("unsupported_mode",assertThrows(McpElicitationSchema.Unsupported.class,()->McpElicitationSchema.parse("url",schema())).code);
        for(JSONObject field:new JSONObject[]{new JSONObject().put("type","object"),new JSONObject().put("type","string").put("format","password"),new JSONObject().put("type","string").put("pattern",".*"),new JSONObject().put("type","string").put("default","answer")}){
            JSONObject s=new JSONObject().put("type","object").put("properties",new JSONObject().put("field",field));assertThrows(McpElicitationSchema.Unsupported.class,()->McpElicitationSchema.parse("form",s));
        }
        JSONObject secret=new JSONObject().put("type","object").put("properties",new JSONObject().put("api_key",new JSONObject().put("type","string")));assertEquals("authentication_field",assertThrows(McpElicitationSchema.Unsupported.class,()->McpElicitationSchema.parse("form",secret)).code);
        JSONObject extra=schema().put("additionalProperties",true);assertThrows(McpElicitationSchema.Unsupported.class,()->McpElicitationSchema.parse("form",extra));
    }
    @Test public void formAndValuesAreImmutableAndDigestIgnoresObjectKeyOrder()throws Exception {
        JSONObject original=schema();McpElicitationSchema.Form form=McpElicitationSchema.parse("form",original);String digest=form.schemaDigest;original.getJSONObject("properties").getJSONObject("name").put("maxLength",200);
        JSONObject view=form.view();view.getJSONObject("schema").getJSONObject("properties").getJSONObject("name").put("maxLength",999);assertEquals(3,form.view().getJSONObject("schema").getJSONObject("properties").getJSONObject("name").getInt("maxLength"));assertEquals(digest,form.schemaDigest);
        JSONObject reordered=new JSONObject().put("properties",schema().getJSONObject("properties")).put("required",schema().getJSONArray("required")).put("type","object");assertEquals(digest,McpElicitationSchema.parse("form",reordered).schemaDigest);
    }
}
