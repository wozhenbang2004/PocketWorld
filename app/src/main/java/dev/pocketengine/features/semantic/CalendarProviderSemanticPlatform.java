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

/** Exact host-profile writes and policy-gated enterprise reads inside the original semantic run. */
final class CalendarProviderSemanticPlatform implements SemanticPlatform {
    private static final int PAGE_SIZE=32;
    private static final class EventPage extends ArrayList<Target> {boolean incomplete;}
    static boolean incomplete(List<Target> values){return values instanceof EventPage&&((EventPage)values).incomplete;}
    private static final String[] CALENDAR_COLUMNS={CalendarContract.Calendars._ID,CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.ACCOUNT_TYPE,CalendarContract.Calendars.OWNER_ACCOUNT,CalendarContract.Calendars.NAME,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
            CalendarContract.Calendars.CALENDAR_TIME_ZONE,CalendarContract.Calendars.VISIBLE,CalendarContract.Calendars.DELETED};
    private static final String[] CALENDAR_KEYS={"calendarId","accountName","accountType","ownerAccount","name","displayName","accessLevel","timeZone","visible","deleted"};
    private static final String[] EVENT_COLUMNS={CalendarContract.Events._ID,CalendarContract.Events.CALENDAR_ID,CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,CalendarContract.Events.DTEND,CalendarContract.Events.EVENT_TIMEZONE,
            CalendarContract.Events.EVENT_END_TIMEZONE,CalendarContract.Events.ALL_DAY,CalendarContract.Events.EVENT_LOCATION,
            CalendarContract.Events.DESCRIPTION,CalendarContract.Events.RRULE,CalendarContract.Events.RDATE,CalendarContract.Events.DELETED,CalendarContract.Events.DURATION};
    private static final String[] WORK_CALENDAR_COLUMNS={CalendarContract.Calendars._ID,CalendarContract.Calendars.CALENDAR_TIME_ZONE,
            CalendarContract.Calendars.VISIBLE,CalendarContract.Calendars.CALENDAR_LOCATION,CalendarContract.Calendars.IS_PRIMARY};
    private static final String[] WORK_CALENDAR_KEYS={"calendarId","timeZone","visible","location","isPrimary"};
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
    static final class EventChanged extends SecurityException {EventChanged(String message){super(message);}}
    static final class Unchanged extends Exception {Unchanged(){super("请求的完整事件内容与刚读取的原事件一致，无需写入，也未请求审批。");}}
    static final class WorkProfileUnavailable extends Exception {
        final String failureType;
        WorkProfileUnavailable(Exception cause){super("工作资料日历当前无法读取，可能是日历已变化、资料关闭，或管理员及系统设置未允许。需工作资料管理员允许此App且主人开启跨资料日历；也可在原手机任务选定工作资料的日历App进行页面操作。");failureType=cause.getClass().getSimpleName();}
    }

    private void requirePermissions(boolean write)throws Exception {
        JSONObject state=permissions(context);
        if(!state.getBoolean("readGranted")||write&&!state.getBoolean("writeGranted"))throw new PermissionRequired(state);
    }

    @Override public List<Target> discover(SemanticRequest request,RunControl control)throws Exception {
        if(!request.calendarProvider())return entries.discover(request,control);
        if(request.calendarMutation())throw new SecurityException("修改或删除必须绑定原执行刚发现的事件引用");
        control.check();requirePermissions(request.calendarWrite());JSONObject provider=providerIdentity();
        if(request.workCalendar())provider=workProvider(provider);
        try{
            if(request.kind.equals("calendar_events"))return discoverEvents(request,provider,control);
            return discoverCalendars(request,provider,control);
        }catch(SecurityException|UnsupportedOperationException|IllegalArgumentException unavailable){
            control.check();if(!request.workCalendar())throw unavailable;throw new WorkProfileUnavailable(unavailable);
        }
    }

