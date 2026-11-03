package dev.pocketengine.features.plugins;

import dev.pocketengine.core.RunControl;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Real loopback wire for each explicitly negotiated HTTP protocol. No public provider/device claim. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE)
public final class McpResourceTemplateClientTest {
    private static final String TEMPLATE="memo://fixture/items/{id}{?q}",TOKEN="template-fixture-credential";
    private static JSONObject definition(String uri)throws Exception{return new JSONObject().put("name","resource by parameters").put("uriTemplate",uri).put("description","original");}
    private static final class Fixture implements AutoCloseable {
        final McpFixture server=new McpFixture();final McpClient client;final boolean modern;
        volatile JSONArray templates=new JSONArray().put(definition(TEMPLATE)).put(definition("memo://fixture/{id:1}"));
        volatile String cursorMode="",wantedUri="",readMode="text";
        volatile CountDownLatch readEntered,readRelease;
        Fixture(String version)throws Exception{
            modern=McpData.VERSION.equals(version);server.modern=modern;
            server.handler=exchange->{try{respond(exchange);}catch(AssertionError error){server.failures.add(error);exchange.error(500,-32603);}};
            client=new McpClient(server.endpoint(),TOKEN,version);assertEquals(0,client.discover(new RunControl()).length());
        }
        void respond(McpFixture.Exchange exchange)throws Exception{
            switch(exchange.method()){
                case "server/discover":case "initialize":{
                    JSONObject result=server.discovery();result.getJSONObject("capabilities").remove("tools");result.getJSONObject("capabilities").put("resources",new JSONObject());
                    if(!modern){exchange.headers.put("MCP-Session-Id",server.session);result.remove("resultType");}exchange.json(result);break;
                }
                case "resources/templates/list":{
                    JSONObject params=exchange.body.getJSONObject("params");assertEquals("Bearer "+TOKEN,exchange.requestHeaders.get("authorization"));assertEquals(server.endpoint().substring(server.endpoint().indexOf('/',7)),exchange.path);
                    if(modern){assertEquals("resources/templates/list",exchange.requestHeaders.get("mcp-method"));assertEquals(McpData.VERSION,params.getJSONObject("_meta").getString("io.modelcontextprotocol/protocolVersion"));}
                    else{assertFalse(params.has("_meta"));assertEquals(server.session,exchange.requestHeaders.get("mcp-session-id"));}
                    if(cursorMode.equals("unsupported")){exchange.error(200,-32601);break;}
                    JSONObject result=new JSONObject();if(modern)result.put("resultType","complete");
                    if(cursorMode.equals("empty")){
                        boolean next=params.has("cursor");if(next)assertEquals("",params.getString("cursor"));
                        result.put("resourceTemplates",new JSONArray().put(templates.getJSONObject(next?1:0)));if(!next)result.put("nextCursor","");
                    }else if(cursorMode.equals("loop")||cursorMode.equals("null"))result.put("resourceTemplates",new JSONArray()).put("nextCursor",cursorMode.equals("loop")?"":JSONObject.NULL);
                    else result.put("resourceTemplates",templates);
                    exchange.json(result);break;
                }
                case "resources/read":{
                    JSONObject params=exchange.body.getJSONObject("params");assertEquals(wantedUri,params.getString("uri"));assertEquals("Bearer "+TOKEN,exchange.requestHeaders.get("authorization"));
                    if(modern){assertEquals(wantedUri,exchange.requestHeaders.get("mcp-name"));assertEquals(McpData.VERSION,params.getJSONObject("_meta").getString("io.modelcontextprotocol/protocolVersion"));}
                    else assertFalse(params.has("_meta"));
                    CountDownLatch entered=readEntered,release=readRelease;if(entered!=null){entered.countDown();assertTrue(release.await(3,TimeUnit.SECONDS));}
                    if(readMode.equals("lost")){exchange.raw(200,"application/json","{\"jsonrpc\":");break;}
                    JSONObject result=new JSONObject().put("contents",new JSONArray().put(new JSONObject().put("uri",wantedUri).put("text","actual text "+TOKEN)));
                    if(readMode.equals("binary"))result.getJSONArray("contents").put(new JSONObject().put("uri","memo://fixture/binary").put("blob","AA=="));
                    if(modern)result.put("resultType","complete");exchange.json(result);break;
                }
                case "resources/list":case "tools/list":case "tools/call":throw new AssertionError("A template-only read must not fall back to literal resources, tools or another endpoint");
                default:server.standard(exchange);
            }
        }
        JSONObject preview(JSONObject arguments)throws Exception{
            JSONArray raw=client.resourceTemplates(new RunControl());return McpResourceTemplateData.preview(raw.getJSONObject(0),arguments);
        }
        JSONObject read(JSONObject preview,RunControl control)throws Exception{
            wantedUri=preview.getString("uri");return client.templateResource(TEMPLATE,preview.getString("descriptorDigest"),preview.getJSONObject("resourceTemplate").getJSONObject("arguments"),wantedUri,control);
        }
        @Override public void close()throws Exception{try{client.close();assertTrue(server.failures.toString(),server.failures.isEmpty());}finally{server.close();}}
    }

