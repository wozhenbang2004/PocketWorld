package dev.pocketengine.features.local.project;

import dev.pocketengine.features.local.NativeCommandExecutor;
import dev.pocketengine.features.workspace.VersionedWorkspace;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import org.json.*;

/** Direct-child metadata of one selected source directory; never follows links or reads file bodies. */
public final class ProjectDirectoryQuery {
    public static final int MAX_ENTRIES=2000,MAX_PAGE=40,MAX_RESULT_BYTES=12000;
    private static final String CONTRACT="pocketworld.source-directory-metadata.v1";
    private ProjectDirectoryQuery(){}
    private static Set<String> keys(JSONObject object){Set<String> result=new TreeSet<>();object.keys().forEachRemaining(result::add);return result;}
    private static boolean hidden(String path){for(String part:path.split("/"))if(part.startsWith("."))return true;return false;}
    private static boolean same(BasicFileAttributes a,BasicFileAttributes b){return a.isDirectory()&&b.isDirectory()&&a.lastModifiedTime().equals(b.lastModifiedTime())&&Objects.equals(a.fileKey(),b.fileKey());}
    private static Path directory(File root,String relative)throws Exception{
        File selected=relative.isEmpty()?root:NativeCommandExecutor.within(root,relative);
        if(!selected.getAbsoluteFile().equals(selected.getCanonicalFile()))throw new SecurityException("目录路径不能包含符号链接");
        BasicFileAttributes attrs=Files.readAttributes(selected.toPath(),BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if(!attrs.isDirectory()||attrs.isSymbolicLink())throw new IllegalArgumentException("原项目目录不存在或不是普通目录");return selected.toPath();
    }
    private static final class Snapshot {
        final SortedMap<String,JSONObject> entries=new TreeMap<>();final JSONObject omitted=new JSONObject();String digest;int examined;
    }
    private static void omit(Snapshot snapshot,String reason)throws Exception{snapshot.omitted.put(reason,snapshot.omitted.optInt(reason)+1);}
    private static Snapshot scan(File root,String relative,boolean includeHidden,ProjectFileQueries.Current current)throws Exception{
        current.check();Path base=directory(root,relative);BasicFileAttributes before=Files.readAttributes(base,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);Snapshot snapshot=new Snapshot();JSONArray identity=new JSONArray();SortedMap<String,JSONObject> privateFacts=new TreeMap<>();
        try(DirectoryStream<Path> children=Files.newDirectoryStream(base)){
            for(Path child:children){current.check();if(++snapshot.examined>MAX_ENTRIES)throw new IllegalArgumentException("此单层目录超过2000条枚举预算，未返回不完整分页；请选择更小子目录");
                String path=relative.isEmpty()?child.getFileName().toString():relative+"/"+child.getFileName();
                if(!ProjectSources.sourcePath(path)){omit(snapshot,"dependency_or_build");continue;}
                if(!includeHidden&&hidden(path)){omit(snapshot,"hidden");continue;}
                try{VersionedWorkspace.path(path);}catch(IllegalArgumentException invalid){omit(snapshot,"invalid_source_path");continue;}
                BasicFileAttributes attrs=Files.readAttributes(child,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
                String kind=attrs.isDirectory()?"directory":attrs.isRegularFile()?"regular_file":attrs.isSymbolicLink()?"symlink":"special";
                if(!attrs.isDirectory()&&!attrs.isRegularFile()){omit(snapshot,kind);privateFacts.put(path,new JSONObject().put("path",path).put("kind",kind));continue;}
                JSONObject fact=new JSONObject().put("path",path).put("name",child.getFileName().toString()).put("entryKind",kind).put("bytes",attrs.isRegularFile()?attrs.size():JSONObject.NULL).put("lastModifiedMillis",attrs.lastModifiedTime().toMillis()).put("sha256",JSONObject.NULL).put("hashAvailable",false).put("recursiveSizeComputed",false);
                snapshot.entries.put(path,fact);privateFacts.put(path,new JSONObject(fact.toString()).put("nativeKey",String.valueOf(attrs.fileKey())).put("nativeModified",attrs.lastModifiedTime().toString()));
            }
        }
        current.check();BasicFileAttributes after=Files.readAttributes(directory(root,relative),BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);if(!same(before,after))throw new SecurityException("目录读取期间变化，未返回混合版本；请重新列出");
        for(JSONObject fact:privateFacts.values())identity.put(fact);
        snapshot.digest=ProjectSources.digest(new JSONObject().put("contract",CONTRACT).put("path",relative).put("directoryKey",String.valueOf(after.fileKey())).put("directoryModified",after.lastModifiedTime().toString()).put("entries",identity).put("omitted",snapshot.omitted).put("examined",snapshot.examined));return snapshot;
    }
    public static JSONObject list(File root,String checkout,JSONObject input,ProjectFileQueries.Current current)throws Exception{
        if(!Set.of("path","hidden","limit","cursor","checkoutId","runtimeId").containsAll(keys(input)))throw new IllegalArgumentException("目录列表只接受path/hidden/limit/cursor");
        Object rawPath=input.opt("path");if(rawPath!=null&&!(rawPath instanceof String))throw new IllegalArgumentException("path需相对目录文字，空字串表示当前副本根");String relative=input.optString("path","");if(!relative.isEmpty())relative=VersionedWorkspace.path(relative);
        if(input.has("hidden")&&!(input.opt("hidden") instanceof Boolean))throw new IllegalArgumentException("hidden需明确布尔值");boolean includeHidden=input.optBoolean("hidden");if(!ProjectSources.sourcePath(relative)||!includeHidden&&hidden(relative))throw new SecurityException("目录不在原source/hidden范围内");
        Object rawLimit=input.opt("limit");if(rawLimit!=null&&(!(rawLimit instanceof Integer||rawLimit instanceof Long)||((Number)rawLimit).longValue()<1||((Number)rawLimit).longValue()>MAX_PAGE))throw new IllegalArgumentException("目录每页需1至40条准确整数");int limit=rawLimit==null?MAX_PAGE:((Number)rawLimit).intValue();
        String query=ProjectSources.digest(new JSONObject().put("contract",CONTRACT).put("checkoutId",checkout).put("path",relative).put("hidden",includeHidden).put("limit",limit));
        Snapshot snapshot=scan(root,relative,includeHidden,current);current.check();Snapshot verified=scan(root,relative,includeHidden,current);if(!snapshot.digest.equals(verified.digest))throw new SecurityException("目录元数据读取期间变化，请重新列出，不拼接不同目录版本");current.check();String after="";
        if(input.has("cursor")){JSONObject cursor=input.getJSONObject("cursor");if(!keys(cursor).equals(Set.of("queryHash","listingDigest","afterPath"))||!query.equals(cursor.getString("queryHash"))||!snapshot.digest.equals(cursor.getString("listingDigest")))throw new SecurityException("目录/范围/页大小或元数据已变化，旧cursor未继续");after=cursor.getString("afterPath");if(!snapshot.entries.containsKey(after))throw new SecurityException("目录cursor不是原准确分页末项");}
        JSONArray page=new JSONArray();JSONObject result=new JSONObject().put("checkoutId",checkout).put("path",relative).put("hidden",includeHidden).put("queryHash",query).put("listingDigest",snapshot.digest).put("entries",page).put("totalEligibleEntries",snapshot.entries.size()).put("examinedEntries",snapshot.examined).put("entryEnumerationComplete",true).put("omitted",snapshot.omitted).put("singleLevel",true).put("coverageScope","eligible_direct_source_children").put("fileBodiesRead",false).put("contentVersionVerified",false).put("atomicReadFence",false).put("untrusted",true).put("authorityAdded",false).put("source","active_checkout_native_directory_metadata");
        String last=after;int eligibleRemaining=0;for(var row:snapshot.entries.entrySet()){if(row.getKey().compareTo(after)<=0)continue;eligibleRemaining++;if(page.length()>=limit)continue;page.put(row.getValue());JSONObject candidateCursor=new JSONObject().put("queryHash",query).put("listingDigest",snapshot.digest).put("afterPath",row.getKey());result.put("nextCursor",candidateCursor).put("hasMore",true).put("complete",false);if(result.toString().getBytes(StandardCharsets.UTF_8).length>MAX_RESULT_BYTES-100){page.remove(page.length()-1);break;}last=row.getKey();}
        if(eligibleRemaining>0&&page.length()==0)throw new IllegalStateException("单个目录元数据超过结果预算，未返回零进度cursor");boolean more=false;for(String path:snapshot.entries.keySet())if(path.compareTo(last)>0){more=true;break;}
        result.put("hasMore",more).put("complete",!more).put("nextCursor",more?new JSONObject().put("queryHash",query).put("listingDigest",snapshot.digest).put("afterPath",last):JSONObject.NULL).put("coverageComplete",snapshot.omitted.length()==0).put("maxEntriesPerScan",MAX_ENTRIES).put("metadataScans",2);if(result.toString().getBytes(StandardCharsets.UTF_8).length>MAX_RESULT_BYTES)throw new IllegalStateException("目录元数据结果超过12KiB，未截断JSON");return result;
    }
}
