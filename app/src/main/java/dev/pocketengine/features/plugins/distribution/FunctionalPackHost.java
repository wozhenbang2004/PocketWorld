package dev.pocketengine.features.plugins.distribution;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.EngineModule;
import dev.pocketengine.features.plugins.PluginRevisions;
import dev.pocketengine.features.plugins.catalog.ExecutablePluginCatalog;
import dev.pocketengine.features.plugins.sdk.js.JsPluginPackage;
import dev.pocketengine.features.skills.SkillsModule;
import dev.pocketengine.features.workspace.JsonFiles;
import dev.pocketengine.store.TaskStore;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONObject;

/** Native component ownership and package transport only. Original modules own installations and permissions;
 * original TaskStore owns every actual operation/unknown receipt. No recipe executor or grant registry. */
public final class FunctionalPackHost implements AutoCloseable {
    private static final String STORE="pocketworld.functional-pack-installations.v1";
    private final EngineModule plugins;
    private final SkillsModule skills;
    private final ExecutablePluginCatalog executable;
    private final PluginPackages declarations;
    private final TaskStore tasks;
    private final File stateFile,packages;
    private final Consumer<String> changedTask,invalidate;
    private final AtomicBoolean closed=new AtomicBoolean();
    private final Set<Page> pages=java.util.concurrent.ConcurrentHashMap.newKeySet();
    public FunctionalPackHost(EngineModule plugins,SkillsModule skills,ExecutablePluginCatalog executable,TaskStore tasks,File privateState,Consumer<String> changedTask,Consumer<String> invalidate){
        this.plugins=java.util.Objects.requireNonNull(plugins);this.skills=java.util.Objects.requireNonNull(skills);this.executable=java.util.Objects.requireNonNull(executable);this.tasks=java.util.Objects.requireNonNull(tasks);stateFile=privateState;packages=new File(privateState.getParentFile(),privateState.getName()+".packages");declarations=new PluginPackages(plugins);this.changedTask=changedTask;this.invalidate=invalidate;
    }
    private static JSONObject scope()throws Exception{return new JSONObject().put("ownerId","owner").put("spaceId","private");}
    private void open(){if(closed.get())throw new SecurityException("原功能包宿主已关闭");}
    public Page newOwnerPage(){open();Page page=new Page();pages.add(page);return page;}
    public final class Page implements AutoCloseable {
        private final AtomicBoolean alive=new AtomicBoolean(true),foreground=new AtomicBoolean();
        private final RunControl control=new RunControl();
        private volatile Review pending;
        private Page(){control.setAuthorityCheck(()->{open();if(!alive.get())throw new SecurityException("原功能包页面已离开");});}
        public void setForeground(boolean value){foreground.set(alive.get()&&value);}
        private void check(){open();if(!alive.get())throw new SecurityException("原功能包页面已关闭");}
        public JSONObject snapshot()throws Exception{check();return FunctionalPackHost.this.snapshot();}
        public JSONObject reconcile(String id)throws Exception{check();return FunctionalPackHost.this.reconcile(id);}
        public JSONObject journal(String id)throws Exception{check();synchronized(FunctionalPackHost.this){JSONObject row=record(read(),id);return originalTask(id,row.getString("taskId"));}}
        public Review inspect(InputStream exactFile)throws Exception{check();Review review=prepare(this,FunctionalPackage.read(exactFile,control),false);check();pending=review;return review;}
        public Review restore(String id,String installationId)throws Exception{check();FunctionalPackage pkg;
            synchronized(FunctionalPackHost.this){JSONObject record=record(read(),id),history=history(record,installationId);pkg=readPackage(history,control);}
            Review review=prepare(this,pkg,true);check();pending=review;return review;
        }
        public Review reviewRemove(String id)throws Exception{check();Review review=prepareRemove(this,id);check();pending=review;return review;}
        /** Native owner chooses an independent, dependency-complete subset; never restores component grants. */
        public Review selectComponents(Review original,JSONArray selectedKeys)throws Exception {
            check();if(original==null||original.page!=this||original!=pending||original.consumed||original.removing)throw new SecurityException("请先读取本页准确原包，再选择可用组件");Set<String> selected=new java.util.LinkedHashSet<>();for(int i=0;i<selectedKeys.length();i++){String key=selectedKeys.getString(i);if(!selected.add(key))throw new IllegalArgumentException("组件选择不能重复");}if(selected.isEmpty()||selected.size()>24)throw new IllegalArgumentException("请选择1至24项本次可用原组件动作");JSONObject previous=original.expectedRevision==0?null:new JSONObject(original.expectedRecord);Review next=new Review(this,original.pkg,previous,false,original.restoring,original.allParts,new JSONArray(original.issues),new JSONArray(original.globalIssues),selected,true);pending=next;return next;
        }

