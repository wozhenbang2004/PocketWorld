package dev.pocketengine.features.attachments.processing;
import dev.pocketengine.core.RunControl;
/** A single bounded native decode/mux buffer lease, not a task queue or execution authority. */
final class OriginalFileProcessingBudget implements AutoCloseable {
    private static final java.util.concurrent.Semaphore SINGLE=new java.util.concurrent.Semaphore(1,true);private boolean closed;
    private OriginalFileProcessingBudget(){}
    static OriginalFileProcessingBudget acquire(RunControl control)throws Exception{long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);while(true){control.check();if(SINGLE.tryAcquire(100,java.util.concurrent.TimeUnit.MILLISECONDS))return new OriginalFileProcessingBudget();if(System.nanoTime()>=deadline)throw new IllegalStateException("另一项原件加工尚未释放资源，当前操作未开始，请稍后明确重试");}}
    public void close(){if(!closed){closed=true;SINGLE.release();}}
}
