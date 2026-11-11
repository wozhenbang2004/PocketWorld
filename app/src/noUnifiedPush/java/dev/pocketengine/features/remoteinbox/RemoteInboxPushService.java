package dev.pocketengine.features.remoteinbox;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

/** Disabled manifest placeholder for the standard build, which has no push SDK or exported receiver. */
public final class RemoteInboxPushService extends Service {
    @Override public IBinder onBind(Intent intent) { return null; }
}
