package dev.pocketengine.store;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.runtime.models.ModelRequestPolicy;
import dev.pocketengine.runtime.usage.InferenceTokenReservation;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashSet;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Projections inside the original TaskStore transaction. Physical-call identity and facts
 * remain in task_events; this class has no worker, task, grant or separate call ledger.
 * Unknown reservations survive cancellation, process restart and daily-window changes. */
final class TaskTokenBudgets {
    private static final String DEFAULT = "owner_token_budget_default_v1", DAILY = "owner_token_budget_daily_v1:";
    private static final int MAX_HISTORY_ROWS = 8192, MAX_HISTORY_BYTES = 8 * 1024 * 1024;
    private TaskTokenBudgets() { }

    static JSONObject defaults(SQLiteDatabase db) throws Exception {
        JSONObject stored = meta(db, DEFAULT);
        return stored == null ? new JSONObject().put("revision", 0).put("enabled", false).put("limit", 0).put("effectiveAt", 0)
                : checkedPolicy(stored);
    }
    static JSONObject setDefaults(SQLiteDatabase db, long expected, boolean enabled, long limit, long now) throws Exception {
        JSONObject old = defaults(db); policyInput(expected, enabled, limit);
        if (old.getLong("revision") != expected) throw new SecurityException("任务默认预算已变化，请重新核对");
        JSONObject next = new JSONObject().put("revision", Math.addExact(expected, 1)).put("enabled", enabled)
                .put("limit", enabled ? limit : old.getLong("limit")).put("effectiveAt", now);
        putMeta(db, DEFAULT, next); return new JSONObject(next.toString());
    }

    private static JSONObject container(JSONObject owner) {
        JSONObject scope = owner.optJSONObject("executionScope");
        return scope != null ? scope : "teams".equals(owner.optString("kind")) ? owner : null;
    }
    /** Capture the owner default on the first original task write, before later edits.
     * Legacy tasks without this native snapshot retain their previous disabled policy. */
    static void initializeDefault(SQLiteDatabase db, JSONObject task) throws Exception {
        if (task.optString("kind").matches("phone|assistant|teams"))
            task.put("tokenBudgetDefault", new JSONObject(defaults(db).toString()));
    }
    static JSONObject task(SQLiteDatabase db, JSONObject owner, String ignoreCall) throws Exception {
        JSONObject place = container(owner); if (place == null) return null;
        JSONObject stored = place.optJSONObject("tokenBudget"); if (stored != null) {JSONObject pool=checkedPool(new JSONObject(stored.toString()));expireReportHolds(pool,System.currentTimeMillis());return pool;}
        JSONObject pool = empty(System.currentTimeMillis()), frozen = owner.optJSONObject("tokenBudgetDefault");
        if (frozen != null && checkedPolicy(frozen).getBoolean("enabled"))
            pool.put("enabled", true).put("limit", frozen.getLong("limit")).put("defaultRevision", frozen.getLong("revision"));
        Set<String> calls = new LinkedHashSet<>();
        try (Cursor rows = db.rawQuery("SELECT call_id FROM task_events WHERE task_id=? AND event_type='inference_receipt'", new String[]{owner.getString("id")})) {
            while (rows.moveToNext()) { if (calls.size() >= MAX_HISTORY_ROWS) { pool.put("coverageComplete", false); break; } if (!rows.isNull(0)) calls.add(rows.getString(0)); }
        }
        JSONObject teams = owner.optJSONObject("teamReservationPool");
        if (teams != null) {
            JSONArray reservations = teams.getJSONArray("reservations");
            for (int i = 0; i < reservations.length(); i++) {
                JSONArray original = reservations.getJSONObject(i).getJSONArray("inferenceCallIds");
                for (int j = 0; j < original.length(); j++) {
                    if (calls.size() >= MAX_HISTORY_ROWS) { pool.put("coverageComplete", false); break; }
                    calls.add(original.getString(j));
                }
            }
        }
        calls.remove(ignoreCall); int bytes = 0;
        for (String call : calls) {
            try (Cursor row = db.rawQuery("SELECT event_json,body_bytes FROM task_events WHERE event_type='inference_receipt' AND call_id=? LIMIT 2", new String[]{call})) {
                if (!row.moveToFirst() || row.getLong(1) < 0 || row.getLong(1) > 65536 || (bytes += row.getInt(1)) > MAX_HISTORY_BYTES) { pool.put("coverageComplete", false); continue; }
                JSONObject receipt;
                try { receipt = new JSONObject(row.getString(0)); }
                catch (org.json.JSONException unreadable) { pool.put("coverageComplete", false); continue; }
                if (row.moveToNext()) { pool.put("coverageComplete", false); continue; }
                seed(pool, receipt);
            }
        }
        JSONObject scope = owner.optJSONObject("executionScope");
        if (scope != null && scope.optInt("modelCallsUsed") > calls.size()) pool.put("coverageComplete", false);
        return pool;
    }
    static void saveTask(JSONObject owner, JSONObject pool) throws Exception {
        JSONObject place = container(owner); if (place == null) throw new SecurityException("原任务没有累计预算容器");
        place.put("tokenBudget", checkedPool(pool));
    }
    static JSONObject setTask(SQLiteDatabase db, JSONObject owner, long expected, boolean enabled, long limit) throws Exception {
        policyInput(expected, enabled, limit); JSONObject pool = task(db, owner, "");
        if (pool == null || pool.getLong("revision") != expected) throw new SecurityException("原任务预算已变化或没有原累计范围");
        if (enabled && (!pool.getBoolean("coverageComplete") || pool.getLong("unboundedCalls") != 0))
            throw new SecurityException("原历史仍有无法界定的用量，不能把遗漏当0启用token上限；请核对原回执或为后续新任务设置默认值");
        pool.put("revision", Math.addExact(expected, 1)).put("enabled", enabled).put("limit", enabled ? limit : pool.getLong("limit"));
        saveTask(owner, pool); return view(pool, false);
    }

