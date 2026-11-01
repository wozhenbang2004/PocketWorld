package dev.pocketengine.features.channels;

import android.app.Application;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.runtime.ModelGateway;
import dev.pocketengine.runtime.models.*;
import java.net.URI;
import java.nio.file.Files;
import java.time.Clock;
import java.util.Base64;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static dev.pocketengine.runtime.models.ModelFacts.*;

/** Actual signed QQ fixture ingress, immutable group bytes, and API request projection. Not a live QQ/provider/Runner UI test. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class ChannelPictureProjectionTest {
    final GroupTaskFlowsTest keys=new GroupTaskFlowsTest();
    @Before public void keys(){keys.keyFixture();}
    @After public void closeKeys(){keys.removeFixture();}
    @Test public void nativeQuoteAndImageFreezeBeforeExactSameBytesReachSelectedApiAndNeverPrivateFiles()throws Exception {
        try(var f=new GroupTaskFlowsTest.Fixture()){
            byte[] picture=Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aH4sAAAAASUVORK5CYII=");f.attachment=picture;f.attachmentMime="image/png";
            f.incomingExtra=new JSONObject().put("message_type",103).put("message_scene",new JSONObject().put("ext",new JSONArray().put("ref_msg_idx=original-native-ref"))).put("msg_elements",new JSONArray().put(new JSONObject().put("msg_idx","original-native-ref").put("content","原平台引文，不是新的工具授权")));
            dev.pocketengine.features.attachments.AttachmentRepository.get(f.android).book.importBytes(new java.io.ByteArrayInputStream("PRIVATE OWNER FILE SENTINEL".getBytes(java.nio.charset.StandardCharsets.UTF_8)),"private.txt","text/plain",new JSONObject());
            f.enable("group.files");f.send("/file --tokens=60000 根据原引用检查图片",true,false);Runnable intake=f.work.poll();assertNotNull(intake);intake.run();assertEquals(f.channels.snapshot().toString(),1,f.downloads.get());assertEquals(f.channels.snapshot().toString(),1,f.tasks.list().length());
            JSONObject reference=f.groups.ownerSnapshot().getJSONArray("requests").getJSONObject(0);assertEquals("native_payload",reference.getJSONObject("messageParts").getJSONArray("parts").getJSONObject(0).getString("availability"));
            ModelBinding binding=new ModelBinding("image-fixture","fixture",ModelBinding.Protocol.OPENAI_CHAT,ModelBinding.Format.OPENAI,URI.create(f.base+"/v1/models"),"fixture-key",1,"fixture-install",1,false);
            ModelCatalog catalog=new ModelCatalog(Files.createTempDirectory("channel-image-catalog-").resolve("catalog.json"),Clock.systemUTC());catalog.bind(binding);ModelCatalog.ModelKey key=new ModelCatalog.ModelKey(binding,"group-flow-model");Map<Field,OwnerValue> values=new EnumMap<>(Field.class);values.put(Field.IMAGE_INPUT,OwnerValue.known(true));values.put(Field.CHAT_OUTPUT_PARAMETER,OwnerValue.known(ChatOutputParameter.MAX_COMPLETION_TOKENS));catalog.updateOwner(key,0,values,Set.of());ModelRequestPolicy.installHostSource(config->catalog.get(new ModelCatalog.ModelKey(config.capabilityBinding,config.model),3600000));
            ModelConfig selected=new ModelConfig(f.base+"/v1","fixture-api-key","group-flow-model",false,4,10,"openai_chat",256).withNativeCatalog(binding,ReasoningOptions.DEFAULT,512);
            f.model=wire->{JSONArray messages=wire.getJSONArray("messages"),content=messages.getJSONObject(messages.length()-1).getJSONArray("content");String data=null;for(int i=0;i<content.length();i++)if(content.getJSONObject(i).optString("type").equals("image_url"))data=content.getJSONObject(i).getJSONObject("image_url").getString("url");assertNotNull(data);assertArrayEquals(picture,Base64.getDecoder().decode(data.substring(data.indexOf(',')+1)));assertFalse(messages.toString().contains("/qq-attachment.txt"));return GroupTaskFlowsTest.message("fixture image wire accepted, not a claim of vision quality");};
            // Owner recovery only authorizes reads: it must never authorize a fresh image/model dispatch.
            f.groups.withOwnerTaskRead(reference.getString("requestId"),(request,read)->{assertThrows(SecurityException.class,()->ChannelGroupInputs.project(request,f.artifacts,selected,new JSONArray(),read,new RunControl()));return null;});
            assertNotNull(f.admittedRequest);assertNotNull(f.admittedExecution);f.admittedExecution.check();
            var request=f.admittedRequest;var authority=f.admittedExecution;assertEquals(reference.getString("requestId"),request.requestId);
            assertEquals(1,request.inputArtifacts().length());assertEquals(picture.length,request.inputArtifacts().getJSONObject(0).getLong("bytes"));assertEquals(512,ChannelGroupInputs.reserveTokens(request,selected));
            JSONArray initial=new JSONArray().put(new JSONObject().put("role","user").put("content",request.goal+ChannelMessageParts.context(request.event.messageParts())));RunControl control=new RunControl();JSONArray projected=ChannelGroupInputs.project(request,f.artifacts,selected,initial,authority,control);
            assertTrue(projected.toString().contains("原平台引文"));ModelGateway.complete(selected,projected,null,control);assertEquals(1,f.modelCalls.get());
            ModelConfig unsupported=new ModelConfig(f.base+"/v1","fixture-api-key","text-model",false,4,10);assertThrows(SecurityException.class,()->ChannelGroupInputs.project(request,f.artifacts,unsupported,initial,authority,new RunControl()));assertEquals(1,f.modelCalls.get());
            assertEquals(1,f.downloads.get());assertEquals(1,f.tasks.list().length());
        }
    }
}
