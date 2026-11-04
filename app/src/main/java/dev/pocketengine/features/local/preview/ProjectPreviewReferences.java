package dev.pocketengine.features.local.preview;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.local.LocalModule;
import dev.pocketengine.features.memory.TaskMemoryTrace;
import dev.pocketengine.store.TaskStore;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Original native document selections. Saved descriptors never recreate their authority. */
public final class ProjectPreviewReferences implements AutoCloseable {
    private static final int MAX_SELECTIONS=256;
    private final TaskStore tasks;
    private final LocalModule local;
    private final Map<String,Entry> selections=new LinkedHashMap<>();
    private boolean closed;
    private record Entry(ProjectPreviewDocument.Selection selection,JSONObject reference,String fingerprint){}

    public ProjectPreviewReferences(TaskStore tasks,LocalModule local){this.tasks=Objects.requireNonNull(tasks);this.local=Objects.requireNonNull(local);}

    public JSONObject register(String taskId,ProjectPreviewDocument.Selection selection)throws Exception {
        return registerOriginal(taskId,null,selection);
    }
    public JSONObject register(String taskId,String packageOperationId,ProjectPreviewDocument.Selection selection)throws Exception {
        if(packageOperationId==null||packageOperationId.isEmpty())throw new SecurityException("缺少原固定包操作");
        return registerOriginal(taskId,packageOperationId,selection);
    }
    private JSONObject registerOriginal(String taskId,String packageOperationId,ProjectPreviewDocument.Selection selection)throws Exception {
        unlocked();Objects.requireNonNull(selection);open();pruneReleasedDocuments();selection.requireCurrent();
        ProjectPreviewBundle.Verified bundle=selection.bundle();
        if((packageOperationId==null)!=(bundle.artifactId==null))throw new SecurityException("活动预览与原固定包入口不能互换");
        JSONObject source=source(taskId,packageOperationId,bundle.identity);requireBundle(source,bundle);
        requireLocalCurrent(bundle,source);selection.requireCurrent();
        requireSameSource(source,source(taskId,packageOperationId,bundle.identity));
        JSONObject value=selection.snapshot();
        value.put("referenceId",UUID.randomUUID().toString()).put("taskId",source.getString("taskId"))
                .put("runId",source.getString("runId")).put("epoch",source.get("epoch"))
                .put("conversationId",source.getString("conversationId")).put("projectId",bundle.projectId)
                .put("checkoutId",bundle.checkoutId).put("previewId",bundle.identity).put("sourceDigest",bundle.sourceDigest)
                .put("untrusted",true).put("selectorIsSourceLocation",false);
        if(packageOperationId!=null)for(String key:new String[]{"sourceKind","artifactId","artifactSha256","packageOperationId"})value.put(key,source.get(key));
        JSONObject reference=PreviewElementRef.checked(value);String fingerprint=fingerprint(reference);
        selection.requireCurrent();
        synchronized(this){openLocked();while(selections.size()>=MAX_SELECTIONS)selections.remove(selections.keySet().iterator().next());selections.put(reference.getString("referenceId"),new Entry(selection,reference,fingerprint));}
        return new JSONObject(reference.toString());
    }

    /** Precommit only: the existing/retained recipient is supplied by AssistantModule, not the page. */
    public JSONArray admit(String conversationId,JSONObject expectedRecipient,JSONArray refs,RunControl original)throws Exception {
        unlocked();JSONArray checked=PreviewElementRef.checked(refs);if(checked.length()==0)return checked;
        if(expectedRecipient==null||expectedRecipient.length()!=3||!expectedRecipient.has("taskId")||!expectedRecipient.has("runId")||!expectedRecipient.has("epoch"))throw new SecurityException("元素引用只能进入原项目任务，不能自动新建执行");
        String task=text(expectedRecipient,"taskId"),run=text(expectedRecipient,"runId");int epoch=positiveInt(expectedRecipient,"epoch");
        currentControl(original);if(!original.matchesHostRun(task,run,epoch))throw new SecurityException("原项目接收执行已变化，请核对原草稿");
        requireRecipientMetadata(conversationId,checked,task,run,epoch);requireSourcesCurrent(conversationId,checked);
        currentControl(original);requireRecipientMetadata(conversationId,checked,task,run,epoch);return checked;
    }