    static JSONObject daily(SQLiteDatabase db, String key, String initialZone, long now) throws Exception {
        key(key); JSONObject stored = meta(db, DAILY + key);
        if (stored != null) {
            checkedDaily(stored, key);
            if (!stored.has("poolId")) stored.put("poolId", java.util.UUID.randomUUID().toString());
            expireReportHolds(stored,now);advance(stored, now); return stored;
        }
        ZoneId zone = ZoneId.of(initialZone); JSONObject pool = empty(now);
        pool.put("routeKey", key).put("poolId", java.util.UUID.randomUUID().toString()).put("zoneId", zone.getId()).put("nextZoneId", zone.getId())
                .put("windowStart", Instant.ofEpochMilli(now).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli())
                .put("windowEnd", midnightAfter(now, zone)).put("lastSeenAt", now);
        int rows = 0, bytes = 0;
        // Bounded original-history seed, never a last-N-event subtotal labeled complete.
        try (Cursor source = db.rawQuery("SELECT event_json,body_bytes FROM task_events WHERE event_type='inference_receipt' ORDER BY task_id,event_order", null)) {
            while (source.moveToNext()) {
                if (++rows > MAX_HISTORY_ROWS || source.getLong(1) < 0 || source.getLong(1) > 65536 || (bytes += source.getInt(1)) > MAX_HISTORY_BYTES) { pool.put("coverageComplete", false); break; }
                JSONObject receipt;
                try { receipt = new JSONObject(source.getString(0)); }
                catch (org.json.JSONException unreadable) { pool.put("coverageComplete", false); continue; }
                String historicalKey = InferenceTokenReservation.routeKey(receipt.optJSONObject("route"));
                JSONObject annotation = receipt.optJSONObject("tokenBudget");
                if (historicalKey.isEmpty() && annotation != null && annotation.opt("routeKey") instanceof String saved
                        && saved.matches("(?:connection|account):[A-Za-z0-9_.:-]{1,200}")) historicalKey = saved;
                // A missing/redacted identity is not evidence that this route had zero calls.
                // Original projections remain usable; a new/missing projection cannot guess.
                if (historicalKey.isEmpty()) { pool.put("coverageComplete", false); continue; }
                if (!key.equals(historicalKey)) continue;
                // A feature-era receipt proves there was an original durable projection.
                // Missing meta is not a new day/account and must not clear an unknown hold.
                if (receipt.optJSONObject("tokenBudget") != null)
                    throw new SecurityException("原连接预算投影缺失，保留原回执且未把未知预留清零");
                Long started = nullableLong(receipt, "startedAt");
                if (started == null || started == 0) { pool.put("coverageComplete", false); continue; }
                boolean unfinished = "STARTED".equals(receipt.optString("outcome"));
                if (unfinished || started >= pool.getLong("windowStart")
                        || InferenceTokenReservation.reportedTotal(receipt.optJSONObject("usage")) == null) seed(pool, receipt);
            }
        }
        return pool;
    }
    static JSONObject setDaily(SQLiteDatabase db, String key, long expected, boolean enabled, long limit, String zone, long now) throws Exception {
        policyInput(expected, enabled, limit); ZoneId.of(zone); JSONObject pool = daily(db, key, zone, now);
        if (pool.getLong("revision") != expected) throw new SecurityException("此原连接的每日预算已变化，请重新核对");
        if (enabled && (!pool.getBoolean("coverageComplete") || pool.getLong("unboundedCalls") != 0))
            throw new SecurityException("此连接的历史用量还有未知或读取不完整，不能把遗漏当0启用每日上限");
        pool.put("revision", Math.addExact(expected, 1)).put("enabled", enabled).put("limit", enabled ? limit : pool.getLong("limit"))
                .put("nextZoneId", zone); // Existing period is never shortened or replayed by changing timezone.
        putMeta(db, DAILY + key, pool); return view(pool, true);
    }

