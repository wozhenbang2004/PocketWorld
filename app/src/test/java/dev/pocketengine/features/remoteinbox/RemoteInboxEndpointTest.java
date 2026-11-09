package dev.pocketengine.features.remoteinbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class RemoteInboxEndpointTest {
    @Test void canonicalizesHttpsRootOnly() {
        assertEquals("https://alerts.example.com", RemoteInboxStore.endpoint("https://alerts.example.com/"));
        assertEquals("https://alerts.example.com", RemoteInboxStore.endpoint(" HTTPS://alerts.example.com "));
    }
    @Test void rejectsCredentialsAndQueryTokens() {
        assertThrows(IllegalArgumentException.class, () -> RemoteInboxStore.endpoint("https://user:password@alerts.example.com"));
        assertThrows(IllegalArgumentException.class, () -> RemoteInboxStore.endpoint("https://alerts.example.com?token=secret"));
        assertThrows(IllegalArgumentException.class, () -> RemoteInboxStore.endpoint("https://alerts.example.com/v1/events"));
    }
    @Test void rejectsCleartextAndLocalEndpoints() {
        assertThrows(IllegalArgumentException.class, () -> RemoteInboxStore.endpoint("http://alerts.example.com"));
        assertThrows(IllegalArgumentException.class, () -> RemoteInboxStore.endpoint("https://localhost"));
        assertThrows(IllegalArgumentException.class, () -> RemoteInboxStore.endpoint("https://127.0.0.1"));
        assertThrows(IllegalArgumentException.class, () -> RemoteInboxStore.endpoint("https://192.168.1.5"));
        assertThrows(IllegalArgumentException.class, () -> RemoteInboxStore.endpoint("https://alerts.example.com:8443"));
    }
}
