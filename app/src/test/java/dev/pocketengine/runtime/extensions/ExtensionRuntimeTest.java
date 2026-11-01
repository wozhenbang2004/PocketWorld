package dev.pocketengine.runtime.extensions;

import static org.junit.Assert.*;
import dev.pocketengine.core.RunControl;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Test;

/** Native authority tests plus real JS semantics in a separate Node VM fixture, not Android proof. */
public final class ExtensionRuntimeTest {
    private static final String TASK="11111111-1111-1111-1111-111111111111",RUN="22222222-2222-2222-2222-222222222222",INSTALL="33333333-3333-3333-3333-333333333333";
    private static RunControl control(){RunControl control=new RunControl();control.bindHostRun(TASK,RUN,2);return control;}
    private static ExtensionProgram program(String text)throws Exception {byte[] bytes=text.getBytes(StandardCharsets.UTF_8);return new ExtensionProgram("test.extension","1",INSTALL,bytes,ExtensionProgram.sha256(bytes),2000);}
    private static ExtensionRuntime runtime(ExtensionRuntime.Evaluator evaluator,ExtensionRuntime.Authority authority){return new ExtensionRuntime(evaluator,new ExtensionRuntime.Binding(TASK,RUN,2),authority);}
    private static JSONObject descriptor()throws Exception {
        JSONObject schema=new JSONObject().put("type","object").put("properties",new JSONObject().put("text",new JSONObject().put("type","string"))).put("required",new JSONArray().put("text")).put("additionalProperties",false);
        return new JSONObject().put("tools",new JSONArray().put(new JSONObject().put("name","echo").put("description","Pure fixture").put("parameters",schema)))
                .put("hooks",new JSONObject().put("context",true).put("beforeTool",true).put("onEvent",true));
    }
    private static String tool(ExtensionRuntime runtime,RunControl control)throws Exception{return runtime.tools(control).getJSONObject(0).getJSONObject("function").getString("name");}
    @FunctionalInterface private interface Response {JSONObject result(JSONObject request,RunControl control)throws Exception;}
    private static final class Fake implements ExtensionRuntime.Evaluator {
        int calls,closes;Response response=(request,control)->new JSONObject();JSONObject definition=descriptor();
        Fake()throws Exception { }
        @Override public String evaluate(byte[] code,JSONObject request,int timeout,RunControl control,ExtensionRuntime.CurrentCheck current)throws Exception {calls++;current.check();return new JSONObject().put("descriptor",definition).put("result",response.result(request,control)).toString();}
        @Override public void close(){closes++;}
    }