    /** Future report allowance shares the existing pool; it is not a physical call or usage receipt. */
    static void reserveOwnerWorkReport(SQLiteDatabase db, JSONObject owner, String workId, long amount,long deadlineAt, InferenceTokenReservation.NativeIdentity identity) throws Exception {
        if(workId==null||!workId.matches("[a-fA-F0-9-]{36}")||amount<1||amount>InferenceTokenReservation.MAX_TOKENS)throw new SecurityException("原结果整理token预留无效");
        JSONObject task=task(db,owner,"");if(task==null)throw new SecurityException("原工作缺少累计token预算容器");
        String key=requireIdentity(db,identity,task.getBoolean("enabled"));long now=System.currentTimeMillis();JSONObject day=key.isEmpty()?null:daily(db,key,ZoneId.systemDefault().getId(),now);
        admit(task,amount,"原任务结果整理");if(day!=null)admit(day,amount,"原连接结果整理");
        addReportHold(task,workId,amount,new JSONObject().put("routeKey",key).put("dailyPoolId",day==null?"":day.getString("poolId")).put("deadlineAt",deadlineAt));
        if(day!=null){addReportHold(day,workId,amount,new JSONObject().put("deadlineAt",deadlineAt));putMeta(db,DAILY+key,day);}saveTask(owner,task);
    }
    /** Only the still-unspent escrow is released; actual/unknown physical-call holds remain untouched. */
    static void releaseOwnerWorkReport(SQLiteDatabase db,JSONObject owner,String workId)throws Exception {
        JSONObject task=task(db,owner,"");if(task==null)throw new SecurityException("原结果整理预算容器已丢失");long now=System.currentTimeMillis();expireReportHolds(task,now);JSONObject hold=reportHold(task,workId);if(hold==null)throw new SecurityException("原结果整理token预留已缺失，未退款");
        long remaining=hold.getLong("remainingTokens");String key=hold.getString("routeKey");
        if(!key.isEmpty()){
            JSONObject day=meta(db,DAILY+key);if(day==null||!hold.getString("dailyPoolId").equals(checkedDaily(day,key).optString("poolId")))throw new SecurityException("原结果整理每日预留已变化，未退款");
            expireReportHolds(day,now);JSONObject original=reportHold(day,workId);if(original==null||original.getLong("remainingTokens")!=remaining)throw new SecurityException("原结果整理每日余量不一致，未退款");
            spendReportHold(day,workId,remaining);putMeta(db,DAILY+key,day);
        }
        spendReportHold(task,workId,remaining);hold.put("releasedAt",now);saveTask(owner,task);
    }
    static JSONArray ownerWorkReportIds(JSONObject pool)throws Exception {
        JSONArray ids=new JSONArray();JSONObject holds=pool==null?null:pool.optJSONObject("ownerWorkReportHolds");if(holds!=null)for(var keys=holds.keys();keys.hasNext();){String id=keys.next();if(reportHold(pool,id).getLong("remainingTokens")>0)ids.put(id);}return ids;
    }
    private static void expireReportHolds(JSONObject pool,long now)throws Exception {
        JSONObject holds=pool.optJSONObject("ownerWorkReportHolds");if(holds==null)return;
        for(var keys=holds.keys();keys.hasNext();){String id=keys.next();JSONObject hold=reportHold(pool,id);long deadline=hold.optLong("deadlineAt");if(deadline>0&&deadline<=now&&hold.getLong("remainingTokens")>0){spendReportHold(pool,id,hold.getLong("remainingTokens"));hold.put("releasedAt",now);}}
    }
    private static void addReportHold(JSONObject pool,String id,long amount,JSONObject identity)throws Exception {
        JSONObject rows=pool.optJSONObject("ownerWorkReportHolds");if(rows==null){rows=new JSONObject();pool.put("ownerWorkReportHolds",rows);}if(rows.has(id))throw new SecurityException("原工作结果整理已预留，未重复扣账");
        rows.put(id,identity.put("initialTokens",amount).put("remainingTokens",amount).put("reservedAt",System.currentTimeMillis()));add(pool,"heldTokens",amount);
    }
    private static JSONObject reportHold(JSONObject pool,String id)throws Exception {
        JSONObject rows=pool.optJSONObject("ownerWorkReportHolds");if(rows==null)return null;JSONObject hold=rows.optJSONObject(id);if(hold==null&&rows.has(id))throw new SecurityException("原结果整理预留损坏");
        if(hold!=null){Long initial=nullableLong(hold,"initialTokens"),remaining=nullableLong(hold,"remainingTokens");if(initial==null||remaining==null||initial<1||initial>InferenceTokenReservation.MAX_TOKENS||remaining>initial)throw new SecurityException("原结果整理预留计数无效");}return hold;
    }
    private static void spendReportHold(JSONObject pool,String id,long amount)throws Exception {
        JSONObject hold=reportHold(pool,id);if(hold==null||amount<0||amount>hold.getLong("remainingTokens"))throw new ModelRequestPolicy.Rejected("owner_work_report_budget_exhausted","本次结果整理超过派单时预留的token范围；原始结果已保留，没有补满预算或重新调用。");
        hold.put("remainingTokens",hold.getLong("remainingTokens")-amount);add(pool,"heldTokens",-amount);
    }
    /** Caller is already in the SAME SQL transaction that writes the original started receipt. */
    static void started(SQLiteDatabase db, JSONObject owner, JSONObject receipt, InferenceTokenReservation.NativeIdentity identity) throws Exception {
        started(db,owner,receipt,identity,"");
    }
    static void started(SQLiteDatabase db, JSONObject owner, JSONObject receipt, InferenceTokenReservation.NativeIdentity identity,String reportWorkId) throws Exception {
        JSONObject task = task(db, owner, receipt.getString("callId")), route = receipt.getJSONObject("route");
        String key = requireIdentity(db, identity, task != null && task.getBoolean("enabled")); long now = receipt.getLong("startedAt");
        JSONObject day = key.isEmpty() ? null : daily(db, key, ZoneId.systemDefault().getId(), now);
        JSONObject estimate = route.optJSONObject("tokenReservation");
        Long reserved = reservation(estimate);
        if(!reportWorkId.isEmpty()){
            JSONObject hold=task==null?null:reportHold(task,reportWorkId);
            if(hold==null||reserved==null||!key.equals(hold.optString("routeKey"))||day!=null&&!day.getString("poolId").equals(hold.optString("dailyPoolId")))throw new ModelRequestPolicy.Rejected("owner_work_report_budget_unavailable","原结果整理预留与当前模型连接或请求估计不一致，尚未发送。");
            // Transfer into the ordinary physical-call hold in this same transaction.
            spendReportHold(task,reportWorkId,reserved);if(day!=null)spendReportHold(day,reportWorkId,reserved);
        }
        if (task != null) admit(task, reserved, "原任务");
        if (day != null) admit(day, reserved, "原连接每日");
        JSONObject annotation = new JSONObject().put("format", "pocketworld.token-budget-receipt.v1")
                .put("taskOwner", task == null ? JSONObject.NULL : owner.getString("id"))
                // A native ID accidentally containing the credential must stay private.
                // The opaque pool ID pins settlement without re-exporting that identity.
                .put("routeKey", !key.isEmpty() && key.equals(InferenceTokenReservation.routeKey(route)) ? key : JSONObject.NULL)
                .put("dailyPoolId", day == null ? JSONObject.NULL : day.getString("poolId"))
                .put("windowStart", day == null ? JSONObject.NULL : day.getLong("windowStart"))
                .put("reservedTokens", reserved == null ? JSONObject.NULL : reserved).put("actualTokens", JSONObject.NULL)
                .put("state", "pending").put("providerHardCap", false);
        if(!reportWorkId.isEmpty())annotation.put("ownerWorkReportReservation",reportWorkId);
        if (task != null) { reserve(task, reserved); saveTask(owner, task); }
        if (day != null) { reserve(day, reserved); putMeta(db, DAILY + key, day); }
        receipt.put("tokenBudget", annotation);
    }
    static void finished(SQLiteDatabase db, JSONObject owner, JSONObject old, JSONObject receipt, InferenceTokenReservation.NativeIdentity identity) throws Exception {
        JSONObject annotation = old.optJSONObject("tokenBudget"); if (annotation == null) return; // Pre-feature receipt: seed retains its unknown state.
        annotation = new JSONObject(annotation.toString());
        if (!"pending".equals(annotation.getString("state"))) throw new SecurityException("原调用预算已经结算，不能重复释放");
        Long reserve = nullableLong(annotation, "reservedTokens"), actual = InferenceTokenReservation.reportedTotal(receipt.optJSONObject("usage"));
        if (actual != null && actual > InferenceTokenReservation.MAX_TOKENS) actual = null;
        if (!annotation.isNull("taskOwner")) {
            if (!owner.getString("id").equals(annotation.getString("taskOwner"))) throw new SecurityException("不能把原调用预算结算到新父任务");
            JSONObject task = task(db, owner, ""); if (task == null) throw new SecurityException("原任务token预算丢失，预留没有退款");
            settle(task, reserve, actual, true); saveTask(owner, task);
        }
        if (!annotation.isNull("routeKey") || !annotation.isNull("dailyPoolId")) {
            String key = identity.routeKey();
            if (key.isEmpty() || !annotation.isNull("routeKey") && !key.equals(annotation.getString("routeKey")))
                throw new SecurityException("原连接预算私有身份不一致，预留没有退款");
            JSONObject day = meta(db, DAILY + key);
            if (day == null) throw new SecurityException("原连接token预留丢失，没有补造新账目");
            checkedDaily(day, key);
            if (!annotation.isNull("dailyPoolId") && !annotation.getString("dailyPoolId").equals(day.optString("poolId")))
                throw new SecurityException("原连接预算池已变化，预留没有退款");
            advance(day, receipt.getLong("finishedAt"));
            settle(day, reserve, actual, annotation.getLong("windowStart") == day.getLong("windowStart"));
            putMeta(db, DAILY + key, day);
        }
        annotation.put("state", actual == null ? "unknown" : "settled").put("actualTokens", actual == null ? JSONObject.NULL : actual)
                .put("exceededReservation", actual != null && reserve != null && actual > reserve);
        receipt.put("tokenBudget", annotation);
    }

