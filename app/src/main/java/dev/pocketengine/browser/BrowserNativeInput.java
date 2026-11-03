package dev.pocketengine.browser;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.webkit.WebView;
import dev.pocketengine.core.RunControl;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.json.JSONObject;

/** Finite Android input to one original WebView. A screenshot never supplies coordinate authority. */
public final class BrowserNativeInput {
    @FunctionalInterface public interface Check {void check()throws Exception;}
    @FunctionalInterface public interface Probe {void check(Runnable ready,Consumer<Exception> stopped)throws Exception;}
    @FunctionalInterface private interface Event {boolean dispatch()throws Exception;}
    @FunctionalInterface private interface Entry {boolean dispatch(Event event)throws Exception;}
    private BrowserNativeInput() { }

    public static boolean requested(JSONObject args){return args!=null&&Set.of("tap","hover","drag","key").contains(args.optString("action"));}
    public static void validate(JSONObject args)throws Exception {
        String action=args.getString("action");if(!requested(args))throw new IllegalArgumentException("原生网页输入类型无效");
        for(String name:new String[]{"ref","toRef"})if(args.has(name)&&!args.getString(name).matches("n[0-9]{1,3}"))throw new IllegalArgumentException("原生输入仅接受本次实际展示的动作ref");
        if(!args.has("ref")||action.equals("drag")&&!args.has("toRef"))throw new IllegalArgumentException("拖动需要本次观察的起点ref与终点toRef");
        for(String name:new String[]{"x","y","toX","toY"})if(args.has(name)&&(!(args.get(name) instanceof Number)||args.getDouble(name)!=args.getInt(name)||args.getInt(name)<0||args.getInt(name)>999))throw new IllegalArgumentException("落点仅接受原目标内0至999的相对坐标");
        if(args.has("x")!=args.has("y")||args.has("toX")!=args.has("toY")||!action.equals("drag")&&(args.has("toX")||args.has("toRef"))||action.equals("key")&&args.has("x")||!action.equals("key")&&args.has("key"))throw new IllegalArgumentException("原生输入参数不适用于本动作");
        if(action.equals("key"))keyCode(args.getString("key"));
    }
    static int keyCode(String key){return switch(key){case "Enter"->KeyEvent.KEYCODE_ENTER;case "Escape"->KeyEvent.KEYCODE_ESCAPE;case "Tab"->KeyEvent.KEYCODE_TAB;case "ArrowUp"->KeyEvent.KEYCODE_DPAD_UP;case "ArrowDown"->KeyEvent.KEYCODE_DPAD_DOWN;case "ArrowLeft"->KeyEvent.KEYCODE_DPAD_LEFT;case "ArrowRight"->KeyEvent.KEYCODE_DPAD_RIGHT;case "Home"->KeyEvent.KEYCODE_MOVE_HOME;case "End"->KeyEvent.KEYCODE_MOVE_END;case "PageUp"->KeyEvent.KEYCODE_PAGE_UP;case "PageDown"->KeyEvent.KEYCODE_PAGE_DOWN;case "Backspace"->KeyEvent.KEYCODE_DEL;case "Delete"->KeyEvent.KEYCODE_FORWARD_DEL;case "Space"->KeyEvent.KEYCODE_SPACE;default->throw new IllegalArgumentException("仅接受声明的单键，不接受剪贴板或系统快捷键");};}
    public static void requireTarget(JSONObject target)throws Exception {
        if(target==null||!target.optString("nodeKey").matches("k[1-9][0-9]{0,6}")||target.optBoolean("ownerRequired"))throw new BrowserTaskSpace.StalePage("原生输入需要原普通观察中的非私密目标");
        JSONObject control=target.optJSONObject("control");if(control!=null&&(control.optBoolean("disabled")||control.optBoolean("readOnly")))throw new SecurityException("原生输入目标当前不可写，请主人处理");
    }
    /** Original native approval presentation, including relative landing points and actual view size. */
    public static String approvalSummary(JSONObject args,JSONObject pin)throws Exception {
        validate(args);JSONObject target=pin.getJSONObject("targetPreview"),view=pin.getJSONObject("nativeInputViewport");requireTarget(target);String action=args.getString("action");
        String label=switch(action){case "tap"->"在原网页点按一次";case "hover"->"将鼠标指针悬停到原控件";case "drag"->"在原网页执行一次触摸拖动";default->"向原已聚焦控件发送一次按键 "+args.getString("key");};
        StringBuilder out=new StringBuilder(label).append("\n原网页：").append(pin.getString("pageUrl")).append("\n").append(action.equals("drag")?"拖动起点：":"原目标：").append(targetDescription(target));
        if(!action.equals("key"))out.append("\n目标内相对位置：").append(args.optInt("x",500)).append(" / 1000（横向），").append(args.optInt("y",500)).append(" / 1000（纵向）");
        if(action.equals("drag")){JSONObject end=pin.getJSONObject("toTargetPreview");requireTarget(end);out.append("\n拖动终点：").append(targetDescription(end)).append("\n终点内相对位置：").append(args.optInt("toX",500)).append(" / 1000（横向），").append(args.optInt("toY",500)).append(" / 1000（纵向）");}
        out.append("\n原网页可见区域：").append(view.getInt("width")).append(" × ").append(view.getInt("height")).append(" 物理像素。坐标只相对于以上原控件，截图不提供坐标权限。");
        if(!target.optString("href").isEmpty())out.append("\n该控件链接：").append(target.getString("href"));String form=target.optString("submitterFormAction",target.optString("formAction"));if(!form.isEmpty())out.append("\n该控件表单目标：").append(form);
        return out.append("\n确认后收起面板，在同一个原网页恢复可见后只派发一次。滚动、缩放、遮挡、焦点或目标变化会拒绝旧观察；你触摸或按键接管会立即停止。随后回查原节点，回执未知时不重发，页面状态不证明服务器业务成功。").toString();
    }
    private static String targetDescription(JSONObject target){String name=target.optString("text");if(name.isBlank())name=target.optString("name");String id=target.optString("id");return (name.isBlank()?"未命名控件":"“"+name+"”（网页标注，未经核实）")+(id.isBlank()?"":" · #"+id)+" · "+target.optString("tag")+" · "+target.optString("ref");}

