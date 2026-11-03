package dev.pocketengine.phone.observation;

import java.math.BigDecimal;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Closed argument contracts; no raw Intent, shell, clipboard read or arbitrary key event. */
public final class PhoneExtensionTools {
    public static final Set<String> NAMES=Set.of("phone_list_app_copies","phone_launch_app_copy","phone_clear_text","phone_paste_text","phone_gesture","phone_dialog","phone_event","phone_set_wheel","phone_practice_target","phone_practice_result","phone_practice_frame_check","phone_app_after_task");
    private PhoneExtensionTools() { }
    public static boolean writes(String name){return !Set.of("phone_list_app_copies","phone_dialog","phone_event","phone_practice_result","phone_practice_frame_check").contains(name);}
    public static void validate(String name,JSONObject p)throws Exception {
        if(!NAMES.contains(name))throw new IllegalArgumentException("phone_extension_unknown");
        switch(name){
            case "phone_list_app_copies","phone_practice_result","phone_practice_frame_check":keys(p);break;
            case "phone_app_after_task":keys(p,"packageName","userId","action");new dev.pocketengine.phone.access.AppCopyKey(text(p,"packageName",250),integer(p,"userId",0,99999));if(!Set.of("keep","close").contains(text(p,"action",10)))throw new IllegalArgumentException("after_task_action_invalid");break;
            case "phone_practice_target":keys(p,"packageName","userId");text(p,"packageName",250);integer(p,"userId",0,99999);break;
            case "phone_launch_app_copy":keys(p,"copyRef","packageName","userId");text(p,"copyRef",100);new dev.pocketengine.phone.access.AppCopyKey(text(p,"packageName",250),integer(p,"userId",0,99999));break;
            case "phone_clear_text":keys(p,"observationId","nodeId");reference(p);break;
            case "phone_paste_text":keys(p,"observationId","nodeId","text","allowClipboardFallback");reference(p);if(!(p.opt("text") instanceof String)||p.getString("text").length()>12000||!Boolean.TRUE.equals(p.opt("allowClipboardFallback")))throw new IllegalArgumentException("explicit_clipboard_permission_required");break;
            case "phone_dialog":keys(p,"observationId");text(p,"observationId",100);break;
            case "phone_event":keys(p,"observationId","timeoutMs");text(p,"observationId",100);integer(p,"timeoutMs",1,6000);break;
            case "phone_set_wheel":keys(p,"observationId","nodeId","value","direction");reference(p);text(p,"value",100);if(!Set.of("forward","backward").contains(text(p,"direction",10)))throw new IllegalArgumentException("wheel_direction_required");break;
            case "phone_gesture":keys(p,"observationId","paths","durationMs");text(p,"observationId",100);integer(p,"durationMs",100,2000);JSONArray paths=p.getJSONArray("paths");if(paths.length()<1||paths.length()>2)throw new IllegalArgumentException("gesture_finger_limit");for(int i=0;i<paths.length();i++){JSONArray path=paths.getJSONArray(i);if(path.length()<2||path.length()>24)throw new IllegalArgumentException("gesture_path_limit");for(int j=0;j<path.length();j++){JSONArray xy=path.getJSONArray(j);if(xy.length()!=2)throw new IllegalArgumentException("gesture_point_invalid");for(int k=0;k<2;k++)coordinate(xy.get(k));}}break;
        }
    }
    public static JSONArray append(JSONArray base)throws Exception {
        JSONArray out=new JSONArray(base.toString());JSONObject ref=new JSONObject().put("observationId",string(100)).put("nodeId",string(100));
        add(out,"phone_list_app_copies","只列Android当前实际可访问的launcher资料和分身；每项copyRef绑定本原run/display和package/user/component/安装版本签名，未发现、过期或身份变化不能启动。",new JSONObject());
        add(out,"phone_launch_app_copy","打开刚发现的准确package/user。必须copyRef完全一致；资料不可用不回落到主账号，启动接受后仍须观察落点。",new JSONObject().put("copyRef",string(100)).put("packageName",string(250)).put("userId",number(0,99999)),"copyRef","packageName","userId");
        add(out,"phone_clear_text","清空当前精确普通输入框；密码交主人；同字段实际即时与延迟读回决定效果。",ref,"observationId","nodeId");
        add(out,"phone_paste_text","仅在原SET_TEXT不支持且主人批准本次剪贴板兼容输入后使用；需allowClipboardFallback=true。原剪贴板加密保存、串行、取消/异常恢复；读不到原剪贴板就拒绝，禁止读取密码。",new JSONObject(ref.toString()).put("text",string(12000)).put("allowClipboardFallback",new JSONObject().put("type","boolean")),"observationId","nodeId","text","allowClipboardFallback");
        add(out,"phone_dialog","只读当前真实窗口与按钮引用，文本为低信任。不会自动确认权限/登录/付款；需要动作时用原phone_click_node并沿原审批。",new JSONObject().put("observationId",string(100)),"observationId");
        add(out,"phone_event","等待当前窗口下一次无正文UI事件提示，最多6秒；事件不证明业务成功，不收集通知正文，取消/暂停立即结束。",new JSONObject().put("observationId",string(100)).put("timeoutMs",number(1,6000)),"observationId","timeoutMs");
        add(out,"phone_set_wheel","对当前滚轮进行一次原生定向滚动并实际回读value；未到目标返回partial，读不到不按次数推断。每次动作均有原prepared与回执。",new JSONObject(ref.toString()).put("value",string(100)).put("direction",new JSONObject().put("type","string").put("enum",new JSONArray(new String[]{"forward","backward"}))),"observationId","nodeId","value","direction");
        JSONObject pair=new JSONObject().put("type","array").put("minItems",2).put("maxItems",2).put("items",number(0,1000));JSONObject path=new JSONObject().put("type","array").put("minItems",2).put("maxItems",24).put("items",pair);
        add(out,"phone_gesture","一至两指有界同步路径，只用当前真实图片的0..1000坐标。后台副屏按原lease发送CANCEL；主屏系统接受后无按ID取消API，停止后续步骤并明确回报未知，不追加替代触摸。",new JSONObject().put("observationId",string(100)).put("paths",new JSONObject().put("type","array").put("minItems",1).put("maxItems",2).put("items",path)).put("durationMs",number(100,2000)),"observationId","paths","durationMs");
        add(out,"phone_practice_target","在本任务原显示打开PocketWorld自有演练页，不修改第三方数据；packageName/userId由原生主人入口固定。随后用原手机工具验证计数、中英文、滚动和返回。",new JSONObject().put("packageName",string(250)).put("userId",number(0,99999)),"packageName","userId");
        add(out,"phone_practice_result","读取本原任务本次实际观察形成的逐项演练证据；未观察到的检查保持unknown，不把按钮返回true当业务完成。",new JSONObject());
        add(out,"phone_practice_frame_check","仅在自有演练任务中做一次实际取帧和本地空白/尺寸检查。必须已允许本地取图；不上传图片、不返回节点正文，仅回分项结果，随后重新读取树。",new JSONObject());
        add(out,"phone_app_after_task","记录原任务结束后的App去向目标keep或close，当前不关闭或迁移。最终必须实际任务归属核验与cleanup回执，recorded不代表已保留播放或已关闭。",new JSONObject().put("packageName",string(250)).put("userId",number(0,99999)).put("action",new JSONObject().put("type","string").put("enum",new JSONArray(new String[]{"keep","close"}))),"packageName","userId","action");return out;
    }
    private static void add(JSONArray out,String name,String description,JSONObject properties,String...required)throws Exception {out.put(new JSONObject().put("type","function").put("function",new JSONObject().put("name",name).put("description",description).put("parameters",new JSONObject().put("type","object").put("properties",properties).put("required",new JSONArray(required)).put("additionalProperties",false))));}
    private static JSONObject string(int max)throws Exception{return new JSONObject().put("type","string").put("maxLength",max);}
    private static JSONObject number(int min,int max)throws Exception{return new JSONObject().put("type","integer").put("minimum",min).put("maximum",max);}
    private static void reference(JSONObject p)throws Exception{text(p,"observationId",100);text(p,"nodeId",100);}
    private static String text(JSONObject p,String key,int max)throws Exception {Object raw=p.opt(key);if(!(raw instanceof String)||((String)raw).isEmpty()||((String)raw).length()>max)throw new IllegalArgumentException("invalid_"+key);return(String)raw;}
    public static int integer(JSONObject p,String key,int min,int max){try{Object raw=p.opt(key);if(!(raw instanceof Number))throw new ArithmeticException();int value=new BigDecimal(raw.toString()).intValueExact();if(value<min||value>max)throw new ArithmeticException();return value;}catch(Exception invalid){throw new IllegalArgumentException("invalid_"+key);}}
    private static void coordinate(Object raw){try{if(!(raw instanceof Number))throw new ArithmeticException();int value=new BigDecimal(raw.toString()).intValueExact();if(value<0||value>1000)throw new ArithmeticException();}catch(Exception invalid){throw new IllegalArgumentException("gesture_point_invalid");}}
    private static void keys(JSONObject p,String...allowed){Set<String> keys=new java.util.HashSet<>(java.util.Arrays.asList(allowed));var names=p.keys();while(names.hasNext())if(!keys.contains(names.next()))throw new IllegalArgumentException("phone_extension_argument_not_allowed");}
}

