package dev.pocketengine.store;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.JsonReader;
import android.util.JsonToken;
import dev.pocketengine.core.ToolOutcome;
import java.io.IOException;
import java.io.Reader;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONObject;

/** Original operation receipt storage. TaskStore retains its monitor, transaction,
 * native-preservation chain and task/run/epoch authority; this class grants none. */
final class TaskOperationReceipts {
    private static final String MIGRATION="tool_outcome_uncertainty_v2";
    private static final int INLINE_BYTES=1024*1024;
    private static final Set<String> UNCERTAINTY_FIELDS=Set.of("ok","accepted","terminal","requiresReconciliation","reconciliationRequired","requiresPause","requiresOwnerReturn","status","state","code","error","effectStatus","dispatchStatus","exitCode","nextObservationAction");
    private TaskOperationReceipts(){}

    static final class Saved {
        final ToolOutcome outcome;
        final String state;
        private final String receipt;
        private Saved(JSONObject actual,String nativeState){
            outcome=ToolOutcome.classify(actual);
            state=outcome.requiresReconciliation()?"unknown":nativeState==null?"receipt":nativeState;
            receipt=actual.toString();
        }
        JSONObject receipt()throws Exception{return new JSONObject(receipt);}
    }

    /** The caller supplies a nativeState only after original-row liveness proof.
     * A positive running acknowledgement cannot override explicit uncertainty. */
    static Saved save(SQLiteDatabase db,String task,String run,String operation,JSONObject canonical,String nativeState)throws Exception{
        if(nativeState!=null&&!Set.of("project_running","project_stopping").contains(nativeState))throw new IllegalArgumentException("Invalid native operation lifecycle");
        Saved saved=new Saved(canonical,nativeState);ContentValues value=new ContentValues();value.put("state",saved.state);value.put("receipt",saved.receipt);
        if(db.update("operations",value,"task_id=? AND run_id=? AND operation_id=?",new String[]{task,run,operation})!=1)throw new IllegalStateException("动作收据不属于原任务执行");
        return saved;
    }

    /** One transactional compatibility pass. Preserve every original receipt and
     * identity; only restore an omitted unknown barrier. Resolved owner decisions
     * and native nonterminal rows are never rewritten or replayed. */
    static Set<String> restoreLegacyUncertainty(SQLiteDatabase db)throws Exception{
        try(Cursor marker=db.rawQuery("SELECT 1 FROM meta WHERE key=?",new String[]{MIGRATION})){if(marker.moveToFirst())return Set.of();}
        Set<String> affected=new HashSet<>();
        try(Cursor rows=db.rawQuery("SELECT operation_id,task_id,CASE WHEN length(CAST(receipt AS BLOB))<=CAST(? AS INTEGER) THEN receipt END FROM operations WHERE state='receipt' AND receipt IS NOT NULL",new String[]{Integer.toString(INLINE_BYTES)})){
            while(rows.moveToNext()){
                boolean unknown;
                try{unknown=ToolOutcome.hasUncertaintyEvidence(rows.isNull(2)?largeReceiptFlags(db,rows.getString(0),rows.getString(1)):new JSONObject(rows.getString(2)));}
                catch(ReceiptReadFailure unavailable){throw unavailable;}
                catch(Exception malformed){unknown=true;}
                if(!unknown)continue;
                ContentValues value=new ContentValues();value.put("state","unknown");
                if(db.update("operations",value,"operation_id=? AND task_id=? AND state='receipt'",new String[]{rows.getString(0),rows.getString(1)})==1)affected.add(rows.getString(1));
            }
        }
        ContentValues marker=new ContentValues();marker.put("key",MIGRATION);marker.put("value","1");db.insertOrThrow("meta",null,marker);
        return affected;
    }

    /** A supported MCP result can exceed CursorWindow and the inline read limit.
     * Stream only top-level flags; a large, known body is not uncertainty evidence. */
    private static JSONObject largeReceiptFlags(SQLiteDatabase db,String operation,String task)throws Exception{
        JSONObject flags=new JSONObject();
        try(JsonReader reader=new JsonReader(new ReceiptReader(db,operation,task))){
            reader.beginObject();
            while(reader.hasNext()){
                String key=reader.nextName();
                if(!UNCERTAINTY_FIELDS.contains(key)){reader.skipValue();continue;}
                JsonToken token=reader.peek();
                if(token==JsonToken.BOOLEAN)flags.put(key,reader.nextBoolean());
                else if(token==JsonToken.STRING)flags.put(key,reader.nextString());
                else if(token==JsonToken.NUMBER)flags.put(key,reader.nextDouble());
                else if(token==JsonToken.NULL){reader.nextNull();flags.put(key,JSONObject.NULL);}
                else{reader.skipValue();flags.put(key,new org.json.JSONArray());}
            }
            reader.endObject();if(reader.peek()!=JsonToken.END_DOCUMENT)throw new IOException("Malformed original receipt");
        }
        return flags;
    }
    private static final class ReceiptReadFailure extends IOException{
        ReceiptReadFailure(Exception cause){super("Original operation receipt read failed",cause);}
    }
    private static final class ReceiptReader extends Reader{
        final SQLiteDatabase db;final String operation,task;long start=1;String chunk="";int offset;boolean ended;
        ReceiptReader(SQLiteDatabase db,String operation,String task){this.db=db;this.operation=operation;this.task=task;}
        @Override public int read(char[] target,int off,int len)throws IOException{
            if(len==0)return 0;
            if(offset==chunk.length()&&!ended)try(Cursor row=db.rawQuery("SELECT substr(receipt,?,4096) FROM operations WHERE operation_id=? AND task_id=?",new String[]{Long.toString(start),operation,task})){
                if(!row.moveToFirst()||row.isNull(0))throw new IOException("Original receipt disappeared");
                chunk=row.getString(0);offset=0;if(chunk.isEmpty())ended=true;else start+=chunk.codePointCount(0,chunk.length());
            }catch(Exception failure){throw new ReceiptReadFailure(failure);}
            if(ended)return -1;int count=Math.min(len,chunk.length()-offset);chunk.getChars(offset,offset+count,target,off);offset+=count;return count;
        }
        @Override public void close(){}
    }
}
