package dev.pocketengine.features.teams;

import java.net.URI;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Data-only exact native review descriptor. Parsing it never issues a read grant. */
public final class TeamResearchReadScope {
    private TeamResearchReadScope(){}
    public static JSONObject empty()throws Exception{return new JSONObject().put("profileId","anonymous_public").put("webOrigins",new JSONArray()).put("projectFiles",new JSONArray());}
    public static JSONObject checked(JSONObject raw)throws Exception {
        if(raw==null)return empty();if(raw.length()!=3||!"anonymous_public".equals(raw.optString("profileId"))||!raw.has("webOrigins")||!raw.has("projectFiles"))throw new SecurityException("研究读取仅接匿名公共网页和准确项目文件");
        JSONArray origins=raw.getJSONArray("webOrigins"),files=raw.getJSONArray("projectFiles");if(origins.length()>4||files.length()>32)throw new IllegalArgumentException("最多4个明确origin和32个准确文件");Set<String> seen=new java.util.HashSet<>();for(int i=0;i<origins.length();i++){String value=origins.getString(i);if(!origin(value).equals(value)||!seen.add(value))throw new IllegalArgumentException("网页范围必须是唯一规范HTTPS origin");}
        seen.clear();for(int i=0;i<files.length();i++){JSONObject file=files.getJSONObject(i);Set<String> keys=new java.util.HashSet<>();file.keys().forEachRemaining(keys::add);if(!keys.equals(Set.of("projectId","path","version","revisionId","sha256","sizeBytes"))||!file.getString("projectId").matches("[a-f0-9-]{36}")||!file.getString("revisionId").matches("[a-f0-9-]{36}")||!file.getString("sha256").matches("[a-f0-9]{64}")||!(file.opt("version") instanceof Integer)||file.getInt("version")<1||file.getLong("sizeBytes")<0||file.getLong("sizeBytes")>2*1024*1024)throw new SecurityException("项目读取缺少准确原修订与字节身份");path(file.getString("path"));if(!seen.add(file.getString("projectId")+"\0"+file.getString("path")))throw new SecurityException("项目文件不能重复");}
        return new JSONObject(raw.toString());
    }
    public static String origin(String raw)throws Exception {URI uri=new URI(raw);String host=uri.getHost();if(!"https".equalsIgnoreCase(uri.getScheme())||host==null||uri.getUserInfo()!=null||uri.getFragment()!=null||host.equalsIgnoreCase("localhost")||host.endsWith(".localhost")||host.contains(":")||host.matches("[0-9.]+"))throw new SecurityException("仅允许无凭据的明确公共HTTPS站点");return "https://"+host.toLowerCase(java.util.Locale.ROOT)+(uri.getPort()==-1||uri.getPort()==443?"":":"+uri.getPort());}
    public static boolean allows(JSONObject scope,String url)throws Exception {String origin=origin(url);JSONArray origins=checked(scope).getJSONArray("webOrigins");for(int i=0;i<origins.length();i++)if(origin.equals(origins.getString(i)))return true;return false;}
    public static boolean covers(JSONObject receiver,JSONObject producer)throws Exception {JSONObject need=checked(producer),has=checked(receiver);for(String key:new String[]{"webOrigins","projectFiles"}){JSONArray a=need.getJSONArray(key),b=has.getJSONArray(key);for(int i=0;i<a.length();i++){boolean found=false;for(int n=0;n<b.length();n++)if(key.equals("webOrigins")?a.getString(i).equals(b.getString(n)):sameFile(a.getJSONObject(i),b.getJSONObject(n)))found=true;if(!found)return false;}}return true;}
    private static boolean sameFile(JSONObject a,JSONObject b)throws Exception {for(String key:new String[]{"projectId","path","version","revisionId","sha256","sizeBytes"})if(!a.get(key).equals(b.get(key)))return false;return true;}
    public static void path(String value){if(value==null||value.isEmpty()||value.length()>240||value.startsWith("/")||value.contains("\\")||value.contains("\0"))throw new IllegalArgumentException("需项目相对路径");for(String part:value.split("/",-1))if(part.isEmpty()||part.equals(".")||part.equals(".."))throw new IllegalArgumentException("项目路径不能跳出显选范围");}
}
