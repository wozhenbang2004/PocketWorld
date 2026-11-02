package dev.pocketengine.features.workspace;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.memory.*;
import dev.pocketengine.features.assistant.ImportedExperienceEvidence;
import dev.pocketengine.store.TaskStore;
import dev.pocketengine.store.OwnerExperienceProjection;
import java.util.*;
import org.json.*;

/** Portable read-only pages of original experiences. The original owner archive ZIP owns the
 * bytes; ConversationRepository/TaskStore/MemoryBook retain all live identity and source authority. */
public final class OwnerExperienceArchive implements OwnerDataArchives.ObjectSectionBackend {
    public static final String FORMAT="pocketworld.owner-experiences.v1",PAGE="pocketworld.owner-experience-page.v1";
    private final TaskStore tasks;private final MemoryModule memory;private final OwnerExperienceProjection.Gate gate;
    private static JSONObject scope()throws Exception{return new JSONObject().put("ownerId","owner").put("spaceId","private");}
    public OwnerExperienceArchive(TaskStore original,MemoryModule book){
        tasks=Objects.requireNonNull(original);memory=Objects.requireNonNull(book);
        gate=new OwnerExperienceProjection.Gate(){public boolean blocked(SourceRef source)throws Exception{return memory.isSourceForgotten(scope(),source)||"message".equals(source.kind)&&tasks.conversationRepository().ownerExperienceBlocked(source);}public ToolOutputStore.Sources outputSources(){return tasks.toolOutputSources();}public boolean dependencyKnownBlocked(JSONObject dependency)throws Exception{return tasks.ownerExperienceKnownAttachmentBlocked(dependency);}};
    }
    public JSONObject export(RunControl original){throw new IllegalStateException("原经历必须经过原备份的分页对象写入器");}
    public OwnerDataArchives.SectionReview review(JSONObject exact,String action,RunControl original){throw new IllegalStateException("原经历审阅必须保留已核ZIP对象读取器");}
    public JSONObject query(JSONObject exact,String action,RunControl original){throw new IllegalStateException("原经历查询必须保留已核ZIP对象读取器");}
    private interface Recheck {JSONObject read()throws Exception;}
    public JSONObject exportObjects(OwnerDataArchives.ObjectWriter writer,RunControl original)throws Exception {
        long started=System.currentTimeMillis();JSONObject barriers=memory.ownerExperienceBarriers(scope());String fence=MemoryArchive.sectionDigest(barriers);JSONArray pages=new JSONArray(),gaps=new JSONArray();List<Recheck> checks=new ArrayList<>();List<String> hashes=new ArrayList<>();int[] counts={0,0,0,0};
        if(tasks.conversationRepository().active()){
            String cursor="";do{String selected=cursor;JSONObject actual=tasks.conversationRepository().ownerExperiencePage(selected,gate,original);add(writer,pages,"messages","",actual,counts,gaps,checks,hashes,()->tasks.conversationRepository().ownerExperiencePage(selected,gate,original));cursor=actual.getString("nextCursor");}while(!cursor.isEmpty());
        }else gaps.put(new JSONObject().put("kind","messages").put("reason","legacy_history_not_activated_or_originals_missing").put("originalsRecovered",false));
        String taskCursor="";Set<String> seenTasks=new HashSet<>();
        do{
            String selected=taskCursor;JSONObject page=tasks.ownerExperienceTasksForOwner(gate,selected,original);add(writer,pages,"task_headers","",page,counts,gaps,checks,hashes,()->tasks.ownerExperienceTasksForOwner(gate,selected,original));
            JSONArray rows=page.getJSONArray("entries");for(int i=0;i<rows.length();i++){
                JSONObject task=rows.getJSONObject(i);String id=task.getString("taskId");if(!seenTasks.add(id)||!task.optBoolean("readable"))continue;
                for(String section:List.of("events","operations","outputs")){
                    String cursor="";do{String pin=cursor;JSONObject actual=section.equals("outputs")?tasks.ownerExperienceOutputsForOwner(gate,id,pin,original):tasks.ownerExperienceAuditForOwner(gate,id,section,pin,original);
                        add(writer,pages,section,id,actual,counts,gaps,checks,hashes,()->section.equals("outputs")?tasks.ownerExperienceOutputsForOwner(gate,id,pin,original):tasks.ownerExperienceAuditForOwner(gate,id,section,pin,original));cursor=actual.getString("nextCursor");}while(!cursor.isEmpty());
                }
            }
            taskCursor=page.getString("nextCursor");
        }while(!taskCursor.isEmpty());
        // A completed ZIP is published only if every actually captured page still matches and no
        // forget/delete barrier changed. This is a checked capture interval, not a DB transaction.
        Map<String,OutputProof> outputs=new LinkedHashMap<>();int nativeMessages=0,importedMessages=0;
        for(int i=0;i<checks.size();i++){original.checkpoint();JSONObject actual=checks.get(i).read();if(!hashes.get(i).equals(MemoryArchive.sectionDigest(actual)))throw new SecurityException("原经历在归档期间变化，未发布混版或已遗忘正文");if("messages".equals(pages.getJSONObject(i).getString("kind"))){JSONArray messages=actual.getJSONArray("entries");for(int m=0;m<messages.length();m++)if("imported_message".equals(messages.getJSONObject(m).optString("kind")))importedMessages++;else nativeMessages++;}if("outputs".equals(pages.getJSONObject(i).getString("kind"))){JSONArray entries=actual.getJSONArray("entries");for(int j=0;j<entries.length();j++){JSONObject row=entries.getJSONObject(j);String id=row.getString("taskId")+":"+row.getString("outputId");OutputProof proof=outputs.get(id);if(proof==null){proof=new OutputProof(row);outputs.put(id,proof);}proof.add(row);}}}
        for(OutputProof proof:outputs.values())proof.finish();
        if(!fence.equals(MemoryArchive.sectionDigest(memory.ownerExperienceBarriers(scope()))))throw new SecurityException("遗忘/来源屏障在归档期间变化，未发布旧经历");
        JSONArray importedDeletions=new JSONArray();for(int i=0;i<gaps.length();i++){JSONObject detail=gaps.getJSONObject(i).optJSONObject("detail");if(detail!=null&&"imported_record_deleted".equals(detail.optString("reason")))importedDeletions.put(ImportedExperienceEvidence.checkedLocator(detail.getJSONObject("importedLocator")));}
        JSONObject result=new JSONObject().put("format",FORMAT).put("version",1).put("ownerId","owner").put("spaceId","private").put("captureStartedAt",started).put("snapshotAt",System.currentTimeMillis()).put("pages",pages).put("barriers",barriers)
            .put("counts",new JSONObject().put("pages",pages.length()).put("messages",counts[0]).put("taskHeaders",counts[1]).put("auditEntries",counts[2]).put("outputChunks",counts[3]).put("gaps",gaps.length()))
            .put("gaps",gaps).put("archiveOnly",true).put("activeAuthorityIncluded",false).put("executionAuthorized",false)
            .put("coverage",new JSONObject().put("originalMessageBodies",true).put("nativeMessageCount",nativeMessages).put("importedMessageCount",importedMessages).put("importedOriginalFragmentsIncluded",true).put("importAssignedRecordsPromotedToSourceRefs",false).put("legacyFullSourceDocumentBytesIncluded",false).put("importedDeletionBarriers",importedDeletions).put("originalTaskAudit",true).put("retainedSafeNativeOutputChunks",true).put("completeProviderWire",false).put("providerHiddenReasoningIncluded",false).put("credentialStoresIncluded",false).put("nativeProtectedFieldsExcluded",true).put("attachmentBytes","separate_explicit_artifacts_section_by_exact_hash").put("unselectedOrUnavailableOriginalsRecovered",false).put("laterDeletionStateKnown",false).put("consistency","per_page_rechecked_capture_interval_not_cross_module_transaction"));
        result.put("sectionDigest",MemoryArchive.sectionDigest(result));return checked(result);
    }
    private static void add(OwnerDataArchives.ObjectWriter writer,JSONArray pages,String kind,String task,JSONObject data,int[] counts,JSONArray allGaps,List<Recheck> checks,List<String> hashes,Recheck current)throws Exception {
        if(pages.length()>=10000)throw new IllegalStateException("原经历超过本包10000页上限，未生成截断备份");JSONArray entries=data.getJSONArray("entries");
        JSONObject object=new JSONObject().put("format",PAGE).put("kind",kind).put("taskId",task).put("data",data).put("archiveOnly",true).put("executionAuthorized",false);
        JSONArray protectedFields=data.optJSONArray("archiveOmittedFields");if(protectedFields!=null)allGaps.put(new JSONObject().put("kind",kind).put("taskId",task).put("reason","protected_fields_excluded").put("fields",protectedFields));
        JSONArray missing=data.optJSONArray("gaps");if(missing!=null)for(int i=0;i<missing.length();i++)allGaps.put(new JSONObject().put("kind",kind).put("taskId",task).put("detail",missing.getJSONObject(i)));
        for(int i=0;i<entries.length();i++){JSONObject row=entries.getJSONObject(i);if("read_limited".equals(row.optString("status")))allGaps.put(new JSONObject().put("kind",kind).put("taskId",task).put("reason",row.optString("reason")).put("position",row.opt("position")));}
        if(allGaps.length()>10000)throw new IllegalStateException("原经历缺口超过10000条，未截断覆盖manifest");
        String hash=writer.save(object);pages.put(new JSONObject().put("kind",kind).put("taskId",task).put("object",hash).put("entries",entries.length()).put("pageDigest",MemoryArchive.sectionDigest(data)));
        counts[kind.equals("messages")?0:kind.equals("task_headers")?1:kind.equals("outputs")?3:2]+=entries.length();checks.add(current);hashes.add(MemoryArchive.sectionDigest(data));
    }
    public static JSONObject checked(JSONObject input)throws Exception {
        JSONObject value=new JSONObject(input.toString());Set<String> keys=new HashSet<>();value.keys().forEachRemaining(keys::add);if(!keys.equals(Set.of("format","version","ownerId","spaceId","captureStartedAt","snapshotAt","pages","barriers","counts","gaps","archiveOnly","activeAuthorityIncluded","executionAuthorized","coverage","sectionDigest")))throw new SecurityException("原经历档案字段不完整或含替代授权字段");if(value.length()!=15||!FORMAT.equals(value.optString("format"))||value.optInt("version")!=1||!"owner".equals(value.optString("ownerId"))||!"private".equals(value.optString("spaceId"))||!Boolean.TRUE.equals(value.opt("archiveOnly"))||!Boolean.FALSE.equals(value.opt("executionAuthorized"))||!Boolean.FALSE.equals(value.opt("activeAuthorityIncluded")))throw new SecurityException("原经历档案格式或只读权限边界无效");
        long started=integer(value,"captureStartedAt"),at=integer(value,"snapshotAt");if(at<started)throw new SecurityException("原经历快照时间倒退");JSONArray pages=value.getJSONArray("pages");if(pages.length()>10000)throw new SecurityException("原经历页数超限");
        Set<String> uniquePages=new HashSet<>();for(int i=0;i<pages.length();i++){JSONObject page=pages.getJSONObject(i);if(!uniquePages.add(page.optString("object")))throw new SecurityException("原经历页重复，未重复计算同一证据");if(page.length()!=5||!Set.of("messages","task_headers","events","operations","outputs").contains(page.optString("kind"))||!page.optString("object").matches("[a-f0-9]{64}")||!page.optString("pageDigest").matches("[a-f0-9]{64}")||integer(page,"entries")>100)throw new SecurityException("原经历页定位、hash或范围无效");}
        JSONObject barriers=value.getJSONObject("barriers");if(barriers.length()!=3||!barriers.has("sourceBarriers")||!barriers.has("recordBarriers")||!barriers.has("assertionFences"))throw new SecurityException("原经历遗忘屏障缺失");JSONArray records=barriers.getJSONArray("recordBarriers"),assertions=barriers.getJSONArray("assertionFences");if(records.length()>1000||assertions.length()>10000)throw new SecurityException("原经历屏障超限");for(int i=0;i<records.length();i++){JSONObject row=records.getJSONObject(i);if(row.length()!=4||!"owner".equals(row.optString("ownerId"))||!"private".equals(row.optString("spaceId"))||!row.optString("recordId").matches("[0-9a-f-]{36}")||integer(row,"forgottenThrough")<1)throw new SecurityException("原记忆删除屏障无效");}for(int i=0;i<assertions.length();i++){JSONObject row=assertions.getJSONObject(i);MemoryAssertion.checkedFence(row);if(!"owner".equals(row.optString("ownerId"))||!"private".equals(row.optString("spaceId")))throw new SecurityException("事实当前性屏障跨范围");}JSONArray sources=barriers.getJSONArray("sourceBarriers");if(sources.length()>10000)throw new SecurityException("原经历来源屏障超限");for(int i=0;i<sources.length();i++){JSONObject row=sources.getJSONObject(i);SourceRef ref=SourceRef.fromJson(row);if(!"owner".equals(ref.ownerId)||!"private".equals(ref.spaceId)||!(row.opt("deleted") instanceof Boolean)||integer(row,"forgottenThrough")>ref.revision)throw new SecurityException("原经历来源屏障无效");}
        JSONObject coverage=value.getJSONObject("coverage");if(coverage.has("importedDeletionBarriers")&&!(coverage.opt("importedDeletionBarriers") instanceof JSONArray))throw new SecurityException("旧导入删除屏障类型无效，未忽略屏障");JSONArray importedDeletions=coverage.optJSONArray("importedDeletionBarriers");if(importedDeletions!=null){if(importedDeletions.length()>10000)throw new SecurityException("旧导入删除屏障过多");for(int i=0;i<importedDeletions.length();i++)ImportedExperienceEvidence.checkedLocator(importedDeletions.getJSONObject(i));}
        if(!value.getString("sectionDigest").equals(MemoryArchive.sectionDigest(value)))throw new SecurityException("原经历分区hash不匹配");return value;
    }
    public OwnerDataArchives.SectionReview reviewObjects(JSONObject input,String action,OwnerDataArchives.ObjectReader reader,RunControl original)throws Exception {
        JSONObject section=checked(input);validatePages(section,reader,original);
        return new OwnerDataArchives.SectionReview(){public JSONObject metadata()throws Exception{return result(section).put("counts",section.getJSONObject("counts")).put("readOnlyPagesRetainedInOriginalArchive",true);}public JSONObject restore()throws Exception{validatePages(section,reader,original);return result(section);}};
    }
    public JSONObject queryObjects(JSONObject input,String action,OwnerDataArchives.ObjectReader reader,RunControl original)throws Exception {JSONObject section=checked(input);validatePages(section,reader,original);return result(section);}
    private static JSONObject result(JSONObject section)throws Exception{return new JSONObject().put("found",true).put("status","completed").put("archiveOnly",true).put("snapshotAt",section.getLong("snapshotAt")).put("laterDeletionStateKnown",false).put("restoredTasks",0).put("restoredSourcePermissions",0).put("restoredActiveGrants",false).put("formalMemoryActivated",0).put("executionAuthorized",false);}
    private void validatePages(JSONObject section,OwnerDataArchives.ObjectReader reader,RunControl original)throws Exception {
        JSONArray pages=section.getJSONArray("pages");Map<String,OutputProof> outputs=new LinkedHashMap<>();int[] counts={0,0,0,0};int importedMessages=0,nativeMessages=0;
        for(int i=0;i<pages.length();i++){original.checkpoint();JSONObject page=page(section,i,reader,original);String kind=page.getString("kind");JSONArray entries=page.getJSONObject("data").getJSONArray("entries");counts[kind.equals("messages")?0:kind.equals("task_headers")?1:kind.equals("outputs")?3:2]+=entries.length();
            if(kind.equals("outputs"))for(int j=0;j<entries.length();j++){JSONObject row=entries.getJSONObject(j);String id=row.getString("taskId")+":"+row.getString("outputId");OutputProof proof=outputs.get(id);if(proof==null){proof=new OutputProof(row);outputs.put(id,proof);}proof.add(row);}
            if(kind.equals("messages"))for(int j=0;j<entries.length();j++){JSONObject row=entries.getJSONObject(j);if("imported_message".equals(row.optString("kind"))){ImportedExperienceEvidence.checkedRow(row);importedMessages++;continue;}nativeMessages++;SourceRef source=SourceRef.fromJson(row.getJSONObject("sourceRef"));if(!"message".equals(source.kind)||!"owner".equals(source.ownerId)||!"private".equals(source.spaceId)||!Set.of("user","assistant").contains(row.optString("role")))throw new SecurityException("档案原消息角色或范围无效");String text=row.getString("text");byte[] bytes=text.getBytes(java.nio.charset.StandardCharsets.UTF_8);if(integer(row,"bytes")!=bytes.length||!dev.pocketengine.features.local.project.ProjectSources.sha256(bytes).equals(row.getString("sha256")))throw new SecurityException("档案消息原文hash损坏");}
        }
        for(OutputProof proof:outputs.values())proof.finish();JSONObject coverage=section.getJSONObject("coverage");if(coverage.has("importedMessageCount")&&coverage.getInt("importedMessageCount")!=importedMessages||coverage.has("nativeMessageCount")&&coverage.getInt("nativeMessageCount")!=nativeMessages||importedMessages>0&&(!Boolean.TRUE.equals(coverage.opt("importedOriginalFragmentsIncluded"))||!Boolean.FALSE.equals(coverage.opt("importAssignedRecordsPromotedToSourceRefs"))))throw new SecurityException("旧导入/原生消息覆盖清单与实际页面不符");JSONObject expected=section.getJSONObject("counts");if(expected.getInt("pages")!=pages.length()||expected.getInt("messages")!=counts[0]||expected.getInt("taskHeaders")!=counts[1]||expected.getInt("auditEntries")!=counts[2]||expected.getInt("outputChunks")!=counts[3]||expected.getInt("gaps")!=section.getJSONArray("gaps").length())throw new SecurityException("原经历覆盖计数不完整");
    }
    private static final class OutputProof {
        final java.security.MessageDigest digest;final long totalBytes;final int totalChars;final String fingerprint;int next,chars;long bytes;
        OutputProof(JSONObject row)throws Exception{digest=java.security.MessageDigest.getInstance("SHA-256");totalBytes=integer(row,"outputBytes");totalChars=Math.toIntExact(integer(row,"outputChars"));fingerprint=row.getString("outputFingerprint");if(totalBytes>ToolOutputStore.MAX_RUN_BYTES||totalChars>8*1024*1024||!fingerprint.matches("sha256:[a-f0-9]{64}"))throw new SecurityException("原工具输出总范围无效");}
        void add(JSONObject row)throws Exception{String text=row.getString("text");byte[] actual=text.getBytes(java.nio.charset.StandardCharsets.UTF_8);if(row.getInt("chunkIndex")!=next++||row.getInt("startChar")!=chars||row.getInt("chars")!=text.length()||!row.getString("sha256").equals(dev.pocketengine.features.local.project.ProjectSources.sha256(actual))||row.getLong("outputBytes")!=totalBytes||row.getInt("outputChars")!=totalChars||!fingerprint.equals(row.getString("outputFingerprint")))throw new SecurityException("原工具输出chunk缺失、重复或hash损坏");chars+=text.length();bytes+=actual.length;digest.update(actual);}
        void finish()throws Exception{StringBuilder hash=new StringBuilder("sha256:");for(byte b:digest.digest())hash.append(String.format(java.util.Locale.ROOT,"%02x",b&255));if(chars!=totalChars||bytes!=totalBytes||!hash.toString().equals(fingerprint))throw new SecurityException("原工具输出缺少末尾chunk或整件hash损坏");}
    }
    private static JSONObject page(JSONObject section,int index,OwnerDataArchives.ObjectReader reader,RunControl original)throws Exception {
        JSONArray pages=section.getJSONArray("pages");if(index<0||index>=pages.length())throw new IllegalArgumentException("原经历页不存在");JSONObject expected=pages.getJSONObject(index),page=reader.read(expected.getString("object"));original.checkpoint();
        if(page.length()!=6||!PAGE.equals(page.optString("format"))||!expected.getString("kind").equals(page.optString("kind"))||!expected.getString("taskId").equals(page.optString("taskId"))||!Boolean.TRUE.equals(page.opt("archiveOnly"))||!Boolean.FALSE.equals(page.opt("executionAuthorized"))||!expected.getString("pageDigest").equals(MemoryArchive.sectionDigest(page.getJSONObject("data")))||page.getJSONObject("data").getJSONArray("entries").length()!=expected.getInt("entries"))throw new SecurityException("原经历页不完整、换版或hash不匹配");return page;
    }
    public JSONObject readPage(JSONObject input,int index,OwnerDataArchives.ObjectReader reader,RunControl original)throws Exception {
        JSONObject section=checked(input);validatePages(section,reader,original);JSONObject page=page(section,index,reader,original),data=page.getJSONObject("data");JSONArray entries=data.getJSONArray("entries"),out=new JSONArray();
        for(int i=0;i<entries.length();i++){
            original.checkpoint();JSONObject row=entries.getJSONObject(i);boolean blocked=false;
            if("imported_message".equals(row.optString("kind"))){JSONObject locator=row.getJSONObject("importedLocator");blocked=packageImportedBlocked(section,locator)||tasks.conversationRepository().ownerImportedExperienceBlocked(locator);}
            if(row.has("sourceRef")){SourceRef source=SourceRef.fromJson(row.getJSONObject("sourceRef"));blocked=packageBlocked(section,source)||gate.blocked(source);}
            if(row.has("dependencies")){JSONArray dependencies=row.getJSONArray("dependencies");for(int d=0;d<dependencies.length();d++)if(gate.dependencyKnownBlocked(dependencies.getJSONObject(d)))blocked=true;}
            if(row.has("dependencies"))try{ToolOutputStore.requireDependencies(row.getJSONArray("dependencies"),gate.outputSources());}catch(Exception unavailable){
                // On a new instance these original attachment generations are absent, not proof
                // of revocation. The bytes stay archive-only; known local invalidation still wins.
                blocked|=tasks.ownerExperienceHasTaskForOwner(row.optString("taskId"));
            }
            String task=page.optString("taskId");if(!task.isEmpty()&&tasks.ownerExperienceHasTaskForOwner(task))try{tasks.ownerExperienceAuditForOwner(gate,task,"events","",original);}catch(Exception unavailable){blocked=true;}
            if(blocked)out.put(new JSONObject().put("status","blocked_by_known_source_barrier").put("sourceRef",row.optJSONObject("sourceRef")).put("importedLocator",row.optJSONObject("importedLocator")).put("originalBodyIncluded",false));
            else out.put(new JSONObject(row.toString()).put("archiveOnly",true).put("executionAuthorized",false));
        }
        data.put("entries",out);return result(section).put("pageIndex",index).put("pageCount",section.getJSONArray("pages").length()).put("kind",page.getString("kind")).put("taskId",page.getString("taskId")).put("data",data).put("hasMore",index+1<section.getJSONArray("pages").length());
    }
    private static boolean packageImportedBlocked(JSONObject section,JSONObject wanted)throws Exception {
        JSONArray barriers=section.getJSONObject("coverage").optJSONArray("importedDeletionBarriers");for(int i=0;barriers!=null&&i<barriers.length();i++){JSONObject barrier=barriers.getJSONObject(i);if(ImportedExperienceEvidence.sameOriginal(barrier,wanted)&&barrier.getLong("recordVersion")>=wanted.getLong("recordVersion"))return true;}return false;
    }
    private static boolean packageBlocked(JSONObject section,SourceRef wanted)throws Exception {JSONArray barriers=section.getJSONObject("barriers").getJSONArray("sourceBarriers");for(int i=0;i<barriers.length();i++){JSONObject row=barriers.getJSONObject(i);SourceRef ref=SourceRef.fromJson(row);if(ref.kind.equals(wanted.kind)&&ref.sourceId.equals(wanted.sourceId)&&ref.ownerId.equals(wanted.ownerId)&&ref.spaceId.equals(wanted.spaceId)&&(row.getBoolean("deleted")||integer(row,"forgottenThrough")>=wanted.revision))return true;}return false;}
    private static long integer(JSONObject value,String key){Object raw=value.opt(key);if(!(raw instanceof Integer||raw instanceof Long)||((Number)raw).longValue()<0)throw new SecurityException("原经历数字字段无效："+key);return ((Number)raw).longValue();}
}
