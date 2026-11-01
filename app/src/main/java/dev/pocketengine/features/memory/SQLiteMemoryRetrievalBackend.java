package dev.pocketengine.features.memory;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.json.JSONObject;

/** In-memory derived index. No file, task state, source authority, model calls or new dependency. */
public final class SQLiteMemoryRetrievalBackend implements MemoryRetrievalBackend {
    private final SQLiteDatabase db;
    private final String mode;
    private final boolean fts5Probed;
    private final LexicalMemoryRetrievalBackend lexical=new LexicalMemoryRetrievalBackend("non_token_query");
    private String generation="";
    private int indexedCount;
    private boolean closed;

    /** Real CREATE/probe decides availability; API level and SQLite version never imply FTS5. */
    public static MemoryRetrievalBackend create(){return create(true);}
    /** Native policy may prefer proven FTS4 on a target driver; false never claims FTS5 was probed. */
    public static MemoryRetrievalBackend create(boolean preferFts5){
        SQLiteDatabase db=null;
        try{db=SQLiteDatabase.create(null);return new SQLiteMemoryRetrievalBackend(db,preferFts5);}
        catch(SQLiteException unavailable){if(db!=null)db.close();return new LexicalMemoryRetrievalBackend("platform_fts_unavailable");}
    }
    private SQLiteMemoryRetrievalBackend(SQLiteDatabase db,boolean preferFts5){
        this.db=db;fts5Probed=preferFts5;String selected="";
        if(preferFts5)try{db.execSQL("CREATE VIRTUAL TABLE memory_fts USING fts5(terms)");selected="fts5";}catch(SQLiteException unsupported){}
        if(selected.isEmpty()){db.execSQL("CREATE VIRTUAL TABLE memory_fts USING fts4(terms)");selected="fts4";}
        mode=selected;db.execSQL("CREATE TABLE memory_refs(rowid INTEGER PRIMARY KEY, record_id TEXT NOT NULL, revision INTEGER NOT NULL, owner_id TEXT NOT NULL, space_id TEXT NOT NULL, updated_at INTEGER NOT NULL)");
    }
    @Override public synchronized void synchronize(List<Document> documents)throws Exception {
        checkOpen();if(documents.size()>1000)throw new IllegalArgumentException("派生索引最多1000条");
        StringBuilder signature=new StringBuilder();long tokenChars=0;List<String> text=new ArrayList<>();
        for(Document document:documents){
            signature.append(document.ownerId).append('\0').append(document.spaceId).append('\0').append(document.id).append('\0').append(document.revision).append('\n');
            String tokens=indexTerms(document.text);tokenChars+=tokens.length();if(tokenChars>8_000_000)throw new IllegalStateException("派生索引超过字符预算，须使用显式词面降级");text.add(tokens);
        }
        String next=signature.toString();
        if(next.equals(generation)&&count("memory_refs")==documents.size()&&count("memory_fts")==documents.size())return;
        db.beginTransaction();try{
            db.execSQL("DELETE FROM memory_fts");db.execSQL("DELETE FROM memory_refs");
            for(int i=0;i<documents.size();i++){
                Document d=documents.get(i);long row=i+1L;
                db.execSQL("INSERT INTO memory_refs(rowid,record_id,revision,owner_id,space_id,updated_at) VALUES(?,?,?,?,?,?)",new Object[]{row,d.id,d.revision,d.ownerId,d.spaceId,d.updatedAt});
                db.execSQL("INSERT INTO memory_fts(rowid,terms) VALUES(?,?)",new Object[]{row,text.get(i)});
            }
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
        generation=next;indexedCount=documents.size();lexical.synchronize(documents);
    }
    @Override public synchronized List<Match> find(String owner,String space,String query,int limit)throws Exception {
        checkOpen();String normalized=LexicalMemoryRetrievalBackend.normalize(query);List<String> terms=LexicalMemoryRetrievalBackend.terms(normalized);
        if(!normalized.isEmpty()&&terms.isEmpty())return lexical.find(owner,space,normalized,limit);
        String sql;String[] args;
        if(normalized.isEmpty()){
            sql="SELECT record_id,revision FROM memory_refs WHERE owner_id=? AND space_id=? ORDER BY updated_at DESC LIMIT ?";args=new String[]{owner,space,String.valueOf(limit)};
        }else{
            StringBuilder match=new StringBuilder();for(String term:new LinkedHashSet<>(terms)){if(match.length()>0)match.append(" OR ");match.append('"').append(term.replace("\"","\"\"")).append('"');}
            sql="SELECT r.record_id,r.revision,r.updated_at"+("fts4".equals(mode)?",matchinfo(memory_fts,'pcnalx')":"")+" FROM memory_fts JOIN memory_refs r ON r.rowid=memory_fts.rowid WHERE memory_fts MATCH ? AND r.owner_id=? AND r.space_id=? ORDER BY "+("fts5".equals(mode)?"bm25(memory_fts), ":"")+"r.updated_at DESC LIMIT ?";
            // FTS4 must rank the whole bounded match set before taking the requested limit.
            args=new String[]{match.toString(),owner,space,String.valueOf("fts4".equals(mode)?1000:limit)};
        }
        List<Match> result=new ArrayList<>();List<Scored> scored=new ArrayList<>();
        try(Cursor cursor=db.rawQuery(sql,args)){while(cursor.moveToNext()){
            Match hit=new Match(cursor.getString(0),cursor.getLong(1));
            if(!normalized.isEmpty()&&"fts4".equals(mode))scored.add(new Scored(hit,bm25(cursor.getBlob(3)),cursor.getLong(2)));else result.add(hit);
        }}
        if(!scored.isEmpty()){
            scored.sort(Comparator.comparingDouble((Scored hit)->hit.score).reversed().thenComparing(Comparator.comparingLong((Scored hit)->hit.updatedAt).reversed()));
            for(int i=0;i<Math.min(limit,scored.size());i++)result.add(scored.get(i).match);
        }
        return result;
    }
    private static String indexTerms(String text){
        String normalized=LexicalMemoryRetrievalBackend.normalize(text);List<String> terms=new ArrayList<>(LexicalMemoryRetrievalBackend.terms(normalized));
        // A single Han query must also find that character inside a longer Han word.
        for(String part:normalized.split("[^\\p{L}\\p{N}]+"))if(part.codePointCount(0,part.length())>1){
            part.codePoints().filter(c->Character.UnicodeScript.of(c)==Character.UnicodeScript.HAN).forEach(c->terms.add(new String(Character.toChars(c))));
            if(part.codePoints().anyMatch(c->Character.UnicodeScript.of(c)==Character.UnicodeScript.HAN)){
                StringBuilder word=new StringBuilder();
                for(int point:part.codePoints().toArray())if(Character.UnicodeScript.of(point)==Character.UnicodeScript.HAN){if(word.length()>0){terms.add(word.toString());word.setLength(0);}}else word.appendCodePoint(point);
                if(word.length()>0)terms.add(word.toString());
            }
        }
        return String.join(" ",terms);
    }
    /** SQLite's documented p/c/n/a/l/x unsigned machine-order statistics, single indexed column. */
    private static double bm25(byte[] blob){
        if(blob==null||blob.length<20||blob.length%4!=0)throw new SQLiteException("无效FTS4统计");
        ByteBuffer values=ByteBuffer.wrap(blob).order(ByteOrder.nativeOrder());long phrases=unsigned(values.getInt()),columns=unsigned(values.getInt()),documents=unsigned(values.getInt());
        if(columns!=1||phrases>500||blob.length!=4*(5+3*phrases)||documents<1)throw new SQLiteException("FTS4统计不匹配");
        double average=Math.max(1,unsigned(values.getInt())),length=unsigned(values.getInt()),score=0;
        for(int i=0;i<phrases;i++){
            double frequency=unsigned(values.getInt());values.getInt();double containing=unsigned(values.getInt());
            if(containing>documents)throw new SQLiteException("FTS4来源统计不匹配");
            if(frequency>0){double idf=Math.log1p((documents-containing+.5)/(containing+.5));score+=idf*frequency*2.2/(frequency+1.2*(.25+.75*length/average));}
        }
        return score;
    }
    private static long unsigned(int value){return Integer.toUnsignedLong(value);}
    private static final class Scored{final Match match;final double score;final long updatedAt;Scored(Match match,double score,long updatedAt){this.match=match;this.score=score;this.updatedAt=updatedAt;}}
    private int count(String table){try(Cursor c=db.rawQuery("SELECT count(*) FROM "+table,null)){c.moveToFirst();return c.getInt(0);}}
    @Override public synchronized void invalidate(){checkOpen();generation="";indexedCount=0;lexical.invalidate();db.execSQL("DELETE FROM memory_fts");db.execSQL("DELETE FROM memory_refs");}
    @Override public synchronized JSONObject status()throws Exception{return new JSONObject().put("backend","sqlite_"+mode).put("fts5",mode.equals("fts5")).put("fts4",mode.equals("fts4")).put("fts5Probed",fts5Probed).put("fallbackReason",mode.equals("fts4")?(fts5Probed?"fts5_probe_failed":"native_fts4_policy"):"").put("rank",mode.equals("fts5")?"sqlite_bm25":"bm25_from_matchinfo").put("indexedCount",indexedCount).put("indexAuthority",false).put("persisted",false).put("closed",closed);}
    private void checkOpen(){if(closed)throw new IllegalStateException("派生检索索引已关闭");}
    @Override public synchronized void close(){if(!closed){closed=true;generation="";indexedCount=0;lexical.close();db.close();}}
}
