package dev.pocketengine.features.channels;

import java.nio.file.Files;
import java.time.Instant;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Exact retained native messages only; no account or remote history API. */
public final class QqOriginalReferenceTest {
    private static ChannelCore core()throws Exception {ChannelCore core=new ChannelCore(Files.createTempDirectory("qq-original-ref-").toFile());core.saveProfile(new JSONObject().put("id","bot").put("provider","qq").put("name","Controlled QQ").put("appId","fixture").put("enabled",true).put("qqGuildEnabled",true));return core;}
    private static JSONObject wire(String id,String channel,String guild,String text,String reference)throws Exception {JSONObject body=new JSONObject().put("id",id).put("content",text).put("channel_id",channel).put("guild_id",guild).put("author",new JSONObject().put("id","native-actor")).put("timestamp",Instant.now().toString());if(reference!=null)body.put("message_reference",new JSONObject().put("message_id",reference));return new JSONObject().put("t","AT_MESSAGE_CREATE").put("op",0).put("d",body);}
    private static JSONObject receive(ChannelCore core,JSONObject wire)throws Exception{return core.accept("bot",ChannelProtocol.canonicalQq(wire),"qq_gateway").getJSONObject("event");}
    private static JSONObject quote(JSONObject event)throws Exception {for(int n=0;n<event.getJSONObject("messageParts").getJSONArray("parts").length();n++){JSONObject part=event.getJSONObject("messageParts").getJSONArray("parts").getJSONObject(n);if(part.optString("kind").equals("quote"))return part;}throw new AssertionError("missing native quote");}
    @Test public void guildProviderMessageReferenceResolvesOnlyOriginalSameBotSceneGuildChannelAndGeneration()throws Exception {
        ChannelCore core=core();receive(core,wire("source","channel-one","guild-one","Actual original native text",null));JSONObject resolved=quote(receive(core,wire("reply-one","channel-one","guild-one","Current goal","source")));assertEquals("original_inbox",resolved.getString("availability"));assertEquals("Actual original native text",resolved.getString("text"));
        for(String[] scope:new String[][]{{"channel-two","guild-one"},{"channel-one","guild-two"}}){JSONObject missing=quote(receive(core,wire("other-"+scope[0]+scope[1],scope[0],scope[1],"Other audience","source")));assertEquals("unavailable",missing.getString("availability"));assertFalse(missing.has("text"));}
        JSONObject profile=core.profile("bot");core.saveProfile(profile);JSONObject old=quote(receive(core,wire("new-generation","channel-one","guild-one","New native generation","source")));assertEquals("unavailable",old.getString("availability"));assertFalse(old.has("text"));
    }
    @Test public void acceptedNativeReplyReferenceUsesOriginalOutboxNotUnrelatedTextAndColdReadPreservesIt()throws Exception {
        var dir=Files.createTempDirectory("qq-reply-native-reference-").toFile();ChannelCore core=new ChannelCore(dir);core.saveProfile(new JSONObject().put("id","bot").put("provider","qq").put("name","Controlled").put("appId","fixture").put("enabled",true).put("qqGuildEnabled",true));JSONObject source=receive(core,wire("inbound","channel","guild","Question",null));JSONObject queued=core.queueReply(source.getString("id"),"Actual accepted native reply");core.prepareSend(queued.getString("id"));core.qqReplyReference(queued.getString("id"),new JSONObject().put("ext_info",new JSONObject().put("ref_idx","native/index+=reference")));core.delivery(queued.getString("id"),"sent","controlled actual HTTP ACK","provider-response-id");
        core=new ChannelCore(dir);JSONObject resolved=quote(receive(core,wire("quote-bot","channel","guild","Question about that reply","provider-response-id")));assertEquals("Actual accepted native reply",resolved.getString("text"));assertEquals("original_inbox",resolved.getString("availability"));
        JSONObject body=wire("quote-index","channel","guild","Index quote",null);body.getJSONObject("d").put("ref_msg_idx","native/index+=reference");assertEquals("Actual accepted native reply",quote(receive(core,body)).getString("text"));assertEquals(1,core.snapshot().getJSONArray("outbox").length());
    }
}
