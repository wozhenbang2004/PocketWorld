package dev.pocketengine.features.memory;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Native-only read restriction, never a replacement owner identity or a JSON grant. The original
 * TaskStore/member call issues implementations. Empty member sets allow nothing; ordinary owner
 * calls keep their existing no-audience entry point. checkCurrent must not enter Book, network,
 * filesystem or the coordinator monitor while the original TaskStore/Book admission is held. */
public interface ReadAudience {
    String ownerId();
    String spaceId();
    String audienceId();
    long revision();
    JSONArray allowedRecords() throws Exception;
    JSONArray allowedSources() throws Exception;
    void checkCurrent() throws Exception;

    int MAX_RECORDS=64,MAX_SOURCES=64,MAX_SOURCE_CHARS=24000;

    /** Structural validation only. The native issuer/currentness check remains the authority. */
    static JSONArray checkedRecords(JSONArray supplied)throws Exception{
        if(supplied==null||supplied.length()>MAX_RECORDS)throw new IllegalArgumentException("成员记忆版本清单缺失或超过64项");
        JSONArray result=new JSONArray();Set<String> ids=new HashSet<>();
        for(int i=0;i<supplied.length();i++){
            JSONObject row=supplied.getJSONObject(i);keys(row,Set.of("id","revision"));Object raw=row.opt("id");
            if(!(raw instanceof String)||((String)raw).isEmpty()||((String)raw).length()>240)throw new IllegalArgumentException("成员记忆标识无效");
            String id=(String)raw;MemoryBook.validateUnicode(id);long revision=SourceRef.integer(row,"revision",1);
            if(!ids.add(id))throw new IllegalArgumentException("成员记忆清单不能以重复标识覆盖版本");
            result.put(new JSONObject().put("id",id).put("revision",revision));
        }
        return result;
    }

    /** A selected SourceRef permits this span only, not arbitrary/full original text. */
    static JSONArray checkedSources(JSONArray supplied,String owner,String space)throws Exception{
        MemoryBook.Scope scope=MemoryBook.Scope.from(new JSONObject().put("ownerId",owner).put("spaceId",space));
        if(supplied==null||supplied.length()>MAX_SOURCES)throw new IllegalArgumentException("成员原文范围清单缺失或超过64项");
        JSONArray result=new JSONArray();Set<String> spans=new HashSet<>();
        for(int i=0;i<supplied.length();i++){
            JSONObject row=supplied.getJSONObject(i);keys(row,Set.of("sourceRef","offset","chars","unit"));
            JSONObject raw=row.getJSONObject("sourceRef");keys(raw,"workspace_file".equals(raw.optString("kind"))?Set.of("kind","sourceId","revision","ownerId","spaceId","workspace"):Set.of("kind","sourceId","revision","ownerId","spaceId"));SourceRef source=SourceRef.fromJson(raw);
            if(!source.belongsTo(scope)||!"UTF-16".equals(row.opt("unit")))throw new SecurityException("成员原文来源不能更换认证空间或字符单位");
            long offset=SourceRef.integer(row,"offset",0),chars=SourceRef.integer(row,"chars",1);
            if(offset>Integer.MAX_VALUE||chars>MAX_SOURCE_CHARS||offset+chars>Integer.MAX_VALUE)throw new IllegalArgumentException("成员原文范围超过可核对上限");
            String key=source.key()+"\n"+source.revision+"\n"+offset+"\n"+chars;if(!spans.add(key))throw new IllegalArgumentException("成员原文范围不能重复");
            result.put(new JSONObject().put("sourceRef",source.toJson()).put("offset",offset).put("chars",chars).put("unit","UTF-16"));
        }
        return result;
    }

    private static void keys(JSONObject row,Set<String> expected){
        if(row==null||row.length()!=expected.size())throw new IllegalArgumentException("成员读取选择字段不完整");
        for(Iterator<String> it=row.keys();it.hasNext();)if(!expected.contains(it.next()))throw new IllegalArgumentException("成员读取选择含未知字段");
    }
}
