package dev.pocketengine.features.assistant;

import static org.junit.Assert.*;
import android.app.Application;
import android.util.AtomicFile;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

/** Actual Android AtomicFile under SDK35; no TaskStore/Assistant replacement or pretend ACK source. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public final class OwnerComposerStoreTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    private static <T>T await(CompletableFuture<T> value)throws Exception{return value.get(5,TimeUnit.SECONDS);}
    private static void text(OwnerComposerState state,String text)throws Exception{var d=state.active();state.edit(d.key,d.revision,text,d.mode,d.attachments());}
    @Test public void beforeSendIdentitySurvivesRecreatedNativeStoreAndAckClearsOnlyThatDraft()throws Exception{
        File root=temporary.newFolder();var store=new OwnerComposerStore(root);var state=await(store.load());text(state,"原消息🧭");var d=state.active();String client=UUID.randomUUID().toString();state.prepare(client,d.key,d.revision,"a".repeat(64),null);await(store.save(state));
        var reopened=new OwnerComposerStore(root);var recovered=await(reopened.load());assertEquals(client,recovered.pending().get(0).clientId);assertEquals("原消息🧭",recovered.active().text);assertTrue(recovered.pending().get(0).originalTarget().length()==0);
        // The real query result is a separate input to the model; reading the file never dispatches.
        recovered.acknowledge(client,UUID.randomUUID().toString());await(reopened.save(recovered));var finalState=await(new OwnerComposerStore(root).load());assertTrue(finalState.pending().isEmpty());assertEquals("",finalState.active().text);
    }
    @Test public void queuedDiskSaveOutlivesUiOwnerAndNewReaderSeesOriginalId()throws Exception{
        File root=temporary.newFolder();var old=new OwnerComposerStore(root);var state=await(old.load());text(state,"离开后只查询");var d=state.active();var p=state.prepare(UUID.randomUUID().toString(),d.key,d.revision,"b".repeat(64),null);
        CompletableFuture<Void> saving=old.save(state);CompletableFuture<OwnerComposerState> opening=new OwnerComposerStore(root).load();await(saving);assertEquals(p.clientId,await(opening).pending().get(0).clientId);
    }
    @Test public void staleOtherWindowFailsCasAndCannotOverwriteNewerNativeFile()throws Exception{
        File root=temporary.newFolder();var first=new OwnerComposerStore(root);var a=await(first.load());text(a,"已保存A");await(first.save(a));var second=new OwnerComposerStore(root);var b=await(second.load());text(a,"A的新版本");await(first.save(a));text(b,"迟到的旧窗口");assertThrows(java.util.concurrent.ExecutionException.class,()->await(second.save(b)));assertEquals("A的新版本",await(new OwnerComposerStore(root).load()).active().text);assertEquals("迟到的旧窗口",b.active().text);
    }
    @Test public void byteLimitRejectsNewSnapshotAndPreservesOriginalNativeFile()throws Exception{
        File root=temporary.newFolder();var store=new OwnerComposerStore(root);var state=await(store.load());text(state,"原文件必须保留");await(store.save(state));byte[] before=Files.readAllBytes(new File(root,"owner-composer-v1.json").toPath());
        for(int i=0;i<10;i++){state.selectConversation(UUID.randomUUID().toString());text(state,"汉".repeat(48_000));}
        assertThrows(java.util.concurrent.ExecutionException.class,()->await(store.save(state)));assertArrayEquals(before,Files.readAllBytes(new File(root,"owner-composer-v1.json").toPath()));assertEquals("原文件必须保留",await(new OwnerComposerStore(root).load()).active().text);
    }
    @Test public void corruptAndOversizedFilesDoNotResetIntoAnEmptyWritableStore()throws Exception{
        File root=temporary.newFolder();File file=new File(root,"owner-composer-v1.json");byte[] broken="{malformed original".getBytes(StandardCharsets.UTF_8);Files.write(file.toPath(),broken);var store=new OwnerComposerStore(root);assertThrows(java.util.concurrent.ExecutionException.class,()->await(store.load()));assertThrows(java.util.concurrent.ExecutionException.class,()->await(store.save(OwnerComposerState.empty())));assertArrayEquals(broken,Files.readAllBytes(file.toPath()));
        byte[] huge=new byte[OwnerComposerState.MAX_STATE_BYTES+1];java.util.Arrays.fill(huge,(byte)'x');Files.write(file.toPath(),huge);assertThrows(java.util.concurrent.ExecutionException.class,()->await(new OwnerComposerStore(root).load()));assertArrayEquals(huge,Files.readAllBytes(file.toPath()));
    }
    @Test public void interruptedAtomicWriteRecoversPreviousDraftNotPartialReplacement()throws Exception{
        File root=temporary.newFolder();var store=new OwnerComposerStore(root);var state=await(store.load());text(state,"原原子版本");await(store.save(state));var nativeFile=new AtomicFile(new File(root,"owner-composer-v1.json"));var partial=nativeFile.startWrite();partial.write("{partial".getBytes(StandardCharsets.UTF_8));partial.close();assertEquals("原原子版本",await(new OwnerComposerStore(root).load()).active().text);
    }
    @Test public void unknownSchemaAndTrailingDataFailWithoutChangingBytes()throws Exception{
        File root=temporary.newFolder();File file=new File(root,"owner-composer-v1.json");JSONObject json=OwnerComposerState.empty().toJson().put("format","future-schema");Files.writeString(file.toPath(),json.toString());assertThrows(java.util.concurrent.ExecutionException.class,()->await(new OwnerComposerStore(root).load()));assertEquals(json.toString(),Files.readString(file.toPath()));String trailing=OwnerComposerState.empty().toJson()+" another";Files.writeString(file.toPath(),trailing);assertThrows(java.util.concurrent.ExecutionException.class,()->await(new OwnerComposerStore(root).load()));assertEquals(trailing,Files.readString(file.toPath()));
    }
    @Test public void uninitializedSaveCannotCreateOrClobberFile()throws Exception{
        File root=temporary.newFolder();assertThrows(java.util.concurrent.ExecutionException.class,()->await(new OwnerComposerStore(root).save(OwnerComposerState.empty())));assertFalse(new File(root,"owner-composer-v1.json").exists());
    }
    @Test public void silentAtomicFinishFailureCannotAcknowledgePendingAsDurable()throws Exception{
        File root=temporary.newFolder();File target=new File(root,"owner-composer-v1.json");var initial=new OwnerComposerStore(root);var old=await(initial.load());text(old,"原已保存文字");await(initial.save(old));byte[] before=Files.readAllBytes(target.toPath());
        var broken=new OwnerComposerStore(new AtomicFile(target){@Override public void finishWrite(java.io.FileOutputStream stream){try{stream.close();}catch(java.io.IOException failed){throw new IllegalStateException(failed);}/* Deliberately do not publish the new native file. */}});var state=await(broken.load());text(state,"本次尚未发送");var d=state.active();state.prepare(UUID.randomUUID().toString(),d.key,d.revision,"c".repeat(64),null);assertThrows(java.util.concurrent.ExecutionException.class,()->await(broken.save(state)));assertArrayEquals(before,Files.readAllBytes(target.toPath()));assertEquals("原已保存文字",await(new OwnerComposerStore(root).load()).active().text);assertThrows(java.util.concurrent.ExecutionException.class,()->await(broken.save(state)));
    }
    @Test public void explicitInProcessUnsentRetrySavesTheSameIdentityAfterDiskRecovery()throws Exception{
        File root=temporary.newFolder();File target=new File(root,"owner-composer-v1.json");var fail=new java.util.concurrent.atomic.AtomicBoolean(true);
        var store=new OwnerComposerStore(new AtomicFile(target){@Override public java.io.FileOutputStream startWrite()throws java.io.IOException{if(fail.get())throw new java.io.IOException("controlled local disk failure before send");return super.startWrite();}});
        var state=await(store.load());text(state,"本进程确定未派发");var d=state.active();var original=state.prepare(UUID.randomUUID().toString(),d.key,d.revision,"d".repeat(64),null);
        assertThrows(java.util.concurrent.ExecutionException.class,()->await(store.save(state)));fail.set(false);await(store.saveAfterLocalUnsentFailure(state));var restored=await(new OwnerComposerStore(root).load());assertEquals(original.clientId,restored.pending().get(0).clientId);assertEquals(original.fingerprint,restored.pending().get(0).fingerprint);assertEquals(1,restored.pending().size());assertFalse(restored.toJson().has("retryPermission"));
    }
    @Test public void explicitLocalUnsentRetryCannotAdoptAnotherWritersChangedSnapshot()throws Exception{
        File root=temporary.newFolder();File target=new File(root,"owner-composer-v1.json");var initial=new OwnerComposerStore(root);var base=await(initial.load());text(base,"原已保存草稿");await(initial.save(base));var fail=new java.util.concurrent.atomic.AtomicBoolean(true);
        var store=new OwnerComposerStore(new AtomicFile(target){@Override public java.io.FileOutputStream startWrite()throws java.io.IOException{if(fail.get())throw new java.io.IOException("controlled local predispatch failure");return super.startWrite();}});var local=await(store.load());text(local,"原身份尚未发出");var d=local.active();local.prepare(UUID.randomUUID().toString(),d.key,d.revision,"e".repeat(64),null);assertThrows(java.util.concurrent.ExecutionException.class,()->await(store.save(local)));
        var other=new OwnerComposerStore(root);var newer=await(other.load());text(newer,"另一窗口已保存，不能用重试覆盖");await(other.save(newer));byte[] before=Files.readAllBytes(target.toPath());fail.set(false);assertThrows(java.util.concurrent.ExecutionException.class,()->await(store.saveAfterLocalUnsentFailure(local)));assertArrayEquals(before,Files.readAllBytes(target.toPath()));assertEquals("原身份尚未发出",local.active().text);
    }
}
