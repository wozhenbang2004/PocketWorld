package dev.pocketengine.store;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.runtime.usage.ExecutionTraceProjection;
import dev.pocketengine.runtime.usage.ExecutionTraceCapture;
import dev.pocketengine.runtime.usage.ToolTraceCapture;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Bounded keyset views of the existing audit tables. No new execution state or ledger. */
final class TaskTraceQuery {
    private static final int MAX_PAGE_BYTES = 1024 * 1024;
    private TaskTraceQuery() { }

    static JSONObject read(SQLiteDatabase db, String task, String requestedRun, String cursor, int requestedLimit, RunControl control) throws Exception {
        if (task == null || !task.matches("[a-fA-F0-9-]{36}") || control == null || control.hasHostRun())
            throw new SecurityException("轨迹只供原生主人页面读取原任务");
        control.check();
        JSONObject header = header(db, task);
        String currentRun = header.optString("runId"), run = requestedRun == null || requestedRun.isEmpty() ? currentRun : requestedRun;
        if (run.length() > 200 || !run.matches("[A-Za-z0-9_.:-]*")) throw new IllegalArgumentException("原执行身份无效");
        int limit = requestedLimit <= 0 ? 100 : Math.min(requestedLimit, 200);
        long upper = highWater(db, task), before = upper + 1;
        if (cursor != null && !cursor.isEmpty()) {
            JSONObject decoded = decode(cursor);
            if (decoded.optInt("v") != 1 || !task.equals(decoded.optString("taskId")) || !run.equals(decoded.optString("runId")))
                throw new SecurityException("轨迹页不属于此任务与执行");
            upper = decoded.getLong("upper"); before = decoded.getLong("before");
            if (upper < 0 || upper == Long.MAX_VALUE || before < 1 || before > upper + 1 || upper > highWater(db, task))
                throw new SecurityException("轨迹页范围已经不可核对");
        }
        JSONObject selected = run(db, task, run, header, control);
        int epoch = selected.optInt("epoch", 0);
        JSONObject history = runs(db, task, header, selected, control);JSONArray runs = history.getJSONArray("runs"), nodes = new JSONArray();
        int examined = 0, sourceBytes = 0, pageBytes = 0, invalid = 0, oversized = 0, unbound = 0;
        boolean limited = false, hasMore = false;
        long resume = before;
        String sql = "SELECT event_order,event_type,body_bytes,call_id FROM task_events WHERE task_id=? AND event_order<=? AND event_order<? ORDER BY event_order DESC LIMIT " + (TaskUsageQuery.MAX_ROWS + 1);
        try (Cursor rows = db.rawQuery(sql, new String[]{task, Long.toString(upper), Long.toString(before)})) {
            while (rows.moveToNext()) {
                control.check();
                long order = rows.getLong(0), bytes = rows.getLong(2);
                if (examined == TaskUsageQuery.MAX_ROWS) { limited = true; hasMore = true; break; }
                examined++;
                String type = rows.getString(1), call = rows.getString(3);
                if (Set.of("operation_intent", "operation_receipt", "model_stream", "inference_request", ExecutionTraceCapture.REQUEST, ExecutionTraceCapture.SOURCES).contains(type)) { resume = order; continue; }
                if (bytes < 0 || bytes > maxBody(type)) { oversized++; limited = true; resume = order; continue; }
                if (sourceBytes + bytes > TaskUsageQuery.MAX_BYTES) { limited = true; hasMore = true; break; }
                JSONObject event = body(db, task, order, bytes); sourceBytes += (int) bytes;
                JSONObject node = null;
                if ("inference_receipt".equals(type)) {
                    JSONObject context = event.optJSONObject("context");
                    if (context == null || !task.equals(context.optString("taskId"))) { invalid++; resume = order; continue; }
                    if (run.equals(context.optString("runId")) && epoch == context.optInt("epoch", -1)) {
                        node = ExecutionTraceProjection.model(event, order);
                        if (node != null) inheritRequestSite(db, task, run, epoch, node, upper);
                    }
                } else if (ToolTraceCapture.TYPE.equals(type)) {
                    if (task.equals(event.optString("taskId")) && run.equals(event.optString("runId")) && epoch == event.optInt("coordinatorEpoch", -1)) {
                        long anchor = anchor(db, task, type, call, upper);
                        if (anchor != order) { resume = order; continue; }
                        JSONObject terminal = latestTool(db, task, call, upper);
                        node = ExecutionTraceProjection.tool(event, terminal, order);
                        if (node != null) inheritModelSite(db, task, run, epoch, node);
                    }
                } else {
                    if ("tool".equals(type) && event.has("traceCallId") && anchor(db, task, ToolTraceCapture.TYPE, event.optString("traceCallId"), upper) > 0) { resume = order; continue; }
                    String provenRun = event.optString("runId");
                    int provenEpoch = event.optInt("coordinatorEpoch", event.optInt("epoch", -1));
                    if (provenRun.isEmpty() && event.has("nativeOperationId")) {
                        try (Cursor op = db.rawQuery("SELECT o.run_id,r.epoch FROM operations o LEFT JOIN runs r ON r.run_id=o.run_id AND r.task_id=o.task_id WHERE o.task_id=? AND o.operation_id=?", new String[]{task, event.optString("nativeOperationId")})) {
                            if (op.moveToFirst()) { provenRun = op.getString(0); provenEpoch = op.isNull(1) ? -1 : op.getInt(1); }
                        }
                    }
                    if (provenRun.isEmpty()) unbound++;
                    else if (run.equals(provenRun) && (provenEpoch < 0 || epoch == provenEpoch)) {node = ExecutionTraceProjection.event(event, order, run, epoch);if(ExecutionTraceCapture.MESSAGE.equals(type))inheritRequestSite(db,task,run,epoch,node,upper);}
                }
                if (node == null) { resume = order; continue; }
                node.put("taskId",task);
                if ("running".equals(node.optString("status")) && Set.of("completed", "failed", "cancelled", "interrupted", "needs_confirmation", "awaiting_user").contains(selected.optString("status")))
                    node.put("status", "unknown").put("terminalObservationMissing", true).put("message", "原执行已结束，未保留此调用的终态；耗时未知");
                node = ExecutionTraceProjection.compact(node);
                int size = node.toString().getBytes(StandardCharsets.UTF_8).length;
                if (nodes.length() == limit || pageBytes + size > MAX_PAGE_BYTES) { hasMore = true; if (pageBytes + size > MAX_PAGE_BYTES) limited = true; break; }
                pageBytes += size; nodes.put(node); resume = order;
            }
        }
        Set<String> visible = new HashSet<>();
        for (int i = 0; i < nodes.length(); i++) visible.add(nodes.getJSONObject(i).getString("id"));
        JSONArray chronological = new JSONArray();
        for (int i = nodes.length() - 1; i >= 0; i--) {
            JSONObject node = nodes.getJSONObject(i); node.put("parentVisible", visible.contains(node.optString("parentId"))); chronological.put(node);
        }
        JSONArray relations = new JSONArray();
        if (!header.optString("parentTaskId").isEmpty()) relations.put(new JSONObject().put("kind", "native_parent_task")
                .put("taskId", header.getString("parentTaskId")).put("runId", header.optString("parentRunId")).put("source", "task_header"));
        control.check();
        return new JSONObject().put("format", "pocketworld.execution-trace.v1").put("taskId", task).put("runId", run).put("epoch", epoch)
                .put("capturedAt", System.currentTimeMillis()).put("runs", runs).put("runsLimited", history.optBoolean("limited")).put("run", selected).put("relations", relations).put("nodes", chronological)
                .put("cursor", cursor == null || cursor.isEmpty() ? encode(task, run, upper, before) : cursor).put("nextCursor", hasMore ? encode(task, run, upper, resume) : "")
                .put("hasMore", hasMore).put("limited", limited || invalid > 0 || oversized > 0).put("limit", limit)
                .put("unboundLegacyEvents", unbound).put("invalidRows", invalid).put("oversizedRows", oversized).put("rowsExamined", examined)
                .put("sourceBytes", sourceBytes).put("pageBytes", pageBytes).put("sourceHighWater", upper)
                .put("coverage", "retained_run_bound_events_only").put("pageSemantics", "fixed_event_membership_receipts_may_finish")
                .put("compact", true).put("bodyIncluded", false)
                .put("readOnly", true).put("executionAuthorized", false);
    }

