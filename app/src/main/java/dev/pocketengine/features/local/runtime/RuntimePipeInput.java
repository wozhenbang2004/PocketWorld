package dev.pocketengine.features.local.runtime;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.local.project.ProjectSources;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;
import org.json.JSONObject;

/** One original job's bounded byte queue. No process launcher, durable task or recoverable FD. */
final class RuntimePipeInput {
    static final int MAX_PACKET=128*1024,MAX_PENDING=16,MAX_TICKETS=256,HEADER=16;
    static final long MAX_TOTAL=16*1024*1024;
    private final long limit;private long charged,sequence;private boolean closed,eofQueued;
    private final Map<String,Ticket> requests=new LinkedHashMap<>();private final ArrayDeque<Ticket> queue=new ArrayDeque<>();
    static final class Ticket {
        final String operation,digest;final long sequence;final int requested;final boolean eof;final ProjectRuntime.LiveGate gate;
        byte[] packet;boolean dequeued,acknowledged,cancelled,effects;int written,error;String state="pending";
        Ticket(String operation,String digest,long sequence,int requested,boolean eof,byte[] packet,ProjectRuntime.LiveGate gate){this.operation=operation;this.digest=digest;this.sequence=sequence;this.requested=requested;this.eof=eof;this.packet=packet;this.gate=gate;}
        JSONObject metadata()throws Exception{return new JSONObject().put("inputOperationId",operation).put("inputSequence",sequence).put("action",eof?"eof":"write").put("status",state).put("requestedBytes",requested).put("writtenBytes",written).put("nativeErrno",error).put("nativeAcknowledged",acknowledged).put("effectMayHaveOccurred",effects).put("stdinClosed",eof&&acknowledged&&error==0&&effects).put("inputTextIncluded",false).put("applicationConsumptionProven",false).put("replayAllowed",false);}
    }
    RuntimePipeInput(long limit){if(limit<1||limit>MAX_TOTAL)throw new IllegalArgumentException("runtime_pipe_total_budget_invalid");this.limit=limit;}
    Ticket offer(String operation,byte[] bytes,boolean eof,ProjectRuntime.LiveGate gate)throws Exception {
        Objects.requireNonNull(gate).check();
        if(operation==null||!operation.matches("[A-Za-z0-9_-]{1,80}")||bytes==null||bytes.length>MAX_PACKET||(!eof&&bytes.length==0)||(eof&&bytes.length!=0))throw new IllegalArgumentException("runtime_pipe_packet_invalid");
        byte[] frozen=bytes.clone();String digest=(eof?"eof:":"write:")+ProjectSources.sha256(frozen);
        synchronized(this){
            Ticket previous=requests.get(operation);if(previous!=null){if(!digest.equals(previous.digest))throw new SecurityException("runtime_pipe_original_request_changed");return previous;}
            if(closed||eofQueued||(!eof&&requests.size()>=MAX_TICKETS)||requests.values().stream().filter(t->!t.acknowledged&&!t.cancelled).count()>=MAX_PENDING||requests.values().stream().anyMatch(t->t.state.equals("unknown")))throw new SecurityException("runtime_pipe_closed_unresolved_or_full");
            long cost=eof?0:frozen.length;if(cost>limit-charged)throw new SecurityException("runtime_pipe_original_byte_budget_exhausted");
            byte[] packet=ByteBuffer.allocate(HEADER+frozen.length).order(ByteOrder.BIG_ENDIAN).putInt(eof?2:1).putLong(++sequence).putInt(frozen.length).put(frozen).array();
            Ticket ticket=new Ticket(operation,digest,sequence,frozen.length,eof,packet,gate);requests.put(operation,ticket);queue.addLast(ticket);charged+=cost;if(eof)eofQueued=true;notifyAll();return ticket;
        }
    }
    synchronized byte[] next(){if(closed||queue.isEmpty())return null;Ticket ticket=queue.removeFirst();ticket.dequeued=true;byte[] value=ticket.packet;ticket.packet=null;return value;}
    boolean current(long sequence)throws Exception {
        Ticket found=null;synchronized(this){for(Ticket ticket:requests.values())if(ticket.sequence==sequence&&ticket.dequeued&&!ticket.acknowledged&&!ticket.cancelled)found=ticket;}
        if(found==null)return false;found.gate.check();synchronized(this){return !closed&&!found.cancelled&&!found.acknowledged;}
    }
    synchronized JSONObject observed(long sequence,int written,int error,boolean effects)throws Exception {
        Ticket found=null;for(Ticket ticket:requests.values())if(ticket.sequence==sequence)found=ticket;
        if(found==null||!found.dequeued||found.acknowledged||written<0||written>found.requested||error<0||written>0&&!effects)throw new IllegalStateException("runtime_pipe_native_ack_invalid");
        found.acknowledged=true;found.written=written;found.error=error;found.effects=effects;
        found.state=error==0&&written==found.requested&&effects?"delivered":written==0&&error>0&&!effects?"not_dispatched":"unknown";
        notifyAll();return metadata(found);
    }
    private JSONObject metadata(Ticket ticket)throws Exception{return ticket.metadata().put("inputBytesCharged",charged).put("inputByteLimit",limit);}
    JSONObject await(Ticket ticket,RunControl original,long maxMillis)throws Exception {
        if(maxMillis<1||maxMillis>20000)throw new IllegalArgumentException("runtime_pipe_wait_budget_invalid");long until=System.nanoTime()+maxMillis*1000000L;
        try {while(true){original.check();synchronized(this){if(!ticket.state.equals("pending"))return metadata(ticket);long left=until-System.nanoTime();if(left<=0){cancel(ticket);return metadata(ticket);}wait(Math.max(1,Math.min(25,left/1000000L)));}}}
        catch(Exception failure){synchronized(this){cancel(ticket);}throw failure;}
    }
    private void cancel(Ticket ticket){if(ticket.acknowledged)return;ticket.cancelled=true;ticket.effects|=ticket.dequeued;ticket.state=ticket.dequeued?"unknown":"not_dispatched";if(!ticket.dequeued){queue.remove(ticket);ticket.packet=null;}notifyAll();}
    synchronized JSONObject query(String operation)throws Exception {Ticket ticket=requests.get(operation);if(ticket==null)throw new IllegalArgumentException("runtime_pipe_original_input_unknown");return metadata(ticket);}
    synchronized void close(){closed=true;queue.clear();for(Ticket ticket:requests.values())if(!ticket.acknowledged){ticket.cancelled=true;ticket.effects|=ticket.dequeued;ticket.state=ticket.dequeued?"unknown":"not_dispatched";ticket.packet=null;}notifyAll();}
}