    @Test public void bothExplicitProtocolsPageTemplatesThenReadOneReviewedUriWithNoToolOrUriResolver()throws Exception{
        for(String version:new String[]{McpData.VERSION,McpData.LEGACY})try(Fixture fixture=new Fixture(version)){
            fixture.cursorMode="empty";JSONArray raw=fixture.client.resourceTemplates(new RunControl()),rows=McpResourceTemplateData.catalogue(raw);
            assertEquals(2,rows.length());assertTrue(rows.getJSONObject(0).getBoolean("supported"));assertFalse(rows.getJSONObject(1).getBoolean("supported"));
            JSONObject preview=McpResourceTemplateData.preview(raw.getJSONObject(0),new JSONObject().put("id","本机/one").put("q","~* %2F"));
            assertEquals("memo://fixture/items/%E6%9C%AC%E6%9C%BA%2Fone?q=~%2A%20%252F",preview.getString("uri"));
            fixture.readMode="binary";JSONObject read=fixture.read(preview,new RunControl());assertFalse(read.toString().contains(TOKEN));
            assertEquals(1,read.getJSONArray("contents").length());assertEquals(1,read.getJSONArray("omittedContent").length());
            assertEquals(preview.getJSONObject("resourceTemplate").toString(),read.getJSONObject("resourceTemplate").toString());
            assertEquals(1,fixture.server.count("resources/read"));assertEquals(0,fixture.server.count("resources/list"));assertFalse(read.getBoolean("automaticConversationInjection"));
        }
    }

    @Test public void changedRemovedOrAmbiguousTemplateAndUnreviewedUriCannotDispatchARead()throws Exception{
        try(Fixture fixture=new Fixture(McpData.VERSION)){
            JSONObject preview=fixture.preview(new JSONObject().put("id","chosen"));
            assertThrows(SecurityException.class,()->fixture.client.templateResource(TEMPLATE,preview.getString("descriptorDigest"),new JSONObject().put("id","changed"),preview.getString("uri"),new RunControl()));
            fixture.templates.getJSONObject(0).put("description","changed");assertThrows(SecurityException.class,()->fixture.read(preview,new RunControl()));
            fixture.templates=new JSONArray();assertThrows(SecurityException.class,()->fixture.read(preview,new RunControl()));
            fixture.templates=new JSONArray().put(definition(TEMPLATE)).put(definition(TEMPLATE));assertThrows(Exception.class,()->fixture.client.resourceTemplates(new RunControl()));
            assertEquals(0,fixture.server.count("resources/read"));
        }
    }

    @Test public void cyclicNullUnsupportedAndOverlongCataloguesNeverBecomeCompleteSelections()throws Exception{
        try(Fixture fixture=new Fixture(McpData.VERSION)){
            for(String mode:new String[]{"loop","null","unsupported"}){fixture.cursorMode=mode;assertThrows(Exception.class,()->fixture.client.resourceTemplates(new RunControl()));}
            fixture.cursorMode="";JSONArray many=new JSONArray();for(int i=0;i<97;i++)many.put(definition("memo://fixture/"+i+"/{id}"));fixture.templates=many;
            assertThrows(Exception.class,()->fixture.client.resourceTemplates(new RunControl()));assertEquals(0,fixture.server.count("resources/read"));
        }
    }

    @Test public void credentialsInDescriptorsAndArgumentsAreRejectedWhileBusinessNamesAreRetained()throws Exception{
        try(Fixture fixture=new Fixture(McpData.VERSION)){
            JSONObject preview=fixture.preview(new JSONObject().put("id","chosen"));
            assertThrows(SecurityException.class,()->fixture.client.templateResource(TEMPLATE,preview.getString("descriptorDigest"),new JSONObject().put("id",TOKEN),preview.getString("uri"),new RunControl()));
            fixture.templates.getJSONObject(0).put("token",TOKEN);assertThrows(Exception.class,()->fixture.client.resourceTemplates(new RunControl()));
            fixture.templates.getJSONObject(0).put("token","ordinary business metadata");assertEquals("ordinary business metadata",fixture.client.resourceTemplates(new RunControl()).getJSONObject(0).getString("token"));
            assertEquals(0,fixture.server.count("resources/read"));
        }
    }

    @Test public void lostReadIsNotRetriedOrPresentedAsACompleteResourceAndCancellationDropsLateData()throws Exception{
        try(Fixture fixture=new Fixture(McpData.VERSION)){
            JSONObject preview=fixture.preview(new JSONObject().put("id","chosen"));fixture.readMode="lost";
            assertThrows(Exception.class,()->fixture.read(preview,new RunControl()));assertEquals(1,fixture.server.count("resources/read"));
        }
        try(Fixture fixture=new Fixture(McpData.VERSION)){
            JSONObject preview=fixture.preview(new JSONObject().put("id","chosen"));RunControl cancelled=new RunControl();fixture.readEntered=new CountDownLatch(1);fixture.readRelease=new CountDownLatch(1);
            ExecutorService worker=Executors.newSingleThreadExecutor();try{
                var pending=worker.submit(()->fixture.read(preview,cancelled));assertTrue(fixture.readEntered.await(3,TimeUnit.SECONDS));cancelled.cancel();fixture.readRelease.countDown();
                assertThrows(ExecutionException.class,()->pending.get(3,TimeUnit.SECONDS));assertEquals(1,fixture.server.count("resources/read"));
            }finally{fixture.readRelease.countDown();worker.shutdownNow();}
        }
    }
}
