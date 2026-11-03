package dev.pocketengine.ui.web;

import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.UUID;
import java.util.function.LongSupplier;
import org.json.JSONObject;

/** Short-lived native review references only. Account storage and execution remain in their native hosts. */
public final class PocketWebAccountReviews implements AutoCloseable {
    public enum Kind { MODEL, LOGOUT }
    private static final long LIFETIME_MILLIS=120_000;
    private static final int MAX_REVIEWS=24;
    public static final class Entry {
        private final PocketWebAccountReviews owner;
        private final Kind kind;
        private final String payload;
        private final long generation,revision,expires;
        private boolean claimed;
        private Entry(PocketWebAccountReviews owner,Kind kind,JSONObject payload,long revision,long generation,long expires){
            this.owner=owner;this.kind=kind;this.payload=payload.toString();this.revision=revision;this.generation=generation;this.expires=expires;
        }
        public long selectionRevision(){return revision;}
        public JSONObject payload()throws Exception{return new JSONObject(payload);}
    }
    private final LongSupplier now;
    private final LinkedHashMap<String,Entry> entries=new LinkedHashMap<>();
    private long generation;
    private boolean closed;
    public PocketWebAccountReviews(LongSupplier monotonicMillis){now=Objects.requireNonNull(monotonicMillis);}
    public synchronized long generation(){return generation;}
    public synchronized String issue(Kind kind,JSONObject nativePayload,long selectionRevision)throws Exception{
        if(closed||selectionRevision<0)throw new IllegalStateException("原账号审核窗口已关闭");
        long time=now.getAsLong();entries.values().removeIf(entry->entry.expires<=time);
        while(entries.size()>=MAX_REVIEWS)entries.remove(entries.keySet().iterator().next());
        String handle=UUID.randomUUID().toString();
        entries.put(handle,new Entry(this,Objects.requireNonNull(kind),Objects.requireNonNull(nativePayload),selectionRevision,generation,Math.addExact(time,LIFETIME_MILLIS)));
        return handle;
    }
    /** Consume before reserving the shared model mutation. A failed reservation needs a fresh review. */
    public synchronized Entry take(String handle,Kind expected,long currentSelectionRevision){
        Entry entry=entries.remove(handle);
        if(entry==null||closed||entry.kind!=expected||entry.generation!=generation||entry.expires<=now.getAsLong()
                ||entry.revision!=currentSelectionRevision)throw new IllegalStateException("原账号审核已变化、使用或过期，请重新核对");
        return entry;
    }
    /** Called inside the actual shared mutation, immediately before native account I/O. */
    public synchronized JSONObject claim(Entry entry,Kind expected)throws Exception{
        requireCurrent(entry,expected);
        if(entry.claimed)throw new IllegalStateException("原账号操作已经领取");
        entry.claimed=true;return entry.payload();
    }
    public synchronized void requireCurrent(Entry entry,Kind expected){
        if(entry==null||entry.owner!=this||closed||entry.kind!=expected||entry.generation!=generation||entry.expires<=now.getAsLong())
            throw new IllegalStateException("原账号审核窗口已关闭或已经过期");
    }
    public synchronized void invalidate(){generation++;entries.clear();}
    @Override public synchronized void close(){closed=true;invalidate();}
}
