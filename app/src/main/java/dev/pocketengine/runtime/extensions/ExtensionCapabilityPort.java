package dev.pocketengine.runtime.extensions;

import dev.pocketengine.core.CapabilityPort;
import dev.pocketengine.core.ExecutionPlanning;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.core.ScopedToolCatalog;
import dev.pocketengine.features.plugins.PluginRules;
import dev.pocketengine.store.TaskStore;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.HashSet;
import java.util.Set;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;

/** Outermost loop adapter: original combined port + actual approved extension code. */
final class ExtensionCapabilityPort implements CapabilityPort {
    private final ExtensionHost host;
    private final CapabilityPort existing;
    private final TaskStore tasks;
    private final ExtensionRuntime.Binding binding;
    private final RunControl original;
    private final Map<String,ExtensionProgram> mounted=new LinkedHashMap<>();
    /** Only the current model request's admitted schemas, including withdrawn-name tombstones. */
    private final Map<String,JSONObject> admitted=new LinkedHashMap<>();
    private volatile ExtensionRuntime runtime;
    private volatile boolean closed,cleanupComplete;
    private boolean begun;
    private String admittedFingerprint="";
    private String admittedBaseFingerprint="";
    private volatile boolean stale=true;
    private JSONArray admittedBaseNames=new JSONArray();
    private volatile dev.pocketengine.core.CapabilitySnapshot capabilitySnapshot;
    private volatile ToolDiscoverySession discovery;
    private volatile TaskStore.ToolCatalogAccess catalogAccess;
    private volatile Set<String> catalogNames=Set.of(),publishedNames=Set.of();
    private String catalogDecisionRevision="";
    private boolean deferCatalog;

    ExtensionCapabilityPort(ExtensionHost host,CapabilityPort existing,TaskStore tasks,ExtensionRuntime.Binding binding,RunControl original){this.host=host;this.existing=existing;this.tasks=tasks;this.binding=binding;this.original=original;}

