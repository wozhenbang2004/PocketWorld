package dev.pocketengine.features.local.project;

import dev.pocketengine.features.local.NativeCommandExecutor;
import dev.pocketengine.features.local.preview.ProjectPreviewBundle;
import dev.pocketengine.features.workspace.VersionedWorkspace;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.json.JSONArray;
import org.json.JSONObject;

/** Bounded portable bytes, not an execution authority or a second artifact repository. */
public final class ProjectPackageArchive {
    public static final String FORMAT = "pocketworld.project-package.v1";
    public static final int MAX_FILES = 2000, MAX_FILE_BYTES = 2 * 1024 * 1024,
        MAX_TOTAL_BYTES = 16 * 1024 * 1024, MAX_ARCHIVE_BYTES = 20 * 1024 * 1024, MAX_MANIFEST_BYTES = 1024 * 1024;
    private static final Set<String> PRIVATE_DIRECTORIES = Set.of(".git", "node_modules", ".venv", "venv", "__pycache__", ".gradle", ".pocketworld", ".pytest_cache", ".ssh", ".aws", ".azure", ".gnupg", ".config", ".npm", ".cache");
    private static final Set<String> GENERATED_ROOTS = Set.of("build", "dist", "out", "target", "coverage");
    private ProjectPackageArchive() {}

    public static final class Frozen {
        private final JSONObject manifest;
        private final Map<String, byte[]> files;
        public final String identity;
        private Frozen(JSONObject manifest, Map<String, byte[]> files) throws Exception {
            this.manifest = ProjectSources.copy(manifest);
            Map<String, byte[]> retained = new TreeMap<>(); for (var file : files.entrySet()) retained.put(file.getKey(), file.getValue().clone());
            this.files = Collections.unmodifiableMap(retained); identity = ProjectSources.digest(this.manifest);
        }
        public JSONObject manifest() throws Exception { return ProjectSources.copy(manifest); }
        public byte[] file(String path) { byte[] value = files.get(path); if (value == null) throw new IllegalArgumentException("固定项目包没有这个文件"); return value.clone(); }
        public Map<String, byte[]> files() { Map<String, byte[]> copy = new LinkedHashMap<>(); for (var file : files.entrySet()) copy.put(file.getKey(), file.getValue().clone()); return copy; }
    }

