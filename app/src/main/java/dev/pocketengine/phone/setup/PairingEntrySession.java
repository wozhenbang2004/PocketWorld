package dev.pocketengine.phone.setup;

import java.util.UUID;
import java.util.function.LongSupplier;
import org.json.JSONObject;

/** A short, owner-created input window. It stores no pairing code or reusable authorization. */
public final class PairingEntrySession {
    public record Entry(String id,String host,int port,long expiresAt) { }
    private final LongSupplier clock;private Entry active;
    public PairingEntrySession(LongSupplier monotonicClock){clock=monotonicClock;}
    public synchronized Entry begin(String localHost,int pairingPort){if(localHost==null||localHost.isBlank()||pairingPort<1||pairingPort>65535)throw new IllegalArgumentException("invalid_local_pairing_endpoint");active=new Entry(UUID.randomUUID().toString(),localHost,pairingPort,clock.getAsLong()+120000);return active;}
    public synchronized JSONObject submit(String sessionId,String code)throws Exception {
        Entry entry=active;if(entry==null||!entry.id.equals(sessionId))throw new SecurityException("pairing_entry_expired");if(clock.getAsLong()>=entry.expiresAt){active=null;throw new SecurityException("pairing_entry_expired");}
        if(code==null||!code.matches("[0-9]{6}"))throw new IllegalArgumentException("pairing_code_requires_six_digits");active=null;
        return new JSONObject().put("host",entry.host).put("pairingPort",entry.port).put("pairingCode",code);
    }
    public synchronized void cancel(){active=null;}
    public synchronized JSONObject snapshot()throws Exception {Entry entry=active;if(entry!=null&&clock.getAsLong()>=entry.expiresAt){active=null;entry=null;}return new JSONObject().put("active",entry!=null).put("sessionId",entry==null?JSONObject.NULL:entry.id).put("remainingMs",entry==null?0:Math.max(0,entry.expiresAt-clock.getAsLong())).put("storesPairingCode",false);}
}
