package dev.pocketengine.features.automation;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.BatteryManager;

/** Read-only platform adapter. No network request, callback registration, wake or phone action. */
public final class AndroidAutomationConditions implements AutomationConditions.Source {
    private final Context context;
    public AndroidAutomationConditions(Context context){this.context=context.getApplicationContext();}
    @Override public AutomationConditions.Snapshot read(long now){
        String network="unavailable",networkReason="service_unavailable",batteryReason="battery_unavailable";Boolean charging=null;Integer percent=null;
        try{
            if(context.checkSelfPermission(Manifest.permission.ACCESS_NETWORK_STATE)!=PackageManager.PERMISSION_GRANTED)networkReason="permission_missing";
            else{
                ConnectivityManager manager=context.getSystemService(ConnectivityManager.class);
                if(manager!=null){Network active=manager.getActiveNetwork();
                    if(active==null){network="disconnected";networkReason="no_default_network";}
                    else{NetworkCapabilities caps=manager.getNetworkCapabilities(active);
                        if(caps==null)networkReason="capabilities_unavailable";
                        else if(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)&&caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)){network="validated";networkReason="system_validated";}
                        else{network="unvalidated";networkReason="not_system_validated";}
                    }
                }
            }
        }catch(SecurityException denied){network="unavailable";networkReason="permission_missing";}
        catch(RuntimeException unavailable){network="unavailable";networkReason="service_unavailable";}
        try{
            Intent battery=context.registerReceiver(null,new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if(battery!=null){
                boolean present=battery.getBooleanExtra(BatteryManager.EXTRA_PRESENT,true);
                int state=battery.getIntExtra(BatteryManager.EXTRA_STATUS,-1);
                switch(present?state:-1){
                    case BatteryManager.BATTERY_STATUS_CHARGING:case BatteryManager.BATTERY_STATUS_FULL:charging=true;break;
                    case BatteryManager.BATTERY_STATUS_DISCHARGING:case BatteryManager.BATTERY_STATUS_NOT_CHARGING:charging=false;break;
                    default:break;
                }
                int level=battery.getIntExtra(BatteryManager.EXTRA_LEVEL,-1),scale=battery.getIntExtra(BatteryManager.EXTRA_SCALE,-1);
                if(present&&scale>0&&level>=0&&level<=scale)percent=(int)((long)level*100/scale);
                batteryReason=!present?"battery_absent":charging==null||percent==null?"battery_data_incomplete":"system_battery_broadcast";
            }
        }catch(RuntimeException unavailable){batteryReason="battery_unavailable";}
        return new AutomationConditions.Snapshot(now,network,networkReason,charging,percent,batteryReason);
    }
}
