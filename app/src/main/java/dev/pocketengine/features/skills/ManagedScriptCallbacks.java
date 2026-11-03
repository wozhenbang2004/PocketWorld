package dev.pocketengine.features.skills;

import dev.pocketengine.core.RunControl;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

/** One original operation's finite Unix request scope, not an execution engine or
 * recovery authority. The native gate/publisher are retained objects, never JSON. */
final class ManagedScriptCallbacks implements AutoCloseable {
    @FunctionalInterface interface Current {void check()throws Exception;}
    @FunctionalInterface interface Publish {JSONObject share(String path)throws Exception;}
    private final ManagedScriptCallbackTransport.Server server;
    private final RunControl control;
    private final Current nativeCurrent;
    private final Publish publish;
    private final String operation,nonce,peerCredentialView;
    private final long pauseRevision,expiresAt,untilNanos;
    private final AtomicBoolean closed=new AtomicBoolean(),started=new AtomicBoolean();
    private final CountDownLatch stopped=new CountDownLatch(1);
    private final Thread worker;
    private final Runnable cancel=this::close;
    private volatile ManagedScriptCallbackTransport.Connection active;
    private volatile boolean closeUnconfirmed;
    ManagedScriptCallbacks(ManagedScriptCallbackTransport.Factory transport,String operation,RunControl control,
            long expiresAt,int timeoutSeconds,Current nativeCurrent,Publish publish)throws Exception {
        this(transport,operation,control,expiresAt,timeoutSeconds,false,nativeCurrent,publish);
    }
    ManagedScriptCallbacks(ManagedScriptCallbackTransport.Factory transport,String operation,RunControl control,
            long expiresAt,int timeoutSeconds,boolean originalFakeRootView,Current nativeCurrent,Publish publish)throws Exception {
        this.operation=operation;this.control=control;this.expiresAt=expiresAt;this.nativeCurrent=nativeCurrent;this.publish=publish;
        peerCredentialView=originalFakeRootView?"proot-id0":"native";
        pauseRevision=control.pauseRevision();untilNanos=System.nanoTime()+TimeUnit.SECONDS.toNanos(timeoutSeconds+10L);
        byte[] secret=new byte[32];new SecureRandom().nextBytes(secret);nonce=Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        server=transport.open("pw-skill-"+java.util.UUID.randomUUID().toString().replace("-",""));
        worker=new Thread(this::serve,"skill-private-output-"+operation.substring(0,8));worker.setDaemon(true);
    }
    JSONObject descriptor()throws Exception{return new JSONObject().put("format","pocketworld.skill-private-output-endpoint.v1").put("endpoint",server.endpoint()).put("nonce",nonce).put("operationId",operation).put("hostUid",server.hostUid()).put("hostPid",server.hostPid()).put("peerCredentialView",peerCredentialView);}
    void start()throws Exception {
        if(!started.compareAndSet(false,true))throw new IllegalStateException("script_callback_scope_already_started");
        control.onCancel(cancel);try{light();worker.start();}catch(Exception|Error failed){close();stopped.countDown();throw failed;}
    }
    private void light()throws Exception {
        if(closed.get()||control.isCancelled()||control.isPaused()||control.pauseRevision()!=pauseRevision||System.currentTimeMillis()>expiresAt||System.nanoTime()>untilNanos||Thread.currentThread().isInterrupted())throw new IOException("script_callback_original_scope_closed");
    }
    void checkCurrent()throws Exception {
        try{light();control.check();nativeCurrent.check();light();}catch(Exception failed){close();throw failed;}
    }
    private void serve() {
        try{
            for(int count=0;count<ManagedScriptCallbackWire.MAX_REQUESTS;){
                light();ManagedScriptCallbackTransport.Connection channel=server.accept();if(channel==null){Thread.sleep(10);continue;}count++;
                active=channel;try(ManagedScriptCallbackTransport.Connection original=channel){
                    light();if(!channel.sameHostUid())continue;
                    byte[] frame=read(channel);ManagedScriptCallbackWire.Request request=ManagedScriptCallbackWire.request(frame,nonce,operation);
                    checkCurrent();JSONObject result;
                    try{result=publish.share(request.path);}catch(Exception uncertain){result=unknown();}
                    // Publication can already have entered its original durable slot. If scope
                    // closes now, do not send a late success or infer an absent side effect.
                    checkCurrent();write(channel,ManagedScriptCallbackWire.reply(request.id,operation,result));
                }catch(InterruptedException stopped){throw stopped;}
                catch(Exception unavailable){/* Body-free; never log nonce, endpoint, frame or exception text. */}
                finally{active=null;}
            }
        }catch(Exception ended){/* The original operation/publisher keeps its actual receipt. */}
        finally{close();stopped.countDown();}
    }
    private byte[] read(ManagedScriptCallbackTransport.Connection channel)throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);ByteArrayOutputStream frame=new ByteArrayOutputStream();byte[] buffer=new byte[512];
        while(true){light();if(System.nanoTime()>deadline)throw new IOException("script_callback_frame_timeout");int count=channel.read(buffer,0,buffer.length);
            if(count<0)throw new IOException("script_callback_incomplete_frame");if(count==0){Thread.sleep(10);continue;}
            for(int at=0;at<count;at++){if(buffer[at]=='\n'){if(at!=count-1)throw new IOException("script_callback_extra_frame");return frame.toByteArray();}if(frame.size()>=ManagedScriptCallbackWire.MAX_REQUEST-1)throw new IOException("script_callback_frame_too_large");frame.write(buffer[at]);}
        }
    }
    private void write(ManagedScriptCallbackTransport.Connection channel,byte[] bytes)throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);int sent=0;
        while(sent<bytes.length){light();if(System.nanoTime()>deadline)throw new IOException("script_callback_reply_unconfirmed");int count=channel.write(bytes,sent,bytes.length-sent);
            if(count<0)throw new IOException("script_callback_reply_unconfirmed");if(count==0)Thread.sleep(10);else sent+=count;
        }
    }
    private static JSONObject unknown()throws Exception{return new JSONObject().put("ok",false).put("accepted",JSONObject.NULL).put("status","unknown").put("code","script_private_output_unconfirmed").put("replayAllowed",false).put("externalTransmission",false).put("executionAuthorized",false);}
    boolean awaitStopped(long millis)throws Exception{return stopped.await(millis,TimeUnit.MILLISECONDS);}
    boolean closeConfirmed(){return stopped.getCount()==0&&!closeUnconfirmed;}
    @Override public void close() {
        if(!closed.compareAndSet(false,true))return;control.removeCancelAction(cancel);
        try{server.close();}catch(Exception unconfirmed){closeUnconfirmed=true;}
        ManagedScriptCallbackTransport.Connection original=active;if(original!=null)try{original.close();}catch(Exception unconfirmed){closeUnconfirmed=true;}
        if(Thread.currentThread()!=worker)worker.interrupt();
        if(!started.get())stopped.countDown();
    }
}
