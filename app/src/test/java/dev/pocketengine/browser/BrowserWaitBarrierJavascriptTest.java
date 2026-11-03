package dev.pocketengine.browser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Exact production emitter in a deterministic JS observer fixture; no rendered browser claim. */
public final class BrowserWaitBarrierJavascriptTest {
    @Test public void originalObserverNonceMutationTimerAndDocumentGuardsExecuteInJavascript()throws Exception {
        String key="__barrier_test",nonce="wait-one",documentKey="__document_test",token="a".repeat(32),probe=BrowserWaitBarrier.domScript(key,nonce);
        JSONObject input=new JSONObject().put("key",key).put("probe",probe).put("cleanup",BrowserWaitBarrier.cleanupScript(key,nonce))
                .put("foreignCleanup",BrowserWaitBarrier.cleanupScript(key,"different-wait")).put("foreignProbe",BrowserWaitBarrier.domScript(key,"different-wait"))
                .put("documentKey",documentKey).put("documentToken",token).put("guarded",BrowserFrameObserver.guardScript(probe,documentKey,token,"barrierProbe"));
        Path fixture=Files.createTempFile("pocket-dom-barrier-",".cjs");Process process=null;
        try(var resource=BrowserWaitBarrierJavascriptTest.class.getResourceAsStream("/browser/dom-barrier.cjs")){
            assertNotNull("The committed exact JS fixture must be in test resources",resource);Files.copy(resource,fixture,java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            process=new ProcessBuilder(System.getProperty("a25.node","node"),fixture.toString()).redirectErrorStream(true).start();
            process.getOutputStream().write(input.toString().getBytes(StandardCharsets.UTF_8));process.getOutputStream().close();assertTrue("JS fixture exceeded five seconds",process.waitFor(5,TimeUnit.SECONDS));String output=new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8);assertEquals(output,0,process.exitValue());JSONObject result=new JSONObject(output);assertEquals("passed",result.getString("status"));assertEquals(6,result.getInt("cases"));assertFalse(result.getBoolean("browserRenderingVerified"));
        }finally{if(process!=null&&process.isAlive()){process.destroyForcibly();process.waitFor(1,TimeUnit.SECONDS);}Files.deleteIfExists(fixture);}
    }
}
