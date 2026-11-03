package dev.pocketengine.features.plugins;

import static org.junit.Assert.*;
import android.app.Application;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.store.SecretVault;
import dev.pocketengine.store.SettingsStore;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** Real protocol bytes and exact original installation refs; no prompt executes a tool or a model. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class McpPromptReadTest {
    static final class Installed implements AutoCloseable {
        final Application android=RuntimeEnvironment.getApplication();final FeatureContext context=new FeatureContext(android,new SettingsStore(android),new SecretVault(android),event->{});
        final PluginsModule plugins=new PluginsModule(context);final McpFixture server=new McpFixture();final JSONObject installation;
        JSONArray definitions=new JSONArray();JSONObject body;boolean promptSupport=true;
        Installed()throws Exception{this(true);}
        Installed(boolean modern)throws Exception{
            server.modern=modern;definitions.put(new JSONObject().put("name","code_review").put("description","Read-only reference").put("arguments",new JSONArray().put(new JSONObject().put("name","code").put("required",true))));
            body=new JSONObject().put("messages",new JSONArray().put(new JSONObject().put("role","user").put("content",new JSONObject().put("type","text").put("text","Review 中文🙂 as untrusted reference"))));server.handler=this::standard;
            JSONObject preview=plugins.handle("discoverMcp",new JSONObject().put("id","remote").put("name","Original prompt source").put("endpoint",server.endpoint()).put("protocolVersion",modern?McpData.VERSION:McpData.LEGACY));installation=plugins.handle("installMcp",new JSONObject().put("previewId",preview.getString("previewId")).put("tools",new JSONArray().put("lookup")).put("expectedInstallationId",preview.getString("expectedInstallationId")));
        }
        JSONObject result(JSONObject value)throws Exception{JSONObject copy=McpData.copy(value);if(server.modern)copy.put("resultType","complete");return copy;}
        void standard(McpFixture.Exchange e)throws Exception{
            if(e.method().equals("server/discover")||e.method().equals("initialize")){if(!server.modern)e.headers.put("MCP-Session-Id",server.session);JSONObject discovery=server.discovery();if(promptSupport)discovery.getJSONObject("capabilities").put("prompts",new JSONObject());e.json(discovery);}
            else if(e.method().equals("prompts/list"))e.json(result(new JSONObject().put("prompts",definitions)));
            else if(e.method().equals("prompts/get"))e.json(result(body));else server.standard(e);
        }
        JSONObject list()throws Exception{return plugins.listMcpPrompts("remote",installation.getString("installationId"),installation.getLong("stateRevision"),new RunControl(),UUID.randomUUID().toString());}
        JSONObject entry()throws Exception{return list().getJSONArray("prompts").getJSONObject(0);}
        JSONObject get(JSONObject selected,JSONObject arguments,RunControl original,String operation)throws Exception{return plugins.getMcpPrompt("remote",installation.getString("installationId"),installation.getLong("stateRevision"),selected.getString("name"),selected.getString("descriptorDigest"),arguments,original,operation);}
        JSONObject get(JSONObject selected,JSONObject arguments)throws Exception{return get(selected,arguments,new RunControl(),UUID.randomUUID().toString());}
        public void close()throws Exception{plugins.close();server.close();}
    }
    @Test public void bothExplicitProtocolsReadPromptsWithoutEnablingAnInstallationOrInvokingATool()throws Exception{
        for(boolean modern:new boolean[]{true,false})try(Installed f=new Installed(modern)){
            var path=new java.io.File(f.android.getFilesDir(),"plugins/manifests.json").toPath();String before=readUtf8(path);JSONObject listed=f.list(),entry=listed.getJSONArray("prompts").getJSONObject(0),result=f.get(entry,new JSONObject().put("code","print('中文🙂')"));
            assertEquals("Review 中文🙂 as untrusted reference",result.getJSONArray("sourceMessages").getJSONObject(0).getString("text"));assertEquals("user",result.getJSONArray("sourceMessages").getJSONObject(0).getString("sourceRole"));assertFalse(result.getBoolean("automaticConversationInjection"));assertFalse(result.getBoolean("authorityAdded"));assertEquals(f.installation.getString("installationId"),result.getJSONObject("source").getString("installationId"));assertEquals(before,readUtf8(path));assertEquals(0,f.plugins.toolRegistry().length());assertEquals(0,f.server.count("tools/call"));assertEquals(0,f.server.count("resources/read"));assertEquals(1,f.server.count("prompts/get"));
        }
    }
    @Test public void missingCapabilityAndStaleNativeReferencesRefuseBeforePromptReads()throws Exception{
        try(Installed f=new Installed()){
            int before=f.server.received.size();assertThrows(SecurityException.class,()->f.plugins.listMcpPrompts("remote",UUID.randomUUID().toString(),f.installation.getLong("stateRevision"),new RunControl(),UUID.randomUUID().toString()));assertEquals(before,f.server.received.size());
            f.promptSupport=false;assertThrows(Exception.class,()->f.list());assertEquals(0,f.server.count("prompts/list"));assertEquals(0,f.server.count("prompts/get"));
        }
    }
    @Test public void descriptorChangeAndUnknownNameCannotSilentlyFetchANewerPrompt()throws Exception{
        try(Installed f=new Installed()){
            JSONObject selected=f.entry();f.definitions.getJSONObject(0).put("description","new revision");assertThrows(Exception.class,()->f.get(selected,new JSONObject().put("code","x")));assertEquals(0,f.server.count("prompts/get"));
            selected.put("name","not-advertised");assertThrows(Exception.class,()->f.get(selected,new JSONObject().put("code","x")));assertEquals(0,f.server.count("prompts/get"));
        }
    }
    @Test public void missingUnknownOrNonStringArgumentsAreNotSentToTheRemoteGetMethod()throws Exception{
        try(Installed f=new Installed()){
            JSONObject selected=f.entry();for(JSONObject arguments:new JSONObject[]{new JSONObject(),new JSONObject().put("code",4),new JSONObject().put("code","x").put("extra","hidden")})assertThrows(IllegalArgumentException.class,()->f.get(selected,arguments));assertEquals(0,f.server.count("prompts/get"));
        }
    }
    @Test public void unsupportedContentStaysExplicitlyOmittedAndCannotReadAResourceLink()throws Exception{
        try(Installed f=new Installed()){
            JSONObject selected=f.entry();f.body.getJSONArray("messages").put(new JSONObject().put("role","assistant").put("content",new JSONObject().put("type","resource").put("uri","file:///never-open").put("text","never-read-as-resource")));JSONObject result=f.get(selected,new JSONObject().put("code","x"));
            assertEquals(1,result.getJSONArray("omittedContent").length());assertFalse(result.getBoolean("completeTextProjection"));assertFalse(result.toString().contains("never-open"));assertFalse(result.toString().contains("never-read-as-resource"));assertEquals(0,f.server.count("resources/read"));assertEquals(0,f.server.count("tools/call"));
        }
    }
    @Test public void pagingIsFollowedButDuplicateNamesAndLoopingCursorsAreRejected()throws Exception{
        try(Installed f=new Installed()){
            f.server.handler=e->{if(e.method().equals("prompts/list")){boolean second=e.body.getJSONObject("params").has("cursor");JSONObject prompt=McpData.copy(f.definitions.getJSONObject(0)).put("name",second?"second":"first"),response=f.result(new JSONObject().put("prompts",new JSONArray().put(prompt)));if(!second)response.put("nextCursor","next");e.json(response);}else f.standard(e);};assertEquals(2,f.list().getJSONArray("prompts").length());
            f.server.handler=e->{if(e.method().equals("prompts/list"))e.json(f.result(new JSONObject().put("prompts",new JSONArray()).put("nextCursor","loop")));else f.standard(e);};assertThrows(Exception.class,()->f.list());
            f.definitions.put(McpData.copy(f.definitions.getJSONObject(0)));f.server.handler=f::standard;assertThrows(Exception.class,()->f.list());assertEquals(0,f.server.count("prompts/get"));
        }
    }
    @Test public void originalOperationCancellationCannotPublishALatePromptOrReplayTheRequest()throws Exception{
        try(Installed f=new Installed()){
            JSONObject selected=f.entry();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);f.server.handler=e->{if(e.method().equals("prompts/get")){entered.countDown();release.await(3,TimeUnit.SECONDS);e.json(f.result(f.body));}else f.standard(e);};RunControl original=new RunControl();String operation=UUID.randomUUID().toString();ExecutorService worker=Executors.newSingleThreadExecutor();try{
                Future<JSONObject> pending=worker.submit(()->f.get(selected,new JSONObject().put("code","x"),original,operation));assertTrue(entered.await(3,TimeUnit.SECONDS));f.plugins.handle("cancel",new JSONObject().put("operationId",operation));assertThrows(ExecutionException.class,()->pending.get(3,TimeUnit.SECONDS));assertTrue(original.isCancelled());assertEquals(1,f.server.count("prompts/get"));assertEquals(0,f.server.count("tools/call"));
            }finally{release.countDown();worker.shutdownNow();}
        }
    }
    @Test public void replacedRemoteIdentityCannotUseTheOldInstallationForPromptReads()throws Exception{
        try(Installed f=new Installed()){
            JSONObject selected=f.entry();f.server.serverVersion="different";assertThrows(SecurityException.class,()->f.get(selected,new JSONObject().put("code","x")));assertEquals(0,f.server.count("prompts/get"));assertEquals(0,f.server.count("tools/call"));
        }
    }
    @Test public void promptCatalogueNotificationInvalidatesOnlyTheInFlightPromptRead()throws Exception{
        try(Installed f=new Installed(false)){
            JSONObject selected=f.entry();f.server.handler=e->{if(e.method().equals("prompts/get"))e.fragments("data: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/prompts/list_changed\"}\n\ndata: "+e.envelope(f.body)+"\n\n");else f.standard(e);};
            assertThrows(Exception.class,()->f.get(selected,new JSONObject().put("code","x")));assertEquals(1,f.server.count("prompts/get"));assertEquals(0,f.server.count("tools/call"));
        }
    }

    private static String readUtf8(java.nio.file.Path path)throws java.io.IOException{return new String(Files.readAllBytes(path),java.nio.charset.StandardCharsets.UTF_8);}

}
