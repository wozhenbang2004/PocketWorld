package dev.pocketengine.features.memory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Read-only projection of the native task/send journal into the one authoritative memory book. */
public final class TaskMemoryUse {
    private final MemoryBook book;
    private final HostReader host;
    private final SourceReader sources;
    TaskMemoryUse(MemoryBook book,HostReader host,SourceReader sources){this.book=book;this.host=host;this.sources=sources;}

    /** Local native reads only. Neither model JSON nor a UI callback is evidence of request delivery. */
    public interface HostReader {
        TaskState readTask(MemoryBook.Scope authenticatedScope,String taskId)throws Exception;
        Provided readProvided(MemoryBook.Scope authenticatedScope,ReceiptRef expected)throws Exception;
    }
    /** Adapt the existing exact-version TaskStore/assistant source reader, never a current summary. */
    public interface SourceReader {Evidence read(SourceRef expected)throws Exception;}
    public static final class TaskRef {
        public final String taskId,runId,ownerId,spaceId;public final long coordinatorEpoch;
        public TaskRef(String taskId,String runId,long coordinatorEpoch,String ownerId,String spaceId){
            this.taskId=identifier(taskId);this.runId=identifier(runId);
            if(coordinatorEpoch<0)throw new IllegalArgumentException("任务代次无效");this.coordinatorEpoch=coordinatorEpoch;
            MemoryBook.Scope scope=MemoryBook.Scope.from(jsonScope(ownerId,spaceId));this.ownerId=scope.ownerId;this.spaceId=scope.spaceId;
        }
        public JSONObject toJson()throws Exception{return jsonScope(ownerId,spaceId).put("taskId",taskId).put("runId",runId).put("coordinatorEpoch",coordinatorEpoch);}
        boolean same(TaskRef other){return other!=null&&taskId.equals(other.taskId)&&runId.equals(other.runId)&&coordinatorEpoch==other.coordinatorEpoch&&ownerId.equals(other.ownerId)&&spaceId.equals(other.spaceId);}
    }
    public static final class TaskState {
        public final TaskRef ref;public final String status;public final boolean cancelled;
        public TaskState(TaskRef ref,String status,boolean cancelled){this.ref=java.util.Objects.requireNonNull(ref);this.status=text(status,1,80);this.cancelled=cancelled;}
    }
    public static final class ReceiptRef {
        public final TaskRef task;public final String requestId,answerId;
        public ReceiptRef(TaskRef task,String requestId,String answerId){this.task=java.util.Objects.requireNonNull(task);this.requestId=identifier(requestId);this.answerId=identifier(answerId);}
        public JSONObject toJson()throws Exception{return task.toJson().put("requestId",requestId).put("answerId",answerId);}
        boolean same(ReceiptRef other){return other!=null&&task.same(other.task)&&requestId.equals(other.requestId)&&answerId.equals(other.answerId);}
    }
    /** Native journal adapter constructs this only from the exact original context/send receipt. */
    public static final class Provided {
        public final ReceiptRef ref;public final String selectionId,contextDigest;public final long providedAt;public final boolean revoked;
        private final String records,diagnostics;
        public Provided(ReceiptRef ref,String selectionId,String contextDigest,long providedAt,boolean revoked,JSONArray originalRecordRefs)throws Exception{
            this(ref,selectionId,contextDigest,providedAt,revoked,originalRecordRefs,null);
        }
        public Provided(ReceiptRef ref,String selectionId,String contextDigest,long providedAt,boolean revoked,JSONArray originalRecordRefs,JSONObject selectionDiagnostics)throws Exception{
            this.ref=java.util.Objects.requireNonNull(ref);this.selectionId=identifier(selectionId);
            if(contextDigest==null||!contextDigest.matches("[0-9a-f]{64}")||providedAt<=0)throw new IllegalArgumentException("原生提供回执指纹或时间无效");
            this.contextDigest=contextDigest;this.providedAt=providedAt;this.revoked=revoked;
            this.records=checkedReferences(ref.task,originalRecordRefs).toString();
            JSONObject checked=MemoryRecallBudget.checked(selectionDiagnostics,new JSONArray(records));this.diagnostics=checked==null?null:checked.toString();
        }
        JSONArray references()throws Exception{return new JSONArray(records);}
        public JSONObject selectionDiagnostics()throws Exception{return diagnostics==null?null:new JSONObject(diagnostics);}
    }
    public static final class Evidence {
        public final SourceRef ref;public final String title,text;
        public Evidence(SourceRef ref,String title,String text){this.ref=java.util.Objects.requireNonNull(ref);this.title=text(title,1,160);this.text=text(text,1,24000);}
    }
    /** Opaque per-book preparation. snapshot/metadata copies cannot mutate or recreate its identity. */
    public static final class Selection {
        private final MemoryBook issuer;private final String snapshot,metadata;
        private final TaskRef task;private final MemoryAudienceSelection audience;
        private Selection(TaskMemoryUse issuer,TaskRef task,JSONObject snapshot,JSONObject metadata,MemoryAudienceSelection audience){this.issuer=issuer.book;this.task=task;this.snapshot=snapshot.toString();this.metadata=metadata.toString();this.audience=audience;}
        public JSONObject snapshot()throws Exception{return new JSONObject(snapshot);}
        /** Root may persist this body-free projection in its existing native send journal. */
        public JSONObject metadata()throws Exception{return new JSONObject(metadata);}
    }

