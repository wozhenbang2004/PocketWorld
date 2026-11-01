package dev.pocketengine.media;

import java.util.concurrent.CompletableFuture;

/**
 * Installed by trusted native host code, never by model/channel JSON. No default network issuer exists.
 * The implementation must authenticate the owner, bind the invitation/room and issue short-lived,
 * least-privilege participant credentials. LiveKit server signing secrets never belong on the phone.
 */
public interface RealtimeCallProvider {
    String id();
    default boolean supportsIncomingInvitations(){return true;}
    /** Cancels local admission work only; it is not proof of a remote agent/server-side rollback. */
    default void cancelAccept(String invitationId){}
    default void close(){}
    interface SecretReader {String read()throws Exception;}
    interface EndpointFactory {RealtimeCallProvider create(RealtimeEndpointProfile profile,SecretReader secret);}

    CompletableFuture<JoinCredentials> accept(String invitationId,boolean microphone,boolean camera);
    CompletableFuture<Void> reject(String invitationId);
    final class ProviderFailure extends Exception {
        public final String code;
        public ProviderFailure(String code){super("通话服务未确认（"+code+"）；没有自动重试或启动模拟对端");this.code=code;}
    }
    final class JoinCredentials {
        public final String serverUrl,participantToken;
        public final long expiresAt;
        public JoinCredentials(String serverUrl,String participantToken,long expiresAt){this.serverUrl=serverUrl;this.participantToken=participantToken;this.expiresAt=expiresAt;}
        @Override public String toString(){return "JoinCredentials[redacted]";}
    }
}
