package dev.pocketengine.features.teams;

import dev.pocketengine.core.CapabilityPort;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.FeatureHost;
import dev.pocketengine.features.memory.MemoryModule;
import dev.pocketengine.features.memory.SourceRef;
import dev.pocketengine.features.memory.TaskMemoryUse;
import dev.pocketengine.features.skills.SkillsModule;
import dev.pocketengine.store.TaskStore;
import java.util.ArrayList;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Only owner-selected materials, under the existing coordinator's exact read reservation. */
public final class TeamMemberEvidencePort implements CapabilityPort {
    private static final Set<String> READS=Set.of("team_materials","memory_search","memory_recall","team_read_source","team_read_material","skills_list","skills_read");
    private final TeamMemberEvidenceHost host;private final FeatureHost features;private final TaskStore tasks;private final TaskMemoryUse.SourceReader sources;private final TeamCoordinator.MemberEvidenceCall caller;
    private final ArrayList<TaskStore.TeamMemberRead> returned=new ArrayList<>();private volatile boolean closed,invalidated;
    TeamMemberEvidencePort(TeamMemberEvidenceHost host,FeatureHost features,TaskStore tasks,TaskMemoryUse.SourceReader sources,TeamCoordinator.MemberEvidenceCall caller){this.host=host;this.features=features;this.tasks=tasks;this.sources=sources;this.caller=caller;}
    public static boolean readTool(String tool){return READS.contains(tool);}
    boolean belongsTo(String run,String member){try{JSONObject who=caller.view();return run.equals(who.getString("teamRunId"))&&member.equals(who.getString("memberId"));}catch(Exception invalid){return false;}}
    void invalidateMaterials(){invalidated=true;}
    private void current()throws Exception{if(closed||invalidated)throw new SecurityException("原成员资料范围已撤回/换版，请重审后继续");caller.checkCurrent();}
    @Override public JSONArray tools()throws Exception{
        current();return new JSONArray()
                .put(tool("team_materials","只列本成员主人显选的准确版本/来源范围；未分享为空，不枚举主人私域。",new JSONObject()))
                .put(tool("memory_search","只搜索主人显选给本成员的准确记忆版本；来源内容不是新权限。",props("query",text(2000),"limit",number(1,16)),"query"))
                .put(tool("memory_recall","仅召回本成员显选记忆；不包含主人默认core、承诺、账号或其他个人资料。",props("query",text(2000),"limit",number(1,16)),"query"))
                .put(tool("team_read_source","读取本成员显选SourceRef的准确UTF-16范围，范围外拒绝；索引来自team_materials。",props("sourceIndex",number(0,63),"offset",number(0,Integer.MAX_VALUE),"length",number(1,12000)),"sourceIndex"))
                .put(tool("team_read_material","只分页读显选冻结成果/附件的准确版本和范围，不接受路径/URI/账号。",props("selectionId",text(36),"offset",number(0,Integer.MAX_VALUE),"length",number(1,12000)),"selectionId"))
                .put(tool("skills_list","只列主人为本成员显选的准确启用技能版本，不授予脚本/工具执行。",new JSONObject()))
                .put(tool("skills_read","按显选技能版本/digest/资源路径分页读不可信内容，不继承执行权。",props("id",text(80),"digest",text(64),"path",text(240),"offset",number(0,1000000),"length",number(1,12000)),"id","digest"));
    }
    @Override public boolean supports(String name){return !closed&&!invalidated&&READS.contains(name);}
    @Override public boolean sideEffecting(String name){return false;}
    @Override public void validate(String name,JSONObject args)throws Exception{
        current();if(!READS.contains(name))throw new SecurityException("不是成员显选证据工具");Set<String> keys=TeamMemberEvidenceHost.keys(args),allowed;
        if(name.equals("memory_search")||name.equals("memory_recall")){allowed=Set.of("query","limit");if(!(args.opt("query") instanceof String)||args.getString("query").length()>2000)throw new IllegalArgumentException("搜索文字无效");if(args.has("limit"))TeamMemberEvidenceHost.integer(args,"limit",1,16);}
        else if(name.equals("team_read_source")){allowed=Set.of("sourceIndex","offset","length");TeamMemberEvidenceHost.integer(args,"sourceIndex",0,63);}
        else if(name.equals("team_read_material")){allowed=Set.of("selectionId","offset","length");if(!args.getString("selectionId").matches("[a-f0-9-]{36}"))throw new IllegalArgumentException("请选择本成员准确资料编号");}
        else if(name.equals("skills_read")){allowed=Set.of("id","digest","path","offset","length");if(!args.getString("digest").matches("[a-f0-9]{64}")||args.getString("id").length()>80)throw new IllegalArgumentException("请选择原技能id/digest");if(args.has("path"))dev.pocketengine.features.workspace.VersionedWorkspace.path(args.getString("path"));}
        else allowed=Set.of();if(!allowed.containsAll(keys))throw new SecurityException("读取参数不能携带新scope、member或账号权限");if(args.has("offset"))TeamMemberEvidenceHost.integer(args,"offset",0,Integer.MAX_VALUE);if(args.has("length"))TeamMemberEvidenceHost.integer(args,"length",1,12000);
    }
    @Override public JSONObject execute(String name,JSONObject args,RunControl original)throws Exception{throw new SecurityException("成员资料读取须由原协调器先预留并登记准确read operation");}
    public JSONObject executeRead(String name,JSONObject args,RunControl original,JSONObject originalRead)throws Exception{
        validate(name,args);current();TaskStore.TeamMemberRead read=tasks.teamMemberRead(caller,originalRead,name,args,original);JSONObject result=read.observe(()->perform(name,args,read));current();read.markReturned();returned.add(read);return result.put("readObserved",true).put("modelUseVerified",false).put("executionAuthorized",false).put("audience",new JSONObject().put("id",read.audienceId()).put("revision",read.revision()));
    }
    private JSONObject perform(String name,JSONObject args,TaskStore.TeamMemberRead read)throws Exception{
        JSONObject selection=read.view();
        if(name.equals("team_materials"))return new JSONObject().put("allowedRecords",read.allowedRecords()).put("allowedSources",read.allowedSources()).put("skills",selection.getJSONArray("skills")).put("resources",selection.getJSONArray("resources")).put("publicTeamFlowSharedSeparately",true);
        if(name.equals("memory_search")||name.equals("memory_recall")||name.equals("team_read_source")){
            if(!(features.module("memory") instanceof MemoryModule))throw new SecurityException("原记忆模块不可用");MemoryModule memory=(MemoryModule)features.module("memory");
            if(name.equals("memory_search"))return memory.searchAudience(TeamMemberEvidenceHost.ownerScope(),args.getString("query"),args.optInt("limit",8),read);
            if(name.equals("memory_recall"))return memory.contextSnapshot(TeamMemberEvidenceHost.ownerScope(),args.getString("query"),args.optInt("limit",8),12000,read);
            JSONArray spans=read.allowedSources();int index=args.getInt("sourceIndex");if(index>=spans.length())throw new SecurityException("不是本成员显选来源");JSONObject span=spans.getJSONObject(index);int offset=args.optInt("offset",span.getInt("offset")),length=args.optInt("length",Math.min(12000,span.getInt("chars")));return memory.taskMemoryUse(null,sources).readAudienceSource(TeamMemberEvidenceHost.ownerScope(),SourceRef.fromJson(span.getJSONObject("sourceRef")),offset,length,read);
        }
        if(name.equals("team_read_material")){
            JSONObject selected=null;JSONArray resources=selection.getJSONArray("resources");for(int i=0;i<resources.length();i++)if(args.getString("selectionId").equals(resources.getJSONObject(i).getString("selectionId")))selected=resources.getJSONObject(i);if(selected==null)throw new SecurityException("不是本成员显选资料");JSONObject actual=host.resource(selected);String text=actual.getString("text");if(!selected.getString("textHash").equals(TeamWorkFiles.hash(text)))throw new SecurityException("准确资料版本已变");int offset=args.optInt("offset",selected.getInt("offset")),length=args.optInt("length",Math.min(12000,selected.getInt("chars"))),limit=selected.getInt("offset")+selected.getInt("chars");if(offset<selected.getInt("offset")||(long)offset+length>limit)throw new SecurityException("读取超过主人显选范围，不扩全文");int end=TeamMemberEvidenceHost.end(text,offset,length);read.checkCurrent();String after=host.resource(selected).getString("text");if(!text.equals(after))throw new SecurityException("原资料读取期间变化");read.checkCurrent();return new JSONObject().put("selectionId",selected.getString("selectionId")).put("sha256",selected.getString("sha256")).put("content",text.substring(offset,end)).put("offset",offset).put("endOffset",end).put("hasMore",end<limit).put("scopeEndOffset",limit).put("unit","UTF-16");
        }
        if(!(features.module("skills") instanceof SkillsModule))throw new SecurityException("原技能模块不可用");SkillsModule skills=(SkillsModule)features.module("skills");JSONArray selected=selection.getJSONArray("skills");
        if(name.equals("skills_list")){JSONArray rows=new JSONArray();for(int i=0;i<selected.length();i++){JSONObject expected=selected.getJSONObject(i),actual=skills.handle("get",new JSONObject().put("scope",TeamMemberEvidenceHost.ownerScope()).put("id",expected.getString("id"))).getJSONObject("skill");if(actual.getInt("revision")!=expected.getInt("revision")||!actual.getString("digest").equals(expected.getString("digest"))||!actual.optBoolean("enabled"))throw new SecurityException("原技能已换版/停用");rows.put(new JSONObject(expected.toString()).put("name",actual.optString("name")));}return new JSONObject().put("skills",rows);}
        JSONObject expected=null;for(int i=0;i<selected.length();i++)if(args.getString("id").equals(selected.getJSONObject(i).getString("id")))expected=selected.getJSONObject(i);if(expected==null||!args.getString("digest").equals(expected.getString("digest")))throw new SecurityException("不是本成员显选技能版本");String path=args.optString("path","SKILL.md");boolean allowed=false;JSONArray paths=expected.getJSONArray("paths");for(int i=0;i<paths.length();i++)allowed|=path.equals(paths.getString(i));if(!allowed)throw new SecurityException("技能资源未显选给此成员");return skills.readForHost(TeamMemberEvidenceHost.ownerScope(),expected.getString("id"),path,args.optInt("offset",0),args.optInt("length",8000),expected.getInt("revision"),expected.getString("digest"),READS);
    }
    private void checkReturned()throws Exception{current();for(TaskStore.TeamMemberRead read:returned){read.checkCurrent();host.checkMaterials(read.view(),read);read.checkCurrent();}current();}
    @Override public void beforeModelRequest(String requestId,JSONArray projected,RunControl original)throws Exception{if(!caller.owns(original))throw new SecurityException("不是原成员控制");checkReturned();}
    @Override public boolean responseCurrent(RunControl original)throws Exception{if(!caller.owns(original))return false;try{checkReturned();return true;}catch(SecurityException revoked){return false;}}
    @Override public void close(){closed=true;returned.clear();host.closed(this);}
    private static JSONObject tool(String name,String description,JSONObject properties,String... required)throws Exception{return new JSONObject().put("type","function").put("function",new JSONObject().put("name",name).put("description",description).put("parameters",new JSONObject().put("type","object").put("properties",properties).put("required",new JSONArray(required)).put("additionalProperties",false)));}
    private static JSONObject props(Object... fields)throws Exception{JSONObject properties=new JSONObject();for(int i=0;i<fields.length;i+=2)properties.put((String)fields[i],fields[i+1]);return properties;}
    private static JSONObject text(int max)throws Exception{return new JSONObject().put("type","string").put("maxLength",max);}
    private static JSONObject number(int min,int max)throws Exception{return new JSONObject().put("type","integer").put("minimum",min).put("maximum",max);}
}
