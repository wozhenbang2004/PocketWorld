package dev.pocketengine.features.local.project;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.assistant.AssistantModule;
import dev.pocketengine.features.local.LocalModule;
import dev.pocketengine.store.TaskStore;
import dev.pocketengine.store.OwnerWorkTask;
import org.json.JSONObject;
import org.json.JSONArray;

/** Native owner selection narrows the existing assistant admission; it creates no runner or grant. */
public final class ProjectOwnerWorkflows {
    private static final String BUILTIN_VITE_STARTER="builtin_vite_starter";
    private final LocalModule local;private final TaskStore tasks;private final AssistantModule assistant;
    private final ThreadLocal<ProjectConventionDelivery.Picked> ownerConvention=new ThreadLocal<>();
    private final ThreadLocal<JSONObject> selection=new ThreadLocal<>();
    private final ThreadLocal<java.util.function.BooleanSupplier> ownerStartFence=new ThreadLocal<>();
    private final ThreadLocal<dev.pocketengine.features.teams.TeamProjectRoundHost.Admission> teamRound=new ThreadLocal<>();
    public ProjectOwnerWorkflows(LocalModule local,TaskStore tasks,AssistantModule assistant){this.local=java.util.Objects.requireNonNull(local);this.tasks=java.util.Objects.requireNonNull(tasks);this.assistant=java.util.Objects.requireNonNull(assistant);}
    @FunctionalInterface public interface RoundContinuationExecutor {JSONObject resume(dev.pocketengine.features.teams.TeamProjectRoundHost.Continuation original)throws Exception;}
    private volatile RoundContinuationExecutor roundContinuationExecutor;
    private final ThreadLocal<TaskStore.OwnerWorkPreparation> butlerPreparation=new ThreadLocal<>();
    public synchronized void setRoundContinuationExecutor(RoundContinuationExecutor original){if(roundContinuationExecutor!=null)throw new IllegalStateException("原round继续宿主已挂载");roundContinuationExecutor=java.util.Objects.requireNonNull(original);}
    public JSONObject continueTeamRound(dev.pocketengine.features.teams.TeamProjectRoundHost.Continuation original)throws Exception{RoundContinuationExecutor nativeHost=roundContinuationExecutor;if(nativeHost==null)throw new IllegalStateException("原native继续入口尚未挂载；没有新建run或更换预算");return nativeHost.resume(java.util.Objects.requireNonNull(original));}
    public JSONObject createProject(JSONObject input)throws Exception{return local.createProjectForOwner(input.getString("name"),input.getString("template"));}
    public JSONObject openProject(JSONObject input)throws Exception{return local.openProjectForOwner(input.getString("projectId"));}
    /** Read-only private choices. A team, Skill or unpublished Git copy is not
     * silently adopted as the owner's ordinary development workspace. */
    public JSONObject inspectForOwner(String project)throws Exception {return inspectForOwner(project,true);}
    public JSONObject inspectForOwner(String project,boolean includeRuntime)throws Exception {
        ProjectBuildPlan.identity(project);local.requireAvailableDraftProjectForOwner(project);JSONArray available=new JSONArray(),rows=local.ownerCheckoutRecords();
        for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);if(!project.equals(row.optString("projectId"))||!ordinaryCheckout(row))continue;
            String checkout=row.getString("id");
            JSONObject choice=new JSONObject().put("checkoutId",checkout).put("projectId",project).put("name",row.optString("name")).put("createdAt",row.optLong("createdAt"))
                .put("draftWorkingCopy",row.optJSONObject(OwnerDraftWorkCopy.FIELD)!=null)
                .put("taskPackageSource",ProjectTaskSourceRecord.hasPublishedIdentity(row))
                .put("hostSource",row.optJSONObject("sourceImport")!=null&&"host_saved_snapshot".equals(row.getJSONObject("sourceImport").opt("sourceKind")));
            if(local.ownerDependencyCopyEditorForOwner(row,project,checkout))choice.put(LocalModule.OWNER_DEPENDENCY_COPY_EDITOR,true);
            available.put(choice);}
        JSONObject picked=new JSONObject().put("projectId",project).put("checkouts",available).put("executionAuthorized",false);
        return includeRuntime?picked.put("runtime",local.projectRuntimeForHost("managed_process_v1")):picked;
    }
    private static boolean ordinaryCheckout(JSONObject value){return OwnerDraftFirstBuild.ordinaryCheckout(value);}
    public JSONObject sourceForOwner(String project,String checkout)throws Exception {return sourceForOwner(project,checkout,false);}
    public JSONObject sourceForOwner(String project,String checkout,boolean observeTooling)throws Exception {
        return sourceForOwner(project,checkout,observeTooling,null,null);
    }
    /** Compare only an existing native save receipt against the manifest already read for the selected copy. */
    public JSONObject sourceAfterSaveForOwner(String project,String checkout,String path,String savedHash)throws Exception {
        if(path==null||savedHash==null||!savedHash.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("没有可核对的原保存回执");
        return sourceForOwner(project,checkout,true,path,savedHash);
    }
    private JSONObject sourceForOwner(String project,String checkout,boolean observeTooling,String savedPath,String savedHash)throws Exception {
        ProjectBuildPlan.identity(project);ProjectBuildPlan.identity(checkout);JSONObject row=local.projectCheckoutForHost(checkout);
        if(!project.equals(row.getString("projectId"))||!ordinaryCheckout(row))throw new SecurityException("请选择此项目的普通私人副本");
        JSONObject snapshot=local.handle("export",new JSONObject().put("checkoutId",checkout)),source=ProjectSources.describe(snapshot);
        JSONObject wrapper=ProjectAndroidPreflight.ownerWrapper(snapshot,source);
        if(!project.equals(source.getString("projectId"))||!checkout.equals(source.getString("checkoutId")))throw new SecurityException("原项目副本已变化");
        if(observeTooling){
            JSONObject metadata=snapshot.getJSONObject("checkout"),archiveRoot=ProjectArchiveRoot.checkout(metadata,source.getJSONArray("files"));
            source.putOpt("retainedArchiveRoot",archiveRoot);
            JSONObject origin=metadata.optJSONObject("sourceImport");
            boolean taskFork=ordinaryCheckout(metadata)&&ProjectTaskSourceRecord.hasPublishedIdentity(metadata);
            boolean starter=origin!=null&&BUILTIN_VITE_STARTER.equals(origin.opt("sourceKind"));
            if(origin!=null&&"new_project".equals(origin.opt("mode"))&&Boolean.FALSE.equals(origin.opt("executionAuthorityRestored"))&&
                (starter||"external_zip".equals(origin.opt("sourceKind"))||"saf_tree".equals(origin.opt("sourceKind"))||taskFork)){
                String root=archiveRoot==null?"":archiveRoot.getString("directory");
                JSONObject entry=ProjectArchiveRoot.firstWebFile(snapshot.getJSONArray("files"),root);
                if(entry!=null&&(!taskFork||unchangedForkStarter(metadata,entry))&&(!starter||builtInViteEntry(metadata,origin,entry))){
                    if(starter)entry.put("sourceKind",BUILTIN_VITE_STARTER);
                    source.put("firstImportedWebFile",entry.put("projectId",project).put("checkoutId",checkout)
                        .put("sourceDigest",source.getString("sourceDigest")));
                }
            }
            try{source.put("checkoutTooling",ProjectCheckoutTooling.describe(snapshot,source,local.projectRuntimeForHost("managed_process_v1")).put("androidClues",wrapper!=null));}
            catch(Exception unavailable){/* Optional observation must not block reading/editing a verified checkout. */}
            JSONObject copy=metadata.optJSONObject(OwnerDraftWorkCopy.FIELD);
            if(copy!=null&&copy.optString("initialCopyDigest").matches("[a-f0-9]{64}")&&
                !source.getString("sourceDigest").equals(copy.opt("initialCopyDigest"))){
                source.put("editedDraftCopy",true);
                try{source.putOpt("editedDraftBuild",tasks.editedBuildForDraftCopy(copy.getString("workIdentity"),project,checkout,source.getString("sourceDigest")));}
                catch(Exception unavailable){source.put("editedDraftBuildUnavailable",true);}
            }
            if(copy==null){
                var state=OwnerDraftFirstBuild.existingBuildState(snapshot);
                if("available".equals(state.state())){
                    source.put("existingNodeBuild",true);
                    if(!state.missingLock().isEmpty()){
                        source.put("existingNodeBuildMissingLock",state.missingLock());
                        try{if(local.canChooseSuppliedViteLockForOwner(project,checkout,snapshot))source.put("canChooseSuppliedViteLock",true);}
                        catch(Exception unavailable){/* An optional file intake is not general source eligibility. */}
                    }
                    try{source.putOpt("existingNodeBuildTask",tasks.existingNodeBuildForOwner(project,checkout,source.getString("sourceDigest")));}
                    catch(Exception unavailable){source.put("existingNodeBuildUnavailable",true);}
                    try{source.putOpt("directNodeBuildWork",tasks.directNodeWorkForSource(project,checkout,source.getString("sourceDigest")));}
                    catch(Exception unavailable){source.put("directNodeBuildWorkUnavailable",true);}
                    try{
                        var inspected=OriginalNodeWebOutput.inspectDirectViteSource(snapshot);
                        if(inspected!=null){
                            if(inspected.supported()!=null)source.put("directBuildKind","vite");
                            else source.putOpt("directReactTypeScriptLockNotice",inspected.lockNotice());
                        }
                    }catch(Exception unavailable){/* Source shape is optional, not build authority. */}
                    try{source.putOpt("directViteBuildWork",tasks.directViteWorkForSource(project,checkout,source.getString("sourceDigest")));}
                    catch(Exception unavailable){source.put("directViteBuildWorkUnavailable",true);}
                }else if(!state.state().isEmpty())source.put("existingNodeBuildBlocked",state.state());
            }else if(ordinaryCheckout(metadata)&&OwnerDraftWorkCopy.matchesRetainedPublication(metadata,copy)){
                // Same observed source revision; do not turn an independent draft copy into an ordinary build.
                String missingLock=OwnerDraftFirstBuild.existingBuildState(snapshot).missingLock();
                if(!missingLock.isEmpty())source.put("draftCopyNodeBuildMissingLock",missingLock);
            }
        }
        if(savedPath!=null){
            JSONArray manifest=source.getJSONArray("files");boolean matches=false;
            for(int i=0;i<manifest.length();i++){
                JSONObject file=manifest.getJSONObject(i);
                if(savedPath.equals(file.getString("path"))){matches=savedHash.equals(file.opt("sha256"));break;}
            }
            source.put("savedEditInSource",new JSONObject().put("path",savedPath).put("savedHash",savedHash)
                .put("sourceDigest",source.getString("sourceDigest")).put("matches",matches));
            if(matches&&local.ownerDependencyCopyEditorForOwner(snapshot.getJSONObject("checkout"),project,checkout))
                source.put(LocalModule.OWNER_DEPENDENCY_COPY_EDITOR,true);
        }
        source.remove("files");
        if(wrapper!=null)source.put("androidGradleWrapper",wrapper);
        return source;
    }
    /** The stored import inventory proves the authored path; the current inspected
     * snapshot pins its actual bytes, which may include the owner's later edits. */
    private static boolean builtInViteEntry(JSONObject checkout,JSONObject origin,JSONObject entry){
        if(!"index.html".equals(entry.opt("path"))||!"".equals(entry.opt("root"))||!ordinaryCheckout(checkout)||
            checkout.has(OwnerDraftWorkCopy.FIELD)||checkout.has("collectedFromCheckoutId")||checkout.has("collectionOperationId")||
            !origin.optString("clientId").matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")||
            !origin.optString("packageSha256").matches("[a-f0-9]{64}")||!origin.optString("manifestIdentity").matches("[a-f0-9]{64}")||
            !origin.optString("sourceEvidenceSha256").matches("[a-f0-9]{64}"))return false;
        JSONArray published=checkout.optJSONArray("files");
        if(published==null||published.length()>ProjectSources.MAX_FILES)return false;
        JSONObject authored=null;
        for(int i=0;i<published.length();i++){
            JSONObject file=published.optJSONObject(i);if(file==null)return false;
            if(!"index.html".equals(file.opt("path")))continue;
            if(authored!=null)return false;
            authored=file;
        }
        return authored!=null&&authored.optInt("sourceVersion",-1)==0&&authored.optLong("bytes",-1)>0&&
            authored.optString("sha256").matches("[a-f0-9]{64}");
    }
    /** Open a starter from the actual new copy only if its published baseline still matches.
     * Any change, ambiguity or missing proof leaves the ordinary exact file list. */
    private static boolean unchangedForkStarter(JSONObject checkout,JSONObject entry){
        JSONArray baseline=checkout.optJSONArray("files");
        if(baseline==null||baseline.length()>ProjectSources.MAX_FILES)return false;
        String path=entry.optString("path");JSONObject matched=null;
        for(int i=0;i<baseline.length();i++){
            JSONObject item=baseline.optJSONObject(i);if(item==null)return false;
            Object name=item.opt("path");
            // Don't substitute package.json if the original root index disappeared or became unreadable.
            if("index.html".equals(name)&&!path.equals(name))return false;
            if(path.equals(name)){if(matched!=null)return false;matched=item;}
        }
        return matched!=null&&matched.optInt("sourceVersion",-1)==0&&entry.optString("sha256").equals(matched.opt("sha256"));
    }
    /** Pin one actual private source tree; a normal checkout does not acquire draft provenance. */
    public JSONObject reviewNodeBuildForOwner(String project,String checkout)throws Exception {
        return reviewNodeBuild(project,checkout,false,null).preview();
    }
    public record DirectNodeBuildReview(JSONObject preview,OwnerDirectNodeBuild.Review offer,
            OwnerDirectNodeDependencies.Review dependencyOffer,JSONObject dependencyWork,
            OwnerDirectNodeBuild.Review viteOffer,JSONObject viteWork,LocalModule.DependencyCheckoutOffer dependencyCopy){}
    public DirectNodeBuildReview reviewDirectNodeBuildForOwner(String project,String checkout)throws Exception {
        return reviewNodeBuild(project,checkout,true,null);
    }
    /** The old ID is captured by the original visible owner Work before its native window closes. */
    public DirectNodeBuildReview reviewDirectNodeBuildForOwner(String project,String checkout,String originalDependencies)throws Exception {
        return reviewNodeBuild(project,checkout,true,originalDependencies);
    }
    private DirectNodeBuildReview reviewNodeBuild(String project,String checkout,boolean offerDirect,String originalDependencies)throws Exception {
        requireOrdinaryFileOwner(project,checkout);JSONObject mark=local.projectCheckoutForHost(checkout).optJSONObject(OwnerDraftWorkCopy.FIELD);
        OwnerDraftWorkCopy.Seed seed=null;JSONObject copy=null;
        if(mark!=null){
            seed=tasks.ownerDraftWorkCopySeed(mark.getString("taskId"),mark.getString("draftId"),mark.getString("revisionId"),mark.getString("sha256"));
            if(!seed.projectId.equals(project)||!seed.checkoutId.equals(checkout))throw new SecurityException("原稿没有建立这个工作副本");
            copy=local.findDraftWorkCopy(seed);
            if(!"ready".equals(copy.opt("status"))||!Boolean.FALSE.equals(copy.opt("recyclable")))
                throw new SecurityException("请先打开这份原稿的实际独立副本。");
        }
        JSONObject snapshot=local.handle("export",new JSONObject().put("checkoutId",checkout));
        JSONObject actual=snapshot.getJSONObject("checkout");
        if(!project.equals(actual.opt("projectId"))||!checkout.equals(actual.opt("id"))||!ordinaryCheckout(actual)||
            !ProjectSources.canonical(mark).equals(ProjectSources.canonical(actual.optJSONObject(OwnerDraftWorkCopy.FIELD))))
            throw new SecurityException("原工作副本已变化，请重新读取");
        if(seed==null)OwnerDraftFirstBuild.requireExistingConfiguration(snapshot);
        JSONObject source=ProjectSources.describe(snapshot);String digest=source.getString("sourceDigest");
        JSONObject plan=ProjectBuildPlan.create(local,new JSONObject().put("checkoutId",checkout).put("runtimeId","managed_process_v1")
            .put("intent","build").put("ecosystem","node").put("expectedSourceDigest",digest));
        JSONObject pin=seed==null?OwnerDraftFirstBuild.existingPin(project,checkout,plan):OwnerDraftFirstBuild.pin(seed,copy,plan);
        JSONObject runtime=local.projectRuntimeForHost("managed_process_v1");
        OwnerDraftFirstBuild.requireTooling(pin,runtime);
        JSONObject scripts=OwnerDraftFirstBuild.scripts(snapshot,pin);
        OwnerDraftFirstBuild.chooseDependencies(pin,snapshot,runtime);
        JSONObject deps=OwnerDraftFirstBuild.dependencies(pin);
        JSONObject result=new JSONObject().put("pin",pin).put("scripts",scripts).putOpt("dependencies",deps);
        if(deps!=null){
            result.put("dependencyState",local.draftDependencyFilesReady(checkout,deps)?"current_packages_checked":"needs_lock_review");
            if(seed!=null&&!OwnerDraftFirstBuild.edited(pin))result.putOpt("previousVerification",tasks.classicFirstBuildForDraft(seed));
        }
        boolean nativeNpm=offerDirect&&seed==null&&deps!=null&&"npm".equals(pin.opt("manager"));
        JSONObject exact=nativeNpm?ProjectDevelopment.exact(plan,OwnerDirectNodeBuild.step(plan)):null;
        boolean directCompiler=nativeNpm&&OwnerDirectNodeBuild.selectedOutput(exact,snapshot,false)!=null;
        var web=nativeNpm&&!directCompiler?OriginalNodeWebOutput.directViteSource(exact,snapshot):null;
        boolean directVite=web!=null;
        boolean supported=directCompiler||directVite;
        if(directVite)result.put("directBuildKind","vite");
        JSONObject viteWork=directVite?tasks.directViteWorkForSource(project,checkout,digest):null;
        JSONObject dependencyWork=supported?tasks.directNodeDependenciesWorkForSource(project,checkout,digest):null;
        boolean ready=supported&&"current_packages_checked".equals(result.opt("dependencyState"))&&
                tasks.directNodeDependenciesPermitBuild(project,checkout,digest,plan.getString("runtimeIdentity"),deps);
        if(ready&&directVite&&viteWork==null)local.requireDirectViteBuildToolsForOwner(project,checkout,web);
        var offer=ready&&directCompiler?OwnerDirectNodeBuild.offer(local,plan,pin,snapshot,false):null;
        var viteOffer=ready&&directVite&&viteWork==null?OwnerDirectNodeBuild.offer(local,plan,pin,snapshot,true):null;
        OwnerDirectNodeDependencies.Review dependencyOffer=null;
        LocalModule.DependencyCheckoutOffer dependencyCopy=null;
        if(supported&&viteWork==null&&dependencyWork==null&&"needs_lock_review".equals(result.opt("dependencyState"))){
            if(local.directNodeDependencyRootAbsentForOwner(project,checkout))
                dependencyOffer=OwnerDirectNodeDependencies.offer(local,ProjectBuildPlan.lockedNodeDependencies(snapshot,plan.getString("runtimeIdentity"),deps));
            else if(directVite)dependencyCopy=local.reviewDependencyCheckoutForOwner(source,deps);
        }else if(originalDependencies!=null&&ready&&directVite&&dependencyWork==null){
            try{
                String identity=plan.getString("runtimeIdentity");
                if(viteOffer!=null||viteWork!=null&&"completed".equals(viteWork.opt("status"))&&
                        tasks.completedDirectViteForLockComparison(project,checkout,digest,identity)){
                    var previous=tasks.ownerDirectNodeLockComparison(originalDependencies,project,checkout,digest,identity,deps);
                    if(previous!=null)dependencyCopy=local.reviewDependencyCheckoutForOwner(source,deps,previous);
                }
            }catch(Exception unavailable){/* Optional historical evidence cannot change A's own build or result. */}
        }
        return new DirectNodeBuildReview(result,offer,dependencyOffer,dependencyWork,viteOffer,viteWork,dependencyCopy);
    }
    public JSONObject confirmDependencyCheckoutForOwner(dev.pocketengine.features.local.OwnerNodeDependencyCheckout review,
            java.util.function.BooleanSupplier current)throws Exception{return local.confirmDependencyCheckoutForOwner(review,current);}
    public JSONObject prepareCheckoutForOwner(String project,String request)throws Exception{return local.prepareDevelopmentCheckoutForOwner(project,request);}
    /** Project-level saved result shelf, separate from a work draft and from current runtime/tooling. */
    public JSONObject savedBuildWebForOwner(String project,String checkout,String cursor,RunControl control)throws Exception{
        control.check();JSONArray originals=inspectForOwner(project,false).getJSONArray("checkouts"),copies=new JSONArray();
        if(originals.length()>128)throw new IllegalStateException("此项目的私人副本超过本页可核对范围");
        boolean found=false;
        for(int i=0;i<originals.length();i++){
            JSONObject copy=originals.getJSONObject(i);String id=copy.getString("checkoutId");
            if(id.equals(checkout))found=true;
            copies.put(new JSONObject().put("id",id).put("name",copy.optString("name")).put("createdAt",copy.optLong("createdAt")));
        }
        if(checkout.isEmpty()){
            if(!cursor.isEmpty())throw new SecurityException("请保留分页所属的原副本");
            if(copies.length()==1)checkout=copies.getJSONObject(0).getString("id");
        }else if(!found)throw new SecurityException("所选的原私人副本已不可用，不改读其他副本");
        JSONObject page;
        if(checkout.isEmpty())page=new JSONObject().put("items",new JSONArray()).put("hasMore",false).put("nextCursor","").put("readLimited",false);
        else{
            requireOrdinaryFileOwner(project,checkout);page=tasks.savedBuildWebForOwner(project,checkout,cursor,control);
            requireOrdinaryFileOwner(project,checkout);
        }
        control.check();return page.put("format","pocketworld.saved-build-web.v1").put("projectId",project).put("checkoutId",checkout)
            .put("checkouts",copies).put("bodyRead",false).put("currentArtifactAvailabilityVerified",false)
            .put("readOnly",true).put("executionAuthorized",false);
    }
    /** Native owner-read fence for the existing checkout, including an already captured path listing. */
    public void requireOrdinaryFileOwner(String project,String checkout)throws Exception {
        ProjectBuildPlan.identity(project);ProjectBuildPlan.identity(checkout);JSONObject row=local.projectCheckoutForHost(checkout);
        if(!project.equals(row.getString("projectId"))||!ordinaryCheckout(row))throw new SecurityException("请选择此项目的普通私人副本");
    }
    /** Project -> historical original receipt. Does not restore a grant, connect, or prepare a new task. */
    public JSONObject taskSourceOriginForOwner(String project,String checkout,String expected)throws Exception {
        requireOrdinaryFileOwner(project,checkout);return local.privateTaskSourceOriginForOwner(project,checkout,expected);
    }
    public JSONObject hostSourceOriginForOwner(String project,String checkout,String expected)throws Exception {
        requireOrdinaryFileOwner(project,checkout);
        var origin=local.phoneHostOriginForOwner(project,checkout);
        if(origin==null){
            if(expected!=null)throw new SecurityException("此前核对的主机来源目前不可用");
            return new JSONObject().put("projectId",project).put("checkoutId",checkout).put("localSourceRecorded",false).put("taskVerified",false);
        }
        if(expected!=null&&!expected.equals(origin.digest()))throw new SecurityException("原来源已变化，请重新核对");
        JSONObject result=origin.summary(tasks.get(origin.task(),false));
        if(expected!=null&&!result.optBoolean("taskVerified"))throw new SecurityException("原私人主机委托和所选文件不再匹配，未打开其他任务");
        return result;
    }
    public JSONObject readChangedHostTextForOwner(String project,String checkout,String evidence,String path,String originalSha,String phoneSha,java.util.function.BooleanSupplier owner)throws Exception{
        requireOrdinaryFileOwner(project,checkout);return local.readChangedHostTextForOwner(project,checkout,evidence,path,originalSha,phoneSha,id->tasks.get(id,false),owner);
    }
    public JSONObject compareHostSourceForOwner(String project,String checkout,String evidence,java.util.function.BooleanSupplier current)throws Exception{
        requireOrdinaryFileOwner(project,checkout);return local.compareSavedHostSourceForOwner(project,checkout,evidence,current);
    }
    public JSONObject filesForOwner(String project,String checkout)throws Exception {requireOrdinaryFileOwner(project,checkout);return local.handle("changes",new JSONObject().put("checkoutId",checkout));}
    public JSONObject ordinaryEditorFilesForOwner(String project,String checkout)throws Exception {
        requireOrdinaryFileOwner(project,checkout);return local.listOrdinaryEditorFilesForOwner(project,checkout);
    }
    public CheckoutTextMatches findTextForOwner(String project,String checkout,String literal,CheckoutTextMatches previous,java.util.function.BooleanSupplier current)throws Exception{
        requireOrdinaryFileOwner(project,checkout);CheckoutTextMatches found=local.findDevelopmentTextForOwner(project,checkout,literal,previous,current);
        requireOrdinaryFileOwner(project,checkout);return found;
    }
    public JSONObject fileForOwner(String project,String checkout,String path)throws Exception {requireOrdinaryFileOwner(project,checkout);return local.readDevelopmentFileForOwner(project,checkout,path);}
    public dev.pocketengine.features.local.runtime.OwnerPythonRun.Review reviewOwnerPythonForOwner(ProjectFileDraft file,
            java.util.function.BooleanSupplier window)throws Exception{
        return reviewOwnerPythonForOwner(file,false,window);
    }
    public dev.pocketengine.features.local.runtime.OwnerPythonRun.Review reviewOwnerPythonForOwner(ProjectFileDraft file,
            boolean directory,java.util.function.BooleanSupplier window)throws Exception{
        if(!window.getAsBoolean())throw new SecurityException("原副本文件窗口已关闭");
        requireOrdinaryFileOwner(file.projectId,file.checkoutId);
        return local.reviewOwnerPythonForOwner(file,directory,()->{
            if(!window.getAsBoolean())throw new SecurityException("原副本文件窗口已变化，未使用其他代码");
        });
    }
    public dev.pocketengine.features.local.runtime.OwnerPythonRun.Review reviewOwnerPythonPackageForOwner(ProjectFileDraft file,
             java.util.List<CheckoutPathMatches.Hit> selected,java.util.function.BooleanSupplier window)throws Exception{
        if(!window.getAsBoolean())throw new SecurityException("原副本文件窗口已关闭");
        requireOrdinaryFileOwner(file.projectId,file.checkoutId);
        return local.reviewOwnerPythonPackageForOwner(file,selected,()->{
            if(!window.getAsBoolean())throw new SecurityException("原副本文件窗口已变化，未使用其他代码");
        });
    }

    public JSONObject saveFileForOwner(ProjectFileDraft.SaveIntent intent,java.util.function.BooleanSupplier current)throws Exception {requireOrdinaryFileOwner(intent.projectId,intent.checkoutId);return local.saveDevelopmentFileForOwner(intent,current);}
    public JSONObject saveOrdinaryEditorFileForOwner(ProjectFileDraft.SaveIntent intent,boolean createEmpty,java.util.function.BooleanSupplier current)throws Exception {
        requireOrdinaryFileOwner(intent.projectId,intent.checkoutId);return local.saveOrdinaryEditorFileForOwner(intent,createEmpty,current);
    }
    public JSONObject reviewFileImportForOwner(String project,String checkout,String path)throws Exception {
        requireOrdinaryFileOwner(project,checkout);JSONObject result=local.handle("reviewFile",new JSONObject().put("checkoutId",checkout).put("path",path));
        if(!ProjectTextImport.REVIEW.equals(result.optString("format")))throw new IllegalArgumentException("这份变更包含二进制文件，请使用原固定成果包审阅，未改成文字采纳");return result;
    }
    public JSONObject importFileForOwner(String project,String checkout,String path,JSONObject reviewed,java.util.function.BooleanSupplier current)throws Exception {requireOrdinaryFileOwner(project,checkout);return local.importDevelopmentFileForOwner(project,checkout,path,reviewed,current);}
    public JSONObject queryCheckoutForOwner(String project,String request)throws Exception{return local.queryDevelopmentCheckoutForOwner(project,request);}
    public JSONObject submissionForOwner(String project,String checkout,String client)throws Exception {
        ProjectBuildPlan.identity(project);ProjectBuildPlan.identity(checkout);ProjectBuildPlan.identity(client);
        JSONObject original=assistant.handle("getSubmission",new JSONObject().put("clientMessageId",client));
        if(!client.equals(original.optString("clientMessageId")))throw new SecurityException("不是原项目输入的回执");
        if(!original.optString("taskId").isEmpty()){
            JSONObject task=tasks.get(original.getString("taskId"),false),context=task==null?null:task.optJSONObject("developmentContext");
            if(task==null||context==null||!"owner".equals(task.optString("actorId"))||!"private".equals(task.optString("spaceId"))||task.optJSONObject("origin")!=null
                    ||!project.equals(context.optString("projectId"))||!checkout.equals(context.optString("checkoutId"))||!client.equals(context.optString("clientMessageId")))throw new SecurityException("原输入属于另一项目任务，不采纳这份回执");
        }
        return new JSONObject(original.toString());
    }
    /** Exact native owner selection for the existing Profile admission. Reading/exporting an
     * already selected checkout creates no task, command, grant or alternate project registry. */
    public ProfileProjectSelection reviewWorkProfileProject(String projectId,String checkoutId,String runtime)throws Exception {
        String project=ProjectBuildPlan.identity(projectId),checkout=ProjectBuildPlan.identity(checkoutId);
        local.projectRuntimeForHost(runtime);
        JSONObject actual=ProjectSources.describe(local.handle("export",new JSONObject().put("checkoutId",checkout)));
        if(!project.equals(actual.getString("projectId")))throw new SecurityException("原项目副本不属于所选项目");
        JSONObject context=new JSONObject().put("format","pocketworld.development-context.v1")
            .put("projectId",project).put("checkoutId",checkout).put("runtime",runtime)
            .put("initialSourceDigest",actual.getString("sourceDigest")).put("clientMessageId",java.util.UUID.randomUUID().toString())
            .put("filesAreUntrusted",true).put("authorityAdded",false);
        return new ProfileProjectSelection(this,context);
    }
    public static final class ProfileProjectSelection {
        private final ProjectOwnerWorkflows issuer;private final String context;private String boundTask;
        private ProfileProjectSelection(ProjectOwnerWorkflows issuer,JSONObject context){this.issuer=issuer;this.context=context.toString();}
        public void checkCurrent()throws Exception {
            JSONObject selected=new JSONObject(context);issuer.local.projectRuntimeForHost(selected.getString("runtime"));
            JSONObject actual=ProjectSources.describe(issuer.local.handle("export",new JSONObject().put("checkoutId",selected.getString("checkoutId"))));
            if(!selected.getString("projectId").equals(actual.getString("projectId"))||!selected.getString("initialSourceDigest").equals(actual.getString("sourceDigest")))throw new SecurityException("原项目或源码已变化，请重新核对项目选择；没有启动");
        }
        public JSONObject view()throws Exception {checkCurrent();return new JSONObject(context).put("executionAuthorized",false).put("commandsAuthorized",false);}
    }
    public void requireWorkProfileProject(ProfileProjectSelection selected)throws Exception {
        if(selected==null||selected.issuer!=this)throw new SecurityException("original_work_profile_project_selection_required");selected.checkCurrent();
    }
    /** One use, original preparing/zero-operation binding. The original SQL method remains the
     * only authority and rejects changing an existing task's project or renewing its budget. */
    public void bindWorkProfileProject(ProfileProjectSelection selected,JSONObject task,RunControl original)throws Exception {
        requireWorkProfileProject(selected);original.check();
        synchronized(selected){if(selected.boundTask!=null)throw new SecurityException("原项目选择已用于一项任务，请重新核对");selected.boundTask=task.getString("id");}
        tasks.bindDevelopmentContext(task.getString("id"),task.getString("runId"),task.getInt("coordinatorEpoch"),original,new JSONObject(selected.context));
    }
    /** Native presentation fence is checked again after the original Assistant
     * captures its model, before Engine binds/schedules this project's attempt. */
    public JSONObject startReviewedForOwner(JSONObject ownerInput,java.util.function.BooleanSupplier current)throws Exception {
        java.util.Objects.requireNonNull(current);if(ownerStartFence.get()!=null)throw new IllegalStateException("不能嵌套项目审阅");
        if(!current.getAsBoolean())throw new SecurityException("原项目或模型审阅已变化，没有进入启动");
        ownerStartFence.set(current);try{return start(ownerInput);}finally{ownerStartFence.remove();}
    }
    /** Owner's picked plain-text provenance only. The original start still takes the exact same owner contract and scopes. */
    public JSONObject startWithOwnerConvention(JSONObject input,java.util.function.BooleanSupplier current,ProjectConventionDelivery.Picked picked)throws Exception{
        if(picked==null||ownerConvention.get()!=null||butlerPreparation.get()!=null)throw new SecurityException("没有此原项目的单次手写约定或正在其他启动");
        ownerConvention.set(picked);try{return startReviewedForOwner(input,current);}finally{ownerConvention.remove();}
    }
    /** The saved proposal supplies provenance only. The actual owner still selects and reviews
     * the original checkout, source digest and runtime through the ordinary project admission. */
    public JSONObject startReviewedForOwner(JSONObject ownerInput,java.util.function.BooleanSupplier current,TaskStore.OwnerWorkPreparation preparation)throws Exception {
        java.util.Objects.requireNonNull(preparation).checkCurrent();JSONObject shown=preparation.view();
        if(!"project".equals(shown.getString("kind"))||!shown.getString("clientMessageId").equals(ownerInput.getString("clientMessageId"))
                ||!shown.getJSONObject("contract").getString("goal").equals(ownerInput.getString("goal")))throw new SecurityException("项目输入不属于这份原管家提案");
        if(butlerPreparation.get()!=null)throw new IllegalStateException("不能嵌套管家项目交接");
        butlerPreparation.set(preparation);
        try{return startReviewedForOwner(ownerInput,()->{try{preparation.checkCurrent();return current.getAsBoolean();}catch(Exception changed){return false;}});}
        finally{butlerPreparation.remove();}
    }
    public void checkStartFenceForHost(dev.pocketengine.core.ModelConfig actual)throws Exception{
        var fence=ownerStartFence.get();if(fence!=null&&!fence.getAsBoolean())throw new SecurityException("原项目窗口或模型已变化，没有分配项目执行");
        JSONObject chosen=selection.get();if(chosen!=null&&chosen.has(OwnerRevisionSource.FIELD)&&!OwnerRevisionRecipient.pin(actual).equals(chosen.opt("revisionRecipient")))throw new SecurityException("实际模型不是主人刚审阅的选稿目的地，没有启动");
    }
    /** Native owner text only. These requirements describe the requested result, not grants. */
    public static JSONObject deliveryContractForOwner(JSONObject input)throws Exception {
        Object value=input.opt("goal");
        if(!(value instanceof String)||((String)value).length()>12000||((String)value).indexOf('\u0000')>=0||((String)value).codePoints().allMatch(Character::isWhitespace))throw new IllegalArgumentException("请填写1至12000字的项目目标");
        JSONObject contract=new JSONObject().put("format",OwnerWorkTask.CONTRACT_FORMAT).put("revision",1).put("goal",((String)value).trim());
        for(String key:new String[]{"constraints","deliverable","completionCriteria"}){
            if(!input.has(key))continue;Object field=input.get(key);
            if(!(field instanceof String)||((String)field).length()>4000||((String)field).indexOf('\u0000')>=0)throw new IllegalArgumentException("项目交付要求每项最多4000字，且必须为文字");
            if(!((String)field).codePoints().allMatch(Character::isWhitespace))contract.put(key,field);
        }
        return contract;
    }
    private JSONObject selectedDeliveryContract(JSONObject input)throws Exception {
        var preparation=butlerPreparation.get();
        if(preparation==null)return deliveryContractForOwner(input);
        JSONObject original=preparation.view().getJSONObject("contract");
        for(String key:new String[]{"goal","constraints","deliverable","completionCriteria"})
            if(input.has(key)&&!ProjectSources.canonical(input.get(key)).equals(ProjectSources.canonical(original.opt(key))))throw new SecurityException("项目交付要求与原管家提案不同，请回到原提案修改");
        // A proposal keeps its own revision and full contract. It is not replaced
        // by the ordinary page's revision-one requirements.
        return new JSONObject(original.toString());
    }
    public JSONObject start(JSONObject ownerInput)throws Exception {
        String checkout=ProjectBuildPlan.identity(ownerInput.getString("checkoutId")),project=ProjectBuildPlan.identity(ownerInput.getString("projectId"));
        JSONObject contract=selectedDeliveryContract(ownerInput);
        String digest=ownerInput.getString("expectedSourceDigest"),client=ownerInput.getString("clientMessageId"),goal=contract.getString("goal");
        String runtime=ownerInput.getString("runtime");if(!ownerInput.has("draftOnly"))local.projectRuntimeForHost(runtime);
        if(!digest.matches("[a-f0-9]{64}")||!client.matches("[a-fA-F0-9-]{36}")||goal.codePoints().allMatch(Character::isWhitespace)||goal.length()>12000)throw new IllegalArgumentException("请选择真实项目、运行位置和1至12000字目标");
        JSONObject context=new JSONObject().put("format","pocketworld.development-context.v1").put("projectId",project).put("checkoutId",checkout).put("runtime",runtime)
                .put("initialSourceDigest",digest).put("clientMessageId",client).put("filesAreUntrusted",true).put("authorityAdded",false);
        if(ownerInput.has(OwnerDraftFirstBuild.FIELD)){
            if(ownerStartFence.get()==null||ownerConvention.get()!=null||butlerPreparation.get()!=null||
                ownerInput.has("draftOnly")||ownerInput.has(OwnerRevisionSource.FIELD))throw new SecurityException("构建必须由主人独立核对此副本版本，不能继承别的任务或项目约定");
            JSONObject pin=reviewNodeBuildForOwner(project,checkout).getJSONObject("pin");OwnerDraftFirstBuild.requireContext(pin,context);
            if(!OwnerDraftFirstBuild.goal(pin).equals(goal))throw new SecurityException("这不是已审阅的本机独立构建目标");
            if(!ProjectSources.canonical(pin).equals(ProjectSources.canonical(ownerInput.getJSONObject(OwnerDraftFirstBuild.FIELD))))throw new SecurityException("原稿副本或构建条件在审阅后已变化，没有提交");
            context.put(OwnerDraftFirstBuild.FIELD,pin);
        }
        if(ownerInput.has("draftOnly")){
            if(ownerInput.opt("draftOnly")!=Boolean.TRUE||butlerPreparation.get()==null||ownerStartFence.get()==null||
                !"managed_process_v1".equals(runtime))throw new SecurityException("独立改稿只接受机主刚确认的原管家项目审阅");
            context.put("draftOnly",true);
        }
        if(ownerInput.has(OwnerRevisionSource.FIELD)){
            if(!ProjectEditDraft.selected(context)||butlerPreparation.get()==null||ownerStartFence.get()==null)throw new SecurityException("已存稿不能独立于新原提案的主人审阅启动");
            JSONObject ref=OwnerRevisionSource.pin(ownerInput.getJSONObject(OwnerRevisionSource.FIELD)),v=tasks.reviewOwnerProjectRevision(butlerPreparation.get(),ref);
            if(!project.equals(v.opt("projectId"))||!checkout.equals(v.opt("checkoutId"))||!digest.equals(v.opt("sourceDigest")))throw new SecurityException("原提案的起始稿不能更换");
            String recipient=ownerInput.getString("revisionRecipient");if(!recipient.matches("[a-f0-9]{64}"))throw new SecurityException("请重新核对这份稿的实际接收模型");
            context.put(OwnerRevisionSource.FIELD,ref).put("revisionRecipient",recipient);
        }
        if(butlerPreparation.get()==null)context.put("deliveryContract",contract);
        JSONObject prior=null;try{prior=assistant.handle("getSubmission",new JSONObject().put("clientMessageId",client));}catch(IllegalArgumentException absent){}
        if(prior!=null){verifyAcknowledgement(prior,context);return prior;}
        if(!context.has(OwnerRevisionSource.FIELD)){JSONObject actual=ProjectSources.describe(local.handle("export",new JSONObject().put("checkoutId",checkout)));
        if(!project.equals(actual.getString("projectId"))||!digest.equals(actual.getString("sourceDigest")))throw new SecurityException("选择后的项目源码已变化，请重新核对再开始");}
        if(selection.get()!=null)throw new IllegalStateException("不能嵌套启动项目任务");selection.set(context);
        try{
            // sendNative synchronously calls the existing NativeInputHost before scheduling its sole runner.
            JSONObject message=new JSONObject().put("clientMessageId",client).put("message",goal).put("mode","queue");
            if(context.has(OwnerRevisionSource.FIELD)||context.has(OwnerDraftFirstBuild.FIELD))message.put("firstMessageWithoutPersonalMemory",true).put("excludeThisInputFromLearning",true).put("firstTaskWithoutDefaultWrites",true);
            JSONObject ack=assistant.handle("send",message);
            if(ack.optString("status").matches("accepted|queued"))verifyAcknowledgement(ack,context);
            return ack;
        }finally{selection.remove();}
    }
    /** Typed native owner submission, not JSON or a ThreadLocal authorization carrier. */
    public static final class NativeStart {
        private final ProjectOwnerWorkflows issuer;private final String context;
        private final dev.pocketengine.features.memory.ProjectMemoryAccess.Consent memory;
        private NativeStart(ProjectOwnerWorkflows issuer,JSONObject context,dev.pocketengine.features.memory.ProjectMemoryAccess.Consent memory){this.issuer=issuer;this.context=context.toString();this.memory=memory;}
    }
    public JSONObject start(JSONObject ownerInput,dev.pocketengine.features.memory.ProjectMemoryAccess.Consent memory)throws Exception{
        if(ownerInput.has("draftOnly")||ownerInput.has(OwnerRevisionSource.FIELD))throw new SecurityException("独立改稿只走已确认的原生私人管家交办，不合并记忆授权");
        java.util.Objects.requireNonNull(memory);String checkout=ProjectBuildPlan.identity(ownerInput.getString("checkoutId")),project=ProjectBuildPlan.identity(ownerInput.getString("projectId"));
        JSONObject contract=selectedDeliveryContract(ownerInput);
        String digest=ownerInput.getString("expectedSourceDigest"),client=ownerInput.getString("clientMessageId"),goal=contract.getString("goal"),runtime=ownerInput.getString("runtime");local.projectRuntimeForHost(runtime);
        if(!digest.matches("[a-f0-9]{64}")||!client.matches("[a-fA-F0-9-]{36}")||goal.isEmpty()||goal.length()>12000)throw new IllegalArgumentException("请选择真实项目及有界目标");
        JSONObject consent=memory.metadata();if(!project.equals(consent.getString("projectId"))||!checkout.equals(consent.getString("checkoutId")))throw new SecurityException("原生记忆同意不属于此实际项目副本");
        JSONObject context=new JSONObject().put("format","pocketworld.development-context.v1").put("projectId",project).put("checkoutId",checkout).put("runtime",runtime).put("initialSourceDigest",digest).put("clientMessageId",client).put("filesAreUntrusted",true).put("authorityAdded",false);
        if(butlerPreparation.get()==null)context.put("deliveryContract",contract);
        JSONObject prior=null;try{prior=assistant.handle("getSubmission",new JSONObject().put("clientMessageId",client));}catch(IllegalArgumentException absent){}
        if(prior!=null){verifyAcknowledgement(prior,context);JSONObject task=requireTask(prior.getString("taskId")),original=task.optJSONObject("projectMemoryRead");if(original==null||!consent.getString("consentId").equals(original.optString("consentId")))throw new SecurityException("原提交的记忆选择不同；只核对原任务，未恢复或替换旧权限");return prior;}
        JSONObject actual=ProjectSources.describe(local.handle("export",new JSONObject().put("checkoutId",checkout)));if(!project.equals(actual.getString("projectId"))||!digest.equals(actual.getString("sourceDigest")))throw new SecurityException("项目源码已变化，请重新核对开始");
        JSONObject ack=assistant.sendProjectForOwner(new JSONObject().put("clientMessageId",client).put("message",goal).put("mode","queue"),new NativeStart(this,context,memory));if(ack.optString("status").matches("accepted|queued"))verifyAcknowledgement(ack,context);return ack;
    }
    public dev.pocketengine.features.memory.ProjectMemoryAccess bindMemoryAttempt(JSONObject task,RunControl original,dev.pocketengine.core.ModelConfig model,NativeStart selected)throws Exception{
        if(selected==null||selected.issuer!=this)throw new SecurityException("项目启动不是此原生主人选择");JSONObject context=new JSONObject(selected.context);tasks.bindDevelopmentContext(task.getString("id"),task.getString("runId"),task.getInt("coordinatorEpoch"),original,context);return selected.memory.bindForRun(tasks,task.getString("id"),original);
    }
    /** Native opaque result review only. JSON owner/model actions cannot mint this selection. */
    public JSONObject startTeamRound(dev.pocketengine.features.teams.TeamProjectRoundHost.Admission admitted)throws Exception {
        java.util.Objects.requireNonNull(admitted);JSONObject input=admitted.ownerRequest(),prior=null;try{prior=assistant.handle("getSubmission",new JSONObject().put("clientMessageId",input.getString("clientMessageId")));}catch(IllegalArgumentException absent){}
        if(prior!=null){admitted.verifyAcknowledgement(prior);return prior;}admitted.checkCurrent();if(selection.get()!=null||teamRound.get()!=null)throw new IllegalStateException("不能嵌套新建项目执行");teamRound.set(admitted);
        try{JSONObject ack=assistant.handle("send",new JSONObject().put("clientMessageId",input.getString("clientMessageId")).put("message",input.getString("goal")).put("mode","queue"));if(ack.optString("status").matches("accepted|queued"))admitted.verifyAcknowledgement(ack);return ack;}finally{teamRound.remove();}
    }
    public JSONObject teamRoundSubmission(dev.pocketengine.features.teams.TeamProjectRoundHost.Admission admitted)throws Exception{JSONObject input=admitted.ownerRequest(),ack=assistant.handle("getSubmission",new JSONObject().put("clientMessageId",input.getString("clientMessageId")));admitted.verifyAcknowledgement(ack);return ack;}
    /** Called only inside the existing native assistant admission, before any model turn is scheduled. */
    public void bindNewAttempt(JSONObject task,RunControl original)throws Exception {bindNewAttempt(task,original,null);}
    public void bindNewAttempt(JSONObject task,RunControl original,dev.pocketengine.core.ModelConfig actualModel)throws Exception {
        var round=teamRound.get();if(round!=null){if(selection.get()!=null)throw new SecurityException("新一轮与其他项目选择不能混合");round.bindNewAttempt(task,original,actualModel);return;}
        JSONObject context=selection.get();if(context!=null){
            tasks.bindDevelopmentContext(task.getString("id"),task.getString("runId"),task.getInt("coordinatorEpoch"),original,new JSONObject(context.toString()));
            ProjectConventionDelivery.bind(ownerConvention.get(),context,task,original);
            var preparation=butlerPreparation.get();if(preparation!=null)preparation.bindPreparedTask(task.getString("id"),task.getString("runId"),task.getInt("coordinatorEpoch"));
        }
    }
    public JSONObject requireTask(String id)throws Exception {
        JSONObject task=tasks.get(id,false),context=task==null?null:task.optJSONObject("developmentContext");
        if(task==null||!"assistant".equals(task.optString("kind"))||!"owner".equals(task.optString("actorId"))||!"private".equals(task.optString("spaceId"))||task.optJSONObject("origin")!=null||context==null)throw new SecurityException("请选择本机主人原开发任务");
        JSONObject checkout=local.projectCheckoutForHost(context.getString("checkoutId"));if(!context.getString("projectId").equals(checkout.getString("projectId")))throw new SecurityException("原项目副本已不可用");return task;
    }
    private void verifyAcknowledgement(JSONObject ack,JSONObject expected)throws Exception {
        if(!ack.has("taskId"))return;JSONObject task;
        if(expected.has(OwnerRevisionSource.FIELD)){task=tasks.get(ack.getString("taskId"),false);
            if(task==null||!"owner".equals(task.opt("actorId"))||!"private".equals(task.opt("spaceId"))||!task.isNull("origin")||task.optJSONObject("ownerWorkPreparation")==null)throw new SecurityException("不是所选稿的新正式工作");
        }else task=requireTask(ack.getString("taskId"));
        JSONObject actual=task.getJSONObject("developmentContext");
        if(ProjectSources.canonical(expected).equals(ProjectSources.canonical(actual)))return;
        // Old saved starts had only a goal. They can be queried, but cannot
        // acquire additional requirements under the same client identity.
        JSONObject contract=expected.optJSONObject("deliveryContract");
        if(!actual.has("deliveryContract")&&contract!=null&&contract.length()==3&&contract.optInt("revision")==1&&contract.getString("goal").equals(task.optString("goal"))){
            JSONObject legacy=new JSONObject(expected.toString());legacy.remove("deliveryContract");
            if(ProjectSources.canonical(legacy).equals(ProjectSources.canonical(actual)))return;
        }
        throw new SecurityException("这条输入回执的项目或交付要求不同，请核对原任务；没有替换原要求");
    }
}
