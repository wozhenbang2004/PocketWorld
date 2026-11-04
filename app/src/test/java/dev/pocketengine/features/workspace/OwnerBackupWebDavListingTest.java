package dev.pocketengine.features.workspace;

import dev.pocketengine.core.RunControl;
import java.io.*;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.junit.Test;
import static org.junit.Assert.*;

public final class OwnerBackupWebDavListingTest {
    private static final String NAME="PocketWorld-51f862bd-47b9-4300-8902-ff951409a6c9.pwbackup";
    private final OwnerBackupWebDavTarget target=new OwnerBackupWebDavTarget("https://backup.example/backups/");
    private static String response(String href,String status,String properties){return "<d:response><d:href>"+href+"</d:href><d:propstat><d:prop>"+properties+"</d:prop><d:status>"+status+"</d:status></d:propstat></d:response>";}
    private static String document(String rows){return "<?xml version=\"1.0\"?><d:multistatus xmlns:d=\"DAV:\">"+rows+"</d:multistatus>";}
    private JSONArray read(String value)throws Exception {return OwnerBackupWebDavListing.read(target,new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8)),new RunControl());}
    private static String properties(){return "<d:resourcetype/><d:getcontentlength>2048</d:getcontentlength><d:getlastmodified>Tue, 03 Nov 2026 10:00:00 GMT</d:getlastmodified>";}
    @Test public void reportsOnlyFixedVersionMetadataAndNeverClaimsVerification()throws Exception {
        JSONArray rows=read(document(response("/backups/"+NAME,"HTTP/1.1 200 OK",properties())));
        assertEquals(1,rows.length());assertEquals(NAME,rows.getJSONObject(0).getString("name"));assertEquals(2048,rows.getJSONObject(0).getLong("encryptedBytes"));assertFalse(rows.getJSONObject(0).getBoolean("verified"));
        assertFalse(rows.getJSONObject(0).has("destination"));
    }
    @Test public void ignoresForeignOriginsParentPathsUnsuccessfulPropertiesAndCollections()throws Exception {
        String rows=response("https://foreign.example/backups/"+NAME,"HTTP/1.1 200 OK",properties())
                +response("/elsewhere/"+NAME,"HTTP/1.1 200 OK",properties())+response("/backups/"+NAME,"HTTP/1.1 404 Not Found",properties())
                +response("/backups/"+NAME,"HTTP/1.1 200 OK","<d:resourcetype><d:collection/></d:resourcetype><d:getcontentlength>2048</d:getcontentlength>")
                +response("/backups/random.txt","HTTP/1.1 200 OK",properties());
        assertEquals(0,read(document(rows)).length());
    }
    @Test public void rejectsXmlEntitiesIncompleteDocumentsAndConflictingVersions(){
        String entity="<?xml version=\"1.0\"?><!DOCTYPE d:multistatus [<!ENTITY x SYSTEM 'file:///never-read-this-fixture'>]><d:multistatus xmlns:d=\"DAV:\">&x;</d:multistatus>";
        assertThrows(SecurityException.class,()->read(entity));
        assertThrows(SecurityException.class,()->read(document(response(NAME,"HTTP/1.1 200 OK",properties())).replace("</d:multistatus>","")));
        assertThrows(SecurityException.class,()->read(document(response(NAME,"HTTP/1.1 200 OK",properties())+response(NAME,"HTTP/1.1 200 OK",properties().replace("2048","4096")))));
    }
    @Test public void boundsBytesAndResponseCountWithoutReturningAPartialList(){
        assertThrows(Exception.class,()->read(document("<!--"+"x".repeat((int)OwnerBackupWebDavListing.MAX_BYTES)+"-->")));
        assertThrows(Exception.class,()->read(document(response("ignored","HTTP/1.1 404 Not Found","").repeat(1001))));
    }
    @Test public void cancellationDuringParsingNeverPublishesAlreadyReadEntries(){
        byte[] bytes=document(response(NAME,"HTTP/1.1 200 OK",properties())).getBytes(StandardCharsets.UTF_8);RunControl original=new RunControl();
        InputStream input=new ByteArrayInputStream(bytes){@Override public synchronized int read(byte[] b,int offset,int length){int count=super.read(b,offset,Math.min(64,length));original.cancel();return count;}};
        assertThrows(Exception.class,()->OwnerBackupWebDavListing.read(target,input,original));
    }
}
