package dev.pocketengine.store;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import org.json.JSONArray;
import org.json.JSONObject;

/** Fixed private extraction files. This record contains evidence, never execution authority. */
final class TaskDatabaseRecoveryManifest {
    static final int MAX_CONVERSATIONS=200,MAX_EXPORT_BYTES=8*1024*1024,MAX_MANIFEST_BYTES=512*1024;
    static final long MAX_CONTENT_BYTES=64L*1024*1024;
    static final String FILE_NAME="extraction-manifest.json";
    static final String OMISSION_MEANING="来源会话数仅计已核来源代次中未删除的会话。遗漏按原导出规则逐会话、逐原因计数；同一条记录可有多个原因，不能把遗漏数当作丢失消息总数。无法读取的整个会话以recordCount=null列明，未声称其消息总数。原删除、撤回、校验失败、附件原件与外部来源授权不恢复。";
    private static final String FORMAT="pocketworld.database-conversation-recovery.v1";
    private static final Set<String> OMIT_REASONS=Set.of("deleted_or_unavailable","retired_for_derived_context","message_read_limited","source_revision_or_role_unavailable","unsupported_historical_record_role","source_integrity_unavailable","attachment_bytes_and_grants_not_exported","external_source_grants_not_exported");
    private static final Set<String> UNREADABLE_REASONS=Set.of("source_conversation_unreadable","source_conversation_read_limited","source_conversation_validation_failed","source_conversation_corrupt");
    private static final String[] FALSE_FLAGS={"preservedSourceOpenedInSQLite","restoresExistingIds","restoresActiveGrants","restoresTaskHistory","restoresAttachmentBytes","restoresCredentialsOrPairings"};
    private static final Set<String> SUMMARY_FIELDS=Set.of("section","sourceConversationCount","conversationCount","messageCount","omittedCount","unreadableConversationCount","partial","contentUtf8Bytes","sourceFingerprint","sourceGeneration","conversations","gaps","sourceReadOnly","preservedSourceOpenedInSQLite","restoresExistingIds","restoresActiveGrants","restoresTaskHistory","restoresAttachmentBytes","restoresCredentialsOrPairings","omissionMeaning");
    private TaskDatabaseRecoveryManifest() { }

    static String exportName(int index) {
        if(index<0||index>=MAX_CONVERSATIONS)throw new IllegalArgumentException("verified_copy_conversation_limit");
        return String.format(Locale.ROOT,"conversation-%03d.json",index);
    }

    static void writeExport(File directory,int index,byte[] bytes,BooleanSupplier current)throws Exception {
        if(bytes.length<1||bytes.length>MAX_EXPORT_BYTES)throw new SecurityException("verified_copy_export_size_unconfirmed");
        writeNew(new File(directory,exportName(index)).toPath(),bytes,current);
    }

    /** The manifest is written last. An interrupted extraction has no completed manifest. */
    static JSONObject seal(File directory,String repair,JSONObject summary,BooleanSupplier current)throws Exception {
        requireRepair(repair);
        JSONObject normalized=normalize(summary,false);
        verifyExports(directory,normalized,current);
        byte[] bytes=new JSONObject().put("format",FORMAT).put("repairId",repair).put("restoration",normalized).toString().getBytes(StandardCharsets.UTF_8);
        if(bytes.length>MAX_MANIFEST_BYTES)throw new SecurityException("verified_copy_manifest_size_unconfirmed");
        writeNew(new File(directory,FILE_NAME).toPath(),bytes,current);
        JSONObject result=new JSONObject(normalized.toString()).put("manifestSha256",DatabaseFilePreservation.sha256(bytes)).put("manifestBytes",bytes.length);
        verify(directory,result,repair,normalized.getString("sourceFingerprint"),current);
        return result;
    }

