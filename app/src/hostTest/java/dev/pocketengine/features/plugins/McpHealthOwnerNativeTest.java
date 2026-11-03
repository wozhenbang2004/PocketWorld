package dev.pocketengine.features.plugins;

import static org.junit.Assert.*;
import android.app.Application;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.features.*;
import dev.pocketengine.store.TaskStore;
import java.util.UUID;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;

/** Original public native-owner command, not an EngineModule/model action or a test-only route. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class McpHealthOwnerNativeTest {
    @Test public void nativeOwnerCommandUsesTheSameInstalledModuleAndNeverImportsAnotherServer()throws Exception{
        try(McpHealthCheckTest.Installed f=new McpHealthCheckTest.Installed()){
            f.android.deleteDatabase("pocket-world.db");TaskStore tasks=new TaskStore(f.android);FeatureHost host=new FeatureHost(event->{},tasks,()->false);host.register(f.plugins);
            try{
                NativeOwnerWorkflows owner=new NativeOwnerWorkflows(host,f.context,tasks,new MemorySourceBridge(host,tasks));JSONObject payload=new JSONObject().put("id","remote").put("expectedInstallationId",f.installation.getString("installationId")).put("expectedStateRevision",f.installation.getLong("stateRevision")).put("_operationId",UUID.randomUUID().toString());
                JSONObject result=owner.mcp("pingMcp",payload);assertEquals("responsive",result.getString("status"));assertEquals(payload.getString("_operationId"),result.getString("operationId"));assertEquals(1,f.plugins.snapshot().getJSONArray("plugins").length());assertEquals(0,f.server.count("tools/call"));
                assertThrows(IllegalArgumentException.class,()->owner.mcp("pingMcp",new JSONObject(payload.toString()).put("expectedStateRevision",1.5)));assertThrows(IllegalArgumentException.class,()->owner.mcp("pingMcp",new JSONObject(payload.toString()).put("expectedStateRevision","1")));assertEquals(1,f.server.count("ping"));
            }finally{host.close();var field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);((SQLiteDatabase)field.get(tasks)).close();}
        }
    }
}