    /** Read-only source revalidation, also used to retire stale historical projections. */
    public void requireSourcesCurrent(String conversationId,JSONArray refs)throws Exception {
        unlocked();JSONArray checked=PreviewElementRef.checked(refs);List<Entry> entries=lookup(conversationId,checked);
        for(Entry entry:entries){
            ProjectPreviewBundle.Verified bundle=entry.selection.bundle();
            try{
                entry.selection.requireCurrent();JSONObject ref=entry.reference;
                JSONObject before=source(ref);requireReferenceSource(ref,before);requireBundle(before,bundle);
                requireLocalCurrent(bundle,before);entry.selection.requireCurrent();
                JSONObject after=source(ref);requireSameSource(before,after);requireReferenceSource(ref,after);
                requireRetained(entry);
            }catch(Exception unavailable){retireBundle(bundle);throw unavailable;}
        }
    }

    /** Real physical request: same receiver object, current run and original source-read grant. */
    public void requireCurrent(String conversationId,JSONArray refs,RunControl receiver)throws Exception {
        unlocked();JSONArray checked=PreviewElementRef.checked(refs);if(checked.length()==0)return;
        currentControl(receiver);RunControl.HostRunIdentity identity=receiver.hostRunIdentity();
        if(identity==null)throw new SecurityException("元素引用缺少原接收执行");
        requireRecipients(conversationId,checked,receiver,identity);requireSourcesCurrent(conversationId,checked);
        currentControl(receiver);requireRecipients(conversationId,checked,receiver,identity);
    }

    /** Presentation observation only. No file IO, SQL lookup, receiver grant or source-hash claim. */
    public boolean available(String conversationId,JSONArray refs){
        try{JSONArray checked=PreviewElementRef.checked(refs);for(Entry entry:lookup(conversationId,checked))entry.selection.requireCurrent();return true;}
        catch(Exception unavailable){return false;}
    }

    private void requireRecipients(String conversation,JSONArray refs,RunControl original,RunControl.HostRunIdentity identity)throws Exception {
        if(!original.matchesHostRun(identity.taskId(),identity.runId(),identity.epoch()))throw new SecurityException("原接收执行已换代");
        for(int i=0;i<refs.length();i++){
            JSONObject ref=refs.getJSONObject(i);if(!identity.taskId().equals(ref.getString("taskId"))||!conversation.equals(ref.getString("conversationId")))throw new SecurityException("元素引用不能移入另一个任务或会话");
            tasks.requireOwnerPreviewRecipient(identity.taskId(),identity.runId(),identity.epoch(),conversation,ref.getString("projectId"),ref.getString("checkoutId"),original);
        }
    }

    private void requireRecipientMetadata(String conversation,JSONArray refs,String task,String run,int epoch)throws Exception {
        JSONObject actual=tasks.get(task,false),context=actual==null?null:actual.optJSONObject("developmentContext");
        if(actual==null||context==null||!"assistant".equals(actual.optString("kind"))||!"owner".equals(actual.optString("actorId"))||!"private".equals(actual.optString("spaceId"))||!"capabilities".equals(actual.optString("executionMode"))||!actual.optString("status").matches("preparing|running|paused|pausing")||actual.optJSONObject("origin")!=null||actual.optBoolean("cancelRequested")||!conversation.equals(actual.optString("conversationId"))||!run.equals(actual.optString("runId"))||epoch!=actual.optInt("coordinatorEpoch"))throw new SecurityException("原项目任务或接收执行已变化，草稿仍保留");
        for(int i=0;i<refs.length();i++){
            JSONObject ref=refs.getJSONObject(i);if(!task.equals(ref.getString("taskId"))||!conversation.equals(ref.getString("conversationId"))||!context.getString("projectId").equals(ref.getString("projectId"))||!context.getString("checkoutId").equals(ref.getString("checkoutId")))throw new SecurityException("元素引用不属于原项目接收任务");
        }
    }

