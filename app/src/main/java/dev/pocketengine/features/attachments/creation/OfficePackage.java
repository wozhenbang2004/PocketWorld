package dev.pocketengine.features.attachments.creation;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.attachments.documents.DocumentContent;
import dev.pocketengine.features.attachments.documents.DocumentReader;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.util.zip.ZipInputStream;
import javax.xml.parsers.SAXParserFactory;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.ext.DefaultHandler2;

/** Shared native OPC bytes and the existing independent bounded reader, not an Office renderer. */
final class OfficePackage {
    static final String XML="<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>";
    static final String REL="http://schemas.openxmlformats.org/package/2006/relationships";
    static final String OFFICE_REL="http://schemas.openxmlformats.org/officeDocument/2006/relationships/";
    static final String CONTENT="http://schemas.openxmlformats.org/package/2006/content-types";
    private OfficePackage(){}
    static byte[] pack(Map<String,String> parts,RunControl control)throws Exception {
        long expanded=0;ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(ZipOutputStream zip=new ZipOutputStream(bytes,StandardCharsets.UTF_8)){
            for(var part:parts.entrySet()){
                control.checkpoint();byte[] content=part.getValue().getBytes(StandardCharsets.UTF_8);expanded+=content.length;
                if(expanded>4*1024*1024||parts.size()>256)throw new IllegalArgumentException("Office输出超过有界包范围，未发布部分文件");
                ZipEntry entry=new ZipEntry(part.getKey());entry.setTime(0);zip.putNextEntry(entry);zip.write(content);zip.closeEntry();
            }
        }
        byte[] output=bytes.toByteArray();if(output.length>2*1024*1024)throw new IllegalArgumentException("Office输出超过2MiB，未发布部分文件");return output;
    }
    static String xml(String text){return text.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&apos;").replace("\r","&#13;");}
    static String relationship(String id,String type,String target){return "<Relationship Id=\""+xml(id)+"\" Type=\""+OFFICE_REL+type+"\" Target=\""+xml(target)+"\"/>";}
    static String relationships(String body){return XML+"<Relationships xmlns=\""+REL+"\">"+body+"</Relationships>";}
    static String override(String path,String type){return "<Override PartName=\"/"+path+"\" ContentType=\"application/vnd.openxmlformats-officedocument."+type+"+xml\"/>";}
    static String types(String body){return XML+"<Types xmlns=\""+CONTENT+"\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/>"+body+"</Types>";}
    static List<DocumentContent.Chunk> projection(byte[] bytes,String format,int expectedUnits,RunControl control)throws Exception {
        List<DocumentContent.Chunk> chunks=new ArrayList<>();int unit=1,chunk=0,requests=0;
        do {
            control.checkpoint();var read=new DocumentReader(null,null).read(bytes,new DocumentContent.Request(unit,chunk,32,65536,256),control);
            if(!format.equals(read.format)||!"partial".equals(read.status)||read.totalUnits!=expectedUnits)throw new SecurityException("实际Office包未通过原读取器的格式/单元数量核对："+read.status);
            chunks.addAll(read.chunks);if(read.nextUnit>0&&(read.nextUnit<unit||read.nextUnit==unit&&read.nextChunk<=chunk))throw new SecurityException("Office内容验证游标未前进");
            unit=read.nextUnit;chunk=read.nextChunk;if(++requests>256)throw new SecurityException("Office内容验证未在有界范围结束");
        }while(unit>0);
        return chunks;
    }
    static List<String> drawingParagraphs(byte[] bytes,String path,RunControl control)throws Exception {
        byte[] content=null;try(ZipInputStream zip=new ZipInputStream(new ByteArrayInputStream(bytes),StandardCharsets.UTF_8)){
            for(ZipEntry entry;(entry=zip.getNextEntry())!=null;){control.checkpoint();if(!entry.getName().equals(path))continue;ByteArrayOutputStream output=new ByteArrayOutputStream();byte[] block=new byte[4096];for(int n;(n=zip.read(block))!=-1;){control.checkpoint();if(output.size()+n>256*1024)throw new SecurityException("Office讲稿超出验证范围");output.write(block,0,n);}content=output.toByteArray();break;}
        }
        if(content==null)throw new SecurityException("Office讲稿关系对应的内容不存在");
        List<String> values=new ArrayList<>();var handler=new DefaultHandler2(){StringBuilder text;boolean capture;
            @Override public void startElement(String uri,String local,String q,Attributes attributes)throws SAXException {
                if(!uri.equals("http://schemas.openxmlformats.org/drawingml/2006/main"))return;
                if(local.equals("p"))text=new StringBuilder();if(local.equals("t"))capture=true;
            }
            @Override public void characters(char[] chars,int offset,int length){if(capture&&text!=null)text.append(chars,offset,length);}
            @Override public void endElement(String uri,String local,String q){if(!uri.equals("http://schemas.openxmlformats.org/drawingml/2006/main"))return;if(local.equals("t"))capture=false;if(local.equals("p")&&text!=null){values.add(text.toString());text=null;}}
            @Override public void startDTD(String name,String publicId,String systemId)throws SAXException{throw new SAXException("DTD forbidden");}
            @Override public InputSource resolveEntity(String publicId,String systemId)throws SAXException{throw new SAXException("Entity forbidden");}
        };
        SAXParserFactory factory=SAXParserFactory.newInstance();factory.setNamespaceAware(true);var reader=factory.newSAXParser().getXMLReader();
        reader.setFeature("http://xml.org/sax/features/external-general-entities",false);reader.setFeature("http://xml.org/sax/features/external-parameter-entities",false);reader.setProperty("http://xml.org/sax/properties/lexical-handler",handler);reader.setContentHandler(handler);reader.setEntityResolver(handler);reader.parse(new InputSource(new ByteArrayInputStream(content)));control.checkpoint();return values;
    }
}