    private List<Target> discoverCalendars(SemanticRequest request,JSONObject provider,RunControl control)throws Exception {
        JSONObject p=request.parameters();boolean batch=request.kind.equals("calendar_batch");
        boolean work=request.workCalendar();String selection=work?CalendarContract.Calendars._ID+">?":CalendarContract.Calendars.DELETED+"=0 AND "
                +CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL+">=? AND "+CalendarContract.Calendars._ID+(batch?"=?":">?");
        String[] args=work?new String[]{Long.toString(p.getLong("afterCalendarId"))}:new String[]{Integer.toString(batch?CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR:CalendarContract.Calendars.CAL_ACCESS_READ),Long.toString(p.getLong(batch?"calendarId":"afterCalendarId"))};
        List<Target> targets=new ArrayList<>();CancellationSignal signal=new CancellationSignal();Runnable cancel=signal::cancel;control.onCancel(cancel);
        try(Cursor rows=context.getContentResolver().query(work?CalendarContract.Calendars.ENTERPRISE_CONTENT_URI:CalendarContract.Calendars.CONTENT_URI,work?WORK_CALENDAR_COLUMNS:CALENDAR_COLUMNS,selection,args,CalendarContract.Calendars._ID+" ASC",signal)){
            if(rows==null)throw new IllegalStateException("系统日历列表未返回，请检查日历服务");
            while(rows.moveToNext()){
                control.check();JSONObject calendar=work?workCalendar(rows):calendar(rows);long id=calendar.getLong("calendarId");
                if(id<=0||id>9_007_199_254_740_991L)continue;
                JSONObject identity=SemanticJson.copy(provider).put("calendar",calendar);
                targets.add(new Target(identity,work?"工作资料日历 "+id:calendar.optString("displayName","日历"),"calendar_provider"));
                if(targets.size()>PAGE_SIZE)break;
            }
        }finally{control.removeCancelAction(cancel);}
        control.check();return targets;
    }

    private List<Target> discoverEvents(SemanticRequest request,JSONObject provider,RunControl control)throws Exception {
        JSONObject p=request.parameters();long calendarId=p.getLong("calendarId");boolean work=request.workCalendar();
        JSONObject calendar=readCalendar(calendarId,work,control);
        if(!work&&calendar.getLong("accessLevel")<CalendarContract.Calendars.CAL_ACCESS_READ)return List.of();
        Uri.Builder builder=(work?CalendarContract.Instances.ENTERPRISE_CONTENT_URI:CalendarContract.Instances.CONTENT_URI).buildUpon();
        ContentUris.appendId(builder,p.getLong("start"));ContentUris.appendId(builder,p.getLong("end")-1);
        String selection=CalendarContract.Instances.CALENDAR_ID+"=? AND "+CalendarContract.Instances.EVENT_ID+">? AND "+CalendarContract.Instances.BEGIN+"<? AND ("+CalendarContract.Instances.END+">? OR ("+CalendarContract.Instances.END+"="+CalendarContract.Instances.BEGIN+" AND "+CalendarContract.Instances.BEGIN+">=?))";
        String[] args={Long.toString(calendarId),Long.toString(p.getLong("afterEventId")),Long.toString(p.getLong("end")),Long.toString(p.getLong("start")),Long.toString(p.getLong("start"))};
        EventPage targets=new EventPage();Set<Long> seen=new HashSet<>();CancellationSignal signal=new CancellationSignal();Runnable cancel=signal::cancel;control.onCancel(cancel);
        try(Cursor rows=context.getContentResolver().query(builder.build(),new String[]{CalendarContract.Instances.EVENT_ID,CalendarContract.Instances.BEGIN,CalendarContract.Instances.END},selection,args,CalendarContract.Instances.EVENT_ID+" ASC, "+CalendarContract.Instances.BEGIN+" ASC",signal)){
            if(rows==null)throw new IllegalStateException("系统日历实例查询未返回");int scanned=0;
            while(rows.moveToNext()){
                control.check();if(scanned++>=4096){targets.incomplete=true;break;}
                long id=rows.getLong(0);if(id<=0||id>9_007_199_254_740_991L||!seen.add(id))continue;
                JSONObject state=CalendarEventRecord.read(context,id,work,control);
                if(state.getJSONObject("row").getLong(CalendarContract.Events.CALENDAR_ID)!=calendarId)throw new EventChanged("实例所属日历已变化，请重新发现");
                JSONObject identity=SemanticJson.copy(provider).put("calendar",SemanticJson.copy(calendar)).put("eventState",state)
                        .put("instanceEvidence",new JSONObject().put("begin",rows.getLong(1)).put("end",rows.getLong(2)).put("rangeStart",p.getLong("start")).put("rangeEnd",p.getLong("end")));
                targets.add(new Target(identity,state.getJSONObject("row").optString(CalendarContract.Events.TITLE,"日历事件"),"calendar_provider_event"));
                if(targets.size()>PAGE_SIZE)break;
            }
        }finally{control.removeCancelAction(cancel);}
        control.check();return targets;
    }

