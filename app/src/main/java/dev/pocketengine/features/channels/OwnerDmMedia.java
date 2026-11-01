package dev.pocketengine.features.channels;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.attachments.AttachmentBook;
import dev.pocketengine.features.attachments.AttachmentRepository;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import org.json.JSONArray;
import org.json.JSONObject;

/** Native selected owner-DM material. Uses the original inbox, Vault and Book only.
 * Saving bytes is neither model understanding nor permission to send them elsewhere. */
final class OwnerDmMedia {
    static final int MAX_BYTES = 8 * 1024 * 1024;
    private final FeatureContext context;
    private final ChannelCore core;
    private final GroupInboundFiles download;
    private final AttachmentBook book;

    OwnerDmMedia(FeatureContext context, ChannelCore core, GroupInboundFiles download) {
        this.context = context;
        this.core = core;
        this.download = download;
        book = AttachmentRepository.get(context.android).book;
    }

    /** Called after authenticated acceptance, before the raw iLink response is discarded.
     * References/keys never enter the public inbox, source metadata or model history. */
    synchronized void captureWeixin(JSONObject event, JSONObject raw, RunControl control) throws Exception {
        JSONArray positions = ChannelMessageParts.ownerMediaPositions(event.optJSONObject("messageParts"));
        if (positions.length() == 0) return;
        JSONObject route = event.getJSONObject("route");
        try { core.checkOwnerMediaOrigin(event, route); }
        catch (SecurityException notConsented) { return; }
        JSONArray specs = new JSONArray(), parts = event.getJSONObject("messageParts").getJSONArray("parts");
        JSONArray items = raw.getJSONArray("item_list");
        for (int n = 0; n < positions.length(); n++) {
            int index = positions.getInt(n);
            JSONObject part = parts.getJSONObject(index);
            try {
                int wire = part.getJSONObject("source").getInt("position");
                specs.put(descriptor(items.getJSONObject(wire), part.getString("mediaKind"), index, wire));
            } catch (Exception unsupported) {
                specs.put(new JSONObject().put("itemIndex", index).put("status", "unavailable")
                        .put("reason", "原媒体引用不完整、类型/大小不支持；未补造地址或密钥"));
            }
        }
        JSONObject entry = new JSONObject().put("source", source(event, route))
                .put("createdAt", System.currentTimeMillis()).put("media", specs);
        control.check(); core.checkOwnerMediaOrigin(event, route);
        String slot = slot(event.getString("profileId")), stored = context.secrets.get(slot);
        JSONObject entries = stored.isEmpty() ? new JSONObject() : new JSONObject(stored);
        entries.remove(event.getString("id")); entries.put(event.getString("id"), entry);
        for (java.util.Iterator<String> keys = entries.keys(); keys.hasNext();) {
            String key = keys.next();
            if (System.currentTimeMillis() - entries.getJSONObject(key).optLong("createdAt") > 86_400_000L) keys.remove();
        }
        while (entries.length() > 100 || entries.toString().length() > 48000) entries.remove(entries.keys().next());
        context.secrets.put(slot, entries.toString());
        control.check(); core.checkOwnerMediaOrigin(event, route);
    }

