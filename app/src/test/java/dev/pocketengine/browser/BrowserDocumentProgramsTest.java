package dev.pocketengine.browser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Production programs with explicit DOM-reference fixtures and real Node data-URL fetch/bytes.
 * This is not a browser renderer or Android WebView test. */
public final class BrowserDocumentProgramsTest {
    @Test public void originalDocumentProgramsPreserveBytesAndRejectUnownedSources()throws Exception {
        JSONObject target=new JSONObject().put("ref","n0").put("nodeKey","k1").put("tag","A");
        JSONObject scripts=new JSONObject()
                .put("open",BrowserDocumentInput.openScript("page-state","https://document.example/export","actual-observation",target,"document-bytes","actual-token"))
                .put("poll",BrowserDocumentInput.pollScript("document-bytes","actual-token",0,false))
                .put("chunk",BrowserDocumentInput.pollScript("document-bytes","actual-token",0,true))
                .put("badOffset",BrowserDocumentInput.pollScript("document-bytes","actual-token",-1,true))
                .put("close",BrowserDocumentInput.cleanupScript("document-bytes","actual-token"));
        Path input=Files.createTempFile("browser-document-programs-",".json"),output=Files.createTempFile("browser-document-programs-",".log");
        Process process=null;
        try {
            Files.write(input,scripts.toString().getBytes(StandardCharsets.UTF_8));
            String node=System.getProperty("a25.node",System.getenv("NODE_BIN"));if(node==null||node.isBlank())node="node";
            String root=System.getProperty("pocket.gateway.source");assertNotNull("Host runner must provide the repository root",root);
            Path runner=Path.of(root,"tools","browser-document-programs.cjs");
            assertTrue("Original repository Node fixture must be available",Files.isRegularFile(runner));
            process=new ProcessBuilder(node,runner.toAbsolutePath().toString(),input.toString()).redirectErrorStream(true).redirectOutput(output.toFile()).start();
            assertTrue("Document program checks exceeded 20 seconds",process.waitFor(20,TimeUnit.SECONDS));
            String observed=new String(Files.readAllBytes(output),StandardCharsets.UTF_8);assertEquals(observed,0,process.exitValue());
            JSONObject result=new JSONObject(observed.trim());assertEquals("passed",result.getString("status"));assertEquals(7,result.getInt("checks"));assertFalse(result.getBoolean("rendererVerified"));
        }finally {
            if(process!=null&&process.isAlive()){process.destroyForcibly();process.waitFor(5,TimeUnit.SECONDS);}
            Files.deleteIfExists(input);Files.deleteIfExists(output);
        }
    }
}