    static void verify(File directory,JSONObject expected,String repair,String fingerprint,BooleanSupplier current)throws Exception {
        requireRepair(repair);
        JSONObject summary=normalize(expected,true);
        if(!summary.getString("sourceFingerprint").equals(fingerprint))throw new SecurityException("verified_copy_source_identity_changed");
        String hash=digest(expected,"manifestSha256");
        long length=integer(expected,"manifestBytes",1,MAX_MANIFEST_BYTES);
        byte[] bytes=DatabaseFilePreservation.readBounded(new File(directory,FILE_NAME).toPath(),MAX_MANIFEST_BYTES,current);
        if(bytes.length!=length||!hash.equals(DatabaseFilePreservation.sha256(bytes)))throw new SecurityException("verified_copy_manifest_changed");
        JSONObject manifest=json(bytes);
        fields(manifest,Set.of("format","repairId","restoration"));
        if(!FORMAT.equals(manifest.getString("format"))||!repair.equals(manifest.getString("repairId")))throw new SecurityException("verified_copy_repair_identity_changed");
        // Both sides are rebuilt in this fixed schema and field order; raw JSON key order is immaterial.
        if(!summary.toString().equals(normalize(manifest.getJSONObject("restoration"),false).toString()))throw new SecurityException("verified_copy_manifest_summary_changed");
        verifyExports(directory,summary,current);
    }

    static Map<String,Long> omissionCounts(JSONArray omitted)throws Exception {
        if(omitted.length()>10000)throw new SecurityException("verified_copy_omission_count_unconfirmed");
        Map<String,Long> counts=new LinkedHashMap<>();
        for(int i=0;i<omitted.length();i++){
            JSONObject row=omitted.getJSONObject(i);String reason=row.getString("reason");
            if(!OMIT_REASONS.contains(reason))throw new SecurityException("verified_copy_omission_reason_unconfirmed");
            counts.put(reason,Math.addExact(counts.getOrDefault(reason,0L),1L));
        }
        return counts;
    }

