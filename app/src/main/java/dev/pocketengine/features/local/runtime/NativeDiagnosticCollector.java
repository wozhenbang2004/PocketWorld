package dev.pocketengine.features.local.runtime;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.local.project.ProjectSources;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.*;

/** Native owner metadata reads, not a Runner, task store, repair/trial or upload
 * service. Each fixed source has one zero-queue read lane: a blocked old read
 * cannot consume another source's lane or spawn replacement readers forever. */
public final class NativeDiagnosticCollector implements AutoCloseable {
    public enum Domain { PLATFORM,RUNTIME,PHONE,STORAGE }
    @FunctionalInterface public interface Probe {JSONObject read(RunControl observation)throws Exception;}
    private enum Type { BOOL,COUNT,FINGERPRINT,STATE }
    private static final int MAX_BYTES=65536;
    private final EnumMap<Domain,Lane> lanes=new EnumMap<>(Domain.class);
    private final long budgetMillis;
    private final AtomicBoolean closed=new AtomicBoolean();
    private static final class Lane {
        final Domain domain;final Probe probe;final ThreadPoolExecutor reads;
        volatile Call active;
        Lane(Domain domain,Probe probe){
            this.domain=domain;this.probe=probe;
            reads=new ThreadPoolExecutor(0,1,0,TimeUnit.MILLISECONDS,new SynchronousQueue<>(),r->{Thread t=new Thread(r,"owner-diagnostic-read-"+domain.name().toLowerCase(Locale.ROOT));t.setDaemon(true);return t;});
        }
    }
    private static final class Reading {
        final JSONObject facts;final String status;final long ended;
        Reading(JSONObject facts,String status){this.facts=facts;this.status=status;ended=System.nanoTime();}
    }
    private static final class Call {
        final Lane lane;final RunControl control=new RunControl();final RunControl owner;
        final long deadline;final AtomicBoolean entered=new AtomicBoolean(),returned=new AtomicBoolean();
        final Runnable cancel=control::cancel;
        FutureTask<Reading> future;
        Call(Lane lane,RunControl owner,long budget){this.lane=lane;this.owner=owner;deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(budget);}
    }
    public NativeDiagnosticCollector(Map<Domain,Probe> sources,long budgetMillis){
        if(budgetMillis<10||budgetMillis>10000||!sources.keySet().equals(EnumSet.allOf(Domain.class)))throw new IllegalArgumentException("diagnostic_fixed_source_contract_required");
        this.budgetMillis=budgetMillis;for(Domain domain:Domain.values())lanes.put(domain,new Lane(domain,Objects.requireNonNull(sources.get(domain))));
    }
    public Preview collect(RunControl owner)throws Exception {
        Objects.requireNonNull(owner).check();if(owner.hasHostRun())throw new SecurityException("diagnostic_does_not_borrow_task_authority");if(closed.get())throw new IllegalStateException("diagnostic_collector_closed");
        EnumMap<Domain,Call> pending=new EnumMap<>(Domain.class);JSONObject results=new JSONObject();long started=System.nanoTime();
        try{
            for(Domain domain:Domain.values()){
                owner.check();Lane lane=lanes.get(domain);Call call=new Call(lane,owner,budgetMillis);
                call.control.setAuthorityCheck(()->{owner.check();if(closed.get()||System.nanoTime()>call.deadline)throw new InterruptedException("diagnostic_read_deadline_or_closed");});
                call.future=new FutureTask<>(()->{
                    call.entered.set(true);lane.active=call;
                    try{call.control.check();JSONObject raw=lane.probe.read(call.control);call.control.check();return new Reading(checked(domain,raw),"collected");}
                    catch(InterruptedException cancelled){return new Reading(null,"cancelled");}
                    catch(Exception unavailable){return new Reading(null,"read_failed");}
                    finally{call.returned.set(true);lane.active=null;owner.removeCancelAction(call.cancel);}
                });
                owner.onCancel(call.cancel);
                try{lane.reads.execute(call.future);pending.put(domain,call);}
                catch(RejectedExecutionException busy){owner.removeCancelAction(call.cancel);results.put(key(domain),row(closed.get()?"collector_closed":"busy_previous_read_unconfirmed",null,false));}
            }
            boolean complete=true;
            for(Domain domain:Domain.values()){
                owner.check();Call call=pending.get(domain);if(call==null){complete=false;continue;}
                Reading read=null;String status;
                try{
                    long remaining=call.deadline-System.nanoTime();
                    if(remaining>0)read=call.future.get(remaining,TimeUnit.NANOSECONDS);
                    else if(call.future.isDone())read=call.future.get();else throw new TimeoutException();
                    status=read.ended<=call.deadline?read.status:"timed_out";
                }catch(TimeoutException late){status="timed_out";call.control.cancel();call.future.cancel(true);}
                catch(CancellationException cancelled){status="cancelled";}
                catch(ExecutionException failed){status="read_failed";}
                if(!"collected".equals(status))complete=false;
                results.put(key(domain),row(status,"collected".equals(status)?read.facts:null,call.returned.get()));
            }
            owner.check();if(closed.get())throw new IllegalStateException("diagnostic_collector_closed");JSONObject bundle=new JSONObject().put("format","pocketworld.owner-diagnostic-metadata.v1")
                    .put("snapshotId",UUID.randomUUID().toString()).put("createdAt",System.currentTimeMillis()).put("clockSource","native_os_clock_not_network_time")
                    .put("collectionStatus",complete?"metadata_collected":"partial_metadata").put("sourceDeadlineMillis",budgetMillis)
                    .put("elapsedMillis",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)).put("sources",results)
                    .put("snapshotAtomic",false)
                    .put("userContentIncluded",false).put("rawConfigurationIncluded",false).put("sessionIdentifiersIncluded",false)
                    .put("commandsExecuted",false).put("trialExecuted",false).put("tasksChanged",false).put("automaticUpload",false)
                    .put("deviceAcceptanceProved",false).put("resourceReleaseProved",false);
            return new Preview(bundle);
        }finally{
            for(Call call:pending.values()){if(!call.returned.get()){call.control.cancel();call.future.cancel(true);}owner.removeCancelAction(call.cancel);}
        }
    }
    private static String key(Domain domain){return domain.name().toLowerCase(Locale.ROOT);}
    private static JSONObject row(String status,JSONObject facts,boolean returned)throws Exception {
        // Future.cancel/isDone is NOT worker exit or permission to reclaim a lane.
        return new JSONObject().put("status",status).put("facts",facts==null?JSONObject.NULL:facts).put("sourceReadReturned",returned).put("workerExitInferredFromFuture",false);
    }
    private static Map<String,Type> schema(Domain domain){
        Map<String,Type> fields=new LinkedHashMap<>();
        switch(domain){
            case PLATFORM:for(String key:List.of("deviceSdk","targetSdk","appVersionCode"))fields.put(key,Type.COUNT);fields.put("arm64Advertised",Type.BOOL);break;
            case RUNTIME:
                for(String key:List.of("androidProfilePresent","activeGenerationLoaded","supervisorLoaded","ptyAvailable","duplexAvailable","integrityRechecked"))fields.put(key,Type.BOOL);
                fields.put("runtimeIdentityFingerprintSha256",Type.FINGERPRINT);fields.put("catalogFingerprintSha256",Type.FINGERPRINT);break;
            case PHONE:
                for(String key:List.of("mainObservation","mainScreenshot","mainExecution","taskNotifications","stopControl"))fields.put(key,Type.STATE);fields.put("backgroundSampled",Type.BOOL);break;
            case STORAGE:
                for(String key:List.of("homeLogicalBytes","temporaryLogicalBytes","downloadLogicalBytes","originalLogLogicalBytes","incompleteScopes","generationScopeCount","linkScopeCount"))fields.put(key,Type.COUNT);
                fields.put("logicalCountsNotAdditive",Type.BOOL);fields.put("fileContentsRead",Type.BOOL);break;
        }
        return fields;
    }
    private static JSONObject checked(Domain domain,JSONObject raw)throws Exception {
        Map<String,Type> fields=schema(domain);Set<String> keys=new HashSet<>();if(raw!=null)for(Iterator<String> it=raw.keys();it.hasNext();)keys.add(it.next());
        if(raw==null||!keys.equals(fields.keySet()))throw new SecurityException("diagnostic_metadata_schema_mismatch");JSONObject safe=new JSONObject();
        for(var field:fields.entrySet()){
            Object value=raw.get(field.getKey());boolean valid=value==JSONObject.NULL;
            if(!valid)switch(field.getValue()){
                case BOOL:valid=value instanceof Boolean;break;
                case COUNT:valid=(value instanceof Integer||value instanceof Long)&&((Number)value).longValue()>=0;break;
                case FINGERPRINT:valid=value instanceof String&&((String)value).matches("[a-f0-9]{64}");break;
                case STATE:valid=value instanceof String&&Set.of("ready","blocked","unknown").contains(value);break;
            }
            if(!valid)throw new SecurityException("diagnostic_metadata_value_rejected");safe.put(field.getKey(),value);
        }
        return safe;
    }
    /** Immutable, local, reviewed metadata bytes; no ArtifactStore or restoreable
     * execution authority. One entered write is never automatically repeated. */
    public static final class Preview {
        private final byte[] bytes;private final String text,sha;private final AtomicBoolean exportEntered=new AtomicBoolean();
        private Preview(JSONObject bundle)throws Exception {text=bundle.toString(2)+"\n";bytes=text.getBytes(StandardCharsets.UTF_8);if(bytes.length>MAX_BYTES)throw new IllegalStateException("diagnostic_metadata_over_budget");sha=ProjectSources.sha256(bytes);}
        public String textForOwner(){return text;}
        public String sha256(){return sha;}
        public int size(){return bytes.length;}
        public boolean exportEntered(){return exportEntered.get();}
        public JSONObject writeOnce(OutputStream destination,RunControl owner)throws Exception {
            Objects.requireNonNull(destination);Objects.requireNonNull(owner).check();if(owner.hasHostRun())throw new SecurityException("diagnostic_export_does_not_borrow_task_authority");
            if(!exportEntered.compareAndSet(false,true))throw new SecurityException("diagnostic_export_requires_new_snapshot_review");
            for(int offset=0;offset<bytes.length;offset+=4096){owner.check();destination.write(bytes,offset,Math.min(4096,bytes.length-offset));}
            owner.check();destination.flush();owner.check();
            return new JSONObject().put("streamWriteAccepted",true).put("bytes",bytes.length).put("sha256",sha).put("destinationPersistenceVerified",false).put("automaticUpload",false);
        }
    }
    @Override public void close(){if(!closed.compareAndSet(false,true))return;for(Lane lane:lanes.values()){Call active=lane.active;if(active!=null){active.control.cancel();active.future.cancel(true);}lane.reads.shutdownNow();}}
}
