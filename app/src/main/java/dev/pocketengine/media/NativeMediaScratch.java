package dev.pocketengine.media;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/** Private, unpublished codec scratch only. All current callers run in the default app
 * process. Creation, live registration and cleanup share one lock, while each lease stays
 * live through the last codec release. This is not a cross-process filesystem lock or a
 * cleanup policy for AttachmentBook, view snapshots, or published native evidence. */
public final class NativeMediaScratch {
    private static final Object DISK=new Object();
    private static final Set<Path> LIVE=new HashSet<>();
    private NativeMediaScratch(){}

    public static Lease trimSource(File privateCache)throws IOException {
        return create(privateCache,"original-media-processing","trim-source-","trim-(source|output)-[0-9]{1,20}\\.mp4",8);
    }
    public static Lease trimOutput(File privateCache)throws IOException {
        return create(privateCache,"original-media-processing","trim-output-","trim-(source|output)-[0-9]{1,20}\\.mp4",-1);
    }
    public static Lease providerValidation(File privateCache)throws IOException {
        return create(privateCache,"provider-media-validation","original-provider-","original-provider-[0-9]{1,20}\\.mp4",4);
    }
    private static Lease create(File cache,String name,String prefix,String temporaryName,int limit)throws IOException {
        synchronized(DISK){
            File root=new File(cache.getCanonicalFile(),name);
            if(Files.isSymbolicLink(root.toPath())||!root.isDirectory()&&!root.mkdirs()||!Files.isDirectory(root.toPath(),LinkOption.NOFOLLOW_LINKS))throw new IOException("无法准备原媒体临时缓存");
            File[] files=root.listFiles();if(files==null)throw new IOException("无法核对原媒体临时缓存");
            // These exact factory namespaces contain no durable records or issued URIs.
            // A timestamp is not evidence of death: a live lease is always retained.
            for(File file:files){Path path=file.toPath();if(!LIVE.contains(path)&&file.getName().matches(temporaryName)&&Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))Files.deleteIfExists(path);}
            files=root.listFiles();if(files==null||limit>=0&&files.length>limit)throw new IOException("原媒体临时缓存仍待释放，请核对后重试");
            File file=File.createTempFile(prefix,".mp4",root);Path path=file.toPath();LIVE.add(path);return new Lease(file,path);
        }
    }
    public static final class Lease implements AutoCloseable {
        private final File file;private final Path path;private boolean closed;
        private Lease(File file,Path path){this.file=file;this.path=path;}
        public File file(){return file;}
        @Override public void close()throws IOException {
            synchronized(DISK){if(closed)return;try{Files.deleteIfExists(path);}finally{LIVE.remove(path);closed=true;}}
        }
    }
}
