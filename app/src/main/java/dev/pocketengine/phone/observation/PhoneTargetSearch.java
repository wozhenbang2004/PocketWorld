package dev.pocketengine.phone.observation;

import java.util.Locale;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Current-page selection only. Recognized words retain their real OCR references and never become tree nodes. */
final class PhoneTargetSearch {
    private PhoneTargetSearch() { }

    static boolean canUseOcr(JSONObject selector){return selector.length()==1&&selector.has("text");}

    static JSONObject tree(JSONObject actual,JSONObject container,JSONObject selector)throws Exception {
        return PhoneTreeQuery.query(actual,new JSONObject().put("observationId",actual.getString("observationId"))
                .put("selector",new JSONObject(selector.toString()).put("parentId",container.getString("id"))).put("limit",40));
    }

    static boolean needsOcr(JSONObject actual,JSONObject container,JSONObject selector,String source)throws Exception {
        return "ocr".equals(source)||"auto".equals(source)&&canUseOcr(selector)&&tree(actual,container,selector).optInt("total")==0;
    }

    static JSONObject read(JSONObject actual,JSONObject container,JSONObject selector,String source)throws Exception {
        JSONObject nativeResult=tree(actual,container,selector).put("source","accessibility");
        if("tree".equals(source)||!"ocr".equals(source)&&nativeResult.optInt("total")>0)return nativeResult;
        if(!canUseOcr(selector))return nativeResult.put("ocrFallbackAvailable",false).put("ocrReason","selector_requires_native_attributes");
        JSONObject ocr=actual.optJSONObject("ocr");
        if(ocr==null||actual.optBoolean("changedDuringRead")){
            if("ocr".equals(source))return PhoneTreeExtract.unavailable("find_ocr_unavailable");
            return nativeResult.put("ocrFallbackAvailable",false).put("ocrReason",actual.optString("localOcrReason","local_ocr_unavailable"));
        }
        int width=ocr.optInt("rasterWidth"),height=ocr.optInt("rasterHeight");
        if(width<=0||height<=0||!actual.getString("observationId").equals(ocr.optString("observationId")))return PhoneTreeExtract.unavailable("find_ocr_source_changed");
        JSONObject bounds=container.getJSONObject("bounds");String needle=selector.getString("text").toLowerCase(Locale.ROOT);
        JSONArray matches=new JSONArray(),lines=ocr.optJSONArray("lines"),words=ocr.optJSONArray("words");int total=0;
        Set<Integer> matchedLines=new HashSet<>();
        for(int i=0;lines!=null&&i<lines.length();i++){
            JSONObject line=lines.getJSONObject(i);JSONArray box=line.getJSONArray("boundsPixels");
            if(!inside(box,bounds,width,height)||!line.getString("text").toLowerCase(Locale.ROOT).contains(needle))continue;
            total++;matchedLines.add(line.getInt("line"));if(matches.length()>=40)continue;
            JSONObject match=new JSONObject(line.toString()).put("source","ocr").put("evidenceOnly",true)
                    .put("nodeId",JSONObject.NULL).put("actionNodeId",JSONObject.NULL).put("recognitionMayBeWrong",true);
            JSONArray targets=new JSONArray();
            for(int j=0;words!=null&&j<words.length()&&targets.length()<20;j++){
                JSONObject word=words.getJSONObject(j);
                if(word.optInt("line",-1)==line.optInt("line",-2)&&word.has("textTargetId")
                        &&inside(word.getJSONArray("boundsPixels"),bounds,width,height)
                        &&word.getString("text").toLowerCase(Locale.ROOT).contains(needle))targets.put(new JSONObject(word.toString()));
            }
            match.put("matchingWordTargets",targets);matches.put(match);
        }
        // OCR may join adjacent columns into a single line. Its real word boxes can still locate a word in this container.
        for(int i=0;words!=null&&i<words.length();i++){
            JSONObject word=words.getJSONObject(i);
            if(matchedLines.contains(word.optInt("line",-1))||!inside(word.getJSONArray("boundsPixels"),bounds,width,height)
                    ||!word.getString("text").toLowerCase(Locale.ROOT).contains(needle))continue;
            total++;if(matches.length()<40)matches.put(new JSONObject(word.toString()).put("source","ocr_word")
                    .put("evidenceOnly",true).put("nodeId",JSONObject.NULL).put("actionNodeId",JSONObject.NULL).put("recognitionMayBeWrong",true));
        }
        JSONObject observation=PhoneTreeExtract.actionObservation(actual,container.getString("id"));
        return PhoneTreeExtract.base().put("ok",true).put("status","read").put("source","ocr")
                .put("observationId",actual.getString("observationId")).put("matches",matches).put("total",total).put("returned",matches.length())
                .put("truncated",ocr.optBoolean("truncated")||total>matches.length()).put("partial",true).put("selectionComplete",false)
                .put("coverage","recognized_text_in_current_native_container").put("recognitionMayBeWrong",true)
                .put("coordinateSpace",ocr.optString("coordinateSpace")).put("rasterWidth",width).put("rasterHeight",height)
                .put("observation",observation).put("nativeMatchCount",nativeResult.optInt("total"));
    }

    static String fingerprint(JSONObject actual,JSONObject container)throws Exception {
        JSONArray values=new JSONArray();Map<String,JSONObject> nodes=PhoneTreeExtract.index(actual);
        for(JSONObject node:nodes.values())if(!node.optBoolean("password")&&node.optBoolean("visible",true)
                &&PhoneTreeExtract.descendant(node,container.getString("id"),nodes)){
            JSONObject row=new JSONObject();
            for(String key:new String[]{"path","bounds","text","description","hint","selected","checked"})if(node.has(key))row.put(key,node.get(key));
            values.put(row);
        }
        JSONObject ocr=actual.optJSONObject("ocr");JSONArray lines=ocr==null?null:ocr.optJSONArray("lines");
        if(ocr!=null&&ocr.optInt("rasterWidth")>0&&ocr.optInt("rasterHeight")>0)for(int i=0;lines!=null&&i<lines.length();i++){
            JSONObject line=lines.getJSONObject(i);
            if(inside(line.getJSONArray("boundsPixels"),container.getJSONObject("bounds"),ocr.getInt("rasterWidth"),ocr.getInt("rasterHeight")))
                values.put(new JSONObject().put("ocrText",line.getString("text")).put("boundsPixels",line.getJSONArray("boundsPixels")));
        }
        JSONArray words=ocr==null?null:ocr.optJSONArray("words");
        if(ocr!=null&&ocr.optInt("rasterWidth")>0&&ocr.optInt("rasterHeight")>0)for(int i=0;words!=null&&i<words.length();i++){
            JSONObject word=words.getJSONObject(i);
            if(inside(word.getJSONArray("boundsPixels"),container.getJSONObject("bounds"),ocr.getInt("rasterWidth"),ocr.getInt("rasterHeight")))
                values.put(new JSONObject().put("ocrWord",word.getString("text")).put("boundsPixels",word.getJSONArray("boundsPixels")));
        }
        return dev.pocketengine.store.TaskStore.capabilityFingerprint(new JSONObject().put("content",values));
    }

    private static boolean inside(JSONArray box,JSONObject b,int width,int height)throws Exception {
        return box.length()==4&&box.getDouble(0)*1000/width>=b.getInt("left")&&box.getDouble(2)*1000/width<=b.getInt("right")
                &&box.getDouble(1)*1000/height>=b.getInt("top")&&box.getDouble(3)*1000/height<=b.getInt("bottom");
    }
}
