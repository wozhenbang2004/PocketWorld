package dev.pocketengine.features.devices;

import android.content.SharedPreferences;
import dev.pocketengine.features.devices.transport.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.net.ssl.SSLContext;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Constructor-free native routing/resource fixture only. No App/store, TLS authentication,
 * socket, task or permission is created. Uninitialized raw objects mark path TYPES only;
 * real TLS/WSS and original SQL gates are covered in the separate journey tests. */
public final class DeviceWorkspaceRouteOwnershipTest {
    private static final String PEER = "pw1_" + "b".repeat(64), LOCAL = "pw1_" + "a".repeat(64);
    private static final String TOKEN = "receiver-generation-current", SERVICE = "wss://relay.example.invalid";
    // AGP's Android --system image intentionally omits this JVM-only API. Resolve it only
    // at host-test runtime, never link it into the Android test compilation surface.
    private static final Object ALLOCATOR;
    private static final Method ALLOCATE_INSTANCE;
    static {
        try {
            Class<?> type = Class.forName("sun.misc.Unsafe"); Field field = type.getDeclaredField("theUnsafe"); field.setAccessible(true);
            ALLOCATOR = field.get(null); ALLOCATE_INSTANCE = type.getMethod("allocateInstance", Class.class);
        }
        catch (ReflectiveOperationException failure) { throw new ExceptionInInitializerError(failure); }
    }
    private static Object empty(Class<?> type) throws Exception { return ALLOCATE_INSTANCE.invoke(ALLOCATOR, type); }
    private static Field field(Class<?> type, String name) throws Exception { Field field = type.getDeclaredField(name); field.setAccessible(true); return field; }
    @SuppressWarnings("unchecked") private static <T> Map<String, T> map(DeviceWorkspaceLanRuntime runtime, String name) throws Exception { return (Map<String, T>) field(DeviceWorkspaceLanRuntime.class, name).get(runtime); }
    @SuppressWarnings("unchecked") private static Map<ReconnectingDeviceClient, Object> owners(DeviceWorkspaceLanRuntime runtime) throws Exception { return (Map<ReconnectingDeviceClient, Object>) field(DeviceWorkspaceLanRuntime.class, "clientOwners").get(runtime); }
    private static Object call(DeviceWorkspaceLanRuntime runtime, String method, Class<?>[] types, Object... args) throws Exception {
        Method target = DeviceWorkspaceLanRuntime.class.getDeclaredMethod(method, types); target.setAccessible(true);
        try { return target.invoke(runtime, args); }
        catch (InvocationTargetException failure) { if (failure.getCause() instanceof Exception cause) throw cause; throw failure; }
    }
    private static DeviceWorkspaceLanRuntime runtime(JSONObject hint) throws Exception {
        var runtime = (DeviceWorkspaceLanRuntime) empty(DeviceWorkspaceLanRuntime.class);
        for (Field field : DeviceWorkspaceLanRuntime.class.getDeclaredFields()) if (Map.class.isAssignableFrom(field.getType())) { field.setAccessible(true); field.set(runtime, new ConcurrentHashMap<>()); }
        Map<String, String> values = new ConcurrentHashMap<>();
        if (hint != null) values.put("endpoint_" + DeviceProtocol.sha256(PEER.getBytes(StandardCharsets.UTF_8)), new JSONObject().put("connectionHint", hint).toString());
        SharedPreferences preferences = (SharedPreferences) Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(), new Class<?>[]{SharedPreferences.class}, (proxy, method, args) -> switch (method.getName()) {
            case "getString" -> values.getOrDefault(args[0], (String) args[1]);
            case "contains" -> values.containsKey(args[0]);
            case "getAll" -> Map.copyOf(values);
            default -> throw new AssertionError("No persistence/authority operation in route fixture: " + method.getName());
        });
        field(DeviceWorkspaceLanRuntime.class, "preferences").set(runtime, preferences);
        field(DeviceWorkspaceLanRuntime.class, "networkOwnerToken").set(runtime, TOKEN);
        return runtime;
    }
    private static JSONObject relayHint(String service) throws Exception { return new JSONObject().put("kind", "relay").put("serviceBase", service).put("inviterDeviceId", PEER); }
    private static DeviceRelayLease lease(String service) throws Exception {
        DeviceRelayLease result = DeviceRelayLease.review(new JSONObject().put("format", DeviceRelayLease.FORMAT).put("serviceBase", service).put("inviterDeviceId", PEER)
                .put("routeId", "synthetic_route_identifier_0001").put("side", "a").put("routeToken", "synthetic_native_memory_fixture_00000001").put("expiresAt", System.currentTimeMillis() + 60_000).toString(), LOCAL, "synthetic-native-instance", System.currentTimeMillis());
        result.bindPeer(PEER); return result;
    }
    private static Object ownership(DeviceRelayLease lease, String token) throws Exception {
        Class<?> type = Class.forName(DeviceWorkspaceLanRuntime.class.getName() + "$ClientOwnership");
        Constructor<?> ctor = type.getDeclaredConstructor(String.class, DeviceRelayLease.class, String.class); ctor.setAccessible(true); return ctor.newInstance(PEER, lease, token);
    }
    private static final DeviceConnection.Listener UNUSED = new DeviceConnection.Listener() {
        public void onOnline(DeviceIdentity.AuthenticatedPeer peer) { fail("No authentication/network in fixture"); }
        public void onControl(DeviceIdentity.AuthenticatedPeer peer, byte[] bytes) { fail("No business dispatch"); }
        public void onChunk(DeviceIdentity.AuthenticatedPeer peer, java.util.UUID id, long offset, byte[] bytes) { fail("No artifact transfer"); }
        public void onClosed(DeviceConnection.State state, boolean unknown) { }
    };
    private static Object channel(boolean relay, Object ownership) throws Exception {
        DeviceByteChannel raw = (DeviceByteChannel) empty(relay ? RelayByteChannel.class : SocketByteChannel.class);
        var connection = new DeviceConnection(SSLContext.getDefault(), true, DeviceConnection.Lane.CONTROL, raw, UNUSED);
        field(DeviceConnection.class, "state").set(connection, DeviceConnection.State.ONLINE);
        return channel(connection, ownership);
    }
    private static Object channel(DeviceConnection connection, Object ownership) throws Exception {
        Class<?> type = Class.forName(DeviceWorkspaceLanRuntime.class.getName() + "$PeerChannel");
        Constructor<?> ctor = type.getDeclaredConstructor(DeviceConnection.class, DeviceIdentity.AuthenticatedPeer.class, ownership(null, null).getClass()); ctor.setAccessible(true);
        return ctor.newInstance(connection, null, ownership);
    }
    private static Object selected(DeviceWorkspaceLanRuntime runtime, String lane) throws Exception { return call(runtime, lane.equals("control") ? "controlChannel" : "bulkChannel", new Class<?>[]{String.class}, PEER); }
    private static void installLease(DeviceWorkspaceLanRuntime runtime, DeviceRelayLease lease) throws Exception { map(runtime, "relayLeases").put(lease.serviceBase + "\n" + lease.inviterDeviceId, lease); }
    private static void retain(DeviceWorkspaceLanRuntime runtime, Object ownership) throws Exception { owners(runtime).put(new ReconnectingDeviceClient(true, System.currentTimeMillis() + 60_000, () -> { throw new AssertionError("No socket/thread"); }), ownership); }
    private static final class CountingChannel implements DeviceByteChannel {
        int closes;
        public byte[] receive(int timeout) { throw new AssertionError("No transport read"); }
        public void send(byte[] bytes) { throw new AssertionError("No write/replay"); }
        public void close() { closes++; }
    }
    private record Client(ReconnectingDeviceClient client, CountingChannel raw) { }
    private static Client client(DeviceWorkspaceLanRuntime runtime, String lane, DeviceRelayLease lease, String token, boolean current) throws Exception {
        CountingChannel raw = new CountingChannel();
        DeviceConnection connection = new DeviceConnection(SSLContext.getDefault(), true, lane.equals("control") ? DeviceConnection.Lane.CONTROL : DeviceConnection.Lane.BULK, raw, UNUSED);
        var client = new ReconnectingDeviceClient(true, System.currentTimeMillis() + 60_000, () -> { throw new AssertionError("No connection/replay"); });
        field(ReconnectingDeviceClient.class, "active").set(client, connection);
        owners(runtime).put(client, ownership(lease, token));
        if (current) map(runtime, lane.equals("control") ? "clients" : "bulkClients").put(PEER, client);
        return new Client(client, raw);
    }
    private static void stopReceiver(DeviceWorkspaceLanRuntime runtime, String token) throws Exception {
        try { call(runtime, "closeReceiverNetwork", new Class<?>[]{String.class}, token); }
        catch (NullPointerException publishWithoutApp) {
            // This isolated object deliberately has no App/owner to publish the final view.
            // Only the exact final owner.changed() dereference is omitted; cleanup exceptions fail.
            assertTrue(publishWithoutApp.getMessage(), publishWithoutApp.getMessage().contains("this.owner"));
            assertEquals("closeReceiverNetwork", publishWithoutApp.getStackTrace()[0].getMethodName());
        }
    }
    @Test public void explicitRelayNeverSilentlyUsesOrClosesTheOldIncomingLanOnEitherLane() throws Exception {
        var runtime = runtime(relayHint(SERVICE)); Object incoming = channel(false, null);
        map(runtime, "channels").put(PEER, incoming); map(runtime, "bulkChannels").put(PEER, incoming);
        assertNull(selected(runtime, "control")); assertNull(selected(runtime, "bulk"));
        assertEquals(DeviceConnection.State.OFFLINE, runtime.current(PEER).state()); assertEquals(DeviceTransportStatus.Path.UNKNOWN, runtime.current(PEER).path());
        assertSame(incoming, map(runtime, "channels").get(PEER)); assertSame(incoming, map(runtime, "bulkChannels").get(PEER));
    }
    @Test public void matchingActualRelayIsSelectedAndReplacementOrCloseCannotFallBackToLan() throws Exception {
        var runtime = runtime(relayHint(SERVICE));
        try (var lease = lease(SERVICE); var replacement = lease(SERVICE)) {
            installLease(runtime, lease); Object owned = ownership(lease, null); retain(runtime, owned);
            Object relay = channel(true, owned), incoming = channel(false, null);
            for (String map : new String[]{"channels", "bulkChannels"}) map(runtime, map).put(PEER, incoming);
            for (String map : new String[]{"outboundChannels", "outboundBulkChannels"}) map(runtime, map).put(PEER, relay);
            assertSame(relay, selected(runtime, "control")); assertSame(relay, selected(runtime, "bulk"));
            installLease(runtime, replacement); assertNull(selected(runtime, "control")); assertNull(selected(runtime, "bulk"));
            installLease(runtime, lease); lease.close(); assertNull(selected(runtime, "control")); assertNull(selected(runtime, "bulk"));
        }
    }
    @Test public void differentRelayServiceIsNotSelectedAndOriginalLanIncomingBehaviorRemains() throws Exception {
        var runtime = runtime(relayHint(SERVICE));
        try (var other = lease("wss://other.example.invalid")) {
            installLease(runtime, other); Object owned = ownership(other, null); retain(runtime, owned); map(runtime, "outboundChannels").put(PEER, channel(true, owned));
            assertNull(selected(runtime, "control"));
        }
        for (JSONObject hint : new JSONObject[]{null, new JSONObject().put("kind", "lan")}) {
            runtime = runtime(hint); Object incoming = channel(false, null); map(runtime, "channels").put(PEER, incoming); map(runtime, "bulkChannels").put(PEER, incoming);
            assertSame(incoming, selected(runtime, "control")); assertSame(incoming, selected(runtime, "bulk"));
        }
    }
    @Test public void receiverOptOutClosesOnlyExactOriginalClientsNotTheirSamePeerSourceReplacements() throws Exception {
        var runtime = runtime(null);
        Client oldControl = client(runtime, "control", null, TOKEN, false), oldBulk = client(runtime, "bulk", null, TOKEN, false);
        Client sourceControl = client(runtime, "control", null, null, true), sourceBulk = client(runtime, "bulk", null, null, true);
        stopReceiver(runtime, TOKEN);
        assertEquals(1, oldControl.raw.closes); assertEquals(1, oldBulk.raw.closes); assertEquals(0, sourceControl.raw.closes); assertEquals(0, sourceBulk.raw.closes);
        assertSame(sourceControl.client, map(runtime, "clients").get(PEER)); assertSame(sourceBulk.client, map(runtime, "bulkClients").get(PEER));
    }
    @Test public void staleReceiverGenerationCannotCloseTheCurrentGenerationOrSource() throws Exception {
        var runtime = runtime(null); Client current = client(runtime, "control", null, TOKEN, true), source = client(runtime, "bulk", null, null, true);
        stopReceiver(runtime, "receiver-generation-old"); assertEquals(0, current.raw.closes); assertEquals(0, source.raw.closes);
        stopReceiver(runtime, TOKEN); assertEquals(1, current.raw.closes); assertEquals(0, source.raw.closes);
    }
    @Test public void removingAnOldLeaseStopsOnlyItsExactClientsNotSamePeerLanOrNewLease() throws Exception {
        var runtime = runtime(null);
        try (var old = lease(SERVICE); var replacement = lease("wss://other.example.invalid")) {
            Client oldControl = client(runtime, "control", old, null, false), oldBulk = client(runtime, "bulk", old, null, false);
            Client newControl = client(runtime, "control", replacement, null, true), newBulk = client(runtime, "bulk", null, null, true);
            call(runtime, "stopClientsForLease", new Class<?>[]{DeviceRelayLease.class}, old);
            assertEquals(1, oldControl.raw.closes); assertEquals(1, oldBulk.raw.closes); assertEquals(0, newControl.raw.closes); assertEquals(0, newBulk.raw.closes);
            assertSame(newControl.client, map(runtime, "clients").get(PEER)); assertSame(newBulk.client, map(runtime, "bulkClients").get(PEER));
        }
    }
    @Test public void lateReplacedClientCannotSelectOrOpenTheCurrentRelayLane() throws Exception {
        var runtime = runtime(relayHint(SERVICE));
        try (var lease = lease(SERVICE)) {
            installLease(runtime, lease); Object stale = ownership(lease, TOKEN); retain(runtime, stale);
            map(runtime, "outboundChannels").put(PEER, channel(true, stale)); owners(runtime).clear();
            assertNull(selected(runtime, "control"));
            assertThrows(SecurityException.class, () -> call(runtime, "openOwnedRelay", new Class<?>[]{DeviceRelayLease.class, stale.getClass(), String.class}, lease, stale, "control"));
        }
    }
    @Test public void replacingOutboundRouteDoesNotCloseEitherOldIncomingLane() throws Exception {
        var runtime = runtime(null); Client sourceControl = client(runtime, "control", null, null, true), sourceBulk = client(runtime, "bulk", null, null, true);
        CountingChannel incomingControl = new CountingChannel(), incomingBulk = new CountingChannel();
        Object control = channel(new DeviceConnection(SSLContext.getDefault(), false, DeviceConnection.Lane.CONTROL, incomingControl, UNUSED), null);
        Object bulk = channel(new DeviceConnection(SSLContext.getDefault(), false, DeviceConnection.Lane.BULK, incomingBulk, UNUSED), null);
        map(runtime, "channels").put(PEER, control); map(runtime, "bulkChannels").put(PEER, bulk);
        call(runtime, "stopOutboundPeer", new Class<?>[]{String.class}, PEER);
        assertEquals(1, sourceControl.raw.closes); assertEquals(1, sourceBulk.raw.closes); assertEquals(0, incomingControl.closes); assertEquals(0, incomingBulk.closes);
        assertSame(control, map(runtime, "channels").get(PEER)); assertSame(bulk, map(runtime, "bulkChannels").get(PEER));
    }
}
