package dev.pocketengine.features.proxy.accounts;
import android.app.Application;
import dev.pocketengine.store.*;
import dev.pocketengine.runtime.models.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class OpenRouterOnboardingTest {
 private static final class Secrets implements SettingsStore.ApiSecrets {final Map<String,String> slots=new HashMap<>();public String get(String slot){return slots.getOrDefault(slot,"");}public void put(String slot,String value){slots.put(slot,value);}}
 private static Map<String,String> query(String url)throws Exception {Map<String,String> out=new HashMap<>();for(String part:URI.create(url).getRawQuery().split("&")){String[] pair=part.split("=",2);out.put(URLDecoder.decode(pair[0],StandardCharsets.UTF_8),URLDecoder.decode(pair.length>1?pair[1]:"",StandardCharsets.UTF_8));}return out;}
 private static int callback(Map<String,String> values,String state,String code)throws Exception {HttpURLConnection http=(HttpURLConnection)URI.create(values.get("callback_url")+"?state="+URLEncoder.encode(state,StandardCharsets.UTF_8)+"&code="+URLEncoder.encode(code,StandardCharsets.UTF_8)).toURL().openConnection();http.setConnectTimeout(3000);http.setReadTimeout(3000);try{return http.getResponseCode();}finally{http.disconnect();}}
 private static SettingsStore settings(Secrets secrets)throws Exception {var prefs=RuntimeEnvironment.getApplication().getSharedPreferences("openrouter-fixture",0);prefs.edit().clear().commit();SettingsStore settings=ModelDiagnosticsFixtureSettings.create(prefs,secrets);settings.save(new JSONObject().put("baseUrl","https://fixture.invalid/v1").put("model","existing-main").put("sendScreenshots",false));settings.apiConnections();return settings;}
 @Test public void actualLoopbackStateAndPkceSaveOnlyAnOriginalApiConnection()throws Exception {Secrets secrets=new Secrets();SettingsStore settings=settings(secrets);AtomicInteger exchanges=new AtomicInteger();AtomicReference<String> challenge=new AtomicReference<>();try(OpenRouterOnboarding auth=new OpenRouterOnboarding(settings,(code,verifier)->{exchanges.incrementAndGet();String actual=Base64.getUrlEncoder().withoutPadding().encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));assertEquals(challenge.get(),actual);if(!code.equals("fixture-code"))throw new GatewayFailure("PKCE_REJECTED");return "fixture-openrouter-key-never-real";})){
  Map<String,String> values=query(auth.begin("provider/model").getString("authorizationUrl"));challenge.set(values.get("code_challenge"));assertEquals("S256",values.get("code_challenge_method"));assertEquals(400,callback(values,"wrong-state","fixture-code"));assertEquals(0,exchanges.get());assertTrue(secrets.slots.isEmpty());assertEquals(200,callback(values,values.get("state"),"fixture-code"));JSONObject snapshot=auth.snapshot();assertEquals("completed",snapshot.getString("status"));assertFalse(snapshot.toString().contains("fixture-openrouter-key"));assertEquals("existing-main",settings.get().model);ModelConnectionService service=new ModelConnectionService(settings,Files.createTempDirectory("openrouter-catalog").resolve("catalog"),Clock.systemUTC());ModelConfigCheck.check(service.captureTarget(ModelTargetRef.exact(snapshot.getString("connectionId"),"provider/model"),"MAIN","APP_OWNER",settings.get()));auth.cancel();assertEquals("completed",auth.snapshot().getString("status"));assertEquals(1,exchanges.get());}
 }
 @Test public void rejectedPkceOrCancelLeavesNoNewCredential()throws Exception {Secrets secrets=new Secrets();SettingsStore settings=settings(secrets);int before=settings.apiConnections().getJSONArray("connections").length();try(OpenRouterOnboarding auth=new OpenRouterOnboarding(settings,(code,verifier)->{throw new GatewayFailure("PKCE_REJECTED");})){Map<String,String> values=query(auth.begin("provider/model").getString("authorizationUrl"));assertEquals(400,callback(values,values.get("state"),"wrong-code"));assertEquals("failed",auth.snapshot().getString("status"));assertTrue(secrets.slots.isEmpty());assertEquals(before,settings.apiConnections().getJSONArray("connections").length());auth.begin("provider/model");auth.cancel();assertEquals("cancelled",auth.snapshot().getString("status"));assertTrue(secrets.slots.isEmpty());}}
 private static final class ModelConfigCheck {static void check(dev.pocketengine.core.ModelConfig config){assertEquals(OpenRouterOnboarding.API_BASE,config.baseUrl);assertEquals("openai_chat",config.provider);assertEquals("fixture-openrouter-key-never-real",config.apiKey);assertFalse(config.usesAccount());}}
}
