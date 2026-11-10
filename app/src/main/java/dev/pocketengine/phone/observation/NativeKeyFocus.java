package dev.pocketengine.phone.observation;

import android.util.SparseArray;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import dev.pocketengine.phone.PhoneAccessibilityService;
import java.util.List;
import org.json.JSONObject;

/** A native focused window and optional focused node, never a model-selected destination. */
public final class NativeKeyFocus {
    public final int windowId;
    public final String packageName;
    public final AccessibilityForest.Ref node;
    private NativeKeyFocus(int windowId,String packageName,AccessibilityForest.Ref node){this.windowId=windowId;this.packageName=packageName;this.node=node;}

    public static NativeKeyFocus from(AccessibilityForest forest,int display,int code)throws Unavailable {
        JSONObject window=null;
        for(int i=0;i<forest.windows.length();i++){
            JSONObject candidate=forest.windows.optJSONObject(i);
            if(candidate==null||!candidate.optBoolean("focused"))continue;
            if(window!=null||candidate.optInt("displayId",-1)!=display||!candidate.optBoolean("rootAvailable"))throw new Unavailable("key_focus_unavailable");
            window=candidate;
        }
        if(window==null||window.optString("packageName").isEmpty())throw new Unavailable("key_focus_unavailable");
        int id=window.optInt("windowId",-1);AccessibilityForest.Ref focused=null;
        for(AccessibilityForest.Ref candidate:forest.refs.values())if(candidate.windowId==id&&candidate.focused){
            if(focused!=null)throw new Unavailable("key_focus_ambiguous");focused=candidate;
        }
        boolean navigation=code==KeyEvent.KEYCODE_BACK||code==KeyEvent.KEYCODE_ESCAPE||code==KeyEvent.KEYCODE_TAB
                ||code==KeyEvent.KEYCODE_DPAD_UP||code==KeyEvent.KEYCODE_DPAD_DOWN||code==KeyEvent.KEYCODE_DPAD_LEFT||code==KeyEvent.KEYCODE_DPAD_RIGHT
                ||code==KeyEvent.KEYCODE_PAGE_UP||code==KeyEvent.KEYCODE_PAGE_DOWN||code==KeyEvent.KEYCODE_MOVE_HOME||code==KeyEvent.KEYCODE_MOVE_END;
        if(id<0||focused==null&&!navigation)throw new Unavailable("key_focus_unavailable");
        return new NativeKeyFocus(id,window.optString("packageName"),focused);
    }

    /** Public global actions have no display argument, so another focused display makes them unusable. */
    public void verify(PhoneAccessibilityService service,int display,boolean exclusiveDisplay)throws Exception {
        verify(service,display,exclusiveDisplay,false);
    }
    /** EditorInfo identifies an app, but exposes no display/profile identity. Exclude a same-app ambiguity. */
    public void verify(PhoneAccessibilityService service,int display,boolean exclusiveDisplay,boolean uniqueEditorPackage)throws Exception {
        PhoneReadGate.checkCurrent();
        SparseArray<List<AccessibilityWindowInfo>> all=service.getWindowsOnAllDisplays();
        if(all==null)throw new Unavailable("key_focus_unavailable");
        int focused=0,total=0;boolean matched=false;
        try{
            for(int i=0;i<all.size();i++){
                List<AccessibilityWindowInfo> windows=all.valueAt(i);if(windows==null)continue;
                for(AccessibilityWindowInfo window:windows){
                    PhoneReadGate.checkCurrent();if(window==null)continue;
                    if(++total>128)throw new Unavailable("key_focus_unavailable");
                    if(!window.isFocused())continue;
                    if(all.keyAt(i)!=display){
                        if(exclusiveDisplay)throw new Unavailable("key_display_focus_unverifiable");
                        if(uniqueEditorPackage){AccessibilityNodeInfo other=window.getRoot();try{
                            if(other==null||other.getPackageName()==null||packageName.contentEquals(other.getPackageName()))throw new Unavailable("key_display_focus_unverifiable");
                        }finally{if(other!=null)other.recycle();}}
                        continue;
                    }
                    if(++focused!=1||window.getId()!=windowId||window.getDisplayId()!=display
                            ||window.getType()==AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY)throw new Unavailable("key_focus_changed");
                    AccessibilityNodeInfo root=window.getRoot(),actual=null;
                    try{
                        if(root==null||!packageName.contentEquals(root.getPackageName()==null?"":root.getPackageName()))throw new Unavailable("key_focus_changed");
                        actual=root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
                        if(node==null){if(actual!=null)throw new Unavailable("key_focus_changed");}
                        else if(actual==null||!actual.refresh()||!actual.isFocused()||!actual.isEnabled()||!actual.isVisibleToUser()
                                ||actual.getWindowId()!=windowId||!AccessibilityForest.matches(node,actual))throw new Unavailable("key_focus_changed");
                        matched=true;
                    }finally{if(actual!=null&&actual!=root)actual.recycle();if(root!=null)root.recycle();}
                }
            }
        }finally{for(int i=0;i<all.size();i++){List<AccessibilityWindowInfo> windows=all.valueAt(i);if(windows!=null)for(AccessibilityWindowInfo window:windows)if(window!=null)window.recycle();}}
        if(!matched||focused!=1)throw new Unavailable("key_focus_unavailable");
        PhoneReadGate.checkCurrent();
    }

    public static final class Unavailable extends Exception {
        public final String code;
        public Unavailable(String code){super("无法确认原显示器的当前键盘焦点，未派发按键，请重新观察。");this.code=code;}
    }
}
