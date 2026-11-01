package dev.pocketengine.features.channels;

import dev.pocketengine.core.RunControl;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.Locale;
import org.json.JSONObject;

/** Bounded credential-free native channel-media GET; callers retain original platform authority. */
final class GroupInboundFiles {
    private final boolean loopbackFixture;
    GroupInboundFiles(){loopbackFixture=false;}
    GroupInboundFiles(boolean loopbackFixture){this.loopbackFixture=loopbackFixture;}
    /** Official iLink CDN only. This narrows the shared QQ downloader before any connection opens. */
    String weixinAddress(String address)throws Exception {
        if(address==null||address.length()>8192)throw new SecurityException("微信图片地址超出原协议范围");URI uri=new URI(address);String host=uri.getHost();
        boolean official="https".equals(uri.getScheme())&&"novac2c.cdn.weixin.qq.com".equalsIgnoreCase(host)&&(uri.getPort()==-1||uri.getPort()==443)&&uri.getRawPath()!=null&&uri.getRawPath().equals("/c2c/download");
        boolean fixture=loopbackFixture&&"http".equals(uri.getScheme())&&"127.0.0.1".equals(host);
        if(uri.getUserInfo()!=null||uri.getFragment()!=null||!official&&!fixture)throw new SecurityException("仅支持微信官方原图片CDN；未跟随其他地址或转发账号凭据");return uri.toASCIIString();
    }
    Download open(JSONObject nativeAttachment,RunControl control)throws Exception {return open(nativeAttachment,control,false);}
    /** Same bounded transport with ambient account cookies/HTTP authorization explicitly blank. */
    Download openWeixin(JSONObject nativeAttachment,RunControl control)throws Exception {weixinAddress(nativeAttachment.getString("url"));return open(nativeAttachment,control,true);}
    private Download open(JSONObject nativeAttachment,RunControl control,boolean credentialFree)throws Exception {
        String address=nativeAttachment.getString("url");if(address.startsWith("//"))address="https:"+address;URI uri=new URI(address);String host=uri.getHost()==null?"":uri.getHost().toLowerCase(Locale.ROOT);
        boolean qq=host.equals("qq.com")||host.endsWith(".qq.com")||host.equals("qpic.cn")||host.endsWith(".qpic.cn")||host.equals("gtimg.cn")||host.endsWith(".gtimg.cn");
        if(uri.getUserInfo()!=null||uri.getFragment()!=null||!(uri.getScheme().equals("https")&&qq||loopbackFixture&&uri.getScheme().equals("http")&&host.equals("127.0.0.1")))throw new SecurityException("群附件地址不属于已支持的QQ官方媒体域名");
        long declared=nativeAttachment.getLong("bytes");if(declared<0||declared>20*1024*1024L)throw new IllegalArgumentException("群附件超过大小限制");
        control.check();HttpURLConnection connection=(HttpURLConnection)uri.toURL().openConnection();connection.setRequestMethod("GET");connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(10_000);connection.setReadTimeout(20_000);if(credentialFree){connection.setRequestProperty("Cookie","");connection.setRequestProperty("Authorization","");}Runnable cancel=connection::disconnect;control.onCancel(cancel);
        try {control.check();if(connection.getResponseCode()!=200)throw new IllegalStateException("原QQ群附件下载未成功；未跟随重定向或转发账号凭据");long length=connection.getContentLengthLong();if(length>20*1024*1024L||declared>0&&length>=0&&declared!=length)throw new SecurityException("群附件大小与平台原记录不符");
            InputStream source=connection.getInputStream();InputStream guarded=new FilterInputStream(source){long count;private void check(int n)throws IOException {try{control.check();}catch(InterruptedException revoked){throw new IOException("群附件权限已失效",revoked);}if(n>0){count+=n;if(count>20*1024*1024L||declared>0&&count>declared)throw new IOException("群附件超出原平台大小");}if(n<0&&declared>0&&count!=declared)throw new IOException("群附件未完整返回");}
                @Override public int read()throws IOException {check(0);int value=super.read();check(value<0?-1:1);return value;}
                @Override public int read(byte[] data,int offset,int size)throws IOException {check(0);int read=in.read(data,offset,size);check(read);return read;}};
            return new Download(connection,guarded,control,cancel);
        }catch(Exception failed){control.removeCancelAction(cancel);connection.disconnect();throw failed;}
    }
    static final class Download implements AutoCloseable {final InputStream input;private final HttpURLConnection connection;private final RunControl control;private final Runnable cancel;Download(HttpURLConnection connection,InputStream input,RunControl control,Runnable cancel){this.connection=connection;this.input=input;this.control=control;this.cancel=cancel;}public void close()throws IOException {try{input.close();}finally{control.removeCancelAction(cancel);connection.disconnect();}}}
}
