package dev.pocketengine.features.remoteinbox

import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage

/** SDK-validated callbacks only. Persist the hint quickly; Android JobService owns network work. */
class RemoteInboxPushService : PushService() {
    override fun onNewEndpoint(endpoint: PushEndpoint, instance: String) {
        if (endpoint.temporary) return UnifiedInboxPush.failed(this, instance, "temporary_receiver")
        val keys = endpoint.pubKeySet ?: return UnifiedInboxPush.failed(this, instance, "encryption_unavailable")
        UnifiedInboxPush.endpoint(this, instance, endpoint.url, keys.pubKey, keys.auth)
    }
    override fun onMessage(message: PushMessage, instance: String) = UnifiedInboxPush.message(this, instance, message.content, message.decrypted)
    override fun onRegistrationFailed(reason: FailedReason, instance: String) = UnifiedInboxPush.failed(this, instance, "registration_failed")
    override fun onUnregistered(instance: String) = UnifiedInboxPush.failed(this, instance, "unregistered")
    override fun onTempUnavailable(instance: String) = UnifiedInboxPush.failed(this, instance, "temporarily_unavailable")
}
