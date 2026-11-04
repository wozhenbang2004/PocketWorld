package dev.pocketengine.features.devices;

import dev.pocketengine.core.PhonePort;
import dev.pocketengine.core.RunControl;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** An owner-selected phone scope over the actual run-bound PhonePort. This class has no
 * executor, retry loop, task store or source of authorization. Native admission is retained. */
public final class DeviceGoalPhonePort implements PhonePort {
    @FunctionalInterface public interface Current { void check() throws Exception; }
    private final PhonePort physical;
    private final RunControl original;
    private final Current current;
    private final JSONObject binding;
    private final Set<String> packages = new HashSet<>(), tools = new HashSet<>();
    private final dev.pocketengine.phone.access.PhoneOwnerPackages.Probe nativeUsers;
    private JSONObject observed;
    private String sessionId;
    private int displayId = -1;

    public DeviceGoalPhonePort(PhonePort physical, DeviceExecutionAdmission admission, RunControl original) throws Exception {
        this(physical, admission, original, null);
    }
    public DeviceGoalPhonePort(PhonePort physical, DeviceExecutionAdmission admission, RunControl original,dev.pocketengine.phone.access.PhoneOwnerPackages.Probe nativeUsers) throws Exception {
        this(physical, admission.request().resourceBinding(), admission.toolNames(), original, admission::checkCurrent,nativeUsers);
        if (admission.kind() != DeviceExecutionAdmission.Kind.GOAL) throw new SecurityException("需要原设备目标准入");
    }
    /** Called only by native owners retaining their opaque admission; serialized scope is not authority. */
    public DeviceGoalPhonePort(PhonePort physical, JSONObject nativeBinding, JSONArray nativeTools,
            RunControl original, Current current) throws Exception {
        this(physical,nativeBinding,nativeTools,original,current,null);
    }
    private DeviceGoalPhonePort(PhonePort physical, JSONObject nativeBinding, JSONArray nativeTools,RunControl original,Current current,dev.pocketengine.phone.access.PhoneOwnerPackages.Probe nativeUsers)throws Exception {
        this.nativeUsers=nativeUsers;
        this.physical = java.util.Objects.requireNonNull(physical);
        this.original = java.util.Objects.requireNonNull(original);
        this.current = java.util.Objects.requireNonNull(current);
        this.binding = DeviceProtocol.validateResourceBinding(DeviceProtocol.VERSION, nativeBinding);
        if (!original.hasHostRun() || !binding.optString("screenMode").matches("main_screen|background"))
            throw new SecurityException("手机目标必须绑定原运行和主人选择的显示范围");
        // This owner review currently exposes only user 0. Launch still requires an actual
        // native app-copy reference; the ordinary package-only lane is not a profile proof.
        if (binding.optInt("androidUserId", -1) != 0) throw new SecurityException("此范围尚无准确的原生 Android 用户启动入口");
        JSONArray names = binding.getJSONArray("packageNames");
        for (int i = 0; i < names.length(); i++) packages.add(names.getString(i));
        for (int i = 0; i < nativeTools.length(); i++) {
            String name = nativeTools.getString(i);
            if (DeviceToolsRuntime.supports(name) && (!DeviceToolsRuntime.requiresWholeDisplayInput(name)
                    || binding.optBoolean("allowWholeDisplayInput", false))) tools.add(name);
        }
        if (!tools.contains("phone_observe")) throw new SecurityException("手机目标必须有明确授权的观察工具");
        original.onCancel(this::clearReferences);
        check(original);
    }
    private void check(RunControl control) throws Exception {
        if (control != original || original.isCancelled() || original.isPaused()) throw new SecurityException("原手机目标已暂停或停止");
        original.check(); current.check();
        if (!physical.isConnected()) throw new SecurityException("原显示连接已失效，不切换屏幕");
    }
    private void tool(String name, RunControl control) throws Exception {
        check(control); if (!tools.contains(name)) throw new SecurityException("工具不在主人选择的手机范围");
    }
    @Override public boolean isConnected() {
        try { check(original); return true; } catch (Exception unavailable) { return false; }
    }
    private void identity(JSONObject observation) throws Exception {
        String session = observation.getString("sessionId"); int display = observation.getInt("displayId");
        if (session.isEmpty() || ("background".equals(binding.getString("screenMode")) ? display <= 0 : display != 0))
            throw new SecurityException("原生观察不属于已选屏幕");
        if (sessionId == null) { sessionId = session; displayId = display; }
        else if (!sessionId.equals(session) || displayId != display) throw new SecurityException("原显示身份已变化");
        if (observation.has("androidUserId") && observation.getInt("androidUserId") != binding.getInt("androidUserId"))
            throw new SecurityException("原观察的 Android 用户与主人选择不一致");
    }
    @Override public synchronized JSONObject observe(boolean includeImage, RunControl control) throws Exception {
        tool("phone_observe", control); observed = null;
        boolean imageAllowed = includeImage && binding.optBoolean("allowWholeDisplayCapture", false);
        dev.pocketengine.phone.access.PhoneOwnerPackages before=nativeUsers==null?null:nativeUsers.read(original);
        JSONObject raw = physical.observe(imageAllowed, original); check(control);
        if(nativeUsers!=null&&raw.optBoolean("ok")&&packages.contains(raw.optString("packageName"))){
            var requested=new dev.pocketengine.phone.access.AppCopyKey(raw.getString("packageName"),binding.getInt("androidUserId"));var after=nativeUsers.read(original);
            if(before!=null&&before.provesCopy(requested)&&after.provesCopy(requested)&&before.owners().equals(after.owners()))raw.put("actualUserVerified",true).put("androidUserId",requested.userId());
        }
        if (!Boolean.TRUE.equals(raw.opt("ok"))) return raw;
        identity(raw);
        JSONObject result = new JSONObject();
        for (String key : new String[]{"ok","observationId","sessionId","displayId","frameId","treeFingerprint",
                "width","height","coordinateWidth","coordinateHeight","rotation","capturedAt","truncated","treeCoverage","observationSources"})
            if (raw.has(key)) result.put(key, raw.get(key));
        JSONArray windows = new JSONArray(), nodes = new JSONArray(); Set<Integer> allowedWindows = new HashSet<>();
        JSONArray sourceWindows = raw.optJSONArray("windows"), sourceNodes = raw.optJSONArray("nodes");
        for (int i = 0; sourceWindows != null && i < sourceWindows.length(); i++) {
            JSONObject window = sourceWindows.getJSONObject(i);
            if (packages.contains(window.optString("packageName"))) { windows.put(window); allowedWindows.add(window.getInt("windowId")); }
        }
        for (int i = 0; sourceNodes != null && i < sourceNodes.length(); i++) {
            JSONObject node = sourceNodes.getJSONObject(i);
            if (allowedWindows.contains(node.optInt("windowId", -1)) && (!node.has("packageName") || packages.contains(node.optString("packageName")))) nodes.put(node);
        }
        boolean userVerified=raw.optBoolean("actualUserVerified")&&raw.optInt("androidUserId",-1)==binding.getInt("androidUserId");
        boolean foregroundAllowed = userVerified&&packages.contains(raw.optString("packageName"));
        if(!userVerified){windows=new JSONArray();nodes=new JSONArray();}
        result.put("windows", windows).put("nodes", nodes).put("packageName", foregroundAllowed ? raw.optString("packageName") : "")
                .put("deviceScopeFiltered", true).put("actualUserVerified",userVerified)
                .put("allowedPackages", new JSONArray(binding.getJSONArray("packageNames").toString()));
        if (!foregroundAllowed) result.put("scopeNotice", "当前前台应用不在范围；只能打开主人选定的应用，不能操作此页面。");
        if (imageAllowed) { for (String key : new String[]{"imageBase64","imageMimeType","imageWidth","imageHeight"}) if (raw.has(key)) result.put(key, raw.get(key)); }
        else result.put("scopeImageNotice", "此原生范围未授权整屏截图，只提供所选应用的节点。");
        observed = new JSONObject(result.toString()); return result;
    }
    @Override public synchronized JSONObject execute(String name, JSONObject input, RunControl control) throws Exception {
        tool(name, control); JSONObject args = new JSONObject(input.toString());
        if (name.equals("phone_observe")) return observe(args.optBoolean("includeImage"), control);
        if (name.equals("phone_list_apps")) {
            JSONObject result = physical.execute(name, new JSONObject(), control); check(control);
            JSONArray selected = new JSONArray(), apps = result.optJSONArray("apps");
            for (int i = 0; apps != null && i < apps.length(); i++) if (packages.contains(apps.getJSONObject(i).optString("packageName"))) selected.put(apps.get(i));
            return new JSONObject().put("ok", result.optBoolean("ok")).put("apps", selected).put("deviceScopeFiltered", true);
        }
        if (name.equals("phone_launch_app")) {
            if (!packages.contains(args.optString("packageName"))) throw new SecurityException("目标应用不在原生范围");
            args = new JSONObject().put("packageName", args.getString("packageName"));
            if(!(physical instanceof dev.pocketengine.phone.observation.NativePhoneExtensions extensions))throw new SecurityException("原手机端口没有准确 App 副本入口");
            {
                JSONObject listed=extensions.extension("phone_list_app_copies",new JSONObject(),original,null);JSONArray copies=listed.optJSONArray("apps");JSONObject selected=null;
                for(int i=0;copies!=null&&i<copies.length();i++){JSONObject copy=copies.getJSONObject(i);if(args.getString("packageName").equals(copy.optString("packageName"))&&binding.getInt("androidUserId")==copy.optInt("userId",-1)){selected=new JSONObject().put("copyRef",copy.getString("copyRef")).put("packageName",copy.getString("packageName")).put("userId",copy.getInt("userId"));break;}}
                if(selected==null)throw new SecurityException("主人选择的准确 App/Android 用户副本当前不可访问");
                try{return extensions.extension("phone_launch_app_copy",selected,original,()->check(original));}finally{clearReferences();}
            }
        } else {
            if (observed == null || !packages.contains(observed.optString("packageName"))
                    || !observed.getString("observationId").equals(args.optString("observationId"))) throw new SecurityException("需要原显示的最新已授权观察");
            if (name.equals("phone_click_node") || name.equals("phone_type")) {
                boolean found = false; JSONArray nodes = observed.getJSONArray("nodes");
                for (int i = 0; i < nodes.length(); i++) if (nodes.getJSONObject(i).optString("id").equals(args.optString("nodeId")) && !args.optString("nodeId").isEmpty()) found = true;
                if (!found) throw new SecurityException("节点未在本次授权观察中返回，不使用输入焦点回落");
            }
            if (DeviceToolsRuntime.requiresWholeDisplayInput(name) && !binding.optBoolean("allowWholeDisplayInput", false)) throw new SecurityException("未授权整屏输入");
            if (name.equals("phone_back") || name.equals("phone_home")) {
                JSONObject now = physical.observe(false, original); identity(now);
                if (!now.optBoolean("ok") || !observed.optString("treeFingerprint").equals(now.optString("treeFingerprint"))) throw new SecurityException("显示内容已变化，请重新观察");
            }
            // Do not permit a model to replace the actual native display/session reference.
            args.put("sessionId", sessionId).put("displayId", displayId);
        }
        check(control);
        try { return physical.execute(name, args, original); }
        finally { clearReferences(); }
    }
    @Override public synchronized void clearReferences() { observed = null; physical.clearReferences(); }
}
