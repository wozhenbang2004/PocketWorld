package dev.pocketengine.features.automation;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;

/** Bounded calendar enumeration, not a timer or execution grant. A partial window leaves its
 * exact next occurrence pending; it never jumps to now and calls the uncovered past complete. */
final class AutomationMisfire {
    static final int MAX_WINDOW=64;
    record Window(List<Long> occurrences,long next,boolean coversNow){Window{occurrences=List.copyOf(occurrences);}}
    private AutomationMisfire(){}
    static Window enumerate(JSONObject schedule,long first,long now)throws Exception{
        if(first<0||now<0)throw new IllegalArgumentException("日历实例时间无效");List<Long> due=new ArrayList<>();long cursor=first;
        while(cursor>=0&&cursor<=now&&due.size()<MAX_WINDOW){
            due.add(cursor);long next=AutomationSchedule.nextAt(schedule,cursor);
            if(next>=0&&next<=cursor)throw new IllegalStateException("原日历未向后推进，未领取重复实例");cursor=next;
        }
        return new Window(due,cursor,cursor<0||cursor>now);
    }
}