    private JSONObject descriptor(JSONObject item, String kind, int index, int wire) throws Exception {
        int expected = kind.equals("file") ? 4 : kind.equals("voice") ? 3 : 5;
        if (!(item.opt("type") instanceof Number) || ((Number) item.get("type")).doubleValue() != expected)
            throw new SecurityException("原消息项类型不符");
        JSONObject nativeMedia = item.getJSONObject(kind + "_item"), media = nativeMedia.getJSONObject("media");
        if (media.has("encrypt_type") && (!(media.opt("encrypt_type") instanceof Number)
                || (((Number) media.get("encrypt_type")).doubleValue() != 0 && ((Number) media.get("encrypt_type")).doubleValue() != 1)))
            throw new IllegalArgumentException("原媒体加密类型不支持");
        String full = string(media, "full_url", 8192), query = string(media, "encrypt_query_param", 4096);
        if (full.isEmpty() && query.isEmpty()) throw new IllegalArgumentException("原媒体没有下载引用");
        String address = full.isEmpty() ? "https://novac2c.cdn.weixin.qq.com/c2c/download?encrypted_query_param="
                + java.net.URLEncoder.encode(query, StandardCharsets.UTF_8.name()) : full;
        address = download.weixinAddress(address);
        byte[] key = WeixinOwnerImages.key("", string(media, "aes_key", 128));
        try {
            // Official non-image items require a decryption key; never pass ciphertext as a file.
            if (key.length != 16) throw new IllegalArgumentException("原媒体缺少完整解密密钥");
            JSONObject spec = new JSONObject().put("itemIndex", index).put("sourceItemIndex", wire)
                    .put("status", "captured").put("mediaKind", kind).put("url", address)
                    .put("aesKey", Base64.getEncoder().encodeToString(key));
            String name = kind.equals("file") ? string(nativeMedia, "file_name", 160) : "weixin-" + kind + "-" + wire;
            spec.put("name", AttachmentBook.displayName(name.isEmpty() ? "weixin-file-" + wire : name));
            if (kind.equals("file")) {
                Object length = nativeMedia.opt("len");
                if (!(length instanceof String) || !((String) length).matches("[0-9]{1,12}")) throw new IllegalArgumentException("文件没有明确大小");
                long bytes = Long.parseLong((String) length);
                if (bytes > MAX_BYTES) throw new IllegalArgumentException("所选文件超过8MiB");
                spec.put("expectedPlainBytes", bytes);
            }
            String md5 = string(nativeMedia, kind.equals("video") ? "video_md5" : "md5", 32);
            if (!md5.isEmpty()) {
                if (!md5.matches("[a-fA-F0-9]{32}")) throw new IllegalArgumentException("原媒体MD5格式无效");
                spec.put("expectedMd5", md5.toLowerCase(java.util.Locale.ROOT));
            }
            if (kind.equals("voice") && nativeMedia.opt("encode_type") instanceof Number)
                spec.put("platformCodecId", nativeMedia.get("encode_type"));
            return spec;
        } finally { Arrays.fill(key, (byte) 0); }
    }

    JSONObject review(JSONObject event, JSONObject route, int index) throws Exception {
        core.checkOwnerMediaOrigin(event, route);
        JSONObject existing = find(core.ownerMediaReferences(event, route), index);
        JSONObject spec = existing == null ? originalDescriptor(event, route, index) : new JSONObject(existing.toString());
        JSONObject view = new JSONObject().put("eventId", event.getString("id")).put("itemIndex", index)
                .put("profileId", event.getString("profileId")).put("profileName", core.profile(event.getString("profileId")).getString("name"))
                .put("senderId", event.getString("senderId")).put("mediaKind", spec.getString("mediaKind"))
                .put("name", spec.getString("name")).put("downloadLimitBytes", MAX_BYTES)
                .put("automaticModelInput", false).put("automaticRemoteUpload", false).put("automaticReply", false);
        if (spec.has("expectedPlainBytes")) view.put("declaredBytes", spec.getLong("expectedPlainBytes"));
        if (spec.has("platformCodecId")) view.put("platformCodecId", spec.get("platformCodecId"));
        if (existing != null) view.put("frozen", verified(event, route, existing));
        return view;
    }