    private static JSONObject normalize(JSONObject value,boolean sealed)throws Exception {
        Set<String> allowed=new HashSet<>(SUMMARY_FIELDS);if(sealed){allowed.add("manifestSha256");allowed.add("manifestBytes");}
        fields(value,allowed);
        if(!"conversations".equals(value.getString("section"))||!OMISSION_MEANING.equals(value.getString("omissionMeaning")))throw new SecurityException("verified_copy_scope_unconfirmed");
        if(!Boolean.TRUE.equals(value.get("sourceReadOnly")))throw new SecurityException("verified_copy_source_not_read_only");
        for(String field:FALSE_FLAGS)if(!Boolean.FALSE.equals(value.get(field)))throw new SecurityException("verified_copy_authority_not_excluded");
        long sourceCount=integer(value,"sourceConversationCount",1,MAX_CONVERSATIONS),count=integer(value,"conversationCount",1,sourceCount);
        long messages=integer(value,"messageCount",1,MAX_CONVERSATIONS*5000L),omitted=integer(value,"omittedCount",0,MAX_CONVERSATIONS*10000L);
        long unreadable=integer(value,"unreadableConversationCount",0,sourceCount),totalBytes=integer(value,"contentUtf8Bytes",1,MAX_CONTENT_BYTES);
        if(count+unreadable!=sourceCount)throw new SecurityException("verified_copy_conversation_count_unconfirmed");
        String fingerprint=digest(value,"sourceFingerprint"),generation=identity(value,"sourceGeneration");
        JSONArray conversations=value.getJSONArray("conversations"),gaps=value.getJSONArray("gaps"),normalizedRows=new JSONArray(),normalizedGaps=new JSONArray();
        if(conversations.length()!=count||gaps.length()>MAX_CONVERSATIONS*(OMIT_REASONS.size()+1))throw new SecurityException("verified_copy_conversation_count_unconfirmed");
        if(!(value.get("partial") instanceof Boolean)||value.getBoolean("partial")!=(gaps.length()>0))throw new SecurityException("verified_copy_partial_count_unconfirmed");
        Set<String> sourceIds=new HashSet<>(),restoredIds=new HashSet<>(),unreadableIds=new HashSet<>();
        Map<String,Long> omissionsByConversation=new HashMap<>(),gapsByConversation=new HashMap<>();
        Set<String> reasonKeys=new HashSet<>();long summedMessages=0,summedOmissions=0,summedBytes=0,summedGaps=0;
        for(int i=0;i<conversations.length();i++){
            JSONObject row=conversations.getJSONObject(i);fields(row,Set.of("sourceConversationId","restoredConversationId","messageCount","omittedCount","exportSha256","exportBytes"));
            String source=identity(row,"sourceConversationId"),restored=identity(row,"restoredConversationId");
            long rowMessages=integer(row,"messageCount",0,5000),rowOmissions=integer(row,"omittedCount",0,10000),rowBytes=integer(row,"exportBytes",1,MAX_EXPORT_BYTES);
            if(!sourceIds.add(source)||!restoredIds.add(restored)||source.equals(restored))throw new SecurityException("verified_copy_new_identity_required");
            omissionsByConversation.put(source,rowOmissions);summedMessages+=rowMessages;summedOmissions+=rowOmissions;summedBytes+=rowBytes;
            normalizedRows.put(new JSONObject().put("sourceConversationId",source).put("restoredConversationId",restored).put("messageCount",rowMessages).put("omittedCount",rowOmissions).put("exportSha256",digest(row,"exportSha256")).put("exportBytes",rowBytes));
        }
        for(int i=0;i<gaps.length();i++){
            JSONObject gap=gaps.getJSONObject(i);fields(gap,Set.of("sourceConversationId","reason","recordCount"));
            String source=identity(gap,"sourceConversationId"),reason=gap.getString("reason");Object number=JSONObject.NULL;
            if(!reasonKeys.add(source+"\u0000"+reason))throw new SecurityException("verified_copy_gap_duplicate");
            if(gap.isNull("recordCount")){
                if(!UNREADABLE_REASONS.contains(reason)||sourceIds.contains(source)||!unreadableIds.add(source))throw new SecurityException("verified_copy_unreadable_count_unconfirmed");
            }else{
                if(!OMIT_REASONS.contains(reason)||!sourceIds.contains(source))throw new SecurityException("verified_copy_omission_reason_unconfirmed");
                long n=integer(gap,"recordCount",1,10000);number=n;summedGaps+=n;gapsByConversation.put(source,gapsByConversation.getOrDefault(source,0L)+n);
            }
            normalizedGaps.put(new JSONObject().put("sourceConversationId",source).put("reason",reason).put("recordCount",number));
        }
        for(String source:sourceIds)if(!Objects.equals(omissionsByConversation.get(source),gapsByConversation.getOrDefault(source,0L)))throw new SecurityException("verified_copy_omission_count_unconfirmed");
        Set<String> allSourceIds=new HashSet<>(sourceIds);allSourceIds.addAll(unreadableIds);
        for(String restored:restoredIds)if(allSourceIds.contains(restored))throw new SecurityException("verified_copy_new_identity_required");
        if(unreadableIds.size()!=unreadable||allSourceIds.size()!=sourceCount||summedMessages!=messages||summedOmissions!=omitted||summedGaps!=omitted||summedBytes!=totalBytes)throw new SecurityException("verified_copy_totals_unconfirmed");
        JSONObject result=new JSONObject().put("section","conversations").put("sourceConversationCount",sourceCount).put("conversationCount",count).put("messageCount",messages).put("omittedCount",omitted).put("unreadableConversationCount",unreadable).put("partial",gaps.length()>0).put("contentUtf8Bytes",totalBytes).put("sourceFingerprint",fingerprint).put("sourceGeneration",generation).put("conversations",normalizedRows).put("gaps",normalizedGaps).put("sourceReadOnly",true);
        for(String flag:FALSE_FLAGS)result.put(flag,false);
        return result.put("omissionMeaning",OMISSION_MEANING);
    }

