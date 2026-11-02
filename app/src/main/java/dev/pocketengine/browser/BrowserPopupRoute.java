package dev.pocketengine.browser;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

/** Native callback ownership for one manual popup. Page text never creates or approves a route. */
public final class BrowserPopupRoute implements AutoCloseable {
    @FunctionalInterface public interface Check {void check()throws Exception;}
    @FunctionalInterface public interface Reply {void deliver()throws Exception;}
    private final JSONObject parent;private final Check current;private final AtomicBoolean transport=new AtomicBoolean(),closed=new AtomicBoolean();private final Set<String> admitted=java.util.concurrent.ConcurrentHashMap.newKeySet();
    public BrowserPopupRoute(JSONObject nativeParent,Check current)throws Exception {
        this.parent=new JSONObject(nativeParent.toString());this.current=current;for(String key:new String[]{"tabId","documentId","browserSessionId"})uuid(parent.getString(key));BrowserFrameOrigins.exact(parent.getString("sourceOrigin"));if(parent.getLong("navigation")<0)throw new SecurityException("弹窗原页面版本无效");current.check();
    }
    public void reply(Reply actualNativeTransport)throws Exception {requireCurrent();if(!transport.compareAndSet(false,true))throw new SecurityException("弹窗native callback已消费");actualNativeTransport.deliver();}
    /** Null-completion is cleanup of this exact native callback, even after its parent was revoked. */
    public boolean cancelReply(Reply cancelNativeTransport)throws Exception {if(!transport.compareAndSet(false,true))return false;cancelNativeTransport.deliver();return true;}
    public void requireCurrent()throws Exception {if(closed.get())throw new SecurityException("弹窗已关闭");current.check();}
    public boolean admitted(String url)throws Exception {requireCurrent();return admitted.contains(origin(url));}
    public Request request(String actualUrl)throws Exception {requireCurrent();String origin=origin(actualUrl);if(actualUrl.length()>16384)throw new SecurityException("弹窗地址超出明确确认预算");return new Request(actualUrl,origin);}
    public final class Request {
        private final String url,origin,id=UUID.randomUUID().toString();private final AtomicBoolean used=new AtomicBoolean();
        private Request(String url,String origin){this.url=url;this.origin=origin;}
        public JSONObject preview()throws Exception {requireCurrent();return new JSONObject(parent.toString()).put("popupRequestId",id).put("destinationOrigin",origin).put("sourceOriginCoverage","native_callback_parent_top_document_subframe_origin_unavailable").put("queryOmitted",new java.net.URI(url).getRawQuery()!=null).put("automaticApproval",false);}
        public String approveForOwner()throws Exception {requireCurrent();if(!used.compareAndSet(false,true))throw new SecurityException("弹窗目的地确认已消费");if(admitted.size()>=4&&!admitted.contains(origin))throw new SecurityException("这个弹窗已达4个已确认来源上限");admitted.add(origin);requireCurrent();return url;}
        public boolean rejectForOwner(){return used.compareAndSet(false,true);}
    }
    private static void uuid(String value){if(value==null||!value.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))throw new SecurityException("弹窗原生身份无效");}
    private static String origin(String value)throws Exception{java.net.URI uri=new java.net.URI(value);if(uri.getRawUserInfo()!=null||uri.getHost()==null)throw new SecurityException("弹窗目的地包含凭据或无有效主机");return BrowserFrameOrigins.exact(new java.net.URI(uri.getScheme(),null,uri.getHost(),uri.getPort(),null,null,null).toASCIIString());}
    @Override public void close(){closed.set(true);admitted.clear();}
}
