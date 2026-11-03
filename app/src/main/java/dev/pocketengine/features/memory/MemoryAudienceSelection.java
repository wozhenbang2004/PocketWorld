package dev.pocketengine.features.memory;

import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** One immutable native audience snapshot. It is not serialized into a permission or stored as a
 * second scope authority. The issuer's original TaskStore facts remain currentness authority. */
final class MemoryAudienceSelection {
    final ReadAudience original;
    private final MemoryBook.Scope scope;
    final String owner,space,id,records,sources;
    final long revision;
    final Map<String,Long> versions=new LinkedHashMap<>();
    private MemoryAudienceSelection(ReadAudience original,MemoryBook.Scope scope)throws Exception{
        this.original=original;this.scope=scope;original.checkCurrent();owner=original.ownerId();space=original.spaceId();id=original.audienceId();revision=original.revision();
        if(!scope.ownerId.equals(owner)||!scope.spaceId.equals(space)||id==null||id.isEmpty()||id.length()>240||revision<1)throw new SecurityException("成员资料范围不属于原认证空间");
        MemoryBook.validateUnicode(id);JSONArray allowed=ReadAudience.checkedRecords(original.allowedRecords());records=allowed.toString();
        sources=ReadAudience.checkedSources(original.allowedSources(),scope).toString();
        for(int i=0;i<allowed.length();i++){JSONObject row=allowed.getJSONObject(i);versions.put(row.getString("id"),row.getLong("revision"));}check();
    }
    static MemoryAudienceSelection capture(ReadAudience original,MemoryBook.Scope scope)throws Exception{return original==null?null:new MemoryAudienceSelection(original,scope);}
    void check()throws Exception{
        original.checkCurrent();
        if(!owner.equals(original.ownerId())||!space.equals(original.spaceId())||!id.equals(original.audienceId())||revision!=original.revision()
                ||!records.equals(ReadAudience.checkedRecords(original.allowedRecords()).toString())
                ||!sources.equals(ReadAudience.checkedSources(original.allowedSources(),scope).toString()))throw new SecurityException("原成员资料选择已变化，未继续返回旧正文");
    }
    boolean permits(String record,long revision){Long expected=versions.get(record);return expected!=null&&expected.longValue()==revision;}
    boolean permits(SourceRef source,int offset,int chars)throws Exception{
        if(offset<0||chars<0)return false;JSONArray ranges=new JSONArray(sources);
        for(int i=0;i<ranges.length();i++){JSONObject range=ranges.getJSONObject(i);
            if(source.equals(SourceRef.fromJson(range.getJSONObject("sourceRef")))&&offset>=range.getLong("offset")
                    &&(long)offset+chars<=range.getLong("offset")+range.getLong("chars"))return true;
        }return false;
    }
    JSONObject metadata()throws Exception{return new JSONObject().put("audienceId",id).put("revision",revision).put("ownerId",owner).put("spaceId",space).put("authority","native_original_scope_only");}
}
