package dev.pocketengine.media;

import dev.pocketengine.core.RunControl;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** Bounded HTTP exchanges. No redirects, implicit retries or provider fallback. Confirmed result GETs are a separate bounded native path. */
public final class MediaTransport {
    public static final int MAX_IMAGE_INPUT_CHARS=8000,MAX_SPEECH_INPUT_CHARS=4000;
    public static final int MAX_MEDIA_BYTES = 20 * 1024 * 1024;
    public static final int MAX_JSON_BYTES = 512 * 1024;
    private MediaTransport() {}

    public static final class Rejected extends Exception {
        public final int status;
        /** A 5xx, redirect, conflict, or timeout cannot prove that generation was not accepted. */
        public final boolean definitive;
        public Rejected(int status) {
            super("媒体服务返回HTTP " + status + "，未自动重试");
            this.status = status;
            definitive = status == 400 || status == 401 || status == 403 || status == 404
                    || status == 405 || status == 413 || status == 415 || status == 422 || status == 429;
        }
    }

    public static final class Payload {
        public final byte[] bytes;
        public final String mimeType, extension;
        private Payload(byte[] bytes, String mime, String extension) {
            this.bytes = bytes;
            this.mimeType = mime;
            this.extension = extension;
        }
    }

    /** Native image bytes are issued by MediaModule after checking actual private Book inputs.
     * IDs, URLs and base64 supplied by a model cannot construct this value. */
    public static final class EditInput implements AutoCloseable {
        private final Payload image,mask;private final JSONObject references;private final InputCheck current;
        private EditInput(Payload image,Payload mask,JSONObject references,InputCheck current)throws Exception{this.image=image;this.mask=mask;this.references=new JSONObject(references.toString());this.current=current;}
        public JSONObject references()throws Exception{return new JSONObject(references.toString());}
        public void requireCurrent(RunControl control)throws Exception{control.check();current.check(control);control.check();}
        @Override public void close(){java.util.Arrays.fill(image.bytes,(byte)0);if(mask!=null)java.util.Arrays.fill(mask.bytes,(byte)0);}
    }
    interface InputCheck {void check(RunControl control)throws Exception;}
    static EditInput editInput(Payload image,Payload mask,JSONObject references,InputCheck check)throws Exception{return new EditInput(image,mask,references,check);}
    public static final class ProviderResponse {
        public final Payload payload;public final String resultUrl,responseDigest;
        private ProviderResponse(Payload payload,String resultUrl,String digest){this.payload=payload;this.resultUrl=resultUrl;responseDigest=digest;}
    }
    public static ProviderResponse response(byte[] bytes,String kind)throws Exception {
        String digest=dev.pocketengine.features.local.project.ProjectSources.sha256(bytes);
        if(kind.equals("speech"))return new ProviderResponse(speechPayload(bytes),"",digest);
        JSONObject reply=new JSONObject(new String(bytes,StandardCharsets.UTF_8));if(reply.getJSONArray("data").length()!=1)throw new IllegalStateException("服务没有返回单个原图像");JSONObject first=reply.getJSONArray("data").getJSONObject(0);String encoded=first.optString("b64_json"),url=first.optString("url");
        if(!encoded.isEmpty()){if(encoded.length()>((MAX_MEDIA_BYTES+2L)/3)*4)throw new IllegalStateException("图像超过20MiB");return new ProviderResponse(imagePayload(java.util.Base64.getDecoder().decode(encoded)),"",digest);}
        if(url.isEmpty())throw new IllegalStateException("服务没有返回图像字节或结果地址");if(url.length()>16384||url.chars().anyMatch(c->c<=32||c==127))throw new SecurityException("原产物URL无效");new URI(url);return new ProviderResponse(null,url,digest);
    }
    public static ProviderResponse requestResponse(String base,String key,String kind,String model,String voice,String text,boolean legacy,RunControl control)throws Exception {
        JSONObject body=compatibleBody(kind,model,voice,text,legacy);return response(exchange(endpoint(base,kind),key,"POST",body,kind.equals("image")?30*1024*1024:MAX_MEDIA_BYTES,control),kind);
    }
    /** Official /images/edits multipart image[] + optional mask; never reads a caller file URI. */
    public static ProviderResponse editResponse(String base,String key,String model,String prompt,boolean legacy,EditInput original,RunControl control)throws Exception {
        if(original==null)throw new SecurityException("缺少原生核对的图像字节");JSONObject fields=compatibleBody("image",model,"",prompt,legacy);String boundary="pw-image-"+java.util.UUID.randomUUID();ByteArrayOutputStream body=new ByteArrayOutputStream();
        for(var keys=fields.keys();keys.hasNext();){String field=keys.next();part(body,boundary,field,null,null,String.valueOf(fields.get(field)).getBytes(StandardCharsets.UTF_8));}
        part(body,boundary,"image[]","source."+original.image.extension,original.image.mimeType,original.image.bytes);if(original.mask!=null)part(body,boundary,"mask","mask.png","image/png",original.mask.bytes);body.write(("--"+boundary+"--\r\n").getBytes(StandardCharsets.US_ASCII));
        byte[] bytes=body.toByteArray();if(bytes.length>10*1024*1024)throw new IllegalArgumentException("原图与mask请求超过10MiB");URI generated=endpoint(base,"image"),edit=new URI(generated.getScheme(),null,generated.getHost(),generated.getPort(),generated.getPath().replaceFirst("/generations$","/edits"),null,null);
        original.requireCurrent(control);try{return response(exchangeBytes(edit,key,"multipart/form-data; boundary="+boundary,bytes,30*1024*1024,control),"image");}finally{java.util.Arrays.fill(bytes,(byte)0);original.requireCurrent(control);}
    }
    private static void part(ByteArrayOutputStream output,String boundary,String field,String filename,String mime,byte[] bytes)throws Exception{
        String header="--"+boundary+"\r\nContent-Disposition: form-data; name=\""+field+"\""+(filename==null?"":"; filename=\""+filename+"\"")+"\r\n"+(mime==null?"":"Content-Type: "+mime+"\r\n")+"\r\n";output.write(header.getBytes(StandardCharsets.US_ASCII));output.write(bytes);output.write("\r\n".getBytes(StandardCharsets.US_ASCII));
    }
    public static URI base(String raw) throws Exception {
        URI result = new URI(raw == null ? "" : raw.trim());
        String path = result.getPath();
        if (result.getHost() == null || result.getUserInfo() != null || result.getRawQuery() != null
                || result.getFragment() != null || !("https".equalsIgnoreCase(result.getScheme())
                || "http".equalsIgnoreCase(result.getScheme())) || result.getPort() > 65535
                || result.getPort() == 0 || path != null && (path.contains("\\")
                || !result.normalize().getPath().equals(path))) {
            throw new IllegalArgumentException("媒体服务需有效的http/https API基础地址");
        }
        if (path != null && path.matches(".*/(?:chat/completions|images/generations|audio/speech)/?")) {
            throw new IllegalArgumentException("请填写API基础地址，而不是单个接口地址");
        }
        return result;
    }

