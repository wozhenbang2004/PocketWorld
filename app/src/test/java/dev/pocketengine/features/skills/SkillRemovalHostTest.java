package dev.pocketengine.features.skills;

import android.app.Application;
import android.content.Context;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.store.SecretVault;
import dev.pocketengine.store.TaskStore;
import java.io.File;
import java.lang.reflect.Modifier;
import java.util.UUID;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** Real original registry, native module and SQLite journal; no platform substitutes. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class SkillRemovalHostTest {
    private static JSONObject scope()throws Exception{return new JSONObject().put("ownerId","owner").put("spaceId","private");}
    private static final class Fixture {
        final Context android=RuntimeEnvironment.getApplication();
        final TaskStore tasks=new TaskStore(android);
        final SkillBook book=new SkillBook(new File(android.getFilesDir(),"skills"));
        final SkillsModule module=new SkillsModule(new FeatureContext(android,null,new SecretVault(android),event->{}),book,new SkillGitClient());
        final JSONObject row=book.install(scope(),new JSONObject().put("source","local:owner").put("manifest","---\nname: remove-me\ndescription: Original registry test\n---\nReference only."),1000);
        Fixture()throws Exception{}
        JSONObject current()throws Exception{return book.get(scope(),row.getString("id"),2000);}
        String removal(JSONObject expected)throws Exception{String id=UUID.randomUUID().toString();tasks.beginExternal("skills","removeReviewed",id,new JSONObject());tasks.status(id,"running","");return id;}
        String operation(String task,JSONObject expected)throws Exception{return tasks.beginCapabilityOperation(task,task,"skills","removeReviewed",new JSONObject().put("expected",expected));}
    }
    @Test public void nativeReviewDeletesOnceAndKeepsOriginalTaskReceipt()throws Exception{
        Fixture f=new Fixture();assertThrows(SecurityException.class,()->f.module.handle("delete",new JSONObject().put("id",f.row.getString("id"))));assertNotNull(f.current());
        var review=f.module.reviewRemovalForOwner(f.tasks,f.current());String action=review.actionId();JSONObject receipt=review.remove(new RunControl());assertTrue(receipt.getBoolean("deleted"));assertFalse(receipt.getBoolean("replayAllowed"));assertEquals(action,receipt.getString("taskId"));
        assertThrows(IllegalArgumentException.class,f::current);JSONObject original=f.tasks.get(action,false);assertEquals("completed",original.getString("status"));assertEquals("skills_removeReviewed",original.getJSONArray("operations").getJSONObject(0).getString("tool"));assertEquals("receipt",original.getJSONArray("operations").getJSONObject(0).getString("state"));assertThrows(SecurityException.class,()->review.remove(new RunControl()));
        for(var constructor:TaskStore.SkillRemovalGuard.class.getDeclaredConstructors())assertTrue(Modifier.isPrivate(constructor.getModifiers()));
    }
    @Test public void sameTickEnableDisableMakesNativeReviewStaleWithoutDeletingContent()throws Exception{
        Fixture f=new Fixture();var review=f.module.reviewRemovalForOwner(f.tasks,f.current());f.book.setEnabled(scope(),f.row.getString("id"),true,f.row.getString("digest"),true,2000);f.book.setEnabled(scope(),f.row.getString("id"),false,"",false,2000);
        JSONObject rejected=review.remove(new RunControl());assertEquals("rejected",rejected.getString("status"));assertNotNull(f.current());assertTrue(new File(f.android.getFilesDir(),"skills/"+f.row.getString("id")).isDirectory());
    }
    @Test public void wrongBookCasAndCancelledControlDoNotConsumeGuardOrErasePackage()throws Exception{
        Fixture f=new Fixture();JSONObject expected=SkillRemovalPolicy.pin(f.current());String task=f.removal(expected),operation=f.operation(task,expected);RunControl original=new RunControl();var guard=f.tasks.skillRemovalGuardForPreparedOperation(task,operation,original);guard.checkCurrentOutsideBook(f.current());
        assertThrows(SecurityException.class,()->f.book.deletePackageReviewed(scope(),f.row.getString("id"),2,f.row.getString("digest"),0,guard));assertFalse(guard.entered());assertNotNull(f.current());
        original.pause();original.resume();assertThrows(InterruptedException.class,()->f.book.deletePackageReviewed(scope(),f.row.getString("id"),1,f.row.getString("digest"),0,guard));assertFalse(guard.entered());assertNotNull(f.current());
    }
    @Test public void unrelatedActiveRunRefusesDeletion()throws Exception{
        Fixture f=new Fixture();f.tasks.startAttempt(f.tasks.createCapabilityTask("original running task","").getString("id"),"capabilities",20);assertThrows(SecurityException.class,()->f.tasks.checkSkillRemovalIdleForOwner(f.row.getString("id")));
    }
    @Test public void unknownSkillEffectRefusesDeletionEvenWithoutAnActiveRun()throws Exception{
        Fixture f=new Fixture();String task=UUID.randomUUID().toString();f.tasks.beginExternal("skills","execute",task,new JSONObject());f.tasks.status(task,"running","");String operation=f.tasks.beginCapabilityOperation(task,task,"skills","execute",new JSONObject().put("id",f.row.getString("id")));f.tasks.finishCapabilityOperation(task,operation,new JSONObject().put("status","unknown").put("accepted",JSONObject.NULL));
        assertThrows(SecurityException.class,()->f.tasks.checkSkillRemovalIdleForOwner(f.row.getString("id")));assertEquals("unknown",f.tasks.get(task,false).getJSONArray("operations").getJSONObject(0).getString("state"));
    }
    @Test public void preparedOtherCommandCannotMintPermitAndTaskLockCannotSpanBookEntry()throws Exception{
        Fixture f=new Fixture();JSONObject expected=SkillRemovalPolicy.pin(f.current());String task=f.removal(expected),wrong=f.tasks.beginCapabilityOperation(task,task,"skills","import",new JSONObject().put("expected",expected));assertThrows(SecurityException.class,()->f.tasks.skillRemovalGuardForPreparedOperation(task,wrong,new RunControl()));
        f.tasks.finishCapabilityOperation(task,wrong,new JSONObject().put("status","rejected"));String operation=f.operation(task,expected);var guard=f.tasks.skillRemovalGuardForPreparedOperation(task,operation,new RunControl());
        synchronized(f.tasks){assertThrows(SecurityException.class,()->f.module.deletePackageReviewed(scope(),f.row.getString("id"),1,f.row.getString("digest"),0,guard));}assertFalse(guard.entered());assertNotNull(f.current());
        guard.checkCurrentOutsideBook(f.current());assertTrue(f.book.deletePackageReviewed(scope(),f.row.getString("id"),1,f.row.getString("digest"),0,guard).getBoolean("deleted"));assertTrue(guard.entered());assertThrows(SecurityException.class,()->guard.enterBookDeletion(f.row));
    }
}
