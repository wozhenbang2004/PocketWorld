package dev.pocketengine.phone.setup;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** System lifecycle trigger restores only previously consented connection settings, never a task or input. */
public final class PhoneRecoveryReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context,Intent intent){if(intent==null||!java.util.Set.of(Intent.ACTION_BOOT_COMPLETED,Intent.ACTION_USER_UNLOCKED).contains(intent.getAction()))return;PendingResult result=goAsync();new Thread(()->{try{new AccessibilityRecovery(context).restore();}catch(Exception ignored){}finally{result.finish();}},"phone-consented-recovery").start();}
}
