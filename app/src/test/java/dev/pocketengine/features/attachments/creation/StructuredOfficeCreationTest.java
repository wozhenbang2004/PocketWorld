package dev.pocketengine.features.attachments.creation;

import static org.junit.Assert.*;
import dev.pocketengine.core.RunControl;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public final class StructuredOfficeCreationTest {
    private static JSONObject cell(String ref,String field,Object value)throws Exception{return new JSONObject().put("ref",ref).put(field,value);}
    private static JSONObject sheet(String name,JSONObject... cells)throws Exception{return new JSONObject().put("name",name).put("cells",new JSONArray(cells));}
    static JSONObject workbook(int amount)throws Exception{return new JSONObject().put("format","xlsx").put("name","经营模型").put("workbook",new JSONObject().put("version",1).put("sheets",new JSONArray()
            .put(sheet("假设",cell("A1","number",amount),cell("A2","number",20),cell("B1","text","中文 & <金额> 😀"),cell("C1","boolean",true)))
            .put(sheet("Calc",cell("A1","formula","='假设'!A1*2"),cell("A2","formula","SUM('假设'!A1:A2)"),cell("B1","formula","AVERAGE(A1:A2)"),cell("C1","formula","(A1+A2)/2").put("numberFormat","decimal")))));}
    static JSONObject deck()throws Exception{return new JSONObject().put("format","pptx").put("name","演示").put("presentation",new JSONObject().put("version",1).put("slides",new JSONArray()
            .put(new JSONObject().put("id","opening").put("title","第一章 <真实> & 可编辑 😀").put("paragraphs",new JSONArray(new String[]{"第一点","第二点"})).put("notes","讲稿 $100\n保留 & <字符>\n").put("shapes",new JSONArray().put(new JSONObject().put("id","annotation").put("text","定位文本").put("x",1).put("y",6.9).put("width",4).put("height",0.4))))
            .put(new JSONObject().put("id","closing").put("title","结束").put("notes","下一步"))));}
    private static Map<String,String> xml(byte[] bytes)throws Exception{Map<String,String> out=new LinkedHashMap<>();try(var zip=new ZipInputStream(new ByteArrayInputStream(bytes),StandardCharsets.UTF_8)){for(ZipEntry e;(e=zip.getNextEntry())!=null;)out.put(e.getName(),new String(zip.readAllBytes(),StandardCharsets.UTF_8));}return out;}
    @Test public void actualWorkbookRetainsFormulasAndRecalculatesCrossSheetDependenciesAfterInputChange()throws Exception {
        var control=new RunControl();var draft=new DocumentDraft(workbook(100));byte[] bytes=SpreadsheetDocumentWriter.write(draft,control);var parts=xml(bytes);
        assertTrue(parts.get("xl/worksheets/sheet2.xml").contains("<f>&apos;假设&apos;!A1*2</f><v>200</v>"));
        assertTrue(parts.get("xl/worksheets/sheet2.xml").contains("<f>SUM(&apos;假设&apos;!A1:A2)</f><v>120</v>"));
        assertTrue(parts.get("xl/worksheets/sheet2.xml").contains("<v>160</v>"));
        var check=SpreadsheetDocumentWriter.check(bytes,draft,control);assertEquals("current",check.getJSONObject("calculation").getString("calcStatus"));assertEquals(4,check.getJSONObject("calculation").getInt("currentFormulaCount"));assertFalse(check.getBoolean("visualLayoutVerified"));
        byte[] edited=SpreadsheetDocumentWriter.write(new DocumentDraft(workbook(120)),control);assertTrue(xml(edited).get("xl/worksheets/sheet2.xml").contains("<v>190</v>"));assertFalse(Arrays.equals(bytes,edited));
    }
    @Test public void unsupportedExternalCircularBrokenAndNumericErrorsHaveNoFabricatedZeroCache()throws Exception {
        var input=new JSONObject().put("format","xlsx").put("workbook",new JSONObject().put("version",1).put("sheets",new JSONArray().put(sheet("Sheet1",
                cell("A1","formula","NO_SUCH_FN(1)"),cell("A2","formula","[other.xlsx]Sheet1!A1"),cell("A3","formula","A4"),cell("A4","formula","A3"),cell("A5","formula","Z99+1"),cell("A6","formula","1/0")))));
        var draft=new DocumentDraft(input);var control=new RunControl();byte[] bytes=SpreadsheetDocumentWriter.write(draft,control);var result=SpreadsheetDocumentWriter.check(bytes,draft,control).getJSONObject("calculation");
        assertEquals("recalc_required",result.getString("calcStatus"));assertEquals(6,result.getJSONArray("issues").length());assertEquals(0,result.getInt("currentFormulaCount"));
        String sheet=xml(bytes).get("xl/worksheets/sheet1.xml");assertFalse(sheet.contains("<v>"));assertFalse(sheet.contains("<f>"));assertTrue(sheet.contains("未计算公式：=1/0"));
        Set<String> codes=new HashSet<>();for(int i=0;i<result.getJSONArray("issues").length();i++)codes.add(result.getJSONArray("issues").getJSONObject(i).getString("code"));
        assertTrue(codes.containsAll(Set.of("unsupported_function","external_reference_unsupported","circular_reference","missing_reference","division_by_zero")));
    }
    @Test public void unsupportedFunctionsAndExternalFormulasAreLiteralTextInTheExportedWorkbook()throws Exception {
        String external="WEBSERVICE(\"https://example.invalid/office-probe\")";
        var input=new JSONObject().put("format","xlsx").put("workbook",new JSONObject().put("version",1).put("sheets",new JSONArray().put(sheet("Sheet1",
                cell("A1","formula",external),cell("A2","formula","NO_SUCH_FN(1)"),cell("A3","formula","'[other.xlsx]Sheet1'!A1"),
                cell("A4","formula","cmd|' /C calc'!A1"),cell("A5","formula","A1+1"),cell("A6","formula","SUM(1,2)"),
                cell("A7","text","="+external),cell("A8","formula","_xlfn.WEBSERVICE(\"file:///etc/passwd\")")))));
        var draft=new DocumentDraft(input);var control=new RunControl();byte[] bytes=SpreadsheetDocumentWriter.write(draft,control);
        var factory=javax.xml.parsers.DocumentBuilderFactory.newInstance();factory.setNamespaceAware(true);factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
        var document=factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml(bytes).get("xl/worksheets/sheet1.xml").getBytes(StandardCharsets.UTF_8)));
        String ns="http://schemas.openxmlformats.org/spreadsheetml/2006/main";
        assertEquals("Only the fully supported local formula may be executable in the actual output",1,document.getElementsByTagNameNS(ns,"f").getLength());
        var cells=document.getElementsByTagNameNS(ns,"c");
        for(int i=0;i<cells.getLength();i++){
            var cell=(org.w3c.dom.Element)cells.item(i);String ref=cell.getAttribute("r");
            if(ref.equals("A6")){assertEquals("SUM(1,2)",cell.getElementsByTagNameNS(ns,"f").item(0).getTextContent());continue;}
            assertEquals("inlineStr",cell.getAttribute("t"));assertEquals(0,cell.getElementsByTagNameNS(ns,"f").getLength());
            String value=cell.getElementsByTagNameNS(ns,"t").item(0).getTextContent();assertTrue(ref.equals("A7")?value.equals("="+external):value.startsWith("未计算公式：="));
        }
        assertEquals(external,draft.source().getJSONObject("workbook").getJSONArray("sheets").getJSONObject(0).getJSONArray("cells").getJSONObject(0).getString("formula"));
        var calculation=SpreadsheetDocumentWriter.check(bytes,draft,control).getJSONObject("calculation");assertEquals(6,calculation.getInt("literalFormulaCount"));assertEquals(1,calculation.getInt("activeFormulaCount"));
        for(int i=0;i<calculation.getJSONArray("issues").length();i++){var issue=calculation.getJSONArray("issues").getJSONObject(i);assertFalse(issue.getBoolean("formulaWritten"));assertEquals("literal_text",issue.getString("outputType"));}
    }
    @Test public void presentationHasEditableObjectsNotesAndRealLayoutRelationships()throws Exception {
        var control=new RunControl();var draft=new DocumentDraft(deck());byte[] bytes=PresentationDocumentWriter.write(draft,control);var parts=xml(bytes);
        assertTrue(parts.containsKey("ppt/slides/slide1.xml"));assertTrue(parts.containsKey("ppt/slideLayouts/slideLayout1.xml"));assertTrue(parts.containsKey("ppt/slideMasters/slideMaster1.xml"));
        assertTrue(parts.get("ppt/slides/slide1.xml").contains("name=\"annotation\""));assertTrue(parts.get("ppt/slides/_rels/slide1.xml.rels").contains("notesSlide1.xml"));
        assertEquals(List.of("讲稿 $100","保留 & <字符>",""),OfficePackage.drawingParagraphs(bytes,"ppt/notesSlides/notesSlide1.xml",control));
        var check=PresentationDocumentWriter.check(bytes,draft,control);assertEquals(2,check.getInt("slideCount"));assertTrue(check.getBoolean("speakerNotesReadBack"));assertFalse(check.getBoolean("visualLayoutVerified"));assertFalse(check.getBoolean("animationVerified"));
    }
    @Test public void malformedOrUnimplementedSourceIsRejectedBeforeAnyArtifactIsPublished()throws Exception {
        assertThrows(Exception.class,()->new DocumentDraft(new JSONObject().put("format","docx")));
        assertThrows(Exception.class,()->new DocumentDraft(workbook(100).put("body","fake workbook")));
        JSONObject both=workbook(100);both.getJSONObject("workbook").getJSONArray("sheets").getJSONObject(0).getJSONArray("cells").getJSONObject(0).put("formula","1+1");assertThrows(Exception.class,()->new DocumentDraft(both));
        JSONObject cache=workbook(100);cache.getJSONObject("workbook").getJSONArray("sheets").getJSONObject(0).getJSONArray("cells").getJSONObject(0).put("cachedValue",999);assertThrows(Exception.class,()->new DocumentDraft(cache));
        JSONObject duplicate=workbook(100);duplicate.getJSONObject("workbook").getJSONArray("sheets").getJSONObject(1).put("name","假设");assertThrows(Exception.class,()->new DocumentDraft(duplicate));
        JSONObject chart=deck();chart.getJSONObject("presentation").getJSONArray("slides").getJSONObject(0).put("chart",new JSONObject());assertThrows(Exception.class,()->new DocumentDraft(chart));
        JSONObject bounds=deck();bounds.getJSONObject("presentation").getJSONArray("slides").getJSONObject(0).getJSONArray("shapes").getJSONObject(0).put("x",13);assertThrows(Exception.class,()->new DocumentDraft(bounds));
        var stopped=new RunControl();stopped.cancel();assertThrows(InterruptedException.class,()->SpreadsheetDocumentWriter.write(new DocumentDraft(workbook(100)),stopped));
    }
    @Test public void parentAndEditableSourceMustBothBePresentBeforeReviewForEveryFormat()throws Exception {
        var inputs=List.of(new JSONObject().put("format","docx").put("body","正文"),new JSONObject().put("format","pdf").put("body","正文"),workbook(100),deck());
        String parent=UUID.randomUUID().toString(),editable=UUID.randomUUID().toString(),sha="a".repeat(64),editableSha="b".repeat(64);
        for(var input:inputs){
            assertNotNull(new DocumentDraft(input));
            var parentOnly=new JSONObject(input.toString()).put("parentArtifactId",parent).put("parentSha256",sha);
            var sourceOnly=new JSONObject(input.toString()).put("editableSourceId",editable).put("editableSourceSha256",editableSha);
            assertThrows("parent-only "+input.getString("format"),SecurityException.class,()->new DocumentDraft(parentOnly));
            assertThrows("editable-only "+input.getString("format"),SecurityException.class,()->new DocumentDraft(sourceOnly));
            var complete=new JSONObject(parentOnly.toString()).put("editableSourceId",editable).put("editableSourceSha256",editableSha);
            assertNotNull(new DocumentDraft(complete));
        }
    }
    @Test public void wordHeadingsAreNamedEditableStyles()throws Exception {
        var draft=new DocumentDraft(new JSONObject().put("format","docx").put("title","报告").put("body","## 小节\n段落\n名称\t金额\n项目\t120"));var bytes=WordDocumentWriter.write(draft,new RunControl());var parts=xml(bytes);
        assertTrue(parts.get("word/document.xml").contains("w:pStyle w:val=\"Heading2\""));assertTrue(parts.get("word/styles.xml").contains("w:styleId=\"Heading2\""));assertTrue(parts.get("word/_rels/document.xml.rels").contains("/styles"));
    }
    @Test public void legacyDocumentCanonicalDigestRemainsCompatibleAndIndependentOfInputFieldOrder()throws Exception {
        var original=new DocumentDraft(new JSONObject().put("format","docx").put("body","中文 😀 & </data>\n项目\t1.25").put("name","原稿").put("title","报告"));
        // Fixed original ProjectSources canonical JSON/SHA-256 vector, including JSONObject's
        // escaped closing slash, UTF-8 supplementary text, default font and legacy sourceFormat.
        assertEquals("d37a089d141043acd9cea4ac3553a28d39195d1c1331c02a03951ae61d193019",original.identity);
        var reordered=new DocumentDraft(new JSONObject().put("title","报告").put("name","原稿").put("fontPoints",12).put("body","中文 😀 & </data>\n项目\t1.25").put("format","docx"));assertEquals(original.identity,reordered.identity);
    }
}
