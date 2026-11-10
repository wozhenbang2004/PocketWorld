package dev.pocketengine.phone.setup;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Protected OS lifecycle signals enter the same bounded native setup worker, never the task runner. */
public final class PhoneRecoveryReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        PhoneSetupModule module = PhoneSetupModule.current();
        if (module == null) return;
        if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction()) || Intent.ACTION_USER_BACKGROUND.equals(intent.getAction())) {
            module.stopRecovery();
            return;
        }
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())
                && !Intent.ACTION_USER_UNLOCKED.equals(intent.getAction())
                && !Intent.ACTION_USER_PRESENT.equals(intent.getAction())) return;
        PendingResult result = goAsync();
        module.recoverAfterLifecycle(result::finish);
    }
}
