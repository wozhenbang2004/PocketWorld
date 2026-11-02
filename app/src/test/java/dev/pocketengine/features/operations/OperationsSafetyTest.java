package dev.pocketengine.features.operations;

import dev.pocketengine.core.RunControl;
import java.net.URI;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class OperationsSafetyTest {
    private JSONObject manifest()throws Exception{return new JSONObject().put("format","pocketworld.private-update.v1").put("packageName","dev.pocketengine").put("versionCode",4).put("versionName","next").put("bytes",10).put("sha256","a".repeat(64)).put("downloadUrl","https://private.example/package.apk");}
    @Test public void diagnosticsExcludeBodiesSecretsAddressesAndKeepOnePreview()throws Exception{
        JSONObject mutable=new JSONObject().put("status","ready").put("apiKey","secret").put("goal","private goal").put("url","https://example/?token=secret").put("lastErrorCategory","token=secret").put("connected",true);
        var snapshot=MinimalDiagnostics.capture(Map.of("runtime",()->mutable,"failed",()->{throw new IllegalStateException("private goal 127.0.0.1 secret");}),123456);
        assertTrue(snapshot.text.contains("collection_failed"));assertTrue(snapshot.text.contains("connected"));assertFalse(snapshot.text.contains("secret"));assertFalse(snapshot.text.contains("private goal"));assertFalse(snapshot.text.contains("127.0.0.1"));String preview=snapshot.text;mutable.put("status","different");assertEquals(preview,snapshot.text);assertEquals(64,snapshot.sha256.length());
    }
    @Test public void privateUpdateCannotChangeOriginPackageVersionOrSigner()throws Exception{
        JSONObject manifest=UpdatePackagePolicy.manifest(manifest(),"dev.pocketengine",3,URI.create("https://private.example/latest.json"));
        assertEquals("https://private.example:443/package.apk",UpdatePackagePolicy.manifest(manifest().put("downloadUrl","https://private.example:443/package.apk"),"dev.pocketengine",3,URI.create("https://private.example/latest.json")).getString("downloadUrl"));
        UpdatePackagePolicy.packageIdentity(manifest,"dev.pocketengine",3,"dev.pocketengine",4,"next",Set.of("same"),Set.of("same"));
        for(String wrong:new String[]{"https://private.example/latest?token=secret","http://private.example/latest","https://user:secret@private.example/latest"})try{UpdatePackagePolicy.source(wrong);fail(wrong);}catch(IllegalArgumentException expected){}
        try{UpdatePackagePolicy.manifest(manifest().put("downloadUrl","https://other.example/package.apk"),"dev.pocketengine",3,URI.create("https://private.example/latest.json"));fail("cross origin");}catch(SecurityException expected){}
        try{UpdatePackagePolicy.packageIdentity(manifest,"dev.pocketengine",4,"dev.pocketengine",4,"next",Set.of("same"),Set.of("same"));fail("not newer");}catch(SecurityException expected){}
        try{UpdatePackagePolicy.packageIdentity(manifest,"dev.pocketengine",3,"another.package",4,"next",Set.of("same"),Set.of("same"));fail("wrong package");}catch(SecurityException expected){}
        try{UpdatePackagePolicy.packageIdentity(manifest,"dev.pocketengine",3,"dev.pocketengine",4,"next",Set.of("same"),Set.of("different"));fail("wrong signer");}catch(SecurityException expected){}
    }
    @Test public void cancelledProbeStillClosesAndOtherUnavailableChecksRemainPartial()throws Exception{
        AtomicBoolean released=new AtomicBoolean();RunControl control=new RunControl();
        var first=new CompatibilityCheckRunner.Check("cancel",100,new CompatibilityCheckRunner.Probe(){public JSONObject run(RunControl original)throws Exception{original.cancel();return new JSONObject().put("status","partial");}public void close(){released.set(true);}});
        var skipped=new CompatibilityCheckRunner.Check("next",100,original->{throw new AssertionError("cancelled checks must not start");});
        JSONObject result=CompatibilityCheckRunner.run(List.of(first,skipped),control,snapshot->{});assertTrue(released.get());assertTrue(result.getBoolean("resourcesReleased"));assertEquals("partial",result.getString("status"));assertEquals("skipped",result.getJSONArray("checks").getJSONObject(1).getString("status"));
    }
    @Test public void generatedBuildIdentityIsBoundedAndDirtyBuildCannotClaimItsHead()throws Exception{
        String commit="a".repeat(40),tree="b".repeat(40);JSONObject actual=new JSONObject().put("commit",commit).put("tree",tree).put("dirty",false);
        JSONObject facts=OperationsEvidence.buildIdentity(new java.io.ByteArrayInputStream(actual.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));assertEquals(commit,facts.getString("sourceCommit"));assertFalse(facts.getBoolean("sourceDirty"));
        var snapshot=MinimalDiagnostics.capture(Map.of("app",()->facts),123456);assertTrue(snapshot.text.contains(commit));assertTrue(snapshot.text.contains(tree));
        actual.put("dirty",true);assertEquals("unavailable",OperationsEvidence.buildIdentity(new java.io.ByteArrayInputStream(actual.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8))).getString("sourceCommit"));
        assertThrows(SecurityException.class,()->OperationsEvidence.buildIdentity(new java.io.ByteArrayInputStream(new byte[4097])));
    }
    @Test public void originalPhonePracticeReadKeepsHistoricalScopeAndUnknownChecksWithoutBodies()throws Exception{
        JSONObject nativeResult=new JSONObject().put("ok",true).put("resultScope","last_completed_original_phone_run").put("evidenceOrigin","original_run_native_observations")
            .put("checks",new org.json.JSONArray().put(new JSONObject().put("id","click").put("state","passed").put("body","PRIVATE-FIXTURE-CONTENT"))).put("goal","PRIVATE-FIXTURE-GOAL");
        JSONObject result=OperationsEvidence.phonePractice(new JSONObject().put("backgroundConnected",true),nativeResult);assertEquals("partial",result.getString("status"));assertEquals("last_completed_original_phone_run",result.getString("resultScope"));assertFalse(result.getBoolean("currentPracticeVerified"));assertFalse(result.getBoolean("phoneActionsVerified"));assertFalse(result.toString().contains("PRIVATE-FIXTURE"));assertEquals(12,result.getJSONArray("checks").length());
        assertEquals("passed",result.getJSONArray("checks").getJSONObject(4).getString("state"));assertEquals("unknown",result.getJSONArray("checks").getJSONObject(0).getString("state"));
        nativeResult.put("resultScope","another_task_model_claim");result=OperationsEvidence.phonePractice(new JSONObject(),nativeResult);for(int i=0;i<12;i++)assertEquals("unknown",result.getJSONArray("checks").getJSONObject(i).getString("state"));
    }
}
