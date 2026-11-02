package dev.pocketengine.phone.setup;

import android.app.RemoteInput;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

/** Private PendingIntent target; stale or expired setup windows cannot trigger pairing. */
public final class PhoneSetupReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context,Intent intent){if(intent==null)return;PhoneSetupModule module=PhoneSetupModule.current();if(module==null)return;
        if("dev.pocketengine.PAIR_CODE".equals(intent.getAction())){Bundle values=RemoteInput.getResultsFromIntent(intent);CharSequence code=values==null?null:values.getCharSequence("pairing_code");if(code==null)return;module.submitNotification(intent.getStringExtra("pairingSessionId"),code.toString());}
    }
}
