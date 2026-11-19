package dev.pocketengine.features.teams;

import org.json.JSONArray;
import org.json.JSONObject;

/** Exact read model for previously verified frozen artifacts/current workspace heads. Never creates work. */
final class TeamRemainingSelection {
    private TeamRemainingSelection(){}
    static JSONObject split(JSONObject plan,JSONObject progress,boolean hasApprovedFile)throws Exception{
        JSONArray refs=plan.getJSONArray("files"),rows=progress.getJSONArray("files"),done=new JSONArray(),pending=new JSONArray();
        if(plan.has("recovery")||plan.has("retained")||refs.length()<(hasApprovedFile?1:2)||refs.length()>32||refs.length()!=rows.length())throw new SecurityException("只继续原来同一份已批准的多文件计划，不处理修订或替换来源。");
        for(int i=0;i<refs.length();i++){
            JSONObject ref=refs.getJSONObject(i),row=rows.getJSONObject(i);
            if(!ref.getString("path").equals(row.getString("path"))||!ref.getString("artifactId").equals(row.getString("artifactId"))||"file_tombstone".equals(ref.opt("artifactKind"))
                    ||ref.getInt("expectedVersion")!=ref.getInt("baseVersion")||!ref.getString("expectedHash").equals(ref.getString("baseHash")))
                throw new SecurityException("一项原文件的文本或初始版本不可核对，不会替换或派发。");
            JSONObject item=new JSONObject().put("path",row.getString("path")).put("artifactId",row.getString("artifactId"))
                    .put("version",row.getInt("currentVersion")).put("revisionId",row.getString("currentRevisionId"));
            switch(row.getString("state")){
                case "applied" -> {if(!row.has("teamIntegrated"))throw new SecurityException("原写回没有实际回执。");done.put(item);}
                case "pending" -> pending.put(item);
                default -> throw new SecurityException("文件「"+ref.getString("path")+"」不再是原审阅基线；保留已写入的结果，没有继续。");
            }
        }
        if(!hasApprovedFile&&done.length()==0||pending.length()==0)throw new IllegalStateException(done.length()==0?"尚无已核实的部分写回，此入口不启动整队。":"原清单的文件已全部写回，没有要继续的文件。");
        return new JSONObject().put("completed",done).put("remaining",pending);
    }
    static boolean contains(JSONArray remaining,String path,String artifact)throws Exception{
        for(int i=0;i<remaining.length();i++){JSONObject row=remaining.getJSONObject(i);if(path.equals(row.getString("path"))&&artifact.equals(row.getString("artifactId")))return true;}return false;
    }
}
