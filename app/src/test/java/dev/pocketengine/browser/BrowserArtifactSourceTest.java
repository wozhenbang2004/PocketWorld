package dev.pocketengine.browser;

import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Original catalog selection and durable attempt rules, without model-provided filesystem paths. */
public final class BrowserArtifactSourceTest {
    static String id(){return UUID.randomUUID().toString();}
    static JSONObject obj(Object... values)throws Exception {JSONObject out=new JSONObject();for(int i=0;i<values.length;i+=2)out.put((String)values[i],values[i+1]);return out;}
    static JSONObject workspace(String task)throws Exception {
        return obj("id",id(),"projectId",id(),"source","task_workspace","taskId",task,"kind","text_file","path","reports/original.txt",
                "sourceVersion",3,"sourceRevisionId",id(),"sha256","a".repeat(64),"sizeBytes",5L,"mimeType","text/plain","createdAt",123L);
    }
    static JSONObject selected(JSONObject row)throws Exception {
        return obj("store","workspace","id",row.getString("id"),"sha256",row.getString("sha256"),"sourceVersion",row.getInt("sourceVersion"),"sourceRevisionId",row.getString("sourceRevisionId"));
    }
    @Test public void workspaceRequiresTheOriginalTaskRevisionAndHash()throws Exception {
        String task=id();JSONObject row=workspace(task),selection=selected(row),pin=BrowserArtifactSource.workspacePin(task,selection,row);
        assertEquals("original.txt",pin.getString("name"));assertEquals(3,pin.getInt("sourceVersion"));assertEquals(row.getString("sourceRevisionId"),pin.getString("sourceRevisionId"));
        assertEquals(selection.toString(),BrowserArtifactSource.selection(pin).toString());assertFalse(pin.has("path"));
        assertThrows(SecurityException.class,()->BrowserArtifactSource.workspacePin(id(),selection,row));
        assertThrows(SecurityException.class,()->BrowserArtifactSource.workspacePin(task,new JSONObject(selection.toString()).put("sourceVersion",4),row));
        assertThrows(SecurityException.class,()->BrowserArtifactSource.workspacePin(task,new JSONObject(selection.toString()).put("sourceRevisionId",id()),row));
        assertThrows(SecurityException.class,()->BrowserArtifactSource.workspacePin(task,new JSONObject(selection.toString()).put("sha256","b".repeat(64)),row));
        assertThrows(SecurityException.class,()->BrowserArtifactSource.workspacePin(task,selection,new JSONObject(row.toString()).put("kind","text_deleted")));
        assertThrows(SecurityException.class,()->BrowserArtifactSource.workspacePin(task,selection,new JSONObject(row.toString()).put("availability","retired")));
    }
    @Test public void attachmentCannotCrossTaskGroupRetirementOrVersionBoundaries()throws Exception {
        String task=id();JSONObject row=obj("id",id(),"sha256","a".repeat(64),"name","original.txt","mimeType","text/plain","size",5L,"createdAt",123L,
                "principalId","owner","spaceId","private","source",obj("taskId",task));
        JSONObject selection=obj("store","attachments","id",row.getString("id"),"sha256",row.getString("sha256"));
        JSONObject pin=BrowserArtifactSource.attachmentPin(task,selection,row);assertEquals("immutable_attachment_id_sha256",pin.getString("versionKind"));
        assertThrows(SecurityException.class,()->BrowserArtifactSource.attachmentPin(id(),selection,row));
        assertThrows(SecurityException.class,()->BrowserArtifactSource.attachmentPin(task,selection,new JSONObject(row.toString()).put("outputSourceRetired",true)));
        assertThrows(SecurityException.class,()->BrowserArtifactSource.attachmentPin(task,selection,new JSONObject(row.toString()).put("spaceId","group")));
        assertThrows(SecurityException.class,()->BrowserArtifactSource.attachmentPin(task,selection,new JSONObject(row.toString()).put("source",obj("taskId",task,"nativePublication",obj("taskId",id())))));
        assertThrows(SecurityException.class,()->BrowserArtifactSource.attachmentPin(task,new JSONObject(selection.toString()).put("sha256","b".repeat(64)),row));
    }
    @Test public void rawPathsMissingWorkspaceRevisionAndUnboundedBytesAreRejected()throws Exception {
        JSONObject row=workspace(id()),selection=selected(row);
        assertThrows(SecurityException.class,()->BrowserArtifactSource.validate(new JSONObject(selection.toString()).put("path","/private/secret")));
        assertThrows(SecurityException.class,()->BrowserArtifactSource.validate(new JSONObject(selection.toString()).put("uri","content://unowned/file")));
        JSONObject missing=new JSONObject(selection.toString());missing.remove("sourceRevisionId");assertThrows(Exception.class,()->BrowserArtifactSource.validate(missing));
        assertThrows(IllegalArgumentException.class,()->BrowserArtifactSource.validate(new JSONObject(selection.toString()).put("sourceVersion",3.5)));
        assertThrows(SecurityException.class,()->BrowserArtifactSource.workspacePin(row.getString("taskId"),selection,new JSONObject(row.toString()).put("sizeBytes",20L*1024*1024+1)));
    }
    @Test public void unknownDeliveryCannotBeRetriedThroughANewOperation()throws Exception {
        JSONObject identity=BrowserArtifactUploadTest.identity(),artifact=BrowserArtifactUploadTest.artifact();
        JSONObject row=BrowserUploadAttempt.create(identity,new JSONArray().put(artifact));
        row=BrowserUploadAttempt.transition(row,"delivery_entered",obj());
        JSONObject next=new JSONObject(identity.toString()).put("operationId",id()).put("chooserId",id());
        JSONArray prior=new JSONArray().put(row);
        assertThrows(SecurityException.class,()->BrowserUploadAttempt.requireNewArtifactAttempt(prior,next,artifact));
        assertEquals(7,row.getJSONObject("pin").getJSONArray("attachments").getJSONObject(0).getInt("sourceVersion"));
        assertFalse(row.getBoolean("automaticReplayAllowed"));
        JSONObject confirmed=BrowserUploadAttempt.transition(row,"site_rejected",obj("source","owner_confirmation","note","原站点明确拒绝该文件"));
        BrowserUploadAttempt.requireNewArtifactAttempt(new JSONArray().put(confirmed),next,artifact);
        assertThrows(SecurityException.class,()->BrowserUploadAttempt.requireNewArtifactAttempt(new JSONArray().put(confirmed),identity,artifact));
    }
}
