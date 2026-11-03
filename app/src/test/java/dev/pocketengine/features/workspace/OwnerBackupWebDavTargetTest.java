package dev.pocketengine.features.workspace;

import org.junit.Test;
import static org.junit.Assert.*;

public final class OwnerBackupWebDavTargetTest {
    private static final String ID="51f862bd-47b9-4300-8902-ff951409a6c9";
    @Test public void canonicalHttpsDirectoryCreatesOneVersionWithoutChangingItsParent(){
        var target=new OwnerBackupWebDavTarget("https://BACKUP.example:443/owner%20backup/");
        assertEquals("https://backup.example/owner%20backup/PocketWorld-"+ID+".pwbackup",target.destination(ID));
        assertEquals(target.identity(),new OwnerBackupWebDavTarget(target.collection).identity());
    }
    @Test public void rejectsUrlCredentialsRedirectMaterialAndEscapingPaths(){
        for(String value:new String[]{"http://backup.example/backups/","https://owner:secret@backup.example/backups/","https://backup.example/backups/?token=fixture","https://backup.example/backups/#anchor","https://backup.example/backups","https://backup.example/a/../backups/","https://backup.example/a/%2e%2e/","https://backup.example/a/%252e%252e/","https://backup.example/a/%2f/","https://backup.example/a/%5C/","https://backup.example//backups/","https://backup.example/%00/","https://backup.example/%FF/","https://backup.example:0/backups/"})
            assertThrows(value,IllegalArgumentException.class,()->new OwnerBackupWebDavTarget(value));
    }
    @Test public void fileNamesCannotChooseAnotherResource(){
        var target=new OwnerBackupWebDavTarget("https://backup.example/backups/");
        for(String name:new String[]{"../private.pwbackup","other.zip","https://other.example/backup.pwbackup","PocketWorld-"+ID+".pwbackup?token=fixture","PocketWorld-"+ID+".pwbackup/","PocketWorld-%2e%2e.pwbackup"})assertThrows(IllegalArgumentException.class,()->target.file(name));
        assertThrows(SecurityException.class,()->target.destination("../escape"));
    }
    @Test public void serverHrefsAreOnlyAcceptedAsExactDirectChildren(){
        var target=new OwnerBackupWebDavTarget("https://backup.example/backups/");String name="PocketWorld-"+ID+".pwbackup";
        assertEquals(name,target.childName(name));assertEquals(name,target.childName("/backups/"+name));assertEquals(name,target.childName("https://BACKUP.example:443/backups/"+name));
        for(String href:new String[]{"https://other.example/backups/"+name,"http://backup.example/backups/"+name,"/other/"+name,"nested/"+name,"/backups/"+name+"?secret=fixture","/backups/%50ocketWorld-"+ID+".pwbackup","https://owner@backup.example/backups/"+name})assertNull(href,target.childName(href));
    }
    @Test public void differentDirectoriesRemainDifferentTargets(){
        assertNotEquals(new OwnerBackupWebDavTarget("https://backup.example/one/").identity(),new OwnerBackupWebDavTarget("https://backup.example/two/").identity());
        assertNotEquals(new OwnerBackupWebDavTarget("https://backup.example:8443/one/").identity(),new OwnerBackupWebDavTarget("https://backup.example/one/").identity());
    }
}
