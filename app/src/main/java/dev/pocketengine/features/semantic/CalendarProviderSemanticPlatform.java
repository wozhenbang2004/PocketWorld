package dev.pocketengine.features.semantic;

import android.Manifest;
import android.content.ComponentName;
import android.content.ContentProviderOperation;
import android.content.ContentProviderResult;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.Signature;
import android.database.Cursor;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.UserManager;
import android.provider.CalendarContract;
import dev.pocketengine.core.RunControl;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;

/** Fixed CalendarContract operations in the host profile, inside the original semantic run. */
final class CalendarProviderSemanticPlatform implements SemanticPlatform {
    private static final int PAGE_SIZE=32;
    private static final String[] CALENDAR_COLUMNS={CalendarContract.Calendars._ID,CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.ACCOUNT_TYPE,CalendarContract.Calendars.OWNER_ACCOUNT,CalendarContract.Calendars.NAME,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
            CalendarContract.Calendars.CALENDAR_TIME_ZONE,CalendarContract.Calendars.VISIBLE,CalendarContract.Calendars.DELETED};
    private static final String[] CALENDAR_KEYS={"calendarId","accountName","accountType","ownerAccount","name","displayName","accessLevel","timeZone","visible","deleted"};
    private static final String[] EVENT_COLUMNS={CalendarContract.Events._ID,CalendarContract.Events.CALENDAR_ID,CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,CalendarContract.Events.DTEND,CalendarContract.Events.EVENT_TIMEZONE,
            CalendarContract.Events.EVENT_END_TIMEZONE,CalendarContract.Events.ALL_DAY,CalendarContract.Events.EVENT_LOCATION,
            CalendarContract.Events.DESCRIPTION,CalendarContract.Events.RRULE,CalendarContract.Events.RDATE,CalendarContract.Events.DELETED};
    private final Context context;
    private final SemanticPlatform entries;

    CalendarProviderSemanticPlatform(Context context,SemanticPlatform entries){this.context=context.getApplicationContext();this.entries=entries;}

    static JSONObject permissions(Context context)throws Exception {
        boolean read=context.checkSelfPermission(Manifest.permission.READ_CALENDAR)==PackageManager.PERMISSION_GRANTED;
        boolean write=context.checkSelfPermission(Manifest.permission.WRITE_CALENDAR)==PackageManager.PERMISSION_GRANTED;
        return new JSONObject().put("readGranted",read).put("writeGranted",write).put("granted",read&&write)
                .put("ownerPermissionEntry","calendar").put("scope","current_host_android_profile");
    }

    static final class PermissionRequired extends SecurityException {
        final JSONObject state;
        PermissionRequired(JSONObject state){super("请主人在设置的日历权限入口允许系统日历访问后重新发现");this.state=state;}
    }

    private void requirePermissions(boolean write)throws Exception {
        JSONObject state=permissions(context);
        if(!state.getBoolean("readGranted")||write&&!state.getBoolean("writeGranted"))throw new PermissionRequired(state);
    }

    @Override public List<Target> discover(SemanticRequest request,RunControl control)throws Exception {
        if(!request.calendarProvider())return entries.discover(request,control);
        control.check();requirePermissions(request.kind.equals("calendar_batch"));JSONObject provider=providerIdentity();
        JSONObject p=request.parameters();boolean batch=request.kind.equals("calendar_batch");
        String selection=CalendarContract.Calendars.DELETED+"=0 AND "
                +CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL+">=? AND "+CalendarContract.Calendars._ID+(batch?"=?":">?");
        String[] args={Integer.toString(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR),Long.toString(p.getLong(batch?"calendarId":"afterCalendarId"))};
        List<Target> targets=new ArrayList<>();CancellationSignal signal=new CancellationSignal();Runnable cancel=signal::cancel;control.onCancel(cancel);
        try(Cursor rows=context.getContentResolver().query(CalendarContract.Calendars.CONTENT_URI,CALENDAR_COLUMNS,selection,args,CalendarContract.Calendars._ID+" ASC",signal)){
            if(rows==null)throw new IllegalStateException("系统日历列表未返回，请检查日历服务");
            while(rows.moveToNext()){
                control.check();JSONObject calendar=calendar(rows);long id=calendar.getLong("calendarId");
                if(id<=0||id>9_007_199_254_740_991L)continue;
                JSONObject identity=SemanticJson.copy(provider).put("calendar",calendar);
                targets.add(new Target(identity,calendar.optString("displayName","日历"),"calendar_provider"));
                if(targets.size()>PAGE_SIZE)break;
            }
        }finally{control.removeCancelAction(cancel);}
        control.check();return targets;
    }

