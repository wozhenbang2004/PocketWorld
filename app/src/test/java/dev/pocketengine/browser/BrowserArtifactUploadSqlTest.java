package dev.pocketengine.browser;

import dev.pocketengine.store.TaskStore;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=35,manifest=Config.NONE) @SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class BrowserArtifactUploadSqlTest {
    @Test public void originalLedgerRetainsWorkspaceVersionAcrossRestartAndRejectsUnknownReplay()throws Exception {
        try(var f=new BrowserTaskPersistenceTest.Fixture()){
            JSONObject identity=f.source().put("operationId",UUID.randomUUID().toString()).put("documentId",UUID.randomUUID().toString())
                    .put("chooserId",UUID.randomUUID().toString()).put("fileInput",new JSONObject().put("ref","n0").put("nodeKey","k1").put("type","file"));
            JSONObject destination=BrowserUploads.destinationIdentity("https://upload.example/form?private=original");
            for(var keys=destination.keys();keys.hasNext();){String key=keys.next();identity.put(key,destination.get(key));}
            JSONObject artifact=BrowserArtifactUploadTest.artifact(),attempt=f.tasks.beginBrowserUpload(identity,new JSONArray().put(artifact));
            f.tasks.updateBrowserUpload(f.binding.taskId,attempt.getString("attemptId"),attempt.getString("fingerprint"),"delivery_entered",new JSONObject());
            TaskStore reopened=new TaskStore(RuntimeEnvironment.getApplication());
            try {
                JSONObject saved=reopened.browserUploadAttempt(f.binding.taskId,attempt.getString("attemptId"),attempt.getString("fingerprint"));
                JSONObject file=saved.getJSONObject("pin").getJSONArray("attachments").getJSONObject(0);
                assertEquals("workspace",file.getString("store"));assertEquals(7,file.getInt("sourceVersion"));assertEquals(artifact.getString("sourceRevisionId"),file.getString("sourceRevisionId"));
                assertEquals(artifact.getString("sha256"),file.getString("sha256"));assertEquals("delivery_unknown",saved.getString("deliveryState"));assertEquals("unknown",saved.getString("siteState"));
                JSONArray prior=reopened.get(f.binding.taskId,false).getJSONArray("browserUploadAttempts");
                JSONObject freshOperation=new JSONObject(identity.toString()).put("operationId",UUID.randomUUID().toString()).put("chooserId",UUID.randomUUID().toString());
                assertThrows(SecurityException.class,()->BrowserUploadAttempt.requireNewArtifactAttempt(prior,freshOperation,artifact));
                assertThrows(SecurityException.class,()->BrowserUploadAttempt.requireNewArtifactAttempt(prior,identity,artifact));
                assertFalse(saved.getBoolean("automaticReplayAllowed"));
            } finally {BrowserTaskPersistenceTest.closeDatabase(reopened);}
        }
    }
}
