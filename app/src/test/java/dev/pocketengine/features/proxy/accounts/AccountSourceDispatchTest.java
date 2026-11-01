package dev.pocketengine.features.proxy.accounts;

import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.memory.ToolOutputCapture;
import dev.pocketengine.features.proxy.providers.ChatGptProvider;
import dev.pocketengine.features.proxy.providers.ResponsesRequest;
import dev.pocketengine.runtime.ModelGateway;
import dev.pocketengine.runtime.usage.InferenceCapture;
import dev.pocketengine.runtime.usage.InferenceProjection;
import dev.pocketengine.runtime.usage.InferenceReceipt;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import static org.junit.Assert.*;

/** Real Gateway -> original AccountGateway version sink -> local synthetic provider.
 * Login fixture HTTP is explicit setup; a refused inference must send zero /responses POSTs.
 * No real account, quota, device, or full source-storage closure is claimed. */
public final class AccountSourceDispatchTest {
    private static final class Receipts implements InferenceCapture.Sink {
        final List<InferenceReceipt> started=new ArrayList<>(),finished=new ArrayList<>();
        public void started(InferenceReceipt receipt){started.add(receipt);}
        public void finished(InferenceReceipt receipt){finished.add(receipt);}
        InferenceCapture.Target target(RunControl original,InferenceReceipt.Purpose purpose,InferenceCapture.Admission source)throws Exception {
            String task=UUID.randomUUID().toString(),run=UUID.randomUUID().toString();original.bindHostRun(task,run,1);
            return new InferenceCapture.Target(new InferenceReceipt.Context(task,run,1,purpose,""),this,source);
        }
    }
    @After public void unbind(){ModelGateway.installAccountInferencePort(null);}
    private ModelConfig config(AccountGatewayTest.Rig rig,String id)throws Exception {
        return new ModelConfig("","","",false,3,10).withAccountModel(ModelConfig.AccountModelRef.fromJson(new JSONObject().put("provider","chatgpt_plan").put("accountId",id).put("accountVersion",rig.book.row(id).getLong("version")).put("modelId","fixture-model")));
    }
    private void bind(AccountGatewayTest.Rig rig,AtomicInteger authorizations) {
        ModelGateway.installAccountInferencePort(new ModelGateway.AccountInferencePort(){
            public boolean accountIdentityCurrent(ModelConfig.AccountModelRef ref){return rig.gateway.inferenceIdentityCurrent(ref.accountId,ref.accountVersion);}
            public void stream(ModelConfig.AccountModelRef ref,ResponsesRequest request,GatewayCall operation,ChatGptProvider.EventSink sink)throws GatewayFailure {throw new GatewayFailure("UNEXPECTED_LEGACY_PATH");}
            public void streamWithVersion(ModelConfig.AccountModelRef ref,ResponsesRequest request,GatewayCall operation,ModelGateway.AccountVersionSink versions,ChatGptProvider.EventSink sink)throws GatewayFailure {
                rig.gateway.streamResponsesReviewed(ref.accountId,ref.accountVersion,request,operation,actual->{authorizations.incrementAndGet();versions.authorized(actual);},sink);
            }
        });
    }
    private JSONObject request(ModelConfig captured,RunControl original,InferenceCapture.Target target,boolean streaming)throws Exception {
        JSONArray messages=new JSONArray().put(new JSONObject().put("role","user").put("content","read only fixture request"));
        return streaming?ModelGateway.completeStreaming(captured,messages,null,original,event->{},target):ModelGateway.complete(captured,messages,null,original,target);
    }
    private void assertNoInferenceOrOpaqueAccountMutation(AccountGatewayTest.Rig rig,String id,String before)throws Exception {
        assertEquals(0,rig.fake.responses.get());assertEquals(0,rig.fake.models.get());assertEquals(0,rig.fake.refreshes.get());
        assertEquals(before,rig.book.row(id).toString());
    }
    private void assertOriginalSinkSingleCall(Receipts receipts,InferenceReceipt.Purpose purpose,String outcome)throws Exception {
        assertEquals(1,receipts.started.size());assertEquals(1,receipts.finished.size());
        InferenceReceipt terminal=receipts.finished.get(0);assertEquals(receipts.started.get(0).callId,terminal.callId);assertEquals(purpose,terminal.context.purpose);assertEquals(outcome,terminal.outcome.name());
        JSONObject receipt=terminal.toJson();JSONObject counters=receipt.getJSONObject("usage").getJSONObject("counters");
        assertTrue(counters.getJSONObject("INPUT").isNull("value"));assertTrue(counters.getJSONObject("OUTPUT").isNull("value"));
        JSONObject projection=InferenceProjection.fromEvents(new JSONArray().put(receipts.started.get(0).toJson()).put(receipt));
        assertEquals(1,projection.getInt("attempts"));assertEquals(0,projection.getInt("completed"));assertEquals(0,projection.getInt("unfinished"));
        assertFalse(receipt.toString().contains("storage-secret"));
    }
    @Test public void exactSourceRefusalCrossesOriginalAccountVersionSinkWithoutAnyInferencePostOrReplay()throws Exception {
        try(AccountGatewayTest.Rig rig=new AccountGatewayTest.Rig()) {
            String id=rig.connect(),before=rig.book.row(id).toString();ModelConfig captured=config(rig,id);AtomicInteger authorizations=new AtomicInteger();bind(rig,authorizations);
            int attempts=0;
            for(boolean streaming:new boolean[]{false,true})for(InferenceReceipt.Purpose purpose:new InferenceReceipt.Purpose[]{InferenceReceipt.Purpose.MAIN,InferenceReceipt.Purpose.SUMMARY})for(int refuseAt:new int[]{1,2}) {
                RunControl original=new RunControl();Receipts receipts=new Receipts();AtomicInteger checks=new AtomicInteger();ToolOutputCapture.OutputSourceUnavailable refusal=new ToolOutputCapture.OutputSourceUnavailable(null);
                InferenceCapture.Target target=receipts.target(original,purpose,()->{if(checks.incrementAndGet()==refuseAt)throw refusal;});
                Exception seen=assertThrows(ToolOutputCapture.OutputSourceUnavailable.class,()->request(captured,original,target,streaming));
                assertSame(refusal,seen);assertFalse(original.isCancelled());assertEquals(refuseAt,checks.get());
                if(refuseAt==1){assertTrue(receipts.started.isEmpty());assertTrue(receipts.finished.isEmpty());}
                else assertOriginalSinkSingleCall(receipts,purpose,"FAILED");
                attempts++;assertEquals(attempts,authorizations.get());assertNoInferenceOrOpaqueAccountMutation(rig,id,before);
            }
        }
    }
    @Test public void ordinaryStorageFailureStillUsesOriginalStableAccountErrorAndKeepsOriginalCallUnknown()throws Exception {
        try(AccountGatewayTest.Rig rig=new AccountGatewayTest.Rig()) {
            String id=rig.connect(),before=rig.book.row(id).toString();ModelConfig captured=config(rig,id);AtomicInteger authorizations=new AtomicInteger();bind(rig,authorizations);
            for(boolean streaming:new boolean[]{false,true}) {
                RunControl original=new RunControl();Receipts receipts=new Receipts();AtomicInteger checks=new AtomicInteger();
                InferenceCapture.Target target=receipts.target(original,InferenceReceipt.Purpose.MAIN,()->{if(checks.incrementAndGet()==2)throw new IOException("storage-secret");});
                ModelGateway.GatewayException seen=assertThrows(ModelGateway.GatewayException.class,()->request(captured,original,target,streaming));
                assertTrue(seen.getMessage().contains("用量身份"));assertFalse(seen.getMessage().contains("storage-secret"));assertFalse(original.isCancelled());assertOriginalSinkSingleCall(receipts,InferenceReceipt.Purpose.MAIN,"FAILED");
                assertEquals("INFERENCE_RECEIPT_STORE_UNAVAILABLE",receipts.finished.get(0).toJson().getJSONObject("failure").getString("nativeCode"));
                assertNoInferenceOrOpaqueAccountMutation(rig,id,before);
            }
            assertEquals(2,authorizations.get());
        }
    }
    @Test public void originalOwnerCancellationWinsOverThePrivateSourceRefusalAndDoesNotBecomeFailedOrRefunded()throws Exception {
        try(AccountGatewayTest.Rig rig=new AccountGatewayTest.Rig()) {
            String id=rig.connect(),before=rig.book.row(id).toString();ModelConfig captured=config(rig,id);AtomicInteger authorizations=new AtomicInteger();bind(rig,authorizations);
            for(boolean streaming:new boolean[]{false,true})for(InferenceReceipt.Purpose purpose:new InferenceReceipt.Purpose[]{InferenceReceipt.Purpose.MAIN,InferenceReceipt.Purpose.SUMMARY}) {
                RunControl original=new RunControl();Receipts receipts=new Receipts();AtomicInteger checks=new AtomicInteger();ToolOutputCapture.OutputSourceUnavailable refusal=new ToolOutputCapture.OutputSourceUnavailable(null);
                InferenceCapture.Target target=receipts.target(original,purpose,()->{if(checks.incrementAndGet()==2){original.cancel();throw refusal;}});
                assertThrows(InterruptedException.class,()->request(captured,original,target,streaming));assertTrue(original.isCancelled());assertOriginalSinkSingleCall(receipts,purpose,"CANCELLED");
                assertNoInferenceOrOpaqueAccountMutation(rig,id,before);
            }
            assertEquals(4,authorizations.get());
        }
    }
}
