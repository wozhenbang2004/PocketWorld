/*
 * Portions adapted from AWS SDK for Java v2.57.4 (Apache-2.0) and
 * aws-samples/sigv4-signing-examples/no-sdk/java/AWSSigner.java (MIT-0).
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * See UPSTREAM.md and the complete packaged licenses in assets/licenses/bedrock/.
 */
package dev.pocketengine.runtime.providers.bedrock;

import dev.pocketengine.core.AwsSessionCredentials;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Signs only the final original transport bytes and an explicitly selected temporary session.
 * No credential discovery, refresh, HTTP client, retries or account-side operations live here. */
public final class BedrockSigV4 {
    private static final String ALGORITHM = "AWS4-HMAC-SHA256";
    private static final DateTimeFormatter AMZ_DATE =
        DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'", Locale.ROOT).withZone(ZoneOffset.UTC);

    private BedrockSigV4() {}

    public static Map<String, String> headers(String region, String model,
            AwsSessionCredentials credentials, URL endpoint, byte[] payload, long nowMillis) throws Exception {
        BedrockEndpoint.validate(region, model);
        if (credentials == null || endpoint == null || payload == null) {
            throw new IllegalArgumentException("bedrock_signing_input_missing");
        }
        credentials.requireValidAt(nowMillis);
        String actual = endpoint.toExternalForm();
        boolean stream = actual.equals(BedrockEndpoint.endpoint(region, model, true).toExternalForm());
        if (!stream && !actual.equals(BedrockEndpoint.endpoint(region, model, false).toExternalForm())) {
            throw new IllegalArgumentException("bedrock_endpoint_identity_changed");
        }
        requireHeaderValue(credentials.accessKeyId());
        requireHeaderValue(credentials.sessionToken());

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Accept", stream ? "application/vnd.amazon.eventstream" : "application/json");
        if (stream) headers.put("X-Amzn-Bedrock-Accept", "application/json");
        headers.put("Host", endpoint.getHost());
        headers.put("X-Amz-Date", amzDate(nowMillis));
        headers.put("X-Amz-Security-Token", credentials.sessionToken());
        headers.put("X-Amz-Content-Sha256", sha256(payload));
        headers.put("Authorization", authorization("POST", endpoint, headers, payload, region, "bedrock",
            credentials.accessKeyId(), credentials.secretAccessKey(), nowMillis));
        return Collections.unmodifiableMap(headers);
    }

    /* Package-private deterministic primitives let the production algorithm be checked against
       independent AWS vectors. They sign values only; they cannot select a route or dispatch. */
    static String authorization(String method, URL endpoint, Map<String, String> headers, byte[] payload,
            String region, String service, String accessKey, String secretKey, long nowMillis) throws Exception {
        String date = amzDate(nowMillis);
        Map<String, String> canonicalHeaders = canonicalHeaders(headers);
        if (!date.equals(canonicalHeaders.get("x-amz-date"))) {
            throw new IllegalArgumentException("bedrock_signing_date_mismatch");
        }
        String day = date.substring(0, 8);
        String scope = day + "/" + region + "/" + service + "/aws4_request";
        String request = canonicalRequest(method, endpoint, canonicalHeaders, payload);
        String toSign = ALGORITHM + "\n" + date + "\n" + scope + "\n"
            + sha256(request.getBytes(StandardCharsets.UTF_8));
        byte[] signingKey = signatureKey(secretKey, day, region, service);
        try {
            return ALGORITHM + " Credential=" + accessKey + "/" + scope
                + ", SignedHeaders=" + String.join(";", canonicalHeaders.keySet())
                + ", Signature=" + hex(hmac(signingKey, toSign));
        } finally {
            Arrays.fill(signingKey, (byte) 0);
        }
    }

