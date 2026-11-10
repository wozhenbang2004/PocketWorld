package dev.pocketengine.features.semantic;

import android.content.ContentProviderOperation;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.CancellationSignal;
import android.provider.CalendarContract;
import dev.pocketengine.core.RunControl;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import org.json.JSONArray;
import org.json.JSONObject;

/** Exact provider rows retained only in the original semantic run, never supplied by a model. */
final class CalendarEventRecord {
    static final int MAX_BYTES=131072;
    static final String[] EVENT_COLUMNS={CalendarContract.Events._ID,CalendarContract.Events.CALENDAR_ID,CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,CalendarContract.Events.DTEND,CalendarContract.Events.DURATION,CalendarContract.Events.EVENT_TIMEZONE,
            CalendarContract.Events.EVENT_END_TIMEZONE,CalendarContract.Events.ALL_DAY,CalendarContract.Events.EVENT_LOCATION,CalendarContract.Events.DESCRIPTION,
            CalendarContract.Events.RRULE,CalendarContract.Events.RDATE,CalendarContract.Events.EXRULE,CalendarContract.Events.EXDATE,CalendarContract.Events.DELETED,
            CalendarContract.Events.ORIGINAL_ID,CalendarContract.Events.ORIGINAL_SYNC_ID,CalendarContract.Events.ORIGINAL_INSTANCE_TIME,CalendarContract.Events.ORIGINAL_ALL_DAY,
            CalendarContract.Events.STATUS,CalendarContract.Events.ORGANIZER,CalendarContract.Events.ACCESS_LEVEL,CalendarContract.Events.AVAILABILITY,
            CalendarContract.Events.GUESTS_CAN_MODIFY,CalendarContract.Events.GUESTS_CAN_INVITE_OTHERS,CalendarContract.Events.GUESTS_CAN_SEE_GUESTS,
            CalendarContract.Events.HAS_ALARM,CalendarContract.Events.HAS_ATTENDEE_DATA,CalendarContract.Events.SELF_ATTENDEE_STATUS,CalendarContract.Events._SYNC_ID};
    static final String[] WORK_COLUMNS={CalendarContract.Events._ID,CalendarContract.Events.CALENDAR_ID,CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,CalendarContract.Events.DTEND,CalendarContract.Events.DURATION,CalendarContract.Events.EVENT_TIMEZONE,
            CalendarContract.Events.EVENT_END_TIMEZONE,CalendarContract.Events.ALL_DAY,CalendarContract.Events.EVENT_LOCATION,
            CalendarContract.Events.RRULE,CalendarContract.Events.RDATE,CalendarContract.Events.EXRULE,CalendarContract.Events.EXDATE,CalendarContract.Events.STATUS};
    private static final String[] REMINDER_COLUMNS={CalendarContract.Reminders._ID,CalendarContract.Reminders.EVENT_ID,CalendarContract.Reminders.MINUTES,CalendarContract.Reminders.METHOD};
    private static final String[] ATTENDEE_COLUMNS={CalendarContract.Attendees._ID,CalendarContract.Attendees.EVENT_ID,CalendarContract.Attendees.ATTENDEE_NAME,
            CalendarContract.Attendees.ATTENDEE_EMAIL,CalendarContract.Attendees.ATTENDEE_STATUS,CalendarContract.Attendees.ATTENDEE_RELATIONSHIP,
            CalendarContract.Attendees.ATTENDEE_TYPE,CalendarContract.Attendees.ATTENDEE_IDENTITY,CalendarContract.Attendees.ATTENDEE_ID_NAMESPACE};
    private CalendarEventRecord() { }

