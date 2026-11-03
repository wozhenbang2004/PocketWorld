package dev.pocketengine.features.skills;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Checks native read ranges for one exact package; it grants no read or update authority. */
public final class SkillReadCoverage {
    private SkillReadCoverage(){}
    public static void requireComplete(JSONArray reads,String run,int epoch,JSONObject target,JSONArray files)throws Exception {
        if(reads==null||reads.length()>200||files==null||files.length()==0||files.length()>41||epoch<1)throw new SecurityException("原技能全文读取证明不完整");
        if(!target.optString("id").matches("[a-f0-9-]{36}")||integer(target,"revision",1)>Integer.MAX_VALUE||integer(target,"executionGeneration",0)<0||!target.optString("digest").matches("[a-f0-9]{64}")||!target.optString("skillScopeFingerprint").matches("[a-f0-9]{64}"))throw new SecurityException("原技能选择身份无效");
        Set<String> paths=new HashSet<>();
        for(int index=0;index<files.length();index++){
            JSONObject file=files.getJSONObject(index);String path=file.getString("path");long chars=integer(file,"chars",0);if(chars>Integer.MAX_VALUE||!paths.add(path))throw new SecurityException("原技能文件列表不可核对");
            List<long[]> ranges=new ArrayList<>();
            for(int i=0;i<reads.length();i++){
                JSONObject read=reads.getJSONObject(i),binding=read.optJSONObject("package");if(!run.equals(read.optString("runId"))||!path.equals(read.optString("path"))||binding==null)continue;if(epoch!=integer(read,"epoch",1))continue;
                boolean matches=true;for(String name:new String[]{"id","digest","skillScopeFingerprint"})if(!(binding.opt(name) instanceof String)||!target.getString(name).equals(binding.getString(name)))matches=false;
                for(String name:new String[]{"revision","executionGeneration"})if(integer(target,name,0)!=integer(binding,name,0))matches=false;
                if(matches){long start=integer(read,"start",0),end=integer(read,"end",0);if(end<start||end>chars)throw new SecurityException("原技能页范围越界");ranges.add(new long[]{start,end});}
            }
            ranges.sort(Comparator.comparingLong(range->range[0]));long covered=0;boolean provided=false;
            for(long[] range:ranges){if(range[0]>covered)break;provided=true;covered=Math.max(covered,range[1]);}
            if(!provided||covered<chars)throw new SecurityException("原技能文件尚未完整回读："+path);
        }
    }
    private static long integer(JSONObject row,String name,long minimum)throws Exception {Object value=row.get(name);if(!(value instanceof Integer||value instanceof Long)||((Number)value).longValue()<minimum)throw new SecurityException("原技能范围数字无效");return ((Number)value).longValue();}
}
