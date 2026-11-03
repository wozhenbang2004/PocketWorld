package dev.pocketengine.features.local.runtime;

import static org.junit.Assert.*;
import dev.pocketengine.core.RunControl;
import java.io.*;
import java.util.concurrent.atomic.*;
import org.junit.Test;

public final class NativeDiagnosticWindowTest {
    private static NativeDiagnosticWindow window(NativeDiagnosticCollector.Preview preview){var w=new NativeDiagnosticWindow(()->true);w.open();w.replace(preview);w.review(preview.sha256(),true);return w;}
    @Test public void noReviewWrongHashAndAReplacementCannotOpenTheDestination()throws Exception {
        var p=NativeDiagnosticCollectorTest.preview();var w=new NativeDiagnosticWindow(()->true);w.open();w.replace(p);assertThrows(SecurityException.class,w::prepare);assertThrows(SecurityException.class,()->w.review("f".repeat(64),true));w.review(p.sha256(),true);var old=w.prepare();w.replace(NativeDiagnosticCollectorTest.preview());AtomicInteger opened=new AtomicInteger();assertThrows(SecurityException.class,()->w.write(old,()->{opened.incrementAndGet();return new ByteArrayOutputStream();},new RunControl()));assertEquals(0,opened.get());
    }
    @Test public void closedPageOrChangedOwnerRejectsOldPickerBeforeOpeningAnyFile()throws Exception {
        var p=NativeDiagnosticCollectorTest.preview();var w=window(p);var ticket=w.prepare();w.close();w.open();AtomicInteger opened=new AtomicInteger();assertThrows(SecurityException.class,()->w.write(ticket,()->{opened.incrementAndGet();return new ByteArrayOutputStream();},new RunControl()));assertEquals(0,opened.get());
        AtomicBoolean owner=new AtomicBoolean(true);var other=new NativeDiagnosticWindow(owner::get);other.open();other.replace(p);other.review(p.sha256(),true);var pin=other.prepare();owner.set(false);assertThrows(SecurityException.class,()->other.write(pin,()->{opened.incrementAndGet();return new ByteArrayOutputStream();},new RunControl()));assertEquals(0,opened.get());
    }
    @Test public void pickerCanReturnAfterForegroundButCannotWriteWhileBackgroundedOrTwice()throws Exception {
        var p=NativeDiagnosticCollectorTest.preview();var w=window(p);var ticket=w.prepare();w.foreground(false);AtomicInteger opened=new AtomicInteger();assertThrows(SecurityException.class,()->w.write(ticket,()->{opened.incrementAndGet();return new ByteArrayOutputStream();},new RunControl()));assertEquals(0,opened.get());w.foreground(true);ByteArrayOutputStream bytes=new ByteArrayOutputStream();assertTrue(w.write(ticket,()->{opened.incrementAndGet();return bytes;},new RunControl()).getBoolean("streamWriteAccepted"));assertEquals(p.size(),bytes.size());assertThrows(SecurityException.class,()->w.write(ticket,()->{opened.incrementAndGet();return bytes;},new RunControl()));assertEquals(1,opened.get());
    }
    @Test public void closingDuringProviderOpenClosesOnlyThatStreamAndWritesNoBytes()throws Exception {
        var p=NativeDiagnosticCollectorTest.preview();var w=window(p);var ticket=w.prepare();AtomicInteger closed=new AtomicInteger();ByteArrayOutputStream bytes=new ByteArrayOutputStream(){@Override public void close(){closed.incrementAndGet();}};
        InterruptedException stopped=assertThrows(InterruptedException.class,()->w.write(ticket,()->{w.close();return bytes;},new RunControl()));assertTrue(stopped.getCause() instanceof SecurityException);assertEquals(0,bytes.size());assertEquals(1,closed.get());
    }
    @Test public void cancelledOrOtherWindowPickerCannotOpenWhileAnExplicitFreshChoiceCan()throws Exception {
        var preview=NativeDiagnosticCollectorTest.preview();var window=window(preview);var cancelled=window.prepare();assertThrows(SecurityException.class,window::prepare);window.cancel(cancelled);AtomicInteger opened=new AtomicInteger();
        assertThrows(SecurityException.class,()->window.write(cancelled,()->{opened.incrementAndGet();return new ByteArrayOutputStream();},new RunControl()));
        var fresh=window.prepare();var other=window(NativeDiagnosticCollectorTest.preview());assertThrows(SecurityException.class,()->other.write(fresh,()->{opened.incrementAndGet();return new ByteArrayOutputStream();},new RunControl()));assertEquals(0,opened.get());
        assertTrue(window.write(fresh,()->{opened.incrementAndGet();return new ByteArrayOutputStream();},new RunControl()).getBoolean("streamWriteAccepted"));assertEquals(1,opened.get());
    }
    @Test public void cancelDuringEnteredWriteIsUnknownAndCannotBeReplayedOrBorrowATaskControl()throws Exception {
        var p=NativeDiagnosticCollectorTest.preview();var w=window(p);var ticket=w.prepare();RunControl c=new RunControl();ByteArrayOutputStream bytes=new ByteArrayOutputStream(){@Override public synchronized void write(byte[] b,int o,int n){super.write(b,o,n);c.cancel();}};
        assertThrows(InterruptedException.class,()->w.write(ticket,()->bytes,c));assertTrue(bytes.size()>0);assertTrue(p.exportEntered());assertThrows(SecurityException.class,()->w.write(ticket,ByteArrayOutputStream::new,new RunControl()));
        var fresh=NativeDiagnosticCollectorTest.preview();var current=window(fresh);var bound=new RunControl();bound.bindHostRun("own-task","own-run",1);assertThrows(SecurityException.class,()->current.write(current.prepare(),ByteArrayOutputStream::new,bound));assertFalse(fresh.exportEntered());
    }
}
