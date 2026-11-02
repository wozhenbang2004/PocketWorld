package dev.pocketengine.features.local.runtime;
import java.io.File;
import java.util.List;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;
/** Run in a fresh JVM with the old/no-offline-symbol native library. */
public final class NativeOfflineUnavailableTest {
    @Rule public final TemporaryFolder temp=new TemporaryFolder();
    @Test public void missingKernelOfflineEntryDoesNotRunTheTargetCommand()throws Exception{
        File target=new File(temp.getRoot(),"must-not-exist");
        assertThrows(IllegalStateException.class,()->new NativeProcessSupervisor().execute(List.of("/usr/bin/touch",target.getPath()),Map.of(),temp.getRoot(),1000,100,new NativeProcessSupervisor.Observer(){public boolean cancelled(){return false;}public boolean output(int stream,byte[] data){return true;}},true));
        assertFalse(target.exists());
    }
}
