package dev.pocketengine.features.memory;

import java.io.File;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public final class AutomaticMemoryPolicyTest {
    @Rule public final TemporaryFolder temp=new TemporaryFolder();
    @Test public void defaultOffAndOptOutFenceTheExactEarlierPolicy()throws Exception{
        File file=new File(temp.getRoot(),"automatic.json");AutomaticMemoryPolicy policy=new AutomaticMemoryPolicy(file);JSONObject initial=policy.snapshot();assertFalse(initial.getBoolean("enabled"));assertEquals(0,initial.getLong("revision"));
        JSONObject selected=policy.setForOwner(0,true,3);policy.requireCurrent(selected);assertThrows(IllegalStateException.class,()->policy.setForOwner(0,true,3));
        assertThrows(SecurityException.class,()->policy.requireCurrent(new JSONObject(selected.toString()).put("enabled","true")));
        AutomaticMemoryPolicy restored=new AutomaticMemoryPolicy(file);assertTrue(restored.snapshot().getBoolean("enabled"));restored.setForOwner(selected.getLong("revision"),false,3);assertThrows(SecurityException.class,()->policy.requireCurrent(selected));assertFalse(policy.snapshot().getBoolean("enabled"));
    }
}
