package dev.pocketengine.ui;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import org.json.JSONObject;

/** Explicit phone-only draft keeps. One immutable file per keep; the first line contains metadata,
 * so a project listing never opens another draft body. No checkout, version write or sharing grant. */
public final class FormalDraftShelf {
    public static final int COUNT=16, MAX_TEXT=65536, MAX_BYTES=262144, TOTAL=2*1024*1024;
    private static final int HEADER=4096; private static final Object LOCK=new Object();
    private final File root;
    public FormalDraftShelf(File privateRoot){this.root=privateRoot;}
    public interface Current {void check()throws Exception;}
    private static boolean id(String s){return s!=null&&s.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}");}
    private static boolean sha(String s){return s!=null&&s.matches("[a-f0-9]{64}");}
    private static boolean path(String s){if(s==null||s.trim().isEmpty()||s.length()>240||s.contains("\\")||s.codePoints().anyMatch(Character::isISOControl))return false;for(String part:s.split("/",-1))if(part.isEmpty()||part.equals(".")||part.equals(".."))return false;return true;}
    private File directory(String project){if(!id(project))throw new IllegalArgumentException("原私人项目身份无效");return new File(root,project);}
    private File target(String project,String keep){if(!id(keep))throw new IllegalArgumentException("本机保留稿定位无效");return new File(directory(project),keep+".draft");}
    private static byte[] utf8(String s)throws Exception{
        if(s==null||s.length()>MAX_TEXT)throw new IllegalArgumentException("每份手机保留稿最多65536字符");
        ByteBuffer b=StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(s));
        if(b.remaining()>MAX_BYTES)throw new IllegalArgumentException("每份手机保留稿最多256KiB");byte[] bytes=new byte[b.remaining()];b.get(bytes);return bytes;
    }
    private static JSONObject header(File file,String project,String keep,InputStream in)throws Exception{
        if(!Files.isRegularFile(file.toPath(),LinkOption.NOFOLLOW_LINKS)||file.length()>MAX_BYTES+HEADER+1)throw new IOException("原保留稿不是有效的本机记录，未展示替代内容");
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();int c;while((c=in.read())!=-1&&c!='\n'){if(bytes.size()>=HEADER)throw new IOException("原稿头超限");bytes.write(c);}
        if(c!='\n')throw new IOException("未读到完整保留稿头");JSONObject h=new JSONObject(bytes.toString("UTF-8"));
        int n=h.getInt("bytes"),base=h.getInt("baseVersion");String revision=h.getString("baseRevisionId"),hash=h.getString("baseSha256");
        if(!"pocketworld.formal-draft.v1".equals(h.getString("format"))||!project.equals(h.getString("projectId"))||!keep.equals(h.getString("keepId"))||!path(h.getString("path"))||
            h.length()!=10||n<0||n>MAX_BYTES||file.length()!=bytes.size()+1L+n||!sha(h.getString("sha256"))||h.getLong("keptAt")<=0||base<0||
            !(base==0?revision.isEmpty()&&"absent".equals(hash):id(revision)&&sha(hash)))throw new IOException("原项目、基线或草稿标识不符");
        return h;
    }
    private static InputStream open(File f)throws IOException{return Files.newInputStream(f.toPath(),LinkOption.NOFOLLOW_LINKS);}
    private List<JSONObject> metadata(String project)throws Exception{
        File dir=directory(project);if(!Files.exists(dir.toPath(),LinkOption.NOFOLLOW_LINKS))return new ArrayList<>();
        if(!Files.isDirectory(dir.toPath(),LinkOption.NOFOLLOW_LINKS))throw new IOException("原项目草稿目录不可核对");
        File[] names=dir.listFiles((d,n)->n.endsWith(".draft"));if(names==null||names.length>COUNT)throw new IOException("原项目保留稿清单不完整或超出上限，未忽略旧稿");
        List<JSONObject> rows=new ArrayList<>();for(File f:names){String name=f.getName(),key=name.substring(0,name.length()-6);if(!id(key))throw new IOException("原稿文件登记无效");
            try(InputStream in=open(f)){rows.add(header(f,project,key,in));}}
        rows.sort(Comparator.<JSONObject>comparingLong(v->v.optLong("keptAt")).reversed().thenComparing(v->v.optString("keepId")));return rows;
    }
    public List<JSONObject> list(String project,String exactPath)throws Exception{synchronized(LOCK){if(exactPath!=null&&!path(exactPath))throw new IllegalArgumentException("原稿路径无效");
        List<JSONObject> rows=metadata(project);if(exactPath!=null)rows.removeIf(r->!exactPath.equals(r.optString("path")));return rows;}}
    public JSONObject keep(String project,String relative,int base,String revision,String baseSha,String text,Current visible)throws Exception{
        if(!path(relative)||base<0||!(base==0?revision.isEmpty()&&"absent".equals(baseSha):id(revision)&&sha(baseSha)))throw new IllegalArgumentException("原文字或新文件基线不可核对");
        byte[] value=utf8(text);try{synchronized(LOCK){visible.check();String hash=ProjectFileDeliveryBytes.sha(value);List<JSONObject> rows=metadata(project);long used=0;
            for(JSONObject row:rows){used+=row.getInt("bytes");if(relative.equals(row.getString("path"))&&base==row.getInt("baseVersion")&&revision.equals(row.getString("baseRevisionId"))&&baseSha.equals(row.getString("baseSha256"))&&hash.equals(row.getString("sha256"))){
                read(project,relative,row.getString("keepId"),hash);visible.check();return row;}}
            if(rows.size()>=COUNT||used+value.length>TOTAL)throw new IOException("此项目保留稿已达16份或2MiB；未挤掉旧稿，请先核对和移除不需要的原稿");
            String keep=UUID.randomUUID().toString();JSONObject h=new JSONObject().put("format","pocketworld.formal-draft.v1").put("projectId",project).put("path",relative)
             .put("keepId",keep).put("baseVersion",base).put("baseRevisionId",revision).put("baseSha256",baseSha).put("sha256",hash).put("bytes",value.length).put("keptAt",System.currentTimeMillis());
            File dir=directory(project);Files.createDirectories(dir.toPath());if(!Files.isDirectory(dir.toPath(),LinkOption.NOFOLLOW_LINKS))throw new IOException("本机草稿目录不符");
            Path pending=Files.createTempFile(dir.toPath(),"pending-",".tmp"),dest=target(project,keep).toPath();
            try{byte[] pre=h.toString().getBytes(StandardCharsets.UTF_8);if(pre.length>HEADER)throw new IOException("本机原稿标识超限");
                try(FileOutputStream out=new FileOutputStream(pending.toFile())){out.write(pre);out.write('\n');out.write(value);out.getFD().sync();}
                visible.check();if(Files.exists(dest,LinkOption.NOFOLLOW_LINKS))throw new IOException("本机原稿位置已被占用，未覆盖");try{Files.move(pending,dest,StandardCopyOption.ATOMIC_MOVE);}catch(AtomicMoveNotSupportedException e){Files.move(pending,dest);}
                read(project,relative,keep,hash);return h;
            }finally{Files.deleteIfExists(pending);}
        }}finally{Arrays.fill(value,(byte)0);}
    }
    public JSONObject read(String project,String relative,String keep,String hash)throws Exception{synchronized(LOCK){
        File f=target(project,keep);try(InputStream in=open(f)){JSONObject h=header(f,project,keep,in);
            if(!relative.equals(h.getString("path"))||!hash.equals(h.getString("sha256")))throw new IOException("原稿已换，不会打开同名内容");
            byte[] value=new byte[h.getInt("bytes")];try{new DataInputStream(in).readFully(value);
                if(in.read()!=-1||!hash.equals(ProjectFileDeliveryBytes.sha(value)))throw new IOException("保留稿的实际字节不符，未使用不完整的文字");
                String exact=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(value)).toString();
                if(exact.length()>MAX_TEXT)throw new IOException("保留稿超过文字上限");return h.put("text",exact);
            }finally{Arrays.fill(value,(byte)0);}
        }}}
    public void discard(String project,String relative,String keep,String hash,Current visible)throws Exception{synchronized(LOCK){
        File f=target(project,keep);try(InputStream in=open(f)){JSONObject h=header(f,project,keep,in);if(!relative.equals(h.getString("path"))||!hash.equals(h.getString("sha256")))throw new IOException("原稿已换，未移除");}
        visible.check();Files.delete(f.toPath());
    }}
}
