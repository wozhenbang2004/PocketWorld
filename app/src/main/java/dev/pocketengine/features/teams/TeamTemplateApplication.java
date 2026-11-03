package dev.pocketengine.features.teams;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Definition data in the existing definitions document. No task, grant or budget is created.
 * The original TeamsModule monitor must span read, native save validation and one atomic write. */
public final class TeamTemplateApplication {
    public static final String KEY="teamTemplateApplications",FORMAT="pocketworld.team-template-applications.v1";
    private static final int MAX_RECEIPTS=64,MAX_TEAMS=16;
    private TeamTemplateApplication(){}

    /** Must be the original save normalization, with no persistence or execution side effect. */
    @FunctionalInterface public interface NativeSaveValidation {
        JSONObject normalize(JSONObject ordinarySaveInput)throws Exception;
    }
    public static final class Change {
        private final String originalHash,document,receipt;
        private final boolean write;
        private Change(JSONObject original,JSONObject document,JSONObject receipt,boolean write)throws Exception {
            originalHash=TeamRoleCatalog.dataHash(original);this.document=document.toString();this.receipt=receipt.toString();this.write=write;
        }
        public boolean writeRequired(){return write;}
        public JSONObject document()throws Exception{return new JSONObject(document);}
        public JSONObject receipt()throws Exception{return new JSONObject(receipt);}
        /** The host calls this under its original monitor immediately before JsonFiles.write. */
        public void requireUnchanged(JSONObject actual)throws Exception {
            if(!originalHash.equals(TeamRoleCatalog.dataHash(actual)))throw new IllegalStateException("原团队定义文档已变，未覆盖其他根字段或旧动作记录");
        }
    }

