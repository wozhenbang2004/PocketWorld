package dev.pocketengine.runtime;

/**
 * Bounded visible text for one physical attempt, retained only as provisional audit output.
 * Does not accept an answer, append model history, replay a tool, or refund usage.
 */
final class InterruptedModelAttempt {
    private static final int MAX_PREFIX_CHARS = 32768;

    @FunctionalInterface
    interface Retention {
        void retain(String callId, String prefix) throws Exception;
    }

    private String callId = "";
    private final StringBuilder prefix = new StringBuilder();
    private boolean retained;

    /** Preserve the prior attempt before advancing; a failed retention does not lose its state. */
    void started(String nextCallId, Retention retention) throws Exception {
        if (callId.equals(nextCallId)) return;
        retain(retention);
        prefix.setLength(0);
        retained = false;
        callId = nextCallId;
    }

    /** Only the active physical call contributes; keep the existing surrogate-safe cutoff rule. */
    void text(String eventCallId, String text) {
        if (!eventCallId.equals(callId) || prefix.length() >= MAX_PREFIX_CHARS) return;
        int count = Math.min(text.length(), MAX_PREFIX_CHARS - prefix.length());
        if (count > 0 && count < text.length() && Character.isHighSurrogate(text.charAt(count - 1))) count--;
        prefix.append(text, 0, count);
    }

    void interrupted(String eventCallId, Retention retention) throws Exception {
        if (eventCallId.equals(callId)) retain(retention);
    }

    /** Mark only after successful delivery so an exception cannot silently discard the prefix. */
    void retain(Retention retention) throws Exception {
        if (retained) return;
        retention.retain(callId, prefix.toString());
        retained = true;
    }

    boolean hasVisibleText() { return prefix.length() != 0; }
}
