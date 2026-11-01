package dev.pocketengine.phone.observation;

import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

final class PhoneCollectionFixtures {
    static JSONObject j(Object... pairs)throws Exception {JSONObject out=new JSONObject();for(int i=0;i<pairs.length;i+=2)out.put((String)pairs[i],pairs[i+1]);return out;}
    static JSONObject node(String id,String parent,String resource,String text,int... indices)throws Exception {
        JSONArray path=new JSONArray();for(int n:indices)path.put(n);
        return j("id",id,"parentId",parent==null?JSONObject.NULL:parent,"windowId",9,"path",path,"resourceId",resource,"className","View","role","text","identityFingerprint","identity-"+id,"text",text,"description","","visible",true,"enabled",true,"password",false,"clickable",false,"editable",false,"checked",false,"selected",false,"scrollable",false,"bounds",j("left",0,"top",0,"right",1000,"bottom",1000),"children",new JSONArray());
    }
    static JSONObject tree(String id,String... values)throws Exception {
        JSONObject root=node("n0",null,"app:id/list","").put("role","list").put("scrollable",true);JSONArray nodes=new JSONArray().put(root);
        for(int i=0;i<values.length;i++){String row="n"+(i*2+1),field="n"+(i*2+2);JSONObject r=node(row,"n0","app:id/row","",i),f=node(field,row,"app:id/title",values[i],i,0);root.getJSONArray("children").put(row);r.getJSONArray("children").put(field);nodes.put(r).put(f);}
        JSONObject out=j("ok",true,"observationId",id,"treeSchemaVersion",1,"displayId",5,"sessionId","s","packageName","app.example","pauseRevision",0,"observedAtUptimeMs",1000,"truncated",false,"treeFingerprint",UUID.nameUUIDFromBytes(java.util.Arrays.toString(values).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString(),"treeCoverage",j("bodyTruncated",false),"nodes",nodes,"windows",new JSONArray().put(j("windowId",9,"displayId",5,"rootAvailable",true,"rootIds",new JSONArray().put("n0"),"truncated",false)));
        return ObservationReport.attach(out,true,"accessibility_tree",false,"not_requested");
    }
    static JSONArray fields()throws Exception{return new JSONArray().put(j("name","title","selector",j("resourceId","app:id/title")));}
    static JSONObject extract(String observation)throws Exception{return j("observationId",observation,"containerId","n0","fields",fields());}
    static JSONObject start(String observation)throws Exception{return extract(observation).put("action","start").put("direction","forward").put("maxPages",6).put("maxRows",60);}
    static JSONObject operation(PhoneTreeCollection.State state,String after)throws Exception {String op=UUID.randomUUID().toString();return j("operationId",op,"tool",NativeTreeScrollPort.TOOL,"state","receipt","arguments",state.expectedScroll(),"receipt",j("operationId",op,"accepted",true,"targetRef",state.result().getJSONObject("nextScrollRef"),"afterObservationId",after));}
    static String value(JSONObject result,int row)throws Exception{return result.getJSONArray("rows").getJSONObject(row).getJSONObject("fields").getJSONObject("title").getString("value");}
}
