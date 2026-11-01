package dev.pocketengine.browser;

import java.net.URI;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Native owner policy inside the original task.executionScope; no second grant store or executor. */
public final class BrowserActionScope {
    public static final int MAX_ACTIONS=100,MAX_ORIGINS=12;
    private static final Set<String> ROUTINE=Set.of("open","navigate","select","observe","scroll","wait","retain_source","focus","type","set_date","evaluate","inspect","network","download_status","finish","click","select_option","set_checked");
    private static final Set<String> INTERACTIONS=Set.of("click","select_option","set_checked");
    private static final String RISK="(?s).*(submit|send|delete|remove|pay|purchase|checkout|logout|signout|unsubscribe|confirm|approve|authorize|consent|subscribe|accept|agree|提交|发送|删除|移除|支付|购买|结算|退出|退订|确认|同意|授权|订阅|下单|保存).*";
    private BrowserActionScope() { }
    public static final class Consent {
        private final JSONObject value;
        private Consent(JSONObject value){this.value=value;}
        public boolean enabled(){return value.optBoolean("enabled");}
        public JSONObject review()throws Exception{return new JSONObject(value.toString());}
    }
    public static Consent disabled(){return new Consent(new JSONObject());}
    public static Consent forOwner(boolean enabled,String origins,int actions,boolean ordinaryInput)throws Exception {
        return forOwner(enabled,origins,actions,ordinaryInput,false,false,false,"");
    }
    public static Consent forOwner(boolean enabled,String origins,int actions,boolean ordinaryInput,boolean clicks,boolean selections,boolean checks,String targets)throws Exception {
        return forOwner(enabled,origins,actions,ordinaryInput,clicks,selections,checks,targets,new JSONArray());
    }
    public static Consent forOwner(boolean enabled,String origins,int actions,boolean ordinaryInput,boolean clicks,boolean selections,boolean checks,String targets,JSONArray observed)throws Exception {
        if(!enabled)return disabled();if(origins==null||origins.length()>4096||actions<1||actions>MAX_ACTIONS)throw new IllegalArgumentException("需要明确站点及1至100次日常浏览上限");
        Set<String> exact=new LinkedHashSet<>();for(String line:origins.split("\\r?\\n")){if(line.isBlank())continue;String input=line.trim(),canonical=origin(input);if(!canonical.equals(input))throw new IllegalArgumentException("请填写完整网站来源，不含路径、查询或片段；不会悄悄扩大成整个站点");exact.add(canonical);if(exact.size()>MAX_ORIGINS)throw new IllegalArgumentException("最多授权12个明确站点");}
        if(exact.isEmpty())throw new IllegalArgumentException("请逐行明确允许连续操作的网站来源");
        JSONArray interactionActions=new JSONArray();if(clicks)interactionActions.put("click");if(selections)interactionActions.put("select_option");if(checks)interactionActions.put("set_checked");
        JSONArray interactionTargets=new JSONArray();if(interactionActions.length()>0){if(targets==null||targets.length()>4096)throw new IllegalArgumentException("请明确允许重复交互的控件");Set<String> names=new LinkedHashSet<>();for(String line:targets.split("\\r?\\n")){String name=line.trim();if(name.isEmpty())continue;checkInteractionTarget(name);names.add(name);if(names.size()>24)throw new IllegalArgumentException("最多明确24个控件边界");}if(names.isEmpty()&&observed.length()==0)throw new IllegalArgumentException("选择交互动作后，请填写允许的完整控件名称或#id，不能默认放行本站所有按钮");for(String name:names)interactionTargets.put(name);}
        return new Consent(new JSONObject().put("enabled",true).put("allowedOrigins",new JSONArray(exact)).put("maxActions",actions).put("ordinaryInput",ordinaryInput).put("interactionActions",interactionActions).put("interactionTargets",interactionTargets).put("observedTargets",new JSONArray(observed.toString())).put("effectfulSubmissionAllowed",false).put("scopeLifetime","original_task_deadline_and_budget"));
    }
    public static JSONObject validate(JSONObject value)throws Exception {
        Set<String> keys=Set.of("enabled","allowedOrigins","maxActions","ordinaryInput","interactionActions","interactionTargets","observedTargets","effectfulSubmissionAllowed","scopeLifetime");if(value.length()!=keys.size())throw new SecurityException("浏览范围字段无效");for(var i=value.keys();i.hasNext();)if(!keys.contains(i.next()))throw new SecurityException("浏览范围含额外授权");
        if(!Boolean.TRUE.equals(value.get("enabled"))||!(value.get("ordinaryInput") instanceof Boolean)||!Boolean.FALSE.equals(value.get("effectfulSubmissionAllowed"))||!value.getString("scopeLifetime").equals("original_task_deadline_and_budget"))throw new SecurityException("日常范围不能放行有后果提交");
        int count=value.getInt("maxActions");if(!(value.get("maxActions") instanceof Number)||((Number)value.get("maxActions")).doubleValue()!=count||count<1||count>MAX_ACTIONS)throw new SecurityException("浏览预算无效");
        JSONArray all=value.getJSONArray("allowedOrigins");if(all.length()<1||all.length()>MAX_ORIGINS)throw new SecurityException("浏览站点范围无效");Set<String> seen=new LinkedHashSet<>();for(int n=0;n<all.length();n++){String raw=all.getString(n);if(!origin(raw).equals(raw)||!seen.add(raw))throw new SecurityException("站点必须是明确无重复的原来源");}
        JSONArray interactions=value.getJSONArray("interactionActions"),targets=value.getJSONArray("interactionTargets"),observed=value.getJSONArray("observedTargets");if(interactions.length()>3||targets.length()>24||observed.length()>24||(interactions.length()==0)!=(targets.length()+observed.length()==0))throw new SecurityException("交互动作与明确控件边界不匹配");Set<String> unique=new LinkedHashSet<>();for(int i=0;i<interactions.length();i++)if(!INTERACTIONS.contains(interactions.getString(i))||!unique.add(interactions.getString(i)))throw new SecurityException("交互动作无效");unique.clear();for(int i=0;i<targets.length();i++){String target=targets.getString(i);checkInteractionTarget(target);if(!unique.add(target))throw new SecurityException("重复的交互目标");}for(int i=0;i<observed.length();i++){JSONObject row=observed.getJSONObject(i),pin=row.getJSONObject("pin"),args=row.getJSONObject("arguments");if(row.length()!=2||args.length()!=3)throw new SecurityException("原观察交互候选无效");for(String key:new String[]{"tabId","observationId","ref"})if(!args.getString(key).equals(key.equals("ref")?pin.getJSONObject("targetPreview").getString(key):pin.getString(key)))throw new SecurityException("候选不属于原观察");String name=targetName(pin.getJSONObject("targetPreview"));checkInteractionTarget(name);if(!pin.getJSONObject("targetPreview").optString("nodeKey").matches("k[1-9][0-9]{0,6}"))throw new SecurityException("候选没有原节点身份");if(pin.optString("documentId").isEmpty())throw new SecurityException("候选没有原文档身份");}return new JSONObject(value.toString());
    }
    public static String targetName(JSONObject target){return !target.optString("id").isEmpty()?"#"+target.optString("id"):target.optString("text");}
    public static boolean candidate(JSONObject target){try{checkInteractionTarget(targetName(target));String type=target.optString("type"),label=(target.optString("text")+" "+target.optString("name")+" "+target.optString("id")).toLowerCase(Locale.ROOT);return !target.optBoolean("ownerRequired")&&!label.matches(RISK)&&!Set.of("password","file","hidden","submit","reset","image").contains(type)&&target.optString("formAction").isEmpty()&&target.optString("submitterFormAction").isEmpty()&&target.optString("href").isEmpty()&&(target.optString("tag").equals("BUTTON")&&type.equals("button")||target.optString("role").equals("button")||target.has("control")&&target.getJSONObject("control").optString("kind").matches("select|checkbox|radio"));}catch(Exception unavailable){return false;}}
    private static void checkInteractionTarget(String value)throws Exception {
        if(value.isEmpty()||value.length()>159||!value.equals(value.trim())||value.contains("*")||value.contains("\n")||value.toLowerCase(Locale.ROOT).matches(RISK)||value.startsWith("#")&&!value.substring(1).matches("[A-Za-z_][A-Za-z0-9_:.\\-]{0,157}"))throw new IllegalArgumentException("交互边界须为非敏感控件的完整名称或#id，不支持通配与有后果操作");
    }
    private static boolean interactionAllowed(JSONObject policy,String command,JSONObject target,JSONObject args,JSONObject pin)throws Exception {
        JSONArray actions=policy.getJSONArray("interactionActions");boolean opted=false;for(int i=0;i<actions.length();i++)if(command.equals(actions.getString(i)))opted=true;if(!opted)return false;
        // This is the owner's explicit control allowlist, NOT a claim that a page label proves safety.
        if(!target.optString("formAction").isEmpty()||!target.optString("submitterFormAction").isEmpty()||!target.optString("href").isEmpty())return false;
        JSONArray names=policy.getJSONArray("interactionTargets");boolean selected=false;for(int i=0;i<names.length();i++){String name=names.getString(i);if(name.startsWith("#")?name.substring(1).equals(target.optString("id")):name.equals(target.optString("text")))selected=true;}if(!selected){JSONArray observed=policy.getJSONArray("observedTargets");for(int i=0;i<observed.length();i++){JSONObject original=observed.getJSONObject(i).getJSONObject("pin");boolean current=true;for(String key:new String[]{"taskId","runId","epoch","profileId","profileRevision","browserSessionId","browserSpaceId","tabId","tabGeneration","navigation","documentId"})if(!String.valueOf(original.get(key)).equals(String.valueOf(pin.opt(key))))current=false;JSONObject a=original.optJSONObject("actionFrame"),b=pin.optJSONObject("actionFrame");if((a==null)!=(b==null))current=false;else if(a!=null)for(String key:new String[]{"frameId","documentId","sourceOrigin","topDocumentId"})if(!String.valueOf(a.get(key)).equals(String.valueOf(b.opt(key))))current=false;JSONObject originalTarget=original.getJSONObject("targetPreview");if(current&&!originalTarget.optString("nodeKey").isEmpty()&&originalTarget.optString("nodeKey").equals(target.optString("nodeKey"))&&targetName(originalTarget).equals(targetName(target)))selected=true;}}if(!selected)return false;
        if(command.equals("click"))return target.optString("tag").equals("BUTTON")&&target.optString("type").equals("button")||target.optString("role").equals("button")&&!target.optString("tag").matches("INPUT|SELECT|TEXTAREA|A|BUTTON");
        JSONObject choice=BrowserFormControls.requireTarget(target,args);return !choice.optString("label").toLowerCase(Locale.ROOT).matches(RISK)&&!choice.optString("value").toLowerCase(Locale.ROOT).matches(RISK);
    }
    public static String origin(String value)throws Exception {
        URI u=new URI(value);if(!Set.of("https","http").contains(u.getScheme())||u.getHost()==null||u.getUserInfo()!=null)throw new SecurityException("站点仅支持无凭据HTTP(S)来源");
        return new URI(u.getScheme().toLowerCase(Locale.ROOT),null,u.getHost().toLowerCase(Locale.ROOT),u.getPort(),null,null,null).toASCIIString();
    }
    private static boolean allowed(JSONObject policy,String url)throws Exception {String actual=origin(url);JSONArray origins=policy.getJSONArray("allowedOrigins");for(int i=0;i<origins.length();i++)if(actual.equals(origins.getString(i)))return true;return false;}
    /** Page labels are untrusted hints only; current identity, exact DOM guard and original prepared operation remain mandatory. */
    public static boolean eligible(JSONObject policy,String tool,JSONObject args,JSONObject pin)throws Exception {
        if(!tool.startsWith("browser_task_")||!ROUTINE.contains(tool.substring(13)))return false;String command=tool.substring(13);
        if(command.equals("finish"))return args.length()==0;
        if(command.equals("download_status"))return !args.optString("downloadId").isEmpty()&&args.optString("downloadId").equals(pin.optString("downloadId"));
        if(command.equals("open")||command.equals("navigate")){String url=args.getString("url");return allowed(policy,url)&&!url.toLowerCase(Locale.ROOT).matches(RISK);}
        if(!args.optString("tabId").equals(pin.optString("tabId"))||!allowed(policy,pin.getString("pageUrl")))return false;
        JSONObject readFrame=pin.optJSONObject("readFrame");if(readFrame!=null&&!allowed(policy,readFrame.getString("sourceOrigin")))return false;JSONObject frame=pin.optJSONObject("actionFrame");if(frame!=null&&!allowed(policy,frame.getString("sourceOrigin")))return false;
        if(command.equals("network"))return args.optString("action").equals("read");if(Set.of("observe","wait","retain_source","scroll","select","evaluate","inspect").contains(command))return true;
        JSONObject target=pin.optJSONObject("targetPreview");if(target==null||target.optBoolean("ownerRequired"))return false;String label=(target.optString("text")+" "+target.optString("name")+" "+target.optString("id")).toLowerCase(Locale.ROOT);if(label.matches(RISK))return false;
        String type=target.optString("type").toLowerCase(Locale.ROOT);if(Set.of("password","file","hidden","submit","reset","image").contains(type))return false;
        if(command.equals("focus"))return true;
        if(command.equals("type")||command.equals("set_date"))return policy.getBoolean("ordinaryInput");
        return INTERACTIONS.contains(command)&&interactionAllowed(policy,command,target,args,pin);
    }
    public static JSONObject receipt(JSONObject receipt,JSONObject permit)throws Exception {return receipt.put("taskScopeAuthorization",new JSONObject().put("mode","native_task_browser_actions").put("operationId",permit.getString("operationId")).put("scopeRevision",permit.getLong("scopeRevision")).put("browserRevision",permit.getLong("browserRevision")).put("remainingActions",permit.getInt("remainingActions")).put("preciseEffectApprovalsRequired",true));}
}
