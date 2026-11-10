package dev.pocketengine.features.memory;

import dev.pocketengine.features.local.LocalModule;
import dev.pocketengine.features.local.project.ProjectSources;
import dev.pocketengine.features.workspace.WorkspaceModule;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Set;

/** Native owner management of project partitions in the one original Book. Not an EngineModule,
 * model tool, project provenance grant, group-retention consent or a member ReadAudience. */
public final class ProjectMemoryNamespaces {
    private final MemoryBook book;
    private final LocalModule local;
    private final WorkspaceModule workspace;
    private final java.util.concurrent.atomic.AtomicReference<Selection> active=new java.util.concurrent.atomic.AtomicReference<>();
    private final java.util.concurrent.atomic.AtomicLong selectionEpoch=new java.util.concurrent.atomic.AtomicLong();
    private dev.pocketengine.features.MemorySourceBridge sourceBridge;

    ProjectMemoryNamespaces(MemoryBook book,LocalModule local,WorkspaceModule workspace){
        this.book=java.util.Objects.requireNonNull(book);this.local=java.util.Objects.requireNonNull(local);this.workspace=java.util.Objects.requireNonNull(workspace);
    }
    synchronized void bindOriginalSources(dev.pocketengine.features.MemorySourceBridge original){
        if(original==null)return;if(sourceBridge!=null&&sourceBridge!=original)throw new SecurityException("项目记忆不能更换原来源宿主");sourceBridge=original;
    }
    /** Only this original native facade can issue a live selection. Its metadata is not the token. */
    public static final class Selection {
        private final ProjectMemoryNamespaces issuer;
        private final String project,checkout,revision;
        private Selection(ProjectMemoryNamespaces issuer,JSONObject facts)throws Exception{
            this.issuer=issuer;project=facts.getString("projectId");checkout=facts.getString("checkoutId");revision=facts.getString("revision");
        }
        String projectId(){return project;}
        void requireBook(MemoryBook expected){
            if(expected!=issuer.book||issuer.active.get()!=this||!Thread.holdsLock(issuer)||!Thread.holdsLock(issuer.local))throw new SecurityException("项目记忆选择不是此原生Book的当前主人准入");
        }
        public JSONObject metadata()throws Exception{return new JSONObject().put("ownerId","owner").put("spaceId","project:"+project).put("projectId",project).put("checkoutId",checkout).put("revision",revision).put("authority","native_owner_explicit_project_selection").put("selectionCurrent",issuer.active.get()==this).put("serializableGrant",false).put("groupRetentionAuthorized",false).put("memberReadAuthorized",false);}
    }
    /** Enumerates actual native checkouts. An unsupported row is not rendered as an empty Book. */
    public synchronized JSONArray choicesForOwner()throws Exception {
        JSONArray checkouts=local.snapshot().getJSONArray("checkouts"),out=new JSONArray();
        if(checkouts.length()>40)throw new IllegalStateException("原项目副本目录超过本次有界读取范围");
        for(int i=0;i<checkouts.length();i++){
            JSONObject row=checkouts.getJSONObject(i),choice=new JSONObject().put("checkoutId",row.getString("id")).put("projectId",row.getString("projectId")).put("name",row.optString("name","项目"));
            try{choice=facts(row.getString("id")).put("available",true).put("name",choice.getString("name"));}
            catch(SecurityException|IllegalStateException|IllegalArgumentException unavailable){choice.put("available",false).put("status","unsupported").put("message",unavailable.getMessage());}
            out.put(choice);
        }
        return out;
    }
    /** Called by an explicit owner screen selection, never by FeatureHost or a model argument. */
    public synchronized Selection selectForOwner(String checkoutId,String expectedProjectId,String expectedRevision)throws Exception {
        long epoch=selectionEpoch.incrementAndGet();active.set(null); // Failed choices do not retain an older audience.
        synchronized(local){
            JSONObject actual=facts(checkoutId);
            if(!actual.getString("projectId").equals(expectedProjectId)||!actual.getString("revision").equals(expectedRevision))throw new SecurityException("所选项目/副本/版本已变化，请重新读取项目列表");
            if(epoch!=selectionEpoch.get())throw new SecurityException("项目选择页面已经关闭");Selection next=new Selection(this,actual);active.set(next);
            if(epoch!=selectionEpoch.get()){active.compareAndSet(next,null);throw new SecurityException("项目选择页面已经关闭");}return next;
        }
    }
    /** Immediate native revocation; never wait on file/Bridge IO on an Android UI thread. */
    public void clearForOwner(){selectionEpoch.incrementAndGet();active.set(null);}
    public void revokeForOwner(Selection expected){if(expected!=null&&expected.issuer==this)active.compareAndSet(expected,null);}
    private void requireCurrent(Selection selected)throws Exception{
        if(selected==null||selected.issuer!=this||active.get()!=selected)throw new SecurityException("请选择当前原生主人项目记忆范围；没有回退主人全局空间");
        JSONObject current;try{current=facts(selected.checkout);}catch(Exception unavailable){active.set(null);throw unavailable;}
        if(!selected.project.equals(current.getString("projectId"))||!selected.revision.equals(current.getString("revision"))){active.set(null);throw new SecurityException("原项目/副本身份已变化，旧记忆选择已失效，请重新选择");}
    }
    private static JSONObject ownerScope()throws Exception{return new JSONObject().put("ownerId","owner").put("spaceId","private");}
    private void synchronizeOriginalSources()throws Exception{
        if(sourceBridge==null)throw new UnsupportedOperationException("项目文件来源尚未连接原来源清理宿主；未回退全局或创建替代宿主");
        // Original Bridge -> original Book/Tasks cleanup receipt; never acquire Tasks from inside
        // a Local/Book monitor or silently retire global records without their original journal.
        sourceBridge.synchronize(ownerScope());
    }
    public synchronized JSONObject handleForOwner(Selection selected,String command,JSONObject input)throws Exception {
        boolean hasSources;synchronized(local){requireCurrent(selected);hasSources=book.projectSourcesForOwner(selected).length()>0;}
        if(hasSources)synchronizeOriginalSources();
        if("correctReviewed".equals(command)){
            if(sourceBridge==null)throw new UnsupportedOperationException("项目记忆纠正尚未连接原上下文清理宿主");
            JSONObject value=input==null?new JSONObject():input;rejectNamespaceArguments(value);
            return sourceBridge.correctProjectReviewed(this,selected,value.getString("id"),SourceRef.integer(value,"expectedRevision",1),value.getString("text"));
        }
        if("forgetReviewed".equals(command)&&sourceBridge!=null){JSONObject value=input==null?new JSONObject():input;rejectNamespaceArguments(value);return sourceBridge.forgetProjectReviewed(this,selected,value.getString("id"),SourceRef.integer(value,"expectedRevision",1));}
        synchronized(local){requireCurrent(selected);JSONObject result=book.projectForOwner(selected,command,input==null?new JSONObject():input);if("snapshot".equals(command))result.put("taskContextSupported",sourceBridge!=null).put("taskContextMode","explicit_native_project_only");return result;}
    }
    /** Original Bridge calls back on the same native facade admission; no reverse facade wait. */
    public JSONObject forgetWithRetirementForHost(Selection selected,String id,long revision,MemoryBook.BeforeForget receipt)throws Exception{
        if(!Thread.holdsLock(this))throw new SecurityException("项目遗忘须沿原facade准入，不接受JSON来源标记");synchronized(local){requireCurrent(selected);return book.forgetProjectWithRetirement(selected,id,revision,receipt);}
    }
    public JSONObject correctWithRetirementForHost(Selection selected,String id,long revision,String text,MemoryBook.BeforeForget receipt)throws Exception{
        if(!Thread.holdsLock(this))throw new SecurityException("项目纠正须沿原facade准入，不接受JSON来源标记");
        synchronized(local){requireCurrent(selected);return book.correctProjectWithRetirement(selected,id,revision,text,receipt);}
    }
    /** A separate explicit native task-start action freezes current formal records/spans once.
     * Closing the management page does not revoke an already bound run; consent/RunControl do. */
    public synchronized ProjectMemoryAccess.Consent captureTaskConsentForOwner(Selection selected)throws Exception{
        if(sourceBridge==null)throw new UnsupportedOperationException("项目run记忆尚未接原撤回/上下文退休宿主");
        boolean hasSources;synchronized(local){requireCurrent(selected);hasSources=book.projectSourcesForOwner(selected).length()>0;}
        if(hasSources)synchronizeOriginalSources();synchronized(local){requireCurrent(selected);return book.captureProjectConsentForOwner(selected);}
    }
    /** Body-free archive admission only; no whole-project export or read consent. */
    public synchronized void requireArchiveSelectionForOwner(Selection selected)throws Exception{synchronized(local){requireCurrent(selected);selected.requireBook(book);}}
    /** Explicit selected project only; the private/global owner archive remains unchanged. */
    public synchronized JSONObject archiveForOwner(Selection selected)throws Exception{
        boolean hasSources;synchronized(local){requireCurrent(selected);hasSources=book.projectSourcesForOwner(selected).length()>0;}if(hasSources)synchronizeOriginalSources();synchronized(local){requireCurrent(selected);return book.projectArchiveForOwner(selected);}
    }
    /** Original archive confirmation and settled project map authorize passive placement only.
     * No management Selection or live task read Consent is issued by restoration. */
    public synchronized ProjectMemoryArchive.Placement prepareArchivePlacementForOwner(dev.pocketengine.features.workspace.OwnerDataArchives original,dev.pocketengine.features.workspace.OwnerDataArchives.Confirmed confirmed,dev.pocketengine.store.TaskStore.OwnerArchiveProjectMap map,JSONObject input)throws Exception{
        java.util.Objects.requireNonNull(original).requireConfirmed(java.util.Objects.requireNonNull(confirmed));java.util.Objects.requireNonNull(map);JSONObject archive=ProjectMemoryArchive.checked(input),meta=confirmed.metadata();String source=ProjectMemoryArchive.dataScope(archive).spaceId.substring(8);
        if(!"memory".equals(meta.getString("phase"))||!meta.getString("sectionDigest").equals(ProjectSources.digest(archive)))throw new SecurityException("项目记忆放置不是这份确切分段的原生恢复确认");
        if(map.originalPhaseId().isEmpty())throw new UnsupportedOperationException("请先完成这份原包的原项目恢复；不伪造sameUUID映射");JSONObject targets=map.forArchive(meta.getString("archiveSha256"));Object target=targets.opt(source);if(!(target instanceof String)||!((String)target).matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))throw new UnsupportedOperationException("原项目恢复没有此来源的已结算映射，未放入private或其它项目");
        synchronized(local){String revision=ProjectSources.digest(workspace.projectIdentityForHost((String)target));ProjectMemoryArchive.Placement placement=new ProjectMemoryArchive.Placement(this,book,original,confirmed,map,archive,(String)target,revision);requireArchivePlacement(placement);return placement;}
    }
    void requireArchivePlacement(ProjectMemoryArchive.Placement placement)throws Exception{
        if(placement==null||placement.issuer!=this||placement.book!=book)throw new SecurityException("项目只读放置不是原Book的native确认");placement.original.requireConfirmed(placement.confirmed);JSONObject meta=placement.confirmed.metadata();if(!"memory".equals(meta.getString("phase"))||!placement.archiveSha.equals(meta.getString("archiveSha256"))||!ProjectSources.digest(new JSONObject(placement.payload)).equals(meta.getString("sectionDigest"))||!placement.targetProject.equals(placement.map.forArchive(placement.archiveSha).optString(placement.sourceSpace.substring(8)))||!placement.targetRevision.equals(ProjectSources.digest(workspace.projectIdentityForHost(placement.targetProject))))throw new SecurityException("原项目映射/确认/目标catalog已变化，未恢复任何读取权限");
    }
    /** Same native facade -> Local -> Book admission order; no filesystem callback under Book. */
    void admitArchivePlacement(ProjectMemoryArchive.Placement placement)throws Exception{if(placement==null||placement.issuer!=this||!Thread.holdsLock(this)||!Thread.holdsLock(local)||placement.confirmed.original().isCancelled()||placement.confirmed.original().isPaused()||System.currentTimeMillis()>=placement.expiresAt)throw new SecurityException("项目档案必须沿当前原生放置准入");}
    public synchronized JSONObject restoreArchiveForOwner(ProjectMemoryArchive.Placement placement)throws Exception{requireArchivePlacement(placement);synchronized(local){requireArchivePlacement(placement);return book.restoreProjectArchive(placement);}}
    /** Body-free original restore lookup, not a project read token or retry instruction. */
    public synchronized JSONObject queryArchiveRestoreForOwner(JSONObject archive,String outerArchiveSha,String nativeAction)throws Exception{return book.queryProjectArchiveRestore(ProjectMemoryArchive.checked(archive),outerArchiveSha,nativeAction);}
    /** Project review never delegates a SourceRef to the unscoped global owner source page. */
    public synchronized JSONObject readFileSourceForOwner(Selection selected,SourceRef ref)throws Exception{
        synchronized(local){requireCurrent(selected);book.requireProjectSourceUseForOwner(selected,ref);}
        synchronizeOriginalSources();sourceBridge.prepareSourceHead(ref.kind,ref.sourceId,null);
        synchronized(local){requireCurrent(selected);book.requireProjectSourceUseForOwner(selected,ref);}
        dev.pocketengine.features.KnowledgeLearningWorkflow.SourceDocument document=sourceBridge.read(ref);
        if(document==null||!ref.equals(document.ref))throw new SecurityException("原项目来源不可读或已变化");
        synchronized(local){requireCurrent(selected);book.requireProjectSourceUseForOwner(selected,ref);}
        return new JSONObject().put("kind",ref.kind).put("title",document.title).put("text",document.text).put("sourceRef",ref.toJson()).put("historicalBodySnapshot",true).put("executionAuthorized",false);
    }
    /** Real committed VWS heads only, not Local uncommitted bytes, chat, team/group or model refs. */
    public synchronized JSONArray fileChoicesForOwner(Selection selected)throws Exception{
        synchronized(local){requireCurrent(selected);JSONArray files=workspace.handle("list",new JSONObject().put("projectId",selected.project)).getJSONArray("files"),out=new JSONArray();
            for(int i=0;i<files.length();i++){JSONObject row=files.getJSONObject(i);out.put(new JSONObject().put("path",row.getString("path")).put("version",row.getInt("version")).put("revisionId",row.getString("revisionId")).put("sha256",row.getString("sha256")).put("tombstone",row.optBoolean("deleted")).put("kind",row.optString("kind","text")).put("sizeBytes",row.getLong("sizeBytes")));}return out;
        }
    }
    /** Explicit native source selection. This stages an exact excerpt for owner review; no model
     * call, automatic acceptance, task context, source alias or new SourceLearningJob is created. */
    public synchronized JSONObject proposeFileForOwner(Selection selected,String path,int version,String revisionId,String sha256,int offset,int chars)throws Exception{
        synchronized(local){requireCurrent(selected);}synchronizeOriginalSources();
        SourceRef original;String excerpt;
        synchronized(local){
            requireCurrent(selected);if(offset<0||chars<1||chars>4000)throw new IllegalArgumentException("项目原文范围须为1–4000个UTF-16字符");
            var before=workspace.refreshFileHeadForHost(selected.project,path);
            if(!before.authorityKnown||before.deleted||before.version!=version||!revisionId.equals(before.revisionId)||!sha256.equals(before.sha256))throw new SecurityException("所选项目原文件版本已变化，请重新读取文件列表");
            if(before.sizeBytes<0||before.sizeBytes>1_048_576)throw new IllegalArgumentException("项目原文超过本次1MiB读取范围");
            var actual=workspace.fileSnapshotForHost(selected.project,path,version);
            if(actual.tombstone||actual.version!=version||!revisionId.equals(actual.revisionId)||!sha256.equals(actual.sha256))throw new SecurityException("真实原文件字节不是所选版本");
            String text=java.nio.charset.StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(actual.bytes())).toString();
            if(offset>=text.length()||offset>0&&Character.isLowSurrogate(text.charAt(offset))&&Character.isHighSurrogate(text.charAt(offset-1)))throw new IllegalArgumentException("项目原文起点不在UTF-16字符边界");
            int end=(int)Math.min(text.length(),(long)offset+chars);if(end<text.length()&&end>offset&&Character.isHighSurrogate(text.charAt(end-1)))end--;if(end<=offset)throw new IllegalArgumentException("原范围没有完整文字");excerpt=text.substring(offset,end);
            original=SourceRef.workspaceFile(selected.project,path,version,revisionId,sha256,offset,excerpt.length(),"owner","private");
            var after=workspace.currentFileHeadForHost(selected.project,path);if(!after.authorityKnown||after.deleted||after.version!=version||!revisionId.equals(after.revisionId)||!sha256.equals(after.sha256))throw new SecurityException("原文件读取期间变化，未交付旧正文");
        }
        // A source may have changed during IO. First retire its old global/project dependants
        // through the original Bridge receipt, then Book verifies the same pinned ref again.
        synchronizeOriginalSources();
        synchronized(local){requireCurrent(selected);return book.proposeProjectFileForOwner(selected,original,excerpt);}
    }
    private JSONObject facts(String checkoutId)throws Exception {
        JSONObject checkout=local.projectCheckoutForHost(checkoutId);String project=checkout.getString("projectId");
        if(!project.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))throw new SecurityException("不是原生项目身份");
        if(checkout.has("teamProjectCopy")||checkout.has("skillExecutionCopy")||checkout.has("nativeGitImportPending"))throw new SecurityException("团队/Skill/未发布副本未获得此主人项目记忆读写授权");
        if(!checkoutId.equals(checkout.getString("id")))throw new SecurityException("原项目副本来源不一致");
        JSONObject definition=workspace.projectIdentityForHost(project);
        // Namespace authority follows the selected native project/checkout occurrence, not every
        // source file revision. Editing unrelated files must not invalidate manual memory work.
        JSONObject occurrence=new JSONObject().put("id",checkout.getString("id")).put("projectId",project).put("createdAt",checkout.getLong("createdAt"));
        JSONObject pin=new JSONObject().put("checkout",occurrence).put("project",definition);
        return new JSONObject().put("ownerId","owner").put("spaceId","project:"+project).put("projectId",project).put("checkoutId",checkoutId).put("revision",ProjectSources.digest(pin)).put("memberReadAuthorized",false).put("groupRetentionAuthorized",false);
    }
    /** Explicit rejection prevents a supplied namespace from silently becoming owner/private. */
    public static void rejectNamespaceArguments(JSONObject value){
        if(value==null)return;
        for(String key:Set.of("namespace","memoryNamespace","projectId","namespaceToken","projectSelection"))if(value.has(key))throw new SecurityException("JSON项目/namespace字段不是记忆授权："+key);
        if(value.has("spaceId")&&!"private".equals(value.optString("spaceId"))||value.has("ownerId")&&!"owner".equals(value.optString("ownerId")))throw new SecurityException("JSON记忆身份不能选择项目/他人空间");
        JSONObject scope=value.optJSONObject("scope");
        if(value.has("scope")&&(scope==null||!"owner".equals(scope.optString("ownerId"))||!"private".equals(scope.optString("spaceId"))))throw new SecurityException("通用记忆入口不接受非主人私人namespace");
        for(String key:Set.of("record","changes","draft")){JSONObject nested=value.optJSONObject(key);if(nested!=null)rejectNamespaceArguments(nested);}
    }
}