    /** Exact node lookup is independent of overview pagination and never starts an execution. */
    static JSONObject node(SQLiteDatabase db, String task, String requestedRun, String id, RunControl control) throws Exception {
        if (task == null || !task.matches("[a-fA-F0-9-]{36}") || control == null || control.hasHostRun()) throw new SecurityException("原主人只读身份缺失");
        control.check(); JSONObject header = header(db, task); String run = requestedRun == null || requestedRun.isEmpty() ? header.optString("runId") : requestedRun;
        JSONObject selected = run(db, task, run, header, control); int epoch = selected.getInt("epoch"); long upper = highWater(db, task);
        JSONObject node = null;
        if (id != null && id.matches("model:[a-fA-F0-9-]{36}")) {
            String call = id.substring(6); long order = anchor(db, task, "inference_receipt", call, upper);
            if (order > 0) { JSONObject event = bodyAt(db, task, order), context = event.optJSONObject("context");
                if (context != null && task.equals(context.optString("taskId")) && run.equals(context.optString("runId")) && epoch == context.optInt("epoch", -1)) {
                    node = ExecutionTraceProjection.model(event, order); if (node != null) inheritRequestSite(db, task, run, epoch, node, upper);
                }
            }
        } else if (id != null && id.matches("tool:[a-fA-F0-9-]{36}")) {
            String call = id.substring(5); long order = anchor(db, task, ToolTraceCapture.TYPE, call, upper);
            if (order > 0) { JSONObject start = bodyAt(db, task, order);
                if (task.equals(start.optString("taskId")) && run.equals(start.optString("runId")) && epoch == start.optInt("coordinatorEpoch", -1)) {
                    node = ExecutionTraceProjection.tool(start, latestTool(db, task, call, upper), order); if (node != null) inheritModelSite(db, task, run, epoch, node);
                }
            }
        } else if (id != null && id.matches("event:[1-9][0-9]{0,18}")) {
            long order = Long.parseLong(id.substring(6)); JSONObject event = bodyAt(db, task, order);
            String bound = event.optString("runId"); int boundEpoch = event.optInt("coordinatorEpoch", event.optInt("epoch", -1));
            if (bound.isEmpty() && event.has("nativeOperationId")) try (Cursor row = db.rawQuery("SELECT o.run_id,r.epoch FROM operations o LEFT JOIN runs r ON r.run_id=o.run_id AND r.task_id=o.task_id WHERE o.task_id=? AND o.operation_id=?", new String[]{task,event.getString("nativeOperationId")})) {
                if (row.moveToFirst()) { bound = row.getString(0); boundEpoch = row.isNull(1) ? -1 : row.getInt(1); }
            }
            if (run.equals(bound) && (boundEpoch < 0 || epoch == boundEpoch)) {node = ExecutionTraceProjection.event(event, order, run, epoch);if(ExecutionTraceCapture.MESSAGE.equals(event.optString("type")))inheritRequestSite(db,task,run,epoch,node,upper);}
        }
        if (node == null) throw new SecurityException("所选轨迹节点不属于原任务执行或已不可读");
        if ("running".equals(node.optString("status")) && Set.of("completed","failed","cancelled","interrupted","needs_confirmation","awaiting_user").contains(selected.optString("status"))) node.put("status","unknown").put("terminalObservationMissing",true);
        control.check(); return node.put("taskId",task);
    }