    @Override public JSONObject resourceIdentity()throws Exception{return entries.resourceIdentity();}
    @Override public JSONObject resourceIdentity(SemanticRequest request)throws Exception {
        if(!request.calendarProvider())return entries.resourceIdentity(request);
        requirePermissions(request.kind.equals("calendar_batch"));
        return new JSONObject().put("executionMode","host_calendar_provider").put("provider",providerIdentity())
                .put("permissions",permissions(context)).put("crossProfile",false);
    }

    private JSONObject providerIdentity()throws Exception {
        int user=dev.pocketengine.phone.setup.AndroidAppCopies.id(android.os.Process.myUserHandle());
        UserManager users=context.getSystemService(UserManager.class);
        long serial=users==null?-1:users.getSerialNumberForUser(android.os.Process.myUserHandle());
        if(user<0||serial<0||!users.isUserUnlocked())throw new SecurityException("当前宿主Android资料未解锁或身份不可核验");
        PackageManager pm=context.getPackageManager();ProviderInfo provider=pm.resolveContentProvider(CalendarContract.AUTHORITY,0);
        if(provider==null||!provider.enabled||provider.applicationInfo==null||!provider.applicationInfo.enabled
                ||provider.applicationInfo.uid<0||provider.applicationInfo.uid/100000!=user)
            throw new SecurityException("当前资料的系统日历Provider不可核验");
        PackageInfo pkg=pm.getPackageInfo(provider.packageName,PackageManager.GET_SIGNING_CERTIFICATES);
        if(pkg.signingInfo==null)throw new SecurityException("系统日历Provider签名不可核验");
        Signature[] signers=pkg.signingInfo.getApkContentsSigners();
        if(signers==null||signers.length==0)throw new SecurityException("系统日历Provider签名不可核验");
        ArrayList<String> certificates=new ArrayList<>();for(Signature signer:signers)certificates.add(SemanticJson.hash(signer.toByteArray()));certificates.sort(String::compareTo);
        return new JSONObject().put("component",new ComponentName(provider.packageName,provider.name).flattenToString())
                .put("packageName",provider.packageName).put("versionCode",pkg.getLongVersionCode()).put("lastUpdateTime",pkg.lastUpdateTime)
                .put("firstInstallTime",pkg.firstInstallTime).put("certificates",new JSONArray(certificates)).put("uid",provider.applicationInfo.uid)
                .put("userId",user).put("profileSerial",serial).put("authority",CalendarContract.AUTHORITY).put("scope","current_host_android_profile");
    }

    private static JSONObject calendar(Cursor row)throws Exception {
        if(row.isNull(0)||row.isNull(6)||row.isNull(8)||row.isNull(9))throw new IllegalStateException("系统日历身份、权限或可见性字段不完整");
        JSONObject calendar=new JSONObject();
        for(int i=0;i<CALENDAR_COLUMNS.length;i++){
            Object value=row.isNull(i)?JSONObject.NULL:(i==0||i==6||i==8||i==9?row.getLong(i):boundedText(row,i));
            calendar.put(CALENDAR_KEYS[i],value);
        }
        return calendar;
    }
    private static String boundedText(Cursor row,int column){String text=row.getString(column);if(text.length()>8192)throw new IllegalStateException("系统日历字段超出可完整核验范围");return text;}

