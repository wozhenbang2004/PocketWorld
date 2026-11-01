package dev.pocketengine.features.plugins.distribution;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.EngineModule;
import dev.pocketengine.features.plugins.PluginRevisions;
import dev.pocketengine.features.plugins.PluginRules;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Isolated stream/CAS fixtures. Real PluginsModule integration is a separate Robolectric test. */
public final class PluginPackagesTest {
    private JSONObject manifest(String version,String endpoint)throws Exception{return PluginRules.manifest(new JSONObject().put("id","lookup").put("name","查询工具😀").put("version",version).put("tools",new JSONArray().put(new JSONObject().put("name","find").put("kind","http").put("effect","read").put("endpoint",endpoint).put("description","核对原始资料"))));}
    private JSONObject source()throws Exception{return new JSONObject().put("label","主人选择的声明文件").put("url","https://example.org/package");}
    private ByteArrayInputStream stream(byte[] bytes){return new ByteArrayInputStream(bytes);}
    private byte[] pkg(JSONObject manifest)throws Exception{return PackageJson.canonical(new JSONObject().put("format",PluginPackages.FORMAT).put("manifest",manifest).put("manifestSha256",PluginPackages.declarationDigest(manifest)).put("source",source())).getBytes(StandardCharsets.UTF_8);}
    private static final class Module implements EngineModule {
        JSONObject plugin,lastInstall;
        int installs,previews;
        boolean throwAfterCommit,badReceipt;
        Runnable onPreview;
        @Override public String id(){return "plugins";}
        @Override public synchronized JSONObject snapshot()throws Exception{return new JSONObject().put("plugins",plugin==null?new JSONArray():new JSONArray().put(new JSONObject(plugin.toString())));}
        @Override public synchronized JSONObject handle(String command,JSONObject input)throws Exception {
            JSONObject declaration=PluginRules.manifest(input.getJSONObject("manifest"));if(command.equals("previewInstall")){previews++;if(onPreview!=null)onPreview.run();return PluginRevisions.preview(plugin,declaration);}
            if(!command.equals("install"))throw new AssertionError("unexpected command: "+command);
            String expected=plugin==null?"":PluginRevisions.installationId(plugin);if(!expected.equals(input.getString("expectedInstallationId")))throw new IllegalStateException("stale installation");
            installs++;lastInstall=new JSONObject(input.toString());plugin=new JSONObject().put("id",declaration.getString("id")).put("manifest",declaration).put("installationId",UUID.randomUUID().toString()).put("enabled",false).put("grants",new JSONArray()).put("hasToken",false);
            if(throwAfterCommit)throw new IOException("after local commit");JSONObject receipt=new JSONObject(plugin.toString());if(badReceipt)receipt.put("enabled",true);return receipt;
        }
    }
    @Test public void realUtf8StreamRoundTripContainsOnlyDeclarationsAndImportsDisabledWithNewIdentity()throws Exception {
        Module module=new Module();module.plugin=new JSONObject().put("id","lookup").put("manifest",manifest("1","https://old.example/tool")).put("installationId","old-install")
                .put("enabled",true).put("grants",new JSONArray().put("tool:find")).put("credentialRef","private-slot").put("token","PRIVATE_TOKEN").put("hasToken",true).put("history",new JSONArray());
        PluginPackages packages=new PluginPackages(module);ByteArrayOutputStream bytes=new ByteArrayOutputStream();JSONObject exported=packages.exportPackage("lookup","old-install",source(),bytes,new RunControl());String text=bytes.toString(StandardCharsets.UTF_8);
        for(String forbidden:new String[]{"private-slot","PRIVATE_TOKEN","credentialRef","enabled","grants","installationId","history"})assertFalse(forbidden,text.contains(forbidden));assertEquals(PluginPackages.hash(bytes.toByteArray()),exported.getString("packageDigest"));
        PluginPackages.Inspection inspection=packages.inspect(stream(bytes.toByteArray()),new RunControl());assertEquals("old-install",inspection.review().getJSONObject("preview").getString("expectedInstallationId"));
        JSONObject result=packages.importReviewed(stream(bytes.toByteArray()),inspection,inspection.reviewDigest,new RunControl());assertEquals("installed_disabled",result.getString("status"));assertFalse(result.getJSONObject("plugin").getBoolean("enabled"));assertEquals(0,result.getJSONObject("plugin").getJSONArray("grants").length());assertNotEquals("old-install",result.getJSONObject("plugin").getString("installationId"));
        assertEquals(2,module.lastInstall.length());assertFalse(module.lastInstall.has("token"));assertEquals(1,module.installs);
        assertThrows(IllegalStateException.class,()->packages.importReviewed(stream(bytes.toByteArray()),inspection,inspection.reviewDigest,new RunControl()));assertEquals(1,module.installs);
    }
    @Test public void sameVersionChangedEndpointEffectAndSchemaProduceRealDigestAndDiff()throws Exception {
        Module module=new Module();module.plugin=new JSONObject().put("id","lookup").put("installationId","installed-1").put("manifest",manifest("same","https://old.example/tool"));
        JSONObject next=manifest("same","https://new.example/tool");next.getJSONArray("tools").getJSONObject(0).put("effect","write").put("schema",new JSONObject().put("type","object").put("required",new JSONArray().put("query")));
        PluginPackages packages=new PluginPackages(module);PluginPackages.Inspection review=packages.inspect(stream(pkg(next)),new RunControl());JSONObject diff=review.review().getJSONObject("preview");assertEquals(diff.getString("currentVersion"),diff.getString("targetVersion"));assertFalse(diff.getBoolean("sameManifest"));assertEquals(3,diff.getJSONArray("changedTools").getJSONObject(0).getJSONArray("fields").length());
        assertNotEquals(PluginPackages.declarationDigest(module.plugin.getJSONObject("manifest")),review.declarationDigest);assertEquals("not_present",review.review().getString("signature"));assertTrue(review.review().getBoolean("untrusted"));
    }
    @Test public void inspectionObjectsAndSourceMutationCannotChangeReviewedContent()throws Exception {
        Module module=new Module();PluginPackages packages=new PluginPackages(module);byte[] bytes=pkg(manifest("1","https://example.org/tool"));PluginPackages.Inspection reviewed=packages.inspect(stream(bytes),new RunControl());
        reviewed.review().getJSONObject("preview").getJSONObject("manifest").getJSONArray("tools").getJSONObject(0).put("endpoint","https://evil.example/tool");
        packages.importReviewed(stream(bytes),reviewed,reviewed.reviewDigest,new RunControl());assertEquals("https://example.org/tool",module.lastInstall.getJSONObject("manifest").getJSONArray("tools").getJSONObject(0).getString("endpoint"));
    }
    @Test public void fileReplacementWhitespaceChangesAndUnapprovedDigestCannotInstall()throws Exception {
        Module module=new Module();PluginPackages packages=new PluginPackages(module);byte[] bytes=pkg(manifest("1","https://example.org/tool"));PluginPackages.Inspection reviewed=packages.inspect(stream(bytes),new RunControl());
        assertThrows(SecurityException.class,()->packages.importReviewed(stream(bytes),reviewed,"a".repeat(64),new RunControl()));
        assertThrows(SecurityException.class,()->packages.importReviewed(stream((new String(bytes,StandardCharsets.UTF_8)+" ").getBytes(StandardCharsets.UTF_8)),reviewed,reviewed.reviewDigest,new RunControl()));
        assertThrows(SecurityException.class,()->packages.importReviewed(stream(pkg(manifest("1","https://changed.example/tool"))),reviewed,reviewed.reviewDigest,new RunControl()));assertEquals(0,module.installs);
    }
    @Test public void initialAbsenceAndReplacementInstallationAreBothCasFenced()throws Exception {
        Module module=new Module();PluginPackages packages=new PluginPackages(module);byte[] bytes=pkg(manifest("1","https://example.org/tool"));PluginPackages.Inspection absent=packages.inspect(stream(bytes),new RunControl());
        module.plugin=new JSONObject().put("id","lookup").put("installationId","newer").put("manifest",manifest("1","https://example.org/tool"));assertThrows(IllegalStateException.class,()->packages.importReviewed(stream(bytes),absent,absent.reviewDigest,new RunControl()));
        PluginPackages.Inspection installed=packages.inspect(stream(bytes),new RunControl());module.plugin.put("installationId","replacement");assertThrows(IllegalStateException.class,()->packages.importReviewed(stream(bytes),installed,installed.reviewDigest,new RunControl()));assertEquals(0,module.installs);
    }
    @Test public void laterOwnerStateInvalidatesInspectionEvenWhenPermissionValuesReturnToTheSameState()throws Exception {
        Module module=new Module();module.plugin=new JSONObject().put("id","lookup").put("installationId","same-install")
                .put("stateRevision",4).put("manifest",manifest("1","https://example.org/tool")).put("enabled",false).put("grants",new JSONArray());
        PluginPackages packages=new PluginPackages(module);byte[] bytes=pkg(manifest("2","https://example.org/tool"));
        PluginPackages.Inspection reviewed=packages.inspect(stream(bytes),new RunControl());
        module.plugin.put("stateRevision",6);
        assertThrows(IllegalStateException.class,()->packages.validateInspection(reviewed,new RunControl()));
        assertThrows(IllegalStateException.class,()->packages.importReviewed(stream(bytes),reviewed,reviewed.reviewDigest,new RunControl()));
        assertEquals(0,module.installs);assertEquals("same-install",module.plugin.getString("installationId"));assertEquals(6,module.plugin.getLong("stateRevision"));
    }
    @Test public void digestTamperingUnknownVersionAndPermissionOrCodePayloadsFailBeforeInstall()throws Exception {
        Module module=new Module();PluginPackages packages=new PluginPackages(module);JSONObject raw=new JSONObject(new String(pkg(manifest("1","https://example.org/tool")),StandardCharsets.UTF_8));
        JSONObject wrong=new JSONObject(raw.toString()).put("manifestSha256","b".repeat(64));assertThrows(SecurityException.class,()->packages.inspect(stream(wrong.toString().getBytes(StandardCharsets.UTF_8)),new RunControl()));
        for(String field:new String[]{"token","credentialRef","grants","enabled","files","path","signature","script"}){JSONObject injected=new JSONObject(raw.toString()).put(field,field.equals("path")?"../../outside":"secret-or-payload");assertThrows(IllegalArgumentException.class,()->packages.inspect(stream(injected.toString().getBytes(StandardCharsets.UTF_8)),new RunControl()));}
        assertThrows(IllegalArgumentException.class,()->packages.inspect(stream(raw.put("format","pocketworld.plugin-declaration.v2").toString().getBytes(StandardCharsets.UTF_8)),new RunControl()));assertEquals(0,module.installs);
    }
    @Test public void unknownManifestToolFieldsAndUnsupportedExecutableKindsAreRejected()throws Exception {
        Module module=new Module();PluginPackages packages=new PluginPackages(module);
        JSONObject bad=manifest("1","https://example.org/tool");bad.put("token","secret");assertThrows(IllegalArgumentException.class,()->packages.inspect(stream(pkg(bad)),new RunControl()));
        for(String kind:new String[]{"script","command","javascript","shell"}){JSONObject declaration=manifest("1","https://example.org/tool");declaration.getJSONArray("tools").getJSONObject(0).put("kind",kind);assertThrows(IllegalArgumentException.class,()->packages.inspect(stream(pkg(declaration)),new RunControl()));}
        assertEquals(0,module.installs);
    }
    @Test public void mcpPackageOnlyReturnsFreshDiscoveryHintsWithoutCallingInstallOrPreviewInstall()throws Exception {
        Module module=new Module();JSONObject manifest=manifest("mcp-old","https://server.example/mcp");manifest.getJSONArray("tools").getJSONObject(0).put("kind","mcp").put("effect","write").put("protocolVersion","2026-07-28").put("descriptorDigest","a".repeat(64)).put("serverDigest","b".repeat(64));
        PluginPackages packages=new PluginPackages(module);byte[] bytes=pkg(PluginRules.manifest(manifest));PluginPackages.Inspection review=packages.inspect(stream(bytes),new RunControl());assertTrue(review.review().getBoolean("requiresMcpRediscovery"));assertEquals(0,module.previews);
        JSONObject result=packages.importReviewed(stream(bytes),review,review.reviewDigest,new RunControl());assertEquals("rediscovery_required",result.getString("status"));assertFalse(result.getBoolean("installed"));assertEquals("https://server.example/mcp",result.getJSONArray("discovery").getJSONObject(0).getString("endpoint"));assertTrue(result.getBoolean("requiresNewApproval"));assertEquals(0,module.installs);assertNull(module.plugin);
    }
    @Test public void outputFailureAndCancelledTransferPreserveCurrentInstallation()throws Exception {
        Module module=new Module();module.plugin=new JSONObject().put("id","lookup").put("manifest",manifest("1","https://example.org/tool")).put("installationId","current");PluginPackages packages=new PluginPackages(module);
        assertThrows(IOException.class,()->packages.exportPackage("lookup","current",source(),new java.io.OutputStream(){public void write(int value)throws IOException{throw new IOException("provider failed");}},new RunControl()));assertEquals("current",module.plugin.getString("installationId"));
        RunControl stopped=new RunControl();stopped.cancel();assertThrows(InterruptedException.class,()->packages.inspect(stream(pkg(manifest("1","https://example.org/tool"))),stopped));assertThrows(InterruptedException.class,()->packages.exportPackage("lookup","current",source(),new ByteArrayOutputStream(),stopped));assertEquals(0,module.installs);
    }
    @Test public void interruptedStreamAndOversizedInputNeverProduceAnInspection()throws Exception {
        Module module=new Module();PluginPackages packages=new PluginPackages(module);assertThrows(IOException.class,()->packages.inspect(new InputStream(){public int read()throws IOException{throw new IOException("read failure");}},new RunControl()));
        byte[] big=new byte[PluginPackages.MAX_BYTES+1];Arrays.fill(big,(byte)' ');assertThrows(IllegalArgumentException.class,()->packages.inspect(stream(big),new RunControl()));assertEquals(0,module.previews);
    }
    @Test public void malformedUtf8DuplicateKeysTrailingDataAndJsonExtensionsAreRejected()throws Exception {
        Module module=new Module();PluginPackages packages=new PluginPackages(module);assertThrows(java.nio.charset.CharacterCodingException.class,()->packages.inspect(stream(new byte[]{(byte)0xc0,(byte)0xaf}),new RunControl()));
        for(String invalid:new String[]{"{\"format\":\"x\",\"format\":\"y\"}","{\"x\":1,}","{'x':1}","{x:1}","{\"x\":NaN}","{\"x\":01}","{\"x\":1} {}","{\"x\":\"\\ud800\"}","{\"x\":\"\\u0000\"}","{\"x\":1e99999}"})assertThrows(IllegalArgumentException.class,()->packages.inspect(stream(invalid.getBytes(StandardCharsets.UTF_8)),new RunControl()));
        assertEquals(0,module.installs);
    }
    @Test public void canonicalNumbersRetainLongPrecisionAndRejectLossyDecimals()throws Exception {
        JSONObject left=PackageJson.parse("{\"n\":9007199254740993,\"m\":1.00,\"s\":\"</script>😀\"}"),right=PackageJson.parse("{\"s\":\"<\\/script>😀\",\"m\":1,\"n\":9007199254740993}");
        assertEquals(PackageJson.canonical(left),PackageJson.canonical(right));assertTrue(PackageJson.canonical(left).contains("9007199254740993"));assertThrows(IllegalArgumentException.class,()->PackageJson.parse("{\"n\":0.10000000000000000001}"));
    }
    @Test public void badOrLostInstallReceiptIsUnknownAndCannotUseTheOldInspectionForReplay()throws Exception {
        for(boolean lost:new boolean[]{false,true}){Module module=new Module();module.throwAfterCommit=lost;module.badReceipt=!lost;PluginPackages packages=new PluginPackages(module);byte[] bytes=pkg(manifest("1","https://example.org/tool"));PluginPackages.Inspection review=packages.inspect(stream(bytes),new RunControl());
            JSONObject result=packages.importReviewed(stream(bytes),review,review.reviewDigest,new RunControl());assertEquals("unknown",result.getString("status"));assertTrue(result.isNull("installed"));assertFalse(result.getBoolean("replayAllowed"));assertEquals(1,module.installs);
            assertThrows(IllegalStateException.class,()->packages.importReviewed(stream(bytes),review,review.reviewDigest,new RunControl()));assertEquals(1,module.installs);}
    }
    @Test public void staleExportAndUntrustedSourceUrlsNeverFetchOrWrite()throws Exception {
        Module module=new Module();module.plugin=new JSONObject().put("id","lookup").put("installationId","current").put("manifest",manifest("1","https://example.org/tool"));PluginPackages packages=new PluginPackages(module);ByteArrayOutputStream output=new ByteArrayOutputStream();
        assertThrows(IllegalStateException.class,()->packages.exportPackage("lookup","old",source(),output,new RunControl()));assertEquals(0,output.size());
        for(String url:new String[]{"file:///secret","https://user:password@example.org/p","https://example.org/p?token=secret","http://127.0.0.1/p"})assertThrows(Exception.class,()->packages.exportPackage("lookup","current",source().put("url",url),output,new RunControl()));assertEquals(0,output.size());
    }
    @Test public void nativeFreshnessCheckDoesNotInstallAndRejectsChangedIdentity()throws Exception {
        Module module=new Module();PluginPackages packages=new PluginPackages(module);byte[] bytes=pkg(manifest("1","https://example.org/tool"));PluginPackages.Inspection review=packages.inspect(stream(bytes),new RunControl());assertEquals(review.reviewDigest,packages.validateInspection(review,new RunControl()));assertEquals(0,module.installs);
        module.plugin=new JSONObject().put("id","lookup").put("installationId","intervening-install").put("manifest",manifest("1","https://example.org/tool"));assertThrows(IllegalStateException.class,()->packages.validateInspection(review,new RunControl()));assertEquals(0,module.installs);
    }
    @Test public void cancelDuringFinalPreviewCannotStartInstallation()throws Exception {
        Module module=new Module();PluginPackages packages=new PluginPackages(module);byte[] bytes=pkg(manifest("1","https://example.org/tool"));PluginPackages.Inspection review=packages.inspect(stream(bytes),new RunControl());RunControl control=new RunControl();module.onPreview=control::cancel;
        assertThrows(InterruptedException.class,()->packages.importReviewed(stream(bytes),review,review.reviewDigest,control));assertEquals(0,module.installs);
    }
}
