package dev.pocketengine.store;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.AtomicFile;
import android.util.Base64;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.core.RunJournal;
import dev.pocketengine.runtime.usage.InferenceCapture;
import dev.pocketengine.runtime.usage.InferenceReceipt;
import dev.pocketengine.runtime.usage.InferenceProjection;
import dev.pocketengine.features.groups.GroupHostPort;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.runtime.context.CompactionJournal;
import dev.pocketengine.runtime.context.CompactionPlan;
import dev.pocketengine.runtime.context.CompactionCheckpoint;
import dev.pocketengine.runtime.context.SummaryDraft;
import dev.pocketengine.features.memory.TaskStoreMemoryReader;
import dev.pocketengine.features.memory.TaskMemoryTrace;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
import java.util.Objects;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** The host's transactional Task/Run/Operation authority. Executors keep receipts, never a second task truth. */
public final class TaskStore {
    private final SQLiteDatabase db;
    private final File artifacts;
    private final dev.pocketengine.features.assistant.ConversationRepository conversationRepository;
    public TaskStore(Context context) {
        artifacts=new File(context.getFilesDir(),"artifacts");if(!artifacts.exists())artifacts.mkdirs();
        Database database=new Database(context);db=database.getWritableDatabase();
        try{importLegacy(new AtomicFile(new File(context.getFilesDir(),"tasks.json")));TaskAuditStore.seedSequenceFloors(db);recover();}
        catch(Exception failure){throw new IllegalStateException("任务数据库恢复失败，原始数据未删除",failure);}
        conversationRepository=new dev.pocketengine.features.assistant.ConversationRepository(db,this);
        bindToolOutputSourceBook(dev.pocketengine.features.attachments.AttachmentRepository.get(context).book);
    }
    /** Singleton original Book read-only publication; never call its monitor while Tasks is held. */
    private dev.pocketengine.features.attachments.AttachmentBook outputBook;
    private dev.pocketengine.features.memory.ToolOutputStore.Sources outputSources;
    public synchronized void bindToolOutputSourceBook(dev.pocketengine.features.attachments.AttachmentBook original){
        if(outputBook!=null&&outputBook!=original)throw new SecurityException("原附件输出来源Book不能替换");if(outputBook!=null)return;outputBook=java.util.Objects.requireNonNull(original);
        outputSources=new dev.pocketengine.features.memory.ToolOutputStore.Sources(){
            public String attachmentGeneration(String id,String sha){var known=original.outputSourceEligibility(id,sha);return known==null?null:known.generation;}
            public boolean attachmentCurrent(String id,String sha,String generation){return original.outputSourceCurrent(id,sha,generation);}
        };
    }
    public synchronized dev.pocketengine.features.memory.ToolOutputStore.Sources toolOutputSources(){return outputSources;}
    private static boolean acceptsPrivateOutputTask(JSONObject task){if(task==null||!"owner".equals(task.optString("actorId"))||!"private".equals(task.optString("spaceId")))return false;JSONObject origin=task.optJSONObject("origin");return origin==null||!java.util.Set.of("channel","group").contains(origin.optString("kind"));}
    private static void requireOutputTask(JSONObject task){if(!acceptsPrivateOutputTask(task))throw new SecurityException("原输出存证不跨群或渠道来源域");}
    public synchronized void requireToolOutputSources(String id,String run,int epoch,RunControl original)throws Exception{
        original.check();if(!original.matchesHostRun(id,run,epoch))throw new SecurityException("原输出不是当前native控制器");fence(id,run,epoch,original,false);requireOutputTask(required(id));
        try{dev.pocketengine.features.memory.TaskStoreMemoryQuery.requireOutputContext(db,outputSources,id,run);}catch(Exception unavailable){original.check();throw new dev.pocketengine.features.memory.ToolOutputCapture.OutputSourceUnavailable(unavailable);}original.check();
    }
    /** Same original native binding, a body-free run fact only; not a replacement reader or grant. */
    public synchronized void bindRawModelExposure(TaskStoreMemoryReader.RawBinding binding)throws Exception{
        if(binding.outputSources()!=outputSources)throw new SecurityException("曝露门必须属于原输出来源绑定");
        binding.control.check();fence(binding.taskId,binding.runId,binding.epoch,binding.control,false);requireOutputTask(required(binding.taskId));
        binding.bindModelExposure(new TaskStoreMemoryReader.ModelExposure(){
            public void prepared()throws Exception{synchronized(TaskStore.this){
                binding.control.check();fence(binding.taskId,binding.runId,binding.epoch,binding.control,false);requireOutputTask(required(binding.taskId));
                db.beginTransaction();try{dev.pocketengine.features.memory.OwnerSourceRunContext.rawEvidencePrepared(db,binding.taskId,binding.runId);binding.control.check();db.setTransactionSuccessful();}finally{db.endTransaction();}
            }}
            public boolean mayRetireUnexposed()throws Exception{synchronized(TaskStore.this){
                binding.control.check();fence(binding.taskId,binding.runId,binding.epoch,binding.control,false);requireOutputTask(required(binding.taskId));
                requireToolOutputSources(binding.taskId,binding.runId,binding.epoch,binding.control);
                try{return dev.pocketengine.features.memory.OwnerSourceRunContext.mayRetireUnexposedReader(db,outputSources,binding.taskId,binding.runId);}catch(TaskStoreMemoryReader.RawMessageUnavailable unknown){return false;}
            }}
        });
    }
    /** Explicit cold admission on the same retained run, before creating any new attempt. No list,
     * metadata-only read, changed generation or new grant can promote a source to eligible. */
    public void warmToolOutputSources(String id,RunControl original)throws Exception{
        if(Thread.holdsLock(this))throw new SecurityException("不能持Tasks锁进入附件Book完整字节核验");java.util.Objects.requireNonNull(original).check();
        String run;int epoch;JSONArray dependencies;dev.pocketengine.features.attachments.AttachmentBook book;
        synchronized(this){JSONObject task=required(id);if(!acceptsPrivateOutputTask(task))return;run=task.optString("runId");epoch=task.optInt("coordinatorEpoch");if(run.isEmpty())return;
            if(original.hasHostRun()&&!original.matchesHostRun(id,run,epoch))throw new SecurityException("不能替换原输出run");book=outputBook;
            try{dependencies=dev.pocketengine.features.memory.TaskStoreMemoryQuery.outputRunDependencies(db,id,run);}catch(Exception unavailable){original.check();throw new dev.pocketengine.features.memory.ToolOutputCapture.OutputSourceUnavailable(unavailable);}
        }
        try{
            long remaining=dev.pocketengine.features.attachments.AttachmentBook.MAX_TOTAL_BYTES;
            for(int i=0;i<dependencies.length();i++){original.check();JSONObject source=dependencies.getJSONObject(i);if(book==null||source.isNull("generation"))throw new SecurityException("原附件来源代次无法核对");String item=source.getString("id"),sha=source.getString("sha256"),generation=source.getString("generation");
                if(!book.outputSourceCurrent(item,sha,generation))remaining-=book.revalidateOutputSource(item,sha,generation,remaining,original);
            }
            synchronized(this){JSONObject current=required(id);requireOutputTask(current);if(!run.equals(current.optString("runId"))||epoch!=current.optInt("coordinatorEpoch")||!canonical(dependencies).equals(canonical(dev.pocketengine.features.memory.TaskStoreMemoryQuery.outputRunDependencies(db,id,run))))throw new SecurityException("核验期间原执行或依赖已变化");
                dev.pocketengine.features.memory.TaskStoreMemoryQuery.requireOutputContext(db,outputSources,id,run);original.check();}
        }catch(Exception unavailable){original.check();throw new dev.pocketengine.features.memory.ToolOutputCapture.OutputSourceUnavailable(unavailable);}
    }
    /** Process-local factory captures the original inference cleaner and exact tuple, not settings.
     * An already-issued call may retain real audit bytes after cancellation; it grants no new read. */
    public dev.pocketengine.features.memory.ToolOutputCapture.Factory toolOutputFactory(String id,String run,int epoch,RunControl original,dev.pocketengine.features.memory.ToolOutputCapture.TextCleaner cleaner){
        java.util.Objects.requireNonNull(original);java.util.Objects.requireNonNull(cleaner);
        return new dev.pocketengine.features.memory.ToolOutputCapture.Factory(){
            public void checkSources(RunControl live)throws Exception{if(live!=original)throw new SecurityException("不能替换原输出控制器");live.check();warmToolOutputSources(id,live);requireToolOutputSources(id,run,epoch,live);}
            public dev.pocketengine.features.memory.ToolOutputCapture.Claim begin(String tool,String prepared,dev.pocketengine.features.memory.ToolOutputCapture.Kind kind,RunControl live)throws Exception{
                if(live!=original)throw new SecurityException("不能替换原输出控制器");checkSources(live);
                if((prepared==null||prepared.isEmpty())&&!java.util.Set.of("attachments_read_document").contains(tool))throw new SecurityException("写入/MCP输出必须有原prepared operation；未声明的read-call producer尚未启用");
                return beginCapture(tool,prepared,kind,null,live);
            }
            @Override public dev.pocketengine.features.memory.ToolOutputCapture.Claim beginRead(dev.pocketengine.features.memory.ToolOutputCapture.ReadCall read,RunControl live)throws Exception{
                if(live!=original||read==null)throw new SecurityException("原native只读调用缺失或控制器不同");read.requireOriginal(id,run,epoch,live);checkSources(live);
                return beginCapture(read.tool.tool,"",dev.pocketengine.features.memory.ToolOutputCapture.Kind.NATIVE_RESULT_DATA_JSON,read,live);
            }
            private dev.pocketengine.features.memory.ToolOutputCapture.Claim beginCapture(String tool,String prepared,dev.pocketengine.features.memory.ToolOutputCapture.Kind kind,dev.pocketengine.features.memory.ToolOutputCapture.ReadCall read,RunControl live)throws Exception{
                final dev.pocketengine.features.memory.ToolOutputStore.Call call;final boolean reserve=read!=null||"attachments_read_document".equals(tool);
                synchronized(TaskStore.this){db.beginTransaction();try{fence(id,run,epoch,original,false);checkCapability(id,run,epoch,tool,original);requireOutputTask(required(id));
                    if(read!=null){
                        read.requireOriginal(id,run,epoch,original);JSONObject task=required(id),selected=task.optJSONObject("developmentContext");
                        // This additive producer does not broaden team/channel/source audiences.
                        // Unsupported native contexts keep their original read without new capture.
                        if(!"assistant".equals(task.optString("kind"))||task.optJSONObject("origin")!=null||selected==null)return null;
                        if(!read.projectId.equals(selected.optString("projectId"))||!read.checkoutId.equals(selected.optString("checkoutId")))throw new SecurityException("只读来源不属于原私人任务选定的项目副本");
                    }
                    if(prepared!=null&&!prepared.isEmpty())try(Cursor row=db.rawQuery("SELECT 1 FROM operations WHERE operation_id=? AND task_id=? AND run_id=? AND tool=? AND state='prepared' LIMIT 1",new String[]{prepared,id,run,tool})){if(!row.moveToFirst())throw new SecurityException("原输出没有本次实际prepared operation");}
                    call=dev.pocketengine.features.memory.ToolOutputStore.issue(TaskStore.this,db,id,run,epoch,tool,prepared,kind);
                    if(reserve)dev.pocketengine.features.memory.OwnerSourceRunContext.reserveOutput(db,id,run,call.output);original.check();db.setTransactionSuccessful();
                }catch(Exception refused){original.check();if(reserve)throw new dev.pocketengine.features.memory.ToolOutputCapture.OutputSourceUnavailable(refused);throw refused;}finally{db.endTransaction();}}
                return new dev.pocketengine.features.memory.ToolOutputCapture.Claim(){
                    private boolean finished;
                    public synchronized void notDispatched()throws Exception{if(finished)return;synchronized(TaskStore.this){db.beginTransaction();try{if(reserve)dev.pocketengine.features.memory.OwnerSourceRunContext.outputNotDispatched(db,id,run,call.output);db.setTransactionSuccessful();finished=true;}finally{db.endTransaction();}}}
                    public synchronized dev.pocketengine.features.memory.ToolOutputCapture.Result record(JSONObject safeData,Boolean upstream)throws Exception{
                        if(finished)throw new SecurityException("原输出claim已经结束");
                        if(read!=null){if(safeData==null)throw new SecurityException("只读原输出为空");safeData=new JSONObject().put("nativeReadCall",read.metadata()).put("data",new JSONObject(safeData.toString()));}
                        final JSONArray deps=dev.pocketengine.features.memory.ToolOutputStore.dependencies(call,safeData,outputSources);
                        synchronized(TaskStore.this){db.beginTransaction();try{
                            // Check the original stored run, not a possibly newer active epoch. No
                            // current grant is minted and no completed/cancelled effect is rewritten.
                            try(Cursor actual=db.rawQuery("SELECT 1 FROM runs WHERE task_id=? AND run_id=? AND epoch=? LIMIT 1",new String[]{id,run,Integer.toString(epoch)})){if(!actual.moveToFirst())throw new SecurityException("原输出run原件已不存在");}
                            requireOutputTask(required(id));dev.pocketengine.features.memory.OwnerSourceRunContext.outputReturned(db,id,run,call.output,deps,reserve);db.setTransactionSuccessful();finished=true;
                        }finally{db.endTransaction();}}
                        // Expensive cleaning/serialization is outside Tasks. A failure leaves the
                        // dependable sources already recorded, not a false effect/rollback claim.
                        var encoded=dev.pocketengine.features.memory.ToolOutputStore.encode(safeData,upstream,cleaner,deps);
                        synchronized(TaskStore.this){db.beginTransaction();try{requireOutputTask(required(id));var saved=dev.pocketengine.features.memory.ToolOutputStore.save(db,TaskStore.this,call,encoded);db.setTransactionSuccessful();return saved;}finally{db.endTransaction();}}
                    }
                };
            }
        };
    }
    /** Native facade/repository on this same original connection, never an EngineModule tool. */
    public dev.pocketengine.features.assistant.ConversationRepository conversationRepository(){return conversationRepository;}
    /** Body-free discovery index only; the original task document remains learning-job authority. */
    public synchronized JSONObject createSourceLearningJob(JSONArray nativeInputs)throws Exception{
        JSONArray inputs=dev.pocketengine.features.memory.SourceLearningJob.checkedInputs(nativeInputs);db.beginTransaction();try{
            JSONObject task=createCapabilityTask("逐批提炼主人选择的原始来源，生成待审候选（不自动采用）","");String id=task.getString("id");task=required(id);
            task.put("sourceLearningJob",new JSONObject().put("format",dev.pocketengine.features.memory.SourceLearningJob.FORMAT).put("revision",1L).put("inputs",inputs).put("manifestHash",capabilityFingerprint(new JSONObject().put("inputs",inputs))).put("nextIndex",0).put("results",new JSONArray()).put("reservedModelAttempts",0).put("started",false)).put("status","queued").put("summary","原始来源已选；等待主人开始有限批次，尚未请求模型");
            appendEvent(task,new JSONObject().put("type","memory_owner_consolidation").put("mode","source_learning_job").put("formalMemorySaved",false));
            ContentValues index=new ContentValues();index.put("key",String.format(java.util.Locale.ROOT,"memory_learning_job:%020d:%s",System.currentTimeMillis(),id));index.put("value",id);db.insertOrThrow("meta",null,index);db.setTransactionSuccessful();return sourceLearningJob(id);
        }finally{db.endTransaction();}
    }
    private JSONObject sourceLearningTask(String id)throws Exception{
        try(Cursor size=db.rawQuery("SELECT length(CAST(document AS BLOB)) FROM tasks WHERE task_id=? LIMIT 1",new String[]{id})){if(!size.moveToFirst()||size.getLong(0)>1_048_576)throw new IllegalStateException("原学习任务缺失或超过本次读取预算");}
        JSONObject task=required(id);requirePrivateOwner(task);JSONObject job=task.optJSONObject("sourceLearningJob");
        if(!"assistant".equals(task.optString("kind"))||task.has("origin")||job==null||!dev.pocketengine.features.memory.SourceLearningJob.FORMAT.equals(job.optString("format")))throw new SecurityException("不是原私人来源学习任务");
        JSONArray inputs=dev.pocketengine.features.memory.SourceLearningJob.checkedInputs(job.getJSONArray("inputs"));if(!capabilityFingerprint(new JSONObject().put("inputs",inputs)).equals(job.optString("manifestHash")))throw new SecurityException("原学习来源清单已变化");return task;
    }
    public synchronized JSONObject sourceLearningJob(String id)throws Exception{
        JSONObject task=sourceLearningTask(id),job=new JSONObject(task.getJSONObject("sourceLearningJob").toString()),active=job.optJSONObject("active");if(active!=null){
            try(Cursor row=db.rawQuery("SELECT state FROM operations WHERE task_id=? AND operation_id=? AND tool=? LIMIT 1",new String[]{id,active.getString("operationId"),dev.pocketengine.features.memory.SourceLearningJob.TOOL})){if(!row.moveToFirst())throw new IllegalStateException("原批次操作缺失，未重做");String state=row.getString(0);active.put("stage",state.equals("receipt")?"draft_recorded":state.equals("prepared")?"inflight":"unknown");}
        }
        boolean live=task.optString("status").matches("preparing|running|observing|planning|acting|stopping"),pending=hasUnresolved(id);int next=job.getInt("nextIndex");
        JSONObject result=new JSONObject().put("taskId",id).put("job",job).put("taskStatus",task.getString("status")).put("createdAt",task.getLong("createdAt")).put("runId",task.optString("runId")).put("epoch",task.optInt("coordinatorEpoch"))
                .put("canAddSources",!job.optBoolean("started")&&!live&&active==null&&task.optString("status").matches("queued|interrupted")).put("canContinue",!live&&!pending&&next<job.getJSONArray("inputs").length()).put("canSkipBatch",!live&&next<job.getJSONArray("inputs").length())
                .put("formalMemorySaved",false).put("automaticLearning",false).put("executionAuthorized",false).put("modelUseVerified",false).put("gatewayInvocationsPerAttempt",job.optInt("attemptMaxBatches",dev.pocketengine.features.memory.SourceLearningJob.CALLS_PER_ATTEMPT)).put("maximumExplicitBatchWindow",dev.pocketengine.features.memory.SourceLearningJob.MAX_INPUTS);
        if(active!=null)result.put("active",new JSONObject(active.toString()));return result;
    }
    public synchronized JSONObject listSourceLearningJobs(String before,int limit)throws Exception{
        if(before==null||before.length()>300||!before.isEmpty()&&!before.matches("memory_learning_job:[0-9]{20}:[a-f0-9-]{36}")||limit<1||limit>20)throw new IllegalArgumentException("来源学习任务分页无效");JSONArray jobs=new JSONArray();String next="";boolean more=false;
        String sql="SELECT key,value FROM meta WHERE key>=? AND key<?"+(before.isEmpty()?"":" AND key<?")+" ORDER BY key DESC LIMIT ?";String[] args=before.isEmpty()?new String[]{"memory_learning_job:","memory_learning_job;",Integer.toString(limit+1)}:new String[]{"memory_learning_job:","memory_learning_job;",before,Integer.toString(limit+1)};
        try(Cursor rows=db.rawQuery(sql,args)){while(rows.moveToNext()){if(jobs.length()>=limit){more=true;break;}JSONObject current=sourceLearningJob(rows.getString(1));jobs.put(current);next=rows.getString(0);}}
        return new JSONObject().put("jobs",jobs).put("nextCursor",more?next:"").put("hasMore",more).put("source","original_task_metadata_index").put("formalMemorySaved",false);
    }
    public synchronized JSONObject addSourceLearningJobSource(String id,long expected,JSONObject nativeInput)throws Exception{
        JSONObject task=sourceLearningTask(id),job=task.getJSONObject("sourceLearningJob");expectLearningRevision(job,expected);if(job.optBoolean("started")||job.has("active")||!task.optString("status").matches("queued|interrupted"))throw new IllegalStateException("任务已开始，不能改写原来源计划");
        JSONArray inputs=new JSONArray(job.getJSONArray("inputs").toString());inputs.put(nativeInput);inputs=dev.pocketengine.features.memory.SourceLearningJob.checkedInputs(inputs);job.put("inputs",inputs).put("manifestHash",capabilityFingerprint(new JSONObject().put("inputs",inputs))).put("revision",Math.addExact(expected,1));task.put("sourceLearningJob",job);write(task);return sourceLearningJob(id);
    }
    private static void expectLearningRevision(JSONObject job,long expected)throws Exception{if(expected<1||job.getLong("revision")!=expected)throw new IllegalStateException("原学习任务已改变，请刷新当前批次状态");}
    public synchronized JSONObject startSourceLearningJob(String id,long expected,RunControl original)throws Exception{
        return startSourceLearningJob(id,expected,original,dev.pocketengine.features.memory.SourceLearningJob.CALLS_PER_ATTEMPT);
    }
    public synchronized JSONObject startSourceLearningJob(String id,long expected,RunControl original,int maxBatches)throws Exception{
        if(maxBatches<1||maxBatches>dev.pocketengine.features.memory.SourceLearningJob.MAX_INPUTS)throw new IllegalArgumentException("本次原提炼窗口为1至20批");
        JSONObject task=sourceLearningTask(id),job=task.getJSONObject("sourceLearningJob");expectLearningRevision(job,expected);
        if(original==null||original.hasHostRun()||original.isCancelled()||original.isPaused()||!task.optString("status").matches("queued|paused|awaiting_user|interrupted|failed|cancelled")||job.getInt("nextIndex")>=job.getJSONArray("inputs").length())throw new SecurityException("原学习任务当前不能开始新的本机执行");
        db.beginTransaction();try{JSONObject attempt=startAttempt(id,"capabilities",maxBatches,false,true);original.bindHostRun(id,attempt.getString("runId"),attempt.getInt("coordinatorEpoch"));authorizeTools(id,attempt.getString("runId"),new JSONArray().put("native_memory_consolidation").put(dev.pocketengine.features.memory.SourceLearningJob.TOOL));task=sourceLearningTask(id);job=task.getJSONObject("sourceLearningJob");job.put("started",true).put("attemptMaxBatches",maxBatches).put("attemptRunId",attempt.getString("runId")).put("attemptPauseRevision",original.pauseRevision()).put("revision",Math.addExact(expected,1));task.put("sourceLearningJob",job).put("status","running").put("summary","原来源计划继续逐批提炼；已完成批次不会再次发送");write(task);db.setTransactionSuccessful();return get(id,false);}finally{db.endTransaction();}
    }
    private JSONObject currentSourceLearningRun(String id,String run,int epoch,RunControl original)throws Exception{
        if(original==null||original.isCancelled()||original.isPaused()||!original.matchesHostRun(id,run,epoch))throw new SecurityException("原学习执行控制已失效");checkCapabilityCurrent(id,run,epoch,"native_memory_consolidation");JSONObject task=sourceLearningTask(id),job=task.getJSONObject("sourceLearningJob");if(!run.equals(job.optString("attemptRunId"))||job.getLong("attemptPauseRevision")!=original.pauseRevision())throw new SecurityException("暂停前学习执行已失效，请从原任务显式继续");return task;
    }
    private void learningCheckpoint(JSONObject task,JSONObject job)throws Exception{
        String run=task.optString("runId");if(run.isEmpty())return;JSONObject checkpoint=new JSONObject().put("messages",new JSONArray()).put("steps",job.getInt("reservedModelAttempts")).put("observation",new JSONObject().put("sourceLearningJobRevision",job.getLong("revision")).put("manifestHash",job.getString("manifestHash")).put("nextIndex",job.getInt("nextIndex")));
        ContentValues row=new ContentValues();row.put("checkpoint",checkpoint.toString());row.put("updated_at",System.currentTimeMillis());if(db.update("runs",row,"task_id=? AND run_id=?",new String[]{task.getString("id"),run})!=1)throw new SecurityException("原学习checkpoint执行不存在");
    }
    public synchronized JSONObject beginSourceLearningBatch(String id,String run,int epoch,int index,RunControl original)throws Exception{
        JSONObject task=currentSourceLearningRun(id,run,epoch,original),job=task.getJSONObject("sourceLearningJob");if(job.has("active")||job.getInt("nextIndex")!=index||index<0||index>=job.getJSONArray("inputs").length()||hasUnresolved(id))throw new IllegalStateException("原批次已登记或有结果待核对，未重新发送");
        db.beginTransaction();try{JSONObject grant=grant(run);int remaining=grant.getInt("remainingOperations");if(remaining<1)throw new IllegalStateException("本次逐批提炼预算已耗尽，请显式继续原任务");grant.put("remainingOperations",remaining-1);ContentValues budget=new ContentValues();budget.put("grant_json",grant.toString());db.update("runs",budget,"run_id=?",new String[]{run});
            String operation=UUID.randomUUID().toString();long floor=TaskAuditStore.lastSequence(db,id);JSONObject input=job.getJSONArray("inputs").getJSONObject(index),active=new JSONObject().put("index",index).put("operationId",operation).put("runId",run).put("epoch",epoch).put("pauseRevision",original.pauseRevision()).put("inferenceSequenceFloor",floor);
            JSONObject args=new JSONObject(active.toString()).put("manifestHash",job.getString("manifestHash")).put("input",input);ContentValues row=new ContentValues();row.put("operation_id",operation);row.put("task_id",id);row.put("run_id",run);row.put("tool",dev.pocketengine.features.memory.SourceLearningJob.TOOL);row.put("arguments",args.toString());row.put("state","prepared");row.put("created_at",System.currentTimeMillis());db.insertOrThrow("operations",null,row);
            job.put("active",active).put("reservedModelAttempts",Math.addExact(job.getInt("reservedModelAttempts"),1)).put("revision",Math.addExact(job.getLong("revision"),1));task.put("sourceLearningJob",job);appendEvent(task,new JSONObject().put("type","source_learning_batch_reserved").put("index",index).put("operationId",operation).put("sourceRef",input.getJSONObject("sourceRef")).put("budgetRefunded",false));learningCheckpoint(task,job);db.setTransactionSuccessful();return new JSONObject(active.toString());
        }finally{db.endTransaction();}
    }
    /** One exact prepared model-draft operation is allowed; other unknown effects still block.
     * No App/Book/Gateway monitor is acquired by this TaskStore accounting callback. */
    public InferenceCapture.Target sourceLearningInferenceTarget(String id,String run,int epoch,String operation,RunControl original){
        return nativeInferenceTarget(id,run,epoch,InferenceReceipt.Purpose.SUMMARY,"",()->{
            JSONObject task=currentSourceLearningRun(id,run,epoch,original),active=task.getJSONObject("sourceLearningJob").getJSONObject("active");checkCapabilityCurrent(id,run,epoch,dev.pocketengine.features.memory.SourceLearningJob.TOOL);if(!operation.equals(active.optString("operationId"))||!run.equals(active.optString("runId"))||active.getLong("pauseRevision")!=original.pauseRevision())throw new SecurityException("原批次模型准入已变化");
            try(Cursor other=db.rawQuery("SELECT operation_id,state FROM operations WHERE task_id=? AND state IN ('prepared','unknown','project_running','project_stopping','remote_job_prepared','remote_job_sending','remote_job_unknown','remote_approval_sending','remote_approval_unknown','remote_question_prepared','remote_question_sending','remote_question_unknown') LIMIT 2",new String[]{id})){if(!other.moveToFirst()||!operation.equals(other.getString(0))||!"prepared".equals(other.getString(1))||other.moveToNext())throw new SecurityException("原批次不是唯一可核验的模型准备操作");}
        });
    }
    private JSONObject sourceLearningActive(JSONObject task,String operation)throws Exception{JSONObject job=task.getJSONObject("sourceLearningJob"),active=job.optJSONObject("active");if(active==null||!operation.equals(active.optString("operationId"))||active.getInt("index")!=job.getInt("nextIndex"))throw new SecurityException("原学习批次身份/水位已变化");return active;}
    private static String learningCandidateBinding(JSONObject recorded,String call)throws Exception{return capabilityFingerprint(new JSONObject().put("sourceRef",recorded.getJSONObject("sourceRef")).put("sourceFingerprint",recorded.getString("sourceFingerprint")).put("draftFingerprint",capabilityFingerprint(recorded)).put("callId",call));}
    public synchronized void recordSourceLearningDraft(String id,String run,int epoch,String operation,JSONObject recorded,InferenceReceipt actual,RunControl original)throws Exception{
        JSONObject task=currentSourceLearningRun(id,run,epoch,original),job=task.getJSONObject("sourceLearningJob"),active=sourceLearningActive(task,operation),input=job.getJSONArray("inputs").getJSONObject(active.getInt("index"));
        if(!run.equals(active.getString("runId"))||epoch!=active.getInt("epoch")||original.pauseRevision()!=active.getLong("pauseRevision")||actual==null||actual.outcome!=InferenceReceipt.Outcome.COMPLETED||actual.context.purpose!=InferenceReceipt.Purpose.SUMMARY||!id.equals(actual.context.taskId)||!run.equals(actual.context.runId)||epoch!=actual.context.epoch)throw new SecurityException("不是原批次真实已完成模型调用");
        JSONObject proof=TaskAuditStore.call(db,id,actual.callId);
        if(proof==null||proof.optLong("seq")<=active.getLong("inferenceSequenceFloor"))throw new SecurityException("缺少本批次之后的原TaskStore实际模型回执");proof.remove("at");proof.remove("seq");if(!canonical(proof).equals(canonical(actual.toJson())))throw new SecurityException("模型draft不能借别的计量证明完成");
        if(recorded.length()!=4||!"pocketworld.source-learning-draft.v1".equals(recorded.optString("format"))||!canonical(input.getJSONObject("sourceRef")).equals(canonical(recorded.getJSONObject("sourceRef")))||!input.getString("sourceFingerprint").equals(recorded.optString("sourceFingerprint")))throw new SecurityException("draft与原来源计划不符");
        JSONObject clean=new JSONObject().put("format",recorded.getString("format")).put("sourceRef",recorded.getJSONObject("sourceRef")).put("sourceFingerprint",recorded.getString("sourceFingerprint")).put("draft",recorded.isNull("draft")?JSONObject.NULL:dev.pocketengine.features.memory.MemoryBook.checkedLearningDraft(recorded.getJSONObject("draft")));
        JSONObject receipt=new JSONObject().put("kind","native_source_learning_draft").put("outcome","model_draft_received").put("callId",actual.callId).put("index",active.getInt("index")).put("recordedDraft",clean).put("draftFingerprint",capabilityFingerprint(clean)).put("candidateBindingKey",learningCandidateBinding(clean,actual.callId)).put("formalMemorySaved",false);
        if(receipt.toString().getBytes(StandardCharsets.UTF_8).length>65_536)throw new IllegalStateException("原模型draft超过本批回执字节预算，未截断保存");
        db.beginTransaction();try{ContentValues row=new ContentValues();row.put("state","receipt");row.put("receipt",receipt.toString());if(db.update("operations",row,"task_id=? AND run_id=? AND operation_id=? AND state='prepared'",new String[]{id,run,operation})!=1)throw new SecurityException("原批次回执已保存或已失效，未覆盖");active.put("callId",actual.callId);job.put("active",active).put("revision",Math.addExact(job.getLong("revision"),1));task.put("sourceLearningJob",job);appendEvent(task,new JSONObject().put("type","source_learning_draft_recorded").put("operationId",operation).put("callId",actual.callId).put("draftFingerprint",receipt.getString("draftFingerprint")));learningCheckpoint(task,job);db.setTransactionSuccessful();}finally{db.endTransaction();}
    }
    public synchronized JSONObject sourceLearningDraft(String id,String operation)throws Exception{
        JSONObject task=sourceLearningTask(id),active=sourceLearningActive(task,operation),input=task.getJSONObject("sourceLearningJob").getJSONArray("inputs").getJSONObject(active.getInt("index"));try(Cursor size=db.rawQuery("SELECT length(CAST(receipt AS BLOB)) FROM operations WHERE task_id=? AND operation_id=? AND tool=? AND state='receipt' LIMIT 1",new String[]{id,operation,dev.pocketengine.features.memory.SourceLearningJob.TOOL})){if(!size.moveToFirst()||size.isNull(0)||size.getLong(0)>65_536)throw new IllegalStateException("原批次draft缺失或超过读取预算，未重新请求模型");}
        try(Cursor row=db.rawQuery("SELECT run_id,arguments,receipt FROM operations WHERE task_id=? AND operation_id=? AND tool=? AND state='receipt' LIMIT 1",new String[]{id,operation,dev.pocketengine.features.memory.SourceLearningJob.TOOL})){if(!row.moveToFirst())throw new IllegalStateException("原批次draft不存在");JSONObject args=new JSONObject(row.getString(1)),receipt=new JSONObject(row.getString(2)),recorded=receipt.getJSONObject("recordedDraft");
            if(!active.getString("runId").equals(row.getString(0))||!"native_source_learning_draft".equals(receipt.optString("kind"))||active.getInt("index")!=receipt.getInt("index")||!task.getJSONObject("sourceLearningJob").getString("manifestHash").equals(args.getString("manifestHash"))||!capabilityFingerprint(recorded).equals(receipt.getString("draftFingerprint"))||!canonical(input).equals(canonical(args.getJSONObject("input")))||!canonical(input.getJSONObject("sourceRef")).equals(canonical(recorded.getJSONObject("sourceRef")))||!input.getString("sourceFingerprint").equals(recorded.getString("sourceFingerprint"))||!active.getString("callId").equals(receipt.getString("callId"))||!learningCandidateBinding(recorded,receipt.getString("callId")).equals(receipt.getString("candidateBindingKey")))throw new SecurityException("原批次draft引用/指纹不符");return new JSONObject(recorded.toString());}
    }
    public synchronized void completeSourceLearningBatch(String id,String run,int epoch,String operation,JSONObject result,RunControl original)throws Exception{
        JSONObject task=currentSourceLearningRun(id,run,epoch,original),job=task.getJSONObject("sourceLearningJob"),active=sourceLearningActive(task,operation);JSONObject originalDraft=sourceLearningDraft(id,operation);if(!Boolean.FALSE.equals(result.opt("formalMemorySaved")))throw new SecurityException("批次不得宣称自动保存正式记忆");JSONObject candidate=result.optJSONObject("candidate"),outcome=new JSONObject().put("index",active.getInt("index")).put("operationId",operation).put("outcome",result.getString("outcome"));
        outcome.put("callId",active.getString("callId")).put("candidateBindingKey",learningCandidateBinding(originalDraft,active.getString("callId")));if(candidate!=null){JSONArray refs=candidate.getJSONArray("sourceRefs");if(refs.length()!=1||!canonical(refs.getJSONObject(0)).equals(canonical(job.getJSONArray("inputs").getJSONObject(active.getInt("index")).getJSONObject("sourceRef"))))throw new SecurityException("候选不属于原批次来源");outcome.put("candidateRef",new JSONObject().put("id",candidate.getString("id")).put("revision",candidate.getLong("revision")).put("statusAtBatchCommit",candidate.getString("status")));}
        else if(!"no_candidate".equals(result.optString("outcome")))throw new SecurityException("缺少候选不能伪报批次完成");
        settleLearningBatch(task,job,outcome,"source_learning_batch_completed");
    }
    private void settleLearningBatch(JSONObject task,JSONObject job,JSONObject outcome,String event)throws Exception{db.beginTransaction();try{job.getJSONArray("results").put(outcome);job.put("nextIndex",job.getInt("nextIndex")+1).put("revision",Math.addExact(job.getLong("revision"),1));job.remove("active");task.put("sourceLearningJob",job);appendEvent(task,new JSONObject().put("type",event).put("batch",outcome).put("formalMemorySaved",false));learningCheckpoint(task,job);db.setTransactionSuccessful();}finally{db.endTransaction();}}
    public synchronized void markSourceLearningUnknown(String id,String run,int epoch,String operation)throws Exception{
        JSONObject task=sourceLearningTask(id),active=sourceLearningActive(task,operation);if(!run.equals(active.getString("runId"))||epoch!=active.getInt("epoch"))return;ContentValues row=new ContentValues();row.put("state","unknown");row.put("receipt",new JSONObject().put("kind","native_source_learning_unknown").put("modelOutcome","unknown").put("providerChargeKnown",false).put("budgetRefunded",false).put("automaticRetry",false).toString());db.update("operations",row,"task_id=? AND operation_id=? AND state='prepared'",new String[]{id,operation});
    }
    /** Explicit owner skip, not a claim that an uncertain request did not execute/charge. */
    public synchronized JSONObject skipSourceLearningBatch(String id,long expected,boolean confirmUnknown)throws Exception{
        if(!confirmUnknown)throw new SecurityException("须明确确认跳过，未知调用可能已有耗用且不会退款");JSONObject task=sourceLearningTask(id),job=task.getJSONObject("sourceLearningJob");expectLearningRevision(job,expected);if(task.optString("status").matches("preparing|running|observing|planning|acting|stopping"))throw new IllegalStateException("原批次仍在执行，先停止并等待原回执");try(Cursor live=db.rawQuery("SELECT status FROM runs WHERE task_id=? AND status='running' LIMIT 1",new String[]{id})){if(live.moveToFirst())throw new IllegalStateException("原执行尚未结束，不能跨过在途批次");}int index=job.getInt("nextIndex");if(index>=job.getJSONArray("inputs").length())throw new IllegalStateException("原来源批次已全部结束");JSONObject active=job.optJSONObject("active"),outcome=new JSONObject().put("index",index).put("outcome","owner_skipped_not_retried").put("providerChargeKnown",false).put("budgetRefunded",false);
        db.beginTransaction();try{if(active!=null){String operation=active.getString("operationId");outcome.put("operationId",operation);ContentValues row=new ContentValues();row.put("state","resolved");row.put("receipt",new JSONObject().put("kind","native_source_learning_unknown").put("modelOutcome","unknown_owner_skipped").put("providerChargeKnown",false).put("budgetRefunded",false).put("automaticRetry",false).toString());db.update("operations",row,"task_id=? AND operation_id=? AND state IN ('prepared','unknown')",new String[]{id,operation});}settleLearningBatch(task,job,outcome,"source_learning_batch_owner_skipped");task=sourceLearningTask(id);task.put("status","awaiting_user").put("summary","已明确跳过该批；未知模型耗用保留，不会重新请求旧来源");write(task);db.setTransactionSuccessful();return sourceLearningJob(id);}finally{db.endTransaction();}
    }
    /** Original migration operation locator, not a second task ledger or a resumable grant. */
    public synchronized JSONObject conversationHistoryMigration()throws Exception{
        try(Cursor row=db.rawQuery("SELECT value FROM meta WHERE key='assistant_history_migration' LIMIT 1",null)){
            if(!row.moveToFirst())return null;JSONObject review=new JSONObject(row.getString(0));String id=review.getString("migrationTaskId"),op=review.getString("operationId");
            JSONObject task=required(id);if(!"assistant".equals(task.optString("feature"))||!"migrateHistory".equals(task.optString("command"))||!"owner".equals(task.optString("actorId"))||!"private".equals(task.optString("spaceId")))throw new SecurityException("旧迁移原任务身份不可核对");
            try(Cursor operation=db.rawQuery("SELECT arguments,state,receipt FROM operations WHERE task_id=? AND run_id=? AND operation_id=? AND tool='assistant_migrateHistory' LIMIT 1",new String[]{id,id,op})){
                if(!operation.moveToFirst())throw new IllegalStateException("旧迁移原操作缺失，未重做");JSONObject args=new JSONObject(operation.getString(0));
                if(!"assistant_history_migration".equals(args.optString("nativeReceipt"))||!review.getString("sourceSha256").equals(args.getJSONObject("sourceFacts").optString("sourceSha256")))throw new SecurityException("旧迁移原来源身份不可核对");
                return review.put("operationState",operation.getString(1)).put("taskStatus",task.optString("status")).put("receipt",operation.isNull(2)?JSONObject.NULL:new JSONObject(operation.getString(2)));
            }
        }
    }
    public synchronized JSONObject prepareConversationHistoryMigration(JSONObject nativeFacts)throws Exception{
        if(conversationRepository.active())return conversationRepository.authority().put("status","already_active");
        String sha=nativeFacts.getString("sourceSha256");if(!sha.matches("[0-9a-f]{64}")&&!(sha.isEmpty()&&"absent".equals(nativeFacts.optString("sourceCondition"))))throw new IllegalArgumentException("原文件迁移需精确SHA256；不存在文件必须是明确absence review");
        db.beginTransaction();try{
            // A new explicit native review may reconcile an invisible abandoned staging generation.
            // Absence of an active authority is read from this same DB; no model replay or body copy.
            JSONObject previous=conversationHistoryMigration();if(previous!=null){String old=previous.getString("migrationTaskId");conversationRepository.discardStage(old);
                finishCapabilityOperation(old,previous.getString("operationId"),new JSONObject().put("status","staging_discarded").put("authorityActivated",false).put("reason","owner_requested_new_native_review"));status(old,"cancelled","原DB核对尚未激活；旧不可见stage已清理，未重放原操作");}
            String id=UUID.randomUUID().toString();beginExternal("assistant","migrateHistory",id,new JSONObject().put("goal","将原私人对话保留原文迁入同一TaskStore长期历史"));status(id,"running","仅登记这次原生迁移审核意图；未迁移正文");
            String operation=beginCapabilityOperation(id,id,"assistant","migrateHistory",new JSONObject().put("nativeReceipt","assistant_history_migration").put("sourceFacts",new JSONObject(nativeFacts.toString())));
            status(id,"awaiting_user","等待主人核对原文件与保留范围；尚未迁移正文");
            JSONObject review=new JSONObject(nativeFacts.toString()).put("migrationTaskId",id).put("operationId",operation).put("status","review_required").put("originalHistoryRecovered",false).put("executionAuthorized",false);
            ContentValues pointer=new ContentValues();pointer.put("key","assistant_history_migration");pointer.put("value",review.toString());db.insertWithOnConflict("meta",null,pointer,SQLiteDatabase.CONFLICT_REPLACE);db.setTransactionSuccessful();return review;
        }finally{db.endTransaction();}
    }
    private JSONObject requireConversationMigration(JSONObject nativeReview)throws Exception{
        String id=nativeReview.getString("migrationTaskId"),operation=nativeReview.getString("operationId");JSONObject task=required(id);
        if(!"owner".equals(task.optString("actorId"))||!"private".equals(task.optString("spaceId"))||!"assistant".equals(task.optString("feature"))||!"migrateHistory".equals(task.optString("command"))||!id.equals(task.optString("runId")))throw new SecurityException("迁移不是原生私人来源任务");
        try(Cursor row=db.rawQuery("SELECT arguments,state FROM operations WHERE operation_id=? AND task_id=? AND run_id=? AND tool='assistant_migrateHistory' LIMIT 1",new String[]{operation,id,id})){
            if(!row.moveToFirst())throw new SecurityException("原迁移操作不存在");JSONObject args=new JSONObject(row.getString(0));if(!"assistant_history_migration".equals(args.optString("nativeReceipt"))||!nativeReview.getString("sourceSha256").equals(args.getJSONObject("sourceFacts").optString("sourceSha256")))throw new SecurityException("原迁移sourceSHA不一致");
            if(!java.util.Set.of("prepared","receipt").contains(row.getString(1)))throw new IllegalStateException("旧迁移结果不确定，必须核对原任务；不会自动重做");return args;
        }
    }
    private void requireConversationMigrationPending(JSONObject review)throws Exception{
        requireConversationMigration(review);JSONObject task=required(review.getString("migrationTaskId"));if(task.optBoolean("cancelRequested")||!java.util.Set.of("running","awaiting_user").contains(task.optString("status")))throw new SecurityException("原迁移已暂停、取消或离开待审状态，旧审核不能切换权威");
    }
    public JSONObject stageConversationHistory(JSONObject nativeReview,JSONObject legacySnapshot,java.util.concurrent.Callable<Void> beforeBatch)throws Exception{
        synchronized(this){requireConversationMigration(nativeReview);if(conversationRepository.active())return conversationRepository.authority().put("status","already_active");requireConversationMigrationPending(nativeReview);}
        return conversationRepository.stageLegacy(nativeReview.getString("migrationTaskId"),legacySnapshot,()->{requireConversationMigrationPending(nativeReview);return beforeBatch.call();});
    }
    public JSONObject stageUnversionedConversationHistory(JSONObject nativeReview,byte[] original,java.util.concurrent.Callable<Void> beforeBatch)throws Exception{
        synchronized(this){JSONObject facts=requireConversationMigration(nativeReview).getJSONObject("sourceFacts");if(!"legacy_array".equals(facts.optString("sourceCondition")))throw new SecurityException("不是原生旧数组review");if(conversationRepository.active())throw new IllegalStateException("已有原文权威");requireConversationMigrationPending(nativeReview);}
        return conversationRepository.stageLegacyArray(nativeReview.getString("migrationTaskId"),original,nativeReview.getString("sourceSha256"),()->{requireConversationMigrationPending(nativeReview);return beforeBatch.call();});
    }
    public synchronized JSONObject activateConversationHistory(JSONObject nativeReview,JSONObject stagedCounts,java.util.concurrent.Callable<String> actualSourceSha)throws Exception{
        requireConversationMigration(nativeReview);String id=nativeReview.getString("migrationTaskId");
        if(conversationRepository.active()){JSONObject authority=conversationRepository.authority();if(id.equals(authority.optString("generation")))return authority.put("status","activated");throw new SecurityException("其他原文权威已经激活，旧审核不能覆盖");}
        requireConversationMigrationPending(nativeReview);if(!nativeReview.getString("sourceSha256").equals(actualSourceSha.call())){conversationRepository.discardStage(id);throw new IllegalStateException("原文件或删除/遗忘状态已变化，旧迁移stage已丢弃，请重新审核");}
        JSONObject facts=requireConversationMigration(nativeReview).getJSONObject("sourceFacts");for(String pair:new String[]{"messages:retainedNativeMessages","importedRecords:retainedUnverifiedRecords","sources:sourceRows","conversations:retainedConversations"}){String[] parts=pair.split(":");if(stagedCounts.getLong(parts[0])!=facts.getLong(parts[1]))throw new SecurityException("原review保留范围与实际stage不一致");}
        JSONObject receipt=new JSONObject(stagedCounts.toString()).put("status","activated").put("sourceSha256",nativeReview.getString("sourceSha256")).put("generation",id).put("originalHistoryRecovered",false).put("legacyCleanupPending",true);boolean committed=false;
        db.beginTransaction();try{
            conversationRepository.activateStaged(id,nativeReview.getString("sourceSha256"),stagedCounts);conversationRepository.recordImportProvenance(facts);finishCapabilityOperation(id,nativeReview.getString("operationId"),receipt);finish(id,"completed","原保留消息与来源已激活到同一TaskStore；旧丢失内容未补造",receipt);db.setTransactionSuccessful();
        }finally{try{db.endTransaction();committed=true;}catch(Exception uncertain){conversationRepository.activationUncertain();throw uncertain;}}
        if(committed)conversationRepository.activatedAfterCommit();return receipt.put("migrationTaskId",id).put("operationId",nativeReview.getString("operationId"));
    }
    public synchronized void recordConversationHistoryCleanup(JSONObject nativeReview,boolean pending)throws Exception{
        requireConversationMigration(nativeReview);JSONObject authority=conversationRepository.authority();if(!conversationRepository.active()||!nativeReview.getString("migrationTaskId").equals(authority.optString("generation")))throw new SecurityException("旧文件清理不是当前迁移权威");
        conversationRepository.recordLegacyCleanup(pending);JSONObject receipt=new JSONObject(authority.toString()).put("status","activated").put("legacyCleanupPending",pending).put("originalHistoryRecovered",false);finishCapabilityOperation(nativeReview.getString("migrationTaskId"),nativeReview.getString("operationId"),receipt);status(nativeReview.getString("migrationTaskId"),"completed",pending?"原文DB已激活；旧文件清理仍待核对，不会回退它作为来源":"原保留历史已激活，旧文件已清理；旧丢失内容未恢复");
    }
    public synchronized void abandonConversationHistoryMigration(JSONObject nativeReview,String reason)throws Exception{
        requireConversationMigration(nativeReview);String id=nativeReview.getString("migrationTaskId");if(conversationRepository.active()&&id.equals(conversationRepository.authority().optString("generation")))return;
        boolean cancelled=required(id).optBoolean("cancelRequested")||"cancelled".equals(required(id).optString("status"));conversationRepository.discardStage(id);finishCapabilityOperation(id,nativeReview.getString("operationId"),new JSONObject().put("status",cancelled?"cancelled":"failed").put("reason",reason==null?"migration_stopped":reason).put("authorityActivated",false));status(id,cancelled?"cancelled":"awaiting_user",cancelled?"原迁移已取消，旧stage已清理，原文件仍为权威":"旧stage已清理，原文件仍为权威；请重新核对迁移");
    }
    public synchronized JSONObject create(String goal)throws Exception{return create(goal,"main_screen");}
    public synchronized JSONObject create(String goal,String mode)throws Exception{
        if(!mode.equals("main_screen")&&!mode.equals("background"))throw new IllegalArgumentException("无效执行模式");
        JSONObject t=base(UUID.randomUUID().toString(),goal,"phone",mode).put("displayId",mode.equals("background")?-1:0);
        write(t);return copy(t,false);
    }
    public synchronized JSONObject createCapabilityTask(String goal,String conversationId)throws Exception{
        JSONObject task=base(UUID.randomUUID().toString(),goal,"assistant","capabilities").put("conversationId",conversationId==null?"":conversationId);
        write(task);return copy(task,false);
    }
    /** New native owner submission only. Existing tasks are never silently upgraded. */
    public synchronized JSONObject initializeExecutionScope(String id,int toolBudget)throws Exception {
        JSONObject task=executionScopeTask(id);
        if(task.has("executionScope")||!task.optString("runId").isEmpty()||!task.optString("status").matches("queued|preparing"))throw new SecurityException("只能为新主人任务创建范围");
        return configureExecutionScope(id,0,0,toolBudget,TaskExecutionScope.defaultModelBudget(toolBudget));
    }
    private JSONObject executionScopeTask(String id)throws Exception {
        JSONObject task=required(id);requirePrivateOwner(task);
        if(!task.optString("kind").matches("phone|assistant")||task.optJSONObject("origin")!=null||task.has("sourceLearningJob")||task.has("teamAssignment")||task.has("teamWriteScope"))throw new SecurityException("请回原主人私有手机或助手任务设置范围；团队、来源学习和渠道不能借此扩权");
        return task;
    }
    /** Native owner-only CAS. No start/resume, effect approval, tool expansion or counter reset. */
    public synchronized JSONObject configureExecutionScope(String id,long expectedRevision,long deadlineAt,int totalTools,int totalModels)throws Exception {
        db.beginTransaction();try{
            JSONObject task=executionScopeTask(id),old=task.optJSONObject("executionScope");long revision=old==null?0:old.getLong("revision"),now=System.currentTimeMillis();
            if(revision!=expectedRevision||task.optString("status").matches("completed|stopping")||deadlineAt!=0&&(deadlineAt<=now||deadlineAt-now>31_536_000_000L))throw new SecurityException("原任务范围已变化或期限无效，请重新审阅");
            int usedTools=old==null?executionOperationCount(id):old.getInt("toolsUsed"),usedModels=old==null?executionModelCount(id):old.getInt("modelCallsUsed");
            JSONArray names=old==null?new JSONArray():new JSONArray(old.getJSONArray("tools").toString());boolean pinned=old!=null&&old.getBoolean("toolsPinned");
            String run=task.optString("runId");JSONObject current=run.isEmpty()?null:grant(run);
            if(old==null&&current!=null&&current.optJSONArray("tools")!=null){names=new JSONArray(current.getJSONArray("tools").toString());pinned=true;}
            JSONObject scope=TaskExecutionScope.create(id,task.getString("executionMode"),revision+1,deadlineAt,totalTools,totalModels,usedTools,usedModels,names,pinned);
            if(old!=null&&old.has("browserActions"))scope.put("browserActions",new JSONObject(old.getJSONObject("browserActions").toString()));
            task.put("executionScope",scope);
            if(current!=null&&task.optString("status").matches("preparing|running|paused|pausing|awaiting_user")){
                current.put("executionScopeRevision",revision+1).put("expiresAt",TaskExecutionScope.effectiveDeadline(scope)).put("remainingOperations",totalTools-usedTools);
                int consumed=checkpoint(run).optInt("steps");current.put("stepCeiling",Math.min(400,consumed+totalTools-usedTools));
                ContentValues value=new ContentValues();value.put("grant_json",current.toString());db.update("runs",value,"run_id=? AND task_id=? AND epoch=?",new String[]{run,id,String.valueOf(task.getInt("coordinatorEpoch"))});
                ContentValues lease=new ContentValues();lease.put("expires_at",TaskExecutionScope.effectiveDeadline(scope));db.update("leases",lease,"run_id=? AND epoch=?",new String[]{run,String.valueOf(task.getInt("coordinatorEpoch"))});
                task.put("stepCeiling",current.getInt("stepCeiling"));
            }
            write(task);appendEvent(task,new JSONObject().put("type","owner_execution_scope").put("scopeRevision",revision+1).put("deadlineAt",deadlineAt).put("toolLimit",totalTools).put("modelCallLimit",totalModels).put("toolsUsed",usedTools).put("modelCallsUsed",usedModels).put("executionStarted",false).put("effectsApproved",false));
            db.setTransactionSuccessful();return copy(task,false);
        }finally{db.endTransaction();}
    }
    private int executionOperationCount(String id)throws Exception{return executionSpentCount(id,false);}
    private int executionModelCount(String id)throws Exception{return executionSpentCount(id,true);}
    private int executionSpentCount(String id,boolean model)throws Exception {
        java.util.Set<String> ids=new java.util.HashSet<>();
        try(Cursor rows=db.rawQuery(model?"SELECT DISTINCT call_id FROM task_events WHERE task_id=? AND event_type='inference_receipt'":"SELECT operation_id FROM operations WHERE task_id=?",new String[]{id})){while(rows.moveToNext())if(!rows.isNull(0))ids.add(rows.getString(0));}
        JSONObject pool=required(id).optJSONObject("teamReservationPool");
        if(pool!=null){JSONArray rows=pool.getJSONArray("reservations");for(int i=0;i<rows.length();i++){JSONArray actual=rows.getJSONObject(i).getJSONArray(model?"inferenceCallIds":"operationIds");for(int j=0;j<actual.length();j++)ids.add(actual.getString(j));}}
        return ids.size();
    }
    private void executionScopeCurrent(JSONObject task,String run,JSONObject permission)throws Exception {
        JSONObject scope=task.optJSONObject("executionScope");if(scope==null)return;
        if(!task.getString("id").equals(scope.getString("taskId"))||!task.getString("executionMode").equals(scope.getString("mode"))
                ||!run.equals(task.optString("runId"))||permission.optLong("executionScopeRevision")!=scope.getLong("revision")
                ||permission.getLong("expiresAt")!=TaskExecutionScope.effectiveDeadline(scope))throw new SecurityException("原任务范围已换版，旧执行不能派发");
    }
    /** Same original SQL transaction as the physical call/effect. Caller persists this owner. */
    private void debitExecutionScope(JSONObject owner,String run,boolean model)throws Exception {
        JSONObject scope=owner.optJSONObject("executionScope");if(scope==null)return;
        executionScopeCurrent(owner,run,grant(run));if(TaskExecutionScope.effectiveDeadline(scope)<=System.currentTimeMillis())throw new SecurityException("原任务范围已到期");
        String usedKey=model?"modelCallsUsed":"toolsUsed",limitKey=model?"modelCallLimit":"toolLimit";int used=scope.getInt(usedKey);
        if(used>=scope.getInt(limitKey))throw new SecurityException("原任务累计预算已耗尽；失败或未知请求没有退款");
        scope.put(usedKey,used+1).put("toolsPinned",true);owner.put("executionScope",scope);
    }
    /** Native read only, including a team parent's ordinary owner budget. Legacy is never unlimited. */
    public synchronized JSONObject executionScopeRemaining(String id,String run,int epoch)throws Exception {
        long deadline=taskExecutionDeadline(id,run,epoch);JSONObject task=required(id);requirePrivateOwner(task);JSONObject scope=task.optJSONObject("executionScope");
        JSONObject view=new JSONObject().put("scopePresent",scope!=null).put("deadline",deadline).put("authorizesExecution",false);
        if(scope!=null)view.put("scope",new JSONObject(scope.toString())).put("toolsRemaining",scope.getInt("toolLimit")-scope.getInt("toolsUsed")).put("modelCallsRemaining",scope.getInt("modelCallLimit")-scope.getInt("modelCallsUsed"));
        return view;
    }
    /** Explicit native owner tool opt-in. Does not renew time/budgets or approve concrete effects. */
    public synchronized JSONObject extendExecutionToolsForOwner(String id,String run,int epoch,long expectedRevision,JSONArray names)throws Exception {
        db.beginTransaction();try{
            JSONObject task=executionScopeTask(id);if(!run.equals(task.optString("runId"))||epoch!=task.optInt("coordinatorEpoch")||task.optBoolean("cancelRequested")||!task.optString("status").matches("preparing|running|paused|awaiting_user"))throw new SecurityException("原主人执行已变化");
            JSONObject scope=task.getJSONObject("executionScope"),permission=grant(run);executionScopeCurrent(task,run,permission);
            if(expectedRevision!=scope.getLong("revision")||TaskExecutionScope.effectiveDeadline(scope)<=System.currentTimeMillis()||names==null||names.length()>100)throw new SecurityException("请重新审阅当前任务工具范围");
            JSONArray allowed=new JSONArray(scope.getJSONArray("tools").toString());java.util.Set<String> seen=new java.util.HashSet<>();for(int i=0;i<allowed.length();i++)seen.add(allowed.getString(i));
            for(int i=0;i<names.length();i++){String name=names.getString(i);if(!name.matches("[A-Za-z][A-Za-z0-9_]{0,63}"))throw new SecurityException("无效工具范围");if(seen.add(name))allowed.put(name);}
            if(allowed.length()>100)throw new SecurityException("单任务工具范围超限");
            scope.put("tools",allowed).put("toolsPinned",true).put("revision",Math.addExact(expectedRevision,1));task.put("executionScope",scope);
            permission.put("executionScopeRevision",scope.getLong("revision"));ContentValues value=new ContentValues();value.put("grant_json",permission.toString());db.update("runs",value,"run_id=?",new String[]{run});write(task);
            appendEvent(task,new JSONObject().put("type","owner_execution_tools").put("scopeRevision",scope.getLong("revision")).put("addedTools",new JSONArray(names.toString())).put("effectsApproved",false).put("budgetChanged",false));db.setTransactionSuccessful();return new JSONObject(scope.toString());
        }finally{db.endTransaction();}
    }
    /** Native consumer read only; ownership, epoch and the original dispatch fence remain required. */
    public synchronized JSONObject executionScopeSnapshot(String id,String run,int epoch,RunControl original)throws Exception {
        if(original==null||!original.hasHostRun()||!original.matchesHostRun(id,run,epoch))throw new SecurityException("没有原执行控制器");
        fence(id,run,epoch,original,true);JSONObject scope=required(id).optJSONObject("executionScope");
        if(scope==null)throw new SecurityException("旧任务尚未由主人明确升级范围");return new JSONObject(scope.toString());
    }
    /** Read-only current deadline. Long.MAX_VALUE is only the explicit native until-complete policy. */
    public synchronized long taskExecutionDeadline(String id,String run,int epoch)throws Exception {
        JSONObject task=required(id);if(!run.equals(task.optString("runId"))||epoch!=task.optInt("coordinatorEpoch")||task.optBoolean("cancelRequested")||!task.optString("status").matches("preparing|running|paused|pausing|awaiting_user"))throw new SecurityException("原执行已变化或停止");
        try(Cursor row=db.rawQuery("SELECT status FROM runs WHERE run_id=? AND task_id=? AND epoch=?",new String[]{run,id,String.valueOf(epoch)})){if(!row.moveToFirst()||!"running".equals(row.getString(0)))throw new SecurityException("原执行已结束");}
        JSONObject permission=grant(run);executionScopeCurrent(task,run,permission);long deadline=permission.getLong("expiresAt");if(deadline<1)throw new SecurityException("没有原执行期限");return deadline;
    }
    /** Native accounting-only origin. These records have no tools, phone lease or restart entry. */
    public enum NativeInferenceKind {CONNECTION_TEST,VOICE_TURN,REALTIME,RELAY,SKILL_DRAFT}
    public final class NativeInferenceSession implements AutoCloseable {
        public final String taskId,runId;public final int epoch;public final NativeInferenceKind kind;
        private final RunControl original;private final int maxCalls;private volatile boolean closed;
        private NativeInferenceSession(String task,String run,int epoch,NativeInferenceKind kind,RunControl original,int maxCalls){taskId=task;runId=run;this.epoch=epoch;this.kind=kind;this.original=original;this.maxCalls=maxCalls;}
        private void current()throws Exception{
            if(closed||original.isCancelled()||original.isPaused()||!original.matchesHostRun(taskId,runId,epoch))throw new SecurityException("原用量记录会话已结束；服务端后续处理不在本次已观测记录内");original.check();
            JSONObject task=required(taskId),scope=task.getJSONObject("nativeInference");
            if(!"inference".equals(task.optString("kind"))||!runId.equals(task.optString("runId"))||epoch!=task.optInt("coordinatorEpoch")||!kind.name().equals(scope.optString("kind"))||task.optBoolean("cancelRequested")||!"recording".equals(task.optString("status"))||scope.getLong("expiresAt")<=System.currentTimeMillis())throw new SecurityException("原用量会话或代次已变化");
        }
        public InferenceCapture.Target target()throws Exception{return target(InferenceReceipt.Purpose.valueOf(kind.name()));}
        public InferenceCapture.Target transcriptionTarget()throws Exception{if(kind!=NativeInferenceKind.REALTIME)throw new SecurityException("转录只归原实时用量会话");return target(InferenceReceipt.Purpose.REALTIME_TRANSCRIPTION);}
        private InferenceCapture.Target target(InferenceReceipt.Purpose purpose)throws Exception{
            synchronized(TaskStore.this){current();}
            InferenceReceipt.Context context=new InferenceReceipt.Context(taskId,runId,epoch,purpose,"");
            return new InferenceCapture.Target(context,new InferenceCapture.Sink(){
                @Override public void started(InferenceReceipt receipt)throws Exception{synchronized(TaskStore.this){current();db.beginTransaction();try{
                    if(TaskAuditStore.call(db,taskId,receipt.callId)==null)try(Cursor count=db.rawQuery("SELECT count(*) FROM task_events WHERE task_id=? AND event_type='inference_receipt'",new String[]{taskId})){if(!count.moveToFirst()||count.getInt(0)>=maxCalls)throw new SecurityException("本次原生用量记录已到本地上限，未推测或增加服务端余量");}
                    recordInference(context,receipt,true);db.setTransactionSuccessful();
                }finally{db.endTransaction();}}}
                @Override public void finished(InferenceReceipt receipt)throws Exception{synchronized(TaskStore.this){recordInference(context,receipt,false);}}
            },()->{synchronized(TaskStore.this){current();}});
        }
        public JSONObject reference()throws Exception{return new JSONObject().put("taskId",taskId).put("runId",runId).put("epoch",epoch).put("kind",kind.name()).put("accountingOnly",true).put("executionAuthorized",false);}
        public void finish(String status)throws Exception{
            if(!Set.of("completed","failed","cancelled","interrupted").contains(status))throw new IllegalArgumentException("invalid_inference_session_end");
            synchronized(TaskStore.this){if(closed)return;JSONObject task=required(taskId);if(!runId.equals(task.optString("runId"))||epoch!=task.optInt("coordinatorEpoch"))throw new SecurityException("原用量记录代次已变化");endAttempt(taskId,runId,status,status.equals("completed")?"推理处理已结束；实际用量以本次提供方回执为准":"推理会话已停止；未确认的用量仍为未知，没有重发或退款",reference());closed=true;}
        }
        @Override public void close()throws Exception{finish(original.isCancelled()?"cancelled":"interrupted");}
    }
    /** Same original SQLite TaskStore/runs/audit. This is not a second task executor or a tool grant. */
    public synchronized NativeInferenceSession beginNativeInference(NativeInferenceKind kind,String nativeSourceId,RunControl original)throws Exception{
        Objects.requireNonNull(kind);Objects.requireNonNull(original);original.check();if(original.hasHostRun()||nativeSourceId==null||!nativeSourceId.matches("[A-Za-z0-9_.:-]{1,200}"))throw new SecurityException("用量会话需要新的原生来源控制器，不能借用已有任务授权");
        String id=UUID.randomUUID().toString(),run=UUID.randomUUID().toString();long now=System.currentTimeMillis(),expires=now+(kind==NativeInferenceKind.REALTIME?30*60_000L:10*60_000L);int maximum=kind==NativeInferenceKind.REALTIME?322:1;
        String title=switch(kind){case CONNECTION_TEST->"模型连接测试";case VOICE_TURN->"语音文字对话";case REALTIME->"自然语音会话";case RELAY->"本机模型服务请求";case SKILL_DRAFT->"经验技能草稿整理";};
        db.beginTransaction();try{
            JSONObject task=base(id,title,"inference","inference").put("runId",run).put("coordinatorEpoch",1).put("status","recording").put("approvalState","not_applicable")
                .put("origin",new JSONObject().put("kind","native_inference").put("sourceKind",kind.name()).put("sourceId",nativeSourceId))
                .put("nativeInference",new JSONObject().put("kind",kind.name()).put("maxCalls",maximum).put("expiresAt",expires).put("accountingOnly",true).put("executionAuthorized",false).put("limitMeaning","bounded_observation_storage_not_remote_quota"));
            JSONObject noTools=new JSONObject().put("principalId","owner").put("spaceId","private").put("mode","inference").put("expiresAt",expires).put("remainingOperations",0).put("stepCeiling",0);
            ContentValues values=new ContentValues();values.put("run_id",run);values.put("task_id",id);values.put("epoch",1);values.put("status","running");values.put("checkpoint",new JSONObject().put("messages",new JSONArray()).put("steps",0).toString());values.put("grant_json",noTools.toString());values.put("created_at",now);values.put("updated_at",now);db.insertOrThrow("runs",null,values);write(task);original.bindHostRun(id,run,1);db.setTransactionSuccessful();return new NativeInferenceSession(id,run,1,kind,original,maximum);
        }finally{db.endTransaction();}
    }
    /** Short native notification claim. It opens only an original record, never executes an action. */
    public static final class OwnerTaskNotice {
        private final TaskStore issuer;private final String snapshot;
        private OwnerTaskNotice(TaskStore issuer,JSONObject snapshot){this.issuer=issuer;this.snapshot=snapshot.toString();}
        public JSONObject view()throws Exception{return new JSONObject(snapshot);}
    }
    /** Same TaskStore document, no delivery queue. Repeated pushes do not post another result. */
    public synchronized OwnerTaskNotice ownerTaskNotice(String id)throws Exception{
        JSONObject header;try(Cursor row=db.rawQuery("SELECT ui_header,length(CAST(ui_header AS BLOB)) FROM tasks WHERE task_id=?",new String[]{id})){if(!row.moveToFirst())return null;if(row.isNull(0)||row.getLong(1)>32768)throw new IllegalStateException("任务通知摘要不可核对");header=new JSONObject(row.getString(0));}
        if(!"assistant".equals(header.optString("kind")))return null;
        JSONObject task=required(id),candidate=settledOwnerNotice(task),prior=task.optJSONObject("ownerNotification");
        if(candidate==null){if(prior==null||Set.of("opened","obsolete","cancelled").contains(prior.optString("phase")))return null;JSONObject cancel=new JSONObject(prior.toString()).put("cancelOnly",true);return new OwnerTaskNotice(this,cancel);}
        if(prior!=null&&candidate.getString("token").equals(prior.optString("token"))&&Set.of("posted","opened","posting","unconfirmed").contains(prior.optString("phase")))return null;
        candidate.put("phase","planned").put("createdAt",System.currentTimeMillis());task.put("ownerNotification",new JSONObject(candidate.toString()));write(task);if(prior!=null&&!candidate.getString("token").equals(prior.optString("token")))candidate.put("replacesToken",prior.optString("token"));return new OwnerTaskNotice(this,candidate);
    }
    private JSONObject settledOwnerNotice(JSONObject task)throws Exception{
        JSONObject candidate=dev.pocketengine.features.assistant.notifications.TaskNotificationState.candidate(task);if(candidate==null)return null;
        try(Cursor run=db.rawQuery("SELECT status FROM runs WHERE task_id=? AND run_id=? AND epoch=?",new String[]{task.getString("id"),task.getString("runId"),Integer.toString(task.getInt("coordinatorEpoch"))})){if(!run.moveToFirst()||"running".equals(run.getString(0)))return null;}
        return candidate;
    }
    public synchronized boolean beginOwnerTaskNoticePosting(OwnerTaskNotice claim)throws Exception{
        if(!ownerTaskNoticeCurrent(claim)||claim.view().optBoolean("cancelOnly"))return false;JSONObject view=claim.view(),task=required(view.getString("taskId")),prior=task.getJSONObject("ownerNotification");
        if(!Set.of("planned","permission_required").contains(prior.optString("phase")))return false;prior.put("phase","posting").put("lastObservedAt",System.currentTimeMillis());task.put("ownerNotification",prior);write(task);return true;
    }
    public synchronized boolean ownerTaskNoticeCurrent(OwnerTaskNotice claim)throws Exception{
        if(claim==null||claim.issuer!=this)return false;JSONObject view=claim.view(),task=read(view.getString("taskId"));if(task==null)return false;
        JSONObject prior=task.optJSONObject("ownerNotification");if(prior==null||!view.getString("token").equals(prior.optString("token")))return false;
        if(view.optBoolean("cancelOnly"))return settledOwnerNotice(task)==null;JSONObject candidate=settledOwnerNotice(task);return candidate!=null&&view.getString("token").equals(candidate.getString("token"));
    }
    public synchronized void ownerTaskNoticePhase(OwnerTaskNotice claim,String phase)throws Exception{
        if(!Set.of("posting","posted","permission_required","unconfirmed","obsolete").contains(phase)||claim==null||claim.issuer!=this)throw new SecurityException("通知状态没有原生来源");
        JSONObject view=claim.view(),task=required(view.getString("taskId")),prior=task.optJSONObject("ownerNotification");if(prior==null||!view.getString("token").equals(prior.optString("token")))return;
        if("opened".equals(prior.optString("phase"))&&!phase.equals("obsolete"))return;if(!ownerTaskNoticeCurrent(claim))return;prior.put("phase",phase).put("lastObservedAt",System.currentTimeMillis()).put("deliveryConfirmed",false);task.put("ownerNotification",prior);write(task);
    }
    /** Native Activity navigation only. Old notices cannot clear a replacement notice or grant continuation. */
    public synchronized JSONObject openOwnerTaskNotice(String id,String run,int epoch,String token)throws Exception{
        if(id==null||run==null||token==null||!id.matches("[a-fA-F0-9-]{36}")||!run.matches("[a-fA-F0-9-]{36}")||epoch<1||!token.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("通知身份无效");
        JSONObject task=required(id);requirePrivateOwner(task);if(!"assistant".equals(task.optString("kind")))throw new SecurityException("通知不是原私人助手结果");
        JSONObject prior=task.optJSONObject("ownerNotification"),current=settledOwnerNotice(task);boolean exact=prior!=null&&token.equals(prior.optString("token"))&&run.equals(prior.optString("runId"))&&epoch==prior.optInt("epoch");
        boolean expired=!exact||current==null||!token.equals(current.getString("token"));
        if(exact){prior.put("phase","opened").put("openedAt",System.currentTimeMillis());task.put("ownerNotification",prior);write(task);}
        return new JSONObject().put("taskId",id).put("runId",task.optString("runId")).put("epoch",task.optInt("coordinatorEpoch")).put("anchor",expired?"current":prior.optString("anchor")).put("expired",expired).put("executionAuthorized",false);
    }
    /** Native reviewed-final admission only: persist a waiting task, never start a Run/model/tool. */
    public synchronized JSONObject createReviewedRealtimeTask(String commitId,String text,JSONObject source)throws Exception{
        JSONObject checked=reviewedRealtimeSource(commitId,text,source);
        db.beginTransaction();try{
            JSONObject existing=read(commitId);
            if(existing!=null){requireReviewedRealtimeIdentity(existing,text,checked);db.setTransactionSuccessful();return copy(existing,false).put("created",false);}
            JSONObject task=base(commitId,text,"assistant","capabilities").put("conversationId","").put("origin",checked)
                .put("status","awaiting_user").put("executionStatus","awaiting_user").put("summary","已确认语音最终文本；等待主人打开原任务并明确继续，不会自动执行");
            write(task);db.setTransactionSuccessful();return copy(task,false).put("created",true);
        }finally{db.endTransaction();}
    }
    /** Unknown creation outcomes reconcile by their exact native review identity; this never creates anything. */
    public synchronized JSONObject reviewedRealtimeTask(String commitId,String text,JSONObject source)throws Exception{
        JSONObject checked=reviewedRealtimeSource(commitId,text,source),task=read(commitId);
        if(task==null)return null;requireReviewedRealtimeIdentity(task,text,checked);return copy(task,false).put("created",false);
    }
    /** Explicit recovery uses original voice origins, including records older than the general recent list. */
    public synchronized JSONArray reviewedRealtimeTasks(int limit)throws Exception{
        if(limit<1||limit>20)throw new IllegalArgumentException("语音任务列表范围无效");JSONArray result=new JSONArray();
        try(Cursor cursor=db.rawQuery("SELECT document FROM tasks ORDER BY updated_at DESC",null)){while(cursor.moveToNext()&&result.length()<limit){
            JSONObject task=new JSONObject(cursor.getString(0)),source=task.optJSONObject("origin");if(source==null||!"owner_reviewed_realtime".equals(source.optString("kind")))continue;
            JSONObject checked=reviewedRealtimeSource(task.getString("id"),task.getString("goal"),source);requireReviewedRealtimeIdentity(task,task.getString("goal"),checked);result.put(copy(task,false));
        }}return result;
    }
    /** Validation and reservation share this store lock; only the original native admission calls it. */
    public synchronized JSONObject startReviewedRealtimeAttempt(String id,String text,JSONObject source,String expectedRun,int expectedEpoch,long expectedRevision,int allocation,RunControl control)throws Exception{
        JSONObject task=requireReviewedRealtimeVersion(id,text,source,expectedRun,expectedEpoch,expectedRevision,false);
        if(expectedEpoch!=0||!expectedRun.isEmpty()||!"awaiting_user".equals(task.optString("status"))||control==null||control.hasHostRun())throw new SecurityException("此语音目标已有执行；请打开原任务核对，不能重复开始");
        control.check();JSONObject admitted=startAttempt(id,"capabilities",allocation);control.bindHostRun(id,admitted.getString("runId"),admitted.getInt("coordinatorEpoch"));return admitted;
    }
    /** A stale voice button cannot cancel a replacement run or rewrite an already terminal result. */
    public synchronized JSONObject cancelReviewedRealtimeRun(String id,String text,JSONObject source,String expectedRun,int expectedEpoch,long expectedRevision,RunControl control)throws Exception{
        db.beginTransaction();try{
            JSONObject task=requireReviewedRealtimeVersion(id,text,source,expectedRun,expectedEpoch,expectedRevision,true);
            if(expectedEpoch<1||expectedRun.isEmpty())throw new SecurityException("没有可取消的原执行轮次");
            if(control!=null&&(!control.hasHostRun()||!control.matchesHostRun(id,expectedRun,expectedEpoch)))throw new SecurityException("当前控制器不属于原执行轮次");
            if(task.optString("status").matches("completed|failed|cancelled|interrupted"))throw new IllegalStateException("该执行已经结束，请核对原结果");
            task.put("cancelRequested",true).put("status",hasUnresolved(id)?"reconciling":control==null?"cancelled":"stopping")
                .put("summary",hasUnresolved(id)?"已停止后续派发；已有动作结果仍需核对，没有撤销或重试":"主人已请求停止这一轮原任务；已经发生的操作不会撤销");
            write(task);if(control!=null)control.cancel();db.setTransactionSuccessful();return copy(required(id),false);
        }finally{db.endTransaction();}
    }
    private JSONObject requireReviewedRealtimeVersion(String id,String text,JSONObject source,String run,int epoch,long revision,boolean allowProgress)throws Exception{
        JSONObject checked=reviewedRealtimeSource(id,text,source),task=required(id);requireReviewedRealtimeIdentity(task,text,checked);
        long actual=Math.max(1,task.optLong("voiceStateRevision",task.optLong("updatedAt",1)));
        if(run==null||!run.equals(task.optString("runId"))||epoch!=task.optInt("coordinatorEpoch")||(allowProgress?revision>actual:revision!=actual))throw new SecurityException("原语音任务记录或执行轮次已变化，请重新核对");return task;
    }
    private static JSONObject reviewedRealtimeSource(String commitId,String text,JSONObject source)throws Exception{
        String uuid="[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}";
        if(commitId==null||!commitId.matches(uuid)||text==null||text.isBlank()||text.length()>4000||source==null)throw new SecurityException("语音复核身份或文本无效");
        java.util.Set<String> fields=java.util.Set.of("kind","commitId","sessionId","turnId","finalRevision","segmentId","participantIdentity","trackSid","protocol","contentHash","sourceKind","identityVerified","reviewedBy","spaceId");
        java.util.Iterator<String> names=source.keys();while(names.hasNext())if(!fields.contains(names.next()))throw new SecurityException("语音来源含未授权字段");
        if(source.length()!=fields.size())throw new SecurityException("语音来源证据不完整");
        if(!source.optString("kind").equals("owner_reviewed_realtime")||!source.optString("commitId").equals(commitId)||!source.optString("reviewedBy").equals("owner")||!source.optString("spaceId").equals("private")||!source.optString("sourceKind").equals("reported_local_microphone")||!(source.opt("identityVerified") instanceof Boolean)||source.getBoolean("identityVerified"))throw new SecurityException("转录不能伪装为身份认证或扩大主人权限");
        for(String key:new String[]{"sessionId","turnId"})if(!(source.opt(key) instanceof String)||!source.getString(key).matches(uuid))throw new SecurityException("语音会话代次无效");
        for(String key:new String[]{"segmentId","participantIdentity","trackSid"})if(!(source.opt(key) instanceof String)||source.getString(key).isBlank()||source.getString(key).length()>200)throw new SecurityException("语音媒体来源无效");
        if(!(source.opt("finalRevision") instanceof Long||source.opt("finalRevision") instanceof Integer)||source.getLong("finalRevision")<=0||!source.optString("protocol").matches("text_stream|legacy_event|openai_realtime_ga"))throw new SecurityException("语音最终版本无效");
        byte[] digest=java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));StringBuilder hash=new StringBuilder();for(byte value:digest)hash.append(String.format(java.util.Locale.ROOT,"%02x",value&255));
        if(!hash.toString().equals(source.optString("contentHash")))throw new SecurityException("语音最终文本与复核指纹不一致");
        return new JSONObject(source.toString());
    }
    private static void requireReviewedRealtimeIdentity(JSONObject task,String text,JSONObject source)throws Exception{
        if(!task.optString("kind").equals("assistant")||!task.optString("executionMode").equals("capabilities")||!task.optString("actorId").equals("owner")||!task.optString("spaceId").equals("private")||!text.equals(task.optString("goal"))||task.optJSONObject("origin")==null||!canonical(source).equals(canonical(task.getJSONObject("origin"))))throw new SecurityException("同一语音提交已绑定其他目标、来源或最终版本");
    }
    /** Native channel admission: one immutable authenticated origin owns one host task ID. */
    public synchronized JSONObject registerChannelTask(String id,String goal,JSONObject origin)throws Exception{
        JSONObject checked=channelOrigin(origin);if(!id.matches("[a-f0-9-]{36}")||goal==null||goal.trim().isEmpty()||goal.length()>4000)throw new IllegalArgumentException("渠道任务身份或目标无效");
        db.beginTransaction();try{
            JSONObject existing=read(id);
            if(existing!=null){requireChannelIdentity(existing,checked);if(!goal.equals(existing.getString("goal")))throw new SecurityException("渠道来源不能更换已登记的任务目标");db.setTransactionSuccessful();return copy(existing,false).put("created",false);}
            try(Cursor cursor=db.rawQuery("SELECT document FROM tasks",null)){while(cursor.moveToNext()){
                JSONObject other=new JSONObject(cursor.getString(0)),source=other.optJSONObject("origin");if(source==null||!source.optString("kind").equals("channel"))continue;
                if(checked.getString("requestId").equals(source.optString("requestId"))||checked.getString("profileId").equals(source.optString("profileId"))&&checked.getString("messageId").equals(source.optString("messageId")))throw new SecurityException("此渠道来源已关联另一个宿主任务");
            }}
            JSONObject task=base(id,goal,"assistant","capabilities").put("conversationId","").put("origin",checked).put("status","queued");write(task);db.setTransactionSuccessful();return copy(task,false).put("created",true);
        }finally{db.endTransaction();}
    }
    public synchronized JSONObject channelTask(String id,JSONObject origin)throws Exception{JSONObject task=read(id);if(task==null)return null;requireChannelIdentity(task,channelOrigin(origin));return copy(task,false);}
    /** Recovery enumerates persisted channel links, not the UI's recent-200 projection. */
    public synchronized JSONArray channelTasks()throws Exception{
        JSONArray result=new JSONArray();try(Cursor cursor=db.rawQuery("SELECT document FROM tasks",null)){while(cursor.moveToNext()){
            JSONObject task=new JSONObject(cursor.getString(0)),origin=task.optJSONObject("origin");
            if(origin!=null&&origin.optString("kind").equals("channel")){requireChannelIdentity(task,channelOrigin(origin));result.put(copy(task,false));}
        }}return result;
    }
    public synchronized void checkChannelTaskControl(String id,JSONObject origin,RunControl control)throws Exception{
        JSONObject task=required(id);requireChannelIdentity(task,channelOrigin(origin));
        if(control.hasHostRun()&&(!control.matchesHostRun(id,task.optString("runId"),task.optInt("coordinatorEpoch"))||task.optBoolean("cancelRequested")))throw new SecurityException("渠道任务的执行代次或取消状态已失效");
    }
    /** Reuse only the actually reserved running attempt; never start a second run for a queued worker. */
    public synchronized JSONObject requireCapabilityAttempt(String id,RunControl control)throws Exception{
        JSONObject task=required(id);String run=task.optString("runId");int epoch=task.optInt("coordinatorEpoch");
        if(!task.optString("kind").equals("assistant")||!task.optString("executionMode").equals("capabilities")||!control.matchesHostRun(id,run,epoch)||task.optBoolean("cancelRequested")||!task.optString("status").matches("preparing|running"))throw new SecurityException("预留能力执行已失效");
        try(Cursor cursor=db.rawQuery("SELECT status FROM runs WHERE task_id=? AND run_id=? AND epoch=?",new String[]{id,run,String.valueOf(epoch)})){if(!cursor.moveToFirst()||!cursor.getString(0).equals("running"))throw new SecurityException("预留执行尝试已结束");}
        return copy(task,false);
    }
    /** Pair validation and cancelling that exact in-memory controller share the TaskStore lock/transaction. */
    public synchronized JSONObject cancelChannelTask(String id,JSONObject origin,String runId,int epoch,RunControl control)throws Exception{
        db.beginTransaction();try{
            JSONObject task=required(id);requireChannelIdentity(task,channelOrigin(origin));
            if(runId==null||!runId.equals(task.optString("runId"))||epoch!=task.optInt("coordinatorEpoch"))throw new SecurityException("取消目标执行已变化，未停止新执行");
            if(control!=null&&!control.matchesHostRun(id,runId,epoch))throw new SecurityException("当前控制器不属于待取消的执行");
            String status=task.optString("status");task.put("cancelRequested",true);
            if(hasUnresolved(id))task.put("status","reconciling").put("summary","已停止后续派发；已有动作结果仍需核对，未撤销或重试");
            else if(!status.matches("completed|failed|cancelled|interrupted|needs_confirmation"))task.put("status",control==null||runId.isEmpty()||status.equals("awaiting_user")?"cancelled":"stopping").put("summary","主人已请求停止此渠道任务；已有动作仍需核对");
            write(task);if(control!=null)control.cancel();db.setTransactionSuccessful();return copy(task,false);
        }finally{db.endTransaction();}
    }
    private static JSONObject channelOrigin(JSONObject origin)throws Exception{
        if(origin==null)throw new SecurityException("缺少渠道来源");java.util.Set<String> fields=java.util.Set.of("kind","requestId","eventId","profileId","messageId","senderId","authenticatedBy","bindingEpoch","taskGrantEpoch","ownerId","spaceId");
        java.util.Iterator<String> names=origin.keys();while(names.hasNext())if(!fields.contains(names.next()))throw new SecurityException("渠道来源含未授权字段");
        for(String key:fields)if(!(origin.opt(key) instanceof String))throw new SecurityException("渠道来源字段无效");
        if(!origin.getString("kind").equals("channel")||!origin.getString("ownerId").equals("owner")||!origin.getString("spaceId").equals("private")||!origin.getString("authenticatedBy").matches("qq_signature|qq_gateway|trusted_relay|weixin_session"))throw new SecurityException("渠道来源不能扩大私人权限");
        for(String key:new String[]{"requestId","eventId","bindingEpoch","taskGrantEpoch"})if(!origin.getString(key).matches("[a-f0-9-]{36}"))throw new SecurityException("渠道来源代次无效");
        for(String key:new String[]{"profileId","messageId","senderId"})if(!origin.getString(key).matches("[A-Za-z0-9_.:@-]{1,180}"))throw new SecurityException("渠道来源身份无效");return new JSONObject(origin.toString());
    }
    private static void requireChannelIdentity(JSONObject task,JSONObject origin)throws Exception{
        if(!task.optString("kind").equals("assistant")||!task.optString("executionMode").equals("capabilities")||!task.optString("actorId").equals("owner")||!task.optString("spaceId").equals("private")||task.optJSONObject("origin")==null||!canonical(origin).equals(canonical(task.getJSONObject("origin"))))throw new SecurityException("宿主任务不属于此渠道私人来源");
    }
    public synchronized JSONObject recordCommitmentDelegation(JSONObject commitment)throws Exception{
        String id=UUID.randomUUID().toString();JSONObject action=new JSONObject().put("kind","user_action").put("id",id).put("ownerId","owner").put("spaceId","private").put("action","delegate_commitment").put("title",commitment.optString("title")).put("recordedAt",System.currentTimeMillis());
        saveEvidence("commitment.action.",id,action);return new JSONObject().put("kind","user_action").put("id",id).put("ownerId","owner").put("spaceId","private");
    }
    public synchronized void bindCommitment(JSONObject commitment)throws Exception{
        String taskId=commitment.optString("taskId");if(taskId.isEmpty())return;JSONObject task=required(taskId);
        if(!"owner".equals(task.optString("actorId"))||!"private".equals(task.optString("spaceId")))throw new SecurityException("承诺不能关联其他空间的任务");
        JSONArray bindings=task.optJSONArray("commitmentBindings");if(bindings==null)bindings=new JSONArray();
        for(int i=0;i<bindings.length();i++)if(bindings.getJSONObject(i).optString("commitmentId").equals(commitment.getString("id")))return;
        bindings.put(new JSONObject().put("commitmentId",commitment.getString("id")).put("commitmentRevision",commitment.getLong("revision")));task.put("commitmentBindings",bindings);write(task);
    }
    public synchronized JSONObject recordOwnerCommitmentConfirmation(JSONObject commitment)throws Exception{
        return recordCommitmentEvidence(commitment,"owner_confirmation",UUID.randomUUID().toString(),commitment.optString("taskId"));
    }
    public synchronized JSONObject recordTaskCommitmentCompletion(String taskId,JSONObject binding)throws Exception{
        JSONObject task=required(taskId);JSONObject confirmation=TaskAuditStore.latest(db,taskId,"user_verification");boolean ownerConfirmed=confirmation!=null&&confirmation.optBoolean("completed");
        JSONObject machine=automationArtifactEvidence(task,binding);if(machine!=null)return new JSONObject().put("kind","task_result").put("id",machine.getString("id"));
        if(!task.optString("status").equals("completed")||!ownerConfirmed)throw new SecurityException("任务尚未取得持久的主人完成确认");
        JSONObject commitment=new JSONObject().put("id",binding.getString("commitmentId")).put("revision",binding.getLong("commitmentRevision"));
        return recordCommitmentEvidence(commitment,"task_result",UUID.randomUUID().toString(),taskId);
    }
    /** Native B2 verifier only. A machine result is a typed original-file proof, never model status. */
    public synchronized JSONObject recordAutomationArtifactResult(String taskId,JSONObject binding,JSONObject standard,JSONObject proof)throws Exception{
        db.beginTransaction();try{
            JSONObject task=required(taskId);requirePrivateOwner(task);
            if(!automationRunSettled(taskId)||!task.optString("status").matches("needs_confirmation|awaiting_user|completed")||hasUnresolved(taskId)||!taskId.equals(proof.optString("taskId"))||!task.optString("runId").equals(proof.optString("runId"))||task.optInt("coordinatorEpoch")<1||task.optInt("coordinatorEpoch")!=proof.optInt("coordinatorEpoch"))throw new SecurityException("原任务或动作结果尚未核实结束");
            if(!"workspace_artifact".equals(proof.optString("kind"))||!capabilityFingerprint(standard).equals(capabilityFingerprint(proof.getJSONObject("standard"))))throw new SecurityException("机器核验标准不属于已审阅步骤");
            boolean bound=false;JSONArray bindings=task.optJSONArray("commitmentBindings");for(int i=0;bindings!=null&&i<bindings.length();i++)if(canonical(binding).equals(canonical(bindings.getJSONObject(i))))bound=true;
            if(!bound)throw new SecurityException("原任务未绑定这个确切承诺版本");
            // Re-read the exact durable original operation at the final commit fence.
            try(Cursor row=db.rawQuery("SELECT run_id,tool,state,receipt FROM operations WHERE task_id=? AND operation_id=?",new String[]{taskId,proof.getString("operationId")})){
                if(!row.moveToFirst()||!task.getString("runId").equals(row.getString(0))||!"workspace_create_artifact".equals(row.getString(1))||!"receipt".equals(row.getString(2))||row.isNull(3))throw new SecurityException("原文件操作收据缺失");
                JSONObject receipt=new JSONObject(row.getString(3));if(!proof.getString("artifactId").equals(receipt.optString("id"))||!proof.getString("sha256").equals(receipt.optString("sha256"))||!taskId.equals(receipt.optString("taskId"))||!task.getString("runId").equals(receipt.optString("runId")))throw new SecurityException("文件成果不属于原操作和原执行");
            }
            String id="automation-"+capabilityFingerprint(new JSONObject().put("binding",binding).put("proof",proof));
            JSONObject evidence=new JSONObject().put("kind","task_result").put("id",id).put("ownerId","owner").put("spaceId","private").put("commitmentId",binding.getString("commitmentId")).put("commitmentRevision",binding.getLong("commitmentRevision"))
                    .put("outcome","completed").put("recordedAt",System.currentTimeMillis()).put("taskId",taskId).put("source","native_workspace_artifact_standard").put("verification",new JSONObject(proof.toString()));
            try(Cursor old=db.rawQuery("SELECT value FROM meta WHERE key=?",new String[]{"commitment.evidence."+id})){if(!old.moveToFirst())saveEvidence("commitment.evidence.",id,evidence);}
            JSONArray refs=task.optJSONArray("automationResultRefs");if(refs==null)refs=new JSONArray();boolean found=false;for(int i=0;i<refs.length();i++)if(id.equals(refs.getString(i)))found=true;if(!found)refs.put(id);
            task.put("automationResultRefs",refs).put("status","completed").put("summary","已按计划标准核验原文件成果；未推定内容质量或业务结果");
            ContentValues runResult=new ContentValues();runResult.put("status","completed");runResult.put("updated_at",System.currentTimeMillis());db.update("runs",runResult,"task_id=? AND run_id=? AND epoch=?",new String[]{taskId,task.getString("runId"),String.valueOf(task.getInt("coordinatorEpoch"))});
            if(!found)appendEvent(task,new JSONObject().put("type","native_plan_verification").put("evidenceId",id).put("artifactId",proof.getString("artifactId")).put("sha256",proof.getString("sha256")).put("runId",proof.getString("runId")).put("coordinatorEpoch",proof.getInt("coordinatorEpoch")));else write(task);
            db.setTransactionSuccessful();return new JSONObject().put("kind","task_result").put("id",id);
        }finally{db.endTransaction();}
    }
    /** Native original-store carrier. JSON metadata cannot restore this object or grant an audience. */
    public final class CompletionFact {
        public final String factId,completionFingerprint,taskId,runId;
        public final int epoch;public final long eventSeq;
        private final JSONObject original;private final TaskStore issuer=TaskStore.this;
        private CompletionFact(JSONObject value)throws Exception{
            original=new JSONObject(value.toString());factId=value.getString("factId");completionFingerprint=value.getString("completionFingerprint");
            taskId=value.getString("taskId");runId=value.getString("runId");epoch=value.getInt("epoch");eventSeq=value.getLong("eventSeq");
        }
        public JSONObject metadata()throws Exception{return new JSONObject(original.toString());}
        /** Pure original SQL only; callers must not hold the channel/outbox/Book monitor. */
        public void checkCurrent()throws Exception{checkCompletionFact(this);}
    }
    /** Explicit native owner picker. Old unknown confirmations require a fresh confirmation;
     * a completed model status alone cannot mint an externally deliverable result. */
    public synchronized CompletionFact completionFactForOwner(String taskId)throws Exception{
        db.beginTransaction();try{
            JSONObject projection=completionProjection(required(taskId));String stateHash=capabilityFingerprint(projection);
            String id=dev.pocketengine.features.automation.CompletionFacts.id(taskId,projection.getString("runId"),projection.getInt("epoch"),stateHash);
            CompletionFact old=storedCompletionFact(id);
            if(old!=null){requireCompletionCurrent(old);db.setTransactionSuccessful();return old;}
            long previous=TaskAuditStore.lastSequence(db,taskId);if(previous==Long.MAX_VALUE)throw new IllegalStateException("原完成事件序号已耗尽");
            JSONObject fact=new JSONObject(projection.toString()).put("format",dev.pocketengine.features.automation.CompletionFacts.FORMAT)
                    .put("factId",id).put("eventSeq",previous+1).put("stateFingerprint",stateHash)
                    .put("executionAuthorized",false).put("audienceAuthorized",false).put("businessSuccessIndependentlyVerified",false);
            fact.put("completionFingerprint",capabilityFingerprint(fact));
            JSONObject event=new JSONObject().put("type","native_completion_fact").put("fact",fact).put("at",System.currentTimeMillis());
            long seq=TaskAuditStore.append(db,taskId,event);if(seq!=fact.getLong("eventSeq"))throw new IllegalStateException("完成事实未写入原事件序列");
            saveEvidence("completion.fact.",id,new JSONObject().put("taskId",taskId).put("eventSeq",seq).put("completionFingerprint",fact.getString("completionFingerprint")).put("eventFingerprint",capabilityFingerprint(event)));
            CompletionFact issued=new CompletionFact(fact);requireCompletionCurrent(issued);db.setTransactionSuccessful();return issued;
        }finally{db.endTransaction();}
    }
    /** Cold restoration only from this original SQL event and pointer, never caller JSON. */
    public synchronized CompletionFact originalCompletionFact(String factId)throws Exception{
        if(!dev.pocketengine.features.automation.CompletionFacts.uuid(factId))throw new IllegalArgumentException("完成事实标识无效");
        db.beginTransaction();try{CompletionFact fact=storedCompletionFact(factId);if(fact==null)throw new IllegalStateException("原完成事实不存在");requireCompletionCurrent(fact);db.setTransactionSuccessful();return fact;}finally{db.endTransaction();}
    }
    private CompletionFact storedCompletionFact(String id)throws Exception{
        JSONObject pointer;try(Cursor row=db.rawQuery("SELECT CASE WHEN length(CAST(value AS BLOB))<=2048 THEN value END FROM meta WHERE key=?",new String[]{"completion.fact."+id})){
            if(!row.moveToFirst())return null;if(row.isNull(0))throw new SecurityException("原完成定位不可用");pointer=new JSONObject(row.getString(0));
        }
        String task=pointer.getString("taskId");long seq=dev.pocketengine.features.automation.CompletionFacts.integer(pointer,"eventSeq",1);
        JSONObject event;try(Cursor row=db.rawQuery("SELECT event_type,CASE WHEN body_bytes<=32768 AND body_bytes>=0 THEN event_json END FROM task_events WHERE task_id=? AND event_order=? AND native_seq=?",new String[]{task,Long.toString(seq),Long.toString(seq)})){
            if(!row.moveToFirst()||!"native_completion_fact".equals(row.getString(0))||row.isNull(1))throw new SecurityException("原完成事件已删除、改变或超出范围");event=new JSONObject(row.getString(1));
        }
        JSONObject value=event.getJSONObject("fact");String fingerprint=value.getString("completionFingerprint");JSONObject unsigned=new JSONObject(value.toString());unsigned.remove("completionFingerprint");
        if(!dev.pocketengine.features.automation.CompletionFacts.FORMAT.equals(value.optString("format"))||!id.equals(value.optString("factId"))
                ||!task.equals(value.optString("taskId"))||seq!=value.optLong("eventSeq")||seq!=event.optLong("seq")
                ||!fingerprint.equals(pointer.optString("completionFingerprint"))||!fingerprint.equals(capabilityFingerprint(unsigned))
                ||!pointer.optString("eventFingerprint").equals(capabilityFingerprint(event)))throw new SecurityException("原完成事实身份或内容已变化");
        return new CompletionFact(value);
    }
    private synchronized void checkCompletionFact(CompletionFact fact)throws Exception{
        if(fact==null)throw new SecurityException("缺少原完成事实");
        db.beginTransaction();try{requireCompletionCurrent(fact);db.setTransactionSuccessful();}finally{db.endTransaction();}
    }
    private void requireCompletionCurrent(CompletionFact fact)throws Exception{
        if(fact==null||fact.issuer!=this)throw new SecurityException("完成事实不属于当前原生 SQL 实例");
        // An instance from a different TaskStore can never be upgraded by copying its metadata.
        CompletionFact saved=storedCompletionFact(fact.factId);
        if(saved==null||!saved.completionFingerprint.equals(fact.completionFingerprint)||!canonical(saved.original).equals(canonical(fact.original)))throw new SecurityException("原完成事实不可恢复或已改变");
        JSONObject current=completionProjection(required(fact.taskId));
        if(!fact.original.getString("stateFingerprint").equals(capabilityFingerprint(current)))throw new SecurityException("原任务、完成来源或准确成果版本已变化，未发送旧结果");
    }
    private JSONObject completionProjection(JSONObject task)throws Exception{
        requirePrivateOwner(task);rejectChannelParent(task);String id=task.getString("id"),run=task.optString("runId");int epoch=task.optInt("coordinatorEpoch");
        if(!"completed".equals(task.optString("status"))||task.optBoolean("cancelRequested")||hasUnresolved(id)
                ||!dev.pocketengine.features.automation.CompletionFacts.uuid(run)||epoch<1)throw new SecurityException("原任务没有当前可核的完成结果");
        try(Cursor row=db.rawQuery("SELECT epoch,status FROM runs WHERE task_id=? AND run_id=?",new String[]{id,run})){
            if(!row.moveToFirst()||row.getInt(0)!=epoch||!row.getString(1).matches("needs_confirmation|awaiting_user|completed|failed|cancelled|interrupted"))throw new SecurityException("原执行尚未持久结束");
        }
        JSONObject basis=null,verification=null;String originalSummary=null;
        try(Cursor row=db.rawQuery("SELECT CASE WHEN body_bytes<=32768 AND body_bytes>=0 THEN event_json END FROM task_events WHERE task_id=? AND event_type='user_verification' ORDER BY event_order DESC LIMIT 1",new String[]{id})){
            if(row.moveToFirst()){
                if(row.isNull(0))throw new SecurityException("原主人确认超出读取范围");JSONObject event=new JSONObject(row.getString(0)),snapshot=event.optJSONObject("memorySourceSnapshot");
                if(event.optBoolean("completed")&&!event.optBoolean("memorySourceSnapshotForgotten")&&snapshot!=null&&snapshot.optInt("version")==1
                        &&run.equals(event.optString("runId"))&&epoch==event.optInt("coordinatorEpoch")){
                    originalSummary=snapshot.optString("summary");basis=new JSONObject().put("kind","owner_verification").put("eventSeq",event.getLong("seq"))
                            .put("eventFingerprint",capabilityFingerprint(event)).put("verificationScope","owner_confirmed_result_summary_not_step_verification");
                }
            }
        }
        JSONObject machine=automationArtifactEvidence(task,null);
        if(machine!=null){verification=machine.getJSONObject("verification");requireCompletionArtifact(task,verification);if(basis==null){originalSummary=task.optString("summary");basis=new JSONObject().put("kind","native_workspace_artifact_standard").put("evidenceId",machine.getString("id"))
                .put("evidenceFingerprint",capabilityFingerprint(machine)).put("verificationScope","file_identity_readability_and_reviewed_heading_only");}}
        if(basis==null)throw new SecurityException("完成状态未绑定本轮原生核验；请主人重新确认原任务，不以旧摘要冒完成事实");
        String summary=dev.pocketengine.features.automation.CompletionFacts.summary(originalSummary);
        return new JSONObject().put("taskId",id).put("runId",run).put("epoch",epoch).put("ownerId","owner").put("spaceId","private")
                .put("summary",summary).put("summaryTruncated",originalSummary.length()>summary.length()).put("completionBasis",basis)
                .put("taskResultFingerprint",capabilityFingerprint(new JSONObject().put("summary",task.optString("summary")).put("evidence",task.optJSONObject("evidence"))))
                .put("artifactRefs",dev.pocketengine.features.automation.CompletionFacts.artifactRefs(verification));
    }
    private void requireCompletionArtifact(JSONObject task,JSONObject proof)throws Exception{
        String operation=proof.getString("operationId");
        try(Cursor row=db.rawQuery("SELECT run_id,tool,state,CASE WHEN length(CAST(receipt AS BLOB))<=32768 THEN receipt END FROM operations WHERE task_id=? AND operation_id=?",new String[]{task.getString("id"),operation})){
            if(!row.moveToFirst()||!task.getString("runId").equals(row.getString(0))||!"workspace_create_artifact".equals(row.getString(1))||!"receipt".equals(row.getString(2))||row.isNull(3))throw new SecurityException("原完成文件操作不可核对");
            JSONObject receipt=new JSONObject(row.getString(3));if(!proof.getString("artifactId").equals(receipt.optString("id"))||!proof.getString("sha256").equals(receipt.optString("sha256"))
                    ||!task.getString("id").equals(receipt.optString("taskId"))||!task.getString("runId").equals(receipt.optString("runId")))throw new SecurityException("完成文件不是原冻结操作的准确版本");
        }
    }
    /** Called only after the original ChannelCore has durably accepted this exact target/payload.
     * It records a link, not a second outbox or a sent/read/retry claim. */
    public synchronized JSONObject recordCompletionOutbox(CompletionFact fact,String outboxId,String payloadSha256,String targetSha256)throws Exception{
        if(outboxId==null||!outboxId.matches("[A-Za-z0-9_.:-]{1,160}")||!dev.pocketengine.features.automation.CompletionFacts.hash(payloadSha256)||!dev.pocketengine.features.automation.CompletionFacts.hash(targetSha256))throw new SecurityException("原出站收据身份不完整");
        db.beginTransaction();try{
            requireCompletionCurrent(fact);JSONObject link=new JSONObject().put("factId",fact.factId).put("completionFingerprint",fact.completionFingerprint)
                    .put("outboxId",outboxId).put("payloadSha256",payloadSha256).put("targetSha256",targetSha256);
            JSONObject old=completionOutboxLink(fact.factId);
            if(old!=null){if(!canonical(old).equals(canonical(link)))throw new SecurityException("完成事实已经关联另一份原出站内容，请查询原出站状态，不自动另发");db.setTransactionSuccessful();return old;}
            saveEvidence("completion.outbox.",fact.factId,link);TaskAuditStore.append(db,fact.taskId,new JSONObject().put("type","native_completion_outbox_link").put("link",link));
            db.setTransactionSuccessful();return new JSONObject(link.toString());
        }finally{db.endTransaction();}
    }
    public synchronized JSONObject completionOutboxLink(String factId)throws Exception{
        if(!dev.pocketengine.features.automation.CompletionFacts.uuid(factId))throw new IllegalArgumentException("完成事实标识无效");
        try(Cursor row=db.rawQuery("SELECT CASE WHEN length(CAST(value AS BLOB))<=4096 THEN value END FROM meta WHERE key=?",new String[]{"completion.outbox."+factId})){
            if(!row.moveToFirst())return null;if(row.isNull(0))throw new SecurityException("原出站关联不可用");return new JSONObject(row.getString(0));
        }
    }
    public synchronized boolean automationRunSettled(String taskId)throws Exception{
        JSONObject task=required(taskId);requirePrivateOwner(task);if(task.optString("runId").isEmpty()||task.optInt("coordinatorEpoch")<1||hasUnresolved(taskId))return false;
        try(Cursor row=db.rawQuery("SELECT status,epoch FROM runs WHERE task_id=? AND run_id=?",new String[]{taskId,task.getString("runId")})){return row.moveToFirst()&&row.getInt(1)==task.getInt("coordinatorEpoch")&&row.getString(0).matches("needs_confirmation|awaiting_user|completed|failed|cancelled|interrupted");}
    }
    /** Native owner picker only: a reference to an existing standalone command, never a new job. */
    public synchronized JSONObject automationRemoteWaitReference(String taskId)throws Exception{
        JSONObject task=required(taskId);requireRemoteOwner(task);rejectChannelParent(task);
        JSONObject refs=task.optJSONObject("externalRefs"),evidence=task.optJSONObject("evidence");
        if(task.has("parentTaskId")||!"submitJob".equals(task.optString("command"))||refs==null||evidence==null
                ||!"command".equals(refs.optString("worker"))||!"command".equals(evidence.optString("worker"))
                ||refs.optString("environmentId").isEmpty()||!refs.getString("environmentId").equals(evidence.optString("environmentId")))throw new SecurityException("请选择已有的主人独立电脑 command 任务；Agent 结束不等于进程结果标准");
        JSONObject original=remoteOwnerTarget(taskId);
        return dev.pocketengine.features.automation.PlanRemoteWait.standard(new JSONObject().put("kind","remote_command_exit")
                .put("taskId",taskId).put("runId",original.getString("runId")).put("coordinatorEpoch",original.getInt("coordinatorEpoch"))
                .put("operationId",original.getString("jobOperationId")).put("profileId",original.getString("profileId"))
                .put("serviceBase",original.getString("serviceBase")).put("jobId",original.getString("jobId"))
                .put("environmentId",original.getString("environmentId")).put("expectedExitCode",0));
    }
    public synchronized JSONArray automationRemoteWaitCandidates(int limit)throws Exception{
        if(limit<1||limit>20)throw new IllegalArgumentException("电脑等待来源数量无效");JSONArray result=new JSONArray();
        try(Cursor rows=db.rawQuery("SELECT document FROM tasks ORDER BY updated_at DESC LIMIT 200",null)){while(rows.moveToNext()&&result.length()<limit){
            JSONObject task=new JSONObject(rows.getString(0));if(!"remote".equals(task.optString("feature")))continue;
            try{JSONObject reference=automationRemoteWaitReference(task.getString("id"));result.put(new JSONObject().put("reference",reference).put("title",task.getString("goal")).put("observation",automationRemoteWaitObservation(reference)));}catch(SecurityException|IllegalArgumentException unavailable){}
        }}return result;
    }
    /** One exact native SQL receipt and run fence. This does not verify the command's business goal. */
    public synchronized JSONObject automationRemoteWaitObservation(JSONObject input)throws Exception{
        JSONObject reference=dev.pocketengine.features.automation.PlanRemoteWait.standard(input),result=new JSONObject().put("allowed",false).put("reason","remote_wait_unavailable").put("observedAt",System.currentTimeMillis()).put("authority","TaskStore");
        final JSONObject current;try{current=automationRemoteWaitReference(reference.getString("taskId"));}catch(SecurityException|IllegalArgumentException missing){return result;}
        if(!dev.pocketengine.features.automation.PlanRemoteWait.sameIdentity(reference,current))return result.put("reason","remote_wait_identity_changed");
        String id=reference.getString("taskId");JSONObject task=required(id);
        if(task.optBoolean("cancelRequested"))return result.put("reason","remote_wait_cancelled");
        if(hasUnresolved(id))return result.put("reason","remote_wait_unknown");
        JSONObject operation=remoteOperation(id,reference.getString("operationId"),"remote_job"),receipt=operation.optJSONObject("receipt"),args=operation.getJSONObject("arguments");
        if(receipt==null||!reference.getString("runId").equals(operation.getString("runId"))||!id.equals(args.optString("taskId"))||!reference.getString("runId").equals(args.optString("runId"))||reference.getInt("coordinatorEpoch")!=args.optInt("coordinatorEpoch",-1))return result;
        for(String key:new String[]{"profileId","serviceBase","operationId","environmentId"})if(!reference.getString(key).equals(receipt.optString(key)))return result.put("reason","remote_wait_identity_changed");
        if(!reference.getString("jobId").equals(receipt.optString("id"))||!"command".equals(receipt.optString("worker")))return result.put("reason","remote_wait_identity_changed");
        String status=receipt.optString("status");result.put("executorStatus",status);
        if(!operation.getString("state").equals("remote_job_receipt"))return result.put("reason",status.matches("unknown|not_found|submitting|interrupted")?"remote_wait_unknown":"remote_wait_pending");
        if(status.equals("cancelled"))return result.put("reason","remote_wait_cancelled");
        if(!status.matches("completed|failed"))return result.put("reason","remote_wait_pending");
        JSONObject evidence=task.optJSONObject("evidence");if(evidence==null||!status.equals(task.optString("executionStatus"))||!status.equals(evidence.optString("executorStatus")))return result;
        for(String key:new String[]{"id","profileId","serviceBase","operationId","environmentId","worker","exitCode"})if(!String.valueOf(receipt.opt(key)).equals(String.valueOf(evidence.opt(key))))return result;
        if(receipt.optBoolean("steeringUnknown")||receipt.optJSONArray("pendingApprovals")!=null&&receipt.getJSONArray("pendingApprovals").length()>0)return result.put("reason","remote_wait_unknown");
        try(Cursor pending=db.rawQuery("SELECT 1 FROM operations WHERE task_id=? AND tool='remote_message' AND state!='message_receipt' LIMIT 1",new String[]{id})){if(pending.moveToFirst())return result.put("reason","remote_wait_unknown");}
        try(Cursor run=db.rawQuery("SELECT status,epoch FROM runs WHERE task_id=? AND run_id=?",new String[]{id,reference.getString("runId")})){if(!run.moveToFirst()||run.getInt(1)!=reference.getInt("coordinatorEpoch")||!status.equals(run.getString(0)))return result;}
        Object exit=receipt.opt("exitCode");if(!(exit instanceof Integer||exit instanceof Long))return result.put("reason","remote_wait_exit_unavailable");
        long code=((Number)exit).longValue();result.put("exitCode",code);
        if(!status.equals("completed")||code!=0)return result.put("reason","remote_wait_failed");
        return result.put("allowed",true).put("reason","remote_wait_satisfied").put("evidence",new JSONObject().put("kind","remote_command_exit").put("reference",reference)
                .put("exitCode",0).put("receiptFingerprint",capabilityFingerprint(receipt)).put("verificationScope","original_command_process_exit_only"));
    }
    public synchronized boolean automationArtifactVerified(String taskId)throws Exception{return automationArtifactEvidence(required(taskId),null)!=null;}
    private JSONObject automationArtifactEvidence(JSONObject task,JSONObject binding)throws Exception{
        if(!"completed".equals(task.optString("status"))||hasUnresolved(task.getString("id")))return null;
        JSONArray refs=task.optJSONArray("automationResultRefs");for(int i=refs==null?-1:refs.length()-1;i>=0;i--)try(Cursor row=db.rawQuery("SELECT value FROM meta WHERE key=?",new String[]{"commitment.evidence."+refs.getString(i)})){
            if(!row.moveToFirst())continue;JSONObject evidence=new JSONObject(row.getString(0)),proof=evidence.optJSONObject("verification");
            if(!"native_workspace_artifact_standard".equals(evidence.optString("source"))||proof==null||!task.getString("id").equals(evidence.optString("taskId"))||!task.optString("runId").equals(proof.optString("runId"))||task.optInt("coordinatorEpoch")!=proof.optInt("coordinatorEpoch"))continue;
            if(binding==null||binding.optString("commitmentId").equals(evidence.optString("commitmentId"))&&binding.optLong("commitmentRevision")==evidence.optLong("commitmentRevision"))return evidence;
        }return null;
    }
    private JSONObject recordCommitmentEvidence(JSONObject commitment,String kind,String id,String taskId)throws Exception{
        JSONObject evidence=new JSONObject().put("kind",kind).put("id",id).put("ownerId","owner").put("spaceId","private").put("commitmentId",commitment.getString("id")).put("commitmentRevision",commitment.getLong("revision")).put("outcome","completed").put("recordedAt",System.currentTimeMillis()).put("taskId",taskId).put("source","native_owner_confirmation");
        saveEvidence("commitment.evidence.",id,evidence);return new JSONObject().put("kind",kind).put("id",id);
    }
    private void saveEvidence(String prefix,String id,JSONObject value){ContentValues row=new ContentValues();row.put("key",prefix+id);row.put("value",value.toString());db.insertOrThrow("meta",null,row);}
    public synchronized void recordFollowUpNotificationLink(String claimId,String commitmentId,long revision,String tag,int notificationId)throws Exception{
        JSONObject old=followUpNotificationLink(claimId);if(old!=null){if(!commitmentId.equals(old.optString("commitmentId"))||revision!=old.optLong("revision")||!tag.equals(old.optString("tag"))||notificationId!=old.optInt("notificationId"))throw new SecurityException("通知收据身份冲突");return;}
        saveEvidence("followup.notification.",claimId,new JSONObject().put("claimId",claimId).put("commitmentId",commitmentId).put("revision",revision).put("tag",tag).put("notificationId",notificationId).put("recordedAt",System.currentTimeMillis()));
    }
    public synchronized JSONObject followUpNotificationLink(String claimId)throws Exception{
        try(Cursor cursor=db.rawQuery("SELECT value FROM meta WHERE key=?",new String[]{"followup.notification."+claimId})){return cursor.moveToFirst()?new JSONObject(cursor.getString(0)):null;}
    }
    public synchronized void recordOwnerFollowUpReconciliation(String claimId,String outcome)throws Exception{
        if(!outcome.matches("notified|not_sent"))throw new IllegalArgumentException("请选择已通知或已核实未发出");
        saveEvidence("followup.owner.",UUID.randomUUID().toString(),new JSONObject().put("claimId",claimId).put("outcome",outcome).put("actorId","owner").put("spaceId","private").put("recordedAt",System.currentTimeMillis()));
    }
    public synchronized JSONObject resolveCommitmentEvidence(JSONObject scope,JSONObject commitment,String kind,String id)throws Exception{
        if(!"owner".equals(scope.optString("ownerId"))||!"private".equals(scope.optString("spaceId"))||!kind.matches("owner_confirmation|task_result"))return null;
        try(Cursor cursor=db.rawQuery("SELECT value FROM meta WHERE key=?",new String[]{"commitment.evidence."+id})){
            if(!cursor.moveToFirst())return null;JSONObject evidence=new JSONObject(cursor.getString(0));
            if(!kind.equals(evidence.optString("kind"))||!commitment.optString("id").equals(evidence.optString("commitmentId"))||commitment.optLong("revision")!=evidence.optLong("commitmentRevision"))return null;
            if(kind.equals("task_result")){JSONObject task=read(evidence.optString("taskId"));if(task==null||!task.optString("status").equals("completed")||hasUnresolved(task.getString("id")))return null;
                if("native_workspace_artifact_standard".equals(evidence.optString("source"))){JSONObject current=automationArtifactEvidence(task,new JSONObject().put("commitmentId",evidence.getString("commitmentId")).put("commitmentRevision",evidence.getLong("commitmentRevision")));if(current==null||!id.equals(current.optString("id")))return null;}
            }
            return new JSONObject(evidence.toString());
        }
    }
    /** A source state is one immutable SQL document read. Do not acquire admission after a Book lock. */
    public dev.pocketengine.features.memory.SourceRef.State resolveMemorySource(dev.pocketengine.features.memory.MemoryBook.Scope scope,String kind,String sourceId)throws Exception{
        if("task_evidence".equals(kind)&&dev.pocketengine.features.memory.TaskSavedEvidence.matches(sourceId))return dev.pocketengine.features.memory.TaskSavedEvidence.resolve(db,scope,sourceId);
        if(!kind.equals("task_evidence")||!sourceId.startsWith("task:")||!sourceId.endsWith(":verified_result"))return null;
        String id=sourceId.substring(5,sourceId.length()-16);
        // One native SQL observation, no TaskStore monitor acquired below MemoryBook.
        try(Cursor row=db.rawQuery("SELECT CASE WHEN length(CAST(t.document AS BLOB))<=1048576 THEN t.document END,e.native_seq,e.completed FROM tasks t LEFT JOIN task_events e ON e.task_id=t.task_id AND e.event_type='user_verification' WHERE t.task_id=? ORDER BY e.event_order DESC LIMIT 1",new String[]{id})){
            if(!row.moveToFirst())return null;if(row.isNull(0))throw new IllegalStateException("原任务来源元数据超过读取范围，未以摘要替代");JSONObject task=new JSONObject(row.getString(0));
            if(!scope.ownerId.equals(task.optString("actorId"))||!scope.spaceId.equals(task.optString("spaceId"))||row.isNull(1)||row.getLong(1)<1)return null;
            return new dev.pocketengine.features.memory.SourceRef.State(new dev.pocketengine.features.memory.SourceRef(kind,sourceId,row.getLong(1),scope.ownerId,scope.spaceId),row.isNull(2)||row.getInt(2)!=1);
        }
    }
    /** Native owner metadata/selection only. These methods are absent from model/feature commands. */
    public synchronized JSONObject savedMemorySources(String taskId,long beforeEventOrder,int limit)throws Exception{
        return dev.pocketengine.features.memory.TaskSavedEvidence.list(db,taskId,beforeEventOrder,limit);
    }
    public synchronized dev.pocketengine.features.memory.SourceRef selectSavedMemorySource(String taskId,String runId,long eventSeq,int offset,int chars)throws Exception{
        db.beginTransaction();try{
            var ref=dev.pocketengine.features.memory.TaskSavedEvidence.select(db,taskId,runId,eventSeq,offset,chars);
            db.setTransactionSuccessful();return ref;
        }finally{db.endTransaction();}
    }
    /** Pure SQL exact original event; never enters an attachment Book while MemoryBook is held. */
    public JSONObject savedMemorySourceEvent(dev.pocketengine.features.memory.SourceRef expected)throws Exception{
        return dev.pocketengine.features.memory.TaskSavedEvidence.readEvent(db,expected);
    }
    /** Full journal recovery query; the recent-task UI limit must never hide pending cleanup. */
    public synchronized JSONArray memoryCleanupTasks()throws Exception {
        JSONArray result=new JSONArray();
        try(Cursor cursor=db.rawQuery("SELECT document FROM tasks ORDER BY updated_at ASC",null)){
            while(cursor.moveToNext()){
                JSONObject task=new JSONObject(cursor.getString(0));
                if(!"memory".equals(task.optString("feature"))||!"sourceCleanup".equals(task.optString("command"))
                        ||task.optString("status").matches("completed|failed|cancelled"))continue;
                // Return the existing journal, not a new cleanup authority. Native caller rechecks scope/intent.
                result.put(get(task.getString("id"),false));
            }
        }return result;
    }
    /** Exact immutable confirmation snapshot, never a later task summary or a model-selected body. */
    public synchronized dev.pocketengine.features.KnowledgeLearningWorkflow.SourceDocument readMemorySource(dev.pocketengine.features.memory.SourceRef expected)throws Exception {
        if(expected==null)throw new IllegalArgumentException("缺少任务来源");
        dev.pocketengine.features.memory.MemoryBook.Scope scope=dev.pocketengine.features.memory.MemoryBook.Scope.from(new JSONObject().put("ownerId",expected.ownerId).put("spaceId",expected.spaceId));
        dev.pocketengine.features.memory.SourceRef.State current=resolveMemorySource(scope,expected.kind,expected.sourceId);
        if(current==null||current.deleted||!expected.equals(current.ref))throw new IllegalStateException("任务确认已变化、被撤销或不可访问");
        String id=expected.sourceId.substring(5,expected.sourceId.length()-16);JSONObject event=TaskAuditStore.latest(db,id,"user_verification");
        if(event==null||event.optLong("seq")!=expected.revision)throw new IllegalStateException("任务确认记录已不可用");
        JSONObject snapshot=event.optJSONObject("memorySourceSnapshot");
        if(snapshot==null||snapshot.optInt("version")!=1||event.optBoolean("memorySourceSnapshotForgotten"))throw new IllegalStateException("该次确认没有可用的历史正文快照；请重新核对并确认任务，不能使用当前摘要替代");
        return new dev.pocketengine.features.KnowledgeLearningWorkflow.SourceDocument(expected,"主人确认时的任务摘要（非逐步执行证明）",snapshot.toString());
    }
    /** Committed native forget cleanup; replay through the existing host receipt until acknowledged. */
    public synchronized JSONObject forgetMemorySourceSnapshot(dev.pocketengine.features.memory.SourceRef through)throws Exception {
        if(through!=null&&"task_evidence".equals(through.kind)&&dev.pocketengine.features.memory.TaskSavedEvidence.matches(through.sourceId)){
            // The Book's original exact source-forget barrier already blocks later model recall.
            // Raw owner audit/document bytes remain original facts, not derived summary snapshots.
            var address=dev.pocketengine.features.memory.TaskSavedEvidence.address(through.sourceId);if(!"owner".equals(through.ownerId)||!"private".equals(through.spaceId)||through.revision!=address.eventSeq)throw new SecurityException("原保存来源的遗忘回执身份无效");
            return new JSONObject().put("removedSnapshots",0).put("status","complete").put("originalSavedEvidencePreserved",true);
        }
        if(through==null||!"task_evidence".equals(through.kind)||!through.sourceId.startsWith("task:")||!through.sourceId.endsWith(":verified_result"))throw new IllegalArgumentException("任务来源无效");
        JSONObject task=read(through.sourceId.substring(5,through.sourceId.length()-16));
        if(task==null)return new JSONObject().put("removedSnapshots",0).put("status","complete");
        if(!through.ownerId.equals(task.optString("actorId"))||!through.spaceId.equals(task.optString("spaceId")))throw new SecurityException("任务来源不能跨空间");
        int removed;db.beginTransaction();try{removed=TaskAuditStore.retireSnapshots(db,task.getString("id"),through.revision,true);write(task);db.setTransactionSuccessful();}finally{db.endTransaction();}
        return new JSONObject().put("removedSnapshots",removed).put("status","complete");
    }
    private static String snapshotText(String text,int max){
        if(text.length()<=max)return text;int end=Character.isHighSurrogate(text.charAt(max-1))?max-1:max;return text.substring(0,end)+"…";
    }
    /** Original automation rule overlap is decided in this same task transaction, not by a
     * stale notification snapshot. Unknown/paused/failed work is not silently considered done. */
    private void requireReminderNonOverlap(String rule,String except,String policy)throws Exception{
        if("allow".equals(policy)||rule==null||rule.isEmpty())return;
        if(!java.util.Set.of("skip","buffer_one").contains(policy)||!rule.matches("[a-f0-9-]{36}"))throw new SecurityException("原提醒重叠策略或身份无效");
        int scanned=0;try(Cursor candidates=db.rawQuery("SELECT task_id,length(CAST(document AS BLOB)) FROM tasks WHERE document LIKE ? ORDER BY updated_at DESC",new String[]{"%"+rule+"%"})){
            while(candidates.moveToNext()){
                if(++scanned>500)throw new SecurityException("原提醒历史超出本次核对窗口，未推定没有在途任务");String id=candidates.getString(0);if(id.equals(except))continue;
                if(candidates.getLong(1)>1_048_576L)throw new SecurityException("原提醒任务来源超出读取窗口");JSONObject other=required(id),origin=other.optJSONObject("origin");
                if(origin==null||origin.has("planId")||!"automation".equals(origin.optString("kind"))||!rule.equals(other.optString("automationRuleId")))continue;
                requirePrivateOwner(other);if(!other.optString("status").matches("completed|cancelled|canceled|stopped"))throw new SecurityException("同一原提醒仍有待核对任务；未并行重派，先处理原任务");
            }
        }
    }
    public synchronized JSONObject createReminderTask(JSONObject request,String mode)throws Exception{
        JSONObject origin=request.getJSONObject("origin");String occurrence=origin.getString("occurrenceId");if(!origin.getString("kind").equals("automation")||occurrence.isEmpty()||occurrence.length()>120)throw new IllegalArgumentException("提醒来源无效");
        boolean capability=origin.has("planId")&&"capabilities".equals(mode);
        if(!mode.equals("main_screen")&&!mode.equals("background")&&!capability)throw new IllegalArgumentException("无效执行模式");
        String goal=request.getString("goal");if(goal.isBlank()||goal.length()>4000)throw new IllegalArgumentException("目标应为1–4000字");
        long revision=request.has("ruleRevision")?request.getLong("ruleRevision"):request.optLong("revision");if(revision<1)throw new IllegalArgumentException("提醒版本无效");
        JSONObject source=new JSONObject(origin.toString()).put("ruleId",request.optString("ruleId")).put("ruleRevision",revision);
        // The occurrence and base task become visible together. Different TaskStore instances
        // serialize this lookup/create through SQLite too; an ACK loss never leaves an orphan.
        db.beginTransaction();try{
            JSONObject existing=automationTask(occurrence);
            if(existing!=null){JSONObject old=existing.getJSONObject("origin");
                if(!goal.equals(existing.optString("goal"))||!mode.equals(existing.optString("executionMode"))||!canonical(source).equals(canonical(old)))throw new SecurityException("同一发生身份不能换目标、执行位置或来源");
                db.setTransactionSuccessful();return existing.put("created",false);
            }
            if(!origin.has("planId"))requireReminderNonOverlap(request.optString("ruleId"),"",request.optString("overlapPolicy","buffer_one"));
            if(request.has("remoteWait")&&!automationRemoteWaitObservation(request.getJSONObject("remoteWait")).optBoolean("allowed"))throw new SecurityException("原电脑等待条件当前未满足，未创建后继任务");
            JSONObject task=base(UUID.randomUUID().toString(),goal,capability?"assistant":"phone",mode)
                    .put("origin",source).put("automationRuleId",request.optString("ruleId")).put("automationRevision",revision).put("ruleRevision",revision)
                    .put("automationOverlapPolicy",request.optString("overlapPolicy","buffer_one"));
            if(capability)task.put("conversationId","");else task.put("displayId",mode.equals("background")?-1:0);
            if(origin.has("planId"))task.put("automationScope",automationScope(request));
            write(task);db.setTransactionSuccessful();return copy(task,false).put("created",true);
        }finally{db.endTransaction();}
    }
    private static JSONObject automationScope(JSONObject request)throws Exception{
        JSONObject scope=new JSONObject().put("planId",request.getString("planId")).put("commitmentId",request.getString("commitmentId")).put("allowPrivateArtifactFreeze",request.optBoolean("allowPrivateArtifactFreeze"))
                .put("verification",new JSONObject(request.getJSONObject("verification").toString()));
        if(request.has("remoteWait"))scope.put("remoteWait",dev.pocketengine.features.automation.PlanRemoteWait.standard(request.getJSONObject("remoteWait")));
        if(request.optJSONObject("businessWait")!=null){if(!Boolean.TRUE.equals(request.opt("businessEventConsent")))throw new SecurityException("原来源触发尚未获主人明确同意");JSONObject wait=dev.pocketengine.features.automation.PlanBusinessWait.standard(request.getJSONObject("businessWait")),proof=request.getJSONObject("businessWaitEvidence");if(!dev.pocketengine.features.automation.PlanBusinessWait.same(wait,proof.optJSONObject("reference"))||!proof.optString("fingerprint").matches("[0-9a-f]{64}")||!proof.optString("payloadSha256").matches("[0-9a-f]{64}")||proof.optString("eventId").isEmpty())throw new SecurityException("原事件消费定位不完整，未登记后继目标");scope.put("businessWait",wait).put("businessWaitEvidence",new JSONObject(proof.toString()));}
        return scope;
    }
    /** Native current Book request only; does not grant any tool beyond the reviewed private file. */
    public synchronized void updateAutomationScope(String taskId,JSONObject request)throws Exception{
        JSONObject task=required(taskId);requirePrivateOwner(task);JSONObject origin=task.getJSONObject("origin");
        if(!"automation".equals(origin.optString("kind"))||!origin.optString("occurrenceId").equals(request.getString("occurrenceId"))||!origin.optString("planId").equals(request.getString("planId"))||!origin.optString("commitmentId").equals(request.getString("commitmentId"))||!task.getString("goal").equals(request.getString("goal")))throw new SecurityException("目标范围不属于此原任务");
        JSONObject next=automationScope(request),previous=task.optJSONObject("automationScope");
        if(previous!=null&&!capabilityFingerprint(previous.getJSONObject("verification")).equals(capabilityFingerprint(next.getJSONObject("verification"))))throw new SecurityException("原任务的核验目标不能借范围刷新更换");
        if(previous!=null&&!dev.pocketengine.features.automation.PlanRemoteWait.sameIdentity(previous.optJSONObject("remoteWait"),next.optJSONObject("remoteWait")))throw new SecurityException("原任务的电脑等待来源不能借范围刷新更换");
        if(next.optJSONObject("remoteWait")!=null&&!automationRemoteWaitObservation(next.getJSONObject("remoteWait")).optBoolean("allowed"))throw new SecurityException("原电脑等待条件已变化，请先核对原任务");
        if(previous!=null&&(!dev.pocketengine.features.automation.PlanBusinessWait.same(previous.optJSONObject("businessWait"),next.optJSONObject("businessWait"))||!java.util.Objects.equals(previous.optJSONObject("businessWaitEvidence")==null?null:capabilityFingerprint(previous.getJSONObject("businessWaitEvidence")),next.optJSONObject("businessWaitEvidence")==null?null:capabilityFingerprint(next.getJSONObject("businessWaitEvidence")))))throw new SecurityException("原目标已消费的入站事实不能在范围刷新时替换");
        if(!next.optBoolean("allowPrivateArtifactFreeze")){JSONArray approvals=task.optJSONArray("approvals");for(int i=0;approvals!=null&&i<approvals.length();i++){JSONObject approval=approvals.getJSONObject(i);if("reviewed_native_plan_private_file_scope".equals(approval.optString("source"))&&approval.optString("state").matches("pending|approved"))approval.put("state","invalidated").put("reason","plan_scope_revoked");}}
        task.put("automationScope",next);write(task);
    }
    /** One copy of the exact private goal file can use its reviewed plan scope; other writes stay pending. */
    public synchronized void approveAutomationArtifactFreeze(String taskId,String runId,JSONObject args)throws Exception{
        JSONObject task=required(taskId);requirePrivateOwner(task);JSONObject scope=task.optJSONObject("automationScope");
        if(scope==null||!scope.optBoolean("allowPrivateArtifactFreeze")||!runId.equals(task.optString("runId"))||task.optBoolean("cancelRequested")||!task.optString("status").matches("preparing|running"))return;
        JSONObject standard=scope.getJSONObject("verification");if(!standard.optString("kind").equals("workspace_artifact")||!standard.getString("projectId").equals(args.optString("projectId"))||!standard.getString("path").equals(args.optString("path")))return;
        try(Cursor prior=db.rawQuery("SELECT receipt FROM operations WHERE task_id=? AND tool='workspace_create_artifact' AND state='receipt' AND receipt IS NOT NULL",new String[]{taskId})){while(prior.moveToNext())if(!new JSONObject(prior.getString(0)).optString("id").isEmpty())return;}
        String capability=canonical(new JSONObject().put("feature","workspace").put("command","createArtifact")),argumentsHash=capabilityFingerprint(args);JSONArray approvals=task.optJSONArray("approvals");if(approvals==null)approvals=new JSONArray();long now=System.currentTimeMillis();
        for(int i=0;i<approvals.length();i++){JSONObject approval=approvals.getJSONObject(i);if(!runId.equals(approval.optString("runId"))||!"workspace_create_artifact".equals(approval.optString("tool"))||!argumentsHash.equals(approval.optString("argumentsHash"))||!capability.equals(approval.optString("capabilityKey"))||approval.optLong("expiresAt")<=now)continue;
            if(approval.optString("state").equals("denied")||approval.optString("state").equals("approved"))return;
            if(approval.optString("state").equals("pending")){approval.put("state","approved").put("source","reviewed_native_plan_private_file_scope");task.put("approvals",approvals);write(task);return;}
        }
        if(approvals.length()>=100)throw new IllegalStateException("原任务审批记录已满");
        approvals.put(new JSONObject().put("id",UUID.randomUUID().toString()).put("runId",runId).put("tool","workspace_create_artifact").put("capability",new JSONObject().put("feature","workspace").put("command","createArtifact")).put("capabilityKey",capability)
                .put("arguments",approvalArguments(args)).put("argumentsHash",argumentsHash).put("state","approved").put("expiresAt",now+300_000L).put("source","reviewed_native_plan_private_file_scope"));task.put("approvals",approvals);write(task);
    }
    /** Same reviewed original run after an owner pause; extend time, never operation/tool budgets. */
    public synchronized void renewAutomationRun(String id,String run,int epoch,RunControl original,long validUntil)throws Exception{
        db.beginTransaction();try{
            JSONObject task=required(id);requirePrivateOwner(task);JSONObject origin=task.optJSONObject("origin");long now=System.currentTimeMillis();
            if(origin==null||!"automation".equals(origin.optString("kind"))||origin.optString("planId").isEmpty()||original==null||!original.isPaused()||original.isCancelled()||!original.matchesHostRun(id,run,epoch)||!run.equals(task.optString("runId"))||epoch!=task.optInt("coordinatorEpoch")||hasUnresolved(id)||validUntil<=now||task.optBoolean("cancelRequested"))throw new SecurityException("原计划执行暂不能继续，未更新授权");
            JSONObject granted=grant(run);if(!"owner".equals(granted.optString("principalId"))||!"private".equals(granted.optString("spaceId")))throw new SecurityException("原任务授权身份无效");
            long expires=Math.min(validUntil,now+600_000L);
            if("phone".equals(task.optString("kind")))try(Cursor lease=db.rawQuery("SELECT run_id,epoch FROM leases WHERE resource_id='phone.local'",null)){
                if(!lease.moveToFirst()||!run.equals(lease.getString(0))||epoch!=lease.getInt(1))throw new SecurityException("手机执行位置已变化，请核对原任务");ContentValues update=new ContentValues();update.put("expires_at",expires);db.update("leases",update,"resource_id=? AND run_id=? AND epoch=?",new String[]{"phone.local",run,String.valueOf(epoch)});
            }
            granted.put("expiresAt",expires);ContentValues update=new ContentValues();update.put("grant_json",granted.toString());update.put("updated_at",now);if(db.update("runs",update,"task_id=? AND run_id=? AND epoch=?",new String[]{id,run,String.valueOf(epoch)})!=1)throw new SecurityException("原执行已变化");
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
    /** Native typed condition exit. Retain a SQL receipt for this exact original run and budget. */
    public synchronized void endAutomationConditionWait(String id,String run,RunControl original,String summary,JSONObject evidence)throws Exception{
        db.beginTransaction();try{
            JSONObject task=required(id),origin=task.optJSONObject("origin");requirePrivateOwner(task);
            RunControl.ConditionWaitException waiting=original==null?null:original.conditionWait();
            if(origin==null||!"automation".equals(origin.optString("kind"))||origin.optString("planId").isEmpty()||original==null
                    ||!run.equals(task.optString("runId"))||!original.matchesHostRun(id,run,task.optInt("coordinatorEpoch")))throw new SecurityException("条件等待不属于这个原执行");
            if(original.isCancelled()||task.optBoolean("cancelRequested")){endAttempt(id,run,"cancelled","已停止后续派发；已有动作和结果保留",evidence);db.setTransactionSuccessful();return;}
            if(waiting==null||!original.isPaused())throw new SecurityException("没有这个原执行的条件等待");
            endAttempt(id,run,"paused",summary,evidence);task=required(id);
            if(!"paused".equals(task.optString("status"))||hasUnresolved(id)){db.setTransactionSuccessful();return;}
            JSONObject granted=grant(run);String receiptId=UUID.randomUUID().toString();boolean automatic=waiting.automatic()&&original.conditionWait()==waiting;
            JSONArray approvals=task.optJSONArray("approvals");for(int i=0;approvals!=null&&i<approvals.length();i++){JSONObject approval=approvals.getJSONObject(i);if(run.equals(approval.optString("runId"))&&approval.optString("state").matches("pending|denied"))automatic=false;}
            JSONObject receipt=new JSONObject().put("kind","automation_condition_wait").put("id",receiptId).put("taskId",id).put("runId",run).put("coordinatorEpoch",task.getInt("coordinatorEpoch"))
                    .put("occurrenceId",origin.getString("occurrenceId")).put("scopeFingerprint",capabilityFingerprint(new JSONObject().put("origin",origin).put("goal",task.getString("goal"))))
                    .put("reason",waiting.reason()).put("validUntil",waiting.validUntil()).put("automatic",automatic)
                    .put("stepCeiling",granted.getInt("stepCeiling")).put("remainingOperations",granted.getInt("remainingOperations")).put("recordedAt",System.currentTimeMillis());
            saveEvidence("automation.condition.wait.",receiptId,receipt);task.put("automationConditionWaitId",receiptId);
            appendEvent(task,new JSONObject().put("type","automation_condition_wait").put("receiptId",receiptId).put("reason",waiting.reason()).put("runId",run));
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
    /** Read the original native receipt only; no flag from a tool or model authorizes resumption. */
    public synchronized boolean automationConditionWaiting(String id)throws Exception{
        JSONObject receipt=automationConditionWait(required(id));return receipt!=null&&Boolean.TRUE.equals(receipt.opt("automatic"));
    }
    private JSONObject automationConditionWait(JSONObject task)throws Exception{
        requirePrivateOwner(task);String receiptId=task.optString("automationConditionWaitId");JSONObject origin=task.optJSONObject("origin");
        if(receiptId.isEmpty()||origin==null||!"automation".equals(origin.optString("kind"))||origin.optString("planId").isEmpty()||!task.optString("status").matches("paused|interrupted")||task.optBoolean("cancelRequested")||hasUnresolved(task.getString("id")))return null;
        JSONObject receipt;try(Cursor row=db.rawQuery("SELECT value FROM meta WHERE key=?",new String[]{"automation.condition.wait."+receiptId})){if(!row.moveToFirst())return null;receipt=new JSONObject(row.getString(0));}
        if(!"automation_condition_wait".equals(receipt.optString("kind"))||!receiptId.equals(receipt.optString("id"))||!task.getString("id").equals(receipt.optString("taskId"))||!task.optString("runId").equals(receipt.optString("runId"))
                ||task.optInt("coordinatorEpoch")!=receipt.optInt("coordinatorEpoch")||!origin.optString("occurrenceId").equals(receipt.optString("occurrenceId"))||receipt.optLong("validUntil")<=System.currentTimeMillis()
                ||!capabilityFingerprint(new JSONObject().put("origin",origin).put("goal",task.getString("goal"))).equals(receipt.optString("scopeFingerprint")))return null;
        try(Cursor row=db.rawQuery("SELECT status,epoch FROM runs WHERE task_id=? AND run_id=?",new String[]{task.getString("id"),task.getString("runId")})){if(!row.moveToFirst()||!"paused".equals(row.getString(0))||row.getInt(1)!=task.getInt("coordinatorEpoch"))return null;}
        JSONObject granted=grant(task.getString("runId"));if(!"owner".equals(granted.optString("principalId"))||!"private".equals(granted.optString("spaceId"))||!task.optString("executionMode").equals(granted.optString("mode"))
                ||granted.optInt("stepCeiling",-1)!=receipt.optInt("stepCeiling",-2)||granted.optInt("remainingOperations",-1)!=receipt.optInt("remainingOperations",-2))return null;
        return receipt;
    }
    /** Typed native group admission. No JSON entry point or owner/private fallback exists. */
    public synchronized JSONObject registerGroupTask(GroupHostPort.Request request,GroupHostPort.Authority authority)throws Exception {
        authority.check();validateGroupRequest(request);db.beginTransaction();try {
            JSONObject existing=read(request.taskId);if(existing!=null){requireGroupIdentity(existing,request);authority.check();db.setTransactionSuccessful();return get(request.taskId,false).put("created",false);}
            try(Cursor rows=db.rawQuery("SELECT document FROM tasks",null)){while(rows.moveToNext()){
                JSONObject task=new JSONObject(rows.getString(0)),source=task.optJSONObject("origin");if(source==null||!source.optString("kind").equals("group"))continue;
                if(request.requestId.equals(source.optString("requestId"))||request.event.provider.equals(source.optString("provider"))&&request.event.accountId.equals(source.optString("accountId"))&&request.event.roomId.equals(source.optString("roomId"))&&request.event.messageId.equals(source.optString("messageId")))throw new SecurityException("原群事件已属于另一任务，不能再次准入");
            }}
            JSONObject task=base(request.taskId,request.goal,"assistant","capabilities").put("actorId",request.actorId).put("spaceId",request.spaceId).put("origin",request.origin())
                    .put("groupInputArtifacts",request.inputArtifacts()).put("groupReservedTokens",0L).put("conversationId","").put("status","queued");
            authority.check();write(task);db.setTransactionSuccessful();return get(request.taskId,false).put("created",true);
        }finally{db.endTransaction();}
    }
    public synchronized JSONObject groupTask(GroupHostPort.Request request,GroupHostPort.Authority authority)throws Exception {
        authority.checkRead();JSONObject task=read(request.taskId);if(task==null){authority.checkRead();return null;}requireGroupIdentity(task,request);JSONObject result=get(request.taskId,false);authority.checkRead();return result;
    }
    public synchronized JSONObject startGroupAttempt(GroupHostPort.Request request,GroupHostPort.Authority authority,int allocation)throws Exception {
        return startGroupAttempt(request,authority,allocation,null);
    }
    /** Native reviewed recovery only; the original task, checkpoint and cumulative budgets stay authoritative. */
    public synchronized JSONObject recoverGroupAttempt(GroupHostPort.Request request,String expectedRunId,int expectedEpoch,long expectedRevision,
            GroupHostPort.Authority authority,int allocation)throws Exception {
        if(expectedRunId==null||!(expectedRunId.isEmpty()&&expectedEpoch==0||expectedRunId.matches("[a-f0-9-]{36}")&&expectedEpoch>0)||expectedRevision<1)
            throw new SecurityException("缺少刚核对的原群中断执行身份");
        return startGroupAttempt(request,authority,allocation,new GroupRecovery(expectedRunId,expectedEpoch,expectedRevision));
    }
    private static final class GroupRecovery {
        final String run;final int epoch;final long revision;
        GroupRecovery(String run,int epoch,long revision){this.run=run;this.epoch=epoch;this.revision=revision;}
    }
    private JSONObject startGroupAttempt(GroupHostPort.Request request,GroupHostPort.Authority authority,int allocation,GroupRecovery recovery)throws Exception {
        authority.check();db.beginTransaction();try {
            JSONObject task=required(request.taskId);requireGroupIdentity(task,request);
            boolean allowed=recovery==null?task.optString("status").matches("queued|paused|awaiting_user|needs_confirmation"):
                    task.optString("status").equals("interrupted")&&recovery.run.equals(task.optString("runId"))&&recovery.epoch==task.optInt("coordinatorEpoch")&&recovery.revision==task.getLong("groupStateRevision");
            if(!allowed||task.optBoolean("cancelRequested")||hasUnresolved(request.taskId))throw new SecurityException("原群任务不能重新派发；目标已变化、已结束或未知操作只能核对");
            try(Cursor runs=db.rawQuery("SELECT 1 FROM runs WHERE task_id=? AND status='running' LIMIT 1",new String[]{request.taskId})){if(runs.moveToFirst())throw new SecurityException("原群执行仍在运行或等待，不能创建第二执行");}
            if(request.capability.startsWith("media."))try(Cursor effects=db.rawQuery("SELECT receipt FROM operations WHERE task_id=? AND tool LIKE 'group_media_%'",new String[]{request.taskId})){while(effects.moveToNext()){if(effects.isNull(0)||!new JSONObject(effects.getString(0)).optString("code").equals("approval_required"))throw new SecurityException("原群媒体操作已建立，只能查询原编号");}}
            long now=System.currentTimeMillis();String runId=UUID.randomUUID().toString();int epoch=Math.addExact(task.optInt("coordinatorEpoch"),1);JSONObject previous=checkpointForTask(request.taskId);int consumed=previous.optInt("steps");
            int ceiling=Math.min(400,consumed+Math.max(1,Math.min(40,allocation)));if(consumed>=ceiling)throw new IllegalStateException("原群任务累计步骤预算已用尽");
            JSONObject grant=new JSONObject().put("principalId",request.actorId).put("spaceId",request.spaceId).put("mode","capabilities").put("nativeGroup",true).put("requestId",request.requestId)
                    .put("capability",request.capability).put("resource",request.resource).put("audience",request.audience).put("policyEpoch",request.policyEpoch)
                    .put("expiresAt",now+600_000L).put("stepCeiling",ceiling).put("remainingOperations",ceiling-consumed).put("tools",groupTools(request.capability));
            ContentValues run=new ContentValues();run.put("run_id",runId);run.put("task_id",request.taskId);run.put("epoch",epoch);run.put("status","running");run.put("checkpoint",previous.toString());run.put("grant_json",grant.toString());run.put("created_at",now);run.put("updated_at",now);db.insertOrThrow("runs",null,run);
            JSONArray approvals=task.optJSONArray("approvals");if(approvals!=null)for(int i=0;i<approvals.length();i++){
                JSONObject approval=approvals.getJSONObject(i);
                if(recovery!=null&&approval.optString("state").equals("pending"))approval.put("state","invalidated").put("reason","execution_recovered");
                if(approval.optString("state").equals("approved")&&approval.optLong("expiresAt")>now)approval.put("runId",runId);
            }
            task.put("runId",runId).put("coordinatorEpoch",epoch).put("status","preparing").put("stepCeiling",ceiling).put("cancelRequested",false);authority.check();write(task);db.setTransactionSuccessful();return get(request.taskId,false);
        }finally{db.endTransaction();}
    }
    /** Strict pre-attempt check for an already-reserved native recovery; not dispatch authority. */
    public synchronized void checkGroupRecoveryAdmission(GroupHostPort.Request request,RunControl control,String expectedRunId,int expectedEpoch,long expectedRevision)throws Exception {
        JSONObject task=required(request.taskId);requireGroupIdentity(task,request);
        if(control.hasHostRun()||!control.matchesHostRun(request.taskId,"",0)||!task.optString("status").equals("interrupted")
                ||!task.optString("runId").equals(expectedRunId)||task.optInt("coordinatorEpoch")!=expectedEpoch||task.optLong("groupStateRevision")!=expectedRevision
                ||task.optBoolean("cancelRequested")||hasUnresolved(request.taskId))throw new SecurityException("原群恢复准入目标或控制已失效");
    }
    /** Retained RunControl callback: never invokes that same callback recursively. */
    public synchronized void checkGroupTaskControl(GroupHostPort.Request request,RunControl control)throws Exception {
        JSONObject task=required(request.taskId);requireGroupIdentity(task,request);if(task.optBoolean("cancelRequested"))throw new SecurityException("原群任务已停止");
        if(control.hasHostRun()){
            if(!control.matchesHostRun(request.taskId,task.optString("runId"),task.optInt("coordinatorEpoch"))||!task.optString("status").matches("preparing|running|paused"))throw new SecurityException("原群执行代次或状态已失效");
            checkGroupRun(request,task.getString("runId"),task.getInt("coordinatorEpoch"),false);
        }else if(!task.optString("status").matches("queued|paused|awaiting_user|needs_confirmation"))throw new SecurityException("原群任务不允许新的控制器");
    }
    public synchronized void reserveGroupModelTokens(GroupHostPort.Request request,String runId,int epoch,long charge,GroupHostPort.Authority authority)throws Exception {
        authority.check();if(charge<1)throw new IllegalArgumentException("模型额度预留必须为正数");db.beginTransaction();try {
            checkGroupRun(request,runId,epoch,true);JSONObject task=required(request.taskId);long reserved=task.optLong("groupReservedTokens");
            if(reserved<0||charge>request.maxTokens||reserved>request.maxTokens-charge)throw new IllegalStateException("群任务累计模型额度已用尽；未发出新的模型请求");
            task.put("groupReservedTokens",Math.addExact(reserved,charge));authority.check();write(task);db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
    public RunJournal groupJournal(GroupHostPort.Request request,String runId,int epoch,RunControl control,GroupHostPort.Authority authority){return new RunJournal(){
        @Override public InferenceCapture.Target inferenceTarget(InferenceReceipt.Purpose purpose,String memberId)throws Exception{
            return nativeInferenceTarget(request.taskId,runId,epoch,purpose,memberId,()->{
                control.check();authority.checkRead();checkGroupRun(request,runId,epoch,true);
                if(control.isPaused())throw new IllegalStateException("原群执行已暂停，未发出模型请求");
            });
        }
        public JSONObject restore()throws Exception {synchronized(TaskStore.this){control.check();authority.checkRead();checkGroupRun(request,runId,epoch,false);if(hasUnresolved(request.taskId))throw new SecurityException("原群动作结果未知，只能查询原编号");return TaskStore.this.checkpoint(runId).put("feedback",required(request.taskId).optString("feedback")).put("operationResults",get(request.taskId,false).getJSONArray("operations"));}}
        public void checkpoint(JSONArray messages,int steps,JSONObject observation)throws Exception {synchronized(TaskStore.this){control.check();authority.checkRead();checkGroupRun(request,runId,epoch,false);JSONObject value=new JSONObject().put("messages",messages).put("steps",steps).put("observation",sanitize(observation==null?new JSONObject():observation));String encoded=value.toString();if(encoded.length()>4_000_000)throw new IllegalStateException("原群上下文超过上限");ContentValues row=new ContentValues();row.put("checkpoint",encoded);row.put("updated_at",System.currentTimeMillis());if(db.update("runs",row,"run_id=? AND task_id=? AND epoch=?",new String[]{runId,request.taskId,String.valueOf(epoch)})!=1)throw new SecurityException("原群执行不存在");}}
        public String beginOperation(String tool,JSONObject args)throws Exception {synchronized(TaskStore.this){control.check();authority.check();db.beginTransaction();try {
            checkGroupRun(request,runId,epoch,true);if(control.isPaused()||hasUnresolved(request.taskId)||!toolGranted(runId,tool))throw new SecurityException("原群动作未获授权、已暂停或仍有未知效果");
            if(tool.startsWith("group_media_"))try(Cursor rows=db.rawQuery("SELECT receipt FROM operations WHERE task_id=? AND tool LIKE 'group_media_%'",new String[]{request.taskId})){int count=0;while(rows.moveToNext()){if(rows.isNull(0)||!new JSONObject(rows.getString(0)).optString("code").equals("approval_required"))count++;}if(count>=request.maxMediaItems)throw new SecurityException("原群媒体额度已使用，不能再次生成");}
            JSONObject grant=grant(runId);int remaining=grant.optInt("remainingOperations");if(remaining<1)throw new IllegalStateException("原群操作预算已用尽");grant.put("remainingOperations",remaining-1);ContentValues budget=new ContentValues();budget.put("grant_json",grant.toString());db.update("runs",budget,"run_id=?",new String[]{runId});
            String operation=UUID.randomUUID().toString();ContentValues intent=new ContentValues();intent.put("operation_id",operation);intent.put("task_id",request.taskId);intent.put("run_id",runId);intent.put("tool",tool);intent.put("arguments",sanitize(args).toString());intent.put("state","prepared");intent.put("created_at",System.currentTimeMillis());authority.check();db.insertOrThrow("operations",null,intent);appendEvent(required(request.taskId),new JSONObject().put("type","operation_intent").put("operationId",operation).put("tool",tool).put("runId",runId));db.setTransactionSuccessful();return operation;
        }finally{db.endTransaction();}}}
        public void finishOperation(String operation,JSONObject receipt)throws Exception {synchronized(TaskStore.this){
            // A late real receipt is recorded even after revocation/cancellation, never dispatched again.
            JSONObject task=required(request.taskId);requireGroupIdentity(task,request);if(!runId.equals(task.optString("runId"))||epoch!=task.optInt("coordinatorEpoch"))throw new SecurityException("旧群执行回执不能覆盖新执行");
            JSONObject clean=sanitize(receipt);db.beginTransaction();try {
                try(Cursor old=db.rawQuery("SELECT state,receipt FROM operations WHERE operation_id=? AND task_id=? AND run_id=?",new String[]{operation,request.taskId,runId})){if(!old.moveToFirst())throw new SecurityException("回执不是原群操作");if(!old.getString(0).equals("prepared")){if(!old.isNull(1)&&canonical(new JSONObject(old.getString(1))).equals(canonical(clean))){db.setTransactionSuccessful();return;}throw new SecurityException("原群操作已有不同回执，只能核对");}}
                String code=clean.optString("code",clean.optString("error"));boolean unknown=code.contains("unknown")||code.equals("action_timeout")||clean.optString("status").equals("unknown")||clean.optString("state").contains("unknown")||clean.has("accepted")&&clean.isNull("accepted");
                ContentValues value=new ContentValues();value.put("state",unknown?"unknown":"receipt");value.put("receipt",clean.toString());if(db.update("operations",value,"operation_id=? AND task_id=? AND run_id=? AND state='prepared'",new String[]{operation,request.taskId,runId})!=1)throw new SecurityException("原群操作状态已变化");appendEvent(required(request.taskId),new JSONObject().put("type","operation_receipt").put("operationId",operation).put("receipt",clean));db.setTransactionSuccessful();
            }finally{db.endTransaction();}
        }}
    };}
    public synchronized JSONObject cancelGroupTask(GroupHostPort.Request request,String runId,int epoch,RunControl control,GroupHostPort.Authority authority)throws Exception {
        authority.check();db.beginTransaction();try {JSONObject task=required(request.taskId);requireGroupIdentity(task,request);
            if(runId==null||!runId.equals(task.optString("runId"))||epoch!=task.optInt("coordinatorEpoch")||control!=null&&!control.matchesHostRun(request.taskId,runId,epoch))throw new SecurityException("取消目标不是原群执行代次");
            String status=task.optString("status");task.put("cancelRequested",true);if(hasUnresolved(request.taskId))task.put("status","reconciling").put("summary","原群动作仍有未知效果，只核对原操作，未撤销或重发");
            else if(!status.matches("completed|failed|cancelled|interrupted"))task.put("status",control==null?"cancelled":"stopping").put("summary","已请求停止原群任务的后续派发，已有结果保留");
            authority.check();write(task);if(control!=null)control.cancel();db.setTransactionSuccessful();return get(request.taskId,false);
        }finally{db.endTransaction();}
    }
    private static void validateGroupRequest(GroupHostPort.Request request)throws Exception {
        if(request==null||!request.taskId.matches("[a-f0-9-]{36}")||!request.requestId.matches("[a-f0-9-]{36}")||!request.groupId.matches("[a-f0-9-]{36}")||!request.spaceId.equals("group:"+request.groupId)||request.actorId.equals("owner")||!request.actorId.matches("[a-f0-9-]{36}")||request.maxTokens<1||request.maxTokens>100_000||request.maxMediaItems<0||request.maxMediaItems>1||request.maxMediaBytes<1||request.maxMediaBytes>20*1024*1024L)throw new SecurityException("非原生群任务身份或额度");
        if(request.goal==null||request.goal.isBlank()||request.goal.length()>4000||!request.audience.matches("group|requester")||!request.visibility.matches("members|requester"))throw new SecurityException("群任务目标或受众无效");
        groupTools(request.capability);if(request.capability.startsWith("public.")&&!request.resource.equals("public")||request.capability.equals("group.files")&&!request.resource.equals(request.spaceId)||request.capability.startsWith("media.")&&request.resource.isEmpty())throw new SecurityException("群资源不是本次原生授权");
        JSONArray refs=request.inputArtifacts();for(int i=0;i<refs.length();i++){JSONObject ref=refs.getJSONObject(i);if(!request.spaceId.equals(ref.getString("spaceId"))||!ref.getString("artifactId").matches("[a-f0-9-]{36}")||ref.getLong("bytes")>request.maxMediaBytes)throw new SecurityException("群附件不是当前原生群空间或超过授权大小");}
    }
    private static void requireGroupIdentity(JSONObject task,GroupHostPort.Request request)throws Exception {
        validateGroupRequest(request);if(!task.optString("kind").equals("assistant")||!task.optString("executionMode").equals("capabilities")||!request.actorId.equals(task.optString("actorId"))||!request.spaceId.equals(task.optString("spaceId"))||!request.goal.equals(task.optString("goal"))||task.optJSONObject("origin")==null||!canonical(request.origin()).equals(canonical(task.getJSONObject("origin")))||task.optJSONArray("groupInputArtifacts")==null||!canonical(request.inputArtifacts()).equals(canonical(task.getJSONArray("groupInputArtifacts"))))throw new SecurityException("TaskStore任务不是此原生群来源");
    }
    private void checkGroupRun(GroupHostPort.Request request,String runId,int epoch,boolean dispatch)throws Exception {
        JSONObject task=required(request.taskId);requireGroupIdentity(task,request);if(!runId.equals(task.optString("runId"))||epoch!=task.optInt("coordinatorEpoch"))throw new SecurityException("原群执行代次已失效");
        if(dispatch&&(task.optBoolean("cancelRequested")||!task.optString("status").matches("preparing|running")||hasUnresolved(request.taskId)))throw new SecurityException("原群任务已停止、暂停或仍有未知效果");
        try(Cursor run=db.rawQuery("SELECT status FROM runs WHERE task_id=? AND run_id=? AND epoch=?",new String[]{request.taskId,runId,String.valueOf(epoch)})){if(!run.moveToFirst()||!run.getString(0).equals("running"))throw new SecurityException("原群执行已结束");}
        JSONObject grant=grant(runId);if(!grant.optBoolean("nativeGroup")||!request.actorId.equals(grant.optString("principalId"))||!request.spaceId.equals(grant.optString("spaceId"))||!request.requestId.equals(grant.optString("requestId"))||!request.capability.equals(grant.optString("capability"))||!request.resource.equals(grant.optString("resource"))||!request.audience.equals(grant.optString("audience"))||!request.policyEpoch.equals(grant.optString("policyEpoch"))||!grant.optString("mode").equals("capabilities")||grant.optLong("expiresAt")<=System.currentTimeMillis())throw new SecurityException("群原生执行授权已失效");
    }
    private static JSONArray groupTools(String capability)throws Exception {
        switch(capability){case "public.answer":return new JSONArray();case "public.search":return new JSONArray().put("group_public_search");case "group.files":return new JSONArray().put("group_files_list").put("group_file_read");case "media.image":return new JSONArray().put("group_media_image");case "media.audio":return new JSONArray().put("group_media_audio");case "media.video":return new JSONArray().put("group_media_video");default:throw new SecurityException("不是群安全能力");}
    }

    /** Native automation evidence lookup; the display's 200-row limit must never hide an original task. */
    public synchronized JSONObject automationTask(String occurrenceId)throws Exception{
        if(occurrenceId==null||occurrenceId.isEmpty()||occurrenceId.length()>120)throw new IllegalArgumentException("提醒发生身份无效");
        String found=null;
        try(Cursor rows=db.rawQuery("SELECT task_id,document FROM tasks",null)){while(rows.moveToNext()){
            JSONObject task=new JSONObject(rows.getString(1)),origin=task.optJSONObject("origin");
            if(origin!=null&&"automation".equals(origin.optString("kind"))&&occurrenceId.equals(origin.optString("occurrenceId"))){requirePrivateOwner(task);if(found!=null)throw new SecurityException("同一提醒对应多个任务，不能推断真实结果");found=rows.getString(0);}
        }}
        return found==null?null:get(found,false);
    }
    public synchronized JSONObject beginExternal(String feature,String command,String operationId,JSONObject input)throws Exception{
        return beginExternal(feature,command,operationId,input,null);
    }
    /** Conversation identity is supplied by the validated host, never copied from tool arguments. */
    public synchronized JSONObject beginExternal(String feature,String command,String operationId,JSONObject input,String conversationId)throws Exception{
        if(feature.equals("ssh")){
            String resource=input.optString("_resourceId");if(resource.isEmpty())throw new SecurityException("SSH缺少宿主连接身份");
            requireSshAccountAvailable(resource,operationId);
        }
        String goal=input.optString("goal",input.optString("prompt",input.optString("tool",command)));
        if(goal.length()>4000)goal=goal.substring(0,4000);
        JSONObject t=base(operationId,goal,feature,feature.equals("remote")?"remote":feature.equals("teams")?"analysis":"tool")
                .put("feature",feature).put("command",command).put("operationId",operationId).put("status","queued");
        if(conversationId!=null&&!conversationId.isEmpty())t.put("conversationId",conversationId);
        JSONObject refs=new JSONObject();for(String key:new String[]{"profileId","environmentId","teamId","id","tool","worker","model","readOnly"})if(input.has(key))refs.put(key,input.get(key));
        if(feature.equals("ssh")){refs.put("resourceId",input.getString("_resourceId"));if(dev.pocketengine.features.ssh.SshNativeRequest.guarded(command))refs.put("sshNativeRequest",dev.pocketengine.features.ssh.SshNativeRequest.describe(command,input));}
        t.put("externalRefs",refs).put("runId",operationId);write(t);return copy(t,false);
    }
    /** Same account identity survives configuration/credential revision changes. */
    public synchronized void requireSshAccountAvailable(String resource,String ownTask)throws Exception {
        if(!resource.matches("[a-f0-9]{64}"))throw new SecurityException("SSH账号资源身份无效");
        try(Cursor cursor=db.rawQuery("SELECT task_id,document FROM tasks",null)){while(cursor.moveToNext()){
            String id=cursor.getString(0);if(id.equals(ownTask))continue;JSONObject previous=new JSONObject(cursor.getString(1)),refs=previous.optJSONObject("externalRefs"),selection=previous.optJSONObject("sshSelection");
            boolean matches=refs!=null&&"ssh".equals(previous.optString("feature"))&&resource.equals(refs.optString("resourceId"))||selection!=null&&resource.equals(selection.optString("accountResource"));
            if(matches&&(hasUnresolved(id)||"ssh".equals(previous.optString("feature"))&&previous.optString("status").matches("queued|running|stopping|reconciling")))throw new IllegalStateException("该SSH账号仍有原执行或未决效果，请先核对原任务");
        }}
    }
    /** Original native SSH scope/currentness, not authority inferred from a JSON taskId. */
    public synchronized void requireSshNativeRequest(String id,String run,dev.pocketengine.features.ssh.SshNativeRequest request)throws Exception {
        java.util.Objects.requireNonNull(request);JSONObject task=required(id);requirePrivateOwner(task);JSONObject refs=task.optJSONObject("externalRefs"),saved=refs==null?null:refs.optJSONObject("sshNativeRequest"),scope=request.view();
        if(!"ssh".equals(task.optString("feature"))||!run.equals(task.optString("runId"))||!run.equals(task.optString("operationId"))||task.optInt("coordinatorEpoch",-1)!=0||!request.command().equals(task.optString("command"))
                ||saved==null||!request.requestHash().equals(saved.optString("requestHash"))||!scope.optString("profileId").equals(refs.optString("profileId"))||!scope.optString("profileBinding").equals(saved.optString("profileBinding")))throw new SecurityException("原SSH任务、准确请求或连接版本不匹配；不能凭任务编号派发");
        // This very queued external row is normal admission, not an unknown prior operation.
        if(task.optBoolean("cancelRequested")||!task.optString("status").matches("queued|running")||hasUnresolved(id))throw new SecurityException("原SSH任务已停止或有未决效果，迟到调用不能连接或继续传输");
    }
    /** A manually requested private read-only diagnosis is an existing native external task. */
    public synchronized JSONObject beginSshDoctorTask(String operation,JSONObject captured,JSONObject profile)throws Exception {
        String account=dev.pocketengine.features.ssh.SshProfile.hash((profile.getString("hostFingerprint")+"\n"+profile.getString("username")).getBytes(StandardCharsets.UTF_8));
        JSONObject input=new JSONObject(captured.toString()).put("_resourceId",account);JSONObject task=beginExternal("ssh","doctor",operation,input);
        task.put("coordinatorEpoch",1).put("status","running").put("readOnly",true).put("diagnosticBodyPersisted",false);write(task);return copy(task,false);
    }
    /** Native private page cancellation cannot turn an already completed diagnosis back into stopping. */
    public synchronized void cancelSshDoctorIfRunning(String id,String run)throws Exception {
        JSONObject task=required(id);requirePrivateOwner(task);
        if(!"ssh".equals(task.optString("feature"))||!"doctor".equals(task.optString("command"))||!task.optBoolean("readOnly")||!run.equals(task.optString("runId")))throw new SecurityException("不是原私人诊断任务");
        if(task.optString("status").matches("queued|running")){task.put("cancelRequested",true).put("status","stopping");write(task);}
    }
    public synchronized JSONObject beginRelatedExternal(String parentId,String parentRun,String feature,String command,String operationId,JSONObject input)throws Exception{
        db.beginTransaction();try{
            JSONObject parent=required(parentId);
            if(!parentRun.equals(parent.optString("runId"))||!"owner".equals(parent.optString("actorId"))||!"private".equals(parent.optString("spaceId"))||parent.optBoolean("cancelRequested")||!parent.optString("status").matches("preparing|running|queued"))throw new SecurityException("父任务执行授权已失效");
            JSONObject old=read(operationId);
            if(old!=null){if(!parentId.equals(old.optString("parentTaskId"))||!feature.equals(old.optString("feature")))throw new SecurityException("子操作已属于其他任务");db.setTransactionSuccessful();return new JSONObject().put("taskId",operationId).put("operationId",operationId).put("knownOperation",true);}
            JSONArray related=parent.optJSONArray("relatedTasks");if(related==null)related=new JSONArray();if(related.length()>=100)throw new IllegalStateException("此任务子操作已达到上限");
            JSONObject child=beginExternal(feature,command,operationId,input,parent.optString("conversationId")).put("parentTaskId",parentId).put("parentRunId",parentRun);
            for(String key:new String[]{"memberId","generation","round","teamRunId","readOnly","worker","model"})if(input.has(key))child.put(key,input.get(key));write(child);
            related.put(new JSONObject().put("taskId",operationId).put("runId",operationId).put("kind",feature).put("feature",feature));parent.put("relatedTasks",related);write(parent);
            db.setTransactionSuccessful();return new JSONObject().put("taskId",operationId).put("operationId",operationId).put("knownOperation",false);
        }finally{db.endTransaction();}
    }
    public synchronized JSONObject prepareTeamChild(JSONObject request,JSONObject binding)throws Exception{
        if(!binding.optBoolean("readOnly")||!"codex_app_server".equals(binding.optString("worker")))throw new SecurityException("团队外部成员只允许已核实的只读Codex协议");
        String run=request.getString("runId"),parent=operationTask("teams",run);
        if(parent==null||!run.equals(request.getString("hostTaskId")))throw new SecurityException("没有匹配的宿主团队任务");
        String operation=request.getString("operationId");if(!operation.matches("[a-f0-9-]{36}"))throw new SecurityException("子操作身份无效");
        JSONObject input=new JSONObject(binding.toString()).put("goal",required(parent).getString("goal")).put("teamRunId",run);
        for(String key:new String[]{"memberId","generation","round"})input.put(key,request.get(key));
        JSONObject old=read(operation);if(old!=null){JSONObject refs=old.optJSONObject("externalRefs");if(refs==null||!binding.optString("profileId").equals(refs.optString("profileId"))||!binding.optString("environmentId").equals(refs.optString("environmentId"))||!old.optBoolean("readOnly")||!old.optString("worker").equals(binding.getString("worker"))||!old.optString("model").equals(binding.optString("model")))throw new SecurityException("原子操作的电脑身份或配置已变化");}
        return beginRelatedExternal(parent,run,"remote","submitJob",operation,input);
    }
    /** Parent and child journal IDs are distinct in the same global operations table. */
    public synchronized JSONObject prepareRemoteDelegation(String parentId,String parentRun,int epoch,String parentOperation,JSONObject payload,RunControl control)throws Exception {
        db.beginTransaction();try {
            checkCapability(parentId,parentRun,epoch,"remote_start_task",control);JSONObject originalArgs;
            try(Cursor row=db.rawQuery("SELECT arguments,state FROM operations WHERE task_id=? AND run_id=? AND operation_id=? AND tool='remote_start_task'",new String[]{parentId,parentRun,parentOperation})){
                if(!row.moveToFirst()||!"prepared".equals(row.getString(1)))throw new SecurityException("电脑委派没有原父任务的精确prepared操作");originalArgs=new JSONObject(row.getString(0));
            }
            JSONObject exact=dev.pocketengine.features.remote.RemoteTaskRequest.first(originalArgs);
            if(!capabilityFingerprint(exact).equals(capabilityFingerprint(payload)))throw new SecurityException("电脑委派参数不是原父prepared参数");
            String childId=UUID.nameUUIDFromBytes((parentId+":"+parentRun+":"+parentOperation+":remote-child").getBytes(StandardCharsets.UTF_8)).toString();
            JSONObject link=beginRelatedExternal(parentId,parentRun,"remote","submitJob",childId,payload),child=required(childId);
            if(link.getBoolean("knownOperation")){if(!parentOperation.equals(child.optString("parentOperationId")))throw new SecurityException("原子任务与父operation关联不一致");}
            else {child.put("parentOperationId",parentOperation).put("parentRequestHash",capabilityFingerprint(originalArgs)).put("remoteRequestHash",capabilityFingerprint(exact));write(child);JSONObject parent=required(parentId);JSONArray related=parent.getJSONArray("relatedTasks");for(int i=0;i<related.length();i++)if(childId.equals(related.getJSONObject(i).optString("taskId")))related.getJSONObject(i).put("parentOperationId",parentOperation);write(parent);}
            db.setTransactionSuccessful();return link.put("parentOperationId",parentOperation);
        }finally{db.endTransaction();}
    }
    /** Native relationship proof, not a caller-asserted parent or a second child mapping. */
    public synchronized JSONObject requirePrivateRemoteChild(String id)throws Exception {
        JSONObject child=required(id);requireRemoteOwner(child);rejectChannelParent(child);
        String parentId=messageText(child,"parentTaskId",80),parentRun=messageText(child,"parentRunId",80),parentOperation=messageText(child,"parentOperationId",80);JSONObject parent=required(parentId);requirePrivateOwner(parent);
        if(!parent.optString("kind").matches("phone|assistant")||"teams".equals(parent.optString("feature")))throw new SecurityException("成员任务必须回原团队协调器");
        for(String key:new String[]{"teamRunId","memberId","generation","round"})if(child.has(key))throw new SecurityException("团队身份不完整也不能降级为私人子任务");
        try(Cursor original=db.rawQuery("SELECT 1 FROM runs WHERE task_id=? AND run_id=?",new String[]{parentId,parentRun})){if(!original.moveToFirst())throw new SecurityException("原父执行尝试不存在");}
        boolean related=false;JSONArray links=parent.optJSONArray("relatedTasks");
        for(int i=0;links!=null&&i<links.length();i++){JSONObject row=links.getJSONObject(i);if(id.equals(row.optString("taskId"))&&id.equals(row.optString("runId"))&&"remote".equals(row.optString("feature"))&&parentOperation.equals(row.optString("parentOperationId")))related=true;}
        if(!related)throw new SecurityException("原父任务没有此电脑操作的关联");
        try(Cursor operation=db.rawQuery("SELECT arguments FROM operations WHERE task_id=? AND run_id=? AND operation_id=? AND tool='remote_start_task'",new String[]{parentId,parentRun,parentOperation})){
            if(!operation.moveToFirst())throw new SecurityException("原电脑子任务没有对应的父prepared操作");JSONObject arguments=new JSONObject(operation.getString(0)),refs=child.getJSONObject("externalRefs");
            String expectedChild=UUID.nameUUIDFromBytes((parentId+":"+parentRun+":"+parentOperation+":remote-child").getBytes(StandardCharsets.UTF_8)).toString();
            if(!expectedChild.equals(id)||!capabilityFingerprint(arguments).equals(child.optString("parentRequestHash"))||!capabilityFingerprint(dev.pocketengine.features.remote.RemoteTaskRequest.first(arguments)).equals(child.optString("remoteRequestHash")))throw new SecurityException("原电脑子任务与父精确请求hash不一致");
            for(String key:new String[]{"profileId","environmentId","worker"})if(!arguments.getString(key).equals(refs.optString(key)))throw new SecurityException("电脑子任务和原父操作的执行位置不一致");
        }
        return new JSONObject().put("parentTaskId",parentId).put("parentRunId",parentRun).put("parentOperationId",parentOperation).put("taskId",id);
    }
    /** Native-only target from the current private task receipt. No UI/model supplied source fields. */
    public synchronized JSONObject remoteOwnerTarget(String id)throws Exception{
        JSONObject task=required(id);requireRemoteOwner(task);rejectChannelParent(task);
        JSONObject evidence=task.optJSONObject("evidence"),refs=task.optJSONObject("externalRefs");
        if(evidence==null||refs==null)throw new SecurityException("原电脑任务尚无可核对的执行证据");
        String operation=messageText(task,"operationId",80),profile=messageText(refs,"profileId",80);
        if(!operation.equals(task.optString("runId"))||!profile.equals(evidence.optString("profileId",profile))||!operation.equals(evidence.optString("operationId",operation)))throw new SecurityException("原电脑执行身份不匹配");
        String service=messageText(evidence,"serviceBase",2048);dev.pocketengine.features.remote.RemoteTransport.validateBase(service);
        JSONObject source=new JSONObject().put("taskId",id).put("runId",task.getString("runId")).put("coordinatorEpoch",task.getInt("coordinatorEpoch")).put("profileId",profile).put("serviceBase",service).put("jobOperationId",operation).put("jobId",task.optString("externalId")).put("cancelRequested",task.optBoolean("cancelRequested"));
        for(String field:new String[]{"environmentId","worker","threadId","sessionId","promptRequestId","turnId","protocol","protocolVerified","controlProtocolVerified","sessionIdentityConfirmed","model","reportedModel","readOnly","readOnlyConfirmed","externalToolsDisabled","controls","steeringUnknown","executorStatus","pendingApprovals","approvalReceipts","pendingQuestions","questionReceipts","progress","promptResultIdentity","workspaceIdentity","assignmentWriteSandboxVerified","assignmentWorkspace","externalJobLimits","inferenceUsage","processStarted","dispatchFenceFinal","notStartedConfirmed","processCleanupProof","inputState"})if(evidence.has(field))source.put(field,evidence.get(field));
        if(task.has("parentTaskId"))source.put("parentTaskId",task.getString("parentTaskId"));
        return new JSONObject(source.toString());
    }
    private void rejectChannelParent(JSONObject task)throws Exception{
        JSONObject current=task;for(int n=0;n<4;n++){
            JSONObject origin=current.optJSONObject("origin");if(origin!=null&&origin.optString("kind").matches("channel|group"))throw new SecurityException("渠道执行不能借此页导入或续接电脑会话");
            String parent=current.optString("parentTaskId");if(parent.isEmpty())return;current=required(parent);requirePrivateOwner(current);
        }
        throw new SecurityException("父任务关系过深，不能在此继续");
    }
    private static String remoteSourceFingerprint(JSONObject source)throws Exception{
        JSONObject value=new JSONObject();for(String key:new String[]{"taskId","runId","coordinatorEpoch","profileId","serviceBase","jobId","jobOperationId","environmentId","worker","threadId","sessionId","promptRequestId","turnId","protocol","protocolVerified","controlProtocolVerified","sessionIdentityConfirmed","model","readOnly","controls","steeringUnknown","executorStatus","pendingApprovals","pendingQuestions","cancelRequested","workspaceIdentity"})if(source.has(key))value.put(key,source.get(key));return capabilityFingerprint(value);
    }
    private JSONObject requireRemoteSource(JSONObject source,boolean continuation)throws Exception{
        String id=messageText(source,"taskId",80);JSONObject task=required(id),current=remoteOwnerTarget(id);
        if(!remoteSourceFingerprint(current).equals(remoteSourceFingerprint(source)))throw new SecurityException("原电脑任务、控制或审批已改变，请重新核对");
        if(task.optBoolean("cancelRequested")||task.optString("status").matches("stopping|cancelled|completed")||task.optJSONObject("evidence")!=null&&task.getJSONObject("evidence").has("ownerReconciliation")||hasUnresolved(id))throw new SecurityException("原任务已停止、已确认或有未决动作，只能查询原执行");
        if(continuation){
            if(task.has("parentTaskId"))requirePrivateRemoteChild(id);
            if(!current.optString("executorStatus").matches("completed|failed|cancelled")||current.optJSONObject("controls")==null||!current.getJSONObject("controls").optBoolean("threadResume")||current.optBoolean("steeringUnknown"))throw new SecurityException("原执行未结算或不支持续接；未知结果只查询");
            String worker=messageText(current,"worker",80),protocol=worker.equals("claude")?"claude_stream_json":"codex_app_server";
            if(!worker.matches("claude|codex_app_server")||!protocol.equals(current.optString("protocol"))||!current.optBoolean("protocolVerified"))throw new SecurityException("尚无真实Claude/Codex协议证据");
            messageText(current,"threadId",200);messageText(current,"environmentId",80);messageText(current,"jobId",80);
            if(worker.equals("claude")&&(!current.optBoolean("sessionIdentityConfirmed")||!current.getString("threadId").equals(current.optString("sessionId"))))throw new SecurityException("Claude原会话身份未确认");
        }
        try(Cursor messages=db.rawQuery("SELECT 1 FROM operations WHERE task_id=? AND tool='remote_message' AND state!='message_receipt' LIMIT 1",new String[]{id})){if(messages.moveToFirst())throw new SecurityException("原turn补充消息未决，只查询原编号");}
        return task;
    }
    public synchronized void checkRemoteContinuationSource(JSONObject source)throws Exception{requireRemoteSource(source,true);}
    /** Persists a new attempt/operation on the same original task, without network I/O. */
    public synchronized JSONObject prepareRemoteContinuation(JSONObject source,String prompt)throws Exception{
        return prepareRemoteContinuation(source,prompt,null);
    }
    public synchronized JSONObject prepareRemoteContinuation(JSONObject source,String prompt,JSONObject snapshot)throws Exception{
        if(prompt==null||prompt.trim().isEmpty()||prompt.length()>12000||prompt.indexOf('\0')>=0)throw new IllegalArgumentException("新要求需为1至12000字符");
        db.beginTransaction();try{
            JSONObject task=requireRemoteSource(source,true);String id=task.getString("id"),old=task.getString("runId"),operation=UUID.randomUUID().toString();int epoch=Math.addExact(task.getInt("coordinatorEpoch"),1);long now=System.currentTimeMillis();
            saveRemoteRun(task,task.optString("executionStatus"),new JSONObject().put("receipt",task.getJSONObject("evidence")));
            JSONObject args=new JSONObject().put("source",source).put("taskId",id).put("runId",operation).put("coordinatorEpoch",epoch).put("prompt",prompt.trim()).put("claimExpiresAt",now+30_000L);
            if(snapshot!=null){JSONObject known=remoteResultSnapshot(id,snapshot.getString("snapshotId"));if(!known.getString("snapshotVersion").equals(snapshot.getString("snapshotVersion"))||!source.getString("jobId").equals(known.getString("jobId"))||!source.getString("jobOperationId").equals(known.getString("jobOperationId")))throw new SecurityException("所选成果版本不是原任务当前job的快照");args.put("resultSnapshotId",known.getString("snapshotId")).put("resultSnapshotVersion",known.getString("snapshotVersion"));}
            insertRemoteOperation(operation,id,operation,"remote_job",args,"remote_job_prepared");
            task.put("runId",operation).put("operationId",operation).put("coordinatorEpoch",epoch).put("cancelRequested",false).put("status","queued").put("executionStatus","submitting").put("evidence",new JSONObject().put("executorStatus","submitting").put("operationId",operation).put("profileId",source.getString("profileId")).put("serviceBase",source.getString("serviceBase")).put("automaticallyVerified",false));task.remove("externalId");task.remove("externalUpdatedAt");
            saveRemoteRun(task,"running",new JSONObject().put("source",source).put("ownerPrompt",prompt.trim()));
            appendEvent(task,new JSONObject().put("type","remote_owner_continuation").put("previousRunId",old).put("runId",operation).put("coordinatorEpoch",epoch).put("source",source).put("prompt",prompt.trim()));db.setTransactionSuccessful();return remoteOwnerOperation(id,operation);
        }finally{db.endTransaction();}
    }
    /** Single local claim. The caller must release this monitor before calling RemoteModule. */
    public synchronized JSONObject claimRemoteContinuation(String id,String operation)throws Exception{
        JSONObject row=remoteOperation(id,operation,"remote_job"),args=row.getJSONObject("arguments"),task=required(id);requireRemoteOwner(task);rejectChannelParent(task);
        if(!row.getString("state").equals("remote_job_prepared")||System.currentTimeMillis()>args.getLong("claimExpiresAt")||!operation.equals(task.optString("runId"))||args.getInt("coordinatorEpoch")!=task.optInt("coordinatorEpoch")||task.optBoolean("cancelRequested")||!task.optString("status").equals("queued"))throw new SecurityException("本次续接已派发、关闭或执行身份已改变；只能查询原操作");
        try(Cursor unresolved=db.rawQuery("SELECT 1 FROM operations WHERE task_id=? AND operation_id!=? AND (state IN ('prepared','unknown','remote_job_prepared','remote_job_sending','remote_job_unknown','remote_approval_sending','remote_approval_unknown','remote_question_prepared','remote_question_sending','remote_question_unknown') OR tool='remote_message' AND state!='message_receipt') LIMIT 1",new String[]{id,operation})){if(unresolved.moveToFirst())throw new SecurityException("原任务仍有未决动作，不得继续派发");}
        ContentValues value=new ContentValues();value.put("state","remote_job_sending");if(db.update("operations",value,"operation_id=? AND state='remote_job_prepared'",new String[]{operation})!=1)throw new SecurityException("续接已被领取");
        JSONObject source=args.getJSONObject("source"),payload=new JSONObject().put("operationId",operation).put("prompt",args.getString("prompt")).put("resumeJobId",source.getString("jobId"));for(String key:new String[]{"profileId","serviceBase","environmentId","worker","threadId","jobOperationId"})payload.put(key,source.get(key));for(String key:new String[]{"resultSnapshotId","resultSnapshotVersion"})if(args.has(key))payload.put(key,args.getString(key));return payload;
    }
    public synchronized JSONObject remoteOwnerOperation(String id,String operation)throws Exception{
        JSONObject row=remoteOperation(id,operation,null),args=row.getJSONObject("arguments");return new JSONObject().put("taskId",id).put("operationId",operation).put("runId",row.getString("runId")).put("state",row.getString("state")).put("tool",row.getString("tool")).put("source",args.optJSONObject("source")).put("receipt",row.optJSONObject("receipt"));
    }
    private JSONObject remoteOperation(String id,String operation,String tool)throws Exception{
        requireRemoteOwner(required(id));try(Cursor row=db.rawQuery("SELECT task_id,run_id,tool,arguments,state,receipt FROM operations WHERE operation_id=?",new String[]{operation})){
            if(!row.moveToFirst()||!id.equals(row.getString(0))||!row.getString(2).matches("remote_job|remote_approval|remote_snapshot|remote_question_answer|remote_private_input")||tool!=null&&!tool.equals(row.getString(2)))throw new SecurityException("没有此原任务的原生电脑操作");
            JSONObject result=new JSONObject().put("runId",row.getString(1)).put("tool",row.getString(2)).put("arguments",new JSONObject(row.getString(3))).put("state",row.getString(4));if(!row.isNull(5))result.put("receipt",new JSONObject(row.getString(5)));return result;
        }
    }
    private void insertRemoteOperation(String operation,String task,String run,String tool,JSONObject args,String state)throws Exception{
        ContentValues row=new ContentValues();row.put("operation_id",operation);row.put("task_id",task);row.put("run_id",run);row.put("tool",tool);row.put("arguments",sanitize(args).toString());row.put("state",state);row.put("created_at",System.currentTimeMillis());db.insertOrThrow("operations",null,row);
    }
    /** Native owner explicitly selects bounded paths. Capture has no task/worker execution authority. */
    public synchronized JSONObject claimRemoteResultSnapshot(JSONObject source,JSONArray paths)throws Exception {
        return claimRemoteResultSnapshot(source,paths,null);
    }
    public synchronized JSONObject claimRemoteResultSnapshot(JSONObject source,JSONArray paths,JSONArray expectedFiles)throws Exception {
        JSONArray reviewed=expectedFiles==null?null:dev.pocketengine.features.remote.RemoteResultSnapshots.expectedFiles(expectedFiles,paths);
        JSONArray selected=dev.pocketengine.features.remote.RemoteResultSnapshots.paths(paths);db.beginTransaction();try{
            String id=messageText(source,"taskId",80);JSONObject task=required(id),current=remoteOwnerTarget(id);if(!remoteSourceFingerprint(source).equals(remoteSourceFingerprint(current)))throw new SecurityException("原job已变化，请刷新后重新选择快照文件");
            if(!source.optString("executorStatus").matches("completed|failed|cancelled")||source.optJSONArray("pendingApprovals")!=null&&source.getJSONArray("pendingApprovals").length()>0||hasUnresolved(id))throw new SecurityException("原job尚未结算或有未决动作，只能查询，不能采集快照");
            JSONArray snapshots=task.optJSONArray("remoteResultSnapshots");if(snapshots!=null&&snapshots.length()>=8)throw new IllegalStateException("原任务已保留8个成果快照，不会自动覆盖旧成果");
            String operation=UUID.randomUUID().toString();JSONObject args=new JSONObject().put("source",source).put("paths",selected).put("previousStatus",task.getString("status"));if(reviewed!=null)args.put("expectedFiles",reviewed);insertRemoteOperation(operation,id,task.getString("runId"),"remote_snapshot",args,"unknown");
            if(!task.optString("status").matches("completed|cancelled"))task.put("status","reconciling").put("summary","指定成果快照已登记，等待原操作回执；不会自动重新采集");write(task);db.setTransactionSuccessful();return remoteOwnerOperation(id,operation);
        }finally{db.endTransaction();}
    }
    public synchronized JSONArray pendingRemoteResultSnapshots(String id)throws Exception {
        remoteOwnerTarget(id);JSONArray pending=new JSONArray();try(Cursor rows=db.rawQuery("SELECT operation_id FROM operations WHERE task_id=? AND tool='remote_snapshot' AND state='unknown' ORDER BY created_at LIMIT 8",new String[]{id})){while(rows.moveToNext())pending.put(remoteOwnerOperation(id,rows.getString(0)));}return pending;
    }
    public synchronized JSONObject remoteResultSnapshot(String id,String snapshotId)throws Exception {
        remoteOwnerTarget(id);JSONArray snapshots=required(id).optJSONArray("remoteResultSnapshots");for(int i=0;snapshots!=null&&i<snapshots.length();i++)if(snapshotId.equals(snapshots.getJSONObject(i).optString("snapshotId")))return new JSONObject(snapshots.getJSONObject(i).toString());throw new SecurityException("原任务没有此原生快照回执");
    }
    public synchronized void recordRemoteResultSnapshot(String id,String operation,JSONObject actual)throws Exception {
        db.beginTransaction();try{
            JSONObject row=remoteOperation(id,operation,"remote_snapshot"),args=row.getJSONObject("arguments"),source=args.getJSONObject("source");
            for(String key:new String[]{"jobId","jobOperationId","profileId","serviceBase"})if(!source.getString(key).equals(actual.optString(key)))throw new SecurityException("快照回执缺少原电脑/job来源");if(!operation.equals(actual.optString("operationId")))throw new SecurityException("快照回执属于另一操作");
            String status=actual.optString("status");if(status.matches("unknown|not_found")){db.setTransactionSuccessful();return;}
            if(row.getString("state").equals("receipt")){
                JSONObject previous=row.getJSONObject("receipt");if(!previous.optString("status").equals(status)||status.equals("completed")&&(!previous.getString("snapshotId").equals(actual.optString("snapshotId"))||!previous.getString("snapshotVersion").equals(actual.optString("snapshotVersion"))))throw new SecurityException("原快照操作已结算，不能替换其不可变回执");db.setTransactionSuccessful();return;
            }
            JSONObject task=required(id),receipt;if(status.equals("completed")){
                receipt=dev.pocketengine.features.remote.RemoteResultSnapshots.metadata(actual,source,operation,args.getJSONArray("paths"));dev.pocketengine.features.remote.RemoteResultSnapshots.requireExpected(receipt,args.optJSONArray("expectedFiles"));JSONArray snapshots=task.optJSONArray("remoteResultSnapshots");if(snapshots==null)snapshots=new JSONArray();boolean known=false;for(int i=0;i<snapshots.length();i++)if(receipt.getString("snapshotId").equals(snapshots.getJSONObject(i).getString("snapshotId"))){if(!receipt.getString("snapshotVersion").equals(snapshots.getJSONObject(i).getString("snapshotVersion")))throw new SecurityException("不可变快照编号返回不同版本");known=true;}
                if(!known)snapshots.put(receipt);task.put("remoteResultSnapshots",snapshots);
            }else if(status.equals("rejected"))receipt=new JSONObject(actual.toString());else throw new SecurityException("快照尚无可结算的真实回执");
            ContentValues settled=new ContentValues();settled.put("state","receipt");settled.put("receipt",sanitize(receipt).toString());db.update("operations",settled,"task_id=? AND operation_id=? AND tool='remote_snapshot' AND state='unknown'",new String[]{id,operation});
            if(source.getString("runId").equals(task.optString("runId"))&&task.optString("status").equals("reconciling")&&!task.optBoolean("cancelRequested")&&!hasUnresolved(id))task.put("status",args.getString("previousStatus"));write(task);db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
    /** Presentation association only. It does not change original immutable snapshot metadata or model permissions. */
    public synchronized JSONObject recordRemoteSnapshotAttachment(String id,dev.pocketengine.features.remote.RemoteResultFiles.Saved nativeSaved)throws Exception {
        if(nativeSaved==null||!nativeSaved.issuedBy(this))throw new SecurityException("没有本机原件保存证明");
        JSONObject value=nativeSaved.view();if(!id.equals(value.getString("taskId")))throw new SecurityException("保存原件属于另一任务");
        JSONObject snapshot=remoteResultSnapshot(id,value.getString("snapshotId"));
        if(!snapshot.getString("snapshotVersion").equals(value.getString("snapshotVersion")))throw new SecurityException("不可变快照版本已改变");
        JSONObject file=null;JSONArray files=snapshot.getJSONArray("files");for(int i=0;i<files.length();i++)if(value.getString("path").equals(files.getJSONObject(i).getString("path")))file=files.getJSONObject(i);
        if(file==null||file.getLong("bytes")!=value.getLong("bytes")||!file.getString("sha256").equals(value.getString("sha256"))||!file.getString("fileVersion").equals(value.getString("fileVersion")))throw new SecurityException("保存原件不在原快照范围");
        JSONObject task=required(id);JSONArray refs=task.optJSONArray("remoteSnapshotAttachments");if(refs==null)refs=new JSONArray();
        boolean replaced=false;for(int i=0;i<refs.length();i++){JSONObject old=refs.getJSONObject(i);if(value.getString("snapshotId").equals(old.optString("snapshotId"))&&value.getString("path").equals(old.optString("path"))){refs.put(i,value);replaced=true;break;}}
        if(!replaced){if(refs.length()>=64)throw new IllegalStateException("原任务已关联64个本机成果，请先整理附件");refs.put(value);}
        task.put("remoteSnapshotAttachments",refs);write(task);return new JSONObject(value.toString());
    }
    public synchronized JSONArray remoteSnapshotAttachments(String id)throws Exception {
        remoteOwnerTarget(id);JSONArray refs=required(id).optJSONArray("remoteSnapshotAttachments");return refs==null?new JSONArray():new JSONArray(refs.toString());
    }
    public enum RemoteInputAction {CREATE,UPLOAD,START}
    /** Native ephemeral proof: a JSON view cannot reconstruct it or its dispatch authority. */
    public static final class RemoteInputClaim {
        private final TaskStore issuer;
        private final dev.pocketengine.features.remote.RemoteInputAttachments.Approval approval;
        private final String value;
        private final boolean dispatch;
        private RemoteInputClaim(TaskStore issuer,dev.pocketengine.features.remote.RemoteInputAttachments.Approval approval,JSONObject value,boolean dispatch){this.issuer=issuer;this.approval=approval;this.value=value.toString();this.dispatch=dispatch;}
        public JSONObject view()throws Exception{return new JSONObject(value);}
        public boolean canDispatch(){return dispatch;}
        public void checkCurrent()throws Exception{issuer.checkRemoteInputClaimCurrent(this);}
        public void beforeDispatch()throws Exception{issuer.beforeRemoteInputDispatch(this);}
    }
    private JSONObject inputTask(dev.pocketengine.features.remote.RemoteInputAttachments.Approval approval)throws Exception {
        if(approval==null||!approval.issuedBy(this))throw new SecurityException("没有此TaskStore的原生附件批准");
        approval.checkCurrent();JSONObject scope=approval.view(),task=required(scope.getString("taskId"));requireRemoteOwner(task);rejectChannelParent(task);
        if(!"native_remote_private_inputs".equals(task.getJSONObject("origin").optString("kind"))||task.has("parentTaskId")||!scope.getString("jobOperationId").equals(task.optString("runId"))||!scope.getString("jobOperationId").equals(task.optString("operationId"))||task.optInt("coordinatorEpoch")!=0||!capabilityFingerprint(scope).equals(task.optString("remoteInputScopeHash")))throw new SecurityException("原任务、外传范围或执行代次已改变");
        if(task.optBoolean("cancelRequested")||task.optString("status").matches("cancelled|stopping|completed"))throw new SecurityException("原附件任务已停止或确认，不能再发送或启动worker");
        return task;
    }
    public synchronized JSONObject beginRemoteInput(dev.pocketengine.features.remote.RemoteInputAttachments.Approval approval)throws Exception {
        if(approval==null||!approval.issuedBy(this))throw new SecurityException("缺少原生明确外传批准");approval.checkCurrent();JSONObject scope=approval.view();String id=scope.getString("taskId");
        if(!id.matches("[a-f0-9-]{36}")||!id.equals(scope.getString("jobOperationId")))throw new SecurityException("原本机任务与job操作身份不一致");
        db.beginTransaction();try{
            JSONObject existing=read(id);if(existing!=null){inputTask(approval);db.setTransactionSuccessful();return copy(existing,false);}
            JSONObject task=beginExternal("remote","submitJob",id,scope.getJSONObject("request"));
            task.put("origin",new JSONObject().put("kind","native_remote_private_inputs")).put("remoteInputScope",scope).put("remoteInputScopeHash",capabilityFingerprint(scope));
            saveRemoteRun(task,"queued",new JSONObject().put("privateInputScopeHash",capabilityFingerprint(scope)));write(task);db.setTransactionSuccessful();return copy(task,false);
        }finally{db.endTransaction();}
    }
    /** Private native owner read only. No claim/RunControl/dispatch can be restored from this JSON. */
    public synchronized JSONObject originalRemoteInputScope(String id)throws Exception {
        JSONObject task=required(id);requireRemoteOwner(task);rejectChannelParent(task);
        if(!"native_remote_private_inputs".equals(task.getJSONObject("origin").optString("kind"))||!task.has("remoteInputScope"))throw new SecurityException("没有此原任务的私人附件暂存登记");
        JSONObject scope=task.getJSONObject("remoteInputScope");
        if(!capabilityFingerprint(scope).equals(task.optString("remoteInputScopeHash")))throw new SecurityException("原附件范围校验失败");
        return new JSONObject(scope.toString());
    }
    private JSONObject inputClaimRow(RemoteInputClaim claim)throws Exception {
        if(claim==null||claim.issuer!=this)throw new SecurityException("附件领取不是此原SQL实例");
        JSONObject view=claim.view(),scope=view.getJSONObject("scope");String id=scope.getString("taskId"),op=view.getString("operationId");
        JSONObject row=remoteOperation(id,op,"remote_private_input"),args=row.getJSONObject("arguments"),original=originalRemoteInputScope(id);
        if(!capabilityFingerprint(original).equals(args.getString("scopeHash"))||!capabilityFingerprint(original).equals(capabilityFingerprint(scope))||!view.getString("action").equals(args.getString("action"))||view.getInt("index")!=args.getInt("index"))throw new SecurityException("附件领取与原prepared、范围或阶段不一致");
        return row;
    }
    public synchronized RemoteInputClaim claimRemoteInputAction(dev.pocketengine.features.remote.RemoteInputAttachments.Approval approval,RemoteInputAction action,int index)throws Exception {
        db.beginTransaction();try{
            JSONObject task=inputTask(approval),scope=approval.view();String id=task.getString("id");
            if(action==RemoteInputAction.UPLOAD?(index<0||index>=scope.getJSONArray("files").length()):index!=-1)throw new SecurityException("附件阶段索引不在原范围");
            String op=UUID.nameUUIDFromBytes(("pocketworld:private-input-v1:"+id+":"+action+":"+index).getBytes(StandardCharsets.UTF_8)).toString();
            JSONObject previous=null;try{previous=remoteOperation(id,op,"remote_private_input");}catch(SecurityException absent){}
            boolean dispatch=previous==null;
            if(dispatch){
                if(hasUnresolvedExcept(id,op))throw new SecurityException("原附件动作仍有未决效果，只能查询原编号");
                if(action!=RemoteInputAction.CREATE&&task.optString("remoteInputJobId").isEmpty())throw new SecurityException("尚未核对原job接收回执");
                JSONObject state=task.optJSONObject("remoteInputState");
                if(action==RemoteInputAction.UPLOAD&&(state==null||!state.optString("phase").matches("awaiting_upload|uploading")||!state.getJSONArray("files").getJSONObject(index).optString("state").equals("pending")))throw new SecurityException("原文件并非未发送状态，不能再传body");
                if(action==RemoteInputAction.START&&(state==null||!state.optString("phase").equals("ready")||state.optBoolean("workerStarted")))throw new SecurityException("所有文件实际hash尚未ready，或原worker已启动");
                JSONObject args=new JSONObject().put("action",action.name()).put("index",index).put("scopeHash",task.getString("remoteInputScopeHash")).put("nativeDispatchState","prepared").put("approvalExpiresAt",approval.expiresAt());
                insertRemoteOperation(op,id,scope.getString("jobOperationId"),"remote_private_input",args,"unknown");
            }
            JSONObject view=new JSONObject().put("operationId",op).put("action",action.name()).put("index",index).put("scope",scope).put("jobId",task.optString("remoteInputJobId")).put("canDispatch",dispatch);
            db.setTransactionSuccessful();return new RemoteInputClaim(this,approval,view,dispatch);
        }finally{db.endTransaction();}
    }
    public synchronized void checkRemoteInputClaimCurrent(RemoteInputClaim claim)throws Exception {
        JSONObject task=inputTask(claim.approval),view=claim.view(),row=inputClaimRow(claim);String own=view.getString("operationId");
        if(hasUnresolvedExcept(view.getJSONObject("scope").getString("taskId"),own))throw new SecurityException("另一原动作仍未知，停止后继外传");
        if(claim.canDispatch()&&!row.getString("state").equals("unknown")){
            // A concurrent original GET may prove this exact sent action before its own response arrives.
            // This is only currentness for finishing IO; beforeDispatch still requires unknown+prepared.
            JSONObject actual=row.optJSONObject("receipt"),state=actual==null?null:actual.optJSONObject("inputState");
            if(!row.getString("state").equals("receipt")||!row.getJSONObject("arguments").optString("nativeDispatchState").equals("sending")||state==null||!inputActionAccepted(view.getString("action"),view.getInt("index"),own,state))throw new SecurityException("该原动作没有已派发且同范围的接收证明，不能继续IO或重发");
            JSONObject bound=new JSONObject(view.getJSONObject("scope").toString());if(!task.optString("remoteInputJobId").isEmpty())bound.put("jobId",task.getString("remoteInputJobId"));
            dev.pocketengine.features.remote.RemoteTaskRequest.inputReceipt(actual,bound);
        }
    }
    public synchronized void beforeRemoteInputDispatch(RemoteInputClaim claim)throws Exception {
        db.beginTransaction();try{
            checkRemoteInputClaimCurrent(claim);JSONObject row=inputClaimRow(claim),args=row.getJSONObject("arguments");
            if(!claim.canDispatch()||!row.getString("state").equals("unknown")||!args.optString("nativeDispatchState").equals("prepared"))throw new SecurityException("原动作已经派发或未知，不能再次发送");
            args.put("nativeDispatchState","sending");ContentValues value=new ContentValues();value.put("arguments",sanitize(args).toString());
            if(db.update("operations",value,"operation_id=? AND state='unknown'",new String[]{claim.view().getString("operationId")})!=1)throw new SecurityException("原派发领取已改变");
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
    public synchronized void recordRemoteInputReceipt(RemoteInputClaim claim,JSONObject actual)throws Exception {
        inputClaimRow(claim);JSONObject view=claim.view(),scope=view.getJSONObject("scope");String id=scope.getString("taskId");
        db.beginTransaction();try{
            if(actual.optString("status").equals("rejected")){
                for(String key:new String[]{"profileId","serviceBase"})if(!scope.getString(key).equals(actual.optString(key)))throw new SecurityException("拒绝回执不是原目标");
                if(!scope.getString("jobOperationId").equals(actual.optString("operationId")))throw new SecurityException("拒绝回执不是原操作");
                settleInputOperation(id,view.getString("operationId"),actual);db.setTransactionSuccessful();return;
            }
            if(!actual.has("inputState")){db.setTransactionSuccessful();return;}
            JSONObject task=required(id),bound=new JSONObject(scope.toString());if(!task.optString("remoteInputJobId").isEmpty())bound.put("jobId",task.getString("remoteInputJobId"));
            dev.pocketengine.features.remote.RemoteTaskRequest.inputReceipt(actual,bound);
            JSONObject state=actual.getJSONObject("inputState");boolean accepted=inputActionAccepted(view.getString("action"),view.getInt("index"),view.getString("operationId"),state);
            if(accepted)settleInputOperation(id,view.getString("operationId"),actual);
            settleRemoteInputFacts(task,actual);write(task);
            if(scope.getString("jobOperationId").equals(task.optString("runId"))&&task.optInt("coordinatorEpoch")==0&&!task.optBoolean("cancelRequested")&&!task.optString("status").matches("cancelled|completed|stopping"))updateExternal(id,actual,view.getString("action").equals("START"));
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
    private static boolean inputActionAccepted(String action,int index,String operation,JSONObject state)throws Exception {
        return action.equals("CREATE")||action.equals("START")&&operation.equals(state.optString("startOperationId"))||action.equals("UPLOAD")&&state.getJSONArray("files").getJSONObject(index).optString("state").equals("verified");
    }
    private void settleInputOperation(String id,String operation,JSONObject actual)throws Exception {
        ContentValues row=new ContentValues();row.put("state","receipt");row.put("receipt",sanitize(actual).toString());
        db.update("operations",row,"task_id=? AND operation_id=? AND tool='remote_private_input' AND state='unknown'",new String[]{id,operation});
    }
    private JSONObject originalInputReceiptState(JSONObject task,JSONObject actual)throws Exception {
        JSONObject original=task.getJSONObject("remoteInputScope");
        if(!"native_remote_private_inputs".equals(task.getJSONObject("origin").optString("kind"))||!task.getString("id").equals(original.getString("taskId"))||!capabilityFingerprint(original).equals(task.getString("remoteInputScopeHash")))throw new SecurityException("原附件任务或登记范围hash不一致");
        JSONObject scope=new JSONObject(original.toString());if(!task.optString("remoteInputJobId").isEmpty())scope.put("jobId",task.getString("remoteInputJobId"));
        dev.pocketengine.features.remote.RemoteTaskRequest.inputReceipt(actual,scope);return actual.getJSONObject("inputState");
    }
    private void settleRemoteInputFacts(JSONObject task,JSONObject actual)throws Exception {
        String id=task.getString("id");JSONObject state=originalInputReceiptState(task,actual);
        try(Cursor rows=db.rawQuery("SELECT operation_id,arguments FROM operations WHERE task_id=? AND tool='remote_private_input' AND state='unknown'",new String[]{id})){
            while(rows.moveToNext()){JSONObject args=new JSONObject(rows.getString(1));if(!task.getString("remoteInputScopeHash").equals(args.getString("scopeHash")))throw new SecurityException("原附件操作范围不同");
                boolean accepted=inputActionAccepted(args.getString("action"),args.getInt("index"),rows.getString(0),state);
                if(accepted)settleInputOperation(id,rows.getString(0),actual);
            }
        }
        long at=actual.optLong("updatedAt",0);task.put("remoteInputJobId",actual.getString("id"));
        JSONObject previous=task.optJSONObject("remoteInputState");long revision=state.getLong("revision");
        if(previous!=null&&revision==previous.getLong("revision")&&!capabilityFingerprint(previous).equals(capabilityFingerprint(state)))throw new SecurityException("同一原输入版本返回不同内容，只核对，不覆盖");
        if(previous==null||revision>previous.getLong("revision"))task.put("remoteInputState",new JSONObject(state.toString())).put("remoteInputStateAt",at);
    }
    private boolean originalInputStartAccepted(JSONObject task,JSONObject actual)throws Exception {
        JSONObject scope=task.getJSONObject("remoteInputScope"),state=actual.optJSONObject("inputState");
        if(state==null||task.optBoolean("cancelRequested")||task.optInt("coordinatorEpoch")!=0||!scope.getString("jobOperationId").equals(task.optString("runId")))return false;
        String operation=state.optString("startOperationId");if(operation.isEmpty())return false;
        try{JSONObject row=remoteOperation(task.getString("id"),operation,"remote_private_input"),args=row.getJSONObject("arguments");
            return args.optString("action").equals("START")&&args.optString("nativeDispatchState").equals("sending")&&task.getString("remoteInputScopeHash").equals(args.optString("scopeHash"));
        }catch(SecurityException missing){return false;}
    }
    public synchronized void reconcileRemoteInputs(String id,JSONObject actual)throws Exception {
        originalRemoteInputScope(id);db.beginTransaction();try{
            JSONObject task=required(id);settleRemoteInputFacts(task,actual);write(task);
            if(task.getJSONObject("remoteInputScope").getString("jobOperationId").equals(task.optString("runId"))&&task.optInt("coordinatorEpoch")==0&&!task.optBoolean("cancelRequested")&&!task.optString("status").matches("cancelled|completed|stopping"))updateExternal(id,actual,false);
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
    /** Only native SQL evidence of not crossing dispatch can settle this own original prepared intent. */
    public synchronized void remoteInputUnconfirmed(RemoteInputClaim claim)throws Exception {
        db.beginTransaction();try{
            JSONObject row=inputClaimRow(claim);if(!row.getString("state").equals("unknown")||!row.getJSONObject("arguments").optString("nativeDispatchState").equals("prepared")){db.setTransactionSuccessful();return;}
            JSONObject view=claim.view(),scope=view.getJSONObject("scope");
            settleInputOperation(scope.getString("taskId"),view.getString("operationId"),new JSONObject().put("status","rejected").put("notDispatchedConfirmed",true).put("reason","original_native_prepared_fence_not_crossed"));
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
    public synchronized JSONObject cancelRemoteInputBeforeJob(String id)throws Exception {
        JSONObject scope=originalRemoteInputScope(id),task=required(id);
        if(!scope.getString("jobOperationId").equals(task.optString("runId"))||task.optInt("coordinatorEpoch")!=0||!task.optString("remoteInputJobId").isEmpty())throw new SecurityException("已有原job或执行代次变化，不能声称本地取消");
        try(Cursor rows=db.rawQuery("SELECT state,arguments FROM operations WHERE task_id=? AND tool='remote_private_input'",new String[]{id})){while(rows.moveToNext()){
            JSONObject args=new JSONObject(rows.getString(1));if(args.optString("nativeDispatchState").equals("sending")||rows.getString(0).equals("unknown"))throw new SecurityException("原派发仍未知，只查询原编号或停止原job，不能假定未发送");
        }}
        task.put("cancelRequested",true).put("status","cancelled").put("summary","本机原生prepared证明未进入网络派发；没有远端删除声明");write(task);return copy(task,false);
    }
    private void saveRemoteRun(JSONObject task,String status,JSONObject checkpoint)throws Exception{
        ContentValues row=new ContentValues();row.put("run_id",task.getString("runId"));row.put("task_id",task.getString("id"));row.put("epoch",task.getInt("coordinatorEpoch"));row.put("status",status);row.put("checkpoint",sanitize(checkpoint).toString());row.put("grant_json",new JSONObject().put("principalId","owner").put("spaceId","private").put("mode","remote").put("tools",new JSONArray()).toString());row.put("created_at",System.currentTimeMillis());row.put("updated_at",System.currentTimeMillis());db.insertWithOnConflict("runs",null,row,SQLiteDatabase.CONFLICT_IGNORE);
    }
    /** Claims one exact decision before POST. A repeated request is query-only, never a retry. */
    /** Normal input is not a tool permission; legacy/team writers must not inherit this private-owner path. */
    private void requirePrivateRemoteQuestionOwner(JSONObject task)throws Exception {
        requireRemoteOwner(task);rejectChannelParent(task);JSONObject origin=task.optJSONObject("origin");
        if(origin!=null&&origin.optString("kind").startsWith("team")||task.has("teamRunId")||task.has("memberId")||task.has("round")||task.has("generation"))throw new SecurityException("团队正常问答须原协调器合同；不能降级为私人回答");
        if(!task.optString("parentTaskId").isEmpty()){JSONObject parent=required(task.getString("parentTaskId"));if(parent.optString("feature").equals("teams"))throw new SecurityException("团队子任务不能借私人问答继续");requirePrivateRemoteChild(task.getString("id"));if(parent.optBoolean("cancelRequested"))throw new SecurityException("原私人父任务已停止");}
    }
    public synchronized JSONObject claimRemoteQuestionAnswer(JSONObject source,String requestId,String requestHash,JSONObject supplied)throws Exception {
        String id=source.getString("taskId");JSONObject question=dev.pocketengine.features.remote.RemoteInteractions.question(source,requestId,requestHash),answers=dev.pocketengine.features.remote.RemoteInteractions.answers(question,supplied);String answerHash=capabilityFingerprint(answers),identity=capabilityFingerprint(new JSONObject().put("taskId",id).put("runId",source.getString("runId")).put("jobOperationId",source.getString("jobOperationId")).put("requestId",requestId).put("requestHash",requestHash));
        db.beginTransaction();try{
            try(Cursor rows=db.rawQuery("SELECT operation_id,arguments,state,receipt FROM operations WHERE task_id=? AND tool='remote_question_answer'",new String[]{id})){while(rows.moveToNext()){JSONObject args=new JSONObject(rows.getString(1));if(!identity.equals(args.optString("questionIdentity")))continue;JSONObject prior=rows.isNull(3)?null:new JSONObject(rows.getString(3));if(rows.getString(2).equals("remote_question_rejected")&&prior!=null&&prior.optBoolean("notDispatchedConfirmed"))continue;if(!answerHash.equals(args.getString("answerHash")))throw new SecurityException("原问题已领取其他答案；不能改变或重发");db.setTransactionSuccessful();return remoteOwnerOperation(id,rows.getString(0)).put("knownAnswer",true);}}
            JSONObject task=requireRemoteSource(source,false);requirePrivateRemoteQuestionOwner(task);if(!source.optString("executorStatus").matches("running|awaiting_input")||source.optJSONObject("controls")==null||!source.getJSONObject("controls").optBoolean("answerQuestion"))throw new SecurityException("原执行未等待可回答的正常问题");
            String operation=UUID.randomUUID().toString();JSONObject body=new JSONObject().put("answerId",operation).put("jobOperationId",source.getString("jobOperationId")).put("requestId",requestId).put("requestHash",requestHash).put("answers",answers),args=new JSONObject().put("source",source).put("question",question).put("questionIdentity",identity).put("answerHash",answerHash).put("request",body).put("claimExpiresAt",System.currentTimeMillis()+30_000L);
            insertRemoteOperation(operation,id,task.getString("runId"),"remote_question_answer",args,"remote_question_prepared");db.setTransactionSuccessful();return remoteOwnerOperation(id,operation).put("knownAnswer",false).put("request",body);
        }finally{db.endTransaction();}
    }
    /** Final local SQL fence; caller releases this monitor before network dispatch. */
    public synchronized void beforeRemoteQuestionAnswerDispatch(String id,String operation)throws Exception {
        db.beginTransaction();try{
            JSONObject row=remoteOperation(id,operation,"remote_question_answer"),args=row.getJSONObject("arguments"),source=args.getJSONObject("source"),task=required(id),current=remoteOwnerTarget(id);requirePrivateRemoteQuestionOwner(task);
            if(!row.getString("state").equals("remote_question_prepared")||System.currentTimeMillis()>=args.getLong("claimExpiresAt")||task.optBoolean("cancelRequested")||!task.optString("status").matches("running|awaiting_user")||!remoteSourceFingerprint(current).equals(remoteSourceFingerprint(source)))throw new SecurityException("原问题/执行/窗口已改变；未重新回答");
            JSONObject request=args.getJSONObject("request");dev.pocketengine.features.remote.RemoteInteractions.question(current,request.getString("requestId"),request.getString("requestHash"));
            try(Cursor other=db.rawQuery("SELECT 1 FROM operations WHERE task_id=? AND operation_id<>? AND state IN ('prepared','unknown','remote_job_prepared','remote_job_sending','remote_job_unknown','remote_approval_sending','remote_approval_unknown','remote_question_prepared','remote_question_sending','remote_question_unknown') LIMIT 1",new String[]{id,operation})){if(other.moveToFirst())throw new SecurityException("原任务尚有其他未知效果，只能核对");}
            ContentValues changed=new ContentValues();changed.put("state","remote_question_sending");if(db.update("operations",changed,"operation_id=? AND state='remote_question_prepared'",new String[]{operation})!=1)throw new SecurityException("原答案已领取派发");db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
    /** A foreground/profile failure before the SQL dispatch boundary proves no wire write; after it remains unknown. */
    public synchronized void remoteQuestionAnswerUnconfirmed(String id,String operation)throws Exception {
        JSONObject row=remoteOperation(id,operation,"remote_question_answer");String state=row.getString("state");if(!state.matches("remote_question_prepared|remote_question_sending"))return;boolean never=state.equals("remote_question_prepared");ContentValues changed=new ContentValues();changed.put("state",never?"remote_question_rejected":"remote_question_unknown");changed.put("receipt",new JSONObject().put("answerId",operation).put("status",never?"rejected":"unknown").put("dispatched",never?false:JSONObject.NULL).put("notDispatchedConfirmed",never).put("message",never?"原SQL最终派发门未领取，已确定未写网络；新回答仍需重新原生审阅":"原回答写入未确认，只查询原job/答案编号，不重发").toString());db.update("operations",changed,"operation_id=? AND state=?",new String[]{operation,state});
    }
    public synchronized void recordRemoteQuestionAnswerResult(String id,String operation,JSONObject receipt)throws Exception {
        JSONObject row=remoteOperation(id,operation,"remote_question_answer"),source=row.getJSONObject("arguments").getJSONObject("source");for(String key:new String[]{"profileId","serviceBase"})if(!source.getString(key).equals(receipt.optString(key)))throw new SecurityException("正常问答回执不是原电脑");if(!source.getString("jobId").equals(receipt.optString("id"))||!source.getString("jobOperationId").equals(receipt.optString("operationId")))throw new SecurityException("正常问答回执不是原job/op");settleRemoteQuestionAnswers(id,receipt);JSONObject current=required(id);boolean admission=source.getString("runId").equals(current.optString("runId"))&&source.getInt("coordinatorEpoch")==current.optInt("coordinatorEpoch")&&!current.optBoolean("cancelRequested")&&!hasUnresolved(id);updateExternal(id,receipt,admission);
    }
    private void settleRemoteQuestionAnswers(String id,JSONObject receipt)throws Exception {
        JSONArray receipts=receipt.optJSONArray("questionReceipts");if(receipts==null||receipts.length()>128)return;
        try(Cursor rows=db.rawQuery("SELECT operation_id,arguments,state FROM operations WHERE task_id=? AND tool='remote_question_answer'",new String[]{id})){while(rows.moveToNext()){
            String operation=rows.getString(0),prior=rows.getString(2);JSONObject args=new JSONObject(rows.getString(1)),source=args.getJSONObject("source"),request=args.getJSONObject("request");if(!source.getString("jobOperationId").equals(receipt.optString("operationId"))||!source.getString("jobId").equals(receipt.optString("id"))||!source.getString("serviceBase").equals(receipt.optString("serviceBase"))||!source.getString("profileId").equals(receipt.optString("profileId")))continue;
            for(int i=0;i<receipts.length();i++){JSONObject ack=receipts.getJSONObject(i);if(!operation.equals(ack.optString("answerId")))continue;if(!request.getString("requestId").equals(ack.optString("requestId"))||!request.getString("requestHash").equals(ack.optString("requestHash"))||!args.getString("answerHash").equals(ack.optString("answerHash")))throw new SecurityException("原答案回执内容冲突，只核对原件");String status=ack.optString("status");boolean known=status.matches("sent|resolved|withdrawn|rejected");if(!known&&!status.matches("queued|sending|unknown"))throw new SecurityException("未知正常问答状态");if(prior.equals("remote_question_receipt")&&!known)continue;ContentValues changed=new ContentValues();changed.put("state",known?(status.equals("rejected")?"remote_question_rejected":"remote_question_receipt"):"remote_question_unknown");changed.put("receipt",new JSONObject(ack.toString()).put("jobId",source.getString("jobId")).put("jobOperationId",source.getString("jobOperationId")).toString());db.update("operations",changed,"operation_id=?",new String[]{operation});}
        }}
    }

    public synchronized JSONObject claimRemoteApproval(JSONObject source,String requestId,String decision)throws Exception{
        if(requestId==null||requestId.isEmpty()||requestId.length()>200||requestId.indexOf('\0')>=0||decision==null||!decision.matches("accept|decline|cancel"))throw new IllegalArgumentException("审批编号或决定无效");
        if(required(source.getString("taskId")).optJSONObject("origin")!=null&&required(source.getString("taskId")).getJSONObject("origin").optString("kind").equals("team_remote_assignment"))throw new SecurityException("团队远端制作审批只能走原native owner window");
        String id=messageText(source,"taskId",80),fingerprint=capabilityFingerprint(new JSONObject().put("taskId",id).put("runId",source.getString("runId")).put("jobOperationId",source.getString("jobOperationId")).put("requestId",requestId));
        db.beginTransaction();try{
            try(Cursor old=db.rawQuery("SELECT operation_id,arguments FROM operations WHERE task_id=? AND tool='remote_approval'",new String[]{id})){while(old.moveToNext()){JSONObject args=new JSONObject(old.getString(1));if(fingerprint.equals(args.optString("approvalIdentity"))){if(!decision.equals(args.optString("decision")))throw new SecurityException("此审批已经领取，不能更换决定或重发");db.setTransactionSuccessful();return remoteOwnerOperation(id,old.getString(0)).put("knownDecision",true);}}}
            JSONObject task=requireRemoteSource(source,false);if(!source.optString("executorStatus").matches("running|awaiting_approval"))throw new SecurityException("原执行不再等待授权");
            JSONObject approval=null;JSONArray pending=source.optJSONArray("pendingApprovals");for(int i=0;pending!=null&&i<pending.length();i++)if(requestId.equals(pending.getJSONObject(i).optString("requestId")))approval=pending.getJSONObject(i);
            if(approval==null||decision.equals("accept")&&!Boolean.TRUE.equals(approval.opt("canAccept")))throw new SecurityException("此请求不存在或执行端禁止接受");
            if(!task.optString("parentTaskId").isEmpty()){
                JSONObject parent=required(task.getString("parentTaskId"));boolean team="teams".equals(parent.optString("feature"));for(String key:new String[]{"teamRunId","memberId","round","generation"})team|=task.has(key);
                if(team){if(!parent.optString("feature").equals("teams")||!parent.optString("runId").equals(task.optString("parentRunId"))||parent.optBoolean("cancelRequested")||!parent.optString("status").matches("running|queued")||!task.optBoolean("readOnly")||!source.optString("protocol").equals("codex_app_server")||!task.has("teamRunId")||!task.has("memberId")||!task.has("round")||!task.has("generation"))throw new SecurityException("成员授权的原协调器已失效");}
                else {requirePrivateRemoteChild(id);if(parent.optBoolean("cancelRequested"))throw new SecurityException("原私人父任务已停止，不能批准旧子任务");}
            }
            String operation=UUID.randomUUID().toString();JSONObject args=new JSONObject().put("source",source).put("taskId",id).put("decision",decision).put("requestId",requestId).put("approvalIdentity",fingerprint).put("approval",approval);insertRemoteOperation(operation,id,task.getString("runId"),"remote_approval",args,"remote_approval_sending");db.setTransactionSuccessful();return remoteOwnerOperation(id,operation).put("knownDecision",false);
        }finally{db.endTransaction();}
    }
    public synchronized void recordRemoteApprovalResult(String id,String operation,JSONObject result)throws Exception{
        JSONObject row=remoteOperation(id,operation,"remote_approval"),args=row.getJSONObject("arguments");JSONObject source=args.getJSONObject("source");
        if(!source.getString("jobId").equals(result.optString("id"))||!source.getString("jobOperationId").equals(result.optString("operationId"))||!source.getString("profileId").equals(result.optString("profileId"))||!source.getString("serviceBase").equals(result.optString("serviceBase")))throw new SecurityException("审批回执缺少原电脑执行身份；仅查询原任务");
        settleRemoteApprovals(id,result);
        JSONObject current=required(id),settled=remoteOperation(id,operation,"remote_approval");
        boolean admission=settled.getString("state").equals("remote_approval_receipt")&&source.getString("runId").equals(current.optString("runId"))&&source.getInt("coordinatorEpoch")==current.optInt("coordinatorEpoch")&&!current.optBoolean("cancelRequested")&&!hasUnresolved(id);
        updateExternal(id,result,admission);
    }
    private void settleRemoteApprovals(String id,JSONObject receipt)throws Exception{
        JSONArray receipts=receipt.optJSONArray("approvalReceipts");if(receipts==null)return;
        try(Cursor rows=db.rawQuery("SELECT operation_id,arguments FROM operations WHERE task_id=? AND run_id=? AND tool='remote_approval'",new String[]{id,receipt.optString("operationId")})){
            while(rows.moveToNext()){JSONObject args=new JSONObject(rows.getString(1));for(int i=0;i<receipts.length();i++){JSONObject ack=receipts.getJSONObject(i);if(args.getString("requestId").equals(ack.optString("requestId"))&&args.getString("decision").equals(ack.optString("decision"))&&ack.optString("status").matches("sent|resolved")){ContentValues changed=new ContentValues();changed.put("state","remote_approval_receipt");changed.put("receipt",sanitize(ack).toString());db.update("operations",changed,"operation_id=?",new String[]{rows.getString(0)});}}}
        }
    }

    /** Owner-only host entry: append input to the exact live Codex turn, never resume a task. */
    public synchronized JSONObject prepareRemoteMessage(JSONObject input)throws Exception{
        String taskId=messageText(input,"taskId",80),messageId=messageText(input,"messageId",80),text=messageText(input,"text",12000);
        if(!messageId.matches("[A-Za-z0-9_-]{1,80}"))throw new IllegalArgumentException("补充消息编号无效");
        String hash=capabilityFingerprint(new JSONObject().put("text",text));
        db.beginTransaction();try{
            JSONObject old=remoteMessageRow(messageId);
            if(old!=null){
                requireRemoteOwner(required(taskId));
                JSONObject binding=old.getJSONObject("arguments");
                if(!taskId.equals(binding.optString("taskId"))||!hash.equals(binding.optString("textHash")))throw new SecurityException("该补充编号已绑定另一任务或内容");
                compareMessageBinding(binding,input);
                db.setTransactionSuccessful();return messageView(old).put("knownMessage",true);
            }
            JSONObject task=required(taskId),binding=currentRemoteMessageBinding(task);
            compareMessageBinding(binding,input);checkRemoteMessageActive(task,binding);
            try(Cursor count=db.rawQuery("SELECT count(*),sum(CASE WHEN state!='message_receipt' THEN 1 ELSE 0 END) FROM operations WHERE task_id=? AND tool='remote_message'",new String[]{taskId})){
                count.moveToFirst();if(count.getInt(0)>=64)throw new IllegalStateException("此任务已达到64条补充消息上限");
                if(count.getInt(1)>0)throw new IllegalStateException("上一条补充仍在等待回执；只核对原编号，不重复发送");
            }
            binding.put("messageId",messageId).put("textHash",hash).put("textLength",text.length());
            JSONObject receipt=messageReceipt(binding,"queued","补充已在手机登记，尚未确认远端接收").put("dispatched",false);
            ContentValues row=new ContentValues();row.put("operation_id",messageId);row.put("task_id",taskId);row.put("run_id",binding.getString("runId"));row.put("tool","remote_message");row.put("arguments",binding.toString());row.put("state","message_prepared");row.put("receipt",receipt.toString());row.put("created_at",System.currentTimeMillis());db.insertOrThrow("operations",null,row);
            db.setTransactionSuccessful();return new JSONObject(binding.toString()).put("status","queued").put("knownMessage",false);
        }finally{db.endTransaction();}
    }
    /** Short local dispatch fence only. The caller must release this monitor before any network I/O. */
    public synchronized JSONObject claimRemoteMessageDispatch(String taskId,String messageId)throws Exception{
        JSONObject row=requireRemoteMessage(taskId,messageId),binding=row.getJSONObject("arguments");
        if(!row.getString("state").equals("message_prepared"))throw new SecurityException("该补充已派发或结果待查，不能重复发送");
        checkRemoteMessageActive(required(taskId),binding);
        ContentValues update=new ContentValues();update.put("state","message_sending");update.put("receipt",messageReceipt(binding,"sending","正在向原turn发送补充；接收结果尚未确认").put("dispatched",JSONObject.NULL).toString());
        if(db.update("operations",update,"operation_id=? AND task_id=? AND state='message_prepared'",new String[]{messageId,taskId})!=1)throw new SecurityException("补充消息派发身份已变化");
        return new JSONObject(binding.toString());
    }
    /** Read the original binding even after cancellation/restart; this never grants a new dispatch. */
    public synchronized JSONObject remoteMessage(String taskId,String messageId)throws Exception{return messageView(requireRemoteMessage(taskId,messageId));}
    /** Message receipts cannot update task/run terminal state, including when an ACK arrives late. */
    public synchronized JSONObject recordRemoteMessage(JSONObject receipt)throws Exception{
        String messageId=messageText(receipt,"messageId",80);JSONObject row=remoteMessageRow(messageId);
        if(row==null||!row.getString("tool").equals("remote_message"))throw new SecurityException("没有此补充消息的原生登记");
        JSONObject binding=row.getJSONObject("arguments");requireRemoteOwner(required(binding.getString("taskId")));
        for(String key:new String[]{"profileId","jobId","jobOperationId","threadId","expectedTurnId"})if(!binding.getString(key).equals(receipt.optString(key)))throw new SecurityException("补充回执不属于原电脑任务、会话或turn");
        if(!messageId.equals(receipt.optString("operationId",messageId)))throw new SecurityException("补充回执操作编号不匹配");
        String status=receipt.optString("status");if(!status.matches("queued|sending|accepted|unknown|rejected|not_found"))throw new IllegalArgumentException("补充回执状态无效");
        if(status.equals("accepted")&&!binding.getString("expectedTurnId").equals(receipt.optString("turnId")))throw new SecurityException("补充未取得原turn的接收回执");
        JSONObject previous=row.optJSONObject("receipt");String prior=previous==null?"":previous.optString("status");
        if(prior.matches("accepted|rejected")){
            if(status.matches("accepted|rejected")&&!prior.equals(status))throw new SecurityException("补充消息的确定回执发生冲突，请核对执行端");
            return messageView(row);
        }
        if(prior.equals("sending")&&status.equals("queued"))return messageView(row);
        JSONObject clean=messageReceipt(binding,status.equals("not_found")?"unknown":status,receipt.optString("message","已取得原补充消息回执"));
        for(String key:new String[]{"turnId","dispatched","errorCode","updatedAt"})if(receipt.has(key))clean.put(key,receipt.get(key));
        String state=status.matches("accepted|rejected")?"message_receipt":status.matches("unknown|not_found")?"message_unknown":"message_sending";
        ContentValues update=new ContentValues();update.put("state",state);update.put("receipt",clean.toString());db.update("operations",update,"operation_id=? AND tool='remote_message'",new String[]{messageId});
        return messageView(requireRemoteMessage(binding.getString("taskId"),messageId));
    }
    private JSONObject currentRemoteMessageBinding(JSONObject task)throws Exception{
        requireRemoteOwner(task);JSONObject evidence=task.optJSONObject("evidence"),refs=task.optJSONObject("externalRefs");
        if(evidence==null||refs==null||!evidence.optString("protocol").equals("codex_app_server"))throw new SecurityException("原任务尚未确认Codex会话");
        JSONObject binding=new JSONObject().put("taskId",task.getString("id")).put("runId",messageText(task,"runId",80)).put("coordinatorEpoch",task.getInt("coordinatorEpoch"))
                .put("profileId",messageText(refs,"profileId",80)).put("jobId",messageText(task,"externalId",80)).put("jobOperationId",messageText(task,"operationId",80))
                .put("threadId",messageText(evidence,"threadId",200)).put("expectedTurnId",messageText(evidence,"turnId",200));
        if(!binding.getString("jobOperationId").equals(evidence.optString("operationId"))||!binding.getString("profileId").equals(evidence.optString("profileId")))throw new SecurityException("原电脑任务回执身份未确认");
        return binding;
    }
    private void checkRemoteMessageActive(JSONObject task,JSONObject binding)throws Exception{
        compareMessageBinding(currentRemoteMessageBinding(task),binding);JSONObject evidence=task.getJSONObject("evidence"),controls=evidence.optJSONObject("controls");
        if(task.optBoolean("cancelRequested")||!task.optString("status").matches("running|awaiting_user")||!evidence.optString("executorStatus").matches("running|awaiting_approval")||controls==null||!controls.optBoolean("inputSteering")||hasUnresolved(task.getString("id")))throw new SecurityException("原turn已停止、待核对或不支持运行中补充");
        if(!task.optString("parentTaskId").isEmpty()){
            JSONObject parent=required(task.getString("parentTaskId"));requirePrivateOwner(parent);
            if(!task.optString("parentRunId").equals(parent.optString("runId"))||parent.optBoolean("cancelRequested")||!parent.optString("status").matches("running|queued"))throw new SecurityException("父任务已暂停、停止或切换执行，不能继续输入");
            if(task.has("generation")&&(parent.optJSONObject("evidence")==null||task.getInt("generation")!=parent.getJSONObject("evidence").optInt("generation",-1)))throw new SecurityException("团队执行代次已改变，不能向旧成员补充");
        }
    }
    private static void compareMessageBinding(JSONObject original,JSONObject supplied)throws Exception{
        for(String key:new String[]{"taskId","runId","profileId","jobId","jobOperationId","threadId","expectedTurnId"})if(!original.getString(key).equals(supplied.optString(key)))throw new SecurityException("补充目标已变化，请重新查看原任务");
        if(!(supplied.opt("coordinatorEpoch") instanceof Integer||supplied.opt("coordinatorEpoch") instanceof Long)||original.getLong("coordinatorEpoch")!=supplied.getLong("coordinatorEpoch"))throw new SecurityException("补充执行代次已失效");
    }
    private static void requirePrivateOwner(JSONObject task){if(!task.optString("actorId").equals("owner")||!task.optString("spaceId").equals("private"))throw new SecurityException("仅私人原生主人可补充任务");}
    private static void requireRemoteOwner(JSONObject task){requirePrivateOwner(task);if(!task.optString("feature").equals("remote"))throw new SecurityException("该任务不是原电脑任务");}
    private static String messageText(JSONObject object,String key,int limit)throws Exception{Object raw=object.opt(key);if(!(raw instanceof String)||((String)raw).trim().isEmpty()||((String)raw).length()>limit||((String)raw).indexOf('\0')>=0)throw new IllegalArgumentException("补充字段无效："+key);return (String)raw;}
    private JSONObject remoteMessageRow(String messageId)throws Exception{
        try(Cursor c=db.rawQuery("SELECT task_id,run_id,tool,arguments,state,receipt FROM operations WHERE operation_id=?",new String[]{messageId})){
            if(!c.moveToFirst())return null;if(!c.getString(2).equals("remote_message"))throw new SecurityException("补充编号已被其他操作占用");
            JSONObject row=new JSONObject().put("tool",c.getString(2)).put("arguments",new JSONObject(c.getString(3))).put("state",c.getString(4));if(!c.isNull(5))row.put("receipt",new JSONObject(c.getString(5)));return row;
        }
    }
    private JSONObject requireRemoteMessage(String taskId,String messageId)throws Exception{requireRemoteOwner(required(taskId));JSONObject row=remoteMessageRow(messageId);if(row==null||!taskId.equals(row.getJSONObject("arguments").optString("taskId")))throw new SecurityException("补充消息不属于该任务");return row;}
    private static JSONObject messageReceipt(JSONObject binding,String status,String message)throws Exception{
        JSONObject result=new JSONObject().put("messageId",binding.getString("messageId")).put("operationId",binding.getString("messageId")).put("status",status).put("message",message.length()>600?message.substring(0,600):message);
        for(String key:new String[]{"taskId","runId","coordinatorEpoch","profileId","jobId","jobOperationId","threadId","expectedTurnId"})result.put(key,binding.get(key));return result;
    }
    private static JSONObject messageView(JSONObject row)throws Exception{JSONObject result=new JSONObject(row.getJSONObject("arguments").toString());result.remove("textHash");result.put("journalState",row.getString("state")).put("knownMessage",true);JSONObject receipt=row.optJSONObject("receipt");if(receipt!=null)result.put("receipt",new JSONObject(receipt.toString())).put("status",receipt.optString("status","unknown"));return result;}
    /** Resume an executor lease on the existing task; this does not replay previous operations. */
    public synchronized JSONObject resumeExternalAttempt(String id,String feature)throws Exception{
        JSONObject task=required(id);
        if(!feature.equals("browser")||!feature.equals(task.optString("feature"))||!feature.equals(task.optString("kind")))throw new IllegalArgumentException("该任务不是可继续的浏览器任务");
        if(hasUnresolved(id))throw new IllegalStateException("上次浏览器动作结果未决，请先核对后继续原任务");
        String status=task.optString("status");
        if(!status.matches("cancelled|awaiting_user|interrupted|failed|needs_confirmation|paused|reconciling"))throw new IllegalStateException(status.equals("completed")?"任务已由主人确认完成":"请先停止或接管当前浏览器执行");
        String previousRun=task.optString("runId"),run=UUID.randomUUID().toString();int epoch=task.optInt("coordinatorEpoch")+1;
        task.put("runId",run).put("coordinatorEpoch",epoch).put("cancelRequested",false).put("status","queued").put("executionStatus","queued").put("summary","正在继续原浏览器任务，旧页面引用已失效，需要重新观察");
        task.remove("externalUpdatedAt");
        appendEvent(task,new JSONObject().put("type","attempt_resumed").put("runId",run).put("previousRunId",previousRun).put("coordinatorEpoch",epoch));
        return copy(task,false);
    }
    private static JSONObject base(String id,String goal,String kind,String mode)throws Exception{return new JSONObject().put("id",id).put("goal",goal).put("kind",kind).put("executionMode",mode).put("actorId","owner").put("spaceId","private").put("coordinatorEpoch",0).put("status","preparing").put("summary","").put("createdAt",System.currentTimeMillis()).put("updatedAt",System.currentTimeMillis()).put("events",new JSONArray()).put("evidence",new JSONObject()).put("feedback","").put("cancelRequested",false);}
    /** Native private call source, original team document and original SQLite pool; never model JSON. */
    private JSONObject teamCoordinatorOwner(dev.pocketengine.features.teams.TeamCoordinator.InferenceCall call)throws Exception {
        call.checkBudgetCurrent();JSONObject source=call.source();if(source.getInt("generation")!=call.site.runGeneration)throw new SecurityException("原团队计量代次已变");JSONObject owner=originalTeamBudgetOwner(call.nativeRunId,source,false);call.checkBudgetCurrent();return owner;
    }
    public synchronized void bindTeamCoordinatorBudget(dev.pocketengine.features.teams.TeamCoordinator.InitialBudgetPlan plan)throws Exception {
        plan.checkCurrent();JSONObject source=plan.view();if(source.optInt("callsUsed")!=0||source.optInt("toolCallsUsed")!=0)throw new SecurityException("初始预算必须在任何成员派发之前捕获");db.beginTransaction();try{originalTeamBudgetOwner(source.getString("id"),source,true);plan.checkCurrent();db.setTransactionSuccessful();}finally{db.endTransaction();}
    }
    private JSONObject originalTeamBudgetOwner(String nativeRunId,JSONObject source,boolean create)throws Exception {
        JSONObject team=teamWriteTask(nativeRunId);requirePrivateOwner(team);if(!nativeRunId.equals(source.getString("id"))||team.optBoolean("cancelRequested"))throw new SecurityException("原团队预算来源已变");
        String parentId=team.optString("parentTaskId"),parentRun=team.optString("parentRunId");JSONObject owner;
        if(!parentId.isEmpty()){
            owner=required(parentId);requirePrivateOwner(owner);JSONObject permission=grant(parentRun);
            if(!parentRun.equals(owner.optString("runId"))||owner.optBoolean("cancelRequested")||owner.optString("status").matches("completed|stopping|cancelled|interrupted|paused|pausing")||permission==null||permission.optLong("expiresAt")<=System.currentTimeMillis())throw new SecurityException("原团队父执行授权已停止或到期");
        }else{owner=team;parentRun=nativeRunId;}
        JSONObject pool=owner.optJSONObject("teamReservationPool");
        if(pool==null){if(!create)throw new SecurityException("原团队计量没有已捕获父pool");JSONObject initial=dev.pocketengine.features.teams.TeamCoordinator.initialBudget(source.getJSONArray("members").length(),source.getInt("rounds"));
            if(source.getInt("callLimit")!=initial.getInt("callLimit")||source.getInt("toolCallLimit")!=initial.getInt("toolCallLimit")||source.optInt("callsUsed")!=0||source.optInt("toolCallsUsed")!=0)throw new SecurityException("不能用后续/旧讨论快照补建或刷新初始预算");
            int tools=initial.getInt("toolCallLimit");if(!parentId.isEmpty())tools=Math.min(tools,grant(parentRun).getInt("remainingOperations"));
            pool=dev.pocketengine.features.teams.TeamParentBudget.create(owner.getString("id"),parentRun,initial.getInt("callLimit"),tools,new JSONObject().put("kind",parentId.isEmpty()?"original_native_team_plan":"original_team_plan_and_parent_grant").put("initialBudget",initial).put("teamRunId",nativeRunId));owner.put("teamReservationPool",pool);write(owner);
            team=teamWriteTask(nativeRunId);team.put("teamCoordinatorBudget",new JSONObject().put("parentTaskId",owner.getString("id")).put("parentRunId",parentRun).put("initialBudget",initial));write(team);
        }
        if(!pool.getString("parentRunId").equals(parentRun))throw new SecurityException("原父pool不能跨run补满或挪给新执行");team=teamWriteTask(nativeRunId);if(team.optJSONObject("teamCoordinatorBudget")==null){team.put("teamCoordinatorBudget",new JSONObject().put("parentTaskId",owner.getString("id")).put("parentRunId",parentRun));write(team);}return required(owner.getString("id"));
    }
    public synchronized JSONObject reserveTeamCoordinatorInference(dev.pocketengine.features.teams.TeamCoordinator.InferenceCall call)throws Exception {
        db.beginTransaction();try{JSONObject owner=teamCoordinatorOwner(call),pool=teamBudget(owner),team=teamWriteTask(call.nativeRunId),site=call.budgetIdentity();
            checkTeamInferenceDocument(team.getString("id"),call.nativeRunId,call.site);
            JSONObject binding=new JSONObject().put("teamRunId",call.nativeRunId).put("childTaskId",team.getString("id")).put("childRunId",call.nativeRunId).put("childEpoch",0).put("runGeneration",call.site.runGeneration).put("memberGeneration",call.site.memberGeneration).put("memberId",call.memberId).put("assignmentId","").put("nativeCallSite",site).put("sourceExecution",call.executionIdentity()).put("dispatchKind","coordinator");
            JSONObject row=dev.pocketengine.features.teams.TeamParentBudget.reserve(pool,binding,call.purpose==InferenceReceipt.Purpose.SUMMARY?"summary":"member","coordinator:"+call.site.turnId,1,0);owner.put("teamReservationPool",pool);write(owner);call.checkBudgetCurrent();db.setTransactionSuccessful();return new JSONObject(row.toString()).put("parentBudget",dev.pocketengine.features.teams.TeamParentBudget.snapshot(pool));
        }finally{db.endTransaction();}
    }
    /** Gateway has returned/thrown: exact not-started native site only. A started/unknown request never refunds. */
    public synchronized void releaseTeamCoordinatorInference(dev.pocketengine.features.teams.TeamCoordinator.InferenceCall call)throws Exception {
        db.beginTransaction();try{JSONObject team=teamWriteTask(call.nativeRunId),owner=teamBudgetOwner(team),pool=teamBudget(owner);dev.pocketengine.features.teams.TeamParentBudget.releaseInferenceSite(pool,team.getString("id"),call.nativeRunId,call.budgetIdentity());owner.put("teamReservationPool",pool);write(owner);db.setTransactionSuccessful();}finally{db.endTransaction();}
    }
    public synchronized JSONObject beginTeamCoordinatorRead(dev.pocketengine.features.teams.TeamCoordinator.InferenceCall call,String tool,JSONObject arguments)throws Exception {
        db.beginTransaction();try{JSONObject owner=teamCoordinatorOwner(call),pool=teamBudget(owner),team=teamWriteTask(call.nativeRunId);String operation=UUID.randomUUID().toString();
            JSONObject binding=new JSONObject().put("teamRunId",call.nativeRunId).put("childTaskId",team.getString("id")).put("childRunId",call.nativeRunId).put("childEpoch",0).put("memberId",call.memberId).put("assignmentId","").put("nativeCallSite",call.budgetIdentity()).put("sourceExecution",call.executionIdentity()).put("dispatchKind","coordinator_read");
            JSONObject reservation=dev.pocketengine.features.teams.TeamParentBudget.reserve(pool,binding,call.purpose==InferenceReceipt.Purpose.SUMMARY?"summary":"member","operation:"+operation,0,1);spendTeamParentToolGrant(owner,pool);reservation.getJSONArray("operationIds").put(operation);reservation.put("state","unknown");
            ContentValues row=new ContentValues();row.put("operation_id",operation);row.put("task_id",team.getString("id"));row.put("run_id",call.nativeRunId);row.put("tool",tool);row.put("arguments",sanitize(arguments).toString());row.put("state","unknown");row.put("receipt",new JSONObject().put("status","unknown").put("nativeTeamRead",true).toString());row.put("created_at",System.currentTimeMillis());db.insertOrThrow("operations",null,row);owner.put("teamReservationPool",pool);write(owner);call.checkBudgetCurrent();db.setTransactionSuccessful();return new JSONObject(binding.toString()).put("operationId",operation).put("reservationId",reservation.getString("reservationId"));
        }finally{db.endTransaction();}
    }
    public synchronized JSONObject beginTeamAssignmentRead(JSONObject binding,String tool,JSONObject args,RunControl original)throws Exception {
        db.beginTransaction();try{checkTeamAssignment(binding,tool,original);JSONObject team=teamWriteTask(binding.getString("teamRunId"));if(team.optJSONObject("teamWorkPlan")==null)throw new SecurityException("本次精确读取计量只用于已批原工作计划");JSONObject bound=teamBudgetBinding(binding),owner=teamBudgetOwner(team),pool=teamBudget(owner);String op=UUID.randomUUID().toString();
            JSONObject permission=grant(binding.getString("runId"));if(permission==null||permission.optInt("remainingOperations")<1)throw new SecurityException("原child操作额度已耗尽");permission.put("remainingOperations",permission.getInt("remainingOperations")-1);ContentValues g=new ContentValues();g.put("grant_json",permission.toString());db.update("runs",g,"run_id=?",new String[]{binding.getString("runId")});
            String phase=required(binding.getString("taskId")).getJSONObject("origin").getString("kind").equals("team_review")?"review":required(binding.getString("taskId")).getJSONObject("origin").getString("kind").equals("team_integration")?"integration":"member";JSONObject row=dev.pocketengine.features.teams.TeamParentBudget.reserve(pool,bound,phase,"operation:"+op,0,1);spendTeamParentToolGrant(owner,pool);row.getJSONArray("operationIds").put(op);row.put("state","unknown");ContentValues operation=new ContentValues();operation.put("operation_id",op);operation.put("task_id",binding.getString("taskId"));operation.put("run_id",binding.getString("runId"));operation.put("tool",tool);operation.put("arguments",sanitize(args).toString());operation.put("state","unknown");JSONObject projectSelection=teamProjectSelection(required(binding.getString("taskId")));if(projectSelection!=null&&dev.pocketengine.features.local.project.ProjectCapabilityPort.isTeamProjectRead(tool))operation.put("receipt",new JSONObject().put("format","pocketworld.team-project-native-read-pending.v1").put("nativeTeamProjectReadPending",true).put("reservationId",row.getString("reservationId")).put("teamRunId",binding.getString("teamRunId")).put("selectionHash",projectSelection.getString("selectionHash")).put("argumentsHash",capabilityFingerprint(sanitize(args))).put("coordinatorEpoch",binding.getInt("coordinatorEpoch")).toString());operation.put("created_at",System.currentTimeMillis());db.insertOrThrow("operations",null,operation);owner.put("teamReservationPool",pool);write(owner);db.setTransactionSuccessful();return new JSONObject(bound.toString()).put("operationId",op).put("reservationId",row.getString("reservationId"));
        }finally{db.endTransaction();}
    }
    public synchronized boolean teamUsesWorkItems(String teamRun)throws Exception{return teamWriteTask(teamRun).optJSONObject("teamWorkPlan")!=null;}
    /** Original owner-only project read facts; no model can attach a checkout through JSON. */
    public synchronized JSONObject teamProjectParentSelectionFacts(String teamRun)throws Exception {JSONObject parent=teamWorkPlanParent(teamRun),task=required(parent.getString("parentTaskId"));return parent.put("developmentContext",new JSONObject(task.getJSONObject("developmentContext").toString()));}
    private JSONObject teamProjectSelection(JSONObject task)throws Exception {
        JSONObject identity=task.optJSONObject("teamAssignment"),origin=task.optJSONObject("origin");if(identity==null||origin==null||!origin.optString("kind").matches("team_assignment|team_integration"))return null;
        JSONObject team=teamWriteTask(identity.getString("teamRunId")),scope=team.getJSONObject("teamWriteScope");if(scope.optJSONObject("projectSelection")==null)return null;
        JSONObject selected;if(origin.getString("kind").equals("team_integration"))selected=dev.pocketengine.features.teams.TeamProjectWorkspaces.forIntegrator(scope.getJSONObject("projectSelection"));else{selected=identity.optJSONObject("projectSelection");if(selected==null)throw new SecurityException("原团队完整项目选择未绑定成员");JSONObject item=currentTeamWorkItem(team,identity);if(item==null||!canonical(selected).equals(canonical(dev.pocketengine.features.teams.TeamProjectWorkspaces.forWorkItem(scope.getJSONObject("projectSelection"),item))))throw new SecurityException("原项目选择不属于当前workItem/ownerGeneration");}
        return new JSONObject(selected.toString());
    }
    private JSONObject teamProjectAssociation(JSONObject task,String run,int epoch)throws Exception {
        requirePrivateOwner(task);JSONObject selected=teamProjectSelection(task),context=task.optJSONObject("teamProject");if(selected==null||context==null)throw new SecurityException("原任务没有实际登记的团队完整副本");JSONObject identity=task.getJSONObject("teamAssignment");if(!context.getString("assignmentId").equals(identity.getString("assignmentId"))||context.optInt("assignmentRevision",1)!=identity.optInt("assignmentRevision",1)||!selected.getString("selectionHash").equals(context.getJSONObject("selection").getString("selectionHash"))||!identity.optString("workItemId").equals(context.optString("workItemId"))||identity.optInt("itemRevision")!=context.optInt("itemRevision")||identity.optInt("ownerGeneration")!=context.optInt("ownerGeneration")||!identity.optString("planHash").equals(context.optString("integrationPlanHash")))throw new SecurityException("原团队副本已改版/改派/换整合计划");
        try(Cursor actual=db.rawQuery("SELECT epoch FROM runs WHERE task_id=? AND run_id=?",new String[]{task.getString("id"),run})){if(!actual.moveToFirst()||epoch!=actual.getInt(0))throw new SecurityException("原项目副本没有该实际child run/epoch");}return new JSONObject(context.toString());
    }
    private JSONObject teamProjectNativeBinding(JSONObject task,String run,int epoch)throws Exception {JSONObject binding=new JSONObject(task.getJSONObject("teamAssignment").toString()).put("taskId",task.getString("id")).put("runId",run).put("coordinatorEpoch",epoch),nativeRun=task.optJSONObject("teamAssignmentNativeRun");if(nativeRun!=null&&run.equals(nativeRun.optString("runId")))binding.put("runGeneration",nativeRun.getInt("runGeneration")).put("memberGeneration",nativeRun.getInt("memberGeneration"));return binding;}
    private void authorizeTeamProjectTools(String id,String run)throws Exception {JSONArray tools=grant(run).optJSONArray("tools");if(tools==null)tools=new JSONArray();else tools=new JSONArray(tools.toString());for(String name:new String[]{"project_inspect","project_read_file","project_search","project_plan_manifest","project_plan","project_execute","project_status","project_log","project_changes","project_review_file","project_process_input","project_stop"}){boolean exists=false;for(int i=0;i<tools.length();i++)exists|=name.equals(tools.getString(i));if(!exists)tools.put(name);}if(required(id).getJSONObject("origin").optString("kind").equals("team_integration"))tools.put("project_package");authorizeTools(id,run,tools);}
    public synchronized void bindTeamProjectCheckout(JSONObject binding,String operation,dev.pocketengine.features.local.LocalModule.TeamProjectCheckout physical,RunControl original)throws Exception {
        db.beginTransaction();try{checkTeamAssignment(binding,teamProjectReadTool(binding),original);JSONObject task=teamAssignmentIdentity(binding),selection=teamProjectSelection(task);if(selection==null)throw new SecurityException("主人未批准此原完整项目范围");JSONObject actual=physical.view(),copy=actual.getJSONObject("teamProjectCopy"),nativeBinding=copy.getJSONObject("nativeBinding");for(String key:new String[]{"taskId","runId","coordinatorEpoch","assignmentId"})if(!canonical(binding.get(key)).equals(canonical(nativeBinding.get(key))))throw new SecurityException("物理副本不是此原成员执行");if(!operation.equals(nativeBinding.getString("operationId"))||!selection.getString("snapshotIdentity").equals(copy.getString("snapshotIdentity"))||!copy.optBoolean("fullSourceCopy")||copy.optBoolean("osSandboxVerified"))throw new SecurityException("原完整副本缺少准确基线/复制证明");
            boolean integrator=task.getJSONObject("origin").optString("kind").equals("team_integration");if(integrator!=actual.getString("projectId").equals(binding.getString("targetProjectId")))throw new SecurityException("作者不得直接编辑原项目；组合副本仅属于原唯一整合者");try(Cursor row=db.rawQuery("SELECT tool,state FROM operations WHERE task_id=? AND run_id=? AND operation_id=?",new String[]{binding.getString("taskId"),binding.getString("runId"),operation})){if(!row.moveToFirst()||!row.getString(0).equals(integrator?"team_prepare_integrated_project":"team_prepare_draft")||!row.getString(1).equals("unknown"))throw new SecurityException("完整复制没有原已claim的prepared操作");}
            JSONObject old=task.optJSONObject("teamProject");if(old!=null)throw new SecurityException("原项目副本已登记，不覆盖或另建替代副本");JSONArray manifest=actual.has("sourceManifest")?actual.getJSONArray("sourceManifest"):copy.getJSONArray("sourceManifest");String digest=dev.pocketengine.features.local.project.ProjectSources.manifestDigest(manifest);if(!digest.equals(copy.getString("sourceDigest")))throw new SecurityException("复制的实际完整源码清单不一致");JSONObject identity=task.getJSONObject("teamAssignment"),context=new JSONObject().put("projectId",actual.getString("projectId")).put("checkoutId",actual.getString("id")).put("runtime",selection.getString("runtimeId")).put("selection",selection).put("assignmentId",binding.getString("assignmentId")).put("assignmentRevision",binding.optInt("assignmentRevision",1)).put("workItemId",identity.optString("workItemId")).put("itemRevision",identity.optInt("itemRevision")).put("ownerGeneration",identity.optInt("ownerGeneration")).put("integrationPlanHash",identity.optString("planHash")).put("initialSourceManifest",manifest).put("initialSourceDigest",digest).put("initialStructure",copy.getJSONObject("structure")).put("creationOperationId",operation).put("nativeCopyBinding",nativeBinding).put("executionAuthorized",false);
            if(integrator){JSONObject fixed=task.getJSONObject("teamProjectIntegratedSource");if(!operation.equals(fixed.getString("creationOperationId"))||!digest.equals(fixed.getString("expectedSourceDigest")))throw new SecurityException("实际完整组合副本与原已审版本清单不同");context.put("fixedResult",fixed);}
            task.put("teamProject",context);write(task);authorizeTeamProjectTools(task.getString("id"),binding.getString("runId"));db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
    private String teamProjectReadTool(JSONObject binding)throws Exception{JSONObject task=teamAssignmentIdentity(binding);return task.has("teamProject")&&teamProjectSelection(task)!=null?"project_inspect":task.getJSONObject("origin").optString("kind").equals("team_integration")?"team_read_integration":"team_list_files";}
    /** Native liveness fence uses the original registered project read gate after the actual copy is bound.
     * This permits only its proven owned-service reads; it never authorizes an unknown effect or dispatch. */
    public synchronized void checkTeamAssignmentExecution(JSONObject binding,RunControl original)throws Exception{checkTeamAssignment(binding,teamProjectReadTool(binding),original);}
    public synchronized void checkTeamAssignmentExecutionCurrent(JSONObject binding)throws Exception{checkTeamAssignmentCurrent(binding,teamProjectReadTool(binding));}
    public synchronized JSONObject teamProjectContextIfReady(JSONObject binding,RunControl original)throws Exception {checkTeamAssignment(binding,teamProjectReadTool(binding),original);JSONObject task=teamAssignmentIdentity(binding);return task.optJSONObject("teamProject")==null?null:teamProjectAssociation(task,binding.getString("runId"),binding.getInt("coordinatorEpoch"));}
    public synchronized JSONObject teamProjectContext(JSONObject binding,RunControl original)throws Exception {JSONObject result=teamProjectContextIfReady(binding,original);if(result==null)throw new SecurityException("原完整项目草稿尚未实际创建");return result;}
    private JSONObject teamProjectContextForPlan(String id,String run,int epoch,JSONObject plan)throws Exception {JSONObject context=teamProjectAssociation(required(id),run,epoch);dev.pocketengine.features.teams.TeamProjectWorkspaces.validatePlan(context,plan);return context;}
    /** Pure original-row query shared by private and typed team hosts; no origin is erased. */
    public synchronized JSONObject originalProjectRequestForHost(JSONObject binding)throws Exception {
        JSONObject task=required(binding.getString("taskId")),row=projectOperationForHost(binding);if(teamProjectSelection(task)==null)return dev.pocketengine.features.local.project.ProjectDevelopment.originalPollRequest(task,row);
        JSONObject args=row.getJSONObject("arguments"),plan=args.getJSONObject("plan"),context=teamProjectContextForPlan(task.getString("id"),binding.getString("runId"),binding.getInt("coordinatorEpoch"),plan),route=row.getJSONObject("receipt").getJSONObject("projectExecution");for(String key:new String[]{"taskId","runId","coordinatorEpoch","operationId"})if(!canonical(binding.get(key)).equals(canonical(route.get(key))))throw new SecurityException("原团队项目回读不是该SQL操作");JSONObject exact=dev.pocketengine.features.local.project.ProjectDevelopment.exact(plan,dev.pocketengine.features.local.project.ProjectBuildPlan.step(plan,args.getString("stepId")));for(String key:new String[]{"command","projectId","checkoutId","sourceDigest","planDigest","stepId","intent","requestDigest"})if(!canonical(exact.get(key)).equals(canonical(route.opt(key))))throw new SecurityException("原团队项目回读的方案路由已变化");if(!route.optString("authority").equals("native_host_operation")||!binding.getString("operationId").equals(route.optString("jobId")))throw new SecurityException("原团队项目回读缺少原native操作绑定");JSONObject result=new JSONObject(args.toString()).put("checkoutId",context.getString("checkoutId")).put("binding",new JSONObject(binding.toString()));JSONObject journal=row.getJSONObject("journalArguments");if(journal.has("planRef"))result.put("planRef",journal.getString("planRef"));return result;
    }
    private void reserveTeamProjectPrepared(String id,String run,int epoch,String operation,String tool)throws Exception {
        JSONObject task=required(id);if(teamProjectSelection(task)==null||!tool.matches("project_(execute|process_input|stop)"))return;JSONObject binding=teamProjectNativeBinding(task,run,epoch);teamProjectAssociation(task,run,epoch);JSONObject bound=teamBudgetBinding(binding),owner=teamPoolOwnerForChild(task),pool=teamBudget(owner);String phase=task.getJSONObject("origin").getString("kind").equals("team_integration")?"integration":"member";JSONObject row=dev.pocketengine.features.teams.TeamParentBudget.reserve(pool,bound,phase,"operation:"+operation,0,1);spendTeamParentToolGrant(owner,pool);row.getJSONArray("operationIds").put(operation);row.put("state","reserved");owner.put("teamReservationPool",pool);write(owner);
    }
    /** Settlement only, after the real native process/file contract was checked. No refund, tool or new run. */
    private void settleTeamProjectNativeBudget(JSONObject binding,boolean terminal,JSONObject backend)throws Exception {
        if(!terminal)return;JSONObject task=required(binding.getString("taskId"));if(teamProjectSelection(task)==null)return;JSONObject owner=teamPoolOwnerForChild(task),pool=teamBudget(owner);JSONArray rows=pool.getJSONArray("reservations");boolean found=false;for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);if(!binding.getString("taskId").equals(row.optString("childTaskId"))||!binding.getString("runId").equals(row.optString("childRunId"))||row.optInt("toolOperations")!=1)continue;JSONArray operations=row.getJSONArray("operationIds");if(operations.length()!=1||!binding.getString("operationId").equals(operations.getString(0)))continue;if(row.optString("state").equals("released_unstarted"))throw new SecurityException("迟账不能复活已释放预留");row.put("state","settled").put("actualNativeReceiptStatus",backend.getString("status")).put("nativeSettlementAt",System.currentTimeMillis());JSONArray refs=row.getJSONArray("actualReceiptRefs");boolean seen=false;for(int n=0;n<refs.length();n++)seen|=binding.getString("operationId").equals(refs.getString(n));if(!seen)refs.put(binding.getString("operationId"));found=true;}if(!found)throw new SecurityException("原团队原生项目回执缺少同child/run/op父预留");owner.put("teamReservationPool",pool);write(owner);
    }
    private JSONObject teamProjectFileGrant(JSONObject task,JSONObject plan)throws Exception {
        JSONObject selection=teamProjectSelection(task);if(selection==null||!plan.optString("intent").matches("edit|patch"))return null;if(!selection.getString("role").equals("author"))throw new SecurityException("唯一整合者不得另写组合源码");JSONObject team=teamWriteTask(task.getJSONObject("teamAssignment").getString("teamRunId")),item=currentTeamWorkItem(team,task.getJSONObject("teamAssignment")),scope=item.getJSONObject("draftScope");if(!scope.optString("state").equals("active")||scope.optLong("expiresAt")<=System.currentTimeMillis()||scope.getInt("remainingOperations")<1)throw new SecurityException("原隔离草稿授权已到期/撤销/耗尽，不自动补权");return scope;
    }
    private void consumeTeamProjectFileGrant(JSONObject task,JSONObject scope)throws Exception {if(scope==null)return;scope.put("remainingOperations",scope.getInt("remainingOperations")-1);JSONObject team=teamWriteTask(task.getJSONObject("teamAssignment").getString("teamRunId"));currentTeamWorkItem(team,task.getJSONObject("teamAssignment")).put("draftScope",scope);write(team);}
    private JSONObject teamProjectChecks(JSONObject task,String digest)throws Exception {
        JSONObject context=teamProjectAssociation(task,task.getString("runId"),task.getInt("coordinatorEpoch")),selected=context.getJSONObject("selection");JSONArray templates=selected.getJSONArray("templates"),proof=new JSONArray();for(int t=0;t<templates.length();t++){JSONObject template=templates.getJSONObject(t);if(!template.getBoolean("requiredForFreeze"))continue;JSONObject found=null;
            try(Cursor rows=db.rawQuery("SELECT operation_id,run_id,arguments,state,receipt FROM operations WHERE task_id=? AND tool='project_execute' ORDER BY created_at",new String[]{task.getString("id")})){while(rows.moveToNext()){if(!rows.getString(3).equals("receipt")||rows.isNull(4))continue;JSONObject args=projectStoredArguments(task.getString("id"),rows.getString(1),new JSONObject(rows.getString(2))),plan=args.getJSONObject("plan");if(!digest.equals(plan.getString("sourceDigest"))||!template.getString("id").equals(dev.pocketengine.features.teams.TeamProjectWorkspaces.templateId(selected,plan)))continue;JSONObject envelope=new JSONObject(rows.getString(4)),backend=envelope.optJSONObject("executionReceipt");if(backend==null||!envelope.optString("status").equals("completed")||!Boolean.TRUE.equals(envelope.opt("accepted"))||!backend.optBoolean("receiptReceived")||!backend.optString("status").equals("completed")||backend.optInt("exitCode",1)!=0||!backend.optBoolean("resourcesReleased")||!backend.optBoolean("processExited")||!backend.optBoolean("processGroupAbsent")||backend.optBoolean("timedOut")||backend.optBoolean("outputLimitExceeded")||backend.optBoolean("cancelRequested")||!backend.optString("sourceCheckStatus").equals("pre_exec_manifest_verified")||!backend.optString("provider").equals("managed_process")||!backend.optString("kind").equals("command"))continue;
                JSONObject route=envelope.getJSONObject("projectExecution"),step=dev.pocketengine.features.local.project.ProjectBuildPlan.step(plan,args.getString("stepId"));if(!task.getString("id").equals(route.optString("taskId"))||!rows.getString(0).equals(route.optString("operationId"))||!context.getString("checkoutId").equals(route.optString("checkoutId"))||!selected.getString("selectionHash").equals(route.optString("teamProjectSelectionHash")))continue;if(step.getJSONObject("payload").optString("terminalMode").equals("pty")&&(!backend.optBoolean("processSessionAbsent")||!dev.pocketengine.features.local.runtime.NativeProcessSupervisor.PTY_EXIT_RESULT_CONTRACT.equals(backend.optString("terminalNativeContract"))))continue;found=new JSONObject().put("templateId",template.getString("id")).put("operationId",rows.getString(0)).put("runId",rows.getString(1)).put("sourceDigest",digest).put("planDigest",plan.getString("planDigest")).put("exitCode",0).put("resourcesReleased",true).put("receiptHash",capabilityFingerprint(backend));}}
            if(found==null)throw new TeamProjectDeliveryIncomplete("缺少当前完整源码版本的真实成功/已释放检查："+template.getString("id"));proof.put(found);
        }if(proof.length()==0)throw new SecurityException("原项目没有同版必要检查");return new JSONObject().put("sourceDigest",digest).put("checks",proof).put("allRequiredChecksPassed",true).put("businessVerified",false);
    }
    public synchronized JSONObject teamProjectFreezeEvidence(JSONObject binding,String digest,RunControl original)throws Exception {checkTeamAssignment(binding,"team_freeze_outputs",original);return teamProjectChecks(teamAssignmentIdentity(binding),digest);}
    public synchronized void prepareTeamProjectFreeze(JSONObject binding,String operation,dev.pocketengine.features.teams.TeamProjectWorkspaces.FreezeCapture capture,RunControl original)throws Exception {
        if(capture==null||!capture.belongsTo(this,binding))throw new SecurityException("不是原native项目回读");checkTeamAssignment(binding,"team_freeze_outputs",original);JSONObject task=teamAssignmentIdentity(binding),value=capture.view();teamProjectAssociation(task,binding.getString("runId"),binding.getInt("coordinatorEpoch"));if(!canonical(value.getJSONObject("checks")).equals(canonical(teamProjectChecks(task,value.getString("sourceDigest")))))throw new SecurityException("原同版检查已变化");try(Cursor row=db.rawQuery("SELECT state,tool FROM operations WHERE task_id=? AND run_id=? AND operation_id=?",new String[]{task.getString("id"),binding.getString("runId"),operation})){if(!row.moveToFirst()||!row.getString(0).equals("prepared")||!row.getString(1).equals("team_freeze_outputs"))throw new SecurityException("冻结回读不是原prepared动作");}JSONObject candidates=task.optJSONObject("teamProjectFreezeCandidates");if(candidates==null)candidates=new JSONObject();if(!candidates.has(operation)&&candidates.length()>=16)throw new IllegalStateException("原项目冻结预检记录已满");candidates.put(operation,new JSONObject().put("runId",binding.getString("runId")).put("captureHash",capabilityFingerprint(value)).put("facts",value));task.put("teamProjectFreezeCandidates",candidates);write(task);
    }
    public synchronized void recordTeamProjectFrozen(JSONObject binding,String operation,dev.pocketengine.features.teams.TeamProjectWorkspaces.FreezeCapture capture,JSONArray artifacts,RunControl original)throws Exception {
        if(capture==null||!capture.belongsTo(this,binding))throw new SecurityException("不是原native冻结来源");checkTeamAssignment(binding,"team_freeze_outputs",original);JSONObject task=teamAssignmentIdentity(binding),candidate=task.getJSONObject("teamProjectFreezeCandidates").getJSONObject(operation),value=capture.view();if(!binding.getString("runId").equals(candidate.getString("runId"))||!capabilityFingerprint(value).equals(candidate.getString("captureHash")))throw new SecurityException("冻结前置实际版本已变化");for(int i=0;i<artifacts.length();i++)if(!canonical(capture.evidence()).equals(canonical(artifacts.getJSONObject(i).getJSONObject("projectEvidence"))))throw new SecurityException("冻结成果未覆盖同版项目/检查证据");JSONObject frozen=task.optJSONObject("teamProjectFrozen");if(frozen==null)frozen=new JSONObject();frozen.put(operation,new JSONObject(value.toString()).put("artifactsHash",capabilityFingerprint(new JSONObject().put("artifacts",artifacts))).put("runId",binding.getString("runId")));task.put("teamProjectFrozen",frozen);write(task);
    }
    public synchronized JSONObject teamProjectIntegrationSelection(JSONObject binding,RunControl original)throws Exception {checkTeamAssignment(binding,"team_read_integration",original);JSONObject task=teamAssignmentIdentity(binding);if(!task.getJSONObject("origin").optString("kind").equals("team_integration"))throw new SecurityException("不是原唯一整合者");return teamProjectSelection(task);}
    public synchronized JSONObject teamProjectIntegrationSource(JSONObject binding,RunControl original)throws Exception {
        JSONObject selection=teamProjectIntegrationSelection(binding,original);if(selection==null)throw new SecurityException("原主人未批准完整项目范围");JSONObject team=teamWriteTask(binding.getString("teamRunId")),integration=teamIntegrationContext(binding.getString("teamRunId"),original);if(!binding.getString("taskId").equals(integration.getString("taskId"))||!binding.getString("runId").equals(integration.getJSONObject("binding").getString("runId"))||!binding.getString("reviewId").equals(integration.getString("reviewId"))||!binding.getString("planHash").equals(integration.getString("planHash")))throw new SecurityException("原组合来源不是此唯一整合的精确planHash");
        JSONArray authors=teamAssignmentsForReview(binding.getString("teamRunId"),original);for(int i=0;i<authors.length();i++){JSONObject author=authors.getJSONObject(i);if(!author.optString("status").equals("completed")||hasUnresolved(author.getString("assignmentId")))throw new SecurityException("原作者仍有未决写入，不能组合完整成果");}
        return new JSONObject().put("selection",selection).put("scopeHash",capabilityFingerprint(team.getJSONObject("teamWriteScope"))).put("integration",integration).put("appliedReceiptsHash",capabilityFingerprint(new JSONObject().put("receipts",integration.getJSONArray("receipts")))).put("executionAuthorized",false);
    }
    public synchronized JSONObject claimTeamProjectCombination(JSONObject binding,String operation,dev.pocketengine.features.teams.TeamProjectWorkspaces.IntegratedSource source,RunControl original)throws Exception {
        if(source==null||!source.belongsTo(this,binding))throw new SecurityException("没有原native完整组合来源");db.beginTransaction();try{checkTeamAssignment(binding,"team_prepare_integrated_project",original);JSONObject current=teamProjectIntegrationSource(binding,original),captured=source.view();for(String key:new String[]{"selection","scopeHash","integration","appliedReceiptsHash"})if(!canonical(current.get(key)).equals(canonical(captured.get(key))))throw new SecurityException("完整组合来源已改变");JSONObject task=teamAssignmentIdentity(binding);if(task.has("teamProject"))throw new SecurityException("原固定组合副本已经存在，只核对，不重建");
            try(Cursor row=db.rawQuery("SELECT state,tool,arguments FROM operations WHERE task_id=? AND run_id=? AND operation_id=?",new String[]{task.getString("id"),binding.getString("runId"),operation})){if(!row.moveToFirst()||!row.getString(0).equals("prepared")||!row.getString(1).equals("team_prepare_integrated_project")||new JSONObject(row.getString(2)).length()!=0)throw new SecurityException("组合没有原唯一prepared空参数操作");}
            JSONArray operations=get(task.getString("id"),false).getJSONArray("operations");for(int i=0;i<operations.length();i++)if(!operation.equals(operations.getJSONObject(i).getString("operationId"))&&operations.getJSONObject(i).optString("state").matches("prepared|unknown|project_running|project_stopping"))throw new SecurityException("原整合还有未决操作，不另建副本");
            JSONObject owner=teamBudgetOwner(teamWriteTask(binding.getString("teamRunId"))),pool=teamBudget(owner),row=dev.pocketengine.features.teams.TeamParentBudget.reserve(pool,teamBudgetBinding(binding),"integration","operation:"+operation,0,1);spendTeamParentToolGrant(owner,pool);row.getJSONArray("operationIds").put(operation);row.put("state","unknown");owner.put("teamReservationPool",pool);write(owner);
            task=required(task.getString("id"));task.put("teamProjectIntegratedSource",captured.put("creationOperationId",operation));write(task);ContentValues update=new ContentValues();update.put("state","unknown");update.put("receipt",new JSONObject().put("status","unknown").put("accepted",JSONObject.NULL).put("code","team_project_combination_pending").toString());if(db.update("operations",update,"task_id=? AND run_id=? AND operation_id=? AND state='prepared'",new String[]{task.getString("id"),binding.getString("runId"),operation})!=1)throw new SecurityException("组合prepared已被消费");db.setTransactionSuccessful();return new JSONObject().put("dispatch",true);
        }finally{db.endTransaction();}
    }
    public static final class TeamProjectDeliveryIncomplete extends IllegalStateException {public TeamProjectDeliveryIncomplete(String reason){super(reason);}}
    /** Original Runner terminal readback only. It intentionally never calls checkCapabilityCurrent. */
    public synchronized JSONObject teamProjectCompletionContext(JSONObject binding,RunControl original)throws Exception {
        checkTeamAssignmentSettlement(binding,original);JSONObject task=teamAssignmentIdentity(binding);if(!task.getJSONObject("origin").optString("kind").equals("team_integration")||!task.optString("status").equals("needs_confirmation"))throw new SecurityException("完整项目完成只能在原Runner工具已关闭的确认终态只读结算");JSONObject selected=teamProjectSelection(task);if(selected==null)return null;if(task.optJSONObject("teamProject")==null)return new JSONObject().put("ready",false).put("executionAuthorized",false);return teamProjectAssociation(task,binding.getString("runId"),binding.getInt("coordinatorEpoch")).put("ready",true);
    }
    public synchronized JSONObject teamProjectCompletionEvidence(JSONObject binding,String digest,RunControl original)throws Exception {
        JSONObject context=teamProjectCompletionContext(binding,original);if(context==null||!context.optBoolean("ready"))throw new TeamProjectDeliveryIncomplete("fixed_result_not_prepared");JSONObject task=teamAssignmentIdentity(binding);if(hasUnresolved(task.getString("id")))throw new TeamProjectDeliveryIncomplete("original_effect_unresolved");if(!context.getJSONObject("fixedResult").getString("expectedSourceDigest").equals(digest))throw new SecurityException("不是原固定组合完整源版本");JSONObject checks=teamProjectChecks(task,digest),evidence=null;
        try(Cursor rows=db.rawQuery("SELECT operation_id,run_id,arguments,state,receipt FROM operations WHERE task_id=? AND tool='project_execute' AND state='receipt' ORDER BY created_at",new String[]{task.getString("id")})){while(rows.moveToNext()){if(rows.isNull(4))continue;JSONObject args=projectStoredArguments(task.getString("id"),rows.getString(1),new JSONObject(rows.getString(2))),plan=args.getJSONObject("plan");if(!plan.optString("intent").equals("freeze_package")||!digest.equals(plan.getString("sourceDigest")))continue;JSONObject receipt=new JSONObject(rows.getString(4)),route=receipt.optJSONObject("projectExecution");if(route==null||!context.getJSONObject("selection").getString("selectionHash").equals(route.optString("teamProjectSelectionHash")))continue;JSONObject row=new JSONObject().put("operationId",rows.getString(0)).put("runId",rows.getString(1)).put("tool","project_execute").put("state",rows.getString(3)).put("arguments",args).put("receipt",receipt);evidence=dev.pocketengine.features.local.project.ProjectPackageDelivery.evidence(plan,row);}}
        if(evidence==null)throw new TeamProjectDeliveryIncomplete("same_source_original_package_missing");return new JSONObject().put("context",context).put("checks",checks).put("packageEvidence",evidence).put("executionAuthorized",false);
    }
    public synchronized void recordTeamProjectDelivery(JSONObject binding,dev.pocketengine.features.teams.TeamProjectWorkspaces.Delivery delivery,RunControl original)throws Exception {
        if(delivery==null||!delivery.complete()||!delivery.belongsTo(this,binding))throw new SecurityException("没有原native完整交付事实");db.beginTransaction();try{JSONObject value=delivery.view(),context=teamProjectCompletionContext(binding,original),evidence=teamProjectCompletionEvidence(binding,value.getString("sourceDigest"),original);if(context==null||!context.optBoolean("ready")||!binding.getString("reviewId").equals(value.getString("reviewId"))||!binding.getString("planHash").equals(value.getString("planHash"))||!canonical(evidence.getJSONObject("checks")).equals(canonical(value.getJSONObject("checks")))||value.optBoolean("currentMainProjectVerified")||value.optBoolean("parentActiveCheckoutOverwritten"))throw new SecurityException("完整交付不能替换原审查/同版检查或宣称覆盖父活跃项目");JSONObject task=teamAssignmentIdentity(binding);task.put("teamProjectDelivery",value);write(task);db.setTransactionSuccessful();}finally{db.endTransaction();}
    }
    /** Owner-only immutable result selection. It grants no model/command or old execution authority. */
    public static final class TeamProjectPackageSelection {
        private final TaskStore issuer;private final String teamRun,value;
        private TeamProjectPackageSelection(TaskStore issuer,String teamRun,JSONObject value){this.issuer=issuer;this.teamRun=teamRun;this.value=value.toString();}
        public JSONObject view()throws Exception{return new JSONObject(value);}
        public void checkCurrent()throws Exception {if(!canonical(new JSONObject(value)).equals(canonical(issuer.teamProjectPackageFactsForOwner(teamRun))))throw new SecurityException("原团队固定成果记录已变化，请重新选择");}
    }
    public synchronized TeamProjectPackageSelection teamProjectPackageForOwner(String teamRun)throws Exception{return new TeamProjectPackageSelection(this,teamRun,teamProjectPackageFactsForOwner(teamRun));}
    private synchronized JSONObject teamProjectPackageFactsForOwner(String teamRun)throws Exception {
        JSONObject team=teamWriteTask(teamRun);requirePrivateOwner(team);String id=UUID.nameUUIDFromBytes(("team-integration:"+teamRun).getBytes(StandardCharsets.UTF_8)).toString();JSONObject task=required(id);requirePrivateOwner(task);JSONObject origin=task.getJSONObject("origin"),identity=task.getJSONObject("teamAssignment"),delivery=task.getJSONObject("teamProjectDelivery"),reference=delivery.getJSONObject("packageArtifact");if(!origin.optString("kind").equals("team_integration")||!teamRun.equals(identity.getString("teamRunId"))||!delivery.optBoolean("complete")||!delivery.getString("reviewId").equals(identity.getString("reviewId"))||!delivery.getString("planHash").equals(identity.getString("planHash")))throw new SecurityException("原团队没有此准确完成交付");
        String operation=reference.getString("operationId");try(Cursor row=db.rawQuery("SELECT run_id,arguments,state,receipt FROM operations WHERE task_id=? AND operation_id=? AND tool='project_execute'",new String[]{id,operation})){
            if(!row.moveToFirst()||!row.getString(2).equals("receipt")||row.isNull(3))throw new SecurityException("原固定包保存回执不可用");JSONObject args=projectStoredArguments(id,row.getString(0),new JSONObject(row.getString(1))),op=new JSONObject().put("operationId",operation).put("runId",row.getString(0)).put("tool","project_execute").put("state","receipt").put("arguments",args).put("receipt",new JSONObject(row.getString(3))),evidence=dev.pocketengine.features.local.project.ProjectPackageDelivery.evidence(args.getJSONObject("plan"),op),artifact=evidence.getJSONObject("packageArtifact");
            for(String key:new String[]{"id","sha256","manifestIdentity","sourceDigest","checkoutId","projectId"})if(!canonical(reference.get(key)).equals(canonical(artifact.get(key))))throw new SecurityException("原交付引用不能替换固定成果身份");if(!id.equals(evidence.getString("taskId"))||!delivery.getString("sourceDigest").equals(evidence.getString("sourceDigest")))throw new SecurityException("原交付与实际完整组合版本不同");return new JSONObject().put("teamRunId",teamRun).put("delivery",new JSONObject(delivery.toString())).put("evidence",evidence).put("executionAuthorized",false);
        }
    }
    private void spendTeamParentToolGrant(JSONObject owner,JSONObject pool)throws Exception {
        if("teams".equals(owner.optString("kind"))&&owner.getString("id").equals(pool.getString("parentTaskId"))&&owner.optString("parentTaskId").isEmpty())return;
        debitExecutionScope(owner,pool.getString("parentRunId"),false);
        JSONObject permission=grant(pool.getString("parentRunId"));if(permission==null||permission.optLong("expiresAt")<=System.currentTimeMillis()||permission.optInt("remainingOperations")<=0)throw new SecurityException("原父实际操作授权不足或到期");permission.put("remainingOperations",permission.getInt("remainingOperations")-1);ContentValues changed=new ContentValues();changed.put("grant_json",permission.toString());db.update("runs",changed,"run_id=?",new String[]{pool.getString("parentRunId")});
    }
    /** Original operation receipt/unknown is useful after cancellation. This is settlement, not admission. */
    public synchronized void finishTeamNativeRead(JSONObject original,JSONObject receipt,boolean unknown)throws Exception {
        db.beginTransaction();try{JSONObject team=teamWriteTask(original.getString("teamRunId")),owner=teamBudgetOwner(team),pool=teamBudget(owner);JSONObject reserved=null;JSONArray rows=pool.getJSONArray("reservations");for(int i=0;i<rows.length();i++)if(original.getString("reservationId").equals(rows.getJSONObject(i).getString("reservationId")))reserved=rows.getJSONObject(i);
            if(reserved==null||!original.getString("childTaskId").equals(reserved.getString("childTaskId"))||!original.getString("childRunId").equals(reserved.getString("childRunId"))||reserved.getJSONArray("operationIds").length()!=1||!original.getString("operationId").equals(reserved.getJSONArray("operationIds").getString(0)))throw new SecurityException("读取迟账不属于这次原SQL预留");
            if(reserved.optString("state").equals("settled")){db.setTransactionSuccessful();return;}reserved.put("state",unknown?"unknown":"settled");reserved.getJSONArray("actualReceiptRefs").put(original.getString("operationId"));ContentValues update=new ContentValues();update.put("state",unknown?"unknown":"receipt");update.put("receipt",sanitize(receipt).put("nativeTeamRead",true).toString());if(db.update("operations",update,"task_id=? AND run_id=? AND operation_id=?",new String[]{original.getString("childTaskId"),original.getString("childRunId"),original.getString("operationId")})!=1)throw new SecurityException("原读取操作不存在");owner.put("teamReservationPool",pool);write(owner);db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
    /** Native FeatureHost only; model JSON cannot construct a TeamWriteScope. */
    public synchronized void bindTeamWriteScope(String teamRun,dev.pocketengine.features.teams.TeamWriteScope nativeScope)throws Exception {
        db.beginTransaction();try{JSONObject team=teamWriteTask(teamRun),scope=nativeScope.snapshot(),parent=required(scope.getString("parentTaskId"));requirePrivateOwner(parent);
        if(!parent.getString("id").equals(team.optString("parentTaskId"))||!scope.getString("parentRunId").equals(team.optString("parentRunId"))||!scope.getString("parentRunId").equals(parent.optString("runId"))||parent.optBoolean("cancelRequested")||!parent.optString("status").matches("preparing|running|queued"))throw new SecurityException("文件协作没有原父任务的当前授权");
        if(!scope.getString("targetProjectId").equals(parent.getJSONObject("developmentContext").getString("projectId")))throw new SecurityException("文件协作不能选择工具JSON中的其他项目");
        JSONObject parentGrant=grant(scope.getString("parentRunId"));if(parentGrant==null||parentGrant.optLong("expiresAt")<=System.currentTimeMillis()||!parentGrant.optString("principalId").equals("owner")||!parentGrant.optString("spaceId").equals("private"))throw new SecurityException("原父任务授权无效，未创建团队预算");
        JSONObject pool=parent.optJSONObject("teamReservationPool");if(pool==null){pool=dev.pocketengine.features.teams.TeamParentBudget.create(parent.getString("id"),scope.getString("parentRunId"),scope.getJSONObject("initialBudget").getInt("callLimit"),Math.min(scope.getJSONObject("initialBudget").getInt("toolCallLimit"),parentGrant.getInt("remainingOperations")),new JSONObject().put("kind","original_team_plan_and_parent_grant").put("initialBudget",scope.getJSONObject("initialBudget")).put("remainingOperationsAtCapture",parentGrant.getInt("remainingOperations")).put("grantExpiresAt",parentGrant.getLong("expiresAt")));parent.put("teamReservationPool",pool);write(parent);}else if(!scope.getString("parentRunId").equals(pool.getString("parentRunId")))throw new SecurityException("原父pool属于旧run，需主人审阅新的父预算；不会补满旧pool");
        JSONObject previous=team.optJSONObject("teamWriteScope");if(previous!=null&&!canonical(previous).equals(canonical(scope)))throw new SecurityException("原团队项目文件范围不可替换");team.put("teamWriteScope",scope);write(team);db.setTransactionSuccessful();}finally{db.endTransaction();}
    }
    /** Original-parent aggregate pool. All callers run inside this TaskStore's SQLite transaction. */
    private JSONObject teamBudgetOwner(JSONObject team)throws Exception {
        JSONObject scope=team.optJSONObject("teamWriteScope");if(scope==null)scope=team.optJSONObject("teamCoordinatorBudget");if(scope==null)throw new SecurityException("原团队没有已捕获父预算来源");JSONObject owner=required(scope.getString("parentTaskId"));requirePrivateOwner(owner);
        return owner;
    }
    private JSONObject teamBudget(JSONObject owner)throws Exception {
        JSONObject pool=owner.optJSONObject("teamReservationPool");if(pool==null)throw new SecurityException("原父任务没有已捕获预算，需主人审阅；不会在Reply时补建额度");
        if(!owner.getString("id").equals(pool.getString("parentTaskId")))throw new SecurityException("父预算归属不符");return pool;
    }
    private JSONObject teamBudgetBinding(JSONObject binding)throws Exception {
        JSONObject task=teamAssignmentIdentity(binding),team=teamWriteTask(binding.getString("teamRunId")),owner=teamBudgetOwner(team);checkTeamIntegrationParents(team);
        if(!task.optString("runId").equals(binding.getString("runId"))||task.optInt("coordinatorEpoch")!=binding.getInt("coordinatorEpoch"))throw new SecurityException("父预留不是原child执行");
        if(!team.getJSONObject("teamWriteScope").getString("parentRunId").equals(owner.optString("runId")))throw new SecurityException("原父预算执行已变化，未新派发");JSONObject parentGrant=grant(owner.getString("runId"));if(parentGrant==null||parentGrant.optLong("expiresAt")<=System.currentTimeMillis())throw new SecurityException("原父授权已到期");JSONObject pool=teamBudget(owner);if(!pool.getString("parentRunId").equals(team.getJSONObject("teamWriteScope").getString("parentRunId")))throw new SecurityException("原父预算来源执行已变化");
        return new JSONObject().put("teamRunId",binding.getString("teamRunId")).put("runGeneration",binding.optInt("runGeneration"))
                .put("childTaskId",task.getString("id")).put("childRunId",binding.getString("runId")).put("childEpoch",binding.getInt("coordinatorEpoch")).put("memberGeneration",binding.optInt("memberGeneration"))
                .put("memberId",binding.getString("memberId")).put("assignmentId",binding.getString("assignmentId")).put("workItemId",binding.optString("workItemId")).put("itemRevision",binding.optInt("itemRevision")).put("ownerGeneration",binding.optInt("ownerGeneration"));
    }
    public synchronized JSONObject teamBudgetSnapshot(String teamRun)throws Exception {return dev.pocketengine.features.teams.TeamParentBudget.snapshot(teamBudget(teamBudgetOwner(teamWriteTask(teamRun))));}
    public synchronized void updateTeamBudgetFromNativeResume(JSONObject nativePlan)throws Exception {
        db.beginTransaction();try{
            JSONObject team=teamWriteTask(nativePlan.getString("id")),scope=team.getJSONObject("teamWriteScope");if(team.optJSONObject("teamWorkPlan")!=null)throw new SecurityException("原工作项预算只能在精确native工作计划审阅更新，普通resume不补额度");if(!canonical(scope).equals(canonical(nativePlan.getJSONObject("productionScope"))))throw new SecurityException("预算变更不是原文件计划");
            JSONObject owner=teamBudgetOwner(team),pool=teamBudget(owner),actual=dev.pocketengine.features.teams.TeamParentBudget.snapshot(pool),parentGrant=grant(pool.getString("parentRunId"));
            if(!pool.getString("parentRunId").equals(owner.optString("runId"))||parentGrant==null||parentGrant.optLong("expiresAt")<=System.currentTimeMillis())throw new SecurityException("原父预算已到期或换run，未刷新");
            int members=nativePlan.getJSONArray("members").length(),models=nativePlan.getInt("callLimit"),tools=nativePlan.getInt("toolCallLimit"),used=actual.getInt("modelRequestsReservedOrUsed"),toolUsed=actual.getInt("toolOperationsReservedOrUsed");
            int maxModels=Math.min(64,Math.max(pool.getInt("modelRequestLimit"),Math.max(used,nativePlan.getInt("callsUsed"))+members*3+1)),maxTools=Math.min(32,Math.max(pool.getInt("toolOperationLimit"),Math.max(toolUsed,nativePlan.getInt("toolCallsUsed"))+members*2));
            if(models<used||models>maxModels||tools<toolUsed||tools>maxTools)throw new SecurityException("原生继续预算不符合原coordinator有界公式");
            pool.put("modelRequestLimit",models).put("toolOperationLimit",Math.min(tools,toolUsed+parentGrant.getInt("remainingOperations")));pool.getJSONObject("budgetSource").put("lastNativeResumeRevision",nativePlan.getLong("revision")).put("lastNativeResumeGeneration",nativePlan.getInt("generation")).put("nativeCallLimit",models).put("nativeToolCallLimit",tools);
            owner.put("teamReservationPool",pool);write(owner);db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
    /** Reserve before the original Runner calls Gateway. No second usage ledger and no token/dollar guess. */
    public synchronized JSONObject reserveTeamInference(JSONObject binding,String phase,String dedupKey)throws Exception {
        db.beginTransaction();try{
            JSONObject bound=teamBudgetBinding(binding),task=required(binding.getString("taskId")),team=teamWriteTask(binding.getString("teamRunId")),owner=teamBudgetOwner(team),pool=teamBudget(owner);if(!pool.getString("parentRunId").equals(team.getJSONObject("teamWriteScope").getString("parentRunId")))throw new SecurityException("原父pool不能跨run复用");
            String expected=task.getJSONObject("origin").getString("kind").equals("team_integration")?"integration":task.getJSONObject("origin").getString("kind").equals("team_review")?"review":"member";
            if(!expected.equals(phase)||!task.optString("status").matches("preparing|running")||task.optBoolean("cancelRequested"))throw new SecurityException("父预留阶段或原执行状态不符");
            JSONObject selected=null;String continued=binding.optString("continuationReservationId");JSONArray rows=pool.getJSONArray("reservations");
            if(!continued.isEmpty())for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);if(continued.equals(row.getString("reservationId"))&&row.getString("state").equals("reserved")&&row.optString("continuationRunId").equals(binding.getString("runId"))){for(String key:new String[]{"childRunId","childEpoch"})row.put(key,bound.get(key));row.put("productionDedupKey",dedupKey);row.remove("continuationRunId");selected=row;}}
            if(selected==null)selected=dev.pocketengine.features.teams.TeamParentBudget.reserve(pool,bound,phase,dedupKey,1,0);
            owner.put("teamReservationPool",pool);write(owner);db.setTransactionSuccessful();return new JSONObject(selected.toString());
        }finally{db.endTransaction();}
    }
    /** Atomic exact-question wake claim and first-call reservation. Duplicate delivery never grants another wake. */
    public synchronized JSONObject claimTeamAnswerContinuation(JSONObject input)throws Exception {
        db.beginTransaction();try{
            String teamRun=input.getString("runId"),assignment=input.getString("assignmentId");JSONObject team=teamWriteTask(teamRun),task=required(assignment);requirePrivateOwner(task);checkTeamIntegrationParents(team);
            JSONObject identity=task.getJSONObject("teamAssignment");if(!teamRun.equals(identity.getString("teamRunId"))||!input.getString("memberId").equals(identity.getString("memberId"))||task.optBoolean("cancelRequested")||!task.optString("status").matches("awaiting_user|needs_confirmation|paused")||hasUnresolved(assignment)||teamHasPendingQuestion(task))throw new SecurityException("原assignment仍在运行、已停止、待审批/未知或还有未回答问题");
            JSONArray approvals=task.optJSONArray("approvals");for(int i=0;approvals!=null&&i<approvals.length();i++)if(approvals.getJSONObject(i).optString("state").equals("pending")&&approvals.getJSONObject(i).optLong("expiresAt")>System.currentTimeMillis())throw new SecurityException("原assignment还有待主人批准的操作");
            JSONObject question=null;JSONArray questions=task.getJSONArray("teamQuestions");for(int i=0;i<questions.length();i++)if(input.getString("questionId").equals(questions.getJSONObject(i).getString("questionId")))question=questions.getJSONObject(i);
            if(question==null||question.optInt("assignmentRevision",1)!=identity.optInt("assignmentRevision",1)||!question.optString("state").equals("answered")||!input.getString("answerId").equals(question.optString("answerId"))||!input.getString("answer").equals(question.optString("answer"))||!input.optString("quoteMessageId").equals("question:"+question.getString("questionId")))throw new SecurityException("继续必须绑定原问题和原答案");
            JSONObject prior=question.optJSONObject("continuationClaim");if(prior!=null){db.setTransactionSuccessful();return new JSONObject(prior.toString()).put("claimed",false);}
            JSONObject owner=teamBudgetOwner(team),pool=teamBudget(owner);if(owner.optString("status").matches("paused|pausing|interrupted")||owner.optBoolean("cancelRequested"))throw new SecurityException("原父任务已暂停或停止，回答保留但未继续");if(!team.getJSONObject("teamWriteScope").getString("parentRunId").equals(owner.optString("runId")))throw new SecurityException("原父预算执行已变化，未继续");JSONObject bound=new JSONObject().put("teamRunId",teamRun).put("runGeneration",input.getInt("expectedGeneration")).put("childTaskId",assignment).put("childRunId",task.getString("runId")).put("childEpoch",task.getInt("coordinatorEpoch")).put("memberId",input.getString("memberId")).put("assignmentId",assignment);
            JSONObject parentGrant=grant(pool.getString("parentRunId"));if(parentGrant==null||parentGrant.optLong("expiresAt")<=System.currentTimeMillis())throw new SecurityException("原父授权已到期，回答保留");
            String key="reply:"+question.getString("questionId")+":"+question.getString("answerId")+":"+input.getInt("expectedMemberGeneration");
            JSONObject reservation=dev.pocketengine.features.teams.TeamParentBudget.reserve(pool,bound,"member",key,1,0),claim=new JSONObject().put("reservationId",reservation.getString("reservationId")).put("dedupKey",key).put("memberGeneration",input.getInt("expectedMemberGeneration")).put("claimedAt",System.currentTimeMillis());
            question.put("continuationClaim",claim);task.put("teamQuestions",questions).put("teamAnswerContinuation",claim);if(!identity.optString("workItemId").isEmpty()){currentTeamWorkItem(team,identity).put("status","queued");write(team);}owner.put("teamReservationPool",pool);write(owner);write(task);db.setTransactionSuccessful();return new JSONObject(claim.toString()).put("claimed",true);
        }finally{db.endTransaction();}
    }
    private JSONObject teamPoolOwnerForChild(JSONObject task)throws Exception {
        if(task.optJSONObject("teamReservationPool")!=null&&task.getJSONObject("teamReservationPool").getJSONObject("budgetSource").optBoolean("includeParentInference"))return task;
        JSONObject origin=task.optJSONObject("origin");return origin!=null&&origin.optString("kind").matches("team_assignment|team_integration|team_review")?teamBudgetOwner(teamWriteTask(origin.getString("teamRunId"))):"teams".equals(task.optString("kind"))&&task.optJSONObject("teamCoordinatorBudget")!=null?teamBudgetOwner(task):null;
    }
    private JSONObject teamWriteTask(String teamRun)throws Exception {
        String id=operationTask("teams",teamRun);if(id==null)id=externalTask("teams",teamRun);if(id==null)throw new SecurityException("原团队TaskStore任务不存在");JSONObject task=required(id);requirePrivateOwner(task);
        if(!"teams".equals(task.optString("feature"))||!teamRun.equals(task.optString("runId")))throw new SecurityException("不是原团队执行身份");return task;
    }
    private JSONObject teamAssignmentIdentity(JSONObject binding)throws Exception {
        JSONObject task=required(binding.getString("taskId"));requirePrivateOwner(task);JSONObject identity=task.getJSONObject("teamAssignment"),origin=task.getJSONObject("origin");
        if(!origin.optString("kind").matches("team_assignment|team_integration|team_review"))throw new SecurityException("不是原团队分工任务");
        if(!origin.getString("teamRunId").equals(identity.getString("teamRunId"))||!origin.getString("assignmentId").equals(identity.getString("assignmentId"))||origin.optString("kind").equals("team_assignment")&&!origin.getString("memberId").equals(identity.getString("memberId")))throw new SecurityException("原assignment来源与实际任务冲突");
        for(String key:new String[]{"teamRunId","assignmentId","memberId","targetProjectId"})if(!identity.getString(key).equals(binding.getString(key)))throw new SecurityException("原assignment身份不符");
        if(!task.getString("id").equals(identity.getString("assignmentId")))throw new SecurityException("原assignment没有唯一child任务");
        if(!identity.optString("workItemId").isEmpty())for(String key:new String[]{"workItemId","itemRevision","ownerGeneration"})if(!canonical(identity.get(key)).equals(canonical(binding.get(key))))throw new SecurityException("工作项native tuple不符");
        if(!canonical(identity.getJSONArray("files")).equals(canonical(binding.getJSONArray("files")))||identity.optInt("assignmentRevision",1)!=binding.optInt("assignmentRevision",1))throw new SecurityException("原assignment文件范围或修订已改变");for(String key:new String[]{"dependencyArtifacts","seedArtifacts"})if(!canonical(identity.optJSONArray(key)==null?new JSONArray():identity.getJSONArray(key)).equals(canonical(binding.optJSONArray(key)==null?new JSONArray():binding.getJSONArray(key))))throw new SecurityException("原assignment前置成果或修订来源已改变");return task;
    }
    /** Dedicated immutable team proposal records in the ORIGINAL database. C2/root own the one v4 migration. */
    public static void createTeamParentSchema(SQLiteDatabase d){
        d.execSQL("CREATE TABLE IF NOT EXISTS team_parent_plans(plan_ref TEXT PRIMARY KEY,task_id TEXT NOT NULL,source_run_id TEXT NOT NULL,source_epoch INTEGER NOT NULL,source_operation_id TEXT NOT NULL UNIQUE,plan_hash TEXT NOT NULL,plan_json TEXT NOT NULL,expires_at INTEGER NOT NULL,state TEXT NOT NULL,start_operation_id TEXT,start_run_id TEXT,start_epoch INTEGER,child_task_id TEXT,created_at INTEGER NOT NULL)");
        d.execSQL("CREATE INDEX IF NOT EXISTS team_parent_plans_owner ON team_parent_plans(task_id,created_at)");
    }
    /** Native original parent invocation, never reconstructed from model JSON or a saved run/control. */
    public static final class TeamParentOrigin {
        private final TaskStore issuer;private final RunControl original;private final String task,run,tool,operation,argsHash,projectHash,view;private final int epoch;private final long pause;
        private TeamParentOrigin(TaskStore issuer,String task,String run,int epoch,String tool,String operation,JSONObject args,RunControl original,JSONObject view)throws Exception{this.issuer=issuer;this.task=task;this.run=run;this.epoch=epoch;this.tool=tool;this.operation=operation;this.original=original;argsHash=capabilityFingerprint(args);this.view=view.toString();projectHash=view.getString("projectContextHash");pause=original.pauseRevision();}
        public JSONObject view()throws Exception{return new JSONObject(view);}
        public void checkCurrent()throws Exception{original.check();synchronized(issuer){issuer.checkTeamParentOriginSql(this);}}
    }
    public synchronized boolean teamParentEligible(String id,String run,int epoch)throws Exception{JSONObject task=read(id);if(task==null)return false;JSONObject origin=task.optJSONObject("origin");return "owner".equals(task.optString("actorId"))&&"private".equals(task.optString("spaceId"))&&run.equals(task.optString("runId"))&&epoch==task.optInt("coordinatorEpoch")&&task.optString("kind").matches("assistant|phone")&&!task.has("teamAssignment")&&(origin==null||!origin.optString("kind").matches("channel|group|team_assignment|team_review|team_integration"));}
    public TeamParentOrigin teamParentOrigin(String id,String run,int epoch,String tool,String operation,JSONObject args,RunControl original)throws Exception{
        if(original==null||!original.hasHostRun()||!original.matchesHostRun(id,run,epoch))throw new SecurityException("团队门面需要原父Runner的真实执行控制");original.check();
        synchronized(this){JSONObject task=required(id);requirePrivateOwner(task);JSONObject origin=task.optJSONObject("origin");if(!task.optString("kind").matches("assistant|phone")||task.has("teamAssignment")||origin!=null&&origin.optString("kind").matches("channel|group|team_assignment|team_review|team_integration"))throw new SecurityException("此门面只属于原私人父任务，不给群/成员第二层派发权");
            JSONObject permission=grant(run),development=task.optJSONObject("developmentContext"),facts=new JSONObject().put("parentTaskId",id).put("parentRunId",run).put("parentEpoch",epoch).put("preparedOperationId",operation==null?"":operation).put("tool",tool).put("targetProjectId",development==null?"":development.optString("projectId")).put("projectContextHash",capabilityFingerprint(development==null?new JSONObject():development)).put("remainingOperations",permission==null?0:permission.optInt("remainingOperations")).put("expiresAt",permission==null?0:permission.optLong("expiresAt"));
            if(task.optJSONObject("teamReservationPool")!=null)facts.put("parentBudget",dev.pocketengine.features.teams.TeamParentBudget.snapshot(task.getJSONObject("teamReservationPool")));
            try(Cursor calls=db.rawQuery("SELECT COUNT(*) FROM task_events WHERE task_id=? AND event_type='inference_receipt'",new String[]{id})){calls.moveToFirst();facts.put("priorParentModelRequests",calls.getInt(0));}facts.put("priorParentModelRequestsInPool",task.optJSONObject("teamReservationPool")!=null&&task.getJSONObject("teamReservationPool").getJSONObject("budgetSource").optBoolean("includeParentInference"));facts.put("priorParentTeamToolsNotInPool",unpooledTeamParentTools(id,task.optJSONObject("teamReservationPool")));
            TeamParentOrigin source=new TeamParentOrigin(this,id,run,epoch,tool,operation==null?"":operation,args,original,facts);checkTeamParentOriginSql(source);return source;}
    }
    /** One SQL predicate for live-service cooperation. This never dispatches, grants, consumes or resumes.
     * Empty operation is a port/journal preflight only. The service-retention path requires its
     * already registered exact native row and parent-pool reservation. Unknown control is NOT safe. */
    private boolean teamParentServiceSql(String id,String run,int epoch,String tool,JSONObject args,String operation)throws Exception {
        boolean list="teams_list".equals(tool),read="teams_get".equals(tool)||"teams_wait".equals(tool),stop="teams_control".equals(tool)&&args.optString("action").matches("pause|cancel");
        if(!list&&!read&&!stop||!teamParentEligible(id,run,epoch))return false;checkCapabilityCurrent(id,run,epoch,tool);
        if(!onlyOwnedProjectPending(id,run,epoch,"",operation))return false;
        if(list)return operation.isEmpty()&&args.length()==0;
        dev.pocketengine.features.teams.TeamParentTools.validate(tool,args);
        String teamRun=args.optString("teamRunId");if(teamRun.isEmpty())return false;JSONObject team;try{team=teamWriteTask(teamRun);}catch(SecurityException absent){return false;}
        if(!id.equals(team.optString("parentTaskId")))return false;boolean related=false;JSONArray links=required(id).optJSONArray("relatedTasks");for(int i=0;links!=null&&i<links.length();i++)if(teamRun.equals(links.getJSONObject(i).optString("taskId")))related=true;if(!related)return false;
        if(stop){
            if(!run.equals(team.optString("parentRunId"))||!team.has("teamParentPlanRef"))return false;
            // A preflight cannot start a new action on a stopped team. The original already
            // prepared stop remains harmless to an unrelated owned service while its receipt
            // is being committed, including after that exact stop set cancelRequested.
            if(operation.isEmpty()&&(team.optBoolean("cancelRequested")||team.optString("status").matches("cancelled|stopping|interrupted")))return false;
            JSONObject plan=teamParentPlanRow(id,team.getString("teamParentPlanRef"),team.getString("teamParentPlanHash"));if(!plan.getString("state").matches("starting|started")||plan.getJSONObject("plan").getLong("expiresAt")<=System.currentTimeMillis())return false;
        }
        if(operation.isEmpty())return true;
        String state;try(Cursor row=db.rawQuery("SELECT tool,arguments,state FROM operations WHERE task_id=? AND run_id=? AND operation_id=?",new String[]{id,run,operation})){
            if(!row.moveToFirst()||!tool.equals(row.getString(0))||!canonical(args).equals(canonical(new JSONObject(row.getString(1)))))return false;state=row.getString(2);
        }
        if(stop&&!"prepared".equals(state)||read&&!"unknown".equals(state))return false;
        try(Cursor row=db.rawQuery("SELECT epoch FROM runs WHERE task_id=? AND run_id=?",new String[]{id,run})){if(!row.moveToFirst()||row.getInt(0)!=epoch)return false;}
        JSONObject pool=required(id).optJSONObject("teamReservationPool");if(pool==null||!pool.getJSONObject("budgetSource").optBoolean("includeParentInference"))return false;
        JSONArray reservations=pool.getJSONArray("reservations");for(int i=0;i<reservations.length();i++){
            JSONObject row=reservations.getJSONObject(i);if(!"parent_coordination".equals(row.optString("phase"))||row.optInt("toolOperations")!=1||!id.equals(row.optString("childTaskId"))||!run.equals(row.optString("childRunId"))||epoch!=row.optInt("childEpoch"))continue;
            JSONArray ops=row.getJSONArray("operationIds");for(int o=0;o<ops.length();o++)if(operation.equals(ops.getString(o)))return true;
        }return false;
    }
    public synchronized boolean teamParentServiceAllowed(String id,String run,int epoch,String tool,JSONObject args,RunControl original)throws Exception {
        return original!=null&&!original.isCancelled()&&!original.isPaused()&&original.matchesHostRun(id,run,epoch)&&teamParentServiceSql(id,run,epoch,tool,args,"");
    }
    /** Called only by the original live service host; candidate JSON is re-resolved against SQL. */
    public synchronized boolean projectServiceAllowsTeamObservation(JSONObject service,JSONObject candidate)throws Exception {
        String id=service.getString("taskId"),run=service.getString("runId");int epoch=service.getInt("coordinatorEpoch");
        if(!run.equals(candidate.optString("runId"))||candidate.optString("operationId").isEmpty())return false;
        JSONObject original=ownedServiceRow(id,run,epoch,service.getString("operationId")),plan=dev.pocketengine.features.local.project.ProjectBuildPlan.verified(original.getJSONObject("arguments").getJSONObject("plan"));
        JSONObject exact=dev.pocketengine.features.local.project.ProjectDevelopment.exact(plan,dev.pocketengine.features.local.project.ProjectBuildPlan.step(plan,original.getJSONObject("arguments").getString("stepId")));
        if(!nativeOwnedService(exact,original.getJSONObject("receipt").getJSONObject("executionReceipt")))return false;
        if(teamProjectSelection(required(id))!=null)return teamProjectServiceReadSql(service,candidate);
        return teamParentServiceSql(id,run,epoch,candidate.optString("tool"),candidate.optJSONObject("arguments")==null?new JSONObject():candidate.getJSONObject("arguments"),candidate.optString("operationId"));
    }
    /** An in-flight native readonly metering row is not another writer. No control.check or IO here. */
    private boolean teamProjectServiceReadSql(JSONObject service,JSONObject candidate)throws Exception {
        String id=service.getString("taskId"),run=service.getString("runId"),operation=candidate.getString("operationId"),tool=candidate.optString("tool");int epoch=service.getInt("coordinatorEpoch");if(!dev.pocketengine.features.local.project.ProjectCapabilityPort.isTeamProjectRead(tool)||!run.equals(candidate.optString("runId")))return false;JSONObject task=required(id),context=teamProjectAssociation(task,run,epoch),selected=context.getJSONObject("selection"),nativeBinding=teamProjectNativeBinding(task,run,epoch),exactBinding=teamBudgetBinding(nativeBinding);checkTeamAssignmentExecutionCurrent(nativeBinding);
        JSONObject marker,args;try(Cursor row=db.rawQuery("SELECT tool,arguments,state,receipt FROM operations WHERE task_id=? AND run_id=? AND operation_id=?",new String[]{id,run,operation})){if(!row.moveToFirst()||!tool.equals(row.getString(0))||!row.getString(2).equals("unknown")||row.isNull(3))return false;args=new JSONObject(row.getString(1));marker=new JSONObject(row.getString(3));if(!canonical(args).equals(canonical(candidate.getJSONObject("arguments"))))return false;}
        if(!marker.optString("format").equals("pocketworld.team-project-native-read-pending.v1")||!marker.optBoolean("nativeTeamProjectReadPending")||epoch!=marker.optInt("coordinatorEpoch")||!task.getJSONObject("teamAssignment").getString("teamRunId").equals(marker.optString("teamRunId"))||!selected.getString("selectionHash").equals(marker.optString("selectionHash"))||!capabilityFingerprint(args).equals(marker.optString("argumentsHash"))||args.has("executionId")&&!service.getString("operationId").equals(args.getString("executionId")))return false;
        JSONObject pool=teamBudget(teamPoolOwnerForChild(task));JSONArray reservations=pool.getJSONArray("reservations");boolean exact=false;for(int i=0;i<reservations.length();i++){JSONObject row=reservations.getJSONObject(i);JSONArray ids=row.getJSONArray("operationIds");boolean bindingMatches=true;for(String key:new String[]{"teamRunId","childTaskId","childRunId","childEpoch","memberId","assignmentId","workItemId","itemRevision","ownerGeneration","runGeneration","memberGeneration"})bindingMatches&=canonical(exactBinding.get(key)).equals(canonical(row.opt(key)));if(marker.getString("reservationId").equals(row.getString("reservationId"))&&bindingMatches&&pool.getString("parentTaskId").equals(row.optString("parentTaskId"))&&pool.getString("parentRunId").equals(row.optString("parentRunId"))&&row.optString("dedupKey").equals("operation:"+operation)&&row.optString("phase").equals(task.getJSONObject("origin").optString("kind").equals("team_integration")?"integration":"member")&&row.optInt("modelRequests")==0&&row.optInt("toolOperations")==1&&row.optString("state").equals("unknown")&&ids.length()==1&&operation.equals(ids.getString(0)))exact=true;}return exact&&onlyOwnedProjectPending(id,run,epoch,"",operation);
    }
    private void checkTeamParentOriginSql(TeamParentOrigin source)throws Exception{
        if(source==null||source.issuer!=this||source.original.isCancelled()||source.original.isPaused()||source.original.pauseRevision()!=source.pause||Thread.currentThread().isInterrupted()||!source.original.matchesHostRun(source.task,source.run,source.epoch))throw new SecurityException("原父执行已暂停、停止或变代，不恢复旧控制");
        checkCapabilityCurrent(source.task,source.run,source.epoch,source.tool);JSONObject task=required(source.task),development=task.optJSONObject("developmentContext");if(!source.projectHash.equals(capabilityFingerprint(development==null?new JSONObject():development)))throw new SecurityException("原父项目范围已经变化");
        if(!source.operation.isEmpty())try(Cursor c=db.rawQuery("SELECT tool,arguments,state FROM operations WHERE task_id=? AND run_id=? AND operation_id=?",new String[]{source.task,source.run,source.operation})){if(!c.moveToFirst()||!c.getString(0).equals(source.tool)||!c.getString(2).equals("prepared")||!source.argsHash.equals(capabilityFingerprint(new JSONObject(c.getString(1)))))throw new SecurityException("团队动作不是原Runner精确prepared参数");}
    }
    private boolean parentOperationInPool(JSONObject pool,String op)throws Exception {
        if(pool==null)return false;JSONArray reservations=pool.getJSONArray("reservations");
        for(int i=0;i<reservations.length();i++){JSONArray ids=reservations.getJSONObject(i).getJSONArray("operationIds");for(int j=0;j<ids.length();j++)if(op.equals(ids.getString(j)))return true;}return false;
    }
    private int unpooledTeamParentTools(String task,JSONObject pool)throws Exception {
        int count=0;try(Cursor rows=db.rawQuery("SELECT operation_id FROM operations WHERE task_id=? AND tool IN ('teams_plan','teams_start','teams_message','teams_control','teams_get','teams_wait')",new String[]{task})){while(rows.moveToNext())if(!parentOperationInPool(pool,rows.getString(0)))count++;}return count;
    }
    public synchronized JSONObject recordTeamParentPlan(TeamParentOrigin source,dev.pocketengine.features.teams.TeamParentPlan nativePlan)throws Exception{
        checkTeamParentOriginSql(source);if(nativePlan==null||nativePlan.origin()!=source||!source.tool.equals("teams_plan")||source.operation.isEmpty())throw new SecurityException("没有原父规划动作与私构项目来源");
        JSONObject plan=nativePlan.view();if(!canonical(plan.getJSONObject("sourceParent")).equals(canonical(source.view()))||plan.getLong("expiresAt")<=System.currentTimeMillis()||plan.toString().getBytes(StandardCharsets.UTF_8).length>65536)throw new SecurityException("提案来源、期限或有界大小无效");
        try(Cursor c=db.rawQuery("SELECT plan_ref,plan_hash FROM team_parent_plans WHERE source_operation_id=? AND task_id=?",new String[]{source.operation,source.task})){if(c.moveToFirst())return teamParentPlanView(source,teamParentPlanRow(source.task,c.getString(0),c.getString(1)));}
        try(Cursor c=db.rawQuery("SELECT COUNT(*) FROM team_parent_plans WHERE task_id=?",new String[]{source.task})){if(!c.moveToFirst()||c.getInt(0)>=8)throw new IllegalStateException("原父任务已保留8份提案，请处理原提案；没有删除或替换旧计划");}
        JSONObject parent=required(source.task),existingPool=parent.optJSONObject("teamReservationPool");if(existingPool!=null&&existingPool.getJSONObject("budgetSource").optBoolean("includeParentInference"))claimTeamParentPreparedToolSql(source,existingPool.getJSONObject("budgetSource").getString("parentOrchestrationTeamRun"));
        String ref=UUID.randomUUID().toString(),hash=capabilityFingerprint(plan);ContentValues row=new ContentValues();row.put("plan_ref",ref);row.put("task_id",source.task);row.put("source_run_id",source.run);row.put("source_epoch",source.epoch);row.put("source_operation_id",source.operation);row.put("plan_hash",hash);row.put("plan_json",plan.toString());row.put("expires_at",plan.getLong("expiresAt"));row.put("state","proposed");row.put("created_at",System.currentTimeMillis());db.insertOrThrow("team_parent_plans",null,row);return teamParentPlanView(source,teamParentPlanRow(source.task,ref,hash));
    }
    private JSONObject teamParentPlanRow(String owner,String ref,String expectedHash)throws Exception{
        try(Cursor c=db.rawQuery("SELECT plan_hash,plan_json,state,start_operation_id,start_run_id,start_epoch,child_task_id FROM team_parent_plans WHERE task_id=? AND plan_ref=?",new String[]{owner,ref})){
            if(!c.moveToFirst()||!c.getString(0).equals(expectedHash))throw new SecurityException("没有此原父任务的准确不可变提案");JSONObject row=new JSONObject().put("planRef",ref).put("planHash",c.getString(0)).put("plan",new JSONObject(c.getString(1))).put("state",c.getString(2));for(int i=3;i<7;i++)if(!c.isNull(i))row.put(new String[]{"startOperationId","startRunId","startEpoch","teamRunId"}[i-3],i==5?c.getInt(i):c.getString(i));return row;
        }
    }
    public synchronized JSONObject teamParentPlan(TeamParentOrigin source,String ref,String hash)throws Exception{checkTeamParentOriginSql(source);return new JSONObject(teamParentPlanRow(source.task,ref,hash).toString());}
    private JSONObject teamParentPlanView(TeamParentOrigin source,JSONObject row)throws Exception{
        JSONObject plan=row.getJSONObject("plan");JSONArray items=plan.getJSONArray("items"),index=new JSONArray();for(int i=0;i<items.length();i++){JSONObject item=items.getJSONObject(i);index.put(dev.pocketengine.features.teams.TeamWorkItems.exactIdentity(item).put("title",item.getString("title")).put("dependsOn",item.getJSONArray("dependsOn")).put("files",item.getJSONArray("files")));}
        JSONObject out=new JSONObject().put("planRef",row.getString("planRef")).put("planHash",row.getString("planHash")).put("state",row.getString("state")).put("goal",plan.getString("goal").substring(0,Math.min(160,plan.getString("goal").length()))).put("goalChars",plan.getString("goal").length()).put("goalComplete",plan.getString("goal").length()<=160).put("workItems",index).put("budgetPlan",plan.getJSONObject("budgetPlan")).put("modelRequestLimit",plan.getInt("modelRequestLimit")).put("toolOperationLimit",plan.getInt("toolOperationLimit")).put("expiresAt",plan.getLong("expiresAt")).put("executionAuthorized",false).put("originalProjectWriteAuthorized",false).put("message","这是提案，不是批准；启动须由主人在原任务审阅准确文件/成员/预算，最终仍需独审及原planHash整合批准");if(row.has("teamRunId"))out.put("teamRunId",row.getString("teamRunId"));return out;
    }
    /** Native foreground owner UI reads the immutable original, not a model-supplied review summary. */
    public synchronized JSONObject teamParentPlanForOwner(String taskId,String approvalId)throws Exception {
        JSONObject task=required(taskId);requirePrivateOwner(task);if(task.optBoolean("cancelRequested"))throw new SecurityException("原父任务已停止，不能审阅旧执行授权");
        JSONArray approvals=task.optJSONArray("approvals");for(int i=0;approvals!=null&&i<approvals.length();i++){
            JSONObject approval=approvals.getJSONObject(i),capability=approval.optJSONObject("capability");
            if(!approvalId.equals(approval.optString("id")))continue;
            if(!"teams_start".equals(approval.optString("tool"))||!"pending".equals(approval.optString("state"))||approval.optLong("expiresAt")<=System.currentTimeMillis()||!task.getString("runId").equals(approval.getString("runId"))||capability==null||!"start_parent_plan".equals(capability.optString("command")))throw new SecurityException("不是当前原父团队待主人审批");
            JSONObject row=teamParentPlanRow(taskId,capability.getString("planRef"),capability.getString("planHash")),plan=row.getJSONObject("plan");
            if(!"proposed".equals(row.getString("state"))||plan.getLong("expiresAt")<=System.currentTimeMillis()||task.optJSONObject("developmentContext")==null||!plan.getJSONObject("sourceParent").getString("projectContextHash").equals(capabilityFingerprint(task.getJSONObject("developmentContext"))))throw new SecurityException("原计划或项目上下文已变化，请重新提案而非批准旧范围");
            return new JSONObject().put("approvalId",approvalId).put("planRef",row.getString("planRef")).put("planHash",row.getString("planHash")).put("plan",plan).put("executionAuthorized",false);
        }throw new SecurityException("此原父任务没有该待审批");
    }
    public static final class TeamParentStart {
        private final TaskStore issuer;private final TeamParentOrigin origin;private final String data,ref,hash;private final boolean dispatch;
        private TeamParentStart(TaskStore issuer,TeamParentOrigin origin,String ref,String hash,JSONObject data,boolean dispatch){this.issuer=issuer;this.origin=origin;this.ref=ref;this.hash=hash;this.data=data.toString();this.dispatch=dispatch;}
        public boolean dispatch(){return dispatch;}public JSONObject view()throws Exception{return new JSONObject(data);}
        public void checkCurrent()throws Exception{origin.original.check();synchronized(issuer){issuer.checkTeamParentStartSql(this);}}
    }
    private void checkTeamParentStartSql(TeamParentStart start)throws Exception{
        if(start==null||start.issuer!=this||!start.dispatch)throw new SecurityException("不是原SQL签发的本次真实启动");checkTeamParentOriginSql(start.origin);JSONObject row=teamParentPlanRow(start.origin.task,start.ref,start.hash);if(!row.optString("state").equals("starting")||!row.optString("startOperationId").equals(start.origin.operation)||!row.optString("startRunId").equals(start.origin.run)||row.optInt("startEpoch")!=start.origin.epoch)throw new SecurityException("本提案已经启动、恢复或换代，不重派");
    }
    /** The original prepared op, exact native capability approval, pool and child are claimed together. */
    public synchronized TeamParentStart claimTeamParentStart(TeamParentOrigin source,String ref,String hash)throws Exception{
        checkTeamParentOriginSql(source);if(!source.tool.equals("teams_start")||source.operation.isEmpty())throw new SecurityException("启动需要原Runner prepared动作");db.beginTransaction();try{
            JSONObject row=teamParentPlanRow(source.task,ref,hash),plan=row.getJSONObject("plan");if(row.has("teamRunId")){db.setTransactionSuccessful();return new TeamParentStart(this,source,ref,hash,new JSONObject().put("teamRunId",row.getString("teamRunId")).put("state",row.getString("state")).put("knownPlan",true).put("queryOnly",true),false);}
            if(!row.getString("state").equals("proposed")||plan.getLong("expiresAt")<=System.currentTimeMillis()||!source.view().getString("targetProjectId").equals(plan.getJSONObject("sourceParent").getString("targetProjectId")))throw new SecurityException("原提案已经到期、停止或项目变更");
            JSONObject permission=grant(source.run),owner=required(source.task);int least=plan.getJSONObject("budgetPlan").getInt("selectedFileToolSteps")+plan.getJSONObject("budgetPlan").getInt("parentOrchestrationToolSteps");if(permission.getInt("remainingOperations")<least)throw new IllegalStateException("原父剩余操作不足以完成这条明确文字文件链；没有创建或补满预算");
            JSONObject capability=new JSONObject().put("feature","teams").put("command","start_parent_plan").put("planRef",ref).put("planHash",hash).put("immutableNativePlanRequired",true).put("draftWritesOnly",true).put("finalProjectWriteNeedsIndependentReviewAndExactOwnerApproval",true);
            JSONObject beforePool=owner.optJSONObject("teamReservationPool");if(beforePool!=null&&beforePool.getJSONObject("budgetSource").optBoolean("includeParentInference")){claimTeamParentPreparedToolSql(source,source.operation);owner=required(source.task);}
            JSONObject arguments=new JSONObject().put("planRef",ref).put("planHash",hash),approval=requireCapabilityApproval(source.task,source.run,"teams_start",arguments,capability);
            if(!approval.optBoolean("approved")){db.setTransactionSuccessful();return new TeamParentStart(this,source,ref,hash,new JSONObject().put("ok",false).put("accepted",false).put("code","approval_required").put("approvalId",approval.getString("approvalId")).put("planRef",ref).put("planHash",hash).put("message","请在原父任务审阅准确成员、互斥文件基线、范围、期限和本次模型/工具预算；未启动成员、未写草稿"),false);}
            owner=required(source.task); // requireCapabilityApproval persisted the original consumed decision.
            JSONObject pool=owner.optJSONObject("teamReservationPool");int unpooledModels=0;if(pool==null||!pool.getJSONObject("budgetSource").optBoolean("includeParentInference"))try(Cursor calls=db.rawQuery("SELECT COUNT(*) FROM task_events WHERE task_id=? AND event_type='inference_receipt'",new String[]{source.task})){calls.moveToFirst();unpooledModels=calls.getInt(0);}int unpooledTools=unpooledTeamParentTools(source.task,pool),futureModels=plan.getJSONObject("budgetPlan").getInt("estimatedModelRequests")+plan.getJSONObject("budgetPlan").getInt("estimatedParentModelRequests");
            JSONObject budget=pool==null?new JSONObject().put("modelRequestsReservedOrUsed",0).put("toolOperationsReservedOrUsed",0):dev.pocketengine.features.teams.TeamParentBudget.snapshot(pool);
            if(plan.getInt("modelRequestLimit")<budget.getInt("modelRequestsReservedOrUsed")+unpooledModels+futureModels||plan.getInt("toolOperationLimit")<budget.getInt("toolOperationsReservedOrUsed")+unpooledTools+least)throw new IllegalStateException("原累计pool、规划/审批调用和本次明确流程不在已批额度内；不启动半条必然耗尽的链");
            if(pool!=null){if(!source.run.equals(pool.optString("parentRunId")))throw new SecurityException("原累计团队pool属于旧父执行；需原生审阅，不因模型启动补满");pool.put("modelRequestLimit",plan.getInt("modelRequestLimit")).put("toolOperationLimit",plan.getInt("toolOperationLimit"));pool.getJSONObject("budgetSource").put("parentPlanOwnerApproval",approval.getString("approvalId")).put("parentPlanHash",hash);owner.put("teamReservationPool",pool);write(owner);}
            JSONObject link=beginRelatedExternal(source.task,source.run,"teams","start",source.operation,arguments);if(link.optBoolean("knownOperation"))throw new SecurityException("原子提案启动前已有不匹配子任务，先核对原操作，不另建执行");
            JSONObject work=new JSONObject().put("format",dev.pocketengine.features.teams.TeamWorkItems.FORMAT).put("teamRunId",source.operation).put("parentTaskId",source.task).put("parentRunId",source.run).put("parentEpoch",source.epoch).put("targetProjectId",source.view().getString("targetProjectId")).put("items",new JSONArray(plan.getJSONArray("items").toString())).put("inventoryHash",plan.getString("inventoryHash")).put("planId",ref).put("revision",1).put("state","active").put("approvedAt",System.currentTimeMillis()).put("expiresAt",Math.min(plan.getLong("expiresAt"),permission.getLong("expiresAt"))).put("ownerReviewDigest",hash).put("parentPlanRef",ref).put("memberSelection",new JSONObject().put("members",plan.getJSONObject("definition").getJSONArray("members"))).put("decision",new JSONObject().put("origin","native_owner_approved_parent_proposal").put("goal",plan.getString("goal")).put("researchDecision",false)).put("executionAuthorized",true).put("originalProjectWriteAuthorized",false);
            JSONArray assignments=new JSONArray();for(int i=0;i<work.getJSONArray("items").length();i++){JSONObject item=work.getJSONArray("items").getJSONObject(i);assignments.put(new JSONObject().put("workItemId",item.getString("workItemId")).put("memberId",item.getString("memberId")).put("files",item.getJSONArray("files")));}
            JSONObject scope=new JSONObject().put("parentTaskId",source.task).put("parentRunId",source.run).put("targetProjectId",work.getString("targetProjectId")).put("definitionHash",plan.getString("inventoryHash")).put("workItemInventoryHash",plan.getString("inventoryHash")).put("parentDefinitionHash",capabilityFingerprint(plan.getJSONObject("definition"))).put("parentPlanRef",ref).put("parentPlanHash",hash).put("assignments",assignments).put("initialBudget",new JSONObject().put("callLimit",plan.getInt("modelRequestLimit")).put("toolCallLimit",plan.getInt("toolOperationLimit")).put("source","exact_owner_approved_parent_plan")).put("externalBudget",new JSONObject().put("externalTurnLimit",0).put("externalTurnsUsed",0));String scopeHash=capabilityFingerprint(scope);
            for(int i=0;i<work.getJSONArray("items").length();i++){JSONObject item=work.getJSONArray("items").getJSONObject(i);item.put("draftScope",new JSONObject().put("grantId",UUID.randomUUID().toString()).put("state","active").put("workItemId",item.getString("workItemId")).put("expiresAt",work.getLong("expiresAt")).put("remainingOperations",item.getJSONArray("files").length()+3).put("operationIds",new JSONArray()).put("planHash",scopeHash).put("parentRunId",source.run).put("parentEpoch",source.epoch));}
            JSONObject child=teamWriteTask(source.operation);child.put("teamWorkPlan",work).put("teamParentPlanRef",ref).put("teamParentPlanHash",hash);write(child);
            ContentValues claimed=new ContentValues();claimed.put("state","starting");claimed.put("start_operation_id",source.operation);claimed.put("start_run_id",source.run);claimed.put("start_epoch",source.epoch);claimed.put("child_task_id",source.operation);if(db.update("team_parent_plans",claimed,"task_id=? AND plan_ref=? AND state='proposed' AND child_task_id IS NULL",new String[]{source.task,ref})!=1)throw new SecurityException("原提案已被另一启动领取");
            TeamParentStart start=new TeamParentStart(this,source,ref,hash,new JSONObject().put("teamRunId",source.operation).put("planRef",ref).put("planHash",hash).put("definition",plan.getJSONObject("definition")).put("goal",plan.getString("goal")).put("scope",scope).put("workPlan",work),true);
            bindTeamWriteScope(source.operation,dev.pocketengine.features.teams.TeamWriteScope.fromParentStart(start));adoptTeamParentNativeCalls(source);claimTeamParentActionSql(source,source.operation);db.setTransactionSuccessful();return start;
        }finally{db.endTransaction();}
    }
    public synchronized void completeTeamParentStart(TeamParentStart start,JSONObject nativeRun)throws Exception{
        checkTeamParentStartSql(start);if(!start.origin.operation.equals(nativeRun.getString("id"))||!start.ref.equals(nativeRun.getJSONObject("workPlan").getString("planId")))throw new SecurityException("不是原提案的真实团队回执");ContentValues row=new ContentValues();row.put("state","started");if(db.update("team_parent_plans",row,"task_id=? AND plan_ref=? AND state='starting'",new String[]{start.origin.task,start.ref})!=1)throw new SecurityException("原启动记录已变化");updateExternal(start.origin.operation,nativeRun);
    }
    public synchronized JSONObject teamParentAccess(TeamParentOrigin source,String teamRun,boolean effect)throws Exception{
        checkTeamParentOriginSql(source);JSONObject team=teamWriteTask(teamRun);if(!source.task.equals(team.optString("parentTaskId")))throw new SecurityException("团队不属于这个原父任务；不能凭run ID读取其他会话或群");boolean related=false;JSONArray links=required(source.task).optJSONArray("relatedTasks");for(int i=0;links!=null&&i<links.length();i++)if(teamRun.equals(links.getJSONObject(i).optString("taskId")))related=true;if(!related)throw new SecurityException("原SQL没有此父子关系");
        if(effect){if(!source.run.equals(team.optString("parentRunId"))||team.optBoolean("cancelRequested")||team.optString("status").matches("cancelled|stopping|interrupted")||team.optString("teamParentPlanRef").isEmpty())throw new SecurityException("原团队已停止/待核对或此父执行没有已批编排scope；只允许查询");JSONObject row=teamParentPlanRow(source.task,team.getString("teamParentPlanRef"),team.getString("teamParentPlanHash")),plan=row.getJSONObject("plan");if(!row.optString("state").matches("starting|started")||plan.getLong("expiresAt")<=System.currentTimeMillis())throw new SecurityException("原编排scope已变或到期；消息不补期限/预算");}
        return team;
    }
    private void claimTeamParentActionSql(TeamParentOrigin source,String teamRun)throws Exception{
        teamParentAccess(source,teamRun,true);claimTeamParentPreparedToolSql(source,teamRun);
    }
    private void claimTeamParentPreparedToolSql(TeamParentOrigin source,String teamRun)throws Exception {
        checkTeamParentOriginSql(source);if(source.operation.isEmpty())throw new SecurityException("编排动作缺少原prepared operation");
        reserveTeamParentPreparedJournal(required(source.task),source.run,source.epoch,source.operation);
    }
    /** Journal entry charges even a later denied coordination attempt, once, against the same pool. */
    private void reserveTeamParentPreparedJournal(JSONObject owner,String run,int epoch,String operation)throws Exception {
        JSONObject pool=teamBudget(owner),binding=new JSONObject().put("teamRunId",pool.getJSONObject("budgetSource").getString("parentOrchestrationTeamRun")).put("childTaskId",owner.getString("id")).put("childRunId",run).put("childEpoch",epoch).put("memberId","@parent").put("assignmentId",owner.getString("id"));
        JSONObject reserved=dev.pocketengine.features.teams.TeamParentBudget.reserve(pool,binding,"parent_coordination","operation:"+operation,0,1);
        JSONArray ids=reserved.getJSONArray("operationIds");boolean found=false;for(int i=0;i<ids.length();i++)if(operation.equals(ids.getString(i)))found=true;if(!found)ids.put(operation);if(!reserved.getString("state").equals("settled"))reserved.put("state","unknown");owner.put("teamReservationPool",pool);write(owner);
    }
    public synchronized JSONObject claimTeamParentAction(TeamParentOrigin source,String teamRun)throws Exception{db.beginTransaction();try{claimTeamParentActionSql(source,teamRun);db.setTransactionSuccessful();return new JSONObject().put("operationId",source.operation).put("parentBudget",teamBudgetSnapshot(teamRun));}finally{db.endTransaction();}}
    private void reserveTeamParentNativeCall(JSONObject pool,String task,JSONObject nativeReceipt)throws Exception{
        JSONObject context=nativeReceipt.getJSONObject("context");if(!task.equals(context.getString("taskId"))||!context.getString("bindingKind").equals(InferenceReceipt.BindingKind.HOST_RUN.name()))throw new SecurityException("父编排模型计量不是原生本任务callId");
        String call=nativeReceipt.getString("callId");JSONObject binding=new JSONObject().put("teamRunId",pool.getJSONObject("budgetSource").getString("parentOrchestrationTeamRun")).put("childTaskId",task).put("childRunId",context.getString("runId")).put("childEpoch",context.getInt("epoch")).put("memberId","@parent").put("assignmentId",task);
        dev.pocketengine.features.teams.TeamParentBudget.reserve(pool,binding,"parent_coordination","parent_call:"+call,1,0);
    }
    private void adoptTeamParentNativeCalls(TeamParentOrigin source)throws Exception{
        JSONObject owner=required(source.task),pool=teamBudget(owner);if(pool.getJSONObject("budgetSource").optBoolean("includeParentInference"))return;
        pool.getJSONObject("budgetSource").put("includeParentInference",true).put("parentOrchestrationTeamRun",source.operation).put("parentModelEvidence","original_TaskAuditStore_native_callIds_usage_not_zero_for_unknown");
        JSONArray calls=TaskAuditStore.events(db,source.task,"inference_receipt");if(calls.length()>64)throw new IllegalStateException("本原父任务已超过本片明确累计模型额度，不清零历史调用");
        for(int i=0;i<calls.length();i++){JSONObject actual=calls.getJSONObject(i),context=actual.getJSONObject("context");reserveTeamParentNativeCall(pool,source.task,actual);dev.pocketengine.features.teams.TeamParentBudget.startInference(pool,source.task,context.getString("runId"),actual.getString("callId"));dev.pocketengine.features.teams.TeamParentBudget.finishInference(pool,actual.getString("callId"),actual);}
        try(Cursor rows=db.rawQuery("SELECT operation_id,run_id,tool,state FROM operations WHERE task_id=? AND tool IN ('teams_plan','teams_start','teams_message','teams_control','teams_get','teams_wait') ORDER BY created_at,operation_id",new String[]{source.task})){
            while(rows.moveToNext()){
                String op=rows.getString(0);if(parentOperationInPool(pool,op))continue;
                JSONObject binding=new JSONObject().put("teamRunId",source.operation).put("childTaskId",source.task).put("childRunId",rows.getString(1)).put("memberId","@parent").put("assignmentId",source.task);
                JSONObject reserved=dev.pocketengine.features.teams.TeamParentBudget.reserve(pool,binding,"parent_coordination","operation:"+op,0,1);reserved.getJSONArray("operationIds").put(op);
                boolean known="receipt".equals(rows.getString(3));reserved.put("state",known?"settled":"unknown");if(known)reserved.getJSONArray("actualReceiptRefs").put(op);
            }
        }
        pool.getJSONObject("budgetSource").put("parentToolEvidence","original_SQL_team_tool_operationIds_including_planning_and_approval_attempts");owner.put("teamReservationPool",pool);write(owner);
    }
    public synchronized JSONObject beginTeamParentRead(TeamParentOrigin source,String teamRun,JSONObject arguments)throws Exception{
        checkTeamParentOriginSql(source);db.beginTransaction();try{JSONObject team=teamParentAccess(source,teamRun,false),owner=teamBudgetOwner(team),pool=teamBudget(owner);String op=UUID.randomUUID().toString();
            JSONObject binding=new JSONObject().put("teamRunId",teamRun).put("childTaskId",source.task).put("childRunId",source.run).put("childEpoch",source.epoch).put("memberId","@parent").put("assignmentId",source.task),reservation=dev.pocketengine.features.teams.TeamParentBudget.reserve(pool,binding,"parent_coordination","operation:"+op,0,1);
            JSONObject permission=grant(source.run);if(permission.optInt("remainingOperations")<1)throw new IllegalStateException("原父操作额度已耗尽");permission.put("remainingOperations",permission.getInt("remainingOperations")-1);ContentValues grantRow=new ContentValues();grantRow.put("grant_json",permission.toString());db.update("runs",grantRow,"run_id=?",new String[]{source.run});
            reservation.getJSONArray("operationIds").put(op);reservation.put("state","unknown");ContentValues row=new ContentValues();row.put("operation_id",op);row.put("task_id",source.task);row.put("run_id",source.run);row.put("tool",source.tool);row.put("arguments",arguments.toString());row.put("state","unknown");row.put("created_at",System.currentTimeMillis());db.insertOrThrow("operations",null,row);owner.put("teamReservationPool",pool);write(owner);db.setTransactionSuccessful();return binding.put("operationId",op).put("reservationId",reservation.getString("reservationId"));
        }finally{db.endTransaction();}
    }
    public synchronized JSONObject teamParentExecutionState(TeamParentOrigin source,String teamRun)throws Exception{
        JSONObject team=teamParentAccess(source,teamRun,false),plan=team.optJSONObject("teamWorkPlan");int running=0,unknown=0;JSONArray children=new JSONArray();if(plan!=null)for(int i=0;i<plan.getJSONArray("items").length();i++){
            JSONObject item=plan.getJSONArray("items").getJSONObject(i),child=read(item.getString("assignmentId"));if(child==null)continue;boolean active;try(Cursor row=db.rawQuery("SELECT 1 FROM runs WHERE task_id=? AND status='running' LIMIT 1",new String[]{child.getString("id")})){active=row.moveToFirst();}boolean unresolved=hasUnresolved(child.getString("id"));if(active)running++;if(unresolved)unknown++;children.put(dev.pocketengine.features.teams.TeamWorkItems.exactIdentity(item).put("status",unresolved?"blocked_unknown":child.optString("status")).put("activeAttempt",active).put("hasUnresolvedEffects",unresolved));
        }return new JSONObject().put("children",children).put("activeChildAttempts",running).put("unknownChildCount",unknown).put("hasUnresolvedEffects",unknown>0).put("resourcesReleased",running==0).put("stopRequested",team.optBoolean("cancelRequested"));
    }
    public synchronized JSONObject queueTeamParentInput(TeamParentOrigin source,JSONObject input)throws Exception{
        teamParentAccess(source,input.getString("runId"),true);db.beginTransaction();try{JSONObject result=queueTeamAssignmentInput(input),task=required(input.getString("assignmentId"));JSONArray rows=task.getJSONArray("teamConversationInputs");for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);if(!input.getString("messageId").equals(row.getString("messageId")))continue;if(result.optBoolean("duplicate")&&!source.task.equals(row.optString("senderParentTaskId")))throw new SecurityException("不能把主人或其他来源的原消息重标为父代理");row.put("senderId","@parent").put("senderRole","parent_model").put("senderParentTaskId",source.task);result=new JSONObject(row.toString()).put("duplicate",result.optBoolean("duplicate"));}task.put("teamConversationInputs",rows);write(task);db.setTransactionSuccessful();return result;}finally{db.endTransaction();}
    }
    public synchronized JSONObject answerTeamParentQuestion(TeamParentOrigin source,JSONObject input)throws Exception{
        teamParentAccess(source,input.getString("runId"),true);db.beginTransaction();try{JSONObject result=answerTeamAssignment(input.getString("runId"),input.getString("assignmentId"),input.getString("questionId"),input.getString("answerId"),input.getString("answer")),task=required(input.getString("assignmentId"));JSONArray rows=task.getJSONArray("teamQuestions");for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);if(!input.getString("questionId").equals(row.getString("questionId")))continue;if(result.optBoolean("duplicate")&&!source.task.equals(row.optString("answerParentTaskId")))throw new SecurityException("父代理不能冒充原主人回答");row.put("answerActor","parent_model").put("answerParentTaskId",source.task);result=new JSONObject(row.toString()).put("duplicate",result.optBoolean("duplicate")).put("awaitingContinuation",true);}task.put("teamQuestions",rows);write(task);db.setTransactionSuccessful();return result;}finally{db.endTransaction();}
    }
    /** A determinate SQL rejection, minted only before any action claim, write or dispatch.
     * It says nothing about the CHILD writer's still-unknown effect and never releases its budget. */
    public static final class TeamParentActionDenied extends Exception {
        private final String assignment;
        private TeamParentActionDenied(String assignment){super("原作者仍有未决写入；不能改派或继续，原效果及预留仍待核对");this.assignment=assignment;}
        public JSONObject receipt()throws Exception{return new JSONObject().put("ok",false).put("accepted",false).put("code","team_control_denied").put("status","denied").put("nativeActionDispatched",false).put("effect","none").put("denialSource","native_sql_before_action_claim").put("assignmentId",assignment).put("childHasUnresolvedEffects",true).put("message",getMessage());}
    }
    /** Narrow read-only preflight. Later races/failures retain the original Runner's unknown fence. */
    public synchronized void checkTeamParentItemWriterBeforeAction(TeamParentOrigin source,String teamRun,String itemId,int itemRevision,int ownerGeneration)throws Exception {
        teamParentAccess(source,teamRun,true);
        JSONObject exact;try(Cursor row=db.rawQuery("SELECT arguments FROM operations WHERE task_id=? AND run_id=? AND operation_id=? AND tool='teams_control' AND state='prepared'",new String[]{source.task,source.run,source.operation})){if(!row.moveToFirst())throw new SecurityException("原动作预检缺少真实prepared意图");exact=new JSONObject(row.getString(0));}
        if(!"teams_control".equals(source.tool)||!source.argsHash.equals(capabilityFingerprint(exact))||!exact.optString("action").matches("reassign|resume")||!teamRun.equals(exact.optString("teamRunId"))||!itemId.equals(exact.optString("workItemId"))||itemRevision!=exact.optInt("itemRevision")||ownerGeneration!=exact.optInt("ownerGeneration"))throw new SecurityException("原动作预检身份不符");
        JSONObject team=teamWriteTask(teamRun),item=dev.pocketengine.features.teams.TeamWorkItems.find(team.getJSONObject("teamWorkPlan").getJSONArray("items"),itemId);currentTeamWorkItem(team,item);
        if(item.getInt("itemRevision")!=itemRevision||item.getInt("ownerGeneration")!=ownerGeneration)throw new SecurityException("原工作项或负责人已变版");
        JSONObject child=read(item.getString("assignmentId"));if(child!=null&&hasUnresolved(child.getString("id")))throw new TeamParentActionDenied(child.getString("id"));
    }
    public synchronized JSONObject transferTeamParentItem(TeamParentOrigin source,String teamRun,String itemId,String nextMember,int itemRevision,int ownerGeneration)throws Exception{
        teamParentAccess(source,teamRun,true);db.beginTransaction();try{JSONObject team=teamWriteTask(teamRun),plan=team.getJSONObject("teamWorkPlan"),item=dev.pocketengine.features.teams.TeamWorkItems.find(plan.getJSONArray("items"),itemId);currentTeamWorkItem(team,item);checkUnstartedWorkItem(item);
            if(item.getInt("itemRevision")!=itemRevision||item.getInt("ownerGeneration")!=ownerGeneration||item.getString("memberId").equals(nextMember))throw new SecurityException("原工作项/负责人已变化");JSONObject selected=dev.pocketengine.features.teams.TeamWorkPlan.find(plan.getJSONObject("memberSelection").getJSONArray("members"),nextMember);if(!"model".equals(selected.optString("runner","model"))||!"phone".equals(selected.opt("device")))throw new SecurityException("只能转给本次主人已经选定的本机成员，不扩大成员或工具范围");
            claimTeamParentActionSql(source,teamRun);team=teamWriteTask(teamRun);plan=team.getJSONObject("teamWorkPlan");item=dev.pocketengine.features.teams.TeamWorkItems.find(plan.getJSONArray("items"),itemId);checkUnstartedWorkItem(item);item.getJSONArray("assignmentHistory").put(dev.pocketengine.features.teams.TeamWorkItems.exactIdentity(item).put("endedAt",System.currentTimeMillis()).put("reason","approved_parent_unstarted_transfer").put("operationId",source.operation));item.put("memberId",nextMember).put("assignmentId",UUID.randomUUID().toString()).put("ownerGeneration",item.getInt("ownerGeneration")+1).put("itemRevision",item.getInt("itemRevision")+1).put("status","queued").put("artifacts",new JSONArray());item.getJSONObject("draftScope").put("grantId",UUID.randomUUID().toString());plan.put("revision",plan.getInt("revision")+1);team.put("teamWorkPlan",plan);write(team);db.setTransactionSuccessful();return teamWorkPlanSnapshot(teamRun);
        }finally{db.endTransaction();}
    }
    public synchronized JSONObject continueTeamParentItem(TeamParentOrigin source,String teamRun,String itemId,int itemRevision,int ownerGeneration,int nextRunGeneration,int nextMemberGeneration)throws Exception{
        teamParentAccess(source,teamRun,true);db.beginTransaction();try{JSONObject team=teamWriteTask(teamRun),plan=team.getJSONObject("teamWorkPlan"),item=dev.pocketengine.features.teams.TeamWorkItems.find(plan.getJSONArray("items"),itemId),child=read(item.getString("assignmentId"));currentTeamWorkItem(team,item);teamWorkPlanParent(teamRun);
            if(child==null){
                if(item.getInt("itemRevision")!=itemRevision||item.getInt("ownerGeneration")!=ownerGeneration||!item.optString("status").matches("queued|waiting_dependency|blocked_dependency|paused")||item.has("parentUnstartedContinuation"))throw new SecurityException("未开始项已改版或已有单次继续claim，不重复排队");
                JSONObject scope=item.getJSONObject("draftScope");if(!"active".equals(scope.optString("state"))||scope.getLong("expiresAt")<=System.currentTimeMillis()||scope.getInt("remainingOperations")<1)throw new SecurityException("原草稿scope已撤销、到期或耗尽");
                claimTeamParentActionSql(source,teamRun);team=teamWriteTask(teamRun);plan=team.getJSONObject("teamWorkPlan");item=dev.pocketengine.features.teams.TeamWorkItems.find(plan.getJSONArray("items"),itemId);
                JSONObject owner=teamBudgetOwner(team),pool=teamBudget(owner),binding=new JSONObject().put("teamRunId",teamRun).put("childTaskId",item.getString("assignmentId")).put("childRunId","").put("memberId",item.getString("memberId")).put("assignmentId",item.getString("assignmentId"));
                JSONObject reservation=dev.pocketengine.features.teams.TeamParentBudget.reserve(pool,binding,"member","parent_continue:"+source.operation,1,0);
                item.put("parentUnstartedContinuation",dev.pocketengine.features.teams.TeamWorkItems.exactIdentity(item).put("reservationId",reservation.getString("reservationId")).put("runGeneration",nextRunGeneration).put("memberGeneration",nextMemberGeneration).put("parentOperationId",source.operation)).put("status","queued");team.put("teamWorkPlan",plan).put("status","queued");owner.put("teamReservationPool",pool);write(owner);write(team);db.setTransactionSuccessful();return teamWorkPlanSnapshot(teamRun);
            }
            if(item.getInt("itemRevision")!=itemRevision||item.getInt("ownerGeneration")!=ownerGeneration||child.optBoolean("cancelRequested")||hasUnresolved(child.getString("id"))||teamHasPendingQuestion(child)||!child.optString("status").matches("paused|interrupted|awaiting_user|needs_confirmation|cancelled"))throw new SecurityException("原child尚非可继续安全点；未知writer不重派、不退额度");
            try(Cursor active=db.rawQuery("SELECT 1 FROM runs WHERE task_id=? AND status='running' LIMIT 1",new String[]{child.getString("id")})){if(active.moveToFirst())throw new SecurityException("原child实际Runner尚未退出，不并发续接");}
            JSONObject previous=child.optJSONObject("teamDraftContinuation");if(previous!=null&&!previous.has("runId"))throw new SecurityException("已有原继续claim，未消费前不重复唤醒");JSONObject scope=item.getJSONObject("draftScope");if(!scope.optString("state").equals("active")||scope.getLong("expiresAt")<=System.currentTimeMillis()||scope.optInt("remainingOperations")<1)throw new SecurityException("原隔离草稿范围已经到期、撤销或耗尽，需要主人原生重审，不补授权");
            claimTeamParentActionSql(source,teamRun);team=teamWriteTask(teamRun);plan=team.getJSONObject("teamWorkPlan");item=dev.pocketengine.features.teams.TeamWorkItems.find(plan.getJSONArray("items"),itemId);JSONObject owner=teamBudgetOwner(team),pool=teamBudget(owner),binding=new JSONObject().put("teamRunId",teamRun).put("runGeneration",nextRunGeneration).put("memberGeneration",nextMemberGeneration).put("childTaskId",child.getString("id")).put("childRunId",child.getString("runId")).put("childEpoch",child.getInt("coordinatorEpoch")).put("memberId",item.getString("memberId")).put("assignmentId",child.getString("id"));
            JSONObject reservation=dev.pocketengine.features.teams.TeamParentBudget.reserve(pool,binding,"member","parent_continue:"+source.operation,1,0);child.put("teamDraftContinuation",new JSONObject().put("reservationId",reservation.getString("reservationId")).put("workItemId",itemId).put("runGeneration",nextRunGeneration).put("memberGeneration",nextMemberGeneration).put("parentOperationId",source.operation));item.put("status","queued");team.put("teamWorkPlan",plan).put("status","queued");owner.put("teamReservationPool",pool);write(owner);write(child);write(team);db.setTransactionSuccessful();return teamWorkPlanSnapshot(teamRun);
        }finally{db.endTransaction();}
    }
    public synchronized JSONObject teamParentQuestion(TeamParentOrigin source,String teamRun,String assignment,String question)throws Exception{
        JSONObject team=teamParentAccess(source,teamRun,false),child=required(assignment),identity=child.optJSONObject("teamAssignment");if(identity==null||!teamRun.equals(identity.optString("teamRunId"))||!child.getJSONObject("origin").optString("kind").equals("team_assignment"))throw new SecurityException("问题不属于原父团队分工");JSONArray rows=child.optJSONArray("teamQuestions");for(int i=0;rows!=null&&i<rows.length();i++)if(question.equals(rows.getJSONObject(i).getString("questionId")))return new JSONObject(rows.getJSONObject(i).toString());throw new SecurityException("原问题不存在，不猜最近问题");
    }

    /** Read-only original owner mailbox observation: no poll, no delivery, no second sequence. */
    public synchronized boolean teamParentHasSteering(TeamParentOrigin source)throws Exception{
        checkTeamParentOriginSql(source);JSONObject task=required(source.task),mailbox=task.optJSONObject("ownerInputMailbox");if(mailbox==null||!mailbox.optBoolean("open")||!source.run.equals(mailbox.optString("runId"))||source.epoch!=mailbox.optInt("epoch"))return false;java.util.Set<String> included=new java.util.HashSet<>();JSONArray history=checkpoint(source.run).optJSONArray("messages");for(int i=0;history!=null&&i<history.length();i++){String id=history.getJSONObject(i).optString("ownerMessageId");if(!id.isEmpty())included.add(id);}JSONArray rows=task.optJSONArray("ownerInputs");for(int i=0;rows!=null&&i<rows.length();i++){JSONObject row=rows.getJSONObject(i);if(!OwnerInputQueue.withdrawn(row)&&!"materialized".equals(OwnerInputQueue.state(row))&&source.run.equals(row.optString("acceptedRunId"))&&source.epoch==row.optInt("acceptedEpoch")&&"steer".equals(row.optString("mode"))&&!included.contains(row.getString("messageId")))return true;}return false;
    }

    /** A current foreground native owner window is distinct from model execution and survives no background approval. */
    public final class TeamWorkWindow implements AutoCloseable {
        private final TaskStore issuer=TaskStore.this;private final String teamRun;private volatile boolean alive=true,foreground;private volatile long pageGeneration;
        private TeamWorkWindow(String run){teamRun=run;}
        public synchronized void setForeground(boolean value){boolean next=alive&&value;if(foreground!=next)pageGeneration++;foreground=next;}
        private void require(){if(!alive||!foreground)throw new SecurityException("请在原团队前台窗口审阅明确工作项与预算");}
        public void close(){alive=false;foreground=false;}
    }
    public static final class TeamWorkReview {
        private final TaskStore issuer;private final TeamWorkWindow window;private final dev.pocketengine.features.teams.TeamWorkItems.Plan plan;
        private final String data,digest;private final long pageGeneration;private boolean consumed;
        private TeamWorkReview(TaskStore issuer,TeamWorkWindow window,dev.pocketengine.features.teams.TeamWorkItems.Plan plan,JSONObject data)throws Exception{this.issuer=issuer;this.window=window;this.plan=plan;this.data=data.toString();digest=capabilityFingerprint(data);pageGeneration=window.pageGeneration;}
        public JSONObject view()throws Exception{return new JSONObject(data).put("digest",digest);}
    }
    public synchronized TeamWorkWindow openTeamWorkWindow(String teamRun)throws Exception{requirePrivateOwner(teamWriteTask(teamRun));return new TeamWorkWindow(teamRun);}
    public synchronized JSONObject teamWorkPlanParent(String teamRun)throws Exception {
        JSONObject team=teamWriteTask(teamRun);requirePrivateOwner(team);String parentId=team.optString("parentTaskId"),run=team.optString("parentRunId");if(parentId.isEmpty()||run.isEmpty())throw new SecurityException("此原讨论没有主人选定项目的真实父任务；可保留讨论，但不能从页面JSON临时取得其他项目写入权");
        JSONObject parent=required(parentId);requirePrivateOwner(parent);JSONObject permission=grant(run);if(!run.equals(parent.optString("runId"))||parent.optBoolean("cancelRequested")||parent.optString("status").matches("completed|stopping|cancelled|paused|pausing|interrupted")||permission==null||permission.optLong("expiresAt")<=System.currentTimeMillis()||!permission.optString("principalId").equals("owner")||!permission.optString("spaceId").equals("private"))throw new SecurityException("原项目父执行授权已变、停止或到期");
        return new JSONObject().put("teamRunId",teamRun).put("parentTaskId",parentId).put("parentRunId",run).put("parentEpoch",parent.getInt("coordinatorEpoch")).put("targetProjectId",parent.getJSONObject("developmentContext").getString("projectId")).put("remainingOperations",permission.getInt("remainingOperations")).put("expiresAt",permission.getLong("expiresAt"));
    }
    private JSONObject teamWorkPlanFacts(TeamWorkWindow window,dev.pocketengine.features.teams.TeamWorkItems.Plan nativePlan)throws Exception {
        window.require();nativePlan.checkCurrent();JSONObject plan=nativePlan.view(),parent=teamWorkPlanParent(window.teamRun),team=teamWriteTask(window.teamRun);if(team.optBoolean("cancelRequested")||team.optString("status").matches("completed|stopping|cancelled"))throw new SecurityException("原讨论任务已停止或主人已确认完成；新制作需原项目的新明确任务，不能复活原执行");if(team.optJSONObject("teamWorkPlan")!=null)throw new SecurityException("原讨论已有工作计划，请处理精确原工作项，不重建全员assignment");
        for(String key:new String[]{"teamRunId","parentTaskId","parentRunId","parentEpoch","targetProjectId"})if(!canonical(plan.get(key)).equals(canonical(parent.get(key))))throw new SecurityException("工作计划源不是原项目/父执行/epoch");
        if(nativePlan.projectSelection()!=null)return teamProjectWorkPlanFacts(window,nativePlan,plan,parent,team);
        JSONObject source=nativePlan.source().view();dev.pocketengine.features.teams.TeamWorkItems.validate(plan.getJSONArray("items"),source.getJSONArray("members"));JSONObject pool=teamBudget(teamBudgetOwner(team)),budget=dev.pocketengine.features.teams.TeamParentBudget.snapshot(pool);int files=0;JSONArray items=plan.getJSONArray("items");for(int i=0;i<items.length();i++)files+=items.getJSONObject(i).getJSONArray("files").length();
        // Concrete selected-file plan: authors prepare/read/write/freeze; reviewer manifest/read/verdict; integrator read/CAS.
        int dependencyReads=0;for(int i=0;i<items.length();i++){JSONArray deps=items.getJSONObject(i).getJSONArray("dependsOn");for(int d=0;d<deps.length();d++)dependencyReads+=dev.pocketengine.features.teams.TeamWorkItems.find(items,deps.getString(d)).getJSONArray("files").length();}int leastTools=4*files+2*items.length()+3+dependencyReads,extraTools=Math.min(2,Math.max(0,32-budget.getInt("toolOperationsReservedOrUsed")-leastTools));
        int proposedTools=budget.getInt("toolOperationsReservedOrUsed")+leastTools+extraTools,additionalModels=items.length()*3+files*5+6+dependencyReads,proposedModels=Math.min(64,budget.getInt("modelRequestsReservedOrUsed")+additionalModels);
        if(proposedTools>32||parent.getInt("remainingOperations")<leastTools||proposedModels-budget.getInt("modelRequestsReservedOrUsed")<additionalModels)throw new IllegalStateException("原父剩余预算不足以完成本次所选文件链；先调整原计划/父授权，不启动必然中途耗尽的工作项");
        proposedTools=Math.min(proposedTools,budget.getInt("toolOperationsReservedOrUsed")+parent.getInt("remainingOperations"));
        return new JSONObject().put("kind","work_plan").put("plan",plan).put("scope",dev.pocketengine.features.teams.TeamWriteScope.fromWorkPlan(nativePlan).snapshot()).put("sourceRevision",source.getLong("revision")).put("parent",parent).put("parentBudget",budget)
                .put("proposedModelRequestLimit",Math.max(pool.getInt("modelRequestLimit"),proposedModels)).put("proposedToolOperationLimit",Math.max(pool.getInt("toolOperationLimit"),proposedTools))
                .put("budgetPlan",new JSONObject().put("selectedFileToolSteps",leastTools).put("toolRetryMargin",extraTools).put("dependencyArtifactReads",dependencyReads).put("estimatedAdditionalModelRequests",additionalModels).put("modelRetryMargin",1).put("modelPlanSource","authors_prepare_each_read_each_write_freeze_finalize;review_manifest_each_read_verdict_finalize;integrator_read_each_apply_and_approval_resume_finalize").put("modelEstimateNotGuarantee",true).put("autoRefill",false).put("tokenCostKnown",false)).put("expiresAt",Math.min(parent.getLong("expiresAt"),System.currentTimeMillis()+10*60_000L)).put("originalProjectWriteAuthorized",false);
    }
    private JSONObject teamProjectWorkPlanFacts(TeamWorkWindow window,dev.pocketengine.features.teams.TeamWorkItems.Plan nativePlan,JSONObject plan,JSONObject parent,JSONObject team)throws Exception {
        dev.pocketengine.features.teams.TeamProjectWorkspaces.Selection nativeSelection=nativePlan.projectSelection();nativeSelection.checkCurrent();JSONObject selected=nativeSelection.view(),actualParent=required(parent.getString("parentTaskId")),development=actualParent.getJSONObject("developmentContext"),permission=grant(parent.getString("parentRunId"));
        for(String key:new String[]{"teamRunId","parentTaskId","parentRunId","parentEpoch"})if(!canonical(plan.get(key)).equals(canonical(selected.get(key))))throw new SecurityException("完整项目选择不是原parent/run/epoch");if(!development.getString("projectId").equals(selected.getString("parentProjectId"))||!development.getString("checkoutId").equals(selected.getString("parentCheckoutId"))||!development.getString("runtime").equals(selected.getString("runtimeId"))||!selected.optBoolean("sameUidExecutionAcknowledged")||!selected.optBoolean("allowCommands")||selected.optBoolean("scopeEnforced")||selected.optBoolean("osSandboxVerified"))throw new SecurityException("原完整项目执行位置或同UID风险确认不符");
        JSONObject pool=teamBudget(teamBudgetOwner(team)),budget=dev.pocketengine.features.teams.TeamParentBudget.snapshot(pool),scope=dev.pocketengine.features.teams.TeamWriteScope.fromWorkPlan(nativePlan).snapshot();JSONArray items=plan.getJSONArray("items"),assignments=scope.getJSONArray("assignments");int files=0,templates=0,pty=0,dependencyReads=0;
        for(int i=0;i<items.length();i++){JSONObject item=items.getJSONObject(i);files+=item.getJSONArray("files").length();JSONArray dependencies=item.getJSONArray("dependsOn");for(int d=0;d<dependencies.length();d++)dependencyReads+=dev.pocketengine.features.teams.TeamWorkItems.find(items,dependencies.getString(d)).getJSONArray("files").length();JSONArray commands=assignments.getJSONObject(i).getJSONObject("projectSelection").getJSONArray("templates");templates+=commands.length();for(int t=0;t<commands.length();t++)if(commands.getJSONObject(t).getJSONObject("command").getString("terminalMode").equals("pty"))pty++;}
        JSONArray integrator=dev.pocketengine.features.teams.TeamProjectWorkspaces.forIntegrator(selected).getJSONArray("templates");int integratorPty=0;for(int t=0;t<integrator.length();t++)if(integrator.getJSONObject(t).getJSONObject("command").getString("terminalMode").equals("pty"))integratorPty++;
        int plannedTools=6*files+4*items.length()+5*templates+5*pty+dependencyReads+10+5*integrator.length()+5*integratorPty,margin=4,desired=budget.getInt("toolOperationsReservedOrUsed")+plannedTools+margin;
        int proposed=Math.max(pool.getInt("toolOperationLimit"),desired);if(proposed>dev.pocketengine.features.teams.TeamParentBudget.MAX_PROJECT_TOOL_OPERATIONS)throw new IllegalStateException("本次完整项目流程估计超过128工具工程上限，请缩小工作项/命令，不启动必然不足的计划");int estimatedModels=plannedTools+items.length()+4,models=Math.min(dev.pocketengine.features.teams.TeamParentBudget.MAX_MODEL_REQUESTS,Math.max(pool.getInt("modelRequestLimit"),budget.getInt("modelRequestsReservedOrUsed")+estimatedModels));if(models-budget.getInt("modelRequestsReservedOrUsed")<items.length()*3+6)throw new IllegalStateException("原累计模型请求已不足以继续本次完整项目流程，不清零旧用量");
        int requiredRemaining=proposed-budget.getInt("toolOperationsReservedOrUsed"),shortfall=Math.max(0,requiredRemaining-permission.getInt("remainingOperations"));parent.put("stepCeiling",permission.getInt("stepCeiling")).put("spentOperations",permission.getInt("stepCeiling")-permission.getInt("remainingOperations")).put("grantFingerprint",capabilityFingerprint(permission));
        JSONObject planning=new JSONObject().put("plannedToolOperations",plannedTools).put("toolRetryMargin",margin).put("planSource","authors_prepare_inspect_search_each_read_exact_edit_plan_execute_freeze;review_manifest_each_read_verdict;unique_integrator_read_each_prepared_approval_CAS_result_copy_inspect_same_source_command_plan_approval_resume_status_package_plan_approval_resume_freeze_readback").put("perCommandPlanApprovalResumeStatusSteps",5).put("authorCommandTemplates",templates).put("integratorCommandTemplates",integrator.length()).put("interactiveInputAndDrainSteps",5*(pty+integratorPty)).put("dependencyArtifactReads",dependencyReads).put("estimatedAdditionalModelRequests",estimatedModels).put("modelEstimateCapped",budget.getInt("modelRequestsReservedOrUsed")+estimatedModels>models).put("strictMinimum",false).put("complexFlowGuaranteed",false).put("autoRefill",false).put("originalModelLimitUnchangedMaximum",64).put("tokenCostKnown",false);
        return new JSONObject().put("kind","work_plan").put("plan",plan).put("scope",scope).put("sourceRevision",nativePlan.source().view().getLong("revision")).put("parent",parent).put("parentBudget",budget).put("proposedModelRequestLimit",models).put("proposedToolOperationLimit",proposed).put("budgetPlan",planning).put("parentBudgetIncreaseRequired",shortfall>0).put("parentOperationShortfall",shortfall).put("proposedParentStepCeiling",permission.getInt("stepCeiling")+shortfall).put("proposedParentRemainingOperations",permission.getInt("remainingOperations")+shortfall).put("expiresAt",Math.min(permission.getLong("expiresAt"),System.currentTimeMillis()+600_000L)).put("originalProjectWriteAuthorized",false);
    }
    /** Native foreground budget-only decision. It never widens tools/resources/expiry or revives the parent run. */
    public synchronized TeamWorkReview approveTeamProjectParentBudget(TeamWorkReview review,String digest)throws Exception {
        if(review==null||review.issuer!=this||review.consumed||review.plan==null||review.plan.projectSelection()==null||!review.digest.equals(digest))throw new SecurityException("请确认本窗口完整项目的准确预算缺口");review.window.require();if(review.pageGeneration!=review.window.pageGeneration)throw new SecurityException("原前台预算窗口已离开");review.plan.checkCurrent();db.beginTransaction();try{
            JSONObject shown=new JSONObject(review.data),current=teamWorkPlanFacts(review.window,review.plan);for(String key:new String[]{"plan","scope","sourceRevision","parent","parentBudget","proposedModelRequestLimit","proposedToolOperationLimit","parentOperationShortfall","proposedParentStepCeiling","proposedParentRemainingOperations"})if(!canonical(shown.get(key)).equals(canonical(current.get(key))))throw new SecurityException("真实父用量/预留/未知或计划已变化，请重新审阅");JSONObject parent=shown.getJSONObject("parent"),task=required(parent.getString("parentTaskId")),permission=grant(parent.getString("parentRunId")),pool=teamBudget(task);int shortfall=shown.getInt("parentOperationShortfall");if(shortfall<1||shown.getInt("proposedParentStepCeiling")>400||shown.getInt("proposedParentRemainingOperations")>dev.pocketengine.features.teams.TeamParentBudget.MAX_PROJECT_TOOL_OPERATIONS||!parent.getString("grantFingerprint").equals(capabilityFingerprint(permission))||!parent.getString("parentRunId").equals(task.optString("runId"))||parent.getInt("parentEpoch")!=task.optInt("coordinatorEpoch")||permission.optLong("expiresAt")<=System.currentTimeMillis()||task.optBoolean("cancelRequested"))throw new SecurityException("原同run预算已变/过期或超过累计400，本次未增加");
            String before=permission.toString();permission.put("stepCeiling",shown.getInt("proposedParentStepCeiling")).put("remainingOperations",shown.getInt("proposedParentRemainingOperations"));ContentValues changed=new ContentValues();changed.put("grant_json",permission.toString());if(db.update("runs",changed,"task_id=? AND run_id=? AND epoch=? AND grant_json=?",new String[]{task.getString("id"),parent.getString("parentRunId"),String.valueOf(parent.getInt("parentEpoch")),before})!=1)throw new SecurityException("原父预算CAS变化");
            pool.put("toolOperationLimit",shown.getInt("proposedToolOperationLimit"));pool.getJSONObject("budgetSource").put("nativeProjectWorkPlan",true).put("nativeParentBudgetReview",digest).put("parentOperationIncrease",shortfall);task.put("teamReservationPool",pool).put("stepCeiling",permission.getInt("stepCeiling"));appendEvent(task,new JSONObject().put("type","team_project_parent_budget_increased").put("runId",parent.getString("parentRunId")).put("coordinatorEpoch",parent.getInt("parentEpoch")).put("oldStepCeiling",parent.getInt("stepCeiling")).put("stepCeiling",permission.getInt("stepCeiling")).put("remainingOperations",permission.getInt("remainingOperations")).put("increase",shortfall).put("toolsChanged",false).put("resourcesChanged",false).put("expiryChanged",false).put("executionAuthorized",false));write(task);review.plan.checkCurrent();review.window.require();db.setTransactionSuccessful();review.consumed=true;
        }finally{db.endTransaction();}
        return new TeamWorkReview(this,review.window,review.plan,teamWorkPlanFacts(review.window,review.plan));
    }
    public synchronized TeamWorkReview prepareTeamWorkPlan(TeamWorkWindow window,dev.pocketengine.features.teams.TeamWorkItems.Plan nativePlan)throws Exception {
        if(window==null||window.issuer!=this)throw new SecurityException("不是原主人工作项窗口");JSONObject data=teamWorkPlanFacts(window,nativePlan);return new TeamWorkReview(this,window,nativePlan,data);
    }
    public synchronized JSONObject approveTeamWorkPlan(TeamWorkReview review,String digest)throws Exception {
        if(review==null||review.issuer!=this||review.consumed||review.plan==null||!review.digest.equals(digest))throw new SecurityException("不是本次精确原生工作计划审阅");review.window.require();if(review.pageGeneration!=review.window.pageGeneration)throw new SecurityException("原前台审阅已离开，请重新核对");review.plan.checkCurrent();db.beginTransaction();try{
            JSONObject shown=new JSONObject(review.data),current=teamWorkPlanFacts(review.window,review.plan);for(String key:new String[]{"plan","scope","sourceRevision","parentBudget","proposedModelRequestLimit","proposedToolOperationLimit"})if(!canonical(shown.get(key)).equals(canonical(current.get(key))))throw new SecurityException("工作计划、范围或实际父预算已变化，请重新审阅");if(shown.getLong("expiresAt")<=System.currentTimeMillis())throw new SecurityException("原工作计划审阅已到期");
            if(review.plan.projectSelection()!=null&&(!canonical(shown.getJSONObject("parent")).equals(canonical(current.getJSONObject("parent")))||current.optBoolean("parentBudgetIncreaseRequired")))throw new SecurityException("原父真实grant用量/权限或预算缺口在scope批准前变化，需重新审阅；不启动注定不足的成员");
            if(shown.optBoolean("parentBudgetIncreaseRequired"))throw new SecurityException("原父grant预算存在明确缺口，请先在本原生窗口仅批准预算增加，再重新确认scope；不会自动补额");
            JSONObject team=teamWriteTask(review.window.teamRun),owner=teamBudgetOwner(team),pool=teamBudget(owner),plan=new JSONObject(shown.getJSONObject("plan").toString());pool.put("modelRequestLimit",shown.getInt("proposedModelRequestLimit")).put("toolOperationLimit",shown.getInt("proposedToolOperationLimit"));if(review.plan.projectSelection()!=null)pool.getJSONObject("budgetSource").put("nativeProjectWorkPlan",true);pool.getJSONObject("budgetSource").put("workPlanOwnerReviewDigest",digest).put("workPlanBudgetPlan",shown.getJSONObject("budgetPlan"));
            plan.put("memberSelection",new JSONObject().put("members",review.plan.source().view().getJSONArray("members"))).put("planId",UUID.randomUUID().toString()).put("revision",1).put("state","active").put("approvedAt",System.currentTimeMillis()).put("expiresAt",shown.getLong("expiresAt")).put("ownerReviewDigest",digest).put("executionAuthorized",true);JSONArray items=plan.getJSONArray("items");for(int i=0;i<items.length();i++){JSONObject item=items.getJSONObject(i);item.put("draftScope",new JSONObject().put("grantId",UUID.randomUUID().toString()).put("state","active").put("workItemId",item.getString("workItemId")).put("expiresAt",plan.getLong("expiresAt")).put("remainingOperations",review.plan.projectSelection()==null?item.getJSONArray("files").length()+3:shown.getInt("proposedToolOperationLimit")).put("operationIds",new JSONArray()).put("planHash",capabilityFingerprint(shown.getJSONObject("scope"))).put("parentRunId",shown.getJSONObject("parent").getString("parentRunId")).put("parentEpoch",shown.getJSONObject("parent").getInt("parentEpoch")));}
            team.put("teamWorkPlan",plan).put("teamWriteScope",shown.getJSONObject("scope")).put("status","queued");owner.put("teamReservationPool",pool);review.window.require();review.plan.checkCurrent();write(owner);write(team);db.setTransactionSuccessful();review.consumed=true;return new JSONObject().put("workPlan",plan).put("scope",shown.getJSONObject("scope")).put("parentBudget",dev.pocketengine.features.teams.TeamParentBudget.snapshot(pool));
        }finally{db.endTransaction();}
    }
    /** Read-only native source of an exact completed outcome, never a restored model control or JSON grant. */
    public static final class TeamWorkRevisionSource {
        private final TaskStore issuer;private final TeamWorkWindow window;private final dev.pocketengine.features.teams.TeamCoordinator.WorkMutationSource nativeSource;
        private final String facts,itemId,text,requestId;private final int itemRevision,ownerGeneration;private final long pageGeneration,expiresAt;
        private TeamWorkRevisionSource(TaskStore issuer,TeamWorkWindow window,dev.pocketengine.features.teams.TeamCoordinator.WorkMutationSource original,String itemId,int itemRevision,int ownerGeneration,String text,JSONObject facts)throws Exception{this.issuer=issuer;this.window=window;nativeSource=original;this.itemId=itemId;this.itemRevision=itemRevision;this.ownerGeneration=ownerGeneration;this.text=text;this.facts=facts.toString();requestId=UUID.randomUUID().toString();pageGeneration=window.pageGeneration;expiresAt=Math.min(facts.getJSONObject("workPlan").optLong("expiresAt"),System.currentTimeMillis()+120_000L);}
        public JSONObject view()throws Exception{return new JSONObject(facts).put("requestId",requestId).put("text",text).put("expiresAt",expiresAt);}
        public void checkCurrent()throws Exception{issuer.checkTeamWorkRevisionSource(this);}
    }
    public static final class TeamWorkRevisionReview {
        private final TaskStore issuer;private final dev.pocketengine.features.teams.TeamWorkItemRevision.Plan plan;private final String data,digest;private boolean consumed;
        private TeamWorkRevisionReview(TaskStore issuer,dev.pocketengine.features.teams.TeamWorkItemRevision.Plan plan,JSONObject data)throws Exception{this.issuer=issuer;this.plan=plan;this.data=data.toString();digest=capabilityFingerprint(data);}
        public JSONObject view()throws Exception{return new JSONObject(data).put("digest",digest);}
        public TeamWorkRevisionSource source(){return plan.source();}
    }
    public synchronized TeamWorkRevisionSource teamWorkRevisionSource(TeamWorkWindow window,dev.pocketengine.features.teams.TeamCoordinator.WorkMutationSource original,String itemId,int itemRevision,int ownerGeneration,String text)throws Exception {
        if(window==null||window.issuer!=this||original==null||text==null||text.length()>4000||text.codePoints().allMatch(Character::isWhitespace))throw new SecurityException("请在原前台工作项填写明确修订要求");
        return new TeamWorkRevisionSource(this,window,original,itemId,itemRevision,ownerGeneration,text,teamWorkRevisionFacts(window,original,itemId,itemRevision,ownerGeneration));
    }
    private synchronized void checkTeamWorkRevisionSource(TeamWorkRevisionSource source)throws Exception {
        if(source==null||source.issuer!=this||source.expiresAt<=System.currentTimeMillis()||source.pageGeneration!=source.window.pageGeneration)throw new SecurityException("原修订审阅已离开前台、到期或不是本窗口");source.window.require();source.nativeSource.checkCurrent();
        JSONObject now=teamWorkRevisionFacts(source.window,source.nativeSource,source.itemId,source.itemRevision,source.ownerGeneration);if(!canonical(now).equals(canonical(new JSONObject(source.facts))))throw new SecurityException("原工作项、已整合成果或实际父预算已变化，请重新审阅");
    }
    private JSONObject teamWorkRevisionFacts(TeamWorkWindow window,dev.pocketengine.features.teams.TeamCoordinator.WorkMutationSource original,String itemId,int itemRevision,int ownerGeneration)throws Exception {
        window.require();original.checkCurrent();JSONObject team=teamWriteTask(window.teamRun),parent=teamWorkPlanParent(window.teamRun),plan=team.getJSONObject("teamWorkPlan"),nativeRun=original.view(),scope=team.getJSONObject("teamWriteScope");
        if(!window.teamRun.equals(nativeRun.getString("id"))||!plan.getString("planId").equals(nativeRun.getJSONObject("workPlan").getString("planId"))||plan.getInt("revision")!=nativeRun.getJSONObject("workPlan").getInt("revision")||!canonical(scope).equals(canonical(nativeRun.getJSONObject("productionScope")))||team.optBoolean("cancelRequested")||team.optString("status").matches("completed|stopping|cancelled")||plan.getLong("expiresAt")<=System.currentTimeMillis())throw new SecurityException("不是当前未停止的原工作计划、scope及成果");
        for(String key:new String[]{"parentTaskId","parentRunId","parentEpoch","targetProjectId"})if(!canonical(plan.get(key)).equals(canonical(parent.get(key))))throw new SecurityException("原工作计划的父授权或epoch已改变");
        JSONArray items=plan.getJSONArray("items");JSONObject target=dev.pocketengine.features.teams.TeamWorkItems.find(items,itemId);currentTeamWorkItem(team,target);if(target.getInt("itemRevision")!=itemRevision||target.getInt("ownerGeneration")!=ownerGeneration)throw new SecurityException("原工作项修订或负责人已经变化");
        if(scope.has("projectSelection"))throw new SecurityException("完整项目V2须重新审阅全基线与命令/源码范围；本片不借旧文件修订沿用授权");
        JSONObject integration=teamIntegrationContext(window.teamRun,new RunControl());if(integration==null||integration.optBoolean("activeAttempt")||integration.optBoolean("hasUnresolved")||!integration.optString("status").equals("completed"))throw new SecurityException("原唯一整合者尚未确定完成；未知写入不能继续、改派或退款");
        JSONArray assignments=teamAssignmentsForReview(window.teamRun,new RunControl()),affected=dev.pocketengine.features.teams.TeamWorkItems.affected(items,itemId),prepared=new JSONArray();
        for(int i=0;i<items.length();i++){JSONObject item=items.getJSONObject(i),child=required(item.getString("assignmentId"));currentTeamWorkItem(team,item);if(!item.optString("status").equals("completed")||!child.optString("status").equals("completed")||child.optBoolean("cancelRequested")||hasUnresolved(child.getString("id"))||teamHasPendingQuestion(child))throw new SecurityException("原工作项尚未取得无未知效果的真实冻结终态");try(Cursor running=db.rawQuery("SELECT 1 FROM runs WHERE task_id=? AND status='running' LIMIT 1",new String[]{child.getString("id")})){if(running.moveToFirst())throw new SecurityException("原作者执行尚未结束");}}
        int files=0,dependencyReads=0,allFiles=0;for(int i=0;i<items.length();i++)allFiles+=items.getJSONObject(i).getJSONArray("files").length();
        for(int i=0;i<affected.length();i++){
            JSONObject item=affected.getJSONObject(i),assignment=null;for(int a=0;a<assignments.length();a++)if(item.getString("assignmentId").equals(assignments.getJSONObject(a).getString("assignmentId")))assignment=assignments.getJSONObject(a);if(assignment==null||!assignment.optString("status").equals("completed"))throw new SecurityException("原工作项没有当前assignment的真实冻结贡献");
            JSONObject child=required(item.getString("assignmentId")),identity=child.getJSONObject("teamAssignment");if(identity.has("remoteSelection")||identity.optInt("assignmentRevision",1)>=8)throw new SecurityException("本次沿原成果继续只接本机作者的有界修订；远端新job需另审准确基线");JSONArray refs=assignment.getJSONArray("artifacts"),heads=new JSONArray();String frozenRun="";
            if(refs.length()!=item.getJSONArray("files").length())throw new SecurityException("原工作项贡献未完整覆盖其所选文件");
            for(int f=0;f<refs.length();f++){JSONObject ref=refs.getJSONObject(f),applied=teamAppliedArtifact(integration,ref);if(applied==null)throw new SecurityException("原唯一整合回执未覆盖当前工作项精确冻结版本");if(!frozenRun.isEmpty()&&!frozenRun.equals(ref.getString("runId")))throw new SecurityException("同一工作项冻结成果混合执行代次");frozenRun=ref.getString("runId");heads.put(new JSONObject().put("path",ref.getString("path")).put("version",applied.getInt("version")).put("sha256",applied.getString("sha256")).put("revisionId",applied.getString("revisionId")).put("appliedReceipt",applied));}
            JSONObject entry=dev.pocketengine.features.teams.TeamWorkItems.exactIdentity(item).put("files",new JSONArray(item.getJSONArray("files").toString())).put("appliedHeads",heads).put("frozenRunId",frozenRun).put("artifactsHash",capabilityFingerprint(new JSONObject().put("artifacts",refs))).put("assignmentRevision",identity.optInt("assignmentRevision",1)).put("artifacts",new JSONArray(refs.toString()));prepared.put(entry);files+=refs.length();JSONArray deps=item.getJSONArray("dependsOn");for(int d=0;d<deps.length();d++)dependencyReads+=dev.pocketengine.features.teams.TeamWorkItems.find(items,deps.getString(d)).getJSONArray("files").length();
        }
        JSONObject owner=teamBudgetOwner(team),pool=teamBudget(owner),budget=dev.pocketengine.features.teams.TeamParentBudget.snapshot(pool);int leastTools=3*files+2*prepared.length()+allFiles+3+dependencyReads,margin=Math.min(2,Math.max(0,32-budget.getInt("toolOperationsReservedOrUsed")-leastTools)),additionalModels=prepared.length()*3+files*5+6+dependencyReads+(allFiles-files);
        int proposedTools=budget.getInt("toolOperationsReservedOrUsed")+leastTools+margin,proposedModels=budget.getInt("modelRequestsReservedOrUsed")+additionalModels;if(proposedTools>32||proposedModels>64||parent.getInt("remainingOperations")<leastTools)throw new IllegalStateException("原累计父预算不足以完成本次受影响工作项、全量独审及剩余唯一整合；不清零旧用量或暗加父授权，请缩小本次修订或另行处理原父预算");proposedTools=Math.min(proposedTools,budget.getInt("toolOperationsReservedOrUsed")+parent.getInt("remainingOperations"));
        return new JSONObject().put("kind","work_revision").put("teamRunId",window.teamRun).put("workItemId",itemId).put("workPlan",new JSONObject(plan.toString())).put("originalScope",new JSONObject(scope.toString())).put("integration",integration).put("affected",prepared).put("parent",parent).put("parentBudget",budget)
                .put("proposedModelRequestLimit",Math.max(pool.getInt("modelRequestLimit"),proposedModels)).put("proposedToolOperationLimit",Math.max(pool.getInt("toolOperationLimit"),proposedTools)).put("budgetPlan",new JSONObject().put("selectedFileToolSteps",leastTools).put("toolRetryMargin",margin).put("dependencyArtifactReads",dependencyReads).put("retainedFilesToReview",allFiles-files).put("estimatedAdditionalModelRequests",additionalModels).put("modelRetryMargin",1).put("modelEstimateNotGuarantee",true).put("autoRefill",false).put("tokenCostKnown",false)).put("originalProjectWriteAuthorized",false);
    }
    /** Original SQL receipts only. Retained files may have been applied by an earlier exact review. */
    private JSONObject teamAppliedArtifact(JSONObject integration,JSONObject ref)throws Exception {
        JSONObject found=null;JSONArray receipts=integration.getJSONArray("receipts");for(int r=0;r<receipts.length();r++){JSONObject receipt=receipts.getJSONObject(r),applied=receipt.optJSONObject("teamIntegrated");if(!receipt.optBoolean("ok")||applied==null||!integration.getString("taskId").equals(applied.optString("integrationTaskId"))||!integration.getString("targetProjectId").equals(applied.optString("projectId")))continue;boolean same=true;java.util.Iterator<String> keys=ref.keys();while(keys.hasNext()){String key=keys.next();if(!canonical(ref.get(key)).equals(canonical(applied.opt(key)))){same=false;break;}}if(same){if(found!=null&&!canonical(found).equals(canonical(applied)))throw new SecurityException("同一冻结成果出现多个不一致原整合回执，请先核对");found=new JSONObject(applied.toString());}}return found;
    }
    public synchronized TeamWorkRevisionReview prepareTeamWorkRevision(dev.pocketengine.features.teams.TeamWorkItemRevision.Plan nativePlan)throws Exception {if(nativePlan==null||nativePlan.source().issuer!=this)throw new SecurityException("不是原TaskStore签发的成果修订来源");nativePlan.checkCurrent();return new TeamWorkRevisionReview(this,nativePlan,nativePlan.view());}
    public synchronized JSONObject approveTeamWorkRevision(TeamWorkRevisionReview review,String digest)throws Exception {
        if(review==null||review.issuer!=this||review.consumed||!review.digest.equals(digest))throw new SecurityException("请批准本窗口刚审阅的准确成果修订");review.plan.checkCurrent();db.beginTransaction();try{
            JSONObject shown=new JSONObject(review.data),current=review.plan.view();if(!canonical(shown).equals(canonical(current)))throw new SecurityException("原成果修订scope已变");TeamWorkRevisionSource source=review.plan.source();source.checkCurrent();JSONObject team=teamWriteTask(source.window.teamRun),workPlan=team.getJSONObject("teamWorkPlan"),scope=team.getJSONObject("teamWriteScope");JSONArray affected=shown.getJSONArray("affected"),ids=new JSONArray();java.util.ArrayList<dev.pocketengine.features.teams.TeamWriteScope> preparedScopes=new java.util.ArrayList<>();
            // Precompute all private native scopes before changing any SQL revision; no partial source recapture.
            for(int i=0;i<affected.length();i++){String id=affected.getJSONObject(i).getString("workItemId");dev.pocketengine.features.teams.TeamWriteScope nextScope=review.plan.scopeForItem(scope,id);scope=nextScope.snapshot();preparedScopes.add(nextScope);ids.put(id);}source.checkCurrent();
            for(int i=0;i<affected.length();i++){JSONObject item=affected.getJSONObject(i);JSONObject input=new JSONObject().put("runId",source.window.teamRun).put("assignmentId",item.getString("assignmentId")).put("memberId",item.getString("memberId")).put("workItemId",item.getString("workItemId")).put("revisionRequestId",source.requestId).put("text",source.text).put("expectedFrozenRunId",item.getString("frozenRunId")).put("expectedArtifactsHash",item.getString("artifactsHash"));reviseTeamAssignment(input,preparedScopes.get(i));}
            team=teamWriteTask(source.window.teamRun);workPlan=team.getJSONObject("teamWorkPlan");JSONObject finalScope=team.getJSONObject("teamWriteScope"),parent=shown.getJSONObject("parent");String scopeHash=capabilityFingerprint(finalScope);JSONArray items=workPlan.getJSONArray("items");
            for(int i=0;i<affected.length();i++){JSONObject selected=affected.getJSONObject(i),item=dev.pocketengine.features.teams.TeamWorkItems.find(items,selected.getString("workItemId")),child=required(item.getString("assignmentId")),identity=child.getJSONObject("teamAssignment");JSONArray history=item.optJSONArray("revisionHistory");if(history==null)history=new JSONArray();history.put(new JSONObject().put("workItem",dev.pocketengine.features.teams.TeamWorkItems.exactIdentity(item)).put("assignmentRevision",selected.getInt("assignmentRevision")).put("artifacts",selected.getJSONArray("artifacts")).put("integrationReviewId",shown.getJSONObject("integration").getString("reviewId")).put("planHash",shown.getJSONObject("integration").getString("planHash")).put("revisionRequestId",source.requestId));item.put("revisionHistory",history).put("itemRevision",Math.addExact(item.getInt("itemRevision"),1)).put("files",selected.getJSONArray("newFiles")).put("status","queued").put("artifacts",new JSONArray());item.remove("assignmentTask");item.put("revisionInstruction",source.text);
                identity.put("itemRevision",item.getInt("itemRevision")).put("ownerGeneration",item.getInt("ownerGeneration"));child.put("teamAssignment",identity).put("status","queued");write(child);
                item.put("draftScope",new JSONObject().put("grantId",UUID.randomUUID().toString()).put("state","active").put("workItemId",item.getString("workItemId")).put("expiresAt",workPlan.getLong("expiresAt")).put("remainingOperations",item.getJSONArray("files").length()+3).put("operationIds",new JSONArray()).put("planHash",scopeHash).put("parentRunId",parent.getString("parentRunId")).put("parentEpoch",parent.getInt("parentEpoch")));
            }
            JSONObject owner=teamBudgetOwner(team),pool=teamBudget(owner);pool.put("modelRequestLimit",shown.getInt("proposedModelRequestLimit")).put("toolOperationLimit",shown.getInt("proposedToolOperationLimit"));pool.getJSONObject("budgetSource").put("workItemRevisionOwnerReviewDigest",digest).put("workItemRevisionBudgetPlan",shown.getJSONObject("budgetPlan"));owner.put("teamReservationPool",pool);workPlan.put("revision",workPlan.getInt("revision")+1).put("lastRevisionRequestId",source.requestId).put("lastRevisionReviewDigest",digest);team.put("teamWorkPlan",workPlan).put("status","queued");source.window.require();write(owner);write(team);db.setTransactionSuccessful();review.consumed=true;return new JSONObject().put("workPlan",workPlan).put("scope",finalScope).put("parentBudget",dev.pocketengine.features.teams.TeamParentBudget.snapshot(pool)).put("affectedWorkItemIds",ids).put("oldReviewStale",true);
        }finally{db.endTransaction();}
    }
    /** Exact foreground handoff review. Only definitely unstarted work may move in this first local slice. */
    public synchronized TeamWorkReview prepareTeamWorkTransfer(TeamWorkWindow window,String itemId,String nextMember,int itemRevision,int ownerGeneration)throws Exception {
        if(window==null||window.issuer!=this)throw new SecurityException("不是原工作项窗口");window.require();JSONObject team=teamWriteTask(window.teamRun),plan=team.getJSONObject("teamWorkPlan"),item=dev.pocketengine.features.teams.TeamWorkItems.find(plan.getJSONArray("items"),itemId);teamWorkPlanParent(window.teamRun);currentTeamWorkItem(team,item);checkUnstartedWorkItem(item);
        if(item.getInt("itemRevision")!=itemRevision||item.getInt("ownerGeneration")!=ownerGeneration||item.getString("memberId").equals(nextMember)||plan.getLong("expiresAt")<=System.currentTimeMillis()||!plan.optString("state").equals("active"))throw new SecurityException("工作项修订/负责人已变，或原scope到期");
        JSONObject source=plan.getJSONObject("memberSelection"),selected=dev.pocketengine.features.teams.TeamWorkPlan.find(source.getJSONArray("members"),nextMember);if(!selected.optString("runner","model").equals("model")||!selected.optString("device").equals("phone"))throw new SecurityException("本次只可交接给原本机模型成员");
        JSONObject data=new JSONObject().put("kind","transfer").put("planId",plan.getString("planId")).put("planRevision",plan.getInt("revision")).put("workItem",dev.pocketengine.features.teams.TeamWorkItems.exactIdentity(item)).put("newMemberId",nextMember).put("files",item.getJSONArray("files")).put("dependsOn",item.getJSONArray("dependsOn")).put("parentBudget",teamBudgetSnapshot(window.teamRun)).put("budgetChanged",false).put("expiresAt",Math.min(plan.getLong("expiresAt"),System.currentTimeMillis()+120_000));return new TeamWorkReview(this,window,null,data);
    }
    private void checkUnstartedWorkItem(JSONObject item)throws Exception {
        if(item.has("parentUnstartedContinuation"))throw new SecurityException("原未开始项已有单次继续预留；先等待原派发/停止确证，不把预留挪到新owner");
        JSONObject child=read(item.getString("assignmentId"));if(child==null)return;
        if(!child.optString("runId").isEmpty()||child.optBoolean("cancelRequested")||child.optString("status").matches("running|preparing|stopping|completed")||hasUnresolved(child.getString("id"))||get(child.getString("id"),false).getJSONArray("operations").length()!=0)throw new SecurityException("原作者已开始、停止未核对或有未知效果；先回原assignment核对，不能改派、退款或重放");
    }
    public synchronized JSONObject approveTeamWorkTransfer(TeamWorkReview review,String digest)throws Exception {
        if(review==null||review.issuer!=this||review.consumed||review.plan!=null||!review.digest.equals(digest))throw new SecurityException("不是本次精确原生交接审阅");review.window.require();if(review.pageGeneration!=review.window.pageGeneration)throw new SecurityException("原前台审阅已离开，请重新核对");db.beginTransaction();try{JSONObject shown=new JSONObject(review.data);if(!shown.optString("kind").equals("transfer"))throw new SecurityException("不是原交接审阅");JSONObject team=teamWriteTask(review.window.teamRun),plan=team.getJSONObject("teamWorkPlan"),expected=shown.getJSONObject("workItem"),item=dev.pocketengine.features.teams.TeamWorkItems.find(plan.getJSONArray("items"),expected.getString("workItemId"));teamWorkPlanParent(review.window.teamRun);
            if(!shown.getString("planId").equals(plan.getString("planId"))||shown.getInt("planRevision")!=plan.getInt("revision")||shown.getLong("expiresAt")<=System.currentTimeMillis()||!dev.pocketengine.features.teams.TeamWorkItems.sameIdentity(item,expected))throw new SecurityException("交接目标已变化，请重新审阅");currentTeamWorkItem(team,item);checkUnstartedWorkItem(item);
            item.getJSONArray("assignmentHistory").put(new JSONObject(dev.pocketengine.features.teams.TeamWorkItems.exactIdentity(item).toString()).put("status",item.optString("status")).put("endedAt",System.currentTimeMillis()).put("reason","native_owner_unstarted_handoff"));item.put("memberId",shown.getString("newMemberId")).put("assignmentId",UUID.randomUUID().toString()).put("ownerGeneration",item.getInt("ownerGeneration")+1).put("itemRevision",item.getInt("itemRevision")+1).put("status","queued").put("artifacts",new JSONArray());item.getJSONObject("draftScope").put("grantId",UUID.randomUUID().toString());plan.put("revision",plan.getInt("revision")+1);team.put("teamWorkPlan",plan);if(team.optString("status").matches("awaiting_user|paused"))team.put("status","queued");review.window.require();write(team);db.setTransactionSuccessful();review.consumed=true;return teamWorkPlanSnapshot(review.window.teamRun);
        }finally{db.endTransaction();}
    }
    public synchronized TeamWorkReview prepareTeamWorkContinuation(TeamWorkWindow window,String itemId,int itemRevision,int ownerGeneration)throws Exception {
        if(window==null||window.issuer!=this)throw new SecurityException("不是原主人工作项窗口");window.require();JSONObject team=teamWriteTask(window.teamRun),plan=team.getJSONObject("teamWorkPlan"),item=dev.pocketengine.features.teams.TeamWorkItems.find(plan.getJSONArray("items"),itemId),child=required(item.getString("assignmentId"));currentTeamWorkItem(team,new JSONObject(item.toString()).put("assignmentId",child.getString("id")));teamWorkPlanParent(window.teamRun);
        if(item.getInt("itemRevision")!=itemRevision||item.getInt("ownerGeneration")!=ownerGeneration||hasUnresolved(child.getString("id"))||teamHasPendingQuestion(child)||!child.optString("status").matches("paused|awaiting_user|needs_confirmation|interrupted|failed|cancelled"))throw new SecurityException("原工作项未处于确定安全等待点；未知操作或未回答问题不能重放");
        JSONObject queued=child.optJSONObject("teamDraftContinuation");if(queued!=null&&!queued.has("runId"))throw new SecurityException("原继续已登记但尚未实际启动；请刷新核对原队列，不重复预留或替换继续身份");JSONObject budget=teamBudgetSnapshot(window.teamRun);if(budget.getInt("remainingModelRequests")<1||budget.getInt("remainingToolOperations")<1)throw new SecurityException("原父预算已用尽，此继续不追加额度");return new TeamWorkReview(this,window,null,new JSONObject().put("kind","continue").put("planId",plan.getString("planId")).put("workItem",dev.pocketengine.features.teams.TeamWorkItems.exactIdentity(item)).put("runId",child.getString("runId")).put("epoch",child.getInt("coordinatorEpoch")).put("parentBudget",budget).put("budgetChanged",false).put("expiresAt",Math.min(plan.getLong("expiresAt"),System.currentTimeMillis()+120_000)));
    }
    public synchronized JSONObject approveTeamWorkContinuation(TeamWorkReview review,String digest,int runGeneration,int memberGeneration)throws Exception {
        if(review==null||review.issuer!=this||review.consumed||review.plan!=null||!review.digest.equals(digest))throw new SecurityException("不是本次精确原工作项继续审阅");review.window.require();if(review.pageGeneration!=review.window.pageGeneration)throw new SecurityException("原前台审阅已离开，请重新核对");JSONObject shown=new JSONObject(review.data);if(!shown.optString("kind").equals("continue"))throw new SecurityException("不是原工作项继续");db.beginTransaction();try{JSONObject team=teamWriteTask(review.window.teamRun),item=currentTeamWorkItem(team,shown.getJSONObject("workItem")),child=required(item.getString("assignmentId")),owner=teamBudgetOwner(team),pool=teamBudget(owner);teamWorkPlanParent(review.window.teamRun);
            if(!shown.getString("runId").equals(child.getString("runId"))||shown.getInt("epoch")!=child.getInt("coordinatorEpoch")||shown.getLong("expiresAt")<=System.currentTimeMillis()||hasUnresolved(child.getString("id"))||teamHasPendingQuestion(child)||!child.optString("status").matches("paused|awaiting_user|needs_confirmation|interrupted|failed|cancelled"))throw new SecurityException("原child/epoch等待点已变化或效果未知");
            JSONObject previousContinuation=child.optJSONObject("teamDraftContinuation");if(previousContinuation!=null&&!previousContinuation.has("runId"))throw new SecurityException("原工作项已有未消费继续claim，不覆盖或重复预留");JSONObject binding=new JSONObject().put("teamRunId",review.window.teamRun).put("runGeneration",runGeneration).put("memberGeneration",memberGeneration).put("childTaskId",child.getString("id")).put("childRunId",child.getString("runId")).put("childEpoch",child.getInt("coordinatorEpoch")).put("memberId",item.getString("memberId")).put("assignmentId",child.getString("id"));JSONObject reservation=dev.pocketengine.features.teams.TeamParentBudget.reserve(pool,binding,"member","work_continue:"+digest,1,0);child.put("teamDraftContinuation",new JSONObject().put("reservationId",reservation.getString("reservationId")).put("workItemId",item.getString("workItemId")).put("runGeneration",runGeneration).put("memberGeneration",memberGeneration));item.put("status","queued");team.put("status","queued");owner.put("teamReservationPool",pool);review.window.require();write(owner);write(child);write(team);db.setTransactionSuccessful();review.consumed=true;return teamWorkPlanSnapshot(review.window.teamRun);
        }finally{db.endTransaction();}
    }
    public synchronized JSONObject teamWorkPlanSnapshot(String teamRun)throws Exception {JSONObject team=teamWriteTask(teamRun);requirePrivateOwner(team);JSONObject plan=team.optJSONObject("teamWorkPlan");if(plan==null)return new JSONObject().put("state","no_work_plan").put("executionAuthorized",false);JSONObject result=new JSONObject(plan.toString());JSONArray items=result.getJSONArray("items");for(int i=0;i<items.length();i++){JSONObject item=items.getJSONObject(i);item.put("blockedBy",dev.pocketengine.features.teams.TeamWorkItems.blockers(items,item));JSONObject child=read(item.getString("assignmentId"));if(child!=null){if(item.optString("status").equals("running")&&!child.optString("status").matches("preparing|running"))item.put("status",hasUnresolved(child.getString("id"))?"blocked_unknown":teamHasPendingQuestion(child)?"awaiting_input":child.optString("status"));item.put("assignmentTask",new JSONObject().put("assignmentId",item.getString("assignmentId")).put("taskId",child.getString("id")).put("memberId",item.getString("memberId")).put("runId",child.optString("runId")).put("status",hasUnresolved(child.getString("id"))?"blocked_unknown":teamHasPendingQuestion(child)?"awaiting_input":child.optString("status")).put("questions",child.optJSONArray("teamQuestions")==null?new JSONArray():child.getJSONArray("teamQuestions")).put("messages",child.optJSONArray("teamConversationInputs")==null?new JSONArray():child.getJSONArray("teamConversationInputs")).put("artifacts",item.getJSONArray("artifacts")));}}return new JSONObject().put("workPlan",result).put("parentBudget",teamBudgetSnapshot(teamRun));}
    private JSONObject currentTeamWorkItem(JSONObject team,JSONObject identity)throws Exception {
        if(identity.optString("workItemId").isEmpty())return null;if(team.optBoolean("cancelRequested")||team.optString("status").matches("stopping|cancelled|completed"))throw new SecurityException("原工作项团队已停止或由主人确认完成");JSONObject plan=team.getJSONObject("teamWorkPlan"),item=dev.pocketengine.features.teams.TeamWorkItems.find(plan.getJSONArray("items"),identity.getString("workItemId"));if(!plan.optString("state").equals("active")||plan.getLong("expiresAt")<=System.currentTimeMillis()||!dev.pocketengine.features.teams.TeamWorkItems.sameIdentity(item,identity))throw new SecurityException("旧工作项/负责人generation不能写入新assignment成果");return item;
    }
    public synchronized JSONObject finishTeamWorkItem(dev.pocketengine.features.teams.TeamCoordinator.AssignmentCall call)throws Exception {
        if(call.workItemId.isEmpty())throw new SecurityException("没有原工作项");JSONObject team=teamWriteTask(call.teamRunId),item=currentTeamWorkItem(team,call.workItem()),task=required(call.assignmentId);JSONObject identity=task.getJSONObject("teamAssignment");teamAssignmentIdentity(new JSONObject(identity.toString()).put("taskId",call.assignmentId));
        JSONArray original=teamAssignmentsForReview(call.teamRunId,new RunControl()),artifacts=null;for(int i=0;i<original.length();i++)if(call.assignmentId.equals(original.getJSONObject(i).getString("assignmentId")))artifacts=original.getJSONObject(i).getJSONArray("artifacts");if(task.optString("status").equals("completed")&&(artifacts==null||artifacts.length()==0))throw new SecurityException("原Runner完成状态没有真实冻结贡献");JSONObject actual=inspectTeamAssignment(call);item.put("status",actual.getString("status")).put("assignmentTask",actual).put("taskId",call.assignmentId).put("artifacts",artifacts==null?new JSONArray():artifacts);team.put("teamWorkPlan",team.getJSONObject("teamWorkPlan"));write(team);return teamWorkPlanSnapshot(call.teamRunId);
    }
    /** Foreground native review only; not a model tool, grant JSON or replacement execution control. */
    public final class TeamDraftWindow implements AutoCloseable {
        private final TaskStore issuer=TaskStore.this;private final String teamRunId;private volatile boolean alive=true,foreground;
        private TeamDraftWindow(String run){teamRunId=run;}
        public void setForeground(boolean value){foreground=alive&&value;}
        private void require(){if(!alive||!foreground)throw new SecurityException("需要当前前台主人窗口审阅隔离草稿范围");}
        @Override public void close(){alive=false;foreground=false;}
    }
    public static final class TeamDraftReview {
        private final TaskStore issuer;private final TeamDraftWindow window;private final dev.pocketengine.features.teams.TeamCoordinator.DraftPlan plan;
        private final String data,digest;private boolean consumed;
        private TeamDraftReview(TaskStore issuer,TeamDraftWindow window,dev.pocketengine.features.teams.TeamCoordinator.DraftPlan plan,JSONObject data)throws Exception{this.issuer=issuer;this.window=window;this.plan=plan;this.data=data.toString();digest=capabilityFingerprint(data);}
        public JSONObject view()throws Exception{return new JSONObject(data).put("digest",digest);}
    }
    public static final class TeamDraftApproval {
        private final TaskStore issuer;private final String data;
        private TeamDraftApproval(TaskStore issuer,JSONObject data){this.issuer=issuer;this.data=data.toString();}
        public JSONObject view()throws Exception{return new JSONObject(data);}
    }
    public synchronized TeamDraftWindow openTeamDraftWindow(String teamRunId)throws Exception{teamWriteTask(teamRunId);return new TeamDraftWindow(teamRunId);}
    public synchronized JSONObject teamDraftScopeSnapshot(String teamRunId)throws Exception {JSONObject grant=teamWriteTask(teamRunId).optJSONObject("teamDraftScope");return grant==null?new JSONObject().put("state","not_authorized"):new JSONObject(grant.toString());}
    public synchronized JSONObject revokeTeamDraftScope(TeamDraftWindow window,String expectedGrantId)throws Exception {if(window==null||window.issuer!=this)throw new SecurityException("不是原主人窗口");window.require();JSONObject team=teamWriteTask(window.teamRunId),grant=team.optJSONObject("teamDraftScope");if(grant==null||!expectedGrantId.equals(grant.optString("grantId")))throw new SecurityException("隔离草稿授权已变化，请重新读取");grant.put("state","revoked").put("revokedAt",System.currentTimeMillis());team.put("teamDraftScope",grant);window.require();write(team);return new JSONObject(grant.toString()).put("alreadyDispatchedEffectsRolledBack",false);}
    public synchronized TeamDraftReview prepareTeamDraftScope(TeamDraftWindow window,dev.pocketengine.features.teams.TeamCoordinator.DraftPlan plan)throws Exception {
        if(window==null||window.issuer!=this)throw new SecurityException("不是原主人窗口");window.require();plan.checkCurrent();JSONObject data=teamDraftFacts(window.teamRunId,plan.view());plan.checkCurrent();window.require();return new TeamDraftReview(this,window,plan,data);
    }
    private JSONObject teamDraftFacts(String teamRunId,JSONObject nativePlan)throws Exception {
        JSONObject team=teamWriteTask(teamRunId),scope=team.getJSONObject("teamWriteScope");checkTeamIntegrationParents(team);
        if(!teamRunId.equals(nativePlan.getString("id"))||!"produce".equals(nativePlan.optString("mode"))||!canonical(scope).equals(canonical(nativePlan.getJSONObject("productionScope"))))throw new SecurityException("隔离草稿不是原团队项目计划");
        JSONObject owner=teamBudgetOwner(team),pool=teamBudget(owner),parentGrant=grant(scope.getString("parentRunId"));long now=System.currentTimeMillis();
        if(!scope.getString("parentRunId").equals(owner.optString("runId"))||parentGrant==null||parentGrant.optLong("expiresAt")<=now||owner.optBoolean("cancelRequested")||owner.optString("status").matches("paused|pausing|interrupted|stopping|cancelled|completed"))throw new SecurityException("原父授权已经暂停、停止或到期");
        JSONArray targets=new JSONArray(),members=nativePlan.getJSONArray("members"),assignments=scope.getJSONArray("assignments");
        for(int i=0;i<members.length();i++){JSONObject member=members.getJSONObject(i),selected=null;for(int s=0;s<assignments.length();s++)if(member.getString("id").equals(assignments.getJSONObject(s).getString("memberId")))selected=assignments.getJSONObject(s);
            if(selected==null||!member.optString("runner","model").equals("model")||!"phone".equals(member.opt("device")))continue;
            String assignment=member.getJSONArray("roundStates").getJSONObject(0).getString("assignmentId");if(!assignment.matches("[a-f0-9-]{36}"))throw new SecurityException("原分工ID无效");JSONObject task=read(assignment);int revision=1;
            if(task!=null){requirePrivateOwner(task);JSONObject identity=task.getJSONObject("teamAssignment");if(!assignment.equals(identity.getString("assignmentId"))||!teamRunId.equals(identity.getString("teamRunId"))||!member.getString("id").equals(identity.getString("memberId"))||!canonical(selected.getJSONArray("files")).equals(canonical(identity.getJSONArray("files"))))throw new SecurityException("原成员分工范围已变化");if(hasUnresolved(assignment))throw new SecurityException("原成员还有未知操作，只可核对原操作");revision=identity.optInt("assignmentRevision",1);}
            targets.put(new JSONObject().put("assignmentId",assignment).put("assignmentRevision",revision).put("memberId",member.getString("id")).put("memberName",member.optString("name")).put("memberGeneration",member.getInt("generation")).put("files",new JSONArray(selected.getJSONArray("files").toString())));
        }
        if(targets.length()==0)throw new SecurityException("没有可授权的原本机隔离草稿分工");
        int files=0;for(int i=0;i<targets.length();i++)files+=targets.getJSONObject(i).getJSONArray("files").length();int membersCount=targets.length();
        // Visible planning, not a model-meter inferred from stepCeiling. A file may need more than one edit.
        int minimumDraftOperations=2*membersCount+files,minimumOperations=minimumDraftOperations+files;
        int estimatedModels=3*membersCount+4*files+6,margin=membersCount;
        JSONObject usage=dev.pocketengine.features.teams.TeamParentBudget.snapshot(pool);int modelUsed=usage.getInt("modelRequestsReservedOrUsed"),toolUsed=usage.getInt("toolOperationsReservedOrUsed"),parentRemaining=parentGrant.getInt("remainingOperations");
        // 64/32 are the pre-existing coordinator's explicit owner-plan ceilings, never fresh defaults.
        int modelLimit=Math.min(64,Math.max(nativePlan.getInt("callLimit"),modelUsed+estimatedModels+margin));
        int toolLimit=Math.min(32,Math.min(toolUsed+parentRemaining,Math.max(nativePlan.getInt("toolCallLimit"),toolUsed+minimumOperations+margin)));
        int draftOperations=Math.min(minimumDraftOperations+margin,Math.max(0,toolLimit-toolUsed-files));
        return new JSONObject().put("teamRunId",teamRunId).put("parentTaskId",owner.getString("id")).put("parentRunId",scope.getString("parentRunId")).put("parentEpoch",owner.getInt("coordinatorEpoch")).put("targetProjectId",scope.getString("targetProjectId")).put("planHash",capabilityFingerprint(scope)).put("runGeneration",nativePlan.getInt("generation")).put("nativeRevision",nativePlan.getLong("revision")).put("targets",targets).put("tools",new JSONArray().put("team_prepare_draft").put("team_write_file").put("team_freeze_outputs")).put("maxToolOperations",draftOperations).put("remainingModelRequests",dev.pocketengine.features.teams.TeamParentBudget.remaining(pool,"modelRequests")).put("minimumDraftOperations",minimumDraftOperations).put("minimumToolOperationsIncludingIntegration",minimumOperations).put("estimatedModelRequests",estimatedModels).put("explicitRetryMargin",margin).put("parentRemainingOperations",parentRemaining).put("previousModelRequestLimit",pool.getInt("modelRequestLimit")).put("previousToolOperationLimit",pool.getInt("toolOperationLimit")).put("proposedModelRequestLimit",modelLimit).put("proposedToolOperationLimit",toolLimit).put("canCompleteMinimumPlan",toolLimit-toolUsed>=minimumOperations&&modelLimit-modelUsed>=estimatedModels).put("expiresAt",parentGrant.getLong("expiresAt")).put("originalProjectWriteAuthorized",false).put("budgetChangeRequiresThisOwnerApproval",true).put("tokenCostLimitsKnown",false);
    }
    public synchronized TeamDraftApproval approveTeamDraftScope(TeamDraftReview review,String acceptedDigest)throws Exception {
        if(review==null||review.issuer!=this||review.consumed||!review.digest.equals(acceptedDigest))throw new SecurityException("需要原生当前隔离草稿范围审阅");review.window.require();review.plan.checkCurrent();db.beginTransaction();try{
            JSONObject current=teamDraftFacts(review.window.teamRunId,review.plan.view());if(!review.digest.equals(capabilityFingerprint(current)))throw new SecurityException("分工、期限或实际剩余预算已变化，请重新审阅");
            if(!current.getBoolean("canCompleteMinimumPlan"))throw new SecurityException("原父上限不足以完成最低准备、编辑、冻结与整合计划；未开始必然中断的制作，请先调整原父预算");
            JSONObject grant=new JSONObject(current.toString()).put("grantId",UUID.randomUUID().toString()).put("state","active").put("approvedAt",System.currentTimeMillis()).put("remainingOperations",current.getInt("maxToolOperations")).put("operationIds",new JSONArray());
            JSONObject team=teamWriteTask(current.getString("teamRunId")),owner=teamBudgetOwner(team),pool=teamBudget(owner);
            pool.put("modelRequestLimit",current.getInt("proposedModelRequestLimit")).put("toolOperationLimit",current.getInt("proposedToolOperationLimit"));pool.getJSONObject("budgetSource").put("lastNativeDraftReview",new JSONObject().put("grantId",grant.getString("grantId")).put("digest",review.digest).put("nativeRevision",current.getLong("nativeRevision")).put("modelRequestLimit",current.getInt("proposedModelRequestLimit")).put("toolOperationLimit",current.getInt("proposedToolOperationLimit")).put("parentRemainingOperations",current.getInt("parentRemainingOperations")));owner.put("teamReservationPool",pool);write(owner);
            team.put("teamDraftScope",grant);write(team);JSONArray targets=current.getJSONArray("targets");
            for(int i=0;i<targets.length();i++){JSONObject target=targets.getJSONObject(i),task=read(target.getString("assignmentId"));if(task==null)continue;JSONArray approvals=task.optJSONArray("approvals");for(int a=0;approvals!=null&&a<approvals.length();a++){JSONObject approval=approvals.getJSONObject(a);if(approval.optString("tool").matches("team_(prepare_draft|write_file|freeze_outputs)")&&approval.optString("state").matches("pending|approved"))approval.put("state","invalidated").put("reason","native_draft_scope_approved");}appendEvent(task,new JSONObject().put("type","team_draft_scope_approved").put("grantId",grant.getString("grantId")).put("assignmentRevision",target.getInt("assignmentRevision")));}
            review.window.require();review.plan.checkCurrent();review.consumed=true;db.setTransactionSuccessful();return new TeamDraftApproval(this,grant);
        }finally{db.endTransaction();}
    }
    public synchronized JSONObject claimTeamDraftContinuation(TeamDraftApproval approval,JSONObject target)throws Exception {
        if(approval==null||approval.issuer!=this)throw new SecurityException("缺少原生隔离草稿范围批准");JSONObject approved=approval.view();db.beginTransaction();try{
            JSONObject team=teamWriteTask(approved.getString("teamRunId")),grant=team.optJSONObject("teamDraftScope"),task=required(target.getString("assignmentId"));
            if(grant==null||!grant.getString("grantId").equals(approved.getString("grantId"))||!grant.optString("state").equals("active")||grant.getLong("expiresAt")<=System.currentTimeMillis())throw new SecurityException("原隔离草稿授权已过期或撤销");
            JSONObject identity=task.getJSONObject("teamAssignment"),selected=teamDraftTarget(grant,identity,target.getInt("runGeneration"),target.getInt("memberGeneration"));
            if(selected==null||!task.optString("status").matches("awaiting_user|needs_confirmation|cancelled")||task.optBoolean("cancelRequested")||teamHasPendingQuestion(task)||hasUnresolved(task.getString("id")))throw new SecurityException("原分工尚非安全继续点");
            JSONObject prior=task.optJSONObject("teamDraftContinuation");if(prior!=null&&grant.getString("grantId").equals(prior.optString("grantId"))){db.setTransactionSuccessful();return new JSONObject().put("claimed",false);}
            JSONObject owner=teamBudgetOwner(team),pool=teamBudget(owner);checkTeamIntegrationParents(team);JSONObject parentGrant=grant(grant.getString("parentRunId"));if(!owner.optString("runId").equals(grant.getString("parentRunId"))||parentGrant==null||parentGrant.optLong("expiresAt")<=System.currentTimeMillis())throw new SecurityException("原父执行已变化或到期");
            JSONObject bound=new JSONObject().put("teamRunId",target.getString("teamRunId")).put("runGeneration",target.getInt("runGeneration")).put("childTaskId",task.getString("id")).put("childRunId",task.getString("runId")).put("childEpoch",task.getInt("coordinatorEpoch")).put("memberGeneration",target.getInt("memberGeneration")).put("memberId",identity.getString("memberId")).put("assignmentId",task.getString("id"));
            JSONObject reservation=dev.pocketengine.features.teams.TeamParentBudget.reserve(pool,bound,"member","draft:"+grant.getString("grantId")+":"+task.getString("id"),1,0);task.put("teamDraftContinuation",new JSONObject().put("grantId",grant.getString("grantId")).put("reservationId",reservation.getString("reservationId")));write(task);owner.put("teamReservationPool",pool);write(owner);db.setTransactionSuccessful();return new JSONObject().put("claimed",true).put("reservationId",reservation.getString("reservationId"));
        }finally{db.endTransaction();}
    }
    private JSONObject teamDraftTarget(JSONObject grant,JSONObject identity,int runGeneration,int memberGeneration)throws Exception {
        if(!grant.optString("state").equals("active")||grant.getLong("expiresAt")<=System.currentTimeMillis()||grant.getInt("runGeneration")!=runGeneration)return null;JSONArray targets=grant.getJSONArray("targets");for(int i=0;i<targets.length();i++){JSONObject target=targets.getJSONObject(i);if(identity.getString("assignmentId").equals(target.getString("assignmentId"))&&identity.getString("memberId").equals(target.getString("memberId"))&&identity.optInt("assignmentRevision",1)==target.getInt("assignmentRevision")&&target.getInt("memberGeneration")==memberGeneration&&canonical(identity.getJSONArray("files")).equals(canonical(target.getJSONArray("files"))))return target;}return null;
    }
    private JSONObject teamDraftGrant(JSONObject binding,String tool,JSONObject args,JSONObject capability)throws Exception {
        if(!tool.matches("team_(prepare_draft|write_file|freeze_outputs)"))return null;JSONObject team=teamWriteTask(binding.getString("teamRunId")),identity=teamAssignmentIdentity(binding).getJSONObject("teamAssignment"),item=currentTeamWorkItem(team,identity),grant=item==null?team.optJSONObject("teamDraftScope"):item.getJSONObject("draftScope");if(grant==null)return null;
        JSONObject owner=teamBudgetOwner(team);if((item==null?teamDraftTarget(grant,identity,binding.getInt("runGeneration"),binding.getInt("memberGeneration"))==null:!grant.optString("state").equals("active")||grant.optLong("expiresAt")<=System.currentTimeMillis())||grant.optInt("remainingOperations")<1||!grant.getString("planHash").equals(capabilityFingerprint(team.getJSONObject("teamWriteScope")))||!grant.getString("parentRunId").equals(owner.optString("runId"))||grant.getInt("parentEpoch")!=owner.optInt("coordinatorEpoch"))return null;
        if(tool.equals("team_prepare_draft")){if(!canonical(identity.getJSONArray("files")).equals(canonical(capability.getJSONArray("files"))))throw new SecurityException("隔离草稿准备范围不符");}
        else {JSONObject draft=null;JSONArray receipts=teamAssignmentReceipts(binding,"team_prepare_draft");for(int i=0;i<receipts.length();i++)if(receipts.getJSONObject(i).optJSONObject("teamDraft")!=null)draft=receipts.getJSONObject(i).getJSONObject("teamDraft");if(draft==null||draft.getString("draftProjectId").equals(identity.getString("targetProjectId"))||!draft.getString("draftProjectId").equals(capability.optString("draftProjectId")))throw new SecurityException("只允许原assignment实际登记的独立草稿，不允许写原项目");
            if(tool.equals("team_write_file")){boolean path=false;JSONArray files=identity.getJSONArray("files");for(int i=0;i<files.length();i++)path|=args.getString("path").equals(files.getJSONObject(i).getString("path"));if(!path||args.getInt("expectedVersion")!=capability.getInt("previousVersion")||!dev.pocketengine.features.teams.TeamWorkFiles.hash(args.getString("content")).equals(capability.getString("desiredHash")))throw new SecurityException("隔离草稿文件或精确版本不符");}
            else {JSONArray selected=capability.getJSONArray("files"),allowed=identity.getJSONArray("files");for(int i=0;i<selected.length();i++){JSONObject file=selected.getJSONObject(i);boolean found=false;for(int a=0;a<allowed.length();a++){JSONObject base=allowed.getJSONObject(a);if(base.getString("path").equals(file.getString("path"))&&base.getInt("baseVersion")==file.getInt("baseVersion")&&base.getString("baseHash").equals(file.getString("baseHash")))found=true;}if(!found)throw new SecurityException("冻结成果不属于原scope的准确文件与基线");}}
        }return grant;
    }
    /** Owner-only fixed remote V2 source. Defensive JSON is evidence, never a restored dispatch capability. */
    public static final class TeamRemoteRevisionSource {
        private final TaskStore issuer;private final TeamRemoteOwnerWindow window;private final long windowRevision,expiresAt;
        private final dev.pocketengine.features.teams.TeamCoordinator.DraftPlan nativePlan;private final String request,facts;
        private TeamRemoteRevisionSource(TaskStore issuer,TeamRemoteOwnerWindow window,dev.pocketengine.features.teams.TeamCoordinator.DraftPlan nativePlan,JSONObject request,JSONObject facts)throws Exception{this.issuer=issuer;this.window=window;this.nativePlan=nativePlan;this.request=request.toString();this.facts=facts.toString();windowRevision=window.current();expiresAt=Math.min(facts.getJSONObject("parent").getLong("expiresAt"),System.currentTimeMillis()+120_000L);}
        public JSONObject view()throws Exception{return new JSONObject(facts).put("request",new JSONObject(request)).put("expiresAt",expiresAt);}
        public void checkCurrent()throws Exception{issuer.checkTeamRemoteRevisionSource(this);}
    }
    public static final class TeamRemoteRevisionReview {
        private final TaskStore issuer;private final TeamRemoteRevisionSource source;private final dev.pocketengine.features.teams.TeamWriteScope scope;
        private final String value,digest;private boolean consumed;
        private TeamRemoteRevisionReview(TaskStore issuer,TeamRemoteRevisionSource source,dev.pocketengine.features.teams.TeamWriteScope scope,JSONObject value)throws Exception{this.issuer=issuer;this.source=source;this.scope=scope;this.value=value.toString();digest=capabilityFingerprint(value);}
        public JSONObject view()throws Exception{return new JSONObject(value).put("digest",digest);}
        public TeamRemoteRevisionSource source(){return source;}
    }
    public synchronized TeamRemoteRevisionSource reviewTeamRemoteRevision(TeamRemoteOwnerWindow window,dev.pocketengine.features.teams.TeamCoordinator.DraftPlan nativePlan,JSONObject exactRequest)throws Exception {
        if(window==null||window.issuer!=this||nativePlan==null)throw new SecurityException("需要原前台主人及真实团队计划");window.current();nativePlan.checkCurrent();
        String text=exactRequest.getString("text");if(text.length()>4000||text.codePoints().allMatch(Character::isWhitespace)||!exactRequest.getString("revisionRequestId").matches("[a-f0-9-]{36}"))throw new SecurityException("新修订要求无效");
        JSONObject facts=teamRemoteRevisionFacts(nativePlan,exactRequest);return new TeamRemoteRevisionSource(this,window,nativePlan,new JSONObject(exactRequest.toString()),facts);
    }
    private synchronized void checkTeamRemoteRevisionSource(TeamRemoteRevisionSource source)throws Exception {
        if(source==null||source.issuer!=this||source.window.current()!=source.windowRevision||System.currentTimeMillis()>=source.expiresAt)throw new SecurityException("原远端成果审阅已离开前台或到期");source.nativePlan.checkCurrent();
        if(!canonical(new JSONObject(source.facts)).equals(canonical(teamRemoteRevisionFacts(source.nativePlan,new JSONObject(source.request)))))throw new SecurityException("原远端成果、父执行或整合版本已变化，请重新审阅");
    }
    private JSONObject teamRemoteRevisionFacts(dev.pocketengine.features.teams.TeamCoordinator.DraftPlan nativePlan,JSONObject request)throws Exception {
        nativePlan.checkCurrent();String run=request.getString("runId"),assignmentId=request.getString("assignmentId");JSONObject nativeRun=nativePlan.view(),team=teamWriteTask(run),task=get(assignmentId,false),parent=teamWorkPlanParent(run);checkTeamIntegrationParents(team);
        if(task==null||!run.equals(nativeRun.getString("id"))||team.optJSONObject("teamWorkPlan")!=null||nativeRun.optJSONObject("workPlan")!=null||!canonical(nativeRun.getJSONObject("productionScope")).equals(canonical(team.getJSONObject("teamWriteScope"))))throw new SecurityException("本入口仅支持原固定分工，不能借成员最近任务修订动态工作项");
        requirePrivateOwner(task);JSONObject identity=task.getJSONObject("teamAssignment"),origin=task.getJSONObject("origin");if(!origin.optString("kind").equals("team_assignment")||!run.equals(identity.getString("teamRunId"))||!assignmentId.equals(identity.getString("assignmentId"))||!request.getString("memberId").equals(identity.getString("memberId"))||!identity.has("remoteSelection")||task.optBoolean("cancelRequested")||!task.optString("status").equals("completed")||hasUnresolved(assignmentId)||teamHasPendingQuestion(task))throw new SecurityException("原远端作者未确定完成或不是此分工");
        try(Cursor active=db.rawQuery("SELECT 1 FROM runs WHERE task_id=? AND status='running' LIMIT 1",new String[]{assignmentId})){if(active.moveToFirst())throw new SecurityException("原成员执行尚未退出");}checkTeamRemoteScopeReplacement(assignmentId);
        JSONObject integration=teamIntegrationContext(run,new RunControl());if(integration==null||integration.optBoolean("activeAttempt")||integration.optBoolean("hasUnresolved")||!integration.optString("status").equals("completed")||!request.getString("expectedIntegrationReviewId").equals(integration.getString("reviewId"))||!request.getString("expectedIntegrationPlanHash").equals(integration.getString("planHash")))throw new SecurityException("请选择原已完成的唯一整合精确版本");
        JSONArray refs=null,assignments=teamAssignmentsForReview(run,new RunControl());for(int i=0;i<assignments.length();i++)if(assignmentId.equals(assignments.getJSONObject(i).getString("assignmentId")))refs=assignments.getJSONObject(i).getJSONArray("artifacts");
        if(refs==null||refs.length()==0||!request.getString("expectedArtifactsHash").equals(capabilityFingerprint(new JSONObject().put("artifacts",refs))))throw new SecurityException("原冻结成果已变化");
        String childId=null,snapshotId=null,snapshotVersion=null;JSONArray heads=new JSONArray();JSONObject oldSelection=identity.getJSONObject("remoteSelection");
        for(int i=0;i<refs.length();i++){JSONObject ref=refs.getJSONObject(i),remote=ref.getJSONObject("remoteContribution");if(!request.getString("expectedFrozenRunId").equals(ref.getString("runId"))||ref.optInt("assignmentRevision",1)!=identity.optInt("assignmentRevision",1)||teamAppliedArtifact(integration,ref)==null)throw new SecurityException("原远端冻结成果未被此次唯一整合完整接收");
            if(childId==null){childId=remote.getString("remoteChildTaskId");snapshotId=remote.getString("contributionId");snapshotVersion=remote.getString("snapshotVersion");}else if(!childId.equals(remote.getString("remoteChildTaskId"))||!snapshotId.equals(remote.getString("contributionId"))||!snapshotVersion.equals(remote.getString("snapshotVersion")))throw new SecurityException("原贡献不能混合多个job或快照");}
        JSONObject child=required(childId),remoteOrigin=child.getJSONObject("origin"),tuple=remoteOrigin.getJSONObject("context").getJSONObject("nativeAssignment");if(!remoteOrigin.optString("kind").equals("team_remote_assignment")||!assignmentId.equals(tuple.getString("assignmentId"))||tuple.getInt("assignmentRevision")!=identity.optInt("assignmentRevision",1)||!run.equals(tuple.getString("teamRunId")))throw new SecurityException("V1贡献不是原SQL登记的作者child");
        JSONObject snapshot=remoteResultSnapshot(childId,snapshotId);if(!snapshotVersion.equals(snapshot.getString("snapshotVersion"))||!snapshot.has("assignmentContribution"))throw new SecurityException("V1实际冻结贡献缺失");JSONArray files=identity.getJSONArray("files"),snapshotFiles=snapshot.getJSONArray("files");if(files.length()!=snapshotFiles.length())throw new SecurityException("贡献未覆盖原所选文件");
        for(int i=0;i<files.length();i++){JSONObject base=files.getJSONObject(i);int version=base.getInt("baseVersion");String hash=base.getString("baseHash"),path=base.getString("path");for(int r=0;r<refs.length();r++)if(path.equals(refs.getJSONObject(r).getString("path"))){JSONObject applied=teamAppliedArtifact(integration,refs.getJSONObject(r));version=applied.getInt("version");hash=applied.getString("sha256");}boolean exact=false;for(int f=0;f<snapshotFiles.length();f++){JSONObject actual=snapshotFiles.getJSONObject(f);if(path.equals(actual.getString("path"))&&hash.equals(actual.getString("sha256")))exact=true;}if(!exact)throw new SecurityException("原贡献字节不是原已整合版本");heads.put(new JSONObject().put("path",path).put("baseVersion",version).put("baseHash",hash));}
        return new JSONObject().put("teamRunId",run).put("assignmentId",assignmentId).put("assignmentRevision",identity.optInt("assignmentRevision",1)).put("memberId",identity.getString("memberId")).put("runGeneration",nativeRun.getInt("generation")).put("originalScope",team.getJSONObject("teamWriteScope")).put("originalSelection",oldSelection).put("integration",integration).put("artifacts",refs).put("appliedHeads",heads).put("sourceTaskId",childId).put("snapshotId",snapshotId).put("snapshotVersion",snapshotVersion).put("snapshot",snapshot).put("parent",parent).put("executionAuthorized",false);
    }
    public synchronized TeamRemoteRevisionReview prepareTeamRemoteRevision(TeamRemoteRevisionSource source,dev.pocketengine.features.remote.RemoteAssignmentWorkspaces.Selection selected,dev.pocketengine.features.teams.TeamWriteScope scope)throws Exception {
        source.checkCurrent();JSONObject facts=source.view(),selection=selected.view(),next=scope.snapshot(),previous=facts.getJSONObject("originalScope");
        if(!facts.getString("sourceTaskId").equals(selection.getString("sourceTaskId"))||!facts.getString("snapshotId").equals(selection.getString("snapshotId"))||!facts.getString("snapshotVersion").equals(selection.getString("snapshotVersion"))||!canonical(facts.getJSONArray("appliedHeads")).equals(canonical(selection.getJSONArray("files"))))throw new SecurityException("新选择不是V1原child贡献与手机准确head");
        JSONObject oldSelection=facts.getJSONObject("originalSelection");for(String key:new String[]{"profileId","serviceBase","environmentId","limits","worker","model"})if(!canonical(oldSelection.opt(key)).equals(canonical(selection.opt(key))))throw new SecurityException("修订不能悄悄换连接、环境、模型或限制");
        JSONObject restored=new JSONObject(next.toString());restored.remove("outcomeContinuation");JSONObject original=new JSONObject(previous.toString());original.remove("outcomeContinuation");JSONArray before=original.getJSONArray("assignments"),after=restored.getJSONArray("assignments");if(before.length()!=after.length())throw new SecurityException("不能增删原分工");boolean found=false;
        for(int i=0;i<before.length();i++){JSONObject a=before.getJSONObject(i),b=after.getJSONObject(i);if(facts.getString("memberId").equals(a.getString("memberId"))){if(!canonical(selection.getJSONArray("files")).equals(canonical(b.getJSONArray("files")))||!canonical(selection).equals(canonical(b.getJSONObject("remoteSelection"))))throw new SecurityException("新scope不是私构选择");b.put("files",a.getJSONArray("files")).put("remoteSelection",a.getJSONObject("remoteSelection"));found=true;}}
        if(!found||!canonical(original).equals(canonical(restored)))throw new SecurityException("修订改变了原分工之外的scope");JSONObject continuation=next.getJSONObject("outcomeContinuation");for(String key:new String[]{"memberId","reviewId","planHash"}){String expected=key.equals("memberId")?facts.getString(key):facts.getJSONObject("integration").getString(key);if(!expected.equals(continuation.getString(key)))throw new SecurityException("新scope没有原整合版本");}
        JSONObject parent=required(facts.getJSONObject("parent").getString("parentTaskId")),pool=teamBudget(parent),budget=dev.pocketengine.features.teams.TeamParentBudget.snapshot(pool);int allFiles=facts.getJSONObject("integration").getJSONObject("binding").getJSONObject("plan").getJSONArray("files").length(),tools=8+2*allFiles,models=12+3*allFiles;
        int proposedTools=Math.max(pool.getInt("toolOperationLimit"),budget.getInt("toolOperationsReservedOrUsed")+tools),proposedModels=Math.max(pool.getInt("modelRequestLimit"),budget.getInt("modelRequestsReservedOrUsed")+models);
        if(proposedTools>32||proposedModels>64||facts.getJSONObject("parent").getInt("remainingOperations")<tools||dev.pocketengine.features.teams.TeamParentBudget.remaining(pool,"externalJobs")<1)throw new IllegalStateException("原累计父工具/模型/独立job剩余不足本次V2完整调度、全量独审与整合计划；不清零旧账或自动续额");
        JSONObject value=new JSONObject().put("source",facts).put("scope",next).put("parentBudget",budget).put("proposedModelRequestLimit",proposedModels).put("proposedToolOperationLimit",proposedTools).put("budgetPlan",new JSONObject().put("estimatedToolOperations",tools).put("estimatedModelRequests",models).put("includesFullIndependentReviewAndUniqueIntegration",true).put("explicitRetryMargin",1).put("strictMinimum",false).put("autoRefill",false)).put("needsRemoteScope",true).put("executionAuthorized",false);return new TeamRemoteRevisionReview(this,source,scope,value);
    }
    public synchronized JSONObject approveTeamRemoteRevision(TeamRemoteRevisionReview review,String digest,int approvedModelLimit,int approvedToolLimit)throws Exception {
        if(review==null||review.issuer!=this||review.consumed||!review.digest.equals(digest))throw new SecurityException("需要本窗口刚显示的精确远端修订审阅");db.beginTransaction();try{
            review.source.checkCurrent();JSONObject shown=review.view(),facts=review.source.view(),team=teamWriteTask(facts.getString("teamRunId")),parent=teamBudgetOwner(team),pool=teamBudget(parent);if(!canonical(shown.getJSONObject("parentBudget")).equals(canonical(dev.pocketengine.features.teams.TeamParentBudget.snapshot(pool)))||approvedModelLimit!=shown.getInt("proposedModelRequestLimit")||approvedToolLimit!=shown.getInt("proposedToolOperationLimit"))throw new SecurityException("原父预算或本次明确批准值已变化");
            reviseTeamAssignment(new JSONObject(review.source.request),review.scope,true);JSONObject task=required(facts.getString("assignmentId"));JSONObject next=review.scope.snapshot();JSONArray assignments=next.getJSONArray("assignments");for(int i=0;i<assignments.length();i++)if(facts.getString("memberId").equals(assignments.getJSONObject(i).getString("memberId")))task.getJSONObject("teamAssignment").put("remoteSelection",assignments.getJSONObject(i).getJSONObject("remoteSelection"));
            pool.put("modelRequestLimit",approvedModelLimit).put("toolOperationLimit",approvedToolLimit);pool.getJSONObject("budgetSource").put("remoteRevisionOwnerDigest",digest).put("remoteRevisionBudgetPlan",shown.getJSONObject("budgetPlan"));parent.put("teamReservationPool",pool);write(parent);
            JSONObject receipt=new JSONObject().put("teamRunId",facts.getString("teamRunId")).put("assignmentId",facts.getString("assignmentId")).put("memberId",facts.getString("memberId")).put("scope",next).put("scopeHash",capabilityFingerprint(next)).put("previousScopeHash",capabilityFingerprint(facts.getJSONObject("originalScope"))).put("runGeneration",facts.getInt("runGeneration")).put("revisionRequestId",new JSONObject(review.source.request).getString("revisionRequestId")).put("revisionText",new JSONObject(review.source.request).getString("text")).put("revisedAssignment",new JSONObject(task.getJSONObject("teamAssignment").toString())).put("approvedAt",System.currentTimeMillis()).put("ownerReviewDigest",digest).put("proposedModelRequestLimit",approvedModelLimit).put("proposedToolOperationLimit",approvedToolLimit).put("parentBudget",dev.pocketengine.features.teams.TeamParentBudget.snapshot(pool)).put("needsRemoteScope",true).put("executionAuthorized",false);
            task.put("teamRemoteRevision",receipt);write(task);db.setTransactionSuccessful();review.consumed=true;return new JSONObject(receipt.toString());
        }finally{db.endTransaction();}
    }
    /** Read-only crash recovery of an already-approved scope. Never restores a control, grant or job. */
    public synchronized JSONObject originalTeamRemoteRevision(String assignmentId)throws Exception {
        JSONObject task=required(assignmentId);requirePrivateOwner(task);JSONObject identity=task.getJSONObject("teamAssignment"),record=task.optJSONObject("teamRemoteRevision");if(record==null)return new JSONObject().put("recorded",false);JSONObject result=new JSONObject(record.toString()),scope=teamWriteTask(identity.getString("teamRunId")).getJSONObject("teamWriteScope");
        return result.put("recorded",true).put("currentScopeMatches",result.getString("scopeHash").equals(capabilityFingerprint(scope))).put("currentAssignmentRevision",identity.optInt("assignmentRevision",1)).put("taskStatus",task.optString("status")).put("cancelRequested",task.optBoolean("cancelRequested")).put("executionAuthorized",false);
    }
    public static final class TeamRemoteRevisionRecord {
        private final TaskStore issuer;private final TeamRemoteOwnerWindow window;private final long windowRevision;private final String assignment,value;
        private TeamRemoteRevisionRecord(TaskStore issuer,TeamRemoteOwnerWindow window,String assignment,JSONObject value){this.issuer=issuer;this.window=window;this.assignment=assignment;this.value=value.toString();windowRevision=window.current();}
        public JSONObject view()throws Exception{return new JSONObject(value);}
        public void checkCurrent()throws Exception{issuer.checkTeamRemoteRevisionRecord(this);}
    }
    public synchronized TeamRemoteRevisionRecord teamRemoteRevisionRecord(TeamRemoteOwnerWindow window,String assignment)throws Exception {
        if(window==null||window.issuer!=this)throw new SecurityException("不是原主人窗口");TeamRemoteRevisionRecord record=new TeamRemoteRevisionRecord(this,window,assignment,originalTeamRemoteRevision(assignment));checkTeamRemoteRevisionRecord(record);return record;
    }
    private synchronized void checkTeamRemoteRevisionRecord(TeamRemoteRevisionRecord record)throws Exception {
        if(record==null||record.issuer!=this||record.window.current()!=record.windowRevision)throw new SecurityException("原修订同步窗口已失效");JSONObject now=originalTeamRemoteRevision(record.assignment);if(!canonical(now).equals(canonical(new JSONObject(record.value)))||!now.optBoolean("recorded")||!now.optBoolean("currentScopeMatches")||now.optBoolean("cancelRequested")||!now.optString("taskStatus").equals("awaiting_user")||now.getJSONObject("revisedAssignment").getInt("assignmentRevision")!=now.getInt("currentAssignmentRevision"))throw new SecurityException("原修订已执行、改变或停止，不能重置成等待");checkTeamIntegrationParents(teamWriteTask(now.getString("teamRunId")));if(hasUnresolved(record.assignment))throw new SecurityException("原分工有未知效果，不能同步继续");try(Cursor active=db.rawQuery("SELECT 1 FROM runs WHERE task_id=? AND status='running' LIMIT 1",new String[]{record.assignment})){if(active.moveToFirst())throw new SecurityException("原分工仍在执行");}
    }
    public enum TeamRemoteAction { PREPARE, SUBMIT, CANCEL, FREEZE, RECLAIM, IMPORT, APPROVE }
    public enum TeamRemoteDisposition { DISPATCH, QUERY_ONLY, AWAITING_APPROVAL }
    public enum TeamRemoteOwnerDecision { APPROVE_ONCE, REJECT }
    /** Views are defensive data. Only this original SQL authority can construct these capabilities. */
    public static final class TeamRemoteContext {
        private final TaskStore issuer;private final String snapshot,binding;private final RunControl original;
        private final dev.pocketengine.features.teams.TeamCoordinator.AssignmentCall assignment;
        private TeamRemoteContext(TaskStore issuer,JSONObject value,JSONObject binding,RunControl original,dev.pocketengine.features.teams.TeamCoordinator.AssignmentCall assignment){this.issuer=issuer;snapshot=value.toString();this.binding=binding.toString();this.original=original;this.assignment=assignment;}
        public JSONObject view()throws Exception{return new JSONObject(snapshot);}
    }
    public static final class TeamRemoteClaim {
        private final TaskStore issuer;private final TeamRemoteContext context;private final TeamRemoteAction action;private final TeamRemoteDisposition disposition;
        private final String snapshot,claimToken;private final TeamRemoteOwnerReview ownerReview;
        private TeamRemoteClaim(TaskStore issuer,TeamRemoteContext context,TeamRemoteAction action,TeamRemoteDisposition disposition,JSONObject value,String token,TeamRemoteOwnerReview ownerReview){this.issuer=issuer;this.context=context;this.action=action;this.disposition=disposition;snapshot=value.toString();claimToken=token;this.ownerReview=ownerReview;}
        public JSONObject view()throws Exception{return new JSONObject(snapshot).put("action",action.name()).put("disposition",disposition.name().toLowerCase(java.util.Locale.ROOT));}
        public boolean canDispatch(){return disposition==TeamRemoteDisposition.DISPATCH;}
    }
    public static final class TeamRemoteOwnerWindow implements AutoCloseable {
        private final TaskStore issuer;private boolean foreground,closed;private long revision;
        private TeamRemoteOwnerWindow(TaskStore issuer){this.issuer=issuer;}
        public synchronized void setForeground(boolean value){if(closed)throw new SecurityException("原主人窗口已关闭");if(foreground!=value){foreground=value;revision++;}}
        public synchronized void close(){closed=true;foreground=false;revision++;}
        private synchronized long current(){if(closed||!foreground)throw new SecurityException("原主人窗口不在前台");return revision;}
    }
    public static final class TeamRemoteOwnerReview {
        private final TaskStore issuer;private final TeamRemoteOwnerWindow window;private final long windowRevision,expiresAt;private final String snapshot;
        private TeamRemoteOwnerReview(TaskStore issuer,TeamRemoteOwnerWindow window,JSONObject value,long expiresAt){this.issuer=issuer;this.window=window;windowRevision=window.current();snapshot=value.toString();this.expiresAt=expiresAt;}
        public JSONObject view()throws Exception{return new JSONObject(snapshot);}
    }
    public static final class TeamRemoteScopeRequired extends SecurityException { public TeamRemoteScopeRequired(String message){super(message);} }
    public static final class TeamRemoteScopeReview {
        private final TaskStore issuer;private final TeamRemoteOwnerWindow window;private final long windowRevision,expiresAt;private final String snapshot;private String approvedScopeId;
        private TeamRemoteScopeReview(TaskStore issuer,TeamRemoteOwnerWindow window,JSONObject snapshot,long expiry){this.issuer=issuer;this.window=window;windowRevision=window.current();expiresAt=expiry;this.snapshot=snapshot.toString();}
        public JSONObject view()throws Exception {JSONObject view=new JSONObject(snapshot);return view.put("digest",capabilityFingerprint(view));}
    }
    /** A native owner scope is per stable assignment/revision, not a resurrected child RunControl. */
    private JSONObject teamRemoteScopeCandidate(String assignmentId)throws Exception {
        JSONObject task=required(assignmentId);requirePrivateOwner(task);rejectChannelParent(task);JSONObject identity=task.getJSONObject("teamAssignment");if(!task.getJSONObject("origin").optString("kind").equals("team_assignment"))throw new SecurityException("不是原制作分工");JSONObject team=teamWriteTask(identity.getString("teamRunId")),scope=team.getJSONObject("teamWriteScope"),parent=teamBudgetOwner(team);checkTeamIntegrationParents(team);
        if(!scope.getString("parentRunId").equals(parent.optString("runId"))||task.optBoolean("cancelRequested")||task.optString("status").matches("completed|cancelled|stopping"))throw new SecurityException("原任务已停止或parent run已改变");JSONObject selection=identity.getJSONObject("remoteSelection"),pool=teamBudget(parent),grant=grant(scope.getString("parentRunId"));if(grant.optLong("expiresAt")<=System.currentTimeMillis())throw new SecurityException("原父执行授权已到期");
        JSONObject candidate=new JSONObject().put("assignmentId",assignmentId).put("assignmentRevision",identity.optInt("assignmentRevision",1)).put("memberId",identity.getString("memberId")).put("teamRunId",identity.getString("teamRunId")).put("teamPlanHash",scope.getString("definitionHash")).put("parentTaskId",parent.getString("id")).put("parentRunId",scope.getString("parentRunId")).put("parentEpoch",parent.getInt("coordinatorEpoch")).put("targetProjectId",identity.getString("targetProjectId"))
                .put("remoteSelection",new JSONObject(selection.toString())).put("files",new JSONArray(identity.getJSONArray("files").toString())).put("parentGrantExpiresAt",grant.getLong("expiresAt")).put("externalBudget",new JSONObject(scope.getJSONObject("externalBudget").toString()));
        JSONObject teamEvidence=team.getJSONObject("evidence");candidate.put("runGeneration",teamEvidence.getInt("generation")).put("waitingRunId",task.getString("runId")).put("waitingEpoch",task.getInt("coordinatorEpoch"));JSONArray members=teamEvidence.getJSONArray("nativeMemberGenerations");boolean found=false;for(int m=0;m<members.length();m++){JSONObject member=members.getJSONObject(m);if(identity.getString("memberId").equals(member.getString("memberId"))){candidate.put("memberGeneration",member.getInt("generation"));found=true;}}if(!found)throw new SecurityException("原成员没有真实native generation回执");
        validateTeamRemoteBase(new JSONObject(selection.toString()).put("files",candidate.getJSONArray("files")),remoteResultSnapshot(selection.getString("sourceTaskId"),selection.getString("snapshotId")));return candidate;
    }
    /** Re-signing only: never revoke a still-live/unknown original job by swapping its scope id. */
    private void checkTeamRemoteScopeReplacement(String assignmentId)throws Exception {
        try(Cursor rows=db.rawQuery("SELECT state,receipt FROM operations WHERE task_id=? AND tool='team_submit_remote_job'",new String[]{assignmentId})){while(rows.moveToNext()){
            if(rows.isNull(1)){if(!rows.getString(0).equals("prepared"))throw new SecurityException("原SUBMIT尚无可核对receipt，不能重签新job scope");continue;}JSONObject record=new JSONObject(rows.getString(1)).optJSONObject("teamRemote");if(record==null)throw new SecurityException("原SUBMIT缺少native来源，先核对原操作");JSONObject actual=record.optJSONObject("actualReceipt");
            if(actual==null||!actual.optString("status").matches("completed|failed|cancelled|timed_out|blocked")||!actual.optBoolean("dispatchFenceFinal"))throw new SecurityException("原远端job仍在执行或结果未知；先查询/停止原job，不能重签新scope绕过额度");
            if(actual.optBoolean("processStarted")){JSONObject cleanup=actual.optJSONObject("processCleanupProof");if(cleanup==null||!cleanup.optBoolean("processGroupStoppedVerified"))throw new SecurityException("原进程组停止尚未确认，不能重签新job scope");}else if(!actual.optBoolean("notStartedConfirmed"))throw new SecurityException("原job没有最终未启动证明，不能重签新job scope");
        }}
    }
    /** An owner-visible local scheduling estimate, not a strict minimum or a promise for arbitrary coding tasks. */
    private static JSONObject teamRemoteLocalDispatcherPlan()throws Exception {
        JSONArray stages=new JSONArray();
        for(String stage:new String[]{"prepare","submit","status_or_owner_approval_continuation","freeze","import","finalization"})
            stages.put(new JSONObject().put("stage",stage).put("estimatedModelRequests",1));
        return new JSONObject().put("unit","local_dispatcher_model_request").put("basis","selected_file_remote_flow_plan_not_guarantee")
                .put("estimatedAdditionalRequests",7).put("plannedFlowRequests",6).put("explicitRetryMargin",1).put("stages",stages)
                .put("strictMinimum",false).put("autoRefill",false).put("moreComplexTaskGuaranteed",false).put("remoteInternalInferenceExcluded",true)
                .put("notice","本次所选文件远端流程的本机调度估计：准备、提交、状态/主人审批续接、冻结、导入、最终收尾各计划一回合，另明示一回合重试余量；不是任意任务严格下限或足额保证。仅主人确认后调整原父上限，真实耗尽仍停止，不自动补额；远端job内部模型次数/计费另属未知。");
    }
    public synchronized TeamRemoteScopeReview reviewTeamRemoteScope(TeamRemoteOwnerWindow window,String assignmentId)throws Exception {
        if(window==null||window.issuer!=this)throw new SecurityException("没有原native前台owner窗口");window.current();checkTeamRemoteScopeReplacement(assignmentId);JSONObject candidate=teamRemoteScopeCandidate(assignmentId),parent=required(candidate.getString("parentTaskId")),pool=teamBudget(parent);JSONObject external=candidate.getJSONObject("externalBudget");int remaining=pool.has("externalJobLimit")?dev.pocketengine.features.teams.TeamParentBudget.remaining(pool,"externalJobs"):Math.max(0,external.getInt("externalTurnLimit")-external.getInt("externalTurnsUsed"));candidate.put("proposedScopeExpiresAt",Math.min(candidate.getLong("parentGrantExpiresAt"),System.currentTimeMillis()+900_000L)).put("parentBudget",dev.pocketengine.features.teams.TeamParentBudget.snapshot(pool)).put("parentRemainingOperations",grant(candidate.getString("parentRunId")).getInt("remainingOperations")).put("minimumAdditionalToolOperations",5).put("localModelBudgetPlan",teamRemoteLocalDispatcherPlan()).put("proposedParentModelLimit",Math.min(64,pool.getInt("modelRequestLimit")+7)).put("proposedParentToolLimit",Math.min(32,pool.getInt("toolOperationLimit")+5)).put("remainingParentExternalJobs",remaining).put("maximumAssignmentJobs",Math.min(1,remaining)).put("internalInferenceBillingUnbounded",true).put("modelRequestsHardCapSupported",false).put("tokenHardCapSupported",false).put("costHardCapSupported",false).put("fullProject",false).put("osReadIsolationVerified",false).put("commandApprovalEscalationSupported",false).put("commandAllowlistEnforced",false).put("scopeRevision",Math.addExact(required(assignmentId).optInt("teamRemoteScopeRevision",0),1));return new TeamRemoteScopeReview(this,window,candidate,System.currentTimeMillis()+60_000L);
    }
    public synchronized JSONObject approveTeamRemoteScope(TeamRemoteScopeReview review,String acceptedDigest,int approvedJobs)throws Exception {return approveTeamRemoteScope(review,acceptedDigest,approvedJobs,review.view().getJSONObject("parentBudget").getInt("toolOperationLimit"));}
    public synchronized JSONObject approveTeamRemoteScope(TeamRemoteScopeReview review,String acceptedDigest,int approvedJobs,int approvedParentToolLimit)throws Exception {return approveTeamRemoteScope(review,acceptedDigest,approvedJobs,approvedParentToolLimit,review.view().getJSONObject("parentBudget").getInt("modelRequestLimit"));}
    public synchronized JSONObject approveTeamRemoteScope(TeamRemoteScopeReview review,String acceptedDigest,int approvedJobs,int approvedParentToolLimit,int approvedParentModelLimit)throws Exception {
        db.beginTransaction();try{
            if(review==null||review.issuer!=this||review.window.current()!=review.windowRevision||System.currentTimeMillis()>=review.expiresAt)throw new SecurityException("原remote scope窗口已关闭、后台或到期");JSONObject shown=review.view();if(!acceptedDigest.equals(shown.getString("digest"))||approvedJobs<1||approvedJobs>shown.getInt("maximumAssignmentJobs"))throw new SecurityException("主人须确认本次所选文件/独立job额度与未知内部计费");checkTeamRemoteScopeReplacement(shown.getString("assignmentId"));JSONObject candidate=teamRemoteScopeCandidate(shown.getString("assignmentId"));for(String key:new String[]{"assignmentId","assignmentRevision","memberId","teamRunId","teamPlanHash","parentTaskId","parentRunId","parentEpoch","targetProjectId","remoteSelection","files","externalBudget","parentGrantExpiresAt","runGeneration","memberGeneration","waitingRunId","waitingEpoch"})if(!canonical(shown.get(key)).equals(canonical(candidate.get(key))))throw new SecurityException("原scope或父授权已改变，旧review未批准");JSONObject task=required(shown.getString("assignmentId"));if(shown.getInt("scopeRevision")!=task.optInt("teamRemoteScopeRevision",0)+1)throw new SecurityException("原scope审批版本已变化");
            JSONObject parent=required(shown.getString("parentTaskId")),pool=teamBudget(parent);JSONObject budget=dev.pocketengine.features.teams.TeamParentBudget.snapshot(pool);if(!canonical(shown.getJSONObject("parentBudget")).equals(canonical(budget)))throw new SecurityException("原父pool已变化，请重新审阅实际剩余");int used=budget.getInt("toolOperationsReservedOrUsed"),parentRemaining=grant(shown.getString("parentRunId")).getInt("remainingOperations");if(parentRemaining!=shown.getInt("parentRemainingOperations")||parentRemaining<shown.getInt("minimumAdditionalToolOperations")||approvedParentToolLimit<pool.getInt("toolOperationLimit")||approvedParentToolLimit>shown.getInt("proposedParentToolLimit")||approvedParentToolLimit-used<shown.getInt("minimumAdditionalToolOperations")||approvedParentToolLimit>used+parentRemaining)throw new SecurityException("本次真实父tool剩余不足或主人未批准完整制作最小预算，不补父grant");if(approvedParentModelLimit<pool.getInt("modelRequestLimit")||approvedParentModelLimit>shown.getInt("proposedParentModelLimit"))throw new SecurityException("本地dispatcher模型请求额度必须原生明确批准，不挪external_job");pool.put("modelRequestLimit",approvedParentModelLimit).put("toolOperationLimit",approvedParentToolLimit);pool.getJSONObject("budgetSource").put("remoteOwnerApprovedToolLimit",approvedParentToolLimit).put("remoteOwnerBudgetReview",acceptedDigest);dev.pocketengine.features.teams.TeamParentBudget.initializeExternalJobs(pool,shown.getJSONObject("externalBudget"));if(dev.pocketengine.features.teams.TeamParentBudget.remaining(pool,"externalJobs")<approvedJobs)throw new SecurityException("原父external_job剩余额度不足，不自动补满");
            JSONObject approved=new JSONObject(candidate.toString()).put("scopeId",UUID.randomUUID().toString()).put("revision",shown.getInt("scopeRevision")).put("state","approved").put("approvedJobs",approvedJobs).put("approvedAt",System.currentTimeMillis()).put("expiresAt",shown.getLong("proposedScopeExpiresAt")).put("acceptedDigest",acceptedDigest).put("approvedParentToolLimit",approvedParentToolLimit).put("approvedParentModelLimit",approvedParentModelLimit).put("proposedModelRequestLimit",approvedParentModelLimit).put("proposedToolOperationLimit",approvedParentToolLimit).put("parentBudget",dev.pocketengine.features.teams.TeamParentBudget.snapshot(pool));
            task.put("teamRemoteAssignmentScope",approved).put("teamRemoteScopeRevision",approved.getInt("revision"));write(task);parent.put("teamReservationPool",pool);write(parent);review.approvedScopeId=approved.getString("scopeId");db.setTransactionSuccessful();return new JSONObject(approved.toString());
        }finally{db.endTransaction();}
    }
    /** Only the original native owner plan callback may queue once and reserve its first real phone-model call. */
    public synchronized JSONObject claimTeamRemoteScopeContinuation(TeamRemoteScopeReview review,JSONObject target)throws Exception {
        if(review==null||review.issuer!=this||review.approvedScopeId==null||review.window.current()!=review.windowRevision)throw new SecurityException("没有本次native owner已批准scope");db.beginTransaction();try{
            JSONObject shown=review.view(),task=required(shown.getString("assignmentId")),permission=approvedTeamRemoteScope(task);if(!review.approvedScopeId.equals(permission.getString("scopeId")))throw new SecurityException("原scope已替换");JSONObject previous=task.optJSONObject("teamRemoteContinuation");if(previous!=null&&permission.getString("scopeId").equals(previous.optString("scopeId"))){db.setTransactionSuccessful();return new JSONObject().put("claimed",false);}
            for(String key:new String[]{"assignmentId","memberId","teamRunId","runGeneration","memberGeneration"})if(!canonical(shown.get(key)).equals(canonical(target.get(key))))throw new SecurityException("不是原native计划选定的等待成员");JSONObject current=teamRemoteScopeCandidate(task.getString("id"));for(String key:new String[]{"runGeneration","memberGeneration","waitingRunId","waitingEpoch"})if(!canonical(shown.get(key)).equals(canonical(current.get(key))))throw new SecurityException("原等待点已变化，旧批准不能再唤醒");if(!task.optString("status").matches("awaiting_user|needs_confirmation|cancelled")||task.optBoolean("cancelRequested")||hasUnresolved(task.getString("id"))||teamHasPendingQuestion(task))throw new SecurityException("原分工不是安全等待点");try(Cursor active=db.rawQuery("SELECT 1 FROM runs WHERE task_id=? AND status='running' LIMIT 1",new String[]{task.getString("id")})){if(active.moveToFirst())throw new SecurityException("原分工旧Runner尚未返回");}
            JSONObject parent=required(shown.getString("parentTaskId")),pool=teamBudget(parent),bound=new JSONObject().put("teamRunId",shown.getString("teamRunId")).put("runGeneration",shown.getInt("runGeneration")).put("childTaskId",task.getString("id")).put("childRunId",task.getString("runId")).put("childEpoch",task.getInt("coordinatorEpoch")).put("memberId",shown.getString("memberId")).put("memberGeneration",shown.getInt("memberGeneration")).put("assignmentId",task.getString("id"));JSONObject reservation=dev.pocketengine.features.teams.TeamParentBudget.reserve(pool,bound,"member","remote-scope:"+permission.getString("scopeId")+":"+task.getString("id"),1,0);task.put("teamRemoteContinuation",new JSONObject().put("scopeId",permission.getString("scopeId")).put("reservationId",reservation.getString("reservationId")).put("runGeneration",shown.getInt("runGeneration")).put("memberGeneration",shown.getInt("memberGeneration")));write(task);parent.put("teamReservationPool",pool);write(parent);db.setTransactionSuccessful();return new JSONObject().put("claimed",true).put("reservationId",reservation.getString("reservationId"));
        }finally{db.endTransaction();}
    }
    public synchronized JSONObject teamRemoteScopeSnapshot(String assignmentId)throws Exception {JSONObject candidate=teamRemoteScopeCandidate(assignmentId),task=required(assignmentId),approved=task.optJSONObject("teamRemoteAssignmentScope");boolean needs=true;try{approvedTeamRemoteScope(task);needs=false;}catch(TeamRemoteScopeRequired absent){/* Original owner facts only. */}catch(SecurityException changed){/* Changed/expired original scope cannot dispatch. */}return new JSONObject().put("needsOwnerScope",needs).put("state",needs?"not_authorized":"approved").put("assignmentId",assignmentId).put("assignmentRevision",candidate.getInt("assignmentRevision")).put("selection",candidate.getJSONObject("remoteSelection")).put("approvedScope",approved==null?JSONObject.NULL:new JSONObject(approved.toString())).put("parentBudget",teamBudgetSnapshot(candidate.getString("teamRunId"))).put("executionAuthorized",false);}
    public synchronized void revokeTeamRemoteScope(String assignmentId,String scopeId,int revision)throws Exception {JSONObject task=required(assignmentId);requirePrivateOwner(task);JSONObject scope=task.getJSONObject("teamRemoteAssignmentScope");if(!scopeId.equals(scope.getString("scopeId"))||revision!=scope.getInt("revision"))throw new SecurityException("请刷新原分工scope后撤销");scope.put("state","revoked");task.put("teamRemoteAssignmentScope",scope);write(task);}
    private JSONObject approvedTeamRemoteScope(JSONObject assignment)throws Exception {
        JSONObject approved=assignment.optJSONObject("teamRemoteAssignmentScope");if(approved==null||!approved.optString("state").equals("approved")||approved.optLong("expiresAt")<=System.currentTimeMillis())throw new TeamRemoteScopeRequired("请主人先审阅原分工所选文件与独立remote job scope；没有派发");JSONObject actual=teamRemoteScopeCandidate(assignment.getString("id"));for(String key:new String[]{"assignmentId","assignmentRevision","memberId","teamRunId","teamPlanHash","parentTaskId","parentRunId","parentEpoch","targetProjectId","remoteSelection","files"})if(!canonical(actual.get(key)).equals(canonical(approved.get(key))))throw new SecurityException("原分工scope已改版/改派/换parent，必须重新原生审阅");return approved;
    }

    private static String teamRemoteTool(TeamRemoteAction action){switch(action){case PREPARE:return "team_prepare_remote_workspace";case SUBMIT:return "team_submit_remote_job";case CANCEL:return "team_cancel_remote_job";case FREEZE:return "team_freeze_remote_contribution";case RECLAIM:return "team_reclaim_remote_workspace";case IMPORT:return "team_import_remote_contribution";default:throw new SecurityException("原模型工具不能批准远端请求");}}
    /** Original AssignmentCall and live original control, not an arbitrary selected JSON object. */
    public synchronized TeamRemoteContext teamRemoteContext(dev.pocketengine.features.teams.TeamCoordinator.AssignmentCall call,JSONObject binding,RunControl original)throws Exception {
        call.checkCurrent();original.check();JSONObject task=teamAssignmentIdentity(binding);if(!task.getJSONObject("origin").optString("kind").equals("team_assignment")||!call.assignmentId.equals(binding.getString("taskId"))||!call.memberId.equals(binding.getString("memberId"))||!call.teamRunId.equals(binding.getString("teamRunId"))||!original.matchesHostRun(binding.getString("taskId"),binding.getString("runId"),binding.getInt("coordinatorEpoch"))||call.runGeneration!=binding.getInt("runGeneration")||call.memberGeneration!=binding.getInt("memberGeneration"))throw new SecurityException("远端制作不是原native分工尝试");
        JSONObject identity=task.getJSONObject("teamAssignment"),selection=identity.getJSONObject("remoteSelection"),team=teamWriteTask(call.teamRunId),scope=team.getJSONObject("teamWriteScope"),parent=teamBudgetOwner(team);
        checkTeamIntegrationParents(team);JSONObject nativeAssignment=new JSONObject().put("taskId",task.getString("id")).put("runId",binding.getString("runId")).put("coordinatorEpoch",binding.getInt("coordinatorEpoch"))
                .put("parentTaskId",parent.getString("id")).put("parentRunId",scope.getString("parentRunId")).put("parentEpoch",parent.getInt("coordinatorEpoch"))
                .put("teamRunId",call.teamRunId).put("teamGeneration",call.runGeneration).put("assignmentId",call.assignmentId).put("assignmentRevision",identity.optInt("assignmentRevision",1)).put("memberId",call.memberId).put("memberGeneration",call.memberGeneration)
                .put("attemptId",UUID.nameUUIDFromBytes(("pocket-engine/team-remote-attempt/v1/"+task.getString("id")+"/"+binding.getString("runId")+"/"+binding.getInt("coordinatorEpoch")).getBytes(StandardCharsets.UTF_8)).toString());
        if(!scope.getString("parentRunId").equals(parent.optString("runId")))throw new SecurityException("原父预算run已改变");
        JSONObject permission=approvedTeamRemoteScope(task);
        JSONObject value=new JSONObject(selection.toString()).put("nativeAssignment",nativeAssignment).put("files",new JSONArray(identity.getJSONArray("files").toString())).put("targetProjectId",identity.getString("targetProjectId")).put("ownerScopeId",permission.getString("scopeId")).put("ownerScopeRevision",permission.getInt("revision")).put("ownerScopeExpiresAt",permission.getLong("expiresAt"));
        JSONObject frozen=remoteResultSnapshot(value.getString("sourceTaskId"),value.getString("snapshotId"));validateTeamRemoteBase(value,frozen);
        return new TeamRemoteContext(this,value,binding,original,call);
    }
    private void validateTeamRemoteBase(JSONObject context,JSONObject snapshot)throws Exception {
        JSONObject source=snapshot.getJSONObject("source");for(String key:new String[]{"profileId","environmentId","serviceBase"})if(!context.getString(key).equals(source.getString(key)))throw new SecurityException("原团队选定连接与原SQL快照来源不同");
        if(!context.getString("sourceJobId").equals(snapshot.getString("jobId"))||!context.getString("snapshotVersion").equals(snapshot.getString("snapshotVersion")))throw new SecurityException("原团队快照版本已改变");
        if(!context.optBoolean("internalInferenceBillingUnboundedAcknowledged"))throw new SecurityException("必须主人明确确认独立job授权与内部推理计费不可硬限");
        dev.pocketengine.features.remote.RemoteAssignmentWorkspaces.validateLimits(context.getJSONObject("limits"));
        JSONArray files=context.getJSONArray("files"),base=snapshot.getJSONArray("files");if(files.length()<1||files.length()>8)throw new SecurityException("只支持明确1至8个既有文字文件副本");
        for(int i=0;i<files.length();i++){JSONObject file=files.getJSONObject(i),found=null;for(int j=0;j<base.length();j++)if(file.getString("path").equals(base.getJSONObject(j).getString("path")))found=base.getJSONObject(j);if(found==null||!file.getString("baseHash").equals(found.getString("sha256"))||file.getInt("baseVersion")<0)throw new SecurityException("团队项目实际base与原远端冻结字节不一致");}
    }
    public synchronized JSONObject originalTeamRemoteSnapshot(TeamRemoteContext context)throws Exception {checkOriginalTeamRemoteRead(context);JSONObject view=context.view(),snapshot=remoteResultSnapshot(view.getString("sourceTaskId"),view.getString("snapshotId"));validateTeamRemoteBase(view,snapshot);return snapshot;}
    public synchronized void checkOriginalTeamRemoteRead(TeamRemoteContext context)throws Exception {
        if(context==null||context.issuer!=this)throw new SecurityException("不是本原SQL签发的远端上下文");JSONObject value=context.view(),nativeAssignment=value.getJSONObject("nativeAssignment"),task=required(nativeAssignment.getString("taskId"));requirePrivateOwner(task);rejectChannelParent(task);
        if(!task.getJSONObject("origin").optString("kind").equals("team_assignment"))throw new SecurityException("原remote读取已不属于原team_assignment");JSONObject identity=task.getJSONObject("teamAssignment");for(String key:new String[]{"teamRunId","assignmentId","memberId"})if(!nativeAssignment.getString(key).equals(identity.getString(key)))throw new SecurityException("原远端读取已不属于该分工");
        try(Cursor run=db.rawQuery("SELECT 1 FROM runs WHERE task_id=? AND run_id=? AND epoch=?",new String[]{task.getString("id"),nativeAssignment.getString("runId"),String.valueOf(nativeAssignment.getInt("coordinatorEpoch"))})){if(!run.moveToFirst())throw new SecurityException("原分工执行不存在");}
        JSONObject parent=required(nativeAssignment.getString("parentTaskId"));requirePrivateOwner(parent);try(Cursor run=db.rawQuery("SELECT 1 FROM runs WHERE task_id=? AND run_id=? AND epoch=?",new String[]{parent.getString("id"),nativeAssignment.getString("parentRunId"),String.valueOf(nativeAssignment.getInt("parentEpoch"))})){if(!run.moveToFirst())throw new SecurityException("原父run不存在");}
    }
    /** Retained dispatch boundary fence: never calls RunControl.check (and therefore never recurses). */
    public synchronized void checkTeamRemoteScopeCurrent(TeamRemoteContext context)throws Exception {
        checkOriginalTeamRemoteRead(context);if(context.assignment==null||context.original==null||context.original.isCancelled()||context.original.isPaused())throw new SecurityException("原read context不是制作执行权");context.assignment.checkCurrent();JSONObject binding=new JSONObject(context.binding);checkTeamAssignmentCurrent(binding,teamRemoteTool(TeamRemoteAction.PREPARE));JSONObject current=approvedTeamRemoteScope(required(binding.getString("taskId"))),view=context.view();if(!current.getString("scopeId").equals(view.getString("ownerScopeId"))||current.getInt("revision")!=view.getInt("ownerScopeRevision"))throw new SecurityException("原scope撤销/换版，停止后继本机CAS");
    }
    private void checkTeamRemoteCurrent(TeamRemoteContext context,String tool,RunControl original)throws Exception {
        checkOriginalTeamRemoteRead(context);if(context.original!=original||context.assignment==null)throw new SecurityException("读取上下文不能变成模型执行权");context.assignment.checkCurrent();checkTeamAssignment(new JSONObject(context.binding),tool,original);JSONObject current=teamRemoteContext(context.assignment,new JSONObject(context.binding),original).view();if(!canonical(current).equals(canonical(context.view())))throw new SecurityException("原远端制作的native选择或代次已变化");
    }
    private JSONObject teamRemoteOperation(TeamRemoteContext context,String prepared)throws Exception {
        checkOriginalTeamRemoteRead(context);JSONObject binding=new JSONObject(context.binding);try(Cursor row=db.rawQuery("SELECT tool,arguments,state,receipt FROM operations WHERE task_id=? AND run_id=? AND operation_id=?",new String[]{binding.getString("taskId"),binding.getString("runId"),prepared})){if(!row.moveToFirst())throw new SecurityException("原prepared动作不存在");JSONObject result=new JSONObject().put("operationId",prepared).put("tool",row.getString(0)).put("arguments",new JSONObject(row.getString(1))).put("state",row.getString(2));if(!row.isNull(3))result.put("receipt",new JSONObject(row.getString(3)));return result;}
    }
    private static JSONObject remoteActionRecord(JSONObject row)throws Exception {JSONObject receipt=row.optJSONObject("receipt");if(receipt==null||receipt.optJSONObject("teamRemote")==null)throw new SecurityException("原操作没有native远端授权记录");return receipt.getJSONObject("teamRemote");}
    public synchronized TeamRemoteClaim originalTeamRemoteClaim(TeamRemoteContext context,String prepared)throws Exception {
        JSONObject record=remoteActionRecord(teamRemoteOperation(context,prepared));if(!canonical(context.view()).equals(canonical(record.getJSONObject("context"))))throw new SecurityException("原恢复记录属于另一原生上下文");return new TeamRemoteClaim(this,context,TeamRemoteAction.valueOf(record.getString("action")),TeamRemoteDisposition.QUERY_ONLY,record,"",null);
    }
    private void saveTeamRemoteRecord(TeamRemoteContext context,String prepared,JSONObject record,String state)throws Exception {
        JSONObject binding=new JSONObject(context.binding);ContentValues value=new ContentValues();value.put("state",state);value.put("receipt",new JSONObject().put("teamRemote",record).put("status",record.optString("status","unknown")).put("accepted",record.optString("status").equals("completed")?true:JSONObject.NULL).toString());if(db.update("operations",value,"task_id=? AND run_id=? AND operation_id=?",new String[]{binding.getString("taskId"),binding.getString("runId"),prepared})!=1)throw new SecurityException("原远端操作已消失");
    }
    public synchronized TeamRemoteClaim claimTeamRemoteAction(TeamRemoteContext context,TeamRemoteAction action,String prepared,JSONObject exactScope,RunControl original)throws Exception {
        if(action==TeamRemoteAction.APPROVE)throw new SecurityException("远端审批只能来自原主人前台window");if(prepared==null){if(action!=TeamRemoteAction.CANCEL)throw new SecurityException("停后权限仅允许停止原job，不创建新制作/写入/回收");return claimTeamRemoteCleanup(context,exactScope.getString("executionPreparedOperationId"));}
        db.beginTransaction();try{
            String tool=teamRemoteTool(action);checkTeamRemoteCurrent(context,tool,original);JSONObject row=teamRemoteOperation(context,prepared),view=context.view(),scope=new JSONObject(exactScope.toString());
            if(!tool.equals(row.getString("tool"))||!canonical(row.getJSONObject("arguments")).equals(canonical(scope.getJSONObject("arguments"))))throw new SecurityException("远端动作不是原Runner精确prepared参数");
            for(String key:new String[]{"nativeAssignment","profileId","profileGeneration","serviceBase","environmentId","environmentGeneration","environmentBinding","sourceTaskId","sourceJobId","snapshotId","snapshotVersion","files","limits","targetProjectId","ownerScopeId","ownerScopeRevision","ownerScopeExpiresAt"})if(!canonical(view.get(key)).equals(canonical(scope.get(key))))throw new SecurityException("远端scope替换了原native选择: "+key);
            if(!row.getString("state").equals("prepared")){TeamRemoteClaim old=originalTeamRemoteClaim(context,prepared);db.setTransactionSuccessful();return old;}
            if(action!=TeamRemoteAction.CANCEL)try(Cursor unresolved=db.rawQuery("SELECT 1 FROM operations WHERE task_id=? AND operation_id!=? AND state IN ('prepared','unknown') LIMIT 1",new String[]{view.getJSONObject("nativeAssignment").getString("taskId"),prepared})){if(unresolved.moveToFirst())throw new SecurityException("原assignment还有未决操作，只能查询或停止原job");}
            JSONObject previous=null;if(action!=TeamRemoteAction.PREPARE){String sourceOperation=scope.getString(action==TeamRemoteAction.SUBMIT?"prepareOperationId":"executionPreparedOperationId");TeamRemoteContext originContext=originalTeamRemoteContextForOperation(view.getJSONObject("nativeAssignment").getString("taskId"),sourceOperation);previous=remoteActionRecord(teamRemoteOperation(originContext,sourceOperation));if(action==TeamRemoteAction.SUBMIT){sameTeamRemotePermission(view,originContext.view());if(!canonical(originContext.view()).equals(canonical(scope.getJSONObject("preparationContext"))))throw new SecurityException("旧prepare proof不是原SQL来源");}if(action!=TeamRemoteAction.SUBMIT){sameTeamRemotePermission(view,originContext.view());if(!canonical(originContext.view()).equals(canonical(scope.getJSONObject("executionContext"))))throw new SecurityException("旧execution origin不是原SQL记录");}
                if(action==TeamRemoteAction.SUBMIT){if(!previous.getString("action").equals("PREPARE")||!previous.optString("status").equals("completed")||!canonical(previous.getJSONObject("actualReceipt")).equals(canonical(scope.getJSONObject("preparation"))))throw new SecurityException("制作需原已核实物理副本prepare回执");if(!scope.getString("prompt").equals(row.getJSONObject("arguments").getString("prompt")))throw new SecurityException("制作prompt不是原prepared内容");}
                else {if(!previous.getString("action").equals("SUBMIT")||previous.optString("jobId").isEmpty())throw new SecurityException("动作没有原已接受job");for(String key:new String[]{"childTaskId","jobOperationId","jobId","reservationId"})scope.put(key,previous.get(key));scope.put("assignmentWorkspace",previous.getJSONObject("scope").getJSONObject("assignmentWorkspace"));}
            }
            if(action==TeamRemoteAction.FREEZE){JSONObject child=required(scope.getString("childTaskId")),source=remoteOwnerTarget(child.getString("id"));if(!source.optString("executorStatus").matches("completed|failed|cancelled")||!source.optBoolean("dispatchFenceFinal")||hasUnresolved(child.getString("id")))throw new SecurityException("原job未最终结算，不冻结当前变化中的文件");scope.put("jobSource",source);}
            if(action==TeamRemoteAction.IMPORT){JSONObject frozenOrigin=originalTeamRemoteContextForOperation(view.getJSONObject("nativeAssignment").getString("taskId"),scope.getString("contributionPreparedOperationId")).view();sameTeamRemotePermission(view,frozenOrigin);if(!canonical(frozenOrigin).equals(canonical(scope.getJSONObject("contributionContext"))))throw new SecurityException("冻结来源不是原SQL action");JSONObject frozen=remoteActionRecord(teamRemoteOperation(originalTeamRemoteContextForOperation(view.getJSONObject("nativeAssignment").getString("taskId"),scope.getString("contributionPreparedOperationId")),scope.getString("contributionPreparedOperationId"))),contribution=frozen.getJSONObject("actualReceipt");if(!frozen.getString("action").equals("FREEZE")||!frozen.optString("status").equals("completed")||!canonical(contribution).equals(canonical(scope.getJSONObject("contribution")))||!capabilityFingerprint(contribution).equals(scope.getString("manifestHash")))throw new SecurityException("没有原SQL冻结贡献，不可用模型manifest导入");JSONObject draft=scope.getJSONObject("draft");boolean known=false;JSONArray drafts=teamAssignmentReceipts(new JSONObject(context.binding),"team_prepare_draft");for(int d=0;d<drafts.length();d++){JSONObject candidate=drafts.getJSONObject(d).optJSONObject("teamDraft");if(candidate!=null&&canonical(candidate).equals(canonical(draft)))known=true;}if(!known||!view.getString("targetProjectId").equals(draft.getString("targetProjectId"))||!view.getJSONObject("nativeAssignment").getString("assignmentId").equals(draft.getString("assignmentId"))||draft.getInt("assignmentRevision")!=view.getJSONObject("nativeAssignment").getInt("assignmentRevision"))throw new SecurityException("贡献只能导入本assignment已登记原draft，不可写原项目");}
            JSONObject permission=approvedTeamRemoteScope(required(view.getJSONObject("nativeAssignment").getString("taskId")));if(!permission.getString("scopeId").equals(view.getString("ownerScopeId"))||permission.getInt("revision")!=view.getInt("ownerScopeRevision"))throw new SecurityException("原native一次scope已撤销或换版");
            JSONObject approval=new JSONObject().put("approvalId",permission.getString("scopeId"));
            JSONObject binding=new JSONObject(context.binding),bound=teamBudgetBinding(binding),owner=teamBudgetOwner(teamWriteTask(binding.getString("teamRunId"))),pool=teamBudget(owner),parentGrant=grant(pool.getString("parentRunId"));
            if(parentGrant.optLong("expiresAt")<=System.currentTimeMillis()||parentGrant.optInt("remainingOperations")<=0||dev.pocketengine.features.teams.TeamParentBudget.remaining(pool,"toolOperations")<=0)throw new SecurityException("原父tool额度已用尽或过期，未借用external_job额度");
            String wire=UUID.nameUUIDFromBytes(("pocket-engine/team-remote-wire/v1/"+binding.getString("taskId")+"/"+binding.getString("runId")+"/"+prepared+"/"+action.name()).getBytes(StandardCharsets.UTF_8)).toString(),token=UUID.randomUUID().toString();long expiry=Math.min(parentGrant.getLong("expiresAt"),permission.getLong("expiresAt"));
            JSONObject record=new JSONObject().put("context",view).put("binding",new JSONObject(context.binding)).put("action",action.name()).put("preparedOperationId",prepared).put("operationId",wire).put("status","reserved").put("claimToken",token).put("approvalRevision",permission.getInt("revision")).put("approvalExpiresAt",expiry).put("dispatchExpiresAt",Math.min(expiry,System.currentTimeMillis()+60_000L)).put("approvalId",approval.getString("approvalId"));
            if(action==TeamRemoteAction.SUBMIT){
                JSONObject externalPlan=teamWriteTask(binding.getString("teamRunId")).getJSONObject("teamWriteScope").getJSONObject("externalBudget");dev.pocketengine.features.teams.TeamParentBudget.initializeExternalJobs(pool,externalPlan);
                int spent=0;JSONArray priorJobs=pool.getJSONArray("reservations");for(int j=0;j<priorJobs.length();j++){JSONObject job=priorJobs.getJSONObject(j),jobScope=job.optJSONObject("approvedScope");if(job.optInt("externalJobs")==1&&!job.optString("state").equals("released_unstarted")&&jobScope!=null&&permission.getString("scopeId").equals(jobScope.optString("ownerScopeId")))spent++;}if(spent>=permission.getInt("approvedJobs"))throw new SecurityException("本assignment已批准job额度用尽，未知/取消不退款");
                String child=UUID.nameUUIDFromBytes(("pocket-engine/team-remote-child/v1/"+binding.getString("taskId")+"/"+binding.getString("runId")+"/"+prepared).getBytes(StandardCharsets.UTF_8)).toString(),reservation=UUID.randomUUID().toString();
                record.put("childTaskId",child).put("jobOperationId",wire).put("reservationId",reservation);JSONObject copy=scope.getJSONObject("preparation");
                JSONObject reference=new JSONObject().put("sourceJobId",view.getString("sourceJobId")).put("checkoutId",copy.getString("checkoutId")).put("checkoutVersion",copy.getString("checkoutVersion")).put("nativeAssignment",view.getJSONObject("nativeAssignment")).put("preparationNativeAssignment",copy.getJSONObject("nativeAssignment")).put("reservationId",reservation).put("approvalRevision",record.getLong("approvalRevision")).put("approvalExpiresAt",expiry);
                scope.put("assignmentWorkspace",reference).put("childTaskId",child).put("jobOperationId",wire).put("reservationId",reservation);
                dev.pocketengine.features.teams.TeamParentBudget.reserveExternalJob(pool,bound,prepared,wire,reservation,scope);
                JSONObject childTask=base(child,scope.getString("prompt"),"remote","remote").put("feature","remote").put("command","submitJob").put("operationId",wire).put("runId",wire).put("coordinatorEpoch",1).put("status","queued").put("parentTaskId",binding.getString("taskId")).put("parentRunId",binding.getString("runId")).put("parentOperationId",prepared)
                    .put("origin",new JSONObject().put("kind","team_remote_assignment").put("context",view).put("binding",binding).put("preparedOperationId",prepared)).put("externalRefs",new JSONObject().put("profileId",view.getString("profileId")).put("environmentId",view.getString("environmentId")).put("worker","codex_app_server").put("readOnly",false))
                    .put("evidence",new JSONObject().put("profileId",view.getString("profileId")).put("serviceBase",view.getString("serviceBase")).put("operationId",wire).put("executorStatus","submitting"));
                if(read(child)!=null)throw new SecurityException("原remote child编号已被占用");write(childTask);saveRemoteRun(childTask,"running",new JSONObject().put("nativeAssignment",view.getJSONObject("nativeAssignment")));insertRemoteOperation(wire,child,wire,"remote_job",new JSONObject().put("taskId",child).put("runId",wire).put("coordinatorEpoch",1),"remote_job_sending");
                JSONObject parent=required(binding.getString("taskId"));JSONArray related=parent.optJSONArray("relatedTasks");if(related==null)related=new JSONArray();if(related.length()>=100)throw new SecurityException("原任务子执行记录已满");related.put(new JSONObject().put("taskId",child).put("runId",wire).put("parentOperationId",prepared).put("kind","remote").put("feature","remote"));parent.put("relatedTasks",related);write(parent);
            }else if(previous!=null&&action!=TeamRemoteAction.PREPARE)for(String key:new String[]{"childTaskId","jobOperationId","jobId","reservationId"})if(previous.has(key))record.put(key,previous.get(key));
            scope.put("operationId",wire).put("approvalRevision",record.getLong("approvalRevision")).put("approvalExpiresAt",expiry);record.put("scope",scope).put("scopeHash",capabilityFingerprint(scope));
            JSONObject unit=dev.pocketengine.features.teams.TeamParentBudget.reserve(pool,bound,"member","operation:"+prepared,0,1);unit.getJSONArray("operationIds").put(prepared);unit.put("state","unknown");parentGrant.put("remainingOperations",parentGrant.getInt("remainingOperations")-1);ContentValues grantRow=new ContentValues();grantRow.put("grant_json",parentGrant.toString());db.update("runs",grantRow,"run_id=?",new String[]{pool.getString("parentRunId")});owner.put("teamReservationPool",pool);write(owner);saveTeamRemoteRecord(context,prepared,record,"unknown");
            db.setTransactionSuccessful();return new TeamRemoteClaim(this,context,action,TeamRemoteDisposition.DISPATCH,record,token,null);
        }finally{db.endTransaction();}
    }
    public synchronized void beforeTeamRemoteDispatch(TeamRemoteClaim claim,RunControl original)throws Exception {
        db.beginTransaction();try{
            if(claim==null||claim.issuer!=this||!claim.canDispatch()||claim.action==TeamRemoteAction.APPROVE)throw new SecurityException("没有原SQL单次派发claim");if(claim.view().optBoolean("cleanupOnly"))checkOriginalTeamRemoteRead(claim.context);else checkTeamRemoteCurrent(claim.context,teamRemoteTool(claim.action),original);JSONObject view=claim.view(),record=remoteActionRecord(teamRemoteOperation(claim.context,view.getString("preparedOperationId")));
            if(!record.optString("status").equals("reserved")||!claim.claimToken.equals(record.optString("claimToken"))||record.getLong("dispatchExpiresAt")<=System.currentTimeMillis()||!record.getString("scopeHash").equals(view.getString("scopeHash")))throw new SecurityException("原claim已消费/过期/被替换；只能查询原动作");
            record.put("status","sending").put("dispatchedAt",System.currentTimeMillis());saveTeamRemoteRecord(claim.context,view.getString("preparedOperationId"),record,"unknown");
            if(claim.action==TeamRemoteAction.SUBMIT){JSONObject owner=teamBudgetOwner(teamWriteTask(claim.context.view().getJSONObject("nativeAssignment").getString("teamRunId"))),pool=teamBudget(owner);dev.pocketengine.features.teams.TeamParentBudget.startExternalJob(pool,record.getString("reservationId"),record.getString("jobOperationId"));owner.put("teamReservationPool",pool);write(owner);}
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
    public synchronized void recordTeamRemoteReceipt(TeamRemoteClaim claim,JSONObject actual)throws Exception {
        if(claim==null||claim.issuer!=this)throw new SecurityException("回执没有原SQL claim");if(claim.action==TeamRemoteAction.APPROVE){recordRemoteApprovalResult(claim.view().getString("childTaskId"),claim.view().getString("operationId"),actual);return;}
        db.beginTransaction();try{
            checkOriginalTeamRemoteRead(claim.context);JSONObject view=claim.view(),record=remoteActionRecord(teamRemoteOperation(claim.context,view.getString("preparedOperationId")));if(!record.getString("scopeHash").equals(view.getString("scopeHash")))throw new SecurityException("迟到回执不能替换原scope");
            if(claim.action==TeamRemoteAction.SUBMIT){updateExternal(record.getString("childTaskId"),actual,false);db.setTransactionSuccessful();return;}
            String status=actual.optString("status");if(status.matches("unknown|not_found|preparing")){if(!record.optString("status").equals("completed")){record.put("status","unknown");saveTeamRemoteRecord(claim.context,record.getString("preparedOperationId"),record,"unknown");}db.setTransactionSuccessful();return;}
            if(record.optString("status").equals("completed")){if(claim.action==TeamRemoteAction.FREEZE){JSONObject previous=record.getJSONObject("actualReceipt");if(!previous.getString("snapshotId").equals(actual.optString("snapshotId"))||!previous.getString("snapshotVersion").equals(actual.optString("snapshotVersion")))throw new SecurityException("原冻结回执不能替换");}else if(!canonical(record.getJSONObject("actualReceipt")).equals(canonical(actual)))throw new SecurityException("原动作已结算，不能替换不可变回执");db.setTransactionSuccessful();return;}
            if(claim.action==TeamRemoteAction.PREPARE)dev.pocketengine.features.remote.RemoteAssignmentWorkspaces.validatePreparation(actual,record.getJSONObject("scope"));
            else if(claim.action==TeamRemoteAction.FREEZE){JSONObject metadata=dev.pocketengine.features.remote.RemoteAssignmentWorkspaces.validateContribution(actual,record.getJSONObject("scope"));JSONObject child=required(record.getString("childTaskId"));JSONArray snapshots=child.optJSONArray("remoteResultSnapshots");if(snapshots==null)snapshots=new JSONArray();if(snapshots.length()>=8)throw new SecurityException("原child冻结成果数已满");snapshots.put(metadata);child.put("remoteResultSnapshots",snapshots);write(child);actual=metadata;}
            else if(claim.action==TeamRemoteAction.IMPORT){if(!actual.optBoolean("importedToOriginalDraft")||actual.optBoolean("integrated")||!record.getJSONObject("scope").getString("manifestHash").equals(actual.optString("manifestHash")))throw new SecurityException("导入回执没有原draft/贡献绑定");}
            else if(claim.action==TeamRemoteAction.CANCEL){JSONObject job=required(record.getString("childTaskId"));validateTeamRemoteJob(job,actual);updateExternal(job.getString("id"),actual,false);}
            else throw new SecurityException("原server尚未提供本动作，不冒充清理完成");
            record.put("status","completed").put("actualReceipt",new JSONObject(actual.toString())).put("settledAt",System.currentTimeMillis());saveTeamRemoteRecord(claim.context,record.getString("preparedOperationId"),record,"receipt");db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
    private void validateTeamRemoteJob(JSONObject child,JSONObject actual)throws Exception {
        JSONObject origin=child.getJSONObject("origin");if(!origin.optString("kind").equals("team_remote_assignment"))throw new SecurityException("不是本remote制作来源");JSONObject context=origin.getJSONObject("context"),binding=origin.getJSONObject("binding");TeamRemoteContext read=new TeamRemoteContext(this,context,binding,null,null);checkOriginalTeamRemoteRead(read);
        JSONObject record=remoteActionRecord(teamRemoteOperation(read,origin.getString("preparedOperationId"))),scope=record.getJSONObject("scope");
        if(!child.getString("id").equals(record.getString("childTaskId"))||!child.getString("operationId").equals(record.getString("jobOperationId"))||!actual.optString("operationId").equals(record.getString("jobOperationId"))||!context.getString("profileId").equals(actual.optString("profileId"))||!context.getString("serviceBase").equals(actual.optString("serviceBase")))throw new SecurityException("迟账不属于原child/job/连接");
        if(!actual.optString("status").matches("unknown|not_found|submitting")){
            if(!context.getString("environmentId").equals(actual.optString("environmentId"))||!"codex_app_server".equals(actual.optString("worker"))||!canonical(scope.getJSONObject("assignmentWorkspace")).equals(canonical(actual.optJSONObject("assignmentWorkspace"))))throw new SecurityException("原job回执替换了checkout/native tuple/预留");
            if(!record.optString("jobId").isEmpty()&&!record.getString("jobId").equals(actual.optString("id")))throw new SecurityException("原jobId已固定，不能替换");
        }
        boolean related=false;JSONArray refs=required(binding.getString("taskId")).optJSONArray("relatedTasks");for(int i=0;refs!=null&&i<refs.length();i++){JSONObject ref=refs.getJSONObject(i);if(child.getString("id").equals(ref.optString("taskId"))&&record.getString("jobOperationId").equals(ref.optString("runId"))&&record.getString("preparedOperationId").equals(ref.optString("parentOperationId")))related=true;}if(!related)throw new SecurityException("原assignment没有此remote child关系");
    }
    /** Original receipt hook also runs on post-restart GET; never resumes the parent/Runner. */
    private void settleTeamRemoteOriginalJob(JSONObject child,JSONObject actual)throws Exception {
        JSONObject origin=child.optJSONObject("origin");if(origin==null||!origin.optString("kind").equals("team_remote_assignment"))return;validateTeamRemoteJob(child,actual);JSONObject context=origin.getJSONObject("context"),binding=origin.getJSONObject("binding");TeamRemoteContext read=new TeamRemoteContext(this,context,binding,null,null);JSONObject record=remoteActionRecord(teamRemoteOperation(read,origin.getString("preparedOperationId")));
        if(actual.optString("id").isEmpty()||actual.optString("status").matches("unknown|not_found|submitting"))return;
        JSONObject owner=required(context.getJSONObject("nativeAssignment").getString("parentTaskId")),pool=teamBudget(owner);dev.pocketengine.features.teams.TeamParentBudget.finishExternalJob(pool,record.getString("reservationId"),record.getString("jobOperationId"),actual,actual.optBoolean("notStartedConfirmed"));owner.put("teamReservationPool",pool);write(owner);
        record.put("jobId",actual.getString("id")).put("status","completed").put("actualReceipt",new JSONObject(actual.toString())).put("acceptedOnly",true);saveTeamRemoteRecord(read,record.getString("preparedOperationId"),record,"receipt");
    }
    private static void sameTeamRemotePermission(JSONObject current,JSONObject original)throws Exception {for(String key:new String[]{"ownerScopeId","ownerScopeRevision","profileId","profileGeneration","serviceBase","environmentId","environmentGeneration","sourceTaskId","sourceJobId","snapshotId","snapshotVersion","files","limits","targetProjectId"})if(!canonical(current.get(key)).equals(canonical(original.get(key))))throw new SecurityException("原job不属于当前同一native assignment scope");for(String key:new String[]{"taskId","parentTaskId","parentRunId","parentEpoch","teamRunId","assignmentId","assignmentRevision","memberId"})if(!canonical(current.getJSONObject("nativeAssignment").get(key)).equals(canonical(original.getJSONObject("nativeAssignment").get(key))))throw new SecurityException("原job已改派/改版/换parent，只能读原件");}
    public synchronized TeamRemoteContext originalTeamRemoteContextForOperation(String assignmentId,String originalPreparedOperation)throws Exception {
        JSONObject task=required(assignmentId);requirePrivateOwner(task);rejectChannelParent(task);try(Cursor row=db.rawQuery("SELECT tool,receipt,run_id FROM operations WHERE task_id=? AND operation_id=?",new String[]{assignmentId,originalPreparedOperation})){if(!row.moveToFirst()||row.isNull(1)||!row.getString(0).matches("team_(prepare_remote_workspace|submit_remote_job|cancel_remote_job|freeze_remote_contribution|import_remote_contribution)"))throw new SecurityException("没有这次原SQL remote action");JSONObject record=new JSONObject(row.getString(1)).getJSONObject("teamRemote"),view=record.getJSONObject("context"),binding=record.getJSONObject("binding");if(!row.getString(2).equals(view.getJSONObject("nativeAssignment").getString("runId"))||!assignmentId.equals(binding.getString("taskId")))throw new SecurityException("原record不是此SQL任务/run");TeamRemoteContext context=new TeamRemoteContext(this,view,binding,null,null);checkOriginalTeamRemoteRead(context);return context;}
    }
    /** Reconstructed original SQL read/stop context; never a model dispatch grant. */
    public synchronized TeamRemoteContext originalTeamRemoteContext(String originalChildTaskId)throws Exception{return teamRemoteContextForChild(originalChildTaskId);}
    private TeamRemoteClaim claimTeamRemoteCleanup(TeamRemoteContext context,String submitOperation)throws Exception {
        db.beginTransaction();try{checkOriginalTeamRemoteRead(context);JSONObject original=remoteActionRecord(teamRemoteOperation(context,submitOperation));if(!original.getString("action").equals("SUBMIT")||original.optString("jobId").isEmpty())throw new SecurityException("未知接收先查原operation，不能猜job取消");String prepared=UUID.nameUUIDFromBytes(("pocket-engine/team-remote-cleanup/v1/"+original.getString("childTaskId")+"/"+original.getString("jobOperationId")+"/cancel").getBytes(StandardCharsets.UTF_8)).toString();try{TeamRemoteClaim old=originalTeamRemoteClaim(context,prepared);db.setTransactionSuccessful();return old;}catch(SecurityException missing){/* Missing original cleanup only: inserted below, never a new coding job. */}
            String wire=UUID.nameUUIDFromBytes(("pocket-engine/team-remote-cleanup-wire/v1/"+prepared).getBytes(StandardCharsets.UTF_8)).toString(),token=UUID.randomUUID().toString();JSONObject scope=new JSONObject(original.getJSONObject("scope").toString()).put("arguments",new JSONObject()).put("executionPreparedOperationId",submitOperation).put("operationId",wire);for(String key:new String[]{"childTaskId","jobOperationId","jobId","reservationId"})scope.put(key,original.get(key));JSONObject record=new JSONObject().put("context",context.view()).put("binding",new JSONObject(context.binding)).put("action","CANCEL").put("cleanupOnly",true).put("status","reserved").put("claimToken",token).put("preparedOperationId",prepared).put("operationId",wire).put("scope",scope).put("scopeHash",capabilityFingerprint(scope)).put("dispatchExpiresAt",System.currentTimeMillis()+60_000L);for(String key:new String[]{"childTaskId","jobOperationId","jobId","reservationId","approvalRevision","approvalExpiresAt"})record.put(key,original.get(key));JSONObject binding=new JSONObject(context.binding);insertRemoteOperation(prepared,binding.getString("taskId"),binding.getString("runId"),teamRemoteTool(TeamRemoteAction.CANCEL),new JSONObject(),"unknown");saveTeamRemoteRecord(context,prepared,record,"unknown");db.setTransactionSuccessful();return new TeamRemoteClaim(this,context,TeamRemoteAction.CANCEL,TeamRemoteDisposition.DISPATCH,record,token,null);
        }finally{db.endTransaction();}
    }
    public synchronized TeamRemoteOwnerWindow openTeamRemoteOwnerWindow(){return new TeamRemoteOwnerWindow(this);}
    private TeamRemoteContext teamRemoteContextForChild(String childId)throws Exception {
        JSONObject child=required(childId);requireRemoteOwner(child);JSONObject origin=child.getJSONObject("origin");if(!origin.optString("kind").equals("team_remote_assignment"))throw new SecurityException("此原child不是团队远端制作");TeamRemoteContext context=new TeamRemoteContext(this,origin.getJSONObject("context"),origin.getJSONObject("binding"),null,null);checkOriginalTeamRemoteRead(context);JSONObject record=remoteActionRecord(teamRemoteOperation(context,origin.getString("preparedOperationId")));if(!childId.equals(record.getString("childTaskId"))||!child.getString("runId").equals(record.getString("jobOperationId")))throw new SecurityException("原child的原操作关联不符");return context;
    }
    private JSONObject currentTeamRemoteOwnerSource(TeamRemoteContext context,String childId)throws Exception {
        checkOriginalTeamRemoteRead(context);JSONObject view=context.view(),tuple=view.getJSONObject("nativeAssignment"),assignment=required(tuple.getString("taskId")),team=teamWriteTask(tuple.getString("teamRunId")),parent=required(tuple.getString("parentTaskId"));checkTeamIntegrationParents(team);
        if(!tuple.getString("runId").equals(assignment.optString("runId"))||tuple.getInt("coordinatorEpoch")!=assignment.optInt("coordinatorEpoch")||assignment.optBoolean("cancelRequested")||assignment.optString("status").matches("completed|cancelled|stopping")||tuple.getInt("assignmentRevision")!=assignment.getJSONObject("teamAssignment").optInt("assignmentRevision",1)||!tuple.getString("parentRunId").equals(parent.optString("runId"))||tuple.getInt("parentEpoch")!=parent.optInt("coordinatorEpoch"))throw new SecurityException("原team/parent/assignment已停止、终态或换代，不能批准新动作");
        JSONObject evidence=team.optJSONObject("evidence");if(evidence==null||!evidence.has("generation")||evidence.getInt("generation")!=tuple.getInt("teamGeneration"))throw new SecurityException("原团队generation已变化或没有真实回执");boolean memberCurrent=false;JSONArray members=evidence.optJSONArray("nativeMemberGenerations");for(int m=0;members!=null&&m<members.length();m++){JSONObject member=members.getJSONObject(m);if(tuple.getString("memberId").equals(member.getString("memberId"))&&tuple.getInt("memberGeneration")==member.getInt("generation")&&!member.optBoolean("paused")&&!member.optString("status").matches("completed|cancelled|failed"))memberCurrent=true;}if(!memberCurrent)throw new SecurityException("原member generation已变化、暂停或无真实回执");
        JSONObject permission=approvedTeamRemoteScope(assignment);if(!permission.getString("scopeId").equals(view.getString("ownerScopeId"))||permission.getInt("revision")!=view.getInt("ownerScopeRevision"))throw new SecurityException("原job scope已撤销或替换");
        JSONObject child=required(childId);if(child.optBoolean("cancelRequested")||child.optString("status").matches("completed|cancelled|stopping"))throw new SecurityException("原remote child已停止或由主人确认完成，不能批准旧pending");JSONObject source=remoteOwnerTarget(childId);if(!source.optString("executorStatus").equals("awaiting_approval"))throw new SecurityException("原job不再处于当前审批等待，只能查询/停止");return source;
    }
    public synchronized TeamRemoteOwnerReview reviewTeamRemotePending(TeamRemoteOwnerWindow window,String childId,String childRun,int epoch,String requestId,String expectedHash)throws Exception {
        if(window==null||window.issuer!=this)throw new SecurityException("没有原native主人窗口");window.current();TeamRemoteContext context=teamRemoteContextForChild(childId);JSONObject source=currentTeamRemoteOwnerSource(context,childId),request=null;
        if(!childRun.equals(source.getString("runId"))||epoch!=source.getInt("coordinatorEpoch")||!source.optBoolean("protocolVerified")||!source.optBoolean("assignmentWriteSandboxVerified")||!source.optBoolean("externalToolsDisabled"))throw new SecurityException("未核原job/epoch与实际协议sandbox事实");JSONArray pending=source.optJSONArray("pendingApprovals");for(int i=0;pending!=null&&i<pending.length();i++)if(requestId.equals(pending.getJSONObject(i).optString("requestId")))request=pending.getJSONObject(i);
        if(request==null||!capabilityFingerprint(request).equals(expectedHash)||!source.getString("threadId").equals(request.optString("threadId"))||!source.getString("turnId").equals(request.optString("turnId")))throw new SecurityException("原pending请求hash/thread/turn已变化");
        JSONObject submit=remoteActionRecord(teamRemoteOperation(context,required(childId).getJSONObject("origin").getString("preparedOperationId")));long expiry=Math.min(submit.getLong("approvalExpiresAt"),System.currentTimeMillis()+60_000L);if(expiry<=System.currentTimeMillis())throw new SecurityException("原制作授权已到期，不能借UI复活");
        JSONObject value=new JSONObject().put("childTaskId",childId).put("childRunId",childRun).put("epoch",epoch).put("source",source).put("requestId",requestId).put("requestHash",expectedHash).put("request",request).put("scope",submit.getJSONObject("scope")).put("approvalRevision",submit.getLong("approvalRevision")).put("approvalExpiresAt",expiry);
        return new TeamRemoteOwnerReview(this,window,value,expiry);
    }
    private void checkTeamRemoteOwnerReview(TeamRemoteOwnerReview review)throws Exception {
        if(review==null||review.issuer!=this||review.window.current()!=review.windowRevision||System.currentTimeMillis()>=review.expiresAt)throw new SecurityException("原主人审阅已关闭、切后台或到期");JSONObject view=review.view();TeamRemoteOwnerReview current=reviewTeamRemotePending(review.window,view.getString("childTaskId"),view.getString("childRunId"),view.getInt("epoch"),view.getString("requestId"),view.getString("requestHash"));JSONObject actual=current.view();for(String key:new String[]{"source","request","scope","approvalRevision"})if(!canonical(view.get(key)).equals(canonical(actual.get(key))))throw new SecurityException("原审批内容或scope已变化");
    }
    public synchronized TeamRemoteClaim claimTeamRemoteOwnerApproval(TeamRemoteOwnerReview review,String acceptedDigest,TeamRemoteOwnerDecision decision)throws Exception {
        db.beginTransaction();try{
            checkTeamRemoteOwnerReview(review);JSONObject view=review.view();if(!capabilityFingerprint(view).equals(acceptedDigest))throw new SecurityException("主人没有确认当前原请求的精确摘要");JSONObject request=view.getJSONObject("request");if(decision==TeamRemoteOwnerDecision.APPROVE_ONCE&&!request.optBoolean("canAccept"))throw new SecurityException("请求将超出原selected-file权限，不能批准");
            TeamRemoteContext context=teamRemoteContextForChild(view.getString("childTaskId"));String wire=UUID.nameUUIDFromBytes(("pocket-engine/team-remote-owner-once/v1/"+view.getString("childTaskId")+"/"+view.getString("childRunId")+"/"+view.getString("requestId")+"/"+view.getString("requestHash")).getBytes(StandardCharsets.UTF_8)).toString();JSONObject old=readTeamRemoteApproval(view.getString("childTaskId"),wire);if(old!=null){db.setTransactionSuccessful();return new TeamRemoteClaim(this,context,TeamRemoteAction.APPROVE,TeamRemoteDisposition.QUERY_ONLY,old,"",null);}
            String token=UUID.randomUUID().toString();JSONObject record=new JSONObject(view.toString()).put("operationId",wire).put("claimToken",token).put("status","reserved").put("jobId",view.getJSONObject("source").getString("jobId")).put("jobOperationId",view.getJSONObject("source").getString("jobOperationId")).put("decision",decision==TeamRemoteOwnerDecision.APPROVE_ONCE?"accept":"decline");
            JSONObject args=new JSONObject().put("source",view.getJSONObject("source")).put("taskId",view.getString("childTaskId")).put("requestId",view.getString("requestId")).put("decision",record.getString("decision")).put("teamRemoteOwner",record);
            insertRemoteOperation(wire,view.getString("childTaskId"),view.getString("childRunId"),"remote_approval",args,"remote_approval_sending");db.setTransactionSuccessful();return new TeamRemoteClaim(this,context,TeamRemoteAction.APPROVE,TeamRemoteDisposition.DISPATCH,record,token,review);
        }finally{db.endTransaction();}
    }
    private JSONObject readTeamRemoteApproval(String task,String operation)throws Exception {try(Cursor row=db.rawQuery("SELECT arguments FROM operations WHERE task_id=? AND operation_id=? AND tool='remote_approval'",new String[]{task,operation})){return row.moveToFirst()?new JSONObject(row.getString(0)).getJSONObject("teamRemoteOwner"):null;}}
    public synchronized void beforeTeamRemoteOwnerDispatch(TeamRemoteClaim claim)throws Exception {
        if(claim==null||claim.issuer!=this||claim.action!=TeamRemoteAction.APPROVE||!claim.canDispatch()||claim.ownerReview==null)throw new SecurityException("没有原主人单次审批claim");db.beginTransaction();try{checkTeamRemoteOwnerReview(claim.ownerReview);JSONObject view=claim.view(),record=readTeamRemoteApproval(view.getString("childTaskId"),view.getString("operationId"));if(record==null||!record.optString("status").equals("reserved")||!claim.claimToken.equals(record.optString("claimToken")))throw new SecurityException("原主人审批已派发，只能查询");record.put("status","sending");JSONObject args=remoteOperation(view.getString("childTaskId"),view.getString("operationId"),"remote_approval").getJSONObject("arguments");args.put("teamRemoteOwner",record);ContentValues changed=new ContentValues();changed.put("arguments",args.toString());db.update("operations",changed,"task_id=? AND operation_id=?",new String[]{view.getString("childTaskId"),view.getString("operationId")});db.setTransactionSuccessful();}finally{db.endTransaction();}
    }

    public synchronized JSONObject registerTeamAssignment(dev.pocketengine.features.teams.TeamCoordinator.AssignmentCall call)throws Exception {
        call.checkCurrent();JSONObject team=teamWriteTask(call.teamRunId),scope=team.getJSONObject("teamWriteScope");
        if(!canonical(scope).equals(canonical(call.scope()))||team.optBoolean("cancelRequested")||team.optString("status").matches("stopping|cancelled|completed"))throw new SecurityException("原团队文件范围已失效或任务已停止");
        JSONObject selected=null;JSONArray assignments=scope.getJSONArray("assignments");for(int i=0;i<assignments.length();i++)if(call.workItemId.isEmpty()?call.memberId.equals(assignments.getJSONObject(i).getString("memberId")):call.workItemId.equals(assignments.getJSONObject(i).optString("workItemId")))selected=assignments.getJSONObject(i);
        if(!call.workItemId.isEmpty()){JSONObject item=currentTeamWorkItem(team,call.workItem());if(dev.pocketengine.features.teams.TeamWorkItems.blockers(team.getJSONObject("teamWorkPlan").getJSONArray("items"),item).length()>0)throw new SecurityException("原工作项依赖尚未真实完成");}
        if(selected==null||!canonical(selected.getJSONArray("files")).equals(canonical(call.files())))throw new SecurityException("原分工文件不属于该成员");
        JSONArray dependencies=new JSONArray(),requestedDependencies=call.dependencyArtifacts();RunControl sourceRead=new RunControl();for(int i=0;i<requestedDependencies.length();i++){JSONObject ref=requestedDependencies.getJSONObject(i);if(ref.getString("assignmentId").equals(call.assignmentId)||!required(ref.getString("taskId")).optString("status").equals("completed"))throw new SecurityException("前置分工尚未取得原宿主完成状态");dependencies.put(resolveTeamArtifact(call.teamRunId,ref.getString("assignmentId"),ref.getString("memberId"),ref.getString("taskId"),ref.getString("runId"),ref.getString("artifactId"),sourceRead));}call.checkCurrent();
        JSONObject identity=new JSONObject().put("teamRunId",call.teamRunId).put("assignmentId",call.assignmentId).put("assignmentRevision",1).put("startedRevision",0).put("dependencyArtifacts",dependencies).put("memberId",call.memberId).put("targetProjectId",scope.getString("targetProjectId")).put("files",call.files());
        if(!call.workItemId.isEmpty()){JSONObject item=call.workItem();for(String key:new String[]{"workItemId","itemRevision","ownerGeneration"})identity.put(key,item.get(key));}
        if(call.workItemId.isEmpty())try(Cursor rows=db.rawQuery("SELECT task_id,document FROM tasks",null)){while(rows.moveToNext()){JSONObject existing=new JSONObject(rows.getString(1)),origin=existing.optJSONObject("origin");if(origin!=null&&origin.optString("kind").equals("team_assignment")&&call.teamRunId.equals(origin.optString("teamRunId"))&&call.memberId.equals(origin.optString("memberId"))&&!call.assignmentId.equals(rows.getString(0)))throw new SecurityException("该成员已有原assignment，只能回到原编号继续");}}
        if(call.scope().getJSONArray("assignments")!=null){JSONArray remoteSelections=call.scope().getJSONArray("assignments");for(int n=0;n<remoteSelections.length();n++){JSONObject item=remoteSelections.getJSONObject(n);if(call.memberId.equals(item.getString("memberId"))&&item.optJSONObject("remoteSelection")!=null)identity.put("remoteSelection",new JSONObject(item.getJSONObject("remoteSelection").toString()));}}
        if(call.projectSelection()!=null)identity.put("projectSelection",call.projectSelection());
        JSONObject previous=read(call.assignmentId);if(previous!=null){JSONObject old=previous.getJSONObject("teamAssignment");if(identity.has("remoteSelection")!=old.has("remoteSelection")||identity.has("remoteSelection")&&!canonical(identity.getJSONObject("remoteSelection")).equals(canonical(old.getJSONObject("remoteSelection"))))throw new SecurityException("原assignment的native远端选择不可被新参数替换");for(String key:new String[]{"teamRunId","assignmentId","memberId","targetProjectId","files"})if(!canonical(identity.get(key)).equals(canonical(old.get(key))))throw new SecurityException("稳定assignment编号已经属于另一原任务或文件范围");if(!canonical(dependencies).equals(canonical(old.optJSONArray("dependencyArtifacts")==null?new JSONArray():old.getJSONArray("dependencyArtifacts")))){if(!old.has("revisionRequestId")||old.optInt("assignmentRevision",1)<=old.optInt("startedRevision",1))throw new SecurityException("原前置成果已更新，请主人请求原分工的新修订后继续");old.put("dependencyArtifacts",dependencies);previous.put("teamAssignment",old);write(previous);}teamAssignmentIdentity(new JSONObject(old.toString()).put("taskId",call.assignmentId));return get(call.assignmentId,false).put("created",false);}
        db.beginTransaction();try {
            JSONObject task=base(call.assignmentId,call.goal+"\n原分工："+call.assignment,"assistant","capabilities").put("conversationId","").put("parentTaskId",team.getString("id")).put("status","queued").put("teamAssignment",identity)
                .put("origin",new JSONObject().put("kind","team_assignment").put("teamRunId",call.teamRunId).put("assignmentId",call.assignmentId).put("memberId",call.memberId));
            if(!call.workItemId.isEmpty()){
                JSONObject item=currentTeamWorkItem(team,call.workItem()),pending=item.optJSONObject("parentUnstartedContinuation");
                if(pending!=null){if(!dev.pocketengine.features.teams.TeamWorkItems.sameIdentity(item,pending)||pending.getInt("runGeneration")!=call.runGeneration||pending.getInt("memberGeneration")!=call.memberGeneration)throw new SecurityException("未开始项的原单次继续claim不属于本generation");task.put("teamDraftContinuation",new JSONObject(pending.toString()));item.remove("parentUnstartedContinuation");}
                item.put("status","running").put("taskId",call.assignmentId);
            }write(task);JSONArray links=team.optJSONArray("relatedTasks");if(links==null)links=new JSONArray();if(links.length()>=32)throw new IllegalStateException("原团队child任务已达上限");links.put(new JSONObject().put("taskId",call.assignmentId).put("feature","team_assignment").put("assignmentId",call.assignmentId));team.put("relatedTasks",links);write(team);db.setTransactionSuccessful();return get(call.assignmentId,false).put("created",true);
        }finally{db.endTransaction();}
    }
    public synchronized JSONObject startTeamAssignment(dev.pocketengine.features.teams.TeamCoordinator.AssignmentCall call,RunControl original,int allocation)throws Exception {
        JSONObject task=registerTeamAssignment(call);if(hasUnresolved(call.assignmentId)||task.optString("status").matches("running|preparing|stopping|completed")||teamHasPendingQuestion(task)||task.optBoolean("cancelRequested")&&!call.ownerContinuation)return new JSONObject().put("inspectOnly",true).put("taskId",call.assignmentId);
        if(!task.optString("status").matches("queued|paused|awaiting_user|needs_confirmation|interrupted|failed|cancelled"))throw new SecurityException("原分工状态不允许继续");
        if(task.optBoolean("cancelRequested")){task.put("cancelRequested",false);appendEvent(task,new JSONObject().put("type","team_owner_continuation").put("assignmentId",call.assignmentId).put("message","主人在原团队审阅后明确继续此原assignment；旧停止与操作回执保留，未知效果未重放"));}
        JSONObject identity=task.getJSONObject("teamAssignment");identity.put("startedRevision",identity.optInt("assignmentRevision",1));task.put("teamAssignment",identity);write(task);JSONObject attempt=startAttempt(call.assignmentId,"capabilities",allocation,true);original.bindHostRun(call.assignmentId,attempt.getString("runId"),attempt.getInt("coordinatorEpoch"));
        JSONArray assignmentTools=new JSONArray().put("team_prepare_draft").put("team_list_files").put("team_read_file").put("team_read_dependency").put("team_send_message").put("team_request_input").put("team_freeze_outputs");if(identity.has("remoteSelection")){for(int a=0;a<assignmentTools.length();a++)if(assignmentTools.getString(a).equals("team_freeze_outputs")){assignmentTools.remove(a);break;}assignmentTools.put("team_query_remote_job");for(TeamRemoteAction action:new TeamRemoteAction[]{TeamRemoteAction.PREPARE,TeamRemoteAction.SUBMIT,TeamRemoteAction.CANCEL,TeamRemoteAction.FREEZE,TeamRemoteAction.IMPORT})assignmentTools.put(teamRemoteTool(action));}else assignmentTools.put("team_write_file");authorizeTools(call.assignmentId,attempt.getString("runId"),assignmentTools);
        JSONObject result=new JSONObject(attempt.getJSONObject("teamAssignment").toString()).put("runGeneration",call.runGeneration).put("memberGeneration",call.memberGeneration).put("taskId",call.assignmentId).put("runId",attempt.getString("runId")).put("coordinatorEpoch",attempt.getInt("coordinatorEpoch")).put("stepCeiling",attempt.getInt("stepCeiling"));
        JSONObject nativeTask=required(call.assignmentId);nativeTask.put("teamAssignmentNativeRun",new JSONObject().put("runId",attempt.getString("runId")).put("runGeneration",call.runGeneration).put("memberGeneration",call.memberGeneration));write(nativeTask);if(nativeTask.has("teamProject"))authorizeTeamProjectTools(call.assignmentId,attempt.getString("runId"));
        String continuationField=task.optJSONObject("teamRemoteContinuation")!=null&&!task.getJSONObject("teamRemoteContinuation").has("runId")?"teamRemoteContinuation":task.optJSONObject("teamDraftContinuation")!=null&&!task.getJSONObject("teamDraftContinuation").has("runId")?"teamDraftContinuation":"teamAnswerContinuation";JSONObject continuation=task.optJSONObject(continuationField);if(continuation!=null&&!continuation.has("runId")){db.beginTransaction();try{JSONObject owner=teamPoolOwnerForChild(task),pool=teamBudget(owner);JSONArray rows=pool.getJSONArray("reservations");for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);if(continuation.getString("reservationId").equals(row.getString("reservationId"))&&row.getString("state").equals("reserved")){if(continuationField.equals("teamRemoteContinuation")){if(continuation.getInt("runGeneration")!=call.runGeneration||continuation.getInt("memberGeneration")!=call.memberGeneration)throw new SecurityException("原native续接计划generation不符");row.put("childRunId",attempt.getString("runId")).put("childEpoch",attempt.getInt("coordinatorEpoch"));}row.put("continuationRunId",attempt.getString("runId"));result.put("continuationReservationId",row.getString("reservationId"));}}continuation.put("runId",attempt.getString("runId"));JSONObject latest=required(call.assignmentId);latest.put(continuationField,continuation);write(latest);owner.put("teamReservationPool",pool);write(owner);db.setTransactionSuccessful();}finally{db.endTransaction();}}return result;
    }
    public synchronized void checkTeamAssignment(JSONObject binding,String tool,RunControl original)throws Exception {
        original.check();if(original.isPaused()||!original.matchesHostRun(binding.getString("taskId"),binding.getString("runId"),binding.getInt("coordinatorEpoch")))throw new SecurityException("原分工执行控制不符");checkTeamAssignmentCurrent(binding,tool);
    }
    /** Retained native read fence only; never calls RunControl.check recursively or claims an effect. */
    public synchronized void checkTeamAssignmentCurrent(JSONObject binding,String tool)throws Exception {
        JSONObject task=teamAssignmentIdentity(binding),team=teamWriteTask(binding.getString("teamRunId")),projectWork=currentTeamWorkItem(team,task.getJSONObject("teamAssignment"));
        if(teamProjectSelection(task)!=null){JSONObject plan=team.getJSONObject("teamWorkPlan");if(!plan.optString("state").equals("active")||plan.getLong("expiresAt")<=System.currentTimeMillis()||projectWork!=null&&(!projectWork.getJSONObject("draftScope").optString("state").equals("active")||projectWork.getJSONObject("draftScope").getLong("expiresAt")<=System.currentTimeMillis()))throw new SecurityException("原项目源码/命令scope已到期或撤销，停止派发；原实际效果保留核对");}
        if(task.getJSONObject("origin").optString("kind").equals("team_review"))checkTeamReviewCurrent(binding);
        checkCapabilityCurrent(binding.getString("taskId"),binding.getString("runId"),binding.getInt("coordinatorEpoch"),tool);
        JSONArray operations=get(binding.getString("taskId"),false).getJSONArray("operations");for(int i=0;i<operations.length();i++){JSONObject operation=operations.getJSONObject(i),pending=operation.optJSONObject("receipt");if(!operation.optString("state").equals("unknown")||pending==null||pending.optString("draftScopeGrantId").isEmpty())continue;JSONObject scope=teamWriteTask(binding.getString("teamRunId")).optJSONObject("teamDraftScope"),work=currentTeamWorkItem(teamWriteTask(binding.getString("teamRunId")),task.getJSONObject("teamAssignment"));if(work!=null)scope=work.getJSONObject("draftScope");if(scope==null||!scope.optString("grantId").equals(pending.getString("draftScopeGrantId"))||(work==null?teamDraftTarget(scope,task.getJSONObject("teamAssignment"),binding.getInt("runGeneration"),binding.getInt("memberGeneration"))==null:!scope.optString("state").equals("active")||scope.optLong("expiresAt")<=System.currentTimeMillis()))throw new SecurityException("本次隔离草稿授权已撤销或到期；已派发操作仍保留核对，不继续文件派发");}
        JSONObject ancestor=task;for(int depth=0;depth<16;depth++){requirePrivateOwner(ancestor);if(ancestor.optBoolean("cancelRequested")||ancestor.optString("status").matches("completed|stopping|cancelled"))throw new SecurityException("原分工或父任务已停止或已确认完成");String parent=ancestor.optString("parentTaskId");if(parent.isEmpty())return;ancestor=required(parent);}throw new SecurityException("原任务父链超限");
    }
    /** After the original Runner closed tools and emitted its terminal event: identity/readback only, no dispatch grant. */
    public synchronized void checkTeamAssignmentSettlement(JSONObject binding,RunControl original)throws Exception {
        original.check();if(original.isPaused()||!original.matchesHostRun(binding.getString("taskId"),binding.getString("runId"),binding.getInt("coordinatorEpoch")))throw new SecurityException("不是原assignment终态结算");
        JSONObject task=teamAssignmentIdentity(binding);if(!binding.getString("runId").equals(task.optString("runId"))||binding.getInt("coordinatorEpoch")!=task.optInt("coordinatorEpoch")||task.optBoolean("cancelRequested"))throw new SecurityException("原assignment终态身份已改变或已停止");JSONObject team=teamWriteTask(binding.getString("teamRunId"));checkTeamIntegrationParents(team);if(!team.getJSONObject("teamWriteScope").getString("parentRunId").equals(teamBudgetOwner(team).optString("runId")))throw new SecurityException("原父run已变化，不能用旧终态恢复完成状态");
    }
    /** Remote authoring scope covers only making its own selected-byte local draft, not phone authoring/freeze. */
    private JSONObject teamRemoteDraftGrant(JSONObject binding,String tool,JSONObject args,JSONObject capability)throws Exception {
        JSONObject task=teamAssignmentIdentity(binding);if(!task.getJSONObject("teamAssignment").has("remoteSelection"))return null;if(tool.matches("team_write_file|team_freeze_outputs"))throw new SecurityException("远端作者的本机正文/冻结只能来自原IMPORT；手机模型不能冒充远端作者");if(!tool.equals("team_prepare_draft"))return null;JSONObject permission=approvedTeamRemoteScope(task);if(args.length()!=0||!canonical(binding.getJSONArray("files")).equals(canonical(capability.getJSONArray("files"))))throw new SecurityException("只能准备原native scope选定文件的本机隔离草稿");JSONArray seeds=binding.optJSONArray("seedArtifacts");if(!canonical(seeds==null?new JSONArray():seeds).equals(canonical(capability.optJSONArray("seedArtifacts")==null?new JSONArray():capability.getJSONArray("seedArtifacts"))))throw new SecurityException("原草稿seed来源改变");return permission;
    }
    public synchronized JSONObject claimTeamFileOperation(JSONObject binding,String operation,String tool,JSONObject args,JSONObject capability,RunControl original)throws Exception {
        db.beginTransaction();try{
            checkTeamAssignment(binding,tool,original);JSONObject row=null;JSONArray operations=get(binding.getString("taskId"),false).getJSONArray("operations");
            for(int i=0;i<operations.length();i++){JSONObject candidate=operations.getJSONObject(i);if(operation.equals(candidate.getString("operationId")))row=candidate;else if(candidate.optString("state").matches("prepared|unknown"))throw new SecurityException("原分工还有其他未决操作");}
            if(row==null||!binding.getString("runId").equals(row.getString("runId"))||!tool.equals(row.getString("tool"))||!canonical(args).equals(canonical(row.getJSONObject("arguments"))))throw new SecurityException("不是原Runner登记的精确prepared操作");
            if(!row.getString("state").equals("prepared")){db.setTransactionSuccessful();return new JSONObject().put("dispatch",false).put("status","unknown").put("accepted",JSONObject.NULL).put("code","team_operation_unknown").put("message","原操作已经派发或待核对，未重新派发");}
            for(String key:new String[]{"teamRunId","assignmentId","memberId","targetProjectId"})if(!binding.getString(key).equals(capability.getString(key)))throw new SecurityException("原文件能力不属于该assignment");
            if(binding.optInt("assignmentRevision",1)!=capability.optInt("assignmentRevision",1))throw new SecurityException("原文件能力不属于本次assignment修订");
            JSONObject projectSelected=teamProjectSelection(required(binding.getString("taskId")));if(projectSelected!=null&&tool.equals("team_write_file"))throw new SecurityException("完整项目只能由原project工具修改实际副本，不能另写文字草稿冒充");if(projectSelected!=null&&tool.equals("team_freeze_outputs")){JSONObject capture=required(binding.getString("taskId")).getJSONObject("teamProjectFreezeCandidates").getJSONObject(operation);if(!binding.getString("runId").equals(capture.getString("runId"))||!capture.getString("captureHash").equals(capability.getString("projectCaptureHash")))throw new SecurityException("没有本prepared原生完整副本/同版检查回读");}
            boolean question=tool.matches("team_(request_input|send_message)");if(!tool.matches("team_(prepare_draft|write_file|request_input|send_message|freeze_outputs|integrate_file)"))throw new SecurityException("不是原团队文件副作用");
            JSONObject bound=teamBudgetBinding(binding),owner=teamBudgetOwner(teamWriteTask(binding.getString("teamRunId"))),pool=teamBudget(owner),parentGrant=grant(pool.getString("parentRunId"));
            if(parentGrant==null||parentGrant.optLong("expiresAt")<=System.currentTimeMillis()||parentGrant.optInt("remainingOperations")<=0||dev.pocketengine.features.teams.TeamParentBudget.remaining(pool,"toolOperations")<=0||pool.getJSONArray("reservations").length()>=(pool.getJSONObject("budgetSource").optBoolean("nativeProjectWorkPlan")?224:96)){db.setTransactionSuccessful();return new JSONObject().put("dispatch",false).put("ok",false).put("accepted",false).put("code","team_parent_budget_exhausted").put("requiresPause",true).put("message","原父任务实际剩余操作额度已用尽或到期；本prepared操作确定未派发，先审阅原计划预算，不需要核对不存在的副作用");}
            JSONObject remoteDraftScope=teamRemoteDraftGrant(binding,tool,args,capability);
            JSONObject draftScope=teamDraftGrant(binding,tool,args,capability);
            if(draftScope==null&&remoteDraftScope==null&&tool.matches("team_(prepare_draft|write_file|freeze_outputs)")&&(teamWriteTask(binding.getString("teamRunId")).optJSONObject("teamDraftScope")!=null||teamWriteTask(binding.getString("teamRunId")).optJSONObject("teamWorkPlan")!=null)){db.setTransactionSuccessful();return new JSONObject().put("dispatch",false).put("ok",false).put("accepted",false).put("code","team_draft_scope_required").put("requiresPause",true).put("message","原隔离草稿授权已过期、撤销、耗尽或不属于当前修订；请主人重新审阅，不自动续预算或授权");}
            if(!question&&draftScope==null&&remoteDraftScope==null){JSONObject approval=requireCapabilityApproval(binding.getString("taskId"),binding.getString("runId"),tool,args,capability);if(!approval.optBoolean("approved")){db.setTransactionSuccessful();return new JSONObject().put("dispatch",false).put("ok",false).put("accepted",false).put("code","approval_required").put("approvalId",approval.getString("approvalId")).put("approvalState",approval.getString("state")).put("message","请在原成员任务核对并批准本次精确文件操作，或在团队一次审阅隔离草稿范围；尚未派发");}}
            if(tool.equals("team_integrate_file")){JSONObject review=teamWriteTask(binding.getString("teamRunId")).getJSONObject("teamOutputReview");if(!review.getString("reviewId").equals(binding.getString("reviewId"))||!review.getString("planHash").equals(binding.getString("planHash"))||!review.getString("planHash").equals(capability.getString("planHash")))throw new SecurityException("唯一整合没有原主人审查");}
            checkTeamAssignment(binding,tool,original);
            if(draftScope!=null){draftScope.put("remainingOperations",draftScope.getInt("remainingOperations")-1);draftScope.getJSONArray("operationIds").put(operation);JSONObject draftTeam=teamWriteTask(binding.getString("teamRunId"));if(draftScope.optString("workItemId").isEmpty())draftTeam.put("teamDraftScope",draftScope);else currentTeamWorkItem(draftTeam,binding).put("draftScope",draftScope);write(draftTeam);}
            JSONObject reservation=dev.pocketengine.features.teams.TeamParentBudget.reserve(pool,bound,tool.equals("team_integrate_file")?"integration":"member","operation:"+operation,0,1);parentGrant.put("remainingOperations",parentGrant.getInt("remainingOperations")-1);ContentValues parentBudgetUpdate=new ContentValues();parentBudgetUpdate.put("grant_json",parentGrant.toString());db.update("runs",parentBudgetUpdate,"run_id=?",new String[]{pool.getString("parentRunId")});reservation.getJSONArray("operationIds").put(operation);reservation.put("state","unknown");owner.put("teamReservationPool",pool);write(owner);
            JSONObject pendingReceipt=new JSONObject().put("status","unknown").put("accepted",JSONObject.NULL).put("code","team_operation_pending").put("assignmentId",binding.getString("assignmentId")).put("capabilityFingerprint",capabilityFingerprint(capability));if(draftScope!=null)pendingReceipt.put("draftScopeGrantId",draftScope.getString("grantId"));
            ContentValues update=new ContentValues();update.put("state","unknown");update.put("receipt",pendingReceipt.toString());
            if(db.update("operations",update,"task_id=? AND run_id=? AND operation_id=? AND state='prepared'",new String[]{binding.getString("taskId"),binding.getString("runId"),operation})!=1)throw new SecurityException("原分工prepared操作已被消费");db.setTransactionSuccessful();return new JSONObject().put("dispatch",true);
        }finally{db.endTransaction();}
    }
    public synchronized JSONArray teamAssignmentReceipts(JSONObject binding,String tool)throws Exception {
        teamAssignmentIdentity(binding);JSONArray result=new JSONArray(),ops=get(binding.getString("taskId"),false).getJSONArray("operations");for(int i=0;i<ops.length();i++){JSONObject op=ops.getJSONObject(i);if((tool.equals(op.optString("tool"))||tool.equals("team_freeze_outputs")&&op.optString("tool").equals("team_import_remote_contribution"))&&op.optString("state").equals("receipt")&&op.optJSONObject("receipt")!=null){JSONObject receipt=tool.equals("team_freeze_outputs")?teamNativeArtifactReceipt(op):op.getJSONObject("receipt");if(receipt==null)continue;JSONObject draft=receipt.optJSONObject("teamDraft");int revision=draft==null?receipt.optInt("assignmentRevision",1):draft.optInt("assignmentRevision",1);if(revision==binding.optInt("assignmentRevision",1))result.put(new JSONObject(receipt.toString()));}}return result;
    }
    private static boolean teamHasPendingQuestion(JSONObject task)throws Exception {JSONArray rows=task.optJSONArray("teamQuestions");for(int i=0;rows!=null&&i<rows.length();i++)if(rows.getJSONObject(i).optString("state").equals("pending"))return true;return false;}
    public synchronized JSONObject askTeamAssignment(JSONObject binding,String operation,String question,RunControl original)throws Exception {
        checkTeamAssignment(binding,"team_request_input",original);JSONObject task=teamAssignmentIdentity(binding);boolean claimed=false;JSONArray ops=get(binding.getString("taskId"),false).getJSONArray("operations");for(int i=0;i<ops.length();i++){JSONObject op=ops.getJSONObject(i);if(operation.equals(op.getString("operationId"))&&binding.getString("runId").equals(op.getString("runId"))&&op.getString("tool").equals("team_request_input")&&op.getString("state").equals("unknown")&&question.equals(op.getJSONObject("arguments").getString("question")))claimed=true;}if(!claimed)throw new SecurityException("提问没有原已claim操作");
        JSONArray rows=task.optJSONArray("teamQuestions");if(rows==null)rows=new JSONArray();JSONObject existing=null;for(int i=0;i<rows.length();i++)if(operation.equals(rows.getJSONObject(i).getString("questionId")))existing=rows.getJSONObject(i);
        if(existing==null){if(rows.length()>=32)throw new IllegalStateException("原assignment提问已达上限");rows.put(new JSONObject().put("questionId",operation).put("assignmentId",binding.getString("assignmentId")).put("askedRunId",binding.getString("runId")).put("assignmentRevision",binding.optInt("assignmentRevision",1)).put("question",question).put("state","pending").put("askedAt",System.currentTimeMillis()));task.put("teamQuestions",rows);write(task);}
        return new JSONObject().put("ok",false).put("accepted",false).put("code","needs_user").put("questionId",operation).put("assignmentId",binding.getString("assignmentId")).put("assignmentRevision",binding.optInt("assignmentRevision",1)).put("message",question);
    }
    public synchronized JSONObject answerTeamAssignment(String teamRun,String assignment,String questionId,String answerId,String answer)throws Exception {
        JSONObject team=teamWriteTask(teamRun),task=required(assignment);requirePrivateOwner(task);JSONObject identity=task.getJSONObject("teamAssignment");if(!task.getJSONObject("origin").optString("kind").equals("team_assignment")||!teamRun.equals(identity.getString("teamRunId"))||!assignment.equals(identity.getString("assignmentId"))||!answerId.matches("[a-f0-9-]{36}")||answer.isBlank()||answer.length()>4000)throw new SecurityException("回答不属于原assignment问题");
        JSONArray questions=task.getJSONArray("teamQuestions");for(int i=0;i<questions.length();i++){JSONObject row=questions.getJSONObject(i);if(!questionId.equals(row.getString("questionId")))continue;
            if(!row.getString("state").equals("pending")){if(!answerId.equals(row.optString("answerId"))||!answer.equals(row.optString("answer")))throw new SecurityException("原问题已回答，不能替换答案");return new JSONObject(row.toString()).put("duplicate",true).put("awaitingContinuation",true);}
            boolean historic=task.optBoolean("cancelRequested")||team.optBoolean("cancelRequested")||row.optInt("assignmentRevision",1)!=identity.optInt("assignmentRevision",1);row.put("answerId",answerId).put("answer",answer).put("state",historic?"answered_historical":"answered").put("answeredAt",System.currentTimeMillis());task.put("teamQuestions",questions);write(task);return new JSONObject(row.toString()).put("awaitingContinuation",true).put("executionAuthorized",false);
        }throw new SecurityException("原assignment问题不存在；不会把回答路由到其他成员");
    }
    public synchronized JSONObject queueTeamAssignmentInput(JSONObject input)throws Exception {
        db.beginTransaction();try{JSONObject team=teamWriteTask(input.getString("runId")),task=required(input.getString("assignmentId"));requirePrivateOwner(task);JSONObject identity=task.getJSONObject("teamAssignment");
            if(!task.getJSONObject("origin").optString("kind").equals("team_assignment")||!team.getString("runId").equals(identity.getString("teamRunId"))||!input.getString("recipientMemberId").equals(identity.getString("memberId")))throw new SecurityException("消息不是原接收成员的assignment");
            String id=input.getString("messageId"),text=input.getString("text").trim(),mode=input.optString("mode","discussion"),quote=input.optString("quoteMessageId");if(!id.matches("[a-f0-9-]{36}")||text.isEmpty()||text.length()>4000||!mode.matches("discussion|queue"))throw new IllegalArgumentException("原群输入无效");
            JSONArray rows=task.optJSONArray("teamConversationInputs");if(rows==null)rows=new JSONArray();for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);if(!id.equals(row.getString("messageId")))continue;if(!text.equals(row.getString("content"))||!quote.equals(row.optString("quoteMessageId"))||!mode.equals(row.getString("mode")))throw new SecurityException("相同消息编号内容已改变");db.setTransactionSuccessful();return new JSONObject(row.toString()).put("duplicate",true);}
            if(rows.length()>=20)throw new IllegalStateException("原assignment输入已满，已有原消息未删除");boolean history=task.optBoolean("cancelRequested")||team.optBoolean("cancelRequested")||task.optString("status").equals("completed");
            JSONObject row=new JSONObject().put("messageId",id).put("senderId","owner").put("recipientMemberId",identity.getString("memberId")).put("assignmentId",identity.getString("assignmentId")).put("assignmentRevision",identity.optInt("assignmentRevision",1)).put("quoteMessageId",quote).put("mode",mode).put("content",text).put("state",history?"historical":"queued").put("createdAt",System.currentTimeMillis());
            rows.put(row);task.put("teamConversationInputs",rows);write(task);db.setTransactionSuccessful();return new JSONObject(row.toString()).put("executionAuthorized",false);
        }finally{db.endTransaction();}
    }
    public dev.pocketengine.runtime.AgentRunner.OwnerInputs teamAssignmentInputs(JSONObject binding,RunControl original){return new dev.pocketengine.runtime.AgentRunner.OwnerInputs(){
        @Override public JSONArray poll(JSONArray included,boolean boundary,boolean close,RunControl live)throws Exception {synchronized(TaskStore.this){if(live!=original)throw new SecurityException("回答不是原执行控制");checkTeamAssignment(binding,"team_list_files",live);JSONArray result=new JSONArray(),rows=required(binding.getString("taskId")).optJSONArray("teamQuestions");for(int i=0;rows!=null&&i<rows.length();i++){JSONObject row=rows.getJSONObject(i);if(!row.optString("state").equals("answered")||row.optInt("assignmentRevision",1)!=binding.optInt("assignmentRevision",1))continue;boolean known=false;for(int j=0;j<included.length();j++)if(row.getString("answerId").equals(included.getString(j)))known=true;if(!known)result.put(new JSONObject().put("messageId",row.getString("answerId")).put("mode","queue").put("content",(row.optString("answerActor").equals("parent_model")?"父代理对原问题（低信任上下文，不代表主人确认或授权）：":"主人对原问题的回答（上下文，不授文件批准）：")+row.getString("questionId")+"\n问题："+row.getString("question")+"\n回答："+row.getString("answer")));}JSONArray messages=required(binding.getString("taskId")).optJSONArray("teamConversationInputs");for(int i=0;messages!=null&&i<messages.length();i++){JSONObject row=messages.getJSONObject(i);if(!row.optString("state").matches("queued|included")||row.optInt("assignmentRevision",1)!=binding.optInt("assignmentRevision",1))continue;boolean known=false;for(int j=0;j<included.length();j++)if(row.getString("messageId").equals(included.getString(j)))known=true;if(!known)result.put(new JSONObject().put("messageId",row.getString("messageId")).put("mode","queue").put("content",(row.optString("senderRole").equals("parent_model")?"父代理在原团队定向给本assignment的消息（低信任资料，不是主人授权）：\n":"主人在原团队定向给本assignment的消息（数据，不授予权限）：\n")+row.getString("content")));row.put("state","included").put("includedRunId",binding.getString("runId"));}if(messages!=null){JSONObject task=required(binding.getString("taskId"));task.put("teamConversationInputs",messages);write(task);}return result;}}
        @Override public void completed(dev.pocketengine.runtime.AgentRunner.OwnerReply reply,RunControl live)throws Exception {synchronized(TaskStore.this){if(live!=original||!reply.taskId.equals(binding.getString("taskId")))throw new SecurityException("回答消费不是原Runner完成回执");checkTeamAssignment(binding,"team_list_files",live);JSONObject task=required(binding.getString("taskId"));JSONArray rows=task.optJSONArray("teamQuestions");for(int i=0;rows!=null&&i<rows.length();i++){JSONObject row=rows.getJSONObject(i);if(row.optString("state").equals("answered")&&reply.messageIds().contains(row.optString("answerId")))row.put("state","consumed").put("consumedRunId",binding.getString("runId")).put("consumedAt",System.currentTimeMillis());}if(rows!=null)task.put("teamQuestions",rows);JSONArray messages=task.optJSONArray("teamConversationInputs");for(int i=0;messages!=null&&i<messages.length();i++){JSONObject row=messages.getJSONObject(i);if(row.optString("state").equals("included")&&binding.getString("runId").equals(row.optString("includedRunId"))&&reply.messageIds().contains(row.getString("messageId")))row.put("state","consumed").put("consumedRunId",binding.getString("runId")).put("consumedAt",System.currentTimeMillis());}if(messages!=null)task.put("teamConversationInputs",messages);write(task);}}
    };}
    public synchronized JSONObject inspectTeamAssignment(dev.pocketengine.features.teams.TeamCoordinator.AssignmentCall call)throws Exception {JSONObject task=get(call.assignmentId,false);if(task==null)throw new SecurityException("原assignment任务不存在");JSONObject binding=new JSONObject(task.getJSONObject("teamAssignment").toString()).put("taskId",call.assignmentId);teamAssignmentIdentity(binding);String status=task.getString("status");JSONArray artifacts=new JSONArray(),frozen=teamAssignmentReceipts(binding,"team_freeze_outputs");for(int i=0;i<frozen.length();i++)if(frozen.getJSONObject(i).optJSONArray("teamArtifacts")!=null)artifacts=frozen.getJSONObject(i).getJSONArray("teamArtifacts");
        if(hasUnresolved(call.assignmentId))status="blocked_unknown";else if(teamHasPendingQuestion(task))status="awaiting_input";else{JSONArray approvals=task.optJSONArray("approvals");for(int i=0;approvals!=null&&i<approvals.length();i++){JSONObject approval=approvals.getJSONObject(i);if(task.optString("runId").equals(approval.optString("runId"))&&approval.optString("state").equals("pending")&&approval.optLong("expiresAt")>System.currentTimeMillis())status="awaiting_approval";}}
        if(status.equals("completed")&&frozen.length()==0)status="awaiting_user";return new JSONObject().put("assignmentId",call.assignmentId).put("memberId",call.memberId).put("teamRunId",call.teamRunId).put("taskId",call.assignmentId).put("runId",task.optString("runId")).put("status",status).put("message",task.optString("summary")).put("artifacts",artifacts).put("questions",task.optJSONArray("teamQuestions")==null?new JSONArray():task.getJSONArray("teamQuestions")).put("messages",task.optJSONArray("teamConversationInputs")==null?new JSONArray():task.getJSONArray("teamConversationInputs")).put("parentBudget",teamBudgetSnapshot(call.teamRunId)).put("businessVerified",false);
    }
    public synchronized JSONObject resolveTeamArtifact(String teamRunId,String assignmentId,String memberId,String taskId,String frozenRunId,String artifactId,RunControl readControl)throws Exception {
        readControl.check();teamWriteTask(teamRunId);JSONObject task=get(taskId,false);if(task==null)throw new SecurityException("原成员任务不存在");requirePrivateOwner(task);JSONObject identity=task.getJSONObject("teamAssignment");if(!task.getJSONObject("origin").optString("kind").equals("team_assignment")||!taskId.equals(assignmentId)||!assignmentId.equals(identity.getString("assignmentId"))||!memberId.equals(identity.getString("memberId"))||!teamRunId.equals(identity.getString("teamRunId")))throw new SecurityException("原成果不属于所选成员分工");
        JSONObject origin=task.getJSONObject("origin");if(!teamRunId.equals(origin.getString("teamRunId"))||!assignmentId.equals(origin.getString("assignmentId"))||!memberId.equals(origin.getString("memberId")))throw new SecurityException("原成果的稳定来源与assignment冲突");
        JSONObject found=null;JSONArray ops=task.getJSONArray("operations");for(int i=0;i<ops.length();i++){JSONObject op=ops.getJSONObject(i);if(!op.optString("state").equals("receipt")||!op.optString("tool").matches("team_freeze_outputs|team_import_remote_contribution")||!frozenRunId.equals(op.optString("runId")))continue;JSONObject receipt=teamNativeArtifactReceipt(op);JSONArray refs=receipt==null?null:receipt.optJSONArray("teamArtifacts");for(int j=0;refs!=null&&j<refs.length();j++){JSONObject ref=refs.getJSONObject(j);if(artifactId.equals(ref.optString("artifactId"))){for(String key:new String[]{"teamRunId","assignmentId","memberId","targetProjectId"})if(!identity.getString(key).equals(ref.getString(key)))throw new SecurityException("原成果收据的分工或项目冲突");if(!taskId.equals(ref.getString("taskId"))||!frozenRunId.equals(ref.getString("runId")))throw new SecurityException("原成果执行代次冲突");if(found!=null&&!canonical(found).equals(canonical(ref)))throw new SecurityException("原成果有多个冲突回执");found=new JSONObject(ref.toString());}}}
        readControl.check();if(found==null)throw new SecurityException("原成果尚未回到原成员TaskStore收据");return found;
    }
    public synchronized void checkTeamArtifact(JSONObject exactRef,RunControl readControl)throws Exception {JSONObject actual=resolveTeamArtifact(exactRef.getString("teamRunId"),exactRef.getString("assignmentId"),exactRef.getString("memberId"),exactRef.getString("taskId"),exactRef.getString("runId"),exactRef.getString("artifactId"),readControl);if(!canonical(actual).equals(canonical(exactRef)))throw new SecurityException("原成果收据身份已变化");}
    public synchronized JSONArray teamAssignmentsForReview(String teamRun,RunControl readControl)throws Exception {
        readControl.check();JSONObject team=teamWriteTask(teamRun);boolean workItems=team.optJSONObject("teamWorkPlan")!=null;JSONArray result=new JSONArray(),scope=workItems?team.getJSONObject("teamWorkPlan").getJSONArray("items"):team.getJSONObject("teamWriteScope").getJSONArray("assignments");
        for(int member=0;member<scope.length();member++){String memberId=scope.getJSONObject(member).getString("memberId");JSONObject selected=null;
            try(Cursor rows=db.rawQuery("SELECT task_id,document FROM tasks",null)){while(rows.moveToNext()){JSONObject task=new JSONObject(rows.getString(1)),origin=task.optJSONObject("origin");if(origin!=null&&origin.optString("kind").equals("team_assignment")&&teamRun.equals(origin.optString("teamRunId"))&&memberId.equals(origin.optString("memberId"))&&(!workItems||scope.getJSONObject(member).getString("assignmentId").equals(rows.getString(0)))){if(selected!=null)throw new SecurityException("原成员有多个assignment，必须先核对");selected=get(rows.getString(0),false);}}}
            if(selected==null){result.put(new JSONObject().put("memberId",memberId).put("assignmentId",workItems?scope.getJSONObject(member).getString("assignmentId"):"").put("taskId","").put("status","not_started").put("artifacts",new JSONArray()));continue;}
            JSONObject binding=new JSONObject(selected.getJSONObject("teamAssignment").toString()).put("taskId",selected.getString("id"));teamAssignmentIdentity(binding);JSONArray frozen=teamAssignmentReceipts(binding,"team_freeze_outputs"),refs=new JSONArray();for(int i=0;i<frozen.length();i++)if(frozen.getJSONObject(i).optJSONArray("teamArtifacts")!=null)refs=frozen.getJSONObject(i).getJSONArray("teamArtifacts");JSONArray verified=new JSONArray();
            for(int i=0;i<refs.length();i++){JSONObject ref=refs.getJSONObject(i);verified.put(resolveTeamArtifact(teamRun,binding.getString("assignmentId"),memberId,selected.getString("id"),ref.getString("runId"),ref.getString("artifactId"),readControl));}
            result.put(new JSONObject().put("memberId",memberId).put("assignmentId",binding.getString("assignmentId")).put("taskId",selected.getString("id")).put("status",selected.getString("status")).put("artifacts",verified));
        }readControl.check();return result;
    }
    public synchronized JSONObject teamWriteScopeForOwner(String teamRun,RunControl readControl)throws Exception {readControl.check();return new JSONObject(teamWriteTask(teamRun).getJSONObject("teamWriteScope").toString());}
    /** Original native task sheet control/decision fence, not a new tool grant. */
    public synchronized JSONObject requireTeamOwnerAttempt(JSONObject input)throws Exception {JSONObject task=required(input.getString("taskId"));requirePrivateOwner(task);JSONObject origin=task.optJSONObject("origin");if(origin==null||!origin.optString("kind").matches("team_assignment|team_integration|team_review")||!input.optString("runId").equals(task.optString("runId"))||input.optInt("epoch",-1)!=task.optInt("coordinatorEpoch"))throw new SecurityException("请刷新原团队分工的当前执行，再处理本次操作");return get(task.getString("id"),false);}
    /** Explicit owner revision, not a write grant: stable assignmentId, new draft only after original Runner approval. */
    public synchronized JSONObject reviseTeamAssignment(JSONObject input)throws Exception {db.beginTransaction();try{JSONObject result=reviseTeamAssignment(input,null);db.setTransactionSuccessful();return result;}finally{db.endTransaction();}}
    public synchronized JSONObject reviseTeamAssignmentFromOutcome(JSONObject input,dev.pocketengine.features.teams.TeamWriteScope nativeScope)throws Exception {db.beginTransaction();try{JSONObject result=reviseTeamAssignment(input,java.util.Objects.requireNonNull(nativeScope));db.setTransactionSuccessful();return result;}finally{db.endTransaction();}}
    private JSONObject reviseTeamAssignment(JSONObject input,dev.pocketengine.features.teams.TeamWriteScope continuedScope)throws Exception {return reviseTeamAssignment(input,continuedScope,false);}
    private JSONObject reviseTeamAssignment(JSONObject input,dev.pocketengine.features.teams.TeamWriteScope continuedScope,boolean nativeRemoteRevision)throws Exception {
        String run=input.getString("runId"),id=input.getString("assignmentId"),request=input.getString("revisionRequestId");JSONObject team=teamWriteTask(run),task=required(id);requirePrivateOwner(task);JSONObject identity=task.getJSONObject("teamAssignment");
        if(!task.getJSONObject("origin").optString("kind").equals("team_assignment")||!run.equals(identity.getString("teamRunId"))||!input.getString("memberId").equals(identity.getString("memberId"))||!request.matches("[a-f0-9-]{36}")||input.getString("text").codePoints().allMatch(Character::isWhitespace)||input.getString("text").length()>4000)throw new SecurityException("修订不是原主人选择的assignment");
        if(identity.has("remoteSelection")&&!nativeRemoteRevision)throw new SecurityException("远端修订必须由原前台私构RevisionReview核V1贡献与新scope，普通JSON不能重用旧选择");
        if(request.equals(identity.optString("revisionRequestId")))return new JSONObject(identity.toString()).put("known",true);
        boolean workingContinuation=team.optJSONObject("teamOutputReview")!=null&&identity.optString("outcomeContinuationReviewId").equals(team.getJSONObject("teamOutputReview").optString("reviewId"));
        if(team.has("teamOutputReview")&&continuedScope==null&&!workingContinuation||team.optBoolean("cancelRequested")||team.optString("status").matches("completed|cancelled|stopping")||hasUnresolved(id)||teamHasPendingQuestion(task)||!task.optString("status").matches("completed|failed|awaiting_user|needs_confirmation|interrupted"))throw new SecurityException("原分工仍在运行、待核对或已批准整合，不能另开修订");
        try(Cursor active=db.rawQuery("SELECT 1 FROM runs WHERE task_id=? AND status='running' LIMIT 1",new String[]{id})){if(active.moveToFirst())throw new SecurityException("原assignment执行尝试尚未结束");}
        JSONArray ops=get(id,false).getJSONArray("operations"),frozen=null;String frozenRun="";for(int i=0;i<ops.length();i++){JSONObject op=ops.getJSONObject(i),receipt=teamNativeArtifactReceipt(op);if(op.optString("state").equals("receipt")&&op.optString("tool").matches("team_freeze_outputs|team_import_remote_contribution")&&receipt!=null&&receipt.optInt("assignmentRevision",1)==identity.optInt("assignmentRevision",1)&&receipt.optJSONArray("teamArtifacts")!=null){frozen=receipt.getJSONArray("teamArtifacts");frozenRun=op.getString("runId");}}
        if(frozen==null||!frozenRun.equals(input.getString("expectedFrozenRunId"))||!capabilityFingerprint(new JSONObject().put("artifacts",frozen)).equals(input.getString("expectedArtifactsHash")))throw new SecurityException("原冻结成果已改变，请重新查看后请求修订");
        if(continuedScope!=null){JSONObject next=continuedScope.snapshot(),previous=team.getJSONObject("teamWriteScope"),context=teamIntegrationContext(run,new RunControl()),source=next.getJSONObject("outcomeContinuation");
            if(context==null||context.optBoolean("activeAttempt")||context.optBoolean("hasUnresolved")||!context.optString("status").equals("completed")||!input.getString("memberId").equals(source.getString("memberId"))||!context.getString("reviewId").equals(source.getString("reviewId"))||!context.getString("planHash").equals(source.getString("planHash")))throw new SecurityException("成果继续不是原已完成整合的精确版本");
            for(String key:new String[]{"parentTaskId","parentRunId","targetProjectId","definitionHash","initialBudget"})if(!canonical(previous.get(key)).equals(canonical(next.get(key))))throw new SecurityException("成果继续不能改变原范围或预算");
            JSONArray oldAssignments=previous.getJSONArray("assignments"),newAssignments=next.getJSONArray("assignments");if(oldAssignments.length()!=newAssignments.length())throw new SecurityException("不能增删原成员");JSONArray selected=null;
            String workItemId=identity.optString("workItemId");if(!workItemId.isEmpty()&&(!workItemId.equals(input.optString("workItemId"))||!workItemId.equals(source.optString("workItemId"))))throw new SecurityException("动态修订必须精确原workItem，不按成员最近assignment猜测");
            for(int m=0;m<oldAssignments.length();m++){
                JSONObject before=oldAssignments.getJSONObject(m),after=newAssignments.getJSONObject(m);if(!before.getString("memberId").equals(after.getString("memberId"))||!before.optString("workItemId").equals(after.optString("workItemId")))throw new SecurityException("不能改派原成员或工作项");
                boolean target=workItemId.isEmpty()?input.getString("memberId").equals(before.getString("memberId")):workItemId.equals(before.optString("workItemId"));if(!target){if(!canonical(before).equals(canonical(after)))throw new SecurityException("不能改变其他工作项文件范围");continue;}
                JSONArray oldFiles=before.getJSONArray("files"),newFiles=after.getJSONArray("files");if(oldFiles.length()!=newFiles.length())throw new SecurityException("不能扩原文件范围");
                for(int f=0;f<oldFiles.length();f++){
                    JSONObject oldFile=oldFiles.getJSONObject(f),newFile=newFiles.getJSONObject(f);if(!oldFile.getString("path").equals(newFile.getString("path")))throw new SecurityException("不能换写入路径");JSONObject applied=null;
                    if(!workItemId.isEmpty()){for(int a=0;a<frozen.length();a++)if(oldFile.getString("path").equals(frozen.getJSONObject(a).getString("path")))applied=teamAppliedArtifact(context,frozen.getJSONObject(a));if(applied==null)throw new SecurityException("工作项新基线必须来自原唯一整合者已应用的精确冻结成果");}
                    else{JSONArray receipts=context.getJSONArray("receipts");for(int r=0;r<receipts.length();r++){JSONObject receipt=receipts.getJSONObject(r),saved=receipt.optJSONObject("teamIntegrated");if(receipt.optBoolean("ok")&&saved!=null&&source.getString("planHash").equals(saved.optString("planHash"))&&oldFile.getString("path").equals(saved.optString("path")))applied=saved;}}
                    int version=applied==null?oldFile.getInt("baseVersion"):applied.getInt("version");String hash=applied==null?oldFile.getString("baseHash"):applied.getString("sha256");if(newFile.getInt("baseVersion")!=version||!newFile.getString("baseHash").equals(hash))throw new SecurityException("当前文件不是原整合回执精确版本，不能沿旧成果覆盖");
                }selected=newFiles;
            }
            if(selected==null)throw new SecurityException("原成果成员不存在");identity.put("files",selected).put("outcomeContinuationReviewId",source.getString("reviewId"));team.put("teamWriteScope",next);write(team);
        }
        if(team.optJSONObject("teamIndependentReview")!=null){team.getJSONObject("teamIndependentReview").put("stale",true);write(team);}
        int revision=Math.addExact(identity.optInt("assignmentRevision",1),1);if(revision>8)throw new IllegalStateException("原assignment已达8次修订上限，请完成并核对已有工作");identity.put("assignmentRevision",revision).put("seedArtifacts",new JSONArray(frozen.toString())).put("revisionRequestId",request);task.put("teamAssignment",identity).put("status","awaiting_user").put("feedback",input.getString("text")).put("summary","原assignment新修订已保存，旧成果保留；等待主人明确继续并批准新草稿");write(task);return new JSONObject(identity.toString()).put("executionAuthorized",false);
    }
    /** Native readback context for the existing unique integration task, never accepted from tool JSON. */
    public synchronized JSONObject teamIntegrationContext(String teamRun,RunControl ownerRead)throws Exception {
        ownerRead.check();JSONObject team=teamWriteTask(teamRun);String id=UUID.nameUUIDFromBytes(("team-integration:"+teamRun).getBytes(StandardCharsets.UTF_8)).toString();JSONObject task=get(id,false);if(task==null)return null;
        JSONObject identity=task.getJSONObject("teamAssignment"),binding=new JSONObject(identity.toString()).put("taskId",id);teamAssignmentIdentity(binding);JSONObject review=team.getJSONObject("teamOutputReview");
        if(!task.getJSONObject("origin").optString("kind").equals("team_integration")||!teamRun.equals(identity.getString("teamRunId"))||!review.getString("reviewId").equals(identity.getString("reviewId"))||!review.getString("planHash").equals(identity.getString("planHash"))||!canonical(review.getJSONObject("plan")).equals(canonical(identity.getJSONObject("plan"))))throw new SecurityException("原唯一整合任务与主人当前审查不一致");
        boolean active;try(Cursor rows=db.rawQuery("SELECT 1 FROM runs WHERE task_id=? AND status='running' LIMIT 1",new String[]{id})){active=rows.moveToFirst();}
        return new JSONObject().put("teamRunId",teamRun).put("targetProjectId",identity.getString("targetProjectId")).put("taskId",id).put("reviewId",identity.getString("reviewId")).put("planHash",identity.getString("planHash")).put("status",task.getString("status")).put("activeAttempt",active).put("hasUnresolved",hasUnresolved(id)).put("receipts",teamAssignmentReceipts(binding,"team_integrate_file")).put("binding",binding.put("runId",task.optString("runId")).put("coordinatorEpoch",task.optInt("coordinatorEpoch")));
    }
    /** A new owner review cannot revive a stopped or owner-confirmed parent/team task. */
    private void checkTeamIntegrationParents(JSONObject team)throws Exception {
        JSONObject ancestor=team;for(int depth=0;depth<16;depth++){requirePrivateOwner(ancestor);if(ancestor.optBoolean("cancelRequested")||ancestor.optString("status").matches("completed|cancelled|stopping"))throw new SecurityException("原团队或父任务已停止或已确认完成，不能批准新的整合");String parent=ancestor.optString("parentTaskId");if(parent.isEmpty())return;ancestor=required(parent);}throw new SecurityException("原任务父链超限");
    }
    public synchronized JSONObject teamReviewState(String teamRun)throws Exception {JSONObject team=teamWriteTask(teamRun),result=new JSONObject();if(team.has("teamIndependentReview"))result.put("independentReview",new JSONObject(team.getJSONObject("teamIndependentReview").toString()));if(team.has("teamOutputReview"))result.put("approvedReview",new JSONObject(team.getJSONObject("teamOutputReview").toString()));return result;}
    private void checkTeamReviewCurrent(JSONObject binding)throws Exception {
        JSONArray assignments=teamAssignmentsForReview(binding.getString("teamRunId"),new RunControl()),current=new JSONArray();
        for(int i=0;i<assignments.length();i++){JSONObject assignment=assignments.getJSONObject(i);if(!assignment.optString("status").equals("completed"))throw new SecurityException("原作者修订尚未冻结，独审失效");JSONArray refs=assignment.getJSONArray("artifacts");for(int j=0;j<refs.length();j++)current.put(refs.getJSONObject(j));}
        JSONArray expected=new JSONArray(),files=binding.getJSONObject("plan").getJSONArray("files");for(int i=0;i<files.length();i++){JSONObject ref=new JSONObject(files.getJSONObject(i).toString());ref.remove("expectedVersion");ref.remove("expectedHash");expected.put(ref);}
        if(!canonical(current).equals(canonical(expected)))throw new SecurityException("原作者成果版本已变化，旧独审不能继续或批准新版本");
    }
    public synchronized JSONObject startTeamReview(String teamRun,dev.pocketengine.features.teams.TeamWorkReview.Prepared nativeReview,String expectedHash,RunControl original,int allocation)throws Exception {
        db.beginTransaction();try{
            JSONObject team=teamWriteTask(teamRun),review=nativeReview.snapshot(),plan=review.getJSONObject("plan");checkTeamIntegrationParents(team);
            if(!expectedHash.equals(review.getString("planHash"))||!expectedHash.equals(capabilityFingerprint(plan))||!teamRun.equals(plan.getString("teamRunId"))||!review.optBoolean("canIntegrate"))throw new SecurityException("独审必须读取当前完整冻结计划");
            String id=UUID.nameUUIDFromBytes(("team-review:"+teamRun+":"+expectedHash).getBytes(StandardCharsets.UTF_8)).toString();JSONObject existing=read(id);
            if(existing!=null&&(!existing.getJSONObject("origin").optString("kind").equals("team_review")||!expectedHash.equals(existing.getJSONObject("teamAssignment").optString("planHash"))))throw new SecurityException("原独审编号属于不同执行来源");
            if(existing!=null&&existing.optString("status").equals("completed")){db.setTransactionSuccessful();return new JSONObject(team.getJSONObject("teamIndependentReview").toString()).put("taskId",id).put("inspectOnly",true);}
            if(existing!=null&&(existing.optString("status").matches("preparing|running|stopping")||hasUnresolved(id)||existing.optBoolean("cancelRequested")))throw new SecurityException("原独审仍活动、已停止或待核对，不能重开");
            JSONObject identity=new JSONObject().put("teamRunId",teamRun).put("assignmentId",id).put("memberId","reviewer").put("targetProjectId",plan.getString("targetProjectId")).put("files",plan.getJSONArray("files")).put("plan",plan).put("planHash",expectedHash);checkTeamReviewCurrent(identity);
            if(existing==null){JSONObject task=base(id,"独立审查原冻结成果 "+expectedHash,"assistant","capabilities").put("conversationId","").put("parentTaskId",team.getString("id")).put("status","queued").put("teamAssignment",identity).put("origin",new JSONObject().put("kind","team_review").put("teamRunId",teamRun).put("assignmentId",id).put("memberId","reviewer"));write(task);JSONArray links=team.optJSONArray("relatedTasks");if(links==null)links=new JSONArray();if(links.length()>=32)throw new IllegalStateException("原团队child任务已满");links.put(new JSONObject().put("taskId",id).put("feature","team_review").put("assignmentId",id));team.put("relatedTasks",links);write(team);}
            JSONObject attempt=startAttempt(id,"capabilities",allocation,true);original.bindHostRun(id,attempt.getString("runId"),attempt.getInt("coordinatorEpoch"));authorizeTools(id,attempt.getString("runId"),new JSONArray().put("team_review_manifest").put("team_read_review_file").put("team_submit_review"));
            db.setTransactionSuccessful();return new JSONObject(identity.toString()).put("taskId",id).put("runId",attempt.getString("runId")).put("coordinatorEpoch",attempt.getInt("coordinatorEpoch")).put("stepCeiling",attempt.getInt("stepCeiling"));
        }finally{db.endTransaction();}
    }
    public synchronized JSONObject finishTeamReview(JSONObject binding,dev.pocketengine.core.RunOutcome outcome,dev.pocketengine.features.teams.TeamFrozenReview.Proof nativeProof,RunControl original)throws Exception {
        db.beginTransaction();try{checkTeamAssignmentSettlement(binding,original);
            JSONObject task=teamAssignmentIdentity(binding),proof=nativeProof.snapshot();if(!binding.getString("runId").equals(task.optString("runId"))||binding.getInt("coordinatorEpoch")!=task.optInt("coordinatorEpoch"))throw new SecurityException("旧独审结算不能覆盖新attempt");for(String key:new String[]{"taskId","runId","teamRunId","planHash"})if(!binding.getString(key).equals(proof.getString(key)))throw new SecurityException("独审阅读证据不是原执行");
            checkTeamReviewCurrent(binding);JSONObject evidence=new JSONObject(outcome.evidence.toString()).put("teamIndependentReview",proof).put("reviewText",outcome.summary);
            boolean complete=proof.optBoolean("completeOriginalFilesRead")&&proof.optBoolean("verdictSubmitted")&&outcome.status.equals("needs_confirmation")&&!task.optBoolean("cancelRequested");String status=complete?"completed":outcome.status;
            endAttempt(binding.getString("taskId"),binding.getString("runId"),status,outcome.summary,evidence);JSONObject receipt=new JSONObject(proof.toString()).put("status",status).put("summary",outcome.summary).put("reviewerKind","independent_original_runner").put("executionAuthorized",false).put("businessVerified",false);
            JSONObject team=teamWriteTask(binding.getString("teamRunId"));receipt.put("parentBudget",teamBudgetSnapshot(binding.getString("teamRunId")));team.put("teamIndependentReview",receipt);write(team);db.setTransactionSuccessful();return receipt;
        }finally{db.endTransaction();}
    }
    public synchronized JSONObject approveTeamOutputs(String teamRun,dev.pocketengine.features.teams.TeamWorkReview.Prepared prepared,String expectedHash,RunControl ownerRead)throws Exception {
        db.beginTransaction();try {
            ownerRead.check();JSONObject team=teamWriteTask(teamRun),review=prepared.snapshot(),plan=review.getJSONObject("plan");checkTeamIntegrationParents(team);
            JSONObject independent=team.optJSONObject("teamIndependentReview");if(independent==null||independent.optBoolean("stale")||!independent.optString("status").equals("completed")||!independent.optBoolean("completeOriginalFilesRead")||!independent.optString("verdict").equals("no_blocking_findings")||!expectedHash.equals(independent.optString("planHash")))throw new SecurityException("请先由独立审查者读取当前完整冻结成果；旧审查不能批准新修订");
            if(!review.optBoolean("canIntegrate")||!teamRun.equals(plan.getString("teamRunId"))||!team.getJSONObject("teamWriteScope").getString("targetProjectId").equals(plan.getString("targetProjectId"))||!expectedHash.equals(review.getString("planHash"))||!expectedHash.equals(capabilityFingerprint(plan)))throw new SecurityException("原成果或当前项目已经改变，请重新查看实际差异");
            JSONArray original=teamAssignmentsForReview(teamRun,ownerRead),refs=plan.getJSONArray("files"),currentRefs=new JSONArray(),reviewedRefs=new JSONArray();for(int i=0;i<original.length();i++){JSONObject assignment=original.getJSONObject(i);if(!assignment.optString("status").equals("completed"))throw new SecurityException("仍有原成员未冻结完整成果，请先处理原assignment");JSONArray artifacts=assignment.getJSONArray("artifacts");for(int a=0;a<artifacts.length();a++)currentRefs.put(new JSONObject(artifacts.getJSONObject(a).toString()));}
            for(int i=0;i<refs.length();i++){JSONObject ref=new JSONObject(refs.getJSONObject(i).toString());ref.remove("expectedVersion");ref.remove("expectedHash");checkTeamArtifact(ref,ownerRead);reviewedRefs.put(ref);}if(!canonical(currentRefs).equals(canonical(reviewedRefs)))throw new SecurityException("审查不是原团队当前完整冻结成果，请重新查看");
            JSONObject previous=team.optJSONObject("teamOutputReview"),recovery=plan.optJSONObject("recovery"),integration=null;
            if(previous!=null&&expectedHash.equals(previous.getString("planHash"))){db.setTransactionSuccessful();return new JSONObject(previous.toString()).put("known",true);}
            if(previous!=null){
                JSONObject context=teamIntegrationContext(teamRun,ownerRead);
                if(recovery==null||context==null||context.getBoolean("activeAttempt")||context.getBoolean("hasUnresolved")||!context.getString("taskId").equals(recovery.optString("taskId"))||!previous.getString("reviewId").equals(recovery.optString("reviewId"))||!previous.getString("planHash").equals(recovery.optString("planHash"))||!capabilityFingerprint(context.getJSONObject("binding").getJSONObject("plan")).equals(previous.getString("planHash"))||!dev.pocketengine.features.teams.TeamWorkFiles.hash(dev.pocketengine.features.teams.TeamWorkFiles.canonical(context.getJSONArray("receipts"))).equals(recovery.optString("receiptsHash")))throw new SecurityException("原整合仍在执行、效果未知或旧审查已变化，请重新查看原任务后明确批准");
                integration=required(context.getString("taskId"));JSONArray history=team.optJSONArray("teamOutputReviewHistory");if(history==null)history=new JSONArray();history.put(new JSONObject(previous.toString()));team.put("teamOutputReviewHistory",history);
            }else if(recovery!=null)throw new SecurityException("重新审查没有原唯一整合任务");
            JSONObject approved=new JSONObject().put("reviewId",UUID.randomUUID().toString()).put("planHash",expectedHash).put("plan",plan).put("approvedAt",System.currentTimeMillis()).put("actorId","owner").put("spaceId","private");
            if(previous!=null){
                approved.put("previousReviewId",previous.getString("reviewId")).put("previousPlanHash",previous.getString("planHash"));
                JSONObject identity=integration.getJSONObject("teamAssignment");identity.put("files",new JSONArray(refs.toString())).put("plan",new JSONObject(plan.toString())).put("planHash",expectedHash).put("reviewId",approved.getString("reviewId"));
                integration.put("teamAssignment",identity).put("cancelRequested",false).put("status","awaiting_user").put("summary","主人已明确批准原唯一整合任务的新审查；旧操作与审批保留，尚未开始新的执行");
                appendEvent(integration,new JSONObject().put("type","team_integration_review_replaced").put("previousReviewId",previous.getString("reviewId")).put("previousPlanHash",previous.getString("planHash")).put("reviewId",approved.getString("reviewId")).put("planHash",expectedHash).put("executionAuthorized",false));write(integration);
            }
            team.put("teamOutputReview",approved);write(team);ownerRead.check();db.setTransactionSuccessful();return new JSONObject(approved.toString());
        }finally{db.endTransaction();}
    }
    public synchronized JSONObject startTeamIntegration(String teamRun,String reviewId,RunControl original,int allocation)throws Exception {
        JSONObject team=teamWriteTask(teamRun),review=team.getJSONObject("teamOutputReview");checkTeamIntegrationParents(team);if(!reviewId.equals(review.getString("reviewId"))||team.optBoolean("cancelRequested")||team.optString("status").matches("cancelled|stopping|completed"))throw new SecurityException("原主人审查或团队状态已失效");
        String id=UUID.nameUUIDFromBytes(("team-integration:"+teamRun).getBytes(StandardCharsets.UTF_8)).toString();JSONObject task=read(id);
        JSONObject identity=new JSONObject().put("teamRunId",teamRun).put("assignmentId",id).put("memberId","integrator").put("targetProjectId",review.getJSONObject("plan").getString("targetProjectId")).put("files",review.getJSONObject("plan").getJSONArray("files")).put("plan",review.getJSONObject("plan")).put("planHash",review.getString("planHash")).put("reviewId",reviewId);
        if(task==null){task=base(id,"按主人审查的原团队成果逐文件整合，保留每份实际回执","assistant","capabilities").put("conversationId","").put("parentTaskId",team.getString("id")).put("status","queued").put("teamAssignment",identity).put("origin",new JSONObject().put("kind","team_integration").put("teamRunId",teamRun).put("assignmentId",id));write(task);}else if(!canonical(task.getJSONObject("teamAssignment")).equals(canonical(identity)))throw new SecurityException("唯一整合者的原计划不能替换");
        if(hasUnresolved(id)||task.optString("status").matches("preparing|running|stopping|cancelled|completed"))return new JSONObject(identity.toString()).put("taskId",id).put("inspectOnly",true);
        if(!task.optString("status").matches("queued|paused|awaiting_user|needs_confirmation|interrupted|failed"))throw new SecurityException("原唯一整合任务当前不可继续");JSONObject attempt=startAttempt(id,"capabilities",allocation,true);original.bindHostRun(id,attempt.getString("runId"),attempt.getInt("coordinatorEpoch"));JSONArray integrationTools=new JSONArray().put("team_read_integration").put("team_integrate_file");if(team.getJSONObject("teamWriteScope").has("projectSelection"))integrationTools.put("team_prepare_integrated_project");authorizeTools(id,attempt.getString("runId"),integrationTools);if(required(id).has("teamProject"))authorizeTeamProjectTools(id,attempt.getString("runId"));return new JSONObject(identity.toString()).put("taskId",id).put("runId",attempt.getString("runId")).put("coordinatorEpoch",attempt.getInt("coordinatorEpoch")).put("stepCeiling",attempt.getInt("stepCeiling"));
    }
    public synchronized JSONObject inspectTeamIntegration(String teamRun)throws Exception {JSONObject team=teamWriteTask(teamRun);String id=UUID.nameUUIDFromBytes(("team-integration:"+teamRun).getBytes(StandardCharsets.UTF_8)).toString();JSONObject task=get(id,false);if(task==null)return new JSONObject().put("teamRunId",teamRun).put("status","not_started").put("artifactsApplied",0);JSONObject binding=new JSONObject(task.getJSONObject("teamAssignment").toString()).put("taskId",id);JSONArray receipts=teamAssignmentReceipts(binding,"team_integrate_file");return new JSONObject().put("teamRunId",teamRun).put("taskId",id).put("runId",task.optString("runId")).put("status",hasUnresolved(id)?"blocked_unknown":task.getString("status")).put("planHash",binding.getString("planHash")).put("reviewId",binding.getString("reviewId")).put("receipts",receipts).put("message",task.optString("summary")).put("atomicBatch",false).put("businessVerified",false).put("projectDelivery",task.optJSONObject("teamProjectDelivery")==null?JSONObject.NULL:new JSONObject(task.getJSONObject("teamProjectDelivery").toString()));}

    public synchronized JSONObject startAttempt(String id,String mode,int allocation)throws Exception{return startAttempt(id,mode,allocation,false);}
    private JSONObject startAttempt(String id,String mode,int allocation,boolean nativeTeam)throws Exception{return startAttempt(id,mode,allocation,nativeTeam,false);}
    private JSONObject startAttempt(String id,String mode,int allocation,boolean nativeTeam,boolean nativeLearning)throws Exception{
        db.beginTransaction();try{
            JSONObject t=required(id);if("inference".equals(t.optString("kind")))throw new SecurityException("用量记录不能恢复为任务执行；请从原模型、语音或本机服务入口明确发起新请求");if(t.has("sourceLearningJob")&&!nativeLearning)throw new SecurityException("来源学习只能回原逐批任务入口继续，不能由通用Runner重放");if(!nativeTeam&&t.optJSONObject("origin")!=null&&t.getJSONObject("origin").optString("kind").matches("team_assignment|team_integration|team_review"))throw new SecurityException("团队分工只能回到原团队assignment入口继续");if(t.optJSONObject("origin")!=null&&t.getJSONObject("origin").optString("kind").equals("group"))throw new SecurityException("群任务只能使用保留原生群授权的专用执行入口");if(hasUnresolved(id))throw new IllegalStateException("有动作结果尚未核对，请先确认这些动作是否已经发生");
            if(t.optJSONObject("origin")!=null&&t.getJSONObject("origin").optString("kind").equals("channel")){
                if(!mode.equals("capabilities"))throw new SecurityException("渠道能力任务不能取得手机执行权限");
                if(!t.optString("status").matches("queued|paused|awaiting_user|needs_confirmation|cancelled|interrupted|failed"))throw new IllegalStateException("渠道任务已有执行或已完成，不能重复启动");
            }
            boolean phoneMode=!mode.equals("capabilities");
            if(!mode.equals("background")&&!mode.equals("main_screen")&&(!mode.equals("capabilities")||!t.optString("kind").equals("assistant")))throw new IllegalArgumentException("无效执行模式");
            JSONObject reminderOrigin=t.optJSONObject("origin");if(reminderOrigin!=null&&"automation".equals(reminderOrigin.optString("kind"))&&!reminderOrigin.has("planId"))requireReminderNonOverlap(t.optString("automationRuleId"),id,t.optString("automationOverlapPolicy","buffer_one"));
            JSONObject conditionWait=t.has("automationConditionWaitId")?automationConditionWait(t):null;
            if(t.has("automationConditionWaitId")&&conditionWait==null)throw new SecurityException("原条件等待收据已变化或失效，请核对原执行");
            JSONObject origin=t.optJSONObject("origin");boolean planContinuation=origin!=null&&"automation".equals(origin.optString("kind"))&&!origin.optString("planId").isEmpty()&&!t.optString("runId").isEmpty();
            if(planContinuation&&(t.optBoolean("cancelRequested")||!t.optString("status").matches("paused|interrupted|awaiting_user|needs_confirmation")))throw new SecurityException("原目标已停止或尚未结束，不能重新派发");
            if(planContinuation&&!mode.equals(t.optString("executionMode")))throw new SecurityException("原目标恢复须保持原执行位置");
            JSONObject retainedGrant=planContinuation?grant(t.getString("runId")):null;
            if(retainedGrant!=null&&(!"owner".equals(retainedGrant.optString("principalId"))||!"private".equals(retainedGrant.optString("spaceId"))||!mode.equals(retainedGrant.optString("mode"))))throw new SecurityException("原目标预算不属于这个执行位置");
            long now=System.currentTimeMillis();if(phoneMode)try(Cursor lease=db.rawQuery("SELECT run_id FROM leases WHERE resource_id=? AND expires_at>?",new String[]{"phone.local",String.valueOf(now)})){if(lease.moveToFirst())throw new IllegalStateException("手机执行资源已有任务持有，请先停止原任务");}
            int epoch=t.optInt("coordinatorEpoch")+1;String runId=UUID.randomUUID().toString();
            JSONObject previous=planContinuation?checkpoint(t.getString("runId")):checkpointForTask(id);int consumed=previous.optInt("steps");
            // Explicit owner pause may revoke automatic recovery, never replenish this occurrence's native budget.
            int ceiling=retainedGrant==null?Math.min(400,consumed+Math.max(1,Math.min(40,allocation))):retainedGrant.getInt("stepCeiling");if(consumed>=ceiling)throw new IllegalStateException("当前任务已达到累计操作预算，请先核对结果");
            JSONObject taskScope=t.optJSONObject("executionScope");
            if(taskScope!=null){executionScopeTask(id);if(!t.optString("runId").isEmpty())taskScope.put("toolsPinned",true);if(!mode.equals(taskScope.getString("mode")))throw new SecurityException("原任务范围不能借继续切换执行位置，请明确审阅新任务");long scopeDeadline=TaskExecutionScope.effectiveDeadline(taskScope);if(scopeDeadline<=now)throw new SecurityException("原任务范围已到期，请主人明确续期");ceiling=Math.min(400,consumed+taskScope.getInt("toolLimit")-taskScope.getInt("toolsUsed"));if(consumed>=ceiling||taskScope.getInt("modelCallsUsed")>=taskScope.getInt("modelCallLimit"))throw new IllegalStateException("原任务累计预算已耗尽，请审阅原范围；没有自动补额度");}
            long expires=taskScope==null?now+600_000L:TaskExecutionScope.effectiveDeadline(taskScope);
            if(conditionWait!=null)expires=Math.min(expires,conditionWait.getLong("validUntil"));
            JSONObject grant=new JSONObject().put("principalId","owner").put("spaceId","private").put("mode",mode).put("expiresAt",expires).put("stepCeiling",ceiling)
                    .put("remainingOperations",taskScope!=null?taskScope.getInt("toolLimit")-taskScope.getInt("toolsUsed"):retainedGrant==null?ceiling-consumed:retainedGrant.getInt("remainingOperations"));
            if(taskScope!=null)grant.put("executionScopeRevision",taskScope.getLong("revision"));
            ContentValues run=new ContentValues();run.put("run_id",runId);run.put("task_id",id);run.put("epoch",epoch);run.put("status","running");run.put("checkpoint",previous.toString());run.put("grant_json",grant.toString());run.put("created_at",now);run.put("updated_at",now);db.insertOrThrow("runs",null,run);
            boolean ownerSourceCapable=dev.pocketengine.features.memory.OwnerSourceRunContext.acceptsPrivateOwnerProjection(t);
            dev.pocketengine.features.memory.OwnerSourceRunContext.initialize(db,id,runId,epoch,t.optString("runId"),ownerSourceCapable);
            try{if(acceptsPrivateOutputTask(t))dev.pocketengine.features.memory.TaskStoreMemoryQuery.requireOutputContext(db,outputSources,id,runId);}catch(Exception unavailable){throw new dev.pocketengine.features.memory.ToolOutputCapture.OutputSourceUnavailable(unavailable);}
            if(ownerSourceCapable)dev.pocketengine.features.memory.TaskStoreMemoryQuery.requireOwnerSourceContinuation(db,conversationRepository,id,runId,t.getString("conversationId"));
            if(phoneMode){ContentValues lease=new ContentValues();lease.put("resource_id","phone.local");lease.put("run_id",runId);lease.put("epoch",epoch);lease.put("expires_at",expires);db.insertWithOnConflict("leases",null,lease,SQLiteDatabase.CONFLICT_REPLACE);}
            JSONArray approvals=t.optJSONArray("approvals");if(!phoneMode&&approvals!=null)for(int i=0;i<approvals.length();i++){JSONObject approval=approvals.getJSONObject(i);if(approval.optString("state").equals("approved")&&approval.optLong("expiresAt")>now){if("project_execute".equals(approval.optString("tool"))&&approval.optJSONObject("arguments")!=null&&approval.getJSONObject("arguments").has("planRef"))continueProjectPlanApproval(t,approval,runId,epoch,now);else approval.put("runId",runId);}}
            t.remove("automationConditionWaitId");t.put("runId",runId).put("coordinatorEpoch",epoch).put("executionMode",mode).put("status","preparing").put("cancelRequested",false).put("stepCeiling",ceiling).put("approvalState","granted").put("leaseResource","phone.local").put("summary","正在恢复任务并重新观察当前现场");write(t);db.setTransactionSuccessful();return copy(t,false);
        }finally{db.endTransaction();}
    }
    /** Read-only current native destination; paused state confers no dispatch or resume authority. */
    public synchronized JSONObject ownerScreenContextTarget(String id,String run,int epoch,RunControl original)throws Exception {
        JSONObject task=required(id);requirePrivateOwner(task);
        if(original==null||!original.hasHostRun()||!original.matchesHostRun(id,run,epoch))throw new SecurityException("画面补充没有原任务控制器");
        original.check();fence(id,run,epoch,original,false);
        JSONObject origin=task.optJSONObject("origin");String kind=task.optString("kind"),mode=task.optString("executionMode");
        if(origin!=null&&origin.optString("kind").matches("channel|group")
                ||!("phone".equals(kind)&&mode.matches("main_screen|background")||"assistant".equals(kind)&&"capabilities".equals(mode))
                ||task.optBoolean("cancelRequested")||!task.optString("status").matches("preparing|running|pausing|paused|awaiting_user|reconciling"))
            throw new SecurityException("画面补充只能绑定私人原任务的当前执行");
        try(Cursor row=db.rawQuery("SELECT status FROM runs WHERE run_id=? AND task_id=? AND epoch=?",new String[]{run,id,String.valueOf(epoch)})){
            if(!row.moveToFirst()||!"running".equals(row.getString(0)))throw new SecurityException("原任务执行尝试已结束");
        }
        JSONObject allowed=grant(run);long now=System.currentTimeMillis();
        if(allowed.optLong("expiresAt")<=now||!"owner".equals(allowed.optString("principalId"))||!"private".equals(allowed.optString("spaceId"))||!mode.equals(allowed.optString("mode")))throw new SecurityException("原任务授权已失效");
        if("phone".equals(kind))try(Cursor lease=db.rawQuery("SELECT run_id,epoch,expires_at FROM leases WHERE resource_id='phone.local'",null)){
            if(!lease.moveToFirst()||!run.equals(lease.getString(0))||epoch!=lease.getInt(1)||lease.getLong(2)<=now)throw new SecurityException("原手机执行租约已变化");
        }
        return new JSONObject(task.toString());
    }

    private JSONObject nativeOwnerInputTask(String id,String run,int epoch,String conversation,RunControl original,boolean phone)throws Exception {
        if(!phone)return ownerInputTask(id,run,epoch,conversation,original);
        JSONObject task=ownerScreenContextTarget(id,run,epoch,original);
        if(!"phone".equals(task.optString("kind"))||conversation==null||!conversation.isEmpty()||!task.optString("status").matches("preparing|running|pausing|paused|awaiting_user"))throw new SecurityException("补充没有原手机任务身份");
        int prepared=0;try(Cursor pending=db.rawQuery("SELECT run_id,state FROM operations WHERE task_id=? AND state IN ('prepared','unknown','remote_job_prepared','remote_job_sending','remote_job_unknown','remote_approval_sending','remote_approval_unknown','remote_question_prepared','remote_question_sending','remote_question_unknown')",new String[]{id})){
            while(pending.moveToNext())if(!"prepared".equals(pending.getString(1))||!run.equals(pending.getString(0))||++prepared>1)throw new IllegalStateException("原任务有动作结果待核对，不能接收新内容");
        }
        return task;
    }

    /** Original owner/private assistant task only. No message JSON can supply an actor/grant/run. */
    private JSONObject ownerInputTask(String id,String run,int epoch,String conversation,RunControl control)throws Exception{
        JSONObject task=required(id);requirePrivateOwner(task);
        if(!"assistant".equals(task.optString("kind"))||!"capabilities".equals(task.optString("executionMode"))||conversation==null||conversation.isBlank()||!conversation.equals(task.optString("conversationId"))||task.optJSONObject("origin")!=null&&task.getJSONObject("origin").optString("kind").matches("channel|group"))throw new SecurityException("输入不属于该私人原对话任务");
        if(control==null||!control.hasHostRun()||!control.matchesHostRun(id,run,epoch))throw new SecurityException("输入没有原任务的活动控制器");
        control.check();
        if(!run.equals(task.optString("runId"))||epoch!=task.optInt("coordinatorEpoch")||task.optBoolean("cancelRequested")||!task.optString("status").matches("preparing|running|paused|pausing"))throw new SecurityException("原任务已停止或输入执行代次失效");
        try(Cursor current=db.rawQuery("SELECT status FROM runs WHERE run_id=? AND task_id=? AND epoch=?",new String[]{run,id,String.valueOf(epoch)})){if(!current.moveToFirst()||!"running".equals(current.getString(0)))throw new SecurityException("原输入执行尝试已结束");}
        JSONObject grant=grant(run);if(grant.optLong("expiresAt")<=System.currentTimeMillis()||!"owner".equals(grant.optString("principalId"))||!"private".equals(grant.optString("spaceId"))||!task.optString("executionMode").equals(grant.optString("mode")))throw new SecurityException("原输入授权已失效");
        // Text admission does not dispatch an effect. One current in-flight prepared receipt may finish;
        // true uncertainty or a foreign/multiple prepared effect still blocks new accepted input.
        int prepared=0;try(Cursor pending=db.rawQuery("SELECT run_id,state FROM operations WHERE task_id=? AND state IN ('prepared','unknown','remote_job_prepared','remote_job_sending','remote_job_unknown','remote_approval_sending','remote_approval_unknown','remote_question_prepared','remote_question_sending','remote_question_unknown')",new String[]{id})){while(pending.moveToNext())if(!"prepared".equals(pending.getString(1))||!run.equals(pending.getString(0))||++prepared>1)throw new IllegalStateException("原任务有结果待核对，不能接收新输入");}
        return task;
    }
    public synchronized JSONObject openOwnerInputMailbox(String id,String run,int epoch,String conversation,RunControl control)throws Exception{
        return openNativeOwnerInputMailbox(id,run,epoch,conversation,control,false);
    }
    public synchronized JSONObject openPhoneOwnerInputMailbox(String id,String run,int epoch,RunControl control)throws Exception{
        return openNativeOwnerInputMailbox(id,run,epoch,"",control,true);
    }
    private JSONObject openNativeOwnerInputMailbox(String id,String run,int epoch,String conversation,RunControl control,boolean phone)throws Exception{
        db.beginTransaction();try{
            JSONObject task=nativeOwnerInputTask(id,run,epoch,conversation,control,phone),mailbox=task.optJSONObject("ownerInputMailbox");
            if(mailbox==null||!run.equals(mailbox.optString("runId"))||epoch!=mailbox.optInt("epoch")){
                mailbox=new JSONObject().put("runId",run).put("epoch",epoch).put("conversationId",conversation).put("open",true);
                task.put("ownerInputMailbox",mailbox);if(task.optJSONArray("ownerInputs")==null)task.put("ownerInputs",new JSONArray());write(task);
            }
            db.setTransactionSuccessful();return new JSONObject(mailbox.toString());
        }finally{db.endTransaction();}
    }
    /** Native accepted message metadata; attachment pixels/URIs and model-generated authority never enter it. */
    public synchronized JSONObject appendOwnerInput(String id,String run,int epoch,String conversation,JSONObject message,String mode,RunControl control)throws Exception{
        return appendNativeOwnerInput(id,run,epoch,conversation,message,mode,control,false,false);
    }
    public synchronized JSONObject appendPhoneOwnerInput(String id,String run,int epoch,JSONObject message,RunControl control)throws Exception{
        return appendNativeOwnerInput(id,run,epoch,"",message,"steer",control,true,false);
    }
    /** Initial goal is already admitted; it is not an editable follow-up. Native start only. */
    public synchronized JSONObject appendOwnerGoalInput(String id,String run,int epoch,String conversation,JSONObject message,String mode,RunControl control)throws Exception{
        return appendNativeOwnerInput(id,run,epoch,conversation,message,mode,control,false,true);
    }
    private JSONObject appendNativeOwnerInput(String id,String run,int epoch,String conversation,JSONObject message,String mode,RunControl control,boolean phone,boolean taskGoal)throws Exception{
        db.beginTransaction();try{
            JSONObject task=nativeOwnerInputTask(id,run,epoch,conversation,control,phone),mailbox=ownerMailbox(task,run,epoch);
            if(!mailbox.getBoolean("open"))throw new IllegalStateException("原任务已关闭输入，请查询原消息回执后另行开始；没有自动重发");
            if(!mode.matches("queue|steer")||message.has("inputMode")&&!mode.equals(message.optString("inputMode")))throw new IllegalArgumentException("输入排队方式无效");
            String client=message.getString("clientMessageId"),messageId=message.getString("id");
            if(!client.matches("[a-fA-F0-9-]{36}")||!messageId.matches("[a-fA-F0-9-]{36}")||!(message.opt("content") instanceof String))throw new IllegalArgumentException("原生消息身份无效");
            String content=message.getString("content");JSONArray supplied=message.optJSONArray("attachments"),attachments=new JSONArray();if(supplied==null)supplied=new JSONArray();if(supplied.length()>4)throw new IllegalArgumentException("每条消息最多4个附件引用");
            java.util.Set<String> ids=new java.util.HashSet<>();
            for(int i=0;i<supplied.length();i++){
                JSONObject ref=supplied.getJSONObject(i);String attachment=ref.getString("id"),sha=ref.getString("sha256"),mime=ref.getString("mimeType");Object size=ref.opt("size");
                if(!attachment.matches("[a-fA-F0-9-]{36}")||!ids.add(attachment)||!sha.matches("[a-f0-9]{64}")||mime.isBlank()||mime.length()>150||!(size instanceof Number)||ref.getLong("size")<0||ref.getLong("size")>20L*1024*1024)throw new IllegalArgumentException("不可变附件引用无效");
                attachments.put(new JSONObject().put("id",attachment).put("sha256",sha).put("mimeType",mime).put("size",ref.getLong("size")));
            }
            if(content.length()>12000||content.isBlank()&&attachments.length()==0)throw new IllegalArgumentException("输入应为1至12000字或有效附件引用");
            if(message.has("ownerSourceRefs")&&message.optJSONArray("ownerSourceRefs")==null)throw new IllegalArgumentException("原文选择必须为固定metadata数组");
            JSONArray sourceRefs=dev.pocketengine.features.assistant.OwnerSourceReferences.checked(message.optJSONArray("ownerSourceRefs")==null?new JSONArray():message.getJSONArray("ownerSourceRefs"));
            if(phone&&sourceRefs.length()>0)throw new SecurityException("主人原文选择只能进入原私人聊天邮箱，不能冒手机或外部调用来源");
            JSONObject immutableInput=new JSONObject().put("content",content).put("mode",mode).put("attachments",attachments);if(sourceRefs.length()>0)immutableInput.put("ownerSourceRefs",sourceRefs);
            String digest=capabilityFingerprint(immutableInput);JSONArray rows=task.optJSONArray("ownerInputs");if(rows==null)rows=new JSONArray();long characters=content.length();
            for(int i=0;i<rows.length();i++){
                JSONObject row=rows.getJSONObject(i);characters+=row.optString("content").length();
                if(client.equals(row.optString("clientMessageId"))){if(!digest.equals(row.optString("acceptedPayloadSha256",row.optString("payloadSha256"))))throw new SecurityException("同一消息身份不能改写原输入或附件");db.setTransactionSuccessful();return new JSONObject(row.toString()).put("duplicate",true);}
                if(messageId.equals(row.optString("messageId")))throw new SecurityException("同一原生消息不能属于两个排队输入身份");
            }
            if(taskGoal&&(rows.length()!=0||!content.equals(task.optString("goal"))))throw new SecurityException("初始输入必须是这次新任务已接收的同一目标");
            if(rows.length()>=64||characters>240000)throw new IllegalStateException("本次原任务的输入留存已达上限，请先结束并核对原任务；未自动新建任务");
            if(sourceRefs.length()>0)try(var inspection=conversationRepository.inspectOwnerSources()){for(int i=0;i<sourceRefs.length();i++)inspection.requireSource(sourceRefs.getJSONObject(i));}
            JSONObject row=new JSONObject().put("messageId",messageId).put("clientMessageId",client).put("content",content).put("mode",mode).put("attachments",attachments).put("payloadSha256",digest).put("acceptedRunId",run).put("acceptedEpoch",epoch).put("acceptedAt",System.currentTimeMillis());if(sourceRefs.length()>0)row.put("ownerSourceRefs",sourceRefs);if(message.has("revision")){Object actual=message.get("revision");if(!(actual instanceof Integer||actual instanceof Long)||((Number)actual).longValue()<1)throw new IllegalArgumentException("原生消息revision必须为实际正整数");row.put("ownerMessageRevision",((Number)actual).longValue());}if(!phone&&message.has("requestFingerprint")){Object fingerprint=message.get("requestFingerprint");if(!(fingerprint instanceof String)||!((String)fingerprint).matches("[a-f0-9]{64}"))throw new IllegalArgumentException("原生提交fingerprint无效");row.put("ownerRequestFingerprint",fingerprint);}if(!phone){row.put("acceptedTaskId",id);OwnerInputQueue.accepted(row,taskGoal);}rows.put(row);task.put("ownerInputs",rows);write(task);db.setTransactionSuccessful();return new JSONObject(row.toString()).put("duplicate",false);
        }finally{db.endTransaction();}
    }
    private static JSONObject ownerMailbox(JSONObject task,String run,int epoch)throws Exception{
        JSONObject mailbox=task.optJSONObject("ownerInputMailbox");if(mailbox==null||!run.equals(mailbox.optString("runId"))||epoch!=mailbox.optInt("epoch"))throw new SecurityException("输入邮箱不属于原生当前执行代次");return mailbox;
    }
    /** Native message/source facade only. The callback runs in this same original SQLite transaction. */
    public interface OwnerMessageMutation {
        JSONObject apply(QueuedOwnerMessage selected,String content,String mode,boolean withdrawn)throws Exception;
    }
    /** Opaque pending selection. JSON cannot manufacture a selection or keep it beyond the callback. */
    public static final class QueuedOwnerMessage {
        private final TaskStore issuer;
        private final JSONArray attachments,sourceRefs;
        private boolean active=true;
        public final String taskId,conversationId,clientMessageId,messageId,acceptedRunId,mode,queueId,content,requestFingerprint;
        public final int acceptedEpoch;
        public final long queueRevision,currentMessageRevision;
        private QueuedOwnerMessage(TaskStore issuer,JSONObject task,JSONObject row)throws Exception{
            this.issuer=issuer;taskId=task.getString("id");conversationId=task.getString("conversationId");
            clientMessageId=row.getString("clientMessageId");messageId=row.getString("messageId");queueId=messageId;
            acceptedRunId=row.getString("acceptedRunId");acceptedEpoch=row.getInt("acceptedEpoch");mode=row.getString("mode");content=row.getString("content");requestFingerprint=row.getString("ownerRequestFingerprint");
            queueRevision=OwnerInputQueue.revision(row);currentMessageRevision=OwnerInputQueue.integer(row.opt("ownerMessageRevision"));
            attachments=row.optJSONArray("attachments")==null?new JSONArray():new JSONArray(row.getJSONArray("attachments").toString());
            sourceRefs=row.optJSONArray("ownerSourceRefs")==null?new JSONArray():new JSONArray(row.getJSONArray("ownerSourceRefs").toString());
        }
        public void requireMutationScope(TaskStore original){
            if(!active||issuer!=original||!Thread.holdsLock(original)||!original.db.inTransaction())throw new SecurityException("队列选择不在原生待处理消息变更事务内");
        }
        public JSONArray attachments()throws Exception{requireMutationScope(issuer);return new JSONArray(attachments.toString());}
        public JSONArray sourceRefs()throws Exception{requireMutationScope(issuer);return new JSONArray(sourceRefs.toString());}
    }
    public interface OwnerReplyMutation {
        JSONObject apply(QueuedOwnerReplySelection selected)throws Exception;
    }
    /** One actual completed request on the original task; this is data evidence, never a grant. */
    public static final class QueuedOwnerReplySelection {
        private final TaskStore issuer;
        private final String inputs;
        private boolean active=true;
        public final String taskId,conversationId,runId;
        public final int epoch;
        public final dev.pocketengine.runtime.AgentRunner.OwnerReply completed;
        private QueuedOwnerReplySelection(TaskStore issuer,JSONObject task,dev.pocketengine.runtime.AgentRunner.OwnerReply completed,JSONArray inputs)throws Exception{
            this.issuer=issuer;taskId=task.getString("id");conversationId=task.getString("conversationId");runId=task.getString("runId");epoch=task.getInt("coordinatorEpoch");this.completed=completed;this.inputs=inputs.toString();
        }
        public void requireMutationScope(TaskStore original){
            if(!active||issuer!=original||!Thread.holdsLock(original)||!original.db.inTransaction())throw new SecurityException("采用回执不在原任务的同一消息事务内");
        }
        public JSONArray inputs()throws Exception{requireMutationScope(issuer);return new JSONArray(inputs);}
    }
    /** Records already-checkpointed model facts, including an explicit same-task resume.
     * Does not open activeChat, mint an input/grant, dispatch, retry, or refund an inference. */
    public synchronized JSONObject withQueuedOwnerReply(dev.pocketengine.runtime.AgentRunner.OwnerReply completed,RunControl original,OwnerReplyMutation mutation)throws Exception{
        java.util.Objects.requireNonNull(mutation);if(completed==null||original==null||!completed.matchesOriginal(original)||!original.hasHostRun()||completed.messageIds().size()>500)throw new SecurityException("缺少实际完成请求的原生证明");
        db.beginTransaction();try{
            JSONObject task=required(completed.taskId);task=queuedOwnerTask(completed.taskId,task.optString("conversationId"));String run=task.getString("runId");int epoch=task.getInt("coordinatorEpoch");
            if(!original.matchesHostRun(completed.taskId,run,epoch))throw new SecurityException("答复不属于当前原任务执行代次");
            // Cancellation after this already committed response is not a new dispatch. The exact
            // current tuple/checkpoint/source checks below still apply; no RunControl grant is refreshed.
            try(Cursor raw=db.rawQuery("SELECT length(CAST(checkpoint AS BLOB)) FROM runs WHERE task_id=? AND run_id=? AND epoch=?",new String[]{completed.taskId,run,Integer.toString(epoch)})){
                if(!raw.moveToFirst()||raw.isNull(0)||raw.getLong(0)>dev.pocketengine.runtime.context.ContextBudgetPolicy.CHECKPOINT_BYTES)throw new IllegalStateException("答复检查点超过本次可核对范围，未写采用状态");
            }
            JSONArray messages=checkpoint(run).optJSONArray("messages");java.util.Map<String,JSONObject> included=new java.util.HashMap<>();JSONObject lastAssistant=null;
            for(int i=0;messages!=null&&i<messages.length();i++){
                JSONObject message=messages.getJSONObject(i);String id=message.optString("ownerMessageId");if(!id.isEmpty()&&included.put(id,message)!=null)throw new SecurityException("检查点重复了主人消息身份");
                if("assistant".equals(message.optString("role")))lastAssistant=message;
            }
            if(lastAssistant==null||!completed.text.equals(lastAssistant.optString("content")))throw new SecurityException("答复没有同一原生检查点的实际正文证明");
            java.util.Set<String> requested=new java.util.HashSet<>(completed.messageIds());JSONArray rows=task.optJSONArray("ownerInputs"),selectedRows=new JSONArray();
            for(int i=0;rows!=null&&i<rows.length();i++){
                JSONObject row=rows.getJSONObject(i),message=included.get(row.getString("messageId"));
                if(!requested.contains(row.getString("messageId"))||message==null||!"materialized".equals(OwnerInputQueue.state(row))||!run.equals(row.optString("materializedRunId"))||epoch!=row.optInt("materializedEpoch",-1))continue;
                JSONObject nativeSource=conversationRepository.source(row.getString("messageId")),prior=nativeSource==null?null:nativeSource.optJSONObject("submission");
                if(prior!=null&&"consumed".equals(prior.optString("status")))continue; // Already recorded facts are not a new consumption of historical context.
                long revision=OwnerInputQueue.integer(row.opt("ownerMessageRevision"));if(revision<1||revision!=OwnerInputQueue.integer(message.opt("ownerMessageRevision"))||!"user".equals(message.optString("role")))throw new SecurityException("采用回执的主人消息版本不一致");
                conversationRepository.requireQueuedOwnerMessageCurrent(task.getString("conversationId"),row);selectedRows.put(new JSONObject(row.toString()));
            }
            QueuedOwnerReplySelection selected=new QueuedOwnerReplySelection(this,task,completed,selectedRows);JSONObject result;
            try{result=mutation.apply(selected);}finally{selected.active=false;}
            db.setTransactionSuccessful();return result;
        }finally{db.endTransaction();}
    }
    private JSONObject queuedOwnerTask(String id,String conversation)throws Exception{
        JSONObject task=required(id);requirePrivateOwner(task);JSONObject origin=task.optJSONObject("origin");
        if(!"assistant".equals(task.optString("kind"))||!"capabilities".equals(task.optString("executionMode"))||conversation==null||conversation.isBlank()||!conversation.equals(task.optString("conversationId"))||origin!=null&&origin.optString("kind").matches("channel|group"))throw new SecurityException("请选择这条私人对话对应的原任务输入");
        return task;
    }
    private java.util.Set<String> queuedCheckpointIds(JSONObject task)throws Exception{
        java.util.Set<String> result=new java.util.HashSet<>();JSONArray messages=checkpointForTask(task.getString("id")).optJSONArray("messages");
        for(int i=0;messages!=null&&i<messages.length();i++){String message=messages.getJSONObject(i).optString("ownerMessageId");if(!message.isEmpty())result.add(message);}return result;
    }
    /** Native owner read, including after restart. It does not reopen an inbox or grant a new attempt. */
    public synchronized JSONObject queuedOwnerInputs(String id,String conversation)throws Exception{
        JSONObject task=queuedOwnerTask(id,conversation);java.util.Set<String> included=queuedCheckpointIds(task);JSONArray rows=task.optJSONArray("ownerInputs"),result=new JSONArray();
        if(rows!=null&&rows.length()>64)throw new IllegalStateException("输入留存超过可安全显示的范围，请先核对原任务");
        for(int i=0;rows!=null&&i<rows.length();i++)result.put(OwnerInputQueue.view(rows.getJSONObject(i),included.contains(rows.getJSONObject(i).getString("messageId")),false));
        return new JSONObject().put("taskId",id).put("conversationId",conversation).put("runId",task.optString("runId")).put("epoch",task.optInt("coordinatorEpoch"))
                .put("taskStatus",task.optString("status")).put("items",result).put("order","accepted_order_boundary_batch")
                .put("executionAuthorized",false).put("snapshotFingerprint",capabilityFingerprint(new JSONObject().put("items",result)));
    }
    public synchronized JSONObject queuedOwnerInput(String id,String conversation,String messageId)throws Exception{
        JSONObject task=queuedOwnerTask(id,conversation);java.util.Set<String> included=queuedCheckpointIds(task);JSONArray rows=task.optJSONArray("ownerInputs");
        for(int i=0;rows!=null&&i<rows.length();i++){JSONObject row=rows.getJSONObject(i);if(messageId.equals(row.optString("messageId")))return OwnerInputQueue.view(row,included.contains(messageId),true).put("taskId",id).put("conversationId",conversation);}
        throw new IllegalArgumentException("这条排队输入不存在，请刷新后核对");
    }
    /** Owner-only pending edit/retract. No input body is dispatched from this method. */
    public synchronized JSONObject mutateQueuedOwnerInput(String id,String conversation,String messageId,long expectedQueueRevision,long expectedMessageRevision,
            String content,String mode,boolean withdrawn,OwnerMessageMutation mutation)throws Exception{
        java.util.Objects.requireNonNull(mutation);if(mode==null||!mode.matches("queue|steer")||content==null||content.length()>12000)throw new IllegalArgumentException("输入内容或排队方式无效");
        db.beginTransaction();try{
            JSONObject task=queuedOwnerTask(id,conversation);if(queuedCheckpointIds(task).contains(messageId))throw new IllegalStateException("这条输入已进入任务上下文，不能撤回或改写");
            JSONArray rows=task.optJSONArray("ownerInputs");JSONObject row=null;
            for(int i=0;rows!=null&&i<rows.length();i++)if(messageId.equals(rows.getJSONObject(i).optString("messageId"))){row=rows.getJSONObject(i);break;}
            if(row==null)throw new IllegalArgumentException("这条排队输入不存在");OwnerInputQueue.requirePending(row,expectedQueueRevision,expectedMessageRevision);
            try(Cursor accepted=db.rawQuery("SELECT epoch FROM runs WHERE task_id=? AND run_id=?",new String[]{id,row.getString("acceptedRunId")})){
                if(!accepted.moveToFirst()||accepted.getInt(0)!=row.getInt("acceptedEpoch"))throw new SecurityException("输入没有可核对的原接收执行身份");
            }
            JSONArray attachments=row.optJSONArray("attachments");if(!withdrawn&&content.isBlank()&&(attachments==null||attachments.length()==0))throw new IllegalArgumentException("输入应包含文字或原附件引用");
            // A retract keeps its accepted text/references for audit. It is not formal source deletion.
            if(withdrawn&&(!content.equals(row.getString("content"))||!mode.equals(row.getString("mode"))))throw new IllegalArgumentException("撤回不能同时改写输入内容");
            QueuedOwnerMessage selected=new QueuedOwnerMessage(this,task,row);JSONObject nativeMessage;
            try{nativeMessage=mutation.apply(selected,content,mode,withdrawn);}finally{selected.active=false;}
            if(nativeMessage==null||!messageId.equals(nativeMessage.optString("id"))||!row.getString("clientMessageId").equals(nativeMessage.optString("clientMessageId")))throw new SecurityException("消息变更未返回同一原生消息");
            long revision=OwnerInputQueue.integer(nativeMessage.opt("revision"));if(revision<expectedMessageRevision||!content.equals(row.optString("content"))&&revision<=expectedMessageRevision)throw new SecurityException("消息变更缺少真实当前版本");
            if(!(nativeMessage.opt("content") instanceof String)||nativeMessage.getString("content").length()>12000)throw new SecurityException("消息变更没有有效原生正文");
            JSONArray actualAttachments=nativeMessage.optJSONArray("attachments"),actualSources=nativeMessage.optJSONArray("ownerSourceRefs");
            if(!canonical(attachments==null?new JSONArray():attachments).equals(canonical(actualAttachments==null?new JSONArray():actualAttachments))
                    ||!canonical(selected.sourceRefs).equals(canonical(actualSources==null?new JSONArray():actualSources)))throw new SecurityException("编辑排队文字不能替换原附件或原文引用");
            JSONObject before=new JSONObject().put("messageId",messageId).put("queueRevision",expectedQueueRevision).put("ownerMessageRevision",expectedMessageRevision)
                    .put("content",row.getString("content")).put("mode",row.getString("mode")).put("payloadSha256",row.getString("payloadSha256"));
            String actualContent=nativeMessage.getString("content");if(withdrawn&&!actualContent.equals(row.getString("content")))throw new SecurityException("撤回应保留原消息审计正文");if(!mode.equals(nativeMessage.optString("inputMode")))throw new SecurityException("原生消息的排队方式没有同步");long characters=0;for(int i=0;i<rows.length();i++)characters+=row==rows.getJSONObject(i)?actualContent.length():rows.getJSONObject(i).optString("content").length();
            if(characters>240000)throw new IllegalStateException("本任务输入留存已达上限，未保存修改");
            row.put("content",actualContent).put("mode",mode).put("ownerMessageRevision",revision);
            JSONObject payload=new JSONObject().put("content",actualContent).put("mode",mode).put("attachments",attachments==null?new JSONArray():attachments);if(selected.sourceRefs.length()>0)payload.put("ownerSourceRefs",selected.sourceRefs);
            row.put("payloadSha256",capabilityFingerprint(payload));OwnerInputQueue.changed(row,withdrawn?"withdrawn":"pending");row.put(withdrawn?"withdrawnAt":"editedAt",System.currentTimeMillis());
            appendEvent(task,new JSONObject().put("type",withdrawn?"owner_input_withdrawn":"owner_input_edited").put("inputBefore",before)
                    .put("messageId",messageId).put("queueRevision",OwnerInputQueue.revision(row)).put("ownerMessageRevision",revision).put("mode",mode).put("payloadSha256",row.getString("payloadSha256")).put("executionAuthorized",false));
            db.setTransactionSuccessful();return OwnerInputQueue.view(row,false,true).put("taskId",id).put("conversationId",conversation);
        }finally{db.endTransaction();}
    }
    /** Claims accepted rows atomically; materialization is only acknowledged by an original checkpoint. */
    public synchronized JSONArray pollOwnerInputs(String id,String run,int epoch,JSONArray includedMessageIds,boolean turnBoundary,boolean closeIfEmpty,RunControl control)throws Exception{
        return pollNativeOwnerInputs(id,run,epoch,includedMessageIds,turnBoundary,closeIfEmpty,control,false);
    }
    public synchronized JSONArray pollPhoneOwnerInputs(String id,String run,int epoch,JSONArray includedMessageIds,boolean turnBoundary,boolean closeIfEmpty,RunControl control)throws Exception{
        return pollNativeOwnerInputs(id,run,epoch,includedMessageIds,turnBoundary,closeIfEmpty,control,true);
    }
    private JSONArray pollNativeOwnerInputs(String id,String run,int epoch,JSONArray includedMessageIds,boolean turnBoundary,boolean closeIfEmpty,RunControl control,boolean phone)throws Exception{
        db.beginTransaction();try{
            JSONObject task=required(id);task=nativeOwnerInputTask(id,run,epoch,phone?"":task.optString("conversationId"),control,phone);checkExtensionRun(id,run,epoch,control);JSONObject mailbox=ownerMailbox(task,run,epoch);
            if(!mailbox.getBoolean("open")){db.setTransactionSuccessful();return new JSONArray();}
            if(includedMessageIds==null||includedMessageIds.length()>500)throw new IllegalArgumentException("原检查点消息身份无效");java.util.Set<String> included=new java.util.HashSet<>();for(int i=0;i<includedMessageIds.length();i++)included.add(includedMessageIds.getString(i));
            JSONArray rows=task.optJSONArray("ownerInputs"),next=new JSONArray();boolean pending=false,changed=false;
            for(int i=0;rows!=null&&i<rows.length();i++){
                JSONObject row=rows.getJSONObject(i);if(OwnerInputQueue.withdrawn(row)||included.contains(row.getString("messageId"))||"materialized".equals(OwnerInputQueue.state(row)))continue;
                pending=true;if(turnBoundary||row.getString("mode").equals("steer")){
                    if(!phone&&OwnerInputQueue.tracked(row)&&OwnerInputQueue.integer(row.opt("ownerMessageRevision"))>0){
                        try{conversationRepository.requireQueuedOwnerMessageCurrent(task.getString("conversationId"),row);}
                        catch(Exception sourceChanged){control.check();throw new QueuedInputSourceUnavailable(sourceChanged);}
                    }
                    if(!phone)changed|=OwnerInputQueue.claim(row,run,epoch,System.currentTimeMillis());
                    next.put(new JSONObject(row.toString()));
                }
            }
            if(closeIfEmpty&&!pending){mailbox.put("open",false);task.put("ownerInputMailbox",mailbox);changed=true;}
            if(changed)write(task);
            db.setTransactionSuccessful();return next;
        }finally{db.endTransaction();}
    }
    /** Read-only lookup after an uncertain ACK. It never reopens a mailbox or starts an attempt. */
    public synchronized JSONObject phoneOwnerInputReceipt(String id,String run,int epoch,String client)throws Exception {
        JSONObject task=required(id);requirePrivateOwner(task);
        if(!"phone".equals(task.optString("kind"))||!client.matches("[a-fA-F0-9-]{36}"))throw new SecurityException("原手机输入回执身份无效");
        JSONObject origin=task.optJSONObject("origin");if(origin!=null&&origin.optString("kind").matches("channel|group"))throw new SecurityException("不能从私人快捷入口查询群或渠道输入");
        JSONArray rows=task.optJSONArray("ownerInputs");
        for(int i=0;rows!=null&&i<rows.length();i++){
            JSONObject row=rows.getJSONObject(i);
            if(client.equals(row.optString("clientMessageId"))){
                if(!run.equals(row.optString("acceptedRunId"))||epoch!=row.optInt("acceptedEpoch"))throw new SecurityException("回执不属于原发送执行代次");
                return new JSONObject().put("taskId",id).put("runId",run).put("epoch",epoch).put("clientMessageId",client)
                        .put("messageId",row.getString("messageId")).put("status","queued").put("acceptedAt",row.getLong("acceptedAt"));
            }
        }
        return new JSONObject().put("taskId",id).put("runId",run).put("epoch",epoch).put("clientMessageId",client).put("status","unconfirmed");
    }
    public synchronized JSONObject ownerConversationUnresolved(String conversation)throws Exception{
        try(Cursor rows=db.rawQuery("SELECT task_id,document FROM tasks",null)){while(rows.moveToNext()){
            JSONObject task=new JSONObject(rows.getString(1));if("assistant".equals(task.optString("kind"))&&conversation.equals(task.optString("conversationId"))&&hasUnresolved(rows.getString(0))){requirePrivateOwner(task);return get(rows.getString(0),false);}
        }}return null;
    }
    /** Native project selection only; it narrows an already admitted private assistant task. */
    public synchronized void bindSshSelection(String id,String run,int epoch,RunControl original,JSONObject profile)throws Exception {
        JSONObject task=required(id);requirePrivateOwner(task);original.check();
        if(!"assistant".equals(task.optString("kind"))||task.optJSONObject("origin")!=null||!run.equals(task.optString("runId"))||epoch!=task.optInt("coordinatorEpoch")||!original.matchesHostRun(id,run,epoch)||!"preparing".equals(task.optString("status"))||get(id,false).getJSONArray("operations").length()!=0)throw new SecurityException("SSH选择没有原生新任务绑定");
        if(!profile.optBoolean("hostKeyTrusted")||!profile.optBoolean("credentialsConfigured")||!profile.getString("bindingHash").matches("[a-f0-9]{64}"))throw new SecurityException("SSH连接尚未明确准备");
        JSONObject clean=new JSONObject();for(String key:new String[]{"id","bindingHash","hostFingerprint","username","hostKeyTrusted","credentialsConfigured"})clean.put(key,profile.get(key));
        clean.put("accountResource",dev.pocketengine.features.ssh.SshProfile.hash((profile.getString("hostFingerprint")+"\n"+profile.getString("username")).getBytes(StandardCharsets.UTF_8)));
        if(task.has("sshSelection")&&!canonical(clean).equals(canonical(task.getJSONObject("sshSelection"))))throw new SecurityException("原任务不能替换已审阅SSH身份");task.put("sshSelection",clean);write(task);
    }
    public synchronized void bindDevelopmentContext(String id,String run,int epoch,RunControl original,JSONObject selected)throws Exception {
        JSONObject task=required(id);requirePrivateOwner(task);original.check();
        if(!"assistant".equals(task.optString("kind"))||task.optJSONObject("origin")!=null||!"capabilities".equals(task.optString("executionMode"))||!run.equals(task.optString("runId"))||epoch!=task.optInt("coordinatorEpoch")||!original.matchesHostRun(id,run,epoch)||task.optBoolean("cancelRequested")||!"preparing".equals(task.optString("status")))throw new SecurityException("项目选择没有原生新任务绑定");
        if(!"pocketworld.development-context.v1".equals(selected.getString("format"))||!selected.getString("runtime").matches("termux|managed_process_v1")||!selected.getString("initialSourceDigest").matches("[a-f0-9]{64}")||!selected.getString("clientMessageId").matches("[a-fA-F0-9-]{36}"))throw new SecurityException("项目选择格式无效");
        dev.pocketengine.features.local.project.ProjectBuildPlan.identity(selected.getString("projectId"));dev.pocketengine.features.local.project.ProjectBuildPlan.identity(selected.getString("checkoutId"));
        JSONObject clean=new JSONObject();for(String key:new String[]{"format","projectId","checkoutId","runtime","initialSourceDigest","clientMessageId"})clean.put(key,selected.get(key));clean.put("filesAreUntrusted",true).put("authorityAdded",false);
        JSONObject prior=task.optJSONObject("developmentContext");if(prior!=null&&!canonical(prior).equals(canonical(clean)))throw new SecurityException("原任务不能切换项目或执行位置");
        if(get(id,false).getJSONArray("operations").length()!=0)throw new SecurityException("不能向已开始的任务补挂项目");task.put("developmentContext",clean);write(task);
    }
    /** Native bytes were captured by the selected local module; only metadata enters the original task. */
    public synchronized void recordDevelopmentPreview(String id,String run,int epoch,RunControl original,JSONObject actual)throws Exception {
        original.check();checkCapabilityCurrent(id,run,epoch,"project_preview");JSONObject task=required(id),selected=task.getJSONObject("developmentContext");
        if(!original.matchesHostRun(id,run,epoch)||!selected.getString("checkoutId").equals(actual.getString("checkoutId"))||!selected.getString("projectId").equals(actual.getString("projectId"))||!actual.getString("previewId").matches("[a-f0-9]{64}"))throw new SecurityException("预览不属于原任务已选项目");
        JSONObject metadata=new JSONObject();for(String key:new String[]{"previewId","checkoutId","projectId","sourceDigest","entryPath","previewRoot","resourceCount","bytes","capturedAt","origin","atomicArtifactFence","businessVerified","persisted"})metadata.put(key,actual.get(key));metadata.put("runId",run).put("epoch",epoch).put("untrusted",true);
        JSONArray prior=task.optJSONArray("developmentPreviews"),next=new JSONArray();if(prior!=null)for(int i=Math.max(0,prior.length()-7);i<prior.length();i++)if(!actual.getString("previewId").equals(prior.getJSONObject(i).optString("previewId")))next.put(prior.getJSONObject(i));next.put(metadata);task.put("developmentPreviews",next);write(task);
    }
    /** Callback is from the native no-bridge preview instance, never a model or webpage command. */
    public synchronized void recordDevelopmentPreviewConsole(String id,String checkout,String previewId,String sourceDigest,String level,String text,String source,int line)throws Exception {
        JSONObject task=required(id);requirePrivateOwner(task);JSONObject selected=task.getJSONObject("developmentContext");if(!selected.getString("checkoutId").equals(checkout)||!level.matches("ERROR|WARNING|LOG|TIP|DEBUG|RENDER_ERROR"))throw new SecurityException("预览事件不属于原项目");
        boolean found=false;JSONArray previews=task.optJSONArray("developmentPreviews");for(int i=0;previews!=null&&i<previews.length();i++){JSONObject row=previews.getJSONObject(i);if(previewId.equals(row.optString("previewId"))&&sourceDigest.equals(row.optString("sourceDigest")))found=true;}if(!found)throw new SecurityException("原任务没有此原生预览");
        JSONArray prior=task.optJSONArray("developmentPreviewLog"),next=new JSONArray();if(prior!=null)for(int i=Math.max(0,prior.length()-23);i<prior.length();i++)next.put(prior.getJSONObject(i));next.put(new JSONObject().put("previewId",previewId).put("sourceDigest",sourceDigest).put("level",level).put("text",text.substring(0,Math.min(text.length(),1600))).put("source",source.substring(0,Math.min(source.length(),240))).put("line",Math.max(0,line)).put("at",System.currentTimeMillis()).put("untrusted",true));task.put("developmentPreviewLog",next);write(task);
    }
    public synchronized JSONObject developmentPreviewLog(String id)throws Exception {JSONObject task=required(id);requirePrivateOwner(task);if(task.optJSONObject("developmentContext")==null)throw new SecurityException("不是原开发任务");return new JSONObject().put("entries",task.optJSONArray("developmentPreviewLog")==null?new JSONArray():new JSONArray(task.getJSONArray("developmentPreviewLog").toString())).put("observedOnly",true).put("untrusted",true);}
    public synchronized void feedback(String id,String text)throws Exception{if(text.length()>8000)throw new IllegalArgumentException("补充要求最多8000字");JSONObject t=required(id);String old=t.optString("feedback");String next=old.isEmpty()?text:old+"\n"+text;if(next.length()>16000)next=next.substring(next.length()-16000);t.put("feedback",next);write(t);}
    public synchronized void requestCancel(String id,String runId)throws Exception{JSONObject t=required(id);if(runId!=null&&!runId.equals(t.optString("runId")))return;t.put("cancelRequested",true).put("status","stopping");write(t);}
    public synchronized void authorizeTools(String id,String runId,JSONArray names)throws Exception{
        JSONObject task=required(id);if(task.optJSONObject("origin")!=null&&task.getJSONObject("origin").optString("kind").equals("group"))throw new SecurityException("群工具只能由固定原生能力合同授予");if(!runId.equals(task.optString("runId")))throw new SecurityException("执行代次已失效");
        if(names.length()>100)throw new IllegalArgumentException("单任务工具数量超限");JSONArray clean=new JSONArray();for(int i=0;i<names.length();i++){String name=names.getString(i);if(!name.matches("[A-Za-z][A-Za-z0-9_]{0,63}"))throw new IllegalArgumentException("工具名称无效");clean.put(name);}
        if(task.optString("kind").equals("teams")){task.put("capabilityTools",clean);write(task);return;}
        JSONObject scope=task.optJSONObject("executionScope");
        if(scope!=null){
            if(!scope.getBoolean("toolsPinned")&&"preparing".equals(task.optString("status"))){if(scope.getInt("toolsUsed")!=0||scope.getInt("modelCallsUsed")!=0)throw new SecurityException("已经派发的任务工具清单必须冻结");scope.put("tools",new JSONArray(clean.toString()));task.put("executionScope",scope);write(task);}
            else{scope.put("toolsPinned",true);task.put("executionScope",scope);write(task);JSONArray admitted=new JSONArray(),allowed=scope.getJSONArray("tools");for(int i=0;i<clean.length();i++)for(int j=0;j<allowed.length();j++)if(clean.getString(i).equals(allowed.getString(j))){admitted.put(clean.getString(i));break;}clean=admitted;}
        }
        JSONObject grant=grant(runId);executionScopeCurrent(task,runId,grant);grant.put("tools",clean);ContentValues v=new ContentValues();v.put("grant_json",grant.toString());db.update("runs",v,"run_id=?",new String[]{runId});
    }
    private boolean toolGranted(String runId,String name)throws Exception{JSONArray names=grant(runId).optJSONArray("tools");if(names!=null)for(int i=0;i<names.length();i++)if(name.equals(names.optString(i)))return true;return false;}
    public synchronized void checkCapability(String id,String runId,int epoch,String name,RunControl control)throws Exception{
        control.check();checkCapabilityCurrent(id,runId,epoch,name);
        if(control.isPaused()&&!required(id).optString("kind").equals("teams"))throw new SecurityException("任务已暂停，工具执行权已失效");
    }
    /** Native retained-authority callback only: no control.check recursion or grant/op mutation; not standalone dispatch admission.
     * The caller separately retains/checks the exact live control and its cancellation/pause state,
     * and verifies its one exact prepared operation while rejecting other prepared/unknown effects. */
    public synchronized void checkCapabilityCurrent(String id,String runId,int epoch,String name)throws Exception{
        JSONObject task=required(id);if(!"owner".equals(task.optString("actorId"))||!"private".equals(task.optString("spaceId"))||!runId.equals(task.optString("runId"))||epoch!=task.optInt("coordinatorEpoch")||task.optBoolean("cancelRequested"))throw new SecurityException("能力调用身份或执行代次已失效");
        if(task.optString("kind").equals("teams")){if(!task.optString("status").matches("running|queued"))throw new SecurityException("团队已停止");JSONArray names=task.optJSONArray("capabilityTools");boolean found=false;if(names!=null)for(int i=0;i<names.length();i++)if(name.equals(names.optString(i)))found=true;if(!found)throw new SecurityException("团队未获得该证据工具授权");return;}
        if(!task.optString("status").matches("preparing|running"))throw new SecurityException("任务已暂停或结束，工具执行权已失效");
        try(Cursor run=db.rawQuery("SELECT status FROM runs WHERE run_id=? AND task_id=? AND epoch=?",new String[]{runId,id,String.valueOf(epoch)})){if(!run.moveToFirst()||!run.getString(0).equals("running"))throw new SecurityException("执行尝试已结束");}
        JSONObject grant=grant(runId);executionScopeCurrent(task,runId,grant);if(grant.optLong("expiresAt")<=System.currentTimeMillis()||!"owner".equals(grant.optString("principalId"))||!"private".equals(grant.optString("spaceId"))||!task.optString("executionMode").equals(grant.optString("mode"))||!toolGranted(runId,name))throw new SecurityException("该工具未获得当前任务授权");
        if(task.optString("kind").equals("phone"))try(Cursor lease=db.rawQuery("SELECT run_id,epoch,expires_at FROM leases WHERE resource_id='phone.local'",null)){if(!lease.moveToFirst()||!runId.equals(lease.getString(0))||epoch!=lease.getInt(1)||lease.getLong(2)<=System.currentTimeMillis())throw new SecurityException("手机执行资源已失效");}
    }
    /** Same owner executionScope, not a parallel browser grant. Native page/profile checks stay outside this monitor. */
    private boolean browserScopeConfigurationPendingOnly(String id,String run,JSONObject waiting)throws Exception {
        if(waiting==null)return false;String operation=waiting.getString("operationId");if(hasUnresolvedExcept(id,operation))return false;
        JSONObject intent=null;try(Cursor row=db.rawQuery("SELECT tool,arguments,state FROM operations WHERE task_id=? AND run_id=? AND operation_id=?",new String[]{id,run,operation})){if(!row.moveToFirst()||!row.getString(2).equals("prepared"))return false;intent=new JSONObject().put("tool",row.getString(0)).put("arguments",new JSONObject(row.getString(1)));}
        JSONArray approvals=required(id).getJSONArray("approvals");for(int i=0;i<approvals.length();i++){JSONObject approval=approvals.getJSONObject(i);if(waiting.getString("approvalId").equals(approval.optString("id"))&&run.equals(approval.optString("runId"))&&approval.optString("state").equals("pending")&&approval.optLong("expiresAt")>System.currentTimeMillis()&&intent.getString("tool").startsWith("browser_task_")&&intent.getString("tool").equals(approval.optString("tool"))&&capabilityFingerprint(intent.getJSONObject("arguments")).equals(approval.optString("argumentsHash")))return true;}return false;
    }
    public synchronized JSONObject grantBrowserActions(String id,String run,int epoch,RunControl original,JSONObject nativePin,long expectedRevision,long expectedExecutionRevision,dev.pocketengine.browser.BrowserActionScope.Consent consent)throws Exception {
        return grantBrowserActions(id,run,epoch,original,nativePin,expectedRevision,expectedExecutionRevision,consent,null);
    }
    public synchronized JSONObject grantBrowserActions(String id,String run,int epoch,RunControl original,JSONObject nativePin,long expectedRevision,long expectedExecutionRevision,dev.pocketengine.browser.BrowserActionScope.Consent consent,JSONObject nativeWaiting)throws Exception {
        JSONObject policy=dev.pocketengine.browser.BrowserActionScope.validate(consent.review());
        db.beginTransaction();try{
            browserReadCurrent(id,run,epoch,original,"browser_task_observe");if(hasUnresolved(id)&&!browserScopeConfigurationPendingOnly(id,run,nativeWaiting))throw new SecurityException("请先核对当前未决操作");
            JSONObject task=required(id),scope=browserTaskExecutionScope(task,run),previous=scope.optJSONObject("browserActions");long revision=previous==null?0:previous.getLong("revision");if(scope.getLong("revision")!=expectedExecutionRevision||revision!=expectedRevision||revision==Long.MAX_VALUE)throw new SecurityException("浏览授权预览已被替换");
            JSONObject identity=dev.pocketengine.browser.BrowserReadScope.identity(nativePin,original.pauseRevision());if(!id.equals(identity.getString("taskId"))||!run.equals(identity.getString("runId"))||epoch!=identity.getInt("epoch")||nativePin.getLong("pauseRevision")!=original.pauseRevision())throw new SecurityException("浏览授权不属于原执行");
            JSONObject actions=new JSONObject().put("revision",revision+1).put("scopeRevision",scope.getLong("revision")).put("identity",identity).put("policy",policy).put("state","active").put("remainingActions",policy.getInt("maxActions")).put("permits",new JSONObject()).put("issuedAt",System.currentTimeMillis()).put("expiresAt",grant(run).getLong("expiresAt"));
            scope.put("browserActions",actions);write(task);appendEvent(task,new JSONObject().put("type","browser_task_scope_granted").put("runId",run).put("epoch",epoch).put("browserRevision",revision+1).put("policy",policy));db.setTransactionSuccessful();return browserActionsProjection(actions,scope,original.pauseRevision());
        }finally{db.endTransaction();}
    }
    private JSONObject browserTaskExecutionScope(JSONObject task,String run)throws Exception {
        JSONObject scope=task.optJSONObject("executionScope");if(scope==null||!"pocketworld.task-execution-scope.v1".equals(scope.optString("format"))||!"active".equals(scope.optString("state"))||scope.getLong("revision")!=grant(run).optLong("executionScopeRevision",-1))throw new SecurityException("原任务scope不可用或已变化");return scope;
    }
    public synchronized JSONObject admitBrowserActionScope(String id,String run,int epoch,RunControl original,String operation,String tool,JSONObject args,JSONObject pin)throws Exception {
        db.beginTransaction();try{
            browserReadCurrent(id,run,epoch,original,tool);browserReadPrepared(id,run,operation,tool,args);JSONObject task=required(id),scope=task.optJSONObject("executionScope"),actions=scope==null?null:scope.optJSONObject("browserActions");
            if(actions==null){db.setTransactionSuccessful();return null;}browserTaskExecutionScope(task,run);
            JSONObject permits=actions.getJSONObject("permits");if(permits.has(operation))throw new SecurityException("此原操作已领取许可，不得重放");
            if(!browserActionsProjection(actions,scope,original.pauseRevision()).getString("state").equals("active")||!dev.pocketengine.browser.BrowserReadScope.sameIdentity(actions.getJSONObject("identity"),pin,original.pauseRevision())||!dev.pocketengine.browser.BrowserActionScope.eligible(actions.getJSONObject("policy"),tool,args,pin)){db.setTransactionSuccessful();return null;}
            int left=actions.getInt("remainingActions");if(left<1){db.setTransactionSuccessful();return null;}actions.put("remainingActions",left-1);
            JSONObject permit=new JSONObject().put("kind","task_browser_actions").put("operationId",operation).put("tool",tool).put("scopeRevision",scope.getLong("revision")).put("browserRevision",actions.getLong("revision")).put("argumentsHash",capabilityFingerprint(args)).put("nativePinHash",capabilityFingerprint(pin)).put("remainingActions",left-1).put("admittedAt",System.currentTimeMillis());permits.put(operation,permit);write(task);appendEvent(task,new JSONObject().put("type","browser_task_scope_used").put("operationId",operation).put("tool",tool).put("browserRevision",actions.getLong("revision")));db.setTransactionSuccessful();return new JSONObject(permit.toString());
        }finally{db.endTransaction();}
    }
    public synchronized void checkBrowserActionPermit(String id,String run,int epoch,RunControl original,JSONObject permit)throws Exception {
        String tool=permit.getString("tool"),operation=permit.getString("operationId");browserReadCurrent(id,run,epoch,original,tool);JSONObject task=required(id),scope=browserTaskExecutionScope(task,run),actions=scope.getJSONObject("browserActions"),record=actions.getJSONObject("permits").optJSONObject(operation);
        // The final admitted call can consume the last slot; exhaustion blocks the next admission, not this call's readback.
        if(record==null||!capabilityFingerprint(record).equals(capabilityFingerprint(permit))||scope.getLong("revision")!=permit.getLong("scopeRevision")||actions.getLong("revision")!=permit.getLong("browserRevision")||!actions.getString("state").equals("active")||actions.getLong("expiresAt")<=System.currentTimeMillis()||actions.getJSONObject("identity").getLong("pauseRevision")!=original.pauseRevision())throw new SecurityException("原浏览任务许可已失效");
        try(Cursor row=db.rawQuery("SELECT arguments FROM operations WHERE task_id=? AND run_id=? AND operation_id=?",new String[]{id,run,operation})){if(!row.moveToFirst())throw new SecurityException("原prepared操作已不存在");JSONObject args=new JSONObject(row.getString(0));if(!capabilityFingerprint(args).equals(permit.getString("argumentsHash")))throw new SecurityException("原参数已变化");browserReadPrepared(id,run,operation,tool,args);}
    }
    public synchronized JSONObject browserActionsForOwner(String id,String run,int epoch,RunControl original)throws Exception {
        JSONObject task=required(id);requirePrivateOwner(task);if(!original.matchesHostRun(id,run,epoch)||!run.equals(task.optString("runId"))||epoch!=task.optInt("coordinatorEpoch"))throw new SecurityException("浏览范围不属于原执行");JSONObject scope=task.optJSONObject("executionScope"),actions=scope==null?null:scope.optJSONObject("browserActions");return actions==null?new JSONObject().put("state","not_granted").put("revision",0):browserActionsProjection(actions,scope,original.pauseRevision());
    }
    public synchronized JSONObject revokeBrowserActions(String id,String run,int epoch,RunControl original,long expectedRevision)throws Exception {
        JSONObject task=required(id);requirePrivateOwner(task);if(!original.matchesHostRun(id,run,epoch)||!run.equals(task.optString("runId"))||epoch!=task.optInt("coordinatorEpoch"))throw new SecurityException("旧任务不能撤销当前浏览范围");JSONObject scope=task.getJSONObject("executionScope"),actions=scope.getJSONObject("browserActions");if(actions.getLong("revision")!=expectedRevision)throw new SecurityException("撤销预览已过期");actions.put("state","revoked").put("revokedAt",System.currentTimeMillis());write(task);return browserActionsProjection(actions,scope,original.pauseRevision());
    }
    public synchronized void checkBrowserActionOperationIfPresent(String id,String run,int epoch,RunControl original,String operation,String tool)throws Exception {JSONObject scope=required(id).optJSONObject("executionScope"),actions=scope==null?null:scope.optJSONObject("browserActions"),permit=actions==null?null:actions.getJSONObject("permits").optJSONObject(operation);if(permit!=null){if(!tool.equals(permit.getString("tool")))throw new SecurityException("浏览许可不能换工具");checkBrowserActionPermit(id,run,epoch,original,permit);}}
    private JSONObject browserActionsProjection(JSONObject actions,JSONObject scope,long pause)throws Exception {
        JSONObject out=new JSONObject(actions.toString());out.remove("permits");if(out.getString("state").equals("active")){if(out.getLong("scopeRevision")!=scope.getLong("revision")||out.getJSONObject("identity").getLong("pauseRevision")!=pause)out.put("state","revoked");else if(out.getLong("expiresAt")<=System.currentTimeMillis())out.put("state","expired");else if(out.getInt("remainingActions")<1)out.put("state","exhausted");}return out;
    }
    /** Native BrowserTaskHost owner consent only. No model/feature/channel route grants this scope.
     * Native profile/resource/pin checks happen outside this monitor; only cheap original-control fences run inside. */
    public synchronized JSONObject grantBrowserReadScope(String id,String run,int epoch,RunControl original,JSONObject nativePin,dev.pocketengine.browser.BrowserReadScope.Consent consent)throws Exception {
        if(original==null||consent==null)throw new SecurityException("需要原控制器及明确原生主人同意");
        JSONObject policy=dev.pocketengine.browser.BrowserReadScope.validatePolicy(consent.review()),identity=dev.pocketengine.browser.BrowserReadScope.identity(nativePin,original.pauseRevision());
        db.beginTransaction();try{
            browserReadCurrent(id,run,epoch,original,"browser_task_observe");
            if(!id.equals(identity.getString("taskId"))||!run.equals(identity.getString("runId"))||epoch!=identity.getInt("epoch")||nativePin.getLong("pauseRevision")!=original.pauseRevision())throw new SecurityException("只读授权的原生身份已变化");
            if(hasUnresolved(id))throw new SecurityException("当前原动作尚待回执，先核对后再授予新的只读范围");
            if(policy.getBoolean("allowSourceRetention"))checkCapabilityCurrent(id,run,epoch,"browser_task_retain_source");
            if(policy.getJSONArray("publicSeeds").length()>0)checkCapabilityCurrent(id,run,epoch,"browser_task_read_public");
            JSONObject g=grant(run);long previous=g.optLong("browserReadScopeRevision",0);if(previous==Long.MAX_VALUE)throw new IllegalStateException("只读范围版本已耗尽");
            long now=System.currentTimeMillis();JSONObject scope=new JSONObject().put("kind","browser_native_read_scope").put("scopeId",UUID.randomUUID().toString()).put("revision",previous+1)
                    .put("identity",identity).put("policy",policy).put("state","active").put("issuedAt",now).put("expiresAt",Math.min(g.getLong("expiresAt"),now+dev.pocketengine.browser.BrowserReadScope.TTL_MS))
                    .put("remainingReads",dev.pocketengine.browser.BrowserReadScope.READS).put("remainingPublicBytes",dev.pocketengine.browser.BrowserReadScope.PUBLIC_BYTES).put("issuedBy","owner_native_browser_panel");
            g.put("browserReadScope",scope).put("browserReadScopeRevision",previous+1);if(!g.has("browserReadPermits"))g.put("browserReadPermits",new JSONObject());browserReadWriteGrant(run,g);
            appendEvent(required(id),new JSONObject().put("type","browser_read_scope_granted").put("scopeId",scope.getString("scopeId")).put("scopeRevision",previous+1).put("runId",run).put("epoch",epoch));
            db.setTransactionSuccessful();return browserReadProjection(scope,original.pauseRevision());
        }finally{db.endTransaction();}
    }
    /** One original prepared operation atomically reserves one read and its entire HTTP body ceiling. No refund after uncertainty. */
    public synchronized JSONObject admitBrowserReadScope(String id,String run,int epoch,RunControl original,String operation,String tool,JSONObject args,JSONObject nativePin)throws Exception {
        if(!dev.pocketengine.browser.BrowserReadScope.readTool(tool))return null;
        db.beginTransaction();try{
            browserReadCurrent(id,run,epoch,original,tool);browserReadPrepared(id,run,operation,tool,args);
            JSONObject g=grant(run),permits=g.optJSONObject("browserReadPermits"),scope=g.optJSONObject("browserReadScope");if(permits!=null&&permits.has(operation))throw new SecurityException("此原操作已领取只读许可，不能重发");if(scope==null||!"active".equals(scope.optString("state"))){db.setTransactionSuccessful();return null;}
            if(scope.optLong("expiresAt")<=System.currentTimeMillis()||!dev.pocketengine.browser.BrowserReadScope.sameIdentity(scope.getJSONObject("identity"),nativePin,original.pauseRevision())){
                scope.put("state","revoked").put("revokedAt",System.currentTimeMillis()).put("reason","expired_or_native_identity_changed");browserReadWriteGrant(run,g);db.setTransactionSuccessful();return null;
            }
            if(!dev.pocketengine.browser.BrowserReadScope.eligible(scope.getJSONObject("policy"),tool,args,nativePin)){db.setTransactionSuccessful();return null;}
            long reserved=dev.pocketengine.browser.BrowserReadScope.bodyReservation(tool,args);if(scope.getInt("remainingReads")<=0||reserved>scope.getLong("remainingPublicBytes")){db.setTransactionSuccessful();return null;}
            if(permits==null)permits=new JSONObject();if(permits.length()>=1000)throw new IllegalStateException("原执行只读操作记录已满");
            scope.put("remainingReads",scope.getInt("remainingReads")-1).put("remainingPublicBytes",scope.getLong("remainingPublicBytes")-reserved);
            JSONObject permit=new JSONObject().put("permitId",UUID.randomUUID().toString()).put("scopeId",scope.getString("scopeId")).put("scopeRevision",scope.getLong("revision"))
                    .put("operationId",operation).put("tool",tool).put("argumentsHash",capabilityFingerprint(args)).put("nativePinHash",capabilityFingerprint(nativePin)).put("reservedBodyBytes",reserved)
                    .put("admittedAt",System.currentTimeMillis()).put("remainingReadsAtAdmission",scope.getInt("remainingReads")).put("remainingPublicBytesAtAdmission",scope.getLong("remainingPublicBytes"));
            permits.put(operation,permit);g.put("browserReadPermits",permits);browserReadWriteGrant(run,g);
            appendEvent(required(id),new JSONObject().put("type","browser_read_scope_used").put("scopeId",scope.getString("scopeId")).put("scopeRevision",scope.getLong("revision")).put("operationId",operation).put("tool",tool).put("reservedBodyBytes",reserved));
            db.setTransactionSuccessful();return new JSONObject(permit.toString());
        }finally{db.endTransaction();}
    }
    /** Retained native permit, not a substitute tool grant. Never calls control.check / UI / resource callbacks under the lock. */
    public synchronized void checkBrowserReadScopePermit(String id,String run,int epoch,RunControl original,JSONObject permit)throws Exception {
        String operation=permit.getString("operationId"),tool=permit.getString("tool");browserReadCurrent(id,run,epoch,original,tool);
        JSONObject g=grant(run),permits=g.optJSONObject("browserReadPermits"),record=permits==null?null:permits.optJSONObject(operation),scope=g.optJSONObject("browserReadScope");
        if(record==null||!capabilityFingerprint(record).equals(capabilityFingerprint(permit))||scope==null||!"active".equals(scope.optString("state"))||!scope.getString("scopeId").equals(record.getString("scopeId"))||scope.getLong("revision")!=record.getLong("scopeRevision")
                ||scope.getLong("expiresAt")<=System.currentTimeMillis()||scope.getJSONObject("identity").getLong("pauseRevision")!=original.pauseRevision())throw new SecurityException("原只读范围已撤销、过期或被替换");
        try(Cursor row=db.rawQuery("SELECT arguments FROM operations WHERE task_id=? AND run_id=? AND operation_id=?",new String[]{id,run,operation})){if(!row.moveToFirst())throw new SecurityException("原只读操作不存在");JSONObject args=new JSONObject(row.getString(0));if(!capabilityFingerprint(args).equals(record.getString("argumentsHash")))throw new SecurityException("原只读参数已变化");browserReadPrepared(id,run,operation,tool,args);}
    }
    /** SourcePort calls this inside its existing original commit monitor; manual exact approvals have no scoped permit. */
    public synchronized void checkBrowserReadScopeOperationIfPresent(String id,String run,int epoch,RunControl original,String operation,String tool)throws Exception {
        JSONObject permits=grant(run).optJSONObject("browserReadPermits"),permit=permits==null?null:permits.optJSONObject(operation);if(permit==null)return;
        if(!tool.equals(permit.getString("tool")))throw new SecurityException("原只读许可不能授予其他写入工具");checkBrowserReadScopePermit(id,run,epoch,original,permit);
    }
    public synchronized JSONObject revokeBrowserReadScope(String id,String run,int epoch,String expectedScope,String reason)throws Exception {
        db.beginTransaction();try{
            JSONObject task=required(id);requirePrivateOwner(task);if(!run.equals(task.optString("runId"))||epoch!=task.optInt("coordinatorEpoch"))throw new SecurityException("只能撤销原执行的只读范围");
            JSONObject g=grant(run),scope=g.optJSONObject("browserReadScope");if(scope==null){db.setTransactionSuccessful();return new JSONObject().put("state","not_granted");}
            if(!scope.getString("scopeId").equals(expectedScope))throw new SecurityException("只读范围已被替换，旧撤销请求失效");
            if("active".equals(scope.optString("state"))){scope.put("state","revoked").put("revokedAt",System.currentTimeMillis()).put("reason",reason==null?"owner_revoked":reason.substring(0,Math.min(80,reason.length())));browserReadWriteGrant(run,g);appendEvent(required(id),new JSONObject().put("type","browser_read_scope_revoked").put("scopeId",expectedScope).put("scopeRevision",scope.getLong("revision")).put("runId",run).put("epoch",epoch));}
            db.setTransactionSuccessful();return browserReadProjection(scope,-1);
        }finally{db.endTransaction();}
    }
    public synchronized JSONObject browserReadScopeForOwner(String id,String run,int epoch,long pauseRevision)throws Exception {
        JSONObject task=required(id);requirePrivateOwner(task);if(!run.equals(task.optString("runId"))||epoch!=task.optInt("coordinatorEpoch"))throw new SecurityException("只读范围不属于原执行");JSONObject scope=grant(run).optJSONObject("browserReadScope");return scope==null?new JSONObject().put("state","not_granted"):browserReadProjection(scope,pauseRevision);
    }
    private JSONObject browserReadProjection(JSONObject scope,long pauseRevision)throws Exception {
        JSONObject value=new JSONObject(scope.toString());if("active".equals(value.optString("state"))){if(value.optLong("expiresAt")<=System.currentTimeMillis())value.put("state","expired");else if(pauseRevision>=0&&value.getJSONObject("identity").getLong("pauseRevision")!=pauseRevision)value.put("state","revoked").put("reason","pause_revision_changed");else if(value.optInt("remainingReads")<=0)value.put("state","exhausted");}return value;
    }
    private void browserReadCurrent(String id,String run,int epoch,RunControl original,String tool)throws Exception {
        JSONObject task=required(id);requirePrivateOwner(task);JSONObject origin=task.optJSONObject("origin");
        if(!"assistant".equals(task.optString("kind"))||!"capabilities".equals(task.optString("executionMode"))||origin!=null&&origin.optString("kind").matches("channel|group")||original==null||!original.hasHostRun()||!original.matchesHostRun(id,run,epoch)||original.isCancelled()||original.isPaused()||Thread.currentThread().isInterrupted())throw new SecurityException("只读范围需要原主人私人任务的当前未暂停控制器");checkCapabilityCurrent(id,run,epoch,tool);
    }
    private void browserReadPrepared(String id,String run,String operation,String tool,JSONObject args)throws Exception {
        try(Cursor row=db.rawQuery("SELECT tool,arguments,state FROM operations WHERE task_id=? AND run_id=? AND operation_id=?",new String[]{id,run,operation})){
            if(!row.moveToFirst()||!tool.equals(row.getString(0))||!capabilityFingerprint(args).equals(capabilityFingerprint(new JSONObject(row.getString(1))))||!"prepared".equals(row.getString(2)))throw new SecurityException("只读许可需要同一个原prepared操作");
        }
        try(Cursor other=db.rawQuery("SELECT operation_id FROM operations WHERE task_id=? AND run_id=? AND state IN ('prepared','unknown') AND operation_id<>?",new String[]{id,run,operation})){if(other.moveToFirst())throw new SecurityException("已有原动作待核对，不能派发新的只读操作");}
    }
    private void browserReadWriteGrant(String run,JSONObject grant)throws Exception {ContentValues value=new ContentValues();value.put("grant_json",grant.toString());if(db.update("runs",value,"run_id=?",new String[]{run})!=1)throw new SecurityException("原执行授权不存在");}
    /** Only an explicit private startAttempt carries an approved, definitely undispatched proposal.
     * This copies immutable proposal bytes, not a grant. Native capability/source checks still precede dispatch. */
    private void continueProjectPlanApproval(JSONObject task,JSONObject approval,String nextRun,int nextEpoch,long now)throws Exception {
        JSONObject args=approval.optJSONObject("arguments");String id=task.getString("id"),priorRun=approval.optString("runId");
        if(args==null||!args.has("planRef")||!"assistant".equals(task.optString("kind"))||task.optJSONObject("origin")!=null&&teamProjectSelection(task)==null||!task.has("developmentContext")&&teamProjectSelection(task)==null||task.optBoolean("cancelRequested")||!priorRun.equals(task.optString("runId"))){approval.put("state","invalidated").put("reason","project_plan_continuation_not_current");return;}
        JSONObject oldGrant=grant(priorRun);if(oldGrant.optLong("expiresAt")<=now||!"owner".equals(oldGrant.optString("principalId"))||!"private".equals(oldGrant.optString("spaceId"))||!toolGranted(priorRun,"project_execute")){approval.put("state","invalidated").put("reason","project_plan_original_authority_revoked_or_expired");return;}
        String originalOperation=null;
        try(Cursor rows=db.rawQuery("SELECT operation_id,arguments,state,receipt FROM operations WHERE task_id=? AND run_id=? AND tool='project_execute'",new String[]{id,priorRun})){
            while(rows.moveToNext())if(capabilityFingerprint(args).equals(capabilityFingerprint(new JSONObject(rows.getString(1))))){
                if(!"receipt".equals(rows.getString(2))||rows.isNull(3)){approval.put("state","invalidated").put("reason","project_plan_original_dispatch_unproven");return;}
                JSONObject receipt=new JSONObject(rows.getString(3));if(!"approval_required".equals(receipt.optString("code"))||!"not_dispatched".equals(receipt.optString("status"))||!Boolean.FALSE.equals(receipt.opt("accepted"))||receipt.has("projectExecution")){approval.put("state","invalidated").put("reason","project_plan_original_may_have_dispatched");return;}originalOperation=rows.getString(0);
            }
        }
        if(originalOperation==null){approval.put("state","invalidated").put("reason","project_plan_missing_not_dispatched_receipt");return;}
        JSONObject original=projectStoredArguments(id,priorRun,args),plan=original.getJSONObject("plan"),capability=approval.getJSONObject("capability"),review=capability.optJSONObject("nativeProjectPlan");
        if(review==null||!plan.getString("planDigest").equals(review.optString("planDigest"))||!canonical(capability).equals(approval.optString("capabilityKey"))){approval.put("state","invalidated").put("reason","project_plan_original_capability_changed");return;}
        JSONObject next=dev.pocketengine.features.local.project.ProjectPlanRecords.store(db,new JSONObject().put("taskId",id).put("runId",nextRun).put("coordinatorEpoch",nextEpoch),plan),nextArgs=new JSONObject().put("planRef",next.getString("planRef")).put("planDigest",next.getString("planDigest")).put("stepId",args.getString("stepId"));
        approval.put("continuedFromRun",priorRun).put("continuedFromPlanRef",args.getString("planRef")).put("notDispatchedOperationId",originalOperation).put("continuedToEpoch",nextEpoch).put("runId",nextRun).put("arguments",nextArgs).put("argumentsHash",capabilityFingerprint(nextArgs)).put("nativeCapabilityRevalidationRequired",true);
        appendEvent(task,new JSONObject().put("type","project_plan_approved_continuation").put("approvalId",approval.getString("id")).put("fromRun",priorRun).put("fromPlanRef",args.getString("planRef")).put("planRef",next.getString("planRef")).put("planDigest",next.getString("planDigest")).put("runId",nextRun).put("epoch",nextEpoch).put("notDispatchedOperationId",originalOperation).put("grantCopied",false).put("nativeCapabilityRevalidationRequired",true));
    }
    public synchronized JSONArray approvedProjectPlanProposals(String id,String run,int epoch,RunControl original)throws Exception {
        checkCapability(id,run,epoch,"project_inspect",original);JSONObject task=required(id);JSONArray approvals=task.optJSONArray("approvals"),result=new JSONArray();for(int i=0;approvals!=null&&i<approvals.length();i++){JSONObject approval=approvals.getJSONObject(i),args=approval.optJSONObject("arguments");if(!run.equals(approval.optString("runId"))||!"approved".equals(approval.optString("state"))||!"project_execute".equals(approval.optString("tool"))||approval.optLong("expiresAt")<=System.currentTimeMillis()||args==null||!args.has("planRef"))continue;JSONObject plan=projectArguments(id,run,epoch,args).getJSONObject("plan");result.put(new JSONObject(args.toString()).put("sourceDigest",plan.getString("sourceDigest")).put("ownerApprovedExactStep",true).put("executionAuthorized",false).put("requiresCurrentGrantSourceAndCapability",true));}return result;
    }
    /** Full immutable proposal bytes stay in this original SQLite authority, never in model arguments. */
    public synchronized JSONObject recordProjectPlan(String id,String run,int epoch,RunControl original,JSONObject full)throws Exception {
        checkCapability(id,run,epoch,"project_plan",original);JSONObject plan=dev.pocketengine.features.local.project.ProjectBuildPlan.verified(full);projectPlanContext(id,run,epoch,plan);
        db.beginTransaction();try{JSONObject ref=dev.pocketengine.features.local.project.ProjectPlanRecords.store(db,new JSONObject().put("taskId",id).put("runId",run).put("coordinatorEpoch",epoch),plan);db.setTransactionSuccessful();return ref;}finally{db.endTransaction();}
    }
    private void projectPlanContext(String id,String run,int epoch,JSONObject plan)throws Exception {
        JSONObject nativeTask=required(id);if(teamProjectSelection(nativeTask)!=null){teamProjectContextForPlan(id,run,epoch,plan);return;}
        JSONObject task=required(id);requirePrivateOwner(task);JSONObject selected=task.getJSONObject("developmentContext");if(!"assistant".equals(task.optString("kind"))||task.has("origin")||!selected.getString("projectId").equals(plan.getString("projectId"))||!selected.getString("checkoutId").equals(plan.getString("checkoutId"))||!selected.getString("runtime").equals(plan.optString("runtimeId","termux")))throw new SecurityException("完整方案不属于原任务已选项目/运行位置");
        try(Cursor row=db.rawQuery("SELECT epoch FROM runs WHERE task_id=? AND run_id=?",new String[]{id,run})){if(!row.moveToFirst()||epoch!=row.getInt(0))throw new SecurityException("完整方案没有此原run/epoch");}
    }
    /** Resolves evidence only. Every execution caller still checks its current grant/control and actual source bytes. */
    public synchronized JSONObject projectArguments(String id,String run,int epoch,JSONObject raw)throws Exception {
        JSONObject args=dev.pocketengine.features.local.project.ProjectPlanRecords.resolve(db,new JSONObject().put("taskId",id).put("runId",run).put("coordinatorEpoch",epoch),raw);projectPlanContext(id,run,epoch,args.getJSONObject("plan"));return args;
    }
    private JSONObject projectStoredArguments(String id,String run,JSONObject raw)throws Exception {
        try(Cursor row=db.rawQuery("SELECT epoch FROM runs WHERE task_id=? AND run_id=?",new String[]{id,run})){if(!row.moveToFirst())throw new SecurityException("原项目run不存在");return projectArguments(id,run,row.getInt(0),raw);}
    }
    private JSONObject projectResolvedRow(String id,JSONObject row)throws Exception {
        JSONObject copy=new JSONObject(row.toString()),raw=copy.getJSONObject("arguments");copy.put("journalArguments",new JSONObject(raw.toString())).put("arguments",projectStoredArguments(id,copy.getString("runId"),raw));return copy;
    }
    public synchronized JSONObject projectOperationForHost(JSONObject binding)throws Exception {
        String id=binding.getString("taskId"),run=binding.getString("runId");JSONObject task=required(id);requirePrivateOwner(task);
        try(Cursor row=db.rawQuery("SELECT tool,arguments,state,receipt FROM operations WHERE task_id=? AND run_id=? AND operation_id=?",new String[]{id,run,binding.getString("operationId")})){
            if(!row.moveToFirst()||!"project_execute".equals(row.getString(0)))throw new SecurityException("原项目操作不存在");JSONObject args=projectArguments(id,run,binding.getInt("coordinatorEpoch"),new JSONObject(row.getString(1))),result=new JSONObject().put("operationId",binding.getString("operationId")).put("runId",run).put("tool",row.getString(0)).put("arguments",args).put("journalArguments",new JSONObject(row.getString(1))).put("state",row.getString(2));if(!row.isNull(3))result.put("receipt",new JSONObject(row.getString(3)));return result;
        }
    }
    /** Same-task original settled package evidence. Pure SQL/JSON: no catalog/runtime lock inside. */
    public synchronized JSONObject projectPackageEvidence(String id,String run,int epoch,RunControl original,String tool,String operation)throws Exception {
        if(!java.util.Set.of("project_package","project_package_preview").contains(tool))throw new SecurityException("不是原固定成果读取工具");checkCapability(id,run,epoch,tool,original);JSONObject task=required(id);if(teamProjectSelection(task)!=null)return teamProjectPackageEvidence(task,run,epoch,operation);return projectPackageEvidenceForOwner(id,operation);
    }
    /** Typed team origin remains distinct from the private model route. Original SQL row only. */
    private JSONObject teamProjectPackageEvidence(JSONObject task,String run,int epoch,String operation)throws Exception {
        JSONObject context=teamProjectAssociation(task,run,epoch);if(!task.getJSONObject("origin").optString("kind").equals("team_integration")||!context.getJSONObject("selection").getString("role").equals("unique_integrator"))throw new SecurityException("只有原唯一整合者可回读原包");
        try(Cursor row=db.rawQuery("SELECT run_id,arguments,state,receipt FROM operations WHERE task_id=? AND operation_id=? AND tool='project_execute'",new String[]{task.getString("id"),operation})){
            if(!row.moveToFirst()||!"receipt".equals(row.getString(2))||row.isNull(3))throw new SecurityException("原成果动作未取得确定回执");JSONObject args=projectStoredArguments(task.getString("id"),row.getString(0),new JSONObject(row.getString(1))),receipt=new JSONObject(row.getString(3)),route=receipt.getJSONObject("projectExecution");if(!context.getJSONObject("selection").getString("selectionHash").equals(route.optString("teamProjectSelectionHash")))throw new SecurityException("原包属于另一团队副本/授权版本");dev.pocketengine.features.teams.TeamProjectWorkspaces.validatePlan(context,args.getJSONObject("plan"));JSONObject op=new JSONObject().put("operationId",operation).put("runId",row.getString(0)).put("tool","project_execute").put("state",row.getString(2)).put("arguments",args).put("receipt",receipt);JSONObject evidence=dev.pocketengine.features.local.project.ProjectPackageDelivery.evidence(args.getJSONObject("plan"),op);if(!task.getString("id").equals(evidence.getString("taskId")))throw new SecurityException("原包不是本团队整合任务");return evidence;
        }
    }
    public synchronized JSONObject projectPackageEvidenceForOwner(String id,String operation)throws Exception {
        JSONObject task=required(id);requirePrivateOwner(task);if(!"assistant".equals(task.optString("kind"))||task.optJSONObject("origin")!=null||task.optJSONObject("developmentContext")==null)throw new SecurityException("请选择私人原开发任务");
        try(Cursor row=db.rawQuery("SELECT run_id,arguments,state,receipt FROM operations WHERE task_id=? AND operation_id=? AND tool='project_execute'",new String[]{id,operation})){
            if(!row.moveToFirst()||!"receipt".equals(row.getString(2))||row.isNull(3))throw new SecurityException("原保存操作未确定，不能用未知动作交付成果");JSONObject args=projectStoredArguments(id,row.getString(0),new JSONObject(row.getString(1))),op=new JSONObject().put("operationId",operation).put("runId",row.getString(0)).put("tool","project_execute").put("state",row.getString(2)).put("arguments",args).put("receipt",new JSONObject(row.getString(3)));
            dev.pocketengine.features.local.project.ProjectDevelopment.originalPollRequest(task,op);JSONObject evidence=dev.pocketengine.features.local.project.ProjectPackageDelivery.evidence(args.getJSONObject("plan"),op);if(!id.equals(evidence.getString("taskId")))throw new SecurityException("原成果属于其他任务");return evidence;
        }
    }
    /** Full original proposal for native owner review. Not a model argument or an approval grant. */
    public synchronized JSONObject projectPackagePlanForOwner(String id,String approvalId)throws Exception {
        JSONObject task=required(id);requirePrivateOwner(task);if(!"assistant".equals(task.optString("kind"))||task.optJSONObject("origin")!=null||task.optJSONObject("developmentContext")==null)throw new SecurityException("请选择私人原开发任务");JSONArray approvals=task.getJSONArray("approvals");for(int i=0;i<approvals.length();i++){JSONObject approval=approvals.getJSONObject(i);if(!approvalId.equals(approval.optString("id")))continue;if(!"project_execute".equals(approval.optString("tool"))||!"pending".equals(approval.optString("state"))||!task.getString("runId").equals(approval.getString("runId"))||approval.optLong("expiresAt")<=System.currentTimeMillis())throw new SecurityException("原项目包审批已变化");JSONObject args=projectStoredArguments(id,approval.getString("runId"),approval.getJSONObject("arguments")),plan=args.getJSONObject("plan");if(!"freeze_package".equals(plan.optString("intent")))throw new SecurityException("不是项目包保存方案");return new JSONObject(plan.toString()).put("reviewApprovalId",approvalId).put("reviewTaskId",id);}throw new SecurityException("原项目包审批不存在");
    }
    public synchronized JSONObject projectRevertEvidence(String id,String run,int epoch,RunControl original,String operation)throws Exception {
        checkCapability(id,run,epoch,"project_plan",original);JSONObject task=required(id);requirePrivateOwner(task);
        try(Cursor row=db.rawQuery("SELECT run_id,arguments,state,receipt FROM operations WHERE task_id=? AND operation_id=? AND tool='project_execute'",new String[]{id,operation})){
            if(!row.moveToFirst()||!"receipt".equals(row.getString(2))||row.isNull(3))throw new SecurityException("撤销需要原任务已确定文件操作，不处理unknown或其他任务");JSONObject args=projectStoredArguments(id,row.getString(0),new JSONObject(row.getString(1))),envelope=new JSONObject(row.getString(3)),receipt=envelope.getJSONObject("executionReceipt");
            if(!receipt.optBoolean("receiptReceived")||!"completed".equals(receipt.optString("status"))||receipt.optInt("exitCode",1)!=0||!receipt.optBoolean("allFilesApplied"))throw new SecurityException("原补丁没有完整applied回执，不能推定可撤销");return new JSONObject().put("plan",args.getJSONObject("plan")).put("receipt",receipt).put("operationId",operation);
        }
    }
    /** Native project HostPort only. The existing operation is the only durable launch ticket. */
    public synchronized JSONObject projectOperation(JSONObject binding,JSONObject exact)throws Exception {
        return projectOperationRecord(binding,exact,true);
    }
    /** Read/record the exact prior native operation without conferring a launch right in a later attempt. */
    public synchronized JSONObject projectOperationReceipt(JSONObject binding,JSONObject exact)throws Exception {
        return projectOperationRecord(binding,exact,false);
    }
    private JSONObject projectOperationRecord(JSONObject binding,JSONObject exact,boolean current)throws Exception {
        String taskId=binding.getString("taskId"),run=binding.getString("runId"),operation=binding.getString("operationId");int epoch=binding.getInt("coordinatorEpoch");
        JSONObject task=get(taskId,false);if(task==null)throw new SecurityException("项目原任务不存在");requirePrivateOwner(task);
        if(current&&(!run.equals(task.optString("runId"))||epoch!=task.optInt("coordinatorEpoch")))throw new SecurityException("项目原执行代次已失效");
        try(Cursor original=db.rawQuery("SELECT epoch FROM runs WHERE task_id=? AND run_id=?",new String[]{taskId,run})){if(!original.moveToFirst()||epoch!=original.getInt(0))throw new SecurityException("项目回执不是此任务的原执行代次");}
        JSONObject found=null;JSONArray rows=task.getJSONArray("operations");
        for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);if(operation.equals(row.optString("operationId"))){if(found!=null)throw new SecurityException("项目原操作重复");found=row;}}
        if(found==null||!run.equals(found.optString("runId"))||!"project_execute".equals(found.optString("tool")))throw new SecurityException("缺少原项目prepared操作");
        found=projectResolvedRow(taskId,found);JSONObject args=found.getJSONObject("arguments"),plan=dev.pocketengine.features.local.project.ProjectBuildPlan.verified(args.getJSONObject("plan"));
        JSONObject expected=dev.pocketengine.features.local.project.ProjectDevelopment.exact(plan,dev.pocketengine.features.local.project.ProjectBuildPlan.step(plan,args.getString("stepId")));
        if(!capabilityFingerprint(expected).equals(capabilityFingerprint(exact)))throw new SecurityException("项目原操作参数或源码方案不同");
        return new JSONObject(found.toString());
    }
    /** One short SQLite transaction: exact approval consumption and prepared->unknown CAS before I/O.
     * The UNKNOWN journal state means dispatch/pending, with the actual typed backend receipt beside it. */
    public synchronized JSONObject claimProjectOperation(JSONObject binding,JSONObject exact,JSONObject capability,RunControl original)throws Exception {
        db.beginTransaction();try{
            original.check();if(original.isPaused()||!original.hasHostRun()||!original.matchesHostRun(binding.getString("taskId"),binding.getString("runId"),binding.getInt("coordinatorEpoch")))throw new SecurityException("项目原控制已失效");
            checkCapabilityCurrent(binding.getString("taskId"),binding.getString("runId"),binding.getInt("coordinatorEpoch"),"project_execute");
            JSONObject row=projectOperation(binding,exact);if(!"prepared".equals(row.getString("state"))){db.setTransactionSuccessful();return new JSONObject().put("fresh",false).put("knownOperation",true);}
            JSONObject liveService=projectLiveEditService(binding,exact);String serviceId=liveService==null?null:liveService.getString("operationId");
            if(projectServiceCheckoutConflict(exact,binding.getString("operationId"),serviceId)){db.setTransactionSuccessful();return new JSONObject().put("fresh",false).put("notDispatched",true).put("status","not_dispatched").put("accepted",false).put("code","project_service_conflict").put("message","同一项目仍有原受控服务或冲突执行；请回原任务停止并确认释放。本步骤未派发");}
            JSONArray all=get(binding.getString("taskId"),false).getJSONArray("operations");for(int i=0;i<all.length();i++){JSONObject other=all.getJSONObject(i);if(!row.getString("operationId").equals(other.getString("operationId"))&&!other.getString("operationId").equals(serviceId)&&other.optString("state").matches("prepared|unknown|project_running|project_stopping"))throw new SecurityException("原任务有其他未决动作，不能派发项目命令");}
            // Only the original consumed startup approval can delegate these particular
            // existing source edits. Each write still spends an original journal operation.
            JSONObject teamFileScope=teamProjectFileGrant(required(binding.getString("taskId")),row.getJSONObject("arguments").getJSONObject("plan"));
            JSONObject approval=teamFileScope!=null?new JSONObject().put("approved",true).put("approvalId",teamFileScope.getString("grantId")):liveService==null?requireCapabilityApproval(binding.getString("taskId"),binding.getString("runId"),"project_execute",row.getJSONObject("journalArguments"),capability):new JSONObject().put("approved",true).put("approvalId",liveService.getJSONObject("receipt").getJSONObject("projectExecution").getString("approvalId"));
            if(!approval.optBoolean("approved")){db.setTransactionSuccessful();return new JSONObject(approval.toString()).put("fresh",false).put("approvalRequired",true);}
            original.check();if(original.isPaused())throw new SecurityException("项目派发前已暂停");
            consumeTeamProjectFileGrant(required(binding.getString("taskId")),teamFileScope);
            JSONObject route=new JSONObject(binding.toString()).put("jobId",row.getString("operationId")).put("authority","native_host_operation").put("approvalId",approval.getString("approvalId")).put("capabilityFingerprint",capabilityFingerprint(capability));
            JSONObject teamSelected=teamProjectSelection(required(binding.getString("taskId")));if(teamSelected!=null)route.put("teamProjectSelectionHash",teamSelected.getString("selectionHash"));
            if(liveService!=null)route.put("liveEditServiceExecutionId",serviceId).put("liveEditPolicyDigest",liveService.getJSONObject("receipt").getJSONObject("executionReceipt").getJSONObject("devWatch").getString("policyDigest"));
            for(String key:new String[]{"command","planDigest","stepId","intent","projectId","checkoutId","sourceDigest","requestDigest"})route.put(key,exact.get(key));
            if(exact.has("runtimeId"))route.put("runtimeId",exact.getString("runtimeId")).put("runtimeIdentity",exact.getString("runtimeIdentity"));
            JSONObject pending=new JSONObject().put("status","unknown").put("code","project_pending").put("accepted",JSONObject.NULL).put("projectExecution",route).put("executionStatus","dispatching").put("businessVerified",false).put("replayAllowed",false);
            ContentValues update=new ContentValues();update.put("state","unknown");update.put("receipt",sanitize(pending).toString());
            if(db.update("operations",update,"task_id=? AND run_id=? AND operation_id=? AND tool='project_execute' AND state='prepared'",new String[]{binding.getString("taskId"),binding.getString("runId"),row.getString("operationId")})!=1)throw new SecurityException("项目原操作已被消费");
            db.setTransactionSuccessful();return new JSONObject().put("fresh",true).put("projectExecution",route);
        }finally{db.endTransaction();}
    }
    private boolean projectServiceCheckoutConflict(JSONObject exact,String operation,String permittedService)throws Exception {
        boolean starting="owned_service".equals(exact.getJSONObject("payload").optString("runMode"));String checkout=exact.getString("checkoutId");
        try(Cursor rows=db.rawQuery("SELECT operation_id,arguments,task_id,run_id FROM operations WHERE tool='project_execute' AND state IN ('prepared','unknown','project_running','project_stopping')",null)){
            while(rows.moveToNext())if(!operation.equals(rows.getString(0))&&!rows.getString(0).equals(permittedService)){JSONObject args=projectStoredArguments(rows.getString(2),rows.getString(3),new JSONObject(rows.getString(1))),plan=dev.pocketengine.features.local.project.ProjectBuildPlan.verified(args.getJSONObject("plan"));if(!checkout.equals(plan.getString("checkoutId")))continue;
                JSONObject step=dev.pocketengine.features.local.project.ProjectBuildPlan.step(plan,args.getString("stepId"));if(starting||"owned_service".equals(step.getJSONObject("payload").optString("runMode")))return true;
            }
        }return false;
    }
    /** Pure native journal proof; never calls LocalModule/runtime while holding the store lock. */
    public synchronized JSONObject projectLiveEditService(JSONObject binding,JSONObject exact)throws Exception {return projectLiveEditService(binding,exact,null);}
    private JSONObject projectLiveEditService(JSONObject binding,JSONObject exact,JSONArray beforePrepareManifest)throws Exception {
        JSONObject payload=exact.optJSONObject("payload");if(payload==null||!payload.has("liveEditServiceExecutionId"))return null;
        if(!"writeFile".equals(exact.optString("command"))||!"edit".equals(exact.optString("intent")))throw new SecurityException("热改授权只适用于原文件CAS，不可执行其他命令");
        String service=dev.pocketengine.features.local.project.ProjectBuildPlan.identity(payload.getString("liveEditServiceExecutionId"));
        JSONObject row=ownedServiceRow(binding.getString("taskId"),binding.getString("runId"),binding.getInt("coordinatorEpoch"),service),args=row.getJSONObject("arguments"),plan=dev.pocketengine.features.local.project.ProjectBuildPlan.verified(args.getJSONObject("plan")),step=dev.pocketengine.features.local.project.ProjectBuildPlan.step(plan,args.getString("stepId")),launch=dev.pocketengine.features.local.project.ProjectDevelopment.exact(plan,step),envelope=row.getJSONObject("receipt"),backend=envelope.getJSONObject("executionReceipt"),route=envelope.getJSONObject("projectExecution");
        if(!"project_running".equals(row.optString("state"))||!nativeOwnedService(launch,backend)||Boolean.TRUE.equals(backend.opt("cancelRequested"))||Boolean.TRUE.equals(route.opt("cancelRequested"))||Boolean.TRUE.equals(backend.opt("processExited"))||!exact.getString("projectId").equals(plan.getString("projectId"))||!exact.getString("checkoutId").equals(plan.getString("checkoutId")))throw new SecurityException("原dev-watch服务不再是同run已核存活的执行");
        JSONObject policy=dev.pocketengine.features.local.project.ProjectLiveEditPolicy.fromExact(launch),actual=backend.getJSONObject("devWatch");
        if(policy==null||!Boolean.TRUE.equals(actual.opt("nativeSessionCreated"))||!policy.getString("policyDigest").equals(actual.optString("policyDigest")))throw new SecurityException("原服务没有本机创建的dev-watch会话");
        dev.pocketengine.features.local.project.ProjectLiveEditPolicy.requireLaunchManifest(policy,plan.getJSONArray("sourceManifest"),plan.getString("projectId"),plan.getString("checkoutId"),plan.getString("sourceDigest"));
        JSONObject editPlan=null;JSONArray operations=get(binding.getString("taskId"),false).getJSONArray("operations");for(int i=0;i<operations.length();i++){JSONObject candidate=operations.getJSONObject(i);if(binding.optString("operationId").equals(candidate.optString("operationId")))editPlan=dev.pocketengine.features.local.project.ProjectBuildPlan.verified(projectStoredArguments(binding.getString("taskId"),candidate.getString("runId"),candidate.getJSONObject("arguments")).getJSONObject("plan"));}
        // A before-prepare caller supplies the already verified plan separately below.
        JSONArray manifest=editPlan==null?beforePrepareManifest:editPlan.getJSONArray("sourceManifest");
        if(manifest==null||!exact.getString("sourceDigest").equals(dev.pocketengine.features.local.project.ProjectSources.manifestDigest(manifest)))throw new SecurityException("热改缺少原prepared源码清单");
        dev.pocketengine.features.local.project.ProjectLiveEditPolicy.requirePlannedEdit(policy,manifest,payload.getString("path"));
        if(!payload.optString("expectedHash").matches("[a-f0-9]{64}")||!(payload.opt("content") instanceof String))throw new SecurityException("热改必须提供既有文件的SHA与明确正文");
        boolean approved=false;JSONArray approvals=get(binding.getString("taskId"),false).getJSONArray("approvals");for(int i=0;i<approvals.length();i++){JSONObject approval=approvals.getJSONObject(i),capability=approval.optJSONObject("capability"),owned=capability==null?null:capability.optJSONObject("ownedService");if(route.getString("approvalId").equals(approval.optString("id"))&&"consumed".equals(approval.optString("state"))&&owned!=null&&policy.getString("policyDigest").equals(owned.optString("liveEditPolicyDigest"))&&Boolean.TRUE.equals(owned.opt("subsequentSourceExecution"))&&route.getString("capabilityFingerprint").equals(capabilityFingerprint(capability)))approved=true;}
        if(!approved)throw new SecurityException("原启动批准未明确包含这些源码的后续执行影响");return new JSONObject(row.toString());
    }
    /** Native before-prepare proof. The copied manifest is not a launch/lease ticket. */
    public synchronized boolean projectLiveEditAllowed(String id,String run,int epoch,JSONObject args)throws Exception {
        args=projectArguments(id,run,epoch,args);JSONObject plan=dev.pocketengine.features.local.project.ProjectBuildPlan.verified(args.getJSONObject("plan")),exact=dev.pocketengine.features.local.project.ProjectDevelopment.exact(plan,dev.pocketengine.features.local.project.ProjectBuildPlan.step(plan,args.getString("stepId")));
        if(!exact.getJSONObject("payload").has("liveEditServiceExecutionId"))return false;
        return projectLiveEditService(new JSONObject().put("taskId",id).put("runId",run).put("coordinatorEpoch",epoch),exact,plan.getJSONArray("sourceManifest"))!=null;
    }
    /** Only keeps the original supervisor alive during its own bounded file operation.
     * This does not relax restore/inference/unknown fences or admit another tool. */
    public synchronized boolean projectServiceAllowsInFlightEdit(JSONObject serviceBinding,JSONObject candidate)throws Exception {
        if(!"project_execute".equals(candidate.optString("tool"))||!serviceBinding.getString("runId").equals(candidate.optString("runId"))||!candidate.optString("state").matches("prepared|unknown"))return false;
        JSONObject plan=dev.pocketengine.features.local.project.ProjectBuildPlan.verified(projectStoredArguments(serviceBinding.getString("taskId"),candidate.getString("runId"),candidate.getJSONObject("arguments")).getJSONObject("plan")),exact=dev.pocketengine.features.local.project.ProjectDevelopment.exact(plan,dev.pocketengine.features.local.project.ProjectBuildPlan.step(plan,candidate.getJSONObject("arguments").getString("stepId")));
        if(!serviceBinding.getString("operationId").equals(exact.getJSONObject("payload").optString("liveEditServiceExecutionId")))return false;
        JSONObject binding=new JSONObject(serviceBinding.toString()).put("operationId",candidate.getString("operationId"));JSONObject service=projectLiveEditService(binding,exact);if(service==null)return false;
        if("unknown".equals(candidate.getString("state"))){JSONObject receipt=candidate.optJSONObject("receipt"),route=receipt==null?null:receipt.optJSONObject("projectExecution");return route!=null&&serviceBinding.getString("operationId").equals(route.optString("liveEditServiceExecutionId"))&&service.getJSONObject("receipt").getJSONObject("executionReceipt").getJSONObject("devWatch").getString("policyDigest").equals(route.optString("liveEditPolicyDigest"));}return true;
    }
    /** Keep accepted/running/unknown pending; only an actual terminal process receipt settles this operation. */
    private static boolean nativeOwnedService(JSONObject exact,JSONObject backend){
        return "managed_process_v1".equals(exact.optString("runtimeId"))&&"project.runtime.run".equals(exact.optString("command"))&&"owned_service".equals(exact.optJSONObject("payload")==null?"":exact.optJSONObject("payload").optString("runMode"))
                &&"managed_process".equals(backend.optString("provider"))&&"command".equals(backend.optString("kind"))&&Boolean.TRUE.equals(backend.opt("nativeStartObserved"))&&Boolean.TRUE.equals(backend.opt("ownedWorkerLive"))
                &&backend.optString("launchAckIdentity").matches("[a-f0-9]{64}")&&backend.optString("status").matches("running|awaiting_resource_release")&&Boolean.TRUE.equals(backend.opt("processLaunched"))&&!Boolean.TRUE.equals(backend.opt("resourcesReleased"));
    }
    public synchronized void recordProjectOperation(JSONObject binding,JSONObject exact,JSONObject backend)throws Exception {
        db.beginTransaction();try{
            JSONObject row=projectOperationReceipt(binding,exact),prior=row.optJSONObject("receipt"),route=prior==null?null:prior.optJSONObject("projectExecution");
            if(route==null||!binding.getString("operationId").equals(backend.optString("operationId"))||!binding.getString("operationId").equals(backend.optString("id")))throw new SecurityException("项目回执没有原派发路由");
            JSONObject link=backend.optJSONObject("projectExecution");if(link==null)throw new SecurityException("项目回执缺少原绑定");
            for(String key:new String[]{"authority","command","projectId","checkoutId","taskId","runId","operationId","coordinatorEpoch","planDigest","stepId","intent","sourceDigest","requestDigest"})if(!canonical(route.get(key)).equals(canonical(link.opt(key))))throw new SecurityException("项目回执属于其他原操作");
            if(route.has("runtimeId"))for(String key:new String[]{"runtimeId","runtimeIdentity"})if(!route.getString(key).equals(link.optString(key)))throw new SecurityException("项目回执属于其他工具环境版本");
            if(route.has("liveEditServiceExecutionId"))for(String key:new String[]{"liveEditServiceExecutionId","liveEditPolicyDigest"})if(!route.getString(key).equals(link.optString(key)))throw new SecurityException("热改回执不是原服务批准的文件操作");
            String status=backend.optString("status");Object exit=backend.opt("exitCode");boolean managed="managed_process_v1".equals(exact.optString("runtimeId"));
            boolean terminal=backend.optBoolean("receiptReceived")&&status.matches("completed|failed")&&(exit instanceof Integer||exit instanceof Long),preparationNotDispatched=false,commandNoChild=false;
            if(managed){
                String kind="project.runtime.prepare".equals(exact.optString("command"))?"project_stage":"project.runtime.probe".equals(exact.optString("command"))?"tool_probe":"command";
                boolean nativeKind="managed_process".equals(backend.optString("provider"))&&kind.equals(backend.optString("kind"));
                boolean exited=Boolean.TRUE.equals(backend.opt("processExited"))&&Boolean.TRUE.equals(backend.opt("processGroupAbsent"));
                preparationNotDispatched="project.runtime.prepare".equals(exact.optString("command"))&&"project_stage".equals(backend.optString("kind"))&&"managed_process".equals(backend.optString("provider"))&&"failed".equals(status)&&"not_dispatched".equals(backend.optString("effectStatus"))&&Boolean.FALSE.equals(backend.opt("processDispatchAttempted"))&&Boolean.FALSE.equals(backend.opt("processLaunched"));
                commandNoChild="project.runtime.run".equals(exact.optString("command"))&&"failed".equals(status)&&dev.pocketengine.features.local.runtime.NativeProcessSupervisor.validatesNoChildReceipt(backend);
                terminal&=Boolean.TRUE.equals(backend.opt("receiptReceived"))&&nativeKind&&Boolean.TRUE.equals(backend.opt("resourcesReleased"))&&(exited||preparationNotDispatched||commandNoChild);
                if("pty".equals(exact.getJSONObject("payload").optString("terminalMode")))terminal&=Boolean.TRUE.equals(backend.opt("processSessionAbsent"))&&dev.pocketengine.features.local.runtime.NativeProcessSupervisor.PTY_EXIT_RESULT_CONTRACT.equals(backend.optString("terminalNativeContract"));
            }
            boolean nativePackage="freezeProjectPackage".equals(exact.optString("command"));if(nativePackage)terminal&=dev.pocketengine.features.local.project.ProjectPackageDelivery.terminalReceipt(exact,backend);
            settleTeamProjectNativeBudget(binding,terminal,backend);
            boolean owned=!terminal&&nativeOwnedService(exact,backend);
            if("receipt".equals(row.optString("state"))&&!terminal)return;
            if(row.optString("state").matches("project_running|project_stopping")&&"awaiting_receipt".equals(status)&&!backend.has("nativeStartObserved")&&!backend.has("observation")){db.setTransactionSuccessful();return;}
            if(prior.optJSONObject("executionReceipt")!=null&&canonical(prior.getJSONObject("executionReceipt")).equals(canonical(backend))){db.setTransactionSuccessful();return;}
            JSONObject receipt=new JSONObject().put("status",terminal?status:owned?"running":"unknown").put("code",terminal?(nativePackage?"project_file_receipt":"project_process_receipt"):owned?"project_running":"project_pending").put("accepted",terminal||owned?true:JSONObject.NULL).put("projectExecution",route).put("executionStatus",status).put("executionReceipt",backend).put("businessVerified",false).put("replayAllowed",false);
            if(owned)receipt.put("launchAckIdentity",backend.getString("launchAckIdentity")).put("receiptReceived",false).put("terminal",false).put("serviceReady",false);
            ContentValues update=new ContentValues();update.put("state",terminal?"receipt":owned?(backend.optBoolean("cancelRequested")||route.optBoolean("cancelRequested")?"project_stopping":"project_running"):"unknown");update.put("receipt",sanitize(receipt).toString());
            if(db.update("operations",update,"task_id=? AND run_id=? AND operation_id=? AND tool='project_execute' AND state IN ('unknown','receipt','project_running','project_stopping')",new String[]{binding.getString("taskId"),binding.getString("runId"),binding.getString("operationId")})!=1)throw new SecurityException("项目原回执登记已变化");
            if(terminal&&(managed||nativePackage)){
                JSONObject task=required(binding.getString("taskId"));
                // A late receipt may settle its original row, but cannot revive a run,
                // alter a replacement attempt, or clear another unresolved effect.
                if("reconciling".equals(task.optString("status"))&&binding.getString("runId").equals(task.optString("runId"))&&binding.getInt("coordinatorEpoch")==task.optInt("coordinatorEpoch")&&!hasUnresolved(binding.getString("taskId"))){
                    boolean ended=false;try(Cursor run=db.rawQuery("SELECT status FROM runs WHERE task_id=? AND run_id=? AND epoch=?",new String[]{binding.getString("taskId"),binding.getString("runId"),String.valueOf(binding.getInt("coordinatorEpoch"))})){ended=run.moveToFirst()&&!"running".equals(run.getString(0));}
                    if(ended){task.put("status",task.optBoolean("cancelRequested")?"paused":"awaiting_user").put("summary",nativePackage?"原文件成果操作已有确定回执；保留文件与记录，等待主人明确继续，不自动保存或执行":preparationNotDispatched?"原准备失败已确认未派发进程且资源已释放；等待主人明确修改或继续，不自动重试":commandNoChild?"原命令已确证未创建子进程且资源已释放；等待主人明确修改或继续，不自动重试":"原项目执行已取得真实退出与资源释放回执；已有日志和文件保留，等待主人明确继续，不自动重启");write(task);}
                }
            }
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
    public synchronized void cancelProjectOperation(JSONObject binding,JSONObject exact)throws Exception {
        db.beginTransaction();try{
            JSONObject row=projectOperation(binding,exact);if("receipt".equals(row.optString("state"))){db.setTransactionSuccessful();return;}
            JSONObject receipt=row.optJSONObject("receipt");if(receipt==null)receipt=new JSONObject();
            boolean entered=receipt.optJSONObject("projectExecution")!=null;
            if(entered)receipt.getJSONObject("projectExecution").put("cancelRequested",true);
            boolean owned=row.optString("state").matches("project_running|project_stopping")&&receipt.optJSONObject("executionReceipt")!=null&&nativeOwnedService(exact,receipt.getJSONObject("executionReceipt"));
            receipt.put("status",owned?"running":entered?"unknown":"cancelled").put("accepted",owned?true:entered?JSONObject.NULL:false).put("cancelRequested",true).put("code",owned?"project_running":entered?"project_cancel_pending_unknown":"project_not_dispatched");
            ContentValues update=new ContentValues();update.put("state",owned?"project_stopping":entered?"unknown":"receipt");update.put("receipt",sanitize(receipt).toString());
            db.update("operations",update,"task_id=? AND run_id=? AND operation_id=? AND state IN ('prepared','unknown','project_running','project_stopping')",new String[]{binding.getString("taskId"),binding.getString("runId"),binding.getString("operationId")});
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
    public synchronized JSONObject requireCapabilityApproval(String id,String runId,String tool,JSONObject args)throws Exception{
        return requireCapabilityApproval(id,runId,tool,args,new JSONObject().put("kind","local").put("tool",tool));
    }
    private JSONObject ownedServiceRow(String id,String run,int epoch,String operation)throws Exception {
        JSONObject task=get(id,false);requirePrivateOwner(task);JSONObject selected;if(teamProjectSelection(task)!=null)selected=teamProjectAssociation(task,run,epoch);else{if(!"assistant".equals(task.optString("kind"))||task.optJSONObject("origin")!=null)throw new SecurityException("受控服务只属于本机私人原项目任务");selected=task.getJSONObject("developmentContext");}
        for(int i=0;i<task.getJSONArray("operations").length();i++){
            JSONObject row=task.getJSONArray("operations").getJSONObject(i);if(!operation.equals(row.optString("operationId")))continue;
            if(!"project_execute".equals(row.optString("tool"))||!run.equals(row.optString("runId"))||!row.optString("state").matches("project_running|project_stopping|receipt"))throw new SecurityException("不是原执行轮次的受控服务");
            row=projectResolvedRow(id,row);JSONObject args=row.getJSONObject("arguments"),plan=dev.pocketengine.features.local.project.ProjectBuildPlan.verified(args.getJSONObject("plan")),step=dev.pocketengine.features.local.project.ProjectBuildPlan.step(plan,args.getString("stepId")),exact=dev.pocketengine.features.local.project.ProjectDevelopment.exact(plan,step),envelope=row.getJSONObject("receipt"),route=envelope.getJSONObject("projectExecution"),backend=envelope.getJSONObject("executionReceipt");
            if(!"managed_process_v1".equals(selected.optString("runtime"))||!selected.getString("checkoutId").equals(plan.getString("checkoutId"))||!selected.getString("projectId").equals(plan.getString("projectId"))||!"owned_service".equals(step.getJSONObject("payload").optString("runMode"))||!"project.runtime.run".equals(step.getString("localCommand"))||epoch!=route.getInt("coordinatorEpoch"))throw new SecurityException("原服务项目、计划或代次不一致");
            dev.pocketengine.features.local.runtime.RuntimeExecutionRef.fromHost(exact,route).requireReceipt(backend);projectOperationReceipt(route,exact);
            boolean approved=false;JSONArray approvals=task.optJSONArray("approvals");for(int a=0;approvals!=null&&a<approvals.length();a++){JSONObject approval=approvals.getJSONObject(a),capability=approval.optJSONObject("capability");if(route.optString("approvalId").equals(approval.optString("id"))&&"consumed".equals(approval.optString("state"))&&capability!=null&&capability.optJSONObject("ownedService")!=null&&Boolean.TRUE.equals(capability.getJSONObject("ownedService").opt("stopSameJob"))&&route.optString("capabilityFingerprint").equals(capabilityFingerprint(capability)))approved=true;}
            if(!approved)throw new SecurityException("原启动批准未包含停止这个受控job的权限");
            return row;
        }
        throw new SecurityException("原受控服务不存在");
    }
    /** A lookup of the original native service only; HTTP/WS verification happens outside this lock. */
    public synchronized JSONObject projectDevServiceForPreview(JSONObject binding,String operation)throws Exception {
        JSONObject row=ownedServiceRow(binding.getString("taskId"),binding.getString("runId"),binding.getInt("coordinatorEpoch"),operation),args=row.getJSONObject("arguments"),plan=dev.pocketengine.features.local.project.ProjectBuildPlan.verified(args.getJSONObject("plan")),exact=dev.pocketengine.features.local.project.ProjectDevelopment.exact(plan,dev.pocketengine.features.local.project.ProjectBuildPlan.step(plan,args.getString("stepId"))),backend=row.getJSONObject("receipt").getJSONObject("executionReceipt");
        if(!"project_running".equals(row.optString("state"))||!nativeOwnedService(exact,backend)||Boolean.TRUE.equals(backend.opt("cancelRequested"))||!Boolean.TRUE.equals(exact.getJSONObject("payload").opt("devWatch"))||backend.optJSONObject("devWatch")==null||!Boolean.TRUE.equals(backend.getJSONObject("devWatch").opt("nativeSessionCreated")))throw new SecurityException("原热更新服务不再是同run已核存活状态");return new JSONObject(row.toString());
    }
    /** This typed native value cannot be supplied by a webpage/model or restored from metadata. */
    public synchronized void recordDevelopmentLivePreview(String id,String run,int epoch,RunControl original,dev.pocketengine.features.local.runtime.ProjectDevSession.Observed observed)throws Exception {
        original.check();checkCapabilityCurrent(id,run,epoch,"project_live_preview");if(!original.matchesHostRun(id,run,epoch))throw new SecurityException("活预览不属于原控制器");JSONObject route=observed.reference.binding();if(!id.equals(route.getString("taskId"))||!run.equals(route.getString("runId"))||epoch!=route.getInt("coordinatorEpoch"))throw new SecurityException("活预览属于其他执行代次");projectDevServiceForPreview(route,observed.reference.operationId());JSONObject task=required(id);task.put("developmentLivePreview",observed.metadata().put("runId",run).put("epoch",epoch).put("untrusted",true));write(task);
    }
    public synchronized void recordDevelopmentLivePreviewConsole(String id,dev.pocketengine.features.local.runtime.ProjectDevSession.Observed observed,String level,String text,String source,int line)throws Exception {
        JSONObject task=required(id);requirePrivateOwner(task);JSONObject registered=task.optJSONObject("developmentLivePreview"),route=observed.reference.binding();if(registered==null||!observed.identity.equals(registered.optString("previewId"))||!id.equals(route.getString("taskId"))||!route.getString("runId").equals(task.optString("runId"))||route.getInt("coordinatorEpoch")!=task.optInt("coordinatorEpoch")||!level.matches("ERROR|WARNING|LOG|TIP|DEBUG|RENDER_ERROR"))throw new SecurityException("活预览事件不属于本轮原生页面");
        JSONArray prior=task.optJSONArray("developmentPreviewLog"),next=new JSONArray();if(prior!=null)for(int i=Math.max(0,prior.length()-23);i<prior.length();i++)next.put(prior.getJSONObject(i));next.put(new JSONObject().put("previewId",observed.identity).put("executionId",observed.reference.operationId()).put("sourceDigest",observed.reference.sourceDigest()).put("liveSourceGenerationUnknown",true).put("level",level).put("text",text.substring(0,Math.min(text.length(),1600))).put("source",source.substring(0,Math.min(source.length(),240))).put("line",Math.max(0,line)).put("at",System.currentTimeMillis()).put("untrusted",true));task.put("developmentPreviewLog",next);write(task);
    }
    public synchronized JSONObject projectServiceForInputWait(JSONObject binding,String execution)throws Exception {
        JSONObject row=ownedServiceRow(binding.getString("taskId"),binding.getString("runId"),binding.getInt("coordinatorEpoch"),execution),args=row.getJSONObject("arguments"),plan=dev.pocketengine.features.local.project.ProjectBuildPlan.verified(args.getJSONObject("plan")),payload=dev.pocketengine.features.local.project.ProjectBuildPlan.step(plan,args.getString("stepId")).getJSONObject("payload");
        return "pty".equals(payload.optString("terminalMode"))?ptyServiceRow(binding.getString("taskId"),binding.getString("runId"),binding.getInt("coordinatorEpoch"),execution,true):projectDevServiceForPreview(binding,execution);
    }
    /** Read-only cursor observation. The original Runner remains the only input consumer. */
    public synchronized JSONObject projectLiveInputObservation(JSONObject binding,String executionId,String afterMessageId)throws Exception {
        String id=binding.getString("taskId"),run=binding.getString("runId");int epoch=binding.getInt("coordinatorEpoch");checkCapabilityCurrent(id,run,epoch,"project_wait_input");projectServiceForInputWait(binding,executionId);JSONArray inputs=required(id).optJSONArray("ownerInputs");if(inputs!=null&&inputs.length()>1000)throw new IllegalStateException("原输入观察超出有界范围");boolean found=afterMessageId==null||afterMessageId.isEmpty();int added=0;String cursor="";
        for(int i=0;inputs!=null&&i<inputs.length();i++){JSONObject row=inputs.getJSONObject(i);if(!run.equals(row.optString("acceptedRunId"))||epoch!=row.optInt("acceptedEpoch"))continue;String message=row.getString("messageId");if(afterMessageId!=null&&found&&!OwnerInputQueue.withdrawn(row))added++;if(message.equals(afterMessageId)){found=true;added=0;}cursor=message;}
        if(afterMessageId!=null&&!found)throw new SecurityException("原输入cursor不是这个run实际保留的消息，未猜测新旧顺序");return new JSONObject().put("cursor",cursor).put("available",afterMessageId!=null&&added>0).put("newInputCount",afterMessageId==null?0:added).put("consumed",false).put("taskId",id).put("runId",run).put("coordinatorEpoch",epoch).put("executionId",executionId);
    }
    /** Only a native owned observation in this same run may be excluded from the model fence. */
    private boolean onlyOwnedProjectPending(String id,String run,int epoch,String preparedStop)throws Exception {return onlyOwnedProjectPending(id,run,epoch,preparedStop,"");}
    private boolean onlyOwnedProjectPending(String id,String run,int epoch,String preparedStop,String excludedNativeObservation)throws Exception {
        boolean any=false;try(Cursor rows=db.rawQuery("SELECT operation_id,run_id,tool,state FROM operations WHERE task_id=? AND state IN ('prepared','unknown','project_running','project_stopping','remote_job_prepared','remote_job_sending','remote_job_unknown','remote_approval_sending','remote_approval_unknown','remote_question_prepared','remote_question_sending','remote_question_unknown')",new String[]{id})){
            while(rows.moveToNext()){
                if(!excludedNativeObservation.isEmpty()&&excludedNativeObservation.equals(rows.getString(0))&&run.equals(rows.getString(1)))continue;
                if(rows.getString(0).equals(preparedStop)&&run.equals(rows.getString(1))&&"prepared".equals(rows.getString(3))){if("project_stop".equals(rows.getString(2))){any=true;continue;}if("project_process_input".equals(rows.getString(2))){JSONObject input=projectInputOperation(new JSONObject().put("taskId",id).put("runId",run).put("coordinatorEpoch",epoch).put("operationId",preparedStop));projectProcessInputAllowed(id,run,epoch,input.getJSONObject("arguments"));any=true;continue;}}
                if(!run.equals(rows.getString(1))||!"project_execute".equals(rows.getString(2))||!rows.getString(3).matches("project_running|project_stopping"))return false;
                JSONObject row=ownedServiceRow(id,run,epoch,rows.getString(0)),args=row.getJSONObject("arguments"),plan=dev.pocketengine.features.local.project.ProjectBuildPlan.verified(args.getJSONObject("plan"));
                if(!nativeOwnedService(dev.pocketengine.features.local.project.ProjectDevelopment.exact(plan,dev.pocketengine.features.local.project.ProjectBuildPlan.step(plan,args.getString("stepId"))),row.getJSONObject("receipt").getJSONObject("executionReceipt")))return false;any=true;
            }
        }return any;
    }
    /** Native original-journal lookup: source/approval evidence, never runtime re-entry under SQL. */
    private JSONObject ptyServiceRow(String id,String run,int epoch,String execution,boolean live)throws Exception {
        JSONObject row=ownedServiceRow(id,run,epoch,execution),args=row.getJSONObject("arguments"),plan=dev.pocketengine.features.local.project.ProjectBuildPlan.verified(args.getJSONObject("plan"));
        JSONObject step=dev.pocketengine.features.local.project.ProjectBuildPlan.step(plan,args.getString("stepId")),exact=dev.pocketengine.features.local.project.ProjectDevelopment.exact(plan,step),payload=exact.getJSONObject("payload"),envelope=row.getJSONObject("receipt"),route=envelope.getJSONObject("projectExecution"),backend=envelope.getJSONObject("executionReceipt");
        if(!"command".equals(exact.optString("intent"))||!"pty".equals(payload.optString("terminalMode"))||!dev.pocketengine.features.local.runtime.NativeProcessSupervisor.PTY_EXIT_RESULT_CONTRACT.equals(payload.optString("terminalNativeContract")))throw new SecurityException("原服务没有当前受控PTY命令范围");
        dev.pocketengine.features.local.project.ProjectCommandSpec.fromPayload(payload);String scope=dev.pocketengine.features.local.project.ProjectCommandSpec.inputScopeDigest(payload);boolean approved=false;JSONArray approvals=required(id).optJSONArray("approvals");
        for(int i=0;approvals!=null&&i<approvals.length();i++){JSONObject approval=approvals.getJSONObject(i),cap=approval.optJSONObject("capability"),owned=cap==null?null:cap.optJSONObject("ownedService");if(route.optString("approvalId").equals(approval.optString("id"))&&"consumed".equals(approval.optString("state"))&&owned!=null&&Boolean.TRUE.equals(owned.opt("interactiveInputExplicitlyIncluded"))&&scope.equals(owned.optString("interactiveInputScopeDigest"))&&route.optString("capabilityFingerprint").equals(capabilityFingerprint(cap)))approved=true;}
        if(!approved)throw new SecurityException("原启动批准未明确包括这一命令的有限后续输入");
        if(live&&(!"project_running".equals(row.optString("state"))||!nativeOwnedService(exact,backend)||backend.optBoolean("cancelRequested")||route.optBoolean("cancelRequested")||backend.optBoolean("processExited")||!"pty".equals(backend.optString("terminalMode"))||!payload.getString("terminalNativeContract").equals(backend.optString("terminalNativeContract"))||backend.optLong("ptyLeaderPid")<2||backend.optLong("ptyLeaderStartTicks")<1||backend.optLong("ptySessionId")!=backend.optLong("ptyLeaderPid")))throw new SecurityException("原PTY不再是同run已核存活的会话");
        return row;
    }
    public synchronized boolean projectProcessInputAllowed(String id,String run,int epoch,JSONObject args)throws Exception {
        dev.pocketengine.features.local.project.ProjectCommandSpec.inputRequest(args);ptyServiceRow(id,run,epoch,dev.pocketengine.features.local.project.ProjectBuildPlan.identity(args.getString("executionId")),true);return true;
    }
    private JSONObject projectInputOperation(JSONObject binding)throws Exception {
        String id=binding.getString("taskId"),run=binding.getString("runId");requirePrivateOwner(required(id));
        try(Cursor generation=db.rawQuery("SELECT epoch FROM runs WHERE task_id=? AND run_id=?",new String[]{id,run})){if(!generation.moveToFirst()||generation.getInt(0)!=binding.getInt("coordinatorEpoch"))throw new SecurityException("输入不是这个原run/epoch");}
        try(Cursor row=db.rawQuery("SELECT tool,arguments,state,receipt FROM operations WHERE task_id=? AND run_id=? AND operation_id=?",new String[]{id,run,binding.getString("operationId")})){
            if(!row.moveToFirst()||!"project_process_input".equals(row.getString(0)))throw new SecurityException("没有这个原prepared终端输入");JSONObject result=new JSONObject().put("operationId",binding.getString("operationId")).put("runId",run).put("tool",row.getString(0)).put("arguments",new JSONObject(row.getString(1))).put("state",row.getString(2));if(!row.isNull(3))result.put("receipt",new JSONObject(row.getString(3)));return result;
        }
    }
    /** Keeps only the original native job alive during its already prepared, bounded input call. */
    public synchronized boolean projectServiceAllowsInFlightInput(JSONObject serviceBinding,JSONObject candidate)throws Exception {
        if(!"project_process_input".equals(candidate.optString("tool"))||!serviceBinding.getString("runId").equals(candidate.optString("runId"))||!candidate.optString("state").matches("prepared|unknown"))return false;
        JSONObject args=candidate.getJSONObject("arguments");if(!serviceBinding.getString("operationId").equals(args.optString("executionId")))return false;
        projectProcessInputAllowed(serviceBinding.getString("taskId"),serviceBinding.getString("runId"),serviceBinding.getInt("coordinatorEpoch"),args);
        if("prepared".equals(candidate.getString("state")))return true;
        JSONObject receipt=candidate.optJSONObject("receipt"),route=receipt==null?null:receipt.optJSONObject("ptyInput");return route!=null&&"native_pending".equals(route.optString("deliveryState"))&&serviceBinding.getString("operationId").equals(route.optString("executionId"))&&dev.pocketengine.features.local.project.ProjectSources.digest(dev.pocketengine.features.local.project.ProjectCommandSpec.inputRequest(args)).equals(route.optString("inputDigest"));
    }
    /** One fresh original intent is consumed before any native queue is entered. No input replay. */
    public synchronized JSONObject claimProjectProcessInput(JSONObject binding,String execution,RunControl original)throws Exception {
        db.beginTransaction();try{
            String id=binding.getString("taskId"),run=binding.getString("runId"),operation=binding.getString("operationId");int epoch=binding.getInt("coordinatorEpoch");
            fence(id,run,epoch,original,true,true,operation);checkCapabilityCurrent(id,run,epoch,"project_process_input");JSONObject input=projectInputOperation(binding),args=input.getJSONObject("arguments");if(!execution.equals(args.optString("executionId")))throw new SecurityException("终端输入目标已变化");
            JSONObject service=ptyServiceRow(id,run,epoch,execution,true);if(!"prepared".equals(input.optString("state"))){db.setTransactionSuccessful();return new JSONObject().put("fresh",false).put("receipt",input.optJSONObject("receipt")==null?new JSONObject().put("code","project_input_unknown").put("status","unknown").put("accepted",JSONObject.NULL):input.getJSONObject("receipt"));}
            JSONObject route=new JSONObject().put("format","pocketworld.pty-input.v1").put("executionId",execution).put("inputDigest",dev.pocketengine.features.local.project.ProjectSources.digest(dev.pocketengine.features.local.project.ProjectCommandSpec.inputRequest(args))).put("binding",binding).put("deliveryState","native_pending");
            JSONObject pending=new JSONObject().put("code","project_input_pending_unknown").put("status","unknown").put("accepted",JSONObject.NULL).put("ptyInput",route).put("executionId",execution).put("processTerminal",false).put("replayAllowed",false);
            ContentValues values=new ContentValues();values.put("state","unknown");values.put("receipt",pending.toString());if(db.update("operations",values,"task_id=? AND run_id=? AND operation_id=? AND tool='project_process_input' AND state='prepared'",new String[]{id,run,operation})!=1)throw new SecurityException("终端输入原意图已被消费");db.setTransactionSuccessful();return new JSONObject().put("fresh",true).put("service",service).put("request",dev.pocketengine.features.local.project.ProjectCommandSpec.inputRequest(args));
        }finally{db.endTransaction();}
    }
    /** Native writer's last-moment check; no runtime callback while this monitor is held. */
    public synchronized void checkProjectProcessInput(JSONObject binding,String execution)throws Exception {
        checkCapabilityCurrent(binding.getString("taskId"),binding.getString("runId"),binding.getInt("coordinatorEpoch"),"project_process_input");JSONObject input=projectInputOperation(binding),receipt=input.optJSONObject("receipt"),route=receipt==null?null:receipt.optJSONObject("ptyInput");
        if(!"unknown".equals(input.optString("state"))||route==null||!"native_pending".equals(route.optString("deliveryState"))||!execution.equals(route.optString("executionId"))||!execution.equals(input.getJSONObject("arguments").optString("executionId")))throw new SecurityException("原终端输入已结束、未知或被替换");ptyServiceRow(binding.getString("taskId"),binding.getString("runId"),binding.getInt("coordinatorEpoch"),execution,true);
    }
    /** Actual typed native IO evidence only. External accepted/running/delivered JSON is not proof. */
    public synchronized void recordProjectProcessInput(dev.pocketengine.features.local.runtime.ManagedProcessJobs.ProcessInputReceipt proof)throws Exception {
        if(proof==null)throw new SecurityException("缺少原native输入回执");var execution=proof.execution();JSONObject nativeBinding=execution.binding(),actual=proof.json();String id=nativeBinding.getString("taskId"),run=nativeBinding.getString("runId"),target=execution.operationId();int epoch=nativeBinding.getInt("coordinatorEpoch");
        if(proof.originalControl()==null||!proof.originalControl().matchesHostRun(id,run,epoch)||!dev.pocketengine.features.local.runtime.NativeProcessSupervisor.PTY_EXIT_RESULT_CONTRACT.equals(actual.optString("nativeInputReceiptContract")))throw new SecurityException("终端输入回执不是原native宿主");
        db.beginTransaction();try{
            JSONObject binding=new JSONObject(nativeBinding.toString()).put("operationId",actual.getString("inputOperationId")),input=projectInputOperation(binding),args=input.getJSONObject("arguments"),prior=input.optJSONObject("receipt"),route=prior==null?null:prior.optJSONObject("ptyInput"),service=ptyServiceRow(id,run,epoch,target,false),serviceArgs=service.getJSONObject("arguments"),plan=dev.pocketengine.features.local.project.ProjectBuildPlan.verified(serviceArgs.getJSONObject("plan"));
            JSONObject expected=dev.pocketengine.features.local.project.ProjectDevelopment.exact(plan,dev.pocketengine.features.local.project.ProjectBuildPlan.step(plan,serviceArgs.getString("stepId")));
            String digest=dev.pocketengine.features.local.project.ProjectSources.digest(dev.pocketengine.features.local.project.ProjectCommandSpec.inputRequest(args));
            if(!input.optString("state").matches("unknown|receipt")||route==null||!target.equals(args.optString("executionId"))||!target.equals(route.optString("executionId"))||!digest.equals(route.optString("inputDigest"))||!digest.equals(actual.optString("inputDigest"))||!canonical(expected).equals(canonical(execution.exact())))throw new SecurityException("原终端输入参数、计划或派发路由不一致");
            String state=actual.getString("status");boolean known=state.matches("delivered|not_dispatched");if(!known&&!state.equals("unknown"))throw new SecurityException("原native输入状态无效");
            if(state.equals("delivered")&&!Boolean.TRUE.equals(actual.opt("nativeAcknowledged")))throw new SecurityException("没有原native实际写入/窗口ACK");
            if(prior.optBoolean("nativeInputSettled")){if(!canonical(prior.optJSONObject("inputDelivery")).equals(canonical(actual)))throw new SecurityException("原输入已确定，不能被迟到结果改写");db.setTransactionSuccessful();return;}
            JSONObject nextRoute=new JSONObject(route.toString()).put("deliveryState",state),receipt=new JSONObject().put("code",state.equals("delivered")?"project_input_delivered":state.equals("not_dispatched")?"project_input_not_dispatched":"project_input_unknown").put("status",known?(state.equals("delivered")?"observed":"not_dispatched"):"unknown").put("ok",state.equals("delivered")).put("accepted",known?state.equals("delivered"):JSONObject.NULL).put("ptyInput",nextRoute).put("inputDelivery",actual).put("nativeInputSettled",known).put("executionId",target).put("processTerminal",false).put("replayAllowed",false);
            ContentValues values=new ContentValues();values.put("state",known?"receipt":"unknown");values.put("receipt",sanitize(receipt).toString());if(db.update("operations",values,"task_id=? AND run_id=? AND operation_id=? AND tool='project_process_input' AND state IN ('unknown','receipt')",new String[]{id,run,binding.getString("operationId")})!=1)throw new SecurityException("终端输入原回执登记已变化");appendEvent(required(id),new JSONObject().put("type","project_terminal_input_observed").put("operationId",binding.getString("operationId")).put("executionId",target).put("status",state).put("inputDigest",digest).put("writtenBytes",actual.optInt("writtenBytes")));
            JSONObject task=required(id);if(known&&"reconciling".equals(task.optString("status"))&&run.equals(task.optString("runId"))&&epoch==task.optInt("coordinatorEpoch")&&!hasUnresolved(id)){boolean ended=false;try(Cursor row=db.rawQuery("SELECT status FROM runs WHERE task_id=? AND run_id=? AND epoch=?",new String[]{id,run,String.valueOf(epoch)})){ended=row.moveToFirst()&&!"running".equals(row.getString(0));}if(ended){task.put("status",task.optBoolean("cancelRequested")?"paused":"awaiting_user").put("summary","项目输入与进程结果已核对，等待明确继续");write(task);}}
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
    public synchronized JSONObject projectProcessInputReceipt(JSONObject binding)throws Exception {JSONObject row=projectInputOperation(binding);JSONObject receipt=row.optJSONObject("receipt");return receipt==null?new JSONObject().put("code","project_input_unknown").put("status","unknown").put("accepted",JSONObject.NULL):new JSONObject(receipt.toString());}
    /** A stop is a new original-journal control intent, not another process or launch grant. */
    public synchronized JSONObject claimProjectServiceStop(JSONObject binding,String target,RunControl original)throws Exception {
        String id=binding.getString("taskId"),run=binding.getString("runId"),operation=binding.getString("operationId");int epoch=binding.getInt("coordinatorEpoch");
        fence(id,run,epoch,original,true,true,operation);checkCapabilityCurrent(id,run,epoch,"project_stop");JSONObject service=ownedServiceRow(id,run,epoch,target),intent=null;JSONArray rows=get(id,false).getJSONArray("operations");
        for(int i=0;i<rows.length();i++)if(operation.equals(rows.getJSONObject(i).optString("operationId")))intent=rows.getJSONObject(i);
        if(intent==null||!run.equals(intent.optString("runId"))||!"project_stop".equals(intent.optString("tool"))||!"prepared".equals(intent.optString("state"))||!target.equals(intent.getJSONObject("arguments").getString("executionId")))throw new SecurityException("缺少原prepared停止意图或目标已变化");
        JSONObject pending=new JSONObject().put("code","project_stop_pending_unknown").put("status","unknown").put("accepted",JSONObject.NULL).put("projectStopTarget",target).put("binding",binding).put("replayAllowed",false);
        ContentValues update=new ContentValues();update.put("state","unknown");update.put("receipt",pending.toString());if(db.update("operations",update,"task_id=? AND run_id=? AND operation_id=? AND tool='project_stop' AND state='prepared'",new String[]{id,run,operation})!=1)throw new SecurityException("停止意图已被消费");return service;
    }
    public synchronized JSONObject recordProjectServiceStop(JSONObject binding,String target)throws Exception {
        JSONObject task=get(binding.getString("taskId"),false);requirePrivateOwner(task);JSONArray rows=task.getJSONArray("operations");JSONObject intent=null;
        for(int i=0;i<rows.length();i++)if(binding.getString("operationId").equals(rows.getJSONObject(i).optString("operationId")))intent=rows.getJSONObject(i);
        if(intent==null||!binding.getString("runId").equals(intent.optString("runId"))||!"project_stop".equals(intent.optString("tool"))||!"unknown".equals(intent.optString("state"))||!target.equals(intent.getJSONObject("arguments").getString("executionId")))throw new SecurityException("停止回执不属于原意图");
        JSONObject receipt=new JSONObject().put("code","project_stop_requested").put("status","stop_requested").put("ok",true).put("accepted",true).put("processTerminal",false).put("executionId",target).put("projectStopTarget",target).put("binding",binding).put("replayAllowed",false).put("message","停止要求已交给原owned进程；是否已退出仍以原服务回执为准");
        ContentValues update=new ContentValues();update.put("state","receipt");update.put("receipt",sanitize(receipt).toString());if(db.update("operations",update,"task_id=? AND run_id=? AND operation_id=? AND tool='project_stop' AND state='unknown'",new String[]{binding.getString("taskId"),binding.getString("runId"),binding.getString("operationId")})!=1)throw new SecurityException("停止回执登记已变化");return receipt;
    }
    public synchronized JSONObject requireCapabilityApproval(String id,String runId,String tool,JSONObject args,JSONObject capability)throws Exception{
        JSONObject task=required(id);if(!runId.equals(task.optString("runId")))throw new SecurityException("审批执行代次已失效");
        String capabilityKey=canonical(capability);
        byte[] digest=java.security.MessageDigest.getInstance("SHA-256").digest(canonical(args).getBytes(StandardCharsets.UTF_8));StringBuilder encoded=new StringBuilder();for(byte value:digest)encoded.append(String.format(java.util.Locale.ROOT,"%02x",value&255));String hash=encoded.toString();
        JSONArray approvals=task.optJSONArray("approvals");if(approvals==null)approvals=new JSONArray();long now=System.currentTimeMillis();
        for(int i=0;i<approvals.length();i++){
            JSONObject a=approvals.getJSONObject(i);
            if(!tool.equals(a.optString("tool"))||!hash.equals(a.optString("argumentsHash")))continue;
            if(!capabilityKey.equals(a.optString("capabilityKey"))){
                if(a.optString("state").matches("pending|approved"))a.put("state","invalidated").put("reason","capability_changed");
                continue;
            }
            if(runId.equals(a.optString("runId"))&&a.optLong("expiresAt")>now){
                if(a.optString("state").equals("approved")){a.put("state","consumed");task.put("approvals",approvals);write(task);return new JSONObject().put("approved",true).put("approvalId",a.getString("id"));}
                if(a.optString("state").equals("pending")||a.optString("state").equals("denied"))return new JSONObject().put("approved",false).put("approvalId",a.getString("id")).put("state",a.getString("state"));
            }
        }
        if(approvals.length()>=100)throw new IllegalStateException("此任务审批记录已达上限");String approval=UUID.randomUUID().toString();
        approvals.put(new JSONObject().put("id",approval).put("runId",runId).put("tool",tool).put("capability",approvalArguments(capability)).put("capabilityKey",capabilityKey).put("arguments",approvalArguments(args)).put("argumentsHash",hash).put("state","pending").put("expiresAt",now+300_000L));task.put("approvals",approvals);write(task);return new JSONObject().put("approved",false).put("approvalId",approval).put("state","pending");
    }
    public synchronized JSONObject decideCapabilityApproval(String id,String approvalId,boolean approved)throws Exception{
        JSONObject task=required(id);JSONArray approvals=task.optJSONArray("approvals");if(approvals!=null)for(int i=0;i<approvals.length();i++){JSONObject a=approvals.getJSONObject(i);if(approvalId.equals(a.optString("id"))){
            if(task.optJSONObject("origin")!=null&&task.getJSONObject("origin").optString("kind").equals("group")&&!task.optString("runId").equals(a.optString("runId")))throw new SecurityException("审批不属于当前原群执行代次");
            if(!a.optString("state").equals("pending")||a.optLong("expiresAt")<=System.currentTimeMillis())throw new IllegalStateException("该审批已处理或过期");a.put("state",approved?"approved":"denied");write(task);return copy(task,false);}}
        throw new IllegalArgumentException("找不到该审批");
    }
    private static JSONObject approvalArguments(JSONObject source)throws Exception{
        JSONObject result=new JSONObject();java.util.Iterator<String> keys=source.keys();while(keys.hasNext()){String key=keys.next();if(key.matches("(?i)apiKey|api_key|authorization|token|secret|password")){result.put(key,"[已隐藏]");continue;}Object value=source.get(key);if(value instanceof JSONObject)value=approvalArguments((JSONObject)value);else if(value instanceof JSONArray){JSONArray copy=new JSONArray();JSONArray array=(JSONArray)value;for(int i=0;i<array.length();i++){Object item=array.get(i);copy.put(item instanceof JSONObject?approvalArguments((JSONObject)item):item);}value=copy;}result.put(key,value);}return result;
    }
    private static String canonical(Object value)throws Exception{
        if(value instanceof JSONObject){JSONObject object=(JSONObject)value;java.util.List<String> keys=new java.util.ArrayList<>();object.keys().forEachRemaining(keys::add);java.util.Collections.sort(keys);StringBuilder out=new StringBuilder("{");for(String key:keys){if(out.length()>1)out.append(',');out.append(JSONObject.quote(key)).append(':').append(canonical(object.get(key)));}return out.append('}').toString();}
        if(value instanceof JSONArray){JSONArray array=(JSONArray)value;StringBuilder out=new StringBuilder("[");for(int i=0;i<array.length();i++){if(i>0)out.append(',');out.append(canonical(array.get(i)));}return out.append(']').toString();}return value==JSONObject.NULL?"null":value instanceof String?JSONObject.quote((String)value):String.valueOf(value);
    }
    public static String capabilityFingerprint(JSONObject value)throws Exception{
        byte[] digest=java.security.MessageDigest.getInstance("SHA-256").digest(canonical(value).getBytes(StandardCharsets.UTF_8));StringBuilder result=new StringBuilder();for(byte b:digest)result.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return result.toString();
    }
    public synchronized void endAttempt(String id,String runId,String status,String summary,JSONObject evidence)throws Exception{
        db.beginTransaction();try{JSONObject t=required(id);if(!runId.equals(t.optString("runId")))return;
            boolean channel=t.optJSONObject("origin")!=null&&t.getJSONObject("origin").optString("kind").matches("channel|group");
            if(channel&&t.optString("status").equals("completed"))return;
            JSONObject origin=t.optJSONObject("origin");boolean plan=origin!=null&&"automation".equals(origin.optString("kind"))&&!origin.optString("planId").isEmpty();
            if((channel||plan)&&t.optBoolean("cancelRequested")){
                status="cancelled";summary="已停止后续派发；已有动作和结果保留，停止不代表撤销已发生的效果";
            }
            if(hasUnresolved(id))status="reconciling";
            JSONObject mailbox=t.optJSONObject("ownerInputMailbox");if(mailbox!=null&&runId.equals(mailbox.optString("runId"))){mailbox.put("open",false);t.put("ownerInputMailbox",mailbox);write(t);}
            JSONObject budgetOwner=teamPoolOwnerForChild(t);if(budgetOwner!=null){JSONObject pool=teamBudget(budgetOwner);dev.pocketengine.features.teams.TeamParentBudget.releaseUnstarted(pool,id,runId);budgetOwner.put("teamReservationPool",pool);write(budgetOwner);}
            finish(id,status,summary,evidence);ContentValues ended=new ContentValues();ended.put("status",status);ended.put("updated_at",System.currentTimeMillis());db.update("runs",ended,"run_id=?",new String[]{runId});db.delete("leases","run_id=?",new String[]{runId});db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
    public RunJournal journal(String id,String runId,int epoch,RunControl control){return new RunJournal(){
        @Override public int executionStepCeiling(int captured)throws Exception {synchronized(TaskStore.this){
            JSONObject task=required(id);if(task.optJSONObject("executionScope")==null)return captured;
            if(control==null||!control.matchesHostRun(id,runId,epoch))throw new SecurityException("没有原执行控制器");
            fence(id,runId,epoch,control,false);JSONObject permission=grant(runId);executionScopeCurrent(task,runId,permission);return permission.getInt("stepCeiling");
        }}
        @Override public boolean reserveModelRecovery(String failedCall,long waitMillis)throws Exception {synchronized(TaskStore.this){
            db.beginTransaction();try{
                fence(id,runId,epoch,control,true,true);JSONObject task=required(id),call=TaskAuditStore.call(db,id,failedCall);
                if(waitMillis<0||call==null||!"FAILED".equals(call.optString("outcome"))||!runId.equals(call.getJSONObject("context").optString("runId"))||epoch!=call.getJSONObject("context").optInt("epoch"))return false;
                JSONObject recovery=task.optJSONObject("modelRecoveryBudget");if(recovery==null)recovery=new JSONObject().put("waitedMillis",0L).put("retries",0).put("calls",new JSONArray());
                JSONArray calls=recovery.getJSONArray("calls");for(int i=0;i<calls.length();i++)if(failedCall.equals(calls.getString(i)))return false;
                long used=recovery.getLong("waitedMillis");int retries=recovery.getInt("retries");
                if(used<0||retries<0||retries>=dev.pocketengine.runtime.SafeModelRecovery.MAX_RETRIES_PER_TASK||waitMillis>dev.pocketengine.runtime.SafeModelRecovery.MAX_TOTAL_WAIT_MILLIS-used)return false;
                recovery.put("waitedMillis",used+waitMillis).put("retries",retries+1);calls.put(failedCall);task.put("modelRecoveryBudget",recovery);write(task);db.setTransactionSuccessful();return true;
            }finally{db.endTransaction();}
        }}

        @Override public InferenceCapture.Target inferenceTarget(InferenceReceipt.Purpose purpose,String memberId)throws Exception{
            InferenceAdmission admission=()->{
                if(control==null||!control.matchesHostRun(id,runId,epoch))throw new SecurityException("计量没有原任务控制器");
                fence(id,runId,epoch,control,true,true);
                if(acceptsPrivateOutputTask(required(id)))requireToolOutputSources(id,runId,epoch,control);
                if(!required(id).optString("status").matches("preparing|running"))throw new SecurityException("原执行已结束，未发出模型请求");
            };
            InferenceCapture.Target target=nativeInferenceTarget(id,runId,epoch,purpose,memberId,admission);
            // The same native SQL/volatile-Book check also runs before and after STARTED commit,
            // outside that commit's monitor. Never warm/read attachment bytes from this callback.
            return InferenceCapture.withAdmission(target,()->{synchronized(TaskStore.this){admission.check();}});
        }
        public JSONObject restore()throws Exception{synchronized(TaskStore.this){fence(id,runId,epoch,control,false);if(hasUnresolved(id)&&(control==null||!control.hasHostRun()||!control.matchesHostRun(id,runId,epoch)||!onlyOwnedProjectPending(id,runId,epoch,"")))throw new IllegalStateException("动作结果仍待核对，不能重放");return TaskStore.this.checkpoint(runId).put("feedback",required(id).optString("feedback")).put("operationResults",get(id,false).getJSONArray("operations"));}}
        @Override public boolean hasNativeProjectRunningAck(String operationId,JSONObject deliveredReceipt)throws Exception {
            if(control==null||!control.hasHostRun()||!control.matchesHostRun(id,runId,epoch))return false;control.check();synchronized(TaskStore.this){fence(id,runId,epoch,null,false);JSONObject row=ownedServiceRow(id,runId,epoch,operationId),args=row.getJSONObject("arguments"),plan=dev.pocketengine.features.local.project.ProjectBuildPlan.verified(args.getJSONObject("plan")),backend=row.getJSONObject("receipt").getJSONObject("executionReceipt"),deliveredBinding=deliveredReceipt.optJSONObject("binding");
                if(deliveredBinding==null||!id.equals(deliveredBinding.optString("taskId"))||!runId.equals(deliveredBinding.optString("runId"))||epoch!=deliveredBinding.optInt("coordinatorEpoch")||!operationId.equals(deliveredBinding.optString("operationId")))return false;
                for(String key:new String[]{"projectId","checkoutId","sourceDigest","planDigest"})if(!plan.getString(key).equals(deliveredReceipt.optString(key)))return false;
                return row.optString("state").matches("project_running|project_stopping")&&nativeOwnedService(dev.pocketengine.features.local.project.ProjectDevelopment.exact(plan,dev.pocketengine.features.local.project.ProjectBuildPlan.step(plan,args.getString("stepId"))),backend)
                        &&"project_running".equals(deliveredReceipt.optString("code"))&&"running".equals(deliveredReceipt.optString("status"))&&operationId.equals(deliveredReceipt.optString("operationId"))&&operationId.equals(deliveredReceipt.optString("executionId"))&&Boolean.TRUE.equals(deliveredReceipt.opt("accepted"))&&Boolean.FALSE.equals(deliveredReceipt.opt("terminal"))&&Boolean.FALSE.equals(deliveredReceipt.opt("receiptReceived"))
                        &&backend.getString("launchAckIdentity").equals(deliveredReceipt.optString("launchAckIdentity"));
            }
        }
        @Override public JSONObject projectServiceExitEvidence(JSONArray provenLaunchOperationIds)throws Exception {
            synchronized(TaskStore.this){fence(id,runId,epoch,null,false);if(provenLaunchOperationIds==null||provenLaunchOperationIds.length()==0||provenLaunchOperationIds.length()>100)return null;JSONArray executions=new JSONArray();java.util.Set<String> unique=new java.util.HashSet<>();
                for(int i=0;i<provenLaunchOperationIds.length();i++){
                    String operation=provenLaunchOperationIds.getString(i);if(!unique.add(operation))return null;JSONObject row=ownedServiceRow(id,runId,epoch,operation),actual=row.getJSONObject("receipt").getJSONObject("executionReceipt");
                    if(!"receipt".equals(row.optString("state"))||!actual.optString("status").matches("completed|failed")||!Boolean.TRUE.equals(actual.opt("receiptReceived"))||!Boolean.TRUE.equals(actual.opt("processExited"))||!Boolean.TRUE.equals(actual.opt("processGroupAbsent"))||!Boolean.TRUE.equals(actual.opt("resourcesReleased"))||!(actual.opt("exitCode") instanceof Integer||actual.opt("exitCode") instanceof Long))return null;
                    executions.put(new JSONObject().put("operationId",operation).put("executionId",operation).put("status",actual.getString("status")).put("exitCode",actual.get("exitCode")).put("terminationSignal",actual.optInt("terminationSignal")).put("cancelRequested",actual.optBoolean("cancelRequested")).put("timedOut",actual.optBoolean("timedOut")).put("processExited",true).put("processGroupAbsent",true).put("resourcesReleased",true));
                }
                return new JSONObject().put("format","pocketworld.project-service-exits.v1").put("taskId",id).put("runId",runId).put("coordinatorEpoch",epoch).put("executions",executions).put("allReleased",true);
            }
        }
        public void checkpoint(JSONArray messages,int steps,JSONObject observation)throws Exception{synchronized(TaskStore.this){fence(id,runId,epoch,control,false);JSONObject value=new JSONObject().put("messages",messages).put("steps",steps).put("observation",sanitize(observation==null?new JSONObject():observation));String encoded=value.toString();if(encoded.length()>4_000_000)throw new IllegalStateException("任务上下文过大，请先压缩后继续");writeOwnerInputCheckpoint(id,runId,epoch,value,encoded);}}
        public String beginOperation(String tool,JSONObject args)throws Exception{synchronized(TaskStore.this){
            db.beginTransaction();try{if(tool.equals("project_execute")){if(!canonical(args).equals(canonical(sanitize(args))))throw new SecurityException("原项目执行参数超过journal可无损记录范围，未登记prepared");if(!args.has("planRef"))dev.pocketengine.features.local.project.ProjectPlanRecords.requireInline(args);else if(args.has("plan"))throw new SecurityException("模型执行参数不能混合inline与planRef");projectArguments(id,runId,epoch,args);}boolean liveEdit=tool.equals("project_execute")&&projectLiveEditAllowed(id,runId,epoch,args),ptyInput=tool.equals("project_process_input")&&projectProcessInputAllowed(id,runId,epoch,args);if(ptyInput&&!canonical(args).equals(canonical(sanitize(args))))throw new SecurityException("原输入参数超过journal可无损记录范围");fence(id,runId,epoch,control,true,tool.equals("project_stop")||liveEdit||ptyInput||teamParentServiceAllowed(id,runId,epoch,tool,args,control));if(tool.equals("project_stop"))ownedServiceRow(id,runId,epoch,args.getString("executionId"));boolean phoneTool=!required(id).optString("executionMode").equals("capabilities")&&tool.matches("phone_(launch_app|click_node|tap|type|swipe|back|home)");if(!phoneTool&&!toolGranted(runId,tool))throw new SecurityException("宿主未授权此执行能力");
                JSONObject grant=grant(runId);JSONObject scopeTask=required(id);debitExecutionScope(scopeTask,runId,false);write(scopeTask);int remaining=grant.optInt("remainingOperations");if(remaining<=0)throw new IllegalStateException("本次授权操作预算已耗尽");grant.put("remainingOperations",remaining-1);
                ContentValues g=new ContentValues();g.put("grant_json",grant.toString());db.update("runs",g,"run_id=?",new String[]{runId});
                String op=UUID.randomUUID().toString();ContentValues v=new ContentValues();v.put("operation_id",op);v.put("task_id",id);v.put("run_id",runId);v.put("tool",tool);v.put("arguments",sanitize(args).toString());v.put("state","prepared");v.put("created_at",System.currentTimeMillis());db.insertOrThrow("operations",null,v);if(tool.equals("project_execute"))dev.pocketengine.features.local.project.ProjectPlanRecords.pin(db,op,args);reserveTeamProjectPrepared(id,runId,epoch,op,tool);JSONObject parent=required(id),parentPool=parent.optJSONObject("teamReservationPool");if(tool.matches("teams_(plan|start|message|control)")&&parentPool!=null&&parentPool.getJSONObject("budgetSource").optBoolean("includeParentInference"))reserveTeamParentPreparedJournal(parent,runId,epoch,op);appendEvent(required(id),new JSONObject().put("type","operation_intent").put("operationId",op).put("tool",tool).put("runId",runId));db.setTransactionSuccessful();return op;
            }finally{db.endTransaction();}
        }}
        public void finishOperation(String operationId,JSONObject receipt)throws Exception{synchronized(TaskStore.this){
            // A real receipt remains valuable after cancellation; only epoch fencing applies here.
            fence(id,runId,epoch,null,false);receipt=projectJournalReceipt(id,runId,operationId,preserveTeamRemoteReceipt(id,runId,operationId,preserveNativeSkillReceipt(id,operationId,receipt)));String code=receipt.optString("code",receipt.optString("error"));boolean unknown=code.contains("unknown")||code.equals("action_timeout")||receipt.optString("status").equals("unknown")||receipt.optString("state").contains("unknown")||receipt.has("accepted")&&receipt.isNull("accepted");
            String nativeState=null;try(Cursor row=db.rawQuery("SELECT state FROM operations WHERE task_id=? AND run_id=? AND operation_id=? AND tool='project_execute'",new String[]{id,runId,operationId})){if(row.moveToFirst()&&row.getString(0).matches("project_running|project_stopping")&&"project_running".equals(receipt.optString("code")))nativeState=row.getString(0);}
            ContentValues v=new ContentValues();v.put("state",nativeState==null?(unknown?"unknown":"receipt"):nativeState);JSONObject safeReceipt=sanitize(receipt);try(Cursor nativeRow=db.rawQuery("SELECT receipt FROM operations WHERE task_id=? AND run_id=? AND operation_id=? AND tool IN ('team_prepare_remote_workspace','team_submit_remote_job','team_cancel_remote_job','team_freeze_remote_contribution','team_import_remote_contribution')",new String[]{id,runId,operationId})){if(nativeRow.moveToFirst()&&!nativeRow.isNull(0)){JSONObject nativeReceipt=new JSONObject(nativeRow.getString(0));if(nativeReceipt.optJSONObject("teamRemote")!=null)safeReceipt.put("teamRemote",nativeReceipt.getJSONObject("teamRemote"));}}v.put("receipt",safeReceipt.toString());db.beginTransaction();try{if(db.update("operations",v,"operation_id=? AND run_id=?",new String[]{operationId,runId})!=1)throw new IllegalStateException("动作收据不属于当前执行");
            JSONObject budgetOwner=teamPoolOwnerForChild(required(id));if(budgetOwner!=null){JSONObject pool=teamBudget(budgetOwner);JSONArray reservations=pool.getJSONArray("reservations");for(int r=0;r<reservations.length();r++){JSONObject reserved=reservations.getJSONObject(r);if(reserved.optInt("externalJobs")==1)continue;JSONArray ops=reserved.getJSONArray("operationIds");for(int o=0;o<ops.length();o++)if(operationId.equals(ops.getString(o))){reserved.put("state",unknown||nativeState!=null?"unknown":"settled");JSONArray refs=reserved.getJSONArray("actualReceiptRefs");boolean found=false;for(int x=0;x<refs.length();x++)if(operationId.equals(refs.optString(x)))found=true;if(!found)refs.put(operationId);}}budgetOwner.put("teamReservationPool",pool);write(budgetOwner);}
            appendEvent(required(id),new JSONObject().put("type","operation_receipt").put("operationId",operationId).put("receipt",sanitize(receipt)));db.setTransactionSuccessful();}finally{db.endTransaction();}
        }}
    };}
    private void writeOwnerInputCheckpoint(String id,String run,int epoch,JSONObject value,String encoded)throws Exception{
        db.beginTransaction();try{
            JSONObject task=required(id);JSONArray inputs=task.optJSONArray("ownerInputs");
            if(OwnerInputQueue.checkpoint(inputs,value.optJSONArray("messages"),run,epoch))write(task);
            ContentValues saved=new ContentValues();saved.put("checkpoint",encoded);saved.put("updated_at",System.currentTimeMillis());
            if(db.update("runs",saved,"task_id=? AND run_id=? AND epoch=?",new String[]{id,run,Integer.toString(epoch)})!=1)throw new SecurityException("原上下文执行已不存在");
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
    /** Same private run/source authority used by memory_raw_query/read. No network is called here. */
    public CompactionJournal compactionJournal(String id,String runId,int epoch,RunControl original,
            TaskStoreMemoryReader.RawBinding binding,java.util.concurrent.Callable<ModelConfig> currentRoute){
        java.util.Objects.requireNonNull(binding);java.util.Objects.requireNonNull(currentRoute);
        if(binding.control!=original||!id.equals(binding.taskId)||!runId.equals(binding.runId)||epoch!=binding.epoch)throw new SecurityException("摘要与原件读取不是同一原生执行范围");
        return new CompactionJournal(){
            private JSONObject privateTask()throws Exception{return privateTask(true);}
            private JSONObject privateTask(boolean dispatch)throws Exception{
                binding.check();fence(id,runId,epoch,original,dispatch,dispatch);JSONObject task=required(id),origin=task.optJSONObject("origin");
                if(!"owner".equals(task.optString("actorId"))||!"private".equals(task.optString("spaceId"))||origin!=null&&origin.optString("kind").matches("group|channel")
                        ||!task.optString("status").matches("preparing|running"))throw new SecurityException("摘要仅供原私人活动任务使用");
                return task;
            }
            private long sourceCheckpointBytes()throws Exception{
                try(Cursor row=db.rawQuery("SELECT length(CAST(checkpoint AS BLOB)) FROM runs WHERE task_id=? AND run_id=? AND epoch=?",new String[]{id,runId,Integer.toString(epoch)})){
                    if(!row.moveToFirst()||row.isNull(0))throw new CompactionPlan.Unavailable("raw_run_unavailable");
                    long bytes=row.getLong(0);if(bytes<0||bytes>1_048_576)throw new CompactionPlan.Unavailable("raw_checkpoint_read_limited");return bytes;
                }
            }
            private JSONObject sourceCheckpoint()throws Exception{
                sourceCheckpointBytes();return TaskStore.this.checkpoint(runId);
            }
            private JSONArray operations()throws Exception{
                JSONArray rows=new JSONArray();long bytes=0,started=System.nanoTime();
                try(Cursor cursor=db.rawQuery("SELECT operation_id,tool,state,length(CAST(arguments AS BLOB))+coalesce(length(CAST(receipt AS BLOB)),0) FROM operations WHERE task_id=? AND run_id=? AND tool<>'native_memory_reference' ORDER BY operation_id LIMIT 1001",new String[]{id,runId})){
                    while(cursor.moveToNext()){
                        original.check();if(rows.length()>=1000||cursor.isNull(3)||cursor.getLong(3)<0||cursor.getLong(3)>1_048_576-bytes||System.nanoTime()-started>500_000_000L)throw new CompactionPlan.Unavailable("operation_barrier_read_limited");bytes+=cursor.getLong(3);
                        JSONObject row=new JSONObject().put("operationId",cursor.getString(0)).put("tool",cursor.getString(1)).put("state",cursor.getString(2));
                        try(Cursor exact=db.rawQuery("SELECT arguments,receipt FROM operations WHERE operation_id=? AND task_id=? AND run_id=?",new String[]{cursor.getString(0),id,runId})){
                            if(!exact.moveToFirst())throw new CompactionPlan.Unavailable("operation_barrier_changed");
                            row.put("argumentsFingerprint",CompactionPlan.fingerprint(exact.getString(0))).put("receiptFingerprint",CompactionPlan.fingerprint(exact.isNull(1)?JSONObject.NULL:exact.getString(1)));
                        }
                        rows.put(row);if(rows.length()>1000)throw new CompactionPlan.Unavailable("operation_barrier_read_limited");
                    }
                }return rows;
            }
            private JSONArray approvals(JSONObject task)throws Exception{
                JSONArray rows=new JSONArray(),all=task.optJSONArray("approvals");
                for(int i=0;all!=null&&i<all.length();i++){JSONObject row=all.getJSONObject(i);if(!runId.equals(row.optString("runId")))continue;
                    if(row.optString("state").matches("pending|approved"))throw new CompactionPlan.Unavailable("approval_not_settled");rows.put(row);}
                return rows;
            }
            private JSONObject state(CompactionPlan plan,JSONObject checkpoint)throws Exception{
                JSONObject task=privateTask();JSONArray messages=checkpoint.getJSONArray("messages"),ops=operations(),approvals=approvals(task);
                JSONArray included=new JSONArray(),pending=new JSONArray();java.util.Set<String> includedIds=new java.util.HashSet<>();
                for(int i=0;i<messages.length();i++){String message=messages.getJSONObject(i).optString("ownerMessageId");if(!message.isEmpty()){included.put(message);includedIds.add(message);}}
                JSONArray accepted=task.optJSONArray("ownerInputs");for(int i=0;accepted!=null&&i<accepted.length();i++){
                    JSONObject input=accepted.getJSONObject(i);if(OwnerInputQueue.withdrawn(input)||"materialized".equals(OwnerInputQueue.state(input))||includedIds.contains(input.getString("messageId")))continue;
                    if("steer".equals(input.optString("mode")))throw new CompactionPlan.Unavailable("owner_input_not_materialized");
                    pending.put(new JSONObject().put("messageId",input.getString("messageId")).put("payloadSha256",input.optString("payloadSha256")).put("mode",input.optString("mode")));
                }
                JSONObject inputs=new JSONObject().put("ownerInputs",task.optJSONArray("ownerInputs")==null?new JSONArray():task.getJSONArray("ownerInputs"))
                        .put("feedback",task.optString("feedback"));
                return new JSONObject().put("taskId",id).put("runId",runId).put("epoch",epoch).put("pauseRevision",original.pauseRevision()).put("sourceBarrier",binding.sourceRevision)
                        .put("inputsFingerprint",CompactionPlan.fingerprint(inputs)).put("approvalsFingerprint",CompactionPlan.fingerprint(approvals))
                        .put("includedInputsFingerprint",CompactionPlan.fingerprint(included)).put("pendingInputsFingerprint",CompactionPlan.fingerprint(pending))
                        .put("operationsFingerprint",CompactionPlan.fingerprint(ops)).put("operationSources",ops)
                        .put("routeFingerprint",CompactionPlan.routeFingerprint(currentRoute.call())).put("rawFingerprint",CompactionPlan.fingerprint(messages))
                        .put("tailFingerprint",CompactionPlan.fingerprint(CompactionPlan.slice(messages,Math.min(plan.endExclusive,messages.length()),messages.length())));
            }
            /** Reuses C4's exact native projection check, never a second retirement/projection rule. */
            private TaskStoreMemoryReader.RawBinding.RawMessageSession currentOriginals(JSONArray messages,java.util.List<Integer> indexes)throws Exception{
                long started=System.nanoTime();if(indexes.size()>CompactionPlan.MAX_COVERED_ROWS)throw new CompactionPlan.Unavailable("source_currentness_read_limited");
                // The original SQL read materialized the whole envelope, not messages alone.
                var session=binding.openMessageCurrentness(messages,sourceCheckpointBytes());
                try{
                    for(int index:indexes){original.check();if(System.nanoTime()-started>500_000_000L)throw new CompactionPlan.Unavailable("source_currentness_deadline");
                        session.requireCurrentOriginalMessage(index,messages.getJSONObject(index));}
                    return session;
                }catch(Exception failed){try{session.close();}catch(Exception closing){failed.addSuppressed(closing);}throw failed;}
            }
            private JSONArray sourceRefs(CompactionPlan plan,JSONArray messages)throws Exception{
                JSONArray refs=new JSONArray();long started=System.nanoTime();
                try(var current=currentOriginals(messages,plan.coveredIndexes())){
                for(int index:plan.coveredIndexes()){
                    original.check();if(System.nanoTime()-started>500_000_000L)throw new CompactionPlan.Unavailable("source_freeze_deadline");
                    JSONObject message=messages.getJSONObject(index),citation=new JSONObject().put("sourceId",TaskMemoryTrace.rawMessageId(id,runId,index))
                            .put("revision",TaskMemoryTrace.rawFingerprint(message)).put("offset",0).put("maxChars",16000);
                    // Same exact producer as the bounded RawAccess reader, over this one frozen original row.
                    int offset=0,total=-1;do{
                        citation.put("offset",offset);JSONObject page=TaskMemoryTrace.rawMessagePage(id,runId,message,citation),range=page.getJSONObject("readRange");
                        if(!"current".equals(page.optString("status"))||range.getInt("offset")!=offset)throw new CompactionPlan.Unavailable("raw_source_unavailable");
                        total=range.getInt("totalChars");offset+=range.getInt("chars");
                        if(offset<total&&range.getInt("chars")==0)throw new CompactionPlan.Unavailable("raw_source_read_limited");
                    }while(offset<total);
                    refs.put(new JSONObject().put("sourceId",citation.getString("sourceId")).put("revision",citation.getString("revision"))
                            .put("revisionKind","sha256_content_fingerprint").put("readRange",TaskMemoryTrace.rawRange(0,total,total)));
                }
                }catch(TaskStoreMemoryReader.RawMessageUnavailable unavailable){throw new CompactionPlan.Unavailable("raw_source_currentness_unavailable");}
                if(System.nanoTime()-started>500_000_000L)throw new CompactionPlan.Unavailable("source_freeze_deadline");return refs;
            }
            private boolean currentProjection(JSONObject projection,JSONObject checkpoint)throws Exception{
                if(projection==null||!CompactionCheckpoint.sourcesCurrent(projection,checkpoint.optJSONArray("messages")))return false;
                JSONObject saved=projection.getJSONObject("guard"),task=privateTask();
                if(!id.equals(saved.optString("taskId"))||!runId.equals(saved.optString("runId"))||epoch!=saved.optInt("epoch")
                        ||binding.sourceRevision!=saved.optLong("sourceBarrier",-1)||original.pauseRevision()!=saved.optLong("pauseRevision",-1)
                        ||!CompactionPlan.routeFingerprint(currentRoute.call()).equals(saved.optString("routeFingerprint")))return false;
                JSONObject inputs=new JSONObject().put("ownerInputs",task.optJSONArray("ownerInputs")==null?new JSONArray():task.getJSONArray("ownerInputs")).put("feedback",task.optString("feedback"));
                if(!CompactionPlan.fingerprint(inputs).equals(saved.optString("inputsFingerprint"))||!CompactionPlan.fingerprint(approvals(task)).equals(saved.optString("approvalsFingerprint")))return false;
                JSONArray actual=operations(),previous=saved.optJSONArray("operationSources");if(previous==null)return false;
                java.util.Map<String,String> now=new java.util.HashMap<>();for(int i=0;i<actual.length();i++)now.put(actual.getJSONObject(i).getString("operationId"),CompactionPlan.fingerprint(actual.getJSONObject(i)));
                for(int i=0;i<previous.length();i++){JSONObject row=previous.getJSONObject(i);if(!CompactionPlan.fingerprint(row).equals(now.get(row.getString("operationId"))))return false;}
                JSONArray covered=projection.getJSONObject("coverage").getJSONArray("coveredIndexes");java.util.List<Integer> indexes=new java.util.ArrayList<>();
                for(int i=0;i<covered.length();i++)indexes.add(covered.getInt(i));
                try(var current=currentOriginals(checkpoint.getJSONArray("messages"),indexes)){
                    return true; // Newly settled tail operations stay raw; every covered original remains readable.
                }catch(TaskStoreMemoryReader.RawMessageUnavailable unavailable){return false;}
            }
            public FrozenPrefix freeze(CompactionPlan plan,RunControl control)throws Exception{
                if(control!=original)throw new SecurityException("不是原摘要控制器");final long entryPause=original.pauseRevision();FrozenPrefix[] result={null};
                binding.admit(()->{synchronized(TaskStore.this){db.beginTransaction();try{
                    if(original.isPaused()||entryPause!=original.pauseRevision())throw new CompactionPlan.Unavailable("source_freeze_pause_changed");
                    JSONObject checkpoint=sourceCheckpoint(),guard=state(plan,checkpoint);
                    if(!plan.rawFingerprint.equals(guard.getString("rawFingerprint"))||!plan.routeFingerprint.equals(guard.getString("routeFingerprint")))throw new CompactionPlan.Unavailable("summary_snapshot_changed");
                    result[0]=FrozenPrefix.fromHost(plan,guard,sourceRefs(plan,checkpoint.getJSONArray("messages")),original);binding.check();if(entryPause!=original.pauseRevision())throw new CompactionPlan.Unavailable("source_freeze_pause_changed");db.setTransactionSuccessful();return new JSONObject();
                }finally{db.endTransaction();}}});if(entryPause!=original.pauseRevision())throw new CompactionPlan.Unavailable("source_freeze_pause_changed");return result[0];
            }
            public PublishReceipt publish(FrozenPrefix expected,SummaryDraft actual,RunControl control)throws Exception{
                if(control!=original||!actual.belongsTo(expected))throw new SecurityException("不是原SUMMARY候选");PublishReceipt[] result={new PublishReceipt("stale","")};
                binding.admit(()->{synchronized(TaskStore.this){boolean[] transactionOpen={true};db.beginTransaction();try{
                    expected.check(original);JSONObject checkpoint=sourceCheckpoint(),guard=state(expected.plan,checkpoint);
                    if(!expected.samePublicationState(guard)){db.setTransactionSuccessful();return new JSONObject();}
                    // One short source session, never across SUMMARY network I/O.
                    try(var current=currentOriginals(checkpoint.getJSONArray("messages"),expected.plan.coveredIndexes())){
                    String projectionId=UUID.nameUUIDFromBytes((id+":"+runId+":"+actual.callId).getBytes(StandardCharsets.UTF_8)).toString();
                    JSONObject next=CompactionCheckpoint.publish(checkpoint,expected,actual,projectionId);String encoded=next.toString();
                    if(encoded.getBytes(StandardCharsets.UTF_8).length>1_048_576){result[0]=new PublishReceipt("unavailable","");db.setTransactionSuccessful();return new JSONObject();}
                    // Finish native source revalidation before committing a candidate. A failed
                    // close must not leave a newly published derived field behind in SQLite.
                    current.close();
                    ContentValues value=new ContentValues();value.put("checkpoint",encoded);value.put("updated_at",System.currentTimeMillis());
                    // Hashing/authority work is done above. Only this short write+commit shares
                    // the original pause monitor, so a new pause cannot commit an old candidate.
                    original.dispatchAtPauseRevision(expected.pauseRevision,()->{
                        if(db.update("runs",value,"task_id=? AND run_id=? AND epoch=?",new String[]{id,runId,Integer.toString(epoch)})!=1)throw new SecurityException("摘要原执行已不存在");
                        db.setTransactionSuccessful();transactionOpen[0]=false;db.endTransaction();
                    });
                    result[0]=new PublishReceipt("published",projectionId);return new JSONObject();
                    }catch(TaskStoreMemoryReader.RawMessageUnavailable unavailable){throw new CompactionPlan.Unavailable("raw_source_currentness_unavailable");}
                }finally{if(transactionOpen[0])db.endTransaction();}}});return result[0];
            }
            public boolean current(FrozenPrefix published,RunControl control)throws Exception{
                if(control!=original)return false;boolean[] result={false};binding.admit(()->{synchronized(TaskStore.this){published.check(original);JSONObject checkpoint=sourceCheckpoint(),projection=checkpoint.optJSONObject("contextCompaction");
                    result[0]=projection!=null&&published.plan.rawPrefixFingerprint.equals(projection.getJSONObject("coverage").optString("rawPrefixFingerprint"))&&currentProjection(projection,checkpoint);return new JSONObject();}});return result[0];
            }
            @Override public boolean scopeCurrent(RunControl control)throws Exception{
                if(control!=original)return false;binding.admit(()->{synchronized(TaskStore.this){privateTask();return new JSONObject();}});return true;
            }
            @Override public void checkpoint(RunJournal delegate,JSONArray messages,int steps,JSONObject observation)throws Exception{
                try{binding.admit(()->{synchronized(TaskStore.this){privateTask(false);JSONObject previous=TaskStore.this.checkpoint(runId),projection=previous.optJSONObject("contextCompaction");
                    boolean current=false;try{current=projection!=null&&currentProjection(projection,previous);}catch(CompactionPlan.Unavailable|IllegalStateException invalidated){/* Keep the raw receipt, never a stale projection. */}
                    JSONObject next=new JSONObject().put("messages",messages).put("steps",steps).put("observation",sanitize(observation==null?new JSONObject():observation));
                    next=CompactionCheckpoint.append(previous,next,current);String encoded=next.toString();
                    if(encoded.getBytes(StandardCharsets.UTF_8).length>dev.pocketengine.runtime.context.ContextBudgetPolicy.CHECKPOINT_BYTES)throw new CompactionPlan.Unavailable("raw_checkpoint_read_limited");
                    // A source larger than the reader's inline cap remains raw, but may not carry a recoverability claim.
                    if(encoded.getBytes(StandardCharsets.UTF_8).length>1_048_576){next.remove("contextCompaction");encoded=next.toString();}
                    writeOwnerInputCheckpoint(id,runId,epoch,next,encoded);return new JSONObject();}});}
                catch(SecurityException staleScope){
                    // This is the original journal's audit write, not a model/source read grant.
                    // A pause can precede the next fresh native capability admission. Forget is
                    // different: never copy a stale in-memory derived body back over its retirement.
                    synchronized(TaskStore.this){
                        JSONArray audit=new JSONArray(messages.toString());if(!binding.sourceBarrierCurrent())dev.pocketengine.features.memory.TaskMemoryHistory.retire(audit,null,true);
                        JSONObject value=new JSONObject().put("messages",audit).put("steps",steps).put("observation",sanitize(observation==null?new JSONObject():observation));
                        if(value.toString().getBytes(StandardCharsets.UTF_8).length>dev.pocketengine.runtime.context.ContextBudgetPolicy.CHECKPOINT_BYTES)throw new CompactionPlan.Unavailable("raw_checkpoint_read_limited");
                        delegate.checkpoint(audit,steps,observation); // Rebuilds the original envelope; no old summary field is retained.
                    }
                }
            }
            @Override public JSONObject restore(RunJournal delegate)throws Exception{
                // Runner reads native feedback before the outer capability host can re-admit a
                // post-pause snapshot. This is the original audit restore, not model raw access.
                JSONObject result=delegate.restore(),projection=result.optJSONObject("contextCompaction");boolean valid=false;
                if(projection!=null)try{binding.check();synchronized(TaskStore.this){valid=currentProjection(projection,result);}}catch(SecurityException|CompactionPlan.Unavailable staleScope){valid=false;}
                if(!valid)result.remove("contextCompaction");return result;
            }
        };
    }
    private interface InferenceAdmission {void check()throws Exception;}
    /** Existing teams tasks have a native document runId/epoch=0, not a synthetic SQL HostRun. */
    public InferenceCapture.Target teamInferenceTarget(String nativeRunId,InferenceReceipt.Purpose purpose,String memberId,
                                                      InferenceReceipt.CallSite site,RunControl control,InferenceCapture.Admission current)throws Exception{
        if(control==null||current==null||site==null||!java.util.Set.of(InferenceReceipt.Purpose.MEMBER,InferenceReceipt.Purpose.SUMMARY).contains(purpose))
            throw new SecurityException("缺少原团队模型调用身份");
        current.check();String id;
        synchronized(this){
            id=operationTask("teams",nativeRunId);if(id==null)throw new SecurityException("团队模型调用没有原任务记录");
            checkTeamInferenceDocument(id,nativeRunId,site);
        }
        // A task identity is not a HostRun or a phone/tool grant. Never invent a runs row/epoch.
        if(control.hasHostRun())throw new SecurityException("团队内部控制不能替换原HostRun");
        control.bindTask(id);
        InferenceReceipt.Context context=InferenceReceipt.Context.externalTaskRun(id,nativeRunId,purpose,memberId,site);
        return new InferenceCapture.Target(context,new InferenceCapture.Sink(){
            @Override public void started(InferenceReceipt receipt)throws Exception{
                synchronized(TaskStore.this){checkTeamInferenceDocument(id,nativeRunId,site);recordInference(context,receipt,true);}
            }
            @Override public void finished(InferenceReceipt receipt)throws Exception{
                synchronized(TaskStore.this){recordInference(context,receipt,false);}
            }
        },current);
    }
    private void checkTeamInferenceDocument(String id,String nativeRunId,InferenceReceipt.CallSite site)throws Exception{
        JSONObject task=required(id);
        if(!"teams".equals(task.optString("kind"))||!"teams".equals(task.optString("feature"))||!"start".equals(task.optString("command"))
                ||!"owner".equals(task.optString("actorId"))||!"private".equals(task.optString("spaceId"))
                ||!nativeRunId.equals(task.optString("runId"))||task.optInt("coordinatorEpoch")!=0||task.optBoolean("cancelRequested")
                ||!task.optString("status").matches("queued|running"))throw new SecurityException("原团队任务已停止或身份不匹配");
        JSONObject evidence=task.optJSONObject("evidence");
        if(evidence!=null&&evidence.optInt("generation",0)>site.runGeneration)throw new SecurityException("旧团队代次不能发出新模型请求");
    }
    private InferenceCapture.Target nativeInferenceTarget(String id,String runId,int epoch,InferenceReceipt.Purpose purpose,String memberId,
                                                          InferenceAdmission admission){
        InferenceReceipt.Context context=new InferenceReceipt.Context(id,runId,epoch,purpose,memberId);
        return new InferenceCapture.Target(context,new InferenceCapture.Sink(){
            @Override public void started(InferenceReceipt receipt)throws Exception{
                synchronized(TaskStore.this){admission.check();recordInference(context,receipt,true);}
            }
            @Override public void finished(InferenceReceipt receipt)throws Exception{
                // An old/ended run's real accounting remains evidence, never a new dispatch grant.
                synchronized(TaskStore.this){recordInference(context,receipt,false);}
            }
        });
    }
    private void recordInference(InferenceReceipt.Context expected,InferenceReceipt receipt,boolean starting)throws Exception{
        InferenceReceipt.Context actual=receipt.context;
        if(!canonical(expected.toJson()).equals(canonical(actual.toJson()))
                ||starting!=(receipt.outcome==InferenceReceipt.Outcome.STARTED))throw new SecurityException("模型计量不是原生调用身份");
        db.beginTransaction();try{
            if(expected.bindingKind==InferenceReceipt.BindingKind.HOST_RUN){
                try(Cursor run=db.rawQuery("SELECT task_id,epoch FROM runs WHERE run_id=?",new String[]{expected.runId})){
                    if(!run.moveToFirst()||!expected.taskId.equals(run.getString(0))||expected.epoch!=run.getInt(1))throw new SecurityException("模型计量的原执行不存在");
                }
            }else if(expected.bindingKind==InferenceReceipt.BindingKind.EXTERNAL_TASK_RUN){
                JSONObject original=required(expected.taskId);
                if(!"teams".equals(original.optString("kind"))||!"teams".equals(original.optString("feature")))throw new SecurityException("原团队调用来源不存在");
                if(starting)checkTeamInferenceDocument(expected.taskId,expected.runId,expected.callSite);
            }else{
                throw new SecurityException("未归因调用不能写入某个原任务");
            }
            JSONObject task=required(expected.taskId),next=receipt.toJson(),budgetOwner=teamPoolOwnerForChild(task);
            if(budgetOwner!=null){JSONObject pool=teamBudget(budgetOwner);if(starting){if(task.getString("id").equals(budgetOwner.getString("id"))&&pool.getJSONObject("budgetSource").optBoolean("includeParentInference"))reserveTeamParentNativeCall(pool,task.getString("id"),next);if(expected.bindingKind==InferenceReceipt.BindingKind.EXTERNAL_TASK_RUN)dev.pocketengine.features.teams.TeamParentBudget.startInferenceExact(pool,expected.taskId,expected.runId,expected.memberId,expected.toJson().getJSONObject("callSite"),receipt.callId);else dev.pocketengine.features.teams.TeamParentBudget.startInference(pool,expected.taskId,expected.runId,receipt.callId);}else dev.pocketengine.features.teams.TeamParentBudget.finishInference(pool,receipt.callId,next);budgetOwner.put("teamReservationPool",pool);write(budgetOwner);if(task.getString("id").equals(budgetOwner.getString("id")))task=required(task.getString("id"));}
            JSONObject old=TaskAuditStore.call(db,expected.taskId,receipt.callId);
            if(old==null){
                if(!starting)throw new SecurityException("没有本次调用的原生开始记录");
                JSONObject chargedOwner=budgetOwner==null?task:required(budgetOwner.getString("id"));
                String chargedRun=budgetOwner==null?expected.runId:teamBudget(chargedOwner).getString("parentRunId");
                debitExecutionScope(chargedOwner,chargedRun,true);
                if(chargedOwner.getString("id").equals(task.getString("id")))task=chargedOwner;else write(chargedOwner);
                next.put("at",System.currentTimeMillis());TaskAuditStore.append(db,expected.taskId,next);
            }else{
                JSONObject oldRoute=new JSONObject(old.getJSONObject("route").toString()),newRoute=new JSONObject(next.getJSONObject("route").toString());oldRoute.remove("responseModel");newRoute.remove("responseModel");
                if(!canonical(old.getJSONObject("context")).equals(canonical(next.getJSONObject("context")))||!canonical(oldRoute).equals(canonical(newRoute))||old.getLong("startedAt")!=receipt.startedAt)throw new SecurityException("不能替换原模型调用的归属或连接");
                if(!"STARTED".equals(old.getString("outcome"))){JSONObject prior=new JSONObject(old.toString());prior.remove("at");prior.remove("seq");if(starting||canonical(prior).equals(canonical(next))){db.setTransactionSuccessful();return;}throw new SecurityException("本次模型调用已有不同终态计量");}
                if(starting){db.setTransactionSuccessful();return;}
                next.put("seq",old.getLong("seq")).put("at",System.currentTimeMillis());TaskAuditStore.replace(db,expected.taskId,old.getLong("seq"),next);
            }
            write(task);db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
    /** Bounded single native receipt for an owner-only alternate estimate; no full task/audit materialization. */
    public synchronized JSONObject inferenceReceiptForEstimate(String id,String callId)throws Exception{
        if(id==null||callId==null||!id.matches("[a-fA-F0-9-]{36}")||!callId.matches("[a-fA-F0-9-]{36}"))throw new IllegalArgumentException("请指定原任务和原调用身份");
        long bytes=-1;int matches=0;
        try(Cursor rows=db.rawQuery("SELECT e.body_bytes FROM task_events e JOIN tasks t ON t.task_id=e.task_id WHERE e.task_id=? AND e.event_type='inference_receipt' AND e.call_id=? LIMIT 2",new String[]{id,callId})){
            while(rows.moveToNext()){matches++;bytes=rows.getLong(0);}}
        if(matches!=1||bytes<0||bytes>65536)return new JSONObject().put("available",false).put("callId",callId).put("reason",matches>1?"receipt_identity_conflict":matches==0?"receipt_not_found":"receipt_read_limited").put("maxBytes",65536);
        JSONObject receipt=TaskAuditStore.call(db,id,callId),context=receipt==null?null:receipt.optJSONObject("context");
        if(context==null||!id.equals(context.optString("taskId"))||!callId.equals(receipt.optString("callId")))throw new SecurityException("原调用不属于此任务");
        return new JSONObject().put("available",true).put("receipt",receipt).put("sourceBytes",bytes).put("maxBytes",65536);
    }
    /** Owner UI derives this from the sole original event history; reads create no calls or task transitions. */
    public synchronized JSONObject inferenceUsage(String id)throws Exception{
        required(id);return InferenceProjection.fromEvents(TaskAuditStore.events(db,id,"inference_receipt"));
    }
    /** Model-return clipping must not erase the native original task's file references or terminal receipt. */
    /** Runner result JSON cannot replace the native SQL action/claim that actually ran. */
    private JSONObject preserveTeamRemoteReceipt(String task,String run,String operation,JSONObject returned)throws Exception {
        try(Cursor row=db.rawQuery("SELECT tool,receipt FROM operations WHERE task_id=? AND run_id=? AND operation_id=?",new String[]{task,run,operation})){
            if(!row.moveToFirst()||!row.getString(0).matches("team_(prepare_remote_workspace|submit_remote_job|cancel_remote_job|freeze_remote_contribution|import_remote_contribution|reclaim_remote_workspace)"))return returned;
            JSONObject result=new JSONObject(returned.toString());result.remove("teamRemote");if(row.isNull(1))return result;JSONObject saved=new JSONObject(row.getString(1)),record=saved.optJSONObject("teamRemote");if(record==null)return result;
            // Only the persisted native row is authority; never accept a copied model/port claim.
            JSONObject actual=record.optJSONObject("actualReceipt");if(actual!=null)result=new JSONObject(actual.toString());result.put("teamRemote",new JSONObject(record.toString())).put("status",record.optString("status").equals("completed")?"completed":"unknown").put("accepted",record.optString("status").equals("completed")?true:JSONObject.NULL);return result;
        }
    }
    private JSONObject teamNativeArtifactReceipt(JSONObject operation)throws Exception {
        JSONObject receipt=operation.optJSONObject("receipt");if(receipt==null)return null;if(operation.optString("tool").equals("team_freeze_outputs"))return receipt;
        if(!operation.optString("tool").equals("team_import_remote_contribution"))return null;JSONObject record=receipt.optJSONObject("teamRemote");if(record==null||!record.optString("action").equals("IMPORT")||!record.optString("status").equals("completed"))return null;JSONObject actual=record.optJSONObject("actualReceipt");if(actual==null||!actual.optBoolean("importedToOriginalDraft")||!Boolean.FALSE.equals(actual.opt("integrated"))||!record.getJSONObject("scope").getString("manifestHash").equals(actual.optString("manifestHash")))throw new SecurityException("原IMPORT成果缺少native贡献scope回执");return actual;
    }
    private JSONObject preserveNativeSkillReceipt(String task,String operation,JSONObject returned)throws Exception {
        try(Cursor row=db.rawQuery("SELECT tool,arguments,receipt FROM operations WHERE task_id=? AND operation_id=?",new String[]{task,operation})){
            if(!row.moveToFirst()||!row.getString(0).equals("skills_execute")||row.isNull(2)||!new JSONObject(row.getString(1)).has("nativeSkill"))return returned;
            JSONObject actual=new JSONObject(row.getString(2));if(actual.optString("status").matches("completed|failed|cancelled|timed_out|rejected"))return actual;
            JSONObject merged=new JSONObject(returned.toString());for(String key:new String[]{"artifacts","missingArtifactPaths","artifactsTruncated"})if(actual.has(key))merged.put(key,actual.get(key));return merged;
        }
    }
    /** Native Skill review attaches to the exact effect intent already created by AgentRunner. */
    public synchronized void reserveSkillExecution(dev.pocketengine.features.skills.SkillExecution.Plan plan,dev.pocketengine.features.skills.SkillExecution.Grant grant,JSONObject deployment,RunControl control)throws Exception {
        checkSkillRun(plan,grant,control);JSONObject operation=skillOperation(grant.taskId,grant.operationId),arguments=operation.getJSONObject("arguments");
        if(!"prepared".equals(operation.getString("state"))||arguments.has("nativeSkill"))throw new SecurityException("原脚本操作已登记，不能重建执行计划");
        var ref=dev.pocketengine.features.skills.CompanionSkillBackend.ReceiptRef.from(plan,grant);
        JSONObject nativeSkill=new JSONObject().put("format","pocketworld.native-skill-operation.v1").put("approvalId",grant.approvalId).put("approvalState","pending")
                .put("grantHash",capabilityFingerprint(new JSONObject().put("backendGrantId",grant.backendGrantId))).put("expiresAt",grant.expiresAt)
                .put("reference",dev.pocketengine.features.skills.SkillExecutionRecords.reference(ref)).put("deployment",new JSONObject(deployment.toString()))
                .put("review",plan.review()).put("dispatchState","not_dispatched").put("cancelPending",false);
        arguments.put("nativeSkill",nativeSkill);writeSkillArguments(grant.taskId,grant.operationId,arguments);
    }
    /** Called only with the retained native Plan after the actual review screen's explicit decision. */
    public synchronized void decideSkillExecution(dev.pocketengine.features.skills.SkillExecution.Plan plan,dev.pocketengine.features.skills.SkillExecution.Grant grant,RunControl control,boolean approved)throws Exception {
        checkSkillRun(plan,grant,control);JSONObject row=skillOperation(grant.taskId,grant.operationId),args=row.getJSONObject("arguments"),nativeSkill=skillMetadata(args);
        checkSkillGrant(plan,grant,nativeSkill);
        if(!nativeSkill.getString("approvalState").equals("pending")||!nativeSkill.getString("dispatchState").equals("not_dispatched"))throw new SecurityException("原脚本审批已处理");
        nativeSkill.put("approvalState",approved?"consumed":"denied").put("decidedAt",System.currentTimeMillis());writeSkillArguments(grant.taskId,grant.operationId,args);
    }
    public synchronized void checkSkillExecution(dev.pocketengine.features.skills.SkillExecution.Plan plan,dev.pocketengine.features.skills.SkillExecution.Grant grant,RunControl control)throws Exception {
        checkSkillRun(plan,grant,control);JSONObject nativeSkill=skillMetadata(skillOperation(grant.taskId,grant.operationId).getJSONObject("arguments"));checkSkillGrant(plan,grant,nativeSkill);
        if(!nativeSkill.getString("approvalState").equals("consumed")||nativeSkill.optBoolean("cancelPending")||!nativeSkill.getString("dispatchState").matches("not_dispatched|claimed"))throw new SecurityException("原脚本未获本次批准、已停止或已结束");
    }
    /** Short SQLite CAS completes before any package lock, remote probe, staging or submit. */
    public synchronized boolean claimSkillExecution(dev.pocketengine.features.skills.SkillExecution.Plan plan,dev.pocketengine.features.skills.SkillExecution.Grant grant,dev.pocketengine.features.skills.CompanionSkillBackend.ReceiptRef ref,RunControl control)throws Exception {
        checkSkillExecution(plan,grant,control);JSONObject row=skillOperation(ref.taskId,ref.operationId),args=row.getJSONObject("arguments"),nativeSkill=skillMetadata(args);checkSkillRef(ref,nativeSkill);
        if(!nativeSkill.getString("dispatchState").equals("not_dispatched"))return false;
        nativeSkill.put("dispatchState","claimed").put("claimedAt",System.currentTimeMillis());writeSkillArguments(ref.taskId,ref.operationId,args);return true;
    }
    /** Receipt reads remain possible after cancellation, expiry, package removal and process restart. */
    public synchronized JSONObject readSkillExecution(String taskId,String operationId)throws Exception {
        JSONObject task=required(taskId);requirePrivateOwner(task);JSONObject row=skillOperation(taskId,operationId),metadata=skillMetadata(row.getJSONObject("arguments"));
        JSONObject result=new JSONObject(metadata.toString()).put("operationState",row.getString("state"));if(row.optJSONObject("receipt")!=null)result.put("receipt",row.getJSONObject("receipt"));return result;
    }
    /** An audit on the original Runner-prepared operation. The opaque Book selection only pins
     * a current learned method; it never grants execution or lets JSON mint source authority. */
    public JSONObject recordLearnedMethodDecision(String id,String run,int epoch,String operation,dev.pocketengine.features.memory.MemoryBook.ProcedureSelection selected,RunControl original)throws Exception{
        java.util.Objects.requireNonNull(selected).requireNoBookMonitorHeld();
        synchronized(this){
        db.beginTransaction();try{
            checkCapability(id,run,epoch,"memory_method_decision",original);requirePrivateOwner(required(id));requireOutputTask(required(id));selected.checkCurrent();
            if(!original.matchesHostRun(id,run,epoch)||hasUnresolvedExcept(id,operation))throw new SecurityException("原方法决定的控制器失效或还有其它未决操作");
            if(!"owner".equals(selected.ownerId)||!"private".equals(selected.spaceId))throw new SecurityException("方法选择不属于原私人任务");
            JSONObject row=learnedMethodDecisionOperation(id,operation),args=row.getJSONObject("arguments");
            if(!run.equals(row.getString("runId"))||!"prepared".equals(row.getString("state"))||args.has("nativeMethodDecision"))throw new SecurityException("原方法决定已记录或不属于本轮；请查询原回执");
            if(args.length()!=5)throw new SecurityException("方法决定只接受准确版本、决定、理由与原步骤标识");
            for(var keys=args.keys();keys.hasNext();)if(!java.util.Set.of("recordId","revision","decision","reason","stepOperationIds").contains(keys.next()))throw new SecurityException("方法决定不能携带替代授权或回执");
            if(!selected.recordId.equals(args.getString("recordId"))||selected.revision!=learnedMethodInteger(args,"revision",1))throw new SecurityException("方法选择与原操作参数版本不一致");
            String decision=args.getString("decision"),reason=args.getString("reason");
            if(!java.util.Set.of("apply","not_applicable","not_selected").contains(decision)||reason.trim().isEmpty()||reason.length()>1000)throw new SecurityException("必须明确说明采用、不适用或未选的理由");
            if(!java.nio.charset.StandardCharsets.UTF_8.newEncoder().canEncode(reason))throw new SecurityException("方法决定理由包含不完整字符");JSONArray ids=args.getJSONArray("stepOperationIds");
            if(ids.length()>8||decision.equals("apply")&&ids.length()<1||!decision.equals("apply")&&ids.length()!=0)throw new SecurityException("采用须关联1至8个原步骤；未采用不伪造步骤");
            JSONArray steps=learnedMethodStepFacts(id,run,operation,ids,row.getLong("createdAt"));
            JSONObject fact=selected.metadata().put("format","pocketworld.learned-method-decision.v1").put("taskId",id).put("runId",run).put("epoch",epoch).put("operationId",operation)
                    .put("decision",decision).put("reason",reason).put("stepRefs",steps).put("recordedAt",System.currentTimeMillis())
                    .put("applicationState",decision.equals("apply")?"model_apply_with_native_steps":decision).put("backendOutcome",decision.equals("apply")?"native_step_receipts_confirmed":"not_attempted")
                    .put("applicationScope","explicit_model_decision_and_original_step_receipts").put("methodSemanticUseIndependentlyVerified",false).put("methodBusinessSuccessVerified",false).put("executionAuthorized",false).put("replayAllowed",false);
            selected.checkCurrent();args.put("nativeMethodDecision",fact);ContentValues value=new ContentValues();value.put("arguments",args.toString());
            if(db.update("operations",value,"task_id=? AND run_id=? AND operation_id=? AND tool='memory_method_decision' AND state='prepared'",new String[]{id,run,operation})!=1)throw new SecurityException("原方法决定操作已变化");
            dev.pocketengine.features.memory.OwnerSourceRunContext.formalPrepared(db,id,run,new JSONArray().put(new JSONObject().put("id",selected.recordId).put("revision",selected.revision)));
            original.check();selected.checkCurrent();db.setTransactionSuccessful();return new JSONObject().put("ok",true).put("code","method_decision_recorded").put("methodDecision",new JSONObject(fact.toString())).put("executionAuthorized",false);
        }finally{db.endTransaction();}}
    }
    private static long learnedMethodInteger(JSONObject value,String key,long minimum)throws Exception{
        Object raw=value.opt(key);if(!(raw instanceof Integer||raw instanceof Long)||((Number)raw).longValue()<minimum)throw new SecurityException("原方法步骤的"+key+"不是准确整数");return((Number)raw).longValue();
    }
    private JSONObject learnedMethodDecisionOperation(String id,String operation)throws Exception{
        try(Cursor row=db.rawQuery("SELECT run_id,state,created_at,CASE WHEN length(CAST(arguments AS BLOB))<=16384 THEN arguments END,CASE WHEN length(CAST(receipt AS BLOB))<=65536 THEN receipt END FROM operations WHERE task_id=? AND operation_id=? AND tool='memory_method_decision'",new String[]{id,operation})){
            if(!row.moveToFirst()||row.isNull(3))throw new SecurityException("缺少有界原方法决定操作");JSONObject value=new JSONObject().put("runId",row.getString(0)).put("state",row.getString(1)).put("createdAt",row.getLong(2)).put("arguments",new JSONObject(row.getString(3)));if(!row.isNull(4))value.put("receipt",new JSONObject(row.getString(4)));return value;
        }
    }
    private JSONArray learnedMethodStepFacts(String id,String run,String decisionOperation,JSONArray ids,long decisionAt)throws Exception{
        JSONArray result=new JSONArray();java.util.Set<String> unique=new java.util.HashSet<>();
        for(int i=0;i<ids.length();i++){String operation=ids.getString(i);
            if(!operation.matches("[a-f0-9-]{36}")||operation.equals(decisionOperation)||!unique.add(operation))throw new SecurityException("方法原步骤标识重复或无效");
            try(Cursor row=db.rawQuery("SELECT tool,state,created_at,CASE WHEN length(CAST(receipt AS BLOB))<=65536 THEN receipt END,CASE WHEN length(CAST(arguments AS BLOB))<=32768 THEN arguments END FROM operations WHERE task_id=? AND run_id=? AND operation_id=?",new String[]{id,run,operation})){
                if(!row.moveToFirst()||!"receipt".equals(row.getString(1))||row.getLong(2)>decisionAt||row.isNull(3)||row.getString(0).matches("memory_.*|native_.*|skills_.*"))throw new SecurityException("关联步骤不是同轮已发生的原执行结果");
                JSONObject receipt=new JSONObject(row.getString(3));String code=receipt.optString("code"),status=receipt.optString("status");
                // Workspace writes return their real VWS revision rather than a synthetic ok flag.
                // Accept only that native route's exact path/CAS/revision shape, never an arbitrary
                // model-supplied success-looking JSON object or a receipt from another operation.
                boolean workspaceWrite=false;
                if("workspace_write".equals(row.getString(0))&&!row.isNull(4)){
                    JSONObject args=new JSONObject(row.getString(4));long before=learnedMethodInteger(args,"expectedVersion",0);
                    workspaceWrite=before<Integer.MAX_VALUE&&args.getString("path").equals(receipt.optString("path"))
                            &&receipt.opt("version") instanceof Number&&learnedMethodInteger(receipt,"version",1)==before+1
                            &&receipt.optString("revisionId").matches("[a-f0-9-]{36}")&&Boolean.FALSE.equals(receipt.opt("deleted"));
                }
                if(Boolean.FALSE.equals(receipt.opt("accepted"))||receipt.has("accepted")&&receipt.isNull("accepted")||code.contains("unknown")||status.matches("unknown|pending|running|awaiting_receipt")
                        ||code.matches("approval_required|not_dispatched|invalid_arguments|source_unavailable|cancelled_before_dispatch")
                        ||!workspaceWrite&&!Boolean.TRUE.equals(receipt.opt("ok"))&&!Boolean.TRUE.equals(receipt.opt("accepted"))&&!status.matches("completed|success"))throw new SecurityException("未知、未派发或缺少确定回执的步骤不计采用证据");
                result.put(new JSONObject().put("operationId",operation).put("tool",row.getString(0)).put("operationState","receipt").put("resultStatus",status.isEmpty()?code:status).put("receiptFingerprint",capabilityFingerprint(receipt)));
            }
        }return result;
    }
    private JSONObject learnedMethodDecisionProjection(String id,String operation,String record,long revision)throws Exception{
        JSONObject row=learnedMethodDecisionOperation(id,operation),args=row.getJSONObject("arguments"),fact=args.optJSONObject("nativeMethodDecision");
        if(!record.equals(args.optString("recordId"))||revision!=args.optLong("revision"))return null;
        if(fact==null)throw new SecurityException("原方法决定尚无已保存的原生证明");
        JSONObject receipt=row.optJSONObject("receipt");
        if(!"receipt".equals(row.optString("state"))||receipt==null||!"pocketworld.learned-method-decision.v1".equals(fact.optString("format"))
                ||!id.equals(fact.optString("taskId"))||!operation.equals(fact.optString("operationId"))||!row.getString("runId").equals(fact.optString("runId"))
                ||!"owner".equals(fact.optString("ownerId"))||!"private".equals(fact.optString("spaceId"))||!canonical(fact).equals(canonical(receipt.getJSONObject("methodDecision"))))throw new SecurityException("原方法决定没有同一操作的最终回执");
        JSONArray steps=fact.getJSONArray("stepRefs"),ids=new JSONArray();for(int i=0;i<steps.length();i++)ids.put(steps.getJSONObject(i).getString("operationId"));
        if(!canonical(steps).equals(canonical(learnedMethodStepFacts(id,row.getString("runId"),operation,ids,row.getLong("createdAt")))))throw new SecurityException("原方法步骤回执已变化");
        return new JSONObject(fact.toString()).put("recordRevision",revision);
    }
    /** Owner-only bounded projection; original operations remain the sole application ledger. */
    public synchronized JSONObject learnedMethodApplications(String record,long revision,String cursor,int requested)throws Exception{
        if(record==null||record.isBlank()||record.length()>240||revision<1||requested<1||requested>50||cursor!=null&&cursor.length()>2048)throw new IllegalArgumentException("请指定真实经验版本和读取窗口");
        long after=Long.MAX_VALUE;String afterId="";if(cursor!=null&&!cursor.isEmpty()){
            JSONObject value=new JSONObject(new String(java.util.Base64.getUrlDecoder().decode(cursor),java.nio.charset.StandardCharsets.UTF_8));
            if(!record.equals(value.optString("recordId"))||revision!=value.optLong("revision"))throw new SecurityException("方法应用游标属于其它经验版本");after=value.getLong("createdAt");afterId=value.getString("operationId");
            if(!afterId.matches("[a-f0-9-]{36}")||after<0)throw new SecurityException("原方法应用游标无效");
        }
        JSONArray results=new JSONArray();int scanned=0,limited=0,unavailable=0;String next="";boolean more=false;
        try(Cursor rows=db.rawQuery("SELECT operation_id,task_id,created_at,length(CAST(arguments AS BLOB)),coalesce(length(CAST(receipt AS BLOB)),0),tool FROM operations WHERE tool IN ('skills_execute','memory_method_decision') AND (created_at<? OR (created_at=? AND operation_id<?)) ORDER BY created_at DESC,operation_id DESC LIMIT 201",new String[]{Long.toString(after),Long.toString(after),afterId})){
            while(rows.moveToNext()){
                if(scanned>=200||results.length()>=requested){more=true;break;}scanned++;
                next=java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(new JSONObject().put("recordId",record).put("revision",revision).put("createdAt",rows.getLong(2)).put("operationId",rows.getString(0)).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                if(rows.getLong(3)>131072||rows.getLong(4)>65536){limited++;continue;}
                try(Cursor size=db.rawQuery("SELECT length(CAST(document AS BLOB)) FROM tasks WHERE task_id=?",new String[]{rows.getString(1)})){if(!size.moveToFirst()||size.getLong(0)>1_048_576){limited++;continue;}}
                JSONObject task=read(rows.getString(1));if(task==null||!"owner".equals(task.optString("actorId"))||!"private".equals(task.optString("spaceId"))||task.optJSONObject("origin")!=null&&task.getJSONObject("origin").optString("kind").matches("channel|group"))continue;
                try{JSONObject projected=rows.getString(5).equals("memory_method_decision")?learnedMethodDecisionProjection(rows.getString(1),rows.getString(0),record,revision):dev.pocketengine.features.memory.LearnedMethodReceipt.project(readSkillExecution(rows.getString(1),rows.getString(0)),record,revision,"owner","private");if(projected!=null)results.put(projected);}
                catch(org.json.JSONException|SecurityException|IllegalArgumentException|IllegalStateException invalidNativeProof){unavailable++;}
            }
        }
        return new JSONObject().put("recordId",record).put("recordRevision",revision).put("applications",results).put("nextCursor",more?next:"").put("scannedOperations",scanned).put("readLimitedOperations",limited).put("unavailableNativeProofs",unavailable).put("completeHistory",!more&&limited==0&&unavailable==0).put("executionAuthorized",false);
    }
    public synchronized void cancelSkillExecution(dev.pocketengine.features.skills.CompanionSkillBackend.ReceiptRef ref)throws Exception {
        requirePrivateOwner(required(ref.taskId));JSONObject row=skillOperation(ref.taskId,ref.operationId),args=row.getJSONObject("arguments"),metadata=skillMetadata(args);checkSkillRef(ref,metadata);
        metadata.put("cancelPending",true);if(metadata.getString("dispatchState").equals("not_dispatched"))metadata.put("approvalState","cancelled");writeSkillArguments(ref.taskId,ref.operationId,args);
    }
    /** Real executor receipts update the existing original operation, never task business success. */
    public synchronized void recordSkillExecution(dev.pocketengine.features.skills.CompanionSkillBackend.ReceiptRef ref,dev.pocketengine.features.skills.SkillExecution.Receipt receipt)throws Exception {
        requirePrivateOwner(required(ref.taskId));JSONObject row=skillOperation(ref.taskId,ref.operationId),args=row.getJSONObject("arguments"),metadata=skillMetadata(args);checkSkillRef(ref,metadata);
        if(row.getString("state").equals("resolved"))return;
        JSONObject actual=dev.pocketengine.features.skills.SkillExecutionRecords.receipt(ref,receipt);
        JSONObject previous=row.optJSONObject("receipt");
        if(previous!=null&&previous.optString("status").matches("completed|failed|cancelled|timed_out|rejected")&&!actual.optString("status").matches("completed|failed|cancelled|timed_out|rejected"))return;
        if(previous!=null&&previous.optString("status").matches("completed|failed|cancelled|timed_out|rejected")&&!previous.optString("status").equals(actual.optString("status")))return;
        boolean terminal=actual.optString("status").matches("completed|failed|cancelled|timed_out|rejected");
        metadata.put("dispatchState",terminal?"settled":metadata.getString("dispatchState")).put("lastCheckedAt",System.currentTimeMillis());
        if(terminal)metadata.put("cancelPending",false);
        actual.put("cancelRequested",metadata.optBoolean("cancelPending"));
        if(previous!=null&&previous.optJSONArray("artifacts")!=null)actual.put("artifacts",previous.getJSONArray("artifacts"));
        ContentValues update=new ContentValues();update.put("arguments",args.toString());update.put("receipt",sanitize(actual).toString());update.put("state",terminal?"receipt":"unknown");
        if(db.update("operations",update,"task_id=? AND operation_id=?",new String[]{ref.taskId,ref.operationId})!=1)throw new SecurityException("原脚本操作不存在");
        JSONObject task=required(ref.taskId);if(terminal&&task.optString("status").equals("reconciling")&&!hasUnresolved(ref.taskId)){
            boolean stopped=task.optBoolean("cancelRequested");task.put("status",stopped?"cancelled":"paused").put("summary",stopped?"原脚本操作已取得终态回执，原任务保持停止；已有结果保留":"原脚本操作已取得终态回执，请核对结果后明确继续原任务");write(task);
        }
    }
    public synchronized JSONArray saveSkillArtifacts(dev.pocketengine.features.skills.CompanionSkillBackend.ReceiptRef ref,dev.pocketengine.features.skills.CompanionSkillBackend.ArtifactInventory inventory)throws Exception {
        requirePrivateOwner(required(ref.taskId));JSONObject row=skillOperation(ref.taskId,ref.operationId);checkSkillRef(ref,skillMetadata(row.getJSONObject("arguments")));
        JSONObject receipt=row.optJSONObject("receipt");if(receipt==null||!receipt.optString("status").matches("completed|failed|timed_out"))throw new SecurityException("没有原脚本的终态成果回执");
        JSONArray refs=new JSONArray();int total=0;
        for(var artifact:inventory.files){byte[] bytes=artifact.bytes();total+=bytes.length;if(total>ref.maxOutputBytes)throw new SecurityException("成果超过原输出预算");
            String identity=capabilityFingerprint(new JSONObject().put("operationId",ref.operationId).put("path",artifact.path).put("sha256",artifact.sha256));
            AtomicFile target=new AtomicFile(new File(artifacts,"skill-"+identity+".bin"));FileOutputStream stream=null;
            try{stream=target.startWrite();stream.write(bytes);target.finishWrite(stream);}catch(Exception failure){if(stream!=null)target.failWrite(stream);throw failure;}
            refs.put(new JSONObject().put("artifactId",identity).put("path",artifact.path).put("sha256",artifact.sha256).put("bytes",bytes.length).put("untrusted",true).put("imported",false));
        }
        receipt.put("artifacts",refs).put("missingArtifactPaths",new JSONArray(inventory.missingPaths)).put("artifactsTruncated",inventory.truncated);
        ContentValues update=new ContentValues();update.put("receipt",receipt.toString());db.update("operations",update,"task_id=? AND operation_id=?",new String[]{ref.taskId,ref.operationId});return refs;
    }
    public synchronized byte[] readSkillArtifact(String taskId,String operationId,String artifactId,String expectedSha256)throws Exception {
        requirePrivateOwner(required(taskId));JSONObject row=skillOperation(taskId,operationId);skillMetadata(row.getJSONObject("arguments"));JSONArray refs=row.getJSONObject("receipt").getJSONArray("artifacts");
        for(int i=0;i<refs.length();i++){JSONObject ref=refs.getJSONObject(i);if(!artifactId.equals(ref.getString("artifactId")))continue;
            if(!artifactId.matches("[a-f0-9]{64}")||!expectedSha256.equals(ref.getString("sha256")))throw new SecurityException("成果版本已变化");
            File file=new File(artifacts,"skill-"+artifactId+".bin");if(!file.isFile()||file.length()!=ref.getLong("bytes")||file.length()>65536)throw new SecurityException("原成果文件缺失或过大");
            byte[] bytes=Files.readAllBytes(file.toPath());StringBuilder hash=new StringBuilder();for(byte value:java.security.MessageDigest.getInstance("SHA-256").digest(bytes))hash.append(String.format(java.util.Locale.ROOT,"%02x",value&255));
            if(!expectedSha256.equals(hash.toString()))throw new SecurityException("原成果文件校验失败");return bytes;
        }throw new SecurityException("该成果不属于原任务操作");
    }
    private JSONObject skillOperation(String id,String operation)throws Exception {
        try(Cursor rows=db.rawQuery("SELECT run_id,tool,arguments,state,receipt FROM operations WHERE task_id=? AND operation_id=?",new String[]{id,operation})){
            if(!rows.moveToFirst()||!"skills_execute".equals(rows.getString(1)))throw new SecurityException("缺少原Runner的脚本动作意图");
            JSONObject row=new JSONObject().put("runId",rows.getString(0)).put("arguments",new JSONObject(rows.getString(2))).put("state",rows.getString(3));if(!rows.isNull(4))row.put("receipt",new JSONObject(rows.getString(4)));return row;
        }
    }
    private void writeSkillArguments(String task,String operation,JSONObject args)throws Exception {ContentValues update=new ContentValues();update.put("arguments",args.toString());if(db.update("operations",update,"task_id=? AND operation_id=?",new String[]{task,operation})!=1)throw new SecurityException("原脚本操作不存在");}
    private static JSONObject skillMetadata(JSONObject args)throws Exception {JSONObject value=args.getJSONObject("nativeSkill");if(!value.optString("format").equals("pocketworld.native-skill-operation.v1"))throw new SecurityException("缺少原生脚本收据身份");return value;}
    private static void checkSkillRef(dev.pocketengine.features.skills.CompanionSkillBackend.ReceiptRef ref,JSONObject metadata)throws Exception {if(!canonical(dev.pocketengine.features.skills.SkillExecutionRecords.reference(ref)).equals(canonical(metadata.getJSONObject("reference"))))throw new SecurityException("原脚本收据身份已变化");}
    private void checkSkillRun(dev.pocketengine.features.skills.SkillExecution.Plan plan,dev.pocketengine.features.skills.SkillExecution.Grant grant,RunControl control)throws Exception {
        if(control==null||!control.matchesHostRun(grant.taskId,grant.runId,grant.epoch)||control.isPaused())throw new SecurityException("没有原脚本的活动控制器");control.check();
        checkCapabilityCurrent(grant.taskId,grant.runId,grant.epoch,"skills_execute");
        var ref=dev.pocketengine.features.skills.CompanionSkillBackend.ReceiptRef.from(plan,grant);JSONObject row=skillOperation(ref.taskId,ref.operationId);
        if(!ref.runId.equals(row.getString("runId"))||!row.getString("state").matches("prepared|unknown"))throw new SecurityException("原脚本操作已结束或被核对");
        try(Cursor other=db.rawQuery("SELECT 1 FROM operations WHERE task_id=? AND operation_id<>? AND state IN ('prepared','unknown','remote_job_prepared','remote_job_sending','remote_job_unknown','remote_approval_sending','remote_approval_unknown','remote_question_prepared','remote_question_sending','remote_question_unknown') LIMIT 1",new String[]{ref.taskId,ref.operationId})){if(other.moveToFirst())throw new SecurityException("原任务有其他未决动作，不能派发脚本");}
    }
    private static void checkSkillGrant(dev.pocketengine.features.skills.SkillExecution.Plan plan,dev.pocketengine.features.skills.SkillExecution.Grant grant,JSONObject metadata)throws Exception {
        checkSkillRef(dev.pocketengine.features.skills.CompanionSkillBackend.ReceiptRef.from(plan,grant),metadata);
        if(!grant.approvalId.equals(metadata.getString("approvalId"))||!capabilityFingerprint(new JSONObject().put("backendGrantId",grant.backendGrantId)).equals(metadata.getString("grantHash"))||grant.expiresAt!=metadata.getLong("expiresAt")||System.currentTimeMillis()>=grant.expiresAt||grant.expiresAt>plan.expiresAt)throw new SecurityException("原脚本具体授权已变化或过期");
    }
    /** A late synchronous tool delivery cannot replace the native route or downgrade a real callback. */
    private JSONObject projectJournalReceipt(String task,String run,String operation,JSONObject delivered)throws Exception {
        try(Cursor row=db.rawQuery("SELECT tool,receipt,state,arguments FROM operations WHERE task_id=? AND run_id=? AND operation_id=?",new String[]{task,run,operation})){
            if(!row.moveToFirst())return delivered;String tool=row.getString(0);JSONObject nativeReceipt=row.isNull(1)?null:new JSONObject(row.getString(1));
            if("project_process_input".equals(tool)){
                if(nativeReceipt!=null&&nativeReceipt.optJSONObject("ptyInput")!=null)return nativeReceipt;
                // This tool has exactly one native dispatch entry: claimProjectProcessInput
                // consumes prepared -> unknown and persists its route BEFORE entering any queue.
                // Generic tool JSON cannot attest byte delivery, including on an unclaimed intent.
                JSONObject args=new JSONObject(row.getString(3));boolean unclaimed="prepared".equals(row.getString(2))&&nativeReceipt==null;
                return new JSONObject().put("code",unclaimed?"project_input_not_dispatched":"project_input_unknown").put("status",unclaimed?"not_dispatched":"unknown").put("accepted",unclaimed?false:JSONObject.NULL).put("ok",false).put("executionId",args.getString("executionId")).put("nativeInputSettled",unclaimed).put("processTerminal",false).put("replayAllowed",false).put("reason",unclaimed?"original_prepared_input_not_claimed":"native_input_route_missing");
            }
            if(nativeReceipt==null)return delivered;
            return "project_execute".equals(tool)&&nativeReceipt.optJSONObject("projectExecution")!=null?nativeReceipt:delivered;
        }
    }
    /** Native task-page preparation only. Selection is not a grant and creates no Run or tool call. */
    public synchronized JSONObject extensionSelectionTarget(String id)throws Exception {
        JSONObject task=required(id);requirePrivateOwner(task);JSONObject selection=task.optJSONObject("extensionSelection");
        if(selection==null)selection=new JSONObject().put("revision",0L).put("mode","inherit_enabled").put("programs",new JSONArray());
        boolean editable=!hasUnresolved(id)&&!task.optString("status").matches("running|stopping|reconciling")&&(!task.optString("status").equals("preparing")||task.optString("runId").isEmpty());
        return new JSONObject().put("taskId",id).put("goal",task.optString("goal")).put("runId",task.optString("runId")).put("epoch",task.optInt("coordinatorEpoch"))
                .put("status",task.optString("status")).put("selection",new JSONObject(selection.toString())).put("editable",editable).put("executionAuthorized",false);
    }
    /** Original task JSON, one SQL transaction/CAS. The caller retains the native reviewed source objects. */
    public synchronized JSONObject saveExtensionSelection(JSONObject expected,JSONArray programs)throws Exception {
        db.beginTransaction();try {
            String id=expected.getString("taskId");JSONObject target=extensionSelectionTarget(id),task=required(id),old=target.getJSONObject("selection");
            if(!target.getBoolean("editable")||!target.getString("runId").equals(expected.getString("runId"))||target.getInt("epoch")!=expected.getInt("epoch")
                    ||old.getLong("revision")!=expected.getJSONObject("selection").getLong("revision"))throw new SecurityException("原任务或选用版本已变化；暂停后重新审阅");
            if(programs.length()>16)throw new IllegalArgumentException("每任务最多选用16个JS扩展");
            JSONArray retained=new JSONArray();java.util.Set<String> ids=new java.util.HashSet<>();
            for(int i=0;i<programs.length();i++) {JSONObject row=programs.getJSONObject(i);String plugin=row.getString("id");
                if(!plugin.matches("[a-zA-Z][a-zA-Z0-9_.-]{0,79}")||!ids.add(plugin)||row.getString("installationId").codePoints().allMatch(Character::isWhitespace)||row.getString("version").codePoints().allMatch(Character::isWhitespace)||row.getString("version").length()>32||row.getLong("approvalRevision")<1||!row.getString("sourceSha256").matches("[a-f0-9]{64}"))throw new SecurityException("需要准确的当前源码选用引用");
                retained.put(new JSONObject().put("id",plugin).put("version",row.getString("version")).put("installationId",row.getString("installationId")).put("approvalRevision",row.getLong("approvalRevision")).put("sourceSha256",row.getString("sourceSha256")));
            }
            JSONObject selected=new JSONObject().put("revision",Math.addExact(old.getLong("revision"),1L)).put("mode","explicit").put("programs",retained);
            task.put("extensionSelection",selected);write(task);appendEvent(task,new JSONObject().put("type","native_extension_selection").put("selection",selected).put("executionAuthorized",false));
            db.setTransactionSuccessful();return extensionSelectionTarget(id);
        }finally{db.endTransaction();}
    }
    /** Identity/currentness only; original dispatch admission and unknown-effect barriers are unchanged. */
    public synchronized void checkExtensionSelection(String id,String runId,int epoch,long revision)throws Exception {
        JSONObject task=required(id);requirePrivateOwner(task);JSONObject selected=task.optJSONObject("extensionSelection");long current=selected==null?0L:selected.getLong("revision");
        if(!runId.equals(task.optString("runId"))||epoch!=task.optInt("coordinatorEpoch")||revision!=current)throw new SecurityException("本任务的JS选用或执行代次已变化；旧扩展不能继续");
    }
    /** Read-only admission for extensions in the current real model loop; grants and journal remain unchanged. */
    public synchronized void checkExtensionRun(String id,String runId,int epoch,RunControl control)throws Exception{
        if(control==null||!control.hasHostRun()||!control.matchesHostRun(id,runId,epoch))throw new SecurityException("扩展执行没有当前原生任务控制");
        JSONObject task=required(id);requirePrivateOwner(task);
        if(!task.optString("status").matches("preparing|running"))throw new SecurityException("原任务没有活动执行");
        try(Cursor run=db.rawQuery("SELECT status FROM runs WHERE run_id=? AND task_id=? AND epoch=?",new String[]{runId,id,String.valueOf(epoch)})){
            if(!run.moveToFirst()||!run.getString(0).equals("running"))throw new SecurityException("扩展执行尝试已结束");
        }
        fence(id,runId,epoch,control,true,true);
    }
    private void fence(String id,String runId,int epoch,RunControl control,boolean dispatch)throws Exception{
        fence(id,runId,epoch,control,dispatch,false);
    }
    private void fence(String id,String runId,int epoch,RunControl control,boolean dispatch,boolean ownedInference)throws Exception{
        fence(id,runId,epoch,control,dispatch,ownedInference,"");
    }
    private void fence(String id,String runId,int epoch,RunControl control,boolean dispatch,boolean ownedInference,String preparedStop)throws Exception{
        if(control!=null)control.check();JSONObject t=required(id);if(!runId.equals(t.optString("runId"))||epoch!=t.optInt("coordinatorEpoch"))throw new SecurityException("旧执行代次已失效");
        if(dispatch){if(control==null||control.isPaused()||t.optBoolean("cancelRequested"))throw new IllegalStateException("任务已暂停或取消");if(hasUnresolved(id)&&(!ownedInference||!onlyOwnedProjectPending(id,runId,epoch,preparedStop)))throw new IllegalStateException("有动作结果待核对，禁止继续派发");JSONObject grant=grant(runId);executionScopeCurrent(t,runId,grant);if(grant.optLong("expiresAt")<=System.currentTimeMillis()||!"owner".equals(grant.optString("principalId"))||!"private".equals(grant.optString("spaceId"))||!t.optString("executionMode").equals(grant.optString("mode")))throw new SecurityException("执行授权已失效");
            if(!t.optString("executionMode").equals("capabilities"))try(Cursor lease=db.rawQuery("SELECT run_id,epoch,expires_at FROM leases WHERE resource_id=?",new String[]{"phone.local"})){if(!lease.moveToFirst()||!runId.equals(lease.getString(0))||epoch!=lease.getInt(1)||lease.getLong(2)<=System.currentTimeMillis())throw new SecurityException("手机资源租约已失效");}
        }
    }
    public synchronized JSONObject resolveOperation(String id,String operationId,String outcome)throws Exception{
        if(required(id).has("sourceLearningJob"))throw new SecurityException("模型批次未知不能确认未发生或退款；请回原逐批学习入口核对/明确跳过");
        if(!outcome.equals("occurred")&&!outcome.equals("not_occurred"))throw new IllegalArgumentException("请根据实际现场确认已发生或未发生");
        ContentValues value=new ContentValues();value.put("state","resolved");value.put("receipt",new JSONObject().put("source","owner_reconciliation").put("outcome",outcome).put("at",System.currentTimeMillis()).toString());
        if(db.update("operations",value,"operation_id=? AND task_id=? AND state IN ('prepared','unknown')",new String[]{operationId,id})!=1)throw new IllegalArgumentException("没有需要核对的该动作");
        JSONObject t=required(id);if(!hasUnresolved(id))t.put("status","paused").put("summary","动作结果已核对，可以继续原任务");write(t);return copy(t,false);
    }
    public synchronized JSONObject reconcileExternalTask(String id,String outcome,String note)throws Exception{
        JSONObject task=required(id);if(task.optString("kind").equals("phone")||task.optString("kind").equals("assistant")||!task.optString("status").equals("reconciling")||hasUnresolved(id))throw new IllegalStateException("请使用该任务的具体操作核对入口");
        if(!outcome.equals("occurred")&&!outcome.equals("not_occurred"))throw new IllegalArgumentException("请选择已发生或未发生");
        if(note.length()>4000)throw new IllegalArgumentException("核对说明过长");
        JSONObject evidence=task.optJSONObject("evidence");if(evidence==null)evidence=new JSONObject();evidence.put("ownerReconciliation",new JSONObject().put("outcome",outcome).put("note",note).put("at",System.currentTimeMillis())).put("automaticallyVerified",false);
        task.put("evidence",evidence).put("status",outcome.equals("occurred")?"needs_confirmation":"awaiting_user").put("summary",outcome.equals("occurred")?"主人已核对外部操作发生，请再确认目标结果":"主人已核对外部操作未发生；保留原记录，不会自动重试");
        appendEvent(task,new JSONObject().put("type","owner_external_reconciliation").put("outcome",outcome).put("note",note));return copy(task,false);
    }
    /** Native chooser only. Stored in the original task, never rebound to a newer run. */
    public synchronized JSONObject beginBrowserUpload(JSONObject identity,JSONArray attachments)throws Exception {
        JSONObject task=required(identity.getString("taskId"));
        if(!"owner".equals(task.optString("actorId"))||!"private".equals(task.optString("spaceId"))||!identity.getString("runId").equals(task.optString("runId"))||identity.getInt("epoch")!=task.optInt("coordinatorEpoch")||task.optBoolean("cancelRequested"))throw new SecurityException("上传不是当前主人原任务");
        JSONObject attempt=dev.pocketengine.browser.BrowserUploadAttempt.create(identity,attachments);JSONArray all=task.optJSONArray("browserUploadAttempts");if(all==null)all=new JSONArray();
        if(all.length()>=100)throw new IllegalStateException("原任务上传尝试已达有界上限");for(int i=0;i<all.length();i++)if(attempt.getString("attemptId").equals(all.getJSONObject(i).getString("attemptId")))throw new SecurityException("此上传选择已登记，禁止重传");
        all.put(attempt);task.put("browserUploadAttempts",all);appendEvent(task,new JSONObject().put("type","browser_upload_attempt").put("attempt",attempt));return new JSONObject(attempt.toString());
    }
    public synchronized JSONObject browserUploadAttempt(String taskId,String attemptId,String fingerprint)throws Exception {
        JSONObject task=required(taskId);if(!"owner".equals(task.optString("actorId"))||!"private".equals(task.optString("spaceId")))throw new SecurityException("上传仅供原主人核对");
        JSONArray all=task.optJSONArray("browserUploadAttempts");for(int i=0;all!=null&&i<all.length();i++){JSONObject row=all.getJSONObject(i);if(attemptId.equals(row.getString("attemptId"))&&fingerprint.equals(row.getString("fingerprint")))return new JSONObject(row.toString());}throw new SecurityException("上传尝试或附件版本已变化");
    }
    public synchronized JSONObject updateBrowserUpload(String taskId,String attemptId,String fingerprint,String state,JSONObject evidence)throws Exception {
        JSONObject original=browserUploadAttempt(taskId,attemptId,fingerprint),task=required(taskId);if(state.equals("delivery_entered")){JSONObject pin=original.getJSONObject("pin");if(!pin.getString("runId").equals(task.optString("runId"))||pin.getInt("epoch")!=task.optInt("coordinatorEpoch")||task.optBoolean("cancelRequested"))throw new SecurityException("原上传执行已变化，未交付文件");}JSONObject next=dev.pocketengine.browser.BrowserUploadAttempt.transition(original,state,evidence);JSONArray all=task.getJSONArray("browserUploadAttempts");
        for(int i=0;i<all.length();i++)if(attemptId.equals(all.getJSONObject(i).getString("attemptId"))){all.put(i,next);break;}
        appendEvent(task,new JSONObject().put("type","browser_upload_attempt").put("attempt",next));return new JSONObject(next.toString());
    }
    public synchronized boolean hasUnresolved(String id){return hasUnresolvedExcept(id,null);}
    private boolean hasUnresolvedExcept(String id,String ownOperation){String except=ownOperation==null?"":" AND operation_id!=?";try(Cursor c=db.rawQuery("SELECT 1 FROM operations WHERE task_id=?"+except+" AND state IN ('prepared','unknown','project_running','project_stopping','remote_job_prepared','remote_job_sending','remote_job_unknown','remote_approval_sending','remote_approval_unknown','remote_question_prepared','remote_question_sending','remote_question_unknown') LIMIT 1",ownOperation==null?new String[]{id}:new String[]{id,ownOperation})){return c.moveToFirst();}}
    public synchronized void event(String id,JSONObject event)throws Exception{JSONObject t=read(id);if(t==null)return;applyEvent(t,event,t.optJSONObject("origin")==null||!t.getJSONObject("origin").optString("kind").matches("channel|group"));}
    /** Shared capability runner events capture their admitted run, never the currently newest one. */
    public synchronized void event(String id,String runId,int epoch,JSONObject event)throws Exception{
        JSONObject task=read(id);if(task==null||!runId.equals(task.optString("runId"))||epoch!=task.optInt("coordinatorEpoch"))return;
        applyEvent(task,event,true);
    }
    private void applyEvent(JSONObject task,JSONObject event,boolean currentRun)throws Exception{
        if("inference_receipt".equals(event.optString("type")))throw new SecurityException("通用事件不能伪装原生模型计量");
        String status=event.optString("status"),type=event.optString("type");
        boolean channel=task.optJSONObject("origin")!=null&&task.getJSONObject("origin").optString("kind").matches("channel|group");
        if(currentRun&&(!channel||!task.optBoolean("cancelRequested")&&!task.optString("status").equals("completed"))&&!status.isEmpty()&&(type.equals("run")||type.equals("result")))task.put("status",status);
        appendEvent(task,event);
    }
    private void appendEvent(JSONObject task,JSONObject event)throws Exception{
        db.beginTransaction();try{
            JSONObject clean=sanitize(event);clean.remove("memorySourceSnapshot");clean.remove("memorySourceSnapshotForgotten");
            if("user_verification".equals(clean.optString("type"))){
                clean.remove("runId");clean.remove("coordinatorEpoch");
                if(!task.optString("runId").isEmpty()&&task.optInt("coordinatorEpoch")>0)clean.put("runId",task.getString("runId")).put("coordinatorEpoch",task.getInt("coordinatorEpoch"));
                TaskAuditStore.retireSnapshots(db,task.getString("id"),Long.MAX_VALUE,false);
                if(clean.optBoolean("completed")){String goal=task.optString("goal"),summary=task.optString("summary"),feedback=clean.optString("feedback");clean.put("memorySourceSnapshot",new JSONObject().put("version",1).put("goal",snapshotText(goal,8000)).put("summary",snapshotText(summary,8000)).put("ownerFeedback",snapshotText(feedback,4000)).put("truncated",goal.length()>8000||summary.length()>8000||feedback.length()>4000).put("evidenceRole","owner_confirmed_result_summary_not_step_verification").put("executionIndependentlyVerified",false));}
            }
            clean.put("at",System.currentTimeMillis());TaskAuditStore.append(db,task.getString("id"),clean);write(task);db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
    public synchronized void status(String id,String status,String summary)throws Exception{JSONObject t=read(id);if(t==null)return;if(status.matches("paused|pausing|stopping|cancelled"))t.remove("automationConditionWaitId");t.put("status",status).put("summary",summary);write(t);}
    public synchronized void finish(String id,String status,String summary,JSONObject evidence)throws Exception{
        JSONObject t=read(id);if(t==null)return;JSONObject clean=new JSONObject(evidence.toString());String image=clean.optString("imageBase64");
        if(!image.isEmpty()){if(image.length()>6_000_000)throw new IllegalArgumentException("结果截图过大");AtomicFile target=new AtomicFile(new File(artifacts,id+".jpg"));FileOutputStream out=null;try{out=target.startWrite();out.write(Base64.decode(image,Base64.DEFAULT));target.finishWrite(out);}catch(Exception e){if(out!=null)target.failWrite(out);throw e;}clean.remove("imageBase64");clean.put("imageFile",id+".jpg");}
        t.put("status",status).put("summary",summary).put("evidence",sanitize(clean));write(t);
    }
    /** Native owner continuation only; the actual TeamsModule still performs its atomic snapshot CAS. */
    public synchronized String requireReviewedTeamControl(JSONObject input)throws Exception{return reviewedTeamTask(input,true);}
    private String reviewedTeamTask(JSONObject input,boolean beforeDispatch)throws Exception{
        String run=input.getString("runId"),id=externalTask("teams",run);if(id==null)id=operationTask("teams",run);
        if(id==null)throw new SecurityException("缺少原团队任务记录，请先刷新原任务");
        JSONObject task=required(id);requirePrivateOwner(task);
        if(!task.optString("feature").equals("teams")||!run.equals(task.optString("runId"))||!task.optString("externalId",run).equals(run))throw new SecurityException("团队控制与原任务不匹配");
        Object revision=input.opt("expectedRevision"),generation=input.opt("expectedGeneration");
        if(!(revision instanceof Integer||revision instanceof Long)||((Number)revision).longValue()<1||!(generation instanceof Integer)||(Integer)generation<0)throw new SecurityException("请读取当前团队版本后再继续");
        if(!input.optString("memberId").isEmpty()&&(!(input.opt("expectedMemberGeneration") instanceof Integer)||input.getInt("expectedMemberGeneration")<0))throw new SecurityException("缺少当前成员版本");
        JSONObject evidence=task.optJSONObject("evidence");
        if(task.optBoolean("cancelRequested")||task.optString("status").matches("completed|cancelled|stopping")||evidence!=null&&evidence.has("ownerReconciliation")||hasUnresolved(id))throw new SecurityException("原团队任务已停止、已确认或动作待核对，不能继续派发");
        if(beforeDispatch&&evidence!=null&&(evidence.optInt("generation")>(Integer)generation||evidence.optLong("revision")>((Number)revision).longValue()))throw new SecurityException("团队状态已改变，请刷新原任务");
        return id;
    }
    /** Called only after a native reviewed resume/continue returns the same module-owned run. */
    public synchronized void updateReviewedTeamControl(String id,JSONObject input,JSONObject receipt)throws Exception{
        if(!id.equals(reviewedTeamTask(input,false))||!input.getString("runId").equals(receipt.optString("id"))||!receipt.optString("operationId",receipt.optString("id")).equals(input.getString("runId")))throw new SecurityException("团队继续回执与原任务不匹配");
        if(!(receipt.opt("revision") instanceof Number)||receipt.getLong("revision")<=input.getLong("expectedRevision")||!(receipt.opt("generation") instanceof Integer)||receipt.getInt("generation")<input.getInt("expectedGeneration"))throw new SecurityException("团队继续没有返回当前版本的回执");
        updateExternal(id,receipt,true);
    }
    public synchronized void updateExternal(String id,JSONObject receipt)throws Exception{db.beginTransaction();try{updateExternal(id,receipt,false);db.setTransactionSuccessful();}finally{db.endTransaction();}}
    private void updateExternal(String id,JSONObject receipt,boolean reviewedAdmission)throws Exception{
        JSONObject t=required(id);String raw=receipt.optString("status",receipt.optBoolean("ok",true)?"response_received":"failed");
        if(t.optString("feature").equals("remote")){
            String operation=receipt.optString("operationId"),current=t.optString("operationId");
            if(operation.isEmpty()){
                receipt=bindRemoteNotice(id,receipt);if(receipt==null)return;
                operation=receipt.getString("operationId");
            }
            if(!recordRemoteJobReceipt(t,receipt))return;
            settleTeamRemoteOriginalJob(t,receipt);settleRemoteQuestionAnswers(id,receipt);
            if(t.has("remoteInputScope")&&receipt.has("inputState")){settleRemoteInputFacts(t,receipt);if(originalInputStartAccepted(t,receipt))reviewedAdmission=true;}
            if(!operation.equals(current)||!operation.equals(t.optString("runId")))return;
            settleRemoteApprovals(id,receipt);
        }
        if(t.optString("feature").matches("browser|ssh")&&receipt.has("runId")&&!t.optString("runId").equals(receipt.optString("runId")))return;
        String current=t.optString("status");
        // Owner verification is authoritative. A delayed admission receipt must never reopen a settled execution.
        if(current.equals("completed"))return;
        JSONObject savedEvidence=t.optJSONObject("evidence");
        if(savedEvidence!=null&&savedEvidence.has("ownerReconciliation"))return;
        if(current.equals("running")&&raw.matches("queued|accepted|submitting"))return;
        boolean admission=raw.equals("queued")||raw.equals("running")||raw.equals("stopping")||raw.equals("accepted")||raw.equals("submitting")||raw.equals("leased");
        if(admission&&((!reviewedAdmission&&current.matches("needs_confirmation|failed|interrupted|awaiting_user"))||current.equals("cancelled")
                ||current.equals("stopping")&&!raw.equals("stopping")
                ||current.equals("reconciling")&&t.optString("feature").matches("plugins|ssh")))return;
        boolean teamGenerations=t.optString("feature").equals("teams")&&savedEvidence!=null&&savedEvidence.has("generation")&&receipt.has("generation");
        int generationOrder=teamGenerations?Integer.compare(receipt.optInt("generation"),savedEvidence.optInt("generation")):0;
        if(generationOrder<0)return;
        boolean versionedTeam=teamGenerations&&savedEvidence.has("revision")&&receipt.has("revision");
        if(versionedTeam&&generationOrder==0&&receipt.optLong("revision")<savedEvidence.optLong("revision"))return;
        long sourceAt=receipt.optLong("updatedAt",receipt.optLong("finishedAt",0));
        if(!versionedTeam&&generationOrder<=0&&sourceAt>0&&sourceAt<t.optLong("externalUpdatedAt"))return;
        if(t.optString("feature").equals("remote"))settleRemoteDelegationAcceptance(t,receipt);
        if(!receipt.optString("id").isEmpty())t.put("externalId",receipt.getString("id"));
        if(receipt.has("operationId"))t.put("externalOperationId",receipt.getString("operationId"));
        String status;
        switch(raw){
            case "completed":case "partial":case "response_received":case "release":status="needs_confirmation";break;
            case "unknown":case "not_found":case "submitting":status="reconciling";break;
            case "taken_over":case "takeover":case "cancel":status="cancelled";break;
            case "expired":status="awaiting_user";break;
            case "awaiting_approval":case "awaiting_input":status="awaiting_user";break;
            case "awaiting_result":status="running";break;
            case "timed_out":status="failed";break;
            case "leased":case "accepted":status="running";break;
            case "not_active":return; // A missing in-process request says nothing about an already dispatched side effect.
            default:status=raw;
        }
        if(!status.matches("queued|running|paused|stopping|cancelled|failed|interrupted|awaiting_user|needs_confirmation|reconciling"))status="needs_confirmation";
        if(hasUnresolved(id))status="reconciling";
        JSONObject evidence=new JSONObject().put("executorStatus",raw).put("automaticallyVerified",false);for(String key:new String[]{"id","profileId","environmentId","operationId","expiresAt","exitCode","summary","message","pendingApprovals","pendingQuestions","questionReceipts","progress","promptResultIdentity","diffTruncated","threadId","turnId","protocol","outputTruncated","phase","round","generation","revision","mode","leaderId","controls","inputSteeringVerified","steeringUnknown","serviceBase","worker","model","capabilities","readOnly","readOnlyConfirmed","externalToolsDisabled","protocolVerified","controlProtocolVerified","sessionIdentityConfirmed","sessionId","promptRequestId","reportedModel","interruptAcknowledged","turnStartAcknowledged","resumeJobId","sourceServiceBase","approvalReceipts","remoteEffectsVerified","eventCount","resultSubtype","permissionDenialCount","resultSnapshotId","resultSnapshotVersion","snapshotPrelaunchVerified","snapshotBaseVerifiedAt","workspaceIdentity","assignmentWorkspace","externalJobLimits","inferenceUsage","modelCallHardCapSupported","tokenHardCapSupported","costHardCapSupported","wallTimeHardCapSupported","wallTimeEnforcement","detachedDescendantIsolationVerified","assignmentWriteSandboxVerified","osReadIsolationVerified","commandAllowlistEnforced","processStarted","dispatchFenceFinal","notStartedConfirmed","processCleanupProof","outputLimitExceeded","inputState"})if(receipt.has(key))evidence.put(key,receipt.get(key));
        if(t.has("remoteInputScope")&&t.has("remoteInputState")&&evidence.has("inputState"))evidence.put("inputState",new JSONObject(t.getJSONObject("remoteInputState").toString()));
        if("ssh".equals(t.optString("feature"))&&t.optString("command").matches("inspectFile|downloadFile|uploadFile"))for(String key:new String[]{"path","exists","bytes","version","expectedVersion","currentVersion","expectedRemoteVersion","maxTransferBytes","modifiedAtSeconds","streamedHash","atomicRemoteSnapshot","artifactSaved","attachment","sourceAttachment","sourceSha256","bytesReadFromLocal","expectedBytes","verifiedBytes","verifiedBusinessSuccess","targetReplacementSent","stagingPath","lockPath","stagingCleanupConfirmed","lockCleanupConfirmed","lockOwnershipConfirmed","cleanupReviewRequired","partialRemoteFilePossible","sourceChanged","cooperativeConditionalWrite","externalWriterRacePossible","workspaceIsSandbox","transferExpiresAt","localTransferDeadline","automaticRetry","code"})if(receipt.has(key))evidence.put(key,receipt.get(key));
        if(t.optString("feature").equals("teams")&&receipt.optJSONArray("members")!=null){JSONArray nativeMembers=new JSONArray(),members=receipt.getJSONArray("members");for(int m=0;m<members.length()&&m<8;m++){JSONObject member=members.getJSONObject(m);if(member.has("id")&&member.has("generation"))nativeMembers.put(new JSONObject().put("memberId",member.getString("id")).put("generation",member.getInt("generation")).put("paused",member.optBoolean("paused")).put("status",member.optString("status")));}evidence.put("nativeMemberGenerations",nativeMembers);}
        if(t.optString("feature").equals("remote")&&savedEvidence!=null&&raw.matches("unknown|not_found"))for(String key:new String[]{"id","operationId","profileId","serviceBase","environmentId","worker","threadId","sessionId","promptRequestId","turnId","protocol","model"})if(!evidence.has(key)&&savedEvidence.has(key))evidence.put(key,savedEvidence.get(key));
        String fallback=status.equals("needs_confirmation")?"执行返回，请核对目标结果":status.equals("reconciling")?"执行结果仍待核对；未自动重试":status.equals("cancelled")?"执行已停止，已有结果与现场保留":"正在执行";
        if(sourceAt>0)t.put("externalUpdatedAt",sourceAt);
        t.put("executionStatus",raw).put("status",status).put("summary",receipt.optString("message",receipt.optString("summary",fallback))).put("evidence",evidence);write(t);
    }
    /** Reconciles the parent's delegation effect, not the remote coding/business outcome. */
    private void settleRemoteDelegationAcceptance(JSONObject child,JSONObject actual)throws Exception {
        if(child.optString("parentOperationId").isEmpty()||actual.optString("id").isEmpty()||actual.optString("status").matches("unknown|not_found|submitting")||!child.optString("operationId").equals(actual.optString("operationId")))return;
        try(Cursor pending=db.rawQuery("SELECT 1 FROM operations WHERE operation_id=? AND tool='remote_start_task' AND state='unknown'",new String[]{child.getString("parentOperationId")})){if(!pending.moveToFirst())return;}
        JSONObject relation=requirePrivateRemoteChild(child.getString("id"));String parent=relation.getString("parentTaskId"),originalRun=relation.getString("parentRunId"),operation=relation.getString("parentOperationId");
        JSONObject receipt=new JSONObject();for(String key:new String[]{"id","operationId","profileId","serviceBase","environmentId","worker","status"})if(actual.has(key))receipt.put(key,actual.get(key));receipt.put("hostTaskId",child.getString("id")).put("taskId",child.getString("id")).put("parentTaskId",parent).put("parentOperationId",operation).put("ok",true).put("remoteEffectsVerified",false).put("reconciledAcceptance",true);
        ContentValues row=new ContentValues();row.put("state","receipt");row.put("receipt",sanitize(receipt).toString());
        if(db.update("operations",row,"task_id=? AND run_id=? AND operation_id=? AND tool='remote_start_task' AND state='unknown'",new String[]{parent,originalRun,operation})==0)return;
        JSONObject target=required(parent);if(originalRun.equals(target.optString("runId"))&&!target.optBoolean("cancelRequested")&&target.optString("status").equals("reconciling")&&!hasUnresolved(parent)){
            target.put("status","awaiting_user").put("summary","原电脑委派已按同一child取得接收回执；没有重发或验证业务成功，请回原目标继续");write(target);ContentValues ended=new ContentValues();ended.put("status","awaiting_user");db.update("runs",ended,"task_id=? AND run_id=?",new String[]{parent,originalRun});
        }
    }
    /** A transport loss is not a new job receipt. Resolve only one exact native journal owner. */
    private JSONObject bindRemoteNotice(String taskId,JSONObject notice)throws Exception{
        if(!notice.optString("status").matches("unknown|not_found"))return null;
        String job=notice.optString("id");if(job.isEmpty())job=notice.optString("jobId");
        if(job.isEmpty()||!notice.optString("jobId",job).equals(job)||notice.optString("profileId").isEmpty())return null;
        String operation=null,owner=null;JSONObject original=null;
        try(Cursor rows=db.rawQuery("SELECT operation_id,task_id,receipt FROM operations WHERE tool='remote_job' AND receipt IS NOT NULL",null)){
            while(rows.moveToNext()){
                JSONObject saved=new JSONObject(rows.getString(2));
                if(!job.equals(saved.optString("id"))||!notice.getString("profileId").equals(saved.optString("profileId"))||saved.optString("serviceBase").isEmpty()||!rows.getString(0).equals(saved.optString("operationId")))continue;
                if(notice.has("serviceBase")&&!notice.optString("serviceBase").equals(saved.getString("serviceBase")))continue;
                if(operation!=null&&!operation.equals(rows.getString(0)))return null; // No globally-unique-job assumption.
                operation=rows.getString(0);owner=rows.getString(1);original=saved;
            }
        }
        if(operation==null||!taskId.equals(owner))return null;
        return new JSONObject(notice.toString()).put("id",job).put("operationId",operation).put("profileId",original.getString("profileId")).put("serviceBase",original.getString("serviceBase"));
    }
    private boolean recordRemoteJobReceipt(JSONObject task,JSONObject receipt)throws Exception{
        String operation=receipt.getString("operationId"),id=task.getString("id");JSONObject row=null;
        try{row=remoteOperation(id,operation,"remote_job");}catch(SecurityException missing){if(!operation.equals(task.optString("operationId")))return false;}
        JSONObject refs=task.optJSONObject("externalRefs"),args=row==null?new JSONObject().put("taskId",id).put("runId",task.getString("runId")).put("coordinatorEpoch",task.getInt("coordinatorEpoch")):row.getJSONObject("arguments");JSONObject source=args.optJSONObject("source");
        JSONObject previous=row==null?null:row.optJSONObject("receipt");
        String expectedProfile=source==null?refs==null?"":refs.optString("profileId"):source.optString("profileId"),expectedOrigin=source==null?previous==null?"":previous.optString("serviceBase"):source.optString("serviceBase");
        if(!expectedProfile.isEmpty()&&!expectedProfile.equals(receipt.optString("profileId",expectedProfile)))throw new SecurityException("电脑回执不属于原任务连接");
        if(!expectedOrigin.isEmpty()&&!expectedOrigin.equals(receipt.optString("serviceBase",expectedOrigin)))throw new SecurityException("电脑回执改变了原地址");
        if(previous!=null&&!previous.optString("id").isEmpty()&&!receipt.optString("id").isEmpty()&&!previous.getString("id").equals(receipt.getString("id")))throw new SecurityException("电脑回执替换了原job");
        if(previous!=null&&previous.has("workspaceIdentity")&&receipt.has("workspaceIdentity")&&!previous.getString("workspaceIdentity").equals(receipt.getString("workspaceIdentity")))throw new SecurityException("原job回执改变了物理workspace身份");
        if(task.has("remoteInputScope")&&receipt.has("inputState")){
            JSONObject incomingInput=originalInputReceiptState(task,receipt),previousInput=task.optJSONObject("remoteInputState");
            if(previousInput!=null){long previousRevision=previousInput.getLong("revision"),incomingRevision=incomingInput.getLong("revision");
                // A stale input component does not suppress other genuine newer job metadata.
                // settleRemoteInputFacts retains the newest original input state independently.
                if(incomingRevision==previousRevision&&!capabilityFingerprint(previousInput).equals(capabilityFingerprint(incomingInput)))throw new SecurityException("原job相同输入版本内容冲突，未覆盖当前状态");
            }
        }
        boolean unknown=receipt.optString("status").matches("unknown|not_found|submitting");
        if(!unknown&&source==null&&refs!=null)for(String key:new String[]{"environmentId","worker"})if(refs.has(key)&&!refs.get(key).equals(receipt.opt(key)))throw new SecurityException("首创回执与原电脑环境/执行器不一致");
        if(source!=null&&!unknown){for(String key:new String[]{"resultSnapshotId","resultSnapshotVersion"})if(args.has(key)&&!args.getString(key).equals(receipt.optString(key)))throw new SecurityException("续接回执改变了所审查的成果快照版本");for(String key:new String[]{"threadId","environmentId","worker"})if(source.has(key)&&!source.get(key).equals(receipt.opt(key)))throw new SecurityException("续接回执与原会话环境不匹配");if(!source.getString("jobId").equals(receipt.optString("resumeJobId")))throw new SecurityException("续接回执未绑定原job");}
        if(row==null){insertRemoteOperation(operation,id,task.getString("runId"),"remote_job",args,"remote_job_sending");saveRemoteRun(task,"running",new JSONObject());}
        JSONObject clean=new JSONObject();for(String key:new String[]{"id","profileId","serviceBase","operationId","environmentId","worker","status","message","createdAt","updatedAt","exitCode","outputTruncated","archived","lastKnownStatus","protocol","protocolVerified","controlProtocolVerified","sessionIdentityConfirmed","sessionId","promptRequestId","threadId","turnId","pendingApprovals","approvalReceipts","pendingQuestions","questionReceipts","progress","promptResultIdentity","diffTruncated","model","reportedModel","readOnly","readOnlyConfirmed","externalToolsDisabled","controls","capabilities","inputSteeringVerified","steeringUnknown","interruptAcknowledged","turnStartAcknowledged","resumeJobId","sourceServiceBase","remoteEffectsVerified","eventCount","resultSubtype","permissionDenialCount","resultSnapshotId","resultSnapshotVersion","snapshotPrelaunchVerified","snapshotBaseVerifiedAt","workspaceIdentity","assignmentWorkspace","externalJobLimits","inferenceUsage","modelCallHardCapSupported","tokenHardCapSupported","costHardCapSupported","wallTimeHardCapSupported","wallTimeEnforcement","detachedDescendantIsolationVerified","assignmentWriteSandboxVerified","osReadIsolationVerified","commandAllowlistEnforced","processStarted","dispatchFenceFinal","notStartedConfirmed","processCleanupProof","outputLimitExceeded","inputState"})if(receipt.has(key))clean.put(key,receipt.get(key));
        String raw=receipt.optString("status"),state=unknown?"remote_job_unknown":raw.matches("completed|failed|cancelled|rejected")?"remote_job_receipt":"remote_job_active";
        if(previous!=null&&row.getString("state").equals("remote_job_receipt")&&!state.equals("remote_job_receipt"))return false;
        ContentValues values=new ContentValues();values.put("state",state);values.put("receipt",sanitize(clean).toString());db.update("operations",values,"operation_id=? AND task_id=? AND tool='remote_job'",new String[]{operation,id});
        ContentValues run=new ContentValues();run.put("status",unknown?"reconciling":raw.matches("completed|failed|cancelled")?raw:"running");run.put("updated_at",System.currentTimeMillis());db.update("runs",run,"run_id=? AND task_id=?",new String[]{operation,id});return true;
    }

    /** Remote fallback is scoped by the actual profile/origin, never a globally unique job-id assumption. */
    public synchronized String externalTask(String feature,String externalId,String profile,String service)throws Exception{
        if(!feature.equals("remote"))return externalTask(feature,externalId);if(externalId.isEmpty()||profile.isEmpty())return null;
        try(Cursor rows=db.rawQuery("SELECT task_id,receipt FROM operations WHERE tool='remote_job' AND receipt IS NOT NULL ORDER BY created_at DESC",null)){
            while(rows.moveToNext()){JSONObject receipt=new JSONObject(rows.getString(1));if(externalId.equals(receipt.optString("id"))&&profile.equals(receipt.optString("profileId"))&&(service.isEmpty()||service.equals(receipt.optString("serviceBase"))))return rows.getString(0);}
        }
        try(Cursor rows=db.rawQuery("SELECT document FROM tasks ORDER BY updated_at DESC",null)){
            while(rows.moveToNext()){JSONObject task=new JSONObject(rows.getString(0)),refs=task.optJSONObject("externalRefs"),evidence=task.optJSONObject("evidence");if(feature.equals(task.optString("feature"))&&externalId.equals(task.optString("externalId"))&&refs!=null&&profile.equals(refs.optString("profileId"))&&(service.isEmpty()||evidence!=null&&service.equals(evidence.optString("serviceBase"))))return task.getString("id");}
        }
        return null;
    }
    public synchronized String externalTask(String feature,String externalId)throws Exception{if(externalId==null||externalId.isEmpty())return null;if(feature.equals("remote")){try(Cursor rows=db.rawQuery("SELECT task_id,receipt FROM operations WHERE tool='remote_job' AND receipt IS NOT NULL ORDER BY created_at DESC",null)){while(rows.moveToNext())if(externalId.equals(new JSONObject(rows.getString(1)).optString("id")))return rows.getString(0);}}try(Cursor c=db.rawQuery("SELECT document FROM tasks ORDER BY updated_at DESC",null)){while(c.moveToNext()){JSONObject t=new JSONObject(c.getString(0));if(feature.equals(t.optString("feature"))&&externalId.equals(t.optString("externalId")))return t.getString("id");}}return null;}
    public synchronized String operationTask(String feature,String operationId)throws Exception{if(operationId==null||operationId.isEmpty())return null;if(feature.equals("media")){try(Cursor operation=db.rawQuery("SELECT task_id FROM operations WHERE operation_id=? AND tool IN ('media_workflow','media_generate_image','media_synthesize_speech')",new String[]{operationId})){return operation.moveToFirst()?operation.getString(0):null;}}if(feature.equals("remote")){try(Cursor operation=db.rawQuery("SELECT task_id FROM operations WHERE operation_id=? AND tool='remote_job'",new String[]{operationId})){if(operation.moveToFirst())return operation.getString(0);}}try(Cursor c=db.rawQuery("SELECT document FROM tasks ORDER BY updated_at DESC",null)){while(c.moveToNext()){JSONObject t=new JSONObject(c.getString(0));if(feature.equals(t.optString("feature"))&&operationId.equals(t.optString("operationId")))return t.getString("id");}}return null;}
    public synchronized JSONObject requireExternalRun(String id,String runId,String feature,boolean dispatch)throws Exception{
        JSONObject task=required(id);
        if(!feature.equals(task.optString("feature"))||!runId.equals(task.optString("runId"))||!"owner".equals(task.optString("actorId"))||!"private".equals(task.optString("spaceId")))throw new SecurityException("能力任务身份已失效");
        if(dispatch&&(!task.optString("status").equals("running")||task.optBoolean("cancelRequested")||hasUnresolved(id)))throw new SecurityException("任务已停止或结果待核对，不能继续输入");
        return copy(task,false);
    }
    /** Whole-journal native catalog fence; a new page/process cannot bypass a prior uncertain write. */
    public synchronized JSONObject unresolvedCatalogOperation()throws Exception{
        try(Cursor row=db.rawQuery("SELECT task_id,operation_id,tool,state FROM operations WHERE tool IN ('plugins_catalog_configure','plugins_catalog_submit','plugins_catalog_jsChange','plugins_pack_component') AND state IN ('prepared','unknown') ORDER BY created_at LIMIT 1",null)){
            return row.moveToFirst()?new JSONObject().put("taskId",row.getString(0)).put("operationId",row.getString(1)).put("tool",row.getString(2)).put("state",row.getString(3)):null;
        }
    }
    public synchronized String beginCapabilityOperation(String id,String runId,String feature,String command,JSONObject args)throws Exception{
        JSONObject task=required(id);if(!feature.equals(task.optString("feature"))||!runId.equals(task.optString("runId"))||!"running".equals(task.optString("status"))||task.optBoolean("cancelRequested"))throw new SecurityException("该能力没有当前任务的有效执行授权");
        if(hasUnresolved(id))throw new IllegalStateException("请先核对上次动作结果");
        String op=UUID.randomUUID().toString();ContentValues v=new ContentValues();v.put("operation_id",op);v.put("task_id",id);v.put("run_id",runId);v.put("tool",feature+"_"+command);v.put("arguments",sanitize(args).toString());v.put("state","prepared");v.put("created_at",System.currentTimeMillis());db.insertOrThrow("operations",null,v);return op;
    }
    public synchronized void finishCapabilityOperation(String id,String operationId,JSONObject receipt)throws Exception{
        JSONObject task=required(id);
        try(Cursor operation=db.rawQuery("SELECT run_id FROM operations WHERE task_id=? AND operation_id=?",new String[]{id,operationId})){
            if(!operation.moveToFirst())throw new IllegalArgumentException("执行收据与任务不匹配");
            if(!task.optString("runId").equals(operation.getString(0)))return;
        }
        String status=receipt.optString("status"),code=receipt.optString("code");boolean unknown=status.equals("unknown")||code.contains("unknown")||receipt.has("accepted")&&receipt.isNull("accepted");
        ContentValues value=new ContentValues();value.put("state",unknown?"unknown":"receipt");value.put("receipt",sanitize(receipt).toString());if(db.update("operations",value,"task_id=? AND operation_id=?",new String[]{id,operationId})!=1)throw new IllegalArgumentException("执行收据与任务不匹配");
        if(unknown)status(id,"reconciling","动作结果不明，请核对后继续");
    }
    /** Native owner attachment selection. Only immutable references enter the original task, never bytes/URIs. */
    public synchronized JSONObject linkOwnerAttachment(String taskId,JSONObject imported)throws Exception{
        JSONObject task=required(taskId);requirePrivateOwner(task);JSONObject reference=attachmentReference(imported);
        JSONArray refs=task.optJSONArray("attachmentSources");if(refs==null)refs=new JSONArray();
        for(int i=0;i<refs.length();i++)if(refs.getJSONObject(i).getString("id").equals(reference.getString("id"))){
            if(!refs.getJSONObject(i).getString("sha256").equals(reference.getString("sha256")))throw new SecurityException("原任务文件版本已变化");return new JSONObject(refs.getJSONObject(i).toString());}
        if(refs.length()>=64)throw new IllegalStateException("原任务已关联64个文件，请缩小当前目标的来源范围");
        refs.put(reference);task.put("attachmentSources",refs);appendEvent(task,new JSONObject().put("type","owner_attachment_selected").put("attachment",reference));
        return new JSONObject(reference.toString());
    }
    /** The exact owner-selected source set survives queued input and task/run continuation. */
    public synchronized JSONArray ownerAttachmentReferences(String taskId)throws Exception{
        JSONObject task=required(taskId);requirePrivateOwner(task);JSONArray result=new JSONArray();java.util.Set<String> seen=new java.util.HashSet<>();
        JSONArray selected=task.optJSONArray("attachmentSources");for(int i=0;selected!=null&&i<selected.length();i++){JSONObject ref=attachmentReference(selected.getJSONObject(i));if(seen.add(ref.getString("id")+":"+ref.getString("sha256")))result.put(ref);}
        JSONArray inputs=task.optJSONArray("ownerInputs");for(int i=0;inputs!=null&&i<inputs.length();i++){JSONArray refs=inputs.getJSONObject(i).optJSONArray("attachments");for(int j=0;refs!=null&&j<refs.length();j++){JSONObject ref=attachmentReference(refs.getJSONObject(j));if(seen.add(ref.getString("id")+":"+ref.getString("sha256")))result.put(ref);}}
        return result;
    }
    private static JSONObject attachmentReference(JSONObject imported)throws Exception{
        String id=imported.getString("id"),sha=imported.getString("sha256"),mime=imported.getString("mimeType");Object raw=imported.opt("size");
        if(!id.matches("[a-f0-9-]{36}")||!sha.matches("[a-f0-9]{64}")||!mime.matches("[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+")||!(raw instanceof Number)||imported.getLong("size")<0||imported.getLong("size")>20L*1024*1024)throw new SecurityException("来源必须是真实导入文件的不可变引用");
        return new JSONObject().put("id",id).put("sha256",sha).put("mimeType",mime).put("size",imported.getLong("size"));
    }
    /** Native projection metadata only, in this same TaskStore. References do not assert content was read. */
    public synchronized void noteOwnerDocumentReference(String id,String run,int epoch,JSONObject reference,RunControl control)throws Exception {
        checkCapability(id,run,epoch,"attachments_read_document",control);JSONObject task=required(id);requirePrivateOwner(task);JSONObject ref=attachmentReference(reference);
        JSONArray all=task.optJSONArray("documentSources");if(all==null)all=new JSONArray();JSONObject row=null;
        for(int i=0;i<all.length();i++)if(ref.getString("id").equals(all.getJSONObject(i).optString("id"))&&ref.getString("sha256").equals(all.getJSONObject(i).optString("sha256")))row=all.getJSONObject(i);
        if(row==null){if(all.length()>=128)throw new IllegalStateException("原任务文档版本记录已达上限");row=ref;all.put(row);}
        if(run.equals(row.optString("presentedRunId")))return;
        row.put("presentedRunId",run).put("presentedEpoch",epoch).put("readState","reference_only").put("presentedAt",System.currentTimeMillis());task.put("documentSources",all);
        appendEvent(task,new JSONObject().put("type","attachment_reference_presented").put("attachmentId",ref.getString("id")).put("sha256",ref.getString("sha256")).put("runId",run).put("epoch",epoch).put("readState","not_read_by_model").put("verifiedBusinessSuccess",false));
    }
    /** Actual bounded parser receipt, not a model claim, text cache, whole-document or goal-success certificate. */
    public synchronized void noteOwnerDocumentRead(String id,String run,int epoch,JSONObject nativeFact,RunControl control)throws Exception {
        checkCapability(id,run,epoch,"attachments_read_document",control);JSONObject task=required(id);JSONArray all=task.optJSONArray("documentSources");
        for(int i=0;all!=null&&i<all.length();i++){
            JSONObject row=all.getJSONObject(i);if(!row.optString("id").equals(nativeFact.getString("attachmentId"))||!row.optString("sha256").equals(nativeFact.getString("sha256")))continue;
            JSONArray ranges=row.optJSONArray("nativeReadRanges");if(ranges==null)ranges=new JSONArray();if(ranges.length()>=8)ranges.remove(0);
            ranges.put(new JSONObject(nativeFact.toString()));row.put("nativeReadRanges",ranges).put("rangeHistoryRetention","last_8_only");
            if(nativeFact.optInt("chunkCount")>0&&nativeFact.optString("status").matches("ok|partial|truncated"))row.put("lastNativeReadRunId",run).put("readState","partially_read_by_native_tool");
        }
        if(all!=null)task.put("documentSources",all);appendEvent(task,nativeFact);
    }
    public synchronized boolean hasUnreadOwnerDocumentReference(String id,String run)throws Exception {
        JSONObject task=required(id);requirePrivateOwner(task);JSONArray all=task.optJSONArray("documentSources");
        for(int i=0;all!=null&&i<all.length();i++){JSONObject ref=all.getJSONObject(i);if(run.equals(ref.optString("presentedRunId"))&&!run.equals(ref.optString("lastNativeReadRunId")))return true;}return false;
    }
    /** Original media source, including an old run, with native owner/private read scope. */
    public synchronized JSONObject ownerMediaOperationSource(String taskId,String operationId)throws Exception{
        requirePrivateOwner(required(taskId));return mediaOperationSource(taskId,operationId);
    }
    /** Group recovery still rechecks the complete original authenticated origin and read authority. */
    public synchronized JSONObject groupMediaOperationSource(dev.pocketengine.features.groups.GroupHostPort.Request request,String operationId,dev.pocketengine.features.groups.GroupHostPort.Authority authority)throws Exception{
        authority.checkRead();if(groupTask(request,authority)==null)throw new SecurityException("原群任务尚未登记");JSONObject result=mediaOperationSource(request.taskId,operationId);authority.checkRead();return result;
    }
    private JSONObject mediaOperationSource(String taskId,String operationId)throws Exception{
        try(Cursor c=db.rawQuery("SELECT o.run_id,r.epoch,o.tool,o.arguments,o.state,o.receipt FROM operations o JOIN runs r ON r.run_id=o.run_id AND r.task_id=o.task_id WHERE o.task_id=? AND o.operation_id=?",new String[]{taskId,operationId})){
            if(!c.moveToFirst()||!c.getString(2).matches("media_workflow|media_generate_image|media_synthesize_speech|group_media_image|group_media_audio|group_media_video"))throw new SecurityException("原媒体操作不属于此任务");
            JSONObject args=new JSONObject(c.getString(3)),result=new JSONObject().put("taskId",taskId).put("operationId",operationId).put("runId",c.getString(0)).put("epoch",c.getInt(1))
                    .put("tool",c.getString(2)).put("arguments",args).put("argumentsHash",capabilityFingerprint(args)).put("state",c.getString(4));
            if(!c.isNull(5))result.put("receipt",new JSONObject(c.getString(5)));return result;
        }
    }
    /** Actual native media query/fetch result only. It never starts or rewrites the current task attempt. */
    public synchronized JSONObject reconcileOwnerMediaReceipt(JSONObject source,JSONObject nativeReceipt)throws Exception{
        String taskId=source.getString("taskId");requirePrivateOwner(required(taskId));return reconcileMediaReceipt(source,nativeReceipt);
    }
    public synchronized JSONObject reconcileGroupMediaReceipt(dev.pocketengine.features.groups.GroupHostPort.Request request,dev.pocketengine.features.groups.GroupHostPort.Authority authority,JSONObject source,JSONObject nativeReceipt)throws Exception{
        authority.checkRead();if(!request.taskId.equals(source.optString("taskId"))||groupTask(request,authority)==null)throw new SecurityException("媒体回执不属于原群任务");JSONObject result=reconcileMediaReceipt(source,nativeReceipt);authority.checkRead();return result;
    }
    private JSONObject reconcileMediaReceipt(JSONObject expected,JSONObject receipt)throws Exception{
        String taskId=expected.getString("taskId"),operationId=expected.getString("operationId");JSONObject actual=mediaOperationSource(taskId,operationId);
        for(String field:new String[]{"runId","tool","argumentsHash"})if(!actual.getString(field).equals(expected.getString(field)))throw new SecurityException("原媒体操作身份已变化");
        if(actual.getInt("epoch")!=expected.getInt("epoch")||!operationId.equals(receipt.optString("operationId"))||!taskId.equals(receipt.optString("taskId"))||!actual.getString("runId").equals(receipt.optString("runId")))throw new SecurityException("媒体回执不是原任务与原执行代次");
        JSONObject previous=actual.optJSONObject("receipt");String pinned=actual.getJSONObject("arguments").optString("configurationRevision");
        if(!pinned.isEmpty()&&!pinned.equals(receipt.optString("configurationRevision")))throw new SecurityException("媒体回执配置不是原操作配置");
        if(previous!=null){
            for(String key:new String[]{"configurationRevision","provider"})if(!previous.optString(key).isEmpty()&&!previous.getString(key).equals(receipt.optString(key)))throw new SecurityException("媒体回执替换了原服务或配置");
            if(previous.optBoolean("providerJobIdConfirmed")&&(!receipt.optBoolean("providerJobIdConfirmed")||!previous.getString("providerJobId").equals(receipt.optString("providerJobId"))))throw new SecurityException("媒体回执替换了已确认的原job");
        }
        String state=receipt.optString("status");boolean uncertain=state.matches("unknown|prepared|submitting|host_receipt_unavailable");
        if(!uncertain&&!state.matches("queued|running|stopping|response_received|failed|cancelled"))throw new SecurityException("媒体回执状态不可判定");
        JSONObject safe=sanitize(receipt);ContentValues value=new ContentValues();value.put("state",uncertain?"unknown":"receipt");value.put("receipt",safe.toString());
        db.beginTransaction();try{
            if(db.update("operations",value,"task_id=? AND operation_id=? AND run_id=?",new String[]{taskId,operationId,actual.getString("runId")})!=1)throw new SecurityException("原媒体操作已移除");
            JSONObject task=required(taskId);appendEvent(task,new JSONObject().put("type","media_original_reconciled").put("operationId",operationId).put("sourceRunId",actual.getString("runId")).put("sourceEpoch",actual.getInt("epoch")).put("receipt",safe));
            task=required(taskId);if(task.optString("status").equals("reconciling")&&!hasUnresolved(taskId))task.put("status","awaiting_user").put("summary","原媒体效果已取得回执，可查看产物或继续同一原任务");write(task);db.setTransactionSuccessful();
        }finally{db.endTransaction();}
        return get(taskId,false);
    }
    public synchronized JSONObject get(String id,boolean image)throws Exception{JSONObject t=read(id);if(t==null)return null;t.put("events",TaskAuditStore.events(db,id,null));JSONObject result=copy(t,image);JSONArray operations=new JSONArray();try(Cursor c=db.rawQuery("SELECT operation_id,tool,arguments,state,receipt,run_id FROM operations WHERE task_id=? ORDER BY created_at",new String[]{id})){while(c.moveToNext()){JSONObject op=new JSONObject().put("operationId",c.getString(0)).put("tool",c.getString(1)).put("arguments",new JSONObject(c.getString(2))).put("state",c.getString(3)).put("runId",c.getString(5));if(!c.isNull(4))op.put("receipt",new JSONObject(c.getString(4)));operations.put(op);}}return result.put("operations",operations);}
    /** Owner presentation only. Native authorization/recovery continues to use get/hasUnresolved. */
    public synchronized JSONObject taskSummaryPage(String cursor,int limit)throws Exception{
        JSONObject page=TaskAuditStore.summaryPage(db,cursor,limit);JSONArray rows=page.getJSONArray("tasks"),visible=new JSONArray();for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);if("inference".equals(row.optString("kind")))continue;row.put("hasUnresolvedOperations",hasUnresolved(row.getString("id"))).put("historyHasUnconfirmedOperations",hasUnconfirmedForUi(row.getString("id")));visible.put(row);}return page.put("tasks",visible).put("accountingRecordsExcluded",rows.length()-visible.length());
    }
    /** Bounded durable accounting view over the same task headers, not fake executable tasks. */
    public synchronized JSONObject nativeInferenceUsagePage(String cursor,int limit)throws Exception{
        JSONObject page=TaskAuditStore.summaryPage(db,cursor,limit);JSONArray selected=new JSONArray(),rows=page.getJSONArray("tasks");
        for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);if(!"inference".equals(row.optString("kind"))||!"owner".equals(row.optString("actorId"))||!"private".equals(row.optString("spaceId")))continue;
            JSONObject scope=row.optJSONObject("nativeInference");if(scope==null||!Boolean.TRUE.equals(scope.opt("accountingOnly"))||!Boolean.FALSE.equals(scope.opt("executionAuthorized")))continue;selected.put(row);
        }
        return page.put("tasks",selected).put("headerRowsExamined",rows.length()).put("accountingOnly",true).put("executionAuthorized",false).put("coverage","bounded_original_header_window_not_global_usage");
    }
    public synchronized JSONObject getForUi(String id,boolean image)throws Exception{
        JSONObject result;boolean limited;
        try(Cursor row=db.rawQuery("SELECT CASE WHEN length(CAST(document AS BLOB))<=262144 THEN document END,ui_header,length(CAST(document AS BLOB)) FROM tasks WHERE task_id=?",new String[]{id})){
            if(!row.moveToFirst())return null;limited=row.isNull(0);result=new JSONObject(row.getString(limited?1:0));
            result.put("taskCoreBytes",row.getLong(2)).put("taskCoreReadLimited",limited);
        }
        JSONObject events=TaskAuditStore.eventPage(db,id,"",32,65536),operations=TaskAuditStore.operationPage(db,id,"",24,65536);
        JSONArray eventBodies=new JSONArray(),operationBodies=new JSONArray(),eventRows=events.getJSONArray("entries"),operationRows=operations.getJSONArray("entries");
        for(int i=0;i<eventRows.length();i++){JSONObject row=eventRows.getJSONObject(i);if(row.optJSONObject("event")!=null)eventBodies.put(row.getJSONObject("event"));}
        // Legacy consumers expect chronological events and operations, not a fabricated complete log.
        JSONArray chronological=new JSONArray();for(int i=eventBodies.length()-1;i>=0;i--)chronological.put(eventBodies.get(i));
        for(int i=operationRows.length()-1;i>=0;i--){JSONObject row=operationRows.getJSONObject(i);if(!"available".equals(row.optString("status")))continue;JSONObject operation=new JSONObject(row.toString());operation.remove("status");operationBodies.put(operation);}
        result.put("events",chronological).put("operations",operationBodies).put("taskProjection","detail_window").put("auditBodyIncluded",true).put("historyEventPage",events).put("historyOperationPage",operations).put("hasUnresolvedOperations",hasUnresolved(id)).put("historyHasUnconfirmedOperations",hasUnconfirmedForUi(id));
        JSONObject flags=new JSONObject();for(String type:new String[]{"memory_owner_consolidation","media_owner_prepared","browser_research_requested"})try(Cursor row=db.rawQuery("SELECT 1 FROM task_events WHERE task_id=? AND event_type=? LIMIT 1",new String[]{id,type})){flags.put(type,row.moveToFirst());}result.put("nativeEventTypeFlags",flags).put("nativeTaskKind",result.optJSONObject("sourceLearningJob")!=null?"source_learning":flags.optBoolean("memory_owner_consolidation")?"memory_consolidation":flags.optBoolean("media_owner_prepared")?"media":result.optString("kind"));
        return image&&!limited?copy(result,true):result;
    }
    private boolean hasUnconfirmedForUi(String id){
        // Presentation only: owned-running/stopping are real nonterminal facts, not unknown effects.
        try(Cursor row=db.rawQuery("SELECT 1 FROM operations WHERE task_id=? AND state IN ('prepared','unknown','remote_job_prepared','remote_job_sending','remote_job_unknown','remote_approval_sending','remote_approval_unknown','remote_question_prepared','remote_question_sending','remote_question_unknown') LIMIT 1",new String[]{id})){return row.moveToFirst();}
    }
    public synchronized JSONObject taskHistoryPage(String id,String section,String cursor,int limit,int maxBytes)throws Exception{
        try(Cursor row=db.rawQuery("SELECT 1 FROM tasks WHERE task_id=?",new String[]{id})){if(!row.moveToFirst())throw new IllegalArgumentException("原任务不存在");}if("events".equals(section))return TaskAuditStore.eventPage(db,id,cursor,limit,maxBytes);if("operations".equals(section))return TaskAuditStore.operationPage(db,id,cursor,limit,maxBytes);throw new IllegalArgumentException("未知原历史范围");
    }
    /** Read-only full history on this authority's original table. No parallel task/usage database. */
    public synchronized dev.pocketengine.features.memory.TaskStoreMemoryReader.TaskPage queryMemoryTasks(dev.pocketengine.features.memory.MemoryBook.Scope scope,String query,String cursor,int limit)throws Exception{
        return dev.pocketengine.features.memory.TaskStoreMemoryQuery.query(db,scope,query,cursor,limit);
    }
    /** The model raw reader and H4 share this original authority; all identity is native-bound. */
    /** Original run provenance only; prepared parts are not a model-use/completion receipt. */
    public synchronized void recordOwnerSourceExposure(String id,String run,int epoch,RunControl original,JSONArray carriers)throws Exception{
        db.beginTransaction();try{fence(id,run,epoch,original,false);JSONObject task=required(id);requirePrivateOwner(task);JSONObject origin=task.optJSONObject("origin");
            if(!dev.pocketengine.features.memory.OwnerSourceRunContext.acceptsPrivateOwnerProjection(task))throw new SecurityException("原文来源事实只能属于原私人聊天run");
            dev.pocketengine.features.memory.OwnerSourceRunContext.prepared(db,conversationRepository,id,run,task.getString("conversationId"),carriers);original.check();db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
    public synchronized JSONArray projectOwnerSourceContext(String id,String run,int epoch,RunControl original,JSONArray messages)throws Exception{
        fence(id,run,epoch,original,false);requireToolOutputSources(id,run,epoch,original);JSONObject task=required(id);requirePrivateOwner(task);return dev.pocketengine.features.memory.TaskStoreMemoryQuery.projectOwnerSourceContext(db,conversationRepository,id,run,task.optString("conversationId"),messages);
    }
    public synchronized JSONObject queryMemoryRaw(dev.pocketengine.features.memory.TaskStoreMemoryReader.RawBinding binding,JSONObject options)throws Exception{
        return dev.pocketengine.features.memory.TaskStoreMemoryQuery.queryRaw(db,binding,options,conversationRepository);
    }
    public synchronized JSONObject readMemoryRaw(dev.pocketengine.features.memory.TaskStoreMemoryReader.RawBinding binding,JSONObject citation)throws Exception{
        return dev.pocketengine.features.memory.TaskStoreMemoryQuery.readRaw(db,binding,citation,conversationRepository);
    }
    public synchronized JSONObject readNativeOutputAudit(dev.pocketengine.features.memory.MemoryBook.Scope scope,JSONObject request,RunControl ownerRead)throws Exception{
        return dev.pocketengine.features.memory.TaskStoreMemoryQuery.readOutputAudit(db,scope,request,ownerRead);
    }
    /** The active caller and historical source identities are intentionally separate. */
    public JSONObject queryConversationHistory(dev.pocketengine.features.memory.ConversationHistory.HistoryBinding binding,dev.pocketengine.features.memory.ConversationHistory.SourceLocator source,String kind,JSONObject options)throws Exception{
        JSONObject warm="outputs".equals(kind)?warmHistoricalOutputSources(binding,source):null;
        synchronized(this){JSONObject result=dev.pocketengine.features.memory.TaskStoreMemoryQuery.queryConversationHistory(db,binding,source,kind,options,conversationRepository);return warm==null?result:result.put("sourceRevalidation",warm);}
    }
    public JSONObject readConversationHistoryOperation(dev.pocketengine.features.memory.ConversationHistory.HistoryBinding binding,dev.pocketengine.features.memory.ConversationHistory.SourceLocator source,int offset,int maxChars)throws Exception{
        JSONObject warm=source.kind==dev.pocketengine.features.memory.ConversationHistory.Kind.OUTPUT?warmHistoricalOutputSources(binding,source):null;
        synchronized(this){JSONObject result=dev.pocketengine.features.memory.TaskStoreMemoryQuery.readConversationHistoryOperation(db,binding,source,offset,maxChars,conversationRepository);return warm==null?result:result.put("sourceRevalidation",warm);}
    }
    /** Only an explicit same-conversation historical selection. No source scan, new grant, old
     * control revival or metadata-only promotion; Book bytes are checked without holding Tasks. */
    private JSONObject warmHistoricalOutputSources(dev.pocketengine.features.memory.ConversationHistory.HistoryBinding binding,dev.pocketengine.features.memory.ConversationHistory.SourceLocator source)throws Exception{
        if(Thread.holdsLock(this))throw new SecurityException("不能持Tasks锁进入历史附件原件字节核验");binding.caller.control.check();
        final JSONArray dependencies;final dev.pocketengine.features.attachments.AttachmentBook book;
        synchronized(this){if(binding.caller.outputSources()!=outputSources)throw new SecurityException("历史原件不是本TaskStore来源绑定");
            dependencies=dev.pocketengine.features.memory.TaskStoreMemoryQuery.historicalOutputWarmDependencies(db,binding,source,conversationRepository);book=outputBook;}
        try{
            long started=System.nanoTime(),remaining=dev.pocketengine.features.attachments.AttachmentBook.MAX_TOTAL_BYTES;
            for(int i=0;i<dependencies.length();i++){binding.caller.control.check();JSONObject ref=dependencies.getJSONObject(i);if(book==null||ref.isNull("generation"))throw new SecurityException("原附件历史来源代次无法核对");
                String id=ref.getString("id"),sha=ref.getString("sha256"),generation=ref.getString("generation");
                if(!book.outputSourceCurrent(id,sha,generation))remaining-=book.revalidateOutputSource(id,sha,generation,remaining,binding.caller.control);
            }
            synchronized(this){JSONArray current=dev.pocketengine.features.memory.TaskStoreMemoryQuery.historicalOutputWarmDependencies(db,binding,source,conversationRepository);
                if(!canonical(dependencies).equals(canonical(current)))throw new SecurityException("冷核验期间历史原来源依赖已变化");
                dev.pocketengine.features.memory.ToolOutputStore.requireDependencies(current,outputSources);binding.check();}
            return new JSONObject().put("scope","explicit_selected_historical_run_dependencies_only").put("dependenciesChecked",dependencies.length())
                    .put("revalidatedOriginalSourceBytes",dev.pocketengine.features.attachments.AttachmentBook.MAX_TOTAL_BYTES-remaining).put("sourceByteLimit",dev.pocketengine.features.attachments.AttachmentBook.MAX_TOTAL_BYTES)
                    .put("elapsedMillis",(System.nanoTime()-started)/1_000_000L).put("outsideTaskStoreMonitor",true).put("separateFromSqlPayloadBudget",true)
                    .put("deadlineSemantics","original_book_bounded_bytes_and_cancellation_not_500ms_physical_IO");
        }catch(Exception unavailable){binding.caller.control.check();var refused=new dev.pocketengine.features.memory.TaskStoreMemoryReader.RawMessageUnavailable("historical_output_source_unavailable");refused.initCause(unavailable);throw refused;}
    }
    public synchronized void requireConversationHistorySources(dev.pocketengine.features.memory.ConversationHistory.HistoryBinding binding,java.util.List<dev.pocketengine.features.memory.ConversationHistory.SourceLocator> provided)throws Exception{
        dev.pocketengine.features.memory.TaskStoreMemoryQuery.requireConversationHistorySources(db,binding,provided,conversationRepository);
    }
    public synchronized JSONObject conversationHistoryCitation(JSONObject originalReceiptCitation)throws Exception{
        return dev.pocketengine.features.memory.TaskStoreMemoryQuery.conversationHistoryCitation(db,originalReceiptCitation);
    }
    public synchronized dev.pocketengine.features.memory.TaskStoreMemoryQuery.HistoryProjectionSession openConversationHistoryProjection(dev.pocketengine.features.memory.ConversationHistory.HistoryBinding binding,long sourceBytesRemaining)throws Exception{
        return dev.pocketengine.features.memory.TaskStoreMemoryQuery.openConversationHistoryProjection(db,this,binding,sourceBytesRemaining,conversationRepository);
    }
    public synchronized JSONArray projectConversationHistoryReferences(JSONArray history,String conversation)throws Exception{
        return dev.pocketengine.features.memory.TaskStoreMemoryQuery.projectConversationHistoryReferences(db,history,conversation,conversationRepository,outputSources);
    }
    /** Read only from this original Task/Run/Operation authority. No shadow trace database or replay. */
    public synchronized JSONObject readMemoryTaskTrace(dev.pocketengine.features.memory.MemoryBook.Scope scope,String taskId,String runId,JSONObject options)throws Exception{
        if(scope==null||!dev.pocketengine.features.memory.TaskStoreMemoryQuery.validTaskId(taskId)||runId==null
                ||!runId.isEmpty()&&!dev.pocketengine.features.memory.TaskStoreMemoryQuery.validTaskId(runId))throw new IllegalArgumentException("原任务或执行轮次无效");
        if(!"owner".equals(scope.ownerId)||!"private".equals(scope.spaceId))throw new SecurityException("此入口只读当前私人任务；群和渠道须回到原入口核对");
        db.beginTransaction();try{
            JSONObject task=read(taskId);if(task!=null)task.put("events",TaskAuditStore.events(db,taskId,null));
            if(!memoryTraceVisible(scope,task))throw new SecurityException("执行记录不属于当前私人任务空间；请回到原入口核对");
            JSONObject run=null;
            String sql="SELECT run_id,epoch,status,checkpoint FROM runs WHERE task_id=?"+(runId.isEmpty()?" ORDER BY epoch DESC LIMIT 1":" AND run_id=?");
            try(Cursor row=db.rawQuery(sql,runId.isEmpty()?new String[]{taskId}:new String[]{taskId,runId})){
                if(row.moveToFirst())run=new JSONObject().put("taskId",taskId).put("runId",row.getString(0)).put("coordinatorEpoch",row.getInt(1))
                        .put("status",row.getString(2)).put("checkpoint",new JSONObject(row.getString(3)));
            }
            if(run==null&&!runId.isEmpty())return new JSONObject().put("taskId",taskId).put("runId",runId).put("ownerId",scope.ownerId).put("spaceId",scope.spaceId)
                    .put("status","unavailable").put("reason","run_not_found").put("entries",new JSONArray()).put("nextCursor","")
                    .put("executionAuthorized",false).put("executionIndependentlyVerified",false).put("completeOriginalTrace",false);
            if(run==null)run=new JSONObject().put("taskId",taskId).put("runId","").put("coordinatorEpoch",0).put("status","no_run_record");
            int epoch=run.getInt("coordinatorEpoch");
            run.put("previousRunId",memoryTraceNeighbour(taskId,epoch,false)).put("nextRunId",memoryTraceNeighbour(taskId,epoch,true));
            JSONObject lineage=memoryTraceParents(scope,task);
            final String selectedRun=run.getString("runId");JSONObject result;
            // Stream the original operation rows: fingerprint every row, retain only the bounded page.
            try(Cursor rows=db.rawQuery("SELECT operation_id,tool,arguments,state,receipt,run_id,created_at FROM operations WHERE task_id=? AND run_id=? ORDER BY created_at,operation_id",new String[]{taskId,selectedRun})){
                java.util.Iterator<JSONObject> iterator=new java.util.Iterator<JSONObject>(){
                    boolean loaded,available;
                    public boolean hasNext(){if(!loaded){available=rows.moveToNext();loaded=true;}return available;}
                    public JSONObject next(){
                        if(!hasNext())throw new java.util.NoSuchElementException();loaded=false;
                        try{
                            String arguments=rows.getString(2),receipt=rows.isNull(4)?null:rows.getString(4);
                            JSONObject raw=new JSONObject().put("arguments",arguments).put("receipt",receipt==null?JSONObject.NULL:receipt);
                            return new JSONObject().put("taskId",taskId).put("runId",rows.getString(5)).put("operationId",rows.getString(0)).put("tool",rows.getString(1))
                                    .put("arguments",memoryTraceBody(arguments)).put("state",rows.getString(3)).put("receipt",receipt==null?JSONObject.NULL:memoryTraceBody(receipt))
                                    .put("createdAt",rows.getLong(6)).put("storedBodyFingerprint",capabilityFingerprint(raw));
                        }catch(Exception invalid){throw new IllegalStateException("原操作行不可读，未用摘要替代",invalid);}
                    }
                };
                result=dev.pocketengine.features.memory.TaskMemoryTrace.page(new JSONObject().put("ownerId",scope.ownerId).put("spaceId",scope.spaceId),task,run,iterator,lineage,options);
            }
            db.setTransactionSuccessful();return result;
        }finally{db.endTransaction();}
    }
    private String memoryTraceNeighbour(String taskId,int epoch,boolean newer)throws Exception{
        try(Cursor row=db.rawQuery("SELECT run_id FROM runs WHERE task_id=? AND epoch"+(newer?">? ORDER BY epoch ASC":"<? ORDER BY epoch DESC")+" LIMIT 1",new String[]{taskId,Integer.toString(epoch)})){
            return row.moveToFirst()?row.getString(0):"";
        }
    }
    private static boolean memoryTraceVisible(dev.pocketengine.features.memory.MemoryBook.Scope scope,JSONObject task){
        if(task==null||!scope.ownerId.equals(task.optString("actorId"))||!scope.spaceId.equals(task.optString("spaceId")))return false;
        JSONObject origin=task.optJSONObject("origin");return origin==null||!java.util.Set.of("group","channel").contains(origin.optString("kind"));
    }
    private JSONObject memoryTraceParents(dev.pocketengine.features.memory.MemoryBook.Scope scope,JSONObject task)throws Exception{
        JSONArray links=new JSONArray();java.util.Set<String> seen=new java.util.HashSet<>();JSONObject child=task;String status="complete";
        seen.add(task.getString("id"));
        for(int depth=0;depth<dev.pocketengine.features.memory.TaskMemoryTrace.MAX_PARENTS;depth++){
            String parentId=child.optString("parentTaskId");if(parentId.isEmpty())break;
            if(!dev.pocketengine.features.memory.TaskStoreMemoryQuery.validTaskId(parentId)||!child.optString("parentRunId").isEmpty()&&!dev.pocketengine.features.memory.TaskStoreMemoryQuery.validTaskId(child.optString("parentRunId"))){status="invalid_parent";break;}
            JSONObject parent=read(parentId);boolean visible=memoryTraceVisible(scope,parent)&&parentId.equals(parent.optString("id"));
            links.put(new JSONObject().put("ownerId",scope.ownerId).put("spaceId",scope.spaceId).put("childTaskId",child.getString("id"))
                    .put("parentTaskId",parentId).put("parentRunId",child.optString("parentRunId")).put("parentReadable",visible));
            if(!visible){status="parent_unavailable";break;}
            if(!seen.add(parentId)){status="cycle";break;}child=parent;
            if(depth==dev.pocketengine.features.memory.TaskMemoryTrace.MAX_PARENTS-1&&!child.optString("parentTaskId").isEmpty())status="depth_limit";
        }return new JSONObject().put("status",status).put("links",links);
    }
    private static Object memoryTraceBody(String stored){
        try{org.json.JSONTokener parser=new org.json.JSONTokener(stored);Object value=parser.nextValue();if(parser.nextClean()==0)return value;}catch(Exception unparsed){}
        return stored; // Preserve an older non-JSON stored body exactly; never generate replacement prose.
    }
    /** Native owner read only, delegated to the original SQLite authority under this monitor. */
    public synchronized JSONObject readMemoryTraceOperation(dev.pocketengine.features.memory.MemoryBook.Scope scope,JSONObject request)throws Exception{
        return dev.pocketengine.features.memory.TaskStoreMemoryQuery.readOperation(db,scope,request);
    }
    /** Native selection token only. A tool lookup is prepared reference data, not a claim
     * that a MAIN request already received/applied it. Persist dependencies before its body. */
    public synchronized void prepareMemoryToolReference(String tool,dev.pocketengine.features.memory.TaskMemoryUse.Selection selected,RunControl original)throws Exception{
        if(!java.util.Set.of("memory_search","memory_recall").contains(tool))throw new SecurityException("不是原记忆只读工具");
        JSONObject metadata=java.util.Objects.requireNonNull(selected).metadata();String id=metadata.getString("taskId"),run=metadata.getString("runId");int epoch=Math.toIntExact(metadata.getLong("coordinatorEpoch"));
        db.beginTransaction();try{
            checkCapability(id,run,epoch,tool,original);JSONObject task=required(id);requirePrivateOwner(task);
            if(!"owner".equals(metadata.optString("ownerId"))||!"private".equals(metadata.optString("spaceId")))throw new SecurityException("原记忆工具选择不属于私人原任务");
            dev.pocketengine.features.memory.OwnerSourceRunContext.formalPrepared(db,id,run,metadata.getJSONArray("records"));
            appendEvent(task,new JSONObject().put("type","memory_tool_reference_prepared").put("tool",tool).put("selectionId",metadata.getString("selectionId")).put("contextDigest",metadata.getString("contextDigest")).put("records",metadata.getJSONArray("records")).put("modelUseVerified",false).put("executionAuthorized",false));
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
    /** An audit intent consumes no operation budget and is never treated as an effect/permission. */
    public synchronized void prepareMemoryReference(dev.pocketengine.features.memory.TaskMemoryJournal.Intent intent,RunControl control)throws Exception{
        db.beginTransaction();try{checkExtensionRun(intent.task.taskId,intent.task.runId,Math.toIntExact(intent.task.coordinatorEpoch),control);
        JSONObject task=required(intent.task.taskId);if(!intent.task.ownerId.equals(task.getString("actorId"))||!intent.task.spaceId.equals(task.getString("spaceId")))throw new SecurityException("记忆请求空间已变化");
        ContentValues value=new ContentValues();value.put("operation_id",intent.operationId);value.put("task_id",intent.task.taskId);value.put("run_id",intent.task.runId);
        value.put("tool",dev.pocketengine.features.memory.TaskMemoryJournal.TOOL);value.put("arguments",intent.arguments().toString());value.put("state",dev.pocketengine.features.memory.TaskMemoryJournal.PREPARED);value.put("created_at",System.currentTimeMillis());db.insertOrThrow("operations",null,value);
        if(acceptsPrivateOutputTask(task))dev.pocketengine.features.memory.OwnerSourceRunContext.formalPrepared(db,intent.task.taskId,intent.task.runId,intent.arguments().getJSONArray("records"));db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
    /** Only a live opaque pre-send intent can be completed by the original native model-response path. */
    public synchronized JSONObject completeMemoryReference(dev.pocketengine.features.memory.TaskMemoryJournal.Intent intent,String answerId,RunControl control)throws Exception{
        checkExtensionRun(intent.task.taskId,intent.task.runId,Math.toIntExact(intent.task.coordinatorEpoch),control);
        try(Cursor row=db.rawQuery("SELECT arguments,state FROM operations WHERE operation_id=? AND task_id=? AND run_id=? AND tool=?",new String[]{intent.operationId,intent.task.taskId,intent.task.runId,dev.pocketengine.features.memory.TaskMemoryJournal.TOOL})){
            if(!row.moveToFirst()||!dev.pocketengine.features.memory.TaskMemoryJournal.PREPARED.equals(row.getString(1)))throw new SecurityException("原记忆发送意图已结束或失效");
            dev.pocketengine.features.memory.TaskMemoryJournal.requireIntent(intent,new JSONObject(row.getString(0)));
        }
        JSONObject receipt=dev.pocketengine.features.memory.TaskMemoryJournal.completed(intent,answerId,System.currentTimeMillis());ContentValues value=new ContentValues();value.put("state",dev.pocketengine.features.memory.TaskMemoryJournal.PROVIDED);value.put("receipt",receipt.toString());
        if(db.update("operations",value,"operation_id=? AND state=?",new String[]{intent.operationId,dev.pocketengine.features.memory.TaskMemoryJournal.PREPARED})!=1)throw new IllegalStateException("原记忆提供回执未保存");return receipt;
    }
    public synchronized void abandonMemoryReference(dev.pocketengine.features.memory.TaskMemoryJournal.Intent intent)throws Exception{
        if(intent==null)return;ContentValues value=new ContentValues();value.put("state",dev.pocketengine.features.memory.TaskMemoryJournal.UNCONFIRMED);
        db.update("operations",value,"operation_id=? AND task_id=? AND run_id=? AND tool=? AND state=?",new String[]{intent.operationId,intent.task.taskId,intent.task.runId,dev.pocketengine.features.memory.TaskMemoryJournal.TOOL,dev.pocketengine.features.memory.TaskMemoryJournal.PREPARED});
    }
    /** Retire original persisted request contexts, not user text or irreversible effect receipts. */
    public synchronized JSONObject invalidateMemoryContexts(dev.pocketengine.features.memory.MemoryBook.Scope scope)throws Exception{return invalidateMemoryContexts(scope,null);}
    public synchronized JSONObject invalidateMemoryContexts(dev.pocketengine.features.memory.MemoryBook.Scope scope,JSONArray removedRecords)throws Exception{
        int checkpoints=0,messages=0,intents=0;db.beginTransaction();try{
            try(Cursor rows=db.rawQuery("SELECT r.run_id,r.checkpoint,t.document FROM runs r JOIN tasks t ON t.task_id=r.task_id",null)){
                while(rows.moveToNext()){
                    JSONObject task=new JSONObject(rows.getString(2));if(!scope.ownerId.equals(task.optString("actorId"))||!scope.spaceId.equals(task.optString("spaceId")))continue;
                    if(removedRecords!=null)dev.pocketengine.features.memory.OwnerSourceRunContext.withdrawFormalOutputs(db,task.getString("id"),rows.getString(0),removedRecords);
                    else if(dev.pocketengine.features.memory.ToolOutputStore.hasCapturedOutput(db,task.getString("id"),rows.getString(0)))dev.pocketengine.features.memory.OwnerSourceRunContext.withdrawFormalOutputs(db,task.getString("id"),rows.getString(0),null);
                    JSONObject checkpoint=new JSONObject(rows.getString(1));int retired=dev.pocketengine.features.memory.TaskMemoryHistory.retire(checkpoint.optJSONArray("messages"),null,true);
                    if(checkpoint.remove("contextCompaction")!=null)retired++;
                    if(retired>0){ContentValues value=new ContentValues();value.put("checkpoint",checkpoint.toString());value.put("updated_at",System.currentTimeMillis());db.update("runs",value,"run_id=?",new String[]{rows.getString(0)});checkpoints++;messages+=retired;}
                    ContentValues invalid=new ContentValues();invalid.put("state",dev.pocketengine.features.memory.TaskMemoryJournal.UNCONFIRMED);
                    intents+=db.update("operations",invalid,"run_id=? AND tool=? AND state=?",new String[]{rows.getString(0),dev.pocketengine.features.memory.TaskMemoryJournal.TOOL,dev.pocketengine.features.memory.TaskMemoryJournal.PREPARED});
                }
            }
            db.setTransactionSuccessful();return new JSONObject().put("checkpointsRetired",checkpoints).put("referenceMessagesRetired",messages).put("inflightIntentsInvalidated",intents).put("contextInvalidated",true);
        }finally{db.endTransaction();}
    }
    public synchronized JSONArray list()throws Exception{JSONArray list=new JSONArray();try(Cursor c=db.rawQuery("SELECT document FROM tasks ORDER BY updated_at DESC LIMIT 200",null)){while(c.moveToNext()){JSONObject task=new JSONObject(c.getString(0));task.put("events",TaskAuditStore.events(db,task.getString("id"),null));list.put(copy(task,false));}}return list;}
    private JSONObject read(String id)throws Exception{try(Cursor c=db.rawQuery("SELECT document FROM tasks WHERE task_id=?",new String[]{id})){return c.moveToFirst()?new JSONObject(c.getString(0)):null;}}
    private JSONObject required(String id)throws Exception{JSONObject t=read(id);if(t==null)throw new IllegalArgumentException("任务不存在");return t;}
    private void write(JSONObject value)throws Exception{
        boolean channel=value.optJSONObject("origin")!=null&&value.getJSONObject("origin").optString("kind").equals("channel"),group=value.optJSONObject("origin")!=null&&value.getJSONObject("origin").optString("kind").equals("group");db.beginTransaction();
        try{
            if(channel||group){String key=group?"groupStateRevision":"channelStateRevision";JSONObject previous=read(value.getString("id"));long revision=previous==null?0:previous.optLong(key,0);if(revision==Long.MAX_VALUE)throw new IllegalStateException("渠道任务回执版本已耗尽");value.put(key,revision+1);}
            if(value.optJSONObject("origin")!=null&&"owner_reviewed_realtime".equals(value.getJSONObject("origin").optString("kind"))){JSONObject previous=read(value.getString("id"));long revision=previous==null?0:Math.max(1,previous.optLong("voiceStateRevision",previous.optLong("updatedAt",1)));if(revision==Long.MAX_VALUE)throw new IllegalStateException("语音任务记录版本已耗尽");value.put("voiceStateRevision",revision+1);}
            value.put("updatedAt",System.currentTimeMillis());ContentValues v=new ContentValues();v.put("task_id",value.getString("id"));v.put("document",TaskAuditStore.coreAndImport(db,value));v.put("audit_format",1);v.put("ui_header",TaskAuditStore.header(db,value).toString());v.put("updated_at",value.getLong("updatedAt"));if(db.update("tasks",v,"task_id=?",new String[]{value.getString("id")})==0&&db.insertOrThrow("tasks",null,v)<0)throw new IllegalStateException("任务事务写入失败");
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
    private JSONObject checkpoint(String runId)throws Exception{try(Cursor c=db.rawQuery("SELECT checkpoint FROM runs WHERE run_id=?",new String[]{runId})){return c.moveToFirst()?new JSONObject(c.getString(0)):new JSONObject();}}
    private JSONObject checkpointForTask(String id)throws Exception{try(Cursor c=db.rawQuery("SELECT checkpoint FROM runs WHERE task_id=? ORDER BY epoch DESC LIMIT 1",new String[]{id})){return c.moveToFirst()?new JSONObject(c.getString(0)):new JSONObject();}}
    private JSONObject grant(String runId)throws Exception{try(Cursor c=db.rawQuery("SELECT grant_json FROM runs WHERE run_id=?",new String[]{runId})){if(!c.moveToFirst())throw new SecurityException("没有执行授权");return new JSONObject(c.getString(0));}}
    private JSONObject copy(JSONObject t,boolean image)throws Exception{JSONObject result=new JSONObject(t.toString());JSONObject evidence=result.optJSONObject("evidence");if(image&&evidence!=null&&evidence.optString("imageFile").equals(t.optString("id")+".jpg")){File f=new File(artifacts,evidence.getString("imageFile"));if(f.isFile()&&f.length()<4_000_000)evidence.put("imageDataUrl","data:image/jpeg;base64,"+Base64.encodeToString(Files.readAllBytes(f.toPath()),Base64.NO_WRAP));}return result;}
    private void importLegacy(AtomicFile legacy)throws Exception{try(Cursor c=db.rawQuery("SELECT value FROM meta WHERE key='legacyImported'",null)){if(c.moveToFirst())return;}db.beginTransaction();try{JSONArray old;try{old=new JSONArray(new String(legacy.readFully(),StandardCharsets.UTF_8));}catch(java.io.FileNotFoundException absent){old=new JSONArray();}for(int i=0;i<old.length();i++){JSONObject t=old.optJSONObject(i);if(t==null||!t.optString("id").matches("[a-f0-9-]{36}"))continue;if(t.optJSONArray("events")==null)t.put("events",new JSONArray());if(t.optJSONObject("evidence")==null)t.put("evidence",new JSONObject());if(!t.has("kind"))t.put("kind","phone");write(t);}db.execSQL("INSERT INTO meta(key,value) VALUES('legacyImported','1')");db.setTransactionSuccessful();}finally{db.endTransaction();}}
    private void recover()throws Exception{
        db.beginTransaction();try{
            db.execSQL("UPDATE operations SET state='reference_unconfirmed' WHERE tool='native_memory_reference' AND state='reference_prepared'");
            db.execSQL("UPDATE operations SET state='unknown' WHERE state='prepared'");
            db.execSQL("UPDATE operations SET state='remote_job_unknown' WHERE state IN ('remote_job_prepared','remote_job_sending')");
            db.execSQL("UPDATE operations SET state='remote_approval_unknown' WHERE state='remote_approval_sending'");
            try(Cursor questions=db.rawQuery("SELECT operation_id FROM operations WHERE tool='remote_question_answer' AND state='remote_question_prepared'",null)){while(questions.moveToNext()){String operation=questions.getString(0);ContentValues value=new ContentValues();value.put("state","remote_question_rejected");value.put("receipt",new JSONObject().put("answerId",operation).put("status","rejected").put("dispatched",false).put("notDispatchedConfirmed",true).put("proof","original_SQL_dispatch_fence_was_never_claimed").toString());db.update("operations",value,"operation_id=? AND state='remote_question_prepared'",new String[]{operation});}}
            db.execSQL("UPDATE operations SET state='remote_question_unknown' WHERE state='remote_question_sending'");
            try(Cursor messages=db.rawQuery("SELECT operation_id,arguments FROM operations WHERE tool='remote_message' AND state IN ('message_prepared','message_sending')",null)){
                while(messages.moveToNext()){
                    JSONObject binding=new JSONObject(messages.getString(1));ContentValues changed=new ContentValues();changed.put("state","message_unknown");changed.put("receipt",messageReceipt(binding,"unknown","手机重启前未确认消息接收；仅核对原消息编号，未重发").toString());db.update("operations",changed,"operation_id=?",new String[]{messages.getString(0)});
                }
            }
            try(Cursor c=db.rawQuery("SELECT document FROM tasks",null)){
                while(c.moveToNext()){
                    JSONObject t=new JSONObject(c.getString(0));String status=t.optString("status");
                    if("inference".equals(t.optString("kind"))&&"recording".equals(status)){t.put("status","interrupted").put("summary","用量会话因进程重开结束；未收到的终态/用量仍未知，没有重发");write(t);continue;}
                    if(!status.matches("running|preparing|paused|pausing|stopping|verifying|queued"))continue;
                    String feature=t.optString("feature");
                    if(hasUnresolved(t.getString("id")))t.put("status","reconciling").put("summary","上次动作结果不明，请核对后继续原任务");
                    else if(t.optJSONObject("origin")!=null&&t.getJSONObject("origin").optString("kind").equals("channel")&&t.optBoolean("cancelRequested"))t.put("status","cancelled").put("summary","停止请求已保存；重启后未恢复执行，已有结果保留");
                    else if(feature.equals("remote")||feature.equals("ssh"))t.put("status","reconciling").put("summary","手机已重启，远端任务可能仍在执行；请核对原操作，未重提任务");
                    else if(feature.equals("browser"))t.put("status","awaiting_user").put("summary","浏览器执行租约已失效，目标和已有结果保留；请重新打开浏览器并接管");
                    else if(feature.equals("teams"))t.put("status","interrupted").put("summary","手机已重启，团队分析已中断；已有成员结果保留，未自动重放");
                    else if(feature.equals("plugins"))t.put("status","reconciling").put("summary","工具请求可能已经被处理；请核对服务端结果，未重复提交");
                    else if(t.optString("kind").equals("phone")&&!t.optString("runId").isEmpty())t.put("status",t.optBoolean("cancelRequested")?"cancelled":"paused").put("summary","执行已中断，目标和上下文已保留，可继续原任务");
                    else t.put("status","interrupted").put("summary","上次执行已中断，请检查已有结果");
                    write(t);
                }
            }
            db.execSQL("UPDATE runs SET status='interrupted' WHERE status='running'");db.delete("leases",null,null);db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
    private static JSONObject sanitize(JSONObject in)throws Exception{JSONObject out=new JSONObject();java.util.Iterator<String> keys=in.keys();while(keys.hasNext()){String key=keys.next();if(key.equalsIgnoreCase("apiKey")||key.equalsIgnoreCase("authorization")||key.equalsIgnoreCase("token")||key.equalsIgnoreCase("secret")||key.equals("imageBase64")||key.equals("imageDataUrl"))continue;Object value=in.get(key);if(value instanceof JSONObject)value=sanitize((JSONObject)value);else if(value instanceof JSONArray){JSONArray arr=(JSONArray)value,small=new JSONArray();for(int i=0;i<Math.min(200,arr.length());i++){Object v=arr.get(i);small.put(v instanceof JSONObject?sanitize((JSONObject)v):v);}value=small;}else if(value instanceof String&&((String)value).length()>12000)value=((String)value).substring(0,12000)+"…";out.put(key,value);}return out;}
    private static final class Database extends SQLiteOpenHelper{
        Database(Context context){super(context,"pocket-world.db",null,5);}
        @Override public void onCreate(SQLiteDatabase d){d.execSQL("CREATE TABLE meta(key TEXT PRIMARY KEY,value TEXT NOT NULL)");d.execSQL("INSERT INTO meta(key,value) VALUES('assistant_history_new_install','1')");d.execSQL("CREATE TABLE tasks(task_id TEXT PRIMARY KEY,document TEXT NOT NULL,updated_at INTEGER NOT NULL)");d.execSQL("CREATE TABLE runs(run_id TEXT PRIMARY KEY,task_id TEXT NOT NULL,epoch INTEGER NOT NULL,status TEXT NOT NULL,checkpoint TEXT NOT NULL,grant_json TEXT NOT NULL,created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL,UNIQUE(task_id,epoch))");d.execSQL("CREATE TABLE operations(operation_id TEXT PRIMARY KEY,task_id TEXT NOT NULL,run_id TEXT NOT NULL,tool TEXT NOT NULL,arguments TEXT NOT NULL,state TEXT NOT NULL,receipt TEXT,created_at INTEGER NOT NULL)");d.execSQL("CREATE INDEX operations_task ON operations(task_id,state)");d.execSQL("CREATE TABLE leases(resource_id TEXT PRIMARY KEY,run_id TEXT NOT NULL,epoch INTEGER NOT NULL,expires_at INTEGER NOT NULL)");dev.pocketengine.features.assistant.ConversationRepository.createSchema(d);TaskAuditStore.createSchema(d);dev.pocketengine.features.local.project.ProjectPlanRecords.createSchema(d);createTeamParentSchema(d);dev.pocketengine.features.memory.ToolOutputStore.createSchema(d);}
        @Override public void onUpgrade(SQLiteDatabase d,int oldVersion,int newVersion){if(oldVersion<1||newVersion!=5)throw new IllegalStateException("缺少明确数据库迁移，保留旧库并停止升级");if(oldVersion<2)dev.pocketengine.features.assistant.ConversationRepository.createSchema(d);if(oldVersion<3){TaskAuditStore.createSchema(d);try{TaskAuditStore.migrateLegacy(d);}catch(Exception failed){throw new IllegalStateException("原任务事件迁移失败，原始数据库未删除",failed);}}if(oldVersion<4){dev.pocketengine.features.local.project.ProjectPlanRecords.createSchema(d);createTeamParentSchema(d);}if(oldVersion<5)dev.pocketengine.features.memory.ToolOutputStore.createSchema(d);}
    }
}