    static boolean dailyEnabled(SQLiteDatabase db, String key) throws Exception {
        if (key.isEmpty()) return false; JSONObject pool = meta(db, DAILY + key);
        return pool != null && checkedDaily(pool, key).getBoolean("enabled");
    }
    /** Repeat inside the original write transaction so a late policy edit cannot race admission. */
    static String requireIdentity(SQLiteDatabase db, InferenceTokenReservation.NativeIdentity identity, boolean taskEnabled) throws Exception {
        String key = java.util.Objects.requireNonNull(identity).routeKey();
        if (key.isEmpty() && (taskEnabled || anyDailyEnabled(db)))
            throw new ModelRequestPolicy.Rejected("token_budget_identity_required", "已启用token预算，但本次请求缺少可核对的原生连接或账号身份，尚未发送。");
        return key;
    }
    private static boolean anyDailyEnabled(SQLiteDatabase db) throws Exception {
        try (Cursor rows = db.rawQuery("SELECT key,value FROM meta WHERE key GLOB ?", new String[]{DAILY + "*"})) {
            while (rows.moveToNext()) {
                String key = rows.getString(0).substring(DAILY.length()); key(key);
                if (checkedDaily(new JSONObject(rows.getString(1)), key).getBoolean("enabled")) return true;
            }
        }
        return false;
    }
    static JSONObject view(JSONObject source, boolean daily) throws Exception {
        JSONObject pool = checkedPool(new JSONObject(source.toString()));
        long occupied = Math.addExact(pool.getLong("knownTokens"), pool.getLong("heldTokens"));
        long reports=0;int count=0;JSONObject holds=pool.optJSONObject("ownerWorkReportHolds");if(holds!=null)for(var keys=holds.keys();keys.hasNext();){long remaining=reportHold(pool,keys.next()).getLong("remainingTokens");reports=Math.addExact(reports,remaining);if(remaining>0)count++;}
        pool.remove("ownerWorkReportHolds");pool.put("reportReservedTokens",reports).put("reportReservationCount",count);
        return pool.put("occupiedTokens", occupied).put("remainingTokens", pool.getBoolean("enabled") ? Math.max(0, pool.getLong("limit") - occupied) : JSONObject.NULL)
                .put("overLimit", pool.getBoolean("enabled") && occupied > pool.getLong("limit"))
                .put("blockedByUnknown", !pool.getBoolean("coverageComplete") || pool.getLong("unboundedCalls") != 0)
                .put("meaning", "local_token_admission_estimate_not_bill_or_provider_hard_cap")
                .put("scope", daily ? "this_device_saved_connection_or_registration" : "original_task_and_native_team_children")
                .put("automaticRefundOnCancel", false).put("executionAuthorized", false);
    }

