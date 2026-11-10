package dev.pocketengine.phone.observation;

import java.util.Objects;

/** Stateless selection only. It neither grants input authority nor declares a business outcome. */
public final class AdaptiveObservationPolicy {
    public enum Request { NODES, CAPTURE_IMAGE, VISION, OCR, EMPTY_DISPLAY, REOBSERVE, PAUSE, CANCEL, RECONCILE }
    public enum Need { AUTOMATIC, TARGET_MISSING, ACTION_REJECTED, EFFECT_CONFIRMED_ABSENT, EFFECT_UNKNOWN }
    public enum Health { READY, PERMISSION_LOST, LOCKED, DISPLAY_LOST, FOCUS_ISOLATION_LOST }

    /** Host identities, not values extracted from model tool arguments. */
    public static final class Binding {
        public final String taskId, runId, sessionId, observationId;
        public final int epoch, displayId;
        public final long pauseRevision;
        public Binding(String taskId, String runId, int epoch, String sessionId, int displayId,
                       String observationId, long pauseRevision) {
            this.taskId = required(taskId); this.runId = required(runId); this.sessionId = required(sessionId);
            this.observationId = required(observationId);
            if (epoch < 1 || displayId < 0 || pauseRevision < 0) throw new IllegalArgumentException("Invalid observation identity");
            this.epoch = epoch; this.displayId = displayId; this.pauseRevision = pauseRevision;
        }
        public boolean sameRunAndDisplay(Binding other) {
            return other != null && taskId.equals(other.taskId) && runId.equals(other.runId) && epoch == other.epoch
                    && sessionId.equals(other.sessionId) && displayId == other.displayId;
        }
        public boolean sameObservation(Binding other) {
            return sameRunAndDisplay(other) && observationId.equals(other.observationId) && pauseRevision == other.pauseRevision;
        }
    }

    public static final class Evidence {
        public final Binding binding;
        public final boolean treeAvailable, treeTruncated, actualImage, emptyDisplay, treeAdequate;
        public final int usefulNodes;
        public final long observedAtUptimeMs;
        public Evidence(Binding binding, boolean treeAvailable, boolean treeTruncated, int usefulNodes,
                        boolean actualImage, boolean emptyDisplay, long observedAtUptimeMs) {
            this(binding,treeAvailable,treeTruncated,usefulNodes,actualImage,emptyDisplay,observedAtUptimeMs,treeAvailable&&!treeTruncated&&usefulNodes>0);
        }
        public Evidence(Binding binding,boolean treeAvailable,boolean treeTruncated,int usefulNodes,
                        boolean actualImage,boolean emptyDisplay,long observedAtUptimeMs,boolean treeAdequate){
            this.binding = Objects.requireNonNull(binding);
            if (usefulNodes < 0 || observedAtUptimeMs < 0 || (!treeAvailable && usefulNodes != 0))
                throw new IllegalArgumentException("Invalid observation evidence");
            this.treeAvailable = treeAvailable; this.treeTruncated = treeTruncated; this.usefulNodes = usefulNodes;
            this.actualImage = actualImage; this.emptyDisplay = emptyDisplay; this.observedAtUptimeMs = observedAtUptimeMs;this.treeAdequate=treeAdequate;
        }
    }

    public static final class Decision {
        public final Request request;
        public final String reason;
        public final Binding binding;
        private Decision(Request request, String reason, Binding binding) {
            this.request = request; this.reason = reason; this.binding = binding;
        }
        public boolean requestsImage() { return request == Request.CAPTURE_IMAGE; }
        public boolean permitsNodeSelection() { return request == Request.NODES; }
    }

    public Decision decide(Binding expected, Evidence observed, Need need, Health health,
                           boolean sendScreenshots, boolean visionAvailable, boolean ocrAvailable,
                           boolean paused, boolean cancelled, long nowUptimeMs, long maxAgeMs) {
        return decide(expected,observed,need,health,sendScreenshots,true,sendScreenshots,visionAvailable,ocrAvailable,paused,cancelled,nowUptimeMs,maxAgeMs);
    }
    public Decision decide(Binding expected,Evidence observed,Need need,Health health,
                           boolean localCaptureAllowed,boolean localOcrAllowed,boolean uploadImageAllowed,boolean visionAvailable,boolean ocrAvailable,
                           boolean paused,boolean cancelled,long nowUptimeMs,long maxAgeMs) {
        Objects.requireNonNull(expected); Objects.requireNonNull(observed);
        Objects.requireNonNull(need); Objects.requireNonNull(health);
        if (maxAgeMs <= 0 || nowUptimeMs < 0) throw new IllegalArgumentException("Invalid observation clock");
        if (cancelled) return decision(Request.CANCEL, "run_cancelled", expected);
        if (!expected.sameRunAndDisplay(observed.binding)) return decision(Request.PAUSE, "identity_mismatch", expected);
        if (health != Health.READY) return decision(Request.PAUSE, health.name().toLowerCase(java.util.Locale.ROOT), expected);
        if (paused) return decision(Request.PAUSE, "task_paused", expected);
        if (need == Need.EFFECT_UNKNOWN) return decision(Request.RECONCILE, "effect_unknown_do_not_resend", expected);
        if (!expected.sameObservation(observed.binding)) return decision(Request.REOBSERVE, "observation_changed", expected);
        if (nowUptimeMs < observed.observedAtUptimeMs || nowUptimeMs - observed.observedAtUptimeMs > maxAgeMs)
            return decision(Request.REOBSERVE, "observation_expired", expected);
        if (observed.emptyDisplay && observed.usefulNodes == 0 && !observed.actualImage)
            return decision(Request.EMPTY_DISPLAY, "display_has_no_frame", expected);
        if (need == Need.AUTOMATIC && observed.treeAvailable && observed.usefulNodes > 0 && (observed.treeAdequate||observed.treeTruncated))
            return decision(Request.NODES, observed.treeTruncated?"partial_tree_visible_targets":"tree_sufficient", expected);
        String cause = need != Need.AUTOMATIC ? need.name().toLowerCase(java.util.Locale.ROOT)
                : !observed.treeAvailable ? "tree_unavailable" : observed.treeTruncated ? "tree_truncated" : observed.usefulNodes==0?"no_useful_nodes":"tree_partial";
        // Even a retained image cannot override the owner's disabled screenshots setting.
        if (!localCaptureAllowed) return decision(Request.PAUSE, "local_capture_disabled:" + cause, expected);
        ocrAvailable=localOcrAllowed&&ocrAvailable;visionAvailable=uploadImageAllowed&&visionAvailable;
        if (!visionAvailable && !ocrAvailable) return decision(Request.PAUSE, "visual_engines_unavailable:" + cause, expected);
        if (!observed.actualImage) return decision(Request.CAPTURE_IMAGE, cause, expected);
        // OCR is an explicit, actually-ready capability supplied by the native host. No engine is bundled here.
        return decision(ocrAvailable ? Request.OCR : Request.VISION, cause, expected);
    }

    private static Decision decision(Request request, String reason, Binding binding) { return new Decision(request, reason, binding); }
    static Decision retainTreeAfterImageFailure(Decision checked) {
        if (!checked.requestsImage()) throw new IllegalArgumentException("Observation must pass the capture admission checks");
        return decision(Request.NODES, "image_unavailable_tree_retained", checked.binding);
    }
    static Decision blocked(String reason) { return new Decision(Request.PAUSE, reason, null); }
    static String required(String value) {
        if (value == null || value.isEmpty() || value.length() > 250) throw new IllegalArgumentException("Missing observation identity");
        return value;
    }
}
