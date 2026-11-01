package dev.pocketengine.features.local.project;

import dev.pocketengine.features.workspace.VersionedWorkspace;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.TreeMap;
import org.json.JSONArray;
import org.json.JSONObject;

/** Source identities refer to actual active-tree bytes, never checkout metadata alone. */
public final class ProjectSources {
    public static final int MAX_FILES=2000,MAX_FILE_BYTES=2*1024*1024,MAX_TOTAL_BYTES=16*1024*1024;
    private ProjectSources() {}
    public static Map<String,String> files(JSONObject snapshot)throws Exception {
        Map<String,byte[]> all=byteFiles(snapshot);Map<String,String> text=new TreeMap<>();JSONArray input=snapshot.getJSONArray("files");
        for(int i=0;i<input.length();i++){JSONObject file=input.getJSONObject(i);if(!file.optBoolean("binary"))text.put(file.getString("path"),StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(all.get(file.getString("path")))).toString());}return text;
    }
    public static Map<String,byte[]> byteFiles(JSONObject snapshot)throws Exception {
        JSONArray input=snapshot.getJSONArray("files");if(input.length()>MAX_FILES)throw new IllegalArgumentException("项目源码清单超过2000个文件");Map<String,byte[]> files=new TreeMap<>();long total=0;
        for(int i=0;i<input.length();i++){JSONObject file=input.getJSONObject(i);String path=VersionedWorkspace.path(file.getString("path"));byte[] bytes;
            if(file.optBoolean("binary")){String encoded=file.getString("contentBase64");if(encoded.length()>((MAX_FILE_BYTES+2)/3)*4)throw new IllegalArgumentException("源码资源超过2MiB");bytes=java.util.Base64.getDecoder().decode(encoded);}
            else{String content=file.getString("content");if(content.indexOf('\0')>=0)throw new IllegalArgumentException("文本源码包含空字节");java.nio.ByteBuffer encoded=StandardCharsets.UTF_8.newEncoder().encode(java.nio.CharBuffer.wrap(content));bytes=new byte[encoded.remaining()];encoded.get(bytes);}
            total+=bytes.length;if(bytes.length>MAX_FILE_BYTES||total>MAX_TOTAL_BYTES||files.put(path,bytes)!=null)throw new IllegalArgumentException("项目源码字节/路径超过清单范围");
            if(file.has("sha256")&&!sha256(bytes).equals(file.getString("sha256")))throw new SecurityException("源码摘要与实际字节不一致");
        }
        for(String path:files.keySet())for(int at=path.indexOf('/');at>=0;at=path.indexOf('/',at+1))if(files.containsKey(path.substring(0,at)))throw new IllegalArgumentException("文件与目录冲突");return files;
    }
    /** Dependency caches and build results remain in the active tree, outside the source revision. */
    public static boolean sourcePath(String path){String[] parts=path.split("/");if(parts.length>0&&java.util.Set.of("build","dist").contains(parts[0]))return false;for(String part:parts)if(java.util.Set.of(".git","node_modules",".venv","venv","__pycache__",".gradle",".pocketworld",".pytest_cache").contains(part))return false;return true;}
    public static JSONObject describe(JSONObject snapshot)throws Exception {
        JSONArray manifest=new JSONArray();int total=0;
        for(Map.Entry<String,byte[]> file:byteFiles(snapshot).entrySet()){
            byte[] bytes=file.getValue();total+=bytes.length;
            manifest.put(new JSONObject().put("path",file.getKey()).put("sha256",sha256(bytes)).put("bytes",bytes.length));
        }
        JSONObject checkout=snapshot.getJSONObject("checkout");
        return new JSONObject().put("checkoutId",checkout.getString("id")).put("projectId",checkout.getString("projectId"))
                .put("files",manifest).put("sourceDigest",manifestDigest(manifest)).put("bytes",total).put("fileCount",manifest.length());
    }
    public static String manifestDigest(JSONArray manifest)throws Exception {
        if(manifest.length()>MAX_FILES)throw new IllegalArgumentException("源码清单最多2000个文件");
        Map<String,JSONObject> sorted=new TreeMap<>();long total=0;
        for(int i=0;i<manifest.length();i++){
            JSONObject file=manifest.getJSONObject(i);String path=VersionedWorkspace.path(file.getString("path"));
            long bytes=file.getLong("bytes");total+=bytes;
            if(!file.getString("sha256").matches("[a-f0-9]{64}")||bytes<0||bytes>MAX_FILE_BYTES||total>MAX_TOTAL_BYTES||sorted.put(path,file)!=null)
                throw new IllegalArgumentException("源码清单无效");
        }
        for(String path:sorted.keySet())for(int at=path.indexOf('/');at>=0;at=path.indexOf('/',at+1))if(sorted.containsKey(path.substring(0,at)))throw new IllegalArgumentException("源码文件与目录冲突");
        JSONArray values=new JSONArray();for(Map.Entry<String,JSONObject> file:sorted.entrySet())
            values.put(new JSONArray().put(file.getKey()).put(file.getValue().getString("sha256")).put(file.getValue().getLong("bytes")));
        return sha256(("pocketworld.project-source.v1\n"+values).getBytes(StandardCharsets.UTF_8));
    }
    public static String sha256(byte[] bytes)throws Exception {
        StringBuilder out=new StringBuilder();for(byte value:MessageDigest.getInstance("SHA-256").digest(bytes))
            out.append(String.format(java.util.Locale.ROOT,"%02x",value&255));return out.toString();
    }
    public static String canonical(Object value)throws Exception {
        if(value instanceof JSONObject){
            JSONObject object=(JSONObject)value;java.util.TreeSet<String> keys=new java.util.TreeSet<>();object.keys().forEachRemaining(keys::add);
            StringBuilder out=new StringBuilder("{");for(String key:keys){if(out.length()>1)out.append(',');out.append(JSONObject.quote(key)).append(':').append(canonical(object.get(key)));}return out.append('}').toString();
        }
        if(value instanceof JSONArray){JSONArray array=(JSONArray)value;StringBuilder out=new StringBuilder("[");for(int i=0;i<array.length();i++){if(i>0)out.append(',');out.append(canonical(array.get(i)));}return out.append(']').toString();}
        if(value==null||value==JSONObject.NULL)return "null";
        if(value instanceof String)return JSONObject.quote((String)value);
        if(value instanceof Boolean||value instanceof Number)return value.toString();
        throw new IllegalArgumentException("不支持的计划数据");
    }
    public static String digest(JSONObject value)throws Exception{return sha256(canonical(value).getBytes(StandardCharsets.UTF_8));}
    public static JSONObject copy(JSONObject value)throws Exception{return new JSONObject(value.toString());}
    public static String quote(String value){return "'"+value.replace("'","'\\''")+"'";}
    /** Exact bounded UTF-8 revision staging through the existing Termux stdin transport. */
    public static String stageScript(JSONObject snapshot,String directory,String parent)throws Exception {
        StringBuilder script=new StringBuilder("set -eu\nmkdir -p "+quote(parent)+"\nmkdir "+quote(directory)+"\ncd "+quote(directory)+"\n");
        for(Map.Entry<String,byte[]> file:byteFiles(snapshot).entrySet()){
            String path=file.getKey();int slash=path.lastIndexOf('/');if(slash>0)script.append("mkdir -p ").append(quote(path.substring(0,slash))).append('\n');
            String encoded=java.util.Base64.getEncoder().encodeToString(file.getValue());
            script.append("printf '%s' ").append(quote(encoded)).append(" | base64 -d > ").append(quote(path)).append('\n');
        }
        if(script.toString().getBytes(StandardCharsets.UTF_8).length>196608)throw new IllegalArgumentException("Termux一次传入项目最多约144KiB内容，请拆分项目");return script.toString();
    }
    /** A pre-exec check of manifest files. Same-UID edits after the check are not fenced. */
    public static String launchGuard(JSONArray manifest,String digest,String executable,JSONArray argv)throws Exception {
        if(!manifestDigest(manifest).equals(digest))throw new SecurityException("准备回执的源码清单不匹配");
        StringBuilder script=new StringBuilder("set -eu\nsource_fail() { printf '%s\\n' 'POCKETWORLD_SOURCE_MISMATCH' >&2; exit 73; }\n");
        for(int i=0;i<manifest.length();i++){
            JSONObject file=manifest.getJSONObject(i);String path=VersionedWorkspace.path(file.getString("path"));String prefix="";
            for(String part:path.split("/")){prefix=prefix.isEmpty()?part:prefix+"/"+part;script.append("[ ! -L ").append(quote("./"+prefix)).append(" ] || source_fail\n");}
            script.append("[ -f ").append(quote("./"+path)).append(" ] || source_fail\n")
                    .append("actual=$(sha256sum < ").append(quote("./"+path)).append(") || source_fail\n")
                    .append("[ \"${actual%% *}\" = ").append(quote(file.getString("sha256"))).append(" ] || source_fail\n");
        }
        script.append("printf '%s\\n' ").append(quote(marker(digest))).append("\nexec ").append(quote(executable));
        for(int i=0;i<argv.length();i++){String arg=argv.getString(i);if(arg.indexOf('\0')>=0)throw new IllegalArgumentException("参数无效");script.append(' ').append(quote(arg));}
        return script.append('\n').toString();
    }
    public static String marker(String digest){return "POCKETWORLD_SOURCE_V1:"+digest;}
}
