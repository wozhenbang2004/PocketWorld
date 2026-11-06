package dev.pocketengine.store;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;

/** One transport for large stored JSON text. It neither owns transactions nor
 * authorizes access: callers must select/authorize the original row in the same
 * active transaction. Only fixed production columns are addressable. */
final class TaskStoreTextReader {
    private static final int CHUNK_BYTES=65536;
    enum Column {
        TASK_DOCUMENT("tasks","task_id","document",false),
        RUN_CHECKPOINT("runs","run_id","checkpoint",false),
        RUN_GRANT("runs","run_id","grant_json",false),
        OPERATION_ARGUMENTS("operations","operation_id","arguments",false),
        OPERATION_RECEIPT("operations","operation_id","receipt",true),
        PHONE_STOP_CHILDREN("phone_stop_batches","batch_id","children_json",false);
        final String table,key,field;final boolean nullable;
        Column(String table,String key,String field,boolean nullable){this.table=table;this.key=key;this.field=field;this.nullable=nullable;}
    }
    private TaskStoreTextReader() {}

    static String read(SQLiteDatabase db,Column column,String identity) {
        if(!db.inTransaction())throw new IllegalStateException("原文分块读取需要同一活动事务");
        String table=column.table,key=column.key,field=column.field;
        // Reassemble bytes before decoding: a chunk boundary can split a UTF-8
        // code point or UTF-16 surrogate. The database encoding is authoritative.
        // This bounds CursorWindow transfer, not the final Java String's heap.
        long size;
        try(Cursor row=db.rawQuery("SELECT length(CAST("+field+" AS BLOB)) FROM "+table+" WHERE "+key+"=?",new String[]{identity})){
            if(!row.moveToFirst())throw new IllegalStateException("原文记录在同一快照中缺失");
            if(row.isNull(0)){
                if(column.nullable)return null;
                throw new IllegalStateException("原文必需字段为空");
            }
            size=row.getLong(0);
        }
        if(size<0||size>Integer.MAX_VALUE)throw new IllegalStateException("原文超过当前字符串可表示范围，未截断");
        // The transaction fixes the byte length. Allocate one exact buffer instead
        // of geometric growth plus toByteArray(), which held duplicate full bodies
        // during decode. This still materializes one whole document; it is not a
        // promise of streaming JSON or protection from an arbitrarily large row.
        byte[] bytes=new byte[(int)size];
        for(long offset=0;offset<size;){
            int wanted=(int)Math.min(CHUNK_BYTES,size-offset);
            try(Cursor row=db.rawQuery("SELECT substr(CAST("+field+" AS BLOB),?,?) FROM "+table+" WHERE "+key+"=?",new String[]{Long.toString(offset+1),Integer.toString(wanted),identity})){
                if(!row.moveToFirst()||row.isNull(0))throw new IllegalStateException("原文分块在同一快照中缺失");
                byte[] chunk=row.getBlob(0);if(chunk.length!=wanted)throw new IllegalStateException("原文分块长度改变，未返回不完整正文");
                System.arraycopy(chunk,0,bytes,(int)offset,chunk.length);offset+=chunk.length;
            }
        }
        String encoding;
        try(Cursor row=db.rawQuery("PRAGMA encoding",null)){if(!row.moveToFirst())throw new IllegalStateException("原数据库编码不可核对");encoding=row.getString(0);}
        Charset charset;
        if("UTF-8".equalsIgnoreCase(encoding))charset=java.nio.charset.StandardCharsets.UTF_8;
        else if("UTF-16le".equalsIgnoreCase(encoding))charset=java.nio.charset.StandardCharsets.UTF_16LE;
        else if("UTF-16be".equalsIgnoreCase(encoding))charset=java.nio.charset.StandardCharsets.UTF_16BE;
        else throw new IllegalStateException("原数据库编码不受支持，未替换正文");
        try{return charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();}
        catch(java.nio.charset.CharacterCodingException malformed){throw new IllegalStateException("原文编码损坏，未替换或截断正文",malformed);}
    }
}
