package dev.pocketengine.features.ssh;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.HostKey;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpATTRS;
import com.jcraft.jsch.SftpException;
import dev.pocketengine.core.RunControl;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.net.Socket;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;

/** Real SSH/SFTP transport. No reconnect, retry, durable remote job or implicit host trust. */
public final class DirectSshClient {
    static final int MAX_FILE=262144,MAX_OUTPUT=262144;
    public static final long MAX_TRANSFER=20L*1024*1024;
    private DirectSshClient(){}

    private static Session session(JSch client,SshProfile profile,PinnedHostKeys keys)throws Exception {
        client.setHostKeyRepository(keys);Session session=client.getSession(profile.user,profile.host,profile.port);
        session.setHostKeyAlias(profile.hostAlias());session.setConfig("StrictHostKeyChecking","yes");
        session.setConfig("PreferredAuthentications","publickey,password");session.setConfig("MaxAuthTries","2");
        // Android uses the Java-8 base of this MR-JAR. Do not assume desktop EdDSA providers.
        session.setConfig("server_host_key","ecdsa-sha2-nistp256,rsa-sha2-512,rsa-sha2-256");
        session.setConfig("kex","ecdh-sha2-nistp256,diffie-hellman-group14-sha256");
        session.setConfig("cipher.c2s","aes128-ctr,aes256-ctr");session.setConfig("cipher.s2c","aes128-ctr,aes256-ctr");
        session.setConfig("mac.c2s","hmac-sha2-256,hmac-sha2-512");session.setConfig("mac.s2c","hmac-sha2-256,hmac-sha2-512");
        session.setServerAliveInterval(5000);session.setServerAliveCountMax(1);session.setTimeout(10000);return session;
    }

    public static JSONObject probe(SshProfile profile)throws Exception {
        PinnedHostKeys keys=new PinnedHostKeys(profile,true);Session session=session(new JSch(),profile,keys);
        OwnedSocket sockets=new OwnedSocket(null);session.setSocketFactory(sockets);
        session.setConfig("PreferredAuthentications","none");
        try{session.connect(10000);}catch(Exception expectedRejection){if(keys.presented()==null)throw new IllegalStateException("尚未取得SSH主机密钥，请检查地址、网络和算法支持",expectedRejection);}
        finally{try{session.disconnect();}finally{sockets.close();}}
        byte[] key=keys.presented();HostKey parsed=new HostKey(profile.hostAlias(),key);
        boolean changed=!profile.pinnedKey.isEmpty()&&!java.security.MessageDigest.isEqual(Base64.getDecoder().decode(profile.pinnedKey),key);
        return new JSONObject().put("key",Base64.getEncoder().encodeToString(key)).put("fingerprint",SshProfile.fingerprint(key))
                .put("keyType",parsed.getType()).put("changed",changed).put("credentialsSent",false);
    }

    public static Connection connect(SshProfile profile,JSONObject credentials,RunControl control)throws Exception {
        if(profile.pinnedKey.isEmpty())throw new SecurityException("请先核对并确认SSH主机指纹");
        control.check();if(control.isPaused())throw new SecurityException("任务已暂停，未连接SSH");JSch client=new JSch();PinnedHostKeys keys=new PinnedHostKeys(profile,false);Session session=session(client,profile,keys);
        OwnedSocket sockets=new OwnedSocket(control);session.setSocketFactory(sockets);
        byte[] privateKey=null,passphrase=null;
        if(profile.authType.equals("password")){
            String password=credentials.optString("password");if(password.isEmpty())throw new IllegalArgumentException("此连接尚未保存密码");session.setPassword(password);
        }else{
            String key=credentials.optString("privateKey");if(key.isEmpty())throw new IllegalArgumentException("此连接尚未保存私钥");
            privateKey=key.getBytes(StandardCharsets.UTF_8);passphrase=credentials.optString("passphrase").getBytes(StandardCharsets.UTF_8);
            try{client.addIdentity(profile.id,privateKey,null,passphrase);}finally{Arrays.fill(privateKey,(byte)0);Arrays.fill(passphrase,(byte)0);}
        }
        Runnable cancel=()->{sockets.close();session.disconnect();};control.onCancel(cancel);
        try{control.check();if(control.isPaused())throw new SecurityException("任务已暂停，未连接SSH");session.connect(10000);control.check();return new Connection(profile,session,control,cancel,sockets);}
        catch(Exception failed){try{session.disconnect();}finally{sockets.close();control.removeCancelAction(cancel);}byte[] actual=keys.presented();
            if(actual!=null&&!java.security.MessageDigest.isEqual(Base64.getDecoder().decode(profile.pinnedKey),actual))throw new SecurityException("SSH主机密钥发生变化，已在认证前阻断连接，请主人独立核对");
            if(control.isCancelled())throw new InterruptedException("SSH连接已取消");
            throw new IllegalStateException("SSH连接或认证失败，请检查网络、凭据与所支持的算法");
        }finally{try{client.removeAllIdentity();}catch(Exception ignored){}}
    }

