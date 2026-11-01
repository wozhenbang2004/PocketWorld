package dev.pocketengine.features.attachments.documents;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Authored package probes plus full Office-generated files; fixtures never establish device engine availability. */
public final class DocumentFixtures {
    private DocumentFixtures(){}
    public static byte[] actual(String name)throws Exception {
        for(String prefix:new String[]{"app/src/test/java/","src/test/java/"}) {
            java.nio.file.Path path=java.nio.file.Paths.get(prefix+"dev/pocketengine/features/attachments/documents/fixtures/"+name);
            if(java.nio.file.Files.isRegularFile(path))return java.nio.file.Files.readAllBytes(path);
        }
        throw new IllegalStateException("Document fixture not found in repository test inputs");
    }
    public static final String W="http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    public static final String S="http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    public static final String P="http://schemas.openxmlformats.org/presentationml/2006/main";
    public static final String A="http://schemas.openxmlformats.org/drawingml/2006/main";
    public static final String R="http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    public static Map<String,String> wordParts(String body) {
        Map<String,String> parts=base("word/document.xml","wordprocessingml.document");
        parts.put("word/document.xml","<w:document xmlns:w=\""+W+"\"><w:body>"+body+"</w:body></w:document>");return parts;
    }
    public static byte[] word(String body)throws Exception{return zip(wordParts(body));}
    public static String paragraph(String text){return "<w:p><w:r><w:t>"+text+"</w:t></w:r></w:p>";}
    public static byte[] spreadsheet()throws Exception {
        Map<String,String> parts=base("xl/workbook.xml","spreadsheetml.sheet");
        parts.put("xl/workbook.xml","<workbook xmlns=\""+S+"\" xmlns:r=\""+R+"\"><sheets><sheet name=\"预算\" sheetId=\"1\" state=\"hidden\" r:id=\"s\"/></sheets></workbook>");
        parts.put("xl/_rels/workbook.xml.rels",relationships("<Relationship Id=\"s\" Type=\""+R+"/worksheet\" Target=\"worksheets/sheet7.xml\"/><Relationship Id=\"ss\" Type=\""+R+"/sharedStrings\" Target=\"sharedStrings.xml\"/>"));
        parts.put("xl/sharedStrings.xml","<sst xmlns=\""+S+"\"><si><r><t>真实</t></r><r><t>单元格</t></r><rPh><t>phonetic</t></rPh></si></sst>");
        parts.put("xl/worksheets/sheet7.xml","<worksheet xmlns=\""+S+"\"><sheetData><row r=\"2\"><c r=\"A2\" t=\"s\"><v>0</v></c><c r=\"B2\"><v>41</v></c><c r=\"C2\"><f>B2+1</f><v>42</v></c><c r=\"D2\" t=\"inlineStr\"><is><t>内联</t><rPh><t>ignore</t></rPh></is></c><c r=\"E2\"><f>SUM(B2:C2)</f></c></row></sheetData></worksheet>");
        return zip(parts);
    }
    public static byte[] presentation()throws Exception {
        Map<String,String> parts=base("ppt/presentation.xml","presentationml.presentation");
        parts.put("ppt/presentation.xml","<p:presentation xmlns:p=\""+P+"\" xmlns:r=\""+R+"\"><p:sldIdLst><p:sldId id=\"256\" r:id=\"second\"/><p:sldId id=\"257\" r:id=\"first\"/></p:sldIdLst></p:presentation>");
        parts.put("ppt/_rels/presentation.xml.rels",relationships("<Relationship Id=\"first\" Type=\""+R+"/slide\" Target=\"slides/slide1.xml\"/><Relationship Id=\"second\" Type=\""+R+"/slide\" Target=\"slides/slide2.xml\"/>"));
        parts.put("ppt/slides/slide1.xml",slide("<a:p><a:r><a:t>Shown second</a:t></a:r></a:p>"));
        parts.put("ppt/slides/slide2.xml",slide("<a:p><a:r><a:t>Shown first</a:t></a:r></a:p><a:tbl><a:tr><a:tc><a:txBody><a:p><a:r><a:t>Table value</a:t></a:r></a:p></a:txBody></a:tc></a:tr></a:tbl>"));return zip(parts);
    }
    private static String slide(String content){return "<p:sld xmlns:p=\""+P+"\" xmlns:a=\""+A+"\"><p:cSld><p:spTree>"+content+"</p:spTree></p:cSld></p:sld>";}
    private static Map<String,String> base(String main,String type) {
        Map<String,String> parts=new LinkedHashMap<>();
        parts.put("[Content_Types].xml","<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/"+main+"\" ContentType=\"application/vnd.openxmlformats-officedocument."+type+".main+xml\"/></Types>");
        parts.put("_rels/.rels",relationships("<Relationship Id=\"root\" Type=\""+R+"/officeDocument\" Target=\""+main+"\"/>"));return parts;
    }
    public static String relationships(String content){return "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"+content+"</Relationships>";}
    public static byte[] zip(Map<String,String> parts)throws Exception {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        try(ZipOutputStream zip=new ZipOutputStream(out)){for(Map.Entry<String,String> part:parts.entrySet()){zip.putNextEntry(new ZipEntry(part.getKey()));zip.write(part.getValue().getBytes(StandardCharsets.UTF_8));zip.closeEntry();}}
        return out.toByteArray();
    }
    public static byte[] png(){return java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGNgYGD4DwABBAEAX+XDSwAAAABJRU5ErkJggg==");}
    public static byte[] pdf(boolean imageOnly)throws Exception {
        ByteArrayOutputStream out=new ByteArrayOutputStream();out.write("%PDF-1.4\n".getBytes(StandardCharsets.US_ASCII));
        String stream=imageOnly?"q 100 0 0 100 0 0 cm /Im0 Do Q":"BT /F1 12 Tf 20 100 Td (Actual PDF 42) Tj ET";
        String[] objects={"<< /Type /Catalog /Pages 2 0 R >>","<< /Type /Pages /Count 1 /Kids [3 0 R] >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] /Resources "+(imageOnly?"<< /XObject << /Im0 5 0 R >> >>":"<< /Font << /F1 5 0 R >> >>")+" /Contents 4 0 R >>",
            "<< /Length "+stream.length()+" >>\nstream\n"+stream+"\nendstream",
            imageOnly?"<< /Type /XObject /Subtype /Image /Width 1 /Height 1 /ColorSpace /DeviceRGB /BitsPerComponent 8 /Length 3 >>\nstream\nxxx\nendstream":"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"};
        int[] offsets=new int[objects.length];for(int i=0;i<objects.length;i++){offsets[i]=out.size();out.write(((i+1)+" 0 obj\n"+objects[i]+"\nendobj\n").getBytes(StandardCharsets.US_ASCII));}
        int xref=out.size();StringBuilder trailer=new StringBuilder("xref\n0 6\n0000000000 65535 f \n");
        for(int offset:offsets)trailer.append(String.format(java.util.Locale.ROOT,"%010d 00000 n \n",offset));
        trailer.append("trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n").append(xref).append("\n%%EOF\n");out.write(trailer.toString().getBytes(StandardCharsets.US_ASCII));return out.toByteArray();
    }
    public static void main(String[] args)throws Exception {
        java.nio.file.Path root=java.nio.file.Paths.get(args[0]);java.nio.file.Files.createDirectories(root);
        java.nio.file.Files.write(root.resolve("text.pdf"),pdf(false));java.nio.file.Files.write(root.resolve("image.pdf"),pdf(true));java.nio.file.Files.write(root.resolve("image.png"),png());
        java.nio.file.Files.write(root.resolve("content.docx"),word(paragraph("Document fixture")));java.nio.file.Files.write(root.resolve("content.xlsx"),spreadsheet());java.nio.file.Files.write(root.resolve("content.pptx"),presentation());
    }
}
