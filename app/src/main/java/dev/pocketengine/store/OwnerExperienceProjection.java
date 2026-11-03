package dev.pocketengine.store;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.assistant.ConversationRepository;
import dev.pocketengine.features.memory.*;
import org.json.*;
import java.util.*;

/** Read-only portable evidence from the original TaskStore connection. No checkpoint, grant,
 * lease, account secret, executable task or import authority is serialized. */
public final class OwnerExperienceProjection {
    private OwnerExperienceProjection(){}
    public interface Gate {boolean blocked(SourceRef source)throws Exception;ToolOutputStore.Sources outputSources();default boolean dependencyKnownBlocked(JSONObject dependency)throws Exception{return false;}}
    public static JSONObject tasks(SQLiteDatabase db,ConversationRepository originals,Gate gate,String cursor,RunControl control)throws Exception {
        control.checkpoint();JSONObject page=TaskAuditStore.summaryPage(db,cursor,20);JSONArray rows=page.getJSONArray("tasks"),out=new JSONArray(),gaps=new JSONArray();
        for(int i=0;i<rows.length();i++){
            control.checkpoint();JSONObject source=rows.getJSONObject(i);if(!"owner".equals(source.optString("actorId"))||!"private".equals(source.optString("spaceId"))||source.has("origin")||"inference".equals(source.optString("kind")))continue;
            String id=source.getString("id");JSONObject row=new JSONObject().put("kind","task_header").put("taskId",id).put("ownerId","owner").put("spaceId","private");
            try{requireRuns(db,originals,gate,id,source.optString("conversationId"),control);for(String key:List.of("goal","summary","status","feature","command","createdAt","updatedAt","conversationId"))if(source.has(key))row.put(key,source.get(key));row.put("readable",true);}catch(Exception unavailable){if(control.isCancelled()||Thread.currentThread().isInterrupted())throw unavailable;row.put("readable",false).put("reason","original_run_sources_unavailable");gaps.put(new JSONObject().put("taskId",id).put("reason","original_run_sources_unavailable"));}
            out.put(row);
        }
        return new JSONObject().put("entries",out).put("gaps",gaps).put("nextCursor",page.getString("nextCursor")).put("hasMore",page.getBoolean("hasMore")).put("sourceRowsExamined",rows.length());
    }
    public static void requireRuns(SQLiteDatabase db,ConversationRepository originals,Gate gate,String task,String conversation,RunControl control)throws Exception {
        control.checkpoint();long[] remaining={4L*1024*1024};ConversationHistory.SourceBytes budget=new ConversationHistory.SourceBytes(){public long remaining(){return remaining[0];}public void charge(long amount){if(amount<0||amount>remaining[0])throw new IllegalStateException("原任务来源复核超出4MiB");remaining[0]-=amount;}};
        try(Cursor rows=db.rawQuery("SELECT run_id FROM runs WHERE task_id=? ORDER BY run_id",new String[]{task})){while(rows.moveToNext()){control.checkpoint();OwnerSourceRunContext.require(db,originals,task,rows.getString(0),conversation,budget,null);OwnerSourceRunContext.requireOutputs(db,gate.outputSources(),task,rows.getString(0),budget,null);}}
        control.checkpoint();
    }
    public static JSONObject audit(SQLiteDatabase db,ConversationRepository originals,Gate gate,String task,String section,String cursor,RunControl control)throws Exception {
        JSONObject header=privateHeader(db,task);requireRuns(db,originals,gate,task,header.optString("conversationId"),control);
        JSONObject page=section.equals("events")?TaskAuditStore.eventPage(db,task,cursor,20,131072):section.equals("operations")?TaskAuditStore.operationPage(db,task,cursor,20,131072):null;
        if(page==null)throw new IllegalArgumentException("只读取原事件或操作页");page=visibleAudit(page);
        if(section.equals("operations")){
            JSONArray rows=page.getJSONArray("entries"),gaps=page.optJSONArray("gaps");if(gaps==null)gaps=new JSONArray();
            for(int i=0;i<rows.length();i++){JSONObject original=rows.getJSONObject(i),projected=projectNativeLearningDraft(original,gate::blocked);rows.put(i,projected);
                if(projected!=original)gaps.put(new JSONObject().put("operationId",projected.getString("operationId")).put("reason","withdrawn_native_learning_draft_excluded").put("occurrenceRetained",true));}
            if(gaps.length()>0)page.put("gaps",gaps);
        }
        requireRuns(db,originals,gate,task,header.optString("conversationId"),control);return page;
    }
    /** This is a read-side restriction for one native operation contract, never an authority
     * inferred from arbitrary nested JSON. The original SQL operation/call remains untouched. */
    @FunctionalInterface public interface LearningSourceBarrier {boolean blocked(SourceRef source)throws Exception;}
    public static JSONObject projectNativeLearningDraft(JSONObject operation,LearningSourceBarrier barrier)throws Exception {
        if(!SourceLearningJob.TOOL.equals(operation.optString("tool")))return operation;
        JSONObject receipt=operation.optJSONObject("receipt");if(receipt==null||!"native_source_learning_draft".equals(receipt.optString("kind")))return operation;
        JSONObject arguments=operation.getJSONObject("arguments"),input=SourceLearningJob.checkedInputs(new JSONArray().put(arguments.getJSONObject("input"))).getJSONObject(0);
        if(!"receipt".equals(operation.optString("state"))||!"model_draft_received".equals(receipt.optString("outcome"))
                ||!operation.optString("operationId").matches("[a-f0-9-]{36}")||!operation.getString("operationId").equals(arguments.optString("operationId"))
                ||!operation.optString("runId").matches("[a-f0-9-]{36}")||!operation.getString("runId").equals(arguments.optString("runId"))
                ||!arguments.optString("manifestHash").matches("[a-f0-9]{64}")||nativeInteger(arguments,"epoch",1)>Integer.MAX_VALUE
                ||nativeInteger(arguments,"index",0)>=SourceLearningJob.MAX_INPUTS||nativeInteger(arguments,"index",0)!=nativeInteger(receipt,"index",0)||!receipt.optString("callId").matches("[a-f0-9-]{36}")
                ||!receipt.optString("draftFingerprint").matches("[a-f0-9]{64}")||!receipt.optString("candidateBindingKey").matches("[a-f0-9]{64}")
                ||!Boolean.FALSE.equals(receipt.opt("formalMemorySaved")))throw new SecurityException("原学习操作的身份/模型回执不完整，未把嵌套JSON当原来源");
        SourceRef source=SourceRef.fromJson(input.getJSONObject("sourceRef"));boolean retired=Boolean.TRUE.equals(receipt.opt("recordedDraftRetired"));
        if(retired){
            if(receipt.has("recordedDraft")||!"source_withdrawn".equals(operation.optString("status"))||!Boolean.FALSE.equals(operation.opt("derivedBodyIncluded"))
                    ||!source.equals(SourceRef.fromJson(receipt.getJSONObject("sourceRef")))||!input.getString("sourceFingerprint").equals(receipt.optString("sourceFingerprint")))throw new SecurityException("已退役学习正文不能携带替代draft或来源");
            return operation; // A passive archive never revives a previously retired body.
        }
        JSONObject recorded=receipt.getJSONObject("recordedDraft");
        if(recorded.length()!=4||!recorded.has("draft")||!"pocketworld.source-learning-draft.v1".equals(recorded.optString("format"))
                ||!source.equals(SourceRef.fromJson(recorded.getJSONObject("sourceRef")))||!input.getString("sourceFingerprint").equals(recorded.optString("sourceFingerprint"))
                ||!TaskStore.capabilityFingerprint(recorded).equals(receipt.getString("draftFingerprint")))throw new SecurityException("原学习draft与原操作输入/hash不一致");
        if(!barrier.blocked(source))return operation;
        JSONObject projected=new JSONObject(operation.toString()),redacted=projected.getJSONObject("receipt");redacted.remove("recordedDraft");
        redacted.put("recordedDraftRetired",true).put("sourceRef",source.toJson()).put("sourceFingerprint",input.getString("sourceFingerprint"))
                .put("retirementReason","known_source_barrier").put("originalDraftFingerprintRetained",true);
        return projected.put("status","source_withdrawn").put("derivedBodyIncluded",false).put("occurrenceRetained",true).put("sourceCurrent",false);
    }
    private static long nativeInteger(JSONObject value,String key,long minimum)throws Exception {Object raw=value.opt(key);if(!(raw instanceof Integer||raw instanceof Long)||((Number)raw).longValue()<minimum)throw new SecurityException("原学习操作整数身份无效");return ((Number)raw).longValue();}
    public static JSONObject outputs(SQLiteDatabase db,ConversationRepository originals,Gate gate,String task,String cursor,RunControl control)throws Exception {
        JSONObject header=privateHeader(db,task);requireRuns(db,originals,gate,task,header.optString("conversationId"),control);if(cursor==null||!cursor.isEmpty()&&!cursor.matches("[0-9a-f-]{36}:[0-9]{1,8}"))throw new IllegalArgumentException("原工具输出游标无效");
        String id="",chunk="-1";if(!cursor.isEmpty()){int split=cursor.lastIndexOf(':');id=cursor.substring(0,split);chunk=cursor.substring(split+1);}
        JSONArray entries=new JSONArray(),gaps=new JSONArray();String next="";boolean more=false;Map<String,Boolean> protectedOutputs=new HashMap<>();
        try(Cursor rows=db.rawQuery("SELECT s.output_id,s.run_id,s.reference_json,s.dependencies_json,s.content_fingerprint,s.retained_chars,s.retained_bytes,c.chunk_index,c.start_char,c.char_count,c.chunk_fingerprint,length(CAST(c.body AS BLOB)),s.capture_kind FROM task_output_sources s LEFT JOIN task_output_chunks c ON c.output_id=s.output_id WHERE s.task_id=? AND (s.output_id>? OR (s.output_id=? AND c.chunk_index>CAST(? AS INTEGER))) ORDER BY s.output_id,c.chunk_index LIMIT 21",new String[]{task,id,id,chunk})){
            while(rows.moveToNext()){
                control.checkpoint();if(entries.length()+gaps.length()>=20){more=true;break;}String output=rows.getString(0);int index=rows.isNull(7)?-1:rows.getInt(7);next=output+":"+Math.max(0,index);
                JSONObject ref=new JSONObject(rows.getString(2));JSONArray dependencies=new JSONArray(rows.getString(3));
                if(index<0)throw new SecurityException("原工具输出缺少chunk，不能备份成完整原件");Boolean protectedOutput=protectedOutputs.get(output);if(protectedOutput==null){protectedOutput=hasProtectedOutputFields(db,output,control);protectedOutputs.put(output,protectedOutput);}
                boolean blocked=protectedOutput||ref.optBoolean("withdrawn")||rows.getLong(11)>262144||!Set.of("NATIVE_RESULT_DATA_JSON","MCP_RESULT_JSON").contains(rows.getString(12));
                try{ToolOutputStore.requireDependencies(dependencies,gate.outputSources());}catch(Exception invalid){blocked=true;}
                if(blocked){gaps.put(new JSONObject().put("outputId",output).put("chunkIndex",index).put("reason",protectedOutput?"protected_native_fields_excluded":"withdrawn_missing_chunk_or_dependency_unavailable"));continue;}
                String text;try(Cursor body=db.rawQuery("SELECT body FROM task_output_chunks WHERE output_id=? AND chunk_index=? LIMIT 1",new String[]{output,Integer.toString(index)})){if(!body.moveToFirst())throw new SecurityException("原工具输出chunk已缺失");text=body.getString(0);}
                String sha=dev.pocketengine.features.local.project.ProjectSources.sha256(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));if(!("sha256:"+sha).equals(rows.getString(10))||text.length()!=rows.getInt(9)||text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length!=rows.getLong(11))throw new SecurityException("原工具输出chunk hash或范围损坏");
                entries.put(new JSONObject().put("kind","tool_output_chunk").put("ownerId","owner").put("spaceId","private").put("taskId",task).put("runId",rows.getString(1)).put("outputId",output).put("chunkIndex",index).put("startChar",rows.getInt(8)).put("chars",rows.getInt(9)).put("sha256",sha).put("text",text).put("outputFingerprint",rows.getString(4)).put("outputChars",rows.getInt(5)).put("outputBytes",rows.getLong(6)).put("dependencies",dependencies).put("reference",ref));
            }
        }
        requireRuns(db,originals,gate,task,header.optString("conversationId"),control);return new JSONObject().put("entries",entries).put("gaps",gaps).put("hasMore",more).put("nextCursor",more?next:"");
    }
    private static final java.util.regex.Pattern PROTECTED_OUTPUT_FIELD=java.util.regex.Pattern.compile("(?i)\"(?:api[_-]?key|authorization|access[_-]?token|refresh[_-]?token|session[_-]?token|token|secret|client[_-]?secret|password|private[_-]?key|cookie|set[_-]?cookie|credentials?|grant(?:[_-]?json)?|lease|execution[_-]?token|pairing[_-]?secret|reasoning(?:[_-]?content)?|encrypted[_-]?content|hidden[_-]?(?:reasoning|analysis)|chain[_-]?of[_-]?thought|thinking)\"\\s*:");
    /** Raw chunks stay byte-exact. If protected native fields exist, omit the whole output
     * before any of its chunks can enter ZIP objects; do not relabel altered bytes as originals.
     * Adjacent chunks overlap for detection of keys split across a stored boundary. */
    private static boolean hasProtectedOutputFields(SQLiteDatabase db,String output,RunControl control)throws Exception {
        long total=0;try(Cursor rows=db.rawQuery("SELECT c.body,coalesce(n.body,'') FROM task_output_chunks c LEFT JOIN task_output_chunks n ON n.output_id=c.output_id AND n.chunk_index=c.chunk_index+1 WHERE c.output_id=? ORDER BY c.chunk_index LIMIT 258",new String[]{output})){int count=0;while(rows.moveToNext()){control.checkpoint();if(++count>257)throw new SecurityException("原工具输出chunk数量超限");String body=rows.getString(0),next=rows.getString(1);total+=body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;if(total>ToolOutputStore.MAX_RUN_BYTES)throw new SecurityException("原工具输出字段扫描超过原run上限");if(PROTECTED_OUTPUT_FIELD.matcher(body+next).find())return true;}}return false;
    }
    private static final Set<String> PRIVATE_FIELDS=Set.of("apikey","authorization","accesstoken","refreshtoken","sessiontoken","token","secret","clientsecret","password","privatekey","cookie","setcookie","credential","credentials","grant","grantjson","lease","executiontoken","pairingsecret","reasoningcontent","reasoning","encryptedcontent","hiddenreasoning","hiddenanalysis","chainofthought","thinking");
    /** Shared by passive legacy-fragment export. Strings are original user content; native
     * credential/hidden-provider fields are excluded without relabeling modified bytes as exact. */
    public static boolean hasProtectedArchiveFields(Object value)throws Exception{return hasProtectedArchiveFields(value,0,new int[]{0});}
    private static boolean hasProtectedArchiveFields(Object value,int depth,int[] nodes)throws Exception {
        if(depth>32||++nodes[0]>100000)throw new SecurityException("原件可见字段超过归档结构预算");
        if(value instanceof JSONObject){JSONObject object=(JSONObject)value;for(var keys=object.keys();keys.hasNext();){String key=keys.next(),normalized=key.replace("_","").replace("-","").toLowerCase(java.util.Locale.ROOT);if(PRIVATE_FIELDS.contains(normalized)||hasProtectedArchiveFields(object.get(key),depth+1,nodes))return true;}}
        else if(value instanceof JSONArray){JSONArray rows=(JSONArray)value;for(int i=0;i<rows.length();i++)if(hasProtectedArchiveFields(rows.get(i),depth+1,nodes))return true;}return false;
    }
    /** Preserve original user-visible values, never a checkpoint/provider dump. Omitted protected
     * fields are explicit coverage gaps; this is not a claim of an unredacted original body. */
    private static JSONObject visibleAudit(JSONObject input)throws Exception {
        JSONArray omitted=new JSONArray();JSONObject out=(JSONObject)visibleValue(input,"",omitted,0,new int[]{0});
        if(omitted.length()>0)out.put("archiveOmittedFields",omitted).put("archiveFiltering","credentials_execution_tokens_and_non_user_visible_provider_reasoning_excluded");return out;
    }
    private static Object visibleValue(Object value,String path,JSONArray omitted,int depth,int[] nodes)throws Exception {
        if(depth>32||++nodes[0]>100000)throw new IllegalStateException("原审计可见字段超过归档结构预算，未截断当完整");
        if(value instanceof JSONObject){JSONObject original=(JSONObject)value,out=new JSONObject();for(var keys=original.keys();keys.hasNext();){String key=keys.next(),location=path+"/"+key;String normalized=key.replace("_","").replace("-","").toLowerCase(java.util.Locale.ROOT);if(PRIVATE_FIELDS.contains(normalized)){if(omitted.length()<128)omitted.put(location);else throw new IllegalStateException("受保护审计字段过多，未忽略省略清单");continue;}out.put(key,visibleValue(original.get(key),location,omitted,depth+1,nodes));}return out;}
        if(value instanceof JSONArray){JSONArray original=(JSONArray)value,out=new JSONArray();for(int i=0;i<original.length();i++)out.put(visibleValue(original.get(i),path+"/"+i,omitted,depth+1,nodes));return out;}return value;
    }
    private static JSONObject privateHeader(SQLiteDatabase db,String task)throws Exception {
        try(Cursor row=db.rawQuery("SELECT ui_header FROM tasks WHERE task_id=? AND length(CAST(ui_header AS BLOB))<=32768 LIMIT 1",new String[]{task})){if(!row.moveToFirst()||row.isNull(0))throw new SecurityException("原任务元数据不可读");JSONObject header=new JSONObject(row.getString(0));if(!task.equals(header.optString("id"))||!"owner".equals(header.optString("actorId"))||!"private".equals(header.optString("spaceId"))||header.has("origin"))throw new SecurityException("原经历只能显选主人私人任务");return header;}
    }
}