    public static final class Connection implements AutoCloseable {
        final SshProfile profile;final Session session;final RunControl control;final Runnable cancel;
        final OwnedSocket sockets;
        private final AtomicBoolean closed=new AtomicBoolean();
        Connection(SshProfile profile,Session session,RunControl control,Runnable cancel,OwnedSocket sockets){this.profile=profile;this.session=session;this.control=control;this.cancel=cancel;this.sockets=sockets;}
        @Override public void close(){try{if(closed.compareAndSet(false,true))session.disconnect();}finally{sockets.close();control.removeCancelAction(cancel);}}
        public boolean localClosed(){return closed.get()&&!session.isConnected()&&sockets.localClosed();}
        private void check()throws Exception {control.check();if(closed.get())throw new IllegalStateException("SSH连接已关闭");if(control.isPaused())throw new SecurityException("任务已暂停，未派发新的SSH操作");}
        private ChannelSftp sftp()throws Exception {check();ChannelSftp channel=(ChannelSftp)session.openChannel("sftp");try{check();channel.connect(10000);check();return channel;}catch(Exception failure){channel.disconnect();throw failure;}}

        public JSONObject exec(String command,int timeoutSeconds)throws Exception {
            return execute(command,timeoutSeconds,MAX_OUTPUT,true);
        }
        /** Same connection, cancellation and host-key fences as every other SSH operation. */
        public JSONObject doctor()throws Exception {
            return SshEnvironmentDoctor.report(execute(SshEnvironmentDoctor.script(profile),SshEnvironmentDoctor.TIMEOUT_SECONDS,SshEnvironmentDoctor.MAX_OUTPUT,false),profile);
        }
        private JSONObject execute(String command,int timeoutSeconds,int limit,boolean inWorkspace)throws Exception {
            if(command==null||command.trim().isEmpty()||command.length()>12000||command.indexOf('\0')>=0)throw new IllegalArgumentException("请提供有效的POSIX命令");
            if(timeoutSeconds<1||timeoutSeconds>300)throw new IllegalArgumentException("SSH命令时限需为1–300秒");
            check();
            ChannelExec channel=(ChannelExec)session.openChannel("exec");boolean dispatched=false;
            ByteArrayOutputStream stdout=new ByteArrayOutputStream(),stderr=new ByteArrayOutputStream();
            try{
                channel.setCommand((inWorkspace?"cd -- "+SshProfile.quote(profile.root)+" && exec sh -c ":"ENV= BASH_ENV= exec /bin/sh -c ")+SshProfile.quote(command));channel.setInputStream(null);
                channel.setAgentForwarding(false);channel.setXForwarding(false);
                InputStream output=channel.getInputStream(),errors=channel.getErrStream();check();dispatched=true;channel.connect(10000);
                long deadline=System.nanoTime()+timeoutSeconds*1_000_000_000L;
                while(true){
                    check();drainReceived(output,stdout,limit,"stdout");drainReceived(errors,stderr,limit,"stderr");
                    if(channel.isClosed()){drainReceived(output,stdout,limit,"stdout");drainReceived(errors,stderr,limit,"stderr");int exit=channel.getExitStatus();
                        if(exit<0)return incomplete("未取得SSH命令退出回执",stdout,stderr,limit,"missing_exit",null);
                        return returnedOutput(receipt(exit==0?"completed":"failed").put("exitCode",exit)
                                .put("accepted",true).put("verified",false).put("message",exit==0?"命令已返回退出回执，请核对业务结果":"远端命令返回非零退出码"),stdout,stderr,limit,true,"exit",null);
                    }
                    if(!session.isConnected()||System.nanoTime()>=deadline)return incomplete("连接断开或执行超时；远端命令可能仍在运行，请核对后再操作",stdout,stderr,limit,session.isConnected()?"timeout":"connection_lost",null);
                    Thread.sleep(25);
                }
            }catch(Exception failure){if(dispatched){OutputLimitExceeded overflow=failure instanceof OutputLimitExceeded?(OutputLimitExceeded)failure:null;String reason=overflow!=null?"output_limit":control.isCancelled()||failure instanceof InterruptedException?"interrupted":!session.isConnected()?"connection_lost":"read_unconfirmed";return incomplete(overflow!=null?"SSH输出超过本机收集上限；已收前缀保留，远端结果待核对，未重试":control.isCancelled()?"已关闭本机连接；无法确认远端进程是否终止":"命令已派发但回执不完整，请先核对远端结果",stdout,stderr,limit,reason,overflow);}throw failure;}
            finally{channel.disconnect();}
        }
        private JSONObject incomplete(String message,ByteArrayOutputStream stdout,ByteArrayOutputStream stderr,int limit,String reason,OutputLimitExceeded overflow)throws Exception {return returnedOutput(unknown(message,true).put("cancelled",control.isCancelled()),stdout,stderr,limit,false,reason,overflow);}
        private JSONObject returnedOutput(JSONObject result,ByteArrayOutputStream stdout,ByteArrayOutputStream stderr,int limit,boolean complete,String reason,OutputLimitExceeded overflow)throws Exception {
            JSONObject streams=new JSONObject();for(String name:new String[]{"stdout","stderr"}){int retained=name.equals("stdout")?stdout.size():stderr.size();boolean observed=overflow!=null&&name.equals(overflow.stream);JSONObject range=new JSONObject().put("fromByte",0).put("toByteExclusive",retained).put("retainedBytes",retained).put("limitBytes",limit).put("complete",complete).put("truncated",observed?true:complete?false:JSONObject.NULL);if(observed)range.put("observedBytesAtLeast",overflow.observedBytesAtLeast);streams.put(name,range);}
            return result.put("stdout",stdout.toString("UTF-8")).put("stderr",stderr.toString("UTF-8")).put("outputComplete",complete).put("outputTruncated",overflow!=null?true:complete?false:JSONObject.NULL)
                    .put("nativeOutputRange",new JSONObject().put("source","ssh_native_received_prefix").put("encoding","UTF-8-with-replacement").put("losslessWireBytes",false).put("termination",reason).put("streams",streams));
        }

