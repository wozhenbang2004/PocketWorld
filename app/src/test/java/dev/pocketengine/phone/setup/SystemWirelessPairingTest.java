package dev.pocketengine.phone.setup;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

/** Pure visible-window parsing. No real Android settings or ADB authorization is simulated. */
public final class SystemWirelessPairingTest {
    @Test public void acceptsUnambiguousChineseAndEnglishSystemDialogLabels() {
        var chinese=SystemWirelessPairing.parseVisibleText(List.of(
                "使用配对码配对设备", "Wi-Fi 配对码", "482911", "IP 地址和端口", "192.168.1.23:42715"));
        assertNotNull(chinese);
        assertEquals("192.168.1.23",chinese.host());
        assertEquals(42715,chinese.port());
        assertEquals("482911",chinese.code());
        var english=SystemWirelessPairing.parseVisibleText(List.of(
                "Pair device with pairing code", "Wi-Fi pairing code: 654321",
                "IP address & Port", "10.0.0.8:35145"));
        assertNotNull(english);
        assertEquals(35145,english.port());
        assertEquals("654321",english.code());
    }

    @Test public void refusesMissingMarkersAmbiguousValuesAndUnrelatedDigits() {
        assertNull(SystemWirelessPairing.parseVisibleText(List.of("Sign in", "Wi-Fi", "482911", "192.168.1.23:42715")));
        assertNull(SystemWirelessPairing.parseVisibleText(List.of("Wi-Fi pairing code", "482911", "192.168.1.23:42715", "192.168.1.23:40123")));
        assertNull(SystemWirelessPairing.parseVisibleText(List.of("Wi-Fi pairing code", "482911", "123456", "192.168.1.23:42715")));
        assertNull(SystemWirelessPairing.parseVisibleText(List.of("Wi-Fi pairing code", "192.168.1.23:42715")));
        assertNull(SystemWirelessPairing.parseVisibleText(List.of("Wi-Fi pairing code", "482911", "192.168.1.23:70000")));
        assertNull(SystemWirelessPairing.parseVisibleText(List.of("Wi-Fi pairing code", "482911", "https://example.com:40123")));
    }

    @Test public void refusesOversizedOrPartialWindowObservations() {
        List<String> lines=new ArrayList<>();
        for(int i=0;i<97;i++) lines.add("Wi-Fi pairing code "+i);
        assertNull(SystemWirelessPairing.parseVisibleText(lines));
        assertNull(SystemWirelessPairing.parseVisibleText(List.of("Wi-Fi pairing code", "482911", "1".repeat(241), "192.168.1.23:42715")));
    }
}
