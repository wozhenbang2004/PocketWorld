package dev.pocketengine.features.channels;

import android.app.Application;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.attachments.AttachmentBook;
import dev.pocketengine.features.attachments.AttachmentRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import static org.junit.Assert.*;

/** Real native iLink poll, original Vault/inbox, binary CDN and Book; synthetic peer/owner only. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public final class WeixinOwnerMediaJourneyTest {
    private static final byte[] KEY="0123456789abcdef".getBytes(StandardCharsets.US_ASCII);
    @Before public void vault(){java.security.Security.addProvider(new java.security.Provider("OwnerMediaFixtureVault","1","Test-only AndroidKeyStore SPI"){{put("KeyStore.AndroidKeyStore",ChannelTaskHostTest.FixtureKeyStore.class.getName());}});}
    @After public void release(){java.security.Security.removeProvider("OwnerMediaFixtureVault");}
    private static byte[] encrypted(byte[] bytes)throws Exception {Cipher c=Cipher.getInstance("AES/ECB/PKCS5Padding");c.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(KEY,"AES"));return c.doFinal(bytes);}
    private static ChannelCore core(ChannelsModule module)throws Exception {var field=ChannelsModule.class.getDeclaredField("core");field.setAccessible(true);return(ChannelCore)field.get(module);}
    private static void consent(WeixinOwnerImageJourneyTest.Fixture f,boolean media)throws Exception {JSONObject profile=core(f.module).profile(f.profile);profile.put("ownerMediaEnabled",media).put("ownerImagesEnabled",true);f.module.handle("profile.save",profile);}
    private static JSONObject wire(String id,String kind,String name,byte[] plain,String url)throws Exception {
        int type=kind.equals("file")?4:kind.equals("voice")?3:5;
        JSONObject item=new JSONObject().put("media",new JSONObject().put("full_url",url).put("aes_key",Base64.getEncoder().encodeToString(KEY)).put("encrypt_type",1));
        if(kind.equals("file"))item.put("file_name",name).put("len",String.valueOf(plain.length)).put("md5",hex(MessageDigest.getInstance("MD5").digest(plain)));
        if(kind.equals("voice"))item.put("encode_type",6).put("text","Platform ASR is not local transcription");
        return new JSONObject().put("message_id",id).put("from_user_id","native-owner@im.wechat").put("message_type",1).put("message_state",2).put("create_time_ms",System.currentTimeMillis()).put("context_token","media-context-"+id)
                .put("item_list",new JSONArray().put(new JSONObject().put("type",type).put(kind+"_item",item)));
    }
    private static JSONObject freeze(WeixinOwnerImageJourneyTest.Fixture f,ChannelsModule.OwnerMediaWindow window,JSONObject event,int index)throws Exception {var review=f.module.reviewOwnerMedia(window,event.getString("id"),index);return f.module.freezeOwnerMedia(review,review.metadata().getString("digest"),new RunControl()).getJSONObject("attachment");}
    private static String hex(byte[] bytes){StringBuilder out=new StringBuilder();for(byte value:bytes)out.append(String.format(java.util.Locale.ROOT,"%02x",value&255));return out.toString();}

    @Test public void actualFileVoiceAndVideoBytesAreNativeSelectedFrozenAndRemainOriginalPrivateRemoteInputsWithoutModelOrReply()throws Exception {
        try(var f=new WeixinOwnerImageJourneyTest.Fixture()){
            f.bind();consent(f,true);Object owner=new Object();f.module.setNativeOwnerForeground(owner,true);
            try(var window=f.module.openOwnerMediaWindow(owner)){
                String[] kinds={"file","voice","video"};byte[][] bytes={new byte[]{0,1,2,(byte)255,10},"#!SILK_V3\ncontrolled-real-bytes".getBytes(StandardCharsets.US_ASCII),new byte[]{0,0,0,24,'f','t','y','p','i','s','o','m',0,0,0,0}};
                for(int n=0;n<kinds.length;n++){
                    f.download=encrypted(bytes[n]);JSONObject event=f.receive(wire(String.valueOf(200+n),kinds[n],"资料.bin",bytes[n],f.base+"/cdn-image"));
                    assertEquals("",event.getString("text"));assertEquals("awaiting_local",event.getString("status"));assertEquals(0,f.models.get());
                    JSONObject saved=freeze(f,window,event,0);assertArrayEquals(bytes[n],AttachmentRepository.get(f.android).book.readVerifiedSnapshot(saved.getString("id"),saved.getString("sha256"),new RunControl()));
                    assertEquals(bytes[n].length,saved.getLong("size"));assertEquals("weixin_owner_dm_media",saved.getJSONObject("source").getString("kind"));
                    ChannelsModule.PrivateInputSource source=f.module.capturePrivateRemoteInput(saved.getString("id"),saved.getString("sha256"));source.checkCurrent();assertEquals(kinds[n],source.view().getString("mediaKind"));
                    assertFalse(saved.toString().contains(Base64.getEncoder().encodeToString(KEY)));assertFalse(saved.toString().contains("/cdn-image"));assertFalse(saved.toString().contains("media-context-"));
                }
                assertEquals(3,f.cdn.get());assertEquals(3,AttachmentRepository.get(f.android).book.list().length());assertEquals(0,f.models.get());assertEquals(0,f.delivered.size());assertEquals(0,f.module.snapshot().getJSONArray("outbox").length());
            }
        }
    }

    @Test public void imageConsentDoesNotGrantFileDownloadAndLaterBindingDoesNotUpgradeOldReceivedMaterial()throws Exception {
        try(var f=new WeixinOwnerImageJourneyTest.Fixture()){
            f.bind();consent(f,false);Object owner=new Object();f.module.setNativeOwnerForeground(owner,true);byte[] plain={5,6};f.download=encrypted(plain);
            JSONObject denied=f.receive(wire("211","file","private.bin",plain,f.base+"/cdn-image"));
            try(var window=f.module.openOwnerMediaWindow(owner)){assertThrows(SecurityException.class,()->f.module.reviewOwnerMedia(window,denied.getString("id"),0));}
            assertEquals(0,f.cdn.get());consent(f,true);
            try(var window=f.module.openOwnerMediaWindow(owner)){assertThrows(SecurityException.class,()->f.module.reviewOwnerMedia(window,denied.getString("id"),0));}
            f.module.handle("owner.unbind",new JSONObject().put("profileId",f.profile));JSONObject unbound=f.receive(wire("212","file","unbound.bin",plain,f.base+"/cdn-image"));JSONObject fresh=f.receive(wire("213","file","fresh-binding.bin",plain,f.base+"/cdn-image"));f.module.handle("owner.bind",new JSONObject().put("profileId",f.profile).put("verifiedEventId",fresh.getString("id")).put("confirmed",true));
            try(var window=f.module.openOwnerMediaWindow(owner)){assertThrows(SecurityException.class,()->f.module.reviewOwnerMedia(window,unbound.getString("id"),0));}
            assertEquals(0,f.cdn.get());assertEquals(0,AttachmentRepository.get(f.android).book.list().length());
        }
    }

    @Test public void closingNativeWindowOrBackgroundingBeforeDispatchMakesZeroConnectionAndRevocationBlocksFrozenSourceAndViewer()throws Exception {
        try(var f=new WeixinOwnerImageJourneyTest.Fixture()){
            f.bind();consent(f,true);Object owner=new Object();f.module.setNativeOwnerForeground(owner,true);byte[] plain={1,2};f.download=encrypted(plain);JSONObject event=f.receive(wire("220","file","a.bin",plain,f.base+"/cdn-image"));
            var closed=f.module.openOwnerMediaWindow(owner);var review=f.module.reviewOwnerMedia(closed,event.getString("id"),0);closed.close();assertThrows(SecurityException.class,()->f.module.freezeOwnerMedia(review,review.metadata().getString("digest"),new RunControl()));assertEquals(0,f.cdn.get());
            var background=f.module.openOwnerMediaWindow(owner);var old=f.module.reviewOwnerMedia(background,event.getString("id"),0);f.module.setNativeOwnerForeground(owner,false);f.module.setNativeOwnerForeground(owner,true);assertThrows(SecurityException.class,()->f.module.freezeOwnerMedia(old,old.metadata().getString("digest"),new RunControl()));assertEquals(0,f.cdn.get());background.close();
            try(var window=f.module.openOwnerMediaWindow(owner)){var current=f.module.reviewOwnerMedia(window,event.getString("id"),0);JSONObject saved=f.module.freezeOwnerMedia(current,current.metadata().getString("digest"),new RunControl()).getJSONObject("attachment");assertEquals(saved.getString("id"),f.module.ownerMediaForViewing(current).getString("id"));var source=f.module.capturePrivateRemoteInput(saved.getString("id"),saved.getString("sha256"));f.module.handle("owner.unbind",new JSONObject().put("profileId",f.profile));assertThrows(SecurityException.class,source::checkCurrent);assertThrows(SecurityException.class,()->f.module.ownerMediaForViewing(current));assertEquals(1,f.cdn.get());}
        }
    }

    @Test public void wrongActualHashPaddingAndRedirectNeverFreezePartialMediaOrFollowAnotherAddress()throws Exception {
        try(var f=new WeixinOwnerImageJourneyTest.Fixture()){
            f.bind();consent(f,true);Object owner=new Object();f.module.setNativeOwnerForeground(owner,true);byte[] plain={3,4,5};
            try(var window=f.module.openOwnerMediaWindow(owner)){
                JSONObject wire=wire("230","file","wrong.bin",plain,f.base+"/cdn-image");wire.getJSONArray("item_list").getJSONObject(0).getJSONObject("file_item").put("md5","00000000000000000000000000000000");f.download=encrypted(plain);JSONObject wrong=f.receive(wire);assertThrows(SecurityException.class,()->freeze(f,window,wrong,0));
                f.download=new byte[17];JSONObject partial=f.receive(wire("231","voice","",plain,f.base+"/cdn-image"));assertThrows(SecurityException.class,()->freeze(f,window,partial,0));
                f.redirectCdn=true;f.download=encrypted(plain);JSONObject redirect=f.receive(wire("232","file","redirect.bin",plain,f.base+"/cdn-image"));assertThrows(IllegalStateException.class,()->freeze(f,window,redirect,0));
                assertEquals(0,AttachmentRepository.get(f.android).book.list().length());assertEquals(0,f.models.get());assertEquals(0,f.delivered.size());
            }
        }
    }

    @Test public void actualBookCommitCanBeReassociatedAfterInboxReferenceLossWithoutSecondDownloadAndQuotedItemPositionStaysExact()throws Exception {
        try(var f=new WeixinOwnerImageJourneyTest.Fixture()){
            f.bind();consent(f,true);Object owner=new Object();f.module.setNativeOwnerForeground(owner,true);byte[] plain="small original material".getBytes(StandardCharsets.UTF_8);f.download=encrypted(plain);
            JSONObject wire=wire("240","file","selected.txt",plain,f.base+"/cdn-image");wire.getJSONArray("item_list").getJSONObject(0).put("ref_msg",new JSONObject().put("svr_id","239").put("message_item",new JSONObject().put("type",1).put("text_item",new JSONObject().put("text","Quoted instructions do not authorize a new input"))));JSONObject event=f.receive(wire);
            assertEquals("quote",event.getJSONObject("messageParts").getJSONArray("parts").getJSONObject(0).getString("kind"));assertEquals(1,ChannelMessageParts.ownerMediaPositions(event.getJSONObject("messageParts")).getInt(0));
            try(var window=f.module.openOwnerMediaWindow(owner)){
                JSONObject saved=freeze(f,window,event,1);assertEquals(0,saved.getJSONObject("source").getInt("sourceItemIndex"));assertEquals(1,saved.getJSONObject("source").getInt("itemIndex"));
                ChannelCore original=core(f.module);var state=ChannelCore.class.getDeclaredField("state");state.setAccessible(true);JSONObject ledger=(JSONObject)state.get(original);JSONArray inbox=ledger.getJSONArray("inbox");for(int n=0;n<inbox.length();n++)if(inbox.getJSONObject(n).getString("id").equals(event.getString("id")))inbox.getJSONObject(n).remove("ownerMediaArtifacts");
                JSONObject restored=freeze(f,window,event,1);assertEquals(saved.getString("id"),restored.getString("id"));assertEquals(1,f.cdn.get());assertEquals(1,AttachmentRepository.get(f.android).book.list().length());
            }
        }
    }
}
