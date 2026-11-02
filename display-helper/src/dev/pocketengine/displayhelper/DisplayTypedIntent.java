package dev.pocketengine.displayhelper;

import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.provider.AlarmClock;
import android.provider.CalendarContract;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** A closed, typed Intent protocol. No raw Intent URI, arbitrary component/extra or shell command. */
final class DisplayTypedIntent {
    interface Gate {void check()throws Exception;void requireAppFree(String packageName,int userId)throws Exception;}
    static JSONObject dispatch(Context context,int display,JSONObject request,Gate gate)throws Exception {
        if(display<=0)throw new SecurityException("background_display_required");JSONObject target=request.getJSONObject("target");String pkg=target.getString("packageName");int user=integer(request,"userId",0,21474);
        if(user!=android.os.Process.myUid()/100000)throw new SecurityException("semantic_profile_not_available");
        ComponentName component=ComponentName.unflattenFromString(target.getString("component"));if(component==null||!pkg.equals(component.getPackageName()))throw new SecurityException("intent_target_mismatch");
        PackageManager pm=context.getPackageManager();PackageInfo actual=pm.getPackageInfo(pkg,PackageManager.GET_SIGNING_CERTIFICATES);if(actual.getLongVersionCode()!=target.getLong("versionCode")||actual.lastUpdateTime!=target.getLong("lastUpdateTime")||actual.signingInfo==null)throw new SecurityException("intent_package_changed");
        ArrayList<String> certificates=new ArrayList<>();for(Signature signer:actual.signingInfo.getApkContentsSigners())certificates.add(hash(signer.toByteArray()));certificates.sort(String::compareTo);if(!new JSONArray(certificates).toString().equals(target.getJSONArray("certificates").toString()))throw new SecurityException("intent_signer_changed");
        ActivityInfo activity=pm.getActivityInfo(component,0);if(!activity.exported||!activity.enabled||activity.launchMode>ActivityInfo.LAUNCH_SINGLE_TOP||activity.permission!=null&&context.checkSelfPermission(activity.permission)!=PackageManager.PERMISSION_GRANTED)throw new SecurityException("intent_handler_unavailable");
        Intent intent=build(request.getString("kind"),request.getJSONObject("parameters"));intent.setComponent(component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
        if(intent.resolveActivityInfo(pm,PackageManager.MATCH_DEFAULT_ONLY)==null)throw new SecurityException("intent_handler_unresolved");gate.check();gate.requireAppFree(pkg,user);gate.check();
        try{context.startActivity(intent,ActivityOptions.makeBasic().setLaunchDisplayId(display).toBundle());}
        catch(android.content.ActivityNotFoundException|SecurityException refused){return new JSONObject().put("ok",false).put("accepted",false).put("code","background_intent_platform_refused").put("automaticFallback",false);}
        return new JSONObject().put("ok",true).put("accepted",true).put("status","launch_requested").put("packageName",pkg).put("userId",user).put("verifiedBusinessSuccess",false).put("messageSent",false).put("requiresPostconditionCheck",true);
    }
    static Intent build(String kind,JSONObject p)throws Exception {
        switch(kind){
            case "share_text":keys(p,"kind","packageName","text");return new Intent(Intent.ACTION_SEND).setType("text/plain").setPackage(text(p,"packageName",250)).putExtra(Intent.EXTRA_TEXT,text(p,"text",10000));
            case "show_alarms":keys(p,"kind");return new Intent(AlarmClock.ACTION_SHOW_ALARMS);
            case "alarm":{keys(p,"kind","hour","minutes","message");Intent intent=new Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR,integer(p,"hour",0,23)).putExtra(AlarmClock.EXTRA_MINUTES,integer(p,"minutes",0,59)).putExtra(AlarmClock.EXTRA_SKIP_UI,false);if(p.has("message"))intent.putExtra(AlarmClock.EXTRA_MESSAGE,text(p,"message",120));return intent;}
            case "timer":{keys(p,"kind","seconds","message");Intent intent=new Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH,integer(p,"seconds",1,86400)).putExtra(AlarmClock.EXTRA_SKIP_UI,false);if(p.has("message"))intent.putExtra(AlarmClock.EXTRA_MESSAGE,text(p,"message",120));return intent;}
            case "calendar_draft":{keys(p,"kind","title","start","end","timeZone","location","description");String zone=text(p,"timeZone",80);if(!java.time.ZoneId.getAvailableZoneIds().contains(zone))throw new SecurityException("calendar_zone_invalid");long begin=whole(p,"start",0,253402300799999L),end=whole(p,"end",0,253402300799999L);if(end<=begin)throw new SecurityException("calendar_range_invalid");return new Intent(Intent.ACTION_INSERT).setDataAndType(CalendarContract.Events.CONTENT_URI,"vnd.android.cursor.dir/event").putExtra(CalendarContract.Events.TITLE,text(p,"title",200)).putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME,begin).putExtra(CalendarContract.EXTRA_EVENT_END_TIME,end).putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY,false).putExtra(CalendarContract.Events.EVENT_TIMEZONE,zone).putExtra(CalendarContract.Events.EVENT_END_TIMEZONE,zone).putExtra(CalendarContract.Events.EVENT_LOCATION,text(p,"location",500)).putExtra(CalendarContract.Events.DESCRIPTION,text(p,"description",4000));}
            case "web":{keys(p,"kind","url");String url=text(p,"url",2048);Uri uri=Uri.parse(url);if(!"https".equals(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null)throw new SecurityException("web_url_invalid");return new Intent(Intent.ACTION_VIEW,uri).addCategory(Intent.CATEGORY_BROWSABLE);}
            case "app_link":{keys(p,"kind","url","packageName");String url=text(p,"url",2048),scheme=Uri.parse(url).getScheme();if(scheme==null||!scheme.matches("[a-z][a-z0-9+.-]{0,40}")||Set.of("intent","file","content","javascript","data","android-app").contains(scheme)||Uri.parse(url).getUserInfo()!=null)throw new SecurityException("app_link_invalid");return new Intent(Intent.ACTION_VIEW,Uri.parse(url)).setPackage(text(p,"packageName",250));}
            case "map_search":keys(p,"kind","query");return new Intent(Intent.ACTION_VIEW,Uri.parse("geo:0,0?q="+Uri.encode(text(p,"query",500))));
            case "dial":{keys(p,"kind","number");String number=text(p,"number",32);if(!number.matches("\\+?[0-9]{1,20}"))throw new SecurityException("dial_number_invalid");return new Intent(Intent.ACTION_DIAL,Uri.parse("tel:"+number));}
            case "email_draft":{keys(p,"kind","to","subject","body");String to=text(p,"to",254);if(!to.matches("[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,63}"))throw new SecurityException("email_address_invalid");return new Intent(Intent.ACTION_SENDTO,Uri.parse("mailto:"+Uri.encode(to))).putExtra(Intent.EXTRA_EMAIL,new String[]{to}).putExtra(Intent.EXTRA_SUBJECT,text(p,"subject",200)).putExtra(Intent.EXTRA_TEXT,text(p,"body",4000));}
            default:throw new SecurityException("typed_intent_kind_unavailable");
        }
    }
    private static String hash(byte[] bytes)throws Exception {StringBuilder out=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))out.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return out.toString();}
    private static String text(JSONObject p,String key,int max)throws Exception {Object raw=p.get(key);if(!(raw instanceof String)||((String)raw).length()>max)throw new SecurityException("typed_intent_text_invalid");return(String)raw;}
    private static int integer(JSONObject p,String key,int min,int max)throws Exception {return(int)whole(p,key,min,max);}
    private static long whole(JSONObject p,String key,long min,long max)throws Exception {Object raw=p.get(key);if(!(raw instanceof Number))throw new SecurityException("typed_intent_integer_invalid");long value=new java.math.BigDecimal(raw.toString()).longValueExact();if(value<min||value>max)throw new SecurityException("typed_intent_integer_invalid");return value;}
    private static void keys(JSONObject p,String...allowed){Set<String> keys=new java.util.HashSet<>(java.util.Arrays.asList(allowed));var names=p.keys();while(names.hasNext())if(!keys.contains(names.next()))throw new SecurityException("typed_intent_extra_not_allowed");}
}