    JSONObject freeze(JSONObject event, JSONObject route, int index, RunControl control) throws Exception {
        core.checkOwnerMediaOrigin(event, route); control.check();
        JSONObject known = find(core.ownerMediaReferences(event, route), index);
        if (known != null) return verifiedBytes(event, route, known, control);
        // An inbox-save failure after a successful Book commit must not create another blob.
        JSONObject recovered = null;
        JSONArray catalog = book.list();
        for (int n = 0; n < catalog.length(); n++) {
            JSONObject meta = catalog.getJSONObject(n), origin = meta.optJSONObject("source");
            if (origin == null || !origin.optString("kind").equals(provider(event) + "_owner_dm_media")
                    || origin.optInt("itemIndex", -1) != index || !event.getString("id").equals(origin.optString("eventId"))) continue;
            checkSource(source(event, route), origin);
            if (recovered != null) throw new SecurityException("原媒体项有多个冻结原件，须本机核对，不猜最新");
            recovered = reference(meta, origin);
        }
        if (recovered != null) {
            JSONObject actual = verifiedBytes(event, route, recovered, control); core.ownerMediaFrozen(event, route, recovered); return actual;
        }
        JSONObject spec = originalDescriptor(event, route, index);
        byte[] encrypted = null, plain = null, key = null;
        try {
            encrypted = read(spec, control);
            key = spec.optString("aesKey").isEmpty() ? new byte[0] : Base64.getDecoder().decode(spec.getString("aesKey"));
            plain = decrypt(encrypted, key);
            if (spec.has("expectedPlainBytes") && plain.length != spec.getLong("expectedPlainBytes")) throw new SecurityException("实际文件大小与原消息不符");
            if (spec.has("expectedMd5") && !spec.getString("expectedMd5").equals(hex(MessageDigest.getInstance("MD5").digest(plain)))) throw new SecurityException("实际文件MD5与原消息不符");
            JSONObject facts = facts(plain, spec);
            JSONObject origin = source(event, route).put("kind", provider(event) + "_owner_dm_media")
                    .put("itemIndex", index).put("sourceItemIndex", spec.getInt("sourceItemIndex"))
                    .put("mediaKind", spec.getString("mediaKind")).put("contentUnderstandingVerified", false)
                    .put("formatEvidence", facts.getString("formatEvidence")).put("downloadAuthorizationInAttachmentMetadata", false);
            control.check(); core.checkOwnerMediaOrigin(event, route);
            JSONObject saved = book.importBytes(new ByteArrayInputStream(plain), spec.getString("name"), facts.getString("mimeType"), origin, control);
            JSONObject ref = reference(saved, origin);
            control.check(); core.ownerMediaFrozen(event, route, ref);
            return verifiedBytes(event, route, ref, control);
        } finally {
            if (encrypted != null) Arrays.fill(encrypted, (byte) 0);
            if (plain != null) Arrays.fill(plain, (byte) 0);
            if (key != null) Arrays.fill(key, (byte) 0);
        }
    }

    JSONObject frozenRemoteInputSource(String id, String hash) throws Exception {
        JSONObject meta = book.get(id), origin = meta.getJSONObject("source");
        if (!origin.optString("kind").matches("weixin_owner_dm_media|qq_owner_dm_media") || !hash.equals(meta.getString("sha256"))) throw new SecurityException("不是准确主人媒体原件");
        JSONObject event = core.event(origin.getString("eventId")), route = event.getJSONObject("route");
        checkSource(source(event, route), origin);
        if (!origin.getString("kind").equals(provider(event) + "_owner_dm_media")) throw new SecurityException("媒体来源平台不符");
        JSONObject ref = find(core.ownerMediaReferences(event, route), origin.getInt("itemIndex"));
        if (ref == null || !id.equals(ref.getString("artifactId")) || !hash.equals(ref.getString("sha256"))) throw new SecurityException("原消息不含此固定媒体版本");
        return new JSONObject().put("event", event).put("route", route).put("reference", ref)
                .put("source", source(event, route).put("provider", provider(event)).put("profileName", core.profile(event.getString("profileId")).getString("name"))
                        .put("attachmentId", id).put("sha256", hash).put("itemIndex", origin.getInt("itemIndex")).put("bytes", meta.getLong("size")).put("mediaKind", origin.getString("mediaKind")));
    }

    private JSONObject verifiedBytes(JSONObject event, JSONObject route, JSONObject ref, RunControl control) throws Exception {
        JSONObject meta = verified(event, route, ref);
        AttachmentBook.VerifiedMetadata verified = book.readVerifiedMetadata(ref.getString("artifactId"), ref.getString("sha256"), control);
        if (verified.size != ref.getLong("bytes")) throw new SecurityException("实际媒体大小变化");
        control.check(); core.checkOwnerMediaOrigin(event, route); return meta;
    }

    private JSONObject verified(JSONObject event, JSONObject route, JSONObject ref) throws Exception {
        core.checkOwnerMediaOrigin(event, route);
        JSONObject meta = book.get(ref.getString("artifactId")), origin = meta.getJSONObject("source");
        if (!meta.optString("principalId").equals("owner") || !meta.optString("spaceId").equals("private")
                || !origin.optString("kind").equals(provider(event) + "_owner_dm_media")) throw new SecurityException("原媒体不属于此私人来源");
        checkSource(source(event, route), origin);
        if (origin.getInt("itemIndex") != ref.getInt("itemIndex") || !meta.getString("sha256").equals(ref.getString("sha256"))
                || meta.getLong("size") != ref.getLong("bytes") || !meta.getString("mimeType").equals(ref.getString("mimeType"))) throw new SecurityException("原媒体版本已变化");
        return meta;
    }

