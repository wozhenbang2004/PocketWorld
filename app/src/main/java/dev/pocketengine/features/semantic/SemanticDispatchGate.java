package dev.pocketengine.features.semantic;

import java.util.concurrent.atomic.AtomicInteger;

/** A timed-out queued main-thread callback cannot later begin an Android launch. */
final class SemanticDispatchGate {
    private final AtomicInteger state=new AtomicInteger();
    boolean enter() { return state.compareAndSet(0,1); }
    boolean cancelBeforeEntry() { return state.compareAndSet(0,2)||state.get()==2; }
    boolean entered() { return state.get()==1; }
}
