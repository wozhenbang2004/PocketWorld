#!/usr/bin/env python3
"""Exercise the exact production consumeFrame/coordinate guard on deterministic host fixtures.

This is a JVM method harness, not Android/device evidence. No replacement implementation
of consumeFrame is maintained here; the method and guard are extracted from the selected
production source. --source allows comparison with a previous checked-in version.
--classpath supplies the existing real org.json jar used by production geometry metadata.
Geometry/display events remain explicit fixture inputs; this does not validate Android rotation.
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
parser.add_argument('--classpath', default=os.environ.get('POCKETWORLD_HOST_CLASSPATH', ''))
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
import dev.pocketengine.displayhelper.DisplayGeometry;

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
    private Object lease=new Object();
    private DisplayGeometry geometry=new DisplayGeometry(1,2,1,160,0);
    private boolean geometryReady=true;
    // Explicit display-provider fixture. consumeFrame and logicalPixels are real source.
    private DisplayGeometry synchronizeGeometry(Object ignored,boolean changed){return geometry;}
    private DisplayGeometry readOwnedGeometry(long ignored){return geometry;}
    private int captureTransform(Image image){return image.transform;}
    private void blockGeometry(){geometryReady=false;latestPixels=null;framePending=true;}
    private final AtomicLong frameGeneration=new AtomicLong();
    __TRAILING__
    __CONSUME__
    private void coordinateGuard(long expectedFrame){__GUARD__}

    public static void main(String[] args){
        if(args.length==0){scenario(50);scenario(200);burst();retiredSurface();changedDuringCapture();unsupportedPlane();}
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
    private static void retiredSurface(){
        SystemClock.now=1000;FrameRegression engine=new FrameRegression();ImageReader old=engine.reader;
        engine.reader=new ImageReader();engine.geometry=new DisplayGeometry(2,2,1,160,2);
        old.enqueue(11);engine.consumeFrame(old);
        check(engine.latestPixels==null&&old.acquisitions==0,"Retired surface entered the new capture generation");
        engine.reader.enqueue(22);engine.consumeFrame(engine.reader);
        check(engine.latestPixels[0]==22,"Replacement surface could not publish its current geometry");
        System.out.println("retired surface: ignored; current surface publishes");
    }
    private static void changedDuringCapture(){
        SystemClock.now=1000;FrameRegression engine=new FrameRegression();engine.reader.enqueue(11);
        engine.reader.beforeRead=()->engine.geometry=engine.geometry.withGeneration(2);
        engine.consumeFrame(engine.reader);
        check(engine.latestPixels==null,"A frame crossed a geometry generation during capture");
        engine.reader.beforeRead=null;SystemClock.now=1200;engine.reader.enqueue(22);engine.consumeFrame(engine.reader);
        check(engine.latestPixels[0]==22,"New geometry could not publish a fresh frame");
        System.out.println("capture generation change: old pixels rejected, fresh pixels publish");
    }
    private static void unsupportedPlane(){
        for(boolean crop:new boolean[]{false,true}){
            SystemClock.now=1000;FrameRegression engine=new FrameRegression();engine.reader.enqueue(11);
            Image queued=engine.reader.queue.peekLast();if(crop)queued.right=1;else queued.transform=4;
            engine.consumeFrame(engine.reader);
            check(engine.latestPixels==null,"Unknown crop/producer transform was exposed as logical pixels");
        }
        System.out.println("unknown crop/producer transform: no image published");
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
        private final Deque<Image> queue=new ArrayDeque<>();int acquisitions;Runnable beforeRead;
        void enqueue(int value){queue.add(new Image((byte)value));}
        Image acquireLatestImage(){acquisitions++;Image latest=queue.pollLast();queue.clear();if(beforeRead!=null)beforeRead.run();return latest;}
    }
    private static final class Image implements AutoCloseable{
        private final Plane[] planes;
        int transform,right=2;
        Image(byte value){planes=new Plane[]{new Plane(value)};}
        Crop getCropRect(){return new Crop(right);}
        Plane[] getPlanes(){return planes;}int getWidth(){return 2;}int getHeight(){return 1;}
        public void close(){}
        private static final class Crop{final int left=0,top=0,bottom=1,right;Crop(int right){this.right=right;}}
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
    geometry_source=root/'display-helper/src/dev/pocketengine/displayhelper/DisplayGeometry.java'
    subprocess.run([str(java_root/'bin/javac'), '--release', '17', '-cp', args.classpath,
                    '-d', temporary, str(target), str(geometry_source)], check=True)
    command = [str(java_root/'bin/java'), '-cp', temporary+os.pathsep+args.classpath, 'FrameRegression']
    if args.delay is not None:
        command.append(str(args.delay))
    subprocess.run(command, check=True)
