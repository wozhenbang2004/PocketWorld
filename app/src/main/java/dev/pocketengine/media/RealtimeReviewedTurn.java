package dev.pocketengine.media;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.json.JSONObject;

/** Immutable native-owner review. A transcript's reported track is not speaker authentication. */
public final class RealtimeReviewedTurn {
    public final String commitId,sessionId,turnId,text,contentHash;
    public final long finalRevision;
    private final String segmentId,participantIdentity,trackSid,protocol;
    RealtimeReviewedTurn(String commitId,String sessionId,String turnId,long finalRevision,String text,String segmentId,String participantIdentity,String trackSid,String protocol){
        this.commitId=commitId;this.sessionId=sessionId;this.turnId=turnId;this.finalRevision=finalRevision;this.text=text;this.contentHash=hash(text);
        this.segmentId=segmentId;this.participantIdentity=participantIdentity;this.trackSid=trackSid;this.protocol=protocol;
    }
    public JSONObject source()throws Exception{return new JSONObject().put("kind","owner_reviewed_realtime").put("commitId",commitId).put("sessionId",sessionId).put("turnId",turnId).put("finalRevision",finalRevision)
        .put("segmentId",segmentId).put("participantIdentity",participantIdentity).put("trackSid",trackSid).put("protocol",protocol).put("contentHash",contentHash)
        .put("sourceKind","reported_local_microphone").put("identityVerified",false).put("reviewedBy","owner").put("spaceId","private");}
    static String hash(String text){try{byte[] hash=MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));StringBuilder value=new StringBuilder();for(byte b:hash)value.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return value.toString();}catch(Exception impossible){throw new IllegalStateException("SHA-256 unavailable",impossible);}}
    @Override public String toString(){return "RealtimeReviewedTurn["+commitId+", revision="+finalRevision+"]";}
}
