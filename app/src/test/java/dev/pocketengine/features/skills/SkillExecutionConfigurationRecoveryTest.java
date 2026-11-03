package dev.pocketengine.features.skills;

import static org.junit.Assert.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.json.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

/** Actual private-file/configuration primitives, not a task grant or Android sandbox. */
public final class SkillExecutionConfigurationRecoveryTest {
    @Rule public final TemporaryFolder temp=new TemporaryFolder();
    private File file()throws Exception{return new File(temp.newFolder(),"skill-execution.json");}
    private static JSONObject configuration()throws Exception{return ManagedSkillPolicyTest.configuration();}
    private static void damage(File file,byte[] bytes)throws Exception{Files.write(file.toPath(),bytes);}
    private static File backup(File file,byte[] bytes)throws Exception{return new File(new File(file.getParentFile(),file.getName()+".recovery"),SkillExecution.sha256(bytes)+".bin");}

    @Test public void damagedOptionalConfigurationIsVisibleFailClosedAndRetainedUntilExplicitReview()throws Exception {
        File file=file();byte[] broken="{\"revision\":7,\"enabled\":true,PRIVATE-CORRUPT-BODY".getBytes(StandardCharsets.UTF_8);damage(file,broken);SkillExecutionConfiguration host=new SkillExecutionConfiguration(file);
        JSONObject state=host.snapshot();assertFalse(state.getBoolean("configured"));assertTrue(state.getBoolean("recoveryRequired"));assertTrue(state.getBoolean("recoveryAvailable"));assertFalse(state.getBoolean("revisionKnown"));assertFalse(state.toString().contains("PRIVATE"));assertArrayEquals(broken,Files.readAllBytes(file.toPath()));
        assertThrows(SkillExecution.Unavailable.class,host::current);var review=host.inspectPhone(configuration());JSONObject recovery=review.view().getJSONObject("configurationRecovery");assertTrue(recovery.getBoolean("required"));assertEquals(SkillExecution.sha256(broken),recovery.getString("originalSha256"));assertEquals(broken.length,recovery.getLong("originalBytes"));assertFalse(backup(file,broken).exists());
        assertThrows(SecurityException.class,()->host.install(review,"f".repeat(64)));assertArrayEquals(broken,Files.readAllBytes(file.toPath()));
        var current=host.install(review,review.digest);assertTrue(current.phone());assertTrue(host.snapshot().getBoolean("configured"));assertArrayEquals(broken,Files.readAllBytes(backup(file,broken).toPath()));assertThrows(SecurityException.class,()->host.install(review,review.digest));
    }
    @Test public void changedDamagedBytesInvalidateTheExactNativeReviewWithoutOverwritingThem()throws Exception {
        File file=file();byte[] before="{broken-before".getBytes(StandardCharsets.UTF_8),after="{broken-after".getBytes(StandardCharsets.UTF_8);damage(file,before);SkillExecutionConfiguration host=new SkillExecutionConfiguration(file);var review=host.inspectPhone(configuration());damage(file,after);
        assertThrows(IllegalStateException.class,()->host.install(review,review.digest));assertArrayEquals(after,Files.readAllBytes(file.toPath()));assertFalse(backup(file,before).exists());assertFalse(host.snapshot().getBoolean("configured"));
    }
    @Test public void recoveryWithSameRevisionAndConfigurationCannotReviveOldSelection()throws Exception {
        File file=file();SkillExecutionConfiguration host=new SkillExecutionConfiguration(file);var firstReview=host.inspectPhone(configuration());var old=host.install(firstReview,firstReview.digest);assertEquals(1,old.revision);host.check(old);
        byte[] broken="{lost revision".getBytes(StandardCharsets.UTF_8);damage(file,broken);assertThrows(SkillExecution.Unavailable.class,()->host.check(old));var review=host.inspectPhone(configuration());var restored=host.install(review,review.digest);
        assertEquals(old.revision,restored.revision);assertEquals(old.digest,restored.digest);assertThrows(SecurityException.class,()->host.check(old));host.check(restored);new SkillExecutionConfiguration(file).check(restored);assertArrayEquals(broken,Files.readAllBytes(backup(file,broken).toPath()));
    }
    @Test public void validLegacyStateIsNotRewrittenAndExactBytesStillFenceStaleSelections()throws Exception {
        File file=file();JSONObject legacy=new JSONObject().put("revision",9).put("enabled",true).put("configuration",configuration());byte[] original=legacy.toString().getBytes(StandardCharsets.UTF_8);damage(file,original);SkillExecutionConfiguration host=new SkillExecutionConfiguration(file);var selected=host.current();assertTrue(host.snapshot().getBoolean("configured"));host.check(selected);assertArrayEquals(original,Files.readAllBytes(file.toPath()));
        // Same public revision/config digest, but a different actual installed state is not the original selection.
        damage(file,(legacy+"\n").getBytes(StandardCharsets.UTF_8));assertThrows(SecurityException.class,()->host.check(selected));assertEquals(selected.revision,host.current().revision);
    }
    @Test public void oldDisableObservationAndLegacyRevisionCannotAbaTheRecoveredConfiguration()throws Exception {
        File file=file();SkillExecutionConfiguration host=new SkillExecutionConfiguration(file);var first=host.inspectPhone(configuration());host.install(first,first.digest);JSONObject old=host.snapshot();damage(file,"{damaged".getBytes(StandardCharsets.UTF_8));var repair=host.inspectPhone(configuration());host.install(repair,repair.digest);JSONObject fresh=host.snapshot();assertEquals(old.getLong("revision"),fresh.getLong("revision"));assertNotEquals(old.getString("stateFingerprint"),fresh.getString("stateFingerprint"));
        assertThrows(SecurityException.class,()->host.disable(old.getLong("revision"),old.getString("stateFingerprint")));assertTrue(host.snapshot().getBoolean("configured"));assertThrows(SecurityException.class,()->host.disable(fresh.getLong("revision")));assertTrue(host.snapshot().getBoolean("configured"));host.disable(fresh.getLong("revision"),fresh.getString("stateFingerprint"));assertFalse(host.snapshot().getBoolean("configured"));
    }
    @Test public void malformedUtf8AndInvalidEnabledStateCanBeReviewedButNeverActivateThemselves()throws Exception {
        for(byte[] bytes:new byte[][]{new byte[]{(byte)0xff},"{\"revision\":1,\"enabled\":true}".getBytes(StandardCharsets.UTF_8),"{\"revision\":\"1\",\"enabled\":false}".getBytes(StandardCharsets.UTF_8)}){
            File file=file();damage(file,bytes);SkillExecutionConfiguration host=new SkillExecutionConfiguration(file);assertFalse(host.snapshot().getBoolean("configured"));assertTrue(host.snapshot().getBoolean("recoveryRequired"));assertThrows(SkillExecution.Unavailable.class,host::current);var review=host.inspectPhone(configuration());host.install(review,review.digest);assertArrayEquals(bytes,Files.readAllBytes(backup(file,bytes).toPath()));
        }
    }
    @Test public void recoveryBackupCollisionOrSourceLinkNeverReplacesOriginalOrOtherBytes()throws Exception {
        File file=file();byte[] broken="{private original".getBytes(StandardCharsets.UTF_8);damage(file,broken);File backup=backup(file,broken);assertTrue(backup.getParentFile().mkdir());byte[] foreign="different retained bytes".getBytes(StandardCharsets.UTF_8);damage(backup,foreign);SkillExecutionConfiguration host=new SkillExecutionConfiguration(file);var review=host.inspectPhone(configuration());assertThrows(Exception.class,()->host.install(review,review.digest));assertArrayEquals(broken,Files.readAllBytes(file.toPath()));assertArrayEquals(foreign,Files.readAllBytes(backup.toPath()));
        File target=new File(temp.newFolder(),"original-other.json");byte[] original=configuration().toString().getBytes(StandardCharsets.UTF_8);damage(target,original);File link=new File(temp.newFolder(),"skill-execution.json");Files.createSymbolicLink(link.toPath(),target.toPath());SkillExecutionConfiguration linked=new SkillExecutionConfiguration(link);assertFalse(linked.snapshot().getBoolean("configured"));assertFalse(linked.snapshot().getBoolean("recoveryAvailable"));assertThrows(Exception.class,()->linked.inspectPhone(configuration()));assertArrayEquals(original,Files.readAllBytes(target.toPath()));
    }
}
