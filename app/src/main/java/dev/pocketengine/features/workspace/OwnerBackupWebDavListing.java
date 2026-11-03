package dev.pocketengine.features.workspace;

import dev.pocketengine.core.RunControl;
import java.io.*;
import java.util.*;
import javax.xml.parsers.SAXParserFactory;
import org.json.JSONArray;
import org.json.JSONObject;
import org.xml.sax.*;
import org.xml.sax.helpers.DefaultHandler;

/** A bounded DAV metadata reader. Server hrefs never become arbitrary download URLs. */
final class OwnerBackupWebDavListing {
    static final long MAX_BYTES=1024*1024;
    private OwnerBackupWebDavListing(){}

    static JSONArray read(OwnerBackupWebDavTarget target,InputStream source,RunControl original)throws Exception {
        SAXParserFactory factory=SAXParserFactory.newInstance();factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities",false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
        XMLReader reader=factory.newSAXParser().getXMLReader();Listing handler=new Listing(target);
        reader.setContentHandler(handler);reader.setErrorHandler(handler);
        reader.setEntityResolver((publicId,systemId)->{throw new SAXException("外部 XML 实体不属于备份目录");});
        InputStream bounded=new FilterInputStream(source){
            private long bytes;
            private void checked(int count)throws IOException {
                try{original.checkpoint();}catch(Exception stopped){throw new IOException("原目录读取已停止",stopped);}
                if(count>0&&(bytes+=count)>MAX_BYTES)throw new IOException("WebDAV 列表超过1MiB；请选择专用备份目录");
            }
            @Override public int read()throws IOException{int value=super.read();checked(value<0?0:1);return value;}
            @Override public int read(byte[] b,int offset,int length)throws IOException{int count=in.read(b,offset,length);checked(count);return count;}
        };
        try{reader.parse(new InputSource(bounded));}catch(SAXException malformed){throw new SecurityException("WebDAV 列表不是受支持的完整目录响应；未采用部分或外部条目",malformed);}
        original.checkpoint();return handler.result();
    }

    private static final class Listing extends DefaultHandler {
        private final OwnerBackupWebDavTarget target;
        private final Map<String,JSONObject> items=new LinkedHashMap<>();
        private int depth,responses,responseDepth,propstatDepth,propDepth;
        private String capture="",href="",status="",length="",modified="";
        private final StringBuilder text=new StringBuilder();
        private boolean collection,rootSeen,rootClosed;
        private final List<Properties> properties=new ArrayList<>();
        private record Properties(String status,String length,String modified,boolean collection){}
        Listing(OwnerBackupWebDavTarget target){this.target=target;}
        @Override public void startElement(String uri,String local,String qName,Attributes attributes)throws SAXException {
            depth++;if(depth>16)throw new SAXException("目录嵌套过深");
            if(depth==1){if(!"DAV:".equals(uri)||!"multistatus".equals(local)||rootSeen)throw new SAXException("不是 DAV multistatus");rootSeen=true;}
            if(!"DAV:".equals(uri))return;
            if("response".equals(local)&&depth==2){if(++responses>1000)throw new SAXException("目录条目过多");responseDepth=depth;href="";properties.clear();}
            if(responseDepth==0)return;
            if("propstat".equals(local)&&depth==responseDepth+1){propstatDepth=depth;status="";length="";modified="";collection=false;}
            if("prop".equals(local)&&propstatDepth>0&&depth==propstatDepth+1)propDepth=depth;
            if("collection".equals(local)&&propDepth>0&&depth==propDepth+2)collection=true;
            boolean field="href".equals(local)&&depth==responseDepth+1||"status".equals(local)&&propstatDepth>0&&depth==propstatDepth+1
                    ||Set.of("getcontentlength","getlastmodified").contains(local)&&propDepth>0&&depth==propDepth+1;
            if(field){capture=local;text.setLength(0);}
        }
        @Override public void characters(char[] ch,int start,int count)throws SAXException {if(!capture.isEmpty()){if(text.length()+count>4096)throw new SAXException("目录字段过长");text.append(ch,start,count);}}
        @Override public void endElement(String uri,String local,String qName)throws SAXException {
            if("DAV:".equals(uri)){
                if(local.equals(capture)){String value=text.toString().trim();switch(local){case "href"->href=value;case "status"->status=value;case "getcontentlength"->length=value;case "getlastmodified"->modified=value;}capture="";text.setLength(0);}
                if("prop".equals(local)&&depth==propDepth)propDepth=0;
                if("propstat".equals(local)&&depth==propstatDepth){properties.add(new Properties(status,length,modified,collection));propstatDepth=0;if(properties.size()>16)throw new SAXException("目录属性分组过多");}
                if("response".equals(local)&&depth==responseDepth){finishResponse();responseDepth=0;}
                if("multistatus".equals(local)&&depth==1)rootClosed=true;
            }
            depth--;
        }
        private void finishResponse()throws SAXException {
            String name=target.childName(href);if(name==null)return;
            Long bytes=null;String changed="";
            for(Properties part:properties)if(part.status.matches("HTTP/1\\.[01] 2[0-9]{2}(?: .*)?")){
                if(part.collection)return;
                if(!part.length.isEmpty())try{long value=Long.parseLong(part.length);if(value<OwnerBackupEnvelope.encryptedBytes(22)||value>OwnerBackupEnvelope.encryptedBytes(1536L*1024*1024))return;if(bytes!=null&&bytes!=value)throw new SAXException("目录长度属性冲突");bytes=value;}catch(NumberFormatException malformed){return;}
                if(!part.modified.isEmpty()){if(part.modified.length()>100||part.modified.codePoints().anyMatch(c->c<32||c==127))return;changed=part.modified;}
            }
            if(bytes==null)return;
            try{JSONObject next=new JSONObject().put("name",name).put("encryptedBytes",bytes).put("modified",changed).put("verified",false);
                JSONObject prior=items.putIfAbsent(name,next);if(prior!=null&&(!prior.get("encryptedBytes").equals(next.get("encryptedBytes"))||!prior.getString("modified").equals(changed)))throw new SAXException("目录重复版本冲突");
                if(items.size()>256)throw new SAXException("备份版本超过256项，请选择较小的专用目录");
            }catch(org.json.JSONException malformed){throw new SAXException("目录元数据无效",malformed);}
        }
        JSONArray result()throws SAXException {if(!rootSeen||!rootClosed||depth!=0)throw new SAXException("目录不完整");return new JSONArray(items.values());}
    }
}
