package dev.pocketengine.displayhelper;

import java.util.regex.*;
import org.json.*;

/** Read-only identities from the explicitly supported activity dump; never moves a task. */
final class DisplayOwnershipSnapshot {
    static JSONObject parse(String dump,int ownedDisplay)throws Exception {
        Pattern display=Pattern.compile("^Display #([0-9]+)(?:\\s.*)?$"),activity=Pattern.compile("([A-Za-z][A-Za-z0-9_.]*)/[^ ]+"),task=Pattern.compile("\\bt([0-9]+)\\b");
        JSONArray primary=new JSONArray(),owned=new JSONArray();int current=-1;boolean sawPrimary=false,sawOwned=false;
        for(String line:dump.split("\n")){String text=line.trim();Matcher d=display.matcher(text);if(d.matches()){current=Integer.parseInt(d.group(1));sawPrimary|=current==0;sawOwned|=current==ownedDisplay;continue;}
            if(current!=0&&current!=ownedDisplay||!text.contains("mResumedActivity")&&!text.contains("topResumedActivity"))continue;
            Matcher a=activity.matcher(text),t=task.matcher(text);if(!a.find()||!t.find())continue;
            JSONObject row=new JSONObject().put("displayId",current).put("packageName",a.group(1)).put("taskId",Integer.parseInt(t.group(1)));
            JSONArray rows=current==0?primary:owned;boolean duplicate=false;for(int i=0;i<rows.length();i++)if(rows.getJSONObject(i).getInt("taskId")==row.getInt("taskId"))duplicate=true;if(!duplicate)rows.put(row);
        }
        boolean known=sawPrimary&&sawOwned&&primary.length()>0&&owned.length()>0;
        return new JSONObject().put("status",known?"observed":"unknown").put("reason",known?"resumed_task_identity":"task_format_or_resumed_identity_unavailable").put("primary",primary).put("owned",owned).put("readOnly",true).put("taskMigrationPerformed",false);
    }
}
