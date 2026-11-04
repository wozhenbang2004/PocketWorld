package dev.pocketengine.runtime.routes;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.runtime.providers.ProtocolJson;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Inert, de-identified navigation references. This format is deliberately not accepted by
 * PhoneRouteSession: no selectors, page text, source identity, confirmation or replay grant. */
public final class PortablePhoneRouteReference {
    public static final String FORMAT="pocketworld.portable-route-reference.v1";
    public static final int MAX_BYTES=16384,MAX_STEPS=4;
    private static final Set<String> ROLES=Set.of("button","checkbox","radio","switch","web","list","scroll","image","text","container");
    private static final Map<String,String> NAVIGATION=Map.ofEntries(
            Map.entry("首页","home"),Map.entry("主页","home"),Map.entry("home","home"),Map.entry("搜索","search"),Map.entry("search","search"),
            Map.entry("设置","settings"),Map.entry("settings","settings"),Map.entry("返回","back"),Map.entry("back","back"),
            Map.entry("下一页","next"),Map.entry("next","next"),Map.entry("上一页","previous"),Map.entry("previous","previous"),
            Map.entry("展开","expand"),Map.entry("收起","collapse"),Map.entry("详情","details"),Map.entry("details","details"),
            Map.entry("菜单","menu"),Map.entry("menu","menu"),Map.entry("通知","notifications"),Map.entry("notifications","notifications"),
            Map.entry("消息","messages"),Map.entry("messages","messages"),Map.entry("我的","profile"),Map.entry("profile","profile"));
    private PortablePhoneRouteReference(){}

    /** The caller must first admit the exact owner-confirmed native source. This pure projection
     * cannot assert that admission and never carries it into the transferable document. */
    public static JSONObject fromLocalCandidate(JSONObject candidate)throws Exception {
        if(candidate==null||!"pocketworld.phone-route-candidate.v1".equals(candidate.optString("format"))||!PhoneRouteSession.digest(candidate).equals(candidate.optString("digest")))throw invalid("原路线内容摘要不匹配");
        String pkg=packageName(candidate.opt("packageName"));long version=positive(candidate.opt("packageVersion"));
        JSONArray original=candidate.getJSONArray("steps");if(original.length()<1||original.length()>MAX_STEPS)throw invalid("只支持原路线的1至4步导航");
        JSONArray steps=new JSONArray();
        for(int i=0;i<original.length();i++){
            JSONObject source=original.getJSONObject(i),selector=source.getJSONObject("selector");String tool=source.getString("tool"),role=selector.getString("role").toLowerCase(Locale.ROOT);
            if(!ROLES.contains(role))throw invalid("这个原节点角色尚不支持可移植参考");
            JSONObject step=new JSONObject().put("tool",tool).put("role",role).put("targetParameter","target_"+(i+1)).put("beforeParameter","before_"+(i+1)).put("afterParameter","after_"+(i+1));
            if("phone_click_node".equals(tool)){
                String navigation=NAVIGATION.get(selector.getString("textExact").trim().toLowerCase(Locale.ROOT));if(navigation==null)throw invalid("原点击不是白名单导航意图");step.put("navigation",navigation);
            }else if("phone_scroll_node".equals(tool)){
                String direction=source.getString("direction");if(!Set.of("forward","backward").contains(direction))throw invalid("原滚动方向不受支持");step.put("navigation","scroll").put("direction",direction);
            }else throw invalid("参考不接受输入、坐标、脚本或其他动作");
            steps.put(step);
        }
        JSONObject result=new JSONObject().put("format",FORMAT).put("schemaVersion",1).put("application",new JSONObject().put("packageName",pkg).put("versionCode",version))
                .put("steps",steps).put("parameters",parameters(steps.length())).put("trust","unverified_reference").put("executionAuthorized",false);
        result.put("digest",PhoneRouteSession.digest(result));return checked(result);
    }

    public static JSONObject read(InputStream input,RunControl original)throws Exception {
        if(input==null||original==null)throw invalid("请选择本次原路线参考文件");ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[2048];
        while(true){original.check();int count=input.read(buffer);if(count<0)break;if(count==0)continue;if(bytes.size()+count>MAX_BYTES)throw invalid("路线参考超过16384字节");bytes.write(buffer,0,count);}
        original.check();String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
        JSONObject parsed;try{parsed=ProtocolJson.parse(text);}catch(Exception malformed){throw new IllegalArgumentException("路线参考文件不是完整、严格且无重复字段的JSON",malformed);}
        return checked(parsed);
    }

