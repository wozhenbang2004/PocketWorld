package dev.pocketengine.features.plugins;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.memory.ToolOutputCapture;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;

/** Per actual host call only. No cache, transport, grant, replay or independent output store. */
final class McpResultCapture {
    static final int MAX_VIEW_BYTES=12_288,MAX_VIEW_BLOCKS=16,MAX_PREVIEW_CHARS=4000;
    private final ToolOutputCapture.Claim claim;
    private final JSONObject provenance;
    private JSONObject metadata;
    private boolean received;
    /** Issued only around the native Factory admission, before entering any MCP transport. */
    static final class AdmissionRefused extends Exception {
        AdmissionRefused(Throwable cause){super("原MCP输出调用身份或prepared动作不匹配，尚未进入MCP派发",cause);}
    }

    private McpResultCapture(ToolOutputCapture.Claim claim,JSONObject provenance,String reason)throws Exception {
        this.claim=claim;this.provenance=new JSONObject(provenance.toString());
        metadata=ToolOutputCapture.unavailable(reason).metadata();
    }
    static McpResultCapture open(ToolOutputCapture.Factory factory,String hostTool,String operationId,
            RunControl original,JSONObject nativeProvenance)throws Exception {
        if(factory==null)throw new IllegalArgumentException("需要原native输出Factory");
        if(!hostTool.matches("plugin_[a-f0-9]{24}")||!operationId.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")||!original.hasHostRun())
            throw new AdmissionRefused(null);
        ToolOutputCapture.Claim claim=null;
        try{claim=factory.begin(hostTool,operationId,ToolOutputCapture.Kind.MCP_RESULT_JSON,original);}
        catch(ToolOutputCapture.OutputSourceUnavailable refused){throw refused;}
        catch(SecurityException refused){throw new AdmissionRefused(refused);}
        catch(Exception unavailable){original.check();}
        return new McpResultCapture(claim,nativeProvenance,claim==null?"native_output_claim_unavailable":"native_output_capture_unavailable");
    }
    JSONObject result(JSONObject matchedCompleteResult,String token)throws Exception {
        if(received)throw new SecurityException("原MCP返回只能存证一次");
        // Structural/protocol checks remain real; only the model-view size limits are deferred.
        JSONObject data=McpData.resultForCapture(matchedCompleteResult,token);
        received=true;
        if(claim!=null)try{
            JSONObject safeProvenance=(JSONObject)McpData.scrub(provenance,token,0);
            JSONObject safe=new JSONObject().put("format","pocketworld.mcp-returned-data.v1")
                .put("provenance",safeProvenance)
                .put("sourceCoverage","supported_text_and_structured_content_fields")
                .put("unsupportedContentOmitted",data.getJSONArray("omittedContent").length()>0)
                .put("upstreamTruncationKnown",false).put("result",data);
            // Provenance as well as data is scrubbed; neither headers, token nor OAuth lease enter it.
            // Complete RPC != proof that the server did not pre-truncate its tool output.
            ToolOutputCapture.Result saved=claim.record(safe,null);
            metadata=saved==null?ToolOutputCapture.unavailable("native_output_capture_unavailable").metadata():saved.metadata();
        }catch(Exception unavailable){metadata=ToolOutputCapture.unavailable("native_output_capture_unavailable").metadata();}
        return view(data);
    }
    void notDispatched() {
        if(claim!=null&&!received)try{claim.notDispatched();}catch(Exception ignored){/* No execution or source claim is inferred from cleanup. */}
    }
    JSONObject metadata()throws Exception{return ToolOutputCapture.checkedMetadata(metadata);}

    /** A bounded view of independently retained data. A summary is never labelled the original. */
    static JSONObject view(JSONObject data)throws Exception {
        JSONObject out=new JSONObject().put("isError",data.getBoolean("isError"))
            .put("content",new JSONArray()).put("omittedContent",new JSONArray())
            .put("untrusted",true).put("instructionsAreData",true)
            .put("nativeResultCoverage","supported_text_and_structured_content_fields")
            .put("upstreamTruncationKnown",false);
        JSONArray texts=data.getJSONArray("content"),omitted=data.getJSONArray("omittedContent"),shown=out.getJSONArray("content");
        boolean truncated=false;long textChars=0;
        for(int i=0;i<texts.length();i++){
            String value=texts.getJSONObject(i).getString("text");textChars+=value.length();
            if(shown.length()>=MAX_VIEW_BLOCKS){truncated=true;continue;}
            JSONObject block=new JSONObject().put("type","text").put("text","");shown.put(block);
            String preview=prefix(value,Math.min(value.length(),MAX_PREVIEW_CHARS));block.put("text",preview);
            if(bytes(out)>MAX_VIEW_BYTES-1024){
                int low=0,high=preview.length();
                while(low<high){int mid=low+(high-low+1)/2;block.put("text",prefix(preview,mid));if(bytes(out)<=MAX_VIEW_BYTES-1024)low=mid;else high=mid-1;}
                block.put("text",prefix(preview,low));
            }
            if(block.getString("text").length()<value.length())truncated=true;
            if(block.getString("text").isEmpty()&&!value.isEmpty()){
                shown.remove(shown.length()-1);truncated=true;
            }
        }
        JSONArray displayedOmitted=out.getJSONArray("omittedContent");
        for(int i=0;i<omitted.length()&&displayedOmitted.length()<MAX_VIEW_BLOCKS;i++){
            displayedOmitted.put(new JSONObject(omitted.getJSONObject(i).toString()));
            if(bytes(out)>MAX_VIEW_BYTES-1024){displayedOmitted.remove(displayedOmitted.length()-1);truncated=true;break;}
        }
        if(displayedOmitted.length()<omitted.length())truncated=true;
        if(data.has("structuredContent")){
            JSONObject structured=data.getJSONObject("structuredContent");out.put("structuredContent",structured).put("outputSchemaValidated",false);
            if(bytes(out)>MAX_VIEW_BYTES-1024){
                out.remove("structuredContent");out.put("structuredContentOmittedFromView",true);
                out.put("structuredContentCharacters",structured.toString().length());truncated=true;
            }
        }
        out.put("viewTruncated",truncated).put("textBlockCount",texts.length()).put("textCharacters",textChars)
            .put("unsupportedContentBlockCount",omitted.length())
            .put("viewNotice",truncated?"这是有界预览，不是原文；请核对同回执outputCapture的保留区间和完整性后按原ref查询/读取。":"这是受支持返回字段的模型视图；远端是否预截断未知，原文保留事实只以outputCapture为准。");
        if(bytes(out)>MAX_VIEW_BYTES)throw new IllegalStateException("MCP native有界视图超出固定预算");
        return out;
    }
    private static int bytes(JSONObject value){return value.toString().getBytes(StandardCharsets.UTF_8).length;}
    static String prefix(String value,int end){
        end=Math.min(end,value.length());
        if(end>0&&end<value.length()&&Character.isHighSurrogate(value.charAt(end-1))&&Character.isLowSurrogate(value.charAt(end)))end--;
        return value.substring(0,end);
    }
}
