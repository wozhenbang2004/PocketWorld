package dev.pocketengine.features.ssh;

import com.jcraft.jsch.HostKey;
import com.jcraft.jsch.HostKeyRepository;
import com.jcraft.jsch.UserInfo;
import java.security.MessageDigest;
import java.util.Base64;

/** No UserInfo prompt, no automatic trust. check happens before SSH user authentication. */
final class PinnedHostKeys implements HostKeyRepository {
    private final SshProfile profile;
    private final boolean probe;
    private volatile byte[] presented;
    PinnedHostKeys(SshProfile profile,boolean probe){this.profile=profile;this.probe=probe;}
    @Override public int check(String host,byte[] key){
        if(key==null||key.length<16||key.length>49152||!profile.hostAlias().equals(host))return CHANGED;
        presented=key.clone();
        if(probe||profile.pinnedKey.isEmpty())return NOT_INCLUDED;
        return MessageDigest.isEqual(Base64.getDecoder().decode(profile.pinnedKey),key)?OK:CHANGED;
    }
    byte[] presented(){return presented==null?null:presented.clone();}
    @Override public void add(HostKey key,UserInfo userinfo){throw new SecurityException("主机信任仅能由主人明确确认");}
    @Override public void remove(String host,String type){throw new SecurityException("主机信任不可由协议修改");}
    @Override public void remove(String host,String type,byte[] key){throw new SecurityException("主机信任不可由协议修改");}
    @Override public String getKnownHostsRepositoryID(){return profile.hostAlias();}
    @Override public HostKey[] getHostKey(){return getHostKey(null,null);}
    @Override public HostKey[] getHostKey(String host,String type){
        if(profile.pinnedKey.isEmpty()||host!=null&&!host.equals(profile.hostAlias()))return new HostKey[0];
        try{HostKey key=new HostKey(profile.hostAlias(),Base64.getDecoder().decode(profile.pinnedKey));return type==null||type.equals(key.getType())?new HostKey[]{key}:new HostKey[0];}catch(Exception invalid){return new HostKey[0];}
    }
}
