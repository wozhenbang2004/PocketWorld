#!/usr/bin/env python3
"""Exercise the exact production consumeFrame/coordinate guard on deterministic host fixtures.

This is a JVM method harness, not Android/device evidence. No replacement implementation
of consumeFrame is maintained here; the method and guard are extracted from the selected
production source. --source allows comparison with a previous checked-in version.
"""
import argparse
import os
from pathlib import Path
import re
import subprocess
import tempfile

parser = argparse.ArgumentParser()
root = Path(__file__).resolve().parents[2]
parser.add_argument('--source', type=Path, default=root/'display-helper/src/dev/pocketengine/displayhelper/DisplayEngine.java')
parser.add_argument('--delay', type=int, choices=[50, 200])
args = parser.parse_args()
source = args.source.read_text()
start = source.index('    private void consumeFrame(ImageReader source)')
end = source.index('\n    private JSONObject frame(', start)
consume = source[start:end]
guard = re.search(r'if\((?:framePending\|\|)?expectedFrame<0\|\|expectedFrame!=frameGeneration\.get\(\)\)throw new Failure\([^;]+;', source)
if guard is None:
    raise SystemExit('The production coordinate guard changed; update this harness explicitly.')
trailing_match = re.search(r'    private final Runnable trailingFrame=\(\)->\{.*?\n    \};', source, re.S)
trailing = trailing_match.group(0) if trailing_match else 'private final Runnable trailingFrame=()->{};'
java = r'''
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

public final class FrameRegression {
    private final Handler main=new Handler();
    private ImageReader reader=new ImageReader();
    private long lastFrameConsumed;
    private volatile boolean framePending;
    private ImageReader scheduledFrameReader;
    private final Object frameLock=new Object();
    private volatile byte[] latestPixels;
    private volatile long frameAt;
    private int width=2,height=1;
    private final AtomicLong frameGeneration=new AtomicLong();
    __TRAILING__
    __CONSUME__
    private void coordinateGuard(long expectedFrame){__GUARD__}

    public static void main(String[] args){
        if(args.length==0){scenario(50);scenario(200);burst();}
        else scenario(Integer.parseInt(args[0]));
        System.out.println("Production consumeFrame host regression passed (fixture clock/ImageReader, no Android device)");
    }
    private static void scenario(int delay){
        SystemClock.now=1000;FrameRegression engine=new FrameRegression();
        engine.reader.enqueue(11);engine.consumeFrame(engine.reader);long first=engine.frameGeneration.get();
        check(engine.latestPixels[0]==11,"Initial actual fixture frame was not captured");
        SystemClock.now=1000+delay;engine.reader.enqueue(22);engine.consumeFrame(engine.reader);
        if(delay<150){
            check(engine.reader.acquisitions==1,"Throttling consumed and discarded the final frame");
            check(engine.framePending,"A changed queued frame must invalidate coordinate dispatch immediately");
            rejects(()->engine.coordinateGuard(first));engine.main.advanceTo(1150);
        }
        check(engine.latestPixels!=null&&engine.latestPixels[0]==22,"Final stable frame at "+delay+"ms was lost");
        check(engine.frameGeneration.get()>first,"Changed final frame did not invalidate the previous generation");
        check(!engine.framePending,"Trailing capture left an unresolvable pending barrier");
        rejects(()->engine.coordinateGuard(first));engine.coordinateGuard(engine.frameGeneration.get());
        long settled=engine.frameGeneration.get();engine.main.advanceTo(3000);
        check(engine.latestPixels[0]==22&&engine.frameGeneration.get()==settled,"Final frame required a later compositor callback");
        System.out.println("stable-final-frame delay="+delay+"ms: generation="+settled+", previous generation rejected");
    }
    private static void burst(){
        SystemClock.now=1000;FrameRegression engine=new FrameRegression();
        engine.reader.enqueue(1);engine.consumeFrame(engine.reader);long first=engine.frameGeneration.get();
        SystemClock.now=1050;engine.reader.enqueue(2);engine.consumeFrame(engine.reader);
        SystemClock.now=1090;engine.reader.enqueue(3);engine.consumeFrame(engine.reader);
        check(engine.reader.acquisitions==1,"Burst acquired a frame before the trailing deadline");
        check(engine.main.tasks.size()==1,"Burst scheduled duplicate trailing acquisitions");
        rejects(()->engine.coordinateGuard(first));engine.main.advanceTo(1150);
        check(engine.latestPixels[0]==3,"Coalescer did not capture the newest stable burst frame");
        check(engine.reader.acquisitions==2,"Trailing coalescer acquired more than the newest frame");
        System.out.println("burst 50/90ms: newest frame captured once, pending input rejected");
    }
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    private static void rejects(Runnable action){try{action.run();}catch(Failure expected){return;}throw new AssertionError("Stale coordinate input was accepted");}
    private static final class Failure extends RuntimeException{Failure(String code,String message,boolean dispatched){super(code);}}
    private static final class SystemClock{static long now;static long uptimeMillis(){return now;}}
    private static final class Handler{
        private final List<Scheduled> tasks=new ArrayList<>();
        void postDelayed(Runnable action,long delay){tasks.add(new Scheduled(action,SystemClock.now+delay));}
        void removeCallbacks(Runnable action){tasks.removeIf(task->task.action==action);}
        void advanceTo(long time){while(true){Scheduled next=tasks.stream().filter(task->task.at<=time).min(Comparator.comparingLong(task->task.at)).orElse(null);if(next==null)break;tasks.remove(next);SystemClock.now=next.at;next.action.run();}SystemClock.now=time;}
    }
    private static final class Scheduled{final Runnable action;final long at;Scheduled(Runnable action,long at){this.action=action;this.at=at;}}
    private static final class ImageReader{
        private final Deque<Image> queue=new ArrayDeque<>();int acquisitions;
        void enqueue(int value){queue.add(new Image((byte)value));}
        Image acquireLatestImage(){acquisitions++;Image latest=queue.pollLast();queue.clear();return latest;}
    }
    private static final class Image implements AutoCloseable{
        private final Plane[] planes;
        Image(byte value){planes=new Plane[]{new Plane(value)};}
        Plane[] getPlanes(){return planes;}int getWidth(){return 2;}int getHeight(){return 1;}
        public void close(){}
        private static final class Plane{
            final byte[] pixels;Plane(byte value){pixels=new byte[]{value,0,0,-1,value,0,0,-1};}
            int getPixelStride(){return 4;}int getRowStride(){return 8;}ByteBuffer getBuffer(){return ByteBuffer.wrap(pixels);}
        }
    }
}
'''.replace('__TRAILING__', trailing).replace('__CONSUME__', consume).replace('__GUARD__', guard.group(0))
java_root = Path(os.environ.get('JAVA_HOME', '/usr/lib/jvm/java-17-openjdk-amd64'))
with tempfile.TemporaryDirectory(prefix='pocketworld-frame-regression-') as temporary:
    target = Path(temporary)/'FrameRegression.java'
    target.write_text(java)
    subprocess.run([str(java_root/'bin/javac'), '--release', '17', '-d', temporary, str(target)], check=True)
    command = [str(java_root/'bin/java'), '-cp', temporary, 'FrameRegression']
    if args.delay is not None:
        command.append(str(args.delay))
    subprocess.run(command, check=True)
