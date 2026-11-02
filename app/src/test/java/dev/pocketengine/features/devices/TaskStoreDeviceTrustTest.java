package dev.pocketengine.features.devices;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.store.TaskStoreDeviceTrust;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Original Android SQLite tables/atomicity only; this is NOT a device or TLS/key-store test. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE)
public final class TaskStoreDeviceTrustTest {
    private SQLiteDatabase db;private TaskStoreDeviceTrust trust;
    private static final String A="pw1_"+"a".repeat(64),B="pw1_"+"b".repeat(64),C="pw1_"+"c".repeat(64);
    private static final long NOW=2000000000000L;
    @Before public void open(){db=SQLiteDatabase.create(null);db.execSQL("CREATE TABLE tasks(id TEXT PRIMARY KEY)");db.execSQL("INSERT INTO tasks VALUES('original-task')");TaskStoreDeviceTrust.createSchema(db);trust=new TaskStoreDeviceTrust(db);}
    @After public void close(){db.close();}
    private JSONObject grant(String peer,long epoch)throws Exception{return new JSONObject().put("grantId","grant-1").put("peerDeviceId",peer).put("localDeviceId",B).put("authorizationEpoch",epoch).put("issuedAt",NOW).put("expiresAt",NOW+86400000).put("capabilities",new JSONArray().put("tool.invoke")).put("toolNames",new JSONArray().put("phone.tap")).put("resources",new JSONArray().put(new JSONObject().put("screenMode","main_screen"))).put("allowedOrigins",new JSONArray().put(peer)).put("allowDelegation",false).put("maxDelegationDepth",0).put("toolBudget",4).put("modelBudget",0);}
    private JSONObject peer(String id)throws Exception{return new JSONObject().put("deviceId",id).put("certificateDer",Base64.getEncoder().encodeToString(new byte[]{1,2,3})).put("fingerprint",id.substring(4)).put("instanceId","test-instance").put("authorizationEpoch",1).put("revoked",false).put("updatedAt",NOW);}
    private void invite(String id,long expires)throws Exception{trust.putInvitation(new JSONObject().put("inviteId",id).put("tokenDigest","d".repeat(64)).put("inviterDeviceId",B).put("instanceId","test-instance-b").put("expectedPeerFingerprint","").put("expiresAt",expires));}
    private JSONObject receipt(String id,String peer)throws Exception{return new JSONObject().put("inviteId",id).put("peerDeviceId",peer).put("status","paired");}
    private void pair()throws Exception{invite("invite-1",NOW+120000);trust.consumeInvitation("invite-1","d".repeat(64),"e".repeat(64),peer(A),grant(A,1),receipt("invite-1",A),NOW);}
    @Test public void sameGivenDatabasePreservesOriginalTasks()throws Exception{pair();try(Cursor row=db.rawQuery("SELECT id FROM tasks",null)){assertTrue(row.moveToFirst());assertEquals("original-task",row.getString(0));}assertNotNull(trust.peer(A));}
    @Test public void oneUseReceiptCannotChangeGrantOrPeer()throws Exception{
        pair();JSONObject original=trust.consumeInvitation("invite-1","d".repeat(64),"e".repeat(64),peer(A),grant(A,1).put("toolBudget",400),receipt("invite-1",A),NOW);assertEquals("paired",original.getString("status"));assertEquals(4,trust.grant("grant-1").getInt("toolBudget"));
        try{trust.consumeInvitation("invite-1","d".repeat(64),"e".repeat(64),peer(C),grant(C,1),receipt("invite-1",C),NOW);fail();}catch(SecurityException expected){}assertNull(trust.peer(C));
    }
    @Test public void expiredInvitationDoesNotCreateTrust()throws Exception{invite("expired",NOW);try{trust.consumeInvitation("expired","d".repeat(64),"e".repeat(64),peer(A),grant(A,1),receipt("expired",A),NOW);fail();}catch(SecurityException expected){}assertNull(trust.peer(A));}
    @Test public void transactionRollsBackPeerIfGrantInsertFails()throws Exception{
        pair();invite("invite-c",NOW+120000);try{trust.consumeInvitation("invite-c","d".repeat(64),"f".repeat(64),peer(C),grant(C,1),receipt("invite-c",C),NOW);fail();}catch(android.database.sqlite.SQLiteConstraintException expected){}assertNull(trust.peer(C));try(Cursor row=db.rawQuery("SELECT state FROM device_pairing_invites WHERE invite_id='invite-c'",null)){assertTrue(row.moveToFirst());assertEquals("pending",row.getString(0));}
    }
    @Test public void revokePersistsEpochAndOfflineAckSeparately()throws Exception{
        pair();assertEquals(2,trust.revoke(A,NOW+1));assertTrue(trust.peer(A).getBoolean("revoked"));assertTrue(trust.grant("grant-1").getBoolean("revoked"));assertFalse(trust.revocationStatus(A).getBoolean("peerAcknowledged"));try{trust.acknowledgeRevocation(A,1,NOW+2);fail();}catch(SecurityException expected){}trust.acknowledgeRevocation(A,2,NOW+3);assertTrue(trust.revocationStatus(A).getBoolean("peerAcknowledged"));
    }
    @Test public void permissionChangeMustUseNextEpoch()throws Exception{
        pair();try{trust.replaceGrant(grant(A,1),NOW);fail();}catch(SecurityException expected){}trust.replaceGrant(grant(A,2).put("toolBudget",1),NOW);assertEquals(2,trust.peer(A).getLong("authorizationEpoch"));assertEquals(1,trust.grant("grant-1").getLong("toolBudget"));
    }
    @Test public void rotationRevokesOldGrantsAndKeepsHistory()throws Exception{
        pair();JSONObject next=peer(C).put("authorizationEpoch",2);trust.rotatePeer(trust.peer(A),next,new JSONObject().put("rotationId","rotation-1").put("capabilitiesRegrantRequired",true),NOW);assertTrue(trust.peer(A).getBoolean("revoked"));assertFalse(trust.peer(C).getBoolean("revoked"));assertTrue(trust.grant("grant-1").getBoolean("revoked"));try(Cursor row=db.rawQuery("SELECT COUNT(*) FROM device_identity_rotations",null)){assertTrue(row.moveToFirst());assertEquals(1,row.getInt(0));}
    }
    @Test public void fileReopenRetainsRevocation()throws Exception{
        Path path=Files.createTempFile("pocketworld-test-trust-",".sqlite3");SQLiteDatabase actual=SQLiteDatabase.openOrCreateDatabase(path.toFile(),null);try{TaskStoreDeviceTrust.createSchema(actual);TaskStoreDeviceTrust stored=new TaskStoreDeviceTrust(actual);stored.putInvitation(new JSONObject().put("inviteId","saved").put("tokenDigest","d".repeat(64)).put("inviterDeviceId",B).put("instanceId","test-instance-b").put("expectedPeerFingerprint","").put("expiresAt",NOW+120000));stored.consumeInvitation("saved","d".repeat(64),"e".repeat(64),peer(A),grant(A,1),receipt("saved",A),NOW);stored.revoke(A,NOW+1);}finally{actual.close();}
        SQLiteDatabase reopened=SQLiteDatabase.openDatabase(path.toString(),null,SQLiteDatabase.OPEN_READWRITE);try{TaskStoreDeviceTrust restored=new TaskStoreDeviceTrust(reopened);assertTrue(restored.peer(A).getBoolean("revoked"));assertFalse(restored.revocationStatus(A).getBoolean("peerAcknowledged"));}finally{reopened.close();Files.deleteIfExists(path);Files.deleteIfExists(Path.of(path+"-journal"));Files.deleteIfExists(Path.of(path+"-wal"));Files.deleteIfExists(Path.of(path+"-shm"));}
    }
    @Test public void candidateCannotCommitBeforeNativeConfirmationAndCachesOneIdentity()throws Exception{
        invite("candidate",NOW+120000);trust.beginCandidateCheck("candidate","d".repeat(64),"a".repeat(64),NOW);JSONObject record=new JSONObject().put("inviteId","candidate").put("candidateDigest","e".repeat(64)).put("offerDigest","a".repeat(64)).put("peerDeviceId",A).put("transcript",new JSONObject());trust.saveCandidate(record,NOW);assertNull(trust.peer(A));assertNull(trust.grant("grant-1"));
        try{trust.consumeInvitation("candidate","d".repeat(64),"e".repeat(64),peer(A),grant(A,1),receipt("candidate",A),NOW);fail();}catch(SecurityException expected){}assertNull(trust.peer(A));trust.confirmCandidate("candidate","e".repeat(64),new JSONObject().put("nativeFixtureOnly",true),NOW);trust.consumeInvitation("candidate","d".repeat(64),"e".repeat(64),peer(A),grant(A,1),receipt("candidate",A),NOW);assertEquals("consumed",trust.candidate("candidate").getString("state"));assertNotNull(trust.candidate("candidate").getJSONObject("pairingReceipt"));
    }
    @Test public void candidateSignatureAttemptsAreBoundedWithoutPersistingSecret()throws Exception{
        invite("attempts",NOW+120000);for(int i=0;i<8;i++)trust.beginCandidateCheck("attempts","d".repeat(64),"a".repeat(64),NOW);try{trust.beginCandidateCheck("attempts","d".repeat(64),"a".repeat(64),NOW);fail();}catch(SecurityException expected){}assertEquals(8,trust.candidate("attempts").getInt("attempts"));assertNull(trust.peer(A));
    }
    @Test public void ownerWorkspaceHasStablePagesScopesAndNoDerOrInvitationSecret()throws Exception{
        pair();invite("invite-c",NOW+120000);trust.consumeInvitation("invite-c","d".repeat(64),"f".repeat(64),peer(C),grant(C,1).put("grantId","grant-c"),receipt("invite-c",C),NOW);JSONObject first=trust.workspaceSnapshot(NOW,"",1);assertTrue(first.getBoolean("hasMore"));assertEquals(A,first.getJSONArray("devices").getJSONObject(0).getString("deviceId"));assertEquals("active",first.getJSONArray("devices").getJSONObject(0).getJSONArray("grantScopes").getJSONObject(0).getString("state"));JSONObject second=trust.workspaceSnapshot(NOW,first.getString("nextCursor"),1);assertFalse(second.getBoolean("hasMore"));assertEquals(C,second.getJSONArray("devices").getJSONObject(0).getString("deviceId"));assertFalse(first.toString().contains("certificateDer"));assertFalse(first.toString().contains("token"));assertFalse(first.toString().contains("AQID"));trust.revoke(A,NOW);JSONObject peer=trust.workspaceSnapshot(NOW).getJSONArray("devices").getJSONObject(0);assertTrue(peer.getBoolean("revoked"));assertFalse(peer.getJSONObject("revocation").getBoolean("peerAcknowledged"));
    }
    @Test public void rotationCasUsesOriginalRevocationSnapshotAndConsumesConfirmationOnce()throws Exception{
        pair();JSONObject before=trust.peer(A),next=peer(C).put("authorizationEpoch",2);trust.revoke(A,NOW+1);
        JSONObject approval=new JSONObject().put("rotationId","local-confirm-1").put("confirmationId","local-confirm-1").put("confirmation","local_owner").put("expectedEpoch",1).put("expectedRevoked",false).put("issuedAt",NOW).put("expiresAt",NOW+1000);
        try{trust.rotatePeer(before,next,approval,NOW+2);fail();}catch(SecurityException expected){}assertNull(trust.peer(C));assertTrue(trust.grant("grant-1").getBoolean("revoked"));
        JSONObject after=trust.peer(A);approval.put("rotationId","local-confirm-2").put("confirmationId","local-confirm-2").put("expectedEpoch",2).put("expectedRevoked",true);next.put("authorizationEpoch",3);
        try{trust.rotatePeer(after,next,approval,NOW+1000);fail();}catch(SecurityException expected){}assertNull(trust.peer(C));
        trust.rotatePeer(after,next,approval,NOW+3);try{trust.rotatePeer(after,next,approval,NOW+4);fail();}catch(SecurityException expected){}
        try(Cursor row=db.rawQuery("SELECT COUNT(*) FROM device_identity_rotations",null)){assertTrue(row.moveToFirst());assertEquals(1,row.getInt(0));}
    }
    private long confirmedRecoveryCandidate(String id,JSONObject nativeGrant,JSONObject confirmation)throws Exception{
        invite(id,NOW+1000);trust.beginCandidateCheck(id,"d".repeat(64),"a".repeat(64),NOW);
        trust.saveCandidate(new JSONObject().put("inviteId",id).put("candidateDigest","e".repeat(64)).put("offerDigest","a".repeat(64)).put("peerDeviceId",A).put("transcript",new JSONObject()),NOW);
        JSONObject scope=new JSONObject(nativeGrant.toString());scope.remove("revoked");
        return trust.confirmCandidate(id,"e".repeat(64),confirmation,DeviceProtocol.sha256(DeviceProtocol.canonicalBytes(scope)),NOW);
    }
    @Test public void originalCommitStoresImmutableCompletionAndDoesNotResignOnReplay()throws Exception{
        // SQL atomicity fixture ONLY. Crypto/identity checks run separately on real shared proofs.
        JSONObject grant=grant(A,1),confirmation=new JSONObject().put("nativeFixtureOnly",true);
        long revision=confirmedRecoveryCandidate("recovery-original",grant,confirmation);
        JSONObject receipt=receipt("recovery-original",A).put("localConfirmation",confirmation),completion=new JSONObject().put("nativeSqlFixtureOnly",true).put("commitId","one-original-commit");
        JSONObject saved=trust.commitCandidateCompletion("recovery-original","d".repeat(64),"e".repeat(64),revision,peer(A),grant,receipt,completion,NOW+100);
        assertEquals(DeviceProtocol.canonical(completion),DeviceProtocol.canonical(saved));
        JSONObject repeated=trust.commitCandidateCompletion("recovery-original","d".repeat(64),"e".repeat(64),revision,peer(A),grant,receipt,new JSONObject().put("differentProposal",true),NOW+1001);
        assertEquals(DeviceProtocol.canonical(saved),DeviceProtocol.canonical(repeated));
        assertEquals(DeviceProtocol.canonical(saved),DeviceProtocol.canonical(trust.committedCompletion("recovery-original")));
        assertFalse(trust.candidate("recovery-original").getJSONObject("pairingReceipt").has("_immutableCompletion"));
        saved.put("callerMutation",true);assertFalse(trust.committedCompletion("recovery-original").has("callerMutation"));
        assertEquals(4,trust.grant("grant-1").getInt("toolBudget"));
        assertEquals(1,trust.committedPairingCandidates(8).length());
    }
    @Test public void originalCompletionRevisionExpiryAndFailureAreAtomicWithPeerAndGrant()throws Exception{
        JSONObject grant=grant(A,1),confirmation=new JSONObject().put("nativeFixtureOnly",true);
        long revision=confirmedRecoveryCandidate("recovery-fail",grant,confirmation);
        JSONObject receipt=receipt("recovery-fail",A).put("localConfirmation",confirmation),completion=new JSONObject().put("nativeSqlFixtureOnly",true);
        JSONObject scope=new JSONObject(grant.toString());scope.remove("revoked");String digest=DeviceProtocol.sha256(DeviceProtocol.canonicalBytes(scope));
        trust.confirmCandidate("recovery-fail","e".repeat(64),confirmation,digest,NOW+1);
        try{trust.commitCandidateCompletion("recovery-fail","d".repeat(64),"e".repeat(64),revision,peer(A),grant,receipt,completion,NOW+100);fail();}catch(SecurityException expected){}
        assertNull(trust.peer(A));assertNull(trust.grant("grant-1"));assertNull(trust.committedCompletion("recovery-fail"));
        db.execSQL("CREATE TEMP TRIGGER fail_pair_commit BEFORE UPDATE OF state ON device_pairing_invites WHEN NEW.state='consumed' BEGIN SELECT RAISE(ABORT,'injected original transaction failure'); END");
        try{trust.commitCandidateCompletion("recovery-fail","d".repeat(64),"e".repeat(64),revision+1,peer(A),grant,receipt,completion,NOW+100);fail();}catch(android.database.sqlite.SQLiteConstraintException expected){}
        assertNull(trust.peer(A));assertNull(trust.grant("grant-1"));assertNull(trust.committedCompletion("recovery-fail"));
        try{trust.commitCandidateCompletion("recovery-fail","d".repeat(64),"e".repeat(64),revision+1,peer(A),grant,receipt,completion,NOW+1001);fail();}catch(SecurityException expected){}
        assertEquals("confirmed",trust.candidate("recovery-fail").getString("state"));
    }
    @Test public void nativeOutgoingReviewUsesOriginalCandidateTableWithoutBecomingPendingPeer()throws Exception{
        JSONObject review=new JSONObject().put("role","local_offer").put("inviteId","outgoing-reviewed").put("candidateDigest","e".repeat(64)).put("offerDigest","a".repeat(64)).put("publicInvitation",new JSONObject().put("expiresAt",NOW+1000)).put("nativeSqlFixtureOnly",true);
        trust.saveLocalPairingOffer(review,NOW);trust.saveLocalPairingOffer(review,NOW+1);
        assertEquals(0,trust.pendingCandidates(NOW).length());assertEquals(1,trust.localPairingOffers(8).length());assertNull(trust.peer(A));
        assertEquals(DeviceProtocol.canonical(review),DeviceProtocol.canonical(trust.localPairingOffer("outgoing-reviewed")));
        try{trust.saveLocalPairingOffer(new JSONObject(review.toString()).put("differentReview",true),NOW+2);fail();}catch(SecurityException expected){}
        assertEquals(1,trust.localPairingOffers(8).length());
    }

