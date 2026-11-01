package dev.pocketengine.features.local;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import org.json.JSONObject;

/** Explicit, non-exported receiver reached only through a per-operation one-shot PendingIntent. */
public final class TermuxResultReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context,Intent intent){
        PendingResult pending=goAsync();new Thread(()->{try{JSONObject job=TermuxBridge.receive(context,intent);if(job!=null&&context.getApplicationContext() instanceof dev.pocketengine.EngineApplication){JSONObject event=new JSONObject().put("type","feature_event").put("feature","local").put("payload",new JSONObject().put("kind","job").put("job",job));((dev.pocketengine.EngineApplication)context.getApplicationContext()).publish(event);}}catch(Exception ignored){}finally{pending.finish();}},"pocket-termux-result").start();
    }
}
