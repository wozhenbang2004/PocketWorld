package dev.pocketengine.features.automation;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Iterator;
import org.json.JSONObject;

/** Pure policy arithmetic. It never grants a task, wakes a process or performs an effect. */
final class AutomationPolicy {
    static final long MAX_TIME=253402300799999L;
    private AutomationPolicy(){}

    static JSONObject rule(JSONObject input,String defaultZone)throws Exception {
        if(input==null)input=new JSONObject();
        for(Iterator<String> keys=input.keys();keys.hasNext();){String key=keys.next();
            if(!key.matches("zoneId|quietHours|expiresAt|maxLatenessMillis|maxOccurrences|requiresNetwork|requiresCharging|minBatteryPercent|misfire|overlap|maxCatchUp"))throw new IllegalArgumentException("不支持的规则策略："+key);
        }
        Object zoneValue=input.has("zoneId")?input.opt("zoneId"):defaultZone;
        if(!(zoneValue instanceof String))throw new IllegalArgumentException("规则时区无效");
        String zone=(String)zoneValue;ZoneId.of(zone);
        JSONObject value=new JSONObject().put("zoneId",zone).put("quietHours",quiet(input.optJSONObject("quietHours")));
        AutomationConditions.appendRequirements(value,input);
        String misfire=choice(input,"misfire","skip",java.util.Set.of("skip","coalesce","catch_up"));
        String overlap=choice(input,"overlap","buffer_one",java.util.Set.of("allow","skip","buffer_one"));
        value.put("misfire",misfire).put("overlap",overlap).put("maxCatchUp",input.has("maxCatchUp")?integer(input,"maxCatchUp",1,20):3);
        if(input.has("quietHours")&&!input.isNull("quietHours")&&input.optJSONObject("quietHours")==null)throw new IllegalArgumentException("安静时段格式无效");
        for(String key:new String[]{"expiresAt","maxLatenessMillis","maxOccurrences"}){
            if(!input.has(key)||input.isNull(key)){value.put(key,JSONObject.NULL);continue;}
            long number=integer(input,key,key.equals("maxOccurrences")?0:1,key.equals("maxOccurrences")?1_000_000:MAX_TIME);
            value.put(key,number);
        }
        return value;
    }
    static Object quiet(JSONObject hours)throws Exception {
        if(hours==null)return JSONObject.NULL;
        if(hours.length()!=2)throw new IllegalArgumentException("安静时段只接受start和end");
        String start=clock(hours,"start"),end=clock(hours,"end");
        if(start.equals(end))throw new IllegalArgumentException("安静时段起止不能相同");
        return new JSONObject().put("start",start).put("end",end);
    }
    static long quietEnd(JSONObject policy,long at)throws Exception {
        JSONObject hours=policy.optJSONObject("quietHours");if(hours==null)return at;
        ZoneId zone=ZoneId.of(policy.getString("zoneId"));LocalTime start=LocalTime.parse(hours.getString("start")),end=LocalTime.parse(hours.getString("end"));
        LocalDate today=Instant.ofEpochMilli(at).atZone(zone).toLocalDate();long result=at;
        for(int day=-1;day<=0;day++){
            LocalDate date=today.plusDays(day),endDate=start.isBefore(end)?date:date.plusDays(1);
            long from=date.atTime(start).atZone(zone).withEarlierOffsetAtOverlap().toInstant().toEpochMilli();
            long until=endDate.atTime(end).atZone(zone).withLaterOffsetAtOverlap().toInstant().toEpochMilli();
            if(at>=from&&at<until)result=Math.max(result,until);
        }
        return result;
    }
    static long deadline(JSONObject policy,long occurrence){
        long deadline=policy.isNull("expiresAt")?MAX_TIME:policy.optLong("expiresAt",MAX_TIME);
        if(!policy.isNull("maxLatenessMillis")){
            long lateness=policy.optLong("maxLatenessMillis",MAX_TIME);
            deadline=Math.min(deadline,occurrence>MAX_TIME-lateness?MAX_TIME:occurrence+lateness);
        }
        return deadline;
    }
    static long nextDay(JSONObject policy,long at)throws Exception {
        ZoneId zone=ZoneId.of(policy.getString("zoneId"));
        return Instant.ofEpochMilli(at).atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
    }
    static long dayStart(JSONObject policy,long at)throws Exception {
        ZoneId zone=ZoneId.of(policy.getString("zoneId"));
        return Instant.ofEpochMilli(at).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli();
    }
    static long integer(JSONObject value,String key,long min,long max){
        Object raw=value.opt(key);
        if(!(raw instanceof Number))throw new IllegalArgumentException(key+"必须为整数");
        Number number=(Number)raw;long result=number.longValue();
        if(result<min||result>max||number.doubleValue()!=result)throw new IllegalArgumentException(key+"超出有效范围");
        return result;
    }
    private static String choice(JSONObject value,String key,String fallback,java.util.Set<String> allowed){
        Object raw=value.has(key)?value.opt(key):fallback;if(!(raw instanceof String)||!allowed.contains(raw))throw new IllegalArgumentException(key+"策略无效");return (String)raw;
    }
    private static String clock(JSONObject value,String key)throws Exception {
        Object raw=value.opt(key);
        if(!(raw instanceof String)||!((String)raw).matches("(?:[01][0-9]|2[0-3]):[0-5][0-9]"))throw new IllegalArgumentException("安静时段使用HH:mm");
        return (String)raw;
    }
}
