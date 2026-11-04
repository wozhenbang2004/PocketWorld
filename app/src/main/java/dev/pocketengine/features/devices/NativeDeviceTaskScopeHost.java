package dev.pocketengine.features.devices;

import dev.pocketengine.store.TaskStore;
import dev.pocketengine.store.TaskStoreDeviceTasks;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** Native A-owner task range page. Selection records are untrusted choices, never grants.
 * Only live target-signed OutgoingGrant objects and same-channel verified capability snapshots
 * can become options. Opening/reviewing never starts a task, signs a proof or reserves a budget. */
public final class NativeDeviceTaskScopeHost {
    public interface NativeOptionSource {
        Iterable<PairingCoordinator.OutgoingGrant> originalOutgoingGrants()throws Exception;
        /** Actual authenticated device.describe result with capturedAt/validUntil; not a UI/paired list. */
        JSONObject authenticatedCapabilities(String targetDeviceId)throws Exception;
        default dev.pocketengine.features.local.LocalModule localModule(){return null;}
        default dev.pocketengine.features.attachments.AttachmentBook attachmentBook(){return null;}
        default void sendSelectedFile(DeviceIdentity.AuthenticatedPeer peer,java.util.UUID transfer,long offset,byte[] bytes,long deadline,dev.pocketengine.features.devices.transport.DeviceArtifactTransfer.Current current)throws Exception{throw new SecurityException("原文件发送器未安装");}
        default boolean hasMoreOutgoingGrants()throws Exception{return false;}
    }
    public enum ContextChoice { GOAL_ONLY }
    public record ProjectChoice(String artifactId,String sha256,String path) { }
    public record FileChoice(String attachmentId,String sha256,String path,String expectedVersion) { }
    public record ShareChoice(String optionId,int toolBudget,int modelBudget){ }
    public record RouteChoice(String optionId,int toolBudget,int modelBudget,long deadline,
            int localToolShare,int localModelShare,List<ShareChoice> shares,ContextChoice context,List<FileChoice> files,List<ProjectChoice> projects){
        public RouteChoice(String optionId,int toolBudget,int modelBudget,long deadline,int localToolShare,int localModelShare,List<ShareChoice> shares,ContextChoice context,List<FileChoice> files){this(optionId,toolBudget,modelBudget,deadline,localToolShare,localModelShare,shares,context,files,List.of());}
        public RouteChoice(String optionId,int toolBudget,int modelBudget,long deadline,int localToolShare,int localModelShare,List<ShareChoice> shares,ContextChoice context){this(optionId,toolBudget,modelBudget,deadline,localToolShare,localModelShare,shares,context,List.of(),List.of());}
        public RouteChoice {projects=List.copyOf(projects==null?List.of():projects);files=List.copyOf(files==null?List.of():files);shares=shares==null?List.of():java.util.Collections.unmodifiableList(new java.util.ArrayList<>(shares));context=java.util.Objects.requireNonNull(context);}
    }
    public record Selection(List<RouteChoice> routes){public Selection{routes=java.util.Collections.unmodifiableList(new java.util.ArrayList<>(routes));}}
    public static final class ShareOption {
        private final String id,target,resource;
        private ShareOption(String parent,String target,JSONObject binding)throws Exception{this.target=target;resource=DeviceProtocol.canonical(binding);id="share_"+DeviceProtocol.sha256(DeviceProtocol.canonicalBytes(new JSONObject().put("route",parent).put("target",target).put("resource",binding)));}
        public String optionId(){return id;}public String targetDeviceId(){return target;}public JSONObject resourceBinding()throws Exception{return new JSONObject(resource);}
        public JSONObject view()throws Exception{return new JSONObject().put("optionId",id).put("targetDeviceId",target).put("resourceBinding",resourceBinding()).put("executionAuthorized",false);}
    }
    public static final class RouteOption {
        private final String id,key,kind,version,resource;private final PairingCoordinator.OutgoingGrant grant;
        private final Map<String,String> effects;private final List<ShareOption> shares;private final JSONObject diagnostics;
        private RouteOption(PairingCoordinator.OutgoingGrant grant,String kind,String version,JSONObject resource,Map<String,String> effects,JSONObject diagnostic)throws Exception{
            this.grant=grant;this.kind=kind;this.version=version;this.resource=DeviceProtocol.canonical(resource);this.effects=java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(effects));diagnostics=new JSONObject(diagnostic.toString());
            key=sourceRouteKey(grant.targetDeviceId(),resource,kind);id="option_"+DeviceProtocol.sha256(DeviceProtocol.canonicalBytes(new JSONObject().put("key",key).put("grantId",grant.grantId())));
            ArrayList<ShareOption> downstream=new ArrayList<>();JSONObject forward=grant.grant().optJSONObject("forwardingScope");
            if(kind.equals("goal")&&forward!=null){JSONArray pairs=forward.optJSONArray("targetResources");if(pairs!=null){if(pairs.length()>128)throw new SecurityException("B的native准确转派候选过多");for(int i=0;i<pairs.length();i++){JSONObject pair=pairs.getJSONObject(i);downstream.add(new ShareOption(id,pair.getString("targetDeviceId"),pair.getJSONObject("resourceBinding")));}}}shares=java.util.Collections.unmodifiableList(new java.util.ArrayList<>(downstream));
        }
        public String optionId(){return id;}public String sourceKey(){return key;}public String kind(){return kind;}public String capabilityVersion(){return version;}public PairingCoordinator.OutgoingGrant receivingGrant(){return grant;}
        public JSONObject resourceBinding()throws Exception{return new JSONObject(resource);}public Map<String,String> toolEffects(){return effects;}public List<ShareOption> shareOptions(){return shares;}
        public JSONObject view()throws Exception{JSONArray tools=new JSONArray(),children=new JSONArray();for(var entry:effects.entrySet())tools.put(new JSONObject().put("toolName",entry.getKey()).put("effect",entry.getValue()));for(var share:shares)children.put(share.view());JSONObject owner=grant.grant();return new JSONObject().put("optionId",id).put("sourceKey",key).put("targetDeviceId",grant.targetDeviceId()).put("targetInstanceId",grant.targetInstanceId()).put("grantId",grant.grantId()).put("authorizationEpoch",grant.authorizationEpoch()).put("kind",kind).put("capabilityVersion",version).put("resourceBinding",resourceBinding()).put("toolDeclarations",tools).put("toolBudgetMaximum",owner.getLong("toolBudget")).put("modelBudgetMaximum",kind.equals("goal")?owner.getLong("modelBudget"):0).put("deadlineMaximum",grant.deadline()).put("downstreamOptions",children).put("diagnostics",new JSONObject(diagnostics.toString())).put("executionAuthorized",false);}
    }
    /** Resolved native choice; private construction prevents UI JSON from supplying an OutgoingGrant. */
    public static final class RouteSelection {
        private final RouteOption option;private final RouteChoice choice;private final List<ShareOption> shares;private final JSONArray files,projects;private dev.pocketengine.features.attachments.AttachmentBook book;
        private RouteSelection(RouteOption option,RouteChoice choice,List<ShareOption> shares,JSONArray files,JSONArray projects){this.projects=projects;this.files=files;this.option=option;this.choice=choice;this.shares=java.util.Collections.unmodifiableList(new java.util.ArrayList<>(shares));}
        public JSONArray projects()throws Exception{return new JSONArray(projects.toString());}
        public JSONArray files()throws Exception{for(int i=0;i<files.length();i++){JSONObject f=files.getJSONObject(i);if(book==null||!book.outputSourceCurrent(f.getString("id"),f.getString("sha256"),f.getString("generation")))throw new SecurityException("原选材已经撤下或改变");}return new JSONArray(files.toString());}
        public RouteOption option(){return option;}public RouteChoice choice(){return choice;}public List<ShareOption> shares(){return shares;}
    }
    private final TaskStore tasks;private final NativeOptionSource source;private final DeviceTaskCoordinator.NativeHost originalHost;private final DeviceCapabilityPort.VerifiedImages images;private final DeviceCapabilityPort.ReceivedFiles files;
    public NativeDeviceTaskScopeHost(TaskStore originalTasks,NativeOptionSource nativeSource){this(originalTasks,nativeSource,null,null);}
    public NativeDeviceTaskScopeHost(TaskStore originalTasks,NativeOptionSource nativeSource,DeviceTaskCoordinator.NativeHost originalHost){this(originalTasks,nativeSource,originalHost,null);}
    public NativeDeviceTaskScopeHost(TaskStore originalTasks,NativeOptionSource nativeSource,DeviceTaskCoordinator.NativeHost originalHost,DeviceCapabilityPort.VerifiedImages actualImages){this(originalTasks,nativeSource,originalHost,actualImages,null);}
    public NativeDeviceTaskScopeHost(TaskStore originalTasks,NativeOptionSource nativeSource,DeviceTaskCoordinator.NativeHost originalHost,DeviceCapabilityPort.VerifiedImages actualImages,DeviceCapabilityPort.ReceivedFiles receivedFiles){files=receivedFiles;images=actualImages;tasks=java.util.Objects.requireNonNull(originalTasks);source=java.util.Objects.requireNonNull(nativeSource);this.originalHost=originalHost;}
    public dev.pocketengine.core.CapabilityPort actualHostTools(dev.pocketengine.core.RunControl original,DeviceIdentity.LocalIdentity existing,DeviceNativeRoute.Exchange transport)throws Exception{
        var run=java.util.Objects.requireNonNull(original).hostRunIdentity();if(run==null||originalHost==null)throw new SecurityException("原native host或run未安装，不生成另Runner");List<DeviceCapabilityPort.NativeRoute> routes=new ArrayList<>();for(var approval:routeApprovalsForOriginalRun(run.taskId(),original))routes.add(new DeviceNativeRoute(tasks,existing,approval,original,transport,source));return new DeviceCapabilityPort(tasks,new DeviceTaskCoordinator(tasks,originalHost,existing.deviceId(),existing.instanceId()),original,routes,images,files);
    }
    /** B owner's native pairing-range page can offer ONLY these typed live target/resource options. */
    public List<RouteOption> forwardingOptions()throws Exception{return options();}
    public dev.pocketengine.core.CapabilityPort forwardedHostTools(DeviceExecutionAdmission nativeAAdmission,dev.pocketengine.core.RunControl original,DeviceIdentity.LocalIdentity existing,DeviceNativeRoute.Exchange transport)throws Exception{
        var run=java.util.Objects.requireNonNull(original).hostRunIdentity();if(run==null||originalHost==null)throw new SecurityException("没有B原目标native host");tasks.deviceTasks().requireForwardedSource(run.taskId(),nativeAAdmission,original);List<DeviceCapabilityPort.NativeRoute> routes=new ArrayList<>();for(var approval:tasks.deviceTasks().forwardedApprovalsForRun(run.taskId(),nativeAAdmission,original,options()))routes.add(new DeviceNativeRoute(tasks,existing,approval,original,transport,source));return new DeviceCapabilityPort(tasks,new DeviceTaskCoordinator(tasks,originalHost,existing.deviceId(),existing.instanceId()),original,routes,images,files);
    }
    public Window open(String taskId)throws Exception{
        JSONObject scope=tasks.deviceTasks().sourceScopeView(taskId); // Private-owner fence BEFORE catalog access.
        return new Window(taskId,scope,options());
    }
    /** Restore only this original TaskStore's OWNER-CONFIRMED declarations, never a network template.
     * Actual target grant handles remain mandatory. Capability health's 5s diagnostic TTL is not
     * permission lifetime; the receiver still validates exact version/schema/effect before accepting. */
    public List<TaskStoreDeviceTasks.OwnerRouteApproval> routeApprovalsForOriginalRun(String taskId,dev.pocketengine.core.RunControl original)throws Exception{
        var identity=java.util.Objects.requireNonNull(original).hostRunIdentity();if(identity==null||!taskId.equals(identity.taskId()))throw new SecurityException("没有原owner任务控制");JSONObject current=tasks.deviceTasks().sourceScopeView(taskId),selected=current.optJSONObject("selected");if(selected==null)return List.of();ArrayList<RouteOption> restored=new ArrayList<>();ArrayList<PairingCoordinator.OutgoingGrant> grants=new ArrayList<>();for(var grant:source.originalOutgoingGrants())grants.add(grant);
        JSONArray routes=selected.getJSONArray("routes");for(int i=0;i<routes.length();i++){JSONObject route=routes.getJSONObject(i);PairingCoordinator.OutgoingGrant actual=null;for(var candidate:grants)if(candidate.targetDeviceId().equals(route.getString("targetDeviceId"))&&candidate.grantId().equals(route.getString("grantId")))actual=candidate;if(actual==null)throw new SecurityException("原本机没有此目标的真实grant句柄；只显示持久历史，不造peer");Map<String,String> effects=new LinkedHashMap<>();JSONObject saved=route.getJSONObject("toolDeclarations");for(String name:DeviceProtocol.objectKeys(saved))effects.put(name,saved.getString(name));restored.add(new RouteOption(actual,route.getString("kind"),route.getString("capabilityVersion"),route.getJSONObject("resourceBinding"),effects,new JSONObject().put("source","original_owner_confirmed_policy").put("freshHealthAsserted",false)));}
        return tasks.deviceTasks().sourceApprovalsForRun(taskId,identity.runId(),identity.epoch(),original,restored);
    }
    public final class Window implements AutoCloseable {
        private final String taskId,fingerprint;private final Map<String,RouteOption> options;private final JSONArray materials,projectMaterials;private volatile boolean closed;private final Object confirmationGate=new Object();
        private Window(String id,JSONObject state,List<RouteOption> options)throws Exception{materials=DeviceSelectedFileTransfer.catalog(source.attachmentBook());projectMaterials=DeviceSelectedProjectTransfer.catalog(tasks,id,source.localModule());taskId=id;fingerprint=state.optString("ownerScopeFingerprint");LinkedHashMap<String,RouteOption> map=new LinkedHashMap<>();for(RouteOption option:options)if(map.putIfAbsent(option.optionId(),option)!=null)throw new SecurityException("原native路由选项冲突");this.options=java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(map));}
        public String taskId(){return taskId;}
        public void requireCurrent()throws Exception{if(closed||!fingerprint.equals(tasks.deviceTasks().sourceScopeView(taskId).getString("ownerScopeFingerprint")))throw new SecurityException("原主人任务范围/窗口已变化，请重开审阅");}
        public JSONObject view()throws Exception{requireCurrent();JSONArray choices=new JSONArray();for(RouteOption option:options.values()){option.receivingGrant().checkCurrent(System.currentTimeMillis());choices.put(option.view());}return tasks.deviceTasks().sourceScopeView(taskId).put("options",choices).put("hasMoreOptions",source.hasMoreOutgoingGrants()).put("projectOptions",new JSONArray(projectMaterials.toString())).put("pendingProjectImports",tasks.deviceTasks().pendingProjectImportsForOwner(taskId)).put("materialOptions",new JSONArray(materials.toString())).put("maximumFileBytes",262144).put("contextMode","goal_only").put("ambientMemoryProvided",false).put("retention","task_only").put("unsupportedMaterials",new JSONArray().put("private_memory").put("whole_workspace").put("unapproved_task_attachments")).put("executionAuthorized",false);}
        public TaskStoreDeviceTasks.SourceScopeReview review(Selection selection)throws Exception{requireCurrent();if(selection==null||selection.routes().size()>16)throw new SecurityException("原设备范围选择越界");ArrayList<RouteSelection> resolved=new ArrayList<>();java.util.Set<String> seen=new java.util.HashSet<>();for(RouteChoice choice:selection.routes()){RouteOption option=options.get(choice.optionId());if(option==null||!seen.add(option.sourceKey()))throw new SecurityException("路由不是本窗口native选项或重复源份额");ArrayList<ShareOption> downstream=new ArrayList<>();java.util.Set<String> seenShares=new java.util.HashSet<>();for(ShareChoice share:choice.shares()){ShareOption found=null;for(ShareOption candidate:option.shareOptions())if(candidate.optionId().equals(share.optionId()))found=candidate;if(found==null||!seenShares.add(found.optionId()))throw new SecurityException("下游不是B原owner明确范围中的准确资源");downstream.add(found);}JSONArray files=DeviceSelectedFileTransfer.resolve(source.attachmentBook(),materials,choice.files());if(files.length()>0&&!"write".equals(option.toolEffects().get("workspace.receive")))throw new SecurityException("目标没有显式workspace.receive授权");JSONArray projects=DeviceSelectedProjectTransfer.resolve(tasks,source.localModule(),projectMaterials,choice.projects());if(projects.length()>0&&!"write".equals(option.toolEffects().get("workspace.project.receive")))throw new SecurityException("目标没有原项目包接收能力");for(int f=0;f<files.length();f++)for(int p=0;p<projects.length();p++)if(files.getJSONObject(f).getString("path").equals(projects.getJSONObject(p).getString("path")))throw new SecurityException("文件和项目不能写入同一路径");RouteSelection selected=new RouteSelection(option,choice,downstream,files,projects);selected.book=source.attachmentBook();resolved.add(selected);}return tasks.deviceTasks().reviewSourceScope(this,resolved);}
        public JSONObject confirm(TaskStoreDeviceTasks.SourceScopeReview review)throws Exception{DeviceSelectedFileTransfer.revalidateReview(source.attachmentBook(),review.view());JSONArray routes=review.view().getJSONArray("routes");for(int i=0;i<routes.length();i++){JSONArray projects=routes.getJSONObject(i).optJSONArray("projects");for(int n=0;projects!=null&&n<projects.length();n++)DeviceSelectedProjectTransfer.require(tasks,source.localModule(),projects.getJSONObject(n),new dev.pocketengine.core.RunControl());}return tasks.deviceTasks().confirmSourceScope(this,review);}
        public TaskStoreDeviceTasks.ProjectImportReview reviewProjectImport(String receiveOperationId)throws Exception{requireCurrent();var review=tasks.deviceTasks().reviewProjectImportForOwner(this,receiveOperationId);DeviceSelectedProjectTransfer.require(tasks,source.localModule(),review.view(),new dev.pocketengine.core.RunControl());return review;}
        public JSONObject confirmProjectImport(TaskStoreDeviceTasks.ProjectImportReview review)throws Exception{requireCurrent();DeviceSelectedProjectTransfer.require(tasks,source.localModule(),review.view(),new dev.pocketengine.core.RunControl());return tasks.deviceTasks().confirmProjectImportForOwner(this,review);}
        /** Fixed lock order: native owner-window gate -> original TaskStore. SQL only, no I/O. */
        public JSONObject commitOriginalSourceScope(DeviceTaskCoordinator.Acceptance commit)throws Exception{synchronized(confirmationGate){requireCurrent();return commit.commit();}}
        @Override public void close(){synchronized(confirmationGate){closed=true;}}
    }
    public static String sourceRouteKey(String target,JSONObject binding,String kind)throws Exception{return "route_"+DeviceProtocol.sha256(DeviceProtocol.canonicalBytes(new JSONObject().put("targetDeviceId",target).put("resourceBinding",binding).put("kind",kind)));}
    private List<RouteOption> options()throws Exception{
        ArrayList<RouteOption> result=new ArrayList<>();java.util.Set<String> optionIds=new java.util.HashSet<>();Map<String,JSONObject> facts=new LinkedHashMap<>();int count=0;long now=System.currentTimeMillis();for(PairingCoordinator.OutgoingGrant grant:source.originalOutgoingGrants()){
            if(++count>128)throw new SecurityException("native路由候选过多");grant.checkCurrent(now);if(!facts.containsKey(grant.targetDeviceId()))facts.put(grant.targetDeviceId(),source.authenticatedCapabilities(grant.targetDeviceId()));JSONObject manifest=facts.get(grant.targetDeviceId());now=System.currentTimeMillis();grant.checkCurrent(now);if(manifest==null||!grant.targetDeviceId().equals(manifest.optString("deviceId"))||!grant.targetInstanceId().equals(manifest.optString("instanceId"))||manifest.optLong("capturedAt")>now||manifest.optLong("validUntil")<=now)continue;
            JSONArray profiles=manifest.optJSONArray("capabilities");JSONObject body=grant.grant();JSONArray resources=body.getJSONArray("resources");
            for(int r=0;r<resources.length();r++){JSONObject binding=resources.getJSONObject(r);for(int p=0;profiles!=null&&p<profiles.length();p++){
                JSONObject profile=profiles.getJSONObject(p);if(Boolean.FALSE.equals(profile.opt("implemented"))||Boolean.FALSE.equals(profile.opt("installed")))continue;String version=profile.optString("capabilityVersion",profile.optString("version"));String id=profile.optString("id");boolean goal=id.equals("agent.goal");String kind=goal?"goal":"tool_session";if(goal&&(!profile.optBoolean("implemented")||!profile.optBoolean("installed")||!profile.optBoolean("ready")&&!profile.optBoolean("canSubmitForLocalReview")))continue;
                if(!PairingCoordinator.contains(body.getJSONArray("capabilities"),goal?"task.submitGoal":"tool.invoke"))continue;if(goal&&!PairingCoordinator.contains(profile.optJSONArray("supportedExecutionModes"),binding.optString("screenMode")))continue;
                if(id.equals("phone.tools.main")&&!"main_screen".equals(binding.optString("screenMode"))||id.equals("phone.tools.background")&&!"background".equals(binding.optString("screenMode")))continue;
                if(!goal&&!(id.startsWith("phone.tools.")||version.equals("desktop-v2")||version.equals("workspace-v1")))continue;try{DeviceProtocol.validateResourceBinding(version,binding);}catch(DeviceProtocol.ProtocolException outside){continue;}
                JSONArray declared=profile.optJSONArray("resources");if(declared!=null&&!PairingCoordinator.resourceContains(declared,binding))continue;Map<String,String> effects=new LinkedHashMap<>();JSONArray tools=profile.optJSONArray("toolDeclarations");for(int t=0;tools!=null&&t<tools.length();t++){JSONObject tool=tools.getJSONObject(t);String name=DeviceProtocol.text(tool.opt("toolName"),"toolName",128),effect=tool.optString("effect");if(!effect.matches("read|write|external"))throw new SecurityException("目标实际工具声明副作用未知");if(!goal&&!effect.equals(DeviceSourceToolContracts.effect(version,name)))continue;if(name.matches("desktop\\.(observe|act)")&&images==null)continue;if(PairingCoordinator.contains(body.getJSONArray("toolNames"),name)&&(!DeviceToolsRuntime.requiresWholeDisplayInput(name)||binding.optBoolean("allowWholeDisplayInput",false)))effects.put(name,effect);}
                if(!goal&&effects.isEmpty())continue;if(result.size()>=128)throw new SecurityException("实际设备范围候选过多，请收窄本机原grant配置");RouteOption candidate=new RouteOption(grant,kind,version,binding,effects,new JSONObject(profile.toString()).put("capturedAt",manifest.getLong("capturedAt")).put("validUntil",manifest.getLong("validUntil")));if(optionIds.add(candidate.optionId()))result.add(candidate);
            }}
        }return java.util.Collections.unmodifiableList(new java.util.ArrayList<>(result));
    }
}