    @Override public JSONObject dispatch(SemanticRequest request,Target target,RunControl control,Admission admission)throws Exception {
        if(!request.calendarProvider())return entries.dispatch(request,target,control,admission);
        if(!request.kind.equals("calendar_batch"))throw new SecurityException("日历列表只读，不能作为写入请求派发");
        final long admittedPauseRevision=control.pauseRevision();
        JSONObject p=request.parameters(),calendar=target.identity().getJSONObject("calendar");long calendarId=p.getLong("calendarId");
        if(calendar.getLong("calendarId")!=calendarId)throw new SecurityException("批次日历与已确认身份不一致");
        JSONArray events=p.getJSONArray("events"),items=new JSONArray();ArrayList<ContentProviderOperation> operations=new ArrayList<>();
        ContentValues expected=new ContentValues();
        for(int i=0;i<CALENDAR_COLUMNS.length;i++){
            Object value=calendar.get(CALENDAR_KEYS[i]);
            if(value==JSONObject.NULL)expected.putNull(CALENDAR_COLUMNS[i]);
            else if(value instanceof Number)expected.put(CALENDAR_COLUMNS[i],((Number)value).longValue());
            else expected.put(CALENDAR_COLUMNS[i],(String)value);
        }
        // The same provider rechecks the approved calendar before the first insert.
        operations.add(ContentProviderOperation.newAssertQuery(ContentUris.withAppendedId(CalendarContract.Calendars.CONTENT_URI,calendarId))
                .withValues(expected).withExpectedCount(1).build());
        for(int i=0;i<events.length();i++){
            JSONObject event=events.getJSONObject(i);operations.add(ContentProviderOperation.newInsert(CalendarContract.Events.CONTENT_URI).withValues(eventValues(event,calendarId)).build());
            items.put(new JSONObject().put("index",i).put("uri",JSONObject.NULL).put("accepted",JSONObject.NULL).put("readbackVerified",false));
        }
        JSONObject receipt=new JSONObject().put("accepted",JSONObject.NULL).put("status","unknown").put("code","calendar_batch_effect_unknown")
                .put("calendar",SemanticJson.copy(calendar)).put("requestedCount",events.length()).put("items",items)
                .put("providerReadbackVerified",false).put("cloudSyncVerified",false).put("verifiedBusinessSuccess",false)
                .put("requiresReconciliation",true).put("automaticReplayAllowed",false).put("writeEntered",false).put("scope","current_host_android_profile");
        SemanticDispatchGate entry=new SemanticDispatchGate();Runnable cancel=entry::cancelBeforeEntry;control.onCancel(cancel);
        try {
            AtomicReference<ContentProviderResult[]> returned=new AtomicReference<>();
            try{
                control.check();admission.check();requirePermissions(true);
                control.dispatchAtPauseRevision(admittedPauseRevision,()->{
                    if(!entry.enter())return;
                    receipt.put("writeEntered",true);
                    returned.set(context.getContentResolver().applyBatch(CalendarContract.AUTHORITY,operations));
                });
            }catch(Exception uncertain){
                if(!entry.entered())return notDispatched(receipt);
                return receipt.put("failureType",uncertain.getClass().getSimpleName())
                        .put("message","系统日历批处理已进入调用，但没有确定回执；可能已有部分或全部事件，须核对原批次，不会自动重发");
            }
            if(!entry.entered())return notDispatched(receipt);
            ContentProviderResult[] results=returned.get();
            boolean complete=results!=null&&results.length==operations.size()&&results[0]!=null&&Integer.valueOf(1).equals(results[0].count);Set<Long> ids=new HashSet<>();
            for(int i=0;i<events.length();i++){
                Uri uri=results!=null&&i+1<results.length&&results[i+1]!=null?results[i+1].uri:null;Long id=eventId(uri);
                if(id==null||!ids.add(id)){complete=false;continue;}
                items.getJSONObject(i).put("accepted",true).put("uri",uri.toString()).put("eventId",id);
            }
            receipt.put("providerReturned",true).put("acknowledgedCount",ids.size());
            if(!complete)return receipt.put("message","系统日历返回的事件回执不完整；已取得的URI已保留，其余写入状态待核对，不会重发本批次");
            // Preserve every acknowledged URI before any cancellable read or authority check.
            receipt.put("accepted",true).put("status","created").put("code","calendar_batch_accepted")
                    .put("requiresReconciliation",false).put("terminal",true).put("createdCount",events.length());
            boolean verified=true;
            for(int i=0;i<events.length();i++){
                JSONObject item=items.getJSONObject(i);
                try {
                    admission.check();JSONObject observed=readEvent(Uri.parse(item.getString("uri")),control);
                    boolean matches=matchesEvent(events.getJSONObject(i),observed,calendarId,item.getLong("eventId"));
                    item.put("readback",observed).put("readbackVerified",matches).put("readbackStatus",matches?"matched":"mismatch");verified&=matches;
                }catch(Exception unavailable){verified=false;item.put("readbackStatus","unavailable").put("readbackFailure",unavailable.getClass().getSimpleName());}
            }
            receipt.put("providerReadbackVerified",verified).put("requiresPostconditionCheck",!verified)
                    .put("message",verified?"系统日历已返回本批次全部事件URI，并逐项读回匹配已批准参数；云同步状态未核验。":"系统日历已返回本批次全部事件URI，但部分读回尚未核实；保留已执行回执，请核对这些URI，不会重发。");
            if(!verified)receipt.put("requiresPause",true).put("requiresOwnerVerification",true);
            return receipt;
        }finally{control.removeCancelAction(cancel);}
    }

