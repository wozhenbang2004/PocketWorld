package dev.pocketengine.features.local.project;

import static org.junit.Assert.*;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.*;
import org.json.*;
import org.junit.*;

/** New post-recovery fixtures against the rescued exact SourceImport and new codec glue. */
public final class ProjectSourceImportReimplementationTest {
    private Path temp;
    @Before public void create()throws Exception{temp=Files.createTempDirectory("new-c03-source-");}
    @After public void close()throws Exception{try(var paths=Files.walk(temp)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
    private Path dir(String name)throws Exception{return Files.createDirectories(temp.resolve(name));}
    private static void text(Path root,String path,String value)throws Exception{Path p=root.resolve(path);Files.createDirectories(p.getParent());Files.writeString(p,value);}
    private interface Action{void run()throws Exception;}
    private static void reject(Action action)throws Exception{assertThrows(Exception.class,action::run);}
    static final class OwnTree implements ProjectSourceImport.Tree {
        final Path root;int opened;Runnable onOpen;
        OwnTree(Path root){this.root=root;}
        public String rootHandle(){return "root";}
        public List<ProjectSourceImport.Entry> children(String handle)throws Exception{Path p=handle.equals("root")?root:root.resolve(handle);List<ProjectSourceImport.Entry> rows=new ArrayList<>();try(var entries=Files.list(p)){for(Path file:entries.sorted().toList()){String key=root.relativize(file).toString();rows.add(new ProjectSourceImport.Entry(key,file.getFileName().toString(),Files.isDirectory(file),Files.isDirectory(file)?null:Files.size(file),false,false));}}return rows;}
        public InputStream open(ProjectSourceImport.Entry entry)throws Exception{opened++;if(onOpen!=null)onOpen.run();return Files.newInputStream(root.resolve(entry.handle));}
    }
    private ProjectSourceImport.Selection tree(ProjectSourceImport.Tree source,String name,ProjectSourceImport.Current current)throws Exception{return ProjectSourceImport.tree(source,dir(name).toFile(),UUID.randomUUID().toString(),"主人所选源码",false,current);}
    static byte[] zip(Map<String,byte[]> entries)throws Exception{ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(ZipOutputStream zip=new ZipOutputStream(bytes)){for(var row:entries.entrySet()){zip.putNextEntry(new ZipEntry(row.getKey()));zip.write(row.getValue());zip.closeEntry();}}return bytes.toByteArray();}
    private Path archive(String name,byte[] bytes)throws Exception{return Files.write(temp.resolve(name+".zip"),bytes);}
    private ProjectSourceImport.Selection unzip(Path archive,String name)throws Exception{return ProjectSourceImport.zip(archive.toFile(),dir(name).toFile(),UUID.randomUUID().toString(),"普通ZIP源码",true,false,()->{});}
    private ProjectSourceImport.Selection tree(OwnTree tree,String name)throws Exception{return ProjectSourceImport.tree(tree,dir(name).toFile(),UUID.randomUUID().toString(),"源码目录",false,()->{});}
    @Test public void actualTreeRereadsBinaryAndDirectoriesAndUsesOriginalPortableCodec()throws Exception {
        Path raw=dir("raw");text(raw,"src/app.js","export const amount=40;\n");Files.write(raw.resolve("picture.bin"),new byte[]{0,-1,42});Files.createDirectories(raw.resolve("empty/deep"));text(raw,".env","private");text(raw,"node_modules/pkg/private.js","private cache");OwnTree source=new OwnTree(raw);var selected=tree(source,"copy");assertEquals(4,source.opened);assertArrayEquals(new byte[]{0,-1,42},selected.frozen.file("picture.bin"));assertTrue(selected.frozen.manifest().getJSONArray("directories").toString().contains("empty/deep"));assertFalse(selected.frozen.files().containsKey(".env"));assertFalse(selected.frozen.files().containsKey("node_modules/pkg/private.js"));assertEquals(2,selected.evidence().getInt("omittedCount"));assertFalse(selected.evidence().getBoolean("executionAuthorityRestored"));assertFalse(selected.evidence().getBoolean("sourcePermissionPersisted"));ByteArrayOutputStream out=new ByteArrayOutputStream();ProjectPackageArchive.write(selected.frozen,out);var decoded=ProjectPackageArchive.read(archive("portable",out.toByteArray()).toFile());assertEquals(selected.frozen.identity,decoded.identity);assertArrayEquals(selected.frozen.file("picture.bin"),decoded.file("picture.bin"));
    }
    @Test public void providerPartialVirtualDuplicateHandlesAndCyclesRejectRatherThanPublish()throws Exception {
        for(int scenario=0;scenario<4;scenario++){final int kind=scenario;ProjectSourceImport.Tree invalid=new ProjectSourceImport.Tree(){public String rootHandle(){return "root";}public List<ProjectSourceImport.Entry> children(String h){if(kind<2)return List.of(new ProjectSourceImport.Entry("x","main.js",false,1L,kind==0,kind==1));if(kind==2)return List.of(new ProjectSourceImport.Entry("same","a.js",false,1L,false,false),new ProjectSourceImport.Entry("same","b.js",false,1L,false,false));return List.of(new ProjectSourceImport.Entry("root","cycle",true,null,false,false));}public InputStream open(ProjectSourceImport.Entry e){return new ByteArrayInputStream(new byte[]{1});}};String name="invalid"+scenario;reject(()->tree(invalid,name,()->{}));}
    }
    @Test public void providerChangesBetweenCopiesOrCancellationRetainsNoSuccessfulSelection()throws Exception {
        Path raw=dir("changing");text(raw,"main.js","a");OwnTree changing=new OwnTree(raw);changing.onOpen=()->{if(changing.opened==2)try{Files.writeString(raw.resolve("main.js"),"b");}catch(Exception failure){throw new IllegalStateException(failure);}};reject(()->tree(changing,"changed"));OwnTree cancelled=new OwnTree(raw);AtomicInteger checks=new AtomicInteger();reject(()->tree(cancelled,"cancelled",()->{if(checks.incrementAndGet()>5)throw new InterruptedIOException("explicit cancellation");}));assertEquals("b",Files.readString(raw.resolve("main.js")));
    }
    @Test public void ordinaryZipStripsOnlyExplicitSingleRootAndPreservesBinaryAndExclusionEvidence()throws Exception {
        Map<String,byte[]> rows=new LinkedHashMap<>();rows.put("repo/main.js","export const amount=40;".getBytes(StandardCharsets.UTF_8));rows.put("repo/raw.bin",new byte[]{0,1,-1});rows.put("repo/empty/",new byte[0]);rows.put("repo/.env","private".getBytes());rows.put("repo/node_modules/private.js","cache".getBytes());var selected=unzip(archive("normal",zip(rows)),"zip-copy");assertEquals("repo",selected.evidence().getJSONObject("original").getString("singleRootStripped"));assertFalse(selected.evidence().getBoolean("ordinaryZipIsPocketWorldPackage"));assertArrayEquals(new byte[]{0,1,-1},selected.frozen.file("raw.bin"));assertTrue(selected.frozen.manifest().getJSONArray("directories").toString().contains("empty"));assertEquals(2,selected.frozen.manifest().getJSONArray("omitted").length());
    }
    private static List<Integer> central(byte[] bytes){List<Integer> positions=new ArrayList<>();ByteBuffer b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);for(int i=0;i+46<=bytes.length;i++)if(b.getInt(i)==0x02014b50)positions.add(i);return positions;}
    private void retainRawZipFacts(Path archive,String input)throws Exception {
        byte[] bytes=Files.readAllBytes(archive);ByteBuffer b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);int c=central(bytes).get(0),local=b.getInt(c+42),length=b.getShort(c+28)&65535;String raw=new String(bytes,c+46,length,StandardCharsets.UTF_8);
        JSONObject facts=new JSONObject().put("originalInput",input).put("rawCentralName",raw).put("versionMadeBy",b.getShort(c+4)&65535).put("creatorPlatform",bytes[c+5]&255).put("externalAttributes",Integer.toUnsignedLong(b.getInt(c+38))).put("rawLocalName",new String(bytes,local+30,b.getShort(local+26)&65535,StandardCharsets.UTF_8)).put("archiveSha256",ProjectSources.sha256(bytes));
        try(var zip=new org.apache.commons.compress.archivers.zip.ZipFile(archive.toFile(),StandardCharsets.UTF_8.name())){facts.put("commonsGetName",zip.getEntries().nextElement().getName());}catch(Exception rejected){facts.put("commonsReadError",rejected.getClass().getName());}
        System.out.println("C03_RAW_ZIP "+facts);
        String retained=System.getProperty("c2.rawZipEvidence");if(retained!=null){Path folder=Path.of(retained);Files.createDirectories(folder);String key=ProjectSources.sha256(input.getBytes(StandardCharsets.UTF_8)).substring(0,16);Files.write(folder.resolve(key+".zip"),bytes,StandardOpenOption.CREATE_NEW);Files.writeString(folder.resolve(key+".json"),facts.toString(2)+"\n",StandardOpenOption.CREATE_NEW);}
    }
    @Test public void zipSlipSymlinkUnixSpecialAndDuplicateTargetsReject()throws Exception {
        for(String path:List.of("../evil.js","/absolute.js","a\\b.js")){Path a=archive("slip"+Math.abs(path.hashCode()),zip(Map.of(path,new byte[]{1})));retainRawZipFacts(a,path);reject(()->unzip(a,"out"+Math.abs(path.hashCode())));}
        for(int mode:new int[]{0120777,0010644}){byte[] bytes=zip(Map.of("link.js","target".getBytes()));int at=central(bytes).get(0);bytes[at+5]=3;ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(at+38,mode<<16);Path a=archive("mode"+mode,bytes);reject(()->unzip(a,"mode-out"+mode));}
        byte[] duplicate=zip(new LinkedHashMap<>(Map.of("a.js",new byte[]{1},"b.js",new byte[]{2})));List<Integer> c=central(duplicate);ByteBuffer b=ByteBuffer.wrap(duplicate).order(ByteOrder.LITTLE_ENDIAN);int second=c.get(1),first=c.get(0),offset=b.getInt(second+42);for(int i=0;i<4;i++){duplicate[second+46+i]=duplicate[first+46+i];duplicate[offset+30+i]=duplicate[first+46+i];}Path a=archive("duplicate",duplicate);reject(()->unzip(a,"duplicate-out"));
    }
    @Test public void allDeclaredZipBytesAreBoundedEvenWhenNamesWouldBeExcluded()throws Exception {
        Map<String,byte[]> rows=new LinkedHashMap<>();for(int i=0;i<9;i++)rows.put("node_modules/private"+i,new byte[1]);rows.put("main.js",new byte[]{1});byte[] bytes=zip(rows);ByteBuffer b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);List<Integer> c=central(bytes);for(int at:c)if(bytes[at+46]=='n')b.putInt(at+24,ProjectPackageArchive.MAX_FILE_BYTES);Path a=archive("declaration",bytes);reject(()->unzip(a,"declaration-out"));byte[] oversized=zip(Map.of("main.js",new byte[]{1}));b=ByteBuffer.wrap(oversized).order(ByteOrder.LITTLE_ENDIAN);b.putInt(central(oversized).get(0)+24,ProjectPackageArchive.MAX_FILE_BYTES+1);Path tooLarge=archive("too-large",oversized);reject(()->unzip(tooLarge,"too-large-out"));
    }
    @Test public void zipCrcCommentAndIncompleteDirectoryRejectWithoutFallback()throws Exception {
        byte[] crc=zip(Map.of("main.js",new byte[]{1,2,3}));int at=central(crc).get(0);ByteBuffer.wrap(crc).order(ByteOrder.LITTLE_ENDIAN).putInt(at+16,1234567);Path bad=archive("crc",crc);reject(()->unzip(bad,"crc-out"));ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(ZipOutputStream z=new ZipOutputStream(bytes)){z.setComment("not supported");z.putNextEntry(new ZipEntry("main.js"));z.write(1);z.closeEntry();}Path comment=archive("comment",bytes.toByteArray());reject(()->unzip(comment,"comment-out"));Path cut=archive("incomplete",Arrays.copyOf(crc,crc.length-3));reject(()->unzip(cut,"incomplete-out"));
    }
    @Test public void newSelectionMustUseEmptyDestinationAndNoProgressStreamFailsBoundedly()throws Exception {
        Path source=dir("already"),destination=dir("not-empty");text(source,"main.js","one");text(destination,"keep.js","untouched");reject(()->ProjectSourceImport.tree(new OwnTree(source),destination.toFile(),UUID.randomUUID().toString(),"source",false,()->{}));assertEquals("untouched",Files.readString(destination.resolve("keep.js")));InputStream noProgress=new InputStream(){public int read(){return 0;}public int read(byte[] b,int o,int n){return 0;}};reject(()->ProjectSourceImport.copyArchive(noProgress,temp.resolve("partial.zip").toFile(),()->{}));
    }
}
