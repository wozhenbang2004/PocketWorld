package dev.pocketengine.features.devices;

import java.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;

/** Human reading a receipt already on this device. No TLS client, outgoing grant handle, tool route,
 * data from another workspace, or implication about the issuer's CURRENT grants/readiness. */
final class DeviceSavedPeerScope {
    private DeviceSavedPeerScope() {}
    static JSONObject read(DeviceWorkspaceNativeOwner owner,String id,String instance,String fingerprint)throws Exception {
        DeviceProtocol.deviceId(id,"deviceId"); DeviceProtocol.identifier(instance,"instanceId");
        DeviceIdentity.LocalIdentity here=owner.existingIdentity();
        JSONObject peer=owner.trust.peer(id);
        if(here==null||here.deviceId().equals(id)||!matches(peer,instance,fingerprint))throw new SecurityException("当前设备或伙伴身份已变化");
        JSONObject safe=new JSONObject().put("localOnly",true).put("readerDeviceId",here.deviceId()).put("readerInstanceId",here.instanceId())
                .put("targetDeviceId",id).put("targetInstanceId",instance).put("targetFingerprint",fingerprint);
        try {
            JSONObject evidence=owner.trust.savedPeerScopeEvidence(id);
            if(!"record".equals(evidence.optString("state")))return safe.put("state",evidence.getString("state"));
            long now=System.currentTimeMillis();
            byte[] remote=Base64.getDecoder().decode(peer.getString("certificateDer")), local=here.certificateDer();
            DeviceIdentity.certificate(remote).checkValidity(new java.util.Date(now)); DeviceIdentity.certificate(local).checkValidity(new java.util.Date(now));
            if(!fingerprint.equals(DeviceProtocol.sha256(remote))||!id.equals(DeviceIdentity.id(DeviceIdentity.certificate(remote))))throw new SecurityException();
            boolean localInvited=evidence.getBoolean("invitedHere");
            JSONObject inviter=evidence.getJSONObject("inviter"),joiner=evidence.getJSONObject("joiner"),t=inviter.getJSONObject("transcript");
            if(!"paired".equals(evidence.optString("pairStatus"))||!evidence.getString("inviteId").equals(t.optString("inviteId"))
                    ||!evidence.getString("digest").equals(DeviceProtocol.sha256(DeviceProtocol.canonicalBytes(t))))throw new SecurityException();
            requireSide(t,localInvited?"inviter":"joiner",here.deviceId(),here.instanceId(),here.certificateFingerprint());
            requireSide(t,localInvited?"joiner":"inviter",id,instance,fingerprint);
            PairingCoordinator.verifyConfirmationCertificate(inviter,localInvited?local:remote,t);
            PairingCoordinator.verifyConfirmationCertificate(joiner,localInvited?remote:local,t);

            JSONObject signed=evidence.getJSONObject("signed"),body=PairingCoordinator.verifyRecordCertificate(remote,signed,"PairGrantReceipt");
            DeviceProtocol.keys(body,DeviceProtocol.set("grant targetInstanceId issuedAt"),java.util.Set.of());
            JSONObject grant=body.getJSONObject("grant"); long received=evidence.getLong("receivedAt"),issued=body.getLong("issuedAt");
            // Our grant revision for the issuer is separate; never present it as their current revision.
            PairingCoordinator.validateGrant(grant,id,here.deviceId(),now);
            if(!id.equals(signed.optString("issuerDeviceId"))||!fingerprint.equals(signed.optString("issuerCertificateFingerprint"))
                    ||!instance.equals(body.optString("targetInstanceId"))||!evidence.getString("grantId").equals(grant.optString("grantId"))
                    ||grant.optBoolean("revoked",false)||issued<grant.getLong("issuedAt")||received<issued||received>now)throw new SecurityException();
            long until=Math.min(now+30_000L,grant.getLong("expiresAt"));
            JSONObject latestPeer=owner.trust.peer(id), latest=owner.trust.savedPeerScopeEvidence(id); DeviceIdentity.LocalIdentity reader=owner.existingIdentity();
            if(reader==null||!here.deviceId().equals(reader.deviceId())||!here.instanceId().equals(reader.instanceId())
                    ||!matches(latestPeer,instance,fingerprint)||peer.getLong("authorizationEpoch")!=latestPeer.getLong("authorizationEpoch")
                    ||!DeviceProtocol.canonical(evidence).equals(DeviceProtocol.canonical(latest))||System.currentTimeMillis()>=until)throw new SecurityException();
            JSONArray methods=grant.getJSONArray("capabilities");
            return safe.put("state","original_checked").put("receivedAt",received).put("expiresAt",grant.getLong("expiresAt")).put("viewUntil",until)
                    .put("issuerRevision",grant.getLong("authorizationEpoch")).put("identityOnly",DevicePairVisibility.identityOnly(grant))
                    .put("submitGoal",PairingCoordinator.contains(methods,"task.submitGoal")).put("invokeSelectedTools",PairingCoordinator.contains(methods,"tool.invoke"))
                    .put("originalTaskRead",PairingCoordinator.contains(methods,"task.get")).put("selectedToolCount",grant.getJSONArray("toolNames").length());
        } catch (SecurityException | IllegalArgumentException | java.security.GeneralSecurityException | org.json.JSONException unreadable) {
            return safe.put("state","expired_or_unverifiable");
        }
    }
    private static boolean matches(JSONObject p,String instance,String fp) {
        return p!=null && !p.optBoolean("revoked",true) && fp!=null && fp.matches("[a-f0-9]{64}") && fp.equals(p.optString("fingerprint")) && instance.equals(p.optString("instanceId"));
    }
    private static void requireSide(JSONObject t,String side,String id,String instance,String fp) {
        if(!id.equals(t.optString(side+"DeviceId"))||!instance.equals(t.optString(side+"InstanceId"))||!fp.equals(t.optString(side+"Fingerprint")))throw new SecurityException();
    }
}
