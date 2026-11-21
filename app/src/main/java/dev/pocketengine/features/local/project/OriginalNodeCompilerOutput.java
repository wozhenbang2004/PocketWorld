package dev.pocketengine.features.local.project;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.local.runtime.NativeProcessSupervisor;
import dev.pocketengine.features.local.runtime.RuntimeExecutionRef;
import dev.pocketengine.features.local.runtime.RuntimeInvocation;
import dev.pocketengine.features.local.runtime.RuntimeWorkspace;
import dev.pocketengine.features.plugins.mcp.McpJson;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/** Evidence for one ordinary locked tsc build. No publication, retained body, or execution permission. */
public final class OriginalNodeCompilerOutput {
    public static final String WITNESS="nodeCompilerOutput", FORMAT="pocketworld.owner-node-compiler-output.v1";
    public static final int MAX_BYTES=256*1024;
    private static final String ABSENT="absent";
    private static final Pattern INPUT=Pattern.compile("src/([A-Za-z0-9][A-Za-z0-9_-]{0,63})\\.ts"),
        OUTPUT=Pattern.compile("dist/[A-Za-z0-9][A-Za-z0-9_-]{0,63}\\.js");
    private static final Set<String> PIN_KEYS=Set.of("format","taskId","runId","epoch","buildOperationId","projectId","checkoutId",
        "sourceDigest","planDigest","requestDigest","runtimeIdentity","checkoutSequence","path","bytes","sha256","beforeSha256");
    private static final Set<String> PLAIN_OPTIONS=Set.of("strict","esModuleInterop","skipLibCheck","noEmitOnError",
        "forceConsistentCasingInFileNames","isolatedModules");
    private static final Set<String> DISABLED_OPTIONS=Set.of("noEmit","declaration","declarationMap","emitDeclarationOnly",
        "sourceMap","inlineSourceMap","inlineSources","incremental","composite","allowJs");
    private static final Set<String> NAMED_OPTIONS=Set.of("target","module","moduleResolution","moduleDetection");
    private OriginalNodeCompilerOutput(){}

    private static boolean npmBuild(JSONObject exact, String marker)throws Exception {
        JSONObject payload=exact.optJSONObject("payload");
        return payload!=null&&"managed_process_v1".equals(exact.opt("runtimeId"))&&"project.runtime.run".equals(exact.opt("command"))&&
            "build".equals(exact.opt("intent"))&&"build".equals(exact.opt("stepId"))&&"npm".equals(payload.opt("executable"))&&
            "[\"run\",\"build\"]".equals(ProjectSources.canonical(payload.opt("argv")))&&".".equals(payload.optString("workingDirectory","."))&&
            "foreground".equals(payload.optString("runMode","foreground"))&&!payload.has("terminalMode")&&!payload.has("devWatch")&&
            marker.equals(OwnerDraftFirstBuild.ordinaryWebVersion(exact));
    }

