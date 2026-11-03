package dev.pocketengine.features.semantic;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import org.json.JSONArray;
import org.json.JSONObject;

/** Read-only native approval presentation. Text is not a grant or a dispatch entry. */
public final class SemanticApprovalText {
    private SemanticApprovalText() { }
    public static String describe(JSONObject capability) {
        try {
            JSONObject request=capability.getJSONObject("request"),target=capability.getJSONObject("target"),identity=capability.getJSONObject("targetIdentity");
            StringBuilder text=new StringBuilder("打开已选择的应用入口\n应用：").append(target.getString("label"))
                    .append("\n包名：").append(target.getString("packageName"))
                    .append("\n入口：").append(target.getString("component"))
                    .append("\n版本：").append(identity.getLong("versionCode"));
            JSONArray certificates=identity.getJSONArray("certificates");
            for(int i=0;i<certificates.length();i++)text.append("\n签名 SHA-256：").append(certificates.getString(i));
            JSONObject resource=capability.optJSONObject("resource");
            if(resource!=null&&"background".equals(resource.optString("executionMode")))
                text.append("\n位置：原任务后台屏 ").append(resource.optInt("displayId",-1)).append("，不回落到主屏。\n");
            else text.append("\n位置：手机主屏，执行时会切换应用并占用屏幕。\n");
            if(identity.has("userId"))text.append("Android 资料：").append(identity.getInt("userId")).append(" / 序列 ").append(identity.getLong("profileSerial")).append('\n');
            switch(request.getString("kind")) {
                case "show_alarms":text.append("打开当前资料中的闹钟列表；不会据此宣称创建或更改闹钟。\n");break;
                case "web":field(text,request,"url","网址");break;
                case "app_link":
                    field(text,request,"packageName","准确目标包名");field(text,request,"url","完整 HTTPS 链接");
                    text.append("链接会交给这个准确App；HTTPS或App Links不保证业务只读，接收方可能立即触发操作。退出不保证撤销；启动后交主人核验，不会自动继续点击。\n");break;
                case "share_text":
                    field(text,request,"packageName","准确目标包名");field(text,request,"text","交给目标App的完整纯文本");
                    text.append("这会将上述文本交给选定App；接收方可能立即处理或转发，不保证只打开草稿。不会替你选择收件人或点击发送；启动后交主人核验，不据此宣称已发送。\n");break;
                case "map_search":field(text,request,"query","搜索词");break;
                case "dial":field(text,request,"number","拨号界面中的号码");break;
                case "email_draft":field(text,request,"to","收件人");field(text,request,"subject","主题");field(text,request,"body","正文");break;
                case "calendar_draft":
                    field(text,request,"title","日历标题");
                    long start=request.getLong("start"),end=request.getLong("end");String zone=request.getString("timeZone");
                    text.append("开始 UTC：").append(Instant.ofEpochMilli(start)).append("\n结束 UTC：").append(Instant.ofEpochMilli(end))
                            .append("\n请求时区：").append(zone)
                            .append("\n请求显示开始：").append(DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(Instant.ofEpochMilli(start).atZone(ZoneId.of(zone))))
                            .append("\n请求显示结束：").append(DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(Instant.ofEpochMilli(end).atZone(ZoneId.of(zone)))).append('\n');
                    field(text,request,"location","地点");field(text,request,"description","描述");
                    text.append("打开日历编辑器；第三方可能自动保存或创建事件，退出不保证取消。实际显示时区可能不同，请核对目标、时间和保存结果。\n");break;
                case "alarm":
                    text.append("闹钟时间：").append(String.format(java.util.Locale.ROOT,"%02d:%02d",request.getInt("hour"),request.getInt("minutes"))).append("（设备时区：").append(identity.getString("deviceTimeZone")).append("）\n");
                    field(text,request,"message","提示");clockWarning(text);break;
                case "timer":
                    text.append("计时长度：").append(request.getInt("seconds")).append("秒\n设备时区：").append(identity.getString("deviceTimeZone")).append('\n');
                    field(text,request,"message","提示");clockWarning(text);break;
                default:return "当前入口类型不可读取，请拒绝这次操作并重新发现。";
            }
            return text.append("本次授权只允许发送上述固定入口请求；随后仍需核验实际结果，启动回执不代表业务完成。").toString();
        }catch(Exception invalid){return "当前入口参数不可完整读取，请拒绝这次操作并重新发现；不要根据旧参数批准。";}
    }
    private static void field(StringBuilder text,JSONObject request,String key,String label)throws Exception {
        text.append(label).append('：').append(request.getString(key)).append('\n');
    }
    private static void clockWarning(StringBuilder text){text.append("系统时钟请求可能立即创建或开始计时；会请求显示界面，但不保证接收App另有确认或退出可撤销。请仅在同意这些参数时批准，并在时钟App核对实际结果。\n");}
}
