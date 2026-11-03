package dev.pocketengine.phone.setup;

import android.app.RemoteInput;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

/** Explicit, non-exported PendingIntent target. The nonce URI and extra must agree before a one-shot claim. */
public final class PhoneSetupReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getData() == null) return;
        String action = intent.getAction();
        if (!PhoneSetupModule.SUBMIT.equals(action) && !PhoneSetupModule.CANCEL.equals(action)) return;
        String session = intent.getStringExtra(PhoneSetupModule.SESSION);
        if (session == null || !session.matches("[a-f0-9-]{36}")
                || !intent.getData().toString().equals("pocketworld-internal://pair/" + session)) return;
        PhoneSetupModule module = PhoneSetupModule.current();
        if (module == null) return;
        String code = null;
        if (PhoneSetupModule.SUBMIT.equals(action)) {
            Bundle values = RemoteInput.getResultsFromIntent(intent);
            CharSequence raw = values == null ? null : values.getCharSequence(PhoneSetupModule.CODE);
            if (raw == null || raw.length() != 6) return;
            code = raw.toString();
        }
        PendingResult result = goAsync();
        module.receiveNotification(action, session, code, result::finish);
    }
}
