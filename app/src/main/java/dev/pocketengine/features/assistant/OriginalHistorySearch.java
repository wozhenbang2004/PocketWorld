package dev.pocketengine.features.assistant;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.CancellationSignal;
import android.os.OperationCanceledException;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.memory.SourceRef;
import dev.pocketengine.features.memory.TaskMemoryTrace;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Base64;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.json.JSONArray;
import org.json.JSONObject;

/** Native owner original-page lookup only. Does not register a model source, grant or index. */
final class OriginalHistorySearch {
    private final SQLiteDatabase db;private final Object monitor;private final ConversationRepository originals;
    private final Callable<String> generation;private final LongSupplier nanos;
    OriginalHistorySearch(SQLiteDatabase db,Object monitor,ConversationRepository originals,Callable<String> generation,LongSupplier nanos){this.db=db;this.monitor=monitor;this.originals=originals;this.generation=generation;this.nanos=nanos;}
    // Rebuildable hints only: no body, permission or source truth. The original DB is
    // checked on every range read; missing hints rebuild only from that original DB/version.
    private static final int BLOCK_CHARS=4096,MAX_HINT_SOURCES=64,MAX_HINT_BLOCKS=2048;
    private static final long HINT_TTL_NANOS=300_000_000_000L;
    private static final class Block {final int offset,chars,scalarOffset,scalars;final String sha;Block(int o,int n,int s,int c,String h){offset=o;chars=n;scalarOffset=s;scalars=c;sha=h;}}
    private static final class RangeHint {final int total;final java.util.List<Block> blocks;final boolean scalarSafe;final long created=System.nanoTime();RangeHint(int total,java.util.List<Block> blocks,boolean safe){this.total=total;this.blocks=blocks;scalarSafe=safe;}}
    private final java.util.LinkedHashMap<String,RangeHint> hints=new java.util.LinkedHashMap<>(16,0.75f,true);private int hintBlocks;
    private static String digest(String text)throws Exception{byte[] value=java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));char[] hex="0123456789abcdef".toCharArray(),out=new char[value.length*2];for(int i=0;i<value.length;i++){out[i*2]=hex[(value[i]&255)>>>4];out[i*2+1]=hex[value[i]&15];}return new String(out);}
    private void pruneHints(){long now=System.nanoTime();var iterator=hints.entrySet().iterator();while(iterator.hasNext()){var next=iterator.next();if(now-next.getValue().created>HINT_TTL_NANOS){hintBlocks-=next.getValue().blocks.size();iterator.remove();}}}
    private void remember(JSONObject locator,String body,RunControl original,long pause)throws Exception{
        // SQLite substr uses scalar positions. Capture that mapping and hashes only from
        // this already materialized, full-fingerprint-verified native source version.
        java.util.ArrayList<Block> blocks=new java.util.ArrayList<>();int scalar=0;
        for(int at=0;at<body.length();){control(original,pause);int end=Math.min(body.length(),at+BLOCK_CHARS);if(end<body.length()&&Character.isHighSurrogate(body.charAt(end-1))&&Character.isLowSurrogate(body.charAt(end)))end--;String chunk=body.substring(at,end);int count=chunk.codePointCount(0,chunk.length());blocks.add(new Block(at,end-at,scalar,count,digest(chunk)));scalar+=count;at=end;}
        String key=TaskMemoryTrace.rawFingerprint(locator);pruneHints();RangeHint old=hints.remove(key);if(old!=null)hintBlocks-=old.blocks.size();while(!hints.isEmpty()&&(hints.size()>=MAX_HINT_SOURCES||hintBlocks+blocks.size()>MAX_HINT_BLOCKS)){var entry=hints.entrySet().iterator();var first=entry.next();hintBlocks-=first.getValue().blocks.size();entry.remove();}if(blocks.size()<=MAX_HINT_BLOCKS){hints.put(key,new RangeHint(body.length(),blocks,body.indexOf('\0')<0));hintBlocks+=blocks.size();}
    }
    private RangeHint hint(JSONObject locator)throws Exception{pruneHints();return hints.get(TaskMemoryTrace.rawFingerprint(locator));}
    private static JSONObject proof(JSONObject value)throws Exception{return value.put("ownerOnly",true).put("modelReferenceRegistered",false).put("executionAuthorized",false).put("formalMemorySaved",false).put("modelCalled",false);}
    private static void control(RunControl original,long pause)throws InterruptedException{if(original.isCancelled()||original.isPaused()||original.pauseRevision()!=pause||Thread.currentThread().isInterrupted())throw new InterruptedException("主人原文读取已停止或跨过暂停，未返回迟到正文");}
    private static void ownerControl(RunControl original)throws Exception{if(original==null||original.hasHostRun())throw new SecurityException("跨会话原文只供当前原生主人页面，不是模型任务检索");original.check();}
    private static long integer(JSONObject value,String key,long min,long max){Object raw=value.opt(key);if(!(raw instanceof Integer||raw instanceof Long)||((Number)raw).longValue()<min||((Number)raw).longValue()>max)throw new IllegalArgumentException(key+"须为预算内实际整数");return ((Number)raw).longValue();}
    private static String text(JSONObject value,String key,int max)throws Exception{Object raw=value.opt(key);if(!(raw instanceof String)||((String)raw).length()>max)throw new IllegalArgumentException(key+"须为预算内文字");String s=(String)raw;if(s.indexOf('\0')>=0||!StandardCharsets.UTF_8.newEncoder().canEncode(s))throw new IllegalArgumentException(key+"文字无效");return s;}
    private static void keys(JSONObject value,Set<String> allowed)throws Exception{if(value==null||value.length()!=allowed.size())throw new SecurityException("主人原文参数不完整或含替代字段");for(var it=value.keys();it.hasNext();)if(!allowed.contains(it.next()))throw new SecurityException("主人原文参数不能覆盖身份、模型或权限");}
    private static String encode(JSONObject value){return Base64.getUrlEncoder().withoutPadding().encodeToString(value.toString().getBytes(StandardCharsets.UTF_8));}
    private static JSONObject decode(String value)throws Exception{if(value.isEmpty())return new JSONObject();if(value.length()>4000)throw new IllegalArgumentException("原文检索游标过长");return new JSONObject(new String(Base64.getUrlDecoder().decode(value),StandardCharsets.UTF_8));}
    private String upper(String table,String key,String gen,CancellationSignal signal){try(Cursor row=db.rawQuery("SELECT "+key+" FROM "+table+" WHERE generation=? ORDER BY "+key+" DESC LIMIT 1",new String[]{gen},signal)){return row.moveToFirst()?row.getString(0):"";}}
    JSONObject search(JSONObject options,RunControl original)throws Exception{
        keys(options,Set.of("query","cursor","limit","maxChars"));String query=text(options,"query",500),cursor=text(options,"cursor",4000);if(Normalizer.normalize(query,Normalizer.Form.NFKC).trim().isEmpty())throw new IllegalArgumentException("请输入要在保留原文中找的文字");int limit=(int)integer(options,"limit",1,20),maxChars=(int)integer(options,"maxChars",64,16000);ownerControl(original);long pause=original.pauseRevision();JSONObject answer;
        synchronized(monitor){String gen=generation.call();long started=nanos.getAsLong(),bytes=0;int scanned=0,chars=0,phase=0;boolean more=false;String reason="",lastNative="",lastImported="",upperNative="",upperImported="";JSONArray entries=new JSONArray(),gaps=new JSONArray();CancellationSignal signal=new CancellationSignal();Runnable cancel=signal::cancel;original.onCancel(cancel);var deadline=ConversationRepository.DEADLINES.schedule(signal::cancel,500,TimeUnit.MILLISECONDS);
            try{
                String signature=TaskMemoryTrace.rawFingerprint(new JSONObject().put("kind","owner_original_search").put("generation",gen).put("query",query));JSONObject position=decode(cursor);
                if(cursor.isEmpty()){upperNative=upper("assistant_messages","source_id",gen,signal);upperImported=upper("assistant_import_records","record_id",gen,signal);}else{if(!signature.equals(position.optString("signature"))||!gen.equals(position.optString("generation")))throw new SecurityException("原文游标不属于当前权威和文字查询");phase=(int)integer(position,"phase",0,1);lastNative=text(position,"native",240);lastImported=text(position,"imported",512);upperNative=text(position,"upperNative",240);upperImported=text(position,"upperImported",512);}
                while(phase<2){
                    String last=phase==0?lastNative:lastImported,top=phase==0?upperNative:upperImported;if(top.isEmpty()){phase++;continue;}boolean exhausted=true;
                    String sql=phase==0?"SELECT m.source_id,m.conversation_id,length(CAST(m.body AS BLOB)),m.revision,m.body_sha,m.at_ms,s.revision,s.role,s.deleted,s.retired_through,s.context_retired,coalesce(c.deleted,1) FROM assistant_messages m LEFT JOIN assistant_sources s ON s.generation=m.generation AND s.source_id=m.source_id LEFT JOIN assistant_conversations c ON c.generation=m.generation AND c.conversation_id=m.conversation_id WHERE m.generation=? AND m.source_id>? AND m.source_id<=? ORDER BY m.source_id LIMIT ?":"SELECT r.record_id,r.conversation_id,r.byte_length,r.record_version,r.fragment_sha,r.at_ms,r.deleted,coalesce(c.deleted,1),length(f.data) FROM assistant_import_records r LEFT JOIN assistant_conversations c ON c.generation=r.generation AND c.conversation_id=r.conversation_id LEFT JOIN assistant_import_fragments f ON f.generation=r.generation AND f.source_sha=r.source_sha AND f.byte_offset=r.byte_offset WHERE r.generation=? AND r.record_id>? AND r.record_id<=? ORDER BY r.record_id LIMIT ?";
                    try(Cursor rows=db.rawQuery(sql,new String[]{gen,last,top,Integer.toString(ConversationRepository.MAX_ROWS-scanned+1)},signal)){
                        while(rows.moveToNext()){
                            control(original,pause);if(scanned>=ConversationRepository.MAX_ROWS||entries.length()>=limit||chars>=maxChars||nanos.getAsLong()-started>500_000_000L){more=true;reason=scanned>=ConversationRepository.MAX_ROWS?"source_row_budget":entries.length()>=limit?"result_limit":chars>=maxChars?"character_limit":"read_deadline";exhausted=false;break;}
                            String id=rows.getString(0),conversation=rows.getString(1);long size=rows.getLong(2);scanned++;boolean visible=phase==0?!rows.isNull(6)&&!rows.isNull(2)&&rows.getLong(3)==rows.getLong(6)&&rows.getInt(8)==0&&rows.getInt(11)==0:rows.getInt(6)==0&&rows.getInt(7)==0&&!rows.isNull(8)&&rows.getLong(8)==size;
                            if(!visible){gaps.put(new JSONObject().put("recordId",id).put("status","deleted_or_provenance_unavailable"));if(phase==0)lastNative=id;else lastImported=id;continue;}
                            if(size>ConversationRepository.MAX_BYTES){gaps.put(new JSONObject().put("recordId",id).put("status","read_limited").put("reason","original_record_exceeds_inline_budget"));if(phase==0)lastNative=id;else lastImported=id;continue;}
                            if(size>ConversationRepository.MAX_BYTES-bytes){more=true;reason="source_byte_budget";exhausted=false;break;}
                            JSONObject message=phase==0?originals.historyMessage(conversation,id,signal):originals.importedMessage(conversation,id);bytes+=size;if(message==null){gaps.put(new JSONObject().put("recordId",id).put("status","unavailable"));if(phase==0)lastNative=id;else lastImported=id;continue;}
                            String body=message.getString("content");if(phase==0&&!rows.getString(4).equals("sha256:"+ConversationRepository.shaBytes(body.getBytes(StandardCharsets.UTF_8)))){gaps.put(new JSONObject().put("recordId",id).put("status","original_integrity_unavailable"));lastNative=id;continue;}if(matches(body,query)){
                                JSONObject locator=new JSONObject().put("kind",phase==0?"owner_native_original":"owner_imported_original").put("generation",gen).put("conversationId",conversation).put("recordId",id).put("recordVersion",rows.getLong(3)).put("fingerprint",rows.getString(4));boolean retired=phase==0&&(rows.getLong(9)>=rows.getLong(3)||rows.getInt(10)!=0);int offset=excerptOffset(body,query);JSONObject hit=page(locator,message,offset,Math.min(1000,maxChars-chars),retired);
                                if(hit.getString("body").isEmpty()&&!body.isEmpty()){more=true;reason="character_limit";exhausted=false;break;}if(phase==0)remember(locator,body,original,pause);entries.put(hit);chars+=hit.getString("body").length();
                            }
                            if(phase==0)lastNative=id;else lastImported=id;
                        }
                    }
                    if(!exhausted)break;phase++;
                }
                if(nanos.getAsLong()-started>500_000_000L){if(phase<2)more=true;reason="read_deadline";}
                String next=more?encode(new JSONObject().put("signature",signature).put("generation",gen).put("phase",Math.min(phase,1)).put("native",lastNative).put("imported",lastImported).put("upperNative",upperNative).put("upperImported",upperImported)):"";
                // All native owner-visible writes use this same original monitor. Retired is a
                // derived-use restriction, not deletion: owner originals remain visible here.
                control(original,pause);if(!gen.equals(generation.call()))throw new SecurityException("原文权威已改变，未返回旧页面");answer=proof(new JSONObject().put("status",reason.isEmpty()?"current":"partial").put("reason",reason).put("entries",entries).put("gaps",gaps).put("nextCursor",next).put("hasMore",more).put("charsReturned",chars).put("pageCoverage",new JSONObject().put("scannedRows",scanned).put("materializedOriginalRecordUtf8Bytes",bytes).put("byteAccounting","native_body_or_import_original_fragment_not_physical_IO").put("physicalIoMeasured",false).put("paginationSemantics","mutable_native_id_keyset_with_upper_id_watermarks_not_snapshot").put("concurrentWritesMayChangeMatches",true).put("retentionLimitApplied",false).put("completeHistory",false).put("semanticSearch",false).put("elapsedMillis",(nanos.getAsLong()-started)/1_000_000L)));
            }catch(OperationCanceledException stopped){control(original,pause);throw new IllegalStateException("本页原文读取达到截止；未交付的扫描位置没有推进，请保留原游标重试",stopped);}
            finally{deadline.cancel(false);original.removeCancelAction(cancel);}
        }original.check();control(original,pause);return answer;
    }
    private static boolean matches(String body,String query){String text=Normalizer.normalize(body,Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);for(String term:Normalizer.normalize(query,Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).trim().split("\\s+"))if(!text.contains(term))return false;return true;}
    private static int excerptOffset(String body,String query){String term=query.trim().split("\\s+",2)[0];int match=body.indexOf(term);int offset=match<0?0:Math.max(0,match-120);if(offset>0&&offset<body.length()&&Character.isLowSurrogate(body.charAt(offset))&&Character.isHighSurrogate(body.charAt(offset-1)))offset--;return offset;}
    private static JSONObject page(JSONObject locator,JSONObject message,int offset,int maxChars,boolean retired)throws Exception{
        String body=message.getString("content");if(offset<0||offset>body.length()||offset>0&&offset<body.length()&&Character.isLowSurrogate(body.charAt(offset))&&Character.isHighSurrogate(body.charAt(offset-1)))throw new IllegalArgumentException("原文UTF-16范围无效");int end=(int)Math.min((long)body.length(),(long)offset+maxChars);if(end>offset&&end<body.length()&&Character.isHighSurrogate(body.charAt(end-1))&&Character.isLowSurrogate(body.charAt(end)))end--;
        return rangePage(locator,message,body.substring(offset,end),offset,body.length(),retired);
    }
    private static JSONObject rangePage(JSONObject locator,JSONObject message,String body,int offset,int total,boolean retired)throws Exception{
        boolean imported="owner_imported_original".equals(locator.getString("kind"));JSONObject result=proof(new JSONObject().put("ownerLocator",new JSONObject(locator.toString())).put("conversationId",locator.getString("conversationId")).put("recordId",locator.getString("recordId")).put("recordVersion",locator.getLong("recordVersion")).put("role",message.optString("role")).put("body",body).put("readRange",new JSONObject().put("offset",offset).put("chars",body.length()).put("totalChars",total).put("unit","UTF-16")).put("nextOffset",offset+body.length()<total?offset+body.length():-1).put("status","current").put("derivedUseStatus",imported?"provenance_unavailable":retired?"retired_for_derived_context":"not_authorized_by_this_owner_read").put("importAssignedIdentity",imported).put("historicalRevision",imported?message.opt("historicalRevision"):locator.getLong("recordVersion")).put("historicalRevisionKnown",!imported||message.optBoolean("historicalRevisionKnown")).put("at",message.has("at")?message.get("at"):JSONObject.NULL).put("timeKnown",message.opt("at") instanceof Number));
        if(imported)result.put("originalByteReference",message.getJSONObject("originalByteReference"));return result;
    }
    private JSONObject nativeRange(JSONObject locator,JSONObject state,RangeHint hint,int offset,int maxChars,CancellationSignal signal,RunControl original,long pause,long maximumBytes)throws Exception{
        if(offset>hint.total)throw new IllegalArgumentException("原文UTF-16范围无效");String gen=locator.getString("generation"),conversation=locator.getString("conversationId"),id=locator.getString("recordId"),version=Long.toString(locator.getLong("recordVersion")),fingerprint=locator.getString("fingerprint");JSONObject metadata=new JSONObject().put("role",state.getString("role"));
        // The immutable source version/hash predicate is rechecked on each exact SQL slice.
        String where=" FROM assistant_messages WHERE generation=? AND conversation_id=? AND source_id=? AND revision=? AND body_sha=? AND body IS NOT NULL LIMIT 1";
        try(Cursor row=db.rawQuery("SELECT at_ms"+where,new String[]{gen,conversation,id,version,fingerprint},signal)){if(!row.moveToFirst())return unavailable("source_changed","原文修订/指纹改变，未展示新正文");if(!row.isNull(0))metadata.put("at",row.getLong(0));}
        StringBuilder result=new StringBuilder();long bytes=0;int loadedChars=0;int wanted=(int)Math.min((long)hint.total,(long)offset+maxChars);
        for(Block block:hint.blocks){if(block.offset+block.chars<=offset||block.offset>=wanted)continue;control(original,pause);String[] args={Integer.toString(block.scalarOffset+1),Integer.toString(block.scalars),gen,conversation,id,version,fingerprint};long size;
            try(Cursor preflight=db.rawQuery("SELECT length(CAST(substr(body,?,?) AS BLOB))"+where,args,signal)){if(!preflight.moveToFirst()||preflight.isNull(0))return unavailable("source_changed","原范围已不可用");size=preflight.getLong(0);if(size>maximumBytes-bytes)return unavailable("read_limited","原范围超过本页实际字节预算");}
            String chunk;try(Cursor row=db.rawQuery("SELECT substr(body,?,?)"+where,args,signal)){if(!row.moveToFirst())return unavailable("source_changed","原范围已变化");chunk=row.getString(0);}bytes+=size;loadedChars+=chunk.length();
            if(chunk.length()!=block.chars||!digest(chunk).equals(block.sha))return unavailable("source_changed","原件范围指纹不符，未展示替代正文；请重新搜索");
            int from=Math.max(0,offset-block.offset),to=Math.min(block.chars,wanted-block.offset);if(from>0&&from<chunk.length()&&Character.isLowSurrogate(chunk.charAt(from))&&Character.isHighSurrogate(chunk.charAt(from-1)))throw new IllegalArgumentException("原文范围不能从代理对中间开始");if(to>from&&to<chunk.length()&&Character.isHighSurrogate(chunk.charAt(to-1))&&Character.isLowSurrogate(chunk.charAt(to)))to--;result.append(chunk,from,to);if(to<Math.min(block.chars,wanted-block.offset))break;
        }
        boolean retired=state.optLong("memorySourceRetiredThrough")>=locator.getLong("recordVersion")||state.optBoolean("memoryContextRetired");return rangePage(locator,metadata,result.toString(),offset,hint.total,retired).put("applicationMaterializedSqlBlockUtf8Bytes",bytes).put("fullOriginalMaterializedInApp",loadedChars==hint.total).put("readMode","verified_native_SQL_substr_blocks").put("byteAccounting","application_materialized_SQL_substr_UTF8_not_SQL_internal_work_or_disk_IO").put("sqlInternalWorkMeasured",false).put("physicalIoMeasured",false).put("rangeHintsAreDerived",true);
    }
    JSONObject read(JSONObject request,RunControl original)throws Exception{return read(request,original,false,ConversationRepository.MAX_BYTES,Long.MAX_VALUE);}
    /** Only the native typed-input projection can enter this path; never a model search command. */
    JSONObject readSelected(JSONObject reference,RunControl original,long maximumBytes,long deadlineNanos)throws Exception{
        JSONObject ref=OwnerSourceReferences.checked(new JSONArray().put(reference)).getJSONObject(0),source=ref.getJSONObject("sourceRef");
        JSONObject locator=new JSONObject().put("kind","owner_native_original").put("generation",ref.getString("generation")).put("conversationId",ref.getString("sourceConversationId"))
                .put("recordId",source.getString("sourceId")).put("recordVersion",source.getLong("revision")).put("fingerprint",ref.getString("bodyFingerprint"));
        JSONObject page=read(new JSONObject().put("ownerLocator",locator).put("offset",ref.getInt("offset")).put("maxChars",Math.max(64,ref.getInt("chars"))),original,true,maximumBytes,deadlineNanos);
        if(!"current".equals(page.optString("status")))throw new IllegalStateException("主人所选原文版本已不可派生使用："+page.optString("status"));
        String body=page.getString("body");int chars=ref.getInt("chars");
        if(body.length()<chars||chars<body.length()&&Character.isHighSurrogate(body.charAt(chars-1))&&Character.isLowSurrogate(body.charAt(chars)))throw new IllegalArgumentException("所选精确UTF-16范围已不可用或拆开了代理对");
        return page.put("body",body.substring(0,chars)).put("readRange",new JSONObject().put("offset",ref.getInt("offset")).put("chars",chars).put("unit","UTF-16"));
    }
    private JSONObject read(JSONObject request,RunControl original,boolean derived,long maximumBytes,long deadlineNanos)throws Exception{
        keys(request,Set.of("ownerLocator","offset","maxChars"));JSONObject locator=request.getJSONObject("ownerLocator");keys(locator,Set.of("kind","generation","conversationId","recordId","recordVersion","fingerprint"));String kind=text(locator,"kind",40),gen=text(locator,"generation",80),conversation=text(locator,"conversationId",2048),id=text(locator,"recordId",512),fingerprint=text(locator,"fingerprint",80);long version=integer(locator,"recordVersion",1,Long.MAX_VALUE);int offset=(int)integer(request,"offset",0,Integer.MAX_VALUE),maxChars=(int)integer(request,"maxChars",64,16000);if(!Set.of("owner_native_original","owner_imported_original").contains(kind)||!(kind.equals("owner_native_original")?fingerprint.matches("sha256:[0-9a-f]{64}"):fingerprint.matches("[0-9a-f]{64}")))throw new SecurityException("不是当前native主人原件引用");if(derived){if(original==null||maximumBytes<1||maximumBytes>ConversationRepository.MAX_BYTES)throw new SecurityException("原文派生投影缺少当前原生任务/字节预算");original.check();}else ownerControl(original);long pause=original.pauseRevision();JSONObject result=null;
        synchronized(monitor){control(original,pause);if(!gen.equals(generation.call()))return unavailable("source_changed","原文权威已改变，请重新搜索");CancellationSignal signal=new CancellationSignal();Runnable cancel=signal::cancel;long remaining=derived?Math.min(500_000_000L,deadlineNanos-System.nanoTime()):500_000_000L;if(remaining<=0)throw new IllegalStateException("原文引用批次读取已到截止");original.onCancel(cancel);var deadline=ConversationRepository.DEADLINES.schedule(signal::cancel,remaining,TimeUnit.NANOSECONDS);long started=nanos.getAsLong();try{
            JSONObject message=null;boolean retired=false,originalReadCompleted=false;if(kind.equals("owner_native_original")){
                JSONObject state=originals.metadata(id);if(state==null||state.getBoolean("deleted")||!conversation.equals(state.getString("conversationId")))return unavailable("deleted_or_unavailable","原消息或原会话已删除/不可见");if(version!=state.getLong("revision"))return unavailable("source_changed","原修订已改变，请重新搜索，未展示新正文");if(derived&&(state.optLong("memorySourceRetiredThrough")>=version||state.optBoolean("memoryContextRetired")))return unavailable("retired","这份原文仍可由主人查看，但已停止用于模型派生上下文");
                RangeHint found=hint(locator);if(found==null||!found.scalarSafe){
                    // A derived hint is never authority: a miss may rebuild from the same
                    // current original under the unchanged 1MiB/500ms full-read bound.
                    message=originals.historyMessage(conversation,id,signal,maximumBytes);if(message==null)return unavailable("unavailable","此版本原文缺失");String body=message.getString("content");if(!fingerprint.equals("sha256:"+digest(body)))return unavailable("source_changed","原文指纹不符，未展示替代正文");remember(locator,body,original,pause);retired=state.optLong("memorySourceRetiredThrough")>=version||state.optBoolean("memoryContextRetired");result=page(locator,message,offset,maxChars,retired).put("rangeHintsRebuilt",true).put("applicationMaterializedOriginalUtf8Bytes",body.getBytes(StandardCharsets.UTF_8).length).put("fullOriginalMaterializedInApp",true).put("readMode",found!=null&&!found.scalarSafe?"bounded_native_full_original_embedded_nul":"bounded_native_full_original_hint_rebuild").put("sqlInternalWorkMeasured",false).put("physicalIoMeasured",false);
                }else result=nativeRange(locator,state,found,offset,maxChars,signal,original,pause,maximumBytes);control(original,pause);if(nanos.getAsLong()-started>500_000_000L)throw new IllegalStateException("原件范围读取超过本页截止，未返回迟到正文");
                // A hit reads verified SQL blocks only. A miss above is the bounded
                // original-DB rebuild, never a cached body or replacement source.
                originalReadCompleted=true;
            }else{
                try(Cursor row=db.rawQuery("SELECT r.record_version,r.fragment_sha,r.byte_length,r.deleted,coalesce(c.deleted,1) FROM assistant_import_records r LEFT JOIN assistant_conversations c ON c.generation=r.generation AND c.conversation_id=r.conversation_id WHERE r.generation=? AND r.conversation_id=? AND r.record_id=? LIMIT 1",new String[]{gen,conversation,id},signal)){if(!row.moveToFirst()||row.getInt(3)!=0||row.getInt(4)!=0)return unavailable("deleted_or_unavailable","旧原件或会话已删除/不可见");if(version!=row.getLong(0)||!fingerprint.equals(row.getString(1)))return unavailable("source_changed","旧原件内部版本/字节指纹改变，未展示替代正文");if(row.getLong(2)>ConversationRepository.MAX_BYTES)return unavailable("read_limited","原件超过本页预算");}message=originals.importedMessage(conversation,id);if(message==null)return unavailable("unavailable","旧原件段缺失");
            }
            control(original,pause);if(nanos.getAsLong()-started>500_000_000L)throw new IllegalStateException("原件读取超过本页截止，未返回迟到正文");if(!originalReadCompleted)result=page(locator,message,offset,maxChars,retired).put("fullImportFragmentMaterializedInApp",true).put("readMode","bounded_full_import_fragment_JSON");
        }finally{deadline.cancel(false);original.removeCancelAction(cancel);}}
        original.check();control(original,pause);if(derived&&System.nanoTime()>deadlineNanos)throw new IllegalStateException("原文引用批次读取达到截止，未返回迟到正文");if(result==null)throw new IllegalStateException("原范围未完成核对");return result;
    }
    private static JSONObject unavailable(String status,String reason)throws Exception{return proof(new JSONObject().put("status",status).put("reason",reason));}
}