    static String canonicalRequest(String method, URL endpoint, Map<String, String> headers,
            byte[] payload) throws Exception {
        if (endpoint.getQuery() != null || endpoint.getRef() != null || endpoint.getUserInfo() != null) {
            throw new IllegalArgumentException("bedrock_signing_url_unsupported");
        }
        Map<String, String> canonical = canonicalHeaders(headers);
        StringBuilder values = new StringBuilder();
        for (Map.Entry<String, String> entry : canonical.entrySet()) {
            values.append(entry.getKey()).append(':').append(entry.getValue()).append('\n');
        }
        return method + "\n" + canonicalPath(endpoint) + "\n\n" + values + "\n"
            + String.join(";", canonical.keySet()) + "\n" + sha256(payload);
    }

    static String canonicalPath(URL endpoint) throws Exception {
        URI uri = endpoint.toURI();
        String path = uri.normalize().getRawPath();
        if (path == null || path.isEmpty() || "/".equals(path)) return "/";
        // Official default non-S3 signing encodes the already encoded raw path again.
        path = BedrockEndpoint.encodeLabel(path).replace("%2F", "/");
        if (!path.startsWith("/")) throw new IllegalArgumentException("bedrock_signing_path_invalid");
        if (path.length() > 1 && !uri.getPath().endsWith("/") && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path;
    }

    private static Map<String, String> canonicalHeaders(Map<String, String> input) {
        Map<String, String> result = new TreeMap<>();
        for (Map.Entry<String, String> entry : input.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                throw new IllegalArgumentException("bedrock_signing_header_invalid");
            }
            String key = entry.getKey().toLowerCase(Locale.ROOT);
            if (!key.matches("[a-z0-9-]+") || "authorization".equals(key)
                || result.putIfAbsent(key, trimAndCollapse(entry.getValue())) != null) {
                throw new IllegalArgumentException("bedrock_signing_header_invalid");
            }
        }
        return result;
    }

    /** Same whitespace rule as V4CanonicalRequest.addAndTrim. */
    private static String trimAndCollapse(String value) {
        StringBuilder result = new StringBuilder(value.length());
        boolean pendingSpace = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == ' ' || c == '\t' || c == '\r' || c == '\n' || c == '\u000b' || c == '\f') {
                pendingSpace = result.length() > 0;
            } else {
                if (pendingSpace) result.append(' ');
                result.append(c);
                pendingSpace = false;
            }
        }
        return result.toString();
    }

    private static String amzDate(long nowMillis) {
        if (nowMillis < 0) throw new IllegalArgumentException("bedrock_signing_clock_invalid");
        String date = AMZ_DATE.format(Instant.ofEpochMilli(nowMillis));
        if (!date.matches("[0-9]{8}T[0-9]{6}Z")) {
            throw new IllegalArgumentException("bedrock_signing_clock_invalid");
        }
        return date;
    }

    private static void requireHeaderValue(String value) {
        if (value == null || value.isEmpty()) throw new IllegalArgumentException("bedrock_session_invalid");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 33 || c > 126) throw new IllegalArgumentException("bedrock_session_invalid");
        }
    }

    static String sha256(byte[] data) throws Exception {
        return hex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    private static byte[] signatureKey(String key, String day, String region, String service) throws Exception {
        byte[] secret = ("AWS4" + key).getBytes(StandardCharsets.UTF_8);
        byte[] dateKey = null, regionKey = null, serviceKey = null;
        try {
            dateKey = hmac(secret, day);
            regionKey = hmac(dateKey, region);
            serviceKey = hmac(regionKey, service);
            return hmac(serviceKey, "aws4_request");
        } finally {
            Arrays.fill(secret, (byte) 0);
            if (dateKey != null) Arrays.fill(dateKey, (byte) 0);
            if (regionKey != null) Arrays.fill(regionKey, (byte) 0);
            if (serviceKey != null) Arrays.fill(serviceKey, (byte) 0);
        }
    }

    private static byte[] hmac(byte[] key, String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
    }

    private static String hex(byte[] bytes) {
        char[] output = new char[bytes.length * 2];
        char[] alphabet = "0123456789abcdef".toCharArray();
        for (int i = 0; i < bytes.length; i++) {
            output[i * 2] = alphabet[(bytes[i] & 255) >>> 4];
            output[i * 2 + 1] = alphabet[bytes[i] & 15];
        }
        return new String(output);
    }
}

