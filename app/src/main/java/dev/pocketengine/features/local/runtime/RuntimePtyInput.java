package dev.pocketengine.features.local.runtime;

import dev.pocketengine.features.local.project.ProjectCommandSpec;
import dev.pocketengine.features.local.project.ProjectSources;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONObject;

/** Bounded in-memory IO queue of one already admitted native job. No task, grant or replay authority. */
final class RuntimePtyInput {
    static final int TEXT=1,EOF=2,RESIZE=3,HEADER=24,MAX_REQUESTS=128;
    private final long limit;private long charged,sequence;private boolean closed;
    private final Map<String,Ticket> requests=new LinkedHashMap<>();private final ArrayDeque<Ticket> queue=new ArrayDeque<>();
    static final class Ticket {
        final String operationId,digest,action;final long sequence;final int requestedBytes,rows,columns;final ProjectRuntime.LiveGate gate;byte[] packet;
        boolean dequeued,nativeAcknowledged;String state="pending";int written,error,actualRows,actualColumns;
        Ticket(String id,String digest,String action,long sequence,int count,int rows,int columns,byte[] packet,ProjectRuntime.LiveGate gate){this.gate=java.util.Objects.requireNonNull(gate);operationId=id;this.digest=digest;this.action=action;this.sequence=sequence;requestedBytes=count;this.rows=rows;this.columns=columns;this.packet=packet;}
        JSONObject metadata()throws Exception{return new JSONObject().put("inputOperationId",operationId).put("inputDigest",digest).put("inputSequence",sequence).put("action",action).put("status",state).put("requestedBytes",requestedBytes).put("writtenBytes",written).put("nativeErrno",error).put("rows",actualRows).put("columns",actualColumns).put("inputTextIncluded",false).put("processTerminal",false).put("replayAllowed",false);}
    }
    RuntimePtyInput(long limit){if(limit<1||limit>ProjectCommandSpec.MAX_INPUT_BYTES)throw new IllegalArgumentException("runtime_pty_input_budget_invalid");this.limit=limit;}
    Ticket offer(String operationId,JSONObject request)throws Exception{return offer(operationId,request,()->{});}
    synchronized Ticket offer(String operationId,JSONObject request,ProjectRuntime.LiveGate gate)throws Exception{
        if(operationId==null||!operationId.matches("[A-Za-z0-9_-]{1,80}"))throw new IllegalArgumentException("runtime_pty_input_operation_invalid");
        String digest=ProjectSources.digest(request);Ticket prior=requests.get(operationId);if(prior!=null){if(!prior.digest.equals(digest))throw new SecurityException("runtime_pty_input_original_request_changed");return prior;}
        if(closed)throw new SecurityException("runtime_pty_input_closed");if(requests.size()>=MAX_REQUESTS||requests.values().stream().anyMatch(t->t.state.equals("unknown")))throw new SecurityException("runtime_pty_input_unresolved_or_budget_exhausted");
        String action=request.getString("action");int kind,count=0,rows=0,columns=0;byte[] value=new byte[0];
        for(java.util.Iterator<String> keys=request.keys();keys.hasNext();){String name=keys.next();if(!java.util.Set.of("action","text","rows","columns").contains(name))throw new IllegalArgumentException("runtime_pty_input_field_unsupported");}
        switch(action){
            case "text":kind=TEXT;if(!request.has("text")||request.has("rows")||request.has("columns"))throw new IllegalArgumentException("runtime_pty_text_request_invalid");value=ProjectCommandSpec.encode(request.getString("text"));count=value.length;if(count<1||count>ProjectCommandSpec.MAX_INPUT_CHUNK)throw new IllegalArgumentException("runtime_pty_text_size_invalid");break;
            case "eof":kind=EOF;if(request.has("text")||request.has("rows")||request.has("columns"))throw new IllegalArgumentException("runtime_pty_eof_request_invalid");count=1;break;
            case "resize":kind=RESIZE;if(request.has("text"))throw new IllegalArgumentException("runtime_pty_resize_request_invalid");rows=(int)ProjectCommandSpec.integer(request,"rows",-1,2,200);columns=(int)ProjectCommandSpec.integer(request,"columns",-1,4,400);if(rows<2||columns<4)throw new IllegalArgumentException("runtime_pty_resize_request_invalid");break;
            default:throw new IllegalArgumentException("runtime_pty_input_action_invalid");
        }
        if(charged+count>limit)throw new SecurityException("runtime_pty_original_input_byte_budget_exhausted");
        byte[] packet=ByteBuffer.allocate(HEADER+value.length).order(ByteOrder.BIG_ENDIAN).putInt(kind).putLong(++sequence).putInt(rows).putInt(columns).putInt(value.length).put(value).array();
        Ticket ticket=new Ticket(operationId,digest,action,sequence,count,rows,columns,packet,gate);requests.put(operationId,ticket);queue.addLast(ticket);charged+=count;notifyAll();return ticket;
    }
    void checkCurrent(long sequence)throws Exception {
        ProjectRuntime.LiveGate gate=null;synchronized(this){for(Ticket ticket:requests.values())if(ticket.sequence==sequence&&ticket.dequeued&&!ticket.nativeAcknowledged)gate=ticket.gate;}
        if(gate==null)throw new SecurityException("runtime_pty_input_original_gate_missing");gate.check();
    }
    synchronized boolean hasRequest(String operationId){return requests.containsKey(operationId);}
    synchronized byte[] next(){if(closed||queue.isEmpty())return null;Ticket ticket=queue.removeFirst();ticket.dequeued=true;byte[] packet=ticket.packet;ticket.packet=null;return packet;}
    synchronized JSONObject observed(long token,int bytes,int error,int rows,int columns,boolean effectMayHaveOccurred)throws Exception{
        Ticket ticket=null;for(Ticket item:requests.values())if(item.sequence==token){ticket=item;break;}
        if(ticket==null||!ticket.dequeued||ticket.nativeAcknowledged||bytes<0||bytes>ticket.requestedBytes||error<0)throw new IllegalStateException("runtime_pty_native_input_ack_invalid");
        boolean complete=error==0&&bytes==ticket.requestedBytes&&(!ticket.action.equals("resize")||rows==ticket.rows&&columns==ticket.columns);
        ticket.nativeAcknowledged=true;ticket.written=bytes;ticket.error=error;ticket.actualRows=rows;ticket.actualColumns=columns;
        ticket.state=complete?"delivered":bytes==0&&error>0&&!effectMayHaveOccurred?"not_dispatched":"unknown";notifyAll();return ticket.metadata().put("nativeAcknowledged",true).put("inputBudgetBytes",limit).put("inputBytesCharged",charged);
    }
    JSONObject await(Ticket ticket,dev.pocketengine.core.RunControl original,long maxMillis)throws Exception{
        long until=System.nanoTime()+maxMillis*1_000_000L;
        while(true){
            // Never call original authority while holding the IO queue monitor.
            if(original!=null)original.check();
            synchronized(this){
                if(!ticket.state.equals("pending"))return ticket.metadata().put("nativeAcknowledged",ticket.nativeAcknowledged).put("inputBudgetBytes",limit).put("inputBytesCharged",charged);
                long remaining=until-System.nanoTime();if(remaining<=0){
                    if(!ticket.dequeued){queue.remove(ticket);ticket.packet=null;ticket.state="not_dispatched";}else ticket.state="unknown";
                    return ticket.metadata().put("nativeAcknowledged",false).put("inputBudgetBytes",limit).put("inputBytesCharged",charged);
                }
                wait(Math.max(1,Math.min(50,remaining/1_000_000L)));
            }
        }
    }
    synchronized java.util.List<JSONObject> closeAndObserveNeverDequeued()throws Exception{
        closed=true;queue.clear();java.util.List<JSONObject> safe=new java.util.ArrayList<>();
        for(Ticket ticket:requests.values())if(ticket.state.equals("pending")){ticket.state=ticket.dequeued?"unknown":"not_dispatched";ticket.packet=null;if(!ticket.dequeued)safe.add(ticket.metadata().put("nativeAcknowledged",false).put("inputBudgetBytes",limit).put("inputBytesCharged",charged));}
        notifyAll();return safe;
    }
    void closed(){try{closeAndObserveNeverDequeued();}catch(Exception failed){throw new IllegalStateException("runtime_pty_close_receipt_failed",failed);}}
}