    private synchronized JSONObject originalDescriptor(JSONObject event, JSONObject route, int index) throws Exception {
        core.checkOwnerMediaOrigin(event, route);
        JSONArray positions = ChannelMessageParts.ownerMediaPositions(event.optJSONObject("messageParts"));
        boolean current = false; for (int n = 0; n < positions.length(); n++) if (positions.getInt(n) == index) current = true;
        if (!current) throw new SecurityException("不是当前消息的原媒体项，引用不升级成新输入");
        JSONObject part = core.event(event.getString("id")).getJSONObject("messageParts").getJSONArray("parts").getJSONObject(index);
        if (provider(event).equals("qq")) {
            JSONObject media = part.getJSONObject("media"); download.checkedAddress(media.getString("url"), false);
            JSONObject spec = new JSONObject(media.toString()).put("itemIndex", index).put("sourceItemIndex", part.getJSONObject("source").getInt("position"))
                    .put("mediaKind", media.optString("mimeType").startsWith("audio/") ? "voice" : media.optString("mimeType").startsWith("video/") ? "video" : "file")
                    .put("name", AttachmentBook.displayName(media.optString("name", "qq-media-" + index))).put("aesKey", "").put("nativeProvider", "qq");
            if (media.optBoolean("declaredSizeKnown")) spec.put("expectedPlainBytes", media.getLong("bytes"));
            if (spec.optLong("expectedPlainBytes", 0) > MAX_BYTES) throw new IllegalArgumentException("所选媒体超过8MiB");
            return spec;
        }
        String stored = context.secrets.get(slot(event.getString("profileId")));
        JSONObject entry = stored.isEmpty() ? null : new JSONObject(stored).optJSONObject(event.getString("id"));
        if (entry == null || System.currentTimeMillis() - entry.optLong("createdAt") > 86_400_000L) throw new SecurityException("原媒体下载授权已过期或未保留；不替换来源");
        checkSource(source(event, route), entry.getJSONObject("source"));
        JSONArray specs = entry.getJSONArray("media");
        for (int n = 0; n < specs.length(); n++) {
            JSONObject spec = specs.getJSONObject(n);
            if (spec.getInt("itemIndex") != index) continue;
            if (!spec.optString("status").equals("captured")) throw new SecurityException(spec.optString("reason"));
            download.weixinAddress(spec.getString("url")); return new JSONObject(spec.toString());
        }
        throw new SecurityException("原媒体下载引用不对应此消息项");
    }

