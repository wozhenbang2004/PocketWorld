package dev.pocketengine.features.local.runtime;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.Map;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;

/** Commons Compress reads the tar format. Files are written without traversing archive-created links. */
final class RuntimeTarExtractor {
    private RuntimeTarExtractor() {}
    interface HardLinkCreator {void create(Path path,Path target) throws Exception;}
    private static final class Link {final Path path,target;final boolean hard;Link(Path path,Path target,boolean hard){this.path=path;this.target=target;this.hard=hard;}}
    private static final class Mode {final Path path;final int mode;Mode(Path path,int mode){this.path=path;this.mode=mode;}}
    static void extract(File archive,String format,File destination,int strip,long budget,
            ProjectRuntime.LiveGate gate,RuntimeEnvironment.Progress progress) throws Exception {
        extract(archive,format,destination,strip,budget,gate,progress,Files::createLink);
    }
    static void extract(File archive,String format,File destination,int strip,long budget,
            ProjectRuntime.LiveGate gate,RuntimeEnvironment.Progress progress,HardLinkCreator hardLinks) throws Exception {
        Path root=destination.getCanonicalFile().toPath();
        if(!destination.isDirectory()||Files.isSymbolicLink(root))throw new SecurityException("runtime_extract_root_invalid");
        Set<Path> entries=new HashSet<>();List<Link> links=new ArrayList<>();List<Mode> modes=new ArrayList<>();long expanded=0;int count=0;
        try(InputStream raw=new BufferedInputStream(new FileInputStream(archive));
                InputStream compressed=format.equals("tar.gz")?new GzipCompressorInputStream(raw):new XZCompressorInputStream(raw);
                TarArchiveInputStream tar=new TarArchiveInputStream(compressed)) {
            TarArchiveEntry entry;byte[] bytes=new byte[65536];
            while((entry=tar.getNextEntry())!=null) {
                gate.check();if(++count>200000)throw new SecurityException("runtime_archive_entry_budget_exceeded");
                Path relative=relative(entry.getName(),strip);if(relative==null)continue;
                Path path=root.resolve(relative).normalize();if(!path.startsWith(root)||!entries.add(path))throw new SecurityException("runtime_archive_duplicate_or_escaping_path");
                parent(root,path.getParent());
                if(entry.isSymbolicLink()||entry.isLink()) {
                    String name=entry.getLinkName();if(name.isEmpty()||name.contains("\\")||name.indexOf('\0')>=0)throw new SecurityException("runtime_archive_link_invalid");
                    Path target;
                    if(entry.isLink()) {Path targetRelative=relative(name,strip);if(targetRelative==null)throw new SecurityException("runtime_archive_hardlink_invalid");target=root.resolve(targetRelative).normalize();}
                    else target=name.startsWith("/")?root.resolve(name.substring(1)).normalize():path.getParent().resolve(name).normalize();
                    if(!target.startsWith(root))throw new SecurityException("runtime_archive_link_escape");
                    links.add(new Link(path,target,entry.isLink()));continue;
                }
                if(entry.isDirectory()) {
                    if(Files.exists(path,LinkOption.NOFOLLOW_LINKS)&&!Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS))throw new SecurityException("runtime_archive_entry_conflict");
                    Files.createDirectories(path);modes.add(new Mode(path,entry.getMode()));continue;
                }
                if(entry.isFIFO()||entry.isCharacterDevice()||entry.isBlockDevice()||!entry.isFile()||entry.isSparse()||!tar.canReadEntryData(entry))throw new SecurityException("runtime_archive_special_entry_rejected");
                if(entry.getSize()<0||entry.getSize()>budget-expanded||Files.exists(path,LinkOption.NOFOLLOW_LINKS))throw new SecurityException("runtime_archive_expansion_budget_exceeded");
                long size=0;
                try(FileOutputStream output=new FileOutputStream(path.toFile())) {
                    int n;while((n=tar.read(bytes))!=-1) {gate.check();if(n==0)continue;size+=n;expanded+=n;if(size>entry.getSize()||expanded>budget)throw new SecurityException("runtime_archive_expansion_budget_exceeded");output.write(bytes,0,n);progress.changed("extracting",expanded,budget);}
                    output.getFD().sync();
                }
                if(size!=entry.getSize())throw new SecurityException("runtime_archive_truncated_entry");
                mode(path,entry.getMode());
            }
        }
        // Guest-absolute links become equivalent root-relative host links, never /host links.
        for(Link link:links)if(!link.hard) {gate.check();parent(root,link.path.getParent());if(Files.exists(link.path,LinkOption.NOFOLLOW_LINKS))throw new SecurityException("runtime_archive_link_conflict");Files.createSymbolicLink(link.path,link.path.getParent().relativize(link.target));}
        // Resolve the complete graph before the first callback. Android delegates to the
        // packaged PRoot's ln only now, when its loader, libraries and symlinks exist.
        // Resolve forward chains to a regular archive file; cycles and symbolic targets
        // are rejected rather than following an archive-created link into a host bind.
        Map<Path,Link> hard=new HashMap<>();for(Link link:links)if(link.hard)hard.put(link.path,link);
        List<Link> resolved=new ArrayList<>();Map<Path,Path> targets=new HashMap<>();
        for(Link link:links)if(link.hard) {
            gate.check();parent(root,link.path.getParent());
            if(Files.exists(link.path,LinkOption.NOFOLLOW_LINKS))throw new SecurityException("runtime_archive_hardlink_target_invalid");
            Path target=link.target;Set<Path> visited=new HashSet<>();List<Path> chain=new ArrayList<>();
            while(hard.containsKey(target)&&!targets.containsKey(target)) {
                if(!visited.add(target))throw new SecurityException("runtime_archive_hardlink_cycle");
                chain.add(target);target=hard.get(target).target;
            }
            if(targets.containsKey(target))target=targets.get(target);
            noLinks(root,target);
            if(!Files.isRegularFile(target,LinkOption.NOFOLLOW_LINKS))throw new SecurityException("runtime_archive_hardlink_target_missing");
            targets.put(link.path,target);for(Path part:chain)targets.put(part,target);
            resolved.add(new Link(link.path,target,true));
        }
        for(Link link:resolved) {gate.check();hardLinks.create(link.path,link.target);}
        for(int i=modes.size()-1;i>=0;i--)mode(modes.get(i).path,modes.get(i).mode);
        progress.changed("extracted",expanded,budget);
    }
    private static Path relative(String name,int strip) {
        if(name.indexOf('\0')>=0||name.startsWith("/")||name.contains("\\"))throw new SecurityException("runtime_archive_path_invalid");
        String clean=name;while(clean.startsWith("./"))clean=clean.substring(2);while(clean.endsWith("/"))clean=clean.substring(0,clean.length()-1);
        if(clean.isEmpty()||clean.equals("."))return null;
        String[] parts=clean.split("/",-1);for(String part:parts)if(part.isEmpty()||part.equals(".")||part.equals(".."))throw new SecurityException("runtime_archive_path_invalid");
        if(parts.length<=strip)return null;
        return java.nio.file.Paths.get(String.join("/",java.util.Arrays.copyOfRange(parts,strip,parts.length)));
    }
    private static void noLinks(Path root,Path path) {
        Path current=root;for(Path part:root.relativize(path)) {current=current.resolve(part);if(Files.isSymbolicLink(current))throw new SecurityException("runtime_archive_symlink_write_rejected");}
    }
    private static void parent(Path root,Path path) throws Exception {noLinks(root,path);Files.createDirectories(path);}
    private static void mode(Path path,int mode) throws Exception {
        // Do not install host setuid/setgid bits. Preserve the real guest rwx permissions.
        Set<PosixFilePermission> permissions=new HashSet<>();PosixFilePermission[] values={PosixFilePermission.OTHERS_EXECUTE,PosixFilePermission.OTHERS_WRITE,PosixFilePermission.OTHERS_READ,PosixFilePermission.GROUP_EXECUTE,PosixFilePermission.GROUP_WRITE,PosixFilePermission.GROUP_READ,PosixFilePermission.OWNER_EXECUTE,PosixFilePermission.OWNER_WRITE,PosixFilePermission.OWNER_READ};
        for(int i=0;i<values.length;i++)if((mode&(1<<i))!=0)permissions.add(values[i]);Files.setPosixFilePermissions(path,permissions);
    }
}
