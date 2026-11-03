package dev.pocketengine.features.remote;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class RemoteTaskRequestTest {
    static JSONObject coding()throws Exception{return new JSONObject().put("profileId","computer").put("bindingHash","a".repeat(64)).put("environmentId","project").put("environmentBinding","b".repeat(64)).put("worker","codex_app_server").put("prompt","修复真实构建失败");}
    @Test public void firstCreateKeepsExactNativeBindingAndDefaultsToReadOnly()throws Exception {
        JSONObject payload=RemoteTaskRequest.first(coding());assertEquals("a".repeat(64),payload.getString("_profileBinding"));assertEquals("b".repeat(64),payload.getString("environmentBinding"));assertTrue(payload.getBoolean("readOnly"));assertEquals(300,payload.getInt("timeoutSeconds"));assertFalse(payload.has("threadId"));
    }
    @Test public void continuationHostAndCredentialClaimsNeverEnterFirstCreate()throws Exception {
        for(String field:new String[]{"threadId","resumeJobId","serviceBase","operationId","hostTaskId","token","_profileBinding"})assertThrows(SecurityException.class,()->RemoteTaskRequest.first(coding().put(field,"injected")));
    }
    @Test public void commandIsAnExplicitArrayNotAHiddenShellGoal()throws Exception {
        JSONObject input=coding();input.remove("prompt");input.put("worker","command").put("argv",new JSONArray().put("git").put("status").put("--short"));JSONObject actual=RemoteTaskRequest.first(input);assertEquals(3,actual.getJSONArray("argv").length());assertFalse(actual.has("readOnly"));
        assertThrows(IllegalArgumentException.class,()->RemoteTaskRequest.first(new JSONObject(input.toString()).put("argv","git status")));
        assertThrows(IllegalArgumentException.class,()->RemoteTaskRequest.first(new JSONObject(input.toString()).put("prompt","implicit shell")));
    }
    @Test public void badTypesOrFalseClaudeSandboxAreRefusedBeforeDispatch()throws Exception {
        assertThrows(IllegalArgumentException.class,()->RemoteTaskRequest.first(coding().put("timeoutSeconds",3.5)));
        assertThrows(IllegalArgumentException.class,()->RemoteTaskRequest.first(coding().put("readOnly","true")));
        assertThrows(SecurityException.class,()->RemoteTaskRequest.first(coding().put("worker","claude").put("readOnly",true)));
        assertFalse(RemoteTaskRequest.first(coding().put("worker","claude")).getBoolean("readOnly"));
    }
    @Test public void piOnlyAcceptsTheOriginalPrivateNoToolsRequestAndNeverClaimsOsSandbox()throws Exception {
        JSONObject value=RemoteTaskRequest.first(coding().put("worker","pi").put("model","fixture/model-a"));assertEquals("pi",value.getString("worker"));assertFalse(value.getBoolean("readOnly"));assertEquals("fixture/model-a",value.getString("model"));
        assertThrows(SecurityException.class,()->RemoteTaskRequest.first(coding().put("worker","pi").put("readOnly",true)));assertThrows(IllegalArgumentException.class,()->RemoteTaskRequest.first(coding().put("worker","pi").put("argv",new JSONArray().put("pi").put("--tools").put("bash"))));
        assertThrows(SecurityException.class,()->RemoteTaskRequest.first(coding().put("worker","pi").put("sessionFile","/private/unreviewed.jsonl")));
    }
    @Test public void firstCodexCanPinActualEffortButClaudeWaitsForOriginalApprovedSessionCatalog()throws Exception {
        JSONObject actual=RemoteTaskRequest.first(coding().put("model","fixture-model").put("effort","ultra").put("modelOptionsDigest","c".repeat(64)));assertEquals("ultra",actual.getString("effort"));assertEquals("c".repeat(64),actual.getString("modelOptionsDigest"));
        assertThrows(SecurityException.class,()->RemoteTaskRequest.first(coding().put("worker","claude").put("model","fixture-model").put("effort","high")));assertThrows(SecurityException.class,()->RemoteTaskRequest.first(coding().put("effort","ultra")));assertThrows(SecurityException.class,()->RemoteTaskRequest.first(coding().put("model","fixture-model").put("modelOptionsDigest","not-a-digest")));
    }
    @Test public void duplicateOrUnavailableNativeEnvironmentIsNeverGuessed()throws Exception {
        JSONObject env=new JSONObject().put("id","project").put("workers",new JSONArray().put(new JSONObject().put("id","codex_app_server").put("available",true)));
        assertEquals("project",RemoteTaskRequest.environment(new JSONObject().put("environments",new JSONArray().put(env)),"project","codex_app_server").getString("id"));
        assertThrows(SecurityException.class,()->RemoteTaskRequest.environment(new JSONObject().put("environments",new JSONArray().put(env).put(env)),"project","codex_app_server"));
        assertThrows(SecurityException.class,()->RemoteTaskRequest.environment(new JSONObject().put("environments",new JSONArray().put(env)),"missing","codex_app_server"));
    }
}
