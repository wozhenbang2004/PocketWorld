package dev.pocketengine.features.skills;

import dev.pocketengine.features.EngineModule;
import dev.pocketengine.features.FeatureContext;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

public final class SkillsModule implements EngineModule {
    private final FeatureContext context;
    private final SkillBook book;
    private final SkillGitClient gitClient;
    private volatile dev.pocketengine.features.plugins.PluginsModule mcpSources;
    private volatile dev.pocketengine.features.memory.ProjectMemoryNamespaces projectNamespaces;
    public SkillsModule(FeatureContext context)throws Exception{this(context,null);}
    public SkillsModule(FeatureContext context,ExperienceOrigin.ExperienceResolver resolver)throws Exception{this(context,new SkillBook(new File(context.android.getFilesDir(),"skills"),resolver),new SkillGitClient());}
    /** Same native module with a loopback protocol client for direct tests; no feature/model input. */
    SkillsModule(FeatureContext context,SkillBook book,SkillGitClient gitClient){this.context=java.util.Objects.requireNonNull(context);this.book=java.util.Objects.requireNonNull(book);this.gitClient=java.util.Objects.requireNonNull(gitClient);}
    @Override public String id(){return "skills";}
    private static JSONObject privateScope()throws Exception{return new JSONObject().put("ownerId","owner").put("spaceId","private");}
    @Override public JSONObject snapshot()throws Exception{return new JSONObject().put("available",true).put("skills",book.list(privateScope(),System.currentTimeMillis()))
            .put("progressiveReading",true).put("textResourcesOnly",true).put("grantsFromInstructions",false).put("scriptExecution",false).put("validationOnLoad",true)
            .put("nativeGitSourceReview",true).put("gitSourceProvider","github_public_https").put("nativeRetainedRevisionReview",true)
            .put("nativeMcpSourceReview",mcpSources!=null).put("mcpSourceAutomaticSync",false)
            .put("nativePackageStreams",true).put("maxPackageArchiveBytes",SkillArchive.MAX_ARCHIVE_BYTES)
            .put("nativeDiscovery",true).put("discoveryMatching","lexical-v1")
            .put("nativeSourcePolicyReview",true).put("sourcePriority","project_then_user_then_bundled_then_stable_id").put("modelInvocationPolicyEnforced",true)
            .put("nativeProjectSourceBinding",projectNamespaces!=null).put("automaticProjectWatch",false)
            .put("nativeExecutionReview",true).put("executionRequiresNativeBackend",true)
            .put("nativeExperienceImport",true).put("experienceResolverAvailable",book.experienceResolverAvailable()).put("experienceExecutionVerified",false)
            .put("commands",new JSONArray(new String[]{"list","get","import","read","setEnabled","delete"}));}
    @Override public JSONObject handle(String command,JSONObject input)throws Exception {
        JSONObject scope=input.optJSONObject("scope");if(scope==null)scope=privateScope();long now=System.currentTimeMillis();JSONObject result;
        for(String key:new String[]{"projectId","checkoutId","projectRevision","projectSelection","namespace"})if(input.has(key)||scope.has(key))throw new SecurityException("项目技能范围只能来自原生项目选择与原任务，不接受命令JSON指定");
        switch(command){
            case "list":return new JSONObject().put("skills",book.list(scope,now));
            case "get":return new JSONObject().put("skill",book.get(scope,input.getString("id"),now));
            case "read":return book.read(scope,input.getString("id"),input.optString("path","SKILL.md"),input.optInt("offset",0),input.optInt("length",8000),now);
            case "import":result=new JSONObject().put("skill",book.install(scope,input,now));break;
            case "setEnabled":result=new JSONObject().put("skill",book.setEnabled(scope,input.getString("id"),input.getBoolean("enabled"),input.optString("acceptedDigest"),input.optBoolean("confirmed",false),now));break;
            case "delete":book.delete(scope,input.getString("id"));result=new JSONObject().put("deleted",true);break;
            default:throw new IllegalArgumentException("未知技能操作");
        }
        context.emit(id(),new JSONObject().put("event","skills_changed"));return result;
    }
    /** Supervisor-only loading with actual host capabilities, never a model-supplied grant list. */
    public JSONObject loadForHost(JSONObject scope,String id,String digest,Set<String> hostCapabilities)throws Exception{return book.loadForHost(scope,id,digest,hostCapabilities,System.currentTimeMillis());}
    public JSONObject catalogueForHost(JSONObject scope)throws Exception {return book.catalogueForHost(scope,System.currentTimeMillis());}
    public JSONObject cataloguePageForHost(JSONObject scope,Set<String> capabilities,JSONObject cursor,int pageSize)throws Exception {
        return cataloguePageForHost(scope,capabilities,cursor,pageSize,null);
    }
    public JSONObject cataloguePageForHost(JSONObject scope,Set<String> capabilities,JSONObject cursor,int pageSize,Set<String> selectedVersions)throws Exception {
        return book.cataloguePageForHost(scope,capabilities,cursor,pageSize,selectedVersions,System.currentTimeMillis());
    }
    public JSONObject discoverForHost(JSONObject authenticatedScope,String query,Set<String> hostCapabilities,int limit,int maxChars)throws Exception {
        return book.discoverForHost(authenticatedScope,query,hostCapabilities,limit,maxChars,System.currentTimeMillis());
    }
    /** Trusted management UI only; never inject these inactive/invalid rows into a model catalogue. */
    public JSONObject diagnoseDiscovery(JSONObject authenticatedScope,String query,Set<String> hostCapabilities)throws Exception {
        return book.diagnoseDiscovery(authenticatedScope,query,hostCapabilities,System.currentTimeMillis());
    }
    /** Explicit native owner action; not accepted by generic feature or model command JSON. */
    public JSONObject configureSourceForOwner(JSONObject authenticatedScope,String id,int revision,String digest,long expectedExecutionGeneration,
            SkillSourcePolicy.Layer layer,SkillSourcePolicy.Invocation invocation)throws Exception {
        return configureSourceForOwner(authenticatedScope,id,revision,digest,expectedExecutionGeneration,layer,invocation,null);
    }
    public JSONObject configureSourceForOwner(JSONObject authenticatedScope,String id,int revision,String digest,long expectedExecutionGeneration,
            SkillSourcePolicy.Layer layer,SkillSourcePolicy.Invocation invocation,JSONObject observedProjectChoice)throws Exception {
        String project=null;
        if(layer==SkillSourcePolicy.Layer.PROJECT){
            if(observedProjectChoice==null)throw new SecurityException("请选择原生目录中的真实项目，再审阅项目技能来源");
            JSONObject actual=projectChoice(observedProjectChoice.getString("projectId"),observedProjectChoice.getString("checkoutId"));
            if(!actual.getString("revision").equals(observedProjectChoice.getString("revision")))throw new SecurityException("原项目或副本身份已变化，请重新读取项目列表");
            project=actual.getString("projectId");
        }else if(observedProjectChoice!=null)throw new SecurityException("全局技能来源不接受项目选择");
        JSONObject result=new JSONObject().put("skill",book.configureSourceForOwner(authenticatedScope,id,revision,digest,expectedExecutionGeneration,layer,invocation,project,System.currentTimeMillis()));
        context.emit(id(),new JSONObject().put("event","skills_changed").put("reason","source_policy_changed"));return result;
    }
    /** Reuses the original project catalogue. Reading choices does not activate or replace the
     * owner's project-memory selection, and creates no additional namespace or grant. */
    public synchronized void mountProjectNamespaces(dev.pocketengine.features.memory.ProjectMemoryNamespaces original){
        java.util.Objects.requireNonNull(original);if(projectNamespaces!=null&&projectNamespaces!=original)throw new SecurityException("原项目来源宿主不能替换");projectNamespaces=original;
    }
    public JSONArray projectSourceChoicesForOwner()throws Exception {
        var original=projectNamespaces;if(original==null)throw new SkillExecution.Unavailable("原项目目录尚未接入，不能把项目技能改成全局来源");
        return original.choicesForOwner();
    }
    private JSONObject projectChoice(String project,String checkout)throws Exception {
        SkillSourcePolicy.project(project);SkillSourcePolicy.project(checkout);JSONArray choices=projectSourceChoicesForOwner();
        for(int i=0;i<choices.length();i++){JSONObject row=choices.getJSONObject(i);if(project.equals(row.optString("projectId"))&&checkout.equals(row.optString("checkoutId"))&&row.optBoolean("available"))return row;}
        throw new SecurityException("原项目/副本已不存在或不属于私人主人项目，请重新选择");
    }
    /** Only native callers pass a freshly read original TaskStore record. This is deliberately
     * absent from handle(): tool JSON and catalogue metadata cannot select a project. */
    public JSONObject taskScopeForHost(JSONObject originalTask)throws Exception {
        if(originalTask==null||!"owner".equals(originalTask.optString("actorId"))||!"private".equals(originalTask.optString("spaceId")))throw new SecurityException("技能范围只来自原主人私人任务");JSONObject scope=privateScope();
        if(!originalTask.has("developmentContext"))return scope;
        if(!"assistant".equals(originalTask.optString("kind"))||originalTask.has("origin"))throw new SecurityException("项目技能只使用原私人任务的项目范围");
        JSONObject selected=originalTask.getJSONObject("developmentContext");
        if(!"pocketworld.development-context.v1".equals(selected.optString("format")))throw new SecurityException("原任务项目选择格式已变化");
        JSONObject actual=projectChoice(selected.getString("projectId"),selected.getString("checkoutId"));
        return SkillSourcePolicy.checkedScope(scope.put("projectId",actual.getString("projectId")).put("checkoutId",actual.getString("checkoutId")).put("projectRevision",actual.getString("revision")));
    }
    public void requireTaskScopeForHost(JSONObject originalTask,JSONObject expectedScope)throws Exception {
        if(!SkillSourcePolicy.scopeFingerprint(taskScopeForHost(originalTask)).equals(SkillSourcePolicy.scopeFingerprint(expectedScope)))throw new SecurityException("原任务项目已变化或解绑，旧技能正文与脚本选择已失效");
    }
    /** Native Profile preflight only. The opaque original project selection, not imported
     * Profile/model JSON, supplies the project. The original namespace pins this checkout
     * occurrence independently from the source bytes reviewed before starting a task. */
    public JSONObject profileScopeForHost(dev.pocketengine.features.local.project.ProjectOwnerWorkflows.ProfileProjectSelection selected)throws Exception {
        if(selected==null)return privateScope();
        JSONObject original=selected.view(),actual=projectChoice(original.getString("projectId"),original.getString("checkoutId"));
        return SkillSourcePolicy.checkedScope(privateScope().put("projectId",actual.getString("projectId")).put("checkoutId",actual.getString("checkoutId")).put("projectRevision",actual.getString("revision")));
    }
    /** Currentness guard over a scope already issued by a native caller. It cannot issue a
     * selection, change a task, or grant model access. Ordinary source edits do not replace
     * the original checkout occurrence and therefore do not revoke this namespace. */
    public void requireProjectScopeForHost(JSONObject expectedScope)throws Exception {
        JSONObject expected=SkillSourcePolicy.checkedScope(expectedScope);
        if(!"owner".equals(expected.getString("ownerId"))||!"private".equals(expected.getString("spaceId")))throw new SecurityException("工作配置技能范围必须来自原私人主人");
        if(!expected.has("projectId"))return;
        JSONObject actual=projectChoice(expected.getString("projectId"),expected.getString("checkoutId"));
        if(!actual.getString("revision").equals(expected.getString("projectRevision")))throw new SecurityException("原项目副本身份已变化，请重新预检工作配置");
    }
    public JSONObject readForHost(JSONObject scope,String id,String path,int offset,int length,String expectedDigest,Set<String> hostCapabilities)throws Exception{return book.readForHost(scope,id,path,offset,length,expectedDigest,hostCapabilities,System.currentTimeMillis());}
    public JSONObject readForHost(JSONObject scope,String id,String path,int offset,int length,int expectedRevision,String expectedDigest,Set<String> hostCapabilities)throws Exception{return book.readForHost(scope,id,path,offset,length,expectedRevision,expectedDigest,hostCapabilities,System.currentTimeMillis());}
    /** Trusted native owner/model-worker bridge. Draft is only content; origin and options are host state. */
    public JSONObject inspectExperience(JSONObject authenticatedScope,ExperienceOrigin origin,JSONObject draft,JSONObject options)throws Exception {
        return book.inspectExperience(authenticatedScope,origin,draft,options,System.currentTimeMillis());
    }
    public JSONObject importExperience(JSONObject authenticatedScope,ExperienceOrigin origin,JSONObject draft,JSONObject options)throws Exception {
        JSONObject result=new JSONObject().put("skill",book.importExperience(authenticatedScope,origin,draft,options,System.currentTimeMillis()));
        context.emit(id(),new JSONObject().put("event","skills_changed"));return result;
    }
    /** Call only after the owner explicitly approves the displayed current revision and package digest. */
    public JSONObject approveExperience(JSONObject authenticatedScope,String id,int revision,String digest)throws Exception {
        JSONObject result=new JSONObject().put("skill",book.approveExperience(authenticatedScope,id,revision,digest,System.currentTimeMillis()));
        context.emit(id(),new JSONObject().put("event","skills_changed"));return result;
    }
    public SkillBook.ReferenceSelection selectReferenceForHost(JSONObject scope,String id,int revision,String digest,Set<String> capabilities)throws Exception {
        return book.selectReferenceForHost(scope,id,revision,digest,capabilities,System.currentTimeMillis());
    }
    public JSONObject recordQuality(JSONObject scope,SkillQualityReceipt receipt)throws Exception {
        JSONObject result=new JSONObject().put("skill",book.recordQuality(scope,receipt,System.currentTimeMillis()));
        context.emit(id(),new JSONObject().put("event","skills_changed"));return result;
    }
    /** Only a committed native source-delete/forget event; replay its existing host operation receipt. */
    public JSONObject removeExperienceSource(JSONObject authenticatedScope,ExperienceOrigin.Ref removed)throws Exception {
        try{return book.removeExperienceSource(authenticatedScope,removed);}
        finally{context.emit(id(),new JSONObject().put("event","skills_changed"));}
    }
    /** Call after source changes/forget and on startup before resuming skill context; retry blocked cleanup. */
    public JSONObject reconcileExperiences(JSONObject authenticatedScope)throws Exception {
        try{return book.reconcileExperiences(authenticatedScope,System.currentTimeMillis());}
        finally{context.emit(id(),new JSONObject().put("event","skills_changed"));}
    }
    /** Owner UI opens a user-selected SAF document off the main thread. Not a model/feature command. */
    public JSONObject inspectPackage(JSONObject scope,InputStream archive,String id)throws Exception{return book.inspectPackage(scope,archive,id,System.currentTimeMillis());}
    public JSONObject importPackage(JSONObject scope,InputStream archive,JSONObject options)throws Exception{
        JSONObject skill=book.importPackage(scope,archive,options,System.currentTimeMillis());
        context.emit(id(),new JSONObject().put("event","skills_changed"));return new JSONObject().put("skill",skill);
    }
    public JSONObject exportPackage(JSONObject scope,String id,int revision,String expectedDigest,OutputStream output)throws Exception{return book.exportPackage(scope,id,revision,expectedDigest,output,System.currentTimeMillis());}
    /** App composition once, using the already mounted original MCP installation authority. */
    public synchronized void mountMcpSources(dev.pocketengine.features.plugins.PluginsModule plugins){
        if(mcpSources!=null)throw new IllegalStateException("原MCP技能来源已挂载");
        mcpSources=java.util.Objects.requireNonNull(plugins);book.mountMcpSourceValidation(plugins::assertMcpSkillSourceCurrent);
    }
    private synchronized dev.pocketengine.features.plugins.PluginsModule mcpSources(){
        if(mcpSources==null)throw new IllegalStateException("原MCP技能来源尚未挂载，未读取任何远端资源");return mcpSources;
    }
    /** Owner source review data. No generic feature command, enabled flag or tool grant is added. */
    public JSONObject listMcpSkillResources(String pluginId,String installationId,long stateRevision,dev.pocketengine.core.RunControl original)throws Exception {
        return listMcpSkillResources(privateScope(),pluginId,installationId,stateRevision,original);
    }
    public JSONObject listMcpSkillResources(JSONObject authenticatedScope,String pluginId,String installationId,long stateRevision,dev.pocketengine.core.RunControl original)throws Exception {
        Exception originalFailure=null;
        try{return mcpSources().listMcpSkillResources(pluginId,installationId,stateRevision,original);}
        catch(Exception failure){originalFailure=failure;throw failure;}
        finally{try{reconcileMcpSources(authenticatedScope);}catch(Exception revokeFailure){if(originalFailure!=null)originalFailure.addSuppressed(revokeFailure);else throw revokeFailure;}}
    }
    public SkillBook.SourceReview inspectMcpSource(JSONObject scope,String targetId,String pluginId,String installationId,long stateRevision,String resourceURI,dev.pocketengine.core.RunControl original)throws Exception {
        try{dev.pocketengine.features.plugins.McpSkillSource.Fetched fetched=mcpSources().readMcpSkillSource(pluginId,installationId,stateRevision,resourceURI,original);
            original.check();return book.inspectMcpSource(scope,targetId,fetched,System.currentTimeMillis());}
        catch(Exception failure){try{book.reconcileMcpSources(scope,System.currentTimeMillis());}catch(Exception cleanup){failure.addSuppressed(cleanup);}throw failure;}
        finally{context.emit(id(),new JSONObject().put("event","skills_changed").put("reason","mcp_source_observed"));}
    }
    public JSONObject reconcileMcpSources(JSONObject scope)throws Exception {
        JSONObject result=book.reconcileMcpSources(scope,System.currentTimeMillis());if(result.getInt("revoked")>0)context.emit(id(),new JSONObject().put("event","skills_changed").put("reason","mcp_source_revoked"));return result;
    }
    /** Native owner UI only: neither source JSON nor model commands can create a reviewed import. */
    public SkillBook.SourceReview inspectGitSource(JSONObject scope,String targetId,SkillGitSource source,dev.pocketengine.core.RunControl original)throws Exception {
        return book.inspectGitSource(scope,targetId,source,gitClient,original,System.currentTimeMillis());
    }
    public JSONObject applySourceReview(JSONObject scope,SkillBook.SourceReview review,SkillBook.SourceChoice choice,boolean discardLocal)throws Exception {
        JSONObject skill=book.applySourceReview(scope,review,choice,discardLocal,System.currentTimeMillis());
        context.emit(id(),new JSONObject().put("event","skills_changed"));return new JSONObject().put("skill",skill);
    }
    public JSONObject readSourceReview(JSONObject scope,SkillBook.SourceReview review,String path,int offset,int length)throws Exception {
        return book.readSourceReview(scope,review,path,offset,length,System.currentTimeMillis());
    }
    public JSONObject retainedRevisions(JSONObject scope,String id,int beforeRevision,int limit)throws Exception {
        return book.retainedRevisions(scope,id,beforeRevision,limit,System.currentTimeMillis());
    }
    public SkillBook.SourceReview inspectRetainedRevision(JSONObject scope,String id,int revision)throws Exception {
        return book.inspectRetainedRevision(scope,id,revision,System.currentTimeMillis());
    }
    /** At most eight explicitly selected existing skills. A failure never overwrites another result. */
    public static final class SourceBatch {
        private final SkillsModule issuer;
        private final String scope,rows;
        private final java.util.List<SkillBook.SourceReview> reviews;
        private SourceBatch(SkillsModule issuer,JSONObject scope,JSONArray rows,java.util.List<SkillBook.SourceReview> reviews) {
            this.issuer=issuer;this.scope=scope.toString();this.rows=rows.toString();this.reviews=new java.util.ArrayList<>(reviews);
        }
        public JSONArray rows()throws Exception { return new JSONArray(rows); }
        public SkillBook.SourceReview review(int position) { return reviews.get(position); }
    }
    public SourceBatch inspectTrackedSources(JSONObject scope,java.util.List<String> ids,dev.pocketengine.core.RunControl original)throws Exception {
        if(ids==null||ids.isEmpty()||ids.size()>8||new java.util.HashSet<>(ids).size()!=ids.size())throw new IllegalArgumentException("请选择1至8个不同的已有技能");
        for(String id:ids)if(id==null||!id.matches("[a-f0-9-]{36}"))throw new IllegalArgumentException("技能标识无效");
        JSONArray rows=new JSONArray();java.util.List<SkillBook.SourceReview> reviews=new java.util.ArrayList<>();
        for(String id:ids) {
            original.check();SkillBook.SourceReview reviewed=null;JSONObject row=new JSONObject().put("id",id);
            try {
                JSONObject current=book.get(scope,id,System.currentTimeMillis()),git=current.optJSONObject("gitSource");
                if(git==null)throw new IllegalArgumentException("没有原生登记的 Git 来源");
                reviewed=book.inspectGitSource(scope,id,SkillGitSource.fromJson(git.getJSONObject("source")),gitClient,original,System.currentTimeMillis());
                row.put("status","checked").put("review",reviewed.view());
            } catch(InterruptedException|java.util.concurrent.CancellationException cancelled) { throw cancelled; }
            catch(Exception unavailable) { original.check();row.put("status","unavailable").put("reason",sourceFailureCode(unavailable)); }
            reviews.add(reviewed);rows.put(row);
        }
        return new SourceBatch(this,scope,rows,reviews);
    }
    /** Explicit owner batch action: only unchanged-local tracked updates; edited/unknown items are skipped. */
    public JSONObject applyCleanSourceUpdates(JSONObject scope,SourceBatch batch,dev.pocketengine.core.RunControl original)throws Exception {
        if(batch==null||batch.issuer!=this||!sameScope(scope,new JSONObject(batch.scope)))throw new SecurityException("批量检查不属于当前技能空间");
        JSONArray rows=new JSONArray(),originalRows=batch.rows();boolean changed=false;
        try {
            for(int i=0;i<batch.reviews.size();i++) {
                original.check();SkillBook.SourceReview review=batch.reviews.get(i);JSONObject row=new JSONObject().put("id",originalRows.getJSONObject(i).getString("id"));
                if(review==null){rows.put(row.put("status","skipped").put("reason","check_unavailable"));continue;}
                JSONObject view=review.view();
                if(!"upstream_changed".equals(view.optString("classification"))||view.optBoolean("requiresDiscardConfirmation",true)) {
                    rows.put(row.put("status","skipped").put("reason","current".equals(view.optString("classification"))?"already_current":"individual_review_required"));continue;
                }
                try {
                    JSONObject skill=book.applySourceReview(scope,review,SkillBook.SourceChoice.REPLACE,false,System.currentTimeMillis());
                    changed=true;row.put("status","imported_disabled").put("revision",skill.getInt("revision"));
                } catch(Exception unavailable) { original.check();row.put("status","unavailable").put("reason",sourceFailureCode(unavailable)); }
                rows.put(row);
            }
            return new JSONObject().put("items",rows).put("executionAuthorized",false).put("automaticRetry",false);
        } finally { if(changed)context.emit(id(),new JSONObject().put("event","skills_changed")); }
    }
    private static boolean sameScope(JSONObject a,JSONObject b) {
        return a.optString("ownerId").equals(b.optString("ownerId"))&&a.optString("spaceId").equals(b.optString("spaceId"));
    }
    private static String sourceFailureCode(Exception failure) {
        if(failure instanceof SkillBook.SourceUnavailable known)return known.code;
        if(failure instanceof SkillGitClient.Unavailable known)return known.code;
        return "source_check_unavailable";
    }
    /** Exact native functional-package ownership cleanup; never accepts an unpinned model delete. */
    public JSONObject deletePackageReviewed(JSONObject scope,String id,int revision,String digest,long executionGeneration)throws Exception {
        JSONObject result=book.deletePackageReviewed(scope,id,revision,digest,executionGeneration);context.emit(id(),new JSONObject().put("event","skills_changed"));return result;
    }
    /** Native only. No registered backend/TaskStore adapter means unavailable, never an app-UID shell. */
    public SkillExecution executionForHost(SkillExecution.Backend backend,SkillExecution.HostPort host){
        return new SkillExecution(book,backend,host,System::currentTimeMillis);
    }
    SkillBook.OwnerExecutionSelection selectExecutionForOwner(JSONObject scope,String id,int revision,String digest,long generation,Set<String> capabilities)throws Exception {
        return book.selectExecutionForOwner(scope,id,revision,digest,generation,capabilities,System.currentTimeMillis());
    }
    SkillExecution executionForOwner(SkillExecution.Backend backend,SkillExecution.HostPort host,SkillBook.OwnerExecutionSelection selected){
        return new SkillExecution(book,backend,host,System::currentTimeMillis,java.util.Objects.requireNonNull(selected));
    }
}
