package dev.pocketengine.displayhelper;

import android.content.Context;
import org.json.JSONObject;

/** Finite local device facts; no content, mutation, permission grant or shell command. */
public final class PhoneDeviceState {
    private PhoneDeviceState(){ }
    public static JSONObject read(Context context)throws Exception {
        JSONObject out=new JSONObject().put("sdkInt",android.os.Build.VERSION.SDK_INT);
        android.os.PowerManager power=context.getSystemService(android.os.PowerManager.class);android.app.KeyguardManager lock=context.getSystemService(android.app.KeyguardManager.class);
        out.put("interactive",power==null?JSONObject.NULL:power.isInteractive()).put("powerSaveMode",power==null?JSONObject.NULL:power.isPowerSaveMode())
                .put("deviceLocked",lock==null?JSONObject.NULL:lock.isDeviceLocked()).put("keyguardLocked",lock==null?JSONObject.NULL:lock.isKeyguardLocked());
        android.os.BatteryManager battery=context.getSystemService(android.os.BatteryManager.class);
        if(battery!=null){int level=battery.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY);out.put("batteryPercent",level>=0&&level<=100?level:JSONObject.NULL).put("charging",battery.isCharging());}
        return out;
    }
}