    private static JSONObject empty(long now) throws Exception {
        return new JSONObject().put("format", "pocketworld.token-budget-pool.v1").put("revision", 0).put("enabled", false).put("limit", 0)
                .put("knownTokens", 0).put("heldTokens", 0).put("unboundedCalls", 0).put("pendingCalls", 0).put("unknownCalls", 0)
                .put("calls", 0).put("coverageComplete", true).put("createdAt", now);
    }
    private static void seed(JSONObject pool, JSONObject receipt) throws Exception {
        Long reported = InferenceTokenReservation.reportedTotal(receipt.optJSONObject("usage"));
        if (reported != null && reported > InferenceTokenReservation.MAX_TOKENS) reported = null;
        boolean pending = "STARTED".equals(receipt.optString("outcome"));
        if (!pending && reported != null) add(pool, "knownTokens", reported);
        else {
            Long reserved = reservation(receipt.optJSONObject("route") == null ? null : receipt.getJSONObject("route").optJSONObject("tokenReservation"));
            if (reserved == null) add(pool, "unboundedCalls", 1); else add(pool, "heldTokens", reserved);
            add(pool, pending ? "pendingCalls" : "unknownCalls", 1);
        }
        add(pool, "calls", 1);
    }
    private static void admit(JSONObject pool, Long reserve, String label) throws Exception {
        if (!pool.getBoolean("enabled")) return;
        if (!pool.getBoolean("coverageComplete") || pool.getLong("unboundedCalls") != 0 || reserve == null)
            throw new ModelRequestPolicy.Rejected("token_budget_unknown", label + "token预留无法完整核对，尚未发送；请检查原用量和图像预留，未知没有按0计算。");
        long occupied = Math.addExact(pool.getLong("knownTokens"), pool.getLong("heldTokens"));
        if (occupied > pool.getLong("limit") || reserve > pool.getLong("limit") - occupied)
            throw new ModelRequestPolicy.Rejected("token_budget_exhausted", label + "token余额不足以容纳本次保守预留，尚未发送；请在原预算页核对或明确调整上限。");
    }
    private static void reserve(JSONObject pool, Long reserve) throws Exception {
        if (reserve == null) add(pool, "unboundedCalls", 1); else add(pool, "heldTokens", reserve);
        add(pool, "pendingCalls", 1); add(pool, "calls", 1);
    }
    private static void settle(JSONObject pool, Long reserve, Long actual, boolean sameWindow) throws Exception {
        add(pool, "pendingCalls", -1);
        if (actual == null) { add(pool, "unknownCalls", 1); return; }
        if (reserve == null) add(pool, "unboundedCalls", -1); else add(pool, "heldTokens", -reserve);
        if (sameWindow) add(pool, "knownTokens", actual);
    }
    static void advance(JSONObject day, long wallNow) throws Exception {
        long now = Math.max(wallNow, day.getLong("lastSeenAt")); day.put("lastSeenAt", now);
        if (now < day.getLong("windowEnd")) return;
        ZoneId zone = ZoneId.of(day.getString("nextZoneId"));
        long start = Math.max(day.getLong("windowEnd"), Instant.ofEpochMilli(now).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli());
        day.put("zoneId", zone.getId()).put("windowStart", start).put("windowEnd", midnightAfter(now, zone)).put("knownTokens", 0);
        // heldTokens/unboundedCalls never reset here, including after a process restart.
    }
    private static long midnightAfter(long now, ZoneId zone) { return Instant.ofEpochMilli(now).atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(); }
    private static Long reservation(JSONObject estimate) throws Exception {
        if (estimate == null || !"pocketworld.inference-token-reserve.v1".equals(estimate.optString("format")) || !Boolean.TRUE.equals(estimate.opt("available"))) return null;
        Long value = nullableLong(estimate, "reservedTokens");
        return value != null && value > 0 && value <= InferenceTokenReservation.MAX_TOKENS ? value : null;
    }
    private static Long nullableLong(JSONObject value, String name) throws Exception {
        Object raw = value.opt(name); if (raw == null || raw == JSONObject.NULL) return null;
        if (!(raw instanceof Integer || raw instanceof Long) || ((Number)raw).longValue() < 0) throw new SecurityException("原token预算计数不可核对");
        return ((Number)raw).longValue();
    }
    private static void add(JSONObject value, String key, long amount) throws Exception {
        long next = Math.addExact(value.getLong(key), amount); if (next < 0) throw new SecurityException("原token预算重复释放或计数不符"); value.put(key, next);
    }
    private static JSONObject checkedPolicy(JSONObject value) throws Exception {
        Long revision = nullableLong(value, "revision"), limit = nullableLong(value, "limit");
        if (!(value.opt("enabled") instanceof Boolean) || revision == null || limit == null || limit > InferenceTokenReservation.MAX_TOKENS || value.getBoolean("enabled") && limit < 1) throw new SecurityException("原token预算策略不可核对");
        return value;
    }
    private static JSONObject checkedPool(JSONObject value) throws Exception {
        checkedPolicy(value);
        if (!"pocketworld.token-budget-pool.v1".equals(value.optString("format")) || !(value.opt("coverageComplete") instanceof Boolean)) throw new SecurityException("原token预算投影损坏，未重置账目");
        for (String key : new String[]{"knownTokens", "heldTokens", "unboundedCalls", "pendingCalls", "unknownCalls", "calls"}) if (nullableLong(value, key) == null) throw new SecurityException("原token预算缺少计数");
        if(value.has("ownerWorkReportHolds")){
            JSONObject holds=value.getJSONObject("ownerWorkReportHolds");long reserved=0;
            for(var keys=holds.keys();keys.hasNext();){String id=keys.next();if(!id.matches("[a-fA-F0-9-]{36}"))throw new SecurityException("原结果整理预留身份无效");reserved=Math.addExact(reserved,reportHold(value,id).getLong("remainingTokens"));}
            if(reserved>value.getLong("heldTokens"))throw new SecurityException("原结果整理预留与累计占用不一致");
        }
        return value;
    }
    private static JSONObject checkedDaily(JSONObject value, String key) throws Exception {
        checkedPool(value);
        if (value.has("poolId") && (!(value.opt("poolId") instanceof String id) || !id.matches("[a-fA-F0-9-]{36}")))
            throw new SecurityException("原每日预算池身份不可核对，未重置余额");
        Long start = nullableLong(value, "windowStart"), end = nullableLong(value, "windowEnd"), seen = nullableLong(value, "lastSeenAt");
        if (!key.equals(value.optString("routeKey")) || start == null || end == null || seen == null
                || end <= start || seen < start || seen >= end) throw new SecurityException("原每日预算身份或区间损坏，未重置余额");
        ZoneId.of(value.getString("zoneId")); ZoneId.of(value.getString("nextZoneId")); return value;
    }
    private static void policyInput(long expected, boolean enabled, long limit) {
        if (expected < 0 || limit < 0 || limit > InferenceTokenReservation.MAX_TOKENS || enabled && limit < 1) throw new IllegalArgumentException("请填写1至1000000000000的本机token上限");
    }
    private static void key(String key) { if (key == null || !key.matches("(?:connection|account):[A-Za-z0-9_.:-]{1,200}")) throw new SecurityException("请选择本机原连接或账号登记"); }
    private static JSONObject meta(SQLiteDatabase db, String key) throws Exception { try (Cursor row = db.rawQuery("SELECT value FROM meta WHERE key=?", new String[]{key})) { return row.moveToFirst() ? new JSONObject(row.getString(0)) : null; } }
    private static void putMeta(SQLiteDatabase db, String key, JSONObject value) { ContentValues row = new ContentValues(); row.put("key", key); row.put("value", value.toString()); if(db.insertWithOnConflict("meta", null, row, SQLiteDatabase.CONFLICT_REPLACE)<0)throw new IllegalStateException("原token预算未确认保存，未继续派发"); }
}
