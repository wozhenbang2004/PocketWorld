package dev.pocketengine.ui.web

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PocketWebGroupsTest {
    private var time = 1_000_000L
    private fun group() = JSONObject().put("id", "group-a").put("spaceId", "group:group-a").put("revision", 5L).put("policyEpoch", "policy-a")
    private fun connection() = JSONObject().put("id", "qq-a").put("provider", "qq").put("enabled", true).put("qqGuildEnabled", false)
    private fun snapshot() = JSONObject().put("groups", JSONArray().put(group())).put("requests", JSONArray()).put("deliveries", JSONArray())
        .put("nativeConnections", JSONArray().put(connection())).put("nativeResources", JSONObject().put("public.answer", true).put("group.files", true).put("media.image", true).put("mediaResource", "media:original"))
    private fun quota() = JSONObject().put("dailyTasks", 20).put("memberDailyTasks", 5).put("dailyTokens", 40000).put("maxTokensPerTask", 4000)
        .put("maxConcurrent", 2).put("dailyMedia", 4).put("memberDailyMedia", 1).put("maxMediaBytes", 20971520)
    private fun policy() = group().apply { remove("spaceId") }.put("enabled", true).put("visibility", "requester").put("quota", quota())
        .put("grants", JSONArray().put(JSONObject().put("capability", "group.files").put("resource", "group:group-a").put("allowGroupAudience", false).put("hours", 24)))
    private fun member() = group().apply { remove("spaceId") }.put("senderId", "sender-a").put("displayName", "成员甲").put("role", "member").put("enabled", true)
    private fun create() = JSONObject().put("name", "原群").put("accountId", "qq-a").put("roomId", "room-a").put("conversationKind", "group")

    @Test fun aReviewIsBoundToWindowExpiryAndSingleConsumption() {
        val reviews = PocketWebGroupReviews { time }; val snapshot = snapshot()
        var handle = reviews.member(member(), snapshot, "owner-window-a").getString("reviewHandle")
        assertThrows(IllegalStateException::class.java) { reviews.take(handle, "owner-window-b", snapshot) }
        assertThrows(IllegalStateException::class.java) { reviews.take(handle, "owner-window-a", snapshot) }
        handle = reviews.member(member(), snapshot, "owner-window-a").getString("reviewHandle"); time += 120_000
        assertThrows(IllegalStateException::class.java) { reviews.take(handle, "owner-window-a", snapshot) }
        handle = reviews.member(member(), snapshot, "owner-window-a").getString("reviewHandle")
        assertEquals("member.set", reviews.take(handle, "owner-window-a", snapshot).command)
        assertThrows(IllegalStateException::class.java) { reviews.take(handle, "owner-window-a", snapshot) }
    }
    @Test fun groupMemberAndPolicyChangesInvalidateTheFrozenReview() {
        val reviews = PocketWebGroupReviews { time }; val snapshot = snapshot()
        val handle = reviews.member(member(), snapshot, "window").getString("reviewHandle")
        snapshot.getJSONArray("groups").getJSONObject(0).put("revision", 6L)
        assertThrows(IllegalStateException::class.java) { reviews.take(handle, "window", snapshot) }
        assertThrows(IllegalStateException::class.java) { reviews.member(member(), snapshot, "window") }
        snapshot.getJSONArray("groups").getJSONObject(0).put("revision", 5L).put("policyEpoch", "replaced")
        assertThrows(IllegalStateException::class.java) { reviews.policy(policy(), snapshot, "window") }
    }
    @Test fun creatingAGuildRequiresTheActualEnabledOriginalConnection() {
        val reviews = PocketWebGroupReviews { time }; val snapshot = snapshot()
        assertEquals("qq", reviews.create(create(), snapshot, "window").getJSONObject("input").getString("provider"))
        val guild = create().put("conversationKind", "qq_guild_channel").put("conversationParentId", "guild-a")
        assertThrows(IllegalArgumentException::class.java) { reviews.create(guild, snapshot, "window") }
        snapshot.getJSONArray("nativeConnections").getJSONObject(0).put("qqGuildEnabled", true)
        val handle = reviews.create(guild, snapshot, "window").getString("reviewHandle")
        snapshot.getJSONArray("nativeConnections").getJSONObject(0).put("enabled", false)
        assertThrows(IllegalStateException::class.java) { reviews.take(handle, "window", snapshot) }
        assertThrows(IllegalArgumentException::class.java) { reviews.create(create().put("provider", "webhook"), snapshot, "window") }
    }
    @Test fun policyCannotUsePrivateOrAnotherGroupResourceOrImplicitMediaMigration() {
        val reviews = PocketWebGroupReviews { time }
        for (resource in listOf("private", "group:other")) {
            val input = policy(); input.getJSONArray("grants").getJSONObject(0).put("resource", resource)
            assertThrows(IllegalArgumentException::class.java) { reviews.policy(input, snapshot(), "window") }
        }
        val input = policy(); input.getJSONObject("quota").put("maxTokensPerTask", 32000)
        input.getJSONArray("grants").getJSONObject(0).put("capability", "media.image").put("resource", "media:original")
        val current = snapshot(); val handle = reviews.policy(input, current, "window").getString("reviewHandle")
        current.getJSONObject("nativeResources").put("mediaResource", "media:replacement")
        assertThrows(IllegalArgumentException::class.java) { reviews.take(handle, "window", current) }
        // Revocation remains possible even when an old media service is no longer configured.
        input.put("enabled", false)
        assertNotNull(reviews.policy(input, current, "window"))
    }
    @Test fun strictFieldsAndNumbersCannotExpandTheOriginalOwnerContract() {
        val reviews = PocketWebGroupReviews { time }
        assertThrows(IllegalArgumentException::class.java) { reviews.member(member().put("actorId", "owner"), snapshot(), "window") }
        assertThrows(IllegalArgumentException::class.java) { reviews.member(member().put("enabled", "true"), snapshot(), "window") }
        val input = policy(); input.getJSONObject("quota").put("dailyTasks", "20")
        assertThrows(IllegalArgumentException::class.java) { reviews.policy(input, snapshot(), "window") }
        val tooLarge = policy(); tooLarge.getJSONObject("quota").put("memberDailyTasks", 21)
        assertThrows(IllegalArgumentException::class.java) { reviews.policy(tooLarge, snapshot(), "window") }
        val unavailable = snapshot(); unavailable.getJSONObject("nativeResources").put("group.files", false)
        assertThrows(IllegalArgumentException::class.java) { reviews.policy(policy(), unavailable, "window") }
    }
}
