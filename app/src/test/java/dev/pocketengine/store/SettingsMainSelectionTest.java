package dev.pocketengine.store;

import android.app.Application;
import android.content.SharedPreferences;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.runtime.models.ModelBinding;
import dev.pocketengine.runtime.models.ModelConnectionService;
import dev.pocketengine.runtime.models.ModelGroup;
import dev.pocketengine.runtime.models.ModelTargetRef;
import dev.pocketengine.runtime.models.ReasoningOptions;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.file.Files;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Actual native preferences transactions and admission captures, not device or live-provider acceptance. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class SettingsMainSelectionTest {
    private enum Failure { NONE, BEFORE, AFTER, THROW_AFTER }
    private static final class Fixture {
        final SharedPreferences real,prefs;
        final SettingsStore settings;
        final JSONObject a,b,group;
        int commits;
        Failure failure=Failure.NONE;
        Fixture()throws Exception {
            real=RuntimeEnvironment.getApplication().getSharedPreferences("main-selection-"+java.util.UUID.randomUUID(),0);
            prefs=(SharedPreferences)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{SharedPreferences.class},(proxy,method,args)->{
                try {
                    Object result=method.invoke(real,args);
                    if(!method.getName().equals("edit"))return result;
                    SharedPreferences.Editor editor=(SharedPreferences.Editor)result;
                    return Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{SharedPreferences.Editor.class},(owner,operation,values)->{
                        try {
                            if(operation.getName().equals("commit")){
                                commits++;Failure mode=failure;failure=Failure.NONE;
                                if(mode==Failure.BEFORE)return false;
                                Object reply=operation.invoke(editor,values);
                                if(mode==Failure.THROW_AFTER)throw new IllegalStateException("fixture disk outcome unknown");
                                return mode==Failure.AFTER?false:reply;
                            }
                            Object reply=operation.invoke(editor,values);return reply==editor?owner:reply;
                        }catch(InvocationTargetException error){throw error.getCause();}
                    });
                }catch(InvocationTargetException error){throw error.getCause();}
            });
            settings=new SettingsStore(prefs,new SettingsStore.ApiSecrets(){
                public String get(String slot){throw new AssertionError("no fixture credential slot");}
                public void put(String slot,String value){throw new AssertionError("no fixture credential slot");}
            });
            settings.apiConnections();
            a=settings.saveApiConnection("",0,"Retained A",input("a","retained-a"));
            b=settings.saveApiConnection("",0,"Picker B",input("b","picker-b"));
            settings.selectApiConnection(a.getString("id"),a.getLong("revision"));
            ModelTargetRef second=ModelTargetRef.exact(b.getString("id"),"picker-b");
            ModelGroup definition=new ModelGroup("fixture-group",1,"Keep group",Set.of(b.getString("id")),
                    List.of(new ModelGroup.Candidate("candidate-b",second,true,Set.of("MAIN","MEMBER.worker"),Set.of("APP_OWNER"))),1,0);
            group=settings.saveModelGroup("",0,"Keep group",definition.toJson());
            settings.selectModelPurpose("SUMMARY",ModelTargetRef.exact(a.getString("id"),"summary-a"));
            settings.selectModelPurpose("RELAY",ModelTargetRef.exact(a.getString("id"),"relay-a"));
            settings.selectModelPurpose("MEMBER.worker",second);
            exactMain();commits=0;
        }
        void exactMain()throws Exception{settings.selectModelPurpose("MAIN",ModelTargetRef.exact(b.getString("id"),"picker-b"));}
        void groupMain()throws Exception{settings.selectModelPurpose("MAIN",ModelTargetRef.group(group.getString("groupId"),group.getLong("revision")));}
        ModelBinding catalog()throws Exception{return settings.configureApiCatalog(null,ModelBinding.Format.OPENAI,URI.create("https://a.fixture/v1/models")).capabilityBinding;}
        JSONObject withoutMain()throws Exception{JSONObject value=settings.modelGroupsSnapshot();value.getJSONObject("purposeBindings").remove("MAIN");return value;}
        void preserved(JSONObject expected)throws Exception{assertEquals(expected.toString(),settings.modelGroupsSnapshot().toString());assertNull(settings.modelPurposeTarget("MAIN"));}
    }
    private static JSONObject input(String host,String model)throws Exception{return new JSONObject().put("baseUrl","https://"+host+".fixture/v1").put("provider","openai_chat").put("model",model).put("sendScreenshots",false);}
    private static JSONObject account()throws Exception{return new JSONObject().put("provider","chatgpt_plan").put("accountId","original-account").put("accountVersion",7).put("modelId","account-model");}
    private static void retained(ModelConfig old,Fixture f)throws Exception{
        assertEquals("picker-b",old.model);assertEquals(f.b.getString("id"),old.capabilityBinding.connectionId());
        ModelConfig live=f.settings.currentForCaptured(old);assertEquals(old.model,live.model);assertEquals(old.capabilityBinding,live.capabilityBinding);assertSame(old.routePlan,live.routePlan);
    }
    @Test public void apiMainToAccountReplacesOnlyMainForFutureAdmissions()throws Exception {
        Fixture f=new Fixture();ModelConfig old=f.settings.get();JSONObject expected=f.withoutMain();
        ModelConfig chosen=f.settings.selectAccountModel(account(),ReasoningOptions.DEFAULT,0,512);
        assertTrue(chosen.usesAccount());assertTrue(f.settings.get().usesAccount());assertEquals("account-model",f.settings.get().model);
        assertTrue(f.settings.selectedAccountModel().matches(chosen.accountModel));assertEquals(1,f.commits);f.preserved(expected);retained(old,f);
        assertEquals("retained-a",f.settings.apiConfigForCatalog().model);
        assertTrue(new SettingsStore(f.prefs).get().usesAccount());
    }
    @Test public void groupMainToCatalogReplacesOnlyMainInOneCommit()throws Exception {
        Fixture f=new Fixture();ModelBinding binding=f.catalog();f.groupMain();ModelConfig old=f.settings.get();JSONObject expected=f.withoutMain();f.commits=0;
        f.settings.selectCatalogModel(binding,"catalog-choice",512,ReasoningOptions.DEFAULT,0);
        ModelConfig next=f.settings.get();assertFalse(next.usesAccount());assertEquals("catalog-choice",next.model);assertEquals(binding,next.capabilityBinding);
        assertEquals(1,f.commits);f.preserved(expected);retained(old,f);assertEquals(f.group.getString("groupId"),old.routePlan.requestedAlias);
        assertEquals("catalog-choice",new SettingsStore(f.prefs).get().model);
    }
    @Test public void exactMainToRetainedApiChangesActualMainInOneCommit()throws Exception {
        Fixture f=new Fixture();ModelConfig old=f.settings.get();JSONObject expected=f.withoutMain();
        f.settings.useApiKeyModel();assertEquals("retained-a",f.settings.get().model);assertEquals(1,f.commits);f.preserved(expected);retained(old,f);
    }
    @Test public void selectConnectionDoesNotCommitRouteBeforeClearingMain()throws Exception {
        Fixture f=new Fixture();JSONObject expected=f.withoutMain();ModelConfig old=f.settings.get();
        ModelConnectionService service=new ModelConnectionService(f.settings,Files.createTempDirectory("main-selection-catalog").resolve("catalog.json"),Clock.systemUTC());
        service.selectConnection(f.a.getString("id"),f.a.getLong("revision"));
        assertEquals("retained-a",f.settings.get().model);assertEquals(1,f.commits);f.preserved(expected);retained(old,f);
    }
    @Test public void explicitBasicApiSaveAlsoReplacesMainWithoutChangingGroupDefinitions()throws Exception {
        Fixture f=new Fixture();JSONObject expected=f.withoutMain();ModelConfig old=f.settings.get();
        f.settings.save(new JSONObject().put("model","saved-a"));assertEquals("saved-a",f.settings.get().model);assertEquals(1,f.commits);f.preserved(expected);retained(old,f);
    }
    @Test public void retainedProfileEditDoesNotReplaceMainOrOtherPurposeBindings()throws Exception {
        Fixture f=new Fixture();JSONObject expected=f.settings.modelGroupsSnapshot();
        f.settings.saveApiConnection(f.a.getString("id"),f.a.getLong("revision"),"Renamed A",new JSONObject().put("model","edited-a"));
        assertEquals("picker-b",f.settings.get().model);assertEquals(expected.toString(),f.settings.modelGroupsSnapshot().toString());assertEquals(1,f.commits);
    }
    @Test public void legacySelectionsAlsoReplaceAnExactAccountMain()throws Exception {
        for(String operation:List.of("account","api","catalog","save")){
            SharedPreferences prefs=RuntimeEnvironment.getApplication().getSharedPreferences("legacy-main-"+java.util.UUID.randomUUID(),0);
            SettingsStore settings=new SettingsStore(prefs);settings.save(input("legacy","legacy-model"));
            ModelBinding binding=settings.configureApiCatalog(null,ModelBinding.Format.OPENAI,URI.create("https://legacy.fixture/v1/models")).capabilityBinding;
            settings.selectAccountModel(account());settings.selectModelPurpose("MAIN",ModelTargetRef.exact("account:original-account","account-model"));
            ModelConfig old=settings.get();
            switch(operation){
                case "account" -> settings.selectAccountModel(account().put("modelId","new-account-model"));
                case "api" -> settings.useApiKeyModel();
                case "catalog" -> settings.selectCatalogModel(binding,"catalog-choice",512,ReasoningOptions.DEFAULT,0);
                case "save" -> settings.save(new JSONObject().put("model","saved-model"));
                default -> throw new AssertionError(operation);
            }
            assertNull(settings.modelPurposeTarget("MAIN"));assertEquals("account-model",old.model);
            assertEquals(operation.equals("account"),settings.get().usesAccount());
            assertEquals(switch(operation){case "account" -> "new-account-model";case "api" -> "legacy-model";case "catalog" -> "catalog-choice";default -> "saved-model";},settings.get().model);
            assertFalse(settings.apiConnectionsSnapshot().getBoolean("migrated"));
        }
    }
    @Test public void failedSwitchNeverLeavesRouteAndMainHalfReplaced()throws Exception {
        for(Failure failure:List.of(Failure.BEFORE,Failure.AFTER,Failure.THROW_AFTER))for(String operation:List.of("account","api","catalog","connection","save")){
            Fixture f=new Fixture();ModelBinding binding=operation.equals("catalog")?f.catalog():null;
            Map<String,?> old=f.real.getAll();JSONObject expected=f.withoutMain();f.commits=0;f.failure=failure;
            assertThrows(operation+" "+failure,SettingsStore.PersistenceUncertain.class,()->{
                switch(operation){
                    case "account" -> f.settings.selectAccountModel(account());
                    case "api" -> f.settings.useApiKeyModel();
                    case "catalog" -> f.settings.selectCatalogModel(binding,"catalog-choice",512,ReasoningOptions.DEFAULT,0);
                    case "connection" -> new ModelConnectionService(f.settings,Files.createTempDirectory("main-failure").resolve("catalog.json"),Clock.systemUTC()).selectConnection(f.a.getString("id"),f.a.getLong("revision"));
                    case "save" -> f.settings.save(new JSONObject().put("model","saved-a"));
                    default -> throw new AssertionError(operation);
                }
            });
            assertEquals(operation+" "+failure,1,f.commits);
            if(failure==Failure.BEFORE)assertEquals(old,f.real.getAll());
            else{
                assertEquals(operation.equals("account")?"account":"api_key",f.real.getString("modelRoute",""));
                assertEquals(expected.toString(),new JSONObject(f.real.getString("modelGroups","")).toString());
            }
            assertThrows(SettingsStore.PersistenceUncertain.class,()->f.settings.get());
            assertThrows(SettingsStore.PersistenceUncertain.class,()->new SettingsStore(f.prefs).get());
            assertThrows(SettingsStore.PersistenceUncertain.class,()->f.settings.modelGroupsSnapshot());
        }
    }
}
