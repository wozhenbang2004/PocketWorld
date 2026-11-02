package dev.pocketengine.phone.observation;
import android.content.Context;
import android.content.pm.PackageInfo;
import org.json.JSONObject;
/** Native package-manager evidence for the app actually present in this observation. */
public final class InstalledAppEvidence {
 private InstalledAppEvidence(){}
 public static String currentVersion(Context context,String packageName)throws Exception {
  if(packageName==null||!packageName.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+"))return null;
  try{PackageInfo p=context.getPackageManager().getPackageInfo(packageName,0);return packageName.equals(p.packageName)?p.getLongVersionCode()+"@"+p.lastUpdateTime:null;}catch(android.content.pm.PackageManager.NameNotFoundException missing){return null;}
 }
 public static void attach(Context context,JSONObject observation)throws Exception {
  String app=observation.optString("packageName");if(!app.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+"))return;
  try{PackageInfo p=context.getPackageManager().getPackageInfo(app,0);if(!app.equals(p.packageName))return;
   observation.put("installedApp",new JSONObject().put("packageName",app).put("versionCode",p.getLongVersionCode()).put("versionName",p.versionName==null?"":p.versionName).put("lastUpdateTime",p.lastUpdateTime).put("source","android_package_manager"));
  }catch(android.content.pm.PackageManager.NameNotFoundException unavailable){ /* Unknown is not the requested launch package. */ }
 }
}