    /** Current-run native tool observations for the existing explicit diagnostics exporter. */
    static JSONObject toolSource(SQLiteDatabase db, String task, String run, int epoch, RunControl control) throws Exception {
        JSONArray calls = new JSONArray(); StringBuilder revision = new StringBuilder(); int examined = 0, bytes = 0; boolean limited = false;
        long upper = highWater(db, task);
        try (Cursor rows = db.rawQuery("SELECT event_order,call_id,body_bytes,row_revision FROM task_events WHERE task_id=? AND event_type='tool_trace' ORDER BY event_order DESC LIMIT 1025", new String[]{task})) {
            Set<String> seen = new HashSet<>();
            while (rows.moveToNext()) {
                control.check(); if (examined++ == 1024 || calls.length() == 512) { limited = true; break; }
                long size = rows.getLong(2); if (size < 0 || size > maxBody(ToolTraceCapture.TYPE) || bytes + size > TaskUsageQuery.MAX_BYTES) { limited = true; continue; }
                String call = rows.getString(1); if (call == null || !seen.add(call)) continue;
                JSONObject event = body(db, task, rows.getLong(0), size); bytes += (int) size;
                if (!task.equals(event.optString("taskId")) || !run.equals(event.optString("runId")) || epoch != event.optInt("coordinatorEpoch", -1)) continue;
                long first = anchor(db, task, ToolTraceCapture.TYPE, call, upper);
                JSONObject start = bodyAt(db, task, first);
                JSONObject node = ExecutionTraceProjection.tool(start, "finished".equals(event.optString("stage")) ? event : null, first);
                if (node == null) { limited = true; continue; }
                calls.put(node); revision.append('|').append(first).append(':').append(rows.getLong(0)).append(':').append(rows.getLong(3));
            }
        }
        return new JSONObject().put("toolCalls", calls).put("revision", revision.toString()).put("limited", limited);
    }

