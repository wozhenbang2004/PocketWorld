package dev.pocketengine.features.skills;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public final class SkillPackageTest {
    @Rule public final TemporaryFolder temp=new TemporaryFolder();
    private JSONObject scope()throws Exception{return new JSONObject().put("ownerId","owner").put("spaceId","private");}
    private String manifest(String version){return "---\nname: calculator-check\ndescription: 核对计算器结果\nmetadata:\n  version: '"+version+"'\n---\n观察后再行动。\n";}
    private Map<String,byte[]> files(String version){Map<String,byte[]> files=new LinkedHashMap<>();files.put("calculator-check/SKILL.md",manifest(version).getBytes(StandardCharsets.UTF_8));files.put("calculator-check/references/check.md","保留用户内容。\r\n🙂\n".getBytes(StandardCharsets.UTF_8));return files;}
    private byte[] zip(Map<String,byte[]> files)throws Exception{ByteArrayOutputStream out=new ByteArrayOutputStream();try(ZipOutputStream zip=new ZipOutputStream(out)){for(Map.Entry<String,byte[]> file:files.entrySet()){zip.putNextEntry(new ZipEntry(file.getKey()));zip.write(file.getValue());zip.closeEntry();}}return out.toByteArray();}
    private ByteArrayInputStream stream(byte[] bytes){return new ByteArrayInputStream(bytes);}
    private JSONObject options(SkillBook book,byte[] bytes,String id)throws Exception{
        JSONObject inspected=book.inspectPackage(scope(),stream(bytes),id,1000),input=new JSONObject().put("source","local:user-selected-zip").put("inspectedDigest",inspected.getJSONObject("package").getString("digest"));
        if(id!=null){JSONObject comparison=inspected.getJSONObject("comparison");input.put("id",id).put("revision",comparison.getInt("revision")).put("expectedCurrentDigest",comparison.getString("expectedCurrentDigest"));}return input;
    }
    private JSONObject install(SkillBook book,byte[] bytes)throws Exception{return book.importPackage(scope(),stream(bytes),options(book,bytes,null),1000);}
    private void enable(SkillBook book,JSONObject record)throws Exception{book.setEnabled(scope(),record.getString("id"),true,record.getString("digest"),true,1100);}
    private byte[] export(SkillBook book,JSONObject record)throws Exception{ByteArrayOutputStream out=new ByteArrayOutputStream();JSONObject result=book.exportPackage(scope(),record.getString("id"),record.getInt("revision"),record.getString("digest"),out,1200);assertFalse(result.getBoolean("containsApproval"));assertEquals(out.size(),result.getInt("archiveBytes"));return out.toByteArray();}

    @Test public void actualZipRoundTripPreservesTextAndDigestWithoutTransportingApproval()throws Exception{
        File root=new File(temp.getRoot(),"skills");SkillBook book=new SkillBook(root);Map<String,byte[]> files=files("1.0");files.put("calculator-check/",new byte[0]);files.put("calculator-check/references/",new byte[0]);files.put("calculator-check/scripts/check.sh","echo inspection-only".getBytes(StandardCharsets.UTF_8));byte[] archive=zip(files);
        JSONObject inspection=book.inspectPackage(scope(),stream(archive),null,1000);assertFalse(root.exists());assertFalse(inspection.toString().contains("echo inspection-only"));assertFalse(inspection.toString().contains("保留用户内容"));assertEquals("new",inspection.getJSONObject("comparison").getString("kind"));
        JSONObject record=install(book,archive);assertFalse(record.getBoolean("active"));enable(book,record);byte[] exported=export(book,record);
        SkillBook other=new SkillBook(new File(temp.getRoot(),"restored"));JSONObject restored=install(other,exported);assertEquals(record.getString("digest"),restored.getString("digest"));assertFalse(restored.getBoolean("enabled"));assertEquals("",restored.getString("approvedDigest"));assertNotEquals(record.getString("id"),restored.getString("id"));
        assertEquals("保留用户内容。\r\n🙂\n",other.read(scope(),restored.getString("id"),"references/check.md",0,100,1300).getString("content"));
        assertThrows(SecurityException.class,()->other.loadForHost(scope(),restored.getString("id"),restored.getString("digest"),Set.of(),1300));
    }

    @Test public void completeVersionComparisonAndOldExportRestoreCreateNewDisabledRevision()throws Exception{
        SkillBook book=new SkillBook(new File(temp.getRoot(),"skills"));byte[] v1=zip(files("1.0"));JSONObject first=install(book,v1);enable(book,first);byte[] saved=export(book,first);
        assertEquals("same-content",book.inspectPackage(scope(),stream(saved),first.getString("id"),1200).getJSONObject("comparison").getString("kind"));
        Map<String,byte[]> v2files=files("2.0");v2files.remove("calculator-check/references/check.md");v2files.put("calculator-check/references/new.md","新内容".getBytes(StandardCharsets.UTF_8));byte[] v2=zip(v2files);
        JSONObject comparison=book.inspectPackage(scope(),stream(v2),first.getString("id"),1200).getJSONObject("comparison");assertEquals("changed-content",comparison.getString("kind"));assertEquals("references/check.md",comparison.getJSONArray("removed").getString(0));assertEquals("references/new.md",comparison.getJSONArray("added").getString(0));assertEquals("SKILL.md",comparison.getJSONArray("modified").getString(0));
        JSONObject second=book.importPackage(scope(),stream(v2),options(book,v2,first.getString("id")),1300);assertEquals(2,second.getInt("revision"));assertFalse(second.getBoolean("enabled"));enable(book,second);
        JSONObject restored=book.importPackage(scope(),stream(saved),options(book,saved,first.getString("id")),1400);assertEquals(3,restored.getInt("revision"));assertEquals(first.getString("digest"),restored.getString("digest"));assertEquals("1.0",restored.getString("sourceVersion"));assertFalse(restored.getBoolean("active"));assertEquals("",restored.getString("approvedDigest"));
        assertThrows(IllegalArgumentException.class,()->book.read(scope(),restored.getString("id"),"references/new.md",0,100,1500));
    }

    @Test public void staleInspectionPackageChangeAndStaleExportCannotReplaceCurrentVersion()throws Exception{
        SkillBook book=new SkillBook(new File(temp.getRoot(),"skills"));byte[] v1=zip(files("1.0")),v2=zip(files("2.0"));JSONObject first=install(book,v1);enable(book,first);JSONObject stale=options(book,v2,first.getString("id"));
        assertThrows(SecurityException.class,()->book.importPackage(scope(),stream(v1),stale,1200));assertTrue(book.get(scope(),first.getString("id"),1200).getBoolean("active"));
        JSONObject second=book.importPackage(scope(),stream(v2),stale,1300);enable(book,second);assertThrows(IllegalStateException.class,()->book.importPackage(scope(),stream(v2),stale,1400));
        ByteArrayOutputStream output=new ByteArrayOutputStream();assertThrows(IllegalStateException.class,()->book.exportPackage(scope(),first.getString("id"),first.getInt("revision"),first.getString("digest"),output,1400));assertEquals(0,output.size());assertTrue(book.get(scope(),first.getString("id"),1400).getBoolean("active"));
    }

    @Test public void scopeBoundariesAndCapabilityExpiryRestrictionsSurvivePackageUpdate()throws Exception{
        SkillBook book=new SkillBook(new File(temp.getRoot(),"skills"));byte[] v1=zip(files("1.0")),v2=zip(files("2.0"));JSONObject input=options(book,v1,null).put("requiredCapabilities",new JSONArray().put("phone_observe")).put("expiresAt",5000);JSONObject first=book.importPackage(scope(),stream(v1),input,1000);
        JSONObject foreign=new JSONObject().put("ownerId","other").put("spaceId","private");assertThrows(IllegalArgumentException.class,()->book.inspectPackage(foreign,stream(v2),first.getString("id"),1200));assertThrows(IllegalArgumentException.class,()->book.importPackage(foreign,stream(v2),options(book,v2,first.getString("id")),1200));
        ByteArrayOutputStream out=new ByteArrayOutputStream();assertThrows(IllegalArgumentException.class,()->book.exportPackage(foreign,first.getString("id"),1,first.getString("digest"),out,1200));assertEquals(0,out.size());
        JSONObject second=book.importPackage(scope(),stream(v2),options(book,v2,first.getString("id")),1200);assertEquals("phone_observe",second.getJSONArray("requiredCapabilities").getString(0));assertEquals(5000,second.getLong("expiresAt"));enable(book,second);
        assertThrows(SecurityException.class,()->book.loadForHost(scope(),second.getString("id"),second.getString("digest"),Set.of(),1300));assertThrows(SecurityException.class,()->book.loadForHost(scope(),second.getString("id"),second.getString("digest"),Set.of("phone_observe"),5000));
    }

    @Test public void damagedInstalledContentCanBeRepairedButCannotBeExportedAsTrustedSnapshot()throws Exception{
        File root=new File(temp.getRoot(),"skills");SkillBook book=new SkillBook(root);byte[] bytes=zip(files("1.0"));JSONObject first=install(book,bytes);enable(book,first);
        Files.write(new File(root,first.getString("id")+"/1/calculator-check/references/check.md").toPath(),"tampered".getBytes(StandardCharsets.UTF_8));ByteArrayOutputStream out=new ByteArrayOutputStream();assertThrows(IllegalStateException.class,()->book.exportPackage(scope(),first.getString("id"),1,first.getString("digest"),out,1200));assertEquals(0,out.size());
        JSONObject inspection=book.inspectPackage(scope(),stream(bytes),first.getString("id"),1200);assertEquals("repair",inspection.getJSONObject("comparison").getString("kind"));assertFalse(inspection.getJSONObject("comparison").getBoolean("currentContentVerified"));
        JSONObject repaired=book.importPackage(scope(),stream(bytes),options(book,bytes,first.getString("id")),1300);assertEquals(2,repaired.getInt("revision"));assertFalse(repaired.getBoolean("active"));enable(book,repaired);assertTrue(book.readForHost(scope(),repaired.getString("id"),"SKILL.md",0,100,repaired.getString("digest"),Set.of(),1400).getString("content").contains("calculator-check"));
    }

    @Test public void malformedPersistedBytesCannotMasqueradeAsApprovedReplacementCharacter()throws Exception{
        File root=new File(temp.getRoot(),"skills");SkillBook book=new SkillBook(root);Map<String,byte[]> source=files("1.0");source.put("calculator-check/references/check.md","\ufffd".getBytes(StandardCharsets.UTF_8));byte[] archive=zip(source);JSONObject record=install(book,archive);enable(book,record);
        Files.write(new File(root,record.getString("id")+"/1/calculator-check/references/check.md").toPath(),new byte[]{(byte)0x80});
        ByteArrayOutputStream out=new ByteArrayOutputStream();assertThrows(IllegalStateException.class,()->book.exportPackage(scope(),record.getString("id"),1,record.getString("digest"),out,1200));assertEquals(0,out.size());
        JSONObject comparison=book.inspectPackage(scope(),stream(archive),record.getString("id"),1200).getJSONObject("comparison");assertEquals("repair",comparison.getString("kind"));assertFalse(comparison.getBoolean("currentContentVerified"));
        assertThrows(IllegalStateException.class,()->book.readForHost(scope(),record.getString("id"),"references/check.md",0,100,record.getString("digest"),Set.of(),1200));
    }

    @Test public void traversalAbsoluteBackslashMultipleRootsAndFileDirectoryConflictsFailBeforeInstall()throws Exception{
        SkillBook book=new SkillBook(new File(temp.getRoot(),"skills"));String[] paths={"calculator-check/../outside.md","/calculator-check/bad.md","calculator-check\\bad.md","other/second.md","calculator-check/references/check.md/child.md"};
        for(String path:paths){Map<String,byte[]> files=files("1.0");files.put(path,"bad".getBytes(StandardCharsets.UTF_8));byte[] bytes=zip(files);assertThrows(path,IllegalArgumentException.class,()->book.inspectPackage(scope(),stream(bytes),null,1000));}
        Map<String,byte[]> wrong=new LinkedHashMap<>();wrong.put("other/SKILL.md",manifest("1.0").getBytes(StandardCharsets.UTF_8));assertThrows(IllegalArgumentException.class,()->book.inspectPackage(scope(),stream(zip(wrong)),null,1000));assertEquals(0,book.list(scope(),1000).length());
    }

    @Test public void malformedUtf8BinaryAndMissingManifestAreRejected()throws Exception{
        SkillBook book=new SkillBook(new File(temp.getRoot(),"skills"));Map<String,byte[]> malformed=files("1.0");malformed.put("calculator-check/references/check.md",new byte[]{(byte)0xc3,0x28});assertThrows(Exception.class,()->book.inspectPackage(scope(),stream(zip(malformed)),null,1000));
        Map<String,byte[]> binary=files("1.0");binary.put("calculator-check/assets/photo.png",new byte[]{1,0,2});assertThrows(IllegalArgumentException.class,()->book.inspectPackage(scope(),stream(zip(binary)),null,1000));
        Map<String,byte[]> missing=files("1.0");missing.remove("calculator-check/SKILL.md");assertThrows(IllegalArgumentException.class,()->book.inspectPackage(scope(),stream(zip(missing)),null,1000));assertEquals(0,book.list(scope(),1000).length());
    }

    @Test public void fileCountCharacterBytesAndCompressedUploadBoundsAreEnforced()throws Exception{
        SkillBook book=new SkillBook(new File(temp.getRoot(),"skills"));Map<String,byte[]> many=files("1.0");for(int i=0;i<40;i++)many.put("calculator-check/references/"+i+".txt",new byte[0]);assertThrows(IllegalArgumentException.class,()->book.inspectPackage(scope(),stream(zip(many)),null,1000));
        Map<String,byte[]> longFile=files("1.0");longFile.put("calculator-check/references/large.txt","x".repeat(64001).getBytes(StandardCharsets.UTF_8));assertThrows(IllegalArgumentException.class,()->book.inspectPackage(scope(),stream(zip(longFile)),null,1000));
        Map<String,byte[]> huge=files("1.0");for(int i=0;i<6;i++)huge.put("calculator-check/references/"+i+".txt","长".repeat(64000).getBytes(StandardCharsets.UTF_8));assertThrows(IllegalArgumentException.class,()->book.inspectPackage(scope(),stream(zip(huge)),null,1000));
        assertThrows(IllegalArgumentException.class,()->book.inspectPackage(scope(),stream(new byte[SkillArchive.MAX_ARCHIVE_BYTES+1]),null,1000));assertEquals(0,book.list(scope(),1000).length());
    }

    @Test public void truncatedCrcCorruptDuplicatePathAndSymlinkArchivesAreRejected()throws Exception{
        SkillBook book=new SkillBook(new File(temp.getRoot(),"skills"));byte[] good=zip(files("1.0"));assertThrows(IllegalArgumentException.class,()->book.inspectPackage(scope(),stream(Arrays.copyOf(good,good.length-22)),null,1000));
        byte[] crc=good.clone();int central=find(crc,new byte[]{0x50,0x4b,0x01,0x02});int descriptor=find(crc,new byte[]{0x50,0x4b,0x07,0x08});crc[descriptor+4]^=1;assertThrows(Exception.class,()->book.inspectPackage(scope(),stream(crc),null,1000));
        byte[] directoryCrc=good.clone();directoryCrc[central+16]^=1;assertThrows(IllegalArgumentException.class,()->book.inspectPackage(scope(),stream(directoryCrc),null,1000));
        byte[] link=good.clone();link[central+40]=(byte)0xff;link[central+41]=(byte)0xa1;assertThrows(IllegalArgumentException.class,()->book.inspectPackage(scope(),stream(link),null,1000));
        Map<String,byte[]> duplicate=files("1.0");duplicate.put("calculator-check/references/other.md","x".getBytes(StandardCharsets.UTF_8));byte[] repeated=zip(duplicate);replaceAll(repeated,"references/other.md","references/check.md");assertThrows(IllegalArgumentException.class,()->book.inspectPackage(scope(),stream(repeated),null,1000));
    }

    @Test public void failedImportAndOutputWriteKeepOriginalRegistryAndApproval()throws Exception{
        SkillBook book=new SkillBook(new File(temp.getRoot(),"skills"));byte[] v1=zip(files("1.0"));JSONObject first=install(book,v1);enable(book,first);
        byte[] bad=Arrays.copyOf(v1,v1.length-5);JSONObject input=options(book,v1,first.getString("id"));assertThrows(IllegalArgumentException.class,()->book.importPackage(scope(),stream(bad),input,1200));
        OutputStream failed=new OutputStream(){@Override public void write(int value)throws IOException{throw new IOException("document provider failed");}};assertThrows(IOException.class,()->book.exportPackage(scope(),first.getString("id"),1,first.getString("digest"),failed,1200));
        JSONObject current=book.get(scope(),first.getString("id"),1300);assertEquals(1,current.getInt("revision"));assertTrue(current.getBoolean("active"));assertEquals(first.getString("digest"),current.getString("approvedDigest"));
    }

    private int find(byte[] bytes,byte[] pattern){outer:for(int i=0;i<=bytes.length-pattern.length;i++){for(int j=0;j<pattern.length;j++)if(bytes[i+j]!=pattern[j])continue outer;return i;}throw new AssertionError("fixture ZIP header missing");}
    private void replaceAll(byte[] bytes,String old,String replacement){byte[] before=old.getBytes(StandardCharsets.UTF_8),after=replacement.getBytes(StandardCharsets.UTF_8);assertEquals(before.length,after.length);outer:for(int i=0;i<=bytes.length-before.length;i++){for(int j=0;j<before.length;j++)if(bytes[i+j]!=before[j])continue outer;System.arraycopy(after,0,bytes,i,after.length);}}
}
