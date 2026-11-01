package dev.pocketengine.browser;

import android.graphics.Bitmap;
import dev.pocketengine.core.NativeToolObservation;
import dev.pocketengine.core.RunControl;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** One actual Window PixelCopy viewport. It is not a DOM render, action coordinate grant, or retained attachment. */
public final class BrowserVisualCapture {
    public static final String TOOL="browser_task_observe_visual";
    public static final int MAX_PIXELS=1_000_000,MAX_BYTES=2*1024*1024;
    private BrowserVisualCapture() { }
    public static JSONObject cropSchema()throws Exception {JSONObject p=new JSONObject();for(String k:new String[]{"x","y","width","height"})p.put(k,new JSONObject().put("type","integer").put("minimum",k.equals("x")||k.equals("y")?0:1).put("maximum",20000));return new JSONObject().put("type","object").put("additionalProperties",false).put("required",new JSONArray().put("x").put("y").put("width").put("height")).put("properties",p);}
    public static NativeToolObservation.Crop crop(JSONObject supplied,int width,int height)throws Exception {
        if(width<1||height<1||width>20000||height>20000)throw new IllegalArgumentException("网页尚无可捕获的可见尺寸");if(supplied==null)return new NativeToolObservation.Crop(0,0,width,height);if(supplied.length()!=4)throw new IllegalArgumentException("网页裁剪区域不完整");for(var it=supplied.keys();it.hasNext();)if(!Set.of("x","y","width","height").contains(it.next()))throw new IllegalArgumentException("裁剪区域不接受额外坐标权限");
        for(String k:new String[]{"x","y","width","height"}){Object v=supplied.get(k);if(!(v instanceof Number)||((Number)v).doubleValue()!=supplied.getInt(k)||supplied.getInt(k)<(k.equals("x")||k.equals("y")?0:1)||supplied.getInt(k)>20000)throw new IllegalArgumentException("裁剪区域必须是有效整像素");}
        NativeToolObservation.Crop crop=new NativeToolObservation.Crop(supplied.getInt("x"),supplied.getInt("y"),supplied.getInt("width"),supplied.getInt("height"));if(crop.x()>width-crop.width()||crop.y()>height-crop.height())throw new IllegalArgumentException("截图区域超出当前网页viewport，未放大到其他窗口");return crop;
    }
    public static int[] imageSize(NativeToolObservation.Crop crop){double ratio=Math.min(1d,Math.sqrt(MAX_PIXELS/((double)crop.width()*crop.height())));int w=Math.max(1,(int)Math.floor(crop.width()*ratio)),h=Math.max(1,(int)Math.floor(crop.height()*ratio));return new int[]{w,h};}
    public static JSONObject viewport(int w,int h,int sx,int sy,double scale,JSONObject crop)throws Exception {NativeToolObservation.Crop c=crop(crop,w,h);int[] size=imageSize(c);return new JSONObject().put("widthPx",w).put("heightPx",h).put("scrollXPx",sx).put("scrollYPx",sy).put("scale",scale).put("crop",new JSONObject().put("x",c.x()).put("y",c.y()).put("width",c.width()).put("height",c.height())).put("imageWidthPx",size[0]).put("imageHeightPx",size[1]);}
    public static NativeToolObservation.Viewport viewport(JSONObject v)throws Exception {JSONObject c=v.getJSONObject("crop");return new NativeToolObservation.Viewport(v.getInt("widthPx"),v.getInt("heightPx"),v.getInt("scrollXPx"),v.getInt("scrollYPx"),v.getDouble("scale"),crop(c,v.getInt("widthPx"),v.getInt("heightPx")),v.getInt("imageWidthPx"),v.getInt("imageHeightPx"));}
    public static final class Frame implements AutoCloseable {
        public final NativeToolObservation.Identity identity;private Bitmap bitmap;private final RunControl.AuthorityCheck current;
        public Frame(NativeToolObservation.Identity identity,Bitmap actual,RunControl.AuthorityCheck current){this.identity=java.util.Objects.requireNonNull(identity);bitmap=java.util.Objects.requireNonNull(actual);this.current=java.util.Objects.requireNonNull(current);if(actual.getWidth()!=identity.viewport().imageWidthPx()||actual.getHeight()!=identity.viewport().imageHeightPx())throw new IllegalArgumentException("真实截图与声明尺寸不同");}
        public void requirePageCurrent()throws Exception {current.check();}
        public byte[] encodePng()throws Exception {current.check();Bitmap actual; synchronized(this){actual=bitmap;if(actual==null)throw new IllegalStateException("网页截图已释放");}try(PrivateBytes output=new PrivateBytes()){if(!actual.compress(Bitmap.CompressFormat.PNG,100,output))throw new IllegalStateException("网页截图编码未完成");current.check();return output.take();}}
        @Override public synchronized void close(){if(bitmap!=null){bitmap.recycle();bitmap=null;}}
    }
    private static final class PrivateBytes extends ByteArrayOutputStream {
        @Override public synchronized void write(int value){if(count>=MAX_BYTES)throw new IllegalStateException("网页图像超过2MiB预算");super.write(value);}
        @Override public synchronized void write(byte[] value,int offset,int length){if(length<0||count>MAX_BYTES-length)throw new IllegalStateException("网页图像超过2MiB预算");super.write(value,offset,length);}
        synchronized byte[] take(){byte[] result=toByteArray();Arrays.fill(buf,(byte)0);reset();return result;}
        @Override public synchronized void close(){Arrays.fill(buf,(byte)0);reset();}
    }
    /** No input values, HTML or page prose. Bounded known sensitive controls/child-frame omissions are explicit. */
    static String privacyScript(String key){return "JSON.stringify((()=>{const key="+JSONObject.quote(key)+";let s=globalThis[key];if(!s){s={revision:0,watched:new WeakSet(),rootCount:0};s.observer=new MutationObserver(()=>s.revision++);s.observer.observe(document,{subtree:true,childList:true,attributes:true,characterData:true});Object.defineProperty(globalThis,key,{value:s});}const stack=[document.documentElement],seen=new WeakSet();let privateVisible=false,frames=0,visited=0;const deadline=Date.now()+60;while(stack.length){if(++visited>2000||Date.now()>deadline)return {visualProbe:true,status:'unavailable',code:'visual_privacy_scan_budget'};const e=stack.pop();if(!e||seen.has(e)||e.nodeType!==1)continue;seen.add(e);const r=e.getBoundingClientRect();let visible=r.width>0&&r.height>0&&r.right>0&&r.bottom>0&&r.left<innerWidth&&r.top<innerHeight&&!e.closest('[hidden]');if(visible){const style=getComputedStyle(e);visible=style.display!=='none'&&style.visibility!=='hidden';}if(visible){if(e.tagName==='IFRAME'||e.tagName==='FRAME'){frames++;continue;}if(e.type==='password'||e.type==='file'||String(e.autocomplete||'').toLowerCase().split(/\\s+/).includes('one-time-code'))privateVisible=true;}let children=e.children;if(e.shadowRoot){if(!s.watched.has(e.shadowRoot)){if(s.rootCount>=128)return {visualProbe:true,status:'unavailable',code:'visual_privacy_scan_budget'};s.watched.add(e.shadowRoot);s.rootCount++;s.revision++;s.observer.observe(e.shadowRoot,{subtree:true,childList:true,attributes:true,characterData:true});e.shadowRoot.addEventListener('slotchange',()=>s.revision++,true);}children=e.shadowRoot.children;}else if(e.tagName==='SLOT'&&typeof e.assignedElements==='function'){const assigned=e.assignedElements({flatten:false});if(assigned.length)children=assigned;}if(stack.length+children.length>2000)return {visualProbe:true,status:'unavailable',code:'visual_privacy_scan_budget'};for(let i=children.length-1;i>=0;i--)stack.push(children[i]);}return {visualProbe:true,status:'observed',mutationRevision:s.revision,privateControlsVisible:privateVisible,visibleChildFrames:frames,scope:'bounded_main_document_known_controls_no_value_read',completeSecretClassification:false};})())";}
    static JSONObject privacy(JSONObject raw)throws Exception {if(!raw.optBoolean("visualProbe")||!raw.optString("status").equals("observed"))throw new IllegalStateException("当前网页未完成截图隐私检查，请人工查看");if(!(raw.get("privateControlsVisible") instanceof Boolean)||raw.getLong("mutationRevision")<0||raw.getInt("visibleChildFrames")<0||!raw.getString("scope").equals("bounded_main_document_known_controls_no_value_read")||!Boolean.FALSE.equals(raw.get("completeSecretClassification")))throw new IllegalStateException("网页截图检查回执无效");if(raw.getBoolean("privateControlsVisible"))throw new IllegalStateException("页面正在显示密码、验证码或文件输入，请先人工处理；未发送截图");if(raw.getInt("visibleChildFrames")>0)throw new IllegalStateException("可见嵌入页的敏感内容无法完整核对，未发送整页截图；可人工处理或明确打开目标原页");return new JSONObject(raw.toString());}
}
