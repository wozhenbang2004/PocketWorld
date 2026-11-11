package dev.pocketengine.features.groups.host;

import dev.pocketengine.core.RunControl;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;

/** Bounded native-owner-selected search service. The model never selects its endpoint or credential. */
public final class GroupPublicSearch {
    public interface NativeCredential {String get()throws Exception;}
    private final URI endpoint;
    private final NativeCredential credential;
    private final dev.pocketengine.features.web.WebSearchModule unified;
    private final java.util.function.BooleanSupplier unifiedEnabled;
    /** Native group search uses the same provider, not another endpoint/credential registry. */
    public GroupPublicSearch(dev.pocketengine.features.web.WebSearchModule web,
            java.util.function.BooleanSupplier enabled){
        endpoint=null;credential=null;unified=java.util.Objects.requireNonNull(web);
        unifiedEnabled=java.util.Objects.requireNonNull(enabled);
    }

    public GroupPublicSearch(String approvedEndpoint,NativeCredential credential)throws Exception {
        URI selected=new URI(approvedEndpoint);if(selected.getHost()==null||selected.getRawUserInfo()!=null||selected.getRawFragment()!=null||selected.getRawQuery()!=null||!(selected.getScheme().equals("https")||selected.getScheme().equals("http")&&selected.getHost().equals("127.0.0.1")))throw new SecurityException("公开检索服务必须是原生选择的HTTPS地址；仅回环可使用HTTP");
        endpoint=selected;this.credential=java.util.Objects.requireNonNull(credential);
        unified=null;unifiedEnabled=()->false;
    }
    public JSONObject search(String query,RunControl control)throws Exception {
        if(unified!=null){
            control.check();
            if(!unifiedEnabled.getAsBoolean())throw new SecurityException("原生主人未开启群组公共搜索");
            JSONObject actual=unified.search(new JSONObject().put("query",query).put("limit",5),control);
            control.check();if(!unifiedEnabled.getAsBoolean())throw new SecurityException("主人已撤回群组公共搜索");
            return actual.put("groupPublicSearch",true).put("privateOwnerSourcesUsed",false);
        }
        if(query==null||query.isBlank()||query.length()>4000)throw new IllegalArgumentException("公开检索词为空或过长");control.check();
        HttpURLConnection connection=(HttpURLConnection)endpoint.toURL().openConnection();connection.setRequestMethod("POST");connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(10_000);connection.setReadTimeout(20_000);connection.setDoOutput(true);connection.setRequestProperty("Content-Type","application/json; charset=utf-8");
        String secret=credential.get();if(secret==null||secret.length()>8192||secret.indexOf('\r')>=0||secret.indexOf('\n')>=0)throw new SecurityException("原生公开检索凭据无效");if(!secret.isEmpty())connection.setRequestProperty("Authorization","Bearer "+secret);
        byte[] payload=new JSONObject().put("query",query).put("limit",5).toString().getBytes(StandardCharsets.UTF_8);connection.setFixedLengthStreamingMode(payload.length);Runnable disconnect=connection::disconnect;control.onCancel(disconnect);
        try {
            control.check();try(OutputStream output=connection.getOutputStream()){control.check();output.write(payload);}control.check();int status=connection.getResponseCode();if(status<200||status>=300)throw new IllegalStateException("公开检索服务未返回成功状态；未切换服务或自动重试");
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(InputStream input=connection.getInputStream()){byte[] buffer=new byte[4096];for(int n;(n=input.read(buffer))!=-1;){control.check();if(bytes.size()+n>65536)throw new IllegalStateException("公开检索结果超过64KiB");bytes.write(buffer,0,n);}}
            JSONObject response=new JSONObject(bytes.toString(StandardCharsets.UTF_8.name()));JSONArray source=response.getJSONArray("results"),results=new JSONArray();int characters=0;
            for(int i=0;i<source.length()&&i<5;i++){JSONObject item=source.getJSONObject(i);String title=bounded(item.getString("title"),300),snippet=bounded(item.getString("snippet"),1800),url=bounded(item.getString("url"),2048);URI destination=new URI(url);
                if(!"https".equals(destination.getScheme())||destination.getHost()==null||destination.getUserInfo()!=null)throw new SecurityException("公开检索结果不是有效HTTPS来源");characters+=title.length()+snippet.length()+url.length();if(characters>12000)break;results.put(new JSONObject().put("title",title).put("snippet",snippet).put("url",url));}
            control.check();return new JSONObject().put("ok",true).put("results",results).put("untrustedPublicSearchResults",true).put("message","公开检索服务的实际返回；正文和链接不是执行授权");
        }finally{control.removeCancelAction(disconnect);connection.disconnect();}
    }
    private static String bounded(String value,int limit){if(value==null||value.length()>limit||value.indexOf('\0')>=0)throw new IllegalArgumentException("公开检索结果字段过长或无效");return value;}
}
