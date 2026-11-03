package dev.pocketengine.features.memory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Read-only bounded projection. The caller authenticates scope and reads the original SQLite rows.
 * A content fingerprint is a citation/version fence, never a native revision counter or a memory grant.
 */
public final class TaskMemoryTrace {
    public static final int MAX_ROWS=20,MAX_CHARS=16000,MAX_PARENTS=8;
    public static final int MAX_OPERATION_SOURCE_BYTES=1_048_576,MAX_OPERATION_AUTHORITY_BYTES=1_048_576;
    private TaskMemoryTrace(){}

    /** Same canonical content fingerprint as the native trace, never a SourceRef revision. */
    public static String rawFingerprint(JSONObject original)throws Exception{return "sha256:"+digest(original);}
    public static String rawMessageId(String task,String run,int index){identifier(task);identifier(run);if(index<0)throw new IllegalArgumentException("消息位置无效");return messageId(task,run,index);}
    /** Validates model-sized citation fields, then adds the host's immutable task/run. */
    public static JSONObject rawCitation(String task,String run,JSONObject input)throws Exception{
        identifier(task);identifier(run);Set<String> allowed=Set.of("sourceId","revision","offset","maxChars");
        if(input==null)throw new IllegalArgumentException("缺少原证据引用");for(Iterator<String> keys=input.keys();keys.hasNext();)if(!allowed.contains(keys.next()))throw new IllegalArgumentException("原证据不能更换任务或空间");
        if(!(input.opt("sourceId") instanceof String)||!(input.opt("revision") instanceof String)||!input.getString("revision").matches("sha256:[0-9a-f]{64}"))throw new IllegalArgumentException("请保留精确原来源和内容指纹");
        String source=input.getString("sourceId"),messagePrefix="task:"+task+":run:"+run+":checkpoint-message:",operationPrefix="task:"+task+":operation:";
        if(source.startsWith(messagePrefix)){
            String index=source.substring(messagePrefix.length());if(!index.matches("0|[1-9][0-9]{0,9}"))throw new IllegalArgumentException("原消息位置无效");
            try{Integer.parseInt(index);}catch(NumberFormatException invalid){throw new IllegalArgumentException("原消息位置超过范围",invalid);}
        }else if(source.startsWith(operationPrefix))identifier(source.substring(operationPrefix.length()));else if(source.startsWith("task:"+task+":run:"+run+":output:"))ToolOutputStore.outputId(task,run,source);else throw new SecurityException("来源不属于此原任务轮次");
        return new JSONObject().put("taskId",task).put("runId",run).put("sourceId",source).put("revision",input.getString("revision"))
                .put("offset",integer(input,"offset",0,0,Integer.MAX_VALUE)).put("maxChars",integer(input,"maxChars",4000,64,MAX_CHARS));
    }
    public static JSONObject rawSourceStatus(String task,String run,JSONObject input,String status,String reason)throws Exception{
        JSONObject wanted=rawCitation(task,run,input);
        if(!Set.of("current","source_changed","unavailable","read_limited").contains(status)||reason.length()>96)throw new IllegalArgumentException("原证据状态无效");
        return new JSONObject().put("taskId",task).put("runId",run).put("ownerId","owner").put("spaceId","private")
                .put("sourceId",wanted.getString("sourceId")).put("revision",wanted.getString("revision")).put("revisionKind","sha256_content_fingerprint").put("nativeRevisionStatus","unversioned")
                .put("status",status).put("reason",reason).put("coverage","exact_retained_checkpoint_message_only")
                .put("executionAuthorized",false).put("executionIndependentlyVerified",false).put("completeOriginalTrace",false).put("formalMemorySaved",false);
    }
    /** One retained checkpoint row only. Missing/pruned rows are unavailable, not reconstructed. */
    public static JSONObject rawMessagePage(String task,String run,JSONObject message,JSONObject input)throws Exception{
        JSONObject wanted=rawCitation(task,run,input);
        String prefix="task:"+task+":run:"+run+":checkpoint-message:";if(!wanted.getString("sourceId").startsWith(prefix))throw new IllegalArgumentException("不是保留消息引用");
        if(message==null)return rawSourceStatus(task,run,input,"unavailable","retained_message_unavailable");
        if(!wanted.getString("revision").equals(rawFingerprint(message)))return rawSourceStatus(task,run,input,"source_changed","cited_checkpoint_message_changed");
        String body=message.toString(2);int offset=wanted.getInt("offset"),end=safeEnd(body,offset,wanted.getInt("maxChars"));
        return rawSourceStatus(task,run,input,"current","").put("body",body.substring(offset,end)).put("nextOffset",end<body.length()?end:-1)
                .put("readRange",rawRange(offset,end,body.length())).put("sourceJump",rawJump(task,run,wanted,offset,end-offset));
    }
    public static JSONObject rawRange(int offset,int end,int total)throws Exception{return new JSONObject().put("offset",offset).put("chars",end-offset).put("totalChars",total).put("unit","UTF-16").put("complete",offset==0&&end==total).put("hasMore",end<total);}
    static int safeEnd(String body,int offset,int max){
        unicode(body);if(offset<0||offset>body.length()||offset>0&&offset<body.length()&&Character.isHighSurrogate(body.charAt(offset-1))&&Character.isLowSurrogate(body.charAt(offset)))throw new IllegalArgumentException("原证据读取位置无效");
        int end=(int)Math.min((long)body.length(),(long)offset+max);if(end<body.length()&&end>offset&&Character.isHighSurrogate(body.charAt(end-1))&&Character.isLowSurrogate(body.charAt(end)))end--;return end;
    }
    public static JSONObject rawJump(String task,String run,JSONObject citation,int offset,int chars)throws Exception{
        return new JSONObject().put("kind","task_raw_source").put("taskId",task).put("runId",run).put("sourceId",citation.getString("sourceId"))
                .put("revision",citation.getString("revision")).put("revisionKind","sha256_content_fingerprint").put("offset",offset).put("chars",chars).put("unit","UTF-16");
    }
    /** Native viewer adapter only. The model raw port never accepts this broader UI request. */
    public static JSONObject rawMessageTraceRequest(String task,String run,JSONObject citation,String checkedSnapshot)throws Exception{
        JSONObject wanted=rawCitation(task,run,citation);String prefix="task:"+task+":run:"+run+":checkpoint-message:";
        if(!wanted.getString("sourceId").startsWith(prefix))throw new IllegalArgumentException("不是保留消息引用");
        JSONObject options=new JSONObject().put("section","messages").put("query","").put("sourceId",wanted.getString("sourceId")).put("limit",1).put("maxChars",wanted.getInt("maxChars"));
        if(checkedSnapshot!=null&&!checkedSnapshot.isEmpty()){
            if(!checkedSnapshot.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("原生执行记录指纹无效");
            Request request=new Request(new JSONObject().put("ownerId","owner").put("spaceId","private"),task,run,options);
            options.put("expectedSnapshot",checkedSnapshot).put("cursor",request.cursor(checkedSnapshot,Integer.parseInt(wanted.getString("sourceId").substring(prefix.length())),wanted.getInt("offset")));
        }else options.put("cursor","");
        return options.put("taskId",task).put("runId",run);
    }
    /** Bounded UI metadata extraction from real native tool events, never from assistant prose/JSON. */
    public static JSONArray rawCitationJumps(JSONObject task)throws Exception{
        JSONArray result=new JSONArray();if(task==null||!"owner".equals(task.optString("actorId"))||!"private".equals(task.optString("spaceId")))return result;
        JSONObject origin=task.optJSONObject("origin");if(origin!=null&&Set.of("group","channel").contains(origin.optString("kind")))return result;
        String id=task.optString("id"),run=task.optString("runId");if(id.isEmpty()||run.isEmpty())return result;
        JSONArray events=task.optJSONArray("events");java.util.LinkedHashMap<String,JSONObject> found=new java.util.LinkedHashMap<>();
        for(int i=events==null?0:Math.max(0,events.length()-200);events!=null&&i<events.length();i++){
            JSONObject event=events.optJSONObject(i);if(event==null||!"tool".equals(event.optString("type"))||!Set.of(TaskStoreMemoryReader.RAW_QUERY,TaskStoreMemoryReader.RAW_READ).contains(event.optString("tool")))continue;
            JSONObject receipt=event.optJSONObject("receipt");if(receipt==null||!Set.of("current","partial").contains(receipt.optString("status")))continue;
            try{checkRawIdentity(receipt,id,run);}catch(Exception unrelated){continue;}
            JSONArray rows=TaskStoreMemoryReader.RAW_QUERY.equals(event.optString("tool"))?receipt.optJSONArray("entries"):new JSONArray().put(receipt);
            for(int j=0;rows!=null&&j<Math.min(20,rows.length());j++)try{
                JSONObject row=rows.getJSONObject(j),range=row.getJSONObject("readRange"),jump=row.getJSONObject("sourceJump");
                int offset=integer(range,"offset",-1,0,Integer.MAX_VALUE),chars=integer(range,"chars",-1,0,MAX_CHARS);
                JSONObject citation=new JSONObject().put("sourceId",row.getString("sourceId")).put("revision",row.getString("revision"));rawCitation(id,run,citation);
                JSONObject expected=rawJump(id,run,citation,offset,chars);
                if(chars==0||!digest(expected).equals(digest(jump)))continue;
                String key=new JSONArray().put(jump.getString("sourceId")).put(jump.getString("revision")).put(offset).put(chars).toString();found.remove(key);found.put(key,new JSONObject(jump.toString()));
                while(found.size()>100)found.remove(found.keySet().iterator().next());
            }catch(Exception invalid){/* A truncated/invalid event is not reclassified as a source proof. */}
        }
        for(JSONObject jump:found.values())result.put(jump);return result;
    }
    /** Retained output ranges from actual native tool receipts only, not answer-authored JSON.
     * These describe archived safe returned data, not that the model read the whole retained range. */
    public static JSONArray outputCitationJumps(JSONObject task)throws Exception{
        JSONArray result=new JSONArray();if(task==null||!"owner".equals(task.optString("actorId"))||!"private".equals(task.optString("spaceId")))return result;
        JSONObject origin=task.optJSONObject("origin");if(origin!=null&&Set.of("channel","group").contains(origin.optString("kind")))return result;
        String id=task.optString("id"),run=task.optString("runId");if(!TaskStoreMemoryQuery.validTaskId(id)||!TaskStoreMemoryQuery.validTaskId(run))return result;JSONArray events=task.optJSONArray("events");java.util.LinkedHashMap<String,JSONObject> unique=new java.util.LinkedHashMap<>();
        for(int i=events==null?0:Math.max(0,events.length()-200);events!=null&&i<events.length();i++)try{
            JSONObject event=events.getJSONObject(i);if(!"tool".equals(event.optString("type")))continue;JSONObject receipt=event.optJSONObject("receipt");if(receipt==null||!receipt.has("outputCapture"))continue;
            JSONArray refs=ToolOutputCapture.checkedMetadata(receipt.getJSONObject("outputCapture")).getJSONArray("outputRefs");for(int r=0;r<refs.length();r++){
                JSONObject ref=refs.getJSONObject(r);if(!id.equals(ref.getString("taskId"))||!run.equals(ref.getString("runId"))||!event.optString("tool").equals(ref.getString("tool")))continue;
                int chars=ref.getJSONObject("retainedRange").getInt("previewChars");if(chars==0)continue;JSONObject jump=rawJump(id,run,ref,0,chars);unique.put(rawFingerprint(jump),jump);while(unique.size()>100)unique.remove(unique.keySet().iterator().next());
            }
        }catch(Exception unavailable){/* Invalid/truncated/fabricated diagnostics are not a citation. */}
        for(JSONObject jump:unique.values())result.put(jump);return result;
    }
    /** Exact retained history citations from real native tool events. Caller/source remain separate. */
    public static JSONArray historyCitationJumps(JSONObject task)throws Exception{
        JSONArray result=new JSONArray();if(task==null||!"owner".equals(task.optString("actorId"))||!"private".equals(task.optString("spaceId"))||!"assistant".equals(task.optString("kind")))return result;
        JSONObject origin=task.optJSONObject("origin");if(origin!=null&&Set.of("group","channel").contains(origin.optString("kind")))return result;
        String id=task.optString("id"),run=task.optString("runId"),conversation=task.optString("conversationId");if(!TaskStoreMemoryQuery.validTaskId(id)||!TaskStoreMemoryQuery.validTaskId(run)||!TaskStoreMemoryQuery.validTaskId(conversation))return result;
        JSONArray events=task.optJSONArray("events");java.util.LinkedHashMap<String,JSONObject> found=new java.util.LinkedHashMap<>();
        for(int i=events==null?0:Math.max(0,events.length()-200);events!=null&&i<events.length();i++){
            JSONObject event=events.optJSONObject(i);if(event==null||!"tool".equals(event.optString("type"))||!Set.of(ConversationHistory.SEARCH,ConversationHistory.READ).contains(event.optString("tool")))continue;
            JSONObject receipt=event.optJSONObject("receipt");if(receipt==null||!Set.of("current","partial").contains(receipt.optString("status"))||!id.equals(receipt.optString("callerTaskId"))||!run.equals(receipt.optString("callerRunId"))||!conversation.equals(receipt.optString("conversationId"))||!"owner".equals(receipt.optString("ownerId"))||!"private".equals(receipt.optString("spaceId")))continue;
            boolean nativeProof=true;for(String key:List.of("executionAuthorized","executionIndependentlyVerified","formalMemorySaved","completeOriginalTrace"))if(!Boolean.FALSE.equals(receipt.opt(key)))nativeProof=false;if(!nativeProof)continue;
            JSONArray rows=ConversationHistory.SEARCH.equals(event.optString("tool"))?receipt.optJSONArray("entries"):new JSONArray().put(receipt);
            for(int j=0;rows!=null&&j<Math.min(20,rows.length());j++)try{
                JSONObject row=rows.getJSONObject(j),jump=row.getJSONObject("sourceJump"),range=row.getJSONObject("readRange");if(!"UTF-16".equals(range.optString("unit"))||range.getInt("offset")!=jump.getInt("offset")||range.getInt("chars")!=jump.getInt("chars")||row.getString("body").length()!=range.getInt("chars"))continue;
                JSONObject citation=new JSONObject().put("callerTaskId",id).put("callerRunId",run).put("conversationId",conversation).put("sourceJump",new JSONObject(jump.toString()));
                if("task_raw_source".equals(jump.optString("kind"))){
                    JSONObject ref=null;if(ConversationHistory.SEARCH.equals(event.optString("tool")))ref=ConversationHistory.SourceLocator.from(row).taskReference();
                    else{JSONArray refs=receipt.optJSONArray("taskSourceRefs");for(int r=0;refs!=null&&r<Math.min(100,refs.length());r++){JSONObject candidate=refs.getJSONObject(r);if(row.optString("sourceId").equals(candidate.optString("sourceId"))&&row.optString("revision").equals(candidate.optString("revision")))ref=candidate;}}
                    if(ref==null)continue;citation.put("taskSource",ref);
                }else if(!"assistant_message".equals(row.optString("sourceKind"))||!rawFingerprint(row.getJSONObject("sourceRef")).equals(rawFingerprint(jump.getJSONObject("sourceRef")))||!row.optString("bodyFingerprint").equals(jump.optString("bodyFingerprint")))continue;
                citation=ConversationHistory.checkedCitation(citation);String key=rawFingerprint(citation);found.remove(key);found.put(key,citation);while(found.size()>100)found.remove(found.keySet().iterator().next());
            }catch(Exception invalid){/* Truncated/foreign/native-proof-missing events do not become citations. */}
        }
        for(JSONObject citation:found.values())result.put(citation);return result;
    }
    /** Checks the narrow producer reply; no aggregate UI snapshot is accepted as raw coverage. */
    public static JSONObject checkedRawPage(JSONObject supplied,String task,String run,JSONObject options)throws Exception{
        TaskStoreMemoryQuery.RawRequest request=new TaskStoreMemoryQuery.RawRequest(options);JSONObject page=new JSONObject(supplied.toString());
        checkRawIdentity(page,task,run);String status=page.optString("status");
        if(!Set.of("current","partial","source_changed","unavailable","read_limited").contains(status)||!request.section.equals(page.optString("section"))||!request.query.equals(page.optString("query"))||page.has("snapshot"))throw new SecurityException("原证据查询范围无法核对");
        JSONObject coverage=page.getJSONObject("pageCoverage");
        int scanned=integer(coverage,"scannedRows",-1,0,100);long bytes=SourceRef.integer(coverage,"scannedUtf8Bytes",0);
        if(bytes>1_048_576||!(coverage.opt("totalKnown") instanceof Boolean)||!(coverage.opt("partialReason") instanceof String)||!"materialized_sqlite_text_not_physical_io".equals(coverage.optString("byteAccounting")))throw new SecurityException("原证据扫描预算无法核对");
        JSONArray rows=page.getJSONArray("entries");if(rows.length()>request.limit||rows.length()>scanned)throw new SecurityException("原证据结果超过扫描范围");
        int chars=0;for(int i=0;i<rows.length();i++){
            JSONObject row=rows.getJSONObject(i),citation=new JSONObject().put("sourceId",row.getString("sourceId")).put("revision",row.getString("revision"));rawCitation(task,run,citation);
            if(!"sha256_content_fingerprint".equals(row.optString("revisionKind")))throw new SecurityException("原证据版本类型无效");
            String body=row.getString("body");unicode(body);JSONObject range=row.getJSONObject("readRange");int length=integer(range,"chars",-1,0,MAX_CHARS),total=integer(range,"totalChars",-1,0,Integer.MAX_VALUE);
            if(integer(range,"offset",-1,0,Integer.MAX_VALUE)!=0||length!=body.length()||length>total||!Boolean.valueOf(length==total).equals(range.opt("complete"))||!Boolean.valueOf(length<total).equals(range.opt("hasMore")))throw new SecurityException("原证据预览范围无效");chars+=length;
        }
        if(chars>request.maxChars||chars!=integer(page,"charsReturned",-1,0,MAX_CHARS)||page.optString("nextCursor").length()>4000)throw new SecurityException("原证据响应超过本次范围");
        if(!Set.of("current","partial").contains(status)&&rows.length()!=0)throw new SecurityException("失效原证据不能带旧正文");return page;
    }
    public static JSONObject checkedRawSource(JSONObject supplied,String task,String run,JSONObject input)throws Exception{
        JSONObject wanted=rawCitation(task,run,input);checkRawIdentity(supplied,task,run);
        if(wanted.getString("sourceId").startsWith("task:"+task+":operation:"))return checkedOperationPage(supplied,wanted);
        if(wanted.getString("sourceId").startsWith("task:"+task+":run:"+run+":output:"))return ToolOutputStore.checkedPage(supplied,wanted);
        if(!wanted.getString("sourceId").equals(supplied.optString("sourceId"))||!wanted.getString("revision").equals(supplied.optString("revision")))throw new SecurityException("原消息回复不是所选版本");
        String status=supplied.getString("status");if(!"current".equals(status))return rawSourceStatus(task,run,input,status,supplied.optString("reason"));
        if(!"exact_retained_checkpoint_message_only".equals(supplied.optString("coverage"))||!"sha256_content_fingerprint".equals(supplied.optString("revisionKind"))||!"unversioned".equals(supplied.optString("nativeRevisionStatus")))throw new SecurityException("原消息覆盖无效");
        String body=supplied.getString("body");unicode(body);JSONObject range=supplied.getJSONObject("readRange");int offset=integer(range,"offset",-1,0,Integer.MAX_VALUE),chars=integer(range,"chars",-1,0,MAX_CHARS),total=integer(range,"totalChars",-1,0,Integer.MAX_VALUE);long end=(long)offset+chars;
        if(offset!=wanted.getInt("offset")||chars!=body.length()||chars>wanted.getInt("maxChars")||end>total||integer(supplied,"nextOffset",-2,-1,Integer.MAX_VALUE)!=(end<total?(int)end:-1)||!Boolean.valueOf(end<total).equals(range.opt("hasMore"))||!Boolean.valueOf(offset==0&&end==total).equals(range.opt("complete")))throw new SecurityException("原消息正文范围无效");return new JSONObject(supplied.toString());
    }
    private static void checkRawIdentity(JSONObject page,String task,String run)throws Exception{
        if(!task.equals(page.optString("taskId"))||!run.equals(page.optString("runId"))||!"owner".equals(page.optString("ownerId"))||!"private".equals(page.optString("spaceId"))||!Boolean.FALSE.equals(page.opt("executionAuthorized"))||!Boolean.FALSE.equals(page.opt("executionIndependentlyVerified"))||!Boolean.FALSE.equals(page.opt("completeOriginalTrace"))||!Boolean.FALSE.equals(page.opt("formalMemorySaved")))throw new SecurityException("原证据回复身份或证明边界无效");
    }

    /** Existing confirmation source namespace only. Parsing a location never grants read access. */
    public static String sourceTaskId(JSONObject source){
        if(source==null||!"task_evidence".equals(source.optString("kind"))||!positiveInteger(source.opt("revision")))return "";
        String id=source.optString("sourceId");if(!id.startsWith("task:")||!id.endsWith(":verified_result"))return "";
        id=id.substring(5,id.length()-16);if(id.isBlank()||id.length()>240)return "";
        for(int i=0;i<id.length();i++)if(Character.isISOControl(id.charAt(i)))return "";return id;
    }

    /** UI consistency only; opaque native request identity and store authentication remain outside. */
    public static JSONObject checkedPage(JSONObject supplied,JSONObject request)throws Exception{
        JSONObject page=new JSONObject(supplied.toString());String status=page.optString("status");
        if(!request.getString("taskId").equals(page.optString("taskId"))||!"owner".equals(page.optString("ownerId"))||!"private".equals(page.optString("spaceId"))
                ||!request.optString("runId").isEmpty()&&!request.getString("runId").equals(page.optString("runId")))throw new SecurityException("执行记录回复不属于所选任务或轮次");
        if(!Set.of("current","snapshot_changed","unavailable").contains(status))throw new IllegalArgumentException("执行记录状态无法核对");
        if(!"current".equals(status))return page.put("entries",new JSONArray());
        if(!request.optString("section","messages").equals(page.optString("section"))||!request.optString("query").equals(page.optString("query"))||!request.optString("sourceId").equals(page.optString("focusedSourceId"))
                ||!page.optString("snapshot").matches("[0-9a-f]{64}")||!Boolean.FALSE.equals(page.opt("executionAuthorized"))
                ||!Boolean.FALSE.equals(page.opt("executionIndependentlyVerified"))||!Boolean.FALSE.equals(page.opt("completeOriginalTrace")))throw new SecurityException("执行记录范围或证明边界无法核对");
        JSONArray rows=page.getJSONArray("entries");int chars=0;
        if(rows.length()>integer(request,"limit",10,1,MAX_ROWS))throw new IllegalArgumentException("返回条数超过读取范围");
        for(int i=0;i<rows.length();i++){
            JSONObject row=rows.getJSONObject(i),range=row.getJSONObject("readRange");String body=row.getString("body");unicode(body);
            int offset=integer(range,"offset",0,0,Integer.MAX_VALUE),length=integer(range,"chars",0,0,MAX_CHARS),total=integer(range,"totalChars",0,0,Integer.MAX_VALUE);
            if(length!=body.length()||(long)offset+length>total||row.optString("sourceId").isEmpty()||!row.optString("revision").matches("sha256:[0-9a-f]{64}"))throw new IllegalArgumentException("执行记录引文或已读范围无效");
            chars+=length;
        }
        if(chars>integer(request,"maxChars",4000,64,MAX_CHARS)||chars!=page.getInt("charsReturned"))throw new IllegalArgumentException("正文超过本次读取范围");
        return page;
    }

    /** Parses an existing operation citation; a location/fingerprint is never a read grant. */
    public static String operationId(JSONObject request){return new OperationRequest(request).operation;}

    /** Same original-row projection used by the trace. Raw stored strings also fence its revision. */
    public static JSONObject storedOperation(String task,String run,String id,String tool,String arguments,String state,String receipt,long createdAt)throws Exception{
        JSONObject raw=new JSONObject().put("arguments",arguments).put("receipt",receipt==null?JSONObject.NULL:receipt);
        return new JSONObject().put("taskId",task).put("runId",run).put("operationId",id).put("tool",tool).put("arguments",storedBody(arguments))
                .put("state",state).put("receipt",receipt==null?JSONObject.NULL:storedBody(receipt)).put("createdAt",createdAt).put("storedBodyFingerprint",digest(raw));
    }
    private static Object storedBody(String text){
        try{org.json.JSONTokener parser=new org.json.JSONTokener(text);Object value=parser.nextValue();if(parser.nextClean()==0)return value;}catch(Exception unparsed){}
        return text;
    }

    /** Only this one original operation is re-read; unrelated trace domains are not its revision. */
    public static JSONObject operationPage(JSONObject scope,JSONObject task,JSONObject operation,JSONObject request)throws Exception{
        OperationRequest wanted=new OperationRequest(request);checkOperationScope(scope);
        JSONObject origin=task==null?null:task.optJSONObject("origin");
        if(task==null||!wanted.task.equals(task.optString("id"))||!scope.getString("ownerId").equals(task.optString("actorId"))
                ||!scope.getString("spaceId").equals(task.optString("spaceId"))||origin!=null&&Set.of("group","channel").contains(origin.optString("kind")))
            throw new SecurityException("原操作不属于当前私人任务空间");
        if(operation==null)return operationStatus(scope,request,"unavailable","original_operation_unavailable");
        if(!wanted.task.equals(operation.optString("taskId"))||!wanted.run.equals(operation.optString("runId"))||!wanted.operation.equals(operation.optString("operationId")))
            throw new SecurityException("原操作行不属于这条任务、轮次或来源");
        if(!wanted.revision.equals("sha256:"+digest(operation)))return operationStatus(scope,request,"source_changed","cited_operation_version_changed");
        String body=dev.pocketengine.store.NativeMcpPrivateFields.publicOperation(operation).toString(2);int offset=wanted.offset;
        if(offset>body.length()||offset>0&&offset<body.length()&&Character.isHighSurrogate(body.charAt(offset-1))&&Character.isLowSurrogate(body.charAt(offset)))
            throw new IllegalArgumentException("原操作读取位置无效，请从此版本开头读取");
        int end=(int)Math.min((long)body.length(),(long)offset+wanted.maxChars);
        if(end<body.length()&&end>offset&&Character.isHighSurrogate(body.charAt(end-1))&&Character.isLowSurrogate(body.charAt(end)))end--;
        String text=body.substring(offset,end);unicode(text);
        return operationStatus(scope,request,"current","").put("body",text).put("nextOffset",end<body.length()?end:-1)
                .put("readRange",new JSONObject().put("offset",offset).put("chars",text.length()).put("totalChars",body.length())
                        .put("complete",offset==0&&end==body.length()).put("hasMore",end<body.length()));
    }

    /** No successful empty evidence is invented when the original native row cannot be read. */
    public static JSONObject operationStatus(JSONObject scope,JSONObject request,String status,String reason)throws Exception{
        OperationRequest wanted=new OperationRequest(request);checkOperationScope(scope);
        if(!Set.of("current","source_changed","unavailable","read_limited").contains(status)||reason.length()>96)throw new IllegalArgumentException("原操作读取状态无效");
        return new JSONObject().put("taskId",wanted.task).put("runId",wanted.run).put("sourceId",wanted.source).put("revision",wanted.revision)
                .put("ownerId",scope.getString("ownerId")).put("spaceId",scope.getString("spaceId")).put("status",status).put("reason",reason)
                .put("coverage","exact_original_operation_only").put("revisionKind","sha256_content_fingerprint").put("nativeRevisionStatus","unversioned")
                .put("executionAuthorized",false).put("executionIndependentlyVerified",false).put("completeOriginalTrace",false).put("formalMemorySaved",false);
    }

    public static JSONObject checkedOperationPage(JSONObject supplied,JSONObject request)throws Exception{
        OperationRequest wanted=new OperationRequest(request);JSONObject scope=new JSONObject().put("ownerId","owner").put("spaceId","private");
        if(!wanted.task.equals(supplied.optString("taskId"))||!wanted.run.equals(supplied.optString("runId"))||!wanted.source.equals(supplied.optString("sourceId"))
                ||!wanted.revision.equals(supplied.optString("revision"))||!"owner".equals(supplied.optString("ownerId"))||!"private".equals(supplied.optString("spaceId")))
            throw new SecurityException("原操作回复不属于所选来源版本");
        String status=supplied.getString("status");
        if(!"current".equals(status))return operationStatus(scope,request,status,supplied.optString("reason")); // Drop all old bodies and ranges.
        if(!"exact_original_operation_only".equals(supplied.optString("coverage"))||!"sha256_content_fingerprint".equals(supplied.optString("revisionKind"))||!"unversioned".equals(supplied.optString("nativeRevisionStatus"))
                ||!Boolean.FALSE.equals(supplied.opt("executionAuthorized"))||!Boolean.FALSE.equals(supplied.opt("executionIndependentlyVerified"))
                ||!Boolean.FALSE.equals(supplied.opt("completeOriginalTrace"))||!Boolean.FALSE.equals(supplied.opt("formalMemorySaved")))
            throw new SecurityException("原操作回复的范围或证明边界无效");
        String body=supplied.getString("body");unicode(body);JSONObject range=supplied.getJSONObject("readRange");
        int offset=integer(range,"offset",-1,0,Integer.MAX_VALUE),chars=integer(range,"chars",-1,0,MAX_CHARS),total=integer(range,"totalChars",-1,0,Integer.MAX_VALUE);
        long end=(long)offset+chars;int next=integer(supplied,"nextOffset",-2,-1,Integer.MAX_VALUE);
        if(offset!=wanted.offset||chars!=body.length()||chars>wanted.maxChars||end>total||next!=(end<total?(int)end:-1)
                ||!Boolean.valueOf(end<total).equals(range.opt("hasMore"))||!Boolean.valueOf(offset==0&&end==total).equals(range.opt("complete")))
            throw new IllegalArgumentException("原操作正文超过所选读取范围");
        return new JSONObject(supplied.toString());
    }
    private static void checkOperationScope(JSONObject scope){
        if(scope==null||!"owner".equals(scope.optString("ownerId"))||!"private".equals(scope.optString("spaceId")))throw new SecurityException("此入口只读原私人操作来源");
    }
    private static final class OperationRequest {
        final String task,run,source,revision,operation;final int offset,maxChars;
        OperationRequest(JSONObject value){
            Set<String> allowed=Set.of("taskId","runId","sourceId","revision","offset","maxChars");
            if(value==null)throw new IllegalArgumentException("缺少原操作引文");for(Iterator<String> keys=value.keys();keys.hasNext();)if(!allowed.contains(keys.next()))throw new IllegalArgumentException("原操作读取字段无效");
            for(String field:Set.of("taskId","runId","sourceId","revision"))if(!(value.opt(field) instanceof String))throw new IllegalArgumentException("原操作引文必须保留原标识与内容版本");
            task=value.optString("taskId");run=value.optString("runId");source=value.optString("sourceId");revision=value.optString("revision");identifier(task);identifier(run);
            String prefix="task:"+task+":operation:";
            if(!source.startsWith(prefix)||!revision.matches("sha256:[0-9a-f]{64}"))throw new IllegalArgumentException("原操作引文位置或内容版本无效");
            operation=source.substring(prefix.length());identifier(operation);offset=integer(value,"offset",0,0,Integer.MAX_VALUE);maxChars=integer(value,"maxChars",4000,64,MAX_CHARS);
        }
    }

    public static JSONObject page(JSONObject scope,JSONObject task,JSONObject run,Iterator<JSONObject> operations,
                                  JSONObject lineage,JSONObject options)throws Exception{
        if(scope==null||task==null||run==null||operations==null||lineage==null||options==null)throw new IllegalArgumentException("缺少原执行记录");
        String owner=scope.getString("ownerId"),space=scope.getString("spaceId"),taskId=task.getString("id"),runId=run.getString("runId");
        identifier(taskId);if(!runId.isEmpty())identifier(runId);
        JSONObject origin=task.optJSONObject("origin");
        if(!"owner".equals(owner)||!"private".equals(space)||!owner.equals(task.optString("actorId"))||!space.equals(task.optString("spaceId"))||!taskId.equals(run.optString("taskId"))
                ||origin!=null&&Set.of("group","channel").contains(origin.optString("kind")))throw new SecurityException("执行记录不属于当前原私人任务空间");
        Request request=new Request(scope,taskId,runId,options);
        JSONObject checkpoint=run.optJSONObject("checkpoint");JSONArray messages=checkpoint==null?null:checkpoint.optJSONArray("messages");
        if(messages==null)messages=new JSONArray();JSONArray events=task.optJSONArray("events");if(events==null)events=new JSONArray();
        JSONArray links=lineage.getJSONArray("links");if(links.length()>MAX_PARENTS)throw new IllegalArgumentException("父链超过有界范围");
        Map<Integer,JSONObject> messageParents=toolParents(taskId,runId,messages);
        MessageDigest snapshot=MessageDigest.getInstance("SHA-256");
        charge(snapshot,"task",digest(task));charge(snapshot,"run",digest(run));charge(snapshot,"lineage",digest(lineage));
        Selection selected=new Selection(request);
        for(int i=0;i<messages.length();i++){
            JSONObject message=messages.getJSONObject(i);String revision=digest(message);
            if(request.section.equals("messages")){
                JSONObject row=entry("checkpoint_message",messageId(taskId,runId,i),revision,i).put("role",message.optString("role"));
                if("tool".equals(message.optString("role"))){JSONObject parent=messageParents.get(i);row.put("causalState",parent==null?"parent_unavailable":"matched_tool_call");if(parent!=null)row.put("parent",parent);}
                else row.put("causalState","not_recorded");
                Object nativeRevision=message.opt("ownerMessageRevision");
                if("user".equals(message.optString("role"))&&message.optString("ownerMessageId").matches("[a-fA-F0-9-]{36}")&&positiveInteger(nativeRevision))
                    row.put("originalMessageSource",new JSONObject().put("kind","message").put("sourceId",message.getString("ownerMessageId"))
                            .put("revision",nativeRevision).put("ownerId",owner).put("spaceId",space));
                selected.read(i,message,row);
            }
        }
        int operationCount=0;
        while(operations.hasNext()){
            JSONObject operation=operations.next();
            if(operation==null||!taskId.equals(operation.optString("taskId"))||!runId.equals(operation.optString("runId"))||operation.optString("operationId").isEmpty())throw new SecurityException("原操作行属于不同任务或执行轮次");
            identifier(operation.getString("operationId"));
            String revision=digest(operation);charge(snapshot,"operation",revision);
            if(request.section.equals("operations"))selected.read(operationCount,dev.pocketengine.store.NativeMcpPrivateFields.publicOperation(operation),entry("operation_receipt","task:"+taskId+":operation:"+operation.getString("operationId"),revision,operationCount)
                    .put("causalState","not_recorded").put("operationId",operation.getString("operationId")).put("state",operation.optString("state")));
            operationCount++;
        }
        for(int i=0;i<events.length();i++)if(request.section.equals("events")){
            JSONObject event=events.getJSONObject(i);Object seq=event.opt("seq");
            selected.read(i,event,entry("host_event","task:"+taskId+":event:"+(positiveInteger(seq)?seq:"position-"+i),digest(event),i)
                    .put("causalState","not_recorded").put("runBinding",event.optString("sourceRunId").isEmpty()?"not_recorded":event.getString("sourceRunId")));
        }
        for(int i=0;i<links.length();i++){
            JSONObject link=links.getJSONObject(i);
            if(!owner.equals(link.optString("ownerId"))||!space.equals(link.optString("spaceId"))||link.optString("childTaskId").isEmpty()||link.optString("parentTaskId").isEmpty())throw new SecurityException("父链包含不同空间或无来源的链接");
            identifier(link.getString("childTaskId"));identifier(link.getString("parentTaskId"));if(!link.optString("parentRunId").isEmpty())identifier(link.getString("parentRunId"));
            if(request.section.equals("parents"))selected.read(i,link,entry("task_parent_link","task:"+link.getString("childTaskId")+":parent",digest(link),i)
                    .put("causalState","stored_parent_link").put("parentTaskId",link.getString("parentTaskId")).put("parentRunId",link.optString("parentRunId")).put("parentReadable",link.optBoolean("parentReadable")));
        }
        String fingerprint=hex(snapshot.digest());
        JSONObject result=new JSONObject().put("taskId",taskId).put("runId",runId).put("ownerId",owner).put("spaceId",space)
                .put("taskGoalPreview",preview(task.optString("goal"),200)).put("taskGoalHasMore",task.optString("goal").length()>200)
                .put("snapshot",fingerprint).put("section",request.section).put("query",request.query).put("focusedSourceId",request.sourceId).put("executionAuthorized",false)
                .put("executionIndependentlyVerified",false).put("completeOriginalTrace",false).put("formalMemorySaved",false);
        if(!request.expectedSnapshot.isEmpty()&&!fingerprint.equals(request.expectedSnapshot))return result.put("status","snapshot_changed")
                .put("entries",new JSONArray()).put("nextCursor","").put("charsReturned",0);
        if(selected.invalidPosition)throw new IllegalArgumentException("读取位置已无效，请回到开头");
        // This TaskStore producer has no native checkpoint revision counter. A JSON field cannot self-attest one.
        JSONObject runCard=new JSONObject().put("runId",runId).put("coordinatorEpoch",run.optInt("coordinatorEpoch"))
                .put("status",run.optString("status")).put("previousRunId",run.optString("previousRunId")).put("nextRunId",run.optString("nextRunId"))
                .put("nativeRevision",JSONObject.NULL).put("nativeRevisionStatus","unversioned");
        return result.put("status","current").put("run",runCard).put("entries",selected.rows).put("charsReturned",selected.chars)
                .put("matchedCount",selected.matches).put("entriesNotOnPage",Math.max(0,selected.matches-selected.rows.length()))
                .put("nextCursor",selected.nextIndex<0?"":request.cursor(fingerprint,selected.nextIndex,selected.nextOffset))
                .put("counts",new JSONObject().put("messages",messages.length()).put("operations",operationCount).put("events",events.length()).put("parents",links.length()))
                .put("coverage",new JSONObject().put("messages","retained_checkpoint_only").put("operations","stored_host_ledger_for_selected_run")
                        .put("events","stored_task_events_some_legacy_run_bindings_unavailable").put("parents",lineage.optString("status","unavailable"))
                        .put("maxParents",MAX_PARENTS).put("maxRows",request.limit).put("maxChars",request.maxChars).put("revisionKind","sha256_content_fingerprint")
                        .put("snapshotScanning","selected_run_checkpoint_and_ledger_task_events_and_bounded_parent_links")
                        .put("returnedBodies","bounded_ranges_only").put("originalMessageTotalKnown",false).put("storedBodiesMayAlreadyBeTruncated",true));
    }

    private static JSONObject entry(String kind,String id,String revision,int index)throws Exception{
        return new JSONObject().put("kind",kind).put("sourceId",id).put("revision","sha256:"+revision).put("revisionKind","sha256_content_fingerprint").put("index",index);
    }
    private static String messageId(String task,String run,int index){return "task:"+task+":run:"+run+":checkpoint-message:"+index;}
    private static Map<Integer,JSONObject> toolParents(String task,String run,JSONArray messages)throws Exception{
        Map<Integer,JSONObject> result=new HashMap<>();Map<String,Integer> pending=new HashMap<>();
        for(int i=0;i<messages.length();i++){
            JSONObject row=messages.getJSONObject(i);String role=row.optString("role");
            if("assistant".equals(role)){
                pending.clear();JSONArray calls=row.optJSONArray("tool_calls");
                for(int j=0;calls!=null&&j<calls.length();j++){String id=calls.getJSONObject(j).optString("id");if(!id.isEmpty())pending.put(id,pending.containsKey(id)?-1:i);}
            }else if("tool".equals(role)){
                String id=row.optString("tool_call_id");Integer parent=pending.remove(id);
                if(parent!=null&&parent>=0)result.put(i,new JSONObject().put("sourceId",messageId(task,run,parent))
                        .put("revision","sha256:"+digest(messages.getJSONObject(parent))).put("toolCallId",preview(id,200)).put("toolCallIdTruncated",id.length()>200).put("relation","tool_call_id"));
            }else pending.clear();
        }return result;
    }
    private static final class Selection {
        final Request request;final JSONArray rows=new JSONArray();int chars,matches,nextIndex=-1,nextOffset;boolean stopped,invalidPosition;
        Selection(Request request){this.request=request;}
        void read(int index,JSONObject original,JSONObject row)throws Exception{
            String body=original.toString(2);if(!request.sourceId.isEmpty()&&!request.sourceId.equals(row.getString("sourceId"))||!matches(body,request.query))return;matches++;
            if(index<request.index||stopped)return;
            int offset=index==request.index?request.offset:0;
            if(offset>body.length()||offset>0&&offset<body.length()&&Character.isHighSurrogate(body.charAt(offset-1))&&Character.isLowSurrogate(body.charAt(offset))){invalidPosition=true;return;}
            int available=request.maxChars-chars;
            if(rows.length()>=request.limit||available==0){next(index,offset);return;}
            int end=Math.min(body.length(),offset+available);
            if(end<body.length()&&end>offset&&Character.isHighSurrogate(body.charAt(end-1))&&Character.isLowSurrogate(body.charAt(end)))end--;
            if(end==offset&&end<body.length()){next(index,offset);return;}
            row.put("body",body.substring(offset,end)).put("readRange",new JSONObject().put("offset",offset).put("chars",end-offset)
                    .put("totalChars",body.length()).put("complete",offset==0&&end==body.length()).put("hasMore",end<body.length()));
            rows.put(row);chars+=end-offset;if(end<body.length())next(index,end);
        }
        void next(int index,int offset){if(!stopped){nextIndex=index;nextOffset=offset;stopped=true;}}
    }
    private static final class Request {
        final JSONObject scope;final String task,run,section,query,sourceId,queryHash,expectedSnapshot;final int limit,maxChars,index,offset;
        Request(JSONObject scope,String task,String run,JSONObject options)throws Exception{
            this.scope=scope;this.task=task;this.run=run;
            Set<String> allowed=Set.of("section","query","sourceId","cursor","limit","maxChars","expectedSnapshot");
            for(Iterator<String> keys=options.keys();keys.hasNext();)if(!allowed.contains(keys.next()))throw new IllegalArgumentException("执行记录读取字段无效");
            section=options.optString("section","messages");if(!Set.of("messages","operations","events","parents").contains(section))throw new IllegalArgumentException("未知执行记录范围");
            query=options.optString("query");sourceId=options.optString("sourceId");if(query.length()>500||sourceId.length()>800)throw new IllegalArgumentException("检索词或来源位置过长");unicode(query);unicode(sourceId);queryHash=hash(new JSONArray().put(normalize(query)).put(sourceId).toString());
            limit=integer(options,"limit",10,1,MAX_ROWS);maxChars=integer(options,"maxChars",4000,64,MAX_CHARS);
            String cursor=options.optString("cursor"),expected=options.optString("expectedSnapshot");int at=0,textOffset=0;
            if(!expected.isEmpty()&&!expected.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("内容指纹无效");
            if(!cursor.isEmpty()){
                if(cursor.length()>4000)throw new IllegalArgumentException("读取游标过长");JSONObject value;
                try{value=new JSONObject(new String(Base64.getUrlDecoder().decode(cursor),StandardCharsets.UTF_8));}catch(Exception malformed){throw new IllegalArgumentException("读取游标无效",malformed);}
                Set<String> keys=Set.of("version","taskId","runId","ownerId","spaceId","section","queryHash","snapshot","index","offset");
                if(value.length()!=keys.size())throw new IllegalArgumentException("读取游标字段无效");for(Iterator<String> names=value.keys();names.hasNext();)if(!keys.contains(names.next()))throw new IllegalArgumentException("读取游标字段无效");
                if(integer(value,"version",0,1,1)!=1||!task.equals(value.opt("taskId"))||!run.equals(value.opt("runId"))
                        ||!scope.getString("ownerId").equals(value.opt("ownerId"))||!scope.getString("spaceId").equals(value.opt("spaceId"))
                        ||!section.equals(value.opt("section"))||!queryHash.equals(value.opt("queryHash"))||!value.optString("snapshot").matches("[0-9a-f]{64}"))throw new SecurityException("读取游标不属于当前任务、空间或检索范围");
                if(!expected.isEmpty()&&!expected.equals(value.getString("snapshot")))throw new SecurityException("读取游标内容版本不一致");
                expected=value.getString("snapshot");at=integer(value,"index",0,0,Integer.MAX_VALUE);textOffset=integer(value,"offset",0,0,Integer.MAX_VALUE);
            }
            expectedSnapshot=expected;index=at;offset=textOffset;
        }
        String cursor(String snapshot,int index,int offset)throws Exception{
            JSONObject value=new JSONObject().put("version",1).put("taskId",task).put("runId",run).put("ownerId",scope.getString("ownerId"))
                    .put("spaceId",scope.getString("spaceId")).put("section",section).put("queryHash",queryHash).put("snapshot",snapshot).put("index",index).put("offset",offset);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(value.toString().getBytes(StandardCharsets.UTF_8));
        }
    }
    private static int integer(JSONObject value,String key,int fallback,int min,int max){Object number=value.opt(key);if(number==null)return fallback;if(!(number instanceof Integer||number instanceof Long)||((Number)number).longValue()<min||((Number)number).longValue()>max)throw new IllegalArgumentException("读取范围必须为有界整数");return ((Number)number).intValue();}
    private static boolean positiveInteger(Object value){return (value instanceof Integer||value instanceof Long)&&((Number)value).longValue()>0;}
    private static boolean matches(String body,String query){String text=normalize(body);for(String term:normalize(query).split("\\s+"))if(!text.contains(term))return false;return true;}
    private static String normalize(String value){return Normalizer.normalize(value,Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replaceAll("\\s+"," ").trim();}
    private static String preview(String value,int max){if(value.length()<=max)return value;int end=max;if(Character.isHighSurrogate(value.charAt(end-1)))end--;return value.substring(0,end)+"…";}
    private static void identifier(String value){if(value.isEmpty()||value.length()>240)throw new IllegalArgumentException("保存的标识超过读取范围");unicode(value);for(int i=0;i<value.length();i++)if(Character.isISOControl(value.charAt(i)))throw new IllegalArgumentException("保存的标识含控制字符");}
    private static void unicode(String value){for(int i=0;i<value.length();i++){char c=value.charAt(i);if(Character.isHighSurrogate(c)){if(++i>=value.length()||!Character.isLowSurrogate(value.charAt(i)))throw new IllegalArgumentException("文字编码无效");}else if(Character.isLowSurrogate(c))throw new IllegalArgumentException("文字编码无效");}}
    private static String digest(Object value)throws Exception{return hash(canonical(value));}
    private static String hash(String value)throws Exception{return hex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
    private static void charge(MessageDigest digest,String kind,String value){digest.update((kind+":"+value+"\n").getBytes(StandardCharsets.UTF_8));}
    private static String hex(byte[] bytes){StringBuilder result=new StringBuilder();for(byte value:bytes)result.append(String.format(Locale.ROOT,"%02x",value&255));return result.toString();}
    private static String canonical(Object value)throws Exception{
        if(value instanceof JSONObject){JSONObject object=(JSONObject)value;List<String> keys=new ArrayList<>();object.keys().forEachRemaining(keys::add);java.util.Collections.sort(keys);StringBuilder out=new StringBuilder("{");for(String key:keys){if(out.length()>1)out.append(',');out.append(JSONObject.quote(key)).append(':').append(canonical(object.get(key)));}return out.append('}').toString();}
        if(value instanceof JSONArray){JSONArray array=(JSONArray)value;StringBuilder out=new StringBuilder("[");for(int i=0;i<array.length();i++){if(i>0)out.append(',');out.append(canonical(array.get(i)));}return out.append(']').toString();}
        return value==null||value==JSONObject.NULL?"null":value instanceof String?JSONObject.quote((String)value):value.toString();
    }
}
