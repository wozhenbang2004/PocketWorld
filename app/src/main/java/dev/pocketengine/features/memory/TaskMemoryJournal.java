package dev.pocketengine.features.memory;

import java.util.Iterator;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Codec for dedicated native-reference rows in the existing TaskStore operations journal. */
public final class TaskMemoryJournal {
    public static final String TOOL="native_memory_reference",PREPARED="reference_prepared",PROVIDED="reference_provided",UNCONFIRMED="reference_unconfirmed",REVOKED="reference_revoked";
    private TaskMemoryJournal(){}
    /** Opaque native pre-send token; no JSON restore path can turn an uncertain send into provided. */
    public static final class Intent {
        public final String operationId,requestId;public final TaskMemoryUse.TaskRef task;
        private final String arguments;
        private Intent(String operationId,TaskMemoryUse.TaskRef task,String requestId,JSONObject arguments){this.operationId=operationId;this.task=task;this.requestId=requestId;this.arguments=arguments.toString();}
        public JSONObject arguments()throws Exception{return new JSONObject(arguments);}
    }
    /** Root calls after validateTaskMemorySelection, with its real native request identity. */
    public static Intent prepare(String operationId,TaskMemoryUse.TaskRef task,String requestId,TaskMemoryUse.Selection selected)throws Exception{
        requireId(operationId);new TaskMemoryUse.ReceiptRef(task,requestId,"unconfirmed");
        if(selected==null)throw new IllegalArgumentException("缺少原生记忆选择");JSONObject metadata=selected.metadata();
        requireBinding(metadata,task);
        JSONObject diagnostics=MemoryRecallBudget.checked(metadata.getJSONObject("selectionDiagnostics"),metadata.getJSONArray("records"));
        JSONObject args=task.toJson().put("format",2).put("requestId",requestId).put("selectionId",metadata.getString("selectionId"))
                .put("contextDigest",metadata.getString("contextDigest")).put("records",metadata.getJSONArray("records"))
                .put("selectionDiagnostics",diagnostics).put("diagnosticsDigest",TaskMemoryUse.digest(canonical(diagnostics)));
        // Validate the exact body-free shape through the same typed projection validator.
        new TaskMemoryUse.Provided(new TaskMemoryUse.ReceiptRef(task,requestId,"unconfirmed"),args.getString("selectionId"),args.getString("contextDigest"),1,false,args.getJSONArray("records"),diagnostics);
        return new Intent(operationId,task,requestId,args);
    }
    /** Called only by the dedicated native completion path after an actual completed model response. */
    public static JSONObject completed(Intent intent,String nativeAnswerId,long at)throws Exception{
        if(intent==null||at<=0)throw new IllegalArgumentException("缺少原生发送意图或时间");requireId(nativeAnswerId);
        JSONObject args=intent.arguments();return new TaskMemoryUse.ReceiptRef(intent.task,intent.requestId,nativeAnswerId).toJson()
                .put("format",2).put("selectionId",args.getString("selectionId")).put("contextDigest",args.getString("contextDigest")).put("diagnosticsDigest",args.getString("diagnosticsDigest"))
                .put("providedAt",at).put("executionAuthorized",false).put("modelUseVerified",false);
    }
    /** Requires a row from TaskStore.get's native operations projection, never a task event/model JSON. */
    public static TaskMemoryUse.Provided readProvided(JSONObject task,TaskMemoryUse.ReceiptRef expected)throws Exception{
        if(task==null)return null;
        if(!expected.task.taskId.equals(task.optString("id"))||!expected.task.ownerId.equals(task.optString("actorId"))||!expected.task.spaceId.equals(task.optString("spaceId")))throw new SecurityException("任务来源日志空间不匹配");
        JSONArray operations=task.optJSONArray("operations");TaskMemoryUse.Provided found=null;
        for(int i=0;operations!=null&&i<operations.length();i++){
            JSONObject operation=operations.getJSONObject(i);String state=operation.optString("state");
            if(!TOOL.equals(operation.optString("tool"))||!Set.of(PROVIDED,REVOKED).contains(state)||!expected.task.runId.equals(operation.optString("runId")))continue;
            JSONObject receipt=operation.optJSONObject("receipt"),args=operation.optJSONObject("arguments");
            if(receipt==null||args==null||!expected.requestId.equals(receipt.optString("requestId"))||!expected.answerId.equals(receipt.optString("answerId")))continue;
            long format=SourceRef.integer(args,"format",1);checkKeys(args,argumentKeys(format));
            Set<String> receiptKeys=new java.util.HashSet<>(Set.of("format","taskId","runId","coordinatorEpoch","ownerId","spaceId","requestId","answerId","selectionId","contextDigest","providedAt","executionAuthorized","modelUseVerified"));if(format==2)receiptKeys.add("diagnosticsDigest");checkKeys(receipt,receiptKeys);
            if(SourceRef.integer(receipt,"format",1)!=format||!expected.requestId.equals(args.optString("requestId")))throw new SecurityException("原生记忆日志格式或请求不匹配");
            requireBinding(args,expected.task);requireBinding(receipt,expected.task);
            if(!args.getString("selectionId").equals(receipt.getString("selectionId"))||!args.getString("contextDigest").equals(receipt.getString("contextDigest"))
                    ||!Boolean.FALSE.equals(receipt.opt("executionAuthorized"))||!Boolean.FALSE.equals(receipt.opt("modelUseVerified")))throw new SecurityException("原生记忆日志不能自证执行或模型采用");
            if(found!=null)throw new SecurityException("同一原请求/答案存在重复记忆证明，请核对原日志");
            JSONObject diagnostics=format==2?MemoryRecallBudget.checked(args.getJSONObject("selectionDiagnostics"),args.getJSONArray("records")):null;
            if(format==2&&(!args.getString("diagnosticsDigest").equals(TaskMemoryUse.digest(canonical(diagnostics)))||!args.getString("diagnosticsDigest").equals(receipt.getString("diagnosticsDigest"))))throw new SecurityException("原始记忆选择说明指纹不匹配");
            found=new TaskMemoryUse.Provided(expected,args.getString("selectionId"),args.getString("contextDigest"),SourceRef.integer(receipt,"providedAt",1),REVOKED.equals(state),args.getJSONArray("records"),diagnostics);
        }return found;
    }
    /** Recover original epoch from the dedicated native row, rather than trusting an epoch in UI input. */
    public static TaskMemoryUse.ReceiptRef receiptRef(JSONObject task,String runId,String requestId,String answerId)throws Exception{
        if(task==null)return null;requireId(runId);requireId(requestId);requireId(answerId);
        JSONArray operations=task.optJSONArray("operations");TaskMemoryUse.ReceiptRef found=null;
        for(int i=0;operations!=null&&i<operations.length();i++){
            JSONObject op=operations.getJSONObject(i),receipt=op.optJSONObject("receipt"),args=op.optJSONObject("arguments");
            if(!TOOL.equals(op.optString("tool"))||!Set.of(PROVIDED,REVOKED).contains(op.optString("state"))||!runId.equals(op.optString("runId"))
                    ||receipt==null||args==null||!requestId.equals(receipt.optString("requestId"))||!answerId.equals(receipt.optString("answerId")))continue;
            TaskMemoryUse.TaskRef taskRef=new TaskMemoryUse.TaskRef(task.getString("id"),runId,SourceRef.integer(args,"coordinatorEpoch",0),task.getString("actorId"),task.getString("spaceId"));
            TaskMemoryUse.ReceiptRef ref=new TaskMemoryUse.ReceiptRef(taskRef,requestId,answerId);
            if(readProvided(task,ref)==null)throw new SecurityException("原生记忆回执不可恢复");
            if(found!=null)throw new SecurityException("同一原请求/答案存在重复日志");found=ref;
        }return found;
    }
    /** Native TaskStore completion checks its persisted intent against the live opaque token. */
    public static void requireIntent(Intent intent,JSONObject storedArguments)throws Exception{
        JSONObject expected=intent.arguments();checkKeys(storedArguments,argumentKeys(SourceRef.integer(expected,"format",1)));
        if(!canonical(expected).equals(canonical(storedArguments)))throw new SecurityException("发送意图已改变，不能确认旧记忆提供记录");
    }
    private static Set<String> argumentKeys(long format){
        if(format!=1&&format!=2)throw new SecurityException("不支持的原生记忆日志版本");
        Set<String> keys=new java.util.HashSet<>(Set.of("format","taskId","runId","coordinatorEpoch","ownerId","spaceId","requestId","selectionId","contextDigest","records"));
        if(format==2){keys.add("selectionDiagnostics");keys.add("diagnosticsDigest");}return keys;
    }
    private static void requireBinding(JSONObject value,TaskMemoryUse.TaskRef task)throws Exception{
        if(!task.taskId.equals(value.opt("taskId"))||!task.runId.equals(value.opt("runId"))||task.coordinatorEpoch!=SourceRef.integer(value,"coordinatorEpoch",0)
                ||!task.ownerId.equals(value.opt("ownerId"))||!task.spaceId.equals(value.opt("spaceId")))throw new SecurityException("原生记忆来源执行身份不匹配");
    }
    private static void checkKeys(JSONObject value,Set<String> allowed){if(value==null||value.length()!=allowed.size())throw new SecurityException("原生记忆日志字段缺失");for(Iterator<String> keys=value.keys();keys.hasNext();)if(!allowed.contains(keys.next()))throw new SecurityException("原生记忆日志含非投影字段");}
    private static void requireId(String id){if(id==null||!id.matches("[\\p{L}\\p{N}_.:/@-]{1,240}"))throw new IllegalArgumentException("原生记忆请求标识无效");}
    private static String canonical(Object value)throws Exception{
        if(value instanceof JSONObject){JSONObject object=(JSONObject)value;java.util.List<String> keys=new java.util.ArrayList<>();for(Iterator<String> it=object.keys();it.hasNext();)keys.add(it.next());java.util.Collections.sort(keys);StringBuilder s=new StringBuilder("{");for(String key:keys)s.append(JSONObject.quote(key)).append(':').append(canonical(object.get(key))).append(',');return s.append('}').toString();}
        if(value instanceof JSONArray){JSONArray array=(JSONArray)value;StringBuilder s=new StringBuilder("[");for(int i=0;i<array.length();i++)s.append(canonical(array.get(i))).append(',');return s.append(']').toString();}
        String scalar=new JSONArray().put(value).toString();return scalar.substring(1,scalar.length()-1);
    }
}
