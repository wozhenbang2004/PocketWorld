package dev.pocketengine.features.plugins.catalog;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.EngineModule;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;

/** One native owner page's review/request mapping. Uses the host's worker and existing operation journal. */
public final class PluginCatalogSession implements AutoCloseable {
    private final PluginCatalog catalog;
    private final ExecutablePluginCatalog executable;
    private final EngineModule plugins;
    private final Object owner;
    private final String pageRequestId;
    private final RunControl.AuthorityCheck authority;
    private long revision;
    private Request active;
    private PluginCatalog.Snapshot snapshot;
    private ExecutablePluginCatalog.Snapshot jsSnapshot;
    private Approval approval;
    private String config = "{}", rows = "{}", review = "{}", result = "{}", installed = "{}", jsInstalled = "{}", jsHistory = "{}";
    private String status = "idle", message = "添加自己的目录来源，选择明确版本后查看安装差异。";
    private String lastRequestId = "";
    private boolean closed, unresolved;

    public PluginCatalogSession(PluginCatalog catalog, ExecutablePluginCatalog executable, EngineModule existingPlugins, Object nativeOwner,
            String nativePageRequestId, RunControl.AuthorityCheck authority) {
        this.catalog = java.util.Objects.requireNonNull(catalog); this.executable = java.util.Objects.requireNonNull(executable); plugins = java.util.Objects.requireNonNull(existingPlugins);
        owner = java.util.Objects.requireNonNull(nativeOwner); this.authority = java.util.Objects.requireNonNull(authority);
        if (nativePageRequestId == null || nativePageRequestId.isBlank() || nativePageRequestId.length() > 160) throw new IllegalArgumentException("需要原生页面请求身份");
        pageRequestId = nativePageRequestId;
    }

    /** Read-only defensive data plus an opaque native confirmation object; JSON cannot reconstruct it. */
    public static final class View {
        public final String pageRequestId;
        public final long revision;
        public final Approval approval;
        private final PluginCatalogSession session;
        private final String json;
        private View(PluginCatalogSession session, JSONObject data) throws Exception {
            this(session, data, session.revision, session.active == null && !session.unresolved ? session.approval : null);
        }
        private View(PluginCatalogSession session, JSONObject data, long revision, Approval approval) {
            this.session = session; pageRequestId = session.pageRequestId; this.revision = revision; this.approval = approval; json = data.toString();
        }
        public JSONObject data() throws Exception { return new JSONObject(json); }
    }
    public static final class Approval {
        public final String reviewDigest;
        public final String kind;
        private final PluginCatalogSession session;
        private final PluginCatalog.Review reviewed;
        private final ExecutablePluginCatalog.Inspection jsInspection;
        private final ExecutablePluginCatalog.InstalledReview jsReview;
        private Approval(PluginCatalogSession session, PluginCatalog.Review reviewed) {
            this.session = session; this.reviewed = reviewed; jsInspection = null; jsReview = null; kind = "declaration_install"; reviewDigest = reviewed.reviewDigest;
        }
        private Approval(PluginCatalogSession session, ExecutablePluginCatalog.Inspection inspected) {
            this.session = session; reviewed = null; jsInspection = inspected; jsReview = null; kind = "js_install"; reviewDigest = inspected.reviewDigest;
        }
        private Approval(PluginCatalogSession session, ExecutablePluginCatalog.InstalledReview inspected) {
            this.session = session; reviewed = null; jsInspection = null; jsReview = inspected; kind = "js_permission"; reviewDigest = inspected.reviewDigest;
        }
    }

    public synchronized View view(Object nativeOwner) throws Exception { owner(nativeOwner); return view(); }
    /** Main-thread reply admission. A late/foreign reply cannot restore a previous page's controls. */
    public synchronized boolean accepts(Object nativeOwner, View view) {
        return nativeOwner == owner && !closed && view != null && view.session == this && view.revision == revision;
    }
    private View view() throws Exception {
        return new View(this, new JSONObject().put("pageRequestId", pageRequestId).put("requestId", lastRequestId).put("revision", revision)
                .put("status", status).put("busy", active != null).put("unresolved", unresolved).put("message", message)
                .put("config", new JSONObject(config)).put("catalog", new JSONObject(rows)).put("review", new JSONObject(review))
                .put("result", new JSONObject(result)).put("installed", new JSONObject(installed)).put("jsInstalled", new JSONObject(jsInstalled)).put("jsHistory", new JSONObject(jsHistory)));
    }

