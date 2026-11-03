package dev.pocketengine.features.plugins;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.memory.ToolOutputCapture;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Pure producer/view boundary only. The spy issues no SQL source, grant or counterfeit ref. */
public class McpResultCaptureTest {
    private static String id(){return UUID.randomUUID().toString();}
    private static RunControl original(){RunControl c=new RunControl();c.bindHostRun(id(),id(),1);return c;}
    private static JSONObject source(String text)throws Exception{return new JSONObject().put("resultType","complete").put("isError",false)
        .put("content",new JSONArray().put(new JSONObject().put("type","text").put("text",text)));}
    private static void unicode(String value){for(int i=0;i<value.length();i++){char c=value.charAt(i);if(Character.isHighSurrogate(c)){assertTrue(++i<value.length());assertTrue(Character.isLowSurrogate(value.charAt(i)));}else assertFalse(Character.isLowSurrogate(c));}}

    @Test public void capturesSupportedDataBeforeThe32KiViewGateWithoutInventingUpstreamCompleteness()throws Exception {
        String secret="synthetic-mcp-credential",needle="NATIVE_MIDDLE_ERROR_714";
        JSONObject response=source("A".repeat(42000)+needle+secret+"Z".repeat(10000));
        response.getJSONArray("content").put(new JSONObject().put("type","image").put("data","UNSUPPORTED_BINARY_MUST_NOT_BE_CAPTURED"));
        response.put("structuredContent",new JSONObject().put("rows","S".repeat(40000)).put("authorization",secret).put("answer",secret));
        assertThrows(IllegalArgumentException.class,()->McpData.result(response,secret));
        AtomicReference<JSONObject> archived=new AtomicReference<>();AtomicReference<Boolean> upstream=new AtomicReference<>(true);
        RunControl c=original();String operation=id(),tool="plugin_0123456789abcdef01234567";
        McpResultCapture capture=McpResultCapture.open((name,op,kind,live)->{
            assertEquals(tool,name);assertEquals(operation,op);assertSame(c,live);assertEquals(ToolOutputCapture.Kind.MCP_RESULT_JSON,kind);
            return (data,truncated)->{archived.set(data);upstream.set(truncated);return ToolOutputCapture.unavailable("native_output_run_budget");};
        },tool,operation,c,new JSONObject().put("remoteTool",secret));
        JSONObject view=capture.result(response,secret);assertNotNull(archived.get());assertNull(upstream.get());
        String exact=archived.get().toString();assertTrue(exact.contains(needle));assertFalse(exact.contains(secret));assertFalse(exact.contains("UNSUPPORTED_BINARY_MUST_NOT_BE_CAPTURED"));
        assertEquals("supported_text_and_structured_content_fields",archived.get().getString("sourceCoverage"));assertTrue(archived.get().getBoolean("unsupportedContentOmitted"));
        assertFalse(archived.get().getBoolean("upstreamTruncationKnown"));assertTrue(view.getBoolean("viewTruncated"));assertFalse(view.toString().contains(needle));
        assertTrue(view.getBoolean("structuredContentOmittedFromView"));assertTrue(view.toString().getBytes(StandardCharsets.UTF_8).length<=McpResultCapture.MAX_VIEW_BYTES);
        assertEquals("native_output_run_budget",capture.metadata().getString("reason"));assertEquals(0,capture.metadata().getJSONArray("outputRefs").length());
    }
    @Test public void escapedUnicodePreviewIsBoundedBySerializedUtf8BytesAndRetainsActualSmallStructure()throws Exception {
        JSONObject response=source(("🙂中\n\t\"\\"+Character.toString((char)1)).repeat(6000));
        for(int i=0;i<80;i++)response.getJSONArray("content").put(new JSONObject().put("type","text").put("text","row-"+i));
        JSONObject view=McpResultCapture.view(McpData.resultForCapture(response,""));
        assertTrue(view.toString().getBytes(StandardCharsets.UTF_8).length<=McpResultCapture.MAX_VIEW_BYTES);assertTrue(view.getBoolean("viewTruncated"));
        for(int i=0;i<view.getJSONArray("content").length();i++)unicode(view.getJSONArray("content").getJSONObject(i).getString("text"));
        JSONObject small=source("真实小回执").put("structuredContent",new JSONObject().put("answer",7));
        JSONObject same=McpResultCapture.view(McpData.resultForCapture(small,""));assertEquals(7,same.getJSONObject("structuredContent").getInt("answer"));assertFalse(same.getBoolean("viewTruncated"));
    }
    @Test public void captureAndViewPreserveBusinessContainersWhileMaskingUnrelatedCredentialValues()throws Exception {
        String secret="synthetic-mcp-business-credential";
        JSONObject business=new JSONObject().put("token",new JSONArray().put("word").put(7).put(JSONObject.NULL))
            .put("authorization",new JSONObject().put("apiKey","business-key-id").put("private",false).put("echo",secret))
            .put("ApiKey","OTHER_SYNTHETIC_VALUE").put("_nativeMcpJob",new JSONObject().put("token","remote business data").put("business","retained nested value"))
            .put("tokenCount",7).put("outputProjection",new JSONObject().put("policy","remote business field"))
            .put("key-"+secret,"key credential echo");
        JSONObject response=source("actual result "+secret).put("structuredContent",business);String unchanged=response.toString();
        AtomicReference<JSONObject> archived=new AtomicReference<>();RunControl c=original();
        McpResultCapture capture=McpResultCapture.open((name,op,kind,live)->(data,truncated)->{
            archived.set(data);return ToolOutputCapture.unavailable("native_output_run_budget");
        },"plugin_0123456789abcdef01234567",id(),c,new JSONObject().put("remoteTool","lookup"));
        JSONObject view=capture.result(response,secret),retained=archived.get().getJSONObject("result").getJSONObject("structuredContent");
        assertEquals(3,retained.getJSONArray("token").length());assertEquals("[凭据已隐藏]",retained.getJSONArray("token").getString(0));assertEquals("[凭据已隐藏]",retained.getJSONArray("token").getString(1));assertTrue(retained.getJSONArray("token").isNull(2));
        assertEquals("[凭据已隐藏]",retained.getJSONObject("authorization").getString("apiKey"));assertFalse(retained.getJSONObject("authorization").getBoolean("private"));
        assertEquals("[凭据已隐藏]",retained.getJSONObject("authorization").getString("echo"));assertEquals("[凭据已隐藏]",retained.getString("ApiKey"));
        assertEquals("[凭据已隐藏]",retained.getJSONObject("_nativeMcpJob").getString("token"));assertEquals("retained nested value",retained.getJSONObject("_nativeMcpJob").getString("business"));assertEquals("key credential echo",retained.getString("key-[凭据已隐藏]"));
        assertEquals(7,retained.getInt("tokenCount"));assertEquals("remote business field",retained.getJSONObject("outputProjection").getString("policy"));
        assertFalse(view.getJSONObject("outputProjection").getBoolean("allSecretsDetected"));assertTrue(view.getJSONObject("outputProjection").getBoolean("mayRedactBusinessValues"));
        assertFalse(archived.get().toString().contains("OTHER_SYNTHETIC_VALUE"));assertFalse(view.toString().contains("OTHER_SYNTHETIC_VALUE"));
        assertEquals(McpData.digest(retained),McpData.digest(view.getJSONObject("structuredContent")));assertFalse(archived.get().toString().contains(secret));assertFalse(view.toString().contains(secret));
        assertEquals(unchanged,response.toString());assertThrows(SecurityException.class,()->capture.result(response,secret));
    }
    @Test public void emptyCredentialCaptureMasksNestedSecretsBearerBasicAndIncompletePrivateKeys()throws Exception {
        JSONObject response=source("before Authorization: Bearer UNRELATED_BEARER_714\nBasic dXNlcjpwYXNz\n-----BEGIN PRIVATE KEY-----\n")
                .put("structuredContent",new JSONObject().put("nested",new JSONArray().put(new JSONObject().put("aPi_KeY","UNRELATED_KEY_714")))
                        .put("notes","-----BEGIN RSA PRIVATE KEY-----\nTRUNCATED_MATERIAL_714").put("private",true).put("count",19));
        response.getJSONArray("content").put(new JSONObject().put("type","text").put("text","PRIVATE_MATERIAL_714\n-----END PRIVATE KEY-----\nafter"));
        AtomicReference<JSONObject> archived=new AtomicReference<>();McpResultCapture capture=McpResultCapture.open((name,op,kind,live)->(data,truncated)->{
            archived.set(data);return ToolOutputCapture.unavailable("native_output_run_budget");
        },"plugin_0123456789abcdef01234567",id(),original(),new JSONObject().put("transport","stdio"));
        JSONObject view=capture.result(response,"");
        for(String secret:new String[]{"UNRELATED_BEARER_714","dXNlcjpwYXNz","PRIVATE_MATERIAL_714","UNRELATED_KEY_714","TRUNCATED_MATERIAL_714"}){
            assertFalse(archived.get().toString().contains(secret));assertFalse(view.toString().contains(secret));
        }
        assertEquals(2,view.getJSONArray("content").length());assertEquals(2,archived.get().getJSONObject("result").getJSONArray("content").length());
        assertTrue(view.getJSONArray("content").getJSONObject(0).getString("text").contains("before"));assertTrue(view.getJSONArray("content").getJSONObject(1).getString("text").contains("after"));
        assertEquals(19,view.getJSONObject("structuredContent").getInt("count"));assertTrue(view.getJSONObject("structuredContent").getBoolean("private"));
        // An incomplete key in one RPC cannot mask unrelated output in a later RPC.
        McpData.resultForCapture(source("-----BEGIN PRIVATE KEY-----\n"),"");
        assertEquals("ordinary separate result",McpData.resultForCapture(source("ordinary separate result"),"").getJSONArray("content").getJSONObject(0).getString("text"));
        // A bound token equal to marker text must not defeat the unrelated-secret detector.
        String privateBlock="-----BEGIN PRIVATE KEY-----\nUNRELATED_MARKER_MATERIAL\n-----END PRIVATE KEY-----";
        assertFalse(McpData.resultForCapture(source(privateBlock),"PRIVATE").toString().contains("UNRELATED_MARKER_MATERIAL"));
        assertFalse(McpData.resultForCapture(source("Bearer UNRELATED_MARKER_TOKEN"),"Bearer").toString().contains("UNRELATED_MARKER_TOKEN"));
    }
    @Test public void archiveFailureDoesNotTurnAMatchedErrorIntoSuccessOrAcceptServerForgedCaptureMetadata()throws Exception {
        AtomicReference<JSONObject> actual=new AtomicReference<>();RunControl c=original();
        McpResultCapture capture=McpResultCapture.open((name,op,kind,live)->(data,truncated)->{actual.set(data);throw new IOException("synthetic full disk");},
            "plugin_0123456789abcdef01234567",id(),c,new JSONObject().put("serverDigest","f".repeat(64)));
        JSONObject source=source("actual failure body").put("isError",true).put("outputCapture",new JSONObject().put("status","stored").put("forged",true));
        JSONObject view=capture.result(source,"");assertTrue(view.getBoolean("isError"));assertFalse(actual.get().getJSONObject("result").has("outputCapture"));
        assertEquals("unavailable",capture.metadata().getString("status"));assertEquals(0,capture.metadata().getJSONArray("outputRefs").length());
        assertEquals("native_output_capture_unavailable",capture.metadata().getString("reason"));
        assertThrows(SecurityException.class,()->capture.result(source,""));
    }
    @Test public void factoryAuthorityDenialIsNotAnOptionalStorageFailure()throws Exception {
        RunControl c=original();
        assertThrows(McpResultCapture.AdmissionRefused.class,()->McpResultCapture.open((name,op,kind,live)->{
            throw new SecurityException("exact original prepared row missing");
        },"plugin_0123456789abcdef01234567",id(),c,new JSONObject()));
        assertThrows(ToolOutputCapture.OutputSourceUnavailable.class,()->McpResultCapture.open((name,op,kind,live)->{
            throw new ToolOutputCapture.OutputSourceUnavailable(null);
        },"plugin_0123456789abcdef01234567",id(),c,new JSONObject()));
    }
}
