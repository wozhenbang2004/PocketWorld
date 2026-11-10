package dev.pocketengine.displayhelper;

import android.view.KeyEvent;

/** The complete keyboard domain shared by the tool schema and native drivers. */
public final class PhoneKeyCodes {
    private PhoneKeyCodes() { }
    private static final String[] NAMES={
            "KEYCODE_DPAD_UP","KEYCODE_DPAD_DOWN","KEYCODE_DPAD_LEFT","KEYCODE_DPAD_RIGHT","KEYCODE_DPAD_CENTER",
            "KEYCODE_ENTER","KEYCODE_NUMPAD_ENTER","KEYCODE_DEL","KEYCODE_FORWARD_DEL","KEYCODE_TAB","KEYCODE_SPACE",
            "KEYCODE_ESCAPE","KEYCODE_PAGE_UP","KEYCODE_PAGE_DOWN","KEYCODE_MOVE_HOME","KEYCODE_MOVE_END","KEYCODE_BACK","KEYCODE_MENU"};
    private static final int[] CODES={
            KeyEvent.KEYCODE_DPAD_UP,KeyEvent.KEYCODE_DPAD_DOWN,KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.KEYCODE_DPAD_RIGHT,KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,KeyEvent.KEYCODE_NUMPAD_ENTER,KeyEvent.KEYCODE_DEL,KeyEvent.KEYCODE_FORWARD_DEL,KeyEvent.KEYCODE_TAB,KeyEvent.KEYCODE_SPACE,
            KeyEvent.KEYCODE_ESCAPE,KeyEvent.KEYCODE_PAGE_UP,KeyEvent.KEYCODE_PAGE_DOWN,KeyEvent.KEYCODE_MOVE_HOME,KeyEvent.KEYCODE_MOVE_END,KeyEvent.KEYCODE_BACK,KeyEvent.KEYCODE_MENU};
    public static String[] names(){return NAMES.clone();}
    public static int require(String key){
        for(int i=0;i<NAMES.length;i++)if(NAMES[i].equals(key))return CODES[i];
        throw new IllegalArgumentException("unsupported_phone_key");
    }
    public static boolean allowed(int code){for(int candidate:CODES)if(candidate==code)return true;return false;}
    public static boolean mayActivate(int code){
        return code==KeyEvent.KEYCODE_ENTER||code==KeyEvent.KEYCODE_NUMPAD_ENTER||code==KeyEvent.KEYCODE_DPAD_CENTER
                ||code==KeyEvent.KEYCODE_SPACE||code==KeyEvent.KEYCODE_MENU;
    }
}