    static JSONObject read(Context context,long id,boolean work,RunControl control)throws Exception {
        Uri base=work?CalendarContract.Events.ENTERPRISE_CONTENT_URI:CalendarContract.Events.CONTENT_URI;
        JSONArray events=query(context,ContentUris.withAppendedId(base,id),work?WORK_COLUMNS:EVENT_COLUMNS,null,null,null,1,control);
        if(events.length()!=1)throw new IllegalStateException("刚发现的日历事件已经移除或不可读取，请重新列出");
        JSONObject row=events.getJSONObject(0);requireId(row,CalendarContract.Events._ID,id);
        if(row.isNull(CalendarContract.Events.CALENDAR_ID)||row.isNull(CalendarContract.Events.DTSTART)||row.isNull(CalendarContract.Events.ALL_DAY))throw new IllegalStateException("日历事件关键字段不完整");
        JSONObject snapshot=new JSONObject().put("row",row);
        if(!work){
            if(row.optLong(CalendarContract.Events.DELETED,1)!=0)throw new IllegalStateException("日历事件已删除");
            snapshot.put("reminders",query(context,CalendarContract.Reminders.CONTENT_URI,REMINDER_COLUMNS,CalendarContract.Reminders.EVENT_ID+"=?",new String[]{Long.toString(id)},CalendarContract.Reminders._ID+" ASC",64,control));
            snapshot.put("attendees",query(context,CalendarContract.Attendees.CONTENT_URI,ATTENDEE_COLUMNS,CalendarContract.Attendees.EVENT_ID+"=?",new String[]{Long.toString(id)},CalendarContract.Attendees._ID+" ASC",128,control));
            String selection=CalendarContract.Events.DELETED+"=0 AND "+CalendarContract.Events.CALENDAR_ID+"=? AND "+exceptionSelection(row);
            snapshot.put("exceptions",query(context,CalendarContract.Events.CONTENT_URI,EVENT_COLUMNS,selection,exceptionArgs(row),CalendarContract.Events._ID+" ASC",64,control));
        }
        requireBudget(snapshot);return snapshot;
    }

    static JSONArray query(Context context,Uri uri,String[] columns,String selection,String[] arguments,String order,int limit,RunControl control)throws Exception {
        control.check();CancellationSignal signal=new CancellationSignal();Runnable cancel=signal::cancel;control.onCancel(cancel);
        try(Cursor cursor=context.getContentResolver().query(uri,columns,selection,arguments,order,signal)){
            if(cursor==null)throw new IllegalStateException("系统日历读取未返回");JSONArray rows=new JSONArray();int bytes=0;
            while(cursor.moveToNext()){
                control.check();if(rows.length()>=limit)throw new IllegalStateException("单个日历事件关联内容超出完整核验范围，请在目标日历App操作");
                JSONObject row=new JSONObject();for(int i=0;i<columns.length;i++){
                    Object value;
                    if(cursor.isNull(i))value=JSONObject.NULL;
                    else if(cursor.getType(i)==Cursor.FIELD_TYPE_INTEGER)value=cursor.getLong(i);
                    else if(cursor.getType(i)==Cursor.FIELD_TYPE_STRING){String text=cursor.getString(i);if(text.length()>32768)throw new IllegalStateException("日历字段过长，无法完整核验");value=text;}
                    else throw new IllegalStateException("日历字段类型不受支持");
                    row.put(columns[i],value);
                }
                bytes+=row.toString().getBytes(StandardCharsets.UTF_8).length;if(bytes>MAX_BYTES)throw new IllegalStateException("日历来源超过完整核验预算，请在日历App操作");rows.put(row);
            }
            control.check();return rows;
        }finally{control.removeCancelAction(cancel);}
    }

    static JSONObject describe(JSONObject snapshot)throws Exception {
        JSONObject row=snapshot.getJSONObject("row");JSONObject out=new JSONObject();
        String[][] names={{"eventId",CalendarContract.Events._ID},{"calendarId",CalendarContract.Events.CALENDAR_ID},{"title",CalendarContract.Events.TITLE},
                {"start",CalendarContract.Events.DTSTART},{"end",CalendarContract.Events.DTEND},{"duration",CalendarContract.Events.DURATION},
                {"timeZone",CalendarContract.Events.EVENT_TIMEZONE},{"endTimeZone",CalendarContract.Events.EVENT_END_TIMEZONE},
                {"location",CalendarContract.Events.EVENT_LOCATION},{"description",CalendarContract.Events.DESCRIPTION},{"rrule",CalendarContract.Events.RRULE},
                {"rdate",CalendarContract.Events.RDATE},{"exrule",CalendarContract.Events.EXRULE},{"exdate",CalendarContract.Events.EXDATE},
                {"organizer",CalendarContract.Events.ORGANIZER},{"status",CalendarContract.Events.STATUS},{"originalEventId",CalendarContract.Events.ORIGINAL_ID},
                {"originalInstanceTime",CalendarContract.Events.ORIGINAL_INSTANCE_TIME}};
        for(String[] pair:names)if(row.has(pair[1]))out.put(pair[0],row.get(pair[1]));
        if(row.isNull(CalendarContract.Events.DTEND)){
            Long duration=CalendarRecurrence.durationMillis(text(row,CalendarContract.Events.DURATION));
            if(duration!=null)try{out.put("firstEndFromDuration",Math.addExact(row.getLong(CalendarContract.Events.DTSTART),duration));}catch(ArithmeticException outOfRange){/* Keep the original duration; never invent an end. */}
        }
        out.put("allDay",row.getInt(CalendarContract.Events.ALL_DAY)!=0).put("recurring",recurring(row)).put("contentIsUntrusted",true);
        if(snapshot.has("reminders"))out.put("reminders",new JSONArray(snapshot.getJSONArray("reminders").toString()))
                .put("attendeeCount",snapshot.getJSONArray("attendees").length()).put("exceptionCount",snapshot.getJSONArray("exceptions").length());
        return out;
    }