    private void check(RunControl supplied)throws Exception {
        if(closed||supplied!=original)throw new SecurityException("扩展不能替换原控制器或跨轮恢复");
        tasks.checkExtensionRun(binding.taskId,binding.runId,binding.epoch,original);
    }
    @Override public void beginRun(String goal,RunControl control)throws Exception {
        check(control);if(begun)throw new IllegalStateException("本轮能力生命周期已经开始");existing.beginRun(goal,control);begun=true;sync(true);
        if(runtime!=null)runtime.event("run_start",new JSONObject().put("goal",limited(goal,12000)),original);
    }
    @Override public JSONObject prepareTurn(String query,int round,int steps,RunControl control)throws Exception {
        check(control);if(!begun)throw new IllegalStateException("请先开始原能力执行");prepareTurnSurface(control);String fingerprint=sync(true);
        JSONArray expectedBase=existing.tools(),extensionTools=runtime==null?new JSONArray():runtime.tools(original),expectedAll=merge(expectedBase,extensionTools);
        Set<String> capabilities=new HashSet<>();JSONArray expectedNames=names(expectedAll);for(int i=0;i<expectedNames.length();i++)capabilities.add(expectedNames.getString(i));
        if(runtime!=null)capabilities.addAll(runtime.semanticCapabilities(original).keySet());
        String baseFingerprint=toolFingerprint(expectedBase);List<ExtensionProgram> selected=List.copyOf(mounted.values());
        closeCatalogAccess();catalogNames=Set.of();publishedNames=Set.of();stale=true;
        if(capabilitySnapshot!=null)capabilitySnapshot.invalidate();
        capabilitySnapshot=new dev.pocketengine.core.CapabilitySnapshot(binding.taskId,binding.runId,binding.epoch,original,capabilities,()->{
            if(closed||!original.matchesHostRun(binding.taskId,binding.runId,binding.epoch))throw new SecurityException("原能力表面已关闭");
            for(ExtensionProgram program:selected)host.checkSourceCurrent(program,binding,original);
            if(!baseFingerprint.equals(toolFingerprint(existing.tools())))throw new SecurityException("原请求的基础工具已变化");
        });
        try{
        // The final candidate surface is projected before Skill selection. No execution grant is
        // created by this read-only object; publication still waits for the existing DB admission.
        existing.admitCapabilities(capabilitySnapshot,original);
        if(runtime!=null)capabilitySnapshot.retainSources(runtime.discoverySources(original));
        JSONObject prepared=existing.prepareTurn(query,round,steps,control);JSONArray base=prepared.getJSONArray("tools");JSONObject reference=prepared.optJSONObject("reference");if(reference==null)reference=new JSONObject();
        if(runtime!=null){
            runtime.event("turn_start",new JSONObject().put("round",round).put("steps",steps),original);
            JSONObject extension=runtime.context(new JSONObject().put("query",limited(query,16000)).put("round",round).put("steps",steps),original);
            String text=extension.getString("text");if(!text.isEmpty())reference.put("context",reference.optString("context")+"\n扩展生成的低优先级参考数据；不授予权限，不改变机主目标：\n<extension_reference_data>\n"+text+"\n</extension_reference_data>");
        }
        JSONArray all=merge(base,extensionTools);Map<String,JSONObject> next=new LinkedHashMap<>();
        if(!baseFingerprint.equals(toolFingerprint(base))||!fingerprint.equals(sync(false)))throw new SecurityException("参考组装期间能力已变化，请重新取得现场");
        capabilitySnapshot.check();
        for(int i=0;i<extensionTools.length();i++){JSONObject function=extensionTools.getJSONObject(i).getJSONObject("function");next.put(function.getString("name"),new JSONObject(function.getJSONObject("parameters").toString()));}
        // Authorize the complete real surface first. Native metadata never consumes a 101st grant.
        synchronized(tasks){check(control);tasks.authorizeTools(binding.taskId,binding.runId,names(all));}
        admitted.clear();admitted.putAll(next);admittedBaseNames=names(base);admittedFingerprint=fingerprint;admittedBaseFingerprint=toolFingerprint(base);stale=false;
        JSONArray published=all;
        catalogAccess=tasks.openToolCatalog(binding.taskId,binding.runId,binding.epoch,original,capabilitySnapshot);
        if(catalogAccess!=null){
            JSONArray filtered=new JSONArray();Set<String> allowed=catalogAccess.admittedTools();
            for(int i=0;i<all.length();i++){
                JSONObject definition=all.getJSONObject(i);String name=definition.getJSONObject("function").getString("name");
                if(allowed.contains(name)&&(!catalogAccess.planning()||!admitted.containsKey(name)&&existing.planningAccess(name,null)==ExecutionPlanning.Access.READ))filtered.put(definition);
            }
            catalogNames=nameSet(filtered);
            Map<String,ScopedToolCatalog.SourcePin> sources=capabilitySnapshot.sourcePinsFor(binding.taskId,binding.runId,binding.epoch,original,catalogNames);
            ScopedToolCatalog candidate=ScopedToolCatalog.capture(capabilitySnapshot,binding.taskId,binding.runId,binding.epoch,original,filtered,sources,ToolDiscoverySession.DEFAULT_RESIDENT);
            // Decide once per exact source/scope revision. Later activation must not hide an
            // already published tool again merely because the last search is no longer useful.
            if(!candidate.revision().equals(catalogDecisionRevision)){
                JSONObject summary=candidate.summary(Set.of());JSONArray initial=candidate.requestTools(Set.of());
                deferCatalog=summary.getInt("hiddenCount")>0&&utf8Bytes(initial)+utf8Bytes(discoveryReference(summary))<utf8Bytes(filtered);
                catalogDecisionRevision=candidate.revision();
            }
            published=filtered;
            if(deferCatalog){
                if(discovery==null)discovery=new ToolDiscoverySession(binding.taskId,binding.runId,binding.epoch,original);
                ToolDiscoverySession requestSession=discovery;
                requestSession.beginRequest(capabilitySnapshot,filtered,sources,ToolDiscoverySession.DEFAULT_RESIDENT,original);
                published=requestSession.requestTools(original);JSONObject summary=requestSession.summary(original);
                catalogAccess.bindRequest(()->requestSession.requirePublished(ScopedToolCatalog.SEARCH_TOOL,original));
                if(summary.getInt("hiddenCount")>0)reference.put("context",reference.optString("context")+discoveryReference(summary));
            }else closeDiscovery();
            catalogAccess.check(original);
        }else{
            // Dedicated native scopes retain their complete original schemas and budget path.
            closeDiscovery();catalogDecisionRevision="";deferCatalog=false;
        }
        publishedNames=nameSet(published);capabilitySnapshot.check();
        return new JSONObject().put("tools",published).put("reference",reference).put("extensionSnapshot",fingerprint);
        }catch(Exception failure){capabilitySnapshot.invalidate();stale=true;closeCatalogAccess();throw failure;}
    }
    @Override public void prepareTurnSurface(RunControl control)throws Exception {check(control);existing.prepareTurnSurface(control);check(control);}
    @Override public boolean stepReadSourceCurrent(String tool,JSONObject receipt,RunControl control)throws Exception {check(control);return !stale&&!admitted.containsKey(tool)&&existing.stepReadSourceCurrent(tool,receipt,control);}
    @Override public boolean responseCurrent(RunControl control)throws Exception {
        check(control);boolean current=false;
        try{current=!stale&&admittedFingerprint.equals(sync(false))&&admittedBaseFingerprint.equals(toolFingerprint(existing.tools()))&&existing.responseCurrent(control);if(current)requireRequestCurrent(control);}
        catch(SecurityException changed){current=false;}
        if(!current){stale=true;if(capabilitySnapshot!=null)capabilitySnapshot.invalidate();}return current;
    }
    @Override public void beforeModelRequest(String requestId,JSONArray messages,RunControl control)throws Exception{check(control);requireRequestCurrent(control);existing.beforeModelRequest(requestId,messages,control);requireRequestCurrent(control);}
    @Override public JSONObject afterModelResponse(String requestId,String answerId,RunControl control)throws Exception{check(control);requireRequestCurrent(control);JSONObject receipt=existing.afterModelResponse(requestId,answerId,control);requireRequestCurrent(control);return receipt;}
    @Override public void modelRequestAbandoned(String requestId)throws Exception{existing.modelRequestAbandoned(requestId);}
    @Override public dev.pocketengine.core.NativeToolObservation.Requirement observationRequirement(String name,JSONObject args,RunControl control)throws Exception{check(control);return admitted.containsKey(name)?dev.pocketengine.core.NativeToolObservation.Requirement.NONE:existing.observationRequirement(name,args,control);}
    @Override public dev.pocketengine.core.NativeToolObservation takeToolObservation(String name,String operation,RunControl control)throws Exception{check(control);return admitted.containsKey(name)?null:existing.takeToolObservation(name,operation,control);}
    @Override public dev.pocketengine.core.NativeToolPlan takeToolPlan(String name,String operation,RunControl control)throws Exception{check(control);return admitted.containsKey(name)?null:existing.takeToolPlan(name,operation,control);}
    @Override public dev.pocketengine.core.NativeReadContextRepair nativeReadContextRepair(JSONArray messages,RunControl control)throws Exception{check(control);return existing.nativeReadContextRepair(messages,control);}
    @Override public JSONObject beforeTool(String name,JSONObject arguments,RunControl control)throws Exception {
        check(control);String fingerprint=sync(false);
        if(stale||!admittedFingerprint.equals(fingerprint)||!admittedBaseFingerprint.equals(toolFingerprint(existing.tools()))){stale=true;if(capabilitySnapshot!=null)capabilitySnapshot.invalidate();return refused("stale_extension_context","能力、扩展启停、审批或源码已变化；旧决定未执行，请按当前现场重新规划。");}
        try{requireRequestCurrent(control);}catch(SecurityException changed){stale=true;if(capabilitySnapshot!=null)capabilitySnapshot.invalidate();return refused("stale_extension_context","原工具来源或范围已变化；旧决定尚未派发。");}
        // A guessed hidden name is refused without retiring this still-current catalogue.
        requirePublishedTool(name,control);
        if(admitted.containsKey(name)&&(runtime==null||!runtime.supports(name)))return refused("tool_withdrawn","本次请求的扩展工具已撤销，尚未执行。");
        JSONObject originalGate=existing.beforeTool(name,new JSONObject(arguments.toString()),control);if(originalGate!=null)return originalGate;
        if(runtime!=null){ExtensionRuntime.Decision gate=runtime.beforeTool(name,arguments,original);if(!gate.allowed)return refused("extension_blocked","扩展 "+gate.extensionId+" 拒绝了本次工具："+gate.reason);}
        check(control);return null;
    }
    @Override public void afterTool(String name,JSONObject arguments,JSONObject receipt,RunControl control)throws Exception {
        check(control);
        try{requireRequestCurrent(control);}
        catch(ExecutionPlanning.ScopeRevisionChanged changed){
            // The real receipt and checkpoint are already durable. A native allocation
            // may change this same run's budget; retire its old callbacks and decisions,
            // then let the next normal turn capture the current scope. Other refusals
            // and storage failures still propagate, including source/plan revocation.
            check(control);stale=true;if(capabilitySnapshot!=null)capabilitySnapshot.invalidate();
            closeCatalogAccess();closeDiscovery();return;
        }
        existing.afterTool(name,arguments,receipt,control);
        if(runtime!=null&&admittedFingerprint.equals(sync(false)))runtime.event("tool_result",new JSONObject().put("name",name).put("arguments",eventData(arguments)).put("receipt",eventData(receipt)),original);
        if(discovery!=null)discovery.afterTool(name,receipt,control);
    }
    @Override public void endRun(RunControl control)throws Exception {
        if(!begun)return;check(control);existing.endRun(control);if(runtime!=null&&admittedFingerprint.equals(sync(false)))runtime.event("run_end",new JSONObject().put("driverExited",true).put("taskCompletionClaimed",false),original);
    }
    /** Complete business definitions for original authorization. prepareTurn owns metadata publication. */
    @Override public JSONArray tools()throws Exception {return merge(existing.tools(),runtime==null?new JSONArray():runtime.tools(original));}
    @Override public JSONObject contextSnapshot(String query)throws Exception {return existing.contextSnapshot(query);}
    @Override public boolean supports(String name){return !closed&&(nativeSearch(name)?!stale&&publishedNames.contains(name):admitted.containsKey(name)||runtime!=null&&runtime.supports(name)||existing.supports(name));}
    @Override public boolean sideEffecting(String name){return nativeSearch(name)||admitted.containsKey(name)||runtime!=null&&runtime.supports(name)?false:existing.sideEffecting(name);}
    @Override public ExecutionPlanning.Access planningAccess(String name,JSONObject arguments)throws Exception{
        if(closed)return ExecutionPlanning.Access.DENY;
        if(nativeSearch(name)){
            if(stale||!publishedNames.contains(name))return ExecutionPlanning.Access.DENY;
            requireRequestCurrent(original);requirePublishedTool(name,original);
            if(arguments!=null){JSONObject clean=new JSONObject(arguments.toString());clean.remove("_hostOperationId");discovery.validateSearch(clean,original);}
            return ExecutionPlanning.Access.READ;
        }
        return admitted.containsKey(name)||runtime!=null&&runtime.supports(name)?ExecutionPlanning.Access.DENY:existing.planningAccess(name,arguments);
    }
    @Override public void validate(String name,JSONObject arguments)throws Exception {
        requirePublishedTool(name,original);
        if(nativeSearch(name)){discovery.validateSearch(arguments,original);return;}
        JSONObject schema=admitted.get(name);if(schema!=null)PluginRules.validateArguments(arguments,schema);else existing.validate(name,arguments);
    }
    @Override public JSONObject execute(String name,JSONObject arguments,RunControl control)throws Exception {
        if(closed||control!=original||!control.matchesHostRun(binding.taskId,binding.runId,binding.epoch))throw new SecurityException("执行控制器不属于本轮能力");
        if(stale)return refused("tool_withdrawn","本轮能力参考已撤销，尚未进入原执行器。");
        requireRequestCurrent(control);requirePublishedTool(name,control);
        if(nativeSearch(name)){
            TaskStore.ToolCatalogAccess access=catalogAccess;ToolDiscoverySession requestSession=discovery;
            if(access==null||requestSession==null)throw new CapabilityPort.Unavailable();
            JSONObject clean=new JSONObject(arguments.toString());clean.remove("_hostOperationId");
            return access.executeRead(clean,control,()->requestSession.search(clean,control));
        }
        // Do NOT put the read-side no-prepared fence or a post-control check around W08.
        // Its native effect already has the original journal intent and may return a real receipt after stop.
        if(!admitted.containsKey(name))return existing.execute(name,arguments,control);
        check(control);if(!admittedFingerprint.equals(sync(false))||runtime==null||!runtime.supports(name))return refused("tool_withdrawn","扩展工具或其授权已撤销，尚未执行。");
        // Pure JS has no host effect and therefore no prepared operation. It still needs this grant.
        tasks.checkCapability(binding.taskId,binding.runId,binding.epoch,name,original);JSONObject result=runtime.execute(name,arguments,original);tasks.checkCapability(binding.taskId,binding.runId,binding.epoch,name,original);return result;
    }

