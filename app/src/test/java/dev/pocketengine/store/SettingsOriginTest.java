package dev.pocketengine.store;

import org.junit.Test;
import static org.junit.Assert.*;

public final class SettingsOriginTest {
    @Test public void credentialsStayWithinTheSameTransportAndAuthority() {
        assertTrue(SettingsStore.sameOrigin("https://model.example/v1","https://MODEL.example:443/other"));
        assertTrue(SettingsStore.sameOrigin("http://127.0.0.1:8080/v1","http://127.0.0.1:8080/custom"));
        assertFalse(SettingsStore.sameOrigin("https://old.example/v1","https://new.example/v1"));
        assertFalse(SettingsStore.sameOrigin("https://model.example/v1","http://model.example/v1"));
        assertFalse(SettingsStore.sameOrigin("http://127.0.0.1:8080","http://127.0.0.1:8081"));
        assertFalse(SettingsStore.sameOrigin("","https://model.example/v1"));
    }
}
