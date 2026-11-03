package dev.pocketengine.features.conversation;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;
public final class ConversationPinnedHistoryTest {
    private final long now=Instant.parse("2026-11-03T01:00:00Z").toEpochMilli();
    @Test public void pinnedOldConversationIsVisibleBeforeRecentDateGroupsWithoutChangingItsTime(){
        var pinned=new ConversationHistory.Item("old","旧会话",now-30*86400000L,1,1);var recent=new ConversationHistory.Item("new","今天对话",now,0,0);
        var groups=ConversationHistory.organize(List.of(recent,pinned),"",now,ZoneId.of("UTC"));assertEquals("置顶",groups.get(0).label);assertEquals("old",groups.get(0).items.get(0).id);assertEquals(pinned.updatedAt,groups.get(0).items.get(0).updatedAt);assertEquals("今天",groups.get(1).label);
    }
    @Test public void newerNativeMetadataRevisionWinsOverAStaleDuplicateWithALaterReplyTime(){
        var stale=new ConversationHistory.Item("same","旧标题",now+1000,0,1);var current=new ConversationHistory.Item("same","手工标题",now-86400000L,1,2);
        var groups=ConversationHistory.organize(List.of(stale,current),"手工",now,ZoneId.of("UTC"));assertEquals(1,groups.size());assertEquals("手工标题",groups.get(0).items.get(0).title);assertEquals(2,groups.get(0).items.get(0).organizationRevision);
    }
    @Test public void unpinReturnsToOriginalDateOrderingAndSearchNeverChangesIdentity(){
        var unpinned=new ConversationHistory.Item("old","持续会话",now-30*86400000L,0,2);var recent=new ConversationHistory.Item("new","持续研究",now,0,0);var groups=ConversationHistory.organize(List.of(unpinned,recent),"持续",now,ZoneId.of("UTC"));assertEquals("今天",groups.get(0).label);assertEquals("更早",groups.get(1).label);assertEquals("old",groups.get(1).items.get(0).id);assertEquals(-1,new ConversationHistory.Item("legacy","兼容原列表",now).organizationRevision);
    }
}
