package dev.pocketengine.features.assistant;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Owner-selected object locators. They supply context, never an execution target or grant. */
public final class OwnerObjectReferences {
    public static final int MAX_REFERENCES=4;
    public interface Resolver { JSONObject select(JSONObject locator,dev.pocketengine.core.RunControl control)throws Exception; }
    private OwnerObjectReferences(){}

    public static JSONObject locator(JSONObject value)throws Exception {
        String kind=value.getString("kind"),id=value.getString("id");
        if(!Set.of("work","project","artifact","team").contains(kind)||id.isEmpty()||id.length()>240||!id.equals(id.trim())||id.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("请选择真实工作、项目、成果或团队");
        Set<String> fields=switch(kind){
            case "work"->Set.of("kind","id","runId","epoch","label");
            case "artifact"->Set.of("kind","id","sha256","label");
            default->Set.of("kind","id","label");
        };
        for(var keys=value.keys();keys.hasNext();)if(!fields.contains(keys.next()))throw new IllegalArgumentException("对象引用包含不支持的字段");
        JSONObject result=new JSONObject().put("kind",kind).put("id",id);
        if("work".equals(kind)){
            String run=value.getString("runId");Object epoch=value.get("epoch");
            if(!uuid(id)||!uuid(run)||!(epoch instanceof Integer||epoch instanceof Long)||((Number)epoch).longValue()<1||((Number)epoch).longValue()>Integer.MAX_VALUE)
                throw new IllegalArgumentException("原工作执行身份无效");
            result.put("runId",run).put("epoch",((Number)epoch).longValue());
        }else if("artifact".equals(kind)){
            String sha=value.getString("sha256");if(!sha.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("成果版本不可核对");result.put("sha256",sha);
        }
        return result;
    }

    public static JSONArray checked(JSONArray values)throws Exception {
        if(values==null||values.length()>MAX_REFERENCES)throw new IllegalArgumentException("每份草稿最多关联4个对象");
        JSONArray result=new JSONArray();Set<String> seen=new HashSet<>();
        for(int i=0;i<values.length();i++){
            JSONObject value=values.getJSONObject(i),ref=locator(value);String label=value.getString("label");
            if(label.isBlank()||label.length()>160||label.codePoints().anyMatch(Character::isISOControl)||!seen.add(ref.getString("kind")+":"+ref.getString("id")))
                throw new IllegalArgumentException("对象引用重复或名称无效");
            result.put(ref.put("label",label));
        }
        return result;
    }

    public static JSONObject selected(JSONObject locator,String nativeLabel)throws Exception {
        String label=nativeLabel==null?"":nativeLabel.replaceAll("[\\p{Cntrl}\\s]+"," ").trim();
        if(label.isEmpty())label=switch(locator.getString("kind")){case "work"->"这件工作";case "project"->"这个项目";case "artifact"->"这份成果";default->"这个团队";};
        if(label.length()>160)label=label.substring(0,Character.isHighSurrogate(label.charAt(159))?159:160);
        return checked(new JSONArray().put(locator(locator).put("label",label))).getJSONObject(0);
    }

    /** Display names are excluded: a locator is not a fresh read of the object's contents. */
    public static JSONArray locations(JSONArray refs)throws Exception {
        JSONArray result=new JSONArray(),checked=checked(refs);for(int i=0;i<checked.length();i++)result.put(locator(checked.getJSONObject(i)));return result;
    }

    public static JSONArray admit(JSONArray refs,Resolver resolver,dev.pocketengine.core.RunControl control)throws Exception {
        JSONArray checked=checked(refs);if(checked.length()>0&&resolver==null)throw new IllegalStateException("原对象读取宿主未就绪，草稿仍保留");
        for(int i=0;i<checked.length();i++){
            control.check();JSONObject expected=locator(checked.getJSONObject(i)),actual=locator(resolver.select(expected,control));
            if(!dev.pocketengine.features.memory.TaskMemoryTrace.rawFingerprint(expected).equals(dev.pocketengine.features.memory.TaskMemoryTrace.rawFingerprint(actual)))
                throw new SecurityException("原对象已变化，请重新选择；没有改投其他对象");
        }
        control.check();return checked;
    }
    private static boolean uuid(String value){try{return UUID.fromString(value).toString().equalsIgnoreCase(value);}catch(IllegalArgumentException invalid){return false;}}
}
