package dev.pocketengine.features.remote;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class RemoteResultSnapshotTest {
    private JSONObject source()throws Exception{return new JSONObject().put("profileId","computer").put("serviceBase","http://127.0.0.1:8766").put("jobOperationId","original-operation").put("jobId","job-one").put("environmentId","project");}
    @Test public void realOutputDiffRemainSeparateFromBusinessSuccessAndFrozenArtifact()throws Exception {
        JSONObject source=source(),raw=new JSONObject(source.toString()).put("id","job-one").put("operationId","original-operation").put("output","build failed: missing import").put("diff","--- a/main.py\n+++ b/main.py\n+import fixed\n").put("exitCode",1).put("status","failed");
        JSONObject view=RemoteResultSnapshot.job(raw,source);assertEquals("driver_report",view.getString("kind"));assertEquals(1,view.getInt("exitCode"));assertTrue(view.getString("diff").contains("import fixed"));assertFalse(view.getBoolean("immutableArtifact"));
        assertThrows(SecurityException.class,()->RemoteResultSnapshot.job(new JSONObject(raw.toString()).put("operationId","other"),source));
    }
    @Test public void actualArtifactBytesHashAndPathAreVerifiedBeforePreview()throws Exception {
        byte[] bytes="真实文件内容".getBytes(StandardCharsets.UTF_8);StringBuilder sha=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))sha.append(String.format("%02x",b&255));
        JSONObject raw=new JSONObject().put("path","src/main.py").put("bytes",bytes.length).put("sha256",sha.toString()).put("base64",Base64.getEncoder().encodeToString(bytes));
        JSONObject view=RemoteResultSnapshot.artifact(raw,source(),"src/main.py");assertEquals("真实文件内容",view.getString("text"));assertEquals("current_workspace_bytes",view.getString("kind"));assertFalse(view.getBoolean("immutableArtifact"));
        assertThrows(SecurityException.class,()->RemoteResultSnapshot.artifact(raw,source(),"other.py"));
        assertThrows(SecurityException.class,()->RemoteResultSnapshot.artifact(new JSONObject(raw.toString()).put("sha256","0".repeat(64)),source(),"src/main.py"));
    }
}
