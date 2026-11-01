package dev.pocketengine.features.local.runtime;

import dev.pocketengine.features.local.project.ProjectSources;
import java.net.URI;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Release-controlled APK data. This class does not admit catalog JSON from feature/model requests. */
public final class AndroidRuntimeCatalog {
    public final String identity,abi,profileId;
    private final JSONObject value;
    public AndroidRuntimeCatalog(JSONObject apkDescriptor) throws Exception {
        value=ProjectSources.copy(apkDescriptor);
        if(!"pocketworld.android-runtime.v1".equals(value.getString("schema"))
                ||!"arm64-v8a".equals(value.getString("abi"))||!"glibc".equals(value.getString("libc"))
                ||!value.getString("profileId").matches("[A-Za-z0-9_-]{1,80}")
                ||value.getString("revision").isEmpty()||value.getString("distribution").isEmpty())
            throw new IllegalArgumentException("android_runtime_catalog_invalid");
        abi=value.getString("abi");profileId=value.getString("profileId");
        JSONObject engine=value.getJSONObject("engine");
        if(!"libproot.so".equals(engine.getString("prootLibrary"))||!"libloader.so".equals(engine.getString("loaderLibrary")))
            throw new SecurityException("android_runtime_packaged_engine_required");
        hash(engine.getString("prootSha256"));hash(engine.getString("loaderSha256"));
        archive(value.getJSONObject("rootfs"),false);archive(value.getJSONObject("node"),true);
        JSONArray packages=value.getJSONArray("systemPackages");Set<String> packageNames=new HashSet<>();
        for(int i=0;i<packages.length();i++) {
            String name=packages.getString(i);
            if(!name.matches("[a-z0-9][a-z0-9+.-]*(?:=[A-Za-z0-9.+:~_-]+)?")||!packageNames.add(name.split("=",2)[0]))
                throw new IllegalArgumentException("android_runtime_system_package_invalid");
        }
        if(!packageNames.containsAll(Set.of("ca-certificates","git","python3","python3-venv","python3-pip")))
            throw new IllegalArgumentException("android_runtime_required_system_packages_missing");
        String apt=value.getString("aptSources");java.util.Map<String,String> aptFields=new java.util.HashMap<>();
        for(String line:apt.split("\\R")) {if(line.isBlank())continue;int colon=line.indexOf(':');if(colon<1||aptFields.put(line.substring(0,colon),line.substring(colon+1).trim())!=null)throw new SecurityException("android_runtime_signed_ubuntu_sources_required");}
        if(!aptFields.keySet().equals(Set.of("Types","URIs","Suites","Components","Signed-By"))
                ||!"deb".equals(aptFields.get("Types"))
                ||!Set.of("http://ports.ubuntu.com/ubuntu-ports/","https://ports.ubuntu.com/ubuntu-ports/").contains(aptFields.get("URIs"))
                ||!"noble noble-updates noble-security".equals(aptFields.get("Suites"))||!"main universe".equals(aptFields.get("Components"))
                ||!"/usr/share/keyrings/ubuntu-archive-keyring.gpg".equals(aptFields.get("Signed-By")))
            throw new SecurityException("android_runtime_signed_ubuntu_sources_required");
        JSONArray dns=value.getJSONArray("dnsServers");if(dns.length()<1||dns.length()>4)throw new IllegalArgumentException("android_runtime_dns_missing");
        for(int i=0;i<dns.length();i++)if(!dns.getString(i).matches("[0-9a-fA-F:.]{3,64}"))throw new IllegalArgumentException("android_runtime_dns_invalid");
        JSONArray tools=value.getJSONArray("tools");Set<String> names=new HashSet<>();
        for(int i=0;i<tools.length();i++) {
            JSONObject tool=tools.getJSONObject(i);String name=tool.getString("name"),path=tool.getString("path");
            if(!name.matches("[A-Za-z0-9_-]{1,80}")||!names.add(name)||!path.startsWith("/")||path.contains("..")
                    ||path.contains("\\")||path.indexOf('\0')>=0||tool.getString("license").isEmpty())
                throw new IllegalArgumentException("android_runtime_guest_tool_invalid");
            JSONArray args=tool.getJSONArray("versionArguments");if(args.length()>8)throw new IllegalArgumentException("android_runtime_probe_invalid");
            for(int j=0;j<args.length();j++)if(args.getString(j).indexOf('\0')>=0)throw new IllegalArgumentException("android_runtime_probe_invalid");
        }
        if(!names.containsAll(Set.of("sh","node","npm","python3","git")))throw new IllegalArgumentException("android_runtime_required_tools_missing");
        if(value.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>131072)throw new IllegalArgumentException("android_runtime_catalog_too_large");
        identity=ProjectSources.digest(value);
    }
    private static void hash(String value) {if(!value.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("android_runtime_unpinned_artifact");}
    private static void archive(JSONObject source,boolean node) throws Exception {
        URI uri=new URI(source.getString("url"));
        if(!"https".equals(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null||uri.getFragment()!=null
                ||node&&!"nodejs.org".equals(uri.getHost())||!node&&!"cdimage.ubuntu.com".equals(uri.getHost()))
            throw new SecurityException("android_runtime_official_artifact_required");
        hash(source.getString("archiveSha256"));
        if(source.getLong("archiveBytes")<1||source.getLong("archiveBytes")>536870912L
                ||source.getLong("unpackedBytes")<1||source.getLong("unpackedBytes")>2147483648L
                ||source.getInt("stripComponents")!=(node?1:0)||!Set.of("tar.gz","tar.xz").contains(source.getString("format")))
            throw new IllegalArgumentException("android_runtime_archive_budget_invalid");
    }
    public JSONObject descriptor() throws Exception {return ProjectSources.copy(value);}
    public JSONObject preparationEffects() throws Exception {
        JSONArray sources=new JSONArray().put(value.getJSONObject("rootfs")).put(value.getJSONObject("node"));
        return new JSONObject().put("catalogIdentity",identity).put("abi",abi).put("guestLibc","glibc")
                .put("archives",new JSONArray(sources.toString())).put("signedSystemPackageSources",value.getString("aptSources"))
                .put("systemPackages",new JSONArray(value.getJSONArray("systemPackages").toString()))
                .put("networkMayBeUsed",true).put("projectFilesRetained",true).put("sameUidNotSandbox",true);
    }
}
