package dev.pocketengine.runtime;

import dev.pocketengine.core.AwsSessionCredentials;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.runtime.models.ModelBinding;
import dev.pocketengine.runtime.models.ModelRequestPolicy;
import dev.pocketengine.runtime.models.ModelRoutePlan;
import dev.pocketengine.runtime.usage.InferenceCapture;
import dev.pocketengine.runtime.usage.InferenceFailure;
import dev.pocketengine.runtime.usage.InferenceReceipt;
import dev.pocketengine.runtime.usage.LogicalInferenceAuthority;
import dev.pocketengine.runtime.usage.ProviderUsage;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

/** Actual Gateway logical/presentation/receipt methods with an explicit failing physical attempt.
 * No production endpoint bypass, HTTP, AWS account, Android store or platform stubs are used.
 * This class still needs the original complete production dependency closure to compile/run. */
public final class BedrockGatewayBoundaryTest {
    private static final String ACCESS="ASIA1234567890ABCDEF";
    private static final String SECRET="FixtureSecretKeyOnlyForTests0123456789ABCD";
    private static final String TOKEN=ACCESS+"FixtureSessionTokenOnlyForTests0123456789";
    private Object source,attribution,observer;

    @Before public void isolateNativeHosts()throws Exception {
        source=field(ModelRequestPolicy.class,"source").get(null);
        attribution=field(InferenceCapture.class,"budgetAttribution").get(null);
        observer=field(InferenceCapture.class,"presentationObserver").get(null);
        ModelRequestPolicy.installHostSource(selected->null);
        InferenceCapture.installBudgetAttribution((context,identity)->{});
        field(InferenceCapture.class,"presentationObserver").set(null,null);
    }
    @After public void restoreNativeHosts()throws Exception {
        field(ModelRequestPolicy.class,"source").set(null,source);
        field(InferenceCapture.class,"budgetAttribution").set(null,attribution);
        field(InferenceCapture.class,"presentationObserver").set(null,observer);
    }
    private static Field field(Class<?> type,String name)throws Exception {
        Field result=type.getDeclaredField(name);result.setAccessible(true);return result;
    }
    private static ModelConfig selected() {
        ModelConfig config=new ModelConfig("","","anthropic.claude-sonnet-4-6",false,4,15,"bedrock_anthropic",512)
                .withBedrockSession("eu-west-2",new AwsSessionCredentials(ACCESS,SECRET,TOKEN,System.currentTimeMillis()+120000));
        return config.withApiConnection(new ModelBinding("connection-original","aws-bedrock",ModelBinding.Protocol.BEDROCK_ANTHROPIC,
                ModelBinding.Format.NONE,URI.create(""),"credential-original",1,"installation-original",1,false));
    }
    private static ModelGateway.GatewayException failed(InferenceFailure failure)throws Exception {
        Constructor<ModelGateway.GatewayException> constructor=ModelGateway.GatewayException.class.getDeclaredConstructor(String.class,InferenceFailure.class);
        constructor.setAccessible(true);return constructor.newInstance("fixture physical failure",failure);
    }
    private static Object invoke(Method method,Object target,Object...args)throws Exception {
        try{return method.invoke(target,args);}catch(InvocationTargetException wrapped){
            if(wrapped.getCause() instanceof Exception exception)throw exception;
            if(wrapped.getCause() instanceof Error error)throw error;
            throw wrapped;
        }
    }
    private static final class Evidence implements LogicalInferenceAuthority,InferenceCapture.Sink {
        int physical,reservations,retries,commits;
        String state,completion,attemptState;
        final List<JSONObject> receipts=new ArrayList<>();
        public JSONObject begin(String id,String digest,JSONObject route,long deadline)throws Exception {
            return new JSONObject().put("deadlineAt",deadline).put("control",new JSONObject().put("maxAttempts",route.getInt("maxAttempts")));
        }
        public boolean matchesHistoryOrigin(String call,ModelConfig candidate,JSONObject data){return false;}
        public boolean reserveRetry(String id,String call,long delay){retries++;return true;}
        public void reserve(String id,String call,int number,String candidate,String reason){reservations++;}
        public void commit(String id,String call,long sequence){commits++;}
        public void attemptEnded(String id,String call,boolean dispatched,String value){assertTrue(dispatched);attemptState=value;}
        public void finish(String id,String value,String outcome,String stream){if(state==null){state=value;completion=outcome;}}
        public void started(InferenceReceipt receipt)throws Exception{receipts.add(receipt.toJson());}
        public void finished(InferenceReceipt receipt)throws Exception{receipts.add(receipt.toJson());}
    }
    private static Exception logicalFailure(InferenceFailure failure,boolean semantic,boolean cancel,Evidence evidence)throws Exception {
        ModelConfig first=selected(),second=selected().withModel("anthropic.claude-opus-4-6-v1");
        ModelRoutePlan plan=new ModelRoutePlan("fixture-group",0,"UNATTRIBUTED","APP_OWNER",
                List.of(new ModelRoutePlan.Candidate("first",first,""),new ModelRoutePlan.Candidate("second",second,"")),3,1);
        RunControl control=new RunControl();
        InferenceCapture.Target target=new InferenceCapture.Target(InferenceReceipt.Context.unattributed(),evidence,()->{},evidence);
        Class<?> physicalType=Class.forName("dev.pocketengine.runtime.ModelGateway$PhysicalAttempt");
        Object physical=Proxy.newProxyInstance(physicalType.getClassLoader(),new Class<?>[]{physicalType},(proxy,method,args)->{
            if(method.getName().equals("receivedText"))return semantic;
            if(method.getName().equals("run")) {
                evidence.physical++;InferenceCapture capture=(InferenceCapture)args[1];capture.dispatch();
                if(semantic)((ModelGateway.SemanticCommit)args[2]).beforeFlush(capture.callId,1);
                if(cancel){control.cancel();throw new InterruptedException("fixture cancellation");}
                throw failed(failure);
            }
            return null;
        });
        Method execute=ModelGateway.class.getDeclaredMethod("executeLogical",ModelConfig.class,JSONArray.class,JSONArray.class,
                RunControl.class,InferenceCapture.Target.class,String.class,String.class,physicalType,ModelGateway.CandidateAdmission.class);
        execute.setAccessible(true);
        try {
            invoke(execute,null,first.withRoutePlan(plan),new JSONArray().put(new JSONObject().put("role","user").put("content","fixture request")),
                    null,control,target,"INTERNAL",null,physical,(ModelGateway.CandidateAdmission)candidate->{});
            throw new AssertionError("failed physical attempt cannot return a model decision");
        }catch(Exception expected){return expected;}
    }
    private static void singleAttempt(Evidence evidence,String state,String attempt)throws Exception {
        assertEquals(1,evidence.physical);assertEquals(1,evidence.reservations);assertEquals(0,evidence.retries);
        assertEquals(state,evidence.state);assertEquals(attempt,evidence.attemptState);assertEquals(2,evidence.receipts.size());
        assertEquals(evidence.receipts.get(0).getString("callId"),evidence.receipts.get(1).getString("callId"));
        assertFalse(evidence.receipts.get(1).getJSONObject("failure").getBoolean("chargeKnown"));
    }
    @Test public void evenDefinite503DoesNotReplayOrAdvanceToAnotherCandidate()throws Exception {
        Evidence evidence=new Evidence();Exception error=logicalFailure(InferenceFailure.bedrockHttp(503,List.of("0"),List.of(),List.of("ServiceUnavailableException"),System.currentTimeMillis(),"{}"),false,false,evidence);
        assertTrue(error instanceof ModelGateway.GatewayException);singleAttempt(evidence,"FAILED","failed");
        assertEquals("failed",evidence.completion);assertEquals(0,evidence.commits);
    }
    @Test public void ambiguousDispatchedFailureKeepsOriginalCallUnknownWithoutReplay()throws Exception {
        for(InferenceFailure.Kind kind:List.of(InferenceFailure.Kind.NETWORK,InferenceFailure.Kind.TIMEOUT,InferenceFailure.Kind.INCOMPLETE_STREAM,InferenceFailure.Kind.PROTOCOL,InferenceFailure.Kind.UNKNOWN)) {
            Evidence evidence=new Evidence();logicalFailure(InferenceFailure.local(kind,System.currentTimeMillis()),false,false,evidence);
            singleAttempt(evidence,"UNKNOWN","unknown");assertEquals("unknown",evidence.completion);
        }
    }
    @Test public void partialTextAndCancellationKeepTheirOwnTerminalState()throws Exception {
        Evidence partial=new Evidence();logicalFailure(InferenceFailure.local(InferenceFailure.Kind.INCOMPLETE_STREAM,System.currentTimeMillis()),true,false,partial);
        singleAttempt(partial,"PARTIAL","partial");assertEquals(1,partial.commits);assertEquals("unknown",partial.completion);
        assertTrue(partial.receipts.get(1).getJSONObject("failure").getBoolean("partialTextReceived"));
        Evidence cancelled=new Evidence();assertTrue(logicalFailure(null,false,true,cancelled) instanceof InterruptedException);
        singleAttempt(cancelled,"CANCELLED","cancelled");assertEquals("CANCELLED",cancelled.receipts.get(1).getString("outcome"));
    }
    @Test public void bedrockHttpCannotEnterLegacyContextRepairWhileOldProviderKeepsItsContract()throws Exception {
        Method method=ModelGateway.class.getDeclaredMethod("httpFailure",ModelConfig.class,int.class,List.class,List.class,List.class,java.io.InputStream.class);method.setAccessible(true);
        byte[] body="{\"error\":{\"code\":\"context_length_exceeded\"}}".getBytes(StandardCharsets.UTF_8);
        ModelGateway.GatewayException bedrock=(ModelGateway.GatewayException)invoke(method,null,selected(),400,List.of(),List.of(),List.of(),new ByteArrayInputStream(body));
        assertEquals(InferenceFailure.Kind.HTTP,bedrock.observation().kind);
        ModelConfig old=new ModelConfig("https://api.example.invalid","fixture-key","model",false,4,15);
        ModelGateway.GatewayException original=(ModelGateway.GatewayException)invoke(method,null,old,400,List.of(),List.of(),List.of(),new ByteArrayInputStream(body));
        assertEquals(InferenceFailure.Kind.CONTEXT_LIMIT,original.observation().kind);
    }
    @Test public void originalStreamPresentationKeepsOverlappingSessionPrefixAcrossEveryBoundary()throws Exception {
        String raw="visible "+TOKEN+" / "+SECRET+" / "+ACCESS+" tail";
        for(int split=1;split<raw.length();split++) {
            List<ModelGateway.StreamEvent> events=new ArrayList<>();Object stream=stream(selected(),events);
            Method text=stream.getClass().getDeclaredMethod("text",String.class),finish=stream.getClass().getDeclaredMethod("finish");text.setAccessible(true);finish.setAccessible(true);
            invoke(text,stream,raw.substring(0,split));invoke(text,stream,raw.substring(split));invoke(finish,stream);
            StringBuilder visible=new StringBuilder();for(var event:events)if(event.kind==ModelGateway.StreamKind.TEXT_DELTA)visible.append(event.text);
            assertEquals("split="+split,"visible [密钥已隐藏] / [密钥已隐藏] / [密钥已隐藏] tail",visible.toString());
        }
    }
    private static Object stream(ModelConfig config,List<ModelGateway.StreamEvent> events)throws Exception {
        Class<?> type=Class.forName("dev.pocketengine.runtime.ModelGateway$StreamSession");
        Constructor<?> constructor=type.getDeclaredConstructor(ModelConfig.class,RunControl.class,ModelGateway.StreamSink.class,String.class,ModelGateway.SemanticCommit.class,RunControl.ModelRequest.class);
        constructor.setAccessible(true);return constructor.newInstance(config,new RunControl(),(ModelGateway.StreamSink)events::add,"fixture-call",(ModelGateway.SemanticCommit)(call,sequence)->{},null);
    }
    @Test public void providerFinishReasonAndRequestIdCannotDiscloseCapturedCredentials()throws Exception {
        Evidence evidence=new Evidence();ModelConfig config=selected();InferenceCapture capture=new InferenceCapture(config,new RunControl(),new InferenceCapture.Target(InferenceReceipt.Context.unattributed(),evidence));
        capture.dispatch();capture.observedBedrockRequestId(List.of(ACCESS));capture.usage.terminal(SECRET);
        capture.usage.observe(new JSONObject().put("usage",new JSONObject().put("input_tokens",7).put("output_tokens",2)),ProviderUsage.Stage.PARTIAL);
        capture.finish(InferenceReceipt.Outcome.FAILED);JSONObject receipt=evidence.receipts.get(1),usage=receipt.getJSONObject("usage");
        assertTrue(usage.isNull("finishReason"));assertFalse(receipt.getJSONObject("route").has("providerRequestId"));
        assertEquals(7,usage.getJSONObject("counters").getJSONObject("INPUT").getLong("value"));
        assertEquals("PARTIAL",usage.getJSONObject("counters").getJSONObject("INPUT").getString("stage"));
        assertTrue(usage.getJSONObject("counters").getJSONObject("TOTAL").isNull("value"));
        for(String secret:List.of(ACCESS,SECRET,TOKEN))assertFalse(receipt.toString().contains(secret));
    }
    @Test public void sourceReplacementCannotTurnIdentityOnlyCatalogIntoPermission()throws Exception {
        ModelConfig config=selected();ModelRequestPolicy.installHostSource(selected->{ModelRequestPolicy.installHostSource(other->null);return null;});
        ModelRequestPolicy.Rejected refusal=assertThrows(ModelRequestPolicy.Rejected.class,()->ModelRequestPolicy.resolve(config));
        assertEquals("catalog_host_changed",refusal.code);
    }
}