    public static URI endpoint(String raw, String kind) throws Exception {
        if (!kind.equals("image") && !kind.equals("speech")) throw new IllegalArgumentException("不支持的媒体类型");
        URI root = base(raw);
        String path = root.getPath() == null ? "" : root.getPath().replaceAll("/+$", "");
        if (path.isEmpty()) path = "/v1";
        return new URI(root.getScheme(), null, root.getHost(), root.getPort(),
                path + (kind.equals("image") ? "/images/generations" : "/audio/speech"), null, null);
    }

    /** Append a fixed provider route under an explicitly configured reverse-proxy root. */
    public static URI route(String raw, String fixedPath, String query) throws Exception {
        URI root = base(raw);
        if (!fixedPath.matches("/[A-Za-z0-9_./-]+") || fixedPath.contains("..")) {
            throw new IllegalArgumentException("媒体服务路由无效");
        }
        String path = root.getPath() == null ? "" : root.getPath().replaceAll("/+$", "");
        URI target = new URI(root.getScheme(), null, root.getHost(), root.getPort(), path + fixedPath, null, null);
        return query == null ? target : new URI(target.toASCIIString() + "?" + query);
    }

    public static boolean sameOrigin(String a, String b) throws Exception {
        URI x = base(a), y = base(b);
        return x.getScheme().equalsIgnoreCase(y.getScheme()) && x.getHost().equalsIgnoreCase(y.getHost())
                && port(x) == port(y);
    }
    private static int port(URI uri) { return uri.getPort() < 0 ? (uri.getScheme().equalsIgnoreCase("https") ? 443 : 80) : uri.getPort(); }

