package dev.pocketengine.features.plugins.distribution;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.EngineModule;
import dev.pocketengine.features.plugins.PluginRevisions;
import dev.pocketengine.features.plugins.PluginRules;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Portable declaration bytes over the existing plugin authority; no network, code or second registry. */
public final class PluginPackages {
    public static final int MAX_BYTES=262144;
    public static final String FORMAT="pocketworld.plugin-declaration.v1";
    private final EngineModule plugins;
    public PluginPackages(EngineModule plugins){if(plugins==null||!"plugins".equals(plugins.id()))throw new IllegalArgumentException("需要现有插件模块");this.plugins=plugins;}
    public static final class Inspection {
        public final String packageDigest,declarationDigest,reviewDigest;
        private final String reviewJson,manifestJson;
        private final boolean mcp;
        private Inspection(Package data,JSONObject preview)throws Exception {
            packageDigest=data.rawDigest;declarationDigest=data.declarationDigest;manifestJson=PackageJson.canonical(data.manifest);mcp=hasMcp(data.manifest);
            JSONObject review=new JSONObject().put("format",FORMAT).put("packageDigest",packageDigest).put("declarationDigest",declarationDigest).put("bytes",data.bytes)
                    .put("source",data.source).put("preview",preview).put("requiresMcpRediscovery",mcp).put("signature","not_present").put("untrusted",true)
                    .put("containsHostCredentials",false).put("containsHostGrants",false).put("installed",false);
            reviewDigest=hash(PackageJson.canonical(review).getBytes(StandardCharsets.UTF_8));reviewJson=review.put("reviewDigest",reviewDigest).toString();
        }
        public JSONObject review()throws Exception{return new JSONObject(reviewJson);}
    }
    public Inspection inspect(InputStream input,RunControl control)throws Exception {
        Package data=read(input,control);synchronized(plugins){control.check();return new Inspection(data,preview(data.manifest));}
    }
    /** Native page freshness check; file bytes are separately reopened and revalidated at import. */
    public String validateInspection(Inspection inspected,RunControl control)throws Exception {
        if(inspected==null)throw new IllegalArgumentException("缺少已检查的声明包");synchronized(plugins){control.check();
            if(!PackageJson.canonical(preview(new JSONObject(inspected.manifestJson))).equals(PackageJson.canonical(inspected.review().getJSONObject("preview"))))throw new IllegalStateException("当前安装或差异已变化，请重新检查");return inspected.reviewDigest;
        }
    }
    /** Native owner action only, after displaying that exact Inspection; not a model/feature command. */
    public JSONObject importReviewed(InputStream input,Inspection inspected,String acceptedReviewDigest,RunControl control)throws Exception {
        if(inspected==null||!inspected.reviewDigest.equals(acceptedReviewDigest))throw new SecurityException("请先审阅并批准当前插件包差异");
        Package incoming=read(input,control);if(!inspected.packageDigest.equals(incoming.rawDigest)||!inspected.declarationDigest.equals(incoming.declarationDigest))throw new SecurityException("插件文件已变化，请重新检查");
        synchronized(plugins){control.check();Inspection fresh=new Inspection(incoming,preview(incoming.manifest));if(!fresh.reviewDigest.equals(inspected.reviewDigest))throw new IllegalStateException("当前安装或差异已变化，请重新检查");
            if(inspected.mcp)return new JSONObject().put("status","rediscovery_required").put("installed",false).put("packageDigest",inspected.packageDigest)
                    .put("discovery",discovery(incoming.manifest)).put("requiresNewApproval",true).put("replayAllowed",false)
                    .put("message","MCP声明仅作比较；请重新发现当前服务、认证并审阅，未安装或启用包内声明");
            JSONObject payload=new JSONObject().put("manifest",new JSONObject(inspected.manifestJson)).put("expectedInstallationId",fresh.review().getJSONObject("preview").getString("expectedInstallationId"));
            control.check();
            JSONObject receipt;
            try{receipt=plugins.handle("install",payload);}catch(Exception uncertain){
                // The existing module may have committed before its credential/event cleanup failed.
                return unknown(inspected);
            }
            try{if(!validReceipt(receipt,incoming.manifest,payload.getString("expectedInstallationId")))return unknown(inspected);}catch(Exception invalidReceipt){return unknown(inspected);}
            return new JSONObject().put("status","installed_disabled").put("installed",true).put("packageDigest",inspected.packageDigest).put("declarationDigest",inspected.declarationDigest)
                    .put("plugin",new JSONObject().put("id",receipt.getString("id")).put("installationId",receipt.getString("installationId"))
                            .put("manifest",PluginRules.manifest(receipt.getJSONObject("manifest"))).put("enabled",false).put("grants",new JSONArray()).put("hasToken",false))
                    .put("credentialsRestored",false).put("grantsRestored",false).put("replayAllowed",false);
        }
    }
    /** Snapshot exact current installation; the owner-selected stream must still flush/close successfully. */
    public JSONObject exportPackage(String id,String expectedInstallationId,JSONObject source,OutputStream output,RunControl control)throws Exception {
        byte[] bytes;String digest;JSONObject manifest;
        synchronized(plugins){control.check();JSONObject current=current(id);if(current==null||expectedInstallationId==null||!expectedInstallationId.equals(PluginRevisions.installationId(current)))throw new IllegalStateException("插件安装已变化，请刷新后导出");
            manifest=portableManifest(PluginRules.manifest(current.getJSONObject("manifest")));digest=declarationDigest(manifest);JSONObject provenance=source(source);
            bytes=PackageJson.canonical(new JSONObject().put("format",FORMAT).put("manifestSha256",digest).put("manifest",manifest).put("source",provenance)).getBytes(StandardCharsets.UTF_8);
            if(bytes.length>MAX_BYTES)throw new IllegalArgumentException("声明包超过256KiB");
        }
        for(int offset=0;offset<bytes.length;offset+=4096){control.check();output.write(bytes,offset,Math.min(4096,bytes.length-offset));}control.check();
        return new JSONObject().put("fileName",manifest.getString("id")+".pwp.json").put("mediaType","application/json").put("bytes",bytes.length).put("packageDigest",hash(bytes)).put("declarationDigest",digest)
                .put("signature","not_present").put("containsHostCredentials",false).put("containsHostGrants",false).put("containsInstallationIdentity",false).put("requiresDestinationClose",true);
    }
    private JSONObject preview(JSONObject manifest)throws Exception {
        JSONObject current=current(manifest.getString("id"));return hasMcp(manifest)?PluginRevisions.preview(current,manifest):plugins.handle("previewInstall",new JSONObject().put("manifest",manifest));
    }
    private JSONObject current(String id)throws Exception {JSONArray all=plugins.snapshot().getJSONArray("plugins");for(int i=0;i<all.length();i++){JSONObject row=all.getJSONObject(i);if(id.equals(row.getString("id")))return row;}return null;}
    private static boolean hasMcp(JSONObject manifest)throws Exception {JSONArray tools=manifest.getJSONArray("tools");for(int i=0;i<tools.length();i++)if("mcp".equals(tools.getJSONObject(i).getString("kind")))return true;return false;}
    private static boolean validReceipt(JSONObject receipt,JSONObject manifest,String previous)throws Exception {
        if(receipt==null||!manifest.getString("id").equals(receipt.optString("id"))||receipt.optString("installationId").isEmpty()||previous.equals(receipt.optString("installationId"))||!Boolean.FALSE.equals(receipt.opt("enabled"))||!Boolean.FALSE.equals(receipt.opt("hasToken"))||receipt.optJSONArray("grants")==null||receipt.getJSONArray("grants").length()!=0)return false;
        return declarationDigest(manifest).equals(declarationDigest(portableManifest(receipt.getJSONObject("manifest"))));
    }
    private static JSONObject unknown(Inspection inspected)throws Exception {return new JSONObject().put("status","unknown").put("installed",JSONObject.NULL).put("packageDigest",inspected.packageDigest).put("replayAllowed",false).put("message","安装结果尚未确认，请刷新当前安装身份和声明；不会自动再次导入");}
    private static JSONArray discovery(JSONObject manifest)throws Exception {
        JSONArray requests=new JSONArray();Set<String> seen=new java.util.HashSet<>();JSONArray tools=manifest.getJSONArray("tools");
        for(int i=0;i<tools.length();i++){JSONObject tool=tools.getJSONObject(i);if(!"mcp".equals(tool.getString("kind")))continue;String key=tool.getString("endpoint")+"\n"+tool.getString("protocolVersion");if(seen.add(key))requests.put(new JSONObject().put("id",manifest.getString("id")).put("name",manifest.getString("name")).put("endpoint",tool.getString("endpoint")).put("protocolVersion",tool.getString("protocolVersion")).put("untrusted",true));}return requests;
    }
    private static final class Package {
        final JSONObject manifest,source;final String rawDigest,declarationDigest;final int bytes;
        Package(JSONObject manifest,JSONObject source,byte[] raw)throws Exception{this.manifest=manifest;this.source=source;this.rawDigest=hash(raw);this.declarationDigest=declarationDigest(manifest);this.bytes=raw.length;}
    }
    private static Package read(InputStream input,RunControl control)throws Exception {
        if(input==null||control==null)throw new IllegalArgumentException("需要主人选择的文件流与取消控制");ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[4096];int count;
        while(true){control.check();count=input.read(buffer,0,Math.min(buffer.length,MAX_BYTES+1-out.size()));if(count<0)break;if(count==0)throw new IllegalArgumentException("文件流未提供数据");out.write(buffer,0,count);if(out.size()>MAX_BYTES)throw new IllegalArgumentException("声明包超过256KiB");}
        control.check();byte[] raw=out.toByteArray();String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString();JSONObject pkg=PackageJson.parse(text);
        fields(pkg,Set.of("format","manifestSha256","manifest","source"));if(!FORMAT.equals(pkg.opt("format")))throw new IllegalArgumentException("不支持此插件包版本");
        JSONObject manifest=portableManifest(pkg.getJSONObject("manifest"));String expected=pkg.getString("manifestSha256");if(!expected.matches("[a-f0-9]{64}")||!expected.equals(declarationDigest(manifest)))throw new SecurityException("声明内容摘要不匹配");return new Package(manifest,source(pkg.getJSONObject("source")),raw);
    }
    private static JSONObject source(JSONObject value)throws Exception {
        if(value==null)throw new IllegalArgumentException("需要包来源说明");fields(value,Set.of("label","url"));Object label=value.opt("label");if(!(label instanceof String)||((String)label).isBlank()||((String)label).length()>240)throw new IllegalArgumentException("来源说明无效");PackageJson.unicode((String)label);
        JSONObject result=new JSONObject().put("label",label);if(value.has("url")){String url=value.getString("url");PluginRules.validateEndpoint(url);if(!url.startsWith("https://"))throw new IllegalArgumentException("来源链接仅支持HTTPS");result.put("url",url);}return result;
    }
    private static JSONObject portableManifest(JSONObject value)throws Exception {
        fields(value,Set.of("id","name","version","tools"));JSONArray tools=value.getJSONArray("tools");
        for(int i=0;i<tools.length();i++){JSONObject tool=tools.getJSONObject(i);String kind=tool.getString("kind");Set<String> allowed=new java.util.HashSet<>(Set.of("name","kind","description","effect","schema"));if(kind.equals("http")||kind.equals("mcp"))allowed.add("endpoint");if(kind.equals("mcp"))allowed.addAll(Set.of("protocolVersion","descriptorDigest","serverDigest","annotations","outputSchema"));fields(tool,allowed);if(tool.has("description")&&!(tool.get("description") instanceof String))throw new IllegalArgumentException("工具描述需为文字");}
        JSONObject normalized=PluginRules.manifest(value);for(int i=0;i<tools.length();i++){JSONObject original=tools.getJSONObject(i);if(original.has("schema")&&!PackageJson.canonical(original.getJSONObject("schema")).equals(PackageJson.canonical(normalized.getJSONArray("tools").getJSONObject(i).getJSONObject("schema"))))throw new IllegalArgumentException("参数定义无法无损保存");}
        return normalized;
    }
    private static void fields(JSONObject object,Set<String> allowed){java.util.Iterator<String> names=object.keys();while(names.hasNext())if(!allowed.contains(names.next()))throw new IllegalArgumentException("包内含非声明字段；权限、凭据、路径、签名或代码载荷不能导入");}
    static String declarationDigest(JSONObject manifest)throws Exception{return hash(PackageJson.canonical(manifest).getBytes(StandardCharsets.UTF_8));}
    static String hash(byte[] bytes)throws Exception {StringBuilder out=new StringBuilder();for(byte value:MessageDigest.getInstance("SHA-256").digest(bytes))out.append(String.format(java.util.Locale.ROOT,"%02x",value&255));return out.toString();}
}
