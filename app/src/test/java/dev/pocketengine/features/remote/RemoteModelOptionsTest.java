package dev.pocketengine.features.remote;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class RemoteModelOptionsTest {
    static JSONObject source()throws Exception{return new JSONObject().put("worker","codex_app_server").put("modelOptions",new JSONObject().put("protocol","codex_model_options_v1").put("digest","a".repeat(64)).put("runtimeVersion","0.166.0-alpha.1").put("runningTurnChanges",false).put("changeModes",new JSONArray().put("new_job").put("settled_continuation")).put("models",new JSONArray().put(new JSONObject().put("id","actual-native-model").put("efforts",new JSONArray().put(new JSONObject().put("id","ultra"))))));}
    static JSONObject choice()throws Exception{return new JSONObject().put("model","actual-native-model").put("effort","ultra").put("modelOptionsDigest","a".repeat(64));}
    @Test public void onlyActualNativeAdvertisedEffortsAreSelectedWithoutHardCodedTiers()throws Exception {JSONObject actual=RemoteModelOptions.select(source(),choice());assertEquals("ultra",actual.getString("effort"));assertEquals(3,actual.length());assertFalse(actual.has("grant"));}
    @Test public void staleDigestModelOrEffortCannotBeApproximated()throws Exception {for(JSONObject invalid:new JSONObject[]{choice().put("modelOptionsDigest","b".repeat(64)),choice().put("model","other"),choice().put("effort","high"),choice().put("permissions",new JSONObject())})assertThrows(SecurityException.class,()->RemoteModelOptions.select(source(),invalid));}
    @Test public void wrongWorkerProtocolOrMissingLifecycleCannotBorrowAValidDigest()throws Exception {JSONObject wrong=source().put("worker","pi");assertThrows(SecurityException.class,()->RemoteModelOptions.select(wrong,choice()));JSONObject missing=source();missing.getJSONObject("modelOptions").remove("runningTurnChanges");assertThrows(SecurityException.class,()->RemoteModelOptions.select(missing,choice()));JSONObject running=source();running.getJSONObject("modelOptions").put("changeModes",new JSONArray().put("running_turn"));assertThrows(SecurityException.class,()->RemoteModelOptions.select(running,choice()));}
    @Test public void omittedEffortIsAnExplicitNonOverrideNotAnInventedDefault()throws Exception {JSONObject value=choice();value.remove("effort");JSONObject actual=RemoteModelOptions.select(source(),value);assertFalse(actual.has("effort"));assertEquals("actual-native-model",actual.getString("model"));}
}
