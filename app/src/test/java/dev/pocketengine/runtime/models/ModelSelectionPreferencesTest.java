package dev.pocketengine.runtime.models;

import static org.junit.Assert.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class ModelSelectionPreferencesTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    private Path file(){return temp.getRoot().toPath().resolve("model-preferences.json");}
    private static ModelSelectionPreferences.Key key(String connection,String model){return new ModelSelectionPreferences.Key(connection,model);}
    @Test public void sameModelOnDifferentConnectionsRemainsDistinctAndFavoriteDoesNotMarkUse()throws Exception{
        var prefs=new ModelSelectionPreferences(file());ModelSelectionPreferences.Key first=key("a","shared-model"),second=key("b","shared-model");
        prefs.favorites(List.of(first,first,second),true);assertEquals(2,prefs.entries().size());assertEquals(0,prefs.get(first).lastUsedAt());
        prefs.favorites(List.of(first),false);assertFalse(prefs.get(first).favorite());assertTrue(prefs.get(second).favorite());
        var reopened=new ModelSelectionPreferences(file());assertTrue(reopened.get(second).favorite());assertEquals(0,reopened.get(second).lastUsedAt());assertEquals(1,reopened.entries().size());
    }
    @Test public void idempotentBatchDoesNotRewriteAndSuccessfulUseIsMonotonic()throws Exception{
        var prefs=new ModelSelectionPreferences(file());var first=key("a","one");prefs.favorites(List.of(first),true);byte[] saved=Files.readAllBytes(file());
        prefs.favorites(List.of(first,first),true);assertArrayEquals(saved,Files.readAllBytes(file()));assertEquals(0,prefs.get(first).lastUsedAt());
        prefs.used(first,3000);prefs.used(first,1000);prefs.favorites(List.of(first),false);assertEquals(3000,prefs.get(first).lastUsedAt());
        assertEquals(3000,new ModelSelectionPreferences(file()).get(first).lastUsedAt());
    }
    @Test public void favoritesPrecedeRecentThenStableIdentity()throws Exception{
        var prefs=new ModelSelectionPreferences(file());ModelSelectionPreferences.Key favorite=key("c","z"),recent=key("b","new"),old=key("a","old"),lower=key("c","a"),upper=key("c","A");
        prefs.favorites(List.of(favorite),true);prefs.used(old,1000);prefs.used(recent,2000);
        assertEquals(List.of(favorite,recent,old,upper,lower),prefs.sort(List.of(lower,old,upper,recent,favorite)));
    }
    @Test public void exceedingFavoriteBoundIsAtomicAndCannotEvictFavorites()throws Exception{
        var prefs=new ModelSelectionPreferences(file());var keys=new ArrayList<ModelSelectionPreferences.Key>();for(int i=0;i<512;i++)keys.add(key("a","m-"+i));prefs.favorites(keys,true);byte[] before=Files.readAllBytes(file());
        assertThrows(IllegalStateException.class,()->prefs.favorites(List.of(key("b","one-more")),true));assertArrayEquals(before,Files.readAllBytes(file()));assertEquals(512,prefs.entries().size());
        prefs.used(key("b","recent-only"),3000);assertEquals(512,prefs.entries().size());for(var key:keys)assertTrue(prefs.get(key).favorite());
    }
    @Test public void damagedMetadataIsRetainedAndCannotMasqueradeAsASavedFavorite()throws Exception{
        Files.writeString(file(),"{broken");var prefs=new ModelSelectionPreferences(file());assertFalse(prefs.readable());assertFalse(prefs.get(key("a","m")).favorite());
        assertThrows(IllegalStateException.class,()->prefs.favorites(List.of(key("a","m")),true));assertEquals("{broken",Files.readString(file()));
    }
    @Test public void invalidPublicIdentityAndControlCharactersAreRejected(){
        assertThrows(IllegalArgumentException.class,()->key("a\r\nHeader","m"));assertThrows(IllegalArgumentException.class,()->key("a","m\n"));
    }
    @Test public void staleServiceCannotEraseAnotherInstancesFavoritesOrRecentUse()throws Exception{
        var first=new ModelSelectionPreferences(file());var second=new ModelSelectionPreferences(file());
        var a=key("a","one");var b=key("b","two");
        first.favorites(List.of(a),true);second.used(b,2000);first.used(a,1000);second.favorites(List.of(a),false);
        var reopened=new ModelSelectionPreferences(file());assertEquals(2,reopened.entries().size());
        assertFalse(reopened.get(a).favorite());assertEquals(1000,reopened.get(a).lastUsedAt());assertEquals(2000,reopened.get(b).lastUsedAt());
        first.refresh();assertEquals(reopened.entries(),first.entries());
    }
    @Test public void multipleWritersMergeTheirOwnDistinctFavorites()throws Exception{
        var start=new java.util.concurrent.CountDownLatch(1);var workers=java.util.concurrent.Executors.newFixedThreadPool(4);
        var pending=new ArrayList<java.util.concurrent.Future<?>>();
        try{
            for(int i=0;i<20;i++){var preferences=new ModelSelectionPreferences(file());var model=key("shared","model-"+i);
                pending.add(workers.submit(()->{assertTrue(start.await(5,java.util.concurrent.TimeUnit.SECONDS));preferences.favorites(List.of(model),true);return null;}));}
            start.countDown();for(var write:pending)write.get(10,java.util.concurrent.TimeUnit.SECONDS);
            var reopened=new ModelSelectionPreferences(file());assertEquals(20,reopened.entries().size());
            for(var entry:reopened.entries())assertTrue(entry.favorite());
        }finally{start.countDown();workers.shutdownNow();}
    }
    @Test public void removedOrCorruptObservedFileIsNotRecreatedFromAStaleCache()throws Exception{
        var preferences=new ModelSelectionPreferences(file());var a=key("a","one");preferences.favorites(List.of(a),true);
        Files.delete(file());assertThrows(java.io.IOException.class,()->preferences.used(a,2000));assertFalse(Files.exists(file()));
        Files.writeString(file(),"{damaged");preferences.refresh();assertFalse(preferences.readable());
        assertThrows(IllegalStateException.class,()->preferences.favorites(List.of(a),false));assertEquals("{damaged",Files.readString(file()));
    }
}
