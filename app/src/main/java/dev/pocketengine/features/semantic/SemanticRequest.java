package dev.pocketengine.features.semantic;

import java.net.URI;
import java.net.URLEncoder;
import java.math.BigDecimal;
import java.time.ZoneId;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Typed public Android entry points. There is deliberately no raw Intent or arbitrary extras API. */
public final class SemanticRequest {
    static final java.util.List<String> WEEKDAYS=java.util.List.of("SUN","MON","TUE","WED","THU","FRI","SAT");
    private static final long DAY_MS=86_400_000L;
    public final String kind,action,data,host;
    private final JSONObject parameters;

    private SemanticRequest(String kind,String action,String data,String host,JSONObject parameters) throws Exception {
        this.kind=kind;this.action=action;this.data=data;this.host=host;this.parameters=SemanticJson.copy(parameters);
    }
    public static SemanticRequest parse(JSONObject input) throws Exception {
        JSONObject p=SemanticJson.copy(input==null?new JSONObject():input);
        String kind=SemanticJson.text(p,"kind",32,false),action,data,host="";
        switch(kind) {
            case "web": {
                SemanticJson.keys(p,Set.of("kind","url"));String url=SemanticJson.text(p,"url",2048,false);
                URI uri=new URI(url);
                if(!"https".equals(uri.getScheme())||uri.getHost()==null||uri.getRawUserInfo()!=null||uri.getPort()<-1||uri.getPort()>65535
                        ||url.indexOf('\\')>=0||url.matches("(?s).*[\\p{Cntrl}\\s].*"))
                    throw new IllegalArgumentException("网页入口需要不含账号密码的完整HTTPS地址");
                action="android.intent.action.VIEW";data=uri.toASCIIString();host=uri.getHost().toLowerCase(java.util.Locale.ROOT);p.put("url",data);break;
            }
            case "app_link": {
                SemanticJson.keys(p,Set.of("kind","url","packageName"));checkedPackage(p);
                // Initial adapter deliberately reuses the original HTTPS contract. Custom
                // schemes cannot bypass dial/USSD/mail/raw-Intent or browser URL admission.
                SemanticRequest link=parse(new JSONObject().put("kind","web").put("url",p.opt("url")));
                action=link.action;data=link.data;host=link.host;p.put("url",data);break;
            }
            case "share_text": {
                SemanticJson.keys(p,Set.of("kind","packageName","text"));checkedPackage(p);SemanticJson.text(p,"text",10000,false);
                action="android.intent.action.SEND";data="";break;
            }
            case "show_alarms":
                SemanticJson.keys(p,Set.of("kind"));action="android.intent.action.SHOW_ALARMS";data="";break;
            case "map_search":
                SemanticJson.keys(p,Set.of("kind","query"));String query=SemanticJson.text(p,"query",500,false);
                action="android.intent.action.VIEW";data="geo:0,0?q="+encode(query);break;
            case "dial":
                SemanticJson.keys(p,Set.of("kind","number"));String number=SemanticJson.text(p,"number",32,false);
                // No USSD/MMI, pause/wait, extension, fragment or automatic CALL entry point.
                if(!number.matches("\\+?[0-9][0-9 ()-]{0,30}"))throw new IllegalArgumentException("拨号入口仅接受普通电话号码");
                number=number.replace(" ","").replace("(","").replace(")","").replace("-","");
                if(!number.matches("\\+?[0-9]{1,20}"))throw new IllegalArgumentException("电话号码长度无效");
                p.put("number",number);action="android.intent.action.DIAL";data="tel:"+number;break;
            case "email_draft":
                SemanticJson.keys(p,Set.of("kind","to","subject","body"));String to=SemanticJson.text(p,"to",254,false);
                if(!to.matches("[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?\\.[A-Za-z]{2,63}"))
                    throw new IllegalArgumentException("邮件草稿入口需要一个普通邮件地址");
                if(!p.has("subject"))p.put("subject","");if(!p.has("body"))p.put("body","");
                if(SemanticJson.text(p,"subject",200,true).matches("(?s).*[\\p{Cntrl}].*"))throw new IllegalArgumentException("邮件主题不能包含控制字符");
                SemanticJson.text(p,"body",4000,true);
                action="android.intent.action.SENDTO";data="mailto:"+encode(to).replace("%40","@");break;
            case "calendar_draft": {
                SemanticJson.keys(p,Set.of("kind","title","start","end","timeZone","allDay","location","description"));
                String title=SemanticJson.text(p,"title",200,false),zone=SemanticJson.text(p,"timeZone",80,false);
                if(title.matches("(?s).*[\\p{Cntrl}].*")||!ZoneId.getAvailableZoneIds().contains(zone))
                    throw new IllegalArgumentException("日历草稿需要单行标题和明确的IANA时区，例如Asia/Shanghai或UTC");
                long start=calendarTime(p,"start"),end=calendarTime(p,"end");
                if(end<=start)throw new IllegalArgumentException("日历草稿结束时间必须晚于开始时间");
                boolean allDay=booleanValue(p,"allDay",false);
                if(allDay&&(!"UTC".equals(zone)||start%DAY_MS!=0||end%DAY_MS!=0))
                    throw new IllegalArgumentException("全天日历须使用UTC时区与UTC午夜毫秒；start包含开始日期，end是不包含的结束日期");
                if(!p.has("location"))p.put("location","");if(!p.has("description"))p.put("description","");
                if(SemanticJson.text(p,"location",500,true).matches("(?s).*[\\p{Cntrl}].*"))
                    throw new IllegalArgumentException("日历地点需要单行文字");
                SemanticJson.text(p,"description",4000,true);p.put("start",start).put("end",end).put("allDay",allDay);
                // Fixed public CalendarContract values. Android construction uses the public
                // constants; caller-supplied content URIs, calendar IDs and extras are forbidden.
                action="android.intent.action.INSERT";data="content://com.android.calendar/events";break;
            }
            case "alarm":
                SemanticJson.keys(p,Set.of("kind","hour","minutes","message","days","vibrate"));
                p.put("hour",clockInteger(p,"hour",0,23)).put("minutes",clockInteger(p,"minutes",0,59));
                p.put("days",alarmDays(p));if(p.has("vibrate"))p.put("vibrate",booleanValue(p,"vibrate",false));
                clockMessage(p);action="android.intent.action.SET_ALARM";data="";break;
            case "timer":
                SemanticJson.keys(p,Set.of("kind","seconds","message"));
                p.put("seconds",clockInteger(p,"seconds",1,86400));
                clockMessage(p);action="android.intent.action.SET_TIMER";data="";break;
            default:throw new IllegalArgumentException("此语义入口尚无可信适配器，请用现有phone/DOM能力");
        }
        return new SemanticRequest(kind,action,data,host,p);
    }
    private static String checkedPackage(JSONObject p)throws Exception {
        String pkg=SemanticJson.text(p,"packageName",250,false);
        if(!pkg.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+"))throw new IllegalArgumentException("入口需要准确应用包名");return pkg;
    }
    private static String encode(String value) throws java.io.UnsupportedEncodingException { return URLEncoder.encode(value,"UTF-8").replace("+","%20"); }
    private static boolean booleanValue(JSONObject p,String key,boolean fallback) {
        if(!p.has(key))return fallback;Object value=p.opt(key);
        if(!(value instanceof Boolean))throw new IllegalArgumentException("语义参数需要布尔值："+key);
        return (Boolean)value;
    }
    private static JSONArray alarmDays(JSONObject p)throws Exception {
        if(!p.has("days"))return new JSONArray();Object raw=p.opt("days");
        if(!(raw instanceof JSONArray)||((JSONArray)raw).length()>7)throw new IllegalArgumentException("闹钟重复星期需要至多7项SUN至SAT字符串");
        JSONArray supplied=(JSONArray)raw;Set<String> selected=new java.util.HashSet<>();
        for(int i=0;i<supplied.length();i++){
            Object value=supplied.get(i);if(!(value instanceof String)||((String)value).length()>16)throw new IllegalArgumentException("闹钟重复星期只能使用SUN至SAT字符串");
            String day=((String)value).trim().toUpperCase(java.util.Locale.ROOT);
            if(!WEEKDAYS.contains(day))throw new IllegalArgumentException("未知闹钟重复星期："+day);
            selected.add(day);
        }
        JSONArray normalized=new JSONArray();for(String day:WEEKDAYS)if(selected.contains(day))normalized.put(day);return normalized;
    }
    private static int clockInteger(JSONObject p,String key,int min,int max) {
        Object value=p.opt(key);if(!(value instanceof Number))throw new IllegalArgumentException("时钟参数需要范围内整数："+key);
        try{int n=new BigDecimal(value.toString()).intValueExact();if(n<min||n>max)throw new ArithmeticException("outside clock range");return n;}
        catch(NumberFormatException|ArithmeticException invalid){throw new IllegalArgumentException("时钟参数需要范围内整数："+key);}
    }
    private static void clockMessage(JSONObject p)throws Exception {
        if(!p.has("message"))p.put("message","");if(SemanticJson.text(p,"message",120,true).matches("(?s).*[\\p{Cntrl}].*"))throw new IllegalArgumentException("时钟提示需要不超过120字的单行文字");
    }
    public boolean clock(){return kind.equals("alarm")||kind.equals("timer");}
    private static long calendarTime(JSONObject p,String key) {
        Object value=p.opt(key);
        if(!(value instanceof Number))throw new IllegalArgumentException("日历时间需为UTC毫秒整数："+key);
        try {
            long millis=new BigDecimal(value.toString()).longValueExact();
            if(millis<0||millis>253402300799999L)throw new ArithmeticException("outside supported calendar range");
            return millis;
        } catch(NumberFormatException|ArithmeticException invalid) {throw new IllegalArgumentException("日历时间超出1970至9999年UTC范围或不是整数："+key);}
    }
    public JSONObject parameters() throws Exception { return SemanticJson.copy(parameters); }
    public String digest() throws Exception { return SemanticJson.hash(parameters); }
    public String followup() {
        return switch(kind) { case "web"->"phone_observe_or_authorized_browser_dom";case "app_link","share_text"->"owner_verifies_target_app_no_automatic_continuation";case "dial","email_draft","calendar_draft"->"owner_completes_in_target_app";case "alarm","timer"->"owner_verifies_clock_in_target_app";default->"phone_observe"; };
    }
    static JSONObject discoverySchema() throws Exception {
        JSONObject p=new JSONObject().put("kind",new JSONObject().put("type","string").put("enum",new JSONArray(new String[]{"web","app_link","share_text","show_alarms","map_search","dial","email_draft","calendar_draft","alarm","timer"})));
        p.put("packageName",new JSONObject().put("type","string").put("maxLength",250)).put("text",new JSONObject().put("type","string").put("maxLength",10000));
        for(String key:new String[]{"url","query","number","to","subject","body"})
            p.put(key,new JSONObject().put("type","string").put("maxLength",key.equals("body")?4000:key.equals("url")?2048:key.equals("query")?500:key.equals("number")?32:key.equals("to")?254:200));
        for(String key:new String[]{"title","timeZone","location","description"})
            p.put(key,new JSONObject().put("type","string").put("maxLength",key.equals("description")?4000:key.equals("location")?500:key.equals("timeZone")?80:200));
        p.getJSONObject("timeZone").put("description","calendar_draft的明确IANA时区；allDay=true时必须为UTC。");
        for(String key:new String[]{"start","end"})p.put(key,new JSONObject().put("type","integer").put("minimum",0).put("maximum",253402300799999L)
                .put("description",key.equals("start")?"calendar_draft开始UTC毫秒；全天须为开始日期的UTC午夜，包含此日期。":"calendar_draft结束UTC毫秒；全天须为结束日期的UTC午夜，不包含此日期。"));
        p.put("allDay",new JSONObject().put("type","boolean").put("description","仅calendar_draft；默认false。true要求start/end均为UTC午夜且timeZone=UTC；一天日程的end是次日午夜。"));
        p.put("hour",new JSONObject().put("type","integer").put("minimum",0).put("maximum",23))
                .put("minutes",new JSONObject().put("type","integer").put("minimum",0).put("maximum",59))
                .put("seconds",new JSONObject().put("type","integer").put("minimum",1).put("maximum",86400))
                .put("message",new JSONObject().put("type","string").put("maxLength",120))
                .put("days",new JSONObject().put("type","array").put("maxItems",7).put("items",new JSONObject().put("type","string").put("enum",new JSONArray(WEEKDAYS)))
                        .put("description","仅alarm；重复星期SUN、MON、TUE、WED、THU、FRI、SAT。省略或空数组为一次性；输入会去重并按SUN至SAT排序后审批。"))
                .put("vibrate",new JSONObject().put("type","boolean").put("description","仅alarm；明确请求开启或关闭震动，省略则不指定。实际设置仍须在目标App核验。"));
        return new JSONObject().put("type","object").put("properties",p).put("required",new JSONArray(new String[]{"kind"})).put("additionalProperties",false);
    }
}
