package dev.pocketengine.phone;

import android.content.Context;
import android.graphics.Insets;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;

/** Layout only: never reparent or replace a displayed stop/hand-back when the available width changes. */
final class OwnerOverlayActionBar extends ViewGroup {
    private static final int LINE=0, TWO=1, NARROW=2;
    private View grip,detail,stop;
    private int mode,firstHeight,bodyWidth,traversal=-1,blockedSides;
    private final int touch,edge;
    OwnerOverlayActionBar(Context context){
        super(context);float scale=getResources().getDisplayMetrics().density;
        touch=Math.round(48*scale);edge=Math.round(8*scale);
    }
    void bind(Button grip,View detail,Button stop){
        if(this.stop!=null)throw new IllegalStateException("原悬浮条已经绑定");
        this.grip=grip;this.detail=detail;this.stop=stop;
        for(Button b:new Button[]{grip,stop}){b.setMinWidth(touch);b.setMinimumWidth(touch);}
        for(View v:new View[]{grip,detail,stop}){
            if(v.getId()==View.NO_ID)v.setId(View.generateViewId());
            addView(v,new LayoutParams(LayoutParams.WRAP_CONTENT,LayoutParams.WRAP_CONTENT));
        }
    }
    @Override public WindowInsets onApplyWindowInsets(WindowInsets window){
        Insets in=window.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());
        if(blockedSides!=in.left+in.right){blockedSides=in.left+in.right;requestLayout();}
        return super.onApplyWindowInsets(window);
    }
    private int available(int spec){
        int safe=getResources().getDisplayMetrics().widthPixels-2*edge;
        WindowInsets window=getRootWindowInsets();
        if(window!=null){
            Insets inset=window.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());
            safe-=inset.left+inset.right;
        }else safe-=blockedSides;
        if(getParent() instanceof View panel)safe-=panel.getPaddingLeft()+panel.getPaddingRight();
        safe=Math.max(1,safe);
        return MeasureSpec.getMode(spec)==MeasureSpec.UNSPECIFIED?safe:Math.min(safe,MeasureSpec.getSize(spec));
    }
    @Override protected void onMeasure(int wSpec,int hSpec){
        if(stop==null){setMeasuredDimension(0,0);return;}
        int limit=available(wSpec),w=MeasureSpec.makeMeasureSpec(limit,MeasureSpec.AT_MOST),h=MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED);
        grip.measure(w,h);detail.measure(w,h);stop.measure(w,h);
        int g=grip.getMeasuredWidth(),d=detail.getMeasuredWidth(),s=stop.getMeasuredWidth();
        int gh=grip.getMeasuredHeight(),dh=detail.getMeasuredHeight(),sh=stop.getMeasuredHeight(),height;
        if(g+d+s<=limit){mode=LINE;bodyWidth=g+d+s;firstHeight=Math.max(gh,Math.max(dh,sh));height=firstHeight;}
        else if(g+s<=limit){mode=TWO;bodyWidth=Math.max(g+s,d);firstHeight=Math.max(gh,sh);height=firstHeight+dh;}
        else{mode=NARROW;bodyWidth=Math.max(g,Math.max(d,s));firstHeight=sh;height=sh+gh+dh;}
        setMeasuredDimension(resolveSize(bodyWidth,wSpec),resolveSize(height,hSpec));
    }
    @Override protected void onLayout(boolean changed,int left,int top,int right,int bottom){
        if(stop==null)return;
        if(mode==LINE){
            place(grip,0,0,firstHeight);place(detail,grip.getMeasuredWidth(),0,firstHeight);
            place(stop,grip.getMeasuredWidth()+detail.getMeasuredWidth(),0,firstHeight);
            order(grip,detail,stop);
        }else if(mode==TWO){
            place(grip,0,0,firstHeight);place(stop,bodyWidth-stop.getMeasuredWidth(),0,firstHeight);
            place(detail,0,firstHeight,detail.getMeasuredHeight());order(grip,stop,detail);
        }else{
            // If even the grip and stop cannot share a row, the emergency action is the first visible row.
            place(stop,0,0,firstHeight);place(grip,0,firstHeight,grip.getMeasuredHeight());
            place(detail,0,firstHeight+grip.getMeasuredHeight(),detail.getMeasuredHeight());order(stop,grip,detail);
        }
    }
    private void place(View v,int start,int top,int height){
        int x=getLayoutDirection()==LAYOUT_DIRECTION_RTL?getWidth()-start-v.getMeasuredWidth():start;
        int y=top+(height-v.getMeasuredHeight())/2;v.layout(x,y,x+v.getMeasuredWidth(),y+v.getMeasuredHeight());
    }
    private void order(View a,View b,View c){
        if(traversal==mode)return;
        traversal=mode;a.setAccessibilityTraversalBefore(b.getId());b.setAccessibilityTraversalBefore(c.getId());c.setAccessibilityTraversalBefore(View.NO_ID);
    }
}