    /** Native LocalModule supplies the actual active root and observed source identity. No JSON host path. */
    public static Frozen capture(File activeRoot, JSONObject original, String mode, String entryPath, boolean includeGeneratedRoots) throws Exception {
        if (!Set.of("source_project", "web_output").contains(mode)) throw new IllegalArgumentException("请选择源项目包或固定网页成果");
        for (String key : new String[]{"projectId", "checkoutId"}) ProjectBuildPlan.identity(original.getString(key));
        if (!original.getString("sourceDigest").matches("[a-f0-9]{64}")) throw new IllegalArgumentException("缺少实际源码摘要");
        File root = activeRoot.getAbsoluteFile();
        if (Files.isSymbolicLink(root.toPath()) || !root.isDirectory() || !root.equals(root.getCanonicalFile())) throw new SecurityException("项目根目录不可用或含链接");
        boolean web = mode.equals("web_output");
        String selectedEntry = entryPath == null ? "" : entryPath;
        if (!selectedEntry.isEmpty()) packagePath(selectedEntry);
        if (web && (!ProjectPreviewBundle.mime(packagePath(selectedEntry)).equals("text/html"))) throw new IllegalArgumentException("请选择项目内实际HTML入口");
        int slash = selectedEntry.lastIndexOf('/');
        String outputRoot = web && slash >= 0 ? selectedEntry.substring(0, slash) : "";
        File scanRoot = outputRoot.isEmpty() ? root : NativeCommandExecutor.within(root, outputRoot);
        if (!scanRoot.isDirectory()) throw new IllegalArgumentException("所选网页输出目录不存在");
        Map<String, byte[]> files = new TreeMap<>(); Map<String, Boolean> executable = new TreeMap<>(); JSONArray omitted = new JSONArray();
        java.util.TreeSet<String> directories=new java.util.TreeSet<>();
        long[] total = {0};
        int[] visited = {0};
        Files.walkFileTree(scanRoot.toPath(), new java.nio.file.SimpleFileVisitor<Path>() {
            private String relative(Path path) { return root.toPath().relativize(path).toString().replace(File.separatorChar, '/'); }
            @Override public java.nio.file.FileVisitResult preVisitDirectory(Path path, BasicFileAttributes attrs) throws java.io.IOException {
                String relative = relative(path);
                if(Thread.currentThread().isInterrupted()||++visited[0]>10000)throw new java.io.IOException("项目包采集取消或目录条目超过10000，未生成部分成果");
                if (!relative.isEmpty()) {
                    try {
                        packagePath(relative);
                        if(!root.toPath().resolve(relative).equals(path))throw new SecurityException("目录名不是可往返UTF-8路径，未另名保存");
                        String reason = excluded(relative, true, web || includeGeneratedRoots);
                        if (reason != null) { omit(omitted, relative, reason); return java.nio.file.FileVisitResult.SKIP_SUBTREE; }
                        if (relative.split("/").length > 40) throw new IllegalArgumentException("项目目录超过40层，未保存不完整成果");
                        NativeCommandExecutor.within(root, relative);
                        if(directories.size()>=MAX_FILES)throw new IllegalArgumentException("项目包目录超过2000，未丢弃空目录");directories.add(relative);
                    } catch (Exception invalid) { throw new java.io.IOException(invalid.getMessage(), invalid); }
                }
                return java.nio.file.FileVisitResult.CONTINUE;
            }
            @Override public java.nio.file.FileVisitResult visitFile(Path path, BasicFileAttributes attrs) throws java.io.IOException {
                String relative = relative(path);
                if(Thread.currentThread().isInterrupted()||++visited[0]>10000)throw new java.io.IOException("项目包采集取消或目录条目超过10000，未生成部分成果");
                try {
                    packagePath(relative);
                    if(!root.toPath().resolve(relative).equals(path))throw new SecurityException("文件名不是可往返UTF-8路径，未另名保存");
                    String reason = excluded(relative, false, web || includeGeneratedRoots);
                    if (reason != null) { omit(omitted, relative, reason); return java.nio.file.FileVisitResult.CONTINUE; }
                    if (attrs.isSymbolicLink() || !attrs.isRegularFile()) throw new SecurityException("项目包不跟随链接或特殊文件：" + relative);
                    if (web && ProjectPreviewBundle.mime(relative).isEmpty()) { omit(omitted, relative, "unsupported_web_resource"); return java.nio.file.FileVisitResult.CONTINUE; }
                    if (attrs.size() > MAX_FILE_BYTES || files.size() >= MAX_FILES) throw new IllegalArgumentException("项目包超过2000文件/单文件2MiB；未保存部分成果");
                    NativeCommandExecutor.within(root, relative);
                    byte[] bytes;
                    try (InputStream input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) { bytes = bounded(input, MAX_FILE_BYTES); }
                    BasicFileAttributes after = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    if (after.isSymbolicLink() || !after.isRegularFile() || after.size() != bytes.length || !java.util.Objects.equals(attrs.fileKey(), after.fileKey()) || !attrs.lastModifiedTime().equals(after.lastModifiedTime())) throw new SecurityException("采集期间文件变化：" + relative);
                    total[0] += bytes.length; if (total[0] > MAX_TOTAL_BYTES) throw new IllegalArgumentException("项目包展开超过16MiB；未保存部分成果");
                    if(files.put(relative, bytes)!=null)throw new SecurityException("实际文件映射为重复路径，未覆盖清单");executable.put(relative, Files.isExecutable(path));
                    return java.nio.file.FileVisitResult.CONTINUE;
                } catch (Exception invalid) { throw new java.io.IOException(invalid.getMessage(), invalid); }
            }
        });
        if (files.isEmpty() || !selectedEntry.isEmpty() && !files.containsKey(selectedEntry)) throw new IllegalArgumentException("所选范围没有可保存文件或指定入口被排除");
        JSONObject scope = new JSONObject().put("format", FORMAT).put("mode", mode)
            .put("project", new JSONObject().put("id", original.getString("projectId")).put("name", original.optString("name", "项目")))
            .put("source", new JSONObject().put("projectId", original.getString("projectId")).put("checkoutId", original.getString("checkoutId")).put("sourceDigest", original.getString("sourceDigest")))
            .put("entryPath", selectedEntry).put("previewRoot", outputRoot).put("directories",new JSONArray(directories)).put("files", manifest(files, executable)).put("bytes", total[0])
            .put("omitted", orderedOmissions(omitted)).put("includesHistory", false).put("includesToolEnvironment", false).put("atomicSourceFence", false)
            .put("policy", new JSONObject().put("version", 1).put("generatedRootsIncluded", web || includeGeneratedRoots).put("privateFilesExcluded", true));
        if (scope.toString().getBytes(StandardCharsets.UTF_8).length > MAX_MANIFEST_BYTES) throw new IllegalArgumentException("项目包清单超过1MiB，未生成不能重导入的包");
        validateHeader(scope);return new Frozen(scope, files);
    }

