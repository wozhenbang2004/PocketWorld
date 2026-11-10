package dev.pocketengine.phone;

import android.icu.text.BreakIterator;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inputmethod.SurroundingText;
import java.util.Locale;

/** Exact ordinary-editor edits. A void input-connection call is confirmed only by its actual postcondition. */
final class PhoneKeyEdit {
    static final int MAX_TEXT=16000;
    final String before,after;
    final int start,end,cursor,deleteBefore,deleteAfter;
    final String insertion;
    private PhoneKeyEdit(String before,int start,int end,String after,int cursor,int deleteBefore,int deleteAfter,String insertion){
        this.before=before;this.start=start;this.end=end;this.after=after;this.cursor=cursor;
        this.deleteBefore=deleteBefore;this.deleteAfter=deleteAfter;this.insertion=insertion;
    }
    static boolean supports(int code){return code==KeyEvent.KEYCODE_DEL||code==KeyEvent.KEYCODE_FORWARD_DEL||code==KeyEvent.KEYCODE_SPACE;}
    static PhoneKeyEdit prepare(int code,AccessibilityNodeInfo node,PhoneInputMethod.Token connection){
        if(!supports(code)||node==null||node.isPassword()||!node.isEditable()||!node.isFocused())return null;
        String text=text(node);int start=node.getTextSelectionStart(),end=node.getTextSelectionEnd();
        if(text==null||start<0||end<0||start>text.length()||end>text.length())return null;
        if(!matches(connection.surrounding(),text,start,end))return null;
        int low=Math.min(start,end),high=Math.max(start,end),from=low,to=high;
        String insert=code==KeyEvent.KEYCODE_SPACE?" ":"";
        if(low==high&&code!=KeyEvent.KEYCODE_SPACE){
            if(code==KeyEvent.KEYCODE_DEL&&low==0||code==KeyEvent.KEYCODE_FORWARD_DEL&&high==text.length())
                return new PhoneKeyEdit(text,start,end,text,low,0,0,null);
            // deleteSurroundingText expands around a composing range on Android. Unknown composition is not safe to calculate.
            if(!connection.noComposition(start,end))return null;
            BreakIterator graphemes=BreakIterator.getCharacterInstance(Locale.ROOT);graphemes.setText(text);
            // Do not split an existing grapheme/UTF-16 selection boundary supplied by the editor.
            if(!graphemes.isBoundary(low))return null;
            if(code==KeyEvent.KEYCODE_DEL&&low>0)from=graphemes.preceding(low);
            if(code==KeyEvent.KEYCODE_FORWARD_DEL&&high<text.length())to=graphemes.following(high);
        }
        if(from<0||to<from)return null;
        String after=text.substring(0,from)+insert+text.substring(to);
        return new PhoneKeyEdit(text,start,end,after,from+insert.length(),low-from,to-high,
                low!=high||code==KeyEvent.KEYCODE_SPACE?insert:null);
    }
    boolean unchanged(){return before.equals(after)&&start==cursor&&end==cursor;}
    boolean beforeMatches(AccessibilityNodeInfo node){return before.equals(text(node))&&start==node.getTextSelectionStart()&&end==node.getTextSelectionEnd();}
    boolean canDispatch(PhoneInputMethod.Token connection){return connection.current()&&(insertion!=null||connection.noComposition(start,end));}
    void perform(PhoneInputMethod.Token connection){
        if(insertion!=null)connection.commit(insertion);else connection.delete(deleteBefore,deleteAfter);
    }
    boolean confirmed(PhoneInputMethod.Token connection,AccessibilityNodeInfo node){
        return node.isFocused()&&after.equals(text(node))&&node.getTextSelectionStart()==cursor&&node.getTextSelectionEnd()==cursor
                &&matches(connection.surrounding(),after,cursor,cursor);
    }
    private static String text(AccessibilityNodeInfo node){
        if(node.isPassword())return null;CharSequence text=node.isShowingHintText()?"":node.getText();
        if(text==null)text="";return text.length()>MAX_TEXT?null:text.toString();
    }
    private static boolean matches(SurroundingText value,String text,int start,int end){
        return value!=null&&value.getOffset()==0&&value.getText()!=null&&text.contentEquals(value.getText())
                &&value.getSelectionStart()==start&&value.getSelectionEnd()==end;
    }
}
