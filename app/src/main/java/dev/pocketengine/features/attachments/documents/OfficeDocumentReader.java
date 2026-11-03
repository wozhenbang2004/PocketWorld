package dev.pocketengine.features.attachments.documents;

import dev.pocketengine.core.RunControl;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;
import javax.xml.parsers.SAXParserFactory;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.ext.DefaultHandler2;

/** Bounded text projection of OPC parts, independently authored from public OOXML contracts. */
final class OfficeDocumentReader {
    private static final int MAX_ENTRIES=1024, MAX_ENTRY=8*1024*1024, MAX_EXPANDED=32*1024*1024;
    private static final int MAX_SLIDE_REFERENCES=1024;
    private static final String REL="http://schemas.openxmlformats.org/package/2006/relationships";
    private static final String CONTENT_TYPES="http://schemas.openxmlformats.org/package/2006/content-types";
    private final Map<String,byte[]> parts=new LinkedHashMap<>();
    private final Set<String> names=new HashSet<>();
    private RunControl control;
    private DocumentContent.Collector out;
    private String format="zip";
    private static final class Limit extends SAXException { Limit(String s){super(s);} }
    private static final class Invalid extends SAXException { Invalid(String s){super(s);} }
    private static final class Relation {final String target,type;Relation(String target,String type){this.target=target;this.type=type;}}

