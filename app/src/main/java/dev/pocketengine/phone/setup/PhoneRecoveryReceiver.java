package dev.pocketengine.phone.setup;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Protected OS lifecycle signals enter the same bounded native setup worker, never the task runner. */
public final class PhoneRecoveryReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())
                && !Intent.ACTION_USER_UNLOCKED.equals(intent.getAction())
                && !Intent.ACTION_USER_PRESENT.equals(intent.getAction())) return;
        PhoneSetupModule module = PhoneSetupModule.current();
        if (module == null) return;
        PendingResult result = goAsync();
        module.recoverAfterLifecycle(result::finish);
    }
}
