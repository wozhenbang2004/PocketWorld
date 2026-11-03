package dev.pocketengine.store;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.features.devices.DeviceProtocol;
import dev.pocketengine.features.devices.PairingCoordinator;
import java.util.Base64;
import org.json.JSONObject;

/** Device trust tables in the ORIGINAL TaskStore SQLiteDatabase; never a second database/runner. */
public final class TaskStoreDeviceTrust implements PairingCoordinator.TrustStore {
    private final SQLiteDatabase db;
    public TaskStoreDeviceTrust(SQLiteDatabase db){this.db=java.util.Objects.requireNonNull(db);}
    /** Root integrator calls this from the one versioned Database migration. */
    public static void createSchema(SQLiteDatabase db){
        db.execSQL("CREATE TABLE IF NOT EXISTS device_peers (device_id TEXT PRIMARY KEY,certificate_der BLOB NOT NULL,fingerprint TEXT NOT NULL,instance_id TEXT NOT NULL,authorization_epoch INTEGER NOT NULL CHECK(authorization_epoch>=1),revoked INTEGER NOT NULL CHECK(revoked IN (0,1)),updated_at INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE IF NOT EXISTS device_grants (grant_id TEXT PRIMARY KEY,peer_device_id TEXT NOT NULL,local_device_id TEXT NOT NULL,authorization_epoch INTEGER NOT NULL CHECK(authorization_epoch>=1),expires_at INTEGER NOT NULL,body_json TEXT NOT NULL,revoked INTEGER NOT NULL CHECK(revoked IN (0,1)))");
        db.execSQL("CREATE INDEX IF NOT EXISTS device_grants_peer ON device_grants(peer_device_id)");
        db.execSQL("CREATE TABLE IF NOT EXISTS device_pairing_invites (invite_id TEXT PRIMARY KEY,token_digest TEXT NOT NULL,inviter_device_id TEXT NOT NULL,instance_id TEXT NOT NULL,expected_peer_fingerprint TEXT NOT NULL,expires_at INTEGER NOT NULL,state TEXT NOT NULL CHECK(state IN ('pending','consumed','revoked')),transcript_digest TEXT,peer_device_id TEXT,receipt_json TEXT)");
        db.execSQL("CREATE TABLE IF NOT EXISTS device_revocation_acks (peer_device_id TEXT PRIMARY KEY,authorization_epoch INTEGER NOT NULL CHECK(authorization_epoch>=1),acknowledged_epoch INTEGER NOT NULL DEFAULT 0 CHECK(acknowledged_epoch>=0),updated_at INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE IF NOT EXISTS device_outgoing_grants (target_device_id TEXT NOT NULL,grant_id TEXT NOT NULL,receipt_json TEXT NOT NULL,received_at INTEGER NOT NULL,PRIMARY KEY(target_device_id,grant_id))");
        db.execSQL("CREATE TABLE IF NOT EXISTS device_identity_rotations (rotation_id TEXT PRIMARY KEY,old_device_id TEXT NOT NULL,new_device_id TEXT NOT NULL,authorization_epoch INTEGER NOT NULL CHECK(authorization_epoch>=1),receipt_json TEXT NOT NULL,created_at INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE IF NOT EXISTS device_pairing_candidates (invite_id TEXT PRIMARY KEY,attempts INTEGER NOT NULL CHECK(attempts>=0),candidate_digest TEXT,offer_digest TEXT,record_json TEXT,state TEXT NOT NULL CHECK(state IN ('checking','presented','confirmed','consumed','rejected')),expires_at INTEGER NOT NULL,updated_at INTEGER NOT NULL)");
    }
    // These single-statement permission reads share no mutable Java state. Do
    // not take this facade's monitor: TaskStore rechecks them inside its original
    // SQL transaction, while another caller may be waiting for that connection.
    // SQL provides the read snapshot; all compound trust writes keep their locks
    // and transactions below. Each read returns a new, caller-owned JSON value.
    @Override public JSONObject peer(String id)throws Exception{
        try(Cursor row=db.rawQuery("SELECT certificate_der,fingerprint,instance_id,authorization_epoch,revoked,updated_at FROM device_peers WHERE device_id=?",new String[]{id})){if(!row.moveToFirst())return null;return new JSONObject().put("deviceId",id).put("certificateDer",Base64.getEncoder().encodeToString(row.getBlob(0))).put("fingerprint",row.getString(1)).put("instanceId",row.getString(2)).put("authorizationEpoch",row.getLong(3)).put("revoked",row.getInt(4)!=0).put("updatedAt",row.getLong(5));}
    }
    @Override public JSONObject grant(String id)throws Exception{
        try(Cursor row=db.rawQuery("SELECT body_json,authorization_epoch,revoked FROM device_grants WHERE grant_id=?",new String[]{id})){if(!row.moveToFirst())return null;JSONObject value=new JSONObject(row.getString(0));value.put("authorizationEpoch",row.getLong(1)).put("revoked",row.getInt(2)!=0);return value;}
    }
    @Override public synchronized org.json.JSONArray grantsForPeer(String peer,int limit)throws Exception{if(limit<1||limit>16)throw new IllegalArgumentException("Invalid scoped grant limit");org.json.JSONArray values=new org.json.JSONArray();try(Cursor rows=db.rawQuery("SELECT grant_id FROM device_grants WHERE peer_device_id=? AND revoked=0 AND authorization_epoch=(SELECT authorization_epoch FROM device_peers WHERE device_id=?) ORDER BY grant_id LIMIT ?",new String[]{peer,peer,Integer.toString(limit)})){while(rows.moveToNext())values.put(grant(rows.getString(0)));}return values;}
    @Override public synchronized void putInvitation(JSONObject value)throws Exception{
        db.beginTransaction();try{try(Cursor active=db.rawQuery("SELECT COUNT(*) FROM device_pairing_invites WHERE state='pending' AND expires_at>?",new String[]{Long.toString(value.optLong("createdAt",System.currentTimeMillis()))})){if(active.moveToFirst()&&active.getInt(0)>=32)throw new SecurityException("Too many pending local pairing invitations");}ContentValues row=new ContentValues();row.put("invite_id",value.getString("inviteId"));row.put("token_digest",value.getString("tokenDigest"));row.put("inviter_device_id",value.getString("inviterDeviceId"));row.put("instance_id",value.getString("instanceId"));row.put("expected_peer_fingerprint",value.getString("expectedPeerFingerprint"));row.put("expires_at",value.getLong("expiresAt"));row.put("state","pending");db.insertOrThrow("device_pairing_invites",null,row);db.setTransactionSuccessful();}finally{db.endTransaction();}
    }
    @Override public synchronized JSONObject consumeInvitation(String id,String tokenDigest,String transcriptDigest,JSONObject peer,JSONObject grant,JSONObject receipt,long now)throws Exception{
        db.beginTransaction();try{
            try(Cursor row=db.rawQuery("SELECT token_digest,inviter_device_id,instance_id,expected_peer_fingerprint,expires_at,state,transcript_digest,peer_device_id,receipt_json FROM device_pairing_invites WHERE invite_id=?",new String[]{id})){
                if(!row.moveToFirst()||!constant(row.getString(0),tokenDigest)||!row.getString(1).equals(grant.getString("localDeviceId")))throw new SecurityException("Pairing invitation does not belong to this device");
                if("consumed".equals(row.getString(5))){if(!transcriptDigest.equals(row.getString(6))||!peer.getString("deviceId").equals(row.getString(7)))throw new SecurityException("Pairing invitation already consumed");JSONObject known=new JSONObject(row.getString(8));db.setTransactionSuccessful();return known;}
                if(!"pending".equals(row.getString(5))||row.getLong(4)<=now||!row.getString(3).isEmpty()&&!row.getString(3).equals(peer.getString("fingerprint")))throw new SecurityException("Pairing invitation expired, revoked or for another peer");
            }
            try(Cursor candidate=db.rawQuery("SELECT candidate_digest,state FROM device_pairing_candidates WHERE invite_id=?",new String[]{id})){if(candidate.moveToFirst()&&!candidate.isNull(0)&&(!transcriptDigest.equals(candidate.getString(0))||!"confirmed".equals(candidate.getString(1))))throw new SecurityException("The one-time candidate has not been natively confirmed");}
            if(peer(peer.getString("deviceId"))!=null)throw new SecurityException("Existing device requires explicit grant change or rotation, not re-pairing");
            ContentValues device=new ContentValues();device.put("device_id",peer.getString("deviceId"));device.put("certificate_der",Base64.getDecoder().decode(peer.getString("certificateDer")));device.put("fingerprint",peer.getString("fingerprint"));device.put("instance_id",peer.getString("instanceId"));device.put("authorization_epoch",1);device.put("revoked",0);device.put("updated_at",now);db.insertOrThrow("device_peers",null,device);
            insertGrant(grant);
            ContentValues used=new ContentValues();used.put("state","consumed");used.put("transcript_digest",transcriptDigest);used.put("peer_device_id",peer.getString("deviceId"));used.put("receipt_json",DeviceProtocol.canonical(receipt));if(db.update("device_pairing_invites",used,"invite_id=? AND state='pending'",new String[]{id})!=1)throw new SecurityException("Pairing invitation was concurrently consumed");
            ContentValues completed=new ContentValues();completed.put("state","consumed");completed.put("updated_at",now);db.update("device_pairing_candidates",completed,"invite_id=?",new String[]{id});
            db.setTransactionSuccessful();return new JSONObject(receipt.toString());
        }finally{db.endTransaction();}
    }
    /** Original transaction persists the peer/grant AND the exact completion bytes or neither. */
    @Override public synchronized JSONObject commitCandidateCompletion(String id,String tokenDigest,String transcriptDigest,long expectedRevision,JSONObject peer,JSONObject grant,JSONObject receipt,JSONObject completion,long now)throws Exception{
        db.beginTransaction();try{
            JSONObject known=candidate(id);
            if(known==null||!transcriptDigest.equals(known.optString("candidateDigest")))throw new SecurityException("Original candidate changed");
            if("consumed".equals(known.optString("state"))){
                JSONObject saved=known.optJSONObject("committedCompletion");
                if(saved==null||!peer.getString("deviceId").equals(known.optString("peerDeviceId")))throw new SecurityException("No immutable original completion");
                db.setTransactionSuccessful();return new JSONObject(saved.toString());
            }
            JSONObject scope=new JSONObject(grant.toString());scope.remove("revoked");
            if(!"confirmed".equals(known.optString("state"))||known.optLong("confirmationRevision")!=expectedRevision||!DeviceProtocol.sha256(DeviceProtocol.canonicalBytes(scope)).equals(known.optString("reviewedOwnerGrantDigest"))||!DeviceProtocol.canonical(known.optJSONObject("localConfirmation")).equals(DeviceProtocol.canonical(receipt.getJSONObject("localConfirmation"))))throw new SecurityException("Native owner confirmation revision or exact scope changed before commit");
            PairingCoordinator.validateGrant(grant,grant.getString("localDeviceId"),peer.getString("deviceId"),now);
            JSONObject wrapped=new JSONObject(receipt.toString()).put("_immutableCompletion",new JSONObject(completion.toString()));
            consumeInvitation(id,tokenDigest,transcriptDigest,peer,grant,wrapped,now);
            db.setTransactionSuccessful();return new JSONObject(completion.toString());
        }finally{db.endTransaction();}
    }
    @Override public synchronized JSONObject committedCompletion(String id)throws Exception{
        JSONObject candidate=candidate(id);return candidate!=null&&"consumed".equals(candidate.optString("state"))?candidate.optJSONObject("committedCompletion"):null;
    }
    @Override public synchronized void saveLocalPairingOffer(JSONObject record,long now)throws Exception{
        db.beginTransaction();try{
            String id=record.getString("inviteId");JSONObject known=localPairingOffer(id);
            if(known!=null){String state=localPairingOfferState(id);if(!("confirmed".equals(state)||"consumed".equals(state)))throw new SecurityException("Original local review was abandoned");if(!DeviceProtocol.canonical(known).equals(DeviceProtocol.canonical(record)))throw new SecurityException("Original local offer cannot be replaced");db.setTransactionSuccessful();return;}
            try(Cursor exists=db.rawQuery("SELECT 1 FROM device_pairing_candidates WHERE invite_id=?",new String[]{id})){if(exists.moveToFirst()||!"local_offer".equals(record.optString("role")))throw new SecurityException("Invitation belongs to another onboarding role");}
            try(Cursor count=db.rawQuery("SELECT COUNT(*) FROM device_pairing_candidates WHERE state='confirmed' AND (attempts=0 OR expires_at>?)",new String[]{Long.toString(now)})){if(count.moveToFirst()&&count.getInt(0)>=8)throw new SecurityException("Too many original pending local offers");}
            ContentValues row=new ContentValues();row.put("invite_id",id);row.put("attempts",0);row.put("candidate_digest",record.getString("candidateDigest"));row.put("offer_digest",record.getString("offerDigest"));row.put("record_json",DeviceProtocol.canonical(record));row.put("state","confirmed");row.put("expires_at",record.getJSONObject("publicInvitation").getLong("expiresAt"));row.put("updated_at",now);db.insertOrThrow("device_pairing_candidates",null,row);
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
    @Override public synchronized org.json.JSONArray committedPairingCandidates(int limit)throws Exception{
        if(limit<1||limit>8)throw new IllegalArgumentException("Invalid immutable pairing display limit");org.json.JSONArray result=new org.json.JSONArray();
        try(Cursor rows=db.rawQuery("SELECT c.invite_id FROM device_pairing_candidates c JOIN device_pairing_invites i ON i.invite_id=c.invite_id WHERE c.state='consumed' AND i.state='consumed' AND c.attempts>0 ORDER BY c.updated_at DESC,c.invite_id LIMIT ?",new String[]{Integer.toString(limit)})){while(rows.moveToNext())result.put(candidate(rows.getString(0)));}return result;
    }
    @Override public synchronized JSONObject localPairingOffer(String id)throws Exception{
        try(Cursor row=db.rawQuery("SELECT record_json FROM device_pairing_candidates WHERE invite_id=?",new String[]{id})){if(!row.moveToFirst()||row.isNull(0))return null;JSONObject record=new JSONObject(row.getString(0));return "local_offer".equals(record.optString("role"))?record:null;}
    }
    @Override public synchronized String localPairingOfferState(String id)throws Exception{
        try(Cursor row=db.rawQuery("SELECT state,attempts,record_json FROM device_pairing_candidates WHERE invite_id=?",new String[]{id})){if(!row.moveToFirst()||row.getInt(1)!=0||row.isNull(2)||!"local_offer".equals(new JSONObject(row.getString(2)).optString("role")))return null;return row.getString(0);}
    }
    @Override public synchronized void abandonLocalPairingOffer(String id,String digest,String recordDigest,long now)throws Exception{
        db.beginTransaction();try{
            JSONObject record=localPairingOffer(id);String state=localPairingOfferState(id);
            if(record==null||!digest.equals(record.optString("candidateDigest"))||!DeviceProtocol.sha256(DeviceProtocol.canonicalBytes(record)).equals(recordDigest))throw new SecurityException("Original native local review changed");
            if(!("confirmed".equals(state)||"rejected".equals(state)))throw new SecurityException("Local pairing already applied; use explicit owner revoke, not pending abandonment");
            if("confirmed".equals(state)){ContentValues row=new ContentValues();row.put("state","rejected");row.put("updated_at",now);if(db.update("device_pairing_candidates",row,"invite_id=? AND attempts=0 AND state='confirmed'",new String[]{id})!=1)throw new SecurityException("Local pairing changed before abandonment");}
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
    @Override public synchronized org.json.JSONArray localPairingOffers(int limit)throws Exception{
        if(limit<1||limit>8)throw new IllegalArgumentException("Invalid original local offer limit");org.json.JSONArray result=new org.json.JSONArray();
        try(Cursor rows=db.rawQuery("SELECT record_json FROM device_pairing_candidates WHERE state='confirmed' AND attempts=0 ORDER BY updated_at,invite_id LIMIT ?",new String[]{Integer.toString(limit)})){while(rows.moveToNext()){JSONObject record=new JSONObject(rows.getString(0));if("local_offer".equals(record.optString("role")))result.put(record);}}return result;
    }
    private void insertGrant(JSONObject grant)throws Exception{
        ContentValues row=new ContentValues();row.put("grant_id",grant.getString("grantId"));row.put("peer_device_id",grant.getString("peerDeviceId"));row.put("local_device_id",grant.getString("localDeviceId"));row.put("authorization_epoch",grant.getLong("authorizationEpoch"));row.put("expires_at",grant.getLong("expiresAt"));row.put("body_json",DeviceProtocol.canonical(grant));row.put("revoked",0);db.insertOrThrow("device_grants",null,row);
    }
    @Override public synchronized JSONObject installConfirmedPeer(String id,JSONObject transcript,JSONObject peer,JSONObject grant,JSONObject receipt,long now)throws Exception{
        String digest=DeviceProtocol.sha256(DeviceProtocol.canonicalBytes(transcript));db.beginTransaction();try{
            JSONObject review=localPairingOffer(id);String reviewState=localPairingOfferState(id);
            if(review!=null){JSONObject reviewedScope=new JSONObject(grant.toString());reviewedScope.remove("revoked");if(!("confirmed".equals(reviewState)||"consumed".equals(reviewState))||!digest.equals(review.optString("candidateDigest"))||!DeviceProtocol.sha256(DeviceProtocol.canonicalBytes(reviewedScope)).equals(review.optString("reviewedOwnerGrantDigest")))throw new SecurityException("Original local review abandoned or changed before reciprocal install");}
            try(Cursor row=db.rawQuery("SELECT transcript_digest,peer_device_id,receipt_json FROM device_pairing_invites WHERE invite_id=?",new String[]{id})){if(row.moveToFirst()){if(!digest.equals(row.getString(0))||!peer.getString("deviceId").equals(row.getString(1)))throw new SecurityException("Reciprocal invitation was already used");JSONObject known=new JSONObject(row.getString(2));db.setTransactionSuccessful();return known;}}
            if(review!=null&&!"confirmed".equals(reviewState))throw new SecurityException("Missing reciprocal commit cannot be inferred from consumed review");
            if(peer(peer.getString("deviceId"))!=null)throw new SecurityException("Existing peer requires grant change or rotation");
            ContentValues device=new ContentValues();device.put("device_id",peer.getString("deviceId"));device.put("certificate_der",Base64.getDecoder().decode(peer.getString("certificateDer")));device.put("fingerprint",peer.getString("fingerprint"));device.put("instance_id",peer.getString("instanceId"));device.put("authorization_epoch",1);device.put("revoked",0);device.put("updated_at",now);db.insertOrThrow("device_peers",null,device);insertGrant(grant);
            ContentValues offerState=new ContentValues();offerState.put("state","consumed");offerState.put("updated_at",now);db.update("device_pairing_candidates",offerState,"invite_id=? AND attempts=0",new String[]{id});
            ContentValues used=new ContentValues();used.put("invite_id",id);used.put("token_digest",transcript.getString("tokenDigest"));used.put("inviter_device_id",transcript.getString("inviterDeviceId"));used.put("instance_id",transcript.getString("inviterInstanceId"));used.put("expected_peer_fingerprint",peer.getString("fingerprint"));used.put("expires_at",transcript.getLong("expiresAt"));used.put("state","consumed");used.put("transcript_digest",digest);used.put("peer_device_id",peer.getString("deviceId"));used.put("receipt_json",DeviceProtocol.canonical(receipt));db.insertOrThrow("device_pairing_invites",null,used);db.setTransactionSuccessful();return new JSONObject(receipt.toString());
        }finally{db.endTransaction();}
    }
    @Override public synchronized void putOutgoingGrant(String target,String grant,JSONObject receipt,long now){ContentValues values=new ContentValues();values.put("target_device_id",target);values.put("grant_id",grant);values.put("receipt_json",DeviceProtocol.canonical(receipt));values.put("received_at",now);db.insertWithOnConflict("device_outgoing_grants",null,values,SQLiteDatabase.CONFLICT_REPLACE);}
    @Override public JSONObject outgoingGrant(String target,String grant)throws Exception{try(Cursor row=db.rawQuery("SELECT receipt_json FROM device_outgoing_grants WHERE target_device_id=? AND grant_id=?",new String[]{target,grant})){return row.moveToFirst()?new JSONObject(row.getString(0)):null;}}
    @Override public synchronized JSONObject rotatePeer(JSONObject previous,JSONObject next,JSONObject receipt,long now)throws Exception{
        db.beginTransaction();try{
            String oldId=previous.getString("deviceId"),newId=next.getString("deviceId");JSONObject actual=peer(oldId);if(actual==null||actual.getLong("authorizationEpoch")!=previous.getLong("authorizationEpoch")||!actual.getString("fingerprint").equals(previous.getString("fingerprint"))||actual.getBoolean("revoked")!=previous.getBoolean("revoked"))throw new SecurityException("Original identity changed before rotation");if(!oldId.equals(newId)&&peer(newId)!=null)throw new SecurityException("Replacement is already a separate paired device");
            if("local_owner".equals(receipt.optString("confirmation"))){if(receipt.getLong("expectedEpoch")!=actual.getLong("authorizationEpoch")||receipt.getBoolean("expectedRevoked")!=actual.getBoolean("revoked")||receipt.getLong("issuedAt")>now||receipt.getLong("expiresAt")<=now||!receipt.getString("rotationId").equals(receipt.getString("confirmationId")))throw new SecurityException("Native rotation confirmation expired or was superseded");try(Cursor used=db.rawQuery("SELECT 1 FROM device_identity_rotations WHERE rotation_id=?",new String[]{receipt.getString("confirmationId")})){if(used.moveToFirst())throw new SecurityException("Native rotation confirmation already consumed");}}
            ContentValues revoke=new ContentValues();revoke.put("authorization_epoch",next.getLong("authorizationEpoch"));revoke.put("revoked",1);revoke.put("updated_at",now);db.update("device_peers",revoke,"device_id=?",new String[]{oldId});ContentValues grants=new ContentValues();grants.put("revoked",1);db.update("device_grants",grants,"peer_device_id=?",new String[]{oldId});
            ContentValues device=new ContentValues();device.put("device_id",newId);device.put("certificate_der",Base64.getDecoder().decode(next.getString("certificateDer")));device.put("fingerprint",next.getString("fingerprint"));device.put("instance_id",next.getString("instanceId"));device.put("authorization_epoch",next.getLong("authorizationEpoch"));device.put("revoked",0);device.put("updated_at",now);if(oldId.equals(newId))db.update("device_peers",device,"device_id=?",new String[]{oldId});else db.insertOrThrow("device_peers",null,device);
            ContentValues rotation=new ContentValues();rotation.put("rotation_id",receipt.getString("rotationId"));rotation.put("old_device_id",oldId);rotation.put("new_device_id",newId);rotation.put("authorization_epoch",next.getLong("authorizationEpoch"));rotation.put("receipt_json",DeviceProtocol.canonical(receipt));rotation.put("created_at",now);db.insertOrThrow("device_identity_rotations",null,rotation);
            ContentValues ack=new ContentValues();ack.put("peer_device_id",oldId);ack.put("authorization_epoch",next.getLong("authorizationEpoch"));ack.put("acknowledged_epoch",0);ack.put("updated_at",now);db.insertWithOnConflict("device_revocation_acks",null,ack,SQLiteDatabase.CONFLICT_REPLACE);db.setTransactionSuccessful();return new JSONObject(receipt.toString());
        }finally{db.endTransaction();}
    }
    @Override public synchronized long revoke(String id,long now)throws Exception{
        db.beginTransaction();try{JSONObject peer=peer(id);if(peer==null)throw new SecurityException("Device is not paired");long epoch=Math.addExact(peer.getLong("authorizationEpoch"),1);ContentValues values=new ContentValues();values.put("authorization_epoch",epoch);values.put("revoked",1);values.put("updated_at",now);db.update("device_peers",values,"device_id=?",new String[]{id});ContentValues grants=new ContentValues();grants.put("revoked",1);db.update("device_grants",grants,"peer_device_id=?",new String[]{id});ContentValues ack=new ContentValues();ack.put("peer_device_id",id);ack.put("authorization_epoch",epoch);ack.put("acknowledged_epoch",0);ack.put("updated_at",now);db.insertWithOnConflict("device_revocation_acks",null,ack,SQLiteDatabase.CONFLICT_REPLACE);db.setTransactionSuccessful();return epoch;}finally{db.endTransaction();}
    }
    @Override public synchronized void replaceGrant(JSONObject grant,long now)throws Exception{
        db.beginTransaction();try{String id=grant.getString("peerDeviceId");JSONObject peer=peer(id);if(peer==null||peer.optBoolean("revoked"))throw new SecurityException("Device must be paired before granting capabilities");long epoch=Math.addExact(peer.getLong("authorizationEpoch"),1);if(grant.getLong("authorizationEpoch")!=epoch)throw new SecurityException("Grant change requires the next original device epoch");PairingCoordinator.validateGrant(grant,grant.getString("localDeviceId"),id,now);ContentValues current=new ContentValues();current.put("authorization_epoch",epoch);current.put("updated_at",now);db.update("device_peers",current,"device_id=?",new String[]{id});db.delete("device_grants","grant_id=? AND peer_device_id=?",new String[]{grant.getString("grantId"),id});insertGrant(grant);db.setTransactionSuccessful();}finally{db.endTransaction();}
    }
    @Override public synchronized void acknowledgeRevocation(String id,long epoch,long now)throws Exception{
        ContentValues values=new ContentValues();values.put("acknowledged_epoch",epoch);values.put("updated_at",now);if(db.update("device_revocation_acks",values,"peer_device_id=? AND authorization_epoch=?",new String[]{id,Long.toString(epoch)})!=1)throw new SecurityException("Revocation acknowledgement is not for the current epoch");
    }
    @Override public synchronized JSONObject revocationStatus(String id)throws Exception{
        try(Cursor row=db.rawQuery("SELECT authorization_epoch,acknowledged_epoch,updated_at FROM device_revocation_acks WHERE peer_device_id=?",new String[]{id})){if(!row.moveToFirst())return new JSONObject().put("peerDeviceId",id).put("locallyRevoked",false).put("peerAcknowledged",false);return new JSONObject().put("peerDeviceId",id).put("locallyRevoked",true).put("authorizationEpoch",row.getLong(0)).put("acknowledgedEpoch",row.getLong(1)).put("peerAcknowledged",row.getLong(0)==row.getLong(1)).put("updatedAt",row.getLong(2));}
    }
    private static boolean constant(String a,String b){return a!=null&&b!=null&&java.security.MessageDigest.isEqual(a.getBytes(java.nio.charset.StandardCharsets.US_ASCII),b.getBytes(java.nio.charset.StandardCharsets.US_ASCII));}
    @Override public synchronized JSONObject candidate(String id)throws Exception{
        try(Cursor row=db.rawQuery("SELECT c.candidate_digest,c.offer_digest,c.record_json,c.state,c.expires_at,c.attempts,i.receipt_json FROM device_pairing_candidates c JOIN device_pairing_invites i ON i.invite_id=c.invite_id WHERE c.invite_id=?",new String[]{id})){if(!row.moveToFirst())return null;JSONObject value=row.isNull(2)?new JSONObject():new JSONObject(row.getString(2));value.put("candidateDigest",row.isNull(0)?"":row.getString(0)).put("offerDigest",row.isNull(1)?"":row.getString(1)).put("state",row.getString(3)).put("expiresAt",row.getLong(4)).put("attempts",row.getInt(5));if(!row.isNull(6)){JSONObject stored=new JSONObject(row.getString(6));JSONObject completion=stored.optJSONObject("_immutableCompletion");stored.remove("_immutableCompletion");value.put("pairingReceipt",stored);if(completion!=null)value.put("committedCompletion",completion);}return value;}
    }
    @Override public synchronized JSONObject beginCandidateCheck(String id,String tokenDigest,String offerDigest,long now)throws Exception{
        db.beginTransaction();try{
            JSONObject invitation;
            try(Cursor row=db.rawQuery("SELECT token_digest,inviter_device_id,instance_id,expected_peer_fingerprint,expires_at,state,receipt_json FROM device_pairing_invites WHERE invite_id=?",new String[]{id})){
                if(!row.moveToFirst()||!constant(row.getString(0),tokenDigest))throw new SecurityException("Pairing invitation is unavailable");invitation=new JSONObject().put("inviteId",id).put("inviterDeviceId",row.getString(1)).put("instanceId",row.getString(2)).put("expectedPeerFingerprint",row.getString(3)).put("expiresAt",row.getLong(4)).put("state",row.getString(5));if(!row.isNull(6))invitation.put("pairingReceipt",new JSONObject(row.getString(6)));
            }
            JSONObject known=candidate(id);if(known!=null&&!known.optString("candidateDigest").isEmpty()&&offerDigest.equals(known.optString("offerDigest"))){if("pending".equals(invitation.optString("state"))&&invitation.getLong("expiresAt")<=now)throw new SecurityException("Pairing invitation expired");invitation.put("cachedCandidate",known);db.setTransactionSuccessful();return invitation;}
            if(!"pending".equals(invitation.getString("state"))||invitation.getLong("expiresAt")<=now)throw new SecurityException("Pairing invitation expired or consumed");
            int attempts=known==null?0:known.optInt("attempts");if(attempts>=8)throw new SecurityException("Pairing signature attempts exhausted");if(known==null){try(Cursor active=db.rawQuery("SELECT COUNT(*) FROM device_pairing_candidates WHERE state IN ('checking','presented','confirmed') AND expires_at>?",new String[]{Long.toString(now)})){if(active.moveToFirst()&&active.getInt(0)>=8)throw new SecurityException("Too many active pairing candidates");}ContentValues row=new ContentValues();row.put("invite_id",id);row.put("attempts",1);row.put("state","checking");row.put("expires_at",invitation.getLong("expiresAt"));row.put("updated_at",now);db.insertOrThrow("device_pairing_candidates",null,row);}else{ContentValues row=new ContentValues();row.put("attempts",attempts+1);row.put("updated_at",now);db.update("device_pairing_candidates",row,"invite_id=?",new String[]{id});}db.setTransactionSuccessful();return invitation;
        }finally{db.endTransaction();}
    }
    @Override public synchronized JSONObject saveCandidate(JSONObject value,long now)throws Exception{
        db.beginTransaction();try{String id=value.getString("inviteId");JSONObject known=candidate(id);if(known==null||known.optInt("attempts")<1||known.getLong("expiresAt")<=now||!("checking".equals(known.optString("state"))||"presented".equals(known.optString("state"))||"confirmed".equals(known.optString("state"))))throw new SecurityException("Pairing candidate is unavailable");try(Cursor invitation=db.rawQuery("SELECT state FROM device_pairing_invites WHERE invite_id=?",new String[]{id})){if(!invitation.moveToFirst()||!"pending".equals(invitation.getString(0)))throw new SecurityException("Pairing invitation is unavailable");}if(!known.optString("candidateDigest").isEmpty()&&!known.getString("candidateDigest").equals(value.getString("candidateDigest")))throw new SecurityException("Invitation is already bound to another exact candidate");if(!known.optString("candidateDigest").isEmpty()){ContentValues cache=new ContentValues();cache.put("offer_digest",value.getString("offerDigest"));db.update("device_pairing_candidates",cache,"invite_id=?",new String[]{id});db.setTransactionSuccessful();return candidate(id);}
            ContentValues row=new ContentValues();row.put("candidate_digest",value.getString("candidateDigest"));row.put("offer_digest",value.getString("offerDigest"));row.put("record_json",DeviceProtocol.canonical(value));row.put("state","presented");row.put("updated_at",now);db.update("device_pairing_candidates",row,"invite_id=?",new String[]{id});db.setTransactionSuccessful();return candidate(id);
        }finally{db.endTransaction();}
    }
    @Override public synchronized long confirmCandidate(String id,String digest,JSONObject confirmation,long now)throws Exception{
        return confirmCandidate(id,digest,confirmation,null,now);
    }
    @Override public synchronized long confirmCandidate(String id,String digest,JSONObject confirmation,String reviewedGrantDigest,long now)throws Exception{
        db.beginTransaction();try{
            JSONObject known=candidate(id);
            if(known==null||!digest.equals(known.optString("candidateDigest"))||known.getLong("expiresAt")<=now||!("presented".equals(known.optString("state"))||"confirmed".equals(known.optString("state"))))throw new SecurityException("Pairing candidate expired or changed");
            long revision=Math.addExact(known.optLong("confirmationRevision"),1);
            known.put("localConfirmation",confirmation).put("confirmationRevision",revision);known.remove("reviewedOwnerGrantDigest");
            if(reviewedGrantDigest!=null){if(!reviewedGrantDigest.matches("[a-f0-9]{64}"))throw new SecurityException("Invalid native scope digest");known.put("reviewedOwnerGrantDigest",reviewedGrantDigest);}
            ContentValues row=new ContentValues();row.put("state","confirmed");row.put("record_json",DeviceProtocol.canonical(known));row.put("updated_at",now);db.update("device_pairing_candidates",row,"invite_id=?",new String[]{id});
            db.setTransactionSuccessful();return revision;
        }finally{db.endTransaction();}
    }
    @Override public synchronized void rejectCandidate(String id,String digest,long now)throws Exception{
        db.beginTransaction();try{JSONObject known=candidate(id);if(known==null||!digest.equals(known.optString("candidateDigest"))||"consumed".equals(known.optString("state")))throw new SecurityException("Pairing candidate changed");ContentValues row=new ContentValues();row.put("state","rejected");row.put("updated_at",now);db.update("device_pairing_candidates",row,"invite_id=?",new String[]{id});ContentValues revoked=new ContentValues();revoked.put("state","revoked");db.update("device_pairing_invites",revoked,"invite_id=? AND state='pending'",new String[]{id});db.setTransactionSuccessful();}finally{db.endTransaction();}
    }
    @Override public synchronized org.json.JSONArray pendingCandidates(long now)throws Exception{org.json.JSONArray result=new org.json.JSONArray();try(Cursor rows=db.rawQuery("SELECT c.invite_id FROM device_pairing_candidates c JOIN device_pairing_invites i ON i.invite_id=c.invite_id WHERE c.state IN ('presented','confirmed') AND c.expires_at>? ORDER BY c.updated_at,c.invite_id LIMIT 8",new String[]{Long.toString(now)})){while(rows.moveToNext())result.put(candidate(rows.getString(0)));}return result;}
    @Override public synchronized org.json.JSONArray peers(int limit)throws Exception{if(limit<1||limit>128)throw new IllegalArgumentException("Bounded peer list required");org.json.JSONArray result=new org.json.JSONArray();try(Cursor rows=db.rawQuery("SELECT device_id,fingerprint,instance_id,authorization_epoch,revoked,updated_at FROM device_peers ORDER BY updated_at DESC,device_id LIMIT ?",new String[]{Integer.toString(limit)})){while(rows.moveToNext())result.put(new JSONObject().put("deviceId",rows.getString(0)).put("certificateFingerprint",rows.getString(1)).put("instanceId",rows.getString(2)).put("authorizationEpoch",rows.getLong(3)).put("revoked",rows.getInt(4)!=0).put("updatedAt",rows.getLong(5)));}return result;}
    public JSONObject workspaceSnapshot(long now)throws Exception{return workspaceSnapshot(now,"",64);}
    /** Native owner UI metadata only: no DER, invitation token, model secret or fabricated health. */
    public synchronized JSONObject workspaceSnapshot(long now,String afterDeviceId,int limit)throws Exception{
        if(limit<1||limit>64)throw new IllegalArgumentException("Workspace device page is limited to 64");String cursor=afterDeviceId==null?"":afterDeviceId;if(!cursor.isEmpty())DeviceProtocol.deviceId(cursor,"cursor");org.json.JSONArray devices=new org.json.JSONArray();boolean more=false;String next="";
        try(Cursor rows=db.rawQuery("SELECT device_id,fingerprint,instance_id,authorization_epoch,revoked,updated_at FROM device_peers WHERE device_id>? ORDER BY device_id LIMIT ?",new String[]{cursor,Integer.toString(limit+1)})){while(rows.moveToNext()){if(devices.length()>=limit){more=true;break;}String id=rows.getString(0);long epoch=rows.getLong(3);boolean revoked=rows.getInt(4)!=0;JSONObject peer=new JSONObject().put("deviceId",id).put("certificateFingerprint",rows.getString(1)).put("instanceId",rows.getString(2)).put("authorizationEpoch",epoch).put("revoked",revoked).put("updatedAt",rows.getLong(5)).put("revocation",revocationStatus(id));org.json.JSONArray scopes=new org.json.JSONArray();boolean moreScopes=false;
            try(Cursor grants=db.rawQuery("SELECT body_json,authorization_epoch,expires_at,revoked FROM device_grants WHERE peer_device_id=? ORDER BY grant_id LIMIT 33",new String[]{id})){while(grants.moveToNext()){if(scopes.length()>=32){moreScopes=true;break;}JSONObject grant=new JSONObject(grants.getString(0)),scope=new JSONObject().put("grantId",grant.getString("grantId")).put("authorizationEpoch",grants.getLong(1)).put("expiresAt",grants.getLong(2));for(String name:new String[]{"capabilities","toolNames","resources","allowDelegation","maxDelegationDepth","toolBudget","modelBudget","forwardingScope","historyRead"})if(grant.has(name))scope.put(name,grant.get(name));String state=revoked||grants.getInt(3)!=0?"revoked":grants.getLong(1)!=epoch?"stale_epoch":grants.getLong(2)<=now?"expired":"active";scope.put("state",state);if(grant.has("historyRead"))scope.put("historyState",revoked||grants.getInt(3)!=0?"revoked":grants.getLong(1)!=epoch?"stale_epoch":grant.getJSONObject("historyRead").getLong("expiresAt")<=now?"expired":"active");scopes.put(scope);}}
            peer.put("grantScopes",scopes).put("hasMoreGrantScopes",moreScopes);devices.put(peer);next=id;}}
        return new JSONObject().put("devices",devices).put("hasMore",more).put("nextCursor",more?next:"").put("asOf",now).put("source","original_task_store_device_trust");
    }
}
