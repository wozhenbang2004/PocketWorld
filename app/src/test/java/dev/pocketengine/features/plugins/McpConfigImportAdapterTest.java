package dev.pocketengine.features.plugins;

import android.app.Application;
import dev.pocketengine.core.RunControl;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Actual strict parser and input bytes; native picker ownership is tested separately. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class McpConfigImportAdapterTest {
    private static final String REF="content://selected.documents/document/configuration";
    private final McpConfigImportAdapter adapter=new McpConfigImportAdapter();
    private McpConfigImportAdapter.Preview inspect(String text,McpConfigImportAdapter.Format format,McpConfigImportAdapter.Preview previous,JSONArray installed)throws Exception {
        return adapter.inspect(McpConfigImportAdapter.Source.selectedSaf(REF),format,new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)),installed,previous,new RunControl());
    }
    private McpConfigImportAdapter.Preview json(JSONObject servers)throws Exception{return inspect(new JSONObject().put("mcpServers",servers).toString(),McpConfigImportAdapter.Format.CLAUDE_JSON,null,new JSONArray());}
    private static JSONObject network(String endpoint)throws Exception{return new JSONObject().put("type","http").put("url",endpoint);}
    private static JSONObject row(McpConfigImportAdapter.Preview preview,int index)throws Exception{return preview.view().getJSONArray("items").getJSONObject(index);}
    private static JSONArray installed()throws Exception{return new JSONArray().put(new JSONObject().put("id","existing").put("installationId","original-installation").put("stateRevision",4).put("version","v1").put("enabled",true).put("grantsDigest","a".repeat(64)));}

    @Test public void maliciousCommandProducesOnlyRedactedPreviewWithZeroProcessNetworkOrLiveStateChanges()throws Exception {
        java.nio.file.Path marker=Files.createTempDirectory("mcp-config-preview").resolve("must-not-exist");
        try(ServerSocket endpoint=new ServerSocket(0,4,InetAddress.getByName("127.0.0.1"))){endpoint.setSoTimeout(100);
            JSONArray original=installed();String before=original.toString();JSONObject servers=new JSONObject()
                    .put("local",new JSONObject().put("command","/bin/sh").put("args",new JSONArray().put("-c").put("touch "+marker)).put("env",new JSONObject().put("API_KEY","fixture-env-secret")))
                    .put("remote",network("http://127.0.0.1:"+endpoint.getLocalPort()+"/mcp").put("headers",new JSONObject().put("Authorization","Bearer fixture-header-secret")));
            String source=new JSONObject().put("mcpServers",servers).toString();McpConfigImportAdapter.Preview preview=inspect(source,McpConfigImportAdapter.Format.CLAUDE_JSON,null,original);String visible=preview.view().toString();
            assertFalse(visible.contains("fixture-env-secret"));assertFalse(visible.contains("fixture-header-secret"));assertFalse(visible.contains("touch "));assertFalse(visible.contains(marker.toString()));
            assertFalse(Files.exists(marker));assertThrows(SocketTimeoutException.class,endpoint::accept);assertEquals(before,original.toString());
            assertEquals("/bin/sh",row(preview,0).getString("command"));assertEquals(2,row(preview,0).getJSONArray("argv").length());assertFalse(preview.view().getBoolean("processStarted"));assertFalse(preview.view().getBoolean("networkRequested"));
            assertFalse(preview.view().getBoolean("credentialsImported"));assertFalse(preview.view().getBoolean("configurationWritten"));assertFalse(preview.view().getBoolean("enabledAfterInstall"));assertEquals(0,preview.view().getJSONArray("grantsAfterInstall").length());
        }
    }
    @Test public void knownShortCredentialValuesCannotReflectAcrossServerNamesCommandsOrEndpoints()throws Exception {
        for(String secret:new String[]{"1","12","123"}){
            McpConfigImportAdapter.Preview review=json(new JSONObject()
                    .put("local",new JSONObject().put("command","node"+secret).put("env",new JSONObject().put("API_KEY",secret)))
                    .put("remote"+secret,network("https://fixture.invalid/"+secret)));
            assertFalse("known credential reflected in executable",row(review,0).getString("command").contains(secret));
            JSONObject remote=row(review,1);assertFalse("known credential reflected in server name",remote.getString("name").contains(secret));assertFalse("known credential reflected in endpoint",remote.has("endpoint"));assertTrue(remote.getBoolean("endpointHidden"));assertFalse(remote.getBoolean("fieldsSupported"));
            assertEquals("unsupported_fields",assertThrows(McpConfigImportAdapter.Rejected.class,()->adapter.discoveryInput(review,1,review.reviewDigest,"remote","Remote",McpData.VERSION)).code);
        }
    }
    @Test public void credentialFreeHttpOnlyBuildsPinnedInputForOriginalDiscoveryAndSchemaApproval()throws Exception {
        String source=new JSONObject().put("mcpServers",new JSONObject().put("remote",network("https://fixture.invalid/mcp"))).toString();McpConfigImportAdapter.Preview preview=inspect(source,McpConfigImportAdapter.Format.GEMINI_JSON,null,installed());
        JSONObject input=adapter.discoveryInput(preview,0,preview.reviewDigest,"existing","Original native target",McpData.VERSION);
        assertEquals("https://fixture.invalid/mcp",input.getString("endpoint"));assertEquals("original-installation",input.getString("expectedInstallationId"));assertEquals(4,input.getLong("expectedStateRevision"));assertFalse(input.has("token"));assertFalse(input.has("tools"));assertFalse(input.has("grants"));
        assertTrue(preview.view().getBoolean("requiresDiscovery"));assertTrue(preview.view().getBoolean("requiresToolSchemaApproval"));
        assertThrows(McpConfigImportAdapter.Rejected.class,()->adapter.discoveryInput(preview,0,"wrong","existing","Wrong digest",McpData.VERSION));
        assertThrows(IllegalArgumentException.class,()->McpData.manifest("attempt","Attempt","https://fixture.invalid/mcp",McpData.VERSION,"a".repeat(64),new JSONArray(),new JSONArray()));
    }
    @Test public void credentialsBecomeUnboundReferencesAndCannotBeUsedAsDiscoveryTokens()throws Exception {
        McpConfigImportAdapter.Preview preview=json(new JSONObject().put("remote",network("https://fixture.invalid/mcp").put("headers",new JSONObject().put("Authorization","Bearer fixture-http-credential"))));
        JSONObject entry=row(preview,0),reference=entry.getJSONArray("credentialReferences").getJSONObject(0);assertEquals("Authorization",reference.getString("name"));assertFalse(reference.getBoolean("bound"));assertFalse(reference.getBoolean("valueImported"));
        assertEquals("credential_reentry_required",assertThrows(McpConfigImportAdapter.Rejected.class,()->adapter.discoveryInput(preview,0,preview.reviewDigest,"remote","Remote",McpData.VERSION)).code);assertFalse(preview.view().toString().contains("fixture-http-credential"));
    }
    @Test public void repeatedImportsCompareExactSourceVersionsWithoutChangingEarlierReviews()throws Exception {
        String first="{\"mcpServers\":{\"local\":{\"command\":\"node\",\"env\":{\"API_KEY\":\"first-hidden-value\"}}}}";
        McpConfigImportAdapter.Preview original=inspect(first,McpConfigImportAdapter.Format.CLAUDE_JSON,null,installed()),same=inspect(first,McpConfigImportAdapter.Format.CLAUDE_JSON,original,installed());
        assertEquals("same_source_version",same.view().getJSONObject("diff").getString("kind"));assertEquals(0,same.view().getJSONObject("diff").getJSONArray("changed").length());
        McpConfigImportAdapter.Preview changed=inspect(first.replace("first-hidden-value","second-hidden-value"),McpConfigImportAdapter.Format.CLAUDE_JSON,same,installed());
        assertEquals("source_changed",changed.view().getJSONObject("diff").getString("kind"));assertEquals(1,changed.view().getJSONObject("diff").getJSONArray("changed").length());assertNotEquals(original.view().getJSONObject("source").getString("digest"),changed.view().getJSONObject("source").getString("digest"));
        assertFalse(changed.view().toString().contains("first-hidden-value"));assertFalse(changed.view().toString().contains("second-hidden-value"));assertEquals("new_source",original.view().getJSONObject("diff").getString("kind"));
        JSONArray replaced=installed();replaced.getJSONObject(0).put("installationId","replacement-installation");assertTrue(inspect(first,McpConfigImportAdapter.Format.CLAUDE_JSON,original,replaced).view().getJSONObject("diff").getBoolean("installationChanged"));
    }
    @Test public void unknownRootAndServerFieldsAreExplicitlyRejectedWithoutEchoingNamesOrValues()throws Exception {
        String source="{\"unknown-sensitive-field-name\":\"root-secret-value\",\"mcpServers\":{\"remote\":{\"url\":\"https://fixture.invalid/mcp\",\"unrecognized-secret-field\":{\"nested\":\"server-secret-value\"}}}}";
        McpConfigImportAdapter.Preview preview=inspect(source,McpConfigImportAdapter.Format.CLAUDE_JSON,null,new JSONArray());String visible=preview.view().toString();assertEquals(1,preview.view().getJSONArray("unsupportedRootFields").length());assertEquals(1,row(preview,0).getJSONArray("unsupported").length());
        for(String secret:new String[]{"unknown-sensitive-field-name","root-secret-value","unrecognized-secret-field","server-secret-value"})assertFalse(visible.contains(secret));
        assertEquals("unsupported_fields",assertThrows(McpConfigImportAdapter.Rejected.class,()->adapter.discoveryInput(preview,0,preview.reviewDigest,"remote","Remote",McpData.VERSION)).code);
    }
    @Test public void userinfoQueryFragmentAndKnownCredentialReflectionNeverEnterPreviewEndpoints()throws Exception {
        for(String endpoint:new String[]{"https://user:fixture-password@example.invalid/mcp","https://example.invalid/mcp?token=fixture-query-secret","https://example.invalid/mcp#fixture-fragment"}){
            McpConfigImportAdapter.Preview preview=json(new JSONObject().put("remote",network(endpoint)));assertTrue(row(preview,0).getBoolean("endpointHidden"));assertFalse(preview.view().toString().contains(endpoint));assertFalse(row(preview,0).getBoolean("fieldsSupported"));
        }
        McpConfigImportAdapter.Preview reflected=json(new JSONObject().put("remote",network("https://fixture.invalid/fixture-bearer-token").put("headers",new JSONObject().put("Authorization","Bearer fixture-bearer-token"))));assertFalse(reflected.view().toString().contains("fixture-bearer-token"));
    }
    @Test public void codexTomlSubsetReadsActualTablesLiteralHashAndUnboundEnvironmentNames()throws Exception {
        String toml="[mcp_servers.local]\ncommand = 'npx'\nargs = [\"-y\", \"@safe/server\",]\n[mcp_servers.local.env]\nAPI_KEY = 'fixture#literal-secret' # a real comment\n[mcp_servers.remote]\nurl = \"https://fixture.invalid/mcp\"\nenabled = false\n[mcp_servers.remote.http_headers]\nAuthorization = \"Bearer fixture-codex-header\"\n";
        McpConfigImportAdapter.Preview preview=inspect(toml,McpConfigImportAdapter.Format.CODEX_TOML,null,new JSONArray());assertEquals(2,preview.view().getJSONArray("items").length());assertEquals("npx",row(preview,0).getString("command"));assertEquals(2,row(preview,0).getJSONArray("argv").length());assertEquals("API_KEY",row(preview,0).getJSONArray("credentialReferences").getJSONObject(0).getString("name"));assertFalse(row(preview,1).getBoolean("sourceEnabled"));
        assertFalse(preview.view().toString().contains("fixture#literal-secret"));assertFalse(preview.view().toString().contains("fixture-codex-header"));assertFalse(preview.view().toString().contains("@safe/server"));assertEquals(McpSkillSource.sha256(toml),preview.view().getJSONObject("source").getString("digest"));
    }
    @Test public void codexJsonExportReadsStructuredTransportWithoutExecutingTheCli()throws Exception {
        String export="[{\"name\":\"local\",\"enabled\":true,\"transport\":{\"type\":\"stdio\",\"command\":\"node\",\"env_vars\":[{\"name\":\"API_KEY\",\"value\":\"fixture-export-secret\"}]}},{\"name\":\"remote\",\"transport\":{\"type\":\"streamable_http\",\"url\":\"https://fixture.invalid/mcp\"}}]";
        McpConfigImportAdapter.Preview preview=inspect(export,McpConfigImportAdapter.Format.CODEX_LIST_JSON,null,new JSONArray());assertEquals(2,preview.view().getJSONArray("items").length());assertEquals("API_KEY",row(preview,0).getJSONArray("credentialReferences").getJSONObject(0).getString("name"));assertEquals("http",row(preview,1).getString("transport"));assertFalse(preview.view().toString().contains("fixture-export-secret"));
    }
    @Test public void duplicateAndComplexSpellingsFailClosedWithoutReturningInputInErrors()throws Exception {
        for(String source:new String[]{"{\"mcpServers\":{\"same\":{},\"same\":{}}}","{\"mcpServers\":{},\"mcpServers\":{}}","{\"mcpServers\":{}} trailing-secret"}){
            McpConfigImportAdapter.Rejected failure=assertThrows(McpConfigImportAdapter.Rejected.class,()->inspect(source,McpConfigImportAdapter.Format.CLAUDE_JSON,null,new JSONArray()));assertFalse(failure.toString().contains("trailing-secret"));assertNull(failure.getCause());
        }
        for(String source:new String[]{"[mcp_servers.same]\ncommand='node'\ncommand='bad'","[mcp_servers.same]\ncommand='node'\n[mcp_servers.same]\n", "[mcp_servers.same]\nargs = [\n'unsafe'\n]", "[[mcp_servers.same]]\ncommand='node'"})assertThrows(McpConfigImportAdapter.Rejected.class,()->inspect(source,McpConfigImportAdapter.Format.CODEX_TOML,null,new JSONArray()));
    }
    @Test public void byteDepthEntryArgumentAndPreviewBudgetsBoundTheActualReadAndParse()throws Exception {
        AtomicInteger read=new AtomicInteger();InputStream large=new InputStream(){@Override public int read(){read.incrementAndGet();return ' ';}@Override public int read(byte[] target,int offset,int length){java.util.Arrays.fill(target,offset,offset+length,(byte)' ');read.addAndGet(length);return length;}};
        assertEquals("source_size",assertThrows(McpConfigImportAdapter.Rejected.class,()->adapter.inspect(McpConfigImportAdapter.Source.selectedSaf(REF),McpConfigImportAdapter.Format.CLAUDE_JSON,large,new JSONArray(),null,new RunControl())).code);assertEquals(McpConfigImportAdapter.MAX_BYTES+1,read.get());
        String nested="{\"mcpServers\":{},\"x\":"+"[".repeat(12)+"0"+"]".repeat(12)+"}";assertEquals("configuration_complexity",assertThrows(McpConfigImportAdapter.Rejected.class,()->inspect(nested,McpConfigImportAdapter.Format.CLAUDE_JSON,null,new JSONArray())).code);
        JSONObject servers=new JSONObject();for(int i=0;i<33;i++)servers.put("server"+i,network("https://fixture.invalid/mcp"));assertEquals("server_count",assertThrows(McpConfigImportAdapter.Rejected.class,()->json(servers)).code);
        JSONArray args=new JSONArray();for(int i=0;i<65;i++)args.put("arg"+i);assertEquals("argument_count",assertThrows(McpConfigImportAdapter.Rejected.class,()->json(new JSONObject().put("local",new JSONObject().put("command","node").put("args",args)))).code);
        JSONObject many=new JSONObject(),env=new JSONObject();JSONArray values=new JSONArray();for(int i=0;i<64;i++){env.put("V"+i,"v");values.put("x");}for(int i=0;i<11;i++)many.put("local"+i,new JSONObject().put("command","node").put("args",values).put("env",env));assertEquals("preview_size",assertThrows(McpConfigImportAdapter.Rejected.class,()->json(many)).code);
    }
    @Test public void cancellationZeroReadAndPoisonedIoCannotEmitCredentialsOrCreateAPreview()throws Exception {
        RunControl cancelled=new RunControl();cancelled.cancel();AtomicInteger reads=new AtomicInteger();InputStream stream=new InputStream(){@Override public int read(){reads.incrementAndGet();return 0;}};
        assertThrows(InterruptedException.class,()->adapter.inspect(McpConfigImportAdapter.Source.selectedSaf(REF),McpConfigImportAdapter.Format.CLAUDE_JSON,stream,new JSONArray(),null,cancelled));assertEquals(0,reads.get());
        InputStream poisoned=new InputStream(){@Override public int read()throws IOException{throw new IOException("fixture-io-secret");}};McpConfigImportAdapter.Rejected failure=assertThrows(McpConfigImportAdapter.Rejected.class,()->adapter.inspect(McpConfigImportAdapter.Source.selectedSaf(REF),McpConfigImportAdapter.Format.CLAUDE_JSON,poisoned,new JSONArray(),null,new RunControl()));assertEquals("source_read_unavailable",failure.code);assertFalse(failure.toString().contains("fixture-io-secret"));assertNull(failure.getCause());
    }
    @Test public void malformedUtf8EscapedUnicodeAndUnsafeSourceReferencesAreRejected()throws Exception {
        assertEquals("invalid_utf8",assertThrows(McpConfigImportAdapter.Rejected.class,()->adapter.inspect(McpConfigImportAdapter.Source.selectedSaf(REF),McpConfigImportAdapter.Format.CLAUDE_JSON,new ByteArrayInputStream(new byte[]{(byte)0xff}),new JSONArray(),null,new RunControl())).code);
        String source="{\"mcpServers\":{\"local\":{\"command\":\"node\",\"args\":[\""+String.valueOf((char)92)+"ud800\"]}}}";assertEquals("invalid_unicode",assertThrows(McpConfigImportAdapter.Rejected.class,()->inspect(source,McpConfigImportAdapter.Format.CLAUDE_JSON,null,new JSONArray())).code);
        for(String reference:new String[]{"file:///private/config","https://fixture.invalid/config","content://provider/document?a=fixture-source-secret"}){McpConfigImportAdapter.Rejected failure=assertThrows(McpConfigImportAdapter.Rejected.class,()->McpConfigImportAdapter.Source.selectedSaf(reference));assertFalse(failure.toString().contains("fixture-source-secret"));}
    }
    @Test public void previewsAreOpaqueImmutableAndCannotBeReusedAcrossSourceOrNativeAdapter()throws Exception {
        String source="{\"mcpServers\":{\"remote\":{\"url\":\"https://fixture.invalid/mcp\"}}}";McpConfigImportAdapter.Preview preview=inspect(source,McpConfigImportAdapter.Format.CLAUDE_JSON,null,new JSONArray());JSONObject view=preview.view();view.getJSONArray("items").getJSONObject(0).put("endpoint","https://other.invalid");assertEquals("https://fixture.invalid/mcp",adapter.discoveryInput(preview,0,preview.reviewDigest,"remote","Remote",McpData.VERSION).getString("endpoint"));
        assertThrows(McpConfigImportAdapter.Rejected.class,()->new McpConfigImportAdapter().discoveryInput(preview,0,preview.reviewDigest,"remote","Remote",McpData.VERSION));
        assertThrows(McpConfigImportAdapter.Rejected.class,()->adapter.inspect(McpConfigImportAdapter.Source.selectedSaf("content://selected.documents/document/other"),McpConfigImportAdapter.Format.CLAUDE_JSON,new ByteArrayInputStream(source.getBytes(StandardCharsets.UTF_8)),new JSONArray(),preview,new RunControl()));
        assertFalse(preview.view().toString().contains(REF));assertTrue(preview.view().getJSONObject("source").getString("ref").startsWith("selected-document-sha256:"));
    }
    @Test public void aKnownSecretCannotBeReexposedAsAnEnvironmentReferenceName()throws Exception {
        String source="[mcp_servers.local]\ncommand='node'\n[mcp_servers.local.env]\nAPI_KEY='fixture_ref_secret'\n[mcp_servers.remote]\nurl='https://fixture.invalid/mcp'\n[mcp_servers.remote.env_http_headers]\nAuthorization='fixture_ref_secret'\n";
        McpConfigImportAdapter.Preview preview=inspect(source,McpConfigImportAdapter.Format.CODEX_TOML,null,new JSONArray());assertFalse(preview.view().toString().contains("fixture_ref_secret"));assertFalse(row(preview,1).getBoolean("fieldsSupported"));
    }
}
