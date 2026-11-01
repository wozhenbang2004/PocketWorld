import org.junit.runner.JUnitCore;
import org.junit.runner.Request;

/** One isolated native render check; the caller starts a fresh JVM for each method. */
public final class SelectedNativeUiCheck {
    public static void main(String[] args) throws Exception {
        var result = new JUnitCore().run(Request.method(Class.forName(args[0]), args[1]));
        result.getFailures().forEach(failure -> System.out.println(failure.getTrace()));
        System.out.println(args[0] + "#" + args[1] + " run=" + result.getRunCount()
                + " failed=" + result.getFailureCount() + " ignored=" + result.getIgnoreCount()
                + " assumptions=" + result.getAssumptionFailureCount());
        System.exit(result.wasSuccessful() && result.getRunCount() == 1
                && result.getIgnoreCount() == 0 && result.getAssumptionFailureCount() == 0 ? 0 : 1);
    }
}
