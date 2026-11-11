package dev.pocketengine.runtime.extensions;

import dev.pocketengine.core.CapabilitySnapshot;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.core.ScopedToolCatalog;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Metadata state inside the existing original run; no execution, task store or JSON restore API. */
public final class ToolDiscoverySession implements AutoCloseable {
    /** Metadata occupies a schema slot only while at least one real definition is hidden. */
    public static boolean shouldDefer(int realToolCount,int capabilityNameCount){
        if(realToolCount<0||capabilityNameCount<0)throw new IllegalArgumentException("negative_native_tool_count");
        return realToolCount>0&&realToolCount<=100&&capabilityNameCount>=realToolCount&&capabilityNameCount<=512;
    }
    // Only definitions already admitted to the original scope can become resident.
    public static final Set<String> DEFAULT_RESIDENT=Set.of("owner_work_list","owner_work_directory","owner_work_get","owner_work_start","owner_work_input","owner_work_control",
            "memory_search","memory_recall","memory_raw_query","memory_raw_read","memory_history_search","memory_history_read",
            "workspace_projects","workspace_list","workspace_read","skills_list","skills_read","skills_execute","attachments_list","attachments_read_text","project_inspect","project_read_file","project_search","browser_task_observe","browser_task_read");
    private record Pending(String digest,Set<String> identities) { }
    private final String taskId,runId;
    private final int epoch;
    private final RunControl original;
    private final Set<String> activated=new HashSet<>();
    private final Map<String,Pending> pending=new LinkedHashMap<>();
    private ScopedToolCatalog catalog;
    private Set<String> published=Set.of();
    private boolean closed,stale;

    public ToolDiscoverySession(String taskId,String runId,int epoch,RunControl original){
        this.taskId=java.util.Objects.requireNonNull(taskId);this.runId=java.util.Objects.requireNonNull(runId);this.epoch=epoch;this.original=java.util.Objects.requireNonNull(original);
        if(!original.hasHostRun()||!original.matchesHostRun(taskId,runId,epoch))throw new SecurityException("tool_discovery_original_run_required");
    }
    /** The original host calls this after its full-scope authorization; hiding definitions does not change that set. */
    public synchronized void beginRequest(CapabilitySnapshot scope,JSONArray definitions,Map<String,ScopedToolCatalog.SourcePin> sources,Set<String> resident,RunControl control)throws Exception {
        original(control);ScopedToolCatalog next=ScopedToolCatalog.capture(scope,taskId,runId,epoch,original,definitions,sources,resident);
        if(catalog==null||!catalog.revision().equals(next.revision()))activated.clear();
        activated.removeIf(identity->!next.containsIdentity(identity));pending.clear();published=Set.copyOf(activated);catalog=next;stale=false;
    }
    public synchronized JSONArray requestTools(RunControl control)throws Exception{check(control);return catalog.requestTools(published);}
    public synchronized JSONObject summary(RunControl control)throws Exception{check(control);return catalog.summary(published);}
    public synchronized void validateSearch(JSONObject arguments,RunControl control)throws Exception{requirePublished(ScopedToolCatalog.SEARCH_TOOL,control);ScopedToolCatalog.SearchRequest.fromJson(arguments);}
    public synchronized JSONObject search(JSONObject arguments,RunControl control)throws Exception {
        requirePublished(ScopedToolCatalog.SEARCH_TOOL,control);if(pending.size()>=16)throw new IllegalStateException("tool_discovery_uncommitted_receipt_bound");
        ScopedToolCatalog.Page page=catalog.search(ScopedToolCatalog.SearchRequest.fromJson(arguments));String id=UUID.randomUUID().toString();
        JSONObject receipt=page.view().put("discoveryId",id).put("taskId",taskId).put("runId",runId).put("epoch",epoch).put("activation","after_original_checkpoint").put("ok",true);
        check(control);pending.put(id,new Pending(ScopedToolCatalog.fingerprint(receipt),page.identities()));return receipt;
    }
    /** Native afterTool callback only, after the existing Runner's real receipt/history checkpoint succeeds.
     * JSON cannot recreate this session's one-use pending object, original control or scope. */
    public synchronized void afterTool(String tool,JSONObject originalReceipt,RunControl control)throws Exception {
        if(!ScopedToolCatalog.SEARCH_TOOL.equals(tool))return;check(control);
        Object raw=originalReceipt.opt("discoveryId");Pending issued=raw instanceof String?pending.remove((String)raw):null;
        if(issued==null||!issued.digest.equals(ScopedToolCatalog.fingerprint(originalReceipt)))throw new SecurityException("tool_discovery_receipt_not_original_or_changed");
        check(control);activated.addAll(issued.identities);
        // Keep this request's published definitions unchanged. Only the next original beginRequest exposes them.
    }
    public synchronized void requirePublished(String name,RunControl control)throws Exception {
        check(control);if(!catalog.exposes(name,published))throw new SecurityException("tool_schema_not_published_for_this_request");
    }
    public synchronized boolean current(RunControl control){try{check(control);return true;}catch(Exception unavailable){return false;}}
    private void original(RunControl control)throws InterruptedException{if(closed||control!=original||!original.matchesHostRun(taskId,runId,epoch))throw new SecurityException("tool_discovery_foreign_or_closed_run");original.check();}
    private void check(RunControl control)throws Exception {
        original(control);if(stale||catalog==null)throw new SecurityException("tool_discovery_request_not_current");
        try{catalog.check();}catch(Exception revoked){stale=true;activated.clear();pending.clear();published=Set.of();throw revoked;}
    }
    @Override public synchronized void close(){closed=true;activated.clear();pending.clear();published=Set.of();catalog=null;}
}
