package dev.pocketengine.features.semantic;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.json.JSONObject;

/** The bounded, explicitly supported RFC 5545 rule grammar; Android expands occurrences. */
final class CalendarRecurrence {
    private static final String[] ORDER={"FREQ","INTERVAL","COUNT","UNTIL","BYMONTH","BYMONTHDAY","BYDAY","BYSETPOS","WKST"};
    private static final Set<String> DAYS=Set.of("MO","TU","WE","TH","FR","SA","SU");
    private CalendarRecurrence() { }

    static String normalize(String supplied,JSONObject event)throws Exception {
        if(supplied.isEmpty())return "";
        if(supplied.length()>1024||!supplied.matches("[A-Za-z0-9=;,\\-+]+"))throw invalid();
        Map<String,String> fields=new LinkedHashMap<>();
        for(String pair:supplied.toUpperCase(Locale.ROOT).split(";",-1)){
            String[] part=pair.split("=",-1);
            if(part.length!=2||part[1].isEmpty()||!java.util.List.of(ORDER).contains(part[0])||fields.putIfAbsent(part[0],part[1])!=null)throw invalid();
        }
        String frequency=fields.get("FREQ");
        if(frequency==null||!Set.of("DAILY","WEEKLY","MONTHLY","YEARLY").contains(frequency))throw invalid();
        if(fields.containsKey("COUNT")&&fields.containsKey("UNTIL"))throw new IllegalArgumentException("重复规则不能同时指定COUNT和UNTIL");
        for(String key:new String[]{"INTERVAL","COUNT"})if(fields.containsKey(key))fields.put(key,Integer.toString(integer(fields.get(key),1,key.equals("COUNT")?10000:1000)));
        if(fields.containsKey("BYMONTH"))fields.put("BYMONTH",numbers(fields.get("BYMONTH"),1,12,false));
        if(fields.containsKey("BYMONTHDAY")){
            if(frequency.equals("WEEKLY"))throw new IllegalArgumentException("WEEKLY规则不能使用BYMONTHDAY");
            fields.put("BYMONTHDAY",numbers(fields.get("BYMONTHDAY"),-31,31,true));
        }
        if(fields.containsKey("BYDAY")){
            TreeSet<String> days=new TreeSet<>();
            for(String day:fields.get("BYDAY").split(",",-1)){
                if(!day.matches("[+-]?[0-9]{0,2}(MO|TU|WE|TH|FR|SA|SU)"))throw invalid();
                String weekday=day.substring(day.length()-2),ordinal=day.substring(0,day.length()-2);
                if(!ordinal.isEmpty()){
                    if(!frequency.equals("MONTHLY")&&!frequency.equals("YEARLY"))throw invalid();
                    int position=integer(ordinal,-53,53);if(position==0||frequency.equals("MONTHLY")&&Math.abs(position)>5)throw invalid();
                    day=position+weekday;
                }
                days.add(day);
            }
            if(days.size()>53)throw invalid();fields.put("BYDAY",String.join(",",days));
        }
        if(fields.containsKey("BYSETPOS")){
            if(!fields.containsKey("BYDAY")&&!fields.containsKey("BYMONTHDAY")&&!fields.containsKey("BYMONTH"))throw invalid();
            fields.put("BYSETPOS",numbers(fields.get("BYSETPOS"),-366,366,true));
        }
        if(fields.containsKey("WKST")&&!DAYS.contains(fields.get("WKST")))throw invalid();
        if(fields.containsKey("UNTIL")){
            String until=fields.get("UNTIL");long last;
            try{
                if(event.getBoolean("allDay")){
                    if(!until.matches("[0-9]{8}"))throw invalid();
                    last=LocalDate.parse(until,DateTimeFormatter.ofPattern("uuuuMMdd",Locale.ROOT).withResolverStyle(ResolverStyle.STRICT)).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
                }else{
                    if(!until.matches("[0-9]{8}T[0-9]{6}Z"))throw invalid();
                    last=LocalDateTime.parse(until,DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss'Z'",Locale.ROOT).withResolverStyle(ResolverStyle.STRICT)).toInstant(ZoneOffset.UTC).toEpochMilli();
                }
            }catch(java.time.DateTimeException bad){throw invalid();}
            if(last<event.getLong("start"))throw new IllegalArgumentException("重复结束边界不能早于首个事件开始");
        }
        duration(event);
        StringBuilder result=new StringBuilder();
        for(String key:ORDER)if(fields.containsKey(key)){if(result.length()>0)result.append(';');result.append(key).append('=').append(fields.get(key));}
        return result.toString();
    }

    static String duration(JSONObject event)throws Exception {
        requireWholeSeconds(event);long length=event.getLong("end")-event.getLong("start");
        if(length/1000>Integer.MAX_VALUE)throw new IllegalArgumentException("重复事件时长超出Android日历可准确表示的秒数范围");
        return event.getBoolean("allDay")?"P"+(length/86_400_000L)+"D":"PT"+(length/1000L)+"S";
    }

    static boolean durationMatches(String supplied,JSONObject event)throws Exception {
        Long millis=durationMillis(supplied);return millis!=null&&millis==event.getLong("end")-event.getLong("start");
    }

    static Long durationMillis(String supplied){
        try{
            // Android also accepts P3600S and RFC week durations. Preserve existing
            // spelling when the requested duration is unchanged; new writes use RFC form.
            if(supplied.length()>128)return null;
            java.util.regex.Matcher parts=java.util.regex.Pattern.compile("[+]?P(?:(\\d+)W)?(?:(\\d+)D)?T?(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?").matcher(supplied);
            if(!parts.matches())return null;long millis=0;boolean any=false;long[] units={604_800_000L,86_400_000L,3_600_000L,60_000L,1000L};
            for(int i=1;i<=5;i++)if(parts.group(i)!=null){any=true;millis=Math.addExact(millis,Math.multiplyExact(Long.parseLong(parts.group(i)),units[i-1]));}
            return any?millis:null;
        }
        catch(NumberFormatException|ArithmeticException invalid){return null;}
    }

    static void requireWholeSeconds(JSONObject event)throws Exception {
        if(event.getLong("start")%1000!=0||event.getLong("end")%1000!=0)throw new IllegalArgumentException("重复日历时间必须精确到整秒，避免系统DURATION丢失毫秒");
    }

    private static String numbers(String supplied,int minimum,int maximum,boolean noZero){
        String[] parts=supplied.split(",",-1);if(parts.length>366)throw invalid();TreeSet<Integer> values=new TreeSet<>();
        for(String part:parts){int value=integer(part,minimum,maximum);if(noZero&&value==0)throw invalid();values.add(value);}
        return values.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));
    }
    private static int integer(String supplied,int minimum,int maximum){
        try{if(!supplied.matches("[+-]?[0-9]{1,5}"))throw invalid();int value=Integer.parseInt(supplied);if(value<minimum||value>maximum)throw invalid();return value;}
        catch(NumberFormatException bad){throw invalid();}
    }
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("重复规则仅支持DAILY/WEEKLY/MONTHLY/YEARLY、INTERVAL、COUNT或UNTIL、BYDAY、BYMONTHDAY、BYMONTH、BYSETPOS及WKST；请使用有效的有界RRULE字段");}
}
