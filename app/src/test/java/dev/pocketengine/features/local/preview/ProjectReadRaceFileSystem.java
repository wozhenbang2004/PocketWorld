package dev.pocketengine.features.local.preview;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.nio.file.spi.FileSystemProvider;
import java.util.*;

/** Delegates to real files, with deterministic changes at the original open/read boundary. */
public final class ProjectReadRaceFileSystem extends FileSystem {
    @FunctionalInterface public interface Change { void run(Path path) throws IOException; }
    private final FileSystem delegate=FileSystems.getDefault();
    private final Provider provider=new Provider();
    private final Map<Path,Long> readBytes=new HashMap<>();
    public Change beforeOpen=path->{},afterRead=path->{};
    public boolean noFollow=true;public int opens,closes;
    public Path wrap(Path path){return new ObservedPath(path);}
    private Path raw(Path path){return ((ObservedPath)path).actual;}
    public long bytesRead(Path path){return readBytes.getOrDefault(path,0L);}
    public File root(Path path){return new File(path.toString()){@Override public Path toPath(){return wrap(path);}};}
    @Override public FileSystemProvider provider(){return provider;}
    @Override public void close(){}
    @Override public boolean isOpen(){return true;}
    @Override public boolean isReadOnly(){return delegate.isReadOnly();}
    @Override public String getSeparator(){return delegate.getSeparator();}
    @Override public Iterable<Path> getRootDirectories(){throw new UnsupportedOperationException();}
    @Override public Iterable<FileStore> getFileStores(){return delegate.getFileStores();}
    @Override public Set<String> supportedFileAttributeViews(){return delegate.supportedFileAttributeViews();}
    @Override public Path getPath(String first,String... more){return wrap(delegate.getPath(first,more));}
    @Override public PathMatcher getPathMatcher(String pattern){return path->delegate.getPathMatcher(pattern).matches(raw(path));}
    @Override public UserPrincipalLookupService getUserPrincipalLookupService(){return delegate.getUserPrincipalLookupService();}
    @Override public WatchService newWatchService()throws IOException{return delegate.newWatchService();}
    private final class Provider extends FileSystemProvider {
        @Override public String getScheme(){return "preview-race";}
        @Override public FileSystem newFileSystem(URI uri,Map<String,?> env){throw new UnsupportedOperationException();}
        @Override public FileSystem getFileSystem(URI uri){return ProjectReadRaceFileSystem.this;}
        @Override public Path getPath(URI uri){throw new UnsupportedOperationException();}
        @Override public SeekableByteChannel newByteChannel(Path path,Set<? extends OpenOption> options,FileAttribute<?>... attrs)throws IOException {
            Path actual=raw(path);beforeOpen.run(actual);noFollow&=options.contains(LinkOption.NOFOLLOW_LINKS);
            SeekableByteChannel channel=Files.newByteChannel(actual,options,attrs);opens++;
            return new SeekableByteChannel(){
                @Override public int read(ByteBuffer target)throws IOException{int n=channel.read(target);if(n>0){readBytes.merge(actual,(long)n,Long::sum);afterRead.run(actual);}return n;}
                @Override public int write(ByteBuffer source)throws IOException{return channel.write(source);}
                @Override public long position()throws IOException{return channel.position();}
                @Override public SeekableByteChannel position(long value)throws IOException{channel.position(value);return this;}
                @Override public long size()throws IOException{return channel.size();}
                @Override public SeekableByteChannel truncate(long size)throws IOException{channel.truncate(size);return this;}
                @Override public boolean isOpen(){return channel.isOpen();}
                @Override public void close()throws IOException{if(channel.isOpen())closes++;channel.close();}
            };
        }
        @Override public DirectoryStream<Path> newDirectoryStream(Path path,DirectoryStream.Filter<? super Path> filter)throws IOException {
            List<Path> entries=new ArrayList<>();try(DirectoryStream<Path> stream=Files.newDirectoryStream(raw(path))){for(Path entry:stream){Path wrapped=wrap(entry);if(filter.accept(wrapped))entries.add(wrapped);}}
            entries.sort(Comparator.comparing(Path::toString));return new DirectoryStream<Path>(){public Iterator<Path> iterator(){return entries.iterator();}public void close(){}};
        }
        @Override public void createDirectory(Path path,FileAttribute<?>... attrs)throws IOException{Files.createDirectory(raw(path),attrs);}
        @Override public void delete(Path path)throws IOException{Files.delete(raw(path));}
        @Override public void copy(Path source,Path target,CopyOption... options)throws IOException{Files.copy(raw(source),raw(target),options);}
        @Override public void move(Path source,Path target,CopyOption... options)throws IOException{Files.move(raw(source),raw(target),options);}
        @Override public boolean isSameFile(Path a,Path b)throws IOException{return Files.isSameFile(raw(a),raw(b));}
        @Override public boolean isHidden(Path path)throws IOException{return Files.isHidden(raw(path));}
        @Override public FileStore getFileStore(Path path)throws IOException{return Files.getFileStore(raw(path));}
        @Override public void checkAccess(Path path,AccessMode... modes)throws IOException{delegate.provider().checkAccess(raw(path),modes);}
        @Override public <V extends FileAttributeView> V getFileAttributeView(Path path,Class<V> type,LinkOption... options){return Files.getFileAttributeView(raw(path),type,options);}
        @Override public <A extends BasicFileAttributes> A readAttributes(Path path,Class<A> type,LinkOption... options)throws IOException{return Files.readAttributes(raw(path),type,options);}
        @Override public Map<String,Object> readAttributes(Path path,String attributes,LinkOption... options)throws IOException{return Files.readAttributes(raw(path),attributes,options);}
        @Override public void setAttribute(Path path,String attribute,Object value,LinkOption... options)throws IOException{Files.setAttribute(raw(path),attribute,value,options);}
    }
    private final class ObservedPath implements Path {
        final Path actual;ObservedPath(Path actual){this.actual=actual;}
        @Override public FileSystem getFileSystem(){return ProjectReadRaceFileSystem.this;}
        @Override public boolean isAbsolute(){return actual.isAbsolute();}
        @Override public Path getRoot(){return actual.getRoot()==null?null:wrap(actual.getRoot());}
        @Override public Path getFileName(){return actual.getFileName()==null?null:wrap(actual.getFileName());}
        @Override public Path getParent(){return actual.getParent()==null?null:wrap(actual.getParent());}
        @Override public int getNameCount(){return actual.getNameCount();}
        @Override public Path getName(int index){return wrap(actual.getName(index));}
        @Override public Path subpath(int begin,int end){return wrap(actual.subpath(begin,end));}
        @Override public boolean startsWith(Path other){return actual.startsWith(raw(other));}
        @Override public boolean endsWith(Path other){return actual.endsWith(raw(other));}
        @Override public Path normalize(){return wrap(actual.normalize());}
        @Override public Path resolve(Path other){return wrap(actual.resolve(raw(other)));}
        @Override public Path relativize(Path other){return wrap(actual.relativize(raw(other)));}
        @Override public URI toUri(){return actual.toUri();}
        @Override public Path toAbsolutePath(){return wrap(actual.toAbsolutePath());}
        @Override public Path toRealPath(LinkOption... options)throws IOException{return wrap(actual.toRealPath(options));}
        @Override public File toFile(){return actual.toFile();}
        @Override public WatchKey register(WatchService watcher,WatchEvent.Kind<?>[] events,WatchEvent.Modifier... modifiers)throws IOException{return actual.register(watcher,events,modifiers);}
        @Override public Iterator<Path> iterator(){Iterator<Path> iterator=actual.iterator();return new Iterator<Path>(){public boolean hasNext(){return iterator.hasNext();}public Path next(){return wrap(iterator.next());}};}
        @Override public int compareTo(Path other){return actual.compareTo(raw(other));}
        @Override public boolean equals(Object other){return other instanceof ProjectReadRaceFileSystem.ObservedPath&&actual.equals(((ObservedPath)other).actual);}
        @Override public int hashCode(){return actual.hashCode();}
        @Override public String toString(){return actual.toString();}
    }
}
