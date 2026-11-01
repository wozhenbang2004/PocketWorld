package dev.pocketengine.features.automation;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.zone.ZoneOffsetTransition;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.json.JSONArray;
import org.json.JSONObject;

/** Calendar arithmetic shared by actual rule arming and a side-effect-free native preview. */
public final class AutomationSchedule {
    public static final int MAX_PREVIEW=20;
    private AutomationSchedule(){}

    public static JSONObject normalize(JSONObject input)throws Exception {
        if(input==null)throw new IllegalArgumentException("必须提供日历规则");
        String type=string(input,"type");
        if(type.equals("once"))return new JSONObject().put("type",type).put("at",AutomationPolicy.integer(input,"at",1,AutomationPolicy.MAX_TIME));
        if(!Set.of("daily","weekly","monthly").contains(type))throw new IllegalArgumentException("当前支持一次性、每日、每周或每月规则");
        String time=string(input,"time"),zone=string(input,"zone");
        if(!time.matches("(?:[01][0-9]|2[0-3]):[0-5][0-9]"))throw new IllegalArgumentException("重复时间使用HH:mm");
        try{ZoneId.of(zone);}catch(java.time.DateTimeException invalid){throw new IllegalArgumentException("重复规则时区无效");}
        String gap=option(input,"dstGap","shift_forward",Set.of("shift_forward","skip"));
        String overlap=option(input,"dstOverlap","earlier",Set.of("earlier","later"));
        JSONObject result=new JSONObject().put("type",type).put("time",time).put("zone",zone);
        // Existing daily rows keep their old shape and atZone gap/overlap defaults.
        if(input.has("dstGap")||!type.equals("daily"))result.put("dstGap",gap);
        if(input.has("dstOverlap")||!type.equals("daily"))result.put("dstOverlap",overlap);
        if(type.equals("weekly")){
            allowed(input,Set.of("type","time","zone","daysOfWeek","dstGap","dstOverlap"));
            JSONArray raw=input.optJSONArray("daysOfWeek");if(raw==null||raw.length()<1||raw.length()>7)throw new IllegalArgumentException("每周规则须选择1至7个星期，周一为1、周日为7");
            TreeSet<Integer> days=new TreeSet<>();for(int i=0;i<raw.length();i++){
                JSONObject value=new JSONObject().put("day",raw.get(i));days.add((int)AutomationPolicy.integer(value,"day",1,7));
            }
            result.put("daysOfWeek",new JSONArray(days));
        }else if(type.equals("monthly")){
            allowed(input,Set.of("type","time","zone","dayOfMonth","shortMonthPolicy","dstGap","dstOverlap"));
            result.put("dayOfMonth",AutomationPolicy.integer(input,"dayOfMonth",1,31))
                    .put("shortMonthPolicy",option(input,"shortMonthPolicy","skip",Set.of("skip","last_day")));
        }
        return result;
    }

    /** Strictly after the supplied epoch, or -1 when the supported calendar is exhausted. */
    public static long nextAt(JSONObject input,long after)throws Exception {
        Occurrence next=next(normalize(input),after);return next==null?-1:next.at;
    }

    /** Nominal calendar times only. Does not reserve, persist, evaluate run policy or authorize. */
    public static JSONObject preview(JSONObject input,long after,int limit)throws Exception {
        if(limit<1||limit>MAX_PREVIEW)throw new IllegalArgumentException("日历预览数量应为1至20");
        validAfter(after);JSONObject schedule=normalize(input);JSONArray times=new JSONArray();long cursor=after;
        for(int i=0;i<limit;i++){
            Occurrence occurrence=next(schedule,cursor);if(occurrence==null)break;times.put(occurrence.json());cursor=occurrence.at;
        }
        return new JSONObject().put("schedule",schedule).put("after",after).put("occurrences",times)
                .put("exhausted",next(schedule,cursor)==null).put("timing","inexact_best_effort")
                .put("calendarOnly",true).put("policyEvaluated",false).put("executionAuthorized",false);
    }

