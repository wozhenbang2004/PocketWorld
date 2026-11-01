package dev.pocketengine.features.skills;

import java.util.Objects;
import org.json.JSONObject;

/** Host-owned provenance, not model input, evidence text, execution proof or a capability grant. */
public final class ExperienceOrigin {
    public enum DraftAuthor { MODEL, USER }
    public enum Status { ACTIVE, INACTIVE, DELETED, FORGOTTEN }

    public static final class Ref {
        public final String recordId, ownerId, spaceId;
        public final long revision;
        public Ref(String recordId,long revision,String ownerId,String spaceId) {
            if(recordId==null||!recordId.matches("[\\p{L}\\p{N}_.:/@-]{1,240}"))throw new IllegalArgumentException("经验标识无效");
            if(revision<1)throw new IllegalArgumentException("经验版本必须为正整数");
            if(ownerId==null||!ownerId.matches("[\\p{L}\\p{N}_.:/@-]{1,160}")||spaceId==null||!spaceId.matches("[\\p{L}\\p{N}_.:/@-]{1,200}"))throw new IllegalArgumentException("经验空间无效");
            this.recordId=recordId;this.revision=revision;this.ownerId=ownerId;this.spaceId=spaceId;
        }
        boolean sameIdentity(Ref other){return other!=null&&recordId.equals(other.recordId)&&ownerId.equals(other.ownerId)&&spaceId.equals(other.spaceId);}
        boolean sameVersion(Ref other){return sameIdentity(other)&&revision==other.revision;}
        JSONObject toJson()throws org.json.JSONException {return new JSONObject().put("recordId",recordId).put("revision",revision).put("ownerId",ownerId).put("spaceId",spaceId);}
        static Ref fromJson(JSONObject value)throws org.json.JSONException {
            Object revision=value.get("revision");
            if(!(revision instanceof Integer||revision instanceof Long))throw new IllegalArgumentException("经验版本必须为整数");
            return new Ref(value.getString("recordId"),((Number)revision).longValue(),value.getString("ownerId"),value.getString("spaceId"));
        }
    }

    /** The resolver must check the accepted procedure AND its own underlying source/forget fences. */
    public static final class State {
        public final Ref current;
        public final Status status;
        public final boolean acceptedProcedure;
        public State(Ref current,Status status,boolean acceptedProcedure){this.current=Objects.requireNonNull(current);this.status=Objects.requireNonNull(status);this.acceptedProcedure=acceptedProcedure;}
    }
    /** Read only. Return null for unavailable/inaccessible state; explicit tombstones permit cleanup. */
    public interface ExperienceResolver {
        State resolve(String authenticatedOwnerId,String authenticatedSpaceId,String recordId)throws Exception;
    }

    final Ref ref;
    final DraftAuthor author;
    public ExperienceOrigin(Ref ref,DraftAuthor author){this.ref=Objects.requireNonNull(ref);this.author=Objects.requireNonNull(author);}
    JSONObject toJson()throws org.json.JSONException {return new JSONObject().put("kind","accepted_procedure").put("ref",ref.toJson())
            .put("draftAuthor",author.name()).put("generated",author==DraftAuthor.MODEL).put("draftStatus",author==DraftAuthor.MODEL?"generated_draft":"user_draft")
            .put("validationStatus","unverified").put("executionVerified",false);}
    static ExperienceOrigin fromJson(JSONObject value)throws org.json.JSONException {
        if(!"accepted_procedure".equals(value.getString("kind")))throw new IllegalArgumentException("未知技能经验来源");
        return new ExperienceOrigin(Ref.fromJson(value.getJSONObject("ref")),DraftAuthor.valueOf(value.getString("draftAuthor")));
    }
}