        public JSONObject submit(Review review,String digest)throws Exception{
            check();if(!foreground.get()||review==null||review.page!=this||review!=pending||review.consumed||!review.digest.equals(digest)||!review.ready)throw new SecurityException("需前台原页面当前完整功能包审阅；不能从JSON恢复批准");
            review.consumed=true;pending=null;return apply(review,control);
        }
        /** Cancellation never retries/rolls back already committed components. */
        public void cancel(){control.cancel();}
        @Override public void close(){if(alive.compareAndSet(true,false)){foreground.set(false);pending=null;control.cancel();pages.remove(this);}}
    }
    public static final class Review {
        public final String digest;
        public final boolean ready;
        private final Page page;
        private final FunctionalPackage pkg;
        private final String id,expectedInstallation,expectedRecord,view;
        private final long expectedRevision;
        private final boolean removing,restoring;
        private final List<Part> parts,allParts;private final String issues,globalIssues;private final boolean selectionExplicit;
        private boolean consumed;
        private Review(Page page,FunctionalPackage pkg,JSONObject previous,boolean removing,boolean restoring,List<Part> parts,JSONArray issues)throws Exception{this(page,pkg,previous,removing,restoring,parts,issues,new JSONArray(),null,false);}
        private Review(Page page,FunctionalPackage pkg,JSONObject previous,boolean removing,boolean restoring,List<Part> allParts,JSONArray issues,JSONArray globalIssues,Set<String> chosen,boolean explicit)throws Exception {
            this.page=page;this.pkg=pkg;id=pkg==null?previous.getString("id"):pkg.id;expectedInstallation=previous==null?"":previous.getString("installationId");expectedRevision=previous==null?0:previous.getLong("revision");expectedRecord=previous==null?"{}":previous.toString();this.removing=removing;this.restoring=restoring;selectionExplicit=explicit;this.allParts=java.util.Collections.unmodifiableList(new ArrayList<>(allParts));this.issues=issues.toString();this.globalIssues=globalIssues.toString();
            ArrayList<Part> selected=new ArrayList<>();JSONArray rows=new JSONArray(),excluded=new JSONArray(),selectionProblems=new JSONArray();Set<String> found=new HashSet<>();for(Part part:allParts){boolean include=chosen==null||chosen.contains(part.key);if(include&&part.action.equals("blocked")){if(explicit)throw new SecurityException("受阻组件不可选："+part.key);include=false;}if(include){selected.add(part);found.add(part.key);}JSONObject row=part.view().put("selected",include).put("selectable",!part.action.equals("blocked"));rows.put(row);if(!include)excluded.put(row);}
            if(chosen!=null&&!found.equals(chosen))throw new SecurityException("所选key不属于本准确原包的可用动作");
            for(Part part:selected)if(part.action.equals("install")){JSONArray dependencies=part.component.requires();for(int i=0;i<dependencies.length();i++)if(!found.contains(dependencies.getString(i)))selectionProblems.put(part.key+"：本次未选择其所需组件 "+dependencies.getString(i)+"；不会把旧安装或未知效果当新依赖已满足");}
            this.parts=java.util.Collections.unmodifiableList(selected);ready=(removing||!selected.isEmpty())&&globalIssues.length()==0&&selectionProblems.length()==0&&(explicit||issues.length()==0);
            boolean whole=excluded.length()==0;JSONObject data=new JSONObject().put("id",id).put("package",pkg==null?JSONObject.NULL:pkg.view()).put("current",previous==null?JSONObject.NULL:publicRecord(previous)).put("removing",removing).put("restoring",restoring).put("components",rows).put("issues",issues).put("globalIssues",globalIssues).put("selectionProblems",selectionProblems).put("excludedComponents",excluded).put("selectionExplicit",explicit).put("wholePackageSelected",whole).put("recipeContentsCompleteIfActionsFinish",whole).put("ready",ready).put("atomic",false).put("executed",false).put("permissionsRestored",false).put("recipeExecution","instruction_only_original_runner");
            digest=TaskStore.capabilityFingerprint(data);view=data.put("reviewDigest",digest).toString();
        }
        /** Compare owner edits with this review's frozen actions, without changing native approval. */
        public boolean matchesSelection(Set<String> selectedKeys){
            if(selectedKeys==null||selectedKeys.size()!=parts.size())return false;
            for(Part part:parts)if(!selectedKeys.contains(part.key))return false;
            return true;
        }
        public JSONObject view()throws Exception{return new JSONObject(view);}
    }
    private static final class Part {
        final String key,kind,action,inspection,expected;
        final FunctionalPackage.Component component;
        final ExecutablePluginCatalog.Inspection jsInstall;
        final ExecutablePluginCatalog.InstalledReview jsRemove;
        final PluginPackages.Inspection declaration;
        Part(String key,String kind,String action,FunctionalPackage.Component component,JSONObject inspection,JSONObject expected,ExecutablePluginCatalog.Inspection jsInstall,ExecutablePluginCatalog.InstalledReview jsRemove,PluginPackages.Inspection declaration){this.key=key;this.kind=kind;this.action=action;this.component=component;this.inspection=inspection.toString();this.expected=expected==null?"{}":expected.toString();this.jsInstall=jsInstall;this.jsRemove=jsRemove;this.declaration=declaration;}
        JSONObject view()throws Exception{return new JSONObject().put("key",key).put("kind",kind).put("action",action).put("inspection",new JSONObject(inspection)).put("current",new JSONObject(expected)).put("component",component==null?JSONObject.NULL:component.view());}
    }
    private synchronized Review prepare(Page page,FunctionalPackage pkg,boolean restoring)throws Exception {
        open();page.control.check();JSONObject state=read(),old=find(state,pkg.id);admitPrevious(old);JSONArray issues=new JSONArray(),globalIssues=new JSONArray();List<Part> parts=new ArrayList<>();Set<String> backendIds=new HashSet<>(),incoming=new HashSet<>();
        for(FunctionalPackage.Component component:pkg.components()){
            incoming.add(component.key);JSONObject owned=owned(old,component.key);if(owned!=null&&!component.kind.equals(owned.getString("kind"))){String reason="同一组件key不能改变类型，请使用新key并审阅移除原组件";issues.put(component.key+"："+reason);parts.add(blockedPart(component,owned,reason));continue;}
            try{Part part=prepareInstall(component,owned,page.control);String backend=component.kind+":"+backendId(part);if(!backendIds.add(backend))throw new IllegalArgumentException("同包重复目标安装身份");parts.add(part);}catch(Exception blocked){if(blocked instanceof InterruptedException)throw blocked;issues.put(component.key+"："+safe(blocked));parts.add(blockedPart(component,owned,safe(blocked)));}
        }
        if(old!=null){JSONArray previous=confirmedOwnership(old);if(previous.length()>24)globalIssues.put("已确认保留组件超过24项，请先审阅卸载当前部分安装，不再累积新组件");for(int i=0;i<previous.length();i++){JSONObject owned=previous.getJSONObject(i);if(!incoming.contains(owned.getString("key")))parts.add(prepareRemoval(owned,page.control));}}
        return new Review(page,pkg,old,false,restoring,parts,issues,globalIssues,null,false);
    }
    private static Part blockedPart(FunctionalPackage.Component component,JSONObject owned,String reason)throws Exception{return new Part(component.key,component.kind,"blocked",component,new JSONObject().put("reason",reason).put("nativeDispatched",false).put("dependencyOrCapabilityUnavailable",true),owned,null,null,null);}
    private Part prepareInstall(FunctionalPackage.Component component,JSONObject owned,RunControl control)throws Exception {
        JSONObject current=null,inspection;ExecutablePluginCatalog.Inspection js=null;PluginPackages.Inspection declaration=null;
        if(component.kind.equals("executable_js")){
            JsPluginPackage pkg=JsPluginPackage.read(component.open(),control);current=currentJs(pkg.id);requireOwnedOrAbsent(current,owned,component.kind);js=executable.inspect(pkg,control);inspection=js.view();
        }else if(component.kind.equals("declaration")){
            declaration=declarations.inspect(component.open(),control);inspection=declaration.review();if(inspection.getBoolean("requiresMcpRediscovery"))throw new IllegalArgumentException("此MCP组件需原服务重新发现、认证及精确工具批准；此组件保持受阻；可另选独立可用组件，不能直接恢复此服务真实性");
            current=currentDeclaration(inspection.getJSONObject("preview").getString("id"));requireOwnedOrAbsent(current,owned,component.kind);
        }else{
            current=owned==null?null:currentSkill(owned.getString("id"));requireOwnedOrAbsent(current,owned,component.kind);inspection=skills.inspectPackage(scope(),component.open(),current==null?null:current.getString("id"));
        }
        return new Part(component.key,component.kind,"install",component,inspection,current,js,null,declaration);
    }
    private synchronized Review prepareRemove(Page page,String id)throws Exception {JSONObject old=record(read(),id);admitPrevious(old);List<Part> parts=new ArrayList<>();JSONArray owned=confirmedOwnership(old);
        for(int i=0;i<owned.length();i++)parts.add(prepareRemoval(owned.getJSONObject(i),page.control));return new Review(page,null,old,true,false,parts,new JSONArray());}
    private Part prepareRemoval(JSONObject owned,RunControl control)throws Exception {
        String kind=owned.getString("kind"),id=owned.getString("id");JSONObject current=kind.equals("executable_js")?currentJs(id):kind.equals("declaration")?currentDeclaration(id):currentSkill(id);
        if(current==null)return new Part(owned.getString("key"),kind,"already_absent",null,new JSONObject().put("message","原组件已不在当前安装，未重复卸载"),null,null,null,null);
        if(!sameOwned(current,owned,kind))return new Part(owned.getString("key"),kind,"retain_changed",null,new JSONObject().put("message","此组件已被手工更新或替换；保留当前安装，不接管或删除"),current,null,null,null);
        ExecutablePluginCatalog.InstalledReview js=kind.equals("executable_js")?executable.reviewInstalled(id,control):null;
        return new Part(owned.getString("key"),kind,"remove",null,new JSONObject().put("message","仅移除此包原确认安装；已发生的效果和原任务回执保留"),current,null,js,null);
    }
    private static void requireOwnedOrAbsent(JSONObject current,JSONObject owned,String kind)throws Exception {
        if(current!=null&&(owned==null||!sameOwned(current,owned,kind)))throw new SecurityException("目标已由手工或另一安装占用，不会接管该组件；请更换组件ID或先到原管理页处理");
    }
    private static boolean sameOwned(JSONObject current,JSONObject owned,String kind)throws Exception {
        if(!current.getString("id").equals(owned.getString("id")))return false;
        if(kind.equals("skill_zip"))return current.getInt("revision")==owned.getInt("revision")&&current.getString("digest").equals(owned.getString("digest"))&&!current.has("origin");
        if(!current.getString("installationId").equals(owned.getString("installationId")))return false;
        return kind.equals("executable_js")?current.getString("sourceSha256").equals(owned.getString("sourceSha256")):PluginPackages.declarationDigest(current.getJSONObject("manifest")).equals(owned.getString("declarationDigest"));
    }
    private static String backendId(Part part)throws Exception {JSONObject inspected=new JSONObject(part.inspection);return part.kind.equals("declaration")?inspected.getJSONObject("preview").getString("id"):part.kind.equals("executable_js")?inspected.getString("id"):inspected.getJSONObject("package").getString("name");}
    private static void admitPrevious(JSONObject old)throws Exception {if(old!=null&&!old.getString("state").matches("installed|partial|removed"))throw new IllegalStateException("上次功能包操作仍未确认；只可读取原任务回执，不能重新提交或回退");}
    private synchronized JSONObject apply(Review review,RunControl control)throws Exception {
        open();control.check();JSONObject state=read(),old=find(state,review.id);expected(old,review.expectedInstallation,review.expectedRevision);
        if(old!=null&&!TaskStore.capabilityFingerprint(old).equals(TaskStore.capabilityFingerprint(new JSONObject(review.expectedRecord))))throw new SecurityException("功能包ownership已变化，请重新审阅");
        checkPartsCurrent(review.parts,control);
        if(!review.removing){if(old==null&&activeCount(state)>=10)throw new IllegalStateException("最多保留10个当前功能包");cache(review.pkg,control);}
        String installation=UUID.randomUUID().toString(),request=UUID.randomUUID().toString();long revision=old==null?1:Math.addExact(old.getLong("revision"),1);
        JSONObject identity=new JSONObject().put("goal",(review.removing?"卸载":"安装")+"功能包 "+review.id).put("functionalPack",true).put("packId",review.id).put("packInstallationId",installation).put("reviewDigest",review.digest).put("packageSha256",review.pkg==null?old.optString("packageSha256"):review.pkg.packageSha256);
        JSONObject task;synchronized(tasks){JSONObject unresolved=tasks.unresolvedCatalogOperation();if(unresolved!=null)throw new IllegalStateException("仍有原目录操作待核对："+unresolved.getString("taskId"));task=tasks.beginExternal("plugins","functional_pack",request,identity);tasks.status(request,"running","原功能包逐组件操作已登记；不会自动回滚或重提");}
        JSONObject record=new JSONObject().put("id",review.id).put("installationId",installation).put("revision",revision).put("name",review.pkg==null?old.getString("name"):review.pkg.name).put("version",review.pkg==null?old.getString("version"):review.pkg.version).put("taskId",task.getString("id")).put("state",review.removing?"removing":"installing").put("history",archive(old)).put("updatedAt",System.currentTimeMillis()).put("removing",review.removing).put("plannedCount",review.parts.size()).put("selectionExplicit",review.selectionExplicit).put("excludedComponents",review.view().getJSONArray("excludedComponents")).put("wholePackageSelected",review.view().getBoolean("wholePackageSelected")).put("previousOwnership",old==null?new JSONArray():confirmedOwnership(old));
        if(!review.removing)record.put("packageSha256",review.pkg.packageSha256).put("packageByteSize",review.pkg.byteSize);
        replace(state,record);try{JsonFiles.write(stateFile,state);}catch(Exception uncertain){tasks.status(request,"reconciling","功能包ownership登记未确认；未派发任何组件，请只读原记录");return unknown(request);}
        changedTask.accept(request);JSONArray results=new JSONArray();boolean uncertain=false,stopped=false;
        for(Part part:review.parts){String operation=null;try{
            control.check();JSONObject args=new JSONObject().put("functionalPack",identity).put("componentKey",part.key).put("componentKind",part.kind).put("action",part.action).put("byteSha256",part.component==null?"":part.component.sha256).put("expected",new JSONObject(part.expected));
            operation=tasks.beginCapabilityOperation(request,request,"plugins","pack_component",args);JSONObject receipt;
            if(part.action.equals("install"))receipt=installPart(part,control);else receipt=removePart(part,control,request,operation);
            receipt.put("componentKey",part.key).put("componentKind",part.kind).put("action",part.action).put("packInstallationId",installation).put("replayAllowed",false);tasks.finishCapabilityOperation(request,operation,receipt);results.put(receipt);changedTask.accept(request);
            if(receipt.optString("status").equals("unknown")){uncertain=true;break;}
        }catch(Exception failure){if(operation!=null){JSONObject lost=new JSONObject().put("status","unknown").put("accepted",JSONObject.NULL).put("componentKey",part.key).put("componentKind",part.kind).put("action",part.action).put("message",safe(failure)).put("replayAllowed",false);try{tasks.finishCapabilityOperation(request,operation,lost);}catch(Exception ignored){}results.put(lost);uncertain=true;}else{stopped=true;results.put(new JSONObject().put("status","not_dispatched").put("accepted",false).put("componentKey",part.key).put("message",safe(failure)));}break;}}
        boolean partialSelection=!review.removing&&!review.view().getBoolean("wholePackageSelected");
        record.put("state",uncertain?"unknown":stopped||partialSelection?"partial":review.removing?"removed":"installed").put("updatedAt",System.currentTimeMillis());
        // Ownership is reconstructed only from this native TaskStore's actual receipts, never package/model JSON.
        record.put("components",confirmedOwnership(record));replace(state,record);
        try{JsonFiles.write(stateFile,state);cleanup(state);}catch(Exception lost){record.put("state","unknown");uncertain=true;}
        JSONObject receipt=new JSONObject().put("status",uncertain?"unknown":stopped||partialSelection?"partial":"completed").put("id",review.id).put("installationId",installation).put("taskId",request).put("components",results).put("excludedComponents",review.view().getJSONArray("excludedComponents")).put("selectedActionsCompleted",!uncertain&&!stopped).put("wholePackageInstalled",!review.removing&&!uncertain&&!stopped&&!partialSelection).put("recipeContentsComplete",!review.removing&&!uncertain&&!stopped&&!partialSelection).put("recipeExecutionAuthorized",false).put("atomic",false).put("replayAllowed",false).put("executed",false).put("permissionsRestored",false)
                .put("message",uncertain?"某组件或ownership回执未确认；仅查询原任务操作，不自动重提":stopped?"已停止后续组件；已确认部分保留，不自动回滚或重试":review.removing?"已仅处理原包确认组件；手工改版组件与原任务回执保留":partialSelection?"主人显选的独立组件已安装；其他组件仍受阻或未选择，整包/配方未宣称可用，分别保持停用/未批准":"准确组件已安装，分别保持停用/未批准；请到原管理入口配置和批准，未运行配方");
        tasks.updateExternal(request,receipt);changedTask.accept(request);return receipt;
    }
    private void checkPartsCurrent(List<Part> parts,RunControl control)throws Exception {
        for(Part part:parts){control.check();if(part.action.equals("already_absent")||part.action.equals("retain_changed"))continue;
            JSONObject expected=new JSONObject(part.expected),actual=null;
            if(part.kind.equals("executable_js")){String id=part.action.equals("install")?new JSONObject(part.inspection).getString("id"):expected.getString("id");actual=currentJs(id);}
            else if(part.kind.equals("declaration")){String id=part.action.equals("install")?new JSONObject(part.inspection).getJSONObject("preview").getString("id"):expected.getString("id");actual=currentDeclaration(id);}
            else if(expected.has("id"))actual=currentSkill(expected.getString("id"));
            if(!(actual==null?expected.length()==0:fingerprint(actual).equals(fingerprint(expected))))throw new SecurityException("组件安装、内容或授权已改变，请重新做整包预检："+part.key);
            if(part.declaration!=null)declarations.validateInspection(part.declaration,control);
        }
    }
    private JSONObject installPart(Part part,RunControl control)throws Exception {
        JSONObject receipt,component;String kind=part.kind;
        if(kind.equals("executable_js")){synchronized(executable){control.check();receipt=executable.installReviewed(part.jsInstall,part.jsInstall.reviewDigest,control);if(receipt.optString("status").equals("unknown"))return receipt;component=receipt.getJSONObject("plugin");}JSONObject previous=new JSONObject(part.expected);if(previous.has("installationId"))invalidate.accept(previous.getString("installationId"));}
        else if(kind.equals("declaration")){synchronized(plugins){control.check();declarations.validateInspection(part.declaration,control);receipt=declarations.importReviewed(part.component.open(),part.declaration,part.declaration.reviewDigest,control);if(receipt.optString("status").equals("unknown"))return receipt;component=receipt.getJSONObject("plugin");}}
        else{JSONObject inspection=new JSONObject(part.inspection),options=new JSONObject().put("source","functional-pack:"+part.key).put("inspectedDigest",inspection.getJSONObject("package").getString("digest")).put("requiredCapabilities",part.component.requiredCapabilities());JSONObject comparison=inspection.getJSONObject("comparison");if(comparison.has("id"))options.put("id",comparison.getString("id")).put("revision",comparison.getInt("revision")).put("expectedCurrentDigest",comparison.getString("expectedCurrentDigest"));control.check();receipt=skills.importPackage(scope(),part.component.open(),options);component=receipt.getJSONObject("skill");}
        return new JSONObject().put("status","installed").put("component",reference(part.key,kind,component)).put("byteSha256",part.component.sha256).put("approvalRequired",true).put("enabled",false).put("accepted",true);
    }
    private JSONObject removePart(Part part,RunControl control,String originalTask,String originalOperation)throws Exception {
        if(!part.action.equals("remove"))return new JSONObject().put("status",part.action).put("accepted",false).put("message",new JSONObject(part.inspection).optString("message"));
        JSONObject expected=new JSONObject(part.expected),receipt;String id=expected.getString("id");control.check();
        if(part.kind.equals("executable_js")){synchronized(executable){receipt=executable.removeReviewed(part.jsRemove,part.jsRemove.reviewDigest,control);}invalidate.accept(expected.getString("installationId"));if(receipt.optString("status").equals("unknown"))return receipt;}
        else if(part.kind.equals("declaration")){synchronized(plugins){JSONObject current=currentDeclaration(id);if(current==null||!fingerprint(current).equals(fingerprint(expected)))throw new SecurityException("声明安装或授权已变化，未删除当前组件");receipt=plugins.handle("remove",new JSONObject().put("id",id));}}
        else receipt=skills.deletePackageReviewed(scope(),id,expected.getInt("revision"),expected.getString("digest"),expected.optLong("executionGeneration",0),tasks.skillRemovalGuardForPreparedOperation(originalTask,originalOperation,control));
        return new JSONObject().put("status","removed").put("component",reference(part.key,part.kind,expected)).put("accepted",true).put("moduleReceipt",receipt);
    }
    private static JSONObject reference(String key,String kind,JSONObject row)throws Exception {JSONObject result=new JSONObject().put("key",key).put("kind",kind).put("id",row.getString("id"));if(kind.equals("skill_zip"))result.put("revision",row.getInt("revision")).put("digest",row.getString("digest"));else{result.put("installationId",row.getString("installationId"));if(kind.equals("executable_js"))result.put("sourceSha256",row.getString("sourceSha256"));else result.put("declarationDigest",PluginPackages.declarationDigest(row.getJSONObject("manifest")));}return result;}
    private JSONArray confirmedOwnership(JSONObject record)throws Exception {
        if(record.optString("state").equals("removed"))return new JSONArray();
        java.util.Map<String,JSONObject> ownership=new java.util.LinkedHashMap<>();java.util.Map<String,JSONArray> verifiedSources=new java.util.HashMap<>();JSONArray prior=record.optJSONArray("previousOwnership");
        if(prior!=null)for(int i=0;i<prior.length();i++){
            JSONObject ref=prior.getJSONObject(i);String sourceKey=ref.getString("sourceTaskId")+":"+ref.getString("sourcePackInstallationId");JSONArray source=verifiedSources.get(sourceKey);if(source==null){source=taskOwnership(record.getString("id"),ref.getString("sourceTaskId"),ref.getString("sourcePackInstallationId"));verifiedSources.put(sourceKey,source);}boolean verified=false;
            for(int j=0;j<source.length();j++)if(sameReference(ref,source.getJSONObject(j)))verified=true;
            if(!verified)throw new SecurityException("保留组件缺少原TaskStore准确安装回执");ownership.put(ref.getString("key"),new JSONObject(ref.toString()));
        }
        String taskId=record.getString("taskId"),installation=record.getString("installationId");JSONObject task=originalTask(record.getString("id"),taskId);JSONArray operations=task.getJSONArray("operations");
        for(int i=0;i<operations.length();i++){JSONObject op=operations.getJSONObject(i),args=op.getJSONObject("arguments"),identity=args.optJSONObject("functionalPack"),receipt=op.optJSONObject("receipt");
            if(!op.getString("tool").equals("plugins_pack_component")||identity==null||!installation.equals(identity.optString("packInstallationId"))||!record.getString("id").equals(identity.optString("packId"))||!op.getString("state").equals("receipt")||receipt==null)continue;
            String key=args.getString("componentKey"),status=receipt.optString("status");if(status.matches("removed|already_absent|retain_changed")){ownership.remove(key);continue;}if(!status.equals("installed"))continue;
            JSONObject component=receipt.optJSONObject("component");if(component==null||!key.equals(component.optString("key"))||!args.getString("componentKind").equals(component.optString("kind")))throw new SecurityException("原组件回执身份不符");ownership.put(key,new JSONObject(component.toString()).put("sourceTaskId",taskId).put("sourcePackInstallationId",installation));
        }
        JSONArray result=new JSONArray();for(JSONObject ref:ownership.values())result.put(ref);return result;
    }
    private JSONObject originalTask(String packId,String taskId)throws Exception {
        JSONObject task=tasks.get(taskId,false);
        if(task==null||!"owner".equals(task.optString("actorId"))||!"private".equals(task.optString("spaceId"))||!"plugins".equals(task.optString("feature"))||!"functional_pack".equals(task.optString("command")))throw new SecurityException("功能包ownership缺少原生TaskStore操作来源");return task;
    }
    private JSONArray taskOwnership(String packId,String taskId,String installation)throws Exception {
        JSONArray result=new JSONArray(),operations=originalTask(packId,taskId).getJSONArray("operations");
        for(int i=0;i<operations.length();i++){JSONObject op=operations.getJSONObject(i),args=op.getJSONObject("arguments"),identity=args.optJSONObject("functionalPack"),receipt=op.optJSONObject("receipt");
            if(!op.getString("tool").equals("plugins_pack_component")||identity==null||!installation.equals(identity.optString("packInstallationId"))||!packId.equals(identity.optString("packId"))||!op.getString("state").equals("receipt")||receipt==null||!receipt.optString("status").equals("installed"))continue;
            JSONObject component=receipt.optJSONObject("component");if(component==null||!args.getString("componentKey").equals(component.optString("key"))||!args.getString("componentKind").equals(component.optString("kind")))throw new SecurityException("原组件回执身份不符");result.put(new JSONObject(component.toString()).put("sourceTaskId",taskId).put("sourcePackInstallationId",installation));
        }return result;
    }
    private static boolean sameReference(JSONObject left,JSONObject right)throws Exception {JSONObject a=new JSONObject(left.toString()),b=new JSONObject(right.toString());for(String key:new String[]{"sourceTaskId","sourcePackInstallationId"}){a.remove(key);b.remove(key);}return fingerprint(a).equals(fingerprint(b));}
    /** Query original operations only. It can settle local ownership metadata, never run a component again. */
    private synchronized JSONObject reconcile(String id)throws Exception {
        open();JSONObject state=read(),record=record(state,id),task=originalTask(id,record.getString("taskId"));JSONArray operations=task.getJSONArray("operations");int actual=0;boolean unknown=false;
        for(int i=0;i<operations.length();i++){JSONObject op=operations.getJSONObject(i),identity=op.getJSONObject("arguments").optJSONObject("functionalPack");if(identity==null||!record.getString("installationId").equals(identity.optString("packInstallationId")))continue;actual++;if(!op.getString("state").equals("receipt"))unknown=true;}
        if(!unknown){record.put("components",confirmedOwnership(record)).put("state",actual<record.optInt("plannedCount")||record.optJSONArray("excludedComponents")!=null&&record.getJSONArray("excludedComponents").length()>0?"partial":record.optBoolean("removing")?"removed":"installed");replace(state,record);JsonFiles.write(stateFile,state);cleanup(state);tasks.updateExternal(record.getString("taskId"),new JSONObject().put("status",record.getString("state").equals("partial")?"partial":"completed").put("message","已只读核对原组件回执与ownership登记；未重新派发，未推定业务结果"));changedTask.accept(record.getString("taskId"));}
        return publicRecord(record).put("reconciled",!unknown).put("originalTask",task).put("replayAllowed",false).put("executed",false);
    }
    private JSONObject owned(JSONObject old,String key)throws Exception {if(old==null||old.optString("state").equals("removed"))return null;JSONArray rows=confirmedOwnership(old);for(int i=0;i<rows.length();i++)if(key.equals(rows.getJSONObject(i).getString("key")))return rows.getJSONObject(i);return null;}
    public synchronized JSONObject snapshot()throws Exception {open();JSONObject state=read();JSONArray rows=new JSONArray(),all=state.getJSONArray("packs");int removed=0;for(int i=all.length()-1;i>=0;i--){JSONObject row=all.getJSONObject(i);if(row.optString("state").equals("removed")&&removed++>=10)continue;JSONObject view=publicRecord(row);view.put("components",confirmedOwnership(row));rows.put(view);}return new JSONObject().put("packs",rows).put("retainedRemovedLimit",10).put("executed",false).put("recipeExecution","instruction_only_original_runner");}
    private JSONObject currentJs(String id)throws Exception{return byId(executable.snapshot().getJSONArray("plugins"),id);}
    private JSONObject currentDeclaration(String id)throws Exception{return byId(plugins.snapshot().getJSONArray("plugins"),id);}
    private JSONObject currentSkill(String id)throws Exception{try{return skills.handle("get",new JSONObject().put("scope",scope()).put("id",id)).getJSONObject("skill");}catch(IllegalArgumentException missing){return null;}}
    private static JSONObject byId(JSONArray rows,String id)throws Exception{for(int i=0;i<rows.length();i++)if(id.equals(rows.getJSONObject(i).getString("id")))return rows.getJSONObject(i);return null;}
    private JSONObject read()throws Exception{JSONObject state=JsonFiles.read(stateFile);if(state.length()==0)return new JSONObject().put("format",STORE).put("packs",new JSONArray());if(!STORE.equals(state.getString("format"))||state.getJSONArray("packs").length()>50)throw new SecurityException("功能包ownership记录无效");Set<String> ids=new HashSet<>();for(int i=0;i<state.getJSONArray("packs").length();i++){JSONObject row=state.getJSONArray("packs").getJSONObject(i);if(!ids.add(row.getString("id"))||row.getLong("revision")<1||!row.getString("installationId").matches("[a-f0-9-]{36}")||!row.getString("taskId").matches("[a-f0-9-]{36}")||row.getJSONArray("history").length()>3)throw new SecurityException("功能包ownership身份无效");}return state;}
    private static JSONObject find(JSONObject state,String id)throws Exception{return byId(state.getJSONArray("packs"),id);}
    private static JSONObject record(JSONObject state,String id)throws Exception{JSONObject row=find(state,id);if(row==null)throw new IllegalArgumentException("功能包不在本机原登记");return row;}
    private static JSONObject history(JSONObject record,String installation)throws Exception{JSONArray history=record.getJSONArray("history");for(int i=0;i<history.length();i++)if(installation.equals(history.getJSONObject(i).getString("installationId")))return history.getJSONObject(i);throw new IllegalArgumentException("此历史原包已不在保留范围");}
    private static JSONArray archive(JSONObject previous)throws Exception{JSONArray rows=new JSONArray();if(previous==null||previous.optString("state").equals("removed"))return rows;if(previous.has("packageSha256"))rows.put(new JSONObject().put("installationId",previous.getString("installationId")).put("version",previous.getString("version")).put("packageSha256",previous.getString("packageSha256")).put("packageByteSize",previous.getLong("packageByteSize")));JSONArray older=previous.getJSONArray("history");for(int i=0;i<older.length()&&rows.length()<3;i++)rows.put(older.getJSONObject(i));return rows;}
    private static JSONObject publicRecord(JSONObject row)throws Exception{JSONObject publicRow=new JSONObject(row.toString());publicRow.remove("previousOwnership");return publicRow.put("permissionsRetained",false).put("recipeExecuted",false);}
    private static void expected(JSONObject row,String installation,long revision)throws Exception{if(row==null?!(installation.isEmpty()&&revision==0):!installation.equals(row.getString("installationId"))||revision!=row.getLong("revision"))throw new SecurityException("功能包已改变，请重新预检");}
    private static String fingerprint(JSONObject data)throws Exception{return TaskStore.capabilityFingerprint(data);}
    private static void replace(JSONObject state,JSONObject record)throws Exception{JSONArray rows=state.getJSONArray("packs"),next=new JSONArray();for(int i=0;i<rows.length();i++)if(!record.getString("id").equals(rows.getJSONObject(i).getString("id")))next.put(rows.getJSONObject(i));next.put(record);if(next.length()>50){JSONArray bounded=new JSONArray();int drop=next.length()-50;for(int i=0;i<next.length();i++)if(drop>0&&next.getJSONObject(i).optString("state").equals("removed")){drop--;continue;}else bounded.put(next.getJSONObject(i));next=bounded;}if(next.length()>50)throw new IllegalStateException("功能包原记录已满");state.put("packs",next);}
    private static int activeCount(JSONObject state)throws Exception{int count=0;JSONArray rows=state.getJSONArray("packs");for(int i=0;i<rows.length();i++)if(!rows.getJSONObject(i).getString("state").equals("removed"))count++;return count;}
    private File packageFile(String sha)throws Exception{if(!sha.matches("[a-f0-9]{64}")||Files.isSymbolicLink(packages.toPath()))throw new SecurityException("功能包原字节目录无效");File file=new File(packages,sha+".pack");if(Files.isSymbolicLink(file.toPath()))throw new SecurityException("功能包原文件不能是链接");return file;}
    private void cache(FunctionalPackage pkg,RunControl control)throws Exception{File file=packageFile(pkg.packageSha256);if(file.isFile()){readPackage(new JSONObject().put("packageSha256",pkg.packageSha256).put("packageByteSize",pkg.byteSize),control);return;}if(!packages.isDirectory()&&!packages.mkdirs())throw new IllegalStateException("无法保留准确功能包");long total=0;File[] existing=packages.listFiles();if(existing!=null)for(File item:existing)if(item.isFile())total=Math.addExact(total,item.length());if(total+pkg.byteSize>128L*1024*1024)throw new IllegalStateException("功能包准确字节缓存达到128MiB，请先核对并卸载不再需要的包");File pending=File.createTempFile("pending-",".tmp",packages);try{try(FileOutputStream output=new FileOutputStream(pending)){output.write(pkg.exactBytes());output.getFD().sync();}control.check();try{Files.move(pending.toPath(),file.toPath(),StandardCopyOption.ATOMIC_MOVE);}catch(java.nio.file.AtomicMoveNotSupportedException unsupported){Files.move(pending.toPath(),file.toPath());}}finally{Files.deleteIfExists(pending.toPath());}}
    private FunctionalPackage readPackage(JSONObject row,RunControl control)throws Exception{File file=packageFile(row.getString("packageSha256"));if(!file.isFile()||file.length()!=row.getLong("packageByteSize")||file.length()>FunctionalPackage.MAX_BYTES)throw new SecurityException("功能包原字节缺失或大小已变");FunctionalPackage pkg;try(InputStream input=Files.newInputStream(file.toPath())){pkg=FunctionalPackage.read(input,control);}if(!pkg.packageSha256.equals(row.getString("packageSha256")))throw new SecurityException("功能包原字节摘要已变化");return pkg;}
    private void cleanup(JSONObject state){try{Set<String> retained=new HashSet<>();JSONArray rows=state.getJSONArray("packs");for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);if(row.optString("state").equals("removed"))continue;if(row.has("packageSha256"))retained.add(row.getString("packageSha256")+".pack");JSONArray history=row.getJSONArray("history");for(int j=0;j<history.length();j++)retained.add(history.getJSONObject(j).getString("packageSha256")+".pack");}File[] files=packages.listFiles();if(files!=null)for(File file:files)if(file.isFile()&&file.getName().matches("[a-f0-9]{64}\\.pack")&&!retained.contains(file.getName())&&!Files.isSymbolicLink(file.toPath()))Files.deleteIfExists(file.toPath());}catch(Exception ignored){/* Confirmed module receipts are never replayed because cache cleanup failed. */}}
    private static JSONObject unknown(String task)throws Exception{return new JSONObject().put("status","unknown").put("taskId",task).put("replayAllowed",false).put("executed",false).put("message","原登记结果未确认；只可查询原任务，未重提组件写入");}
    private static String safe(Exception failure){String text=failure.getMessage();return text==null?failure.getClass().getSimpleName():text.substring(0,Math.min(text.length(),240));}
    @Override public void close(){if(closed.compareAndSet(false,true)){for(Page page:new ArrayList<>(pages))page.close();pages.clear();}}
}