    /** Native copied source selection, still unpublished. The existing codec remains the only writer. */
    public static Frozen captureSourceImport(File root,JSONObject original,boolean generated,JSONArray sourceOmissions)throws Exception {
        Frozen captured=capture(root,original,"source_project","",generated);JSONObject manifest=captured.manifest();JSONArray omissions=manifest.getJSONArray("omitted");
        for(int i=0;i<sourceOmissions.length();i++){JSONObject row=sourceOmissions.getJSONObject(i);keys(row,Set.of("path","reason"));packagePath(row.getString("path"));if(row.getString("reason").length()>120)throw new SecurityException("来源排除说明超限");omissions.put(ProjectSources.copy(row));}
        manifest.put("omitted",orderedOmissions(omissions));validateHeader(manifest);if(manifest.toString().getBytes(StandardCharsets.UTF_8).length>MAX_MANIFEST_BYTES)throw new IllegalArgumentException("来源项目包清单超过1MiB");return new Frozen(manifest,captured.files);
    }
    /** Ordinary ZIP is not a PocketWorld package: all declared entries are bounded before ZipFile allocation. */
    public static void preflightSourceZip(File archive)throws Exception {preflightDirectory(archive,ProjectSourceImport.MAX_ENTRIES,MAX_TOTAL_BYTES);}

    /** The same policy is applied to imported manifests. An archive never restores accounts or grants. */
    public static String excluded(String path, boolean directory, boolean includeGeneratedRoots) {
        String[] parts = path.split("/");
        for (String part : parts) if (PRIVATE_DIRECTORIES.contains(part)) return "dependency_environment_or_private_directory";
        if (!includeGeneratedRoots && GENERATED_ROOTS.contains(parts[0])) return "generated_root_not_selected";
        String name = parts[parts.length - 1].toLowerCase(java.util.Locale.ROOT);
        if (!directory && (name.equals(".env") || name.startsWith(".env.") || Set.of(".npmrc", ".pypirc", ".netrc", ".git-credentials", "credentials.json", "accounts.json", "secrets.json", "token.json", "tokens.json").contains(name)
            || name.matches("id_(rsa|dsa|ecdsa|ed25519)(\\.pub)?") || name.matches(".*\\.(pem|key|p12|pfx|jks|keystore|ppk|gpg)") || name.startsWith("service-account"))) return "credential_or_private_configuration";
        return null;
    }