    private static String selectedPath(JSONObject exact,JSONObject snapshot)throws Exception {
        JSONObject checkout=snapshot.getJSONObject("checkout");
        if(!OwnerDraftFirstBuild.ordinaryCheckout(checkout)||checkout.has(OwnerDraftWorkCopy.FIELD)||
            checkout.has("collectedFromCheckoutId")||checkout.has("collectionOperationId")||
            !exact.getString("checkoutId").equals(checkout.opt("id"))||!exact.getString("projectId").equals(checkout.opt("projectId")))return null;
        // Only inspect these existing, frozen, bounded source records. Never infer an entry from a name
        // in stdout, or walk the current source/dependency directories to select a result.
        JSONObject pkg=null,config=null;String input=null,output=null;boolean lock=false;
        JSONArray files=snapshot.getJSONArray("files");
        for(int i=0;i<files.length();i++){
            JSONObject file=files.getJSONObject(i);String path=file.getString("path");
            if("package.json".equals(path))pkg=smallJson(file);
            else if("tsconfig.json".equals(path))config=smallJson(file);
            else if("package-lock.json".equals(path))lock=file.opt("content") instanceof String;
            else if("pnpm-lock.yaml".equals(path))return null;
            else if(path.startsWith("src/")&&path.endsWith(".ts")&&!path.endsWith(".d.ts")){
                var match=INPUT.matcher(path);
                if(input!=null||!match.matches()||!(file.opt("content") instanceof String))return null;
                input=path;output="dist/"+match.group(1)+".js";
            }else if(path.startsWith("src/")&&(path.endsWith(".tsx")||path.endsWith(".mts")||path.endsWith(".cts")))return null;
        }
        if(!lock||input==null||pkg==null||config==null)return null;
        JSONObject scripts=pkg.optJSONObject("scripts"),deps=pkg.optJSONObject("dependencies"),dev=pkg.optJSONObject("devDependencies");
        if(scripts==null||!"tsc".equals(scripts.opt("build"))||scripts.has("prebuild")||scripts.has("postbuild")||
            !((deps!=null&&deps.opt("typescript") instanceof String)^(dev!=null&&dev.opt("typescript") instanceof String)))return null;
        JSONObject options=config.optJSONObject("compilerOptions");
        if(options==null||!keysWithin(config,Set.of("compilerOptions","files","include"))||
            !("src".equals(options.opt("rootDir"))||"./src".equals(options.opt("rootDir")))||
            !("dist".equals(options.opt("outDir"))||"./dist".equals(options.opt("outDir"))))return null;
        for(var keys=options.keys();keys.hasNext();){
            String key=keys.next();Object value=options.get(key);
            if("rootDir".equals(key)||"outDir".equals(key))continue;
            if(PLAIN_OPTIONS.contains(key)?!(value instanceof Boolean):DISABLED_OPTIONS.contains(key)?!Boolean.FALSE.equals(value):
                !NAMED_OPTIONS.contains(key)||!(value instanceof String text)||!text.matches("[A-Za-z0-9_-]{1,32}"))return null;
        }
        if(config.has("files")&&config.has("include"))return null;
        if(!(single(config.optJSONArray("files"),input)||single(config.optJSONArray("include"),"src/**/*.ts")||
            single(config.optJSONArray("include"),"src/*.ts")))return null;
        return output;
    }

    private static JSONObject smallJson(JSONObject file)throws Exception {
        if(!(file.opt("content") instanceof String source)||source.length()>65536||
            source.getBytes(StandardCharsets.UTF_8).length>65536)return null;
        return McpJson.parse(source);
    }
    private static boolean keysWithin(JSONObject row,Set<String> allowed){
        for(var keys=row.keys();keys.hasNext();)if(!allowed.contains(keys.next()))return false;return true;
    }
    private static boolean single(JSONArray list,String value){return list!=null&&list.length()==1&&value.equals(list.opt(0));}

    /** Native values only. Identity stamps never cross the process or leave the private checkout. */
    public static final class LaunchObservation {
        private final RuntimeInvocation invocation;
        private final String path,sha;
        private final Object rootKey,distKey;
        private LaunchObservation(RuntimeInvocation invocation,String path,Sample before){
            this.invocation=invocation;this.path=path;sha=before.sha();rootKey=before.root.key;
            distKey=before.dist==null?null:before.dist.key;
        }
    }
    public static LaunchObservation observeBeforeLaunch(RuntimeInvocation invocation)throws Exception{
        JSONObject exact=invocation.exact();String marker=invocation.existingWebVersion();
        if(marker.isEmpty()||!invocation.editedWebVersion().isEmpty()||!npmBuild(exact,marker))return null;
        String path=selectedPath(exact,invocation.sourceSnapshot());if(path==null)return null;
        RuntimeWorkspace root=invocation.workspace();root.verify(invocation);
        try(Sample before=read(root,path,invocation::checkCurrent)){
            return new LaunchObservation(invocation,path,before);
        }
    }

    /** Called only by the same live worker after actual success, while the original lease is still held. */
    public static JSONObject observeAtOriginalExit(RuntimeInvocation invocation,long sequence,LaunchObservation before)throws Exception {
        if(before==null||before.invocation!=invocation||sequence<1)return null;
        RuntimeWorkspace root=invocation.workspace();root.verify(invocation);
        try(Sample exit=read(root,before.path,invocation::checkCurrent);Sample again=read(root,before.path,invocation::checkCurrent)){
            if(exit.leaf==null||!exit.same(again)||!before.rootKey.equals(exit.root.key)||
                before.distKey!=null&&!before.distKey.equals(exit.dist.key)||before.sha.equals(exit.sha()))return null;
            root.verify(invocation);invocation.checkCurrent();
            return binding(invocation.executionRef(),sequence).put("path",before.path).put("bytes",exit.leaf.bytes)
                .put("sha256",exit.sha()).put("beforeSha256",before.sha);
        }
    }