    private List<Entry> lookup(String conversation,JSONArray refs)throws Exception {
        List<Entry> result=new ArrayList<>();
        for(int i=0;i<refs.length();i++){
            JSONObject ref=refs.getJSONObject(i);if(!conversation.equals(ref.getString("conversationId")))throw new SecurityException("元素引用不属于原会话");
            String id=ref.getString("referenceId"),hash=fingerprint(ref);Entry entry;
            synchronized(this){openLocked();entry=selections.get(id);}
            if(entry==null||!entry.fingerprint.equals(hash))throw new SecurityException("原元素选择已不可用，请重新打开原预览选择");
            entry.selection.requireCurrent();result.add(entry);
        }
        return result;
    }
    private void requireRetained(Entry entry){synchronized(this){openLocked();if(selections.get(entry.reference.optString("referenceId"))!=entry)throw new SecurityException("原元素选择已释放");}}
    private void pruneReleasedDocuments(){
        List<Entry> prior;synchronized(this){openLocked();prior=new ArrayList<>(selections.values());}
        for(Entry entry:prior)try{entry.selection.requireCurrent();}catch(SecurityException unavailable){synchronized(this){selections.remove(entry.reference.optString("referenceId"),entry);}}
    }
    private void retireBundle(ProjectPreviewBundle.Verified bundle){synchronized(this){selections.values().removeIf(entry->entry.selection.bundle()==bundle);}}
    private JSONObject source(String task,String operation,String preview)throws Exception {return operation==null?tasks.ownerPreviewSource(task,preview):tasks.ownerPackagePreviewSource(task,operation);}
    private JSONObject source(JSONObject ref)throws Exception {return source(ref.getString("taskId"),fixed(ref)?ref.getString("packageOperationId"):null,ref.getString("previewId"));}
    private static boolean fixed(JSONObject source){return "fixed_workspace_project_artifact".equals(source.optString("sourceKind"));}
    private void requireLocalCurrent(ProjectPreviewBundle.Verified bundle,JSONObject source)throws Exception {
        if(fixed(source))local.requireProjectPackagePreviewCurrent(bundle,source);else local.requireProjectPreviewCurrent(bundle);
    }
    private static void requireBundle(JSONObject source,ProjectPreviewBundle.Verified bundle)throws Exception {
        if(!bundle.checkoutId.equals(source.getString("checkoutId"))||!bundle.projectId.equals(source.getString("projectId"))||!bundle.sourceDigest.equals(source.getString("sourceDigest")))throw new SecurityException("原预览资源身份不一致");
        if(fixed(source)){
            if(bundle.artifactId==null||!bundle.artifactId.equals(source.getString("artifactId"))||!bundle.artifactSha256.equals(source.getString("artifactSha256")))throw new SecurityException("原固定包预览不是这个操作保存的字节");
        }else if(bundle.artifactId!=null||!bundle.identity.equals(source.getString("previewId")))throw new SecurityException("原活动预览资源身份不一致");
    }
    private static void requireReferenceSource(JSONObject ref,JSONObject source)throws Exception {
        if(fixed(ref)!=fixed(source))throw new SecurityException("原元素来源种类已变化");
        for(String key:new String[]{"taskId","conversationId","runId","projectId","checkoutId","sourceDigest"})if(!Objects.equals(ref.get(key),source.get(key)))throw new SecurityException("原元素来源的产生执行或项目已变化");
        if(positiveInt(ref,"epoch")!=positiveInt(source,"epoch"))throw new SecurityException("原元素来源的产生执行代次已变化");
        if(fixed(ref)){
            for(String key:new String[]{"sourceKind","artifactId","artifactSha256","packageOperationId"})if(!Objects.equals(ref.get(key),source.get(key)))throw new SecurityException("原固定包来源操作或字节已变化");
        }else if(!ref.getString("previewId").equals(source.getString("previewId")))throw new SecurityException("原活动预览身份已变化");
    }
    private static void requireSameSource(JSONObject before,JSONObject after)throws Exception {requireReferenceSource(before,after);}
    private static void currentControl(RunControl control)throws Exception {if(control==null||control.isCancelled()||control.isPaused())throw new SecurityException("原元素接收执行已停止或暂停");control.check();}
    private static String fingerprint(JSONObject ref)throws Exception{return TaskMemoryTrace.rawFingerprint(ref);}
    private static String text(JSONObject object,String key)throws Exception {Object value=object.get(key);if(!(value instanceof String)||((String)value).isEmpty())throw new SecurityException("原接收执行身份无效");return (String)value;}
    private static int positiveInt(JSONObject object,String key)throws Exception {Object value=object.get(key);if(!(value instanceof Integer||value instanceof Long)||((Number)value).longValue()<1||((Number)value).longValue()>Integer.MAX_VALUE)throw new SecurityException("原接收执行代次无效");return ((Number)value).intValue();}
    private void unlocked(){if(Thread.holdsLock(tasks))throw new IllegalStateException("preview_source_check_requires_unlocked_tasks");}
    private void open(){synchronized(this){openLocked();}}
    private void openLocked(){if(closed)throw new SecurityException("原预览引用宿主已关闭");}
    @Override public void close(){synchronized(this){closed=true;selections.clear();}}
}
