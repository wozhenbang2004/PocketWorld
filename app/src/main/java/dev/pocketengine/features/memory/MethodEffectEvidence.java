package dev.pocketengine.features.memory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Body-free, frozen evidence on an original operation. This is not a success verifier,
 * permission, new execution ledger, or proof that every external condition was controlled. */
public final class MethodEffectEvidence {
    public static final String FORMAT = "pocketworld.method-effect-evidence.v1";
    private static final int MAX_STEPS = 8;
    private MethodEffectEvidence() {}

    /** Called by TaskStore after admitting the exact Book selection and original settled steps. */
    public static JSONObject captureTaskDecision(JSONObject decision, JSONObject task) throws Exception {
        requireDecision(decision);
        if (!decision.getString("taskId").equals(task.optString("id"))
                || !decision.getString("runId").equals(task.optString("runId"))
                || integer(decision, "epoch") != integer(task, "coordinatorEpoch")
                || !"owner".equals(task.optString("actorId")) || !"private".equals(task.optString("spaceId"))
                || task.optBoolean("cancelRequested"))
            throw new SecurityException("方法效果证据不属于原私人执行代次");
        JSONArray steps = checkedSteps(decision.getJSONArray("stepRefs"));
        JSONObject input = new JSONObject().put("goal", task.getString("goal"));
        if (task.getString("goal").length() > 16000) throw new SecurityException("原目标超过方法证据范围");
        JSONArray inputs = task.optJSONArray("ownerInputs"), admitted = new JSONArray(); boolean exactInputVersions = true;
        if (inputs != null && inputs.length() > 1000) throw new SecurityException("原输入超过有界证据范围");
        for (int i = 0; inputs != null && i < inputs.length(); i++) {
            JSONObject row = inputs.getJSONObject(i);
            if (!decision.getString("runId").equals(row.optString("materializedRunId"))) continue;
            if (!"materialized".equals(row.optString("queueState")))
                throw new SecurityException("原方法使用的输入已撤回或不可核对");
            JSONObject identity = new JSONObject().put("payloadSha256", hashValue(row, "payloadSha256"));
            if (row.has("ownerMessageRevision")) identity.put("revision", integer(row, "ownerMessageRevision"));
            else exactInputVersions = false;
            admitted.put(identity);
        }
        input.put("materializedInputs", admitted);
        JSONArray arguments = new JSONArray();
        boolean exactArguments = steps.length() > 0 && exactInputVersions;
        for (int i = 0; i < steps.length(); i++) {
            JSONObject step = steps.getJSONObject(i), parameter = new JSONObject().put("tool", step.getString("tool"));
            if (isHash(step.optString("argumentsFingerprint"))) parameter.put("argumentsFingerprint", step.getString("argumentsFingerprint"));
            else exactArguments = false;
            arguments.put(parameter);
        }
        input.put("operationInputs", arguments);
        JSONObject environment = taskEnvironment(task, decision, steps);
        JSONObject result = base(decision).put("implementationFingerprint", hashValue(decision, "recordFingerprint"))
                .put("inputFingerprint", digest(input))
                .put("inputCoverage", exactArguments ? "registered_goal_materialized_inputs_and_operation_arguments" : "registered_goal_and_available_inputs")
                .put("inputComparable", exactArguments).put("inputCount", admitted.length())
                .put("environmentCoverage", environment.getString("coverage"))
                .put("environmentComparable", environment.getBoolean("comparable"))
                .put("environmentFingerprint", digest(environment.getJSONObject("identity")))
                .put("stepRefsFingerprint", digest(new JSONObject().put("steps", steps)))
                .put("stepCount", steps.length()).put("source", "original_task_store")
                .put("capturedAt", integer(decision, "recordedAt"));
        return seal(result);
    }

