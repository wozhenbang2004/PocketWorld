package dev.pocketengine.phone;

import android.accessibilityservice.InputMethod;
import android.annotation.TargetApi;
import android.os.Looper;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;

/** Holds only the current accessibility editor session; it never focuses or chooses an editor. */
@TargetApi(33)
final class PhoneInputMethod extends InputMethod {
    private volatile long revision;
    PhoneInputMethod(PhoneAccessibilityService service){super(service);}
    @Override public void onStartInput(EditorInfo editor,boolean restarting){revision++;}
    @Override public void onFinishInput(){revision++;}
    long revision(){return revision;}
    Token current(String packageName,long expectedRevision){
        if(Looper.myLooper()!=Looper.getMainLooper())throw new IllegalStateException("editor_session_requires_main");
        EditorInfo editor=getCurrentInputEditorInfo();AccessibilityInputConnection connection=getCurrentInputConnection();
        if(!getCurrentInputStarted()||connection==null||editor==null||revision!=expectedRevision||!packageName.equals(editor.packageName))return null;
        return new Token(this,revision,editor,connection);
    }
    static final class Token {
        private final PhoneInputMethod owner;
        private final long revision;
        private final EditorInfo editor;
        private final AccessibilityInputConnection connection;
        private Token(PhoneInputMethod owner,long revision,EditorInfo editor,AccessibilityInputConnection connection){this.owner=owner;this.revision=revision;this.editor=editor;this.connection=connection;}
        boolean current(){return owner.revision==revision&&owner.getCurrentInputStarted()&&owner.getCurrentInputEditorInfo()==editor;}
        void send(int code){
            long time=SystemClock.uptimeMillis();
            try{connection.sendKeyEvent(new KeyEvent(time,time,KeyEvent.ACTION_DOWN,code,0,0,KeyCharacterMap.VIRTUAL_KEYBOARD,0,KeyEvent.FLAG_SOFT_KEYBOARD,InputDevice.SOURCE_KEYBOARD));}
            finally{
                // Release this attempt through the same connection even when DOWN throws or changes focus.
                connection.sendKeyEvent(new KeyEvent(time,SystemClock.uptimeMillis(),KeyEvent.ACTION_UP,code,0,0,KeyCharacterMap.VIRTUAL_KEYBOARD,0,KeyEvent.FLAG_SOFT_KEYBOARD,InputDevice.SOURCE_KEYBOARD));
            }
        }
    }
}
