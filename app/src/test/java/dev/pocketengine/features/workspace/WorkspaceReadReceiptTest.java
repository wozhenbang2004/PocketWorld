package dev.pocketengine.features.workspace;

import android.app.Application;
import dev.pocketengine.features.FeatureContext;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=35,application=Application.class)
public final class WorkspaceReadReceiptTest {
    @Test public void knownAbsentOrDeletedFilesReturnErrorsWithoutLosingHistoricalBytes()throws Exception {
        WorkspaceModule workspace=new WorkspaceModule(new FeatureContext(RuntimeEnvironment.getApplication(),null,null,event->{}));
        try{
            String id=workspace.handle("create",new JSONObject().put("name","receipt fixture")).getString("id");
            JSONObject args=new JSONObject().put("projectId",id).put("path","hello.txt");
            JSONObject missing=workspace.handle("read",args);assertFalse(missing.getBoolean("ok"));assertEquals("workspace_file_not_found",missing.getString("code"));assertFalse(missing.has("content"));
            workspace.handle("write",new JSONObject(args.toString()).put("content","saved bytes").put("expectedVersion",0));
            assertTrue(workspace.handle("read",args).getBoolean("ok"));
            workspace.handle("removeFile",new JSONObject(args.toString()).put("expectedVersion",1));
            JSONObject deleted=workspace.handle("read",args);assertFalse(deleted.getBoolean("ok"));assertEquals("workspace_file_deleted",deleted.getString("code"));assertFalse(deleted.has("content"));
            assertEquals("saved bytes",workspace.handle("read",new JSONObject(args.toString()).put("version",1)).getString("content"));
            assertThrows(IllegalArgumentException.class,()->workspace.handle("read",new JSONObject(args.toString()).put("path","../outside")));
            assertThrows(IllegalArgumentException.class,()->workspace.handle("read",new JSONObject(args.toString()).put("projectId","invalid")));
            assertThrows(IllegalArgumentException.class,()->workspace.handle("read",new JSONObject(args.toString()).put("version",999)));
        }finally{workspace.close();}
    }
}
