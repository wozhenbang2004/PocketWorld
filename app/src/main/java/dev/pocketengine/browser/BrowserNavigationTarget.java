package dev.pocketengine.browser;

import java.net.URI;
import java.util.Set;

/** Native-only parsed destination. Never serialize this value into task events or UI snapshots. */
final class BrowserNavigationTarget {
    final String exactUrl,displayUrl;
    final boolean parameters;
    private BrowserNavigationTarget(String exact,String display,boolean parameters){exactUrl=exact;displayUrl=display;this.parameters=parameters;}
    static BrowserNavigationTarget parse(String raw){
        try{
            if(raw==null||raw.length()>8192)return null;
            URI uri=new URI(raw);
            if(!Set.of("http","https").contains(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null)return null;
            String exact=uri.toASCIIString(),display=new URI(uri.getScheme()+"://"+uri.getRawAuthority()+(uri.getRawPath()==null?"":uri.getRawPath())).toASCIIString();
            if(exact.length()>8192||display.length()>2048)return null;
            return new BrowserNavigationTarget(exact,display,uri.getRawQuery()!=null||uri.getRawFragment()!=null);
        }catch(Exception invalid){return null;}
    }
    String title(String raw,String title){
        // Query/fragment data can be decoded, segmented or rewritten into an untrusted title.
        // Do not guess which pieces are secret: persist only the already-redacted address.
        if(parameters)return displayUrl;
        String value=title==null?"":title;
        if(raw!=null&&!raw.isEmpty()&&(raw.startsWith(value)||value.contains(raw)||value.contains(exactUrl)))return displayUrl;
        return value;
    }
}
