package dev.pocketengine.media.jobs;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.media.MediaResultDownload;
import dev.pocketengine.media.MediaTransport;
import dev.pocketengine.media.MediaResultLoopbackFixture;
import dev.pocketengine.testutil.HttpServer;
import java.io.File;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

/** Real POST/GET loopback bytes with a TEST-ONLY OkHttp application-interceptor socket seam.
 * Production URL validation, request construction and auth/redirect handling still run. This
 * deliberately does not claim public DNS, actual-connect or TLS integration coverage. No
 * production downloader setting, provider, public-address rule or test hook is changed. */
public final class MediaJobsOwnerDownloadTest {
    private static final byte[] PNG=java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jJu0AAAAASUVORK5CYII=");
    private static final String URL="https://cdn.example.test/original.png?signed_secret=fixture-original-secret";
    private final String operation=UUID.randomUUID().toString(),task=UUID.randomUUID().toString(),run=UUID.randomUUID().toString();
    private final AtomicInteger posts=new AtomicInteger(),gets=new AtomicInteger(),imports=new AtomicInteger(),redirectTarget=new AtomicInteger();
    private HttpServer server;private File file;private MediaJobJournal journal;private MediaJobs jobs;private JSONObject config,saved;private int resultCode=200;
    private MediaResultLoopbackFixture network;private String expectedConsumed="";
    @Before public void prepare()throws Exception{
        file=new File(Files.createTempDirectory("media-owner-download-").toFile(),"receipts.json");
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/images/generations",exchange->{
            assertEquals("POST",exchange.getRequestMethod());posts.incrementAndGet();exchange.getRequestBody().readAllBytes();
            byte[] body=new JSONObject().put("data",new JSONArray().put(new JSONObject().put("url",URL))).toString().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();
        });
        server.createContext("/original.png",exchange->{
            assertEquals("GET",exchange.getRequestMethod());assertEquals("signed_secret=fixture-original-secret",exchange.getRequestURI().getRawQuery());gets.incrementAndGet();
            if(!expectedConsumed.isEmpty()){
                JSONObject disk=new JSONObject(Files.readString(file.toPath())).getJSONObject("receipts").getJSONObject(operation);
                assertFalse(disk.has("_ownerResultDownloadReviewId"));assertEquals(expectedConsumed,disk.getString("_ownerResultDownloadConsumedId"));
            }
            if(resultCode==302)exchange.getResponseHeaders().add("Location","http://127.0.0.1:"+server.getAddress().getPort()+"/redirected");
            exchange.sendResponseHeaders(resultCode,resultCode==200?PNG.length:0);if(resultCode==200)exchange.getResponseBody().write(PNG);exchange.close();
        });
        server.createContext("/redirected",exchange->{redirectTarget.incrementAndGet();exchange.close();});server.start();
        config=new JSONObject().put("provider","openai_compatible").put("baseUrl",base()+"/v1").put("imageModel","original-model").put("ttsModel","").put("voice","").put("secretSlot","original-vault-slot").put("legacyImageBase64",false).put("allowResultUrls",false).put("comfyJobsApi",false).put("revision","original-configuration");
        reopen();network=new MediaResultLoopbackFixture(URL,base());
    }
    private String base(){return "http://127.0.0.1:"+server.getAddress().getPort();}
    private void reopen()throws Exception{
        journal=new MediaJobJournal(file);jobs=new MediaJobs(journal,new MediaJobs.ArtifactSink(){
            public JSONObject save(MediaTransport.Payload payload,JSONObject source,RunControl control)throws Exception{control.check();assertArrayEquals(PNG,payload.bytes);imports.incrementAndGet();return saved=new JSONObject().put("id","original-artifact").put("size",payload.bytes.length).put("sha256",sha(PNG)).put("source",new JSONObject(source.toString()));}
            public JSONObject find(JSONObject source,RunControl control)throws Exception{control.check();return saved;}
        },receipt->{});
    }
    @After public void close()throws Exception{
        if(network!=null)network.close();
        if(server!=null)server.stop(0);
    }
    private JSONObject submit()throws Exception{return jobs.execute("generateImage",new JSONObject().put("_operationId",operation).put("_taskId",task).put("_runId",run).put("_configurationRevision","original-configuration").put("prompt","labelled fixture"),config,"fixture-only-key",new RunControl());}
    private JSONObject input()throws Exception{return new JSONObject().put("operationId",operation).put("_taskId",task).put("_runId",run).put("_epoch",1).put("_configurationRevision","original-configuration");}
    private RunControl control(){RunControl control=new RunControl();control.bindHostRun(task,run,1);return control;}
    private JSONObject review()throws Exception{return jobs.reviewOriginalResultDownload(input(),config,"fixture-only-key",control()).getJSONObject("resultDownloadReview");}
    private JSONObject confirm(JSONObject review)throws Exception{expectedConsumed=review.getString("reviewId");return jobs.confirmOriginalResultDownload(input().put("downloadReview",review),config,"fixture-only-key",control());}
    private static String sha(byte[] bytes)throws Exception{StringBuilder s=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))s.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return s.toString();}

    @Test public void falsePolicyPaidPostSurvivesToggleAndColdReviewAllowsOnlyOneExactOriginalGet()throws Exception{
        JSONObject first=submit();assertEquals("owner_download_policy_required",first.getString("resultStatus"));assertEquals(1,posts.get());assertEquals(0,gets.get());
        config.put("allowResultUrls",true).put("revision","new-configure-revision");reopen();
        assertThrows(SecurityException.class,()->jobs.execute("fetchResults",input(),config,"fixture-only-key",control()));
        JSONObject review=review();assertEquals(operation,review.getString("operationId"));assertEquals("original-configuration",review.getString("configurationRevision"));assertEquals("new-configure-revision",review.getString("currentConfigurationRevision"));assertEquals(sha(URL.getBytes(StandardCharsets.UTF_8)),review.getString("resultUrlSha256"));
        assertEquals(journal.get(operation).getString("_responseDigest"),review.getString("providerResponseSha256"));assertEquals(0,gets.get());assertFalse(review.toString().contains("signed_secret"));assertFalse(review.toString().contains("fixture-only-key"));
        JSONObject result=confirm(review);assertEquals("saved",result.getString("resultStatus"));assertEquals(1,posts.get());assertEquals(1,gets.get());assertEquals(1,imports.get());assertEquals(List.of(URL),network.requested);assertNull(network.auth.get(0));
        JSONObject source=saved.getJSONObject("source");assertEquals(operation,source.getString("operationId"));assertEquals(task,source.getString("taskId"));assertEquals(run,source.getString("runId"));assertEquals("original-configuration",source.getString("configurationRevision"));assertEquals(review.getString("providerResponseSha256"),source.getString("providerResponseSha256"));assertFalse(result.toString().contains("signed_secret"));
        assertThrows(SecurityException.class,()->confirm(review));reopen();assertThrows(SecurityException.class,()->confirm(review));assertEquals(1,gets.get());assertEquals(1,posts.get());
    }
    @Test public void noHostOrChangedIdentityProviderOriginModelsSlotOrCredentialCannotAuthorize()throws Exception{
        submit();assertThrows(SecurityException.class,()->jobs.reviewOriginalResultDownload(input(),config,"fixture-only-key",new RunControl()));
        assertThrows(SecurityException.class,()->jobs.reviewOriginalResultDownload(input().put("_taskId","other-task"),config,"fixture-only-key",control()));
        assertThrows(SecurityException.class,()->jobs.reviewOriginalResultDownload(input().put("_epoch",2),config,"fixture-only-key",control()));
        assertThrows(SecurityException.class,()->jobs.reviewOriginalResultDownload(input().put("_configurationRevision","other-revision"),config,"fixture-only-key",control()));
        for(String field:new String[]{"provider","baseUrl","imageModel","ttsModel","voice","secretSlot","legacyImageBase64","comfyJobsApi"}){
            JSONObject changed=new JSONObject(config.toString()).put(field,field.equals("legacyImageBase64")||field.equals("comfyJobsApi")?true:"changed");
            assertThrows(field,SecurityException.class,()->jobs.reviewOriginalResultDownload(input(),changed,"fixture-only-key",control()));
        }
        assertThrows(SecurityException.class,()->jobs.reviewOriginalResultDownload(input(),config,"rotated-key",control()));assertEquals(0,gets.get());assertEquals(1,posts.get());
    }
    @Test public void tamperedConfirmationOtherOperationResponseOrUrlCannotDispatch()throws Exception{
        submit();JSONObject review=review();
        for(String field:new String[]{"operationId","taskId","runId","configurationRevision","currentConfigurationRevision","epoch","providerResponseSha256","resultUrlSha256","resultOrigin","providerOrigin","reviewId"}){
            assertThrows(field,SecurityException.class,()->confirm(new JSONObject(review.toString()).put(field,"changed")));
        }
        assertThrows(SecurityException.class,()->confirm(new JSONObject(review.toString()).put("url","https://attacker.example/new")));
        JSONObject original=journal.get(operation),changed=new JSONObject(original.toString()).put("_resultUrl","https://attacker.example/new.png");journal.put(changed);assertThrows(SecurityException.class,()->confirm(review));journal.put(original);
        changed=new JSONObject(original.toString()).put("_responseDigest","b".repeat(64));journal.put(changed);assertThrows(SecurityException.class,()->confirm(review));journal.put(original);
        assertEquals(0,gets.get());assertEquals(1,posts.get());
    }
    @Test public void failedGetRedirectAndColdStartNeedFreshReviewNeverAnotherPost()throws Exception{
        submit();JSONObject stale=review();reopen();assertThrows(SecurityException.class,()->confirm(stale));
        JSONObject review=review();resultCode=302;JSONObject failed=confirm(review);assertEquals("unavailable",failed.getString("resultStatus"));assertEquals(1,gets.get());assertEquals(0,redirectTarget.get());assertEquals(0,imports.get());JSONObject used=review;
        assertThrows(SecurityException.class,()->confirm(used));assertEquals(1,gets.get());
        review=review();resultCode=200;assertEquals("saved",confirm(review).getString("resultStatus"));assertEquals(2,gets.get());assertEquals(1,posts.get());
    }
    @Test public void cancelledOrExpiredReviewDoesNotGetAndLegacySlotStillHasExactFullConfigurationGate()throws Exception{
        submit();JSONObject review=review();RunControl stopped=control();stopped.cancel();assertThrows(InterruptedException.class,()->jobs.confirmOriginalResultDownload(input().put("downloadReview",review),config,"fixture-only-key",stopped));
        JSONObject record=journal.get(operation);record.put("_ownerResultDownloadReviewUntil",System.currentTimeMillis()-1);journal.put(record);assertThrows(SecurityException.class,()->confirm(review));
        record.remove("_providerCredentialHash");journal.put(record);config.put("revision","legacy-policy-toggle").put("allowResultUrls",true);JSONObject fresh=review();assertEquals("saved",confirm(fresh).getString("resultStatus"));assertEquals(1,gets.get());assertEquals(1,posts.get());
    }
    @Test public void consumeWriteFailureNeverGetsOrReusesTokenEvenAfterReopen()throws Exception{
        submit();JSONObject review=review();File backup=new File(file.getParentFile(),"original-backup.json");Files.move(file.toPath(),backup.toPath());assertTrue(file.mkdir());File marker=new File(file,"owned-blocker");Files.writeString(marker.toPath(),"fixture");
        assertThrows(Exception.class,()->confirm(review));assertEquals(0,gets.get());assertTrue(marker.delete());assertTrue(file.delete());Files.move(backup.toPath(),file.toPath());
        assertThrows(SecurityException.class,()->confirm(review));reopen();assertThrows(SecurityException.class,()->confirm(review));assertEquals(0,gets.get());assertEquals(1,posts.get());
        assertEquals("saved",confirm(review()).getString("resultStatus"));assertEquals(1,gets.get());assertEquals(1,posts.get());
    }
    @Test public void privateDescriptorStillFailsProductionValidationBeforeAnySocket()throws Exception{
        submit();JSONObject record=journal.get(operation).put("_resultUrl","https://127.0.0.1/private");journal.put(record);assertThrows(SecurityException.class,()->review());assertEquals(0,gets.get());assertEquals(0,network.requested.size());assertEquals(1,posts.get());
    }
    @Test public void reviewWriteFailureReturnsNoLiveTokenAndCannotRevivePreviousReview()throws Exception{
        submit();JSONObject old=review();File backup=new File(file.getParentFile(),"before-review.json");Files.move(file.toPath(),backup.toPath());assertTrue(file.mkdir());File marker=new File(file,"owned-review-blocker");Files.writeString(marker.toPath(),"fixture");
        assertThrows(Exception.class,()->review());assertEquals(0,gets.get());assertTrue(marker.delete());assertTrue(file.delete());Files.move(backup.toPath(),file.toPath());assertThrows(SecurityException.class,()->confirm(old));assertEquals(0,gets.get());
        assertEquals("saved",confirm(review()).getString("resultStatus"));assertEquals(1,gets.get());assertEquals(1,posts.get());
    }
    @Test public void liveAuthorityRevokedAfterDurableConsumptionStillDoesNotGetOrReplay()throws Exception{
        submit();JSONObject review=review();RunControl revoked=control();AtomicInteger checks=new AtomicInteger();revoked.setAuthorityCheck(()->{if(checks.incrementAndGet()>=3)throw new SecurityException("fixture authority withdrawn before GET");});
        assertThrows(InterruptedException.class,()->jobs.confirmOriginalResultDownload(input().put("downloadReview",review),config,"fixture-only-key",revoked));assertEquals(0,gets.get());assertFalse(journal.get(operation).has("_ownerResultDownloadReviewId"));assertThrows(SecurityException.class,()->confirm(review));assertEquals(0,gets.get());assertEquals(1,posts.get());
    }
    @Test public void nonceCannotCrossAChangedLiveEpochEvenWhenCallerEditsItsProjection()throws Exception{
        submit();JSONObject review=review();RunControl next=new RunControl();next.bindHostRun(task,run,2);
        JSONObject changed=new JSONObject(review.toString()).put("epoch",2);
        assertThrows(SecurityException.class,()->jobs.confirmOriginalResultDownload(input().put("_epoch",2).put("downloadReview",changed),config,"fixture-only-key",next));
        assertEquals(0,gets.get());assertEquals(1,posts.get());assertEquals("saved",confirm(review).getString("resultStatus"));assertEquals(1,gets.get());
    }
    @Test public void unchangedDownloaderSendsCredentialOnlyToTheExactHttpsProviderOrigin()throws Exception{
        assertArrayEquals(PNG,MediaResultDownload.getConfirmedResult(URL,"https://cdn.example.test/v1","fixture-provider-key",new RunControl()));assertEquals("Bearer fixture-provider-key",network.auth.get(0));assertEquals(1,gets.get());assertEquals(0,posts.get());
    }

}
