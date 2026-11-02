package dev.pocketengine.browser;
import org.json.JSONObject;
public final class EmitBrowserTransferPrograms {
    public static void main(String[] args)throws Exception {
        JSONObject target=new JSONObject().put("ref","n0").put("nodeKey","k1").put("tag","A");
        System.out.println(new JSONObject().put("network",BrowserNetworkWitness.script("nativeNetwork","registration-test")).put("networkStop",BrowserNetworkWitness.stopScript("nativeNetwork","registration-test"))
                .put("documentOpen",BrowserDocumentInput.openScript("page-state","https://document.example/export","actual-observation",target,"document-bytes","actual-token"))
                .put("documentPoll",BrowserDocumentInput.pollScript("document-bytes","actual-token",0,false)).put("documentChunk",BrowserDocumentInput.pollScript("document-bytes","actual-token",0,true)).put("documentClose",BrowserDocumentInput.cleanupScript("document-bytes","actual-token")));
    }
}
