package dev.pocketengine.phone;

import android.os.Handler;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Pure presentation reads. Main owns the latest request. A stalled old journal read cannot create unbounded workers or resurrect a departed UI. */
final class OwnerOverlayReads<T> {
    private static final ThreadPoolExecutor IO=new ThreadPoolExecutor(0,1,15,TimeUnit.SECONDS,new ArrayBlockingQueue<>(2),
            work->{Thread t=new Thread(work,"phone-overlay-facts");t.setDaemon(true);return t;});
    private record Request<T>(long seq,Supplier<T> read,Consumer<T> render){}
    private final Handler main;
    private long sequence;private boolean running;
    private Request<T> next;
    OwnerOverlayReads(Handler main){this.main=main;}
    void request(Supplier<T> read,Consumer<T> render){next=new Request<>(++sequence,read,render);start();}
    void clear(){sequence++;next=null;}
    private void start(){
        if(running||next==null)return;
        var request=next;next=null;running=true;
        try{IO.execute(()->{
            T result;
            try{result=request.read().get();}catch(RuntimeException unavailable){result=null;}
            T ready=result;main.post(()->finish(request,ready));
        });}catch(RejectedExecutionException busy){finish(request,null);}
    }
    private void finish(Request<T> original,T value){
        running=false;
        try{if(sequence==original.seq())original.render().accept(value);}finally{start();}
    }
}
