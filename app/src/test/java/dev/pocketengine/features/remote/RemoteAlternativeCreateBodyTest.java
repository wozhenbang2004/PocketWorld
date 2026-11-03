package dev.pocketengine.features.remote;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Pure final-body contracts. No owner claim, HTTP call, or model job is created here. */
public final class RemoteAlternativeCreateBodyTest {
    private static JSONObject request(boolean effort)throws Exception {
        JSONObject input=coding().put("model","fixture-model").put("modelOptionsDigest","c".repeat(64));
        if(effort)input.put("effort","ultra");
        return RemoteTaskRequest.first(input);
    }
    private static JSONObject coding()throws Exception {
        return new JSONObject().put("profileId","computer").put("bindingHash","a".repeat(64)).put("environmentId","project").put("environmentBinding","b".repeat(64)).put("worker","codex_app_server").put("prompt","Explicit frozen coding goal");
    }
    private static JSONObject catalog()throws Exception {
        return new JSONObject().put("worker","codex_app_server").put("modelOptions",new JSONObject().put("protocol","codex_model_options_v1").put("digest","c".repeat(64)).put("runningTurnChanges",false).put("changeModes",new JSONArray().put("new_job").put("settled_continuation")).put("models",new JSONArray().put(new JSONObject().put("id","fixture-model").put("efforts",new JSONArray().put(new JSONObject().put("id","ultra"))))));
    }
    private static JSONArray files()throws Exception {
        return new JSONArray().put(new JSONObject().put("attachmentId","11111111-1111-1111-1111-111111111111").put("name","selected.txt").put("mimeType","text/plain").put("bytes",3).put("sha256","d".repeat(64)));
    }
    private static JSONObject inputScope(JSONObject request)throws Exception {
        return new JSONObject().put("request",request).put("jobOperationId","original-input-operation").put("files",files());
    }
    private static void selection(JSONObject body,boolean effort)throws Exception {
        assertEquals("fixture-model",body.getString("model"));
        assertEquals("c".repeat(64),body.getString("modelOptionsDigest"));
        assertEquals(effort,body.has("effort"));
        if(effort)assertEquals("ultra",body.getString("effort"));
    }
    @Test public void projectCreatePreservesFrozenExplicitModelEffortAndCatalog()throws Exception {
        JSONObject request=request(true).put("projectHistory",new JSONObject().put("projectRoot","src"));String before=request.toString();
        JSONObject body=RemoteModule.projectCreateBody(request,"original-project-operation");
        selection(body,true);assertEquals("original-project-operation",body.getString("operationId"));
        assertEquals("src",body.getJSONObject("projectHistory").getString("projectRoot"));assertEquals(before,request.toString());
    }
    @Test public void privateInputCreatePreservesFrozenSelectionAndExactInputEnvelope()throws Exception {
        JSONObject scope=inputScope(request(true));String before=scope.toString();JSONObject body=RemoteModule.inputCreateBody(scope);
        selection(body,true);assertEquals("original-input-operation",body.getString("operationId"));assertEquals(RemoteTaskRequest.INPUT_PROTOCOL,body.getString("inputProtocol"));
        assertEquals(RemoteTaskRequest.inputHash(files()),RemoteTaskRequest.inputHash(body.getJSONArray("inputFiles")));assertEquals(before,scope.toString());
    }
    @Test public void omittedEffortRetainsCatalogButDoesNotInventAnOverride()throws Exception {
        selection(RemoteModule.projectCreateBody(request(false),"operation"),false);
        selection(RemoteModule.inputCreateBody(inputScope(request(false))),false);
        JSONObject defaultModel=RemoteTaskRequest.first(coding());
        for(JSONObject body:new JSONObject[]{RemoteModule.projectCreateBody(defaultModel,"operation"),RemoteModule.inputCreateBody(inputScope(defaultModel))}){
            assertFalse(body.has("model"));assertFalse(body.has("effort"));assertFalse(body.has("modelOptionsDigest"));
        }
    }
    @Test public void modelOnlyRequestsRemainCompatibleWithoutASynthesizedCatalogBinding()throws Exception {
        JSONObject modelOnly=RemoteTaskRequest.first(coding().put("model","fixture-model"));
        for(JSONObject body:new JSONObject[]{RemoteModule.projectCreateBody(modelOnly,"operation"),RemoteModule.inputCreateBody(inputScope(modelOnly))}){
            assertEquals("fixture-model",body.getString("model"));assertFalse(body.has("effort"));assertFalse(body.has("modelOptionsDigest"));
        }
    }
    @Test public void changedCatalogBindingIsNotDroppedOrReplacedByCurrentDefaults()throws Exception {
        assertEquals("ultra",RemoteModelOptions.select(catalog(),new JSONObject().put("model","fixture-model").put("effort","ultra").put("modelOptionsDigest","c".repeat(64))).getString("effort"));
        JSONObject frozen=request(true).put("modelOptionsDigest","e".repeat(64));
        for(JSONObject body:new JSONObject[]{RemoteModule.projectCreateBody(frozen,"operation"),RemoteModule.inputCreateBody(inputScope(frozen))}){
            assertEquals("e".repeat(64),body.getString("modelOptionsDigest"));assertEquals("ultra",body.getString("effort"));
            JSONObject choice=new JSONObject().put("model",body.get("model")).put("effort",body.get("effort")).put("modelOptionsDigest",body.get("modelOptionsDigest"));
            assertThrows(SecurityException.class,()->RemoteModelOptions.select(catalog(),choice));
        }
    }
    @Test public void whitelistsStillExcludeUnreviewedRoutesCredentialsAndApprovalClaims()throws Exception {
        JSONObject request=request(true).put("serviceBase","http://wrong.invalid").put("token","never-copy").put("scopeAcknowledged",true).put("operationId","forged").put("argv",new JSONArray().put("sh"));
        for(JSONObject body:new JSONObject[]{RemoteModule.projectCreateBody(request,"operation"),RemoteModule.inputCreateBody(inputScope(request))}){
            for(String key:new String[]{"profileId","_profileBinding","serviceBase","token","scopeAcknowledged","argv"})assertFalse(key,body.has(key));
            assertNotEquals("forged",body.getString("operationId"));selection(body,true);
        }
    }
    @Test public void privateInputManifestValidationStillRunsBeforeSerializationCompletes()throws Exception {
        JSONObject scope=inputScope(request(true));scope.getJSONArray("files").getJSONObject(0).put("name","../other.txt");
        assertThrows(SecurityException.class,()->RemoteModule.inputCreateBody(scope));
    }
}
