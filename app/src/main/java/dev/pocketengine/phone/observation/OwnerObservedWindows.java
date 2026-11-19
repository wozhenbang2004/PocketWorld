package dev.pocketengine.phone.observation;

import android.view.accessibility.AccessibilityWindowInfo;
import java.util.HashSet;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/** Describe an already accepted local forest. Its primary package is a ranking, not evidence of a single foreground app.
 * Presentation only; do not expose a window id, other packages, app copies, controls or new information to the model. */
final class OwnerObservedWindows {
    private static final Pattern PACKAGE = Pattern.compile("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+");
    private OwnerObservedWindows() { }
    private static int number(JSONObject row,String key) {
        Object v=row.opt(key);
        if(v instanceof Integer i && i>=0)return i;
        if(v instanceof Long l && l>=0 && l<=Integer.MAX_VALUE)return l.intValue();
        return -1;
    }
    static void describe(JSONObject original,int display,JSONObject owner)throws Exception {
        JSONArray windows=original.optJSONArray("windows");JSONObject coverage=original.optJSONObject("treeCoverage");
        if(display<0||number(original,"treeSchemaVersion")!=1||windows==null||coverage==null||windows.length()<1||windows.length()>32||
                number(coverage,"windowCount")!=windows.length()||number(coverage,"omittedWindows")<0)return;
        var ids=new HashSet<Integer>();String first=null;boolean different=false,unknown=false,appActive=false,otherActive=false;
        for(int i=0;i<windows.length();i++){
            JSONObject w=windows.optJSONObject(i);if(w==null||number(w,"displayId")!=display)return;
            int wid=number(w,"windowId"),type=number(w,"type");Object active=w.opt("active"),focus=w.opt("focused");
            if(wid<0||!ids.add(wid)||type<1||!(active instanceof Boolean)||!(focus instanceof Boolean))return;
            boolean selected=Boolean.TRUE.equals(active)||Boolean.TRUE.equals(focus);
            if(type!=AccessibilityWindowInfo.TYPE_APPLICATION){otherActive|=selected;continue;}
            Object value=w.opt("packageName");
            // Android's framework package may be a second real app window, but is never fabricated as an installed target.
            if(!(value instanceof String pkg)||pkg.length()>255||!("android".equals(pkg)||PACKAGE.matcher(pkg).matches())){unknown=true;continue;}
            if(first==null)first=pkg;else if(!first.equals(pkg))different=true;
            appActive|=selected;
        }
        // Positive mixed-window facts do not depend on every other app being readable.
        if(different){owner.put("windowDisposition","several_apps");return;}
        if(otherActive){owner.put("windowDisposition","non_app_active");return;}
        if(first==null||unknown||!appActive||number(coverage,"omittedWindows")!=0||!first.equals(original.opt("packageName")))return;
        JSONObject installed=original.optJSONObject("installedApp");
        if(installed!=null&&"android_package_manager".equals(installed.opt("source"))&&first.equals(installed.opt("packageName"))&&PACKAGE.matcher(first).matches())
            owner.put("windowDisposition","one_app").put("packageName",first);
    }
}
