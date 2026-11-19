package dev.pocketengine.features.remote;

import java.util.HashSet;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Validate all selections before reading any bytes. One already-frozen original, within the existing limits. */
public final class RemoteResultPickup {
    private RemoteResultPickup() {}
    private static final Set<String> FIELDS=Set.of("path","bytes","fileVersion","sha256");
    private static SecurityException bad(){return new SecurityException("所选文件与原冻结清单不符，尚未领取。");}
    private static long bytes(JSONObject o)throws Exception {
        Object b=o.opt("bytes");if(!(b instanceof Integer||b instanceof Long))throw bad();
        long n=((Number)b).longValue();if(n<0||n>262144)throw bad();return n;
    }
    public static JSONArray validate(JSONObject original,JSONObject request)throws Exception {
        if(!original.getString("snapshotId").equals(request.opt("snapshotId"))||!original.getString("snapshotVersion").equals(request.opt("snapshotVersion"))||
                !original.getString("snapshotVersion").matches("[0-9a-f]{64}")||original.opt("immutableArtifact")!=Boolean.TRUE)throw bad();
        JSONArray manifest=original.getJSONArray("files"),choices=request.getJSONArray("files"),out=new JSONArray();
        if(choices.length()<1||choices.length()>8||manifest.length()>8)throw bad();
        HashSet<String> seen=new HashSet<>();long total=0;
        for(int i=0;i<choices.length();i++){
            JSONObject pick=choices.getJSONObject(i);if(pick.length()!=FIELDS.size())throw bad();
            for(String key:FIELDS)if(!pick.has(key))throw bad();
            Object value=pick.opt("path");if(!(value instanceof String path)||path.isBlank()||path.length()>1024||!seen.add(path))throw bad();
            JSONObject found=null;
            for(int j=0;j<manifest.length();j++)if(path.equals(manifest.getJSONObject(j).opt("path"))){if(found!=null)throw bad();found=manifest.getJSONObject(j);}
            if(found==null||bytes(pick)!=bytes(found))throw bad();
            for(String k:new String[]{"fileVersion","sha256"})if(!found.getString(k).matches("[0-9a-f]{64}")||!found.getString(k).equals(pick.opt(k)))throw bad();
            total+=bytes(found);if(total>524288)throw bad();
            out.put(new JSONObject().put("path",path).put("bytes",bytes(found)).put("fileVersion",found.get("fileVersion")).put("sha256",found.get("sha256")));
        }
        return out;
    }
}
