package dev.pocketengine.features.teams;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Versioned role/template DATA in the existing definitions document.
 * Native runner/read-tool observations are supplied by the original host; no template grants them. */
public final class TeamRoleCatalog {
    public static final String KEY="roleCatalog",FORMAT="pocketworld.team.roles.v1";
    private static final int MAX_ROLES=32,MAX_TEMPLATES=16;
    private TeamRoleCatalog(){}
    public static final class Change {
        private final String document,receipt;
        private Change(JSONObject document,JSONObject receipt){this.document=document.toString();this.receipt=receipt.toString();}
        public JSONObject document()throws Exception{return new JSONObject(document);}
        public JSONObject receipt()throws Exception{return new JSONObject(receipt);}
    }
    /** The existing TeamsModule must write this complete document under its existing monitor. */
    public static Change change(JSONObject stored,String action,JSONObject request,JSONArray nativeReadTools)throws Exception {
        JSONObject document=new JSONObject(stored.toString()),catalog=catalog(document);long revision=revision(request,"expectedCatalogRevision",0);
        if(revision!=catalog.getLong("revision"))throw new IllegalStateException("角色目录已变化，请重新审阅准确版本");
        JSONObject result;
        switch(action){
            case "saveRole":{
                fields(request,Set.of("expectedCatalogRevision","role"));JSONObject input=request.getJSONObject("role");
                Set<String> allowed=Set.of("id","name","description","instruction","acceptance","runner","suggestedModel","readToolNeeds","inputContract","outputContract");fields(input,allowed);
                String id=id(input.getString("id"));JSONObject previous=find(catalog.getJSONArray("roles"),id,false);JSONObject role=new JSONObject().put("id",id).put("version",previous==null?1:Math.addExact(previous.getInt("version"),1)).put("archived",false)
                    .put("name",text(input,"name",80,true)).put("description",text(input,"description",1200,true)).put("instruction",text(input,"instruction",2000,true)).put("acceptance",text(input,"acceptance",1200,false)).put("inputContract",text(input,"inputContract",1200,false)).put("outputContract",text(input,"outputContract",1200,false));
                String runner=input.getString("runner");if(!runner.matches("model|codex_app_server|claude|kimi"))throw new IllegalArgumentException("角色runner未由原成员合同识别");role.put("runner",runner);
                if(input.has("suggestedModel")){String model=text(input,"suggestedModel",200,true);if(!model.matches("[A-Za-z0-9_.:/@+-]+"))throw new IllegalArgumentException("模型建议只能为公开模型标识");role.put("suggestedModel",model);}
                role.put("readToolNeeds",subset(input.getJSONArray("readToolNeeds"),nativeReadTools));role.put("hash",hash(role));catalog.put("roles",replace(catalog.getJSONArray("roles"),role,MAX_ROLES));result=role;break;
            }
            case "saveTemplate":{
                fields(request,Set.of("expectedCatalogRevision","template"));JSONObject input=request.getJSONObject("template");fields(input,Set.of("id","name","mode","leaderId","members"));String id=id(input.getString("id"));JSONObject previous=find(catalog.getJSONArray("templates"),id,false);
                String mode=input.getString("mode");if(!mode.matches("debate|collaborate|produce"))throw new IllegalArgumentException("模板讨论模式无效");JSONArray drafts=input.getJSONArray("members"),members=new JSONArray();if(drafts.length()<1||drafts.length()>4)throw new IllegalArgumentException("模板需1至4名成员");Set<String> ids=new HashSet<>();
                for(int i=0;i<drafts.length();i++){
                    JSONObject draft=drafts.getJSONObject(i);fields(draft,Set.of("memberId","name","roleId","roleVersion","roleHash","dependsOn"));String member=id(draft.getString("memberId"));if(!ids.add(member))throw new IllegalArgumentException("模板成员ID重复");JSONObject role=find(catalog.getJSONArray("roles"),id(draft.getString("roleId")),true);
                    exact(role,draft.getInt("roleVersion"),draft.getString("roleHash"));if(role.getBoolean("archived"))throw new SecurityException("已归档角色不能进入新模板");
                    members.put(new JSONObject().put("memberId",member).put("name",text(draft,"name",48,true)).put("role",new JSONObject(role.toString())).put("dependsOn",new JSONArray(draft.getJSONArray("dependsOn").toString())));
                }
                String leader=input.getString("leaderId");if(!ids.contains(leader))throw new IllegalArgumentException("模板必须明确一名原成员Leader");validateDependencies(members,ids);
                JSONObject template=new JSONObject().put("id",id).put("version",previous==null?1:Math.addExact(previous.getInt("version"),1)).put("name",text(input,"name",80,true)).put("mode",mode).put("leaderId",leader).put("members",members).put("archived",false);template.put("hash",hash(template));catalog.put("templates",replace(catalog.getJSONArray("templates"),template,MAX_TEMPLATES));result=template;break;
            }
            case "archiveRole":case "removeRole":case "archiveTemplate":case "removeTemplate":{
                fields(request,Set.of("expectedCatalogRevision","id","expectedVersion","expectedHash"));boolean role=action.endsWith("Role"),remove=action.startsWith("remove");String list=role?"roles":"templates",id=id(request.getString("id"));JSONObject current=find(catalog.getJSONArray(list),id,true);exact(current,request.getInt("expectedVersion"),request.getString("expectedHash"));JSONArray next=new JSONArray();
                result=new JSONObject(current.toString());if(!remove){result.put("version",Math.addExact(current.getInt("version"),1)).put("archived",true);result.remove("hash");result.put("hash",hash(result));}
                JSONArray rows=catalog.getJSONArray(list);for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);if(!id.equals(row.getString("id")))next.put(row);else if(!remove)next.put(result);}catalog.put(list,next);result=new JSONObject().put("id",id).put("removed",remove).put("archived",!remove).put("previousVersion",current.getInt("version")).put("previousHash",current.getString("hash"));break;
            }
            default:throw new IllegalArgumentException("角色目录没有此数据动作");
        }
        catalog.put("revision",Math.addExact(revision,1));document.put(KEY,catalog);
        return new Change(document,new JSONObject().put("catalogRevision",catalog.getLong("revision")).put("value",result).put("executionAuthorized",false).put("runningTeamsChanged",false));
    }
    public static JSONObject snapshot(JSONObject stored,JSONArray nativeRunnerCapabilities,JSONArray nativeReadTools)throws Exception {
        JSONObject catalog=catalog(stored),shown=new JSONObject(catalog.toString());JSONArray roles=shown.getJSONArray("roles");for(int i=0;i<roles.length();i++)roles.getJSONObject(i).put("availability",availability(roles.getJSONObject(i),nativeRunnerCapabilities,nativeReadTools));
        return shown.put("executionAuthorized",false).put("dataOnly",true);
    }
    public static JSONObject template(JSONObject stored,String id,int version,String expectedHash)throws Exception{JSONObject row=find(catalog(stored).getJSONArray("templates"),id(id),true);exact(row,version,expectedHash);return new JSONObject(row.toString()).put("executionAuthorized",false);}
    public static JSONObject role(JSONObject stored,String id,int version,String expectedHash)throws Exception{JSONObject row=find(catalog(stored).getJSONArray("roles"),id(id),true);exact(row,version,expectedHash);return new JSONObject(row.toString()).put("executionAuthorized",false);}
    public static long catalogRevision(JSONObject stored)throws Exception{return catalog(stored).getLong("revision");}
    /** Canonical data identity only; the original TaskStore still owns execution admission. */
    static String dataHash(JSONObject value)throws Exception{return hash(value);}
    static String dataText(JSONObject value)throws Exception{return canonical(value);}
    static JSONObject templateAvailability(JSONObject stored,JSONObject template,JSONArray capabilities,JSONArray readTools)throws Exception{
        JSONObject catalog=catalog(stored);JSONArray prerequisites=new JSONArray();boolean available=!template.getBoolean("archived");
        JSONArray pins=template.getJSONArray("members");for(int i=0;i<pins.length();i++){
            JSONObject pin=pins.getJSONObject(i),role=pin.getJSONObject("role"),current=find(catalog.getJSONArray("roles"),role.getString("id"),false);
            JSONObject condition=availability(role,capabilities,readTools);boolean active=current!=null&&!current.getBoolean("archived");condition.put("catalogRoleActive",active);available&=active&&condition.getBoolean("available");
            prerequisites.put(new JSONObject().put("memberId",pin.getString("memberId")).put("roleId",role.getString("id")).put("roleVersion",role.getInt("version")).put("roleHash",role.getString("hash")).put("condition",condition));
        }return new JSONObject().put("available",available).put("prerequisites",prerequisites).put("executionAuthorized",false);
    }
    /** Produces ordinary save input only. The original save/start/scope/budget gates still apply. */
    public static JSONObject definitionProposal(JSONObject stored,String templateId,int version,String expectedHash,String name,JSONArray nativeCapabilities,JSONArray nativeReadTools)throws Exception {
        JSONObject catalog=catalog(stored),template=find(catalog.getJSONArray("templates"),id(templateId),true);exact(template,version,expectedHash);if(template.getBoolean("archived"))throw new SecurityException("已归档模板不能再创建团队");if(name==null||name.trim().isEmpty()||name.length()>80)throw new IllegalArgumentException("新团队名称无效");JSONArray members=new JSONArray(),pins=template.getJSONArray("members");
        for(int i=0;i<pins.length();i++){
            JSONObject pin=pins.getJSONObject(i),role=pin.getJSONObject("role"),current=find(catalog.getJSONArray("roles"),role.getString("id"),true);if(current.getBoolean("archived"))throw new SecurityException("模板角色已归档，需重新选择");
            if(!availability(role,nativeCapabilities,nativeReadTools).getBoolean("available"))throw new SecurityException("原角色runner、探测或只读能力前提尚未满足");
            JSONObject member=new JSONObject().put("id",pin.getString("memberId")).put("name",pin.getString("name")).put("role",role.getString("description")).put("runner",role.getString("runner")).put("device","phone").put("assignment",role.getString("instruction")).put("dependsOn",new JSONArray(pin.getJSONArray("dependsOn").toString()));
            if(!role.getString("acceptance").isEmpty())member.put("acceptance",role.getString("acceptance"));if(role.has("suggestedModel"))member.put("model",role.getString("suggestedModel"));members.put(member);
        }
        return new JSONObject().put("name",name.trim()).put("mode",template.getString("mode")).put("leaderId",template.getString("leaderId")).put("members",members).put("templateReference",new JSONObject().put("id",templateId).put("version",version).put("hash",expectedHash)).put("executionAuthorized",false);
    }
    private static JSONObject availability(JSONObject role,JSONArray capabilities,JSONArray readTools)throws Exception {
        JSONArray missing=new JSONArray(),needed=role.getJSONArray("readToolNeeds");Set<String> nativeTools=strings(readTools);for(int i=0;i<needed.length();i++)if(!nativeTools.contains(needed.getString(i)))missing.put(needed.getString(i));
        boolean runner=false,probe=false;for(int i=0;i<capabilities.length();i++){JSONObject c=capabilities.getJSONObject(i);if(role.getString("runner").equals(c.optString("id"))){runner=c.optBoolean("available");probe=role.getString("runner").equals("model")||c.optBoolean("probeVerified");}}
        boolean active=!role.getBoolean("archived");return new JSONObject().put("available",active&&runner&&probe&&missing.length()==0).put("runnerAvailable",runner).put("probeVerified",probe).put("missingReadTools",missing).put("archived",!active).put("connectionVerified",false).put("executionAuthorized",false);
    }
    private static JSONObject catalog(JSONObject stored)throws Exception{
        JSONObject catalog=stored.optJSONObject(KEY);if(catalog==null){if(stored.has(KEY))throw new SecurityException("角色目录原数据格式不可核对，未覆盖");return new JSONObject().put("format",FORMAT).put("revision",0).put("roles",new JSONArray()).put("templates",new JSONArray());}
        if(!FORMAT.equals(catalog.getString("format")))throw new SecurityException("角色目录版本不可核对，保留原数据");revision(catalog,"revision",0);
        for(String list:new String[]{"roles","templates"}){JSONArray rows=catalog.getJSONArray(list);if(rows.length()>(list.equals("roles")?MAX_ROLES:MAX_TEMPLATES))throw new SecurityException("原角色目录超过有界范围");Set<String> ids=new HashSet<>();for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);if(!ids.add(id(row.getString("id")))||row.getInt("version")<1)throw new SecurityException("原角色目录身份重复或版本无效");JSONObject content=new JSONObject(row.toString());content.remove("hash");if(!hash(content).equals(row.getString("hash")))throw new SecurityException("原角色或模板正文不匹配固定hash");}}
        return new JSONObject(catalog.toString());
    }
    private static JSONArray replace(JSONArray rows,JSONObject next,int max)throws Exception{JSONArray out=new JSONArray();boolean replaced=false;for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);if(next.getString("id").equals(row.getString("id"))){out.put(next);replaced=true;}else out.put(row);}if(!replaced){if(out.length()>=max)throw new IllegalStateException("角色或模板目录已满，未覆盖旧内容");out.put(next);}return out;}
    private static JSONObject find(JSONArray rows,String id,boolean required)throws Exception{for(int i=0;i<rows.length();i++)if(id.equals(rows.getJSONObject(i).getString("id")))return rows.getJSONObject(i);if(required)throw new SecurityException("原角色或模板已不存在");return null;}
    private static void exact(JSONObject value,int version,String hash)throws Exception{if(version!=value.getInt("version")||!hash.equals(value.getString("hash")))throw new IllegalStateException("角色或模板准确版本已变化");}
    private static void validateDependencies(JSONArray members,Set<String> ids)throws Exception{for(int i=0;i<members.length();i++){JSONObject member=members.getJSONObject(i);JSONArray dependencies=member.getJSONArray("dependsOn");Set<String> seen=new HashSet<>();if(dependencies.length()>3)throw new IllegalArgumentException("模板成员依赖过多");for(int d=0;d<dependencies.length();d++){String id=dependencies.getString(d);if(!ids.contains(id)||member.getString("memberId").equals(id)||!seen.add(id))throw new IllegalArgumentException("模板依赖缺失、自依赖或重复");}visit(members,member.getString("memberId"),new HashSet<>());}}
    private static void visit(JSONArray members,String id,Set<String> active)throws Exception{if(!active.add(id))throw new IllegalArgumentException("模板依赖成环");for(int i=0;i<members.length();i++){JSONObject member=members.getJSONObject(i);if(id.equals(member.getString("memberId"))){JSONArray deps=member.getJSONArray("dependsOn");for(int d=0;d<deps.length();d++)visit(members,deps.getString(d),active);break;}}active.remove(id);}
    private static JSONArray subset(JSONArray needs,JSONArray allowed)throws Exception{if(needs.length()>16)throw new IllegalArgumentException("角色只读工具需求过多");Set<String> available=strings(allowed),seen=new HashSet<>();JSONArray out=new JSONArray();for(int i=0;i<needs.length();i++){String name=needs.getString(i);if(!available.contains(name)||!seen.add(name))throw new SecurityException("角色需求不是原宿主只读子集");out.put(name);}return out;}
    private static Set<String> strings(JSONArray values)throws Exception{Set<String> out=new HashSet<>();for(int i=0;i<values.length();i++)out.add(values.getString(i));return out;}
    private static String id(String id){if(!id.matches("[A-Za-z][A-Za-z0-9_-]{0,63}"))throw new IllegalArgumentException("角色/模板稳定ID无效");return id;}
    private static String text(JSONObject input,String key,int max,boolean required)throws Exception{Object value=input.get(key);if(!(value instanceof String))throw new IllegalArgumentException("角色文字类型无效");String text=(String)value;if(text.length()>max||required&&text.trim().isEmpty())throw new IllegalArgumentException("角色文字为空或过长："+key);StandardCharsets.UTF_8.newEncoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(text));return text;}
    private static long revision(JSONObject input,String key,long minimum)throws Exception{Object value=input.get(key);if(!(value instanceof Integer||value instanceof Long)||((Number)value).longValue()<minimum)throw new IllegalArgumentException("目录修订需准确整数");return ((Number)value).longValue();}
    private static void fields(JSONObject input,Set<String> allowed){for(var keys=input.keys();keys.hasNext();)if(!allowed.contains(keys.next()))throw new SecurityException("模板数据不能夹带权限、秘密、预算、脚本或私有上下文");}
    private static String hash(JSONObject value)throws Exception{byte[] digest=MessageDigest.getInstance("SHA-256").digest(canonical(value).getBytes(StandardCharsets.UTF_8));StringBuilder out=new StringBuilder();for(byte b:digest)out.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return out.toString();}
    private static String canonical(Object value)throws Exception{if(value instanceof JSONObject object){ArrayList<String> keys=new ArrayList<>();object.keys().forEachRemaining(keys::add);java.util.Collections.sort(keys);StringBuilder out=new StringBuilder("{");for(String key:keys){if(out.length()>1)out.append(',');out.append(JSONObject.quote(key)).append(':').append(canonical(object.get(key)));}return out.append('}').toString();}if(value instanceof JSONArray array){StringBuilder out=new StringBuilder("[");for(int i=0;i<array.length();i++){if(i>0)out.append(',');out.append(canonical(array.get(i)));}return out.append(']').toString();}return value instanceof String?JSONObject.quote((String)value):String.valueOf(value);}
}