    public static final class Witness {
        private final JSONObject pin;
        private Witness(JSONObject pin)throws Exception{this.pin=ProjectSources.copy(pin);}
        public JSONObject pin()throws Exception{return ProjectSources.copy(pin);}
        public String path(){return pin.optString("path");}
        public long bytes(){return pin.optLong("bytes");}
        public String sha256(){return pin.optString("sha256");}
    }
    /** Caller supplies the original SQL route and this actual retained native receipt, never Web/model data. */
    public static Witness fromReceipt(JSONObject exact,JSONObject originalRoute,JSONObject receipt)throws Exception {
        if(!npmBuild(exact,originalRoute.optString(OriginalNodeWebOutput.EXISTING_ROUTE))||originalRoute.has(OriginalNodeWebOutput.EDITED_ROUTE)||
            !"managed_process".equals(receipt.opt("provider"))||!"command".equals(receipt.opt("kind"))||!"completed".equals(receipt.opt("status"))||
            !"foreground".equals(receipt.opt("runMode"))||!"pre_exec_manifest_verified".equals(receipt.opt("sourceCheckStatus"))||
            !"npm".equals(receipt.opt("requestedExecutable"))||!"[\"run\",\"build\"]".equals(ProjectSources.canonical(receipt.opt("requestedArgv")))||
            !NativeProcessSupervisor.knownOrdinaryExitContract(receipt.optString("nativeResultContract"))||
            !"exit_observed".equals(receipt.opt("launchPhase"))||!number(receipt.opt("exitCode"),0)||
            !number(receipt.opt("terminationSignal"),0)||!number(receipt.opt("launchErrno"),0))throw unavailable();
        for(String key:Set.of("receiptReceived","processLaunched","processExited","processGroupAbsent","resourcesReleased","outputComplete"))
            if(!Boolean.TRUE.equals(receipt.opt(key)))throw unavailable();
        for(String key:Set.of("ownedWorkerLive","cancelRequested","timedOut","outputTruncated","noChildCreated"))
            if(!Boolean.FALSE.equals(receipt.opt(key)))throw unavailable();
        RuntimeExecutionRef ref=RuntimeExecutionRef.fromHost(exact,originalRoute);ref.requireReceipt(receipt);
        long sequence=integer(receipt.opt("checkoutSequence")),size;
        JSONObject witness=receipt.optJSONObject(WITNESS);
        if(sequence<1||witness==null||witness.length()!=PIN_KEYS.size()||!keysWithin(witness,PIN_KEYS)||
            !outputPath(witness.opt("path"))||(size=integer(witness.opt("bytes")))<1||size>MAX_BYTES||!digest(witness.opt("sha256"))||
            !(ABSENT.equals(witness.opt("beforeSha256"))||digest(witness.opt("beforeSha256")))||
            witness.getString("sha256").equals(witness.opt("beforeSha256")))throw unavailable();
        JSONObject expected=binding(ref,sequence);
        for(var keys=expected.keys();keys.hasNext();){String key=keys.next();
            if(!ProjectSources.canonical(expected.get(key)).equals(ProjectSources.canonical(witness.opt(key))))throw unavailable();}
        return new Witness(witness);
    }

    private static JSONObject binding(RuntimeExecutionRef ref,long sequence)throws Exception {
        JSONObject exact=ref.exact(),scope=ref.binding();
        JSONObject out=new JSONObject().put("format",FORMAT).put("taskId",scope.getString("taskId")).put("runId",scope.getString("runId"))
            .put("epoch",scope.getInt("coordinatorEpoch")).put("buildOperationId",ref.operationId()).put("checkoutSequence",sequence);
        for(String key:Set.of("projectId","checkoutId","sourceDigest","planDigest","requestDigest","runtimeIdentity"))out.put(key,exact.get(key));
        return out;
    }

    /** Foreground owner caller independently checks the unchanged source, environment, order and window.
     * Only serve bytes matching the original exit witness, never a later unpinned same-name file. */
    public static byte[] readPinned(RuntimeWorkspace root,Witness witness,RunControl control)throws Exception {
        long pause=control.pauseRevision();
        Current current=()->{control.check();if(control.isPaused()||control.pauseRevision()!=pause)throw unavailable();};
        current.check();
        if(!root.projectId().equals(witness.pin.opt("projectId"))||!root.checkoutId().equals(witness.pin.opt("checkoutId")))throw unavailable();
        try(Sample file=read(root,witness.path(),current);Sample again=read(root,witness.path(),current)){
            if(file.leaf==null||!file.same(again)||file.leaf.bytes!=witness.bytes()||!file.sha().equals(witness.sha256()))throw unavailable();
            current.check();return file.body.clone();
        }
    }

