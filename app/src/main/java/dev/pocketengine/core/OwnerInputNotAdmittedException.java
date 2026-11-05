package dev.pocketengine.core;

/** Native proof that this input was rejected before any queue row or execution was admitted.
 * Transport, persistence and generic host failures must never use this outcome. */
public final class OwnerInputNotAdmittedException extends IllegalStateException {
    public enum Reason { MAILBOX_CLOSED, CAPACITY_REACHED }
    private final Reason reason;
    public OwnerInputNotAdmittedException(String message) { this(Reason.MAILBOX_CLOSED,message); }
    public OwnerInputNotAdmittedException(Reason reason,String message) { super(message);this.reason=java.util.Objects.requireNonNull(reason); }
    public String reason() { return reason==Reason.MAILBOX_CLOSED?"mailbox_closed":"capacity_reached"; }
}
