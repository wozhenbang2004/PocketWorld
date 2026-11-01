package dev.pocketengine.runtime.context;

import dev.pocketengine.core.ModelConfig;
import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Ephemeral projection/provenance for one existing AgentRunner invocation; never task authority. */
public final class RunContextBudget {
    private final ContextBudgetPolicy.Source source;
    private final Map<JSONObject,String> hostObservations = new IdentityHashMap<>();
    private final Map<JSONObject,String> projectedObservations = new IdentityHashMap<>();

    public RunContextBudget(ContextBudgetPolicy.Source source) { this.source = java.util.Objects.requireNonNull(source); }

    /** Before a native producer captures pixels, check this exact run's image qualification and reserve.
     * Actual bytes/history are still checked at the normal checkpoint and request boundaries. */
    public void requireImageInput(ModelConfig config)throws Blocked{
        try{
            if(!config.sendScreenshots||!config.supportsImageInput)throw new Blocked("image_input_not_authorized","native_visual_before_dispatch",new JSONObject());
            ContextBudgetPolicy.Resolved current=ContextBudgetPolicy.resolve(source,config,ContextBudgetPolicy.REQUEST_BYTES);
            if(current.limits.tokenWindowKnown&&(current.limits.tokensPerImage<=0||current.limits.tokensPerImage>=current.limits.inputLimit()))throw new Blocked("image_budget_unknown","native_visual_before_dispatch",new JSONObject());
        }catch(Blocked blocked){throw blocked;}catch(Exception unavailable){throw unavailable("native_visual_before_dispatch");}
    }
    /** Called only where this runner actually appends a native observation, never for owner input. */
    public void observed(JSONObject message, JSONObject nativeObservation) throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        fingerprint(nativeObservation,digest,true,false);
        hostObservations.put(message,java.util.Base64.getEncoder().encodeToString(digest.digest()));
    }
    public String observationFingerprint(JSONObject message) {
        String nativeProof = hostObservations.get(message);
        return nativeProof == null ? projectedObservations.get(message) : nativeProof;
    }

    /** A native row-preserving projection may copy JSON and remove private journal metadata.
     * Transfer existing proof only at the same index with identical role and full content/pixels;
     * an owner lookalike or any changed projected observation never gains native provenance.
     */
    public void projected(JSONArray original, JSONArray projected) {
        // Retain one projected boundary only; old wire copies can hold large pixel payloads.
        projectedObservations.clear();
        if (original.length() != projected.length()) return;
        for (int i = 0; i < original.length(); i++) {
            JSONObject source = original.optJSONObject(i), target = projected.optJSONObject(i);
            String proof = hostObservations.get(source);
            if (proof != null && target != null && "user".equals(target.optString("role"))
                    && !target.has("tool_calls") && sameContent(source.opt("content"), target.opt("content")))
                projectedObservations.put(target, proof);
        }
    }

    private static boolean sameContent(Object first, Object second) {
        if (first == second) return true;
        if (first instanceof JSONObject && second instanceof JSONObject) {
            JSONObject a = (JSONObject) first, b = (JSONObject) second;
            if (a.length() != b.length()) return false;
            Iterator<String> keys = a.keys();
            while (keys.hasNext()) { String key = keys.next(); if (!b.has(key) || !sameContent(a.opt(key), b.opt(key))) return false; }
            return true;
        }
        if (first instanceof JSONArray && second instanceof JSONArray) {
            JSONArray a = (JSONArray) first, b = (JSONArray) second;
            if (a.length() != b.length()) return false;
            for (int i = 0; i < a.length(); i++) if (!sameContent(a.opt(i), b.opt(i))) return false;
            return true;
        }
        return first != null && first.equals(second);
    }

    public Projection request(JSONArray messages, JSONArray tools, ModelConfig config) throws Blocked {
        Map<Integer,String> observed = new LinkedHashMap<>();
        for (int i = 0; i < messages.length(); i++) {
            String fingerprint=observationFingerprint(messages.optJSONObject(i));
            if(fingerprint!=null)observed.put(i,fingerprint);
        }
        return project(messages, tools, config, ContextBudgetPolicy.REQUEST_BYTES, observed, "request");
    }

    public Projection checkpoint(JSONArray messages, Map<Integer,String> observations, int steps,
                                          JSONObject observation, ModelConfig config) throws Blocked {
        try {
            // Count the actual host checkpoint envelope separately. Canonical projection also
            // charges its own tools:[] framing, making this remaining-byte bound conservative.
            long envelope = new JSONObject().put("messages", new JSONArray()).put("steps", steps)
                    .put("observation", observation).toString().getBytes(StandardCharsets.UTF_8).length;
            long remaining = ContextBudgetPolicy.CHECKPOINT_BYTES - envelope;
            if (remaining < 1) throw new Blocked("checkpoint_envelope_exceeded", "checkpoint", new JSONObject()
                    .put("checkpointEnvelopeBytes", envelope).put("maxCheckpointBytes", ContextBudgetPolicy.CHECKPOINT_BYTES));
            return project(messages, null, config, remaining, observations, "checkpoint");
        } catch (Blocked blocked) { throw blocked; }
        catch (Exception unavailable) { throw unavailable("checkpoint"); }
    }

    public record Projection(ContextWindow.Plan plan, JSONObject diagnostics) { }

    /** H4 keeps source rows intact. Model token limits apply to requests, not a raw source archive. */
    public Projection rawCheckpoint(JSONArray messages,int steps,JSONObject observation)throws Blocked {
        try{
            long envelope=new JSONObject().put("messages",new JSONArray()).put("steps",steps).put("observation",observation).toString().getBytes(StandardCharsets.UTF_8).length;
            long remaining=ContextBudgetPolicy.CHECKPOINT_BYTES-envelope;
            if(remaining<1)throw new Blocked("checkpoint_envelope_exceeded","checkpoint",new JSONObject());
            ContextWindow.Plan plan=ContextWindow.plan(messages,null,ContextWindow.Budget.localBytes(remaining),ContextWindow.Retention.preserveAll());
            JSONObject diagnostics=plan.diagnostics().put("boundary","checkpoint").put("retentionPolicy","raw_source_preserve_all")
                    .put("rawRetained",true).put("summariesGenerated",false).put("toolResultsAutomaticallyEvicted",false);
            if(!plan.canSend())throw new Blocked("raw_checkpoint_"+plan.status,"checkpoint",diagnostics);
            return new Projection(plan,diagnostics);
        }catch(Blocked blocked){throw blocked;}catch(Exception unavailable){throw unavailable("checkpoint");}
    }

    private Projection project(JSONArray messages, JSONArray tools, ModelConfig config, long byteCeiling,
                                       Map<Integer,String> observations, String boundary) throws Blocked {
        try {
            ContextBudgetPolicy.Resolved selected = ContextBudgetPolicy.resolve(source, config, byteCeiling);
            Map<Integer, ContextWindow.Eviction> optional = new LinkedHashMap<>();
            // Expired references do not make page contents dispensable. Only an older native
            // observation with exactly the same canonical content fingerprint as a retained
            // later native observation may go. Owner input is never classified from shape.
            Map<String,Integer> newest=new java.util.HashMap<>();
            for(Map.Entry<Integer,String> row:observations.entrySet())newest.merge(row.getValue(),row.getKey(),Math::max);
            for(Map.Entry<Integer,String> row:observations.entrySet())
                if(row.getKey()<newest.get(row.getValue()))optional.put(row.getKey(),ContextWindow.Eviction.STALE_OBSERVATION);
            ContextWindow.Plan plan = ContextWindow.plan(messages, tools, selected.limits,
                    new ContextWindow.Retention(optional, Set.of()));
            JSONObject diagnostics = plan.diagnostics().put("boundary", boundary).put("metadata", selected.diagnostics())
                    .put("retentionPolicy", "host_proven_duplicate_observations_only")
                    .put("toolResultsAutomaticallyEvicted", false).put("summariesGenerated", false);
            if (!plan.canSend()) throw new Blocked(plan.status, boundary, diagnostics);
            return new Projection(plan, diagnostics);
        } catch (Blocked blocked) { throw blocked; }
        catch (ContextBudgetPolicy.Unavailable unavailable) { throw new Blocked(unavailable.code, boundary, new JSONObject()); }
        catch (Exception unavailable) { throw unavailable(boundary); }
    }

    /** Sort keys, retain every value/pixel/source version, and ignore only native reference ids.
     * Capture times, app/display/session/window identity and source versions deliberately remain.
     * This is duplicate detection, not a claim of semantic summarization or long-term memory.
     */
    private static void fingerprint(Object value,MessageDigest digest,boolean root,boolean node) throws Exception {
        if(value instanceof JSONObject) {
            JSONObject object=(JSONObject)value;java.util.List<String> keys=new java.util.ArrayList<>();
            java.util.Iterator<String> iterator=object.keys();while(iterator.hasNext())keys.add(iterator.next());
            Collections.sort(keys);update(digest,"{");
            for(String key:keys) {
                if((root&&key.equals("observationId"))||(node&&key.equals("id")))continue;
                update(digest,JSONObject.quote(key));update(digest,":");
                Object item=object.get(key);
                if(root&&key.equals("nodes")&&item instanceof JSONArray) {
                    JSONArray nodes=(JSONArray)item;update(digest,"[");
                    for(int i=0;i<nodes.length();i++){fingerprint(nodes.get(i),digest,false,true);update(digest,",");}
                    update(digest,"]");
                } else fingerprint(item,digest,false,false);
                update(digest,",");
            }
            update(digest,"}");
        } else if(value instanceof JSONArray) {
            JSONArray array=(JSONArray)value;update(digest,"[");
            for(int i=0;i<array.length();i++){fingerprint(array.get(i),digest,false,false);update(digest,",");}
            update(digest,"]");
        } else if(value==null||value==JSONObject.NULL)update(digest,"null");
        else if(value instanceof String)update(digest,JSONObject.quote((String)value));
        else if(value instanceof Number)update(digest,JSONObject.numberToString((Number)value));
        else if(value instanceof Boolean)update(digest,value.toString());
        else throw new IllegalArgumentException("Non-canonical native observation");
    }
    private static void update(MessageDigest digest,String text){digest.update(text.getBytes(StandardCharsets.UTF_8));}

    private static Blocked unavailable(String boundary) {
        return new Blocked("context_budget_unavailable", boundary, new JSONObject());
    }

    public static final class Blocked extends Exception {
        public final String status, boundary;
        public final JSONObject diagnostics;
        public Blocked(String status, String boundary, JSONObject diagnostics) {
            super("Context budget blocked: " + status);
            this.status = status; this.boundary = boundary; this.diagnostics = diagnostics;
            try { diagnostics.put("status", status).put("boundary", boundary).put("automaticReplay", false); }
            catch (Exception ignored) { }
        }
    }
}
