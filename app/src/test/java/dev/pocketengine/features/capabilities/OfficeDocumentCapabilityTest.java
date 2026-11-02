package dev.pocketengine.features.capabilities;

import static org.junit.Assert.*;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public final class OfficeDocumentCapabilityTest {
    private static JSONObject workbook()throws Exception{return new JSONObject().put("format","xlsx").put("workbook",new JSONObject().put("version",1).put("sheets",new JSONArray().put(new JSONObject().put("name","Inputs").put("cells",new JSONArray().put(new JSONObject().put("ref","A1").put("number",12))))));}
    @Test public void existingNativeDocumentToolAdmitsStructuredOfficeBeforeReviewWithCompleteBoundedTypes()throws Exception {
        var descriptor=CapabilityDescriptor.createDocument();var result=descriptor.normalize(workbook());assertEquals("xlsx",result.getString("format"));assertTrue(descriptor.metadata().getBoolean("requiresApproval"));assertTrue(descriptor.metadata().getBoolean("journalRequired"));
        var slide=new JSONObject().put("id","start").put("title","标题").put("notes","讲稿").put("shapes",new JSONArray().put(new JSONObject().put("id","detail").put("text","内容").put("x",1).put("y",2).put("width",3).put("height",1)));
        assertEquals("pptx",descriptor.normalize(new JSONObject().put("format","pptx").put("presentation",new JSONObject().put("version",1).put("slides",new JSONArray().put(slide)))).getString("format"));
        JSONObject redacted=descriptor.redactedArguments(workbook());assertEquals("[已隐藏]",redacted.getString("workbook"));
    }
    @Test public void schemaAndFormatSpecificGateRejectMalformedBodiesTypesAndHostIdentityBeforeApproval()throws Exception {
        var descriptor=CapabilityDescriptor.createDocument();assertThrows(Exception.class,()->descriptor.normalize(new JSONObject().put("format","docx")));assertThrows(Exception.class,()->descriptor.normalize(new JSONObject().put("format","pdf").put("body","")));
        JSONObject wrong=workbook();wrong.getJSONObject("workbook").getJSONArray("sheets").getJSONObject(0).getJSONArray("cells").getJSONObject(0).put("number","12");assertThrows(Exception.class,()->descriptor.normalize(wrong));
        JSONObject extra=workbook();extra.getJSONObject("workbook").put("script","untrusted");assertThrows(Exception.class,()->descriptor.normalize(extra));assertThrows(SecurityException.class,()->descriptor.normalize(workbook().put("taskId","forged")));
        JSONObject both=workbook();both.getJSONObject("workbook").getJSONArray("sheets").getJSONObject(0).getJSONArray("cells").getJSONObject(0).put("text","other");assertThrows(Exception.class,()->descriptor.normalize(both));
        assertEquals("docx",descriptor.normalize(new JSONObject().put("format","docx").put("body","# 原有标题\n正文")).getString("format"));
    }
    @Test public void originalToolRejectsPartialParentAndEditableReferencesBeforeApproval()throws Exception {
        var descriptor=CapabilityDescriptor.createDocument();String parent="00000000-0000-0000-0000-000000000001",source="00000000-0000-0000-0000-000000000002";
        var inputs=java.util.List.of(new JSONObject().put("format","docx").put("body","正文"),new JSONObject().put("format","pdf").put("body","正文"),workbook(),
                new JSONObject().put("format","pptx").put("presentation",new JSONObject().put("version",1).put("slides",new JSONArray().put(new JSONObject().put("id","first").put("title","标题")))));
        for(var input:inputs){
            var parentOnly=new JSONObject(input.toString()).put("parentArtifactId",parent).put("parentSha256","a".repeat(64));
            var sourceOnly=new JSONObject(input.toString()).put("editableSourceId",source).put("editableSourceSha256","b".repeat(64));
            assertThrows(SecurityException.class,()->descriptor.normalize(parentOnly));assertThrows(SecurityException.class,()->descriptor.normalize(sourceOnly));
            assertEquals(input.getString("format"),descriptor.normalize(new JSONObject(parentOnly.toString()).put("editableSourceId",source).put("editableSourceSha256","b".repeat(64))).getString("format"));
        }
    }
}