    /** The original Skill review already pins exact request bytes and a probed backend identity. */
    public static JSONObject captureSkillAttempt(JSONObject original, JSONObject projection) throws Exception {
        JSONObject reference = original.getJSONObject("reference"), review = original.getJSONObject("review");
        JSONObject request = review.optJSONObject("request"), receipt = original.optJSONObject("receipt"), pack = review.getJSONObject("package");
        boolean exactPlan = originalSkillReviewBound(review, reference);
        JSONObject implementation = new JSONObject();
        for (String key : new String[]{"id", "revision", "digest", "executionGeneration"}) if (pack.has(key)) implementation.put(key, pack.get(key));
        if (review.has("scriptSha256")) implementation.put("scriptSha256", review.get("scriptSha256"));
        boolean exactImplementation = implementation.has("id") && implementation.has("revision") && implementation.has("digest");
        JSONObject result = base(projection).put("source", "original_skill_operation")
                .put("implementationFingerprint", exactImplementation ? digest(implementation) : "")
                .put("implementationRef", implementation)
                .put("inputComparable", request != null && exactImplementation && exactPlan)
                .put("inputCoverage", request == null || !exactPlan ? "unavailable_legacy" : "frozen_skill_request")
                .put("inputFingerprint", request == null ? "" : digest(request))
                .put("environmentComparable", exactPlan).put("environmentCoverage", exactPlan ? "probed_backend_and_workspace_revision" : "unavailable_legacy")
                .put("environmentFingerprint", digest(new JSONObject().put("environmentBinding", hashValue(reference, "environmentBinding"))
                        .put("workspaceRevision", reference.getString("workspaceRevision"))))
                .put("originalReceiptFingerprint", receipt == null ? "" : digest(receipt))
                .put("stepCount", receipt == null ? 0 : 1)
                .put("stepRefsFingerprint", digest(new JSONObject().put("planDigest", hashValue(reference, "planDigest"))
                        .put("receiptFingerprint", receipt == null ? JSONObject.NULL : digest(receipt))))
                .put("capturedAt", original.optLong("claimedAt", 0));
        return seal(result);
    }

    private static boolean originalSkillReviewBound(JSONObject review, JSONObject reference) throws Exception {
        if (!review.has("planDigest")) return false; // Old incomplete receipts stay readable.
        JSONObject unsigned = new JSONObject(review.toString()); String claimed = hashValue(unsigned, "planDigest"); unsigned.remove("planDigest");
        if (!claimed.equals(hashValue(reference, "planDigest")) || !claimed.equals(digest(unsigned)))
            throw new SecurityException("原方法执行计划已变化");
        JSONObject run = review.getJSONObject("run"), backend = review.getJSONObject("backend");
        for (String key : new String[]{"taskId", "runId", "epoch", "ownerId", "spaceId"})
            if (!String.valueOf(run.get(key)).equals(String.valueOf(reference.get(key))))
                throw new SecurityException("原方法执行计划不属于同一任务代次");
        if (!digest(backend).equals(hashValue(reference, "environmentBinding"))
                || !backend.getString("workspaceRevision").equals(reference.getString("workspaceRevision")))
            throw new SecurityException("原方法执行环境已变化");
        return true;
    }

    /** Revalidate a persisted projection against its exact original decision before displaying it. */
    public static void requireBinding(JSONObject method) throws Exception {
        JSONObject evidence = method.optJSONObject("effectEvidence");
        if (evidence == null) return; // Historical rows remain readable, explicitly non-comparable.
        checked(evidence);
        JSONObject expected = base(method);
        for (String key : new String[]{"taskId", "runId", "operationId", "recordId", "recordRevision", "ownerId", "spaceId", "epoch"})
            if (!String.valueOf(expected.get(key)).equals(String.valueOf(evidence.get(key))))
                throw new SecurityException("方法效果证据绑定已变化");
        if (method.has("recordFingerprint") && !method.getString("recordFingerprint").equals(evidence.optString("recordFingerprint")))
            throw new SecurityException("方法效果证据属于另一正文版本");
        if (method.optJSONArray("stepRefs") != null && !digest(new JSONObject().put("steps", checkedSteps(method.getJSONArray("stepRefs")))).equals(evidence.getString("stepRefsFingerprint")))
            throw new SecurityException("方法原步骤或结果指纹已变化");
    }