    private static JSONObject notDispatched(JSONObject receipt)throws Exception {
        JSONArray items=receipt.getJSONArray("items");for(int i=0;i<items.length();i++)items.getJSONObject(i).put("accepted",false);
        return receipt.put("accepted",false).put("status","not_dispatched").put("code","calendar_batch_not_dispatched")
                .put("requiresReconciliation",false).put("requiresPause",true).put("message","原任务或日历权限在调用写入前变化，批次尚未派发");
    }

    private static ContentValues eventValues(JSONObject event,long calendarId)throws Exception {
        ContentValues values=new ContentValues();values.put(CalendarContract.Events.CALENDAR_ID,calendarId);
        values.put(CalendarContract.Events.TITLE,event.getString("title"));values.put(CalendarContract.Events.DTSTART,event.getLong("start"));values.put(CalendarContract.Events.DTEND,event.getLong("end"));
        values.put(CalendarContract.Events.EVENT_TIMEZONE,event.getString("timeZone"));values.put(CalendarContract.Events.EVENT_END_TIMEZONE,event.getString("timeZone"));
        values.put(CalendarContract.Events.ALL_DAY,event.getBoolean("allDay")?1:0);values.put(CalendarContract.Events.EVENT_LOCATION,event.getString("location"));
        values.put(CalendarContract.Events.DESCRIPTION,event.getString("description"));return values;
    }
    private static Long eventId(Uri uri){
        try{if(uri==null)return null;long id=ContentUris.parseId(uri);return id>0&&ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI,id).equals(uri)?id:null;}
        catch(RuntimeException invalid){return null;}
    }
    private JSONObject readEvent(Uri uri,RunControl control)throws Exception {
        control.check();requirePermissions(false);CancellationSignal signal=new CancellationSignal();Runnable cancel=signal::cancel;control.onCancel(cancel);
        try(Cursor row=context.getContentResolver().query(uri,EVENT_COLUMNS,null,null,null,signal)){
            if(row==null||!row.moveToFirst())throw new IllegalStateException("已返回URI的事件无法读回");
            JSONObject value=new JSONObject().put("eventId",row.getLong(0)).put("calendarId",row.getLong(1)).put("title",boundedText(row,2))
                    .put("start",row.getLong(3)).put("end",row.isNull(4)?JSONObject.NULL:row.getLong(4))
                    .put("timeZone",row.isNull(5)?JSONObject.NULL:boundedText(row,5)).put("endTimeZone",row.isNull(6)?JSONObject.NULL:boundedText(row,6))
                    .put("allDay",row.getInt(7)==1).put("location",row.isNull(8)?"":boundedText(row,8)).put("description",row.isNull(9)?"":boundedText(row,9))
                    .put("rrule",row.isNull(10)?"":boundedText(row,10)).put("rdate",row.isNull(11)?"":boundedText(row,11)).put("deleted",row.getInt(12)!=0);
            if(row.moveToNext())throw new IllegalStateException("单事件URI返回多行");control.check();return value;
        }finally{control.removeCancelAction(cancel);}
    }
    private static boolean matchesEvent(JSONObject expected,JSONObject actual,long calendarId,long eventId)throws Exception {
        if(actual.getLong("calendarId")!=calendarId||actual.getLong("eventId")!=eventId||actual.getBoolean("deleted")
                ||!actual.getString("rrule").isEmpty()||!actual.getString("rdate").isEmpty())return false;
        if(actual.isNull("end")||expected.getLong("start")!=actual.getLong("start")||expected.getLong("end")!=actual.getLong("end")
                ||expected.getBoolean("allDay")!=actual.getBoolean("allDay"))return false;
        for(String key:new String[]{"title","timeZone","location","description"})if(!expected.getString(key).equals(actual.optString(key)))return false;
        return expected.getString("timeZone").equals(actual.optString("endTimeZone"));
    }
}
