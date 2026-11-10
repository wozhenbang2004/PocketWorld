package dev.pocketengine.features.notifications;

import java.util.Locale;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** A bounded literal predicate, not a notification command or an executable expression. */
final class NotificationWaitRequest {
    static final int MAX_TIMEOUT_MS=60_000;
    final String packageName,titleContains,textContains,channelId;
    final boolean caseSensitive;
    final int timeoutMs;

    private NotificationWaitRequest(String packageName,String title,String text,String channel,boolean sensitive,int timeout){
        this.packageName=packageName;titleContains=title;textContains=text;channelId=channel;caseSensitive=sensitive;timeoutMs=timeout;
    }
    static NotificationWaitRequest parse(JSONObject args)throws Exception {
        if(args==null)throw new IllegalArgumentException("通知等待参数不能为空");
        Set<String> allowed=Set.of("packageName","titleContains","textContains","channelId","caseSensitive","timeoutMs");
        for(var keys=args.keys();keys.hasNext();)if(!allowed.contains(keys.next()))throw new IllegalArgumentException("通知等待包含未知参数");
        String pkg=text(args,"packageName",200,true);
        NotificationInbox.validatePackages(Set.of(pkg));
        String title=text(args,"titleContains",256,false),body=text(args,"textContains",1024,false),channel=text(args,"channelId",256,false);
        if(title.isBlank()&&body.isBlank())throw new IllegalArgumentException("请指定非空的标题或正文文字条件，不能读取任意下一条通知");
        Object rawSensitive=args.opt("caseSensitive");
        if(rawSensitive!=null&&!(rawSensitive instanceof Boolean))throw new IllegalArgumentException("caseSensitive 必须是布尔值");
        int timeout=30_000;
        if(args.has("timeoutMs")){
            Object raw=args.opt("timeoutMs");
            if(!(raw instanceof Number))throw new IllegalArgumentException("timeoutMs 必须是整数");
            double value=((Number)raw).doubleValue();
            if(!Double.isFinite(value)||value!=Math.rint(value)||value<1||value>MAX_TIMEOUT_MS)throw new IllegalArgumentException("通知等待限 1 至 60000 毫秒");
            timeout=(int)value;
        }
        return new NotificationWaitRequest(pkg,title,body,channel,Boolean.TRUE.equals(rawSensitive),timeout);
    }
    private static String text(JSONObject args,String key,int maximum,boolean required)throws Exception {
        if(!args.has(key)){if(required)throw new IllegalArgumentException("缺少 "+key);return "";}
        Object raw=args.get(key);
        if(!(raw instanceof String))throw new IllegalArgumentException(key+" 必须是文字");
        String value=(String)raw;
        if(value.length()>maximum||required&&value.isBlank()||value.indexOf('\0')>=0)throw new IllegalArgumentException(key+" 为空或超过限长");
        return value;
    }
    boolean matches(NotificationInbox.Notice notice){
        return notice.triggerCandidate&&packageName.equals(notice.packageName)
                &&(channelId.isEmpty()||!notice.content.channelTruncated&&channelId.equals(notice.content.channel))
                &&contains(notice.content.title,titleContains)&&contains(notice.content.text,textContains);
    }
    private boolean contains(String value,String wanted){
        return wanted.isEmpty()||(caseSensitive?value.contains(wanted):value.toLowerCase(Locale.ROOT).contains(wanted.toLowerCase(Locale.ROOT)));
    }
    JSONObject parameters()throws Exception {
        return new JSONObject().put("packageName",packageName).put("titleContains",titleContains).put("textContains",textContains)
                .put("channelId",channelId).put("caseSensitive",caseSensitive).put("timeoutMs",timeoutMs);
    }
    static JSONObject schema()throws Exception {
        return new JSONObject().put("type","object").put("properties",new JSONObject()
                .put("packageName",new JSONObject().put("type","string").put("minLength",1).put("maxLength",200))
                .put("titleContains",new JSONObject().put("type","string").put("maxLength",256))
                .put("textContains",new JSONObject().put("type","string").put("maxLength",1024))
                .put("channelId",new JSONObject().put("type","string").put("maxLength",256))
                .put("caseSensitive",new JSONObject().put("type","boolean"))
                .put("timeoutMs",new JSONObject().put("type","integer").put("minimum",1).put("maximum",MAX_TIMEOUT_MS)))
                .put("required",new JSONArray(new String[]{"packageName"})).put("additionalProperties",false);
    }
}
