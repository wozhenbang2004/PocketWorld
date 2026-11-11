package dev.pocketengine.features.skills;

import android.content.Context;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.EngineModule;
import dev.pocketengine.store.TaskStore;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONObject;

/** One real AgentRunner call waits for its exact native review, then uses the canonical backend.
 * No second runner, detached script task, inferred owner grant, or resumable Plan JSON. */
public final class NativeSkillExecutionHost implements AutoCloseable {
    @FunctionalInterface public interface LiveRun {void check(String task,String run,int epoch,RunControl original)throws Exception;}
    private final SkillsModule skills;
    private final Context android;
    private final dev.pocketengine.features.local.LocalModule local;
    private final EngineModule remote;
    private final TaskStore tasks;
    private final LiveRun live;
    private final Consumer<String> changed;
    private final SkillExecutionConfiguration configuration;
    private final ManagedScriptCallbackTransport.Factory callbackTransport;
    private final ConcurrentHashMap<String,Review> pending=new ConcurrentHashMap<>();
    private final ConcurrentHashMap<RunControl,OwnerInvocation> ownerInvocations=new ConcurrentHashMap<>();
    private volatile boolean closed;
    public NativeSkillExecutionHost(Context android,SkillsModule skills,EngineModule remote,TaskStore tasks,LiveRun live,Consumer<String> changed){this(android,skills,remote,tasks,live,changed,null);}
    public NativeSkillExecutionHost(Context android,SkillsModule skills,EngineModule remote,TaskStore tasks,LiveRun live,Consumer<String> changed,dev.pocketengine.features.local.LocalModule local){
        this(android,skills,remote,tasks,live,changed,local,new AndroidManagedScriptCallbackTransport());
    }
    NativeSkillExecutionHost(Context android,SkillsModule skills,EngineModule remote,TaskStore tasks,LiveRun live,Consumer<String> changed,dev.pocketengine.features.local.LocalModule local,ManagedScriptCallbackTransport.Factory callbackTransport){
        this.android=java.util.Objects.requireNonNull(android).getApplicationContext();this.local=local;
        this.callbackTransport=java.util.Objects.requireNonNull(callbackTransport);
        this.skills=java.util.Objects.requireNonNull(skills);this.remote=java.util.Objects.requireNonNull(remote);this.tasks=java.util.Objects.requireNonNull(tasks);
        this.live=java.util.Objects.requireNonNull(live);this.changed=java.util.Objects.requireNonNull(changed);configuration=new SkillExecutionConfiguration(new File(android.getFilesDir(),"skill-execution.json"));
    }
    /** Retained opaque native page value. JSON or a recovered journal cannot construct this authority. */
    public final class Review {
        public final SkillExecution.Plan plan;
        private final SkillExecution.Grant grant;
        private final SkillExecutionConfiguration.Selection selected;
        private final RunControl control;
        private final SkillExecution execution;
        private final NativeSkillBackend backend;
        private final Set<String> capabilities;
        private final dev.pocketengine.core.CapabilitySnapshot.CurrentCheck capabilityCurrent;
        private final SkillBook.OwnerExecutionSelection ownerSelection;
        private final String nativeScope,assetScope;
        private final Object decision=new Object();
        private Boolean approved;
        private boolean dispatched,finished;
        private final java.util.concurrent.atomic.AtomicLong publicationRevision=new java.util.concurrent.atomic.AtomicLong();
        public long publicationRevision(){return publicationRevision.get();}
        private Review(SkillExecution.Plan plan,SkillExecution.Grant grant,SkillExecutionConfiguration.Selection selected,RunControl control,SkillExecution execution,NativeSkillBackend backend,Set<String> capabilities,dev.pocketengine.core.CapabilitySnapshot.CurrentCheck capabilityCurrent,SkillBook.OwnerExecutionSelection ownerSelection,JSONObject nativeScope,JSONObject assetScope){
            this.plan=plan;this.grant=grant;this.selected=selected;this.control=control;this.execution=execution;this.backend=backend;this.capabilities=Set.copyOf(capabilities);this.capabilityCurrent=capabilityCurrent;this.ownerSelection=ownerSelection;this.nativeScope=nativeScope.toString();this.assetScope=assetScope.toString();
        }
    }
    /** Native UI lifecycle only. Closing/backgrounding during a remote probe fences its late decision. */
    public final class OwnerWindow implements AutoCloseable {
        private final NativeSkillExecutionHost owner=NativeSkillExecutionHost.this;
        private boolean foreground,ended;
        public synchronized void setForeground(boolean value){if(!ended)foreground=value;}
        private synchronized void admit(Decision action)throws Exception {open();if(ended||!foreground)throw new SecurityException("原审阅页面已离开前台，请重新打开原任务");action.run();}
        @Override public synchronized void close(){foreground=false;ended=true;}
    }
    @FunctionalInterface private interface Decision {void run()throws Exception;}
    public OwnerWindow openOwnerWindow(){open();return new OwnerWindow();}
    /** One exact native owner choice. Persisted JSON is audit only and cannot rebuild this value. */
    public final class OwnerInvocation implements AutoCloseable {
        private final OwnerWindow window;private final String task,pin,input,nativeScope;private final SkillBook.OwnerExecutionSelection source;private final SkillExecutionConfiguration.Selection selected;
        private final long expiresAt=System.currentTimeMillis()+120_000L;
        private final java.util.concurrent.atomic.AtomicInteger phase=new java.util.concurrent.atomic.AtomicInteger();
        private volatile RunControl original;
        private OwnerInvocation(OwnerWindow window,String task,String pin,JSONObject input,SkillBook.OwnerExecutionSelection source,SkillExecutionConfiguration.Selection selected,JSONObject nativeScope){this.window=window;this.task=task;this.pin=pin;this.input=input.toString();this.source=source;this.selected=selected;this.nativeScope=nativeScope.toString();}
        public String taskId(){return task;}
        public JSONObject input()throws Exception{return new JSONObject(input);}
        public JSONObject view()throws Exception{return new JSONObject().put("taskId",task).put("input",input()).put("skillScopeFingerprint",SkillSourcePolicy.scopeFingerprint(new JSONObject(nativeScope))).put("expiresAt",expiresAt).put("invocationOrigin","owner_selection").put("executionAuthorized",false).put("replayAllowed",false);}
        private void requireProject()throws Exception{JSONObject scope=new JSONObject(nativeScope);skills.requireTaskScopeForHost(tasks.get(task,false),scope);requireExecutionProject(scope,selected);}
        public void checkBeforeStart(NativeSkillExecutionHost expected)throws Exception {
            if(expected!=NativeSkillExecutionHost.this||phase.get()!=0)throw new SecurityException("主人选择已经使用或不属于原执行宿主");
            open();window.admit(()->{});requireProject();source.checkCurrent();configuration.check(selected);if(System.currentTimeMillis()>=expiresAt)throw new SecurityException("主人选择已过期，请重新核对");
        }
        /** Original TaskStore atomic start only; no Book or backend call while its monitor is held. */
        public void admitOriginalTask(TaskStore actual,JSONObject current,RunControl control)throws Exception {
            if(actual!=tasks||!task.equals(current.optString("id"))||!pin.equals(ownerTaskPin(current))||!ownerTaskEligible(current)||control==null||control.hasHostRun()||control.isCancelled()||control.isPaused()||System.currentTimeMillis()>=expiresAt)throw new SecurityException("原任务或主人选择已变化，请重新核对，不恢复旧请求");
            window.admit(()->{if(!phase.compareAndSet(0,1))throw new SecurityException("主人选择仅能发起一次");original=control;});
        }
        public void bindOriginalRun(TaskStore actual,JSONObject started,RunControl control)throws Exception {
            if(actual!=tasks||phase.get()!=1||original!=control||!task.equals(started.optString("id"))||!control.matchesHostRun(task,started.getString("runId"),started.getInt("coordinatorEpoch"))||ownerInvocations.putIfAbsent(control,this)!=null)throw new SecurityException("主人选择未绑定原任务执行代次");
        }
        public void requireRun(String taskId,RunControl control)throws Exception {
            if(!task.equals(taskId)||original!=control||phase.get()!=1||ownerInvocations.get(control)!=this||System.currentTimeMillis()>=expiresAt)throw new SecurityException("原主人选择不再有效");
            control.check();if(control.isPaused())throw new SecurityException("原任务已暂停");requireProject();source.checkCurrent();configuration.check(selected);
        }
        private void claim(String taskId,String run,int epoch,JSONObject requested,RunControl control)throws Exception {
            if(!task.equals(taskId)||original!=control||!control.matchesHostRun(taskId,run,epoch)||System.currentTimeMillis()>=expiresAt)throw new SecurityException("主人请求与原run不同");
            JSONObject actual=new JSONObject(requested.toString());actual.remove("_hostOperationId");
            if(!TaskStore.capabilityFingerprint(input()).equals(TaskStore.capabilityFingerprint(actual))||!phase.compareAndSet(1,2))throw new SecurityException("请求不是主人一次性选择的准确脚本和参数");
            requireProject();source.checkCurrent();configuration.check(selected);
        }
        @Override public void close(){phase.set(3);RunControl retained=original;if(retained!=null)ownerInvocations.remove(retained,this);}
    }
    /** Explicit foreground owner selection only; generic model/feature JSON has no such command. */
    public OwnerInvocation selectForOwner(OwnerWindow window,String taskId,String observedTaskPin,JSONObject input,long generation,Set<String> configuredCapabilities)throws Exception {
        open();if(window==null||window.owner!=this)throw new SecurityException("没有本机主人选择页面");JSONObject task=tasks.get(taskId,false);
        if(task==null||!ownerTaskEligible(task)||!ownerTaskPin(task).equals(observedTaskPin)||tasks.hasUnresolved(taskId))throw new SecurityException("原任务正在执行、已停止或有待核对效果，请返回原任务处理");
        Set<String> fields=Set.of("id","revision","digest","scriptPath","arguments","timeoutSeconds","maxOutputBytes");for(var keys=input.keys();keys.hasNext();)if(!fields.contains(keys.next()))throw new IllegalArgumentException("主人选择不能携带替代权限或执行端");
        JSONObject exact=new JSONObject(input.toString());if(!exact.has("arguments"))exact.put("arguments",new JSONArray());if(!exact.has("timeoutSeconds"))exact.put("timeoutSeconds",30);if(!exact.has("maxOutputBytes"))exact.put("maxOutputBytes",16384);
        SkillExecutionConfiguration.Selection selected=configuration.current();selected.request(exact.getString("scriptPath"),strings(exact.getJSONArray("arguments")),exact.getInt("timeoutSeconds"),exact.getInt("maxOutputBytes"));
        JSONObject nativeScope=skills.taskScopeForHost(task);
        requireExecutionProject(nativeScope,selected);
        SkillBook.OwnerExecutionSelection source=skills.selectExecutionForOwner(nativeScope,exact.getString("id"),exact.getInt("revision"),exact.getString("digest"),generation,configuredCapabilities);
        source.read(exact.getString("scriptPath"),0,1,System.currentTimeMillis());final OwnerInvocation[] result={null};
        skills.requireTaskScopeForHost(tasks.get(taskId,false),nativeScope);
        window.admit(()->result[0]=new OwnerInvocation(window,taskId,observedTaskPin,exact,source,selected,nativeScope));return result[0];
    }
    private static boolean ownerTaskEligible(JSONObject task){return "owner".equals(task.optString("actorId"))&&"private".equals(task.optString("spaceId"))&&"assistant".equals(task.optString("kind"))&&!task.has("origin")&&!task.has("workProfileRef")&&!task.has("workProfileIntent")&&!task.has("sourceLearningJob")&&!task.optBoolean("cancelRequested")&&task.optString("status").matches("queued|interrupted|needs_confirmation|awaiting_user|failed");}
    private static String ownerTaskPin(JSONObject task)throws Exception {return TaskStore.capabilityFingerprint(new JSONObject().put("id",task.getString("id")).put("runId",task.optString("runId")).put("epoch",task.optInt("coordinatorEpoch")).put("status",task.optString("status")).put("updatedAt",task.optLong("updatedAt")).put("goal",task.optString("goal")).put("feedback",task.optString("feedback")).put("developmentContext",task.optJSONObject("developmentContext")==null?JSONObject.NULL:task.getJSONObject("developmentContext")).put("executionScope",task.optJSONObject("executionScope")==null?JSONObject.NULL:task.getJSONObject("executionScope")));}
    public JSONObject configuration()throws Exception {open();return configuration.snapshot();}
    /** Same-task native preparation, lexical candidates only; configuration is not execution authority. */
    public JSONObject taskSnapshot(String taskId,Set<String> configuredCapabilities,JSONObject pluginSnapshot)throws Exception {
        open();JSONObject task=tasks.get(taskId,false);if(task==null||!"owner".equals(task.optString("actorId"))||!"private".equals(task.optString("spaceId")))throw new SecurityException("只能准备本机主人私人任务的能力");
        String query=(task.optString("goal")+"\n"+task.optString("feedback")).replaceAll("[\\p{Cntrl}&&[^\\n\\r\\t]]","");int end=Math.min(512,query.length());if(end>0&&end<query.length()&&Character.isHighSurrogate(query.charAt(end-1)))end--;
        JSONObject nativeScope=null;
        try{nativeScope=skills.taskScopeForHost(task);}catch(Exception unavailable){/* Original receipts stay readable; no fallback catalogue is exposed. */}
        JSONObject diagnosed=nativeScope==null?new JSONObject().put("skills",new JSONArray()):skills.diagnoseDiscovery(nativeScope,query.substring(0,end),configuredCapabilities);
        JSONArray related=new JSONArray(),needs=new JSONArray(),plugins=pluginSnapshot.optJSONArray("plugins"),executions=new JSONArray(),ownerSkills=new JSONArray();Set<String> seen=new java.util.HashSet<>();boolean scriptsRelevant=false;
        JSONArray all=diagnosed.getJSONArray("skills");for(int i=0;i<all.length();i++){JSONObject skill=all.getJSONObject(i);if(!skill.optBoolean("applicableToScope")||!skill.optBoolean("active")||!SkillSourcePolicy.ownerInvocable(skill))continue;JSONArray scripts=new JSONArray(),resources=skill.optJSONArray("resources");for(int j=0;resources!=null&&j<resources.length();j++)if(resources.getString(j).startsWith("scripts/"))scripts.put(resources.getString(j));if(scripts.length()>0)ownerSkills.put(new JSONObject().put("id",skill.getString("id")).put("name",skill.getString("name")).put("revision",skill.getInt("revision")).put("digest",skill.getString("digest")).put("executionGeneration",skill.optLong("executionGeneration",0)).put("sourceVersion",skill.getString("sourceVersion")).put("sourcePolicy",skill.getJSONObject("sourcePolicy")).put("skillScopeFingerprint",skill.getString("skillScopeFingerprint")).put("scripts",scripts));}
        scriptsRelevant=ownerSkills.length()>0;
        JSONArray rows=diagnosed.getJSONArray("skills");for(int i=0;i<rows.length()&&related.length()<8;i++){JSONObject skill=rows.getJSONObject(i);if(!skill.optBoolean("applicableToScope")||skill.optInt("score")<=0)continue;related.put(skill);
            JSONArray resources=skill.optJSONArray("resources");for(int j=0;resources!=null&&j<resources.length();j++)if(resources.optString(j).startsWith("scripts/"))scriptsRelevant=true;
            JSONArray required=skill.optJSONArray("requiredCapabilities");for(int j=0;required!=null&&j<required.length();j++){String capability=required.getString(j);if(!capability.startsWith("plugin:")||!seen.add(capability))continue;
                String[] parts=capability.split(":",3);if(parts.length!=3)continue;JSONObject need=new JSONObject().put("capability",capability).put("pluginId",parts[1]).put("tool",parts[2]).put("available",configuredCapabilities.contains(capability));
                for(int k=0;plugins!=null&&k<plugins.length();k++){JSONObject plugin=plugins.getJSONObject(k);if(parts[1].equals(plugin.optString("id")))need.put("installation",plugin);}needs.put(need);
            }
        }
        JSONArray operations=task.getJSONArray("operations");for(int i=0;i<operations.length();i++){JSONObject row=operations.getJSONObject(i);if("skills_execute".equals(row.optString("tool"))&&row.getJSONObject("arguments").has("nativeSkill")){
            JSONObject original=new JSONObject(row.getJSONObject("arguments").getJSONObject("nativeSkill").toString());if(row.optJSONObject("receipt")!=null)original.put("receipt",row.getJSONObject("receipt"));
            executions.put(row.put("artifactAvailability",SkillExecutionRecords.artifactAvailability(original)));
        }}
        JSONObject toolInventory;
        try{toolInventory=tasks.ownerToolInventory(taskId);}
        catch(Exception unavailable){toolInventory=new JSONObject().put("state","unavailable").put("executionAuthorized",false).put("message","原任务工具记录暂不可核对；下方原脚本回执仍可查看。");}
        JSONObject configState=configuration.snapshot();
        boolean configurationApplicable=true;
        if(nativeScope!=null&&configState.optBoolean("configured"))try{requireExecutionProject(nativeScope,configuration.current());}catch(Exception different){configurationApplicable=false;}
        return new JSONObject().put("taskId",taskId).put("goal",task.optString("goal")).put("configuration",configState).put("toolInventory",toolInventory).put("skills",related).put("pluginNeeds",needs)
                .put("executions",executions).put("reviewPending",pendingReview(taskId)!=null).put("scriptPreparationRelevant",scriptsRelevant||pendingReview(taskId)!=null||executions.length()>0||configState.optBoolean("recoveryRequired")).put("matching","lexical-v1").put("phoneExecution",phonePreparation(nativeScope)).put("executionAuthorized",false).put("businessVerified",false)
                .put("ownerInvocation",new JSONObject().put("available",nativeScope!=null&&configurationApplicable&&ownerTaskEligible(task)&&!tasks.hasUnresolved(taskId)).put("projectScopeAvailable",nativeScope!=null).put("projectConfigurationCompatible",configurationApplicable).put("skillScopeFingerprint",nativeScope==null?"":SkillSourcePolicy.scopeFingerprint(nativeScope)).put("projectId",nativeScope==null?"":nativeScope.optString("projectId")).put("taskPin",ownerTaskPin(task)).put("skills",ownerSkills).put("executionAuthorized",false));
    }
    private JSONObject phonePreparation(JSONObject nativeScope)throws Exception {
        if(local==null)return new JSONObject().put("available",false).put("message","原手机托管环境未接入");
        if(nativeScope==null)return new JSONObject().put("available",false).put("message","原任务项目暂不能核对，未提供其它项目的执行准备");
        JSONObject value=new JSONObject().put("executionLocation","this_phone").put("contract",ManagedSkillPolicy.CONTRACT).put("executionAuthorized",false);
        try{JSONObject actual=local.managedSkillRuntimeForHost().describe();JSONArray checkouts=local.managedSkillCheckoutsForHost(),eligible=new JSONArray();for(int i=0;i<checkouts.length();i++){JSONObject checkout=checkouts.getJSONObject(i);if(!nativeScope.has("projectId")||nativeScope.getString("projectId").equals(checkout.getString("projectId"))&&nativeScope.getString("checkoutId").equals(checkout.getString("checkoutId")))eligible.put(checkout);}value.put("runtime",actual).put("checkouts",eligible).put("available",actual.optBoolean("canExecute"));}
        catch(Exception unavailable){value.put("available",false).put("message","手机工具环境暂不可用，请回项目页核对原工具准备状态；未换到电脑执行");}return value;
    }
    public SkillExecutionConfiguration.Review inspectConfiguration(String text)throws Exception {open();return configuration.inspect(text);}
    /** Owner-selected native values only; this does not prepare tools, create a
     * task or authorize a script. Every concrete run still presents its Plan. */
    public SkillExecutionConfiguration.Review inspectPhoneConfiguration(String taskId,JSONObject selected)throws Exception {
        open();JSONObject task=tasks.get(taskId,false);if(task==null||!"owner".equals(task.optString("actorId"))||!"private".equals(task.optString("spaceId"))||task.has("origin"))throw new SecurityException("请在原本机私人任务选择手机Skill执行");
        JSONObject scope=skills.taskScopeForHost(task);var review=configuration.inspectPhone(selected);requirePhoneProject(scope,review.view().getJSONObject("configuration"));checkPhoneConfiguration(review.view());skills.requireTaskScopeForHost(tasks.get(taskId,false),scope);return review;
    }
    private static void requirePhoneProject(JSONObject nativeScope,JSONObject phone)throws Exception {
        String checkout=phone.getString("checkoutId");
        if(nativeScope.has("projectId")&&!checkout.isEmpty()&&!checkout.equals(nativeScope.getString("checkoutId")))throw new SecurityException("手机执行配置使用了另一项目副本，请按原任务项目重新审阅；没有读取或执行另一项目");
    }
    private static void requireExecutionProject(JSONObject nativeScope,SkillExecutionConfiguration.Selection selected)throws Exception {if(selected.phone())requirePhoneProject(nativeScope,selected.phoneDeployment.configuration());}
    private void checkPhoneConfiguration(JSONObject view)throws Exception {
        if(local==null)throw new SkillExecution.Unavailable("当前应用未连接原手机托管运行环境");JSONObject q=view.getJSONObject("configuration");
        local.managedSkillRuntimeForHost().skillToolsForHost(q.getString("runtimeId"));
        RunControl observation=new RunControl();ManagedSkillInputs.capture(local,q.getString("checkoutId"),ManagedSkillPolicy.strings(q.getJSONArray("readPaths")),observation);
    }
    private void checkSelectedConfiguration(JSONObject view)throws Exception {if("this_phone".equals(view.optString("executionLocation")))checkPhoneConfiguration(view);else checkProfile(view.getJSONObject("deployment"));}
    private NativeSkillBackend backend(SkillExecutionConfiguration.Selection selected,String operation,RunControl original)throws Exception {
        if(selected.phone()){if(local==null)throw new SkillExecution.Unavailable("原手机托管运行环境不可用");return new ManagedSkillBackend(android,local,tasks,selected.phoneDeployment,operation,original,false,this::publicationChanged,callbackTransport);}
        return new CompanionSkillBackend(remote,selected.deployment,System::currentTimeMillis);
    }
    private NativeSkillBackend receiptBackend(JSONObject record,CompanionSkillBackend.ReceiptRef reference)throws Exception {
        JSONObject recorded=record.getJSONObject("deployment");
        if(ManagedSkillBackend.BACKEND.equals(recorded.optString("backendId"))){if(local==null)throw new SkillExecution.Unavailable("原手机托管运行环境不可用");return new ManagedSkillBackend(android,local,tasks,ManagedSkillBackend.savedDeployment(recorded),reference.operationId,new RunControl(),true);}
        return new CompanionSkillBackend(remote,SkillExecutionRecords.deployment(recorded),System::currentTimeMillis);
    }
    public JSONObject installConfiguration(OwnerWindow window,SkillExecutionConfiguration.Review review,String digest)throws Exception {
        open();JSONObject view=review.view();checkSelectedConfiguration(view);if(window==null||window.owner!=this)throw new SecurityException("没有本机配置审阅页面");window.admit(()->configuration.install(review,digest));invalidatePending();return configuration.snapshot();
    }
    @Deprecated public JSONObject removeConfiguration(OwnerWindow window,long expectedRevision)throws Exception {throw new SecurityException("请重新读取原执行配置，停用需带原观察状态指纹");}
    public JSONObject removeConfiguration(OwnerWindow window,long expectedRevision,String observedStateFingerprint)throws Exception {open();if(window==null||window.owner!=this)throw new SecurityException("没有本机配置审阅页面");window.admit(()->configuration.disable(expectedRevision,observedStateFingerprint));invalidatePending();return configuration.snapshot();}
    private void invalidatePending(){for(Review review:pending.values())synchronized(review.decision){review.approved=false;review.decision.notifyAll();if(review.dispatched)try{tasks.cancelSkillExecution(CompanionSkillBackend.ReceiptRef.from(review.plan,review.grant));review.backend.requestCancel(CompanionSkillBackend.ReceiptRef.from(review.plan,review.grant));}catch(Exception unknown){/* Durable original remains query-only. */}}}
    private void open(){if(closed)throw new IllegalStateException("脚本执行宿主已关闭");}
    private void publicationChanged(String task){Review review=pending.get(task);if(review!=null)review.publicationRevision.incrementAndGet();changed.accept(task);}
    /** UI invalidation hint only, not a publication fact or a recovered grant.
     * The subsequent taskSnapshot still reads/rechecks the original private task. */
    public long privateArtifactRevision(String task){Review review=pending.get(task);return review==null?0:review.publicationRevision.get();}
    /** Original private task and durable publication only. This cannot import missing/deleted
     * bytes, revive its original control or obtain a fresh publication slot. */
    public byte[] readPublishedArtifact(String task,String operation,String attachment,String sha)throws Exception {
        open();JSONArray publications=tasks.scriptArtifactPublicationsForOwner(task,operation);JSONObject selected=null;
        for(int i=0;i<publications.length();i++){JSONObject row=publications.getJSONObject(i);if("published".equals(row.optString("state"))&&attachment.equals(row.optString("attachmentId"))&&sha.equals(row.optString("sha256")))selected=row;}
        if(selected==null)throw new SecurityException("原任务没有这个已确认私有成果");RunControl observation=new RunControl();
        byte[] bytes=dev.pocketengine.features.attachments.AttachmentRepository.get(android).book.readVerifiedSnapshot(attachment,sha,observation);
        boolean current=false;JSONArray after=tasks.scriptArtifactPublicationsForOwner(task,operation);for(int i=0;i<after.length();i++){JSONObject row=after.getJSONObject(i);if("published".equals(row.optString("state"))&&attachment.equals(row.optString("attachmentId"))&&sha.equals(row.optString("sha256"))&&bytes.length==row.optLong("bytes",-1))current=true;}
        if(!current){java.util.Arrays.fill(bytes,(byte)0);throw new SecurityException("原成果引用在读取期间变化");}return bytes;
    }
    private void checkProfile(JSONObject deployment)throws Exception {
        JSONArray profiles=remote.snapshot().getJSONArray("profiles");for(int i=0;i<profiles.length();i++){JSONObject p=profiles.getJSONObject(i);
            if(deployment.getString("profileId").equals(p.optString("id"))&&deployment.getString("serviceBase").equals(p.optString("baseUrl").replaceAll("/+$","")))return;
        }throw new SecurityException("原电脑连接不存在或地址已变化，请先核对已有连接");
    }
    /** Native Runner capability only: input has no owner/scope/backend pins or task permission fields. */
    public JSONObject execute(String task,String run,int epoch,JSONObject input,Set<String> capabilities,RunControl original)throws Exception {
        return execute(task,run,epoch,input,capabilities,original,()->{});
    }
    /** The actual native port retains its current dependency check through review and dispatch. */
    public JSONObject execute(String task,String run,int epoch,JSONObject input,Set<String> capabilities,RunControl original,dev.pocketengine.core.CapabilitySnapshot.CurrentCheck capabilityCurrent)throws Exception {
        return execute(task,run,epoch,input,capabilities,original,capabilityCurrent,null);
    }
    public JSONObject execute(String task,String run,int epoch,JSONObject input,Set<String> capabilities,RunControl original,dev.pocketengine.core.CapabilitySnapshot.CurrentCheck capabilityCurrent,dev.pocketengine.features.controlplane.WorkProfileToolSelection.PreparedTool profile)throws Exception {
        if(profile!=null)profile.requireSkillExecution(task,run,epoch,input,original);
        java.util.Objects.requireNonNull(capabilityCurrent).check();
        open();live.check(task,run,epoch,original);String operation=input.getString("_hostOperationId");
        SkillExecutionConfiguration.Selection selected;
        try{selected=configuration.current();}catch(SkillExecution.Unavailable missing){return rejected(operation,"needs_user","请选择手机托管执行或电脑隔离执行，并在原任务准备；没有运行脚本");}
        JSONObject nativeScope=skills.taskScopeForHost(tasks.get(task,false));
        JSONObject assetScope=profile==null?nativeScope:profile.skillScopeForHost(nativeScope);
        try{requireExecutionProject(nativeScope,selected);}catch(SecurityException different){return rejected(operation,"needs_user",different.getMessage());}
        NativeSkillBackend backend=backend(selected,operation,original);Review review=null;OwnerInvocation ownerChoice=ownerInvocations.remove(original);
        try {
            // Keep the original package/approval/source selection through the managed
            // worker and artifact callback gates, not just until the first dispatch.
            dev.pocketengine.core.CapabilitySnapshot.CurrentCheck source;
            if(ownerChoice!=null){ownerChoice.claim(task,run,epoch,input,original);source=()->{skills.requireTaskScopeForHost(tasks.get(task,false),nativeScope);requireExecutionProject(nativeScope,selected);ownerChoice.source.checkCurrent();};}
            else {SkillBook.ReferenceSelection modelSource=skills.selectReferenceForHost(assetScope,input.getString("id"),input.getInt("revision"),input.getString("digest"),capabilities);source=()->{skills.requireTaskScopeForHost(tasks.get(task,false),nativeScope);requireExecutionProject(nativeScope,selected);if(profile!=null)profile.checkSkillExecutionCurrent();modelSource.checkCurrent();};}
            CompanionSkillHostPort port=new CompanionSkillHostPort(new CompanionSkillHostPort.Store(){
                public void checkCurrent(SkillExecution.Plan plan,SkillExecution.Grant grant,RunControl control)throws Exception {open();source.check();configuration.check(selected);capabilityCurrent.check();live.check(task,run,epoch,control);tasks.checkSkillExecution(plan,grant,control);}
                public boolean claimFresh(SkillExecution.Plan plan,SkillExecution.Grant grant,CompanionSkillBackend.ReceiptRef ref,RunControl control)throws Exception {checkCurrent(plan,grant,control);return tasks.claimSkillExecution(plan,grant,ref,control);}
                public void record(CompanionSkillBackend.ReceiptRef ref,SkillExecution.Receipt receipt)throws Exception {tasks.recordSkillExecution(ref,receipt);changed.accept(ref.taskId);}
                public void checkReceiptReader(CompanionSkillBackend.ReceiptRef ref)throws Exception {tasks.readSkillExecution(ref.taskId,ref.operationId);}
                public void cancelRequested(CompanionSkillBackend.ReceiptRef ref)throws Exception {tasks.cancelSkillExecution(ref);changed.accept(ref.taskId);}
            },backend);
            SkillExecution execution=ownerChoice==null?skills.executionForHost(backend,port):skills.executionForOwner(backend,port,ownerChoice.source);
            SkillExecution.Request request=selected.request(input.getString("scriptPath"),strings(input.optJSONArray("arguments")),input.optInt("timeoutSeconds",30),input.optInt("maxOutputBytes",16384));
            SkillExecution.Run identity=new SkillExecution.Run(assetScope,task,run,epoch,capabilities);
            SkillExecution.Plan plan=execution.prepare(identity,input.getString("id"),input.getInt("revision"),input.getString("digest"),request,original);
            SkillExecution.Grant grant=new SkillExecution.Grant(operation,UUID.randomUUID().toString(),selected.backendGrantId,plan.digest,task,run,epoch,SkillExecution.environmentBinding(plan),plan.expiresAt);
            configuration.check(selected);live.check(task,run,epoch,original);tasks.reserveSkillExecution(plan,grant,selected.recordedDeployment(),original);
            review=new Review(plan,grant,selected,original,execution,backend,capabilities,capabilityCurrent,ownerChoice==null?null:ownerChoice.source,nativeScope,assetScope);if(pending.putIfAbsent(task,review)!=null)throw new SecurityException("原任务已有具体脚本审阅，不能开第二执行器");changed.accept(task);
            while(true){
                original.check();source.check();configuration.check(selected);capabilityCurrent.check();live.check(task,run,epoch,original);
                synchronized(review.decision){if(review.approved!=null)break;long remaining=plan.expiresAt-System.currentTimeMillis();if(remaining<=0)break;review.decision.wait(Math.min(250,remaining));}
            }
            if(!Boolean.TRUE.equals(review.approved)){
                tasks.recordSkillExecution(CompanionSkillBackend.ReceiptRef.from(plan,grant),new SkillExecution.Receipt(operation,"",SkillExecution.Status.REJECTED,null,null,false));
                return rejected(operation,"needs_user","本次脚本未获批准或审阅已过期，没有运行；可返回原任务重新判断需要的能力");
            }
            synchronized(review.decision){review.dispatched=true;}
            JSONObject receipt=execution.dispatch(plan,grant,original);long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(request.timeoutSeconds+10L);
            while(receipt.optString("status").matches("accepted|running")&&System.nanoTime()<until){original.sleep(400);source.check();capabilityCurrent.check();receipt=execution.reconcile(plan,grant);}
            var ref=CompanionSkillBackend.ReceiptRef.from(plan,grant);
            if(receipt.optString("status").matches("accepted|running")){tasks.recordSkillExecution(ref,SkillExecution.Receipt.unknown(operation));receipt=SkillExecutionRecords.receipt(ref,SkillExecution.Receipt.unknown(operation));}
            boolean artifactReadFailed=false;
            if(receipt.optString("status").matches("completed|failed|timed_out"))try{tasks.saveSkillArtifacts(ref,backend.artifacts(ref));}catch(Exception absent){artifactReadFailed=true;}
            JSONObject originalRecord=tasks.readSkillExecution(task,operation),saved=originalRecord.optJSONObject("receipt");if(saved!=null)receipt=saved;
            JSONObject availability=SkillExecutionRecords.artifactAvailability(originalRecord);
            receipt.put("artifactStatus",artifactReadFailed?"unavailable":availability.getString("state"));
            boolean resultsPending=availability.getInt("requestedCount")>0&&!availability.getString("state").equals("available");
            return SkillExecutionRecords.forModel(receipt.put("ok",receipt.optString("status").equals("completed")).put("code",receipt.optString("status").equals("unknown")?"skill_effect_unknown":"skill_receipt")
                    .put("requiresPause",!receipt.optString("status").equals("completed")||resultsPending),request.maxResultChars);
        } catch(Exception failure){
            if(review!=null){var ref=CompanionSkillBackend.ReceiptRef.from(review.plan,review.grant);boolean dispatched=tasks.readSkillExecution(task,operation).has("claimedAt");
                if(dispatched){
                    tasks.cancelSkillExecution(ref);tasks.recordSkillExecution(ref,backend.cancelOriginal(ref));
                    JSONObject actual=tasks.readSkillExecution(task,operation).getJSONObject("receipt");
                    return SkillExecutionRecords.forModel(actual.put("ok",false).put("code",actual.optString("status").equals("unknown")?"skill_effect_unknown":"skill_receipt").put("requiresPause",true),review.selected.view().getJSONObject("request").getInt("maxResultChars"));
                }
                tasks.recordSkillExecution(ref,new SkillExecution.Receipt(operation,"",SkillExecution.Status.REJECTED,null,null,false));
            }
            if(failure instanceof InterruptedException)throw failure;
            return rejected(operation,"needs_user",failure instanceof SkillExecution.Unavailable?failure.getMessage():"脚本包、执行端或原任务状态已变化，请重新核对；没有再次派发");
        } finally {if(review!=null){synchronized(review.decision){review.finished=true;review.decision.notifyAll();}pending.remove(task,review);}backend.close();changed.accept(task);}
    }
    public Review pendingReview(String taskId){open();Review review=pending.get(taskId);if(review==null)return null;synchronized(review.decision){return review.finished||review.approved!=null||review.dispatched?null:review;}}
    public String validateReview(Review review)throws Exception {
        requireReview(review);JSONObject scope=new JSONObject(review.nativeScope);skills.requireTaskScopeForHost(tasks.get(review.grant.taskId,false),scope);requireExecutionProject(scope,review.selected);configuration.check(review.selected);review.capabilityCurrent.check();live.check(review.grant.taskId,review.grant.runId,review.grant.epoch,review.control);
        return review.execution.validateForReview(review.plan,review.control);
    }
    public void decide(OwnerWindow window,Review review,String displayedDigest,boolean approved)throws Exception {
        if(window==null||window.owner!=this)throw new SecurityException("没有本机原生审阅页面");
        requireReview(review);if(!review.plan.digest.equals(displayedDigest))throw new SecurityException("显示的计划已变化");if(approved)validateReview(review);
        window.admit(()->decideKnown(review,approved));changed.accept(review.grant.taskId);
    }
    private void decideKnown(Review review,boolean approved)throws Exception {
        synchronized(review.decision){requireReview(review);configuration.check(review.selected);live.check(review.grant.taskId,review.grant.runId,review.grant.epoch,review.control);
            if(review.approved!=null||review.dispatched)throw new SecurityException("原脚本审阅已经处理");tasks.decideSkillExecution(review.plan,review.grant,review.control,approved);review.approved=approved;review.decision.notifyAll();}
    }
    public JSONObject readScript(Review review)throws Exception {
        validateReview(review);JSONObject p=review.plan.review().getJSONObject("package"),scope=new JSONObject(review.assetScope);String path=review.plan.review().getJSONObject("request").getString("scriptPath");
        StringBuilder text=new StringBuilder();int offset=0;JSONObject page;
        do{page=review.ownerSelection==null?skills.readForHost(scope,p.getString("id"),path,offset,12000,p.getInt("revision"),p.getString("digest"),review.capabilities):review.ownerSelection.read(path,offset,12000,System.currentTimeMillis());text.append(page.getString("content"));int next=page.getInt("nextOffset");
            if(text.length()>64000||page.getBoolean("hasMore")&&next<=offset)throw new SecurityException("当前脚本超过可审阅范围或分页已变化");offset=next;
        }while(page.getBoolean("hasMore"));
        validateReview(review);return page.put("content",text.toString()).put("hasMore",false).put("previewTruncated",false);
    }
    public void cancelReview(Review review)throws Exception {
        requireReview(review);synchronized(review.decision){if(!review.dispatched){decideKnown(review,false);return;}}
        var ref=CompanionSkillBackend.ReceiptRef.from(review.plan,review.grant);tasks.cancelSkillExecution(ref);review.backend.requestCancel(ref);changed.accept(ref.taskId);
    }
    private void requireReview(Review review){open();if(review==null||pending.get(review.grant.taskId)!=review||review.finished)throw new SecurityException("原生脚本审阅已经结束，不能重建或沿用旧批准");}
    /** Retrieves only the original approved output bytes. Works after package/config removal; never submits a job. */
    public JSONObject retrieveArtifacts(String taskId,String operationId)throws Exception {
        open();JSONObject record=tasks.readSkillExecution(taskId,operationId);var ref=SkillExecutionRecords.reference(record.getJSONObject("reference"));
        if(!taskId.equals(ref.taskId)||!operationId.equals(ref.operationId))throw new SecurityException("成果归属不是原任务操作");
        if(!SkillExecutionRecords.artifactAvailability(record).getBoolean("canRecheck"))throw new IllegalStateException("原脚本尚无可取回成果的终态回执，请先核对原回执；不会重跑脚本");
        try(NativeSkillBackend backend=receiptBackend(record,ref)){
            tasks.saveSkillArtifacts(ref,backend.artifacts(ref));
        }catch(Exception unavailable){throw new IllegalStateException("原成果尚未完整取回，请核对原执行地点和固定文件后重试；已保存成果保留，没有重新运行脚本",unavailable);}
        changed.accept(taskId);JSONObject actual=tasks.readSkillExecution(taskId,operationId);
        return actual.put("artifactAvailability",SkillExecutionRecords.artifactAvailability(actual)).put("artifactRetrieval","retrieved").put("replayAllowed",false);
    }
    /** Cursor pages from the existing managed output.bin, not a log mirror. */
    public JSONObject readExecutionLog(String taskId,String operationId,long cursor,int maxBytes)throws Exception {
        open();JSONObject record=tasks.readSkillExecution(taskId,operationId);var ref=SkillExecutionRecords.reference(record.getJSONObject("reference"));
        try(NativeSkillBackend backend=receiptBackend(record,ref)){
            if(backend instanceof ManagedSkillBackend)return ((ManagedSkillBackend)backend).readLog(ref,cursor,maxBytes);
            return new JSONObject().put("status","unsupported").put("message","原电脑执行端仅提供原回执中的有界输出；未声明日志游标接口").put("executionLocation","companion").put("replayAllowed",false);
        }
    }
    /** Same original task/ref/pinned deployment; neither a new Plan nor a fresh submission is created. */
    public JSONObject reconcile(String taskId,String operationId,boolean cancel)throws Exception {
        open();JSONObject record=tasks.readSkillExecution(taskId,operationId);var ref=SkillExecutionRecords.reference(record.getJSONObject("reference"));
        if(!record.has("claimedAt")){
            if(pending.containsKey(taskId))return record;
            if(record.optJSONObject("receipt")!=null&&record.getJSONObject("receipt").optString("status").matches("completed|failed|cancelled|timed_out|rejected"))return record;
            tasks.recordSkillExecution(ref,new SkillExecution.Receipt(operationId,"",SkillExecution.Status.REJECTED,null,null,false));return tasks.readSkillExecution(taskId,operationId);
        }
        if(record.optString("operationState").equals("resolved"))return record;
        try(NativeSkillBackend backend=receiptBackend(record,ref)){
            SkillExecution.Receipt receipt;if(cancel||record.optBoolean("cancelPending")){tasks.cancelSkillExecution(ref);receipt=backend.cancelOriginal(ref);}else receipt=backend.query(ref);
            tasks.recordSkillExecution(ref,receipt);if(SkillExecutionRecords.terminal(receipt.status)&&receipt.status!=SkillExecution.Status.CANCELLED)try{tasks.saveSkillArtifacts(ref,backend.artifacts(ref));}catch(Exception missing){/* Missing artifact bytes stay missing, not invented from stdout. */}
            changed.accept(taskId);return tasks.readSkillExecution(taskId,operationId);
        }
    }
    private static JSONObject rejected(String operation,String code,String message)throws Exception {return new JSONObject().put("operationId",operation).put("status","rejected").put("ok",false).put("code",code).put("executed",false).put("businessVerified",false).put("replayAllowed",false).put("message",message);}
    private static List<String> strings(JSONArray value)throws Exception {List<String> result=new ArrayList<>();for(int i=0;value!=null&&i<value.length();i++)result.add(value.getString(i));return result;}
    @Override public void close(){if(closed)return;invalidatePending();closed=true;for(OwnerInvocation choice:ownerInvocations.values())choice.close();ownerInvocations.clear();}
}