    /** Closed fields and finite enums, including parameter names: an import cannot smuggle
     * arbitrary text in a nominally harmless title, node ID, condition, approval or metadata. */
    public static JSONObject checked(JSONObject value)throws Exception {
        if(value==null||value.toString().getBytes(StandardCharsets.UTF_8).length>MAX_BYTES)throw invalid("路线参考大小无效");
        fields(value,"format","schemaVersion","application","steps","parameters","trust","executionAuthorized","digest");
        if(!FORMAT.equals(value.opt("format"))||positive(value.opt("schemaVersion"))!=1||!"unverified_reference".equals(value.opt("trust"))||!Boolean.FALSE.equals(value.opt("executionAuthorized")))throw invalid("路线参考版本、信任或权限声明无效");
        JSONObject app=value.getJSONObject("application");fields(app,"packageName","versionCode");packageName(app.opt("packageName"));positive(app.opt("versionCode"));
        JSONArray steps=value.getJSONArray("steps");if(steps.length()<1||steps.length()>MAX_STEPS)throw invalid("路线参考必须包含1至4步");
        for(int i=0;i<steps.length();i++){
            JSONObject step=steps.getJSONObject(i);String tool=step.getString("tool");boolean scroll="phone_scroll_node".equals(tool);
            if(scroll)fields(step,"tool","role","navigation","targetParameter","beforeParameter","afterParameter","direction");else fields(step,"tool","role","navigation","targetParameter","beforeParameter","afterParameter");
            if(!ROLES.contains(step.opt("role"))||!("target_"+(i+1)).equals(step.opt("targetParameter"))||!("before_"+(i+1)).equals(step.opt("beforeParameter"))||!("after_"+(i+1)).equals(step.opt("afterParameter")))throw invalid("节点角色或未绑定参数不符合当前参考版本");
            if(scroll){if(!"scroll".equals(step.opt("navigation"))||!Set.of("forward","backward").contains(step.opt("direction")))throw invalid("滚动参考无效");}
            else if(!"phone_click_node".equals(tool)||!NAVIGATION.containsValue(step.opt("navigation")))throw invalid("参考包含非导航动作");
        }
        JSONArray parameters=value.getJSONArray("parameters"),expected=parameters(steps.length());if(parameters.length()!=expected.length())throw invalid("未绑定参数清单不完整");
        for(int i=0;i<parameters.length();i++){JSONObject p=parameters.getJSONObject(i),e=expected.getJSONObject(i);fields(p,"name","kind");if(!e.getString("name").equals(p.opt("name"))||!e.getString("kind").equals(p.opt("kind")))throw invalid("参数不可携带账户、原节点或已绑定值");}
        Object digest=value.opt("digest");if(!(digest instanceof String)||!((String)digest).matches("[a-f0-9]{64}")||!PhoneRouteSession.digest(value).equals(digest))throw invalid("路线参考内容摘要不匹配");
        return new JSONObject(value.toString());
    }

    public static JSONObject presentation(JSONObject value)throws Exception {
        JSONObject reference=checked(value);String text=reference.toString(2);byte[] bytes=text.getBytes(StandardCharsets.UTF_8);
        return new JSONObject().put("reference",reference).put("text",text).put("digest",reference.getString("digest")).put("fileName","pocketworld-route-reference.json").put("mimeType","application/json").put("bytes",bytes.length).put("sha256",sha256(bytes))
                .put("referenceOnly",true).put("imported",false).put("executionAuthorized",false).put("replayAllowed",false).put("localTargetsBound",false).put("crossDeviceVerified",false)
                .put("message","只含App版本与导航意图；原目标、节点、页面文字、用户身份和审批已排除。所有目标与前后条件仍未绑定；导入只预览，不能回放或定时执行。");
    }
    static String sha256(byte[] bytes)throws Exception{StringBuilder out=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();}
    private static JSONArray parameters(int count)throws Exception{JSONArray out=new JSONArray();for(int i=1;i<=count;i++){out.put(new JSONObject().put("name","target_"+i).put("kind","current_unique_node"));out.put(new JSONObject().put("name","before_"+i).put("kind","current_page_precondition"));out.put(new JSONObject().put("name","after_"+i).put("kind","current_page_postcondition"));}return out;}
    private static String packageName(Object value){if(!(value instanceof String)||((String)value).length()>200||!((String)value).matches("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+"))throw invalid("App包名无效");return (String)value;}
    private static long positive(Object value){if(!(value instanceof Integer||value instanceof Long)||((Number)value).longValue()<1)throw invalid("版本必须为正整数");return ((Number)value).longValue();}
    private static void fields(JSONObject value,String...names){Set<String> allowed=Set.of(names);if(value.length()!=allowed.size())throw invalid("路线参考包含缺失或未知字段");for(var keys=value.keys();keys.hasNext();)if(!allowed.contains(keys.next()))throw invalid("路线参考包含未知字段");}
    private static IllegalArgumentException invalid(String message){return new IllegalArgumentException(message);}
}