    DocumentContent.Result read(byte[] bytes,DocumentContent.Request request,RunControl control)throws Exception {
        this.control=control;out=new DocumentContent.Collector(request);
        try {
            unpack(bytes);
            final Map<String,String> contentTypes=new HashMap<>();
            parse("[Content_Types].xml",new Handler(){
                @Override void start(String uri,String n,Attributes a)throws Exception {
                    if(depth==1&&(!uri.equals(CONTENT_TYPES)||!n.equals("Types")))throw new Invalid("Invalid content-type root");
                    if(n.equals("Override")&&uri.equals(CONTENT_TYPES)) {
                        String name=a.getValue("PartName"),type=a.getValue("ContentType");
                        if(name==null||!name.startsWith("/")||type==null||contentTypes.put(name,type)!=null)throw new Invalid("Invalid or duplicate content type");
                    }
                }
            });
            Relation office=null;
            for(Relation rel:relationships("").values())if(rel.type.endsWith("/officeDocument")) {
                if(office!=null)throw new Invalid("Multiple officeDocument roots");office=rel;
            }
            if(office==null)return DocumentReader.unavailable("zip","unsupported","ZIP is not a supported OOXML document");
            String main=office.target, type=contentTypes.get("/"+main);
            if(type==null)throw new Invalid("Main part content type missing");
            if(type.equals("application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"))format="docx";
            else if(type.equals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"))format="xlsx";
            else if(type.equals("application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml"))format="pptx";
            else return DocumentReader.unavailable("office","unsupported","Only non-macro DOCX, XLSX and PPTX main parts are supported");
            for(String name:names)if(name.toLowerCase(java.util.Locale.ROOT).endsWith("vbaproject.bin"))throw new Invalid("Macro payload in non-macro document");
            if(format.equals("docx"))word(main);
            else if(format.equals("xlsx"))workbook(main);
            else presentation(main);
            control.checkpoint();
            return out.result(format,"partial",format.equals("docx")?"body_paragraphs_and_tables":format.equals("xlsx")?"stored_cell_values":"slide_text");
        } catch(Limit limit) {return DocumentReader.unavailable(format,"limit_exceeded",limit.getMessage());}
        catch(Invalid invalid) {return DocumentReader.unavailable(format,"invalid",invalid.getMessage());}
        catch(SAXException invalid) {
            if(invalid.getException() instanceof InterruptedException)throw invalid.getException();
            return DocumentReader.unavailable(format,"invalid","Malformed XML or forbidden DTD/entity");
        } catch(ZipException invalid) {return DocumentReader.unavailable(format,"invalid","Malformed or unsupported ZIP package");}
        catch(IOException invalid) {return DocumentReader.unavailable(format,"invalid","Incomplete document package");}
    }
    private void unpack(byte[] bytes)throws Exception {
        int expectedEntries=inspectDirectory(bytes);
        long total=0;int count=0;
        try(ZipInputStream zip=new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for(ZipEntry entry;(entry=zip.getNextEntry())!=null;) {
                control.checkpoint();if(++count>MAX_ENTRIES)throw new Limit("Package has more than 1024 entries");
                String name=entry.getName();validateEntry(name);
                if(!names.add(name))throw new Invalid("Duplicate ZIP part");
                if(entry.isDirectory()){zip.closeEntry();continue;}
                boolean xml=name.endsWith(".xml")||name.endsWith(".rels");
                ByteArrayOutputStream buffer=xml?new ByteArrayOutputStream():null;int size=0;byte[] block=new byte[8192];
                for(int n;(n=zip.read(block))!=-1;) {control.checkpoint();if(n==0)continue;size+=n;total+=n;
                    if(size>MAX_ENTRY||total>MAX_EXPANDED)throw new Limit("Package exceeds 8 MiB per entry or 32 MiB expanded");
                    if(buffer!=null)buffer.write(block,0,n);
                }
                if(buffer!=null)parts.put(name,buffer.toByteArray());zip.closeEntry();
            }
        }
        if(count!=expectedEntries)throw new Invalid("ZIP entry count does not match its directory");
        if(names.isEmpty())throw new Invalid("Empty ZIP package");
    }
    private int inspectDirectory(byte[] bytes)throws Exception {
        int end=-1;
        for(int i=bytes.length-22;i>=Math.max(0,bytes.length-65557);i--) {
            if(u32(bytes,i)==0x06054b50L&&i+22+u16(bytes,i+20)==bytes.length){end=i;break;}
        }
        if(end<0)throw new Invalid("ZIP directory is missing or truncated");
        int entries=u16(bytes,end+10);
        if(u16(bytes,end+4)!=0||u16(bytes,end+6)!=0||u16(bytes,end+8)!=entries)throw new Invalid("Multi-volume ZIP is unsupported");
        long length=u32(bytes,end+12),offset=u32(bytes,end+16);
        if(entries==65535||length==0xffffffffL||offset==0xffffffffL)throw new Invalid("ZIP64 packages are unsupported");
        if(entries>MAX_ENTRIES)throw new Limit("Package has more than 1024 entries");
        if(offset+length!=end)throw new Invalid("Invalid ZIP directory bounds");
        int cursor=(int)offset;long expanded=0;
        for(int i=0;i<entries;i++) {
            control.checkpoint();
            if(cursor+46>end||u32(bytes,cursor)!=0x02014b50L)throw new Invalid("Invalid ZIP directory entry");
            int flags=u16(bytes,cursor+8);
            if((flags&0x41)!=0)throw new DocumentReader.EncryptedDocumentException();
            long size=u32(bytes,cursor+24),local=u32(bytes,cursor+42);expanded+=size;
            if(size>MAX_ENTRY||expanded>MAX_EXPANDED)throw new Limit("Package exceeds 8 MiB per entry or 32 MiB expanded");
            if(local+30>offset||u32(bytes,(int)local)!=0x04034b50L)throw new Invalid("Invalid ZIP local entry");
            if((u16(bytes,(int)local+6)&0x41)!=0)throw new DocumentReader.EncryptedDocumentException();
            cursor+=46+u16(bytes,cursor+28)+u16(bytes,cursor+30)+u16(bytes,cursor+32);
            if(cursor>end)throw new Invalid("ZIP directory entry exceeds package bounds");
        }
        if(cursor!=end)throw new Invalid("Unexpected ZIP directory data");
        return entries;
    }
    private static int u16(byte[] b,int at){return (b[at]&255)|((b[at+1]&255)<<8);}
    private static long u32(byte[] b,int at){return (b[at]&255L)|((b[at+1]&255L)<<8)|((b[at+2]&255L)<<16)|((b[at+3]&255L)<<24);}
    private static void validateEntry(String n)throws Invalid {
        if(n==null||n.length()>512||n.startsWith("/")||n.indexOf('\\')>=0||n.indexOf(':')>=0||n.indexOf('\0')>=0)throw new Invalid("Unsafe ZIP part name");
        for(String segment:n.split("/",-1))if(segment.equals("..")||segment.equals("."))throw new Invalid("Unsafe ZIP part name");
    }
    private Map<String,Relation> relationships(String part)throws Exception {
        String folder=part.contains("/")?part.substring(0,part.lastIndexOf('/')+1):"";
        String base=part.substring(folder.length());String relPath=folder+"_rels/"+base+".rels";
        Map<String,Relation> map=new LinkedHashMap<>();
        if(!parts.containsKey(relPath))return map;
        parse(relPath,new Handler(){
            @Override void start(String uri,String n,Attributes a)throws Exception {
                if(depth==1&&(!uri.equals(REL)||!n.equals("Relationships")))throw new Invalid("Invalid relationship root");
                if(!n.equals("Relationship")||!uri.equals(REL))return;
                String id=a.getValue("Id"),target=a.getValue("Target"),type=a.getValue("Type");
                if(id==null||type==null||target==null||map.containsKey(id))throw new Invalid("Invalid or duplicate relationship");
                if("External".equals(a.getValue("TargetMode"))){out.warning("External relationships were not fetched");return;}
                if(a.getValue("TargetMode")!=null&&!"Internal".equals(a.getValue("TargetMode")))throw new Invalid("Invalid relationship mode");
                map.put(id,new Relation(resolve(folder,target),type));
            }
        });return map;
    }
    private static String resolve(String folder,String target)throws Invalid {
        if(target.length()>512||target.contains(":")||target.contains("\\")||target.contains("?")||target.contains("#")||target.contains("%")||target.startsWith("//"))throw new Invalid("Unsupported relationship target");
        String path=target.startsWith("/")?target.substring(1):folder+target;
        Deque<String> stack=new ArrayDeque<>();for(String p:path.split("/",-1)) {
            if(p.equals("..")){if(stack.isEmpty())throw new Invalid("Relationship escapes package");stack.removeLast();}
            else if(!p.equals(".")&&!p.isEmpty())stack.addLast(p);
        }
        if(stack.isEmpty())throw new Invalid("Empty relationship target");return String.join("/",stack);
    }
    private abstract class Handler extends DefaultHandler2 {
        int depth,nodes;
        void start(String uri,String n,Attributes a)throws Exception {}
        void end(String uri,String n)throws Exception {}
        void text(char[] c,int start,int length)throws Exception {}
        @Override public final void startElement(String uri,String local,String q,Attributes a)throws SAXException {
            try {control.checkpoint();if(++depth>128||++nodes>500000)throw new Limit("XML structure limit exceeded");start(uri,local,a);}catch(SAXException e){throw e;}catch(Exception e){throw new SAXException(e);}
        }
        @Override public final void endElement(String uri,String local,String q)throws SAXException {
            try {control.checkpoint();end(uri,local);depth--;}catch(SAXException e){throw e;}catch(Exception e){throw new SAXException(e);}
        }
        @Override public final void characters(char[] c,int s,int n)throws SAXException {
            try {control.checkpoint();text(c,s,n);}catch(SAXException e){throw e;}catch(Exception e){throw new SAXException(e);}
        }
        @Override public void startDTD(String name,String publicId,String systemId)throws SAXException {throw new Invalid("DTD is forbidden");}
        @Override public InputSource resolveEntity(String publicId,String systemId)throws SAXException {throw new Invalid("External entity is forbidden");}
        @Override public void skippedEntity(String name)throws SAXException {throw new Invalid("Entity is forbidden");}
        @Override public void fatalError(org.xml.sax.SAXParseException e)throws SAXException {throw e;}
        @Override public void error(org.xml.sax.SAXParseException e)throws SAXException {throw e;}
    }
    private void parse(String name,Handler handler)throws Exception {
        byte[] bytes=parts.get(name);if(bytes==null)throw new Invalid("Required XML part missing: "+name);
        SAXParserFactory factory=SAXParserFactory.newInstance();factory.setNamespaceAware(true);
        XMLReader reader=factory.newSAXParser().getXMLReader();
        // Lexical DOCTYPE rejection is required, not silently ignored on an unsupported parser.
        reader.setProperty("http://xml.org/sax/properties/lexical-handler",handler);
        reader.setFeature("http://xml.org/sax/features/external-general-entities",false);
        reader.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
        reader.setContentHandler(handler);reader.setEntityResolver(handler);reader.setErrorHandler(handler);
        reader.parse(new InputSource(new ByteArrayInputStream(bytes)));
    }
    private static boolean ns(String uri,String family) {
        return uri.equals("http://schemas.openxmlformats.org/"+family+"/2006/main")
            ||uri.equals("http://purl.oclc.org/ooxml/"+family+"/main");
    }
    private static String relationshipId(Attributes a) {
        String id=a.getValue("http://schemas.openxmlformats.org/officeDocument/2006/relationships","id");
        return id==null?a.getValue("http://purl.oclc.org/ooxml/officeDocument/relationships","id"):id;
    }
    private void word(String part)throws Exception {
        out.warning("DOCX body text only; pagination, headers, footnotes, drawings, comments and deleted revisions are not extracted");
        parse(part,new Handler(){
            int paragraph,table,deleted,capture,body,drawing;StringBuilder text;Map<String,String> locator;
            final Deque<int[]> tables=new ArrayDeque<>();
            @Override void start(String uri,String n,Attributes a)throws Exception {
                if(depth==1&&(!ns(uri,"wordprocessingml")||!n.equals("document")))throw new Invalid("Invalid DOCX document root");
                if(!ns(uri,"wordprocessingml"))return;
                if(n.equals("body"))body++;
                if(n.equals("drawing")||n.equals("pict")||n.equals("object"))drawing++;
                if(body==0||drawing>0)return;
                if(n.equals("del"))deleted++;
                if(n.equals("tbl"))tables.push(new int[]{++table,0,0});
                if(n.equals("tr")&&!tables.isEmpty()){tables.peek()[1]++;tables.peek()[2]=0;}
                if(n.equals("tc")&&!tables.isEmpty())tables.peek()[2]++;
                if(n.equals("p")){paragraph++;text=new StringBuilder();locator=DocumentContent.location("part",part,"paragraph",Integer.toString(paragraph));
                    if(!tables.isEmpty()){int[] t=tables.peek();locator.put("table",Integer.toString(t[0]));locator.put("row",Integer.toString(t[1]));locator.put("column",Integer.toString(t[2]));}}
                if(n.equals("t")&&deleted==0)capture++;
                if(text!=null&&deleted==0&&(n.equals("tab")||n.equals("br")))text.append(n.equals("tab")?'\t':'\n');
            }
            @Override void text(char[] c,int s,int n){if(capture>0&&text!=null&&body>0&&drawing==0&&deleted==0)text.append(c,s,n);}
            @Override void end(String uri,String n) {
                if(!ns(uri,"wordprocessingml"))return;
                if(n.equals("drawing")||n.equals("pict")||n.equals("object")){drawing--;return;}
                if(n.equals("body")){body--;return;}
                if(body==0||drawing>0)return;
                if(n.equals("t")&&deleted==0)capture--;
                if(n.equals("p")&&text!=null){out.add(paragraph,tables.isEmpty()?"text":"table_cell",locator,text.toString());out.unit(paragraph);text=null;}
                if(n.equals("tbl")&&!tables.isEmpty())tables.pop();
                if(n.equals("del"))deleted--;
            }
        });
    }
    private void workbook(String part)throws Exception {
        out.warning("Stored cell values only; formulas are not evaluated, cached values can be stale; styles, dates, charts and merged-cell layout are not rendered");
        Map<String,Relation> rels=relationships(part);List<String[]> sheets=new ArrayList<>();
        parse(part,new Handler(){@Override void start(String uri,String n,Attributes a)throws Exception {
            if(depth==1&&(!ns(uri,"spreadsheetml")||!n.equals("workbook")))throw new Invalid("Invalid XLSX workbook root");
            if(ns(uri,"spreadsheetml")&&n.equals("sheet")) {
                String name=a.getValue("name");if(name==null||name.length()>128)throw new Invalid("Invalid sheet name");
                if(sheets.size()>=1024)throw new Limit("Workbook has more than 1024 sheets");
                sheets.add(new String[]{name,relationshipId(a),a.getValue("state")});
            }
        }});
        List<String> strings=new ArrayList<>();
        for(Relation r:rels.values())if(r.type.endsWith("/sharedStrings"))readStrings(r.target,strings);
        int index=0;for(String[] sheet:sheets) {
            control.checkpoint();final int unit=++index;out.unit(unit);
            Relation rel=rels.get(sheet[1]);if(rel==null||!rel.type.endsWith("/worksheet"))throw new Invalid("Worksheet relationship missing");
            final String path=rel.target;
            parse(path,new Handler(){
                String ref,type;StringBuilder value,inline,formula;int mode,phonetic;boolean cell,hasFormula;
                final Set<String> seenCells=new HashSet<>();
                @Override void start(String uri,String n,Attributes a)throws Exception {
                    if(depth==1&&(!ns(uri,"spreadsheetml")||!n.equals("worksheet")))throw new Invalid("Invalid worksheet root");
                    if(!ns(uri,"spreadsheetml"))return;
                    if(n.equals("c")) {if(cell)throw new Invalid("Nested cell");cell=true;hasFormula=false;ref=a.getValue("r");type=a.getValue("t");value=new StringBuilder();inline=new StringBuilder();formula=new StringBuilder();
                        if(ref==null||!validCell(ref)||!seenCells.add(ref))throw new Invalid("Cell reference missing, repeated or invalid");}
                    if(seenCells.size()>100000)throw new Limit("Worksheet has more than 100000 cells");
                    if(n.equals("rPh"))phonetic++;
                    if(cell&&n.equals("v"))mode=1;if(cell&&n.equals("t")&&phonetic==0)mode=2;if(cell&&n.equals("f")){mode=3;hasFormula=true;}
                }
                @Override void text(char[] c,int s,int n){if(mode==1)value.append(c,s,n);if(mode==2)inline.append(c,s,n);if(mode==3)formula.append(c,s,n);}
                @Override void end(String uri,String n)throws Exception {
                    if(!ns(uri,"spreadsheetml"))return;
                    if(n.equals("rPh"))phonetic--;
                    if(n.equals("v")||n.equals("t")||n.equals("f"))mode=0;
                    if(n.equals("c")&&cell) {
                        String text=value.toString(),kind="table_cell";
                        if("s".equals(type)){int stringIndex;try{stringIndex=Integer.parseInt(text);}catch(NumberFormatException e){throw new Invalid("Invalid shared-string index");}
                            if(stringIndex<0||stringIndex>=strings.size())throw new Invalid("Shared-string index out of range");text=strings.get(stringIndex);}
                        else if("inlineStr".equals(type))text=inline.toString();
                        else if("b".equals(type)){if(!text.equals("0")&&!text.equals("1"))throw new Invalid("Invalid boolean cell");text=text.equals("1")?"true":"false";}
                        Map<String,String> loc=DocumentContent.location("part",path,"sheet",sheet[0],"sheetIndex",Integer.toString(unit),"cell",ref,"valueType",type==null?"n":type);
                        if(sheet[2]!=null)loc.put("sheetState",sheet[2]);
                        if(hasFormula){loc.put("valueSource","cached_formula");if(text.isEmpty()){text=formula.toString();kind="formula";loc.put("valueSource","unevaluated_formula");}}
                        out.add(unit,kind,loc,text);cell=false;
                    }
                }
            });
        }
    }
    private static boolean validCell(String ref) {
        if(!ref.matches("[A-Z]{1,3}[1-9][0-9]{0,6}"))return false;
        int i=0,column=0;while(i<ref.length()&&ref.charAt(i)>='A'&&ref.charAt(i)<='Z')column=column*26+ref.charAt(i++)-'A'+1;
        return column<=16384&&Integer.parseInt(ref.substring(i))<=1048576;
    }
    private void readStrings(String part,List<String> strings)throws Exception {
        parse(part,new Handler(){StringBuilder value;boolean capture;int phonetic;
            @Override void start(String uri,String n,Attributes a)throws Exception {
                if(depth==1&&(!ns(uri,"spreadsheetml")||!n.equals("sst")))throw new Invalid("Invalid shared-string root");
                if(!ns(uri,"spreadsheetml"))return;
                if(n.equals("si")){if(strings.size()>=100000)throw new Limit("Too many shared strings");value=new StringBuilder();}
                if(n.equals("rPh"))phonetic++;if(n.equals("t")&&phonetic==0)capture=true;
            }
            @Override void text(char[] c,int s,int n){if(capture&&value!=null)value.append(c,s,n);}
            @Override void end(String uri,String n){if(!ns(uri,"spreadsheetml"))return;
                if(n.equals("t"))capture=false;if(n.equals("rPh"))phonetic--;if(n.equals("si")){strings.add(value.toString());value=null;}}
        });
    }
    private void presentation(String part)throws Exception {
        out.warning("Slide text in presentation order; notes, charts, images, master text and visual reading order are not extracted");
        Map<String,Relation> rels=relationships(part);List<String> slides=new ArrayList<>();
        parse(part,new Handler(){@Override void start(String uri,String n,Attributes a)throws Exception{
            if(depth==1&&(!ns(uri,"presentationml")||!n.equals("presentation")))throw new Invalid("Invalid PPTX presentation root");
            if(ns(uri,"presentationml")&&n.equals("sldId")) {
                if(slides.size()>=MAX_SLIDE_REFERENCES)throw new Limit("Presentation has more than 1024 slide references");
                slides.add(relationshipId(a));}}});
        int index=0;long parsedBytes=0;for(String id:slides) {
            control.checkpoint();final int unit=++index;out.unit(unit);Relation r=rels.get(id);
            if(r==null||!r.type.endsWith("/slide"))throw new Invalid("Slide relationship missing");String path=r.target;
            byte[] slide=parts.get(path);if(slide==null)throw new Invalid("Required XML part missing: "+path);
            // Charge every reference, including aliases of one part and slides outside the requested range.
            parsedBytes+=slide.length;if(parsedBytes>MAX_EXPANDED)throw new Limit("Slide XML exceeds 32 MiB cumulative parsing budget");
            parse(path,new Handler(){StringBuilder text;boolean capture;int paragraph,table,row,column;
                @Override void start(String uri,String n,Attributes a)throws Exception{
                    if(depth==1&&(!ns(uri,"presentationml")||!n.equals("sld")))throw new Invalid("Invalid slide root");
                    if(!ns(uri,"drawingml"))return;
                    if(n.equals("tbl")){table++;row=0;column=0;}if(n.equals("tr")){row++;column=0;}if(n.equals("tc"))column++;
                    if(n.equals("p")){text=new StringBuilder();paragraph++;}if(n.equals("t"))capture=true;if(n.equals("br")&&text!=null)text.append('\n');}
                @Override void text(char[] c,int s,int n){if(capture&&text!=null)text.append(c,s,n);}
                @Override void end(String uri,String n){if(!ns(uri,"drawingml"))return;
                    if(n.equals("t"))capture=false;if(n.equals("p")&&text!=null){
                        Map<String,String> loc=DocumentContent.location("part",path,"slide",Integer.toString(unit),"paragraph",Integer.toString(paragraph));
                        if(row>0){loc.put("table",Integer.toString(table));loc.put("row",Integer.toString(row));loc.put("column",Integer.toString(column));}
                        out.add(unit,row>0?"table_cell":"text",loc,text.toString());text=null;}
                    if(n.equals("tbl")){row=0;column=0;}}
            });
        }
    }
}
