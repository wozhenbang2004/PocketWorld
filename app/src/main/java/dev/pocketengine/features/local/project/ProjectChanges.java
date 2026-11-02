package dev.pocketengine.features.local.project;

import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;

/** Model-only pages of the native change list; owner views retain their full list. */
public final class ProjectChanges {
    public static final int MAX_ENTRIES=100,ENTRY_BYTES=12000;
    private ProjectChanges() {}
    public static void requireSame(JSONObject before,JSONObject after)throws Exception {
        for(String key:new String[]{"checkoutId","projectId","sourceDigest","baselineIdentity"})
            if(!before.getString(key).equals(after.getString(key)))throw new SecurityException("project_changes cursor失效：源码或原项目基线已变化，请不带cursor重新读取");
    }
    public static JSONObject page(JSONObject full,JSONObject input)throws Exception {
        boolean changedOnly=input.optBoolean("changedOnly",true);int limit=input.optInt("limit",40);
        if(limit<1||limit>MAX_ENTRIES)throw new IllegalArgumentException("变化清单每页须为1至100项");
        JSONObject cursor=input.optJSONObject("cursor");int start=0;
        if(cursor!=null){requireSame(full,cursor);if(cursor.getBoolean("changedOnly")!=changedOnly)throw new SecurityException("project_changes cursor查询范围已变化，请重新读取");start=cursor.getInt("offset");}
        JSONArray eligible=new JSONArray(),all=full.getJSONArray("files");
        for(int i=0;i<all.length();i++){JSONObject entry=all.getJSONObject(i);if(!changedOnly||!"unchanged".equals(entry.getString("change")))eligible.put(entry);}
        if(start<0||start>eligible.length())throw new IllegalArgumentException("变化清单cursor越界");
        JSONArray files=new JSONArray();int end=start,bytes=2;
        while(end<eligible.length()&&files.length()<limit){JSONObject entry=eligible.getJSONObject(end);int size=entry.toString().getBytes(StandardCharsets.UTF_8).length+1;if(bytes+size>ENTRY_BYTES)break;files.put(entry);bytes+=size;end++;}
        if(end==start&&start<eligible.length())throw new IllegalStateException("单项变化超过分页字节预算，未返回零进度cursor");
        boolean complete=end==eligible.length();JSONObject next=new JSONObject();
        for(String key:new String[]{"checkoutId","projectId","sourceDigest","baselineIdentity"})next.put(key,full.getString(key));
        next.put("changedOnly",changedOnly).put("offset",end);
        JSONObject result=new JSONObject(full.toString()).put("files",files).put("changedOnly",changedOnly)
                .put("offset",start).put("totalEntries",eligible.length()).put("returnedCount",files.length())
                .put("omittedCount",eligible.length()-end).put("unchangedExcludedCount",changedOnly?all.length()-eligible.length():0)
                .put("complete",complete).put("hasMore",!complete).put("nextCursor",complete?JSONObject.NULL:next)
                .put("atomicReadFence",false);
        return result;
    }
}
