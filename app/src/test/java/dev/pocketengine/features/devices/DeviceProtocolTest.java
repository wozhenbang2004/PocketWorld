package dev.pocketengine.features.devices;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class DeviceProtocolTest {
    private JSONObject fixtures()throws Exception{
        String explicit=System.getProperty("pocketworld.protocol.fixtures");if(explicit!=null)return new JSONObject(new String(Files.readAllBytes(Path.of(explicit)),StandardCharsets.UTF_8));
        Path at=Path.of("").toAbsolutePath();for(int depth=0;at!=null&&depth<5;depth++,at=at.getParent()){Path file=at.resolve("protocol/device-v1/fixtures.json");if(Files.isRegularFile(file))return new JSONObject(new String(Files.readAllBytes(file),StandardCharsets.UTF_8));}throw new IllegalStateException("Shared protocol fixture asset not found");
    }
    @Test public void javaAndPythonUseTheSameSharedFixtures()throws Exception{
        JSONArray rows=fixtures().getJSONArray("fixtures");assertEquals(55,rows.length());for(int i=0;i<rows.length();i++){
            JSONObject row=rows.getJSONObject(i);try{
                if("message".equals(row.getString("kind")))DeviceProtocol.validateMessage(row.getJSONObject("message"));
                else{byte[] raw=("raw".equals(row.getString("kind"))?row.getString("raw"):row.getJSONObject("message").toString()).getBytes(StandardCharsets.UTF_8);DeviceTaskEnvelope value=DeviceProtocol.parseRequest(raw);if(row.getBoolean("valid")){assertEquals(row.getString("name"),row.getString("canonical"),DeviceProtocol.canonical(value.json()));assertEquals(row.getString("name"),row.getString("argumentsDigest"),DeviceProtocol.argumentsDigest(value.json()));if(row.has("receiverTaskId"))assertEquals(row.getString("receiverTaskId"),DeviceProtocol.receiverTaskId(value));}}
                assertTrue(row.getString("name"),row.getBoolean("valid"));
            }catch(DeviceProtocol.ProtocolException rejected){assertFalse(row.getString("name"),row.getBoolean("valid"));assertEquals(row.getString("name"),row.getString("error"),rejected.code);}
        }
    }
    @Test public void correlationDoesNotChangeOperationButAudienceDoes()throws Exception{
        JSONObject first=fixtures().getJSONArray("fixtures").getJSONObject(0).getJSONObject("message"),retry=new JSONObject(first.toString());retry.put("requestId","request-retry").put("traceId","trace-retry");assertEquals(DeviceProtocol.argumentsDigest(first),DeviceProtocol.argumentsDigest(retry));retry.put("targetDeviceId","pw1_"+"c".repeat(64));assertNotEquals(DeviceProtocol.argumentsDigest(first),DeviceProtocol.argumentsDigest(retry));
    }
    @Test public void canonicalOrderingIsUnicodeCodepointsNotUtf16()throws Exception{
        assertEquals("{\"a\":\"\\n/\",\"\ue000\":1,\"😀\":2}",DeviceProtocol.canonical(new JSONObject().put("😀",2).put("\ue000",1).put("a","\n/")));
    }
    @Test public void overLimitAndDeepInputCannotReachExecution(){
        try{DeviceProtocol.parseRequest(new byte[65537]);fail();}catch(DeviceProtocol.ProtocolException rejected){assertEquals("limit_exceeded",rejected.code);}
        try{DeviceProtocol.parseRequest(("{\"a\":".repeat(18)+"0"+"}".repeat(18)).getBytes(StandardCharsets.UTF_8));fail();}catch(DeviceProtocol.ProtocolException rejected){assertEquals("limit_exceeded",rejected.code);}
    }
    @Test public void wireObjectCannotMutateTheValidatedEnvelope()throws Exception{
        DeviceTaskEnvelope value=DeviceProtocol.parseRequest(fixtures().getJSONArray("fixtures").getJSONObject(0).getJSONObject("message").toString().getBytes(StandardCharsets.UTF_8));value.arguments().put("effect","read");assertTrue(value.sideEffecting());
    }
    @Test public void sharedStableReceiverAndAllocationIdentities()throws Exception{
        JSONObject data=fixtures();JSONArray receiver=data.getJSONArray("receiverTaskIds");for(int i=0;i<receiver.length();i++){JSONObject row=receiver.getJSONObject(i);assertEquals(row.getString("name"),row.getString("expected"),DeviceProtocol.receiverTaskId(row.getJSONObject("input")));}assertEquals(receiver.getJSONObject(0).getString("expected"),receiver.getJSONObject(1).getString("expected"));assertNotEquals(receiver.getJSONObject(2).getString("expected"),receiver.getJSONObject(3).getString("expected"));JSONArray allocations=data.getJSONArray("allocationIds");for(int i=0;i<allocations.length();i++){JSONObject row=allocations.getJSONObject(i),value=row.getJSONObject("input");assertEquals(row.getString("name"),row.getString("expected"),DeviceProtocol.allocationId(value.getString("parentTaskId"),value.getString("targetDeviceId"),value.getJSONObject("resourceBinding"),value.getString("kind"),value.isNull("parentOperationId")?null:value.getString("parentOperationId")));}
    }
    @Test public void exactResourcesAndBoundedIntegersShareVectors()throws Exception{
        JSONArray resources=fixtures().getJSONArray("resourceEquality");for(int i=0;i<resources.length();i++){JSONObject row=resources.getJSONObject(i);assertEquals(row.getString("name"),row.getBoolean("equal"),DeviceProtocol.resourceEquals(row.getJSONObject("left"),row.getJSONObject("right")));}
        JSONArray numbers=fixtures().getJSONArray("integerTokens");for(int i=0;i<numbers.length();i++){JSONObject row=numbers.getJSONObject(i);try{JSONObject value=DeviceProtocol.parseObject(row.getString("raw").getBytes(StandardCharsets.UTF_8));assertTrue(row.getString("name"),row.getBoolean("valid"));assertEquals(row.getLong("expected"),value.getLong("n"));}catch(DeviceProtocol.ProtocolException error){assertFalse(row.getString("name"),row.getBoolean("valid"));assertEquals(row.getString("error"),error.code);}}
    }
    @Test public void executionResourcesAndInvitationRoutingHintsShareVectors()throws Exception{
        for(String group:new String[]{"executionResources","connectionHints"}){JSONArray rows=fixtures().getJSONArray(group);for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);try{if("executionResources".equals(group))DeviceProtocol.validateResourceBinding(row.getString("capabilityVersion"),row.getJSONObject("binding"));else DeviceProtocol.validateConnectionHint(row.getJSONObject("hint"));assertTrue(row.getString("name"),row.getBoolean("valid"));}catch(DeviceProtocol.ProtocolException error){assertFalse(row.getString("name"),row.getBoolean("valid"));assertEquals(row.optString("error","invalid_request"),error.code);}}}
    }
    @Test public void nonOverlappingSourceSharesAndStableIdsShareVectors()throws Exception{
        JSONArray identities=fixtures().getJSONArray("sourceShareIds");for(int i=0;i<identities.length();i++){JSONObject row=identities.getJSONObject(i),v=row.getJSONObject("input");assertEquals(row.getString("expected"),DelegationGrant.shareId(v.getString("issuerDeviceId"),v.getString("sourceAllocationId"),v.getString("targetDeviceId"),v.getJSONObject("resourceBinding")));}
        JSONArray rows=fixtures().getJSONArray("sourceShares");for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);try{DelegationGrant.validateWireProof(row.getJSONObject("proof"));assertTrue(row.getString("name"),row.getBoolean("valid"));}catch(DeviceProtocol.ProtocolException error){assertFalse(row.getString("name"),row.getBoolean("valid"));assertEquals(row.getString("error"),error.code);}}
    }
    @Test public void exactP256AndCanonicalDerSignaturesShareVectors()throws Exception{
        JSONArray rows=fixtures().getJSONArray("deviceSignatureAlgorithms");for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);try{DeviceIdentity.verifySignature(DeviceIdentity.certificate(java.util.Base64.getDecoder().decode(row.getString("certificateDer"))),row.getString("message").getBytes(StandardCharsets.UTF_8),java.util.Base64.getDecoder().decode(row.getString("signature")));assertTrue(row.getString("name"),row.getBoolean("valid"));}catch(SecurityException|java.security.GeneralSecurityException invalid){assertFalse(row.getString("name"),row.getBoolean("valid"));}}
    }
    @Test public void operationManagementHasANewControlIdWithoutToolBudget()throws Exception{JSONArray rows=fixtures().getJSONArray("operationManagementRequests");for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);try{DeviceProtocol.parseRequest(DeviceProtocol.canonicalBytes(row.getJSONObject("message")));assertTrue(row.getString("name"),row.getBoolean("valid"));}catch(DeviceProtocol.ProtocolException error){assertFalse(row.getString("name"),row.getBoolean("valid"));assertEquals(row.getString("error"),error.code);}}}
    @Test public void typedMethodResultsNeverInventTargetRuns()throws Exception{JSONArray rows=fixtures().getJSONArray("typedMethodResults");for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);try{DeviceProtocol.validateMessage(row.getJSONObject("message"));assertTrue(row.getString("name"),row.getBoolean("valid"));}catch(DeviceProtocol.ProtocolException error){assertFalse(row.getString("name"),row.getBoolean("valid"));assertEquals(row.getString("error"),error.code);}}}
    @Test public void authoritativeProfileIsOneCanonicalMachineIdentity()throws Exception{JSONObject manifest=new JSONObject(fixtures().getJSONObject("profileManifest").toString());String profile=manifest.getString("profileId");manifest.remove("profileId");assertEquals(DeviceProtocol.PROFILE,profile);assertEquals(profile,"pwp1_"+DeviceProtocol.sha256(DeviceProtocol.canonicalBytes(manifest)));JSONArray rows=fixtures().getJSONArray("profileRequests");for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);try{DeviceProtocol.parseRequest(DeviceProtocol.canonicalBytes(row.getJSONObject("message")));assertTrue(row.getString("name"),row.getBoolean("valid"));}catch(DeviceProtocol.ProtocolException error){assertFalse(row.getString("name"),row.getBoolean("valid"));assertEquals(row.getString("error"),error.code);}}}
    @Test public void exactTargetResourcesDoNotFormACartesianProduct()throws Exception{JSONArray rows=fixtures().getJSONArray("targetResourceScopes");for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);try{JSONObject scope=row.getJSONObject("scope");DelegationGrant.validateForwardingScope(scope);assertTrue(row.getString("name"),row.getBoolean("valid"));JSONArray checks=row.getJSONArray("checks");for(int j=0;j<checks.length();j++){JSONObject check=checks.getJSONObject(j);assertEquals(check.getBoolean("allowed"),DelegationGrant.targetResourceContains(scope.getJSONArray("targetResources"),check.getString("targetDeviceId"),check.getJSONObject("resourceBinding")));}}catch(SecurityException|DeviceProtocol.ProtocolException invalid){assertFalse(row.getString("name"),row.getBoolean("valid"));}}}
    @Test public void artifactNamesUseNative160ScalarControlAndPathPolicy()throws Exception{JSONArray rows=fixtures().getJSONArray("artifactNames");for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);try{DeviceProtocol.validateMessage(row.getJSONObject("message"));assertTrue(row.getString("name"),row.getBoolean("valid"));}catch(DeviceProtocol.ProtocolException invalid){assertFalse(row.getString("name"),row.getBoolean("valid"));assertEquals(row.getString("error"),invalid.code);}}}
    @Test public void sharedFinalizeCodecSeparatesLiveCommitAndReadOnlyRecovery()throws Exception{
        JSONArray rows=fixtures().getJSONArray("pairingFinalizeRequests");for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);try{PairingCoordinator.parsePairingFinalize(DeviceProtocol.canonicalBytes(row.getJSONObject("message")));assertTrue(row.getString("name"),row.getBoolean("valid"));}catch(DeviceProtocol.ProtocolException error){assertFalse(row.getString("name"),row.getBoolean("valid"));assertEquals(row.getString("error"),error.code);}}
    }
    @Test public void sharedImmutablePairingCommitProofsBindOriginalTransactionAndScope()throws Exception{
        JSONArray rows=fixtures().getJSONArray("pairingCommits");for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);try{PairingCoordinator.verifyCommittedCompletion(java.util.Base64.getDecoder().decode(row.getString("certificateDer")),row.getJSONObject("completion"),row.getJSONObject("transcript"),row.getLong("nowMs"));assertTrue(row.getString("name"),row.getBoolean("valid"));}catch(SecurityException|java.security.GeneralSecurityException|DeviceProtocol.ProtocolException invalid){assertFalse(row.getString("name"),row.getBoolean("valid"));}}
    }

}
