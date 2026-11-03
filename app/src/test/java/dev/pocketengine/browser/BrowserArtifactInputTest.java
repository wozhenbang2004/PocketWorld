package dev.pocketengine.browser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Executes the exact generated input programs; does not claim a real WebView user gesture. */
public final class BrowserArtifactInputTest {
    @Test public void actualProgramsRejectOldInputDocumentDestinationAndChooser()throws Exception {
        JSONObject target=new JSONObject().put("ref","n0").put("nodeKey","k1").put("tag","INPUT").put("type","file")
                .put("id","upload").put("name","file").put("role","").put("formAction","https://upload.example/submit").put("formMethod","post").put("submitterFormAction","");
        String token=UUID.randomUUID().toString();JSONObject scripts=new JSONObject()
                .put("open",BrowserArtifactInput.script("native-page","https://upload.example/form","original-observation",target,token,true))
                .put("verify",BrowserArtifactInput.script("native-page","https://upload.example/form","original-observation",target,token,false));
        Path input=Files.createTempFile("browser-artifact-input-",".json"),output=Files.createTempFile("browser-artifact-input-",".log");Process process=null;
        try {
            Files.write(input,scripts.toString().getBytes(StandardCharsets.UTF_8));
            String node=System.getProperty("a25.node",System.getenv("NODE_BIN"));if(node==null||node.isBlank())node="node";
            String root=System.getProperty("pocket.gateway.source");assertNotNull("Host runner must provide the repository root",root);
            Path runner=Path.of(root,"tools","browser-artifact-input.cjs");
            assertTrue("Repository fixture must be available",Files.isRegularFile(runner));
            process=new ProcessBuilder(node,runner.toAbsolutePath().toString(),input.toString()).redirectErrorStream(true).redirectOutput(output.toFile()).start();
            assertTrue("File input programs exceeded 20 seconds",process.waitFor(20,TimeUnit.SECONDS));
            String observed=new String(Files.readAllBytes(output),StandardCharsets.UTF_8);assertEquals(observed,0,process.exitValue());
            JSONObject result=new JSONObject(observed.trim());assertEquals("passed",result.getString("status"));assertEquals(13,result.getInt("checks"));assertFalse(result.getBoolean("rendererVerified"));
        } finally {
            if(process!=null&&process.isAlive()){process.destroyForcibly();process.waitFor(5,TimeUnit.SECONDS);}
            Files.deleteIfExists(input);Files.deleteIfExists(output);
        }
    }
}
