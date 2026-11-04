package dev.pocketengine.features.workspace;

import dev.pocketengine.features.controlplane.WorkProfile;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Portable section envelopes only. Each original store retains its writer, CAS and receipt. */
public final class OwnerConfigurationBackupSections {
    private OwnerConfigurationBackupSections() { }
    public interface Review { JSONObject metadata()throws Exception; JSONObject restore()throws Exception; default void reserve()throws Exception { } }
    public static final Set<String> KINDS=Set.of("settings","skills","schedules");
    public static JSONObject create(String kind,JSONObject content,JSONArray omitted)throws Exception {
        requireKind(kind);
        JSONObject value=new JSONObject().put("format","pocketworld.owner-"+kind+".v1").put("version",1)
                .put("content",new JSONObject(content.toString())).put("omitted",new JSONArray(omitted.toString()))
                .put("secretsIncluded",false).put("activePermissionsIncluded",false).put("deviceSessionsIncluded",false)
                .put("restoredEnabled",false);
        value.put("sectionDigest",digest(value));return checked(kind,value);
    }
    public static JSONObject checked(String kind,JSONObject exact)throws Exception {
        requireKind(kind);if(exact==null||exact.toString().getBytes(StandardCharsets.UTF_8).length>8*1024*1024)throw new SecurityException("配置归档分区超过8MiB");
        fields(exact,"format","version","content","omitted","secretsIncluded","activePermissionsIncluded","deviceSessionsIncluded","restoredEnabled","sectionDigest");
        Object version=exact.opt("version");
        if(!("pocketworld.owner-"+kind+".v1").equals(exact.getString("format"))||!(version instanceof Integer||version instanceof Long)||((Number)version).longValue()!=1
                ||!Boolean.FALSE.equals(exact.opt("secretsIncluded"))||!Boolean.FALSE.equals(exact.opt("activePermissionsIncluded"))
                ||!Boolean.FALSE.equals(exact.opt("deviceSessionsIncluded"))||!Boolean.FALSE.equals(exact.opt("restoredEnabled")))throw new SecurityException("归档版本或停用/权限范围不支持");
        exact.getJSONObject("content");JSONArray omitted=exact.getJSONArray("omitted");if(omitted.length()>300)throw new SecurityException("归档排除清单超限");
        for(int i=0;i<omitted.length();i++){JSONObject row=omitted.getJSONObject(i);fields(row,"id","reason");text(row,"id",200);text(row,"reason",500);}
        if(!digest(exact).equals(exact.getString("sectionDigest")))throw new SecurityException("配置归档摘要不匹配");
        return new JSONObject(exact.toString());
    }
    private static void requireKind(String kind){if(!KINDS.contains(kind))throw new IllegalArgumentException("不支持的配置归档分区");}
    private static String digest(JSONObject value)throws Exception{JSONObject copy=new JSONObject(value.toString());copy.remove("sectionDigest");return WorkProfile.fingerprint(copy);}
    public static String originalId(String kind,String action,String source)throws Exception {
        requireKind(kind);if(!validAction(action)||source==null||source.isBlank()||source.length()>200)throw new SecurityException("原恢复动作或来源身份无效");
        return UUID.nameUUIDFromBytes(("owner-"+kind+"-restore-v1:"+action+":"+source).getBytes(StandardCharsets.UTF_8)).toString();
    }
    public static JSONObject receipt(String action,String sectionDigest,String source)throws Exception {
        if(!validAction(action)||sectionDigest==null||!sectionDigest.matches("[a-f0-9]{64}")||source==null||source.isBlank()||source.length()>210)throw new SecurityException("归档原动作/摘要不可核对");
        return new JSONObject().put("format","pocketworld.owner-configuration-restore.v1").put("actionId",action).put("sectionDigest",sectionDigest).put("sourceId",source);
    }
    public static boolean matches(JSONObject receipt,String action,String digest,String source){return receipt!=null&&"pocketworld.owner-configuration-restore.v1".equals(receipt.optString("format"))&&action.equals(receipt.optString("actionId"))&&digest.equals(receipt.optString("sectionDigest"))&&source.equals(receipt.optString("sourceId"));}
    public static void fields(JSONObject value,String... allowed){Set<String> names=Set.of(allowed);for(var keys=value.keys();keys.hasNext();)if(!names.contains(keys.next()))throw new SecurityException("归档包含未声明字段");}
    public static String text(JSONObject value,String name,int max)throws Exception{Object raw=value.get(name);if(!(raw instanceof String text)||text.isBlank()||text.length()>max||text.indexOf('\0')>=0)throw new SecurityException("归档字段无效："+name);return text;}
    private static boolean validAction(String action){return action!=null&&action.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}");}
    /** Preview never forwards skill bodies or the entire raw section to a UI bridge. */
    public static JSONObject preview(String kind,JSONObject selected)throws Exception {
        JSONObject section=checked(kind,selected),content=section.getJSONObject("content"),counts=new JSONObject();JSONArray entries=new JSONArray();
        if(kind.equals("settings")){
            for(String field:new String[]{"apiConnections","modelGroups","workProfiles"})counts.put(field,content.getJSONArray(field).length());
            JSONArray api=content.getJSONArray("apiConnections");for(int i=0;i<api.length();i++){JSONObject row=api.getJSONObject(i),config=row.getJSONObject("configuration");entries.put(new JSONObject().put("kind","api").put("sourceId",row.getString("sourceId")).put("name",row.getString("name")).put("provider",config.getString("provider")).put("baseUrl",config.getString("baseUrl")).put("model",config.getString("model")));}
            for(String field:new String[]{"modelGroups","workProfiles"}){JSONArray rows=content.getJSONArray(field);for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);entries.put(new JSONObject().put("kind",field).put("sourceId",row.getString(field.equals("modelGroups")?"groupId":"profileId")).put("name",row.getString("name")));}}
        }else{
            JSONArray rows=content.getJSONArray(kind.equals("skills")?"packages":"rules");counts.put(kind,rows.length());for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i),entry=new JSONObject().put("sourceId",row.getString("sourceId"));for(String field:kind.equals("skills")?new String[]{"name","digest","projectBindingRequired"}:new String[]{"title","kind","goal","schedule"})entry.put(field,row.get(field));entries.put(entry);}
        }
        return new JSONObject().put("counts",counts).put("entries",entries).put("omitted",section.getJSONArray("omitted")).put("restoredActive",false).put("secretsIncluded",false).put("requiresNewBindingAndApproval",true);
    }
    public static JSONObject result(JSONArray rows)throws Exception {boolean complete=true;for(int i=0;i<rows.length();i++)complete&=rows.getJSONObject(i).optBoolean("found");return new JSONObject().put("rows",rows).put("found",complete).put("status",complete?"completed":"unknown").put("readOnly",true).put("replayAllowed",false).put("activePermissionsRestored",false);}
}
