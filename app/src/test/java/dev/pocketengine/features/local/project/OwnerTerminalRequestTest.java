package dev.pocketengine.features.local.project;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class OwnerTerminalRequestTest {
    private JSONObject request()throws Exception{return new JSONObject().put("projectId","11111111-1111-1111-1111-111111111111").put("checkoutId","22222222-2222-2222-2222-222222222222").put("expectedSourceDigest","a".repeat(64)).put("runtimeId","managed_process_v1").put("cwd","src").put("env",new JSONObject().put("APP_LABEL","原目录")).put("timeoutMillis",120000).put("columns",96).put("rows",30).put("inputByteLimit",4096);}
    @Test public void fixedRegisteredShellUsesExistingFinitePtyContract()throws Exception {
        JSONObject input=OwnerTerminalRequest.command(request()),payload=ProjectCommandSpec.normalize(input);
        assertEquals("sh",payload.getString("executable"));assertEquals("-i",payload.getJSONArray("argv").getString(0));assertEquals(1,payload.getJSONArray("argv").length());assertEquals("owned_service",payload.getString("runMode"));assertEquals("pty",payload.getString("terminalMode"));assertEquals("src",payload.getString("workingDirectory"));assertEquals(120000,payload.getLong("timeoutMillis"));assertEquals(4096,payload.getInt("inputByteLimit"));assertTrue(payload.getBoolean("interactiveInputExplicitlyIncluded"));assertEquals("原目录",payload.getJSONObject("environment").getString("APP_LABEL"));assertFalse(input.has("taskId"));
    }
    @Test public void formCannotSmuggleExecutionIdentityCommandOrAlternateRuntime()throws Exception {
        for(String key:new String[]{"taskId","runId","epoch","executionId","executable","argv","runMode","terminal","foregroundWaitMillis","_hostOperationId"})assertThrows(SecurityException.class,()->OwnerTerminalRequest.command(request().put(key,"replacement")));
        assertThrows(SecurityException.class,()->OwnerTerminalRequest.command(request().put("runtimeId","termux")));assertThrows(SecurityException.class,()->OwnerTerminalRequest.command(request().put("expectedSourceDigest","unknown")));
    }
    @Test public void actualCommandLimitsRejectTraversalSecretsAndUnrenderableDimensions()throws Exception {
        for(String cwd:new String[]{"../outside","/data/data/other","src/../other"})assertThrows(Exception.class,()->OwnerTerminalRequest.command(request().put("cwd",cwd)));
        for(String key:new String[]{"PATH","HOME","LD_PRELOAD","APP_PASSWORD","PROOT_LOADER"})assertThrows(IllegalArgumentException.class,()->OwnerTerminalRequest.command(request().put("env",new JSONObject().put(key,"private-value"))));
        for(String key:new String[]{"columns","rows","timeoutMillis","inputByteLimit"})assertThrows(IllegalArgumentException.class,()->OwnerTerminalRequest.command(request().put(key,1.5)));
        for(int width:new int[]{19,301})assertThrows(IllegalArgumentException.class,()->OwnerTerminalRequest.command(request().put("columns",width)));
        for(int height:new int[]{4,121})assertThrows(IllegalArgumentException.class,()->OwnerTerminalRequest.command(request().put("rows",height)));
        assertThrows(IllegalArgumentException.class,()->OwnerTerminalRequest.command(request().put("timeoutMillis",300001)));assertThrows(IllegalArgumentException.class,()->OwnerTerminalRequest.command(request().put("inputByteLimit",262145)));
    }
}
