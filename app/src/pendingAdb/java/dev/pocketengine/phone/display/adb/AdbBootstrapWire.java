package dev.pocketengine.phone.display.adb;

import android.os.IBinder;

/** Private in-app Binder protocol. Neither secrets nor arbitrary shell commands enter public feature events. */
final class AdbBootstrapWire {
    static final String SERVICE="dev.pocketengine.adb.bootstrap.v1";
    static final String CALLBACK="dev.pocketengine.adb.bootstrap.callback.v1";
    static final int REQUEST=IBinder.FIRST_CALL_TRANSACTION,CANCEL=REQUEST+1,READY=REQUEST+2,INFO=REQUEST+3;
    static final int EVENT=IBinder.FIRST_CALL_TRANSACTION;
    private AdbBootstrapWire(){}
}
