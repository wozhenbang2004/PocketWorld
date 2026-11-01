package dev.pocketengine.features.memory;

import java.util.Objects;
import org.json.JSONArray;
import org.json.JSONObject;

/** Identity/version of host-owned evidence, never a permission or an embedded evidence body. */
public final class SourceRef {
    public final String kind,sourceId,ownerId,spaceId;
    public final long revision;

    public SourceRef(String kind,String sourceId,long revision,String ownerId,String spaceId) {
        if(!"message".equals(kind)&&!"task_evidence".equals(kind))throw new IllegalArgumentException("未知外部记忆来源类型");
        if(sourceId==null||!sourceId.matches("[\\p{L}\\p{N}_.:/@-]{1,240}"))throw new IllegalArgumentException("来源标识无效");
        if(revision<1)throw new IllegalArgumentException("来源版本必须为正整数");
        if(ownerId==null||!ownerId.matches("[\\p{L}\\p{N}_.:/@-]{1,160}")||spaceId==null||!spaceId.matches("[\\p{L}\\p{N}_.:/@-]{1,200}"))
            throw new IllegalArgumentException("来源空间标识无效");
        this.kind=kind;this.sourceId=sourceId;this.revision=revision;this.ownerId=ownerId;this.spaceId=spaceId;
    }

    public static SourceRef fromJson(JSONObject value) {
        if(value==null)throw new IllegalArgumentException("必须提供来源引用");
        return new SourceRef(string(value,"kind"),string(value,"sourceId"),integer(value,"revision",1),string(value,"ownerId"),string(value,"spaceId"));
    }
    public JSONObject toJson()throws Exception {
        return new JSONObject().put("kind",kind).put("sourceId",sourceId).put("revision",revision).put("ownerId",ownerId).put("spaceId",spaceId);
    }
    String key(){return new JSONArray().put(ownerId).put(spaceId).put(kind).put(sourceId).toString();}
    boolean belongsTo(MemoryBook.Scope scope){return ownerId.equals(scope.ownerId)&&spaceId.equals(scope.spaceId);}
    static long integer(JSONObject value,String key,long minimum) {
        Object number=value.opt(key);
        if(!(number instanceof Byte||number instanceof Short||number instanceof Integer||number instanceof Long)||((Number)number).longValue()<minimum)
            throw new IllegalArgumentException(key+"必须为不小于"+minimum+"的整数");
        return ((Number)number).longValue();
    }
    private static String string(JSONObject value,String key) {
        Object text=value.opt(key);if(!(text instanceof String))throw new IllegalArgumentException(key+"必须为文字");return (String)text;
    }
    @Override public boolean equals(Object other) {
        if(!(other instanceof SourceRef))return false;SourceRef ref=(SourceRef)other;
        return revision==ref.revision&&kind.equals(ref.kind)&&sourceId.equals(ref.sourceId)&&ownerId.equals(ref.ownerId)&&spaceId.equals(ref.spaceId);
    }
    @Override public int hashCode(){return Objects.hash(kind,sourceId,revision,ownerId,spaceId);}

    /** Current evidence state supplied by the authenticated host, including deletion tombstones. */
    public static final class State {
        public final SourceRef ref;
        public final boolean deleted;
        public State(SourceRef ref,boolean deleted){this.ref=Objects.requireNonNull(ref);this.deleted=deleted;}
    }
    /**
     * Resolve from the host's authoritative message/task store, not from the submitted payload.
     * Scope comes from authenticated host identity; return null for missing/inaccessible evidence.
     * Revisions must increase for content changes/restoration, and IDs must not be reused.
     */
    public interface Resolver {
        State resolve(MemoryBook.Scope scope,String kind,String sourceId)throws Exception;
    }
}
