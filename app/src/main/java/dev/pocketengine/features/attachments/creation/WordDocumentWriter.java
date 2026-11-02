package dev.pocketengine.features.attachments.creation;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.attachments.documents.DocumentReader;
import dev.pocketengine.features.attachments.documents.DocumentContent;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.zip.*;
import org.json.JSONObject;

/** Minimal non-macro ECMA-376 WordprocessingML using the JDK's real ZIP writer and the app's
 * existing independent OOXML reader as a structural/content check. No project code or templates. */
public final class WordDocumentWriter {
    private WordDocumentWriter(){}
    public static byte[] write(DocumentDraft draft,RunControl control)throws Exception{
        control.check();StringBuilder body=new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>");
        boolean table=false;int columns=0;
        for(var block:draft.blocks){control.check();
            if(block.table()){
                if(table&&columns!=block.cells.size()){body.append("</w:tbl>");table=false;}
                if(!table){columns=block.cells.size();body.append("<w:tbl><w:tblPr><w:tblW w:w=\"0\" w:type=\"auto\"/><w:tblBorders><w:top w:val=\"single\" w:sz=\"4\"/><w:left w:val=\"single\" w:sz=\"4\"/><w:bottom w:val=\"single\" w:sz=\"4\"/><w:right w:val=\"single\" w:sz=\"4\"/><w:insideH w:val=\"single\" w:sz=\"4\"/><w:insideV w:val=\"single\" w:sz=\"4\"/></w:tblBorders></w:tblPr><w:tblGrid>");for(int i=0;i<columns;i++)body.append("<w:gridCol w:w=\"").append(9960/columns).append("\"/>");body.append("</w:tblGrid>");table=true;}
                body.append("<w:tr>");for(String cell:block.cells){body.append("<w:tc><w:tcPr><w:tcW w:w=\"").append(9960/columns).append("\" w:type=\"dxa\"/></w:tcPr>");paragraph(body,cell,0,draft.fontPoints);body.append("</w:tc>");}body.append("</w:tr>");
            }else{if(table){body.append("</w:tbl>");table=false;}paragraph(body,block.text,block.heading,draft.fontPoints);}
        }
        if(table)body.append("</w:tbl>");body.append("<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/><w:pgMar w:top=\"960\" w:right=\"960\" w:bottom=\"960\" w:left=\"960\" w:header=\"480\" w:footer=\"480\" w:gutter=\"0\"/></w:sectPr></w:body></w:document>");
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(ZipOutputStream zip=new ZipOutputStream(bytes,StandardCharsets.UTF_8)){
            part(zip,"[Content_Types].xml","<?xml version=\"1.0\" encoding=\"UTF-8\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/><Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/></Types>");
            part(zip,"_rels/.rels","<?xml version=\"1.0\" encoding=\"UTF-8\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/></Relationships>");part(zip,"word/document.xml",body.toString());
            part(zip,"word/_rels/document.xml.rels",OfficePackage.relationships(OfficePackage.relationship("rIdStyles","styles","styles.xml")));
            part(zip,"word/styles.xml",styles(draft.fontPoints));
        }
        byte[] result=bytes.toByteArray();if(result.length>2*1024*1024)throw new IllegalArgumentException("生成文档超过2MiB，未发布部分文档");check(result,draft,control);return result;
    }
    public static JSONObject check(byte[] result,DocumentDraft draft,RunControl control)throws Exception{
        DocumentReader reader=new DocumentReader(null,null);int unit=1,chunk=0,readChars=0,actualUnits=0;java.util.Map<Integer,StringBuilder> actualText=new java.util.LinkedHashMap<>();int requests=0;int maximumRequests=(DocumentDraft.MAX_BLOCKS*DocumentDraft.MAX_COLUMNS+31)/32;
        do{control.check();var actual=reader.read(result,new DocumentContent.Request(unit,chunk,32,65536,256),control);if(!"docx".equals(actual.format)||!"partial".equals(actual.status))throw new SecurityException("生成文件未通过原OOXML读取核对："+actual.status);for(var c:actual.chunks){actualText.computeIfAbsent(c.unit,ignored->new StringBuilder()).append(c.text);readChars+=c.text.length();}actualUnits=actual.totalUnits;if(actual.nextUnit>0&&(actual.nextUnit<unit||actual.nextUnit==unit&&actual.nextChunk<=chunk))throw new SecurityException("原文档验证游标未前进");unit=actual.nextUnit;chunk=actual.nextChunk;if(++requests>maximumRequests)throw new SecurityException("原文档验证范围未完整结束");}while(unit>0);
        java.util.List<String> expected=new java.util.ArrayList<>();for(var block:draft.blocks)if(block.table())expected.addAll(block.cells);else expected.add(block.text);
        if(actualUnits!=expected.size())throw new SecurityException("实际生成段落/单元格数量与原可编辑源不同");for(int i=0;i<expected.size();i++)if(!expected.get(i).equals(actualText.getOrDefault(i+1,new StringBuilder()).toString()))throw new SecurityException("实际生成正文段落或表格单元格字节不完整："+(i+1));
        return new JSONObject().put("format","docx").put("structuralReadComplete",true).put("charactersRead",readChars).put("macroFree",true).put("layout","A4_48pt_margins").put("visualLayoutVerified",false).put("externalRelationships",false).put("editableSourcePreserved",true);
    }
    private static String styles(int font){StringBuilder out=new StringBuilder(OfficePackage.XML).append("<w:styles xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/><w:rPr><w:sz w:val=\"").append(font*2).append("\"/></w:rPr></w:style>");for(int level=1;level<=3;level++)out.append("<w:style w:type=\"paragraph\" w:styleId=\"Heading").append(level).append("\"><w:name w:val=\"Heading ").append(level).append("\"/><w:basedOn w:val=\"Normal\"/><w:next w:val=\"Normal\"/><w:pPr><w:keepNext/><w:outlineLvl w:val=\"").append(level-1).append("\"/></w:pPr><w:rPr><w:b/></w:rPr></w:style>");return out.append("</w:styles>").toString();}
    private static void paragraph(StringBuilder out,String value,int heading,int font){out.append("<w:p><w:pPr><w:pStyle w:val=\"").append(heading>0?"Heading"+heading:"Normal").append("\"/><w:spacing w:after=\"120\"/>");if(heading>0)out.append("<w:keepNext/><w:outlineLvl w:val=\"").append(heading-1).append("\"/>");out.append("</w:pPr><w:r><w:rPr>");if(heading>0)out.append("<w:b/>");int size=(heading==1?font+6:heading==2?font+3:heading==3?font+1:font)*2;out.append("<w:sz w:val=\"").append(size).append("\"/><w:szCs w:val=\"").append(size).append("\"/></w:rPr><w:t xml:space=\"preserve\">").append(xml(value)).append("</w:t></w:r></w:p>");}
    private static String xml(String v){return v.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;");}
    private static void part(ZipOutputStream zip,String name,String value)throws Exception{ZipEntry entry=new ZipEntry(name);entry.setTime(0);zip.putNextEntry(entry);zip.write(value.getBytes(StandardCharsets.UTF_8));zip.closeEntry();}
}