    public static void write(Frozen frozen, OutputStream destination) throws Exception {
        try (ZipOutputStream zip = new ZipOutputStream(new LimitedOutput(destination, MAX_ARCHIVE_BYTES), StandardCharsets.UTF_8)) {
            put(zip, "manifest.json", frozen.manifest.toString().getBytes(StandardCharsets.UTF_8));
            for (var entry : frozen.files.entrySet()) put(zip, "project/" + entry.getKey(), entry.getValue());
            zip.finish();
        }
    }
    /** Caller owns this private staged file; compressed bytes and all expanded content are bounded. */
    public static Frozen read(File archive) throws Exception {
        if (Files.isSymbolicLink(archive.toPath()) || !archive.isFile() || archive.length() > MAX_ARCHIVE_BYTES) throw new IllegalArgumentException("项目包不是有界普通ZIP文件");
        preflightDirectory(archive); // Count/metadata limits apply before a library allocates entry objects.
        try (org.apache.commons.compress.archivers.zip.ZipFile zip = new org.apache.commons.compress.archivers.zip.ZipFile(archive, StandardCharsets.UTF_8.name())) {
            Map<String, ZipArchiveEntry> entries = new TreeMap<>();
            var iterator = zip.getEntries();
            while (iterator.hasMoreElements()) {
                ZipArchiveEntry entry = iterator.nextElement(); String name = entry.getName();
                if (entry.isDirectory() || entry.isUnixSymlink() || entry.getGeneralPurposeBit().usesEncryption() || !zip.canReadEntryData(entry) || entries.size() > MAX_FILES) throw new SecurityException("项目包含目录/链接/加密/不支持项目或条目超限");
                int mode = entry.getUnixMode(); if ((mode & 07000) != 0 || (mode & 0170000) != 0 && (mode & 0170000) != 0100000) throw new SecurityException("项目包含特殊Unix文件类型或权限");
                if (!name.equals("manifest.json")) {
                    if (!name.startsWith("project/")) throw new SecurityException("项目包包含未声明命名空间");
                    packagePath(name.substring(8));
                }
                if (entries.put(name, entry) != null) throw new SecurityException("项目包含重复路径");
            }
            ZipArchiveEntry manifestEntry = entries.remove("manifest.json"); if (manifestEntry == null) throw new IllegalArgumentException("不是PocketWorld可重导入项目包：缺少manifest");
            JSONObject manifest;
            try (InputStream input = zip.getInputStream(manifestEntry)) { byte[] bytes=bounded(input, MAX_MANIFEST_BYTES);java.util.zip.CRC32 crc=new java.util.zip.CRC32();crc.update(bytes);if(manifestEntry.getCrc()<0||manifestEntry.getCrc()!=crc.getValue())throw new SecurityException("项目包清单ZIP校验不一致");manifest = new JSONObject(strictText(bytes)); }
            validateHeader(manifest);
            JSONArray declared = manifest.getJSONArray("files"); if (declared.length() < 1 || declared.length() > MAX_FILES || entries.size() != declared.length()) throw new SecurityException("项目包清单与真实条目数不同");
            Map<String, byte[]> files = new TreeMap<>(); long total = 0;
            for (int i = 0; i < declared.length(); i++) {
                JSONObject file = declared.getJSONObject(i); String path = packagePath(file.getString("path"));
                keys(file,Set.of("path","bytes","sha256","kind","mimeType","executable"));
                if (excluded(path, false, manifest.getJSONObject("policy").getBoolean("generatedRootsIncluded")) != null) throw new SecurityException("项目包含凭据、工具环境、未选输出或私有配置，不导入：" + path);
                ZipArchiveEntry entry = entries.remove("project/" + path);
                long declaredBytes=integer(file,"bytes");
                if (entry == null || declaredBytes < 0 || declaredBytes > MAX_FILE_BYTES) throw new SecurityException("项目包清单路径或大小无效");
                byte[] bytes; try (InputStream input = zip.getInputStream(entry)) { bytes = bounded(input, MAX_FILE_BYTES); }
                java.util.zip.CRC32 crc=new java.util.zip.CRC32();crc.update(bytes);if(entry.getCrc()<0||entry.getCrc()!=crc.getValue())throw new SecurityException("项目包ZIP校验不一致");
                total += bytes.length;
                if (total > MAX_TOTAL_BYTES || file.getLong("bytes") != bytes.length || !ProjectSources.sha256(bytes).equals(file.getString("sha256")) || files.put(path, bytes) != null) throw new SecurityException("项目包文件字节/摘要/重复路径校验失败");
                if (!(file.opt("executable") instanceof Boolean)) throw new SecurityException("项目包执行权限声明无效");
                String kind = file.getString("kind"); if (!Set.of("text", "binary").contains(kind)) throw new SecurityException("项目包文件类型无效");
                String actualKind="text";try{strictText(bytes);}catch(Exception binary){actualKind="binary";}
                String mime=ProjectPreviewBundle.mime(path);if(mime.isEmpty())mime=actualKind.equals("text")?"text/plain":"application/octet-stream";
                if(!kind.equals(actualKind)||!mime.equals(file.getString("mimeType")))throw new SecurityException("项目包类型与真实字节/路径不一致");
            }
            if (!entries.isEmpty() || total != integer(manifest,"bytes")) throw new SecurityException("项目包还有未声明字节或大小不一致");
            for (String path : files.keySet()) for (int slash = path.indexOf('/'); slash >= 0; slash = path.indexOf('/', slash + 1)) if (files.containsKey(path.substring(0, slash))) throw new SecurityException("项目包文件与目录冲突");
            JSONArray dirs=manifest.getJSONArray("directories");for(int i=0;i<dirs.length();i++){String path=dirs.getString(i);if(files.containsKey(path))throw new SecurityException("项目包文件与空目录冲突");for(int slash=path.indexOf('/');slash>=0;slash=path.indexOf('/',slash+1))if(files.containsKey(path.substring(0,slash)))throw new SecurityException("项目包目录祖先是文件");}
            String entry = manifest.optString("entryPath"); if (!entry.isEmpty() && !files.containsKey(packagePath(entry))) throw new SecurityException("项目包入口不在真实文件清单中");
            return new Frozen(manifest, files);
        }
    }

