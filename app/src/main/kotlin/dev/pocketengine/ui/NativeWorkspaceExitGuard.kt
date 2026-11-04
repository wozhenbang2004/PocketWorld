package dev.pocketengine.ui

internal fun nativeWorkspaceExitAllowed(hasOpenLayer: Boolean, hasPendingWork: Boolean): Boolean =
    !hasOpenLayer && !hasPendingWork

internal fun nativeBusyBack(busy: Boolean, back: () -> Unit) {
    if (!busy) back()
}

internal fun nativeVoiceBusy(active: Boolean, lease: Boolean, realtime: Boolean, review: Boolean,
                             staged: Boolean, unknownRelease: Boolean, retained: Boolean): Boolean =
    active || lease || realtime || review || staged || unknownRelease || retained
