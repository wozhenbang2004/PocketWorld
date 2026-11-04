package dev.pocketengine.core;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Set;
import org.json.JSONObject;

/** One explicitly supplied STS session. No default provider chain, refresh or public projection. */
public final class AwsSessionCredentials {
    private final String accessKeyId, secretAccessKey, sessionToken;
    private final long expiresAtEpochMillis;

    public AwsSessionCredentials(String accessKeyId, String secretAccessKey, String sessionToken, long expiresAtEpochMillis) {
        if (accessKeyId == null || !accessKeyId.matches("ASIA[A-Z0-9]{12,124}")
                || secretAccessKey == null || !secretAccessKey.matches("[A-Za-z0-9+/=]{16,256}")
                || sessionToken == null || !sessionToken.matches("[A-Za-z0-9+/=_-]{16,16384}")
                || expiresAtEpochMillis < 1) throw new IllegalArgumentException("请提供完整的正式 STS 临时凭据与原到期时间；不支持长期密钥或自动获取凭据。");
        this.accessKeyId = accessKeyId;
        this.secretAccessKey = secretAccessKey;
        this.sessionToken = sessionToken;
        this.expiresAtEpochMillis = expiresAtEpochMillis;
    }

    /** Internal signer access only. Never copy these values into a receipt or public snapshot. */
    public String accessKeyId() { return accessKeyId; }
    public String secretAccessKey() { return secretAccessKey; }
    public String sessionToken() { return sessionToken; }
    public long expiresAtEpochMillis() { return expiresAtEpochMillis; }
    public void requireValidAt(long nowMillis) {
        if (nowMillis < 1 || nowMillis >= expiresAtEpochMillis)
            throw new IllegalArgumentException("此连接的 STS 临时凭据已过期或时间不可核对；请明确替换完整会话，没有刷新或切换连接。");
    }

    /** The original owner-only settings parser uses this; generic ModelConfig JSON cannot. */
    public static AwsSessionCredentials fromOwnerInput(JSONObject input) {
        try {
            fields(input, Set.of("accessKeyId", "secretAccessKey", "sessionToken", "expiresAt"));
            requireStrings(input);
            Object raw = input.get("expiresAt");
            if (!(raw instanceof String expiry) || !expiry.endsWith("Z") || expiry.length() > 40) throw new IllegalArgumentException();
            return new AwsSessionCredentials(input.getString("accessKeyId"), input.getString("secretAccessKey"),
                    input.getString("sessionToken"), Instant.parse(expiry).toEpochMilli());
        } catch (Exception invalid) {
            throw new IllegalArgumentException("请填写完整 STS 临时凭据，并使用 UTC 到期时间（例如 2026-11-04T12:00:00Z）。");
        }
    }

