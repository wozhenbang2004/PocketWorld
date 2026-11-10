package dev.pocketengine.displayhelper;

import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.ResolveInfo;
import android.content.pm.verify.domain.DomainVerificationManager;
import android.content.pm.verify.domain.DomainVerificationUserState;
import android.os.UserManager;
import android.net.Uri;
import android.provider.AlarmClock;
import android.provider.CalendarContract;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** A closed, typed Intent protocol. No raw Intent URI, arbitrary component/extra or shell command. */
final class DisplayTypedIntent {
    interface Gate {void check()throws Exception;void requireAppFree(String packageName,int userId)throws Exception;default void entering()throws Exception{}}
    static JSONObject dispatch(Context context,int display,JSONObject request,Gate gate)throws Exception {
        if(display<=0)throw new SecurityException("background_display_required");
        Intent intent=requireTarget(context,request);JSONObject target=request.getJSONObject("target");
        String pkg=target.getString("packageName");int user=integer(request,"userId",0,21474);
        gate.check();gate.requireAppFree(pkg,user);gate.check();
        // The request travelled through the original admitted IPC call. Recheck native
        // install/profile/routing facts after that gate, immediately before entry.
        intent=requireTarget(context,request);gate.check();
        gate.entering();
        try{context.startActivity(intent,ActivityOptions.makeBasic().setLaunchDisplayId(display).toBundle());}
        catch(android.content.ActivityNotFoundException|SecurityException refused){return new JSONObject().put("ok",false).put("accepted",false).put("code","background_intent_platform_refused").put("automaticFallback",false);}
        catch(Exception unknown){return new JSONObject().put("ok",false).put("accepted",JSONObject.NULL).put("code","background_intent_outcome_unknown").put("automaticReplayAllowed",false).put("requiresReconciliation",true).put("verifiedBusinessSuccess",false);}
        return new JSONObject().put("ok",true).put("accepted",true).put("status","launch_requested").put("packageName",pkg).put("userId",user).put("verifiedBusinessSuccess",false).put("messageSent",false).put("requiresPostconditionCheck",true);
    }
    static Intent build(String kind,JSONObject p)throws Exception {
        if(!kind.equals(p.getString("kind")))throw new SecurityException("typed_intent_kind_mismatch");
        switch(kind){
            case "share_text":keys(p,"kind","packageName","text");return new Intent(Intent.ACTION_SEND).setType("text/plain").setPackage(packageName(p,"packageName")).putExtra(Intent.EXTRA_TEXT,text(p,"text",10000));
            case "show_alarms":keys(p,"kind");return new Intent(AlarmClock.ACTION_SHOW_ALARMS);
            case "alarm":{
                keys(p,"kind","hour","minutes","message","days","vibrate");
                Intent intent=new Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR,integer(p,"hour",0,23)).putExtra(AlarmClock.EXTRA_MINUTES,integer(p,"minutes",0,59)).putExtra(AlarmClock.EXTRA_SKIP_UI,false);
                ArrayList<Integer> days=alarmDays(p);if(!days.isEmpty())intent.putIntegerArrayListExtra(AlarmClock.EXTRA_DAYS,days);
                if(p.has("vibrate"))intent.putExtra(AlarmClock.EXTRA_VIBRATE,booleanValue(p,"vibrate",false));
                if(p.has("message"))intent.putExtra(AlarmClock.EXTRA_MESSAGE,text(p,"message",120));return intent;
            }
            case "timer":{keys(p,"kind","seconds","message");Intent intent=new Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH,integer(p,"seconds",1,86400)).putExtra(AlarmClock.EXTRA_SKIP_UI,false);if(p.has("message"))intent.putExtra(AlarmClock.EXTRA_MESSAGE,text(p,"message",120));return intent;}
            case "calendar_draft":{
                keys(p,"kind","title","start","end","timeZone","allDay","location","description");String zone=text(p,"timeZone",80);
                if(!java.time.ZoneId.getAvailableZoneIds().contains(zone))throw new SecurityException("calendar_zone_invalid");
                long begin=whole(p,"start",0,253402300799999L),end=whole(p,"end",0,253402300799999L);if(end<=begin)throw new SecurityException("calendar_range_invalid");
                boolean allDay=booleanValue(p,"allDay",false);
                if(allDay&&(!"UTC".equals(zone)||begin%86_400_000L!=0||end%86_400_000L!=0))throw new SecurityException("calendar_all_day_utc_boundaries_required");
                return new Intent(Intent.ACTION_INSERT).setDataAndType(CalendarContract.Events.CONTENT_URI,"vnd.android.cursor.dir/event")
                        .putExtra(CalendarContract.Events.TITLE,text(p,"title",200)).putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME,begin)
                        .putExtra(CalendarContract.EXTRA_EVENT_END_TIME,end).putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY,allDay)
                        .putExtra(CalendarContract.Events.EVENT_TIMEZONE,zone).putExtra(CalendarContract.Events.EVENT_END_TIMEZONE,zone)
                        .putExtra(CalendarContract.Events.EVENT_LOCATION,text(p,"location",500)).putExtra(CalendarContract.Events.DESCRIPTION,text(p,"description",4000));
            }
            case "web":{keys(p,"kind","url");return new Intent(Intent.ACTION_VIEW,https(p)).addCategory(Intent.CATEGORY_BROWSABLE);}
            case "app_link":{keys(p,"kind","url","packageName");String pkg=packageName(p,"packageName");return new Intent(Intent.ACTION_VIEW,https(p)).addCategory(Intent.CATEGORY_BROWSABLE).setPackage(pkg);}
            case "map_search":keys(p,"kind","query");return new Intent(Intent.ACTION_VIEW,Uri.parse("geo:0,0?q="+Uri.encode(text(p,"query",500))));
            case "dial":{keys(p,"kind","number");String number=text(p,"number",32);if(!number.matches("\\+?[0-9]{1,20}"))throw new SecurityException("dial_number_invalid");return new Intent(Intent.ACTION_DIAL,Uri.parse("tel:"+number));}
            case "email_draft":{keys(p,"kind","to","subject","body");String to=text(p,"to",254);if(!to.matches("[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,63}"))throw new SecurityException("email_address_invalid");return new Intent(Intent.ACTION_SENDTO,Uri.parse("mailto:"+Uri.encode(to))).putExtra(Intent.EXTRA_EMAIL,new String[]{to}).putExtra(Intent.EXTRA_SUBJECT,text(p,"subject",200)).putExtra(Intent.EXTRA_TEXT,text(p,"body",4000));}
            default:throw new SecurityException("typed_intent_kind_unavailable");
        }
    }
    private static Intent requireTarget(Context context,JSONObject request)throws Exception {
        JSONObject target=request.getJSONObject("target");String pkg=packageName(target,"packageName");int user=integer(request,"userId",0,21474);
        int targetUid=integer(target,"uid",0,Integer.MAX_VALUE);
        if(user!=android.os.Process.myUid()/100000||targetUid/100000!=user||integer(target,"userId",0,21474)!=user)
            throw new SecurityException("semantic_profile_not_available");
        UserManager users=context.getSystemService(UserManager.class);
        if(users==null||!users.isUserUnlocked()||users.getSerialNumberForUser(android.os.Process.myUserHandle())!=whole(target,"profileSerial",0,Long.MAX_VALUE))
            throw new SecurityException("semantic_profile_changed");
        ComponentName component=ComponentName.unflattenFromString(target.getString("component"));
        if(component==null||!pkg.equals(component.getPackageName()))throw new SecurityException("intent_target_mismatch");
        PackageManager pm=context.getPackageManager();PackageInfo actual=pm.getPackageInfo(pkg,PackageManager.GET_SIGNING_CERTIFICATES);
        if(actual.getLongVersionCode()!=target.getLong("versionCode")||actual.lastUpdateTime!=target.getLong("lastUpdateTime")
                ||actual.firstInstallTime!=target.getLong("firstInstallTime")||actual.signingInfo==null)
            throw new SecurityException("intent_package_changed");
        ArrayList<String> certificates=new ArrayList<>();Signature[] signers=actual.signingInfo.getApkContentsSigners();
        if(signers==null||signers.length==0)throw new SecurityException("intent_signer_unavailable");
        for(Signature signer:signers)certificates.add(hash(signer.toByteArray()));certificates.sort(String::compareTo);
        if(!new JSONArray(certificates).toString().equals(target.getJSONArray("certificates").toString()))throw new SecurityException("intent_signer_changed");
        String kind=request.getString("kind");JSONObject parameters=request.getJSONObject("parameters");
        if((kind.equals("app_link")||kind.equals("share_text"))&&!pkg.equals(packageName(parameters,"packageName")))throw new SecurityException("intent_package_mismatch");
        if((kind.equals("alarm")||kind.equals("timer"))&&!java.util.TimeZone.getDefault().getID().equals(target.getString("deviceTimeZone")))throw new SecurityException("intent_clock_zone_changed");
        Intent intent=build(kind,parameters);boolean eligible=false;
        for(ResolveInfo match:pm.queryIntentActivities(intent,PackageManager.MATCH_DEFAULT_ONLY|PackageManager.GET_RESOLVED_FILTER)) {
            ActivityInfo activity=match.activityInfo;
            if(activity==null||!component.equals(new ComponentName(activity.packageName,activity.name)))continue;
            if(!activity.exported||!activity.enabled||activity.applicationInfo==null||!activity.applicationInfo.enabled
                    ||activity.applicationInfo.uid!=targetUid||activity.launchMode>ActivityInfo.LAUNCH_SINGLE_TOP
                    ||activity.permission!=null&&!activity.permission.isEmpty()&&context.checkSelfPermission(activity.permission)!=PackageManager.PERMISSION_GRANTED)
                throw new SecurityException("intent_handler_unavailable");
            if(kind.equals("web")||kind.equals("app_link")) {
                String route=webRoute(context,match,intent.getData().getHost().toLowerCase(java.util.Locale.ROOT));
                if(route==null||!route.equals(request.getString("route")))throw new SecurityException("intent_app_link_route_changed");
            }
            eligible=true;break;
        }
        if(!eligible)throw new SecurityException("intent_handler_unresolved");
        return intent.setComponent(component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
    }
    private static String webRoute(Context context,ResolveInfo match,String host)throws Exception {
        if(match.filter!=null&&match.filter.hasDataScheme("https")&&match.filter.hasCategory(Intent.CATEGORY_BROWSABLE)
                &&match.filter.countDataAuthorities()==0&&match.filter.countDataPaths()==0&&match.filter.countDataSchemeSpecificParts()==0)return "browser";
        DomainVerificationManager manager=context.getSystemService(DomainVerificationManager.class);
        DomainVerificationUserState state=manager==null?null:manager.getDomainVerificationUserState(match.activityInfo.packageName);
        if(state==null||!state.isLinkHandlingAllowed())return null;boolean selected=false;
        for(var entry:state.getHostToStateMap().entrySet()) {
            String domain=entry.getKey().toLowerCase(java.util.Locale.ROOT);
            if(host.equals(domain)||domain.startsWith("*.")&&host.endsWith(domain.substring(1))&&host.length()>domain.length()-1) {
                if(entry.getValue()==DomainVerificationUserState.DOMAIN_STATE_VERIFIED)return "verified_app_link";
                if(entry.getValue()==DomainVerificationUserState.DOMAIN_STATE_SELECTED)selected=true;
            }
        }
        return selected?"user_selected_app_link":null;
    }
    private static Uri https(JSONObject parameters)throws Exception {
        String url=text(parameters,"url",2048);java.net.URI uri=new java.net.URI(url);
        if(!"https".equals(uri.getScheme())||uri.getHost()==null||uri.getRawUserInfo()!=null||uri.getPort()<-1||uri.getPort()>65535
                ||url.indexOf('\\')>=0||url.matches("(?s).*[\\p{Cntrl}\\s].*"))throw new SecurityException("web_url_invalid");
        return Uri.parse(uri.toASCIIString());
    }
    private static String packageName(JSONObject values,String key)throws Exception {
        String pkg=text(values,key,250);if(!pkg.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+"))throw new SecurityException("typed_intent_package_invalid");return pkg;
    }
    private static String hash(byte[] bytes)throws Exception {StringBuilder out=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))out.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return out.toString();}
    private static String text(JSONObject p,String key,int max)throws Exception {Object raw=p.get(key);if(!(raw instanceof String)||((String)raw).length()>max)throw new SecurityException("typed_intent_text_invalid");return(String)raw;}
    private static boolean booleanValue(JSONObject p,String key,boolean fallback)throws Exception {
        if(!p.has(key))return fallback;Object raw=p.get(key);if(!(raw instanceof Boolean))throw new SecurityException("typed_intent_boolean_invalid");return(Boolean)raw;
    }
    private static ArrayList<Integer> alarmDays(JSONObject p)throws Exception {
        ArrayList<Integer> days=new ArrayList<>();if(!p.has("days"))return days;
        Object raw=p.get("days");if(!(raw instanceof JSONArray)||((JSONArray)raw).length()>7)throw new SecurityException("alarm_days_invalid");
        JSONArray selected=(JSONArray)raw;int previous=-1;
        for(int i=0;i<selected.length();i++){
            Object day=selected.get(i);if(!(day instanceof String))throw new SecurityException("alarm_days_invalid");
            int index=switch((String)day){case "SUN"->0;case "MON"->1;case "TUE"->2;case "WED"->3;case "THU"->4;case "FRI"->5;case "SAT"->6;default->throw new SecurityException("alarm_days_invalid");};
            if(index<=previous)throw new SecurityException("alarm_days_not_canonical");
            days.add(java.util.Calendar.SUNDAY+index);previous=index;
        }
        return days;
    }
    private static int integer(JSONObject p,String key,int min,int max)throws Exception {return(int)whole(p,key,min,max);}
    private static long whole(JSONObject p,String key,long min,long max)throws Exception {Object raw=p.get(key);if(!(raw instanceof Number))throw new SecurityException("typed_intent_integer_invalid");long value=new java.math.BigDecimal(raw.toString()).longValueExact();if(value<min||value>max)throw new SecurityException("typed_intent_integer_invalid");return value;}
    private static void keys(JSONObject p,String...allowed){Set<String> keys=new java.util.HashSet<>(java.util.Arrays.asList(allowed));var names=p.keys();while(names.hasNext())if(!keys.contains(names.next()))throw new SecurityException("typed_intent_extra_not_allowed");}
}
