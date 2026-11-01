package dev.pocketengine.features.memory;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.CancellationSignal;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Retained safe native returned JSON in the original TaskStore SQLite database. No task authority,
 * source permission, remote wire completeness, execution result or physical I/O claim is created. */
public final class ToolOutputStore {
    private ToolOutputStore(){}
    public static final int MAX_RUN_OUTPUTS=64,MAX_DEPENDENCIES=64;
    public static final long MAX_RUN_BYTES=8L*1024*1024;
    private static final String UUID_PATTERN="[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    public interface Sources {
        String attachmentGeneration(String id,String sha256)throws Exception;
        boolean attachmentCurrent(String id,String sha256,String generation)throws Exception;
    }
    public interface ReadBudget {
        long remaining()throws Exception;
        void check()throws Exception;
        /** Actual materialized UTF-8 text after an admitted size probe; never physical disk I/O. */
        void charge(long bytes,String category)throws Exception;
        default void chunks(int count){}
    }
    public static final class Call {
        public final String task,run,tool,operation,output;public final int epoch;public final ToolOutputCapture.Kind kind;
        private final Object issuer;private final SQLiteDatabase database;
        private Call(Object issuer,SQLiteDatabase db,String task,String run,int epoch,String tool,String operation,ToolOutputCapture.Kind kind){this.issuer=issuer;database=db;this.task=task;this.run=run;this.epoch=epoch;this.tool=tool;this.operation=operation;this.kind=kind;output=UUID.randomUUID().toString();}
    }
    /** TaskStore checks its original tuple/control/prepared row before issuing this opaque call. */
    public static Call issue(Object originalIssuer,SQLiteDatabase db,String task,String run,int epoch,String tool,String preparedOperation,ToolOutputCapture.Kind kind){
        if(originalIssuer==null||db==null||kind==null||!id(task)||!id(run)||epoch<1||tool==null||!tool.matches("[A-Za-z_][A-Za-z0-9_-]{0,119}")||preparedOperation==null||!preparedOperation.isEmpty()&&!id(preparedOperation))throw invalid();
        return new Call(originalIssuer,db,task,run,epoch,tool,preparedOperation,kind);
    }
    public static final class Encoded {
        final ToolOutputEncoding.Payload payload;final JSONArray dependencies;final Boolean upstream;
        private Encoded(ToolOutputEncoding.Payload payload,JSONArray dependencies,Boolean upstream){this.payload=payload;this.dependencies=dependencies;this.upstream=upstream;}
    }
    public static Encoded encode(JSONObject safeNative,Boolean upstream,ToolOutputCapture.TextCleaner cleaner,JSONArray dependencies)throws Exception{
        return new Encoded(ToolOutputEncoding.encode(safeNative,cleaner),checkedDependencies(dependencies),upstream);
    }
    /** Dependency identity comes from an actual correlated native document receipt, not model args. */
    public static JSONArray dependencies(Call call,JSONObject safeNative,Sources sources)throws Exception{
        JSONArray rows=new JSONArray();if(!"attachments_read_document".equals(call.tool))return rows;
        String id=safeNative.getString("attachmentId"),sha=safeNative.getString("sha256");if(!id(id)||!sha.matches("[a-f0-9]{64}"))throw invalid();String generation=sources==null?null:sources.attachmentGeneration(id,sha);
        return rows.put(new JSONObject().put("kind","attachment").put("id",id).put("sha256",sha).put("generation",generation==null?JSONObject.NULL:generation));
    }
    public static JSONArray checkedDependencies(JSONArray supplied)throws Exception{
        if(supplied==null||supplied.length()>MAX_DEPENDENCIES)throw invalid();JSONArray rows=new JSONArray();Set<String> seen=new java.util.HashSet<>();
        for(int i=0;i<supplied.length();i++){
            JSONObject row=supplied.getJSONObject(i);Object generation=row.opt("generation");String id=row.optString("id"),sha=row.optString("sha256");
            if(row.length()!=4||!"attachment".equals(row.opt("kind"))||!id(id)||!sha.matches("[a-f0-9]{64}")||!(generation==JSONObject.NULL||generation instanceof String&&((String)generation).matches("sha256:[a-f0-9]{64}"))||!seen.add(id+"\n"+sha+"\n"+generation))throw invalid();
            rows.put(new JSONObject().put("kind","attachment").put("id",id).put("sha256",sha).put("generation",generation));
        }return rows;
    }
    public static void requireDependencies(JSONArray supplied,Sources sources)throws Exception{
        JSONArray rows=checkedDependencies(supplied);for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);if(row.opt("generation")==JSONObject.NULL||sources==null||!sources.attachmentCurrent(row.getString("id"),row.getString("sha256"),row.getString("generation")))throw new TaskStoreMemoryReader.RawMessageUnavailable("native_output_source_unavailable");}
    }
    public static void createSchema(SQLiteDatabase db){
        db.execSQL("CREATE TABLE IF NOT EXISTS task_output_sources(output_id TEXT PRIMARY KEY,task_id TEXT NOT NULL,run_id TEXT NOT NULL,epoch INTEGER NOT NULL,operation_id TEXT NOT NULL,tool TEXT NOT NULL,capture_kind TEXT NOT NULL,reference_json TEXT NOT NULL,dependencies_json TEXT NOT NULL,retained_bytes INTEGER NOT NULL,retained_chars INTEGER NOT NULL,content_fingerprint TEXT NOT NULL,created_at INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX IF NOT EXISTS task_output_sources_run ON task_output_sources(task_id,run_id,created_at,output_id)");
        db.execSQL("CREATE TABLE IF NOT EXISTS task_output_chunks(output_id TEXT NOT NULL,chunk_index INTEGER NOT NULL,start_char INTEGER NOT NULL,char_count INTEGER NOT NULL,utf8_bytes INTEGER NOT NULL,chunk_fingerprint TEXT NOT NULL,body TEXT NOT NULL,PRIMARY KEY(output_id,chunk_index))");
        // No product task deletion API is created. These cleanup effects remain in the original
        // explicit SQLite DELETE transaction; stopping, finishing and forgetting are not deletion.
        db.execSQL("CREATE TRIGGER IF NOT EXISTS task_output_task_delete AFTER DELETE ON tasks BEGIN DELETE FROM task_output_sources WHERE task_id=OLD.task_id; END");
        db.execSQL("CREATE TRIGGER IF NOT EXISTS task_output_run_delete AFTER DELETE ON runs BEGIN DELETE FROM task_output_sources WHERE task_id=OLD.task_id AND run_id=OLD.run_id; END");
        db.execSQL("CREATE TRIGGER IF NOT EXISTS task_output_header_delete AFTER DELETE ON task_output_sources BEGIN DELETE FROM task_output_chunks WHERE output_id=OLD.output_id; END");
    }
    public static ToolOutputCapture.Result save(SQLiteDatabase db,Object issuer,Call call,Encoded encoded)throws Exception{
        if(call==null||encoded==null||issuer!=call.issuer||db!=call.database)throw invalid();long count,bytes;
        try(Cursor row=db.rawQuery("SELECT count(*),coalesce(sum(retained_bytes),0) FROM task_output_sources WHERE task_id=? AND run_id=?",new String[]{call.task,call.run})){if(!row.moveToFirst())throw invalid();count=row.getLong(0);bytes=row.getLong(1);}
        if(count>=MAX_RUN_OUTPUTS||bytes<0||encoded.payload.bytes>MAX_RUN_BYTES-bytes)return ToolOutputCapture.unavailable("native_output_run_budget");
        JSONObject reference=ToolOutputCapture.checkedRefs(new JSONArray().put(new JSONObject().put("taskId",call.task).put("runId",call.run).put("epoch",call.epoch).put("tool",call.tool).put("operationId",call.operation).put("sourceId",sourceId(call.task,call.run,call.output)).put("revision",encoded.payload.fingerprint).put("captureKind",call.kind.name())
            .put("retainedRange",new JSONObject().put("offset",0).put("chars",encoded.payload.text.length()).put("unit","UTF-16").put("utf8Bytes",encoded.payload.bytes).put("previewChars",TaskMemoryTrace.safeEnd(encoded.payload.text,0,4000))).put("retainedNativeDataComplete",encoded.payload.complete).put("upstreamTruncated",encoded.upstream==null?JSONObject.NULL:encoded.upstream))).getJSONObject(0);
        ContentValues row=new ContentValues();row.put("output_id",call.output);row.put("task_id",call.task);row.put("run_id",call.run);row.put("epoch",call.epoch);row.put("operation_id",call.operation);row.put("tool",call.tool);row.put("capture_kind",call.kind.name());row.put("reference_json",reference.toString());row.put("dependencies_json",encoded.dependencies.toString());row.put("retained_bytes",encoded.payload.bytes);row.put("retained_chars",encoded.payload.text.length());row.put("content_fingerprint",encoded.payload.fingerprint);row.put("created_at",System.currentTimeMillis());db.insertOrThrow("task_output_sources",null,row);
        for(ToolOutputEncoding.Chunk chunk:encoded.payload.chunks){ContentValues value=new ContentValues();value.put("output_id",call.output);value.put("chunk_index",chunk.sequence);value.put("start_char",chunk.start);value.put("char_count",chunk.chars);value.put("utf8_bytes",chunk.bytes);value.put("chunk_fingerprint",chunk.fingerprint);value.put("body",chunk.text);db.insertOrThrow("task_output_chunks",null,value);}
        String reason="";for(int i=0;i<encoded.dependencies.length();i++)if(encoded.dependencies.getJSONObject(i).opt("generation")==JSONObject.NULL)reason="native_output_provenance_unavailable";
        if(reason.isEmpty())reason=!encoded.payload.complete?"native_output_capture_limit":Boolean.TRUE.equals(encoded.upstream)?"native_output_upstream_truncated":encoded.upstream==null?"native_output_upstream_completeness_unknown":"";
        return ToolOutputCapture.recorded(reason.isEmpty()?"stored":"partial",reason,new JSONArray().put(reference));
    }
    public static boolean hasCapturedOutput(SQLiteDatabase db,String task,String run){try(Cursor row=db.rawQuery("SELECT 1 FROM task_output_sources WHERE task_id=? AND run_id=? LIMIT 1",new String[]{task,run})){return row.moveToFirst();}}
    public static String sourceId(String task,String run,String output){if(!id(task)||!id(run)||!id(output))throw invalid();return "task:"+task+":run:"+run+":output:"+output;}
    public static String outputId(String task,String run,String source){String prefix="task:"+task+":run:"+run+":output:";if(source==null||!source.startsWith(prefix)||!id(source.substring(prefix.length())))throw invalid();return source.substring(prefix.length());}
    public static final class Header {
        final String output;private final JSONObject ref;private final JSONArray deps;
        Header(String output,JSONObject ref,JSONArray deps){this.output=output;this.ref=ref;this.deps=deps;}
        public JSONObject reference()throws Exception{return new JSONObject(ref.toString());}
        public JSONArray dependencies()throws Exception{return new JSONArray(deps.toString());}
    }
    public static Header header(SQLiteDatabase db,String task,String run,String source,ReadBudget budget,CancellationSignal signal)throws Exception{
        String output=outputId(task,run,source);String[] args={task,run,output};long size;budget.check();
        try(Cursor row=db.rawQuery("SELECT length(CAST(reference_json AS BLOB))+length(CAST(dependencies_json AS BLOB))+length(CAST(content_fingerprint AS BLOB)) FROM task_output_sources WHERE task_id=? AND run_id=? AND output_id=? LIMIT 1",args,signal)){if(!row.moveToFirst())return null;size=row.getLong(0);}
        if(size<0||size>65536||size>budget.remaining())throw limited();
        try(Cursor row=db.rawQuery("SELECT reference_json,dependencies_json,content_fingerprint,retained_chars,retained_bytes FROM task_output_sources WHERE task_id=? AND run_id=? AND output_id=? AND length(CAST(reference_json AS BLOB))+length(CAST(dependencies_json AS BLOB))+length(CAST(content_fingerprint AS BLOB))<=CAST(? AS INTEGER) LIMIT 1",new String[]{task,run,output,Long.toString(size)},signal)){
            if(!row.moveToFirst())throw invalid();String text=row.getString(0),dependencies=row.getString(1);budget.charge(utf8(text)+utf8(dependencies)+utf8(row.getString(2)),"header");JSONObject ref=ToolOutputCapture.checkedRefs(new JSONArray().put(new JSONObject(text))).getJSONObject(0);
            if(!task.equals(ref.getString("taskId"))||!run.equals(ref.getString("runId"))||!source.equals(ref.getString("sourceId"))||!row.getString(2).equals(ref.getString("revision"))||row.getLong(3)!=ref.getJSONObject("retainedRange").getLong("chars")||row.getLong(4)!=ref.getJSONObject("retainedRange").getLong("utf8Bytes"))throw invalid();return new Header(output,ref,checkedDependencies(new JSONArray(dependencies)));
        }
    }
    public static long inlineReadBytes(SQLiteDatabase db,Header header,CancellationSignal signal)throws Exception{
        try(Cursor row=db.rawQuery("SELECT coalesce(sum(length(CAST(body AS BLOB))+length(CAST(chunk_fingerprint AS BLOB))),0),count(*) FROM task_output_chunks WHERE output_id=?",new String[]{header.output},signal)){if(!row.moveToFirst()||row.getLong(1)>ToolOutputEncoding.MAX_CHUNKS)throw invalid();return row.getLong(0);}
    }
    public static String retainedText(SQLiteDatabase db,Header header,ReadBudget budget,CancellationSignal signal)throws Exception{return range(db,header,0,header.ref.getJSONObject("retainedRange").getInt("chars"),budget,signal);}
    public static String range(SQLiteDatabase db,Header header,int offset,int maxChars,ReadBudget budget,CancellationSignal signal)throws Exception{
        int total=header.ref.getJSONObject("retainedRange").getInt("chars");if(offset<0||offset>total||maxChars<1||maxChars>ToolOutputCapture.MAX_OUTPUT_BYTES)throw invalid();int end=(int)Math.min(total,(long)offset+maxChars),covered=offset;StringBuilder result=new StringBuilder();budget.check();
        try(Cursor rows=db.rawQuery("SELECT chunk_index,start_char,char_count,utf8_bytes,length(CAST(body AS BLOB)),length(CAST(chunk_fingerprint AS BLOB)) FROM task_output_chunks WHERE output_id=? AND start_char<CAST(? AS INTEGER) AND start_char+char_count>CAST(? AS INTEGER) ORDER BY chunk_index LIMIT 258",new String[]{header.output,Integer.toString(end),Integer.toString(offset)},signal)){
            int count=0;while(rows.moveToNext()){
                budget.check();if(++count>ToolOutputEncoding.MAX_CHUNKS)throw invalid();int index=rows.getInt(0),start=rows.getInt(1),chars=rows.getInt(2);long bytes=rows.getLong(3),actualSize=rows.getLong(4),hashSize=rows.getLong(5);
                if(start<0||chars<1||chars>4096||start>covered||start+chars<=covered||bytes<1||bytes>16384||actualSize!=bytes||hashSize!=71||bytes+hashSize>budget.remaining())throw limited();
                try(Cursor body=db.rawQuery("SELECT body,chunk_fingerprint FROM task_output_chunks WHERE output_id=? AND chunk_index=? AND start_char=? AND char_count=? AND utf8_bytes=? AND length(CAST(body AS BLOB))=CAST(? AS INTEGER) AND length(CAST(chunk_fingerprint AS BLOB))=71 LIMIT 1",new String[]{header.output,Integer.toString(index),Integer.toString(start),Integer.toString(chars),Long.toString(bytes),Long.toString(bytes)},signal)){
                    if(!body.moveToFirst())throw invalid();String text=body.getString(0),fingerprint=body.getString(1);budget.charge(utf8(text),"body");budget.charge(utf8(fingerprint),"chunk_metadata");budget.chunks(1);if(text.length()!=chars||utf8(text)!=bytes||!ToolOutputEncoding.fingerprint(text).equals(fingerprint))throw invalid();int from=covered-start,to=Math.min(chars,end-start);if(from>0&&Character.isLowSurrogate(text.charAt(from))&&Character.isHighSurrogate(text.charAt(from-1)))throw invalid();result.append(text,from,to);covered=start+to;
                }
            }
        }
        if(covered!=end)throw invalid();if(result.length()>0&&Character.isHighSurrogate(result.charAt(result.length()-1)))result.setLength(result.length()-1);budget.check();return result.toString();
    }
    public static JSONObject citation(String task,String run,JSONObject supplied)throws Exception{
        String source=supplied.getString("sourceId"),revision=supplied.getString("revision");outputId(task,run,source);if(!revision.matches("sha256:[a-f0-9]{64}"))throw invalid();long offset=number(supplied,"offset",0,0,Integer.MAX_VALUE),max=number(supplied,"maxChars",4000,64,16000);
        return new JSONObject().put("sourceId",source).put("revision",revision).put("offset",offset).put("maxChars",max);
    }
    public static JSONObject page(String task,String run,JSONObject wanted,Header header,String body,int offset)throws Exception{
        JSONObject range=header.ref.getJSONObject("retainedRange");int total=range.getInt("chars"),end=offset+body.length();if(offset<0||end>total||!wanted.getString("revision").equals(header.ref.getString("revision")))throw invalid();
        return status(task,run,wanted,"current","").put("body",body).put("readRange",TaskMemoryTrace.rawRange(offset,end,total)).put("nextOffset",end<total?end:-1).put("sourceJump",TaskMemoryTrace.rawJump(task,run,wanted,offset,body.length()))
            .put("retainedNativeDataComplete",header.ref.getBoolean("retainedNativeDataComplete")).put("upstreamTruncated",header.ref.get("upstreamTruncated")).put("nativeOutputRef",header.reference()).put("coverage","exact_retained_safe_native_result_json_not_wire");
    }
    public static JSONObject status(String task,String run,JSONObject wanted,String status,String reason)throws Exception{
        return new JSONObject().put("taskId",task).put("runId",run).put("ownerId","owner").put("spaceId","private").put("kind","native_tool_output").put("sourceId",wanted.getString("sourceId")).put("revision",wanted.getString("revision")).put("revisionKind","sha256_content_fingerprint").put("nativeRevisionStatus","capture_content_hash_not_formal_source_revision")
            .put("status",status).put("reason",reason==null?"native_output_unavailable":reason).put("executionAuthorized",false).put("executionIndependentlyVerified",false).put("completeOriginalTrace",false).put("formalMemorySaved",false);
    }
    public static JSONObject checkedPage(JSONObject supplied,JSONObject wanted)throws Exception{
        if(!wanted.getString("sourceId").equals(supplied.optString("sourceId"))||!wanted.getString("revision").equals(supplied.optString("revision")))throw invalid();String status=supplied.getString("status");if(!Set.of("current","source_changed","unavailable","read_limited").contains(status))throw invalid();
        if(!"current".equals(status)){if(supplied.has("body"))throw invalid();return new JSONObject(supplied.toString());}
        if(!"native_tool_output".equals(supplied.optString("kind"))||!"exact_retained_safe_native_result_json_not_wire".equals(supplied.optString("coverage"))||!"capture_content_hash_not_formal_source_revision".equals(supplied.optString("nativeRevisionStatus")))throw invalid();
        JSONObject retained=ToolOutputCapture.checkedRefs(new JSONArray().put(supplied.getJSONObject("nativeOutputRef"))).getJSONObject(0);if(!retained.getString("sourceId").equals(wanted.getString("sourceId"))||!retained.getString("revision").equals(wanted.getString("revision")))throw invalid();String body=supplied.getString("body");MemoryBook.validateUnicode(body);JSONObject range=supplied.getJSONObject("readRange");int offset=wanted.getInt("offset"),chars=body.length(),total=range.getInt("totalChars");long end=(long)offset+chars;
        if(total!=retained.getJSONObject("retainedRange").getInt("chars")||!TaskMemoryTrace.rawFingerprint(TaskMemoryTrace.rawJump(supplied.getString("taskId"),supplied.getString("runId"),wanted,offset,chars)).equals(TaskMemoryTrace.rawFingerprint(supplied.getJSONObject("sourceJump"))))throw invalid();
        if(!"UTF-16".equals(range.optString("unit"))||offset!=range.getInt("offset")||chars!=range.getInt("chars")||chars>wanted.getInt("maxChars")||end>total||!Boolean.valueOf(offset==0&&end==total).equals(range.opt("complete"))||!Boolean.valueOf(end<total).equals(range.opt("hasMore"))||supplied.getInt("nextOffset")!=(end<total?(int)end:-1))throw invalid();return new JSONObject(supplied.toString());
    }
    private static long number(JSONObject value,String key,long fallback,long min,long max){if(!value.has(key))return fallback;Object v=value.opt(key);if(!(v instanceof Integer||v instanceof Long))throw invalid();long n=((Number)v).longValue();if(n<min||n>max)throw invalid();return n;}
    private static long utf8(String text){return text.getBytes(StandardCharsets.UTF_8).length;}
    private static boolean id(String id){return id!=null&&id.matches(UUID_PATTERN);}
    private static IllegalStateException invalid(){return new IllegalStateException("原native输出身份、字节或范围无法核对");}
    private static TaskStoreMemoryReader.RawMessageUnavailable limited(){return new TaskStoreMemoryReader.RawMessageUnavailable("native_output_read_limited");}
}
