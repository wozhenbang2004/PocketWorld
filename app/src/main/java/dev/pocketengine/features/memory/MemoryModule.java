package dev.pocketengine.features.memory;

import dev.pocketengine.features.EngineModule;
import dev.pocketengine.features.FeatureContext;
import java.io.File;
import org.json.JSONArray;
import org.json.JSONObject;

public final class MemoryModule implements EngineModule {
    private final FeatureContext context;
    private final MemoryBook book;
    private final boolean sourceVerification;
    public MemoryModule(FeatureContext context)throws Exception {this(context,null);}
    /** The resolver reads host-owned evidence; payload fields never authenticate a caller or source. */
    public MemoryModule(FeatureContext context,SourceRef.Resolver sourceResolver)throws Exception {
        this(context,sourceResolver,new LexicalMemoryRetrievalBackend());
    }
    /** Native root may inject SQLiteMemoryRetrievalBackend.create(); existing constructors keep compatibility. */
    public MemoryModule(FeatureContext context,SourceRef.Resolver sourceResolver,MemoryRetrievalBackend retrieval)throws Exception {
        this.context=context;sourceVerification=sourceResolver!=null;
        try{book=new MemoryBook(new File(context.android.getFilesDir(),"memory/records.json"),sourceResolver,retrieval);}
        catch(Exception invalidBook){retrieval.close();throw invalidBook;}
    }
    @Override public String id(){return "memory";}
    /** Native owner archive UI only; intentionally absent from handle/model command routes. */
    public JSONObject ownerArchive(JSONObject scope)throws Exception{return book.ownerArchive(scope);}
    public JSONObject restoreOwnerArchive(JSONObject scope,JSONObject archive,String nativeImportId)throws Exception{return book.restoreOwnerArchive(scope,archive,nativeImportId);}
    public JSONObject ownerArchiveRestoration(JSONObject scope,String nativeImportId)throws Exception{return book.ownerArchiveRestoration(scope,nativeImportId);}
    public JSONObject queryOwnerArchiveRestore(JSONObject scope,String exactSectionDigest,String nativeImportId)throws Exception{return book.queryOwnerArchiveRestore(scope,exactSectionDigest,nativeImportId);}
    /** Native original task admission only; deliberately absent from handle/model JSON routes. */
    public MemoryBook.ProcedureSelection selectProcedureForNativeTask(JSONObject scope,String id,long revision)throws Exception{return book.selectProcedureForNativeTask(scope,id,revision);}
    @Override public JSONObject snapshot()throws Exception {
        JSONObject scope=new JSONObject().put("ownerId","owner").put("spaceId","private");
        JSONArray records=book.list(scope);JSONObject layers=new JSONObject().put("fact",0).put("preference",0).put("episodic",0).put("procedural",0);
        int coreSelected=0,coreActive=0;
        for(int i=0;i<records.length();i++){
            JSONObject record=records.getJSONObject(i);String layer=record.getString("layer");layers.put(layer,layers.optInt(layer)+1);
            if(record.optBoolean("core",false)){coreSelected++;if(record.optBoolean("active",false))coreActive++;}
        }
        JSONArray candidates=book.listCandidates(scope);JSONObject candidateCounts=new JSONObject().put("pending",0).put("accepted",0).put("rejected",0).put("invalidated",0);
        for(int i=0;i<candidates.length();i++){String status=candidates.getJSONObject(i).getString("status");candidateCounts.put(status,candidateCounts.getInt(status)+1);}
        JSONObject tiers=new JSONObject().put("core",0).put("episodic",0).put("semantic",0).put("procedural",0);
        for(int i=0;i<records.length();i++){String tier=MemoryBook.tierOf(records.getJSONObject(i));tiers.put(tier,tiers.getInt(tier)+1);}
        return new JSONObject().put("available",true).put("recordCount",records.length()).put("layers",layers)
                .put("memoryTiers",tiers).put("workingMemory","host_fenced_ephemeral_per_run").put("controlledConsolidation",true)
                .put("consolidationAcceptance","existing_native_candidate_review").put("maxConsolidationSourceChars",MemoryBook.MAX_CONSOLIDATION_SOURCE_CHARS)
                .put("scope",scope).put("retrieval",book.retrievalStatus().getString("backend")).put("retrievalBackend",book.retrievalStatus()).put("automaticLearning",false)
                .put("explicitExpiry",true).put("coreSelection","explicit").put("coreSelectedCount",coreSelected).put("coreActiveCount",coreActive)
                .put("maxCoreRecords",MemoryBook.MAX_CORE_RECORDS).put("maxContextRecords",MemoryBook.MAX_CONTEXT_RECORDS)
                .put("maxContextChars",MemoryBook.MAX_CONTEXT_CHARS)
                .put("sourceLifecycle",true).put("sourceVerificationConnected",sourceVerification).put("durableForgetBarriers",true)
                .put("explicitConflictReplacement","trusted_host_native_review_only")
                .put("learningCandidates",true).put("learningMode","host_proposed_user_reviewed").put("candidateCounts",candidateCounts)
                .put("maxLearningCandidates",MemoryBook.MAX_LEARNING_CANDIDATES).put("candidateAcceptance","trusted_host_native_only")
                .put("candidateRetention","trusted_host_native_only").put("candidateCapacity",book.candidateCapacity(scope))
                .put("learnedSkillExecution",false)
                .put("commands",new JSONArray(new String[]{"list","search","get","save","delete","consolidate","recall","setCore","contextSnapshot","importFromSources","sourceUpdated","sourceDeleted","forget","listCandidates","getCandidate","editCandidate","rejectCandidate"}));
    }
    @Override public JSONObject handle(String command,JSONObject input)throws Exception {
        JSONObject scope=input.optJSONObject("scope");
        if(scope==null){
            // New contextual APIs never infer an owner/space from absent or malformed input.
            if("contextSnapshot".equals(command)||"setCore".equals(command)||"importFromSources".equals(command)
                    ||"sourceUpdated".equals(command)||"sourceDeleted".equals(command)||"forget".equals(command)
                    ||"listCandidates".equals(command)||"getCandidate".equals(command)||"editCandidate".equals(command)||"rejectCandidate".equals(command))throw new IllegalArgumentException("必须明确指定记忆空间");
            scope=new JSONObject().put("ownerId","owner").put("spaceId","private");
        }
        JSONObject result;
        switch(command){
            case "list":return new JSONObject().put("records",book.list(scope));
            case "search":return new JSONObject().put("records",book.search(scope,input.optString("query"),Math.max(1,Math.min(100,input.optInt("limit",20)))));
            case "get":return new JSONObject().put("record",book.get(scope,input.getString("id")));
            case "recall":return book.recall(scope,input.optString("query"),input.optInt("limit",6));
            case "contextSnapshot":return contextSnapshot(scope,optionalQuery(input),optionalBudget(input,"limit",MemoryBook.MAX_CONTEXT_RECORDS),optionalBudget(input,"maxChars",MemoryBook.MAX_CONTEXT_CHARS));
            case "listCandidates":return new JSONObject().put("candidates",book.listCandidates(scope));
            case "getCandidate":return new JSONObject().put("candidate",book.getCandidate(scope,input.getString("id")));
            case "editCandidate":result=new JSONObject().put("candidate",book.editCandidate(scope,input.getString("id"),SourceRef.integer(input,"revision",1),input.getJSONObject("changes"),System.currentTimeMillis()));break;
            case "rejectCandidate":result=new JSONObject().put("candidate",book.rejectCandidate(scope,input.getString("id"),SourceRef.integer(input,"revision",1),input.getString("reason"),System.currentTimeMillis()));break;
            case "setCore":result=new JSONObject().put("record",book.setCore(scope,input.getString("id"),Math.toIntExact(SourceRef.integer(input,"revision",1)),MemoryBook.requiredBoolean(input,"core"),System.currentTimeMillis()));break;
            case "save":result=new JSONObject().put("record",book.save(scope,input.getJSONObject("record"),System.currentTimeMillis()));break;
            case "importFromSources":result=new JSONObject().put("record",book.importFromSources(scope,input.getJSONObject("record"),System.currentTimeMillis()));break;
            case "sourceUpdated":result=book.sourceUpdated(scope,SourceRef.fromJson(input.getJSONObject("sourceRef")));break;
            case "sourceDeleted":result=book.sourceDeleted(scope,SourceRef.fromJson(input.getJSONObject("sourceRef")));break;
            case "forget":result=new JSONObject().put("deleted",book.forget(scope,input.getString("id")));break;
            case "consolidate":result=new JSONObject().put("record",book.consolidate(scope,input,System.currentTimeMillis()));break;
            case "delete":result=new JSONObject().put("deleted",book.delete(scope,input.getString("id")));break;
            default:throw new IllegalArgumentException("未知记忆操作");
        }
        context.emit(id(),new JSONObject().put("event","memory_changed").put("snapshot",snapshot()));return result;
    }
    /** Native-only explicit review; never a model command or a preflight followed by an unfenced delete. */
    public JSONObject forgetReviewed(JSONObject authenticatedScope,String id,long expectedRevision)throws Exception {
        JSONObject result=new JSONObject().put("deleted",book.forgetReviewed(authenticatedScope,id,expectedRevision));
        context.emit(id(),new JSONObject().put("event","memory_changed").put("snapshot",snapshot()));return result;
    }
    public dev.pocketengine.features.skills.ExperienceOrigin.State resolveExperience(String ownerId,String spaceId,String recordId)throws Exception {
        return book.resolveExperience(ownerId,spaceId,recordId);
    }
    public JSONObject reviewedProcedure(JSONObject authenticatedScope,String id,long expectedRevision)throws Exception {
        return book.reviewedProcedure(authenticatedScope,id,expectedRevision);
    }
    public JSONArray sourceRefsForSynchronization(JSONObject authenticatedScope)throws Exception {
        return book.sourceRefsForSynchronization(authenticatedScope);
    }
    /** Host-only read; intentionally not exposed through handle() or model tools. */
    public boolean isSourceForgotten(JSONObject authenticatedScope,SourceRef ref)throws Exception {
        return book.isSourceForgotten(authenticatedScope,ref);
    }
    public JSONObject forgetReviewed(JSONObject authenticatedScope,String id,long expectedRevision,MemoryBook.BeforeForget receipt)throws Exception {
        JSONObject result=book.forgetReviewed(authenticatedScope,id,expectedRevision,receipt);
        context.emit(id(),new JSONObject().put("event","memory_changed").put("snapshot",snapshot()));return result;
    }
    public JSONObject correctReviewed(JSONObject authenticatedScope,String id,long expectedRevision,String text,MemoryBook.BeforeForget receipt)throws Exception {
        JSONObject result=book.correctReviewed(authenticatedScope,id,expectedRevision,text,receipt,System.currentTimeMillis());
        context.emit(id(),new JSONObject().put("event","memory_changed").put("snapshot",snapshot()));return result;
    }
    /** Native owner review only. Never expose replacement consent through handle/model/plugin payloads. */
    public JSONObject supersedeReviewed(JSONObject authenticatedScope,String keptId,long keptRevision,String replacedId,long replacedRevision,MemoryBook.BeforeForget receipt)throws Exception {
        JSONObject result=book.supersedeReviewed(authenticatedScope,keptId,keptRevision,replacedId,replacedRevision,receipt,System.currentTimeMillis());
        context.emit(id(),new JSONObject().put("event","memory_changed").put("snapshot",snapshot()));return result;
    }
    /** Host must derive scope from its authenticated identity, and inject only as reference data. */
    public JSONObject contextSnapshot(JSONObject scope,String query,int limit,int maxChars)throws Exception {
        return book.contextSnapshot(scope,query,limit,maxChars);
    }
    /** Native member audience only; never exposed as a caller supplied EngineModule JSON scope. */
    public JSONObject contextSnapshot(JSONObject scope,String query,int limit,int maxChars,ReadAudience audience)throws Exception {
        return book.contextSnapshot(scope,query,limit,maxChars,audience);
    }
    public JSONObject searchAudience(JSONObject scope,String query,int limit,ReadAudience audience)throws Exception{
        if(audience==null)throw new SecurityException("成员检索必须保留原生资料范围");
        return new JSONObject().put("records",book.search(scope,query,limit,audience)).put("executionAuthorized",false).put("audienceRestricted",true);
    }

