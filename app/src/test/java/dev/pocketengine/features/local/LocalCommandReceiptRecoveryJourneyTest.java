package dev.pocketengine.features.local;

import static org.junit.Assert.*;
import android.app.Application;
import android.content.Context;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.local.runtime.ManagedProjectRuntime;
import dev.pocketengine.features.workspace.WorkspaceModule;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.*;

/** Actual LocalModule/Workspace and an unavailable native runtime, not a fake
 * executor/process release or physical device acceptance. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public final class LocalCommandReceiptRecoveryJourneyTest {
    private static Context android(){return org.robolectric.RuntimeEnvironment.getApplication();}
    private static void clean(Path root)throws Exception{try(var files=Files.walk(root)){for(Path path:files.sorted(java.util.Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
    private static String project(WorkspaceModule workspace)throws Exception {String project=workspace.handle("create",new JSONObject().put("name","original project")).getString("id");workspace.handle("write",new JSONObject().put("projectId",project).put("path","main.py").put("content","original user source\n").put("expectedVersion",0));return project;}
    @Test public void damagedLegacyReceiptNoLongerDisablesOriginalProjectAndRuntimeReadViewsButCannotClearUnknownForWrites()throws Exception {
        Context android=android();AtomicInteger events=new AtomicInteger();FeatureContext context=new FeatureContext(android,null,null,event->events.incrementAndGet());var workspace=new WorkspaceModule(context);String project=project(workspace);Path runtimeRoot=Files.createTempDirectory("legacy-local-module-");
        Path receiptRoot=android.getFilesDir().toPath().resolve("local-development/command-receipts");Files.createDirectories(receiptRoot);byte[] damaged="{\"id\":\"torn_private_output".getBytes(StandardCharsets.UTF_8);Files.write(receiptRoot.resolve("damaged.json"),damaged);
        try(var runtime=ManagedProjectRuntime.unconfigured(runtimeRoot.toFile());var local=new LocalModule(context,workspace,runtime)){
            JSONObject snapshot=local.snapshot(),health=snapshot.getJSONObject("legacyReceiptRecovery");assertEquals(1,health.getInt("unreadableOriginalRecordsRetained"));assertTrue(health.getBoolean("originalInventoryUnconfirmed"));assertFalse(snapshot.toString().contains("torn_private_output"));assertFalse(health.getBoolean("executionAuthorityRestored"));assertEquals(0,events.get());
            assertEquals(health.toString(),local.handle("probe",new JSONObject()).getJSONObject("legacyReceiptRecovery").toString());assertEquals(health.toString(),local.handle("listJobs",new JSONObject()).getJSONObject("legacyReceiptRecovery").toString());assertFalse(snapshot.getJSONObject("projectRuntime").getBoolean("canExecute"));
            String checkout=local.handle("prepare",new JSONObject().put("projectId",project)).getString("id");JSONObject original=local.handle("readFile",new JSONObject().put("checkoutId",checkout).put("path","main.py"));assertEquals("original user source\n",original.getString("content"));
            assertThrows(IllegalStateException.class,()->local.handle("writeFile",new JSONObject(original.toString()).put("expectedHash",original.getString("sha256")).put("content","must not overwrite")));assertEquals("original user source\n",workspace.handle("read",new JSONObject().put("projectId",project).put("path","main.py")).getString("content"));assertArrayEquals(damaged,Files.readAllBytes(receiptRoot.resolve("damaged.json")));assertEquals(0,events.get());
        }finally{workspace.close();clean(runtimeRoot);}
    }
    @Test public void aColdOriginalCommandKeepsOnlyItsActualCheckoutBusyAndUnrelatedOriginalEditingStillWorks()throws Exception {
        Context android=android();FeatureContext context=new FeatureContext(android,null,null,event->fail("cold observation emitted a dispatch event"));var workspace=new WorkspaceModule(context);String project=project(workspace);Path runtimeRoot=Files.createTempDirectory("legacy-checkout-isolation-");String checkout;
        try{
            try(var runtime=ManagedProjectRuntime.unconfigured(runtimeRoot.toFile());var local=new LocalModule(context,workspace,runtime)){checkout=local.handle("prepare",new JSONObject().put("projectId",project)).getString("id");}
            Path file=android.getFilesDir().toPath().resolve("local-development/command-receipts/original.json");byte[] persisted=new JSONObject().put("id","original").put("operationId","original").put("status","running").put("checkoutId",checkout).put("output","original prefix").toString().getBytes(StandardCharsets.UTF_8);Files.write(file,persisted);
            try(var runtime=ManagedProjectRuntime.unconfigured(runtimeRoot.toFile());var local=new LocalModule(context,workspace,runtime)){
                assertEquals("unknown",local.handle("getJob",new JSONObject().put("jobId","original")).getString("status"));JSONObject blocked=local.handle("readFile",new JSONObject().put("checkoutId",checkout).put("path","main.py"));assertThrows(IllegalStateException.class,()->local.handle("writeFile",new JSONObject(blocked.toString()).put("expectedHash",blocked.getString("sha256")).put("content","must not enter")));
                java.lang.reflect.Method dispatchGate=LocalModule.class.getDeclaredMethod("requireCheckoutWritable",String.class);dispatchGate.setAccessible(true);var denied=assertThrows(java.lang.reflect.InvocationTargetException.class,()->dispatchGate.invoke(local,checkout));assertTrue(denied.getCause() instanceof IllegalStateException);
                String other=local.handle("prepare",new JSONObject().put("projectId",project)).getString("id");dispatchGate.invoke(local,other);JSONObject readable=local.handle("readFile",new JSONObject().put("checkoutId",other).put("path","main.py"));local.handle("writeFile",new JSONObject(readable.toString()).put("expectedHash",readable.getString("sha256")).put("content","explicit unrelated local edit\n"));assertEquals("explicit unrelated local edit\n",local.handle("readFile",new JSONObject().put("checkoutId",other).put("path","main.py")).getString("content"));assertEquals("original user source\n",local.handle("readFile",new JSONObject().put("checkoutId",checkout).put("path","main.py")).getString("content"));assertArrayEquals(persisted,Files.readAllBytes(file));assertFalse(local.snapshot().getJSONObject("legacyReceiptRecovery").getBoolean("originalInventoryUnconfirmed"));
            }
        }finally{workspace.close();clean(runtimeRoot);}
    }
}
