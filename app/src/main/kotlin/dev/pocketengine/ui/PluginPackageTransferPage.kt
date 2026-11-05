package dev.pocketengine.ui

internal object PluginPackageTransferGuard {
    @JvmStatic fun canImport(displayed: String?, current: String?, busy: Boolean, confirmed: Boolean, submitted: Boolean): Boolean =
        displayed != null && displayed.matches(Regex("[a-f0-9]{64}")) && displayed == current && !busy && confirmed && !submitted
}

/** Native owner file-transfer leaf. The host owns SAF streams, exact requests and current-module state. */
