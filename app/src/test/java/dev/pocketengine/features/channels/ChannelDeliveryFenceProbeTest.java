package dev.pocketengine.features.channels;
import java.nio.file.Files;
import java.util.UUID;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;
/** Isolated domain fixture: synthetic QQ receipt metadata, no credentials/account/network. */
public final class ChannelDeliveryFenceProbeTest {
 @Test public void replyQuotaCannotFreezeCancellationOnAnOlderRun() throws Exception {
  java.nio.file.Path directory=Files.createTempDirectory("channel-delivery-fence-");
  try {
   ChannelCore core=new ChannelCore(directory.toFile());
   String profile=ChannelSecurityTest.addProfile(core,"quota-probe","qq");
   String binding=core.accept(profile,ChannelSecurityTest.message("bind","owner","dm",""),"qq_signature").getJSONObject("event").getString("id");
   core.bindOwner(profile,binding,true);core.setOwnerTasks(profile,true,true);
   String event=core.accept(profile,ChannelSecurityTest.message("task","owner","dm","").put("text","/task same task"),"qq_signature").getJSONObject("event").getString("id");
   String request=core.prepareHostTask(event).getJSONObject("request").getString("requestId");
   String task=core.hostTask(request).getString("taskId"), oldRun=UUID.randomUUID().toString(),secondRun=UUID.randomUUID().toString(),newRun=UUID.randomUUID().toString();
   for(int revision=1;revision<=4;revision++) {
    HostTaskPort.TaskState receipt=new HostTaskPort.TaskState(task,revision<=2?oldRun:secondRun,revision<=2?1:2,revision,revision%2==1?"running":"awaiting_user","approval round "+revision);
    core.hostTaskAdmitted(request,receipt);core.queueHostTaskUpdate(request,receipt);
   }
   HostTaskPort.TaskState resumed=new HostTaskPort.TaskState(task,newRun,3,5,"running","same task resumed by native owner");
   core.hostTaskAdmitted(request,resumed);
   assertThrows(IllegalStateException.class,()->core.queueHostTaskUpdate(request,resumed));
   ChannelCore restored=new ChannelCore(directory.toFile());
   HostTaskPort.Request cancellation=restored.prepareHostTaskCancellation(request);
   assertEquals("An outbox quota failure must not leave cancellation aimed at the superseded run",3,cancellation.expectedCoordinatorEpoch);
   assertEquals(newRun,cancellation.expectedRunId);
   assertEquals(5,restored.hostTask(request).getLong("hostReceiptRevision"));
   assertEquals("running",restored.hostTask(request).getString("hostStatus"));
   assertEquals(4,restored.hostTask(request).getLong("lastUpdateRevision"));
   HostTaskPort.TaskState oldAdmission=new HostTaskPort.TaskState(task,secondRun,2,4,"awaiting_user","approval round 4");
   restored.hostTaskAdmitted(request,oldAdmission);
   assertEquals(newRun,restored.hostTask(request).getString("hostRunId"));
   assertThrows(SecurityException.class,()->restored.queueHostTaskUpdate(request,oldAdmission));
   assertThrows(SecurityException.class,()->restored.hostTaskAdmitted(request,new HostTaskPort.TaskState(task,newRun,3,5,"completed","conflicting same revision")));
   assertEquals(4,restored.snapshot().getJSONArray("outbox").length());
  } finally {try(java.util.stream.Stream<java.nio.file.Path> all=Files.walk(directory)){all.sorted(java.util.Comparator.reverseOrder()).forEach(p->{try{Files.delete(p);}catch(Exception ignored){}});}}
 }
}
