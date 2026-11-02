package dev.pocketengine.browser;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.*;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.Assume;
import static org.junit.Assert.*;

/** Real Chromium DOM using the production program emitter; never a WebView/provider or recipe claim. */
public final class BrowserPageProgramsChromiumTest {
    static String chrome(){
        String configured=System.getenv("CHROME_BIN");if(configured!=null&&!configured.isBlank()&&Files.isExecutable(Path.of(configured)))return configured;
        for(String path:List.of("/usr/bin/google-chrome","/usr/bin/google-chrome-stable","/usr/bin/chromium","/usr/bin/chromium-browser"))if(Files.isExecutable(Path.of(path)))return path;return null;
    }
    @Test public void currentProductionExpressionsReadChangingDomWithoutPrivateOrHiddenSubtrees()throws Exception {
        String chrome=chrome();if("true".equals(System.getenv("GITHUB_ACTIONS")))assertNotNull("CI must provide a real Chrome/Chromium executable, optionally through CHROME_BIN",chrome);Assume.assumeTrue("Real Chromium unavailable outside CI; DOM behavior remains unverified",chrome!=null);
        String text=BrowserPagePrograms.expression("{\"op\":\"text\",\"input\":{\"op\":\"query\",\"selector\":\"main\"}}"),sum=BrowserPagePrograms.expression("{\"op\":\"sum\",\"input\":{\"op\":\"map\",\"input\":{\"op\":\"queryAll\",\"selector\":\".price\"},\"body\":{\"op\":\"number\",\"input\":{\"op\":\"text\",\"input\":{\"op\":\"item\"}}}}}"),overflow=BrowserPagePrograms.expression("{\"op\":\"multiply\",\"left\":{\"op\":\"literal\",\"value\":1e308},\"right\":{\"op\":\"literal\",\"value\":1e308}}");
        String html="<!doctype html><meta charset='utf-8'><meta http-equiv='Content-Security-Policy' content=\"default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'\"><title>production DOM calculation test</title><main style='display:contents'><span class='price'>12</span><span class='price'>8</span><section data-private='true'>PRIVATE_CANARY_7341</section><span style='display:none'>HIDDEN_CANARY_937</span><input value='CONTROL_CANARY_713'></main><output id='result'></output><script>"+
                "let privateReads=0,hiddenReads=0;const result=document.getElementById('result');try{const privateNode=document.querySelector('[data-private]').firstChild,hiddenNode=document.querySelector('main>span[style]').firstChild;Object.defineProperty(privateNode,'nodeValue',{get(){privateReads++;throw Error('private_text_was_read')}});Object.defineProperty(hiddenNode,'nodeValue',{get(){hiddenReads++;throw Error('hidden_text_was_read')}});"+
                "const text=JSON.parse("+text+"),initial=JSON.parse("+sum+");if(text.status!=='observed'||text.value!=='128'||initial.value!==20)throw Error('visible_public_dom_read_failed');document.querySelector('.price').textContent='23';const changed=JSON.parse("+sum+"),overflow=JSON.parse("+overflow+");if(changed.value!==31||overflow.status!=='unavailable'||overflow.code!=='expression_non_finite_result'||privateReads!==0||hiddenReads!==0)throw Error('privacy_or_finite_calculation_failed');result.dataset.status='passed';result.textContent=JSON.stringify({status:'passed',initialSum:initial.value,changedSum:changed.value,privateReads,hiddenReads,displayContentsPreserved:true,overflowRejected:true,renderer:'actual_chromium'});"+
                "}catch(error){result.dataset.status='failed';result.textContent=JSON.stringify({status:'failed',message:String(error.message),privateReads,hiddenReads})}</script>";
        Path temp=Files.createTempDirectory("pocket-browser-dom-"),page=temp.resolve("fixture.html"),dom=temp.resolve("dom.html"),errors=temp.resolve("chrome.stderr");Process process=null;
        try{
            Files.write(page,html.getBytes(StandardCharsets.UTF_8));process=new ProcessBuilder(chrome,"--headless","--disable-gpu","--disable-dev-shm-usage","--disable-background-networking","--disable-component-update","--disable-sync","--disable-extensions","--no-first-run","--no-default-browser-check","--user-data-dir="+temp.resolve("profile"),"--dump-dom",page.toUri().toString()).redirectOutput(dom.toFile()).redirectError(errors.toFile()).start();
            assertTrue("Chromium local DOM check exceeded25seconds",process.waitFor(25,TimeUnit.SECONDS));String errorLog=new String(Files.readAllBytes(errors),StandardCharsets.UTF_8);assertEquals(errorLog.substring(0,Math.min(1600,errorLog.length())),0,process.exitValue());String rendered=new String(Files.readAllBytes(dom),StandardCharsets.UTF_8);Matcher output=Pattern.compile("<output\\b[^>]*\\bid=\"result\"[^>]*>(.*?)</output>",Pattern.DOTALL).matcher(rendered);assertTrue("Chromium did not return the real result element",output.find());JSONObject actual=new JSONObject(output.group(1));assertEquals(actual.toString(),"passed",actual.getString("status"));assertEquals(20,actual.getInt("initialSum"));assertEquals(31,actual.getInt("changedSum"));assertEquals(0,actual.getInt("privateReads"));assertEquals(0,actual.getInt("hiddenReads"));assertTrue(actual.getBoolean("displayContentsPreserved"));assertTrue(actual.getBoolean("overflowRejected"));
        }finally{if(process!=null&&process.isAlive()){process.destroyForcibly();process.waitFor(5,TimeUnit.SECONDS);}try(var files=Files.walk(temp)){for(Path file:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(file);}}
    }
}