    /** Exact native call-site match. Ambiguous or missing physical ancestry stays absent. */
    static String completedCallAtSite(SQLiteDatabase db, String task, String run, String member, String turn) throws Exception {
        String found = ""; int bytes = 0;
        try (Cursor rows = db.rawQuery("SELECT event_order,body_bytes FROM task_events WHERE task_id=? AND event_type='inference_receipt' ORDER BY event_order DESC LIMIT 512", new String[]{task})) {
            while (rows.moveToNext()) {
                long size = rows.getLong(1); if (size < 0 || size > TaskUsageQuery.MAX_RECEIPT_BYTES) continue;
                if (bytes + size > TaskUsageQuery.MAX_BYTES) return ""; bytes += (int) size;
                JSONObject event = body(db, task, rows.getLong(0), size), context = event.optJSONObject("context"), site = context == null ? null : context.optJSONObject("callSite");
                if (context == null || site == null || !task.equals(context.optString("taskId")) || !run.equals(context.optString("runId")) || !member.equals(context.optString("memberId")) || !turn.equals(site.optString("turnId")) || !"COMPLETED".equals(event.optString("outcome"))) continue;
                if (!found.isEmpty() && !found.equals(event.optString("callId"))) return "";
                found = event.optString("callId");
            }
        }
        return found;
    }

