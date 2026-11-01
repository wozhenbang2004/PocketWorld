package dev.pocketengine.features.local.preview;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.json.JSONObject;

/** Real production document wrapper for an isolated interactive browser check; not product seed data. */
public final class ProjectPreviewFixture {
    public static void main(String[] args)throws Exception {
        String checkout="11111111-1111-1111-1111-111111111111",job="owner-build-test",source="a".repeat(64);
        String content="<style>body{font:18px sans-serif;padding:24px}button{padding:12px}</style><h1>离线产物交互验证</h1><p id=\"value\">0</p><button id=\"increment\" onclick=\"document.querySelector('#value').textContent=Number(document.querySelector('#value').textContent)+1\">加一</button><p id=\"network\">未请求</p><button id=\"probe\" onclick=\"fetch('https://example.com/should-not-load').then(()=>document.querySelector('#network').textContent='ERROR').catch(()=>document.querySelector('#network').textContent='blocked')\">验证外网阻断</button>";
        JSONObject build=new JSONObject().put("checkoutId",checkout).put("sourceDigest",source).put("intent","build").put("status","completed").put("exitCode",0).put("jobId",job).put("receiptIdentity","fixture-receipt").put("receiptReceived",true).put("sourceVerifiedBeforeExec",true).put("cancelRequested",false);
        JSONObject artifact=new JSONObject().put("checkoutId",checkout).put("sourceDigest",source).put("jobId",job).put("buildReceiptIdentity","fixture-receipt").put("path","dist/index.html").put("content",content).put("sha256",ProjectPreviewArtifact.hash(content.getBytes(StandardCharsets.UTF_8))).put("mimeType","text/html").put("truncated",false).put("artifactOrigin","checkout_source").put("generatedArtifact",false);
        ProjectPreviewArtifact.Verified verified=ProjectPreviewArtifact.verify(artifact,build,checkout,source);
        Files.write(Paths.get(args[0]),ProjectPreviewPolicy.document(verified).getBytes(StandardCharsets.UTF_8));
        System.out.println("Verified production HTML fixture: "+verified.identity);
    }
}