    static boolean recurring(JSONObject row){return !text(row,CalendarContract.Events.RRULE).isEmpty()||!text(row,CalendarContract.Events.RDATE).isEmpty();}
    static String text(JSONObject row,String column){return row.isNull(column)?"":row.optString(column,"");}
    static ContentValues values(JSONObject row)throws Exception {
        ContentValues values=new ContentValues();for(java.util.Iterator<String> it=row.keys();it.hasNext();){String key=it.next();Object value=row.get(key);
            if(value==JSONObject.NULL)values.putNull(key);else if(value instanceof Number)values.put(key,((Number)value).longValue());else if(value instanceof String)values.put(key,(String)value);else throw new IllegalArgumentException("日历断言字段类型无效");}
        return values;
    }

    static void appendAssertions(ArrayList<ContentProviderOperation> operations,JSONObject snapshot)throws Exception {
        JSONObject event=snapshot.getJSONObject("row");long id=event.getLong(CalendarContract.Events._ID);
        operations.add(ContentProviderOperation.newAssertQuery(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI,id)).withValues(values(event)).withExpectedCount(1).build());
        appendRelated(operations,CalendarContract.Reminders.CONTENT_URI,CalendarContract.Reminders.EVENT_ID+"=?",new String[]{Long.toString(id)},snapshot.getJSONArray("reminders"));
        appendRelated(operations,CalendarContract.Attendees.CONTENT_URI,CalendarContract.Attendees.EVENT_ID+"=?",new String[]{Long.toString(id)},snapshot.getJSONArray("attendees"));
        appendRelated(operations,CalendarContract.Events.CONTENT_URI,CalendarContract.Events.DELETED+"=0 AND "+CalendarContract.Events.CALENDAR_ID+"=? AND "+exceptionSelection(event),exceptionArgs(event),snapshot.getJSONArray("exceptions"));
    }
    private static void appendRelated(ArrayList<ContentProviderOperation> operations,Uri uri,String selection,String[] arguments,JSONArray rows)throws Exception {
        operations.add(ContentProviderOperation.newAssertQuery(uri).withSelection(selection,arguments).withExpectedCount(rows.length()).build());
        for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);operations.add(ContentProviderOperation.newAssertQuery(ContentUris.withAppendedId(uri,row.getLong("_id"))).withValues(values(row)).withExpectedCount(1).build());}
    }
    private static String exceptionSelection(JSONObject event){return "("+CalendarContract.Events.ORIGINAL_ID+"=?"+(text(event,CalendarContract.Events._SYNC_ID).isEmpty()?"":" OR "+CalendarContract.Events.ORIGINAL_SYNC_ID+"=?")+")";}
    private static String[] exceptionArgs(JSONObject event)throws Exception {
        String calendar=Long.toString(event.getLong(CalendarContract.Events.CALENDAR_ID)),id=Long.toString(event.getLong(CalendarContract.Events._ID)),sync=text(event,CalendarContract.Events._SYNC_ID);
        return sync.isEmpty()?new String[]{calendar,id}:new String[]{calendar,id,sync};
    }
    static boolean absentOrDeleted(Context context,long id,RunControl control)throws Exception {
        JSONArray rows=query(context,ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI,id),new String[]{CalendarContract.Events._ID,CalendarContract.Events.DELETED},null,null,null,1,control);
        return rows.length()==0||rows.getJSONObject(0).getLong(CalendarContract.Events._ID)==id&&rows.getJSONObject(0).getInt(CalendarContract.Events.DELETED)!=0;
    }
    private static void requireId(JSONObject row,String column,long expected)throws Exception {if(row.isNull(column)||row.getLong(column)!=expected)throw new SecurityException("日历返回了不同事件身份");}
    private static void requireBudget(JSONObject snapshot){if(snapshot.toString().getBytes(StandardCharsets.UTF_8).length>MAX_BYTES)throw new IllegalStateException("日历事件及关联记录超过128KiB，不能截断后审批；请在日历App操作");}
}
