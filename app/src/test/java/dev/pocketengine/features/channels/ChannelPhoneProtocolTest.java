package dev.pocketengine.features.channels;
import java.nio.file.*;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;
public final class ChannelPhoneProtocolTest {
 @Test public void claimIsDurableSingleUseAndRestartNeverRestartsUnknownPhone()throws Exception {
  Path path=Files.createTempDirectory("phone-origin-fixture");ChannelCore core=new ChannelCore(path.toFile());String profile=ChannelSecurityTest.addProfile(core,"phone","webhook");
  String bind=core.accept(profile,ChannelSecurityTest.message("bind","owner","dm",""),"trusted_relay").getJSONObject("event").getString("id");core.bindOwner(profile,bind,true);core.setOwnerTasks(profile,true,true);
  String event=core.accept(profile,ChannelSecurityTest.message("phone","owner","dm","").put("text","/phone 原目标"),"trusted_relay").getJSONObject("event").getString("id");JSONObject review=core.preparePhoneTask(event);String request=review.getString("requestId");
  assertEquals("awaiting_owner",review.getString("admission"));assertThrows(SecurityException.class,()->core.claimPhoneTask(request,"原目标",false));assertThrows(SecurityException.class,()->core.claimPhoneTask(request,"改目标",true));
  HostTaskPort.Request claimed=core.claimPhoneTask(request,"原目标",true);assertEquals("phone",claimed.origin().getString("taskKind"));assertThrows(SecurityException.class,()->core.claimPhoneTask(request,"原目标",true));
  ChannelCore restored=new ChannelCore(path.toFile());assertEquals("admission_unknown",restored.preparePhoneTask(event).getString("admission"));assertEquals(claimed.taskId,restored.preparePhoneTask(event).getString("taskId"));assertThrows(SecurityException.class,()->restored.claimPhoneTask(request,"原目标",true));
 }
 @Test public void expirationAndTransportReplacementCannotAuthorizeOldPhone()throws Exception {
  Path path=Files.createTempDirectory("phone-expiry-fixture");ChannelCore core=new ChannelCore(path.toFile());String profile=ChannelSecurityTest.addProfile(core,"phone","webhook");String bind=core.accept(profile,ChannelSecurityTest.message("bind","owner","dm",""),"trusted_relay").getJSONObject("event").getString("id");core.bindOwner(profile,bind,true);core.setOwnerTasks(profile,true,true);
  String expired=core.accept(profile,ChannelSecurityTest.message("expired","owner","dm","").put("text","/phone 过期").put("sentAt",System.currentTimeMillis()-3_600_001L),"trusted_relay").getJSONObject("event").getString("id");assertThrows(SecurityException.class,()->core.preparePhoneTask(expired));
  String event=core.accept(profile,ChannelSecurityTest.message("current","owner","dm","").put("text","/phone 原目标"),"trusted_relay").getJSONObject("event").getString("id");String request=core.preparePhoneTask(event).getString("requestId");core.prepareCredentialChange(profile);assertThrows(SecurityException.class,()->core.claimPhoneTask(request,"原目标",true));
 }
}