        public JSONObject listFiles(String relative)throws Exception {
            ChannelSftp channel=sftp();try{
                String path=path(channel,relative,true,false);JSONArray files=new JSONArray();
                java.util.Vector<ChannelSftp.LsEntry> entries=channel.ls(path);
                for(ChannelSftp.LsEntry entry:entries){String name=entry.getFilename();if(name.equals(".")||name.equals(".."))continue;if(files.length()>=200)throw new IllegalStateException("目录超过200项，请缩小范围");SftpATTRS attributes=entry.getAttrs();files.put(new JSONObject().put("name",name).put("directory",attributes.isDir()).put("symlink",attributes.isLink()).put("bytes",attributes.getSize()));}
                return receipt("completed").put("path",relative).put("files",files).put("readOnly",true);
            }finally{channel.disconnect();}
        }
        public JSONObject readFile(String relative)throws Exception {
            ChannelSftp channel=sftp();try{String path=path(channel,relative,false,false);byte[] bytes=bytes(channel,path);
                String content=StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
                return receipt("completed").put("path",relative).put("content",content).put("bytes",bytes.length).put("version","sha256:"+SshProfile.hash(bytes)).put("readOnly",true);
            }finally{channel.disconnect();}
        }
        public JSONObject writeFile(String relative,String content,String expected,boolean cooperativeAccepted)throws Exception {
            if(content==null||content.getBytes(StandardCharsets.UTF_8).length>MAX_FILE)throw new IllegalArgumentException("文件需为256KiB以内UTF-8文本");
            if(expected==null||!expected.matches("absent|sha256:[a-f0-9]{64}"))throw new IllegalArgumentException("请先读取文件版本，再保存修改");
            if(!cooperativeAccepted)throw new SecurityException("标准SFTP不提供全局原子CAS；请主人确认采用协作锁与版本校验写入");
            ChannelSftp channel=sftp();String target="",temporary="",lock="";boolean ownsLock=false,mutating=false;
            try{
                target=path(channel,relative,false,true);
                if(channel.getExtension("posix-rename@openssh.com")==null)throw new IllegalStateException("服务器未提供POSIX原子rename扩展，未写入文件");
                lock=join(profile.root,".pocketworld-lock-"+SshProfile.hash(relative.getBytes(StandardCharsets.UTF_8)));
                check();mutating=true;channel.mkdir(lock);ownsLock=true;check();
                String before=version(channel,target);if(!expected.equals(before))return receipt("failed").put("code","version_conflict").put("accepted",false).put("currentVersion",before).put("message","文件版本已变化，未覆盖目标文件");
                int permissions=before.equals("absent")?0600:channel.lstat(target).getPermissions()&0777;
                int slash=target.lastIndexOf('/');temporary=target.substring(0,slash+1)+".pocketworld-write-"+UUID.randomUUID()+".tmp";
                byte[] data=content.getBytes(StandardCharsets.UTF_8);check();
                try(java.io.OutputStream destination=channel.put(temporary)){check();channel.chmod(0600,temporary);check();destination.write(data);}
                check();channel.chmod(permissions,temporary);
                if(!expected.equals(version(channel,target)))return receipt("failed").put("code","version_conflict").put("accepted",false).put("message","写入前发现其他修改，未覆盖目标文件");
                check();channel.rename(temporary,target);temporary="";
                String after=version(channel,target),wanted="sha256:"+SshProfile.hash(data);
                if(!after.equals(wanted))return unknown("写入后的版本与本次内容不一致，请核对目标文件",true);
                return receipt("completed").put("path",relative).put("version",after).put("accepted",true).put("verified",false)
                        .put("cooperativeConditionalWrite",true).put("externalWriterRacePossible",true).put("message","已在协作锁内检查版本并替换文件；外部不遵守锁的写入仍可能竞争");
            }catch(Exception failure){if(mutating)return unknown("SFTP写入或协作锁回执不完整，未重试；请核对目标、临时文件和锁目录",true).put("path",relative).put("stagingPath",temporary).put("lockPath",lock);throw failure;}
            finally{if(session.isConnected()){if(!temporary.isEmpty())try{channel.rm(temporary);}catch(Exception ignored){}if(ownsLock)try{channel.rmdir(lock);}catch(Exception ignored){}}channel.disconnect();}
        }
        /** Native-owner binary metadata. The digest is over actual streamed bytes, not stat alone. */
        public JSONObject inspectFile(String relative)throws Exception {
            ChannelSftp channel=sftp();try {
                String target=path(channel,relative,false,true);SftpATTRS before;
                try{before=channel.lstat(target);}catch(SftpException missing){if(missing.id==ChannelSftp.SSH_FX_NO_SUCH_FILE)return receipt("completed").put("path",relative).put("exists",false).put("bytes",0).put("version","absent").put("readOnly",true);throw missing;}
                String version=binaryVersion(channel,target,before);return receipt("completed").put("path",relative).put("exists",true).put("bytes",before.getSize()).put("version",version)
                        .put("modifiedAtSeconds",Integer.toUnsignedLong(before.getMTime())).put("readOnly",true).put("streamedHash",true).put("atomicRemoteSnapshot",false).put("maxTransferBytes",MAX_TRANSFER);
            }catch(InputChanged changed){return receipt("failed").put("code","source_changed").put("accepted",false).put("readOnly",true).put("message",changed.getMessage());}
            finally{channel.disconnect();}
        }
        /** Same native SFTP connection, verified EOF before the caller can commit local bytes. */
        public BinaryRead openBinaryRead(String relative,String expectedVersion)throws Exception {
            if(expectedVersion==null||!expectedVersion.matches("sha256:[a-f0-9]{64}"))throw new IllegalArgumentException("请先读取该远端文件的准确SHA256版本");
            ChannelSftp channel=sftp();try{String target=path(channel,relative,false,false);SftpATTRS before=channel.lstat(target);binaryAttributes(before);return new BinaryRead(channel,target,relative,before,expectedVersion,channel.get(target));}
            catch(Exception failure){channel.disconnect();throw failure;}
        }
        public final class BinaryRead extends InputStream {
            private final ChannelSftp channel;private final String target,relative,expected;private final SftpATTRS before;private final InputStream source;private final java.security.MessageDigest hash;private long count;private boolean verified,closed;
            private BinaryRead(ChannelSftp channel,String target,String relative,SftpATTRS before,String expected,InputStream source)throws Exception{this.channel=channel;this.target=target;this.relative=relative;this.before=before;this.expected=expected;this.source=source;hash=java.security.MessageDigest.getInstance("SHA-256");}
            @Override public int read()throws IOException {byte[] single=new byte[1];int n=read(single,0,1);return n<0?-1:single[0]&255;}
            @Override public int read(byte[] data,int offset,int length)throws IOException {
                if(closed)throw new IOException("原SFTP读取已关闭");if(length==0)return 0;
                try {check();int n=source.read(data,offset,length);check();if(n<0){if(!verified){if(count!=before.getSize()||!expected.equals("sha256:"+hex(hash.digest())))throw new InputChanged("远端实际文件不再是刚才所选版本，未保存其他字节");String actual=path(channel,relative,false,false);SftpATTRS after=channel.lstat(actual);if(!target.equals(actual)||!sameAttributes(before,after))throw new InputChanged("远端文件在读取期间变化，未提交旧基线的副本");verified=true;}return -1;}if(n>0){count+=n;if(count>MAX_TRANSFER||count>before.getSize())throw new InputChanged("远端实际文件增长或超出20MiB，未保存部分文件");hash.update(data,offset,n);}return n;}
                catch(IOException failure){throw failure;}catch(Exception unavailable){throw new IOException("原SFTP读取被取消或未完整确认",unavailable);}
            }
            public long bytesRead(){return count;}
            public JSONObject receipt()throws Exception{if(!verified)throw new SecurityException("原SFTP文件尚未完整校验");return DirectSshClient.receipt("completed").put("path",relative).put("bytes",count).put("version",expected).put("readOnly",true).put("verifiedBytes",true).put("atomicRemoteSnapshot",false);}
            @Override public void close(){if(closed)return;closed=true;try{source.close();}catch(IOException ignored){}finally{channel.disconnect();}}
        }
        /** Streaming native attachment upload. It never accepts inline/base64 binary contents. */
        public JSONObject uploadBinary(String relative,InputStream source,long size,String expectedSourceSha256,String expectedRemoteVersion,boolean cooperativeAccepted,RunControl.AuthorityCheck sourceCurrent,RunControl.AuthorityCheck sourceBeforeCommit)throws Exception {
            if(source==null||size<0||size>MAX_TRANSFER||expectedSourceSha256==null||!expectedSourceSha256.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("仅支持原私人附件的20MiB以内固定版本");
            if(expectedRemoteVersion==null||!expectedRemoteVersion.matches("absent|sha256:[a-f0-9]{64}"))throw new IllegalArgumentException("请先核对远端现有文件版本或准确不存在状态");
            if(!cooperativeAccepted)throw new SecurityException("请明确确认标准SFTP协作锁/版本写入不是全局原子CAS或OS沙箱");
            ChannelSftp channel=sftp();String target="",staging="",lock="",phase="checking";long sent=0;boolean ownsLock=false,mutating=false,renameSent=false;JSONObject result=null;
            try {
                target=path(channel,relative,false,true);if(!"1".equals(channel.getExtension("posix-rename@openssh.com")))throw new IllegalStateException("服务器没有已支持的POSIX原子rename扩展，未上传或替换文件");
                sourceCurrent.check();check();lock=join(profile.root,".pocketworld-lock-"+SshProfile.hash(relative.getBytes(StandardCharsets.UTF_8)));mutating=true;channel.mkdir(lock);ownsLock=true;phase="lock_acquired";
                String before=binaryVersion(channel,target);if(!expectedRemoteVersion.equals(before)){result=receipt("failed").put("code","version_conflict").put("accepted",false).put("path",relative).put("currentVersion",before).put("targetReplacementSent",false).put("message","远端文件版本已变化，没有覆盖目标");return result;}
                int permissions=before.equals("absent")?0600:channel.lstat(target).getPermissions()&0777;int slash=target.lastIndexOf('/');staging=target.substring(0,slash+1)+".pocketworld-upload-"+UUID.randomUUID()+".part";java.security.MessageDigest digest=java.security.MessageDigest.getInstance("SHA-256");phase="streaming_to_staging";
                try(OutputStream output=channel.put(staging)){check();channel.chmod(0600,staging);byte[] chunk=new byte[32768];while(true){check();sourceCurrent.check();int n=source.read(chunk);if(n<0)break;if(n>0)sent+=n;check();sourceCurrent.check();if(n==0)continue;if(sent>size||sent>MAX_TRANSFER)throw new InputChanged("原手机附件在上传期间变化，未替换远端文件");digest.update(chunk,0,n);output.write(chunk,0,n);}check();output.flush();}
                phase="staging_close_acknowledged";if(sent!=size||!hex(digest.digest()).equals(expectedSourceSha256))throw new InputChanged("原手机附件字节不再匹配所选SHA256，未替换远端目标");
                sourceCurrent.check();check();String wanted="sha256:"+expectedSourceSha256;if(!wanted.equals(binaryVersion(channel,staging)))throw new InputChanged("远端临时文件读回摘要不匹配，没有替换目标");
                channel.chmod(permissions,staging);if(!expectedRemoteVersion.equals(binaryVersion(channel,target))){result=receipt("failed").put("code","version_conflict").put("accepted",false).put("path",relative).put("targetReplacementSent",false).put("message","提交前远端文件又有修改，未覆盖目标");return result;}
                // Re-hash the original local Book version immediately before the irreversible rename.
                sourceBeforeCommit.check();sourceCurrent.check();check();phase="rename_sending";renameSent=true;channel.rename(staging,target);staging="";phase="rename_acknowledged";
                String after=binaryVersion(channel,target);if(!wanted.equals(after))result=unknown("替换后远端实际摘要不同，结果未知；没有重试",true).put("currentVersion",after);
                else {phase="readback_verified";result=receipt("completed").put("accepted",true).put("version",after).put("verifiedBytes",true).put("verifiedBusinessSuccess",false).put("cooperativeConditionalWrite",true).put("externalWriterRacePossible",true).put("workspaceIsSandbox",false).put("message","原手机附件已流式上传、临时文件与目标实际SHA256已读回核对；不代表项目测试通过或全局原子CAS");}
            }catch(Exception failure){if(!mutating)throw failure;result=unknown("SFTP上传/暂存/替换未取得完整回执，未重试；请核对原任务、目标和暂存文件",renameSent).put("partialRemoteFilePossible",!staging.isEmpty()).put("sourceChanged",failure instanceof InputChanged).put("accepted",JSONObject.NULL);}
            finally {
                if(result!=null){result.put("path",relative).put("bytesReadFromLocal",sent).put("expectedBytes",size).put("sourceSha256",expectedSourceSha256).put("expectedRemoteVersion",expectedRemoteVersion).put("phase",phase).put("targetReplacementSent",renameSent).put("stagingPath",staging).put("lockPath",lock);}
                boolean stageClean=staging.isEmpty(),lockClean=!mutating;
                if(session.isConnected()){if(!staging.isEmpty())try{channel.rm(staging);stageClean=true;}catch(Exception ignored){}if(ownsLock)try{channel.rmdir(lock);lockClean=true;}catch(Exception ignored){}}
                if(result!=null){result.put("stagingCleanupConfirmed",stageClean).put("lockCleanupConfirmed",lockClean).put("lockOwnershipConfirmed",ownsLock).put("cleanupReviewRequired",!stageClean||!lockClean).put("automaticRetry",false);if(!stageClean||!lockClean)result.put("status","unknown").put("accepted",JSONObject.NULL).put("message","目标替换与文件校验事实已保留，但本次暂存文件或协作锁清理尚未确认；请核对原任务，不自动重试");}channel.disconnect();
            }
            return result;
        }
        private String binaryVersion(ChannelSftp channel,String target)throws Exception {try{return binaryVersion(channel,target,channel.lstat(target));}catch(SftpException missing){if(missing.id==ChannelSftp.SSH_FX_NO_SUCH_FILE)return "absent";throw missing;}}
        private String binaryVersion(ChannelSftp channel,String target,SftpATTRS before)throws Exception {
            binaryAttributes(before);java.security.MessageDigest hash=java.security.MessageDigest.getInstance("SHA-256");long count=0;
            try(InputStream input=channel.get(target)){byte[] bytes=new byte[32768];for(int n;(n=input.read(bytes))!=-1;){check();if(n==0)continue;count+=n;if(count>MAX_TRANSFER||count>before.getSize())throw new InputChanged("远端文件读取期间增长或超出20MiB");hash.update(bytes,0,n);}}
            check();SftpATTRS after=channel.lstat(target);if(count!=before.getSize()||!sameAttributes(before,after))throw new InputChanged("远端文件读取期间变化，请重新核对真实版本");return "sha256:"+hex(hash.digest());
        }
        private void binaryAttributes(SftpATTRS attrs)throws Exception {if(!attrs.isReg()||attrs.getSize()<0||attrs.getSize()>MAX_TRANSFER)throw new IllegalArgumentException("只支持非符号链接的20MiB以内普通文件");}
        private boolean sameAttributes(SftpATTRS a,SftpATTRS b){return b.isReg()&&a.getSize()==b.getSize()&&a.getMTime()==b.getMTime()&&a.getPermissions()==b.getPermissions();}

        private String path(ChannelSftp channel,String relative,boolean directory,boolean allowMissingFinal)throws Exception {
            SshProfile.relative(relative,directory);String full=join(profile.root,relative);String current="";String[] components=full.split("/");
            for(int i=1;i<components.length;i++){if(components[i].isEmpty())continue;current+="/"+components[i];
                try{SftpATTRS attributes=channel.lstat(current);if(attributes.isLink())throw new SecurityException("SFTP工作区不跟随符号链接");if(i<components.length-1&&!attributes.isDir())throw new IllegalArgumentException("父路径不是目录");}
                catch(SftpException missing){if(allowMissingFinal&&i==components.length-1&&missing.id==ChannelSftp.SSH_FX_NO_SUCH_FILE)break;throw missing;}
            }
            if(directory&&!channel.stat(full).isDir())throw new IllegalArgumentException("该路径不是目录");return full;
        }
        private byte[] bytes(ChannelSftp channel,String path)throws Exception {
            SftpATTRS stat=channel.lstat(path);if(stat.isDir()||stat.isLink()||stat.getSize()>MAX_FILE)throw new IllegalArgumentException("文件类型不支持或超过256KiB");
            try(InputStream input=channel.get(path)){ByteArrayOutputStream output=new ByteArrayOutputStream();byte[] chunk=new byte[4096];int n;while((n=input.read(chunk))!=-1){control.check();if(output.size()+n>MAX_FILE)throw new IllegalArgumentException("文件超过256KiB");output.write(chunk,0,n);}return output.toByteArray();}
        }
        private String version(ChannelSftp channel,String path)throws Exception {try{return "sha256:"+SshProfile.hash(bytes(channel,path));}catch(SftpException missing){if(missing.id==ChannelSftp.SSH_FX_NO_SUCH_FILE)return "absent";throw missing;}}
        /** Exact local loopback listener owned by this SSH session; no remote daemon is started. */
        Forward openPreviewForward(int remotePort,int seconds)throws Exception {
            if(remotePort<1024||remotePort>65535||seconds<10||seconds>600)throw new IllegalArgumentException("预览只允许明确的开发端口1024–65535、10–600秒");
            check();int local=session.setPortForwardingL("127.0.0.1",0,"127.0.0.1",remotePort);try{check();return new Forward(this,local,remotePort,System.currentTimeMillis()+seconds*1000L);}catch(Exception revoked){close();throw revoked;}
        }
        public Pty openPty(int columns,int rows,int seconds)throws Exception {
            if(columns<20||columns>300||rows<5||rows>120||seconds<10||seconds>600)throw new IllegalArgumentException("终端尺寸或会话时限无效");
            check();
            ChannelExec channel=(ChannelExec)session.openChannel("exec");channel.setPty(true);channel.setPtyType("xterm-256color",columns,rows,0,0);
            String marker="PW_PTY_READY_"+UUID.randomUUID().toString().replace("-","");
            channel.setCommand("cd -- "+SshProfile.quote(profile.root)+" && if test -t 0; then printf '%s\\n' "+SshProfile.quote(marker)+"; exec /bin/sh -i; else exit 125; fi");
            channel.setAgentForwarding(false);channel.setXForwarding(false);
            InputStream input=channel.getInputStream();java.io.OutputStream output=channel.getOutputStream();check();
            try{channel.connect(10000);
            ByteArrayOutputStream prefix=new ByteArrayOutputStream();long deadline=System.nanoTime()+5_000_000_000L;
            byte[] markerBytes=marker.getBytes(StandardCharsets.US_ASCII);
            while(System.nanoTime()<deadline){check();drain(input,prefix,65536);byte[] all=prefix.toByteArray();int found=indexOf(all,markerBytes);
                if(found>=0){int end=found+markerBytes.length;while(end<all.length&&(all[end]=='\r'||all[end]=='\n'))end++;return new Pty(this,channel,input,output,Arrays.copyOfRange(all,end,all.length),System.currentTimeMillis()+seconds*1000L);}
                if(channel.isClosed()||!session.isConnected())break;Thread.sleep(20);
            }
            channel.disconnect();throw new IllegalStateException("未取得远端test -t确认，不能声称PTY已建立");
            }catch(Exception failure){channel.disconnect();throw new Uncertain("PTY请求已派发，但没有取得可用会话回执");}
        }
    }

