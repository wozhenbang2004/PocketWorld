package dev.pocketengine.browser;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.capabilities.ModelCapabilityAdapters;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Actual page-space state machine with an explicit native-page fixture; no Android/website claim. */
public final class BrowserTaskReturnSpaceTest {
    static JSONObject j(Object... pairs)throws Exception {JSONObject out=new JSONObject();for(int i=0;i<pairs.length;i+=2)out.put((String)pairs[i],pairs[i+1]);return out;}
    static Object call(Object value,String method,Class<?>[] types,Object... args)throws Exception {try{return value.getClass().getMethod(method,types).invoke(value,args);}catch(InvocationTargetException failure){Throwable cause=failure.getCause();if(cause instanceof Exception)throw (Exception)cause;throw new AssertionError(cause);}}
    static final class NativePages implements BrowserTaskSpace.Driver {
        final Map<String,BrowserTaskSpace.Tab> owned=new LinkedHashMap<>();final Map<String,JSONObject> pages=new LinkedHashMap<>();final String owner=UUID.randomUUID().toString();String selected=owner;
        NativePages()throws Exception {pages.put(owner,j("url","https://owner.example/untouched","navigation",1L,"title","我的页面"));}
        public JSONObject metadata(BrowserTaskSpace.Tab tab)throws Exception {if(owned.get(tab.tabId)!=tab)throw new SecurityException("wrong task page");return new JSONObject(pages.get(tab.tabId).toString());}
        public JSONObject ownerMetadata(String tabId)throws Exception {if(owned.containsKey(tabId)||!pages.containsKey(tabId))throw new SecurityException("not the handed-off owner page");return new JSONObject(pages.get(tabId).toString());}
        public void open(BrowserTaskSpace.Tab tab,String url,BrowserTaskSpace.Gate gate)throws Exception {gate.check();gate.entered();pages.put(tab.tabId,j("url",url,"navigation",1L,"title","原任务网页"));owned.put(tab.tabId,tab);selected=tab.tabId;}
        public void select(BrowserTaskSpace.Tab tab,BrowserTaskSpace.Gate gate)throws Exception {gate.check();metadata(tab);selected=tab.tabId;}
        public void execute(BrowserTaskSpace.Tab tab,String command,JSONObject args,BrowserTaskSpace.Gate gate,CompletableFuture<JSONObject> result)throws Exception {gate.check();if(!command.equals("observe"))throw new UnsupportedOperationException(command);JSONObject page=metadata(tab);result.complete(tab.identity().put("observationId",UUID.randomUUID().toString()).put("navigation",page.getLong("navigation")).put("url",page.getString("url")).put("title","登录后的页面 fixture").put("text","重新观察后的资料").put("elements",new JSONArray()));}
        public JSONObject download(BrowserTaskSpace.Tab tab,String url,String operation,BrowserTaskSpace.Gate gate){throw new UnsupportedOperationException();}
        public void adoptOwner(BrowserTaskSpace.Tab tab,BrowserTaskSpace.Gate gate)throws Exception {gate.check();ownerMetadata(tab.tabId);owned.put(tab.tabId,tab);}
        public void returnOwner(BrowserTaskSpace.Tab tab,BrowserTaskSpace.Gate gate)throws Exception {adoptOwner(tab,gate);}
        public void releaseToOwner(BrowserTaskSpace.Tab tab){owned.remove(tab.tabId);}
        public boolean closeOwned(BrowserTaskSpace.Tab tab){if(owned.get(tab.tabId)!=tab)return false;owned.remove(tab.tabId);pages.remove(tab.tabId);return true;}
        public void abort(BrowserTaskSpace.Tab tab,CompletableFuture<JSONObject> result){}
    }
    static final class Fixture implements AutoCloseable {
        final RunControl original=new RunControl();final ModelCapabilityAdapters.Binding binding;final NativePages driver=new NativePages();final BrowserProfileRegistry profiles;final BrowserTaskSpace space;final AtomicBoolean resource=new AtomicBoolean(true);
        Fixture()throws Exception {String task=UUID.randomUUID().toString(),run=UUID.randomUUID().toString();original.bindHostRun(task,run,7);binding=new ModelCapabilityAdapters.Binding(task,run,7,original,true,"");profiles=new BrowserProfileRegistry(new File(Files.createTempDirectory("browser-return-").toFile(),"profiles.json"));BrowserTaskSpace.Ui ui=new BrowserTaskSpace.Ui(){public boolean isMain(){return true;}public void post(Runnable work){work.run();}};
            space=new BrowserTaskSpace(binding,original,profiles.openSession(true),driver,ui,null,new BrowserTaskSpace.NativeEvents(){public void takenOver(ModelCapabilityAdapters.Binding b,RunControl c,String s,String t){assertSame(original,c);c.pause();resource.set(false);}public void ended(ModelCapabilityAdapters.Binding b,RunControl c,String s,JSONObject receipt){resource.set(false);}});
        }
        BrowserTaskSpace.Gate live(){return ()->{space.checkOriginal(original);if(!resource.get())throw new SecurityException("reservation lost");};}
        String open()throws Exception{return space.dispatch("open",j("url","https://account.example/login"),UUID.randomUUID().toString(),live()).get().getString("tabId");}
        JSONObject handoff(String tab)throws Exception {return space.dispatch("request_user",j("tabId",tab,"reason","请在此页登录后交还"),UUID.randomUUID().toString(),live()).get();}
        JSONObject pin(String tab)throws Exception {return (JSONObject)call(space,"returnPinNative",new Class<?>[]{String.class},tab);}
        JSONObject giveBack(JSONObject pin)throws Exception {resource.set(true);try{return (JSONObject)call(space,"returnOwnerNative",new Class<?>[]{JSONObject.class,BrowserTaskSpace.Gate.class},pin,(BrowserTaskSpace.Gate)()->{if(!original.isPaused()||original.isCancelled()||!resource.get())throw new SecurityException("original return gate rejected");});}catch(Exception failure){resource.set(false);throw failure;}}
        public void close()throws Exception {space.endNative("fixture_end");}
    }
    @Test public void explicitHandoffAndReturnKeepSpacePageTaskRunButInvalidatePreLoginObservation()throws Exception {try(Fixture f=new Fixture()){
        String tab=f.open();JSONObject old=f.space.dispatch("observe",j("tabId",tab),UUID.randomUUID().toString(),f.live()).get();long generation=old.getLong("tabGeneration");String space=f.space.spaceId;JSONObject handoff=f.handoff(tab);assertEquals("owner_control",handoff.getString("status"));assertTrue(f.original.isPaused());assertFalse(f.resource.get());assertFalse(f.driver.owned.containsKey(tab));
        f.driver.pages.get(tab).put("url","https://account.example/workspace?view=mine#result").put("navigation",2L);JSONObject pin=f.pin(tab);JSONObject returned=f.giveBack(pin);assertEquals(space,returned.getString("browserSpaceId"));assertEquals(f.binding.taskId,returned.getString("taskId"));assertEquals(f.binding.runId,returned.getString("runId"));assertEquals(7,returned.getInt("epoch"));assertEquals(tab,returned.getString("tabId"));assertTrue(returned.getBoolean("freshObservationRequired"));f.original.resumeAtPauseRevision(pin.getLong("pauseRevision"));
        assertThrows(SecurityException.class,()->f.space.pin(j("tabId",tab,"observationId",old.getString("observationId"))));JSONObject fresh=f.space.dispatch("observe",j("tabId",tab),UUID.randomUUID().toString(),f.live()).get();assertNotEquals(generation,fresh.getLong("tabGeneration"));assertNotEquals(old.getString("observationId"),fresh.getString("observationId"));assertTrue(fresh.getString("url").contains("view=mine"));assertTrue(f.driver.pages.containsKey(f.driver.owner));
    }}
    @Test public void sameNavigationWithChangedDestinationQueryCannotConsumeReturnConfirmation()throws Exception {try(Fixture f=new Fixture()){
        String tab=f.open();f.handoff(tab);JSONObject pin=f.pin(tab);f.driver.pages.get(tab).put("url","https://account.example/login?account=changed");assertThrows(SecurityException.class,()->f.giveBack(pin));assertTrue(f.original.isPaused());assertFalse(f.driver.owned.containsKey(tab));assertEquals("owner_control",f.space.snapshot().getString("status"));
    }}
    @Test public void anotherOwnerPageAndForeignSpaceCannotBeAdoptedByReturn()throws Exception {try(Fixture f=new Fixture()){
        String tab=f.open();f.handoff(tab);assertThrows(SecurityException.class,()->f.pin(f.driver.owner));JSONObject wrong=new JSONObject(f.pin(tab).toString()).put("browserSpaceId",UUID.randomUUID().toString());assertThrows(SecurityException.class,()->f.giveBack(wrong));assertFalse(f.driver.owned.containsKey(f.driver.owner));
    }}
    @Test public void profileChangeAndCancellationInvalidateReturnAndPreserveHandedOffPage()throws Exception {try(Fixture f=new Fixture()){
        String tab=f.open();f.handoff(tab);JSONObject pin=f.pin(tab);f.profiles.select(f.space.profile.profileId,f.profiles.revision(),true);assertThrows(SecurityException.class,()->f.giveBack(pin));assertTrue(f.driver.pages.containsKey(tab));
    }try(Fixture f=new Fixture()){
        String tab=f.open();f.handoff(tab);JSONObject pin=f.pin(tab);f.original.cancel();assertThrows(SecurityException.class,()->f.giveBack(pin));assertTrue(f.driver.pages.containsKey(tab));assertTrue(f.driver.pages.containsKey(f.driver.owner));
    }}
}