    @Test public void exactSourceAndDefensiveBytes()throws Exception {
        byte[] bytes="defineExtension({})".getBytes(StandardCharsets.UTF_8);String hash=ExtensionProgram.sha256(bytes);ExtensionProgram program=new ExtensionProgram("test","1",INSTALL,bytes,hash,1000);
        bytes[0]=0;byte[] returned=program.sourceBytes();returned[0]=0;assertEquals('d',program.sourceBytes()[0]);
        assertThrows(SecurityException.class,()->new ExtensionProgram("test","1",INSTALL,"different".getBytes(StandardCharsets.UTF_8),hash,1000));
        byte[] invalid={(byte)0xc3,0x28};assertThrows(Exception.class,()->new ExtensionProgram("test","1",INSTALL,invalid,ExtensionProgram.sha256(invalid),1000));
    }
    @Test public void unboundOrReplacedRunCannotEvenDescribe()throws Exception {
        Fake evaluator=new Fake();ExtensionRuntime runtime=runtime(evaluator,(program,binding,control)->{});
        assertThrows(SecurityException.class,()->runtime.mount(program("x"),new RunControl()));assertEquals(0,evaluator.calls);
        RunControl other=new RunControl();other.bindHostRun(TASK,RUN,3);assertThrows(SecurityException.class,()->runtime.mount(program("x"),other));assertEquals(0,evaluator.calls);runtime.close();
    }
    @Test public void nativeRevocationBlocksCachedToolsBeforeAnotherEvaluation()throws Exception {
        Fake evaluator=new Fake();AtomicBoolean enabled=new AtomicBoolean(true);RunControl control=control();ExtensionRuntime runtime=runtime(evaluator,(program,binding,run)->{if(!enabled.get())throw new SecurityException("revoked");});
        runtime.mount(program("x"),control);String tool=tool(runtime,control);enabled.set(false);
        assertThrows(SecurityException.class,()->runtime.execute(tool,new JSONObject().put("text","private"),control));assertEquals(1,evaluator.calls);runtime.close();
    }
    @Test public void unmountCancelsOwnedEvaluationAndRejectsItsLateValue()throws Exception {
        Fake evaluator=new Fake();RunControl control=control();ExtensionRuntime runtime=runtime(evaluator,(program,binding,run)->{});runtime.mount(program("x"),control);String tool=tool(runtime,control);
        evaluator.response=(request,operation)->{runtime.unmount(INSTALL);assertTrue(operation.isCancelled());return new JSONObject().put("value","late");};
        assertThrows(SecurityException.class,()->runtime.execute(tool,new JSONObject().put("text","x"),control));assertFalse(runtime.supports(tool));assertEquals(0,runtime.mounted().length());assertFalse(control.isCancelled());runtime.close();
    }
    @Test public void denialCannotRewriteArgumentsOrForgeAHostReceipt()throws Exception {
        Fake evaluator=new Fake();RunControl control=control();ExtensionRuntime runtime=runtime(evaluator,(program,binding,run)->{});runtime.mount(program("x"),control);
        JSONObject original=new JSONObject().put("path","report.txt");evaluator.response=(request,run)->new JSONObject().put("block",false).put("arguments",new JSONObject().put("path","other"));
        assertThrows(IllegalArgumentException.class,()->runtime.beforeTool("workspace_write",original,control));assertEquals("report.txt",original.getString("path"));runtime.close();
    }
    @Test public void pauseEvenWhenImmediatelyResumedInvalidatesLateCallback()throws Exception {
        Fake evaluator=new Fake();RunControl control=control();ExtensionRuntime runtime=runtime(evaluator,(program,binding,run)->{});runtime.mount(program("x"),control);
        evaluator.response=(request,run)->{control.pause();control.resume();return new JSONObject().put("text","old screen");};
        assertThrows(SecurityException.class,()->runtime.context(new JSONObject(),control));runtime.close();
    }
    @Test public void closureIsIdempotentAndWithdrawsAllTools()throws Exception {
        Fake evaluator=new Fake();RunControl control=control();ExtensionRuntime runtime=runtime(evaluator,(program,binding,run)->{});runtime.mount(program("x"),control);String name=tool(runtime,control);
        runtime.close();runtime.close();assertEquals(1,evaluator.closes);assertFalse(runtime.supports(name));assertThrows(IllegalStateException.class,()->runtime.tools(control));
    }
    @Test public void activationRevisionCannotReviveThePreviousToolRegistration()throws Exception {
        Fake evaluator=new Fake();RunControl control=control();AtomicLong revision=new AtomicLong(1);
        ExtensionRuntime runtime=runtime(evaluator,(program,binding,run)->{if(program.approvalRevision!=revision.get())throw new SecurityException("changed approval");});
        ExtensionProgram first=program("x");runtime.mount(first,control);String previous=tool(runtime,control);revision.set(2);
        assertThrows(SecurityException.class,()->runtime.tools(control));runtime.unmount(INSTALL);
        runtime.mount(new ExtensionProgram(first.id,first.version,INSTALL,2,first.sourceBytes(),first.sourceSha256,1000),control);assertNotEquals(previous,tool(runtime,control));runtime.close();
    }
    @Test public void failedRuntimeCloseMayRetryOnlyTheOwnedCleanup()throws Exception {
        Fake fake=new Fake();ExtensionRuntime runtime=runtime(new ExtensionRuntime.Evaluator(){
            @Override public String evaluate(byte[] script,JSONObject request,int timeout,RunControl control,ExtensionRuntime.CurrentCheck current)throws Exception{return fake.evaluate(script,request,timeout,control,current);}
            @Override public void close(){if(++fake.closes==1)throw new IllegalStateException("not yet closed");}
        },(program,binding,control)->{});
        assertThrows(IllegalStateException.class,runtime::close);runtime.close();runtime.close();assertEquals(2,fake.closes);
    }
    @Test public void actualJavascriptToolsContextDenialAndLifecycleUseOneRunState()throws Exception {
        Node evaluator=new Node();RunControl control=control();ExtensionRuntime runtime=runtime(evaluator,(program,binding,run)->{});
        String source="""
                defineExtension({
                  tools:[{name:'add',description:'Add two numbers without host effects',parameters:{type:'object',properties:{a:{type:'integer'},b:{type:'integer'}},required:['a','b'],additionalProperties:false},execute(args,state){return {value:args.a+args.b,state:{...state,last:args.a+args.b}};}}],
                  context(input,state){return {text:'turns='+(state.turns||0)+'; '+input.goal,state};},
                  beforeTool(call,state){return {block:call.name==='workspace_write',reason:call.name==='workspace_write'?'Read-only project policy':'',state};},
                  onEvent(event,state){return {state:{...state,turns:(state.turns||0)+(event.type==='turn_start'?1:0)}};}
                });
                """;
        try{
            runtime.mount(program(source),control);runtime.event("run_start",new JSONObject(),control);runtime.event("turn_start",new JSONObject(),control);
            JSONObject context=runtime.context(new JSONObject().put("goal","sum"),control);assertEquals("turns=1; sum",context.getString("text"));assertFalse(context.getBoolean("executionAuthorized"));
            JSONObject result=runtime.execute(tool(runtime,control),new JSONObject().put("a",3).put("b",4),control);assertEquals(7,result.getInt("output"));assertFalse(result.getBoolean("businessVerified"));assertFalse(result.getBoolean("hostEffects"));
            ExtensionRuntime.Decision denied=runtime.beforeTool("workspace_write",new JSONObject().put("content","data"),control);assertFalse(denied.allowed);assertEquals("Read-only project policy",denied.reason);
            assertTrue(runtime.beforeTool("workspace_read",new JSONObject(),control).allowed);runtime.event("tool_result",new JSONObject().put("ok",true),control);runtime.event("run_end",new JSONObject(),control);
        }finally{runtime.close();}
    }
    @Test public void actualJavascriptCannotChangeItsToolIdentityBetweenDescribeAndCallback()throws Exception {
        Node evaluator=new Node();RunControl control=control();ExtensionRuntime runtime=runtime(evaluator,(program,binding,run)->{});
        String source="const method=JSON.parse(pocket.resources['extension-request.json']).method;defineExtension({tools:[{name:method==='describe'?'first':'changed',description:'Dynamic fixture',parameters:{type:'object'},execute(){return {value:'not admitted'};}}],context(){return {text:'not admitted'};}});";
        try{runtime.mount(program(source),control);assertThrows(IllegalArgumentException.class,()->runtime.context(new JSONObject(),control));}finally{runtime.close();}
    }