    private static Occurrence next(JSONObject schedule,long after)throws Exception {
        validAfter(after);String type=schedule.getString("type");
        if(type.equals("once")){
            long at=schedule.getLong("at");if(at<=after)return null;ZonedDateTime utc=Instant.ofEpochMilli(at).atZone(ZoneOffset.UTC);
            return new Occurrence(at,utc.toLocalDateTime(),utc,"absolute");
        }
        ZoneId zone=ZoneId.of(schedule.getString("zone"));LocalDate start=Instant.ofEpochMilli(after).atZone(zone).toLocalDate();LocalTime time=LocalTime.parse(schedule.getString("time"));
        // All supported recurrences have a matching date within this fixed bound, even if a
        // date disappears at a zone transition. No unbounded cron-expression search is used.
        for(int day=0;day<=370;day++){
            LocalDate date=start.plusDays(day);if(!matches(schedule,date))continue;
            Occurrence occurrence=resolve(date.atTime(time),zone,schedule);
            if(occurrence!=null&&occurrence.at>after&&occurrence.at<=AutomationPolicy.MAX_TIME)return occurrence;
        }
        return null;
    }
    private static boolean matches(JSONObject schedule,LocalDate date)throws Exception {
        String type=schedule.getString("type");if(type.equals("daily"))return true;
        if(type.equals("weekly")){
            JSONArray days=schedule.getJSONArray("daysOfWeek");for(int i=0;i<days.length();i++)if(days.getInt(i)==date.getDayOfWeek().getValue())return true;return false;
        }
        int wanted=schedule.getInt("dayOfMonth");
        if(schedule.getString("shortMonthPolicy").equals("last_day"))wanted=Math.min(wanted,date.lengthOfMonth());
        return date.getDayOfMonth()==wanted;
    }
    private static Occurrence resolve(LocalDateTime requested,ZoneId zone,JSONObject schedule)throws Exception {
        List<ZoneOffset> offsets=zone.getRules().getValidOffsets(requested);ZonedDateTime resolved;String resolution="normal";
        if(offsets.isEmpty()){
            if(schedule.optString("dstGap","shift_forward").equals("skip"))return null;
            ZoneOffsetTransition transition=zone.getRules().getTransition(requested);if(transition==null)return null;
            LocalDateTime shifted=requested.plusSeconds(transition.getDuration().getSeconds());
            resolved=ZonedDateTime.ofLocal(shifted,zone,transition.getOffsetAfter());resolution="gap_shifted_forward";
        }else{
            boolean later=schedule.optString("dstOverlap","earlier").equals("later");ZoneOffset offset=offsets.get(later?offsets.size()-1:0);
            resolved=ZonedDateTime.ofLocal(requested,zone,offset);if(offsets.size()>1)resolution=later?"overlap_later":"overlap_earlier";
        }
        return new Occurrence(resolved.toInstant().toEpochMilli(),requested,resolved,resolution);
    }
    private static void validAfter(long value){if(value<0||value>AutomationPolicy.MAX_TIME)throw new IllegalArgumentException("日历参考时间超出有效范围");}
    private static String string(JSONObject input,String key){Object value=input.opt(key);if(!(value instanceof String))throw new IllegalArgumentException(key+"必须为文字");return (String)value;}
    private static String option(JSONObject input,String key,String fallback,Set<String> accepted){String value=input.has(key)?string(input,key):fallback;if(!accepted.contains(value))throw new IllegalArgumentException(key+"取值无效");return value;}
    private static void allowed(JSONObject input,Set<String> keys){for(Iterator<String> values=input.keys();values.hasNext();){String key=values.next();if(!keys.contains(key))throw new IllegalArgumentException("重复规则不支持字段："+key);}}
    private static final class Occurrence {
        final long at;final LocalDateTime requested;final ZonedDateTime resolved;final String resolution;
        Occurrence(long at,LocalDateTime requested,ZonedDateTime resolved,String resolution){this.at=at;this.requested=requested;this.resolved=resolved;this.resolution=resolution;}
        JSONObject json()throws Exception{return new JSONObject().put("at",at).put("requestedLocal",requested.toString()).put("local",resolved.toLocalDateTime().toString()).put("zone",resolved.getZone().getId()).put("offset",resolved.getOffset().getId()).put("dstResolution",resolution);}
    }
}
