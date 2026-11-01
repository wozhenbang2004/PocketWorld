package dev.pocketengine.browser;

/** A transient resource lifetime, never a task grant or persistent operation authority. */
public interface BrowserTransferFence {
    boolean current();
    void check()throws Exception;
    AutoCloseable watch(Runnable closeResource);
}