    @Override public List<Target> discover(SemanticRequest request,Target source,RunControl control)throws Exception {
        if(!request.calendarMutation())return SemanticPlatform.super.discover(request,source,control);
        if(source==null||source.identity().optBoolean("workCalendar")||!source.identity().has("eventState"))throw new EventChanged("更新或删除需要当前宿主资料的事件引用；工作资料请在原任务选择对应日历App副本进行页面操作");
        requireCurrent(request,source,control);
        if(request.kind.equals("calendar_update")&&updateValues(request.parameters().getJSONObject("event"),source.identity().getJSONObject("eventState").getJSONObject("row")).size()==0)throw new Unchanged();
        return List.of(source);
    }

    @Override public void requireCurrent(SemanticRequest request,Target target,RunControl control)throws Exception {
        if(!request.calendarMutation()){SemanticPlatform.super.requireCurrent(request,target,control);return;}
        control.check();requirePermissions(true);JSONObject identity=target.identity(),old=identity.getJSONObject("eventState"),row=old.getJSONObject("row");
        if(identity.optBoolean("workCalendar"))throw new EventChanged("企业日历只读引用不能用于写入");
        JSONObject provider=providerIdentity(),calendar=readCalendar(row.getLong(CalendarContract.Events.CALENDAR_ID),false,control);
        if(calendar.getLong("accessLevel")<CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR)throw new EventChanged("日历当前不允许写入");
        JSONObject now=SemanticJson.copy(provider).put("calendar",calendar).put("eventState",CalendarEventRecord.read(context,row.getLong(CalendarContract.Events._ID),false,control));
        if(identity.has("instanceEvidence"))now.put("instanceEvidence",SemanticJson.copy(identity.getJSONObject("instanceEvidence")));
        if(!new Target(now,"",target.route).fingerprint.equals(target.fingerprint))throw new EventChanged("日历、事件或关联提醒/参与人/例外已变化，请重新读取并审批");
        requireMutationScope(request,old);control.check();
    }

    @Override public JSONObject resourceIdentity()throws Exception{return entries.resourceIdentity();}
    @Override public JSONObject resourceIdentity(SemanticRequest request)throws Exception {
        if(!request.calendarProvider())return entries.resourceIdentity(request);
        requirePermissions(request.calendarWrite());
        return new JSONObject().put("executionMode","host_calendar_provider").put("provider",providerIdentity())
                .put("permissions",permissions(context)).put("crossProfile",request.workCalendar())
                .put("scope",request.workCalendar()?"managed_profile_enterprise_read_only":"current_host_android_profile").put("writesUnsupported",request.workCalendar());
    }

    private static JSONObject workProvider(JSONObject provider)throws Exception {
        JSONObject result=SemanticJson.copy(provider);result.put("queriedViaHostProvider",SemanticJson.copy(provider));
        result.remove("userId");result.remove("profileSerial");result.remove("uid");
        return result.put("workCalendar",true).put("scope","managed_profile_enterprise_read_only");
    }

