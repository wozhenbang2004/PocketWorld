package dev.pocketengine.displayhelper;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;

/** Two-pointer extension of the same lease-bound helper, with exact-sequence cancellation. */
final class DisplayGesturePaths {
    interface Gate {void check()throws Exception;}
    interface Input {boolean send(MotionEvent event)throws Exception;}
    private final Handler main;private volatile Sequence current;
    private static final class Sequence {final LeasePolicy lease;final float[][][] paths;final long down;volatile boolean attempted,completed;boolean downAccepted,cancelAttempted,cancelAccepted;int pointers;float progress;Sequence(LeasePolicy lease,float[][][] paths){this.lease=lease;this.paths=paths;down=SystemClock.uptimeMillis();}}
    DisplayGesturePaths(Handler main){this.main=main;}
    boolean active(){return current!=null;}
    JSONObject run(LeasePolicy lease,JSONObject request,int width,int height,Gate gate,Input input)throws Exception {
        float[][][] paths=parse(request.getJSONArray("paths"),width,height);int duration=integer(request,"durationMs",50,5000);Sequence sequence=new Sequence(lease,paths);
        try{
            onMain(()->{gate.check();if(current!=null)throw new IllegalStateException("touch_sequence_busy");current=sequence;return true;});
            if(!onMain(()->{gate.check();require(sequence);sequence.pointers=1;boolean accepted=send(sequence,MotionEvent.ACTION_DOWN,input);sequence.downAccepted=accepted;return accepted;}))throw new IllegalStateException("gesture_down_unconfirmed");
            if(paths.length==2&&!onMain(()->{gate.check();require(sequence);sequence.pointers=2;return send(sequence,MotionEvent.ACTION_POINTER_DOWN|(1<<MotionEvent.ACTION_POINTER_INDEX_SHIFT),input);}))throw new IllegalStateException("second_pointer_rejected");
            long until=SystemClock.uptimeMillis()+duration;
            while(true){long remaining=until-SystemClock.uptimeMillis();if(remaining>0)Thread.sleep(Math.min(20,remaining));final float fraction=Math.min(1f,1f-(float)(until-SystemClock.uptimeMillis())/duration);
                if(!onMain(()->{gate.check();require(sequence);sequence.progress=fraction;return send(sequence,MotionEvent.ACTION_MOVE,input);}))throw new IllegalStateException("gesture_move_rejected");
                if(fraction>=1f)break;
            }
            if(paths.length==2&&!onMain(()->{gate.check();require(sequence);boolean accepted=send(sequence,MotionEvent.ACTION_POINTER_UP|(1<<MotionEvent.ACTION_POINTER_INDEX_SHIFT),input);sequence.pointers=1;return accepted;}))throw new IllegalStateException("pointer_up_rejected");
            boolean finished=onMain(()->{gate.check();require(sequence);boolean accepted=send(sequence,MotionEvent.ACTION_UP,input);if(accepted){sequence.completed=true;if(current==sequence)current=null;}return accepted;});if(!finished)throw new IllegalStateException("gesture_up_rejected");return receipt(true);
        }catch(Exception failure){
            boolean interrupted=Thread.interrupted()||failure instanceof InterruptedException;String cleanup;
            try{cleanup=cancel(sequence,input);}finally{if(interrupted)Thread.currentThread().interrupt();}
            if(sequence.completed)return receipt(true).put("requiresPause",true).put("postActionObservation","unavailable").put("dispatchReceiptPreserved",true).put("automaticReplayAllowed",false);
            if(!sequence.attempted)return receipt(false).put("error","gesture_not_started");
            return new JSONObject().put("ok",false).put("accepted",JSONObject.NULL).put("error","gesture_outcome_unknown").put("pointerCleanup",cleanup).put("requiresReconciliation",true).put("automaticReplayAllowed",false);
        }
    }
    void cancelLease(LeasePolicy lease,Input input){Sequence value=current;if(value!=null&&value.lease==lease)cancel(value,input);}
    /** Called by the owner only after the original display token has actually been released. */
    void releaseLease(LeasePolicy lease){Sequence value=current;if(value!=null&&value.lease==lease)current=null;}
    private String cancel(Sequence sequence,Input input){try{return onMain(()->{
        if(current!=sequence)return sequence.cancelAccepted?"cancel_accepted_not_business_proof":"not_attempted_original_sequence_gone";
        if(!sequence.attempted){current=null;return "not_started";}
        if(!sequence.downAccepted)return "not_attempted_down_unconfirmed";
        if(sequence.cancelAttempted)return sequence.cancelAccepted?"cancel_accepted_not_business_proof":"cancel_unconfirmed";
        sequence.cancelAttempted=true;
        if(send(sequence,MotionEvent.ACTION_CANCEL,input)){sequence.cancelAccepted=true;if(current==sequence)current=null;return "cancel_accepted_not_business_proof";}
        return "cancel_unconfirmed";
    });}catch(Exception ignored){return "cancel_unconfirmed";/* Original display teardown is the final fence. */}}
    private void require(Sequence sequence){if(current!=sequence)throw new SecurityException("touch_sequence_replaced");}
    private static boolean send(Sequence sequence,int action,Input input)throws Exception {
        int count=sequence.pointers;MotionEvent.PointerProperties[] properties=new MotionEvent.PointerProperties[count];MotionEvent.PointerCoords[] coordinates=new MotionEvent.PointerCoords[count];
        for(int i=0;i<count;i++){MotionEvent.PointerProperties p=new MotionEvent.PointerProperties();p.id=i;p.toolType=MotionEvent.TOOL_TYPE_FINGER;properties[i]=p;float[][] path=sequence.paths[i];float at=sequence.progress*(path.length-1);int a=Math.min(path.length-2,(int)at);float part=at-a;MotionEvent.PointerCoords c=new MotionEvent.PointerCoords();c.x=path[a][0]+(path[a+1][0]-path[a][0])*part;c.y=path[a][1]+(path[a+1][1]-path[a][1])*part;c.pressure=1;c.size=1;coordinates[i]=c;}
        MotionEvent event=MotionEvent.obtain(sequence.down,SystemClock.uptimeMillis(),action,count,properties,coordinates,0,0,1,1,0,0,InputDevice.SOURCE_TOUCHSCREEN,0);try{sequence.attempted=true;return input.send(event);}finally{event.recycle();}
    }
    private static float[][][] parse(JSONArray paths,int width,int height)throws Exception {if(width<1||height<1||paths.length()<1||paths.length()>2)throw new IllegalArgumentException("gesture_paths_invalid");float[][][] out=new float[paths.length()][][];for(int i=0;i<out.length;i++){JSONArray path=paths.getJSONArray(i);if(path.length()<2||path.length()>32)throw new IllegalArgumentException("gesture_path_limit");out[i]=new float[path.length()][2];for(int j=0;j<path.length();j++){JSONArray point=path.getJSONArray(j);if(point.length()!=2)throw new IllegalArgumentException("gesture_point_invalid");for(int k=0;k<2;k++){Object raw=point.get(k);if(!(raw instanceof Number))throw new IllegalArgumentException("gesture_coordinate_invalid");int value=new java.math.BigDecimal(raw.toString()).intValueExact();if(value<0||value>1000)throw new IllegalArgumentException("gesture_coordinate_invalid");out[i][j][k]=(float)value/1000*(k==0?width-1:height-1);}}}return out;}
    private static int integer(JSONObject value,String key,int min,int max)throws Exception{Object raw=value.get(key);if(!(raw instanceof Number))throw new IllegalArgumentException("gesture_duration_invalid");int n=new java.math.BigDecimal(raw.toString()).intValueExact();if(n<min||n>max)throw new IllegalArgumentException("gesture_duration_invalid");return n;}
    private static JSONObject receipt(boolean accepted)throws Exception{return new JSONObject().put("ok",accepted).put("accepted",accepted).put("verified",false).put("requiresPostconditionCheck",accepted);}
    private <T>T onMain(java.util.concurrent.Callable<T> action)throws Exception {
        if(Looper.myLooper()==main.getLooper())return action.call();long deadline=SystemClock.uptimeMillis()+3000;AtomicInteger entry=new AtomicInteger();FutureTask<T> future=new FutureTask<>(()->{if(SystemClock.uptimeMillis()>=deadline||!entry.compareAndSet(0,1))throw new SecurityException("gesture_queue_expired");return action.call();});main.post(future);
        try{return future.get(3,TimeUnit.SECONDS);}catch(Exception failed){entry.compareAndSet(0,2);throw failed;}finally{future.cancel(false);}
    }
}