    public static Change apply(JSONObject stored,String nativeActionId,JSONObject request,
            JSONArray nativeCapabilities,JSONArray nativeReadTools,NativeSaveValidation nativeSave)throws Exception {
        actionId(nativeActionId);fields(request,Set.of("expectedCatalogRevision","templateId","templateVersion","templateHash","name"));
        long expected=integer(request,"expectedCatalogRevision",0,Long.MAX_VALUE);
        int version=(int)integer(request,"templateVersion",1,Integer.MAX_VALUE);
        String templateId=text(request,"templateId"),hash=text(request,"templateHash"),name=text(request,"name");
        if(!templateId.matches("[A-Za-z][A-Za-z0-9_-]{0,63}")||!hash.matches("[a-f0-9]{64}")||name.isBlank()||name.length()>80)
            throw new IllegalArgumentException("模板应用需要准确版本、hash和新团队名称");
        StandardCharsets.UTF_8.newEncoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(name));
        JSONObject document=new JSONObject(stored.toString()),applications=applications(document);JSONArray records=applications.getJSONArray("receipts");
        String requestHash=TeamRoleCatalog.dataHash(request),teamId=definitionId(nativeActionId);
        JSONArray teams=document.optJSONArray("teams");if(teams==null){if(document.has("teams"))throw new SecurityException("原团队定义格式不可核对，未覆盖");teams=new JSONArray();}
        if(teams.length()>MAX_TEAMS)throw new SecurityException("原团队定义超过有界范围，未截断");
        for(int i=0;i<records.length();i++){
            JSONObject record=records.getJSONObject(i);
            if(!nativeActionId.equals(record.getString("actionId")))continue;
            checkRecord(record);
            if(!requestHash.equals(record.getString("requestHash"))||!teamId.equals(record.getString("definitionId")))
                throw new SecurityException("原模板应用动作已绑定另一份准确请求");
            JSONObject current=find(teams,teamId),receipt=new JSONObject(record.toString()).put("duplicate",true)
                .put("definitionPresent",current!=null).put("historical",current==null||!record.getString("definitionHash").equals(TeamRoleCatalog.dataHash(current)));
            return new Change(stored,document,receipt,false);
        }
        if(records.length()>=MAX_RECEIPTS)throw new IllegalStateException("原模板应用记录已满，不删除旧动作身份后重新创建");
        if(teams.length()>=MAX_TEAMS||find(teams,teamId)!=null)throw new IllegalStateException("团队定义已满或原动作目标ID已有其他定义，未覆盖");
        if(expected!=TeamRoleCatalog.catalogRevision(document))throw new IllegalStateException("原角色目录已变化，请重新审阅");
        JSONObject template=TeamRoleCatalog.template(document,templateId,version,hash);
        JSONObject proposed=TeamRoleCatalog.definitionProposal(document,templateId,version,hash,name,nativeCapabilities,nativeReadTools);
        // Native save receives the ordinary fields only. Template metadata cannot impersonate
        // scope, permissions, a configured device, an execution caller or an existing team.
        JSONObject save=new JSONObject().put("id",teamId);
        for(String key:new String[]{"name","mode","leaderId","members"})save.put(key,proposed.get(key));
        JSONObject definition=new JSONObject(java.util.Objects.requireNonNull(nativeSave).normalize(new JSONObject(save.toString())).toString());
        fields(definition,Set.of("id","name","mode","leaderId","members","discussionFormat"));
        if(!teamId.equals(definition.getString("id"))||!save.getString("name").equals(definition.getString("name"))||!save.getString("mode").equals(definition.getString("mode"))
                ||!save.getString("leaderId").equals(definition.getString("leaderId")))throw new SecurityException("原save校验返回了另一份团队身份");
        JSONArray normalized=definition.getJSONArray("members"),drafts=save.getJSONArray("members");
        if(normalized.length()!=drafts.length())throw new SecurityException("原save校验没有保留准确成员选择");
        Set<String> members=new HashSet<>();for(int i=0;i<normalized.length();i++)members.add(normalized.getJSONObject(i).getString("id"));
        for(int i=0;i<drafts.length();i++)if(!members.remove(drafts.getJSONObject(i).getString("id")))throw new SecurityException("原save校验改变了稳定成员ID");
        JSONObject reference=new JSONObject().put("id",templateId).put("version",version).put("hash",hash);
        JSONArray roles=new JSONArray(),pins=template.getJSONArray("members");
        for(int i=0;i<pins.length();i++){JSONObject pin=pins.getJSONObject(i),role=pin.getJSONObject("role");roles.put(new JSONObject().put("memberId",pin.getString("memberId"))
            .put("roleId",role.getString("id")).put("version",role.getInt("version")).put("hash",role.getString("hash")));}
        definition.put("templateReference",reference).put("templateRoleReferences",roles);
        JSONObject record=new JSONObject().put("actionId",nativeActionId).put("requestHash",requestHash)
            .put("definitionId",teamId).put("definitionHash",TeamRoleCatalog.dataHash(definition))
            .put("templateReference",new JSONObject(reference.toString())).put("catalogRevisionAtApplication",expected)
            .put("status","definition_saved").put("executionAuthorized",false).put("runningTeamsChanged",false);
        record.put("recordHash",TeamRoleCatalog.dataHash(record));
        teams.put(definition);records.put(record);document.put("teams",teams).put(KEY,applications);
        return new Change(stored,document,new JSONObject(record.toString()).put("duplicate",false).put("definitionPresent",true).put("historical",false),true);
    }
    public static String definitionId(String nativeActionId){
        actionId(nativeActionId);return UUID.nameUUIDFromBytes(("pocketworld.team-template-definition.v1\0"+nativeActionId).getBytes(StandardCharsets.UTF_8)).toString();
    }
    private static JSONObject applications(JSONObject document)throws Exception {
        JSONObject value=document.optJSONObject(KEY);
        if(value==null){if(document.has(KEY))throw new SecurityException("原模板应用记录格式不可核对");return new JSONObject().put("format",FORMAT).put("receipts",new JSONArray());}
        fields(value,Set.of("format","receipts"));if(!FORMAT.equals(value.getString("format"))||value.getJSONArray("receipts").length()>MAX_RECEIPTS)throw new SecurityException("原模板应用记录格式或范围不可核对");
        Set<String> ids=new HashSet<>();JSONArray rows=value.getJSONArray("receipts");for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);checkRecord(row);if(!ids.add(row.getString("actionId")))throw new SecurityException("原模板应用动作身份重复");}
        return value;
    }
    private static void checkRecord(JSONObject row)throws Exception {
        actionId(row.getString("actionId"));JSONObject content=new JSONObject(row.toString());content.remove("recordHash");
        if(!definitionId(row.getString("actionId")).equals(row.getString("definitionId"))||!TeamRoleCatalog.dataHash(content).equals(row.getString("recordHash"))
                ||row.getBoolean("executionAuthorized")||row.getBoolean("runningTeamsChanged"))throw new SecurityException("原模板应用回执身份或正文已变");
    }
    private static JSONObject find(JSONArray rows,String id)throws Exception {for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);if(id.equals(row.getString("id")))return row;}return null;}
    private static String text(JSONObject value,String key)throws Exception{Object v=value.get(key);if(!(v instanceof String))throw new IllegalArgumentException("模板应用文字参数类型无效");return (String)v;}
    private static void actionId(String value){try{if(value==null||!UUID.fromString(value).toString().equals(value))throw new IllegalArgumentException();}catch(IllegalArgumentException bad){throw new SecurityException("需要原生接受的稳定模板应用UUID");}}
    private static long integer(JSONObject request,String key,long min,long max)throws Exception {Object value=request.get(key);if(!(value instanceof Integer||value instanceof Long)||((Number)value).longValue()<min||((Number)value).longValue()>max)throw new IllegalArgumentException("模板应用修订须为准确整数");return ((Number)value).longValue();}
    private static void fields(JSONObject value,Set<String> allowed){for(var keys=value.keys();keys.hasNext();)if(!allowed.contains(keys.next()))throw new SecurityException("模板应用数据不能夹带执行身份、权限或额外字段");}
}
