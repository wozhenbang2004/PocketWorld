package dev.pocketengine.features.ssh;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class SshPolicyTest {
    private JSONObject value()throws Exception{return new JSONObject().put("id","profile-a").put("name","我的服务器").put("host","Example.COM").put("port",22).put("username","developer").put("workspace","/home/developer/project").put("authType","key");}
    @Test public void endpointUserAndWorkspaceBindSecretsAndTrustIndependently()throws Exception {
        SshProfile profile=new SshProfile(value());assertEquals("example.com",profile.host);
        for(String field:new String[]{"host","username","workspace","port"}){
            JSONObject changed=value();changed.put(field,field.equals("port")?2222:field.equals("host")?"other.example":field.equals("username")?"other":"/another");
            SshProfile other=new SshProfile(changed);assertNotEquals(profile.bindingHash(),other.bindingHash());assertNotEquals(profile.credentialSlot(),other.credentialSlot());
        }
        assertNotEquals(profile.credentialSlot(),new SshProfile(value().put("id","profile-b")).credentialSlot());
    }
    @Test public void staleOrMissingHostGrantedBindingIsRejectedBeforeAuthentication()throws Exception {
        SshProfile original=new SshProfile(value()),moved=new SshProfile(value().put("workspace","/different"));
        original.requireBinding(original.bindingHash());
        assertThrows(SecurityException.class,()->moved.requireBinding(original.bindingHash()));
        assertThrows(SecurityException.class,()->original.requireBinding(""));assertThrows(SecurityException.class,()->original.requireBinding(null));
    }
    @Test public void alternateDnsAndIpv6AliasesCannotCreateASecondLeaseForTheSamePinnedAccount()throws Exception {
        String key=Base64.getEncoder().encodeToString("fixture-host-key-1111111111111111".getBytes(StandardCharsets.UTF_8));
        SshProfile original=new SshProfile(value().put("pinnedKey",key));
        for(String alias:new String[]{"example.com.","127.0.0.1","::1"}){
            SshProfile other=new SshProfile(value().put("id","alias").put("host",alias).put("port",2222).put("pinnedKey",key));
            assertEquals(original.accountKey(),other.accountKey());assertNotEquals(original.bindingHash(),other.bindingHash());
        }
        assertThrows(SecurityException.class,()->new SshProfile(value()).accountKey());
    }
    @Test public void filePathsCannotEscapeOrInvokeSftpGlobs() {
        for(String path:new String[]{"../outside","/absolute","a/../b","a//b","a/./b","a\\b","a*","a?","[x]","a\nb\nc"})
            assertThrows(IllegalArgumentException.class,()->SshProfile.relative(path,false));
        assertEquals("文档/版本.txt",SshProfile.relative("文档/版本.txt",false));assertEquals("",SshProfile.relative("",true));
    }
    @Test public void shellQuotingPreservesLiteralApostrophes() {
        assertEquals("'a'\"'\"'b'",SshProfile.quote("a'b"));
        assertThrows(IllegalArgumentException.class,()->SshProfile.quote("a\0b"));
    }
    @Test public void unknownAndChangedHostKeysNeverBecomeTrustedByProtocol()throws Exception {
        SshProfile profile=new SshProfile(value());byte[] key="fixture-host-key-1111111111111111".getBytes(StandardCharsets.UTF_8);
        PinnedHostKeys probe=new PinnedHostKeys(profile,true);assertEquals(PinnedHostKeys.NOT_INCLUDED,probe.check(profile.hostAlias(),key));
        assertArrayEquals(key,probe.presented());key[0]=0;assertNotEquals(key[0],probe.presented()[0]);
        SshProfile pinned=new SshProfile(value().put("pinnedKey",Base64.getEncoder().encodeToString(probe.presented())));
        PinnedHostKeys trusted=new PinnedHostKeys(pinned,false);assertEquals(PinnedHostKeys.OK,trusted.check(pinned.hostAlias(),probe.presented()));
        assertEquals(PinnedHostKeys.CHANGED,trusted.check(pinned.hostAlias(),key));assertEquals(PinnedHostKeys.CHANGED,trusted.check("different-alias",probe.presented()));
        assertThrows(SecurityException.class,()->trusted.remove(pinned.hostAlias(),"ssh-rsa"));
    }
    @Test public void blankSettingsRetainSecretsOnlyOnTheSameBoundProfile()throws Exception {
        SshProfile old=new SshProfile(value());JSONObject secret=new JSONObject().put("privateKey","fixture-key").put("passphrase","fixture-pass");
        JSONObject blank=new JSONObject().put("privateKey","").put("passphrase","");
        assertEquals("fixture-key",SshCredentials.merge(old,old,blank,secret).getString("privateKey"));
        SshProfile moved=new SshProfile(value().put("host","other.example"));assertEquals(0,SshCredentials.merge(old,moved,blank,secret).length());
        SshProfile other=new SshProfile(value().put("id","profile-b"));assertEquals(0,SshCredentials.merge(old,other,blank,secret).length());
    }
    @Test public void changingAuthMethodDoesNotCarryOldCredentials()throws Exception {
        SshProfile key=new SshProfile(value()),password=new SshProfile(value().put("authType","password"));
        assertEquals(0,SshCredentials.merge(key,password,new JSONObject(),new JSONObject().put("privateKey","fixture-key")).length());
    }
    @Test public void passphraseUpdateRetainsKeyAndNewKeyDoesNotInheritOldPassphrase()throws Exception {
        SshProfile profile=new SshProfile(value());JSONObject secret=new JSONObject().put("privateKey","old-key").put("passphrase","old-pass");
        JSONObject changed=SshCredentials.merge(profile,profile,new JSONObject().put("passphrase","new-pass"),secret);assertEquals("old-key",changed.getString("privateKey"));assertEquals("new-pass",changed.getString("passphrase"));
        JSONObject replaced=SshCredentials.merge(profile,profile,new JSONObject().put("privateKey","new-key"),secret);assertEquals("",replaced.getString("passphrase"));
    }
    @Test public void secretClearIsExplicitAndUnusedCredentialTypesAreRejected()throws Exception {
        SshProfile profile=new SshProfile(value());JSONObject secret=new JSONObject().put("privateKey","fixture-key");
        assertEquals(0,SshCredentials.merge(profile,profile,new JSONObject().put("clearCredentials",true),secret).length());
        assertThrows(IllegalArgumentException.class,()->SshCredentials.merge(profile,profile,new JSONObject().put("password","not-a-key"),secret));
    }
}
