package dev.pocketengine.features.assistant;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.CancellationSignal;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.memory.ConversationHistory;
import dev.pocketengine.features.memory.SourceRef;
import dev.pocketengine.features.memory.TaskMemoryTrace;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.*;
import java.util.concurrent.Callable;
import org.json.JSONArray;
import org.json.JSONObject;

/** Original TaskStore connection/monitor only. No second DB, body mirror, model command or grant. */
public final class ConversationRepository {
    public static final class ReadLimited extends Exception {public ReadLimited(String reason){super(reason);}}
    public static final int MAX_ROWS=100,MAX_BYTES=1_048_576;
    static final java.util.concurrent.ScheduledThreadPoolExecutor DEADLINES=deadlines();
    private static java.util.concurrent.ScheduledThreadPoolExecutor deadlines(){
        var timer=new java.util.concurrent.ScheduledThreadPoolExecutor(1,action->{Thread thread=new Thread(action,"original-history-read-deadline");thread.setDaemon(true);return thread;});
        timer.setRemoveOnCancelPolicy(true);timer.setKeepAliveTime(1,java.util.concurrent.TimeUnit.SECONDS);timer.allowCoreThreadTimeOut(true);return timer;
    }
    /** No body/JSON/cache. One SQLite statement is the source-state linearization point.
     * This is deliberately callable while MemoryBook is held: no TaskStore/storage monitor,
     * callback or write is acquired here. Cancellation is best effort, not a hard I/O claim. */
    public static final class SourceMetadata {
        public final boolean databaseAuthority;public final SourceRef.State state;
        private SourceMetadata(boolean authority,SourceRef.State value){databaseAuthority=authority;state=value;}
    }
    /** Lock-free bounded original SQLite proof. An assistant message/document/summary cannot
     * become a user utterance by copying the same words or assigning itself a source label. */
    public void requireUserSaidSpan(SourceRef ref,int offset,int chars,String sha,String claimed)throws Exception {requireUserSaidSpan(ref,offset,chars,sha,claimed,null);}
    public void requireUserSaidSpan(SourceRef ref,int offset,int chars,String sha,String claimed,Long assertedAt)throws Exception {
        if(!healthy||!active()||!"message".equals(ref.kind)||!"owner".equals(ref.ownerId)||!"private".equals(ref.spaceId)||offset<0||chars<1||chars>24000)throw new SecurityException("原主人发言证明不可用");
        CancellationSignal signal=new CancellationSignal();long started=System.nanoTime();var deadline=DEADLINES.schedule(signal::cancel,500,java.util.concurrent.TimeUnit.MILLISECONDS);
        try(Cursor row=db.rawQuery("SELECT s.role,s.revision,s.deleted,s.retired_through,s.context_retired,coalesce(c.deleted,1),m.body,m.body_bytes,m.body_sha,m.revision,m.at_ms FROM assistant_sources s JOIN meta a ON a.key='assistant_history_generation' AND a.value=s.generation LEFT JOIN assistant_conversations c ON c.generation=s.generation AND c.conversation_id=s.conversation_id LEFT JOIN assistant_messages m ON m.generation=s.generation AND m.source_id=s.source_id WHERE s.source_id=? AND m.body_bytes<=1048576 LIMIT 1",new String[]{ref.sourceId},signal)){
            if(!row.moveToFirst()||!"user".equals(row.getString(0))||row.getLong(1)!=ref.revision||row.getInt(2)!=0||row.getLong(3)>=ref.revision||row.getInt(4)!=0||row.getInt(5)!=0||row.isNull(6)||row.getLong(9)!=ref.revision)throw new SecurityException("不是确切仍有效的原主人发言");
            if(assertedAt!=null&&(row.isNull(10)||row.getLong(10)!=assertedAt))throw new SecurityException("陈述时间不是原消息实际时间，未知不能补为今天");
            String text=row.getString(6);long end=(long)offset+chars;if(end>text.length()||offset>0&&Character.isLowSurrogate(text.charAt(offset))&&Character.isHighSurrogate(text.charAt(offset-1))||end<text.length()&&Character.isLowSurrogate(text.charAt((int)end))&&Character.isHighSurrogate(text.charAt((int)end-1)))throw new SecurityException("主人原话范围不完整");
            byte[] bytes=text.getBytes(StandardCharsets.UTF_8);String span=text.substring(offset,(int)end);if(bytes.length!=row.getLong(7)||!("sha256:"+shaBytes(bytes)).equals(row.getString(8))||!shaBytes(span.getBytes(StandardCharsets.UTF_8)).equals(sha)||!span.equals(claimed))throw new SecurityException("主人原话、hash或声称文字不一致，不能把推断当明确原话");
            if(!healthy||signal.isCanceled()||System.nanoTime()-started>500_000_000L)throw new SecurityException("原作者/原话证明超时，未采用模型自报标签");
        }finally{deadline.cancel(false);}
    }
    public SourceMetadata resolveMetadata(String sourceId)throws Exception{
        if(sourceId==null||sourceId.length()>240)throw new IllegalArgumentException("来源标识无效");
        if(!healthy)throw new IllegalStateException("原文权威状态不确定，未采用缓存来源");
        CancellationSignal signal=new CancellationSignal();long started=System.nanoTime();
        var deadline=DEADLINES.schedule(signal::cancel,500,java.util.concurrent.TimeUnit.MILLISECONDS);
        try(Cursor row=db.rawQuery("SELECT a.value,s.revision,s.deleted,coalesce(c.deleted,1) FROM (SELECT value FROM meta WHERE key='assistant_history_generation') a LEFT JOIN assistant_sources s ON s.generation=a.value AND s.source_id=? LEFT JOIN assistant_conversations c ON c.generation=s.generation AND c.conversation_id=s.conversation_id LIMIT 1",new String[]{sourceId},signal)){
            boolean found=row.moveToFirst();
            if(!healthy||signal.isCanceled()||System.nanoTime()-started>500_000_000L)throw new IllegalStateException("来源metadata读取超时或权威不确定，未授权旧来源");
            if(!found)return new SourceMetadata(false,null);if(row.isNull(1))return new SourceMetadata(true,null);long revision=row.getLong(1);if(revision<1)throw new IllegalStateException("原来源实际修订无效");
            return new SourceMetadata(true,new SourceRef.State(new SourceRef("message",sourceId,revision,"owner","private"),row.getInt(2)!=0||row.getInt(3)!=0));
        }finally{deadline.cancel(false);}
    }
    private final SQLiteDatabase db;private final Object monitor;private final java.util.function.LongSupplier monotonicNanos;private volatile String generation;private volatile boolean healthy=true;
    public ConversationRepository(SQLiteDatabase original,Object originalMonitor){this(original,originalMonitor,System::nanoTime);}
    /** Test-only native clock seam; production always uses the platform monotonic clock. */
    ConversationRepository(SQLiteDatabase original,Object originalMonitor,java.util.function.LongSupplier nanos){db=Objects.requireNonNull(original);monitor=Objects.requireNonNull(originalMonitor);monotonicNanos=Objects.requireNonNull(nanos);synchronized(monitor){reloadAuthority();}}
    public static void createSchema(SQLiteDatabase d){
        d.execSQL("CREATE TABLE IF NOT EXISTS assistant_conversations(generation TEXT NOT NULL,conversation_id TEXT NOT NULL,header_json TEXT NOT NULL,updated_at INTEGER NOT NULL,deleted INTEGER NOT NULL DEFAULT 0,delete_reason TEXT NOT NULL DEFAULT '',PRIMARY KEY(generation,conversation_id))");
        d.execSQL("CREATE INDEX IF NOT EXISTS assistant_conversations_page ON assistant_conversations(generation,deleted,updated_at,conversation_id)");
        d.execSQL("CREATE TABLE IF NOT EXISTS assistant_sources(generation TEXT NOT NULL,source_id TEXT NOT NULL,conversation_id TEXT NOT NULL,revision INTEGER NOT NULL,role TEXT NOT NULL,deleted INTEGER NOT NULL,unavailable_reason TEXT NOT NULL,retired_through INTEGER NOT NULL DEFAULT 0,retirement_reason TEXT NOT NULL DEFAULT '',context_retired INTEGER NOT NULL DEFAULT 0,source_json TEXT NOT NULL,client_message_id TEXT,native_reply_id TEXT,PRIMARY KEY(generation,source_id))");
        d.execSQL("CREATE INDEX IF NOT EXISTS assistant_sources_conversation ON assistant_sources(generation,conversation_id,deleted)");
        d.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS assistant_sources_client ON assistant_sources(generation,client_message_id) WHERE client_message_id IS NOT NULL");
        d.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS assistant_sources_reply ON assistant_sources(generation,native_reply_id) WHERE native_reply_id IS NOT NULL");
        d.execSQL("CREATE TABLE IF NOT EXISTS assistant_messages(generation TEXT NOT NULL,source_id TEXT NOT NULL,conversation_id TEXT NOT NULL,sequence INTEGER NOT NULL,revision INTEGER NOT NULL,body TEXT,body_bytes INTEGER NOT NULL,body_sha TEXT NOT NULL,meta_json TEXT NOT NULL,at_ms INTEGER,PRIMARY KEY(generation,source_id),UNIQUE(generation,conversation_id,sequence))");
        d.execSQL("CREATE INDEX IF NOT EXISTS assistant_messages_page ON assistant_messages(generation,conversation_id,sequence)");
        d.execSQL("CREATE TABLE IF NOT EXISTS assistant_import_documents(generation TEXT NOT NULL,source_sha TEXT NOT NULL,source_bytes INTEGER NOT NULL,source_format TEXT NOT NULL,redacted INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(generation,source_sha))");
        d.execSQL("CREATE TABLE IF NOT EXISTS assistant_import_fragments(generation TEXT NOT NULL,source_sha TEXT NOT NULL,byte_offset INTEGER NOT NULL,byte_length INTEGER NOT NULL,conversation_id TEXT,record_id TEXT,data BLOB,PRIMARY KEY(generation,source_sha,byte_offset))");
        d.execSQL("CREATE TABLE IF NOT EXISTS assistant_import_records(generation TEXT NOT NULL,record_id TEXT NOT NULL,conversation_id TEXT NOT NULL,sequence INTEGER NOT NULL,record_version INTEGER NOT NULL,historical_revision INTEGER,at_ms INTEGER,role TEXT NOT NULL,source_sha TEXT NOT NULL,byte_offset INTEGER NOT NULL,byte_length INTEGER NOT NULL,fragment_sha TEXT NOT NULL,deleted INTEGER NOT NULL DEFAULT 0,unavailable_reason TEXT NOT NULL DEFAULT '',PRIMARY KEY(generation,record_id),UNIQUE(generation,conversation_id,sequence))");
        d.execSQL("CREATE INDEX IF NOT EXISTS assistant_import_records_page ON assistant_import_records(generation,conversation_id,sequence)");
    }
    private void reloadAuthority(){try(Cursor row=db.rawQuery("SELECT (SELECT value FROM meta WHERE key='assistant_history_authority'),(SELECT value FROM meta WHERE key='assistant_history_generation')",null)){
        row.moveToFirst();String actual=row.isNull(0)?null:new JSONObject(row.getString(0)).getString("generation"),typed=row.isNull(1)?null:row.getString(1);
        if(!Objects.equals(actual,typed)||actual!=null&&actual.isEmpty())throw new IllegalStateException("原文权威的typed generation不一致");generation=actual;
    }catch(Exception bad){healthy=false;throw new IllegalStateException("原始历史权威状态无法恢复，未回退旧文件",bad);}}
    public boolean active(){if(!healthy)throw new IllegalStateException("原始历史存储结果不确定，已停止读取/写入");return generation!=null;}
    private String activeGeneration(){if(!active())throw new IllegalStateException("旧对话仍待主人明确迁移；未切换原文权威");return generation;}
    public <T>T read(Callable<T> action)throws Exception{synchronized(monitor){activeGeneration();return action.call();}}
    public <T>T transaction(Callable<T> action)throws Exception{synchronized(monitor){activeGeneration();db.beginTransaction();try{T value=action.call();db.setTransactionSuccessful();return value;}finally{try{db.endTransaction();}catch(Exception uncertain){healthy=false;throw uncertain;}}}}
    public void noteLegacyFilePresent(){synchronized(monitor){ContentValues value=new ContentValues();value.put("value","0");db.update("meta",value,"key='assistant_history_new_install'",null);}}
    public void recordImportProvenance(JSONObject facts)throws Exception{
        if(!Thread.holdsLock(monitor)||!db.inTransaction())throw new SecurityException("导入来源条件只能随原激活事务保存");JSONObject marker=authority().put("originalImportCondition",facts.getString("sourceCondition")).put("originalImportSourceBytes",facts.getLong("sourceBytes")).put("legacyImportPerformed",!"absent".equals(facts.getString("sourceCondition"))).put("preVersion2ProvenanceKnown",false);ContentValues value=new ContentValues();value.put("value",marker.toString());db.update("meta",value,"key='assistant_history_authority'",null);
    }
    public boolean newInstallation(){synchronized(monitor){try(Cursor row=db.rawQuery("SELECT 1 FROM meta WHERE key='assistant_history_new_install' AND value='1' LIMIT 1",null)){return row.moveToFirst();}}}
    public void initializeEmpty()throws Exception{synchronized(monitor){if(active())return;if(!newInstallation())throw new IllegalStateException("已有安装缺原文件，不能静默当作空新安装");String next=UUID.randomUUID().toString();db.beginTransaction();boolean committing=false;try{publishAuthority(next,"",0,0);db.setTransactionSuccessful();committing=true;}finally{try{db.endTransaction();}catch(Exception uncertain){healthy=false;throw uncertain;}}if(committing)generation=next;}}
    private void publishAuthority(String next,String sourceSha,int messages,int sources)throws Exception{ContentValues value=new ContentValues();value.put("key","assistant_history_authority");value.put("value",new JSONObject().put("generation",next).put("legacySourceSha256",sourceSha).put("importedMessages",messages).put("importedSources",sources).put("legacyLossNotRecovered",!sourceSha.isEmpty()).put("legacyCleanupPending",!sourceSha.isEmpty()).toString());db.insertWithOnConflict("meta",null,value,SQLiteDatabase.CONFLICT_REPLACE);ContentValues typed=new ContentValues();typed.put("key","assistant_history_generation");typed.put("value",next);db.insertWithOnConflict("meta",null,typed,SQLiteDatabase.CONFLICT_REPLACE);}
    private static String historyScopeKey(String generation,String conversation){return "assistant.history_read_scope."+generation+":"+conversation;}
    private final class NativeHistoryReadScope implements ConversationHistory.ReadScope {
        final ConversationRepository issuer=ConversationRepository.this;final String generation,conversation;final ConversationHistory.ScopeMode mode;final long revision;
        NativeHistoryReadScope(String gen,String cid,ConversationHistory.ScopeMode mode,long revision){generation=gen;conversation=cid;this.mode=mode;this.revision=revision;}
        public String conversationId(){return conversation;}public ConversationHistory.ScopeMode mode(){return mode;}public long revision(){return revision;}
        public void checkCurrent()throws Exception{
            if(!healthy||!generation.equals(activeGeneration()))throw new SecurityException("原历史读取权威已变化");
            NativeHistoryReadScope current=historyReadScope(conversation);
            if(current.revision!=revision||current.mode!=mode||!current.generation.equals(generation))throw new SecurityException("主人已修改或关闭本轮跨会话读取范围");
        }
    }
    /** Metadata SQL only. A missing setting means CURRENT revision zero, never owner-wide consent. */
    private NativeHistoryReadScope historyReadScope(String conversation)throws Exception{
        if(conversation==null||!dev.pocketengine.features.memory.TaskStoreMemoryQuery.validTaskId(conversation))throw new SecurityException("历史范围缺当前原会话");
        String gen=activeGeneration();JSONObject stored=null;
        try(Cursor row=db.rawQuery("SELECT m.value FROM assistant_conversations c LEFT JOIN meta m ON m.key=? WHERE c.generation=? AND c.conversation_id=? AND c.deleted=0 LIMIT 1",new String[]{historyScopeKey(gen,conversation),gen,conversation})){
            if(!row.moveToFirst())throw new SecurityException("原会话已不存在");if(!row.isNull(0)){String raw=row.getString(0);if(raw.length()>1024)throw new SecurityException("原历史范围配置不可读");stored=new JSONObject(raw);}
        }
        if(stored==null)return new NativeHistoryReadScope(gen,conversation,ConversationHistory.ScopeMode.CURRENT,0);
        if(stored.length()!=5||!"owner".equals(stored.optString("ownerId"))||!"private".equals(stored.optString("spaceId"))||!conversation.equals(stored.optString("conversationId")))throw new SecurityException("原历史范围主体不符");
        long revision=integer(stored,"revision",1);ConversationHistory.ScopeMode mode;
        try{mode=ConversationHistory.ScopeMode.valueOf(stored.getString("mode"));}catch(Exception unknown){throw new SecurityException("原历史范围模式未知");}
        return new NativeHistoryReadScope(gen,conversation,mode,revision);
    }
    public ConversationHistory.ReadScope captureHistoryReadScope(String conversation)throws Exception{return historyReadScope(conversation);}
    public JSONObject historyReadScopeSnapshot(String conversation)throws Exception{
        NativeHistoryReadScope value=historyReadScope(conversation);return new JSONObject().put("conversationId",conversation).put("ownerId","owner").put("spaceId","private")
                .put("mode",value.mode.name()).put("revision",value.revision).put("canEnableOwnerPrivate",true).put("bodyIncluded",false).put("executionAuthorized",false);
    }
    public JSONObject setHistoryReadScope(String conversation,long expected,ConversationHistory.ScopeMode mode)throws Exception{
        if(mode==null||expected<0)throw new SecurityException("历史范围配置参数无效");
        return transaction(()->{NativeHistoryReadScope old=historyReadScope(conversation);if(old.revision!=expected)throw new SecurityException("历史范围已变化，请刷新后确认");long revision=Math.addExact(old.revision,1);
            JSONObject value=new JSONObject().put("conversationId",conversation).put("ownerId","owner").put("spaceId","private").put("mode",mode.name()).put("revision",revision);
            ContentValues row=new ContentValues();row.put("key",historyScopeKey(old.generation,conversation));row.put("value",value.toString());db.insertWithOnConflict("meta",null,row,SQLiteDatabase.CONFLICT_REPLACE);
            return historyReadScopeSnapshot(conversation);
        });
    }
    public JSONObject queryHistoryConversations(ConversationHistory.ReadScope supplied,JSONObject options,RunControl control)throws Exception{
        if(!(supplied instanceof ConversationRepository.NativeHistoryReadScope))throw new SecurityException("不是原主人历史范围");NativeHistoryReadScope scope=(NativeHistoryReadScope)supplied;
        if(scope.issuer!=this)throw new SecurityException("历史范围来自其他原文权威");scope.checkCurrent();control.check();
        int limit=options.getInt("limit");if(limit<1||limit>20||!options.optString("query").isEmpty())throw new IllegalArgumentException("会话目录只接受有界分页，不把标题或正文作为目录");
        String cursor=options.optString("cursor");if(cursor.length()>4000)throw new IllegalArgumentException("原会话目录游标过大");JSONObject at=decode(cursor);
        String signature=TaskMemoryTrace.rawFingerprint(new JSONObject().put("callerConversationId",scope.conversation).put("scopeRevision",scope.revision).put("mode",scope.mode.name()).put("generation",scope.generation));
        if(!cursor.isEmpty()&&!signature.equals(at.optString("signature")))throw new SecurityException("原会话目录游标范围已变化");
        String where="generation=? AND deleted=0";List<String> args=new ArrayList<>();args.add(scope.generation);
        if(scope.mode==ConversationHistory.ScopeMode.CURRENT){where+=" AND conversation_id=?";args.add(scope.conversation);}
        if(at.has("at")){where+=" AND (updated_at<? OR (updated_at=? AND conversation_id<?))";args.add(at.get("at").toString());args.add(at.get("at").toString());args.add(at.getString("id"));}
        JSONArray entries=new JSONArray();String last="";long lastAt=0;boolean more=false;
        try(Cursor rows=db.rawQuery("SELECT conversation_id,updated_at FROM assistant_conversations WHERE "+where+" ORDER BY updated_at DESC,conversation_id DESC LIMIT ?",append(args,Integer.toString(limit+1)))){
            while(rows.moveToNext()){control.check();if(entries.length()>=limit){more=true;break;}String id=rows.getString(0);long time=rows.getLong(1);if(!dev.pocketengine.features.memory.TaskStoreMemoryQuery.validTaskId(id)||time<0)throw new SecurityException("原会话目录身份不可读");
                entries.put(new JSONObject().put("sourceKind","assistant_conversation").put("sourceConversationId",id).put("updatedAt",time).put("metadataOnly",true).put("originalBodyIncluded",false));last=id;lastAt=time;}
        }
        scope.checkCurrent();control.check();String next=more?encode(new JSONObject().put("signature",signature).put("at",lastAt).put("id",last)):"";
        return ConversationHistory.proof(new JSONObject().put("status","current").put("entries",entries).put("gaps",new JSONArray()).put("hasMore",more).put("nextCursor",next)
                .put("bodyIncluded",false).put("historyScopeMode",scope.mode.name()).put("historyScopeRevision",scope.revision).put("scopeFilteredBeforeLimit",true));
    }
    public JSONObject authority()throws Exception{synchronized(monitor){try(Cursor row=db.rawQuery("SELECT value FROM meta WHERE key='assistant_history_authority' LIMIT 1",null)){return row.moveToFirst()?new JSONObject(row.getString(0)):new JSONObject().put("status","legacy_pending_explicit_migration");}}}
    /** Typed per-ID native metadata; no source_json or message body is loaded. */
    public JSONObject metadata(String id)throws Exception{
        try(Cursor row=db.rawQuery("SELECT s.revision,s.conversation_id,s.role,s.deleted,s.unavailable_reason,s.retired_through,s.retirement_reason,s.context_retired,coalesce(c.deleted,1),coalesce(c.delete_reason,'unknown') FROM assistant_sources s LEFT JOIN assistant_conversations c ON c.generation=s.generation AND c.conversation_id=s.conversation_id WHERE s.generation=? AND s.source_id=? LIMIT 1",new String[]{activeGeneration(),id})){
            if(!row.moveToFirst())return null;return new JSONObject().put("sourceId",id).put("revision",row.getLong(0)).put("conversationId",row.getString(1)).put("role",row.getString(2)).put("deleted",row.getInt(3)!=0||row.getInt(8)!=0).put("unavailableReason",row.getInt(3)!=0?row.getString(4):row.getInt(8)!=0?row.getString(9):"").put("memorySourceRetiredThrough",row.getLong(5)).put("retirementReason",row.getString(6)).put("memoryContextRetired",row.getInt(7)!=0);
        }
    }
    /** Explicit owner archive page. Native sources and import-assigned original fragments
     * share this bounded capture, but imported records never become SourceRefs or author proof.
     * Both original-table rowid high bounds and this generation are pinned before pagination. */
    public JSONObject ownerExperiencePage(String cursor,dev.pocketengine.store.OwnerExperienceProjection.Gate gate,RunControl control)throws Exception {
        return read(()->{
            control.checkpoint();String gen=activeGeneration();JSONObject position=decode(cursor);String phase=position.optString("phase","native");
            if(!Set.of("native","imported").contains(phase)||!cursor.isEmpty()&&!gen.equals(position.optString("generation")))throw new SecurityException("原聊天归档generation或分页类型已变化");
            long importedUpper;if(cursor.isEmpty())try(Cursor row=db.rawQuery("SELECT coalesce(max(rowid),0) FROM assistant_import_records WHERE generation=?",new String[]{gen})){row.moveToFirst();importedUpper=row.getLong(0);}else importedUpper=integer(position,"importedUpperRow",0);
            if(phase.equals("imported"))return ownerImportedExperiencePage(gen,position.optString("lastImported"),importedUpper,control);
            String last=position.optString("last"),upper=position.optString("upper");
            if(cursor.isEmpty())try(Cursor row=db.rawQuery("SELECT coalesce(max(source_id),'') FROM assistant_sources WHERE generation=?",new String[]{gen})){row.moveToFirst();upper=row.getString(0);}
            JSONArray entries=new JSONArray(),gaps=new JSONArray();boolean nativeMore=false;String next=last;long upperRow=position.has("upperRow")?position.getLong("upperRow"):0;if(cursor.isEmpty())try(Cursor row=db.rawQuery("SELECT coalesce(max(rowid),0) FROM assistant_sources WHERE generation=?",new String[]{gen})){row.moveToFirst();upperRow=row.getLong(0);}
            try(Cursor rows=db.rawQuery("SELECT s.source_id,s.revision,s.conversation_id,s.role,s.deleted,s.retired_through,s.context_retired,coalesce(c.deleted,1),m.body_sha,m.body_bytes,length(CAST(m.meta_json AS BLOB)) FROM assistant_sources s LEFT JOIN assistant_conversations c ON c.generation=s.generation AND c.conversation_id=s.conversation_id LEFT JOIN assistant_messages m ON m.generation=s.generation AND m.source_id=s.source_id WHERE s.generation=? AND s.source_id>? AND s.source_id<=? AND s.rowid<=CAST(? AS INTEGER) ORDER BY s.source_id LIMIT 21",new String[]{gen,last,upper,Long.toString(upperRow)})){
                while(rows.moveToNext()){
                    control.checkpoint();if(entries.length()+gaps.length()>=20){nativeMore=true;break;}String id=rows.getString(0);next=id;SourceRef ref=new SourceRef("message",id,rows.getLong(1),"owner","private");
                    if(rows.getInt(4)!=0||rows.getLong(5)>=ref.revision||rows.getInt(6)!=0||rows.getInt(7)!=0||gate.blocked(ref)||rows.isNull(8)||rows.getLong(9)+rows.getLong(10)>MAX_BYTES){gaps.put(new JSONObject().put("sourceRef",ref.toJson()).put("reason","deleted_retired_forgotten_missing_or_read_limited"));continue;}
                    JSONObject actual=message(rows.getString(2),id);if(actual==null)throw new SecurityException("原消息归档期间正文不可读");String text=actual.getString("content"),hash=shaBytes(text.getBytes(StandardCharsets.UTF_8));if(!("sha256:"+hash).equals(rows.getString(8))||text.getBytes(StandardCharsets.UTF_8).length!=rows.getLong(9))throw new SecurityException("原消息hash或字节数损坏");
                    JSONObject archived=new JSONObject().put("kind","message").put("ownerId","owner").put("spaceId","private").put("sourceRef",ref.toJson()).put("conversationId",rows.getString(2)).put("role",rows.getString(3)).put("text",text).put("sha256",hash).put("bytes",rows.getLong(9));
                    for(String key:List.of("at","editedAt","attachments"))if(actual.has(key))archived.put(key,actual.get(key));entries.put(archived);
                }
            }
            if(!nativeMore&&entries.length()+gaps.length()==0&&importedUpper>0)return ownerImportedExperiencePage(gen,"",importedUpper,control);
            boolean more=nativeMore||importedUpper>0;JSONObject nextPosition=nativeMore?new JSONObject().put("phase","native").put("generation",gen).put("upper",upper).put("upperRow",upperRow).put("last",next).put("importedUpperRow",importedUpper):new JSONObject().put("phase","imported").put("generation",gen).put("lastImported","").put("importedUpperRow",importedUpper);
            control.checkpoint();return new JSONObject().put("entries",entries).put("gaps",gaps).put("hasMore",more).put("nextCursor",more?encode(nextPosition):"").put("generation",gen).put("snapshotHighSourceId",upper).put("importedSnapshotUpperRow",importedUpper);
        });
    }
    private JSONObject ownerImportedExperiencePage(String gen,String last,long upper,RunControl control)throws Exception {
        JSONArray entries=new JSONArray(),gaps=new JSONArray();String next=last;boolean more=false;long used=0;
        try(Cursor rows=db.rawQuery("SELECT r.record_id,r.record_version,r.conversation_id,r.historical_revision,r.at_ms,r.role,r.source_sha,r.byte_offset,r.byte_length,r.fragment_sha,r.deleted,coalesce(c.deleted,1),f.record_id,f.conversation_id,f.byte_length,length(f.data),d.source_bytes,d.source_format FROM assistant_import_records r LEFT JOIN assistant_conversations c ON c.generation=r.generation AND c.conversation_id=r.conversation_id LEFT JOIN assistant_import_fragments f ON f.generation=r.generation AND f.source_sha=r.source_sha AND f.byte_offset=r.byte_offset LEFT JOIN assistant_import_documents d ON d.generation=r.generation AND d.source_sha=r.source_sha WHERE r.generation=? AND r.record_id>? AND r.rowid<=CAST(? AS INTEGER) ORDER BY r.record_id LIMIT 21",new String[]{gen,last,Long.toString(upper)})){
            while(rows.moveToNext()){
                control.checkpoint();if(entries.length()+gaps.length()>=20){more=true;break;}
                String id=rows.getString(0);long length=rows.getLong(8);JSONObject locator=new JSONObject().put("recordId",id).put("recordVersion",rows.getLong(1)).put("conversationId",rows.getString(2)).put("sourceSha256",rows.getString(6)).put("offset",rows.getLong(7)).put("length",length).put("fragmentSha256",rows.getString(9));
                if(rows.getInt(10)!=0||rows.getInt(11)!=0){next=id;gaps.put(new JSONObject().put("importedLocator",locator).put("reason","imported_record_deleted").put("originalBodyIncluded",false));continue;}
                if(length>MAX_BYTES){next=id;gaps.put(new JSONObject().put("importedLocator",locator).put("reason","imported_original_exceeds_archive_page_budget").put("originalBodyIncluded",false));continue;}
                ImportedExperienceEvidence.checkedLocator(locator);
                if(rows.isNull(12)||rows.isNull(15)||!id.equals(rows.getString(12))||!rows.getString(2).equals(rows.getString(13))||length!=rows.getLong(14)||length!=rows.getLong(15)||rows.isNull(16)||rows.getLong(7)>rows.getLong(16)-length||!"legacy_array".equals(rows.getString(17)))throw new SecurityException("旧导入原件段缺失或原记录定位不符，未生成空正文的成功归档");
                if(length>MAX_BYTES-used){more=true;break;}byte[] bytes;try(Cursor fragment=db.rawQuery("SELECT data FROM assistant_import_fragments WHERE generation=? AND source_sha=? AND byte_offset=? AND record_id=? AND data IS NOT NULL LIMIT 1",new String[]{gen,rows.getString(6),Long.toString(rows.getLong(7)),id})){if(!fragment.moveToFirst())throw new SecurityException("旧导入原件段在读取期间缺失");bytes=fragment.getBlob(0);}
                next=id;used+=bytes.length;
                try{entries.put(ImportedExperienceEvidence.passiveRow(locator,bytes,rows.isNull(3)?null:rows.getLong(3),rows.isNull(4)?null:rows.getLong(4),rows.getString(5)));}
                catch(ImportedExperienceEvidence.ProtectedFields protectedFields){gaps.put(new JSONObject().put("importedLocator",locator).put("reason","protected_imported_fields_excluded").put("originalBodyIncluded",false));}
            }
        }
        control.checkpoint();return new JSONObject().put("entries",entries).put("gaps",gaps).put("hasMore",more).put("nextCursor",more?encode(new JSONObject().put("phase","imported").put("generation",gen).put("lastImported",next).put("importedUpperRow",upper)):"").put("generation",gen).put("importedSnapshotUpperRow",upper).put("importedIdentitiesAreNotSourceRefs",true).put("materializedOriginalFragmentBytes",used);
    }
    /** Any known original import deletion/version/hash change wins over an older ZIP. Unknown
     * on a new instance remains explicitly archive-only, never a restored source permission. */
    public boolean ownerImportedExperienceBlocked(JSONObject input)throws Exception {
        if(!healthy)throw new SecurityException("原导入删除状态不确定，未回读旧档案正文");JSONObject pin=ImportedExperienceEvidence.checkedLocator(input);if(!active())return false;String gen=activeGeneration();
        try(Cursor rows=db.rawQuery("SELECT r.record_version,r.conversation_id,r.source_sha,r.byte_offset,r.byte_length,r.fragment_sha,r.deleted,coalesce(c.deleted,1),length(f.data),CASE WHEN length(f.data)<=1048576 THEN f.data END FROM assistant_import_records r LEFT JOIN assistant_conversations c ON c.generation=r.generation AND c.conversation_id=r.conversation_id LEFT JOIN assistant_import_fragments f ON f.generation=r.generation AND f.source_sha=r.source_sha AND f.byte_offset=r.byte_offset WHERE r.generation=? AND r.record_id=? LIMIT 1",new String[]{gen,pin.getString("recordId")})){
            while(rows.moveToNext()){if(rows.getLong(0)!=pin.getLong("recordVersion")||!rows.getString(1).equals(pin.getString("conversationId"))||!rows.getString(2).equals(pin.getString("sourceSha256"))||rows.getLong(3)!=pin.getLong("offset")||rows.getLong(4)!=pin.getLong("length")||!rows.getString(5).equals(pin.getString("fragmentSha256"))||rows.getInt(6)!=0||rows.getInt(7)!=0||rows.isNull(8)||rows.getLong(8)!=pin.getLong("length")||rows.isNull(9)||!shaBytes(rows.getBlob(9)).equals(pin.getString("fragmentSha256")))return true;}
        }return false;
    }
    /** Local deletion/retirement is negative evidence even when an old ZIP still has the bytes. */
    public boolean ownerExperienceBlocked(SourceRef ref)throws Exception {
        if(!active())return false;JSONObject state=metadata(ref.sourceId);return state!=null&&(state.getBoolean("deleted")||state.optLong("memorySourceRetiredThrough")>=ref.revision||state.optBoolean("memoryContextRetired"));
    }
    public JSONObject source(String id)throws Exception{return source(activeGeneration(),id);}
    private JSONObject source(String gen,String id)throws Exception{
        try(Cursor size=db.rawQuery("SELECT length(CAST(source_json AS BLOB)) FROM assistant_sources WHERE generation=? AND source_id=? LIMIT 1",new String[]{gen,id})){if(size.moveToFirst()&&size.getLong(0)>MAX_BYTES)throw new IllegalStateException("单条来源metadata超过本次读取预算");}
        try(Cursor row=db.rawQuery("SELECT s.source_json,s.revision,s.deleted,s.unavailable_reason,s.retired_through,s.retirement_reason,s.context_retired,coalesce(c.deleted,1),coalesce(c.delete_reason,'unknown') FROM assistant_sources s LEFT JOIN assistant_conversations c ON c.generation=s.generation AND c.conversation_id=s.conversation_id WHERE s.generation=? AND s.source_id=? LIMIT 1",new String[]{gen,id})){
            if(!row.moveToFirst())return null;JSONObject value=new JSONObject(row.getString(0));boolean deleted=row.getInt(2)!=0||row.getInt(7)!=0;
            value.put("revision",row.getLong(1)).put("deleted",deleted).put("unavailableReason",row.getInt(2)!=0?row.getString(3):row.getInt(7)!=0?row.getString(8):"").put("memorySourceRetiredThrough",row.getLong(4)).put("retirementReason",row.getString(5)).put("memoryContextRetired",row.getInt(6)!=0);return value;
        }
    }
    public JSONObject sourceByClient(String client)throws Exception{return sourceByKey("client_message_id",client);}
    public JSONObject sourceByReply(String reply)throws Exception{return sourceByKey("native_reply_id",reply);}
    private JSONObject sourceByKey(String field,String key)throws Exception{try(Cursor row=db.rawQuery("SELECT source_id FROM assistant_sources WHERE generation=? AND "+field+"=? LIMIT 1",new String[]{activeGeneration(),key})){return row.moveToFirst()?source(row.getString(0)):null;}}
    public void saveSource(JSONObject source)throws Exception{saveSource(activeGeneration(),source);}
    private void saveSource(String gen,JSONObject source)throws Exception{
        SourceRef ref=SourceRef.fromJson(source);if(!"message".equals(ref.kind)||!"owner".equals(ref.ownerId)||!"private".equals(ref.spaceId))throw new SecurityException("原始消息来源不能更换私人身份");
        String cid=source.getString("conversationId"),role=source.optString("role");if(!Set.of("user","assistant").contains(role)&&!(source.getBoolean("deleted")&&role.isEmpty()))throw new IllegalArgumentException("原始发言角色未知");
        ContentValues v=new ContentValues();v.put("generation",gen);v.put("source_id",ref.sourceId);v.put("conversation_id",cid);v.put("revision",ref.revision);v.put("role",role);v.put("deleted",source.getBoolean("deleted")?1:0);v.put("unavailable_reason",knownReason(source.optString("unavailableReason",source.getBoolean("deleted")?"unknown":"")));v.put("retired_through",source.optLong("memorySourceRetiredThrough"));v.put("retirement_reason",knownReason(source.optString("retirementReason","unknown")));v.put("context_retired",source.optBoolean("memoryContextRetired")?1:0);v.put("source_json",source.toString());
        if(source.has("clientMessageId"))v.put("client_message_id",source.getString("clientMessageId"));else v.putNull("client_message_id");if(source.has("nativeReplyId"))v.put("native_reply_id",source.getString("nativeReplyId"));else v.putNull("native_reply_id");
        db.insertWithOnConflict("assistant_sources",null,v,SQLiteDatabase.CONFLICT_REPLACE);
        if(source.getBoolean("deleted")){ContentValues erased=new ContentValues();erased.putNull("body");erased.put("body_bytes",0);erased.put("body_sha","");db.update("assistant_messages",erased,"generation=? AND source_id=?",new String[]{gen,ref.sourceId});}
    }
    public void saveConversation(JSONObject conversation)throws Exception{saveConversation(activeGeneration(),conversation);}
    private void saveConversation(String gen,JSONObject conversation)throws Exception{JSONObject header=new JSONObject(conversation.toString());header.remove("messages");header.remove("messagePage");ContentValues v=new ContentValues();v.put("generation",gen);v.put("conversation_id",header.getString("conversationId"));v.put("header_json",header.toString());v.put("updated_at",header.optLong("updatedAt",header.optLong("createdAt")));v.put("deleted",0);v.put("delete_reason","");db.insertWithOnConflict("assistant_conversations",null,v,SQLiteDatabase.CONFLICT_REPLACE);}
    public void saveMessage(String conversation,JSONObject message)throws Exception{saveMessage(activeGeneration(),conversation,message,-1);}
    private void saveMessage(String gen,String conversation,JSONObject message,long importedSequence)throws Exception{
        String id=message.getString("id");JSONObject state=source(gen,id);if(state==null||state.getBoolean("deleted")||!conversation.equals(state.getString("conversationId"))||integer(message,"revision",1)!=state.getLong("revision")||!message.getString("role").equals(state.getString("role")))throw new SecurityException("原消息/来源修订不一致，未保存副本");
        JSONObject meta=new JSONObject(message.toString());String body=meta.getString("content");if(!StandardCharsets.UTF_8.newEncoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).canEncode(body))throw new IllegalArgumentException("原文含不完整Unicode字符，未隐式替换后保存");byte[] bodyBytes=body.getBytes(StandardCharsets.UTF_8);meta.remove("content");long sequence=importedSequence;
        try(Cursor row=db.rawQuery("SELECT sequence FROM assistant_messages WHERE generation=? AND source_id=? LIMIT 1",new String[]{gen,id})){if(row.moveToFirst())sequence=row.getLong(0);}
        if(sequence<0)try(Cursor row=db.rawQuery("SELECT max(n)+1 FROM (SELECT coalesce(max(sequence),0) n FROM assistant_messages WHERE generation=? AND conversation_id=? UNION ALL SELECT coalesce(max(sequence),0) n FROM assistant_import_records WHERE generation=? AND conversation_id=?)",new String[]{gen,conversation,gen,conversation})){row.moveToFirst();sequence=row.getLong(0);}
        ContentValues v=new ContentValues();v.put("generation",gen);v.put("source_id",id);v.put("conversation_id",conversation);v.put("sequence",sequence);v.put("revision",state.getLong("revision"));v.put("body",body);v.put("body_bytes",bodyBytes.length);v.put("body_sha","sha256:"+shaBytes(bodyBytes));v.put("meta_json",meta.toString());Object at=message.opt("at");if(at instanceof Byte||at instanceof Short||at instanceof Integer||at instanceof Long)v.put("at_ms",((Number)at).longValue());else v.putNull("at_ms");db.insertWithOnConflict("assistant_messages",null,v,SQLiteDatabase.CONFLICT_REPLACE);
    }
    public boolean exists(String conversation)throws Exception{try(Cursor row=db.rawQuery("SELECT 1 FROM assistant_conversations WHERE generation=? AND conversation_id=? AND deleted=0 LIMIT 1",new String[]{activeGeneration(),conversation})){return row.moveToFirst();}}
    public JSONObject header(String conversation)throws Exception{try(Cursor size=db.rawQuery("SELECT length(CAST(header_json AS BLOB)) FROM assistant_conversations WHERE generation=? AND conversation_id=? AND deleted=0 LIMIT 1",new String[]{activeGeneration(),conversation})){if(size.moveToFirst()&&size.getLong(0)>MAX_BYTES)throw new ReadLimited("original_conversation_header_exceeds_inline_budget");}try(Cursor row=db.rawQuery("SELECT header_json FROM assistant_conversations WHERE generation=? AND conversation_id=? AND deleted=0 LIMIT 1",new String[]{activeGeneration(),conversation})){if(!row.moveToFirst())throw new IllegalArgumentException("对话不存在");return new JSONObject(row.getString(0));}}
    public JSONObject list(String cursor,int limit)throws Exception{
        if(limit<1||limit>20)throw new IllegalArgumentException("会话分页最多20条");JSONObject pos=decode(cursor);List<String> args=new ArrayList<>();args.add(activeGeneration());String where="generation=? AND deleted=0";
        if(pos.has("at")){where+=" AND (updated_at<? OR (updated_at=? AND conversation_id<?))";args.add(pos.get("at").toString());args.add(pos.get("at").toString());args.add(pos.getString("id"));}
        JSONArray rows=new JSONArray(),gaps=new JSONArray();String next="",lastId="";long lastAt=0,bytes=0;int scanned=0;boolean more=false;
        try(Cursor row=db.rawQuery("SELECT conversation_id,length(CAST(header_json AS BLOB)),updated_at FROM assistant_conversations WHERE "+where+" ORDER BY updated_at DESC,conversation_id DESC LIMIT ?",append(args,Integer.toString(limit+1)))){
            while(row.moveToNext()){
                if(scanned>=limit){more=true;break;}long size=row.getLong(1);if(size>MAX_BYTES){gaps.put(new JSONObject().put("conversationId",row.getString(0)).put("status","read_limited"));lastId=row.getString(0);lastAt=row.getLong(2);scanned++;continue;}if(size>MAX_BYTES-bytes){more=true;break;}
                JSONObject header=header(row.getString(0)),item=new JSONObject().put("conversationId",row.getString(0)).put("title",header.optString("title"));Object actual=header.opt("updatedAt");boolean known=actualInteger(actual);item.put("updatedAt",known?actual:JSONObject.NULL).put("updatedAtKnown",known);rows.put(item);bytes+=size;lastId=row.getString(0);lastAt=row.getLong(2);scanned++;
            }
        }
        if(more&&!lastId.isEmpty())next=encode(new JSONObject().put("at",lastAt).put("id",lastId));return new JSONObject().put("conversations",rows).put("gaps",gaps).put("scannedRows",scanned).put("materializedHeaderUtf8Bytes",bytes).put("nextCursor",next).put("hasMore",more).put("retentionLimitApplied",false).put("paginationSemantics","mutable_updated_at_keyset_not_snapshot");
    }
    private JSONObject messageFromRow(Cursor row)throws Exception{JSONObject message=new JSONObject(row.getString(1));message.put("content",row.getString(0)).put("revision",row.getLong(2));JSONObject state=source(message.getString("id"));if(state==null||state.getBoolean("deleted"))return null;for(String key:List.of("submission","memorySourceRetiredThrough","memoryContextRetired","retirementReason"))if(state.has(key))message.put(key,state.get(key));return message;}
    public JSONObject message(String conversation,String id)throws Exception{
        JSONObject state=metadata(id);if(state==null||state.getBoolean("deleted")||!conversation.equals(state.getString("conversationId")))return null;
        try(Cursor size=db.rawQuery("SELECT length(CAST(m.body AS BLOB))+length(CAST(m.meta_json AS BLOB))+length(CAST(s.source_json AS BLOB)) FROM assistant_messages m JOIN assistant_sources s ON s.generation=m.generation AND s.source_id=m.source_id WHERE m.generation=? AND m.conversation_id=? AND m.source_id=? LIMIT 1",new String[]{activeGeneration(),conversation,id})){if(!size.moveToFirst())return null;if(size.getLong(0)>MAX_BYTES)throw new ReadLimited("original_message_exceeds_inline_budget");}
        try(Cursor row=db.rawQuery("SELECT body,meta_json,revision FROM assistant_messages WHERE generation=? AND conversation_id=? AND source_id=? AND body IS NOT NULL LIMIT 1",new String[]{activeGeneration(),conversation,id})){return row.moveToFirst()?messageFromRow(row):null;}
    }
    /** Original queue claim/mutation only, in the same TaskStore monitor and SQLite transaction.
     * This validates the real message/source, not a JSON capability or a second queue authority. */
    public void requireQueuedOwnerMessageCurrent(String conversation,JSONObject row)throws Exception{
        if(!Thread.holdsLock(monitor)||!db.inTransaction())throw new SecurityException("排队输入核对必须在原TaskStore事务内");
        String id=row.getString("messageId"),client=row.getString("clientMessageId");long revision=integer(row,"ownerMessageRevision",1);
        JSONObject state=source(id),message=message(conversation,id);
        if(state==null||message==null||state.getBoolean("deleted")||!conversation.equals(state.getString("conversationId"))
                ||!"user".equals(state.optString("role"))||!"user".equals(message.optString("role"))
                ||!id.equals(message.getString("id"))||revision!=state.getLong("revision")||revision!=integer(message,"revision",1)
                ||!client.equals(state.optString("clientMessageId"))||!client.equals(message.optString("clientMessageId"))
                ||!row.getString("content").equals(message.getString("content"))||!row.getString("mode").equals(message.optString("inputMode"))
                ||state.optString("requestFingerprint").isEmpty()||!state.getString("requestFingerprint").equals(message.optString("requestFingerprint")))
            throw new SecurityException("原生排队消息或来源版本已变化，未接手旧正文");
        if(row.has("ownerRequestFingerprint")){
            Object pin=row.opt("ownerRequestFingerprint");
            if(!(pin instanceof String)||!((String)pin).matches("[0-9a-f]{64}")||!pin.equals(state.optString("requestFingerprint"))||!pin.equals(message.optString("requestFingerprint")))
                throw new SecurityException("原接收时请求指纹与当前消息来源不一致");
        }
        JSONObject ack=state.optJSONObject("submission");
        if(ack==null||!client.equals(ack.optString("clientMessageId"))||!id.equals(ack.optString("messageId"))||!conversation.equals(ack.optString("conversationId")))
            throw new SecurityException("原接收回执与消息身份不一致，未接手副本");
        // appendOwnerInput is already the native acceptance authority. Its real pending row can
        // be claimed before sendNative commits its ACK; this is not a consumption/grant claim.
        boolean pendingAck="unconfirmed".equals(ack.optString("status"))&&!ack.has("taskId")&&!ack.has("runId")&&!ack.has("epoch");
        if(!pendingAck&&(!row.getString("acceptedRunId").equals(ack.optString("runId"))||integer(row,"acceptedEpoch",1)!=integer(ack,"epoch",1)
                ||row.has("acceptedTaskId")&&!row.getString("acceptedTaskId").equals(ack.optString("taskId"))))
            throw new SecurityException("原接收代次与消息回执不一致，未接手副本");
        for(String key:List.of("attachments","ownerSourceRefs")){
            JSONArray expected=row.optJSONArray(key),actual=message.optJSONArray(key);
            if(row.has(key)&&expected==null||message.has(key)&&actual==null
                    ||!TaskMemoryTrace.rawFingerprint(new JSONObject().put("refs",expected==null?new JSONArray():expected))
                    .equals(TaskMemoryTrace.rawFingerprint(new JSONObject().put("refs",actual==null?new JSONArray():actual))))
                throw new SecurityException("原排队输入的附件或原文引用已变化");
        }
    }
    /** Native opaque selection only; no independent transaction, source grant or model command. */
    JSONObject mutateQueuedOwnerMessage(dev.pocketengine.store.TaskStore original,dev.pocketengine.store.TaskStore.QueuedOwnerMessage selected,
            String content,String mode,boolean withdrawn)throws Exception{
        selected.requireMutationScope(original);if(monitor!=original)throw new SecurityException("不能替换原消息数据库宿主");
        JSONObject expected=new JSONObject().put("messageId",selected.messageId).put("clientMessageId",selected.clientMessageId)
                .put("ownerMessageRevision",selected.currentMessageRevision).put("acceptedRunId",selected.acceptedRunId).put("acceptedEpoch",selected.acceptedEpoch)
                .put("ownerRequestFingerprint",selected.requestFingerprint).put("acceptedTaskId",selected.taskId)
                .put("content",selected.content)
                .put("mode",selected.mode).put("attachments",selected.attachments()).put("ownerSourceRefs",selected.sourceRefs());
        requireQueuedOwnerMessageCurrent(selected.conversationId,expected);
        JSONObject state=source(selected.messageId),message=message(selected.conversationId,selected.messageId),ack=new JSONObject(state.getJSONObject("submission").toString());
        boolean missingAck="unconfirmed".equals(ack.optString("status"))&&!ack.has("taskId")&&!ack.has("runId")&&!ack.has("epoch");
        if(missingAck)ack.put("taskId",selected.taskId).put("runId",selected.acceptedRunId).put("epoch",selected.acceptedEpoch).put("status","queued").put("reconciliationRequired",false);
        if(!selected.taskId.equals(ack.optString("taskId"))||!mode.matches("queue|steer")||content==null||content.length()>12000
                ||withdrawn&&(!content.equals(message.getString("content"))||!mode.equals(selected.mode)))
            throw new SecurityException("修改范围不属于同一原生待处理输入");
        long next=Math.addExact(selected.currentMessageRevision,1L),queue=Math.addExact(selected.queueRevision,1L),now=System.currentTimeMillis();
        ack.put("queueRevision",queue).put("ownerMessageRevision",next).put("queueState",withdrawn?"withdrawn":"pending").put("mode",mode);
        if(withdrawn){
            ack.put("status","withdrawn").put("withdrawnAt",now);
            // A pending intention was never consumed. Retain owner audit bytes, but do not make
            // the incremented revision a fresh model/history/learning source after withdrawal.
            state.put("memorySourceRetiredThrough",next).put("memoryContextRetired",true).put("retirementReason","owner_correction");
            message.put("memorySourceRetiredThrough",next).put("memoryContextRetired",true).put("retirementReason","owner_correction");
        }
        // Preserve original client/fingerprint/task/run/epoch and references. Withdrawal is not forget.
        message.put("content",content).put("revision",next).put("inputMode",mode).put("submission",new JSONObject(ack.toString())).put(withdrawn?"withdrawnAt":"editedAt",now);
        state.put("revision",next).put("updatedAt",now).put("submission",ack);saveSource(state);saveMessage(selected.conversationId,message);
        JSONObject header=header(selected.conversationId);header.put("updatedAt",now);saveConversation(header);return new JSONObject(message.toString());
    }
    /** Already-checkpointed private native reply proof. Same original SQL transaction only;
     * neither this callback nor a historical message ID can start a task or grant new input. */
    JSONObject acceptQueuedOwnerReply(dev.pocketengine.store.TaskStore original,dev.pocketengine.store.TaskStore.QueuedOwnerReplySelection selected,
            String text,String digest)throws Exception{
        selected.requireMutationScope(original);if(monitor!=original)throw new SecurityException("不能替换原完成消息数据库宿主");
        if(text==null||text.length()>24000||digest==null||digest.isEmpty())throw new IllegalArgumentException("原答复保存范围无效");
        JSONObject indexed=sourceByReply(selected.completed.id);
        if(indexed!=null){
            JSONObject prior=message(selected.conversationId,indexed.getString("sourceId"));
            if(prior==null||!selected.conversationId.equals(indexed.optString("conversationId"))
                    ||!digest.equals(indexed.optString("nativeReplyDigest"))||!selected.taskId.equals(prior.optString("taskId"))
                    ||!selected.runId.equals(prior.optString("nativeRunId"))||selected.epoch!=integer(prior,"nativeEpoch",1))
                throw new SecurityException("原答复身份不能更换内容、任务或执行代次");
            return new JSONObject().put("duplicate",true).put("message",prior).put("consumed",new JSONArray());
        }
        JSONArray inputs=selected.inputs(),consumedRows=new JSONArray(),replyTo=new JSONArray();long now=System.currentTimeMillis();
        String replyId;do{replyId=java.util.UUID.randomUUID().toString();}while(source(replyId)!=null);
        for(int i=0;i<inputs.length();i++){
            JSONObject row=inputs.getJSONObject(i);requireQueuedOwnerMessageCurrent(selected.conversationId,row);
            String id=row.getString("messageId");JSONObject state=source(id),message=message(selected.conversationId,id),ack=new JSONObject(state.getJSONObject("submission").toString());
            if("consumed".equals(ack.optString("status")))throw new SecurityException("原输入已记录采用，未重复写成本次新采用");
            boolean missingAck="unconfirmed".equals(ack.optString("status"))&&!ack.has("taskId")&&!ack.has("runId")&&!ack.has("epoch");
            if(missingAck)ack.put("taskId",selected.taskId).put("runId",row.getString("acceptedRunId")).put("epoch",integer(row,"acceptedEpoch",1));
            if(!selected.taskId.equals(ack.optString("taskId"))||!row.getString("acceptedRunId").equals(ack.optString("runId"))||integer(row,"acceptedEpoch",1)!=integer(ack,"epoch",1))
                throw new SecurityException("输入原接收身份不能被恢复答复替换");
            ack.put("status","consumed").put("queueState","materialized").put("queueRevision",integer(row,"queueRevision",1))
                    .put("ownerMessageRevision",integer(row,"ownerMessageRevision",1)).put("mode",row.getString("mode"))
                    .put("consumedRunId",selected.runId).put("consumedEpoch",selected.epoch).put("replyMessageId",replyId).put("consumedAt",now).put("reconciliationRequired",false);
            state.put("submission",new JSONObject(ack.toString())).put("updatedAt",now);message.put("submission",new JSONObject(ack.toString()));
            saveSource(state);saveMessage(selected.conversationId,message);consumedRows.put(ack);replyTo.put(id);
        }
        JSONObject reply=new JSONObject().put("id",replyId).put("revision",1L).put("role","assistant").put("content",text).put("at",now)
                .put("conversationId",selected.conversationId).put("taskId",selected.taskId).put("nativeReplyId",selected.completed.id).put("nativeRunId",selected.runId).put("nativeEpoch",selected.epoch).put("replyToMessageIds",replyTo);
        if(selected.completed.memoryReference()!=null)reply.put("memoryReference",selected.completed.memoryReference());
        if(selected.completed.memoryContext()!=null)reply.put("memoryContext",selected.completed.memoryContext());
        JSONObject state=new JSONObject().put("kind","message").put("sourceId",replyId).put("revision",1L).put("ownerId","owner").put("spaceId","private")
                .put("conversationId",selected.conversationId).put("role","assistant").put("deleted",false).put("updatedAt",now)
                .put("nativeReplyId",selected.completed.id).put("nativeReplyDigest",digest).put("nativeRunId",selected.runId).put("nativeEpoch",selected.epoch).put("nativeTaskId",selected.taskId);
        saveSource(state);saveMessage(selected.conversationId,reply);JSONObject header=header(selected.conversationId);header.put("updatedAt",now);saveConversation(header);
        return new JSONObject().put("duplicate",false).put("message",reply).put("consumed",consumedRows);
    }
    /** Native owner/UI window, not a retention operation. Import records never become SourceRefs. */
    public JSONObject window(String conversation,String cursor,int limit)throws Exception{
        if(limit<1||limit>80)throw new IllegalArgumentException("消息窗口最多80条");JSONObject result=header(conversation),pos=decode(cursor);long upper=pos.optLong("before",Long.MAX_VALUE);JSONArray reverse=new JSONArray();long bytes=result.toString().getBytes(StandardCharsets.UTF_8).length,last=-1;boolean more=false;int scanned=0;JSONArray gaps=new JSONArray();
        String gen=activeGeneration();
        try(Cursor rows=db.rawQuery("SELECT id,n,bytes,kind FROM (SELECT m.source_id id,m.sequence n,length(CAST(m.body AS BLOB))+length(CAST(m.meta_json AS BLOB))+length(CAST(s.source_json AS BLOB)) bytes,'native' kind FROM assistant_messages m JOIN assistant_sources s ON s.generation=m.generation AND s.source_id=m.source_id WHERE m.generation=? AND m.conversation_id=? AND m.body IS NOT NULL UNION ALL SELECT record_id id,sequence n,byte_length bytes,'legacy' kind FROM assistant_import_records WHERE generation=? AND conversation_id=? AND deleted=0) WHERE n<? ORDER BY n DESC LIMIT ?",new String[]{gen,conversation,gen,conversation,Long.toString(upper),Integer.toString(limit+1)})){
            while(rows.moveToNext()){
                if(scanned>=limit){more=true;break;}long size=rows.getLong(2);if(size>MAX_BYTES){gaps.put(new JSONObject().put("recordId",rows.getString(0)).put("status","read_limited"));last=rows.getLong(1);scanned++;continue;}
                if(size>MAX_BYTES-bytes){more=true;break;}
                JSONObject message="legacy".equals(rows.getString(3))?importedMessage(conversation,rows.getString(0)):message(conversation,rows.getString(0));last=rows.getLong(1);scanned++;if(message!=null){reverse.put(message);bytes+=size;}
            }
        }
        JSONArray messages=new JSONArray();for(int i=reverse.length()-1;i>=0;i--)messages.put(reverse.getJSONObject(i));String next=more&&last>=0?encode(new JSONObject().put("before",last)):"";
        return result.put("messages",messages).put("messagePage",new JSONObject().put("hasMore",more).put("nextCursor",next).put("scannedRows",scanned).put("materializedOriginalRecordUtf8Bytes",bytes).put("gaps",gaps).put("retentionLimitApplied",false));
    }
    public JSONObject importedMessage(String conversation,String record)throws Exception{
        String gen=activeGeneration();try(Cursor row=db.rawQuery("SELECT r.record_version,r.historical_revision,r.at_ms,r.role,r.source_sha,r.byte_offset,r.byte_length,r.fragment_sha,length(f.data) FROM assistant_import_records r JOIN assistant_conversations c ON c.generation=r.generation AND c.conversation_id=r.conversation_id JOIN assistant_import_fragments f ON f.generation=r.generation AND f.source_sha=r.source_sha AND f.byte_offset=r.byte_offset WHERE r.generation=? AND r.conversation_id=? AND r.record_id=? AND r.deleted=0 AND c.deleted=0 LIMIT 1",new String[]{gen,conversation,record})){
            if(!row.moveToFirst()||row.isNull(8))return null;if(row.getLong(6)>MAX_BYTES||row.getLong(8)!=row.getLong(6))throw new IllegalStateException("原件段超过读取预算或长度已变化");
            byte[] original;try(Cursor fragment=db.rawQuery("SELECT data FROM assistant_import_fragments WHERE generation=? AND source_sha=? AND byte_offset=? AND data IS NOT NULL LIMIT 1",new String[]{gen,row.getString(4),Long.toString(row.getLong(5))})){if(!fragment.moveToFirst())return null;original=fragment.getBlob(0);}
            if(!row.getString(7).equals(shaBytes(original)))throw new SecurityException("原件段指纹不符，未展示替代正文");JSONObject raw=new JSONObject(new String(original,StandardCharsets.UTF_8));Object content=raw.opt("content");String body=content instanceof String?(String)content:content==null?"":content.toString();
            JSONObject value=LegacyConversationImport.ownerView(raw).put("id",record).put("recordId",record).put("recordVersion",row.getLong(0)).put("revision",JSONObject.NULL).put("historicalRevision",row.isNull(1)?JSONObject.NULL:row.getLong(1)).put("historicalRevisionKnown",!row.isNull(1)).put("importAssignedIdentity",true).put("formalSourceRefAvailable",false).put("identityProvenance","import_assigned_not_historical_source").put("role",row.getString(3)).put("content",body).put("originalIdentity",new JSONObject().put("id",raw.has("id")?raw.get("id"):JSONObject.NULL).put("revision",raw.has("revision")?raw.get("revision"):JSONObject.NULL)).put("originalByteReference",new JSONObject().put("sourceSha256",row.getString(4)).put("offset",row.getLong(5)).put("length",row.getLong(6)).put("unit","UTF-8 bytes").put("fragmentSha256",row.getString(7)));
            if(row.isNull(2))value.put("at",JSONObject.NULL);else value.put("at",row.getLong(2));return value;
        }
    }
    public void deleteImportedMessage(String conversation,String record,long version)throws Exception{
        String gen=activeGeneration();ContentValues tombstone=new ContentValues();tombstone.put("deleted",1);tombstone.put("unavailable_reason","user_message_delete");tombstone.put("record_version",version+1);
        if(db.update("assistant_import_records",tombstone,"generation=? AND conversation_id=? AND record_id=? AND record_version=? AND deleted=0",new String[]{gen,conversation,record,Long.toString(version)})!=1)throw new IllegalStateException("原导入记录已变化，请刷新后重试");
        ContentValues erased=new ContentValues();erased.putNull("data");db.update("assistant_import_fragments",erased,"generation=? AND record_id=?",new String[]{gen,record});db.execSQL("UPDATE assistant_import_documents SET redacted=1 WHERE generation=?",new Object[]{gen});
    }
    public void deleteConversation(String conversation,String reason)throws Exception{ContentValues c=new ContentValues();c.put("deleted",1);c.put("delete_reason",reason);c.put("header_json",new JSONObject().put("conversationId",conversation).put("deleted",true).toString());if(db.update("assistant_conversations",c,"generation=? AND conversation_id=? AND deleted=0",new String[]{activeGeneration(),conversation})!=1)throw new IllegalArgumentException("对话不存在");db.execSQL("UPDATE assistant_sources SET revision=revision+1,deleted=1,unavailable_reason=? WHERE generation=? AND conversation_id=? AND deleted=0",new Object[]{reason,activeGeneration(),conversation});ContentValues erased=new ContentValues();erased.putNull("body");erased.put("body_bytes",0);erased.put("body_sha","");db.update("assistant_messages",erased,"generation=? AND conversation_id=?",new String[]{activeGeneration(),conversation});
        db.execSQL("UPDATE assistant_import_records SET deleted=1,record_version=record_version+1,unavailable_reason=? WHERE generation=? AND conversation_id=? AND deleted=0",new Object[]{reason,activeGeneration(),conversation});ContentValues fragments=new ContentValues();fragments.putNull("data");db.update("assistant_import_fragments",fragments,"generation=? AND conversation_id=?",new String[]{activeGeneration(),conversation});db.execSQL("UPDATE assistant_import_documents SET redacted=1 WHERE generation=?",new Object[]{activeGeneration()});}
    public int retire(JSONArray removed,String cause)throws Exception{
        int affected=0;String gen=activeGeneration();for(int i=0;i<removed.length();i++){JSONArray refs=removed.getJSONObject(i).optJSONArray("sourceRefs");for(int j=0;refs!=null&&j<refs.length();j++){SourceRef ref=SourceRef.fromJson(refs.getJSONObject(j));if(!"message".equals(ref.kind)||!"owner".equals(ref.ownerId)||!"private".equals(ref.spaceId))continue;
            try(var update=db.compileStatement("UPDATE assistant_sources SET retired_through=?,retirement_reason=? WHERE generation=? AND source_id=? AND retired_through<?")){update.bindLong(1,ref.revision);update.bindString(2,cause);update.bindString(3,gen);update.bindString(4,ref.sourceId);update.bindLong(5,ref.revision);affected=Math.addExact(affected,update.executeUpdateDelete());}
        }}
        try(var update=db.compileStatement("UPDATE assistant_sources SET retirement_reason=?,context_retired=1 WHERE generation=? AND role='assistant' AND context_retired=0")){update.bindString(1,cause);update.bindString(2,gen);affected=Math.addExact(affected,update.executeUpdateDelete());}return affected;
    }
    public JSONObject state(String conversation,SourceRef expected)throws Exception{
        JSONObject state=metadata(expected.sourceId);String status="provenance_unavailable",reason="unknown";
        if(state!=null&&conversation.equals(state.optString("conversationId"))){
            if(state.getBoolean("deleted")){status="deleted";reason=state.optString("unavailableReason","unknown");}
            else if(state.getLong("revision")!=expected.revision){status="source_changed";reason="message_revision_changed";}
            else if(state.optLong("memorySourceRetiredThrough")>=expected.revision||state.optBoolean("memoryContextRetired")){status="retired";reason=state.optString("retirementReason","unknown");}
            else{status="current";reason="";}
        }
        return new JSONObject().put("sourceRef",expected.toJson()).put("status",status).put("reason",reason);
    }
    /** Minimal original model evidence; owner/UI metadata is not materialized on this path. */
    public JSONObject historyMessage(String conversation,String id,CancellationSignal signal)throws Exception{return historyMessage(conversation,id,signal,MAX_BYTES);}
    JSONObject historyMessage(String conversation,String id,CancellationSignal signal,long maximumBytes)throws Exception{
        JSONObject state=metadata(id);if(state==null||state.getBoolean("deleted")||!conversation.equals(state.getString("conversationId")))return null;
        try(Cursor size=db.rawQuery("SELECT length(CAST(body AS BLOB)) FROM assistant_messages WHERE generation=? AND conversation_id=? AND source_id=? AND body IS NOT NULL LIMIT 1",new String[]{activeGeneration(),conversation,id},signal)){if(!size.moveToFirst())return null;if(size.getLong(0)>Math.min(MAX_BYTES,maximumBytes))throw new ReadLimited("original_message_exceeds_inline_budget");}
        try(Cursor row=db.rawQuery("SELECT body,revision,at_ms FROM assistant_messages WHERE generation=? AND conversation_id=? AND source_id=? AND body IS NOT NULL LIMIT 1",new String[]{activeGeneration(),conversation,id},signal)){
            if(!row.moveToFirst())return null;if(row.getLong(1)!=state.getLong("revision"))throw new SecurityException("原消息修订与native来源不一致");JSONObject value=new JSONObject().put("id",id).put("revision",row.getLong(1)).put("role",state.getString("role")).put("content",row.getString(0));if(!row.isNull(2))value.put("at",row.getLong(2));return value;
        }
    }
    /** Native owner page only; never consumed by ConversationHistory model providers. */
    private OriginalHistorySearch ownerOriginalSearch;
    private OriginalHistorySearch ownerOriginalSearch(){synchronized(monitor){activeGeneration();if(ownerOriginalSearch==null)ownerOriginalSearch=new OriginalHistorySearch(db,monitor,this,this::activeGeneration,monotonicNanos);return ownerOriginalSearch;}}
    public JSONObject searchOwnerOriginals(JSONObject options,RunControl original)throws Exception{return ownerOriginalSearch().search(options,original);}
    public JSONObject readOwnerSearchHit(JSONObject request,RunControl original)throws Exception{return ownerOriginalSearch().read(request,original);}
    /** One bounded, body-free currentness batch. No TaskStore/Book monitor or callback is acquired. */
    public static final class SourceUnavailable extends Exception {SourceUnavailable(String reason){super(reason);}}
    public OwnerSourceInspection inspectOwnerSources(){return new OwnerSourceInspection(null);}
    public OwnerSourceInspection inspectOwnerSources(ConversationHistory.SourceBytes bytes){return new OwnerSourceInspection(bytes);}
    public final class OwnerSourceInspection implements AutoCloseable {
        private final ConversationHistory.SourceBytes externalBytes;
        private OwnerSourceInspection(ConversationHistory.SourceBytes bytes){externalBytes=bytes;}
        private final CancellationSignal signal=new CancellationSignal();
        private final long deadlineNanos=System.nanoTime()+500_000_000L;
        private final java.util.concurrent.ScheduledFuture<?> deadline=DEADLINES.schedule(signal::cancel,500,java.util.concurrent.TimeUnit.MILLISECONDS);
        private final Map<String,JSONObject> checkedSources=new LinkedHashMap<>(),checkedCarriers=new LinkedHashMap<>();
        private long materializedBytes;private int statements;private boolean closed;
        private void current(){if(closed||!healthy||signal.isCanceled()||System.nanoTime()>deadlineNanos||Thread.currentThread().isInterrupted())throw new IllegalStateException("原文引用批次超时、停止或权威不确定");}
        private void statement(){current();if(++statements>500)throw new IllegalStateException("原文引用metadata批次超过语句预算");}
        private long remainingBytes()throws Exception{return Math.min(MAX_BYTES-materializedBytes,externalBytes==null?MAX_BYTES:externalBytes.remaining());}
        private Exception limited(String reason){return externalBytes==null?new ReadLimited(reason):new dev.pocketengine.features.memory.TaskStoreMemoryReader.RawMessageUnavailable(reason);}
        private void materialized(long bytes)throws Exception{if(bytes<0||bytes>remainingBytes())throw limited("owner_source_batch_bytes");if(externalBytes!=null)externalBytes.charge(bytes);materializedBytes=Math.addExact(materializedBytes,bytes);}
        public void requireSource(JSONObject supplied)throws Exception {
            current();JSONObject ref=OwnerSourceReferences.checked(new JSONArray().put(supplied)).getJSONObject(0),source=ref.getJSONObject("sourceRef");statement();
            // Active generation, source, body version/hash and conversation tombstone are
            // constrained by the same read statement. Normal unrelated tail writes do not revoke it.
            String sql="SELECT a.value,s.conversation_id,s.revision,s.deleted,s.retired_through,s.context_retired,coalesce(c.deleted,1),m.revision,m.body_sha,m.body IS NULL FROM meta a LEFT JOIN assistant_sources s ON s.generation=a.value AND s.source_id=? LEFT JOIN assistant_conversations c ON c.generation=s.generation AND c.conversation_id=s.conversation_id LEFT JOIN assistant_messages m ON m.generation=s.generation AND m.source_id=s.source_id AND m.conversation_id=s.conversation_id WHERE a.key='assistant_history_generation' LIMIT 1";
            try(Cursor row=db.rawQuery(sql,new String[]{source.getString("sourceId")},signal)){
                if(!row.moveToFirst()||!ref.getString("generation").equals(row.getString(0))||row.isNull(2)
                        ||!ref.getString("sourceConversationId").equals(row.getString(1))||source.getLong("revision")!=row.getLong(2)
                        ||row.getInt(3)!=0||row.getLong(4)>=row.getLong(2)||row.getInt(5)!=0||row.getInt(6)!=0
                        ||row.isNull(7)||row.getLong(7)!=row.getLong(2)||!ref.getString("bodyFingerprint").equals(row.getString(8))||row.getInt(9)!=0)
                    throw new SourceUnavailable("所选原文已删除、停用、换版或不再属于这份原来源");
            }
            current();String key=TaskMemoryTrace.rawFingerprint(ref);if(!checkedSources.containsKey(key)&&checkedSources.size()>=100)throw new IllegalStateException("原文引用currentness最多100个精确范围");checkedSources.put(key,ref);
        }
        public boolean nativeOwnerInput(JSONObject row,String conversation)throws Exception{
            if(conversation==null||conversation.isEmpty()||!"user".equals(row.optString("role"))||!(row.opt("ownerMessageId") instanceof String))return false;
            Object revision=row.opt("ownerMessageRevision");if(!(revision instanceof Integer||revision instanceof Long)||((Number)revision).longValue()<1)return false;statement();
            String sql="SELECT 1 FROM meta a JOIN assistant_sources s ON s.generation=a.value JOIN assistant_messages m ON m.generation=s.generation AND m.source_id=s.source_id AND m.conversation_id=s.conversation_id JOIN assistant_conversations c ON c.generation=s.generation AND c.conversation_id=s.conversation_id WHERE a.key='assistant_history_generation' AND s.source_id=? AND s.revision=? AND m.revision=s.revision AND s.conversation_id=? AND s.role='user' AND s.deleted=0 AND s.retired_through<s.revision AND s.context_retired=0 AND m.body IS NOT NULL AND c.deleted=0 LIMIT 1";
            boolean found;try(Cursor actual=db.rawQuery(sql,new String[]{row.getString("ownerMessageId"),revision.toString(),conversation},signal)){found=actual.moveToFirst();}current();return found;
        }
        /** A model/checkpoint cannot invent a selected source on an arbitrary user row. */
        public void requireCarrier(JSONObject row,String conversation)throws Exception {
            current();if(!nativeOwnerInput(row,conversation))throw new SecurityException("原文引用缺少属于当前原会话的原生主人消息");
            String id=row.getString("ownerMessageId");Object revision=row.opt("ownerMessageRevision");if(!(revision instanceof Integer||revision instanceof Long)||((Number)revision).longValue()<1)throw new SecurityException("原文引用缺少真实主人消息修订");
            JSONArray expected=OwnerSourceReferences.checked(row.getJSONArray("ownerSourceRefs"));String where=" FROM meta a JOIN assistant_messages m ON m.generation=a.value JOIN assistant_sources s ON s.generation=m.generation AND s.source_id=m.source_id JOIN assistant_conversations c ON c.generation=s.generation AND c.conversation_id=s.conversation_id WHERE a.key='assistant_history_generation' AND m.source_id=? AND m.revision=? AND s.revision=m.revision AND s.role='user' AND s.deleted=0 AND s.retired_through<s.revision AND s.context_retired=0 AND m.body IS NOT NULL AND c.deleted=0 LIMIT 1";
            String[] args={id,Long.toString(((Number)revision).longValue())};long bytes;statement();
            try(Cursor size=db.rawQuery("SELECT length(CAST(m.meta_json AS BLOB))"+where,args,signal)){if(!size.moveToFirst()||size.isNull(0))throw new SourceUnavailable("原主人消息不可核对");bytes=size.getLong(0);if(bytes>remainingBytes())throw limited("owner_source_carrier_metadata_exceeds_batch_bytes");}
            statement();JSONObject nativeMessage;String bounded=where.replace(" LIMIT 1"," AND length(CAST(m.meta_json AS BLOB))<=? LIMIT 1");
            try(Cursor actual=db.rawQuery("SELECT m.meta_json"+bounded,new String[]{args[0],args[1],Long.toString(remainingBytes())},signal)){if(!actual.moveToFirst())throw limited("owner_source_carrier_changed_or_byte_budget");String text=actual.getString(0);materialized(text.getBytes(StandardCharsets.UTF_8).length);nativeMessage=new JSONObject(text);}
            JSONArray actual=nativeMessage.optJSONArray("ownerSourceRefs");if(actual==null||!TaskMemoryTrace.rawFingerprint(new JSONObject().put("refs",OwnerSourceReferences.checked(actual))).equals(TaskMemoryTrace.rawFingerprint(new JSONObject().put("refs",expected))))throw new SecurityException("原文引用不等于这条原生主人消息的实际选择");
            current();if(!checkedCarriers.containsKey(id)&&checkedCarriers.size()>=100)throw limited("owner_source_carrier_row_budget");checkedCarriers.put(id,new JSONObject().put("role","user").put("ownerMessageId",id).put("ownerMessageRevision",revision).put("ownerSourceRefs",expected).put("_nativeConversationId",conversation));
        }
        public JSONObject readRange(JSONObject reference,RunControl original)throws Exception {
            requireSource(reference);long remaining=remainingBytes();if(remaining<1)throw limited("owner_source_batch_bytes");
            JSONObject page=ownerOriginalSearch().readSelected(reference,original,remaining,deadlineNanos);long read=page.has("applicationMaterializedSqlBlockUtf8Bytes")?page.getLong("applicationMaterializedSqlBlockUtf8Bytes"):page.getLong("applicationMaterializedOriginalUtf8Bytes");
            materialized(read);current();requireSource(reference);return page;
        }
        public long materializedUtf8Bytes(){return materializedBytes;}
        @Override public void close()throws Exception {
            if(closed)return;
            try{
                current();for(JSONObject ref:new ArrayList<>(checkedSources.values()))requireSource(ref);
                for(JSONObject row:new ArrayList<>(checkedCarriers.values()))requireCarrier(row,row.getString("_nativeConversationId"));
            }finally{closed=true;deadline.cancel(false);}
        }
    }
    public JSONObject queryHistory(String conversation,JSONObject options,RunControl control)throws Exception{
        String query=options.getString("query"),cursor=options.optString("cursor");int limit=options.getInt("limit"),maxChars=options.getInt("maxChars");long after=options.optLong("createdAfter",-1),before=options.optLong("createdBefore",-1);
        if(query.length()>500||cursor.length()>4000||limit<1||limit>20||maxChars<64||maxChars>16000)throw new IllegalArgumentException("原始历史查询超出预算");
        control.check();synchronized(monitor){if(!exists(conversation))throw new IllegalArgumentException("原私人会话不存在");long started=monotonicNanos.getAsLong(),bytes=0,last=0,upper=0;int rowsRead=0,chars=0;String reason="";JSONArray entries=new JSONArray(),gaps=new JSONArray();boolean more=false;CancellationSignal signal=new CancellationSignal();Runnable cancel=signal::cancel;control.onCancel(cancel);var deadline=DEADLINES.schedule(signal::cancel,500,java.util.concurrent.TimeUnit.MILLISECONDS);
            try{
                String signature=TaskMemoryTrace.rawFingerprint(new JSONObject().put("conversationId",conversation).put("query",query).put("after",after).put("before",before));JSONObject position=decode(cursor);
                if(!cursor.isEmpty()){if(!signature.equals(position.optString("signature")))throw new SecurityException("历史游标不属于此原会话查询");last=position.getLong("last");upper=position.getLong("upper");}
                else try(Cursor row=db.rawQuery("SELECT coalesce(max(sequence),0) FROM assistant_messages WHERE generation=? AND conversation_id=?",new String[]{activeGeneration(),conversation},signal)){row.moveToFirst();upper=row.getLong(0);}
                try(Cursor rows=db.rawQuery("SELECT m.source_id,m.sequence,length(CAST(m.body AS BLOB)),m.at_ms,s.revision FROM assistant_messages m JOIN assistant_sources s ON s.generation=m.generation AND s.source_id=m.source_id WHERE m.generation=? AND m.conversation_id=? AND m.sequence>? AND m.sequence<=? ORDER BY m.sequence LIMIT 101",new String[]{activeGeneration(),conversation,Long.toString(last),Long.toString(upper)},signal)){
                    while(rows.moveToNext()){
                        if(rowsRead>=MAX_ROWS||entries.length()>=limit||chars>=maxChars||monotonicNanos.getAsLong()-started>500_000_000L){more=true;reason=rowsRead>=MAX_ROWS?"source_row_budget":monotonicNanos.getAsLong()-started>500_000_000L?"read_deadline":entries.length()>=limit?"result_limit":"character_limit";break;}
                        if(control.isCancelled()||control.isPaused()||Thread.currentThread().isInterrupted())throw new InterruptedException("原始历史读取已停止");String id=rows.getString(0);long sequence=rows.getLong(1),size=rows.getLong(2);SourceRef ref=new SourceRef("message",id,rows.getLong(4),"owner","private");JSONObject state=state(conversation,ref);rowsRead++;
                        if(!"current".equals(state.optString("status"))){gaps.put(state);last=sequence;continue;}
                        if(size>MAX_BYTES){gaps.put(state.put("status","read_limited").put("reason","message_exceeds_inline_limit"));last=sequence;reason="source_read_limited";continue;}
                        if(size>MAX_BYTES-bytes){more=true;reason="source_byte_budget";break;}
                        if((after>=0&&(rows.isNull(3)||rows.getLong(3)<after))||(before>=0&&(rows.isNull(3)||rows.getLong(3)>before))){last=sequence;continue;}
                        JSONObject message=historyMessage(conversation,id,signal);if(message==null){last=sequence;gaps.put(state.put("status","unavailable").put("reason","source_body_unavailable"));continue;}bytes+=size;
                        if(matches(message.getString("content"),query)){JSONObject row=ConversationHistory.messagePage(conversation,message,0,Math.min(4000,maxChars-chars)).put("role",message.getString("role"));entries.put(row);chars+=row.getString("body").length();}last=sequence;
                    }
                }
                // A late deadline stops further reads; it must not erase already verified hits while
                // publishing a cursor beyond them. These bounded hits are actually delivered below.
                if(monotonicNanos.getAsLong()-started>500_000_000L){more=true;reason="read_deadline";}
                if(control.isCancelled()||control.isPaused()||Thread.currentThread().isInterrupted())throw new InterruptedException("原始历史读取已停止，未返回迟到正文");
                String next=more&&last>0?encode(new JSONObject().put("signature",signature).put("last",last).put("upper",upper)):"";
                return ConversationHistory.proof(new JSONObject().put("status",reason.isEmpty()?"current":"partial").put("reason",reason).put("entries",entries).put("gaps",gaps).put("nextCursor",next).put("hasMore",more).put("charsReturned",chars).put("pageCoverage",new JSONObject().put("sourceKinds",new JSONArray().put("durable_original_message")).put("scannedRows",rowsRead).put("scannedUtf8Bytes",bytes).put("partialReason",reason).put("upperSequence",upper).put("newTailIncluded",false).put("retentionLimitApplied",false).put("originalMessageTotalKnown",false).put("legacyLossRecovered",false).put("importAssignedRecordsNotPromotedToFormalSourceRefs",true).put("byteAccounting","materialized_original_SQLite_body_UTF8_not_physical_io").put("elapsedMillis",(monotonicNanos.getAsLong()-started)/1_000_000L)));
            }finally{deadline.cancel(false);control.removeCancelAction(cancel);}
        }
    }
    private static boolean matches(String text,String query){String value=Normalizer.normalize(text,Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);for(String term:Normalizer.normalize(query,Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).trim().split("\\s+"))if(!value.contains(term))return false;return true;}
    /** Bounded sequence adjacency around an already discovered native source. Not a parent/child
     * graph, a new search scope or an all-history cursor. Exact bodies remain in this same DB. */
    public JSONObject neighbors(String conversation,SourceRef anchor,String fingerprint,String direction,JSONObject budgets,RunControl control)throws Exception{
        if(anchor==null||!"message".equals(anchor.kind)||!"owner".equals(anchor.ownerId)||!"private".equals(anchor.spaceId)||fingerprint==null||!fingerprint.matches("sha256:[0-9a-f]{64}")||!Set.of("before","after").contains(direction))throw new SecurityException("邻域缺少当前私人原消息锚点");
        int limit=budgets.getInt("limit"),maxChars=budgets.getInt("maxChars");if(limit<1||limit>20||maxChars<64||maxChars>16000)throw new IllegalArgumentException("邻域读取超过原文预算");
        control.check();long entryPause=control.pauseRevision();
        synchronized(monitor){
            long started=monotonicNanos.getAsLong(),bytes=0;int scanned=0,chars=0,checked=0;boolean more=false;String reason="";JSONArray entries=new JSONArray(),gaps=new JSONArray();
            JSONObject state=state(conversation,anchor),adjacency=new JSONObject().put("kind","same_conversation_message_sequence").put("anchorSourceRef",anchor.toJson()).put("anchorBodyFingerprint",fingerprint).put("direction",direction).put("order","nearest_first").put("parentChildAncestry",false);
            if(!"current".equals(state.optString("status")))return neighborUnavailable(state.getString("status"),state.getString("reason"),adjacency);
            CancellationSignal signal=new CancellationSignal();Runnable cancel=signal::cancel;control.onCancel(cancel);var deadline=DEADLINES.schedule(signal::cancel,500,java.util.concurrent.TimeUnit.MILLISECONDS);
            try{
                long anchorSequence=historyPosition(conversation,anchor,fingerprint,signal);checked++;
                if(anchorSequence<0)return neighborUnavailable("source_changed","anchor_version_or_fingerprint_unavailable",adjacency);
                String gen=activeGeneration(),comparison=direction.equals("before")?"<":">",order=direction.equals("before")?"DESC":"ASC";
                // The UNION includes import-assigned/deleted gaps without reading their bodies.
                // Neither an unknown historical revision nor the internal import version is a SourceRef.
                String sql="SELECT id,n,bytes,revision,kind,deleted,reason FROM (SELECT * FROM (SELECT m.source_id id,m.sequence n,length(CAST(m.body AS BLOB)) bytes,s.revision revision,'native' kind,s.deleted deleted,s.unavailable_reason reason FROM assistant_messages m JOIN assistant_sources s ON s.generation=m.generation AND s.source_id=m.source_id WHERE m.generation=? AND m.conversation_id=? AND m.sequence"+comparison+"? ORDER BY m.sequence "+order+" LIMIT 101) UNION ALL SELECT * FROM (SELECT record_id id,sequence n,0 bytes,0 revision,'import_assigned' kind,deleted,unavailable_reason reason FROM assistant_import_records WHERE generation=? AND conversation_id=? AND sequence"+comparison+"? ORDER BY sequence "+order+" LIMIT 101)) ORDER BY n "+order+" LIMIT 101";
                try(Cursor rows=db.rawQuery(sql,new String[]{gen,conversation,Long.toString(anchorSequence),gen,conversation,Long.toString(anchorSequence)},signal)){
                    while(rows.moveToNext()){
                        if(scanned>=MAX_ROWS||entries.length()>=limit||chars>=maxChars||monotonicNanos.getAsLong()-started>500_000_000L){more=true;reason=scanned>=MAX_ROWS?"source_row_budget":monotonicNanos.getAsLong()-started>500_000_000L?"read_deadline":entries.length()>=limit?"result_limit":"character_limit";break;}
                        requireHistoryReadControl(control,entryPause);scanned++;
                        if(!"native".equals(rows.getString(4))){gaps.put(new JSONObject().put("recordId",rows.getString(0)).put("status",rows.getInt(5)!=0?"deleted":"provenance_unavailable").put("reason",rows.getInt(5)!=0?rows.getString(6):"import_assigned_identity_not_a_historical_source_ref"));if(reason.isEmpty())reason="neighbor_provenance_gap";continue;}
                        SourceRef ref=new SourceRef("message",rows.getString(0),rows.getLong(3),"owner","private");JSONObject current=state(conversation,ref);checked++;
                        if(!"current".equals(current.optString("status"))){gaps.put(current);if(reason.isEmpty())reason="neighbor_source_unavailable";continue;}
                        long size=rows.getLong(2);if(rows.isNull(2)){gaps.put(current.put("status","unavailable").put("reason","source_body_unavailable"));continue;}
                        if(size>MAX_BYTES){gaps.put(current.put("status","read_limited").put("reason","message_exceeds_inline_limit"));reason="source_read_limited";continue;}
                        if(size>MAX_BYTES-bytes){more=true;reason="source_byte_budget";break;}
                        JSONObject message=historyMessage(conversation,ref.sourceId,signal);if(message==null){gaps.put(current.put("status","unavailable").put("reason","source_body_unavailable"));continue;}bytes+=size;
                        JSONObject row=ConversationHistory.messagePage(conversation,message,0,Math.min(4000,maxChars-chars)).put("role",message.getString("role"));if(row.getString("body").isEmpty()){if(!message.getString("content").isEmpty()){more=true;reason="character_boundary_budget";break;}gaps.put(current.put("status","unavailable").put("reason","empty_original_body"));continue;}entries.put(row);chars+=row.getString("body").length();
                    }
                }
                // A normal appended tail does not revoke any selected old row. Only the exact
                // relevant sources and this anchor are rechecked; no whole-conversation generation.
                if(historyPosition(conversation,anchor,fingerprint,signal)!=anchorSequence)throw new SecurityException("邻域锚点在读取时已改变，未返回旧正文");checked++;
                for(int i=0;i<entries.length();i++){JSONObject row=entries.getJSONObject(i);if(historyPosition(conversation,SourceRef.fromJson(row.getJSONObject("sourceRef")),row.getString("bodyFingerprint"),signal)<0)throw new SecurityException("邻域来源在读取时已撤回或改变，未返回旧正文");checked++;}
                if(monotonicNanos.getAsLong()-started>500_000_000L){more=true;reason="read_deadline";}
                requireHistoryReadControl(control,entryPause);
                return ConversationHistory.proof(new JSONObject().put("status",reason.isEmpty()?"current":"partial").put("reason",reason).put("entries",entries).put("gaps",gaps).put("nextCursor","").put("hasMore",more).put("charsReturned",chars).put("adjacency",adjacency)
                        .put("pageCoverage",new JSONObject().put("sourceKinds",new JSONArray().put("durable_original_message").put("import_metadata_gap_only")).put("scannedRows",scanned).put("sourceMetadataChecks",checked).put("metadataCandidateSqlLimitPerBranch",101).put("scannedUtf8Bytes",bytes).put("byteAccounting","materialized_original_SQLite_body_UTF8_not_physical_io").put("physicalIoMeasured",false).put("partialReason",reason).put("retentionLimitApplied",false).put("originalMessageTotalKnown",false).put("legacyLossRecovered",false).put("adjacentSourceScanExhausted",!more).put("allAdjacentBodiesRead",false).put("continuation","select_a_returned_boundary_message_and_query_neighbors_again").put("elapsedMillis",(monotonicNanos.getAsLong()-started)/1_000_000L)));
            }finally{deadline.cancel(false);control.removeCancelAction(cancel);}
        }
    }
    private static void requireHistoryReadControl(RunControl control,long entryPause)throws InterruptedException{
        if(control.isCancelled()||control.isPaused()||control.pauseRevision()!=entryPause||Thread.currentThread().isInterrupted())throw new InterruptedException("邻域读取已停止或跨过暂停，未返回迟到正文");
    }
    private static JSONObject neighborUnavailable(String status,String reason,JSONObject adjacency)throws Exception{return ConversationHistory.proof(new JSONObject().put("status",status).put("reason",reason).put("entries",new JSONArray()).put("gaps",new JSONArray()).put("nextCursor","").put("hasMore",false).put("charsReturned",0).put("adjacency",adjacency));}
    /** One typed metadata-only identity check. Never materializes source_json, meta_json or body. */
    private long historyPosition(String conversation,SourceRef ref,String fingerprint,CancellationSignal signal)throws Exception{
        try(Cursor row=db.rawQuery("SELECT m.sequence FROM meta a JOIN assistant_messages m ON m.generation=a.value JOIN assistant_sources s ON s.generation=m.generation AND s.source_id=m.source_id JOIN assistant_conversations c ON c.generation=m.generation AND c.conversation_id=m.conversation_id WHERE a.key='assistant_history_generation' AND m.generation=? AND m.conversation_id=? AND s.conversation_id=m.conversation_id AND m.source_id=? AND m.revision=? AND s.revision=m.revision AND m.body_sha=? AND m.body IS NOT NULL AND s.deleted=0 AND s.retired_through<s.revision AND s.context_retired=0 AND c.deleted=0 LIMIT 1",new String[]{activeGeneration(),conversation,ref.sourceId,Long.toString(ref.revision),fingerprint},signal)){return row.moveToFirst()?row.getLong(0):-1;}
    }
    public JSONObject stageLegacyArray(String stage,byte[] original,String sourceSha,Callable<Void> beforeBatch)throws Exception{
        if(active())throw new IllegalStateException("已有DB原文权威，不能再次导入旧数组");if(!sourceSha.equals(shaBytes(original)))throw new SecurityException("原始字节SHA与native review不符");
        List<LegacyConversationImport.Conversation> conversations=LegacyConversationImport.index(original,sourceSha);stageTransaction(()->{beforeBatch.call();ContentValues doc=new ContentValues();doc.put("generation",stage);doc.put("source_sha",sourceSha);doc.put("source_bytes",original.length);doc.put("source_format","legacy_array");db.insertOrThrow("assistant_import_documents",null,doc);return null;});
        int previous=0,total=0;for(LegacyConversationImport.Conversation conversation:conversations){final int gap=previous;stageTransaction(()->{beforeBatch.call();fragment(stage,sourceSha,null,null,original,gap,conversation.bytes.start);saveConversation(stage,conversation.header);return null;});int at=conversation.bytes.start,number=0;
            for(LegacyConversationImport.Range range:conversation.messages){final int start=at,index=number;stageTransaction(()->{beforeBatch.call();String record="import-message:"+sourceSha+":"+conversation.bytes.start+":"+index;fragment(stage,sourceSha,conversation.id,null,original,start,range.start);fragment(stage,sourceSha,conversation.id,record,original,range.start,range.end);JSONObject raw=new JSONObject(LegacyConversationImport.text(original,range));ContentValues row=new ContentValues();row.put("generation",stage);row.put("record_id",record);row.put("conversation_id",conversation.id);row.put("sequence",index+1);row.put("record_version",1);Object revision=raw.opt("revision"),time=raw.opt("at");if(actualInteger(revision)&&((Number)revision).longValue()>0)row.put("historical_revision",((Number)revision).longValue());else row.putNull("historical_revision");if(actualInteger(time))row.put("at_ms",((Number)time).longValue());else row.putNull("at_ms");row.put("role",raw.opt("role") instanceof String?raw.getString("role"):"");row.put("source_sha",sourceSha);row.put("byte_offset",range.start);row.put("byte_length",range.length());row.put("fragment_sha",shaBytes(LegacyConversationImport.bytes(original,range)));db.insertOrThrow("assistant_import_records",null,row);return null;});at=range.end;number++;total++;}
            final int tail=at;stageTransaction(()->{beforeBatch.call();fragment(stage,sourceSha,conversation.id,null,original,tail,conversation.bytes.end);return null;});previous=conversation.bytes.end;
        }
        final int tail=previous;stageTransaction(()->{beforeBatch.call();fragment(stage,sourceSha,null,null,original,tail,original.length);return null;});return new JSONObject().put("messages",0).put("importedRecords",total).put("sources",0).put("conversations",conversations.size());
    }
    private void fragment(String stage,String sha,String conversation,String record,byte[] original,int start,int end){if(start==end)return;ContentValues row=new ContentValues();row.put("generation",stage);row.put("source_sha",sha);row.put("byte_offset",start);row.put("byte_length",end-start);if(conversation==null)row.putNull("conversation_id");else row.put("conversation_id",conversation);if(record==null)row.putNull("record_id");else row.put("record_id",record);row.put("data",Arrays.copyOfRange(original,start,end));db.insertOrThrow("assistant_import_fragments",null,row);}
    static String shaBytes(byte[] bytes)throws Exception{byte[] hash=java.security.MessageDigest.getInstance("SHA-256").digest(bytes);StringBuilder hex=new StringBuilder();for(byte value:hash)hex.append(String.format(Locale.ROOT,"%02x",value&255));return hex.toString();}
    private static String knownReason(String reason){return Set.of("","unknown","user_message_delete","user_conversation_delete","capacity_message_limit","capacity_conversation_limit","owner_forget","source_change","owner_correction").contains(reason)?reason:"unknown";}
    private static boolean actualInteger(Object value){return value instanceof Byte||value instanceof Short||value instanceof Integer||value instanceof Long;}
    /** Staging is invisible and lives only in this original DB. Bodies/versions/times are not invented. */
    public JSONObject stageLegacy(String stage,JSONObject legacy,Callable<Void> beforeBatch)throws Exception{
        if(active())throw new IllegalStateException("已有DB原文权威，不能重导旧副本");JSONArray convs=legacy.getJSONArray("conversations"),sources=legacy.getJSONArray("sourceStates");if(integer(legacy,"version",1)!=2)throw new IllegalArgumentException("旧原文需已知原生version2来源，未补造旧修订");
        Set<String> ids=new HashSet<>();for(int start=0;start<sources.length();start+=100){final int a=start,b=Math.min(start+100,sources.length());stageTransaction(()->{beforeBatch.call();for(int i=a;i<b;i++){JSONObject source=sources.getJSONObject(i);if(!ids.add(source.getString("sourceId")))throw new IllegalArgumentException("旧来源编号重复");saveSource(stage,source);}return null;});}
        int count=0;Set<String> conversationIds=new HashSet<>(),messageIds=new HashSet<>();for(int i=0;i<convs.length();i++){JSONObject conversation=convs.getJSONObject(i);if(!conversationIds.add(conversation.getString("conversationId")))throw new IllegalArgumentException("旧会话编号重复");final JSONObject current=conversation;stageTransaction(()->{beforeBatch.call();saveConversation(stage,current);return null;});JSONArray messages=conversation.getJSONArray("messages");for(int start=0;start<messages.length();start+=40){final int a=start,b=Math.min(start+40,messages.length());stageTransaction(()->{beforeBatch.call();for(int j=a;j<b;j++){JSONObject message=messages.getJSONObject(j);if(!messageIds.add(message.getString("id")))throw new IllegalArgumentException("旧消息编号重复");saveMessage(stage,current.getString("conversationId"),message,j+1);}return null;});}count+=messages.length();}
        try(Cursor missing=db.rawQuery("SELECT 1 FROM assistant_sources s LEFT JOIN assistant_messages m ON m.generation=s.generation AND m.source_id=s.source_id LEFT JOIN assistant_conversations c ON c.generation=s.generation AND c.conversation_id=s.conversation_id WHERE s.generation=? AND s.deleted=0 AND (m.source_id IS NULL OR c.conversation_id IS NULL OR m.conversation_id<>s.conversation_id OR m.revision<>s.revision) LIMIT 1",new String[]{stage})){if(missing.moveToFirst())throw new IllegalArgumentException("旧活动来源缺真实原文/容器，未激活不完整副本");}
        // Tombstones whose old container was already trimmed/deleted still keep their true source
        // identity and revision. Missing header is treated as unavailable, never a live conversation.
        return new JSONObject().put("messages",count).put("importedRecords",0).put("sources",sources.length()).put("conversations",convs.length());
    }
    private <T>T stageTransaction(Callable<T> action)throws Exception{synchronized(monitor){if(active())throw new IllegalStateException("权威已切换，旧stage不能继续");db.beginTransaction();try{T result=action.call();db.setTransactionSuccessful();return result;}finally{db.endTransaction();}}}
    public void activateStaged(String stage,String sourceSha,JSONObject counts)throws Exception{
        if(!Thread.holdsLock(monitor)||active()||!db.inTransaction())throw new SecurityException("迁移激活必须在原TaskStore单事务内，不能覆盖已有权威");
        for(String pair:List.of("assistant_messages:messages","assistant_sources:sources","assistant_conversations:conversations","assistant_import_records:importedRecords")){String[] parts=pair.split(":");try(Cursor row=db.rawQuery("SELECT count(*) FROM "+parts[0]+" WHERE generation=?",new String[]{stage})){row.moveToFirst();if(row.getLong(0)!=integer(counts,parts[1],0))throw new SecurityException("原stage实际记录数不符，未激活");}}
        publishAuthority(stage,sourceSha,counts.getInt("messages"),counts.getInt("sources"));JSONObject marker=authority().put("importedUnverifiedRecords",counts.optInt("importedRecords")).put("historicalRevisionOfImportedRecordsMayBeUnknown",true);ContentValues value=new ContentValues();value.put("value",marker.toString());db.update("meta",value,"key='assistant_history_authority'",null);
    }
    public void recordLegacyCleanup(boolean pending)throws Exception{synchronized(monitor){JSONObject current=authority().put("legacyCleanupPending",pending);ContentValues value=new ContentValues();value.put("value",current.toString());if(db.update("meta",value,"key='assistant_history_authority'",null)!=1)throw new IllegalStateException("原文权威marker已缺失");}}
    /** Called only after the original outer SQLite transaction has positively committed. */
    public void activatedAfterCommit(){synchronized(monitor){reloadAuthority();}}
    public void activationUncertain(){healthy=false;}
    public void discardStage(String stage){synchronized(monitor){if(generation!=null&&generation.equals(stage))throw new SecurityException("不能清理当前原文权威");for(String table:List.of("assistant_import_records","assistant_import_fragments","assistant_import_documents","assistant_messages","assistant_sources","assistant_conversations"))db.delete(table,"generation=?",new String[]{stage});}}
    private static long integer(JSONObject value,String key,long minimum){Object number=value.opt(key);if(!(number instanceof Byte||number instanceof Short||number instanceof Integer||number instanceof Long)||((Number)number).longValue()<minimum)throw new IllegalArgumentException(key+"必须为实际整数");return ((Number)number).longValue();}
    private static String[] append(List<String> list,String value){ArrayList<String> result=new ArrayList<>(list);result.add(value);return result.toArray(new String[0]);}
    private static String encode(JSONObject value){return Base64.getUrlEncoder().withoutPadding().encodeToString(value.toString().getBytes(StandardCharsets.UTF_8));}
    private static JSONObject decode(String cursor)throws Exception{if(cursor==null||cursor.isEmpty())return new JSONObject();if(cursor.length()>4000)throw new IllegalArgumentException("原文游标过长");return new JSONObject(new String(Base64.getUrlDecoder().decode(cursor),StandardCharsets.UTF_8));}
}
