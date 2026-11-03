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
    public static final int SCHEDULE_VERSION=1,MAX_CRON_LENGTH=512,CRON_LOOKAHEAD_YEARS=8;
    private AutomationSchedule(){}

    public static JSONObject normalize(JSONObject input)throws Exception {
        if(input==null)throw new IllegalArgumentException("必须提供日历规则");
        version(input);
        String type=string(input,"type");
        if(type.equals("once"))return versioned(input,new JSONObject().put("type",type).put("at",AutomationPolicy.integer(input,"at",1,AutomationPolicy.MAX_TIME)));
        if(type.equals("interval")){
            allowed(input,Set.of("type","anchorAt","intervalMillis"));
            long anchor=AutomationPolicy.integer(input,"anchorAt",1,AutomationPolicy.MAX_TIME);
            long every=AutomationPolicy.integer(input,"intervalMillis",60000,366L*24*60*60*1000);
            return versioned(input,new JSONObject().put("type",type).put("anchorAt",anchor).put("intervalMillis",every));
        }
        if(!Set.of("daily","weekly","monthly","cron").contains(type))throw new IllegalArgumentException("当前支持一次性、固定间隔、每日、每周、每月或五字段 cron 规则");
        String zone=string(input,"zone");
        try{ZoneId.of(zone);}catch(java.time.DateTimeException invalid){throw new IllegalArgumentException("重复规则时区无效");}
        String gap=option(input,"dstGap","shift_forward",Set.of("shift_forward","skip"));
        String overlap=option(input,"dstOverlap","earlier",Set.of("earlier","later"));
        if(type.equals("cron")){
            allowed(input,Set.of("type","expression","zone","dstGap","dstOverlap"));
            Cron cron=new Cron(string(input,"expression"));
            return new JSONObject().put("type",type).put("scheduleVersion",SCHEDULE_VERSION).put("expression",cron.expression)
                    .put("zone",zone).put("dstGap",gap).put("dstOverlap",overlap);
        }
        String time=string(input,"time");
        if(!time.matches("(?:[01][0-9]|2[0-3]):[0-5][0-9]"))throw new IllegalArgumentException("重复时间使用HH:mm");
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
        return versioned(input,result);
    }

    /** Missing versions are the original v1 shape. Never rewrite a reviewed legacy digest. */
    public static int version(JSONObject schedule){
        if(schedule==null)throw new IllegalArgumentException("必须提供日历规则");
        if(!schedule.has("scheduleVersion"))return SCHEDULE_VERSION;
        return (int)AutomationPolicy.integer(schedule,"scheduleVersion",SCHEDULE_VERSION,SCHEDULE_VERSION);
    }
    private static JSONObject versioned(JSONObject input,JSONObject result)throws Exception {if(input.has("scheduleVersion"))result.put("scheduleVersion",SCHEDULE_VERSION);return result;}

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
        JSONObject result=new JSONObject().put("schedule",schedule).put("after",after).put("occurrences",times)
                .put("exhausted",next(schedule,cursor)==null).put("timing","inexact_best_effort")
                .put("scheduleVersion",version(schedule))
                .put("calendarOnly",true).put("policyEvaluated",false).put("executionAuthorized",false);
        if("cron".equals(schedule.getString("type")))result.put("cronNextSearchYears",CRON_LOOKAHEAD_YEARS);return result;
    }

    private static Occurrence next(JSONObject schedule,long after)throws Exception {
        validAfter(after);String type=schedule.getString("type");
        if(type.equals("once")){
            long at=schedule.getLong("at");if(at<=after)return null;ZonedDateTime utc=Instant.ofEpochMilli(at).atZone(ZoneOffset.UTC);
            return new Occurrence(at,utc.toLocalDateTime(),utc,"absolute");
        }
        if(type.equals("interval")){
            long anchor=schedule.getLong("anchorAt"),every=schedule.getLong("intervalMillis");
            long steps=after<anchor?0:Math.addExact(Math.floorDiv(after-anchor,every),1);
            // Bound before multiplying: exhausted calendars return no occurrence, never wrap.
            if(steps>(AutomationPolicy.MAX_TIME-anchor)/every)return null;
            long at=anchor+steps*every;ZonedDateTime utc=Instant.ofEpochMilli(at).atZone(ZoneOffset.UTC);
            return new Occurrence(at,utc.toLocalDateTime(),utc,"fixed_anchor_interval");
        }
        if(type.equals("cron"))return nextCron(schedule,after);
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
    private static Occurrence nextCron(JSONObject schedule,long after)throws Exception {
        Cron cron=new Cron(schedule.getString("expression"));ZoneId zone=ZoneId.of(schedule.getString("zone"));
        LocalDate start=Instant.ofEpochMilli(after).atZone(zone).toLocalDate(),end=start.plusYears(CRON_LOOKAHEAD_YEARS);
        long horizon=Math.min(AutomationPolicy.MAX_TIME,Instant.ofEpochMilli(after).atZone(zone).plusYears(CRON_LOOKAHEAD_YEARS).toInstant().toEpochMilli());
        // A gap can shift a requested time into the next day. Include that original day,
        // then compare actual instants; wall-clock order is not necessarily instant order.
        Occurrence earliest=null;
        for(LocalDate date=start.minusDays(1);!date.isAfter(end);date=date.plusDays(1)){
            if(date.atStartOfDay(zone).toInstant().toEpochMilli()>AutomationPolicy.MAX_TIME)break;
            if(earliest!=null&&date.atStartOfDay(zone).toInstant().toEpochMilli()>earliest.at)break;
            if(!cron.matches(date))continue;
            for(int hour:cron.hours)for(int minute:cron.minutes){
                Occurrence candidate=resolve(date.atTime(hour,minute),zone,schedule);
                if(candidate!=null&&candidate.at>after&&candidate.at<=horizon&&(earliest==null||candidate.at<earliest.at))earliest=candidate;
            }
        }
        return earliest;
    }

    /** Deliberately bounded five-field syntax, not a shell command or a full cron dialect. */
    private static final class Cron {
        final int[] minutes,hours,days,months,weekdays;final String expression;
        Cron(String expression){
            if(expression.length()>MAX_CRON_LENGTH)throw new IllegalArgumentException("cron 表达式最多512个字符");
            String[] fields=expression.trim().split("\\s+");if(fields.length!=5)throw new IllegalArgumentException("cron 须为五字段：分 时 日 月 周；不支持秒、年或命令");
            minutes=field(fields[0],0,59);hours=field(fields[1],0,23);days=field(fields[2],1,31);months=field(fields[3],1,12);weekdays=field(fields[4],0,6);
            if(days.length!=31&&weekdays.length!=7)throw new IllegalArgumentException("cron 的日与周最多限制一项，另一项须覆盖全部；周日为0");
            this.expression=canonical(minutes,60)+" "+canonical(hours,24)+" "+canonical(days,31)+" "+canonical(months,12)+" "+canonical(weekdays,7);
            // Leap-year dates include every valid month/day combination in this subset.
            boolean possible=false;for(LocalDate date=LocalDate.of(2000,1,1);date.getYear()==2000;date=date.plusDays(1))if(matches(date)){possible=true;break;}
            if(!possible)throw new IllegalArgumentException("cron 没有合法日历日期，例如二月不存在31日");
        }
        boolean matches(LocalDate date){return contains(months,date.getMonthValue())&&contains(days,date.getDayOfMonth())&&contains(weekdays,date.getDayOfWeek().getValue()%7);}
        private static boolean contains(int[] values,int value){return java.util.Arrays.binarySearch(values,value)>=0;}
        private static String canonical(int[] values,int size){if(values.length==size)return "*";StringBuilder text=new StringBuilder();for(int value:values){if(text.length()>0)text.append(',');text.append(value);}return text.toString();}
        private static int[] field(String field,int min,int max){
            String[] parts=field.split(",",-1);if(parts.length>max-min+1)throw new IllegalArgumentException("cron 字段集合过长");TreeSet<Integer> values=new TreeSet<>();
            for(String part:parts){
                String[] stepParts=part.split("/",-1);if(stepParts.length>2)throw new IllegalArgumentException("cron 步长格式无效");
                int step=stepParts.length==2?number(stepParts[1],1,max-min+1):1;String base=stepParts[0];int from,to;
                if(base.equals("*")){from=min;to=max;}
                else{String[] bounds=base.split("-",-1);if(bounds.length>2||stepParts.length==2&&bounds.length!=2)throw new IllegalArgumentException("cron 步长仅支持 */n 或 a-b/n");from=number(bounds[0],min,max);to=bounds.length==2?number(bounds[1],min,max):from;if(from>to)throw new IllegalArgumentException("cron 范围须递增，不支持跨界范围");}
                for(int value=from;value<=to;value+=step)values.add(value);
            }
            if(values.isEmpty())throw new IllegalArgumentException("cron 字段不能为空");return values.stream().mapToInt(Integer::intValue).toArray();
        }
        private static int number(String text,int min,int max){
            if(!text.matches("[0-9]{1,2}"))throw new IllegalArgumentException("cron 仅接受数字、*、逗号、递增范围与正步长，不支持别名或特殊日期语法");
            int value=Integer.parseInt(text);if(value<min||value>max)throw new IllegalArgumentException("cron 字段数值应为"+min+"至"+max);return value;
        }
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
    private static void allowed(JSONObject input,Set<String> keys){for(Iterator<String> values=input.keys();values.hasNext();){String key=values.next();if(!key.equals("scheduleVersion")&&!keys.contains(key))throw new IllegalArgumentException("重复规则不支持字段："+key);}}
    private static final class Occurrence {
        final long at;final LocalDateTime requested;final ZonedDateTime resolved;final String resolution;
        Occurrence(long at,LocalDateTime requested,ZonedDateTime resolved,String resolution){this.at=at;this.requested=requested;this.resolved=resolved;this.resolution=resolution;}
        JSONObject json()throws Exception{return new JSONObject().put("at",at).put("requestedLocal",requested.toString()).put("local",resolved.toLocalDateTime().toString()).put("zone",resolved.getZone().getId()).put("offset",resolved.getOffset().getId()).put("dstResolution",resolution);}
    }
}
