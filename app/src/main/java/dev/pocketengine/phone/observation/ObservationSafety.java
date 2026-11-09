package dev.pocketengine.phone.observation;

/** Shared, platform-independent failure semantics. Unknown effects always win over retry advice. */
public final class ObservationSafety {
    private ObservationSafety() { }
    public static boolean requiresPause(String code) {
        if (code == null) return false;
        switch (code) {
            case "permission_denied": case "service_disconnected": case "screen_locked":
            case "task_paused": case "paused_or_changed": case "screenshots_disabled":
            case "visual_engines_unavailable": case "screenshot_secure_window":
            case "display_unavailable": case "display_lost": case "wrong_display":
            case "geometry_unavailable":
            case "invalid_lease": case "focus_isolation_unavailable": case "focus_isolation_lost":
            case "background_unavailable": case "metadata_only_unavailable": case "main_screen_conflict": case "identity_mismatch":
            case "observation_metadata_missing": case "image_unavailable":
            case "reconciliation_required": case "display_ownership_unknown":
            case "tree_read_timeout": case "tree_read_busy": case "screen_unstable": return true;
            default: return false;
        }
    }
    public static boolean unknownEffect(String code) {
        return "action_unknown".equals(code) || "dispatch_unknown".equals(code)
                || "gesture_completion_unknown".equals(code) || "gesture_cancelled".equals(code);
    }
    /** Coordinate/helper input still requires the exact observed pixel generation. */
    public static String frameFailure(long expectedGeneration, int expectedWidth, int expectedHeight,
                                      long actualGeneration, int actualWidth, int actualHeight) {
        if (expectedGeneration < 0 || actualGeneration < 0 || expectedWidth <= 0 || expectedHeight <= 0
                || actualWidth <= 0 || actualHeight <= 0 || expectedGeneration != actualGeneration
                || expectedWidth != actualWidth || expectedHeight != actualHeight) return "stale_observation";
        return null;
    }
    /** Semantic input uses exact accessibility target context; only frame identity/geometry is shared with pixels. */
    public static String semanticFrameFailure(long expectedGeneration,int expectedWidth,int expectedHeight,
            long actualGeneration,int actualWidth,int actualHeight) {
        return expectedGeneration<0||actualGeneration<0||expectedWidth<=0||expectedHeight<=0
                ||expectedWidth!=actualWidth||expectedHeight!=actualHeight?"stale_observation":null;
    }
    public static String isolationFailure(int expectedDisplay, String expectedSession, int actualDisplay,
                                          String actualSession, boolean active, boolean backgroundOnly,
                                          boolean requested, boolean confirmed) {
        if (expectedDisplay <= 0 || actualDisplay != expectedDisplay || expectedSession == null
                || expectedSession.isEmpty() || !expectedSession.equals(actualSession)) return "wrong_display";
        if (!active) return "display_lost";
        if (!backgroundOnly || !requested || !confirmed) return "focus_isolation_lost";
        return null;
    }
}
