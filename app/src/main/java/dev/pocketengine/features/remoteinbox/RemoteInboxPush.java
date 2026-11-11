package dev.pocketengine.features.remoteinbox;

import android.content.Context;
import org.json.JSONObject;

/** Optional SDK boundary. The standard build neither contains a distributor SDK nor registers one. */
public final class RemoteInboxPush {
    public interface Backend {
        JSONObject snapshot(Context context) throws Exception;
        void enable(Context context, String distributor, String registrationToken, String configId, String endpoint) throws Exception;
        void disable(Context context) throws Exception;
        void reconcile(Context context) throws Exception;
        void maintain(Context context) throws Exception;
        void acknowledge(Context context) throws Exception;
        boolean needsMaintenance(Context context) throws Exception;
    }
    private static final Backend backend = load();
    private RemoteInboxPush() { }
    private static Backend load() {
        try {
            return (Backend) Class.forName("dev.pocketengine.features.remoteinbox.UnifiedInboxPush")
                .getDeclaredConstructor().newInstance();
        } catch (ClassNotFoundException absent) { return null; }
        catch (ReflectiveOperationException broken) { throw new IllegalStateException("推送模块不可用", broken); }
    }
    public static JSONObject snapshot(Context context) throws Exception {
        return backend == null ? new JSONObject().put("supported", false).put("enabled", false) : backend.snapshot(context);
    }
    public static void enable(Context context, String distributor, String token, String configId, String endpoint) throws Exception {
        if (backend == null) throw new IllegalStateException("此构建未包含可选 UnifiedPush 模块");
        backend.enable(context.getApplicationContext(), distributor, token, configId, endpoint);
    }
    public static void disable(Context context) throws Exception { if (backend != null) backend.disable(context.getApplicationContext()); }
    public static void reconcile(Context context) throws Exception { if (backend != null) backend.reconcile(context.getApplicationContext()); }
    public static void maintain(Context context) throws Exception { if (backend != null) backend.maintain(context.getApplicationContext()); }
    public static boolean needsMaintenance(Context context) throws Exception { return backend != null && backend.needsMaintenance(context.getApplicationContext()); }
    public static void acknowledge(Context context) throws Exception { if (backend != null) backend.acknowledge(context.getApplicationContext()); }
}
