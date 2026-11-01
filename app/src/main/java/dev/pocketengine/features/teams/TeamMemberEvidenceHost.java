package dev.pocketengine.features.teams;

import dev.pocketengine.core.CapabilityPort;
import dev.pocketengine.features.EngineModule;
import dev.pocketengine.features.FeatureHost;
import dev.pocketengine.features.memory.ReadAudience;
import dev.pocketengine.features.memory.SourceRef;
import dev.pocketengine.features.memory.TaskMemoryUse;
import dev.pocketengine.store.TaskStore;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** The existing discussion's native material picker, not the owner's default private capability table. */
public final class TeamMemberEvidenceHost implements TeamCoordinator.EvidenceFactory {
    private final FeatureHost features;private final TaskStore tasks;private final TaskMemoryUse.SourceReader sources;
    private final Set<TeamMemberEvidencePort> live=java.util.concurrent.ConcurrentHashMap.newKeySet();
    public TeamMemberEvidenceHost(FeatureHost features,TaskStore tasks,TaskMemoryUse.SourceReader sources){this.features=java.util.Objects.requireNonNull(features);this.tasks=java.util.Objects.requireNonNull(tasks);this.sources=java.util.Objects.requireNonNull(sources);}
    static JSONObject ownerScope()throws Exception{return new JSONObject().put("ownerId","owner").put("spaceId","private");}
    @Override public CapabilityPort forMember(TeamCoordinator.MemberEvidenceCall call)throws Exception{call.checkCurrent();TeamMemberEvidencePort port=new TeamMemberEvidencePort(this,features,tasks,sources,call);live.add(port);return port;}
    void closed(TeamMemberEvidencePort port){live.remove(port);}
    private void changed(String run,String member){for(TeamMemberEvidencePort port:live)if(port.belongsTo(run,member))port.invalidateMaterials();}
    public Page open(TeamCoordinator coordinator,String run,String member)throws Exception{return new Page(coordinator,run,member);}
    public final class Page implements AutoCloseable {
        private final TeamCoordinator coordinator;private final String run,member;private final TaskStore.TeamMemberMaterialWindow window;private volatile boolean alive=true;
        private Selection selected;private TaskStore.TeamMemberMaterialReview pending;
        private Page(TeamCoordinator coordinator,String run,String member)throws Exception{this.coordinator=coordinator;this.run=run;this.member=member;window=tasks.openTeamMemberMaterialWindow(run);coordinator.memberScopePlan(run,member);}
        public void setForeground(boolean foreground){window.setForeground(alive&&foreground);}
        private void require()throws Exception{if(!alive)throw new SecurityException("原资料选择页已关闭");window.require();}
        public JSONObject current()throws Exception{require();return tasks.teamMemberMaterialScope(run,member);}
        public JSONObject catalogue()throws Exception{
            require();JSONObject value=new JSONObject().put("records",new JSONArray()).put("sources",new JSONArray()).put("skills",new JSONArray()).put("resources",new JSONArray());EngineModule memory=features.module("memory");
            if(memory!=null){JSONArray records=memory.handle("list",new JSONObject().put("scope",ownerScope())).getJSONArray("records"),kept=new JSONArray(),sourceRows=new JSONArray();java.util.Set<String> seen=new java.util.HashSet<>();for(int i=0;i<records.length();i++){JSONObject record=records.getJSONObject(i);if(!record.optBoolean("active"))continue;kept.put(record);JSONArray refs=record.optJSONArray("sourceRefs");for(int j=0;refs!=null&&j<refs.length();j++){JSONObject ref=refs.getJSONObject(j);String key=TeamWorkFiles.canonical(ref);if(seen.add(key))sourceRows.put(new JSONObject().put("sourceRef",ref).put("offset",0).put("chars",24000).put("unit","UTF-16"));}}value.put("records",kept).put("sources",sourceRows);}
            EngineModule skills=features.module("skills");if(skills instanceof dev.pocketengine.features.skills.SkillsModule)value.put("skills",((dev.pocketengine.features.skills.SkillsModule)skills).catalogueForHost(ownerScope()).getJSONArray("skills"));
            JSONArray resources=new JSONArray();EngineModule attachments=features.module("attachments");if(attachments!=null){JSONArray rows=attachments.handle("list",new JSONObject()).getJSONArray("attachments");for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);String mime=row.optString("mimeType");if(row.optLong("size")>65536||!mime.startsWith("text/"))continue;resources.put(new JSONObject().put("kind","attachment").put("id",row.getString("id")).put("name",row.optString("name")).put("sha256",row.getString("sha256")));}}
            EngineModule workspace=features.module("workspace");if(workspace!=null){JSONArray rows=workspace.handle("listArtifacts",new JSONObject()).getJSONArray("artifacts");for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);if(!row.optString("kind").equals("text_file"))continue;resources.put(new JSONObject().put("kind","workspace_artifact").put("id",row.getString("id")).put("name",row.optString("path")).put("sha256",row.getString("sha256")));}}
            require();return value.put("resources",resources).put("accountsAndCredentialsSelectable",false);
        }
        public synchronized TaskStore.TeamMemberMaterialReview review(JSONObject input)throws Exception{
            require();selected=capture(this,coordinator.memberScopePlan(run,member),input);pending=tasks.reviewTeamMemberMaterials(window,selected);return pending;
        }
        public synchronized JSONObject approve(TaskStore.TeamMemberMaterialReview review,String digest)throws Exception{require();if(review!=pending||selected==null)throw new SecurityException("请先审阅本页准确资料");selected.verifyMaterialsCurrent();JSONObject value=tasks.approveTeamMemberMaterials(review,digest);pending=null;changed(run,member);return value;}
        public JSONObject revoke(long revision)throws Exception{require();JSONObject value=tasks.revokeTeamMemberMaterials(window,member,revision);changed(run,member);return value;}
        @Override public void close(){alive=false;window.close();}
    }
    public static final class Selection {
        private final TeamMemberEvidenceHost issuer;private final Page page;private final TeamCoordinator.MemberScopePlan nativePlan;private final String value;
        private Selection(TeamMemberEvidenceHost issuer,Page page,TeamCoordinator.MemberScopePlan plan,JSONObject value){this.issuer=issuer;this.page=page;nativePlan=plan;this.value=value.toString();}
        public boolean belongsTo(TaskStore tasks,TaskStore.TeamMemberMaterialWindow window){return issuer.tasks==tasks&&page.window==window;}
        public TeamCoordinator.MemberScopePlan nativePlan(){return nativePlan;}
        public JSONObject view()throws Exception{return new JSONObject(value);}
        public void checkCurrent()throws Exception{page.require();nativePlan.checkCurrent();}
        private void verifyMaterialsCurrent()throws Exception{checkCurrent();issuer.checkMaterials(view(),ownerAudience());checkCurrent();}
        private ReadAudience ownerAudience()throws Exception{JSONObject selected=view();return new ReadAudience(){public String ownerId(){return "owner";}public String spaceId(){return "private";}public String audienceId(){return "native-owner-review";}public long revision(){return 1;}public JSONArray allowedRecords()throws Exception{return new JSONArray(selected.getJSONArray("allowedRecords").toString());}public JSONArray allowedSources()throws Exception{return new JSONArray(selected.getJSONArray("allowedSources").toString());}public void checkCurrent()throws Exception{Selection.this.checkCurrent();}};}
    }
    private Selection capture(Page page,TeamCoordinator.MemberScopePlan plan,JSONObject input)throws Exception{
        plan.checkCurrent();for(var keys=input.keys();keys.hasNext();)if(!Set.of("allowedRecords","allowedSources","skills","resources").contains(keys.next()))throw new SecurityException("分享选择不能携带工具授权/账号/密钥/登录状态");
        JSONArray records=ReadAudience.checkedRecords(input.getJSONArray("allowedRecords")),sourceRows=ReadAudience.checkedSources(input.getJSONArray("allowedSources"),"owner","private"),skills=new JSONArray(),resources=new JSONArray();
        JSONArray requested=input.getJSONArray("skills");if(requested.length()>16)throw new IllegalArgumentException("一次最多显选16个准确技能版本");java.util.Set<String> selectedIds=new java.util.HashSet<>();
        for(int i=0;i<requested.length();i++){JSONObject chosen=requested.getJSONObject(i);if(!Set.of("id","revision","digest","paths").equals(keys(chosen))||!selectedIds.add(chosen.getString("id")))throw new SecurityException("技能须显选唯一id/版本/digest与资源路径");JSONArray paths=chosen.getJSONArray("paths");if(paths.length()<1||paths.length()>16)throw new SecurityException("技能资源路径需1至16项");JSONObject copy=new JSONObject(chosen.toString());for(int n=0;n<paths.length();n++)dev.pocketengine.features.workspace.VersionedWorkspace.path(paths.getString(n));skills.put(copy);}
        requested=input.getJSONArray("resources");if(requested.length()>32)throw new IllegalArgumentException("一次最多分享32个准确资料范围");for(int i=0;i<requested.length();i++){JSONObject chosen=requested.getJSONObject(i);if(!Set.of("kind","id","sha256","offset","chars").equals(keys(chosen)))throw new SecurityException("资料只接原冻结文件/附件的准确标识与范围");JSONObject actual=resource(chosen);String text=actual.getString("text");int offset=integer(chosen,"offset",0,Integer.MAX_VALUE),chars=integer(chosen,"chars",1,24000);int end=end(text,offset,chars);resources.put(new JSONObject(chosen.toString()).put("selectionId",java.util.UUID.randomUUID().toString()).put("chars",end-offset).put("textHash",TeamWorkFiles.hash(text)).put("totalChars",text.length()));}
        JSONObject value=plan.view().put("allowedRecords",records).put("allowedSources",sourceRows).put("skills",skills).put("resources",resources);page.require();plan.checkCurrent();Selection selected=new Selection(this,page,plan,value);selected.verifyMaterialsCurrent();return selected;
    }
    void checkMaterials(JSONObject selected,ReadAudience audience)throws Exception{
        if(audience!=null)audience.checkCurrent();EngineModule memory=features.module("memory");JSONArray records=selected.getJSONArray("allowedRecords");if(records.length()>0&&memory==null)throw new SecurityException("原记忆模块不可用");for(int i=0;i<records.length();i++){JSONObject expected=records.getJSONObject(i),actual=memory.handle("get",new JSONObject().put("scope",ownerScope()).put("id",expected.getString("id"))).getJSONObject("record");if(actual.getLong("revision")!=expected.getLong("revision")||!actual.optBoolean("active"))throw new SecurityException("所选记忆已换版/撤回/失效，不替换为当前全文");}
        JSONArray skillRows=selected.getJSONArray("skills");EngineModule skills=features.module("skills");for(int i=0;i<skillRows.length();i++){JSONObject expected=skillRows.getJSONObject(i);if(!(skills instanceof dev.pocketengine.features.skills.SkillsModule))throw new SecurityException("原技能模块不可用");JSONObject actual=skills.handle("get",new JSONObject().put("scope",ownerScope()).put("id",expected.getString("id"))).getJSONObject("skill");if(actual.optInt("revision")!=expected.getInt("revision")||!actual.optString("digest").equals(expected.getString("digest"))||!actual.optBoolean("enabled"))throw new SecurityException("已显选技能换版/停用，不继承旧选择");}
        JSONArray refs=selected.getJSONArray("resources");for(int i=0;i<refs.length();i++){JSONObject expected=refs.getJSONObject(i),actual=resource(expected);if(!expected.getString("textHash").equals(TeamWorkFiles.hash(actual.getString("text"))))throw new SecurityException("所选资料内容身份改变");}
        // Source privacy/current-version and exact span rules belong only to C4's original SourceReader adapter.
        if(audience!=null&&memory instanceof dev.pocketengine.features.memory.MemoryModule){TaskMemoryUse reader=((dev.pocketengine.features.memory.MemoryModule)memory).taskMemoryUse(null,sources);JSONArray rows=audience.allowedSources();for(int i=0;i<rows.length();i++){JSONObject span=rows.getJSONObject(i);JSONObject read=reader.readAudienceSource(ownerScope(),SourceRef.fromJson(span.getJSONObject("sourceRef")),span.getInt("offset"),span.getInt("chars"),audience);if(!read.optString("status").equals("current"))throw new SecurityException("所选准确来源已不可用");}}
        if(audience!=null)audience.checkCurrent();
    }
    JSONObject resource(JSONObject selected)throws Exception{
        String kind=selected.getString("kind"),id=selected.getString("id"),sha=selected.getString("sha256");JSONObject value;
        if(kind.equals("attachment")){EngineModule module=features.module("attachments");if(module==null)throw new SecurityException("原附件模块不可用");value=module.handle("readText",new JSONObject().put("id",id));if(!sha.equals(value.getString("sha256")))throw new SecurityException("原附件版本已变化");return new JSONObject().put("text",value.getString("text"));}
        if(kind.equals("workspace_artifact")){EngineModule module=features.module("workspace");if(module==null)throw new SecurityException("原文件成果模块不可用");value=module.handle("readArtifact",new JSONObject().put("artifactId",id));if(!sha.equals(value.getString("sha256"))||!value.optString("kind").equals("text_file"))throw new SecurityException("原冻结文字成果身份已变化");return new JSONObject().put("text",value.getString("content"));}
        throw new SecurityException("此分享不能选择私有账号、密钥、登录状态、远端会话或任意文件路径");
    }
    static Set<String> keys(JSONObject value){java.util.HashSet<String> keys=new java.util.HashSet<>();value.keys().forEachRemaining(keys::add);return keys;}
    static int integer(JSONObject value,String key,int min,int max)throws Exception{Object raw=value.get(key);if(!(raw instanceof Integer||raw instanceof Long)||((Number)raw).longValue()<min||((Number)raw).longValue()>max)throw new IllegalArgumentException("准确整数范围无效："+key);return ((Number)raw).intValue();}
    static int end(String text,int offset,int chars){if(offset<0||offset>text.length()||chars<1)throw new IllegalArgumentException("原文范围越界");int end=(int)Math.min(text.length(),(long)offset+chars);if(offset>0&&offset<text.length()&&Character.isLowSurrogate(text.charAt(offset))&&Character.isHighSurrogate(text.charAt(offset-1)))throw new IllegalArgumentException("原文起点不能拆Unicode字符");if(end>offset&&end<text.length()&&Character.isHighSurrogate(text.charAt(end-1))&&Character.isLowSurrogate(text.charAt(end)))end--;if(end==offset&&offset<text.length())throw new IllegalArgumentException("所选范围不足容纳一个完整Unicode字符");return end;}
}
