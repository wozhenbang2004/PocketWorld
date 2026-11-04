package dev.pocketengine.runtime.providers.bedrock;

import static org.junit.Assert.*;

import dev.pocketengine.core.AwsSessionCredentials;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;

public final class BedrockSigV4Test {
    private static final long VECTOR_TIME = Instant.parse("2015-08-30T12:36:00Z").toEpochMilli();
    private static final long NOW = Instant.parse("2026-11-04T10:00:00Z").toEpochMilli();
    // Public AWS documentation fixtures, never real account credentials.
    private static final String EXAMPLE_SECRET = "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY";
    private static final String MODEL = "anthropic.claude-sonnet-4-6";

    @Test public void officialGetVanillaVector() throws Exception { assertAwsVector("get-vanilla"); }
    @Test public void officialUtf8Vector() throws Exception { assertAwsVector("get-utf8"); }
    @Test public void officialSessionTokenVector() throws Exception { assertAwsVector("get-vanilla-with-session-token"); }
    @Test public void officialNonemptyPostBodyVector() throws Exception { assertAwsVector("post-x-www-form-urlencoded"); }

    @Test public void productionHeadersBindExactFinalBytesAndSelectedSession() throws Exception {
        byte[] bytes = "{\"text\":\"中文🐢\",\"n\":1}".getBytes(StandardCharsets.UTF_8);
        byte[] before = bytes.clone();
        URL url = BedrockEndpoint.endpoint("eu-west-2", MODEL, true);
        AwsSessionCredentials session = session("TESTSESSIONTOKEN0001", NOW + 60_000);
        Map<String, String> headers = BedrockSigV4.headers("eu-west-2", MODEL, session, url, bytes, NOW);
        assertArrayEquals(before, bytes);
        assertEquals("application/json", headers.get("Content-Type"));
        assertEquals("application/vnd.amazon.eventstream", headers.get("Accept"));
        assertEquals("application/json", headers.get("X-Amzn-Bedrock-Accept"));
        assertEquals("bedrock-runtime.eu-west-2.amazonaws.com", headers.get("Host"));
        assertEquals("TESTSESSIONTOKEN0001", headers.get("X-Amz-Security-Token"));
        assertEquals(hex(MessageDigest.getInstance("SHA-256").digest(bytes)), headers.get("X-Amz-Content-Sha256"));
        assertTrue(headers.get("Authorization").contains("/eu-west-2/bedrock/aws4_request"));
        assertTrue(headers.get("Authorization").contains(
            "SignedHeaders=accept;content-type;host;x-amz-content-sha256;x-amz-date;x-amz-security-token;x-amzn-bedrock-accept"));
        assertFalse(headers.get("Authorization").contains(EXAMPLE_SECRET));
        assertThrows(UnsupportedOperationException.class, () -> headers.put("Host", "other.invalid"));
    }

    @Test public void changedBodyOrTokenChangesSignatureWithoutMutatingInput() throws Exception {
        URL url = BedrockEndpoint.endpoint("eu-west-2", MODEL, false);
        byte[] first = "{\"value\":1}".getBytes(StandardCharsets.UTF_8);
        byte[] second = "{\"value\":2}".getBytes(StandardCharsets.UTF_8);
        Map<String, String> a = BedrockSigV4.headers("eu-west-2", MODEL, session("TESTSESSIONTOKEN0001", NOW + 60_000), url, first, NOW);
        Map<String, String> b = BedrockSigV4.headers("eu-west-2", MODEL, session("TESTSESSIONTOKEN0001", NOW + 60_000), url, second, NOW);
        Map<String, String> c = BedrockSigV4.headers("eu-west-2", MODEL, session("TESTSESSIONTOKEN0002", NOW + 60_000), url, first, NOW);
        assertNotEquals(a.get("Authorization"), b.get("Authorization"));
        assertNotEquals(a.get("X-Amz-Content-Sha256"), b.get("X-Amz-Content-Sha256"));
        assertNotEquals(a.get("Authorization"), c.get("Authorization"));
        assertEquals(a.get("X-Amz-Content-Sha256"), c.get("X-Amz-Content-Sha256"));
        assertEquals("application/json", a.get("Accept"));
        assertFalse(a.containsKey("X-Amzn-Bedrock-Accept"));
    }

