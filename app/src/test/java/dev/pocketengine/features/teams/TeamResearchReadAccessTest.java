package dev.pocketengine.features.teams;

import android.app.Application;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.workspace.WorkspaceModule;
import dev.pocketengine.store.SecretVault;
import dev.pocketengine.store.SettingsStore;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import static org.junit.Assert.*;

/** Actual original project bytes/current head, independent of any model or external network. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public final class TeamResearchReadAccessTest {
    @Test public void selectedProjectFileReadsRealOriginalBytesAndRevocationBlocksNextRead()throws Exception {
        var app=RuntimeEnvironment.getApplication();WorkspaceModule workspace=new WorkspaceModule(new FeatureContext(app,new SettingsStore(app),new SecretVault(app),event->{}));
        String project=workspace.handle("create",new JSONObject().put("name","scope-"+java.util.UUID.randomUUID())).getString("id");String text="准确原件\n"+"正文".repeat(7000)+"末尾证据";
        workspace.handle("write",new JSONObject().put("projectId",project).put("path","src/notes.txt").put("expectedVersion",0).put("content",text));
        JSONObject scope=TeamResearchReadAccess.capture(new JSONObject().put("webOrigins",new JSONArray().put("https://example.org")).put("projectFiles",new JSONArray().put(new JSONObject().put("projectId",project).put("path","src/notes.txt"))),workspace);RunControl original=new RunControl();boolean[] live={true};RunControl.AuthorityCheck gate=()->{if(!live[0])throw new SecurityException("revoked");};
        JSONObject first=TeamResearchReadAccess.read(scope,TeamResearchReadAccess.READ,new JSONObject().put("fileIndex",0).put("length",12000),workspace,original,gate);assertEquals(text.substring(0,12000),first.getString("content"));assertTrue(first.getBoolean("hasMore"));JSONObject tail=TeamResearchReadAccess.read(scope,TeamResearchReadAccess.READ,new JSONObject().put("fileIndex",0).put("offset",12000),workspace,original,gate);assertTrue(tail.getString("content").endsWith("末尾证据"));assertEquals(scope.getJSONArray("projectFiles").getJSONObject(0).getString("sha256"),tail.getJSONObject("source").getString("sha256"));
        live[0]=false;assertThrows(SecurityException.class,()->TeamResearchReadAccess.read(scope,TeamResearchReadAccess.READ,new JSONObject().put("fileIndex",0),workspace,original,gate));live[0]=true;workspace.handle("write",new JSONObject().put("projectId",project).put("path","src/notes.txt").put("expectedVersion",1).put("content","changed"));assertThrows(SecurityException.class,()->TeamResearchReadAccess.read(scope,TeamResearchReadAccess.READ,new JSONObject().put("fileIndex",0),workspace,original,gate));
        assertThrows(SecurityException.class,()->TeamResearchReadAccess.validate(scope,TeamResearchReadAccess.WEB,new JSONObject().put("url","https://not-shared.example/private")));assertThrows(SecurityException.class,()->TeamResearchReadAccess.validate(scope,TeamResearchReadAccess.READ,new JSONObject().put("fileIndex",0).put("path","../secret")));workspace.close();
    }
    @Test public void ownerCaptureRejectsOriginsTheOriginalPublicReaderCannotUse()throws Exception {
        for(String origin:new String[]{"https://example.org:444","https://a.local","https://localhost"}){
            JSONObject input=new JSONObject().put("webOrigins",new JSONArray().put(origin)).put("projectFiles",new JSONArray());
            assertThrows(Exception.class,()->TeamResearchReadAccess.capture(input,null));
        }
        JSONObject empty=TeamResearchReadAccess.capture(new JSONObject().put("webOrigins",new JSONArray()).put("projectFiles",new JSONArray()),null);
        assertEquals(0,TeamResearchReadAccess.tools(empty).length());
    }
}