    /** Non-install operations only. No command accepts a review digest/approval/manifest for installation. */
    public synchronized Request begin(Object nativeOwner, String requestId, String command, JSONObject input) throws Exception {
        owner(nativeOwner);
        if (!Set.of("read", "configure", "refresh", "prepare", "queryInstalled", "jsRefresh", "jsPrepare", "jsReviewInstalled", "jsHistory", "jsPrepareRestore").contains(command)) throw new SecurityException("该JSON操作不属于原生目录读取/配置流程");
        if (unresolved && !Set.of("read", "queryInstalled").contains(command)) throw new IllegalStateException("先核对上次安装结果，再从当前插件管理重新开始");
        Request request = start(requestId, command, new JSONObject(input.toString()), null);
        approval = null; review = "{}";
        if (command.equals("refresh")) { snapshot = null; rows = "{}"; }
        if (command.equals("jsRefresh")) { jsSnapshot = null; rows = "{}"; }
        return request;
    }
    /** Native button only. Identity comparison, not data supplied through featureRequest or a model JSON boolean. */
    public synchronized Request submit(Object nativeOwner, String requestId, Approval accepted) throws Exception {
        owner(nativeOwner);
        if (unresolved || accepted == null || accepted.session != this || accepted != approval || accepted.kind.equals("js_permission")) throw new SecurityException("需当前页面的真实原生安装审阅对象");
        Request request = start(requestId, "submit", new JSONObject(), accepted);
        approval = null; return request;
    }
    /** Separate native buttons for source approval/revocation and enabling/stopping. Never a JSON command. */
    public synchronized Request changeJs(Object nativeOwner, String requestId, Approval accepted, String action) throws Exception {
        owner(nativeOwner);
        if (unresolved || accepted == null || accepted.session != this || accepted != approval || !accepted.kind.equals("js_permission")
                || !Set.of("approve", "revoke", "enable", "disable", "remove").contains(action)) throw new SecurityException("需本次安装源码的原生确认对象");
        Request request = start(requestId, "jsChange", new JSONObject().put("action", action), accepted); approval = null; return request;
    }
    private Request start(String requestId, String command, JSONObject input, Approval accepted) throws Exception {
        authority.check();
        if (requestId == null || requestId.isBlank() || requestId.length() > 160) throw new IllegalArgumentException("需要本次原生请求身份");
        if (active != null) {
            if (active.effectful()) throw new IllegalStateException("这项操作仍在处理中，请等待实际回执");
            active.control.cancel();
        }
        Request request = new Request(++revision, requestId, command, input, accepted);
        active = request; lastRequestId = requestId; status = "working"; message = "正在处理，请稍候。"; result = "{}";
        return request;
    }
    public synchronized View cancel(Object nativeOwner) throws Exception {
        owner(nativeOwner); approval = null; review = "{}";
        if (active != null) { active.control.cancel(); message = "已请求停止，正在核对实际回执。"; }
        else { revision++; status = "cancelled"; message = "已取消本次审阅。"; }
        return view();
    }