    private String sync(boolean mountNew)throws Exception {
        check(original);List<ExtensionProgram> selected=List.copyOf(host.approved(binding,original));if(selected.size()>ExtensionRuntime.MAX_EXTENSIONS)throw new IllegalArgumentException("扩展目录返回数量超限");
        Map<String,ExtensionProgram> current=new TreeMap<>();Set<String> installations=new HashSet<>();
        for(ExtensionProgram program:selected){if(current.put(program.id,program)!=null||!installations.add(program.installationId))throw new SecurityException("扩展目录存在重复身份");}
        for(ExtensionProgram previous:List.copyOf(mounted.values())){ExtensionProgram next=current.get(previous.id);if(next==null||!same(previous,next)){if(runtime!=null)runtime.unmount(previous.installationId);mounted.remove(previous.id);}}
        if(mountNew)for(ExtensionProgram program:current.values())if(!mounted.containsKey(program.id)){
            if(runtime==null)runtime=host.runtime(binding);runtime.mount(program,original);mounted.put(program.id,program);
        }
        StringBuilder fingerprint=new StringBuilder();for(ExtensionProgram program:current.values())fingerprint.append(program.id).append(':').append(program.version).append(':').append(program.installationId).append(':').append(program.approvalRevision).append(':').append(program.sourceSha256).append(':').append(program.maxMillis).append('\n');
        check(original);return ExtensionProgram.sha256(fingerprint.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    private static boolean same(ExtensionProgram a,ExtensionProgram b){return a.id.equals(b.id)&&a.version.equals(b.version)&&a.installationId.equals(b.installationId)&&a.approvalRevision==b.approvalRevision&&a.sourceSha256.equals(b.sourceSha256)&&a.maxMillis==b.maxMillis;}
    private boolean nativeSearch(String name){return catalogAccess!=null&&ScopedToolCatalog.SEARCH_TOOL.equals(name);}
    private void requireRequestCurrent(RunControl control)throws Exception{
        if(closed||stale||control!=original||capabilitySnapshot==null)throw new CapabilityPort.Unavailable();
        capabilitySnapshot.current(binding.taskId,binding.runId,binding.epoch,control);
    }
    private void requirePublishedTool(String name,RunControl control)throws Exception{
        if(catalogAccess==null)return;requireRequestCurrent(control);
        if(nativeSearch(name)){
            ToolDiscoverySession requestSession=discovery;
            if(requestSession==null||!publishedNames.contains(name))throw new SecurityException("工具搜索没有在本请求发布");
            requestSession.requirePublished(name,control);
        }else if(admitted.containsKey(name)||existing.supports(name)){
            if(!catalogNames.contains(name)||!publishedNames.contains(name))throw new SecurityException("工具不在本请求已准入且发布的定义中");
            if(discovery!=null)discovery.requirePublished(name,control);
        }
    }
    private void closeCatalogAccess(){TaskStore.ToolCatalogAccess previous=catalogAccess;catalogAccess=null;if(previous!=null)previous.close();}
    private void closeDiscovery(){ToolDiscoverySession previous=discovery;discovery=null;if(previous!=null)previous.close();}
    void invalidate(String installationId){stale=true;if(capabilitySnapshot!=null)capabilitySnapshot.invalidate();closeCatalogAccess();closeDiscovery();ExtensionRuntime current=runtime;if(current!=null)current.unmount(installationId);}
    @Override public void close(){
        if(cleanupComplete)return;closed=true;if(capabilitySnapshot!=null)capabilitySnapshot.invalidate();RuntimeException failure=null;
        closeCatalogAccess();closeDiscovery();
        try{ExtensionRuntime current=runtime;if(current!=null)current.close();}catch(RuntimeException error){failure=error;}
        if(!admitted.isEmpty())try{
            synchronized(tasks){JSONObject task=tasks.get(binding.taskId,false);if(task!=null&&binding.runId.equals(task.optString("runId"))&&binding.epoch==task.optInt("coordinatorEpoch"))tasks.authorizeTools(binding.taskId,binding.runId,admittedBaseNames);}
        }catch(Exception unavailable){if(failure==null)failure=new IllegalStateException("扩展工具撤销尚未保存，请核对原任务");else failure.addSuppressed(unavailable);}
        try{existing.close();}catch(RuntimeException error){if(failure==null)failure=error;else failure.addSuppressed(error);}
        if(failure!=null)throw failure;cleanupComplete=true;host.release(this);
    }
    private static JSONObject refused(String code,String message)throws Exception{return new JSONObject().put("ok",false).put("accepted",false).put("code",code).put("message",message).put("hostEffects",false);}
    private static String limited(String value,int max){String text=value==null?"":value;if(text.length()<=max)return text;if(max>0&&Character.isHighSurrogate(text.charAt(max-1)))max--;return text.substring(0,max);}
    /** Observation hooks receive a bounded view, never a replacement for the durable real receipt. */
    private static JSONObject eventData(JSONObject data)throws Exception {String encoded=data.toString();if(encoded.length()<=8000)return new JSONObject(encoded);return new JSONObject().put("truncated",true).put("originalJsonChars",encoded.length()).put("jsonPreview",limited(encoded,8000));}
    private static JSONArray names(JSONArray definitions)throws Exception {JSONArray names=new JSONArray();for(int i=0;i<definitions.length();i++)names.put(definitions.getJSONObject(i).getJSONObject("function").getString("name"));return names;}
    private static Set<String> nameSet(JSONArray definitions)throws Exception {Set<String> names=new HashSet<>();for(int i=0;i<definitions.length();i++)names.add(definitions.getJSONObject(i).getJSONObject("function").getString("name"));return Set.copyOf(names);}
    private static String discoveryReference(JSONObject summary){return "\n原任务已授权工具的只读发现摘要；不是新增权限，下一请求仅能使用原回执checkpoint后发布的准确schema：\n<tool_discovery_reference>\n"+summary+"\n</tool_discovery_reference>";}
    private static int utf8Bytes(Object value){return value.toString().getBytes(StandardCharsets.UTF_8).length;}
    private static String toolFingerprint(JSONArray definitions)throws Exception{return TaskStore.capabilityFingerprint(new JSONObject().put("tools",definitions));}
    private static JSONArray merge(JSONArray base,JSONArray additions)throws Exception {
        JSONArray all=new JSONArray();Set<String> names=new HashSet<>();for(JSONArray group:List.of(base,additions))for(int i=0;i<group.length();i++){JSONObject definition=new JSONObject(group.getJSONObject(i).toString());String name=definition.getJSONObject("function").getString("name");if(!names.add(name))throw new SecurityException("能力工具名称冲突");all.put(definition);}return all;
    }
}