    private byte[] read(JSONObject spec, RunControl control) throws Exception {
        JSONObject wire = new JSONObject().put("url", spec.getString("url")).put("bytes", 0);
        boolean qq = spec.optString("nativeProvider").equals("qq");
        try (GroupInboundFiles.Download body = qq ? download.open(wire, control) : download.openWeixin(wire, control)) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); byte[] chunk = new byte[32768];
            for (int n; (n = body.input.read(chunk)) != -1;) {
                control.check(); if (n == 0) continue;
                if (bytes.size() + n > MAX_BYTES + (qq ? 0 : 16)) throw new IllegalArgumentException("原媒体超出8MiB下载范围，未保留部分原件");
                bytes.write(chunk, 0, n);
            }
            control.check(); return bytes.toByteArray();
        }
    }

    static byte[] decrypt(byte[] encrypted, byte[] key) throws Exception {
        if (encrypted.length > MAX_BYTES + 16) throw new IllegalArgumentException("原媒体超限");
        if (key.length == 0) return encrypted.clone();
        if (key.length != 16 || encrypted.length == 0 || encrypted.length % 16 != 0) throw new SecurityException("原媒体加密块不完整");
        Cipher aes = Cipher.getInstance("AES/ECB/PKCS5Padding"); aes.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"));
        byte[] plain = aes.doFinal(encrypted);
        if (plain.length > MAX_BYTES) { Arrays.fill(plain, (byte) 0); throw new IllegalArgumentException("解密后媒体超限"); }
        return plain;
    }

    private static JSONObject facts(byte[] bytes, JSONObject spec) throws Exception {
        String mime = "application/octet-stream", evidence = "opaque_bytes_no_decoder_claim";
        if (starts(bytes, "%PDF-")) { mime = "application/pdf"; evidence = "pdf_header_only"; }
        else if (starts(bytes, "#!AMR")) { mime = "audio/amr"; evidence = "amr_header_only_no_transcription"; }
        else if (starts(bytes, "#!SILK_V3") || bytes.length > 10 && bytes[0] == 2 && new String(bytes, 1, 9, StandardCharsets.US_ASCII).equals("#!SILK_V3")) { mime = "audio/silk"; evidence = "silk_header_only_no_decoder"; }
        else if (starts(bytes, "OggS")) { mime = "audio/ogg"; evidence = "ogg_header_only_no_codec_or_transcription_claim"; }
        else if (starts(bytes, "ID3")) { mime = "audio/mpeg"; evidence = "id3_header_only"; }
        else if (bytes.length >= 12 && starts(bytes, "RIFF") && new String(bytes, 8, 4, StandardCharsets.US_ASCII).equals("WAVE")) { mime = "audio/wav"; evidence = "wave_container_header_only"; }
        else if (bytes.length >= 12 && new String(bytes, 4, 4, StandardCharsets.US_ASCII).equals("ftyp") && spec.optString("mediaKind").equals("video")) { mime = "video/mp4"; evidence = "mp4_container_header_only"; }
        return new JSONObject().put("mimeType", mime).put("formatEvidence", evidence);
    }

    private String provider(JSONObject event) throws Exception { return core.profile(event.getString("profileId")).getString("provider"); }
    private static boolean starts(byte[] bytes, String prefix) { byte[] expected = prefix.getBytes(StandardCharsets.US_ASCII); if (bytes.length < expected.length) return false; for (int n = 0; n < expected.length; n++) if (bytes[n] != expected[n]) return false; return true; }
    private static String string(JSONObject object, String key, int max) throws Exception { Object value = object.opt(key); if (value == null || value == JSONObject.NULL) return ""; if (!(value instanceof String) || ((String) value).length() > max) throw new IllegalArgumentException("原媒体字段格式无效"); return (String) value; }
    private static String slot(String profile) { return "channels." + profile + ".owner_media_sources"; }
    synchronized void reset(String profile) throws Exception { context.secrets.remove(slot(profile)); }
    static JSONObject source(JSONObject event, JSONObject route) throws Exception { JSONObject result = new JSONObject().put("bindingEpoch", route.getString("bindingEpoch")); for (String key : new String[]{"id", "profileId", "messageId", "senderId", "transportEpoch"}) result.put(key.equals("id") ? "eventId" : key, event.getString(key)); if (event.optString("conversationKind").startsWith("qq_")) QqConversation.copyNative(event, result); return result; }
    private static void checkSource(JSONObject expected, JSONObject actual) throws Exception { for (java.util.Iterator<String> keys = expected.keys(); keys.hasNext();) { String key = keys.next(); if (!expected.get(key).equals(actual.opt(key))) throw new SecurityException("原媒体账号、消息或绑定已变化"); } }
    private static JSONObject reference(JSONObject meta, JSONObject origin) throws Exception { return new JSONObject().put("itemIndex", origin.getInt("itemIndex")).put("sourceItemIndex", origin.getInt("sourceItemIndex")).put("artifactId", meta.getString("id")).put("sha256", meta.getString("sha256")).put("bytes", meta.getLong("size")).put("mimeType", meta.getString("mimeType")).put("name", meta.getString("name")).put("mediaKind", origin.getString("mediaKind")); }
    private static JSONObject find(JSONArray refs, int index) throws Exception { JSONObject found = null; for (int n = 0; n < refs.length(); n++) if (refs.getJSONObject(n).getInt("itemIndex") == index) { if (found != null) throw new SecurityException("原媒体固定版本重复"); found = refs.getJSONObject(n); } return found; }
    private static String hex(byte[] bytes) { StringBuilder out = new StringBuilder(); for (byte value : bytes) out.append(String.format(java.util.Locale.ROOT, "%02x", value & 255)); return out.toString(); }
}