    /** Retain our own socket so early cancellation and cleanup do not rely on a session flag. */
    static final class OwnedSocket implements com.jcraft.jsch.SocketFactory,AutoCloseable {
        private final RunControl control;private volatile boolean closed;private volatile Socket socket;
        OwnedSocket(RunControl control){this.control=control;}
        @Override public Socket createSocket(String host,int port)throws IOException {
            Socket created=new Socket();synchronized(this){if(closed||control!=null&&control.isCancelled()){created.close();throw new IOException("SSH连接已取消");}socket=created;}
            try{InetSocketAddress target=new InetSocketAddress(host,port);if(control!=null){control.check();if(control.isPaused())throw new IOException("宿主任务已暂停");}if(closed)throw new IOException("SSH连接已关闭");created.connect(target,10000);if(control!=null)control.check();if(closed)throw new IOException("SSH连接已关闭");return created;}
            catch(Exception failure){close();throw new IOException("SSH套接字未建立",failure);}
        }
        @Override public InputStream getInputStream(Socket value)throws IOException {return value.getInputStream();}
        @Override public OutputStream getOutputStream(Socket value)throws IOException {return value.getOutputStream();}
        @Override public synchronized void close(){closed=true;if(socket!=null)try{socket.close();}catch(IOException ignored){}}
        synchronized boolean localClosed(){return closed&&(socket==null||socket.isClosed());}
    }