    private JSONObject readCalendar(long id,boolean work,RunControl control)throws Exception {
        Uri uri=ContentUris.withAppendedId(work?CalendarContract.Calendars.ENTERPRISE_CONTENT_URI:CalendarContract.Calendars.CONTENT_URI,id);
        CancellationSignal signal=new CancellationSignal();Runnable cancel=signal::cancel;control.onCancel(cancel);
        try(Cursor row=context.getContentResolver().query(uri,work?WORK_CALENDAR_COLUMNS:CALENDAR_COLUMNS,null,null,null,signal)){
            control.check();if(row==null||!row.moveToFirst())throw new EventChanged("日历已移除、不可读取或工作资料策略未允许此App");
            JSONObject calendar=work?workCalendar(row):calendar(row);
            if(calendar.getLong("calendarId")!=id||row.moveToNext()||!work&&calendar.getLong("deleted")!=0)throw new EventChanged("日历身份已变化");
            return calendar;
        }finally{control.removeCancelAction(cancel);}
    }

    private static JSONObject workCalendar(Cursor row)throws Exception {
        if(row.isNull(0))throw new IllegalStateException("工作资料日历ID不可读取");JSONObject calendar=new JSONObject();
        for(int i=0;i<WORK_CALENDAR_COLUMNS.length;i++)calendar.put(WORK_CALENDAR_KEYS[i],row.isNull(i)?JSONObject.NULL:i==1||i==3?boundedText(row,i):row.getLong(i));
        return calendar.put("writable",false).put("namesAndAccountsNotExposedByEnterpriseApi",true);
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
        if(request.calendarMutation())return dispatchMutation(request,target,control,admission);
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
                .put("calendar",SemanticJson.copy(calendar)).put("requestedCount",events.length()).put("countScope","event_rows_not_occurrences").put("items",items)
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
                    admission.afterDispatch();JSONObject observed=readEvent(Uri.parse(item.getString("uri")),control);
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
        return eventValues(event,calendarId,!event.optString("rrule","").isEmpty());
    }
    private static ContentValues eventValues(JSONObject event,long calendarId,boolean recurring)throws Exception {
        ContentValues values=new ContentValues();values.put(CalendarContract.Events.CALENDAR_ID,calendarId);
        values.put(CalendarContract.Events.TITLE,event.getString("title"));values.put(CalendarContract.Events.DTSTART,event.getLong("start"));
        if(recurring){values.putNull(CalendarContract.Events.DTEND);values.put(CalendarContract.Events.DURATION,CalendarRecurrence.duration(event));}
        else{values.put(CalendarContract.Events.DTEND,event.getLong("end"));values.putNull(CalendarContract.Events.DURATION);}
        if(event.has("rrule"))values.put(CalendarContract.Events.RRULE,event.getString("rrule"));
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
                    .put("rrule",row.isNull(10)?"":boundedText(row,10)).put("rdate",row.isNull(11)?"":boundedText(row,11)).put("deleted",row.getInt(12)!=0)
                    .put("duration",row.isNull(13)?"":boundedText(row,13));
            if(row.moveToNext())throw new IllegalStateException("单事件URI返回多行");control.check();return value;
        }finally{control.removeCancelAction(cancel);}
    }
    private static boolean matchesEvent(JSONObject expected,JSONObject actual,long calendarId,long eventId)throws Exception {
        if(actual.getLong("calendarId")!=calendarId||actual.getLong("eventId")!=eventId||actual.getBoolean("deleted")
                ||!expected.optString("rrule","").equals(actual.getString("rrule"))||!actual.getString("rdate").isEmpty())return false;
        if(expected.getLong("start")!=actual.getLong("start")||expected.getBoolean("allDay")!=actual.getBoolean("allDay"))return false;
        if(expected.optString("rrule","").isEmpty()){
            if(actual.isNull("end")||expected.getLong("end")!=actual.getLong("end")||!actual.getString("duration").isEmpty())return false;
        }else if(!actual.isNull("end")||!CalendarRecurrence.durationMatches(actual.getString("duration"),expected))return false;
        for(String key:new String[]{"title","timeZone","location","description"})if(!expected.getString(key).equals(actual.optString(key)))return false;
        return expected.getString("timeZone").equals(actual.optString("endTimeZone"));
    }

    private static void requireMutationScope(SemanticRequest request,JSONObject snapshot)throws Exception {
        JSONObject p=request.parameters(),row=snapshot.getJSONObject("row");boolean series=CalendarEventRecord.recurring(row);
        if(request.kind.equals("calendar_update")){
            JSONObject event=p.getJSONObject("event");String rule=event.has("rrule")?event.getString("rrule"):CalendarEventRecord.text(row,CalendarContract.Events.RRULE);
            series|=!rule.isEmpty()||!CalendarEventRecord.text(row,CalendarContract.Events.RDATE).isEmpty();
            if(!rule.isEmpty()||!CalendarEventRecord.text(row,CalendarContract.Events.RDATE).isEmpty())CalendarRecurrence.duration(event);
            boolean timesChanged=event.getLong("start")!=row.getLong(CalendarContract.Events.DTSTART)
                    ||event.getBoolean("allDay")!=(row.getInt(CalendarContract.Events.ALL_DAY)!=0)
                    ||!event.getString("timeZone").equals(CalendarEventRecord.text(row,CalendarContract.Events.EVENT_TIMEZONE))
                    ||!rule.equals(CalendarEventRecord.text(row,CalendarContract.Events.RRULE));
            if(CalendarEventRecord.recurring(row))timesChanged|=!CalendarRecurrence.durationMatches(CalendarEventRecord.text(row,CalendarContract.Events.DURATION),event);
            else timesChanged|=row.isNull(CalendarContract.Events.DTEND)||event.getLong("end")!=row.getLong(CalendarContract.Events.DTEND);
            if(timesChanged&&snapshot.getJSONArray("exceptions").length()>0)
                throw new EventChanged("此重复系列已有单次例外；修改时间或重复规则可能改变例外归属，请在原任务选定的日历App中完成。标题、地点和描述可按原范围更新。");
            if(rule.isEmpty()&&CalendarEventRecord.text(row,CalendarContract.Events.RDATE).isEmpty()
                    &&(!CalendarEventRecord.text(row,CalendarContract.Events.EXRULE).isEmpty()||!CalendarEventRecord.text(row,CalendarContract.Events.EXDATE).isEmpty()))
                throw new EventChanged("此系列含独立排除规则，不能在保留这些规则的同时清除全部重复来源；请在原日历App中明确处理。");
        }
        if(!p.getString("recurrenceScope").equals(series?"series":"event"))throw new EventChanged(series?"这是重复系列，需明确recurrenceScope=series并审批整个系列；实例时间不是单次修改授权":"这是单个事件或已存在的单次例外，请使用recurrenceScope=event");
    }

    private JSONObject dispatchMutation(SemanticRequest request,Target target,RunControl control,Admission admission)throws Exception {
        final long pauseRevision=control.pauseRevision();JSONObject p=request.parameters(),identity=target.identity(),calendar=identity.getJSONObject("calendar"),state=identity.getJSONObject("eventState"),before=state.getJSONObject("row");
        if(identity.optBoolean("workCalendar"))throw new SecurityException("工作资料企业日历URI仅支持读取");
        requireMutationScope(request,state);long eventId=before.getLong(CalendarContract.Events._ID),calendarId=before.getLong(CalendarContract.Events.CALENDAR_ID);
        boolean deleting=request.kind.equals("calendar_delete");Uri uri=ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI,eventId);
        ArrayList<ContentProviderOperation> operations=new ArrayList<>();ContentValues expectedCalendar=new ContentValues();
        for(int i=0;i<CALENDAR_COLUMNS.length;i++){Object value=calendar.get(CALENDAR_KEYS[i]);if(value==JSONObject.NULL)expectedCalendar.putNull(CALENDAR_COLUMNS[i]);else if(value instanceof Number)expectedCalendar.put(CALENDAR_COLUMNS[i],((Number)value).longValue());else expectedCalendar.put(CALENDAR_COLUMNS[i],(String)value);}
        operations.add(ContentProviderOperation.newAssertQuery(ContentUris.withAppendedId(CalendarContract.Calendars.CONTENT_URI,calendarId)).withValues(expectedCalendar).withExpectedCount(1).build());
        CalendarEventRecord.appendAssertions(operations,state);ContentValues replacement=null;
        if(deleting)operations.add(ContentProviderOperation.newDelete(uri).withExpectedCount(1).build());
        else{
            replacement=updateValues(p.getJSONObject("event"),before);
            operations.add(ContentProviderOperation.newUpdate(uri).withValues(replacement).withExpectedCount(1).build());
        }
        JSONArray items=new JSONArray().put(new JSONObject().put("index",0).put("uri",uri.toString()).put("eventId",eventId).put("accepted",JSONObject.NULL).put("readbackVerified",false));
        JSONObject receipt=new JSONObject().put("accepted",JSONObject.NULL).put("status","unknown").put("code","calendar_mutation_effect_unknown")
                .put("action",deleting?"delete":"update").put("recurrenceScope",p.getString("recurrenceScope")).put("calendar",SemanticJson.copy(calendar)).put("items",items)
                .put("before",CalendarEventRecord.describe(state)).put("providerReadbackVerified",false).put("cloudSyncVerified",false).put("verifiedBusinessSuccess",false)
                .put("requiresReconciliation",true).put("automaticReplayAllowed",false).put("writeEntered",false).put("scope","current_host_android_profile");
        SemanticDispatchGate entry=new SemanticDispatchGate();Runnable cancel=entry::cancelBeforeEntry;control.onCancel(cancel);
        try{
            AtomicReference<ContentProviderResult[]> returned=new AtomicReference<>();
            try{
                control.check();admission.check();requirePermissions(true);
                control.dispatchAtPauseRevision(pauseRevision,()->{if(!entry.enter())return;receipt.put("writeEntered",true);returned.set(context.getContentResolver().applyBatch(CalendarContract.AUTHORITY,operations));});
            }catch(Exception unknown){
                if(!entry.entered())return mutationNotDispatched(receipt);
                return receipt.put("failureType",unknown.getClass().getSimpleName()).put("message","准确事件的修改或删除已进入系统调用，但没有确定回执；请核对原事件，不会自动重发。");
            }
            if(!entry.entered())return mutationNotDispatched(receipt);
            ContentProviderResult[] results=returned.get();boolean complete=results!=null&&results.length==operations.size();
            if(complete)for(ContentProviderResult result:results)if(result==null||result.count==null){complete=false;break;}
            complete&=results!=null&&results.length>0&&results[results.length-1]!=null&&Integer.valueOf(1).equals(results[results.length-1].count);
            receipt.put("providerReturned",true);
            if(!complete)return receipt.put("message","日历服务没有返回完整的单事件修改/删除数量回执；请核对原事件，不会重发。");
            JSONObject item=items.getJSONObject(0);item.put("accepted",true);
            receipt.put("accepted",true).put("status",deleting?"deleted":"updated").put("code",deleting?"calendar_delete_accepted":"calendar_update_accepted")
                    .put("requiresReconciliation",false).put("terminal",true).put("changedCount",1);
            boolean verified=false;
            try{
                admission.afterDispatch();requirePermissions(false);
                if(deleting){
                    verified=CalendarEventRecord.absentOrDeleted(context,eventId,control);JSONArray exceptions=state.getJSONArray("exceptions"),childReceipts=new JSONArray();
                    for(int i=0;i<exceptions.length();i++){
                        admission.afterDispatch();long childId=exceptions.getJSONObject(i).getLong(CalendarContract.Events._ID);boolean removed=CalendarEventRecord.absentOrDeleted(context,childId,control);
                        childReceipts.put(new JSONObject().put("eventId",childId).put("absentOrDeleted",removed));verified&=removed;
                    }
                    item.put("absenceOrDeletedVerified",verified).put("seriesExceptions",childReceipts);
                }else{
                    JSONObject observed=CalendarEventRecord.read(context,eventId,false,control);verified=matchesUpdate(p.getJSONObject("event"),state,observed,replacement);
                    item.put("readback",CalendarEventRecord.describe(observed));
                }
                item.put("readbackVerified",verified).put("readbackStatus",verified?"matched":"mismatch");
            }catch(Exception unavailable){item.put("readbackStatus","unavailable").put("readbackFailure",unavailable.getClass().getSimpleName());}
            receipt.put("providerReadbackVerified",verified).put("requiresPostconditionCheck",!verified)
                    .put("message",verified?(deleting?"系统日历已确认删除准确事件，并读回确认主事件及已知系列例外已移除或标为删除；云端同步与取消通知未核验。":"系统日历已确认更新准确事件，读回匹配，并确认未请求的提醒、参与人和例外记录保留；云端同步未核验。"):
                            "系统日历已返回准确事件的修改/删除回执，但读回尚未完整匹配；保留原操作和事件URI，请主人核对，不会重发。");
            if(!verified)receipt.put("requiresPause",true).put("requiresOwnerVerification",true);
            return receipt;
        }finally{control.removeCancelAction(cancel);}
    }

    private static JSONObject mutationNotDispatched(JSONObject receipt)throws Exception {
        receipt.getJSONArray("items").getJSONObject(0).put("accepted",false);
        return receipt.put("accepted",false).put("status","not_dispatched").put("code","calendar_mutation_not_dispatched").put("requiresReconciliation",false)
                .put("requiresPause",true).put("message","原任务、事件版本或日历权限在进入写入前变化，本次修改/删除尚未派发");
    }

    private static boolean matchesUpdate(JSONObject event,JSONObject before,JSONObject after,ContentValues replacement)throws Exception {
        JSONObject previous=before.getJSONObject("row"),current=after.getJSONObject("row");
        for(java.util.Iterator<String> keys=previous.keys();keys.hasNext();){
            String key=keys.next();Object expected=replacement.containsKey(key)?replacement.get(key):previous.get(key);Object actual=current.opt(key);
            if(key.equals(CalendarContract.Events.DURATION)&&expected instanceof String){if(!(actual instanceof String)||!CalendarRecurrence.durationMatches((String)actual,event))return false;}
            else if(expected==null||expected==JSONObject.NULL){if(actual!=JSONObject.NULL&&actual!=null)return false;}
            else if(expected instanceof Number&&actual instanceof Number){if(((Number)expected).longValue()!=((Number)actual).longValue())return false;}
            else if(!expected.equals(actual))return false;
        }
        for(String key:new String[]{"reminders","attendees","exceptions"})
            if(!SemanticJson.hash(new JSONObject().put("rows",before.getJSONArray(key))).equals(SemanticJson.hash(new JSONObject().put("rows",after.getJSONArray(key)))))return false;
        return true;
    }

    private static ContentValues updateValues(JSONObject event,JSONObject before)throws Exception {
        String rule=event.has("rrule")?event.getString("rrule"):CalendarEventRecord.text(before,CalendarContract.Events.RRULE);
        ContentValues values=eventValues(event,before.getLong(CalendarContract.Events.CALENDAR_ID),!rule.isEmpty()||!CalendarEventRecord.text(before,CalendarContract.Events.RDATE).isEmpty());
        values.remove(CalendarContract.Events.CALENDAR_ID);
        // Text/time edits do not implicitly replace a distinct arrival/end zone.
        if(event.getString("timeZone").equals(CalendarEventRecord.text(before,CalendarContract.Events.EVENT_TIMEZONE))
                &&event.getBoolean("allDay")==(before.optInt(CalendarContract.Events.ALL_DAY)==1))
            values.remove(CalendarContract.Events.EVENT_END_TIMEZONE);
        for(String key:new ArrayList<>(values.keySet())){
            Object requested=values.get(key),original=before.opt(key);
            if((requested==null&&(original==null||original==JSONObject.NULL))||requested!=null&&requested.equals(original))values.remove(key);
            else if(requested instanceof Number&&original instanceof Number&&((Number)requested).longValue()==((Number)original).longValue())values.remove(key);
            else if(key.equals(CalendarContract.Events.DURATION)&&requested instanceof String&&original instanceof String&&CalendarRecurrence.durationMatches((String)original,event))values.remove(key);
            else if(key.equals(CalendarContract.Events.RRULE)&&requested instanceof String&&((String)requested).isEmpty()&&(original==null||original==JSONObject.NULL))values.remove(key);
        }
        return values;
    }
}