    /** This bounded v1 never needs ZIP64/multi-disk. Validate the directory before constructing ZipFile. */
    private static void preflightDirectory(File archive)throws Exception {preflightDirectory(archive,MAX_FILES+1,MAX_TOTAL_BYTES+MAX_MANIFEST_BYTES);}
    private static void preflightDirectory(File archive,int entryLimit,long expandedLimit)throws Exception {
        try(var channel=Files.newByteChannel(archive.toPath(),java.nio.file.StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)){
            long size=channel.size();if(size<22||size>MAX_ARCHIVE_BYTES)throw new SecurityException("项目包ZIP大小无效");
            int tailSize=(int)Math.min(size,65557);java.nio.ByteBuffer tail=java.nio.ByteBuffer.allocate(tailSize).order(java.nio.ByteOrder.LITTLE_ENDIAN);channel.position(size-tailSize);readFully(channel,tail);int end=-1;
            for(int i=tailSize-22;i>=0;i--)if(tail.getInt(i)==0x06054b50&&i+22+(tail.getShort(i+20)&65535)==tailSize){end=i;break;}
            if(end<0||end!=tailSize-22||(tail.getShort(end+20)&65535)!=0)throw new SecurityException("项目包缺少完整ZIP目录尾，或含不支持的注释/尾部数据");
            int disk=tail.getShort(end+4)&65535,centralDisk=tail.getShort(end+6)&65535,onDisk=tail.getShort(end+8)&65535,count=tail.getShort(end+10)&65535;
            long directoryBytes=Integer.toUnsignedLong(tail.getInt(end+12)),offset=Integer.toUnsignedLong(tail.getInt(end+16));
            if(disk!=0||centralDisk!=0||onDisk!=count||count<1||count>entryLimit||directoryBytes>2*1024*1024||directoryBytes<46L*count||offset+directoryBytes!=size-tailSize+end)throw new SecurityException("项目包ZIP目录超限、多盘或ZIP64，不分配未受限条目");
            java.nio.ByteBuffer central=java.nio.ByteBuffer.allocate((int)directoryBytes).order(java.nio.ByteOrder.LITTLE_ENDIAN);channel.position(offset);readFully(channel,central);int at=0;long expanded=0;java.util.HashSet<Long> localOffsets=new java.util.HashSet<>();
            for(int i=0;i<count;i++){
                if(at+46>central.limit()||central.getInt(at)!=0x02014b50)throw new SecurityException("项目包ZIP目录条目不完整");
                int name=central.getShort(at+28)&65535,extra=central.getShort(at+30)&65535,comment=central.getShort(at+32)&65535,method=central.getShort(at+10)&65535;
                if(name<1||name>1024||extra>4096||comment>4096||(central.getShort(at+34)&65535)!=0||method!=0&&method!=8)throw new SecurityException("项目包ZIP目录名/附加信息/压缩方式不支持");
                long localOffset=Integer.toUnsignedLong(central.getInt(at+42)),compressed=Integer.toUnsignedLong(central.getInt(at+20)),uncompressed=Integer.toUnsignedLong(central.getInt(at+24));expanded+=uncompressed;
                if(comment!=0||!localOffsets.add(localOffset)||localOffset+30>offset||uncompressed>MAX_FILE_BYTES||expanded>expandedLimit)throw new SecurityException("项目包ZIP头重复、展开声明或注释超限");
                java.nio.ByteBuffer local=java.nio.ByteBuffer.allocate(30).order(java.nio.ByteOrder.LITTLE_ENDIAN);channel.position(localOffset);readFully(channel,local);
                int localName=local.getShort(26)&65535,localExtra=local.getShort(28)&65535;
                if(local.getInt(0)!=0x04034b50||(local.getShort(8)&65535)!=method||(local.getShort(6)&1)!=0||localName!=name||localExtra>4096||localOffset+30+localName+localExtra+compressed>offset)throw new SecurityException("项目包ZIP本地头/附加信息与有界目录不一致");
                if(at+46+name+extra+comment>central.limit())throw new SecurityException("项目包ZIP目录长度越界");
                java.nio.ByteBuffer localPath=java.nio.ByteBuffer.allocate(name);readFully(channel,localPath);for(int n=0;n<name;n++)if(localPath.get(n)!=central.get(at+46+n))throw new SecurityException("项目包ZIP本地路径与目录不一致");
                at+=46+name+extra+comment;
            }
            if(at!=central.limit())throw new SecurityException("项目包ZIP条目数与真实目录不同");
        }
    }
    private static void readFully(java.nio.channels.SeekableByteChannel channel,java.nio.ByteBuffer buffer)throws Exception {
        while(buffer.hasRemaining()){if(Thread.currentThread().isInterrupted())throw new java.io.InterruptedIOException("项目包读取已取消");if(channel.read(buffer)<0)throw new java.io.EOFException("项目包目录读取不完整");}buffer.flip();
    }

