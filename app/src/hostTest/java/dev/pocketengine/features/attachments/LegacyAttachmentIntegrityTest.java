package dev.pocketengine.features.attachments;

import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.ProviderInfo;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.FeatureContext;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowParcelFileDescriptor;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

/** Exact legacy module/repository/provider consumers, not mounted UI or external-reader acceptance. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class,shadows={LegacyAttachmentIntegrityTest.PosixDupShadow.class,LegacyAttachmentIntegrityTest.PosixSeekShadow.class})
public final class LegacyAttachmentIntegrityTest {
    @Rule public TemporaryFolder directory=new TemporaryFolder();
    private static final byte[] ORIGINAL="original".getBytes(StandardCharsets.UTF_8);
    private static final byte[] REPLACED="replaced".getBytes(StandardCharsets.UTF_8);
    private final ArrayList<Intent> launches=new ArrayList<>();

    /** Robolectric 4.16.1's default static dup copies bytes into a new rw file.
     * This test-only JDK17 POSIX bridge preserves actual access mode and offset.
     * Run with --add-opens=java.base/java.io=ALL-UNNAMED and
     * --add-opens=java.base/sun.nio.fs=ALL-UNNAMED. No Android-device claim. */
    @Implements(ParcelFileDescriptor.class)
    public static final class PosixDupShadow extends ShadowParcelFileDescriptor {
        private java.io.FileDescriptor nativeDescriptor;
        @Implementation protected static ParcelFileDescriptor dup(java.io.FileDescriptor source)throws IOException {
            try{
                java.lang.reflect.Method duplicate=Class.forName("sun.nio.fs.UnixNativeDispatcher").getDeclaredMethod("dup",int.class);duplicate.setAccessible(true);
                int fd=(Integer)duplicate.invoke(null,(Integer)ReflectionHelpers.getField(source,"fd"));java.io.FileDescriptor result=new java.io.FileDescriptor();ReflectionHelpers.setField(result,"fd",fd);
                try{ParcelFileDescriptor parcel=ReflectionHelpers.callConstructor(ParcelFileDescriptor.class,ReflectionHelpers.ClassParameter.from(java.io.FileDescriptor.class,result));((PosixDupShadow)org.robolectric.shadow.api.Shadow.extract(parcel)).nativeDescriptor=result;return parcel;}catch(RuntimeException failure){try{new java.io.FileInputStream(result).close();}catch(Exception cleanup){failure.addSuppressed(cleanup);}throw failure;}
            }catch(ReflectiveOperationException failure){throw new IOException("JDK17 native dup fixture unavailable",failure);}
        }
        @Implementation @Override protected void close()throws IOException {
            java.io.FileDescriptor descriptor=nativeDescriptor;nativeDescriptor=null;
            try{if(descriptor!=null)PosixSeekShadow.close(descriptor);}catch(android.system.ErrnoException failure){throw new IOException(failure);}finally{super.close();}
        }
    }
    @Implements(android.system.Os.class)
    public static final class PosixSeekShadow {
        @Implementation protected static long lseek(java.io.FileDescriptor fd,long offset,int whence)throws android.system.ErrnoException {
            if(whence!=android.system.OsConstants.SEEK_SET)throw new AssertionError("Only the production SEEK_SET contract is selected");
            try{return new java.io.FileInputStream(fd).getChannel().position(offset).position();} // Borrowed FD: its PFD owner closes it.
            catch(IOException failure){throw new android.system.ErrnoException("lseek",android.system.OsConstants.EIO,failure);}
        }
        @Implementation protected static void close(java.io.FileDescriptor fd)throws android.system.ErrnoException {
            try{int nativeFd=ReflectionHelpers.getField(fd,"fd");if(nativeFd<0)return;java.lang.reflect.Method close=Class.forName("sun.nio.fs.UnixNativeDispatcher").getDeclaredMethod("close",int.class);close.setAccessible(true);close.invoke(null,nativeFd);ReflectionHelpers.setField(fd,"fd",-1);}
            catch(ReflectiveOperationException failure){throw new android.system.ErrnoException("close",android.system.OsConstants.EIO,failure);}
        }
    }
    private java.util.Map<String,String> openDescriptors()throws Exception {java.util.Map<String,String> result=new java.util.TreeMap<>();try(var entries=Files.list(java.nio.file.Path.of("/proc/self/fd"))){for(java.nio.file.Path entry:entries.toList())try{String target=Files.readSymbolicLink(entry).toString();if(!target.equals("/proc/"+ProcessHandle.current().pid()+"/fd"))result.put(entry.getFileName().toString(),target);}catch(java.nio.file.NoSuchFileException vanished){}}return result;}

    @Test public void nativeDupFixturePreservesReadOnlyOffsetAndLifetimeWithoutDescriptorLeaks()throws Exception {
        File file=directory.newFile("dup-contract.bin");Files.write(file.toPath(),ORIGINAL);java.util.Map<String,String> before=openDescriptors();
        try(var source=new java.io.FileInputStream(file);var duplicate=ParcelFileDescriptor.dup(source.getFD());var input=new ParcelFileDescriptor.AutoCloseInputStream(duplicate)){
            assertEquals('o',source.read());assertEquals('r',input.read());
            try(var output=new ParcelFileDescriptor.AutoCloseOutputStream(ParcelFileDescriptor.dup(duplicate.getFileDescriptor()))){assertThrows(IOException.class,()->output.write('X'));}
            assertEquals(3,android.system.Os.lseek(duplicate.getFileDescriptor(),3,android.system.OsConstants.SEEK_SET));assertEquals('g',source.read());source.close();assertArrayEquals("inal".getBytes(StandardCharsets.UTF_8),input.readAllBytes());
        }
        assertEquals(before,openDescriptors());assertArrayEquals(ORIGINAL,Files.readAllBytes(file.toPath()));
    }

    private Context context()throws Exception {
        File files=directory.newFolder("files"),cache=directory.newFolder("cache");
        return new ContextWrapper(RuntimeEnvironment.getApplication()) {
            @Override public Context getApplicationContext(){return this;}
            @Override public File getFilesDir(){return files;}
            @Override public File getCacheDir(){return cache;}
            @Override public void startActivity(Intent intent){launches.add(intent);}
        };
    }
    private JSONObject original(AttachmentRepository repository)throws Exception {
        return repository.book.importBytes(new ByteArrayInputStream(ORIGINAL),"source.txt","text/plain",new JSONObject());
    }
    private AttachmentsModule module(Context context){return new AttachmentsModule(new FeatureContext(context,null,null,event->{}));}
    private AttachmentProvider provider(Context context){AttachmentProvider provider=new AttachmentProvider();ProviderInfo info=new ProviderInfo();info.authority=context.getPackageName()+".attachments";provider.attachInfo(context,info);return provider;}
    private File blob(Context context,String id){return new File(new File(context.getFilesDir(),"attachments"),id+".blob");}
    private int viewFiles(Context context){File[] files=new File(context.getCacheDir(),"outcome-views").listFiles();return files==null?0:files.length;}
    private byte[] read(AttachmentProvider provider,Uri uri)throws Exception {try(var input=new ParcelFileDescriptor.AutoCloseInputStream(provider.openFile(uri,"r"))){return input.readAllBytes();}}

    @Test public void legacyTextReadRejectsSameLengthCorruptionInsteadOfPairingItWithStoredDigest()throws Exception {
        Context context=context();AttachmentRepository repository=AttachmentRepository.get(context);JSONObject file=original(repository);String id=file.getString("id");AttachmentsModule module=module(context);AtomicInteger ownerChecks=new AtomicInteger();module.setNativeRecordRead(record->ownerChecks.incrementAndGet());
        JSONObject read=module.handle("readText",new JSONObject().put("id",id));assertEquals("original",read.getString("text"));assertEquals(file.getString("sha256"),read.getString("sha256"));assertTrue(read.getBoolean("untrustedAttachmentContent"));
        Files.write(blob(context,id).toPath(),REPLACED);assertEquals(file.getLong("size"),repository.book.get(id).getLong("size"));assertThrows(Exception.class,()->module.handle("readText",new JSONObject().put("id",id)));assertEquals(2,ownerChecks.get());assertEquals(file.getString("sha256"),repository.book.get(id).getString("sha256"));
    }
    @Test public void bookExportChecksTheExactSnapshotBeforeWritingAnyBytes()throws Exception {
        Context context=context();AttachmentRepository repository=AttachmentRepository.get(context);JSONObject file=original(repository);String id=file.getString("id");ByteArrayOutputStream clean=new ByteArrayOutputStream();assertEquals(ORIGINAL.length,repository.book.exportTo(id,clean));assertArrayEquals(ORIGINAL,clean.toByteArray());
        Files.write(blob(context,id).toPath(),REPLACED);ByteArrayOutputStream output=new ByteArrayOutputStream();assertThrows(Exception.class,()->repository.book.exportTo(id,output));assertEquals(0,output.size());assertArrayEquals(REPLACED,Files.readAllBytes(blob(context,id).toPath()));
    }
    @Test public void legacyExportRejectsCorruptionBeforeOpeningOrTruncatingTheDestination()throws Exception {
        Context context=context();AttachmentRepository repository=AttachmentRepository.get(context);JSONObject file=original(repository);String id=file.getString("id");Uri destination=Uri.parse("content://legacy-export/selected");AtomicInteger opened=new AtomicInteger();ByteArrayOutputStream output=new ByteArrayOutputStream();output.write("keep".getBytes(StandardCharsets.UTF_8));
        Shadows.shadowOf(context.getContentResolver()).registerOutputStreamSupplier(destination,()->{opened.incrementAndGet();output.reset();return output;});Files.write(blob(context,id).toPath(),REPLACED);
        assertThrows(Exception.class,()->module(context).handle("exportToUri",new JSONObject().put("id",id).put("uri",destination.toString())));assertEquals(0,opened.get());assertEquals("keep",output.toString(StandardCharsets.UTF_8));assertArrayEquals(REPLACED,Files.readAllBytes(blob(context,id).toPath()));
    }
    @Test public void successfulLegacyExportReportsOnlyTheActuallyVerifiedBytes()throws Exception {
        Context context=context();AttachmentRepository repository=AttachmentRepository.get(context);JSONObject file=original(repository);Uri destination=Uri.parse("content://legacy-export/selected");ByteArrayOutputStream output=new ByteArrayOutputStream();Shadows.shadowOf(context.getContentResolver()).registerOutputStream(destination,output);
        JSONObject result=module(context).handle("exportToUri",new JSONObject().put("id",file.getString("id")).put("uri",destination.toString()));assertTrue(result.getBoolean("verified"));assertEquals(ORIGINAL.length,result.getLong("bytes"));assertArrayEquals(ORIGINAL,output.toByteArray());assertEquals(file.getString("sha256"),result.getString("sha256"));
    }
    @Test public void destinationOpenMutationCannotSubstituteBytesFromASecondCanonicalRead()throws Exception {
        Context context=context();AttachmentRepository repository=AttachmentRepository.get(context);JSONObject file=original(repository);String id=file.getString("id");Uri destination=Uri.parse("content://legacy-export/selected");ByteArrayOutputStream output=new ByteArrayOutputStream();
        Shadows.shadowOf(context.getContentResolver()).registerOutputStreamSupplier(destination,()->{try{Files.write(blob(context,id).toPath(),REPLACED);}catch(IOException error){throw new RuntimeException(error);}return output;});
        Exception changed=assertThrows(Exception.class,()->repository.exportToUri(id,destination));assertTrue(changed.getMessage().contains("部分内容"));assertArrayEquals(ORIGINAL,output.toByteArray());assertArrayEquals(REPLACED,Files.readAllBytes(blob(context,id).toPath()));
    }
    @Test public void exportFailureWarnsAboutPartialDestinationAndNeverChangesTheOriginal()throws Exception {
        Context context=context();AttachmentRepository repository=AttachmentRepository.get(context);JSONObject file=original(repository);String id=file.getString("id");Uri destination=Uri.parse("content://legacy-export/selected");ByteArrayOutputStream prefix=new ByteArrayOutputStream();
        Shadows.shadowOf(context.getContentResolver()).registerOutputStream(destination,new java.io.OutputStream(){@Override public void write(int value)throws IOException{throw new IOException("disconnected");}@Override public void write(byte[] bytes,int offset,int length)throws IOException{prefix.write(bytes,offset,3);throw new IOException("disconnected");}});
        Exception failure=assertThrows(Exception.class,()->repository.exportToUri(id,destination));assertTrue(failure.getMessage().contains("部分内容"));assertArrayEquals("ori".getBytes(StandardCharsets.UTF_8),prefix.toByteArray());assertArrayEquals(ORIGINAL,Files.readAllBytes(blob(context,id).toPath()));
    }
    @Test public void wrongExpectedDigestCannotOpenAnExportOrPrepareAReader()throws Exception {
        Context context=context();AttachmentRepository repository=AttachmentRepository.get(context);JSONObject file=original(repository);String id=file.getString("id");Uri destination=Uri.parse("content://legacy-export/selected");AtomicInteger opened=new AtomicInteger();Shadows.shadowOf(context.getContentResolver()).registerOutputStreamSupplier(destination,()->{opened.incrementAndGet();return new ByteArrayOutputStream();});
        assertThrows(Exception.class,()->repository.exportVerifiedToUri(id,"0".repeat(64),destination,new RunControl()));assertThrows(Exception.class,()->repository.verifiedViewIntent(id,"0".repeat(64),new RunControl()));assertEquals(0,opened.get());assertEquals(0,viewFiles(context));
    }
    @Test public void legacyViewAndShareGrantPreparedBytesRatherThanTheLaterCanonicalPath()throws Exception {
        Context context=context();AttachmentRepository repository=AttachmentRepository.get(context);JSONObject file=original(repository);String id=file.getString("id");Intent view=repository.viewIntent(id),share=repository.shareIntent(id);Uri shared=share.getParcelableExtra(Intent.EXTRA_STREAM);assertNotEquals(repository.uri(id),view.getData());assertNotEquals(repository.uri(id),shared);assertEquals("view",view.getData().getPathSegments().get(0));assertEquals("view",shared.getPathSegments().get(0));assertEquals(shared,share.getClipData().getItemAt(0).getUri());
        for(Intent intent:new Intent[]{view,share}){assertNotEquals(0,intent.getFlags()&Intent.FLAG_GRANT_READ_URI_PERMISSION);assertEquals(0,intent.getFlags()&Intent.FLAG_GRANT_WRITE_URI_PERMISSION);}
        Files.write(blob(context,id).toPath(),REPLACED);AttachmentProvider provider=provider(context);assertArrayEquals(ORIGINAL,read(provider,view.getData()));assertArrayEquals(ORIGINAL,read(provider,shared));int before=viewFiles(context);assertThrows(Exception.class,()->repository.viewIntent(id));assertThrows(Exception.class,()->repository.shareIntent(id));assertEquals(before,viewFiles(context));
    }
    @Test public void canonicalProviderVerifiesAtOpenAndItsDescriptorCannotFollowLaterCanonicalWrites()throws Exception {
        Context context=context();AttachmentRepository repository=AttachmentRepository.get(context);JSONObject file=original(repository);String id=file.getString("id");AttachmentProvider provider=provider(context);Uri canonical=repository.uri(id);
        try(var input=new ParcelFileDescriptor.AutoCloseInputStream(provider.openFile(canonical,"r"))){Files.write(blob(context,id).toPath(),REPLACED);assertArrayEquals(ORIGINAL,input.readAllBytes());}
        assertThrows(java.io.FileNotFoundException.class,()->provider.openFile(canonical,"r"));assertEquals(0,viewFiles(context));assertEquals(file.getString("sha256"),repository.book.get(id).getString("sha256"));
    }
    @Test public void preparedSnapshotProviderDetachesTheActuallyVerifiedBytesFromItsCachePath()throws Exception {
        Context context=context();AttachmentRepository repository=AttachmentRepository.get(context);JSONObject file=original(repository);Intent prepared=repository.verifiedViewIntent(file.getString("id"),file.getString("sha256"),new RunControl());Uri uri=prepared.getData();File cache=new File(new File(context.getCacheDir(),"outcome-views"),uri.getLastPathSegment()+".blob");AttachmentProvider provider=provider(context);
        try(var input=new ParcelFileDescriptor.AutoCloseInputStream(provider.openFile(uri,"r"))){Files.write(cache.toPath(),REPLACED);assertArrayEquals(ORIGINAL,input.readAllBytes());}
        assertThrows(java.io.FileNotFoundException.class,()->provider.openFile(uri,"r"));assertArrayEquals(ORIGINAL,Files.readAllBytes(blob(context,file.getString("id")).toPath()));assertEquals(2,viewFiles(context));
    }
    @Test public void repeatedCanonicalReadsReleaseOnlyTheirOwnUnissuedCopy()throws Exception {
        Context context=context();AttachmentRepository repository=AttachmentRepository.get(context);JSONObject file=original(repository);String id=file.getString("id");Intent granted=repository.verifiedViewIntent(id,file.getString("sha256"),new RunControl());granted.putExtra(AttachmentOutcomeHost.NATIVE_VIEW_ISSUED,true);AttachmentProvider provider=provider(context);int before=viewFiles(context);
        for(int i=0;i<6;i++){assertArrayEquals(ORIGINAL,read(provider,repository.uri(id)));assertEquals(before,viewFiles(context));assertArrayEquals(ORIGINAL,read(provider,granted.getData()));}
        assertEquals(2,before);assertThrows(SecurityException.class,()->new AttachmentViewSnapshots(context).discardUnissuedView(granted));
    }
    @Test public void moduleScopeChecksAndProviderReadOnlyUriBoundariesRemainInForce()throws Exception {
        Context context=context();AttachmentRepository repository=AttachmentRepository.get(context);JSONObject file=original(repository);String id=file.getString("id");AttachmentsModule module=module(context);module.setNativeRecordRead(record->{throw new SecurityException("original owner source revoked");});
        for(String command:new String[]{"readText","exportToUri","view","share"})assertThrows(SecurityException.class,()->module.handle(command,new JSONObject().put("id",id).put("uri","content://legacy-export/selected")));assertEquals(0,launches.size());assertEquals(0,viewFiles(context));
        AttachmentProvider provider=provider(context);Uri canonical=repository.uri(id);assertThrows(java.io.FileNotFoundException.class,()->provider.openFile(canonical,"rw"));for(Uri invalid:new Uri[]{Uri.parse("content://another.app.attachments/"+id),Uri.parse(canonical+"?sha=other"),Uri.parse(canonical+"#fragment"),Uri.parse(canonical+"/other")})assertThrows(java.io.FileNotFoundException.class,()->provider.openFile(invalid,"r"));assertThrows(UnsupportedOperationException.class,()->provider.delete(canonical,null,null));assertArrayEquals(ORIGINAL,Files.readAllBytes(blob(context,id).toPath()));
    }
    @Test public void modulePinsTheDigestOfTheRecordThatPassedItsOriginalOwnerGate()throws Exception {
        Context context=context();AttachmentRepository repository=AttachmentRepository.get(context);JSONObject file=original(repository);String id=file.getString("id");File record=new File(new File(context.getFilesDir(),"attachments"),id+".json");String originalRecord=Files.readString(record.toPath());AttachmentsModule module=module(context);Uri destination=Uri.parse("content://legacy-export/selected");AtomicInteger opened=new AtomicInteger();Shadows.shadowOf(context.getContentResolver()).registerOutputStreamSupplier(destination,()->{opened.incrementAndGet();return new ByteArrayOutputStream();});
        module.setNativeRecordRead(selected->{Files.write(blob(context,id).toPath(),REPLACED);StringBuilder hash=new StringBuilder();for(byte value:java.security.MessageDigest.getInstance("SHA-256").digest(REPLACED))hash.append(String.format(java.util.Locale.ROOT,"%02x",value&255));Files.writeString(record.toPath(),new JSONObject(originalRecord).put("sha256",hash.toString()).toString());});
        for(String command:new String[]{"readText","exportToUri","view","share"}){Files.write(blob(context,id).toPath(),ORIGINAL);Files.writeString(record.toPath(),originalRecord);assertThrows(Exception.class,()->module.handle(command,new JSONObject().put("id",id).put("uri",destination.toString())));}
        assertEquals(0,opened.get());assertEquals(0,launches.size());assertEquals(0,viewFiles(context));
    }
    @Test public void canonicalAndPreparedReadersRemainSeekableAndActuallyReadOnly()throws Exception {
        Context context=context();AttachmentRepository repository=AttachmentRepository.get(context);JSONObject file=original(repository);Intent prepared=repository.verifiedViewIntent(file.getString("id"),file.getString("sha256"),new RunControl());AttachmentProvider provider=provider(context);
        for(Uri uri:new Uri[]{repository.uri(file.getString("id")),prepared.getData()}){
            try(ParcelFileDescriptor descriptor=provider.openFile(uri,"r")){
                try(var writer=new ParcelFileDescriptor.AutoCloseOutputStream(ParcelFileDescriptor.dup(descriptor.getFileDescriptor()))){assertThrows(IOException.class,()->writer.write('X'));}
                assertEquals(3,android.system.Os.lseek(descriptor.getFileDescriptor(),3,android.system.OsConstants.SEEK_SET));
                try(var input=new ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(descriptor.getFileDescriptor()))){assertArrayEquals("ginal".getBytes(StandardCharsets.UTF_8),input.readAllBytes());}
            }
        }
        assertEquals(2,viewFiles(context));assertArrayEquals(ORIGINAL,Files.readAllBytes(blob(context,file.getString("id")).toPath()));
    }
    @Test public void canonicalReaderCannotBypassTheExistingPreparedSnapshotQuota()throws Exception {
        Context context=context();AttachmentRepository repository=AttachmentRepository.get(context);JSONObject file=original(repository);ArrayList<Uri> granted=new ArrayList<>();for(int i=0;i<4;i++)granted.add(repository.verifiedViewIntent(file.getString("id"),file.getString("sha256"),new RunControl()).getData());AttachmentProvider provider=provider(context);
        assertThrows(java.io.FileNotFoundException.class,()->provider.openFile(repository.uri(file.getString("id")),"r"));assertEquals(8,viewFiles(context));for(Uri uri:granted)assertArrayEquals(ORIGINAL,read(provider,uri));assertEquals(8,viewFiles(context));
    }
    @Test public void groupAndRetiredSourcesGainNoLegacyPrivateReadOrExportPermission()throws Exception {
        Context context=context();AttachmentRepository repository=AttachmentRepository.get(context);JSONObject group=repository.book.importBytes(new ByteArrayInputStream(ORIGINAL),"group.txt","text/plain",new JSONObject().put("groupSpaceId","original-group"));JSONObject retired=original(repository);String id=retired.getString("id");File record=new File(new File(context.getFilesDir(),"attachments"),id+".json");Files.writeString(record.toPath(),new JSONObject(Files.readString(record.toPath())).put("outputSourceRetired",true).toString());AttachmentsModule module=module(context);AtomicInteger opened=new AtomicInteger();Uri destination=Uri.parse("content://legacy-export/selected");Shadows.shadowOf(context.getContentResolver()).registerOutputStreamSupplier(destination,()->{opened.incrementAndGet();return new ByteArrayOutputStream();});
        for(JSONObject file:new JSONObject[]{group,retired})for(String command:new String[]{"readText","exportToUri","view","share"})assertThrows(Exception.class,()->module.handle(command,new JSONObject().put("id",file.getString("id")).put("uri",destination.toString())));
        assertThrows(java.io.FileNotFoundException.class,()->provider(context).openFile(repository.uri(id),"r"));assertEquals(0,opened.get());assertEquals(0,launches.size());assertEquals(0,viewFiles(context));assertArrayEquals(ORIGINAL,Files.readAllBytes(blob(context,id).toPath()));
    }
    @Test public void coldReaderPartCleanupLeavesOriginalsGrantedSnapshotsAndExistingDescriptorsIntact()throws Exception {
        Context context=context();AttachmentRepository repository=AttachmentRepository.get(context);JSONObject file=original(repository);String id=file.getString("id");Intent prepared=repository.verifiedViewIntent(id,file.getString("sha256"),new RunControl());AttachmentProvider provider=provider(context);File cache=new File(context.getCacheDir(),"outcome-views");
        try(var existing=new ParcelFileDescriptor.AutoCloseInputStream(provider.openFile(prepared.getData(),"r"))){
            for(int i=0;i<20;i++)Files.write(new File(cache,"reader-"+java.util.UUID.randomUUID()+".part").toPath(),REPLACED);
            File unrelated=new File(cache,"reader-not-a-native-token.part");Files.write(unrelated.toPath(),ORIGINAL);File linked=new File(cache,"reader-"+java.util.UUID.randomUUID()+".part");Files.createSymbolicLink(linked.toPath(),blob(context,id).toPath());
            assertArrayEquals(ORIGINAL,read(provider,prepared.getData()));assertTrue(viewFiles(context)>2);assertArrayEquals(ORIGINAL,read(provider,prepared.getData()));assertEquals(3,viewFiles(context));assertTrue(unrelated.isFile());assertFalse(Files.exists(linked.toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS));assertArrayEquals(ORIGINAL,existing.readAllBytes());
        }
        assertArrayEquals(ORIGINAL,Files.readAllBytes(blob(context,id).toPath()));assertArrayEquals(ORIGINAL,read(provider,prepared.getData()));
    }
    @Test public void aNonFileReaderPartFailsClosedWithoutCreatingOrDeletingOtherCopies()throws Exception {
        Context context=context();AttachmentRepository repository=AttachmentRepository.get(context);JSONObject file=original(repository);Intent prepared=repository.verifiedViewIntent(file.getString("id"),file.getString("sha256"),new RunControl());File cache=new File(context.getCacheDir(),"outcome-views"),foreign=new File(cache,"reader-"+java.util.UUID.randomUUID()+".part");assertTrue(foreign.mkdir());AttachmentProvider provider=provider(context);
        assertThrows(java.io.FileNotFoundException.class,()->provider.openFile(prepared.getData(),"r"));assertTrue(foreign.isDirectory());assertEquals(3,viewFiles(context));assertArrayEquals(ORIGINAL,Files.readAllBytes(blob(context,file.getString("id")).toPath()));
    }
}
