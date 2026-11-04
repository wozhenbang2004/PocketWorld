package dev.pocketengine.features.workspace;

import dev.pocketengine.features.controlplane.WorkProfile;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class OwnerConfigurationBackupSectionsTest {
    @Test public void portableEnvelopeRejectsCoercedVersionPermissionsAndUnknownFields()throws Exception {
        JSONObject value=OwnerConfigurationBackupSections.create("skills",new JSONObject().put("packages",new JSONArray()),new JSONArray());
        assertEquals(1,OwnerConfigurationBackupSections.checked("skills",value).getInt("version"));
        assertThrows(SecurityException.class,()->OwnerConfigurationBackupSections.checked("skills",new JSONObject(value.toString()).put("version","1")));
        assertThrows(SecurityException.class,()->OwnerConfigurationBackupSections.checked("skills",new JSONObject(value.toString()).put("version",1.0)));
        assertThrows(SecurityException.class,()->OwnerConfigurationBackupSections.checked("skills",new JSONObject(value.toString()).put("activePermissionsIncluded",true)));
        assertThrows(SecurityException.class,()->OwnerConfigurationBackupSections.checked("skills",new JSONObject(value.toString()).put("apiKey","must-not-be-accepted")));
        JSONObject changed=new JSONObject(value.toString());changed.getJSONObject("content").put("packages",new JSONArray().put("changed"));
        assertThrows(SecurityException.class,()->OwnerConfigurationBackupSections.checked("skills",changed));
    }
    @Test public void pendingProfileIsReadableButCannotBeExecutedAndHistoricalReferencesStayStrict()throws Exception {
        JSONObject reference=new JSONObject().put("kind","ssh").put("profileId","old-ssh").put("bindingHash","a".repeat(64)).put("optional",false);
        JSONObject restored=WorkProfile.definition("work-restored",1,"换机工作配置").put("restoredPendingReview",true)
                .put("restoredReferences",new JSONArray().put(new JSONObject().put("kind","targetBindings").put("reference",reference)));
        WorkProfile pending=WorkProfile.fromJson(restored);assertEquals(0,pending.toJson().getJSONArray("targetBindings").length());
        assertThrows(SecurityException.class,pending::requireExecutable);assertTrue(pending.toJson().getBoolean("restoredPendingReview"));
        reference.put("privateKey","must-not-be-retained");
        assertThrows(IllegalArgumentException.class,()->WorkProfile.fromJson(restored));
    }
    @Test public void nativeRestoreIdentityIsStableOnlyWithinTheOriginalActionAndKind()throws Exception {
        String first="12345678-1234-1234-1234-123456789abc",second="87654321-1234-1234-1234-123456789abc";
        assertEquals(OwnerConfigurationBackupSections.originalId("skills",first,"source"),OwnerConfigurationBackupSections.originalId("skills",first,"source"));
        assertNotEquals(OwnerConfigurationBackupSections.originalId("skills",first,"source"),OwnerConfigurationBackupSections.originalId("schedules",first,"source"));
        assertNotEquals(OwnerConfigurationBackupSections.originalId("skills",first,"source"),OwnerConfigurationBackupSections.originalId("skills",second,"source"));
        assertThrows(SecurityException.class,()->OwnerConfigurationBackupSections.originalId("skills","-".repeat(36),"source"));
    }
}
