package dev.pocketengine.features.devices.transport;

import java.io.EOFException;
import java.io.IOException;
import java.net.URI;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/** Outbound WSS route lease. Outer TLS authenticates the relay; inner TLS authenticates devices.
 * Route tokens are short-lived relay slots, never model credentials or device permissions. */
public final class RelayByteChannel extends WebSocketListener implements DeviceByteChannel {
    public static final String SUBPROTOCOL = "pocketworld.byte-relay.v1";
    // Reconnects share a bounded standard dispatcher/pool; never allocate a new idle
    // dispatcher per socket or inherit a caller's hostname/trust-all configuration.
    private static final OkHttpClient PLATFORM_TLS_CLIENT = new OkHttpClient();
    private final ArrayBlockingQueue<byte[]> inbound = new ArrayBlockingQueue<>(4);
    private final CountDownLatch ready = new CountDownLatch(1);
    private final WebSocket socket;
    private volatile boolean paired, closed;
    public RelayByteChannel(String endpoint, String routeToken) throws Exception {
        // The public product path cannot inherit a caller's trust-all/hostname override.
        this(PLATFORM_TLS_CLIENT, endpoint, routeToken);
    }
    RelayByteChannel(OkHttpClient platformTlsClient, String endpoint, String routeToken) throws Exception {
        URI uri = new URI(endpoint);
        if (!"wss".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
            || uri.getPort() == 0 || uri.getPort() > 65535 || !uri.getPath().matches("/v1/tunnel/[A-Za-z0-9_-]{22,80}/[ab]/(?:control|bulk|pairing|pairing-complete)")) throw new IllegalArgumentException("Exact WSS relay route required");
        if (routeToken == null || !routeToken.matches("[A-Za-z0-9_-]{32,256}")) throw new IllegalArgumentException("Short-lived relay route token required");
        // redirect/retry disabled: an uncertain tunnel is closed, not recreated under pending writes.
        OkHttpClient client = platformTlsClient.newBuilder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS).pingInterval(20, TimeUnit.SECONDS).build();
        socket = client.newWebSocket(new Request.Builder().url(endpoint).header("Authorization", "Bearer " + routeToken).header("Sec-WebSocket-Protocol", SUBPROTOCOL).build(), this);
    }
    public void awaitReady(int timeoutMillis) throws IOException, InterruptedException {
        if (!ready.await(timeoutMillis, TimeUnit.MILLISECONDS) || !paired || closed) { close(); throw new IOException("Relay route unavailable (not device acceptance)"); }
    }
    @Override public void onOpen(WebSocket webSocket, Response response) {
        if (!SUBPROTOCOL.equals(response.header("Sec-WebSocket-Protocol"))) fail();
    }
    @Override public void onMessage(WebSocket webSocket, String text) {
        if (paired || !"ready".equals(text)) { fail(); return; } paired = true; ready.countDown();
    }
    @Override public void onMessage(WebSocket webSocket, ByteString binary) {
        if (!paired || closed || binary.size() == 0 || binary.size() > MAX_PACKET || !inbound.offer(binary.toByteArray())) fail();
    }
    @Override public byte[] receive(int timeoutMillis) throws IOException, InterruptedException {
        if (!paired && !closed) awaitReady(15_000);
        if (closed) throw new EOFException("Relay disconnected; original operations require reconciliation");
        byte[] value = inbound.poll(timeoutMillis, TimeUnit.MILLISECONDS);
        if (closed) throw new EOFException("Relay disconnected"); return value;
    }
    @Override public void send(byte[] encrypted) throws IOException, InterruptedException {
        if (!paired && !closed) awaitReady(15_000);
        if (!paired || closed || encrypted.length == 0 || encrypted.length > MAX_PACKET) throw new IOException("Relay not ready or record outside bound");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (socket.queueSize() > MAX_PACKET) {
            if (closed || System.nanoTime() >= deadline) { fail(); throw new IOException("Relay backpressure deadline; delivery unknown"); }
            Thread.sleep(5);
        }
        if (!socket.send(ByteString.of(encrypted))) { fail(); throw new IOException("Relay rejected record; no retry"); }
    }
    @Override public void flush() throws IOException, InterruptedException {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (socket.queueSize() != 0) {
            if (closed || System.nanoTime() >= until) throw new IOException("Relay flush not confirmed; no automatic replay");
            Thread.sleep(5);
        }
    }
    @Override public void onClosed(WebSocket webSocket, int code, String reason) { fail(); }
    @Override public void onFailure(WebSocket webSocket, Throwable failure, Response response) { fail(); }
    private void fail() { closed = true; ready.countDown(); if (socket != null) socket.cancel(); inbound.clear(); }
    @Override public void close() { fail(); }
}