    /** Small immutable link for owner feedback; never duplicates a task goal or tool body. */
    static JSONObject qualityLink(JSONObject method) throws Exception {
        requireBinding(method);
        JSONObject evidence = method.optJSONObject("effectEvidence");
        JSONObject link = new JSONObject().put("stepRefsFingerprint", digest(new JSONObject().put("steps", checkedSteps(method.getJSONArray("stepRefs")))))
                .put("recordFingerprint", method.optString("recordFingerprint"))
                .put("evaluationCoverage", evidence == null ? "legacy_original_steps_only" : "frozen_original_inputs_environment_and_steps");
        if (evidence != null) for (String key : new String[]{"evidenceFingerprint", "inputFingerprint", "inputCoverage", "environmentFingerprint", "environmentCoverage"})
            link.put(key, evidence.get(key));
        return link;
    }

    static JSONObject checked(JSONObject evidence) throws Exception {
        if (!FORMAT.equals(evidence.optString("format")) || evidence.toString().length() > 12000)
            throw new SecurityException("方法效果证据格式或大小无效");
        JSONObject unsigned = new JSONObject(evidence.toString());
        String expected = hashValue(unsigned, "evidenceFingerprint"); unsigned.remove("evidenceFingerprint");
        if (!expected.equals(digest(unsigned)) || evidence.optBoolean("causalEffectVerified", true)
                || evidence.optBoolean("businessSuccessVerified", true) || evidence.optBoolean("executionAuthorized", true)
                || evidence.optBoolean("replayAllowed", true) || evidence.optDouble("confidenceDelta", Double.NaN) != 0)
            throw new SecurityException("方法效果证据被修改或含越权结论");
        return evidence;
    }

    private static JSONObject taskEnvironment(JSONObject task, JSONObject decision, JSONArray steps) throws Exception {
        JSONArray observations = task.optJSONArray("nativeAppObservations"), identities = new JSONArray();
        if (observations != null && observations.length() > 200) throw new SecurityException("原应用环境观察超过范围");
        Set<String> seen = new HashSet<>(); int covered = 0;
        for (int i = 0; i < steps.length(); i++) {
            JSONObject step = steps.getJSONObject(i), identity = null;
            String observation = step.optString("observationId");
            for (int j = 0; !observation.isEmpty() && observations != null && j < observations.length(); j++) {
                JSONObject row = observations.getJSONObject(j), app = row.optJSONObject("app");
                if (!observation.equals(row.optString("observationId")) || !decision.getString("runId").equals(row.optString("runId"))
                        || integer(decision, "epoch") != row.optLong("epoch") || app == null
                        || !"android_package_manager".equals(app.optString("source"))) continue;
                identity = new JSONObject().put("kind", "observed_android_package").put("packageName", app.getString("packageName"))
                        .put("versionCode", SourceRef.integer(app, "versionCode", 0)).put("lastUpdateTime", SourceRef.integer(app, "lastUpdateTime", 0));
                break;
            }
            JSONObject workspace = step.optJSONObject("workspaceSource");
            if (identity == null && workspace != null && step.getString("tool").startsWith("workspace_"))
                identity = new JSONObject().put("kind", "versioned_workspace").put("source", new JSONObject(workspace.toString()));
            if (identity != null) { covered++; String key = digest(identity); if (seen.add(key)) identities.put(identity); }
        }
        List<String> sorted = new ArrayList<>();
        for (int i = 0; i < identities.length(); i++) sorted.add(canonical(identities.getJSONObject(i)));
        Collections.sort(sorted);
        boolean comparable = steps.length() > 0 && covered == steps.length();
        return new JSONObject().put("identity", new JSONObject().put("executionMode", task.optString("executionMode"))
                .put("observedIdentities", new JSONArray(sorted)))
                .put("comparable", comparable).put("coverage", comparable ? "selected_native_operation_environment" : covered == 0 ? "unobserved" : "partial_native_operation_environment");
    }

