package dev.pocketengine.browser;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

/** Validates data for a new ACTION_VIEW intent; serialized intents and file grants are never accepted. */
public final class BrowserExternalNavigation {
    private static final Set<String> FORBIDDEN = Set.of("intent", "android-app", "file", "content",
            "data", "javascript", "about", "blob", "chrome", "devtools", "http", "https", "ftp");
    public final String uri, scheme, host;
    private BrowserExternalNavigation(String uri, String scheme, String host) {
        this.uri=uri;this.scheme=scheme;this.host=host;
    }
    public static BrowserExternalNavigation parse(String value) throws Exception {
        if(value==null||value.length()>4096||value.isEmpty()||!value.equals(value.trim()))throw new SecurityException("外部地址无效");
        for(int i=0;i<value.length();i++)if(Character.isISOControl(value.charAt(i)))throw new SecurityException("外部地址含控制字符");
        URI parsed=new URI(value);String scheme=parsed.getScheme();
        if(scheme==null)throw new SecurityException("外部地址缺少协议");scheme=scheme.toLowerCase(Locale.ROOT);
        if(!scheme.matches("[a-z][a-z0-9+.-]{1,40}")||FORBIDDEN.contains(scheme)||parsed.getRawUserInfo()!=null)throw new SecurityException("此地址不能作为外部应用链接打开");
        return new BrowserExternalNavigation(value,scheme,parsed.getHost()==null?"":parsed.getHost().toLowerCase(Locale.ROOT));
    }
}
