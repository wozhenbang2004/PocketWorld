package dev.pocketengine.runtime.providers;

import org.json.JSONObject;

/** Ordered projection of a validated provider event; never permission to execute a tool. */
public final class GatewayEvent {
    public enum Type { BEGIN, PART_BEGIN, TEXT_DELTA, REASONING_DELTA, TOOL_ARGS_DELTA, PART_END, USAGE, COMPLETE, ERROR }
    public final long eventSeq;
    public final String attemptCallId,itemId,toolCallId,delta,sourceProtocol,wireProtocol;
    public final Type type;
    /** Client-wire event; source accounting is retained separately without adding counters. */
    public final JSONObject nativeEvent;
    public final JSONObject usageObservation;
    /** Counter snapshots remain provider observations, never a second charge or additive delta. */
    public final String measurementKind;
    public final long observedAt;
    GatewayEvent(long seq,String attempt,Type type,String item,String tool,String delta,JSONObject raw,String sourceProtocol,String wireProtocol,JSONObject usage){
        this.sourceProtocol=sourceProtocol;this.wireProtocol=wireProtocol;usageObservation=usage;
        measurementKind=type==Type.USAGE?"cumulative":"";observedAt=System.currentTimeMillis();
        eventSeq=seq;attemptCallId=attempt;this.type=type;itemId=item;toolCallId=tool;this.delta=delta;nativeEvent=raw;
    }
}