    private static JSONObject base(JSONObject method) throws Exception {
        String owner = method.getString("ownerId"), space = method.getString("spaceId");
        if (!"owner".equals(owner) || !"private".equals(space)) throw new SecurityException("方法效果证据不能跨受众");
        JSONObject result = new JSONObject().put("format", FORMAT).put("ownerId", owner).put("spaceId", space)
                .put("taskId", bounded(method, "taskId", 240)).put("runId", bounded(method, "runId", 240))
                .put("operationId", bounded(method, "operationId", 240)).put("epoch", integer(method, "epoch"))
                .put("recordId", bounded(method, "recordId", 240))
                .put("recordRevision", integer(method, method.has("recordRevision") ? "recordRevision" : "revision"))
                .put("evidenceRelation", "observational").put("allExternalConditionsControlled", false)
                .put("causalEffectVerified", false).put("businessSuccessVerified", false)
                .put("confidenceDelta", 0).put("executionAuthorized", false).put("replayAllowed", false);
        if (method.has("recordFingerprint")) result.put("recordFingerprint", hashValue(method, "recordFingerprint"));
        return result;
    }

    private static void requireDecision(JSONObject method) throws Exception {
        if (!"pocketworld.learned-method-decision.v1".equals(method.optString("format")))
            throw new SecurityException("不是原TaskStore方法决定");
        String decision = method.getString("decision");
        if (!Set.of("apply", "not_applicable", "not_selected").contains(decision)) throw new SecurityException("方法决定无效");
        int count = method.getJSONArray("stepRefs").length();
        if (count > MAX_STEPS || decision.equals("apply") && count == 0 || !decision.equals("apply") && count != 0)
            throw new SecurityException("方法采用与原步骤不一致");
    }

    private static JSONArray checkedSteps(JSONArray steps) throws Exception {
        if (steps.length() > MAX_STEPS) throw new SecurityException("方法原步骤超过8项");
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < steps.length(); i++) {
            JSONObject step = steps.getJSONObject(i);
            if (!seen.add(bounded(step, "operationId", 240)) || !"receipt".equals(step.optString("operationState")))
                throw new SecurityException("原方法步骤重复或尚无回执");
            bounded(step, "tool", 160); hashValue(step, "receiptFingerprint");
        }
        return steps;
    }

    private static JSONObject seal(JSONObject value) throws Exception { return value.put("evidenceFingerprint", digest(value)); }
    private static long integer(JSONObject value, String key) throws Exception { return SourceRef.integer(value, key, 1); }
    private static String bounded(JSONObject value, String key, int max) throws Exception {
        Object raw = value.opt(key);
        if (!(raw instanceof String) || ((String) raw).isBlank() || ((String) raw).length() > max)
            throw new SecurityException("方法证据字段无效：" + key);
        return (String) raw;
    }
    private static boolean isHash(String value) { return value != null && value.matches("[a-f0-9]{64}"); }
    private static String hashValue(JSONObject value, String key) throws Exception {
        String hash = bounded(value, key, 64);
        if (!isHash(hash)) throw new SecurityException("方法证据摘要无效：" + key);
        return hash;
    }
    static String digest(JSONObject value) throws Exception { return TaskMemoryUse.digest(canonical(value)); }
    private static String canonical(Object value) throws Exception {
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value; List<String> keys = new ArrayList<>(); object.keys().forEachRemaining(keys::add); Collections.sort(keys);
            StringBuilder out = new StringBuilder("{");
            for (String key : keys) { if (out.length() > 1) out.append(','); out.append(JSONObject.quote(key)).append(':').append(canonical(object.get(key))); }
            return out.append('}').toString();
        }
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value; StringBuilder out = new StringBuilder("[");
            for (int i = 0; i < array.length(); i++) { if (i > 0) out.append(','); out.append(canonical(array.get(i))); }
            return out.append(']').toString();
        }
        return value == JSONObject.NULL ? "null" : value instanceof String ? JSONObject.quote((String) value) : String.valueOf(value);
    }
}
