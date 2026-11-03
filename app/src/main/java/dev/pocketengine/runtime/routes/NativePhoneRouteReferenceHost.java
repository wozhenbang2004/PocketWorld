package dev.pocketengine.runtime.routes;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.MemorySourceBridge;
import dev.pocketengine.features.memory.TaskMemoryTrace;
import dev.pocketengine.store.TaskStore;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Objects;
import org.json.JSONArray;
import org.json.JSONObject;

/** Owner-only projection of the existing confirmed route source. No new store, queue, grant,
 * route import or execution path. The canonical forget monitor still precedes original SQL. */
public final class NativePhoneRouteReferenceHost {
    private final TaskStore tasks;private final MemorySourceBridge sources;
    public NativePhoneRouteReferenceHost(TaskStore tasks,MemorySourceBridge sources){this.tasks=Objects.requireNonNull(tasks);this.sources=Objects.requireNonNull(sources);}
    public static final class NoReference extends IllegalStateException {public NoReference(){super("这次原任务没有可导出的主人已确认路线");}}

    public Review inspect(String task,String run,int epoch,RunControl control)throws Exception {
        control.check();JSONObject metadata=tasks.phoneRouteSourceMetadata(task);if(metadata==null)throw new NoReference();
        requireTuple(metadata,task,run,epoch);JSONArray proofs=proofs(task,metadata);
        JSONObject reference=sources.withCurrentPhoneRouteResults(proofs,()->tasks.withPhoneRouteSourcesCurrent(proofs,()->{
            control.check();JSONObject candidate=tasks.originalPhoneRoute(metadata);requireTuple(metadata,task,run,epoch);return PortablePhoneRouteReference.fromLocalCandidate(candidate);
        }));control.check();return new Review(task,run,epoch,metadata,proofs,reference);
    }
    public final class Review implements AutoCloseable {
        private final String task,run,source,proofs,reference,presentation;private final int epoch;private final long expiresAt=System.currentTimeMillis()+600_000;
        private volatile boolean closed;
        private Review(String task,String run,int epoch,JSONObject source,JSONArray proofs,JSONObject reference)throws Exception{
            this.task=task;this.run=run;this.epoch=epoch;this.source=source.toString();this.proofs=proofs.toString();this.reference=reference.toString();this.presentation=PortablePhoneRouteReference.presentation(reference).toString();
        }
        public JSONObject view()throws Exception{
            require();return new JSONObject(presentation).put("source",new JSONObject().put("taskId",task).put("runId",run).put("epoch",epoch).put("namespace","owner/private"))
                    .put("expiresAt",expiresAt).put("sourceConfirmationTransfers",false);
        }
        /** Capture under the original source gate; stream IO never runs in the Tasks transaction. */
        public JSONObject export(OutputStream output,RunControl control)throws Exception{
            Objects.requireNonNull(output);require();control.check();JSONObject metadata=new JSONObject(source);JSONArray exactProofs=new JSONArray(proofs);
            sources.withCurrentPhoneRouteResults(exactProofs,()->tasks.withPhoneRouteSourcesCurrent(exactProofs,()->{
                require();control.check();requireTuple(metadata,task,run,epoch);JSONObject actual=PortablePhoneRouteReference.fromLocalCandidate(tasks.originalPhoneRoute(metadata));
                if(!actual.getString("digest").equals(new JSONObject(reference).getString("digest")))throw new SecurityException("原路线参考已变化，请重新检查");return null;
            }));
            byte[] exact=new JSONObject(presentation).getString("text").getBytes(StandardCharsets.UTF_8);
            try{for(int offset=0;offset<exact.length;){require();control.check();int size=Math.min(2048,exact.length-offset);output.write(exact,offset,size);offset+=size;}}
            finally{java.util.Arrays.fill(exact,(byte)0);}
            require();control.check();JSONObject receipt=new JSONObject(presentation);receipt.remove("reference");receipt.remove("text");return receipt.put("source",view().getJSONObject("source")).put("capturedFromOriginalConfirmedSource",true);
        }
        private void require(){if(closed||System.currentTimeMillis()>expiresAt)throw new SecurityException("原路线参考审阅已经关闭或过期");}
        @Override public void close(){closed=true;}
    }
    private JSONArray proofs(String task,JSONObject original)throws Exception{
        LinkedHashMap<String,JSONObject> all=new LinkedHashMap<>();all.put(TaskStore.capabilityFingerprint(original),original);JSONArray inherited=tasks.phoneRouteDependencies(task);
        for(int i=0;i<inherited.length();i++){JSONObject proof=inherited.getJSONObject(i);all.put(TaskStore.capabilityFingerprint(proof),proof);}if(all.size()>64)throw new IllegalStateException("原路线来源超过有界范围，未截断");JSONArray result=new JSONArray();for(JSONObject proof:all.values())result.put(new JSONObject(proof.toString()));return result;
    }
    private static void requireTuple(JSONObject proof,String task,String run,int epoch)throws Exception{
        if(task==null||run==null||epoch<1||!task.equals(TaskMemoryTrace.sourceTaskId(proof.getJSONObject("sourceRef")))||!run.equals(proof.getString("sourceRunId"))||epoch!=proof.getInt("sourceEpoch"))throw new SecurityException("路线参考只属于这个原任务、运行和代次");
    }
}