    /** Only SettingsStore's existing encrypted inactive slot receives this encoding. */
    public String toVaultString() throws Exception {
        return new JSONObject().put("format", "pocketworld.aws-sts-session.v1")
                .put("accessKeyId", accessKeyId).put("secretAccessKey", secretAccessKey)
                .put("sessionToken", sessionToken).put("expiresAtEpochMillis", expiresAtEpochMillis).toString();
    }
    public static AwsSessionCredentials fromVaultString(String encoded) {
        try {
            if (encoded == null || encoded.length() > 20000) throw new IllegalArgumentException();
            JSONObject input = new JSONObject(encoded);
            fields(input, Set.of("format", "accessKeyId", "secretAccessKey", "sessionToken", "expiresAtEpochMillis"));
            requireStrings(input);
            Object expiry = input.get("expiresAtEpochMillis");
            if (!"pocketworld.aws-sts-session.v1".equals(input.getString("format"))
                    || !(expiry instanceof Long || expiry instanceof Integer)) throw new IllegalArgumentException();
            return new AwsSessionCredentials(input.getString("accessKeyId"), input.getString("secretAccessKey"),
                    input.getString("sessionToken"), ((Number) expiry).longValue());
        } catch (Exception invalid) { throw new IllegalStateException("此连接的短期凭据无法读取；请保留原连接并明确替换完整会话。"); }
    }
    private static void fields(JSONObject value, Set<String> expected) {
        if (value == null) throw new IllegalArgumentException();
        Set<String> found = new java.util.HashSet<>(); value.keys().forEachRemaining(found::add);
        if (!found.equals(expected)) throw new IllegalArgumentException();
    }
    private static void requireStrings(JSONObject input){
        for(String field:new String[]{"accessKeyId","secretAccessKey","sessionToken"})if(!(input.opt(field) instanceof String))throw new IllegalArgumentException();
    }
    boolean contains(String value) {
        return value != null && (value.contains(accessKeyId) || value.contains(secretAccessKey) || value.contains(sessionToken));
    }
    String redact(String value) {
        if (value == null) return "";
        String[] values={accessKeyId,secretAccessKey,sessionToken};
        java.util.Arrays.sort(values,(left,right)->Integer.compare(right.length(),left.length()));
        int[] next=new int[values.length];for(int i=0;i<values.length;i++)next[i]=value.indexOf(values[i]);
        StringBuilder safe=new StringBuilder();int copied=0;
        while(true){
            int first=-1;for(int i=0;i<next.length;i++)if(next[i]>=0&&(first<0||next[i]<next[first]))first=i;
            if(first<0)break;
            int start=next[first],end=start;
            // Merge overlapping original matches before replacing anything. A short
            // value must not destroy a longer token or expose an overlapping tail.
            while(first>=0&&next[first]<=end){
                end=Math.max(end,next[first]+values[first].length());next[first]=value.indexOf(values[first],next[first]+1);
                first=-1;for(int i=0;i<next.length;i++)if(next[i]>=0&&(first<0||next[i]<next[first]))first=i;
            }
            safe.append(value,copied,start).append("[密钥已隐藏]");copied=end;
        }
        return safe.append(value,copied,value.length()).toString();
    }
    int suffixLength(String text) {
        return Math.max(suffix(text, accessKeyId), Math.max(suffix(text, secretAccessKey), suffix(text, sessionToken)));
    }
    boolean crossesBoundary(String text,int boundary){return crosses(text,accessKeyId,boundary)||crosses(text,secretAccessKey,boundary)||crosses(text,sessionToken,boundary);}
    static boolean crosses(String text,String secret,int boundary){if(secret==null||secret.isEmpty()||boundary<=0)return false;int start=text.lastIndexOf(secret,boundary-1);return start>=0&&start+secret.length()>boundary;}
    static int suffix(String text, String secret) {
        if (text == null || secret == null || secret.isEmpty()) return 0;
        int limit=Math.min(text.length(),secret.length()-1);if(limit==0)return 0;
        // Only an incomplete prefix can be held. Linear prefix matching avoids
        // rescanning a 16 KiB session token for every possible split point.
        int[] prefix=new int[limit];
        for(int i=1,matched=0;i<limit;i++){
            while(matched>0&&secret.charAt(i)!=secret.charAt(matched))matched=prefix[matched-1];
            if(secret.charAt(i)==secret.charAt(matched))matched++;
            prefix[i]=matched;
        }
        int matched=0;
        for(int i=text.length()-limit;i<text.length();i++){
            while(matched>0&&text.charAt(i)!=secret.charAt(matched))matched=prefix[matched-1];
            if(text.charAt(i)==secret.charAt(matched))matched++;
        }
        return matched;
    }
    String identityDigest() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update("pocketworld.aws-sts-session.v1".getBytes(StandardCharsets.UTF_8));
            for (String value : new String[]{accessKeyId, secretAccessKey, sessionToken}) {
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array()); digest.update(bytes);
            }
            digest.update(ByteBuffer.allocate(8).putLong(expiresAtEpochMillis).array());
            StringBuilder out = new StringBuilder(); for (byte b : digest.digest()) out.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            return out.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException("无法核对原凭据身份"); }
    }
    @Override public String toString() { return "AwsSessionCredentials[private]"; }
}
