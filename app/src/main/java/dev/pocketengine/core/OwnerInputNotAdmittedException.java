package dev.pocketengine.core;

/** Native proof that this input was rejected before any queue row or execution was admitted.
 * Transport, persistence and generic host failures must never use this outcome. */
public final class OwnerInputNotAdmittedException extends IllegalStateException {
    public OwnerInputNotAdmittedException(String message) { super(message); }
}
