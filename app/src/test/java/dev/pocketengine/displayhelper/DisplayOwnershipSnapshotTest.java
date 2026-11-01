package dev.pocketengine.displayhelper;
import static org.junit.Assert.*;
import org.junit.Test;
public final class DisplayOwnershipSnapshotTest {
 @Test public void resumedChooserOnOwnedDisplayKeepsExactPackageAndTask()throws Exception {
  var result=DisplayOwnershipSnapshot.parse("Display #0 (activities from top to bottom):\n mResumedActivity: ActivityRecord{0 u0 owner.reader/.Main t11}\nDisplay #5 (activities from top to bottom):\n mResumedActivity: ActivityRecord{1 u0 com.android.intentresolver/.ChooserActivity t22}\n",5);
  assertEquals("observed",result.getString("status"));assertEquals("com.android.intentresolver",result.getJSONArray("owned").getJSONObject(0).getString("packageName"));assertEquals(22,result.getJSONArray("owned").getJSONObject(0).getInt("taskId"));assertEquals(11,result.getJSONArray("primary").getJSONObject(0).getInt("taskId"));assertFalse(result.getBoolean("taskMigrationPerformed"));
 }
 @Test public void missingUnsupportedOrEmptyDisplayNeverInventsKnownOwnership()throws Exception {for(String dump:new String[]{"unsupported dump", "Display #0\n mResumedActivity: ActivityRecord{0 u0 owner.reader/.Main t11}\nDisplay #5\n mResumedActivity: null"}){var result=DisplayOwnershipSnapshot.parse(dump,5);assertEquals("unknown",result.getString("status"));assertEquals(0,result.getJSONArray("owned").length());}}
}
