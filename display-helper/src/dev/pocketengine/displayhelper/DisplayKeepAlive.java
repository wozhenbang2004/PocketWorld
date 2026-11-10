package dev.pocketengine.displayhelper;

import android.os.IBinder;
import android.os.SystemClock;
import android.view.Display;
import java.lang.reflect.Method;

/** Periodic activity belongs only to a verified helper-owned, separate display group. */
final class DisplayKeepAlive {
    private static final long INTERVAL_MS=4000;
    private Object power;
    private Method activity;
    private int displayId=-1;
    private long lastActivity;
    private String state="unavailable",reason="display_not_created";

    void configure(Display owned){
        clear();
        try{
            if(owned==null||!owned.isValid()||owned.getDisplayId()<=0)throw new IllegalStateException();
            int ownGroup=group(owned.getDisplayId()),primaryGroup=group(Display.DEFAULT_DISPLAY);
            if(ownGroup<=0||ownGroup==primaryGroup){reason="separate_display_group_unconfirmed";return;}
            Object binder=Class.forName("android.os.ServiceManager").getMethod("getService",String.class).invoke(null,"power");
            power=Class.forName("android.os.IPowerManager$Stub").getMethod("asInterface",IBinder.class).invoke(null,binder);
            activity=Class.forName("android.os.IPowerManager").getMethod("userActivity",int.class,long.class,int.class,int.class);
            displayId=owned.getDisplayId();state="ready";reason="per_display_activity_ready";
            tick(owned);
        }catch(Exception unavailable){disable("per_display_activity_unavailable");}
    }

    void tick(Display owned){
        if(activity==null||displayId<=0)return;
        long now=SystemClock.uptimeMillis();
        if(lastActivity!=0&&now-lastActivity<INTERVAL_MS)return;
        try{
            if(owned==null||!owned.isValid()||owned.getDisplayId()!=displayId)throw new IllegalStateException();
            int ownGroup=group(displayId),primaryGroup=group(Display.DEFAULT_DISPLAY);
            if(ownGroup<=0||ownGroup==primaryGroup){disable("separate_display_group_lost");return;}
            // This exact overload names a display. The global overload is never a substitute.
            activity.invoke(power,displayId,now,2,0);
            lastActivity=now;state="available";reason="owned_display_group_activity_accepted";
        }catch(Exception unavailable){disable("per_display_activity_unavailable");}
    }

    private static int group(int id)throws Exception {
        Class<?> globalType=Class.forName("android.hardware.display.DisplayManagerGlobal");
        Object global=globalType.getMethod("getInstance").invoke(null);
        Object info=globalType.getMethod("getDisplayInfo",int.class).invoke(global,id);
        if(info==null)throw new IllegalStateException();
        return Class.forName("android.view.DisplayInfo").getField("displayGroupId").getInt(info);
    }

    String state(){return state;}
    String reason(){return reason;}
    void clear(){power=null;activity=null;displayId=-1;lastActivity=0;state="unavailable";reason="display_not_created";}
    private void disable(String detail){clear();reason=detail;}
}
