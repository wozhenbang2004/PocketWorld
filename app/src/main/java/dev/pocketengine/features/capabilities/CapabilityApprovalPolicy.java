package dev.pocketengine.features.capabilities;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.json.JSONObject;

/** Owner preferences select a reviewer, never a system permission or task grant. */
public final class CapabilityApprovalPolicy {
    public enum Decision { NATIVE, ALLOW, ASK, DENY, REVIEW }
    private final String mode;
    private final String instructions;
    private final Map<String,Decision> tools=new HashMap<>();
    private static final String[] LEGACY_WRITES={"browser_navigate","browser_click","browser_type","browser_scroll","browser_back","browser_forward","browser_reload","ssh_exec","ssh_write_file","attachments_create_text","attachments_create_document","attachments_transform_image","attachments_trim_media"};

    public CapabilityApprovalPolicy(JSONObject value) {
        try{
            JSONObject checked=normalize(value);mode=checked.getString("mode");instructions=checked.getString("instructions");
            JSONObject rules=checked.getJSONObject("tools");for(var keys=rules.keys();keys.hasNext();){String name=keys.next();tools.put(name,Decision.valueOf(rules.getString(name).toUpperCase(java.util.Locale.ROOT)));}
        }catch(Exception invalid){throw new IllegalArgumentException("无效的工具审批策略",invalid);}
    }
    /** Reading old preferences must not silently grant all newly supported tools. */
    public static JSONObject normalize(JSONObject value)throws Exception {
        if(value==null)return new JSONObject().put("version",2).put("mode","custom").put("tools",new JSONObject()).put("instructions","");
        Object rawVersion=value.opt("version");if(!(rawVersion instanceof Integer||rawVersion instanceof Long))throw new IllegalArgumentException("权限设置版本无效");
        long version=((Number)rawVersion).longValue();String mode=value.getString("mode");JSONObject rules=value.getJSONObject("tools"),copy=new JSONObject();
        if(version==1){
            if(!Set.of("ask","controlled","auto").contains(mode))throw new IllegalArgumentException("旧权限模式无效");
            if("auto".equals(mode))for(String name:LEGACY_WRITES)copy.put(name,"allow");
            if("controlled".equals(mode))copy.put("attachments_create_text","allow").put("attachments_create_document","allow");
            mode="custom";
        }else if(version!=2||!Set.of("custom","auto_review","full_access").contains(mode))throw new IllegalArgumentException("请选择有效权限模式");
        if(rules.length()>512)throw new IllegalArgumentException("工具审批规则最多512项");
        for(var keys=rules.keys();keys.hasNext();){String name=keys.next();Object decision=rules.opt(name);
            if(!name.matches("[A-Za-z][A-Za-z0-9_-]{0,63}")||!(decision instanceof String)||!Set.of("allow","ask","deny","review").contains(decision))throw new IllegalArgumentException("请使用精确工具名及允许/询问/禁止/AI审批规则");
            copy.put(name,decision);
        }
        Object instructions=version==1?"":value.opt("instructions");if(instructions==null)instructions="";
        if(!(instructions instanceof String)||((String)instructions).length()>8000)throw new IllegalArgumentException("AI审批规则最多8000字符");
        return new JSONObject().put("version",2).put("mode",mode).put("tools",copy).put("instructions",instructions);
    }
    public String mode(){return mode;}
    public String instructions(){return instructions;}
    public Decision decisionFor(String name,boolean effecting){
        Decision explicit=tools.get(name);if(explicit!=null)return explicit;
        if("full_access".equals(mode))return Decision.ALLOW;
        if("auto_review".equals(mode)&&effecting)return Decision.REVIEW;
        return Decision.NATIVE;
    }
    public void requireNotDenied(String tool){if(tools.get(tool)==Decision.DENY)throw new SecurityException("能力已被用户权限策略禁止："+tool);}
    public Decision decisionFor(CapabilityDescriptor tool){Decision choice=decisionFor(tool.name,tool.approvalRequired);return choice==Decision.NATIVE?(tool.approvalRequired?Decision.ASK:Decision.ALLOW):choice;}
}
