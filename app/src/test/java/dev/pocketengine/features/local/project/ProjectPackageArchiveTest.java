package dev.pocketengine.features.local.project;

import dev.pocketengine.features.local.preview.ProjectPreviewBundle;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class ProjectPackageArchiveTest {
    private static boolean hasDirectory(org.json.JSONArray paths,String expected)throws Exception{for(int i=0;i<paths.length();i++)if(expected.equals(paths.getString(i)))return true;return false;}
    private static final byte[] PNG=java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aN1kAAAAASUVORK5CYII=");
    @Test public void actualBinaryNestedSourceEmptyDirectoriesAndOwnerExecutableRoundTripWithoutEnvironmentOrSecrets() throws Exception {
        File root=Files.createTempDirectory("package-source-").toFile();
        try {
            text(root,"index.html","<link rel=stylesheet href=style.css><script src=src/build/app.js></script><img src=pixel.png>");text(root,"style.css","body{color:#222}");text(root,"src/build/app.js","globalThis.total=40.00");text(root,"src/dist/input.txt","real nested source");bytes(root,"pixel.png",PNG);text(root,"bin/tool.sh","#!/bin/sh\nexit 0\n");assertTrue(new File(root,"bin/tool.sh").setExecutable(true,true));assertTrue(new File(root,"empty/sub").mkdirs());
            for(String denied:new String[]{"node_modules/pkg/index.js",".venv/bin/python",".git/config",".env",".npmrc","config/accounts.json","config/id_ed25519","build/old.js","dist/old.html"})text(root,denied,"not exported");
            var captured=ProjectPackageArchive.capture(root,original(),"source_project","",false);var manifest=captured.manifest();
            assertArrayEquals(PNG,captured.file("pixel.png"));assertTrue(captured.files().containsKey("src/build/app.js"));assertTrue(captured.files().containsKey("src/dist/input.txt"));assertTrue(ProjectSources.sourcePath("src/build/app.js"));assertFalse(ProjectSources.sourcePath("build/old.js"));assertEquals(6,captured.files().size());assertTrue(hasDirectory(manifest.getJSONArray("directories"),"empty/sub"));assertTrue(manifest.getJSONArray("omitted").length()>=9);assertFalse(manifest.getBoolean("includesToolEnvironment"));
            File archive=write(root,captured);var cold=ProjectPackageArchive.read(archive);assertEquals(captured.identity,cold.identity);assertArrayEquals(PNG,cold.file("pixel.png"));
            assertTrue(file(cold,"bin/tool.sh").getBoolean("executable"));assertEquals("binary",file(cold,"pixel.png").getString("kind"));
            byte[] detached=cold.file("pixel.png");detached[0]=0;assertArrayEquals(PNG,cold.file("pixel.png"));
            text(root,"src/build/app.js","globalThis.total=45.00");assertEquals("globalThis.total=40.00",new String(cold.file("src/build/app.js"),StandardCharsets.UTF_8));
            var preview=ProjectPreviewBundle.fromProjectPackage(cold,UUID.randomUUID().toString(),"a".repeat(64),"index.html");assertArrayEquals(PNG,preview.resource(preview.origin+"pixel.png").bytes());assertEquals(4,preview.manifest().length());assertTrue(preview.metadata().getBoolean("persisted"));assertFalse(preview.metadata().getBoolean("businessVerified"));
            var other=ProjectPreviewBundle.fromProjectPackage(cold,UUID.randomUUID().toString(),"a".repeat(64),"index.html");assertNotEquals(preview.identity,other.identity);
        } finally {remove(root);}
    }
    @Test public void explicitGeneratedScopeAndWebEntryHaveHonestOmissions()throws Exception {
        File root=Files.createTempDirectory("package-output-").toFile();try{
            text(root,"src/build/input.ts","export const amount=40");text(root,"dist/index.html","<script src=app.js></script><img src=pixel.png>");text(root,"dist/app.js","globalThis.amount=40");bytes(root,"dist/pixel.png",PNG);text(root,"dist/unknown.db","unrendered binary");text(root,"dist/.env","secret");
            var source=ProjectPackageArchive.capture(root,original(),"source_project","",true);assertTrue(source.files().containsKey("dist/unknown.db"));assertFalse(source.files().containsKey("dist/.env"));
            var web=ProjectPackageArchive.capture(root,original(),"web_output","dist/index.html",false);assertEquals(3,web.files().size());assertFalse(web.files().containsKey("src/build/input.ts"));assertEquals("dist",web.manifest().getString("previewRoot"));assertEquals(2,web.manifest().getJSONArray("omitted").length());
            var cold=ProjectPackageArchive.read(write(root,web));assertEquals(web.identity,cold.identity);
        }finally{remove(root);}
    }
    @Test public void linksSpecialAndOversizeNeverProducePartialArchive()throws Exception {
        File root=Files.createTempDirectory("package-links-").toFile();File outside=Files.createTempFile("outside-package-",".txt").toFile();try{
            text(root,"index.html","base");Files.createSymbolicLink(new File(root,"leak.txt").toPath(),outside.toPath());reject(()->ProjectPackageArchive.capture(root,original(),"source_project","",false));Files.delete(new File(root,"leak.txt").toPath());
            bytes(root,"too-large.bin",new byte[ProjectPackageArchive.MAX_FILE_BYTES+1]);reject(()->ProjectPackageArchive.capture(root,original(),"source_project","",false));
        }finally{remove(root);Files.deleteIfExists(outside.toPath());}
    }
    @Test public void traversalUnknownNamespaceDuplicateAndUnixSymlinkEntriesAreRejected()throws Exception {
        File root=Files.createTempDirectory("package-malformed-").toFile();try{
            File zip=new File(root,"external.zip");for(String path:new String[]{"../outside","project/../../outside","unknown/data"}){try(var out=new ZipArchiveOutputStream(zip)){ZipArchiveEntry entry=new ZipArchiveEntry(path);out.putArchiveEntry(entry);out.write(new byte[]{1});out.closeArchiveEntry();}reject(()->ProjectPackageArchive.read(zip));}
            try(var out=new ZipArchiveOutputStream(zip)){for(int i=0;i<2;i++){out.putArchiveEntry(new ZipArchiveEntry("project/same"));out.write(new byte[]{1});out.closeArchiveEntry();}}reject(()->ProjectPackageArchive.read(zip));
            try(var out=new ZipArchiveOutputStream(zip)){ZipArchiveEntry entry=new ZipArchiveEntry("project/link");entry.setUnixMode(0120777);out.putArchiveEntry(entry);out.write("/outside".getBytes(StandardCharsets.UTF_8));out.closeArchiveEntry();}reject(()->ProjectPackageArchive.read(zip));
        }finally{remove(root);}
    }
    @Test public void modifiedPayloadMissingDeclaredFileAndEmbeddedAuthorityAreRejected()throws Exception {
        File root=Files.createTempDirectory("package-integrity-").toFile();try{
            text(root,"index.html","original");var frozen=ProjectPackageArchive.capture(root,original(),"source_project","",false);Map<String,byte[]> actual=new LinkedHashMap<>(frozen.files());File zip=new File(root,"changed.zip");
            actual.put("index.html","tampered".getBytes(StandardCharsets.UTF_8));custom(zip,frozen.manifest(),actual);reject(()->ProjectPackageArchive.read(zip));
            actual.clear();custom(zip,frozen.manifest(),actual);reject(()->ProjectPackageArchive.read(zip));
            custom(zip,frozen.manifest().put("grants",new JSONObject().put("execute",true)),frozen.files());reject(()->ProjectPackageArchive.read(zip));
            JSONObject header=frozen.manifest();header.getJSONObject("source").put("runId",UUID.randomUUID().toString());custom(zip,header,frozen.files());reject(()->ProjectPackageArchive.read(zip));
        }finally{remove(root);}
    }
    @Test public void importerReappliesPrivatePolicyEvenToSelfConsistentForeignManifest()throws Exception {
        File root=Files.createTempDirectory("package-private-").toFile();try{
            text(root,"index.html","base");var frozen=ProjectPackageArchive.capture(root,original(),"source_project","",false);JSONObject header=frozen.manifest();byte[] secret="private".getBytes(StandardCharsets.UTF_8);header.getJSONArray("files").put(new JSONObject().put("path",".env").put("bytes",secret.length).put("sha256",ProjectSources.sha256(secret)).put("kind","text").put("mimeType","text/plain").put("executable",false));header.put("bytes",header.getLong("bytes")+secret.length);Map<String,byte[]> payload=frozen.files();payload.put(".env",secret);File zip=new File(root,"private.zip");custom(zip,header,payload);reject(()->ProjectPackageArchive.read(zip));
        }finally{remove(root);}
    }
    @Test public void forgedZipEntryCountIsRejectedBeforeEntryAllocation()throws Exception {
        File root=Files.createTempDirectory("package-directory-limit-").toFile();try{
            text(root,"index.html","base");var frozen=ProjectPackageArchive.capture(root,original(),"source_project","",false);File zip=write(root,frozen);byte[] bytes=Files.readAllBytes(zip.toPath());int end=bytes.length-22;
            assertEquals(0x50,bytes[end]&255);java.nio.ByteBuffer data=java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN);data.putShort(end+8,(short)60000);data.putShort(end+10,(short)60000);Files.write(zip.toPath(),bytes);reject(()->ProjectPackageArchive.read(zip));
        }finally{remove(root);}
    }
    private interface Action{void run()throws Exception;}
    private static void reject(Action action)throws Exception{try{action.run();fail("Invalid archive unexpectedly accepted");}catch(AssertionError failed){throw failed;}catch(Exception expected){}}
    static JSONObject original()throws Exception{return new JSONObject().put("projectId",UUID.randomUUID().toString()).put("checkoutId",UUID.randomUUID().toString()).put("sourceDigest","a".repeat(64)).put("name","实际多资源项目");}
    static void text(File root,String path,String text)throws Exception{bytes(root,path,text.getBytes(StandardCharsets.UTF_8));}
    static void bytes(File root,String path,byte[] bytes)throws Exception{File target=new File(root,path);Files.createDirectories(target.toPath().getParent());Files.write(target.toPath(),bytes);}
    private static JSONObject file(ProjectPackageArchive.Frozen frozen,String path)throws Exception{var files=frozen.manifest().getJSONArray("files");for(int i=0;i<files.length();i++)if(path.equals(files.getJSONObject(i).getString("path")))return files.getJSONObject(i);throw new AssertionError(path);}
    static File write(File root,ProjectPackageArchive.Frozen frozen)throws Exception{File archive=new File(root.getParentFile(),UUID.randomUUID()+".zip");try(var out=Files.newOutputStream(archive.toPath())){ProjectPackageArchive.write(frozen,out);}archive.deleteOnExit();return archive;}
    private static void custom(File destination,JSONObject manifest,Map<String,byte[]> files)throws Exception{try(var zip=new ZipOutputStream(Files.newOutputStream(destination.toPath()),StandardCharsets.UTF_8)){zip.putNextEntry(new ZipEntry("manifest.json"));zip.write(manifest.toString().getBytes(StandardCharsets.UTF_8));zip.closeEntry();for(var row:files.entrySet()){zip.putNextEntry(new ZipEntry("project/"+row.getKey()));zip.write(row.getValue());zip.closeEntry();}}}
    static void remove(File file)throws Exception{try(var paths=Files.walk(file.toPath())){for(var path:paths.sorted(java.util.Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
}
