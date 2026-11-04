package dev.pocketengine.features.plugins;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.store.SecretVault;
import dev.pocketengine.store.SettingsStore;
import java.io.File;
import java.io.FileInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Original native module plus real file bytes and loopback RPC. No device picker/OS grant claim. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class McpConfigImportNativeJourneyTest {
    private static final class Journey implements AutoCloseable {
        final Context android=RuntimeEnvironment.getApplication();
        final PluginsModule plugins=new PluginsModule(new FeatureContext(android,new SettingsStore(android),new SecretVault(android),e->{}));
        final Uri uri=Uri.parse("content://native.import.fixture/document/"+java.util.UUID.randomUUID());
        final File input=new File(android.getCacheDir(),"selected-mcp-"+java.util.UUID.randomUUID()+".json");
        final AtomicInteger opened=new AtomicInteger(),closed=new AtomicInteger();
        IOException closeFailure;
        RunControl cancelOnRead;
        boolean interruptOnRead;
        Journey()throws Exception {
            Shadows.shadowOf(android.getContentResolver()).registerInputStreamSupplier(uri,()->{
                opened.incrementAndGet();try{return new FilterInputStream(new FileInputStream(input)){
                    private boolean done;
                    @Override public int read(byte[] bytes,int start,int length)throws IOException {
                        int count=super.read(bytes,start,length);
                        if(cancelOnRead!=null){RunControl target=cancelOnRead;cancelOnRead=null;target.cancel();}
                        if(interruptOnRead){interruptOnRead=false;Thread.currentThread().interrupt();}
                        return count;
                    }
                    @Override public void close()throws IOException {try{super.close();}finally{if(!done){done=true;closed.incrementAndGet();}}if(closeFailure!=null)throw closeFailure;}
                };}catch(IOException failure){throw new IllegalStateException("fixture input unavailable",failure);}
            });
        }
        Intent selection(){return new Intent().setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);}
        PluginsModule.SelectedMcpConfigurationDocument selected()throws Exception{return plugins.selectedMcpConfigurationDocument(Activity.RESULT_OK,selection());}
        void write(String text)throws Exception{Files.write(input.toPath(),text.getBytes(StandardCharsets.UTF_8));}
        McpConfigImportAdapter.Preview preview()throws Exception{return plugins.previewMcpConfigurationImport(selected(),McpConfigImportAdapter.Format.CLAUDE_JSON,null,new RunControl());}
        JSONObject publicInput(McpConfigImportAdapter.Preview preview,String id)throws Exception{return plugins.mcpConfigurationDiscoveryInput(preview,0,preview.reviewDigest,id,"Native import",McpData.VERSION);}
        @Override public void close(){plugins.close();}
    }
    private static String network(String endpoint)throws Exception{return new JSONObject().put("mcpServers",new JSONObject().put("remote",new JSONObject().put("url",endpoint))).toString();}
    private static JSONObject installEcho(PluginsModule plugins)throws Exception {
        JSONObject manifest=new JSONObject().put("id","existing").put("name","Original echo").put("version","1").put("tools",new JSONArray().put(new JSONObject().put("name","show").put("kind","echo")));
        return plugins.handle("install",new JSONObject().put("manifest",manifest));
    }
    private static Map<String,String> files(File root)throws Exception {
        Map<String,String> result=new LinkedHashMap<>();if(!root.exists())return result;
        try(java.util.stream.Stream<java.nio.file.Path> paths=Files.walk(root.toPath())){
            for(java.nio.file.Path path:(Iterable<java.nio.file.Path>)paths.filter(Files::isRegularFile).sorted()::iterator)result.put(root.toPath().relativize(path).toString(),java.util.Base64.getEncoder().encodeToString(Files.readAllBytes(path)));
        }return result;
    }
    @Test public void genericJsonAndInvalidResultsCannotOpenAConfigurationDocument()throws Exception {
        try(Journey j=new Journey()){
            j.write(network("https://fixture.invalid/mcp"));
            assertThrows(IllegalArgumentException.class,()->j.plugins.handle("previewMcpConfigurationImport",new JSONObject().put("uri",j.uri.toString()).put("format","CLAUDE_JSON")));
            assertThrows(McpConfigImportAdapter.Rejected.class,()->j.plugins.selectedMcpConfigurationDocument(Activity.RESULT_CANCELED,j.selection()));
            assertThrows(McpConfigImportAdapter.Rejected.class,()->j.plugins.selectedMcpConfigurationDocument(Activity.RESULT_OK,new Intent().setData(j.uri)));
            assertThrows(McpConfigImportAdapter.Rejected.class,()->j.plugins.selectedMcpConfigurationDocument(Activity.RESULT_OK,new Intent().setData(Uri.parse("file:///private/config.json")).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)));
            assertEquals(0,j.opened.get());assertEquals(0,j.closed.get());
        }
    }
    @Test public void nativePreviewClosesActualFileAndNeedsOriginalNetworkDiscoveryBeforeAnyInstallation()throws Exception {
        try(Journey j=new Journey();McpFixture server=new McpFixture()){
            j.write(network(server.endpoint()));Map<String,String> before=files(new File(j.android.getFilesDir(),"plugins"));Map<String,?> vaultBefore=new LinkedHashMap<>(j.android.getSharedPreferences("feature_secrets",Context.MODE_PRIVATE).getAll());
            McpConfigImportAdapter.Preview review=j.preview();assertEquals(1,j.opened.get());assertEquals(1,j.closed.get());assertEquals(0,server.received.size());assertEquals(before,files(new File(j.android.getFilesDir(),"plugins")));assertEquals(vaultBefore,j.android.getSharedPreferences("feature_secrets",Context.MODE_PRIVATE).getAll());
            assertEquals(0,j.plugins.toolRegistry().length());JSONObject input=j.publicInput(review,"remote");assertEquals(0,server.received.size());assertEquals("",input.getString("expectedInstallationId"));assertEquals(-1,input.getLong("expectedStateRevision"));assertFalse(input.has("token"));assertFalse(input.has("headers"));assertFalse(input.has("tools"));assertFalse(input.has("grants"));
            assertThrows(IllegalStateException.class,()->j.plugins.handle("installMcp",new JSONObject().put("previewId",review.reviewDigest).put("tools",new JSONArray().put("lookup"))));
            JSONObject discovery=j.plugins.handle("discoverMcp",input);assertTrue(server.count("server/discover")>0);assertTrue(discovery.getJSONArray("tools").getJSONObject(0).getBoolean("supported"));assertFalse(discovery.getBoolean("installed"));assertEquals(0,j.plugins.snapshot().getJSONArray("plugins").length());assertEquals(0,j.plugins.toolRegistry().length());assertEquals(0,server.count("tools/call"));
            j.plugins.handle("discardMcpPreview",new JSONObject().put("previewId",discovery.getString("previewId")));assertEquals(0,server.count("tools/call"));
        }
    }
    @Test public void selectedDocumentAndReviewCannotCrossNativeOwnerInstances()throws Exception {
        try(Journey first=new Journey();Journey second=new Journey()){
            first.write(network("https://fixture.invalid/mcp"));PluginsModule.SelectedMcpConfigurationDocument selected=first.selected();
            assertThrows(McpConfigImportAdapter.Rejected.class,()->second.plugins.previewMcpConfigurationImport(selected,McpConfigImportAdapter.Format.CLAUDE_JSON,null,new RunControl()));assertEquals(0,first.opened.get());assertEquals(0,second.opened.get());
            McpConfigImportAdapter.Preview review=first.preview();assertThrows(McpConfigImportAdapter.Rejected.class,()->second.publicInput(review,"remote"));assertEquals(1,first.opened.get());assertEquals(1,first.closed.get());assertEquals(0,second.opened.get());assertEquals(0,second.closed.get());
        }
    }
    @Test public void installationRevisionChangesInvalidateNativeReviewWithoutChangingOtherSourceState()throws Exception {
        try(Journey j=new Journey()){
            JSONObject installed=installEcho(j.plugins);j.write(network("https://fixture.invalid/mcp"));McpConfigImportAdapter.Preview review=j.preview();JSONObject input=j.publicInput(review,"existing");assertEquals(installed.getString("installationId"),input.getString("expectedInstallationId"));assertEquals(installed.getLong("stateRevision"),input.getLong("expectedStateRevision"));
            dev.pocketengine.features.plugins.PluginOwnerTestReview.enableCurrent(j.plugins,"existing",true);Map<String,String> changed=files(new File(j.android.getFilesDir(),"plugins"));
            assertEquals("installation_changed_since_preview",assertThrows(McpConfigImportAdapter.Rejected.class,()->j.publicInput(review,"existing")).code);assertEquals(changed,files(new File(j.android.getFilesDir(),"plugins")));assertEquals(1,j.opened.get());assertEquals(1,j.closed.get());assertEquals(0,j.plugins.toolRegistry().length());
        }
    }
    @Test public void cancelledOrOversizeNativeReadsNeverLeaveAnOpenStreamOrLiveWrite()throws Exception {
        try(Journey j=new Journey()){
            j.write(" ".repeat(McpConfigImportAdapter.MAX_BYTES+1));Map<String,String> before=files(new File(j.android.getFilesDir(),"plugins"));
            assertEquals("source_size",assertThrows(McpConfigImportAdapter.Rejected.class,j::preview).code);assertEquals(1,j.opened.get());assertEquals(1,j.closed.get());assertEquals(before,files(new File(j.android.getFilesDir(),"plugins")));
            RunControl cancelled=new RunControl();cancelled.cancel();PluginsModule.SelectedMcpConfigurationDocument selected=j.selected();assertThrows(InterruptedException.class,()->j.plugins.previewMcpConfigurationImport(selected,McpConfigImportAdapter.Format.CLAUDE_JSON,null,cancelled));assertEquals(1,j.opened.get());assertEquals(1,j.closed.get());
        }
    }
    @Test public void providerCloseDiagnosticsCannotLeakThroughPrimaryParserRejectionOrSuccess()throws Exception {
        try(Journey j=new Journey()){
            j.closeFailure=new IOException("fixture-close-sensitive-value");j.write("{invalid");
            McpConfigImportAdapter.Rejected invalid=assertThrows(McpConfigImportAdapter.Rejected.class,j::preview);
            assertNull(invalid.getCause());assertEquals("provider diagnostics must not become suppressed source content",0,invalid.getSuppressed().length);assertFalse(invalid.toString().contains("fixture-close-sensitive-value"));assertEquals(1,j.closed.get());
            j.write(network("https://fixture.invalid/mcp"));McpConfigImportAdapter.Rejected closed=assertThrows(McpConfigImportAdapter.Rejected.class,j::preview);
            assertNull(closed.getCause());assertEquals(0,closed.getSuppressed().length);assertFalse(closed.toString().contains("fixture-close-sensitive-value"));assertEquals(2,j.closed.get());
        }
    }

    @Test public void delayedDiscoveryCannotUseCapturedStateAfterNativeReviewBecomesStale()throws Exception {
        try(Journey j=new Journey();McpFixture server=new McpFixture()){
            installEcho(j.plugins);j.write(network(server.endpoint()));McpConfigImportAdapter.Preview review=j.preview();JSONObject input=j.publicInput(review,"existing");
            dev.pocketengine.features.plugins.PluginOwnerTestReview.enableCurrent(j.plugins,"existing",true);Map<String,String> current=files(new File(j.android.getFilesDir(),"plugins"));
            IllegalStateException rejected=assertThrows(IllegalStateException.class,()->j.plugins.handle("discoverMcp",input));
            assertEquals("插件安装或状态已变化，请刷新后重新审阅MCP配置",rejected.getMessage());assertEquals(0,server.received.size());assertEquals(current,files(new File(j.android.getFilesDir(),"plugins")));
        }
    }
    @Test public void delayedDiscoveryCannotUseCapturedInstallationAfterReplacement()throws Exception {
        try(Journey j=new Journey();McpFixture server=new McpFixture()){
            JSONObject original=installEcho(j.plugins);j.write(network(server.endpoint()));JSONObject input=j.publicInput(j.preview(),"existing");
            j.plugins.handle("remove",new JSONObject().put("id","existing"));JSONObject replacement=installEcho(j.plugins);assertNotEquals(original.getString("installationId"),replacement.getString("installationId"));
            IllegalStateException rejected=assertThrows(IllegalStateException.class,()->j.plugins.handle("discoverMcp",input));
            assertEquals("插件安装或状态已变化，请刷新后重新审阅MCP配置",rejected.getMessage());assertEquals(0,server.received.size());assertEquals(replacement.getString("installationId"),j.plugins.snapshot().getJSONArray("plugins").getJSONObject(0).getString("installationId"));
        }
    }
    @Test public void partialOrNonIntegralExpectedIdentityRejectsBeforeAnyDiscoveryRequest()throws Exception {
        try(Journey j=new Journey();McpFixture server=new McpFixture()){
            j.write(network(server.endpoint()));JSONObject input=j.publicInput(j.preview(),"remote");
            for(String absent:new String[]{"expectedInstallationId","expectedStateRevision"}){
                JSONObject partial=new JSONObject(input.toString());partial.remove(absent);
                assertThrows(IllegalArgumentException.class,()->j.plugins.handle("discoverMcp",partial));assertEquals(0,server.received.size());
            }
            for(Object invalid:new Object[]{"-1",-1.5,JSONObject.NULL}){
                JSONObject wrong=new JSONObject(input.toString()).put("expectedStateRevision",invalid);
                assertThrows(IllegalArgumentException.class,()->j.plugins.handle("discoverMcp",wrong));assertEquals(0,server.received.size());
            }
        }
    }
    @Test public void originalDiscoveryWithoutExpectedFieldsKeepsItsExistingPublicWorkflow()throws Exception {
        try(Journey j=new Journey();McpFixture server=new McpFixture()){
            JSONObject result=j.plugins.handle("discoverMcp",new JSONObject().put("id","legacy-input").put("name","Existing native form").put("endpoint",server.endpoint()));
            assertTrue(server.count("server/discover")>0);assertFalse(result.getBoolean("installed"));assertEquals(0,server.count("tools/call"));assertEquals(0,j.plugins.snapshot().getJSONArray("plugins").length());
            j.plugins.handle("discardMcpPreview",new JSONObject().put("previewId",result.getString("previewId")));
        }
    }
    @Test public void closeFailureCannotReplaceOriginalCancellationOrInterruption()throws Exception {
        try(Journey j=new Journey()){
            j.write(network("https://fixture.invalid/mcp"));j.closeFailure=new IOException("fixture-close-sensitive-value");RunControl original=new RunControl();j.cancelOnRead=original;
            PluginsModule.SelectedMcpConfigurationDocument selected=j.selected();InterruptedException cancelled=assertThrows(InterruptedException.class,()->j.plugins.previewMcpConfigurationImport(selected,McpConfigImportAdapter.Format.CLAUDE_JSON,null,original));
            assertTrue(original.isCancelled());assertEquals("Run stopped",cancelled.getMessage());assertNull(cancelled.getCause());assertEquals(0,cancelled.getSuppressed().length);assertEquals(1,j.opened.get());assertEquals(1,j.closed.get());
            j.interruptOnRead=true;
            try{
                InterruptedException interrupted=assertThrows(InterruptedException.class,j::preview);assertEquals("Run stopped",interrupted.getMessage());assertNull(interrupted.getCause());assertEquals(0,interrupted.getSuppressed().length);assertTrue(Thread.currentThread().isInterrupted());assertEquals(2,j.opened.get());assertEquals(2,j.closed.get());
            }finally{Thread.interrupted();}
        }
    }

}
