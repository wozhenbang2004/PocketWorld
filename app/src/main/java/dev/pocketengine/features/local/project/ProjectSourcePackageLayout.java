package dev.pocketengine.features.local.project;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.json.JSONTokener;

/** Recognize the exported root format, never a filename/extension or a nested application's manifest.
 * This only chooses the decoder. The independent package reader verifies schema and bytes, not who authored them. */
final class ProjectSourcePackageLayout {
    private ProjectSourcePackageLayout(){}
    static boolean declared(ZipFile zip,ZipArchiveEntry entry,ProjectSourceImport.Current current)throws Exception {
        if(entry==null||entry.isDirectory())return false;
        if(entry.getSize()<0||entry.getSize()>ProjectPackageArchive.MAX_FILE_BYTES)throw new IllegalArgumentException("ZIP 根清单大小未知或超过单文件上限");
        byte[] bytes;
        try(var input=zip.getInputStream(entry);var output=new ByteArrayOutputStream()){
            byte[] buffer=new byte[8192];for(int n;(n=input.read(buffer))>=0;){current.check();if(n==0||output.size()+n>entry.getSize())throw new IllegalArgumentException("ZIP 根清单不是有界完整文件");output.write(buffer,0,n);}
            if(output.size()!=entry.getSize())throw new IllegalArgumentException("ZIP 根清单大小不符");bytes=output.toByteArray();
        }
        String s;try{s=StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();}catch(java.nio.charset.CharacterCodingException nonText){return false;}
        // Walk over quoted scalars without recursively parsing an arbitrary ordinary project's JSON.
        int first=space(s,0);if(first>=s.length()||s.charAt(first)!='{')return false;
        boolean ours=false,deep=false;int depth=0;char previous=0;
        for(int at=first;at<s.length();at++){
            char ch=s.charAt(at);if(Character.isWhitespace(ch))continue;
            if(ch=='"'){
                int end=end(s,at);if(end<0)break;
                if(depth==1&&(previous=='{'||previous==',')&&end-at<=37&&"format".equals(scalar(s,at,end))){
                    int colon=space(s,end+1),value=space(s,colon+1);
                    if(colon<s.length()&&s.charAt(colon)==':'&&value<s.length()&&s.charAt(value)=='"'){
                        int last=end(s,value);if(last>=0){String format=scalar(s,value,last);
                            if(format!=null&&format.startsWith("pocketworld.project-package.")){
                                if(!ProjectPackageArchive.FORMAT.equals(format))throw new IllegalArgumentException("这份 PocketWorld 项目包版本不受支持，未当普通源码导入");ours=true;
                            }
                        }
                    }
                }
                at=end;
            }else if(ch=='{'||ch=='['){depth++;deep|=depth>48;}else if(ch=='}'||ch==']'){if(--depth==0)break;}
            previous=ch;
        }
        if(ours&&(deep||bytes.length>ProjectPackageArchive.MAX_MANIFEST_BYTES))throw new IllegalArgumentException("固定项目包清单超过校验范围，未作为普通源码导入");
        return ours;
    }
    private static int space(String s,int at){while(at<s.length()&&Character.isWhitespace(s.charAt(at)))at++;return at;}
    private static int end(String s,int start){for(int at=start+1;at<s.length();at++){char ch=s.charAt(at);if(ch=='\\')at++;else if(ch=='"')return at;}return -1;}
    private static String scalar(String text,int start,int end){try{Object value=new JSONTokener(text.substring(start,end+1)).nextValue();return value instanceof String?(String)value:null;}catch(org.json.JSONException malformed){return null;}}
}
