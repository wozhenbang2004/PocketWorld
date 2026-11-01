package dev.pocketengine.features.plugins.mcp;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** OAuth discovery data has no authority to redirect credentials or select another installation. */
public final class McpOAuthMetadata {
    private McpOAuthMetadata() { }
    public static final class Challenge {
        public final String metadataUrl,scope;
        private Challenge(String metadataUrl,String scope){this.metadataUrl=metadataUrl;this.scope=scope;}
    }
    public static Challenge challenge(List<String> headers)throws Exception {
        List<String> segments=new ArrayList<>();int bytes=0;
        for(String header:headers){
            bytes+=header.length();if(bytes>16384||header.indexOf('\r')>=0||header.indexOf('\n')>=0)throw new IllegalArgumentException("Invalid authorization challenge");
            StringBuilder part=new StringBuilder();boolean quoted=false,escape=false;
            for(int i=0;i<header.length();i++){
                char c=header.charAt(i);if(c==','&&!quoted){segments.add(part.toString().trim());part.setLength(0);continue;}
                part.append(c);if(escape)escape=false;else if(c=='\\'&&quoted)escape=true;else if(c=='"')quoted=!quoted;
            }
            if(quoted||escape)throw new IllegalArgumentException("Unclosed authorization challenge");segments.add(part.toString().trim());
        }
        boolean bearer=false;int bearerCount=0;Map<String,String> params=new LinkedHashMap<>();
        for(String part:segments){
            if(part.isEmpty())continue;int at=0;while(at<part.length()&&token(part.charAt(at)))at++;
            if(at==0)throw new IllegalArgumentException("Invalid authorization challenge");
            String first=part.substring(0,at);int rest=at;while(rest<part.length()&&Character.isWhitespace(part.charAt(rest)))rest++;
            if(rest==part.length()||part.charAt(rest)!='='){
                bearer=first.equalsIgnoreCase("Bearer");if(bearer&&++bearerCount>1)throw new IllegalArgumentException("Ambiguous Bearer challenges");
                part=part.substring(rest);if(part.isEmpty())continue;
            }
            if(!bearer)continue;
            int equal=part.indexOf('=');if(equal<1)throw new IllegalArgumentException("Invalid Bearer parameter");
            String name=part.substring(0,equal).trim().toLowerCase(Locale.ROOT),raw=part.substring(equal+1).trim();
            if(!name.chars().allMatch(c->token((char)c))||params.containsKey(name))throw new IllegalArgumentException("Duplicate Bearer parameter");
            String value;
            if(raw.startsWith("\"")){
                if(raw.length()<2||!raw.endsWith("\""))throw new IllegalArgumentException("Invalid Bearer quoted value");
                StringBuilder decoded=new StringBuilder();for(int i=1;i<raw.length()-1;i++){
                    char c=raw.charAt(i);if(c=='\\'){if(++i>=raw.length()-1)throw new IllegalArgumentException("Invalid Bearer escape");c=raw.charAt(i);}
                    else if(c=='"')throw new IllegalArgumentException("Invalid Bearer quoted value");
                    if(c<32||c==127)throw new IllegalArgumentException("Invalid Bearer value");decoded.append(c);
                }value=decoded.toString();
            }else {if(raw.isEmpty()||!raw.chars().allMatch(c->token((char)c)))throw new IllegalArgumentException("Invalid Bearer value");value=raw;}
            params.put(name,value);
        }
        String url=params.getOrDefault("resource_metadata","");if(!url.isEmpty())https(url);
        return new Challenge(url,scopes(params.getOrDefault("scope","")));
    }
    private static boolean token(char c){return c<128&&(Character.isLetterOrDigit(c)||"!#$%&'*+-.^_`|~".indexOf(c)>=0);}
    public static List<String> resourceCandidates(String resource,Challenge challenge)throws Exception {
        URI uri=https(resource);List<String> out=new ArrayList<>();
        if(!challenge.metadataUrl.isEmpty()){out.add(challenge.metadataUrl);return out;}
        String root=uri.getScheme()+"://"+uri.getRawAuthority(),path=uri.getRawPath();
        if(path!=null&&!path.isEmpty()&&!path.equals("/"))out.add(root+"/.well-known/oauth-protected-resource"+path);
        out.add(root+"/.well-known/oauth-protected-resource");return out;
    }
    public static List<String> issuerCandidates(String issuer)throws Exception {
        URI uri=https(issuer);List<String> out=new ArrayList<>();String root=uri.getScheme()+"://"+uri.getRawAuthority(),path=uri.getRawPath();
        boolean hasPath=path!=null&&!path.isEmpty()&&!path.equals("/");
        out.add(root+"/.well-known/oauth-authorization-server"+(hasPath?path:""));
        out.add(root+"/.well-known/openid-configuration"+(hasPath?path:""));
        if(hasPath)out.add((issuer.endsWith("/")?issuer.substring(0,issuer.length()-1):issuer)+"/.well-known/openid-configuration");return out;
    }
    /** Deliberately excludes query-bearing endpoints and local/private literals in this bounded subset. */
    public static URI https(String value)throws Exception {
        URI uri=new URI(value);String host=uri.getHost();
        if(value.length()>2000||!"https".equals(uri.getScheme())||host==null||uri.getRawUserInfo()!=null||uri.getRawQuery()!=null||uri.getRawFragment()!=null||uri.getPort()==0||uri.getPort()>65535)
            throw new SecurityException("OAuth requires an uncredentialed HTTPS endpoint");
        String h=host.toLowerCase(Locale.ROOT);
        if(!h.equals(host)||h.endsWith(".")||h.equals("localhost")||h.endsWith(".localhost")||h.endsWith(".local")||h.endsWith(".internal")||!h.contains(".")||h.matches("[0-9.]+")||h.contains(":"))
            throw new SecurityException("OAuth endpoint is not a public DNS host");
        return uri;
    }
    public static List<String> strings(JSONObject source,String key,boolean required)throws Exception {
        JSONArray array=source.optJSONArray(key);if(array==null){if(required||source.has(key))throw new IllegalArgumentException("Missing or invalid metadata list");return java.util.Collections.emptyList();}
        if(array.length()>64||required&&array.length()==0)throw new IllegalArgumentException("Invalid metadata list");
        List<String> out=new ArrayList<>();for(int i=0;i<array.length();i++){
            Object item=array.get(i);if(!(item instanceof String)||((String)item).isEmpty()||((String)item).length()>2000||out.contains(item))throw new IllegalArgumentException("Invalid metadata list entry");out.add((String)item);
        }return out;
    }
    public static String scopes(String value){
        if(value.length()>4096)throw new IllegalArgumentException("Scope limit");List<String> parts=new ArrayList<>();
        if(!value.isEmpty())for(String part:value.split(" ",-1)){
            if(part.isEmpty()||!part.chars().allMatch(c->c==0x21||c>=0x23&&c<=0x5b||c>=0x5d&&c<=0x7e)||parts.contains(part))throw new IllegalArgumentException("Invalid OAuth scope");parts.add(part);
        }
        return String.join(" ",parts);
    }
}