    public static final class Pty implements AutoCloseable {
        private final Connection connection;private final ChannelExec channel;private final InputStream input;private final java.io.OutputStream output;
        private final byte[] ring=new byte[MAX_OUTPUT];private long first,next;private final long expiresAt;private volatile String status="running";private volatile int exitCode=-1;
        Pty(Connection connection,ChannelExec channel,InputStream input,java.io.OutputStream output,byte[] initial,long expiresAt){this.connection=connection;this.channel=channel;this.input=input;this.output=output;this.expiresAt=expiresAt;append(initial);Thread reader=new Thread(this::pump,"ssh-pty-output");reader.setDaemon(true);reader.start();}
        private void pump(){try{byte[] buffer=new byte[4096];while(true){connection.control.check();int count=input.read(buffer);if(count<0)break;append(Arrays.copyOf(buffer,count));}
            exitCode=channel.getExitStatus();status=exitCode>=0?"completed":"unknown";
        }catch(Exception disconnected){status="unknown";}finally{channel.disconnect();connection.close();}}
        private synchronized void append(byte[] value){for(byte b:value){ring[(int)(next%ring.length)]=b;next++;if(next-first>ring.length)first=next-ring.length;}}
        public synchronized JSONObject read(long cursor)throws Exception {
            if(System.currentTimeMillis()>=expiresAt&&status.equals("running"))close();
            if(cursor<0||cursor>next)throw new IllegalArgumentException("终端输出位置无效");long start=Math.max(first,cursor);int count=(int)Math.min(65536,next-start);byte[] value=new byte[count];for(int i=0;i<count;i++)value[i]=ring[(int)((start+i)%ring.length)];
            return receipt(status).put("dataBase64",Base64.getEncoder().encodeToString(value)).put("cursor",start+count).put("truncated",cursor<first).put("exitCode",exitCode).put("expiresAt",expiresAt).put("ptyConfirmed",true);
        }
        public synchronized JSONObject write(String encoded)throws Exception {
            live();byte[] value;try{value=Base64.getDecoder().decode(encoded);}catch(Exception bad){throw new IllegalArgumentException("终端输入编码无效");}
            if(value.length>65536)throw new IllegalArgumentException("单次终端输入超过64KiB");
            try{output.write(value);output.flush();return receipt("running").put("accepted",true).put("verified",false);}
            catch(Exception unknown){status="unknown";close();return unknown("终端输入是否被处理尚未确认，请先读取输出核对",true);}
        }
        public synchronized JSONObject resize(int columns,int rows)throws Exception {live();if(columns<20||columns>300||rows<5||rows>120)throw new IllegalArgumentException("终端尺寸无效");channel.setPtySize(columns,rows,0,0);return receipt("running").put("accepted",true).put("verified",false);}
        private void live()throws Exception {connection.check();if(!status.equals("running")||System.currentTimeMillis()>=expiresAt){close();throw new IllegalStateException("SSH终端会话已结束，不能重放输入");}}
        @Override public synchronized void close(){if(status.equals("running"))status="unknown";channel.disconnect();connection.close();}
        public String status(){return status;}
        public long expiresAt(){return expiresAt;}
        public boolean localClosed(){return connection.localClosed();}
    }
    static final class Forward implements AutoCloseable {
        final Connection connection;final int localPort,remotePort;final long expiresAt;private volatile boolean closed;
        Forward(Connection connection,int localPort,int remotePort,long expiresAt){this.connection=connection;this.localPort=localPort;this.remotePort=remotePort;this.expiresAt=expiresAt;}
        void requireCurrent()throws Exception{connection.check();if(closed||System.currentTimeMillis()>=expiresAt){close();throw new SecurityException("原SSH预览隧道已关闭或到期");}}
        JSONObject metadata()throws Exception{boolean active=!closed&&!connection.localClosed()&&connection.session.isConnected();return receipt(active?"running":"completed").put("localBaseUrl","http://127.0.0.1:"+localPort+"/").put("remoteHost","127.0.0.1").put("remotePort",remotePort).put("localPort",localPort).put("expiresAt",expiresAt).put("forwardingEstablished",active).put("localResourcesClosed",connection.localClosed()).put("remoteServerStopped",false).put("verified",false);}
        @Override public void close(){closed=true;connection.close();} // closes listener AND every established channel, including upgraded WS
    }
    static void drain(InputStream input,ByteArrayOutputStream output,int limit)throws Exception {byte[] chunk=new byte[4096];while(input.available()>0){int n=input.read(chunk,0,Math.min(chunk.length,input.available()));if(n<0)return;if(output.size()+n>limit)throw new IllegalStateException("SSH输出超过限制，请缩小命令结果");output.write(chunk,0,n);}}
    /** Keep the bounded bytes already received, including the last in-range prefix. PTY's
     * existing ring/bootstrap drain and its permissions remain a separate unchanged path. */
    private static void drainReceived(InputStream input,ByteArrayOutputStream output,int limit,String stream)throws Exception {byte[] chunk=new byte[4096];while(input.available()>0){int n=input.read(chunk,0,Math.min(chunk.length,input.available()));if(n<=0)return;int observed=output.size()+n,keep=Math.min(n,Math.max(0,limit-output.size()));if(keep>0)output.write(chunk,0,keep);if(keep<n)throw new OutputLimitExceeded(stream,observed);}}
    private static int indexOf(byte[] value,byte[] wanted){outer:for(int i=0;i<=value.length-wanted.length;i++){for(int j=0;j<wanted.length;j++)if(value[i+j]!=wanted[j])continue outer;return i;}return -1;}
    static String join(String root,String relative){return relative.isEmpty()?root:root.equals("/")?"/"+relative:root+"/"+relative;}
    static JSONObject receipt(String status)throws Exception{return new JSONObject().put("status",status).put("transport","direct_ssh").put("remotePersistentJob",false);}
    static JSONObject unknown(String message,boolean dispatched)throws Exception{return receipt("unknown").put("accepted",dispatched?JSONObject.NULL:false).put("verified",false).put("remoteTerminationConfirmed",false).put("message",message);}
    private static String hex(byte[] bytes){StringBuilder value=new StringBuilder();for(byte b:bytes)value.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return value.toString();}
    static final class InputChanged extends IOException {InputChanged(String message){super(message);}}
    private static final class OutputLimitExceeded extends IOException {final String stream;final int observedBytesAtLeast;OutputLimitExceeded(String stream,int observed){super("SSH native output limit exceeded");this.stream=stream;observedBytesAtLeast=observed;}}
    static final class Uncertain extends Exception{Uncertain(String message){super(message);}}
}
