package dev.pocketengine.features.workspace;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** Private, transient index of actually retained tombstones in ONE already selected formal project. */
public final class RemovedProjectFiles {
    private RemovedProjectFiles(){}
    private static final int PAGE=20;
    private static final String ID="[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}";
    private record Pin(String path,int version,String revision,long at){
        JSONObject row(int index)throws Exception{return new JSONObject().put("index",index).put("path",path).put("version",version).put("revisionId",revision).put("at",at);}
    }
    public static final class Shelf{
        private final VersionedWorkspace issuer;private final List<Pin> pins;public final String handle=UUID.randomUUID().toString(),project;
        private Shelf(VersionedWorkspace ws,String project,List<Pin> pins){issuer=ws;this.project=project;this.pins=java.util.Collections.unmodifiableList(new ArrayList<>(pins));}
        public JSONObject page(int start)throws Exception{
            if(start<0||start%PAGE!=0||start>=Math.max(1,pins.size()))throw new IllegalArgumentException("请从这次原项目清单继续翻页");
            JSONArray rows=new JSONArray();int end=Math.min(start+PAGE,pins.size());for(int i=start;i<end;i++)rows.put(pins.get(i).row(i));
            return new JSONObject().put("format","removed_formal_files_v1").put("projectId",project).put("handle",handle).put("start",start)
                    .put("next",end<pins.size()?end:-1).put("count",pins.size()).put("files",rows);
        }
        private JSONObject record(VersionedWorkspace ws,File directory,int index,BooleanSupplier live)throws Exception{
            check(live);if(ws!=issuer||index<0||index>=pins.size())throw new SecurityException("未选择这次原项目清单内的删除记录");
            Pin original=pins.get(index);Path file=directory.toPath().resolve(name(original.path));JSONObject record=read(file,live);
            int n=record.getJSONArray("history").length();if(n<1||n>8)throw new IllegalStateException("保留版本数不符");
            JSONObject end=record.getJSONArray("history").getJSONObject(n-1);
            if(!original.path.equals(record.opt("path"))||record.opt("deleted")!=Boolean.TRUE||end.opt("deleted")!=Boolean.TRUE||
                    original.version!=record.getInt("version")||original.version!=end.getInt("version")||!original.revision.equals(record.opt("revisionId"))||!original.revision.equals(end.opt("revisionId"))||original.at!=end.getLong("at"))
                throw new IllegalStateException("这个文件已重建或删除记录已变化；没有改开新版本，请重新读取清单");
            return record;
        }
        JSONObject versions(VersionedWorkspace ws,File directory,int index,BooleanSupplier live)throws Exception{
            JSONObject record=record(ws,directory,index,live);Pin original=pins.get(index);
            JSONArray history=ws.historyOfRecord(project,original.path,record),values=new JSONArray();
            for(int i=0;i<history.length();i++){check(live);JSONObject r=history.getJSONObject(i),v=new JSONObject();for(String k:new String[]{"projectId","path","version","revisionId","kind","sizeBytes","sha256","deleted","tombstone","at"})v.put(k,r.get(k));values.put(v);}
            check(live);return new JSONObject().put("format","removed_formal_file_history_v1").put("handle",handle).put("projectId",project).put("selected",original.row(index)).put("versions",values);
        }
        JSONObject originalText(VersionedWorkspace ws,File directory,int index,JSONObject selected,BooleanSupplier live)throws Exception{
            JSONObject record=record(ws,directory,index,live);Pin original=pins.get(index);
            if(!project.equals(selected.opt("projectId"))||!original.path.equals(selected.opt("path"))||!"text".equals(selected.opt("kind"))||
                    selected.opt("deleted")!=Boolean.FALSE||selected.opt("tombstone")!=Boolean.FALSE||selected.getInt("version")>=original.version)
                throw new SecurityException("仅能阅读这个删除记录中明确选过的较早文字版本");
            JSONArray history=record.getJSONArray("history");
            for(int i=0;i<history.length();i++){JSONObject entry=history.getJSONObject(i);if(entry.getInt("version")!=selected.getInt("version"))continue;
                JSONObject out=ws.originalTextOfRecord(project,original.path,entry,selected);
                check(live);return out.put("format","removed_formal_original_text_v1").put("handle",handle).put("projectId",project).put("selected",original.row(index));}
            throw new IllegalStateException("所选修订已不在本文件的保留版本内，没有改读最新文件");
        }
    }
    private static void check(BooleanSupplier live){if(!live.getAsBoolean()||Thread.currentThread().isInterrupted())throw new IllegalStateException("已离开原项目，不再读取删除记录");}
    private static String name(String path)throws Exception{
        byte[] b=MessageDigest.getInstance("SHA-256").digest(path.getBytes(StandardCharsets.UTF_8));StringBuilder s=new StringBuilder();for(byte v:b)s.append(String.format(java.util.Locale.ROOT,"%02x",v&255));return s+".json";
    }
    private static JSONObject read(Path path,BooleanSupplier live)throws Exception{
        var a=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if(!a.isRegularFile()||a.size()<1||a.size()>VersionedWorkspace.MAX_PROJECT_RECORD_BYTES)throw new IllegalStateException("原项目记录不可读");
        ByteArrayOutputStream bytes=new ByteArrayOutputStream((int)Math.min(a.size(),8192));byte[] buf=new byte[8192];
        try(var in=new BufferedInputStream(Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS))){int count;while((count=in.read(buf))!=-1){
            check(live);if(bytes.size()+count>a.size())throw new IllegalStateException("正在读取的项目记录已变化");bytes.write(buf,0,count);}}
        var b=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if(!b.isRegularFile()||a.size()!=b.size()||a.size()!=bytes.size()||!a.lastModifiedTime().equals(b.lastModifiedTime())||!java.util.Objects.equals(a.fileKey(),b.fileKey()))
            throw new IllegalStateException("项目记录已变化，请重新读取");
        return new JSONObject(StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes.toByteArray())).toString());
    }
    static File folder(File root,JSONArray projects,String id)throws Exception{
        if(id==null||!id.matches(ID)||Files.isSymbolicLink(root.toPath()))throw new SecurityException("原项目身份不可核对");
        boolean found=false;for(int i=0;i<projects.length();i++)found|=id.equals(projects.getJSONObject(i).opt("id"));if(!found)throw new IllegalArgumentException("原项目不存在");
        File dir=new File(root,id);if(Files.isSymbolicLink(dir.toPath()))throw new SecurityException("项目删除记录不跟随链接");return dir;
    }
    static Shelf load(VersionedWorkspace ws,File dir,String project,BooleanSupplier live)throws Exception{
        check(live);List<Pin> out=new ArrayList<>();
        if(Files.notExists(dir.toPath(),LinkOption.NOFOLLOW_LINKS))return new Shelf(ws,project,out);
        if(!Files.isDirectory(dir.toPath(),LinkOption.NOFOLLOW_LINKS))throw new IllegalStateException("原项目目录不可读");long budget=0;int count=0;
        try(DirectoryStream<Path> files=Files.newDirectoryStream(dir.toPath())){for(Path f:files){
            check(live);String filename=f.getFileName().toString();if(!filename.matches("[a-f0-9]{64}\\.json"))continue;
            if(++count>4096||!Files.isRegularFile(f,LinkOption.NOFOLLOW_LINKS)||(budget+=Files.size(f))>VersionedWorkspace.MAX_PROJECT_RECORD_BYTES)throw new IllegalStateException("原项目记录超过核对范围，未列出部分结果");
            JSONObject h=read(f,live);if(h.opt("deleted")==Boolean.FALSE)continue;
            String path=VersionedWorkspace.path(h.getString("path"));JSONArray all=h.getJSONArray("history");int n=all.length();
            if(h.opt("deleted")!=Boolean.TRUE||!filename.equals(name(path))||n<1||n>8)throw new IllegalStateException("原删除记录的文件身份不完整");
            JSONObject end=all.getJSONObject(n-1);int v=h.getInt("version");String r=h.getString("revisionId");long at=end.getLong("at");
            if(v<1||!r.matches(ID)||v!=end.getInt("version")||!r.equals(end.opt("revisionId"))||end.opt("deleted")!=Boolean.TRUE||!end.optString("sha256").equals("absent")||at<0)
                throw new IllegalStateException("删除记录不属于文件最后一次正式修订");
            out.add(new Pin(path,v,r,at));
        }}
        check(live);out.sort(Comparator.comparingLong(Pin::at).reversed().thenComparing(Pin::path));return new Shelf(ws,project,out);
    }
}