    public static JSONObject compatibleBody(String kind, String model, String voice, String text, boolean legacyImage) throws Exception {
        if (!kind.equals("image") && !kind.equals("speech")) throw new IllegalArgumentException("不支持的媒体类型");
        if (model == null || model.trim().isEmpty()) throw new IllegalArgumentException("请先配置对应的媒体模型");
        if (text == null || text.trim().isEmpty() || text.length() > (kind.equals("speech") ? MAX_SPEECH_INPUT_CHARS : MAX_IMAGE_INPUT_CHARS)) {
            throw new IllegalArgumentException("媒体输入为空或超过长度限制");
        }
        JSONObject body = new JSONObject().put("model", model);
        if (kind.equals("image")) {
            body.put("prompt", text).put("n", 1);
            if (legacyImage) body.put("response_format", "b64_json"); else body.put("output_format", "png");
        } else {
            if (voice == null || voice.trim().isEmpty()) throw new IllegalArgumentException("请先配置语音voice标识");
            body.put("input", text).put("voice", voice).put("response_format", "wav").put("stream", false);
        }
        return body;
    }

    public static Payload request(String base, String key, String kind, String model, String voice,
            String text, boolean legacyImage, RunControl control) throws Exception {
        JSONObject body = compatibleBody(kind, model, voice, text, legacyImage);
        byte[] bytes = exchange(endpoint(base, kind), key, "POST", body,
                kind.equals("image") ? 30 * 1024 * 1024 : MAX_MEDIA_BYTES, control);
        if (kind.equals("speech")) return speechPayload(bytes);
        JSONObject reply = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        if (reply.getJSONArray("data").length() != 1) throw new IllegalStateException("图像服务未返回请求的单个图像");
        String encoded = reply.getJSONArray("data").getJSONObject(0).optString("b64_json");
        if (encoded.isEmpty()) throw new IllegalStateException("图像服务没有返回内联图像；未自动抓取外部URL或重试请求");
        if (encoded.length() > ((MAX_MEDIA_BYTES + 2L) / 3) * 4) throw new IllegalStateException("图像超过附件上限");
        return imagePayload(java.util.Base64.getDecoder().decode(encoded));
    }

