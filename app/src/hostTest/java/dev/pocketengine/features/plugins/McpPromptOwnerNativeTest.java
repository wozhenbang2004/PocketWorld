package dev.pocketengine.features.plugins;

import static org.junit.Assert.*;
import android.app.Application;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.features.*;
import dev.pocketengine.store.TaskStore;
import java.nio.file.Files;
import java.util.UUID;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;

/** Original native owner entry and installed module; no automatic conversation/Skill mutation. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class McpPromptOwnerNativeTest {
    @Test public void ownerListsAndReadsTheExactPromptAsReferenceWithoutCreatingOrRunningATask()throws Exception{
        try(McpPromptReadTest.Installed f=new McpPromptReadTest.Installed()){
            f.android.deleteDatabase("pocket-world.db");TaskStore tasks=new TaskStore(f.android);FeatureHost host=new FeatureHost(event->{},tasks,()->false);host.register(f.plugins);
            try{
                NativeOwnerWorkflows owner=new NativeOwnerWorkflows(host,f.context,tasks,new MemorySourceBridge(host,tasks));int count=tasks.list().length();var path=new java.io.File(f.android.getFilesDir(),"plugins/manifests.json").toPath();String original=readUtf8(path);
                JSONObject request=new JSONObject().put("id","remote").put("expectedInstallationId",f.installation.getString("installationId")).put("expectedStateRevision",f.installation.getLong("stateRevision")).put("_operationId",UUID.randomUUID().toString());JSONObject listed=owner.mcp("listMcpPrompts",request),selected=listed.getJSONArray("prompts").getJSONObject(0);
                JSONObject result=owner.mcp("getMcpPrompt",new JSONObject(request.toString()).put("_operationId",UUID.randomUUID().toString()).put("name",selected.getString("name")).put("descriptorDigest",selected.getString("descriptorDigest")).put("arguments",new JSONObject().put("code","private owner reference")));
                assertEquals("user",result.getJSONArray("sourceMessages").getJSONObject(0).getString("sourceRole"));assertFalse(result.getBoolean("automaticConversationInjection"));assertTrue(result.getBoolean("untrusted"));assertFalse(result.getBoolean("toolGrantsChanged"));assertEquals(count,tasks.list().length());assertEquals(0,f.server.count("tools/call"));assertEquals(0,f.server.count("resources/read"));assertEquals(original,readUtf8(path));assertEquals(0,f.plugins.toolRegistry().length());
            }finally{host.close();var field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);((SQLiteDatabase)field.get(tasks)).close();}
        }
    }
    private static String readUtf8(java.nio.file.Path path)throws java.io.IOException{return new String(Files.readAllBytes(path),java.nio.charset.StandardCharsets.UTF_8);}

}
