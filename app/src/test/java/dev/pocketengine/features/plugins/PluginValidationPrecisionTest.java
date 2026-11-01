package dev.pocketengine.features.plugins;

import java.math.BigDecimal;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Regression cases captured against the original double-based execution validator. */
public class PluginValidationPrecisionTest {
    private JSONObject schema(JSONObject value)throws Exception {
        return new JSONObject().put("type","object").put("properties",new JSONObject().put("value",value));
    }
    private void rejects(JSONObject value,Number number)throws Exception {
        try{PluginRules.validateArguments(new JSONObject().put("value",number),schema(value));fail("different exact JSON numbers must not collapse through double");}
        catch(IllegalArgumentException expected){}
    }
    @Test public void adjacentLargeIntegerEnumValuesAreDistinct()throws Exception {
        rejects(new JSONObject().put("type","integer").put("enum",new JSONArray().put(9007199254740992L)),9007199254740993L);
    }
    @Test public void adjacentLargeIntegerBoundsAreEnforced()throws Exception {
        rejects(new JSONObject().put("type","integer").put("maximum",9007199254740992L),9007199254740993L);
        rejects(new JSONObject().put("type","integer").put("minimum",9007199254740993L),9007199254740992L);
    }
    @Test public void fractionalValueDoesNotBecomeIntegerAfterDoubleRounding()throws Exception {
        rejects(new JSONObject().put("type","integer"),new BigDecimal("9007199254740992.1"));
    }
    @Test public void requiredMeansPresentAndNullStillObeysThePropertySchema()throws Exception {
        JSONObject definition=schema(new JSONObject().put("enum",new JSONArray().put(JSONObject.NULL))).put("required",new JSONArray().put("value"));
        PluginRules.validateArguments(new JSONObject().put("value",JSONObject.NULL),definition);
    }
    @Test public void oversizedLengthKeywordCannotWrapToAnInteger()throws Exception {
        JSONObject manifest=new JSONObject("{\"id\":\"limits\",\"name\":\"limits\",\"version\":\"1\",\"tools\":[{\"name\":\"echo\",\"kind\":\"echo\"}]}");
        manifest.getJSONArray("tools").getJSONObject(0).put("schema",schema(new JSONObject().put("type","string").put("maxLength",4294967296L)));
        try{PluginRules.manifest(manifest);fail("unsupported length keyword must not silently wrap to zero");}catch(IllegalArgumentException expected){}
    }
}