    private static void verifyExports(File directory,JSONObject summary,BooleanSupplier current)throws Exception {
        DatabaseFilePreservation.requireDirectory(directory.toPath());JSONArray rows=summary.getJSONArray("conversations"),gaps=summary.getJSONArray("gaps");
        for(int i=0;i<rows.length();i++){
            DatabaseFilePreservation.requireCurrent(current);JSONObject row=rows.getJSONObject(i);
            byte[] bytes=DatabaseFilePreservation.readBounded(new File(directory,exportName(i)).toPath(),MAX_EXPORT_BYTES,current);
            if(bytes.length!=row.getLong("exportBytes")||!DatabaseFilePreservation.sha256(bytes).equals(row.getString("exportSha256")))throw new SecurityException("verified_copy_export_changed");
            JSONObject payload=json(bytes);
            if(!"pocketworld_conversation_export".equals(payload.getString("schema"))||integer(payload,"schemaVersion",1,1)!=1||!row.getString("sourceConversationId").equals(payload.getString("sourceConversationId"))||!summary.getString("sourceGeneration").equals(payload.getString("sourceGeneration"))||payload.getJSONArray("messages").length()!=row.getLong("messageCount")||payload.getJSONArray("omitted").length()!=row.getLong("omittedCount"))throw new SecurityException("verified_copy_export_identity_changed");
            Map<String,Long> actual=omissionCounts(payload.getJSONArray("omitted")),expected=new LinkedHashMap<>();
            for(int j=0;j<gaps.length();j++){JSONObject gap=gaps.getJSONObject(j);if(row.getString("sourceConversationId").equals(gap.getString("sourceConversationId")))expected.put(gap.getString("reason"),gap.getLong("recordCount"));}
            if(!actual.equals(expected))throw new SecurityException("verified_copy_export_omissions_changed");
        }
        DatabaseFilePreservation.requireCurrent(current);
    }

    private static void writeNew(Path path,byte[] bytes,BooleanSupplier current)throws Exception {
        DatabaseFilePreservation.requireCurrent(current);DatabaseFilePreservation.requireDirectory(path.toAbsolutePath().getParent());
        try(FileChannel file=FileChannel.open(path,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){
            DatabaseFilePreservation.privateFile(path);ByteBuffer buffer=ByteBuffer.wrap(bytes);
            while(buffer.hasRemaining()){DatabaseFilePreservation.requireCurrent(current);file.write(buffer);}file.force(true);
        }
        DatabaseFilePreservation.syncDirectory(path.toAbsolutePath().getParent());DatabaseFilePreservation.requireCurrent(current);
    }

    private static JSONObject json(byte[] bytes)throws Exception{return new JSONObject(StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString());}
    private static void fields(JSONObject value,Set<String> fields)throws Exception{if(value.length()!=fields.size())throw new SecurityException("verified_copy_manifest_fields_unconfirmed");var keys=value.keys();while(keys.hasNext())if(!fields.contains(keys.next()))throw new SecurityException("verified_copy_manifest_fields_unconfirmed");}
    private static String identity(JSONObject value,String key)throws Exception{Object actual=value.get(key);if(!(actual instanceof String)||((String)actual).isEmpty()||((String)actual).length()>240||((String)actual).indexOf('\0')>=0||!StandardCharsets.UTF_8.newEncoder().canEncode((String)actual))throw new SecurityException("verified_copy_identity_unconfirmed");return (String)actual;}
    private static String digest(JSONObject value,String key)throws Exception{String actual=identity(value,key);if(!actual.matches("[a-f0-9]{64}"))throw new SecurityException("verified_copy_digest_unconfirmed");return actual;}
    private static long integer(JSONObject value,String key,long minimum,long maximum)throws Exception{Object actual=value.get(key);if(!(actual instanceof Integer||actual instanceof Long))throw new SecurityException("verified_copy_count_unconfirmed");long number=((Number)actual).longValue();if(number<minimum||number>maximum)throw new SecurityException("verified_copy_count_unconfirmed");return number;}
    private static void requireRepair(String repair){if(repair==null||!repair.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))throw new SecurityException("verified_copy_repair_identity_changed");}
}
