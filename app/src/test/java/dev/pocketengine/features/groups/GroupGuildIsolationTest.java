package dev.pocketengine.features.groups;

import android.app.Application;
import dev.pocketengine.store.TaskStore;
import java.nio.file.Files;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Same raw QQ ids never cross ordinary group / guild / guild-parent native policy. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class GroupGuildIsolationTest {
    private static GroupIngressPort.Event event(String id,String parent)throws Exception {long now=System.currentTimeMillis();return new GroupIngressPort.Event(id,"qq","bot","same-room","same-member","same-message","transport-one","Public question",now,now+300000,new JSONArray(),null,parent.isEmpty()?"group":"qq_guild_channel",parent);}
    private static JSONObject configure(GroupRegistry registry,String parent,long now)throws Exception {
        JSONObject input=new JSONObject().put("name","Fixture namespace").put("provider","qq").put("accountId","bot").put("roomId","same-room");if(!parent.isEmpty())input.put("conversationKind","qq_guild_channel").put("conversationParentId",parent);
        JSONObject group=registry.create(input,now);group=registry.member(new JSONObject().put("id",group.getString("id")).put("revision",group.getLong("revision")).put("senderId","same-member").put("displayName","Same visible name").put("role","member").put("enabled",true),null,now);
        return registry.policy(new JSONObject().put("id",group.getString("id")).put("revision",group.getLong("revision")).put("enabled",true).put("visibility","members").put("quota",group.getJSONObject("quota")).put("grants",new JSONArray().put(new JSONObject().put("capability","public.answer").put("resource","public").put("expiresAt",now+3600000).put("allowGroupAudience",true))),now);
    }
    @Test public void nativeGuildRegistrationIsSeparateAndDurableSqlAcceptsSameRawIdsOnlyInItsExactNamespace()throws Exception {
        long now=System.currentTimeMillis();try(GroupRegistry registry=new GroupRegistry(Files.createTempDirectory("qq-native-guild-registry-").toFile())){
            configure(registry,"",now);GroupIngressPort.Event ordinary=event("ordinary",""),guild=event("guild","guild-one"),other=event("other","guild-two");
            GroupHostPort.Request old=registry.prepare(ordinary,"public.answer","group","members",1000,now);assertThrows(SecurityException.class,()->registry.prepare(guild,"public.answer","group","members",1000,now));
            configure(registry,"guild-one",now);GroupHostPort.Request fresh=registry.prepare(guild,"public.answer","group","members",1000,now);assertNotEquals(old.requestId,fresh.requestId);assertNotEquals(old.actorId,fresh.actorId);assertNotEquals(old.spaceId,fresh.spaceId);assertEquals("qq_guild_channel",fresh.origin().getString("conversationKind"));assertEquals("guild-one",fresh.origin().getString("conversationParentId"));
            assertThrows(SecurityException.class,()->registry.prepare(other,"public.answer","group","members",1000,now));configure(registry,"guild-two",now);GroupHostPort.Request third=registry.prepare(other,"public.answer","group","members",1000,now);assertNotEquals(fresh.requestId,third.requestId);
            TaskStore tasks=new TaskStore(RuntimeEnvironment.getApplication());GroupHostPort.Authority authority=()->{};try{
                assertTrue(tasks.registerGroupTask(old,authority).getBoolean("created"));assertTrue(tasks.registerGroupTask(fresh,authority).getBoolean("created"));assertTrue(tasks.registerGroupTask(third,authority).getBoolean("created"));assertFalse(tasks.registerGroupTask(fresh,authority).getBoolean("created"));
                JSONObject collision=new JSONObject().put("requestId",UUID.randomUUID().toString()).put("taskId",UUID.randomUUID().toString()).put("groupId",fresh.groupId).put("spaceId",fresh.spaceId).put("actorId",fresh.actorId).put("goal",fresh.goal).put("capability",fresh.capability).put("resource",fresh.resource).put("audience",fresh.audience).put("visibility",fresh.visibility).put("policyEpoch",fresh.policyEpoch).put("maxTokens",fresh.maxTokens).put("maxMediaBytes",fresh.maxMediaBytes).put("mediaItems",fresh.maxMediaItems).put("event",guild.json());
                assertThrows(SecurityException.class,()->tasks.registerGroupTask(new GroupHostPort.Request(collision),authority));
            }finally{var database=TaskStore.class.getDeclaredField("db");database.setAccessible(true);((android.database.sqlite.SQLiteDatabase)database.get(tasks)).close();}
        }
    }
}