    /** Select using the real task's current scope/run. A preparation is never a provided receipt. */
    public Selection prepareTaskRecall(JSONObject authenticatedScope,TaskRef task,String query,int limit,int maxChars,MemoryHostFence fence)throws Exception{
        return prepareTaskRecall(authenticatedScope,task,query,limit,maxChars,fence,null);
    }
    /** Exact native member allowlist; no audience retains the original personal owner behavior. */
    public Selection prepareTaskRecall(JSONObject authenticatedScope,TaskRef task,String query,int limit,int maxChars,MemoryHostFence fence,ReadAudience readAudience)throws Exception{
        return prepareRecallPlan(authenticatedScope,task,query,null,limit,maxChars,fence,readAudience);
    }
    /** A native metered query rewrite only changes candidate ranking, never caller authority. */
    public Selection prepareSemanticTaskRecall(JSONObject authenticatedScope,TaskRef task,MemoryQueryPlan plan,int limit,int maxChars,MemoryHostFence fence)throws Exception{
        return prepareRecallPlan(authenticatedScope,task,"",java.util.Objects.requireNonNull(plan),limit,maxChars,fence,null);
    }
    private Selection prepareRecallPlan(JSONObject authenticatedScope,TaskRef task,String query,MemoryQueryPlan plan,int limit,int maxChars,MemoryHostFence fence,ReadAudience readAudience)throws Exception{
        MemoryBook.Scope scope=scope(authenticatedScope);requireScope(scope,task);requireFence(fence);
        MemoryAudienceSelection audience=MemoryAudienceSelection.capture(readAudience,scope);final Selection[] selection={null};
        fence.admit(()->{checkAudience(audience);synchronized(book){
            TaskState state=task(scope,task);if(!"current".equals(taskStatus(state,task))||!Set.of("preparing","running").contains(state.status))throw new IllegalStateException("任务不可用于新的记忆请求");
            JSONObject snapshot=plan==null?book.contextSnapshotScoped(authenticatedScope,query,limit,maxChars,audience):book.contextSnapshotQueriesScoped(authenticatedScope,plan,limit,maxChars,audience);JSONArray rows=snapshot.getJSONArray("records"),refs=new JSONArray(),projected=new JSONArray();
            for(int i=0;i<rows.length();i++){JSONObject ref=book.taskReference(scope,rows.getJSONObject(i));refs.put(ref);projected.put(book.taskReferenceState(scope,ref));}
            String id=UUID.randomUUID().toString(),digest=digest(snapshot.getString("context"));
            JSONObject metadata=task.toJson().put("selectionId",id).put("contextDigest",digest).put("records",refs)
                    .put("selectionDiagnostics",MemoryRecallBudget.checked(snapshot.getJSONObject("selectionDiagnostics"),refs));
            JSONObject binding=task.toJson();for(java.util.Iterator<String> it=binding.keys();it.hasNext();){String key=it.next();snapshot.put(key,binding.get(key));}
            snapshot.put("task",binding).put("records",projected).put("receiptId",id).put("selectionId",id).put("contextDigest",digest).put("phase","prepared")
                    .put("status","current").put("modelUseVerified",false).put("executionAuthorized",false);
            if(audience!=null){book.requireAudienceSnapshotCurrent(scope,snapshot,audience);snapshot.put("audience",audience.metadata());metadata.put("audience",audience.metadata());}
            if(!"current".equals(taskStatus(task(scope,task),task)))throw new IllegalStateException("任务来源已变化");
            selection[0]=new Selection(this,task,snapshot,metadata,audience);
        }checkAudience(audience);return new JSONObject().put("prepared",true);});return selection[0];
    }
    /** Fresh final-read gate for root's existing request assembly; never substitutes newly searched facts. */
    public JSONObject validateTaskMemorySelection(JSONObject authenticatedScope,Selection selection,MemoryHostFence fence)throws Exception{
        MemoryBook.Scope scope=scope(authenticatedScope);requireFence(fence);
        if(selection==null||selection.issuer!=book)throw new SecurityException("不是此记忆书的选择");requireScope(scope,selection.task);
        checkAudience(selection.audience);return fence.admit(()->{checkAudience(selection.audience);synchronized(book){
            TaskState state=task(scope,selection.task);if(!"current".equals(taskStatus(state,selection.task))||!Set.of("preparing","running").contains(state.status))throw new IllegalStateException("任务执行代次已失效");
            JSONArray refs=selection.metadata().getJSONArray("records");for(int i=0;i<refs.length();i++)
                if(!"current".equals(book.taskReferenceState(scope,refs.getJSONObject(i)).getString("status")))throw new IllegalStateException("选中记忆已变化，不能提供旧片段");
            if(!"current".equals(taskStatus(task(scope,selection.task),selection.task)))throw new IllegalStateException("任务已变化");
            JSONObject result=selection.snapshot();book.requireAudienceSnapshotCurrent(scope,result,selection.audience);checkAudience(selection.audience);return result;
        }});
    }
    /** Confirm only the native journal's exact original projection; do not infer use from a search. */
    public JSONObject confirmTaskMemoryUse(JSONObject authenticatedScope,Selection selection,ReceiptRef expected,MemoryHostFence fence)throws Exception{
        MemoryBook.Scope scope=scope(authenticatedScope);requireScope(scope,expected.task);requireFence(fence);
        if(selection==null||selection.issuer!=book||!selection.task.same(expected.task))throw new SecurityException("不是本记忆书为该任务生成的选择");
        checkAudience(selection.audience);return fence.admit(()->{checkAudience(selection.audience);synchronized(book){
            Provided proof=provided(scope,expected);if(proof==null)return unavailable(expected,"native_proof_unavailable");
            JSONObject metadata=selection.metadata();
            if(!proof.selectionId.equals(metadata.getString("selectionId"))||!proof.contextDigest.equals(metadata.getString("contextDigest"))
                    ||!canonical(proof.references()).equals(canonical(metadata.getJSONArray("records")))
                    ||proof.selectionDiagnostics()!=null&&!canonical(proof.selectionDiagnostics()).equals(canonical(metadata.getJSONObject("selectionDiagnostics"))))return unavailable(expected,"native_proof_mismatch");
            requireAudienceReferences(selection.audience,proof.references());JSONObject result=project(scope,expected,proof);checkAudience(selection.audience);return result;
        }});
    }
    /** Recovery reads the original host journal. It never calls search or accepts a supplied receipt JSON. */
    public JSONObject readTaskMemoryReceipt(JSONObject authenticatedScope,ReceiptRef expected,MemoryHostFence fence)throws Exception{
        return readTaskMemoryReceipt(authenticatedScope,expected,fence,null);
    }
    public JSONObject readTaskMemoryReceipt(JSONObject authenticatedScope,ReceiptRef expected,MemoryHostFence fence,ReadAudience readAudience)throws Exception{
        MemoryBook.Scope scope=scope(authenticatedScope);requireScope(scope,expected.task);requireFence(fence);MemoryAudienceSelection audience=MemoryAudienceSelection.capture(readAudience,scope);
        return fence.admit(()->{checkAudience(audience);synchronized(book){Provided proof=provided(scope,expected);if(proof==null)return unavailable(expected,"native_proof_unavailable");
            requireAudienceReferences(audience,proof.references());JSONObject result=project(scope,expected,proof);checkAudience(audience);return result;
        }});
    }
    /** Source expansion is tied to a current record in a proven request, not an arbitrary source ID. */
    public JSONObject readTaskMemorySource(JSONObject authenticatedScope,ReceiptRef receipt,String recordId,long revision,SourceRef expected,MemoryHostFence fence)throws Exception{
        return readTaskMemorySource(authenticatedScope,receipt,recordId,revision,expected,0,24000,fence,null);
    }
    /** Native member source span. Selecting a formal record alone never permits whole-source expansion. */
    public JSONObject readTaskMemorySource(JSONObject authenticatedScope,ReceiptRef receipt,String recordId,long revision,SourceRef expected,int offset,int maxChars,MemoryHostFence fence,ReadAudience readAudience)throws Exception{
        MemoryBook.Scope scope=scope(authenticatedScope);requireScope(scope,receipt.task);requireFence(fence);
        if(expected==null||recordId==null||revision<1||offset<0||maxChars<1||maxChars>24000)throw new IllegalArgumentException("必须指定本次来源的精确记录版本及范围");
        MemoryAudienceSelection audience=MemoryAudienceSelection.capture(readAudience,scope);
        if(audience!=null&&(!audience.permits(recordId,revision)||!audience.permits(expected,offset,maxChars)))throw new SecurityException("成员未获选择这条记忆或此精确原文范围");
        return fence.admit(()->{checkAudience(audience);synchronized(book){
            Provided proof=provided(scope,receipt);if(proof==null)return unavailable(receipt,"native_proof_unavailable");requireAudienceReferences(audience,proof.references());
            JSONObject current=project(scope,receipt,proof),record=null;JSONArray rows=current.getJSONArray("records");
            for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);if(recordId.equals(row.getString("id"))&&revision==row.getLong("revision"))record=row;}
            if(record==null||!"current".equals(record.getString("status")))return sourceUnavailable(expected,"reference_invalidated");
            boolean attached=false;JSONArray refs=record.getJSONArray("sourceRefs");
            for(int i=0;i<refs.length();i++)if(expected.equals(SourceRef.fromJson(refs.getJSONObject(i))))attached=true;
            if(!attached||!expected.belongsTo(scope))throw new SecurityException("来源不属于本次提供的精确记忆版本");
            if(sources==null)return sourceUnavailable(expected,"body_unavailable");
            Evidence body;try{body=sources.read(expected);}catch(Exception missing){return sourceUnavailable(expected,"body_unavailable");}
            if(body==null||!expected.equals(body.ref)||offset>body.text.length())return sourceUnavailable(expected,"body_unavailable");
            int end=TaskMemoryTrace.safeEnd(body.text,offset,maxChars);checkAudience(audience);
            Provided checked=provided(scope,receipt);if(!sameProof(proof,checked))return sourceUnavailable(expected,"reference_invalidated");
            requireAudienceReferences(audience,checked.references());JSONObject after=project(scope,receipt,checked);boolean stillCurrent=false;JSONArray afterRows=after.getJSONArray("records");
            for(int i=0;i<afterRows.length();i++){JSONObject row=afterRows.getJSONObject(i);if(recordId.equals(row.getString("id"))&&revision==row.getLong("revision")&&"current".equals(row.getString("status")))stillCurrent=true;}
            if(!stillCurrent)return sourceUnavailable(expected,"reference_invalidated");checkAudience(audience);
            return new JSONObject().put("status","current").put("sourceRef",expected.toJson()).put("title",audience==null?body.title:"所选原文范围").put("text",body.text.substring(offset,end))
                    .put("readRange",TaskMemoryTrace.rawRange(offset,end,body.text.length())).put("nextOffset",end<body.text.length()?end:-1)
                    .put("evidenceRole","host_exact_version_snapshot_not_execution_proof").put("executionAuthorized",false).put("modelUseVerified",false);
        }});
    }
    /** Native discussion/member source read without inventing a TaskMemoryUse provided receipt.
     * Only an explicit source span is read. This records an observation, never method application,
     * formal memory publication, execution authority, or proof that a model used the returned text. */
    public JSONObject readAudienceSource(JSONObject authenticatedScope,SourceRef expected,int offset,int chars,ReadAudience readAudience)throws Exception{
        MemoryBook.Scope scope=scope(authenticatedScope);
        if(readAudience==null||expected==null||!expected.belongsTo(scope)||offset<0||chars<1||chars>ReadAudience.MAX_SOURCE_CHARS)
            throw new SecurityException("成员原文读取必须绑定当前认证范围及精确字符区间");
        MemoryAudienceSelection audience=MemoryAudienceSelection.capture(readAudience,scope);
        if(!audience.permits(expected,offset,chars))throw new SecurityException("原文区间不在主人为此成员显选的范围内");
        requireSourceNotForgotten(authenticatedScope,expected);
        if(sources==null)return sourceUnavailable(expected,"body_unavailable");audience.check();Evidence first=sources.read(expected);
        if(first==null||!expected.equals(first.ref)||offset>first.text.length())return sourceUnavailable(expected,"body_unavailable");
        int end=TaskMemoryTrace.safeEnd(first.text,offset,chars);audience.check();requireSourceNotForgotten(authenticatedScope,expected);Evidence current=sources.read(expected);
        if(current==null||!expected.equals(current.ref)||!first.text.equals(current.text))return sourceUnavailable(expected,"source_changed");
        audience.check();requireSourceNotForgotten(authenticatedScope,expected);return new JSONObject().put("status","current").put("sourceRef",expected.toJson()).put("title","所选原文范围")
                .put("text",first.text.substring(offset,end)).put("readRange",TaskMemoryTrace.rawRange(offset,end,first.text.length()))
                .put("audience",audience.metadata()).put("readObserved",true).put("modelUseVerified",false).put("executionAuthorized",false).put("formalMemorySaved",false);
    }
    private void requireSourceNotForgotten(JSONObject scope,SourceRef expected)throws Exception{
        if(book.isSourceForgotten(scope,expected))throw new SecurityException("原文此版本已被主人遗忘，显选成员范围不能复活旧来源");
    }
    private static void checkAudience(MemoryAudienceSelection audience)throws Exception{if(audience!=null)audience.check();}
    private static void requireAudienceReferences(MemoryAudienceSelection audience,JSONArray refs)throws Exception{
        if(audience==null)return;for(int i=0;i<refs.length();i++){JSONObject row=refs.getJSONObject(i);if(!audience.permits(row.getString("id"),SourceRef.integer(row,"revision",1)))throw new SecurityException("原提供回执含未选入当前成员范围的记忆版本");}
    }
    /** Explicit replacement picker, separate from provenance: it never rewrites the original receipt. */
    public JSONObject listTaskMemoryReplacements(JSONObject authenticatedScope,ReceiptRef receipt,String recordId,long revision,MemoryHostFence fence)throws Exception{
        MemoryBook.Scope scope=scope(authenticatedScope);requireScope(scope,receipt.task);requireFence(fence);
        return fence.admit(()->{synchronized(book){
            Provided proof=provided(scope,receipt);if(proof==null)return unavailable(receipt,"native_proof_unavailable");
            JSONObject current=project(scope,receipt,proof);boolean selected=false;JSONArray refs=current.getJSONArray("records");
            for(int i=0;i<refs.length();i++){JSONObject row=refs.getJSONObject(i);if(recordId.equals(row.getString("id"))&&revision==row.getLong("revision")&&"current".equals(row.getString("status")))selected=true;}
            if(!selected)return new JSONObject().put("status","reference_invalidated").put("records",new JSONArray());
            return new JSONObject().put("status","current").put("records",book.taskReplacementCandidates(scope,recordId,revision)).put("executionAuthorized",false);
        }});
    }

    private JSONObject project(MemoryBook.Scope scope,ReceiptRef expected,Provided proof)throws Exception{
        if(proof==null)return unavailable(expected,"native_proof_unavailable");
        String taskState=taskStatus(task(scope,expected.task),expected.task);
        String taskGate=proof.revoked?"revoked":taskState,status=taskGate;
        JSONArray records=new JSONArray(),refs=proof.references();
        for(int i=0;i<refs.length();i++){
            JSONObject row=book.taskReferenceState(scope,refs.getJSONObject(i));
            if(!"current".equals(taskGate)){row.remove("text");row.put("status",taskGate);}
            if(!"current".equals(row.getString("status"))&&"current".equals(status))status="invalidated";
            records.put(row);
        }
        // A later invalid row must not turn an otherwise-current row into an invented invalid one.
        String finalTaskState=taskStatus(task(scope,expected.task),expected.task);
        if(!"current".equals(finalTaskState)){status=finalTaskState;for(int i=0;i<records.length();i++){JSONObject row=records.getJSONObject(i);row.remove("text");row.put("status",finalTaskState);}}
        JSONObject result=expected.toJson().put("receiptId",proof.selectionId).put("selectionId",proof.selectionId).put("phase","provided_to_request")
                .put("status",status).put("contextDigest",proof.contextDigest).put("recordedAt",proof.providedAt).put("checkedAt",System.currentTimeMillis())
                .put("records",records).put("executionAuthorized",false).put("modelUseVerified",false);
        JSONObject diagnostics=proof.selectionDiagnostics();result.put("selectionDiagnosticsAvailable",diagnostics!=null);
        if(diagnostics!=null){result.put("selectionDiagnostics",diagnostics);JSONArray selected=diagnostics.getJSONArray("selected");for(int i=0;i<records.length();i++)records.getJSONObject(i).put("selectionReason",selected.getJSONObject(i).getString("reason"));}
        return result;
    }
    private TaskState task(MemoryBook.Scope scope,TaskRef ref){try{return host==null?null:host.readTask(scope,ref.taskId);}catch(Exception unavailable){return null;}}
    private Provided provided(MemoryBook.Scope scope,ReceiptRef expected)throws Exception{
        Provided proof;try{proof=host==null?null:host.readProvided(scope,expected);}catch(Exception unavailable){return null;}
        if(proof!=null&&!proof.ref.same(expected))throw new SecurityException("原生回执绑定了其他任务或请求");return proof;
    }
    private static boolean sameProof(Provided a,Provided b)throws Exception{return a!=null&&b!=null&&a.ref.same(b.ref)&&a.selectionId.equals(b.selectionId)&&a.contextDigest.equals(b.contextDigest)&&a.providedAt==b.providedAt&&a.revoked==b.revoked&&canonical(a.references()).equals(canonical(b.references()))&&canonical(a.selectionDiagnostics()).equals(canonical(b.selectionDiagnostics()));}
    private static String taskStatus(TaskState state,TaskRef expected){return state==null?"task_unavailable":!expected.same(state.ref)?"run_changed":state.cancelled?"task_cancelled":"current";}
    private static JSONObject unavailable(ReceiptRef ref,String reason)throws Exception{return ref.toJson().put("phase","unavailable").put("status","unavailable").put("reason",reason).put("records",new JSONArray()).put("executionAuthorized",false).put("modelUseVerified",false);}
    private static JSONObject sourceUnavailable(SourceRef ref,String reason)throws Exception{return new JSONObject().put("status",reason).put("sourceRef",ref.toJson()).put("executionAuthorized",false).put("modelUseVerified",false);}
    private static void requireFence(MemoryHostFence fence){if(fence==null)throw new SecurityException("缺少原生任务准入边界");}
    private static MemoryBook.Scope scope(JSONObject input){if(input==null||!(input.opt("ownerId") instanceof String)||!(input.opt("spaceId") instanceof String))throw new SecurityException("必须明确指定原生认证空间");return MemoryBook.Scope.from(input);}
    private static void requireScope(MemoryBook.Scope scope,TaskRef task){if(task==null||!scope.ownerId.equals(task.ownerId)||!scope.spaceId.equals(task.spaceId))throw new SecurityException("任务记忆不能跨空间");}
    private static JSONObject jsonScope(String owner,String space){try{return new JSONObject().put("ownerId",owner).put("spaceId",space);}catch(Exception bad){throw new IllegalArgumentException(bad);}}
    private static String identifier(String value){if(value==null||!value.matches("[\\p{L}\\p{N}_.:/@-]{1,240}"))throw new IllegalArgumentException("原生任务或请求标识无效");return value;}
    private static String text(String value,int min,int max){if(value==null||value.trim().length()<min||value.length()>max||value.indexOf('\0')>=0)throw new IllegalArgumentException("来源文字为空、过长或无效");MemoryBook.validateUnicode(value);return value;}
    static String digest(String value)throws Exception{byte[] bytes=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));StringBuilder result=new StringBuilder();for(byte b:bytes)result.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return result.toString();}
    private static JSONArray checkedReferences(TaskRef task,JSONArray input)throws Exception{
        if(input==null||input.length()>MemoryBook.MAX_CONTEXT_RECORDS)throw new IllegalArgumentException("原生记忆回执条数无效");JSONArray result=new JSONArray();Set<String> ids=new HashSet<>();
        for(int i=0;i<input.length();i++){
            JSONObject row=input.getJSONObject(i);for(java.util.Iterator<String> keys=row.keys();keys.hasNext();)if(!Set.of("id","revision","ownerId","spaceId","selection","sourceRefs").contains(keys.next()))throw new IllegalArgumentException("记忆回执只接受原始版本/来源元数据");
            String id=row.getString("id");if(!id.matches("[0-9a-f-]{36}")||!ids.add(id)||!task.ownerId.equals(row.getString("ownerId"))||!task.spaceId.equals(row.getString("spaceId"))||!Set.of("core","retrieved").contains(row.getString("selection")))throw new SecurityException("记忆回执空间或记录无效");
            long revision=SourceRef.integer(row,"revision",1);JSONArray sources=row.getJSONArray("sourceRefs");if(sources.length()>100)throw new IllegalArgumentException("记忆回执来源过多");
            JSONArray checked=new JSONArray();Set<SourceRef> unique=new HashSet<>();for(int j=0;j<sources.length();j++){SourceRef ref=SourceRef.fromJson(sources.getJSONObject(j));if(!task.ownerId.equals(ref.ownerId)||!task.spaceId.equals(ref.spaceId)||!unique.add(ref))throw new SecurityException("记忆回执来源跨空间或重复");checked.put(ref.toJson());}
            result.put(jsonScope(task.ownerId,task.spaceId).put("id",id).put("revision",revision).put("selection",row.getString("selection")).put("sourceRefs",checked));
        }return result;
    }
    private static String canonical(Object value)throws Exception{
        if(value instanceof JSONObject){JSONObject object=(JSONObject)value;java.util.List<String> keys=new java.util.ArrayList<>();for(java.util.Iterator<String> it=object.keys();it.hasNext();)keys.add(it.next());java.util.Collections.sort(keys);StringBuilder s=new StringBuilder("{");for(String key:keys)s.append(JSONObject.quote(key)).append(':').append(canonical(object.get(key))).append(',');return s.append('}').toString();}
        if(value instanceof JSONArray){JSONArray array=(JSONArray)value;StringBuilder s=new StringBuilder("[");for(int i=0;i<array.length();i++)s.append(canonical(array.get(i))).append(',');return s.append(']').toString();}
        String scalar=new JSONArray().put(value).toString();return scalar.substring(1,scalar.length()-1);
    }
}