    private static JSONObject header(SQLiteDatabase db, String task) throws Exception {
        try (Cursor row = db.rawQuery("SELECT ui_header FROM tasks WHERE task_id=?", new String[]{task})) {
            if (!row.moveToFirst() || row.isNull(0) || row.getString(0).length() > 32768) throw new SecurityException("原任务来源已不可读");
            JSONObject header = new JSONObject(row.getString(0));
            JSONObject origin = header.optJSONObject("origin");
            boolean nativeTeam = "owner".equals(header.optString("actorId")) && "private".equals(header.optString("spaceId")) && !header.optBoolean("metadataLimited")
                    && origin != null && Set.of("team_assignment", "team_integration", "team_review", "team_phone").contains(origin.optString("kind"))
                    && !"EXTERNAL_GROUP".equals(origin.optString("inferenceAudience")) && !origin.has("groupInputSource") && !origin.has("externalGroupApiMode");
            if (!TaskUsageQuery.privateHeader(header) && !nativeTeam) throw new SecurityException("轨迹只读取本机主人私人任务");
            return header;
        }
    }
    private static JSONObject run(SQLiteDatabase db, String task, String run, JSONObject header, RunControl control) throws Exception {
        try (Cursor row = db.rawQuery("SELECT run_id,epoch,status,created_at,updated_at FROM runs WHERE task_id=? AND run_id=?", new String[]{task, run})) {
            if (row.moveToFirst()) return runRow(row);
        }
        if (run.equals(header.optString("runId"))) return new JSONObject().put("runId", run).put("epoch", header.optInt("coordinatorEpoch", 0))
                .put("status", header.optString("status")).put("startedAt", JSONObject.NULL).put("updatedAt", header.optLong("updatedAt", 0)).put("source", "current_task_header");
        // External task runs use epoch zero and may not have a HostRun row. Accept only a retained
        // native receipt's exact context; no run is reconstructed from timestamps or guessed order.
        int sourceBytes = 0;
        try (Cursor rows = db.rawQuery("SELECT event_order,body_bytes FROM task_events WHERE task_id=? AND event_type='inference_receipt' ORDER BY event_order DESC LIMIT 8192", new String[]{task})) {
            while (rows.moveToNext()) {
                control.check();long size = rows.getLong(1); if (size < 0 || size > TaskUsageQuery.MAX_RECEIPT_BYTES) continue;
                if (sourceBytes + size > TaskUsageQuery.MAX_BYTES) break;sourceBytes += (int) size;
                JSONObject event = body(db, task, rows.getLong(0), size), context = event.optJSONObject("context");
                if (context != null && task.equals(context.optString("taskId")) && run.equals(context.optString("runId")) && "EXTERNAL_TASK_RUN".equals(context.optString("bindingKind")) && context.optInt("epoch", -1) == 0)
                    return new JSONObject().put("runId", run).put("epoch", 0).put("status", "retained_external_run").put("startedAt", JSONObject.NULL).put("updatedAt", JSONObject.NULL).put("source", "native_inference_context");
            }
        }
        throw new SecurityException("此执行不属于原任务或已不在保留范围内");
    }
    private static JSONObject runs(SQLiteDatabase db, String task, JSONObject header, JSONObject selected, RunControl control) throws Exception {
        JSONArray result = new JSONArray(); Set<String> seen = new HashSet<>();boolean limited = false;
        if (!selected.optString("runId").isEmpty()) { result.put(selected);seen.add(selected.getString("runId")); }
        String current = header.optString("runId");if (!current.isEmpty() && seen.add(current)) result.put(run(db, task, current, header, control));
        try (Cursor rows = db.rawQuery("SELECT run_id,epoch,status,created_at,updated_at FROM runs WHERE task_id=? ORDER BY epoch DESC LIMIT 201", new String[]{task})) {
            while (rows.moveToNext()) { control.check();if (!seen.add(rows.getString(0))) continue;if (result.length() == 200) { limited = true;break; }result.put(runRow(rows)); }
        }
        // External native coordinator runs have no HostRun row. Discover them from the original
        // content-free receipts with the same bounded SQL read budget, including epoch zero.
        int bytes = 0, examined = 0;
        try (Cursor rows = db.rawQuery("SELECT event_order,body_bytes FROM task_events WHERE task_id=? AND event_type='inference_receipt' ORDER BY event_order DESC LIMIT 8193", new String[]{task})) {
            while (rows.moveToNext()) {
                control.check();if (examined++ == TaskUsageQuery.MAX_ROWS) { limited = true;break; }
                long size = rows.getLong(1);if (size < 0 || size > TaskUsageQuery.MAX_RECEIPT_BYTES) { limited = true;continue; }
                if (bytes + size > TaskUsageQuery.MAX_BYTES) { limited = true;break; }bytes += (int) size;
                JSONObject event = body(db, task, rows.getLong(0), size), context = event.optJSONObject("context");
                if (context == null || !task.equals(context.optString("taskId")) || !"EXTERNAL_TASK_RUN".equals(context.optString("bindingKind")) || context.optInt("epoch", -1) != 0 || context.optString("runId").isEmpty()) continue;
                String external = context.getString("runId");if (!seen.add(external)) continue;
                if (result.length() == 200) { limited = true;break; }
                result.put(new JSONObject().put("runId", external).put("epoch", 0).put("status", "retained_external_run").put("startedAt", JSONObject.NULL).put("updatedAt", JSONObject.NULL).put("source", "native_inference_context"));
            }
        }
        return new JSONObject().put("runs", result).put("limited", limited);
    }
    private static JSONObject runRow(Cursor row) throws Exception {
        return new JSONObject().put("runId", row.getString(0)).put("epoch", row.getInt(1)).put("status", row.getString(2))
                .put("startedAt", row.getLong(3)).put("updatedAt", row.getLong(4)).put("source", "native_run_row");
    }
    private static long highWater(SQLiteDatabase db, String task) {
        try (Cursor row = db.rawQuery("SELECT COALESCE(MAX(event_order),0) FROM task_events WHERE task_id=?", new String[]{task})) { row.moveToFirst(); return row.getLong(0); }
    }
    private static long anchor(SQLiteDatabase db, String task, String type, String call, long upper) {
        if (call == null || call.isEmpty()) return -1;
        try (Cursor row = db.rawQuery("SELECT MIN(event_order) FROM task_events WHERE task_id=? AND event_type=? AND call_id=? AND event_order<=?", new String[]{task, type, call, Long.toString(upper)})) { return row.moveToFirst() && !row.isNull(0) ? row.getLong(0) : -1; }
    }
    private static JSONObject latestTool(SQLiteDatabase db, String task, String call, long upper) throws Exception {
        try (Cursor row = db.rawQuery("SELECT event_order,body_bytes FROM task_events WHERE task_id=? AND event_type='tool_trace' AND call_id=? AND event_order<=? ORDER BY event_order DESC LIMIT 1", new String[]{task, call, Long.toString(upper)})) {
            if (!row.moveToFirst() || row.getLong(1) < 0 || row.getLong(1) > maxBody(ToolTraceCapture.TYPE)) return null;
            JSONObject event = body(db, task, row.getLong(0), row.getLong(1)); return "finished".equals(event.optString("stage")) ? event : null;
        }
    }
    private static JSONObject bodyAt(SQLiteDatabase db, String task, long order) throws Exception {
        try (Cursor row = db.rawQuery("SELECT body_bytes FROM task_events WHERE task_id=? AND event_order=?", new String[]{task, Long.toString(order)})) {
            if (!row.moveToFirst() || row.getLong(0) < 0 || row.getLong(0) > 262144) throw new SecurityException("原轨迹事件超过读取范围");
            return body(db, task, order, row.getLong(0));
        }
    }
    private static JSONObject body(SQLiteDatabase db, String task, long order, long expectedBytes) throws Exception {
        try (Cursor row = db.rawQuery("SELECT event_json FROM task_events WHERE task_id=? AND event_order=?", new String[]{task, Long.toString(order)})) {
            if (!row.moveToFirst()) throw new SecurityException("原轨迹事件已被移除");
            String text = row.getString(0); if (text.getBytes(StandardCharsets.UTF_8).length != expectedBytes) throw new SecurityException("原轨迹事件大小已改变");
            return new JSONObject(text);
        }
    }
    private static void inheritModelSite(SQLiteDatabase db, String task, String run, int epoch, JSONObject tool) throws Exception {
        String call = tool.optString("parentCallId"); if (!call.matches("[a-fA-F0-9-]{36}")) return;
        try (Cursor row = db.rawQuery("SELECT event_order,body_bytes FROM task_events WHERE task_id=? AND event_type='inference_receipt' AND call_id=? LIMIT 1", new String[]{task, call})) {
            if (!row.moveToFirst() || row.getLong(1) < 0 || row.getLong(1) > TaskUsageQuery.MAX_RECEIPT_BYTES) return;
            JSONObject event = body(db, task, row.getLong(0), row.getLong(1)), context = event.optJSONObject("context");
            if (context == null || !task.equals(context.optString("taskId")) || !run.equals(context.optString("runId")) || epoch != context.optInt("epoch", -1)) return;
            JSONObject route = event.optJSONObject("route"); if (route != null) tool.put("logicalRequestId", route.optString("logicalRequestId")).put("attemptNo", route.optInt("attemptNo", 0));
            JSONObject model = ExecutionTraceProjection.model(event, row.getLong(0));
            if (model != null) { inheritRequestSite(db, task, run, epoch, model, highWater(db,task));
                for (String key : new String[]{"turnId", "turnIndex", "callSite", "checkpointId", "discussionRound", "executionTurnId", "sourcePlan"}) if (model.has(key) && !model.isNull(key)) tool.put(key, model.get(key));
            }
        }
    }
    private static void inheritRequestSite(SQLiteDatabase db,String task,String run,int epoch,JSONObject node,long upper)throws Exception {
        String call=node.optString("callId");
        try(Cursor row=db.rawQuery("SELECT event_order,body_bytes FROM task_events WHERE task_id=? AND event_type=? AND call_id=? AND event_order<=? ORDER BY event_order LIMIT 1",new String[]{task,ExecutionTraceCapture.SOURCES,call,Long.toString(upper)})){
            if(row.moveToFirst()&&row.getLong(1)>=0&&row.getLong(1)<=262144){JSONObject source=body(db,task,row.getLong(0),row.getLong(1));if(source.optInt("traceVersion")==1&&source.optBoolean("evidenceOnly")&&"native_store".equals(source.optString("source"))&&task.equals(source.optString("taskId"))&&run.equals(source.optString("runId"))&&epoch==source.optInt("coordinatorEpoch",-1)&&source.optJSONObject("sourcePlan")!=null)node.put("sourcePlan",source.getJSONObject("sourcePlan"));}
        }
        try(Cursor row=db.rawQuery("SELECT event_order,body_bytes FROM task_events WHERE task_id=? AND event_type=? AND call_id=? AND event_order<=? ORDER BY event_order LIMIT 1",new String[]{task,ExecutionTraceCapture.REQUEST,call,Long.toString(upper)})){
            if(!row.moveToFirst()||row.getLong(1)<0||row.getLong(1)>262144)return;JSONObject original=body(db,task,row.getLong(0),row.getLong(1));
            if(!Set.of("native_runner","native_coordinator").contains(original.optString("source"))||original.optInt("traceVersion")!=1||!original.optBoolean("evidenceOnly")||!task.equals(original.optString("taskId"))||!run.equals(original.optString("runId"))||epoch!=original.optInt("coordinatorEpoch",-1)||!call.equals(original.optString("callId")))return;
            for(String key:new String[]{"turnId","turnIndex","stepId","stepIndex","callSite","sourceRefs","contextRefCount","contextRefsRetained","contextRefsLimited","checkpointId","discussionRound","executionTurnId"})if(original.has(key))node.put(key,original.get(key));
            node.put("contextMeaning",original.optString("contextMeaning","original_checkpoint_context_not_final_wire"));
        }
    }
    private static int maxBody(String type){return ToolTraceCapture.TYPE.equals(type)||ExecutionTraceCapture.REQUEST.equals(type)?262144:TaskUsageQuery.MAX_RECEIPT_BYTES;}
    private static String encode(String task, String run, long upper, long before) throws Exception {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(new JSONObject().put("v", 1).put("taskId", task).put("runId", run).put("upper", upper).put("before", before).toString().getBytes(StandardCharsets.UTF_8));
    }
    private static JSONObject decode(String cursor) throws Exception {
        if (cursor.length() > 1536) throw new IllegalArgumentException("轨迹页游标过长");
        try { return new JSONObject(new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8)); }
        catch (Exception malformed) { throw new IllegalArgumentException("轨迹页游标无效", malformed); }
    }
}