    /** Captured by the same ordinary observation, before any model or approval delay. */
    public static String observationStateScript(){return "state.inputViewport="+VIEWPORT+";state.inputRects=nodes.map(e=>{const r=e.getBoundingClientRect();return [r.left,r.top,r.width,r.height]});state.inputFocus=("+FOCUS+");state.inputValues=nodes.map("+EDIT_STATE+");";}
    // Native input is optional observation evidence: absent/incomplete viewport APIs never break ordinary DOM reads.
    // Null keeps geometryScript fail-closed; do not invent zoom, offsets, DPR or dimensions for a native event.
    private static final String VIEWPORT="(()=>{const v=globalThis.visualViewport;if(!v)return null;const p={width:v.width,height:v.height,layoutWidth:globalThis.innerWidth,layoutHeight:globalThis.innerHeight,dpr:globalThis.devicePixelRatio,scrollX:globalThis.scrollX,scrollY:globalThis.scrollY,scale:v.scale,offsetX:v.offsetLeft,offsetY:v.offsetTop};return Object.values(p).every(Number.isFinite)&&p.width>0&&p.height>0&&p.layoutWidth>0&&p.layoutHeight>0&&p.dpr>0&&p.scale>0?p:null})()";
    private static final String FOCUS="(()=>{let f=document.activeElement;for(let n=0;f&&f.shadowRoot&&f.shadowRoot.activeElement&&n<32;n++)f=f.shadowRoot.activeElement;return f})()";
    private static final String EDIT_STATE="(e=>{if(e.type==='password'||e.type==='file'||e.autocomplete==='one-time-code')return null;const value=e.isContentEditable?String(e.textContent||''):('value'in e?String(e.value):null);return value===null?null:[value.length<=4000?value:null,value.length,e.selectionStart??null,e.selectionEnd??null]})";
    public static String geometryScript(String pageKey,String url,String observation,JSONObject args,JSONObject start,JSONObject end,String token)throws Exception {
        validate(args);requireTarget(start);if(end!=null)requireTarget(end);
        JSONObject request=new JSONObject();for(String key:new String[]{"action","ref","toRef","x","y","toX","toY","key"})if(args.has(key))request.put(key,args.get(key));
        return "JSON.stringify((()=>{const s=window["+JSONObject.quote(pageKey)+"],expectedUrl="+JSONObject.quote(url)+",observation="+JSONObject.quote(observation)+",request="+request+",first="+start+",last="+(end==null?start:end)+",token="+JSONObject.quote(token)+",viewport=()=>"+VIEWPORT+",focus=()=>"+FOCUS+",editState="+EDIT_STATE+";\n"+GEOMETRY+"\n})())";
    }
    private static final String GEOMETRY="""
const fail=code=>({status:'unavailable',accepted:false,code});
if(location.href!==expectedUrl||!s||s.id!==observation||s.dirty||!s.observer||s.observer.takeRecords().length||!s.inputViewport||!s.inputRects)return fail('input_observation_changed');
const same=(a,b)=>JSON.stringify(a)===JSON.stringify(b);
if(!same(s.inputViewport,viewport())||s.inputFocus!==focus())return fail('input_viewport_or_focus_changed');
const within=(node,ancestor)=>{for(let n=node,i=0;n&&i<64;i++,n=n.parentElement||(n.getRootNode&&n.getRootNode().host))if(n===ancestor)return true;return false};
const point=(preview,x,y)=>{const index=Number(preview.ref.slice(1)),e=s.nodes[index];
if(!e||!e.isConnected||e.ownerDocument!==document||!s.nodeKeys||s.nodeKeys.get(e)!==preview.nodeKey||e.type==='password'||e.type==='file'||e.autocomplete==='one-time-code'||e.closest('[data-private=true]')||e.disabled||e.matches(':disabled')||e.readOnly||e.getAttribute('aria-disabled')==='true'||e.getAttribute('aria-readonly')==='true')throw Error('input_target_changed');
if(request.action==='key'){const value=editState(e);if(!s.inputValues||!same(s.inputValues[index],value)||value&&value[0]===null)throw Error('input_target_state_changed')}
const form=e.form||e.closest('form'),actual={tag:e.tagName,type:e.type||'',role:e.getAttribute('role')||'',id:e.id||'',name:e.name||'',href:e.tagName==='A'?e.href:undefined,formAction:form?form.action:undefined,formMethod:form?form.method:undefined,submitterFormAction:e.hasAttribute('formaction')?e.formAction:undefined};
for(const k of Object.keys(actual))if(String(actual[k]??'')!==String(preview[k]??''))throw Error('input_target_changed');
const r=e.getBoundingClientRect(),style=getComputedStyle(e);if(!same(s.inputRects[index],[r.left,r.top,r.width,r.height]))throw Error('input_target_moved');
if(!e.getClientRects().length||r.width<=0||r.height<=0||style.visibility==='hidden'||style.visibility==='collapse'||style.display==='none'||style.pointerEvents==='none'||Number(style.opacity)===0)throw Error('input_target_hidden');
const px=r.left+r.width*x/1000,py=r.top+r.height*y/1000,v=viewport();if(px<v.offsetX||py<v.offsetY||px>=v.offsetX+v.width||py>=v.offsetY+v.height)throw Error('input_outside_viewport');
let hit=document.elementFromPoint(px,py);for(let i=0;hit&&hit.shadowRoot&&i<32;i++){const deeper=hit.shadowRoot.elementFromPoint(px,py);if(!deeper||deeper===hit)break;hit=deeper}if(!hit||!within(hit,e))throw Error('input_target_occluded');return {node:e,x:px,y:py,nodeKey:preview.nodeKey,focused:focus()===e}};
try{const from=point(first,request.x??500,request.y??500),to=point(last,request.toX??500,request.toY??500);if(request.action==='key'&&!from.focused)return fail('input_target_not_focused');
if(s.nativeInput&&s.nativeInput.dispose)s.nativeInput.dispose();const e=from.node,types=['pointerdown','pointerup','pointermove','mouseover','keydown','keyup','click','input','change'];
const state=()=>({text:(document.body?document.body.innerText:'').slice(0,12000),url:location.href,scrollX,scrollY,value:e.isContentEditable?e.textContent:('value'in e?String(e.value):null),checked:'checked'in e?!!e.checked:null,selectedIndex:'selectedIndex'in e?e.selectedIndex:null,focused:focus()===e});
const p={token,node:e,toNode:to.node,from,to,first,last,request,viewport:viewport(),before:state(),state,trusted:false,eventCount:0};const seen=event=>{if(p.eventCount<64)p.eventCount++;if(event.isTrusted===true)p.trusted=true};for(const type of types)e.addEventListener(type,seen,true);p.dispose=()=>{clearTimeout(p.expiry);for(const type of types)e.removeEventListener(type,seen,true)};p.expiry=setTimeout(()=>{p.dispose();if(s.nativeInput===p)delete s.nativeInput},5000);
p.check=()=>{if(!same(p.viewport,viewport()))throw Error('input_viewport_changed');const focused=focus();if(focused!==s.inputFocus&&!within(focused,p.node))throw Error('input_focus_changed');const a=point(first,request.x??500,request.y??500),b=point(last,request.toX??500,request.toY??500);if(a.node!==p.node||b.node!==p.toNode||a.x!==p.from.x||a.y!==p.from.y||b.x!==p.to.x||b.y!==p.to.y)throw Error('input_target_changed');return true};
s.nativeInput=p;return {status:'geometry_observed',from:{x:from.x,y:from.y,nodeKey:from.nodeKey,focused:from.focused},to:{x:to.x,y:to.y,nodeKey:to.nodeKey},viewport:p.viewport,documentUrl:location.href};}catch(error){return fail(String(error.message||'input_geometry_unavailable'))}
""";
    public static String continuationScript(String pageKey,String url,String observation,String token){return "JSON.stringify((()=>{const s=window["+JSONObject.quote(pageKey)+"];if(location.href!=="+JSONObject.quote(url)+"||!s||s.id!=="+JSONObject.quote(observation)+"||!s.nativeInput||s.nativeInput.token!=="+JSONObject.quote(token)+")return {status:'unavailable'};try{s.nativeInput.check();return {status:'input_current'}}catch(e){return {status:'unavailable'}}})())";}
    public static String readbackScript(String pageKey,String url,String observation,String token){return "JSON.stringify((()=>{const s=window["+JSONObject.quote(pageKey)+"];if(location.href!=="+JSONObject.quote(url)+"||!s||s.id!=="+JSONObject.quote(observation)+"||!s.nativeInput||s.nativeInput.token!=="+JSONObject.quote(token)+")return {status:'unavailable'};const p=s.nativeInput;p.dispose();delete s.nativeInput;const e=p.node;if(!e||!e.isConnected||e.ownerDocument!==document||!s.nodeKeys||s.nodeKeys.get(e)!==p.first.nodeKey||e.type==='password'||e.type==='file'||e.autocomplete==='one-time-code'||e.tagName!==p.first.tag||(e.type||'')!==(p.first.type||'')||(e.getAttribute('role')||'')!==(p.first.role||'')||(e.id||'')!==(p.first.id||'')||(e.name||'')!==(p.first.name||''))return {status:'unavailable'};const after=p.state(),changed={};for(const name of ['text','url','scrollX','scrollY','value','checked','selectedIndex','focused'])changed[name+'Changed']=after[name]!==p.before[name];return {status:'observed',sameNode:true,nodeKey:p.first.nodeKey,trustedEventObserved:p.trusted,eventCount:p.eventCount,changed,focused:after.focused}})())";}
    public static JSONObject readback(JSONObject dispatch,JSONObject probe)throws Exception {
        if(!probe.optString("status").equals("observed")||!probe.optBoolean("sameNode")||!(probe.opt("trustedEventObserved") instanceof Boolean))return unknown("native_input_readback_unknown");
        if(!probe.getString("nodeKey").matches("k[1-9][0-9]{0,6}"))return unknown("native_input_readback_unknown");JSONObject changes=new JSONObject(),raw=probe.getJSONObject("changed");for(String key:new String[]{"textChanged","urlChanged","scrollXChanged","scrollYChanged","valueChanged","checkedChanged","selectedIndexChanged","focusedChanged"})if(raw.opt(key) instanceof Boolean)changes.put(key,raw.getBoolean(key));
        JSONObject evidence=new JSONObject().put("sameNode",true).put("nodeKey",probe.getString("nodeKey")).put("trustedEventObserved",probe.getBoolean("trustedEventObserved")).put("eventCount",Math.min(64,Math.max(0,probe.getInt("eventCount")))).put("focused",probe.getBoolean("focused")).put("stateChanges",changes).put("evidenceTrust","page_world_untrusted").put("readback","same_node_after_600ms").put("businessSuccess",false);
        return new JSONObject(dispatch.toString()).put("nativeInputEvidence",evidence).put("status",dispatch.optBoolean("platformDispatchAccepted")?"native_input_read_back":"unknown").put("freshObservationRequired",true).put("verified",false).put("automaticReplayAllowed",false);
    }
    public static JSONObject unknown(String code)throws Exception {return new JSONObject().put("status","unknown").put("accepted",JSONObject.NULL).put("verified",false).put("code",code).put("automaticReplayAllowed",false).put("freshObservationRequired",true).put("message","原生输入可能已产生效果，原节点结果尚未核对；请查看原页，不要重复操作。");}

