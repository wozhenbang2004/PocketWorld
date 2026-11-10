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
            if("calendar_batch".equals(request.getString("kind")))return calendarBatch(capability,request,identity);
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
                    if(booleanValue(request,"allDay",false)){
                        text.append("全天：是\n开始日期（含）：").append(Instant.ofEpochMilli(start).atZone(ZoneId.of("UTC")).toLocalDate())
                                .append("\n结束日期（不含）：").append(Instant.ofEpochMilli(end).atZone(ZoneId.of("UTC")).toLocalDate())
                                .append("\n日期按UTC午夜传递，结束日期不计入此日程。\n");
                    }else text.append("全天：否\n开始 UTC：").append(Instant.ofEpochMilli(start)).append("\n结束 UTC：").append(Instant.ofEpochMilli(end))
                                .append("\n请求时区：").append(zone)
                                .append("\n请求显示开始：").append(DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(Instant.ofEpochMilli(start).atZone(ZoneId.of(zone))))
                                .append("\n请求显示结束：").append(DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(Instant.ofEpochMilli(end).atZone(ZoneId.of(zone)))).append('\n');
                    field(text,request,"location","地点");field(text,request,"description","描述");
                    text.append("打开日历编辑器；第三方可能自动保存或创建事件，退出不保证取消。实际显示时区可能不同，请核对目标、时间和保存结果。\n");break;
                case "alarm":
                    text.append("闹钟时间：").append(String.format(java.util.Locale.ROOT,"%02d:%02d",request.getInt("hour"),request.getInt("minutes"))).append("（设备时区：").append(identity.getString("deviceTimeZone")).append("）\n");
                    JSONArray days=request.has("days")?request.getJSONArray("days"):new JSONArray();text.append("重复星期：");
                    if(days.length()==0)text.append("不重复（一次性）");
                    for(int i=0;i<days.length();i++){
                        if(i>0)text.append("、");String day=days.getString(i);
                        String label=switch(day){case "SUN"->"周日";case "MON"->"周一";case "TUE"->"周二";case "WED"->"周三";case "THU"->"周四";case "FRI"->"周五";case "SAT"->"周六";default->throw new IllegalArgumentException("未知星期");};
                        text.append(label).append("（").append(day).append('）');
                    }
                    text.append("\n震动：").append(request.has("vibrate")?booleanValue(request,"vibrate",false)?"开启":"关闭":"未指定，使用接收App默认行为").append('\n');
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
    private static String calendarBatch(JSONObject capability,JSONObject request,JSONObject identity)throws Exception {
        SemanticRequest checked=SemanticRequest.parse(request);
        if(!checked.digest().equals(capability.getString("requestDigest")))throw new IllegalArgumentException("日历审批参数已变化");
        JSONObject calendar=identity.getJSONObject("calendar");JSONArray events=request.getJSONArray("events");
        if(calendar.getLong("calendarId")!=request.getLong("calendarId")||events.length()!=capability.getInt("eventCount"))
            throw new IllegalArgumentException("日历审批目标或批次不完整");
        StringBuilder text=new StringBuilder("创建日历事件 · 本批共 ").append(events.length()).append(" 项\n")
                .append("位置：PocketWorld 当前所在的 Android 资料，不使用后台屏所显示的其他资料。\n")
                .append("Android 资料：").append(identity.getInt("userId")).append(" / 序列 ").append(identity.getLong("profileSerial"))
                .append("\n日历 ID：").append(calendar.getLong("calendarId"))
                .append("\n日历名称：").append(quoted(calendar,"displayName"))
                .append("\n日历内部名称：").append(quoted(calendar,"name"))
                .append("\n账户：").append(quoted(calendar,"accountName")).append(" / ").append(quoted(calendar,"accountType"))
                .append("\n所有者：").append(quoted(calendar,"ownerAccount"))
                .append("\n日历时区：").append(quoted(calendar,"timeZone"))
                .append("\n日历可见性：").append(calendar.getLong("visible")==1?"当前显示":"当前隐藏，创建后可能需在日历App中开启显示")
                .append("\n系统日历服务：").append(identity.getString("packageName")).append(" / 版本 ").append(identity.getLong("versionCode"));
        JSONArray certificates=identity.getJSONArray("certificates");
        for(int i=0;i<certificates.length();i++)text.append("\n服务签名 SHA-256：").append(certificates.getString(i));
        text.append("\n日历名称和账户是外部数据。批准后会直接创建以下事件，可能由系统账户同步到其他设备；不会再打开编辑器逐项确认。\n");
        for(int i=0;i<events.length();i++){
            JSONObject event=events.getJSONObject(i);text.append("\n第 ").append(i+1).append(" / ").append(events.length()).append(" 项\n");
            field(text,event,"title","标题");String zone=event.getString("timeZone");
            long start=event.getLong("start"),end=event.getLong("end");
            if(event.getBoolean("allDay"))text.append("全天：是\n开始日期（含）：").append(Instant.ofEpochMilli(start).atZone(ZoneId.of("UTC")).toLocalDate())
                    .append("\n结束日期（不含）：").append(Instant.ofEpochMilli(end).atZone(ZoneId.of("UTC")).toLocalDate()).append("\n时区：UTC；结束日期不计入。\n");
            else text.append("全天：否\n时区：").append(zone).append("\n开始：")
                    .append(DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(Instant.ofEpochMilli(start).atZone(ZoneId.of(zone))))
                    .append("\n结束：").append(DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(Instant.ofEpochMilli(end).atZone(ZoneId.of(zone))))
                    .append("\n开始 UTC：").append(Instant.ofEpochMilli(start)).append("\n结束 UTC：").append(Instant.ofEpochMilli(end)).append('\n');
            field(text,event,"location","地点");field(text,event,"description","描述");text.append("重复：单次事件\n");
        }
        return text.append("\n仅批准这个固定日历与上述完整批次的一次写入。将逐项保留系统返回的事件URI并读回核对；读回不代表云同步已完成。写入结果未知时停止并保留原操作，不会自动重发、改写其他日历或删除补偿。").toString();
    }
    private static String quoted(JSONObject object,String key)throws Exception{return object.isNull(key)?"未提供":JSONObject.quote(object.getString(key));}
    private static boolean booleanValue(JSONObject request,String key,boolean fallback)throws Exception {
        if(!request.has(key))return fallback;Object value=request.get(key);
        if(!(value instanceof Boolean))throw new IllegalArgumentException("入口布尔参数无效");return(Boolean)value;
    }
    private static void clockWarning(StringBuilder text){text.append("系统时钟请求可能立即创建或开始计时；会请求显示界面，但不保证接收App另有确认或退出可撤销。请仅在同意这些参数时批准，并在时钟App核对实际结果。\n");}
}
