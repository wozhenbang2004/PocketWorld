package dev.pocketengine.features.plugins.distribution;

import android.app.Application;
import java.io.ByteArrayInputStream;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONArray;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Selection comparison exposes only the frozen actions, never native approval or mutable review data. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class FunctionalPackReviewSelectionTest {
    @Test public void frozenSelectionCannotBeRewrittenByCheckboxEditsOrViewMutation()throws Exception {
        try(var f=new FunctionalPackNativeJourneyTest.Fixture();var page=f.page()){
            var full=page.inspect(new ByteArrayInputStream(FunctionalPackNativeJourneyTest.packageBytes("selection",false)));
            Set<String> all=Set.of("data","transform","method"),subset=Set.of("data","transform");
            assertTrue(full.ready);assertTrue(full.matchesSelection(all));assertFalse(full.matchesSelection(subset));
            assertFalse(full.matchesSelection(Set.of("data","transform","unknown")));assertFalse(full.matchesSelection(Set.of()));assertFalse(full.matchesSelection(null));
            full.view().getJSONArray("components").getJSONObject(0).put("selected",false);
            assertTrue("Display JSON cannot change a frozen review",full.matchesSelection(all));
            var edited=new HashSet<>(all);edited.remove("method");assertFalse(full.matchesSelection(edited));edited.add("method");assertTrue(full.matchesSelection(edited));
            var selected=page.selectComponents(full,new JSONArray().put("data").put("transform"));
            assertTrue(selected.ready);assertTrue(selected.matchesSelection(subset));assertFalse(selected.matchesSelection(all));
            assertTrue("The old read-only comparison is not native authority",full.matchesSelection(all));
            assertThrows(SecurityException.class,()->page.submit(full,full.digest));
            assertThrows(SecurityException.class,()->page.submit(selected,full.digest));
            page.setForeground(false);assertThrows(SecurityException.class,()->page.submit(selected,selected.digest));assertEquals(0,f.tasks.list().length());
            page.setForeground(true);assertEquals("partial",page.submit(selected,selected.digest).getString("status"));
            assertEquals(0,f.skills.snapshot().getJSONArray("skills").length());assertEquals(1,f.plugins.snapshot().getJSONArray("plugins").length());
            assertThrows(SecurityException.class,()->page.submit(selected,selected.digest));
        }
    }
    @Test public void matchingSelectionsDoNotBypassDependencyRefusalOrClosedPages()throws Exception {
        try(var f=new FunctionalPackNativeJourneyTest.Fixture();var page=f.page()){
            var initial=page.inspect(new ByteArrayInputStream(FunctionalPackNativeJourneyTest.packageBytes("dependency",true)));
            assertFalse(initial.ready);assertTrue(initial.matchesSelection(Set.of("transform","method")));
            var invalid=page.selectComponents(initial,new JSONArray().put("method").put("transform"));
            assertTrue(invalid.matchesSelection(Set.of("transform","method")));assertFalse(invalid.ready);
            assertThrows(SecurityException.class,()->page.submit(invalid,invalid.digest));
            var selected=page.selectComponents(invalid,new JSONArray().put("transform"));assertTrue(selected.ready);assertTrue(selected.matchesSelection(Set.of("transform")));
            page.close();assertThrows(SecurityException.class,()->page.submit(selected,selected.digest));assertEquals(0,f.tasks.list().length());
        }
    }
}