    /** Every event is bounded by native checks; delayed events additionally recheck the exact DOM geometry. */
    public static void dispatch(WebView view,JSONObject args,JSONObject geometry,Check current,Probe probe,Runnable begin,Runnable end,CompletableFuture<JSONObject> result)throws Exception {
        dispatchBound(view,args,geometry,current,probe,begin,end,Event::dispatch,result);
    }
    /** Original RunControl entry closes the pause/resume gap without running authority callbacks under its monitor. */
    public static void dispatch(WebView view,JSONObject args,JSONObject geometry,Check current,Probe probe,Runnable begin,Runnable end,RunControl original,long pauseRevision,CompletableFuture<JSONObject> result)throws Exception {
        java.util.Objects.requireNonNull(original);dispatchBound(view,args,geometry,current,probe,begin,end,event->{final boolean[] accepted={false};original.dispatchAtPauseRevision(pauseRevision,()->accepted[0]=event.dispatch());return accepted[0];},result);
    }
    private static void dispatchBound(WebView view,JSONObject args,JSONObject geometry,Check current,Probe probe,Runnable begin,Runnable end,Entry entry,CompletableFuture<JSONObject> result)throws Exception {
        if(Looper.myLooper()!=Looper.getMainLooper())throw new IllegalStateException("原生网页输入只能在原主线程派发");validate(args);current.check();
        if(!geometry.optString("status").equals("geometry_observed"))throw new SecurityException("原生输入没有当前目标几何");
        JSONObject viewport=geometry.getJSONObject("viewport"),from=geometry.getJSONObject("from"),to=geometry.getJSONObject("to");double width=viewport.getDouble("width"),height=viewport.getDouble("height");
        if(!Double.isFinite(width)||!Double.isFinite(height)||width<=0||height<=0)throw new SecurityException("原网页视口无效");
        float sx=(float)(view.getWidth()/width),sy=(float)(view.getHeight()/height),x=(float)(from.getDouble("x")-viewport.getDouble("offsetX"))*sx,y=(float)(from.getDouble("y")-viewport.getDouble("offsetY"))*sy,tx=(float)(to.getDouble("x")-viewport.getDouble("offsetX"))*sx,ty=(float)(to.getDouble("y")-viewport.getDouble("offsetY"))*sy;
        if(!Float.isFinite(x)||!Float.isFinite(y)||!Float.isFinite(tx)||!Float.isFinite(ty)||x<0||y<0||x>=view.getWidth()||y>=view.getHeight()||tx<0||ty<0||tx>=view.getWidth()||ty>=view.getHeight())throw new SecurityException("原生网页落点超出真实视口");
        String action=args.getString("action");Handler handler=new Handler(Looper.getMainLooper());long down=SystemClock.uptimeMillis();
        class Input {
            boolean entered,pressed,accepted=true,stopped;int heldKey=-1;float lastX=x,lastY=y;Runnable scheduled;
            boolean motion(int kind,float px,float py)throws Exception {current.check();return entry.dispatch(()->{if(result.isDone()||stopped)throw new SecurityException("原输入已终止");begin.run();try{entered=true;lastX=px;lastY=py;if(kind==MotionEvent.ACTION_DOWN)pressed=true;MotionEvent.PointerProperties pointer=new MotionEvent.PointerProperties();pointer.id=0;pointer.toolType=action.equals("hover")?MotionEvent.TOOL_TYPE_MOUSE:MotionEvent.TOOL_TYPE_FINGER;MotionEvent.PointerCoords coordinate=new MotionEvent.PointerCoords();coordinate.x=px;coordinate.y=py;coordinate.pressure=kind==MotionEvent.ACTION_UP||kind==MotionEvent.ACTION_CANCEL?0:1;coordinate.size=1;MotionEvent event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),kind,1,new MotionEvent.PointerProperties[]{pointer},new MotionEvent.PointerCoords[]{coordinate},0,0,1,1,0,0,action.equals("hover")?InputDevice.SOURCE_MOUSE:InputDevice.SOURCE_TOUCHSCREEN,0);try{return action.equals("hover")?view.dispatchGenericMotionEvent(event):view.dispatchTouchEvent(event);}finally{event.recycle();if(kind==MotionEvent.ACTION_UP)pressed=false;}}finally{end.run();}});}
            void cleanup(){if(scheduled!=null)handler.removeCallbacks(scheduled);if(heldKey>=0){begin.run();try{view.dispatchKeyEvent(new KeyEvent(down,SystemClock.uptimeMillis(),KeyEvent.ACTION_UP,heldKey,0,0,KeyCharacterMap.VIRTUAL_KEYBOARD,0,0,InputDevice.SOURCE_KEYBOARD));}finally{end.run();heldKey=-1;}}if(pressed){begin.run();try{MotionEvent event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),MotionEvent.ACTION_CANCEL,lastX,lastY,0);try{view.dispatchTouchEvent(event);}finally{event.recycle();}}finally{end.run();pressed=false;}}}
            void stop(Exception reason){if(stopped)return;stopped=true;try{cleanup();}catch(Exception ignored){}try{if(entered)result.complete(unknown("native_input_interrupted_after_entry"));else result.complete(new JSONObject().put("status","unavailable").put("accepted",false).put("code","native_input_not_dispatched").put("verified",false).put("automaticReplayAllowed",false));}catch(Exception malformed){result.completeExceptionally(malformed);}}
            void complete()throws Exception {current.check();result.complete(new JSONObject().put("status",accepted?"native_input_returned":"unknown").put("accepted",accepted?true:JSONObject.NULL).put("verified",false).put("platformDispatchAccepted",accepted).put("nativeEventsDispatched",true).put("businessOutcomeVerified",false).put("automaticReplayAllowed",false));}
            void schedule(int index,long delay){scheduled=()->{if(result.isDone()||stopped)return;try{current.check();java.util.concurrent.atomic.AtomicBoolean once=new java.util.concurrent.atomic.AtomicBoolean();probe.check(()->{if(!once.compareAndSet(false,true)||result.isDone()||stopped)return;step(index);},failure->{if(once.compareAndSet(false,true))stop(failure);});}catch(Exception failed){stop(failed);}};if(!handler.postDelayed(scheduled,delay))stop(new IllegalStateException("input_handler_closed"));}
            void step(int index){if(result.isDone()||stopped)return;try{current.check();if(action.equals("drag")){if(index==0)accepted&=motion(MotionEvent.ACTION_DOWN,x,y);else if(index==9){accepted&=motion(MotionEvent.ACTION_UP,tx,ty);complete();return;}else accepted&=motion(MotionEvent.ACTION_MOVE,x+(tx-x)*index/8,y+(ty-y)*index/8);schedule(index+1,25);return;}
                if(action.equals("tap")){if(index==0){accepted&=motion(MotionEvent.ACTION_DOWN,x,y);schedule(1,40);}else{accepted&=motion(MotionEvent.ACTION_UP,x,y);complete();}return;}
                if(action.equals("hover")){accepted=motion(MotionEvent.ACTION_HOVER_MOVE,x,y);complete();return;}
                int key=keyCode(args.getString("key"));accepted=entry.dispatch(()->{if(result.isDone()||stopped)throw new SecurityException("原输入已终止");begin.run();try{entered=true;heldKey=key;return view.dispatchKeyEvent(new KeyEvent(down,down,KeyEvent.ACTION_DOWN,key,0,0,KeyCharacterMap.VIRTUAL_KEYBOARD,0,0,InputDevice.SOURCE_KEYBOARD));}finally{end.run();}});current.check();accepted&=entry.dispatch(()->{if(result.isDone()||stopped)throw new SecurityException("原输入已终止");begin.run();try{boolean handled=view.dispatchKeyEvent(new KeyEvent(down,SystemClock.uptimeMillis(),KeyEvent.ACTION_UP,key,0,0,KeyCharacterMap.VIRTUAL_KEYBOARD,0,0,InputDevice.SOURCE_KEYBOARD));heldKey=-1;return handled;}finally{end.run();}});complete();
            }catch(Exception interrupted){stop(interrupted);}}
        }
        Input input=new Input();result.whenComplete((value,error)->{Runnable clean=()->{input.stopped=true;try{input.cleanup();}catch(Exception ignored){}};if(Looper.myLooper()==Looper.getMainLooper())clean.run();else handler.post(clean);});input.step(0);
    }
}
