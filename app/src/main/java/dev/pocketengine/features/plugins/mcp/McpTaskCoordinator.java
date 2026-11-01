package dev.pocketengine.features.plugins.mcp;

import dev.pocketengine.core.RunControl;
import java.time.Instant;
import java.util.Objects;
import org.json.JSONObject;

/** Execution receipts only. The host's TaskStore owns admission, operations and persistence. */
public final class McpTaskCoordinator {
    public static final String VERSION="2026-07-28", EXTENSION="io.modelcontextprotocol/tasks";
    public enum Access { DISPATCH, QUERY, CANCEL }
    public static final class Binding {
        public final String taskId,runId,operationId,installationId,credentialId,endpoint,serverDigest,descriptorDigest;
        public final int epoch;
        private final String declaration;
        public Binding(String taskId,String runId,int epoch,String operationId,String installationId,String credentialId,JSONObject declaration)throws Exception {
            this.taskId=id(taskId);this.runId=id(runId);this.operationId=id(operationId);this.installationId=id(installationId);
            this.credentialId=id(credentialId);if(epoch<1)throw new IllegalArgumentException("Run epoch required");this.epoch=epoch;
            if(!VERSION.equals(declaration.getString("protocolVersion")))throw new IllegalArgumentException("Tasks requires explicit 2026-07-28; legacy tasks are unavailable");
            endpoint=declaration.getString("endpoint");serverDigest=hash(declaration.getString("serverDigest"));
            descriptorDigest=hash(declaration.getString("descriptorDigest"));this.declaration=declaration.toString();
        }
        public JSONObject declaration()throws Exception{return new JSONObject(declaration);}
        public String fingerprint()throws Exception {
            return McpJson.digest(new org.json.JSONArray().put(taskId).put(runId).put(epoch).put(operationId).put(installationId)
                    .put(credentialId).put(endpoint).put(serverDigest).put(descriptorDigest).put(new JSONObject(declaration)));
        }
    }
    public interface Transport {
        JSONObject start(JSONObject declaration,JSONObject arguments,RunControl control)throws Exception;
        JSONObject query(JSONObject declaration,String remoteTaskId,RunControl control)throws Exception;
        void cancel(JSONObject declaration,String remoteTaskId,RunControl control)throws Exception;
    }
    /** Only a transport which proves the business request boundary may clear the provisional uncertainty. */
    public interface DispatchFailure {boolean mayHaveDispatched();}
    public interface Host {
        /** Live owner/install/grant and exact task/run/epoch check, under the host's admission lock. */
        void check(Binding binding,Access access)throws Exception;
        /** Returns the durable operation receipt, or null. Never creates a product task. */
        JSONObject load(Binding binding)throws Exception;
        /** Atomically persist reservation before dispatch. Reject duplicates and unresolved task effects,
         * including a new operation/run trying to replay an unresolved write. */
        void reserve(Binding binding,String argumentsDigest,JSONObject reservation)throws Exception;
        /** CAS on binding, merge cancellation flags monotonically, reject task/chronology changes and
         * resolved-result regression. Persist even after cancellation/revocation for reconciliation. */
        void save(Binding binding,JSONObject receipt)throws Exception;
        /** Atomic once-only cancellation intent; false means already sent or outcome unknown. */
        boolean reserveCancellation(Binding binding)throws Exception;
    }
    private final Host host;
    private final Transport transport;
    private final java.util.function.LongSupplier clock;
    public McpTaskCoordinator(Host host,Transport transport){this(host,transport,System::currentTimeMillis);}
    public McpTaskCoordinator(Host host,Transport transport,java.util.function.LongSupplier clock){
        this.host=Objects.requireNonNull(host);this.transport=Objects.requireNonNull(transport);this.clock=Objects.requireNonNull(clock);
    }
    public JSONObject start(Binding binding,JSONObject arguments,RunControl control)throws Exception {
        check(binding,control,Access.DISPATCH);JSONObject snapshot=new JSONObject(arguments.toString());String argumentsDigest=McpJson.digest(snapshot);
        JSONObject previous=host.load(binding);
        if(previous!=null){
            JSONObject saved=checked(binding,previous);
            if(!argumentsDigest.equals(saved.optString("argumentsDigest")))throw new SecurityException("Operation arguments changed");
            return saved; // Crash/timeout never resubmits tools/call.
        }
        JSONObject receipt=base(binding).put("argumentsDigest",argumentsDigest).put("status","unknown").put("dispatchStatus","may_have_dispatched");
        host.reserve(binding,argumentsDigest,new JSONObject(receipt.toString()));
        // Reservation is itself an unknown effect until a durable outcome is recorded.
        try {
            check(binding,control,Access.DISPATCH);
            JSONObject result=transport.start(binding.declaration(),snapshot,control);
            receipt=record(binding,result,true).put("argumentsDigest",argumentsDigest);
        }catch(Exception failure){
            receipt.put("cancelRequested",control.isCancelled()).put("message","未取得完整执行回执；只核对，不重新提交");
            if(failure instanceof DispatchFailure&&!((DispatchFailure)failure).mayHaveDispatched())
                receipt.put("status","failed").put("dispatchStatus","not_dispatched").put("resolved",true).put("terminal",true).put("message","工具调用前校验失败；未发送tools/call");
            host.save(binding,receipt);throw failure;
        }
        host.save(binding,receipt);check(binding,control,Access.QUERY);return checked(binding,host.load(binding));
    }
    public JSONObject query(Binding binding,RunControl control)throws Exception {
        check(binding,control,Access.QUERY);JSONObject old=checked(binding,host.load(binding));
        if(!old.has("remoteTaskId"))return old; // Lost creation ACK has no safe lookup handle.
        if(old.optBoolean("resolved"))return old;
        if(old.has("ttlMs")&&!old.isNull("ttlMs")&&clock.getAsLong()>=Math.addExact(Instant.parse(old.getString("createdAt")).toEpochMilli(),old.getLong("ttlMs")))
            return old.put("receiptExpired",true).put("message","执行端收据已过期；不能据此重新提交操作");
        if(clock.getAsLong()<old.optLong("nextPollAt"))return old;
        JSONObject result=transport.query(binding.declaration(),old.getString("remoteTaskId"),control);
        JSONObject receipt=record(binding,result,false).put("argumentsDigest",old.getString("argumentsDigest"));
        if(!old.getString("remoteTaskId").equals(receipt.optString("remoteTaskId")))throw new SecurityException("Remote task identity changed");
        if(!old.getString("createdAt").equals(receipt.optString("createdAt"))||
                Instant.parse(receipt.getString("lastUpdatedAt")).isBefore(Instant.parse(old.getString("lastUpdatedAt"))))
            throw new SecurityException("Remote task chronology changed");
        if(old.optBoolean("cancelRequested"))receipt.put("cancelRequested",true).put("cancelAcknowledged",old.optBoolean("cancelAcknowledged"));
        host.save(binding,receipt);check(binding,control,Access.QUERY);return checked(binding,host.load(binding));
    }
    public JSONObject cancel(Binding binding,RunControl signalControl)throws Exception {
        check(binding,signalControl,Access.CANCEL);JSONObject receipt=checked(binding,host.load(binding));
        if(!receipt.has("remoteTaskId")){
            receipt.put("cancelRequested",true).put("cancelAcknowledged",false).put("message","缺少执行端任务身份；停止本地派发，远端效果仍需核对");
            host.save(binding,receipt);return checked(binding,host.load(binding));
        }
        if(receipt.optBoolean("terminal"))return receipt;
        if(!host.reserveCancellation(binding))return checked(binding,host.load(binding));
        receipt.put("cancelRequested",true).put("cancelAcknowledged",false);host.save(binding,receipt);
        // A separate bounded signal control is required: the execution control is already cancelled.
        transport.cancel(binding.declaration(),receipt.getString("remoteTaskId"),signalControl);
        receipt.put("cancelAcknowledged",true);host.save(binding,receipt);check(binding,signalControl,Access.CANCEL);return checked(binding,host.load(binding));
    }
    private void check(Binding b,RunControl control,Access access)throws Exception {
        if(!control.hasHostRun()||!control.matchesHostRun(b.taskId,b.runId,b.epoch))throw new SecurityException("Exact native run binding required");
        control.check();if(control.isPaused()&&access==Access.DISPATCH)throw new IllegalStateException("Run paused");host.check(b,access);
    }
    private JSONObject checked(Binding b,JSONObject receipt)throws Exception {
        if(receipt==null||!b.fingerprint().equals(receipt.optString("binding")))throw new SecurityException("Receipt binding mismatch");
        return new JSONObject(receipt.toString());
    }
    private JSONObject base(Binding b)throws Exception{return new JSONObject().put("binding",b.fingerprint()).put("operationId",b.operationId)
            .put("verified",false).put("untrusted",true).put("instructionsAreData",true);}
    private JSONObject record(Binding b,JSONObject result,boolean creation)throws Exception {
        JSONObject receipt=base(b).put("dispatchStatus","response_received");
        if(!result.has("taskId")){
            if(!creation)throw new IllegalArgumentException("Missing task identity");
            return receipt.put("status","response_received").put("terminal",true).put("resolved",true).put("toolResult",result);
        }
        String remote=id(result.getString("taskId")),status=result.getString("status");
        if(!java.util.Arrays.asList("working","input_required","completed","failed","cancelled").contains(status))throw new IllegalArgumentException("Unknown task status");
        String created=result.getString("createdAt"),updated=result.getString("lastUpdatedAt");
        if(Instant.parse(updated).isBefore(Instant.parse(created)))throw new IllegalArgumentException("Task timestamp order");
        if(!result.has("ttlMs"))throw new IllegalArgumentException("Missing task TTL");
        long interval=McpJson.interval(result.opt("pollIntervalMs"),1000);McpJson.interval(result.opt("ttlMs"),0);
        boolean terminal=status.equals("completed")||status.equals("failed")||status.equals("cancelled");
        receipt.put("remoteTaskId",remote).put("status",status).put("createdAt",created).put("lastUpdatedAt",updated)
                .put("terminal",terminal).put("resolved",terminal&&!creation).put("ttlMs",result.opt("ttlMs")).put("nextPollAt",Math.addExact(clock.getAsLong(),Math.max(250,interval)));
        if(!creation&&status.equals("completed")&&!result.has("result"))throw new IllegalArgumentException("Missing completed result");
        if(!creation&&status.equals("failed")&&!result.has("error"))throw new IllegalArgumentException("Missing task error");
        if(result.has("result"))receipt.put("toolResult",result.getJSONObject("result"));
        if(result.has("error"))receipt.put("protocolError",result.getJSONObject("error"));
        if(status.equals("input_required"))receipt.put("unsupportedInputRequired",true);
        return receipt;
    }
    private static String id(String value){if(value==null||value.isEmpty()||value.length()>4096||value.chars().anyMatch(c->c<32||c==127))throw new IllegalArgumentException("Invalid identity");return value;}
    private static String hash(String value){if(!value.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Digest required");return value;}
}
