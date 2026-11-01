package dev.pocketengine.features.local.project;

import dev.pocketengine.features.local.NativeCommandExecutor;
import dev.pocketengine.features.workspace.VersionedWorkspace;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.json.JSONArray;
import org.json.JSONObject;

/** Bounded native reads/search of the selected active tree. Content is evidence, never instructions. */
public final class ProjectFileQueries {
    public static final int PAGE_CHARS=8000,MAX_RESULTS=40;
    @FunctionalInterface public interface Current {void check()throws Exception;}
    private ProjectFileQueries() {}
    private static final class FileInfo {
        final String path,sha;final long bytes,chars,lines;final boolean binary;
        FileInfo(String path,String sha,long bytes,long chars,long lines,boolean binary){this.path=path;this.sha=sha;this.bytes=bytes;this.chars=chars;this.lines=lines;this.binary=binary;}
        JSONObject json()throws Exception{return new JSONObject().put("path",path).put("sha256",sha).put("bytes",bytes).put("binary",binary).put("totalChars",binary?JSONObject.NULL:chars).put("lineCount",binary?JSONObject.NULL:lines);}
    }
    private static SeekableByteChannel open(Path path)throws Exception {
        return Files.newByteChannel(path,java.util.Set.of(StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS));
    }
    private static BufferedReader reader(Path path)throws Exception {
        return new BufferedReader(new InputStreamReader(Channels.newInputStream(open(path)),StandardCharsets.UTF_8.newDecoder()),4096);
    }
    private static Path target(File root,String relative)throws Exception {
        Path path=NativeCommandExecutor.within(root,VersionedWorkspace.path(relative)).toPath();
        if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))throw new IllegalArgumentException("原项目文件不存在或不是普通文件");return path;
    }
    private static FileInfo describe(File root,String relative,Current current)throws Exception {
        Path path=target(root,relative);BasicFileAttributes before=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if(before.size()>ProjectSources.MAX_FILE_BYTES)throw new IllegalArgumentException("文件超过本次2MiB读取范围");
        MessageDigest digest=MessageDigest.getInstance("SHA-256");long bytes=0;boolean nul=false;byte[] block=new byte[8192];
        try(var input=Channels.newInputStream(open(path))){int n;while((n=input.read(block))>=0){current.check();bytes+=n;if(bytes>ProjectSources.MAX_FILE_BYTES)throw new IllegalArgumentException("文件在读取时超过2MiB");for(int i=0;i<n;i++)nul|=block[i]==0;digest.update(block,0,n);}}
        boolean binary=nul;long chars=0,lines=0;boolean any=false,lastNewline=false;
        if(!binary)try(BufferedReader input=reader(path)){char[] values=new char[4096];int n;while((n=input.read(values))>=0){current.check();chars+=n;for(int i=0;i<n;i++){any=true;lastNewline=values[i]=='\n';if(lastNewline)lines++;}}if(any&&!lastNewline)lines++;}
        catch(CharacterCodingException invalid){binary=true;chars=0;lines=0;}
        BasicFileAttributes after=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if(!same(before,after)||bytes!=after.size())throw new SecurityException("文件在读取时变化，请重新读取原路径");
        return new FileInfo(relative,hex(digest.digest()),bytes,chars,lines,binary);
    }
    private static boolean same(BasicFileAttributes a,BasicFileAttributes b){return a.isRegularFile()&&b.isRegularFile()&&a.size()==b.size()&&a.lastModifiedTime().equals(b.lastModifiedTime())&&java.util.Objects.equals(a.fileKey(),b.fileKey());}
    private static String hex(byte[] bytes){StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return out.toString();}
    private static void unchanged(File root,FileInfo info,Current current)throws Exception {
        FileInfo after=describe(root,info.path,current);if(!info.sha.equals(after.sha)||info.bytes!=after.bytes)throw new SecurityException("读取期间文件SHA变化，未返回跨版本内容");
    }
    public static JSONObject read(File root,String checkout,JSONObject input,Current current)throws Exception {
        String relative=VersionedWorkspace.path(input.getString("path"));current.check();FileInfo info=describe(root,relative,current);
        if(input.has("expectedHash")&&!info.sha.equals(input.getString("expectedHash")))throw new SecurityException("文件SHA已变化，请明确重新读取当前版本");
        JSONObject result=info.json().put("checkoutId",checkout).put("untrusted",true).put("source","active_checkout_bytes").put("atomicReadFence",false);
        if(info.binary)return result.put("contentOmitted",true).put("reason","binary_not_utf8_text").put("complete",false).put("nextOffset",JSONObject.NULL);
        String mode=input.optString("rangeMode","chars");if(!java.util.Set.of("chars","lines","bytes").contains(mode))throw new IllegalArgumentException("读取范围模式无效");
        int limit=Math.min(PAGE_CHARS,input.optInt("maxChars",PAGE_CHARS));if(limit<1)throw new IllegalArgumentException("读取长度无效");long start=input.optLong("offset",0),end;StringBuilder text=new StringBuilder();
        if(mode.equals("bytes")){
            if(start<0||start>info.bytes)throw new IllegalArgumentException("字节范围越界");int requestedBytes=input.optInt("maxBytes",limit);if(requestedBytes<1)throw new IllegalArgumentException("字节长度至少为1");int count=(int)Math.min(Math.min(limit,requestedBytes),info.bytes-start);if(count<0)throw new IllegalArgumentException("字节长度无效");
            ByteBuffer bytes=ByteBuffer.allocate(count);try(var channel=open(target(root,relative))){channel.position(start);while(bytes.hasRemaining()&&channel.read(bytes)>=0)current.check();}bytes.flip();
            try{text.append(StandardCharsets.UTF_8.newDecoder().decode(bytes));}catch(CharacterCodingException split){throw new IllegalArgumentException("字节范围切断UTF-8字符，请使用字符/行范围，或选择完整字符边界",split);}end=start+count;
            result.put("byteOffset",start).put("nextByteOffset",end<info.bytes?end:JSONObject.NULL).put("complete",end==info.bytes);
        }else{
            if(start<0||start>info.chars)throw new IllegalArgumentException("字符范围越界");int requestedLine=input.optInt("startLine",1),maxLines=input.optInt("maxLines",200);if(requestedLine<1||maxLines<1||maxLines>400)throw new IllegalArgumentException("行范围无效");
            long position=0,line=1;int seenLines=0,previous=-1;boolean collecting=!mode.equals("lines");
            try(BufferedReader inputReader=reader(target(root,relative))){int value;while((value=inputReader.read())!=-1){if((position&4095)==0)current.check();
                if(mode.equals("lines")&&!collecting&&line>=requestedLine){start=position;collecting=true;}
                if(collecting&&position>=start){if(position==start&&Character.isLowSurrogate((char)value)&&previous>=0&&Character.isHighSurrogate((char)previous))throw new IllegalArgumentException("字符范围切断Unicode字符，请退回一个字符位置");
                    if(text.length()>=limit||mode.equals("lines")&&seenLines>=maxLines)break;text.append((char)value);if(value=='\n')seenLines++;}
                previous=value;position++;if(value=='\n')line++;
            }}
            if(mode.equals("lines")&&!collecting){if(requestedLine>info.lines+1)throw new IllegalArgumentException("起始行超过文件范围");start=info.chars;}
            if(text.length()>0&&Character.isHighSurrogate(text.charAt(text.length()-1))){text.setLength(text.length()-1);if(text.length()==0)throw new IllegalArgumentException("此页长度容不下完整Unicode字符，请将maxChars至少设为2；未返回零进度cursor");}
            end=start+text.length();result.put("offset",start).put("nextOffset",end<info.chars?end:JSONObject.NULL).put("complete",end==info.chars).put("rangeMode",mode);if(mode.equals("lines"))result.put("startLine",requestedLine).put("returnedLineBreaks",seenLines);
        }
        unchanged(root,info,current);return result.put("content",text.toString()).put("rangeMode",mode).put("returnedChars",text.length());
    }
    private static boolean hidden(String path){for(String part:path.split("/"))if(part.startsWith("."))return true;return false;}
    /** A glob is not a Java regular expression. Two bounded DP rows prevent exponential backtracking. */
    public static final class Glob {
        private static final int LITERAL=0,ONE=1,STAR=2,ALL=3,DIRECTORIES=4;
        private final int[] kinds,values;private final boolean sensitive;
        private Glob(int[] kinds,int[] values,boolean sensitive){this.kinds=kinds;this.values=values;this.sensitive=sensitive;}
        public boolean matches(String value)throws Exception{return matches(value,()->{});}
        private boolean matches(String value,Current current)throws Exception {
            if(value==null||value.length()>240)throw new IllegalArgumentException("glob目标超出项目路径240字符范围");current.check();int[] path=value.codePoints().toArray();boolean[] previous=new boolean[path.length+1],next=new boolean[path.length+1];previous[0]=true;
            for(int token=0;token<kinds.length;token++){
                if((token&31)==0)current.check();java.util.Arrays.fill(next,false);boolean prefix=false;
                for(int at=0;at<=path.length;at++){
                    switch(kinds[token]){
                        case STAR:next[at]=previous[at]||at>0&&path[at-1]!='/'&&next[at-1];break;
                        case ALL:next[at]=previous[at]||at>0&&next[at-1];break;
                        case DIRECTORIES:next[at]=previous[at]||at>0&&path[at-1]=='/'&&prefix;prefix|=previous[at];break;
                        case ONE:next[at]=at>0&&path[at-1]!='/'&&previous[at-1];break;
                        default:next[at]=at>0&&sameCodePoint(path[at-1],values[token],sensitive)&&previous[at-1];
                    }
                }
                boolean[] swap=previous;previous=next;next=swap;
            }
            return previous[path.length];
        }
        private static boolean sameCodePoint(int a,int b,boolean sensitive){return a==b||!sensitive&&(Character.toLowerCase(a)==Character.toLowerCase(b)||Character.toUpperCase(a)==Character.toUpperCase(b));}
    }
    /** Explicit syntax: star, double-star, directory double-star, and question mark, with ? matching one Unicode code point. */
    public static Glob glob(String expression){return glob(expression,true);}
    private static Glob glob(String expression,boolean sensitive){
        if(expression==null||expression.length()>240||expression.indexOf('\\')>=0||expression.indexOf('\0')>=0||expression.startsWith("/"))throw new IllegalArgumentException("路径glob无效");
        int[] input=expression.codePoints().toArray();java.util.List<Integer> kinds=new ArrayList<>(),values=new ArrayList<>();
        for(int at=0;at<input.length;at++){
            int value=input[at],kind=Glob.LITERAL;
            if(value=='*'){int stars=1;while(at+1<input.length&&input[at+1]=='*'){stars++;at++;}kind=stars>1?Glob.ALL:Glob.STAR;if(stars>1&&at+1<input.length&&input[at+1]=='/'){kind=Glob.DIRECTORIES;at++;}}
            else if(value=='?')kind=Glob.ONE;kinds.add(kind);values.add(value);
        }
        return new Glob(kinds.stream().mapToInt(Integer::intValue).toArray(),values.stream().mapToInt(Integer::intValue).toArray(),sensitive);
    }
    public static JSONObject search(File root,String checkout,JSONObject input,Current current)throws Exception {
        String mode=input.optString("mode","text"),query=input.getString("query"),scope=input.optString("path","");boolean includeHidden=input.optBoolean("hidden"),caseSensitive=input.optBoolean("caseSensitive",true);
        if(!java.util.Set.of("path","text").contains(mode)||query.isEmpty()||query.length()>240)throw new IllegalArgumentException("搜索模式/查询无效；本片内容搜索只支持literal，不把Java回溯正则当受控执行");
        if(mode.equals("text")&&(query.indexOf('\n')>=0||query.indexOf('\r')>=0||query.indexOf('\0')>=0))throw new IllegalArgumentException("正文literal搜索按行定位；查询不能包含换行或空字节");
        if(!scope.isEmpty())VersionedWorkspace.path(scope);String include=input.optString("include","**");if(include.isEmpty())include="**";Glob filter=glob(include),pathQuery=mode.equals("path")?glob(query,caseSensitive):null;int limit=input.optInt("limit",20);if(limit<1||limit>MAX_RESULTS)throw new IllegalArgumentException("搜索页最多40项");
        Map<String,Path> paths=new TreeMap<>();JSONArray skipped=new JSONArray();long[] skippedCount={0};boolean[] enumerationComplete={true};
        Path base=scope.isEmpty()?root.toPath():NativeCommandExecutor.within(root,scope).toPath();if(!Files.exists(base,LinkOption.NOFOLLOW_LINKS))throw new IllegalArgumentException("搜索范围不存在");
        Files.walkFileTree(base,new java.nio.file.SimpleFileVisitor<Path>(){
            @Override public java.nio.file.FileVisitResult preVisitDirectory(Path path,BasicFileAttributes attrs)throws java.io.IOException {try{current.check();String name=root.toPath().relativize(path).toString();return !ProjectSources.sourcePath(name)||!includeHidden&&!name.isEmpty()&&hidden(name)?java.nio.file.FileVisitResult.SKIP_SUBTREE:java.nio.file.FileVisitResult.CONTINUE;}catch(Exception stop){throw new java.io.IOException(stop);}}
            @Override public java.nio.file.FileVisitResult visitFile(Path path,BasicFileAttributes attrs)throws java.io.IOException {try{current.check();String name=root.toPath().relativize(path).toString();if(!ProjectSources.sourcePath(name)||!includeHidden&&hidden(name))return java.nio.file.FileVisitResult.CONTINUE;
                if(!attrs.isRegularFile()||attrs.isSymbolicLink()||attrs.size()>ProjectSources.MAX_FILE_BYTES){skip(name,!attrs.isRegularFile()||attrs.isSymbolicLink()?"symlink_or_special":"over_2MiB");return java.nio.file.FileVisitResult.CONTINUE;}
                if(paths.size()>=ProjectSources.MAX_FILES){enumerationComplete[0]=false;return java.nio.file.FileVisitResult.TERMINATE;}paths.put(name,path);return java.nio.file.FileVisitResult.CONTINUE;}catch(Exception stop){throw new java.io.IOException(stop);}}
            private void skip(String path,String reason)throws Exception {skippedCount[0]++;if(skipped.length()<10)skipped.put(new JSONObject().put("path",path).put("reason",reason));}
        });
        List<FileInfo> facts=new ArrayList<>();JSONArray manifest=new JSONArray();long total=0;int binary=0;boolean byteBudget=true;
        for(String path:paths.keySet()){current.check();FileInfo info=describe(root,path,current);if(total+info.bytes>ProjectSources.MAX_TOTAL_BYTES){byteBudget=false;break;}total+=info.bytes;facts.add(info);manifest.put(new JSONObject().put("path",path).put("sha256",info.sha).put("bytes",info.bytes));if(info.binary)binary++;}
        String manifestHash=ProjectSources.manifestDigest(manifest);JSONObject searchIdentity=new JSONObject().put("checkoutId",checkout).put("manifestHash",manifestHash).put("mode",mode).put("query",query).put("path",scope).put("include",include).put("hidden",includeHidden).put("caseSensitive",caseSensitive);String queryHash=ProjectSources.digest(searchIdentity);
        JSONObject cursor=input.optJSONObject("cursor");String afterPath="";int afterLine=0;if(cursor!=null){if(!queryHash.equals(cursor.getString("queryHash"))||!manifestHash.equals(cursor.getString("manifestHash")))throw new SecurityException("搜索cursor所属查询或实际文件版本已变化，请重新搜索");afterPath=VersionedWorkspace.path(cursor.getString("path"));afterLine=cursor.getInt("line");if(afterLine<0)throw new SecurityException("搜索cursor无效");}
        JSONArray matches=new JSONArray();int resultBytes=0;boolean more=false;String lastPath="";int lastLine=0;String needle=query;
        search:for(FileInfo info:facts){if(!filter.matches(info.path,current)||info.path.compareTo(afterPath)<0)continue;
            if(mode.equals("path")){if(!pathQuery.matches(info.path,current)||info.path.equals(afterPath))continue;JSONObject match=info.json();int bytes=match.toString().getBytes(StandardCharsets.UTF_8).length;if(matches.length()==limit||resultBytes+bytes>12000){more=true;break;}matches.put(match);resultBytes+=bytes;lastPath=info.path;lastLine=0;continue;}
            if(info.binary)continue;int line=0;try(BufferedReader source=reader(target(root,info.path))){String value;while((value=boundedLine(source,current))!=null){line++;if(info.path.equals(afterPath)&&line<=afterLine)continue;int column=findLiteral(value,needle,caseSensitive,current);if(column<0)continue;if(matches.length()==limit){more=true;break search;}int from=Math.max(0,column-100),to=Math.min(value.length(),column+query.length()+300);JSONObject match=new JSONObject().put("path",info.path).put("sha256",info.sha).put("bytes",info.bytes).put("line",line).put("column",column+1).put("columnUnit","UTF16_code_units").put("text",value.substring(from,to)).put("linePreviewOffset",from).put("linePreviewTruncated",from>0||to<value.length());int bytes=match.toString().getBytes(StandardCharsets.UTF_8).length;if(resultBytes+bytes>12000){more=true;break search;}matches.put(match);resultBytes+=bytes;lastPath=info.path;lastLine=line;}}
            unchanged(root,info,current);
        }
        // Recheck every participating file, including a result page stopped in the middle of one file.
        for(FileInfo info:facts)unchanged(root,info,current);
        boolean coverage=enumerationComplete[0]&&byteBudget&&skippedCount[0]==0;
        return new JSONObject().put("checkoutId",checkout).put("queryHash",queryHash).put("manifestHash",manifestHash).put("mode",mode).put("matches",matches).put("nextCursor",more?new JSONObject().put("queryHash",queryHash).put("manifestHash",manifestHash).put("path",lastPath).put("line",lastLine):JSONObject.NULL).put("hasMore",more).put("coverageComplete",coverage).put("enumerationComplete",enumerationComplete[0]).put("byteBudgetComplete",byteBudget).put("coverageScope","eligible_source_paths_or_UTF8_text_only").put("resultBytes",resultBytes).put("scannedFiles",facts.size()).put("scannedBytes",total).put("binaryFilesSkippedForText",mode.equals("text")?binary:0).put("skippedCount",skippedCount[0]).put("skipped",skipped).put("scope",scope).put("include",include).put("hidden",includeHidden).put("excluded",new JSONArray(new String[]{"dependency_caches","build_outputs","symlinks","special_files"})).put("pathGlobEngine","bounded_dynamic_programming").put("pathGlobUnicodeUnit","code_point").put("searchSyntax",mode.equals("path")?"glob_star_double_star_question":"literal").put("untrusted",true).put("atomicReadFence",false);
    }
    private static int findLiteral(String text,String query,boolean sensitive,Current current)throws Exception {
        if(sensitive)return text.indexOf(query);for(int i=0;i<=text.length()-query.length();i++){if((i&4095)==0)current.check();if(text.regionMatches(true,i,query,0,query.length()))return i;}return -1;
    }
    // A line is bounded by the existing 2MiB per-file ceiling, never the entire project.
    private static String boundedLine(BufferedReader input,Current current)throws Exception {StringBuilder out=new StringBuilder();int c;while((c=input.read())!=-1){if((out.length()&4095)==0)current.check();if(c=='\n')return out.toString();out.append((char)c);if(out.length()>ProjectSources.MAX_FILE_BYTES)throw new IllegalStateException("单行超过本次读取范围");}return out.length()==0?null:out.toString();}
}