    @Test public void selectedTupleRejectsAnOldOrAlteredEndpoint() throws Exception {
        URL selected = BedrockEndpoint.endpoint("eu-west-2", MODEL, false);
        AwsSessionCredentials credentials = session("TESTSESSIONTOKEN0001", NOW + 60_000);
        byte[] bytes = "{}".getBytes(StandardCharsets.UTF_8);
        for (URL altered : new URL[] {
                new URL(selected + "?x=1"),
                new URL(selected + "#fragment"),
                new URL(selected.toString().replace(".amazonaws.com", ".amazonaws.com.attacker.invalid")),
                new URL(selected.toString().replace("https://", "http://")),
                new URL(selected.toString().replace(".com/", ".com:443/")),
                BedrockEndpoint.endpoint("us-east-1", MODEL, false),
                BedrockEndpoint.endpoint("eu-west-2", "anthropic.claude-opus-4-6-v1", false)
        }) {
            assertThrows(IllegalArgumentException.class,
                () -> BedrockSigV4.headers("eu-west-2", MODEL, credentials, altered, bytes, NOW));
        }
        assertThrows(IllegalArgumentException.class,
            () -> BedrockSigV4.headers("us-east-1", MODEL, credentials, selected, bytes, NOW));
    }

    @Test public void expirationIsCheckedBeforeAnySignatureAndNoDefaultSessionExists() throws Exception {
        URL selected = BedrockEndpoint.endpoint("eu-west-2", MODEL, false);
        byte[] bytes = "{}".getBytes(StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class,
            () -> BedrockSigV4.headers("eu-west-2", MODEL, session("TESTSESSIONTOKEN0001", NOW), selected, bytes, NOW));
        assertThrows(IllegalArgumentException.class,
            () -> BedrockSigV4.headers("eu-west-2", MODEL, null, selected, bytes, NOW));
        assertThrows(IllegalArgumentException.class,
            () -> BedrockSigV4.headers("eu-west-2", MODEL, session("TESTSESSIONTOKEN0001", NOW + 1), selected, bytes, 0));
        assertNotNull(BedrockSigV4.headers("eu-west-2", MODEL,
            session("TESTSESSIONTOKEN0001", NOW + 1), selected, bytes, NOW));
    }

    @Test public void onlyExplicitCurrentMessagesModelsAndCommercialRegionSyntaxAreAdmitted() throws Exception {
        assertEquals("https://bedrock-runtime.eu-west-2.amazonaws.com/model/anthropic.claude-sonnet-4-6/invoke",
            BedrockEndpoint.endpoint("eu-west-2", MODEL, false).toString());
        assertEquals("https://bedrock-runtime.us-east-1.amazonaws.com/model/anthropic.claude-opus-4-6-v1/invoke-with-response-stream",
            BedrockEndpoint.endpoint("us-east-1", "anthropic.claude-opus-4-6-v1", true).toString());
        for (String region : new String[] {null, "", " EU-WEST-2", "eu-west-2 ", "cn-north-1",
                "us-gov-west-1", "us-iso-east-1", "us-isob-east-1", "eu-west-2.invalid", "../us-east-1"}) {
            assertThrows(IllegalArgumentException.class, () -> BedrockEndpoint.endpoint(region, MODEL, false));
        }
        for (String model : new String[] {null, "", "anthropic.claude-v2", "anthropic.claude-instant-v1",
                "anthropic.claude-3-haiku-20240307-v1:0", "anthropic.claude-future-99",
                "us.anthropic.claude-sonnet-4-6", "arn:aws:bedrock:eu-west-2:123456789012:foundation-model/" + MODEL,
                MODEL + "/other", MODEL + "?x=1", MODEL.replace("a", "%61"), MODEL + "#x"}) {
            assertThrows(IllegalArgumentException.class, () -> BedrockEndpoint.endpoint("eu-west-2", model, false));
        }
    }