    private static void validateHeader(JSONObject manifest) throws Exception {
        keys(manifest,Set.of("format","mode","project","source","entryPath","previewRoot","directories","files","bytes","omitted","includesHistory","includesToolEnvironment","atomicSourceFence","policy"));
        if (!FORMAT.equals(manifest.optString("format")) || !Set.of("source_project", "web_output").contains(manifest.optString("mode"))) throw new IllegalArgumentException("项目包格式或范围不支持");
        JSONObject source = manifest.getJSONObject("source"), project = manifest.getJSONObject("project");
        keys(source,Set.of("projectId","checkoutId","sourceDigest"));keys(project,Set.of("id","name"));
        for (String key : new String[]{"projectId", "checkoutId"}) ProjectBuildPlan.identity(source.getString(key));
        if (!project.getString("id").equals(source.getString("projectId")) || project.getString("name").length() > 80 || !source.getString("sourceDigest").matches("[a-f0-9]{64}")) throw new SecurityException("项目包来源描述无效");
        if(project.getString("name").isBlank())throw new SecurityException("项目包名称不可为空");StandardCharsets.UTF_8.newEncoder().encode(java.nio.CharBuffer.wrap(project.getString("name")));
        // These are descriptive provenance, never adopted as an original local task/run grant.
        if (manifest.has("taskId") || manifest.has("runId") || manifest.has("approval") || manifest.has("grants")) throw new SecurityException("项目包不得携带执行授权");
        if (!Boolean.FALSE.equals(manifest.opt("includesHistory")) || !Boolean.FALSE.equals(manifest.opt("includesToolEnvironment")) || !Boolean.FALSE.equals(manifest.opt("atomicSourceFence"))) throw new SecurityException("项目包范围声明不支持");
        JSONObject policy=manifest.getJSONObject("policy");keys(policy,Set.of("version","generatedRootsIncluded","privateFilesExcluded"));if(integer(policy,"version")!=1||!(policy.opt("generatedRootsIncluded") instanceof Boolean)||!Boolean.TRUE.equals(policy.opt("privateFilesExcluded")))throw new SecurityException("项目包包含策略不支持");
        JSONArray directories=manifest.getJSONArray("directories");if(directories.length()>MAX_FILES)throw new SecurityException("项目包目录超限");java.util.HashSet<String> seenDirectories=new java.util.HashSet<>();for(int i=0;i<directories.length();i++){String directory=packagePath(directories.getString(i));if(!seenDirectories.add(directory)||directory.split("/").length>40||excluded(directory,true,policy.getBoolean("generatedRootsIncluded"))!=null)throw new SecurityException("项目包目录重复、越界或含私有环境");}
        JSONArray omissions=manifest.getJSONArray("omitted");if(omissions.length()>MAX_FILES)throw new SecurityException("项目包遗漏清单超限");for(int i=0;i<omissions.length();i++){JSONObject row=omissions.getJSONObject(i);keys(row,Set.of("path","reason"));packagePath(row.getString("path"));if(row.getString("reason").length()>120)throw new SecurityException("项目包遗漏原因无效");}
        String entry = manifest.getString("entryPath"), root = manifest.getString("previewRoot");
        if (!entry.isEmpty()) packagePath(entry); if (!root.isEmpty()) packagePath(root);
        if(manifest.getString("mode").equals("source_project")&&!root.isEmpty())throw new SecurityException("源码项目包不得声明替代资源根");
        if (manifest.getString("mode").equals("web_output") && (entry.isEmpty() || !ProjectPreviewBundle.mime(entry).equals("text/html") || !root.equals(entry.contains("/") ? entry.substring(0, entry.lastIndexOf('/')) : ""))) throw new SecurityException("网页成果入口与资源根不一致");
    }
    private static String packagePath(String value)throws Exception {String path=VersionedWorkspace.path(value);StandardCharsets.UTF_8.newEncoder().encode(java.nio.CharBuffer.wrap(path));return path;}
    private static long integer(JSONObject value,String key)throws Exception {Object raw=value.get(key);if(!(raw instanceof Integer||raw instanceof Long))throw new SecurityException("项目包字段需为整数："+key);return ((Number)raw).longValue();}
    private static void keys(JSONObject value,Set<String> allowed)throws Exception {var keys=value.keys();while(keys.hasNext())if(!allowed.contains(keys.next()))throw new SecurityException("项目包含不支持字段，未恢复任何执行授权");}
    private static JSONArray manifest(Map<String, byte[]> files, Map<String, Boolean> executable) throws Exception {
        JSONArray result = new JSONArray();
        for (var file : files.entrySet()) {
            String kind = "text"; try { strictText(file.getValue()); } catch (Exception binary) { kind = "binary"; }
            String mime = ProjectPreviewBundle.mime(file.getKey()); if (mime.isEmpty()) mime = kind.equals("text") ? "text/plain" : "application/octet-stream";
            result.put(new JSONObject().put("path", file.getKey()).put("bytes", file.getValue().length).put("sha256", ProjectSources.sha256(file.getValue())).put("kind", kind).put("mimeType", mime).put("executable", Boolean.TRUE.equals(executable.get(file.getKey()))));
        }
        return result;
    }
    private static void omit(JSONArray omitted, String path, String reason) throws Exception {
        if (omitted.length() >= MAX_FILES) throw new IllegalArgumentException("排除清单超过2000项，未生成不完整项目包");
        omitted.put(new JSONObject().put("path", path).put("reason", reason));
    }
    private static JSONArray orderedOmissions(JSONArray omitted) throws Exception {
        Map<String,JSONObject> ordered=new TreeMap<>();for(int i=0;i<omitted.length();i++){JSONObject row=omitted.getJSONObject(i);ordered.put(row.getString("path"),row);}JSONArray result=new JSONArray();for(JSONObject row:ordered.values())result.put(row);return result;
    }
    private static String strictText(byte[] bytes) throws Exception {
        String text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString(); if (text.indexOf('\0') >= 0) throw new IllegalArgumentException("不是无NUL UTF-8文本"); return text;
    }
    private static byte[] bounded(InputStream input, int limit) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream(); byte[] buffer = new byte[8192];
        for (;;) { if(Thread.currentThread().isInterrupted())throw new java.io.InterruptedIOException("项目包读取已取消");int count = input.read(buffer, 0, Math.min(buffer.length, limit + 1 - output.size())); if (count < 0) break; output.write(buffer, 0, count); if (output.size() > limit) throw new IllegalArgumentException("项目包内容超过声明界限"); }
        return output.toByteArray();
    }
    private static void put(ZipOutputStream zip, String name, byte[] bytes) throws Exception { ZipEntry entry = new ZipEntry(name); entry.setTime(0); zip.putNextEntry(entry); zip.write(bytes); zip.closeEntry(); }
    private static final class LimitedOutput extends java.io.FilterOutputStream {
        private long count; private final long limit;
        LimitedOutput(OutputStream output, long limit) { super(output); this.limit = limit; }
        @Override public void write(int value) throws java.io.IOException { if (++count > limit) throw new java.io.IOException("项目ZIP超过20MiB"); out.write(value); }
        @Override public void write(byte[] bytes, int offset, int length) throws java.io.IOException { count += length; if (count > limit) throw new java.io.IOException("项目ZIP超过20MiB"); out.write(bytes, offset, length); }
        @Override public void close() throws java.io.IOException { flush(); } // The caller retains/syncs its destination.
    }
}
