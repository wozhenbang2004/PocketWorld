package dev.pocketengine.features.automation;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Short, bounded local disk/notification work; no background model or UI execution. */
public final class AutomationReceiver extends BroadcastReceiver {
    private static final ExecutorService WORKER=Executors.newSingleThreadExecutor(r->{Thread thread=new Thread(r,"pocket-reminders");thread.setDaemon(true);return thread;});
    private static final AtomicBoolean RUNNING=new AtomicBoolean();
    @Override public void onReceive(Context context,Intent intent){
        if(intent==null)return;String action=intent.getAction();
        if(!AutomationModule.ALARM_ACTION.equals(action)&&!Intent.ACTION_BOOT_COMPLETED.equals(action)
                &&!Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)&&!Intent.ACTION_TIME_CHANGED.equals(action)&&!Intent.ACTION_TIMEZONE_CHANGED.equals(action))return;
        // Coalesce broadcasts instead of queueing multiple goAsync deadlines behind a slow host.
        if(!RUNNING.compareAndSet(false,true))return;
        PendingResult pending=goAsync();Context app=context.getApplicationContext();
        WORKER.execute(()->{try{
            if(AutomationModule.ALARM_ACTION.equals(action))AutomationModule.deliver(app);else AutomationModule.restore(app);
        }catch(Exception ignored){/* Persistent claims remain unknown or pending; opening the app can inspect them. */}
        finally{RUNNING.set(false);pending.finish();}});
    }
}