    @Test public void wireLabelAndCanonicalPathHaveDistinctOfficialEncodingSteps() throws Exception {
        String historicalId = "anthropic.claude-3-haiku-20240307-v1:0";
        assertEquals("anthropic.claude-3-haiku-20240307-v1%3A0", BedrockEndpoint.encodeLabel(historicalId));
        URL fixtureOnly = new URL("https://example.amazonaws.com/model/" + BedrockEndpoint.encodeLabel(historicalId) + "/invoke");
        assertEquals("/model/anthropic.claude-3-haiku-20240307-v1%253A0/invoke", BedrockSigV4.canonicalPath(fixtureOnly));
        // Exact expectation from AWS V4CanonicalRequestTest, not from this signer.
        assertEquals("/foo/%252F", BedrockSigV4.canonicalPath(new URL("https://example.amazonaws.com/foo/%2F")));
        assertEquals("a%20b%2A~%2F", BedrockEndpoint.encodeLabel("a b*~/"));
        assertEquals("/bar", BedrockSigV4.canonicalPath(new URL("https://example.amazonaws.com/foo/../bar")));
    }

    @Test public void canonicalHeadersUseOfficialWhitespaceRuleAndRejectAmbiguousDuplicates() throws Exception {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Host", "example.amazonaws.com");
        headers.put("X-Amz-Date", "20150830T123600Z");
        headers.put("My-Header", "  a\t b \r\n c  ");
        String canonical = BedrockSigV4.canonicalRequest("GET", new URL("https://example.amazonaws.com/"), headers, new byte[0]);
        assertTrue(canonical.contains("my-header:a b c\n"));
        assertTrue(canonical.contains("\nhost;my-header;x-amz-date\n"));
        headers.put("host", "another.invalid");
        assertThrows(IllegalArgumentException.class,
            () -> BedrockSigV4.canonicalRequest("GET", new URL("https://example.amazonaws.com/"), headers, new byte[0]));
    }

    @Test public void boundedSignerNeverSilentlyIgnoresQueryOrFragment() throws Exception {
        Map<String, String> headers = Map.of("host", "example.amazonaws.com", "x-amz-date", "20150830T123600Z");
        assertThrows(IllegalArgumentException.class,
            () -> BedrockSigV4.canonicalRequest("GET", new URL("https://example.amazonaws.com/?a=1"), headers, new byte[0]));
        assertThrows(IllegalArgumentException.class,
            () -> BedrockSigV4.canonicalRequest("GET", new URL("https://example.amazonaws.com/#x"), headers, new byte[0]));
    }

    private static void assertAwsVector(String name) throws Exception {
        String request = fixture(name, "req");
        String expectedCanonical = fixture(name, "creq");
        String[] sections = request.split("\n\n", 2);
        String[] lines = sections[0].split("\n");
        String[] firstLine = lines[0].split(" ");
        Map<String, String> headers = new LinkedHashMap<>();
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            headers.put(lines[i].substring(0, colon), lines[i].substring(colon + 1));
        }
        // The official loader adds this public fixture token from the canonical file.
        for (String line : expectedCanonical.split("\n")) {
            if (line.startsWith("x-amz-security-token:")) {
                headers.put("X-Amz-Security-Token", line.substring("x-amz-security-token:".length()));
            }
        }
        URL endpoint = new URL("https://" + headers.get("Host") + firstLine[1]);
        byte[] body = (sections.length == 2 ? sections[1] : "").getBytes(StandardCharsets.UTF_8);
        assertEquals(expectedCanonical, BedrockSigV4.canonicalRequest(firstLine[0], endpoint, headers, body));
        assertEquals(fixture(name, "authz"),
            BedrockSigV4.authorization(firstLine[0], endpoint, headers, body, "us-east-1", "service",
                "AKIDEXAMPLE", EXAMPLE_SECRET, VECTOR_TIME));
    }

    private static String fixture(String name, String extension) throws Exception {
        String path = "/bedrock/sigv4/" + name + "/" + name + "." + extension;
        try (InputStream input = BedrockSigV4Test.class.getResourceAsStream(path)) {
            assertNotNull("Missing exact upstream fixture: " + path, input);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static AwsSessionCredentials session(String token, long expires) {
        return new AwsSessionCredentials("ASIAEXAMPLE1234567890", EXAMPLE_SECRET, token, expires);
    }

    private static String hex(byte[] bytes) {
        StringBuilder output = new StringBuilder();
        for (byte value : bytes) output.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return output.toString();
    }
}

