package dev.pocketengine.media;

import java.net.URI;
import java.util.Locale;

/** Public, native-owner-reviewed service coordinates. It contains no authentication or model key. */
public final class RealtimeEndpointProfile {
    public final String endpointUrl,serverUrl,agentName;
    public final long revision;
    public RealtimeEndpointProfile(String endpointUrl,String serverUrl,String agentName,long revision)throws Exception{
        this.endpointUrl=checked(endpointUrl,"https");this.serverUrl=checked(serverUrl,"wss");
        if(agentName==null||!agentName.matches("[A-Za-z0-9_.-]{1,120}")||revision<1)throw new IllegalArgumentException("请填写真实部署的agent名称");
        this.agentName=agentName;this.revision=revision;
    }
    public static String origin(String url)throws Exception{URI value=new URI(url);return value.getScheme().toLowerCase(Locale.ROOT)+"://"+value.getHost().toLowerCase(Locale.ROOT)+":"+(value.getPort()<0?443:value.getPort());}
    private static String checked(String text,String scheme)throws Exception{
        if(text==null||text.isBlank()||text.length()>2048)throw new IllegalArgumentException("服务地址无效");
        URI uri=new URI(text.trim());if(!scheme.equalsIgnoreCase(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null||uri.getRawQuery()!=null||uri.getFragment()!=null||uri.getPort()==0||uri.getPort()>65535)throw new IllegalArgumentException("认证端点须用HTTPS、房间须用WSS，地址不能携带凭据、查询参数或片段");
        return uri.toString();
    }
    @Override public String toString(){return "RealtimeEndpointProfile[revision="+revision+"]";}
}
