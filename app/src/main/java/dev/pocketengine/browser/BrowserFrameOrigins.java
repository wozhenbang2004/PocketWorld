package dev.pocketengine.browser;

import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import org.json.JSONArray;

/** Native owner's finite extra embedded origins for one original research plan. Not a credential or action grant. */
public final class BrowserFrameOrigins {
    public static final int MAX_EXTRA_ORIGINS=8;
    private BrowserFrameOrigins() { }
    public static final class Consent {
        private final Set<String> origins;
        private Consent(Set<String> origins){this.origins=Collections.unmodifiableSet(new LinkedHashSet<>(origins));}
        public Set<String> origins(){return origins;}
        public JSONArray summary(){return new JSONArray(origins);}
    }
    public static Consent sameOriginOnly(){return new Consent(Set.of());}
    /** Only the native entry calls this with the owner's actual edit text; no tool accepts origins. */
    public static Consent forOwner(String text)throws Exception {
        if(text==null||text.length()>4200)throw new IllegalArgumentException("嵌入来源最多8个，每行填写完整origin");
        LinkedHashSet<String> allowed=new LinkedHashSet<>();
        for(String line:text.split("\\R")){String value=line.trim();if(value.isEmpty())continue;allowed.add(exact(value));if(allowed.size()>MAX_EXTRA_ORIGINS)throw new IllegalArgumentException("嵌入来源最多8个，不支持通配或自动加入其他站点");}
        return new Consent(allowed);
    }
    public static Set<String> admitted(String topOrigin,Set<String> nativeExtra)throws Exception {
        LinkedHashSet<String> admitted=new LinkedHashSet<>();admitted.add(exact(topOrigin));admitted.addAll(checkedExtras(nativeExtra));return Collections.unmodifiableSet(admitted);
    }
    static Set<String> checkedExtras(Set<String> nativeExtra)throws Exception {if(nativeExtra==null||nativeExtra.size()>MAX_EXTRA_ORIGINS)throw new SecurityException("原任务嵌入来源范围无效");LinkedHashSet<String> result=new LinkedHashSet<>();for(String origin:nativeExtra)result.add(exact(origin));return Collections.unmodifiableSet(result);}
    public static String exact(String value)throws Exception {
        if(value==null||value.length()>512||value.contains("*")||!value.equals(value.trim()))throw new IllegalArgumentException("嵌入来源需精确http/https origin，不含通配符");
        URI u=new URI(value);String scheme=u.getScheme(),host=u.getHost();
        if(host==null||scheme==null||!Set.of("http","https").contains(scheme)||u.getRawUserInfo()!=null||u.getRawQuery()!=null||u.getRawFragment()!=null||u.getRawPath()!=null&&!Set.of("","/").contains(u.getRawPath())||u.getPort()==0||u.getPort() < -1||u.getPort()>65535)throw new IllegalArgumentException("请只填来源，例如https://embedded.example，不填账号、路径、查询或片段");
        int port=u.getPort();if(scheme.equals("https")&&port==443||scheme.equals("http")&&port==80)port=-1;
        return new URI(scheme,null,host.toLowerCase(java.util.Locale.ROOT),port,null,null,null).toASCIIString();
    }
}
