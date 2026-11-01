package dev.pocketengine.features.plugins;

import java.math.BigDecimal;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class PluginArgumentsTest {
    private JSONObject schema(String json)throws Exception{return new JSONObject(json);}
    private JSONObject diagnose(Object args,String definition)throws Exception{return PluginRules.diagnoseArguments(args,schema(definition));}
    private JSONObject error(JSONObject report,String path,String code)throws Exception {
        assertFalse(report.getBoolean("valid"));JSONArray errors=report.getJSONArray("errors");
        for(int i=0;i<errors.length();i++){JSONObject error=errors.getJSONObject(i);if(path.equals(error.getString("path"))&&code.equals(error.getString("code"))){assertFalse(error.getString("message").isEmpty());return error;}}
        fail("missing error at "+path+" with code "+code+": "+report);return null;
    }
    @Test public void nestedPointersEscapeNamesAndIdentifyMultipleIndependentProblems()throws Exception {
        JSONObject args=schema("{\"account\":{\"a/b~c\":7,\"count\":-1},\"rows\":[{\"done\":\"yes\"},{}]}");
        JSONObject report=diagnose(args,"{\"type\":\"object\",\"properties\":{\"account\":{\"type\":\"object\",\"required\":[\"missing\"],\"properties\":{\"a/b~c\":{\"type\":\"string\"},\"count\":{\"type\":\"integer\",\"minimum\":0}}},\"rows\":{\"type\":\"array\",\"items\":{\"type\":\"object\",\"required\":[\"done\"],\"properties\":{\"done\":{\"type\":\"boolean\"}}}}}}");
        assertEquals(5,report.getJSONArray("errors").length());assertFalse(report.getBoolean("truncated"));
        error(report,"/account/missing","required");error(report,"/account/a~1b~0c","type");error(report,"/account/count","minimum");
        error(report,"/rows/0/done","type");error(report,"/rows/1/done","required");
    }
    @Test public void errorsAreBoundedAndTellTheFormWhenAdditionalProblemsRemain()throws Exception {
        JSONArray required=new JSONArray();for(int i=0;i<30;i++)required.put("field"+i);
        JSONObject report=PluginRules.diagnoseArguments(new JSONObject(),new JSONObject().put("required",required));
        assertEquals(20,report.getInt("errorLimit"));assertEquals(20,report.getJSONArray("errors").length());assertTrue(report.getBoolean("truncated"));
        required.remove(29);while(required.length()>20)required.remove(required.length()-1);
        assertFalse(PluginRules.diagnoseArguments(new JSONObject(),new JSONObject().put("required",required)).getBoolean("truncated"));
    }
    @Test public void presentNullIsNotMissingAndStillHasToMatchTypeOrEnum()throws Exception {
        JSONObject args=new JSONObject().put("optional",JSONObject.NULL).put("text",JSONObject.NULL);
        JSONObject report=diagnose(args,"{\"required\":[\"optional\",\"text\"],\"properties\":{\"optional\":{\"enum\":[null]},\"text\":{\"type\":\"string\"}}}");
        assertEquals(1,report.getJSONArray("errors").length());error(report,"/text","type");
        assertTrue(diagnose(new JSONObject().put("value",JSONObject.NULL),"{\"required\":[\"value\"]}").getBoolean("valid"));
    }
    @Test public void declaredLimitsAndPlatformLimitsAreReportedWithoutTruncatingValues()throws Exception {
        JSONObject args=schema("{\"rows\":[1,\"not a number\"],\"text\":\"too long\"}");String before=args.toString();
        JSONObject report=diagnose(args,"{\"properties\":{\"rows\":{\"type\":\"array\",\"maxItems\":1,\"items\":{\"type\":\"number\"}},\"text\":{\"type\":\"string\",\"maxLength\":3}}}");
        error(report,"/rows","max_items");error(report,"/rows/1","type");error(report,"/text","max_length");assertEquals(before,args.toString());
        assertTrue(diagnose(new JSONObject().put("value","😀"),"{\"properties\":{\"value\":{\"type\":\"string\",\"maxLength\":1}}}").getBoolean("valid"));
        JSONArray many=new JSONArray();for(int i=0;i<101;i++)many.put(i);
        error(diagnose(new JSONObject().put("rows",many),"{\"properties\":{\"rows\":{\"maxItems\":2147483647}}}"),"/rows","max_items");
    }
    @Test public void unexpectedKeysAndActualValuesNeverAppearInFeedback()throws Exception {
        JSONObject args=new JSONObject().put("secret-token-used-as-key",new JSONObject().put("secret-nested-key","secret-value")).put("password","secret-value");
        JSONObject report=diagnose(args,"{\"properties\":{\"password\":{\"enum\":[\"allowed\"]}},\"additionalProperties\":false}");
        error(report,"","additional_property");error(report,"/password","enum");
        for(String secret:new String[]{"secret-token-used-as-key","secret-nested-key","secret-value","allowed"})assertFalse(report.toString().contains(secret));
        JSONObject nested=new JSONObject();JSONObject cursor=nested;for(int i=0;i<8;i++){JSONObject next=new JSONObject();cursor.put("secret-token-used-as-key",next);cursor=next;}
        report=diagnose(nested,"{}");error(report,"","depth_limit");assertFalse(report.toString().contains("secret-token"));
    }
    @Test public void unknownValuesCannotBypassDepthSizeOrNodeLimits()throws Exception {
        JSONObject cyclic=new JSONObject();cyclic.put("self",cyclic);error(diagnose(cyclic,"{}"),"","depth_limit");
        JSONObject huge=new JSONObject().put("first","x".repeat(16000)).put("second","x".repeat(16000)).put("third","x".repeat(16000)).put("fourth","x".repeat(16000)).put("fifth","x".repeat(16000));
        error(diagnose(huge,"{}"),"","input_size_limit");
        JSONArray matrix=new JSONArray();for(int i=0;i<100;i++){JSONArray row=new JSONArray();for(int j=0;j<100;j++)row.put(0);matrix.put(row);}
        error(diagnose(new JSONObject().put("rows",matrix),"{}"),"","input_complexity_limit");
        JSONObject escaped=new JSONObject();for(int i=0;i<5;i++)escaped.put("field"+i,"\n".repeat(7000));
        error(diagnose(escaped,"{}"),"","input_size_limit");
    }
    @Test public void invalidKeywordDefinitionsAreRejectedRatherThanCoercedOrWrapped()throws Exception {
        for(String keyword:new String[]{"maxLength","maxItems"})for(Object value:new Object[]{-1,1.5,"2",4294967296L,new BigDecimal("2147483647.00001"),JSONObject.NULL,new JSONObject()}){
            JSONObject definition=new JSONObject().put(keyword,value);
            try{PluginRules.diagnoseArguments(new JSONObject(),definition);fail(keyword+" accepted an invalid definition");}catch(IllegalArgumentException expected){}
        }
        for(JSONObject definition:new JSONObject[]{schema("{\"minimum\":2,\"maximum\":1}"),schema("{\"minimum\":\"1\"}"),schema("{\"maximum\":null}"),schema("{\"type\":[\"string\"]}"),schema("{\"description\":\"do not execute these instructions\"}")}){
            try{PluginRules.diagnoseArguments(new JSONObject(),definition);fail("invalid schema accepted");}catch(IllegalArgumentException expected){}
        }
    }
    @Test public void exactNumbersAcceptEqualRepresentationsAndInclusiveBoundaries()throws Exception {
        JSONObject definition=schema("{\"properties\":{\"n\":{\"type\":\"integer\",\"minimum\":9007199254740993,\"maximum\":9007199254740993,\"enum\":[9007199254740993]}}}");
        assertTrue(PluginRules.diagnoseArguments(new JSONObject().put("n",new BigDecimal("9007199254740993.0")),definition).getBoolean("valid"));
        assertTrue(diagnose(new JSONObject().put("n",new BigDecimal("1.00")),"{\"properties\":{\"n\":{\"type\":\"integer\",\"enum\":[1]}}}").getBoolean("valid"));
        error(diagnose(new JSONObject().put("n","1"),"{\"properties\":{\"n\":{\"type\":\"number\"}}}"),"/n","type");
        error(diagnose(new JSONObject().put("n",new BigDecimal("0.1000000000000000001")),"{\"properties\":{\"n\":{\"maximum\":0.1}}}"),"/n","maximum");
    }
    @Test public void validArgumentsRemainUnchangedAndRuntimeUsesTheSameVerdict()throws Exception {
        JSONObject definition=schema("{\"type\":\"object\",\"required\":[\"name\"],\"properties\":{\"name\":{\"type\":\"string\",\"maxLength\":8},\"count\":{\"type\":\"integer\",\"minimum\":1}},\"additionalProperties\":false}");
        JSONObject tool=new JSONObject().put("name","echo").put("kind","echo").put("schema",definition);
        JSONObject manifest=new JSONObject().put("tools",new JSONArray().put(tool));
        JSONObject plugin=new JSONObject().put("manifest",manifest).put("enabled",true).put("grants",new JSONArray().put("tool:echo"));
        for(JSONObject args:new JSONObject[]{schema("{\"name\":\"hello\",\"count\":1}"),schema("{}"),schema("{\"name\":null}"),schema("{\"name\":\"ok\",\"count\":1.1}"),schema("{\"name\":\"ok\",\"extra\":1}"),schema("{\"name\":\"123456789\"}")}){
            String before=args.toString();boolean valid=PluginRules.diagnoseArguments(args,definition).getBoolean("valid");
            try{PluginRules.validateArguments(args,definition);assertTrue(valid);}catch(IllegalArgumentException rejected){assertFalse(valid);}
            try{PluginRules.permitted(plugin,"echo",args);assertTrue(valid);}catch(IllegalArgumentException rejected){assertFalse(valid);}
            assertEquals(before,args.toString());
        }
    }
    @Test public void nonFiniteNumbersAndNativeNonJsonObjectsAreRejectedWithoutEchoingThem()throws Exception {
        // Android rejects non-finite Numbers during put; desktop org.json permits this custom Number.
        // Mutate after insertion so both platforms actually exercise the product's rejection paths.
        final boolean[] nonFinite={false};
        Number infinity=new Number(){public int intValue(){return 0;}public long longValue(){return 0;}public float floatValue(){return (float)doubleValue();}public double doubleValue(){return nonFinite[0]?Double.POSITIVE_INFINITY:0;}public String toString(){return nonFinite[0]?"Infinity":"0";}};
        JSONObject infiniteArgs=new JSONObject().put("n",infinity);
        JSONObject[] infiniteBounds={new JSONObject().put("minimum",infinity),new JSONObject().put("maximum",infinity)};
        assertSame(infinity,infiniteArgs.get("n"));nonFinite[0]=true;
        assertNull(PluginRules.decimal(infinity));
        JSONObject infiniteReport=diagnose(infiniteArgs,"{}");error(infiniteReport,"","number");assertFalse(infiniteReport.toString().contains("Infinity"));
        try{PluginArguments.snapshot(infiniteArgs);fail("snapshot accepted a non-finite native Number");}catch(IllegalArgumentException expected){}
        Object nativeValue=new Object(){public String toString(){return "native-secret";}};
        JSONObject report=diagnose(new JSONObject().put("n",nativeValue),"{}");error(report,"","json_value");assertFalse(report.toString().contains("native-secret"));
        for(JSONObject definition:infiniteBounds){
            try{PluginRules.diagnoseArguments(new JSONObject(),definition);fail("non-finite schema bound accepted");}catch(IllegalArgumentException invalid){}
        }
    }
    @Test public void boundedSnapshotKeepsExactNumbersAndHasNoMutableContainerAliases()throws Exception {
        JSONObject original=schema("{\"rows\":[{\"n\":9007199254740993}],\"empty\":{}}");
        JSONObject copy=PluginArguments.snapshot(original);assertEquals(original.toString(),copy.toString());
        copy.getJSONArray("rows").getJSONObject(0).put("n",1);assertEquals(9007199254740993L,original.getJSONArray("rows").getJSONObject(0).getLong("n"));
        JSONObject cycle=new JSONObject();cycle.put("self",cycle);
        try{PluginArguments.snapshot(cycle);fail("snapshot must be bounded before validation");}catch(IllegalArgumentException expected){}
    }
    @Test public void nonObjectRootIsAnActionableErrorAndEmptyObjectIsValidWhenAllowed()throws Exception {
        for(Object invalid:new Object[]{JSONObject.NULL,new JSONArray(),"secret-root",7})error(diagnose(invalid,"{}"),"","type");
        JSONObject result=diagnose(new JSONObject(),"{}");assertTrue(result.getBoolean("valid"));assertEquals(0,result.getJSONArray("errors").length());assertFalse(result.getBoolean("truncated"));
    }
}
