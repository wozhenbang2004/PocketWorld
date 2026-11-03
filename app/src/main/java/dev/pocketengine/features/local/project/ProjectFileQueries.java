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
    public static final int MAX_HEX_WINDOW_BYTES=2048;
    public static final int MAX_CONTEXT_LINES=20,MAX_CONTEXT_BYTES=12000;
    private static final int CONTEXT_LINE_CHARS=400;
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
        String relative=VersionedWorkspace.path(input.getString("path"));current.check();
        if(input.has("metadataOnly")&&!(input.opt("metadataOnly") instanceof Boolean))throw new IllegalArgumentException("metadataOnly需明确布尔值");
        if(input.optBoolean("metadataOnly"))return metadata(root,checkout,relative,input,current);
        FileInfo info=describe(root,relative,current);
        if(input.has("expectedHash")&&!info.sha.equals(input.getString("expectedHash")))throw new SecurityException("文件SHA已变化，请明确重新读取当前版本");
        JSONObject result=info.json().put("checkoutId",checkout).put("untrusted",true).put("source","active_checkout_bytes").put("atomicReadFence",false);
        if("hex".equals(input.optString("rangeMode")))return hexWindow(root,info,input,result,current);
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
    /** Explicit raw-byte evidence only. Hex/ASCII expansion has a separate 2KiB byte budget;
     * no UTF-8 decoding, binary write authority, arbitrary native path or new tool is implied. */
    private static JSONObject hexWindow(File root,FileInfo info,JSONObject input,JSONObject result,Current current)throws Exception {
        for(String text:new String[]{"maxChars","startLine","maxLines"})if(input.has(text))throw new IllegalArgumentException("hex仅接受字节offset/maxBytes，不能混合字符或行范围");
        long start=input.has("offset")?hexInteger(input.get("offset"),"offset"):0,requested=input.has("maxBytes")?hexInteger(input.get("maxBytes"),"maxBytes"):MAX_HEX_WINDOW_BYTES;
        if(start<0||start>info.bytes||requested<1)throw new IllegalArgumentException("hex字节范围或长度无效");int count=(int)Math.min(Math.min((long)MAX_HEX_WINDOW_BYTES,requested),info.bytes-start);ByteBuffer buffer=ByteBuffer.allocate(count);
        try(var channel=open(target(root,info.path))){channel.position(start);while(buffer.hasRemaining()){current.check();if(channel.read(buffer)<0)throw new java.io.EOFException("原文件在完整字节窗口前结束，未返回半份内容");}}
        StringBuilder ascii=new StringBuilder(count);byte[] bytes=buffer.array();for(byte value:bytes){int unsigned=value&255;ascii.append(unsigned>=32&&unsigned<=126?(char)unsigned:'.');}unchanged(root,info,current);long end=start+count;
        return result.put("rangeMode","hex").put("byteOffset",start).put("returnedBytes",count).put("maxWindowBytes",MAX_HEX_WINDOW_BYTES).put("byteLimitApplied",requested>MAX_HEX_WINDOW_BYTES).put("nextByteOffset",end<info.bytes?end:JSONObject.NULL).put("complete",end==info.bytes)
                .put("hex",hex(bytes)).put("hexEncoding","lowercase_two_digits_per_byte").put("ascii",ascii.toString()).put("asciiPolicy","printable_0x20_to_0x7e_else_dot").put("contentEncoding","hex_and_ascii_not_decoded_text");
    }
    private static long hexInteger(Object value,String name){
        if(!(value instanceof Number))throw new IllegalArgumentException("hex "+name+"需为准确整数");try{return new java.math.BigDecimal(value.toString()).longValueExact();}catch(NumberFormatException|ArithmeticException invalid){throw new IllegalArgumentException("hex "+name+"需为准确整数",invalid);}
    }
    /** Only ENOENT is absence. AccessDenied/IO/cancellation remain failures, never empty files. */
    private static JSONObject metadata(File root,String checkout,String relative,JSONObject input,Current current)throws Exception {
        for(String range:new String[]{"rangeMode","offset","maxChars","startLine","maxLines","maxBytes"})if(input.has(range))throw new IllegalArgumentException("metadataOnly不能混合正文分页范围");
        Path path=NativeCommandExecutor.within(root,relative).toPath();BasicFileAttributes before;
        try{before=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);}
        catch(java.nio.file.NoSuchFileException absent){
            current.check();try{Files.readAttributes(NativeCommandExecutor.within(root,relative).toPath(),BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);throw new SecurityException("原路径在元数据观测期间出现，请重新读取");}catch(java.nio.file.NoSuchFileException confirmed){/* Two actual ENOENT observations; still not a create permission. */}
            if(input.has("expectedHash")&&!"absent".equals(input.getString("expectedHash")))throw new SecurityException("原路径不存在，不能满足指定内容SHA");
            return metadataView(checkout,relative).put("exists",false).put("entryKind","missing").put("bytes",JSONObject.NULL).put("sha256",JSONObject.NULL).put("hashAvailable",false).put("reason","not_found");
        }
        if(before.isSymbolicLink())throw new SecurityException("元数据不跟随项目链接");
        FileInfo info=before.isRegularFile()&&before.size()<=ProjectSources.MAX_FILE_BYTES?describe(root,relative,current):null;
        current.check();BasicFileAttributes after=Files.readAttributes(NativeCommandExecutor.within(root,relative).toPath(),BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if(before.isRegularFile()!=after.isRegularFile()||before.isDirectory()!=after.isDirectory()||before.isOther()!=after.isOther()||after.isSymbolicLink()||before.size()!=after.size()||!before.lastModifiedTime().equals(after.lastModifiedTime())||!java.util.Objects.equals(before.fileKey(),after.fileKey()))throw new SecurityException("原文件类型或元数据在读取期间变化，请重新观测");
        if(input.has("expectedHash")&&(info==null||!info.sha.equals(input.getString("expectedHash"))))throw new SecurityException("原项无法确认指定内容SHA，未把缺失hash当匹配");
        JSONObject result=metadataView(checkout,relative).put("exists",true).put("entryKind",after.isRegularFile()?"regular_file":after.isDirectory()?"directory":"special")
            .put("lastModifiedMillis",after.lastModifiedTime().toMillis()).put("bytes",after.isRegularFile()?after.size():JSONObject.NULL).put("recursiveSizeComputed",false).put("sha256",JSONObject.NULL).put("hashAvailable",info!=null);
        if(info!=null){JSONObject facts=info.json();for(java.util.Iterator<String> keys=facts.keys();keys.hasNext();){String key=keys.next();result.put(key,facts.get(key));}}
        else result.put("hashUnavailableReason",after.isRegularFile()?"over_2MiB_hash_limit":"not_a_regular_file");return result;
    }
    private static JSONObject metadataView(String checkout,String relative)throws Exception {
        return new JSONObject().put("checkoutId",checkout).put("path",relative).put("metadataOnly",true).put("metadataComplete",true).put("complete",false).put("contentOmitted",true).put("contentEvidence",false).put("nextOffset",JSONObject.NULL)
            .put("mimeType",JSONObject.NULL).put("mimeSource","not_probed").put("source","active_checkout_native_metadata").put("untrusted",true).put("atomicReadFence",false).put("authorityAdded",false);
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
    /** Raw pattern search is restricted to one exact eligible source file, never a filesystem
     * sweep. Cursor data selects an offset only; original source/run permission stays outside. */
    private static JSONObject searchHex(File root,String checkout,JSONObject input,Current current)throws Exception {
        current.check();for(String text:new String[]{"syntax","include","exclude","caseSensitive","contextBefore","contextAfter","maxEdits"})if(input.has(text))throw new IllegalArgumentException("hex搜索仅按原字节匹配，不接受正文regex/glob/前后文选项");
        if(input.has("hidden")&&!(input.opt("hidden") instanceof Boolean))throw new IllegalArgumentException("hidden需明确布尔值");String path=VersionedWorkspace.path(input.getString("path"));boolean includeHidden=input.optBoolean("hidden");if(!ProjectSources.sourcePath(path)||!includeHidden&&hidden(path))throw new SecurityException("hex搜索路径不在原source/hidden范围内");
        byte[] pattern=ProjectHexPattern.parse(input.getString("query"));long requested=input.has("limit")?hexInteger(input.get("limit"),"limit"):20;if(requested<1||requested>MAX_RESULTS)throw new IllegalArgumentException("搜索页最多40项");int limit=(int)requested;FileInfo info=describe(root,path,current);
        String queryHash=ProjectSources.digest(new JSONObject().put("mode","hex").put("checkoutId",checkout).put("path",path).put("pattern",hex(pattern)).put("hidden",includeHidden).put("patternEngine","bounded_streaming_kmp_v1"));long start=0;
        if(input.has("cursor")){JSONObject cursor=input.optJSONObject("cursor");if(cursor==null)throw new IllegalArgumentException("hex cursor需为原对象");java.util.Set<String> keys=new java.util.HashSet<>();cursor.keys().forEachRemaining(keys::add);if(!keys.equals(java.util.Set.of("queryHash","sha256","path","byteOffset"))||!queryHash.equals(cursor.getString("queryHash"))||!info.sha.equals(cursor.getString("sha256"))||!path.equals(cursor.getString("path")))throw new SecurityException("hex cursor的路径、模式、查询或原文件SHA已变化");start=hexInteger(cursor.get("byteOffset"),"cursor.byteOffset");if(start<0||start>info.bytes)throw new SecurityException("hex cursor超出原字节范围");}
        ProjectHexPattern.Page page;try(var channel=open(target(root,path))){page=ProjectHexPattern.find(channel,info.bytes,start,pattern,limit,current);}JSONArray matches=new JSONArray();int resultBytes=0;boolean resultBudget=false;long next=page.nextOffset;
        for(long at:page.offsets){current.check();JSONObject match=new JSONObject().put("path",path).put("sha256",info.sha).put("byteOffset",at).put("byteLength",pattern.length);int bytes=match.toString().getBytes(StandardCharsets.UTF_8).length;if(resultBytes+bytes>MAX_CONTEXT_BYTES-4096){if(matches.length()==0)throw new IllegalStateException("原hex命中超出本次结果预算，未签发零进度cursor");resultBudget=true;next=at;break;}matches.put(match);resultBytes+=bytes;}
        unchanged(root,info,current);boolean more=resultBudget||!page.complete;JSONObject result=info.json().put("checkoutId",checkout).put("mode","hex").put("queryHash",queryHash).put("patternEngine","bounded_streaming_kmp_v1").put("patternBytes",pattern.length).put("offsetUnit","bytes").put("searchStartByteOffset",start).put("scannedBytes",page.scannedBytes).put("maxScannedBytesPerPage",ProjectHexPattern.MAX_SCAN_BYTES).put("hashReadIsAdditionalToMatchingScan",true)
                .put("matches",matches).put("hasMore",more).put("complete",!more).put("coverageComplete",!more).put("coverageScope","exact_selected_file_cursor_range").put("scanBudgetExhausted",!page.complete&&page.scannedBytes>=ProjectHexPattern.MAX_SCAN_BYTES).put("resultBudgetExhausted",resultBudget).put("resultBytes",resultBytes).put("maxResultBytes",MAX_CONTEXT_BYTES).put("nextCursor",more?new JSONObject().put("queryHash",queryHash).put("sha256",info.sha).put("path",path).put("byteOffset",next):JSONObject.NULL).put("source","active_checkout_bytes").put("untrusted",true).put("atomicReadFence",false);
        if(result.toString().getBytes(StandardCharsets.UTF_8).length>MAX_CONTEXT_BYTES)throw new IllegalStateException("原hex搜索结果超出12KiB，未返回截断JSON");current.check();return result;
    }
    public static JSONObject search(File root,String checkout,JSONObject input,Current current)throws Exception {
        if("hex".equals(input.optString("mode")))return searchHex(root,checkout,input,current);
        String mode=input.optString("mode","text"),query=input.getString("query"),scope=input.optString("path","");boolean includeHidden=input.optBoolean("hidden"),caseSensitive=input.optBoolean("caseSensitive",true);
        if(!java.util.Set.of("path","text").contains(mode)||query.isEmpty()||query.length()>240)throw new IllegalArgumentException("搜索模式/查询无效");
        String syntax=input.optString("syntax","literal");if(!java.util.Set.of("literal","regex","fuzzy").contains(syntax)||!mode.equals("text")&&!syntax.equals("literal"))throw new IllegalArgumentException("syntax仅支持literal或正文regex/fuzzy；路径仍使用glob");
        int maxEdits=1;
        if(input.has("maxEdits")){Object value=input.opt("maxEdits");if(!syntax.equals("fuzzy")||!(value instanceof Integer||value instanceof Long)||((Number)value).longValue()<1||((Number)value).longValue()>ProjectFuzzyPattern.MAX_EDITS)throw new IllegalArgumentException("maxEdits仅用于正文fuzzy定位，需1至2整数");maxEdits=((Number)value).intValue();}
        ProjectFuzzyPattern fuzzy=syntax.equals("fuzzy")?new ProjectFuzzyPattern(query,caseSensitive,maxEdits):null;
        if(mode.equals("text")&&(query.indexOf('\n')>=0||query.indexOf('\r')>=0||query.indexOf('\0')>=0))throw new IllegalArgumentException("正文搜索按行定位；查询不能包含换行或空字节，不支持跨行正则");
        current.check();ProjectSearchPattern expression=syntax.equals("regex")?new ProjectSearchPattern(query,caseSensitive):null;current.check();
        int contextBefore=contextLines(input,"contextBefore"),contextAfter=contextLines(input,"contextAfter");boolean wantsContext=contextBefore>0||contextAfter>0;
        if(wantsContext&&!mode.equals("text"))throw new IllegalArgumentException("前后文仅适用于正文搜索，不静默忽略路径搜索参数");
        if(!scope.isEmpty())VersionedWorkspace.path(scope);String include=input.optString("include","**");if(include.isEmpty())include="**";if(input.has("exclude")&&!(input.opt("exclude") instanceof String))throw new IllegalArgumentException("exclude需为相对文件路径glob文字");String exclude=input.optString("exclude","");Glob exclusion=exclude.isEmpty()?null:glob(exclude),filter=glob(include),pathQuery=mode.equals("path")?glob(query,caseSensitive):null;int limit=input.optInt("limit",20);if(limit<1||limit>MAX_RESULTS)throw new IllegalArgumentException("搜索页最多40项");
        Map<String,Path> paths=new TreeMap<>();JSONArray skipped=new JSONArray();long[] skippedCount={0},excludedByQuery={0};boolean[] enumerationComplete={true};
        Path base=scope.isEmpty()?root.toPath():NativeCommandExecutor.within(root,scope).toPath();if(!Files.exists(base,LinkOption.NOFOLLOW_LINKS))throw new IllegalArgumentException("搜索范围不存在");
        Files.walkFileTree(base,new java.nio.file.SimpleFileVisitor<Path>(){
            @Override public java.nio.file.FileVisitResult preVisitDirectory(Path path,BasicFileAttributes attrs)throws java.io.IOException {try{current.check();String name=root.toPath().relativize(path).toString();return !ProjectSources.sourcePath(name)||!includeHidden&&!name.isEmpty()&&hidden(name)?java.nio.file.FileVisitResult.SKIP_SUBTREE:java.nio.file.FileVisitResult.CONTINUE;}catch(Exception stop){throw new java.io.IOException(stop);}}
            @Override public java.nio.file.FileVisitResult visitFile(Path path,BasicFileAttributes attrs)throws java.io.IOException {try{current.check();String name=root.toPath().relativize(path).toString();if(!ProjectSources.sourcePath(name)||!includeHidden&&hidden(name))return java.nio.file.FileVisitResult.CONTINUE;
                // Explicit query exclusions happen before any content read/hash; they still
                // spend the original 2000-entry enumeration budget, never create a wider sweep.
                if(exclusion!=null&&exclusion.matches(name,current)){if(paths.size()+excludedByQuery[0]>=ProjectSources.MAX_FILES){enumerationComplete[0]=false;return java.nio.file.FileVisitResult.TERMINATE;}excludedByQuery[0]++;return java.nio.file.FileVisitResult.CONTINUE;}
                if(!attrs.isRegularFile()||attrs.isSymbolicLink()||attrs.size()>ProjectSources.MAX_FILE_BYTES){skip(name,!attrs.isRegularFile()||attrs.isSymbolicLink()?"symlink_or_special":"over_2MiB");return java.nio.file.FileVisitResult.CONTINUE;}
                if(paths.size()+excludedByQuery[0]>=ProjectSources.MAX_FILES){enumerationComplete[0]=false;return java.nio.file.FileVisitResult.TERMINATE;}paths.put(name,path);return java.nio.file.FileVisitResult.CONTINUE;}catch(Exception stop){throw new java.io.IOException(stop);}}
            private void skip(String path,String reason)throws Exception {skippedCount[0]++;if(skipped.length()<10)skipped.put(new JSONObject().put("path",path).put("reason",reason));}
        });
        List<FileInfo> facts=new ArrayList<>();JSONArray manifest=new JSONArray();long total=0;int binary=0;boolean byteBudget=true;
        for(String path:paths.keySet()){current.check();FileInfo info=describe(root,path,current);if(total+info.bytes>ProjectSources.MAX_TOTAL_BYTES){byteBudget=false;break;}total+=info.bytes;facts.add(info);manifest.put(new JSONObject().put("path",path).put("sha256",info.sha).put("bytes",info.bytes));if(info.binary)binary++;}
        String manifestHash=ProjectSources.manifestDigest(manifest);JSONObject searchIdentity=new JSONObject().put("checkoutId",checkout).put("manifestHash",manifestHash).put("mode",mode).put("query",query).put("syntax",syntax).put("path",scope).put("include",include).put("hidden",includeHidden).put("caseSensitive",caseSensitive).put("contextBefore",contextBefore).put("contextAfter",contextAfter);if(!exclude.isEmpty())searchIdentity.put("exclude",exclude);if(fuzzy!=null)searchIdentity.put("maxEdits",maxEdits).put("fuzzyEngine",ProjectFuzzyPattern.ENGINE);String queryHash=ProjectSources.digest(searchIdentity);
        JSONObject cursor=input.optJSONObject("cursor");String afterPath="";int afterLine=0;if(cursor!=null){if(!queryHash.equals(cursor.getString("queryHash"))||!manifestHash.equals(cursor.getString("manifestHash")))throw new SecurityException("搜索cursor所属查询或实际文件版本已变化，请重新搜索");afterPath=VersionedWorkspace.path(cursor.getString("path"));afterLine=cursor.getInt("line");if(afterLine<0)throw new SecurityException("搜索cursor无效");}
        JSONArray matches=new JSONArray();int resultBytes=0;boolean more=false;String lastPath="";int lastLine=0;String needle=query;
        search:for(FileInfo info:facts){if(!filter.matches(info.path,current)||info.path.compareTo(afterPath)<0)continue;
            if(mode.equals("path")){if(!pathQuery.matches(info.path,current)||info.path.equals(afterPath))continue;JSONObject match=info.json();int bytes=match.toString().getBytes(StandardCharsets.UTF_8).length;if(matches.length()==limit||resultBytes+bytes>12000){more=true;break;}matches.put(match);resultBytes+=bytes;lastPath=info.path;lastLine=0;continue;}
            if(info.binary)continue;int line=0;try(BufferedReader source=reader(target(root,info.path))){String value;while((value=boundedLine(source,current))!=null){line++;if(info.path.equals(afterPath)&&line<=afterLine)continue;int[] located=fuzzy!=null?fuzzy.find(value,current):expression==null?null:expression.find(value,current);int column=fuzzy==null&&expression==null?findLiteral(value,needle,caseSensitive,current):located==null?-1:located[0];if(column<0)continue;int endColumn=fuzzy==null&&expression==null?column+query.length():located[1];if(matches.length()==limit){more=true;break search;}int from=Math.max(0,column-100),to=Math.min(value.length(),column+Math.min(240,endColumn-column)+300);if(from>0&&Character.isLowSurrogate(value.charAt(from))&&Character.isHighSurrogate(value.charAt(from-1)))from--;if(to>from&&to<value.length()&&Character.isHighSurrogate(value.charAt(to-1))&&Character.isLowSurrogate(value.charAt(to)))to--;JSONObject match=new JSONObject().put("path",info.path).put("sha256",info.sha).put("bytes",info.bytes).put("line",line).put("column",column+1).put("endColumn",endColumn+1).put("columnUnit","UTF16_code_units").put("text",value.substring(from,to)).put("linePreviewOffset",from).put("linePreviewTruncated",from>0||to<value.length());if(fuzzy!=null)match.put("editDistance",located[2]).put("suggestionOnly",true);int bytes=match.toString().getBytes(StandardCharsets.UTF_8).length;if(resultBytes+bytes>12000){more=true;break search;}matches.put(match);resultBytes+=bytes;lastPath=info.path;lastLine=line;}}
            unchanged(root,info,current);
        }
        JSONObject contexts=wantsContext?searchContexts(root,facts,matches,contextBefore,contextAfter,current):null;
        // Recheck every participating file, including additional context and a page stopped mid-file.
        for(FileInfo info:facts)unchanged(root,info,current);
        boolean coverage=enumerationComplete[0]&&byteBudget&&skippedCount[0]==0;
        JSONObject result=new JSONObject().put("checkoutId",checkout).put("queryHash",queryHash).put("manifestHash",manifestHash).put("mode",mode).put("matches",matches).put("nextCursor",more?new JSONObject().put("queryHash",queryHash).put("manifestHash",manifestHash).put("path",lastPath).put("line",lastLine):JSONObject.NULL).put("hasMore",more).put("coverageComplete",coverage).put("enumerationComplete",enumerationComplete[0]).put("byteBudgetComplete",byteBudget).put("coverageScope","eligible_source_paths_or_UTF8_text_only").put("resultBytes",resultBytes).put("scannedFiles",facts.size()).put("scannedBytes",total).put("binaryFilesSkippedForText",mode.equals("text")?binary:0).put("skippedCount",skippedCount[0]).put("skipped",skipped).put("scope",scope).put("include",include).put("hidden",includeHidden).put("excluded",new JSONArray(new String[]{"dependency_caches","build_outputs","symlinks","special_files"})).put("pathGlobEngine","bounded_dynamic_programming").put("pathGlobUnicodeUnit","code_point").put("searchSyntax",mode.equals("path")?"glob_star_double_star_question":"literal").put("untrusted",true).put("atomicReadFence",false);
        if(!exclude.isEmpty())result.put("exclude",exclude).put("excludeScope","case_sensitive_relative_file_paths_before_content_read").put("excludedByQuery",excludedByQuery[0]).put("queryExclusionsUseOriginalEnumerationBudget",true);
        if(mode.equals("text"))result.put("matchSemantics","first_match_per_line");
        if(expression!=null)result.put("searchSyntax","regex_re2j_line").put("regexEngine","RE2J_1.8").put("regexUnsupported",new JSONArray(new String[]{"backreferences","lookaround","cross_line_matches"})).put("regexMatchTimeBudgetMillis",3000).put("regexCharacterReadBudget",64L*1024*1024).put("regexConservativeCompileExpansionBudget",ProjectSearchPattern.MAX_COMPILE_EXPANSION);
        if(fuzzy!=null)result.put("searchSyntax","fuzzy_levenshtein_substring").put("fuzzyEngine",ProjectFuzzyPattern.ENGINE).put("maxEdits",maxEdits).put("distanceUnit","unicode_code_points").put("normalization","none").put("fuzzyCellBudget",ProjectFuzzyPattern.MAX_CELLS).put("fuzzyTimeBudgetMillis",ProjectFuzzyPattern.MAX_MATCH_MILLIS).put("matchSemantics","lowest_distance_then_first_start_then_longest_span_per_line").put("suggestionOnly",true).put("writeAuthorityAdded",false).put("nextAction","选择候选后按真实path/line/SHA读取原文；写入仍需原expectedHash/sourceDigest/精确patch和审批，候选不自动应用");
        if(contexts!=null)for(java.util.Iterator<String> keys=contexts.keys();keys.hasNext();){String key=keys.next();result.put(key,contexts.get(key));}return result;
    }
    private static int contextLines(JSONObject input,String key){
        if(!input.has(key))return 0;Object value=input.opt(key);
        if(!(value instanceof Integer||value instanceof Long)||((Number)value).longValue()<0||((Number)value).longValue()>MAX_CONTEXT_LINES)throw new IllegalArgumentException(key+"需0至20整数行");return ((Number)value).intValue();
    }
    private static final class ContextWindow {
        final FileInfo file;final long start;long end;final JSONArray matchedLines=new JSONArray();final Map<Integer,Integer> columns=new TreeMap<>();
        ContextWindow(FileInfo file,int line,int column,int before,int after){this.file=file;start=Math.max(1,line-before);end=Math.min(file.lines,(long)line+after);add(line,column,after);}
        void add(int line,int column,int after){end=Math.min(file.lines,Math.max(end,(long)line+after));matchedLines.put(line);columns.put(line,column);}
    }
    /** Context is a projection of this page's real matches, not another search/model/execution path. */
    private static JSONObject searchContexts(File root,List<FileInfo> facts,JSONArray matches,int before,int after,Current current)throws Exception {
        Map<String,FileInfo> files=new TreeMap<>();for(FileInfo file:facts)files.put(file.path,file);List<ContextWindow> windows=new ArrayList<>();long requestedLines=0;
        for(int i=0;i<matches.length();i++){
            JSONObject match=matches.getJSONObject(i);FileInfo file=files.get(match.getString("path"));int line=match.getInt("line"),column=match.getInt("column")-1;ContextWindow last=windows.isEmpty()?null:windows.get(windows.size()-1);
            if(last!=null&&last.file==file&&(long)line-before<=last.end+1)last.add(line,column,after);else windows.add(new ContextWindow(file,line,column,before,after));
        }
        for(ContextWindow window:windows)requestedLines+=window.end-window.start+1;
        JSONArray blocks=new JSONArray();int returnedLines=0;boolean complete=true,budgetExhausted=false;
        for(ContextWindow window:windows){
            current.check();JSONArray lines=new JSONArray();JSONObject block=new JSONObject().put("path",window.file.path).put("sha256",window.file.sha).put("startLine",window.start).put("endLine",window.end).put("matchedLines",window.matchedLines).put("matchesScope","this_result_page").put("lines",lines).put("complete",true);boolean blockComplete=true;
            // Reserve final completeness metadata; the exact serialized array never exceeds 12KiB.
            int priorBytes=blocks.toString().getBytes(StandardCharsets.UTF_8).length;
            if(priorBytes+block.toString().getBytes(StandardCharsets.UTF_8).length+256>MAX_CONTEXT_BYTES){complete=false;budgetExhausted=true;break;}
            try(BufferedReader source=reader(target(root,window.file.path))){
                long number=0;String value;while(number<window.end&&(value=boundedLine(source,current))!=null){number++;if(number<window.start)continue;current.check();
                    int center=window.columns.getOrDefault((int)number,0),from=Math.max(0,center-100),to=Math.min(value.length(),from+CONTEXT_LINE_CHARS);
                    if(from>0&&from<value.length()&&Character.isLowSurrogate(value.charAt(from))&&Character.isHighSurrogate(value.charAt(from-1)))from--;
                    if(to>from&&to<value.length()&&Character.isHighSurrogate(value.charAt(to-1))&&Character.isLowSurrogate(value.charAt(to)))to--;
                    boolean clipped=from>0||to<value.length();JSONObject row=new JSONObject().put("line",number).put("text",value.substring(from,to)).put("previewOffset",from).put("truncated",clipped);lines.put(row);
                    if(priorBytes+block.toString().getBytes(StandardCharsets.UTF_8).length+256>MAX_CONTEXT_BYTES){lines.remove(lines.length()-1);blockComplete=false;budgetExhausted=true;break;}
                    returnedLines++;if(clipped)blockComplete=false;
                }
            }
            if(lines.length()!=window.end-window.start+1)blockComplete=false;
            block.put("complete",blockComplete).put("returnedEndLine",lines.length()==0?JSONObject.NULL:lines.getJSONObject(lines.length()-1).getLong("line"));if(budgetExhausted)block.put("reason","result_budget");
            blocks.put(block);complete&=blockComplete;if(budgetExhausted)break;
        }
        if(blocks.length()!=windows.size())complete=false;
        return new JSONObject().put("contextBefore",before).put("contextAfter",after).put("contextBlocks",blocks).put("contextComplete",complete).put("contextTruncated",!complete).put("contextBudgetExhausted",budgetExhausted).put("contextWindowsRequested",windows.size()).put("contextWindowsReturned",blocks.length()).put("contextLinesRequested",requestedLines).put("contextLinesReturned",returnedLines).put("contextResultBytes",blocks.toString().getBytes(StandardCharsets.UTF_8).length).put("contextCoverage","selected_result_page_windows_only");
    }
    private static int findLiteral(String text,String query,boolean sensitive,Current current)throws Exception {
        if(sensitive)return text.indexOf(query);for(int i=0;i<=text.length()-query.length();i++){if((i&4095)==0)current.check();if(text.regionMatches(true,i,query,0,query.length()))return i;}return -1;
    }
    // A line is bounded by the existing 2MiB per-file ceiling, never the entire project.
    private static String boundedLine(BufferedReader input,Current current)throws Exception {StringBuilder out=new StringBuilder();int c;while((c=input.read())!=-1){if((out.length()&4095)==0)current.check();if(c=='\n')return out.toString();out.append((char)c);if(out.length()>ProjectSources.MAX_FILE_BYTES)throw new IllegalStateException("单行超过本次读取范围");}return out.length()==0?null:out.toString();}
}