    static final class Node implements ExtensionRuntime.Evaluator {
        private Process active;
        Node(){try{Process check=new ProcessBuilder("node","--version").start();Assume.assumeTrue("Node fixture unavailable",check.waitFor(5,TimeUnit.SECONDS)&&check.exitValue()==0);}catch(Exception absent){Assume.assumeNoException(absent);}}
        @Override public String evaluate(byte[] script,JSONObject request,int maxMillis,RunControl control,ExtensionRuntime.CurrentCheck current)throws Exception {
            current.check();String runner="const vm=require('node:vm');let s='';process.stdin.setEncoding('utf8');process.stdin.on('data',x=>s+=x);process.stdin.on('end',async()=>{try{const p=JSON.parse(s);const pocket=Object.freeze({args:Object.freeze([]),resources:Object.freeze({'extension-request.json':JSON.stringify(p.request)})});const value=await vm.runInNewContext(p.script,{pocket},{timeout:p.timeout});process.stdout.write(value);}catch(e){process.exitCode=1;}});";
            active=new ProcessBuilder("node","-e",runner).start();Process process=active;Runnable cancel=process::destroyForcibly;control.onCancel(cancel);
            try{
                JSONObject input=new JSONObject().put("script",new String(script,StandardCharsets.UTF_8)).put("request",request).put("timeout",maxMillis);
                process.getOutputStream().write(input.toString().getBytes(StandardCharsets.UTF_8));process.getOutputStream().close();
                if(!process.waitFor(maxMillis+1000L,TimeUnit.MILLISECONDS))throw new IllegalStateException("Node fixture timeout");control.check();current.check();
                if(process.exitValue()!=0)throw new IllegalStateException("JS fixture failed");return new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
            }finally{control.removeCancelAction(cancel);process.destroyForcibly();active=null;}
        }
        @Override public void close(){if(active!=null)active.destroyForcibly();}
    }
}
