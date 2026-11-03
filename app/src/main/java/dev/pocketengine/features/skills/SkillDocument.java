package dev.pocketengine.features.skills;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import org.json.JSONArray;
import org.json.JSONObject;

/** A selected UTF-8 SKILL.md becomes one original package entry, never a file read grant. */
final class SkillDocument {
    private SkillDocument(){}
    static JSONObject read(InputStream input)throws Exception {
        if(input==null)throw new IllegalArgumentException("请选择 SKILL.md 文件");
        ByteArrayOutputStream data=new ByteArrayOutputStream();byte[] buffer=new byte[4096];int total=0;
        for(int count;(count=input.read(buffer))>=0;){if(Thread.currentThread().isInterrupted())throw new InterruptedException("技能文件读取已取消");if(count==0)continue;if((total+=count)>256000)throw new IllegalArgumentException("SKILL.md 文件超过256000字节");data.write(buffer,0,count);}
        String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data.toByteArray())).toString();
        if(text.length()>64000)throw new IllegalArgumentException("SKILL.md 内容超过64000字");
        for(int i=0;i<text.length();i++){char value=text.charAt(i);if(value<32&&value!='\n'&&value!='\r'&&value!='\t')throw new IllegalArgumentException("SKILL.md 必须是 UTF-8 文本，不接受二进制内容");}
        SkillManifest.parse(text); // The same strict parser applies even if the user did not edit the file.
        return new JSONObject().put("SKILL.md",text);
    }
    /** Exact verified package bytes, without host/source/approval fields. Omitted resources stay
     * visible; textual path mentions are a hint and never a complete dependency analysis. */
    static JSONObject exportPreview(JSONObject verifiedFiles)throws Exception {
        String manifest=verifiedFiles.getString("SKILL.md");byte[] bytes=manifest.getBytes(StandardCharsets.UTF_8);
        if(!manifest.equals(read(new java.io.ByteArrayInputStream(bytes)).getString("SKILL.md")))throw new IllegalArgumentException("SKILL.md包含无法原样导出的Unicode文字");
        ArrayList<String> paths=new ArrayList<>();verifiedFiles.keys().forEachRemaining(path->{if(!"SKILL.md".equals(path))paths.add(path);});Collections.sort(paths);JSONArray mentioned=new JSONArray();for(String path:paths)if(manifest.contains(path))mentioned.put(path);
        StringBuilder sha=new StringBuilder();for(byte value:MessageDigest.getInstance("SHA-256").digest(bytes))sha.append(String.format(java.util.Locale.ROOT,"%02x",value&255));
        return new JSONObject().put("fileName","SKILL.md").put("mediaType","text/markdown").put("manifest",manifest).put("documentSha256",sha.toString()).put("documentBytes",bytes.length)
                .put("omittedResources",new JSONArray(paths)).put("mentionedOmittedResources",mentioned).put("resourceReferenceAnalysis","exact_packaged_paths_in_text_only").put("resourceReferencesComplete",false)
                .put("singleDocumentOnly",true).put("containsHostApproval",false).put("containsHostSourceBinding",false).put("scriptsExecutable",false).put("executionAuthorized",false);
    }
}
