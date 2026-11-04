package dev.pocketengine.features.memory;

import dev.pocketengine.store.TaskStore;
import dev.pocketengine.core.CapabilityPort;
import dev.pocketengine.core.CapabilitySnapshot;
import dev.pocketengine.core.RunControl;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Reads existing TaskStore facts/snapshots; request delivery remains the root send journal's fact. */
public final class TaskStoreMemoryReader implements TaskMemoryUse.HostReader,TaskMemoryUse.SourceReader {
    public static final String RAW_QUERY="memory_raw_query",RAW_READ="memory_raw_read";
    /** One short native read batch. It never returns a substitute message or survives a model call. */
    public interface RawMessageCheck extends AutoCloseable {
        void requireCurrentOriginalMessage(int checkpointIndex,JSONObject originalMessage)throws Exception;
        default JSONObject readBudget()throws Exception{return new JSONObject();}
        @Override void close()throws Exception;
    }
    public interface RawMessageCurrentness {RawMessageCheck open(JSONArray originals,long sourceBytesAlreadyMaterialized)throws Exception;}
    public static final class RawMessageUnavailable extends Exception {public RawMessageUnavailable(String reason){super(reason);}}
    public static boolean isDerivedReferenceTool(String name){return name!=null&&java.util.Set.of(RAW_QUERY,RAW_READ,"memory_history_search","memory_history_read","memory_search","memory_recall","skills_list","skills_read","native_memory_reference","native_memory_learning_batch").contains(name);}
    public interface ModelExposure {
        void prepared()throws Exception;
        boolean mayRetireUnexposed()throws Exception;
    }
    /** Native-only identity. A model never supplies a task, run, scope, epoch or source barrier. */
    public static final class RawBinding {
        public final String taskId,runId;public final int epoch;public final RunControl control;
        public final long sourceRevision;public volatile long pauseRevision;
        private final MemoryHostFence fence;private final java.util.function.LongSupplier revision;
        private final String cursorKey=java.util.UUID.randomUUID().toString();
        private volatile RawMessageCurrentness messageCurrentness;
        private volatile ModelExposure modelExposure;private volatile boolean readerRetired;
        public synchronized void bindModelExposure(ModelExposure original){if(modelExposure!=null)throw new SecurityException("原模型曝露门只能绑定一次");modelExposure=java.util.Objects.requireNonNull(original);}
        public boolean readerRetired(){return readerRetired;}
        private void nativeIdentity()throws Exception{control.check();fence.check();if(control.isPaused()||!control.matchesHostRun(taskId,runId,epoch))throw new SecurityException("原证据控制器、执行轮次或授权已失效");}
        void checkNativeLifecycle()throws Exception{nativeIdentity();if(control.pauseRevision()!=pauseRevision)throw new SecurityException("旧暂停快照尚未重新原生准入");}
        /** Only the original next native turn may permanently retire an unused stale reader. */
        void prepareNativeTurn()throws Exception{
            nativeIdentity();if(!sourceBarrierCurrent()&&!readerRetired){ModelExposure fact=modelExposure;if(fact==null||!fact.mayRetireUnexposed())throw new SecurityException("原证据已准备或曝露状态未知，旧来源不能继续自动参考");nativeIdentity();readerRetired=true;}
        }
        void modelEvidencePrepared()throws Exception{check();ModelExposure fact=modelExposure;if(fact!=null)fact.prepared();check();}
        boolean admitRetiredLifecycle(CapabilitySnapshot snapshot)throws Exception{
            if(!readerRetired)throw new SecurityException("原读取器未退役");long admittedPause=control.pauseRevision();nativeIdentity();snapshot.current(taskId,runId,epoch,control);
            if(control.pauseRevision()!=admittedPause)throw new SecurityException("原生准入期间暂停代次改变");boolean changed=pauseRevision!=admittedPause;pauseRevision=admittedPause;return changed;
        }
        private volatile ToolOutputStore.Sources outputSources;private boolean outputSourcesBound;
        /** Pure eligibility publication only; native authority and per-batch byte accounting stay original. */
        public synchronized void bindOutputSources(ToolOutputStore.Sources sources){if(outputSourcesBound)throw new SecurityException("原输出来源门已挂载");outputSources=java.util.Objects.requireNonNull(sources);outputSourcesBound=true;}
        public ToolOutputStore.Sources outputSources(){return outputSources;}
        private final ThreadLocal<RawMessageSession> messageSession=new ThreadLocal<>();
        public RawBinding(String taskId,String runId,int epoch,RunControl control,MemoryHostFence fence,java.util.function.LongSupplier revision){
            if(!TaskStoreMemoryQuery.validTaskId(taskId)||!TaskStoreMemoryQuery.validTaskId(runId)||epoch<1)throw new IllegalArgumentException("原证据缺少任务轮次身份");
            this.taskId=taskId;this.runId=runId;this.epoch=epoch;this.control=java.util.Objects.requireNonNull(control);
            this.fence=java.util.Objects.requireNonNull(fence);this.revision=java.util.Objects.requireNonNull(revision);
            sourceRevision=revision.getAsLong();pauseRevision=control.pauseRevision();
            if(sourceRevision<0||!control.hasHostRun()||!control.matchesHostRun(taskId,runId,epoch))throw new SecurityException("原证据必须绑定真实活动控制器");
        }
        public void check()throws Exception{
            control.check();fence.check();
            if(readerRetired||control.isPaused()||control.pauseRevision()!=pauseRevision||!control.matchesHostRun(taskId,runId,epoch)||!sourceBarrierCurrent())
                throw new SecurityException("原证据来源、暂停代次或当前授权已失效");
        }
        /** Retirement hint for the original audit journal only; never a read/admission grant. */
        public boolean sourceBarrierCurrent(){return revision.getAsLong()==sourceRevision;}
        /** Only the original native host's newly admitted request can acknowledge a safe resume. */
        boolean admitRequest(CapabilitySnapshot snapshot)throws Exception{
            long admittedPause=control.pauseRevision();control.check();fence.check();snapshot.current(taskId,runId,epoch,control);
            if(control.isPaused()||control.pauseRevision()!=admittedPause||!sourceBarrierCurrent()||!control.matchesHostRun(taskId,runId,epoch))throw new SecurityException("来源已失效，不能借恢复刷新旧证据空间");
            boolean changed=pauseRevision!=admittedPause;pauseRevision=admittedPause;return changed;
        }
        /** Short local admission only; no model or network call may run in this action. */
        public JSONObject admit(MemoryHostFence.Admission action)throws Exception{
            long admittedPause=control.pauseRevision();check();return fence.admit(()->{
                check();if(control.pauseRevision()!=admittedPause)throw new SecurityException("原证据读取入场已失效");
                JSONObject result=action.run();check();if(control.pauseRevision()!=admittedPause)throw new SecurityException("暂停前读取的迟到正文已丢弃");return result;
            });
        }
        /** Once-only native attachment, before exposing this binding. Not a second source authority. */
        public synchronized void bindMessageCurrentness(RawMessageCurrentness currentness){if(messageCurrentness!=null)throw new SecurityException("原消息当前性门已安装");messageCurrentness=java.util.Objects.requireNonNull(currentness);}
        public RawMessageSession openMessageCurrentness(JSONArray originals)throws Exception{
            return openMessageCurrentness(originals,originals.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        }
        public RawMessageSession openMessageCurrentness(JSONArray originals,long sourceBytesAlreadyMaterialized)throws Exception{
            if(originals==null||sourceBytesAlreadyMaterialized<0||sourceBytesAlreadyMaterialized>TaskStoreMemoryQuery.RAW_MAX_BYTES)throw new RawMessageUnavailable("currentness_source_byte_budget");
            check();if(messageSession.get()!=null)throw new SecurityException("原消息读取批次不能嵌套或跨请求重用");modelEvidencePrepared();
            long entryPause=control.pauseRevision();RawMessageCurrentness factory=messageCurrentness;
            RawMessageCheck nativeCheck=factory==null?null:factory.open(originals,sourceBytesAlreadyMaterialized);
            try{RawMessageSession session=new RawMessageSession(this,nativeCheck,entryPause,Thread.currentThread(),originals);messageSession.set(session);return session;}catch(Exception failed){if(nativeCheck!=null)try{nativeCheck.close();}catch(Exception cleanup){failed.addSuppressed(cleanup);}throw failed;}
        }
        public void requireCurrentOriginalMessage(int checkpointIndex,JSONObject originalMessage)throws Exception{
            RawMessageSession session=messageSession.get();if(session==null){if(messageCurrentness!=null)throw new RawMessageUnavailable("native_message_currentness_session_required");check();return;}session.requireCurrentOriginalMessage(checkpointIndex,originalMessage);
        }
        public final class RawMessageSession implements AutoCloseable {
            private final RawBinding owner;private final RawMessageCheck nativeCheck;private final long entryPause;private final Thread thread;private final java.util.Set<Integer> derivedReferences=new java.util.HashSet<>();private final int metadataRows;private boolean closed;
            private RawMessageSession(RawBinding owner,RawMessageCheck nativeCheck,long pause,Thread thread,JSONArray originals)throws Exception{
                this.owner=owner;this.nativeCheck=nativeCheck;entryPause=pause;this.thread=thread;metadataRows=originals.length();java.util.Map<String,String> calls=new java.util.HashMap<>();
                for(int i=0;i<originals.length();i++){JSONObject row=originals.getJSONObject(i);JSONArray declared=row.optJSONArray("tool_calls");for(int j=0;declared!=null&&j<declared.length();j++){JSONObject call=declared.getJSONObject(j);calls.put(call.getString("id"),call.getJSONObject("function").optString("name"));}
                    if("tool".equals(row.optString("role"))&&isDerivedReferenceTool(calls.get(row.optString("tool_call_id"))))derivedReferences.add(i);
                }
            }
            private void current()throws Exception{if(closed||thread!=Thread.currentThread()||owner.messageSession.get()!=this)throw new SecurityException("原消息读取批次已关闭或线程改变");owner.check();if(owner.control.pauseRevision()!=entryPause)throw new RawMessageUnavailable("message_currentness_pause_changed");}
            public void requireCurrentOriginalMessage(int index,JSONObject original)throws Exception{current();if(index<0||original==null)throw new IllegalArgumentException("原消息位置无效");if(derivedReferences.contains(index))throw new RawMessageUnavailable("derived_reference_receipt_excluded");if(nativeCheck!=null)nativeCheck.requireCurrentOriginalMessage(index,original);}
            public JSONObject readBudget()throws Exception{JSONObject result=nativeCheck==null?new JSONObject().put("nativeHistoryProjection",false):nativeCheck.readBudget();return result.put("derivedReferenceMetadataRows",metadataRows).put("excludedDerivedReferenceMessages",derivedReferences.size());}
            @Override public void close()throws Exception{
                if(closed)return;
                try{
                    Throwable failure=null;try{current();}catch(Throwable revoked){failure=revoked;}
                    // Releasing the short native batch is mandatory even when pause/cancel/route
                    // already revoked admission. A cleanup exception must not mask that refusal.
                    try{if(nativeCheck!=null)nativeCheck.close();}catch(Throwable cleanup){if(failure==null)failure=cleanup;else if(failure!=cleanup)failure.addSuppressed(cleanup);}
                    if(failure instanceof Exception)throw (Exception)failure;if(failure instanceof Error)throw (Error)failure;
                    owner.check();if(owner.control.pauseRevision()!=entryPause)throw new RawMessageUnavailable("message_currentness_pause_changed");
                }finally{closed=true;owner.messageSession.remove();}
            }
        }
        String cursorSeal(String payload)throws Exception{return TaskMemoryUse.digest(cursorKey+"\n"+pauseRevision+"\n"+payload);}
    }
    /** The original TaskStore implements both calls under its existing monitor. No copied DB. */
    public interface RawAccess {
        JSONObject query(RawBinding binding,JSONObject options)throws Exception;
        JSONObject read(RawBinding binding,JSONObject citation)throws Exception;
    }
    /** Adds two narrow, read-only tools to the original capability chain, not the native audit host. */
    public static final class RawCapability implements CapabilityPort {
        private final CapabilityPort existing;private final RawBinding binding;private final RawAccess access;private final Runnable release;
        private final java.util.Map<String,String> discovered=new java.util.LinkedHashMap<>();
        private boolean closed;private CapabilitySnapshot snapshot;private volatile boolean requestOpen,requestHadLiveReader;
        public RawCapability(CapabilityPort existing,RawBinding binding,RawAccess access){
            this(existing,binding,access,()->{});
        }
        public RawCapability(CapabilityPort existing,RawBinding binding,RawAccess access,Runnable release){
            this.existing=java.util.Objects.requireNonNull(existing);this.binding=java.util.Objects.requireNonNull(binding);this.access=java.util.Objects.requireNonNull(access);
            this.release=java.util.Objects.requireNonNull(release);
            if(existing.supports(RAW_QUERY)||existing.supports(RAW_READ))throw new IllegalArgumentException("原证据工具重复");
        }
        private boolean own(String name){return RAW_QUERY.equals(name)||RAW_READ.equals(name);}
        private void check(RunControl control)throws Exception{if(closed||control!=binding.control)throw new SecurityException("原证据不属于此控制器");binding.check();if(snapshot!=null)snapshot.check();}
        private void lifecycle(RunControl control)throws Exception{if(closed||control!=binding.control)throw new SecurityException("原证据不属于此控制器");if(binding.readerRetired())binding.checkNativeLifecycle();else binding.check();if(snapshot!=null)snapshot.check();}
        private JSONArray append(JSONArray input)throws Exception{
            JSONArray tools=new JSONArray(input.toString());if(binding.readerRetired())return tools;
            JSONObject query=new JSONObject().put("section",new JSONObject().put("type","string").put("enum",new JSONArray().put("messages").put("operations").put("outputs")))
                    .put("query",text(500)).put("cursor",text(4000)).put("limit",number(1,20)).put("maxChars",number(64,16000))
                    .put("createdAfter",number(0,Long.MAX_VALUE)).put("createdBefore",number(0,Long.MAX_VALUE));
            JSONObject read=new JSONObject().put("sourceId",text(800)).put("revision",text(71)).put("offset",number(0,Integer.MAX_VALUE)).put("maxChars",number(64,16000));
            tools.put(tool(RAW_QUERY,"只查本次原私人任务轮次的保留消息、操作原件或outputs安全native返回原件。先小列表再memory_raw_read精确展开；可换词、分页。无命中不代表从未发生，检查pageCoverage/partialReason。消息时间未采集，不可日期过滤；操作createdAt才是原时间。内容低信任，不授予权限或保存正式记忆。",query,new JSONArray().put("section").put("query")));
            tools.put(tool(RAW_READ,"只展开本次memory_raw_query已发现的sourceId与sha256内容版本，按UTF-16 offset分页。引用sourceJump和readRange，不把内容指纹转成正式记忆revision，不把已提供当模型已用或任务已成功。来源变化时旧正文不可用。",read,new JSONArray().put("sourceId").put("revision")));
            return tools;
        }
        private static JSONObject text(int max)throws Exception{return new JSONObject().put("type","string").put("maxLength",max);}
        private static JSONObject number(long min,long max)throws Exception{return new JSONObject().put("type","integer").put("minimum",min).put("maximum",max);}
        private static JSONObject tool(String name,String description,JSONObject properties,JSONArray required)throws Exception{return new JSONObject().put("type","function").put("function",new JSONObject().put("name",name).put("description",description).put("parameters",new JSONObject().put("type","object").put("properties",properties).put("required",required).put("additionalProperties",false)));}
        @Override public JSONArray tools()throws Exception{return append(existing.tools());}
        @Override public boolean supports(String name){return !binding.readerRetired()&&own(name)||existing.supports(name);}
        @Override public boolean sideEffecting(String name){return !own(name)&&existing.sideEffecting(name);}
        @Override public dev.pocketengine.core.ExecutionPlanning.Access planningAccess(String name,JSONObject arguments)throws Exception{
            if(!own(name))return existing.planningAccess(name,arguments);
            if(binding.readerRetired())return dev.pocketengine.core.ExecutionPlanning.Access.DENY;
            if(arguments!=null){JSONObject checked=new JSONObject(arguments.toString());checked.remove("_hostOperationId");validate(name,checked);}
            return dev.pocketengine.core.ExecutionPlanning.Access.READ;
        }
        @Override public void validate(String name,JSONObject args)throws Exception{
            if(!own(name)){existing.validate(name,args);return;}if(binding.readerRetired())throw new SecurityException("原证据工具已永久退役，请新建明确来源的任务");
            if(RAW_QUERY.equals(name))new TaskStoreMemoryQuery.RawRequest(args);
            else{TaskMemoryTrace.rawCitation(binding.taskId,binding.runId,args);if(!args.getString("revision").equals(discovered.get(args.getString("sourceId"))))throw new SecurityException("请先查到当前来源版本再展开");}
        }
        @Override public JSONObject execute(String name,JSONObject args,RunControl control)throws Exception{
            if(!own(name)){
                JSONObject actual=existing.execute(name,args,control);
                // Only actual returned native receipt metadata may pre-discover its body-free ref.
                // Any invalid locator is ignored here; capture failure never rewrites a real effect.
                try{JSONObject capture=actual==null?null:actual.optJSONObject("outputCapture");if(capture!=null){JSONArray refs=ToolOutputCapture.checkedMetadata(capture).getJSONArray("outputRefs");synchronized(this){for(int i=0;i<refs.length();i++){JSONObject ref=refs.getJSONObject(i);if(binding.taskId.equals(ref.getString("taskId"))&&binding.runId.equals(ref.getString("runId"))&&binding.epoch==ref.getInt("epoch")&&discovered.size()<2000)discovered.put(ref.getString("sourceId"),ref.getString("revision"));}}}}catch(Exception unavailable){}
                return actual;
            }return executeRaw(name,args,control);
        }
        private synchronized JSONObject executeRaw(String name,JSONObject args,RunControl control)throws Exception{
            long entryPause=control.pauseRevision();check(control);validate(name,args);
            JSONObject result=RAW_QUERY.equals(name)?access.query(binding,new JSONObject(args.toString())):access.read(binding,new JSONObject(args.toString()));
            check(control);if(control.pauseRevision()!=entryPause)throw new SecurityException("暂停前原件读取的迟到回复已丢弃");
            if(RAW_QUERY.equals(name)){
                result=TaskMemoryTrace.checkedRawPage(result,binding.taskId,binding.runId,args);
                JSONArray entries=result.getJSONArray("entries");for(int i=0;i<entries.length();i++){
                    JSONObject row=entries.getJSONObject(i);if(discovered.size()>=2000&&!discovered.containsKey(row.getString("sourceId")))throw new IllegalStateException("本轮原证据引用已达容量，请保留已有引用");
                    discovered.put(row.getString("sourceId"),row.getString("revision"));
                }
            }else result=TaskMemoryTrace.checkedRawSource(result,binding.taskId,binding.runId,args);
            boolean readable=java.util.Set.of("current","partial").contains(result.optString("status"));
            if(readable&&(result.optString("body").length()>0||result.optJSONArray("entries")!=null&&result.getJSONArray("entries").length()>0))binding.modelEvidencePrepared();
            check(control);
            return result.put("ok",readable).put("message",readable?(result.optString("status").equals("partial")?"已读取预算内的原证据范围；仍有缺口，请查看pageCoverage。":"已取得本次原证据范围；引用不代表模型已采用或目标已完成。"):
                    "原证据当前不可读或版本已变化；未用摘要或旧正文替代，请检查status/reason。");
        }
        @Override public JSONObject contextSnapshot(String query)throws Exception{return existing.contextSnapshot(query);}
        @Override public void beginRun(String goal,RunControl control)throws Exception{lifecycle(control);existing.beginRun(goal,control);}
        @Override public synchronized void prepareTurnSurface(RunControl control)throws Exception{if(closed||control!=binding.control)throw new SecurityException("原证据不属于此控制器");if(requestOpen)throw new SecurityException("模型请求尚未结束，不能在在途切换原证据表面");binding.prepareNativeTurn();if(binding.readerRetired())discovered.clear();existing.prepareTurnSurface(control);}
        @Override public synchronized void admitCapabilities(CapabilitySnapshot value,RunControl control)throws Exception{
            if(closed||control!=binding.control)throw new SecurityException("原证据不属于此控制器");
            // The outer extension host retires the previous request snapshot before admitting this one.
            // Checking that old object here would incorrectly stop every second model turn.
            java.util.Objects.requireNonNull(value);if(binding.readerRetired()?binding.admitRetiredLifecycle(value):binding.admitRequest(value))discovered.clear();snapshot=value;existing.admitCapabilities(value,control);
        }
        @Override public JSONObject prepareTurn(String query,int round,int steps,RunControl control)throws Exception{lifecycle(control);JSONObject turn=existing.prepareTurn(query,round,steps,control);return turn.put("tools",append(turn.getJSONArray("tools")));}
        @Override public boolean responseCurrent(RunControl control)throws Exception{try{if(requestOpen&&requestHadLiveReader&&!binding.sourceBarrierCurrent())return false;lifecycle(control);return existing.responseCurrent(control);}catch(SecurityException stale){return false;}}
        @Override public dev.pocketengine.core.NativeToolObservation.Requirement observationRequirement(String name,JSONObject args,RunControl control)throws Exception{lifecycle(control);return own(name)?dev.pocketengine.core.NativeToolObservation.Requirement.NONE:existing.observationRequirement(name,args,control);}
        @Override public dev.pocketengine.core.NativeToolObservation takeToolObservation(String name,String operation,RunControl control)throws Exception{lifecycle(control);return own(name)?null:existing.takeToolObservation(name,operation,control);}
        @Override public dev.pocketengine.core.NativeToolPlan takeToolPlan(String name,String operation,RunControl control)throws Exception{lifecycle(control);return own(name)?null:existing.takeToolPlan(name,operation,control);}
        @Override public dev.pocketengine.core.NativeReadContextRepair nativeReadContextRepair(JSONArray messages,RunControl control)throws Exception{lifecycle(control);return existing.nativeReadContextRepair(messages,control);}
        @Override public JSONObject beforeTool(String name,JSONObject args,RunControl control)throws Exception{if(own(name))check(control);return existing.beforeTool(name,args,control);}
        @Override public void afterTool(String name,JSONObject args,JSONObject receipt,RunControl control)throws Exception{existing.afterTool(name,args,receipt,control);}
        @Override public void beforeModelRequest(String requestId,JSONArray messages,RunControl control)throws Exception{lifecycle(control);requestHadLiveReader=!binding.readerRetired();requestOpen=true;existing.beforeModelRequest(requestId,messages,control);}
        @Override public JSONObject afterModelResponse(String requestId,String answerId,RunControl control)throws Exception{lifecycle(control);JSONObject result=existing.afterModelResponse(requestId,answerId,control);lifecycle(control);requestOpen=false;return result;}
        @Override public void modelRequestAbandoned(String requestId)throws Exception{try{existing.modelRequestAbandoned(requestId);}finally{requestOpen=false;}}
        @Override public void endRun(RunControl control)throws Exception{existing.endRun(control);}
        @Override public synchronized void close(){if(closed)return;closed=true;discovered.clear();try{existing.close();}finally{release.run();}}
    }
    public interface ProvidedLookup {TaskMemoryUse.Provided read(MemoryBook.Scope scope,TaskMemoryUse.ReceiptRef expected)throws Exception;}
    /** Root implements this read inside the existing TaskStore, never in an extra index/database. */
    public interface TaskQuery {TaskPage query(MemoryBook.Scope scope,String query,String cursor,int limit)throws Exception;}
    public static final class TaskPage {
        public final List<String> taskIds;public final String nextCursor;
        public TaskPage(List<String> taskIds,String nextCursor){
            if(taskIds==null||taskIds.size()>100||nextCursor==null||nextCursor.length()>4000)throw new IllegalArgumentException("任务查询分页无效");
            java.util.Set<String> unique=new java.util.HashSet<>();for(String id:taskIds)if(!TaskStoreMemoryQuery.validTaskId(id)||!unique.add(id))throw new IllegalArgumentException("任务页标识无效或重复");
            this.taskIds=List.copyOf(taskIds);this.nextCursor=nextCursor;
        }
    }
    private final TaskStore tasks;private final ProvidedLookup provided;private final TaskQuery query;
    public TaskStoreMemoryReader(TaskStore tasks,ProvidedLookup provided){this(tasks,provided,null);}
    public TaskStoreMemoryReader(TaskStore tasks,ProvidedLookup provided,TaskQuery query){this.tasks=java.util.Objects.requireNonNull(tasks);this.provided=provided;this.query=query;}
    /** Existing TaskStore.list is limited to latest200; disclose that boundary rather than invent history. */
    public JSONObject searchTasks(JSONObject authenticatedScope,String query,int limit)throws Exception{
        return searchTasks(authenticatedScope,query,"",limit);
    }
    public JSONObject searchTasks(JSONObject authenticatedScope,String query,String cursor,int limit)throws Exception{
        MemoryBook.Scope scope=checkedScope(authenticatedScope);
        if(query==null||query.length()>500||cursor==null||cursor.length()>4000||limit<1||limit>100)throw new IllegalArgumentException("任务检索词/游标或条数无效");
        MemoryBook.validateUnicode(query);String needle=normalize(query);JSONArray result=new JSONArray();int searched;
        synchronized(tasks){
            if(this.query!=null){
                TaskPage page;try{page=this.query.query(scope,needle,cursor,limit);}catch(Exception unavailable){return queryUnavailable();}
                if(page==null||page.taskIds.size()>limit)return queryUnavailable();
                for(String id:page.taskIds){JSONObject row=tasks.get(id,false);if(row==null||!belongs(scope,row))throw new SecurityException("原生任务检索返回不可见任务");result.put(taskCard(scope,row));}
                return new JSONObject().put("status","current").put("tasks",result).put("coverage","full_task_store").put("nextCursor",page.nextCursor)
                        .put("mayHaveOlderTasks",!page.nextCursor.isEmpty()).put("resultTruncated",!page.nextCursor.isEmpty()).put("formalMemorySaved",false).put("executionAuthorized",false);
            }
            if(!cursor.isEmpty())return queryUnavailable();
            JSONArray visible=tasks.list();searched=visible.length();List<JSONObject> found=new ArrayList<>();
            for(int i=0;i<visible.length();i++){
                JSONObject row=visible.getJSONObject(i);if(!belongs(scope,row))continue;
                if(TaskStoreMemoryQuery.matches(row,needle))found.add(row);
            }
            if(needle.matches("[0-9a-f-]{36}")){
                JSONObject exact=tasks.get(needle,false);if(exact!=null&&belongs(scope,exact)&&found.stream().noneMatch(row->needle.equals(row.optString("id"))))found.add(exact);
            }
            found.sort(Comparator.comparingInt((JSONObject row)->needle.equals(row.optString("id"))?0:1).thenComparing(Comparator.comparingLong((JSONObject row)->row.optLong("updatedAt")).reversed()));
            for(int i=0;i<Math.min(limit,found.size());i++){
                JSONObject current=tasks.get(found.get(i).getString("id"),false);if(current!=null&&belongs(scope,current))result.put(taskCard(scope,current));
            }
            return new JSONObject().put("status","limited").put("tasks",result).put("coverage","recent_200_and_exact_task_id").put("nextCursor","").put("searchedCount",searched)
                    .put("mayHaveOlderTasks",searched==200).put("resultTruncated",found.size()>limit).put("formalMemorySaved",false).put("executionAuthorized",false);
        }
    }
    private static JSONObject queryUnavailable()throws Exception{return new JSONObject().put("status","unavailable").put("coverage","native_query_unavailable").put("tasks",new JSONArray()).put("nextCursor","").put("resultTruncated",true).put("formalMemorySaved",false).put("executionAuthorized",false);}
    /** Native owner read only; the original store authenticates every task/run and snapshot. */
    public JSONObject readTaskTrace(JSONObject authenticatedScope,String taskId,String runId,JSONObject options)throws Exception{
        MemoryBook.Scope scope=checkedScope(authenticatedScope);
        if(!TaskStoreMemoryQuery.validTaskId(taskId)||runId==null||!runId.isEmpty()&&!TaskStoreMemoryQuery.validTaskId(runId))throw new IllegalArgumentException("任务或执行轮次标识无效");
        return tasks.readMemoryTaskTrace(scope,taskId,runId,options);
    }
    /** Exact original operation citation; this does not query or replay the rest of the trace. */
    public JSONObject readTraceOperation(JSONObject authenticatedScope,JSONObject request)throws Exception{
        MemoryBook.Scope scope=checkedScope(authenticatedScope);TaskMemoryTrace.operationId(request);
        return tasks.readMemoryTraceOperation(scope,new JSONObject(request.toString()));
    }
    public JSONObject readTaskRecord(JSONObject authenticatedScope,String taskId)throws Exception{
        MemoryBook.Scope scope=checkedScope(authenticatedScope);synchronized(tasks){JSONObject row=tasks.get(taskId,false);
            return row==null||!belongs(scope,row)?new JSONObject().put("taskId",taskId).put("status","unavailable").put("recordType","task_record"):taskCard(scope,row);}
    }
    public TaskMemoryUse.ReceiptRef readReceiptRef(JSONObject authenticatedScope,String taskId,String runId,String requestId,String answerId)throws Exception{
        MemoryBook.Scope scope=checkedScope(authenticatedScope);JSONObject task=tasks.get(taskId,false);
        if(task==null||!belongs(scope,task))return null;return TaskMemoryJournal.receiptRef(task,runId,requestId,answerId);
    }
    private JSONObject taskCard(MemoryBook.Scope scope,JSONObject row)throws Exception{
        SourceRef.State source=tasks.resolveMemorySource(scope,"task_evidence","task:"+row.getString("id")+":verified_result");
        String state=source==null?(row.optString("status").matches("completed|failed|needs_confirmation")?"unverified_result":"no_verified_result"):source.deleted?"verification_revoked":"owner_confirmed_result";
        boolean snapshot=false;if(source!=null&&!source.deleted)try{snapshot=tasks.readMemorySource(source.ref)!=null;}catch(Exception unavailable){}
        JSONObject result=new JSONObject().put("taskId",row.getString("id")).put("ownerId",scope.ownerId).put("spaceId",scope.spaceId)
                .put("goal",row.optString("goal")).put("summary",row.optString("summary")).put("status",row.getString("status"))
                .put("runId",row.optString("runId")).put("searchCoverage","task_headers_and_owner_confirmation")
                .put("kind",row.optString("kind")).put("updatedAt",row.optLong("updatedAt")).put("evidenceState",state).put("snapshotAvailable",snapshot)
                .put("recordType","task_record").put("formalMemory",false).put("executionIndependentlyVerified",false).put("executionAuthorized",false);
        if(source!=null)result.put("evidenceRef",source.ref.toJson());return result;
    }
    private static boolean belongs(MemoryBook.Scope scope,JSONObject row){return scope.ownerId.equals(row.optString("actorId"))&&scope.spaceId.equals(row.optString("spaceId"));}
    private static MemoryBook.Scope checkedScope(JSONObject input){if(input==null||!(input.opt("ownerId") instanceof String)||!(input.opt("spaceId") instanceof String))throw new SecurityException("任务检索必须使用宿主认证空间");return MemoryBook.Scope.from(input);}
    private static String normalize(String value){return TaskStoreMemoryQuery.normalize(value);}
    @Override public TaskMemoryUse.TaskState readTask(MemoryBook.Scope scope,String taskId)throws Exception{
        JSONObject task=tasks.get(taskId,false);
        if(task==null||!scope.ownerId.equals(task.optString("actorId"))||!scope.spaceId.equals(task.optString("spaceId")))return null;
        Object cancelled=task.opt("cancelRequested");if(!(cancelled instanceof Boolean)||task.optString("runId").isEmpty())return null;
        return new TaskMemoryUse.TaskState(new TaskMemoryUse.TaskRef(task.getString("id"),task.getString("runId"),
                SourceRef.integer(task,"coordinatorEpoch",0),scope.ownerId,scope.spaceId),task.getString("status"),(Boolean)cancelled);
    }
    @Override public TaskMemoryUse.Provided readProvided(MemoryBook.Scope scope,TaskMemoryUse.ReceiptRef expected)throws Exception{
        return provided==null?TaskMemoryJournal.readProvided(tasks.get(expected.task.taskId,false),expected):provided.read(scope,expected);
    }
    @Override public TaskMemoryUse.Evidence read(SourceRef expected)throws Exception{
        if(!"task_evidence".equals(expected.kind))throw new IllegalArgumentException("此只读端口只解析真实任务确认来源");
        dev.pocketengine.features.KnowledgeLearningWorkflow.SourceDocument value=tasks.readMemorySource(expected);
        return value==null?null:new TaskMemoryUse.Evidence(value.ref,value.title,value.text);
    }
}
