package dev.pocketengine.features.teams;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.LinkedHashMap;
import java.util.Map;

/** Owner-only display of one actual native comparison. Never usable to approve or integrate files. */
public final class TeamOwnerFileReading {
    private final String team,plan;
    private final JSONObject description;
    private final Map<String,String> text=new LinkedHashMap<>();
    private final Map<String,JSONObject> entries=new LinkedHashMap<>();
    TeamOwnerFileReading(String teamRun,JSONObject captured)throws Exception{
        JSONObject original=captured.getJSONObject("plan");
        if(!"pocketworld.team.integration.v1".equals(original.getString("format"))||!teamRun.equals(original.getString("teamRunId")))throw new SecurityException("原团队文件计划身份不符");
        team=teamRun;plan=captured.getString("planHash");if(!plan.matches("[a-f0-9]{64}"))throw new SecurityException("原团队文件计划指纹缺失");
        JSONArray files=original.getJSONArray("files"),diffs=captured.getJSONArray("diffs"),rows=new JSONArray();
        if(files.length()!=diffs.length()||files.length()>32)throw new SecurityException("原文件目录与实际差异不一致");
        for(int i=0;i<files.length();i++){
            JSONObject file=files.getJSONObject(i),diff=diffs.getJSONObject(i);String id=file.getString("artifactId");
            if(entries.containsKey(id)||!team.equals(file.getString("teamRunId"))||!original.getString("targetProjectId").equals(file.getString("targetProjectId")))throw new SecurityException("原冻结文件重复或不属于这个项目");
            for(String key:new String[]{"artifactId","assignmentId","memberId","path"})if(!file.getString(key).equals(diff.getString(key)))throw new SecurityException("差异与所选冻结原件不符");
            String hash=file.getString("sha256");if(!hash.matches("[a-f0-9]{64}"))throw new SecurityException("原冻结文件指纹不完整");
            boolean deleted="file_tombstone".equals(file.optString("artifactKind")),hasText=!deleted&&diff.opt("diff") instanceof String;
            JSONObject row=new JSONObject().put("artifactId",id).put("assignmentId",file.getString("assignmentId")).put("memberId",file.getString("memberId"))
                    .put("path",file.getString("path")).put("sha256",hash).put("currentVersion",diff.getInt("currentVersion"))
                    .put("currentExists",diff.getBoolean("currentExists")).put("matchesOwnerBase",diff.getBoolean("matchesOwnerBase"))
                    .put("retainedApplied","retain_applied".equals(diff.optString("disposition"))).put("truncated",diff.getBoolean("truncated"))
                    .put("kind",deleted?"deletion":hasText?"text":"binary_or_large");
            if(hasText){String body=diff.getString("diff");if(body.length()>65536)throw new SecurityException("原生差异超出保留范围");if(diff.getBoolean("truncated")&&!body.isEmpty()&&Character.isHighSurrogate(body.charAt(body.length()-1)))body=body.substring(0,body.length()-1);text.put(id,body);row.put("retainedChars",body.length());}
            for(String key:new String[]{"beforeBytes","afterBytes","beforeHash","afterHash"})if(diff.has(key))row.put(key,diff.get(key));
            entries.put(id,row);rows.put(row);
        }
        description=new JSONObject().put("teamRunId",team).put("planHash",plan).put("capturedAt",System.currentTimeMillis()).put("files",rows)
                .put("incompleteCount",captured.getJSONArray("incompleteAssignments").length()).put("recovery",captured.optBoolean("recovery"))
                .put("readOnly",true).put("executionAuthorized",false);
    }
    public JSONObject index()throws Exception{return new JSONObject(description.toString());}
    public JSONObject page(String id,int offset)throws Exception{
        JSONObject original=entries.get(id);if(original==null)throw new SecurityException("这份文件不在打开时的原目录");
        String body=text.get(id);if(body==null||offset<0||offset>body.length()||offset>0&&offset<body.length()&&Character.isLowSurrogate(body.charAt(offset))&&Character.isHighSurrogate(body.charAt(offset-1)))throw new SecurityException("没有这段可读的原文差异");
        int end=Math.min(body.length(),offset+6000);
        if(end<body.length()&&end>offset&&Character.isHighSurrogate(body.charAt(end-1))&&Character.isLowSurrogate(body.charAt(end)))end--;
        return new JSONObject().put("teamRunId",team).put("planHash",plan).put("artifactId",id).put("assignmentId",original.getString("assignmentId"))
                .put("offset",offset).put("endOffset",end).put("retainedChars",body.length()).put("nativeTruncated",original.getBoolean("truncated"))
                .put("diff",body.substring(offset,end)).put("readOnly",true);
    }
}