    public final class Request {
        public final String requestId, command;
        private final long requestRevision;
        private final JSONObject input;
        private final Approval accepted;
        private final RunControl control = new RunControl();
        private final AtomicBoolean executed = new AtomicBoolean();
        private volatile String receipt;
        private Request(long requestRevision, String requestId, String command, JSONObject input, Approval accepted) {
            this.requestRevision = requestRevision; this.requestId = requestId; this.command = command; this.input = input; this.accepted = accepted;
            control.setAuthorityCheck(() -> {
                authority.check();
                synchronized (PluginCatalogSession.this) { if (closed || active != this || revision != requestRevision) throw new SecurityException("原生页面或请求已失效"); }
            });
        }
        private boolean effectful() { return command.equals("submit") || command.equals("configure") || command.equals("jsChange"); }
        /** The host retains this receipt in its original operation even when the page is gone. */
        public JSONObject receipt() throws Exception { return receipt == null ? null : new JSONObject(receipt); }
        /** Executor rejection is known before dispatch; keep the page usable without pretending a request ran. */
        public View rejected() throws Exception {
            if (!executed.compareAndSet(false, true)) throw new IllegalStateException("请求已经开始，不能标记为未派发");
            synchronized (PluginCatalogSession.this) {
                if (active != this || closed) return superseded();
                active = null; approval = null; review = "{}"; status = "failed"; message = "当前工作队列繁忙，本次操作未开始。请稍后重试。";
                return view();
            }
        }
        private View superseded() throws Exception { return new View(PluginCatalogSession.this, new JSONObject().put("pageRequestId", pageRequestId).put("requestId", requestId)
                .put("revision", requestRevision).put("status", "superseded").put("busy", false), requestRevision, null); }
        public View execute() throws Exception {
            if (!executed.compareAndSet(false, true)) throw new IllegalStateException("同一请求不可重复执行");
            JSONObject output = null; PluginCatalog.Snapshot nextSnapshot = null; PluginCatalog.Review nextReview = null;
            ExecutablePluginCatalog.Snapshot nextJsSnapshot = null; ExecutablePluginCatalog.Inspection nextJsInspection = null; ExecutablePluginCatalog.InstalledReview nextJsReview = null;
            Exception failure = null; String nextJsInstalled = null; boolean jsSnapshotFailed = false;
            try {
                control.check();
                switch (command) {
                    case "read": output = catalog.sources(); break;
                    case "configure": output = catalog.configure(input.getJSONArray("sources"), input.getString("expectedRevision")); break;
                    case "refresh": nextSnapshot = catalog.refresh(input.getString("sourceId"), control); output = catalog.candidates(nextSnapshot, control); break;
                    case "prepare":
                        PluginCatalog.Snapshot selected;
                        synchronized (PluginCatalogSession.this) { selected = snapshot; }
                        if (selected == null) throw new IllegalStateException("请先刷新所选目录");
                        if (!input.getString("sourceId").equals(selected.view().getJSONObject("source").getString("id"))) throw new SecurityException("所选来源与实际目录不符");
                        nextReview = catalog.prepare(selected, input.getString("id"), input.getString("version"), control);
                        output = nextReview.view(); break;
                    case "jsRefresh": nextJsSnapshot = executable.refresh(input.getString("sourceId"), control); output = nextJsSnapshot.view(); break;
                    case "jsPrepare":
                        ExecutablePluginCatalog.Snapshot selectedJs;
                        synchronized (PluginCatalogSession.this) { selectedJs = jsSnapshot; }
                        if (selectedJs == null) throw new IllegalStateException("请先刷新所选JS目录");
                        if (!input.getString("sourceId").equals(selectedJs.view().getJSONObject("source").getString("id"))) throw new SecurityException("所选来源与实际JS目录不符");
                        nextJsInspection = executable.prepare(selectedJs, input.getString("id"), input.getString("version"), control); output = nextJsInspection.view(); break;
                    case "jsHistory": output = executable.history(input.getString("id"), control); break;
                    case "jsPrepareRestore": nextJsInspection = executable.prepareRestore(input.getString("id"), input.getString("installationId"), control); output = nextJsInspection.view(); break;
                    case "jsReviewInstalled": nextJsReview = executable.reviewInstalled(input.getString("id"), control); output = nextJsReview.view(); break;
                    case "submit": output = accepted.reviewed != null ? catalog.installReviewed(accepted.reviewed, accepted.reviewDigest, control)
                            : executable.installReviewed(accepted.jsInspection, accepted.reviewDigest, control); break;
                    case "jsChange":
                        String action = input.getString("action");
                        output = action.equals("remove") ? executable.removeReviewed(accepted.jsReview, accepted.reviewDigest, control)
                                : Set.of("approve", "revoke").contains(action) ? executable.approveReviewed(accepted.jsReview, accepted.reviewDigest, action.equals("approve"), control)
                                : executable.setEnabledReviewed(accepted.jsReview, accepted.reviewDigest, action.equals("enable"), control); break;
                    case "queryInstalled": output = plugins.snapshot(); break;
                    default: throw new AssertionError(command);
                }
                if (effectful()) receipt = output.toString();
            } catch (Exception error) { failure = error; }
            // Never acquire Executable's monitor while holding Session: executable authority checks take Session.
            if (failure == null && (!control.isCancelled() || effectful())) {
                try { nextJsInstalled = executable.snapshot().toString(); } catch (Exception readFailure) { jsSnapshotFailed = true; }
            }
            synchronized (PluginCatalogSession.this) {
                if (closed || active != this || requestRevision != revision) return superseded();
                active = null;
                if (failure != null || (control.isCancelled() && !effectful())) {
                    approval = null; review = "{}";
                    status = control.isCancelled() ? "cancelled" : "failed";
                    message = control.isCancelled() ? "已停止。需要继续时重新刷新和审阅。" : "未完成：" + safeMessage(failure);
                } else {
                    status = "ready";
                    if (command.equals("read") || command.equals("configure")) { config = output.toString(); message = command.equals("configure") ? "目录来源已保存。选择来源后刷新版本。" : "选择自己的目录来源。"; }
                    if (command.equals("configure")) { snapshot = null; jsSnapshot = null; rows = "{}"; }
                    if (command.equals("refresh")) { snapshot = nextSnapshot; rows = output.toString(); message = output.getJSONArray("entries").length() == 0 ? "目录目前没有可选版本。" : "选择一个版本，下载并核对实际差异。"; }
                    if (command.equals("prepare")) { review = output.toString(); approval = new Approval(PluginCatalogSession.this, nextReview); message = "已核对实际包内容。阅读变化后再确认。"; }
                    if (command.equals("jsRefresh")) { jsSnapshot = nextJsSnapshot; rows = output.toString(); message = "JS目录只提供静态包身份。选择版本后先阅读真实源码。"; }
                    if (command.equals("jsHistory")) { jsHistory = output.toString(); message = "只保留最近三份被替换安装。回退不恢复旧批准或任务许可。"; }
                    if (command.equals("jsPrepare") || command.equals("jsPrepareRestore")) { review = output.toString(); approval = new Approval(PluginCatalogSession.this, nextJsInspection); message = "源码尚未执行。安装会保持未批准、停用。"; }
                    if (command.equals("jsReviewInstalled")) { review = output.toString(); approval = new Approval(PluginCatalogSession.this, nextJsReview); message = "这是当前安装的完整源码。批准后仍需单独启用。"; }
                    if (command.equals("queryInstalled")) { installed = output.toString(); message = "这是当前实际安装状态；未重新提交安装。"; }
                    if (command.equals("submit") || command.equals("jsChange")) {
                        jsHistory = "{}";
                        result = output.toString(); review = "{}"; String receiptStatus = output.optString("status");
                        unresolved = "unknown".equals(receiptStatus); status = unresolved ? "unknown" : "completed";
                        message = unresolved ? "记录结果未确认。请查看当前安装，勿再次提交。" : "removed".equals(receiptStatus) ? "扩展已卸载，旧任务不能继续使用这份安装。" : "rediscovery_required".equals(receiptStatus) ? "需要重新发现当前 MCP 服务，尚未安装。" : "installed_unapproved".equals(receiptStatus) ? "JS插件已安装，尚未批准或启用。阅读已安装源码后再决定。" : command.equals("jsChange") ? "源码审批状态已保存。旧任务的许可身份已失效。" : "版本已保存并保持停用。请在插件管理中重新启用和授权。";
                    }
                    if (nextJsInstalled != null) jsInstalled = nextJsInstalled;
                    if (jsSnapshotFailed) message += " 当前JS安装状态读取失败，请重新读取。";
                }
                return view();
            }
        }
    }
    private static String safeMessage(Exception error) { String text = error == null ? "请刷新后重试" : error.getMessage(); return text == null || text.isBlank() ? "请检查配置后重试" : text.substring(0, Math.min(text.length(), 240)); }
    private void owner(Object nativeOwner) { if (nativeOwner != owner || closed) throw new SecurityException("原生目录页面身份已失效"); }
    @Override public synchronized void close() { closed = true; revision++; approval = null; snapshot = null; review = "{}"; if (active != null) active.control.cancel(); }
}
