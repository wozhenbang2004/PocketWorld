package dev.pocketengine.features.memory;

import dev.pocketengine.core.RunControl;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Native-only capture of producer-filtered returned data, before the model view is clipped.
 * A locator/hash never authorizes reading or execution; only the original host issues claims. */
public final class ToolOutputCapture {
    private ToolOutputCapture(){}
    public static final int MAX_OUTPUT_BYTES=1_048_576,MAX_METADATA_BYTES=8192,MAX_REFS=4;
    private static final String UUID="[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    private static final Set<String> REASONS=Set.of("native_output_capture_unavailable","native_output_claim_unavailable","native_output_not_configured","native_output_metadata_unavailable","native_output_source_unavailable","native_output_provenance_unavailable","native_output_run_budget","native_output_capture_limit","native_output_upstream_truncated","native_output_upstream_completeness_unknown");
    public enum Kind {NATIVE_RESULT_DATA_JSON,MCP_RESULT_JSON}
    public interface TextCleaner {String clean(String originalText)throws Exception;}
    /** Only these original native read producers may omit a prepared side-effect operation.
     * This enum is not parsed from a model command, and is not a read or execution grant. */
    public enum ReadTool {
        PROJECT_READ_FILE("project_read_file"),PROJECT_SEARCH("project_search"),PROJECT_LIST_FILES("project_list_files");
        public final String tool;ReadTool(String tool){this.tool=tool;}
    }
    /** Native call identity issued from the existing live project binding, never caller JSON.
     * Exact path/hash/range stay in the producer's actual returned data, not guessed request data. */
    public static final class ReadCall {
        public final String callId,taskId,runId,projectId,checkoutId;public final int epoch;public final ReadTool tool;
        private final RunControl original;
        private ReadCall(ReadTool tool,dev.pocketengine.features.capabilities.ModelCapabilityAdapters.Binding binding,String checkout,RunControl original){
            this.tool=java.util.Objects.requireNonNull(tool);java.util.Objects.requireNonNull(binding);this.original=java.util.Objects.requireNonNull(original);
            if(binding.projectId.isEmpty()||checkout==null||!checkout.matches(UUID)||!original.hasHostRun()||!original.matchesHostRun(binding.taskId,binding.runId,binding.epoch))throw new SecurityException("原native读取缺少真实项目/副本/执行绑定");
            taskId=binding.taskId;runId=binding.runId;epoch=binding.epoch;projectId=binding.projectId;checkoutId=checkout;callId=java.util.UUID.randomUUID().toString();
        }
        public static ReadCall project(ReadTool tool,dev.pocketengine.features.capabilities.ModelCapabilityAdapters.Binding binding,String checkout,RunControl original){return new ReadCall(tool,binding,checkout,original);}
        /** The original Factory checks this before its original SQL admission, never after a
         * completed read as a reason to erase already-observed audit evidence. */
        public void requireOriginal(String task,String run,int expectedEpoch,RunControl live)throws Exception{
            if(live!=original||!taskId.equals(task)||!runId.equals(run)||epoch!=expectedEpoch||!live.matchesHostRun(task,run,expectedEpoch))throw new SecurityException("不能更换原native只读调用身份");live.check();
        }
        public JSONObject metadata()throws Exception{return new JSONObject().put("kind","native_project_read_call").put("callId",callId).put("taskId",taskId).put("runId",runId).put("epoch",epoch).put("tool",tool.tool).put("projectId",projectId).put("checkoutId",checkoutId).put("preparedSideEffectOperation",false).put("executionAuthorized",false);}
    }
    public interface Factory {
        Claim begin(String tool,String preparedOperationId,Kind kind,RunControl original)throws Exception;
        default Claim beginRead(ReadCall call,RunControl original)throws Exception{return null;}
        default void beforeModelRequest(RunControl original)throws Exception{checkSources(original);}
        default void checkSources(RunControl original)throws Exception{}
    }
    public interface Claim {
        Result record(JSONObject safeUncroppedNativeData,Boolean upstreamTruncated)throws Exception;
        /** Original caller proved it never entered the native producer, not a timeout guess. */
        default void notDispatched()throws Exception{}
    }
    /** Source admission refusal only. It is neither owner cancellation nor an effect receipt. */
    public static final class OutputSourceUnavailable extends Exception {
        public OutputSourceUnavailable(Throwable cause){super("原工具输出的附件或记忆来源已撤回、变化或无法核对，后续未继续派发。原操作审计与已发生效果仍保留；请核对原任务和来源，必要时新建私人任务明确选择有效来源。",cause);}
    }
    public static final class Result {
        private final JSONObject value;
        private Result(JSONObject supplied)throws Exception{value=checkedMetadata(supplied);}
        public JSONObject metadata()throws Exception{return new JSONObject(value.toString());}
        public JSONArray outputRefs()throws Exception{return new JSONArray(value.getJSONArray("outputRefs").toString());}
    }
    public static Result unavailable(String reason)throws Exception{return recorded("unavailable",reason,new JSONArray());}
    public static Result recorded(String status,String reason,JSONArray outputRefs)throws Exception{
        return new Result(new JSONObject().put("version",1).put("status",status).put("reason",reason).put("outputRefs",outputRefs).put("bodyIncluded",false).put("executionAuthorized",false));
    }
    /** The only recovery/carrier validator. No arbitrary diagnostic/body can bypass secret cleaning. */
    public static JSONObject checkedMetadata(JSONObject supplied)throws Exception{
        keys(supplied,Set.of("version","status","reason","outputRefs","bodyIncluded","executionAuthorized"));
        if(integer(supplied,"version",1,1)!=1||!Boolean.FALSE.equals(supplied.opt("bodyIncluded"))||!Boolean.FALSE.equals(supplied.opt("executionAuthorized")))throw invalid();
        String status=string(supplied,"status",20),reason=string(supplied,"reason",90);JSONArray refs=checkedRefs(supplied.getJSONArray("outputRefs"));
        if(!Set.of("stored","partial","unavailable").contains(status)||!(reason.isEmpty()||REASONS.contains(reason)))throw invalid();
        if("unavailable".equals(status)?refs.length()!=0||reason.isEmpty():refs.length()==0)throw invalid();
        if("stored".equals(status)&&!reason.isEmpty()||"partial".equals(status)&&reason.isEmpty())throw invalid();
        if("stored".equals(status))for(int i=0;i<refs.length();i++)if(!refs.getJSONObject(i).getBoolean("retainedNativeDataComplete")||!Boolean.FALSE.equals(refs.getJSONObject(i).opt("upstreamTruncated")))throw invalid();
        JSONObject value=new JSONObject().put("version",1).put("status",status).put("reason",reason).put("outputRefs",refs).put("bodyIncluded",false).put("executionAuthorized",false);
        if(value.toString().getBytes(StandardCharsets.UTF_8).length>MAX_METADATA_BYTES)throw invalid();return value;
    }
    /** Body-free native identities, not model-selected JSON authority. Always defensive copies. */
    public static JSONArray checkedRefs(JSONArray supplied)throws Exception{
        if(supplied==null||supplied.length()>MAX_REFS)throw invalid();JSONArray refs=new JSONArray();Set<String> seen=new HashSet<>();
        for(int i=0;i<supplied.length();i++){
            JSONObject ref=supplied.getJSONObject(i);keys(ref,Set.of("taskId","runId","epoch","tool","operationId","sourceId","revision","captureKind","retainedRange","retainedNativeDataComplete","upstreamTruncated"));
            String task=string(ref,"taskId",36),run=string(ref,"runId",36),tool=string(ref,"tool",120),operation=string(ref,"operationId",36),source=string(ref,"sourceId",800),revision=string(ref,"revision",71),kind=string(ref,"captureKind",40);
            if(!task.matches(UUID)||!run.matches(UUID)||!tool.matches("[A-Za-z_][A-Za-z0-9_-]{0,119}")||!operation.isEmpty()&&!operation.matches(UUID)||!revision.matches("sha256:[a-f0-9]{64}"))throw invalid();
            String prefix="task:"+task+":run:"+run+":output:";if(!source.startsWith(prefix)||!source.substring(prefix.length()).matches(UUID)||!seen.add(source))throw invalid();
            try{Kind.valueOf(kind);}catch(IllegalArgumentException unknown){throw invalid();}long epoch=integer(ref,"epoch",1,Integer.MAX_VALUE);
            JSONObject range=ref.getJSONObject("retainedRange");keys(range,Set.of("offset","chars","unit","utf8Bytes","previewChars"));long offset=integer(range,"offset",0,0),chars=integer(range,"chars",1,MAX_OUTPUT_BYTES),bytes=integer(range,"utf8Bytes",1,MAX_OUTPUT_BYTES),preview=integer(range,"previewChars",1,Math.min(4000,chars));if(!"UTF-16".equals(range.opt("unit"))||bytes<chars)throw invalid();
            Object complete=ref.opt("retainedNativeDataComplete"),upstream=ref.opt("upstreamTruncated");if(!(complete instanceof Boolean)||!(upstream instanceof Boolean||upstream==JSONObject.NULL))throw invalid();
            refs.put(new JSONObject().put("taskId",task).put("runId",run).put("epoch",epoch).put("tool",tool).put("operationId",operation).put("sourceId",source).put("revision",revision).put("captureKind",kind)
                    .put("retainedRange",new JSONObject().put("offset",offset).put("chars",chars).put("unit","UTF-16").put("utf8Bytes",bytes).put("previewChars",preview)).put("retainedNativeDataComplete",complete).put("upstreamTruncated",upstream));
        }return refs;
    }
    private static void keys(JSONObject value,Set<String> expected){if(value==null||value.length()!=expected.size())throw invalid();for(var it=value.keys();it.hasNext();)if(!expected.contains(it.next()))throw invalid();}
    private static String string(JSONObject value,String key,int max){Object item=value.opt(key);if(!(item instanceof String)||((String)item).length()>max)throw invalid();return (String)item;}
    private static long integer(JSONObject value,String key,long min,long max){Object item=value.opt(key);if(!(item instanceof Integer||item instanceof Long))throw invalid();long n=((Number)item).longValue();if(n<min||n>max)throw invalid();return n;}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("原输出引用元数据不符合native范围");}
}
