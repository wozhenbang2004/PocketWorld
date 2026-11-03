package dev.pocketengine.features.remote;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import static org.junit.Assert.*;

/** One JUnit entry runs exactly seven original Companion real-file/probe tests, never all Python tests. */
public final class WorkerEntrypointCompanionContractTest {
    @Test public void runSevenExactCompanionIdentityTestsWithNoSkippedOrInventedSuccess()throws Exception {
        String source=System.getProperty("pocket.gateway.source");assertNotNull("Original repository source root is required",source);
        Path directory=Path.of(source).resolve("companion"),log=Files.createTempFile("worker-entrypoint-contract-",".log");Process process=null;
        try {
            assertTrue(Files.isRegularFile(directory.resolve("test_worker_executable_identity.py")));
            process=new ProcessBuilder(System.getProperty("pocket.gateway.python","python3"),"-B","-m","unittest","-v","test_worker_executable_identity").directory(directory.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
            assertTrue("The exact original Companion contract did not finish",process.waitFor(30,TimeUnit.SECONDS));
            assertTrue("Companion contract output exceeded its bound",Files.size(log)<=65536);
            String actual=new String(Files.readAllBytes(log),StandardCharsets.UTF_8);System.out.print(actual);
            assertEquals(actual,0,process.exitValue());assertTrue(actual,actual.matches("(?s).*\\nRan 7 tests in [^\\n]+\\n.*"));
            assertTrue(actual,actual.matches("(?s).*\\nOK\\s*$"));assertFalse(actual,actual.contains("skipped"));
        } finally {
            if(process!=null&&process.isAlive()){process.descendants().forEach(child->child.destroyForcibly());process.destroyForcibly();process.waitFor(5,TimeUnit.SECONDS);}
            Files.deleteIfExists(log);
        }
    }
}
