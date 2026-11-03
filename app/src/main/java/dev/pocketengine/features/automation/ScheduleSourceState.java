package dev.pocketengine.features.automation;

/** Native provenance outcomes, not grants. Only an authoritative negative fact may erase
 * an existing schedule. Host absence, unsupported legacy proof and I/O remain retryable. */
public final class ScheduleSourceState {
    private ScheduleSourceState() {}
    public enum Reason { FORGOTTEN, DELETED, RETIRED, REVISION_CHANGED }
    public static final class Invalidated extends SecurityException {
        public final Reason reason;
        public Invalidated(Reason proven){super("原计划来源已有撤回事实："+java.util.Objects.requireNonNull(proven).name());reason=proven;}
    }
    public static final class Unavailable extends SecurityException {
        public Unavailable(){super("原计划来源暂时无法核对；仅暂停，不删除原提醒");}
    }
}