    public static byte[] exchange(URI endpoint, String key, String method, JSONObject body, int limit, RunControl control) throws Exception {
        if (control == null) throw new IllegalArgumentException("需要实时执行控制");
        if (!method.equals("GET") && !method.equals("POST")) throw new IllegalArgumentException("媒体HTTP方法无效");
        if (key != null && (key.length() > 8192 || key.chars().anyMatch(c -> c < 32 || c == 127))) throw new IllegalArgumentException("媒体凭据格式无效");
        byte[] outgoing = body == null ? null : body.toString().getBytes(StandardCharsets.UTF_8);
        if (outgoing != null && outgoing.length > MAX_JSON_BYTES) throw new IllegalArgumentException("媒体请求超过512KiB上限");
        return exchangeRaw(endpoint,key,method,outgoing,"application/json; charset=utf-8",limit,control);
    }
    private static byte[] exchangeBytes(URI endpoint,String key,String type,byte[] outgoing,int limit,RunControl control)throws Exception{
        if(key!=null&&(key.length()>8192||key.chars().anyMatch(c->c<32||c==127)))throw new IllegalArgumentException("媒体凭据格式无效");return exchangeRaw(endpoint,key,"POST",outgoing,type,limit,control);
    }
    private static byte[] exchangeRaw(URI endpoint,String key,String method,byte[] outgoing,String contentType,int limit,RunControl control)throws Exception {
        control.check();
        HttpURLConnection connection = (HttpURLConnection) endpoint.toURL().openConnection();
        connection.setRequestMethod(method);
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(120000);
        connection.setRequestProperty("Accept-Encoding", "identity");
        if (key != null && !key.isEmpty()) connection.setRequestProperty("Authorization", "Bearer " + key);
        Runnable disconnect = connection::disconnect;
        control.onCancel(disconnect);
        long deadline = System.nanoTime() + 120_000_000_000L;
        try {
            control.check();
            if (outgoing != null) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", contentType);
                connection.setFixedLengthStreamingMode(outgoing.length);
                try (OutputStream output = connection.getOutputStream()) { for(int offset=0;offset<outgoing.length;offset+=32768){control.check();output.write(outgoing,offset,Math.min(32768,outgoing.length-offset));} }
            }
            control.check();
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) throw new Rejected(status);
            long declared = connection.getContentLengthLong();
            if (declared > limit) throw new IllegalStateException("媒体服务返回超过大小上限");
            String encoding = connection.getHeaderField("Content-Encoding");
            if (encoding != null && !encoding.equalsIgnoreCase("identity")) throw new IllegalStateException("媒体服务返回了未请求的压缩编码");
            try (InputStream input = connection.getInputStream(); ByteArrayOutputStream result = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[32768];
                for (int n; (n = input.read(buffer)) != -1;) {
                    control.check();
                    if (System.nanoTime() >= deadline) throw new java.net.SocketTimeoutException("媒体服务超过总时限");
                    if (result.size() + (long) n > limit) throw new IllegalStateException("媒体服务返回超过大小上限");
                    result.write(buffer, 0, n);
                }
                control.check();
                if (System.nanoTime() >= deadline) throw new java.net.SocketTimeoutException("媒体服务超过总时限");
                if (declared >= 0 && declared != result.size()) throw new java.io.EOFException("媒体响应在声明长度之前中断");
                return result.toByteArray();
            }
        } finally {
            control.removeCancelAction(disconnect);
            connection.disconnect();
        }
    }

    public static Payload imagePayload(byte[] bytes) throws Exception {
        if (bytes.length > MAX_MEDIA_BYTES) throw new IllegalStateException("图像超过附件上限");
        if (bytes.length >= 8 && (bytes[0] & 255) == 137 && ascii(bytes, 1, "PNG\r\n\u001a\n")) return new Payload(bytes, "image/png", "png");
        if (bytes.length >= 3 && (bytes[0] & 255) == 255 && (bytes[1] & 255) == 216 && (bytes[2] & 255) == 255) return new Payload(bytes, "image/jpeg", "jpg");
        if (bytes.length >= 12 && ascii(bytes, 0, "RIFF") && ascii(bytes, 8, "WEBP")) return new Payload(bytes, "image/webp", "webp");
        throw new IllegalStateException("媒体服务返回的内容不是支持的图像格式");
    }
    public static Payload speechPayload(byte[] bytes) throws Exception {
        if (bytes.length > MAX_MEDIA_BYTES) throw new IllegalStateException("音频超过附件上限");
        if (bytes.length < 44 || !ascii(bytes, 0, "RIFF") || !ascii(bytes, 8, "WAVE")) throw new IllegalStateException("语音服务未返回请求的WAV音频");
        return new Payload(bytes, "audio/wav", "wav");
    }
    public static Payload outputPayload(byte[] bytes) throws Exception {
        if(bytes.length>=12&&ascii(bytes,4,"ftyp")){if(bytes.length>MAX_MEDIA_BYTES)throw new IllegalStateException("视频超过20MiB");return new Payload(bytes,"video/mp4","mp4");}
        return bytes.length >= 12 && ascii(bytes, 0, "RIFF") && ascii(bytes, 8, "WAVE") ? speechPayload(bytes) : imagePayload(bytes);
    }
    private static boolean ascii(byte[] bytes, int offset, String value) {
        if (bytes.length < offset + value.length()) return false;
        for (int i = 0; i < value.length(); i++) if ((bytes[offset + i] & 255) != value.charAt(i)) return false;
        return true;
    }
}
