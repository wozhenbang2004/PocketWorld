package dev.pocketengine.features.assistant;

import android.util.AtomicFile;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

/** Bounded native UI file only. Serial disk work survives an Activity detach, never sends a message. */
public final class OwnerComposerStore {
    private static final ThreadPoolExecutor DISK=new ThreadPoolExecutor(0,1,30,TimeUnit.SECONDS,
            new java.util.concurrent.ArrayBlockingQueue<>(8),task->{Thread thread=new Thread(task,"owner-composer-disk");thread.setDaemon(true);return thread;});
    private static final String DETACHED_HISTORY="detachedHistoryPositions";
    private static final int MAX_DETACHED_POSITIONS=8,MAX_DETACHED_BYTES=4096;
    private final AtomicFile file;
    private volatile List<OwnerDetachedHistoryPosition> confirmedDetachedHistory=List.of();
    private long persistedRevision;
    private boolean loaded,healthy=true;
    private String confirmedFingerprint="",attemptedFingerprint="";
    public OwnerComposerStore(File privateFiles){this(new AtomicFile(new File(privateFiles,"owner-composer-v1.json")));}
    OwnerComposerStore(AtomicFile original){file=java.util.Objects.requireNonNull(original);}
    public CompletableFuture<OwnerComposerState> load(){return submit(()->{
        JSONObject existing=read();OwnerComposerState state=existing==null?OwnerComposerState.empty():OwnerComposerState.fromJson(existing);
        persistedRevision=existing==null?0:state.revision();confirmedFingerprint=fingerprint(existing);attemptedFingerprint="";healthy=true;loaded=true;
        state.confirmLoadedReportFollowUp();confirmedDetachedHistory=readDetachedHistory(existing,System.currentTimeMillis());return state;
    });}
    /** Capture metadata/text now; actual bounded write is serialized off the UI thread. */
    public CompletableFuture<Void> save(OwnerComposerState state){return save(state,null);}
    public CompletableFuture<Void> save(OwnerComposerState state,OwnerDetachedHistoryPosition position){
        return commit(state,false,null,null,position,false).thenApply(ignored->null);
    }
    /** A reader-only explicit Back may update metadata only if the unchanged composer is already confirmed. */
    public CompletableFuture<Boolean> checkpointDetachedHistory(OwnerComposerState state,OwnerDetachedHistoryPosition position){
        return commit(state,false,null,null,java.util.Objects.requireNonNull(position),true);
    }
    public OwnerDetachedHistoryPosition detachedHistoryPosition(String conversation){
        long now=System.currentTimeMillis();
        for(var position:confirmedDetachedHistory)if(position.conversationId().equals(conversation)&&position.fresh(now))return position;
        return null;
    }
    /** Only an explicit UI action with an in-process proof that send was never called may use
     * this. It relaxes no disk CAS: only this store's exact last-good/attempted bytes can reopen. */
    public CompletableFuture<Void> saveAfterLocalUnsentFailure(OwnerComposerState state){return commit(state,true,null,null,null,false).thenApply(ignored->null);}
    /** Only a verified transition of the same on-disk PREPARED input may fence or release it. */
    public CompletableFuture<Void> saveFirstChatTransition(OwnerComposerState state,OwnerComposerState.Pending before,OwnerComposerState.Pending after){
        return commit(state,false,java.util.Objects.requireNonNull(before),java.util.Objects.requireNonNull(after),null,false).thenApply(ignored->null);
    }
    private CompletableFuture<Boolean> commit(OwnerComposerState state,boolean localUnsentRetry,
            OwnerComposerState.Pending before,OwnerComposerState.Pending after,OwnerDetachedHistoryPosition position,boolean metadataOnly){
        final OwnerComposerState.StoreSnapshot captured;try{captured=state.storeSnapshot();}catch(Exception invalid){CompletableFuture<Boolean> failure=new CompletableFuture<>();failure.completeExceptionally(invalid);return failure;}
        final JSONObject snapshot=captured.document;
        return submit(()->{
            if(localUnsentRetry&&loaded&&!healthy){JSONObject disk=read();String actual=fingerprint(disk);if(!actual.equals(confirmedFingerprint)&&!actual.equals(attemptedFingerprint))throw new IllegalStateException("原草稿文件已被其他窗口改变，未以重试覆盖；请保留本页输入");persistedRevision=disk==null?0:OwnerComposerState.fromJson(disk).revision();confirmedFingerprint=actual;healthy=true;}
            if(!loaded||!healthy)throw new IllegalStateException("原草稿尚未读取或上次保存未确认，未覆盖本机文件");
            OwnerComposerState proposed=OwnerComposerState.fromJson(snapshot);
            long next=proposed.revision();
            if(metadataOnly&&next!=persistedRevision)return false;
            if(next<persistedRevision)throw new IllegalStateException("原草稿保存顺序已变化，未发送新输入");
            // Existing composer size/CAS/fingerprints are checked without optional UI metadata.
            long coreBytes=jsonBudget(snapshot,OwnerComposerState.MAX_STATE_BYTES);
            String core=snapshot.toString();if(!StandardCharsets.UTF_8.newEncoder().canEncode(core))throw new IllegalArgumentException("草稿含不完整Unicode字符，未隐式替换或发送");
            JSONObject current=read();OwnerComposerState disk=current==null?null:OwnerComposerState.fromJson(current);long actual=disk==null?0:disk.revision();
            if(actual!=persistedRevision)throw new IllegalStateException("另一原输入窗口已更新草稿；本页文字保留，未覆盖新版本或自动发送");
            if(before!=null&&!fingerprint(current).equals(confirmedFingerprint))throw new IllegalStateException("原首次输入的已确认快照已变化");
            proposed.requireFirstChatTransitions(disk,before,after);
            proposed.requireReportFollowUpTransition(disk,captured);
            boolean sameCore=next==persistedRevision;
            if(sameCore&&(disk==null||!disk.toJson().toString().equals(core)))throw new IllegalStateException("相同草稿版本的内容不同，未覆盖或发送");
            long now=System.currentTimeMillis();
            List<OwnerDetachedHistoryPosition> stored=readDetachedHistory(current,now);
            // A bookmark-only rewrite preserves even other optional fields in the
            // actual disk envelope. It must not canonicalize or drop newer app metadata.
            JSONObject keptCore=null;
            long envelopeBudget=coreBytes;
            if(sameCore&&position!=null){
                keptCore=new JSONObject(current.toString());
                detachedHistoryContainer(keptCore).remove(DETACHED_HISTORY);
                try{envelopeBudget=jsonBudget(keptCore,OwnerComposerState.MAX_STATE_BYTES);}
                catch(IllegalStateException noRoom){return false;}
            }
            List<OwnerDetachedHistoryPosition> positions=fitDetachedHistory(stored,position,now,OwnerComposerState.MAX_STATE_BYTES-envelopeBudget);
            if(sameCore&&(position==null||positions.equals(stored)||!containsPlace(positions,position))){
                // An unrelated warm no-op is not an acknowledgment of a previously uncertain bookmark.
                var stillConfirmed=new ArrayList<OwnerDetachedHistoryPosition>(confirmedDetachedHistory);
                stillConfirmed.retainAll(stored);confirmedDetachedHistory=List.copyOf(stillConfirmed);
                state.confirmStoredReportFollowUp(captured);return containsPlace(stillConfirmed,position);
            }
            String encoded=core;
            if(!positions.isEmpty()){
                JSONObject updated=keptCore==null?new JSONObject(core):keptCore;
                putDetachedHistory(updated,positions);encoded=updated.toString();
            }
            byte[] bytes=encoded.getBytes(StandardCharsets.UTF_8);
            if(bytes.length>OwnerComposerState.MAX_STATE_BYTES)throw new IllegalStateException("私聊草稿超过1MiB本机上限；原文件保留，新输入尚未保存或发送，请缩小草稿后重试");
            if(!sameCore)attemptedFingerprint=fingerprint(snapshot);
            FileOutputStream out=null;
            try{
                out=file.startWrite();out.write(bytes);file.finishWrite(out);out=null;
                byte[] actualBytes=readBytes();
                try{if(actualBytes==null||!java.util.Arrays.equals(bytes,actualBytes))throw new IllegalStateException("原草稿原子保存未能逐字节确认；未发送新输入");}
                finally{if(actualBytes!=null)java.util.Arrays.fill(actualBytes,(byte)0);}
                if(!sameCore){persistedRevision=next;confirmedFingerprint=attemptedFingerprint;attemptedFingerprint="";}
                confirmedDetachedHistory=positions;state.confirmStoredReportFollowUp(captured);return containsPlace(positions,position);
            }catch(Exception failed){
                if(!sameCore){healthy=false;throw failed;}
                if(out!=null){file.failWrite(out);out=null;}
                // Do not claim this position or retry. Keep ordinary drafts usable only
                // when the exact same canonical core can still be read after this attempt.
                confirmedDetachedHistory=List.of();
                try{JSONObject kept=read();healthy=kept!=null&&OwnerComposerState.fromJson(kept).toJson().toString().equals(core);}
                catch(Exception uncertain){healthy=false;}
                if(healthy)return false;
                throw failed;
            }finally{if(out!=null)file.failWrite(out);java.util.Arrays.fill(bytes,(byte)0);}
        });
    }
    /** Stored within the base composer object, without relaxing the strict outer report wrapper. */
    private static JSONObject detachedHistoryContainer(JSONObject value){
        JSONObject inner=value.optJSONObject("composer");return inner==null?value:inner;
    }
    private static List<OwnerDetachedHistoryPosition> readDetachedHistory(JSONObject value,long now){
        if(value==null)return List.of();JSONArray rows=detachedHistoryContainer(value).optJSONArray(DETACHED_HISTORY);
        if(rows==null||rows.length()>MAX_DETACHED_POSITIONS)return List.of();
        var result=new ArrayList<OwnerDetachedHistoryPosition>();var seen=new java.util.HashSet<String>();
        for(int i=0;i<rows.length();i++)try{
            OwnerDetachedHistoryPosition row=OwnerDetachedHistoryPosition.fromJson(rows.getJSONObject(i));
            if(!seen.add(row.conversationId()))return List.of();
            if(row.fresh(now))result.add(row);
        }catch(Exception invalid){return List.of();}
        return List.copyOf(result);
    }
    private static List<OwnerDetachedHistoryPosition> fitDetachedHistory(List<OwnerDetachedHistoryPosition> stored,
            OwnerDetachedHistoryPosition position,long now,long available)throws Exception{
        var result=new ArrayList<>(stored);
        if(position!=null&&position.fresh(now)&&!containsPlace(stored,position)&&stored.stream().noneMatch(old->
                old.conversationId().equals(position.conversationId())&&old.savedAtMs()>=position.savedAtMs())){
            result.removeIf(old->old.conversationId().equals(position.conversationId()));result.add(position);
        }
        result.sort(java.util.Comparator.comparingLong(OwnerDetachedHistoryPosition::savedAtMs).reversed());
        while(result.size()>MAX_DETACHED_POSITIONS)result.remove(result.size()-1);
        long limit=Math.min(available,MAX_DETACHED_BYTES);
        while(!result.isEmpty()){
            JSONObject optional=new JSONObject();putDetachedHistory(optional,result);
            try{jsonBudget(optional,limit);break;}catch(IllegalStateException noRoom){result.remove(result.size()-1);}
        }
        return List.copyOf(result);
    }
    private static void putDetachedHistory(JSONObject document,List<OwnerDetachedHistoryPosition> positions)throws Exception{
        JSONArray rows=new JSONArray();for(var position:positions)rows.put(position.json());
        detachedHistoryContainer(document).put(DETACHED_HISTORY,rows);
    }
    private static boolean containsPlace(List<OwnerDetachedHistoryPosition> positions,OwnerDetachedHistoryPosition expected){
        if(expected==null)return false;for(var position:positions)if(position.samePlace(expected))return true;return false;
    }
    private byte[] readBytes()throws Exception{
        try(var input=file.openRead();var output=new ByteArrayOutputStream()){
            byte[] buffer=new byte[8192];int count,total=0;while((count=input.read(buffer))!=-1){total+=count;if(total>OwnerComposerState.MAX_STATE_BYTES)throw new IllegalStateException("原私聊草稿超过1MiB，未截断、清空或覆盖原文件");output.write(buffer,0,count);}
            return output.toByteArray();
        }catch(FileNotFoundException absent){File base=file.getBaseFile(),parent=base.getParentFile();if(base.exists()||new File(base.getPath()+".bak").exists()||new File(base.getPath()+".new").exists()||parent==null||!parent.isDirectory()||!parent.canRead())throw absent;return null;}
    }
    private JSONObject read()throws Exception{
        byte[] bytes=readBytes();if(bytes==null)return null;
        try{String original=StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString();JSONTokener parser=new JSONTokener(original);Object parsed=parser.nextValue();if(!(parsed instanceof JSONObject)||parser.nextClean()!=0)throw new IllegalStateException("原草稿文件不可核对，请保留原文件；没有自动重置");return (JSONObject)parsed;}
        finally{java.util.Arrays.fill(bytes,(byte)0);}
    }
    private static String fingerprint(JSONObject value)throws Exception{
        if(value==null)return "absent";byte[] bytes=OwnerComposerState.fromJson(value).toJson().toString().getBytes(StandardCharsets.UTF_8);
        try{byte[] digest=java.security.MessageDigest.getInstance("SHA-256").digest(bytes);StringBuilder hex=new StringBuilder();for(byte item:digest)hex.append(String.format(java.util.Locale.ROOT,"%02x",item&255));return hex.toString();}finally{java.util.Arrays.fill(bytes,(byte)0);}
    }
    /** Preflight without allocating an oversized encoded snapshot; a few escaped characters
     * are deliberately charged conservatively. This is a local UI-byte budget, not tokens. */
    private static long jsonBudget(Object value,long left)throws Exception{
        long used=0;
        if(value instanceof JSONObject){JSONObject object=(JSONObject)value;used=2;var keys=object.keys();while(keys.hasNext()){String key=keys.next();used+=jsonBudget(key,left-used)+2;used+=jsonBudget(object.get(key),left-used);}}
        else if(value instanceof org.json.JSONArray){var array=(org.json.JSONArray)value;used=2;for(int i=0;i<array.length();i++){used+=jsonBudget(array.get(i),left-used)+1;}}
        else if(value instanceof String){String text=(String)value;used=2;for(int i=0;i<text.length();i++){char c=text.charAt(i);if(c=='"'||c=='\\'||c=='/'||c<32||c=='\u2028'||c=='\u2029')used+=6;else if(Character.isHighSurrogate(c)){if(i+1>=text.length()||!Character.isLowSurrogate(text.charAt(++i)))throw new IllegalArgumentException("草稿含不完整Unicode字符，未替换或发送");used+=4;}else if(Character.isLowSurrogate(c))throw new IllegalArgumentException("草稿含不完整Unicode字符，未替换或发送");else used+=c<128?1:c<2048?2:3;if(used>left)break;}}
        else used=value==JSONObject.NULL?4:value.toString().length();
        if(used>left||left<0)throw new IllegalStateException("草稿的保守JSON编码预算超过1MiB；原文件保留，未发送，请缩小未发送草稿或附件引用");return used;
    }
    private interface Work<T>{T run()throws Exception;}
    private static <T> CompletableFuture<T> submit(Work<T> work){CompletableFuture<T> result=new CompletableFuture<>();try{DISK.execute(()->{try{result.complete(work.run());}catch(Exception failed){result.completeExceptionally(failed);}});}catch(java.util.concurrent.RejectedExecutionException full){result.completeExceptionally(new IllegalStateException("草稿磁盘队列已满；未自动发送或丢弃输入",full));}return result;}
}