    private interface Current{void check()throws Exception;}
    private record Directory(Object key,FileTime modified){}
    private record FileStamp(Object key,FileTime modified,FileTime changed,long bytes){}
    private record Leaf(FileStamp stamp,String sha,long bytes){}
    private record Sample(Directory root,Directory dist,Leaf leaf,byte[] body) implements AutoCloseable{
        String sha(){return leaf==null?ABSENT:leaf.sha;}
        boolean same(Sample other){return root.equals(other.root)&&Objects.equals(dist,other.dist)&&Objects.equals(leaf,other.leaf);}
        @Override public void close(){if(body!=null)Arrays.fill(body,(byte)0);}
    }
    private static Sample read(RuntimeWorkspace workspace,String path,Current current)throws Exception {
        if(!outputPath(path)||ProjectPackageArchive.excluded(path,false,true)!=null)throw unavailable();
        current.check();Path root=workspace.directory().toPath(),folder=workspace.resolve("dist",false).toPath();
        Directory rootBefore=directory(root),distBefore;
        try{distBefore=directory(folder);}catch(NoSuchFileException absent){
            if(!rootBefore.equals(directory(root))||!Files.notExists(folder,LinkOption.NOFOLLOW_LINKS))throw unavailable();
            current.check();return new Sample(rootBefore,null,null,null);
        }
        Path file=null;byte[] bytes=null;boolean keep=false;
        try{
            try(var children=Files.newDirectoryStream(folder)){
                for(Path child:children){
                    // Stop at the first additional/unrelated entry. Do not inspect its type or bytes.
                    if(file!=null||!path.equals("dist/"+child.getFileName()))throw unavailable();file=child;
                }
            }
            Leaf leaf=null;
            if(file!=null){
                if(!file.equals(workspace.resolve(path,true).toPath()))throw unavailable();
                BasicFileAttributes attributes=Files.readAttributes(file,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
                FileStamp before=stamp(file,attributes);
                bytes=ProjectFileBytes.read(file,attributes,MAX_BYTES);
                if(!before.equals(stamp(file,Files.readAttributes(file,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS)))||
                    !file.equals(workspace.resolve(path,true).toPath()))throw unavailable();
                var text=StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes));
                while(text.hasRemaining())if(text.get()==0)throw unavailable();
                leaf=new Leaf(before,ProjectSources.sha256(bytes),bytes.length);
            }
            if(!rootBefore.equals(directory(root))||!distBefore.equals(directory(folder)))throw unavailable();
            current.check();keep=true;return new Sample(rootBefore,distBefore,leaf,bytes);
        }finally{if(!keep&&bytes!=null)Arrays.fill(bytes,(byte)0);}
    }
    private static Directory directory(Path path)throws Exception {
        BasicFileAttributes a=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if(!a.isDirectory()||a.isSymbolicLink()||a.fileKey()==null||!path.equals(path.toFile().getCanonicalFile().toPath()))throw unavailable();
        return new Directory(a.fileKey(),a.lastModifiedTime());
    }
    private static FileStamp stamp(Path path,BasicFileAttributes attributes)throws Exception {
        var unix=Files.readAttributes(path,"unix:nlink,ctime",LinkOption.NOFOLLOW_LINKS);
        if(!attributes.isRegularFile()||attributes.isSymbolicLink()||attributes.fileKey()==null||attributes.size()<1||attributes.size()>MAX_BYTES||
            !(unix.get("nlink") instanceof Number links)||links.longValue()!=1||!(unix.get("ctime") instanceof FileTime changed))throw unavailable();
        return new FileStamp(attributes.fileKey(),attributes.lastModifiedTime(),changed,attributes.size());
    }
    private static boolean outputPath(Object path){return path instanceof String text&&OUTPUT.matcher(text).matches();}
    private static boolean digest(Object hash){return hash instanceof String value&&value.matches("[a-f0-9]{64}");}
    private static long integer(Object n){return n instanceof Integer||n instanceof Long?((Number)n).longValue():-1;}
    private static boolean number(Object n,long expected){return integer(n)==expected;}
    private static SecurityException unavailable(){return new SecurityException("无法核对这轮原编译退出时观察到的单个文件，未使用当前同名文件替代");}
}
