package dev.pocketengine.features.workspace;

import android.app.Application;
import android.content.*;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.local.LocalModule;
import dev.pocketengine.features.local.runtime.ManagedProjectRuntime;
import java.io.File;
import java.nio.file.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class LocalStoragePrivateRootTest {
    @Before public void installVault(){OwnerDataArchiveFixture.installFixtureVault();}
    @After public void releaseVault(){OwnerDataArchiveFixture.removeFixtureVault();}
    private static JSONObject group(JSONObject inventory,String id)throws Exception{
        JSONArray groups=inventory.getJSONArray("groups");for(int i=0;i<groups.length();i++)if(id.equals(groups.getJSONObject(i).getString("id")))return groups.getJSONObject(i);throw new AssertionError(id);
    }
    private static Context alias(Context original,Path parent){return new ContextWrapper(original){
        @Override public Context getApplicationContext(){return this;}
        @Override public File getFilesDir(){return parent.resolve("files").toFile();}
        @Override public File getCacheDir(){return parent.resolve("cache").toFile();}
    };}
    @Test public void trustedParentAliasMeasuresActualProtectedFilesAndCache()throws Exception{
        Path base=Files.createTempDirectory("local-storage-alias-"),install=base.resolve("install"),linked=base.resolve("framework-parent");
        try(var fixture=new OwnerDataArchiveFixture(RuntimeEnvironment.getApplication(),install)){
            Files.createSymbolicLink(linked,install);
            Files.write(fixture.android.getCacheDir().toPath().resolve("cache-proof"),new byte[]{1,2,3,4});
            Context android=alias(fixture.android,linked);
            FeatureContext context=new FeatureContext(android,fixture.context.settings,fixture.context.secrets,event->{});
            try(var local=new LocalModule(context,fixture.workspace,ManagedProjectRuntime.unconfigured(base.resolve("runtime").toFile()))){
                JSONObject inventory=local.ownerStorageInventoryForHost(new RunControl());
                JSONObject cache=group(inventory,"protected:app-cache").getJSONObject("usage");
                assertTrue(cache.toString(),cache.getBoolean("complete"));assertEquals(4,cache.getLong("logicalBytes"));
                assertTrue(group(inventory,"protected:workspace").getJSONObject("usage").getBoolean("complete"));
                assertTrue(group(inventory,"protected:owner-data-archives").getJSONObject("usage").getBoolean("complete"));
            }
        }
    }
    @Test public void cacheLeafLinkRemainsUnknownAndNeverCountsOutsideBytes()throws Exception{
        Path base=Files.createTempDirectory("local-storage-cache-leaf-"),outside=Files.createDirectory(base.resolve("outside"));
        Files.write(outside.resolve("sentinel"),new byte[]{9,8,7});
        try(var fixture=new OwnerDataArchiveFixture(RuntimeEnvironment.getApplication(),base.resolve("install"))){
            Path cache=fixture.android.getCacheDir().toPath();Files.move(cache,cache.resolveSibling("retained-cache"));Files.createSymbolicLink(cache,outside);
            JSONObject usage=group(fixture.local.ownerStorageInventoryForHost(new RunControl()),"protected:app-cache").getJSONObject("usage");
            assertFalse(usage.toString(),usage.getBoolean("complete"));assertTrue(usage.isNull("logicalBytes"));
            assertArrayEquals(new byte[]{9,8,7},Files.readAllBytes(outside.resolve("sentinel")));
        }
    }
}