    private JSONObject localOwnerReviewRecord(String id)throws Exception{
        JSONObject nativeGrant=grant(A,1),scope=new JSONObject(nativeGrant.toString());scope.remove("revoked");
        return new JSONObject().put("role","local_offer").put("inviteId",id).put("candidateDigest",DeviceProtocol.sha256(DeviceProtocol.canonicalBytes(new JSONObject().put("nativeSqlFixtureOnly",true).put("inviteId",id)))).put("offerDigest","a".repeat(64)).put("publicInvitation",new JSONObject().put("inviterDeviceId",A).put("certificateFingerprint","a".repeat(64)).put("expiresAt",NOW+1000)).put("createdAt",NOW).put("reviewedOwnerGrant",nativeGrant).put("reviewedOwnerGrantDigest",DeviceProtocol.sha256(DeviceProtocol.canonicalBytes(scope))).put("nativeSqlFixtureOnly",true);
    }
    @Test public void explicitLocalOwnerAbandonmentRetainsHistoryReleasesQuotaAndFencesLateInstall()throws Exception{
        PairingCoordinator coordinator=new PairingCoordinator(trust,B,"test-instance-b");JSONObject original=localOwnerReviewRecord("abandon-local-0");trust.saveLocalPairingOffer(original,NOW);
        for(int i=1;i<8;i++)trust.saveLocalPairingOffer(localOwnerReviewRecord("abandon-local-"+i),NOW);
        try{trust.saveLocalPairingOffer(localOwnerReviewRecord("abandon-ninth"),NOW);fail();}catch(SecurityException expected){}
        PairingCoordinator.LocalOfferAbandonmentReview choice=coordinator.reviewLocalPairingOfferAbandonment("abandon-local-0",original.getString("candidateDigest"),NOW+1001);
        assertTrue(choice.display().getBoolean("localOnly"));assertFalse(choice.display().getBoolean("remoteRevocationAcknowledged"));
        try{coordinator.abandonLocalPairingOffer(choice,false,NOW+1001);fail();}catch(SecurityException expected){}assertEquals("confirmed",trust.localPairingOfferState("abandon-local-0"));
        JSONObject result=coordinator.abandonLocalPairingOffer(choice,true,NOW+1001);assertFalse(result.getBoolean("remoteRevocationAcknowledged"));assertTrue(result.getBoolean("historyRetained"));assertEquals("rejected",trust.localPairingOfferState("abandon-local-0"));
        assertEquals(DeviceProtocol.canonical(original),DeviceProtocol.canonical(trust.localPairingOffer("abandon-local-0")));assertEquals(7,trust.localPairingOffers(8).length());
        try{trust.installConfirmedPeer("abandon-local-0",new JSONObject().put("nativeSqlFixtureOnly",true).put("inviteId","abandon-local-0"),peer(A),grant(A,1),receipt("abandon-local-0",A),NOW+1001);fail();}catch(SecurityException expected){}assertNull(trust.peer(A));assertNull(trust.grant("grant-1"));
        assertEquals("locally_abandoned",coordinator.abandonLocalPairingOffer(choice,true,NOW+1002).getString("state"));
        trust.saveLocalPairingOffer(localOwnerReviewRecord("abandon-ninth"),NOW+1002);assertEquals(8,trust.localPairingOffers(8).length());
        try{trust.saveLocalPairingOffer(original,NOW+1002);fail();}catch(SecurityException expected){}
    }

}
