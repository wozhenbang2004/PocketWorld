package dev.pocketengine.features.memory;

import dev.pocketengine.features.workspace.OwnerDataArchives;
import dev.pocketengine.store.TaskStore;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Closed project data in the original archive/Book, never another store or a read grant.
 * Validation of a JSON namespace is deliberately separate from native export/placement consent. */
public final class ProjectMemoryArchive {
    public static final String FORMAT="pocketworld.project-memory-archive.v1",IMPORT_FORMAT="pocketworld.project-memory-archive-import.v1";
    private static final Set<String> TOP=Set.of("format","version","ownerId","spaceId","records","sources","recordForgetBarriers","learningCandidates","candidateHistory","priorImports","namespaceSourceFences","counts","sectionDigest","executionAuthorized","activeAuthorityIncluded");
    private static final Set<String> IMPORT=Set.of("format","archiveImportId","sectionDigest","ownerId","spaceId","originalSpaceId","archiveSha256","projectPhaseId","importedAt","mappings","sourceEvidence","recordForgetEvidence","candidateMetadata","candidateHistoryEvidence","priorImports","namespaceSourceEvidence","counts");
    private ProjectMemoryArchive(){}
    static MemoryBook.Scope dataScope(JSONObject value){MemoryBook.Scope scope=MemoryBook.Scope.stored(value);if(!"owner".equals(scope.ownerId)||!scope.isProject())throw new SecurityException("仅接受原主人项目档案数据；JSON不是namespace权限");return scope;}
    static boolean isProject(JSONObject value){return value!=null&&(FORMAT.equals(value.optString("format"))||IMPORT_FORMAT.equals(value.optString("format")));}
    static Set<String> origins(JSONObject value,boolean imported)throws Exception{
        Set<String> result=new HashSet<>();result.add(dataScope(value).spaceId);if(imported)result.add(dataScope(new JSONObject().put("ownerId","owner").put("spaceId",value.getString("originalSpaceId"))).spaceId);
        JSONArray prior=value.getJSONArray("priorImports");for(int i=0;i<prior.length();i++)result.addAll(origins(prior.getJSONObject(i),true));return result;
    }
    private static void sourceState(JSONObject row,Set<String> origins)throws Exception{
        SourceRef ref=SourceRef.fromJson(row);Set<String> keys=new HashSet<>(Set.of("kind","sourceId","revision","ownerId","spaceId","deleted","forgottenThrough"));keys.add("workspace");MemoryArchive.keys(row,keys);
        if(row.length()!=keys.size()||!"workspace_file".equals(ref.kind)||!"owner".equals(ref.ownerId)||!"private".equals(ref.spaceId)||ref.workspace==null||!origins.contains("project:"+ref.workspace.projectId)||!(row.opt("deleted") instanceof Boolean)||SourceRef.integer(row,"forgottenThrough",0)>ref.revision)throw new SecurityException("项目档案来源不是同原项目的准确private workspace lineage");
    }
    static void namespaceFence(JSONObject row,Set<String> origins)throws Exception{
        MemoryArchive.keys(row,Set.of("ownerId","spaceId","sourceRef","forgottenThrough"));MemoryBook.Scope scope=dataScope(row);SourceRef ref=SourceRef.fromJson(row.getJSONObject("sourceRef"));if(row.length()!=4||!origins.contains(scope.spaceId)||!scope.acceptsSource(ref)||SourceRef.integer(row,"forgottenThrough",1)>ref.revision)throw new SecurityException("项目使用屏障缺少准确原namespace/SourceRef；未改写来源key");
    }
    private static void recordFence(JSONObject row,Set<String> origins)throws Exception{MemoryBook.Scope scope=dataScope(row);if(!origins.contains(scope.spaceId))throw new SecurityException("项目档案包含无原沿革的record屏障");MemoryArchive.recordFence(row,scope);}
    public static JSONObject counts(JSONObject value)throws Exception{return MemoryArchive.counts(value).put("namespaceSourceTombstones",value.getJSONArray("namespaceSourceFences").length());}
    /** Data/schema/hash checks only. No caller receives a Book Scope, Selection or Consent. */
    public static JSONObject checked(JSONObject input)throws Exception{
        if(input==null)throw new IllegalArgumentException("缺少项目记忆档案");MemoryArchive.bounded(input);JSONObject value=new JSONObject(input.toString());MemoryArchive.keys(value,TOP);MemoryBook.Scope scope=dataScope(value);
        if(value.length()!=TOP.size()||!FORMAT.equals(value.optString("format"))||SourceRef.integer(value,"version",1)!=1||!Boolean.FALSE.equals(value.opt("executionAuthorized"))||!Boolean.FALSE.equals(value.opt("activeAuthorityIncluded")))throw new SecurityException("项目档案格式/权限边界无效");
        JSONArray records=value.getJSONArray("records"),candidates=value.getJSONArray("learningCandidates"),sources=value.getJSONArray("sources"),fences=value.getJSONArray("recordForgetBarriers"),history=value.getJSONArray("candidateHistory"),prior=value.getJSONArray("priorImports"),namespace=value.getJSONArray("namespaceSourceFences");
        if(records.length()>1000||candidates.length()>200||sources.length()>10000||fences.length()>1000||history.length()>1000||prior.length()>32||namespace.length()>10000)throw new IllegalArgumentException("项目档案超过原Book有界容量");
        for(int i=0;i<prior.length();i++)checkImportMetadata(prior.getJSONObject(i),0);Set<String> origins=origins(value,false),ids=new HashSet<>();
        for(int i=0;i<records.length();i++){JSONObject row=records.getJSONObject(i);MemoryArchive.record(row,scope);if(!ids.add(row.getString("id")))throw new SecurityException("项目原记录ID重复");}ids.clear();
        for(int i=0;i<candidates.length();i++){JSONObject row=candidates.getJSONObject(i);MemoryArchive.candidate(row,scope);if(!ids.add(row.getString("id")))throw new SecurityException("项目原候选ID重复");}
        for(int i=0;i<sources.length();i++)sourceState(sources.getJSONObject(i),origins);for(int i=0;i<fences.length();i++)recordFence(fences.getJSONObject(i),origins);for(int i=0;i<history.length();i++)MemoryArchive.history(history.getJSONObject(i),scope);for(int i=0;i<namespace.length();i++)namespaceFence(namespace.getJSONObject(i),origins);
        java.util.List<JSONObject> receipts=new java.util.ArrayList<>();for(int i=0;i<prior.length();i++)receipts.add(prior.getJSONObject(i));Set<String> lineages=MemoryArchive.lineageKeys(receipts);for(int i=0;i<records.length();i++)MemoryArchive.checkLineage(records.getJSONObject(i),lineages,0);
        if(!MemoryArchive.canonical(counts(value)).equals(MemoryArchive.canonical(value.getJSONObject("counts")))||!value.optString("sectionDigest").matches("[a-f0-9]{64}")||!value.getString("sectionDigest").equals(MemoryArchive.sectionDigest(value)))throw new SecurityException("项目档案计数/hash不匹配");return value;
    }
    static void checkRestoredRecord(JSONObject row)throws Exception{
        dataScope(row);if(!Boolean.TRUE.equals(row.opt("archiveOnly"))||row.optBoolean("core")||!"archive_restore".equals(row.optString("formation"))||row.getJSONArray("sourceRefs").length()!=0||row.getJSONArray("sourceIds").length()!=0)throw new SecurityException("项目恢复只放置只读数据，不恢复来源/core/权限");
        JSONObject from=row.getJSONObject("importedFrom"),evidence=row.getJSONObject("archiveEvidence");MemoryArchive.keys(from,Set.of("archiveImportId","originalId","sectionDigest","originalType"));MemoryArchive.keys(evidence,Set.of("originalType","original"));String type=from.getString("originalType");
        if(from.length()!=4||evidence.length()!=2||!Set.of("record","candidate").contains(type)||!from.optString("archiveImportId").matches("[A-Za-z0-9:_-]{1,160}")||!from.optString("originalId").matches("[a-f0-9-]{36}")||!from.optString("sectionDigest").matches("[a-f0-9]{64}")||!type.equals(evidence.optString("originalType")))throw new SecurityException("项目档案恢复沿革不完整");
        JSONObject original=evidence.getJSONObject("original");MemoryArchive.keys(original,"record".equals(type)?MemoryArchive.RECORD:MemoryArchive.CANDIDATE);MemoryBook.Scope origin=dataScope(original);MemoryArchive.id(original);if(original.has("text")||!from.getString("originalId").equals(original.getString("id")))throw new SecurityException("项目旧正文不能另存隐藏副本");MemoryArchive.refs(original.getJSONArray("sourceRefs"),origin);MemoryArchive.bounded(evidence);
    }
    static void checkImportMetadata(JSONObject value,int depth)throws Exception{
        if(depth>8)throw new SecurityException("项目档案沿革超过原有8层界限");MemoryArchive.keys(value,IMPORT);MemoryBook.Scope target=dataScope(value);MemoryBook.Scope origin=dataScope(new JSONObject().put("ownerId","owner").put("spaceId",value.getString("originalSpaceId")));
        if(value.length()!=IMPORT.size()||!IMPORT_FORMAT.equals(value.optString("format"))||!value.optString("archiveImportId").matches("[A-Za-z0-9:_-]{1,160}")||!value.optString("sectionDigest").matches("[a-f0-9]{64}")||!value.optString("archiveSha256").matches("[a-f0-9]{64}")||!value.optString("projectPhaseId").matches("[a-f0-9-]{36}"))throw new SecurityException("项目恢复回执不是原native操作/项目映射的只读事实");SourceRef.integer(value,"importedAt",0);
        JSONArray mappings=value.getJSONArray("mappings"),states=value.getJSONArray("sourceEvidence"),fences=value.getJSONArray("recordForgetEvidence"),metadata=value.getJSONArray("candidateMetadata"),history=value.getJSONArray("candidateHistoryEvidence"),prior=value.getJSONArray("priorImports"),namespace=value.getJSONArray("namespaceSourceEvidence");if(mappings.length()>1200||states.length()>10000||fences.length()>1000||metadata.length()>200||history.length()>1000||prior.length()>32||namespace.length()>10000)throw new SecurityException("项目恢复沿革超出原Book容量");Set<String> seen=new HashSet<>(),originals=new HashSet<>();int records=0,candidates=0;
        for(int i=0;i<mappings.length();i++){JSONObject m=mappings.getJSONObject(i);MemoryArchive.keys(m,Set.of("originalId","originalType","newId"));if(m.length()!=3||!m.optString("originalId").matches("[a-f0-9-]{36}")||!m.optString("newId").matches("[a-f0-9-]{36}")||!Set.of("record","candidate").contains(m.optString("originalType"))||!seen.add(m.getString("newId"))||!originals.add(m.getString("originalType")+":"+m.getString("originalId")))throw new SecurityException("项目原生新旧ID映射无效");if("record".equals(m.getString("originalType")))records++;else candidates++;}
        for(int i=0;i<prior.length();i++)checkImportMetadata(prior.getJSONObject(i),depth+1);Set<String> origins=origins(value,true);for(int i=0;i<states.length();i++)sourceState(states.getJSONObject(i),origins);for(int i=0;i<fences.length();i++)recordFence(fences.getJSONObject(i),origins);for(int i=0;i<namespace.length();i++)namespaceFence(namespace.getJSONObject(i),origins);for(int i=0;i<metadata.length();i++){JSONObject row=metadata.getJSONObject(i);MemoryArchive.candidate(row,origin);if(row.has("text"))throw new SecurityException("项目导入回执不另存候选正文");}for(int i=0;i<history.length();i++)MemoryArchive.history(history.getJSONObject(i),origin);
        JSONObject counts=value.getJSONObject("counts");Set<String> countKeys=Set.of("records","sources","sourceTombstones","recordTombstones","learningCandidates","pendingCandidates","candidateHistory","restoreRecordSlots","namespaceSourceTombstones");MemoryArchive.keys(counts,countKeys);if(counts.length()!=countKeys.size())throw new SecurityException("项目恢复计数缺失");for(String key:countKeys)SourceRef.integer(counts,key,0);int deleted=0;for(int i=0;i<states.length();i++)if(states.getJSONObject(i).getBoolean("deleted"))deleted++;
        if(counts.getInt("records")!=records||counts.getInt("learningCandidates")!=candidates+metadata.length()||counts.getInt("pendingCandidates")>candidates||counts.getInt("restoreRecordSlots")!=mappings.length()||counts.getInt("sources")!=states.length()||counts.getInt("sourceTombstones")!=deleted||counts.getInt("recordTombstones")!=fences.length()||counts.getInt("candidateHistory")!=history.length()||counts.getInt("namespaceSourceTombstones")!=namespace.length())throw new SecurityException("项目恢复计数不符");MemoryArchive.bounded(value);
    }
    /** Opaque placement only: original settled project map + THIS archive's native confirmation.
     * It never exposes a Scope, issues a read token or restores a live run consent. */
    public static final class Placement {
        final ProjectMemoryNamespaces issuer;final MemoryBook book;final OwnerDataArchives original;final OwnerDataArchives.Confirmed confirmed;final TaskStore.OwnerArchiveProjectMap map;
        final String archiveSha,sourceSpace,targetProject,targetRevision,sectionDigest,payload;final long expiresAt;
        Placement(ProjectMemoryNamespaces issuer,MemoryBook book,OwnerDataArchives original,OwnerDataArchives.Confirmed confirmed,TaskStore.OwnerArchiveProjectMap map,JSONObject archive,String target,String revision)throws Exception{this.issuer=issuer;this.book=book;this.original=original;this.confirmed=confirmed;this.map=map;payload=archive.toString();JSONObject meta=confirmed.metadata();expiresAt=meta.getLong("expiresAt");archiveSha=meta.getString("archiveSha256");sourceSpace=dataScope(archive).spaceId;targetProject=target;targetRevision=revision;sectionDigest=archive.getString("sectionDigest");}
        void requireBook(MemoryBook expected)throws Exception{if(book!=expected)throw new SecurityException("不是此原Book的项目只读放置");issuer.admitArchivePlacement(this);}
        public JSONObject metadata()throws Exception{issuer.requireArchivePlacement(this);return new JSONObject().put("archiveImportId",confirmed.actionId()).put("archiveSha256",archiveSha).put("sectionDigest",sectionDigest).put("originalSpaceId",sourceSpace).put("targetSpaceId","project:"+targetProject).put("projectPhaseId",map.originalPhaseId()).put("formalMemoryActivated",0).put("readGrantIssued",false).put("consentRestored",false).put("executionAuthorized",false);}
    }
}
