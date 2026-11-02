package dev.pocketengine.browser;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.webkit.WebView;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.json.JSONObject;

/** Finite input to one actual WebView. Geometry and authorization come from its native host. */
public final class BrowserNativeInput {
    @FunctionalInterface public interface Check {void check()throws Exception;}
    private BrowserNativeInput() { }
    public static void validate(JSONObject args)throws Exception {
        String action=args.getString("action");if(!Set.of("tap","hover","drag","key").contains(action))throw new IllegalArgumentException("原生网页输入类型无效");
        for(String key:new String[]{"x","y","toX","toY"})if(args.has(key)&&(!(args.get(key) instanceof Number)||args.getDouble(key)!=args.getInt(key)||args.getInt(key)<0||args.getInt(key)>999))throw new IllegalArgumentException("落点仅接受原目标内0至999的相对坐标");
        if(args.has("x")!=args.has("y")||args.has("toX")!=args.has("toY")||!action.equals("drag")&&(args.has("toX")||args.has("toRef"))||action.equals("key")&&(args.has("x")||args.has("y"))||!action.equals("key")&&args.has("key"))throw new IllegalArgumentException("原生输入参数不适用于本动作");
        if(action.equals("key"))keyCode(args.getString("key"));
    }
    static int keyCode(String key){return switch(key){case "Enter"->KeyEvent.KEYCODE_ENTER;case "Escape"->KeyEvent.KEYCODE_ESCAPE;case "Tab"->KeyEvent.KEYCODE_TAB;case "ArrowUp"->KeyEvent.KEYCODE_DPAD_UP;case "ArrowDown"->KeyEvent.KEYCODE_DPAD_DOWN;case "ArrowLeft"->KeyEvent.KEYCODE_DPAD_LEFT;case "ArrowRight"->KeyEvent.KEYCODE_DPAD_RIGHT;case "Home"->KeyEvent.KEYCODE_MOVE_HOME;case "End"->KeyEvent.KEYCODE_MOVE_END;case "PageUp"->KeyEvent.KEYCODE_PAGE_UP;case "PageDown"->KeyEvent.KEYCODE_PAGE_DOWN;case "Backspace"->KeyEvent.KEYCODE_DEL;case "Delete"->KeyEvent.KEYCODE_FORWARD_DEL;case "Space"->KeyEvent.KEYCODE_SPACE;default->throw new IllegalArgumentException("仅接受声明的单键，不接受剪贴板或系统快捷键");};}
    public static String geometryScript(String pageKey,String url,String observation,JSONObject args,JSONObject start,JSONObject end)throws Exception {
        validate(args);String ref=args.getString("ref");if(!ref.matches("n[0-9]{1,3}"))throw new IllegalArgumentException("原生输入需准确观察ref");int destination=end==null?Integer.parseInt(ref.substring(1)):Integer.parseInt(end.getString("ref").substring(1));
        return "(()=>{const s=window["+JSONObject.quote(pageKey)+"],expectedUrl="+JSONObject.quote(url)+",observation="+JSONObject.quote(observation)+",first="+Integer.parseInt(ref.substring(1))+",last="+destination+",firstKey="+JSONObject.quote(start.getString("nodeKey"))+",lastKey="+JSONObject.quote((end==null?start:end).getString("nodeKey"))+",request="+args+";\n"+GEOMETRY+"\n})()";
    }
    private static final String GEOMETRY="""
const fail=code=>JSON.stringify({status:'unavailable',accepted:false,code});if(location.href!==expectedUrl||!s||s.id!==observation||s.dirty||s.observer.takeRecords().length)return fail('input_observation_changed');
const point=(index,key,x,y)=>{const e=s.nodes[index];if(!e||!e.isConnected||e.ownerDocument!==document||!s.nodeKeys||s.nodeKeys.get(e)!==key||e.type==='password'||e.type==='file'||e.autocomplete==='one-time-code'||e.closest('[data-private=true]'))throw Error('input_target_changed');const r=e.getBoundingClientRect(),style=getComputedStyle(e);if(!e.getClientRects().length||r.width<=0||r.height<=0||style.visibility==='hidden'||style.pointerEvents==='none')throw Error('input_target_hidden');const px=r.left+r.width*x/1000,py=r.top+r.height*y/1000;const vv=visualViewport,ox=vv?vv.offsetLeft:0,oy=vv?vv.offsetTop:0,vw=vv?vv.width:innerWidth,vh=vv?vv.height:innerHeight;if(px<ox||py<oy||px>=ox+vw||py>=oy+vh)throw Error('input_outside_viewport');const root=e.getRootNode(),hit=(root.elementFromPoint?root:document).elementFromPoint(px,py);if(!hit||!(hit===e||e.contains(hit)))throw Error('input_target_occluded');return {x:px,y:py,nodeKey:key,focused:(root.activeElement||document.activeElement)===e}};
try{const from=point(first,firstKey,request.x??500,request.y??500),to=point(last,lastKey,request.toX??500,request.toY??500);if(request.action==='key'&&!from.focused)return fail('input_target_not_focused');return JSON.stringify({status:'geometry_observed',from,to,viewport:{width:visualViewport?visualViewport.width:innerWidth,height:visualViewport?visualViewport.height:innerHeight,scrollX,scrollY,scale:visualViewport?visualViewport.scale:1,offsetX:visualViewport?visualViewport.offsetLeft:0,offsetY:visualViewport?visualViewport.offsetTop:0},documentUrl:location.href})}catch(error){return fail(String(error.message||'input_geometry_unavailable'))}
""";
    public static void dispatch(WebView view,JSONObject args,JSONObject geometry,Check current,Runnable begin,Runnable end,CompletableFuture<JSONObject> result)throws Exception {
        if(Looper.myLooper()!=Looper.getMainLooper())throw new IllegalStateException("原生网页输入只能在原主线程派发");validate(args);current.check();
        if(!geometry.optString("status").equals("geometry_observed"))throw new SecurityException("原生网页输入没有当前目标几何");
        JSONObject viewport=geometry.getJSONObject("viewport"),from=geometry.getJSONObject("from"),to=geometry.getJSONObject("to");double width=viewport.getDouble("width"),height=viewport.getDouble("height");
        if(width<=0||height<=0)throw new SecurityException("原网页视口无效");float sx=(float)(view.getWidth()/width),sy=(float)(view.getHeight()/height),x=(float)(from.getDouble("x")-viewport.getDouble("offsetX"))*sx,y=(float)(from.getDouble("y")-viewport.getDouble("offsetY"))*sy,tx=(float)(to.getDouble("x")-viewport.getDouble("offsetX"))*sx,ty=(float)(to.getDouble("y")-viewport.getDouble("offsetY"))*sy;
        if(x<0||y<0||x>=view.getWidth()||y>=view.getHeight()||tx<0||ty<0||tx>=view.getWidth()||ty>=view.getHeight())throw new SecurityException("原生网页落点超出真实视口");
        String action=args.getString("action");Handler handler=new Handler(Looper.getMainLooper());long down=SystemClock.uptimeMillis();final boolean[] entered={false},pressed={false},accepted={true};final int[] heldKey={-1};
        class Input {
            boolean motion(int event,float px,float py)throws Exception {current.check();begin.run();try{MotionEvent.PointerProperties pointer=new MotionEvent.PointerProperties();pointer.id=0;pointer.toolType=action.equals("hover")?MotionEvent.TOOL_TYPE_MOUSE:MotionEvent.TOOL_TYPE_FINGER;MotionEvent.PointerCoords coordinate=new MotionEvent.PointerCoords();coordinate.x=px;coordinate.y=py;coordinate.pressure=event==MotionEvent.ACTION_UP||event==MotionEvent.ACTION_CANCEL?0:1;coordinate.size=1;MotionEvent motion=MotionEvent.obtain(down,SystemClock.uptimeMillis(),event,1,new MotionEvent.PointerProperties[]{pointer},new MotionEvent.PointerCoords[]{coordinate},0,0,1,1,0,0,action.equals("hover")?InputDevice.SOURCE_MOUSE:InputDevice.SOURCE_TOUCHSCREEN,0);try{entered[0]=true;return action.equals("hover")?view.dispatchGenericMotionEvent(motion):view.dispatchTouchEvent(motion);}finally{motion.recycle();}}finally{end.run();}}
            void stop(Exception error){if(heldKey[0]>=0){begin.run();try{view.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP,heldKey[0]));}finally{end.run();heldKey[0]=-1;}}if(pressed[0]){begin.run();try{MotionEvent cancel=MotionEvent.obtain(down,SystemClock.uptimeMillis(),MotionEvent.ACTION_CANCEL,x,y,0);try{view.dispatchTouchEvent(cancel);}finally{cancel.recycle();}}finally{end.run();pressed[0]=false;}}try{result.complete(new JSONObject().put("status",entered[0]?"unknown":"unavailable").put("accepted",entered[0]?JSONObject.NULL:false).put("verified",false).put("code",entered[0]?"native_input_interrupted_after_entry":"native_input_not_dispatched").put("automaticReplayAllowed",false));}catch(Exception malformed){result.completeExceptionally(malformed);}}
            void complete()throws Exception {current.check();result.complete(new JSONObject().put("status","native_input_returned").put("accepted",accepted[0]).put("verified",false).put("platformDispatchAccepted",accepted[0]).put("businessOutcomeVerified",false).put("automaticReplayAllowed",false));}
            void step(int index){if(result.isDone())return;try{current.check();if(index==0){accepted[0]&=motion(MotionEvent.ACTION_DOWN,x,y);pressed[0]=true;}else if(index==9){accepted[0]&=motion(MotionEvent.ACTION_UP,tx,ty);pressed[0]=false;complete();return;}else accepted[0]&=motion(MotionEvent.ACTION_MOVE,x+(tx-x)*index/8,y+(ty-y)*index/8);handler.postDelayed(()->step(index+1),25);}catch(Exception interrupted){stop(interrupted);}}
        }
        Input input=new Input();result.whenComplete((value,error)->{if((error!=null||result.isCancelled())&&pressed[0])handler.post(()->input.stop(new SecurityException("input_cancelled")));});
        try{
            if(action.equals("drag")){input.step(0);return;}
            if(action.equals("hover")){accepted[0]=input.motion(MotionEvent.ACTION_HOVER_MOVE,x,y);input.complete();return;}
            if(action.equals("tap")){accepted[0]=input.motion(MotionEvent.ACTION_DOWN,x,y);pressed[0]=true;handler.postDelayed(()->{try{accepted[0]&=input.motion(MotionEvent.ACTION_UP,x,y);pressed[0]=false;input.complete();}catch(Exception interrupted){input.stop(interrupted);}},40);return;}
            current.check();begin.run();try{int key=keyCode(args.getString("key"));entered[0]=true;heldKey[0]=key;accepted[0]=view.dispatchKeyEvent(new KeyEvent(down,down,KeyEvent.ACTION_DOWN,key,0,0,KeyCharacterMap.VIRTUAL_KEYBOARD,0,0,InputDevice.SOURCE_KEYBOARD));current.check();accepted[0]&=view.dispatchKeyEvent(new KeyEvent(down,SystemClock.uptimeMillis(),KeyEvent.ACTION_UP,key,0,0,KeyCharacterMap.VIRTUAL_KEYBOARD,0,0,InputDevice.SOURCE_KEYBOARD));heldKey[0]=-1;}finally{end.run();}input.complete();
        }catch(Exception interrupted){input.stop(interrupted);}
    }
}
