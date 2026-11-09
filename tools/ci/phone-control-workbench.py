from pathlib import Path
import json

ROOT = Path(__file__).resolve().parents[2]
JAVA = 'app/src/main/java/dev/pocketengine/'

def replace(path, old, new):
    target = ROOT / path
    text = target.read_text()
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f'{path}: expected exactly one anchor, got {count}: {old[:100]}')
    target.write_text(text.replace(old, new))

def write(path, content):
    target = ROOT / path
    if target.exists():
        raise RuntimeError(f'Refusing to overwrite new file: {path}')
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(content)

write(JAVA + 'phone/PhoneServiceConnection.java', '''package dev.pocketengine.phone;
import org.json.JSONObject;
/** Registration is not a live service binding; these observations grant no authority. */
public final class PhoneServiceConnection {
    private PhoneServiceConnection() { }
    public static JSONObject describe(Object registered, Object connected) throws Exception {
        boolean live = Boolean.TRUE.equals(connected), enabled = Boolean.TRUE.equals(registered), disabled = Boolean.FALSE.equals(registered);
        String state = live ? "connected" : enabled ? "enabled_disconnected" : disabled ? "not_enabled" : "unknown";
        String code = live ? "" : enabled ? "accessibility_binding_pending" : disabled ? "accessibility_not_enabled" : "accessibility_disconnected";
        String message = live ? "界面读取服务已连接" : enabled ? "无障碍已开启，但当前服务连接尚未就绪；请刷新连接状态，不必重复授权。" : disabled ? "请在系统设置中开启 PocketWorld 手机操作无障碍服务。" : "当前无法确认界面读取服务的连接状态；请刷新检查，不会将未知状态当作未授权。";
        return new JSONObject().put("state", state).put("code", code).put("message", message)
                .put("registered", registered instanceof Boolean ? registered : JSONObject.NULL)
                .put("connected", connected instanceof Boolean ? connected : JSONObject.NULL)
                .put("action", live ? "" : disabled ? "accessibility" : "retry").put("ready", live).put("observedOnly", true);
    }
}
''')
replace(JAVA+'phone/AndroidPhoneReadiness.java', 'import android.content.Context;', 'import android.content.Context;\nimport android.content.ComponentName;\nimport android.provider.Settings;')
replace(JAVA+'phone/AndroidPhoneReadiness.java', '        PhoneAccessibilityService service = PhoneAccessibilityService.get();', '        PhoneAccessibilityService service = PhoneAccessibilityService.get();\n        facts.put("serviceRegistered", serviceRegistered(context));')
replace(JAVA+'phone/AndroidPhoneReadiness.java', '    private static void fact(JSONObject facts, String key, Probe probe) throws Exception {', '''    public static Object serviceRegistered(Context context) {
        try {
            if (Settings.Secure.getInt(context.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 0) != 1) return false;
            ComponentName own = new ComponentName(context, PhoneAccessibilityService.class);
            String services = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (services != null) for (String item : services.split(":")) if (own.equals(ComponentName.unflattenFromString(item))) return true;
            return false;
        } catch (RuntimeException unavailable) { return JSONObject.NULL; }
    }
    public static String serviceUnavailableMessage(Context context) {
        try { return PhoneServiceConnection.describe(serviceRegistered(context), false).getString("message"); }
        catch (Exception unavailable) { return "界面读取服务暂未连接，请刷新连接状态后继续原任务。"; }
    }
    private static void fact(JSONObject facts, String key, Probe probe) throws Exception {''')
replace(JAVA+'phone/PhoneReadiness.java', '        return new JSONObject().put("observedOnly", true).put("deviceVerified", false)', '        return new JSONObject().put("observedOnly", true).put("deviceVerified", false)\n                .put("accessibility", PhoneServiceConnection.describe(facts.opt("serviceRegistered"), facts.opt("serviceConnected")))')
replace(JAVA+'phone/PhoneReadiness.java', '''        String state = state(facts.opt(key));
        return new JSONObject().put("id", key)''', '''        String state = state(facts.opt(key));
        if ("serviceConnected".equals(key) && !"ready".equals(state)) {
            JSONObject connection = PhoneServiceConnection.describe(facts.opt("serviceRegistered"), facts.opt(key));
            return new JSONObject().put("id", key).put("state", state).put("code", connection.getString("code"))
                    .put("action", connection.getString("action")).put("message", connection.getString("message"));
        }
        return new JSONObject().put("id", key)''')
replace(JAVA+'EngineApplication.java', 'if("main_screen".equals(executionMode)&&PhoneAccessibilityService.get()==null)throw new IllegalStateException("请先开启 PocketEngine 手机操作无障碍服务");', 'if(PhoneAccessibilityService.get()==null)throw new IllegalStateException(dev.pocketengine.phone.AndroidPhoneReadiness.serviceUnavailableMessage(this));')
replace(JAVA+'EngineApplication.java', 'if("main_screen".equals(mode)&&PhoneAccessibilityService.get()==null)throw new IllegalStateException("请先开启手机操作无障碍服务");', 'if(PhoneAccessibilityService.get()==null)throw new IllegalStateException(dev.pocketengine.phone.AndroidPhoneReadiness.serviceUnavailableMessage(this));')

# Remaining patch stages contain independently reviewable source and regression tests.
for stage in ('phone-control-capture.py', 'phone-control-recovery.py', 'phone-control-tests.py'):
    source = ROOT / 'tools/ci' / stage
    exec(compile(source.read_text(), str(source), 'exec'), globals())
