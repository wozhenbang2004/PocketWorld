package dev.pocketengine.features.teams;
import dev.pocketengine.core.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;
public final class TeamBudgetPortNativeHooksTest {
    @Test public void meteringWrapperPreservesOriginalTypedConsumersWithoutExecutingOrCharging()throws Exception {
        RunControl original=new RunControl();JSONObject args=new JSONObject().put("nativeFixture",true);JSONArray messages=new JSONArray();AtomicInteger observed=new AtomicInteger();
        CapabilityPort delegate=new CapabilityPort(){public JSONArray tools(){return new JSONArray();}public boolean supports(String name){return false;}public boolean sideEffecting(String name){return false;}public JSONObject execute(String n,JSONObject a,RunControl c){throw new AssertionError("no dispatch");}
            public NativeToolObservation.Requirement observationRequirement(String tool,JSONObject a,RunControl live){assertEquals("original_tool",tool);assertSame(args,a);assertSame(original,live);observed.incrementAndGet();return NativeToolObservation.Requirement.NONE;}
            public NativeToolObservation takeToolObservation(String tool,String op,RunControl live){assertEquals("original_tool",tool);assertEquals("original_operation",op);assertSame(original,live);observed.incrementAndGet();return null;}
            public NativeToolPlan takeToolPlan(String tool,String op,RunControl live){assertEquals("original_tool",tool);assertEquals("original_operation",op);assertSame(original,live);observed.incrementAndGet();return null;}
            public NativeReadContextRepair nativeReadContextRepair(JSONArray wire,RunControl live){assertSame(messages,wire);assertSame(original,live);observed.incrementAndGet();return null;}};
        TeamBudgetPort wrapper=new TeamBudgetPort(delegate,null,new JSONObject());assertEquals(NativeToolObservation.Requirement.NONE,wrapper.observationRequirement("original_tool",args,original));assertNull(wrapper.takeToolObservation("original_tool","original_operation",original));assertNull(wrapper.takeToolPlan("original_tool","original_operation",original));assertNull(wrapper.nativeReadContextRepair(messages,original));assertEquals(4,observed.get());
    }
}
