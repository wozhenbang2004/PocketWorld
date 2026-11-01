package dev.pocketengine.features.automation;

import android.Manifest;
import android.app.Application;
import android.app.NotificationManager;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.os.BatteryManager;
import dev.pocketengine.features.FeatureContext;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowNetworkInfo;
import org.robolectric.shadows.ShadowNetworkCapabilities;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class AndroidAutomationConditionsTest {
    private Application app(){return RuntimeEnvironment.getApplication();}
    private void battery(int level,int scale,int status){app().sendStickyBroadcast(new Intent(Intent.ACTION_BATTERY_CHANGED).putExtra(BatteryManager.EXTRA_LEVEL,level).putExtra(BatteryManager.EXTRA_SCALE,scale).putExtra(BatteryManager.EXTRA_STATUS,status).putExtra(BatteryManager.EXTRA_PRESENT,true));}
    private Network connected(boolean validated){
        ConnectivityManager manager=app().getSystemService(ConnectivityManager.class);
        shadowOf(manager).setActiveNetworkInfo(ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.CONNECTED,ConnectivityManager.TYPE_WIFI,0,true,true));
        Network network=manager.getActiveNetwork();assertNotNull(network);NetworkCapabilities caps=ShadowNetworkCapabilities.newInstance();shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        if(validated)shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);shadowOf(manager).setNetworkCapabilities(network,caps);return network;
    }
    @Test public void missingNetworkPermissionIsUnavailableWhileBatteryIsIndependentlyObserved()throws Exception {
        shadowOf(app()).denyPermissions(Manifest.permission.ACCESS_NETWORK_STATE);battery(1,2,BatteryManager.BATTERY_STATUS_CHARGING);
        AutomationConditions.Snapshot value=new AndroidAutomationConditions(app()).read(System.currentTimeMillis());assertEquals("unavailable",value.networkState);assertEquals("permission_missing",value.networkReason);assertEquals(Integer.valueOf(50),value.batteryPercent);assertEquals(Boolean.TRUE,value.charging);
    }
    @Test public void validatedDefaultNetworkIsDistinctFromUnvalidatedAndDisconnected()throws Exception {
        shadowOf(app()).grantPermissions(Manifest.permission.ACCESS_NETWORK_STATE);battery(100,100,BatteryManager.BATTERY_STATUS_FULL);connected(false);
        AndroidAutomationConditions source=new AndroidAutomationConditions(app());assertEquals("unvalidated",source.read(System.currentTimeMillis()).networkState);connected(true);assertEquals("validated",source.read(System.currentTimeMillis()).networkState);
        shadowOf(app().getSystemService(ConnectivityManager.class)).setActiveNetworkInfo(null);assertEquals("disconnected",source.read(System.currentTimeMillis()).networkState);
    }
    @Test public void incompleteBatteryBroadcastDoesNotInventPercentOrCharging()throws Exception {
        battery(120,100,BatteryManager.BATTERY_STATUS_UNKNOWN);AutomationConditions.Snapshot value=new AndroidAutomationConditions(app()).read(System.currentTimeMillis());assertNull(value.batteryPercent);assertNull(value.charging);assertEquals("battery_data_incomplete",value.batteryReason);
        app().sendStickyBroadcast(new Intent(Intent.ACTION_BATTERY_CHANGED).putExtra(BatteryManager.EXTRA_PRESENT,false).putExtra(BatteryManager.EXTRA_LEVEL,80).putExtra(BatteryManager.EXTRA_SCALE,100).putExtra(BatteryManager.EXTRA_STATUS,BatteryManager.BATTERY_STATUS_CHARGING));
        value=new AndroidAutomationConditions(app()).read(System.currentTimeMillis());assertNull(value.batteryPercent);assertNull(value.charging);assertEquals("battery_absent",value.batteryReason);
    }
    @Test public void actualModuleUsesSameAlarmGateAndPostsOnlyAfterConditionsRecover()throws Exception {
        shadowOf(app()).denyPermissions(Manifest.permission.ACCESS_NETWORK_STATE);battery(90,100,BatteryManager.BATTERY_STATUS_CHARGING);
        AutomationModule module=new AutomationModule(new FeatureContext(app(),null,null,event->{}));
        module.handle("save",new JSONObject().put("rule",new JSONObject().put("title","等网络恢复").put("schedule",new JSONObject().put("type","once").put("at",System.currentTimeMillis()-1000)).put("policy",new JSONObject().put("requiresNetwork",true))));
        assertEquals(0,module.handle("checkDue",new JSONObject()).getJSONArray("receipts").length());assertEquals(0,app().getSystemService(NotificationManager.class).getActiveNotifications().length);
        assertEquals("unavailable",module.conditionsSnapshot().getString("networkState"));shadowOf(app()).grantPermissions(Manifest.permission.ACCESS_NETWORK_STATE);connected(true);module.conditionsChanged();
        assertEquals(1,module.handle("checkDue",new JSONObject()).getJSONArray("receipts").length());assertEquals(1,app().getSystemService(NotificationManager.class).getActiveNotifications().length);assertNull(shadowOf(app()).getNextStartedActivity());
    }
}