    /** Same book; native task/send/source read ports only, never a model/handle command. */
    public TaskMemoryUse taskMemoryUse(TaskMemoryUse.HostReader host,TaskMemoryUse.SourceReader sources){return new TaskMemoryUse(book,host,sources);}
    /** Host-only exact-version consolidation input, intentionally absent from raw JSON/model dispatch. */
    public JSONObject consolidationSnapshot(JSONObject authenticatedScope,JSONArray selections)throws Exception {
        return book.consolidationSnapshot(authenticatedScope,selections,System.currentTimeMillis());
    }
    public JSONObject proposeConsolidation(JSONObject authenticatedScope,JSONObject draft,JSONArray selections)throws Exception {
        JSONObject result=new JSONObject().put("candidate",book.proposeConsolidation(authenticatedScope,draft,selections,System.currentTimeMillis()));
        context.emit(id(),new JSONObject().put("event","memory_changed").put("snapshot",snapshot()));return result;
    }
    /**
     * Not a JSON command: only the trusted host may call this after an explicit user re-save.
     * It bypasses a forget barrier for this save only, not stale/deleted evidence or later imports.
     */
    public JSONObject resaveForgotten(JSONObject authenticatedScope,JSONObject record)throws Exception {
        JSONObject result=new JSONObject().put("record",book.resaveForgotten(authenticatedScope,record,System.currentTimeMillis()));
        context.emit(id(),new JSONObject().put("event","memory_changed").put("snapshot",snapshot()));return result;
    }
    /** Host-native automatic extraction/import; the host supplies authenticated scope and real source IDs. */
    public JSONObject importFromSources(JSONObject authenticatedScope,JSONObject record)throws Exception {
        JSONObject result=new JSONObject().put("record",book.importFromSources(authenticatedScope,record,System.currentTimeMillis()));
        context.emit(id(),new JSONObject().put("event","memory_changed").put("snapshot",snapshot()));return result;
    }
    /**
     * Host model worker supplies one result plus its actual source set. The model never supplies
     * the authenticated scope or source identities. No background model loop is started here.
     */
    public JSONObject proposeLearning(JSONObject authenticatedScope,JSONObject draft,SourceRef... hostSources)throws Exception {
        JSONObject result=new JSONObject().put("candidate",book.proposeLearning(authenticatedScope,draft,System.currentTimeMillis(),hostSources));
        // The Book commit is authoritative. A failed notification must not turn a committed
        // candidate into a falsely cancellable/rolled-back native request.
        try{context.emit(id(),new JSONObject().put("event","memory_changed").put("snapshot",snapshot()));}
        catch(Exception unavailable){result.put("notificationPending",true);}return result;
    }
    /** Native owner-review action only: do not expose through a model, plugin or raw JSON dispatch. */
    public JSONObject acceptCandidate(JSONObject authenticatedScope,String id,long revision)throws Exception {
        JSONObject result=book.acceptCandidate(authenticatedScope,id,revision,System.currentTimeMillis());
        context.emit(id(),new JSONObject().put("event","memory_changed").put("snapshot",snapshot()));return result;
    }
    /** Native owner-management entry points only; deliberately absent from handle/commands. */
    public JSONObject candidateCapacity(JSONObject authenticatedScope)throws Exception {
        return book.candidateCapacity(authenticatedScope);
    }
    public JSONObject listCandidateHistory(JSONObject authenticatedScope)throws Exception {
        return new JSONObject().put("history",book.listCandidateHistory(authenticatedScope));
    }
    public JSONObject archiveCandidates(JSONObject authenticatedScope,JSONArray selections)throws Exception {
        JSONObject result=book.archiveCandidates(authenticatedScope,selections,System.currentTimeMillis());
        context.emit(id(),new JSONObject().put("event","memory_changed").put("snapshot",snapshot()));return result;
    }
    /** Call only for an explicit owner action on the displayed id/revision/content, not model consent. */
    public JSONObject reconsiderRejected(JSONObject authenticatedScope,String id,long revision,JSONObject draft)throws Exception {
        JSONObject result=new JSONObject().put("candidate",book.reconsiderRejected(authenticatedScope,id,revision,draft,System.currentTimeMillis()));
        context.emit(id(),new JSONObject().put("event","memory_changed").put("snapshot",snapshot()));return result;
    }
    public JSONObject sourceUpdated(JSONObject authenticatedScope,SourceRef ref)throws Exception {
        JSONObject result=book.sourceUpdated(authenticatedScope,ref);
        context.emit(id(),new JSONObject().put("event","memory_changed").put("snapshot",snapshot()));return result;
    }
    public JSONObject sourceDeleted(JSONObject authenticatedScope,SourceRef ref)throws Exception {
        JSONObject result=book.sourceDeleted(authenticatedScope,ref);
        context.emit(id(),new JSONObject().put("event","memory_changed").put("snapshot",snapshot()));return result;
    }
    /** Native source bridge uses the same durable host receipt before dropping derived memory. */
    public JSONObject sourceUpdated(JSONObject authenticatedScope,SourceRef ref,MemoryBook.BeforeForget receipt)throws Exception {
        JSONObject result=book.sourceUpdated(authenticatedScope,ref,receipt);
        context.emit(id(),new JSONObject().put("event","memory_changed").put("snapshot",snapshot()));return result;
    }
    public JSONObject sourceDeleted(JSONObject authenticatedScope,SourceRef ref,MemoryBook.BeforeForget receipt)throws Exception {
        JSONObject result=book.sourceDeleted(authenticatedScope,ref,receipt);
        context.emit(id(),new JSONObject().put("event","memory_changed").put("snapshot",snapshot()));return result;
    }
    private static String optionalQuery(JSONObject input){
        if(!input.has("query"))return "";Object value=input.opt("query");
        if(!(value instanceof String))throw new IllegalArgumentException("query必须为文字");return (String)value;
    }
    private static int optionalBudget(JSONObject input,String key,int defaultValue){
        return input.has(key)?requiredInteger(input,key,0):defaultValue;
    }
    private static int requiredInteger(JSONObject input,String key,int minimum){
        Object value=input.opt(key);
        if(!(value instanceof Number)||((Number)value).doubleValue()!=((Number)value).intValue()||((Number)value).intValue()<minimum)
            throw new IllegalArgumentException(key+"必须为不小于"+minimum+"的整数");
        return ((Number)value).intValue();
    }
    @Override public void close(){try{book.closeRetrieval();}catch(Exception ignored){}}
}
